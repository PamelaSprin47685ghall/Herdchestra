(ns hc.compose.owner.scope-state-test
  "Cold-start behavior of the Owner's persisted Scope state."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.commands :as commands]
            [hc.core.scope.annotation :as annotation]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.state :as state]
            [hc.core.scope.ledger :as ledger]
            [hc.core.pair-hint :as pair-hint]))

(defn- established-book []
  (:book (ledger/settle-turn
          (ledger/new-book) "turn-1"
          (annotation/parse-annotation
           {:kind :reset-scope :new-scope-goal "g"
            :new-scope-retention-budget 4})
          {:call-id "r1"})))

(deftest restore-clears-the-scope-budget-and-keeps-records
  (let [initial (state/initial-state {:session-id "scope-restore"})
        records [{:v 1 :type :reset :turn-id "turn-1" :call-id "r1"
                  :goal "g" :budget 4 :remaining 4}]
        persisted (assoc initial :scope {:book (established-book)
                                         :records records})
        restored (state/restore persisted)]
    (is (:ok? restored))
    (let [scope (:scope (:state restored))]
      (is (false? (get-in scope [:book :retention :established?])))
      (is (= 0 (get-in scope [:book :retention :remaining])))
      (is (= records (:records scope)))
      (is (= "r1" (get-in scope [:book :last-reset :call-id]))))))

(deftest restore-keeps-book-history-when-records-are-gone
  (let [initial (state/initial-state {:session-id "scope-restore-old"})
        persisted (assoc initial :scope {:book (established-book)
                                         :records []})
        restored (state/restore persisted)]
    (is (:ok? restored))
    (let [scope (:scope (:state restored))]
      (is (false? (get-in scope [:book :retention :established?])))
      (is (= "r1" (get-in scope [:book :last-reset :call-id]))))))

(deftest restore-clears-a-proven-dead-draft-claim-only
  (let [initial (state/initial-state {:session-id "draft-restart"
                                      :owner-instance-id "old"})
        claim {:path "plans/a.md"
               :request-id "claim-1"
               :call-id "call-1"
               :owner-instance-id "old"}
        persisted (assoc-in initial [:mission :draft-claim] claim)
        same (state/restore persisted {:owner-instance-id "old"})
        dead (state/restore persisted {:owner-instance-id "new"})
        legacy (state/restore
                (update-in persisted [:mission :draft-claim]
                           dissoc :owner-instance-id)
                {:owner-instance-id "new"})]
    (is (:ok? same))
    (is (= "plans/a.md"
           (get-in same [:state :mission :draft-claim :path])))
    (is (:ok? dead))
    (is (nil? (get-in dead [:state :mission :draft-claim])))
    (is (= "new"
           (get-in dead [:state :mission :owner-instance-id])))
    (is (:ok? legacy))
    (is (= "plans/a.md"
           (get-in legacy [:state :mission :draft-claim :path])))))

(deftest close-model-turn-scope-ignores-a-stale-turn
  (let [book (:book (ledger/settle-turn (ledger/new-book) "turn-1"
                                        {:kind :reset-scope :goal "g" :budget 2}
                                        {:call-id "r1"}))
        book (:book (ledger/settle-turn book "turn-2" {:kind :me-too} {}))
        initial (state/initial-state {:session-id "scope-stale"})
        state (assoc initial :scope {:book book :records []})
        state' (state/close-model-turn-scope state "turn-1" 0)]
    (is (= "turn-2" (get-in state' [:scope :book :turn :id])))
    (is (false? (get-in state' [:scope :book :turn :settled?])))
    (is (= 2 (get-in state' [:scope :book :retention :remaining])))
    (is (some #(= :SCOPE_CLOSE_STALE (:code %)) (:diagnostics state')))))

(deftest apply-scope-result-keeps-boundary-records
  (let [initial (state/initial-state {:session-id "scope-records"})
        keeps (vec (repeat 600 {:v 1 :type :keep :remaining 1}))
        state (assoc initial :scope {:book (ledger/new-book)
                                     :records keeps})
        state' (state/apply-scope-result
                state
                (ledger/new-book)
                [{:v 1 :type :remove :call-id "e1"
                  :replacement "lesson" :spin 1}])
        records (get-in state' [:scope :records])]
    (is (= 1 (count (filter #(= :remove (:type %)) records))))
    (is (= 512 (count (filter #(= :keep (:type %)) records))))
    (is (= :remove (:type (last records))))))

(deftest close-model-turn-scope-with-no-scope-activity-is-a-no-op
  (let [initial (state/initial-state {:session-id "scope-empty"})
        state' (state/close-model-turn-scope initial "call-1" 0)]
    (is (nil? (get-in state' [:scope :book :turn])))
    (is (empty? (:diagnostics state')))))

(deftest a-ready-tool-blocks-a-new-model-claim
  (let [initial (state/initial-state {:session-id "scope-ready"})
        state (assoc initial :calls
                     {"model-1" {:call-id "model-1" :kind :model
                                 :state :ready :agent-id :plan
                                 :created-at 1}
                      "tool-1" {:call-id "tool-1" :kind :tool
                                :state :ready :agent-id :plan
                                :scope-turn-id "model-0"
                                :scope-exempt? false
                                :created-at 0}})
        guard (guards/claim-model-request-guard)]
    (is (nil? ((:enabled? guard) state {})))
    (is (= {:call-id "model-1"}
           ((:enabled? guard)
            (assoc-in state [:calls "tool-1" :state] :succeeded)
            {})))))

(deftest duplicate-tool-call-is-ignored-before-scope-admission
  (let [initial (state/initial-state {:session-id "scope-dup"})
        state (assoc initial :calls
                     {"model-1" {:call-id "model-1" :kind :model
                                 :state :running :attempt-id "a1"
                                 :role :plan :lane :plan :agent-id :plan}
                      "model-1/tool-0"
                      {:call-id "model-1/tool-0" :kind :tool
                       :state :running}})
        result (handlers/apply-message
                state
                {:type :model/event
                 :event {:event :tool-call
                         :model-call-id "model-1"
                         :attempt-id "a1"
                         :call-id "tool-1"
                         :payload {:executor :tool :name "cat"}}}
                {:now 0})]
    (is (= :ignored (get-in result [:receipt :status])))
    (is (= :duplicate-tool-call (get-in result [:receipt :reason])))
    (is (= state (:state result)))
    (is (empty? (:conversation (:state result))))))

(deftest admission-refusal-appends-a-paired-tool-result
  (let [initial (state/initial-state {:session-id "scope-refusal"})
        state (assoc initial :calls
                     {"model-1" {:call-id "model-1" :kind :model
                                 :state :running :attempt-id "a1"
                                 :role :plan :lane :plan :agent-id :plan}})
        result (handlers/apply-message
                state
                {:type :model/event
                 :event {:event :tool-call
                         :model-call-id "model-1"
                         :attempt-id "a1"
                         :call-id "tool-1"
                         :arguments {:path "x"}
                         :payload {:executor :tool :name "cat"}}}
                {:now 0})
        conversation (:conversation (:state result))]
    (is (= :refused (get-in result [:receipt :status])))
    (is (= [:tool-call :tool] (mapv :role conversation)))
    (is (= "model-1/tool-0" (:call-id (first conversation))))
    (is (= {:path "x"} (:arguments (first conversation))))
    (is (= "model-1/tool-0" (:tool-call-id (second conversation))))
    (is (nil? (get-in result [:state :calls "model-1/tool-0"])))))

(defn- scope-tool-call
  "One `:tool-call` model event for MEMBER carrying SCOPE (or none)."
  [member scope]
  {:type :model/event
   :event {:event :tool-call
           :attempt-id "a1"
           :model-call-id "model-1"
           :call-id "tool-1"
           :payload (cond-> {:executor :tool :name member}
                       (some? scope) (assoc :scope scope))}})

(defn- scope-tool-result
  "Apply SCOPE-TOOL-CALL against a fresh running model call on BOOK."
  [session-id book member scope]
  (let [state (-> (state/initial-state {:session-id session-id})
                  (assoc :scope {:book book :records []})
                  (assoc-in [:calls "model-1"]
                            {:call-id "model-1" :kind :model
                             :state :running :attempt-id "a1"
                             :role :plan :lane :plan :agent-id :plan}))]
    (handlers/apply-message state (scope-tool-call member scope) {:now 0})))

(deftest string-scope-kind-is-parsed-as-an-annotation
  (testing "a bare kind name admits like the map form"
    (let [result (scope-tool-result "scope-str-me-too"
                                    (established-book) "read" "me-too")
          call (get-in result [:state :calls "model-1/tool-0"])]
      (is (= :accepted (get-in result [:receipt :status])))
      (is (= :ready (:state call)))
      (is (= :me-too (get-in call [:scope-annotation :kind])))))
  (testing "an EDN map string parses through the same path"
    (let [result (scope-tool-result "scope-str-edn"
                                    (established-book) "read"
                                    "{:kind \"me-too\"}")
          call (get-in result [:state :calls "model-1/tool-0"])]
      (is (= :accepted (get-in result [:receipt :status])))
      (is (= :me-too (get-in call [:scope-annotation :kind])))))
  (testing "an unknown kind string is KIND_INVALID, not MISSING"
    (let [result (scope-tool-result "scope-str-bogus"
                                    (established-book) "read" "bogus")]
      (is (= :refused (get-in result [:receipt :status])))
      (is (= :SCOPE_KIND_INVALID
             (get-in result [:receipt :error :code]))))))

(deftest missing-scope-on-a-schema-required-member-names-the-parameter
  (let [result (scope-tool-result "scope-missing-hint"
                                  (ledger/new-book) "devops" nil)
        tool-text (some #(when (= :tool (:role %)) (:text %))
                        (:conversation (:state result)))]
    (is (= :refused (get-in result [:receipt :status])))
    (is (= :SCOPE_ANNOTATION_MISSING
           (get-in result [:receipt :error :code])))
    (is (re-find #"inside this tool's `parameters`" (str tool-text))
        "the refusal locates :scope inside the tool parameters")
    (is (re-find #"required" (str tool-text)))))

(deftest missing-scope-on-another-member-keeps-the-generic-message
  (let [result (scope-tool-result "scope-missing-generic"
                                  (ledger/new-book) "cat" nil)
        tool-text (some #(when (= :tool (:role %)) (:text %))
                        (:conversation (:state result)))]
    (is (= :SCOPE_ANNOTATION_MISSING
           (get-in result [:receipt :error :code])))
    (is (not (re-find #"inside this tool's `parameters`" (str tool-text))))))

;; ---------------------------------------------------------------------------
;; askPreviousScope receipts, the reset ask gate, and answer delivery (P1)

(defn- ask-command
  [command-id call-id questions]
  {:type :command
   :command-id command-id
   :command-epoch 1
   :payload {:kind :scope/ask-previous
             :call-id call-id
             :questions questions
             :member "askPreviousScope"
             :turn-id "turn-ask"
             :lane :execution
             :agent-id "worker-1"}})

(defn- ask-answer-command
  [command-id call-id answer]
  {:type :command
   :command-id command-id
   :command-epoch 1
   :payload {:kind :scope/ask-answer
             :call-id call-id
             :answer answer}})

(defn- with-reset-beat
  "State whose Scope book holds one settled reset beat."
  [initial]
  (let [parsed (annotation/parse-annotation
                {:kind :reset-scope :new-scope-goal "g"
                 :new-scope-retention-budget 4})
        {:keys [book]}
        (ledger/settle-turn (ledger/new-book) "turn-1" parsed
                            {:call-id "r1"})]
    (state/apply-scope-result initial book [])))

(defn- ask-state
  "State with one recorded ask receipt for call-id \"ask-1\"."
  [initial]
  (:state (handlers/apply-message
           (with-reset-beat initial)
           (ask-command "cmd-ask-1" "ask-1" "what did we decide?")
           {:now 10 :registry (commands/local-registry)})))

(deftest ask-previous-scope-returns-accepted-receipt-without-answer
  (let [initial (state/initial-state {:session-id "ask-basic"})
        result (handlers/apply-message
                (with-reset-beat initial)
                (ask-command "cmd-ask-1" "ask-1" "what did we decide?")
                {:now 10 :registry (commands/local-registry)})
        receipt (:receipt result)]
    (is (= :accepted (:status receipt)))
    (is (= :accepted (:outcome receipt)))
    (is (= "ask-1" (:call-id receipt)))
    (is (= "r1" (:target-call-id receipt)))
    (is (not (contains? receipt :answer))
        "the synchronous receipt never carries the answer")
    (is (not (contains? receipt :text))
        "the synchronous receipt never carries answer text")))

(deftest ask-previous-scope-replay-is-idempotent
  (let [initial (state/initial-state {:session-id "ask-replay"})
        state1 (with-reset-beat initial)
        first (handlers/apply-message
               state1 (ask-command "cmd-ask-1" "ask-1" "same question?")
               {:now 10 :registry (commands/local-registry)})
        state2 (:state first)
        ;; A second tool call reuses the same physical call-id: the Owner
        ;; ask table, not the command gate, decides idempotency.
        second (handlers/apply-message
                state2 (ask-command "cmd-ask-2" "ask-1" "same question?")
                {:now 11 :registry (commands/local-registry)})
        asks (get-in (:state second) [:scope :asks])]
    (is (= 1 (count asks)))
    (is (= :accepted (:status (:receipt second))))
    (is (true? (:replayed? (:receipt second))))
    (is (= "same question?"
           (get-in (:state second) [:scope :asks "ask-1" :questions])))))

(deftest ask-previous-scope-same-id-different-questions-is-refused
  (let [initial (state/initial-state {:session-id "ask-mismatch"})
        state1 (with-reset-beat initial)
        first (handlers/apply-message
               state1 (ask-command "cmd-ask-1" "ask-1" "first?")
               {:now 10 :registry (commands/local-registry)})
        second (handlers/apply-message
                (:state first)
                (ask-command "cmd-ask-2" "ask-1" "different?")
                {:now 11 :registry (commands/local-registry)})]
    (is (false? (:ok? (:receipt second))))
    (is (= :ASK_REPLAY_MISMATCH
           (get-in (:receipt second) [:error :code])))))

(deftest ask-previous-scope-without-beat-is-no-previous-scope
  (let [initial (state/initial-state {:session-id "ask-empty"})
        result (handlers/apply-message
                initial (ask-command "cmd-ask-1" "ask-1" "anything?")
                {:now 10 :registry (commands/local-registry)})]
    (is (false? (:ok? (:receipt result))))
    (is (= :NO_PREVIOUS_SCOPE
           (get-in (:receipt result) [:error :code])))))

(deftest ask-previous-scope-blank-questions-is-refused
  (let [initial (state/initial-state {:session-id "ask-blank"})
        result (handlers/apply-message
                (with-reset-beat initial)
                (ask-command "cmd-ask-1" "ask-1" "   ")
                {:now 10 :registry (commands/local-registry)})]
    (is (false? (:ok? (:receipt result))))
    (is (= :ASK_QUESTIONS_REQUIRED
           (get-in (:receipt result) [:error :code])))))

(deftest second-reset-without-ask-receipt-is-refused
  (let [initial (state/initial-state {:session-id "ask-gate"})
        state1 (with-reset-beat initial)
        check (state/scope-admission-check
               state1 "resetScope"
               {:kind :reset-scope :new-scope-goal "g2" :new-scope-retention-budget 3}
               "call-reset-2")]
    (is (false? (:ok check)))
    (is (= :SCOPE_ASK_REQUIRED (:code check)))
    (is (= "r1" (get-in check [:details :previous-scope-call-id])))))
(deftest first-reset-needs-no-ask
  (let [initial (state/initial-state {:session-id "ask-first"})
        check (state/scope-admission-check
               initial "resetScope"
               {:kind :reset-scope :new-scope-goal "g" :new-scope-retention-budget 4}
               "call-reset-1")]
    (is (true? (:ok check)))))

(deftest reset-after-ask-receipt-passes-admission
  (let [initial (state/initial-state {:session-id "ask-pass"})
        state1 (with-reset-beat initial)
        state2 (ask-state state1)
        check (state/scope-admission-check
               state2 "resetScope"
               {:kind :reset-scope :new-scope-goal "g2" :new-scope-retention-budget 3}
               "call-reset-2")]
    (is (true? (:ok check)))))

(deftest ask-answer-delivers-agent-attributed-user-message
  (let [initial (state/initial-state {:session-id "ask-answer"})
        state1 (ask-state initial)
        result (handlers/apply-message
                state1
                (ask-answer-command "cmd-answer-1" "ask-1" "the sealed view said yes")
                {:now 20 :registry (commands/local-registry)})
        state2 (:state result)
        last-message (peek (:conversation state2))]
    (is (= :accepted (:status (:receipt result))))
    (is (= :delivered (:status (get-in state2 [:scope :asks "ask-1"]))))
    (is (= "the sealed view said yes" (get-in state2 [:scope :asks "ask-1" :answer :text])))
    (is (= :user (:role last-message)))
    (is (= "agent" (:attribution last-message)))
    (is (= "ask-1" (:ask-id last-message)))
    (is (= "the sealed view said yes" (:text last-message)))))

(deftest ask-answer-on-unknown-or-resolved-ask-is-refused
  (let [initial (state/initial-state {:session-id "ask-resolved"})
        state1 (ask-state initial)
        delivered (handlers/apply-message
                   state1
                   (ask-answer-command "cmd-answer-1" "ask-1" "answer")
                   {:now 20 :registry (commands/local-registry)})
        missing (handlers/apply-message
                 state1
                 (ask-answer-command "cmd-answer-2" "ask-404" "answer")
                 {:now 21 :registry (commands/local-registry)})
        again (handlers/apply-message
               (:state delivered)
               (ask-answer-command "cmd-answer-3" "ask-1" "again")
               {:now 22 :registry (commands/local-registry)})]
    (is (= :ASK_NOT_FOUND
           (get-in missing [:receipt :error :code])))
    (is (= :ASK_ALREADY_RESOLVED
           (get-in again [:receipt :error :code])))))

(deftest ask-answer-after-newer-reset-is-stale
  (let [initial (state/initial-state {:session-id "ask-stale"})
        state1 (ask-state initial)
        ;; A newer reset beat settles after the ask: the generation no
        ;; longer matches, so the answer is dropped as stale.
        state2 (state/apply-scope-result
                state1
                (:book (ledger/settle-turn
                        (state/scope-book state1) "turn-2"
                        (annotation/parse-annotation
                         {:kind :reset-scope :new-scope-goal "g2"
                          :new-scope-retention-budget 3})
                        {:call-id "r2"}))
                [])
        result (handlers/apply-message
                state2
                (ask-answer-command "cmd-answer-1" "ask-1" "too late")
                {:now 30 :registry (commands/local-registry)})
        state3 (:state result)]
    (is (= :accepted (:status (:receipt result))))
    (is (= :stale (get-in result [:receipt :outcome])))
    (is (= :stale (get-in state3 [:scope :asks "ask-1" :status])))
    (is (nil? (get-in state3 [:scope :asks "ask-1" :answer])))))

;; ---------------------------------------------------------------------------
;; askPreviousScope sealed view, ephemeral consult, and steer delivery (P2)

(defn- consult-conversation
  "Conversation entries that mirror one settled reset beat 'r1' plus the
  previous Scope's visible traffic."
  []
  [{:role :user :text "first scope goal" :at 1}
   {:role :assistant :text "working" :at 2}
   {:role :tool-call :member "resetScope" :call-id "r1"
    :scope {:kind :reset-scope} :at 3}
   {:role :tool :tool-call-id "r1" :member "resetScope" :call-id "r1"
    :text "reset ok" :at 4}
   {:role :assistant :text "scope one answer" :at 5}
   {:role :user :text "operator note" :at 6}])

(defn- consult-state
  "State with one reset beat, its conversation, and one pending ask."
  []
  (let [initial (state/initial-state {:session-id "consult"})
        state1 (-> (with-reset-beat initial)
                   (assoc :conversation (consult-conversation)))
        state2 (ask-state state1)]
    state2))

(deftest sealed-scope-view-slices-the-previous-scope
  (let [s (consult-state)
        view (state/sealed-scope-view s "r1")]
    (is (:ok? view))
    (is (= 2 (:start view)))
    (is (= 6 (:end view)))
    (is (seq (:messages view)))
    (is (string? (:digest view)))))

(deftest sealed-scope-view-stops-at-the-next-reset
  (let [s (consult-state)
        ;; A second reset beat 'r2' and its conversation entries exist;
        ;; the view for 'r1' must end where 'r2' begins.
        book (state/scope-book s)
        parsed (annotation/parse-annotation
                {:kind :reset-scope :new-scope-goal "g2"
                 :new-scope-retention-budget 3})
        {:keys [book]}
        (ledger/settle-turn book "turn-2" parsed {:call-id "r2"})
        s2 (state/apply-scope-result s book [])
        s2 (update s2 :conversation conj
                   {:role :tool-call :member "resetScope" :call-id "r2"
                    :scope {:kind :reset-scope} :at 7}
                   {:role :tool :tool-call-id "r2" :member "resetScope"
                    :call-id "r2" :text "reset2 ok" :at 8}
                   {:role :assistant :text "scope two" :at 9})
        view (state/sealed-scope-view s2 "r1")]
    (is (:ok? view))
    (is (= 6 (:end view))
        "the view ends at the r2 tool-call entry")))

(deftest consult-guard-creates-an-ephemeral-consult-call
  (let [s (consult-state)
        guard (guards/scope-ask-consult-guard)
        work ((:enabled? guard) s {})
        prepared ((:prepare guard) s {:now 20} work nil)
        s2 (:state prepared)
        consult-call (some (fn [call]
                             (when (= "scope-ask:ask-1" (:agent-id call))
                               call))
                           (vals (:calls s2)))
        snapshot (get-in consult-call [:request :snapshot])]
    (is (= :consult (:kind work)))
    (is (= "ask-1" (:call-id work)))
    (is (some? consult-call))
    (is (= :model (:kind consult-call)))
    (is (= :ready (:state consult-call)))
    (is (= "ask-1" (get-in snapshot [:scope-consult-for])))
    (is (true? (:ephemeral? snapshot)))
    (is (= "agent" (:attribution snapshot)))
    (is (= [] (:tools snapshot)))
    (is (= :consulting
           (get-in s2 [:scope :asks "ask-1" :consult])))
    (is (string? (get-in s2 [:scope :asks "ask-1"
                             :consult-view-digest])))
    ;; The snapshot's last message is the ask question, after the sealed
    ;; view's rebuilt messages.
    (let [messages (:messages snapshot)
          last-message (peek messages)]
      (is (= :user (:role last-message)))
      (is (re-find #"what did we decide\?" (:text last-message))))))

(deftest consult-answer-delivers-as-agent-attributed-user-message
  (let [s (consult-state)
        guard (guards/scope-ask-consult-guard)
        work ((:enabled? guard) s {})
        s2 (:state ((:prepare guard) s {:now 20} work nil))
        consult-call (some #(when (= "scope-ask:ask-1" (:agent-id %)) %)
                           (vals (:calls s2)))
        call-id (:call-id consult-call)
        ;; Claim the consult call like claim-model-request-guard would.
        s3 (-> s2
               (assoc-in [:calls call-id :state] :dispatching)
               (assoc-in [:calls call-id :attempt-id] :attempt-1))
        result (handlers/apply-message
                s3
                {:type :model/event
                 :event {:event :completed
                         :model-call-id call-id
                         :attempt-id :attempt-1
                         :text "the sealed view says continue"}}
                {:now 30 :registry (commands/local-registry)})
        s4 (:state result)
        last-message (peek (:conversation s4))]
    (is (= :delivered
           (get-in s4 [:scope :asks "ask-1" :status])))
    (is (= "the sealed view says continue"
           (get-in s4 [:scope :asks "ask-1" :answer :text])))
    (is (= :user (:role last-message)))
    (is (= "agent" (:attribution last-message)))
    (is (= "ask-1" (:ask-id last-message)))
    (is (= "the sealed view says continue" (:text last-message)))
    ;; The consult call never wrote an assistant message of its own.
    (is (not-any? #(and (= :assistant (:role %))
                        (= call-id (:call-id %)))
                  (:conversation s4)))))

(deftest consult-answer-survives-a-later-reset-in-projection
  (let [s (consult-state)
        guard (guards/scope-ask-consult-guard)
        work ((:enabled? guard) s {})
        s2 (:state ((:prepare guard) s {:now 20} work nil))
        consult-call (some #(when (= "scope-ask:ask-1" (:agent-id %)) %)
                           (vals (:calls s2)))
        call-id (:call-id consult-call)
        s3 (-> s2
               (assoc-in [:calls call-id :state] :dispatching)
               (assoc-in [:calls call-id :attempt-id] :attempt-1))
        result (handlers/apply-message
                s3
                {:type :model/event
                 :event {:event :completed
                         :model-call-id call-id
                         :attempt-id :attempt-1
                         :text "keep going"}}
                {:now 30 :registry (commands/local-registry)})
        s4 (:state result)
        ;; A newer reset settles after delivery; the steer is an operator
        ;; message and the projection keeps it across the boundary.
        book (state/scope-book s4)
        parsed (annotation/parse-annotation
                {:kind :reset-scope :new-scope-goal "g2"
                 :new-scope-retention-budget 3})
        {:keys [book]}
        (ledger/settle-turn book "turn-2" parsed {:call-id "r2"})
        s5 (state/apply-scope-result s4 book [])
        projected (state/project-conversation s5 (:conversation s5))]
    (is (:ok? projected))
    ;; The projection keeps operator messages across a reset boundary and
    ;; carries the `:attribution` audit marker; `:ask-id` stays on the
    ;; committed conversation entry, not the model-visible copy.
    (is (some #(and (= :user (:role %))
                    (= "agent" (:attribution %))
                    (= "keep going" (:text %)))
              (:messages projected)))))

(deftest consult-completed-after-newer-reset-is-discarded
  (let [s (consult-state)
        guard (guards/scope-ask-consult-guard)
        work ((:enabled? guard) s {})
        s2 (:state ((:prepare guard) s {:now 20} work nil))
        consult-call (some #(when (= "scope-ask:ask-1" (:agent-id %)) %)
                           (vals (:calls s2)))
        call-id (:call-id consult-call)
        ;; A newer reset settles while the consult is in flight.
        book (state/scope-book s2)
        parsed (annotation/parse-annotation
                {:kind :reset-scope :new-scope-goal "g2"
                 :new-scope-retention-budget 3})
        {:keys [book]}
        (ledger/settle-turn book "turn-2" parsed {:call-id "r2"})
        s3 (state/apply-scope-result s2 book [])
        s3 (-> s3
               (assoc-in [:calls call-id :state] :dispatching)
               (assoc-in [:calls call-id :attempt-id] :attempt-1))
        result (handlers/apply-message
                s3
                {:type :model/event
                 :event {:event :completed
                         :model-call-id call-id
                         :attempt-id :attempt-1
                         :text "too late"}}
                {:now 30 :registry (commands/local-registry)})
        s4 (:state result)
        conversation-count (count (:conversation s4))]
    (is (= :stale (get-in s4 [:scope :asks "ask-1" :status])))
    (is (= :stale (get-in result [:receipt :outcome])))
    (is (nil? (get-in s4 [:scope :asks "ask-1" :answer])))
    ;; No steer message was appended.
    (is (not-any? #(= "too late" (:text %)) (:conversation s4)))
    (is (= conversation-count (count (:conversation s4))))))

(deftest consult-snapshot-is-pair-hint-ephemeral
  ;; The consult snapshot's `:ephemeral?`/`:attribution "agent"` shape is
  ;; exactly what `pair_hint/integration.clj` reads to skip accounting.
  (let [s (consult-state)
        guard (guards/scope-ask-consult-guard)
        work ((:enabled? guard) s {})
        s2 (:state ((:prepare guard) s {:now 20} work nil))
        consult-call (some #(when (= "scope-ask:ask-1" (:agent-id %)) %)
                           (vals (:calls s2)))
        snapshot (get-in consult-call [:request :snapshot])
        session (pair-hint/create-session {:explicit-language :en})
        request {:snapshot snapshot}
        projected (pair-hint/project-request
                   session request
                   {:ephemeral? (or (:ephemeral? snapshot)
                                    (= "agent" (:attribution snapshot)))
                    :placement {:call-id (:call-id consult-call)}
                    :render (fn [v] (pr-str v))})]
    (is (true? (:ok? projected)))
    (is (= session (:session projected))
        "an ephemeral consult changes no Pair Hint session state")))

(deftest stale-pending-ask-is-swept-without-consult
  (let [s (consult-state)
        ;; A newer reset settles before the guard runs: the ask's
        ;; generation no longer matches the latest beat.
        book (state/scope-book s)
        parsed (annotation/parse-annotation
                {:kind :reset-scope :new-scope-goal "g2"
                 :new-scope-retention-budget 3})
        {:keys [book]}
        (ledger/settle-turn book "turn-2" parsed {:call-id "r2"})
        s2 (state/apply-scope-result s book [])
        guard (guards/scope-ask-consult-guard)
        work ((:enabled? guard) s2 {})
        prepared ((:prepare guard) s2 {:now 20} work nil)
        s3 (:state prepared)]
    (is (= :sweep (:kind work)))
    (is (= :stale (get-in s3 [:scope :asks "ask-1" :status])))
    (is (= :stale (get-in s3 [:scope :asks "ask-1" :consult])))
    (is (not-any? #(= "scope-ask:ask-1" (:agent-id %))
                  (vals (:calls s3)))
        "a stale ask never opens a consult call")))
