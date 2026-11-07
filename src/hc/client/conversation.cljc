(ns hc.client.conversation
  "Conversation surface reducer and public facade.

  This namespace owns the browser-local conversation state described by
  AGENTS.md §7.3 and §7.4: per-session drafts, composer decisions, streaming
  follow/selection, stop semantics, S1 ask cards, command tracking, and the
  accessible overlay/focus stack.  It never owns server messages and never
  fabricates a domain terminal state.

  The workbench stores this state under `:ui/conversation` and mounts
  `hc.client.conversation.view/conversation-surface`.  Every operator gesture
  becomes an action map; `apply-action` returns the next state.  Commands are
  appended to `:outbox` and also kept under `:commands` by `command-id`.
  `take-outbox` drains the outbox for the transport layer."
  (:require [clojure.string :as str]
            [hc.client.conversation.a11y :as a11y]
            [hc.client.conversation.ask :as ask]
            [hc.client.conversation.composer :as composer]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]
            [hc.client.conversation.surface :as view-model]
            #?(:cljs [hc.client.conversation.view :as view])))

;; ---------------------------------------------------------------------------
;; State

(def initial-state
  {:session-id nil
   :session nil
   :connection nil
   :platform :desktop
   :layout :wide
   :send-key :enter
   :drafts {}
   :streams {}
   :asks {}
   :commands {}
   :outbox []
   :next-command-id 1
   :overlays []
   :focus nil
   :now nil
   :last-outcome {:kind :initial :result :idle}})

(defn- outcome
  [state kind result details]
  (assoc state :last-outcome (merge {:kind kind :result result} details)))

(defn- sid
  [state action]
  (or (:session-id action) (:session-id state)))

(defn session-draft
  [state session-id]
  (draft/draft (:drafts state) session-id))

(defn session-stream
  [state session-id]
  (or (get (:streams state) session-id) (stream/initial)))

(defn session-asks
  [state session-id]
  (get (:asks state) session-id {}))

(defn- update-stream
  [state session-id f & args]
  (update-in state [:streams session-id]
             (fn [current]
               (apply f (or current (stream/initial)) args))))

(defn- update-draft
  [state session-id f & args]
  (update-in state [:drafts session-id]
             (fn [current]
               (apply f (or current draft/empty-draft) args))))

(defn pending-command
  "Return the newest pending command of `kind` for a session, if any."
  [state session-id kind]
  (->> (vals (:commands state))
       (filter #(and (= session-id (:session-id %))
                     (= kind (:kind %))
                     (= :pending (:status %))))
       (sort-by (juxt #(or (:sequence %) 0) :command-id))
       last))

(defn pending-command?
  [state session-id kind]
  (boolean (pending-command state session-id kind)))

(defn- mark-send-started
  "A run event proves the pending send reached the server.  Mark only the
  client-side command tracking as accepted so a later stop-and-send is not
  blocked by an old pending envelope.  The server receipt remains the
  authority for rejection and error display."
  [state session-id]
  (if-let [command (pending-command state session-id :send)]
    (assoc-in state [:commands (:command-id command) :status] :accepted)
    state))

(defn- enqueue-command
  [state command]
  (let [n (long (or (:next-command-id state) 1))
        ;; A command id must be unique for the whole Owner command epoch,
        ;; not just this page.  A reloaded tab or a second client starts
        ;; its own counter at 1, so a sequential id collides with an
        ;; already accepted command; the Owner then refuses the new
        ;; payload under §5.1 (same id, different payload).  A random id
        ;; is unique across clients and reloads.  The local :sequence
        ;; keeps UI ordering.
        command-id (str "cmd-" (random-uuid))
        command (merge {:command-id command-id
                        :sequence n
                        :command-epoch (:command-epoch state)
                        :status :pending}
                       command)]
    {:state (-> state
                (assoc :next-command-id (inc n))
                (assoc-in [:commands command-id] command)
                (update :outbox conj command))
     :command command}))

;; ---------------------------------------------------------------------------
;; Sending

(defn- session-run-active?
  [session]
  (when (map? session)
    (let [run-status (or (:run-status session)
                         (:session/run-status session)
                         (:runStatus session))
          status (when (or (keyword? run-status) (string? run-status))
                   (-> run-status name str/lower-case keyword))
          run-id (or (:current-run-id session)
                     (:session/current-run-id session))]
      (boolean
       (cond
         (contains? #{:finished :idle :completed} status) false
         (contains? #{:running :streaming :starting :dispatching
                      :applying :stopping} status) true
         :else (or (:unattended? session)
                   (:nudge-active? session)
                   (:nudge-pending? session)
                   (and (string? run-id) (pos? (count run-id)))))))))

(defn- send-context
  [state session-id]
  (let [session (:session state)
        model (if (contains? state :model)
                (:model state)
                (composer/session-model-gate session))]
    {:draft (session-draft state session-id)
     :stream (session-stream state session-id)
     :command-pending? (pending-command? state session-id :send)
     :model model
     :model-availability (composer/model-availability model session)
     :connection (:connection state)
     :run-active? (session-run-active? session)}))

(defn- draft-from-snapshot
  "Rebuild the draft view a captured send snapshot describes.  Used when a
  stop-and-send decision must send exactly what the operator captured, not
  whatever happens to be in the composer after the stop round-trip."
  [snapshot]
  (merge draft/empty-draft
         (select-keys snapshot [:text :cursor :selection :quotes :version
                               :turn-card-ids :instruction-values
                               :temporary-read? :turn-selection-version])
         {:text (or (:text snapshot) "")
          :attachments (vec (or (:attachments snapshot) []))}))

(defn- submit-send
  ([state session-id at] (submit-send state session-id at {}))
  ([state session-id at {:keys [ignore-command-pending? snapshot]}]
  (let [captured? (some? snapshot)
        {:keys [draft stream] :as base} (send-context state session-id)
        draft (if captured? (draft-from-snapshot snapshot) draft)
        context (cond-> (assoc base :draft draft)
                  ignore-command-pending? (assoc :command-pending? false)
                  captured? (assoc :run-active? false :command-pending? false))
        blocker (composer/send-blocker context)]
    (if blocker
      (outcome state :send :blocked
               {:reason blocker
                :intent (composer/send-intent blocker)
                :message (case blocker
                           :empty "输入为空，草稿已保留"
                           :command-pending "正在提交，请稍候"
                           :stopping "正在停止当前轮"
                           :generating "当前轮仍在生成"
                           :unattended "无人值守运行中，可停止当前轮后再发送"
                           :attachment-blocking "仍有附件未准备好"
                           :no-model "先选择一个可用模型，草稿已保留"
                           :model-unavailable
                           (composer/model-unavailable-message
                            (:model-availability context))
                           :connection-not-ready "网络尚未连接，草稿已保留"
                           "暂时无法发送，草稿已保留")})
      (let [{next-state :state command :command}
            (enqueue-command state
                             {:kind :send
                              :session-id session-id
                              :payload (if captured? snapshot (draft/snapshot draft))
                              :submitted-version (:version draft)
                              :at at})
            ;; Clear the live draft, not the captured view: newer text typed
            ;; while the stop was in flight must survive the captured send.
            cleared (draft/clear-submitted (session-draft state session-id)
                                           (:version draft))]
        (-> next-state
            (assoc-in [:drafts session-id] cleared)
            ;; A send is independent evidence of a new visible run.  The
            ;; stop sentinel must not keep the previous answer frozen after
            ;; the operator explicitly starts the next one.
            (update-stream session-id stream/start-run {:force? true})
            (assoc :now at)
            (outcome :send :queued
                     {:command-id (:command-id command)
                      :submitted-version (:version draft)})))))))

(defn- request-send
  [state session-id at snapshot]
  (let [context (send-context state session-id)
        decision (composer/send-decision context)]
    (case (:action decision)
      :send (submit-send state session-id at
                        (when snapshot {:snapshot snapshot}))
      :needs-decision
      (-> state
          (update-stream session-id assoc :decision decision)
          (assoc :now at)
          (outcome :send :needs-decision
                   {:reason (:reason decision)
                    :options (:options decision)}))
      :fix
      (-> state
          (assoc :now at)
          (outcome :send :fix
                   {:intent (:intent decision)
                    :reason (:reason decision)
                    :message (:message decision)}))
      :blocked
      (-> state
          (assoc :now at)
          (outcome :send :blocked
                   {:intent (:intent decision)
                    :reason (:reason decision)
                    :message (:message decision)})))))

;; ---------------------------------------------------------------------------
;; Stopping

(defn- enqueue-stop
  [state session-id at reason source]
  (if (or (stream/stopping? (session-stream state session-id))
          (stream/stopped? (session-stream state session-id)))
    (outcome state :stop :ignored {:reason :already-stopping})
    (let [{next-state :state command :command}
          (enqueue-command state
                           {:kind :stop
                            :session-id session-id
                            ;; Freeze the run identity and origin with the
                            ;; command.  Resolving them at drain time could
                            ;; produce a different wire payload for the same
                            ;; command id and turn an idempotent resend into
                            ;; a payload conflict.
                            :payload {:reason reason
                                      :origin (case source
                                                :escape :operator-esc
                                                :operator-esc :operator-esc
                                                :operator-stop)
                                      :run-id (or (:run-id (session-stream
                                                            state session-id))
                                                  (:current-run-id (:session state))
                                                  (:session/current-run-id
                                                   (:session state)))}
                            :at at})]
      (-> next-state
          (update-stream session-id stream/stop-requested at)
          (assoc :now at)
          (outcome :stop :queued {:command-id (:command-id command)})))))

(defn- request-stop
  [state session-id at source]
  ;; Local stream state is not evidence that the server has no work.  An
  ;; unattended Nudge turn can be live while the visible answer is idle, so
  ;; the operator can always request a stop for a selected session.  The
  ;; Owner decides whether the request has anything to cancel.
  (enqueue-stop state session-id at :operator source))

(defn- request-stop-and-send
  [state session-id at source]
  (let [stream-state (session-stream state session-id)]
    (if-not (stream/streaming? stream-state)
      (request-send state session-id at nil)
      (let [draft-state (session-draft state session-id)
            pending {:snapshot (draft/snapshot draft-state)
                     :at at}
            next-state (enqueue-stop state session-id at :operator source)]
        (if (= :ignored (get-in next-state [:last-outcome :result]))
          next-state
          (-> next-state
              (update-stream session-id assoc
                             :pending-send pending
                             :decision nil)
              (assoc :now at)
              (outcome :stop :stop-and-send
                       {:command-id (get-in next-state [:last-outcome :command-id])})))))))

(defn- send-pending-after-stop
  [state session-id at]
  (let [pending (get-in state [:streams session-id :pending-send])]
    (if-not pending
      state
      (let [next-state (-> state
                           (update-stream session-id dissoc :pending-send)
                           (submit-send session-id at
                                        {:ignore-command-pending? true
                                         :snapshot (:snapshot pending)}))]
        (if (= :blocked (get-in next-state [:last-outcome :result]))
          (update-stream next-state session-id assoc :pending-send pending)
          next-state)))))

;; ---------------------------------------------------------------------------
;; Command receipts

(defn- restore-rejected-send
  [state command]
  (let [session-id (:session-id command)
        snapshot (:payload command)
        current (session-draft state session-id)]
    (if (draft/blank? current)
      (assoc-in state [:drafts session-id]
                (draft/restore-submission current snapshot))
      (assoc-in state [:drafts session-id]
                (assoc current :rejected-submission snapshot)))))

(defn- apply-ack
  [state {:keys [command-id status at error stopped?] :as action}]
  (let [command (get-in state [:commands command-id])]
    (if-not command
      (outcome state :command :ignored
               {:reason :unknown-command :command-id command-id})
      (let [accepted? (= :accepted (keyword status))
            status (if accepted? :accepted :rejected)
            ;; The Owner fences observer commands with :NOT_CONTROLLER.
            ;; Surface the transfer instead of a generic "not delivered".
            control-refused? (= :NOT_CONTROLLER (:code error))
            state (-> state
                      (assoc-in [:commands command-id :status] status)
                      (assoc-in [:commands command-id :acked-at] at)
                      (assoc-in [:commands command-id :error] error)
                      (assoc :now at))]
        (case (:kind command)
          :send
          (if accepted?
            (outcome state :command :accepted
                     {:command-id command-id :kind :send})
            (let [restored? (draft/blank?
                             (session-draft state (:session-id command)))
                  state (restore-rejected-send state command)]
              (-> state
                  (update-stream (:session-id command)
                                 (fn [stream-state]
                                   (stream/send-rejected
                                    (assoc stream-state :decision nil)
                                    error)))
                  (outcome :command :rejected
                           {:command-id command-id :kind :send
                            :restored? restored?
                            :message (if control-refused?
                                       "另一客户端正在控制本会话；请先接管控制"
                                       (if restored?
                                         "发送未送达，草稿已恢复"
                                         "发送未送达，已保留较新草稿"))}))))

          :stop
          (if accepted?
            (if stopped?
              (-> state
                  (update-stream (:session-id command)
                                 stream/stop-confirmed
                                 {:reason :operator :at at})
                  (send-pending-after-stop (:session-id command) at)
                  (outcome :command :accepted
                           {:command-id command-id :kind :stop}))
              (outcome state :command :accepted
                       {:command-id command-id :kind :stop}))
            (-> state
                (update-stream (:session-id command) stream/stop-rejected
                               (or error :stop-rejected))
                (update-stream (:session-id command) dissoc :pending-send)
                (outcome :command :rejected
                         {:command-id command-id :kind :stop
                          :message (if control-refused?
                                     "控制权在另一客户端；停止未发送"
                                     "停止请求未送达，当前轮可能仍在继续")})))

          :unpark
          (let [session-id (:session-id command)
                ask-id (get-in command [:payload :ask-id])]
            (-> state
                (update-in [:asks session-id ask-id]
                           (fn [ask]
                             (if ask
                               (ask/ack ask command-id accepted? at)
                               ask)))
                (outcome :command (if accepted? :accepted :rejected)
                         {:command-id command-id :kind :unpark
                          :message (when-not accepted?
                                     (if control-refused?
                                       "另一客户端正在控制本会话；请先接管控制"
                                       "回答未送达，可再次提交"))})))

          (outcome state :command status {:command-id command-id}))))))

;; ---------------------------------------------------------------------------
;; Ask cards

(defn- show-ask
  [state session-id ask-map]
  (let [card (ask/new-ask ask-map)
        ask-id (:ask-id card)
        existing (get-in state [:asks session-id ask-id])]
    (-> state
        (assoc-in [:asks session-id ask-id]
                  (if existing
                    (ask/merge-ask existing card)
                    card))
        (outcome :ask :shown {:ask-id ask-id}))))

(defn- submit-answer
  [state session-id ask-id at]
  (let [card (get-in state [:asks session-id ask-id])]
    (cond
      (nil? card)
      (outcome state :ask :ignored {:reason :unknown-ask :ask-id ask-id})

      (not (ask/can-submit? card))
      (outcome state :ask :blocked {:reason :empty-answer :ask-id ask-id})

      :else
      (let [{next-state :state command :command}
            (enqueue-command state
                             {:kind :unpark
                              :session-id session-id
                              :payload (cond-> {:ask-id ask-id
                                                :name (:name card)
                                                :value (ask/answer-value card)}
                                         (:lane card) (assoc :lane (:lane card))
                                         (:tenure-id card)
                                         (assoc :tenure-id (:tenure-id card)))
                              :at at})
            {:keys [ask result]}
            (ask/submit card (:command-id command) at)]
        (-> next-state
            (assoc-in [:asks session-id ask-id] ask)
            (assoc :now at)
            (outcome :ask result {:ask-id ask-id
                                  :command-id (:command-id command)}))))))

(defn- skip-answer
  "Record an explicit skip and resume the parked program with
  `{:skipped? true}`.  A skip is a visible answer record, not a hidden
  dismissal."
  [state session-id ask-id at]
  (let [card (get-in state [:asks session-id ask-id])]
    (cond
      (nil? card)
      (outcome state :ask :ignored {:reason :unknown-ask :ask-id ask-id})

      (not (ask/skippable? card))
      (outcome state :ask :blocked {:reason :not-skippable :ask-id ask-id})

      :else
      (let [{next-state :state command :command}
            (enqueue-command state
                             {:kind :unpark
                              :session-id session-id
                              :payload (cond-> {:ask-id ask-id
                                                :name (:name card)
                                                :value {:skipped? true}}
                                         (:lane card) (assoc :lane (:lane card))
                                         (:tenure-id card)
                                         (assoc :tenure-id (:tenure-id card)))
                              :at at})
            {:keys [ask result]}
            (ask/skip card (:command-id command) at)]
        (-> next-state
            (assoc-in [:asks session-id ask-id] ask)
            (assoc :now at)
            (outcome :ask result {:ask-id ask-id
                                  :command-id (:command-id command)}))))))

;; ---------------------------------------------------------------------------
;; Action dispatch

(defn- apply-escape
  [state action]
  (let [session-id (sid state action)
        stream-state (session-stream state session-id)
        draft-state (session-draft state session-id)
        decision (a11y/escape-decision
                  {:overlays (:overlays state)
                   :composing? (:composing? draft-state)
                   :text-selection? (:text-selection? action)
                   :stream-selection (:selection stream-state)
                   :generating? (stream/streaming? stream-state)
                   :stopping? (stream/stopping? stream-state)})]
    (case (:action decision)
      :close-overlay
      (-> state
          (a11y/close-overlay)
          (outcome :escape :close-overlay
                   {:overlay-id (get-in decision [:overlay :id])
                    :focus-target (:focus-target decision)}))

      :clear-selection
      (-> state
          (update-stream session-id stream/clear-selection)
          (outcome :escape :clear-selection {}))

      :stop
      (request-stop state session-id (:at action) :escape)

      :ime-candidate
      (outcome state :escape :ime-candidate
               {:message (:message decision)})

      :ignore
      (outcome state :escape :ignored {}))))

(defn apply-action
  "Apply one operator or transport action and return the next state."
  [state {:keys [type] :as action}]
  (let [session-id (sid state action)
        at (:at action)]
    (case type
      ;; Session context -----------------------------------------------------
      :session/select
      (cond-> (assoc state :session-id session-id)
        (contains? action :session) (assoc :session (:session action))
        (contains? action :model) (assoc :model (:model action))
        (contains? action :connection) (assoc :connection (:connection action)))

      :session/context
      (cond-> state
        (contains? action :session) (assoc :session (:session action))
        (contains? action :model) (assoc :model (:model action))
        (contains? action :connection) (assoc :connection (:connection action)))

      :connection/set
      (assoc state :connection (:connection action))

      :platform/set
      (assoc state :platform (:platform action))

      :settings/send-key
      (assoc state :send-key (:send-key action))

      :viewport/resize
      (assoc state :layout (a11y/layout-for-width (:width action)))

      ;; Drafts --------------------------------------------------------------
      :draft/edit
      (-> state
          (update-draft session-id draft/edit action)
          (outcome :draft :edited {:session-id session-id}))

      :draft/cursor
      (-> state
          (update-draft session-id draft/set-cursor
                        (:cursor action) (:selection action))
          (outcome :draft :cursor {:session-id session-id}))

      :draft/composition
      (-> state
          (update-draft session-id draft/set-composing (:composing? action))
          (outcome :draft :composition {:session-id session-id}))

      :draft/save-state
      (-> state
          (update-draft session-id draft/set-save-state
                        (:save-state action)
                        {:saved-version (:saved-version action)
                         :error (:error action)})
          (outcome :draft :save-state {:save-state (:save-state action)}))

      :draft/attachments
      (-> state
          (update-draft session-id draft/set-attachments (:attachments action))
          (outcome :draft :attachments {:count (count (:attachments action))}))

      :draft/add-attachment
      (-> state
          (update-draft session-id draft/add-attachment (:attachment action))
          (outcome :draft :attachment-added {}))

      :draft/remove-attachment
      (-> state
          (update-draft session-id draft/remove-attachment (:attachment-id action))
          (outcome :draft :attachment-removed {}))

      :draft/quotes
      (-> state
          (update-draft session-id draft/set-quotes (:quotes action))
          (outcome :draft :quotes {:count (count (:quotes action))}))

      ;; Per-turn instruction selection rides the draft so `draft/snapshot`
      ;; carries it into every send payload — including the pending-send
      ;; snapshot rebuilt after a stop and the legacy `send-message!` path.
      ;; It is metadata, not text: it must not bump `:version` or mark the
      ;; draft unsaved.
      :draft/turn-selection
      (-> state
          (update-draft session-id
                        (fn [d]
                          (-> d
                              (assoc :turn-card-ids (vec (or (:turn-card-ids action) []))
                                     :instruction-values (or (:instruction-values action) {})
                                     :temporary-read? (boolean (:temporary-read? action))
                                     :turn-selection-version (:turn-selection-version action)))))
          (outcome :draft :turn-selection {:session-id session-id}))

      :draft/add-quote
      (-> state
          (update-draft session-id draft/add-quote (:quote action))
          (outcome :draft :quote-added {}))

      :draft/remove-quote
      (-> state
          (update-draft session-id draft/remove-quote (:quote-id action))
          (outcome :draft :quote-removed {}))

      :draft/clear
      (-> state
          (update-draft session-id draft/clear at)
          (outcome :draft :cleared {:session-id session-id}))

      :draft/undo-clear
      (-> state
          (update-draft session-id draft/undo-clear)
          (outcome :draft :undo-clear {:session-id session-id}))

      :draft/restore
      (let [restored (draft/restore-all (:drafts action))]
        (-> state
            (assoc :drafts restored)
            (outcome :draft :restored {:count (count restored)})))

      :draft/restore-rejected
      (-> state
          (update-draft session-id draft/accept-rejected)
          (outcome :draft :rejected-restored {:session-id session-id}))

      ;; Composer ------------------------------------------------------------
      :composer/keydown
      (let [decision (composer/keydown
                      {:platform (:platform state)
                       :send-key (:send-key state)}
                      (:event action))]
        (case (:action decision)
          :send (request-send state session-id at (:snapshot action))
          :newline (outcome state :composer :newline {})
          :ime-candidate (outcome state :composer :ime-candidate
                                  {:prevent-default? false})
          :ignore (outcome state :composer :ignored
                           {:reason (:reason decision)})))

      :composer/send
      (request-send state session-id at (:snapshot action))

      :composer/stop
      (request-stop state session-id at
                    (or (:source action) :operator-stop))

      :composer/stop-and-send
      (request-stop-and-send state session-id at
                             (or (:source action) :operator-stop))

      :composer/continue-editing
      (-> state
          (update-stream session-id dissoc :decision)
          (outcome :composer :continue-editing {}))

      :composer/cancel-pending-send
      (-> state
          (update-stream session-id dissoc :pending-send)
          (outcome :composer :pending-send-cancelled {}))

      :composer/ack
      (apply-ack state action)

      ;; Stream --------------------------------------------------------------
      :stream/begin
      (-> state
          (mark-send-started session-id)
          (update-stream session-id stream/begin action)
          (outcome :stream :begin {:message-id (:message-id action)}))

      :stream/scroll
      (-> state
          (update-stream session-id stream/scroll (:scroll action))
          (outcome :stream :scroll {:session-id session-id}))

      :stream/content-grew
      (-> state
          (update-stream session-id stream/content-grew)
          (outcome :stream :content-grew {:session-id session-id}))

      :stream/selection
      (-> state
          (update-stream session-id stream/selection action)
          (outcome :stream :selection {:phase (:phase action)}))

      :stream/chunk
      (let [next-state (update-stream state session-id stream/chunk action)
            next-state (if (= :appended
                              (get-in next-state [:streams session-id :last-outcome :result]))
                         (mark-send-started next-state session-id)
                         next-state)]
        (assoc next-state
               :last-outcome (get-in next-state [:streams session-id :last-outcome])))

      :stream/tool-result
      (let [next-state (update-stream state session-id stream/tool-result action)]
        (assoc next-state
               :last-outcome (get-in next-state [:streams session-id :last-outcome])))

      :stream/jump
      (-> state
          (update-stream session-id stream/jump-to-latest)
          (outcome :stream :jump {:session-id session-id}))

      :stream/stop-confirmed
      (let [next-state (-> state
                           (update-stream session-id stream/stop-confirmed action)
                           (outcome :stream :stop-confirmed
                                    {:reason (:reason action)}))]
        (send-pending-after-stop next-state session-id at))

      :stream/finished
      (let [next-state (-> state
                           (mark-send-started session-id)
                           (update-stream session-id stream/finished)
                           (outcome :stream :finished {:session-id session-id}))]
        (send-pending-after-stop next-state session-id at))

      :stream/interrupted
      (-> state
          (update-stream session-id stream/interrupted (:reason action))
          (outcome :stream :interrupted {:reason (:reason action)}))

      ;; Ask cards -----------------------------------------------------------
      :ask/show
      (show-ask state session-id (:ask action))

      :ask/edit
      (-> state
          (update-in [:asks session-id (:ask-id action)]
                     (fn [card]
                       (if card
                         (ask/edit card action)
                         card)))
          (outcome :ask :edited {:ask-id (:ask-id action)}))

      :ask/submit
      (submit-answer state session-id (:ask-id action) at)

      :ask/skip
      (skip-answer state session-id (:ask-id action) at)

      :ask/hide
      (-> state
          (update-in [:asks session-id] dissoc (:ask-id action))
          (outcome :ask :hidden {:ask-id (:ask-id action)}))

      ;; Accessibility -------------------------------------------------------
      :a11y/escape
      (apply-escape state action)

      :a11y/open-overlay
      (-> state
          (a11y/open-overlay (:overlay action))
          (outcome :a11y :overlay-opened {:overlay-id (get-in action [:overlay :id])}))

      :a11y/close-overlay
      (-> state
          (a11y/close-overlay)
          (outcome :a11y :overlay-closed {}))

      :a11y/close-overlay-id
      (-> state
          (a11y/close-overlay-id (:overlay-id action))
          (outcome :a11y :overlay-closed {:overlay-id (:overlay-id action)}))

      :a11y/focus
      (assoc state :focus (:focus action))

      ;; Default -------------------------------------------------------------
      (outcome state :action :ignored {:type type}))))

(defn take-outbox
  "Return `{:commands [...] :state ...}` and clear the outbox."
  [state]
  {:commands (vec (:outbox state))
   :state (assoc state :outbox [])})

(defn drain-outbox
  "Alias for callers that prefer a verb."
  [state]
  (take-outbox state))

(defn surface-model
  "Build the accessible view model.  See `hc.client.conversation.surface`.

  The name is `surface-model`, not `surface`, because a CLJS var named
  `surface` in this namespace would munge to the same JavaScript property as
  the `hc.client.conversation.surface` namespace object."
  [input]
  (view-model/surface input))

;; ---------------------------------------------------------------------------
;; Re-exports for workbench adapters

(def empty-draft draft/empty-draft)
(def stream-initial stream/initial)
(def layout-for-width a11y/layout-for-width)

#?(:cljs
   (defn conversation-page
     "React entry point for the workbench session route.

  Call with a CLJS props map directly, or create the element with
  `#js {:props props-map}`.  The component never builds commands; it calls the
  callbacks in `:callbacks`."
     [props]
     (view/conversation-page props)))
