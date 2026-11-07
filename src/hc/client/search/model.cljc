(ns hc.client.search.model
  "Pure client state for the independent web-search control.

  The client never decides that a search succeeded.  It only carries the
  operator's mode/service selection and renders facts returned by the server.
  A missing service, a disabled service or a missing credential is a visible
  selection error, not a reason to pick another service silently."
  (:require [clojure.string :as str]))

(def modes [:off :auto :required])

(def mode-labels
  {:off "关闭"
   :auto "自动"
   :required "本轮必须搜索"})

(def mode-descriptions
  {:off "不发起联网搜索，也不会把模型知识标成已联网。"
   :auto "模型可按问题决定是否搜索；搜索失败会如实显示。"
   :required "答案形成前至少尝试一次；失败时本轮报错，不挂“已联网”。"})

(def initial-state
  {:mode :off
   :service-id nil
   :open? false
   :last-run nil
   :notice nil})

(defn state
  [db]
  (merge initial-state (or (:ui/search-state db) {})))

(defn state-for
  "Search selection belongs to one session.  A missing entry starts closed;
  one session's mode/service never leaks into another."
  [db session-id]
  (merge initial-state
         (or (get-in db [:ui/search-state session-id]) {})))

(defn- keywordize
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    :else value))

(defn search-snapshot
  "The server's `:search` component, accepting both the raw public config and
  the `{:search ...}` snapshot wrapper."
  [db]
  (let [value (:search db)]
    (if (and (map? value) (contains? value :search))
      (:search value)
      value)))

(defn- error-label
  [error]
  (when (map? error)
    (case (keywordize (:code error))
      :SEARCH_SERVICE_NOT_SELECTED "请选择搜索服务"
      :SEARCH_SERVICE_NOT_FOUND "所选搜索服务不在当前配置中"
      :SEARCH_SERVICE_DISABLED "所选搜索服务已停用"
      :SEARCH_SERVICE_INVALID "所选搜索服务配置无效"
      :SEARCH_CREDENTIAL_MISSING "所选搜索服务缺少可用凭据"
      :SEARCH_CREDENTIAL_INVALID "搜索凭据失效，请在设置中修复"
      :SEARCH_ACCESS_DENIED "搜索服务拒绝访问"
      :SEARCH_QUOTA_EXCEEDED "搜索额度或速率限制已到"
      :SEARCH_SERVICE_UNREACHABLE "搜索服务不可达"
      :SEARCH_TIMEOUT "搜索超时"
      :SEARCH_REDIRECT_REFUSED "搜索服务要求重定向，已拒绝"
      :SEARCH_RESPONSE_INVALID "搜索服务返回无法解析"
      :SEARCH_RESPONSE_TOO_LARGE "搜索服务响应过大"
      :SEARCH_QUERY_INVALID "搜索查询无效"
      :SEARCH_MODE_INVALID "搜索模式无效"
      :SEARCH_REQUIRED_FAILED "本轮必须搜索失败"
      :SEARCH_SERVICE_ERROR "搜索服务错误"
      (or (:message error) "搜索失败"))))

(def ^:private search-error-codes
  #{:SEARCH_SERVICE_NOT_SELECTED :SEARCH_SERVICE_NOT_FOUND
    :SEARCH_SERVICE_DISABLED :SEARCH_SERVICE_INVALID
    :SEARCH_CREDENTIAL_MISSING :SEARCH_CREDENTIAL_INVALID
    :SEARCH_ACCESS_DENIED :SEARCH_QUOTA_EXCEEDED
    :SEARCH_SERVICE_UNREACHABLE :SEARCH_TIMEOUT
    :SEARCH_REDIRECT_REFUSED :SEARCH_RESPONSE_INVALID
    :SEARCH_RESPONSE_TOO_LARGE :SEARCH_QUERY_INVALID
    :SEARCH_MODE_INVALID :SEARCH_REQUIRED_FAILED
    :SEARCH_SERVICE_ERROR})

(defn- credential-available?
  [service]
  (let [credentials (:credentials service)]
    (if (false? (:requires-credential? service))
      true
      (true? (:available? credentials)))))

(defn- service-reason
  [service]
  (cond
    (:invalid? service) (or (error-label (:error service)) "服务配置无效")
    (false? (:enabled? service)) "服务已停用"
    (not (credential-available? service)) "缺少可用凭据"
    :else nil))

(defn services
  "Normalize the server's service list for the selector.

  A service that cannot be selected stays visible with its reason; it is not
  silently removed and no other service is substituted."
  [db]
  (let [snapshot (search-snapshot db)
        services (get-in snapshot [:config :services])
        services (if (sequential? services) services [])]
    (mapv (fn [service]
            (let [reason (service-reason service)
                  selectable? (nil? reason)
                  last-test (:last-test service)
                  last-test-status (keywordize (:status last-test))]
              {:id (:id service)
               :name (or (:name service) (:id service) "未命名服务")
               :kind (:kind service)
               :enabled? (not (false? (:enabled? service)))
               :invalid? (boolean (:invalid? service))
               :available? (credential-available? service)
               :selectable? selectable?
               :reason reason
               :last-test (when (map? last-test)
                            {:status last-test-status
                             :label (case last-test-status
                                      :ok "最近测试成功"
                                      :failed "最近测试失败"
                                      "未测试")
                             :tested-at (:tested-at last-test)
                             :error (error-label (:error last-test))})
               :label (str (or (:name service) (:id service) "未命名服务")
                           (when-let [kind (:kind service)]
                             (str " · " (name kind)))
                           (when reason (str " · " reason)))}))
          services)))

(defn- id-str
  [value]
  (cond
    (keyword? value) (name value)
    (symbol? value) (name value)
    :else (str value)))

(defn selected-service
  [db state]
  (some #(when (= (id-str (:service-id state)) (id-str (:id %))) %) (services db)))

(defn selection-error
  "Return the first reason this mode cannot run, or nil for `:off`."
  [db state]
  (let [mode (keywordize (:mode state))]
    (cond
      (= :off mode) nil
      (not (contains? (set modes) mode)) "搜索模式无效"
      (str/blank? (str (:service-id state))) "请选择搜索服务"
      :else
      (let [service (selected-service db state)]
        (cond
          (nil? service) "所选搜索服务不在当前配置中"
          (not (:selectable? service)) (or (:reason service) "所选搜索服务不可用")
          :else nil)))))

(defn command-payload
  "Build the `:search` payload for one send.

  `:off` is sent explicitly so the server records a skipped search rather
  than guessing.  The caller must block a non-off mode when
  `selection-error` returns a reason."
  [state text]
  (let [mode (keywordize (:mode state))
        text (or text "")]
    (cond-> {:mode mode}
      (not= :off mode)
      (assoc :service-id (id-str (:service-id state))
             :queries [{:query-id "q1" :text text}]))))

(defn- run-of
  [state]
  (or (:last-run state) {}))

(defn run-status
  [state]
  (keywordize (:status (run-of state))))

(defn run-status-label
  [state]
  (case (run-status state)
    :ok "已搜索"
    :empty "搜索完成，没有结果"
    :partial "部分查询失败"
    :failed "搜索失败"
    :cancelled "搜索已停止"
    :skipped "未搜索"
    nil "尚未搜索"
    "状态未知"))

(defn run-error-label
  [state]
  (error-label (:error (run-of state))))

(defn command-error-label
  "Operator-facing label for a rejected command carrying a search error.
  Returns nil for a non-search command."
  [command]
  (when (map? command)
    (let [code (keywordize (get-in command [:error :code]))]
      (when (contains? search-error-codes code)
        (or (error-label (:error command))
            (:message command))))))

(defn must-search-failed?
  [state]
  (true? (:must-search-failed? (run-of state))))

(defn- host-of
  [url]
  (when (string? url)
    (some-> (re-find #"(?i)^https?://([^/]+)" url) second)))

(defn- source-url
  [source]
  (or (:canonical-url source) (:url source)))

(defn- merge-source
  [first-source next-source]
  (let [merged (reduce-kv (fn [acc k v]
                            (if (nil? (get acc k)) (assoc acc k v) acc))
                          first-source
                          next-source)]
    (assoc merged :queries
           (vec (distinct (concat (:queries first-source)
                                  (:queries next-source)))))))

(defn- merge-sources
  "Merge duplicate canonical URLs, keeping the first citation identity and
  the union of discovery queries."
  [sources]
  (let [{:keys [order by-key]}
        (reduce (fn [{:keys [order by-key]} [index source]]
                  (let [url (source-url source)
                        key (or url (str "source-" index))]
                    (if-let [existing (get by-key key)]
                      {:order order
                       :by-key (assoc by-key key
                                      (merge-source existing source))}
                      {:order (conj order key)
                       :by-key (assoc by-key key source)})))
                {:order [] :by-key {}}
                (map-indexed vector sources))]
    (mapv by-key order)))

(defn- sort-sources
  [sources]
  (->> sources
       (map-indexed vector)
       (sort-by (fn [[index source]]
                  [(or (:citation-number source) 1000000000) index]))
       (mapv second)))

(defn source-cards
  "Project the run's sources into cards.

  `:published-at` is never replaced by `:fetched-at`.  `:snippet` is the
  provider excerpt; full page text is not part of this view."
  [state]
  (let [run (run-of state)
        sources (or (seq (:source-cards run))
                    (seq (:sources run))
                    (seq (get-in run [:provenance :sources]))
                    [])
        sources (sort-sources (merge-sources sources))]
    (mapv (fn [source index]
            (let [url (or (:url source) (:canonical-url source))
                  published (:published-at source)]
              {:label (or (:label source)
                          (str "S" (or (:citation-number source) (inc index))))
               :number (:citation-number source)
               :title (or (:title source) url "来源")
               :url url
               :site (or (:site source) (host-of url))
               :published-at published
               :published-at-unknown? (or (:published-at-unknown? source)
                                          (nil? published))
               :fetched-at (:fetched-at source)
               :snippet (:snippet source)
               :content-status (keywordize (:content-status source))
               :queries (vec (or (:queries source) []))}))
          sources
          (range))))

(defn record-run
  "Store the server's run result for display.  The full `:refs`/`:documents`
  stay out of the client view."
  [state run]
  (if (map? run)
    (assoc state :last-run
           (select-keys run
                        [:ok? :status :mode :searched? :must-search-failed?
                         :cancelled? :service :queries :source-cards :sources
                         :provenance :omitted-source-count :omitted
                         :error :causes]))
    state))

(defn omitted-source-count
  "How many source cards the server omitted from the run context."
  [state]
  (let [run (run-of state)]
    (or (:omitted-source-count run)
        (get-in run [:omitted :sources])
        0)))

(defn set-mode
  [state mode]
  (let [mode (keywordize mode)]
    (if (contains? (set modes) mode)
      (assoc state :mode mode)
      state)))

(defn set-service
  [state service-id]
  (assoc state :service-id service-id))

(defn toggle-open
  [state]
  (update state :open? not))

(defn message-sources
  "Sources already attached to one assistant message version."
  [message]
  (let [sources (or (seq (:sources message))
                    (seq (:citations message))
                    [])
        sources (sort-sources (merge-sources sources))]
    (mapv (fn [source index]
            (let [published (:published-at source)]
              {:label (or (:label source)
                          (str "S" (or (:citation-number source) (inc index))))
               :number (:citation-number source)
               :title (or (:title source) (:url source) "来源")
               :url (:url source)
               :site (or (:site source) (host-of (:url source)))
               :published-at published
               :published-at-unknown? (or (:published-at-unknown? source)
                                          (nil? published))
               :fetched-at (:fetched-at source)
               :snippet (:snippet source)
               :content-status (keywordize (:content-status source))}))
          sources
          (range))))

(defn progress-label
  [message]
  (let [progress (:search-progress message)]
    (cond
      (string? progress) progress
      (map? progress) (or (:label progress) (:summary progress)
                          (some-> (:status progress) name))
      :else nil)))
