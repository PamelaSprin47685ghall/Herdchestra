(ns hc.server.model-auth.oauth
  "OAuth 2.0 protocol primitives for model account login.

  This namespace performs HTTP form posts, PKCE, device authorization, token
  exchange, refresh, userinfo, and revocation.  It never logs or returns a
  token in an error.  Redirects are never followed.  Response bodies are
  bounded before parsing.

  ID-token claims are used only as an identity hint after the token endpoint
  returned over TLS.  Their signature is not trusted for local authorization;
  the server uses the provider's userinfo response when it exists."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net URI URLDecoder URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.time Duration Instant)
           (java.util Base64)))

(def default-timeout-seconds 20)
(def max-response-bytes (* 128 1024))

(defn- bytes->b64url
  [^bytes bytes]
  (-> (Base64/getUrlEncoder)
      (.withoutPadding)
      (.encodeToString bytes)))

(defn random-token
  "Return a URL-safe random string with 256 bits of entropy."
  []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (bytes->b64url bytes)))

(defn sha256
  [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.digest digest (.getBytes (str value) StandardCharsets/UTF_8))))

(defn pkce
  "Return an RFC 7636 S256 verifier/challenge pair."
  []
  (let [verifier (random-token)]
    {:verifier verifier
     :challenge (bytes->b64url (sha256 verifier))
     :method "S256"}))

(defn constant-time=
  "Compare two strings without an early byte exit."
  [a b]
  (and (string? a)
       (string? b)
       (MessageDigest/isEqual (.getBytes ^String a StandardCharsets/UTF_8)
                              (.getBytes ^String b StandardCharsets/UTF_8))))

(defn form-encode
  [params]
  (->> params
       (keep (fn [[k v]]
               (when (some? v)
                 (str (URLEncoder/encode (if (keyword? k) (name k) (str k))
                                         StandardCharsets/UTF_8)
                      "="
                      (URLEncoder/encode (str v) StandardCharsets/UTF_8)))))
       (str/join "&")))

(defn- decode-component
  [value]
  (try
    (URLDecoder/decode (or value "") StandardCharsets/UTF_8)
    (catch Exception _
      (or value ""))))

(defn parse-pairs-string
  "Parse one form-encoded string into string-keyed pairs.

  Malformed percent escapes are kept as their raw text instead of throwing;
  a public callback must not turn a bad query into a 500."
  [value]
  (if (str/blank? value)
    {}
    (reduce (fn [acc pair]
              (if (str/blank? pair)
                acc
                (let [[k v] (str/split pair #"=" 2)
                      k (decode-component k)]
                  (if (str/blank? k)
                    acc
                    (assoc acc k (decode-component v))))))
            {}
            (str/split value #"&" -1))))

(defn parse-pairs
  [value]
  (into {}
        (map (fn [[k v]] [(keyword k) v]))
        (parse-pairs-string value)))

(def callback-keys
  "Callback fields the service understands.

  The first four are the OAuth contract.  The rest are common provider
  additions that must not be keywordized or treated as unknown fields."
  #{:state :code :error :error_description
    :scope :authuser :prompt :hd :session_state :iss})

(defn parse-callback-pairs
  "Parse one OAuth callback query with a bounded key vocabulary.

  Returns `{:ok? true :pairs ...}` or `{:ok? false :error ...}`.  Unknown
  fields are rejected before they can enter the flow; keys are keywordized
  only after the whitelist check."
  [value]
  (let [pairs (parse-pairs-string value)
        unknown (->> (keys pairs)
                     (remove #(contains? callback-keys (keyword %)))
                     (sort)
                     vec)]
    (if (seq unknown)
      {:ok? false
       :error {:code :MODEL_AUTH_CALLBACK_UNKNOWN_FIELD
               :message "the authorization callback contains an unknown field"
               :unknown unknown}}
      {:ok? true
       :pairs (into {}
                    (map (fn [[k v]] [(keyword k) v]))
                    pairs)})))

(defn- read-limited
  [^InputStream in limit]
  (let [out (ByteArrayOutputStream.) buffer (byte-array 8192)]
    (loop [total 0]
      (let [n (.read in buffer)]
        (if (neg? n)
          (.toByteArray out)
          (let [total' (+ total n)]
            (when (> total' limit)
              (throw (ex-info "OAuth response exceeds the bounded body"
                              {:error/kind :model-auth/response-too-large
                               :limit limit})))
            (.write out buffer 0 n)
            (recur total')))))))

(defn http-client
  []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.followRedirects HttpClient$Redirect/NEVER)
      (.build)))

(defn- response-headers
  [response]
  (into {}
        (map (fn [[k values]]
               [(str/lower-case (str k)) (vec values)]))
        (.map (.headers response))))

(defn- send!
  [client ^HttpRequest request]
  (try
    (let [response (.send client request
                          (HttpResponse$BodyHandlers/ofInputStream))
          body (read-limited (.body response) max-response-bytes)
          headers (response-headers response)]
      {:ok? true
       :status-code (.statusCode response)
       :headers headers
       :content-type (some-> (get headers "content-type" [])
                             first
                             str/lower-case)
       :body (String. ^bytes body StandardCharsets/UTF_8)})
    (catch clojure.lang.ExceptionInfo e
      {:ok? false
       :error {:code :MODEL_AUTH_RESPONSE_TOO_LARGE
               :message (or (ex-message e) "response too large")}})
    (catch Exception e
      {:ok? false
       :error {:code :MODEL_AUTH_NETWORK_ERROR
               :message (or (ex-message e) (str e))}})))

(defn- decode-body
  [response]
  (let [body (:body response)
        content-type (or (:content-type response) "")]
    (cond
      (str/blank? body) {}
      (or (str/includes? content-type "json")
          (str/starts-with? (str/trim body) "{"))
      (try
        (json/read-str body :key-fn keyword)
        (catch Exception _
          {:error "invalid_response"}))
      :else
      (parse-pairs body))))

(defn- truncate
  [value n]
  (let [value (str value)]
    (if (> (count value) n) (subs value 0 n) value)))

(defn- redact-description
  [value]
  (some-> value
          str
          (str/replace #"(?i)(access_token|refresh_token|client_secret|code)=([^&\s]+)"
                       "$1=***")
          (truncate 300)))

(defn provider-error
  "Return a small, non-secret error projection from a provider response."
  [response]
  (let [body (decode-body response)
        code (or (:error body) (:error_code body) "provider_error")
        description (or (:error_description body) (:message body))]
    {:code :MODEL_AUTH_PROVIDER_REJECTED
     :status-code (:status-code response)
     :provider-error (truncate code 120)
     :provider-description (redact-description description)}))

(defn- uri-value
  [value]
  (if (instance? URI value) value (URI/create (str value))))

(defn- post-form
  [client url params]
  (let [builder (-> (HttpRequest/newBuilder (uri-value url))
                    (.timeout (Duration/ofSeconds default-timeout-seconds))
                    (.header "accept" "application/json")
                    (.header "content-type"
                             "application/x-www-form-urlencoded")
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (form-encode params)
                            StandardCharsets/UTF_8)))]
    (send! client (.build builder))))

(defn- get-json
  [client url access-token]
  (let [builder (-> (HttpRequest/newBuilder (uri-value url))
                    (.timeout (Duration/ofSeconds default-timeout-seconds))
                    (.header "accept" "application/json")
                    (.header "authorization" (str "Bearer " access-token))
                    (.GET))]
    (send! client (.build builder))))

(defn- ok-status?
  [status]
  (<= 200 status 299))

(defn- token-map
  [body]
  (when (map? body)
    (let [access (or (:access_token body) (:access-token body))
          token-type (or (:token_type body) (:token-type body))
          expires-in (or (:expires_in body) (:expires-in body))
          expires-in (when (or (number? expires-in)
                               (and (string? expires-in)
                                    (re-matches #"\d+" expires-in)))
                       (long (if (number? expires-in)
                               expires-in
                               (Long/parseLong expires-in))))]
      (when (and (string? access) (not (str/blank? access)))
        {:access-token access
         :refresh-token (let [value (or (:refresh_token body)
                                        (:refresh-token body))]
                          (when (and (string? value)
                                     (not (str/blank? value)))
                            value))
         :token-type (keyword (str/lower-case (or token-type "bearer")))
         :expires-in expires-in
         :scope (or (:scope body) (:scope body))
         :id-token (let [value (or (:id_token body) (:id-token body))]
                     (when (and (string? value)
                                (not (str/blank? value)))
                       value))}))))

(defn token-request
  "Exchange one grant at PROVIDER's token endpoint.

  PARAMS supplies grant-specific fields.  The client id and optional client
  secret come from the provider descriptor, never from a browser payload."
  [client provider params]
  (let [form (cond-> (assoc params
                            :client_id (:client-id provider))
               (:client-secret provider)
               (assoc :client_secret (:client-secret provider)))
        response (post-form client (:token-endpoint provider) form)]
    (if-not (:ok? response)
      response
      (let [body (decode-body response)
            token (token-map body)]
        (if (and (ok-status? (:status-code response)) token)
          {:ok? true
           :status-code (:status-code response)
           :token token
           :scope (when-let [scope (:scope body)]
                    (when (string? scope) scope))}
          {:ok? false
           :error (provider-error response)})))))

(defn refresh-token
  [client provider refresh-token]
  (token-request client provider
                 {:grant_type "refresh_token"
                  :refresh_token refresh-token}))

(defn device-authorization
  "Start the RFC 8628 device authorization grant."
  [client provider scopes]
  (let [params (cond-> {:client_id (:client-id provider)
                        :scope (str/join " " scopes)}
                 (and (:client-secret provider)
                      (:device-client-secret? provider))
                 (assoc :client_secret (:client-secret provider)))
        response (post-form client
                            (:device-authorization-endpoint provider)
                            params)]
    (if-not (:ok? response)
      response
      (let [body (decode-body response)
            device-code (:device_code body)
            user-code (:user_code body)
            verification-uri (or (:verification_uri body)
                                 (:verification_url body))
            verification-complete (or (:verification_uri_complete body)
                                      (:verification_url_complete body))
            expires-in (or (:expires_in body) 600)
            interval (or (:interval body) 5)]
        (if (and (ok-status? (:status-code response))
                 (string? device-code)
                 (not (str/blank? device-code))
                 (string? user-code)
                 (not (str/blank? user-code))
                 (string? verification-uri)
                 (not (str/blank? verification-uri)))
          {:ok? true
           :device-code device-code
           :user-code user-code
           :verification-uri verification-uri
           :verification-uri-complete verification-complete
           :expires-in (long (if (number? expires-in) expires-in 600))
           :interval (long (if (number? interval) interval 5))}
          {:ok? false
           :error (provider-error response)})))))

(defn authorization-url
  [provider {:keys [redirect-uri state code-challenge scopes]}]
  (let [params (merge {"response_type" "code"
                       "client_id" (:client-id provider)
                       "redirect_uri" redirect-uri
                       "scope" (str/join " " scopes)
                       "state" state}
                      (when code-challenge
                        {"code_challenge" code-challenge
                         "code_challenge_method" "S256"})
                      (:authorization-params provider))
        endpoint (:authorization-endpoint provider)]
    (str endpoint
         (if (str/includes? endpoint "?") "&" "?")
         (form-encode params))))

(defn authorization-code
  [client provider {:keys [code redirect-uri code-verifier]}]
  (token-request client provider
                 (cond-> {:grant_type "authorization_code"
                          :code code
                          :redirect_uri redirect-uri}
                   code-verifier (assoc :code_verifier code-verifier))))

(defn device-token
  [client provider device-code]
  (token-request client
                 (if (:device-client-secret? provider)
                   provider
                   (dissoc provider :client-secret))
                 {:grant_type "urn:ietf:params:oauth:grant-type:device_code"
                  :device_code device-code}))

(defn userinfo
  "Read userinfo with the access token.  Return `{:ok? ... :claims ...}`."
  [client provider access-token]
  (if-let [url (:userinfo-endpoint provider)]
    (let [response (get-json client url access-token)]
      (if-not (:ok? response)
        response
        (let [body (decode-body response)]
          (if (and (ok-status? (:status-code response)) (map? body))
            {:ok? true :claims body}
            {:ok? false :error (provider-error response)}))))
    {:ok? false :error {:code :MODEL_AUTH_USERINFO_UNAVAILABLE
                        :message "provider has no userinfo endpoint"}}))

(defn jwt-claims
  "Decode an ID-token payload without verifying its signature.

  The result is a display and identity hint only.  The caller must still
  validate issuer, audience, and subject rules."
  [id-token]
  (when (and (string? id-token) (not (str/blank? id-token)))
    (let [parts (str/split id-token #"\.")]
      (when (>= (count parts) 2)
        (try
          (let [payload (-> (Base64/getUrlDecoder)
                            (.decode ^String (nth parts 1)))
                value (json/read-str (String. ^bytes payload
                                              StandardCharsets/UTF_8)
                                     :key-fn keyword)]
            (when (map? value) value))
          (catch Exception _ nil))))))

(defn issuer-matches?
  [provider issuer]
  (when (and (string? issuer) (not (str/blank? issuer)))
    (let [issuer (str/replace issuer #"/+$" "")]
      (boolean
       (or (= issuer (some-> (:issuer provider)
                             (str/replace #"/+$" "")))
           (some #(str/starts-with? issuer (str/replace % #"/+$" ""))
                 (:issuer-prefixes provider)))))))

(defn scope-set
  [scope]
  (if (string? scope)
    (->> (str/split scope #"\s+")
         (remove str/blank?)
         set)
    #{}))

(defn revoke
  "Best-effort remote revocation.  A failure never deletes local state."
  [client provider token]
  (if-let [url (:revocation-endpoint provider)]
    (let [params (cond-> {:token token
                          :client_id (:client-id provider)}
                   (:client-secret provider)
                   (assoc :client_secret (:client-secret provider)))
          response (post-form client url params)]
      (if (:ok? response)
        {:ok? (ok-status? (:status-code response))
         :status-code (:status-code response)}
        {:ok? false :error (:error response)}))
    {:ok? false :error {:code :MODEL_AUTH_REVOCATION_UNSUPPORTED}}))

(defn now-ms
  []
  (System/currentTimeMillis))

(defn expires-at-ms
  [obtained-at-ms expires-in]
  (when expires-in
    (+ (long obtained-at-ms) (* 1000 (long expires-in)))))
