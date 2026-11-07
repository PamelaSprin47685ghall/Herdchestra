(ns hc.server.search.support
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hc.server.search :as search])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn response!
  [^HttpExchange exchange status body content-type]
  (let [bytes (.getBytes (str body) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "content-type" content-type)
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn json-response!
  [exchange status value]
  (response! exchange status (json/write-str value) "application/json"))

(defn request-body
  [^HttpExchange exchange]
  (String. (.readAllBytes (.getRequestBody exchange))
           StandardCharsets/UTF_8))

(defn request-header
  [^HttpExchange exchange name]
  (.getFirst (.getRequestHeaders exchange) name))

(defn- request-record
  [^HttpExchange exchange]
  (let [uri (.getRequestURI exchange)]
    {:method (.getRequestMethod exchange)
     :path (.getPath uri)
     :query (.getRawQuery uri)
     :headers (into {}
                    (map (fn [[name values]]
                           [(str/lower-case (str name)) (vec values)]))
                    (.getRequestHeaders exchange))}))

(defn start-server
  "Start a loopback HTTP server.

  ROUTES maps a path to `(fn [exchange state] ...)`.  STATE is an atom with
  `:requests` and any test-specific keys.  Returns a handle with `:port`,
  `:base`, `:state` and `:server`."
  [routes]
  (let [state (atom {:requests []})
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (try
           (let [record (request-record exchange)
                 handler (get routes (:path record))]
             (swap! state update :requests conj record)
             (if handler
               (handler exchange state)
               (response! exchange 404 "not found" "text/plain")))
           (finally
             (.close exchange))))))
    (.start server)
    (let [port (.getPort (.getAddress server))]
      {:server server
       :port port
       :base (str "http://127.0.0.1:" port)
       :state state})))

(defn stop!
  [server]
  (.stop (:server server) 0))

(defn url
  [server path]
  (str (:base server) path))

(defn requests
  [server]
  (:requests @(:state server)))

(defn request-count
  [server]
  (count (requests server)))

(defn reset-requests!
  [server]
  (swap! (:state server) assoc :requests []))

(defn search-service
  [config & [options]]
  (search/service
   (merge {:config config
           :env {}
           :clock (constantly "2026-11-03T10:00:00Z")
           :allow-private-hosts? true}
          options)))

(defn searx-service
  [base-url & [options]]
  (merge {:id "searx"
          :name "SearXNG"
          :kind :searxng
          :base-url (str base-url "/search")
          :enabled? true
          :timeout-ms 3000}
         options))

(defn brave-service
  [base-url & [options]]
  (merge {:id "brave"
          :name "Brave"
          :kind :brave
          :base-url (str base-url "/search")
          :enabled? true
          :timeout-ms 3000
          :credentials {:api-key "brave-secret-key"}}
         options))

(defn tavily-service
  [base-url & [options]]
  (merge {:id "tavily"
          :name "Tavily"
          :kind :tavily
          :base-url (str base-url "/search")
          :enabled? true
          :timeout-ms 3000
          :credentials {:api-key "tavily-secret-key"}
          :preferences {:include-raw-content? true}}
         options))

(defn query
  ([id text] {:query-id id :text text})
  ([id text extra] (merge {:query-id id :text text} extra)))
