(ns hc.server.history-regenerate-live-test
  "Live app regeneration through a real local OpenAI-compatible HTTP stub.

  Starts a tiny JDK HTTP server, starts the real application with a model
  connection pointing at it, submits :history/regenerate through the gateway
  router, waits for the terminal, and verifies a version was appended to the
  target message with no second assistant message."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.config :as config]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.app.system :as system]
            [hc.server.gateway.ports :as gateway-ports]
            [hc.server.history :as history]
            [hc.server.history.message :as message]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(def ^:private body
  "Stub regenerated answer.")

(defn- send-sse!
  [^HttpExchange exchange]
  (let [response (.getResponseBody exchange)
        chunks [body]
        write! (fn [text]
                 (let [bytes (.getBytes text StandardCharsets/UTF_8)]
                   (.write response bytes 0 (alength bytes))
                   (.flush response)))]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" "text/event-stream")
      (.set "Cache-Control" "no-cache")
      (.set "Connection" "keep-alive"))
    (.sendResponseHeaders exchange 200 0)
    (doseq [chunk chunks]
      (write! (str "data: {\"id\":\"stub\",\"object\":\"chat.completion.chunk\","
                   "\"choices\":[{\"index\":0,\"delta\":{\"content\":"
                   (pr-str chunk) "},\"finish_reason\":null}]}\n\n")))
    (write! (str "data: {\"id\":\"stub\",\"object\":\"chat.completion.chunk\","
                 "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"))
    (write! "data: [DONE]\n\n")
    (.close response)))

(defn- send-json!
  [^HttpExchange exchange]
  (let [text (str "{\"id\":\"stub\",\"object\":\"chat.completion\","
                  "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                  "\"content\":" (pr-str body) "},\"finish_reason\":\"stop\"}],"
                  "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,"
                  "\"total_tokens\":3}}")
        bytes (.getBytes text StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" "application/json"))
    (.sendResponseHeaders exchange 200 (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes 0 (alength bytes)))))

(defn- start-stub!
  []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (try
                      (let [request (String. (.readAllBytes (.getRequestBody exchange))
                                             StandardCharsets/UTF_8)]
                        (if (re-find #"\"stream\"\s*:\s*true" request)
                          (send-sse! exchange)
                          (send-json! exchange)))
                      (finally (.close exchange)))))
        models (reify HttpHandler
                 (handle [_ exchange]
                   (let [text "{\"object\":\"list\",\"data\":[{\"id\":\"test\",\"object\":\"model\"}]}"
                         bytes (.getBytes text StandardCharsets/UTF_8)]
                     (doto (.getResponseHeaders exchange)
                       (.set "Content-Type" "application/json"))
                     (.sendResponseHeaders exchange 200 (alength bytes))
                     (with-open [out (.getResponseBody exchange)]
                       (.write out bytes 0 (alength bytes)))
                     (.close exchange))))]
    (.createContext server "/v1/chat/completions" handler)
    (.createContext server "/chat/completions" handler)
    (.createContext server "/v1/models" models)
    (.createContext server "/models" models)
    (.setExecutor server nil)
    (.start server)
    server))

(defn- test-config
  [data static port]
  (config/normalize
   {:host "127.0.0.1"
    :port 0
    :data-dir data
    :workspace-root (str data "/workspace")
    :static-root static
    :blob-root (str data "/blobs")
    :worktree-root (str data "/worktrees")
    :artifact-root (str data "/artifacts")
    :storage {:path (str data "/db")}
    :log-level :warn
    :models {:connections [{:id "local"
                            :name "Stub"
                            :kind :openai-compatible
                            :base-url (str "http://127.0.0.1:" port "/v1")
                            :api-key "test"
                            :models [{:id "test"
                                      :capabilities #{:streaming}}]}]
             :roles {:main {:connection "local" :model "test"}
                     :task {:connection "local" :model "test"}}}}))

(defn- seed-session!
  [db-path]
  (let [store (storage/open-store {:path db-path :create? true})
        persistence (session-persistence/persistence store)
        created (sessions/new-session
                 {:session-id "regen-session"
                  :owner-id "regen-owner"
                  :now 1000
                  :id-source (ids/counter-source {:prefix "regen"})})
        session (:session created)
        m1 (:message (message/new-message "m1" :user "question" {:now 1100}))
        m2 (:message (message/new-message "m2" :assistant "old answer"
                                          {:now 1200}))]
    (is (= :committed
           (:status
            (sessions/commit-session!
             persistence
             (-> session
                 (assoc :model-binding {:connection-id "local"
                                        :model-entry-id "test"})
                 (assoc-in [:messages "m1"] m1)
                 (assoc-in [:messages "m2"] m2)
                 (assoc :message-order ["m1" "m2"])
                 (assoc :activity-at 1200))))))
    (storage/close-store! store)))

(defn- wait-for-version!
  [owner timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [live (get-in ((:snapshot owner)) [:state :session])
            versions (message/message-history live "m2")]
        (if (or (= 2 (count versions))
                (>= (System/currentTimeMillis) deadline))
          live
          (do (Thread/sleep 100) (recur)))))))

(deftest regenerate-uses-real-model-http-terminal
  (let [data (support/temp-dir "hc-regen-live-")
        static (support/temp-dir "hc-regen-live-static-")
        stub (start-stub!)]
    (try
      (spit (str static "/index.html") "<!doctype html><title>hc</title>")
      (seed-session! (str data "/db"))
      (let [system (system/start! (test-config data static
                                               (.getPort (.getAddress stub))))]
        (try
          (is (false? (:blocked? system)))
          (let [owner (get @(:owners system) "regen-session")
                epoch (get-in ((:snapshot owner))
                              [:state :commands :command-epoch])
                receipt (gateway-ports/submit-command!
                         (:router system)
                         {:command-id "regen-c1"
                          :command-epoch epoch
                          :payload {:kind :history/regenerate
                                    :session-id "regen-session"
                                    :message-id "m2"}}
                         nil)
                live (wait-for-version! owner 15000)
                versions (message/message-history live "m2")
                run-id (get-in receipt [:receipt :run-id])
                run (get-in live [:runs run-id])
                call-id (get-in run [:call-order 0])
                call (get-in live [:calls call-id])
                attempt (get-in live [:attempts (:current-attempt-id call)])]
            (is (:ok? receipt) (pr-str receipt))
            (println "REGEN-DIAG"
                     (pr-str {:run run
                              :call (select-keys call [:state :result :last-error])
                              :attempt (select-keys attempt
                                                    [:state :terminal
                                                     :late-terminal])}))
            (testing "the real HTTP model terminal appended one version"
              (is (= 2 (count versions)))
              (is (= "old answer" (get-in versions [0 :content])))
              (is (= body (get-in versions [1 :content])))
              (is (= :regenerated (get-in versions [1 :source])))
              (is (= 1 (get-in live [:messages "m2" :current-version])))
              (is (= 2 (count (:message-order live)))))
            (testing "the run is completed and has no second assistant message"
              (is (= :completed (get-in live [:runs run-id :state]))))
            (is (empty? (history/check-invariants live))))
          (finally (system/stop! system))))
      (finally
        (.stop stub 0)
        (support/delete-tree! data)
        (support/delete-tree! static)))))
