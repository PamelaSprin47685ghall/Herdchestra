(ns hc.client.workbench.conversation-state
  "Workbench adapter for the conversation reducer.

  `hc.client.conversation` owns drafts, composer decisions, stream state, ask
  cards, and command tracking.  The workbench keeps that reducer state under
  `:ui/conversation` and mirrors it into the flat keys the existing views,
  persistence, and transport code already read:

    :ui/draft    (:drafts state)
    :ui/stream   (:streams state)
    :ui/ask      flattened (:asks state)
    :ui/command  transport mirror (also keeps app-scoped commands)

  The mirror is derived, never a second owner.  Callers must go through
  `apply-actions` (or the CLJS bridge for operator gestures) instead of
  writing the flat keys directly.  When `:ui/conversation` is absent the
  adapter is inert, so the older workbench unit tests keep exercising the
  pre-reducer path.

  This namespace is pure CLJC: no I/O, no Fulcro, no transport."
  (:require [clojure.string :as str]
            [hc.client.conversation :as conversation]
            [hc.client.conversation.a11y :as a11y]
            [hc.client.workbench.model :as model]))

;; ---------------------------------------------------------------------------
;; State and context

(defn conversation-state
  [db]
  (or (:ui/conversation db) conversation/initial-state))

(defn- event-type
  [event]
  (let [value (or (:type event) (:event event))]
    (when (or (keyword? value) (string? value))
      (-> value
          name
          str/lower-case
          (str/replace "_" "-")
          keyword))))

(defn- event-name
  [event]
  (let [value (or (:name event) (:event event))]
    (when (or (keyword? value) (string? value))
      (-> value
          name
          str/lower-case
          (str/replace "_" "-")
          keyword))))

(defn- event-value
  [event]
  (or (:value event) (:data event) event))

(defn context
  "Build the reducer context for SESSION-ID.  EPOCH is the per-session Owner
  command epoch resolved by the caller; nil keeps the reducer's last value."
  [db session-id epoch]
  (let [session (model/session-by-id db session-id)
        projected (when session (model/session-with-model session))]
    (cond-> {:session-id session-id
             :session session
             :connection (:ui/connection db)
             :platform (or (:ui/platform db) :desktop)
             :layout (a11y/layout-for-width (:ui/viewport-width db))}
      (some? epoch) (assoc :command-epoch epoch)
      (some? projected) (assoc :model (:model projected)))))

;; ---------------------------------------------------------------------------
;; Mirror

(defn- wire-kind
  [kind]
  (case kind
    :send :send-message
    :stop :stop-run
    :unpark :answer-ask
    kind))

(defn- mirror-status
  [status]
  (case status
    :pending :submitting
    status))

(defn- mirror-command
  [command]
  {:command-id (:command-id command)
   :command-epoch (:command-epoch command)
   :kind (wire-kind (:kind command))
   :session-id (:session-id command)
   :status (mirror-status (:status command))
   :payload (cond-> (assoc (:payload command)
                           :kind (wire-kind (:kind command))
                           :session-id (:session-id command))
              (:submitted-version command)
              (assoc :draft-version (:submitted-version command)))
   :created-at (:at command)
   :message (:error command)})

(defn- preserve-save-state
  "Keep the browser persistence feedback that lives on the flat draft.
  Only carry it over when the reducer's version did not move; a new edit must
  show `:unsaved`/`:storage-unavailable`, not a stale `:saved`."
  [old-drafts new-drafts]
  (into {}
        (map (fn [[session-id draft]]
               (let [old (get old-drafts session-id)]
                 [session-id
                  (if (and (map? old)
                           (= (:version old) (:version draft)))
                    (merge draft (select-keys old [:save-state
                                                   :saved-version
                                                   :save-error]))
                    draft)])))
        (or new-drafts {})))

(defn mirror
  "Write the reducer state and its derived flat keys into DB."
  [db conv]
  (let [drafts (preserve-save-state (:ui/draft db) (:drafts conv))
        ;; `ask/new-ask` does not keep the session id, but the session view
        ;; filters cards by it.  The nested `:asks` key is the authority.
        asks-by-session (into {}
                              (for [[session-id cards] (:asks conv)]
                                [session-id
                                 (into {}
                                       (for [[ask-id card] cards]
                                         [ask-id (assoc card :session-id
                                                        (or (:session-id card)
                                                            session-id))]))]))
        asks (into {}
                   (for [[_ cards] asks-by-session
                         [ask-id card] cards]
                     [ask-id card]))
        commands (into {}
                       (map (fn [[command-id command]]
                              [command-id (mirror-command command)]))
                       (:commands conv))]
    (assoc db
           :ui/conversation (assoc conv :drafts drafts :asks asks-by-session)
           :ui/draft drafts
           :ui/stream (:streams conv)
           :ui/ask asks
           ;; App-scoped commands (session create/list/metadata) still live
           ;; only in the flat table; conversation commands win by id.
           :ui/command (merge (:ui/command db) commands))))

(defn apply-actions
  "Apply ACTIONS to the reducer for SESSION-ID, then mirror the result.

  EPOCH is the per-session Owner epoch.  A nil epoch keeps the reducer's last
  value and is only valid for read-only actions."
  [db {:keys [session-id epoch]} actions]
  (let [base (-> (conversation-state db)
                 ;; Persisted drafts live on the flat key before the first
                 ;; reducer action.  Seed them once so a reload keeps the
                 ;; text, version, and save-state the operator already had.
                 (update :drafts #(merge (:ui/draft db) %))
                 (merge (context db session-id epoch)))
        conv (reduce conversation/apply-action base actions)]
    (mirror db conv)))

(defn take-outbox
  [db]
  (conversation/take-outbox (conversation-state db)))

;; ---------------------------------------------------------------------------
;; Server events -> reducer actions

(defn- receipt-inner
  [receipt]
  (let [inner (:receipt receipt)]
    (if (map? inner) inner receipt)))

(defn- receipt-status
  [receipt]
  (let [inner (receipt-inner receipt)
        value (or (:status inner) (:status receipt) (:result inner))
        ok? (or (:ok? receipt) (:ok? inner))]
    (cond
      (or (keyword? value) (string? value))
      (let [status (-> value name str/lower-case keyword)]
        (if (= :refused status) :rejected status))

      ;; A gateway/Owner failure without an inner receipt (for example
      ;; COMMAND_STORE_FAILED) is still a rejected command.  Without this
      ;; branch the reducer never receives the acknowledgement, so an
      ;; optimistically cleared draft is never restored.
      (false? ok?) :rejected

      (and (true? ok?) (nil? (:receipt receipt)))
      :not-found

      :else nil)))

(defn- receipt-command-id
  [receipt]
  (let [inner (receipt-inner receipt)]
    (or (:command-id receipt) (:commandId receipt) (:command-id inner))))

(defn- receipt-run-id
  [receipt]
  (let [inner (receipt-inner receipt)
        result (or (:result inner) (:result receipt))]
    (or (:run-id inner) (:runId inner)
        (:run-id receipt) (:runId receipt)
        (get-in result [:run-id]) (get-in result [:runId]))))

(defn- stop-receipt-stopped?
  [conv command receipt]
  (let [stream-state (get-in conv [:streams (:session-id command)])
        receipt-run (receipt-run-id receipt)
        current-run (:run-id stream-state)
        stop-run (:stop-run-id stream-state)]
    (or (nil? receipt-run)
        (nil? current-run)
        (= receipt-run current-run)
        (= receipt-run stop-run))))

(defn- receipt-ack
  [db event]
  (let [value (event-value event)
        command-id (receipt-command-id value)
        conv (conversation-state db)
        command (get-in conv [:commands command-id])
        status (receipt-status value)]
    (when (and command
               (not= :not-found status)
               (contains? #{:accepted :applied :replayed :rejected :refused
                            :failed :unknown}
                          status))
      (let [accepted? (contains? #{:accepted :applied :replayed} status)
            inner (receipt-inner value)]
        {:session-id (:session-id command)
         :action {:type :composer/ack
                  :command-id command-id
                  :status (if accepted? :accepted :rejected)
                  :error (or (:error inner) (:error value))
                  :stopped? (and (= :stop (:kind command))
                                 (stop-receipt-stopped? conv command value))
                  :at (or (:at value) (:at inner))}}))))

(defn- ask-action
  [db event]
  (let [value (event-value event)
        name (event-name event)
        type (event-type event)]
    (cond
      (or (= :hc/ask name) (= :hc/ask type))
      {:session-id (or (:session-id value) (:session-id event)
                       (model/selected-session-id db))
       :action {:type :ask/show
                :session-id (or (:session-id value) (:session-id event)
                                (model/selected-session-id db))
                :ask value}}

      (or (= :hc/ask-ack name) (= :hc/ask-ack type))
      (let [command-id (or (:command-id value) (:commandId value))
            conv (conversation-state db)
            command (some (fn [[_ c]]
                            (when (= command-id (:command-id c)) c))
                          (:commands conv))]
        (when command
          {:session-id (:session-id command)
           :action {:type :composer/ack
                    :command-id command-id
                    :status (if (contains? #{:accepted :applied}
                                           (receipt-status value))
                              :accepted
                              :rejected)
                    :at (:at value)}}))

      :else nil)))

(defn- stream-action
  [db event]
  (let [value (event-value event)
        name (event-name event)
        type (or name (event-type event))
        session-id (or (:session-id value) (:session-id event)
                       (model/selected-session-id db))
        message-id (or (:message-id value) (:messageId value)
                       (get-in db [:ui/run :message-id]))
        at (or (:at value) (:timestamp value) (:at event))]
    (case type
      :run-started
      {:session-id session-id
       :action {:type :stream/begin
                :session-id session-id
                :message-id message-id
                :at at}}

      :text-message-start
      {:session-id session-id
       :action {:type :stream/begin
                :session-id session-id
                :message-id message-id
                :at at}}

      :text-message-content
      {:session-id session-id
       :action {:type :stream/chunk
                :session-id session-id
                :message-id message-id
                :text (or (:delta value) (:text value) (:content value) "")
                :seq (:seq value)
                :at at}}

      :text-message-chunk
      {:session-id session-id
       :action {:type :stream/chunk
                :session-id session-id
                :message-id message-id
                :text (or (:delta value) (:text value) (:content value) "")
                :seq (:seq value)
                :at at}}

      :tool-call-result
      {:session-id session-id
       :action {:type :stream/tool-result
                :session-id session-id
                :message-id message-id
                :tool-call-id (or (:tool-call-id value) (:toolCallId value)
                                  (:id value))
                :status (or (:status value) :succeeded)
                :completed-before-stop? (:completed-before-stop? value)
                :at at}}

      :run-finished
      {:session-id session-id
       :action {:type :stream/finished
                :session-id session-id
                :at at}}

      :run-error
      {:session-id session-id
       :action {:type :stream/interrupted
                :session-id session-id
                :reason (or (:message value) (:reason value))
                :at at}}

      nil)))

(defn apply-event-db
  "Feed one server event to the conversation reducer and mirror the result.

  A database without `:ui/conversation` is returned unchanged, which keeps
  the pre-reducer workbench unit tests on their existing path."
  [db event]
  (if-not (:ui/conversation db)
    db
    (let [entry (or (receipt-ack db event)
                    (ask-action db event)
                    (stream-action db event))]
      (if-let [action (:action entry)]
        (apply-actions db {:session-id (:session-id entry)} [action])
        db))))
