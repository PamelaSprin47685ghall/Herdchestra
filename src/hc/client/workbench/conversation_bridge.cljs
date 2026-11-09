(ns hc.client.workbench.conversation-bridge
  "Transport adapter for the conversation reducer.

  Operator gestures are applied to `:ui/conversation` first.  The reducer's
  outbox is drained here, translated into the wire command kinds, and sent
  through the same Sente transport as every other workbench command.  Server
  receipts travel back through `events` -> `:composer/ack`; this namespace
  never decides that a command succeeded.

  `:ui/command` remains the transport mirror.  It is derived from the
  reducer's `:commands`, so the old status line and materials/browser
  listeners keep working without a second command owner."
  (:require [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.conversation :as conversation]
            [hc.client.conversation.draft :as draft]
            [hc.client.workbench.conversation-state :as conversation-state]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.sync-bridge :as bridge]
            [hc.client.workbench.transport :as transport]))

(defonce app-ref (atom nil))

;; Extra wire fields the surface captured with a send snapshot but the reducer's
;; draft shape does not own (search selection, per-turn instruction cards).
(defonce extras (atom {}))

(declare write!)

(defn set-app!
  [app]
  (reset! app-ref app))

(defn- handle-event!
  "Feed one non-state server event to the reducer after `events` has reduced
  the message/notice side.  The mirror wins over the legacy flat writes."
  [event]
  (when-let [app @app-ref]
    (let [db (application/current-state app)
          db' (conversation-state/apply-event-db db event)]
      (when-not (identical? db db')
        (write! app db')))))

(defn install!
  [app]
  (reset! extras {})
  (set-app! app)
  (bridge/ensure-event-listener! ::conversation handle-event!))

(defn- now []
  (str (js/Date.)))

(defn- current-db []
  (when-let [app @app-ref]
    (application/current-state app)))

(defn- notify!
  [tone text]
  (when-let [app @app-ref]
    (comp/transact! app
                    [(mutations/notify
                      {:notice {:id (str "conv-" (random-uuid))
                                :tone tone
                                :text text
                                :at (now)}})])))

(defn- write!
  "Merge the reducer-owned keys into the current Fulcro database.
  Other bridges may have written unrelated keys while this command was being
  prepared; never replace the whole map."
  [app db]
  (let [state-atom (:com.fulcrologic.fulcro.application/state-atom app)]
    (swap! state-atom
           (fn [current]
             (-> current
                 (assoc :ui/conversation (:ui/conversation db)
                        :ui/draft (:ui/draft db)
                        :ui/stream (:ui/stream db)
                        :ui/ask (:ui/ask db))
                 (update :ui/command #(merge % (:ui/command db))))))
    (application/schedule-render! app)
    db))

(defn- apply!
  [app session-id epoch actions]
  (let [db (application/current-state app)
        db' (conversation-state/apply-actions
             db {:session-id session-id :epoch epoch} actions)]
    {:db db' :conv (:ui/conversation db')}))

(defn- outcome-command
  "Return the command created by the reducer action that just ran.

  Scanning all commands for the newest kind can return a previous command
  when the new action was ignored or blocked; that would attach extras to an
  old envelope and report a stale command id as if it were new."
  [conv kind]
  (let [command-id (get-in conv [:last-outcome :command-id])]
    (when-let [command (and command-id (get-in conv [:commands command-id]))]
      (when (= kind (:kind command))
        command))))

(defn- wire-payload
  [command]
  (let [session-id (:session-id command)
        extra (get @extras (:command-id command))]
    (case (:kind command)
      :send
      (merge {:kind :send-message
              :session-id session-id}
             (:payload command)
             extra)

      :stop
      (let [;; The run identity and origin were frozen when the command was
            ;; enqueued.  Re-reading live state here could change the payload
            ;; of a resent command id and turn an idempotent replay into a
            ;; conflict.
            payload (:payload command)
            run-id (:run-id payload)
            origin (or (:origin payload)
                       (:origin extra)
                       :operator-stop)]
        {:kind :stop-run
         :session-id session-id
         :origin origin
         :reason :user-cancel
         :run-id run-id})

      :unpark
      (assoc (:payload command)
             :kind :answer-ask
             :session-id session-id)

      (:payload command))))

(defn- send-envelope!
  [command]
  (cond
    (nil? (:command-epoch command))
    {:ok? false :reason :command-epoch-unknown}

    :else
    (try
      (if (transport/command!
           {:command-id (:command-id command)
            :command-epoch (:command-epoch command)
            :subscription-id (bridge/subscription)
            :payload (wire-payload command)})
        {:ok? true}
        {:ok? false :reason :transport-not-open})
      (catch :default error
        {:ok? false :reason :transport-error :error (str error)}))))

(defn- reject-command!
  [db command reason]
  (conversation-state/apply-actions
   db {:session-id (:session-id command)}
   [{:type :composer/ack
     :command-id (:command-id command)
     :status :rejected
     :error {:code reason}
     :stopped? true
     :at (now)}]))

(defn- drain!
  "Send every queued reducer command.  A command without an epoch or a closed
  transport is rejected through the reducer so its draft/stream state is
  restored honestly."
  [app db]
  (let [{:keys [commands state]} (conversation/take-outbox
                                  (:ui/conversation db))
        db (conversation-state/mirror db state)]
    (loop [db db
           remaining (seq commands)]
      (if-let [command (first remaining)]
        (let [result (send-envelope! command)
              db (if (:ok? result)
                   db
                   (reject-command! db command (:reason result)))]
          (recur db (next remaining)))
        (write! app db)))))

(defn- outcome
  [db]
  (get-in db [:ui/conversation :last-outcome]))

(defn dispatch!
  "Apply ACTIONS for SESSION-ID and drain any resulting commands."
  [app {:keys [session-id actions]}]
  (let [epoch (when session-id
                (bridge/command-epoch-for-session session-id))
        {:keys [db]} (apply! app session-id epoch actions)
        db (drain! app db)]
    (if (contains? #{:queued :stop-and-send :accepted :rejected
                     :edited :attachment-added :attachment-removed
                     :quote-added :quote-removed :cleared :applied
                     :submitted :skipped :shown :hidden}
                   (:result (outcome db)))
      {:ok? true :db db :outcome (outcome db)}
      {:ok? false
       :db db
       :reason (or (:reason (outcome db)) (:result (outcome db)))
       :message (:message (outcome db))})))

;; ---------------------------------------------------------------------------
;; Operator gestures

(defn send-snapshot!
  "Send the exact snapshot captured by the conversation surface.

  The snapshot seeds the reducer draft for the send.  If the live draft moved
  on while a stop was in flight, it is restored after the command is queued:
  the captured snapshot is what the server receives, and the newer unsent
  text survives in the composer."
  [app session-id snapshot]
  (let [db (application/current-state app)
        epoch (bridge/command-epoch-for-session session-id)
        live (get-in db [:ui/draft session-id])
        live-signature (select-keys live [:text :attachments :quotes])
        snapshot-signature (select-keys snapshot [:text :attachments :quotes])]
    (cond
      (not (bridge/command-allowed?))
      (do (notify! :warn "尚未与服务器同步，命令不可提交。")
          {:ok? false :reason :connection-not-ready})

      (nil? epoch)
      (do (notify! :warn "服务器尚未提供该会话的命令 epoch；请等待同步或切换会话。")
          {:ok? false :reason :command-epoch-unknown})

      (not (map? snapshot))
      {:ok? false :reason :empty}

      :else
      (let [at (now)
            actions [{:type :draft/edit
                      :session-id session-id
                      :text (or (:text snapshot) "")
                      :cursor (:cursor snapshot)
                      :selection (:selection snapshot)
                      :composing? false
                      :at at}
                     {:type :draft/attachments
                      :session-id session-id
                      :attachments (vec (or (:attachments snapshot) []))}
                     {:type :draft/quotes
                      :session-id session-id
                      :quotes (vec (or (:quotes snapshot) []))}
                     {:type :draft/turn-selection
                      :session-id session-id
                      :turn-card-ids (:turn-card-ids snapshot)
                      :instruction-values (:instruction-values snapshot)
                      :temporary-read? (:temporary-read? snapshot)
                      :turn-selection-version (:turn-selection-version snapshot)
                      :at at}
                     {:type :composer/send
                      :session-id session-id
                      :snapshot snapshot
                      :at at}]
            {:keys [db conv]} (apply! app session-id epoch actions)
            send-command (outcome-command conv :send)]
        (when send-command
          (swap! extras assoc (:command-id send-command)
                 (select-keys snapshot [:search :turn-card-ids
                                        :instruction-values
                                        :temporary-read?
                                        :turn-selection-version])))
        (let [db (if (and live
                          (or (nil? send-command)
                              (and (true? (:deferred? snapshot))
                                   (not= live-signature snapshot-signature))))
                   ;; A blocked send, or newer unsent work during a captured
                   ;; stop-and-send: put the live draft back.  The captured
                   ;; snapshot is already on the command, so neither copy is
                   ;; lost.  An immediate send is never deferred: its live
                   ;; signature may lag the DOM snapshot, and restoring it
                   ;; would undo the draft clear.
                   (conversation-state/mirror
                    db
                    (assoc-in conv [:drafts session-id] live))
                   db)
              db (drain! app db)]
          (if (and send-command
                   (contains? #{:queued :stop-and-send}
                              (get-in db [:ui/conversation :last-outcome :result])))
            {:ok? true :command-id (:command-id send-command) :command send-command}
            {:ok? false
             :reason (or (get-in db [:ui/conversation :last-outcome :reason])
                         (get-in db [:ui/conversation :last-outcome :result]))
             :message (get-in db [:ui/conversation :last-outcome :message])}))))))

(defn send-message!
  "Legacy entry point: snapshot the workbench draft and send it."
  [app session-id]
  (let [db (application/current-state app)
        d (draft/draft (:ui/draft db) session-id)]
    (send-snapshot! app session-id (draft/snapshot d))))

(defn stop!
  "Request a stop for SESSION-ID.  The reducer freezes the visible stream
  immediately; only the Owner receipt confirms it."
  [app session-id source]
  (let [epoch (bridge/command-epoch-for-session session-id)]
    (cond
      (not (bridge/command-allowed?))
      (do (notify! :warn "尚未与服务器同步，命令不可提交。")
          {:ok? false :reason :connection-not-ready})

      (nil? epoch)
      (do (notify! :warn "服务器尚未提供该会话的命令 epoch；请等待同步或切换会话。")
          {:ok? false :reason :command-epoch-unknown})

      :else
      (let [{:keys [db conv]} (apply! app session-id epoch
                                      [{:type :composer/stop
                                        :session-id session-id
                                        :source source
                                        :at (now)}])
            stop-command (outcome-command conv :stop)]
        (let [db (drain! app db)]
          (if (contains? #{:queued :stop-and-send}
                         (get-in db [:ui/conversation :last-outcome :result]))
            {:ok? true :command-id (:command-id stop-command)}
            {:ok? false
             :reason (get-in db [:ui/conversation :last-outcome :reason])}))))))

(defn answer-ask!
  "Edit the S1 card and submit its answer through the reducer."
  [app {:keys [session-id ask-id text selected]}]
  (let [epoch (bridge/command-epoch-for-session session-id)]
    (cond
      (not (bridge/command-allowed?))
      (do (notify! :warn "尚未与服务器同步，命令不可提交。")
          {:ok? false :reason :connection-not-ready})

      (nil? epoch)
      (do (notify! :warn "服务器尚未提供该会话的命令 epoch；请等待同步或切换会话。")
          {:ok? false :reason :command-epoch-unknown})

      :else
      (dispatch! app
                 {:session-id session-id
                  :actions [{:type :ask/edit
                             :session-id session-id
                             :ask-id ask-id
                             :text text
                             :selected selected}
                            {:type :ask/submit
                             :session-id session-id
                             :ask-id ask-id
                             :at (now)}]}))))

(defn skip-ask!
  "Record an explicit S1 skip and resume the parked program.

  A skip is a real unpark answer (`{:skipped? true}`), not a hidden dismissal;
  it uses the same connection and per-session epoch gate as a normal answer."
  [app {:keys [session-id ask-id]}]
  (let [epoch (bridge/command-epoch-for-session session-id)]
    (cond
      (not (bridge/command-allowed?))
      (do (notify! :warn "尚未与服务器同步，命令不可提交。")
          {:ok? false :reason :connection-not-ready})

      (nil? epoch)
      (do (notify! :warn "服务器尚未提供该会话的命令 epoch；请等待同步或切换会话。")
          {:ok? false :reason :command-epoch-unknown})

      :else
      (dispatch! app
                 {:session-id session-id
                  :actions [{:type :ask/skip
                             :session-id session-id
                             :ask-id ask-id
                             :at (now)}]}))))

(defn edit-draft!
  "Apply one composer edit through the reducer so `:version` protection and
  the IME composition flag stay in one place."
  [app change]
  (dispatch! app
             {:session-id (:session-id change)
              :actions [(assoc change :type :draft/edit)]}))

(defn scroll!
  "Record a scroll observation.  The surface passes the real scroll offsets;
  the reducer derives follow mode from the distance and stores the offset so
  selecting the session again can restore it.  A boolean is accepted for
  older callers."
  [app session-id observation]
  (let [observation (if (boolean? observation)
                      (if observation
                        {:scroll-top 100 :scroll-height 100 :client-height 100}
                        {:scroll-top 0 :scroll-height 100 :client-height 100})
                      observation)]
    (dispatch! app
               {:session-id session-id
                :actions [{:type :stream/scroll
                           :session-id session-id
                           :scroll observation}]})))
