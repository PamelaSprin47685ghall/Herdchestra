(ns hc.server.model-auth.provider
  "Provider catalog for third-party model account login.

  A provider is plain data.  It names real OAuth/OIDC endpoints and the
  environment variables that hold the registered client identity.  Client
  secrets stay in this server-side value and never enter a receipt, snapshot,
  log line, or browser command.

  Built-in providers use HTTPS.  A loopback HTTP endpoint is accepted only
  when the descriptor explicitly sets `:allow-insecure-http? true`; tests use
  that switch for a local OAuth service."
  (:require [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.net URI)))

(def provider-keys
  #{:id :name :description :enabled? :issuer :issuer-prefixes
    :authorization-endpoint :token-endpoint :device-authorization-endpoint
    :userinfo-endpoint :revocation-endpoint :client-id :client-secret
    :client-id-env :client-secret-env :flows :default-scopes
    :authorization-params :base-url :official-hosts :account-claims
    :pkce? :allow-insecure-http? :supports-refresh? :device-client-secret?
    :model-access-note})

(def builtin-providers
  "Real provider endpoints.  A deployment must register its own OAuth client
  and set the named environment variable; no client secret is shipped here."
  {:google
   {:id :google
    :name "Google"
    :description "Google account for Gemini API models."
    :issuer "https://accounts.google.com"
    :authorization-endpoint "https://accounts.google.com/o/oauth2/v2/auth"
    :token-endpoint "https://oauth2.googleapis.com/token"
    :device-authorization-endpoint "https://oauth2.googleapis.com/device/code"
    :userinfo-endpoint "https://openidconnect.googleapis.com/v1/userinfo"
    :revocation-endpoint "https://oauth2.googleapis.com/revoke"
    :client-id-env "HC_MODEL_AUTH_GOOGLE_CLIENT_ID"
    :client-secret-env "HC_MODEL_AUTH_GOOGLE_CLIENT_SECRET"
    :flows #{:authorization-code :device-code}
    :default-scopes ["openid" "email" "profile"
                     "https://www.googleapis.com/auth/generative-language.retriever"]
    :authorization-params {"access_type" "offline"}
    :base-url "https://generativelanguage.googleapis.com/v1beta/openai"
    :official-hosts ["generativelanguage.googleapis.com"]
    :account-claims [:email :preferred_username :name :sub]
    :pkce? true
    :supports-refresh? true}

   :microsoft
   {:id :microsoft
    :name "Microsoft Entra ID"
    :description "Microsoft Entra ID account for Azure OpenAI / Foundry models."
    :issuer "https://login.microsoftonline.com/common/v2.0"
    :issuer-prefixes ["https://login.microsoftonline.com/"]
    :authorization-endpoint
    "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
    :token-endpoint
    "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    :device-authorization-endpoint
    "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode"
    :userinfo-endpoint "https://graph.microsoft.com/oidc/userinfo"
    :client-id-env "HC_MODEL_AUTH_MICROSOFT_CLIENT_ID"
    :client-secret-env "HC_MODEL_AUTH_MICROSOFT_CLIENT_SECRET"
    :flows #{:authorization-code :device-code}
    :default-scopes ["openid" "profile" "email" "offline_access"
                     "https://cognitiveservices.azure.com/.default"]
    :authorization-params {"prompt" "select_account"}
    :official-hosts ["*.openai.azure.com"
                     "*.cognitiveservices.azure.com"
                     "*.services.ai.azure.com"]
    :account-claims [:preferred_username :email :name :sub]
    :pkce? true
    :supports-refresh? true}

   :oidc
   {:id :oidc
    :name "OIDC / OAuth provider"
    :description
    "A registered OAuth 2.0 or OIDC provider.  Supply endpoints and a client id."
    :client-id-env "HC_MODEL_AUTH_OIDC_CLIENT_ID"
    :client-secret-env "HC_MODEL_AUTH_OIDC_CLIENT_SECRET"
    :flows #{:authorization-code :device-code}
    :default-scopes ["openid" "profile" "email" "offline_access"]
    :account-claims [:email :preferred_username :name :sub]
    :pkce? true
    :supports-refresh? true}})

(defn- invalid!
  [reason message data]
  (throw (ex-info message
                  (merge {:error/kind :model-auth/invalid-provider
                          :reason reason}
                         data))))

(defn- check-keys!
  [m allowed path]
  (when-not (map? m)
    (invalid! :not-a-map "provider descriptor must be a map" {:path path}))
  (when-let [unknown (seq (remove allowed (keys m)))]
    (invalid! :unknown-field
              "provider descriptor contains an unknown field"
              {:path path :unknown (vec (sort-by str unknown))})))

(defn- id-keyword
  [value]
  (cond
    (keyword? value) value
    (string? value) (when-not (str/blank? value) (keyword value))
    (symbol? value) (keyword (name value))
    :else nil))

(defn- require-id
  [value path]
  (or (id-keyword value)
      (invalid! :invalid-id
                "provider id must be a non-blank keyword or string"
                {:path path :value value})))

(defn- optional-string
  [value path]
  (when (some? value)
    (when-not (string? value)
      (invalid! :invalid-string "value must be a string"
                {:path path :value value}))
    value))

(defn- non-blank-string
  [value path]
  (let [value (optional-string value path)]
    (when-not (and value (not (str/blank? value)))
      (invalid! :blank-string "value must be a non-blank string"
                {:path path :value value}))
    value))

(defn- string-vector
  [value path]
  (cond
    (nil? value) []
    (string? value) [value]
    (sequential? value)
    (mapv #(non-blank-string % path) value)
    :else
    (invalid! :invalid-list "value must be a string or a sequence of strings"
              {:path path :value value})))

(defn- keyword-vector
  [value path]
  (let [items (cond
                (nil? value) []
                (or (keyword? value) (string? value) (symbol? value)) [value]
                (or (sequential? value) (set? value)) (seq value)
                :else
                (invalid! :invalid-list
                          "value must be a keyword or a sequence of keywords"
                          {:path path :value value}))]
    (mapv (fn [item]
            (or (id-keyword item)
                (invalid! :invalid-keyword "value must be a keyword"
                          {:path path :value item})))
          items)))

(defn- keyword-set
  [value path]
  (set (keyword-vector value path)))

(defn- string-map
  [value path]
  (cond
    (nil? value) {}
    (map? value)
    (into {}
          (map (fn [[k v]]
                 [(non-blank-string (if (keyword? k) (name k) (str k)) path)
                  (non-blank-string v path)]))
          value)
    :else
    (invalid! :invalid-map "value must be a map of strings"
              {:path path :value value})))

(defn- loopback-host?
  [host]
  (contains? #{"localhost" "127.0.0.1" "::1" "[::1]"}
             (some-> host str/lower-case)))

(defn endpoint-uri
  "Parse one endpoint URI.  Return nil for a missing value."
  [value path allow-insecure-http?]
  (when (some? value)
    (let [value (non-blank-string value path)
          uri (try (URI/create value)
                   (catch Exception _
                     (invalid! :invalid-uri "endpoint is not a valid URI"
                               {:path path :value value})))
          scheme (some-> (.getScheme uri) str/lower-case)
          host (.getHost uri)]
      (cond
        (and (= scheme "https") host) uri
        (and allow-insecure-http? (= scheme "http") (loopback-host? host)) uri
        :else
        (invalid! :unsafe-endpoint
                  "endpoint must use HTTPS, or loopback HTTP with an explicit test switch"
                  {:path path :value value})))))

(defn redirect-uri
  "Validate one OAuth redirect URI.  HTTPS and loopback HTTP are allowed."
  [value]
  (when (some? value)
    (let [value (non-blank-string value [:redirect-uri])
          uri (try (URI/create value)
                   (catch Exception _
                     (invalid! :invalid-uri "redirect URI is not a valid URI"
                               {:redirect-uri value})))
          scheme (some-> (.getScheme uri) str/lower-case)
          host (.getHost uri)]
      (cond
        (and (= scheme "https") host) uri
        (and (= scheme "http") (loopback-host? host)) uri
        :else
        (invalid! :unsafe-redirect-uri
                  "redirect URI must use HTTPS or loopback HTTP"
                  {:redirect-uri value})))))

(defn- normalize-provider
  [id descriptor env]
  (check-keys! descriptor provider-keys [:providers id])
  (let [id (require-id id [:providers :id])
        allow-insecure-http? (boolean (:allow-insecure-http? descriptor))
        flows (keyword-set (:flows descriptor) [:providers id :flows])
        client-id (or (when (some? (:client-id descriptor))
                        (non-blank-string (:client-id descriptor)
                                          [:providers id :client-id]))
                      (when-let [env-name (:client-id-env descriptor)]
                        (let [env-name (non-blank-string
                                        env-name [:providers id :client-id-env])
                              value (get env env-name)]
                          (when (and value (not (str/blank? value))) value))))
        client-secret (or (optional-string (:client-secret descriptor)
                                           [:providers id :client-secret])
                          (when-let [env-name (:client-secret-env descriptor)]
                            (get env (non-blank-string
                                      env-name
                                      [:providers id :client-secret-env]))))
        endpoints (into {}
                        (keep (fn [[key path]]
                                (when (contains? descriptor key)
                                  [key (endpoint-uri (get descriptor key)
                                                     [:providers id key]
                                                     allow-insecure-http?)])))
                        {:authorization-endpoint [:authorization-endpoint]
                         :token-endpoint [:token-endpoint]
                         :device-authorization-endpoint
                         [:device-authorization-endpoint]
                         :userinfo-endpoint [:userinfo-endpoint]
                         :revocation-endpoint [:revocation-endpoint]})
        available (cond-> #{}
                    (and (:authorization-endpoint endpoints)
                         (:token-endpoint endpoints))
                    (conj :authorization-code)

                    (and (:device-authorization-endpoint endpoints)
                         (:token-endpoint endpoints))
                    (conj :device-code))
        available (set/intersection flows available)
        base-url (optional-string (:base-url descriptor)
                                  [:providers id :base-url])
        _ (when base-url
            (endpoint-uri base-url [:providers id :base-url]
                          allow-insecure-http?))]
    (assoc descriptor
           :id id
           :name (non-blank-string (:name descriptor) [:providers id :name])
           :description (optional-string (:description descriptor)
                                         [:providers id :description])
           :enabled? (not (false? (:enabled? descriptor)))
           :issuer (optional-string (:issuer descriptor)
                                    [:providers id :issuer])
           :issuer-prefixes (string-vector (:issuer-prefixes descriptor)
                                           [:providers id :issuer-prefixes])
           :endpoints endpoints
           :authorization-endpoint (:authorization-endpoint endpoints)
           :token-endpoint (:token-endpoint endpoints)
           :device-authorization-endpoint
           (:device-authorization-endpoint endpoints)
           :userinfo-endpoint (:userinfo-endpoint endpoints)
           :revocation-endpoint (:revocation-endpoint endpoints)
           :client-id client-id
           :client-secret (when (and client-secret
                                     (not (str/blank? client-secret)))
                            client-secret)
           :client-id-env (optional-string (:client-id-env descriptor)
                                           [:providers id :client-id-env])
           :client-secret-env (optional-string (:client-secret-env descriptor)
                                               [:providers id :client-secret-env])
           :flows available
           :declared-flows flows
           :default-scopes (string-vector (:default-scopes descriptor)
                                          [:providers id :default-scopes])
           :authorization-params (string-map (:authorization-params descriptor)
                                             [:providers id :authorization-params])
           :base-url base-url
           :official-hosts (string-vector (:official-hosts descriptor)
                                          [:providers id :official-hosts])
           :account-claims (keyword-vector (:account-claims descriptor)
                                           [:providers id :account-claims])
           :pkce? (not (false? (:pkce? descriptor)))
           :allow-insecure-http? allow-insecure-http?
           :supports-refresh? (not (false? (:supports-refresh? descriptor)))
           :device-client-secret? (boolean (:device-client-secret? descriptor))
           :model-access-note (optional-string (:model-access-note descriptor)
                                               [:providers id :model-access-note])
           :configured? (boolean
                         (and (not (false? (:enabled? descriptor)))
                              client-id
                              (seq available))))))

(defn- provider-config
  [config]
  (let [providers (if (and (map? config) (contains? config :providers))
                    (:providers config)
                    config)]
    (cond
      (nil? providers) {}
      (map? providers) providers
      (sequential? providers)
      (into {}
            (map (fn [descriptor]
                   (let [id (require-id (:id descriptor) [:providers :id])]
                     [id descriptor])))
            providers)
      :else
      (invalid! :invalid-providers
                "providers configuration must be a map or a sequence"
                {:value providers}))))

(defn configure-providers
  "Return one internal provider map.

  CONFIG is `{:providers ...}` or a provider map.  Built-in descriptors merge
  with overrides by id.  ENV supplies client ids and secrets."
  ([config] (configure-providers config {}))
  ([config env]
   (let [overrides (provider-config config)
         ids (set/union (set (keys builtin-providers)) (set (keys overrides)))]
     (into {}
           (map (fn [raw-id]
                  (let [id (id-keyword raw-id)
                        builtin (get builtin-providers id)
                        override (or (get overrides raw-id)
                                     (get overrides id)
                                     {})
                        _ (when (and builtin override (contains? override :id)
                                     (not= id (id-keyword (:id override))))
                            (invalid! :id-mismatch
                                      "provider override id does not match its key"
                                      {:key raw-id :id (:id override)}))
                        descriptor (merge builtin override)]
                    [id (normalize-provider id descriptor env)])))
           ids))))

(defn public-provider
  "Return the browser-safe projection of one provider."
  [provider]
  (select-keys provider
               [:id :name :description :enabled? :issuer :flows :declared-flows
                :default-scopes :base-url :official-hosts :account-claims
                :client-id-env :configured? :model-access-note
                :supports-refresh?]))

(defn public-providers
  [providers]
  (->> providers
       vals
       (sort-by (comp str :id))
       (mapv public-provider)))

(defn provider
  [providers id]
  (get providers (id-keyword id)))

(defn require-provider
  [providers id]
  (or (provider providers id)
      (invalid! :unknown-provider "provider is not configured"
                {:provider-id id})))
