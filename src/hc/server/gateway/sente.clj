(ns hc.server.gateway.sente
  "Sente transport for the internal AG-UI gateway.

  This namespace owns the Sente channel socket and its receive loop.  It does
  not decide protocol semantics: every event message is handed to the gateway
  callback, and every outbound event goes through Sente's `send-fn`.

  The web-server adapter is injected.  The gateway therefore works with
  http-kit, Jetty, or the JDK smoke host without owning a server lifecycle."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [hc.server.gateway.auth :as auth]
            [taoensso.sente :as sente]
            [taoensso.sente.interfaces :as sente-i]
            [taoensso.sente.packers.transit :as sente-transit])
  (:import [java.nio.charset StandardCharsets]
           [org.httpkit.server AsyncChannel]))

(def default-max-message-bytes (* 1024 1024))

(def default-worker-count
  "Number of event-handler workers draining the per-uid mailboxes.

  Four workers keep independent connections responsive while bounding the
  threads a stalled handler can occupy; handlers for one uid stay ordered on
  a single mailbox."
  4)

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

(defn- describe-close
  "Build the http-kit `closeRingHandler` callback `(fn [code reason])`.

  http-kit fires both `closeHandler` (keyword status, consumed by Sente) and
  `closeRingHandler` (numeric code + reason string) from the same `onClose`.
  The code distinguishes the close direction: a server `serverClose` reports
  the code it was given (1000 for Sente's normal close), while an abnormal
  disconnect reports 1006."
  []
  ;; http-kit 2.8.0 `AsyncChannel.onClose(int, String)` invokes the ring
  ;; handler with exactly two arguments (code, reason).  An extra channel
  ;; parameter throws ArityException inside the http-kit executor, so the
  ;; diagnostic must match the real call shape.
  (fn [code reason]
    (try
      (println "[hc-ws-close]"
               (pr-str {:code code
                        :reason reason
                        :at (System/currentTimeMillis)}))
      (flush)
      (catch Throwable _ nil))))

(defn- describe-close-status
  "Log the http-kit `:on-close` status keyword.

  `closeRingHandler` reports the numeric WebSocket code, but http-kit may
  already own that slot for its own `ring.websocket` listener.  The status
  keyword is delivered through Sente's `:on-close` callback, so it survives
  that collision and still identifies the close direction:

    :server-close  the server (Sente `sch-close!` or server stop) closed it
    :normal        the client closed it with 1000
    :going-away    the client closed it with 1001
    :unknown       abnormal disconnect (typically 1006)"
  [status at]
  (try
    (println "[hc-ws-close-status]"
             (pr-str {:status status :at at}))
    (flush)
    (catch Throwable _ nil)))

(defn- instrument-adapter
  "Wrap a Sente `IServerChanAdapter` so WebSocket closes are logged.

  Sente's own `on-close` receives only a transport keyword and discards it.
  The http-kit `AsyncChannel` keeps the numeric code and reason and fires
  `closeRingHandler` independently of `closeHandler`, so registering it here
  is additive and leaves Sente's bookkeeping untouched."
  [sch-adapter]
  (reify sente-i/IServerChanAdapter
    (ring-req->server-ch-resp
     [_ ring-req callbacks-map]
     (let [on-close (:on-close callbacks-map)
           callbacks-map (assoc callbacks-map
                                :on-close
                                (fn [sch websocket? status]
                                  (describe-close-status
                                   status (System/currentTimeMillis))
                                  (when on-close
                                    (on-close sch websocket? status))))
           result (sente-i/ring-req->server-ch-resp
                   sch-adapter ring-req callbacks-map)
           channel (:async-channel ring-req)]
       (when (and (:websocket? ring-req)
                  (instance? AsyncChannel channel))
         (try
           (.setCloseRingHandler ^AsyncChannel channel (describe-close))
           (catch IllegalStateException _
             ;; A ring.websocket listener already claimed the slot; the
             ;; status keyword from `:on-close` still records the direction.
             (println "[hc-ws-close]"
                      (pr-str {:code :ring-handler-unavailable
                               :reason "http-kit owns closeRingHandler"
                               :at (System/currentTimeMillis)}))
             (flush))
           (catch Throwable _ nil)))
       result))))

(defn- event-uid
  "Best-effort uid for per-connection ordering.  Sente stamps `:uid` on every
  event message; internal `:chsk/*` messages carry the uid of the connection
  that produced them, or nil for socket-level lifecycle events."
  [event-msg]
  (or (:uid event-msg)
      (let [[ev-id data] (:event event-msg)]
        (when (and (vector? data) (keyword? ev-id)
                   (str/starts-with? (name ev-id) "chsk/"))
          (first data)))))


(defn- worker-index
  "Stable worker partition for UID.  Events for one uid always land on the
  same worker queue, so per-connection ordering survives the pool while a
  stalled handler can only block the uids hashed to its worker."
  [worker-count uid]
  (mod (hash (str uid)) worker-count))

(defn- run-worker!
  "Drain one worker inbox until it closes, then exit.

  `async/<!!` returns nil both for a closed channel and for a nil value;
  Sente never puts nil on an inbox, so nil unambiguously means closed."
  [inbox handler errors]
  (loop []
    (let [event-msg (async/<!! inbox)]
      (when (some? event-msg)
        (try
          (handler event-msg)
          (catch Throwable t
            (log-error! errors t)))
        (recur)))))

(defn- start-receive-pool!
  "Replace the single receive `future` with a dispatcher plus worker pool.

  Sente hands every inbound frame — application events, `:chsk/ws-pong`
  keepalive replies and `:chsk/uidport-*` lifecycle messages — to one
  `ch-recv` channel.  A synchronous handler that stalls (a long commit or a
  full snapshot publish) previously stalled *all* further processing on the
  same thread.  Events are routed by `(hash uid)` to one of
  `worker-count` sliding-buffer inboxes: each connection keeps FIFO order,
  and an inbox that outgrows its buffer drops its own oldest queued event
  instead of blocking the dispatcher or growing memory without bound."
  [socket handler errors stopped? worker-count]
  (let [inbox-size (* 64 worker-count)
        inboxes (mapv (fn [_] (async/chan (async/sliding-buffer inbox-size)))
                      (range worker-count))
        workers (mapv (fn [inbox]
                        (future (run-worker! inbox handler errors)))
                      inboxes)
        dispatcher (future
                     (loop []
                       (when-let [event-msg (async/<!! (:ch-recv socket))]
                         (when-not @stopped?
                           (let [uid (or (event-uid event-msg) :broadcast)
                                 inbox (nth inboxes (worker-index worker-count uid))]
                             (try
                               (async/>!! inbox event-msg)
                               (catch Throwable t
                                 (log-error! errors t)))))
                         (recur))))
        drained (promise)]
    {:socket socket
     :dispatcher dispatcher
     :workers workers
     :inboxes inboxes
     :drained drained}))

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
                (instrument-adapter sch-adapter)
                {:packer bounded-packer
                 :recv-buf-or-n recv-buf-or-n
                 :send-buf-ms-ajax send-buf-ms-ajax
                 :send-buf-ms-ws send-buf-ms-ws
                 :lp-timeout-ms lp-timeout-ms
                 :ws-kalive-ms       (get opts :ws-kalive-ms)
                 :ws-ping-timeout-ms (get opts :ws-ping-timeout-ms)
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
        pool (start-receive-pool! socket handler errors stopped?
                                  default-worker-count)
        drained (:drained pool)]
    (future
      ;; `stop!` closes `ch-recv`; once the dispatcher exits it closes the
      ;; shared inbox, the workers drain their mailboxes, and each worker
      ;; delivers `drained` when its queue is empty.
      (let [drain-ok? (atom true)]
        (try
          @(:dispatcher pool)
          (doseq [inbox (:inboxes pool)]
            (async/close! inbox))
          (let [workers @(:workers pool)]
            (doseq [w workers] @w))
          (catch Throwable t
            (reset! drain-ok? false)
            (log-error! errors t))
          (finally
            (deliver drained @drain-ok?)))))
    {:socket (:socket pool)
     :errors errors
     :stop! (fn []
              (when (compare-and-set! stopped? false true)
                (async/close! (:ch-recv socket)))
              {:ok? true
               :drained? (not= ::timeout (deref drained 5000 ::timeout))})}))
