(ns hc.client.settings.model
  "Settings state, draft lifecycle, model selection, and receipt application.

  This namespace is pure.  It returns `{:state state' :effects effects}`.
  An effect is data for the caller: submit one command, persist drafts, copy
  an explicitly revealed secret, or request a run stop.  No effect reports a
  save or test as successful."
  (:require [clojure.string :as str]
            [hc.client.copy.failures :as failures]
            [hc.client.settings.catalog :as catalog]
            [hc.client.settings.commands :as commands]
            [hc.client.appearance.model :as appearance-model]
            [hc.client.model-auth :as model-auth]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.draft :as draft]
            [hc.client.settings.util :as util]))

(declare role-binding role-row model-groups)

(def role-order
  [:main :task :utility :embedding :vision])

(def role-labels
  {:main "主模型"
   :task "任务模型"
   :utility "辅助模型"
   :embedding "嵌入模型"
   :vision "视觉模型"})

(def role-descriptions
  {:main "普通回答、Steward、Plan 与 Reviewer 的主要任务。"
   :task "Plan dedicated worker、Swarm 普通个体与持令者使用的任务模型；未选择时蜂群无法启动。"
   :utility "摘要、会话标题等轻任务；可由主模型兼任，但需要确认。"
   :embedding "检索向量。未就绪时明确显示检索不可用，不影响纯文本聊天。"
   :vision "图片理解。默认使用主模型已确认的视觉能力；单独指定时显示数据去向。"})

(def test-layer-order
  [:reachable :auth-ok :models-listed :minimal-request-ok])

(def test-layer-labels
  {:reachable "地址可达"
   :auth-ok "身份可用"
   :models-listed "模型可列出"
   :minimal-request-ok "最小请求完成"})

(def test-request-text
  "The fixed non-streaming test request.  The UI shows this before the
  operator clicks a test button so a possible charge is never a surprise."
  "Herdchestra connection test. Reply with OK.")

(defn- default-role
  [role]
  {:role role
   :mode (if (contains? #{:utility :vision} role) :follow-main :unset)
   :connection-id nil
   :model-id nil
   :confirmed? false})

(defn- normalize-role
  [role binding]
  (merge (default-role role)
         (when (map? binding) binding)
         {:role role}))

(defn- normalize-roles
  [roles]
  (let [roles (if (and (map? roles) (map? (:models roles)))
                (:models roles)
                roles)]
    (reduce (fn [m role]
              (assoc m role (normalize-role role (get roles role))))
            {}
            role-order)))

(defn- normalize-connections
  [connections]
  (cond
    (map? connections)
    (into {} (map (fn [[id conn]]
                    [(str (or (:connection-id conn) (:id conn) id))
                     (connection/normalize-connection
                      (assoc conn :connection-id
                             (or (:connection-id conn) (:id conn) id)))]))
          connections)

    (sequential? connections)
    (into {} (map (fn [conn]
                    (let [conn (connection/normalize-connection conn)]
                      [(:connection-id conn) conn])))
          connections)

    :else {}))

(defn- normalize-models
  [models]
  (if (map? models)
    (into {} (map (fn [[connection-id model-list]]
                    [(str connection-id)
                     (if (sequential? model-list)
                       (vec model-list)
                       (when (some? model-list) [model-list]))]))
          models)
    {}))

(defn- normalize-statuses
  [statuses]
  (if (map? statuses)
    (into {} (map (fn [[connection-id status]]
                    [(str connection-id) (util/normalize-keyword status)]))
          statuses)
    {}))

(defn- normalize-assistant
  "Normalize one assistant record to the settings view shape.

  The server publishes `{id name revision}` plus any persisted fields such
  as `:assistant-id`, `:display-name`, `:enabled?`, and `:default-model`."
  [id assistant]
  (when (map? assistant)
    (let [id (str (or (:id assistant) (:assistant-id assistant) id))]
      {:id id
       :assistant-id (str (or (:assistant-id assistant) id))
       :name (str (or (:name assistant) (:display-name assistant) id))
       :display-name (str (or (:display-name assistant)
                              (:name assistant)
                              id))
       :revision (long (or (:revision assistant) 1))
       :enabled? (not (false? (:enabled? assistant)))
       ;; `:model-entry-id` is the sessions-layer alias of `:model-id`; keep
       ;; one canonical client key so editors and validation agree.
       :default-model (when (map? (:default-model assistant))
                        (let [model (select-keys (:default-model assistant)
                                                [:connection-id :model-id
                                                 :model-entry-id])]
                          (cond-> model
                            (and (nil? (:model-id model))
                                 (some? (:model-entry-id model)))
                            (assoc :model-id (:model-entry-id model)))))})))

(defn- normalize-assistants
  [assistants]
  (cond
    (map? assistants)
    (into {} (keep (fn [[id assistant]]
                     (when-let [a (normalize-assistant id assistant)]
                       [(:id a) a])))
          assistants)

    (sequential? assistants)
    (into {} (keep (fn [assistant]
                     (when-let [a (normalize-assistant nil assistant)]
                       [(:id a) a])))
          assistants)

    :else {}))

(defn initial-state
  "Return a disconnected settings state.  Drafts are empty."
  ([] (initial-state {}))
  ([{:keys [connections models model-list-status roles sync
            session-model-snapshots transport-available?]
     :or {transport-available? false}}]
   {:settings/version 1
    :appearance appearance-model/default
    :server {:connections (normalize-connections connections)
             :models (normalize-models models)
             :model-list-status (normalize-statuses model-list-status)
             :roles (normalize-roles roles)
            :assistant-order []
            :assistant-refs {}
             :session-model-snapshots (or session-model-snapshots {})}
    :drafts {}
    :role-drafts {}
    :assistants {}
    :ui {:open? false
         :section :providers
         :selected-draft-id nil
         :selected-role :main
         :selection nil
         :revealed #{}
         :manual-model-inputs {}
         :draft-history []
         :confirm-delete-draft-id nil
         :assistant-drafts {}
         :confirm-delete-assistant-id nil
         :selected-assistant-id nil
         :price-preview-inputs {}
         :current-session-id nil
         :active-session-id nil
         :session-running? false
         :draft-storage-status :saved
         :model-auth model-auth/initial-state
         :onboarding {:step :connection-type
                      :dismissed? false
                      :completed? false}
         :transport-available? (boolean transport-available?)
         :message nil}
    :sync (merge {:phase :disconnected :command-epoch nil}
                 (when (map? sync) sync))
    :commands {}
    :outbox []
    :tests {}
    :orphan-receipts []}))

(defn- result
  ([state] {:state state :effects []})
  ([state effects] {:state state :effects effects}))

(defn- persist-effect
  [state]
  {:effect :persist-drafts
   :drafts (:drafts state)})

(defn- with-draft
  [state draft-id f]
  (if-let [draft (or (get-in state [:drafts draft-id])
                     (some #(when (= draft-id (:connection-id %)) %)
                           (vals (:drafts state))))]
    (let [draft' (f draft)
          state' (assoc-in state [:drafts (:draft-id draft')] draft')]
      (result state' [(persist-effect state')]))
    (result state)))

(defn- message
  [state kind text]
  (assoc-in state [:ui :message] {:kind kind :text text}))

(defn- selected-draft-id
  [state]
  (get-in state [:ui :selected-draft-id]))

(defn- find-draft
  [state draft-id]
  (when draft-id
    (or (get-in state [:drafts draft-id])
        (some #(when (= draft-id (:connection-id %)) %)
              (vals (:drafts state))))))

(defn manual-model-input
  "Return the manual model input for DRAFT-ID.

  The input is UI-only text, so it lives next to the draft rather than in
  the draft payload.  It is still keyed by draft: switching connections must
  not carry a half-typed model id into another connection."
  [state draft-id]
  (get-in state [:ui :manual-model-inputs
                 (or draft-id (selected-draft-id state))]
          ""))

(defn previous-draft
  "Return the draft selected before the most recent new-connection action."
  [state]
  (let [current (selected-draft-id state)]
    (some (fn [draft-id]
            (when (not= draft-id current)
              (find-draft state draft-id)))
          (reverse (get-in state [:ui :draft-history] [])))))

(defn discardable-new-draft?
  "True when DRAFT can be removed by back without losing operator input.

  The draft must still be an untouched new draft, its UI-only manual model
  input must be empty, and no account-login flow may be active for it.  A
  login flow is a visible action even before the connection patch touches
  the draft."
  [state draft]
  (let [draft-id (:draft-id draft)
        auth (get-in state [:ui :model-auth])
        auth-busy? (and (= (str (:connection-id auth)) (str draft-id))
                        (contains? #{:starting :pending :cancelling
                                     :logging-out :authorized}
                                   (:status auth)))]
    (and draft
         (= :new (:mode draft))
         (draft/untouched-new? draft)
         (contains? #{:new :dirty} (:status draft))
         (empty? (:manual-models draft))
         (empty? (:enabled-models draft))
         (str/blank? (str (manual-model-input state draft-id)))
         (not auth-busy?))))

(defn- drop-draft
  "Remove DRAFT-ID and every piece of local UI state keyed by it."
  [state draft-id]
  (-> state
      (update :drafts dissoc draft-id)
      (update :tests dissoc draft-id)
      (update :ui update :manual-model-inputs
              (fn [inputs] (dissoc (or inputs {}) draft-id)))
      (update :ui update :draft-history
              (fn [history]
                (vec (remove #{draft-id} (or history [])))))
      (update :ui update :confirm-delete-draft-id
              (fn [confirm-id]
                (when-not (= confirm-id draft-id) confirm-id)))
      (update :ui update :revealed
              (fn [revealed]
                (into #{}
                      (remove (fn [[id _]] (= id draft-id)))
                      (or revealed #{}))))
      (cond-> (= draft-id (selected-draft-id state))
        (assoc-in [:ui :selected-draft-id] nil))))

(defn- rename-draft-ui-state
  "Move UI-only state from OLD-ID to NEW-ID.

  A clean draft is rebuilt from a fresh server connection and may get a new
  draft id.  The half-typed manual model input, reveal set, test record, and
  return history still belong to the same connection, so they must follow
  the draft rather than become unreachable."
  [state old-id new-id]
  (if (= old-id new-id)
    state
    (-> state
        (update :ui update :manual-model-inputs
                (fn [inputs]
                  (if (contains? inputs old-id)
                    (-> inputs
                        (dissoc old-id)
                        (assoc new-id (get inputs old-id)))
                    inputs)))
        (update :ui update :draft-history
                (fn [history]
                  (mapv #(if (= % old-id) new-id %) history)))
        (update :ui update :confirm-delete-draft-id
                (fn [draft-id]
                  (if (= draft-id old-id) new-id draft-id)))
        (update :ui update :revealed
                (fn [revealed]
                  (into #{}
                        (map (fn [[draft-id key-id]]
                               [(if (= draft-id old-id) new-id draft-id)
                                key-id]))
                        revealed)))
        (update :tests
                (fn [tests]
                  (if (contains? tests old-id)
                    (-> tests
                        (dissoc old-id)
                        (assoc new-id (get tests old-id)))
                    tests))))))

(defn- ensure-draft
  [state connection-id]
  (let [existing (some (fn [[draft-id draft]]
                         (when (= connection-id (:connection-id draft))
                           draft-id))
                       (:drafts state))
        connection (get-in state [:server :connections connection-id])]
    (cond
      existing (assoc-in state [:ui :selected-draft-id] existing)
      connection
      (let [draft (draft/draft-from-connection
                   connection
                   (get-in state [:server :models connection-id]))]
        (-> state
            (assoc-in [:drafts (:draft-id draft)] draft)
            (assoc-in [:ui :selected-draft-id] (:draft-id draft))))
      :else state)))

(defn- current-draft
  [state action]
  (or (find-draft state (:draft-id action))
      (find-draft state (:connection-id action))
      (find-draft state (selected-draft-id state))))

(defn- refresh-clean-drafts
  [state connections]
  (reduce-kv
   (fn [state draft-id draft]
     (let [connection-id (:connection-id draft)
           connection (get connections connection-id)]
       (cond
         (and connection-id (nil? connection))
         (assoc-in state [:drafts draft-id] (draft/mark-orphaned draft))

         (and connection (not (draft/dirty? draft)))
         (let [refreshed (draft/draft-from-connection
                          connection
                          (get-in state [:server :models connection-id]))
               new-id (:draft-id refreshed)
               selected? (= draft-id (selected-draft-id state))
               state' (-> state
                          (update :drafts dissoc draft-id)
                          (assoc-in [:drafts new-id] refreshed)
                          (rename-draft-ui-state draft-id new-id))]
           (if selected?
             (assoc-in state' [:ui :selected-draft-id] new-id)
             state'))

         :else state)))
   state
   (:drafts state)))

(defn- merge-role-drafts
  [state roles]
  (reduce-kv
   (fn [state role binding]
     (let [role-draft (get-in state [:role-drafts role])]
       (if (and role-draft (:dirty? role-draft))
         state
         (update state :role-drafts dissoc role))))
   state
   roles))

(defn apply-snapshot
  "Apply one server baseline snapshot.

  Connections are replaced, not shallow-merged, so removed connections do
  not resurrect.  Drafts are never discarded: a draft whose connection is
  gone becomes orphaned and remains editable.  No test is started.

  `:server-revision` identifies the confirmed Fulcro server copy.  A
  projection older than the one already applied is ignored, so a delayed
  baseline cannot restore removed connections, models, or roles."
  [state snapshot]
  (let [snapshot (or snapshot {})
        revision (:server-revision snapshot)
        current-revision (get-in state [:sync :server-revision])
        stale-revision? (and (integer? revision)
                             (integer? current-revision)
                             (< revision current-revision))]
    (if stale-revision?
      (result state)
      (let [connections (cond
                          (contains? snapshot :settings/by-id)
                          (normalize-connections (:settings/by-id snapshot))

                          (contains? snapshot :server)
                          (normalize-connections
                           (get-in snapshot [:server :connections]))

                          :else nil)
            has-models? (contains? snapshot :settings/models)
            models (when has-models? (normalize-models (:settings/models snapshot)))
            has-statuses? (contains? snapshot :settings/model-list-status)
            statuses (when has-statuses?
                       (normalize-statuses (:settings/model-list-status snapshot)))
            has-roles? (or (contains? snapshot :settings/roles)
                           (contains? snapshot :models))
            roles (when has-roles?
                    (normalize-roles (or (:settings/roles snapshot)
                                         (:models snapshot))))
            has-assistants? (contains? snapshot :settings/assistants)
            assistants (when has-assistants?
                         (normalize-assistants (:settings/assistants snapshot)))
            sync (cond-> (cond
                           (map? (:sync snapshot)) (:sync snapshot)
                           (or (contains? snapshot :phase)
                               (contains? snapshot :command-epoch)
                               (contains? snapshot :epoch))
                           (select-keys snapshot [:phase :command-epoch :epoch])
                           :else nil)
                   (some? revision) (assoc :server-revision revision))
            sessions (:session/by-id snapshot)
            session-snapshots (when sessions
                                (into {}
                                      (keep (fn [[session-id session]]
                                              (when-let [snapshot (:model-snapshot session)]
                                                [(str session-id) snapshot])))
                                      sessions))
            session-epochs (merge
                            (or (when sessions
                                  (into {}
                                        (keep (fn [[session-id session]]
                                                (let [epoch (or (:command-epoch session)
                                                                (:session/command-epoch session))]
                                                  (when (pos-int? epoch)
                                                    [(str session-id) epoch]))))
                                        sessions))
                                {})
                            (or (:session/command-epochs snapshot) {}))
            state (cond-> state
                    (some? connections)
                    (assoc-in [:server :connections] connections)

                    (some? models)
                    (assoc-in [:server :models] models)

                    (some? statuses)
                    (assoc-in [:server :model-list-status] statuses)

                    (some? roles)
                    (-> (assoc-in [:server :roles] roles)
                        (merge-role-drafts roles))

                    (some? assistants)
                    (assoc-in [:server :assistants] assistants)

                    (contains? snapshot :settings/assistant-order)
                    (assoc-in [:server :assistant-order]
                             (vec (:settings/assistant-order snapshot)))

                    (contains? snapshot :assistant-refs)
                    (assoc-in [:server :assistant-refs]
                             (:assistant-refs snapshot))

                    (some? sync)
                    (update :sync merge
                            (select-keys sync [:phase :command-epoch :epoch
                                               :server-revision]))

                    (some? session-snapshots)
                    (assoc-in [:server :session-model-snapshots] session-snapshots)

                    (seq session-epochs)
                    (assoc-in [:server :session-command-epochs] session-epochs)

                    (contains? snapshot :session/selected-id)
                    (assoc-in [:ui :current-session-id]
                              (some-> (:session/selected-id snapshot) str))

                    (some? (:session/active-id snapshot))
                    (assoc-in [:ui :active-session-id]
                              (str (:session/active-id snapshot)))

                    (contains? snapshot :session/running?)
                    (assoc-in [:ui :session-running?]
                              (boolean (:session/running? snapshot)))

                    (and (not (contains? snapshot :session/selected-id))
                         (some? (:session/active-id snapshot)))
                    (assoc-in [:ui :current-session-id]
                              (str (:session/active-id snapshot)))

                    true
                    (update-in [:ui :model-auth]
                               #(model-auth/apply-snapshot
                                 (or % model-auth/initial-state)
                                 snapshot)))]
        (let [state (if connections
                      (refresh-clean-drafts state connections)
                      state)
              state (if (and (nil? (selected-draft-id state))
                             (seq (:drafts state)))
                      (assoc-in state [:ui :selected-draft-id]
                                (first (keys (:drafts state))))
                      state)]
          (result state [(persist-effect state)]))))))

(defn- select-first
  [state]
  (cond
    (selected-draft-id state) state
    (seq (:drafts state))
    (assoc-in state [:ui :selected-draft-id] (first (keys (:drafts state))))
    (seq (get-in state [:server :connections]))
    (ensure-draft state (first (sort (keys (get-in state [:server :connections])))))
    :else state))

(defn- auth-account-for
  [auth draft]
  (let [ids (->> [(:draft-id draft)
                  (:connection-id draft)
                  (get-in draft [:fields :auth-ref])
                  (get-in draft [:saved-connection :auth-ref])]
                 (keep #(when (some? %) (str %)))
                 set)]
    (some (fn [account]
            (when (contains? ids (str (:connection-id account)))
              account))
          (:accounts auth))))

(defn- sync-auth-connection
  [state]
  (if-let [draft (find-draft state (selected-draft-id state))]
    (let [draft-id (:draft-id draft)
          auth (or (get-in state [:ui :model-auth]) model-auth/initial-state)
          switched? (not= (str (:connection-id auth)) (str draft-id))
          ;; Login state belongs to one draft.  Selecting another connection
          ;; must not show the previous account or let its logout button
          ;; target the wrong connection.
          auth (if switched?
                 (assoc auth
                        :connection-id draft-id
                        :flow nil
                        :status :idle
                        :account nil
                        :error nil
                        :last-error nil
                        :copied? false
                        :reveal-account? false)
                 auth)
          account (auth-account-for auth draft)
          auth (if (and account
                        (contains? #{:idle :connected :authorized}
                                   (:status auth)))
                 (assoc auth :account account :status :connected :error nil)
                 auth)]
      (assoc-in state [:ui :model-auth] auth))
    state))

(defn- handle-open
  [state]
  (let [state' (-> state
                   (assoc-in [:ui :open?] true)
                   (select-first)
                   (sync-auth-connection))]
    (result state' [(persist-effect state')])))

(defn- handle-close
  [state]
  (result (assoc-in state [:ui :open?] false)))

(defn- handle-select-section
  [state action]
  (result (assoc-in state [:ui :section] (:section action))))

(defn- handle-new-connection
  [state action]
  (let [previous-id (selected-draft-id state)
        previous (find-draft state previous-id)
        opts (select-keys action [:draft-id :key-id :auth-edit-id :now])
        ;; A caller-supplied id must never overwrite an existing draft or
        ;; alias a saved connection.  The E2E path supplies no id and gets a
        ;; fresh one, but this guard keeps a deterministic test or a stale
        ;; action from silently reusing an unsaved draft.
        opts (if (and (:draft-id opts)
                      (or (find-draft state (:draft-id opts))
                          (contains? (get-in state [:server :connections])
                                     (:draft-id opts))))
               (dissoc opts :draft-id)
               opts)
        draft (draft/blank-draft (:path action) opts)
        history (vec (get-in state [:ui :draft-history] []))
        history (if (and previous
                         (not= previous-id (:draft-id draft)))
                  (conj (vec (remove #{previous-id} history)) previous-id)
                  history)
        history (vec (take-last 20 history))
        state (-> state
                  (assoc-in [:drafts (:draft-id draft)] draft)
                  (assoc-in [:ui :selected-draft-id] (:draft-id draft))
                  (assoc-in [:ui :draft-history] history)
                  (assoc-in [:ui :manual-model-inputs (:draft-id draft)] "")
                  (assoc-in [:ui :section] :providers)
                  (assoc-in [:ui :confirm-delete-draft-id] nil)
                  (sync-auth-connection)
                  (message :info
                           (if previous
                             (str "已新建" (draft/path-label draft)
                                  "草稿；原草稿「" (draft/display-name previous)
                                  "」仍保留，"
                                  (if (or (= :new (:mode previous))
                                          (:orphaned? previous))
                                    "可返回或从“未保存草稿”继续。"
                                    "可返回或从连接列表继续。"))
                             (str "已新建" (draft/path-label draft)
                                  "草稿；保存成功前不会出现在连接列表。"))))]
    (result state [(persist-effect state)])))

(defn- handle-select-connection
  [state action]
  (let [connection-id (:connection-id action)
        state' (ensure-draft state connection-id)]
    (result (-> state'
                (assoc-in [:ui :selected-draft-id]
                          (or (selected-draft-id state') connection-id))
                (assoc-in [:ui :section] :providers)
                (assoc-in [:ui :draft-history] [])
                (assoc-in [:ui :confirm-delete-draft-id] nil)
                (sync-auth-connection))
            [(persist-effect state')])))

(defn- handle-select-draft
  [state action]
  (if-let [draft (find-draft state (:draft-id action))]
    (if (= (:draft-id draft) (selected-draft-id state))
      (result state)
      (let [state' (-> state
                       (assoc-in [:ui :selected-draft-id] (:draft-id draft))
                       (assoc-in [:ui :section] :providers)
                       (assoc-in [:ui :draft-history] [])
                       (assoc-in [:ui :confirm-delete-draft-id] nil)
                       (sync-auth-connection)
                       (message :info
                                (str "已打开草稿「" (draft/display-name draft)
                                     "」；未保存输入仍在。")))]
        (result state' [(persist-effect state')])))
    (result (message state :warning "该草稿已不存在；其他草稿未改变。"))))

(defn- handle-back-draft
  [state]
  (let [history (vec (get-in state [:ui :draft-history] []))
        [target remaining] (loop [candidates history]
                             (if-let [draft-id (peek candidates)]
                               (if (and (find-draft state draft-id)
                                        (not= draft-id
                                              (selected-draft-id state)))
                                 [draft-id (pop candidates)]
                                 (recur (pop candidates)))
                               [nil []]))
        current (find-draft state (selected-draft-id state))
        discard? (discardable-new-draft? state current)]
    (if-not target
      (if discard?
        (let [state' (-> (drop-draft state (:draft-id current))
                         (assoc-in [:ui :draft-history] [])
                         (select-first)
                         (message :info "已撤销新建；没有其他可返回的草稿。"))]
          (result state' [(persist-effect state')]))
        (result (-> state
                    (assoc-in [:ui :draft-history] [])
                    (message :info "没有可返回的上一条草稿。"))))
      (let [state' (cond-> state
                     discard? (drop-draft (:draft-id current))
                     true (assoc-in [:ui :selected-draft-id] target)
                     true (assoc-in [:ui :draft-history] remaining)
                     true (assoc-in [:ui :section] :providers)
                     true (assoc-in [:ui :confirm-delete-draft-id] nil)
                     true (sync-auth-connection)
                     true (message :info
                                   (if discard?
                                     "已撤销新建；原草稿已恢复。"
                                     "已返回上一条草稿；新建草稿仍保留在“未保存草稿”中。")))]
        (result state' [(persist-effect state')])))))

(defn- handle-edit-field
  [state action]
  (with-draft state (:draft-id action)
              #(draft/edit-field % (:field action) (:value action)
                                 (select-keys action [:now]))))

(defn- handle-set-path
  [state action]
  (with-draft state (:draft-id action)
              #(draft/set-path % (or (:path action) (:value action))
                               (select-keys action [:now]))))

(defn- handle-apply-auth-connection
  [state action]
  (with-draft state (:draft-id action)
              #(draft/apply-connection-patch % (:patch action)
                                             (select-keys action [:now]))))

(defn- handle-set-location
  [state action]
  (with-draft state (:draft-id action)
              #(draft/set-location % (or (:location action) (:value action))
                                   (select-keys action [:now]))))

(defn- handle-set-key-strategy
  [state action]
  (with-draft state (:draft-id action)
              #(draft/set-key-strategy % (or (:strategy action) (:value action))
                                       (select-keys action [:now]))))

(defn- handle-edit-key-field
  [state action]
  (with-draft state (:draft-id action)
              #(draft/edit-key-field % (:key-id action) (:field action)
                                     (:value action) (select-keys action [:now]))))

(defn- handle-toggle-key
  [state action]
  (with-draft state (:draft-id action)
              #(draft/toggle-key-enabled % (:key-id action) (select-keys action [:now]))))

(defn- handle-add-key
  [state action]
  (with-draft state (:draft-id action)
              #(draft/add-key % (select-keys action [:key-id :auth-edit-id :alias :now]))))

(defn- handle-remove-key
  [state action]
  (with-draft state (:draft-id action)
              #(draft/remove-key % (:key-id action) (select-keys action [:now]))))

(defn- handle-edit-secret
  [state action]
  (with-draft state (:draft-id action)
              #(draft/edit-key-secret % (:key-id action) (:value action)
                                      (select-keys action [:auth-edit-id :now]))))

(defn- handle-clear-secret
  [state action]
  (with-draft state (:draft-id action)
              #(draft/clear-key-secret % (:key-id action) (select-keys action [:now]))))

(defn- handle-toggle-secret
  [state action]
  (let [draft-id (or (:draft-id action) (selected-draft-id state))
        key-id (:key-id action)
        revealed (get-in state [:ui :revealed] #{})
        revealed (if (contains? revealed [draft-id key-id])
                   (disj revealed [draft-id key-id])
                   (conj revealed [draft-id key-id]))]
    (result (assoc-in state [:ui :revealed] revealed))))

(defn- handle-copy-secret
  [state action]
  (let [draft (current-draft state action)
        key (some #(when (= (:key-id action) (:key-id %)) %)
                  (:keys draft))]
    (if (and key (= :set (:auth-state key)) (util/present? (:secret key)))
      (result state [{:effect :copy-secret
                      :secret (:secret key)
                      :label (str "密钥 " (:alias key))}])
      (result (message state :warning
                       "已保存密钥不会回显；请输入新值后再复制，或显式替换。")))))

(defn- handle-manual-model-input
  [state action]
  (let [draft-id (or (:draft-id action) (selected-draft-id state))]
    (if (find-draft state draft-id)
      (result (assoc-in state [:ui :manual-model-inputs draft-id]
                        (str (:value action))))
      (result state))))

(defn- handle-add-manual-model
  [state action]
  (let [draft-id (or (:draft-id action) (selected-draft-id state))
        model-id (or (:model-id action)
                     (manual-model-input state draft-id))]
    (if (str/blank? (str model-id))
      (result (message state :warning "请先填写模型标识。"))
      (let [state' (with-draft state draft-id
                               #(draft/add-manual-model % model-id
                                                        (select-keys action [:now])))]
        (result (-> (:state state')
                    (assoc-in [:ui :manual-model-inputs draft-id] ""))
                (:effects state'))))))

(defn- handle-remove-manual-model
  [state action]
  (with-draft state (:draft-id action)
              #(draft/remove-manual-model % (:model-id action)
                                          (select-keys action [:now]))))

(defn- handle-toggle-enabled-model
  [state action]
  (with-draft state (:draft-id action)
              #(draft/toggle-enabled-model % (:model-id action)
                                           (select-keys action [:now]))))

(defn- handle-edit-model-field
  [state action]
  (let [draft (current-draft state action)
        model-id (str (:model-id action))
        field (util/normalize-keyword (:field action))
        value (:value action)
        new-id (str/trim (str value))
        duplicate? (some (fn [model]
                           (and (not= model-id
                                      (str (or (:model-id model)
                                               (:id model)
                                               (:name model))))
                                (= new-id
                                   (str (or (:model-id model)
                                            (:id model)
                                            (:name model))))))
                         (:models draft))]
    (cond
      (nil? draft)
      (result (message state :error "没有可编辑的连接草稿。"))

      (and (= field :model-id) (str/blank? new-id))
      (result (message state :error "模型标识不能为空。"))

      (and (= field :model-id) duplicate?)
      (result (message state :error
                       "同一连接内已有相同模型标识；请使用不同标识或先合并。"))

      :else
      (with-draft state (:draft-id draft)
                  #(draft/edit-model-field % model-id field value
                                           (select-keys action [:now]))))))

(defn- handle-set-model-capability
  [state action]
  (with-draft state (:draft-id action)
              #(draft/set-model-capability % (:model-id action)
                                           (:capability action) (:value action)
                                           (select-keys action [:now]))))

(defn- handle-restore-model-capabilities
  [state action]
  (with-draft state (:draft-id action)
              #(draft/restore-model-capabilities % (:model-id action)
                                                 (:capability action)
                                                 (select-keys action [:now]))))

(defn- handle-edit-model-capabilities
  [state action]
  (let [state' (ensure-draft state (:connection-id action))]
    (result state' [(persist-effect state')])))

(defn- handle-select-role
  [state action]
  (result (assoc-in state [:ui :selected-role] (:role action))))

(defn- handle-set-role-mode
  [state action]
  (let [role (:role action)
        mode (util/normalize-keyword (:mode action))
        binding (merge (role-binding state role)
                       {:role role
                        :mode mode
                        :confirmed? (if (= :follow-main mode)
                                      (= :vision role)
                                      true)
                        :dirty? true
                        :version (inc (or (:version (role-binding state role)) 0))})
        state' (-> state
                   (assoc-in [:role-drafts role] binding)
                   (message :info
                            (if (= :follow-main mode)
                              "已设为跟随主模型；辅助模型需要确认后才能用于摘要与标题。"
                              "已设为单独指定；保存角色绑定后生效。")))]
    (result state' [(persist-effect state')])))

(defn- handle-confirm-role
  [state action]
  (let [role (:role action)
        binding (merge (role-binding state role)
                       {:role role
                        :confirmed? true
                        :dirty? true
                        :version (inc (or (:version (role-binding state role)) 0))})
        state' (-> state
                   (assoc-in [:role-drafts role] binding)
                   (message :info "已确认跟随主模型；保存角色绑定后生效。"))]
    (result state' [(persist-effect state')])))

(defn- handle-set-current-session
  [state action]
  (result (assoc-in state [:ui :current-session-id]
                    (some-> (:session-id action) str))))

(defn- handle-select-session-model
  [state action]
  (let [session-id (or (:session-id action)
                       (get-in state [:ui :current-session-id]))
        connection-id (:connection-id action)
        model-id (:model-id action)
        mode (or (util/normalize-keyword (:mode action)) :next-round)]
    (if-not (and (util/present? (str session-id))
                 (util/present? (str connection-id))
                 (util/present? (str model-id)))
      (result (message state :error
                       "没有当前会话或模型选择；请先选择会话与模型。"))
      (let [session-epoch (get-in state [:server :session-command-epochs session-id])
            command-epoch (when (pos-int? session-epoch) session-epoch)
            queued (commands/queue state :session/select-model
                                   {:session-id session-id
                                    :connection-id connection-id
                                    :model-entry-id model-id
                                    :mode mode}
                                   (merge (select-keys action [:command-id :now])
                                          {:command-epoch command-epoch}))]
        (if (:ok? queued)
          (result (message (:state queued) :info
                           (if (= :stop-and-switch mode)
                             "已请求停止当前轮并切换；当前回复仍归属原模型。"
                             "已提交模型切换；生成中只用于下一轮。若刚保存角色/连接，服务器可能需要重启后解析新绑定。"))
                  [(persist-effect (:state queued))])
          (result (message (:state queued) :error
                           (get-in queued [:error :message]))))))))

(defn- apply-model-auth-effects
  [state effects]
  (reduce (fn [{:keys [state out]} effect]
            (if (= :submit (:effect effect))
              (let [queued (commands/queue state (:kind effect) (:payload effect))]
                (if (:ok? queued)
                  {:state (:state queued)
                   :out (conj out {:effect :persist-drafts
                                   :drafts (:drafts (:state queued))})}
                  {:state (message (:state queued) :error
                                   (get-in queued [:error :message]))
                   :out out}))
              {:state state
               :out (conj out
                          (if (= :copy-text (:effect effect))
                            {:effect :copy-secret
                             :secret (:text effect)
                             :label "设备码"}
                            effect))}))
          {:state state :out []}
          effects))

(defn- handle-model-auth
  [state action]
  (let [draft (current-draft state {})
        draft-id (:draft-id draft)
        receipt-connection-id (or (get-in action [:receipt :connection-id])
                                  (get-in action
                                          [:receipt :flow :connection-id]))
        stale-receipt? (and draft-id
                            receipt-connection-id
                            (not= (str draft-id)
                                  (str receipt-connection-id)))
        auth (or (get-in state [:ui :model-auth])
                 model-auth/initial-state)
        auth (if draft-id
               (assoc auth :connection-id draft-id)
               auth)]
    (if stale-receipt?
      ;; A late receipt for another connection must not overwrite the
      ;; selected draft's login panel or patch its auth metadata.
      (result state [(persist-effect state)])
      (let [reduced (model-auth/reduce auth action)
            auth' (:state reduced)
            state (assoc-in state [:ui :model-auth] auth')
            {:keys [state out]} (apply-model-auth-effects
                                 state (:effects reduced))
            patch (model-auth/connection-patch auth')
            draft (current-draft state {})
            state (if (and patch draft
                            (= :account-login
                               (get-in draft [:fields :service-kind])))
                    (let [draft' (draft/apply-connection-patch
                                  draft patch {})]
                      (assoc-in state [:drafts (:draft-id draft')] draft'))
                    state)]
        (result state (conj out (persist-effect state)))))))

(defn- handle-select-model
  [state action]
  (let [selection (catalog/selection (:connection-id action) (:model-id action))
        role (:role action)
        state (assoc-in state [:ui :selection] selection)
        state (if role
                (assoc-in state [:role-drafts role]
                          {:role role
                           :mode :explicit
                           :connection-id (:connection-id selection)
                           :model-id (:model-id selection)
                           :confirmed? true
                           :dirty? true
                           :version (inc (or (:version (role-binding state role)) 0))})
                state)]
    (result (message state :info
                     (if role
                       "已选择模型；保存角色绑定后才对后续会话生效。"
                       "已选择模型；生成中切换只用于下一轮，当前回复归属不变。")))))

(defn- handle-save-connection
  [state action]
  (let [draft (current-draft state action)]
    (if-not draft
      (result (message state :error "没有可保存的连接草稿。"))
      (let [errors (connection/draft-validation-errors draft)]
        (if (seq errors)
          (result (message state :error
                           (str "无法保存：" (str/join "；" (map :message errors)))))
          (let [payload (connection/draft-payload draft)
                queued (commands/queue state :settings/save-connection payload
                                       (select-keys action [:command-id :now]))]
            (if (:ok? queued)
              (let [state' (update-in (:state queued) [:drafts (:draft-id draft)]
                                      draft/mark-saving)
                    state' (message state' :info
                                    "已提交保存；等待服务器回执。草稿在确认前保留。")]
                (result state' [(persist-effect state')]))
              (result (message (:state queued) :error
                               (get-in queued [:error :message]))))))))))

(defn- role->server-config
  "Return one role binding in the server config shape."
  [binding]
  (cond-> {}
    (:connection-id binding) (assoc :connection (:connection-id binding))
    (:model-id binding) (assoc :model (:model-id binding))
    (:mode binding) (assoc :mode (:mode binding))
    (:confirmed? binding) (assoc :confirmed? true)))

(defn- server-roles
  "Return all role bindings; the server replaces the whole roles map.

  `:main` and `:task` are model roles for the server adapter, so a
  mode-only placeholder is not a valid binding.  Omit them until a concrete
  connection and model are selected; sending `{:mode :unset}` would make the
  whole role map invalid.  UI-only roles may keep their follow-main mode."
  [state]
  (into {}
        (keep (fn [role]
                (let [binding (role->server-config (role-binding state role))
                      model-role? (contains? #{:main :task} role)]
                  (when (or (not model-role?)
                            (and (:connection binding) (:model binding)))
                    [role binding]))))
        role-order))

(defn- handle-save-role
  [state action]
  (let [role (:role action)
        binding (or (get-in state [:role-drafts role])
                    (get-in state [:server :roles role]))]
    (if-not binding
      (result (message state :error "没有可保存的角色绑定。"))
      (let [queued (commands/queue state :settings/save-role
                                   {:role role
                                    :role-version (:version binding)
                                    :roles (server-roles state)
                                    :binding (select-keys binding
                                                          [:role :mode :connection-id
                                                           :model-id :confirmed?])}
                                   (select-keys action [:command-id :now]))]
        (if (:ok? queued)
          (result (message (:state queued) :info
                           "已提交角色绑定；等待服务器回执。"))
          (result (message (:state queued) :error
                           (get-in queued [:error :message]))))))))

(defn- test-mode-none?
  [state draft]
  (let [connection (get-in state [:server :connections (:connection-id draft)])]
    (= :none (:test-mode connection))))

(defn- handle-test-connection
  [state action]
  (let [state (if (and (:connection-id action)
                       (nil? (current-draft state action)))
                (ensure-draft state (:connection-id action))
                state)
        draft (current-draft state action)]
    (if-not draft
      (result (message state :error "没有可测试的连接草稿。"))
      (let [errors (connection/draft-validation-errors draft)]
        (if (seq errors)
          (result (message state :error
                           (str "无法测试：" (str/join "；" (map :message errors)))))
          (if (test-mode-none? state draft)
            (result (message state :warning
                             "该服务不提供无副作用测试；请保存连接并保持“未测试”。"))
            (let [payload (connection/draft-payload draft)
                  queued (commands/queue state :settings/test-connection payload
                                         (select-keys action [:command-id :now]))]
              (if (:ok? queued)
                (let [digest (:config-digest payload)
                      state' (-> (:state queued)
                                 (assoc-in [:tests (:draft-id draft)]
                                           {:status :pending
                                            :config-digest digest
                                            :draft-version (:version draft)
                                            :started-at (:now action)
                                            :layers {}
                                            :stale-results []})
                                 (message :info
                                          (str "已提交测试：将发送一次固定测试请求，"
                                               "可能产生少量费用。固定内容："
                                               test-request-text
                                               "；不携带草稿、附件、会话历史或工具定义。"
                                               "测试成功不等于保存成功。")))]
                  (result state' [(persist-effect state')]))
                (result (message (:state queued) :error
                                 (get-in queued [:error :message])))))))))))

(defn- handle-cancel-test
  [state action]
  (let [draft (current-draft state action)
        draft-id (:draft-id draft)
        test (get-in state [:tests draft-id])]
    (if-not (and draft test (contains? #{:pending :running} (:status test)))
      (result (message state :info "没有正在进行的测试。"))
      (let [queued (commands/queue state :settings/cancel-test
                                   {:connection-id (:connection-id draft)
                                    :draft-id draft-id
                                    :test-id (:test-id test)}
                                   (select-keys action [:command-id :now]))
            cancelled? (:ok? queued)
            state' (-> (:state queued)
                       (assoc-in [:tests draft-id :status]
                                 (if cancelled? :cancelled :cancel-requested))
                       (message (if cancelled? :info :warning)
                                (if cancelled?
                                  "已请求取消测试；表单与草稿保留。"
                                  "取消请求尚未送达服务器；表单与草稿保留。")))]
        (result state' [(persist-effect state')])))))

(defn- handle-refresh-models
  [state action]
  (let [state (if (and (:connection-id action)
                       (nil? (current-draft state action)))
                (ensure-draft state (:connection-id action))
                state)
        draft (current-draft state action)
        connection-id (or (:connection-id draft) (:connection-id action))]
    (if-not connection-id
      (result (message state :error
                       "新连接尚未保存；请先保存，或刷新已有连接的模型列表。"))
      (let [queued (commands/queue state :settings/list-models
                                   {:connection-id connection-id
                                    :draft-id (:draft-id draft)}
                                   (select-keys action [:command-id :now]))]
        (if (:ok? queued)
          (result (-> (:state queued)
                      (assoc-in [:server :model-list-status connection-id] :refreshing)
                      (message :info "正在刷新模型列表；现有启用状态、别名与备注会保留。"))
                  [(persist-effect (:state queued))])
          (result (message (:state queued) :error
                           (get-in queued [:error :message]))))))))

(defn- handle-request-delete
  [state action]
  (let [draft (current-draft state action)]
    (result (-> state
                (assoc-in [:ui :confirm-delete-draft-id] (:draft-id draft))
                (message :warning
                         "删除连接会移除保存的配置；历史消息中的模型来源仍保留。请再次确认。")))))

(defn- handle-cancel-delete
  [state]
  (result (assoc-in state [:ui :confirm-delete-draft-id] nil)))

(defn- handle-confirm-delete
  [state action]
  (let [draft (current-draft state action)]
    (if-not (and draft (:connection-id draft))
      (result (message state :error "没有可删除的已保存连接。"))
      (let [queued (commands/queue state :settings/delete-connection
                                   {:connection-id (:connection-id draft)
                                    :draft-id (:draft-id draft)}
                                   (select-keys action [:command-id :now]))]
        (if (:ok? queued)
          (result (-> (:state queued)
                      (assoc-in [:ui :confirm-delete-draft-id] nil)
                      (message :info "已提交删除；等待服务器回执。"))
                  [(persist-effect (:state queued))])
          (result (message (:state queued) :error
                           (get-in queued [:error :message]))))))))

(defn- layer-state
  [value]
  (let [value (util/normalize-keyword value)]
    (cond
      (contains? #{:ok :passed :success :true :yes} value) :ok
      (contains? #{:failed :error :false :no} value) :failed
      (contains? #{:skipped :not-run :pending} value) :pending
      :else :unknown)))

(defn- layer-value
  [layers layer]
  (let [value (get layers layer)
        value (if (map? value) (:status value) value)]
    (layer-state value)))

(defn test-layer-rows
  [layers]
  (mapv (fn [layer]
          (let [state (layer-value layers layer)
                detail (when (map? (get layers layer))
                         (or (get-in layers [layer :detail])
                             (get-in layers [layer :message])))]
            {:layer layer
             :label (get test-layer-labels layer)
             :state state
             :status-label (case state
                             :ok "通过"
                             :failed "失败"
                             :pending "未运行"
                             "未知")
             :detail detail}))
        test-layer-order))

(defn test-conclusion
  [layers]
  (let [state #(layer-value layers %)]
    (cond
      (= :ok (state :minimal-request-ok))
      "所选模型已完成最小请求，可以生成。"

      (= :failed (state :auth-ok))
      "身份不可用；未证明所选模型可生成。"

      (= :failed (state :minimal-request-ok))
      "最小请求失败；所选模型尚未证明可生成。"

      (= :ok (state :models-listed))
      "模型可列出；尚未证明所选模型可生成。"

      (= :ok (state :auth-ok))
      "身份可用；模型列表与生成尚未证明。"

      (= :ok (state :reachable))
      "地址可达；身份、模型列表与生成均未证明。"

      (every? #(= :failed (state %)) test-layer-order)
      "测试未通过。"

      :else
      "测试未完成；未证明所选模型可生成。")))

(defn- apply-test-result
  [state test-result]
  (let [draft (or (find-draft state (:draft-id test-result))
                  (find-draft state (:connection-id test-result)))
        draft-id (:draft-id draft)]
    (if-not draft
      (result state)
      (let [current-digest (connection/draft-digest draft)
            result-digest (:config-digest test-result)
            status (util/normalize-keyword (:status test-result))
            stale? (or (nil? result-digest)
                       (not= result-digest current-digest))
            test (get-in state [:tests draft-id] {})
            stale-results (vec (take-last 5
                                         (conj (vec (:stale-results test))
                                               {:config-digest result-digest
                                                :status status
                                                :layers (:layers test-result)
                                                :at (:at test-result)})))]
        (cond
          (= :cancelled status)
          (result (-> state
                      (assoc-in [:tests draft-id :status] :cancelled)
                      (message :info "测试已取消；表单与草稿保留。"))
                  [(persist-effect state)])

          stale?
          (result (-> state
                      (assoc-in [:tests draft-id :stale-results] stale-results)
                      (message :warning
                               "收到旧配置的测试结果；未覆盖当前选择，草稿保留。"))
                  [(persist-effect state)])

          :else
          (result (-> state
                      (assoc-in [:tests draft-id]
                                (merge test
                                       {:status :complete
                                        :config-digest result-digest
                                        :layers (:layers test-result)
                                        :conclusion (test-conclusion (:layers test-result))
                                        :completed-at (:at test-result)}))
                      (message :info
                               "测试完成；测试成功不等于保存成功。"))
                  [(persist-effect state)]))))))

(defn- apply-list-models
  [state command receipt]
  (let [connection-id (or (get-in command [:payload :connection-id])
                          (:connection-id receipt))
        server-connections (:connections receipt)
        configured? (map? server-connections)
        receipt-models (or (:models receipt)
                           (when configured? (get server-connections connection-id)))
        configured-only? (or (= :configured-list-only (:model-list-status receipt))
                             (and configured? (nil? (:models receipt))))
        existing (get-in state [:server :models connection-id] [])
        listed? (not (or (:listing-unavailable? receipt)
                         (= :unavailable (:status receipt))
                         (:error receipt)))
        status (cond
                 (not listed?) :unavailable
                 configured-only? :configured-list-only
                 :else :listed)
        models (if (and listed? (some? receipt-models))
                 (catalog/merge-model-list existing receipt-models status)
                 existing)
        state' (-> state
                   (assoc-in [:server :models connection-id] models)
                   (assoc-in [:server :model-list-status connection-id] status)
                   (cond-> (:connection receipt)
                     (assoc-in [:server :connections connection-id]
                               (connection/normalize-connection (:connection receipt))))
                   (message (if listed? :info :warning)
                            (cond
                              (not listed?)
                              "模型列表接口不可用；现有模型与启用状态保留，可手动填写模型标识。"

                              configured-only?
                              "已接回服务器配置中的模型；服务器未提供实时列举，可手动补充模型标识。"

                              :else
                              "模型列表已刷新；未返回的模型标为“此次列表未返回”，不会删除。")))]
    (result state' [(persist-effect state')])))

(defn- apply-save-role
  [state command receipt]
  (let [role (get-in command [:payload :role])
        binding (get-in command [:payload :binding])
        submitted-version (get-in command [:payload :role-version])
        current (get-in state [:role-drafts role])
        clear? (or (nil? current)
                   (= submitted-version (:version current)))
        restart? (boolean (:restart-required receipt))
        accepted? (contains? #{:accepted :replayed} (:status receipt))]
    (if accepted?
      (let [state' (-> state
                       (assoc-in [:server :roles role] binding)
                       (cond-> clear? (update :role-drafts dissoc role))
                       (message :info
                                (cond
                                  (and clear? restart?)
                                  "角色绑定已保存；需要重启应用后对新会话与模型切换生效。"

                                  clear?
                                  "角色绑定已保存。"

                                  restart?
                                  "服务器已保存旧版本；更新的角色草稿仍保留；需要重启后生效。"

                                  :else
                                  "服务器已保存旧版本；更新的角色草稿仍保留。")))]
        (result state' [(persist-effect state')]))
      (result (message state :error
                       (or (get-in receipt [:error :message])
                           "角色绑定未被接受；草稿保留。"))))))

(defn- apply-delete
  [state command receipt]
  (if (contains? #{:accepted :replayed} (:status receipt))
    (let [connection-id (or (get-in command [:payload :connection-id])
                            (:connection-id receipt))
          draft-id (or (get-in command [:payload :draft-id])
                       (some (fn [[id draft]]
                               (when (= connection-id (:connection-id draft)) id))
                             (:drafts state)))
          state' (-> state
                     (update :server
                             (fn [server]
                               (-> server
                                   (update :connections dissoc connection-id)
                                   (update :models dissoc connection-id)
                                   (update :model-list-status dissoc connection-id))))
                     (drop-draft draft-id)
                     (assoc-in [:ui :selected-draft-id] nil)
                     (message :info "连接已删除；历史消息中的模型来源仍保留。"))]
      (result state' [(persist-effect state')]))
    (result (message state :error
                     (or (get-in receipt [:error :message])
                         "删除未被接受；连接与草稿保留。")))))

(defn- apply-save-connection
  [state command receipt]
  (let [draft-id (or (get-in command [:payload :draft-id])
                     (:draft-id receipt))
        draft (find-draft state draft-id)
        status (:status receipt)
        submitted (:payload command)]
    (cond
      (contains? #{:accepted :replayed} status)
      (if-not draft
        (result (message state :info "连接已保存。"))
        (let [submitted-digest (:config-digest submitted)
              receipt-digest (:config-digest receipt)
              digest-mismatch? (and receipt-digest
                                    submitted-digest
                                    (not= receipt-digest submitted-digest))]
          (if digest-mismatch?
            (let [state' (-> state
                             (assoc-in [:drafts (:draft-id draft)]
                                       (draft/mark-refused
                                        draft
                                        {:error {:message
                                                 "服务器回执的配置摘要与提交版本不一致；草稿保留，请核对后再提交。"}}))
                             (message :error
                                      "回执配置摘要不一致；未清理草稿，请核对服务器状态。"))]
              (result state' [(persist-effect state')]))
            (let [{:keys [draft clear?]} (draft/apply-save-accepted
                                          draft receipt submitted)
                  saved-connection-id (or (:connection-id draft)
                                          (:connection-id receipt))
                  submitted-connection (when (map? (:fields submitted))
                                         (connection/normalize-connection
                                          (:fields submitted)))
                  server-connection (if (:connection receipt)
                                      (connection/normalize-connection
                                       (:connection receipt))
                                      submitted-connection)
                  restart? (boolean (:restart-required receipt))
                  saved-message (if restart?
                                  "连接已保存；需要重启应用后生效。草稿已清理。"
                                  "连接已保存；草稿已清理。")
                  state' (cond-> state
                           (and server-connection saved-connection-id)
                           (-> (assoc-in [:server :connections saved-connection-id]
                                         server-connection)
                               (assoc-in [:server :models saved-connection-id]
                                         (vec (:models server-connection))))

                           clear?
                           (-> (drop-draft (:draft-id draft))
                               (assoc-in [:ui :selected-draft-id] nil)
                               (message :info saved-message))

                           (not clear?)
                           (-> (assoc-in [:drafts (:draft-id draft)] draft)
                               (message :info
                                        (if restart?
                                          "服务器已保存提交版本；需要重启应用后生效；更新的草稿仍保留。"
                                          "服务器已保存提交版本；更新的草稿仍保留。"))))]
              (result state' [(persist-effect state')])))))

      (= :outcome-unknown status)
      (if draft
        (let [state' (-> state
                         (assoc-in [:drafts (:draft-id draft)]
                                   (draft/mark-unknown draft receipt))
                         (message :warning "提交结果未知；草稿保留，请先核对连接状态，不要自动重发。"))]
          (result state' [(persist-effect state')]))
        (result (message state :warning "提交结果未知；请先核对连接状态。")))

      :else
      (if draft
        (let [state' (-> state
                         (assoc-in [:drafts (:draft-id draft)]
                                   (draft/mark-refused draft receipt))
                         (message :error
                                  (or (get-in receipt [:error :message])
                                      "服务器未接受保存；草稿保留。")))]
          (result state' [(persist-effect state')]))
        (result (message state :error
                         (or (get-in receipt [:error :message])
                             "服务器未接受保存；草稿保留。")))))))

(defn- apply-test-receipt
  [state command receipt]
  (cond
    (or (:layers receipt) (:result receipt))
    (apply-test-result state
                       (merge (:payload command)
                              receipt
                              (:result receipt)))

    (= :cancelled (:status receipt))
    (let [draft-id (or (get-in command [:payload :draft-id])
                       (:draft-id receipt))]
      (result (-> state
                  (assoc-in [:tests draft-id :status] :cancelled)
                  (message :info "测试已取消；表单与草稿保留。"))))

    (contains? #{:accepted :replayed} (:status receipt))
    (let [draft-id (or (get-in command [:payload :draft-id])
                       (:draft-id receipt))]
      (result (-> state
                  (assoc-in [:tests draft-id :status] :running)
                  (message :info "测试已开始；测试成功不等于保存成功。"))))

    :else
    (result (message state :error
                     (or (get-in receipt [:error :message])
                         "测试未被接受；表单与草稿保留。")))))

(defn- apply-save-assistants
  [state command receipt]
  (if (contains? #{:accepted :replayed} (:status receipt))
    (let [payload (:payload command)
          submitted (get payload :assistants)
          delete-id (:delete-assistant-id payload)
          assistants (if (map? submitted)
                       (normalize-assistants submitted)
                       (get-in state [:server :assistants]))
          assistants (if delete-id
                       (dissoc assistants (str delete-id))
                       assistants)
          ;; A delete receipt removes only the deleted assistant's draft.
          ;; Save receipts keep local drafts for ids the server did not
          ;; accept (validation may drop unknown fields).
          drafts (if delete-id
                   (dissoc (:assistant-drafts (:ui state) {})
                           (str delete-id))
                   (:assistant-drafts (:ui state) {}))
          state' (cond-> (-> state
                              (assoc-in [:server :assistants] assistants)
                              (assoc-in [:ui :assistant-drafts] drafts))
                    delete-id
                    (-> (assoc-in [:ui :confirm-delete-assistant-id] nil)
                        (assoc-in [:ui :selected-assistant-id] nil)))
          state' (message state' :info
                           (if delete-id
                             "助手已删除。"
                             "助手已保存。"))]
      (result state' [(persist-effect state')]))
    (result (message state :error
                     (or (get-in receipt [:error :message])
                         "助手保存未被接受；本地修改保留。")))))

(defn- apply-domain-receipt
  [state command receipt]
  (case (:kind command)
    :settings/save-connection (apply-save-connection state command receipt)
    :settings/test-connection (apply-test-receipt state command receipt)
    :settings/cancel-test (apply-test-receipt state command receipt)
    :settings/list-models (apply-list-models state command receipt)
    :settings/save-role (apply-save-role state command receipt)
    :settings/delete-connection (apply-delete state command receipt)
    :settings/save-assistants (apply-save-assistants state command receipt)
    :session/select-model
    (if (contains? #{:accepted :replayed} (:status receipt))
      (result (message state :info
                       "模型切换已接受；生成中的当前轮仍归属原模型。"))
      (result (message state :error
                       (or (get-in receipt [:error :message])
                           "模型切换未被接受；会话模型未改变，草稿保留。"))))
    (:model-auth/begin :model-auth/poll :model-auth/status
     :model-auth/providers :model-auth/cancel :model-auth/logout)
    (if-let [action (model-auth/receipt-action (:kind command) receipt)]
      (handle-model-auth state action)
      (result state))
    (result state)))

(defn apply-receipt
  "Apply one server receipt.  A receipt never clears a newer draft."
  [state receipt]
  (let [{:keys [state command receipt]} (commands/record-receipt state receipt)]
    (if command
      (apply-domain-receipt state command receipt)
      (result (message state :warning
                       "收到未知命令的回执；未改变任何草稿。")))))

(defn mark-outcome-unknown
  "Record that the transport threw after a command was handed off."
  [state command-id error]
  (let [state' (commands/mark-outcome-unknown state command-id error)
        command (get-in state' [:commands command-id])
        draft-id (:draft-id command)
        draft (find-draft state' draft-id)
        state' (if draft
                 (assoc-in state' [:drafts (:draft-id draft)]
                           (draft/mark-unknown draft
                                              {:error {:message (util/error-message error)}}))
                 state')]
    (result (message state' :warning
                     "提交结果未知；草稿保留。请先核对目标状态，不要自动重发。")
            [(persist-effect state')])))

(defn onboarding-status
  "Return the first-run checklist.  Browsing and drafts never require a
  model; the checklist only describes the remaining setup."
  [state]
  (let [connections (get-in state [:server :connections])
        groups (model-groups state)
        enabled-chat-model?
        (some (fn [group]
                (and (:connection-enabled? group)
                     (some :enabled? (:rows group))))
              groups)
        main (role-row state :main)
        tested? (some #(contains? #{:complete :running} (:status %))
                      (vals (:tests state)))
        wizard-step (get-in state [:ui :onboarding :step] :connection-type)
        ;; The checklist step is derived from server state; the wizard step
        ;; is the operator's current position.  They are separate so going
        ;; back never rewrites what is actually configured.
        step (cond
               (empty? connections) :connection-type
               (not enabled-chat-model?) :model
               :else :done)]
    {:step wizard-step
     :checklist-step step
     :complete? (= :done step)
     :connection-count (count connections)
     :has-main? (boolean main)
     :has-enabled-chat-model? (boolean enabled-chat-model?)
     :tested? (boolean tested?)
     :dismissed? (boolean (get-in state [:ui :onboarding :dismissed?]))
     :completed? (boolean (get-in state [:ui :onboarding :completed?]))}))

(defn- handle-onboarding
  [state action]
  (let [type (util/normalize-keyword (:type action))
        onboarding (get-in state [:ui :onboarding])
        step-order [:connection-type :connection-details :model :done]
        step-index (fn [step]
                     (or (first (keep-indexed (fn [idx candidate]
                                                (when (= candidate step) idx))
                                              step-order))
                         0))
        onboarding (case type
                     :settings/start-onboarding
                     (assoc onboarding :dismissed? false :completed? false
                            :step :connection-type)

                     :settings/onboarding-step
                     (assoc onboarding :step (util/normalize-keyword (:step action))
                            :dismissed? false)

                     :settings/onboarding-back
                     (let [idx (step-index (:step onboarding))]
                       (assoc onboarding
                              :step (nth step-order (max 0 (dec idx)))
                              :dismissed? false))

                     :settings/onboarding-next
                     (let [idx (step-index (:step onboarding))]
                       (assoc onboarding
                              :step (nth step-order
                                         (min (dec (count step-order))
                                              (inc idx)))
                              :dismissed? false))

                     :settings/dismiss-onboarding
                     (assoc onboarding :dismissed? true)

                     :settings/finish-onboarding
                     (assoc onboarding :dismissed? true :completed? true
                            :step :done)

                     :settings/reset-onboarding
                     (assoc onboarding :dismissed? false :completed? false
                            :step :connection-type)

                     onboarding)]
    (result (assoc-in state [:ui :onboarding] onboarding))))

(defn- handle-onboarding-edit-field
  "Store one connection-detail field on the wizard's draft.

  The wizard edits the draft's `:fields` map through the same
  `draft/edit-field` path the providers section uses, so saving the
  connection later sees exactly what the operator typed.  The value is also
  mirrored into `:ui :onboarding :fields` for the step summary."
  [state action]
  (let [field (util/normalize-keyword (:field action))
        value (:value action)
        draft-id (or (:draft-id action)
                     (get-in state [:ui :onboarding :draft-id]))
        draft (find-draft state draft-id)]
    (if-not draft
      (result (message state :error "还没有连接草稿；请先选择连接方式。"))
      (let [draft (if (= :path field)
                    ;; Changing the connection kind resets the draft shape;
                    ;; `draft/set-path` keeps the id and only touches
                    ;; kind-specific fields so a cloud-key draft cannot leak
                    ;; into local.
                    (draft/set-path draft value {:now (:now action)})
                    (draft/edit-field draft field value
                                      {:now (:now action)}))
            state (-> state
                      (assoc-in [:drafts (:draft-id draft)] draft)
                      (assoc-in [:ui :onboarding :fields field] value)
                      (assoc-in [:ui :onboarding :draft-id] (:draft-id draft))
                      (assoc-in [:ui :selected-draft-id] (:draft-id draft)))]
        (result state [(persist-effect state)])))))

(defn- handle-onboarding-choose-connection
  "Select or create the draft the wizard edits.

  `:path` is one of the connection service kinds.  An existing draft for the
  same path is reused so stepping back never duplicates unsaved input; a
  saved connection becomes a normal edit draft through `ensure-draft`."
  [state action]
  (let [path (util/normalize-keyword (:path action))
        onboarding (get-in state [:ui :onboarding])
        draft-id (:draft-id onboarding)
        drafts (:drafts state)
        existing-id (or (when (and draft-id (find-draft state draft-id))
                             draft-id)
                           (some (fn [[id draft]]
                                  (when (and (= path (get-in draft [:fields
                                                                   :service-kind]))
                                              (not (:orphaned? draft)))
                                     id))
                                 drafts))]
    (cond
      existing-id
      (result (-> state
                  (assoc-in [:ui :onboarding :draft-id] existing-id)
                  (assoc-in [:ui :onboarding :step] :connection-details)
                  (assoc-in [:ui :onboarding :dismissed?] false)
                  (assoc-in [:ui :selected-draft-id] existing-id)
                  (message :info "继续编辑连接草稿；返回上一步不会丢失已填内容。")))

      :else
      (let [draft (draft/blank-draft path (select-keys action [:now]))
            state (-> state
                      (assoc-in [:drafts (:draft-id draft)] draft)
                      (assoc-in [:ui :selected-draft-id] (:draft-id draft))
                      (assoc-in [:ui :onboarding :draft-id] (:draft-id draft))
                      (assoc-in [:ui :onboarding :step] :connection-details)
                      (assoc-in [:ui :onboarding :dismissed?] false)
                      (assoc-in [:ui :section] :providers)
                      (message :info
                               (str "已新建" (draft/path-label draft)
                                    "草稿；保存成功前不会出现在连接列表。")))]
        (result state [(persist-effect state)])))))

(defn set-transport-available
  [state available?]
  (result (assoc-in state [:ui :transport-available?] (boolean available?))))

(defn set-draft-storage-status
  "Record whether the caller could persist drafts.  `:error` keeps the
  `草稿暂未保存` notice visible; it never claims a draft was saved."
  [state status]
  (result (assoc-in state [:ui :draft-storage-status]
                    (util/normalize-keyword status))))

(defn- draft-model-list
  "Return the draft's model rows, including legacy manual ids."
  [draft]
  (let [models (vec (:models draft))
        ids (set (map (fn [model]
                        (str (or (:model-id model) (:id model) (:name model))))
                      models))]
    (into models
          (comp (map str)
                (remove #(contains? ids %))
                (map (fn [id]
                       {:id id
                        :model-id id
                        :name id
                        :display-name id
                        :enabled? true
                        :source :manual
                        :availability :unknown
                        :pricing :unknown
                        :capabilities {}
                        :capability-sources {}
                        :capability-baseline {}})))
          (:manual-models draft))))

(defn- models-with-drafts
  "Show pending draft edits without overwriting the server copy."
  [state]
  (let [connections (get-in state [:server :connections])
        models (get-in state [:server :models])]
    (reduce-kv (fn [out _draft-id draft]
                 (let [connection-id (:connection-id draft)]
                   (if (and connection-id
                            (contains? connections connection-id))
                     (let [draft-models (draft-model-list draft)
                           server-models (get models connection-id)]
                       ;; A draft persisted before the model list was seeded
                       ;; can have no rows while the server still has them.
                       ;; Showing the server rows is safer than hiding the
                       ;; connection's models until the next save.
                       (assoc out connection-id
                              (if (and (empty? (:models draft))
                                       (seq server-models))
                                (let [server-ids
                                      (set (map (fn [model]
                                                  (str (or (:model-id model)
                                                           (:id model)
                                                           (:name model))))
                                                server-models))]
                                  (into (vec server-models)
                                        (remove (fn [model]
                                                  (contains?
                                                   server-ids
                                                   (str (or (:model-id model)
                                                            (:id model)
                                                            (:name model))))))
                                        draft-models))
                                draft-models)))
                     out)))
               (or models {})
               (or (:drafts state) {}))))

(defn- connections-with-drafts
  "Replace each edited connection's manual-model list with the draft list.

  Without this, removing a manual model from a draft would still show the
  server's older chip because the grouping function re-adds every configured
  manual id that is absent from the model rows."
  [state]
  (reduce-kv (fn [out _draft-id draft]
               (let [connection-id (:connection-id draft)]
                 (if (and connection-id
                          (contains? out connection-id))
                   (if (and (empty? (:models draft))
                            (empty? (:manual-models draft)))
                     out
                     (assoc-in out [connection-id :manual-models]
                               (:manual-models draft)))
                   out)))
             (or (get-in state [:server :connections]) {})
             (or (:drafts state) {})))

(defn model-groups
  [state]
  (let [groups (catalog/grouped-models
                (connections-with-drafts state)
                (models-with-drafts state)
                (get-in state [:server :model-list-status]))
        selection (get-in state [:ui :selection])
        selected-role (get-in state [:ui :selected-role])
        role-binding (or (get-in state [:role-drafts selected-role])
                         (get-in state [:server :roles selected-role]))]
    (mapv (fn [group]
            (update group :rows
                    (fn [rows]
                      (mapv (fn [row]
                              (assoc row
                                     :selected?
                                     (= (catalog/selection-key selection)
                                        (catalog/selection-key row))
                                     :selected-for-role?
                                     (and (= :explicit (:mode role-binding))
                                          (= (:connection-id role-binding)
                                             (:connection-id row))
                                          (= (:model-id role-binding)
                                             (:model-id row)))))
                            rows))))
          groups)))

(defn connection-cards
  [state]
  (let [selected (selected-draft-id state)]
    (->> (get-in state [:server :connections])
         (sort-by (fn [[id connection]] [(:name connection) id]))
         (mapv (fn [[id connection]]
                 (let [draft (find-draft state id)]
                   (assoc (connection/connection-summary connection)
                          :selected? (= selected id)
                          :draft-status
                          (when (and draft (draft/dirty? draft))
                            (draft/status-label draft)))))))))

(defn draft-cards
  "Return the drafts that have no connection card of their own.

  A saved connection is edited from its card.  A new or orphaned draft has
  no card, so without this list a second new-connection click would make the
  first unsaved draft unreachable even though it stayed in `:drafts`."
  [state]
  (let [selected (selected-draft-id state)]
    (->> (:drafts state)
         (keep (fn [[draft-id draft]]
                 (when (or (= :new (:mode draft))
                           (:orphaned? draft))
                   {:draft-id draft-id
                    :name (draft/display-name draft)
                    :short-id (draft/short-id draft)
                    :path-label (draft/path-label draft)
                    :status (draft/status draft)
                    :status-label (draft/status-label draft)
                    :version (:version draft)
                    :manual-model-count (count (:manual-models draft))
                    :selected? (= draft-id selected)
                    :connection-id (:connection-id draft)})))
         (sort-by (fn [card]
                    [(if (:selected? card) 0 1)
                     (:name card)
                     (:draft-id card)]))
         vec)))

(defn selected-draft
  [state]
  (find-draft state (selected-draft-id state)))

(defn role-binding
  [state role]
  (or (get-in state [:role-drafts role])
      (get-in state [:server :roles role])
      (default-role role)))

(defn- role-row
  [state role]
  (let [binding (role-binding state role)
        groups (model-groups state)
        explicit (when (= :explicit (:mode binding))
                   (catalog/find-row groups binding))
        main (when (= :follow-main (:mode binding))
               (catalog/find-row groups (role-binding state :main)))]
    (or explicit main)))

(defn role-status
  [state role]
  (let [binding (role-binding state role)
        row (role-row state role)]
    (case role
      :main (if row
              (str "当前：" (catalog/selection-label (model-groups state) row))
              "未选择主模型；发送前需先选择。")

      :task
      (cond
        (= :follow-main (:mode binding))
        (if-let [main (role-row state :main)]
          (str "跟随主模型：" (catalog/selection-label (model-groups state) main))
          "未选择主模型；任务模型也没有可用来源，蜂群无法启动。")

        row (str "当前：" (catalog/selection-label (model-groups state) row))
        (and (:connection-id binding) (:model-id binding))
        "任务模型绑定指向的连接或模型已不可用；请重新选择。"
        :else "未选择任务模型；Plan dedicated worker 与蜂群无法启动。")

      :utility
      (cond
        (= :follow-main (:mode binding))
        (cond
          (nil? (role-row state :main))
          "未选择主模型；辅助模型需先选择主模型或单独指定。"

          (:confirmed? binding)
          "跟随主模型。"

          :else
          "跟随主模型；需要确认后才能用于摘要与标题。")

        row (str "单独指定：" (catalog/selection-label (model-groups state) row))
        :else "未选择辅助模型；摘要与标题使用可辨认的临时名称。")

      :embedding
      (if row
        (str "当前：" (catalog/selection-label (model-groups state) row))
        "检索不可用；纯文本聊天不受影响。")

      :vision
      (cond
        (= :follow-main (:mode binding))
        (let [main (role-row state :main)
              capability (get-in main [:capabilities :vision] :unknown)]
          (if-not main
            "未选择主模型；图片功能不可用。"
            (case capability
              :known "使用主模型（已确认视觉能力）。"
              :unsupported "主模型不支持视觉；需要单独指定视觉模型。"
              "主模型视觉能力尚未确认；图片发送前需确认。")))

        row
        (str "单独指定：" (catalog/selection-label (model-groups state) row)
             "；图片会发送到 " (:actual-host row) "。")

        :else
        "未选择视觉模型；图片功能不可用。")

      "")))

(defn role-cards
  [state]
  (mapv (fn [role]
          (let [binding (role-binding state role)
                row (role-row state role)]
            {:role role
             :label (get role-labels role)
             :description (get role-descriptions role)
             :binding binding
             :mode (:mode binding)
             :status (role-status state role)
             :selection-label (when row
                                (catalog/selection-label (model-groups state) row))
             :source-label (:source-label row)
             :actual-host (:actual-host row)
             :dirty? (boolean (get-in state [:role-drafts role :dirty?]))
             :confirmed? (:confirmed? binding)}))
        role-order))

(defn test-state
  [state draft-id]
  (get-in state [:tests (or draft-id (selected-draft-id state))]
          {:status :idle :layers {} :stale-results []}))

(defn test-view
  "Return the test panel view.

  The optional NOW makes the waiting duration explicit; callers that do not
  have a clock still get the waiting copy without a fabricated elapsed time."
  ([state draft-id] (test-view state draft-id nil))
  ([state draft-id now]
   (let [draft (or (find-draft state draft-id) (selected-draft state))
         connection (get-in state [:server :connections (:connection-id draft)])
         test (test-state state draft-id)
         no-probe? (= :none (:test-mode connection))
         current-digest (when draft (connection/draft-digest draft))
         test-digest (:config-digest test)
         stale? (boolean (and current-digest
                              test-digest
                              (not= current-digest test-digest)))
         status (if stale? :stale (:status test))
         waiting? (contains? #{:pending :running} status)
         started-at (:started-at test)
         elapsed-ms (when (and waiting? (number? now) (number? started-at))
                      (max 0 (- (long now) (long started-at))))
         elapsed-label (when elapsed-ms
                         (str (long (/ elapsed-ms 1000)) " 秒"))
         waiting-copy (when waiting?
                        (let [copy (failures/failure-copy
                                    {:code :response-timeout
                                     :context {:elapsed-ms elapsed-ms}})]
                          (cond-> copy
                            elapsed-label
                            (update :body str " 已等待 " elapsed-label "。"))))]
     {:status status
      :stale? stale?
      :current-digest current-digest
      :waiting? waiting?
      :elapsed-ms elapsed-ms
      :elapsed-label elapsed-label
      :waiting-copy waiting-copy
      :status-label (case status
                      :idle "未测试"
                      :pending "等待测试"
                      :running "等待服务返回"
                      :complete "测试完成"
                      :stale "配置已修改；旧结果仅参考"
                      :cancelled "已取消"
                      :cancel-requested "取消请求未送达"
                      "状态未知")
      :layers (test-layer-rows (:layers test))
      :conclusion (:conclusion test)
      :config-digest (:config-digest test)
      :short-digest (some-> (:config-digest test) util/short-digest)
      :request-text (when-not no-probe? test-request-text)
      :stale-results (vec (reverse (:stale-results test)))
      :warning (if no-probe?
                 "该服务不提供无副作用测试；保存后保持“未测试”。"
                 "测试会发送一次固定测试请求，可能产生少量费用；不携带草稿、附件、会话历史或工具定义。")})))

(defn dismiss-message
  [state]
  (assoc-in state [:ui :message] nil))

;; ---------------------------------------------------------------------------
;; Assistants

(defn- assistant-draft-default-model
  "Return the default-model selection stored on an assistant draft."
  [draft]
  (or (:default-model draft)
      {:connection-id nil :model-id nil}))

(defn- assistant-validation-errors
  "Client-side checks for one assistant draft before it is queued."
  [state draft]
  (let [id (str/trim (str (:id draft)))
        assistants (get-in state [:server :assistants] {})
        editing-id (str (:assistant-id draft))
        collision? (and (contains? assistants id)
                        (not= id editing-id))
        name (str/trim (str (:name draft)))
        default-model (:default-model draft)
        groups (model-groups state)]
    (cond-> []
      (str/blank? id)
      (conj {:field :id :message "助手标识不能为空。"})

      collision?
      (conj {:field :id
             :message "助手标识已存在；请使用不同标识。"})

      (str/blank? name)
      (conj {:field :name :message "助手名称不能为空。"})

      (and (map? default-model)
           (or (str/blank? (str (:connection-id default-model)))
               (str/blank? (str (:model-id default-model)))))
      (conj {:field :default-model
             :message "默认模型需要同时选择连接与模型。"})

      (and (map? default-model)
           (util/present? (str (:connection-id default-model)))
           (util/present? (str (:model-id default-model)))
           (nil? (catalog/find-row groups default-model)))
      (conj {:field :default-model
             :message "默认模型指向的连接或模型已不可用。"}))))

(defn assistant-ref-counts
  "Return `{:sessions n :worldbooks n}` for ASSISTANT-ID.

  The bridge projects `:server/:assistant-refs` as
  `{assistant-id {:sessions [ids] :worldbooks [ids]}}`; a missing entry
  means no known references."
  [state assistant-id]
  (let [refs (get-in state [:server :assistant-refs] {})
        entry (get refs (str assistant-id))]
    {:sessions (count (get entry :sessions))
     :worldbooks (count (get entry :worldbooks))}))

(defn- assistant-row
  [state assistant]
  (let [groups (model-groups state)
        draft (get-in state [:ui :assistant-drafts (:id assistant)])
        default-model (or (:default-model draft)
                          (:default-model assistant))]
    (cond-> {:id (:id assistant)
             :assistant-id (:assistant-id assistant)
             :name (:name assistant)
             :display-name (:display-name assistant)
             :revision (:revision assistant)
             :enabled? (if draft
                         (not (false? (:enabled? draft)))
                         (:enabled? assistant))
             :editing? (some? draft)
             :selected? (= (:id assistant)
                           (get-in state [:ui :selected-assistant-id]))
             :default-model default-model
             :default-model-label
             (when (and (map? default-model)
                        (util/present? (str (:connection-id default-model))))
               (when-let [row (catalog/find-row groups default-model)]
                 (catalog/selection-label groups row)))
             :reference-counts (assistant-ref-counts state (:id assistant))}
      draft
      (assoc :name (or (:name draft) (:name assistant))
             :display-name (or (:display-name draft)
                               (:display-name assistant))
             :enabled? (not (false? (:enabled? draft)))))))

(defn assistant-cards
  "Return assistant rows for the settings assistants section."
  [state]
  (->> (get-in state [:server :assistants] {})
       (map (fn [[_id assistant]] (assistant-row state assistant)))
       (sort-by (fn [card]
                  [(if (:selected? card) 0 1)
                   (str/lower-case (str (:name card)))
                   (:id card)]))
       vec))

(defn- new-assistant-id
  "Return a fresh assistant id that does not collide with existing ids."
  [state]
  (let [existing (set (keys (get-in state [:server :assistants] {})))
        base "assistant"
        candidate (loop [n 1]
                    (let [id (if (= 1 n) base (str base "-" n))]
                      (if (contains? existing id)
                        (recur (inc n))
                        id)))]
    candidate))

(defn- handle-new-assistant
  [state _action]
  (let [id (new-assistant-id state)
        draft {:id id
               :assistant-id id
               :name ""
               :display-name ""
               :enabled? true
               :default-model nil
               :is-new? true
               :version 0
               :editing? true}
        state' (-> state
                   (assoc-in [:ui :assistant-drafts id] draft)
                   (assoc-in [:ui :selected-assistant-id] id)
                   (assoc-in [:ui :confirm-delete-assistant-id] nil)
                   (message :info "新建助手草稿；保存前不会出现在服务器助手列表。"))]
    (result state')))

(defn- handle-select-assistant
  [state action]
  (result (-> state
              (assoc-in [:ui :selected-assistant-id] (:assistant-id action))
              (assoc-in [:ui :confirm-delete-assistant-id] nil))))

(defn- handle-edit-assistant-field
  [state action]
  (let [assistant-id (str (:assistant-id action))
        field (util/normalize-keyword (:field action))
        value (:value action)
        existing (get-in state [:server :assistants assistant-id])
        draft (get-in state [:ui :assistant-drafts assistant-id]
                      {:id assistant-id
                       :assistant-id (:assistant-id existing assistant-id)
                       :enabled? (not (false? (:enabled? existing)))
                       :version 0})]
    (if-not (or existing
                 (contains? (:assistant-drafts (:ui state) {}) assistant-id))
      (result (message state :error "没有可编辑的助手。"))
      (let [current (or (:default-model draft)
                        (:default-model existing)
                        {})
            draft (case field
                    :default-model-connection
                    (assoc draft :default-model
                           (if (str/blank? (str value))
                             nil
                             {:connection-id (str value)
                              :model-id (:model-id current)}))

                    :default-model-model
                    (assoc draft :default-model
                           (if (str/blank? (str value))
                             (when (util/present? (str (:connection-id current)))
                               {:connection-id (:connection-id current)
                                :model-id nil})
                             {:connection-id (:connection-id current)
                              :model-id (str value)}))

                    ;; Every other field replaces its own key; the draft
                    ;; keeps fields the action did not name.  Unknown fields
                    ;; leave the draft unchanged.
                    (if (contains? #{:id :assistant-id :name :display-name
                                     :enabled? :default-model}
                                   field)
                      (assoc draft field
                             (case field
                               :id (str/trim (str value))
                               :assistant-id (str/trim (str value))
                               :name (str value)
                               :display-name (str value)
                               :enabled? (boolean value)
                               :default-model (when (map? value) value)))
                      draft))]
        (result (assoc-in state [:ui :assistant-drafts assistant-id]
                          (assoc draft :editing? true)))))))

(defn- handle-save-assistant
  [state action]
  (let [assistant-id (str (:assistant-id action))
        draft (get-in state [:ui :assistant-drafts assistant-id])
        existing (get-in state [:server :assistants assistant-id])]
    (if-not (or draft existing)
      (result (message state :error "没有可保存的助手草稿。"))
      (let [merged (merge (or existing
                              {:id assistant-id
                               :assistant-id assistant-id
                               :name assistant-id
                               :revision 1
                               :enabled? true})
                         (select-keys draft [:id :assistant-id :name
                                             :display-name :enabled?
                                             :default-model]))
            draft-for-validation (assoc merged
                                         :is-new? (or (:is-new? draft)
                                                      (nil? existing)))
            errors (assistant-validation-errors state draft-for-validation)]
        (if (seq errors)
          (result (message state :error
                           (str "无法保存助手："
                                (str/join "；" (map :message errors)))))
          (let [assistants (get-in state [:server :assistants] {})
                saved-id (:id merged)
                next-assistants
                (cond-> (dissoc assistants assistant-id)
                  true (assoc saved-id
                              (-> merged
                                  (dissoc :editing? :version :renaming?)
                                  (assoc :revision
                                         (long (inc (or (:revision merged) 0)))))))
                queued (commands/queue state :settings/save-assistants
                                       {:assistants next-assistants}
                                       (select-keys action [:command-id :now]))]
            (if (:ok? queued)
              (result (-> (:state queued)
                          (assoc-in [:ui :assistant-drafts saved-id] nil)
                          (assoc-in [:ui :assistant-drafts assistant-id] nil)
                          (assoc-in [:ui :selected-assistant-id] saved-id)
                          (message :info
                                   "已提交助手保存；等待服务器回执。"))
                      [(persist-effect (:state queued))])
              (result (message (:state queued) :error
                               (get-in queued [:error :message]))))))))))

(defn- handle-request-delete-assistant
  [state action]
  (let [assistant-id (str (:assistant-id action))
        refs (assistant-ref-counts state assistant-id)
        total (+ (:sessions refs) (:worldbooks refs))
        detail (if (pos? total)
                 (str "当前有 " total " 处引用（会话 " (:sessions refs)
                      "，世界书 " (:worldbooks refs)
                      "）；引用保留原助手标识。")
                 "未发现会话或世界书引用该助手。")]
    (result (-> state
                (assoc-in [:ui :confirm-delete-assistant-id] assistant-id)
                (message :warning
                         (str "删除助手会移除其配置；" detail
                              "请再次确认。"))))))

(defn- handle-cancel-delete-assistant
  [state]
  (result (assoc-in state [:ui :confirm-delete-assistant-id] nil)))

(defn- handle-confirm-delete-assistant
  [state action]
  (let [assistant-id (str (:assistant-id action))
        existing (get-in state [:server :assistants assistant-id])]
    (cond
      (nil? existing)
      (result (message state :error "没有可删除的助手。"))

      (= "default" assistant-id)
      (result (message state :error
                       "内置“通用助手”不能删除；可以重命名或停用。"))

      :else
      (let [assistants (get-in state [:server :assistants] {})
            next-assistants (dissoc assistants assistant-id)
            queued (commands/queue state :settings/save-assistants
                                   {:assistants next-assistants
                                    :delete-assistant-id assistant-id}
                                   (select-keys action [:command-id :now]))]
        (if (:ok? queued)
          (result (-> (:state queued)
                      (assoc-in [:ui :assistant-drafts assistant-id] nil)
                      (assoc-in [:ui :confirm-delete-assistant-id] nil)
                      (assoc-in [:ui :selected-assistant-id] nil)
                      (message :info "已提交助手删除；等待服务器回执。"))
                  [(persist-effect (:state queued))])
          (result (message (:state queued) :error
                           (get-in queued [:error :message]))))))))

(defn- handle-edit-price-preview-input
  [state action]
  (let [key [(:connection-id action) (:model-id action)]
        field (util/normalize-keyword (:field action))
        value (:value action)
        current (get-in state [:ui :price-preview-inputs key]
                        {:input 1000 :output 500})]
    (result (assoc-in state [:ui :price-preview-inputs key field]
                      value))))

(defn- price-preview-row
  [state row]
  (let [key [(:connection-id row) (:model-id row)]
        inputs (get-in state [:ui :price-preview-inputs key]
                       {:input 1000 :output 500})
        preview (catalog/price-preview (:pricing row) inputs)]
    {:input (:input inputs)
     :output (:output inputs)
     :text (:text preview)
     :known? (:known? preview)}))

(defn price-preview
  "Return the price-preview view for one model row.

  The operator edits token counts; the preview either shows a computed
  estimate or `无法估算` when the price or unit is unknown."
  [state row]
  (price-preview-row state row))

(defn reduce-action
  "Apply one UI action.  Returns `{:state state' :effects effects}`.

  Actions never call a transport.  A queued command waits in `:outbox` for
  the caller to submit."
  [state action]
  (let [type (util/normalize-keyword (:type action))]
    (case type
      :settings/open (handle-open state)
      :settings/close (handle-close state)
      :settings/select-section (handle-select-section state action)
      :settings/new-connection (handle-new-connection state action)
      :settings/select-connection (handle-select-connection state action)
      :settings/select-draft (handle-select-draft state action)
      :settings/back-draft (handle-back-draft state)
      :settings/edit-field (handle-edit-field state action)
      :settings/set-path (handle-set-path state action)
      :settings/apply-auth-connection (handle-apply-auth-connection state action)
      :settings/set-location (handle-set-location state action)
      :settings/set-key-strategy (handle-set-key-strategy state action)
      :settings/edit-key-field (handle-edit-key-field state action)
      :settings/toggle-key (handle-toggle-key state action)
      :settings/add-key (handle-add-key state action)
      :settings/remove-key (handle-remove-key state action)
      :settings/edit-key-secret (handle-edit-secret state action)
      :settings/clear-key-secret (handle-clear-secret state action)
      :settings/toggle-secret (handle-toggle-secret state action)
      :settings/copy-secret (handle-copy-secret state action)
      :settings/edit-manual-model-input (handle-manual-model-input state action)
      :settings/add-manual-model (handle-add-manual-model state action)
      :settings/remove-manual-model (handle-remove-manual-model state action)
      :settings/toggle-enabled-model (handle-toggle-enabled-model state action)
      :settings/edit-model-field (handle-edit-model-field state action)
      :settings/set-model-capability (handle-set-model-capability state action)
      :settings/restore-model-capabilities
      (handle-restore-model-capabilities state action)
      :settings/edit-model-capabilities
      (handle-edit-model-capabilities state action)
      :settings/select-role (handle-select-role state action)
      (:model-auth/providers :model-auth/select-provider
       :model-auth/select-flow-type :model-auth/begin
       :model-auth/begin-result :model-auth/status-result
       :model-auth/status :model-auth/poll :model-auth/cancel
       :model-auth/logout :model-auth/tick :model-auth/copy-code
       :model-auth/open-authorize :model-auth/reveal-account
       :model-auth/dismiss-error :model-auth/reset :model-auth/error)
      (handle-model-auth state action)
      :settings/set-current-session (handle-set-current-session state action)
      :settings/select-session-model (handle-select-session-model state action)
      :settings/set-role-mode (handle-set-role-mode state action)
      :settings/confirm-role (handle-confirm-role state action)
      :settings/select-model (handle-select-model state action)
      :settings/save-connection (handle-save-connection state action)
      :settings/save-role (handle-save-role state action)
      :settings/test-connection (handle-test-connection state action)
      :settings/cancel-test (handle-cancel-test state action)
      :settings/refresh-models (handle-refresh-models state action)
      :settings/request-delete (handle-request-delete state action)
      :settings/cancel-delete (handle-cancel-delete state)
      :settings/confirm-delete (handle-confirm-delete state action)
      :settings/apply-receipt (apply-receipt state (:receipt action))
      :settings/receive-test-result (apply-test-result state (:result action))
      :settings/mark-outcome-unknown (mark-outcome-unknown state
                                                          (:command-id action)
                                                          (:error action))
      :settings/set-transport-available (set-transport-available
                                         state (:available? action))
      :settings/start-onboarding (handle-onboarding state action)
      :settings/onboarding-step (handle-onboarding state action)
      :settings/onboarding-back (handle-onboarding state action)
      :settings/onboarding-next (handle-onboarding state action)
      :settings/dismiss-onboarding (handle-onboarding state action)
      :settings/finish-onboarding (handle-onboarding state action)
      :settings/reset-onboarding (handle-onboarding state action)
      :settings/onboarding-edit-field (handle-onboarding-edit-field state action)
      :settings/onboarding-choose-connection
      (handle-onboarding-choose-connection state action)
      :settings/new-assistant (handle-new-assistant state action)
      :settings/select-assistant (handle-select-assistant state action)
      :settings/edit-assistant-field (handle-edit-assistant-field state action)
      :settings/save-assistant (handle-save-assistant state action)
      :settings/request-delete-assistant
      (handle-request-delete-assistant state action)
      :settings/cancel-delete-assistant (handle-cancel-delete-assistant state)
      :settings/confirm-delete-assistant
      (handle-confirm-delete-assistant state action)
      :settings/edit-price-preview-input
      (handle-edit-price-preview-input state action)
      :settings/set-draft-storage-status (set-draft-storage-status
                                          state (:status action))
      :settings/dismiss-message (result (dismiss-message state))
      (result state))))
