(ns hc.client.workbench.events
  "Pure run-event reduction for the workbench.

  `hc.client.sync` owns snapshots, deltas, and message snapshots.  This
  namespace owns the AG-UI run stream and project `CUSTOM` events that the
  sync adapter deliberately ignores: text chunks, tool calls, receipts, asks,
  and run lifecycle.

  Every function takes and returns a Fulcro-shaped database map.  It performs
  no I/O and never invents a terminal domain state.  A tool whose arguments
  are complete stays visibly waiting until the server supplies a terminal
  status."
  (:require [clojure.string :as str]
            [hc.client.conversation.ask :as ask]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]
            [hc.client.search.model :as search-model]
            [hc.client.workbench.model :as model]))

;; ---------------------------------------------------------------------------
;; Event access

(defn event-type
  [event]
  (let [value (or (model/field event :type)
                  (model/field event :event))]
    (when (or (keyword? value) (string? value))
      (-> value
          name
          str/lower-case
          (str/replace "_" "-")
          keyword))))

(def state-event-types
  #{:state-snapshot :state-delta :messages-snapshot})

(defn state-event?
  [event]
  (contains? state-event-types (event-type event)))

(defn- now-field
  [event]
  (or (model/field event :at)
      (model/field event :timestamp)
      (model/field event :created-at)))

(defn- event-session-id
  [db event]
  (or (model/field event :session-id)
      (model/field event :sessionId)
      (model/field event :threadId)
      (model/selected-session-id db)))

(defn- event-message-id
  [db event]
  (or (model/field event :message-id)
      (model/field event :messageId)
      (get-in db [:ui/run :message-id])))

;; ---------------------------------------------------------------------------
;; Messages

(defn- add-order
  [db message-id]
  (update db :message/order
          (fn [order]
            (let [order (vec (or order []))]
              (if (some #{message-id} order)
                order
                (conj order message-id))))))

(defn- add-session-message
  [db session-id message-id]
  (if-let [session (get-in db [:session/by-id session-id])]
    (let [keys (filter #(vector? (get session %))
                       [:message-ids :session/message-ids
                        :messages :session/messages])]
      (reduce (fn [db key]
                (update-in db [:session/by-id session-id key]
                           (fn [ids]
                             (let [ids (vec (or ids []))]
                               (if (some #{message-id} ids)
                                 ids
                                 (conj ids message-id))))))
              db keys))
    db))

(defn ensure-message
  "Create the visible assistant message if the run started before any state
  snapshot carried it.  Existing messages are left untouched."
  [db message-id session-id role at]
  (if (or (nil? message-id)
          (get-in db [:message/by-id message-id]))
    db
    (-> db
        (assoc-in [:message/by-id message-id]
                  {:message/id message-id
                   :message/role (or role :assistant)
                   :message/content ""
                   :message/status :streaming
                   :message/session-id session-id
                   :message/created-at at
                   :message/content-version 0})
        (add-order message-id)
        (add-session-message session-id message-id))))

(defn- message-field [db message-id k]
  (get-in db [:message/by-id message-id k]))

(defn- update-message [db message-id f & args]
  (apply update-in db [:message/by-id message-id] f args))

(defn- append-content
  [db message-id delta]
  (update-message db message-id
                  (fn [message]
                    (-> message
                        (update :message/content #(str (or % "") delta))
                        (update :message/content-version (fnil inc 0))))))

(defn- replace-content
  [db message-id text]
  (update-message db message-id
                  (fn [message]
                    (-> message
                        (assoc :message/content text)
                        (update :message/content-version (fnil inc 0))))))

;; ---------------------------------------------------------------------------
;; Stream state

(defn- stream-state [db session-id]
  (or (get-in db [:ui/stream session-id]) (stream/initial)))

(defn- put-stream [db session-id stream']
  (assoc-in db [:ui/stream session-id] stream'))

(defn- update-stream [db session-id f & args]
  (put-stream db session-id (apply f (stream-state db session-id) args)))

;; ---------------------------------------------------------------------------
;; Tool calls

(defn- tool-call-id [event]
  (or (model/field event :tool-call-id)
      (model/field event :toolCallId)
      (model/field event :id)))

(defn- find-tool [message id]
  (some #(when (= id (or (:tool-call-id %) (:id %))) %) (:tool-calls message)))

(defn- update-tool
  [db message-id tool-id f & args]
  (update-message db message-id
                  (fn [message]
                    (let [tools (vec (or (:tool-calls message) []))
                          found? (some #(= tool-id (or (:tool-call-id %) (:id %))) tools)]
                      (assoc message :tool-calls
                             (if found?
                               (mapv (fn [tool]
                                       (if (= tool-id (or (:tool-call-id tool) (:id tool)))
                                         (apply f tool args)
                                         tool))
                                     tools)
                               (conj tools
                                     (apply f {:tool-call-id tool-id
                                               :id tool-id
                                               :status :running
                                               :arguments-complete? false}
                                            args))))))))

;; ---------------------------------------------------------------------------
;; Receipts and asks

(defn- non-blank-string
  [x]
  (and (string? x) (not (str/blank? x))))

(defn- command-session-id [command]
  (or (:session-id command)
      (get-in command [:payload :session-id])))

(defn- command-draft-version [command]
  (or (:draft-version command)
      (get-in command [:payload :draft-version])))

(defn- command-snapshot [command]
  (or (:snapshot command)
      (get-in command [:payload :snapshot])))

(defn- receipt-inner
  "Gateway receipts wrap the Owner receipt under `:receipt` while keeping
  `:command-id` on the outer event."
  [receipt]
  (let [inner (model/field receipt :receipt)]
    (if (map? inner) inner receipt)))

(defn- receipt-status
  [receipt]
  (let [inner (receipt-inner receipt)
        value (or (model/field inner :status)
                  (model/field receipt :status)
                  (model/field inner :result))
        ok? (or (model/field receipt :ok?) (model/field inner :ok?))]
    (cond
      (or (keyword? value) (string? value))
      (let [status (-> value name str/lower-case keyword)]
        (if (= :refused status) :rejected status))

      (false? ok?) :rejected

      (and (true? ok?) (nil? (model/field receipt :receipt)))
      :not-found

      :else nil)))

(defn- receipt-run-id
  "Return the run a stop receipt names, whether the Owner put it on the
  receipt or under its `:result`."
  [receipt]
  (let [inner (receipt-inner receipt)
        result (or (model/field inner :result)
                   (model/field receipt :result))]
    (or (model/field inner :run-id)
        (model/field inner :runId)
        (model/field receipt :run-id)
        (model/field receipt :runId)
        (model/field result :run-id)
        (model/field result :runId))))

(defn epoch-error?
  "True when a receipt/error asks the client to resync its subscription and
  command epoch rather than retry the command."
  [value]
  (let [inner (if (map? value) (receipt-inner value) {})
        code (or (model/field inner :code)
                 (model/field value :code)
                 (some-> (model/field inner :error) (model/field :code))
                 (some-> (model/field value :error) (model/field :code)))]
    (contains? #{:COMMAND_EPOCH_RETIRED :COMMAND_EPOCH_UNKNOWN
                 :COMMAND_EPOCH_INVALID :STALE_SUBSCRIPTION}
               code)))

(defn- storage-commit-error?
  "True for a server-side command commit failure caused by a full store.

  `COMMAND_STORE_FAILED` is also used for unrelated commit errors; only the
  ENOSPC cause maps to the standard Chinese storage message."
  [error]
  (let [code (some-> (model/field error :code)
                     name
                     keyword)
        cause (str (or (model/field error :message) "")
                   " "
                   (or (get-in error [:details :cause])
                       (get-in error ["details" "cause"])
                       ""))]
    (and (= :COMMAND_STORE_FAILED code)
         (boolean (re-find #"(?i)no space|enospc|space left|disk full|quota"
                           cause)))))

(defn- receipt-message
  [inner receipt]
  (let [error (or (model/field inner :error)
                  (model/field receipt :error))
        message (or (model/field inner :message)
                    (model/field receipt :message))]
    (if (storage-commit-error? error)
      (str "空间不足，暂时无法保存更多内容。请释放空间或先导出草稿。"
           "（未确认保存；COMMAND_STORE_FAILED）")
      message)))

(defn- session-row-id
  "Read the exact session id from a raw `:session/list` row or a display
  session.  Deleted rows are absent from `:session/by-id`, so both shapes
  must be addressable by the same id."
  [session]
  (or (model/field session :session-id)
      (model/field session :id)))

(defn- without-session-row
  [sessions session-id]
  (vec (remove #(= session-id (session-row-id %))
               (or sessions []))))

(defn- active-session
  "Return SESSION with the visible active state.  The server snapshot is
  authoritative; this receipt projection only removes the deleted marker so
  the row can re-enter normal history before that snapshot lands."
  [session]
  (when (map? session)
    (-> session
        (assoc :status :active)
        (dissoc :deleted-at))))

(defn- restore-session
  "Move SESSION-ID from the deleted list back into normal history.

  The list receipt is the only copy of a deleted row.  Keep that row as the
  display copy when the confirmed table has none, and update an existing
  display copy in place when it does."
  [db session-id]
  (let [row (some #(when (= session-id (session-row-id %)) %)
                  (:ui/session-list db))
        active (active-session
                (or (not-empty (get-in db [:session/by-id session-id]))
                    row))]
    (cond-> (update db :ui/session-list without-session-row session-id)
      active (assoc-in [:session/by-id session-id] active))))

(defn- apply-receipt
  [db receipt]
  (let [inner (receipt-inner receipt)
        command-id (or (model/field receipt :command-id)
                       (model/field receipt :commandId)
                       (model/field inner :command-id))
        status (receipt-status receipt)
        at (or (now-field receipt) (now-field inner))]
    (if (= :not-found status)
      ;; `:not-found` is the transient answer to a receipt query, not a
      ;; command outcome.  Ignore it before touching `:ui/command`: a query
      ;; can race the real receipt, and it must never downgrade a command the
      ;; Owner already accepted.
      db
      (let [db (if-not (and command-id (contains? (:ui/command db) command-id))
                 db
                 (update-in db [:ui/command command-id]
                            merge
                            {:status status
                             :message (receipt-message inner receipt)
                             :error (or (model/field inner :error)
                                        (model/field receipt :error))
                             :receipt receipt
                             :updated-at at}))
            command (get-in db [:ui/command command-id])
            session-id (command-session-id command)
            new-session-id (or (model/field inner :session-id)
                               (model/field receipt :session-id))
            select-created! (fn [db session-id]
                              ;; A receipt may only move the operator who is
                              ;; already in the session workspace.  Selecting a
                              ;; new session while a settings or board form is
                              ;; open would silently retarget it.
                              (if (contains? #{nil :sessions} (:ui/route db))
                                (-> db
                                    (assoc :ui/selected-session-id session-id
                                           :ui/route :sessions)
                                    (update :ui/selection-seq (fnil inc 0)))
                                db))]
        (cond
          (and (= :session-create (:kind command))
               (= :accepted status)
               (non-blank-string new-session-id)
               (= (or (:selection-seq command) 0)
                  (or (:ui/selection-seq db) 0)))
          (select-created! db new-session-id)

          ;; A branch receipt can be replayed after a baseline.  Select the
          ;; new branch only while the selection captured at submission is
          ;; still current; a later operator jump to the source (or anywhere
          ;; else) must win over the older receipt.
          (and (= :history/branch (:kind command))
               (= :accepted status)
               (non-blank-string new-session-id)
               (contains? command :selection-seq)
               (= (:selection-seq command)
                  (or (:ui/selection-seq db) 0)))
          (let [branch-session (or (model/field inner :session)
                                   (get-in inner [:result :session]))
                branch-messages (or (model/field inner :messages)
                                    (get-in inner [:result :messages])
                                    (:messages branch-session))
                branch-order (or (model/field inner :message-order)
                                 (get-in inner [:result :message-order])
                                 (:message-order branch-session))]
            (cond-> (select-created! db new-session-id)
              (map? branch-session)
              (assoc-in [:session/by-id new-session-id] branch-session)
              (map? branch-messages)
              (update :message/by-id merge branch-messages)
              (sequential? branch-order)
              (update :message/order
                      (fn [order]
                        (vec (distinct (concat (or order []) branch-order)))))))

          ;; A list receipt is the only source of deleted sessions.  Keep the
          ;; raw summaries in local UI state; the normal `:session/by-id` table
          ;; remains the server display copy.
          (and (= :session/list (:kind command))
               (= :accepted status))
          (assoc db :ui/session-list
                 (vec (or (model/field inner :sessions)
                          (model/field receipt :sessions)
                          [])))

          ;; A restored session re-enters normal history.  Remove the stale
          ;; deleted row, then make the same id visible in the display table
          ;; so the deleted count drops and the session is selectable without
          ;; waiting for the next snapshot.  The next snapshot still wins.
          (and (= :session/restore (:kind command))
               (contains? #{:accepted :applied} status)
               session-id)
          (restore-session db session-id)

          ;; A permanent purge removes both the deleted row and any stale
          ;; display copy.  The next snapshot is authoritative; this receipt
          ;; only prevents the purged session from lingering in the UI.
          (and (= :session/purge (:kind command))
               (contains? #{:accepted :applied} status)
               session-id)
          (-> db
              (update :ui/session-list without-session-row session-id)
              (update :session/by-id
                      (fn [sessions] (dissoc (or sessions {}) session-id)))
              (update :ui/selected-session-id
                      (fn [selected]
                        (when-not (= selected session-id) selected))))

          ;; A stop receipt closes the optimistic freeze.  It only applies to
          ;; the stream that asked for the stop; a newer run must keep its
          ;; state.  The accepted receipt means the Owner accepted the cancel,
          ;; not that every resource has already quiesced.
          (and (= :stop-run (:kind command))
               command-id
               session-id
               (contains? #{:accepted :applied} status))
          (let [stream-state (stream-state db session-id)
                receipt-run (receipt-run-id receipt)
                current-run-id (:run-id stream-state)
                stop-run-id (:stop-run-id stream-state)
                same-run? (or (nil? receipt-run)
                              (nil? current-run-id)
                              (= receipt-run current-run-id))]
            (if (and same-run?
                     (or (stream/stopping? stream-state)
                         (and (some? receipt-run)
                              (some? current-run-id)
                              (= receipt-run current-run-id))
                         (and (some? stop-run-id)
                              (or (nil? receipt-run)
                                  (= receipt-run stop-run-id))
                              (or (stream/stopping? stream-state)
                                  (nil? current-run-id)))
                         ;; A late start may have cleared the local freeze.
                         ;; With no run identity and an idle visible stream,
                         ;; the accepted stop is still this session's
                         ;; authority and must release the composer.
                         (and (nil? current-run-id)
                              (nil? stop-run-id)
                              (not (stream/streaming? stream-state)))))
              (put-stream db session-id
                          (stream/stop-confirmed stream-state
                                                 {:reason :operator
                                                  :at at}))
              db))

          (and (= :stop-run (:kind command))
               command-id
               session-id
               (= :rejected status))
          (let [stream-state (stream-state db session-id)
                receipt-run (receipt-run-id receipt)
                current-run-id (:run-id stream-state)
                same-run? (or (nil? receipt-run)
                              (nil? current-run-id)
                              (= receipt-run current-run-id))]
            (if (and same-run? (stream/stopping? stream-state))
              (put-stream db session-id
                          (stream/stop-rejected
                           stream-state
                           (or (model/field inner :error)
                               (model/field receipt :error)
                               {:code :STOP_REJECTED
                                :message "服务器拒绝了停止请求"})))
              db))

          ;; Only a send owns the draft.  Stop/ask/settings receipts must not
          ;; clear, restore, or remount the composer.
          (and (= :send-message (:kind command))
               command-id
               session-id
               (contains? #{:accepted :applied} status))
          (let [version (command-draft-version command)
                drafts (:ui/draft db)
                current (draft/draft drafts session-id)
                cleared? (= version (:version current))]
            (cond-> (assoc-in db [:ui/draft session-id]
                              (draft/clear-submitted current version))
              cleared? (update-in [:ui/draft-reset session-id] (fnil inc 0))))

          (and (= :send-message (:kind command))
               command-id
               session-id
               (= :rejected status))
          (let [drafts (:ui/draft db)
                current (draft/draft drafts session-id)
                snapshot (command-snapshot command)
                restored? (and snapshot (draft/blank? current))]
            (cond-> (assoc-in db [:ui/draft session-id]
                              (if snapshot
                                (draft/restore-submission current snapshot)
                                current))
              restored? (update-in [:ui/draft-reset session-id] (fnil inc 0))))

          :else db)))))

(defn- apply-ask
  [db ask-event]
  (let [ask-id (or (model/field ask-event :ask-id)
                   (model/field ask-event :askId)
                   (model/field ask-event :id))
        value (or (model/field ask-event :value)
                  (model/field ask-event :ask)
                  ask-event)
        ask (assoc (ask/new-ask (cond-> value
                                  (and ask-id (nil? (:ask-id value))) (assoc :ask-id ask-id)))
                   :session-id (or (model/field ask-event :session-id)
                                   (model/field value :session-id)))]
    (assoc-in db [:ui/ask (:ask-id ask)]
              (ask/merge-ask (get-in db [:ui/ask (:ask-id ask)]) ask))))

(defn- apply-ask-ack
  [db receipt]
  (let [command-id (or (model/field receipt :command-id)
                       (model/field receipt :commandId))
        ask-id (or (model/field receipt :ask-id)
                   (model/field receipt :askId))
        accepted? (contains? #{:accepted :applied}
                             (receipt-status receipt))]
    (if-let [ask-id (or ask-id
                        (some->> (vals (:ui/command db))
                                 (filter #(= command-id (:command-id %)))
                                 first
                                 :payload
                                 :ask-id))]
      (update-in db [:ui/ask ask-id]
                 (fn [ask]
                   (if ask
                     (ask/ack ask command-id accepted? (now-field receipt))
                     ask)))
      db)))

;; ---------------------------------------------------------------------------
;; Run events

(defn- nudge-event?
  [event]
  (boolean (model/field event :nudge?)))

(defn- start-run
  [db event]
  (let [session-id (event-session-id db event)
        at (now-field event)
        run-id (or (model/field event :run-id)
                   (model/field event :runId))
        nudge? (nudge-event? event)
        db (if nudge?
             (-> db
                 (assoc :ui/nudge {:active? true
                                   :session-id session-id
                                   :run-id run-id
                                   :started-at at})
                 (update-stream session-id stream/start-run
                                {:run-id run-id}))
             (update-stream db session-id stream/start-run
                            {:run-id run-id}))
        db (if nudge?
             db
             (assoc db :ui/run {:session-id session-id
                              :run-id run-id
                              :thread-id (model/field event :thread-id)
                              :started-at at}))
        ;; The publisher's nudge RUN_STARTED carries no message-id and its
        ;; text is filtered from the visible stream.  Never borrow the
        ;; visible user run's message for an invisible nudge run.
        message-id (when-not nudge? (event-message-id db event))
        role (or (model/field event :role) :assistant)]
    (if message-id
      (-> db
          (ensure-message message-id session-id role at)
          (update-message message-id assoc :message/status :streaming))
      db)))

(defn- start-message
  [db event]
  (let [session-id (event-session-id db event)
        message-id (event-message-id db event)
        role (or (model/field event :role) :assistant)
        at (now-field event)]
    (let [db (-> db
                 (ensure-message message-id session-id role at)
                 (assoc-in [:ui/run :message-id] message-id))]
      (update-stream db session-id stream/begin {:message-id message-id}))))

(defn- text-delta
  [db event]
  (let [session-id (event-session-id db event)
        explicit-id (event-message-id db event)
        stream-state (stream-state db session-id)
        message-id (or explicit-id (:active-message-id stream-state))
        delta? (contains? event :delta)
        delta (if delta?
                (or (model/field event :delta) "")
                (or (model/field event :text)
                    (model/field event :content)
                    ""))
        ;; The publisher may omit RUN_STARTED on reconnect or after a stop.
        ;; A text chunk for a different assistant message is a new visible
        ;; run; do not let the previous stop freeze suppress it.
        stream-state (if (and message-id
                              (stream/frozen? stream-state)
                              (some? (:active-message-id stream-state))
                              (not= message-id (:active-message-id stream-state)))
                       (stream/start-run stream-state {:force? true})
                       stream-state)
        stream' (stream/chunk stream-state
                              {:seq (model/field event :seq)
                               :text delta
                               :message-id message-id})
        result (get-in stream' [:last-outcome :result])
        target-id (or message-id (:active-message-id stream'))]
    (-> db
        (put-stream session-id stream')
        (cond-> (and (= :appended result) target-id)
          (as-> d
                (let [d (ensure-message d target-id session-id :assistant
                                        (now-field event))]
                  (if delta?
                    (append-content d target-id (str delta))
                    (replace-content d target-id (str delta)))))))))

(defn- tool-start
  [db event]
  (let [message-id (event-message-id db event)
        tool-id (tool-call-id event)]
    (if message-id
      (update-tool db message-id tool-id
                   merge
                   {:name (or (model/field event :tool-call-name)
                              (model/field event :toolCallName)
                              (model/field event :name))
                    :status :running
                    :arguments-complete? false
                    :started-at (now-field event)})
      db)))

(defn- tool-args
  [db event]
  (let [message-id (event-message-id db event)
        tool-id (tool-call-id event)
        delta (or (model/field event :delta)
                  (model/field event :arguments)
                  "")]
    (if message-id
      (update-tool db message-id tool-id
                   (fn [tool]
                     (-> tool
                         (update :arguments #(str (or % "") delta))
                         (assoc :arguments-complete? false))))
      db)))

(defn- tool-end
  [db event]
  (let [message-id (event-message-id db event)
        tool-id (tool-call-id event)]
    (if message-id
      (update-tool db message-id tool-id
                   assoc :arguments-complete? true)
      db)))

(defn- tool-result
  [db event]
  (let [session-id (event-session-id db event)
        message-id (event-message-id db event)
        tool-id (tool-call-id event)
        status (some-> (or (model/field event :status) :succeeded)
                       name str/lower-case keyword)
        result (or (model/field event :result)
                   (model/field event :content))
        stream' (stream/tool-result (stream-state db session-id)
                                    {:tool-call-id tool-id
                                     :message-id message-id
                                     :status status
                                     :completed-before-stop? (model/field event :completed-before-stop?)
                                     :at (now-field event)})
        late? (= :late-tool-result (get-in stream' [:last-outcome :result]))
        sources (or (and (map? result) (seq (:sources result)) (:sources result))
                    (and (map? result) (seq (get-in result [:provenance :sources]))
                         (get-in result [:provenance :sources]))
                    (model/field event :sources))]
    (-> db
        (put-stream session-id stream')
        (cond-> (and message-id tool-id (not late?))
          (update-tool message-id tool-id
                       merge
                       {:status status
                        :result result
                        :terminal? (stream/tool-terminal? status)
                        :finished-at (now-field event)}))
        (cond-> (and message-id (seq sources))
          (update-message message-id update :message/sources
                          (fnil into []) sources)))))

(declare add-notice)

(defn- search-degraded?
  [event]
  (or (true? (model/field event :search-degraded?))
      (= :failed (some-> (model/field event :search-status)
                         name str/lower-case keyword))))

(defn- keywordish
  [value]
  (when (or (keyword? value) (string? value))
    (-> value name str/lower-case keyword)))

(defn- message-text
  [message]
  (str (or (:text message) (:message/content message)
           (:content message) "")))

(defn- attach-search-sources
  "Attach the run's public source cards to the assistant answer.

  The search answer can stream with `[S1]` labels and finish with an empty
  terminal text, so `apply-citations` never attaches a citation map.  The
  bounded public context still names every source; keeping those cards on the
  answer message lets the message view and the plain-text export render the
  real list instead of an empty one.  Idempotent by URL (or label)."
  [db session-id sources]
  (if (and session-id (seq sources))
    (let [labels (keep :label sources)
          cited? (fn [message]
                   (some #(str/includes? (message-text message)
                                         (str "[" % "]"))
                         labels))
          assistants (filter (fn [message]
                               (contains? #{:assistant "assistant"}
                                          (model/field message :role)))
                             (model/messages-for-session db session-id))
          target (or (last (filter cited? assistants))
                     (last assistants))]
      (if-let [id (or (:id target) (:message/id target)
                      (:message-id target))]
        (update-message
         db id update :message/sources
         (fn [existing]
           (let [seen (set (map #(or (:url %) (:label %)) existing))]
             (into (vec existing)
                   (remove #(contains? seen (or (:url %) (:label %)))
                           sources)))))
        db))
    db))

(defn- record-search-run
  "Record the server's search result for this turn in the local search panel.

  `RUN_FINISHED` carries the real `:search-status` from the run context.  The
  panel must say 已搜索/未搜索/失败 from that fact instead of staying at
  \"尚未搜索\".  Source cards keep the message citation path; the run view only
  adds `:source-cards` when the event actually carries them."
  [db session-id event]
  (let [status (keywordish (model/field event :search-status))
        context (model/field event :search-context)
        sources (or (some-> context :source-cards vec seq)
                    (model/field event :search-sources))
        must-failed? (true? (model/field event :must-search-failed?))
        error (model/field event :search-error)]
    (if (and session-id (or status context must-failed? error))
      ;; Keep only the bounded panel facts.  Copying the whole public context
      ;; into UI state duplicated the citation map, document summaries and
      ;; source cards on every run; the panel renders only these fields.
      (let [run (cond-> {:status (or status :failed)
                         :searched? (contains? #{:ok :empty :partial}
                                               (or status :failed))
                         :must-search-failed? must-failed?}
                  (seq sources) (assoc :source-cards (vec sources))
                  (some? (:omitted-source-count context))
                  (assoc :omitted-source-count (:omitted-source-count context))
                  error (assoc :error error))]
        (-> db
            (update-in [:ui/search-state session-id :last-run]
                       (fn [current] (merge current run)))
            (attach-search-sources session-id sources)))
      db)))

(defn- clear-session-run
  "A terminal run event is authoritative for the local display copy.  Clear
  the session run/unattended markers immediately so stop/send/badge do not
  wait for the next snapshot."
  [db session-id]
  (-> db
      (assoc-in [:session/by-id session-id :run-status] :finished)
      (assoc-in [:session/by-id session-id :unattended?] false)
      (assoc-in [:session/by-id session-id :nudge-active?] false)
      (assoc-in [:session/by-id session-id :turn-active?] false)
      (assoc-in [:session/by-id session-id :actor-active?] false)
      (assoc-in [:session/by-id session-id :current-run-id] nil)
      (assoc-in [:ui/nudge :active?] false)))

(defn- finish-run
  [db event]
  (if (nudge-event? event)
    (let [session-id (event-session-id db event)
          db (-> db
                 (assoc-in [:ui/nudge :active?] false)
                 (assoc-in [:ui/nudge :finished-at] (now-field event)))]
      (if (get-in db [:ui/run :run-id])
        db
        (put-stream db session-id
                    (stream/finished (stream-state db session-id)))))
    (let [session-id (event-session-id db event)
        message-id (or (event-message-id db event)
                       (get-in db [:ui/run :message-id]))
        stream' (stream/finished (stream-state db session-id))
        stopped? (stream/stopped? stream')]
    (-> db
        (put-stream session-id stream')
        (cond-> message-id
          (update-message message-id
                          assoc
                          :message/status (if stopped? :stopped :completed)
                          :message/finished-at (now-field event)))
        (record-search-run session-id event)
        (cond-> (search-degraded? event)
          (add-notice {:id (str "search-degraded-" session-id "-"
                                (or (model/field event :run-id)
                                    (model/field event :message-id)
                                    "unknown"))
                       :tone :info
                       :text "搜索失败，已继续回答；未获得可引用来源"
                       :at (now-field event)}))
        (clear-session-run session-id)
        (dissoc :ui/run)))))

(defn- assistant-text?
  [db session-id]
  (boolean
   (some (fn [message]
           (and (= :assistant (keyword (or (model/field message :role) :assistant)))
                (not (str/blank? (str (or (model/field message :content) ""))))))
         (model/messages-for-session db session-id))))

(defn- latest-send-command
  [db session-id]
  (->> (vals (:ui/command db))
       (filter #(and (= :send-message (:kind %))
                     (= session-id (command-session-id %))
                     (some? (command-snapshot %))))
       (sort-by #(str (:created-at %)))
       last))

(defn- cancelled-run?
  "Operator or server cancellation is a stopped run, not a failed answer.
  The publisher may deliver it as RUN_FINISHED :failed plus an error whose
  kind/code/reason is :cancelled or :user-cancel."
  [event]
  (let [status (keywordish (model/field event :status))
        error (model/field event :error)
        kind (keywordish (or (:kind error)
                             (:code error)
                             (:reason error)
                             (model/field event :reason)))]
    (or (= :cancelled status)
        (= :cancelled kind)
        (= :user-cancel kind)
        (= :user-cancel status))))

(defn- failed-run?
  [event]
  (let [status (keywordish (model/field event :status))
        error (model/field event :error)]
    (and (not (cancelled-run? event))
         (or (= :failed status)
             (map? error)))))

(defn- add-notice
  "Idempotent notice insertion keyed by `:id`."
  [db notice]
  (if (some #(= (:id %) (:id notice)) (:ui/notices db))
    db
    (update db :ui/notices (fnil conj []) notice)))

(defn- error-notice-id
  [session-id message error]
  (let [code (or (model/field error :code) :unknown)]
    (str "run-error-" session-id "-" (name code) "-"
         (hash (str message)))))

(defn- error-run
  [db event]
  (let [session-id (event-session-id db event)
        message-id (or (event-message-id db event)
                       (get-in db [:ui/run :message-id]))
        error-value (or (model/field event :error)
                        (model/field event :message))
        generic (or (model/field event :message)
                    (when (string? error-value) error-value)
                    "服务器报告本轮未完成")
        search-label (search-model/command-error-label
                      {:error error-value :message generic})
        message (or search-label generic)
        nudge? (nudge-event? event)
        db (if nudge?
             (-> db
                 (assoc-in [:ui/nudge :active?] false)
                 (assoc-in [:ui/nudge :error] message)
                 (add-notice {:id (str "nudge-error-" session-id "-"
                                       (or (model/field event :run-id) "unknown"))
                              :tone :danger
                              :text (str "自动继续失败：" message)
                              :at (now-field event)}))
             db)
        command (when-not nudge? (latest-send-command db session-id))
        restore? (and (not nudge?) command session-id
                      (not (assistant-text? db session-id)))
        db (if restore?
             (let [current (draft/draft (:ui/draft db) session-id)
                   snapshot (command-snapshot command)
                   restored? (and snapshot (draft/blank? current))
                   db (assoc-in db [:ui/draft session-id]
                                (if snapshot
                                  (draft/restore-submission current snapshot)
                                  current))]
               (cond-> db
                 restored? (update-in [:ui/draft-reset session-id] (fnil inc 0))
                 true (update-in [:ui/command (:command-id command)]
                                 merge
                                 {:status :failed
                                  :message message
                                  :error error-value
                                  :updated-at (now-field event)})))
             db)]
    (-> db
        (record-search-run session-id event)
        (put-stream session-id (stream/interrupted (stream-state db session-id) message))
        (cond-> message-id
          (update-message message-id
                          assoc
                          :message/status :error
                          :message/error message))
        (add-notice {:id (error-notice-id session-id message error-value)
                     :tone :danger
                     :text message
                     :at (now-field event)})
        (clear-session-run session-id)
        (dissoc :ui/run))))

(defn- cancelled-run
  "A cancellation terminal preserves the answer and marks the visible run
  stopped.  It never adds a failure notice or restores the sent draft."
  [db event]
  (let [session-id (event-session-id db event)
        message-id (or (event-message-id db event)
                       (get-in db [:ui/run :message-id]))
        stream' (-> (stream-state db session-id)
                    stream/finished
                    (assoc :stop-state :stopped
                           :stop-reason :user-cancel))]
    (-> db
        (record-search-run session-id event)
        (put-stream session-id stream')
        (cond-> message-id
          (update-message message-id
                          assoc
                          :message/status :stopped
                          :message/finished-at (now-field event)))
        (clear-session-run session-id)
        (dissoc :ui/run))))

;; ---------------------------------------------------------------------------
;; CUSTOM

(defn- custom-event
  [db event]
  (let [name (some-> (or (model/field event :name)
                         (model/field event :event))
                     name
                     str/lower-case
                     (str/replace "_" "-")
                     keyword)
        value (or (model/field event :value)
                  (model/field event :data)
                  event)]
    (case name
      :hc/receipt (-> db
                      (apply-receipt value)
                      (apply-ask-ack value))
      :hc/command-receipt (-> db
                              (apply-receipt value)
                              (apply-ask-ack value))
      :hc/ask (apply-ask db (cond-> value
                              (and (model/field event :session-id)
                                   (nil? (model/field value :session-id)))
                              (assoc :session-id (model/field event :session-id))))
      :hc/ask-ack (apply-ask-ack db value)
      ;; The server confirmed the parked tenure resumed.  The card already
      ;; froze on submit; this event must not fall into the unhandled-event
      ;; notice path.
      :hc/ask-resolved db
      :hc/run-started (start-run db value)
      :hc/run-finished (cond
                         (cancelled-run? value) (cancelled-run db value)
                         (failed-run? value) (error-run db value)
                         :else (finish-run db value))
      :hc/run-error (if (cancelled-run? value)
                      (cancelled-run db value)
                      (error-run db value))
      :owner/blocked
      (assoc db :ui/blocked
             (select-keys (or (:blocked value) value)
                          [:code :member :count :last-code :remedy]))
      (update db :ui/notices
              (fnil conj [])
              {:id (str "custom-" (hash [name value]))
               :tone :info
               :text (str "未处理的服务器事件：" (or name "CUSTOM"))
               :at (now-field event)}))))

;; ---------------------------------------------------------------------------
;; Entry point

(declare apply-event)

(defn recover-snapshot-error
  "Snapshot-only recovery: restore the latest send snapshot once when the
  server reports a run error and no assistant text arrived.  Deduplicated by
  command status/error so repeated snapshots do not spam notices."
  [db session-id error]
  (let [command (latest-send-command db session-id)]
    (if (and command
             (not (assistant-text? db session-id))
             (not= :failed (:status command))
             (not= error (:error command)))
      (:db (apply-event db {:type "RUN_ERROR"
                            :session-id session-id
                            :error error}))
      db)))

(defn apply-event
  "Reduce one non-state AG-UI event.

  Returns `{:db db' :handled? bool}`.  State events return `:handled? false`;
  the caller must pass them to `hc.client.sync` instead."
  [db event]
  (if-not (map? event)
    {:db db :handled? false}
    (let [type (event-type event)]
      (if (contains? state-event-types type)
        {:db db :handled? false}
        {:db
         (case type
           :run-started (start-run db event)
           :run-finished (cond
                           (cancelled-run? event) (cancelled-run db event)
                           (failed-run? event) (error-run db event)
                           :else (finish-run db event))
           :run-error (if (cancelled-run? event)
                        (cancelled-run db event)
                        (error-run db event))
           :text-message-start (start-message db event)
           :text-message-content (text-delta db event)
           :text-message-chunk (text-delta db event)
           :text-message-end db
           :tool-call-start (tool-start db event)
           :tool-call-args (tool-args db event)
           :tool-call-end (tool-end db event)
           :tool-call-result (tool-result db event)
           :custom (custom-event db event)
           db)
         :handled? true}))))
