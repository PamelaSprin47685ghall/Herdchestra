(ns hc.client.extensions.model
  "Pure client model for MCP and skill management.

  The server catalog is the only authority.  This namespace validates drafts,
  builds command envelopes, and applies server receipts.  A local click never
  marks a server or skill as enabled; only the accepted receipt and the
  following `:extensions/view` snapshot do.

  There is no per-tool approval in always-authorized mode.  A tool's
  read-only/write/destructive annotation is shown, and the configured session
  role decides whether a call is allowed; this panel manages activation, not
  individual calls."
  (:require [clojure.string :as str]))

(def view-kind (keyword "extensions" "view"))
(def status-kind (keyword "extensions" "status"))
(def mcp-add-kind (keyword "extensions" "mcp/add"))
(def mcp-update-kind (keyword "extensions" "mcp/update"))
(def mcp-remove-kind (keyword "extensions" "mcp/remove"))
(def mcp-enable-kind (keyword "extensions" "mcp/enable"))
(def mcp-tool-enable-kind (keyword "extensions" "mcp/tool-enable"))
(def mcp-open-kind (keyword "extensions" "mcp/open"))
(def mcp-refresh-kind (keyword "extensions" "mcp/refresh"))
(def mcp-close-kind (keyword "extensions" "mcp/close"))
(def mcp-import-preview-kind (keyword "extensions" "mcp/import-preview"))
(def mcp-import-kind (keyword "extensions" "mcp/import"))
(def skill-install-kind (keyword "extensions" "skill/install"))
(def skill-enable-kind (keyword "extensions" "skill/enable"))
(def skill-bind-kind (keyword "extensions" "skill/bind"))
(def skill-unbind-kind (keyword "extensions" "skill/unbind"))
(def skill-delete-kind (keyword "extensions" "skill/delete"))
(def skill-compare-kind (keyword "extensions" "skill/compare"))
(def skill-update-kind (keyword "extensions" "skill/update"))
(def skill-session-kind (keyword "session" "select-skills"))

(def transport-labels
  {:http "HTTP 流式"
   :sse "SSE"
   :stdio "本地进程"})

(def server-state-labels
  {:unconnected "未连接"
   :connected-not-enabled "已连接，未启用"
   :available "可用"
   :needs-login "需登录"
   :connection-failed "连接失败"})

(def server-state-tones
  {:unconnected :neutral
   :connected-not-enabled :warn
   :available :ok
   :needs-login :warn
   :connection-failed :danger})

(defn session-availability
  "Project the current session's access to one MCP server.

  The server may omit this field; absence is not availability."
  [server]
  (let [available? (:session-available? server)
        scope (:session-scope server)]
    {:state (cond
              (true? available?) :available
              (false? available?) :unavailable
              :else :unknown)
     :label (cond
              (true? available?) "当前会话可用"
              (false? available?) "当前会话不可用"
              :else "当前会话可用性未报告")
     :scope scope}))

(defn skill-source-error-label
  [error]
  (if-not (map? error)
    (str (or error "技能源无法读取"))
    (let [code (cond
                 (keyword? (:code error))
                 (keyword (str/upper-case (name (:code error))))
                 (string? (:code error))
                 (keyword (str/upper-case
                           (str/replace (str (:code error)) #"^:" "")))
                 :else nil)]
      (case code
        :SKILL_SOURCE_NEEDS_SELECTION
        "该链接包含多个技能目录；请选择仓库中的具体 SKILL.md 或目录后再安装。"
        :SKILL_SOURCE_PRIVATE_ADDRESS
        "该链接指向本地或私有地址，服务器已拒绝。"
        (or (:message error) "技能源无法读取")))))

(def diff-status-labels
  {:unchanged "未变化"
   :upstream-changed "上游已改"
   :upstream-added "上游新增"
   :upstream-deleted "上游删除"
   :local-changed "本地已改"
   :local-only "仅本地"
   :local-deleted "本地已删"
   :same-add "双方相同"
   :same-change "双方相同"
   :conflict "冲突"
   :absent "不存在"})

(def diff-status-tones
  {:unchanged :neutral
   :upstream-changed :warn
   :upstream-added :ok
   :upstream-deleted :danger
   :local-changed :warn
   :local-only :neutral
   :local-deleted :danger
   :same-add :ok
   :same-change :ok
   :conflict :danger
   :absent :neutral})

(defn initial-state
  []
  {:ready? false
   :version 0
   :skills []
   :mcp []
   :connections []
   :selected-session-id nil
   :session-scope {:mode :follow :skills #{}}
   :resolved-skills {:active [] :blocked [] :excluded []}
   :mcp-import {:preview nil :failed [] :warnings []}
   :skill-updates {}
   :skill-update-sources {}
   :commands {}
   :outbox []
   :last-error nil
   :draft {:mcp {:name "" :type :http :url "" :command "" :args "" :env ""}
           :mcp-import ""
           :skill {:name "" :text "" :source :text :url ""
                   :archive-base64 "" :archive-name ""}
           :skill-updates {}
           :assistant-id ""}
   :selected {:mcp nil :skill nil}
   :confirm-delete {:mcp nil :skill nil}})

(defn field
  [m k]
  (when (map? m)
    (if (contains? m k)
      (get m k)
      (get m (if (keyword? k) (subs (str k) 1) (keyword k))))))

(defn non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn delete-confirmed?
  "Whether the pending deletion confirmation names this MCP server or skill.

  MCP servers and skills can share an id, so each family keeps its own
  confirmation slot."
  [state kind id]
  (let [id (str id)
        confirmed (str (get-in state [:confirm-delete kind]))]
    (and (not (str/blank? id)) (= id confirmed))))

(defn transport-type
  "Normalize a transport type from a keyword or a form/select string."
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case (str/trim value)))
    :else :http))

(defn source-kind
  "Normalize a skill source selector value."
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case (str/trim value)))
    :else :text))

(defn- now []
  #?(:clj (str (java.time.Instant/now))
     :cljs (str (js/Date.))))

(defn- command-id []
  (str (random-uuid)))

(defn- error [message & [details]]
  {:ok? false :error (merge {:message message} details)})

;; ---------------------------------------------------------------------------
;; Draft editing

(defn set-draft
  [state path value]
  (assoc-in state (into [:draft] path) value))

(defn set-selected
  [state kind id]
  (assoc-in state [:selected kind] id))

(defn- split-lines
  [value]
  (->> (str/split-lines (or value ""))
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- parse-env
  [text]
  (reduce (fn [env line]
            (let [idx (str/index-of line "=")]
              (if idx
                (let [k (str/trim (subs line 0 idx))
                      v (subs line (inc idx))]
                  (if (str/blank? k)
                    (reduced (error "环境变量行缺少名称" {:line line}))
                    (assoc env k v)))
                (reduced (error "环境变量必须写成 KEY=VALUE" {:line line})))))
          {}
          (split-lines text)))

(defn mcp-draft->server
  "Validate the MCP draft and return a normalized server map for the wire.

  The server normalizes again; this only prevents obviously empty forms from
  becoming commands."
  [draft]
  (let [name (str/trim (or (:name draft) ""))
        type (transport-type (:type draft))]
    (cond
      (str/blank? name)
      (error "服务器名称不能为空")

      (= :stdio type)
      (let [command (str/trim (or (:command draft) ""))
            env (parse-env (:env draft))]
        (if-not (non-blank-string? command)
          (error "本地进程需要可执行程序")
          (if-not (map? env)
            env
            {:ok? true
             :server {:name name
                      :type :stdio
                      :command command
                      :args (split-lines (:args draft))
                      :env env}})))

      (#{:http :sse} type)
      (let [url (str/trim (or (:url draft) ""))]
        (if-not (non-blank-string? url)
          (error "连接地址不能为空")
          {:ok? true :server {:name name :type type :url url}}))

      :else
      (error "未知连接方式"))))

(defn skill-draft->source
  [draft]
  (let [kind (source-kind (:source draft))
        name (str/trim (or (:name draft) ""))
        text (:text draft)
        url (str/trim (or (:url draft) ""))
        archive (:archive-base64 draft)]
    (cond
      (and (not (str/blank? name))
           (> (count name) 80))
      (error "技能名称不能超过 80 个字符")

      (= :text kind)
      (if-not (non-blank-string? text)
        (error "技能说明不能为空")
        {:ok? true
         :source (cond-> {:kind :text :text text}
                   (not (str/blank? name)) (assoc :name name))})

      (= :url kind)
      (if-not (non-blank-string? url)
        (error "技能链接不能为空")
        {:ok? true
         :source (cond-> {:kind :url :url url}
                   (not (str/blank? name)) (assoc :name name))})

      (= :archive kind)
      (if-not (non-blank-string? archive)
        (error "先选择技能压缩包")
        {:ok? true
         :source (cond-> {:kind :archive-base64
                          :base64 archive
                          :filename (or (:archive-name draft) "skill.zip")}
                   (not (str/blank? name)) (assoc :name name))})

      :else
      (error "未知技能来源"))))

(defn skill-update-source
  "Build the upstream source for one installed skill update."
  [state id]
  (let [source (get-in state [:draft :skill-update-sources id] {})
        kind (source-kind (:kind source))
        text (get-in state [:draft :skill-updates id])
        url (str/trim (or (:url source) ""))
        archive (:archive-base64 source)]
    (cond
      (= :text kind)
      (if (non-blank-string? text)
        {:ok? true :source {:kind :text :text text}}
        (error "先粘贴上游 SKILL.md"))

      (= :url kind)
      (if (non-blank-string? url)
        {:ok? true :source {:kind :url :url url}}
        (error "上游技能链接不能为空"))

      (= :archive kind)
      (if (non-blank-string? archive)
        {:ok? true
         :source {:kind :archive-base64
                  :base64 archive
                  :filename (or (:archive-name source) "skill.zip")}}
        (error "先选择上游技能压缩包"))

      :else
      (error "未知上游来源"))))

;; ---------------------------------------------------------------------------
;; Commands

(def ^:private command-history-limit
  "Completed extension commands kept for receipt inspection.  Pending
  commands are never pruned; the cap only bounds historical receipts."
  100)

(defn- prune-commands
  [state]
  (let [commands (:commands state)]
    (if (<= (count commands) command-history-limit)
      state
      (let [pending? (fn [[_ command]]
                       (contains? #{:queued :submitting} (:status command)))
            [pending completed] ((juxt filter remove) pending? commands)
            room (max 0 (- command-history-limit (count pending)))
            kept (take-last room
                           (sort-by (fn [[id command]]
                                      [(:created-at command) (str id)])
                                    completed))]
        (assoc state :commands (into (into {} pending) kept))))))

(defn- enqueue
  [state payload]
  (let [id (command-id)
        command {:command-id id
                 :payload payload
                 :kind (:kind payload)
                 :status :queued
                 :created-at (now)}]
    (-> state
        (assoc-in [:commands id] command)
        (update :outbox (fnil conj []) command)
        prune-commands)))

(defn pending?
  "A write is pending.  An automatic view refresh must not disable an
  already-prepared skill update; only user-visible writes block the form."
  [state]
  (boolean (some #(and (contains? #{:queued :submitting} (:status %))
                       (not= :extensions/view (:kind %)))
                 (vals (:commands state)))))

(defn mark-submitting
  [state id]
  (if (contains? (:commands state) id)
    (-> state
        (assoc-in [:commands id :status] :submitting)
        (update :outbox (fn [items] (vec (remove #(= id (:command-id %)) items)))))
    state))

(defn mark-send-failed
  [state id message]
  (-> state
      (assoc-in [:commands id :status] :failed)
      (assoc-in [:commands id :error] {:code :TRANSPORT_NOT_OPEN
                                       :message message})
      (assoc :last-error {:code :TRANSPORT_NOT_OPEN :message message})
      (update :outbox (fn [items] (vec (remove #(= id (:command-id %)) items))))))

(defn flush-outbox!
  "Hand every queued command to SUBMIT! exactly once.  SUBMIT! returns true
  only when Sente accepted the envelope."
  [state submit!]
  (if-not submit!
    state
    (loop [state state]
      (if-let [command (first (filter #(contains? #{:queued :submitting}
                                                  (:status %))
                                      (:outbox state)))]
        (let [id (:command-id command)
              state (mark-submitting state id)
              sent? (try (boolean (submit! command))
                         (catch #?(:clj Throwable :cljs :default) _ false))
              state (if sent?
                      state
                      (mark-send-failed state id "实时通道未接受该命令。"))]
          (recur state))
        state))))

;; ---------------------------------------------------------------------------
;; Receipts and snapshots

(defn- receipt-status
  [receipt]
  (let [inner (field receipt :receipt)
        status (field inner :status)]
    (cond
      (false? (field receipt :ok?)) :rejected
      (= :rejected status) :rejected
      (= :accepted status) :accepted
      (true? (field receipt :ok?)) :accepted
      :else :unknown)))

(defn- view-result?
  [result]
  (or (contains? result :skills) (contains? result :mcp)))

(defn switch-session
  "Reset the per-session extension view when the operator selects another
  session.  Catalog, installed skills, commands, and drafts survive; the
  previous session's scope and resolved skills must not be shown for the new
  one.  The next authoritative view replaces the empty projection."
  [state session-id]
  (if (= session-id (:selected-session-id state))
    state
    (assoc state
           :selected-session-id session-id
           :session-scope {:mode :follow :skills #{}}
           :resolved-skills {:active [] :blocked [] :excluded []})))

(defn normalize-session-scope
  "Normalize the server's session skill scope.

  `:mode` is `:follow` or `:custom`; `:skills` is a set of string ids.
  Unknown modes fall back to `:follow`, which never grants a skill by
  accident."
  [scope]
  (let [scope (or scope {})
        mode (keyword (or (field scope :mode) :follow))
        skills (set (map str (or (field scope :skills) [])))]
    {:mode (if (= :custom mode) :custom :follow)
     :skills skills}))

(defn apply-view
  [state result]
  (let [version (or (field result :version) 0)
        current (:version state)
        session-scope (field result :session-scope)
        resolved (field result :resolved-skills)]
    (if (and (integer? version) (integer? current) (< version current))
      state
      (prune-commands
       (cond-> (-> state
                   (assoc :ready? true
                          :version version
                          :skills (vec (or (field result :skills) []))
                          :mcp (vec (or (field result :mcp) []))
                          :connections (vec (or (field result :connections) [])))
                   (update :skill-updates #(or % {}))
                   (update :skill-update-sources #(or % {}))
                   (assoc :last-error nil))
         (map? session-scope)
         (assoc :session-scope (normalize-session-scope session-scope))

         (map? resolved)
         (assoc :resolved-skills
                {:active (vec (or (field resolved :active) []))
                 :blocked (vec (or (field resolved :blocked) []))
                 :excluded (vec (or (field resolved :excluded) []))})

         (some? (field result :session-id))
         (assoc :selected-session-id (field result :session-id)))))))

(defn- local-active-skills
  "Project the selected, enabled, unblocked skills for immediate feedback.

  The next authoritative view replaces this projection.  It never invents a
  skill the client has not installed, and it keeps the server's last blocked
  and excluded ids out."
  [state scope]
  (let [selected (set (:skills scope))
        blocked (set (map :id (get-in state [:resolved-skills :blocked])))
        excluded (set (map :id (get-in state [:resolved-skills :excluded])))]
    (->> (:skills state)
         (filter #(contains? selected (:id %)))
         (filter :enabled?)
         (remove #(contains? blocked (:id %)))
         (remove #(contains? excluded (:id %)))
         (mapv #(select-keys % [:id :name :version])))))

(defn- apply-receipt*
  [state event]
  (let [receipt (or (field event :value) (field event :receipt) event)
        id (or (field receipt :command-id) (field receipt :commandId))
        command (get (:commands state) id)
        status (receipt-status receipt)
        inner (field receipt :receipt)
        result (field inner :result)
        state (if command
                (assoc-in state [:commands id]
                          (merge command
                                 {:status status
                                  :receipt receipt
                                  :updated-at (now)}
                                 (when (field receipt :error)
                                   {:error (field receipt :error)})))
                state)
        state (if (and (= :accepted status) (map? result) (view-result? result))
                (apply-view state result)
                state)
        state (if (and (= :accepted status)
                       command
                       (= skill-compare-kind (:kind command))
                       (map? result))
                (assoc-in state [:skill-updates (:id (:payload command))]
                          {:diff (:diff result)
                           :skill (:skill result)
                           :checked-at (now)})
                state)
        state (if (and (= :accepted status)
                       command
                       (= skill-session-kind (:kind command))
                       (map? (field inner :session-scope)))
                (let [scope (normalize-session-scope
                             (field inner :session-scope))]
                  (-> state
                      (assoc :session-scope scope)
                      (assoc-in [:resolved-skills :active]
                                (local-active-skills state scope))))
                state)
        state (if (and (= :accepted status)
                       command
                       (= skill-update-kind (:kind command))
                       (map? result))
                (assoc-in state [:skill-updates (:id (:payload command)) :applied?]
                          true)
                state)
        state (if (and (= :accepted status)
                       command
                       (= mcp-import-preview-kind (:kind command))
                       (map? result))
                (assoc state :mcp-import
                       {:preview result :failed [] :warnings (:warnings result)})
                state)
        state (if (and (= :accepted status)
                       command
                       (= mcp-import-kind (:kind command)))
                (assoc state :mcp-import {:preview nil :failed [] :warnings []})
                state)
        state (if (and (= :accepted status)
                       command
                       (not (contains? #{view-kind status-kind} (:kind command)))
                       (not (:refresh-queued? (get-in state [:commands id]))))
                (-> state
                    (assoc-in [:commands id :refresh-queued?] true)
                    (enqueue (cond-> {:kind view-kind}
                               (:selected-session-id state)
                               (assoc :session-id (:selected-session-id state)))))
                state)]
    (prune-commands
     (cond
       (= :rejected status)
       (assoc state :last-error
              (or (field receipt :error)
                  (field inner :error)
                  {:code :EXTENSION_COMMAND_REJECTED
                   :message "服务器拒绝了扩展命令"}))
       (= :unknown status)
       (assoc state :last-error {:code :EXTENSION_COMMAND_UNKNOWN
                                 :message "未收到可判定的扩展回执"})
       :else state))))

;; ---------------------------------------------------------------------------
;; Reducer

(defn reduce-action
  "Apply one pure UI action.  Returns `{:state next :error error-or-nil}`."
  [state action]
  (let [type (:type action)]
    (case type
      :extensions/refresh
      {:state (enqueue state (cond-> {:kind view-kind}
                               (:selected-session-id state)
                               (assoc :session-id (:selected-session-id state))))}

      :extensions/mcp-draft
      {:state (set-draft state [:mcp (:field action)] (:value action))}

      :extensions/skill-draft
      {:state (set-draft state [:skill (:field action)] (:value action))}

      :extensions/assistant-draft
      {:state (set-draft state [:assistant-id] (:value action))}

      :extensions/draft
      {:state (set-draft state [(:group action) (:field action)] (:value action))}

      :extensions/select
      {:state (set-selected state (:group action) (:id action))}

      :extensions/dismiss-error
      {:state (assoc state :last-error nil)}

      :extensions/mcp-add
      (let [built (mcp-draft->server (get-in state [:draft :mcp]))]
        (if (:ok? built)
          {:state (-> state
                      (enqueue {:kind mcp-add-kind :server (:server built)})
                      (assoc-in [:draft :mcp] (:draft (initial-state))))}
          {:state state :error (:error built)}))

      :extensions/mcp-import-draft
      {:state (set-draft state [:mcp-import] (:value action))}

      :extensions/mcp-import-preview
      (let [text (get-in state [:draft :mcp-import])]
        (if (non-blank-string? text)
          {:state (enqueue state {:kind mcp-import-preview-kind :text text})}
          {:state state :error {:message "先粘贴 MCP 配置"}}))

      :extensions/mcp-import-apply
      (let [text (get-in state [:draft :mcp-import])]
        (if (non-blank-string? text)
          {:state (enqueue state {:kind mcp-import-kind
                                  :text text
                                  :opts {:on-duplicate :rename}})}
          {:state state :error {:message "先粘贴 MCP 配置"}}))

      :extensions/mcp-enable
      {:state (enqueue state {:kind mcp-enable-kind
                              :id (:id action)
                              :enabled? (boolean (:enabled? action))})}

      :extensions/mcp-request-delete
      {:state (assoc-in state [:confirm-delete :mcp] (:id action))}

      :extensions/mcp-cancel-delete
      {:state (assoc-in state [:confirm-delete :mcp] nil)}

      :extensions/mcp-remove
      (if (delete-confirmed? state :mcp (:id action))
        {:state (-> state
                    (enqueue {:kind mcp-remove-kind :id (:id action)})
                    (assoc-in [:confirm-delete :mcp] nil))}
        {:state state :error {:message "请先确认删除该服务器"}})

      :extensions/mcp-open
      {:state (enqueue state {:kind mcp-open-kind :id (:id action)})}

      :extensions/mcp-refresh
      {:state (enqueue state {:kind mcp-refresh-kind :id (:id action)})}

      :extensions/mcp-close
      {:state (enqueue state {:kind mcp-close-kind :id (:id action)})}

      :extensions/mcp-tool-enable
      {:state (enqueue state
                       (cond-> {:kind mcp-tool-enable-kind
                                :id (:id action)
                                :tool (:tool action)
                                :enabled? (boolean (:enabled? action))}
                         (:approve-review? action)
                         (assoc :opts {:approve-review? true})))}

      :extensions/skill-install
      (let [built (skill-draft->source (get-in state [:draft :skill]))]
        (if (:ok? built)
          {:state (-> state
                      (enqueue {:kind skill-install-kind :source (:source built)})
                      (assoc-in [:draft :skill] (:draft (initial-state))))}
          {:state state :error (:error built)}))

      :extensions/skill-source
      {:state (set-draft state [:skill (:field action)] (:value action))}

      :extensions/skill-archive
      {:state (-> state
                  (assoc-in [:draft :skill :archive-base64] (:value action))
                  (assoc-in [:draft :skill :archive-name] (:filename action))
                  (assoc-in [:draft :skill :source] :archive))}

      :extensions/skill-enable
      {:state (enqueue state {:kind skill-enable-kind
                              :id (:id action)
                              :enabled? (boolean (:enabled? action))})}

      :extensions/skill-update-draft
      {:state (set-draft state [:skill-updates (:id action)] (:value action))}

      :extensions/skill-update-source
      {:state (assoc-in state [:draft :skill-update-sources (:id action) :kind]
                        (or (:kind action) (:value action)))}

      :extensions/skill-update-url
      {:state (assoc-in state [:draft :skill-update-sources (:id action) :url]
                        (:value action))}

      :extensions/skill-update-archive
      {:state (-> state
                  (assoc-in [:draft :skill-update-sources (:id action)
                             :archive-base64] (:value action))
                  (assoc-in [:draft :skill-update-sources (:id action)
                             :archive-name] (:filename action))
                  (assoc-in [:draft :skill-update-sources (:id action) :kind]
                            :archive))}

      :extensions/skill-compare
      (let [id (:id action)
            source (skill-update-source state id)]
        (if (:ok? source)
          {:state (enqueue state
                           {:kind skill-compare-kind
                            :id id
                            :source (:source source)})}
          {:state state :error (:error source)}))

      :extensions/skill-apply-update
      (let [id (:id action)
            source (skill-update-source state id)]
        (if (:ok? source)
          {:state (enqueue state
                           {:kind skill-update-kind
                            :id id
                            :strategy (or (:strategy action) :keep-local)
                            :source (:source source)})}
          {:state state :error (:error source)}))

      :extensions/skill-request-delete
      {:state (assoc-in state [:confirm-delete :skill] (:id action))}

      :extensions/skill-cancel-delete
      {:state (assoc-in state [:confirm-delete :skill] nil)}

      :extensions/skill-delete
      (if (delete-confirmed? state :skill (:id action))
        {:state (-> state
                    (enqueue {:kind skill-delete-kind :id (:id action)})
                    (assoc-in [:confirm-delete :skill] nil))}
        {:state state :error {:message "请先确认删除该技能"}})

      :extensions/skill-bind
      {:state (if (non-blank-string? (get-in state [:draft :assistant-id]))
                (enqueue state {:kind skill-bind-kind
                                :id (:id action)
                                :assistant-id (get-in state [:draft :assistant-id])})
                {:state state :error {:message "先填写助手 ID"}})}

      :extensions/skill-unbind
      {:state (if (non-blank-string? (get-in state [:draft :assistant-id]))
                (enqueue state {:kind skill-unbind-kind
                                :id (:id action)
                                :assistant-id (get-in state [:draft :assistant-id])})
                {:state state :error {:message "先填写助手 ID"}})}

      :extensions/session-skills-follow
      (if-let [session-id (:selected-session-id state)]
        {:state (enqueue state {:kind skill-session-kind
                                :session-id session-id
                                :mode :follow})}
        {:state state :error {:message "先选择一个会话"}})

      :extensions/session-skills-custom
      (if-let [session-id (:selected-session-id state)]
        (let [current (set (get-in state [:session-scope :skills]))
              active (set (map :id (get-in state [:resolved-skills :active])))
              skills (if (seq current) current active)]
          {:state (enqueue state {:kind skill-session-kind
                                  :session-id session-id
                                  :mode :custom
                                  :skills (sort skills)})})
        {:state state :error {:message "先选择一个会话"}})

      :extensions/session-skill-toggle
      (if-let [session-id (:selected-session-id state)]
        (let [id (str (:id action))
              current (set (get-in state [:session-scope :skills]))
              next (if (:selected? action) (conj current id) (disj current id))]
          {:state (enqueue state {:kind skill-session-kind
                                  :session-id session-id
                                  :mode :custom
                                  :skills (sort next)})})
        {:state state :error {:message "先选择一个会话"}})

      :extensions/receive-receipt
      {:state (apply-receipt* state (:event action))}

      :extensions/apply-view
      {:state (apply-view state (:result action))}

      {:state state})))

(defn apply-receipt
  [state event]
  (:state (reduce-action state {:type :extensions/receive-receipt
                                :event event})))

(defn snapshot-from-db
  [db]
  (or (:ui/extensions db) (initial-state)))
