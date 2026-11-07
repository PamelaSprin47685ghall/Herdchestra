(ns hc.compose.owner.terminal-contract-test
  "Regressions for the model claim/terminal contract.

  A completed model event must finish its run, clear the assistant streaming
  flag, preserve citations, and fix the call state.  A start failure must
  never leave a claimed call in `:dispatching`; the scheduler handshake must
  recover instead of wedging after an interrupted commit or ack."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.guards.scheduler :as scheduler]
            [hc.compose.owner :as owner]
            [hc.compose.owner.dispatch :as dispatch]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.state :as state]
            [hc.compose.retry.planner :as retry]))

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

(defn- call-state
  [handle call-id]
  (get-in (snapshot handle) [:state :calls call-id :state]))

(defn- with-owner
  [deps f]
  (let [handle (owner/start-owner! deps)]
    (try
      (f handle)
      (finally
        ((:stop! handle))))))

(defn- claimed-model-state
  []
  (let [base (state/initial-state
              {:session-id "terminal-contract"
               :models {:main "main-model" :task "task-model"}})
        [base call] (state/create-call
                     base
                     {:kind :model
                      :role :plan
                      :lane :plan
                      :agent-id :plan
                      :request {:snapshot {:messages []}}})
        retry-state (:state (retry/init-call
                             {:call-id (:call-id call)
                              :attempt-id "attempt-1"
                              :now 1
                              :policy (:retry-policy base)}))
        call (assoc call
                    :state :dispatching
                    :attempt-id :attempt-1
                    :retry retry-state)
        base (-> base
                 (state/put-call call)
                 (state/add-attempt-node call :attempt-1)
                 (state/start-run call :attempt-1 1))]
    [base call]))

(deftest completed-event-fixes-the-call-and-preserves-citations
  (let [[base call] (claimed-model-state)
        citations [{:title "Source" :url "https://example.test/source"}]
        handled (handlers/apply-message
                 base
                 {:type :model/event
                  :event {:event :completed
                          :call-id (:call-id call)
                          ;; transport spelling, not the scheduler keyword
                          :attempt-id "attempt-1"
                          :text "answer [1]"
                          :citations citations
                          :search-refs {:query "q"}
                          :search-source-list ["web"]}}
                 {:now 2})
        queued (:state handled)
        terminal (first (:terminals queued))
        catalog (scheduler/catalog [(guards/accept-call-terminal-guard)])
        prepared (scheduler/step catalog queued {:now 3}
                                 (scheduler/new-scheduler {:quota 8}))
        after (:state (:transition prepared))
        assistant (some #(when (and (= :assistant (:role %))
                                    (not (:nudge? %)))
                           %)
                        (:conversation after))]
    (testing "the completed event queues exactly one terminal"
      (is (= 1 (count (:terminals queued))))
      (is (= :succeeded (:status terminal)))
      (is (= "attempt-1" (:attempt-id terminal))))
    (testing "the P1 guard fixes the call, not only the run"
      (is (= :prepared (:status prepared)))
      (is (= :succeeded (get-in after [:calls (:call-id call) :state])))
      (is (nil? (get-in after [:calls (:call-id call) :attempt-id]))))
    (testing "the assistant message is final and keeps its citations"
      (is (some? assistant))
      (is (false? (:streaming? assistant)))
      (is (= citations (:citations assistant)))
      (is (= {:query "q"} (:search-refs assistant))))))

(deftest real-streaming-completion-fixes-call-and-citations
  (let [citations [{:title "Source" :url "https://example.test/source"}]
        gateway {:start! (fn [request deliver!]
                           (future
                             (deliver! {:event :chunk :text "Answer "})
                             (Thread/sleep 20)
                             (deliver! {:event :completed
                                        :text "Answer [1]"
                                        :citations citations
                                        :search-refs {:query "q"}}))
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "terminal-stream" :ephemeral? true
       :model-gateway gateway}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "hello"}})
        (is (wait-for #(= :succeeded (call-state handle "call-1")) 5000))
        (let [snap (snapshot handle)
              message (some #(when (and (= :assistant (:role %))
                                        (not (:nudge? %)))
                               %)
                            (get-in snap [:state :conversation]))]
          (is (some? message))
          (is (false? (:streaming? message)))
          (is (= citations (:citations message)))
          (is (= {:query "q"} (:search-refs message))))))))

(deftest failed-model-event-preserves-search-error-classification
  (let [[base call] (claimed-model-state)
        error {:code :SEARCH_SERVICE_ERROR
               :message "required web search failed"
               :kind :search-required-failed
               :category :permanent
               :disposition :fail
               :details {:service-id "broken"}}
        handled (handlers/apply-message
                 base
                 {:type :model/event
                  :event {:event :failed
                          :call-id (:call-id call)
                          :attempt-id "attempt-1"
                          :error error}}
                 {:now 2})
        queued (:state handled)
        terminal (first (:terminals queued))
        prepared (scheduler/step
                  (scheduler/catalog [(guards/accept-call-terminal-guard)])
                  queued {:now 3}
                  (scheduler/new-scheduler {:quota 8}))
        after (:state (:transition prepared))
        planned (scheduler/step
                 (scheduler/catalog [(guards/plan-retry-guard)])
                 after {:now 4 :retry-policy (:retry-policy after)}
                 (scheduler/new-scheduler {:quota 8}))
        after-retry (:state (:transition planned))]
    (is (= :failed (:status terminal)))
    (is (= :search-required-failed (get-in terminal [:error :kind])))
    (is (= :permanent (get-in terminal [:error :category])))
    (is (= :SEARCH_SERVICE_ERROR (get-in terminal [:error :code])))
    (is (= :failed (get-in after [:runs (state/run-id (:call-id call)
                                                      "attempt-1")
                                 :status])))
    ;; P1 leaves a failed model call running for P2.  The actionable error
    ;; must be available as :last-error for the retry planner and publisher.
    (is (= :running (get-in after [:calls (:call-id call) :state])))
    (is (= :search-required-failed
           (get-in after [:calls (:call-id call) :last-error :kind])))
    (is (= :permanent
           (get-in after [:calls (:call-id call) :last-error :category])))
    (testing "P2 keeps the search cause on the terminal call"
      (is (= :prepared (:status planned)))
      (is (= :failed (get-in after-retry [:calls (:call-id call) :state])))
      (is (= :search-required-failed
             (get-in after-retry [:calls (:call-id call) :error :kind])))
      (is (= :SEARCH_SERVICE_ERROR
             (get-in after-retry [:calls (:call-id call) :error :code])))
      (is (true? (get-in after-retry [:calls (:call-id call)
                                      :error :retry-exhausted?]))))))

(deftest model-start-failure-never-stays-dispatching
  (with-owner
    {:session-id "terminal-start-failure" :ephemeral? true
     :model-gateway {:start! (fn [_request _deliver!] nil)
                     :cancel! (fn [_] nil)}}
    (fn [handle]
      ((:submit-command! handle)
       {:command-id "c1" :command-epoch 1
        :payload {:kind :send-message :text "hello"}})
      (is (wait-for #(and (call-state handle "call-1")
                          (not= :dispatching (call-state handle "call-1")))
                    3000)))))

(deftest dispatch-start-failure-posts-a-terminal
  (let [posted (atom [])
        dispatcher (dispatch/create
                    {:post! (fn [message]
                              (swap! posted conj message)
                              {:ok? true})
                     :model-gateway {:start! (fn [_request _deliver!] nil)
                                     :cancel! (fn [_] nil)}})]
    (try
      (let [result (dispatch/start! dispatcher
                                    {:kind :model
                                     :call-id "call-1"
                                     :attempt-id "attempt-1"
                                     :request {:snapshot {:messages []}}})
            terminal (first (filter #(= :call/terminal (:type %)) @posted))]
        (is (false? (:ok? result)))
        (is (true? (:posted? result)))
        (is (= "call-1" (:call-id terminal)))
        (is (= "attempt-1" (:attempt-id terminal)))
        (is (= :failed (:status terminal)))
        (is (= :model-start-failed (get-in terminal [:error :kind])))
        (is (= :permanent (get-in terminal [:error :category]))))
      (finally
        (dispatch/stop! dispatcher)))))

(deftest start-failure-preserves-search-error-data
  (let [posted (atom [])
        dispatcher (dispatch/create
                    {:post! (fn [message]
                              (swap! posted conj message)
                              {:ok? true})
                     :model-gateway
                     {:start! (fn [_request _deliver!]
                                (throw (ex-info
                                        "required search failed"
                                        {:code :SEARCH_FAILED
                                         :kind :search-required-failed
                                         :category :permanent
                                         :details {:service-id "broken"}})))
                      :cancel! (fn [_] nil)}})]
    (try
      (dispatch/start! dispatcher
                       {:kind :model
                        :call-id "call-1"
                        :attempt-id "attempt-1"
                        :request {:snapshot {:messages []}}})
      (let [terminal (first (filter #(= :call/terminal (:type %)) @posted))
            error (:error terminal)]
        (is (= :failed (:status terminal)))
        (is (= :search-required-failed (:kind error)))
        (is (= :permanent (:category error)))
        (is (= :SEARCH_FAILED (:code error)))
        (is (= {:service-id "broken"} (:details error))))
      (finally
        (dispatch/stop! dispatcher)))))

(deftest duplicate-dispatch-by-attempt-id-is-idempotent
  (let [starts (atom 0)
        dispatcher (dispatch/create
                    {:post! (fn [_] {:ok? true})
                     :model-gateway
                     {:start! (fn [request _deliver!]
                                (swap! starts inc)
                                {:attempt-id (:attempt-id request)})
                      :cancel! (fn [_] nil)}})
        effect {:kind :model
                :call-id "call-1"
                :attempt-id "attempt-1"
                :request {:snapshot {:messages []}}}]
    (try
      (is (true? (:ok? (dispatch/start! dispatcher effect))))
      (let [replayed (dispatch/start! dispatcher effect)]
        (is (true? (:ok? replayed)))
        (is (true? (:already-running? replayed))))
      (is (= 1 @starts))
      (finally
        (dispatch/stop! dispatcher)))))

(deftest scheduler-recovers-a-prepared-but-uncommitted-transition
  (with-owner
    {:session-id "scheduler-recovery" :ephemeral? true
     :model-gateway {:start! (fn [_request _deliver!] nil)
                     :cancel! (fn [_] nil)}}
    (fn [handle]
      ;; `start-owner!` enqueues :recover before returning.  Wait for that
      ;; round to finish before replacing the scheduler; otherwise the
      ;; recover round can overwrite the injected pending handshake.
      (is (:idle? ((:await-idle! handle) 5000)))
      (let [runtime (get handle :hc.compose.owner/runtime)
            state-value (:state @(:state runtime))
            candidate (assoc state-value :scheduler-recovery-marker true)
            pending (assoc (scheduler/new-scheduler
                            {:quota 8
                             :attempt-prefix "attempt-test-"
                             :transition-prefix "transition-test-"})
                           :pending-dispatch
                           {:transition-id :transition-test-1
                            :attempt-id :attempt-test-1
                            :guard-id :recovery-test
                            :state candidate
                            :dispatch []
                            :committed? false})]
        (reset! (:scheduler runtime) pending)
        ((:submit! handle) {:type :wake :reason :recovery-test})
        ;; The marker is committed before the handshake is acknowledged.
        ;; Wait for both; reading the scheduler right after the marker can
        ;; observe the pending dispatch under load.
        (is (wait-for #(and (true? (get-in (snapshot handle)
                                           [:state :scheduler-recovery-marker]))
                            (nil? (:pending-dispatch @(:scheduler runtime))))
                      3000))
        (is (nil? (:pending-dispatch @(:scheduler runtime))))))))

(deftest stale-dispatch-after-terminal-is-not-started
  (let [starts (atom 0)
        base (state/initial-state
              {:session-id "stale-dispatch"
               :models {:main "main-model" :task "task-model"}})
        [base call] (state/create-call
                     base
                     {:kind :model
                      :role :plan
                      :lane :plan
                      :agent-id :plan
                      :request {:snapshot {:messages []}}})
        base (state/terminal-call base call :cancelled nil
                                  {:kind :user-cancel} 1)]
    (with-owner
      {:session-id "stale-dispatch" :ephemeral? true
       :initial-state base
       :model-gateway
       {:start! (fn [request _deliver!]
                  (swap! starts inc)
                  {:attempt-id (:attempt-id request)})
        :cancel! (fn [_] nil)}}
      (fn [handle]
        ;; `start-owner!` enqueues :recover before returning.  Wait for that
        ;; round to finish before replacing the scheduler; otherwise the
        ;; recover round can overwrite the injected pending handshake.
        (is (:idle? ((:await-idle! handle) 5000)))
        (let [runtime (get handle :hc.compose.owner/runtime)
              state-value (:state @(:state runtime))
              candidate (assoc state-value :stale-skip-marker true)
              pending (assoc (scheduler/new-scheduler
                              {:quota 8
                               :attempt-prefix "attempt-stale-"
                               :transition-prefix "transition-stale-"})
                             :pending-dispatch
                             {:transition-id :transition-stale-1
                              :attempt-id :attempt-stale-1
                              :guard-id :stale-dispatch-test
                              :state candidate
                              :dispatch [{:kind :model
                                          :call-id (:call-id call)
                                          :attempt-id "attempt-old"
                                          :request {:snapshot {:messages []}}}]
                              :committed? false})]
          (reset! (:scheduler runtime) pending)
          ((:submit! handle) {:type :wake :reason :stale-dispatch-test})
          (is (wait-for #(true? (get-in (snapshot handle)
                                        [:state :stale-skip-marker]))
                        3000))
          (is (zero? @starts)))))))

(deftest settled-resource-cancel-terminal-clears-resource-alive
  (let [base (state/initial-state
              {:session-id "cancel-feedback"
               :models {:main "main-model" :task "task-model"}})
        [base call] (state/create-call
                     base
                     {:kind :tool
                      :role :plan
                      :lane :execution
                      :agent-id :plan
                      :request {:name "shell"}})
        base (state/terminal-call base call :succeeded {:ok true} nil 1)
        attempt-id "attempt-1"
        node-id (state/attempt-node-id (:call-id call) attempt-id)
        run-id (get-in base [:cancel :active-run-id])
        base (-> base
                 (state/add-attempt-node call attempt-id)
                 (assoc-in [:cancel :nodes node-id :state] :succeeded)
                 (assoc-in [:cancel :nodes node-id :outcome] :succeeded)
                 (assoc-in [:cancel :nodes node-id :resource-alive?] true))]
    (with-owner
      {:session-id "cancel-feedback" :ephemeral? true
       :initial-state base
       :cancel-resource!
       (fn [_entry]
         {:ok? true
          :terminal {:status :cancelled
                     :payload {:stopped true}}})}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "stop-1" :command-epoch 1
          :payload {:kind :stop-run :origin :operator-stop}})
        (is (wait-for #(false? (get-in (snapshot handle)
                                       [:state :cancel :nodes node-id
                                        :resource-alive?]))
                      3000))
        (is (wait-for #(= :cancelled
                          (get-in (snapshot handle)
                                  [:state :cancel :nodes run-id :state]))
                      3000))))))
