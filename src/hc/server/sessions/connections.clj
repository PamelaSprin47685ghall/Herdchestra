(ns hc.server.sessions.connections
  "Provider connections, credentials, and model entries.

  Authority: AGENTS.md §7.4 旅程 1.2–1.3（引导与选模型） (multi-key connections, truthful host
  display, capability/availability separation, same-name models, refresh
  semantics) and §5.4 (model identity is not a display name).

  A connection is configuration, not a live client.  It never holds a raw
  secret: `:secret-ref` is an opaque pointer resolved by the assembly layer,
  and `:masked-key` is the only key material that may be displayed or logged.

  A model entry is keyed by a stable project ID, never by display name.
  Availability is a separate field from capability.  `:unknown` capability
  and `:unknown` pricing are honest values, never `false` or zero."
  (:require [clojure.string :as str]
            [hc.server.sessions.ids :as ids]))

(def connection-kinds
  #{:preset :custom :oauth})

(def rotation-policies
  #{:priority :round-robin :least-used :random})

(def capability-values
  #{true false :unknown})

(def model-sources
  #{:listed :manual})

(def model-availabilities
  #{:available :not-returned :unconfirmed :disabled
    :connection-disabled :connection-removed :auth-expired :model-removed})

(defn- error
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- secret-key?
  [key]
  (and (or (keyword? key) (string? key) (symbol? key))
       (contains? #{"secret" "api-key" "api_key" "key" "token" "password"
                    "authorization" "auth"}
                  (name key))))

(defn secret-field?
  "True when a map tries to carry raw key material into persistent state."
  [value]
  (and (map? value) (some secret-key? (keys value))))

(defn- redact-secret-fields
  "Drop raw secret fields from an error or test result before it enters
  persistent state."
  [value]
  (if (map? value)
    (apply dissoc value (filter secret-key? (keys value)))
    value))

(defn- parse-uri
  [value]
  (when (non-blank-string? value)
    (try
      (java.net.URI. value)
      (catch Exception _ nil))))

(defn valid-base-url?
  "True for an absolute http(s) URL with a host."
  [value]
  (let [uri (parse-uri value)]
    (and uri
         (contains? #{"http" "https"} (.getScheme uri))
         (non-blank-string? (.getHost uri)))))

(defn connection-view
  "Derive the truthful display fields of a connection.

  `:official?` is true only for a preset connection whose host was not
  replaced.  Once a custom base URL exists, the effective host is shown and
  the connection is no longer official."
  [connection]
  (let [effective (or (:custom-base-url connection)
                      (:default-base-url connection))
        official? (and (= :preset (:kind connection))
                       (nil? (:custom-base-url connection)))
        host (some-> (parse-uri effective) .getHost)]
    (assoc connection
           :effective-base-url effective
           :official? (boolean official?)
           :display-host host)))

(defn new-connection
  [{:keys [connection-id name kind default-base-url custom-base-url
           enabled? rotation-policy now]}]
  (let [kind (or kind :preset)
        rotation-policy (or rotation-policy :priority)]
    (cond
      (not (ids/valid-id? connection-id))
      (error :CONNECTION_ID_INVALID
             "connection-id must be a non-blank string"
             {:connection-id connection-id})

      (not (non-blank-string? name))
      (error :CONNECTION_NAME_REQUIRED
             "connection name must be a non-blank string"
             {:name name})

      (not (contains? connection-kinds kind))
      (error :CONNECTION_KIND_INVALID
             "connection kind must be :preset, :custom, or :oauth"
             {:kind kind})

      (not (contains? rotation-policies rotation-policy))
      (error :ROTATION_POLICY_INVALID
             "rotation policy must be :priority, :round-robin, :least-used, or :random"
             {:rotation-policy rotation-policy})

      (and (not= kind :oauth)
           (not (valid-base-url? (or custom-base-url default-base-url))))
      (error :INVALID_BASE_URL
             "connection needs an absolute http(s) base URL"
             {:base-url (or custom-base-url default-base-url)})

      (and (some? default-base-url) (not (valid-base-url? default-base-url)))
      (error :INVALID_BASE_URL
             "default-base-url must be an absolute http(s) URL"
             {:base-url default-base-url})

      (and (some? custom-base-url) (not (valid-base-url? custom-base-url)))
      (error :INVALID_BASE_URL
             "custom-base-url must be an absolute http(s) URL"
             {:base-url custom-base-url})

      :else
      {:ok? true
       :connection
       (connection-view
        {:connection-id connection-id
         :name name
         :kind kind
         :default-base-url default-base-url
         :custom-base-url custom-base-url
         :enabled? (if (nil? enabled?) true (boolean enabled?))
         :removed? false
         :auth-status :unknown
         :rotation-policy rotation-policy
         :credentials {}
         :credential-order []
         :models {}
         :model-order []
         :list-status {:state :unknown :at nil :reason nil}
         :last-test nil
         :created-at now
         :updated-at now})})))

(defn set-custom-base-url
  [connection value now]
  (let [value (if (and (string? value) (str/blank? value)) nil value)]
    (if (and (some? value) (not (valid-base-url? value)))
      (error :INVALID_BASE_URL
             "custom-base-url must be an absolute http(s) URL"
             {:base-url value})
      {:ok? true
       :connection (-> connection
                       (assoc :custom-base-url value :updated-at now)
                       connection-view)})))

(defn set-connection-enabled
  [connection enabled? now]
  {:ok? true
   :connection (assoc connection
                      :enabled? (boolean enabled?)
                      :updated-at now)})

(def auth-statuses
  #{:unknown :ok :failed})

(defn set-auth-status
  "Record the connection-level authentication state.

  A single credential failure does not disable the whole connection; this
  function is for an explicit connection test or an OAuth token failure."
  [connection status now]
  (if-not (contains? auth-statuses status)
    (error :AUTH_STATUS_INVALID
           "auth status must be :unknown, :ok, or :failed"
           {:auth-status status})
    {:ok? true
     :connection (assoc connection
                        :auth-status status
                        :updated-at now)}))

(defn request-connection-test
  "Mark one connection test as pending.  The caller performs the I/O."
  [connection now]
  {:ok? true
   :connection (assoc connection
                      :last-test {:status :pending
                                  :requested-at now}
                      :updated-at now)})

(defn record-test-result
  "Record the result of a connection test.

  A failed test is visible but does not silently disable the connection."
  [connection result now]
  (let [status (:status result)]
    (if-not (contains? #{:ok :failed} status)
      (error :TEST_RESULT_INVALID
             "connection test result must be :ok or :failed"
             {:result result})
      {:ok? true
       :connection (assoc connection
                          :last-test (merge (redact-secret-fields result) {:status status :at now})
                          :updated-at now)})))

(declare public-credential public-model)

(defn public-connection
  "A display-safe connection view.  Credentials are masked and models keep
  capability and availability as separate fields."
  [connection]
  (let [view (connection-view connection)]
    (-> (select-keys view
                     [:connection-id :name :kind :effective-base-url
                      :official? :display-host :enabled? :removed?
                      :auth-status :rotation-policy :list-status :last-test
                      :created-at :updated-at])
        (assoc :credentials
               (mapv public-credential
                     (keep #(get-in connection [:credentials %])
                           (:credential-order connection))))
        (assoc :models
               (mapv #(public-model connection %)
                     (keep #(get-in connection [:models %])
                           (:model-order connection)))))))

;; ---------------------------------------------------------------------------
;; Credentials

(defn public-credential
  "A display-safe credential view.  The secret pointer stays internal."
  [credential]
  (select-keys credential
               [:credential-id :alias :enabled? :masked-key :priority
                :use-count :rate-limited-until :auth-failed?
                :last-auth-error :last-used-at]))

(defn credential-eligible?
  [credential now]
  (and (:enabled? credential)
       (not (:auth-failed? credential))
       (<= (or (:rate-limited-until credential) 0) (or now 0))))

(defn add-credential
  [connection credential now]
  (let [credential-id (:credential-id credential)
        secret-ref (:secret-ref credential)
        masked-key (:masked-key credential)]
    (cond
      (secret-field? credential)
      (error :SECRET_IN_STATE
             "raw secret fields are forbidden; store a secret-ref and a masked key"
             {:fields (vec (filter secret-key? (keys credential)))})

      (not (ids/valid-id? credential-id))
      (error :CREDENTIAL_ID_INVALID
             "credential-id must be a non-blank string"
             {:credential-id credential-id})

      (contains? (:credentials connection) credential-id)
      (error :CREDENTIAL_ID_TAKEN
             "credential-id already exists on this connection"
             {:credential-id credential-id})

      (not (non-blank-string? secret-ref))
      (error :CREDENTIAL_SECRET_REF_REQUIRED
             "credential needs an opaque non-blank secret-ref"
             {:credential-id credential-id})

      (not (non-blank-string? masked-key))
      (error :CREDENTIAL_MASKED_KEY_REQUIRED
             "credential needs a non-blank masked-key for display"
             {:credential-id credential-id})

      (not (or (nil? (:priority credential))
               (integer? (:priority credential))))
      (error :CREDENTIAL_PRIORITY_INVALID
             "credential priority must be an integer"
             {:priority (:priority credential)})

      :else
      (let [credential
            {:credential-id credential-id
             :alias (or (:alias credential) credential-id)
             :enabled? (if (nil? (:enabled? credential))
                         true
                         (boolean (:enabled? credential)))
             :secret-ref secret-ref
             :masked-key masked-key
             :priority (or (:priority credential) 0)
             :use-count 0
             :rate-limited-until 0
             :auth-failed? false
             :last-auth-error nil
             :last-used-at nil
             :created-at now
             :updated-at now}]
        {:ok? true
         :connection (-> connection
                         (assoc-in [:credentials credential-id] credential)
                         (update :credential-order
                                 (fnil conj [])
                                 credential-id)
                         (assoc :updated-at now))}))))

(def credential-update-fields
  #{:alias :enabled? :priority :masked-key :secret-ref})

(declare update-credential)

(defn mask-secret
  "Return a display-safe mask.  Short secrets are fully masked; no path
  returns the complete value."
  [secret]
  (if-not (string? secret)
    "****"
    (let [value (str/trim secret)
          n (count value)]
      (cond
        (zero? n) "****"
        (<= n 4) "****"
        (<= n 8) (str (subs value 0 2) "..." (subs value (- n 2)))
        :else (str (subs value 0 3) "..." (subs value (- n 4)))))))

(defn credential-from-secret
  "Build one credential record from a raw secret and its store pointer.

  The result never contains the raw secret.  The caller stores it through a
  `SecretStore` first and passes the returned `:secret-ref`."
  [{:keys [credential-id alias secret secret-ref enabled? priority]}]
  (cond
    (not (ids/valid-id? credential-id))
    (error :CREDENTIAL_ID_INVALID
           "credential-id must be a non-blank string"
           {:credential-id credential-id})

    (not (non-blank-string? secret))
    (error :CREDENTIAL_SECRET_REQUIRED
           "a raw secret is required to create a credential"
           {:credential-id credential-id})

    (not (non-blank-string? secret-ref))
    (error :CREDENTIAL_SECRET_REF_REQUIRED
           "credential needs an opaque non-blank secret-ref"
           {:credential-id credential-id})

    :else
    {:ok? true
     :credential {:credential-id credential-id
                  :alias (or alias credential-id)
                  :enabled? (if (nil? enabled?) true (boolean enabled?))
                  :secret-ref secret-ref
                  :masked-key (mask-secret secret)
                  :priority (or priority 0)}}))

(defn add-credential-from-secret
  "Add a credential after the caller has stored the raw secret.

  The connection state keeps only `:secret-ref` and `:masked-key`."
  [connection input now]
  (let [prepared (credential-from-secret input)]
    (if-not (:ok? prepared)
      prepared
      (add-credential connection (:credential prepared) now))))

(defn rotate-credential-secret
  "Replace a credential's secret pointer and mask without exposing the raw
  value.  Auth-failure and rate-limit state reset because the material
  changed."
  [connection credential-id secret secret-ref now]
  (if-not (non-blank-string? secret)
    (error :CREDENTIAL_SECRET_REQUIRED
           "a raw secret is required to rotate a credential"
           {:credential-id credential-id})
    (update-credential connection credential-id
                       {:secret-ref secret-ref
                        :masked-key (mask-secret secret)}
                       now)))

(defn remove-credential
  [connection credential-id now]
  (if-not (contains? (:credentials connection) credential-id)
    (error :CREDENTIAL_NOT_FOUND
           "credential does not exist on this connection"
           {:credential-id credential-id})
    {:ok? true
     :connection (-> connection
                     (update :credentials dissoc credential-id)
                     (update :credential-order
                             (fn [order] (vec (remove #{credential-id} order))))
                     (assoc :updated-at now))}))

(defn update-credential
  [connection credential-id changes now]
  (let [changes (or changes {})
        credential (get-in connection [:credentials credential-id])]
    (cond
      (nil? credential)
      (error :CREDENTIAL_NOT_FOUND
             "credential does not exist on this connection"
             {:credential-id credential-id})

      (secret-field? changes)
      (error :SECRET_IN_STATE
             "raw secret fields are forbidden; update the secret-ref instead"
             {:fields (vec (filter secret-key? (keys changes)))})

      (seq (remove credential-update-fields (keys changes)))
      (error :CREDENTIAL_FIELD_UNKNOWN
             "credential update contains unsupported fields"
             {:fields (vec (remove credential-update-fields (keys changes)))})

      (and (contains? changes :masked-key)
           (not (non-blank-string? (:masked-key changes))))
      (error :CREDENTIAL_MASKED_KEY_REQUIRED
             "masked-key must be a non-blank string"
             {:credential-id credential-id})

      (and (contains? changes :secret-ref)
           (not (non-blank-string? (:secret-ref changes))))
      (error :CREDENTIAL_SECRET_REF_REQUIRED
             "secret-ref must be a non-blank string"
             {:credential-id credential-id})

      (and (contains? changes :alias)
           (not (or (nil? (:alias changes))
                    (string? (:alias changes)))))
      (error :CREDENTIAL_ALIAS_INVALID
             "credential alias must be a string or nil"
             {:alias (:alias changes)})

      (and (contains? changes :enabled?)
           (not (boolean? (:enabled? changes))))
      (error :CREDENTIAL_ENABLED_INVALID
             "credential enabled? must be a boolean"
             {:enabled? (:enabled? changes)})

      (and (contains? changes :priority)
           (not (integer? (:priority changes))))
      (error :CREDENTIAL_PRIORITY_INVALID
             "credential priority must be an integer"
             {:priority (:priority changes)})

      :else
      (let [secret-changed? (and (contains? changes :secret-ref)
                                 (not= (:secret-ref changes)
                                       (:secret-ref credential)))
            credential' (-> credential
                            (merge (select-keys changes
                                                [:alias :enabled? :priority
                                                 :masked-key :secret-ref]))
                            (cond-> secret-changed?
                              (assoc :auth-failed? false
                                     :last-auth-error nil
                                     :rate-limited-until 0))
                            (assoc :updated-at now))]
        {:ok? true
         :connection (-> connection
                         (assoc-in [:credentials credential-id] credential')
                         (assoc :updated-at now))}))))

(defn set-credential-enabled
  [connection credential-id enabled? now]
  (update-credential connection credential-id {:enabled? (boolean enabled?)} now))

(defn record-auth-failure
  [connection credential-id failure now]
  (if-not (contains? (:credentials connection) credential-id)
    (error :CREDENTIAL_NOT_FOUND
           "credential does not exist on this connection"
           {:credential-id credential-id})
    {:ok? true
     :connection
     (-> connection
         (assoc-in [:credentials credential-id :auth-failed?] true)
         (assoc-in [:credentials credential-id :last-auth-error]
                   (merge {:at now} (redact-secret-fields failure)))
         (assoc-in [:credentials credential-id :updated-at] now)
         (assoc :updated-at now))}))

(defn record-auth-success
  [connection credential-id now]
  (if-not (contains? (:credentials connection) credential-id)
    (error :CREDENTIAL_NOT_FOUND
           "credential does not exist on this connection"
           {:credential-id credential-id})
    {:ok? true
     :connection
     (-> connection
         (assoc-in [:credentials credential-id :auth-failed?] false)
         (assoc-in [:credentials credential-id :last-auth-error] nil)
         (assoc-in [:credentials credential-id :rate-limited-until] 0)
         (assoc-in [:credentials credential-id :updated-at] now)
         (assoc :updated-at now))}))

(defn record-rate-limit
  [connection credential-id retry-at now]
  (cond
    (not (contains? (:credentials connection) credential-id))
    (error :CREDENTIAL_NOT_FOUND
           "credential does not exist on this connection"
           {:credential-id credential-id})

    (not (and (integer? retry-at) (not (neg? retry-at))))
    (error :RATE_LIMIT_UNTIL_INVALID
           "rate-limited-until must be a non-negative integer"
           {:retry-at retry-at})

    :else
    {:ok? true
     :connection
     (-> connection
         (assoc-in [:credentials credential-id :rate-limited-until] retry-at)
         (assoc-in [:credentials credential-id :updated-at] now)
         (assoc :updated-at now))}))

(defn choose-credential
  "Choose one eligible credential for a request.

  Auth failures and rate-limit cooldowns are separate exclusions.  A rate
  limited key becomes eligible again when its cooldown passes; an auth-failed
  key stays excluded until a new secret-ref or an explicit success."
  ([connection now]
   (choose-credential connection now {}))
  ([connection now {:keys [random-int]}]
   (let [eligible (->> (:credential-order connection)
                       (keep #(get-in connection [:credentials %]))
                       (filter #(credential-eligible? % now))
                       vec)
         policy (or (:rotation-policy connection)
                    (:key-strategy connection)
                    :priority)
         last-used (fn [credential] (or (:last-used-at credential) 0))
         chosen
         (case policy
           :priority
           (first (sort-by (juxt :priority :use-count :credential-id) eligible))

           :least-used
           (first (sort-by (juxt :use-count :priority :credential-id) eligible))

           :round-robin
           (first (sort-by (juxt :use-count last-used :credential-id) eligible))

           :random
           (when (seq eligible)
             (let [count (count eligible)
                   raw (if random-int
                         (long (random-int count))
                         (long (hash [now count])))
                   index (mod raw count)]
               (nth eligible index)))

           nil)]
     (if-not chosen
       (error :NO_ELIGIBLE_CREDENTIAL
              "no credential is currently eligible"
              {:connection-id (or (:connection-id connection)
                                  (:id connection))
               :policy policy
               :at now})
       (let [credential (-> chosen
                            (update :use-count (fnil inc 0))
                            (assoc :last-used-at now))
             connection' (-> connection
                             (assoc-in [:credentials (:credential-id credential)]
                                       credential)
                             (assoc :updated-at now))]
         {:ok? true
          :connection connection'
          :credential credential
          :public (public-credential credential)})))))

;; ---------------------------------------------------------------------------
;; Models

(defn- normalize-capability-value
  [value]
  (case value
    true true
    false false
    :unknown :unknown
    "true" true
    "false" false
    "unknown" :unknown
    nil :unknown
    :unknown))

(defn normalize-capabilities
  [capabilities]
  (into {}
        (map (fn [[key value]]
               [(keyword key) (normalize-capability-value value)]))
        (or capabilities {})))

(defn valid-capabilities?
  [capabilities]
  (and (map? capabilities)
       (every? (fn [[key value]]
                 (and (keyword? key)
                      (contains? capability-values value)))
               capabilities)))

(defn normalize-pricing
  [pricing]
  (cond
    (nil? pricing) {:status :unknown}
    (not (map? pricing)) {:status :unknown}
    (= :known (:status pricing))
    (if (and (number? (:input pricing))
             (number? (:output pricing))
             (non-blank-string? (:currency pricing)))
      (select-keys pricing [:status :input :output :currency :source])
      {:status :unknown})
    :else {:status :unknown}))

(defn valid-pricing?
  [pricing]
  (or (= :unknown (:status pricing))
      (and (= :known (:status pricing))
           (number? (:input pricing))
           (number? (:output pricing))
           (non-blank-string? (:currency pricing)))))

(defn confirmed-capabilities
  "The capability set the provider has confirmed true.  Unknown is not true."
  [model]
  (into #{}
        (keep (fn [[key value]] (when (true? value) key)))
        (:capabilities model)))

(defn model-capability
  [model capability]
  (get-in model [:capabilities capability] :unknown))

(defn model-availability
  "Compute the current availability without mutating capability facts."
  [connection model]
  (cond
    (:removed? connection) :connection-removed
    (not (:enabled? connection)) :connection-disabled
    (= :failed (:auth-status connection)) :auth-expired
    (not (:enabled? model)) :disabled
    :else (or (:availability model) :unconfirmed)))

(defn public-model
  [connection model]
  (-> model
      (assoc :availability (model-availability connection model)
             :confirmed-capabilities (confirmed-capabilities model))
      (update :pricing normalize-pricing)))

(defn- model-entry
  [model-entry-id provider-model-id display-name capabilities pricing
   source availability now]
  {:model-entry-id model-entry-id
   :provider-model-id provider-model-id
   :display-name (or display-name provider-model-id)
   :alias nil
   :enabled? true
   :source source
   :availability availability
   :capabilities (normalize-capabilities capabilities)
   :pricing (normalize-pricing pricing)
   :notes nil
   :last-seen-at now
   :created-at now
   :updated-at now})

(defn- model-provider-id
  [value]
  (or (:provider-model-id value) (:model-id value)))

(defn add-manual-model
  [connection {:keys [display-name capabilities pricing] :as model}
   id-source now]
  (let [provider-model-id (model-provider-id model)]
    (cond
      (not (ids/valid-id? (ids/require-id provider-model-id)))
      (error :PROVIDER_MODEL_ID_REQUIRED
             "provider-model-id must be a non-blank string"
             {:provider-model-id provider-model-id})

      (some #(= provider-model-id (:provider-model-id %))
            (vals (:models connection)))
      (error :MODEL_EXISTS
             "this provider model already exists on the connection"
             {:provider-model-id provider-model-id})

      (nil? id-source)
      (error :ID_SOURCE_REQUIRED
             "manual model creation needs an id source"
             {:provider-model-id provider-model-id})

      :else
      (let [model-entry-id (ids/next-id! id-source :model)
            model (model-entry model-entry-id provider-model-id display-name
                               capabilities pricing :manual :unconfirmed now)]
        {:ok? true
         :connection (-> connection
                         (assoc-in [:models model-entry-id] model)
                         (update :model-order (fnil conj []) model-entry-id)
                         (assoc :updated-at now))
         :model-entry-id model-entry-id}))))

(defn refresh-models
  "Merge one observed provider model list.

  Existing entries keep their stable ID, enabled flag, alias, and local notes.
  A listed model that the provider did not return is marked `:not-returned`,
  never deleted.  Manual entries stay present.  The model list is never
  merged by display name."
  [connection observed id-source now]
  (let [observed (or observed [])
        ids (mapv model-provider-id observed)
        duplicate-ids (->> ids frequencies (keep (fn [[id n]] (when (> n 1) id))) vec)]
    (cond
      (not (sequential? observed))
      (error :MODEL_LIST_INVALID "model list must be a sequential collection"
             {:observed observed})

      (some #(not (ids/valid-id? (ids/require-id %))) ids)
      (error :PROVIDER_MODEL_ID_REQUIRED
             "every observed model needs a non-blank provider-model-id"
             {:observed observed})

      (seq duplicate-ids)
      (error :MODEL_LIST_DUPLICATE
             "the provider returned the same model more than once"
             {:provider-model-ids duplicate-ids})

      (and (seq observed) (nil? id-source))
      (error :ID_SOURCE_REQUIRED
             "refreshing new models needs an id source"
             {:observed-count (count observed)})

      :else
      (let [existing-by-provider
            (into {}
                  (map (fn [[entry-id model]]
                         [(:provider-model-id model)
                          (assoc model :model-entry-id entry-id)]))
                  (:models connection))
            observed-provider-ids (set ids)
            [models' new-order]
            (reduce
             (fn [[models order] observed-model]
               (let [provider-id (model-provider-id observed-model)
                     existing (get existing-by-provider provider-id)]
                 (if existing
                   (let [entry-id (:model-entry-id existing)
                         updated (-> existing
                                     (cond-> (:display-name observed-model)
                                       (assoc :display-name (:display-name observed-model)))
                                     (cond-> (contains? observed-model :capabilities)
                                       (assoc :capabilities
                                              (merge (:capabilities existing)
                                                     (normalize-capabilities
                                                      (:capabilities observed-model)))))
                                     (cond-> (contains? observed-model :pricing)
                                       (assoc :pricing
                                              (normalize-pricing (:pricing observed-model))))
                                     (assoc :source :listed
                                            :availability :available
                                            :last-seen-at now
                                            :updated-at now))]
                     [(assoc models entry-id updated) order])
                   (let [entry-id (ids/next-id! id-source :model)
                         model (model-entry entry-id provider-id
                                            (:display-name observed-model)
                                            (:capabilities observed-model)
                                            (:pricing observed-model)
                                            :listed :available now)]
                     [(assoc models entry-id model)
                      (conj order entry-id)]))))
             [{} []]
             observed)
            ;; Keep every old entry in its old order.  New entries follow.
            old-order (vec (:model-order connection))
            all-order (vec (concat old-order
                                   (remove (set old-order) new-order)))
            models' (reduce-kv
                     (fn [result entry-id model]
                       (let [listed-missing?
                             (and (= :listed (:source model))
                                  (not (contains? observed-provider-ids
                                                  (:provider-model-id model))))
                             model' (if listed-missing?
                                      (assoc model
                                             :availability :not-returned
                                             :updated-at now)
                                      model)]
                         (assoc result entry-id model')))
                     models'
                     ;; Include old entries that the reduce did not touch.
                     (into {}
                           (keep (fn [entry-id]
                                   (when-let [model (get-in connection
                                                            [:models entry-id])]
                                     (when-not (contains? models' entry-id)
                                       [entry-id model]))))
                           old-order))]
        {:ok? true
         :connection (-> connection
                         (assoc :models models'
                                :model-order all-order
                                :list-status {:state :available
                                              :at now
                                              :returned-count (count observed)})
                         (assoc :updated-at now))}))))

(defn mark-model-list-unavailable
  [connection reason now]
  {:ok? true
   :connection (-> connection
                   (assoc :list-status {:state :unavailable
                                        :at now
                                        :reason reason})
                   (assoc :updated-at now))})

(defn- with-model
  [connection model-entry-id f]
  (if-let [model (get-in connection [:models model-entry-id])]
    {:ok? true
     :connection (assoc-in connection [:models model-entry-id] (f model))}
    (error :MODEL_ENTRY_NOT_FOUND
           "model entry does not exist on this connection"
           {:model-entry-id model-entry-id})))

(defn set-model-enabled
  [connection model-entry-id enabled? now]
  (with-model connection model-entry-id
              #(assoc % :enabled? (boolean enabled?) :updated-at now)))

(defn set-model-alias
  [connection model-entry-id alias now]
  (with-model connection model-entry-id
              #(assoc % :alias (when (non-blank-string? alias) alias)
                        :updated-at now)))

(defn set-model-notes
  [connection model-entry-id notes now]
  (with-model connection model-entry-id
              #(assoc % :notes (when (non-blank-string? notes) notes)
                        :updated-at now)))

(defn record-model-result
  "Record what a request observed about one model entry."
  [connection model-entry-id outcome now]
  (let [availability
        (case outcome
          :ok :available
          :unconfirmed :unconfirmed
          :not-found :model-removed
          :auth-failed :auth-expired
          nil)]
    (cond
      (nil? availability)
      (error :MODEL_RESULT_INVALID
             "model result must be :ok, :unconfirmed, :not-found, or :auth-failed"
             {:outcome outcome})

      :else
      (with-model connection model-entry-id
                  #(assoc % :availability availability
                            :updated-at now)))))

(defn- capability-map
  "Normalize a capability declaration to a boolean map.

  The session-config shape is already a map; a launch/scheduler config may
  carry a set or vector of capability keywords.  Treating that as a map
  crashed the binding snapshot with an ISeq-from-Keyword error."
  [value]
  (cond
    (map? value) value
    (or (set? value) (sequential? value))
    (into {} (map (fn [capability] [capability true])) value)
    :else {}))

(defn model-binding-snapshot
  "An immutable model binding snapshot for a run.

  It contains no credential or secret.  Later configuration edits do not
  change a stored snapshot."
  [connection model]
  (let [view (connection-view connection)]
    {:connection-id (:connection-id view)
     :connection-name (:name view)
     :model-entry-id (:model-entry-id model)
     :provider-model-id (:provider-model-id model)
     :display-name (or (:alias model)
                       (:display-name model)
                       (:provider-model-id model))
     :effective-base-url (:effective-base-url view)
     :official? (:official? view)
     :capabilities (capability-map (:capabilities model))
     :pricing (normalize-pricing (:pricing model))}))

(defn binding-key
  [binding]
  [(:connection-id binding) (:model-entry-id binding)])

(defn check-connection-invariants
  [connection]
  (cond-> []
    (not (map? connection))
    (conj {:invariant :connection-not-a-map})

    (and (map? connection)
         (not (ids/valid-id? (:connection-id connection))))
    (conj {:invariant :connection-id-invalid
           :connection-id (:connection-id connection)})

    (and (map? connection)
         (not (contains? connection-kinds (:kind connection))))
    (conj {:invariant :connection-kind-invalid
           :kind (:kind connection)})

    (and (map? connection)
         (not (contains? rotation-policies (:rotation-policy connection))))
    (conj {:invariant :rotation-policy-invalid
           :rotation-policy (:rotation-policy connection)})

    (and (map? connection)
         (or (not= (set (:credential-order connection))
                   (set (keys (:credentials connection))))
             (not= (count (:credential-order connection))
                   (count (keys (:credentials connection))))))
    (conj {:invariant :credential-order-mismatch
           :order (:credential-order connection)
           :keys (keys (:credentials connection))})

    (and (map? connection)
         (or (not= (set (:model-order connection))
                   (set (keys (:models connection))))
             (not= (count (:model-order connection))
                   (count (keys (:models connection))))))
    (conj {:invariant :model-order-mismatch
           :order (:model-order connection)
           :keys (keys (:models connection))})

    (and (map? connection)
         (:custom-base-url connection)
         (not (valid-base-url? (:custom-base-url connection))))
    (conj {:invariant :custom-base-url-invalid
           :custom-base-url (:custom-base-url connection)})

    (and (map? connection)
         (:official? connection)
         (:custom-base-url connection))
    (conj {:invariant :official-with-custom-host
           :custom-base-url (:custom-base-url connection)})
    (and (map? connection)
         (:official? connection)
         (not= :preset (:kind connection)))
    (conj {:invariant :official-with-non-preset-kind
           :kind (:kind connection)})

    (and (map? connection)
         (some secret-field? (vals (:credentials connection))))
    (conj {:invariant :secret-in-credential-state})

    (and (map? connection)
         (some (fn [[_ model]]
                 (or (not (ids/valid-id? (:model-entry-id model)))
                     (not (ids/valid-id? (:provider-model-id model)))
                     (not (valid-capabilities? (:capabilities model)))
                     (not (valid-pricing? (:pricing model)))))
               (:models connection)))
    (conj {:invariant :model-entry-invalid})))
