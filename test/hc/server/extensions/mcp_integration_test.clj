(ns hc.server.extensions.mcp-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hc.server.extensions :as ext]
            [hc.server.extensions.mcp.json :as json]
            [hc.server.extensions.support :as support])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private stub-dir (atom nil))

(defn- once-fixture
  [f]
  (let [dir (support/temp-dir)]
    (try
      (support/compile-stub! dir)
      (reset! stub-dir dir)
      (f)
      (finally
        (support/delete-tree dir)
        (reset! stub-dir nil)))))

(use-fixtures :once once-fixture)

(defn- add-server!
  [catalog config]
  (ext/add-mcp-server! catalog config))

(deftest stdio-probe-lists-tools-without-executing-them
  (support/with-temp-dir [dir]
    (let [marker (str dir "/called")
          command (apply support/stub-command @stub-dir ["--marker" marker])
          normalized (ext/normalize-mcp-server {:name "stub"
                                                :type :stdio
                                                :command (first command)
                                                :args (vec (rest command))})
          added (add-server! (ext/empty-catalog) (:server-config normalized))
          id (get-in added [:server :id])
          probed (ext/probe-mcp-server! (:catalog added) id {:timeout-ms 4000})
          server (get-in probed [:catalog :mcp :servers id])]
      (is (:ok? normalized))
      (is (:ok? probed))
      (is (= :connected (get-in probed [:probe :status])))
      (is (= #{"echo" "write_file" "fail_tool"} (set (map #(json/field % :name) (get-in probed [:probe :tools])))))
      (is (= :connected-not-enabled (:state (ext/mcp-server-view server))))
      (is (false? (get-in server [:tools "echo" :enabled?])))
      (is (not (.exists (java.io.File. marker))))
      (testing "enabling a tool still performs no call"
        (let [enabled-server (ext/enable-mcp-server! (:catalog probed) id true)
              enabled-tool (ext/set-mcp-tool-enabled! (:catalog enabled-server) id "echo" true
                                                       {:approve-review? true})
              resolved (ext/resolve-mcp-tools (:catalog enabled-tool)
                                              {:assistant-servers #{id}})]
          (is (= ["echo"] (mapv :name (:tools resolved))))
          (is (not (.exists (java.io.File. marker)))))))))

(deftest stdio-timeout-is-reported-and-process-is-closed
  (let [command (apply support/stub-command @stub-dir ["--hang"])
        normalized (ext/normalize-mcp-server {:name "hang"
                                              :type :stdio
                                              :command (first command)
                                              :args (vec (rest command))})
        added (add-server! (ext/empty-catalog) (:server-config normalized))
        id (get-in added [:server :id])
        probed (ext/probe-mcp-server! (:catalog added) id {:timeout-ms 200})]
    (is (= :failed (get-in probed [:probe :status])))
    (is (= :initialize (get-in probed [:probe :stage])))
    (is (= :MCP_TIMEOUT (get-in probed [:probe :error :code])))))

(deftest stdio-start-failure-is-reported
  (let [normalized (ext/normalize-mcp-server {:name "missing"
                                              :type :stdio
                                              :command "/definitely/not/a/real/program"})
        added (add-server! (ext/empty-catalog) (:server-config normalized))
        id (get-in added [:server :id])
        probed (ext/probe-mcp-server! (:catalog added) id {:timeout-ms 1000})]
    (is (= :failed (get-in probed [:probe :status])))
    (is (= :connect (get-in probed [:probe :stage])))
    (is (= :MCP_PROCESS_START (get-in probed [:probe :error :code])))))

(defn- json-response!
  [exchange status body]
  (let [bytes (.getBytes (json/write-str body) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- mcp-result
  [method]
  (case method
    "initialize" {"protocolVersion" "2024-11-05"
                  "capabilities" {"tools" {}}
                  "serverInfo" {"name" "http-stub" "version" "1"}}
    "tools/list" {"tools" [{"name" "lookup"
                            "description" "Look up"
                            "inputSchema" {"type" "object"}}]}
    {}))

(defn- start-http-server!
  [requests]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (let [path (.getPath (.getRequestURI exchange))
                          body (String. (.readAllBytes (.getRequestBody exchange))
                                        StandardCharsets/UTF_8)]
                      (if (= path "/auth")
                        (json-response! exchange 401 {"error" "unauthorized"})
                        (let [message (json/read-str body)
                              method (json/field message :method)
                              id (json/field message :id)]
                          (swap! requests conj message)
                          (json-response! exchange 200
                                          {"jsonrpc" "2.0"
                                           "id" id
                                           "result" (mcp-result method)}))))))]
    (.createContext server "/mcp" handler)
    (.createContext server "/auth" handler)
    (.start server)
    {:server server
     :port (.getPort (.getAddress server))}))

(deftest http-probe-reads-tools-and-reports-auth
  (let [requests (atom [])
        {:keys [server port]} (start-http-server! requests)]
    (try
      (let [normalized (ext/normalize-mcp-server
                        {:name "http"
                         :type :http
                         :url (str "http://127.0.0.1:" port "/mcp")})
            added (add-server! (ext/empty-catalog) (:server-config normalized))
            id (get-in added [:server :id])
            probed (ext/probe-mcp-server! (:catalog added) id {:timeout-ms 4000})]
        (is (= :connected (get-in probed [:probe :status])))
        (is (= ["lookup"] (mapv #(json/field % :name) (get-in probed [:probe :tools]))))
        (is (= ["initialize" "notifications/initialized" "tools/list"]
               (mapv #(json/field % :method) @requests)))
        (is (not-any? #(= "tools/call" (json/field % :method)) @requests)))
      (testing "401 becomes needs-login, not a generic failure"
        (let [normalized (ext/normalize-mcp-server
                          {:name "auth"
                           :type :http
                           :url (str "http://127.0.0.1:" port "/auth")})
              added (add-server! (ext/empty-catalog) (:server-config normalized))
              id (get-in added [:server :id])
              probed (ext/probe-mcp-server! (:catalog added) id {:timeout-ms 4000})]
          (is (= :needs-login (get-in probed [:probe :status])))
          (is (= :MCP_AUTH_REQUIRED (get-in probed [:probe :error :code])))))
      (finally
        (.stop server 0)))))

(defn- start-sse-server!
  [requests]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        out (atom nil)
        sse-handler (reify HttpHandler
                      (handle [_ exchange]
                        (let [stream (.getResponseBody exchange)]
                          (reset! out stream)
                          (.set (.getResponseHeaders exchange)
                                "Content-Type" "text/event-stream")
                          (.sendResponseHeaders exchange 200 0)
                          (.write stream (.getBytes "event: endpoint\ndata: /message\n\n"
                                                    StandardCharsets/UTF_8))
                          (.flush stream))))
        message-handler (reify HttpHandler
                          (handle [_ exchange]
                            (let [body (String. (.readAllBytes (.getRequestBody exchange))
                                                StandardCharsets/UTF_8)
                                  message (json/read-str body)
                                  method (json/field message :method)
                                  id (json/field message :id)
                                  response (json/write-str
                                            {"jsonrpc" "2.0"
                                             "id" id
                                             "result" (mcp-result method)})]
                              (swap! requests conj message)
                              (when-let [stream @out]
                                (.write stream (.getBytes (str "event: message\ndata: "
                                                               response "\n\n")
                                                          StandardCharsets/UTF_8))
                                (.flush stream))
                              (.sendResponseHeaders exchange 202 -1)
                              (.close exchange))))]
    (.createContext server "/sse" sse-handler)
    (.createContext server "/message" message-handler)
    (.start server)
    {:server server
     :port (.getPort (.getAddress server))}))

(deftest sse-probe-reads-tools
  (let [requests (atom [])
        {:keys [server port]} (start-sse-server! requests)]
    (try
      (let [normalized (ext/normalize-mcp-server
                        {:name "sse"
                         :type :sse
                         :url (str "http://127.0.0.1:" port "/sse")})
            added (add-server! (ext/empty-catalog) (:server-config normalized))
            id (get-in added [:server :id])
            probed (ext/probe-mcp-server! (:catalog added) id {:timeout-ms 4000})]
        (is (= :connected (get-in probed [:probe :status])))
        (is (= ["lookup"] (mapv #(json/field % :name) (get-in probed [:probe :tools]))))
        (is (= ["initialize" "notifications/initialized" "tools/list"]
               (mapv #(json/field % :method) @requests))))
      (finally
        (.stop server 0)))))
