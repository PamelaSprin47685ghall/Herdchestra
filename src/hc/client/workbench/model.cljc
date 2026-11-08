(ns hc.client.workbench.model
  "Pure view projections for the workbench shell.

  This namespace contains no I/O, atoms, timers, Fulcro, or Framework7.
  It answers three questions for the shell:

    - what the current connection can honestly claim;
    - which server entities are visible for a route;
    - which controls have a real effect right now.

  A control is never enabled merely because it looks useful.  When the
  precondition is missing, `availability` returns the reason and the view
  shows that reason instead of a dead button."
  (:require [clojure.string :as str]
            [hc.client.conversation.a11y :as a11y]
            [hc.client.materials.model :as materials-model]
            [hc.client.settings.model :as settings-model]))

;; ---------------------------------------------------------------------------
;; Key access

(defn field
  "Read a field by keyword or its string form.  Server maps may use either."
  [m k]
  (when (map? m)
    (if (contains? m k)
      (get m k)
      (get m (if (keyword? k) (subs (str k) 1) (keyword k))))))

(defn id-of
  "Read a stable string entity ID from the server's entity shape.

  Server records use type-specific keys such as `:session-id`, `:message-id`,
  `:task-id`, and `:swarm-id`; normalized tables may also carry `:id`."
  [entity]
  (let [id (some #(field entity %)
                 [:id :session-id :message-id :swarm-id :task-id :action-id
                  :run-id :call-id :attempt-id :artifact-id :project-id
                  :instance-id :connection-id :profile-id :schedule-id
                  :goal-id :credential-id :channel-id :backup-id
                  :recovery-point-id])]
    (when (and (string? id) (not (str/blank? id))) id)))

(defn- non-blank-string [x]
  (and (string? x) (not (str/blank? x))))

;; ---------------------------------------------------------------------------
;; Routes

(def nav-items
  [{:route :sessions
    :label "会话与分支"
    :meta "对话、草稿、停止"
    :icon "chatbubbles"}
   {:route :board
    :label "Swarm 看板"
    :meta "fleet、任务树、槽位"
    :icon "grid"}
   {:route :assistants
    :label "助手与角色"
    :meta "模型绑定与能力"
    :icon "person_2"}
   {:route :materials
    :label "材料与扩展"
    :meta "文件、记忆、技能、MCP"
    :icon "folder"}
   {:route :settings
    :label "设置与数据"
    :meta "连接、外观、诊断"
    :icon "gear"}])

(def routes (set (map :route nav-items)))

(defn nav-item [route]
  (some #(when (= route (:route %)) %) nav-items))

(def shortcuts
  "The key bindings the workbench actually implements.

  The settings workspace renders this table verbatim, so it must never list a
  binding that has no handler.  Global history search is not wired to a server
  index yet; the row says so instead of claiming the feature."
  [{:id :settings
    :keys-label "Ctrl/Cmd+,"
    :action "打开设置"
    :scope "全局"}
   {:id :new-session
    :keys-label "Ctrl/Cmd+N"
    :action "新建会话"
    :scope "全局"}
   {:id :find-session
    :keys-label "Ctrl/Cmd+F"
    :action "聚焦会话过滤"
    :scope "会话列表"}
   {:id :global-search
    :keys-label "Ctrl/Cmd+Shift+F"
    :action "聚焦会话过滤；全局搜索尚未接入服务器索引"
    :scope "全局"}
   {:id :escape
    :keys-label "Escape"
    :action "关闭最上层弹层 → 输入法/选区 → 停止当前生成"
    :scope "全局"}])

(defn route
  "Normalize a route keyword, string, or hash fragment."
  [value]
  (let [value (cond
                (keyword? value) value
                (string? value) (keyword (str/replace (str/replace value #"^#?/?" "") #"/.*$" ""))
                :else :sessions)]
    (if (contains? routes value) value :sessions)))

(defn parse-hash
  "Parse `#/board` or `#/sessions/s-1` into `{:route :board :session-id nil}`."
  [hash]
  (let [raw (str/replace (or hash "") #"^#" "")
        parts (->> (str/split raw #"/")
                   (remove str/blank?)
                   vec)
        route (if (= "swarm" (first parts))
                :board
                (route (first parts)))
        session-id (when (= :sessions route) (second parts))]
    {:route route
     :session-id (when (non-blank-string session-id) session-id)}))

(defn route->hash
  ([value] (route->hash value nil))
  ([value session-id]
   (let [r (route value)]
     (str "#/" (name r)
          (when (and (= :sessions r) (non-blank-string session-id))
            (str "/" session-id))))))

;; ---------------------------------------------------------------------------
;; Connection

(def phase-labels
  {:disconnected "未连接"
   :connecting "正在连接"
   :connected-awaiting-sync "已连接，等待同步"
   :synced "已同步"
   :interrupted "连接中断"})

(def phase-descriptions
  {:disconnected "尚未建立到 Herdchestra 服务器的实时通道。服务器状态与命令均不可用。"
   :connecting "正在建立 Sente 通道；尚未取得服务器基线。"
   :connected-awaiting-sync "通道已打开，正在等待原子基线快照。命令暂不可用。"
   :synced "已取得服务器基线，命令可以携带 command-id 提交。"
   :interrupted "实时通道中断；界面保留最后确认的服务器副本，但不得把它当作当前状态。"})

(defn phase-tone
  [phase]
  (case phase
    :synced :ok
    :connected-awaiting-sync :warn
    :connecting :warn
    :interrupted :danger
    :danger))

(defn connection-view
  "Project the sync adapter state into the shell's connection view."
  [adapter]
  (let [phase (or (:phase adapter) :disconnected)
        pending-object? (boolean (or (some? (:pending-object adapter))
                                     (seq (:deferred-events adapter))))
        synced? (and (= :synced phase)
                     (some? (:epoch adapter))
                     (some? (:seq adapter))
                     (not pending-object?))]
    {:phase phase
     :pending-object? pending-object?
     :phase-label (get phase-labels phase (name phase))
     :description (if pending-object?
                    "已连接；正在获取大状态对象，命令暂不可用。"
                    (get phase-descriptions phase ""))
     :tone (phase-tone phase)
     :synced? synced?
     :command-allowed? synced?
     :can-retry? (contains? #{:disconnected :interrupted} phase)
     :can-resync? (contains? #{:connected-awaiting-sync :synced} phase)
     :subscription (:subscription adapter)
     :epoch (:epoch adapter)
     ;; Server maps may use keyword or string keys; the adapter accepts both,
     ;; so the UI projection must not show nil for a valid epoch.
     :command-epoch (or (get-in adapter [:db :sync :command-epoch])
                        (get-in adapter [:db "sync" "command-epoch"])
                        (get-in adapter [:server :sync :command-epoch])
                        (get-in adapter [:server "sync" "command-epoch"]))
     :seq (:seq adapter)
     :synced-at (:synced-at adapter)
     :reason (:reason adapter)
     :last-outcome (:last-outcome adapter)}))

(defn connection-label
  [connection]
  (if (map? connection)
    (or (:phase-label connection) "连接状态未知")
    "连接状态未知"))

;; ---------------------------------------------------------------------------
;; Server entity projections

(defn- table [db k]
  (let [v (field db k)]
    (if (map? v) v {})))

(defn- collection [db k]
  (let [v (field db k)]
    (cond
      (vector? v) v
      (sequential? v) (vec v)
      :else [])))

(defn- sort-key [entity]
  [(str (or (field entity :updated-at)
            (field entity :created-at)
            ""))
   (str (id-of entity))])

(defn session-summary
  [session]
  (let [id (id-of session)
        title (or (field session :title)
                  (field session :name)
                  (some-> (field session :last-message) :text)
                  "未命名会话")
        message-ids (or (field session :message-ids)
                        (field session :messages)
                        [])
        branch-of (field session :branch-of)
        binding (or (field session :model-binding)
                    (field session :model))
        model-id (or (field binding :model-entry-id)
                     (field binding :model-id)
                     (field binding :id)
                     (field session :model-id))
        model-label (or (field binding :display-name)
                        (field binding :label)
                        (field binding :model-name)
                        (field session :model-label))]
    {:id id
     :title (if (non-blank-string title) title "未命名会话")
     :status (or (field session :status) :idle)
     :pinned? (boolean (field session :pinned?))
     :updated-at (field session :updated-at)
     :created-at (field session :created-at)
     :message-count (count message-ids)
     :branch-of branch-of
     :model-id model-id
     :model-label (or model-label model-id)
     :run-status (or (field session :run-status)
                     (field session :status))
     :temporary? (boolean (field session :temporary?))}))

(defn session-with-model
  "Project the server session summary into the `:model`/`:model-id` shape the
  conversation surface and composer gate expect.

  The summary publishes an immutable `:model-binding` (the run snapshot) and
  a live `:model-availability` (whether that binding is still usable).  Both
  must reach the gate together: a binding alone says what the session chose,
  not whether the current configuration can still use it.  The projection is
  pure so the consumer test can exercise the same wire shape the browser
  receives."
  [session]
  (let [binding (or (field session :model-binding)
                    (field session :model))
        model-id (or (field binding :model-entry-id)
                     (field binding :model-id)
                     (field binding :id)
                     (field session :model-id))
        model-label (or (field binding :display-name)
                        (field binding :label)
                        (field binding :model-name)
                        (field session :model-label))
        model-capabilities (or (field binding :capabilities)
                               (field binding :input-types)
                               (field session :model-capabilities)
                               (get-in session [:model :capabilities]))
        model-limits (or (field binding :attachment-limits)
                         (field binding :limits)
                         (field session :attachment-limits)
                         (get-in session [:model :attachment-limits]))
        model-availability (or (field binding :availability)
                               (field session :model-availability)
                               (get-in session [:model :availability]))
        model (cond-> (if (map? (:model session)) (:model session) {})
                model-id (assoc :id model-id
                                :label (or model-label model-id))
                model-capabilities (assoc :capabilities model-capabilities)
                model-limits (assoc :attachment-limits model-limits)
                model-availability (assoc :availability model-availability))]
    (cond-> session
      (or model-id model-capabilities model-limits)
      (assoc :model model
             :model-id (or model-id (:id model))
             :model-label (or model-label (:label model) model-id)))))

(defn sessions
  "Return visible session summaries, pinned first, then most recently updated."
  [db]
  (->> (vals (table db :session/by-id))
       (keep (fn [session]
               (when (id-of session)
                 (session-summary session))))
       (sort (fn [a b]
               (let [pa (:pinned? a)
                     pb (:pinned? b)
                     ua (str (or (:updated-at a) ""))
                     ub (str (or (:updated-at b) ""))]
                 (cond
                   (not= pa pb) (if pa -1 1)
                   (not= ua ub) (compare ub ua)
                   :else (compare (str (:id a)) (str (:id b)))))))
       vec))

(defn filter-sessions
  "Filter visible session summaries by a local query.

  The query matches the title, the stable ID, and the model label.  A blank
  query returns every visible session; filtering never invents a session that
  the server did not publish."
  [sessions query]
  (let [q (some-> query str str/trim str/lower-case)]
    (if (str/blank? q)
      (vec sessions)
      (filterv (fn [{:keys [id title model-label]}]
                 (some #(str/includes? (str/lower-case (str %)) q)
                       [id title model-label]))
               sessions))))

(defn- message-id [m]
  (or (field m :id) (field m :message-id)))

(defn- message-session [m]
  (or (field m :session-id)
      (field m :message/session-id)
      (field m :session)))

(defn message-order
  "Return message IDs in display order for one session.

  Preference order: the session's explicit `:message-ids`, the session's
  `:messages` vector, the global `:message/order` filtered by session, then a
  stable timestamp/id sort.  Records are never dropped because an order is
  incomplete."
  [db session-id]
  (let [by-id (table db :message/by-id)
        session (get (table db :session/by-id) session-id)
        explicit (or (field session :message-ids)
                     (field session :messages))
        global-order (field db :message/order)
        belongs? (fn [id]
                   (or (nil? session-id)
                       (= session-id
                          (message-session (get by-id id)))))
        explicit-ids (when (and (sequential? explicit) (seq explicit))
                       (vec explicit))
        global-ids (when (and (sequential? global-order)
                              (seq global-order))
                     (vec global-order))
        ids (cond
              explicit-ids
              (let [listed (vec (distinct explicit-ids))
                    listed-set (set listed)
                    extras (->> global-ids
                                (remove listed-set)
                                (filter #(contains? by-id %))
                                (filter belongs?))]
                (concat listed extras))

              global-ids
              (->> global-order
                   (filter #(contains? by-id %))
                   (filter belongs?)
                   vec)

              :else
              (->> (vals by-id)
                   (filter #(or (nil? session-id)
                                (= session-id (message-session %))))
                   (sort-by (fn [m]
                              [(str (or (field m :created-at)
                                        (field m :at)
                                        (field m :seq)
                                        ""))
                               (str (message-id m))]))
                   (mapv message-id)))]
    (->> ids
         (filter #(contains? by-id %))
         (remove nil?)
         (distinct)
         vec)))

(defn messages-for-session
  [db session-id]
  (let [by-id (table db :message/by-id)]
    (->> (message-order db session-id)
         (mapv #(get by-id %))
         (remove (fn [message]
                   (or (true? (field message :nudge?))
                       (some? (field message :nudge-id))
                       (true? (field message :message/nudge?))
                       (some? (field message :message/nudge-id))
                       (some? (get message :message/nudge-id))
                       (some? (get message :message/nudge?)))))
         vec)))

(defn- source-entry
  [value]
  (cond
    (string? value)
    (when (non-blank-string value) {:label value})

    (map? value)
    (let [label (or (field value :title)
                    (field value :url)
                    (field value :text)
                    (field value :name))]
      (when (non-blank-string (str label))
        {:label (str label)
         :url (field value :url)
         :kind (or (field value :kind) (field value :type))}))

    :else nil))

(defn- source-list
  [value]
  (cond
    (nil? value) []
    (sequential? value) value
    :else [value]))

(defn session-sources
  "Project the source cards the server attached to one session's messages.

  Citations, sources, and quotes are real wire fields.  A message set without
  them yields an empty list; the view must say the server did not report
  sources rather than invent one."
  [db session-id]
  (->> (messages-for-session db session-id)
       (mapcat (fn [message]
                 (concat (source-list (or (field message :citations)
                                          (field message :message/citations)))
                         (source-list (or (field message :sources)
                                          (field message :message/sources)))
                         (source-list (or (field message :quotes)
                                          (field message :message/quotes))))))
       (keep source-entry)
       (reduce (fn [acc entry]
                 (if (some #(= (:label %) (:label entry)) acc)
                   acc
                   (conj acc entry)))
               [])
       vec))

(defn session-by-id [db session-id]
  (get (table db :session/by-id) session-id))

(defn resolve-selected-session-id
  "Return a selected ID that exists in the current visible session table.

  A stale selection (deleted, folded, or replaced by a new snapshot) falls
  back to the first visible session instead of leaving the conversation pane
  pointed at a missing entity.  The server's presentation `:session/active-id`
  is deliberately not a fallback here: it is shared process state, not this
  client's selection."
  [db requested]
  (or (when (and (non-blank-string requested)
                 (session-by-id db requested))
        requested)
      (some-> (first (sessions db)) :id)))

(defn run-active?
  "True when the server still reports a live run for this session.  This is
  used to allow an operator stop even if the local stream state is stale."
  [db session-id]
  (let [session (session-by-id db session-id)
        run-id (field session :current-run-id)
        run (when run-id (get (table db :run/by-id) run-id))
        status (or (field session :run-status)
                   (field run :status))]
    (boolean
     (or (and (string? run-id) (not (str/blank? run-id)))
         (contains? #{:running :streaming :starting :stopping :applying
                      :dispatching} status)))))

(defn stop-command-decision
  "Decide whether the workbench may submit an operator stop for SESSION-ID.

  Local stream activity is deliberately not part of this decision.  An
  unattended Nudge turn can be live while the visible stream is idle, so the
  Owner is the authority that accepts or refuses the stop.  The client only
  checks the transport gate, the selected session, its per-session command
  epoch, and whether a stop is already in flight."
  [{:keys [connection session-id epoch stream]}]
  (let [stopping? (= :stopping (some-> (field stream :stop-state) keyword))]
    (cond
      (not (true? (:command-allowed? connection)))
      {:ok? false :reason :connection-not-ready}

      (not (non-blank-string session-id))
      {:ok? false :reason :no-session}

      (nil? epoch)
      {:ok? false :reason :command-epoch-unknown}

      stopping?
      {:ok? false :reason :stopping}

      :else
      {:ok? true :reason nil})))

(defn- positive-epoch
  [value]
  (when (and (integer? value) (pos? value))
    value))

(defn session-command-epoch
  "Owner command epoch for one session.

  The global `:sync :command-epoch` is the app command epoch; it never
  authorizes a session command.  The AG-UI feed epoch is not a command
  epoch.  Deleted sessions are absent from `:session/by-id`; their rows
  survive only in `:ui/session-list`.  Both tables are searched by the exact
  session id, and a session without its own published epoch must block
  instead of borrowing the app epoch or another session's epoch."
  [db session-id]
  (when (and (string? session-id) (not (str/blank? session-id)))
    (let [by-id (field db :session/by-id)
          session (or (get by-id session-id)
                      (get by-id (str session-id)))
          listed (some (fn [row]
                         (when (= session-id
                                  (or (field row :session-id)
                                      (field row :id)))
                           row))
                       (or (field db :ui/session-list) []))
          epoch (or (positive-epoch (field session :command-epoch))
                    (positive-epoch (field listed :command-epoch)))]
      epoch)))

(defn resolve-session-command-epoch
  "Resolve one session's Owner epoch from the live display database first,
  then the confirmed adapter view.

  Deleted rows are absent from the adapter's confirmed `:session/by-id` and
  exist only in the display database's `:ui/session-list`.  Both lookups use
  the same exact session id.  The global app epoch and another session's
  epoch are never substituted."
  [display-db server-db session-id]
  (or (session-command-epoch display-db session-id)
      (session-command-epoch server-db session-id)))

(defn selected-session-id
  "Return the client's one selected session.

  `:ui/selected-session-id` is the only client selection authority.  Before
  the first local choice, the server's presentation `:session/active-id` is
  adopted as a bootstrap hint.  After that, a later snapshot cannot replace
  the local choice because `install-sync` preserves `:ui/*`.  A selection
  whose session disappeared falls back to the first visible session, never to
  the server's shared presentation pointer."
  [db]
  (let [local (field db :ui/selected-session-id)
        requested (if (non-blank-string local)
                    local
                    (field db :session/active-id))]
    (resolve-selected-session-id db requested)))

(defn current-session
  [db]
  (some-> (session-by-id db (selected-session-id db)) session-summary))

(defn overlay-stack
  "Return the overlays that Escape may close, topmost last.

  The workbench keeps navigation and details as visible columns in the wide
  band.  Their open flags are drawer state there, not modal state: treating
  them as overlays would let Escape close an invisible drawer and steal focus
  from a text selection.  Only the drawer layout exposes a backdrop and an
  Escape target.  Each entry names the control that opened it so focus can
  return to the real opener."
  [db]
  (if (a11y/overlay-layout? (:ui/viewport-width db))
    (cond-> []
      (:ui/sidebar-open db)
      (conj {:id :nav
             :trigger-id (or (:ui/sidebar-return-id db) "wb-menu")})

      (:ui/aux-open db)
      (conj {:id :aux
             :trigger-id (or (:ui/aux-return-id db) "wb-aux-toggle")}))
    []))

(defn escape-stop-allowed?
  "True only while the operator is looking at the conversation workspace.

  Escape in a settings or board form must not stop a background generation;
  those surfaces own their own Escape behavior."
  [db]
  (contains? #{nil :sessions} (:ui/route db)))

(defn branch-origin
  "Return the source session for a branch, or nil for an ordinary session.

  The server publishes `:branch-of` through the history projection (and
  `:origin-session-id` on the session summary).  A source that is no longer
  in the visible session table is reported as unavailable instead of being
  offered as a dead link."
  [db session-id]
  (let [session (session-by-id db session-id)
        origin-id (or (field session :branch-of)
                      (field session :origin-session-id))]
    (when (and (non-blank-string origin-id)
               (not= origin-id session-id))
      (let [origin (session-by-id db origin-id)]
        {:session-id origin-id
         :visible? (boolean origin)
         :title (when origin (:title (session-summary origin)))
         :origin-message-id (field session :origin-message-id)}))))

(defn missing-session-messages?
  "True when a visible session advertises messages but the normalized table
  has none for it.

  With a windowed baseline this means the session has no loaded message
  entities at all even though its summary advertises messages — the
  inconsistent or pruned case, fixed by one `:history/messages` fetch (or a
  resync when even that cannot help).  A partially loaded session is normal:
  the baseline publishes only the newest window and `:message-ids` keeps the
  full visible list, so `messages-for-session` returns the loaded subset and
  this predicate stays false.  A session with an explicit zero message count
  is not considered missing."
  [db session-id]
  (let [summary (some-> (session-by-id db session-id) session-summary)]
    (boolean
     (and summary
          (pos? (long (or (:message-count summary) 0)))
          (empty? (messages-for-session db session-id))))))

(defn session-messages-unloaded?
  "True when a session has loaded message entities but fewer than its
  summary's `:message-count`.

  Windowed baselines make this the normal state for long sessions: the
  newest window is loaded and older messages are fetched on demand through
  `:history/messages`.  It is a display hint for a \"load earlier\" entry,
  never a resync trigger."
  [db session-id]
  (let [summary (some-> (session-by-id db session-id) session-summary)
        expected (long (or (:message-count summary) 0))
        loaded (count (messages-for-session db session-id))]
    (boolean (and (pos? expected) (< loaded expected)))))

(defn has-older-messages?
  "True/false/nil for whether a session has messages older than the loaded
  window.

  The `:history/messages` receipt records `:has-older?` on the
  `:session/history` entry (and on the `:session/by-id` row when one
  exists).  Nil means no fetch has reported yet; callers should fall back
  to `session-messages-unloaded?`."
  [db session-id]
  (let [history (get (field db :session/history) session-id)
        row (session-by-id db session-id)]
    (or (field history :has-older?)
        (field row :has-older?))))

(defn swarm-summary
  [swarm]
  (let [tasks (or (field swarm :tasks) (field swarm :task-ids) [])
        roots (or (field swarm :root-tasks) (field swarm :root-task-ids) tasks)]
    {:id (id-of swarm)
     :slice-id (or (field swarm :slice-id) (id-of swarm))
     :worktree (field swarm :worktree)
     :status (or (field swarm :status) :queued)
     :root-count (count roots)
     :task-count (count tasks)
     :master-version (or (field swarm :master-version)
                         (field swarm :master-sha)
                         (field swarm :version))
     :join-queue (vec (or (field swarm :join-queue) []))
     :holder (field swarm :holder)
     :slot-id (field swarm :slot-id)}))

(defn swarms [db]
  (->> (vals (table db :swarm/by-id))
       (keep #(when (id-of %) (swarm-summary %)))
       (sort-by (fn [{:keys [status slice-id]}]
                  [(case status :running 0 :queued 1 :joined 2 :abandoned 3 :failed 4 5)
                   (str slice-id)]))
       vec))

(defn task-summary
  [task]
  {:id (id-of task)
   :goal (or (field task :goal) (field task :title) "未命名任务")
   :status (or (field task :status) :ready)
   :remaining-work (or (field task :remaining-work) (field task :remaining) "")
   :done-when (or (field task :done-when) (field task :done-condition) "")
   :swarm-id (or (field task :swarm-id)
                 (field task :swarm))
   :parent-id (or (field task :parent-id)
                  (field task :parent)
                  (some-> (field task :parent) id-of))
   :children (vec (or (field task :children) (field task :child-ids) []))
   :wait-for (vec (or (field task :wait-for) (field task :waiting-for) []))
   :owner (or (field task :owner) (field task :agent-id))
   :review-key (field task :review-key)})

(defn tasks [db]
  (->> (vals (table db :task/by-id))
       (keep #(when (id-of %) (task-summary %)))
       (sort-by (fn [{:keys [status id]}]
                  [(case status :ready 0 :waiting 1 :done 2 3) (str id)]))
       vec))

(defn task-tree
  "Build parent/child levels from `:parent-id` and explicit child IDs.

  A task whose parent is absent is a root.  Cycles are broken by visiting each
  ID once; the remaining tasks appear as roots rather than disappearing."
  [db]
  (let [by-id (into {} (map (juxt :id identity) (tasks db)))
        children-of (reduce (fn [acc {:keys [id parent-id children]}]
                              (cond-> acc
                                (contains? by-id parent-id)
                                (update parent-id (fnil conj []) id)

                                (seq children)
                                (update id (fnil into []) children)))
                            {}
                            (vals by-id))
        roots (->> (vals by-id)
                   (filter (fn [{:keys [id parent-id]}]
                             (or (nil? parent-id)
                                 (not (contains? by-id parent-id)))))
                   (map :id)
                   (vec))]
    (letfn [(walk [id depth seen]
              (when (and (contains? by-id id) (not (contains? seen id)))
                (let [task (get by-id id)
                      seen (conj seen id)]
                  (cons (assoc task :depth depth)
                        (mapcat #(walk % (inc depth) seen)
                                (get children-of id []))))))]
      (let [walked (vec (mapcat #(walk % 0 #{}) roots))
            walked-ids (set (map :id walked))]
        (into walked
              (for [id (sort (remove walked-ids (keys by-id)))]
                (assoc (get by-id id) :depth 0)))))))

(defn settings-state
  "The committed settings state shown by the settings workspace.

  The workbench keeps one settings state at `:ui/settings`; the assistants
  workspace reads role ownership from that same projection instead of a
  second profile or connection table that the server never publishes."
  [db]
  (or (field db :ui/settings)
      (settings-model/initial-state)))

(defn assistant-options
  "Assistant choices for materials forms.

  Reads any `:assistants` published in the workbench database, the materials
  projection or the settings projection, then adds distinct
  `:assistant-id` values from `:session/by-id`.  The selected session's
  assistant comes first.  The result is already normalized for the materials
  view; it never invents an assistant id."
  [db]
  (let [materials-state (or (field db :ui/materials) {})
        assistants (or (let [value (field db :assistants)]
                         (when (seq value) value))
                       (let [value (field materials-state :assistants)]
                         (when (seq value) value))
                       (let [value (field db :settings/assistants)]
                         (when (seq value) value))
                       (field (settings-state db) :assistants))]
    (materials-model/assistant-options
     {:assistants assistants
      :assistant-options (field materials-state :assistant-options)
      :session/by-id (table db :session/by-id)
      :selected-session-id (selected-session-id db)
      :session (field materials-state :session)})))

(defn role-cards
  "Role ownership rows for the assistants workspace.

  Includes `:main` and `:task` even before they are configured so an operator
  can see which model serves Steward/Plan/Reviewer and which serves the
  Plan worker and Swarm.  An unconfigured role has no selection label; the
  view must show that gap rather than invent a model."
  [db]
  (settings-model/role-cards (settings-state db)))

(defn connection-cards
  "Provider connection rows from the committed settings projection.

  Adds the per-connection model count without merging same-named models or
  connections."
  [db]
  (let [state (settings-state db)]
    (mapv (fn [card]
            (assoc card :model-count
                   (count (get-in state [:server :models
                                         (:connection-id card)]))))
          (settings-model/connection-cards state))))

(defn artifacts
  [db]
  (->> (vals (table db :artifact/by-id))
       (keep (fn [artifact]
               (when (id-of artifact)
                 {:id (id-of artifact)
                  :name (or (field artifact :name) (field artifact :filename) (id-of artifact))
                  :kind (or (field artifact :kind) (field artifact :mime))
                  :size (field artifact :size)
                  :status (field artifact :status)
                  :task-id (field artifact :task-id)})))
       (sort-by (comp str :name))
       vec))

(defn workspace-files
  [db]
  (let [files (or (field db :workspace/files)
                  (field db :ui/workspace-files))]
    (if (sequential? files) (vec files) [])))

;; ---------------------------------------------------------------------------
;; Command and stream views

(def command-labels
  {:created "已创建"
   :submitting "正在提交"
   :sent "已发送，等待服务器回执"
   :accepted "服务器已接受"
   :applied "服务器已应用"
   :rejected "服务器已拒绝"
   :unconfirmed "回执未确认"
   :failed "发送失败"})

(defn command-view
  [command]
  (when (map? command)
    {:id (or (field command :command-id) (field command :id))
     :kind (field command :kind)
     :status (or (field command :status) :created)
     :status-label (get command-labels (or (field command :status) :created) "状态未知")
     :message (field command :message)
     :payload (field command :payload)
     :created-at (field command :created-at)
     :epoch (field command :command-epoch)}))

(defn session-command
  "Return the newest tracked command for a session, or nil.

  Accepted commands stay visible to the shell status line; use
  `in-flight-session-command` for submission gates."
  [db session-id]
  (->> (vals (or (field db :ui/command) {}))
       (filter #(= session-id (or (field % :session-id)
                                  (some-> (field % :payload) (field :session-id)))))
       (filter #(contains? #{:created :submitting :sent :accepted :unconfirmed}
                           (or (field % :status) :created)))
       (sort-by #(str (or (field % :created-at) "")))
       last
       command-view))

(defn in-flight-session-command
  "Return the newest non-terminal command of KINDS for SESSION-ID.

  `session-command` also exposes accepted commands for the shell's status
  line.  A send/ask gate must not treat an accepted stop or another command
  kind as still blocking its own submission."
  [db session-id kinds]
  (->> (vals (or (field db :ui/command) {}))
       (filter #(= session-id (or (field % :session-id)
                                  (some-> (field % :payload)
                                          (field :session-id)))))
       (filter #(contains? kinds (field % :kind)))
       (filter #(contains? #{:created :submitting :sent :unconfirmed}
                           (or (field % :status) :created)))
       (sort-by #(str (or (field % :created-at) "")))
       last))

(defn stream-view
  [stream]
  (when (map? stream)
    {:streaming? (boolean (or (field stream :streaming?) false))
     :stopping? (= :stopping (field stream :stop-state))
     :stopped? (= :stopped (field stream :stop-state))
     :interrupted? (= :interrupted (field stream :stop-state))
     :follow? (not (false? (field stream :follow?)))
     :show-jump? (boolean (field stream :show-jump?))
     :status-label (cond
                     (= :stopping (field stream :stop-state)) "正在停止"
                     (= :stopped (field stream :stop-state)) "已停止"
                     (= :interrupted (field stream :stop-state)) "已中断"
                     (field stream :streaming?) "正在生成"
                     :else "已完成")
     :late-tool-results (vec (or (field stream :late-tool-results) []))
     :late-text-count (or (field stream :late-text-count) 0)}))

(defn ask-view
  [ask]
  (when (map? ask)
    {:ask-id (or (field ask :ask-id) (field ask :id))
     :name (field ask :name)
     :question (or (field ask :question) "")
     :context (field ask :context)
     :options (vec (or (field ask :options) []))
     :multiple? (boolean (field ask :multiple?))
     :input? (not (false? (field ask :input?)))
     :status (or (field ask :status) :pending)
     :draft (or (field ask :draft) {:text "" :selected #{}})
     :answers (vec (or (field ask :answers) []))}))

;; ---------------------------------------------------------------------------
;; Availability

(defn availability
  "Return the honest state of the workbench's actionable controls.

  Every entry is `{:enabled? bool :reason keyword-or-nil}`.  The view must
  render the reason for a disabled control and must not offer a click target
  that has no effect.  `:stop` is offered for every selected session because
  only the server Owner can tell whether unattended Nudge work is live."
  [{:keys [connection session-id session messages model streaming? stopping?
           draft-sendable? command-pending? ask]}]
  (let [synced? (true? (:command-allowed? connection))
        has-session? (non-blank-string session-id)
        has-model? (boolean model)
        has-ask? (and (map? ask) (= :pending (:status ask)))
        pending? (boolean command-pending?)]
    {:send (cond
             (not synced?) {:enabled? false :reason :connection-not-ready}
             (not has-session?) {:enabled? false :reason :no-session}
             (not has-model?) {:enabled? false :reason :no-model}
             (not draft-sendable?) {:enabled? false :reason :empty}
             pending? {:enabled? false :reason :command-pending}
             streaming? {:enabled? false :reason :generating}
             stopping? {:enabled? false :reason :stopping}
             :else {:enabled? true :reason nil})
     :stop (cond
             (not synced?) {:enabled? false :reason :connection-not-ready}
             (not has-session?) {:enabled? false :reason :no-session}
             stopping? {:enabled? false :reason :stopping}
             :else {:enabled? true :reason nil})
     :answer-ask (cond
                   (not synced?) {:enabled? false :reason :connection-not-ready}
                   (not has-session?) {:enabled? false :reason :no-session}
                   (not has-ask?) {:enabled? false :reason :no-pending-ask}
                   pending? {:enabled? false :reason :command-pending}
                   :else {:enabled? true :reason nil})
     :resync (if (:can-resync? connection)
               {:enabled? true :reason nil}
               {:enabled? false :reason :channel-not-open})
     :reconnect (if (:can-retry? connection)
                  {:enabled? true :reason nil}
                  {:enabled? false :reason :already-connected})}))

(def availability-labels
  {:connection-not-ready "尚未与服务器同步，命令不可提交"
   :command-epoch-unknown "服务器尚未提供该会话的命令 epoch；请等待同步或切换会话"
   :no-session "尚未选择会话"
   :no-model "尚未选择可用模型"
   :empty "输入为空"
   :command-pending "上一条命令仍在等待服务器回执"
   :generating "当前轮仍在生成；可先停止或继续编辑"
   :stopping "正在停止当前轮"
   :no-pending-ask "当前没有待回答的 S1 提问"
   :channel-not-open "实时通道未打开"
   :already-connected "通道已打开"})

(defn availability-reason
  [control]
  (get availability-labels (:reason control) "当前不可用"))

(defn send-guard
  "Return the materials reason that must block a send, or nil.

  The materials page already shows these warnings, but the composer can send
  without visiting it.  The check is pure so the conversation send path and
  its tests use exactly the same rule.  Search-selection errors stay the
  caller's first priority."
  [materials-state]
  (let [conflict (materials-model/selection-format-conflict materials-state)
        missing (materials-model/missing-required-placeholders materials-state)]
    (cond
      (seq conflict)
      {:reason :instruction-format-conflict
       :formats (vec conflict)
       :message (str "指令卡格式冲突："
                     (str/join " / " (map name conflict))
                     "。请只保留一种输出格式。")}

      (seq missing)
      {:reason :missing-required-placeholders
       :missing (vec missing)
       :message (str "必填占位符尚未提供："
                     (str/join "；"
                               (map (fn [{:keys [card-name key]}]
                                      (str card-name " / " key))
                                    missing))
                     "。")}

      :else nil)))

;; ---------------------------------------------------------------------------
;; Draft and storage labels

(defn draft-view
  [draft]
  (when (map? draft)
    {:text (or (:text draft) "")
     :version (:version draft)
     :saved-version (:saved-version draft)
     :composing? (boolean (:composing? draft))
     :attachments (vec (or (:attachments draft) []))
     :quotes (vec (or (:quotes draft) []))
     :save-state (or (:save-state draft) :saved)
     :save-label (case (:save-state draft)
                   :failed "草稿暂未保存"
                   :storage-unavailable "草稿暂未保存"
                   :saving "正在保存草稿"
                   :unsaved (when (pos? (or (:version draft) 0)) "草稿未保存")
                   nil)}))

(defn insert-transcript-change
  "Build the draft edit that inserts TEXT at INSERT-AT, or nil.

  A missing/blank session id must never create an `[:ui/draft nil]` entry:
  speech transcription still belongs to the session that started it, and a
  stray key would leave the visible draft untouched while clearing the
  recorder state.  Blank text is equally a no-op."
  [draft session-id text insert-at at]
  (when (and (string? session-id)
             (not (str/blank? session-id))
             (string? text)
             (not (str/blank? text)))
    (let [old (or (:text draft) "")
          pos (max 0 (min (count old) (long (or insert-at (count old)))))]
      {:session-id session-id
       :text (str (subs old 0 pos) text (subs old pos))
       :cursor (+ pos (count text))
       :at at})))

(defn empty-state
  [route]
  (case route
    :sessions "服务器尚未提供任何会话。连接后可在此查看当前消息与草稿。"
    :board "服务器尚未提供 swarm 或任务。看板只显示服务器当前态，不生成占位任务。"
    :assistants "服务器尚未提供助手、角色或连接配置。"
    :materials "服务器尚未提供材料、工作区文件或扩展配置。"
    :settings "连接建立后可查看同步序号、外观设置与诊断信息。"
    "等待服务器状态。"))
