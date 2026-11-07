(ns hc.compose.cancel-integration-test
  "Real Esc path: Owner -> dispatcher -> shell executor -> managed subprocess.

  This test uses the actual managed shell runtime and a real OS process.  It
  does not assert a pure cancellation tree in isolation: the operator stop
  must end the subprocess and leave the Owner run cancelled with the call
  outcome preserved as unknown."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner :as owner]
            [hc.core.workspace-gate :as wg]
            [hc.server.app.executors :as executors]
            [hc.server.shell :as shell])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 20) (recur))
        :else false))))

(defn- snapshot
  [handle]
  ((:snapshot handle)))

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- path
  [root name]
  (Path/of root (into-array String [name])))

(defn- exists?
  [root name]
  (Files/exists (path root name) (make-array LinkOption 0)))

(defn- delete-tree!
  [root]
  (when (Files/exists (Path/of root (make-array String 0))
                      (make-array LinkOption 0))
    (doseq [file (reverse (file-seq (clojure.java.io/file root)))]
      (try (Files/deleteIfExists (.toPath file)) (catch Throwable _ nil)))))

(deftest operator-esc-stops-a-real-shell-subprocess
  (let [root (temp-dir "hc-cancel-root")
        artifacts (temp-dir "hc-cancel-artifacts")
        gate (wg/write-gate)
        board (-> (shell/memory-board)
                  (shell/set-actor! "agent-1" true)
                  (shell/set-token! "agent-1" 1)
                  (shell/set-claim! {:claim-id "claim-1"
                                     :actor-id "agent-1"
                                     :task-id "task-1"})
                  (shell/set-task! "task-1" :ready {:needs-token true}))
        runner (shell/runner {:root root
                              :gate gate
                              :board board
                              :artifact-dir artifacts
                              :limits {:stop-grace-ms 200
                                       :wait-poll-ms 10
                                       :drain-timeout-ms 100}})
        shell-executor (executors/shell-executor
                        runner
                        (fn [] {:token-slot {:holder-id "agent-1"
                                             :term-id 1}}))
        cancel-shell (fn [{:keys [call-id]}]
                       (shell/request-stop! runner {:request-id call-id}))
        handle (owner/start-owner!
                {:session-id "s1"
                 :ephemeral? true
                 :model-gateway
                 {:start! (fn [request deliver!]
                            (deliver! {:event :tool-call
                                       :call-id "tool-1"
                                       :payload {:executor :shell
                                                 :command "echo started > started; sleep 30"
                                                 :cwd "."
                                                 :actor-id "agent-1"
                                                 :claim-id "claim-1"
                                                 :task-id "task-1"
                                                 :purpose "cancel-test"
                                                 :member "shell"
                                                 :scope {:kind :reset-scope
                                                         :new-scope-goal "cancel test"
                                                         :new-scope-retention-budget 10}}})
                            (deliver! {:event :completed :text "spawned"})
                            {:attempt-id (:attempt-id request)})
                  :cancel! (fn [_] nil)}
                 :executors {:shell {:run shell-executor
                                     :cancel cancel-shell}}
                 :cancel-resource! cancel-shell})]
    (try
      (is (= :accepted
             (:status ((:submit-command! handle)
                       {:command-id "c1"
                        :command-epoch 1
                        :payload {:kind :send-message :text "run"}}))))
      (testing "the real subprocess starts"
        (is (wait-for #(exists? root "started") 5000))
        (is (wait-for #(= :running
                          (:phase (shell/action runner "call-1/tool-0")))
                      5000)))
      (testing "operator Esc is accepted and closes the run"
        (is (= :accepted
               (:status ((:submit-command! handle)
                         {:command-id "c2"
                          :command-epoch 1
                          :payload {:kind :stop-run
                                    :origin :operator-esc}}))))
        (is (wait-for #(= :outcome-unknown
                          (get-in (snapshot handle)
                                  [:state :calls "call-1/tool-0" :state]))
                      5000))
        (is (wait-for #(= :cancelled
                          (get-in (snapshot handle)
                                  [:state :cancel :nodes "s1-run" :state]))
                      5000)))
      (testing "the late shell terminal is preserved as diagnostic"
        (is (wait-for #(seq (get-in (snapshot handle)
                                    [:state :calls "call-1/tool-0"
                                     :late-results]))
                      5000))
        (let [snap (snapshot handle)
              call (get-in snap [:state :calls "call-1/tool-0"])
              late (last (:late-results call))]
          (is (= :outcome-unknown (:state call)))
          (is (not= :outcome-unknown (:result call)))
          (is (contains? #{:cancelled :outcome-unknown :succeeded :failed}
                         (:status late)))
          (is (some? (:at late)))))
      (testing "the managed shell session ends with cancellation facts"
        (is (wait-for #(let [action (shell/action runner "call-1/tool-0")]
                         (and action
                              (not= :running (:status action))
                              (true? (get-in action [:result :cancelled?]))))
                      5000))
        (let [action (shell/action runner "call-1/tool-0")
              result (:result action)]
          (is (contains? #{:uncertain :finished} (:status action)))
          (is (true? (:cancelled? result)))
          (when (= :finished (:status action))
            (is (some? (:exit-code result))))
          (is (empty? (filter :alive?
                              (:known-child-activities result))))))
      (finally
        ((:stop! handle))
        (delete-tree! root)
        (delete-tree! artifacts)))))
