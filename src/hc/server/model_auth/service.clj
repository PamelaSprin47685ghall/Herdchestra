(ns hc.server.model-auth.service
  "Third-party model account login service.

  The service owns pending OAuth flows, the loopback callback listener, the
  device-code poller, and the encrypted credential store.  Browser clients see
  only opaque flow ids, user codes, verification URLs, and account metadata.
  Access tokens, refresh tokens, device codes, PKCE verifiers, and client
  secrets never enter a receipt.

  A browser callback flow uses a one-shot loopback listener by default.  An
  application may instead mount `ring-handler` and pass
  `:external-redirect-uri`; the provider redirect then lands on the
  application's HTTP surface.  The callback state is bound to one flow and is
  compared in constant time."
  (:require [clojure.string :as str]
            [hc.server.model-auth.oauth :as oauth]
            [hc.server.model-auth.provider :as provider]
            [hc.server.model-auth.store :as store])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.time Instant)
           (java.util.concurrent Executors ExecutorService
                                 RejectedExecutionException
                                 ScheduledExecutorService
                                 ScheduledThreadPoolExecutor ThreadFactory
                                 TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(def terminal-statuses
  #{:authorized :denied :expired :cancelled :error})

(def request-keys
  #{:kind :command-id :command-epoch :draft-id :draft-version :config-digest
    :provider :provider-id :flow :flow-type :connection-id :connection-name
    :scopes :replace?})

(def forbidden-request-keys
  #{:password :passwd :username :user :secret :token :access-token
    :refresh-token :id-token :client-secret :api-key})

(def safe-claim-keys
  [:sub :iss :aud :email :email_verified :preferred_username :name
   :given_name :family_name :locale :tid])

(defn- error
  ([code message] (error code message nil))
  ([code message details]
   (cond-> {:ok? false
            :error {:code code :message message}}
     details (assoc-in [:error :details] details))))

(defn- ok
  [receipt]
  {:ok? true :receipt receipt})

(defn- blank?
  [value]
  (or (nil? value) (and (string? value) (str/blank? value))))

(defn- now-ms
  [service]
  (long ((:now-ms service))))

(defn- iso-time
  [ms]
  (when ms (str (Instant/ofEpochMilli (long ms)))))

(defn- keyword-value
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    (symbol? value) (keyword (name value))
    :else nil))

(defn- secret-key?
  [k]
  (contains? forbidden-request-keys
             (keyword (str/lower-case (name k)))))

(defn- contains-secret-key?
  [value]
  (cond
    (map? value) (some (fn [[k v]]
                         (or (secret-key? k) (contains-secret-key? v)))
                       value)
    (sequential? value) (some contains-secret-key? value)
    :else false))

(defn- thread-factory
  [prefix]
  (let [counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable
                       (str prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn- scheduled-executor
  [prefix threads]
  (doto (ScheduledThreadPoolExecutor.
         threads
         (thread-factory prefix))
    (.setRemoveOnCancelPolicy true)))

(defn- schedule!
  [service delay-ms f]
  (try
    (.schedule ^ScheduledExecutorService (:executor service)
               ^Runnable f
               (max 0 (long delay-ms))
               TimeUnit/MILLISECONDS)
    (catch RejectedExecutionException _ nil)
    (catch Exception _ nil)))

(defn- update-flow!
  [service flow-id f & args]
  (swap! (:flows service)
         (fn [flows]
           (if (contains? flows flow-id)
             (update flows flow-id #(apply f % args))
             flows))))

(defn- current-flow
  [service flow-id]
  (get @(:flows service) flow-id))

(defn- cancel-future!
  [future]
  (when future
    (try (.cancel ^java.util.concurrent.Future future false)
         (catch Exception _ nil))))

(defn- cancel-flow-timers!
  [flow]
  (cancel-future! (:expiry-future flow))
  (cancel-future! (:poll-future flow)))

(defn- close-callback-listener!
  [listener]
  (when listener
    (when-let [^HttpServer server (:server listener)]
      (try (.stop server 0) (catch Exception _ nil)))
    (when-let [^ExecutorService executor (:executor listener)]
      (try (.shutdownNow executor) (catch Exception _ nil)))))

(defn- close-flow-resources!
  [service flow-id]
  (when-let [flow (current-flow service flow-id)]
    (close-callback-listener! (:callback-listener flow))
    (cancel-flow-timers! flow)
    (update-flow! service flow-id
                  #(dissoc % :callback-listener :expiry-future :poll-future))))

(defn- schedule-close!
  [service flow-id]
  (try
    (.execute ^ExecutorService (:cleanup-executor service)
              ^Runnable (fn [] (close-flow-resources! service flow-id)))
    (catch RejectedExecutionException _ nil)
    (catch Exception _ nil)))

(defn- schedule-cleanup!
  [service flow-id]
  (schedule! service (* 15 60 1000)
             (fn []
               (swap! (:flows service)
                      (fn [flows]
                        (if (and (contains? flows flow-id)
                                 (contains? terminal-statuses
                                            (get-in flows [flow-id :status])))
                          (dissoc flows flow-id)
                          flows))))))

(defn- mark-terminal!
  [service flow-id status data]
  (when-let [flow (current-flow service flow-id)]
    (when-let [state (:state flow)]
      (swap! (:by-state service) dissoc state))
    (cancel-flow-timers! flow)
    (update-flow! service flow-id
                  #(merge % data {:status status
                                  :terminal-at-ms (now-ms service)}))
    (schedule-cleanup! service flow-id)))

(defn- terminal!
  [service flow-id status data]
  (mark-terminal! service flow-id status data)
  (schedule-close! service flow-id))

(defn- public-account
  [credential]
  (store/public-credential credential))

(defn- public-flow
  [service flow]
  (let [provider (provider/provider (:providers service) (:provider-id flow))
        now (now-ms service)
        expires-at (:expires-at-ms flow)
        remaining (when expires-at
                    (max 0 (quot (- expires-at now) 1000)))]
    (cond-> {:flow-id (:flow-id flow)
             :provider-id (:provider-id flow)
             :provider-name (:name provider)
             :flow-type (:flow-type flow)
             :status (:status flow)
             :connection-id (:connection-id flow)
             :scopes (:scopes flow)
             :expires-at (iso-time expires-at)
             :expires-at-ms expires-at
             :expires-in-seconds remaining
             :interval-seconds (:interval-seconds flow)
             :account (:account flow)}
      (:authorize-url flow) (assoc :authorize-url (:authorize-url flow))
      (:user-code flow) (assoc :user-code (:user-code flow)
                               :verification-uri (:verification-uri flow)
                               :verification-uri-complete
                               (:verification-uri-complete flow))
      (:last-error flow) (assoc :last-error (:last-error flow))
      (:error flow) (assoc :error (:error flow)))))

(defn- flow-expired?
  [service flow]
  (let [expires-at (:expires-at-ms flow)]
    (and expires-at (>= (now-ms service) (long expires-at)))))

(defn- public-flow-status
  [service flow-id]
  (when-let [flow (current-flow service flow-id)]
    (if (and (= :pending (:status flow)) (flow-expired? service flow))
      (do (terminal! service flow-id :expired
                     {:error {:code :MODEL_AUTH_FLOW_EXPIRED
                              :message "the authorization request expired"}})
          (public-flow service (current-flow service flow-id)))
      (public-flow service flow))))

(defn- secret-safe-error
  [value]
  (when (map? value)
    (select-keys value [:code :message :provider-error :provider-description
                        :status-code])))

;; ---------------------------------------------------------------------------
;; Loopback callback listener

(defn- html-escape
  [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- callback-page
  [result]
  (let [ok? (:ok? result)
        title (if ok? "Authorization received" "Authorization not completed")
        detail (if ok?
                 "You can close this window and return to Herdchastre."
                 (str "The authorization could not be completed. "
                      (or (get-in result [:error :code]) "unknown error")
                      ". Return to the application and try again."))]
    (str "<!doctype html><html><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>" (html-escape title) "</title>"
         "<style>body{font-family:system-ui,sans-serif;margin:3rem;line-height:1.5}"
         "main{max-width:36rem;margin:auto}</style></head><body><main>"
         "<h1>" (html-escape title) "</h1><p>" (html-escape detail) "</p>"
         "</main></body></html>")))

(defn- send-http!
  [^HttpExchange exchange status content-type body]
  (let [bytes (.getBytes (str body) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "content-type" content-type)
    (.set (.getResponseHeaders exchange) "cache-control" "no-store")
    (.set (.getResponseHeaders exchange) "content-security-policy"
          "default-src 'none'; style-src 'unsafe-inline'")
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- ring-html
  [status body]
  {:status status
   :headers {"content-type" "text/html; charset=utf-8"
             "cache-control" "no-store"
             "content-security-policy" "default-src 'none'; style-src 'unsafe-inline'"}
   :body body})

(declare handle-callback-query!)

(defn- start-callback-listener!
  [service flow-id]
  (let [host (or (:callback-host service) "127.0.0.1")
        path "/oauth/callback"
        server (HttpServer/create (InetSocketAddress. host 0) 0)
        executor (Executors/newSingleThreadExecutor
                  (thread-factory "hc-model-auth-callback"))
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (try
                      (if-not (= "GET" (.getRequestMethod exchange))
                        (send-http! exchange 405 "text/plain; charset=utf-8"
                                    "method not allowed")
                        (let [parsed (oauth/parse-callback-pairs
                                      (.getRawQuery (.getRequestURI exchange)))
                              result (if (:ok? parsed)
                                       (handle-callback-query!
                                        service flow-id (:pairs parsed))
                                       parsed)]
                          (send-http! exchange
                                      (if (:ok? result) 200 400)
                                      "text/html; charset=utf-8"
                                      (callback-page result))
                          (when (or (:ok? result)
                                    (contains? terminal-statuses
                                               (get-in @(:flows service)
                                                       [flow-id :status])))
                            (schedule-close! service flow-id))))
                      (catch Throwable _
                        (try
                          (send-http! exchange 500 "text/plain; charset=utf-8"
                                      "authorization callback failed")
                          (catch Throwable _ nil)))
                      (finally
                        (try (.close exchange) (catch Throwable _ nil))))))]
    (.createContext server path handler)
    (.setExecutor server executor)
    (.start server)
    (let [port (.getPort (.getAddress server))]
      {:server server
       :executor executor
       :port port
       :redirect-uri (str "http://" host ":" port path)})))

;; ---------------------------------------------------------------------------
;; Provider and flow validation

(defn- provider-for
  [service provider-id]
  (let [provider (provider/provider (:providers service) provider-id)]
    (when-not provider
      (throw (ex-info "provider is not configured"
                      {:error/kind :model-auth/unknown-provider
                       :code :MODEL_AUTH_PROVIDER_UNKNOWN
                       :provider-id provider-id})))
    (when-not (:configured? provider)
      (throw (ex-info "provider client identity is not configured"
                      {:error/kind :model-auth/provider-not-configured
                       :code :MODEL_AUTH_PROVIDER_NOT_CONFIGURED
                       :provider-id provider-id
                       :client-id-env (:client-id-env provider)})))
    provider))

(defn- requested-scopes
  [provider request]
  (let [scopes (or (:scopes request) (:default-scopes provider))]
    (cond
      (string? scopes) (vec (remove str/blank? (str/split scopes #"\s+")))
      (sequential? scopes) (mapv str scopes)
      :else
      (throw (ex-info "scopes must be a string or sequence"
                      {:error/kind :model-auth/invalid-request
                       :code :MODEL_AUTH_INVALID_SCOPES})))))

(defn- begin-authorization-code!
  [service provider request connection-id scopes]
  (let [callback-mode (:callback-mode service)
        external-redirect (when (= :external callback-mode)
                            (:external-redirect-uri service))]
    (when (and (= :external callback-mode) (blank? external-redirect))
      (throw (ex-info "external callback mode needs a redirect URI"
                      {:error/kind :model-auth/redirect-required
                       :code :MODEL_AUTH_REDIRECT_URI_REQUIRED})))
    (let [pkce (when (:pkce? provider) (oauth/pkce))
          state (oauth/random-token)
          flow-id (oauth/random-token)
          listener (when (= :loopback callback-mode)
                     (start-callback-listener! service flow-id))
          redirect-uri (or (some-> listener :redirect-uri)
                           external-redirect)
          _ (provider/redirect-uri redirect-uri)
          expires-in (long (or (:authorization-timeout-seconds service) 600))
          expires-at (+ (now-ms service) (* 1000 expires-in))
          authorize-url (oauth/authorization-url
                         provider
                         {:redirect-uri redirect-uri
                          :state state
                          :code-challenge (:challenge pkce)
                          :scopes scopes})
          flow {:flow-id flow-id
                :provider-id (:id provider)
                :connection-id connection-id
                :flow-type :authorization-code
                :status :pending
                :created-at-ms (now-ms service)
                :expires-at-ms expires-at
                :scopes scopes
                :state state
                :code-verifier (:verifier pkce)
                :redirect-uri redirect-uri
                :callback-listener listener
                :authorize-url authorize-url
                :replace? (boolean (:replace? request))}]
      (swap! (:flows service) assoc flow-id flow)
      (swap! (:by-state service) assoc state flow-id)
      (let [future (schedule! service (* 1000 expires-in)
                              (fn []
                                (when (= :pending (get-in @(:flows service)
                                                           [flow-id :status]))
                                  (terminal!
                                   service flow-id :expired
                                   {:error {:code :MODEL_AUTH_FLOW_EXPIRED
                                            :message "the authorization request expired"}}))))]
        (update-flow! service flow-id assoc :expiry-future future))
      (public-flow service (current-flow service flow-id)))))

(declare poll-device!)

(defn- safe-verification-uri!
  [value]
  (when-not (blank? value)
    (provider/redirect-uri value))
  value)

(defn- begin-device-code!
  [service provider request connection-id scopes]
  (let [result (oauth/device-authorization (:http-client service) provider scopes)]
    (when-not (:ok? result)
      (throw (ex-info "provider refused the device authorization request"
                      {:error/kind :model-auth/provider-rejected
                       :code :MODEL_AUTH_PROVIDER_REJECTED
                       :error (:error result)})))
    (let [expires-in (long (or (:expires-in result) 600))
          interval (max 1 (long (or (:interval result) 5)))
          flow-id (oauth/random-token)
          expires-at (+ (now-ms service) (* 1000 expires-in))
          flow {:flow-id flow-id
                :provider-id (:id provider)
                :connection-id connection-id
                :flow-type :device-code
                :status :pending
                :created-at-ms (now-ms service)
                :expires-at-ms expires-at
                :scopes scopes
                :device-code (:device-code result)
                :user-code (:user-code result)
                :verification-uri (safe-verification-uri!
                                   (:verification-uri result))
                :verification-uri-complete
                (some-> (:verification-uri-complete result)
                        safe-verification-uri!)
                :interval-seconds interval
                :next-poll-at-ms (+ (now-ms service) (* 1000 interval))
                :replace? (boolean (:replace? request))}]
      (swap! (:flows service) assoc flow-id flow)
      (let [expiry (schedule! service (* 1000 expires-in)
                              (fn []
                                (when (= :pending (get-in @(:flows service)
                                                           [flow-id :status]))
                                  (terminal!
                                   service flow-id :expired
                                   {:error {:code :MODEL_AUTH_FLOW_EXPIRED
                                            :message "the device code expired"}}))))
            poll (when (:device-poll? service)
                   (schedule! service (* 1000 interval)
                              (fn [] (poll-device! service flow-id))))]
        (update-flow! service flow-id assoc :expiry-future expiry
                      :poll-future poll))
      (public-flow service (current-flow service flow-id)))))

(defn- existing-identity-conflict
  [existing provider-id replace?]
  (and existing
       (not replace?)
       (not= provider-id (:provider-id existing))))

(defn begin!
  "Start one authorization-code or device-code flow.

  REQUEST may carry `:provider-id`, `:flow-type`, `:connection-id`,
  `:scopes`, and `:replace?`.  A password-like key is refused before any
  network call."
  [service request]
  (try
    (when-not (map? request)
      (throw (ex-info "request must be a map"
                      {:error/kind :model-auth/invalid-request
                       :code :MODEL_AUTH_INVALID_REQUEST})))
    (when (contains-secret-key? request)
      (throw (ex-info "password or token fields are not accepted"
                      {:error/kind :model-auth/password-forbidden
                       :code :MODEL_AUTH_PASSWORD_FORBIDDEN})))
    (when-let [unknown (seq (remove request-keys (keys request)))]
      (throw (ex-info "request has unknown fields"
                      {:error/kind :model-auth/invalid-request
                       :code :MODEL_AUTH_INVALID_REQUEST
                       :unknown (vec (sort-by str unknown))})))
    (let [provider-id (or (:provider-id request) (:provider request))
          connection-id (:connection-id request)
          _ (when (blank? connection-id)
              (throw (ex-info "connection-id is required"
                              {:error/kind :model-auth/invalid-request
                               :code :MODEL_AUTH_CONNECTION_REQUIRED})))
          provider (provider-for service provider-id)
          flow-type (or (keyword-value (:flow-type request))
                        (if (contains? (:flows provider) :authorization-code)
                          :authorization-code
                          :device-code))
          _ (when-not (contains? (:flows provider) flow-type)
              (throw (ex-info "provider does not support this flow"
                              {:error/kind :model-auth/flow-unsupported
                               :code :MODEL_AUTH_FLOW_UNSUPPORTED
                               :provider-id (:id provider)
                               :flow-type flow-type})))
          replace? (boolean (:replace? request))
          existing (store/read-credential (:store service) connection-id)
          _ (when (existing-identity-conflict existing (:id provider) replace?)
              (throw (ex-info "connection is already linked to another provider"
                              {:error/kind :model-auth/identity-conflict
                               :code :MODEL_AUTH_IDENTITY_CONFLICT
                               :provider-id (:id provider)
                               :existing (public-account existing)})))
          scopes (requested-scopes provider request)
          flow (case flow-type
                 :authorization-code
                 (begin-authorization-code! service provider request
                                            connection-id scopes)
                 :device-code
                 (begin-device-code! service provider request
                                     connection-id scopes))]
      (ok (assoc flow :status :pending)))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (error (or (:code data) :MODEL_AUTH_BEGIN_FAILED)
               (or (ex-message e) "authorization could not start")
               (dissoc data :error/kind :code))))))

;; ---------------------------------------------------------------------------
;; Completion and identity

(defn- claim-value
  [claims key]
  (let [value (get claims key)]
    (when (and (some? value) (not (str/blank? (str value))))
      (str value))))

(defn- claims-subject
  [claims]
  (some #(claim-value claims %)
        [:sub :id :login :user_id :oid]))

(defn- claims-label
  [provider claims subject]
  (or (some #(claim-value claims %) (:account-claims provider))
      subject))

(defn- token-scopes
  [token-result flow]
  (let [scope (:scope token-result)]
    (cond
      (string? scope) (vec (sort (oauth/scope-set scope)))
      (sequential? scope) (vec (sort (map str scope)))
      :else (vec (sort (set (:scopes flow)))))))

(defn- validate-claims!
  [service provider client-id claims]
  (when-let [aud (:aud claims)]
    (let [auds (if (sequential? aud) (set (map str aud)) #{(str aud)})]
      (when (and client-id (seq auds) (not (contains? auds client-id)))
        (throw (ex-info "token audience does not match this client"
                        {:error/kind :model-auth/audience-mismatch
                         :code :MODEL_AUTH_AUDIENCE_MISMATCH})))))
  (when-let [exp (:exp claims)]
    (when (and (number? exp)
               (< (long exp) (quot (now-ms service) 1000)))
      (throw (ex-info "identity token is already expired"
                      {:error/kind :model-auth/token-expired
                       :code :MODEL_AUTH_IDENTITY_EXPIRED}))))
  (when (and (or (:issuer provider) (seq (:issuer-prefixes provider)))
             (some? (:iss claims))
             (not (oauth/issuer-matches? provider (:iss claims))))
    (throw (ex-info "token issuer does not match the provider"
                    {:error/kind :model-auth/issuer-mismatch
                     :code :MODEL_AUTH_ISSUER_MISMATCH
                     :issuer (str (:iss claims))}))))

(defn- identity-claims
  [service provider token]
  (let [id-claims (oauth/jwt-claims (:id-token token))
        userinfo (when (:userinfo-endpoint provider)
                   (oauth/userinfo (:http-client service) provider
                                   (:access-token token)))]
    (when id-claims
      (validate-claims! service provider (:client-id provider) id-claims))
    (when (and id-claims
               (:ok? userinfo)
               (map? (:claims userinfo)))
      (let [id-subject (claims-subject id-claims)
            userinfo-subject (claims-subject (:claims userinfo))]
        (when (and id-subject userinfo-subject
                   (not= id-subject userinfo-subject))
          (throw (ex-info "provider identity sources disagree"
                          {:error/kind :model-auth/identity-conflict
                           :code :MODEL_AUTH_IDENTITY_CONFLICT})))))
    (cond
      (and (:ok? userinfo) (map? (:claims userinfo)))
      (merge id-claims (:claims userinfo))

      id-claims id-claims

      :else
      (throw (ex-info "provider returned no account identity"
                      {:error/kind :model-auth/identity-unavailable
                       :code :MODEL_AUTH_IDENTITY_UNAVAILABLE
                       :userinfo-error (secret-safe-error (:error userinfo))})))))

(defn- complete-token!
  [service flow-id token-result]
  (let [flow (current-flow service flow-id)
        provider (provider/provider (:providers service) (:provider-id flow))
        token (:token token-result)
        claims (identity-claims service provider token)
        issuer (or (claim-value claims :iss) (:issuer provider))
        subject (claims-subject claims)
        _ (when (blank? subject)
            (throw (ex-info "provider identity has no subject"
                            {:error/kind :model-auth/identity-missing
                             :code :MODEL_AUTH_IDENTITY_MISSING})))
        existing (store/read-credential (:store service) (:connection-id flow))
        conflict? (and existing
                       (not (:replace? flow))
                       (or (not= (:provider-id existing) (:provider-id flow))
                           (not= (:subject existing) subject)))
        _ (when conflict?
            (throw (ex-info "provider returned a different account"
                            {:error/kind :model-auth/identity-conflict
                             :code :MODEL_AUTH_IDENTITY_CONFLICT
                             :existing (public-account existing)})))
        obtained-at (now-ms service)
        credential {:version 1
                    :connection-id (:connection-id flow)
                    :provider-id (:provider-id flow)
                    :issuer issuer
                    :subject subject
                    :account-label (claims-label provider claims subject)
                    :account-claims (select-keys claims safe-claim-keys)
                    :scopes (token-scopes token-result flow)
                    :access-token (:access-token token)
                    :refresh-token (:refresh-token token)
                    :token-type (or (:token-type token) :bearer)
                    :expires-at-ms (oauth/expires-at-ms
                                    obtained-at (:expires-in token))
                    :obtained-at-ms obtained-at
                    :base-url (:base-url provider)
                    :client-id (:client-id provider)}]
    (store/write-credential! (:store service) (:connection-id flow) credential)
    (when (and existing
               (:replace? flow)
               (not= (:refresh-token existing) (:refresh-token credential)))
      (when-let [old-token (or (:refresh-token existing)
                               (:access-token existing))]
        (oauth/revoke (:http-client service) provider old-token)))
    (mark-terminal! service flow-id :authorized
                    {:account (public-account credential)
                     :provider-id (:provider-id credential)
                     :issuer issuer
                     :subject subject
                     :last-error nil
                     :error nil})
    (public-flow service (current-flow service flow-id))))

(defn- fail-flow!
  [service flow-id status err]
  (terminal! service flow-id status {:error (secret-safe-error err)})
  {:ok? false :error (secret-safe-error err)})

;; ---------------------------------------------------------------------------
;; Callback handling

(defn- handle-callback-query!
  [service flow-id query]
  (let [flow (current-flow service flow-id)]
    (cond
      (nil? flow)
      {:ok? false :error {:code :MODEL_AUTH_FLOW_UNKNOWN
                          :message "authorization flow is unknown"}}

      (not= :pending (:status flow))
      {:ok? false :error {:code :MODEL_AUTH_FLOW_TERMINAL
                          :message "authorization flow is already complete"}}

      (not (oauth/constant-time= (:state flow) (str (:state query))))
      {:ok? false :error {:code :MODEL_AUTH_STATE_MISMATCH
                          :message "authorization state does not match"}}

      (:error query)
      (fail-flow! service flow-id
                  (if (= "access_denied" (:error query))
                    :denied
                    :error)
                  {:code :MODEL_AUTH_PROVIDER_REJECTED
                   :provider-error (str (:error query))})

      (blank? (:code query))
      (fail-flow! service flow-id :error
                  {:code :MODEL_AUTH_CODE_MISSING
                   :message "provider returned no authorization code"})

      :else
      (try
        (let [provider (provider/provider (:providers service)
                                          (:provider-id flow))
              result (oauth/authorization-code
                      (:http-client service) provider
                      {:code (:code query)
                       :redirect-uri (:redirect-uri flow)
                       :code-verifier (:code-verifier flow)})]
          (if (:ok? result)
            (try
              (complete-token! service flow-id result)
              {:ok? true :status :authorized}
              (catch clojure.lang.ExceptionInfo e
                (let [data (ex-data e)]
                  (fail-flow! service flow-id :error
                              {:code (or (:code data)
                                         :MODEL_AUTH_IDENTITY_FAILED)
                               :message (or (ex-message e)
                                            "account identity could not be read")}))))
            (fail-flow! service flow-id :error (:error result))))
        (catch Throwable t
          (fail-flow! service flow-id :error
                      {:code :MODEL_AUTH_CALLBACK_FAILED
                       :message (or (ex-message t)
                                    "authorization callback failed")}))))))

(defn ring-handler
  "Ring handler for an application-mounted callback route.

  Mount this before a catch-all fallback and pass the same path in
  `:callback-path` and the provider redirect URI."
  [service]
  (let [path (or (:callback-path service) "/model-auth/callback")]
    (fn [request]
      (cond
        (not= path (:uri request))
        (ring-html 404 "not found")

        (not= :get (:request-method request))
        (ring-html 405 "method not allowed")

        :else
        (let [raw (or (:query-string request)
                      (oauth/form-encode (:query-params request)))
              parsed (oauth/parse-callback-pairs raw)]
          (if-not (:ok? parsed)
            (ring-html 400 (callback-page parsed))
            (let [query (:pairs parsed)
                  state (:state query)
                  flow-id (get @(:by-state service) state)
                  result (if flow-id
                           (handle-callback-query! service flow-id query)
                           {:ok? false
                            :error {:code :MODEL_AUTH_FLOW_UNKNOWN
                                    :message "authorization state is unknown"}})]
              (ring-html (if (:ok? result) 200 400)
                         (callback-page result)))))))))

;; ---------------------------------------------------------------------------
;; Device polling

(defn- schedule-next-device-poll!
  [service flow-id interval-seconds]
  (when (:device-poll? service)
    (let [delay (* 1000 (max 1 (long interval-seconds)))
          future (schedule! service delay
                            (fn [] (poll-device! service flow-id)))]
      (update-flow! service flow-id
                    assoc :poll-future future
                    :next-poll-at-ms (+ (now-ms service) delay)))))

(defn- poll-device!
  [service flow-id]
  (let [flow (current-flow service flow-id)]
    (when (and flow
               (= :pending (:status flow))
               (not (:polling? flow))
               (not (flow-expired? service flow)))
      (update-flow! service flow-id assoc :polling? true)
      (try
        (let [provider (provider/provider (:providers service)
                                          (:provider-id flow))
              result (oauth/device-token (:http-client service) provider
                                         (:device-code flow))]
          (if (:ok? result)
            (try
              (complete-token! service flow-id result)
              (close-flow-resources! service flow-id)
              (catch clojure.lang.ExceptionInfo e
                (let [data (ex-data e)]
                  (terminal! service flow-id :error
                             {:error {:code (or (:code data)
                                                :MODEL_AUTH_IDENTITY_FAILED)
                                      :message (or (ex-message e)
                                                   "account identity could not be read")}}))))
            (let [provider-error (get-in result [:error :provider-error])]
              (case provider-error
                "authorization_pending"
                (schedule-next-device-poll!
                 service flow-id (or (:interval-seconds flow) 5))

                "slow_down"
                (let [interval (+ 5 (long (or (:interval-seconds flow) 5)))]
                  (update-flow! service flow-id assoc :interval-seconds interval)
                  (schedule-next-device-poll! service flow-id interval))

                "access_denied"
                (terminal! service flow-id :denied
                           {:error {:code :MODEL_AUTH_PROVIDER_REJECTED
                                    :provider-error "access_denied"
                                    :message "the account owner denied access"}})

                "expired_token"
                (terminal! service flow-id :expired
                           {:error {:code :MODEL_AUTH_FLOW_EXPIRED
                                    :message "the device code expired"}})

                ("invalid_grant" "invalid_client" "unauthorized_client")
                (terminal! service flow-id :error
                           {:error (secret-safe-error (:error result))})

                (if (get-in result [:error :code])
                  (do
                    (update-flow! service flow-id assoc :last-error
                                  (secret-safe-error (:error result)))
                    (schedule-next-device-poll!
                     service flow-id (or (:interval-seconds flow) 5)))
                  (terminal! service flow-id :error
                             {:error (secret-safe-error (:error result))}))))))
        (catch Throwable t
          (update-flow! service flow-id assoc
                        :last-error {:code :MODEL_AUTH_NETWORK_ERROR
                                     :message (or (ex-message t)
                                                  "device poll failed")})
          (schedule-next-device-poll!
           service flow-id (or (:interval-seconds flow) 5)))
        (finally
          (update-flow! service flow-id assoc :polling? false))))))

;; ---------------------------------------------------------------------------
;; Public commands

(defn poll!
  "Return one flow's public state.  A due device poll runs once."
  [service flow-id]
  (if-let [flow (current-flow service flow-id)]
    (do
      (when (and (= :device-code (:flow-type flow))
                 (= :pending (:status flow))
                 (not (:polling? flow))
                 (or (not (:device-poll? service))
                     (<= (long (or (:next-poll-at-ms flow) 0))
                         (now-ms service))))
        (poll-device! service flow-id))
      (ok (public-flow-status service flow-id)))
    (error :MODEL_AUTH_FLOW_UNKNOWN "authorization flow is unknown")))

(defn cancel!
  [service flow-id]
  (if-let [flow (current-flow service flow-id)]
    (if (contains? terminal-statuses (:status flow))
      (ok (public-flow service flow))
      (do
        (terminal! service flow-id :cancelled
                   {:error {:code :MODEL_AUTH_FLOW_CANCELLED
                            :message "the authorization flow was cancelled"}})
        (ok (public-flow service (current-flow service flow-id)))))
    (error :MODEL_AUTH_FLOW_UNKNOWN "authorization flow is unknown")))

(defn credential-status
  [service connection-id]
  (let [credential (store/read-credential (:store service) connection-id)]
    (if credential
      (let [expired? (and (:expires-at-ms credential)
                          (>= (now-ms service)
                              (long (:expires-at-ms credential))))
            can-refresh? (boolean (:refresh-token credential))]
        (ok {:status (if (and expired? (not can-refresh?))
                       :expired
                       :connected)
             :connection-id connection-id
             :account (public-account credential)
             :provider-id (:provider-id credential)
             :issuer (:issuer credential)
             :subject (:subject credential)
             :scopes (:scopes credential)
             :expires-at-ms (:expires-at-ms credential)}))
      (ok {:status :not-connected
           :connection-id connection-id
           :account nil}))))

(defn list-credentials
  [service]
  (ok {:status :accepted
       :credentials (->> (store/list-credentials (:store service))
                         (mapv public-account)
                         (sort-by :connection-id)
                         vec)}))

(defn logout!
  [service connection-id]
  (if-let [credential (store/read-credential (:store service) connection-id)]
    (do
      (store/delete-credential! (:store service) connection-id)
      (let [provider (provider/provider (:providers service)
                                        (:provider-id credential))
            revocation (try
                         (if (and provider
                                  (or (:refresh-token credential)
                                      (:access-token credential)))
                           (oauth/revoke (:http-client service) provider
                                         (or (:refresh-token credential)
                                             (:access-token credential)))
                           {:ok? false
                            :error {:code :MODEL_AUTH_REVOCATION_UNSUPPORTED}})
                         (catch Throwable _
                           {:ok? false
                            :error {:code :MODEL_AUTH_REVOCATION_FAILED}}))]
        (ok {:status :logged-out
             :connection-id connection-id
             :provider-id (:provider-id credential)
             :remote-revocation (cond
                                  (:ok? revocation) :succeeded
                                  (= :MODEL_AUTH_REVOCATION_UNSUPPORTED
                                     (get-in revocation [:error :code]))
                                  :unsupported
                                  :else :failed)})))
    (ok {:status :not-connected :connection-id connection-id})))

(defn access-token
  "Return one server-side access token, refreshing it when needed.

  This function is for the model adapter and settings application.  It must
  never be exposed as a browser command."
  [service connection-id]
  (locking (:refresh-lock service)
    (if-let [credential (store/read-credential (:store service) connection-id)]
      (let [now (now-ms service)
            expires-at (:expires-at-ms credential)
            fresh? (or (nil? expires-at)
                       (> (long expires-at) (+ now 60000)))]
        (if fresh?
          {:ok? true
           :token (:access-token credential)
           :token-type (:token-type credential)
           :expires-at-ms expires-at
           :connection-id connection-id
           :provider-id (:provider-id credential)
           :issuer (:issuer credential)
           :subject (:subject credential)}
          (if-let [refresh-token (:refresh-token credential)]
            (let [provider (provider/provider (:providers service)
                                              (:provider-id credential))]
              (if-not provider
                {:ok? false
                 :error {:code :MODEL_AUTH_PROVIDER_UNKNOWN
                         :message "the saved provider is no longer configured"}}
                (let [result (oauth/refresh-token (:http-client service)
                                                  provider refresh-token)]
                  (if (:ok? result)
                    (let [token (:token result)
                          id-claims (oauth/jwt-claims (:id-token token))
                          subject (or (claims-subject id-claims)
                                      (:subject credential))]
                      (when id-claims
                        (validate-claims! service provider
                                          (:client-id provider) id-claims))
                      (if (and id-claims
                               (not= subject (:subject credential)))
                        {:ok? false
                         :error {:code :MODEL_AUTH_IDENTITY_CONFLICT
                                 :message "refresh returned a different account"}}
                        (let [next-credential (assoc credential
                                                     :access-token
                                                     (:access-token token)
                                                     :refresh-token
                                                     (or (:refresh-token token)
                                                         refresh-token)
                                                     :token-type
                                                     (or (:token-type token)
                                                         (:token-type credential))
                                                     :expires-at-ms
                                                     (oauth/expires-at-ms
                                                      now (:expires-in token))
                                                     :obtained-at-ms now)]
                          (store/write-credential! (:store service)
                                                   connection-id next-credential)
                          {:ok? true
                           :token (:access-token token)
                           :token-type (:token-type token)
                           :expires-at-ms (:expires-at-ms next-credential)
                           :connection-id connection-id
                           :provider-id (:provider-id next-credential)
                           :issuer (:issuer next-credential)
                           :subject (:subject next-credential)})))
                    {:ok? false
                     :error {:code :MODEL_AUTH_REAUTHORIZATION_REQUIRED
                             :message "the account needs a new login"
                             :provider-error (get-in result
                                                     [:error :provider-error])}}))))
            {:ok? false
             :error {:code :MODEL_AUTH_REAUTHORIZATION_REQUIRED
                     :message "the access token expired and cannot be refreshed"}})))
      {:ok? false
       :error {:code :MODEL_AUTH_NOT_CONNECTED
               :message "this connection has no saved account"}})))

(defn inject-access-tokens
  "Return a model configuration with live bearer tokens for OAuth connections.

  The result is ephemeral.  Never persist it: the durable connection record
  keeps only the provider identity and credential reference."
  [service config]
  (let [oauth-connection?
        (fn [connection]
          (or (= :oauth (:kind connection))
              (= :account-login (:service-kind connection))
              (= :oauth (get-in connection [:auth :type]))
              (= :oauth (get-in connection [:auth :mode]))))
        [connections errors]
        (reduce
         (fn [[connections errors] connection]
           (if (oauth-connection? connection)
             (let [result (try
                            (access-token service (:id connection))
                            (catch Throwable t
                              {:ok? false
                               :error {:code :MODEL_AUTH_TOKEN_FAILED
                                       :message (or (ex-message t)
                                                    "token resolution failed")}}))]
               (if (:ok? result)
                 (let [provider (provider/provider (:providers service)
                                                   (:provider-id result))
                       base-url (or (:base-url connection)
                                    (:custom-base-url connection)
                                    (:default-base-url connection)
                                    (:effective-base-url connection)
                                    (:base-url provider))
                       kind (if (contains? #{:oauth :account-login}
                                           (:kind connection))
                              :openai-compatible
                              (:kind connection))]
                   [(conj connections
                          (cond-> (assoc connection
                                         :kind kind
                                         :api-key (:token result)
                                         :has-key? true
                                         :auth-status :connected
                                         :auth-expires-at-ms
                                         (:expires-at-ms result))
                            (and (some? base-url)
                                 (nil? (:base-url connection)))
                            (assoc :base-url base-url)))
                    errors])
                 [(conj connections
                        (-> connection
                            (dissoc :api-key)
                            (assoc :auth-status :reauthorization-required)))
                  (assoc errors (:id connection) (:error result))]))
             [(conj connections connection) errors]))
         [[] {}]
         (:connections config))]
    {:config (assoc config :connections (vec connections))
     :errors errors}))

(defn handle!
  "Handle one `:model-auth/*` command payload."
  [service payload]
  (try
    (let [kind (keyword-value (:kind payload))]
      (if (contains-secret-key? payload)
        (error :MODEL_AUTH_PASSWORD_FORBIDDEN
               "password or token fields are not accepted")
        (case kind
          :model-auth/providers
          (ok {:status :accepted
               :providers (provider/public-providers (:providers service))})

          :model-auth/begin
          (begin! service (dissoc payload :kind))

          :model-auth/poll
          (if-let [flow-id (:flow-id payload)]
            (poll! service flow-id)
            (error :MODEL_AUTH_FLOW_REQUIRED "flow-id is required"))

          :model-auth/cancel
          (if-let [flow-id (:flow-id payload)]
            (cancel! service flow-id)
            (error :MODEL_AUTH_FLOW_REQUIRED "flow-id is required"))

          :model-auth/status
          (if-let [connection-id (:connection-id payload)]
            (credential-status service connection-id)
            (error :MODEL_AUTH_CONNECTION_REQUIRED
                   "connection-id is required"))

          :model-auth/list
          (list-credentials service)

          :model-auth/logout
          (if-let [connection-id (:connection-id payload)]
            (logout! service connection-id)
            (error :MODEL_AUTH_CONNECTION_REQUIRED
                   "connection-id is required"))

          (error :MODEL_AUTH_UNKNOWN_COMMAND "unknown model-auth command"
                 {:kind kind}))))
    (catch Throwable _
      (error :MODEL_AUTH_COMMAND_FAILED "model-auth command failed"))))

;; ---------------------------------------------------------------------------
;; Lifecycle

(defn start!
  "Create a model-auth service.

  Options:
    :data-dir                 directory for the encrypted file store
    :store                    an explicit CredentialStore (tests)
    :providers                provider configuration map
    :env                      environment map for client ids and secrets
    :callback-mode            :loopback (default) or :external
    :external-redirect-uri    exact redirect URI for :external mode
    :callback-path            Ring path for :external mode
    :authorization-timeout-seconds  default 600
    :device-poll?             default true
    :now-ms                   clock function for tests"
  [options]
  (let [env (or (:env options) (System/getenv))
        providers (provider/configure-providers
                   (or (:providers options) {}) env)
        store (or (:store options)
                  (when-let [data-dir (:data-dir options)]
                    (store/file-store (str data-dir "/model-auth")))
                  (store/memory-store))
        owned-executor? (nil? (:executor options))
        executor (or (:executor options)
                     (scheduled-executor "hc-model-auth" 2))
        cleanup-executor (Executors/newSingleThreadExecutor
                          (thread-factory "hc-model-auth-cleanup"))
        service {:store store
                 :providers providers
                 :http-client (or (:http-client options) (oauth/http-client))
                 :executor executor
                 :owned-executor? owned-executor?
                 :cleanup-executor cleanup-executor
                 :flows (atom {})
                 :by-state (atom {})
                 :refresh-lock (Object.)
                 :now-ms (or (:now-ms options) oauth/now-ms)
                 :callback-mode (or (:callback-mode options) :loopback)
                 :callback-host (or (:callback-host options) "127.0.0.1")
                 :callback-path (or (:callback-path options)
                                    "/model-auth/callback")
                 :external-redirect-uri (:external-redirect-uri options)
                 :authorization-timeout-seconds
                 (long (or (:authorization-timeout-seconds options) 600))
                 :device-poll? (not (false? (:device-poll? options)))
                 :state (atom {:stopped? false})}]
    (when-not (contains? #{"127.0.0.1" "::1" "localhost"}
                         (some-> (:callback-host service) str/lower-case))
      (throw (ex-info "the loopback callback host must stay on this machine"
                      {:error/kind :model-auth/invalid-callback-host
                       :host (:callback-host service)})))
    (when (= :external (:callback-mode service))
      (provider/redirect-uri (:external-redirect-uri service))
      (when-not (str/starts-with? (str (:callback-path service)) "/")
        (throw (ex-info "callback-path must start with /"
                        {:error/kind :model-auth/invalid-callback-path
                         :path (:callback-path service)}))))
    service))

(defn stop!
  [service]
  (when-not (:stopped? @(:state service))
    (swap! (:state service) assoc :stopped? true)
    (doseq [flow-id (keys @(:flows service))]
      (close-flow-resources! service flow-id))
    (reset! (:flows service) {})
    (reset! (:by-state service) {})
    (when (:owned-executor? service)
      (try (.shutdownNow ^ExecutorService (:executor service))
           (catch Exception _ nil)))
    (try (.shutdownNow ^ExecutorService (:cleanup-executor service))
         (catch Exception _ nil))
    (try (store/close-store! (:store service))
         (catch Exception _ nil)))
  true)
