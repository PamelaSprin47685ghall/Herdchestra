(ns hc.compose.owner.handlers
  "Pure message handlers for one Owner.

  `apply-message` reads committed state and one message, and returns

    {:state state' :receipt receipt :outbox [notification ...]}

  It never dispatches, blocks, or reads a clock.  The Owner commits the
  returned state first and only then acts on the outbox.  A command is
  accepted through `hc.compose.owner.commands`; an internal message carries
  no client epoch."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hc.compose.cancel :as cancel]
            [hc.compose.mission :as mission]
            [hc.compose.mission.actors :as actors]
            [hc.compose.mission.model :as mission-model]
            [hc.compose.mission.surface :as surface]
            [hc.compose.owner.commands :as commands]
            [hc.compose.owner.compact :as compact]
            [hc.compose.owner.state :as state]
            [hc.compose.reports.acceptance :as acceptance]
            [hc.compose.reports.delivery :as delivery]
            [hc.core.relay :as relay]
            [hc.core.scope.ledger :as scope-ledger]
            [hc.core.ufss :as ufss]))

;; ---------------------------------------------------------------------------
;; Small helpers

(def command-kinds
  #{:user/message
    :send-message
    :operator/cancel
    :stop-run
    :answer-ask
    :review/submit
    :scope/ask-previous
    :scope/ask-answer
    :plan/write-draft
    :plan/claim-draft
    :plan/release-draft
    :user/unpark
    :board/command
    :worker/run-program
    :call/ack
    :owner/compact
    :report/ack
    :report/register
    :devops-request
    :devops-complete
    :session/issue-epoch
    :session/attach-client
    :session/takeover
    :session/detach-client})

(def client-presence-kinds
  "Commands that manage multi-client presence rather than session work.

  They are the only commands an observer may submit.  Control takeover is
  explicit: an observer becomes the controller only through
  `:session/takeover`, never by sending a normal command."
  #{:session/attach-client
    :session/takeover
    :session/detach-client})

(defn- session-command-kind?
  "Session metadata and history commands share the injected session port.
  The port validates the exact command type."
  [kind]
  (or (contains? #{:session/rename :session/update-draft :session/pin
                   :session/archive :session/restore :session/delete
                   :session/edit-message :session/delete-message
                   :session/fold-message :session/select-model
                   :session/select-skills
                   :session/send-message :session/transfer}
                 kind)
      (and (keyword? kind)
           (= "history" (namespace kind)))))

(defn- accepted
  ([state receipt]
   (accepted state receipt []))
  ([state receipt outbox]
   {:ok? true :state state :receipt receipt :outbox (vec outbox)}))

(defn- refused
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- in-swarm?
  [state]
  (boolean
   (some #(= :active (:swarm-phase %)) (state/execution-tenures state))))

(defn- central-role
  "The role that answers new user input.

  Plan answers before handoff.  A centralized Reviewer answers during an
  open execution window.  During swarm construction the board input is the
  public event and no central model call is invented."
  [state]
  (if (seq (state/execution-tenures state))
    (when-not (in-swarm? state) :reviewer)
    :plan))

(defn- central-tenure-id
  "Pin the lane tenure that owns a new central turn.

  Plan owns the Plan lane; a centralized Reviewer owns the current execution
  window.  Recording the id at message acceptance keeps the turn bound to the
  tenure it was written for.  If that tenure relays before the turn opens,
  `open-user-turn-guard` refuses it instead of silently running it under the
  successor.  A nil id means no tenure exists yet; the guard then refuses the
  turn rather than creating a tenure-less model call."
  [state role]
  (case role
    :plan (relay/current-tenure-id (:relay state) :plan)
    :reviewer (some-> (state/latest-active-execution-tenure state) :tenure-id)
    nil))

(defn- model-tools-problem
  "Return the explicit refusal for a central role whose selected model
  cannot carry its program tool.

  A nil binding is left to the existing no-model gate: the UI blocks that
  send, and the app gateway still refuses a tool-required request that
  reaches it.  A present binding with no declared `:tools` is a real
  configuration choice and must not start Plan or Reviewer: their unattended
  protocols need the program tool, and a text-only turn would only Nudge
  forever.  The refusal keeps the draft and does not append a message or a
  pending turn."
  [state ctx]
  (when-let [role (central-role state)]
    (let [binding (get-in state [:session :model-binding])
          live-fact (when-let [f (:model-tools-supported ctx)]
                      (try (f binding) (catch Throwable _ nil)))
          ;; `:model-tools-supported` answers the merged live+binding fact.
          ;; A non-nil answer is authoritative; nil means neither the live
          ;; entry nor the binding carried a `:tools` declaration, so the
          ;; binding's copied declaration is the last resort.
          binding-supported? (when (and (map? binding)
                                        (contains? binding :capabilities))
                               (mission-model/supports-tools?
                                (:capabilities binding)))
          ;; `:model-tools-supported` already merges the live model entry with
          ;; the binding declaration.  Trust its non-nil answer; a nil fact
          ;; (no resolver, or no live/binding capability at all) falls back to
          ;; the binding's copied declaration.
          supported? (if (some? live-fact)
                       (true? live-fact)
                       binding-supported?)]
      (when (and (mission-model/role-requires-tools? role)
                 binding
                 (not supported?))
        (let [source-fn (:model-tools-source ctx)
              resolved-source (when (fn? source-fn)
                                (try (source-fn binding) (catch Throwable _ nil)))
              ;; The resolver answers for the merged live+binding fact; a
              ;; non-nil live fact means the live model entry produced the
              ;; refusal.  Without either, only the binding (or nothing) was
              ;; consulted.
              capabilities-source
              (cond
                (some? resolved-source) resolved-source
                ;; Without a source resolver the merged fact cannot say
                ;; which declaration produced it; report the binding when it
                ;; carries capabilities, else :none.
                (and (map? binding)
                     (contains? binding :capabilities))
                :binding
                :else :none)]
        {:code :MODEL_TOOLS_UNAVAILABLE
         :role role
         :program-role (case role
                         :plan :clj-plan
                         :reviewer :clj-review
                         nil)
         :connection-id (:connection-id binding)
         :model-entry-id (:model-entry-id binding)
         :capabilities (:capabilities binding)
         :capabilities-source capabilities-source
         :remedy :select-model
         :draft-preserved? true})))))

(defn- normalize-scope-annotation
  "Model JSON arguments carry enum values as strings; the Scope parser
  expects keyword kinds.  Normalize at the Owner tool-call boundary so a
  valid model call is admitted instead of failing as an unknown kind."
  [annotation]
  (cond
    (string? annotation)
    ;; A bare kind name (`"me-too"`) or a small EDN map string is model
    ;; shorthand for the flat `:scope` map.  Parse it here so a present but
    ;; loosely-shaped annotation reaches the Scope parser instead of
    ;; reporting `:SCOPE_ANNOTATION_MISSING`.
    (let [trimmed (str/trim annotation)
          parsed (when (str/starts-with? trimmed "{")
                   (try (edn/read-string trimmed)
                        (catch Exception _ nil)))]
      (cond
        (map? parsed) (normalize-scope-annotation parsed)
        (some? parsed) parsed
        :else {:kind (keyword trimmed)}))

    (keyword? annotation)
    {:kind annotation}

    (and (map? annotation) (string? (:kind annotation)))
    (update annotation :kind keyword)

    :else
    annotation))

(def ^:private scope-required-members
  "Members whose tool schema declares `:scope` inside `parameters.required`.

  For these a missing annotation is a parameter mistake, not a top-level
  field mistake; the refusal says so explicitly."
  #{"devops" "clj-plan" "clj-review" "clj-worker"})

(defn- scope-refusal-message
  "Attach the parameter-level `:scope` hint when the member's schema
  requires it inside `parameters`.  Other members keep the parser's
  generic message."
  [member admission]
  (if (and (= :SCOPE_ANNOTATION_MISSING (:code admission))
           (contains? scope-required-members (str member)))
    (update admission :message
            (fn [message]
              (str message
                   " `:scope` is a required field inside this tool's "
                   "`parameters` (the schema `required` list), not a "
                   "top-level tool-call field; add `:scope {:kind ...}` "
                   "there and retry.")))
    admission))

(def ^:private owner-tool-keys
  "Tool-payload keys the Owner derives from committed state.

  Model arguments may describe the program and its Scope, never the tenure,
  lane, actor, program role, or Plan policy.  Merging them after the Owner
  values would let a late or forged call redirect a review, unlock a write,
  or claim an S1 ask."
  #{:role :mission-role :program-role :tenure-id :lane :agent-id
    :holder? :s1? :write-unlocked? :draft-path :readable-paths
    :workspace :board-snapshot :model-role :model-id :call-id
    :plan-draft})

(def ^:private model-argument-keys
  "Keys a model argument map may never supply.

  The transport payload still carries `:executor`/`:kind`; a model argument
  map may not choose a different executor for its tool call."
  (into owner-tool-keys [:executor :kind]))

(defn- model-arguments
  [raw-payload arguments]
  (merge raw-payload
         (apply dissoc arguments model-argument-keys)))

(defn- pending-turn
  [role text message ctx tenure-id payload]
  (merge {:role role
          :agent-id role
          :tenure-id tenure-id
          :text text
          :command-id (:command-id message)
          :at (:now ctx)}
         (select-keys payload [:turn-card-ids :instruction-values :search
                               :temporary-read? :chat?])))

(defn- operator-command?
  "True only when the gateway marked a genuine human-originated command."
  [message]
  (or (true? (:operator? message))
      (true? (get-in message [:payload :operator?]))
      (true? (get-in message [:payload :operator]))))

;; ---------------------------------------------------------------------------
;; Mission and board composition

(defn- tenure-id-for
  [state command]
  (or (:tenure-id command)
      ;; A shell action names its actor.  Several execution tenures may be
      ;; active after a relay, so `sole-execution-tenure` is nil and a board
      ;; command without a tenure id would fail `:UNKNOWN_TENURE`.  The
      ;; actor's own board is the authoritative tenure.
      (when-let [actor-id (:actor-id command)]
        (some (fn [[tenure-id board]]
                (when (get-in board [:actors actor-id])
                  tenure-id))
              (get-in state [:mission :boards])))
      (some-> (state/sole-execution-tenure state) :tenure-id)
      (some-> (state/latest-active-execution-tenure state) :tenure-id)))

(defn- mission-result
  [state result]
  (if (:ok? result)
    (let [receipt (:receipt result)]
      (accepted (state/with-mission state (:state result))
                (cond-> receipt
                  (nil? (:status receipt)) (assoc :status :accepted))
                (:effects result)))
    (refused (get-in result [:error :code])
             (get-in result [:error :message])
             (get-in result [:error :details]))))

(defn- root-task?
  [state tenure-id task-id]
  (boolean (get-in state [:mission :boards tenure-id :tasks task-id
                          :ledger-key])))

(defn- apply-board-command
  [state command ctx env]
  (cond
    (state/mission-completed? state)
    (refused :MISSION_COMPLETED
             "a completed mission accepts no board command"
             {:action :operator-message-required})

    (contains? #{:check-draft :claim-draft :release-draft} (:op command))
    (let [result (mission/apply-command (:mission state) command)]
      (if (:ok? result)
        (mission-result (if (= :check-draft (:op command))
                          state
                          (state/progress! state))
                        result)
        (mission-result state result)))

    :else
    (let [tenure-id (tenure-id-for state command)
          command (if (and (= :user-input (:op command))
                           (nil? (:actor-id command)))
                    (assoc command :actor-id
                           (state/control-agent-id state tenure-id))
                    command)
          actor-id (:actor-id command)
          actor (when actor-id
                  (get-in state [:mission :boards tenure-id :actors actor-id]))
          root-finish? (and (= :finish (:op command))
                            (root-task? state tenure-id (:task-id command)))
          request (if root-finish?
                    (merge {:op :finish-root-task
                            :tenure-id tenure-id
                            :board-facts (state/board-facts state)}
                           (select-keys command
                                        [:actor-id :task-id :claim-id
                                         :result-summary :read-set
                                         :verified-read-set :request-id])
                           (select-keys env [:write-gate-proof :verify-read-set]))
                    (cond-> {:op :board-command
                             :tenure-id tenure-id
                             :command command
                             :env env
                             :board-facts (state/board-facts state)}
                      (:request-id command)
                      (assoc :request-id (:request-id command))))]
      (if (and actor-id actor (not= :alive (:life-state actor)))
        (refused :ACTOR_INACTIVE
                 "board command actor is not alive"
                 {:actor-id actor-id})
        (let [result (mission/apply-command (:mission state) request)]
          (if (:ok? result)
            (mission-result (state/progress! state) result)
            (mission-result state result)))))))

(defn- apply-review
  [state {:keys [lane tenure-id findings call-id draft-disk-content]} _ctx]
  (if (state/mission-completed? state)
    (refused :MISSION_COMPLETED
             "a completed mission accepts no review; an operator message reopens it"
             {:action :operator-message-required})
    (let [result (mission/apply-command
                  (:mission state)
                  {:op :review
                   :lane lane
                   :tenure-id tenure-id
                   :findings findings
                   :call-id call-id
                   :draft-disk-content draft-disk-content
                   :board-facts (state/board-facts state)})]
      (if (:ok? result)
        (mission-result (state/progress! state) result)
        (mission-result state result)))))

(defn- apply-scope-ask-previous
  "Record one askPreviousScope receipt.  The answer is never in this
  synchronous receipt; consult delivery is a separate transition."
  [state payload ctx]
  (let [{:keys [state result]}
        (state/ask-previous-scope-state
         state
         (assoc payload :at (:now ctx)))]
    (if (:ok result)
      (accepted state (:receipt result)
                (when (seq (:outbox result)) (:outbox result)))
      (refused (:code result)
               (:message result)
               (:details result)))))

(declare append-terminal)

(defn- deliver-scope-ask-answer
  "Deliver one ask answer as an agent-attributed operator message.

  A stale generation (a newer reset beat settled after the ask was
  accepted) marks the ask `:stale` and drops the answer.  A delivered or
  stale ask refuses a second delivery."
  [state call-id answer now]
  (let [ask (get-in state [:scope :asks call-id])
        beat (peek (scope-ledger/reset-beats (state/scope-book state)))
        generation (:call-id beat)]
    (cond
      (nil? ask)
      (refused :ASK_NOT_FOUND
               "no askPreviousScope receipt exists for this call-id"
               {:call-id call-id})

      (contains? #{:delivered :stale} (:status ask))
      (refused :ASK_ALREADY_RESOLVED
               "the ask receipt is already resolved"
               {:call-id call-id
                :status (:status ask)})

      (not= (:generation ask) generation)
      (accepted (assoc-in state [:scope :asks call-id :status] :stale)
                {:status :accepted
                 :outcome :stale
                 :call-id call-id
                 :generation (:generation ask)
                 :current-generation generation})

      :else
      (let [state' (-> state
                       (assoc-in [:scope :asks call-id :status] :delivered)
                       (assoc-in [:scope :asks call-id :answer]
                                 {:text answer :at now})
                       (update :conversation conj
                               {:role :user
                                :text answer
                                :attribution "agent"
                                :ask-id call-id
                                :source :ask-previous-scope
                                :at now}))]
        (accepted state'
                  {:status :accepted
                   :outcome :delivered
                   :call-id call-id
                   :target-call-id (:target-call-id ask)})))))

(defn- apply-scope-ask-answer
  "Host/operator bypass that injects an ask answer without a consult call.

  This is the P1 delivery channel: until the ephemeral consult guard
  exists, a trusted caller submits `:scope/ask-answer` with the ask's
  call-id and the answer text."
  [state {:keys [call-id answer]} ctx]
  (if (str/blank? (str answer))
    (refused :ASK_ANSWER_REQUIRED
             "ask answer must be a non-blank string"
             {:call-id call-id})
    (deliver-scope-ask-answer state call-id answer (:now ctx))))

(defn- fail-scope-ask-consult
  "Mark the ask's consult failed without delivering an answer.

  A failed or cancelled consult leaves the ask `:status :failed`; the
  `:scope/ask-answer` command remains the manual delivery channel.  The
  consult call itself still receives its normal terminal record."
  [state ask-call-id now]
  (if (and (some? ask-call-id)
           (= :consulting (get-in state [:scope :asks ask-call-id :consult]))
           (= :accepted (get-in state [:scope :asks ask-call-id :status])))
    (-> state
        (assoc-in [:scope :asks ask-call-id :status] :failed)
        (assoc-in [:scope :asks ask-call-id :consult] :failed)
        (assoc-in [:scope :asks ask-call-id :failed-at] now))
    state))

(defn- apply-scope-consult-event
  "Route one model event for a scope-ask consult call.

  The consult is ephemeral: chunks only accumulate on the call record,
  tool calls are refused (the snapshot carries `:tools []`), and a
  `:completed` terminal hands its text to `deliver-scope-ask-answer`,
  which re-checks the generation before appending the agent-attributed
  steer to the conversation.  A stale generation drops the answer."
  [state message ctx current attempt-id now]
  (let [event (:event message)
        model-call-id (or (:model-call-id event) (:call-id event))
        ask-call-id (get-in current [:request :snapshot :scope-consult-for])
        call-attempt (state/normalize-attempt-id (:attempt-id current))
        event-attempt (state/normalize-attempt-id (:attempt-id event))
        same-attempt? (and (some? call-attempt)
                           (= event-attempt call-attempt))]
    (cond
      (not same-attempt?)
      {:state (state/append-diagnostic
               state
               {:code :STALE_MODEL_ATTEMPT
                :call-id model-call-id
                :attempt-id event-attempt
                :current-attempt-id (:attempt-id current)
                :at now})
       :receipt {:status :ignored :reason :stale-attempt}}

      (= :chunk (:event event))
      (let [text (or (:text event) "")]
        {:state (update-in state [:calls model-call-id :output] str text)
         :receipt {:status :accepted :outcome :chunk
                   :call-id model-call-id}})

      (= :tool-call (:event event))
      {:state (state/append-diagnostic
               state
               {:code :SCOPE_CONSULT_TOOL_CALL
                :message "a scope-ask consult call cannot invoke tools"
                :call-id model-call-id
                :ask-call-id ask-call-id
                :at now})
       :receipt {:status :ignored :reason :consult-read-only}}

      (= :completed (:event event))
      (let [answer (or (:text event)
                       (:output current)
                       (get-in current [:request :snapshot :output]))
            ;; Delivery first: it owns the ask transition and the
            ;; conversation append, including the generation re-check.
            delivered (deliver-scope-ask-answer state ask-call-id
                                                (str answer) now)
            ;; `deliver-scope-ask-answer` returns an accepted business
            ;; result or a bare refusal.  A refusal (unknown or already
            ;; resolved ask) leaves the Owner state untouched.
            state (or (:state delivered) state)
            state (append-terminal
                   state
                   {:call-id model-call-id
                    :attempt-id attempt-id
                    :status :succeeded
                    :payload (select-keys event [:text :usage])
                    :at now})]
        {:state state
         :receipt {:status :accepted
                   :outcome (or (get-in delivered [:receipt :outcome])
                                :delivered)
                   :ask-call-id ask-call-id
                   :call-id model-call-id}})

      (contains? #{:failed :cancelled} (:event event))
      (let [status (:event event)
            error (merge {:kind (if (= :failed status)
                                  :model-error :cancelled)
                          :message (if (= :failed status)
                                     "scope-ask consult failed"
                                     "scope-ask consult cancelled")}
                         (select-keys event [:partial?]))
            state (fail-scope-ask-consult state ask-call-id now)
            state (append-terminal
                   state
                   {:call-id model-call-id
                    :attempt-id attempt-id
                    :status (if (= :failed status) :failed :cancelled)
                    :error error
                    :at now})]
        {:state state
         :receipt {:status :accepted
                   :outcome :terminal-queued
                   :ask-call-id ask-call-id
                   :call-id model-call-id}})

      :else
      {:state (state/append-diagnostic
               state
               {:code :UNKNOWN_MODEL_EVENT
                :event (:event event)
                :at now})
       :receipt {:status :ignored :reason :unknown-event}})))

(defn- apply-mission-extension
  "Store one opaque mission extension in the same Owner CAS.

  Pair Hint uses this to keep its session, prefix chain and placements durable
  without adding a pair-hint-specific Owner state key."
  [state message _ctx]
  (mission-result
   state
   (mission/apply-command
    (:mission state)
    {:op :extensions-put
     :key (:key message)
     :value (:value message)})))

(defn- apply-plan-draft
  [state {:keys [path content tenure-id]} _ctx]
  (if (state/mission-completed? state)
    (refused :MISSION_COMPLETED
             "a completed mission accepts no Plan draft write"
             {:action :operator-message-required})
    (mission-result
     state
     (mission/apply-command
      (:mission state)
      {:op :set-draft
       :path path
       :content content
       :tenure-id tenure-id}))))

(defn- apply-plan-draft-claim
  [state {:keys [path request-id call-id tenure-id]} _ctx]
  (if (state/mission-completed? state)
    (refused :MISSION_COMPLETED
             "a completed mission accepts no Plan draft claim"
             {:action :operator-message-required})
    (mission-result
     state
     (mission/apply-command
      (:mission state)
      {:op :claim-draft
       :path path
       :request-id request-id
       :call-id call-id
       :tenure-id tenure-id}))))

(defn- apply-plan-draft-release
  [state {:keys [path request-id]} _ctx]
  (mission-result
   state
   (mission/apply-command
    (:mission state)
    {:op :release-draft
     :path path
     :request-id request-id})))

;; ---------------------------------------------------------------------------
;; Command handlers

(defn- ensure-open-run
  "A new real user message reopens a cancelled run.

  The old run stays in the tree as evidence; a new run becomes active and
  opens admission.  A run that is still cancelling keeps admission closed
  until its cancellation domain is quiescent: `cancel/finish-run` closes it
  only when no live execution resource remains, so starting the successor
  earlier would let a running Shell, workspace gate, or `:applying` program
  act under a second admission.  The user message, pending turn, and wake
  are recorded at once; the claim guards gate on admission and open the turn
  as soon as the old run closes."
  [state]
  (state/open-successor-run state))

(defn- attachment-input-problem
  [attachments]
  (some (fn [attachment]
          (cond
            (not (map? attachment))
            {:code :INVALID_ATTACHMENT
             :attachment attachment}

            (nil? (state/attachment-reference attachment))
            {:code :ATTACHMENT_REFERENCE_REQUIRED
             :attachment attachment}

            (not (or (state/attachment-content? attachment)
                     (:send-blob-id attachment)
                     (:blob-id attachment)))
            {:code :ATTACHMENT_CONTENT_REQUIRED
             :attachment-id (state/attachment-id attachment)}

            :else nil))
        attachments))

(defn- quote-input-problem
  [quotes]
  (some (fn [quote]
          (cond
            (not (map? quote))
            {:code :INVALID_QUOTE :quote quote}

            (nil? (or (:message-id quote) (:quote-id quote) (:id quote)))
            {:code :QUOTE_REFERENCE_REQUIRED :quote quote}

            (str/blank? (str (:text quote)))
            {:code :QUOTE_TEXT_REQUIRED :quote quote}

            :else nil))
        quotes))

(defn- input-parts
  [payload message]
  (let [text (str (or (:text payload) ""))
        attachments (vec (or (:attachments payload) []))
        quotes (vec (or (:quotes payload) []))
        citations (vec (or (:citations payload) []))
        draft (:draft payload)]
    {:text text
     :attachments attachments
     :quotes quotes
     :citations citations
     :draft draft
     :message-id (str "user-" (or (:command-id message)
                                  (count (:conversation {}))))}))

(defn- persist-draft
  [state draft now]
  (if (and (map? draft) (some? (:draft-id draft)))
    (assoc-in state [:drafts (:draft-id draft)]
              (assoc draft :updated-at now))
    state))

(defn- mirror-history-message!
  "Mirror one owner conversation message into the session history.

  The stable conversation message-id is preserved so edit/version/branch
  commands address the same message the client sees.  Re-appending an
  existing id is idempotent."
  [state payload ctx]
  (let [port (:session-port ctx)]
    (if-not port
      state
      (let [result ((:apply-command port)
                    (:session state)
                    (assoc payload :kind :history/append-message))]
        (if (:ok? result)
          (assoc state :session (:session result))
          (state/append-diagnostic
           state
           {:code :HISTORY_MIRROR_FAILED
            :message-id (:message-id payload)
            :error (:error result)
            :at (:now ctx)}))))))

(defn- mirror-outbound-run!
  "Freeze the accepted UI send's outbound projection in the session doc.

  The Owner owns the model turn; the session keeps the same run ledger the
  library send path writes, so the context panel's `:last-run` copy shows the
  real outbound snapshot instead of a preview."
  [state payload ctx]
  (let [port (:session-port ctx)]
    (if-not port
      state
      (let [result ((:apply-command port)
                    (:session state)
                    {:kind :session/record-outbound
                     :message-id (:message-id payload)
                     :turn-card-ids (:turn-card-ids payload)
                     :instruction-values (:instruction-values payload)
                     :temporary-read? (:temporary-read? payload)})]
        (if (:ok? result)
          (assoc state :session (:session result))
          (state/append-diagnostic
           state
           {:code :OUTBOUND_MIRROR_FAILED
            :message-id (:message-id payload)
            :error (:error result)
            :at (:now ctx)}))))))

(defn- mirror-assistant-history!
  "Mirror one successful assistant turn into session history.

  Regeneration runs already append a version to their target and are skipped.
  The owner run's stable message-id is reused, so the client edits the same
  message it rendered."
  [state current run event final ctx]
  (if (or (:session-run current)
          ;; An actor run's answer belongs to that agent's private context,
          ;; never to the session transcript.
          (:actor-attempt-id current)
          (nil? run)
          (str/blank? (str final)))
    state
    (mirror-history-message!
     state
     {:message-id (:message-id run)
      :role :assistant
      :content final
      :usage (:usage event)
      :citations (:citations event)
      :tool-records (:tool-records event)
      :nudge? (:nudge? current)
      :nudge-id (:nudge-id current)
      :run-id (:run-id run)}
     ctx)))

(defn- backfill-history-targets!
  "Lazily mirror only the conversation messages a history command targets.

  Older sessions predate the mirroring hook.  This avoids scanning or copying
  the whole conversation while still making a visible old message editable.
  Provenance markers are carried through so the snapshot projection can
  exclude Nudge answers."
  [state message-ids ctx]
  (let [wanted (set message-ids)]
    (if (empty? wanted)
      state
      (reduce
       (fn [state entry]
         (let [message-id (:message-id entry)
               role (:role entry)]
           (if (and (contains? wanted message-id)
                    (string? message-id)
                    (contains? #{:user :assistant :system} role)
                    (nil? (get-in state [:session :messages message-id])))
             (mirror-history-message!
              state
              {:message-id message-id
               :role role
               :content (or (:text entry) (:content entry) "")
               :attachments (:attachments entry)
               :citations (:citations entry)
               :tool-records (:tool-records entry)
               :usage (:usage entry)
               :nudge? (:nudge? entry)
               :nudge-id (:nudge-id entry)
               :run-id (:run-id entry)}
              ctx)
             state)))
       state
       (:conversation state)))))

(defn- promote-pending-model-binding
  "Make a `:next-round` session model selection current for this turn.

  The session layer promotes the same pending binding when it accepts a
  send.  A selection made while a turn is streaming stays pending until the
  next user turn; the running turn and its Nudge keep the identity frozen in
  their request snapshots.  A message that is refused later never commits
  this local state."
  [state]
  (let [pending (get-in state [:session :pending-model-binding :binding])]
    (if (and (map? pending)
             (some? (:connection-id pending))
             (some? (:model-entry-id pending)))
      (-> state
          (assoc-in [:session :model-binding] pending)
          (assoc-in [:session :pending-model-binding] nil))
      state)))

(defn- user-message
  [state payload message ctx]
  (let [{:keys [text attachments quotes citations draft message-id]}
        (input-parts payload message)
        policy (when (and (seq attachments) (:attachment-policy ctx))
                 (try
                   ((:attachment-policy ctx) attachments)
                   (catch Throwable error
                     {:ok? false
                      :error {:code :ATTACHMENT_POLICY_FAILED
                              :message (or (ex-message error)
                                           (str (class error)))} })))
        attachments (if (and policy (:ok? policy)
                             (sequential? (:attachments policy)))
                      (vec (:attachments policy))
                      attachments)
        entry {:role :user
               :message-id message-id
               :text text
               :attachments attachments
               :quotes quotes
               :citations citations
               :turn-card-ids (vec (or (:turn-card-ids payload) []))
               :instruction-values (or (:instruction-values payload) {})
               :draft draft
               :command-id (:command-id message)
               :operator? (operator-command? message)
               :chat? (true? (:chat? payload))
               :at (:now ctx)}
        state (promote-pending-model-binding state)
        ;; A `:chat?` turn is answered as plain assistant text: it carries
        ;; no program tool, so a tools-less model is a legal chat target.
        ;; The send gate must not demand Plan/Reviewer tool support for it.
        tools-problem (when-not (true? (:chat? payload))
                        (model-tools-problem state ctx))]
    (cond
      (and (str/blank? text) (empty? attachments) (empty? quotes))
      (refused :TEXT_OR_ATTACHMENT_REQUIRED
               "a user message needs text, an attachment, or a quote"
               nil)

      (attachment-input-problem attachments)
      (refused (get (attachment-input-problem attachments) :code)
               "an attachment needs a stable id and content or a blob reference"
               (attachment-input-problem attachments))

      (quote-input-problem quotes)
      (refused (get (quote-input-problem quotes) :code)
               "a quote needs a stable reference and text"
               (quote-input-problem quotes))

      (and policy (false? (:ok? policy)))
      (refused :ATTACHMENT_NOT_SENDABLE
               "attachments are not sendable with the selected model"
               (:error policy))

      tools-problem
      (refused (:code tools-problem)
               "The selected model does not declare tool support; Plan and Reviewer require their program tool. Select a model with tools and send again; your draft is preserved."
               tools-problem)

      (state/mission-completed? state)
      (if-not (operator-command? message)
        (refused :MISSION_COMPLETED_OPERATOR_ONLY
                 "a completed mission reopens only for a real operator message"
                 {:action :operator-message-required})
        (let [result (mission/apply-command
                      (:mission state)
                      {:op :reopen
                       :reason :operator-message
                       :user-input (if (str/blank? text) "[attachments]" text)
                       :user-attachments attachments
                       :request-id (str "reopen-" (:command-id message))
                       :board-facts (state/board-facts state)})]
          (if-not (:ok? result)
            (refused (get-in result [:error :code])
                     (get-in result [:error :message])
                     (get-in result [:error :details]))
            (let [state (state/with-mission state (:state result))
                  state (persist-draft state draft (:now ctx))
                  tenure (or (when-let [tenure-id
                                        (get-in result [:receipt :tenure-id])]
                               (state/tenure-by-id state tenure-id))
                             (state/latest-active-execution-tenure state))
                  state (-> state
                            (update :conversation conj entry)
                            (update :pending-turns conj
                                    (pending-turn
                                     (or (get-in result [:receipt :role])
                                         :reviewer)
                                     text message ctx (:tenure-id tenure)
                                     (select-keys payload
                                                  [:turn-card-ids
                                                   :instruction-values
                                                  :search :chat?
                                                  :temporary-read?]))))]
              (accepted (mirror-outbound-run!
                         (mirror-history-message!
                          state
                          {:message-id message-id
                           :role :user
                           :content text
                           :attachments attachments
                           :citations citations}
                          ctx)
                         {:message-id message-id
                          :turn-card-ids (:turn-card-ids payload)
                          :instruction-values (:instruction-values payload)
                          :temporary-read? (:temporary-read? payload)}
                         ctx)
                        (assoc (:receipt result)
                               :outcome :reopened
                               :reopened? true
                               :tenure-id (:tenure-id tenure)
                               :message-id message-id)
                        (:effects result))))))

      :else
      (let [;; A turn accepted while the run is still `:cancelling` needs a
            ;; deferred successor.  The intent must be read before
            ;; `ensure-open-run` runs: that call opens the successor of an
            ;; already `:cancelled` run and clears the marker.
            ;;
            ;; The guard that opens the deferred successor reads the committed
            ;; `:reopen-requested?` marker, so the marker must record this
            ;; message, not the marker itself.  Testing
            ;; `successor-run-pending?` here inverted the condition: it is
            ;; false exactly when the turn must wait, so the queued turn was
            ;; never reopened.
            ;; The marker is an intent to continue, not a second run-state
            ;; bit.  Read the run state before `ensure-open-run`: an already
            ;; `:cancelled` run is opened in this same transition, so it must
            ;; not re-set the marker after the successor clears it.
            defer? (state/run-cancelling? state)
            state (ensure-open-run state)
            state (persist-draft state draft (:now ctx))
            state (update state :conversation conj entry)
            state (state/progress! state)
            ;; A turn accepted while the run is still :cancelling is served by
            ;; open-successor-run-guard once the cancellation domain is
            ;; quiescent.  A run this message opened itself needs no request.
            state (if defer?
                    (assoc-in state [:cancel :reopen-requested?] true)
                    state)
            state (if-let [role (central-role state)]
                    (update state :pending-turns conj
                            (pending-turn
                             role text message ctx
                             (central-tenure-id state role)
                             (select-keys payload
                                          [:turn-card-ids :instruction-values
                                           :search :chat?
                                           :temporary-read?])))
                    state)
            state (if (in-swarm? state)
                    (reduce
                     (fn [state tenure]
                       (let [tenure-id (:tenure-id tenure)
                             board (get-in state
                                           [:mission :boards tenure-id])
                             actor-id (state/control-agent-id state
                                                              tenure-id)]
                         (if (and board actor-id)
                           (let [applied
                                 (mission/apply-command
                                  (:mission state)
                                  {:op :board-command
                                   :tenure-id tenure-id
                                   :command {:op :user-input
                                             :actor-id actor-id
                                             :text text
                                             :message-id message-id
                                             :attachments attachments}
                                   :env {}})]
                             (if (:ok? applied)
                               (state/with-mission state (:state applied))
                               (state/append-diagnostic
                                state
                                {:code :BOARD_INPUT_REJECTED
                                 :tenure-id tenure-id
                                 :error (get-in applied [:error :details])
                                 :at (:now ctx)})))
                           state)))
                     state
                     (state/execution-tenures state))
                    state)]
        (accepted (mirror-outbound-run!
                   (mirror-history-message!
                    state
                    {:message-id message-id
                     :role :user
                     :content text
                     :attachments attachments
                     :citations citations}
                    ctx)
                   {:message-id message-id
                    :turn-card-ids (:turn-card-ids payload)
                    :instruction-values (:instruction-values payload)
                    :temporary-read? (:temporary-read? payload)}
                   ctx)
                  {:status :accepted
                   :outcome :message-appended
                   :command-id (:command-id message)
                   :message-id message-id
                   :attachment-count (count attachments)}
                  [{:type :swarm/wake-all
                    :reasons [:user-input]
                    :at (:now ctx)}])))))

(defn- operator-cancel
  [state payload message ctx]
  (let [origin (:origin payload)
        reason (or (:reason payload) :user-cancel)]
    (cond
      (not (contains? #{:operator-esc :operator-stop} origin))
      (refused :NOT_OPERATOR_CANCEL
               "operator cancel needs :operator-esc or :operator-stop"
               {:origin origin})

      (not= :user-cancel reason)
      (refused :INVALID_CANCEL_REASON
               "operator cancel must use :user-cancel"
               {:reason reason})

      :else
      ;; A stop discards every queued turn.  The committed user messages
      ;; stay in `:conversation` as evidence, but no pending turn may be
      ;; replayed by a later `ensure-open-run`: "stop" means the session
      ;; waits for new operator input, not for the old queue.  The active
      ;; turn is cancelled by `:pending-cancels` below.
      (accepted (-> state
                    (assoc :pending-turns [])
                    (update :pending-cancels conj
                            {:command-id (:command-id message)
                             :origin origin
                             :at (:now ctx)}))
                {:status :accepted
                 :outcome :cancel-pending
                 :command-id (:command-id message)
                 :origin origin}))))

(defn- user-unpark
  [state payload ctx]
  (let [result (mission/apply-command
                (:mission state)
                {:op :resume
                 :lane (keyword (:lane payload))
                 :tenure-id (:tenure-id payload)})]
    (if (:ok? result)
      (let [state (state/with-mission state (:state result))
            resolved (cond-> {:type :hc/ask-resolved
                              :session-id (:session-id state)
                              :at (:now ctx)}
                       (:ask-id payload) (assoc :ask-id (:ask-id payload))
                       (:name payload) (assoc :name (:name payload)))]
        (accepted (state/append-outbox state [resolved])
                  (:receipt result)
                  (:effects result)))
      (refused (get-in result [:error :code])
               (get-in result [:error :message])
               (get-in result [:error :details])))))

(defn- report-ack
  [state payload ctx]
  (let [ack (:ack payload)
        result (delivery/apply-ack (:reports state) ack)]
    (if (:ok? result)
      (accepted (-> state
                    (assoc :reports (:state result))
                    (update :report-claims dissoc (:report-id ack)))
                {:status :accepted
                 :outcome (:outcome result)
                 :report-id (:report-id ack)
                 :at (:now ctx)})
      (refused (get-in result [:error :code])
               (get-in result [:error :message])
               (get-in result [:error :details])))))

(defn- report-register
  [state payload ctx]
  (let [result (delivery/register (:reports state) (:report payload))]
    (if (:ok? result)
      (let [report (:report result)]
        (accepted (-> state
                      (assoc :reports (:state result))
                      (update-in [:report-claims (:report-id report)]
                                 (fn [claim]
                                   ;; A duplicate register must not reset a
                                   ;; dispatching/delivered claim or its
                                   ;; attempt count; only a missing claim is
                                   ;; initialized.
                                   (or claim
                                       {:state :pending :attempts 0}))))
                  {:status :accepted
                   :outcome (:outcome result)
                   :report-id (:report-id report)
                   :at (:now ctx)}))
      (refused (get-in result [:error :code])
               (get-in result [:error :message])
               (get-in result [:error :details])))))

(defn- close-session-run!
  "Close a session run that could not be dispatched, never leaving it running."
  [state effect error ctx]
  (let [run-port (:session-run-port ctx)
        fail (:fail run-port)
        session-port (:session-port ctx)
        closed (cond
                 (fn? fail)
                 (fail (:session state) effect error)

                 session-port
                 ((:apply-command session-port)
                  (:session state)
                  {:kind :history/fail-run
                   :run-id (:run-id effect)
                   :reason (or (:code error) :model-dispatch-unavailable)
                   :error error})

                 :else nil)]
    (let [session (if (:ok? closed)
                    (:session closed)
                    (let [run-id (:run-id effect)
                          call-id (:call-id effect)
                          reason (or (:code error)
                                     :model-dispatch-unavailable)
                          session (:session state)
                          call (get-in session [:calls call-id])
                          attempts (set (:attempt-order call))
                          session' (-> session
                                       (assoc-in [:runs run-id :state]
                                                 :cancelled)
                                       (assoc-in [:runs run-id :cancel-reason]
                                                 reason)
                                       (assoc-in [:runs run-id :failure]
                                                 {:reason reason
                                                  :error error
                                                  :at (:now ctx)}))
                          session'' (cond-> session'
                                      call-id
                                      (assoc-in [:calls call-id :state]
                                                :cancelled))]
                      (reduce (fn [session attempt-id]
                                (assoc-in session
                                          [:attempts attempt-id :state]
                                          :cancelled))
                              session''
                              attempts)))]
      (-> (assoc state :session session)
        (state/append-diagnostic
         (merge {:code :SESSION_RUN_DISPATCH_FAILED
                 :run-id (:run-id effect)
                 :error error
                 :closed? true
                 :at (:now ctx)}))))))

(defn- start-session-run-calls
  "Turn one accepted session `:run-started` effect into a ready Owner model
  call.  The normal model guard claims and dispatches it."
  [state effects ctx]
  (reduce
   (fn [state effect]
     (if (= :run-started (:kind effect))
       (let [port (:session-run-port ctx)
             run-id (:run-id effect)
             owner-call-id (str "session-run-" run-id)]
         (if-not (and port (fn? (:start port)))
           (close-session-run!
            state effect
            {:code :SESSION_RUN_PORT_UNAVAILABLE
             :message "no session-run port is configured"}
            ctx)
           (let [result ((:start port) (:session state) effect {})]
             (if-not (:ok? result)
               (close-session-run! state effect (:error result) ctx)
               (let [session-run (assoc (:session-run result)
                                        :owner-call-id owner-call-id)
                     [state _call]
                     (state/create-call
                      state
                      {:call-id owner-call-id
                       :kind :model
                       :role :main
                       :lane :execution
                       :agent-id (str "session-run-" run-id)
                       :request (:request result)
                       :created-at (:now ctx)})]
                 (-> state
                     (assoc :session (:session result))
                     (assoc-in [:calls owner-call-id :session-run] session-run)))))))
       state))
   state
   effects))

(defn- session-command
  [state payload message ctx]
  (let [port (:session-port ctx)]
    (if-not port
      (refused :SESSION_PORT_UNAVAILABLE
               "no session port is configured"
               {:kind (:kind payload)})
      (if (and (= :history/regenerate (:kind payload))
               (not (state/admission-open? state)))
        (refused :ADMISSION_CLOSED
                 "history regeneration cannot start under a closed admission"
                 {:action :resync-state})
        (let [kind (:kind payload)
            targets (cond-> []
                      (:message-id payload)
                      (conj (:message-id payload))

                      (sequential? (:message-ids payload))
                      (into (:message-ids payload)))
            state (if (and (keyword? kind)
                           (= "history" (namespace kind))
                           (seq targets))
                    (backfill-history-targets! state targets ctx)
                    state)
            result ((:apply-command port) (:session state) payload)]
        (if-not (:ok? result)
          (let [error (or (:error result)
                          {:code :SESSION_COMMAND_REFUSED
                           :message "session command was refused"})]
            (refused (or (:code error) :SESSION_COMMAND_REFUSED)
                     (or (:message error) "session command was refused")
                     (:details error)))
          (let [state' (assoc state :session (:session result))
                draft-id (get-in result [:result :draft-id])
                confirm? (and (= :session/update-draft (:kind payload))
                              (some? draft-id))
                confirmed
                (when confirm?
                  ((:confirm-draft-saved port) (:session state')
                   {:draft-id draft-id}))
                state' (if (and confirm? (:ok? confirmed))
                         (assoc state' :session (:session confirmed))
                         state')
                receipt (cond-> (assoc (or (:result result) {})
                                       :status :accepted
                                       :command-id (:command-id message))
                          confirm? (assoc :draft-confirmed?
                                          (boolean (:ok? confirmed))))
                effects (vec (:effects result))
                state' (start-session-run-calls state' effects ctx)]
            (accepted state' receipt
                      (remove #(= :run-started (:kind %)) effects)))))))))

(defn- issue-epoch
  [state ctx]
  (let [result (commands/issue-epoch (:registry ctx) (:commands state))]
    (accepted (assoc state :commands (:state result))
              {:status :accepted
               :outcome :epoch-issued
               :epoch (:epoch result)
               :retired-epoch (:retired-epoch result)})))

(defn- awaiting-tenures
  [state]
  (->> (concat (when-let [plan (relay/current-tenure (:relay state) :plan)]
                 [plan])
               (vals (state/execution-tenures state)))
       (filter :awaiting-user?)))

(defn- answer-ask
  [state payload message ctx]
  (let [lane (some-> (:lane payload) keyword)
        tenure-id (:tenure-id payload)
        candidates (cond->> (awaiting-tenures state)
                     tenure-id (filter #(= tenure-id (:tenure-id %)))
                     lane (filter #(= lane (:lane %))))
        tenure (when (= 1 (count candidates)) (first candidates))
        answer (or (:text payload) (:value payload) (:answer payload))]
    (cond
      (empty? candidates)
      (if (or (some? lane) (some? tenure-id))
        (refused :ASK_TENURE_MISMATCH
                 "the answer names a tenure that is not parked; it was refused"
                 {:lane lane
                  :tenure-id tenure-id
                  :parked (mapv (fn [t]
                                  {:tenure-id (:tenure-id t)
                                   :lane (:lane t)})
                                (awaiting-tenures state))})
        (refused :NOT_AWAITING_ANSWER
                 "no tenure is waiting for a human answer"
                 {}))

      (nil? tenure)
      (refused :AMBIGUOUS_ASK
               "several tenures await an answer; select :lane or :tenure-id"
               {:candidates
                (mapv (fn [t]
                        {:tenure-id (:tenure-id t)
                         :lane (:lane t)
                         :milestone (:milestone t)})
                      candidates)})

      (not (and (string? answer) (not (str/blank? answer))))
      (refused :ANSWER_REQUIRED
               "answer needs non-blank :text or :value"
               nil)

      :else
      (let [result (mission/apply-command
                    (:mission state)
                    {:op :resume
                     :lane (:lane tenure)
                     :tenure-id (:tenure-id tenure)})]
        (if (:ok? result)
          (let [state (-> state
                          (state/with-mission (:state result))
                          (update :conversation conj
                                  {:role :user
                                   :text answer
                                   :command-id (:command-id message)
                                   :unparked-tenure-id (:tenure-id tenure)
                                   :at (:now ctx)})
                          (update :pending-turns conj
                                  (pending-turn
                                   (:role tenure) answer message ctx
                                   (:tenure-id tenure)
                                   (select-keys payload
                                                [:turn-card-ids
                                                 :instruction-values
                                                 :search]))))
                resolved (cond-> {:type :hc/ask-resolved
                                  :session-id (:session-id state)
                                  :lane (:lane tenure)
                                  :tenure-id (:tenure-id tenure)
                                  :at (:now ctx)}
                           (:ask-id payload) (assoc :ask-id (:ask-id payload))
                           (:name payload) (assoc :name (:name payload)))]
            (accepted (state/append-outbox state [resolved])
                      (assoc (:receipt result)
                             :outcome :answer-accepted
                             :tenure-id (:tenure-id tenure))))
          (refused (get-in result [:error :code])
                   (get-in result [:error :message])
                   (get-in result [:error :details])))))))

;; ---------------------------------------------------------------------------
;; Multi-client presence (root AGENTS.md §5.1)

(defn- command-client-id
  "Return the stable client identity carried by a command.

  The gateway supplies `:client-id` from the handshake and `:uid` from the
  transport subscription.  `:client-id` is the stable browser identity and
  wins; `:uid` is the fallback for older gateways.  A command without either
  is an internal or legacy command and is not fenced by presence."
  [message]
  (let [value (or (:client-id message) (:uid message))]
    (when (some? value)
      (let [value (if (string? value) value (str value))]
        (when-not (str/blank? value) value)))))

(defn- presence-snapshot
  "The current presence and active-run facts a (re)attaching client needs."
  [state]
  (let [run-id (get-in state [:cancel :active-run-id])
        run (get-in state [:cancel :nodes run-id])]
    {:controller-id (:controller-id state)
     :observer-ids (vec (:observer-ids state))
     :run-id run-id
     :run-state (:state run)}))

(defn- presence-receipt
  [state outcome extra]
  (merge (assoc (presence-snapshot state) :status :accepted :outcome outcome)
         extra))

(defn- attach-client
  [state client-id]
  (cond
    (nil? client-id)
    (refused :CLIENT_ID_REQUIRED
             "attaching a client needs :client-id"
             {:action :reconnect})

    (= client-id (:controller-id state))
    (accepted state (presence-receipt state :controller nil))

    (nil? (:controller-id state))
    (let [state (-> state
                    (assoc :controller-id client-id)
                    (update :observer-ids
                            (fn [ids] (vec (remove #{client-id} ids)))))]
      (accepted state (presence-receipt state :controller nil)))

    (some #{client-id} (:observer-ids state))
    (accepted state (presence-receipt state :observer nil))

    :else
    (let [state (update state :observer-ids (fnil conj []) client-id)]
      (accepted state (presence-receipt state :observer nil)))))

(defn- takeover-client
  "Transfer control to CLIENT-ID.

  The previous controller becomes an observer at the front of the queue so
  a later detach promotes the longest-waiting observer.  Takeover is the
  only command an observer may use to become the controller."
  [state client-id]
  (cond
    (nil? client-id)
    (refused :CLIENT_ID_REQUIRED
             "taking control needs :client-id"
             {:action :reconnect})

    (= client-id (:controller-id state))
    (accepted state (presence-receipt state :already-controller nil))

    :else
    (let [previous (:controller-id state)
          observers (vec (remove #{client-id} (:observer-ids state)))
          observers (if (and (some? previous) (not= previous client-id))
                      (into [previous] observers)
                      observers)
          state (assoc state
                       :controller-id client-id
                       :observer-ids observers)]
      (accepted state
                (presence-receipt state :taken-over
                                  {:previous-controller-id previous})))))

(defn- detach-client
  "Remove one client from the session presence.

  Detach is presence-only: it never cancels a run, withdraws a command, or
  changes the model/board state.  If the controller leaves, the first
  observer is promoted; if none remains, the session has no controller and
  the next attaching client becomes one."
  [state client-id]
  (cond
    (nil? client-id)
    (refused :CLIENT_ID_REQUIRED
             "detaching a client needs :client-id"
             {:action :reconnect})

    (= client-id (:controller-id state))
    (let [observers (vec (remove #{client-id} (:observer-ids state)))
          promoted (first observers)
          state (assoc state
                       :controller-id promoted
                       :observer-ids (vec (rest observers)))]
      (accepted state
                (presence-receipt state :detached
                                  {:promoted-controller-id promoted})))

    (some #{client-id} (:observer-ids state))
    (let [state (update state :observer-ids
                        (fn [ids] (vec (remove #{client-id} ids))))]
      (accepted state (presence-receipt state :detached nil)))

    :else
    (accepted state (presence-receipt state :not-attached nil))))

(defn- controller-allowed?
  "True when CLIENT-ID may submit KIND.

  A command without a client id is internal/legacy.  A session with no
  controller promotes the first client that submits work.  Observers may
  only submit the presence commands; every other command is refused with
  `:NOT_CONTROLLER` instead of being silently dropped."
  [state client-id kind]
  (or (contains? client-presence-kinds kind)
      (nil? client-id)
      (nil? (:controller-id state))
      (= client-id (:controller-id state))))

(defn- promote-first-client
  [state client-id kind]
  (if (and (some? client-id)
           (nil? (:controller-id state))
           (not (contains? client-presence-kinds kind)))
    (assoc state :controller-id client-id)
    state))

;; ---------------------------------------------------------------------------
;; Trusted worker program command

(defn- board-claim
  [state claim-id]
  (some (fn [[tenure-id board]]
          (when-let [claim (get-in board [:claims claim-id])]
            {:tenure-id tenure-id :board board :claim claim}))
        (get-in state [:mission :boards])))

(defn- worker-program-outcome
  [result]
  (let [value (:value result)
        status (or (:status value) (:status result) (:outcome value))]
    (cond
      (contains? #{:APPLIED :applied :succeeded :ok :completed} status)
      :applied

      (contains? #{:FILE_CONFLICT :file-conflict} status)
      :file-conflict

      (contains? #{:PREPARE_REJECTED :prepare-rejected :CLAIM_CHANGED
                   :ACTOR_INACTIVE :WRITE_GATE_REQUIRED
                   :CLAIM_TASK_MISMATCH :TENURE_RETIRED :UNKNOWN_TENURE}
                 status)
      :prepare-rejected

      :else :failed)))

(defn- worker-run-program
  "Run one worker program on a claimed board task.

  This is a trusted command-plane path for headless/server validation.  It
  never guesses an actor: the claim must exist in the mission board, belong
  to TASK-ID, and be held by a live actor.  The injected
  `:worker-run-program-fn` calls `hc.compose.workspace/run-program!` with the
  actor/claim and the protected workspace bridge.  Browser clients are
  forbidden at the gateway; the Owner only enforces the board identity."
  [state payload message ctx]
  (let [task-id (:task-id payload)
        claim-id (:claim-id payload)
        source (or (:program-source payload) (:source payload)
                   (:program payload))
        intent (or (:intent payload) "worker program")
        found (board-claim state claim-id)
        board (:board found)
        claim (:claim found)
        task (get-in board [:tasks task-id])
        agent-id (:agent-id claim)
        actor (get-in board [:actors agent-id])]
    (cond
      (not (and (some? task-id)
                (some? claim-id)
                (string? source)
                (not (str/blank? source))))
      (refused :WORKER_PROGRAM_INVALID
               "worker program needs :task-id, :claim-id and :program-source"
               {:task-id task-id :claim-id claim-id})

      (nil? found)
      (refused :UNKNOWN_CLAIM
               "the claim does not exist in any mission board"
               {:claim-id claim-id})

      (not= task-id (:task-id claim))
      (refused :CLAIM_TASK_MISMATCH
               "the claim belongs to a different task"
               {:claim-id claim-id
                :claim-task-id (:task-id claim)
                :task-id task-id})

      (not= :alive (:life-state actor))
      (refused :ACTOR_INACTIVE
               "the claiming actor is not alive"
               {:claim-id claim-id :agent-id agent-id})

      (not= claim-id (:claim-id task))
      (refused :CLAIM_CHANGED
               "the task no longer holds this claim"
               {:task-id task-id
                :expected claim-id
                :current (:claim-id task)})

      :else
      (let [tenure-id (:tenure-id found)
            job-id (str "worker-job-" (random-uuid))
            request {:session-id (:session-id state)
                     :tenure-id tenure-id
                     :task-id task-id
                     :claim-id claim-id
                     :agent-id agent-id
                     :holder? (= agent-id
                                  (get-in board [:token-slot :holder-id]))
                     :workspace (actors/workspace (:actors state)
                                                  tenure-id)
                     :program-source source
                     :intent intent
                     :command-id (:command-id message)
                     :attempt-id (:command-id message)}
            state (-> state
                      (assoc-in [:worker-jobs job-id]
                                {:job-id job-id
                                 :state :scheduled
                                 :request request
                                 :task-id task-id
                                 :claim-id claim-id
                                 :agent-id agent-id
                                 :created-at (:now ctx)})
                      (state/append-outbox
                       [{:type :worker/run-program
                         :job-id job-id
                         :request request
                         :at (:now ctx)}]))]
        ;; The runtime drains the outbox and runs the program outside the
        ;; mailbox; this receipt is the scheduling acknowledgement.
        (accepted state
                  {:status :accepted
                   :outcome :scheduled
                   :job-id job-id
                   :task-id task-id
                   :claim-id claim-id
                   :agent-id agent-id})))))

(defn- owner-compact
  [state ctx]
  (if-let [store (:blob-store ctx)]
    (let [[state' stats] (compact/compact-state state store)]
      (accepted state' {:status :accepted
                        :outcome :compacted
                        :stats stats}))
    (refused :COMPACTION_UNAVAILABLE
             "no blob store is configured for owner-state compaction"
             {:remedy :configure-blob-store})))

(def ^:private call-ack-outcomes
  "Operator conclusions for one :outcome-unknown call.

  `:succeeded` means the operator checked the external target and the action
  did happen; `:not-occurred` means it did not.  Both are terminal.  The
  action is never replayed: the operator must start a new request if the work
  is still needed."
  #{:succeeded :not-occurred})

(defn- ack-call
  "Close one :outcome-unknown call after an explicit operator check.

  The call id and its current state are the compare-and-set facts.  A stale
  or non-unknown call is refused with the current state; an identical repeat
  returns the same acknowledgement without changing the call again."
  [state payload message ctx]
  (let [call-id (some-> (:call-id payload) str)
        outcome (some-> (or (:outcome payload) (:result payload)) keyword)
        expected (some-> (:expected-state payload) keyword)
        note (when (string? (:note payload))
               (subs (:note payload) 0 (min 500 (count (:note payload)))))
        call (state/call state call-id)]
    (cond
      (not (operator-command? message))
      (refused :CALL_ACK_OPERATOR_ONLY
               "only a real operator may close an unknown call"
               {:action :operator-message-required})

      (not (and call-id (not (str/blank? call-id))))
      (refused :CALL_ACK_INVALID
               "call acknowledgement needs a non-blank :call-id"
               {:call-id call-id})

      (not (contains? call-ack-outcomes outcome))
      (refused :CALL_ACK_INVALID
               "acknowledgement outcome must be :succeeded or :not-occurred"
               {:outcome outcome :allowed (vec call-ack-outcomes)})

      (nil? call)
      (refused :UNKNOWN_CALL
               "no call with this id exists in the current state"
               {:call-id call-id})

      (and expected (not= expected (:state call)))
      (refused :CALL_STATE_CHANGED
               "the call is no longer in the state the acknowledgement expected"
               {:call-id call-id
                :expected expected
                :current (:state call)})

      (not= :outcome-unknown (:state call))
      (if (and (map? (:ack call))
               (= outcome (get-in call [:ack :outcome])))
        (accepted state
                  {:status :accepted
                   :outcome :already-acknowledged
                   :call-id call-id
                   :call-state (:state call)
                   :ack (:ack call)})
        (refused :CALL_NOT_UNKNOWN
                 "only an :outcome-unknown call can be acknowledged"
                 {:call-id call-id :current (:state call)}))

      :else
      (let [next-state (case outcome :succeeded :succeeded :not-occurred :failed)
            ack {:outcome outcome
                 :by :operator
                 :at (:now ctx)
                 :note note}
            call' (-> call
                      (assoc :state next-state
                             :ack ack
                             :acknowledged-at (:now ctx))
                      (assoc-in [:retry :phase] next-state)
                      (assoc-in [:ack :command-id] (:command-id message)))]
        (accepted (state/put-call state call')
                  {:status :accepted
                   :outcome :acknowledged
                   :call-id call-id
                   :call-state next-state
                   :ack ack})))))

(defn- apply-command-kind
  [state kind payload message ctx]
  (case kind
    :user/message (user-message state payload message ctx)
    :send-message (user-message
                   state
                   (assoc payload :text (or (:text payload)
                                            (:content payload)))
                   message ctx)
    :operator/cancel (operator-cancel state payload message ctx)
    :stop-run (operator-cancel
               state
               (merge {:origin :operator-stop :reason :user-cancel} payload)
               message ctx)
    :answer-ask (answer-ask state payload message ctx)
    :review/submit (if (operator-command? message)
                     (refused :REVIEW_OPERATOR_FORBIDDEN
                              "browser-originated review is forbidden"
                              {:action :trusted-reviewer-only})
                     (apply-review state {:lane (keyword (:lane payload))
                                          :tenure-id (:tenure-id payload)
                                          :findings (:findings payload)
                                          :call-id (:command-id message)
                                          :draft-disk-content
                                          (:draft-disk-content payload)}
                                   ctx))
    :scope/ask-previous
    (apply-scope-ask-previous state payload ctx)
    :scope/ask-answer
    (apply-scope-ask-answer state payload ctx)
    :plan/write-draft (apply-plan-draft state payload ctx)
    :plan/claim-draft (apply-plan-draft-claim state payload ctx)
    :plan/release-draft (apply-plan-draft-release state payload ctx)
    :user/unpark (user-unpark state payload ctx)
    :board/command (if (operator-command? message)
                     (refused :BOARD_OPERATOR_FORBIDDEN
                              "browser-originated board command is forbidden"
                              {:action :trusted-worker-only})
                     (apply-board-command state (:command payload) ctx
                                          (:env message)))
    :worker/run-program
    (if (operator-command? message)
      (refused :WORKER_PROGRAM_OPERATOR_FORBIDDEN
               "browser-originated worker program is forbidden"
               {:action :trusted-worker-only})
      (worker-run-program state payload message ctx))
    :call/ack (ack-call state payload message ctx)
    :owner/compact
    (if (operator-command? message)
      (refused :COMPACT_OPERATOR_FORBIDDEN
               "browser-originated compaction is forbidden"
               {:action :trusted-worker-only})
      (owner-compact state ctx))
    :report/ack (if (operator-command? message)
                  (refused :REPORT_OPERATOR_FORBIDDEN
                           "browser-originated report ack is forbidden"
                           {:action :trusted-supervisor-only})
                  (report-ack state payload ctx))
    :report/register (if (operator-command? message)
                       (refused :REPORT_OPERATOR_FORBIDDEN
                                "browser-originated report registration is forbidden"
                                {:action :trusted-supervisor-only})
                       (report-register state payload ctx))
    :devops-request
    (mission-result state
                    (mission/apply-command
                     (:mission state)
                     (assoc payload :op :devops-request)))
    :devops-complete
    (mission-result state
                    (mission/apply-command
                     (:mission state)
                     (assoc payload :op :devops-complete)))
    :session/attach-client
    (attach-client state (command-client-id message))
    :session/takeover
    (takeover-client state (command-client-id message))
    :session/detach-client
    (detach-client state (command-client-id message))
    :session/issue-epoch (issue-epoch state ctx)
    (if (session-command-kind? kind)
      (session-command state payload message ctx)
      (refused :UNKNOWN_COMMAND_KIND
               "unknown command kind"
               {:kind kind}))))

(defn- apply-command
  [state message ctx]
  (let [payload (:payload message)
        kind (when (map? payload) (:kind payload))
        client-id (command-client-id message)
        registry (:registry ctx)
        ;; The client identity is part of the command payload for the gate:
        ;; two clients that happen to use the same command id are a payload
        ;; conflict, never a replay of another client's receipt.
        gate-payload (cond-> payload
                       (some? client-id) (assoc :client-id client-id))
        request {:command-id (:command-id message)
                 :command-epoch (:command-epoch message)
                 :payload gate-payload}
        placeholder {:status :accepted
                     :command-id (:command-id message)
                     :command-epoch (:command-epoch message)}
        gate (commands/accept-with-receipt registry (:commands state)
                                           request placeholder)]
    (cond
      (not (or (contains? command-kinds kind)
               (session-command-kind? kind)))
      {:state state
       :receipt {:ok? false
                 :error (:error (refused :UNKNOWN_COMMAND_KIND
                                         "unknown command kind"
                                         {:kind kind}))}}

      (not (controller-allowed? state client-id kind))
      {:state state
       :receipt {:ok? false
                 :error (:error (refused :NOT_CONTROLLER
                                         "this client is observing; take control first"
                                         {:client-id client-id
                                          :controller-id (:controller-id state)
                                          :action :takeover}))}}

      (not (:ok? gate))
      {:state state
       :receipt {:ok? false :error (:error gate)}}

      (:replayed? gate)
      {:state state
       :receipt (cond-> (assoc (:receipt gate) :replayed? true)
                  ;; Presence is live state, not a historical receipt.  A
                  ;; replay still reports who controls the session now.
                  (contains? client-presence-kinds kind)
                  (merge (presence-snapshot state)))}

      :else
      (let [state (promote-first-client state client-id kind)
            business (apply-command-kind state kind payload message ctx)]
        (if-not (:ok? business)
          {:state state
           :receipt {:ok? false :error (:error business)}}
          (let [business-state (:state business)
                commands-changed? (not= (:commands state)
                                        (:commands business-state))
                commands-state
                (if commands-changed?
                  ;; `:session/issue-epoch` replaces the whole gate state.
                  (:commands business-state)
                  (assoc-in (:state gate)
                            [:receipts (:command-id message) :receipt]
                            (:receipt business)))]
            {:state (assoc business-state :commands commands-state)
             :receipt (:receipt business)
             :outbox (:outbox business)}))))))

;; ---------------------------------------------------------------------------
;; Internal messages

(defn- append-terminal
  [state terminal]
  (let [terminal (update terminal :attempt-id state/normalize-attempt-id)
        duplicate? (some #(and (= (:call-id %) (:call-id terminal))
                               (= (:attempt-id %) (:attempt-id terminal))
                               (= (:status %) (:status terminal)))
                         (:terminals state))]
    (if duplicate?
      state
      (update state :terminals conj terminal))))

(defn- tool-kind
  [payload]
  (let [kind (or (:executor payload) (:kind payload))]
    (if (contains? #{:tool :shell :workspace :devops} kind)
      kind
      :tool)))

(defn- tool-refusal-key
  [tenure-id agent-id member]
  [tenure-id agent-id member])

(defn- record-tool-refusal
  "Count one admission refusal for this tenure/agent/member.

  The count is consecutive: a successful admission for the same key clears
  it.  The Nudge guard reads the count and blocks after the documented
  limit, so a model that keeps omitting `:scope` cannot loop forever."
  [state tenure-id agent-id member admission now]
  (let [refusal-key (tool-refusal-key tenure-id agent-id member)
        count (inc (long (get-in state [:tool-refusals refusal-key :count] 0)))
        state (assoc-in state [:tool-refusals refusal-key]
                        {:count count
                         :last-code (:code admission)
                         :member member
                         :tenure-id tenure-id
                         :agent-id agent-id
                         :at now})
        ;; Bound current-state growth.  Old tenures/agents are evidence only;
        ;; the newest 64 keys keep every live streak and the reported set is
        ;; pruned to the same keys.
        keep-keys (->> (:tool-refusals state)
                       (sort-by (fn [[_ value]] (long (or (:at value) 0))) >)
                       (take 64)
                       (map first)
                       set)
        state (update state :tool-refusals
                      (fn [entries]
                        (into {} (filter (fn [[k _]] (keep-keys k)) entries))))
        state (update state :actor-refusal-reported
                      (fn [reported]
                        (set (filter keep-keys reported))))]
    state))

(defn- clear-tool-refusal
  [state tenure-id agent-id member]
  (let [refusal-key (tool-refusal-key tenure-id agent-id member)]
    (-> state
        (update :tool-refusals dissoc refusal-key)
        (update :actor-refusal-reported disj refusal-key))))

(defn- final-run-text
  [run event-text]
  (let [accumulated (str (or (:text run) ""))
        final (str (or event-text ""))]
    (cond
      (str/blank? final) accumulated
      (str/starts-with? final accumulated) final
      (str/starts-with? accumulated final) accumulated
      :else accumulated)))

(defn- tool-call-summary-text
  "Readable stand-in for a finished turn that only invoked tools.

  A model round can end with tool calls and no text; the assistant
  message would otherwise render empty even though the turn did real
  work.  Name the tools so the operator sees what ran.  Names come from
  the call records first and from the conversation's `:tool-call`
  entries second, so a refused call still counts."
  [state run conversation]
  (let [ids (vec (distinct (:tool-call-ids run)))
        id-set (set ids)
        call-names (into {}
                         (keep (fn [call-id]
                                 (when-let [call (state/call state call-id)]
                                   [call-id (or (:member call)
                                                (:name call)
                                                (when (string? call-id)
                                                  call-id))])))
                         ids)
        entry-names (into {}
                          (keep (fn [entry]
                                  (when (and (= :tool-call (:role entry))
                                             (contains? id-set (:call-id entry)))
                                    [(:call-id entry)
                                     (or (:member entry) (:name entry)
                                         (:call-id entry))])))
                          conversation)
        names (->> ids
                   (keep #(or (get call-names %) (get entry-names %)))
                   distinct
                   vec)]
    (when (seq names)
      (str "已调用工具：" (str/join "、" names)))))

(defn- note-run-tool-call
  "Append TOOL-CALL-ID to the run's tool-call list when the run exists.

  A `:tool-call` event can arrive before any chunk created the run; in
  that case there is no assistant message to annotate yet, so the id is
  simply not recorded."
  [state id tool-call-id]
  (if (get-in state [:runs id])
    (update-in state [:runs id :tool-call-ids] (fnil conj []) tool-call-id)
    state))

(defn- record-actor-terminal
  "Feed one model terminal back into the actor slot lifecycle.

  Only calls created by the wake-actors guard carry :actor-attempt-id.  The
  mission and actor state are replaced together; the caller still appends the
  normal call terminal afterwards."
  [state current event text]
  (if-let [attempt-id (:actor-attempt-id current)]
    (let [result (try
                   (actors/accept-model-event
                    (:mission state) (:actors state) attempt-id
                    {:event (:event event)
                     :text text
                     :message (get-in event [:error :message])})
                   (catch Throwable error
                     {:ok? false
                      :error {:code :ACTOR_TERMINAL_EXCEPTION
                              :message (or (ex-message error)
                                           (str (class error)))} }))]
      (if (:ok? result)
        (-> state
            (assoc :mission (:mission result))
            (assoc :actors (:actors result)))
        (state/append-diagnostic
         state
         (merge {:code :ACTOR_TERMINAL_REJECTED
                 :call-id (:call-id current)
                 :actor-attempt-id attempt-id}
                (:error result)))))
    state))

(defn- actor-terminal-event
  "Translate one Owner :call/terminal status into the actor event vocabulary."
  [{:keys [status payload error]}]
  (case status
    :succeeded
    {:event :completed
     :text (or (get-in payload [:text])
               (when (string? payload) payload)
               "")}

    :failed
    {:event :failed
     :error (or error
                {:kind :model-error
                 :message "model call failed"})}

    :cancelled
    {:event :cancelled
     :error (or error
                {:kind :cancelled
                 :message "model call cancelled"})}

    :outcome-unknown
    {:event :failed
     :error (or error
                {:kind :outcome-unknown
                 :message "model outcome is unknown"})}

    {:event :failed
     :error (or error
                {:kind :model-error
                 :message (str "model call " (name (or status :failed)))})}))

(defn- accept-session-run-terminal
  "Route a terminal Owner model event to the session run, when this call was
  created by an explicit history regeneration."
  [state current event ctx]
  (if-let [session-run (:session-run current)]
    (let [port (:session-run-port ctx)]
      (if-not port
        (state/append-diagnostic
         state {:code :SESSION_RUN_PORT_UNAVAILABLE
                :run-id (:run-id session-run) :at (:now ctx)})
        (let [result (try
                       ((:accept port) (:session state)
                        {:event event :session-run session-run})
                       (catch Throwable error
                         {:ok? false
                          :error {:code :SESSION_RUN_ACCEPT_EXCEPTION
                                  :message (or (ex-message error)
                                               (str (class error)))} }))]
          (if (:ok? result)
            (assoc state :session (:session result))
            (state/append-diagnostic
             state {:code :SESSION_RUN_ACCEPT_FAILED
                    :run-id (:run-id session-run)
                    :error (:error result)
                    :at (:now ctx)})))))
    state))

(defn- model-event-terminal?
  "True when the event fixes the attempt's outcome.

  Only these events let `accept-terminal` clear the attempt node's
  `:resource-alive?` claim.  A chunk observes nothing."
  [event]
  (contains? #{:completed :failed :cancelled} event))

(defn- late-event-outcome
  "The cancellation-terminal outcome one late model event observes."
  [event]
  (case event
    :completed :succeeded
    :failed :failed
    :cancelled :cancelled
    :outcome-unknown))

(defn- apply-model-event
  [state message ctx]
  (let [event (:event message)
        model-call-id (or (:model-call-id event) (:call-id event))
        event-attempt-id (state/normalize-attempt-id (:attempt-id event))
        current (state/call state model-call-id)
        current-attempt-id (state/normalize-attempt-id (:attempt-id current))
        same-attempt? (and current
                           (= event-attempt-id current-attempt-id))
        attempt-id (if same-attempt?
                     current-attempt-id
                     event-attempt-id)
        now (:now ctx)
        id (state/run-id model-call-id attempt-id)
        run (state/run-for-attempt state model-call-id attempt-id)]
    (cond
      ;; A scope-ask consult call is ephemeral: its chunks never open a run,
      ;; its tools are refused, and its terminal delivers the answer through
      ;; `deliver-scope-ask-answer` instead of writing an assistant message.
      (and current
           (some? (get-in current [:request :snapshot
                                   :scope-consult-for])))
      (apply-scope-consult-event state message ctx current attempt-id now)

      (nil? current)
      {:state (state/append-diagnostic
               state
               {:code :UNKNOWN_MODEL_CALL
                :call-id model-call-id
                :at now})
       :receipt {:status :ignored :reason :unknown-call}}

      (not same-attempt?)
      {:state (if run
                ;; A cancellation clears the call's `:attempt-id`, so the
                ;; terminal that arrives afterwards no longer matches and is
                ;; kept as evidence only.  The attempt node it names is still
                ;; `:resource-alive? true`, and `finish-run` will refuse to
                ;; close the run until that flag is cleared.  Settling the
                ;; observation here clears only the resource claim; the call,
                ;; the run text, and the fixed outcome are not rewritten.
                (let [settled (if (model-event-terminal? (:event event))
                                (state/accept-node state id attempt-id
                                                   (late-event-outcome event)
                                                   (:text event) (:error event) now)
                                state)
                      ;; The call node owns a resource flag too.  The
                      ;; cancellation walk may already have fixed it to
                      ;; `:cancelled` while leaving `:resource-alive? true`;
                      ;; the attempt node above is not enough.  Clear the call
                      ;; node only when no other live attempt still backs
                      ;; this call, using the same rule as the P1 terminal
                      ;; branch.
                      call-node (get-in settled [:cancel :nodes model-call-id])
                      live-attempt-id (state/normalize-attempt-id
                                       (:attempt-id current))
                      terminal-attempt-id (state/normalize-attempt-id
                                           attempt-id)
                      resolved-attempt-id (or live-attempt-id
                                              terminal-attempt-id)
                      call-settled (if (and (model-event-terminal? (:event event))
                                            call-node
                                            (= :call (:kind call-node))
                                            (= :cancelled (:state call-node))
                                            (or (nil? live-attempt-id)
                                                (= live-attempt-id
                                                   resolved-attempt-id)))
                                      (assoc-in settled
                                                [:cancel :nodes model-call-id
                                                 :resource-alive?]
                                                false)
                                      settled)]
                  (state/record-late-run-event call-settled id
                                               {:event (:event event)} now))
                (state/append-diagnostic
                 state
                 {:code :STALE_MODEL_ATTEMPT
                  :call-id model-call-id
                  :attempt-id attempt-id
                  :current-attempt-id (:attempt-id current)
                  :at now}))
       :receipt {:status :ignored :reason :stale-attempt}}

      :else
      (case (:event event)
        :chunk
        (let [text (or (:text event) "")
              state (state/ensure-run state model-call-id attempt-id now)
              state (state/append-run-text state id text now)
              run (get-in state [:runs id])
              call (get-in state [:calls model-call-id])
              state (if (and run
                             (not (:session-run call))
                             (not (:actor-attempt-id call)))
                      (mirror-history-message!
                       state
                       {:message-id (:message-id run)
                        :role :assistant
                        :content (str (:text run))
                        :nudge? (:nudge? run)
                        :nudge-id (:nudge-id run)
                        :run-id (:run-id run)}
                       ctx)
                      state)]
          {:state (update-in state [:calls model-call-id :output] str text)
           :receipt {:status :accepted :outcome :chunk
                     :run-id id
                     :message-id (get-in state [:runs id :message-id])}})

        :tool-call
        (let [raw-payload (or (:payload event) {})
              ;; Some transports carry a short assistant preamble with the
              ;; tool call instead of a separate chunk event.  Keeping it in
              ;; the run edits that message instead of losing or blanking it.
              preamble (or (:text event) "")
              state (if (str/blank? preamble)
                      state
                      (let [state (-> (state/ensure-run state model-call-id
                                                        attempt-id now)
                                      (state/append-run-text id preamble now))
                            run (get-in state [:runs id])
                            call (get-in state [:calls model-call-id])]
                        (if (and run
                                 (not (:session-run call))
                                 (not (:actor-attempt-id call)))
                          (mirror-history-message!
                           state
                           {:message-id (:message-id run)
                            :role :assistant
                            :content (str (:text run))
                            :nudge? (:nudge? run)
                            :nudge-id (:nudge-id run)
                            :run-id (:run-id run)}
                           ctx)
                          state)))
              arguments (or (:arguments event) {})
              conversation-arguments (or (not-empty arguments)
                                         (not-empty raw-payload))
              snapshot (get-in current [:request :snapshot])
              role-program (surface/program-role-for (:role current))
              ;; A central Plan call can lack a direct tenure id; its frozen
              ;; snapshot still names the tenure it was created for.  Only a
              ;; legacy call without either falls back to the current lane
              ;; tenure.  Resolve it before the surface context so the
              ;; program policy (write unlock, draft path, S1) belongs to the
              ;; pinned tenure, never to a successor.
              tenure-id (or (:tenure-id current)
                            (get-in current [:request :snapshot :tenure-id])
                            (if (= :clj-plan role-program)
                              (relay/current-tenure-id (:relay state) :plan)
                              (relay/current-tenure-id (:relay state)
                                                       :execution)))
              lane (or (:lane current)
                       (get-in current [:request :snapshot :lane])
                       (if (= :clj-plan role-program) :plan :execution))
              surface-result
              (surface/program-context
               (:mission state)
               {:role (:role current)
                :tenure-id tenure-id
                :agent-id (:agent-id current)})
              surface-context (if (:ok? surface-result)
                                (:context surface-result)
                                {})
              snapshot-context
              (select-keys snapshot
                           [:program-role :holder? :s1?
                            :write-unlocked? :draft-path
                            :workspace :board-snapshot
                            :model-role :model-id])
              ;; A surface refusal means the pinned tenure is no longer the
              ;; live lane tenure (or is ambiguous).  The frozen snapshot
              ;; still supplies role/workspace, but its Plan policy must not
              ;; grant a write, draft path, or S1 ask to a late call.
              snapshot-context
              (if (:ok? surface-result)
                snapshot-context
                (dissoc snapshot-context :s1? :write-unlocked? :draft-path))
              ;; The live surface is authoritative for role and Plan policy;
              ;; the snapshot only supplies fields the surface does not
              ;; carry (workspace, board snapshot, model identity).
              model-context (merge snapshot-context surface-context)
              turn-context (select-keys (:request current)
                                        [:turn-command-id :turn-card-ids
                                         :instruction-values :user-turn?
                                         :search])
              program-role (or (:program-role model-context) role-program)
              ;; The model port namespaces its project id by attempt, but the
              ;; Owner still derives a local id from the parent model call and
              ;; output index.  This keeps the Owner namespace stable even for
              ;; a custom port and makes a collision with an Owner model call
              ;; impossible.  The same id is used for the model-visible
              ;; tool-call/tool pair, keeping the replayed conversation
              ;; internally consistent.
              ;; Keep the stable parent-call/index id for the first attempt.
              ;; A retry keeps the model call id but restarts output indices
              ;; at 0, so only a collision with a *different* attempt gets an
              ;; attempt suffix.  The old attempt's late result therefore
              ;; cannot enter the new call, and existing first-attempt ids
              ;; stay readable.
              base-tool-call-id (str model-call-id "/tool-"
                                     (or (:output-index event) 0))
              existing-base (state/call state base-tool-call-id)
              existing-attempt (or (:model-attempt-id existing-base)
                                   (:attempt-id existing-base)
                                   (when existing-base
                                     (get-in state
                                             [:calls (:parent-call-id existing-base)
                                              :attempt-id]))
                                   (get-in state [:calls model-call-id
                                                 :attempt-id]))
              tool-call-id (if (and existing-base
                                    (not= (state/normalize-attempt-id attempt-id)
                                          (state/normalize-attempt-id
                                           existing-attempt)))
                             (str base-tool-call-id "#" attempt-id)
                             base-tool-call-id)
              member (or (:tool-name event) (:name event) (:member event)
                         (:tool-name raw-payload) (:name raw-payload)
                         (:member raw-payload))
              model-args (model-arguments raw-payload arguments)
              payload (cond-> (merge {:role program-role
                                      :mission-role (:role current)
                                      :tenure-id tenure-id
                                      :lane lane
                                      :agent-id (:agent-id current)}
                                     model-args
                                     (select-keys event [:tool-name :name])
                                     model-context
                                     turn-context
                                     ;; Owner-derived identity is last: no
                                     ;; model argument, snapshot, or surface
                                     ;; value can redirect the call to
                                     ;; another tenure or lane.
                                     {:role program-role
                                      :mission-role (:role current)
                                      :tenure-id tenure-id
                                      :lane lane
                                      :agent-id (:agent-id current)
                                      :call-id tool-call-id})
                        (= "devops" (str member))
                        (assoc :executor :devops))
              annotation (normalize-scope-annotation
                          (or (get-in event [:payload :scope])
                              (get-in event [:arguments :scope])
                              (get-in payload [:scope])))
              payload (cond-> payload
                        (some? annotation) (assoc :scope annotation))
              admission (state/scope-admission-check state member annotation
                                                   tool-call-id)
              existing (state/call state tool-call-id)]
          (cond
            existing
            {:state state
             :receipt {:status :ignored :reason :duplicate-tool-call
                       :call-id tool-call-id}}

            (false? (:ok admission))
            (let [admission (scope-refusal-message member admission)
                  state (record-tool-refusal state tenure-id
                                             (:agent-id current)
                                             member admission now)
                  refusal (ufss/write-str admission)
                  state (state/append-diagnostic
                         state
                         (assoc admission :call-id tool-call-id :at now))
                  actor-attempt-id (:actor-attempt-id current)]
              (if actor-attempt-id
                ;; The refusal is this agent's tool result.  It belongs in
                ;; its private context, not the central conversation.
                (let [routed (actors/accept-tool-result
                              (:mission state) (:actors state)
                              actor-attempt-id
                              {:call-id tool-call-id :name member}
                              refusal true)]
                  (if (:ok? routed)
                    {:state (-> state
                                (assoc :mission (:mission routed))
                                (assoc :actors (:actors routed)))
                     :receipt {:status :refused :error admission}}
                    ;; The refusal cannot reach its agent.  Report it once;
                    ;; there is no call record to retry against.
                    (let [error (:error routed)
                          diagnostic
                          {:code :TOOL_RESULT_UNDELIVERABLE
                           :severity :error
                           :message "The refused tool result could not be delivered to its agent."
                           :remedy :inspect
                           :call-id tool-call-id
                           :actor-attempt-id actor-attempt-id
                           :reason (:code error)
                           :at now}]
                      {:state (state/append-diagnostic
                               state
                               (assoc diagnostic :error error))
                       :receipt {:status :refused :error admission}
                       :outbox [{:type :owner/blocked
                                 :source :tool-result
                                 :diagnostic diagnostic
                                 :at now}]})))
                {:state (-> state
                            ;; Keep the model-visible pair valid: a tool
                            ;; result without its assistant tool-call is
                            ;; rejected by strict providers.  The synthetic
                            ;; call is conversation only; no execution and no
                            ;; Scope settlement happens.
                            ;; The id still belongs to this run: the turn
                            ;; invoked a tool even though Scope refused it,
                            ;; and the finished assistant message names it.
                            (state/ensure-run model-call-id attempt-id now)
                            (note-run-tool-call id tool-call-id)
                            (update :conversation conj
                                    (cond-> {:role :tool-call
                                             :member member
                                             :call-id tool-call-id
                                             :at now}
                                      (some? conversation-arguments)
                                      (assoc :arguments conversation-arguments)))
                            (update :conversation conj
                                    {:role :tool
                                     :tool-call-id tool-call-id
                                     :name member
                                     :call-id tool-call-id
                                     :member member
                                     :text refusal
                                     :scope-refused? true
                                     :at now}))
                 :receipt {:status :refused :error admission}}))

            :else
            (let [state (clear-tool-refusal state tenure-id
                                            (:agent-id current) member)
                  [state call]
                  (state/create-call
                   state
                   {:call-id tool-call-id
                    :kind (tool-kind payload)
                    :role (:role current)
                    :lane lane
                    :tenure-id tenure-id
                    :agent-id (:agent-id current)
                    :parent-call-id model-call-id
                    :request payload
                    :created-at now})
                  call (assoc call
                              :member member
                              :model-attempt-id attempt-id
                              :scope-annotation (:annotation admission)
                              :scope-turn-id model-call-id
                              :scope-exempt? (:exempt admission))
                  state (assoc-in state [:calls tool-call-id] call)
                  ;; A tool call is model output even when the round has
                  ;; no prose: open the run so `:tool-call-ids` has a home
                  ;; and a tool-call-only completion can name what ran.
                  state (state/ensure-run state model-call-id attempt-id now)
                  state (note-run-tool-call state id tool-call-id)
                  ;; A tool call under an actor-owned model call belongs to
                  ;; that agent; the central conversation must not record it.
                  state (if (:actor-attempt-id current)
                          state
                          (update state :conversation conj
                                  (cond-> {:role :tool-call
                                           :member member
                                           :call-id tool-call-id
                                           :scope (:annotation admission)
                                           :at now}
                                    (some? conversation-arguments)
                                    (assoc :arguments conversation-arguments))))]
              {:state state
               :receipt {:status :accepted
                         :outcome :tool-call-created
                         :call-id tool-call-id
                         :scope-exempt? (:exempt admission)}})))

        :completed
        (let [state (state/ensure-run state model-call-id attempt-id now)
              run (state/run-for-attempt state model-call-id attempt-id)
              ;; The model can stream the answer and finish with an empty
              ;; terminal text.  `apply-citations` then has no text to
              ;; rewrite, so the answer keeps `[S1]` labels and no citation
              ;; map.  The bounded public search context still names every
              ;; source: keep those cards on the answer message as its
              ;; source list.  The card labels are `S1..Sn`, so the body and
              ;; the list still correspond one-to-one.
              sources (vec (or (get-in event [:search-context :source-cards])
                               []))
              event (cond-> event
                      (and (empty? (:citations event)) (seq sources))
                      (assoc :citations sources))
              final (let [text (final-run-text run (:text event))]
                      (if (str/blank? (str text))
                        ;; A round that only invoked tools has no prose;
                        ;; name the calls so the assistant message is not
                        ;; an empty bubble.
                        (or (tool-call-summary-text state run
                                                    (:conversation state))
                            text)
                        text))
              mismatch? (and run
                             (not (str/blank? (str (:text event))))
                             (not= (str (:text event)) (str (:text run)))
                             (not (str/starts-with? (str (:text event))
                                                    (str (:text run))))
                             (not (str/starts-with? (str (:text run))
                                                    (str (:text event)))))
              nudge-id (get-in current [:request :snapshot :nudge-id])
              extra (cond-> {:text final}
                      (some? nudge-id)
                      (assoc :nudge-id nudge-id :nudge? true)

                      (seq (:citations event))
                      (assoc :citations (:citations event))

                      (map? (:search-refs event))
                      (assoc :search-refs (:search-refs event))

                      (seq (:search-source-list event))
                      (assoc :search-source-list (:search-source-list event))

                      (string? (:search-plain-text event))
                      (assoc :search-plain-text (:search-plain-text event))

                      (contains? event :search-status)
                      (assoc :search-status (:search-status event))

                      (true? (:search-degraded? event))
                      (assoc :search-degraded? true)

                      (true? (:search-failed? event))
                      (assoc :search-failed? true))
              state (state/finish-run state id :finished now extra)
              state (mirror-assistant-history! state current run event final ctx)
              state (record-actor-terminal state current event final)
              state (accept-session-run-terminal state current event ctx)
              state (if mismatch?
                      (state/append-diagnostic
                       state
                       {:code :FINAL_TEXT_MISMATCH
                        :call-id model-call-id
                        :run-id id
                        :accumulated-length (count (str (:text run)))
                        :final-length (count (str (:text event)))
                        :at now})
                      state)]
          {:state (append-terminal
                   state
                   {:call-id model-call-id
                    :attempt-id attempt-id
                    :status :succeeded
                    :payload (select-keys event
                                         [:text :usage :output
                                          :search-status
                                          :search-degraded?
                                          :search-failed?
                                          :search-context])
                    :at now})
           :receipt {:status :accepted :outcome :terminal-queued
                     :run-id id}})

        :failed
        (let [error (cond-> (merge (or (:error event)
                                       {:kind :model-error
                                        :message "model request failed"})
                                   (select-keys event
                                                [:partial?
                                                 :published-call-ids
                                                 :published-calls]))
                      (:search-error? event)
                      (assoc :search-error? true
                             :kind :search-required-failed
                             :category :permanent))
              state (state/ensure-run state model-call-id attempt-id now)
              state (state/finish-run state id :failed now {:error error})
              state (record-actor-terminal state current event nil)
              state (accept-session-run-terminal state current event ctx)]
          {:state (append-terminal
                   state
                   (cond-> {:call-id model-call-id
                            :attempt-id attempt-id
                            :status :failed
                            :error error
                            :at now}
                     (map? (:search-context event))
                     (assoc :payload (select-keys event
                                                  [:search-context]))))
           :receipt {:status :accepted :outcome :terminal-queued
                     :run-id id}})

        :cancelled
        (let [error (merge {:kind :cancelled
                            :message "model request cancelled"}
                           (select-keys event
                                        [:partial?
                                         :published-call-ids
                                         :published-calls]))
              state (state/ensure-run state model-call-id attempt-id now)
              state (state/finish-run state id :cancelled now {:error error})
              state (record-actor-terminal state current event nil)
              state (accept-session-run-terminal state current event ctx)]
          {:state (append-terminal
                   state
                   (cond-> {:call-id model-call-id
                            :attempt-id attempt-id
                            :status :cancelled
                            :error error
                            :at now}
                     (map? (:search-context event))
                     (assoc :payload (select-keys event
                                                  [:search-context]))))
           :receipt {:status :accepted :outcome :terminal-queued
                     :run-id id}})

        {:state (state/append-diagnostic
                 state
                 {:code :UNKNOWN_MODEL_EVENT
                  :event (:event event)
                  :at now})
         :receipt {:status :ignored :reason :unknown-event}}))))

(defn- apply-call-terminal
  [state message ctx]
  (let [current (state/call state (:call-id message))
        message-attempt (state/normalize-attempt-id (:attempt-id message))
        current-attempt (state/normalize-attempt-id (:attempt-id current))
        same-attempt? (and current
                           (some? message-attempt)
                           (= message-attempt current-attempt))
        actor? (and current
                    (:actor-attempt-id current)
                    same-attempt?
                    (not (state/call-node-terminal?
                          state (:call-id current))))
        state (if actor?
                (record-actor-terminal
                 state current (actor-terminal-event message)
                 (get-in message [:payload :text]))
                state)]
    {:state (append-terminal state (dissoc message :type))
     :receipt {:status :accepted :outcome :terminal-queued}}))

(defn- apply-timer
  [state _message _ctx]
  {:state state
   :receipt {:status :accepted :outcome :timer-fired}})

(defn- apply-dispatch-failed
  [state message ctx]
  (let [call-id (:call-id message)
        current (state/call state call-id)
        message-attempt (state/normalize-attempt-id (:attempt-id message))
        current-attempt (state/normalize-attempt-id (:attempt-id current))
        same-attempt? (and current
                           (some? message-attempt)
                           (= message-attempt current-attempt))
        terminal? (state/call-node-terminal? state call-id)]
    (cond
      (nil? current)
      {:state (state/append-diagnostic
               state
               {:code :DISPATCH_FAILED_UNKNOWN_CALL
                :call-id call-id
                :error (:error message)
                :at (:now ctx)})
       :receipt {:status :accepted :outcome :diagnostic}}

      (not same-attempt?)
      {:state (state/append-diagnostic
               state
               {:code :STALE_DISPATCH_FAILED
                :call-id call-id
                :attempt-id message-attempt
                :current-attempt-id (:attempt-id current)
                :error (:error message)
                :at (:now ctx)})
       :receipt {:status :ignored :reason :stale-attempt}}

      terminal?
      {:state (state/append-diagnostic
               state
               {:code :DISPATCH_FAILED_AFTER_TERMINAL
                :call-id call-id
                :attempt-id message-attempt
                :error (:error message)
                :at (:now ctx)})
       :receipt {:status :ignored :reason :call-terminal}}

      :else
      (let [state (if (:actor-attempt-id current)
                    (record-actor-terminal
                     state current
                     {:event :failed
                      :error (or (:error message)
                                 {:kind :dispatch-failed
                                  :message "model dispatch failed"})}
                     nil)
                    state)]
        {:state (-> state
                    (update-in [:calls call-id] assoc
                               :state :failed
                               :retry-error (:error message))
                    (state/accept-node call-id message-attempt :failed nil
                                       (:error message) (:now ctx))
                    (state/append-diagnostic
                     {:code :DISPATCH_FAILED
                      :call-id call-id
                      :attempt-id message-attempt
                      :error (:error message)
                      :at (:now ctx)}))
         :receipt {:status :accepted :outcome :call-failed}}))))

(defn- apply-settled
  "Record that the dispatcher settled one attempt.

  Settlement means only that the execution layer released its own task slot.
  It is evidence about this process, not about an external Shell, workspace
  action, or devops worker, which can outlive the task that started it.

  For a model attempt the node's own outcome is re-observed unchanged, so
  nothing is invented and no result is rewritten.  `accept-terminal` treats
  that as a duplicate and clears `:resource-alive?` **only for a settlement
  outcome** (`:succeeded`, `:failed`, `:cancelled`).  A node already fixed
  to `:outcome-unknown` therefore keeps its flag: this notice cannot end a
  resource whose end nobody observed.  The release is the executor's own
  terminal or the cancel hook's explicit receipt, never this fallback.

  Only an explicit `:model` settlement is re-observed.  A missing or
  non-model kind is ignored, because a settled task slot cannot prove that a
  side-effecting resource ended."
  [state message ctx]
  (let [call-id (:call-id message)
        attempt-id (:attempt-id message)
        node-id (when (and call-id attempt-id)
                  (state/attempt-node-id call-id attempt-id))
        node (when node-id (get-in state [:cancel :nodes node-id]))
        now (:now ctx)
        ;; Task settlement is the dispatcher releasing its own attempt slot.
        ;; That is only evidence about resources the dispatcher executed
        ;; in-process.  A shell, workspace, or devops task can settle on a
        ;; cancelled mission while the external process is still running;
        ;; clearing its flag from here would publish a completion that has
        ;; not happened.  Those resources end only through the hook's own
        ;; terminal or the real executor terminal.
        ;;
        ;; Fail closed: an absent kind cannot prove that the resource is a
        ;; model attempt.  Only an explicit `:model` settlement may re-observe
        ;; its node.
        kind (:kind message)
        settleable? (= :model kind)
        state (if (and settleable?
                       node
                       (true? (:resource-alive? node))
                       (contains? #{:succeeded :failed :cancelled
                                    :outcome-unknown}
                                  (:state node)))
                (state/accept-node state node-id attempt-id
                                   (:state node) nil nil (:now ctx))
                state)
        ;; A non-model task can settle while its external Shell, workspace,
        ;; or devops resource is still running.  Expose that unobserved
        ;; responsibility once per attempt; never write the outcome or clear
        ;; :resource-alive? from a task settlement.
        unobserved? (and (some? kind)
                         (not= :model kind)
                         node
                         (true? (:resource-alive? node))
                         (not (true? (:external-outcome-unobserved? node))))
        state (if unobserved?
                (-> state
                    (assoc-in [:cancel :nodes node-id
                               :external-outcome-unobserved?]
                              true)
                    (assoc-in [:cancel :nodes node-id :unobserved-at] now)
                    (cond-> (get-in state [:calls call-id])
                      (assoc-in [:calls call-id
                                 :external-outcome-unobserved?]
                                true))
                    (state/append-diagnostic
                     {:code :EXTERNAL_OUTCOME_UNOBSERVED
                      :severity :warning
                      :message (str "The execution task settled without an "
                                    "executor terminal; the external resource "
                                    "outcome is unobserved.")
                      :remedy :reconcile
                      :call-id call-id
                      :attempt-id attempt-id
                      :kind kind
                      :at now}))
                state)
        ;; If the marker already exists, keep the call projection in sync.
        state (if (and node
                       (true? (:external-outcome-unobserved? node))
                       (get-in state [:calls call-id])
                       (nil? (get-in state [:calls call-id
                                            :external-outcome-unobserved?])))
                (assoc-in state [:calls call-id
                                 :external-outcome-unobserved?]
                          true)
                state)]
    {:state state
     :receipt {:status :accepted
               :outcome :settled
               :attempt-id attempt-id}}))

(defn- apply-child-report
  [state message ctx]
  (let [report (:report message)
        parent-call-id (:parent-call-id report)
        active? (state/parent-call-active? state parent-call-id)
        result (acceptance/accept-report (:acceptance state) report
                                         {:call-active? active?})]
    (if (:ok? result)
      {:state (assoc state :acceptance (:state result))
       :receipt {:status :accepted
                 :outcome (:outcome result)
                 :report-id (:report-id report)}
       :outbox [{:type :report/ack
                 :ack (:ack result)
                 :parent-call-id parent-call-id
                 :at (:now ctx)}]}
      (let [conflict? (contains? #{:REPORT_CONFLICT :REPORT_ID_CONFLICT}
                                 (get-in result [:error :code]))
            ;; A second report for a parent call can never close normally.
            ;; Without an ack the child would redeliver forever.  The parent
            ;; still owns the responsibility, so the honest acknowledgement
            ;; is `:parent-terminated`; the child clears its pending record.
            ack (when conflict?
                  (cond-> {:report-id (:report-id report)
                           :parent-call-id parent-call-id
                           :status :parent-terminated}
                    (contains? report :child-session-id)
                    (assoc :child-session-id (:child-session-id report))))]
        {:state (state/append-diagnostic
                 state
                 (merge {:code :CHILD_REPORT_REJECTED
                         :parent-call-id parent-call-id
                         :at (:now ctx)}
                        (select-keys (:error result) [:code :message :details])))
         :receipt {:ok? false :error (:error result)}
         :outbox (if ack
                   [{:type :report/ack
                     :ack ack
                     :parent-call-id parent-call-id
                     :at (:now ctx)}]
                   [])}))))

(defn- apply-report-delivered
  [state message ctx]
  (let [report-id (:report-id message)
        report (get-in state [:reports :reports report-id])
        claim (get-in state [:report-claims report-id])
        now (:now ctx)]
    (cond
      (and report (not= :pending (:phase report)))
      {:state state
       :receipt {:status :ignored :reason :report-closed}}

      (nil? report)
      {:state (update state :report-claims dissoc report-id)
       :receipt {:status :ignored :reason :report-absent}}

      (and claim
           (:attempt-id claim)
           (:attempt-id message)
           (not= (:attempt-id claim) (:attempt-id message)))
      {:state (state/append-diagnostic
               state
               {:code :STALE_REPORT_DELIVERY
                :report-id report-id
                :claim-attempt-id (:attempt-id claim)
                :message-attempt-id (:attempt-id message)
                :at now})
       :receipt {:status :ignored :reason :stale-attempt}}

      (nil? claim)
      ;; A pending report without a claim is an orphan.  Recreate the claim
      ;; instead of waiting for a restart; redelivery is idempotent.
      {:state (assoc-in state [:report-claims report-id]
                        {:state :pending
                         :attempts 0
                         :attempt-id nil
                         :retry-at nil
                         :timer nil
                         :last-error nil})
       :receipt {:status :accepted :outcome :report-claim-recovered}}

      (:ok? message)
      (let [retry-at (+ now state/report-ack-timeout-ms)]
        {:state (assoc-in state [:report-claims report-id]
                          (assoc claim
                                 :state :delivered
                                 :attempt-id nil
                                 :retry-at retry-at
                                 :timer {:state :ready
                                         :retry-at retry-at}
                                 :last-error nil))
         :receipt {:status :accepted
                   :outcome :report-delivered
                   :retry-at retry-at}})

      :else
      (let [attempts (:attempts claim 0)
            retry-at (+ now (state/report-retry-delay attempts))]
        {:state (-> state
                    (assoc-in [:report-claims report-id]
                              (assoc claim
                                     :state :retry-wait
                                     :attempt-id nil
                                     :retry-at retry-at
                                     :timer {:state :ready
                                             :retry-at retry-at}
                                     :last-error (:error message)))
                    (state/append-diagnostic
                     {:code :REPORT_DELIVERY_RETRY
                      :report-id report-id
                      :attempts attempts
                      :retry-at retry-at
                      :error (:error message)
                      :at now}))
         :receipt {:status :accepted
                   :outcome :report-retry
                   :retry-at retry-at}}))))

(defn- apply-turn-ended
  [state message ctx]
  (if (true? (:ended? (:turn state)))
    ;; An ended turn still present has not been settled: every committed
    ;; answer clears `:turn`.  Overwriting it would drop the failure,
    ;; tenure, and agent that the Nudge policy still owes a decision.
    {:state state
     :receipt {:status :ignored :reason :turn-still-unsettled}}
    (let [role (keyword (:role message))]
      {:state (assoc state :turn {:role role
                                  :agent-id (:agent-id message)
                                  :tenure-id (:tenure-id message)
                                  ;; A manually reported end carries no tool
                                  ;; evidence; the policy decides from the
                                  ;; tenure ledger whether this was a plain
                                  ;; stop or still owes a Nudge.
                                  :failure-outcome :succeeded
                                  :ended? true
                                  :at (:now ctx)})
       :receipt {:status :accepted :outcome :turn-ended}})))

(defn- normalize-business
  "Wrap a command-kind result that was refused without a state.

  `accepted` already carries `:state`.  `refused` does not; the caller's
  committed state must stay untouched."
  [state result]
  (if (contains? result :state)
    result
    {:state state
     :receipt {:ok? false :error (:error result)}}))

(defn- apply-worker-program-result
  "Apply one async worker program result exactly once per job-id."
  [state message ctx]
  (let [job-id (:job-id message)
        job (get-in state [:worker-jobs job-id])]
    (cond
      (nil? job)
      {:state state
       :receipt {:status :accepted
                 :outcome :unknown-job
                 :job-id job-id
                 :replayed? true}}

      (contains? #{:finished :failed :outcome-unknown} (:state job))
      {:state state
       :receipt {:status :accepted
                 :outcome (:outcome job)
                 :job-id job-id
                 :replayed? true}}

      :else
      (let [result (:result message)
            outcome (or (:outcome message)
                        (worker-program-outcome result))
            job (merge job
                       {:state :finished
                        :outcome outcome
                        :result result
                        :stamp (or (:stamp message)
                                   (:stamp result)
                                   (get-in result [:value :stamp])
                                   (get-in result [:value :details :stamp])
                                   (get-in result [:value :details]))
                        :error (:error message)
                        :finished-at (:now ctx)})
            event {:type :worker/program-result
                   :job-id job-id
                   :outcome outcome
                   :task-id (:task-id job)
                   :claim-id (:claim-id job)
                   :agent-id (:agent-id job)
                   :stamp (:stamp job)
                   :at (:now ctx)}]
        {:state (-> state
                    (assoc-in [:worker-jobs job-id] job)
                    (state/append-outbox [event]))
         :receipt {:status :accepted
                   :outcome outcome
                   :job-id job-id
                   :task-id (:task-id job)
                   :claim-id (:claim-id job)
                   :agent-id (:agent-id job)
                   :result result
                   :stamp (:stamp job)}}))))

(defn- apply-message*
  [state message ctx]
  (case (:type message)
    :command (apply-command state message ctx)
    :model/event
    (try
      (apply-model-event state message ctx)
      (catch Throwable error
        (let [event (:event message)
              call-id (or (:model-call-id event) (:call-id event))
              attempt-id (:attempt-id event)
              now (:now ctx)
              failure {:kind :model-event-handler-error
                       :message (or (ex-message error) (str (class error)))
                       :class (.getName (class error))}
              run-id (state/run-id call-id attempt-id)]
          {:state (-> state
                      (state/ensure-run call-id attempt-id now)
                      (state/finish-run run-id :failed now {:error failure})
                      (append-terminal {:call-id call-id
                                        :attempt-id attempt-id
                                        :status :failed
                                        :error failure
                                        :at now})
                      (state/append-diagnostic
                       (assoc failure :call-id call-id :at now)))
           :receipt {:ok? false :error failure}})))
    :call/terminal (apply-call-terminal state message ctx)
    :timer/fire (apply-timer state message ctx)
    :dispatch/failed (apply-dispatch-failed state message ctx)
    :dispatch/settled (apply-settled state message ctx)
    :worker/program-result (apply-worker-program-result state message ctx)
    :child/report (apply-child-report state message ctx)
    :report/delivered (apply-report-delivered state message ctx)
    :report/ack (normalize-business
                 state (report-ack state (select-keys message [:ack]) ctx))
    :report/register (normalize-business
                      state (report-register
                             state (select-keys message [:report]) ctx))
    :turn/ended (apply-turn-ended state message ctx)
    :review/submit (normalize-business
                    state
                    (apply-review state (select-keys message
                                                     [:lane :tenure-id :findings
                                                      :call-id
                                                      :draft-disk-content])
                                  ctx))
    :board/command (normalize-business
                    state
                    (apply-board-command state (:command message) ctx
                                         (:env message)))
    ;; One generic handler; Pair Hint and other owners share this path.
    :mission/extensions-put (normalize-business
                             state
                             (apply-mission-extension state message ctx))
    :git/ff-ready
    ;; The mission owns one workspace, so readiness is mission state, not a
    ;; session-level flag that would survive into the next mission.  Keep
    ;; the evidence next to the boolean for audit and diagnostics.
    (let [ready? (true? (:ready? message))
          fact {:ready? ready?
                :source (:source message)
                :ff-commit (:ff-commit message)
                :at (or (:at message) (:now ctx))}]
      {:state (-> state
                  (assoc-in [:mission :ff-ready?] ready?)
                  (assoc-in [:mission :ff-ready] fact))
       :receipt {:status :accepted
                 :outcome :ff-ready-recorded
                 :ready? ready?
                 :source (:source message)
                 :ff-commit (:ff-commit message)
                 :at (:at fact)}})
    :recover {:state (state/recover-state state (:now ctx))
              :receipt {:status :accepted :outcome :recovered}}
    :wake {:state state :receipt {:status :accepted :outcome :wake}}
    {:state (state/append-diagnostic
             state
             {:code :UNKNOWN_MESSAGE_TYPE
              :message-type (:type message)
              :at (:now ctx)})
     :receipt {:ok? false
               :error {:code :UNKNOWN_MESSAGE_TYPE
                       :message "unknown Owner message type"
                       :details {:type (:type message)}}}}))

(defn- invoke-owner-callback!
  [callback payload]
  (when (ifn? callback)
    (try
      (callback payload)
      (catch Throwable _ nil))))

(defn- accepted-user-send?
  [message result]
  (let [kind (get-in message [:payload :kind])
        receipt (:receipt result)]
    (and (= :command (:type message))
         (contains? #{:user/message :send-message} kind)
         (some? receipt)
         (not (true? (:replayed? receipt)))
         (not (false? (:ok? receipt)))
         (not= :refused (:status receipt)))))

(defn- notify-owner-side-effects!
  "Advisory callbacks after the pure transition.

  They are deliberately outside the committed state: a throwing callback
  must never change the receipt or the state.  A replayed command and a
  duplicate terminal do not fire again."
  [state message result ctx]
  (let [changed? (not= state (:state result))]
    (case (:type message)
      :command
      (when (and changed? (accepted-user-send? message result))
        (invoke-owner-callback!
         (:on-user-send! ctx)
         {:send-id (:command-id message)
          :turn-id (:command-id message)
          :session-id (:session-id state)
          :at (:now ctx)}))

      :call/terminal
      (let [call (get-in (:state result) [:calls (:call-id message)])]
        (when (and changed? call (not= :model (:kind call)))
          (invoke-owner-callback!
           (:on-call-terminal! ctx)
           {:tool-call-id (:call-id message)
            :session-id (:session-id state)
            :name (:member call)
            :at (:now ctx)
            :result (:payload message)})))
      nil)))

(defn apply-message
  "Apply one message to committed state.

  Returns `{:state :receipt :outbox}`.  The state is a candidate until the
  Owner commits it.  The outbox is advisory and is drained only after the
  commit.  An outbox entry is appended to the committed state even when the
  business state is unchanged: a duplicate report delivery still has to
  return its acknowledgement, and that effect must survive the commit."
  [state message ctx]
  (let [result (apply-message* state message ctx)
        outbox (vec (:outbox result))
        final (if (and (= state (:state result)) (empty? outbox))
                result
                (-> result
                    (update :state assoc :updated-at (:now ctx))
                    (update :state state/append-outbox outbox)
                    (assoc :outbox [])))]
    (notify-owner-side-effects! state message final ctx)
    final))
