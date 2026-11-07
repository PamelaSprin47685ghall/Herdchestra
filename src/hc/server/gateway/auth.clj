(ns hc.server.gateway.auth
  "Transport authorization for the internal gateway.

  The gateway is an internal control surface.  By default it accepts only
  loopback peers and only same-origin browser requests.  Host and Origin are
  CSRF and DNS-rebinding checks, not authentication: a remote client can
  forge both.  Remote peers are therefore accepted only through a supplied
  `:authorized?-fn` or `:authorize-request`; `:allow-remote? true` without a
  hook is rejected at gateway start rather than treated as authenticated.
  A deployment that owns authentication supplies one of those hooks; the
  hook replaces the default policy and is responsible for every check.

  Two hooks exist because the Sente handshake and ordinary Ring requests are
  different entry points:

    :authorized?-fn   Sente's handshake/POST authorization hook
    :authorize-request ordinary Ring request predicate

  Both receive the Ring request and own every check when supplied.

  Without a hook the default is:

    1. remote address is loopback; `:allow-remote?` never relaxes this
       without an authorization hook;
    2. if the browser sent `Origin` or `Referer`, its origin is the request's
       own trusted Host origin or one of `:allowed-origins`;
    3. a non-browser client with no Origin/Referer is allowed when the peer
       check passes and Host is a trusted local host.

  Host trust is literal: `localhost`, IPv6 loopback and strict 127.0.0.0/8
  addresses.  A DNS name that merely starts with `127.` is not loopback, so a
  rebound page cannot use it as a same-origin Host.

  `:allowed-origins :all` is treated as \"same-origin only\", not as a wildcard.
  This blocks a malicious page on another origin from driving the local
  gateway through a browser, even though the TCP peer is loopback."
  (:require [clojure.string :as str])
  (:import [java.net URI URISyntaxException]))

(defn- header
  [request header-name]
  (let [headers (:headers request)
        header-name (str/lower-case header-name)]
    (or (get headers header-name)
        (some (fn [[k v]]
                (when (= header-name (str/lower-case (name k))) v))
              headers))))

(defn- ipv4-literal
  "Return the four octets of a strict dotted-quad IPv4 literal, or nil."
  [value]
  (when (string? value)
    (when-let [[_ a b c d]
               (re-matches #"([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})"
                           value)]
      (let [octets (mapv #(Long/parseLong %) [a b c d])]
        (when (every? #(<= 0 % 255) octets)
          octets)))))

(defn- ipv4-loopback?
  [value]
  (when-let [[first-octet] (ipv4-literal value)]
    (= 127 first-octet)))

(defn loopback-address?
  "True for `localhost`, IPv6 loopback, and strict IPv4 127.0.0.0/8 literals.

  A hostname such as `127.evil.example` or `127.0.0.1.nip.io` is not a
  loopback address.  Treating a `127.` string prefix as loopback would let a
  DNS-rebound page present a trusted Host and Origin."
  [address]
  (let [address (some-> address str str/trim str/lower-case)]
    (boolean
     (and address
          (or (= "localhost" address)
              (= "::1" address)
              (= "0:0:0:0:0:0:0:1" address)
              (ipv4-loopback? address)
              (and (str/starts-with? address "::ffff:")
                   (let [mapped (subs address (count "::ffff:"))]
                     (or (ipv4-loopback? mapped)
                         (boolean
                          (re-matches #"7f[0-9a-f]{2}:[0-9a-f]{1,4}"
                                      mapped))))))))))

(defn- scheme-name
  [scheme]
  (cond
    (keyword? scheme) (name scheme)
    (string? scheme) scheme
    :else (some-> scheme str)))

(defn- strip-default-port
  [scheme authority]
  (let [default-port (case scheme "http" ":80" "https" ":443" nil)]
    (if (and default-port (str/ends-with? authority default-port))
      (subs authority 0 (- (count authority) (count default-port)))
      authority)))

(defn- canonical-origin
  [scheme authority]
  (let [scheme (some-> (scheme-name scheme) str/trim str/lower-case)
        authority (some-> authority str str/trim str/lower-case)]
    (when (and (seq scheme) (seq authority))
      (str scheme "://" (strip-default-port scheme authority)))))

(defn origin-of
  "Return the canonical `scheme://host[:port]` origin for VALUE, or nil when
  VALUE is blank or not an absolute URL with an authority.

  The result is lower-case and omits the default http `:80` / https `:443`
  port.  Configuration validation uses this same parser so an allowed origin
  cannot mean one thing at startup and another at request time."
  [value]
  (when (and (string? value) (not (str/blank? value)))
    (try
      (let [uri (URI. value)]
        (when (and (.getScheme uri) (.getAuthority uri))
          (canonical-origin (.getScheme uri) (.getAuthority uri))))
      (catch URISyntaxException _ nil))))

(defn- origin-state
  "Classify the browser origin headers.

  A present but malformed `Origin` or `Referer` is rejected rather than
  treated as an absent header.  `Origin: null` is a browser request from a
  sandboxed/file context and is rejected."
  [request]
  (let [origin (header request "origin")
        referer (header request "referer")]
    (cond
      (some? origin)
      (let [origin (str/trim (str origin))]
        (cond
          (str/blank? origin) {:present? true :invalid? true}
          (= "null" (str/lower-case origin)) {:present? true :invalid? true}
          :else (if-let [parsed (origin-of origin)]
                  {:present? true :origin parsed}
                  {:present? true :invalid? true})))

      (some? referer)
      (if-let [parsed (origin-of (str/trim (str referer)))]
        {:present? true :origin parsed}
        {:present? true :invalid? true})

      :else
      {:present? false})))

(defn- host-name
  [host]
  (let [host (some-> host str str/lower-case)]
    (cond
      (nil? host) nil
      (not= host (str/trim host)) nil
      (str/includes? host "@") nil
      (str/starts-with? host "[")
      (second (re-matches #"\[([0-9a-f:.]+)\](?::[0-9]{1,5})?" host))
      :else
      (when-let [[_ name]
                 (re-matches #"([a-z0-9.-]+)(?::[0-9]{1,5})?" host)]
        name))))

(defn- trusted-host?
  [request trusted-hosts]
  (let [host (host-name (header request "host"))
        trusted (if (coll? trusted-hosts)
                  (into #{} (keep #(some-> % str str/trim str/lower-case))
                        trusted-hosts)
                  #{})]
    (boolean
     (and host
          (or (contains? trusted host)
              (loopback-address? host))))))

(defn- own-origin
  [request trusted-hosts]
  (when (trusted-host? request trusted-hosts)
    (let [host (some-> (header request "host") str/lower-case)
          scheme (or (:scheme request) "http")]
      (canonical-origin scheme host))))

(defn- allowed-origin?
  [request origin allowed-origins trusted-hosts]
  (let [allowed (if (set? allowed-origins)
                  (into #{} (keep #(origin-of (str %))) allowed-origins)
                  #{})]
    (and (some? origin)
         (or (contains? allowed origin)
             (= origin (own-origin request trusted-hosts))))))

(defn browser-origin-ok?
  "True when a browser-originated request comes from the request's own trusted
  local origin or an explicitly allowed origin.  Requests with no
  Origin/Referer are treated as non-browser clients and pass only when the
  Host is a trusted local host; otherwise a DNS-rebound page could read
  loopback GET routes without an Origin header.  A present null or malformed
  origin is rejected.  DNS rebinding is blocked because a same-origin match
  also requires a trusted local Host; Host trust accepts only literal loopback
  names and addresses."
  [request allowed-origins trusted-hosts]
  (let [{:keys [present? origin invalid?]} (origin-state request)]
    (cond
      invalid? false
      (not present?) (trusted-host? request trusted-hosts)
      :else (allowed-origin? request origin allowed-origins trusted-hosts))))

(defn authorize
  "Authorize one Ring request.

  Precedence: `:authorized?-fn`, then `:authorize-request`, then the default
  policy.  The default policy accepts loopback peers only.  `:allow-remote?`
  is a declaration checked by the gateway start path; it never relaxes the
  default policy, because Host and Origin are not authentication.  A remote
  deployment must supply a hook that performs real authentication.  A missing
  or blank remote address is denied by the default policy because the
  transport did not establish a peer."
  [{:keys [authorized?-fn authorize-request allowed-origins trusted-hosts]}
   request]
  (let [remote (:remote-addr request)]
    (cond
      (ifn? authorized?-fn) (boolean (authorized?-fn request))
      (ifn? authorize-request) (boolean (authorize-request request))
      :else
      (and (some? remote)
           (not (str/blank? (str remote)))
           (loopback-address? remote)
           (browser-origin-ok? request
                               (or allowed-origins :all)
                               (or trusted-hosts #{}))))))

(defn forbidden
  "The uniform 403 response for a rejected gateway request."
  [request]
  {:status 403
   :headers {"content-type" "application/edn; charset=utf-8"
             "cache-control" "no-store"}
   :body (pr-str {:error :FORBIDDEN
                  :message "gateway request is not authorized"
                  :remote-addr (:remote-addr request)})})

(defn wrap
  "Ring middleware for application-owned routes.

  Mount this around `/attachments`, `/api/*`, `/speech` and any other route
  that accepts commands or bytes.  It uses the same policy as the gateway."
  [handler opts]
  (fn [request]
    (if (authorize opts request)
      (handler request)
      (forbidden request))))
