(ns hc.server.model-adapter.config
  "Normalize the application's provider/role configuration.

  The application owns connection data and secrets.  This namespace only
  validates the shape, resolves an explicitly named environment variable, and
  turns role assignments into `[connection-id model-id]` pairs.  It builds no
  provider object and performs no network I/O."
  (:require [clojure.string :as str]
            [hc.server.model-adapter.request :as request]))

(def connection-keys
  #{:id :connection-id :kind :base-url :default-base-url :api-key
    :api-key-env :timeout-ms
    :max-retries :headers :models :model :model-name :capabilities :options
    :chat-model :streaming-chat-model :cancel-fn :close-fn
    ;; Application display and lifecycle metadata.  The adapter does not read
    ;; these fields, but they travel with the same configuration value.
    :name :display-name :enabled? :removed? :availability :status :notes
    :source :last-seen-at :models-listed :has-key? :service-kind :protocol
    :auth :key-strategy :keys :credentials :default-model :model-order
    :credential-order :updated-at :custom-base-url :effective-base-url
    :official? :display-host :list-status :last-test :rotation-policy
    :auth-status :auth-expires-at-ms :implicit-retries})

(def model-keys
  #{:id :model-id :model-name :provider-model-id :name :display-name
    :full-id :capabilities :context-window :max-output-tokens :max-output
    :capability-sources :capability-baseline
    :temperature :top-p :top-k :stop :frequency-penalty :presence-penalty
    :options :degradations :chat-model :streaming-chat-model :cancel-fn
    :close-fn :implicit-retries
    ;; Application display and lifecycle metadata.
    :enabled? :removed? :availability :status :pricing :capacity
    :context-capacity :capacity-source :reasoning :alias :notes
    :last-seen-at :source :model-order})

(def config-keys
  #{:connections :roles :default-role :parse-arguments :json-key-mode
    :executor-threads :close-models? :attachment-loader :open-blob
    :credential-resolver})

(defn- invalid!
  [reason message data]
  (throw (ex-info message
                  (merge {:error/kind :invalid-model-adapter-config
                          :reason reason}
                         data))))

(defn- check-keys!
  [m allowed path]
  (when-not (map? m)
    (invalid! :not-a-map "expected a map" {:path path}))
  (when-let [unknown (seq (remove allowed (keys m)))]
    (invalid! :unknown-field
              "configuration contains a field the adapter does not understand"
              {:path path :unknown (vec (sort-by str unknown))})))

(defn- id-str
  [value path]
  (cond
    (string? value) (when-not (str/blank? value) value)
    (keyword? value) (name value)
    (symbol? value) (name value)
    :else nil))

(defn- require-id
  [value path]
  (or (id-str value path)
      (invalid! :invalid-id
                "identifier must be a non-blank string, keyword, or symbol"
                {:path path :value value})))

(defn- positive-int!
  [value path]
  (when-not (and (integer? value) (pos? value))
    (invalid! :invalid-integer
              "value must be a positive integer"
              {:path path :value value}))
  value)

(defn- positive-int-or-nil
  [value]
  (when (and (integer? value) (pos? value)) value))

(defn- number-or-nil
  [value]
  (when (number? value) value))

(defn- resolve-api-key
  [connection path]
  (cond
    (some? (:api-key connection))
    (let [key (:api-key connection)]
      (when-not (string? key)
        (invalid! :invalid-api-key
                  "api-key must be a string"
                  {:path (conj path :api-key)}))
      key)

    (some? (:api-key-env connection))
    (let [env-name (:api-key-env connection)]
      (when-not (and (string? env-name) (not (str/blank? env-name)))
        (invalid! :invalid-api-key-env
                  "api-key-env must name an environment variable"
                  {:path (conj path :api-key-env)}))
      (or (System/getenv env-name)
          (invalid! :api-key-env-missing
                    "the configured environment variable is not set"
                    {:path (conj path :api-key-env)
                     :variable env-name})))

    :else nil))

(defn- capability-key
  [value path]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    (symbol? value) (keyword (name value))
    :else (invalid! :invalid-capability
                    "capability names must be keywords or strings"
                    {:path path :value value})))

(defn- declared-capability?
  [value]
  (let [value (cond
                (string? value) (keyword (str/lower-case value))
                (symbol? value) (keyword (name value))
                :else value)]
    (not (or (nil? value)
             (false? value)
             (contains? #{:unknown :unsupported :no :false :off :absent}
                        value)))))

(defn- normalize-capabilities
  [value path]
  (cond
    (nil? value) #{}
    (set? value) (set (map #(capability-key % path) value))
    (sequential? value) (set (map #(capability-key % path) value))
    (map? value)
    (set
     (keep (fn [[capability declared?]]
             (when (declared-capability? declared?)
               (capability-key capability path)))
           value))
    :else
    (invalid! :invalid-capabilities
              "capabilities must be a set, sequence, or map"
              {:path path :value value})))

(defn- normalize-options
  [value path]
  (cond
    (nil? value) {}
    (map? value) value
    :else (invalid! :invalid-options
                    "options must be a map"
                    {:path path :value value})))

(defn- normalize-degradations
  [value path]
  (cond
    (nil? value) {}
    (map? value) value
    :else (invalid! :invalid-degradations
                    "degradations must be a map"
                    {:path path :value value})))

(defn- normalize-model
  [connection model index kind]
  (let [path [:models index]
        id (require-id (or (:id model) (:model-id model) (:model-name model)
                           (:provider-model-id model) (:name model))
                       (conj path :id))
        model-name (or (:model-name model) (:provider-model-id model)
                       (:model-id model) (:id model) (:name model))
        capabilities (if (contains? model :capabilities)
                       (normalize-capabilities (:capabilities model)
                                               (conj path :capabilities))
                       (normalize-capabilities (:capabilities connection)
                                               [:capabilities]))
        max-output (or (:max-output-tokens model) (:max-output model))
        context-window (:context-window model)
        options (merge (normalize-options (:options connection) [:options])
                       (normalize-options (:options model) (conj path :options)))
        implicit-retries
        (when (= :custom kind)
          (let [value (or (:implicit-retries model)
                          (:implicit-retries connection))]
            (if (= 0 value)
              0
              (invalid! :implicit-retries-unverified
                        "a custom model must declare :implicit-retries 0; the adapter cannot inspect an injected model"
                        {:path (conj path :implicit-retries)
                         :model (:id model)
                         :value value}))))]
    (check-keys! model model-keys path)
    (case kind
      (:openai :openai-compatible)
      (when (or (contains? model :chat-model)
                (contains? model :streaming-chat-model))
        (invalid! :model-transport-unsupported
                  "a known provider builds its own transport; remove injected models"
                  {:path path :model (:id model)}))

      :ollama nil

      :custom
      (doseq [key [:options :max-output-tokens :max-output :temperature :top-p :top-k
                   :stop :frequency-penalty :presence-penalty]]
        (when (contains? model key)
          (invalid! :custom-model-parameter-unsupported
                    "a custom model owns its own parameters; remove adapter provider fields"
                    {:path (conj path key) :model (:id model)}))))
    (when-not (and (string? model-name) (not (str/blank? model-name)))
      (invalid! :invalid-model-name
                "model-name must be a non-blank string"
                {:path (conj path :model-name) :value model-name}))
    (when (and (some? context-window)
               (not (map? context-window))
               (not= :unknown context-window)
               (not (positive-int-or-nil context-window)))
      (invalid! :invalid-context-window
                "context-window must be a positive integer or display metadata"
                {:path (conj path :context-window) :value context-window}))
    (when (and (some? max-output)
               (not= :unknown max-output)
               (not (positive-int-or-nil max-output)))
      (invalid! :invalid-max-output
                "max-output must be a positive integer"
                {:path (conj path :max-output) :value max-output}))
    (doseq [key [:temperature :top-p :frequency-penalty :presence-penalty]]
      (when (contains? model key)
        (when-not (or (number? (get model key))
                      (nil? (get model key))
                      (contains? #{:unknown :default} (get model key)))
          (invalid! :invalid-number
                    "model parameter must be a number"
                    {:path (conj path key) :value (get model key)}))))
    (when (contains? model :top-k)
      (when-not (or (positive-int-or-nil (:top-k model))
                    (nil? (:top-k model))
                    (contains? #{:unknown :default} (:top-k model)))
        (invalid! :invalid-top-k
                  "top-k must be a positive integer"
                  {:path (conj path :top-k) :value (:top-k model)})))
    (when (contains? model :stop)
      (when-not (or (contains? #{:default :unknown} (:stop model))
                    (and (sequential? (:stop model))
                         (every? string? (:stop model))))
        (invalid! :invalid-stop
                  "stop must be a sequence of strings"
                  {:path (conj path :stop)})))
    {:id id
     :model-name model-name
     :capabilities capabilities
     :enabled? (and (not (false? (:enabled? model)))
                    (not (true? (:removed? model))))
     :context-window (positive-int-or-nil context-window)
     :max-output-tokens (positive-int-or-nil max-output)
     :temperature (number-or-nil (:temperature model))
     :top-p (number-or-nil (:top-p model))
     :top-k (positive-int-or-nil (:top-k model))
     :stop (when (sequential? (:stop model)) (vec (:stop model)))
     :frequency-penalty (number-or-nil (:frequency-penalty model))
     :presence-penalty (number-or-nil (:presence-penalty model))
     :options options
     :degradations (normalize-degradations (:degradations model)
                                           (conj path :degradations))
     :chat-model (or (:chat-model model) (:chat-model connection))
     :streaming-chat-model (or (:streaming-chat-model model)
                               (:streaming-chat-model connection))
     :cancel-fn (or (:cancel-fn model) (:cancel-fn connection))
     :close-fn (or (:close-fn model) (:close-fn connection))
     :implicit-retries implicit-retries}))

(defn- model-seq
  [connection path]
  (let [models (:models connection)]
    (cond
      (nil? models)
      (let [name (or (:model-name connection) (:model connection))]
        (if (nil? name)
          (invalid! :model-missing
                    "a connection needs :models or :model/:model-name"
                    {:path path})
          [{:id name :model-name name}]))

      (map? models)
      (mapv (fn [[id model]]
              (when-not (map? model)
                (invalid! :model-invalid
                          "each model must be a map"
                          {:path (conj path :models) :id id}))
              (assoc model :id id))
            models)

      (sequential? models) (vec models)

      :else
      (invalid! :models-invalid
                "models must be a map or a sequence"
                {:path (conj path :models) :value models}))))

(defn- normalize-connection
  [connection]
  (check-keys! connection connection-keys [])
  (let [id (require-id (or (:id connection) (:connection-id connection))
                       [:id])
        kind (keyword (or (:kind connection) (:protocol connection) :openai))
        kind (case kind
               :openai-compatible :openai-compatible
               :preset :openai-compatible
               (:oauth :account-login) :openai-compatible
               :openai :openai
               :ollama :ollama
               :custom :custom
               (invalid! :unknown-provider-kind
                         "unknown provider kind"
                         {:connection id :kind kind}))
        path [:connections id]
        models (model-seq connection path)
        models (map-indexed (fn [index model]
                              (normalize-model connection model index kind))
                            models)
        model-map (into {}
                        (map (fn [model] [(:id model) model]))
                        models)]
    (when (and (not= :custom kind)
               (or (contains? connection :chat-model)
                   (contains? connection :streaming-chat-model)))
      (invalid! :connection-transport-unsupported
                "known providers build their own transport; remove connection-level injected models"
                {:connection id
                 :fields (vec (filter #(contains? connection %)
                                      [:chat-model :streaming-chat-model]))}))
    (when-not (= (count models) (count model-map))
      (invalid! :duplicate-model-id
                "model ids must be unique within one connection"
                {:connection id}))
    (when (contains? connection :max-retries)
      (when-not (or (nil? (:max-retries connection))
                    (contains? #{:default :unknown} (:max-retries connection))
                    (and (integer? (:max-retries connection))
                         (zero? (:max-retries connection))))
        (invalid! :implicit-retries-enabled
                  "max-retries must be absent or 0; the adapter owns retry policy"
                  {:connection id :max-retries (:max-retries connection)})))
    (when (contains? connection :timeout-ms)
      (positive-int! (:timeout-ms connection)
                     (conj path :timeout-ms)))
    (when (contains? connection :headers)
      (when-not (and (map? (:headers connection))
                     (every? string? (keys (:headers connection)))
                     (every? string? (vals (:headers connection))))
        (invalid! :invalid-headers
                  "headers must map strings to strings"
                  {:connection id})))
    (when (and (= :openai-compatible kind)
               (not (and (string? (or (:base-url connection)
                                     (:default-base-url connection)
                                     (:effective-base-url connection)
                                     (:custom-base-url connection)))
                         (not (str/blank? (or (:base-url connection)
                                             (:default-base-url connection)
                                             (:effective-base-url connection)
                                             (:custom-base-url connection)))))))
      (invalid! :base-url-required
                "an OpenAI-compatible connection needs a non-blank base-url"
                {:connection id}))
    (when (= :custom kind)
      (doseq [key [:base-url :api-key :api-key-env :timeout-ms :headers
                   :options]]
        (when (contains? connection key)
          (invalid! :custom-connection-field-unsupported
                    "a custom connection owns its own transport; remove provider fields"
                    {:connection id :field key})))
      (doseq [model models]
        (when (and (nil? (:chat-model model))
                   (nil? (:streaming-chat-model model)))
          (invalid! :custom-model-missing
                    "a custom connection/model needs :chat-model or :streaming-chat-model"
                    {:connection id :model (:id model)}))))
    (when (and (= :ollama kind)
               (or (contains? connection :api-key)
                   (contains? connection :api-key-env)))
      (invalid! :api-key-unsupported
                "native Ollama does not use an API key"
                {:connection id}))
    {:id id
     :kind kind
     :enabled? (and (not (false? (:enabled? connection)))
                    (not (true? (:removed? connection))))
     :base-url (or (:base-url connection)
                   (:default-base-url connection)
                   (:effective-base-url connection)
                   (:custom-base-url connection))
     :api-key (resolve-api-key connection path)
     :timeout-ms (:timeout-ms connection)
     :max-retries 0
     :headers (:headers connection)
     :credentials (:credentials connection)
     :keys (:keys connection)
     :key-strategy (:key-strategy connection)
     :has-multi-credentials? (boolean
                              (or (seq (:credentials connection))
                                  (seq (:keys connection))))
     :models model-map
     :options (normalize-options (:options connection) (conj path :options))}))

(defn- normalize-connections
  [connections]
  (let [connections (cond
                      (nil? connections) []
                      (map? connections)
                      (mapv (fn [[id connection]]
                              (when-not (map? connection)
                                (invalid! :connection-invalid
                                          "each connection must be a map"
                                          {:connection id}))
                              (assoc connection :id id))
                            connections)
                      (sequential? connections) (vec connections)
                      :else
                      (invalid! :connections-invalid
                                "connections must be a map or a sequence"
                                {:value connections}))
        normalized (mapv normalize-connection connections)
        by-id (into {} (map (fn [connection] [(:id connection) connection]))
                    normalized)]
    (when-not (= (count normalized) (count by-id))
      (invalid! :duplicate-connection-id
                "connection ids must be unique"
                {:connections (mapv :id normalized)}))
    by-id))

(defn- single-model
  [connection]
  (let [models (vals (:models connection))]
    (when (= 1 (count models))
      (first models))))

(defn- normalize-role-target
  [connections role value]
  (let [target (cond
                 (map? value)
                 (let [_ (when-let [unknown (seq (remove #{:connection
                                                           :connection-id
                                                           :model :model-id
                                                           :mode :confirmed?
                                                           :enabled?}
                                                         (keys value)))]
                           (invalid! :unknown-role-field
                                     "role binding contains an unknown field"
                                     {:role role
                                      :unknown (vec (sort-by str unknown))}))
                       connection-id (require-id (or (:connection value)
                                                     (:connection-id value))
                                                 [:roles role :connection])
                       connection (or (get connections connection-id)
                                      (invalid! :unknown-connection
                                                "role names an unknown connection"
                                                {:role role
                                                 :connection connection-id}))
                       _ (when (or (false? (:enabled? value))
                                   (false? (:enabled? connection)))
                           (invalid! :role-disabled
                                     "role names a disabled connection"
                                     {:role role
                                      :connection connection-id}))
                       model-id (require-id (or (:model value) (:model-id value))
                                            [:roles role :model])
                       model (or (get-in connection [:models model-id])
                                 (invalid! :unknown-model
                                           "role names an unknown model"
                                           {:role role
                                            :connection connection-id
                                            :model model-id}))
                       _ (when (false? (:enabled? model))
                           (invalid! :role-disabled
                                     "role names a disabled model"
                                     {:role role
                                      :connection connection-id
                                      :model model-id}))]
                   {:connection connection-id :model (:id model)})

                 (or (string? value) (keyword? value) (symbol? value))
                 (let [connection-id (require-id value [:roles role])
                       connection (or (get connections connection-id)
                                      (invalid! :unknown-connection
                                                "role names an unknown connection"
                                                {:role role
                                                 :connection connection-id}))
                       _ (when (false? (:enabled? connection))
                           (invalid! :role-disabled
                                     "role names a disabled connection"
                                     {:role role
                                      :connection connection-id}))
                       model (or (single-model connection)
                                 (invalid! :ambiguous-role-model
                                           "role names a connection with multiple models; name :model"
                                           {:role role
                                            :connection connection-id
                                            :models (vec (sort (keys (:models connection))))}))
                       _ (when (false? (:enabled? model))
                           (invalid! :role-disabled
                                     "role names a disabled model"
                                     {:role role
                                      :connection connection-id
                                      :model (:id model)}))]
                   {:connection connection-id :model (:id model)})

                 :else
                 (invalid! :invalid-role-target
                           "role target must be a connection id or {:connection :model}"
                           {:role role :value value}))]
    target))

(defn- normalize-roles
  [connections roles default-role]
  (let [roles (cond
                (nil? roles)
                (let [single (when (= 1 (count connections))
                               (first (vals connections)))
                      model (when single (single-model single))]
                  (if model
                    (if (and (not (false? (:enabled? single)))
                             (not (false? (:enabled? model))))
                      {:main {:connection (:id single) :model (:id model)}
                       :task {:connection (:id single) :model (:id model)}}
                      (invalid! :role-disabled
                                "the only configured connection or model is disabled"
                                {:connection (:id single)
                                 :model (:id model)}))
                    (invalid! :roles-missing
                              "configuration needs :roles when it has more than one model"
                              {:connections (vec (sort (keys connections)))})))
                (map? roles) roles
                :else
                (invalid! :roles-invalid
                          "roles must be a map"
                          {:value roles}))
        roles (into {}
                    (comp (filter (fn [[role _]]
                                    (contains? #{:main :task}
                                               (keyword role))))
                          (map (fn [[role value]]
                                 [(keyword role)
                                  (normalize-role-target connections
                                                         (keyword role)
                                                         value)])))
                    roles)]
    (when (empty? roles)
      (invalid! :roles-empty "configuration needs at least one role" {}))
    (let [default-role (cond
                         (some? default-role) (keyword default-role)
                         (= 1 (count roles)) (first (keys roles))
                         :else nil)
          _ (when (and (some? default-role)
                       (not (contains? roles default-role)))
              (invalid! :unknown-default-role
                        "default-role must name a configured role"
                        {:default-role default-role
                         :roles (vec (sort-by str (keys roles)))}))]
      {:roles roles :default-role default-role})))

(defn normalize-config
  "Validate one application configuration and return a normalized value.

  The result contains plain data only.  Provider construction happens later in
  `hc.server.model-adapter.providers`."
  [config]
  (check-keys! config config-keys [])
  (let [connections (normalize-connections (:connections config))
        _ (when (empty? connections)
            (invalid! :connections-empty
                      "configuration needs at least one provider connection"
                      {}))
        {:keys [roles default-role]} (normalize-roles connections
                                                     (:roles config)
                                                     (:default-role config))
        parse-mode (or (:parse-arguments config) :json)
        json-key-mode (or (:json-key-mode config) :keyword)
        attachment-loader (or (:attachment-loader config) (:open-blob config))
        credential-resolver (:credential-resolver config)
        executor-threads (or (:executor-threads config)
                             (max 2 (min 8 (.. Runtime getRuntime
                                               availableProcessors))))]
    (when-not (or (contains? #{:json :ufss :auto :edn} parse-mode)
                  (fn? parse-mode))
      (invalid! :invalid-parse-arguments
                "parse-arguments must be :json, :ufss, :auto, :edn, or a function"
                {:value parse-mode}))
    (when-not (contains? #{:keyword :string} json-key-mode)
      (when-not (fn? json-key-mode)
        (invalid! :invalid-json-key-mode
                  "json-key-mode must be :keyword, :string, or a function"
                  {:value json-key-mode})))
    (positive-int! executor-threads [:executor-threads])
    (when (and (some? attachment-loader) (not (fn? attachment-loader)))
      (invalid! :invalid-attachment-loader
                "attachment-loader must be a function from attachment to bytes/base64/url"
                {:value attachment-loader}))
    (when (and (some? credential-resolver) (not (fn? credential-resolver)))
      (invalid! :invalid-credential-resolver
                "credential-resolver must be a function"
                {:value credential-resolver}))
    {:connections connections
     :roles roles
     :default-role default-role
     :parse-arguments (request/parse-arguments parse-mode json-key-mode)
     :attachment-loader attachment-loader
     :credential-resolver credential-resolver
     :executor-threads executor-threads
     :close-models? (boolean (:close-models? config))}))

(defn model-configured?
  "True when the configuration structurally names a provider target.

  Structural only: a missing provider module or an unreachable endpoint is not
  a configuration absence, and an invalid role/model is not silently reported
  as absence.  `create-model-port` owns validation and reports the exact
  failure."
  [config]
  (let [connections (:connections config)
        connections (cond
                      (map? connections) (vals connections)
                      (sequential? connections) connections
                      :else [])
        roles (:roles config)]
    (boolean
     (and (map? config)
          (seq connections)
          (or (seq roles)
              (and (= 1 (count connections))
                   (let [models (:models (first connections))
                         models (cond
                                  (map? models) (vals models)
                                  (sequential? models) models
                                  :else [])]
                     (= 1 (count models)))))))))

(defn role-profile-key
  "Resolve one role to its `[connection-id model-id]` pair."
  [normalized role]
  (let [role (keyword role)]
    (or (get-in normalized [:roles role])
        (invalid! :unknown-role
                  "snapshot names an unknown model role"
                  {:role role
                   :roles (vec (sort-by str (keys (:roles normalized))))}))))
