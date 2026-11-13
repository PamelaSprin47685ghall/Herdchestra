(ns hc.client.settings.connection
  "Normalize provider connections and their non-secret display fields.

  A connection keeps its identity separate from its display name.  Two
  connections may share a name; the actual host, service kind, and account
  label are always shown next to the name.  A custom endpoint is never
  labelled official merely because the user reused a familiar name."
  (:require [clojure.string :as str]
            [hc.client.settings.util :as util]))

(def service-kinds
  "The three connection paths from the interaction spec."
  [:cloud-key :account-login :local-service])

(def service-kind-labels
  {:cloud-key "服务密钥"
   :account-login "账号登录（OAuth/设备码）"
   :local-service "本地模型服务"})

(def local-locations
  [:instance :instance-host :lan-device :remote :unknown])

(def local-location-labels
  {:instance "实例自身环境"
   :instance-host "实例所在电脑"
   :lan-device "局域网另一台设备"
   :remote "远程服务器"
   :unknown "位置未知"})

(def local-location-notes
  {:instance "模型服务运行在实例自身环境；请求不出本机。"
   :instance-host "模型服务运行在实例所在电脑；请求不离开该电脑。"
   :lan-device "模型服务在局域网另一台设备；请求经过局域网，不能称为不出本机。"
   :remote "模型服务在远程服务器；请求会发送到该主机。"
   :unknown "模型服务位置未知；不能声称不出本机。"})

(def key-strategies
  [:priority :round-robin :least-used :random])

(def key-strategy-labels
  {:priority "优先级"
   :round-robin "轮询"
   :least-used "最少使用"
   :random "随机"})

(def protocol-labels
  {:openai-compatible "OpenAI 兼容"
   :anthropic "Anthropic"
   :ollama "Ollama"
   :custom "自定义协议"})

(def key-status-labels
  {:ready "可用"
   :cooldown "限流冷却中"
   :rate-limited "限流冷却中"
   :auth-failed "认证失败"
   :disabled "已停用"
   :untested "未测试"
   :unknown "状态未知"})

(def connection-status-labels
  {:connected "已连接"
   :error "连接失败"
   :untested "未测试"
   :disabled "已停用"
   :unknown "状态未知"})

(def ^:private url-pattern
  #"^(?:([a-zA-Z][a-zA-Z0-9+.-]*)://)?(?:[^@/?#]*@)?(\[[^\]]+\]|[^:/?#]+)(?::(\d+))?")

(defn url-parts
  "Split a URL or authority into `{:scheme :host :port :authority}`.

  The host keeps IPv6 brackets.  Missing parts are nil."
  [value]
  (when (and (string? value) (not (str/blank? value)))
    (let [s (str/trim value)
          [_ scheme host port] (re-find url-pattern s)
          host (when (and host (not (str/blank? host))) host)
          port (when (and port (not (str/blank? port))) (str port))]
      {:scheme (some-> scheme str/lower-case)
       :host host
       :port port
       :authority (when host
                    (str host (when port (str ":" port))))})))

(defn authority-from-url
  "Return the actual host authority of URL, or `未知主机`."
  [url]
  (or (:authority (url-parts url)) "未知主机"))

(defn host-from-url
  "Return the hostname of URL without IPv6 brackets, or nil."
  [url]
  (let [host (:host (url-parts url))]
    (when host
      (if (and (str/starts-with? host "[") (str/ends-with? host "]"))
        (subs host 1 (dec (count host)))
        host))))

(defn canonical-base-url
  "Trim whitespace and a trailing slash so a saved URL and a typed URL agree."
  [url]
  (let [s (str/trim (or url ""))]
    (if (and (> (count s) 1) (str/ends-with? s "/"))
      (subs s 0 (dec (count s)))
      s)))

(defn official-host?
  "True when HOST is an official host or a subdomain of one."
  [host official-hosts]
  (let [host (some-> host str/lower-case)
        hosts (keep (fn [h]
                      (let [h (str/lower-case (str h))]
                        (cond
                          (str/blank? h) nil
                          (str/includes? h "://") (host-from-url h)
                          :else h)))
                    official-hosts)]
    (boolean
     (and host
          (some (fn [h]
                  (or (= host h)
                      (str/ends-with? host (str "." h))))
                hosts)))))

(defn- keyword-or
  [value fallback]
  (or (util/normalize-keyword value) fallback))

(defn normalize-key
  "Normalize one auth key.  The secret value is never present in this map."
  [key index]
  (let [status (keyword-or (:status key) :untested)
        rate-limited? (or (:rate-limited? key)
                          (:rate-limited-until key)
                          (contains? #{:cooldown :rate-limited} status))
        auth-failed? (or (:auth-failed? key) (= :auth-failed status))
        enabled? (not (false? (:enabled? key)))
        status (cond
                 (not enabled?) :disabled
                 auth-failed? :auth-failed
                 rate-limited? :cooldown
                 :else status)]
    {:key-id (str (or (:key-id key) (:credential-id key) (:id key)
                      (str "key-" (inc (or index 0)))))
     :alias (or (:alias key) (:name key) (str "密钥 " (inc (or index 0))))
     :enabled? enabled?
     :priority (if (integer? (:priority key)) (:priority key) (inc (or index 0)))
     :has-key? (boolean (or (:has-key? key) (:secret-present? key)
                            (:credential-id key) (:masked-key key)
                            (:secret-ref key)))
     :credential-id (some-> (:credential-id key) str)
     :masked-key (:masked-key key)
     :use-count (or (:use-count key) 0)
     :auth-revision (some-> (or (:auth-revision key) (:revision key)) str)
     :status status
     :rate-limited? (boolean rate-limited?)
     :auth-failed? (boolean auth-failed?)
     :cooldown-until (or (:cooldown-until key) (:rate-limited-until key))
     :rate-limited-until (:rate-limited-until key)
     :last-used-at (:last-used-at key)
     :last-tested-at (:last-tested-at key)}))

(defn client-safe
  "Return a connection copy safe for client state and draft export.

  Raw secrets and opaque secret references never leave the server."
  [connection]
  (-> connection
      (dissoc :api-key :secret :token :password :access-token :refresh-token)
      (update :auth-keys
              (fn [keys] (mapv #(dissoc % :secret :secret-ref) (or keys []))))
      (update :keys
              (fn [keys] (mapv #(dissoc % :secret :secret-ref) (or keys []))))
      (update :credentials
              (fn [credentials]
                (cond
                  (map? credentials)
                  (into {}
                        (map (fn [[id credential]]
                               [id (dissoc credential :secret :secret-ref)]))
                        credentials)

                  (sequential? credentials)
                  (mapv (fn [credential]
                          (if (map? credential)
                            (dissoc credential :secret :secret-ref)
                            credential))
                        credentials)

                  :else credentials)))))

(defn- credential-keys
  "Return credential rows in the draft's auth-key shape.

  The server publishes `:credentials` as a vector.  Older callers and stored
  drafts use a map keyed by credential id.  Both are accepted; ignoring the
  vector collapses a multi-key connection into one synthetic key and can
  lose server key ids on the next save."
  [credentials]
  (cond
    (map? credentials)
    (mapv (fn [[id credential]]
            (assoc credential :key-id (or (:key-id credential) id)))
          credentials)

    (sequential? credentials)
    (mapv (fn [credential]
            (if (map? credential) credential {}))
          credentials)

    :else nil))

(defn normalize-connection
  "Return the display copy of one provider connection.

  `:official?` from the server is not trusted on its own.  The display label
  also needs the actual host to match `:official-hosts`."
  [connection]
  (let [connection-id (str (or (:connection-id connection) (:id connection) ""))
        base-url (canonical-base-url (:base-url connection))
        ;; A draft merges the saved connection with edited fields.  The saved
        ;; `:actual-host` and `:official?` then describe the old address, not
        ;; the URL the operator is editing.  The current `:base-url` is the
        ;; authority whenever it is present.
        actual-host (if (util/present? base-url)
                      (authority-from-url base-url)
                      (if (util/present? (:actual-host connection))
                        (:actual-host connection)
                        "未知主机"))
        hostname (or (host-from-url actual-host)
                     (host-from-url base-url))
        official-claim? (boolean (or (:official-claim? connection)
                                     (:official? connection)))
        official-hosts (vec (:official-hosts connection))
        official? (and official-claim?
                       (seq official-hosts)
                       (official-host? hostname official-hosts))
        raw-kind (keyword-or (:kind connection) nil)
        protocol (or (keyword-or (:protocol connection) nil)
                     (case raw-kind
                       :ollama :ollama
                       :custom :custom
                       :openai-compatible :openai-compatible
                       :openai :openai-compatible
                       :anthropic :anthropic
                       :openai-compatible))
        local-host? (contains? #{"localhost" "127.0.0.1" "::1"} hostname)
        service-kind (or (keyword-or (:service-kind connection) nil)
                         (cond
                           (= :oauth raw-kind) :account-login
                           (= :local-service raw-kind) :local-service
                           (and local-host?
                                (contains? #{:ollama :custom :openai-compatible}
                                           raw-kind)) :local-service
                           :else :cloud-key))
        location (or (keyword-or (:location connection) nil)
                     (keyword-or (get-in connection [:auth :location]) nil)
                     (if (and (= :local-service service-kind) local-host?)
                       :instance-host
                       :unknown))
        status (keyword-or (:status connection)
                           (if (false? (:enabled? connection)) :disabled :untested))
        raw-keys (or (:auth-keys connection)
                     (:keys connection)
                     (credential-keys (:credentials connection)))
        auth-keys (vec (map-indexed (fn [index key]
                                      (normalize-key key index))
                                    raw-keys))
        auth-keys (if (and (empty? auth-keys) (:has-key? connection))
                    [(normalize-key {:key-id "connection-default"
                                     :alias "连接密钥"
                                     :has-key? true
                                     :status :unknown} 0)]
                    auth-keys)
        models (vec (:models connection))
        ;; An explicit empty vector means "all models disabled"; only a
        ;; missing value falls back to the per-model flags.
        enabled-models (if (and (contains? connection :enabled-models)
                                (some? (:enabled-models connection)))
                         (set (map str (or (:enabled-models connection) [])))
                         (set (keep (fn [model]
                                      (when (not (false? (:enabled? model)))
                                        (str (or (:id model) (:model-id model)
                                                 (:name model)))))
                                    models)))
        host-warning (cond
                       (not official-claim?) nil
                       (empty? official-hosts)
                       "服务声明为官方，但未提供官方主机清单；已按未核对处理。"
                       (not official?)
                       (str "实际主机 " actual-host
                            " 与声明的官方地址不符；已按自定义主机显示。")
                       :else nil)]
    (-> connection
        (assoc :connection-id connection-id
               :name (or (:name connection) "未命名连接")
               :base-url base-url
               :actual-host actual-host
               :hostname hostname
               :official? official?
               :official-claim? official-claim?
               :official-hosts official-hosts
               :host-warning host-warning
               :service-kind service-kind
               :protocol protocol
               :location location
               :status status
               :enabled? (not (false? (:enabled? connection)))
               :key-strategy (keyword-or (:key-strategy connection) :priority)
               :test-mode (keyword-or (:test-mode connection) :minimal-request)
               :auth-keys auth-keys
               :models models
               :enabled-models enabled-models
               :manual-models (vec (map str (:manual-models connection)))
               :last-tested-at (:last-tested-at connection)
               :account-label (or (:account-label connection)
                                  (get-in connection [:auth :account-label]))
               :auth-ref (or (:auth-ref connection)
                             (get-in connection [:auth :auth-ref]))
               :auth (or (:auth connection) {})
               :api-key-env (:api-key-env connection)
               :saved-connection (client-safe connection)))))

(defn official-label
  "Return the user-visible provenance label for CONNECTION."
  [connection]
  (cond
    (= :local-service (:service-kind connection)) "本地服务"
    (not (:official-claim? connection)) "自定义主机"
    (empty? (:official-hosts connection)) "声明官方（地址未核对）"
    (:official? connection) "官方"
    :else "自定义主机（与官方地址不符）"))

(defn service-kind-label
  [connection]
  (get service-kind-labels (:service-kind connection) "未知连接路径"))

(defn protocol-label
  [connection]
  (get protocol-labels (:protocol connection) "未知协议"))

(defn location-label
  [connection]
  (get local-location-labels (:location connection) "位置未知"))

(defn location-note
  [connection]
  (get local-location-notes (:location connection)
       "模型服务位置未知；不能声称不出本机。"))

(defn key-strategy-label
  [connection]
  (get key-strategy-labels (:key-strategy connection) "未知策略"))

(defn key-status-label
  [key]
  (get key-status-labels (:status key) "状态未知"))

(defn key-status-detail
  "Return the extra cooldown/failure detail that the single badge omits."
  [key]
  (let [until (:rate-limited-until key)
        cooldown (when (:rate-limited? key)
                   (str "限流冷却中"
                        (when until (str "；下次可尝试 " until))))]
    (cond
      (and (:auth-failed? key) cooldown)
      (str cooldown "；同时存在认证失败")

      cooldown cooldown

      :else nil)))

(defn connection-status-label
  [connection]
  (if (false? (:enabled? connection))
    "已停用"
    (get connection-status-labels (:status connection) "状态未知")))

(defn last-tested-label
  [connection]
  (or (:last-tested-at connection) "未测试"))

(defn connection-source-label
  "Return `name · actual-host`; the host is never omitted."
  [connection]
  (str (:name connection) " · " (:actual-host connection)))

(defn connection-summary
  "Return the fields a connection card needs."
  [connection]
  {:connection-id (:connection-id connection)
   :name (:name connection)
   :source-label (connection-source-label connection)
   :actual-host (:actual-host connection)
   :official-label (official-label connection)
   :official? (:official? connection)
   :host-warning (:host-warning connection)
   :service-kind (:service-kind connection)
   :service-kind-label (service-kind-label connection)
   :protocol-label (protocol-label connection)
   :path-label (service-kind-label connection)
   :location-label (location-label connection)
   :location-note (location-note connection)
   :key-strategy-label (key-strategy-label connection)
   :status (:status connection)
   :status-label (connection-status-label connection)
   :enabled? (:enabled? connection)
   :last-tested-at (:last-tested-at connection)
   :last-tested-label (last-tested-label connection)
   :account-label (:account-label connection)
   :key-count (count (:auth-keys connection))})

(defn config-fields
  "Return the non-secret fields that identify one connection configuration."
  [value]
  {:name (:name value)
   :base-url (canonical-base-url (:base-url value))
   :service-kind (util/normalize-keyword (:service-kind value))
   :protocol (util/normalize-keyword (:protocol value))
   :location (util/normalize-keyword (:location value))
   :account-label (:account-label value)
   :enabled? (not (false? (:enabled? value)))
   :auth-ref (or (:auth-ref value) (get-in value [:auth :auth-ref]))
   :auth (select-keys (or (:auth value) {})
                      [:provider-id :issuer :subject :flow-type :auth-ref
                       :oauth])
   :key-strategy (util/normalize-keyword (:key-strategy value))})

(defn- key-config
  [key]
  {:key-id (:key-id key)
   :alias (:alias key)
   :enabled? (not (false? (:enabled? key)))
   :priority (:priority key)
   :auth-state (or (:auth-state key) :unchanged)
   :auth-edit-id (or (:auth-edit-id key) (:auth-revision key) (:key-id key))})

(defn- declared-capabilities
  "Return only explicit `true`/`false` declarations.

  A missing key means unknown.  This is the shape the server session
  invariant accepts; the catalog's display states (`:known`, `:unsupported`)
  must not cross the wire as if they were capability names."
  [capabilities]
  (cond
    (map? capabilities)
    (into {}
          (keep (fn [[capability value]]
                  (case (util/normalize-keyword value)
                    (:known :declared :supported :yes :true :on)
                    [capability true]
                    (:unsupported :no :false :off :absent)
                    [capability false]
                    nil)))
          capabilities)

    (or (set? capabilities) (sequential? capabilities))
    (into {} (map (fn [capability]
                    [(util/normalize-keyword capability) true]))
          (filter util/normalize-keyword capabilities))

    :else {}))

(defn- model-config
  "Return the stable model facts that a draft can change.

  Volatile snapshot fields such as `:availability` and `:last-seen-at` stay
  out: a provider refresh must not make an untouched draft dirty.  The
  projection includes every model so adding, removing, enabling, renaming,
  or declaring a capability changes the digest."
  [model]
  (let [id (str (or (:model-id model) (:id model) (:name model)))
        context (if (contains? model :context-window)
                  (:context-window model)
                  (or (:context-capacity model) (:capacity model)))
        capacity-source (or (:capacity-source model)
                            (when (map? context) (:source context)))
        max-output (if (contains? model :max-output)
                     (:max-output model)
                     (:max-output-tokens model))]
    {:id id
     :display-name (or (:display-name model) (:name model) id)
     :enabled? (not (false? (:enabled? model)))
     :capabilities (declared-capabilities (:capabilities model))
     :capability-sources (or (:capability-sources model) {})
     :capability-baseline (or (:capability-baseline model) {})
     :context-window context
     :capacity-source capacity-source
     :max-output max-output
     :temperature (:temperature model)
     :top-p (:top-p model)
     :pricing (or (:pricing model) :unknown)
     :reasoning (:reasoning model)}))

(defn config
  "Return the canonical non-secret configuration used by the config digest."
  [{:keys [fields auth-keys keys manual-models enabled-models models]}]
  {:fields (config-fields (or fields {}))
   :keys (vec (sort-by (fn [key] [(:priority key) (:key-id key)])
                       (map key-config (or auth-keys keys))))
   :manual-models (vec (sort (map str (or manual-models []))))
   :enabled-models (vec (sort (map str (or enabled-models []))))
   :models (vec (sort-by :id (map model-config (or models []))))})

(defn config-digest
  [configuration]
  (util/digest {:kind :settings/connection-config
                :config configuration}))

(defn connection-config
  [connection]
  (config {:fields connection
           :auth-keys (:auth-keys connection)
           :manual-models (:manual-models connection)
           :enabled-models (:enabled-models connection)
           :models (:models connection)}))

(defn connection-digest
  [connection]
  (config-digest (connection-config connection)))

(defn draft-digest
  [draft]
  (config-digest (config draft)))

(defn server-kind
  "Map the editable protocol to a provider kind the server can load."
  [fields]
  (case (util/normalize-keyword (:protocol fields))
    :ollama :ollama
    :openai-compatible :openai-compatible
    :custom :custom
    :anthropic :openai-compatible
    :openai-compatible))

(def ^:private server-model-keys
  [:id :model-id :model-name :provider-model-id :name :display-name :full-id
   :capabilities :context-window :max-output-tokens :max-output :temperature
   :top-p :top-k :stop :frequency-penalty :presence-penalty :options
   :degradations :enabled? :removed? :availability :status :pricing :capacity
   :context-capacity :capacity-source :reasoning :alias :notes :last-seen-at
   :source :model-order :capability-sources :capability-baseline])

(defn server-models
  "Return model entries in the server's connection schema.

  Existing model metadata is preserved; the draft's enabled set controls
  `:enabled?`.  Manual ids are appended once.  No catalog-only key is sent."
  [draft]
  (let [existing (vec (:models draft))
        enabled (set (map str (:enabled-models draft)))
        existing-ids (set (map (fn [model]
                                 (str (or (:id model) (:model-id model)
                                          (:name model))))
                               existing))
        existing' (mapv (fn [model]
                          (let [id (str (or (:id model) (:model-id model)
                                            (:name model)))]
                            (assoc (select-keys model server-model-keys)
                                   :id id
                                   :name (or (:name model) (:display-name model) id)
                                   :capabilities
                                   (declared-capabilities (:capabilities model))
                                   :enabled? (contains? enabled id))))
                        existing)
        manual (for [id (:manual-models draft)
                     :let [id (str id)]
                     :when (not (contains? existing-ids id))]
                 {:id id
                  :name id
                  :display-name id
                  :enabled? (contains? enabled id)
                  :source :manual
                  :availability :not-returned
                  :capabilities {}
                  :capability-sources {}
                  :capability-baseline {}})]
    (vec (concat existing' manual))))

(defn sanitize-keys
  "Return auth-key metadata without any secret value."
  [keys]
  (mapv (fn [key]
          (select-keys key [:key-id :alias :enabled? :priority :has-key?
                            :auth-state :auth-edit-id
                            :auth-revision :status :last-tested-at
                            :last-used-at :cooldown-until
                            :credential-id :masked-key :use-count
                            :rate-limited-until]))
        keys))

(def ^:private server-connection-keys
  [:kind :base-url :default-base-url :api-key-env :timeout-ms :max-retries
   :headers :options :capabilities :model-order :service-kind :protocol
   :auth :key-strategy :notes])

(defn server-connection
  "Build the connection value accepted by `hc.server.app.settings`.

  The server treats payload `:fields` as the whole connection.  We send only
  fields its model adapter accepts, keep saved provider metadata, and never
  send an inline secret.  Account and location labels live under `:auth`,
  which the server preserves without interpreting.
  "
  [draft]
  (let [saved (or (:saved-connection draft) {})
        fields (:fields draft)
        saved (if (and (map? saved) (map? (:saved-connection saved)))
                (:saved-connection saved)
                saved)
        keys (:keys draft)
        auth (cond-> (or (:auth saved) {})
               ;; `:location` arrives as a keyword from `draft/edit-field`,
               ;; and `present?` only accepts non-blank strings.  Checking
               ;; `present?` here therefore dropped every location the user
               ;; picked, and the server fell back to guessing from the base
               ;; URL.  `:unknown` is explicitly excluded: it is "not chosen",
               ;; not a claim about where requests go.
               (and (some? (:location fields))
                    (not= :unknown (util/normalize-keyword (:location fields))))
               (assoc :location (util/normalize-keyword (:location fields)))

               (util/present? (:account-label fields))
               (assoc :account-label (:account-label fields))

               (util/present? (:auth-ref fields))
               (assoc :auth-ref (:auth-ref fields))

               (map? (:auth fields))
               (merge (select-keys (:auth fields)
                                   [:provider-id :issuer :subject
                                    :flow-type :auth-ref :oauth])))
        base (merge (select-keys saved server-connection-keys)
                    {:id (or (:connection-id draft) (:draft-id draft))
                     :name (:name fields)
                     :kind (server-kind fields)
                     :base-url (:base-url fields)
                     :enabled? (not (false? (:enabled? fields)))
                     :service-kind (:service-kind fields)
                     :protocol (:protocol fields)
                     :key-strategy (:key-strategy fields)
                     :auth auth
                     :models (server-models draft)
                     :keys (sanitize-keys keys)
                     :has-key? (boolean
                                (or (:has-key? saved)
                                    (some :has-key? keys)
                                    (some #(= :set (:auth-state %)) keys)))})]
    (cond-> (dissoc base :api-key :secret :token :password :credentials)
      (util/present? (:api-key-env fields))
      (assoc :api-key-env (str/trim (:api-key-env fields))))))

(defn draft-payload
  "Return the save/test payload for DRAFT.

  `:fields` is a server-compatible connection.  Secrets are never included;
  a secret edit must be refused before this function is called."
  [draft]
  (let [keys (:keys draft)
        current-ids (set (keep :key-id keys))
        saved-ids (set (keep :key-id
                             (:auth-keys (:saved-connection draft))))
        explicit-clear (->> keys
                            (filter #(= :clear (:auth-state %)))
                            (mapv :key-id))
        removed (->> saved-ids
                     (remove current-ids)
                     (remove (set explicit-clear))
                     vec)]
    {:draft-id (:draft-id draft)
     :connection-id (:connection-id draft)
     :draft-version (:version draft)
     :config-digest (draft-digest draft)
     :fields (server-connection draft)
     :keys (sanitize-keys keys)
     :models (server-models draft)
     :manual-models (vec (sort (map str (:manual-models draft))))
     :enabled-models (vec (sort (map str (:enabled-models draft))))
     :credentials (->> keys
                       (filter #(and (= :set (:auth-state %))
                                     (util/present? (:secret %))))
                       (mapv #(select-keys % [:key-id :alias :enabled?
                                              :priority :secret])))
     :clear-credentials (vec (distinct (concat explicit-clear removed)))}))

(defn redact-payload
  "Return PAYLOAD safe for logs and diagnostics."
  [payload]
  (-> payload
      (update :keys
              (fn [keys]
                (mapv (fn [key]
                        (cond-> key
                          (contains? key :secret) (assoc :secret "***")
                          (contains? key :clear-secret?) (assoc :clear-secret? true)))
                      keys)))
      (update :credentials
              (fn [credentials]
                (mapv (fn [credential]
                        (cond-> credential
                          (contains? credential :secret)
                          (assoc :secret "***")))
                      credentials)))))

(defn draft-validation-errors
  "Return a vector of user-correctable problems.  An empty vector is valid."
  [draft]
  (let [fields (:fields draft)
        keys (:keys draft)
        enabled-keys (filter :enabled? keys)
        key-has-secret? (fn [key]
                          (or (:has-key? key)
                              (and (= :set (:auth-state key))
                                   (not (str/blank? (str (:secret key)))))))
        service-kind (:service-kind fields)
        needs-key? (= :cloud-key service-kind)
        key-errors (when (not= :account-login service-kind)
                     (keep (fn [key]
                             (cond
                               (str/blank? (str (:alias key)))
                               {:field [:keys (:key-id key) :alias]
                                :message "密钥别名不能为空"}

                               (and (= :set (:auth-state key))
                                    (str/blank? (str (:secret key))))
                               {:field [:keys (:key-id key) :secret]
                                :message "新密钥值不能为空；如不修改请保持未改动"}

                               :else nil))
                           keys))
        model-errors
        (keep-indexed
         (fn [index model]
           (let [model-id (str (or (:model-id model) (:id model) (:name model)))
                 display-name (str (or (:display-name model) (:name model)
                                       model-id))
                 context (or (:context-window model)
                             (:context-capacity model)
                             (:capacity model))
                 max-output (or (:max-output model)
                                (:max-output-tokens model))
                 temperature (:temperature model)
                 top-p (:top-p model)
                 pricing (:pricing model)
                 pricing-map (when (map? pricing) pricing)
                 input-price (:input pricing-map)
                 output-price (:output pricing-map)]
             (cond
               (str/blank? model-id)
               {:field [:models index :model-id]
                :message "模型标识不能为空"}

               (str/blank? display-name)
               {:field [:models index :display-name]
                :message "模型显示名不能为空"}

               (and (number? context) (neg? context))
               {:field [:models index :context-window]
                :message "上下文容量不能为负数"}

               (and (number? max-output) (neg? max-output))
               {:field [:models index :max-output]
                :message "最大回复长度不能为负数"}

               (and (number? temperature) (neg? temperature))
               {:field [:models index :temperature]
                :message "温度不能为负数"}

               (and (number? top-p) (or (neg? top-p) (> top-p 1)))
               {:field [:models index :top-p]
                :message "top-p 必须在 0 到 1 之间"}

               (or (and (number? input-price) (neg? input-price))
                   (and (number? output-price) (neg? output-price)))
               {:field [:models index :pricing]
                :message "价格不能为负数"}

               (and (or (number? input-price) (number? output-price))
                    (str/blank? (str (:currency pricing-map))))
               {:field [:models index :pricing]
                :message "填写价格前需要填写币种"}

               :else nil)))
         (:models draft))
        model-ids (map (fn [model]
                         (str (or (:model-id model) (:id model) (:name model))))
                       (:models draft))
        duplicate-model-id?
        (some (fn [[_ count]] (> count 1)) (frequencies model-ids))
        protocol-error (when (contains? #{:anthropic :custom}
                                        (:protocol fields))
                         {:field [:fields :protocol]
                          :message "服务器当前无法通过设置保存该协议；请使用 OpenAI 兼容端点或 Ollama"})]
    (cond-> []
      (str/blank? (str (:name fields)))
      (conj {:field [:fields :name] :message "显示名称不能为空"})

      (str/blank? (str (:base-url fields)))
      (conj {:field [:fields :base-url] :message "基础地址不能为空"})

      (and (not (str/blank? (str (:base-url fields))))
           (nil? (:host (url-parts (:base-url fields)))))
      (conj {:field [:fields :base-url] :message "基础地址缺少可识别的主机"})

      (and (= :local-service service-kind)
           (= :unknown (:location fields)))
      (conj {:field [:fields :location] :message "请选择本地模型服务的位置"})

      (and needs-key? (empty? enabled-keys))
      (conj {:field [:keys] :message "服务密钥连接至少需要一个启用的密钥"})

      (and needs-key? (seq enabled-keys)
           (not-any? key-has-secret? enabled-keys))
      (conj {:field [:keys]
             :message "服务密钥连接至少需要一个已保存或已填写的密钥"})

      duplicate-model-id?
      (conj {:field [:models]
             :message "同一连接内模型标识不能重复；请先合并或改名"})

      protocol-error (conj protocol-error)

      (seq key-errors) (into key-errors)

      (seq model-errors) (into model-errors))))
