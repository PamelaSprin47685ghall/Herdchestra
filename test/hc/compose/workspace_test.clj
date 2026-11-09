(ns hc.compose.workspace-test
  "Real-file smoke and contract tests for the protected program bridge.

  Every test builds its own temporary workspace and never touches the user's
  project.  The bridge is exercised through the same `:workspace` and
  `:control` ports that `hc.server.program` receives."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.workspace :as ws]
            [hc.compose.workspace.board :as ws-board]
            [hc.compose.workspace.session :as session]
            [hc.core.swarm-stm :as swarm]
            [hc.core.ufss :as ufss]
            [hc.core.workspace-gate :as wg]
            [hc.server.program.runtime :as program])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- temp-root []
  (Files/createTempDirectory "hc-workspace-bridge-" (make-array FileAttribute 0)))

(defn- write-file! [root path text]
  (spit (io/file (str root) path) text))

(defn- read-file! [root path]
  (slurp (io/file (str root) path)))

(defn- cleanup! [root]
  (doseq [f (reverse (file-seq (io/file (str root))))]
    (io/delete-file f true)))

(defn- setup
  "A live actor with one ready task and one claim."
  [root]
  (let [gate (wg/write-gate)
        kernel (swarm/create {:verify-read-set (wg/read-set-verifier root)})
        agent (:agent-id (swarm/execute!
                          kernel {:op :register-actor :slot-id :slot-a}))
        task (:task-id (swarm/execute!
                        kernel {:op :create-task
                                :actor-id agent
                                :goal "publish protected edit"
                                :remaining-work "write the file"
                                :done-when "file contains the new text"
                                :owner-id agent}))
        claim (:claim-id (swarm/execute!
                          kernel {:op :claim
                                  :actor-id agent
                                  :task-id task
                                  :atomicity-reason "single file replacement"}))]
    {:root root :gate gate :kernel kernel
     :agent agent :task task :claim claim}))

(defn- runtime [s]
  (ws/make-runtime {:root (:root s)
                    :kernel (:kernel s)
                    :write-gate (:gate s)
                    :clock (fn [] 0)}))

(defn- ctx [s]
  {:agent-id (:agent s)
   :claim-id (:claim s)
   :task-id (:task s)
   :attempt-id "attempt-1"
   :call-id "call-1"
   :program-name "smoke"
   :role :clj-worker})

(defn- action-for [kernel request-id]
  (some (fn [action] (when (= request-id (:request-id action)) action))
        (vals (:actions (swarm/snapshot kernel)))))

;; ---------------------------------------------------------------------------
;; Port smoke

(deftest write-settles-through-w-and-publishes-the-action
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "old")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            session ((:open-session port) (ctx s))]
        (is (= "old" ((:cat port) session "a.txt")))
        (is (= :STAGED (:status ((:write port) session "a.txt" "new"))))
        (let [facts ((:facts port) session)
              out ((:settle! port) session {:request-id "settle-1"
                                            :agent-id (:agent s)})]
          (is (= :APPLIED (:status out)) (pr-str out))
          (is (= "new" (read-file! root "a.txt")))
          (is (false? (wg/write-gate-held? (:gate rt)))
              "W was not released after the commit")
          (is (empty? (:ops ((:facts port) (:session out))))
              "a successful settle returns a fresh empty session")
          (let [action (action-for (:kernel s) "settle-1")
                b (swarm/snapshot (:kernel s))]
            (is (= :finished (:phase action)))
            (is (not (contains? (:result-ref action) :last-known-stage)))
            (is (not (contains? (:result-ref action) :expected-action)))
            (is (not (contains? (:result-ref action) :failed-at)))
            (is (nil? (get-in b [:actors (:agent s) :active-action-id])))
            (is (= :ready (get-in b [:tasks (:task s) :state]))
                "a file edit never completes the task by itself")
            (is (empty? (swarm/check-invariants (:kernel s)))))))
      (finally (cleanup! root)))))

(deftest read-set-conflict-leaves-the-file-and-board-untouched
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "v1")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            session ((:open-session port) (ctx s))
            before (swarm/snapshot (:kernel s))]
        (is (= "v1" ((:cat port) session "a.txt")))
        ;; Another writer changes the file after the read.
        (write-file! root "a.txt" "version-two")
        ((:write port) session "a.txt" "mine")
        (let [out ((:settle! port) session {:request-id "settle-conflict"
                                            :agent-id (:agent s)})]
          (is (= :FILE_CONFLICT (:status out)) (pr-str out))
          (is (= "version-two" (read-file! root "a.txt")))
          (is (= before (swarm/snapshot (:kernel s)))
              "a file rejection creates no action and changes no task")
          (is (false? (wg/write-gate-held? (:gate rt))))))
      (finally (cleanup! root)))))

(deftest a-protected-edit-without-a-claim-is-rejected-before-apply
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "old")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            session ((:open-session port)
                     (assoc (ctx s) :claim-id nil))
            before (swarm/snapshot (:kernel s))]
        ((:write port) session "a.txt" "new")
        (let [out ((:settle! port) session {:request-id "settle-no-claim"
                                            :agent-id (:agent s)})]
          (is (= :CLAIM_CHANGED (:status out)) (pr-str out))
          (is (= "old" (read-file! root "a.txt")))
          (is (= before (swarm/snapshot (:kernel s))))))
      (finally (cleanup! root)))))

(deftest read-set-only-settle-verifies-without-writing
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "same")
      (is (.exists (io/file (str root) "a.txt")))
      (is (= "same" (read-file! root "a.txt")))
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            session ((:open-session port) (ctx s))]
        (is (= "same" ((:cat port) session "a.txt")))
        (let [facts ((:facts port) session)
              out ((:settle! port) session {:request-id "settle-read-only"
                                            :agent-id (:agent s)})]
          (is (empty? (:ops facts)))
          (is (seq (:read-set facts)))
          (is (= :APPLIED (:status out)) (pr-str out))
          (is (.exists (io/file (str root) "a.txt")))
          (is (= "same" (read-file! root "a.txt")))
          (is (= :finished (:phase (action-for (:kernel s) "settle-read-only"))))))
      (finally (cleanup! root)))))

;; ---------------------------------------------------------------------------
;; Board primitives through the control port

(deftest finish-with-read-set-runs-under-w
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "proof")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            control (ws/control-port rt)
            session ((:open-session port) (ctx s))]
        (is (= "proof" ((:cat port) session "a.txt")))
        (let [facts ((:facts port) session)
              result ((:execute! control)
                      (ctx s)
                      {:primitive :finish
                       :request {:task-id (:task s)
                                 :claim-id (:claim s)
                                 :result-summary "the file proves the claim"
                                 :read-set (:read-set facts)}})]
          (is (:ok? result) (pr-str result))
          (is (false? (wg/write-gate-held? (:gate rt))))
          (let [b (swarm/snapshot (:kernel s))]
            (is (= :done (get-in b [:tasks (:task s) :state])))
            (is (nil? (get-in b [:tasks (:task s) :claim-id])))
            (is (= (vec (session/normalize-read-set (:read-set facts)))
                   (get-in b [:tasks (:task s) :result-refs 0 :read-set])))
            (is (empty? (swarm/check-invariants (:kernel s)))))))
      (finally (cleanup! root)))))

(deftest finish-with-stale-read-set-is-rejected-and-board-is-unchanged
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "v1")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            control (ws/control-port rt)
            session ((:open-session port) (ctx s))
            before (swarm/snapshot (:kernel s))]
        (is (= "v1" ((:cat port) session "a.txt")))
        (let [facts ((:facts port) session)]
          (write-file! root "a.txt" "v2")
          (let [result ((:execute! control)
                        (ctx s)
                        {:primitive :finish
                         :request {:task-id (:task s)
                                   :claim-id (:claim s)
                                   :result-summary "stale"
                                   :read-set (:read-set facts)}})]
            (is (false? (:ok? result)))
            (is (= :FILE_CONFLICT (:error result)) (pr-str result))
            (is (= before (swarm/snapshot (:kernel s))))
            (is (false? (wg/write-gate-held? (:gate rt)))))))
      (finally (cleanup! root)))))

(deftest finish-with-pending-writes-does-not-drain
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "old")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            control (ws/control-port rt)
            session ((:open-session port) (ctx s))]
        ((:write port) session "a.txt" "new")
        (let [result ((:execute! control)
                      (ctx s)
                      {:primitive :finish
                       :request {:task-id (:task s)
                                 :claim-id (:claim s)
                                 :result-summary "write then finish"
                                 :read-set []}})]
          (is (:ok? result) (pr-str result))
          (testing "finish commits the board immediately and never drains the batch"
            (is (= "old" (read-file! root "a.txt")))
            (is (= :done (get-in (swarm/snapshot (:kernel s))
                                 [:tasks (:task s) :state])))
            (is (nil? (get-in (swarm/snapshot (:kernel s))
                              [:tasks (:task s) :claim-id])))
            (is (false? (wg/write-gate-held? (:gate rt))))
            (is (seq (:ops ((:facts port) session)))
                "the staged write is still pending"))
          (testing "the later settle may fail because finish released the claim"
            (let [settled (session/settle! session {:request-id "after-finish"})]
              (is (= :CLAIM_CHANGED (:status settled)) (pr-str settled))
              (is (= "old" (read-file! root "a.txt")))
              (is (false? (wg/write-gate-held? (:gate rt))))))))
      (finally (cleanup! root)))))

(deftest finish-with-pending-writes-verifies-the-old-content
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "old")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            control (ws/control-port rt)
            session ((:open-session port) (ctx s))]
        (is (= "old" ((:cat port) session "a.txt")))
        (let [facts ((:facts port) session)]
          ((:write port) session "a.txt" "new")
          (let [result ((:execute! control)
                        (ctx s)
                        {:primitive :finish
                         :request {:task-id (:task s)
                                   :claim-id (:claim s)
                                   :result-summary "old basis"
                                   :read-set (:read-set facts)}})]
            (is (:ok? result) (pr-str result))
            (testing "finish verifies the old real file and does not apply the write"
              (is (= "old" (read-file! root "a.txt")))
              (is (= :done (get-in (swarm/snapshot (:kernel s))
                                   [:tasks (:task s) :state])))
              (is (= (vec (session/normalize-read-set (:read-set facts)))
                     (get-in result [:result-ref :read-set]))))
            (testing "the later settle fails after the claim was released"
              (let [settled (session/settle! session {:request-id "after-finish-2"})]
                (is (= :CLAIM_CHANGED (:status settled)) (pr-str settled))
                (is (= "old" (read-file! root "a.txt"))))))))
      (finally (cleanup! root)))))

(deftest program-command-whitelist-cannot-open-a-bypass
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            control (ws/control-port rt)
            session ((:open-session port) (ctx s))]
        (is (= :UNSUPPORTED_OPERATION
               (:error ((:execute! control) (ctx s)
                        {:primitive :begin-action
                         :request {:task-id (:task s)
                                   :claim-id (:claim s)}}))))
        (is (= :UNSUPPORTED_OPERATION
               (:error ((:execute! control) (ctx s)
                        {:primitive :batch
                         :request {:commands []}}))))
        (is (empty? (:ops ((:facts port) session)))))
      (finally (cleanup! root)))))

(deftest assume-pins-a-judgment-with-fixed-prose
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            rt (runtime s)
            fallback (fn [_ctx _request]
                       (throw (ex-info "assume must not reach the fallback" {})))
            control (ws/control-port rt fallback)]
        (testing "the control port returns the fixed prose, not an echo"
          (let [assumption "the cache is warm"
                result ((:execute! control) (ctx s)
                        {:primitive :assume
                         :request {:assumption assumption}})]
            (is (= ws/assume-prose result))
            (is (string? result))
            (is (not (str/includes? (str result) assumption)))))
        (testing "blank assumptions are refused before any judgment is pinned"
          (doseq [assumption [nil "" "   " 42]]
            (let [result ((:execute! control) (ctx s)
                          {:primitive :assume
                           :request {:assumption assumption}})]
              (is (false? (:ok? result)))
              (is (= :ASSUMPTION_REQUIRED (get-in result [:error :code])))
              (is (not (str/includes? (pr-str result) "cache")))))))
      (testing "the real evaluator's expression value is the fixed prose"
        (let [base (runtime (setup root))
              prt (program/runtime
                   {:workspace (ws/workspace-port base)
                    :control (ws/control-port base)
                    :clock (fn [] 0)})]
          (try
            (let [out (program/evaluate-program!
                       prt
                       {:call-id "call-assume"
                        :attempt-id "attempt-assume"
                        :agent-id "agent-1"
                        :role :clj-worker
                        :intent "pin one already-abstracted judgment"
                        :source
                        "(defprogram pin [] (assume {:assumption \"the cache is warm\"}))"})]
              (is (= :COMPLETED (:status out)) (pr-str out))
              (is (= ws/assume-prose (:value out)))
              (is (not (str/includes? (str (:value out)) "cache")))
              (is (= :assume (get-in out [:control-results 0 :primitive]))))
            (finally
              (program/stop! prt)))))
      (finally (cleanup! root)))))

;; ---------------------------------------------------------------------------
;; run-program! and execute!

(defn- settling-evaluator
  "A stand-in for hc.server.program/evaluate-program!.  It writes one file,
  settles, and returns the terminal shape the real evaluator returns."
  [port file text]
  (fn [request]
    (let [session ((:open-session port)
                   {:agent-id (:agent-id request)
                    :claim-id (:claim-id request)
                    :task-id (:task-id request)
                    :attempt-id (:attempt-id request)
                    :call-id (:call-id request)
                    :program-name "fake-program"
                    :role (:role request)})]
      ((:write port) session file text)
      (let [out ((:settle! port) session
                 {:request-id (str (:call-id request) ":settle:0")
                  :agent-id (:agent-id request)})]
        (if (= :APPLIED (:status out))
          {:status :COMPLETED
           :value {:file file :text text}
           :receipts []
           :effects []}
          {:status :FAILED
           :value nil
           :result (:result out)
           :error (:result out)})))))

(deftest run-program-settles-and-reports-receipts-and-effects
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            rt (assoc rt :evaluate (settling-evaluator port "note.txt" "hello"))
            out (ws/run-program! rt {:agent-id (:agent s)
                                     :claim-id (:claim s)
                                     :task-id (:task s)
                                     :attempt-id "attempt-1"
                                     :call-id "call-1"
                                     :program "(defprogram fake [] nil)"})]
        (is (= :succeeded (:status out)) (pr-str out))
        (is (= "hello" (read-file! root "note.txt")))
        (is (= {:file "note.txt" :text "hello"} (:value out)))
        (is (seq (:receipts out)))
        (is (some #(= :workspace/settle (:type %)) (:receipts out)))
        (is (some #(= :files/applied (:type %)) (:effects out)))
        (is (false? (wg/write-gate-held? (:gate rt)))))
      (finally (cleanup! root)))))

(deftest run-program-review-control-keeps-the-pinned-plan-tenure
  ;; The real Plan failure: the Owner pinned "tenure-1" on the model call and
  ;; tool request, but the bridge dropped it, so the program's control
  ;; context handed `(review ...)` a nil tenure and the mission refused it
  ;; with :INVALID_REQUEST.  This runs the real program runtime over the
  ;; workspace bridge and asserts the control primitive sees the pinned
  ;; identity.
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            base (runtime s)
            controlled (atom nil)
            fallback (fn [ctx request]
                       (reset! controlled {:ctx ctx :request request})
                       {:status :OK})
            prt (program/runtime
                 {:workspace (ws/workspace-port base)
                  :control (ws/control-port base fallback)
                  :clock (fn [] 0)})]
        (try
          (let [rt (assoc base :evaluate (partial program/evaluate-program!
                                                   prt))
                out (ws/run-program! rt
                                     {:agent-id (:agent s)
                                      :claim-id (:claim s)
                                      :task-id (:task s)
                                      :attempt-id "attempt-review"
                                      :call-id "call-review"
                                      :role :clj-plan
                                      :tenure-id "tenure-1"
                                      :lane :plan
                                      :program
                                      "(defprogram plan-review [] (review {:findings []}))"})]
            (is (= :succeeded (:status out)) (pr-str out))
            (is (= :review (get-in @controlled [:request :primitive])))
            (is (= "tenure-1" (get-in @controlled [:ctx :tenure-id]))
                "the review primitive must receive the pinned Plan tenure")
            (is (= :plan (get-in @controlled [:ctx :lane]))))
          (finally (program/stop! prt))))
      (finally (cleanup! root)))))

(deftest run-program-without-an-evaluator-fails-as-data
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            rt (runtime s)
            out (ws/run-program! rt {:agent-id (:agent s)
                                     :claim-id (:claim s)
                                     :task-id (:task s)
                                     :attempt-id "attempt-1"
                                     :program "(defprogram fake [] nil)"})]
        (is (= :failed (:status out)))
        (is (= :PROGRAM_RUNTIME_UNAVAILABLE (get-in out [:error :code]))))
      (finally (cleanup! root)))))

(deftest run-program-normalizes-a-holder-to-clj-worker
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            captured (atom nil)
            rt (assoc (runtime s)
                      :evaluate (fn [request]
                                  (reset! captured request)
                                  {:status :COMPLETED :value nil}))
            out (ws/run-program! rt {:agent-id (:agent s)
                                     :claim-id (:claim s)
                                     :task-id (:task s)
                                     :attempt-id "attempt-holder"
                                     :call-id "call-holder"
                                     :role :dedicated-worker
                                     :holder? true
                                     :program "(defprogram holder [] nil)"})]
        (is (= :succeeded (:status out)) (pr-str out))
        (is (= :clj-worker (:role @captured))
            "a holder never inherits the dedicated-worker capability set")
        (is (true? (:holder? @captured))))
      (finally (cleanup! root)))))

(deftest cooperative-stop-is-observed-between-operations
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "x")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            rt (assoc rt :evaluate
                      (fn [request]
                        (let [session ((:open-session port)
                                       {:agent-id (:agent-id request)
                                        :claim-id (:claim-id request)
                                        :task-id (:task-id request)
                                        :attempt-id (:attempt-id request)
                                        :call-id (:call-id request)})]
                          ((:cat port) session "a.txt")
                          {:status :COMPLETED :value :unreachable})))]
        (is (= :stop-registered
               (:status (ws/request-stop! rt "attempt-1"))))
        (is (false? (:ended? (ws/request-stop! rt "attempt-1")))
            "a stop request is never an observation that the action ended")
        (let [out (ws/run-program! rt {:agent-id (:agent s)
                                       :claim-id (:claim s)
                                       :task-id (:task s)
                                       :attempt-id "attempt-1"
                                       :call-id "call-1"
                                       :program "(defprogram fake [] nil)"})]
          (is (= :cancelled (:status out)) (pr-str out))
          (is (= :STOP_REQUESTED (get-in out [:error :code])))
          (is (false? (wg/write-gate-held? (:gate rt)))))
        (is (= :ok (ws/clear-stop! rt "attempt-1"))))
      (finally (cleanup! root)))))

(deftest stop-contract-never-claims-the-action-ended
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            rt (runtime s)]
        (testing "a registered stop reports no end and no call it cannot name"
          (let [result (ws/request-stop! rt "attempt-unknown")]
            (is (= :stop-registered (:status result)))
            (is (false? (:ended? result)))
            (is (nil? (:call-id result)))))
        (testing "a closed runtime records nothing"
          (reset! (:closed? rt) true)
          (let [result (ws/request-stop! rt "attempt-1")]
            (is (= :unknown (:status result)))
            (is (false? (:ended? result))))))
      (finally (cleanup! root)))))

(deftest execute-write-uses-the-same-protected-path
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            rt (runtime s)
            out (ws/execute! rt {:op :write
                                 :agent-id (:agent s)
                                 :claim-id (:claim s)
                                 :task-id (:task s)
                                 :attempt-id "attempt-1"
                                 :request-id "execute-write-1"
                                 :path "x.txt"
                                 :content "content"})]
        (is (= :succeeded (:status out)) (pr-str out))
        (is (= "content" (read-file! root "x.txt")))
        (is (= :finished (:phase (action-for (:kernel s) "execute-write-1"))))
        (is (false? (wg/write-gate-held? (:gate rt)))))
      (finally (cleanup! root)))))

(deftest execute-unknown-operation-does-not-touch-the-workspace
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            rt (runtime s)
            out (ws/execute! rt {:op :begin-action
                                 :agent-id (:agent s)
                                 :claim-id (:claim s)
                                 :task-id (:task s)})]
        (is (= :failed (:status out)))
        (is (= :UNSUPPORTED_OPERATION (get-in out [:error :code])))
        (is (empty? (:actions (swarm/snapshot (:kernel s))))))
      (finally (cleanup! root)))))

(deftest read-only-dependency-conflict-rejects-before-write
  (let [root (temp-root)]
    (try
      (write-file! root "api.txt" "v1")
      (write-file! root "client.txt" "old")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            session ((:open-session port) (ctx s))]
        (is (= "v1" ((:cat port) session "api.txt")))
        ((:write port) session "client.txt" "new")
        (write-file! root "api.txt" "v2")
        (let [out ((:settle! port) session {:request-id "read-only-dep"
                                            :agent-id (:agent s)})]
          (is (= :FILE_CONFLICT (:status out)) (pr-str out))
          (is (= "old" (read-file! root "client.txt")))
          (is (= "v2" (read-file! root "api.txt")))
          (is (false? (wg/write-gate-held? (:gate rt))))))
      (finally (cleanup! root)))))

(deftest reads-do-not-wait-for-w
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "old")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            session ((:open-session port) (ctx s))
            permit (wg/acquire-write-gate! (:gate s) :test-holder)]
        (try
          (let [result (promise)
                reader (doto (Thread. #(deliver result
                                                ((:cat port) session "a.txt")))
                         (.setDaemon true))]
            (.start reader)
            (is (= "old" (deref result 1000 :timeout))))
          (finally (wg/release-write-gate! permit))))
      (finally (cleanup! root)))))

(deftest parked-program-keeps-the-where-diagnostic
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            diagnostic {:reason :where-ambiguous
                        :payload {:needle "timeout"
                                  :candidates [{:span [1 8] :context "timeout"}]
                                  :nearest {:span [2 9] :text "timeout"}}}
            rt (assoc (runtime s)
                      :evaluate (fn [_request]
                                  (assoc diagnostic
                                         :status :PARKED
                                         :state :parked)))
            out (ws/run-program! rt {:agent-id (:agent s)
                                     :claim-id (:claim s)
                                     :task-id (:task s)
                                     :attempt-id "park-1"
                                     :intent "park"
                                     :program "(defprogram p [] nil)"})]
        (is (= :succeeded (:status out)) (pr-str out))
        (is (true? (:parked? out)))
        (is (= :where-ambiguous (get-in out [:value :reason])))
        (is (= [{:span [1 8] :context "timeout"}]
               (get-in out [:value :payload :candidates])))
        (is (= {:span [2 9] :text "timeout"}
               (get-in out [:value :payload :nearest]))))
      (finally (cleanup! root)))))

(deftest real-unpark-prefixed-program-keeps-its-session
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)]
        ((:open-session port)
         {:agent-id (:agent s)
          :claim-id (:claim s)
          :task-id (:task s)
          :call-id "call-real-unpark"
          :attempt-id "attempt-real-unpark"
          :program-name "unpark-real-program"})
        (is (contains? @(:sessions rt) "call-real-unpark"))
        (is (contains? @(:sessions rt) "attempt-real-unpark")))
      (finally (cleanup! root)))))

(deftest parked-target-terminal-callback-removes-all-session-aliases
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            captured (atom nil)
            rt (assoc (runtime s)
                      :evaluate (fn [request]
                                  (reset! captured request)
                                  {:status :PARKED
                                   :name "parked-program"
                                   :call-id (:call-id request)
                                   :attempt-id (:attempt-id request)}))
            port (ws/workspace-port rt)
            _ ((:open-session port)
               {:agent-id (:agent s)
                :claim-id (:claim s)
                :task-id (:task s)
                :call-id "call-parked"
                :attempt-id "attempt-parked"
                :program-name "parked-program"})
            out (ws/run-program! rt {:agent-id (:agent s)
                                     :claim-id (:claim s)
                                     :task-id (:task s)
                                     :attempt-id "attempt-parked"
                                     :call-id "call-parked"
                                     :intent "park"
                                     :program "(defprogram parked-program [] nil)"})]
        (is (= :succeeded (:status out)) (pr-str out))
        (is (true? (:parked? out)))
        (is (contains? @(:sessions rt) "call-parked"))
        (is (contains? @(:sessions rt) "attempt-parked"))
        (is (not (contains? @(:sessions rt) "parked-program"))
            "program names are not session aliases")
        ((:before-terminal @captured)
         {:call-id "call-parked"
          :attempt-id "attempt-parked"
          :name "parked-program"})
        (is (empty? (filter #{"call-parked" "attempt-parked" "parked-program"}
                            (keys @(:sessions rt))))))
      (finally (cleanup! root)))))

(deftest edn-program-forms-use-ufss-source
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            captured (atom nil)
            form '(defprogram ufss-probe []
                    (write {:path "C:\\tmp\\a.txt"
                            :content "say \"hi\"\n"}))
            rt (assoc (runtime s)
                      :evaluate (fn [request]
                                  (reset! captured request)
                                  {:status :COMPLETED :value :ok}))]
        (is (= :succeeded
               (:status (ws/run-program! rt {:agent-id (:agent s)
                                             :claim-id (:claim s)
                                             :task-id (:task s)
                                             :attempt-id "ufss-1"
                                             :intent "ufss"
                                             :program form}))))
        (let [source (:source @captured)]
          (is (= form (ufss/read-str source)))
          (is (or (str/includes? source "#\"")
                  (str/includes? source "#\"\"\""))
              "model-facing source uses UFSS fences")
          (is (not (str/includes? source "\\\"")))
          (is (not (str/includes? source "\\n")))))
      (finally (cleanup! root)))))

(deftest cooperative-stop-leaves-the-waiting-settle-queue
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "old")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            session ((:open-session port) (ctx s))]
        ((:write port) session "a.txt" "new")
        (let [permit (wg/acquire-write-gate! (:gate s) :test-holder)
              result (promise)
              worker (doto
                       (Thread. #(deliver result
                                          (session/settle!
                                           session
                                           {:request-id "queued-stop"
                                            :agent-id (:agent s)})))
                       (.setDaemon true))]
          (.start worker)
          (loop [n 0]
            (when (and (< n 200)
                       (zero? (wg/write-gate-queued (:gate s))))
              (Thread/sleep 5)
              (recur (inc n))))
          (is (pos? (wg/write-gate-queued (:gate s)))
              "the settle must be queued behind W")
          (is (= :stop-registered
                 (:status (ws/request-stop! rt "attempt-1"))))
          (wg/release-write-gate! permit)
          (let [out (deref result 2000 :timeout)]
            (is (= :WAITING_FOR_WRITE_GATE (:status out)) (pr-str out))
            (is (true? (:cancelled? (:result out))))
            (is (= "old" (read-file! root "a.txt")))
            (is (false? (wg/write-gate-held? (:gate rt)))))))
      (finally (cleanup! root)))))

(deftest publish-conflict-retries-without-reapplying-files
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "old")
      (let [s (setup root)
            applies (atom 0)
            publish-failures (atom 1)
            execute-board!
            (fn [command]
              (when (and (= :advance-action (:op command))
                         (= :finished (:phase command))
                         (pos? @publish-failures))
                (swap! publish-failures dec)
                (throw (ex-info "simulated STM publication conflict"
                                {:code :STM_CONFLICT})))
              (let [result (swarm/execute! (:kernel s) command)]
                {:ok? (:ok? result)
                 :board (swarm/snapshot (:kernel s))
                 :result result}))
            rt (ws/make-runtime
                {:root root
                 :kernel (:kernel s)
                 :write-gate (:gate s)
                 :execute-board! execute-board!
                 :board-snapshot #(swarm/snapshot (:kernel s))
                 :clock (fn [] 0)
                 :apply-fn (fn [prepared opts]
                             (swap! applies inc)
                             (wg/apply-plan! prepared opts))})
            port (ws/workspace-port rt)
            session ((:open-session port) (ctx s))]
        ((:write port) session "a.txt" "new")
        (let [out ((:settle! port) session {:request-id "publish-retry"
                                            :agent-id (:agent s)})]
          (is (= :APPLIED (:status out)) (pr-str out))
          (is (true? (:published? (:result out))))
          (is (= 1 @applies) "the file plan must be applied exactly once")
          (is (= "new" (read-file! root "a.txt")))
          (is (= :finished (:phase (action-for (:kernel s) "publish-retry"))))))
      (finally (cleanup! root)))))

(deftest cancel-during-apply-completes-the-fixed-batch
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "old")
      (let [s (setup root)
            wg-ref (atom nil)
            rt (ws/make-runtime
                {:root root
                 :kernel (:kernel s)
                 :write-gate (:gate s)
                 :clock (fn [] 0)
                 :apply-fn (fn [prepared opts]
                             (wg/request-stop! @wg-ref "cancel-after-apply")
                             (wg/apply-plan! prepared opts))})]
        (reset! wg-ref (:wg rt))
        (let [port (ws/workspace-port rt)
              session ((:open-session port) (ctx s))]
          ((:write port) session "a.txt" "new")
          (let [out ((:settle! port) session {:request-id "cancel-after-apply"
                                              :agent-id (:agent s)})]
            (is (= :APPLIED (:status out)) (pr-str out))
            (is (true? (:cancel-after-apply? (:result out))))
            (is (= "new" (read-file! root "a.txt")))
            (is (= :finished (:phase (action-for (:kernel s)
                                                 "cancel-after-apply")))
                "the fixed batch still publishes after the stop request")
            (is (false? (wg/write-gate-held? (:gate rt)))))))
      (finally (cleanup! root)))))

(deftest owner-adapter-publishes-through-a-mailbox-receipt
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "old")
      (let [s (setup root)
            execute! (fn [command]
                       (let [result (swarm/execute! (:kernel s) command)]
                         {:ok? (:ok? result)
                          :board (swarm/snapshot (:kernel s))
                          :result result}))
            snapshot (fn [] (swarm/snapshot (:kernel s)))
            wg-instance (wg/workspace-gate
                         {:root root
                          :gate (:gate s)
                          :board (ws-board/owner-adapter execute! snapshot)})
            session (wg/session root)]
        (wg/cat session "a.txt")
        (wg/write session "a.txt" "new")
        (let [result (wg/commit! wg-instance
                                 {:request-id "owner-1"
                                  :actor-id (:agent s)
                                  :claim-id (:claim s)
                                  :task-id (:task s)
                                  :batch (wg/snapshot session)})
              action (action-for (:kernel s) "owner-1")]
          (is (= :APPLIED (:status result)) (pr-str result))
          (is (= "new" (read-file! root "a.txt")))
          (is (= :finished (:phase action)))
          (is (nil? (get-in (swarm/snapshot (:kernel s))
                            [:actors (:agent s) :active-action-id])))
          (is (empty? (swarm/check-invariants (:kernel s))))))
      (finally (cleanup! root)))))

(deftest plan-first-draft-write-does-not-need-a-swarm-actor
  ;; Real Plan failure: the draft write settled with :ACTOR_INACTIVE because
  ;; :plan is not a swarm actor.  The Plan draft is authorized by the Owner
  ;; claim and write unlock; only the claim command may reach the board.
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            board-calls (atom [])
            execute-board!
            (fn [command & _]
              (swap! board-calls conj command)
              (case (:op command)
                :check-draft
                {:ok? true
                 :result {:status :accepted :outcome :draft-checked}}

                :claim-draft
                {:ok? true
                 :result {:status :accepted :outcome :draft-claimed}}

                :release-draft
                {:ok? true
                 :result {:status :accepted :outcome :draft-released}}

                (throw (ex-info "Plan draft must not use the swarm board"
                                {:command command}))))
            base (ws/make-runtime
                  {:root (:root s)
                   :write-gate (:gate s)
                   :execute-board! execute-board!
                   :board-snapshot (fn [] {})
                   :clock (fn [] 0)})
            prt (program/runtime
                 {:workspace (ws/workspace-port base)
                  :clock (fn [] 0)})
            rt (assoc base :evaluate (partial program/evaluate-program! prt))]
        (try
          (let [out (ws/run-program!
                     rt
                     {:agent-id :plan
                      :role :clj-plan
                      :tenure-id "tenure-1"
                      :lane :plan
                      :write-unlocked? true
                      :attempt-id "plan-write"
                      :call-id "plan-write"
                      :program
                      "(defprogram plan-write [] (write {:path \"plans/e2e.md\" :content \"draft\"}))"})]
            (is (= :succeeded (:status out)) (pr-str out))
            (is (= "draft" (read-file! root "plans/e2e.md")))
            (is (= [:check-draft :claim-draft :check-draft]
                   (mapv :op @board-calls))
                "the first draft claims once under W and rechecks after the dry-run")
            (is (false? (wg/write-gate-held? (:gate rt))))
            (reset! board-calls [])
            (let [revised (ws/run-program!
                           rt
                           {:agent-id :plan
                            :role :clj-plan
                            :tenure-id "tenure-1"
                            :lane :plan
                            :draft-path "plans/e2e.md"
                            :write-unlocked? true
                            :attempt-id "plan-write-2"
                            :call-id "plan-write-2"
                            :program
                            "(defprogram plan-write-2 [] (write {:path \"plans/e2e.md\" :content \"revised\"}))"})]
              (is (= :succeeded (:status revised)) (pr-str revised))
              (is (= "revised" (read-file! root "plans/e2e.md")))
              (is (= [:check-draft :claim-draft] (mapv :op @board-calls))
                  "a fixed draft path uses only the mission draft authority")))
          (finally (program/stop! prt))))
      (finally (cleanup! root)))))

(deftest hard-failure-keeps-an-uncertain-action-and-can-be-resolved
  (let [root (temp-root)]
    (try
      (write-file! root "a.txt" "old")
      (let [s (setup root)
            rt (ws/make-runtime
                {:root (:root s)
                 :kernel (:kernel s)
                 :write-gate (:gate s)
                 :clock (fn [] 0)
                 :apply-fn (fn [_ _]
                             {:status :HARD_FAILURE
                              :outcome :unknown
                              :file-changed? :unknown
                              :phase :applying
                              :message "simulated disk failure"})})
            port (ws/workspace-port rt)
            session ((:open-session port) (ctx s))]
        ((:write port) session "a.txt" "new")
        (let [out ((:settle! port) session {:request-id "hard-1"})
              action (action-for (:kernel s) "hard-1")]
          (is (= :HARD_FAILURE (:status out)) (pr-str out))
          (is (= :applying (get-in out [:result :last-known-stage])))
          (is (= {:stage :applying} (get-in out [:result :failed-at])))
          (is (string? (get-in out [:result :expected-action])))
          (is (= :uncertain (:phase action)))
          (let [result-ref (:result-ref action)]
            (is (= :applying (:phase result-ref)))
            (is (= :applying (:last-known-stage result-ref)))
            (is (= {:stage :applying} (:failed-at result-ref)))
            (is (string? (:expected-action result-ref)))
            (is (<= (count (:expected-action result-ref)) 300))
            (is (= result-ref (edn/read-string (pr-str result-ref)))))
          (is (= (:action-id action)
                 (get-in (swarm/snapshot (:kernel s))
                         [:actors (:agent s) :active-action-id]))
              "an uncertain action remains visible as a responsibility")
          (let [resolved (ws/execute! rt {:op :resolve-action
                                          :agent-id (:agent s)
                                          :claim-id (:claim s)
                                          :task-id (:task s)
                                          :attempt-id "attempt-1"
                                          :action-id (:action-id action)})]
            (is (= :succeeded (:status resolved)) (pr-str resolved))
            (is (= :finished (:phase (action-for (:kernel s) "hard-1"))))
            (is (nil? (get-in (swarm/snapshot (:kernel s))
                              [:actors (:agent s) :active-action-id])))
            (is (empty? (swarm/check-invariants (:kernel s)))))))
      (finally (cleanup! root)))))

;; ---------------------------------------------------------------------------
;; Property

(defspec protected-write-roundtrip 30
  (prop/for-all [text (gen/fmap #(apply str %) (gen/vector gen/char-alphanumeric 0 80))]
    (let [root (temp-root)]
      (try
        (write-file! root "p.txt" "old")
        (let [s (setup root)
              rt (runtime s)
              port (ws/workspace-port rt)
              session ((:open-session port) (ctx s))]
          ((:write port) session "p.txt" text)
          (let [out ((:settle! port) session
                     {:request-id (str "prop-" (System/nanoTime))
                      :agent-id (:agent s)})]
            (and (= :APPLIED (:status out))
                 (= text (read-file! root "p.txt"))
                 (not (wg/write-gate-held? (:gate rt))))))
        (finally (cleanup! root))))))

(deftest file-conflict-then-reread-retry-succeeds
  ;; E2E 09 core: an external edit between read and settle must reject the
  ;; worker batch with :FILE_CONFLICT and leave the external content; a fresh
  ;; read of the new stamp then commits successfully.
  (let [root (temp-root)]
    (try
      (write-file! root "e2e09.txt" "original")
      (let [s (setup root)
            rt (runtime s)
            port (ws/workspace-port rt)
            session ((:open-session port) (ctx s))]
        (is (= "original" ((:cat port) session "e2e09.txt")))
        ;; The external script edits the same file after the worker's read.
        (write-file! root "e2e09.txt" "external")
        ((:write port) session "e2e09.txt" "worker")
        (let [conflict ((:settle! port) session
                        {:request-id "e2e09-conflict"
                         :agent-id (:agent s)})]
          (is (= :FILE_CONFLICT (:status conflict)) (pr-str conflict))
          (is (= "external" (read-file! root "e2e09.txt"))
              "the real file keeps the external edit")
          (is (false? (wg/write-gate-held? (:gate rt)))))
        ;; Re-read the external content and retry with a new stamp.
        (let [session2 ((:open-session port) (ctx s))]
          (is (= "external" ((:cat port) session2 "e2e09.txt")))
          ((:write port) session2 "e2e09.txt" "worker")
          (let [retry ((:settle! port) session2
                       {:request-id "e2e09-retry"
                        :agent-id (:agent s)})]
            (is (= :APPLIED (:status retry)) (pr-str retry))
            (is (= "worker" (read-file! root "e2e09.txt")))
            (is (= :finished
                   (:phase (action-for (:kernel s) "e2e09-retry")))))))
      (finally (cleanup! root)))))
