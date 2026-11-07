(ns hc.server.program.shell-port-test
  (:require [clojure.test :refer [deftest is]]
            [hc.server.program.shell-port :as shell-port]))

(deftest emits-live-and-final-command-views
  (let [started (atom nil)
        views (atom [])
        start! (fn [_runner request]
                 (reset! started request)
                 :session)
        subscribe! (fn [_session listener]
                     ;; Simulate one live chunk before the terminal result.
                     (listener {:stream :stdout :text "partial" :bytes 7})
                     (fn [] nil))
        status (fn [_session]
                 {:phase :running :elapsed-ms 120 :done? false :pid 42})
        await (fn [_session]
                {:status :COMPLETED
                 :duration-ms 150
                 :exit-code 0
                 :output {:stdout {:inline "partial done" :bytes 12}
                          :stderr {:inline "" :bytes 0}}
                 :output-truncated? false})
        call (shell-port/shell-call-port
              {:runner :runner
               :context-fn (fn [_ctx _request]
                             {:token-term "term"
                              :cwd "."})
               :start! start!
               :subscribe! subscribe!
               :status status
               :await await})
        ctx {:agent-id "agent"
             :claim-id "claim"
             :task-id "task"
             :call-id "call-1"}
        request {:command "echo hi"
                 :operation-id "call-1:call:1"
                 :on-command-view #(swap! views conj %)}
        result (call ctx request)]
    (is (= :COMPLETED (:status result)))
    (is (= "call-1:call:1" (:request-id @started)))
    (is (= "agent" (:actor-id @started)))
    (is (= "claim" (:claim @started)))
    (is (= "task" (:task-id @started)))
    (is (= "term" (:token-term @started)))
    (is (= "." (:cwd @started)))
    (is (= "echo hi" (:purpose @started)))
    (is (= 2 (count @views)))
    (let [[live final] @views]
      (is (= :running (:status live)))
      (is (= "partial" (:stdout live)))
      (is (= 7 (:stdout-bytes live)))
      (is (= 120 (:duration-ms live)))
      (is (= :completed (:status final)))
      (is (= "partial done" (:stdout final)))
      (is (= 12 (:stdout-bytes final)))
      (is (= 0 (:exit-code final))))))

(deftest waits-past-a-provisional-uncertain-result
  (let [status-calls (atom 0)
        result-calls (atom 0)
        call (shell-port/shell-call-port
              {:runner :runner
               :start! (fn [_ _] :session)
               :await (fn [_] {:status :UNCERTAIN :request-id "r1"})
               :status (fn [_]
                         (if (= 1 (swap! status-calls inc))
                           {:done? true
                            :write-gate-held? true
                            :process {:alive? true}
                            :known-child-activities [{:pid 7 :alive? true}]}
                           {:done? true
                            :write-gate-held? false
                            :process {:alive? false}
                            :known-child-activities []}))
               :result (fn [_]
                         (if (= 1 (swap! result-calls inc))
                           {:status :UNCERTAIN :request-id "r1"}
                           {:status :COMPLETED
                            :request-id "r1"
                            :exit-code 0
                            :output {:stdout {:inline "" :bytes 0}
                                     :stderr {:inline "" :bytes 0}}}))
               :uncertain-poll-ms 1})
        result (call {:agent-id "agent" :call-id "call-1"}
                     {:command "stubborn"
                      :operation-id "call-1:call:1"})]
    (is (= :COMPLETED (:status result)))
    (is (= "r1" (:request-id result)))
    (is (>= @status-calls 2))
    (is (>= @result-calls 2))))

(deftest non-string-command-still-has-a-string-purpose
  (let [started (atom nil)
        call (shell-port/shell-call-port
              {:runner :runner
               :start! (fn [_ request] (reset! started request) :session)
               :subscribe! (fn [_ _] (fn [] nil))
               :status (fn [_]
                         {:done? true
                          :write-gate-held? false
                          :process {:alive? false}
                          :known-child-activities []})
               :await (fn [_]
                        {:status :COMPLETED
                         :request-id "r1"
                         :exit-code 0
                         :output {:stdout {:inline "" :bytes 0}
                                  :stderr {:inline "" :bytes 0}}})})
        result (call {:agent-id "agent" :call-id "call-1"}
                     {:command ["sh" "-c" "echo hi"]
                      :operation-id "call-1:call:1"})]
    (is (= :COMPLETED (:status result)))
    (is (= ["sh" "-c" "echo hi"] (:command @started)))
    (is (= "sh -c echo hi" (:purpose @started)))))

(deftest post-start-view-failure-keeps-the-real-terminal
  (let [call (shell-port/shell-call-port
              {:runner :runner
               :start! (fn [_ _] :session)
               :subscribe! (fn [_ _]
                             (throw (ex-info "view exploded" {})))
               :await (fn [_]
                        {:status :COMPLETED
                         :request-id "r1"
                         :exit-code 0
                         :output {:stdout {:inline "" :bytes 0}
                                  :stderr {:inline "" :bytes 0}}})
               :status (fn [_]
                         {:done? true
                          :write-gate-held? false
                          :process {:alive? false}
                          :known-child-activities []})})
        result (call {:agent-id "agent" :call-id "call-1"}
                     {:command "echo"
                      :operation-id "call-1:call:1"
                      :on-command-view (fn [_])})]
    (is (= :COMPLETED (:status result)))
    (is (= "view exploded" (get-in result [:live-view-error :message])))
    (is (= "r1" (:request-id result)))))

(deftest post-start-await-failure-is-uncertain
  (let [call (shell-port/shell-call-port
              {:runner :runner
               :start! (fn [_ _] :session)
               :await (fn [_]
                        (throw (ex-info "await exploded" {})))
               :status (fn [_]
                         {:done? true
                          :write-gate-held? true
                          :process {:alive? true}
                          :known-child-activities [{:pid 7 :alive? true}]})
               :result (fn [_]
                         {:status :UNCERTAIN :request-id "r1"})})
        result (call {:agent-id "agent" :call-id "call-1"}
                     {:command "slow"
                      :operation-id "call-1:call:1"})]
    (is (= :UNCERTAIN (:status result)))
    (is (= "call-1:call:1" (:request-id result)))
    (is (= "await exploded" (get-in result [:error :message])))
    (is (= :UNCERTAIN (get-in result [:last-result :status])))))

(deftest term-fn-resolves-the-live-holder-term
  (let [started (atom nil)
        call (shell-port/shell-call-port
              {:runner :runner
               :term-fn (fn [_ctx] 7)
               :start! (fn [_ request] (reset! started request) :session)
               :subscribe! (fn [_ _] (fn [] nil))
               :status (fn [_]
                         {:done? true
                          :write-gate-held? false
                          :process {:alive? false}
                          :known-child-activities []})
               :await (fn [_]
                        {:status :COMPLETED
                         :request-id "r1"
                         :exit-code 0
                         :output {:stdout {:inline "" :bytes 0}
                                  :stderr {:inline "" :bytes 0}}})})
        result (call {:agent-id "agent-4"
                      :claim-id "claim-1"
                      :task-id "task-1"
                      :call-id "call-1"}
                     {:command "sleep 1"
                      :operation-id "call-1:call:1"})]
    (is (= :COMPLETED (:status result)))
    (is (= 7 (:token-term @started))
        "the host resolves the term; the worker does not supply it")))

(deftest an-explicit-stale-term-is-not-replaced
  (let [started (atom nil)
        call (shell-port/shell-call-port
              {:runner :runner
               :term-fn (fn [_ctx] 7)
               :start! (fn [_ request] (reset! started request) :session)
               :subscribe! (fn [_ _] (fn [] nil))
               :status (fn [_]
                         {:done? true
                          :write-gate-held? false
                          :process {:alive? false}
                          :known-child-activities []})
               :await (fn [_]
                        {:status :COMPLETED
                         :request-id "r1"
                         :exit-code 0
                         :output {:stdout {:inline "" :bytes 0}
                                  :stderr {:inline "" :bytes 0}}})})
        result (call {:agent-id "agent-4"
                      :token-term 6
                      :claim-id "claim-1"
                      :task-id "task-1"
                      :call-id "call-1"}
                     {:command "true"
                      :operation-id "call-1:call:1"})]
    (is (= :COMPLETED (:status result)))
    (is (= 6 (:token-term @started))
        "a caller-supplied term stays authoritative so the board can refuse it")))

(deftest append-bounded-stops-copying-at-the-limit
  (let [append (var-get #'hc.server.program.shell-port/append-bounded)
        full (apply str (repeat 16 "x"))]
    (is (= ["abc" false] (append "" "abc" 10)))
    (is (= ["abc" true] (append "abc" "def" 3)))
    (is (= 16 (count (first (append full "more" 16)))))
    (is (identical? full (first (append full "more" 16)))
        "an already-full view is returned without a new string")))
