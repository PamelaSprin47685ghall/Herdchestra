(ns hc.server.model-auth.support
  "Loopback OAuth 2.0 server for model-auth tests.

  The server implements the authorization-code + PKCE flow, the device
  authorization grant, refresh, userinfo, and revocation.  It is a real HTTP
  service on 127.0.0.1, not a protocol mock."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hc.server.model-auth.oauth :as oauth])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.util Base64)))

(defn- b64url
  [value]
  (let [bytes (if (bytes? value)
                value
                (.getBytes (str value) StandardCharsets/UTF_8))]
    (-> (Base64/getUrlEncoder)
        (.withoutPadding)
        (.encodeToString ^bytes bytes))))

(defn- jwt
  [claims]
  (str (b64url "{\"alg\":\"none\"}")
       "." (b64url (json/write-str claims))
       "."))

(defn- response!
  [^HttpExchange exchange status body content-type]
  (let [bytes (.getBytes (str body) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "content-type" content-type)
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- json!
  [exchange status value]
  (response! exchange status (json/write-str value) "application/json"))

(defn- redirect!
  [^HttpExchange exchange location]
  (.set (.getResponseHeaders exchange) "location" location)
  (.sendResponseHeaders exchange 302 -1)
  (.close exchange))

(defn- request-body
  [^HttpExchange exchange]
  (String. (.readAllBytes (.getRequestBody exchange))
           StandardCharsets/UTF_8))

(defn- request-query
  [^HttpExchange exchange]
  (oauth/parse-pairs (.getRawQuery (.getRequestURI exchange))))

(defn- form
  [^HttpExchange exchange]
  (oauth/parse-pairs (request-body exchange)))

(defn- header
  [^HttpExchange exchange name]
  (.getFirst (.getRequestHeaders exchange) name))

(defn- record!
  [state exchange]
  (swap! state update :requests conj
         {:method (.getRequestMethod exchange)
          :path (.getPath (.getRequestURI exchange))
          :query (request-query exchange)}))

(defn- authorize-handler
  [state exchange]
  (let [query (request-query exchange)
        code (str "code-" (random-uuid))
        redirect-uri (:redirect_uri query)
        state-value (:state query)]
    (swap! state assoc-in [:codes code]
           {:redirect-uri redirect-uri
            :state state-value
            :challenge (:code_challenge query)
            :method (:code_challenge_method query)})
    (redirect! exchange
               (str redirect-uri
                    (if (str/includes? redirect-uri "?") "&" "?")
                    "code=" (java.net.URLEncoder/encode code "UTF-8")
                    "&state=" (java.net.URLEncoder/encode state-value "UTF-8")))))

(defn- token-response
  [state exchange access refresh]
  (let [issuer (:issuer @state)
        claims {:iss issuer
                :aud "test-client"
                :sub (get-in @state [:user :sub])
                :email (get-in @state [:user :email])
                :name "Alice"
                :exp (+ (quot (System/currentTimeMillis) 1000) 3600)}
        body (cond-> {:access_token access
                      :refresh_token refresh
                      :token_type "Bearer"
                      :expires_in 3600
                      :scope "openid email profile"}
               (:id-token? @state)
               (assoc :id_token (jwt claims)))]
    (json! exchange 200 body)))

(defn- token-handler
  [state exchange]
  (let [params (form exchange)
        grant (:grant_type params)]
    (swap! state update :token-requests (fnil conj []) params)
    (case grant
      "authorization_code"
      (let [code (:code params)
            entry (get-in @state [:codes code])]
        (cond
          (nil? entry)
          (json! exchange 400 {:error "invalid_grant"
                               :error_description "unknown code"})

          (not= (:redirect_uri params) (:redirect-uri entry))
          (json! exchange 400 {:error "invalid_grant"
                               :error_description "redirect mismatch"})

          (and (:challenge entry)
               (not= (:challenge entry)
                     (b64url (oauth/sha256 (str (:code_verifier params))))))
          (json! exchange 400 {:error "invalid_grant"
                               :error_description "PKCE mismatch"})

          :else
          (do
            (swap! state update :codes dissoc code)
            (token-response state exchange "access-1" "refresh-1"))))

      "urn:ietf:params:oauth:grant-type:device_code"
      (do
        (swap! state update :device-polls (fnil inc 0))
        (if-not (:device-approved? @state)
          (json! exchange 400 {:error "authorization_pending"
                               :error_description "waiting for approval"})
          (do
            (swap! state assoc :device-approved? false)
            (token-response state exchange "access-device" "refresh-device"))))

      "refresh_token"
      (if (= (:refresh_token params) "refresh-1")
        (token-response state exchange "access-2" "refresh-2")
        (json! exchange 400 {:error "invalid_grant"
                             :error_description "refresh token is not valid"}))

      (json! exchange 400 {:error "unsupported_grant_type"}))))

(defn- device-handler
  [state exchange]
  (let [params (form exchange)]
    (swap! state assoc :device-request params)
    (json! exchange 200
           {:device_code "device-1"
            :user_code "ABCD-EFGH"
            :verification_uri (str (:base @state) "/device")
            :verification_uri_complete (str (:base @state) "/device?code=ABCD-EFGH")
            :expires_in 60
            :interval 1})))

(defn- userinfo-handler
  [state exchange]
  (let [authorization (header exchange "authorization")]
    (if (and authorization (str/starts-with? authorization "Bearer "))
      (json! exchange 200
             (merge (:user @state)
                    {:iss (:issuer @state)}))
      (json! exchange 401 {:error "invalid_token"}))))

(defn- revoke-handler
  [state exchange]
  (swap! state assoc :revoked (form exchange))
  (json! exchange 200 {}))

(defn- device-page-handler
  [_ exchange]
  (response! exchange 200 "<!doctype html><title>Device login</title>"
             "text/html; charset=utf-8"))

(defn start-oauth-server
  ([] (start-oauth-server {}))
  ([{:keys [id-token?]}]
   (let [state (atom {:requests []
                      :codes {}
                      :device-approved? false
                      :device-polls 0
                      :id-token? (boolean id-token?)
                      :user {:sub "user-1"
                             :email "alice@example.test"
                             :name "Alice"}})
         server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
         base (str "http://127.0.0.1:" (.getPort (.getAddress server)))]
     (swap! state assoc :base base :issuer base)
     (doseq [[path handler] {"/authorize" authorize-handler
                             "/token" token-handler
                             "/device_authorization" device-handler
                             "/userinfo" userinfo-handler
                             "/revoke" revoke-handler
                             "/device" device-page-handler}]
       (.createContext
        server path
        (reify HttpHandler
          (handle [_ exchange]
            (try
              (record! state exchange)
              (handler state exchange)
              (finally
                (try (.close exchange) (catch Throwable _ nil))))))))
     (.start server)
     {:server server
      :base base
      :state state
      :issuer base})))

(defn stop!
  [oauth-server]
  (.stop (:server oauth-server) 0))

(defn provider
  [oauth-server & [overrides]]
  (let [base (:base oauth-server)]
    (merge {:id :local
            :name "Local OAuth"
            :description "Loopback OAuth test provider."
            :issuer base
            :authorization-endpoint (str base "/authorize")
            :token-endpoint (str base "/token")
            :device-authorization-endpoint (str base "/device_authorization")
            :userinfo-endpoint (str base "/userinfo")
            :revocation-endpoint (str base "/revoke")
            :client-id "test-client"
            :client-secret "test-secret"
            :flows #{:authorization-code :device-code}
            :default-scopes ["openid" "email" "profile"]
            :authorization-params {}
            :base-url (str base "/v1")
            :official-hosts []
            :account-claims [:email :sub]
            :allow-insecure-http? true
            :pkce? true
            :supports-refresh? true}
           overrides)))

(defn providers
  [oauth-server & [overrides]]
  {:local (provider oauth-server overrides)})

(defn request-for
  [oauth-server path]
  (->> (:requests @(:state oauth-server))
       (filter #(= path (:path %)))
       last))

(defn requests-for
  [oauth-server path]
  (->> (:requests @(:state oauth-server))
       (filter #(= path (:path %)))
       vec))

(defn callback-url
  "Extract the redirect Location from an authorize URL without following it."
  [authorize-url]
  (let [client (-> (java.net.http.HttpClient/newBuilder)
                   (.followRedirects java.net.http.HttpClient$Redirect/NEVER)
                   (.build))
        request (-> (java.net.http.HttpRequest/newBuilder
                     (java.net.URI/create authorize-url))
                    (.GET)
                    (.build))
        response (.send client request
                        (java.net.http.HttpResponse$BodyHandlers/discarding))]
    (some-> (.firstValue (.headers response) "location")
            (.orElse nil))))
