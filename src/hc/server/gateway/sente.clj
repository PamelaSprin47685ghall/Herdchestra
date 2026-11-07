(ns hc.server.gateway.sente
  "Sente transport for the internal AG-UI gateway.

  This namespace owns the Sente channel socket and its receive loop.  It does
  not decide protocol semantics: every event message is handed to the gateway
  callback, and every outbound event goes through Sente's `send-fn`.

  The web-server adapter is injected.  The gateway therefore works with
  http-kit, Jetty, or the JDK smoke host without owning a server lifecycle."
  (:require [clojure.core.async :as async]
            [hc.server.gateway.auth :as auth]
            [taoensso.sente :as sente]
            [taoensso.sente.interfaces :as sente-i]
            [taoensso.sente.packers.transit :as sente-transit])
  (:import [java.nio.charset StandardCharsets]))

(def default-max-message-bytes (* 1024 1024))

(defn- default-packer
  []
  (sente-transit/get-packer :json))

(defn- packed-size
  [value]
  (cond
    (string? value) (alength (.getBytes ^String value StandardCharsets/UTF_8))
    (bytes? value) (alength ^bytes value)
    :else 0))

(defrecord BoundedPacker [delegate max-bytes]
  sente-i/IPacker2
  (pack [_ websocket? value cb-fn]
    (sente-i/pack
     delegate websocket? value
     (fn [result]
       (if-let [packed (:value result)]
         (let [size (packed-size packed)]
           (if (> size max-bytes)
             (cb-fn {:error (ex-info "Sente message exceeds the configured boundary"
                                     {:code :SENTE_MESSAGE_TOO_LARGE
                                      :size size
                                      :limit max-bytes})})
             (cb-fn result)))
         (cb-fn result)))))
  (unpack [_ websocket? packed cb-fn]
    (sente-i/unpack delegate websocket? packed cb-fn)))

(defn- log-error!
  [sink error]
  (when sink
    (swap! sink conj error)))

(defn start!
  "Start a Sente channel socket and its receive loop.

  Options:
    :sch-adapter       required Sente IServerChanAdapter
    :packer            Sente packer, default Transit/JSON
    :recv-buf-or-n     inbound buffer, default 256
    :send-buf-ms-ajax  Ajax batching window, default 100
    :send-buf-ms-ws    WebSocket batching window, default 30
    :lp-timeout-ms     Ajax long-poll timeout, default 20000
    :user-id-fn        default (fn [ring-req] (:client-id ring-req))
    :csrf-token-fn     default skip; the gateway's auth/authorize origin and
                       Host check is the browser CSRF boundary
    :allowed-origins   policy for the default :authorized?-fn; `:all` means
                       same-origin only in `auth/authorize`
    :sente-allowed-origins  Sente's own duplicate origin check; default :all.
                       Sente rejects requests with no Origin/Referer when
                       this is a set, which would break non-browser clients.
                       The installed :authorized?-fn is the authority.
    :allow-remote?     declare a non-loopback deployment; requires
                       :authorized?-fn or :authorize-request.  Host/Origin
                       are not authentication, so the default policy never
                       relaxes the loopback check.
    :authorized?-fn / :authorize-request  real authentication hooks; when
                       supplied they replace the default loopback policy.
    :handshake-data-fn default protocol version marker
    :on-error          optional atom collecting transport errors

  `handler` receives each Sente event message.  Returns
  `{:socket :stop! :errors}`.  `stop!` closes the receive loop; the
  underlying web server is owned by the caller.  It returns
  `{:ok? true :drained? boolean}`; `:drained? false` means a handler was
  still running when the five-second drain window expired."
  [handler
   {:keys [sch-adapter packer recv-buf-or-n send-buf-ms-ajax send-buf-ms-ws
           lp-timeout-ms user-id-fn csrf-token-fn allowed-origins
           sente-allowed-origins
           handshake-data-fn on-error max-message-bytes authorized?-fn
           authorize-request allow-remote? trusted-hosts]
    :or {recv-buf-or-n 256
         send-buf-ms-ajax 100
         send-buf-ms-ws 30
         lp-timeout-ms 20000
         sente-allowed-origins :all
         max-message-bytes default-max-message-bytes}
    :as opts}]
  (when-not sch-adapter
    (throw (ex-info "gateway requires a Sente server-channel adapter"
                    {:code :SCH_ADAPTER_REQUIRED})))
  (when-not (or (nil? allowed-origins)
                (= :all allowed-origins)
                (set? allowed-origins))
    (throw (ex-info "gateway :allowed-origins must be a set or :all"
                    {:code :ALLOWED_ORIGINS_INVALID
                     :value allowed-origins})))
  (when-not (or (nil? allow-remote?) (boolean? allow-remote?))
    (throw (ex-info "gateway :allow-remote? must be a boolean"
                    {:code :ALLOW_REMOTE_INVALID
                     :value allow-remote?})))
  (when (and (true? allow-remote?)
             (not (ifn? authorized?-fn))
             (not (ifn? authorize-request)))
    (throw (ex-info "gateway remote mode requires an authorization hook"
                    {:code :REMOTE_REQUIRES_AUTH
                     :hint (str "supply :authorized?-fn or "
                                ":authorize-request; Host and Origin are "
                                "not authentication")})))
  (let [errors (or on-error (atom []))
        max-message-bytes (or max-message-bytes default-max-message-bytes)
        base-packer (or packer (default-packer))
        bounded-packer (if (satisfies? sente-i/IPacker2 base-packer)
                         (->BoundedPacker base-packer max-message-bytes)
                         base-packer)
        socket (sente/make-channel-socket!
                sch-adapter
                {:packer bounded-packer
                 :recv-buf-or-n recv-buf-or-n
                 :send-buf-ms-ajax send-buf-ms-ajax
                 :send-buf-ms-ws send-buf-ms-ws
                 :lp-timeout-ms lp-timeout-ms
                 :user-id-fn (or user-id-fn (fn [ring-req] (:client-id ring-req)))
                 :csrf-token-fn (or csrf-token-fn
                                    (fn [_] :sente/skip-CSRF-check))
                 :authorized?-fn (or authorized?-fn
                                     (fn [ring-req]
                                       (auth/authorize
                                        {:authorize-request authorize-request
                                         :allowed-origins allowed-origins
                                         :trusted-hosts trusted-hosts}
                                        ring-req)))
                 :allowed-origins sente-allowed-origins
                 :handshake-data-fn (or handshake-data-fn
                                        (fn [_] {:protocol-version 1}))})
        stopped? (atom false)
        drained (promise)]
    (future
      (loop []
        (when-let [event-msg (async/<!! (:ch-recv socket))]
          (when-not @stopped?
            (try
              (handler event-msg)
              (catch Throwable t
                (log-error! errors t))))
          (recur)))
      (deliver drained true))
    {:socket socket
     :errors errors
     :stop! (fn []
              (when (compare-and-set! stopped? false true)
                (async/close! (:ch-recv socket)))
              {:ok? true
               :drained? (not= ::timeout (deref drained 5000 ::timeout))})}))
