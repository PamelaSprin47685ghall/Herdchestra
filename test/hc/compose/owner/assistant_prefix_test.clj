(ns hc.compose.owner.assistant-prefix-test
  "A recorded assistant answer keeps its text.

  The live outage 07753a: a run streamed `\"Stub \"` into the assistant
  message, then the operator stopped it and the cancellation terminal
  arrived with empty text.  The conversation replaced `\"Stub \"` with `\"\"`,
  so the next outbound request carried an empty assistant message where the
  previous request had text, and Pair Hint reported `:PREFIX_DRIFT` at that
  index.  The drift report was correct; the producer was wrong.

  These regressions drive the real `start-run` / `append-run-text` /
  `finish-run` / `project-conversation` path, so a future producer change
  that collapses recorded text fails here instead of at runtime."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.state :as state]
            [hc.core.pair-hint :as pair-hint]
            [hc.core.pair-hint.prefix :as prefix]))

(defn- model-state
  "One claimed model call with a started run, built by the real functions."
  []
  (let [base (state/initial-state {:session-id "assistant-prefix"})
        [base call] (state/create-call
                     base
                     {:kind :model
                      :role :main
                      :lane :execution
                      :agent-id :main
                      :request {:snapshot {:messages []}}})
        call (assoc call :state :running :attempt-id :attempt-1)
        base (-> base
                 (state/put-call call)
                 (state/start-run call :attempt-1 1))]
    [base call (state/run-id (:call-id call) :attempt-1)]))

(defn- assistant-entries
  [state]
  (filterv #(= :assistant (:role %)) (:conversation state)))

(defn- outbound
  "The exact messages the model gateway would receive."
  [state]
  (let [result (state/project-conversation state (:conversation state))]
    (when-not (:ok? result)
      (throw (ex-info "scope projection refused the conversation"
                      {:error (:error result)})))
    (:messages result)))

(deftest cancelled-terminal-keeps-the-text-the-operator-saw
  (let [[base _call run-id] (model-state)
        streamed (-> base
                     (state/append-run-text run-id "Stub " 2))
        stopped (state/finish-run streamed run-id :cancelled 3
                                  {:text "" :cancel-reason :user-cancel})
        entry (first (assistant-entries stopped))]
    (is (= "Stub " (:text entry))
        "a cancellation carries no new text, so it cannot erase recorded text")
    (is (= :cancelled (:status entry)))
    (is (true? (:cancelled? entry)))
    (testing "the outbound copy still carries the same text"
      (is (= "Stub " (:text (last (outbound stopped))))))))

(deftest cancelled-terminal-with-absent-text-keeps-the-stream
  (let [[base _call run-id] (model-state)
        streamed (state/append-run-text base run-id "Partial answer" 2)
        stopped (state/finish-run streamed run-id :cancelled 3
                                  {:cancel-reason :user-cancel})]
    (is (= "Partial answer"
           (:text (first (assistant-entries stopped)))))))

(deftest completed-terminal-still-replaces-text
  (let [[base _call run-id] (model-state)
        streamed (state/append-run-text base run-id "Stub " 2)
        finished (state/finish-run streamed run-id :finished 3
                                   {:text "Stub complete"})]
    (is (= "Stub complete"
           (:text (first (assistant-entries finished))))
        "real text from the terminal wins")))

(deftest the-stop-sequence-extends-one-pair-hint-chain
  ;; End to end through the producer and Pair Hint together: send, stop, send.
  (let [[base _call run-id] (model-state)
        user (update base :conversation conj
                     {:role :user :message-id "user-0" :text "first"})
        streamed (state/append-run-text user run-id "Stub " 2)
        stopped (state/finish-run streamed run-id :cancelled 3
                                  {:text "" :cancel-reason :user-cancel})
        before-stop (outbound streamed)
        after-stop (outbound stopped)]
    (is (= (mapv #(select-keys % [:role :text]) before-stop)
           (mapv #(select-keys % [:role :text]) after-stop))
        "the stop must not change any earlier rendered message")
    (testing "Pair Hint sees a prefix, not drift"
      (let [chain (prefix/chain-of before-stop {:kind :relay :id "start"})]
        (is (true? (:ok? (prefix/verify-prefix chain after-stop))))))
    (testing "the next send extends the same chain"
      (let [request {:attempt-id "attempt-1"
                     :call-id "call-1"
                     :snapshot {:role :main :messages after-stop}}
            session (pair-hint/create-session {:explicit-language :en})
            first-projection (pair-hint/project-request
                              session request
                              {:placement {:call-id "call-1"}
                               :context {:context-window 1000}})
            next-messages (conj after-stop
                                {:role :user :message-id "user-1"
                                 :text "second"})
            next-projection (pair-hint/project-request
                             (:session first-projection)
                             {:attempt-id "attempt-2"
                              :call-id "call-2"
                              :snapshot {:role :main :messages next-messages}}
                             {:placement {:call-id "call-2"}
                              :context {:context-window 1000}})]
        (is (true? (:ok? first-projection)))
        (is (true? (:ok? next-projection)))
        ;; `verify-prefix` returns `{:ok? true :chain ...}`, not a boolean.
        (is (true? (:ok? (pair-hint/verify-prefix
                          (get-in first-projection [:session :chain])
                          (get-in next-projection
                                  [:request :snapshot :messages])))))))))

(deftest a-real-text-rewrite-is-still-reported-as-drift
  ;; The fix must not blunt the law: a genuine change to recorded text, made
  ;; by something other than the blank-terminal bug, is still drift.
  (let [[base _call run-id] (model-state)
        user (update base :conversation conj
                     {:role :user :message-id "user-0" :text "first"})
        streamed (state/append-run-text user run-id "Stub " 2)
        rewritten (update streamed :conversation
                          (fn [messages]
                            (mapv (fn [message]
                                    (if (= :assistant (:role message))
                                      (assoc message :text "edited")
                                      message))
                                  messages)))
        chain (prefix/chain-of (outbound streamed) {:kind :relay :id "start"})
        result (prefix/verify-prefix chain (outbound rewritten))]
    (is (false? (:ok? result)))
    (is (= :PREFIX_DRIFT (get-in result [:error :code])))
    (is (= 1 (get-in result [:error :index])))))

(defn- actor-model-state
  "One claimed swarm-actor model call with a started run."
  []
  (let [base (state/initial-state {:session-id "actor-isolation"})
        [base call] (state/create-call
                     base
                     {:kind :model
                      :role :task
                      :lane :execution
                      :agent-id :worker-1
                      :actor-attempt-id :actor-attempt-1
                      :request {:snapshot {:messages []}}})
        call (assoc call :state :running :attempt-id :attempt-1)
        base (-> base
                 (state/put-call call)
                 (state/start-run call :attempt-1 1))]
    [base call (state/run-id (:call-id call) :attempt-1)]))

(deftest actor-run-text-never-enters-the-shared-conversation
  ;; A swarm worker keeps its answer in its own `:context`.  The Owner
  ;; conversation is the central Plan/Reviewer snapshot, so a worker reply
  ;; there would both leak another agent's text and move the prefix chain.
  (let [[base _call run-id] (actor-model-state)
        streamed (state/append-run-text base run-id "worker says" 2)
        finished (state/finish-run streamed run-id :finished 3
                                   {:text "worker says done"})]
    (is (empty? (assistant-entries streamed))
        "a streaming actor turn writes no shared assistant message")
    (is (empty? (assistant-entries finished))
        "a completed actor turn writes no shared assistant message")
    (is (empty? (outbound finished))
        "the central outbound conversation stays empty")))

(deftest actor-runs-do-not-block-the-conversation-gate
  ;; Workers stay concurrent: their runs never gate a central user turn.
  (let [[base _call _run-id] (actor-model-state)]
    (is (true? (state/conversation-stable? base))
        "a streaming worker does not gate a central user turn"))
  (let [base (state/initial-state {:session-id "central-stability"})
        [base call] (state/create-call
                     base
                     {:kind :model
                      :role :main
                      :lane :plan
                      :agent-id :plan
                      :request {:snapshot {:messages []}}})
        call (assoc call :state :running :attempt-id :attempt-1)
        base (-> base
                 (state/put-call call)
                 (state/start-run call :attempt-1 1))]
    (is (false? (state/conversation-stable? base))
        "a central run still gates the next user turn")))
