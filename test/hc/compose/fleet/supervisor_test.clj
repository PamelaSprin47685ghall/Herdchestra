(ns hc.compose.fleet.supervisor-test
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.fleet.supervisor :as supervisor]))

(defn- fake-service
  [accepted effects]
  {:accept-actor-event! (fn [swarm-id attempt-id event]
                          (swap! accepted conj [swarm-id attempt-id event])
                          {:ok? true :state {:swarm-id swarm-id}})
   :wake-actors! (fn [swarm-id]
                   {:ok? true
                    :swarm-id swarm-id
                    :effects [{:type :model/start
                               :swarm-id swarm-id
                               :slot-id :slot-a
                               :attempt-id (str swarm-id "-next")}]})
   :continue-actor-after-tool!
   (fn [swarm-id attempt-id event result]
     {:ok? true
      :state {:swarm-id swarm-id}
      :effects (or effects
                   [{:type :model/start
                     :swarm-id swarm-id
                     :slot-id :slot-a
                     :attempt-id (str swarm-id "-tool-next")}])
      :tool-result result})
   :runtime (fn [swarm-id]
              {:swarm-id swarm-id
               :workspace (str "/worktrees/" swarm-id)
               :workspace-runtime {:root (str "/worktrees/" swarm-id)}
               :actor {:state {:actors {:calls {"a-1" {:attempt-id "a-1"
                                                        :tenure-id "fleet-swarm-1"
                                                        :slot-id :slot-a
                                                        :agent-id :agent-2}}
                                        :tenures {"fleet-swarm-1"
                                                  {:slots {:slot-a {:slot-id :slot-a
                                                                    :attempt-id "a-1"
                                                                    :agent-id :agent-2
                                                                    :status :running}}}}}}}})
   :board-snapshot (fn [_] {:board-version 7})
   :effects effects})

(deftest launches-actor-effects-and-indexes-attempts
  (let [started (atom [])
        observed (atom [])
        accepted (atom [])
        service (fake-service accepted nil)
        sup (supervisor/make
             {:service-ref (atom service)
              :start-effect! (fn [effect] (swap! started conj effect))
              :on-effect! (fn [effect] (swap! observed conj effect))})
        result ((:launch sup)
                {:swarm-id "swarm-1"
                 :actor {:effects [{:type :actor/rebirth
                                    :slot-id :slot-a
                                    :agent-id :agent-2}
                                   {:type :model/start
                                    :slot-id :slot-a
                                    :attempt-id "a-1"}
                                   {:type :model/start
                                    :slot-id :slot-b
                                    :attempt-id "b-1"}]}})]
    (is (:ok? result))
    (is (= 2 (:started result)))
    (is (= ["a-1" "b-1"] (mapv :attempt-id @started)))
    (is (= 3 (count @observed)))
    (is (= "swarm-1" (get-in @(:attempts sup) ["a-1" :swarm-id])))
    (is (= :slot-b (get-in @(:attempts sup) ["b-1" :slot-id])))))

(deftest forwards-model-cancel-effects-to-the-gateway
  (let [started (atom [])
        cancelled (atom [])
        sup (supervisor/make
             {:service-ref (atom (fake-service (atom []) nil))
              :start-effect! (fn [effect] (swap! started conj effect))
              :cancel-effect! (fn [effect]
                                (swap! cancelled conj effect)
                                {:ok? true :cancelled? true})})
        result ((:launch sup)
                {:swarm-id "swarm-1"
                 :actor {:effects [{:type :model/cancel
                                    :slot-id :slot-a
                                    :attempt-id "old-1"
                                    :reason :actor-dead}
                                   {:type :model/start
                                    :slot-id :slot-b
                                    :attempt-id "new-1"}]}})]
    (is (:ok? result))
    (is (= ["old-1"] (mapv :attempt-id @cancelled)))
    (is (= "swarm-1" (:swarm-id (first @cancelled))))
    (is (= ["new-1"] (mapv :attempt-id @started)))
    (is (= 1 (:started result)))
    (is (= 1 (:cancelled result)))
    (is (= [] (:cancel-errors result)))))

(deftest a-refused-cancel-is-evidence-not-a-launch-failure
  (let [started (atom [])
        sup (supervisor/make
             {:service-ref (atom (fake-service (atom []) nil))
              :start-effect! (fn [effect] (swap! started conj effect))
              :cancel-effect! (fn [_]
                                {:ok? false
                                 :error {:code :CANCEL_REFUSED}})})]
    (let [result ((:launch sup)
                  {:swarm-id "swarm-1"
                   :actor {:effects [{:type :model/cancel
                                      :attempt-id "old-1"}
                                     {:type :model/start
                                      :attempt-id "new-1"}]}})]
      (is (:ok? result))
      (is (= 1 (:started result)))
      (is (= 1 (:cancelled result)))
      (is (= [{:attempt-id "old-1"
               :result {:ok? false
                        :error {:code :CANCEL_REFUSED}}}]
             (:cancel-errors result)))
      (is (= ["new-1"] (mapv :attempt-id @started))))))

(deftest a-false-model-start-result-is-a-launch-failure
  (let [sup (supervisor/make
             {:service-ref (atom (fake-service (atom []) nil))
              :start-effect! (fn [_]
                               {:ok? false
                                :error {:code :MODEL_START_FAILED
                                        :message "gateway rejected the request"}})})
        result ((:launch sup)
                {:swarm-id "swarm-1"
                 :actor {:effects [{:type :model/start
                                    :slot-id :slot-a
                                    :attempt-id "a-1"}]}})]
    (is (false? (:ok? result)))
    (is (= :SWARM_MODEL_START_FAILED (get-in result [:error :code])))
    (is (= "a-1" (get-in result [:error :details :effect :attempt-id])))))

(deftest empty-actor-state-is-a-launch-failure
  (let [sup (supervisor/make
             {:service-ref (atom (fake-service (atom []) nil))
              :start-effect! (fn [_] nil)})
        result ((:launch sup)
                {:swarm-id "swarm-1"
                 :actor {:ok? true :state nil :effects []}})]
    (is (false? (:ok? result)))
    (is (= :SWARM_ACTOR_MISSING (get-in result [:error :code])))))

(deftest completed-event-updates-actor-and-starts-next-turn
  (let [started (atom [])
        accepted (atom [])
        sup (supervisor/make
             {:service-ref (atom (fake-service accepted nil))
              :start-effect! (fn [effect] (swap! started conj effect))})]
    ((:launch sup) {:swarm-id "swarm-1"
                    :actor {:effects [{:type :model/start
                                       :slot-id :slot-a
                                       :attempt-id "a-1"}]}})
    (let [result (supervisor/handle-event!
                  sup "swarm-1"
                  {:event :completed :attempt-id "a-1" :text "done"})]
      (is (:ok? result))
      (is (= 1 (count @accepted)))
      (is (= :completed (get-in @accepted [0 2 :event])))
      (is (= ["a-1" "swarm-1-next"] (mapv :attempt-id @started))
          "the completed slot wakes and starts the next real turn"))))

(deftest tool-call-uses-the-injected-worktree-executor
  (let [called (atom nil)
        sup (supervisor/make
             {:service-ref (atom (fake-service (atom []) nil))
              :start-effect! (fn [_] nil)
              :execute-tool! (fn [request] (reset! called request) {:ok? true :value 42})})
        result (supervisor/handle-event!
                sup "swarm-1"
                {:event :tool-call :attempt-id "a-1" :name "cat"})]
    (is (:ok? result))
    (is (= {:ok? true :value 42} (:tool-result result)))
    (is (:ok? (:continued result)))
    (is (= 1 (get-in result [:started :started])))
    (is (= "swarm-1" (:swarm-id @called)))
    (is (= "/worktrees/swarm-1" (get-in @called [:runtime :workspace])))
    (is (= "cat" (get-in @called [:event :name])))
    (is (= 7 (get-in @called [:board-snapshot :board-version])))
    (is (= "fleet-swarm-1" (get-in @called [:attempt :tenure-id])))))

(deftest tool-executor-failure-is-fed-back-to-the-actor
  (let [sup (supervisor/make
             {:service-ref (atom (fake-service (atom []) nil))
              :start-effect! (fn [_] nil)
              :execute-tool! (fn [_] (throw (ex-info "tool died" {})))})]
    ((:launch sup) {:swarm-id "swarm-1"
                    :actor {:effects [{:type :model/start
                                       :slot-id :slot-a
                                       :attempt-id "a-1"}]}})
    (let [result (supervisor/handle-event!
                  sup "swarm-1"
                  {:event :tool-call :attempt-id "a-1" :name "cat"})]
      (is (:ok? result))
      (is (= :failed (get-in result [:tool-result :status])))
      (is (= :TOOL_EXECUTION_FAILED
             (get-in result [:tool-result :error :code])))
      (is (:ok? (:continued result))))))

(deftest missing-tool-executor-is-explicit
  (let [sup (supervisor/make
             {:service-ref (atom (fake-service (atom []) nil))
              :start-effect! (fn [_] nil)})
        result (supervisor/handle-event!
                sup "swarm-1"
                {:event :tool-call :attempt-id "a-1"})]
    (is (false? (:ok? result)))
    (is (= :TOOL_EXECUTOR_MISSING (get-in result [:error :code])))))

(deftest stale-tool-call-is-rejected-before-execution
  (let [called (atom nil)
        sup (supervisor/make
             {:service-ref (atom (fake-service (atom []) nil))
              :start-effect! (fn [_] nil)
              :execute-tool! (fn [request]
                               (reset! called request)
                               {:ok? true})})
        result (supervisor/handle-event!
                sup "swarm-1"
                {:event :tool-call :attempt-id "stale-1" :name "cat"})]
    (is (false? (:ok? result)))
    (is (= :UNKNOWN_ATTEMPT (get-in result [:error :code])))
    (is (nil? @called)
        "a tool call without a live actor attempt must not run")))

(deftest conflict-resolution-abandons-only-without-a-holder-decision
  (let [sup (supervisor/make
             {:service-ref (atom (fake-service (atom []) nil))
              :start-effect! (fn [_] nil)})
        request {:swarm-id "swarm-1" :workspace "/worktrees/swarm-1"}
        abandoned (supervisor/resolve-conflict! sup request)
        resolved (supervisor/resolve-conflict!
                  (assoc sup :resolve-conflict! (fn [_] :resolved))
                  request)
        failed (supervisor/resolve-conflict!
                (assoc sup :resolve-conflict! (fn [_] (throw (ex-info "no" {}))))
                request)]
    (is (= :abandoned (:status abandoned)))
    (is (= :CONFLICT_RESOLVER_UNAVAILABLE (get-in abandoned [:reason :code])))
    (is (= :resolved resolved))
    (is (= :abandoned (:status failed)))
    (is (= :CONFLICT_RESOLVER_FAILED (get-in failed [:reason :code])))))

(deftest stop-cancels-indexed-attempts-and-refuses-late-events
  (let [cancelled (atom [])
        sup (supervisor/make
             {:service-ref (atom (fake-service (atom []) nil))
              :start-effect! (fn [_] nil)})]
    ((:launch sup) {:swarm-id "swarm-1"
                    :actor {:effects [{:type :model/start
                                       :slot-id :slot-a
                                       :attempt-id "a-1"}
                                      {:type :model/start
                                       :slot-id :slot-b
                                       :attempt-id "b-1"}]}})
    (let [stopped (supervisor/stop!
                   sup {:cancel-attempt! (fn [attempt-id]
                                           (swap! cancelled conj attempt-id))})
          late (supervisor/handle-event!
                sup "swarm-1"
                {:event :chunk :attempt-id "a-1" :text "late"})]
      (is (:stopped? stopped))
      (is (= 2 (:attempts stopped)))
      (is (= 2 (:cancelled stopped)))
      (is (= #{"a-1" "b-1"} (set @cancelled)))
      (is (= :SUPERVISOR_STOPPED (get-in late [:error :code])))
      (is (= {} @(:attempts sup))))))

(deftest attempt-swarm-mismatch-is-rejected
  (let [sup (supervisor/make
             {:service-ref (atom (fake-service (atom []) nil))
              :start-effect! (fn [_] nil)})]
    ((:launch sup) {:swarm-id "swarm-1"
                    :actor {:effects [{:type :model/start
                                       :slot-id :slot-a
                                       :attempt-id "a-1"}]}})
    (let [result (supervisor/handle-event!
                  sup "swarm-2"
                  {:event :chunk :attempt-id "a-1" :text "x"})]
      (is (false? (:ok? result)))
      (is (= :ATTEMPT_SWARM_MISMATCH (get-in result [:error :code]))))))

(deftest completed-with-published-tool-calls-is-deferred
  (let [accepted (atom [])
        started (atom [])
        sup (supervisor/make
             {:service-ref (atom (fake-service accepted nil))
              :start-effect! (fn [effect]
                               (swap! started conj effect))})]
    (let [result (supervisor/handle-event!
                  sup "swarm-1"
                  {:event :completed
                   :attempt-id "a-1"
                   :published-call-ids ["call-1"]})]
      (is (:ok? result))
      (is (= :tool-call (:deferred result))
          "the tool-call continuation owns the turn")
      (is (empty? @accepted)
          "the terminal did not clear the actor slot")
      (is (empty? @started)
          "the deferred terminal did not start another turn"))))

