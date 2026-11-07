(ns hc.server.program.workspace-integration-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.core.workspace-gate.board :as wg-board]
            [hc.server.program.runtime :as program]
            [hc.server.program.workspace-port :as workspace-port])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def long-intent (apply str (repeat 200 "x")))

(defn- temp-dir
  []
  (.toFile (Files/createTempDirectory "hc-program-test"
                                      (make-array FileAttribute 0))))

(defn- delete-tree!
  [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File child (reverse (file-seq f))]
      (io/delete-file child true))))

(defn- with-workspace
  [f]
  (let [dir (temp-dir)]
    (try
      (f dir)
      (finally (delete-tree! dir)))))

(defn- make-runtime
  [dir & [opts]]
  (program/runtime
   (merge {:workspace (workspace-port/workspace-gate-port
                       {:root (.getPath ^java.io.File dir)})
           :max-source-bytes 100000}
          opts)))

(defn- exec!
  ([rt source] (exec! rt source {}))
  ([rt source opts]
   (program/evaluate-program! rt
                              (merge {:call-id (str (random-uuid))
                                      :role :clj-worker
                                      :agent-id "agent"
                                      :claim-id "claim"
                                      :task-id "task"
                                      :intent long-intent
                                      :source source}
                                     opts))))

(deftest read-stat-and-ls
  (with-workspace
    (fn [dir]
      (spit (io/file dir "a.txt") "hello")
      (let [rt (make-runtime dir)]
        (try
          (let [read (exec! rt "(defprogram read [] (cat {:path \"a.txt\"}))")]
            (is (= :COMPLETED (:status read)))
            (is (= "hello" (:value read)))
            (testing "read-only programs do not take a commit"
              (is (nil? (:commit read))))
            (is (map? (:read-set read))))
          (let [stat (exec! rt "(defprogram stat-it [] (stat {:path \"missing.txt\"}))")]
            (is (= :COMPLETED (:status stat)))
            (is (false? (get-in stat [:value :exists]))))
          (let [ls (exec! rt "(defprogram ls-it [] (ls {:path \"\"}))")]
            (is (= :COMPLETED (:status ls)))
            (is (some #{"a.txt"} (:value ls))))
          (finally (program/stop! rt)))))))

(deftest settle-forwards-the-task-id-to-the-board
  (with-workspace
    (fn [dir]
      (let [seen (atom nil)
            board (reify wg-board/BoardAdapter
                    (check-eligibility! [_ _] nil)
                    (begin-apply! [_ request _]
                      (reset! seen request)
                      {:action-id (:request-id request)
                       :request-id (:request-id request)})
                    (publish-applied! [_ _ _] {:published true})
                    (publish-failure! [_ _ _] {:published true}))
            port (workspace-port/workspace-gate-port
                  {:root (.getPath ^java.io.File dir) :board board})
            rt (program/runtime {:workspace port :max-source-bytes 100000})]
        (try
          (let [result (program/evaluate-program!
                        rt
                        {:call-id "board-settle"
                         :role :clj-worker
                         :agent-id "agent"
                         :claim-id "claim"
                         :task-id "task"
                         :intent long-intent
                         :source "(defprogram board-settle [] (write {:path \"a.txt\" :content \"x\"}))"})]
            (is (= :COMPLETED (:status result)))
            ;; The reference port must forward the whole settle request, not
            ;; just request/actor/claim/batch.
            (is (= "task" (:task-id @seen)))
            (is (= "board-settle" (:program-name @seen)))
            (is (= :applied (:phase @seen))))
          (finally (program/stop! rt)))))))

(deftest write-applies-under-the-protected-commit
  (with-workspace
    (fn [dir]
      (let [rt (make-runtime dir)]
        (try
          (let [result (exec! rt "(defprogram write-it []
                                  (write {:path \"b.txt\" :content \"world\"}))")]
            (is (= :COMPLETED (:status result)))
            (is (= :APPLIED (get-in result [:commit :status])))
            (is (= "world" (slurp (io/file dir "b.txt"))))
            (is (true? (get-in result [:commit :file-changed?]))))
          (finally (program/stop! rt)))))))

(deftest read-set-conflict-leaves-the-disk-untouched
  (with-workspace
    (fn [dir]
      (spit (io/file dir "a.txt") "old")
      (let [rt (make-runtime
                dir
                {:control {:execute! (fn [_ctx _req]
                                       (spit (io/file dir "a.txt") "changed")
                                       {:status :OK})}})]
        (try
          (let [result (exec! rt "(defprogram conflict []
                                  (let [x (cat {:path \"a.txt\"})]
                                    (write {:path \"b.txt\" :content x})
                                    (split {:parent-id \"p\" :children []})))")]
            (is (= :FILE_CONFLICT (:status result)))
            (is (= :failed (:state result)))
            (is (false? (.exists (io/file dir "b.txt"))))
            (is (= "changed" (slurp (io/file dir "a.txt")))))
          (finally (program/stop! rt)))))))

(deftest plan-draft-write-and-read-policy
  (with-workspace
    (fn [dir]
      (.mkdirs (io/file dir "plans"))
      (spit (io/file dir "plans/draft.md") "draft")
      (let [rt (make-runtime dir)]
        (try
          (let [locked (exec! rt "(defprogram plan-write []
                                  (write {:path \"plans/draft.md\" :content \"new\"}))"
                             {:role :clj-plan
                              :context {:draft-path "plans/draft.md"}})]
            (is (= :WRITE_LOCKED_BEFORE_FIRST_REVIEW (:status locked)))
            (is (= "draft" (slurp (io/file dir "plans/draft.md")))))
          (let [denied (exec! rt "(defprogram plan-read []
                                  (cat {:path \"plans/draft.md\"}))"
                             {:role :clj-plan
                              :context {:readable-paths #{"other.md"}}})]
            (is (= :CAPABILITY_DENIED (:status denied))))
          (let [applied (exec! rt "(defprogram plan-write []
                                   (write {:path \"plans/draft.md\" :content \"new\"}))"
                              {:role :clj-plan
                               :context {:draft-path "plans/draft.md"
                                         :write-unlocked? true}})]
            (is (= :COMPLETED (:status applied)))
            (is (= "new" (slurp (io/file dir "plans/draft.md")))))
          (finally (program/stop! rt)))))))

(deftest plan-first-write-fixes-a-safe-new-draft
  (with-workspace
    (fn [dir]
      (.mkdirs (io/file dir "plans"))
      (spit (io/file dir "plans/taken.md") "old")
      (let [rt (make-runtime dir)]
        (try
          (let [first (exec! rt "(defprogram plan-first []
                                  (write {:path \"plans/first.md\" :content \"one\"})
                                  (cat {:path \"plans/first.md\"}))"
                             {:role :clj-plan
                              :context {:write-unlocked? true}})]
            (is (= :COMPLETED (:status first)) (pr-str first))
            (is (= "one" (:value first)))
            (is (= "one" (slurp (io/file dir "plans/first.md")))))
          (let [locked (exec! rt "(defprogram plan-second []
                                   (write {:path \"plans/first.md\" :content \"two\"})
                                   (write {:path \"plans/other.md\" :content \"bad\"}))"
                              {:role :clj-plan
                               :context {:draft-path "plans/first.md"
                                         :write-unlocked? true}})]
            (is (= :DRAFT_PATH_LOCKED (:status locked)) (pr-str locked))
            (is (= "one" (slurp (io/file dir "plans/first.md"))))
            (is (false? (.exists (io/file dir "plans/other.md")))))
          (let [taken (exec! rt "(defprogram plan-taken []
                                  (write {:path \"plans/taken.md\" :content \"new\"}))"
                             {:role :clj-plan
                              :context {:write-unlocked? true}})]
            (is (= :DRAFT_NAME_TAKEN (:status taken)) (pr-str taken))
            (is (= "old" (slurp (io/file dir "plans/taken.md")))))
          (let [invalid (exec! rt "(defprogram plan-invalid []
                                    (write {:path \"plans/Bad.md\" :content \"x\"}))"
                               {:role :clj-plan
                                :context {:write-unlocked? true}})]
            (is (= :INVALID_DRAFT_PATH (:status invalid)) (pr-str invalid)))
          (finally (program/stop! rt)))))))

(deftest plan-first-write-creates-the-plans-directory
  (with-workspace
    (fn [dir]
      (let [rt (make-runtime dir)]
        (try
          (let [result (exec! rt "(defprogram plan-fresh []
                                    (write {:path \"plans/fresh.md\" :content \"new\"}))"
                              {:role :clj-plan
                               :context {:write-unlocked? true}})]
            (is (= :COMPLETED (:status result)) (pr-str result))
            (is (= "new" (slurp (io/file dir "plans/fresh.md")))))
          (finally (program/stop! rt)))))))

(deftest call-drains-the-batch-before-the-shell
  (with-workspace
    (fn [dir]
      (let [calls (atom [])
            rt (make-runtime
                dir
                {:call (fn [ctx request]
                         (swap! calls conj [ctx request])
                         {:status 0
                          :stdout (slurp (io/file dir "c.txt"))})})]
        (try
          (let [result (exec! rt "(defprogram run []
                                  (write {:path \"c.txt\" :content \"data\"})
                                  (call {:command \"read c.txt\"}))"
                             {:holder? true})]
            (is (= :COMPLETED (:status result)))
            (is (= "data" (get-in result [:value :stdout])))
            (is (= 1 (count @calls)))
            (is (= "data" (slurp (io/file dir "c.txt")))))
          (finally (program/stop! rt)))))))

(deftest deadline-parks-during-a-shell-wait-and-resumes
  (with-workspace
    (fn [dir]
      (let [rt (make-runtime
                dir
                {:call (fn [_ctx _request]
                         (Thread/sleep 200)
                         {:status 0 :stdout "slow"})})]
        (try
          (let [parked (exec! rt "(defprogram slow []
                                  (call {:command \"slow\"})
                                  (write {:path \"after.txt\" :content \"yes\"}))"
                             {:holder? true :intent "x"})]
            (is (= :PARKED (:status parked)))
            (is (= :deadline (:reason parked)))
            (Thread/sleep 300)
            (is (false? (.exists (io/file dir "after.txt"))))
            (let [resumed (program/unpark! rt {:call-id "slow-u"
                                               :name "slow"
                                               :value nil
                                               :intent long-intent})]
              (is (= :COMPLETED (:status resumed)))
              (is (= 1 (count (:commits resumed))))
              (is (= :settling (get-in resumed [:commits 0 :phase])))
              (is (= "yes" (slurp (io/file dir "after.txt"))))))
          (finally (program/stop! rt)))))))
(deftest failed-drain-aborts-before-the-shell
  (with-workspace
    (fn [dir]
      (spit (io/file dir "a.txt") "old")
      (let [calls (atom 0)
            rt (make-runtime
                dir
                {:control {:execute! (fn [_ctx _req]
                                       (spit (io/file dir "a.txt") "changed")
                                       {:status :OK})}
                 :call (fn [_ctx _req]
                         (swap! calls inc)
                         {:status 0})})]
        (try
          (let [result (exec! rt "(defprogram conflict-before-call []
                                  (let [x (cat {:path \"a.txt\"})]
                                    (write {:path \"b.txt\" :content x})
                                    (split {:parent-id \"p\" :children []})
                                    (call {:command \"should-not-run\"})))"
                             {:holder? true})]
            (is (= :FILE_CONFLICT (:status result)))
            (is (= :failed (:state result)))
            (is (= 0 @calls))
            (is (false? (.exists (io/file dir "b.txt")))))
          (finally (program/stop! rt)))))))
(deftest mkdir-mv-rm-share-one-protected-batch
  (with-workspace
    (fn [dir]
      (let [rt (make-runtime dir)]
        (try
          (let [result (exec! rt "(defprogram mutate []
                                  (mkdir {:path \"d\"})
                                  (write {:path \"d/a.txt\" :content \"x\"})
                                  (mv {:source \"d/a.txt\" :target \"d/b.txt\"})
                                  (rm {:path \"d/b.txt\"})
                                  (write {:path \"d/c.txt\" :content \"y\"}))")]
            (is (= :COMPLETED (:status result)))
            (is (= 5 (get-in result [:commit :op-count])))
            (is (.isDirectory (io/file dir "d")))
            (is (= "y" (slurp (io/file dir "d/c.txt"))))
            (is (false? (.exists (io/file dir "d/b.txt")))))
          (finally (program/stop! rt)))))))

(deftest non-edn-result-fails-before-settlement
  (with-workspace
    (fn [dir]
      (let [rt (make-runtime dir)]
        (try
          (let [result (exec! rt "(defprogram bad-result []
                                  (write {:path \"b.txt\" :content \"x\"})
                                  (fn [] nil))")]
            (is (= :UNSUPPORTED_OPERATION (:status result)))
            (is (= :failed (:state result)))
            (is (false? (.exists (io/file dir "b.txt")))))
          (finally (program/stop! rt)))))))
