(ns hc.server.gateway.jdk-http
  "JDK-only HTTP host for development and the real HTTP smoke test.

  This is not the production web server.  It supports Sente's Ajax transport
  (handshake, POST and long-poll) and ordinary HTTP blob routes.  It uses only
  `com.sun.net.httpserver` from the JDK, so the gateway can be exercised
  end-to-end without adding a dependency to `deps.edn`.

  Production deployments inject Sente's http-kit or Jetty adapter instead."
  (:require [clojure.string :as str]
            [taoensso.sente.interfaces :as sente-i])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io InputStream OutputStream]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent ExecutorService Executors]))

;; ---------------------------------------------------------------------------
;; Deferred Sente response body

(defprotocol IDeferredBody
  (-await! [body]))

(defrecord DeferredSenteBody [response_ timeout-ms]
  IDeferredBody
  (-await! [_]
    (deref response_ timeout-ms nil)))

;; ---------------------------------------------------------------------------
;; One request = one Sente server channel

(defn- close-once!
  [closed?_ f]
  (when (compare-and-set! closed?_ false true)
    (f)))

(deftype OneShotChannel [response_ open?_ closed?_ on-close]
  sente-i/IServerChan
  (sch-open? [_] @open?_)
  (sch-close! [this]
    (let [was-open? (compare-and-set! open?_ true false)]
      (when was-open? (deliver response_ nil))
      (close-once! closed?_ #(when on-close (on-close this false nil)))
      was-open?))
  (sch-send! [this _websocket? msg]
    (if (compare-and-set! open?_ true false)
      (do
        (deliver response_ msg)
        (close-once! closed?_ #(when on-close (on-close this false nil)))
        true)
      false)))

(deftype JdkServerChanAdapter [timeout-ms]
  sente-i/IServerChanAdapter
  (ring-req->server-ch-resp [_ _ring-req callbacks-map]
    (let [{:keys [on-open on-close]} callbacks-map
          response (promise)
          channel (OneShotChannel. response (atom true) (atom false) on-close)]
      (when on-open (on-open channel false))
      {:status 200
       :headers {"content-type" "text/plain; charset=utf-8"}
       :body (->DeferredSenteBody response timeout-ms)})))

(defn get-sch-adapter
  "Return a Sente adapter for the JDK Ajax transport.

  Options: `:timeout-ms`, slightly longer than Sente's long-poll timeout so
  Sente can send its own `:chsk/timeout` message first."
  ([] (get-sch-adapter {}))
  ([{:keys [timeout-ms] :or {timeout-ms 25000}}]
   (JdkServerChanAdapter. timeout-ms)))

;; ---------------------------------------------------------------------------
;; Ring response rendering

(defn- bytes-of
  [body]
  (cond
    (nil? body) nil
    (string? body) (.getBytes ^String body StandardCharsets/UTF_8)
    (instance? (Class/forName "[B") body) body
    (sequential? body) (bytes-of (apply str body))
    :else (bytes-of (str body))))

(defn- copy-stream!
  [^InputStream in ^OutputStream out]
  (with-open [in in]
    (.transferTo in out)))

(defn- header-value
  [headers name]
  (or (get headers name)
      (some (fn [[k v]] (when (= (str/lower-case (name k))
                                 (str/lower-case name))
                          v))
            headers)))

(defn- send-response!
  [^HttpExchange exchange request response]
  (let [head? (= :head (:request-method request))
        status (int (or (:status response) 200))
        headers (or (:headers response) {})
        body (:body response)
        body (if (satisfies? IDeferredBody body) (-await! body) body)
        response-headers (.getResponseHeaders exchange)]
    (doseq [[k v] headers]
      (.set response-headers (name k) (str v)))
    (cond
      (and (not head?) (instance? InputStream body))
      (let [length (some-> (header-value headers "content-length")
                           parse-long)]
        (.sendResponseHeaders exchange status (if length length 0))
        (copy-stream! body (.getResponseBody exchange)))

      (or head? (nil? body) (contains? #{204 304} status))
      (do
        (when (instance? InputStream body)
          (.close ^InputStream body))
        (when (and (not head?) (not (contains? #{204 304} status)))
          (.set response-headers "content-length" "0"))
        (.sendResponseHeaders exchange status -1))

      :else
      (let [^bytes bytes (bytes-of body)]
        (.set response-headers "content-length" (str (alength bytes)))
        (.sendResponseHeaders exchange status (alength bytes))
        (with-open [out (.getResponseBody exchange)]
          (.write out bytes))))))

;; ---------------------------------------------------------------------------
;; Ring request construction

(defn- request-headers
  [^HttpExchange exchange]
  (into {}
        (for [[k values] (.getRequestHeaders exchange)]
          [(str/lower-case k) (first values)])))

(defn- ring-request
  [^HttpExchange exchange]
  (let [uri (.getRequestURI exchange)
        method (keyword (str/lower-case (.getRequestMethod exchange)))
        local (.getLocalAddress exchange)
        remote (.getRemoteAddress exchange)]
    {:request-method method
     :uri (or (.getRawPath uri) "/")
     :query-string (.getRawQuery uri)
     :headers (request-headers exchange)
     :body (when (contains? #{:post :put :patch} method)
             (.getRequestBody exchange))
     :scheme :http
     :server-name (.getHostString local)
     :server-port (.getPort local)
     :remote-addr (some-> remote .getAddress .getHostAddress)
     :websocket? false}))

(defn- server-error
  [t]
  {:status 500
   :headers {"content-type" "application/edn; charset=utf-8"
             "cache-control" "no-store"}
   :body (pr-str {:error :JDK_HTTP_FAILURE
                  :message (or (.getMessage ^Throwable t) "request failed")})})

(defn- serve!
  [handler ^HttpExchange exchange]
  (try
    (let [request (ring-request exchange)
          response (handler request)
          response (if (map? response)
                     response
                     (server-error
                      (ex-info "Ring handler returned a non-map response"
                               {:response response})))]
      (send-response! exchange request response))
    (catch Throwable t
      (try
        (send-response! exchange
                        {:request-method :get}
                        (server-error t))
        (catch Throwable _ nil)))
    (finally
      (.close exchange))))

;; ---------------------------------------------------------------------------
;; Server lifecycle

(defn start!
  "Start a JDK HTTP server.

  Options:
    :handler   required Ring handler
    :port      0 for an ephemeral port (default)
    :host      127.0.0.1 by default
    :executor  optional ExecutorService; otherwise a cached pool is owned

  Returns `{:server :port :stop!}`."
  [{:keys [handler port host executor]
    :or {port 0 host "127.0.0.1"}}]
  (when-not handler
    (throw (ex-info "JDK HTTP host requires a Ring handler"
                    {:code :HANDLER_REQUIRED})))
  (let [server (HttpServer/create (InetSocketAddress. host (int port)) 0)
        owned-executor? (nil? executor)
        executor (or executor (Executors/newCachedThreadPool))]
    (.setExecutor server ^ExecutorService executor)
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (serve! handler exchange))))
    (.start server)
    {:server server
     :port (.getPort (.getAddress server))
     :executor executor
     :stop! (fn []
              (.stop server 0)
              (when owned-executor?
                (.shutdownNow ^ExecutorService executor))
              {:ok? true})}))
