(ns hc.compose.guards.owner-integration-test
  "Real Owner scheduling checks for the pure guard scheduler contract.

  These tests start the actual mailbox, store, and guard loop.  They cover
  the quota requeue path, the absence of a hidden second step cap, the
  committed-before-dispatch claim order, and the P4 blocked diagnostic."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hc.compose.mission.actors :as actors]
            [hc.compose.owner.dispatch :as dispatch]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner :as owner]
            [hc.compose.owner.runtime :as runtime]
            [hc.compose.owner.state :as state]
            [hc.compose.owner.store :as store]
            [hc.compose.reports.acceptance :as acceptance]
            [hc.compose.guards.scheduler :as scheduler]
            [hc.core.ufss :as ufss]))

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

(defn- stats
  [handle]
  ((:stats handle)))

(defn- with-owner
  [deps f]
  (let [handle (owner/start-owner! deps)]
    (try
      (f handle)
      (finally
        ((:stop! handle))))))

(defn- terminal-message
  [i]
  {:call-id (str "missing-" i)
   :attempt-id (str "attempt-" i)
   :status :failed
   :at 1})

(defn- initial-with-terminals
  [n]
  (assoc (state/initial-state {:session-id "s1"})
         :terminals (mapv terminal-message (range n))))

(deftest quota-requeue-processes-every-round
  (with-owner
    {:session-id "s1"
     :ephemeral? true
     :initial-state (initial-with-terminals 200)
     :quota 32}
    (fn [handle]
      (is (:idle? ((:await-idle! handle) 20000)))
      (let [snap (snapshot handle)
            stats (stats handle)]
        (is (zero? (count (get-in snap [:state :terminals]))))
        (is (= 200 (:guard-transitions stats)))
        (is (> (:messages stats) 2)
            "quota exhaustion must enqueue visible wake messages")
        (is (zero? (:errors stats)))))))

(deftest no-hidden-512-step-cap
  ;; One round with quota 600.  The old runtime stopped silently at 512.
  (with-owner
    {:session-id "s1"
     :ephemeral? true
     :initial-state (initial-with-terminals 600)
     :quota 600}
    (fn [handle]
      (is (:idle? ((:await-idle! handle) 20000)))
      (let [snap (snapshot handle)
            stats (stats handle)]
        (is (zero? (count (get-in snap [:state :terminals]))))
        (is (= 600 (:guard-transitions stats)))
        (is (zero? (:errors stats)))))))

(deftest claim-is-committed-before-model-dispatch
  (let [started (promise)
        gateway {:start! (fn [request deliver!]
                           (deliver started {:request request
                                             :deliver! deliver!})
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "s1"
       :ephemeral? true
       :model-gateway gateway}
      (fn [handle]
        (is (= :accepted
               (:status ((:submit-command! handle)
                         {:command-id "c1"
                          :command-epoch 1
                          :payload {:kind :send-message
                                    :text "hello"}}))))
        (let [{:keys [request deliver!]} (deref started 5000 nil)
              call-id (:call-id request)
              call (get-in (snapshot handle) [:state :calls call-id])]
          (is (some? request))
          (is (= :dispatching (:state call))
              "the claim must be committed before the gateway starts")
          (is (= (:attempt-id request) (:attempt-id call))
              "dispatch and committed state must carry one attempt id")
          (deliver! {:event :completed :text "done"})
          (is (wait-for #(= :succeeded
                            (get-in (snapshot handle)
                                    [:state :calls call-id :state]))
                        5000))
          (is (= "done"
                 (-> (snapshot handle) :state :conversation last :text))))))))

(deftest blocked-diagnostic-is-committed
  (let [events (atom [])]
    (with-owner
      {:session-id "s1"
       :ephemeral? true
       :quota 32
       :on-effect! (fn [event] (swap! events conj event))}
      (fn [handle]
        (is (:idle? ((:await-idle! handle) 5000)))
        (let [diagnostics (get-in (snapshot handle) [:state :diagnostics])
              blocked (some #(when (= :BLOCKED (:code %)) %) diagnostics)]
          (is (some? blocked)
              "an active tenure with no work must record an explicit dead end")
          (is (= :no-enabled-guard (:reason blocked)))
          (is (pos? (:blocked (stats handle))))
          (is (some #(= :owner/blocked (:type %)) @events)
              "the handling path must receive a blocked notification"))))))

(defn- fail-first-blocked-commit-store
  []
  (let [delegate (store/memory-store)
        failed? (atom false)]
    (reify store/Store
      (load-state [_]
        (store/load-state delegate))
      (commit! [_ expected new-state]
        (if (and (not @failed?)
                 (some #(= :BLOCKED (:code %)) (:diagnostics new-state)))
          (do
            (reset! failed? true)
            {:ok? false
             :error {:code :VERSION_CONFLICT
                     :message "injected blocked-diagnostic conflict"}})
          (store/commit! delegate expected new-state)))
      (close! [_]
        (store/close! delegate)))))

(deftest blocked-diagnostic-retries-after-commit-failure
  (let [events (atom [])
        blocked-store (fail-first-blocked-commit-store)]
    (with-owner
      {:session-id "s1"
       :store blocked-store
       :initial-state (state/initial-state {:session-id "s1"})
       :quota 32
       :on-effect! (fn [event] (swap! events conj event))}
      (fn [handle]
        (is (:idle? ((:await-idle! handle) 5000)))
        (is (not (some #(= :BLOCKED (:code %))
                       (get-in (snapshot handle) [:state :diagnostics])))
            "the injected conflict must not commit a diagnostic")
        (is (zero? (:blocked (stats handle))))
        (is (pos? (:errors (stats handle))))
        ((:submit! handle) {:type :wake :reason :retry-blocked})
        (is (:idle? ((:await-idle! handle) 5000)))
        (is (some #(= :BLOCKED (:code %))
                  (get-in (snapshot handle) [:state :diagnostics]))
            "the next blocked observation must retry the commit")
        (is (= 1 (:blocked (stats handle))))
        (is (some #(= :owner/blocked (:type %)) @events))))))

(deftest wake-submit-fallback-schedules-a-timer
  (let [messages (atom [])
        dispatcher (dispatch/create
                    {:post! (fn [message]
                              (swap! messages conj message)
                              {:ok? true})})
        test-runtime {:dispatcher dispatcher
                      :closed? (atom false)}]
    (try
      (is (true? (#'runtime/schedule-wake-fallback!
                  test-runtime :quota-requeue
                  (System/currentTimeMillis))))
      (is (wait-for #(some (fn [message]
                             (= :timer/fire (:type message)))
                           @messages)
                    2000))
      (is (not-any? #(= :call/terminal (:type %)) @messages)
          "a wake fallback is a self-wake, not a call terminal")
      (finally
        (dispatch/stop! dispatcher)))))

(deftest non-model-settlement-exposes-unobserved-responsibility
  (let [base (state/initial-state {:session-id "s1"})
        [base call] (state/create-call
                     base
                     {:kind :shell
                      :role :plan
                      :lane :execution
                      :agent-id :plan
                      :request {}
                      :created-at 1})
        call-id (:call-id call)
        attempt-id "a1"
        node-id (state/attempt-node-id call-id attempt-id)
        base (-> base
                 (assoc-in [:calls call-id :state] :dispatching)
                 (assoc-in [:calls call-id :attempt-id] attempt-id)
                 (state/add-attempt-node call attempt-id))
        message {:type :dispatch/settled
                 :call-id call-id
                 :attempt-id attempt-id
                 :kind :shell}
        once (handlers/apply-message base message {:now 1})
        twice (handlers/apply-message (:state once) message {:now 2})]
    (is (= :dispatching (get-in once [:state :calls call-id :state]))
        "settlement must not write a terminal outcome")
    (is (true? (get-in once [:state :cancel :nodes node-id
                            :resource-alive?]))
        "settlement must not clear the live-resource flag")
    (is (true? (get-in once [:state :calls call-id
                            :external-outcome-unobserved?])))
    (is (true? (get-in once [:state :cancel :nodes node-id
                            :external-outcome-unobserved?])))
    (is (= 1 (count (filter #(= :EXTERNAL_OUTCOME_UNOBSERVED (:code %))
                            (get-in once [:state :diagnostics])))))
    (is (= 1 (count (filter #(= :EXTERNAL_OUTCOME_UNOBSERVED (:code %))
                            (get-in twice [:state :diagnostics]))))
        "the diagnostic is deduped by attempt id")))

(deftest prepared-dispatch-is-blocked-by-pending-stop
  (let [starts (atom 0)
        events (atom [])
        initial (-> (state/initial-state {:session-id "s1"})
                    (assoc :pending-cancels
                           [{:command-id "stop"
                             :origin :operator-stop
                             :at 1}])
                    (assoc-in [:calls "x"]
                              {:call-id "x"
                               :kind :model
                               :state :ready
                               :agent-id :reviewer
                               :request {:snapshot {:messages []}}
                               :created-at 1}))
        custom-catalog
        (scheduler/catalog
         [(scheduler/guard
           :always-claim
           :p3-progress
           (fn [s _]
             (when (= :ready (get-in s [:calls "x" :state])) true))
           (fn [s _ _ attempt-id]
             {:state (-> s
                         (assoc-in [:calls "x" :state] :dispatching)
                         (assoc-in [:calls "x" :attempt-id] attempt-id))
              :claim {:path [:calls "x" :state]
                      :work-id "x"
                      :attempt-path [:calls "x" :attempt-id]
                      :from :ready
                      :to :dispatching}
              :dispatch [{:kind :model
                          :call-id "x"
                          :request {:snapshot {:messages []}}}]}))])]
    (with-redefs [guards/catalog (fn [] custom-catalog)]
      (with-owner
        {:session-id "s1"
         :ephemeral? true
         :initial-state initial
         :model-gateway {:start! (fn [_request _deliver!]
                                   (swap! starts inc)
                                   {:attempt-id :a1})
                         :cancel! (fn [_] nil)}
         :on-effect! (fn [event] (swap! events conj event))}
        (fn [handle]
          (is (:idle? ((:await-idle! handle) 5000)))
          (is (zero? @starts)
              "a prepared call effect must not start after an operator stop")
          (is (= :ready
                 (get-in (snapshot handle) [:state :calls "x" :state])))
          (is (some #(and (= :dispatch/skipped (:type %))
                          (= :stop-pending (:reason %)))
                    @events)))))))

(deftest pending-dispatcher-timer-is-not-a-dead-end
  (let [initial (assoc (state/initial-state {:session-id "s1"})
                       :test-timer {:state :ready})
        custom-catalog
        (scheduler/catalog
         [(scheduler/guard
           :arm-test-timer
           :p1-closure
           (fn [s _]
             (when (= :ready (get-in s [:test-timer :state])) true))
           (fn [s ctx _ attempt-id]
             {:state (-> s
                         (assoc-in [:test-timer :state] :dispatching)
                         (assoc-in [:test-timer :attempt-id] attempt-id))
              :claim {:path [:test-timer :state]
                      :work-id :test-timer
                      :attempt-path [:test-timer :attempt-id]
                      :from :ready
                      :to :dispatching}
              :dispatch [{:kind :timer
                          :call-id "test-timer"
                          :retry-at (+ (:now ctx) 60000)}]}))])]
    (with-redefs [guards/catalog (fn [] custom-catalog)]
      (with-owner
        {:session-id "s1"
         :ephemeral? true
         :initial-state initial}
        (fn [handle]
          (is (:idle? ((:await-idle! handle) 5000)))
          (let [runtime (get handle :hc.compose.owner/runtime)
                snap (snapshot handle)]
            (is (not (some #(= :BLOCKED (:code %))
                           (get-in snap [:state :diagnostics])))
                "a pending dispatcher timer is a legal wait, not a dead end")
            (is (= 1 (:timers (dispatch/stats (:dispatcher runtime)))))
            (is (zero? (:blocked (stats handle))))
            (is (= 1 (:guard-transitions (stats handle))))))))))

(deftest report-projection-fallback-uses-ufss
  (let [payload {:path "C:\\tmp\\a b"
                 :lines ["one" "two"]
                 :quoted "a \"quote\""}
        state {:acceptance {:terminated? false
                            :accepted {"c1" {:report-id "r1"
                                             :parent-call-id "c1"
                                             :payload payload
                                             :projection :pending}}}
               :conversation []}
        guard (guards/project-accepted-report-guard)
        work (first (acceptance/pending-projections (:acceptance state)))
        result ((:prepare guard) state {:now 1} work nil)
        message (last (get-in result [:state :conversation]))
        text (:text message)]
    (is (= :user (:role message)))
    (is (= payload (:payload message)))
    (is (string? (:payload-edn message)))
    (is (= payload (ufss/read-str text)))
    (is (not (str/includes? text "\\\"")))
    (is (not (str/includes? text "\\n")))))

(deftest tool-result-is-projected-as-ufss
  (let [payload {:path "C:\\tmp\\a b"
                 :lines ["one" "two"]
                 :quoted "a \"quote\""}
        starts (atom 0)
        requests (atom [])
        gateway {:start! (fn [request deliver!]
                           (if (= 1 (swap! starts inc))
                             (do
                               (deliver! {:event :tool-call
                                          :call-id "tool-1"
                                          :tool-name "shell"
                                          :arguments {:cmd "echo"}
                                          :payload {:executor :shell
                                                    :cmd "echo"
                                                    :scope {:kind :reset-scope
                                                            :new-scope-goal "run shell"
                                                            :new-scope-retention-budget 3}}})
                               (deliver! {:event :completed
                                          :text "spawned"}))
                             (do
                               (swap! requests conj request)
                               (deliver! {:event :completed
                                          :text "done"})))
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "s1"
       :ephemeral? true
       :model-gateway gateway
       :executors {:shell (fn [_]
                            {:status :succeeded
                             :payload payload})}}
      (fn [handle]
        (is (= :accepted
               (:status ((:submit-command! handle)
                         {:command-id "c1"
                          :command-epoch 1
                          :payload {:kind :send-message
                                    :text "run a shell"}}))))
        (is (wait-for
             #(= :succeeded
                 (get-in (snapshot handle)
                         [:state :calls "call-1/tool-0" :state]))
             8000))
        (is (wait-for
             #(some (fn [message]
                      (and (= :tool (:role message))
                           (= "call-1/tool-0" (:call-id message))))
                    (get-in (snapshot handle) [:state :conversation]))
             3000))
        (let [message (->> (get-in (snapshot handle) [:state :conversation])
                           (filter #(and (= :tool (:role %))
                                         (= "call-1/tool-0" (:call-id %))))
                           last)
              text (:text message)]
          (is (= payload (ufss/read-str text)))
          (is (not (str/includes? text "\\\"")))
          (is (not (str/includes? text "\\n")))
          (is (false? (:error? message)))
          (is (= :accepted
                 (:status ((:submit-command! handle)
                           {:command-id "c2"
                            :command-epoch 1
                            :payload {:kind :send-message
                                      :text "follow up"}}))))
          (is (wait-for #(seq @requests) 5000))
          (let [request (first @requests)
                messages (get-in request [:snapshot :messages])]
            (is (some (fn [m]
                        (and (= :tool (:role m))
                             (= text (:text m))))
                      messages)
                "the next model snapshot must carry the projected receipt")
            (is (some (fn [m]
                        (and (= :assistant (:role m))
                             (= "call-1/tool-0"
                                (get-in m [:tool-calls 0 :id]))
                             (= {:cmd "echo"}
                                (get-in m [:tool-calls 0 :arguments]))))
                      messages)
                "the assistant tool call must keep the arguments the model sent")))))))

(deftest failed-tool-result-carries-error-flag
  (let [state (-> (state/initial-state {:session-id "s1"})
                  (assoc-in [:calls "tool-1"]
                            {:call-id "tool-1"
                             :kind :tool
                             :state :failed
                             :member "shell"
                             :last-error {:status :FAILED
                                          :message "boom \"quoted\""}
                             :created-at 1}))
        guard (guards/project-tool-result-guard)
        result ((:prepare guard) state {:now 2} {:call-id "tool-1"} nil)
        message (last (get-in result [:state :conversation]))]
    (is (= :tool (:role message)))
    (is (true? (:error? message)))
    (is (= {:status :FAILED :message "boom \"quoted\""}
           (ufss/read-str (:text message))))))

(deftest structured-result-with-text-field-still-renders-ufss
  (let [result {:text "raw \"quoted\" text"
                :stdout "line1\nline2"
                :path "C:\\tmp\\a b"}
        state (-> (state/initial-state {:session-id "s1"})
                  (assoc-in [:calls "tool-1"]
                            {:call-id "tool-1"
                             :kind :tool
                             :state :succeeded
                             :member "shell"
                             :result result
                             :created-at 1}))
        guard (guards/project-tool-result-guard)
        projected ((:prepare guard) state {:now 2} {:call-id "tool-1"} nil)
        message (last (get-in projected [:state :conversation]))
        text (:text message)]
    (is (= :tool (:role message)))
    (is (false? (:error? message)))
    (is (= result (ufss/read-str text)))
    (is (not= "raw \"quoted\" text" text))))

(defn- actor-tool-fixture
  "One actor-owned model call with one terminal child tool call.

  WITH-ACTOR-CALL? controls whether the actor runtime still knows the parent
  attempt.  SLOT-AGENT is the identity currently holding slot-a, so the
  identity fallback, the stale path, and the undeliverable path can all be
  exercised."
  ([with-actor-call?] (actor-tool-fixture with-actor-call? :worker-1))
  ([with-actor-call? slot-agent]
   (let [base (state/initial-state {:session-id "actor-tool"})
         [base parent] (state/create-call
                        base
                        {:kind :model
                         :role :task
                         :lane :execution
                         :tenure-id "tenure-1"
                         :agent-id :worker-1
                         :actor-attempt-id "att-1"
                         :request {:snapshot {:messages []}}})
         [base tool] (state/create-call
                      base
                      {:kind :tool
                       :role :task
                       :lane :execution
                       :tenure-id "tenure-1"
                       :agent-id :worker-1
                       :parent-call-id (:call-id parent)
                       :request {}})
         tool (assoc (state/call base (:call-id tool)) :member "read_file")
         base (-> base
                  (state/put-call tool)
                  (state/terminal-call tool :succeeded {:ok? true} nil 3))
         actor-calls (if with-actor-call?
                       {:calls {"att-1" {:kind :swarm
                                         :attempt-id "att-1"
                                         :tenure-id "tenure-1"
                                         :slot-id :slot-a
                                         :agent-id :worker-1}}}
                       {})
         base (assoc base :actors
                     (-> (actors/new-state {:models {:main "m" :task "t"}
                                            :slot-ids [:slot-a]})
                         (assoc-in [:tenures "tenure-1" :slots :slot-a]
                                   {:slot-id :slot-a
                                    :agent-id slot-agent
                                    :status :running
                                    :context []})
                         (merge actor-calls)))]
     [base tool])))

(defn- deliver-tool-result
  [base ctx]
  (let [guard (guards/project-tool-result-guard)
        work ((:enabled? guard) base ctx)]
    {:guard guard
     :result (:state ((:prepare guard) base ctx work :attempt-1))}))

(deftest actor-tool-result-goes-to-the-agent-context
  (let [[base tool] (actor-tool-fixture true)
        ctx {:now 5}
        {:keys [guard result]} (deliver-tool-result base ctx)
        context (get-in result [:actors :tenures "tenure-1"
                                :slots :slot-a :context])
        tool-message (last context)]
    (is (empty? (:conversation result))
        "an actor tool result must never enter the shared conversation")
    (is (= 1 (count context)))
    (is (= :tool (:role tool-message)))
    (is (= (:call-id tool) (:tool-call-id tool-message))
        "the result stays attached to its own tool call")
    (is (= "read_file" (:name tool-message)))
    (is (false? (:error? tool-message)))
    (is (= :delivered
           (get-in result [:calls (:call-id tool) :result-delivery :status])))
    (is (some #(= :actor/tool-result (:type %)) (:outbox result))
        "the delivery effect is surfaced as audit evidence")
    (testing "replay is idempotent"
      (is (nil? ((:enabled? guard) result ctx)))
      (is (= 1 (count (get-in result [:actors :tenures "tenure-1"
                                      :slots :slot-a :context])))))))

(deftest actor-tool-result-is-recoverable-without-the-parent-attempt
  ;; The parent attempt record may be gone (pruned or a steered turn) while
  ;; the agent is still live.  The event names the tenure and agent, so the
  ;; result must still reach that agent instead of being dropped.
  (let [[base tool] (actor-tool-fixture false :worker-1)
        ctx {:now 5}
        {:keys [result]} (deliver-tool-result base ctx)
        context (get-in result [:actors :tenures "tenure-1"
                                :slots :slot-a :context])]
    (is (empty? (:conversation result))
        "the identity fallback still never writes the central conversation")
    (is (= 1 (count context)))
    (is (= (:call-id tool) (:tool-call-id (last context))))
    (is (= :delivered
           (get-in result [:calls (:call-id tool) :result-delivery :status])))))

(deftest stale-actor-tool-result-is-evidence-not-a-silent-drop
  (let [[base tool] (actor-tool-fixture true :worker-2)
        ctx {:now 5}
        {:keys [result]} (deliver-tool-result base ctx)
        context (get-in result [:actors :tenures "tenure-1"
                                :slots :slot-a :context])]
    (is (empty? context)
        "a reborn agent never receives the dead identity's result")
    (is (empty? (:conversation result)))
    (is (= :stale-agent
           (get-in result [:calls (:call-id tool) :result-delivery :status])))
    (is (some #(= :actor/stale-tool-result (:type %)) (:outbox result))
        "the stale drop is surfaced, not silent")))

(deftest undeliverable-actor-tool-result-fails-explicitly
  ;; No live slot holds the calling agent: the result cannot be delivered.
  ;; The failure is terminal and reported once; the guard must not retry.
  (let [[base tool] (actor-tool-fixture false :worker-2)
        ctx {:now 5}
        {:keys [guard result]} (deliver-tool-result base ctx)
        delivery (get-in result [:calls (:call-id tool) :result-delivery])]
    (is (empty? (:conversation result))
        "a routing failure must not fall back to the central conversation")
    (is (= :undeliverable (:status delivery)))
    (is (= :UNKNOWN_ACTOR_SLOT (:code delivery)))
    (is (true? (get-in result [:calls (:call-id tool) :result-projected?]))
        "the call is marked so the guard cannot spin")
    (is (some #(= :TOOL_RESULT_UNDELIVERABLE (:code %))
              (:diagnostics result)))
    (is (some #(and (= :owner/blocked (:type %))
                    (= :tool-result (:source %)))
              (:outbox result))
        "the failure is reported to the operator")
    (is (nil? ((:enabled? guard) result ctx))
        "a permanent failure is not retried forever")))
