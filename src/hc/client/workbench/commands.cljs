(ns hc.client.workbench.commands
  "Command construction for the workbench.

  Every server-bound action follows the same path:

    1. read the latest server-confirmed connection gate;
    2. build a command with a stable `command-id` and the current
       `command-epoch`;
    3. record it in `:ui/command` as `:submitting`;
    4. hand it to the transport;
    5. wait for a server receipt before clearing a draft or closing a run.

  No function here marks a domain state as complete on its own."
  (:require [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.conversation.draft :as draft]
            [hc.client.workbench.conversation-bridge :as conversation-bridge]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.sync-bridge :as bridge]
            [hc.client.workbench.transport :as transport]))

(defn- now []
  (str (js/Date.)))

(defn- new-id []
  (str (random-uuid)))

(defn- notify!
  [this tone text]
  (comp/transact! this
                  [(mutations/notify
                    {:notice {:id (str "cmd-" (random-uuid))
                              :tone tone
                              :text text
                              :at (now)}})]))

(defn show-notice!
  "Public notice helper for shell views that do not own a command."
  [this tone text]
  (notify! this tone text))

(defn- blocked!
  [this reason]
  (notify! this :warn (model/availability-reason {:reason reason}))
  {:ok? false :reason reason})

(defn- track!
  "Record the command synchronously before transport.  A fast receipt must
  never arrive before `:ui/command` contains the command id."
  [this command]
  (when-let [app (comp/any->app this)]
    (swap! (:com.fulcrologic.fulcro.application/state-atom app)
           assoc-in [:ui/command (:command-id command)] command)
    (application/schedule-render! app))
  command)

(defn- mark-failed!
  [this command-id message]
  (comp/transact! this
                  [(mutations/update-command
                    {:command-id command-id
                     :changes {:status :failed
                               :message message
                               :updated-at (now)}})] ))

(defn- send-command!
  [this command]
  (if (transport/command! {:command-id (:command-id command)
                           :command-epoch (:command-epoch command)
                           :subscription-id (bridge/subscription)
                           :payload (:payload command)})
    {:ok? true :command command :command-id (:command-id command)}
    (do
      (mark-failed! this (:command-id command) "Sente 通道未接受该命令")
      (notify! this :danger "命令未发送：实时通道未打开或已中断。")
      {:ok? false :reason :transport-not-open :command command})))

;; ---------------------------------------------------------------------------
;; Conversation commands

(defn- app-of
  "Return the Fulcro app for a component instance or the app itself."
  [this]
  (or (comp/any->app this) this))

(defn send-snapshot!
  "Send the exact snapshot captured by the conversation surface.

  The reducer owns the draft version, the send command, and the receipt
  state.  This wrapper only supplies the app object."
  [this session-id snapshot]
  (conversation-bridge/send-snapshot! (app-of this) session-id snapshot))

(defn send-message!
  "Legacy entry point: snapshot the workbench draft and send it."
  ([this session-id] (send-message! this session-id nil))
  ([this session-id {:keys [text]}]
   (let [app (app-of this)
         db (application/current-state app)
         draft (cond-> (draft/draft (:ui/draft db) session-id)
                 (string? text) (draft/edit {:text text}))]
     (conversation-bridge/send-snapshot! app session-id
                                         (draft/snapshot draft)))))

(defn stop-run!
  "Request a stop.  The reducer freezes the visible answer immediately and
  only the Owner receipt confirms it."
  [this session-id source]
  (conversation-bridge/stop! (app-of this) session-id source))

(defn answer-ask!
  "Submit an S1 answer through the conversation reducer."
  [this answer]
  (conversation-bridge/answer-ask! (app-of this) answer))

(defn transfer-session!
  "Turn one temporary session into a normal session."
  [this session-id]
  (let [epoch (bridge/command-epoch-for-session session-id)]
    (cond
      (not (bridge/command-allowed?))
      (blocked! this :connection-not-ready)

      (nil? epoch)
      (blocked! this :command-epoch-unknown)

      :else
      (let [command-id (new-id)
            command {:command-id command-id
                     :command-epoch epoch
                     :kind :session-transfer
                     :session-id session-id
                     :status :submitting
                     :created-at (now)
                     :payload {:kind :session/transfer
                             :session-id session-id}}]
        (track! this command)
        (send-command! this command)))))

(defn discard-session!
  "End a temporary session.  The server excludes it from the deleted area."
  [this session-id]
  (let [epoch (bridge/command-epoch-for-session session-id)]
    (cond
      (not (bridge/command-allowed?))
      (blocked! this :connection-not-ready)

      (nil? epoch)
      (blocked! this :command-epoch-unknown)

      :else
      (let [command-id (new-id)
            command {:command-id command-id
                     :command-epoch epoch
                     :kind :session-delete
                     :session-id session-id
                     :status :submitting
                     :created-at (now)
                     :payload {:kind :session/delete
                             :session-id session-id}}]
        (track! this command)
        (send-command! this command)))))

(defn request-resync!
  [this reason]
  (let [result (bridge/resync! reason)]
    (when-not (:ok? result)
      (notify! this :warn "实时通道未打开，无法请求重同步。"))
    result))

(defn reconnect!
  [this]
  (bridge/reconnect!)
  (notify! this :info "正在重新建立实时通道并获取新的服务器基线。"))

(defn history-command!
  "Send one explicit history command.  The server appends versions or creates a
  branch; it never auto-sends a message.  OPTS may carry `:selection-seq` for
  commands whose accepted receipt may select a new session."
  ([this session-id kind payload]
   (history-command! this session-id kind payload nil))
  ([this session-id kind payload opts]
   (let [epoch (bridge/command-epoch-for-session session-id)]
     (cond
       (not (bridge/command-allowed?))
       (blocked! this :connection-not-ready)

       (nil? epoch)
       (do
         ;; The global app epoch must never stand in for an Owner epoch.  Ask
         ;; for a fresh baseline so the session snapshot can publish its own
         ;; `:command-epoch`, then let the operator retry.
         (bridge/resync! :session-command-epoch-missing)
         (blocked! this :command-epoch-unknown))

       :else
       (let [command-id (new-id)
             command (cond-> {:command-id command-id
                              :command-epoch epoch
                              :kind kind
                              :session-id session-id
                              :status :submitting
                              :created-at (now)
                              :payload (assoc payload
                                             :kind kind
                                             :session-id session-id)}
                       (contains? opts :selection-seq)
                       (assoc :selection-seq (:selection-seq opts)))]
         (track! this command)
         (send-command! this command))))))

(defn session-metadata!
  "Send one real session metadata command under that session's command scope.

  KINDS are `:session/rename`, `:session/pin`, `:session/archive`,
  `:session/restore`, `:session/delete`, and `:session/purge`.  PAYLOAD is
  the action payload without `:kind`/`:session-id`; the helper adds both and
  the exact session's Owner epoch.  The server router validates that epoch
  before routing metadata to the Owner or, for `:session/purge`, stopping
  the Owner and removing the record.  The returned command is tracked in
  `:ui/command`, and only the server receipt changes its status."
  [this session-id kind payload]
  (let [epoch (bridge/command-epoch-for-session session-id)]
    (cond
      (not (bridge/command-allowed?))
      (blocked! this :connection-not-ready)

      (nil? epoch)
      (do
        (bridge/resync! :session-command-epoch-missing)
        (blocked! this :command-epoch-unknown))

      (not (contains? #{:session/rename :session/pin :session/archive
                        :session/restore :session/delete :session/purge}
                      kind))
      (blocked! this :empty)

      (not (map? payload))
      (blocked! this :empty)

      :else
      (let [command-id (new-id)
            command {:command-id command-id
                     :command-epoch epoch
                     :kind kind
                     :session-id session-id
                     :status :submitting
                     :created-at (now)
                     :payload (assoc payload
                                    :kind kind
                                    :session-id session-id)}]
        (track! this command)
        (send-command! this command)))))

(defn session-list!
  "Request the session list, optionally including normal deleted sessions.

  The receipt is stored in `:ui/session-list` by the receipt reducer; the
  control never invents a deleted row."
  [this {:keys [include-deleted?]}]
  (let [epoch (bridge/command-epoch)]
    (cond
      (not (bridge/command-allowed?))
      (blocked! this :connection-not-ready)

      (nil? epoch)
      (blocked! this :command-epoch-unknown)

      :else
      (let [command-id (new-id)
            command {:command-id command-id
                     :command-epoch epoch
                     :kind :session/list
                     :status :submitting
                     :created-at (now)
                     :payload {:kind :session/list
                               :include-deleted? (boolean include-deleted?)}}]
        (track! this command)
        (send-command! this command)))))

(defn edit-message!
  ([this session-id message-id content]
   (edit-message! this session-id message-id content nil nil))
  ([this session-id message-id content attachments citations]
   (history-command! this session-id :history/edit-message
                     (cond-> {:message-id message-id :content content}
                       (some? attachments) (assoc :attachments attachments)
                       (some? citations) (assoc :citations citations)))))

(defn append-version!
  [this session-id message-id content source expected-version-id]
  (history-command! this session-id :history/append-version
                    (cond-> {:message-id message-id
                             :content content
                             :source source}
                      expected-version-id
                      (assoc :expected-version-id expected-version-id))))

(defn branch-session!
  "Create a branch and capture the selection it may change.

  A branch is created from the currently selected source session, but its
  accepted receipt can arrive (or be replayed after a baseline) after the
  operator has already navigated elsewhere.  `:selection-seq` lets the
  receipt reducer select the new branch only while that original selection
  is still current."
  [this session-id before-message-id title]
  (let [db (application/current-state this)
        selection-seq (get-in db [:ui/selection-seq] 0)]
    (history-command! this session-id :history/branch
                      (cond-> {:before-message-id before-message-id}
                        title (assoc :title title))
                      {:selection-seq selection-seq})))

(defn regenerate!
  [this session-id message-id]
  (history-command! this session-id :history/regenerate
                    {:message-id message-id}))

(defn select-version!
  [this session-id message-id version-id]
  (history-command! this session-id :history/select-version
                    {:message-id message-id :version-id version-id}))

(defn set-context-boundary!
  [this session-id message-id]
  (history-command! this session-id :history/set-context-boundary
                    {:message-id message-id}))

(defn clear-context-boundary!
  [this session-id]
  (history-command! this session-id :history/clear-context-boundary {}))

(defn set-recent-rounds!
  [this session-id rounds]
  (history-command! this session-id :history/set-recent-rounds
                    {:recent-rounds rounds}))

(defn prepare-compression!
  [this session-id message-ids summary]
  (history-command! this session-id :history/prepare-compression
                    {:message-ids message-ids :summary summary}))

(defn apply-compression!
  [this session-id compression-id summary]
  (history-command! this session-id :history/apply-compression
                    (cond-> {:compression-id compression-id}
                      (some? summary) (assoc :summary summary))))

(defn undo-compression!
  [this session-id compression-id]
  (history-command! this session-id :history/undo-compression
                    {:compression-id compression-id}))

(defn create-session-from-summary!
  "Create a new session from one applied compression summary.

  The local command kind stays `:session-create` so the existing receipt
  reducer selects the new session.  The server payload kind is distinct and
  copies no source message; only the reviewed summary travels."
  [this session-id compression-id title]
  (if-not (bridge/command-allowed?)
    (blocked! this :connection-not-ready)
    (let [db (application/current-state this)
          epoch (bridge/command-epoch)
          selection-seq (get-in db [:ui/selection-seq] 0)
          command-id (new-id)
          payload (cond-> {:kind :session/create-from-summary
                           :session-id session-id
                           :compression-id compression-id}
                    title (assoc :title title))
          command {:command-id command-id
                   :command-epoch epoch
                   :kind :session-create
                   :selection-seq selection-seq
                   :status :submitting
                   :created-at (now)
                   :payload payload}]
      (if (nil? epoch)
        (do
          (bridge/resync! :app-command-epoch-missing)
          (blocked! this :command-epoch-unknown))
        (do
          (track! this command)
          (send-command! this command))))))

(defn pin-attachment!
  [this session-id attachment-id]
  (history-command! this session-id :history/pin-attachment
                    {:attachment-id attachment-id}))

(defn unpin-attachment!
  [this session-id attachment-id]
  (history-command! this session-id :history/unpin-attachment
                    {:attachment-id attachment-id}))

(defn create-session!
  "Create a session through the real server command surface.

  The receipt carries the new `:session-id`; the event reducer selects it when
  the command is accepted.  Optional `:connection-id`/`:model-entry-id` bind
  the new session explicitly; otherwise the server applies its configured
  defaults."
  ([this] (create-session! this {}))
  ([this {:keys [title connection-id model-entry-id temporary?]}]
   (if-not (bridge/command-allowed?)
     (blocked! this :connection-not-ready)
     (let [db (application/current-state this)
           epoch (bridge/command-epoch)
           selection-seq (get-in db [:ui/selection-seq] 0)
           command-id (new-id)
           payload (cond-> {:kind :session/create
                            :title (or title "新会话")}
                     ;; Ordinary New reuses the current active blank session;
                     ;; an explicit model/connection or temporary session is a
                     ;; deliberate new record.
                     (and (not connection-id)
                          (not model-entry-id)
                          (not temporary?)) (assoc :reuse-blank? true)
                     connection-id (assoc :connection-id connection-id)
                     model-entry-id (assoc :model-entry-id model-entry-id)
                     temporary? (assoc :temporary? true))
           command {:command-id command-id
                    :command-epoch epoch
                    :kind :session-create
                    :selection-seq selection-seq
                    :status :submitting
                    :created-at (now)
                    :payload payload}]
       (if (nil? epoch)
         (do
           ;; App-scoped create needs the app-issued epoch; never send nil or
           ;; reuse a feed/session epoch.  Ask for a fresh baseline first.
           (bridge/resync! :app-command-epoch-missing)
           (blocked! this :command-epoch-unknown))
         (do
           (track! this command)
           (send-command! this command)))))))
