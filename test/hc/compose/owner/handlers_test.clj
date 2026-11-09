(ns hc.compose.owner.handlers-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.cancel :as cancel]
            [hc.compose.owner.commands :as commands]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.state :as state]
            [hc.compose.reports.delivery :as delivery]))

(defn- context
  [registry now]
  {:now now :registry registry})

(defn- initial
  ([] (initial {}))
  ([opts]
   (let [registry (or (:registry opts) (commands/local-registry))]
     {:registry registry
      :state (state/initial-state (merge {:session-id "s1"
                                          :now 1000
                                          :registry registry}
                                         (dissoc opts :registry)))})))

(defn- apply!
  [fixture message]
  (handlers/apply-message (:state fixture) message
                          (context (:registry fixture) 2000)))

(defn- command
  [command-id epoch payload]
  {:type :command
   :command-id command-id
   :command-epoch epoch
   :payload payload})

(deftest command-replay-and-conflict
  (let [fixture (initial)
        first-result (apply! fixture (command "c1" 1 {:kind :send-message
                                                      :text "hello"}))
        state (:state first-result)]
    (testing "the first command is accepted"
      (is (= :accepted (:status (:receipt first-result))))
      (is (= [{:role :user :text "hello" :command-id "c1" :at 2000}]
             (mapv #(select-keys % [:role :text :command-id :at])
                   (:conversation state)))))
    (testing "the same command and payload replay the receipt"
      (let [replay (handlers/apply-message state
                                           (command "c1" 1 {:kind :send-message
                                                            :text "hello"})
                                           (context (:registry fixture) 3000))]
        (is (:replayed? (:receipt replay)))
        (is (= state (:state replay)))))
    (testing "a different payload under the same id is refused"
      (let [conflict (handlers/apply-message state
                                             (command "c1" 1 {:kind :send-message
                                                              :text "different"})
                                             (context (:registry fixture) 3000))]
        (is (= :COMMAND_ID_PAYLOAD_CONFLICT
               (get-in conflict [:receipt :error :code])))
        (is (= state (:state conflict)))))
    (testing "a retired epoch is refused"
      (let [issued (handlers/apply-message state
                                           (command "c2" 1 {:kind :session/issue-epoch})
                                           (context (:registry fixture) 3000))
            retired (handlers/apply-message (:state issued)
                                            (command "c3" 1 {:kind :send-message
                                                             :text "old"})
                                            (context (:registry fixture) 4000))]
        (is (= 2 (get-in issued [:state :commands :command-epoch])))
        (is (= :COMMAND_EPOCH_RETIRED
               (get-in retired [:receipt :error :code])))))))

(deftest user-message-creates-a-pending-turn
  (let [fixture (initial)
        result (apply! fixture (command "c1" 1 {:kind :send-message
                                                :text "do it"}))]
    (is (= [{:role :plan :agent-id :plan :tenure-id "tenure-1"
             :text "do it" :command-id "c1"}]
           (mapv #(select-keys % [:role :agent-id :tenure-id
                                  :text :command-id])
                 (get-in result [:state :pending-turns]))))
    (is (= "tenure-1" (:tenure-id (peek (get-in result [:state
                                                       :pending-turns]))))
        "the Plan tenure is pinned when the message is accepted")
    (is (= [:user-input]
           (get-in result [:state :outbox 0 :reasons])))))

(deftest temporary-read-rides-the-pending-turn
  (testing "the one-shot temporary read grant reaches the queued turn"
    (let [fixture (initial)
          result (apply! fixture (command "c1" 1 {:kind :send-message
                                                  :text "read once"
                                                  :temporary-read? true}))]
      (is (= :accepted (get-in result [:receipt :status])))
      (is (= [true]
             (mapv :temporary-read?
                   (get-in result [:state :pending-turns])))
          "the queued turn carries the grant so open-user-turn-guard can
           put it on the model request envelope")))
  (testing "a send without the grant leaves the turn closed"
    (let [fixture (initial)
          result (apply! fixture (command "c1" 1 {:kind :send-message
                                                  :text "plain"}))]
      (is (= [nil]
             (mapv :temporary-read?
                   (get-in result [:state :pending-turns])))))))

(deftest tool-required-send-refuses-a-tools-less-model-and-keeps-the-draft
  (let [fixture (initial {:initial-session
                          {:model-binding {:connection-id "conn"
                                           :model-entry-id "model"
                                           :capabilities {}}
                           :draft "keep me"}})
        result (apply! fixture (command "c1" 1 {:kind :send-message
                                                :text "do it"
                                                :draft "keep me"}))]
    (is (false? (get-in result [:receipt :ok?])))
    (is (= :MODEL_TOOLS_UNAVAILABLE
           (get-in result [:receipt :error :code])))
    (is (= :plan (get-in result [:receipt :error :details :role])))
    (is (= :clj-plan
           (get-in result [:receipt :error :details :program-role])))
    (is (= :binding
           (get-in result [:receipt :error :details
                           :capabilities-source]))
        "the refusal names the binding declaration as the tools source")
    (is (true? (get-in result [:receipt :error :details
                               :draft-preserved?])))
    (is (= [] (get-in result [:state :conversation]))
        "the refused message is not appended")
    (is (= [] (get-in result [:state :pending-turns]))
        "no responsibility is queued for a role that cannot run")
    (is (= "keep me" (get-in result [:state :session :draft]))
        "the draft stays with the user")))

(deftest tool-capable-send-still-creates-a-pending-turn
  (let [fixture (initial {:initial-session
                          {:model-binding
                           {:connection-id "conn"
                            :model-entry-id "model"
                            :capabilities {:tools true}}}})
        result (apply! fixture (command "c1" 1 {:kind :send-message
                                                :text "do it"}))]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= [:plan] (mapv :role (get-in result [:state :pending-turns]))))))

(deftest chat-send-carries-the-chat-marker-to-the-pending-turn
  (let [fixture (initial)
        result (apply! fixture (command "c1" 1 {:kind :send-message
                                                :text "what is S1"
                                                :chat? true}))]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= [true]
           (mapv :chat? (get-in result [:state :pending-turns])))
        "the chat marker survives onto the queued turn")
    (is (true? (get-in result [:state :conversation 0 :chat?]))
        "the user entry records the chat answer mode")))

(deftest chat-send-does-not-require-model-tool-support
  (let [fixture (initial {:initial-session
                          {:model-binding
                           {:connection-id "conn"
                            :model-entry-id "model"
                            :capabilities {}}}})
        result (apply! fixture (command "c1" 1 {:kind :send-message
                                                :text "what is S1"
                                                :chat? true}))]
    (is (= :accepted (get-in result [:receipt :status]))
        "a plain-text answer turn is legal on a tools-less model")
    (is (= [:plan] (mapv :role (get-in result [:state :pending-turns]))))
    (let [program-send (apply! fixture
                               (command "c2" 1 {:kind :send-message
                                                :text "draft it"}))]
      (is (= :MODEL_TOOLS_UNAVAILABLE
             (get-in program-send [:receipt :error :code]))
          "the same tools-less model still refuses a program turn"))))

(deftest tool-required-send-reads-the-live-capability-fact
  (let [fixture (initial {:initial-session
                          {:model-binding
                           {:connection-id "conn"
                            :model-entry-id "model"
                            :capabilities {}}}})
        message (command "c1" 1 {:kind :send-message
                                 :text "do it"})
        ctx (fn [supported?]
              (assoc (context (:registry fixture) 2000)
                     :model-tools-supported (fn [_] supported?)))
        accepted (handlers/apply-message (:state fixture) message
                                         (ctx true))
        refused (handlers/apply-message (:state fixture) message
                                        (ctx false))]
    (is (= :accepted (get-in accepted [:receipt :status]))
        "a saved capability declaration reaches the send gate without a rebind")
    (is (= [:plan]
           (mapv :role (get-in accepted [:state :pending-turns]))))
    (is (= :MODEL_TOOLS_UNAVAILABLE
           (get-in refused [:receipt :error :code]))
        "an explicit live no-tools fact still refuses the send")))

(deftest tool-required-send-falls-back-to-a-declared-binding
  (let [fixture (initial {:initial-session
                          {:model-binding
                           {:connection-id "conn"
                            :model-entry-id "model"
                            ;; Older snapshots and the client catalog may
                            ;; carry a declared capability in a form other
                            ;; than literal `true`; the send gate must read
                            ;; the same supports-tools? fact as the gateway.
                            :capabilities {:tools :known}}}})
        result (apply! fixture (command "c1" 1 {:kind :send-message
                                                :text "do it"}))]
    (is (= :accepted (get-in result [:receipt :status]))
        "a declared binding capability is enough when no live fact exists")))+
(deftest tool-required-send-trusts-the-merged-live-fact
  (let [fixture (initial {:initial-session
                          {:model-binding
                           {:connection-id "conn"
                            :model-entry-id "model"
                            :capabilities {:tools :known}}}})
        message (command "c1" 1 {:kind :send-message
                                 :text "do it"})
        ctx (assoc (context (:registry fixture) 2000)
                   :model-tools-supported (fn [_] false))
        result (handlers/apply-message (:state fixture) message ctx)]
    (is (= :MODEL_TOOLS_UNAVAILABLE
           (get-in result [:receipt :error :code]))
        "the resolver's merged no-tools answer is authoritative")))

(deftest central-plan-model-call-pins-the-plan-tenure
  (let [fixture (initial)
        sent (apply! fixture (command "c1" 1 {:kind :send-message
                                              :text "do it"}))
        guard (guards/open-user-turn-guard)
        work ((:enabled? guard) (:state sent) {:now 2000})
        prepared (:state ((:prepare guard) (:state sent) {:now 2000}
                          work :attempt-1))
        call (first (filter #(= :model (:kind %))
                            (vals (:calls prepared))))]
    (is (= "tenure-1" (:tenure-id work)))
    (is (= "tenure-1" (:tenure-id call))
        "the central Plan model call names its tenure from the start")
    (is (= "tenure-1" (get-in call [:request :snapshot :tenure-id])))
    (is (= :plan (get-in call [:request :snapshot :lane])))))

(deftest ask-and-answer-resume-the-tenure
  (let [fixture (initial)
        ask (apply! fixture (command "c1" 1 {:kind :review/submit
                                             :lane :plan
                                             :tenure-id "tenure-1"
                                             :findings []}))]
    (is (= :ask (get-in ask [:receipt :outcome])))
    (is (true? (get-in ask [:state :relay :lanes :plan :awaiting-user?])))
    (let [answer (handlers/apply-message
                  (:state ask)
                  (command "c2" 1 {:kind :answer-ask :text "do X"})
                  (context (:registry fixture) 3000))]
      (is (= :accepted (:status (:receipt answer))))
      (is (false? (get-in answer [:state :relay :lanes :plan :awaiting-user?])))
      (is (= [{:role :user :text "do X" :command-id "c2"
               :unparked-tenure-id "tenure-1" :at 3000}]
             (get-in answer [:state :conversation])))
      (is (= [:plan] (mapv :role (get-in answer [:state :pending-turns])))))))

(deftest pending-plan-starts-execution
  (let [fixture (initial {:plan-milestone :pending
                          :draft {:path "plans/demo.md" :content "# plan"}})
        result (apply! fixture (command "c1" 1 {:kind :review/submit
                                                :lane :plan
                                                :tenure-id "tenure-1"
                                                :findings []}))]
    (is (= :execution-started (get-in result [:receipt :outcome])))
    (is (= ["tenure-2"]
           (keys (get-in result [:state :relay :lanes :execution]))))))

(deftest execution-review-creates-board-roots
  (let [fixture (initial {:plan-milestone :pending
                          :draft {:path "plans/demo.md" :content "# plan"}})
        started (apply! fixture (command "c1" 1 {:kind :review/submit
                                                 :lane :plan
                                                 :tenure-id "tenure-1"
                                                 :findings []}))
        reviewed (handlers/apply-message
                  (:state started)
                  (command "c2" 1 {:kind :review/submit
                                   :lane :execution
                                   :tenure-id "tenure-2"
                                   :findings [{:key "k1" :value "make it work"}]})
                  (context (:registry fixture) 3000))
        roots (filter #(= "k1" (:ledger-key %))
                      (vals (get-in reviewed [:state :board :tasks])))]
    (is (= :swarm-started (get-in reviewed [:receipt :outcome])))
    (is (= 1 (count roots)))
    (is (= :active (get-in reviewed
                           [:state :relay :lanes :execution "tenure-2"
                            :swarm-phase])))))

(deftest git-ff-ready-is-recorded-in-mission-state
  (let [fixture (initial {:plan-milestone :pending
                          :draft {:path "plans/demo.md" :content "# plan"}})
        started (apply! fixture (command "c1" 1 {:kind :review/submit
                                                 :lane :plan
                                                 :tenure-id "tenure-1"
                                                 :findings []}))
        recorded (handlers/apply-message
                  (:state started)
                  {:type :git/ff-ready
                   :ready? true
                   :source :join
                   :ff-commit "abc123"
                   :at 4242}
                  (context (:registry fixture) 3000))
        state (:state recorded)]
    (testing "the fact lives in mission state with its evidence"
      (is (true? (get-in state [:mission :ff-ready?])))
      (is (= {:ready? true
              :source :join
              :ff-commit "abc123"
              :at 4242}
             (get-in state [:mission :ff-ready]))))
    (testing "board facts read the mission fact"
      (is (true? (:ff-ready? (state/board-facts state)))))
    (testing "a failed join clears the mission readiness"
      (let [cleared (handlers/apply-message
                     state
                     {:type :git/ff-ready
                      :ready? false
                      :source :join
                      :at 4243}
                     (context (:registry fixture) 3000))]
        (is (false? (get-in cleared [:state :mission :ff-ready?])))
        (is (false? (:ff-ready? (state/board-facts (:state cleared)))))))
    (testing "a new mission starts unready"
      (is (false? (:ff-ready? (state/board-facts
                               (state/initial-state
                                {:session-id "fresh-mission"}))))))))

(deftest board-finish-needs-a-write-gate-proof
  (let [fixture (initial {:plan-milestone :pending
                          :draft {:path "plans/demo.md" :content "# plan"}})
        fixture (update fixture :state
                        (fn [_]
                          (:state (apply! fixture
                                          (command "p" 1
                                                   {:kind :review/submit
                                                    :lane :plan
                                                    :tenure-id "tenure-1"
                                                    :findings []})))))
        fixture (update fixture :state
                        (fn [_]
                          (:state (apply! fixture
                                          (command "e" 1
                                                   {:kind :review/submit
                                                    :lane :execution
                                                    :tenure-id "tenure-2"
                                                    :findings [{:key "k1"
                                                                :value "root"}]})))))
        registered (apply! fixture
                           {:type :board/command
                            :command {:op :register-actor
                                      :tenure-id "tenure-2"
                                      :slot-id "w1"}})
        actor (:agent-id (:receipt registered))
        fixture (assoc fixture :state (:state registered))
        claimed (apply! fixture
                        {:type :board/command
                         :command {:op :claim
                                   :tenure-id "tenure-2"
                                   :actor-id actor
                                   :task-id :task-1
                                   :atomicity-reason "atomic"}})
        fixture (assoc fixture :state (:state claimed))
        claim-id (get-in claimed [:state :board :tasks :task-1 :claim-id])
        began (apply! fixture
                      {:type :board/command
                       :command {:op :begin-action
                                 :tenure-id "tenure-2"
                                 :actor-id actor
                                 :task-id :task-1
                                 :claim-id claim-id
                                 :kind :board
                                 :request-id "r1"}})
        fixture (assoc fixture :state (:state began))
        finish {:op :finish
                :tenure-id "tenure-2"
                :actor-id actor
                :task-id :task-1
                :claim-id claim-id
                :result-summary "done"
                :read-set [{:path "x" :stamp {:mtime-sec 1
                                              :mtime-nsec 0
                                              :size 1}}]}
        refused (apply! fixture {:type :board/command :command finish})
        forged (mapv
                (fn [proof]
                  (apply! fixture
                          {:type :board/command
                           :command finish
                           :env {:write-gate-proof proof
                                 :under-write-gate? true
                                 :verify-read-set (fn [_] {:ok? true})}}))
                [true "forged" :forged {:token "forged"} (Object.)])]
    (is (= :WRITE_GATE_REQUIRED
           (get-in refused [:receipt :error :code])))
    (doseq [result forged]
      (is (= :WRITE_GATE_REQUIRED
             (get-in result [:receipt :error :code])))
      (is (zero? (count (get-in result [:state :relay :barriers])))))
    (is (= :ready
           (get-in (last forged) [:state :board :tasks :task-1 :state])))))

(deftest model-tool-call-creates-a-ready-tool-call
  (let [fixture (initial)
        state (-> fixture
                  :state
                  (assoc-in [:calls "model-1"]
                            {:call-id "model-1"
                             :kind :model
                             :role :plan
                             :lane :plan
                             :agent-id :plan
                             :state :running
                             :attempt-id "a1"}))
        result (handlers/apply-message
                state
                {:type :model/event
                 :event {:event :tool-call
                         :attempt-id "a1"
                         :model-call-id "model-1"
                         :call-id "tool-1"
                         :payload {:executor :workspace
                                   :name "write"
                                   :program [:write]
                                   :scope {:kind :reset-scope :new-scope-goal "test" :new-scope-retention-budget 8}}}}
                (context (:registry fixture) 3000))
        tool (get-in result [:state :calls "model-1/tool-0"])]
    (is (= :ready (:state tool)))
    (is (= :workspace (:kind tool)))
    (is (= :plan (:agent-id tool)))
    (is (= "model-1" (:parent-call-id tool)))
    (is (= "tenure-1" (:tenure-id tool))
        "the tool call record carries the same pinned tenure as its request")
    (is (= "tenure-1" (get-in tool [:request :tenure-id]))
        "a central Plan call without an execution tenure still names the plan tenure")
    (is (= :plan (get-in tool [:request :lane]))
        "the program control context needs the Plan lane, not the role fallback")))

(deftest stale-pending-turn-is-refused-after-the-plan-relays
  (let [fixture (initial {:plan-milestone :S2})
        sent (apply! fixture (command "c1" 1 {:kind :send-message
                                              :text "for tenure one"}))
        relayed (apply! (assoc fixture :state (:state sent))
                        (command "relay" 1
                                 {:kind :review/submit
                                  :lane :plan
                                  :tenure-id "tenure-1"
                                  :findings []}))
        guard (guards/open-user-turn-guard)
        ctx {:now 3000}
        work ((:enabled? guard) (:state relayed) ctx)
        prepared (:state ((:prepare guard) (:state relayed) ctx work
                          :attempt-1))
        blocked (last (:outbox prepared))]
    (is (= :relayed (get-in relayed [:receipt :outcome])))
    (is (= "tenure-2" (get-in prepared [:relay :lanes :plan :tenure-id])))
    (is (= "tenure-1" (:tenure-id work))
        "the queued turn is still pinned to the tenure it was written for")
    (is (empty? (:pending-turns prepared))
        "the stale turn is consumed, never rebound to the successor")
    (is (not-any? #(= :model (:kind %)) (vals (:calls prepared)))
        "no model call is created for a retired tenure")
    (is (= :TURN_TENURE_STALE (:code (last (:diagnostics prepared)))))
    (is (= :owner/blocked (:type blocked)))
    (is (= :turn (:source blocked)))
    (is (= "tenure-1" (get-in blocked [:diagnostic :tenure-id])))
    (testing "a message accepted after the relay pins the successor tenure"
      (let [fresh (apply! (assoc fixture :state prepared)
                          (command "c2" 1 {:kind :send-message
                                           :text "for tenure two"}))
            work' ((:enabled? guard) (:state fresh) {:now 4000})
            prepared' (:state ((:prepare guard) (:state fresh) {:now 4000}
                               work' :attempt-2))
            call (first (filter #(= :model (:kind %))
                                (vals (:calls prepared'))))]
        (is (= "tenure-2" (:tenure-id (peek (get-in fresh [:state
                                                          :pending-turns])))))
        (is (= "tenure-2" (:tenure-id call)))))))

(deftest late-plan-tool-call-keeps-its-retired-tenure
  (let [fixture (initial {:plan-milestone :S2})
        relayed (apply! fixture (command "relay" 1
                                         {:kind :review/submit
                                          :lane :plan
                                          :tenure-id "tenure-1"
                                          :findings []}))
        state (assoc-in (:state relayed) [:calls "model-1"]
                        {:call-id "model-1"
                         :kind :model
                         :role :plan
                         :lane :plan
                         :agent-id :plan
                         :tenure-id "tenure-1"
                         :state :running
                         :attempt-id "a1"
                         ;; The old tenure's frozen policy must not leak to a
                         ;; late tool call after the relay.
                         :request {:snapshot {:write-unlocked? true
                                              :s1? true
                                              :draft-path "plans/late.md"}}})
        result (handlers/apply-message
                state
                {:type :model/event
                 :event {:event :tool-call
                         :attempt-id "a1"
                         :model-call-id "model-1"
                         :output-index 0
                         :arguments {:tenure-id "tenure-2"
                                     :lane :execution
                                     :executor :shell
                                     :call-id "forged"
                                     :write-unlocked? true
                                     :s1? true
                                     :draft-path "plans/forged.md"
                                     :plan-draft {:tenure-id "tenure-2"
                                                  :path "plans/forged.md"}}
                         :payload {:executor :tool
                                   :name "clj-plan"
                                   :scope {:kind :reset-scope
                                           :new-scope-goal "late review"
                                           :new-scope-retention-budget 8}}}}
                (context (:registry fixture) 3000))
        tool (get-in result [:state :calls "model-1/tool-0"])
        reviewed (handlers/apply-message
                  (:state result)
                  (command "late-review" 1
                           {:kind :review/submit
                            :lane :plan
                            :tenure-id (get-in tool [:request :tenure-id])
                            :findings [{:key "goal" :value "late"}]})
                  (context (:registry fixture) 4000))]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= :tool (:kind tool))
        "the model's arguments cannot choose another executor")
    (is (= "model-1/tool-0" (get-in tool [:request :call-id]))
        "the model's arguments cannot forge the Owner call id")
    (is (= "tenure-1" (:tenure-id tool)))
    (is (= "tenure-1" (get-in tool [:request :tenure-id]))
        "the late tool call keeps the model call's pinned tenure")
    (is (= :plan (get-in tool [:request :lane])))
    (is (nil? (get-in tool [:request :write-unlocked?]))
        "the retired tenure's write unlock is not inherited")
    (is (nil? (get-in tool [:request :draft-path])))
    (is (nil? (get-in tool [:request :s1?])))
    (is (nil? (get-in tool [:request :plan-draft]))
        "a model argument cannot supply the Plan draft authorization")
    (is (= :TENURE_RETIRED (get-in reviewed [:receipt :error :code]))
        "the retired tenure is refused, not applied to the successor")
    (is (empty? (get-in reviewed [:state :relay :lanes :plan :ledger])))
    (is (false? (get-in reviewed [:state :mission :bindings :plan
                                  :reviewed-in-tenure?]))
        "the late review never unlocks the successor's write lock")))

(deftest plan-tool-call-review-applies-and-unlocks-the-write-lock
  (let [fixture (initial)
        state (assoc-in (:state fixture) [:calls "model-1"]
                        {:call-id "model-1"
                         :kind :model
                         :role :plan
                         :lane :plan
                         :agent-id :plan
                         :tenure-id "tenure-1"
                         :state :running
                         :attempt-id "a1"})
        result (handlers/apply-message
                state
                {:type :model/event
                 :event {:event :tool-call
                         :attempt-id "a1"
                         :model-call-id "model-1"
                         :output-index 0
                         :payload {:executor :tool
                                   :name "clj-plan"
                                   :scope {:kind :reset-scope
                                           :new-scope-goal "plan review"
                                           :new-scope-retention-budget 8}}}}
                (context (:registry fixture) 3000))
        tool (get-in result [:state :calls "model-1/tool-0"])
        reviewed (handlers/apply-message
                  (:state result)
                  (command "plan-review" 1
                           {:kind :review/submit
                            :lane (get-in tool [:request :lane])
                            :tenure-id (get-in tool [:request :tenure-id])
                            :findings [{:key "goal" :value "real"}]})
                  (context (:registry fixture) 4000))]
    (is (= "tenure-1" (get-in tool [:request :tenure-id])))
    (is (= :ledger-updated (get-in reviewed [:receipt :outcome])))
    (is (= {"goal" "real"}
           (get-in reviewed [:state :mission :relay :lanes :plan :ledger])))
    (is (true? (get-in reviewed [:state :mission :bindings :plan
                                 :reviewed-in-tenure?]))
        "the accepted Plan review unlocks the draft write")))

(deftest model-json-string-scope-kind-is-admitted
  (let [fixture (initial)
        state (-> fixture
                  :state
                  (assoc-in [:calls "model-1"]
                            {:call-id "model-1"
                             :kind :model
                             :role :plan
                             :lane :plan
                             :agent-id :plan
                             :state :running
                             :attempt-id "a1"}))
        result (handlers/apply-message
                state
                {:type :model/event
                 :event {:event :tool-call
                         :attempt-id "a1"
                         :model-call-id "model-1"
                         :call-id "tool-1"
                         :output-index 0
                         :arguments
                         {:scope {:kind "reset-scope"
                                  :new-scope-goal "test"
                                  :new-scope-retention-budget 8}}
                         :payload {:executor :tool :name "read"}}}
                (context (:registry fixture) 3000))
        call (get-in result [:state :calls "model-1/tool-0"])]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= :ready (:state call)))
    (is (= :reset-scope (get-in call [:scope-annotation :kind])))
    (is (= :reset-scope (get-in call [:request :scope :kind]))
        "the executor sees the same normalized annotation")))

(deftest actor-dispatch-failure-releases-slot
  (let [fixture (initial)
        slot {:slot-id :slot-a
              :agent-id :agent-x
              :generation 1
              :role :worker
              :status :running
              :context []
              :attempt-id "actor-attempt-1"
              :turn 0
              :last-event nil}
        actors {:models {:main "main-model" :task "task-model"}
                :slot-ids [:slot-a]
                :tenures {"tenure-x"
                          {:slots {:slot-a slot}
                           :slot-order [:slot-a]
                           :next-turn 1}}
                :plan-worker nil
                :calls {"actor-attempt-1"
                        {:kind :swarm
                         :attempt-id "actor-attempt-1"
                         :tenure-id "tenure-x"
                         :slot-id :slot-a
                         :agent-id :agent-x
                         :role :worker
                         :call-id "actor-attempt-1"
                         :output ""}}
                :next-call-seq 1}
        state (-> (:state fixture)
                  (assoc :actors actors)
                  (assoc-in [:calls "call-actor"]
                            {:call-id "call-actor"
                             :kind :model
                             :role :worker
                             :lane :execution
                             :tenure-id "tenure-x"
                             :agent-id :agent-x
                             :state :dispatching
                             :attempt-id :attempt-1
                             :actor-attempt-id "actor-attempt-1"
                             :request {}
                             :created-at 0}))
        result (handlers/apply-message
                state
                {:type :dispatch/failed
                 :call-id "call-actor"
                 :attempt-id :attempt-1
                 :error {:kind :dispatch-failed
                         :message "gateway down"}}
                {:now 2000})
        state' (:state result)
        slot' (get-in state' [:actors :tenures "tenure-x" :slots :slot-a])
        actor-call' (get-in state' [:actors :calls "actor-attempt-1"])]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= :idle (:status slot')))
    (is (nil? (:attempt-id slot')))
    (is (= :failed (:status actor-call')))
    (is (str/includes? (str/join "\n" (map :text (:context slot')))
                       "gateway down"))
    (is (= :failed (get-in state' [:calls "call-actor" :state])))
    (is (= :dispatch-failed
           (get-in state' [:calls "call-actor" :retry-error :kind])))))

(deftest child-report-acceptance-is-idempotent
  (let [fixture (initial)
        [state _parent] (state/create-call
                         (:state fixture)
                         {:call-id "call-9"
                          :kind :model
                          :role :plan
                          :lane :plan
                          :agent-id :plan
                          :request {:snapshot {}}
                          :created-at 1000})
        fixture (assoc fixture :state state)
        report {:report-id "rep-1"
                :parent-call-id "call-9"
                :payload {:kind :llm-report :text "full"}}
        first-result (apply! fixture {:type :child/report :report report})
        duplicate (handlers/apply-message
                   (:state first-result)
                   {:type :child/report :report report}
                   (context (:registry fixture) 3000))]
    (is (= :accepted (get-in first-result [:receipt :outcome])))
    (is (= :duplicate (get-in duplicate [:receipt :outcome])))
    (is (= "full"
           (get-in first-result
                   [:state :acceptance :accepted "call-9" :payload :text])))
    (is (= :report/ack (get-in first-result [:state :outbox 0 :type])))
    (is (= :report/ack (get-in duplicate [:state :outbox 0 :type])))
    (is (= :duplicate (get-in duplicate [:receipt :outcome])))))

(deftest unknown-message-records-a-diagnostic
  (let [fixture (initial)
        result (apply! fixture {:type :mystery})]
    (is (= :UNKNOWN_MESSAGE_TYPE (get-in result [:receipt :error :code])))
    (is (= :UNKNOWN_MESSAGE_TYPE
           (get-in result [:state :diagnostics 0 :code])))
    (is (= (get-in fixture [:state :conversation])
           (get-in result [:state :conversation])))))

(deftest recovery-marks-orphans-and-redelivers-reports
  (let [fixture (initial)
        base (:state fixture)
        [state call] (state/create-call
                      base
                      {:kind :model
                       :role :plan
                       :lane :plan
                       :agent-id :plan
                       :request {:snapshot {}}
                       :created-at 1000})
        attempt-id :attempt-9
        state (-> state
                  (assoc-in [:calls (:call-id call) :state] :dispatching)
                  (assoc-in [:calls (:call-id call) :attempt-id] attempt-id)
                  (assoc-in [:calls (:call-id call) :retry]
                            {:phase :running :attempts-used 1})
                  (state/add-attempt-node call attempt-id)
                  (assoc :reports
                         (:state
                          (delivery/register
                           (:reports state)
                           {:report-id "rep-1"
                            :parent-call-id "call-9"
                            :payload {:kind :llm-report
                                      :text "full report"}})))
                  (assoc-in [:report-claims "rep-1"]
                            {:state :delivered
                             :attempt-id :a
                             :attempts 2})
                  (assoc-in [:report-claims "orphan"]
                            {:state :failed :attempt-id :b}))
        recovered (state/recover-state state 9000)]
    (is (= :outcome-unknown
           (get-in recovered [:calls (:call-id call) :state])))
    (is (= :pending (get-in recovered [:report-claims "rep-1" :state])))
    (is (= 2 (get-in recovered [:report-claims "rep-1" :attempts])))
    (is (nil? (get-in recovered [:report-claims "orphan"])))
    (is (= :ORPHANED_ATTEMPT
           (-> recovered :diagnostics last :code)))
    (is (= :outcome-unknown
           (get-in recovered [:cancel :nodes (:call-id call) :state])))))


(deftest session-and-history-commands-use-the-injected-port
  (let [calls (atom [])
        port {:apply-command
              (fn [session payload]
                (swap! calls conj payload)
                {:ok? true
                 :session (assoc (or session {}) :last (:kind payload))
                 :result {:status :accepted :outcome (:kind payload)}
                 :effects []})
              :confirm-draft-saved
              (fn [session _] {:ok? true :session session})
              :fail-draft-save
              (fn [session _] {:ok? true :session session})}
        fixture (initial)
        ctx (assoc (context (:registry fixture) 2000) :session-port port)
        first-result (handlers/apply-message
                      (:state fixture)
                      (command "h1" 1 {:kind :history/select-version
                                       :version 2})
                      ctx)
        second-result (handlers/apply-message
                       (:state first-result)
                       (command "h2" 1 {:kind :session/edit-message
                                        :text "edited"})
                       ctx)]
    (is (= :accepted (get-in first-result [:receipt :status])))
    (is (= :history/select-version
           (get-in first-result [:state :session :last])))
    (is (= :session/edit-message
           (get-in second-result [:state :session :last])))
    (is (= [:history/select-version :session/edit-message]
           (mapv :kind @calls)))))

(deftest accepted-ui-send-freezes-the-outbound-run-in-the-session
  (let [calls (atom [])
        port {:apply-command
              (fn [session payload]
                (swap! calls conj payload)
                {:ok? true
                 :session (assoc (or session {}) :last (:kind payload))
                 :result {:status :accepted :outcome (:kind payload)}
                 :effects []})
              :confirm-draft-saved (fn [session _] {:ok? true :session session})
              :fail-draft-save (fn [session _] {:ok? true :session session})}
        fixture (initial)
        ctx (assoc (context (:registry fixture) 2000) :session-port port)
        result (handlers/apply-message
                (:state fixture)
                (command "s1" 1 {:kind :send-message :text "hello"})
                ctx)]
    (is (= :accepted (get-in result [:receipt :status])))
    (testing "the accepted send mirrors history, then freezes the outbound run"
      (is (= [:history/append-message :session/record-outbound]
             (mapv :kind @calls)))
      (is (= :session/record-outbound
             (get-in result [:state :session :last]))))
    (testing "the frozen run carries the client message id"
      (is (= (get-in result [:receipt :message-id])
             (:message-id (second @calls)))))))



(deftest session-select-model-uses-the-session-port
  (let [calls (atom [])
        port {:apply-command
              (fn [session payload]
                (swap! calls conj payload)
                {:ok? true
                 :session (assoc (or session {}) :model (:model payload))
                 :result {:status :accepted :outcome (:kind payload)}
                 :effects []})
              :confirm-draft-saved (fn [session _] {:ok? true :session session})
              :fail-draft-save (fn [session _] {:ok? true :session session})}
        fixture (initial)
        ctx (assoc (context (:registry fixture) 2000) :session-port port)
        result (handlers/apply-message
                (:state fixture)
                (command "m1" 1 {:kind :session/select-model
                                 :model "model-x"})
                ctx)]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= "model-x" (get-in result [:state :session :model])))
    (is (= :session/select-model (-> @calls first :kind)))))

(deftest plan-draft-write-uses-mission-ownership
  (let [fixture (initial)
        before (apply! fixture
                       (command "d1" 1
                                {:kind :plan/write-draft
                                 :path "plans/demo.md"
                                 :content "Draft body."
                                 :tenure-id "tenure-1"}))
        reviewed (apply! fixture
                         (command "r1" 1
                                  {:kind :review/submit
                                   :lane :plan
                                   :tenure-id "tenure-1"
                                   :findings [{:key "open" :value "work"}]}))
        after (handlers/apply-message
               (:state reviewed)
               (command "d2" 1
                        {:kind :plan/write-draft
                         :path "plans/demo.md"
                         :content "Draft body."
                         :tenure-id "tenure-1"})
               (context (:registry fixture) 3000))]
    (is (= :WRITE_LOCKED_BEFORE_FIRST_REVIEW
           (get-in before [:receipt :error :code])))
    (is (= :ledger-updated (get-in reviewed [:receipt :outcome])))
    (is (= :accepted (get-in after [:receipt :status])))
    (is (= "plans/demo.md"
           (get-in after [:state :mission :relay :draft :path])))
    (is (= "Draft body."
           (get-in after [:state :mission :relay :draft :content])))
    (is (= "Draft body." (get-in after [:state :relay :draft :content])))))

(deftest answer-ask-selects-among-multiple-awaiting-tenures
  (let [fixture (initial {:plan-milestone :pending
                          :draft {:path "plans/demo.md" :content "# plan"}})
        handoff-1 (apply! fixture
                          (command "h1" 1
                                   {:kind :review/submit
                                    :lane :plan
                                    :tenure-id "tenure-1"
                                    :findings []}))
        execution-1 (get-in handoff-1 [:receipt :execution-tenure-id])
        handoff-2 (handlers/apply-message
                   (:state handoff-1)
                   (command "h2" 1
                            {:kind :review/submit
                             :lane :plan
                             :tenure-id "tenure-1"
                             :findings []})
                   (context (:registry fixture) 2500))
        execution-2 (get-in handoff-2 [:receipt :execution-tenure-id])
        ask-1 (handlers/apply-message
               (:state handoff-2)
               (command "a1" 1
                        {:kind :review/submit
                         :lane :execution
                         :tenure-id execution-1
                         :findings []})
               (context (:registry fixture) 2600))
        ask-2 (handlers/apply-message
               (:state ask-1)
               (command "a2" 1
                        {:kind :review/submit
                         :lane :execution
                         :tenure-id execution-2
                         :findings []})
               (context (:registry fixture) 2700))
        state (:state ask-2)
        ambiguous (handlers/apply-message
                   state
                   (command "ans-1" 1 {:kind :answer-ask :text "answer"})
                   (context (:registry fixture) 2800))
        selected (handlers/apply-message
                  state
                  (command "ans-2" 1 {:kind :answer-ask
                                      :tenure-id execution-1
                                      :text "answer"})
                  (context (:registry fixture) 2900))]
    (is (= :ask (get-in ask-1 [:receipt :outcome])))
    (is (= :ask (get-in ask-2 [:receipt :outcome])))
    (is (= :AMBIGUOUS_ASK (get-in ambiguous [:receipt :error :code])))
    (is (= :accepted (get-in selected [:receipt :status])))
    (is (false? (get-in selected
                        [:state :relay :lanes :execution execution-1
                         :awaiting-user?])))
    (is (true? (get-in selected
                       [:state :relay :lanes :execution execution-2
                        :awaiting-user?])))
    (is (= execution-1
           (:tenure-id (peek (get-in selected [:state :pending-turns])))))))

(deftest attachment-only-input-is-persisted-with-quotes-and-draft
  (let [fixture (initial)
        result (apply! fixture
                       (command "c1" 1
                                {:kind :send-message
                                 :text ""
                                 :attachments [{:attachment-id "a1"
                                                :send-route :text
                                                :send-text "attachment body"}]
                                 :quotes [{:message-id "m1" :text "quoted"}]
                                 :turn-card-ids ["card-1"]
                                 :instruction-values {:tone "brief"}
                                 :draft {:draft-id "d1"
                                         :revision 2
                                         :text "unsent"}}))
        entry (get-in result [:state :conversation 0])]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= "" (:text entry)))
    (is (= "a1" (get-in entry [:attachments 0 :attachment-id])))
    (is (= "quoted" (get-in entry [:quotes 0 :text])))
    (is (= "unsent" (get-in entry [:draft :text])))
    (is (= ["card-1"] (:turn-card-ids entry)))
    (is (= {:tone "brief"} (:instruction-values entry)))
    (is (= "user-c1" (:message-id entry)))
    (is (= "unsent" (get-in result [:state :drafts "d1" :text])))))

(deftest attachment-without-a-stable-reference-is-refused
  (let [fixture (initial)
        result (apply! fixture
                       (command "c1" 1
                                {:kind :send-message
                                 :text "hello"
                                 :attachments [{:send-text "body"}]}))]
    (is (= :ATTACHMENT_REFERENCE_REQUIRED
           (get-in result [:receipt :error :code])))
    (is (= (:state fixture) (:state result)))))

(deftest attachment-problem-is-explicit
  (let [request {:snapshot {:messages
                            [{:role :user
                              :message-id "user-1"
                              :attachments [{:attachment-id "a1"
                                             :send-blob-id "b1"}]}]}}]
    (is (= :ATTACHMENT_UNRESOLVED
           (:code (state/attachment-problem request {}))))
    (is (nil? (state/attachment-problem request {:resolve? true})))
    (is (nil? (state/attachment-problem
               {:snapshot {:messages
                           [{:role :user
                             :attachments [{:attachment-id "a1"
                                            :send-route :text
                                            :send-text "body"}]}]}}
               {})))))

(deftest scope-refuses-a-tool-call-without-an-annotation
  (let [fixture (initial)
        base (:state fixture)
        state (assoc-in base [:calls "model-1"]
                        {:call-id "model-1"
                         :kind :model
                         :role :plan
                         :lane :plan
                         :agent-id :plan
                         :state :running
                         :attempt-id "a1"})
        result (handlers/apply-message
                state
                {:type :model/event
                 :event {:event :tool-call
                         :attempt-id "a1"
                         :model-call-id "model-1"
                         :call-id "tool-1"
                         :payload {:executor :tool :name "read"}}}
                (context (:registry fixture) 3000))]
    (is (= :refused (get-in result [:receipt :status])))
    (is (nil? (get-in result [:state :calls "model-1/tool-0"])))
    (let [conversation (:conversation (:state result))
          call (first (filter #(= :tool-call (:role %)) conversation))
          tool (first (filter #(= :tool (:role %)) conversation))]
      (is (= "model-1/tool-0" (:call-id call)))
      (is (= "model-1/tool-0" (:call-id tool)))
      (is (= "model-1/tool-0" (:tool-call-id tool)))
      (is (true? (:scope-refused? tool))))))

(defn- actor-owner-state
  "A committed state with one actor-owned model call and its live slot."
  [base]
  (-> base
      (assoc-in [:calls "model-1"]
                {:call-id "model-1"
                 :kind :model
                 :role :task
                 :lane :execution
                 :agent-id :worker-1
                 :state :running
                 :attempt-id "a1"
                 :actor-attempt-id "att-1"})
      (assoc-in [:actors :tenures "tenure-1" :slots :slot-a]
                {:slot-id :slot-a
                 :agent-id :worker-1
                 :status :running
                 :context []})
      (assoc-in [:actors :calls "att-1"]
                {:kind :swarm
                 :attempt-id "att-1"
                 :tenure-id "tenure-1"
                 :slot-id :slot-a
                 :agent-id :worker-1})))

(deftest actor-tool-refusal-stays-in-the-agent-context
  (let [fixture (initial)
        base (actor-owner-state (:state fixture))
        result (handlers/apply-message
                base
                {:type :model/event
                 :event {:event :tool-call
                         :attempt-id "a1"
                         :model-call-id "model-1"
                         :call-id "tool-1"
                         :payload {:executor :tool :name "read"}}}
                (context (:registry fixture) 3000))
        context (get-in result [:state :actors :tenures "tenure-1"
                                :slots :slot-a :context])
        refusal (last context)]
    (is (= :refused (get-in result [:receipt :status])))
    (is (empty? (get-in result [:state :conversation]))
        "an actor tool refusal must not enter the shared conversation")
    (is (= 1 (count context)))
    (is (= :tool (:role refusal)))
    (is (= "model-1/tool-0" (:tool-call-id refusal)))
    (is (true? (:error? refusal)))))

(deftest actor-tool-call-record-stays-out-of-the-conversation
  (let [fixture (initial)
        base (actor-owner-state (:state fixture))
        result (handlers/apply-message
                base
                {:type :model/event
                 :event {:event :tool-call
                         :attempt-id "a1"
                         :model-call-id "model-1"
                         :call-id "tool-1"
                         :tool-name "read"
                         :payload {:executor :tool
                                   :name "read"
                                   :scope {:kind :reset-scope
                                           :new-scope-goal "work"
                                           :new-scope-retention-budget 3}}}}
                (context (:registry fixture) 3000))]
    (is (= :accepted (get-in result [:receipt :status]))
        (pr-str (:receipt result)))
    (is (some? (get-in result [:state :calls "model-1/tool-0"])))
    (is (empty? (get-in result [:state :conversation]))
        "an actor tool-call record must not enter the shared conversation")))

(deftest recovery-resumes-retry-wait-without-moving-retry-at
  (let [fixture (initial)
        base (:state fixture)
        [state call] (state/create-call
                      base
                      {:kind :model
                       :role :plan
                       :lane :plan
                       :agent-id :plan
                       :request {:snapshot {}}
                       :created-at 1000})
        retry-state {:call-id (:call-id call)
                     :attempt-id "attempt-1"
                     :phase :retry-wait
                     :attempts-used 1
                     :retries-used 0
                     :max-attempts 4
                     :max-retries 3
                     :started-at 1000
                     :deadline-at 100000
                     :deadline 100000
                     :retry-at 5000
                     :effects {:status :none :repeat-safe? true}}
        state (-> state
                  (assoc-in [:calls (:call-id call) :state] :retry-wait)
                  (assoc-in [:calls (:call-id call) :retry] retry-state))
        early (state/recover-state state 2000)
        due (state/recover-state state 6000)
        exhausted (state/recover-state
                   (assoc-in state [:calls (:call-id call) :retry]
                             (assoc retry-state :attempts-used 4))
                   6000)]
    (testing "a future retry re-arms the persisted instant"
      (is (= :retry-wait (get-in early [:calls (:call-id call) :state])))
      (is (= 5000 (get-in early [:calls (:call-id call) :retry :retry-at])))
      (is (= :retry/rearm (get-in early [:outbox 0 :type]))))
    (testing "a due retry becomes claimable without moving :retry-at"
      (is (= :ready (get-in due [:calls (:call-id call) :state])))
      (is (= 5000 (get-in due [:calls (:call-id call) :retry :retry-at])))
      (is (true? (get-in due [:calls (:call-id call) :retry-due?]))))
    (testing "an exhausted budget fails the call"
      (is (= :failed (get-in exhausted [:calls (:call-id call) :state])))
      (is (= :retry-budget-exhausted
             (get-in exhausted [:calls (:call-id call) :last-error :kind]))))))


(deftest projected-conversation-preserves-structured-quotes
  (let [fixture (initial)
        state (update (:state fixture) :conversation conj
                      {:role :user
                       :message-id "u1"
                       :text "question"
                       :quotes [{:message-id "m1" :text "quoted body"}]})
        result (state/project-conversation state (:conversation state))
        message (first (:messages result))]
    (is (:ok? result))
    (is (= "question" (:text message)))
    (is (= "quoted body" (get-in message [:quotes 0 :text])))
    (is (= "m1" (get-in message [:quotes 0 :message-id])))))

(deftest projected-conversation-preserves-tool-call-arguments
  (let [fixture (initial)
        state (-> (:state fixture)
                  (update :conversation conj
                          {:role :tool-call
                           :member "shell"
                           :call-id "tool-1"
                           :arguments {:cmd "echo \"hi\""
                                       :path "C:\\tmp\\a b"}
                           :at 1})
                  (update :conversation conj
                          {:role :tool
                           :tool-call-id "tool-1"
                           :name "shell"
                           :call-id "tool-1"
                           :error? false
                           :text "{:status :succeeded}"
                           :at 2}))
        result (state/project-conversation state (:conversation state))
        messages (:messages result)
        assistant (some #(when (= :assistant (:role %)) %) messages)
        tool (some #(when (= :tool (:role %)) %) messages)]
    (is (:ok? result))
    (is (= "tool-1" (get-in assistant [:tool-calls 0 :id])))
    (is (= "shell" (get-in assistant [:tool-calls 0 :name])))
    (is (= {:cmd "echo \"hi\"" :path "C:\\tmp\\a b"}
           (get-in assistant [:tool-calls 0 :arguments])))
    (is (= "tool-1" (:tool-call-id tool)))
    (is (false? (:error? tool)))))

(deftest mission-extension-put-is-generic-and-durable
  (let [fixture (initial)
        result (apply! fixture
                       {:type :mission/extensions-put
                        :key :pair-hint
                        :value {:chain 7 :language "zh-CN"}})]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= {:chain 7 :language "zh-CN"}
           (get-in result [:state :mission :extensions :pair-hint])))))

(deftest completed-model-event-preserves-search-citations
  (let [fixture (initial)
        base (:state fixture)
        state (assoc-in base [:calls "model-1"]
                        {:call-id "model-1"
                         :kind :model
                         :role :plan
                         :lane :plan
                         :agent-id :plan
                         :state :running
                         :attempt-id "a1"})
        result (handlers/apply-message
                state
                {:type :model/event
                 :event {:event :completed
                         :attempt-id "a1"
                         :model-call-id "model-1"
                         :text "answer"
                         :citations [{:title "Source" :url "https://example.com"}]
                         :search-refs {:query "q"}
                         :search-source-list ["web"]
                         :search-plain-text "answer"}}
                (context (:registry fixture) 3000))
        message (first (get-in result [:state :conversation]))]
    (is (= [{:title "Source" :url "https://example.com"}]
           (:citations message)))
    (is (= {:query "q"} (:search-refs message)))
    (is (= ["web"] (:search-source-list message)))))

(defn- running-model-call
  "State with one running central model call for MODEL-EVENT tests."
  [fixture]
  (assoc-in (:state fixture) [:calls "model-1"]
            {:call-id "model-1"
             :kind :model
             :role :plan
             :lane :plan
             :agent-id :plan
             :state :running
             :attempt-id "a1"}))

(defn- model-event!
  [fixture event]
  (handlers/apply-message (:state fixture)
                          {:type :model/event :event event}
                          (context (:registry fixture) 3000)))

(deftest completed-tool-call-only-round-names-the-tools
  (let [fixture (initial)
        called (model-event!
                (assoc fixture :state (running-model-call fixture))
                {:event :tool-call
                 :attempt-id "a1"
                 :model-call-id "model-1"
                 :call-id "tool-1"
                 :payload {:executor :workspace
                           :name "write"
                           :program [:write]
                           :scope {:kind :reset-scope
                                   :new-scope-goal "test"
                                   :new-scope-retention-budget 8}}})
        done (model-event!
              (assoc fixture :state (:state called))
              {:event :completed
               :attempt-id "a1"
               :model-call-id "model-1"
               :text ""})
        assistant (->> (get-in done [:state :conversation])
                       (filter #(= :assistant (:role %)))
                       last)]
    (is (= "已调用工具：write" (:text assistant))
        "a tool-call-only round must not leave an empty assistant message")
    (is (= :finished (get-in done [:state :runs
                                  "model-1#a1" :status])))))

(deftest completed-round-with-text-and-tools-keeps-the-text
  (let [fixture (initial)
        called (model-event!
                (assoc fixture :state (running-model-call fixture))
                {:event :tool-call
                 :attempt-id "a1"
                 :model-call-id "model-1"
                 :call-id "tool-1"
                 :payload {:executor :workspace
                           :name "write"
                           :program [:write]
                           :scope {:kind :reset-scope
                                   :new-scope-goal "test"
                                   :new-scope-retention-budget 8}}})
        done (model-event!
              (assoc fixture :state (:state called))
              {:event :completed
               :attempt-id "a1"
               :model-call-id "model-1"
               :text "done writing the file"})
        assistant (->> (get-in done [:state :conversation])
                       (filter #(= :assistant (:role %)))
                       last)]
    (is (= "done writing the file" (:text assistant)))
    (is (not (str/includes? (str (:text assistant)) "已调用工具")))))

(deftest completed-round-with-preamble-and-tools-keeps-the-preamble
  (let [fixture (initial)
        called (model-event!
                (assoc fixture :state (running-model-call fixture))
                {:event :tool-call
                 :attempt-id "a1"
                 :model-call-id "model-1"
                 :call-id "tool-1"
                 :text "let me check that"
                 :payload {:executor :workspace
                           :name "write"
                           :program [:write]
                           :scope {:kind :reset-scope
                                   :new-scope-goal "test"
                                   :new-scope-retention-budget 8}}})
        done (model-event!
              (assoc fixture :state (:state called))
              {:event :completed
               :attempt-id "a1"
               :model-call-id "model-1"
               :text ""})
        assistant (->> (get-in done [:state :conversation])
                       (filter #(= :assistant (:role %)))
                       last)]
    (is (= "let me check that" (:text assistant))
        "streamed or preamble text wins over the tool-call placeholder")))

(deftest completed-model-event-mirrors-usage-into-history
  ;; The :completed event carries the model-port usage report.  The mirror
  ;; into session history must forward it unchanged so the durable version
  ;; keeps the report; the read side projects the flat token fields.
  (let [mirrored (atom nil)
        port {:apply-command
              (fn [session payload]
                (when (= :history/append-message (:kind payload))
                  (reset! mirrored payload))
                {:ok? true
                 :session (assoc (or session {}) :last (:kind payload))
                 :result {:status :accepted}
                 :effects []})}
        fixture (initial)
        ctx (assoc (context (:registry fixture) 3000) :session-port port)
        report {:coverage :final
                :usage {:input-tokens 100 :output-tokens 20
                        :cache-read-tokens :unknown
                        :cache-write-tokens :unknown}
                :sources {:input-tokens :reported
                          :output-tokens :reported
                          :cache-read-tokens :unknown
                          :cache-write-tokens :unknown}}
        done (handlers/apply-message
              (assoc-in (:state fixture) [:calls "model-1"]
                        {:call-id "model-1"
                         :kind :model
                         :role :plan
                         :lane :plan
                         :agent-id :plan
                         :state :running
                         :attempt-id "a1"})
              {:type :model/event
               :event {:event :completed
                       :attempt-id "a1"
                       :model-call-id "model-1"
                       :text "priced answer"
                       :usage report}}
              ctx)]
    (is (= :accepted (get-in done [:receipt :status])))
    (is (= report (:usage @mirrored))
        "the history mirror payload carries the full usage report")))

(deftest scope-close-waits-for-live-child-tools
  (let [fixture (initial)
        state (-> (:state fixture)
                  (assoc-in [:calls "m1"]
                            {:call-id "m1" :kind :model :state :succeeded})
                  (assoc-in [:calls "t1"]
                            {:call-id "t1" :kind :tool :state :ready
                             :scope-turn-id "m1" :scope-exempt? false}))]
    (is (true? (state/live-scope-children? state "m1")))
    (is (false? (state/live-scope-children?
                 (assoc-in state [:calls "t1" :state] :succeeded)
                 "m1")))
    (is (false? (state/live-scope-children?
                 (assoc-in state [:calls "t1" :scope-exempt?] true)
                 "m1")))))

(deftest operator-cannot-submit-trusted-review-or-board-command
  (let [fixture (initial)]
    (is (= :REVIEW_OPERATOR_FORBIDDEN
           (get-in (apply! fixture
                           (assoc (command "r1" 1
                                           {:kind :review/submit
                                            :lane :plan
                                            :tenure-id "tenure-1"
                                            :findings []})
                                  :operator? true))
                   [:receipt :error :code])))
    (is (= :BOARD_OPERATOR_FORBIDDEN
           (get-in (apply! fixture
                           (assoc (command "b1" 1
                                           {:kind :board/command
                                            :command {:op :wake-all}})
                                  :operator? true))
                   [:receipt :error :code])))
    (is (= :REPORT_OPERATOR_FORBIDDEN
           (get-in (apply! fixture
                           (assoc (command "p1" 1
                                           {:kind :report/register
                                            :report {:report-id "r"
                                                     :parent-call-id "p"
                                                     :payload {:kind :llm-report
                                                               :text "x"}}})
                                  :operator? true))
                   [:receipt :error :code])))))

(deftest extension-put-does-not-reset-nudge-progress
  (let [fixture (initial)
        extended (apply! fixture
                         {:type :mission/extensions-put
                          :key :pair-hint
                          :value {:chain 1}})
        messaged (handlers/apply-message
                  (:state extended)
                  (command "c1" 1 {:kind :send-message :text "hi"})
                  (context (:registry fixture) 3000))]
    (is (= 0 (get-in extended [:state :progress-seq])))
    (is (= 1 (get-in messaged [:state :progress-seq])))))

(deftest pending-model-suppresses-nudge-and-is-claimable
  (let [fixture (initial)
        state (assoc-in (:state fixture) [:calls "m1"]
                        {:call-id "m1" :kind :model :agent-id :plan
                         :state :ready})]
    (is (true? (state/pending-model-for-agent? state :plan)))
    (is (false? (state/pending-model-for-agent?
                 (assoc-in state [:calls "m1" :state] :succeeded)
                 :plan)))))

(deftest quote-only-message-is-valid
  (let [fixture (initial)
        result (apply! fixture
                       (command "q1" 1
                                {:kind :send-message
                                 :text ""
                                 :quotes [{:message-id "m1" :text "quoted"}]}))
        entry (get-in result [:state :conversation 0])]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= "" (:text entry)))
    (is (= "quoted" (get-in entry [:quotes 0 :text])))))

(deftest attachment-policy-refusal-preserves-state
  (let [fixture (initial)
        ctx (assoc (context (:registry fixture) 2000)
                   :attachment-policy
                   (fn [_] {:ok? false
                            :error {:code :ATTACHMENT_INCOMPATIBLE}}))
        result (handlers/apply-message
                (:state fixture)
                (command "a1" 1
                         {:kind :send-message
                          :text "hi"
                          :attachments [{:attachment-id "a"
                                         :send-route :native
                                         :send-blob-id "b"}]
                          :draft {:draft-id "d1" :text "unsent"}})
                ctx)]
    (is (= :ATTACHMENT_NOT_SENDABLE
           (get-in result [:receipt :error :code])))
    (is (= (:state fixture) (:state result)))))

(defn- set-establishing-run
  "Give FIXTURE `:state` the run state `:cancelling` with admission closed,
  and one live shell call with an unobserved resource, as it looks after Esc
  but before the managed process reports its final exit."
  [state]
  (let [run-id (get-in state [:cancel :active-run-id])
        state (-> state
                  (assoc-in [:cancel :nodes run-id :state] :cancelling)
                  (assoc-in [:cancel :nodes run-id :admission] :closed)
                  (assoc-in [:cancel :nodes run-id :cancellation]
                            {:reason :user-cancel
                             :origin :operator-esc
                             :accepted-at 1500}))
        call-id "shell-1"
        node-id (str call-id "#a1")]
    (-> state
        (assoc-in [:calls call-id]
                  {:call-id call-id
                   :kind :shell
                   :agent-id :agent-x
                   :state :outcome-unknown
                   :attempt-id "a1"})
        (update :cancel cancel/add-node
                {:node-id call-id
                 :kind :call
                 :parent-id run-id
                 :state :outcome-unknown
                 :side-effecting? true
                 :resource-alive? true})
        (update :cancel cancel/add-node
                {:node-id node-id
                 :kind :attempt
                 :parent-id call-id
                 :state :outcome-unknown
                 :attempt-id "a1"
                 :resource-alive? true})
        (assoc :turn {:role :main
                      :agent-id :main
                      :ended? true
                      :at 1500}))))

(defn- new-run-message
  [state]
  (handlers/apply-message
   state
   (command "c2" 1 {:kind :send-message :text "again"})
   {:now 3000 :registry (commands/local-registry)}))

(deftest user-message-during-cancellation-keeps-the-old-run-active
  (let [state (set-establishing-run (:state (initial)))
        old-run-id (get-in state [:cancel :active-run-id])
        result (new-run-message state)
        after (:state result)]
    (testing "the message is recorded without opening a second admission"
      (is (= :accepted (get-in result [:receipt :status])))
      (is (= old-run-id (get-in after [:cancel :active-run-id])))
      (is (= :cancelling (get-in after [:cancel :nodes old-run-id :state])))
      (is (= :closed (get-in after [:cancel :nodes old-run-id :admission])))
      (is (false? (state/admission-open? after))
          "the cancelled run keeps admission closed until it is quiescent")
      (is (state/successor-run-pending? after)
          "the turn keeps a committed request for a successor run")
      (is (true? (get-in after [:cancel :reopen-requested?]))
          "the marker itself must be what the successor guard reads")
      (is (= 1 (count (:pending-turns after))))
      (is (= "again" (:text (peek (:conversation after))))))))

(deftest user-message-after-a-quiescent-cancel-opens-a-new-run
  (let [fixture (initial)
        base (:state fixture)
        old-run-id (get-in base [:cancel :active-run-id])
        cancelled (-> base
                      (assoc-in [:cancel :nodes old-run-id :state] :cancelled)
                      (assoc-in [:cancel :nodes old-run-id :admission] :closed))
        result (new-run-message cancelled)
        new-run-id (get-in result [:state :cancel :active-run-id])]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (not= new-run-id old-run-id))
    (is (= :open (get-in result [:state :cancel :nodes new-run-id
                                 :admission])))
    (is (= 1 (count (get-in result [:state :pending-turns]))))))

(deftest message-during-cancellation-commits-a-successor-request
  ;; Two things must hold in the same transition: the message records an
  ;; intent for `open-successor-run-guard`, and the superseded cancel domain
  ;; leaves no hold deadline behind for the run that replaces it.
  (let [fixture (initial)
        base (:state fixture)
        run-id (get-in base [:cancel :active-run-id])
        cancelling (-> base
                       (assoc-in [:cancel :nodes run-id :state] :cancelling)
                       (assoc-in [:cancel :nodes run-id :admission] :closed))
        deferred (:state (new-run-message cancelling))]
    (is (= run-id (get-in deferred [:cancel :active-run-id]))
        "a cancelling run stays active; no second admission opens")
    (is (state/successor-run-pending? deferred)
        "the queued turn must carry a committed successor request")
    (is (= 1 (count (:pending-turns deferred)))))
  (let [fixture (initial)
        base (:state fixture)
        run-id (get-in base [:cancel :active-run-id])
        cancelled (-> base
                      (assoc-in [:cancel :nodes run-id :state] :cancelled)
                      (assoc-in [:cancel :nodes run-id :admission] :closed))
        opened (:state (new-run-message cancelled))]
    (is (false? (state/successor-run-pending? opened))
        "the run this message opened needs no deferred request")))

(defn- cancelling-domain
  "A cancelling run with one late `:retry-wait` call under it."
  [cancellation]
  (let [base (:state (initial))
        run-id (get-in base [:cancel :active-run-id])]
    (-> base
        (assoc-in [:cancel :nodes run-id :state] :cancelling)
        (assoc-in [:cancel :nodes run-id :admission] :closed)
        (assoc-in [:cancel :nodes run-id :cancellation]
                  (assoc cancellation
                         :command-id (:command-id cancellation)))
        (assoc-in [:calls "late-1"]
                  {:call-id "late-1" :kind :shell
                   :agent-id :agent-x :state :retry-wait
                   :attempt-id "a1"})
        (update :cancel cancel/add-node
                {:node-id "late-1" :kind :call
                 :parent-id run-id :state :retry-wait
                 :side-effecting? true
                 :current-attempt-id "a1"}))))

(deftest late-node-cancel-keeps-the-operator-origin
  ;; The late-node transition is producer-agnostic: it signals the node with
  ;; the domain's own reason and origin.  The operator domain therefore
  ;; keeps `:user-cancel` from `:operator-esc`, and nothing is rewritten to
  ;; look like a human stop it was not.
  (let [state (cancelling-domain {:reason :user-cancel
                                  :origin :operator-esc
                                  :command-id "c1"
                                  :accepted-at 1000})
        run-id (get-in state [:cancel :active-run-id])
        result (cancel/cancel-run-late-nodes
                (:cancel state)
                {:run-id run-id :node-id "late-1"
                 :reason :user-cancel
                 :origin :operator-esc
                 :at 2500})
        node (get-in (:state result) [:nodes "late-1"])]
    (is (:ok? result))
    (is (= :operator-esc (:cancel-origin node)))
    (is (= :user-cancel (:cancel-root-reason node)))
    (is (= :cancelled (:state node))
        "a not-yet-started call is cancelled, not marked successful")))

(deftest late-node-cancel-keeps-a-shutdown-domain-distinct
  ;; An upstream executor shutdown is a cancellation in its own right, with
  ;; its own reason and origin.  It must reach the node unchanged, not be
  ;; refused and not be relabelled as `:user-cancel` from an operator.
  (let [state (cancelling-domain {:reason :executor-shutdown
                                  :origin :executor-shutdown
                                  :accepted-at 1000})
        run-id (get-in state [:cancel :active-run-id])
        result (cancel/cancel-run-late-nodes
                (:cancel state)
                {:run-id run-id :node-id "late-1"
                 :reason :executor-shutdown
                 :origin :executor-shutdown
                 :at 2500})
        node (get-in (:state result) [:nodes "late-1"])]
    (is (:ok? result))
    (is (= :executor-shutdown (:cancel-origin node)))
    (is (= :executor-shutdown (:cancel-root-reason node)))
    (is (= :cancelled (:state node)))
    (testing "an operator origin may not be forged onto another reason"
      (let [original (:cancel state)
            forged (cancel/cancel-run-late-nodes
                    original
                    {:run-id run-id :node-id "late-1"
                     :reason :executor-shutdown
                     :origin :operator-esc
                     :at 2500})]
        (is (false? (:ok? forged)))
        ;; `failure` returns the tree it was given, so identity holds here
        ;; and the equality below is the contract that matters: a refusal
        ;; publishes no change.
        (is (= original (:state forged))
            "a refused signal must not change the tree")))))

(deftest cancel-outbox-keeps-node-and-executor-kinds-distinct
  (let [state (:state (initial))
        effects [{:effect :abort-resource
                  :node-id "call-1#a1"
                  :node-kind :attempt
                  :kind :attempt
                  :executor-kind :shell
                  :resource-kind :shell
                  :resource-id "proc-1"
                  :call-id "call-1"
                  :attempt-id "a1"}
                 {:effect :abort-resource
                  :node-id "legacy-call"
                  :kind :call
                  :call-id "legacy-call"
                  :attempt-id "a2"}]
        out (#'guards/cancel-outbox state effects :user-cancel)
        [new-effect old-effect] out]
    (is (= :attempt (:node-kind new-effect)))
    (is (= :shell (:kind new-effect)))
    (is (= :shell (:resource-kind new-effect)))
    (is (= "proc-1" (:resource-id new-effect)))
    (is (= "call-1" (:call-id new-effect)))
    (testing "an old effect with only :kind keeps node-kind semantics"
      (is (= :call (:node-kind old-effect)))
      (is (= :call (:kind old-effect))))))

(deftest session-run-cannot-claim-under-closed-admission
  (let [base (state/initial-state {:session-id "closed-admission"})
        run-id (get-in base [:cancel :active-run-id])
        base (assoc-in base [:cancel :nodes run-id :admission] :closed)
        [base call] (state/create-call
                     base
                     {:kind :model
                      :role :main
                      :lane :execution
                      :agent-id "session-run-r1"
                      :request {:snapshot {}}})
        base (assoc-in base [:calls (:call-id call) :session-run]
                       {:run-id "r1"})
        guard (guards/claim-model-request-guard)]
    (is (nil? ((:enabled? guard) base {:now 1}))
        "a closed admission blocks session-run model claims too")))

(deftest plan-turn-merges-extension-tools-and-skill-context
  (let [fixture (initial {:config {:plan-tools [{:name "devops"}]}})
        sent (apply! fixture (command "c1" 1
                                      {:kind :send-message :text "hello"}))
        state (:state sent)
        guard (guards/open-user-turn-guard)
        ctx {:now 2000
             :extension-tools-fn (fn [_scope] [{:name "mcp-tool"}])
             :skill-context-fn (fn [_scope]
                                 [{:id "s1"
                                   :name "Skill One"
                                   :text "skill body"}])}
        work ((:enabled? guard) state ctx)
        prepared (:state ((:prepare guard) state ctx work nil))
        call (some #(when (= :model (:kind %)) %) (vals (:calls prepared)))
        snapshot (get-in call [:request :snapshot])]
    (is (= ["devops" "mcp-tool"] (mapv :name (:tools snapshot))))
    (is (= :system (get-in snapshot [:messages 0 :role])))
    (is (str/includes? (get-in snapshot [:messages 0 :text]) "skill body"))
    (is (= :user (get-in snapshot [:messages 1 :role]))
        "the skill system message precedes the conversation tail")))

(deftest tool-request-preserves-mcp-metadata
  (let [fixture (initial)
        [state _] (state/create-call
                   (:state fixture)
                   {:call-id "model-1"
                    :kind :model
                    :role :plan
                    :lane :plan
                    :agent-id :plan
                    :tenure-id "tenure-1"
                    :request {:snapshot {}}})
        state (-> state
                  (assoc-in [:calls "model-1" :state] :running)
                  (assoc-in [:calls "model-1" :attempt-id] "a1"))
        result (handlers/apply-message
                state
                {:type :model/event
                 :event {:event :tool-call
                         :model-call-id "model-1"
                         :attempt-id "a1"
                         :call-id "provider-1"
                         :output-index 0
                         :payload {:executor :tool
                                   :name "mcp__s__t"
                                   :metadata {:server-id "s"
                                              :tool "t"}
                                   :scope {:kind :reset-scope
                                           :new-scope-goal "mcp"
                                           :new-scope-retention-budget 4}}}}
                (context (:registry fixture) 2000))
        call (get-in result [:state :calls "model-1/tool-0"])]
    (is (some? call))
    (is (= {:server-id "s" :tool "t"}
           (get-in call [:request :metadata])))))

(deftest user-send-callback-fires-once-for-accepted-send
  (let [fixture (initial)
        calls (atom [])
        ctx (assoc (context (:registry fixture) 2000)
                   :on-user-send! #(swap! calls conj %))
        result (handlers/apply-message
                (:state fixture)
                (command "c1" 1 {:kind :send-message :text "hello"})
                ctx)]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= 1 (count @calls)))
    (is (= "c1" (:send-id (first @calls))))))

(deftest user-send-callback-does-not-fire-for-refused-send
  (let [fixture (initial)
        calls (atom [])
        ctx (assoc (context (:registry fixture) 2000)
                   :on-user-send! #(swap! calls conj %))
        result (handlers/apply-message
                (:state fixture)
                (command "c1" 1 {:kind :send-message :text ""})
                ctx)]
    (is (false? (get-in result [:receipt :ok?])))
    (is (empty? @calls))))

(deftest user-send-callback-exception-does-not-break-the-transition
  (let [fixture (initial)
        ctx (assoc (context (:registry fixture) 2000)
                   :on-user-send! (fn [_] (throw (ex-info "boom" {}))))
        result (handlers/apply-message
                (:state fixture)
                (command "c1" 1 {:kind :send-message :text "hello"})
                ctx)]
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= "hello" (get-in result [:state :conversation 0 :text])))))

(deftest call-terminal-callback-fires-once-for-non-model-call
  (let [fixture (initial)
        [state call] (state/create-call
                      (:state fixture)
                      {:kind :tool
                       :role :plan
                       :lane :plan
                       :agent-id :plan
                       :request {}})
        state (-> state
                  (assoc-in [:calls (:call-id call) :state] :running)
                  (assoc-in [:calls (:call-id call) :attempt-id] "a1"))
        calls (atom [])
        ctx (assoc (context (:registry fixture) 2000)
                   :on-call-terminal! #(swap! calls conj %))
        message {:type :call/terminal
                 :call-id (:call-id call)
                 :attempt-id "a1"
                 :status :succeeded
                 :payload {:ok true}}
        first-result (handlers/apply-message state message ctx)
        _ (handlers/apply-message (:state first-result) message ctx)]
    (is (= 1 (count @calls)))
    (is (= (:call-id call) (:tool-call-id (first @calls))))))

(deftest operator-acknowledges-an-unknown-call-once
  (let [fixture (initial)
        state (assoc-in (:state fixture) [:calls "call-x"]
                        {:call-id "call-x"
                         :kind :tool
                         :role :worker
                         :state :outcome-unknown
                         :created-at 1000
                         :last-error {:code :SHELL_OUTCOME_UNKNOWN
                                      :message "no terminal"}})
        ack (fn [payload]
              (handlers/apply-message
               state
               (command "ack-1" 1 (merge {:kind :call/ack
                                          :call-id "call-x"
                                          :operator? true}
                                         payload))
               (context (:registry fixture) 2000)))
        first-result (ack {:outcome :succeeded
                           :expected-state :outcome-unknown
                           :note "checked the target file"})
        call (get-in first-result [:state :calls "call-x"])
        replay (handlers/apply-message
                (:state first-result)
                (command "ack-2" 1 {:kind :call/ack
                                    :call-id "call-x"
                                    :outcome :succeeded
                                    :operator? true})
                (context (:registry fixture) 3000))
        conflicting (handlers/apply-message
                     (:state first-result)
                     (command "ack-3" 1 {:kind :call/ack
                                         :call-id "call-x"
                                         :outcome :not-occurred
                                         :operator? true})
                     (context (:registry fixture) 3000))]
    (testing "a checked-success acknowledgement closes the call"
      (is (= :accepted (get-in first-result [:receipt :status])))
      (is (= :acknowledged (get-in first-result [:receipt :outcome])))
      (is (= :succeeded (:state call)))
      (is (= :succeeded (get-in call [:ack :outcome])))
      (is (= :operator (get-in call [:ack :by])))
      (is (= 2000 (get-in call [:ack :at])))
      (is (= "checked the target file" (get-in call [:ack :note])))
      (is (= :SHELL_OUTCOME_UNKNOWN (get-in call [:last-error :code]))
          "the unknown-outcome evidence is retained")
      (is (empty? (:outbox first-result))
          "acknowledgement never replays the action"))
    (testing "the same acknowledgement is idempotent"
      (is (= :already-acknowledged (get-in replay [:receipt :outcome])))
      (is (= (get-in first-result [:state :calls "call-x"])
             (get-in replay [:state :calls "call-x"]))
          "the call record is unchanged by a repeated acknowledgement"))
    (testing "a different conclusion on a closed call is refused"
      (is (= :CALL_NOT_UNKNOWN (get-in conflicting [:receipt :error :code])))
      (is (= (get-in first-result [:state :calls "call-x"])
             (get-in conflicting [:state :calls "call-x"]))))))

(deftest call-acknowledgement-refuses-stale-and-non-operator-requests
  (let [fixture (initial)
        state (assoc-in (:state fixture) [:calls "call-y"]
                        {:call-id "call-y"
                         :kind :tool
                         :state :outcome-unknown
                         :created-at 1000})
        apply-ack (fn [message]
                    (handlers/apply-message
                     state message (context (:registry fixture) 2000)))]
    (testing "a non-operator command cannot close a call"
      (let [result (apply-ack (command "ack-1" 1 {:kind :call/ack
                                                  :call-id "call-y"
                                                  :outcome :succeeded}))]
        (is (= :CALL_ACK_OPERATOR_ONLY
               (get-in result [:receipt :error :code])))))
    (testing "a stale expected state is refused without a write"
      (let [result (apply-ack (command "ack-2" 1
                                       {:kind :call/ack
                                        :call-id "call-y"
                                        :outcome :succeeded
                                        :expected-state :running
                                        :operator? true}))]
        (is (= :CALL_STATE_CHANGED
               (get-in result [:receipt :error :code])))
        (is (= :outcome-unknown
               (get-in result [:state :calls "call-y" :state])))))
    (testing "an invalid conclusion is refused"
      (let [result (apply-ack (command "ack-3" 1
                                       {:kind :call/ack
                                        :call-id "call-y"
                                        :outcome :maybe
                                        :operator? true}))]
        (is (= :CALL_ACK_INVALID
               (get-in result [:receipt :error :code])))))
    (testing "an unknown call id is refused"
      (let [result (apply-ack (command "ack-4" 1
                                       {:kind :call/ack
                                        :call-id "missing"
                                        :outcome :succeeded
                                        :operator? true}))]
        (is (= :UNKNOWN_CALL
               (get-in result [:receipt :error :code])))))))
