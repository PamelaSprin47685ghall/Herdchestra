(ns hc.compose.nudge.owner-integration-test
  "Real Owner-runtime Nudge transitions.

  These tests do not call `nudge/decide` directly.  They commit a
  `:turn/ended` message or a real model terminal into the Owner mailbox and
  assert the resulting state: a dispatched Nudge model call, a legal exit,
  or an explicit blocked diagnostic.  Gateways hold requests when a test
  needs to stop a chain."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.cancel :as cancel]
            [hc.compose.mission :as mission]
            [hc.compose.mission.actors :as actors]
            [hc.compose.owner :as owner]
            [hc.compose.owner.commands :as commands]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.state :as state]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]))

(def ^:private owner-counter (atom 0))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 20) (recur))
        :else false))))

(defn- utf8-size
  ^long [^String s]
  (alength (.getBytes s "UTF-8")))

(defn- hold-gateway
  "Record the model request and leave the attempt running."
  [requests]
  {:start! (fn [request _deliver!]
             (swap! requests conj request)
             {:attempt-id (:attempt-id request)})
   :cancel! (fn [_] nil)})

(defn- gated-model-gateway
  "Record every model request and hold its events until the test releases
  them, one `deliver!` per request in start order.

  The Owner settles a turn only after the model terminal commits, and a
  tool-free `:succeeded` stop is the `:plain-response` legal exit.  Tests
  that need several Nudge rounds must therefore gate each request: an
  instantly-completing gateway runs the whole chain to the round boundary
  before the assertions can observe the live turn.  `deliver!` for the
  `:completed` event queues the call terminal, so releasing it is the same
  as the gateway answering.

  Returns `{:gateway g :releases (atom [deliver! ...])}`; call
  `(deliver! {:event :tool-call ...})` then `(deliver! {:event :completed
  ...})` on the released slot to finish that round with tool evidence."
  [requests]
  (let [releases (atom [])]
    {:gateway {:start! (fn [request deliver!]
                         (swap! requests conj request)
                         (swap! releases conj deliver!)
                         {:attempt-id (:attempt-id request)})
              :cancel! (fn [_] nil)}
     :releases releases}))

(defn- start-owner
  [requests opts]
  (owner/start-owner!
   (merge {:session-id (str "nudge-owner-" (swap! owner-counter inc))
           :ephemeral? true
           :models {:main "main-model" :task "task-model"}
           ;; These tests isolate the Nudge policy.  Actor slot dispatch has
           ;; its own Owner integration test; no slots here means no actor
           ;; model turns competing with the manual turn-ended message.
           :config {:swarm-slots []}
           :model-gateway (hold-gateway requests)}
          opts)))

(defn- state
  [handle]
  (:state ((:snapshot handle))))

(defn- command!
  [handle command-id payload]
  ((:submit-command! handle)
   {:command-id command-id
    :command-epoch 1
    :payload payload}))

(defn- turn-ended!
  [handle role tenure-id agent-id]
  ((:submit! handle)
   {:type :turn/ended
    :role role
    :tenure-id tenure-id
    :agent-id agent-id}))

(defn- nudge-calls
  [state]
  (filter :nudge? (vals (:calls state))))

(defn- last-history
  [state]
  (last (:turn-history state)))

(defn- execution-id
  [handle]
  (first (keys (get-in (state handle)
                       [:mission :relay :lanes :execution]))))

(defn- start-execution!
  [handle]
  (command! handle "handoff"
            {:kind :review/submit
             :lane :plan
             :tenure-id "tenure-1"
             :findings []})
  (is (wait-for #(some? (execution-id handle)) 3000))
  (execution-id handle))

(defn- start-swarm!
  [handle tenure-id]
  (command! handle "swarm"
            {:kind :review/submit
             :lane :execution
             :tenure-id tenure-id
             :findings [{:key "work" :value "Do the work"}]})
  (is (wait-for #(= :active
                    (get-in (state handle)
                            [:mission :relay :lanes :execution tenure-id
                             :swarm-phase]))
                3000))
  tenure-id)

(defn- register-worker!
  [handle tenure-id slot-id]
  (let [receipt ((:execute-board! handle)
                 {:op :register-actor :slot-id slot-id})
        worker-id (or (:agent-id receipt)
                      (get-in receipt [:receipt :agent-id])
                      (some (fn [[id actor]]
                              (when (= slot-id (:slot-id actor)) id))
                            (get-in (state handle)
                                    [:mission :boards tenure-id :actors])))]
    (is (some? worker-id) (pr-str receipt))
    worker-id))

(defn- claim-root!
  [handle tenure-id worker-id]
  (let [task-id (first (keys (get-in (state handle)
                                     [:mission :boards tenure-id :tasks])))
        receipt ((:execute-board! handle)
                 {:op :claim
                  :actor-id worker-id
                  :task-id task-id
                  :atomicity-reason "one root task"})]
    (is (= :accepted (:status receipt)) (pr-str receipt))
    task-id))

(deftest plan-turn-end-dispatches-nudge
  (let [requests (atom [])
        handle (start-owner requests {})]
    (try
      (turn-ended! handle :plan "tenure-1" :plan)
      (is (wait-for #(= 1 (count (nudge-calls (state handle)))) 3000))
      (is (wait-for #(= 1 (count @requests)) 3000))
      (let [state (state handle)
            call (first (nudge-calls state))
            request (first @requests)]
        (is (= :plan (:role call)))
        (is (= :plan (:agent-id call)))
        (is (= "tenure-1" (:tenure-id call)))
        (is (= "nudge-plan-tenure-1-1" (:nudge-id call)))
        (is (= (:nudge-id call)
               (get-in call [:request :snapshot :nudge-id])))
        (is (re-find #"review 账本"
                     (get-in call [:request :snapshot :messages 0 :text])))
        (is (false? (get-in state [:turn :ended?]))
            "the Nudge call owns a live turn")
        (is (= (:call-id call) (get-in state [:turn :call-id])))
        (is (= 1 (:nudge-seq state)))
        (is (= :main (get-in request [:snapshot :role])))
        (is (= :plan (get-in request [:snapshot :mission-role])))
        (is (= :plan (get-in request [:snapshot :lane]))))
      (finally
        ((:stop! handle))))))

(deftest plan-long-ledger-nudge-stays-under-the-message-cap
  (let [requests (atom [])
        handle (start-owner requests {})
        long-value (apply str (repeat 10240 "z"))]
    (try
      (command! handle "long-ledger"
                {:kind :review/submit
                 :lane :plan
                 :tenure-id "tenure-1"
                 :findings [{:key "long-item" :value long-value}]})
      (is (wait-for #(= long-value
                        (get-in (state handle)
                                [:mission :relay :lanes :plan
                                 :ledger "long-item"]))
                    3000))
      (turn-ended! handle :plan "tenure-1" :plan)
      (is (wait-for #(= 1 (count (nudge-calls (state handle)))) 3000))
      (is (wait-for #(= 1 (count @requests)) 3000))
      (let [messages (get-in (first @requests) [:snapshot :messages])
            message (some (fn [m]
                            (let [text (str (:text m))]
                              (when (str/includes? text "review 账本")
                                text)))
                          messages)]
        (is (some? message))
        (when message
          (is (<= (utf8-size message) 16384))
          (is (str/includes? message "long-item"))
          (is (str/includes? message "…[+"))
          (is (str/includes? message "(truncated)"))
          (is (str/includes?
               message
               "系统是无人值守的常授权模式，禁止等待用户决策或授权，禁止半途而废。"))
          (is (str/includes? message "立刻去做"))))
      (finally
        ((:stop! handle))))))

(deftest model-completion-emits-turn-and-nudges
  (let [requests (atom [])
        ;; The user turn invokes a tool, so its end is task-cycle work and
        ;; still owes a Nudge.  The Nudge request itself is held open: a
        ;; tool-free Nudge answer would be the `:plain-response` legal
        ;; exit and clear the live turn this test asserts on.  `negotiate`
        ;; is a Scope-exempt member, so the fake call needs no `:scope`.
        gateway {:start! (fn [request deliver!]
                           (let [n (count (swap! requests conj request))]
                             (if (= 1 n)
                               (do
                                 (deliver! {:event :tool-call
                                            :call-id "negotiate-1"
                                            :name "negotiate"
                                            :payload {}})
                                 (deliver! {:event :completed
                                            :text "stopped early"}))
                               nil))
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        handle (start-owner requests {:model-gateway gateway})]
    (try
      (command! handle "c1"
                {:kind :send-message :text "hello"})
      ;; Wait until the Nudge call exists and owns a live turn; the held
      ;; request keeps that window open instead of racing the next settle.
      (is (wait-for
           (fn []
             (let [s (state handle)
                   c (first (nudge-calls s))]
               (and (some? c)
                    (false? (get-in s [:turn :ended?]))
                    (= (:call-id c) (get-in s [:turn :call-id])))))
           5000))
      (let [state (state handle)
            nudge-request (second @requests)
            messages (get-in nudge-request [:snapshot :messages])
            nudge-call (first (nudge-calls state))]
        (is (some? nudge-call))
        (is (= 1 (count (nudge-calls state))))
        (is (= :plan (:agent-id nudge-call)))
        (is (= "tenure-1" (:tenure-id nudge-call)))
        (is (= "nudge-plan-tenure-1-1" (:nudge-id nudge-call)))
        (is (some #(re-find #"hello" (str (:text %))) messages))
        (is (some #(re-find #"stopped early" (str (:text %))) messages))
        (is (re-find #"review 账本" (str (:text (last messages)))))
        (is (false? (get-in state [:turn :ended?]))
            "the Nudge call owns a live turn")
        (is (= (:call-id nudge-call) (get-in state [:turn :call-id]))))
      (finally
        ((:stop! handle))))))

(deftest session-binding-reaches-the-turn-and-its-nudge
  (let [requests (atom [])
        binding {:connection-id "alpha"
                 :model-entry-id "same-model"
                 :display-name "Same Model"
                 :capabilities #{:tools}}
        gateway {:start! (fn [request deliver!]
                           (swap! requests conj request)
                           ;; Same shape as `model-completion-emits-turn-
                           ;; and-nudges`: the user turn calls the exempt
                           ;; `negotiate` member and the Nudge request is
                           ;; held open so its live turn keeps the frozen
                           ;; model binding this test asserts on.
                           (when (= 1 (count @requests))
                             (deliver! {:event :tool-call
                                        :call-id "negotiate-1"
                                        :name "negotiate"
                                        :payload {}})
                             (deliver! {:event :completed
                                        :text "stopped early"}))
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        handle (start-owner requests
                            {:model-gateway gateway
                             :initial-session
                             {:model-binding binding}})]
    (try
      (command! handle "c1"
                {:kind :send-message :text "hello"})
      ;; The Nudge owns a live turn while its request is held open; wait
      ;; for that window instead of racing the next settle.
      (is (wait-for
           (fn []
             (let [s (state handle)
                   c (first (nudge-calls s))]
               (and (some? c)
                    (false? (get-in s [:turn :ended?]))
                    (= (:call-id c) (get-in s [:turn :call-id])))))
           5000))
      (let [turn-request (first @requests)
            nudge-request (second @requests)
            owner-state (state handle)]
        (is (= "alpha" (get-in turn-request [:snapshot :connection])))
        (is (= "same-model" (get-in turn-request [:snapshot :model])))
        (is (= "alpha" (get-in nudge-request [:snapshot :connection]))
            "the Nudge continues the turn's frozen model identity")
        (is (= "same-model" (get-in nudge-request [:snapshot :model])))
        (is (= {:connection "alpha" :model "same-model"}
               (get-in owner-state [:turn :model-binding]))
            "the ended turn records the identity its Nudge must reuse"))
      (finally
        ((:stop! handle))))))

(deftest plain-answer-turn-ends-without-nudge
  ;; A user turn whose model answer is plain text — no tool call, empty
  ;; Plan ledger — is a legal §2.5 stop.  No Nudge call is created, the
  ;; turn history records `:plain-response`, and the session can idle.
  (let [requests (atom [])
        gateway {:start! (fn [request deliver!]
                           (swap! requests conj request)
                           (deliver! {:event :completed
                                      :text "plain answer"})
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        handle (start-owner requests {:model-gateway gateway})]
    (try
      (command! handle "c1" {:kind :send-message :text "hello"})
      (is (wait-for #(seq (:turn-history (state handle))) 5000))
      (let [owner-state (state handle)]
        (is (= 1 (count @requests))
            "a plain answer dispatches exactly one model request")
        (is (empty? (nudge-calls owner-state))
            "no Nudge call is created for a plain answer")
        (is (= :plain-response
               (get-in owner-state [:turn-history 0 :exit :kind])))
        (is (nil? (:turn owner-state))
            "the committed exit clears the turn"))
      (finally
        ((:stop! handle))))))

(deftest pending-session-binding-applies-to-the-next-user-turn
  (let [requests (atom [])
        session-id "nudge-owner-pending-binding"
        persistence (ports/memory-persistence)
        created (sessions/new-session
                 {:session-id session-id
                  :owner-id "nudge-owner"
                  :now 1000
                  :id-source (ids/counter-source {:prefix "nudge"})})
        _ (is (:ok? created) (pr-str created))
        initial-session
        (-> (:session created)
            (assoc :model-binding
                   {:connection-id "alpha"
                    :model-entry-id "same-model"
                    :capabilities #{:tools}}
                   :pending-model-binding
                   {:binding
                    {:connection-id "beta"
                     :model-entry-id "same-model"
                     :capabilities #{:tools}}
                    :mode :next-round}))
        committed (sessions/commit-session! persistence initial-session)
        _ (is (= :committed (:status committed)) (pr-str committed))
        handle (start-owner requests
                            {:session-id session-id
                             :initial-session (:session committed)
                             :session-port
                             (sessions/session-port
                              {:persistence persistence})})]
    (try
      (command! handle "c1"
                {:kind :send-message :text "hello"})
      (is (wait-for #(= 1 (count @requests)) 3000))
      (is (= "beta" (get-in (first @requests) [:snapshot :connection]))
          "the next user turn promotes the pending selection")
      (is (= "beta"
             (get-in (state handle) [:session :model-binding :connection-id])))
      (is (nil? (get-in (state handle) [:session :pending-model-binding])))
      (finally
        ((:stop! handle))))))

(deftest plan-s1-ask-suppresses-real-nudge
  (let [requests (atom [])
        handle (start-owner requests {})]
    (try
      (command! handle "ask"
                {:kind :review/submit
                 :lane :plan
                 :tenure-id "tenure-1"
                 :findings []})
      (is (wait-for #(true? (get-in (state handle)
                                    [:mission :relay :lanes :plan
                                     :awaiting-user?]))
                    3000))
      (turn-ended! handle :plan "tenure-1" :plan)
      (is (wait-for #(= 1 (count (:turn-history (state handle)))) 3000))
      (let [state (state handle)]
        (is (empty? (nudge-calls state)))
        (is (empty? @requests))
        (is (= :plan-s1-ask (get-in (last-history state) [:exit :kind])))
        (is (nil? (:turn state))))
      (finally
        ((:stop! handle))))))

(deftest reviewer-turn-end-dispatches-central-nudge
  (let [requests (atom [])
        handle (start-owner requests
                            {:plan-milestone :pending
                             :draft {:path "plans/nudge.md"
                                     :content "Draft body."}})]
    (try
      (let [tenure-id (start-execution! handle)]
        (turn-ended! handle :reviewer tenure-id :reviewer)
        (is (wait-for #(= 1 (count (nudge-calls (state handle)))) 3000))
        (is (wait-for #(= 1 (count @requests)) 3000))
        (let [state (state handle)
              call (first (nudge-calls state))
              nudge (get-in state [:nudges (:nudge-id call)])]
          (is (= :reviewer (:role call)))
          (is (= :reviewer (:agent-id call)))
          (is (= tenure-id (:tenure-id call)))
          (is (= (str "nudge-reviewer-" tenure-id "-1")
                 (:nudge-id call)))
          (is (= :central (:kind nudge)))
          (is (re-find #"尚未完成评审"
                       (get-in call [:request :snapshot :messages 0 :text])))
          (is (false? (get-in state [:turn :ended?]))
              "the Nudge call owns a live turn")
          (is (= (:call-id call) (get-in state [:turn :call-id])))))
      (finally
        ((:stop! handle))))))

(deftest execution-initial-ask-suppresses-real-nudge
  (let [requests (atom [])
        handle (start-owner requests
                            {:plan-milestone :pending
                             :draft {:path "plans/nudge.md"
                                     :content "Draft body."}})]
    (try
      (let [tenure-id (start-execution! handle)]
        (command! handle "initial-empty"
                  {:kind :review/submit
                   :lane :execution
                   :tenure-id tenure-id
                   :findings []})
        (is (wait-for #(true? (get-in (state handle)
                                      [:mission :relay :lanes :execution
                                       tenure-id :awaiting-user?]))
                      3000))
        (turn-ended! handle :reviewer tenure-id :reviewer)
        (is (wait-for #(= 1 (count (:turn-history (state handle)))) 3000))
        (let [state (state handle)]
          (is (empty? (nudge-calls state)))
          (is (empty? @requests))
          (is (= :execution-initial-ask
                 (get-in (last-history state) [:exit :kind])))
          (is (nil? (:turn state)))))
      (finally
        ((:stop! handle))))))

(deftest plan-handoff-suppresses-real-nudge
  (let [requests (atom [])
        handle (start-owner requests
                            {:plan-milestone :pending
                             :draft {:path "plans/nudge.md"
                                     :content "Draft body."}})]
    (try
      (start-execution! handle)
      (turn-ended! handle :plan "tenure-1" :plan)
      (is (wait-for #(= 1 (count (:turn-history (state handle)))) 3000))
      (let [state (state handle)]
        (is (empty? (nudge-calls state)))
        (is (empty? @requests))
        (is (= :handoff-complete
               (get-in (last-history state) [:exit :kind])))
        (is (nil? (:turn state))))
      (finally
        ((:stop! handle))))))

(deftest worker-awaiting-user-suppresses-real-nudge
  (let [requests (atom [])
        handle (start-owner requests
                            {:plan-milestone :pending
                             :draft {:path "plans/nudge.md"
                                     :content "Draft body."}})]
    (try
      (let [tenure-id (start-execution! handle)]
        (command! handle "initial-empty"
                  {:kind :review/submit
                   :lane :execution
                   :tenure-id tenure-id
                   :findings []})
        (is (wait-for #(true? (get-in (state handle)
                                      [:mission :relay :lanes :execution
                                       tenure-id :awaiting-user?]))
                      3000))
        (turn-ended! handle :worker tenure-id :worker-1)
        (is (wait-for #(= 1 (count (:turn-history (state handle)))) 3000))
        (let [state (state handle)]
          (is (empty? (nudge-calls state)))
          (is (empty? @requests))
          (is (= :execution-initial-ask
                 (get-in (last-history state) [:exit :kind])))
          (is (nil? (:turn state)))))
      (finally
        ((:stop! handle))))))

(deftest reviewer-swarm-turn-end-suppresses-nudge
  (let [requests (atom [])
        handle (start-owner requests
                            {:plan-milestone :pending
                             :draft {:path "plans/nudge.md"
                                     :content "Draft body."}})]
    (try
      (let [tenure-id (start-execution! handle)]
        (start-swarm! handle tenure-id)
        (turn-ended! handle :reviewer tenure-id :reviewer)
        (is (wait-for #(= 1 (count (:turn-history (state handle)))) 3000))
        (let [state (state handle)]
          (is (empty? (nudge-calls state)))
          (is (empty? @requests))
          (is (= :reviewer-suspended-in-swarm
                 (get-in (last-history state) [:reason])))
          (is (nil? (:turn state)))))
      (finally
        ((:stop! handle))))))

(deftest worker-turn-end-dispatches-responsibility-nudge
  (let [requests (atom [])
        handle (start-owner requests
                            {:plan-milestone :pending
                             :draft {:path "plans/nudge.md"
                                     :content "Draft body."}})]
    (try
      (let [tenure-id (start-execution! handle)
            _ (start-swarm! handle tenure-id)
            worker-id (register-worker! handle tenure-id "slot-a")
            task-id (claim-root! handle tenure-id worker-id)]
        (turn-ended! handle :worker tenure-id worker-id)
        (is (wait-for #(= 1 (count (nudge-calls (state handle)))) 3000))
        (is (wait-for #(= 1 (count @requests)) 3000))
        (let [state (state handle)
              call (first (nudge-calls state))
              nudge (get-in state [:nudges (:nudge-id call)])
              text (get-in call [:request :snapshot :messages 0 :text])]
          (is (= :worker (:role call)))
          (is (= worker-id (:agent-id call)))
          (is (= tenure-id (:tenure-id call)))
          (is (= (str "nudge-worker-" tenure-id "-"
                      (if (keyword? worker-id) (name worker-id) worker-id)
                      "-1")
                 (:nudge-id call)))
          (is (= :worker (:kind nudge)))
          (is (some #{task-id} (:responsibility nudge)))
          (is (= :task (get-in call [:request :snapshot :role])))
          (is (= :task (get-in call [:request :snapshot :model-role])))
          (is (= worker-id (get-in call [:request :snapshot :agent-id])))
          (is (map? (get-in call [:request :snapshot :board-snapshot])))
          (is (re-find #"全局仍有可推进工作" text))
          (is (not (re-find #"review 账本" text)))
          (is (false? (get-in state [:turn :ended?]))
              "the Nudge call owns a live turn")
          (is (= (:call-id call) (get-in state [:turn :call-id])))))
      (finally
        ((:stop! handle))))))

(deftest actor-owned-turn-is-left-to-wake-actors
  (let [base (state/initial-state
              {:session-id "actor-turn-owner"
               :models {:main "main-model" :task "task-model"}})
        [base call] (state/create-call
                     base
                     {:kind :model
                      :role :worker
                      :lane :execution
                      :tenure-id "tenure-1"
                      :agent-id :worker-1
                      :actor-attempt-id "actor-a1"
                      :request {:snapshot {}}})
        owner-state (assoc base :turn {:ended? true
                                       :role :worker
                                       :agent-id :worker-1
                                       :tenure-id "tenure-1"
                                       :call-id (:call-id call)})
        guard (guards/settle-turn-guard)]
    (is (nil? ((:enabled? guard) owner-state {:now 1}))
        "wake-actors owns the Phase B worker turn; settle-turn must not duplicate it")
    (is (state/actor-call? owner-state (:call-id call)))))

(deftest retry-wait-model-call-defers-nudge
  (let [base (state/initial-state
              {:session-id "retry-wait-race"
               :models {:main "main-model" :task "task-model"}
               :plan-milestone :pending
               :draft {:path "plans/retry.md" :content "Retry plan"}})
        handoff (mission/apply-command
                 (:mission base)
                 {:op :review :lane :plan :tenure-id "tenure-1"
                  :call-id "retry-handoff" :findings []})
        owner-state (state/with-mission base (:state handoff))
        tenure-id (-> (:state handoff) :relay :lanes :execution keys first)
        owner-state (-> owner-state
                        (assoc :turn {:ended? true
                                      :role :worker
                                      :agent-id :worker-1
                                      :tenure-id tenure-id})
                        (assoc-in [:calls "retry-call"]
                                  {:call-id "retry-call"
                                   :kind :model
                                   :state :retry-wait
                                   :agent-id :worker-1
                                   :tenure-id tenure-id}))
        guard (guards/settle-turn-guard)
        ctx {:now 123}
        work ((:enabled? guard) owner-state ctx)]
    (is (nil? work)
        "a live retry-wait call disables settle-turn")
    (is (true? (state/live-model-for-agent? owner-state :worker-1)))
    (is (true? (get-in owner-state [:turn :ended?]))
        "the turn stays pending while the retry is live")
    (is (empty? (filter :nudge? (vals (:calls owner-state)))))
    (is (empty? (:turn-history owner-state))
        "settle does not record a decision while the retry is live")))

(deftest pending-user-turn-preempts-stale-nudge
  (let [base (state/initial-state
              {:session-id "pending-user-turn"
               :models {:main "main-model" :task "task-model"}})
        pending {:role :plan
                 :agent-id :plan
                 :text "answer the new question"
                 :command-id "cmd-user-1"
                 :at 1
                 :turn-card-ids ["card-1"]
                 :instruction-values {"card-1" "value-1"}}
        owner-state (-> base
                        (assoc :pending-turns [pending])
                        (assoc :turn {:ended? true
                                      :role :plan
                                      :agent-id :plan}))
        settle (guards/settle-turn-guard)
        open (guards/open-user-turn-guard)
        settle-work ((:enabled? settle) owner-state {:now 2})
        open-work ((:enabled? open) owner-state {:now 2})
        prepared ((:prepare open) owner-state {:now 2} open-work
                  :open-user-turn-attempt)
        call (first (vals (:calls (:state prepared))))
        request (:request call)]
    (is (nil? settle-work)
        "a pending user turn must suppress a stale Nudge")
    (is (= :plan (:role open-work)))
    (is (true? (:user-turn? request)))
    (is (= "cmd-user-1" (:turn-command-id request)))
    (is (= ["card-1"] (:turn-card-ids request)))
    (is (= {"card-1" "value-1"} (:instruction-values request)))))

(deftest real-normalized-snapshot-failure-blocks-nudge
  (let [requests (atom [])
        gateway {:start! (fn [request deliver!]
                           (swap! requests conj request)
                           (when (= 1 (count @requests))
                             (deliver!
                              {:event :failed
                               :error {:kind :unknown-field
                                       :reason :unknown-field
                                       :path [:messages 0]
                                       :unknown [:quotes]
                                       :category :unclassified
                                       :disposition :fail
                                       :retryable? false}}))
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        handle (start-owner requests {:model-gateway gateway})]
    (try
      (command! handle "c1" {:kind :send-message :text "hello"})
      (is (wait-for
           #(some (fn [diagnostic]
                    (= :NUDGE_BLOCKED_PERMANENT_FAILURE
                       (:code diagnostic)))
                  (:diagnostics (state handle)))
           3000))
      (let [owner-state (state handle)
            blocked (some #(when (= :NUDGE_BLOCKED_PERMANENT_FAILURE
                                    (:code %))
                             %)
                          (:diagnostics owner-state))
            outbox-types (set (map :type (:outbox owner-state)))]
        (is (= :unknown-field (get-in blocked [:failure :kind])))
        (is (= [:messages 0] (get-in blocked [:failure :path])))
        (is (= :blocked (get-in (last (:turn-history owner-state))
                                [:reason])))
        (is (contains? outbox-types :owner/blocked))
        (is (empty? (nudge-calls owner-state))
            "a permanent failure must not create a Nudge call")
        (is (= 1 (count @requests))
            "one failed model call, no retry/Nudge loop")
        (is (nil? (:turn owner-state))))
      (finally
        ((:stop! handle))))))

(deftest ordinary-natural-stops-keep-nudging
  (let [requests (atom [])
        {:keys [gateway releases]}
        (gated-model-gateway requests)
        handle (start-owner requests {:model-gateway gateway})]
    (try
      (command! handle "c1" {:kind :send-message :text "hello"})
      ;; Release the held request, then wait for the Nudge call it produced
      ;; to own a live turn before releasing the next one.  Every turn
      ;; invokes the Scope-exempt `negotiate` member, so each settled turn
      ;; is task-cycle work that still owes a Nudge; the budget, not a
      ;; plain stop, ends the chain.  Four rounds leave the fourth Nudge
      ;; request held, so its live turn is what the final assertions see.
      (dotimes [n 4]
        (is (wait-for #(<= (inc n) (count @requests)) 5000)
            (str "request " (inc n) " was dispatched"))
        (let [deliver! (nth @releases n)]
          (deliver! {:event :tool-call
                     :call-id (str "negotiate-" (inc n))
                     :name "negotiate"
                     :payload {}})
          (deliver! {:event :completed :text "still here"}))
        (when (< n 3)
          (is (wait-for (fn []
                          (let [s (state handle)
                                live (some (fn [[_ call]]
                                             (when (and (:nudge? call)
                                                        (contains? #{:ready
                                                                     :dispatching
                                                                     :running
                                                                     :retry-wait}
                                                                    (:state call)))
                                               call))
                                           (:calls s))]
                            (and (some? live)
                                 (false? (get-in s [:turn :ended?]))
                                 (= (:call-id live)
                                    (get-in s [:turn :call-id])))))
                        5000)
              (str "round " (inc n) " produced a live Nudge call"))))
      (let [owner-state (state handle)]
        (is (>= (count (nudge-calls owner-state)) 3))
        (is (not-any? #(= :NUDGE_BUDGET_EXHAUSTED (:code %))
                      (:diagnostics owner-state))
            "ordinary natural stops are not failure-budgeted")
        (is (false? (get-in owner-state [:turn :ended?]))
            "the held Nudge call owns a live turn"))
      (finally
        ((:stop! handle))))))

(defn- settled-turn-owner
  "One ended Plan turn whose last call is a dispatched Nudge."
  [extra]
  (let [base (state/initial-state
              {:session-id "settle-gate"
               :models {:main "main-model" :task "task-model"}})
        [base call] (state/create-call
                     base
                     {:call-id "nudge-1"
                      :kind :model
                      :role :plan
                      :lane :plan
                      :tenure-id "tenure-1"
                      :agent-id :plan
                      :nudge-id "nudge-plan-tenure-1-1"
                      :created-at 1})
        base (-> base
                 (assoc :nudge-seq 1)
                 (assoc-in [:calls (:call-id call) :nudge?] true))
        base (-> (state/terminal-call base (state/call base (:call-id call))
                                      :succeeded {:text "still working"} nil 2)
                 (assoc :turn {:role :plan
                               :agent-id :plan
                               :tenure-id "tenure-1"
                               :call-id "nudge-1"
                               :ended? true
                               :at 3}))]
    (merge base extra)))

(defn- settle-preparation
  [owner-state]
  (let [guard (guards/settle-turn-guard)
        ctx {:now 4}
        work ((:enabled? guard) owner-state ctx)]
    (when work
      ((:prepare guard) owner-state ctx work :settle-attempt))))

(deftest settled-nudge-turn-still-owes-continuation
  ;; §2.5 lists three legal exits.  "My last call was a Nudge" is not one:
  ;; the Nudge turn ended naturally, so the unresolved Plan tenure must keep
  ;; the prompt to continue.  The delivered Nudge moved `:nudge-seq`, so the
  ;; next candidate is a distinct call rather than a replay.
  (let [owner-state (settled-turn-owner {})
        prepared (settle-preparation owner-state)
        calls (filter :nudge? (vals (:calls (:state prepared))))]
    (is (some? prepared)
        "a settled Nudge turn must not be exempt from the settle decision")
    (is (= 2 (count calls)))
    (is (= 2 (:nudge-seq (:state prepared))))
    (is (not= "nudge-plan-tenure-1-1"
              (:nudge-id (first (remove #(= "nudge-1" (:call-id %)) calls)))))))

(deftest operator-cancel-stops-dispatch-without-nudging
  ;; An operator stop is not a natural stop, so it owes no Nudge.  Admission
  ;; is already closed by the walk; the turn is still recorded, so the
  ;; history says why nothing was dispatched.
  (let [owner-state (settled-turn-owner {})
        run-id (get-in owner-state [:cancel :active-run-id])
        cancelled (assoc-in owner-state [:cancel :nodes run-id :state]
                            :cancelled)
        prepared (settle-preparation cancelled)]
    (is (some? prepared))
    (is (nil? (:turn (:state prepared))))
    (is (= :admission-closed (get-in (:state prepared)
                                     [:turn-history 0 :reason]))
        "a finished run closes dispatch whatever its admission flag says")
    ;; `:enabled?` in the history records the policy's own decision, which
    ;; still asks for a Nudge; the stop is a dispatch gate, so what must not
    ;; change is the Nudge call count below.
    (is (= 1 (count (filter :nudge? (vals (:calls (:state prepared))))))
        "a stopped run dispatches no Nudge call")))

(deftest cancelled-nudge-leaves-no-unended-turn
  ;; The E2E real-model stop path exposed this: the operator cancels a Nudge
  ;; attempt, the call becomes terminal, but the turn was never marked
  ;; ended.  The run is already :cancelled, yet the snapshot kept reporting
  ;; an unattended active turn.  A terminal call under a stopped run must be
  ;; settleable and cleared.
  (let [owner-state (settled-turn-owner {})
        run-id (get-in owner-state [:cancel :active-run-id])
        cancelled (-> owner-state
                      (assoc-in [:cancel :nodes run-id :state] :cancelled)
                      (assoc-in [:calls "nudge-1" :state] :cancelled)
                      (assoc-in [:turn :ended?] false))
        prepared (settle-preparation cancelled)]
    (is (some? prepared)
        "a terminal call under a stopped run must settle")
    (is (nil? (:turn (:state prepared))))
    (is (= :admission-closed (get-in (:state prepared)
                                     [:turn-history 0 :reason])))))

(deftest cancelling-run-records-the-operator-stop
  (let [owner-state (settled-turn-owner {})
        run-id (get-in owner-state [:cancel :active-run-id])
        cancelling (assoc-in owner-state [:cancel :nodes run-id :state]
                             :cancelling)
        prepared (settle-preparation cancelling)]
    (is (some? prepared))
    (is (nil? (:turn (:state prepared))))
    (is (= :operator-cancelled (get-in (:state prepared)
                                       [:turn-history 0 :reason]))
        "a cancelling run records the operator stop, not a closed admission")
    ;; `:enabled?` in the history records the policy's own decision, which
    ;; still asks for a Nudge; the stop is a dispatch gate, so what must not
    ;; change is the Nudge call count below.
    (is (= 1 (count (filter :nudge? (vals (:calls (:state prepared)))))))))

(deftest busy-turn-settles-when-the-agent-is-free
  ;; A turn that ends while the agent still owns a call cannot be settled in
  ;; that window; it must be settleable once the call is gone, or the slot
  ;; stays stopped with the work still owing (§2.5).
  (let [base (state/initial-state
              {:session-id "busy-turn"
               :models {:main "main-model" :task "task-model"}})
        busy (-> base
                 (assoc :turn {:ended? true
                               :role :plan
                               :agent-id :plan
                               :tenure-id "tenure-1"})
                 (assoc-in [:calls "busy-call"]
                           {:call-id "busy-call"
                            :kind :model
                            :state :running
                            :agent-id :plan
                            :tenure-id "tenure-1"}))
        guard (guards/settle-turn-guard)]
    (is (nil? ((:enabled? guard) busy {:now 6}))
        "a live call for the agent defers settlement")
    (is (some? ((:enabled? guard)
                (assoc-in busy [:calls "busy-call" :state] :succeeded)
                {:now 6}))
        "the same turn settles once the agent is free")))

(deftest non-enabled-decision-records-its-answer-once
  ;; An exit or a suspended lane is a committed answer.  `:prepare` records
  ;; it in `:turn-history` and clears the turn, so a second pass finds no
  ;; turn to answer and can neither repeat the answer nor turn it into a
  ;; Nudge.
  (let [base (state/initial-state
              {:session-id "settle-once"
               :models {:main "main-model" :task "task-model"}})
        awaiting (assoc-in base [:mission :relay :lanes :plan :awaiting-user?]
                           true)
        owner-state (-> awaiting
                        (assoc :turn {:ended? true
                                      :role :plan
                                      :agent-id :plan
                                      :tenure-id "tenure-1"}))
        prepared (settle-preparation owner-state)
        settled (:state prepared)
        again (settle-preparation settled)]
    (is (some? prepared))
    ;; The empty Plan ledger at `:S1` makes this a real ask, and `S1` is the
    ;; legal S1 ask exit, not the generic user wait.
    (is (= :plan-s1-ask (get-in settled [:turn-history 0 :exit :kind])))
    (is (nil? (:turn settled))
        "a committed answer clears the turn instead of leaving it stale")
    (is (nil? again)
        "the cleared turn is not answered a second time")
    (is (= 1 (count (:turn-history settled)))
        "one answer, one history entry")
    (is (empty? (nudge-calls settled)))))

(deftest round-boundary-stops-nudging-after-the-budget
  ;; D-13: an unresolved Plan ledger keeps §2.5 asking for a Nudge, but the
  ;; host round budget is a hard stop.  After `:nudge-round-budget`
  ;; consecutive Nudge calls the settle records `:round-boundary`, clears
  ;; the turn, and creates no further call.
  (let [budget 3
        ;; `:nudge-rounds` is the committed count the observation reads;
        ;; the fixture's ended turn already consumed `budget` rounds.
        owner-state (settled-turn-owner
                     {:config {:nudge-round-budget budget}
                      :nudge-rounds {["tenure-1" :plan] budget}})
        prepared (settle-preparation owner-state)
        settled (:state prepared)]
    (is (some? prepared))
    (is (= :round-boundary
           (get-in settled [:turn-history 0 :reason])))
    (is (= budget (get-in settled [:turn-history 0 :round-seq])))
    (is (nil? (:turn settled))
        "the boundary clears the turn like every other committed answer")
    (is (= 1 (count (nudge-calls settled)))
        "no new Nudge call is created at the boundary")
    (is (not (contains? (:nudge-rounds settled) ["tenure-1" :plan]))
        "the boundary resets the consumed count for the next judgement")))

(deftest round-boundary-resets-and-level-triggers-again
  ;; After a boundary the count restarts: the next ended turn is judged
  ;; fresh, and a full budget of Nudge calls can run again before the next
  ;; boundary.  Suppression is not one-shot; neither is the boundary.
  (let [budget 2
        base (settled-turn-owner
              {:config {:nudge-round-budget budget}
               :nudge-rounds {["tenure-1" :plan] budget}})
        settled (:state (settle-preparation base))
        ;; A fresh ended turn after the boundary sees `round-seq` 0 again.
        next-state (assoc settled
                          :turn {:role :plan
                                 :agent-id :plan
                                 :tenure-id "tenure-1"
                                 :ended? true
                                 :at 9})
        guard (guards/settle-turn-guard)
        work ((:enabled? guard) next-state {:now 10})
        plan (when work ((:prepare guard) next-state {:now 10} work
                         :settle-attempt-2))
        next-state' (:state plan)]
    (is (some? work)
        "a later ended turn is judged again after the boundary")
    (is (= 2 (count (nudge-calls next-state')))
        "the fresh judgement creates one more Nudge call")
    (is (= 1 (get-in next-state' [:nudge-rounds ["tenure-1" :plan]]))
        "the count restarts at one for the new round")))

(defn- cancelling-owner-with-live-shell
  "An Owner mid-Esc: the run is cancelling, and one managed Shell call was
  signalled but its exit has not been observed, so it still owns its process.

  This is the shape `runtime-test` observes after a real `:stop-run`, built
  here as committed state so the guard can be driven deterministically."
  []
  (let [base (state/initial-state
              {:session-id "cancel-unknown"
               :models {:main "main-model" :task "task-model"}})
        run-id (get-in base [:cancel :active-run-id])
        call-id "shell-1"
        attempt-id "a1"
        base (-> base
                 (assoc-in [:cancel :nodes run-id :state] :cancelling)
                 (assoc-in [:cancel :nodes run-id :admission] :closed)
                 (assoc-in [:calls call-id]
                           {:call-id call-id
                            :kind :shell
                            :agent-id :agent-x
                            :state :dispatching
                            :attempt-id attempt-id})
                 (update :cancel cancel/add-node
                         {:node-id call-id
                          :kind :call
                          :parent-id run-id
                          :state :dispatching
                          :side-effecting? true
                          :resource-alive? true})
                 (update :cancel cancel/add-node
                         {:node-id (state/attempt-node-id call-id attempt-id)
                          :kind :attempt
                          :parent-id call-id
                          :state :dispatching
                          :attempt-id attempt-id
                          :side-effecting? true
                          :resource-alive? true}))]
    {:state base :run-id run-id :call-id call-id :attempt-id attempt-id}))

(defn- accept-terminal!
  [owner-state call-id attempt-id status]
  (let [guard (guards/accept-call-terminal-guard)
        terminal {:type :call/terminal
                  :call-id call-id
                  :attempt-id attempt-id
                  :status status
                  :error {:kind :unknown}}
        state (update owner-state :terminals conj terminal)]
    (:state ((:prepare guard) state {:now 5}
             ((:enabled? guard) state {:now 5})
             :attempt-1))))

(deftest unknown-terminal-keeps-the-cancellation-domain-open
  ;; A stop signal is not an observation that the process ended.  An accepted
  ;; `:outcome-unknown` must leave the Shell's resource live, so `finish-run`
  ;; keeps refusing and the successor run cannot open while that process may
  ;; still act.
  (let [{:keys [state run-id call-id attempt-id]}
        (cancelling-owner-with-live-shell)
        after (accept-terminal! state call-id attempt-id :outcome-unknown)]
    (is (= :outcome-unknown (get-in after [:calls call-id :state]))
        "the call records the unknown outcome")
    (is (true? (get-in after [:cancel :nodes call-id :resource-alive?]))
        "the call node still owns its live Shell")
    (is (true? (get-in after [:cancel :nodes
                              (state/attempt-node-id call-id attempt-id)
                              :resource-alive?]))
        "the attempt node still owns its live Shell")
    (is (false? (cancel/cancellation-quiescent? (:cancel after) run-id))
        "an unknown outcome does not prove the resource ended")
    (is (= [call-id (state/attempt-node-id call-id attempt-id)]
           (cancel/active-resources (:cancel after) run-id))
        "both nodes are still counted as live")
    (let [closed (cancel/finish-run (:cancel after)
                                    {:run-id run-id :at 6})]
      (is (false? (:ok? closed))
          "the cancel layer refuses to close a live domain")
      (is (= :RESOURCES_NOT_QUIESCENT (get-in closed [:error :code])))
      (is (= :cancelling (get-in (:state closed)
                                 [:nodes run-id :state]))
          "the run stays cancelling until a real terminal arrives"))))

(deftest settling-terminal-does-release-the-resource
  ;; The counterpart: a real terminal is the observation that ends the
  ;; resource, so the same domain becomes closable.
  (let [{:keys [state run-id call-id attempt-id]}
        (cancelling-owner-with-live-shell)
        after (accept-terminal! state call-id attempt-id :cancelled)]
    (is (false? (get-in after [:cancel :nodes call-id :resource-alive?]))
        "a settlement observation ends the call resource")
    (is (false? (get-in after [:cancel :nodes
                               (state/attempt-node-id call-id attempt-id)
                               :resource-alive?]))
        "a settlement observation ends the attempt resource")
    (is (cancel/cancellation-quiescent? (:cancel after) run-id)
        "the domain is quiescent once the process reported its exit")
    (is (:ok? (cancel/finish-run (:cancel after) {:run-id run-id :at 6}))
        "a quiescent domain can be closed")
    (is (= :cancelled (get-in (cancel/finish-run
                               (:cancel after) {:run-id run-id :at 6})
                              [:state :nodes run-id :state])))))

(deftest deferred-settle-keeps-the-turn-for-a-later-decision
  ;; A turn deferred by live work must stay eligible: once that work ends the
  ;; same stop is judged again.  A "settled" marker would strand it forever.
  (let [base (state/initial-state
              {:session-id "deferred-turn"
               :models {:main "main-model" :task "task-model"}})
        busy (-> base
                 (assoc :turn {:ended? true
                               :role :plan
                               :agent-id :plan
                               :tenure-id "tenure-1"})
                 (assoc-in [:calls "busy-call"]
                           {:call-id "busy-call"
                            :kind :model
                            :state :running
                            :agent-id :plan
                            :tenure-id "tenure-1"}))
        guard (guards/settle-turn-guard)
        ctx {:now 7}
        ;; Simulate the deferred commit the guard performs when the policy
        ;; reports `:deferred?` but the agent was still free at commit time.
        deferred (-> busy
                     (update :turn assoc :deferred-at 7))
        free (assoc-in busy [:calls "busy-call" :state] :succeeded)]
    (is (nil? ((:enabled? guard) busy ctx))
        "a live call defers settlement")
    (is (nil? (get-in deferred [:turn :settled?]))
        "the deferred record must not carry a disabling marker")
    (is (some? ((:enabled? guard) (assoc-in deferred
                                            [:calls "busy-call" :state]
                                            :succeeded)
                ctx))
        "once the deferring call ends the turn is judged again")
    (let [prepared (:state ((:prepare guard) free ctx
                            ((:enabled? guard) free ctx) :attempt-2))]
      (is (some? prepared))
      (is (= 1 (count (filter :nudge? (vals (:calls prepared)))))
          "the stop is finally decided with a real Nudge call"))))

(defn- admission-refusal-gateway
  "Emit a devops tool call without :scope, then a model completion."
  [requests]
  {:start! (fn [request deliver!]
             (let [n (swap! requests inc)]
               (deliver! {:event :tool-call
                          :call-id (str "devops-" n)
                          :name "devops"
                          :payload {:verbose-intent "search the repo"}})
               (deliver! {:event :completed :text "refused tool"}))
             {:attempt-id (:attempt-id request)})
   :cancel! (fn [_] nil)})

(defn- admission-refusal-once-gateway
  "Emit one refusal, then let later turns complete without a tool call."
  [requests]
  {:start! (fn [request deliver!]
             (let [n (swap! requests inc)]
               (if (= 1 n)
                 (do
                   (deliver! {:event :tool-call
                              :call-id "devops-1"
                              :name "devops"
                              :payload {:verbose-intent "search the repo"}})
                   (deliver! {:event :completed :text "refused tool"}))
                 (deliver! {:event :completed :text "no tool"})))
             {:attempt-id (:attempt-id request)})
   :cancel! (fn [_] nil)})

(deftest admission-refusal-injects-one-nudge
  (let [requests (atom 0)
        handle (start-owner requests
                            {:model-gateway
                             (admission-refusal-once-gateway requests)})]
    (try
      (command! handle "c1" {:kind :send-message :text "search"})
      (is (wait-for #(seq (nudge-calls (state handle))) 5000)
          "a refused tool call must not hang the turn")
      (let [owner-state (state handle)
            nudge (first (nudge-calls owner-state))
            messages (get-in nudge [:request :snapshot :messages])
            refusal (some #(when (and (= :tool (:role %))
                                      (re-find #"SCOPE_ANNOTATION_MISSING"
                                               (str (:text %))))
                            %)
                          (:conversation owner-state))]
        (is (some? refusal)
            "the refusal is a visible tool result")
        (is (some #(re-find #"SCOPE_ANNOTATION_MISSING" (str (:text %)))
                  messages)
            "the Nudge request carries the refusal so the model can correct it")
        (is (re-find #"Scope 提醒" (str (:text (last messages))))
            "the Nudge restates that :scope lives inside the tool parameters")
        (testing "the refusal tells the model the expected shape"
          (let [text (str (:text refusal))]
            (is (re-find #":expected" text)
                "the refusal carries the expected/allowed annotation shape")
            (is (re-find #"reset-scope" text))
            (is (re-find #"new-scope-goal" text))
            (is (re-find #"new-scope-retention-budget" text))
            (is (re-find #":hint" text)
                "the refusal tells the model how to correct the call")))
        (is (= 1 (get-in owner-state
                         [:tool-refusals ["tenure-1" :plan "devops"] :count]))
            "one refusal is counted once")
        (is (seq (nudge-calls owner-state))
            "one refusal injects a Nudge"))
      (finally
        ((:stop! handle))))))

(deftest repeated-admission-refusals-end-in-a-diagnostic
  (let [requests (atom 0)
        handle (start-owner requests
                            {:model-gateway (admission-refusal-gateway requests)})]
    (try
      (command! handle "c1" {:kind :send-message :text "search"})
      (is (wait-for
           #(some (fn [diagnostic]
                    (= :TOOL_ADMISSION_REFUSED_LIMIT (:code diagnostic)))
                  (:diagnostics (state handle)))
           8000)
          "the refusal loop must end in an explicit diagnostic")
      (let [owner-state (state handle)
            diagnostic (some #(when (= :TOOL_ADMISSION_REFUSED_LIMIT
                                       (:code %))
                                %)
                             (:diagnostics owner-state))
            outbox-types (set (map :type (:outbox owner-state)))]
        (is (= 3 (:count diagnostic)))
        (is (= "devops" (:member diagnostic)))
        (is (= :SCOPE_ANNOTATION_MISSING (:last-code diagnostic)))
        (is (= :fix-request (:remedy diagnostic)))
        (is (re-find #"Add the missing :scope" (:message diagnostic)))
        (is (contains? outbox-types :owner/blocked)
            "the user sees an actionable blocked event")
        (is (nil? (:turn owner-state))
            "the blocked turn is terminal, not left pending")
        (is (= 2 (count (nudge-calls owner-state)))
            "only the first two refusals Nudge; the third blocks")
        (is (= 3 @requests)
            "no fourth model request is started after the block"))
      (finally
        ((:stop! handle))))))

(deftest wake-actors-blocks-after-three-refusals
  (let [base (state/initial-state
              {:session-id "actor-refusal-limit"
               :models {:main "main-model" :task "task-model"}
               :plan-milestone :pending
               :draft {:path "plans/actor.md" :content "Draft"}})
        handoff (mission/apply-command
                 (:mission base)
                 {:op :review :lane :plan :tenure-id "tenure-1"
                  :call-id "handoff" :findings []})
        base (state/with-mission base (:state handoff))
        tenure-id (-> (:state handoff) :relay :lanes :execution keys first)
        swarm (mission/apply-command
               (:mission base)
               {:op :review :lane :execution :tenure-id tenure-id
                :call-id "swarm" :findings [{:key "work" :value "Do"}]})
        base (state/with-mission base (:state swarm))
        synced (actors/sync-tenure (:mission base) (:actors base) tenure-id)
        base (-> base
                 (state/with-mission (:mission synced))
                 (assoc :actors (:actors synced)))
        slots (get-in base [:actors :tenures tenure-id :slots])
        blocked-agent (:agent-id (get slots :slot-a))
        other-agent (:agent-id (get slots :slot-b))
        member "clj-worker"
        key [tenure-id blocked-agent member]
        state (-> base
                  ;; Two refusals for another member do not block.
                  (assoc-in [:tool-refusals [tenure-id blocked-agent "shell"]]
                            {:count 2
                             :last-code :SCOPE_ANNOTATION_MISSING
                             :member "shell"
                             :tenure-id tenure-id
                             :agent-id blocked-agent
                             :at 9})
                  (assoc-in [:tool-refusals key]
                            {:count 3
                             :last-code :SCOPE_ANNOTATION_MISSING
                             :member member
                             :tenure-id tenure-id
                             :agent-id blocked-agent
                             :at 10}))
        guard (guards/wake-actors-guard)
        ctx {:now 20}
        actor-calls (fn [s]
                      (filter #(and (= :model (:kind %))
                                    (:actor-attempt-id %))
                              (vals (:calls s))))
        by-agent (fn [s] (group-by :agent-id (actor-calls s)))
        work ((:enabled? guard) state ctx)
        prepared (:state ((:prepare guard) state ctx work :attempt-1))]
    (is (some? work)
        "the guard still runs for the unblocked actor")
    (is (empty? (get (by-agent prepared) blocked-agent))
        "the actor at the refusal limit gets no new model call")
    (is (= 1 (count (get (by-agent prepared) other-agent)))
        "the unblocked actor still starts")
    (is (some #(and (= :TOOL_ADMISSION_REFUSED_LIMIT (:code %))
                    (= :wake-actors (:source %))
                    (= blocked-agent (:agent-id %))
                    (= member (:member %))
                    (= 3 (:count %))
                    (= :SCOPE_ANNOTATION_MISSING (:last-code %))
                    (= :fix-request (:remedy %)))
              (:diagnostics prepared))
        "the blocked actor gets one actionable diagnostic")
    (is (some #(= :owner/blocked (:type %)) (:outbox prepared)))
    (is (contains? (:actor-refusal-reported prepared) key))
    (is (nil? ((:enabled? guard) prepared ctx))
        "a reported, blocked idle actor does not spin the guard")
    ;; A successful admission clears the streak and the reported marker.
    (let [cleared (#'handlers/clear-tool-refusal prepared tenure-id
                                                blocked-agent member)
          work2 ((:enabled? guard) cleared ctx)
          prepared2 (:state ((:prepare guard) cleared ctx work2 :attempt-2))]
      (is (some? work2))
      (is (= 1 (count (get (by-agent prepared2) blocked-agent)))
          "the actor wakes again after a successful admission"))))

(deftest operator-cancel-clears-tool-refusal-streaks
  (let [base (settled-turn-owner {})
        key ["tenure-1" :plan "devops"]
        seeded (-> base
                   (assoc :pending-cancels [{:command-id "stop-1"
                                             :origin :operator-stop
                                             :at 1}])
                   (assoc :tool-refusals
                          {key {:count 3
                                :last-code :SCOPE_ANNOTATION_MISSING
                                :member "devops"
                                :tenure-id "tenure-1"
                                :agent-id :plan
                                :at 1}})
                   (assoc :actor-refusal-reported #{key}))
        guard (guards/operator-cancel-guard)
        ctx {:now 10}
        work ((:enabled? guard) seeded ctx)
        prepared (:state ((:prepare guard) seeded ctx work :cancel-attempt))]
    (is (some? work))
    (is (= {} (:tool-refusals prepared))
        "an operator cancel resets the streak")
    (is (= #{} (:actor-refusal-reported prepared)))))

(deftest parked-ask-projects-one-event
  (let [base (state/initial-state
              {:session-id "ask-event"
               :models {:main "main-model" :task "task-model"}})
        [base call] (state/create-call
                     base
                     {:call-id "ask-call"
                      :kind :tool
                      :role :plan
                      :lane :plan
                      :tenure-id "tenure-1"
                      :agent-id :plan
                      :created-at 1})
        parked {:status :succeeded
                :value {:state :parked
                        :payload {:question "E2E-08 请给出一个数字"}
                        :name "ask-e2e-08"
                        :call-id "ask-call"}}
        state (state/terminal-call base call :succeeded parked nil 2)
        guard (guards/project-tool-result-guard)
        ctx {:now 3}
        work ((:enabled? guard) state ctx)
        prepared (:state ((:prepare guard) state ctx work :attempt-1))
        asks (filter #(= :hc/ask (:type %)) (:outbox prepared))]
    (is (some? work))
    (is (= 1 (count asks)))
    (is (= "E2E-08 请给出一个数字" (:question (first asks))))
    (is (= "ask-e2e-08" (:name (first asks))))
    (is (= "ask-ask-call" (:ask-id (first asks))))
    (is (= :plan (:lane (first asks)))
        "the card carries the tenure lane so the answer can name it")
    (is (= "tenure-1" (:tenure-id (first asks)))
        "the card carries the tenure id so the answer can name it")
    (is (true? (get-in prepared [:relay :lanes :plan :awaiting-user?]))
        "the program park marks the relay tenure as awaiting a human")
    (testing "the answer resumes the same parked tenure"
      (let [answered (handlers/apply-message
                      prepared
                      {:type :command
                       :command-id "answer-1"
                       :command-epoch 1
                       :payload {:kind :answer-ask
                                 :text "42"
                                 :lane :plan
                                 :tenure-id "tenure-1"
                                 :ask-id "ask-ask-call"}}
                      {:now 4 :registry (commands/local-registry)})]
        (is (= :accepted (get-in answered [:receipt :status]))
            (pr-str answered))
        (is (= :answer-accepted (get-in answered [:receipt :outcome])))
        (is (false? (get-in answered [:state :relay :lanes :plan
                                      :awaiting-user?]))
            "the parked tenure is resumed, not left waiting")))
    (is (nil? ((:enabled? guard) prepared ctx))
        "a projected result does not emit a second ask event")))

(deftest answer-ask-emits-one-resolved-event
  (let [registry (commands/local-registry)
        base (state/initial-state
              {:session-id "ask-resolved"
               :models {:main "main-model" :task "task-model"}
               :registry registry})
        review (mission/apply-command
                (:mission base)
                {:op :review :lane :plan :tenure-id "tenure-1"
                 :call-id "review-1" :findings []})
        state (-> (state/with-mission base (:state review))
                  ;; The initial empty review records the ask; the real
                  ;; program park sets awaiting-user, which this handler test
                  ;; states explicitly.
                  (assoc-in [:mission :bindings :plan :awaiting-user?] true)
                  (assoc-in [:relay :lanes :plan :awaiting-user?] true))
        result (handlers/apply-message
                state
                {:type :command
                 :command-id "answer-1"
                 :command-epoch 1
                 :payload {:kind :answer-ask
                           :text "42"
                           :lane :plan
                           :tenure-id "tenure-1"
                           :ask-id "ask-1"
                           :name "ask-prog"}}
                {:now 2 :registry registry})
        resolved (filter #(= :hc/ask-resolved (:type %))
                         (get-in result [:state :outbox]))]
    (is (not= :refused (get-in result [:receipt :status])) (pr-str result))
    (is (= :answer-accepted (:outcome (:receipt result))))
    (is (false? (get-in result [:state :mission :relay :lanes :plan
                                :awaiting-user?]))
        "the same parked tenure is resumed, not a second run")
    (is (= 1 (count resolved)))
    (is (= "ask-1" (:ask-id (first resolved))))
    (is (= "ask-prog" (:name (first resolved))))
    (is (= :plan (:lane (first resolved))))
    (is (= "tenure-1" (:tenure-id (first resolved))))))

(defn- parked-state
  [session-id]
  (let [registry (commands/local-registry)
        base (state/initial-state
              {:session-id session-id
               :models {:main "main-model" :task "task-model"}
               :registry registry})
        review (mission/apply-command
                (:mission base)
                {:op :review :lane :plan :tenure-id "tenure-1"
                 :call-id (str session-id "-review") :findings []})]
    (-> (state/with-mission base (:state review))
        (assoc-in [:mission :relay :lanes :plan :awaiting-user?] true)
        (state/project))))

(defn- answer!
  [state payload]
  (handlers/apply-message
   state
   {:type :command
    :command-id (str "answer-" (random-uuid))
    :command-epoch 1
    :payload (merge {:kind :answer-ask :text "42" :ask-id "ask-1"}
                    payload)}
   {:now 2 :registry (commands/local-registry)}))

(deftest answer-ask-infers-the-only-parked-tenure
  (let [state (parked-state "ask-infer")
        result (answer! state {})]
    (is (= :accepted (get-in result [:receipt :status])) (pr-str result))
    (is (= :answer-accepted (:outcome (:receipt result))))
    (is (false? (get-in result [:state :mission :relay :lanes :plan
                                :awaiting-user?])))))

(deftest answer-ask-rejects-a-stale-tenure
  (let [state (parked-state "ask-stale")
        result (answer! state {:lane :plan :tenure-id "tenure-old"})]
    (is (false? (get-in result [:receipt :ok?])))
    (is (= :ASK_TENURE_MISMATCH (get-in result [:receipt :error :code])))
    (is (= "tenure-old" (get-in result [:receipt :error :details :tenure-id])))
    (is (true? (get-in result [:state :mission :relay :lanes :plan
                               :awaiting-user?]))
        "a refused answer leaves the parked tenure untouched")))

(deftest answer-ask-requires-an-explicit-tenure-when-several-are-parked
  (let [state (-> (parked-state "ask-many")
                  (assoc-in [:mission :relay :lanes :execution "tenure-2"]
                            {:lane :execution
                             :tenure-id "tenure-2"
                             :role :reviewer
                             :status :active
                             :awaiting-user? true})
                  (state/project))
        ambiguous (answer! state {})
        explicit (answer! state {:lane :plan :tenure-id "tenure-1"})]
    (is (false? (get-in ambiguous [:receipt :ok?])))
    (is (= :AMBIGUOUS_ASK
           (get-in ambiguous [:receipt :error :code])))
    (is (= 2 (count (get-in ambiguous [:receipt :error :details :candidates]))))
    (is (= :accepted (get-in explicit [:receipt :status])) (pr-str explicit))
    (is (= :answer-accepted (:outcome (:receipt explicit))))
    (is (true? (get-in explicit [:state :mission :relay :lanes :execution
                                 "tenure-2" :awaiting-user?]))
        "answering the Plan tenure does not resume the other parked tenure")))

(deftest outbound-context-freezes-once-per-call
  (let [calls (atom 0)
        provider (fn [_]
                   {:ok? true
                    :context {:marker (swap! calls inc)}})
        base (state/initial-state
              {:session-id "context-freeze"
               :models {:main "main-model" :task "task-model"}})
        [base call] (state/create-call
                     base
                     {:call-id "model-1"
                      :kind :model
                      :role :plan
                      :lane :plan
                      :tenure-id "tenure-1"
                      :agent-id :plan
                      :request {:snapshot {:messages [{:role :user
                                                       :text "hi"}]}}
                      :created-at 1})
        ctx {:outbound-context-fn provider}
        frozen (#'guards/freeze-outbound-context
                base ctx call "attempt-1")
        retry (#'guards/freeze-outbound-context
               base ctx frozen "attempt-2")
        retry-calls @calls
        [base2 call2] (state/create-call
                       base
                       {:call-id "model-2"
                        :kind :model
                        :role :plan
                        :lane :plan
                        :tenure-id "tenure-1"
                        :agent-id :plan
                        :request {:snapshot {:messages [{:role :user
                                                         :text "next"}]}}
                        :created-at 2})
        next-turn (#'guards/freeze-outbound-context
                   base2 ctx call2 "attempt-3")]
    (is (= {:marker 1 :attempt-id "attempt-1"} (:context frozen)))
    (is (= "attempt-1" (:context-attempt-id frozen)))
    (is (= (:context frozen) (:context retry))
        "a retry reuses the frozen snapshot")
    (is (= 1 retry-calls) "a retry does not recompute")
    (is (= {:marker 2 :attempt-id "attempt-3"} (:context next-turn))
        "a new model call computes a new snapshot")
    (is (= 2 @calls))))

(deftest worker-run-program-command-applies-and-reports-conflict
  (let [runs (atom [])
        mode (atom :applied)
        handle (start-owner
                []
                {:plan-milestone :pending
                 :draft {:path "plans/e2e09.md" :content "Draft body"}
                 :worker-run-program-fn
                 (fn [request]
                   (swap! runs conj request)
                   (if (= :conflict @mode)
                     {:status :succeeded
                      :value {:status :FILE_CONFLICT
                              :details {:path "e2e09.txt"
                                        :expected {:mtime-sec 1 :size 8}
                                        :actual {:mtime-sec 2 :size 9}}}}
                     {:status :succeeded
                      :value {:status :APPLIED
                              :stamp {:mtime-sec 2 :size 9}}}))})]
    (try
      (let [tenure-id (start-execution! handle)
            _ (start-swarm! handle tenure-id)
            worker-id (register-worker! handle tenure-id "slot-a")
            task-id (claim-root! handle tenure-id worker-id)
            claim-id (get-in (state handle)
                             [:mission :boards tenure-id :tasks task-id
                              :claim-id])
            receipt (command! handle "wp-1"
                              {:kind :worker/run-program
                               :task-id task-id
                               :claim-id claim-id
                               :program-source "(defprogram w [] (finish {}))"
                               :intent "write e2e09.txt"})
            job-id (:job-id receipt)]
        (is (= :accepted (:status receipt)) (pr-str receipt))
        (is (= :scheduled (:outcome receipt))
            "the handler schedules and returns immediately")
        (is (wait-for #(contains? #{:finished :failed}
                                  (get-in (state handle)
                                          [:worker-jobs job-id :state]))
                      3000))
        (let [job (get-in (state handle) [:worker-jobs job-id])]
          (is (= :applied (:outcome job)))
          (is (= {:mtime-sec 2 :size 9} (:stamp job)))
          (is (= 1 (count @runs)))
          (is (= worker-id (:agent-id (first @runs))))
          (is (= claim-id (:claim-id (first @runs))))
          ;; A duplicate result is idempotent.
          (let [before job
                duplicate ((:submit! handle)
                           {:type :worker/program-result
                            :job-id job-id
                            :result {:status :succeeded
                                     :value {:status :APPLIED}}})]
            ;; `submit!` returns the mailbox acknowledgement; the job map
            ;; itself proves the duplicate result was ignored.
            (is (= before (get-in (state handle)
                                  [:worker-jobs job-id]))))))
      (reset! mode :conflict)
      (let [tenure-id (first (keys (get-in (state handle)
                                           [:mission :relay :lanes :execution])))
            task-id (first (keys (get-in (state handle)
                                         [:mission :boards tenure-id :tasks])))
            claim-id (get-in (state handle)
                             [:mission :boards tenure-id :tasks task-id
                              :claim-id])
            receipt (command! handle "wp-2"
                              {:kind :worker/run-program
                               :task-id task-id
                               :claim-id claim-id
                               :program-source "(defprogram w [] (finish {}))"
                               :intent "conflict"})
            job-id (:job-id receipt)]
        (is (= :accepted (:status receipt)) (pr-str receipt))
        (is (wait-for #(contains? #{:finished :failed}
                                  (get-in (state handle)
                                          [:worker-jobs job-id :state]))
                      3000))
        (let [job (get-in (state handle) [:worker-jobs job-id])]
          (is (= :file-conflict (:outcome job)))
          (is (= {:mtime-sec 1 :size 8}
                 (get-in job [:stamp :expected])))
          (is (= {:mtime-sec 2 :size 9}
                 (get-in job [:stamp :actual])))))
      (finally ((:stop! handle))))))

(deftest nudge-suppress-clears-the-turn-as-an-idle-boundary
  ;; D-13: a host/test hook may suppress every enabled Nudge.  The stopped
  ;; turn must still be answered once -- `:nudge-suppressed` in
  ;; `:turn-history` is the observable idle boundary -- and a later ended
  ;; turn is judged again, so suppression is level-triggered, not one-shot.
  (let [requests (atom [])
        handle (start-owner requests
                            {:nudge-suppress
                             (fn [_state _turn _observation]
                               :nudge-disabled-by-config)})]
    (try
      (turn-ended! handle :plan "tenure-1" :plan)
      (is (wait-for #(= 1 (count (:turn-history (state handle)))) 3000))
      (let [owner-state (state handle)
            entry (last (:turn-history owner-state))]
        (is (empty? (nudge-calls owner-state)))
        (is (empty? @requests))
        (is (= :nudge-suppressed (:reason entry)))
        (is (= :nudge-disabled-by-config (:suppress-reason entry)))
        (is (false? (:enabled? entry))
            "a suppressed stop is not recorded as a Nudge decision")
        (is (nil? (:turn owner-state))
            "the suppressed turn is cleared, not left pending"))
      ;; Level-triggered: the next natural stop is suppressed again, never
      ;; silently re-enabled after one answer.
      (turn-ended! handle :plan "tenure-1" :plan)
      (is (wait-for #(= 2 (count (:turn-history (state handle)))) 3000))
      (let [owner-state (state handle)]
        (is (empty? (nudge-calls owner-state)))
        (is (empty? @requests))
        (is (= :nudge-suppressed
               (:reason (last (:turn-history owner-state))))))
      (finally
        ((:stop! handle))))))

(deftest nudge-suppress-nil-allows-the-nudge
  ;; The hook returning nil must not change §2.5: the same observation still
  ;; produces the Nudge call.
  (let [requests (atom [])
        handle (start-owner requests
                            {:nudge-suppress (fn [_state _turn _obs] nil)})]
    (try
      (turn-ended! handle :plan "tenure-1" :plan)
      (is (wait-for #(= 1 (count (nudge-calls (state handle)))) 3000))
      (is (wait-for #(= 1 (count @requests)) 3000))
      (finally
        ((:stop! handle))))))

(deftest nudge-suppress-leaves-legal-exits-alone
  ;; Suppression only gates an *enabled* Nudge decision.  A legal exit is
  ;; still recorded as the exit, not as `:nudge-suppressed`.
  (let [requests (atom [])
        handle (start-owner requests
                            {:nudge-suppress (fn [_state _turn _obs] :off)})]
    (try
      (command! handle "ask"
                {:kind :review/submit
                 :lane :plan
                 :tenure-id "tenure-1"
                 :findings []})
      (is (wait-for #(true? (get-in (state handle)
                                    [:mission :relay :lanes :plan
                                     :awaiting-user?]))
                    3000))
      (turn-ended! handle :plan "tenure-1" :plan)
      (is (wait-for #(= 1 (count (:turn-history (state handle)))) 3000))
      (let [entry (last (:turn-history (state handle)))]
        (is (= :plan-s1-ask (get-in entry [:exit :kind])))
        (is (not= :nudge-suppressed (:reason entry))))
      (finally
        ((:stop! handle))))))

(deftest round-boundary-stops-the-request-loop
  ;; D-13 end to end: an open Plan ledger keeps §2.5 enabled, but after the
  ;; configured round budget the Owner answers `:round-boundary`, clears
  ;; the turn, and stops dispatching model requests.  The next ended turn
  ;; is judged again (level-triggered), never silently re-enabled.  The
  ;; gateway completes every request immediately, so each settled turn
  ;; produces exactly one Nudge call until the budget is spent.
  (let [requests (atom [])
        budget 3
        {:keys [gateway releases]}
        (gated-model-gateway requests)
        handle (start-owner requests
                            {:model-gateway gateway
                             :config {:swarm-slots []
                                      :nudge-round-budget budget}})]
    (try
      (command! handle "c1"
                {:kind :send-message :text "hello"})
      ;; One user turn plus `budget` Nudge rounds, then the boundary.
      ;; Release one request at a time, only after the Nudge call it
      ;; produced owns a live turn.  Every turn invokes the Scope-exempt
      ;; `negotiate` member, so each settled turn is task-cycle work that
      ;; still owes a Nudge — the round budget, not a `:plain-response`
      ;; exit, is what stops the loop.
      (dotimes [n (inc budget)]
        (is (wait-for #(<= (inc n) (count @requests)) 5000)
            (str "request " (inc n) " was dispatched"))
        (let [deliver! (nth @releases n)]
          (deliver! {:event :tool-call
                     :call-id (str "negotiate-" (inc n))
                     :name "negotiate"
                     :payload {}})
          (deliver! {:event :completed :text "still working"}))
        (when (< n budget)
          (is (wait-for (fn []
                          (let [s (state handle)
                                live (some (fn [[_ call]]
                                             (when (and (:nudge? call)
                                                        (contains? #{:ready
                                                                     :dispatching
                                                                     :running
                                                                     :retry-wait}
                                                                    (:state call)))
                                               call))
                                           (:calls s))]
                            (and (some? live)
                                 (false? (get-in s [:turn :ended?]))
                                 (= (:call-id live)
                                    (get-in s [:turn :call-id])))))
                        5000)
              (str "round " (inc n) " produced a live Nudge call"))))
      (is (wait-for
           (fn []
             (let [s (state handle)]
               (and (nil? (:turn s))
                    (= :round-boundary
                       (:reason (last (:turn-history s)))))))
           5000)
          "the round budget commits the :round-boundary stop")
      (let [owner-state (state handle)]
        (is (= (inc budget) (count @requests))
            "the request count stops growing at the budget")
        (is (= budget (count (nudge-calls owner-state)))
            "exactly `budget` Nudge calls were created")
        (is (= :round-boundary
               (:reason (last (:turn-history owner-state)))))
        (is (= budget
               (get-in owner-state
                       [:turn-history (dec (count (:turn-history
                                                   owner-state)))
                        :round-seq]))
            "the boundary records the consumed round count")
        (is (nil? (:turn owner-state))
            "the boundary leaves no live turn")
        ;; The snapshot's run-status derivation (`hc.server.app.snapshot`)
        ;; treats a settled `:round-boundary` as an idle boundary: with no
        ;; live call or turn the session reports `:finished`/`unattended?
        ;; false` even while actor slots are still initialized.  The
        ;; expressions below mirror snapshot.clj's let bindings.
        (let [live-calls
              (remove (fn [[_ call]]
                        (contains? #{:completed :succeeded :failed
                                     :cancelled :outcome-unknown}
                                   (:state call)))
                      (:calls owner-state))
              nudge-active?
              (boolean
               (some (fn [[_ call]]
                       (or (:nudge? call) (:nudge-id call)
                           (get-in call [:request :snapshot :nudge-id])))
                     live-calls))
              turn-active? (some? (:turn owner-state))
              actor-active?
              (boolean
               (some (fn [[_ tenure]]
                       (some (fn [[_ slot]]
                               (or (= :running (:status slot))
                                   (some? (:attempt-id slot))))
                             (:slots tenure)))
                     (:tenures (:actors owner-state))))
              has-conversation? (boolean (seq (:conversation owner-state)))
              round-boundary?
              (and (seq (:turn-history owner-state))
                   (= :round-boundary
                      (:reason (last (:turn-history owner-state))))
                   (not turn-active?)
                   (empty? live-calls))
              run-active? (boolean
                           (or (seq live-calls) turn-active?
                               (and has-conversation? actor-active?
                                    (not round-boundary?))))
              run-status (if (or run-active?
                                 (and has-conversation? actor-active?
                                      (not round-boundary?)))
                           :running :finished)
              unattended? (boolean
                           (and (or has-conversation? run-active?)
                                (not round-boundary?)
                                (or nudge-active? turn-active?
                                    actor-active?)))]
          (is (= :finished run-status))
          (is (false? unattended?))))
      (finally
        ((:stop! handle))))))
