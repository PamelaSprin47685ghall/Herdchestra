(ns hc.server.extensions.mcp-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.extensions.mcp.config :as config]))

(defn- error-code
  [result]
  (get-in result [:error :code]))

(deftest normalizes-three-transport-shapes
  (testing "stdio keeps program and arguments separate"
    (let [result (config/normalize {:name "local"
                                    :type "stdio"
                                    :command "python3"
                                    :args ["-m" "server"]
                                    :env {"TOKEN" "secret"}
                                    :cwd "/tmp"})]
      (is (:ok? result))
      (is (= {:type :stdio
              :command "python3"
              :args ["-m" "server"]
              :env {"TOKEN" "secret"}
              :cwd "/tmp"}
             (get-in result [:server-config :transport])))
      (is (:has-secrets? result))))
  (testing "HTTP"
    (let [result (config/normalize {:name "remote"
                                    :url "https://example.test/mcp"
                                    :headers {"Authorization" "Bearer x"}})]
      (is (:ok? result))
      (is (= :http (get-in result [:server-config :transport :type])))
      (is (:has-secrets? result))))
  (testing "SSE"
    (let [result (config/normalize {:name "events"
                                    :type :sse
                                    :url "http://127.0.0.1:1234/sse"})]
      (is (:ok? result))
      (is (= :sse (get-in result [:server-config :transport :type]))))))

(deftest refuses-invalid-configuration
  (testing "blank name"
    (is (= :EXTENSION_NAME_REQUIRED
           (error-code (config/normalize {:name " " :command "x"})))))
  (testing "missing transport"
    (is (= :MCP_TRANSPORT_REQUIRED
           (error-code (config/normalize {:name "x"})))))
  (testing "URL whitespace"
    (is (= :MCP_URL_INVALID
           (error-code (config/normalize {:name "x"
                                          :url "https://example.test/a b"})))))
  (testing "non-http scheme"
    (is (= :MCP_URL_INVALID
           (error-code (config/normalize {:name "x" :url "file:///tmp/x"})))))
  (testing "header line break"
    (is (= :MCP_HEADER_INVALID
           (error-code (config/normalize {:name "x"
                                          :url "https://example.test"
                                          :headers {"X-Test" "a\r\nb"}})))))
  (testing "relative working directory"
    (is (= :MCP_CWD_INVALID
           (error-code (config/normalize {:name "x"
                                          :command "x"
                                          :cwd "relative"})))))
  (testing "arguments must be separate"
    (is (= :MCP_ARGS_INVALID
           (error-code (config/normalize {:name "x"
                                          :command "x"
                                          :args "a b"}))))))

(deftest warns-without-executing-shell-syntax
  (let [result (config/normalize {:name "x" :command "npx -y pkg"})]
    (is (:ok? result))
    (is (= "npx -y pkg" (get-in result [:server-config :transport :command])))
    (is (= [] (get-in result [:server-config :transport :args])))
    (is (some #(= :MCP_COMMAND_NOT_SPLIT (:code %)) (:warnings result)))))

(deftest reports-unknown-fields-without-copying-them
  (let [result (config/normalize {:name "x" :command "x" :mystery 42})]
    (is (:ok? result))
    (is (= [:mystery] (:unknown-fields result)))
    (is (not (contains? (get-in result [:server-config :transport]) :mystery)))))

(deftest public-view-masks-credentials
  (let [normalized (config/normalize {:name "remote"
                                      :command "server"
                                      :env {"API_KEY" "env-secret"
                                            "PLAIN" "visible"}})
        public (config/public-view (:server-config normalized))]
    (is (= "***" (get-in public [:transport :env "API_KEY"])))
    (is (= "visible" (get-in public [:transport :env "PLAIN"])))
    (is (:has-secrets? public))
    (is (not (re-find #"Bearer secret|env-secret|user:pass"
                      (pr-str public))))))

(deftest url-credentials-are-reported-and-redacted
  (let [normalized (config/normalize {:name "remote"
                                      :url "https://user:pass@example.test/mcp"})
        public (config/public-view (:server-config normalized))]
    (is (:has-secrets? normalized))
    (is (:has-secrets? public))
    (is (not (re-find #"user:pass" (pr-str public))))
    (is (= "https://***@example.test/mcp" (get-in public [:transport :url])))))

(deftest public-view-masks-secret-looking-arguments
  (let [normalized (config/normalize {:name "x"
                                      :command "tool"
                                      :args ["--token=abc" "--plain"]})
        public (config/public-view (:server-config normalized))]
    (is (= ["--token=***" "--plain"] (get-in public [:transport :args])))
    (is (:has-secrets? public))))
