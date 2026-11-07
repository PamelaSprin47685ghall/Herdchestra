(ns hc.server.model-adapter.openai-smoke-test
  "Smoke the actual OpenAI LangChain4j provider against a deterministic local
  OpenAI-compatible protocol server.  No credentials and no public network."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.model-adapter :as adapter]
            [hc.server.model-adapter.support :as support]
            [hc.server.model-port.contract :as contract]))

(def snapshot
  {:role :main
   :messages [{:role :system :text "be terse"}
              {:role :user :text "read a.txt"}]
   :tools [{:name "cat"
            :description "read a file"
            :parameters {:type "object"
                         :properties {:path {:type "string"}}
                         :required ["path"]}}]
   :stream? true})

(deftest openai-compatible-streaming-path
  (let [server (support/local-openai-server)
        port (try
               (adapter/create-model-port
                {:connections [{:id "local"
                                :kind :openai-compatible
                                :base-url (:base-url server)
                                :api-key "test-key"
                                :timeout-ms 5000
                                :models [{:id "test-model"
                                          :capabilities #{:tools :streaming}}]}]
                 :roles {:main {:connection "local" :model "test-model"}}
                 :executor-threads 2})
               (catch Throwable t
                 ((:stop server))
                 (throw t)))
        rec (support/receiver)]
    (try
      (contract/start! port
                       (contract/request {:attempt-id "openai-smoke-1"
                                          :call-id "openai-business-1"
                                          :snapshot snapshot
                                          :required-capabilities #{:tools}})
                       (:receiver rec))
      (let [result (support/wait-for
                    #(first (support/events-of (:events rec) :completed))
                    5000)
            body (first @(:requests server))]
        (is (some? result))
        (is (some? body))
        (is (= ["Hel" "lo"]
               (mapv :text (support/events-of (:events rec) :chunk))))
        (let [calls (support/events-of (:events rec) :tool-call)]
          (is (= 1 (count calls)))
          (is (= "cat" (:tool-name (first calls))))
          (is (= {:path "a.txt"} (:arguments (first calls)))))
        (is (= 10 (get-in result [:usage :usage :input-tokens])))
        (is (= 5 (get-in result [:usage :usage :output-tokens])))
        (testing "the request used the configured model, streaming, and tools"
          (is (re-find #"\"model\"\s*:\s*\"test-model\"" body))
          (is (re-find #"\"stream\"\s*:\s*true" body))
          (is (re-find #"\"tools\"" body))
          (is (re-find #"read a.txt" body))))
      (finally
        (adapter/stop-port! port)
        ((:stop server))))))

(deftest openai-compatible-cancel-path
  (let [server (support/local-slow-openai-server)
        port (adapter/create-model-port
              {:connections [{:id "local"
                              :kind :openai-compatible
                              :base-url (:base-url server)
                              :api-key "test-key"
                              :timeout-ms 5000
                              :models [{:id "test-model"
                                        :capabilities #{:tools :streaming}}]}]
               :roles {:main {:connection "local" :model "test-model"}}
               :executor-threads 2})
        rec (support/receiver)]
    (try
      (let [handle (contract/start! port
                                    (contract/request
                                     {:attempt-id "openai-cancel-1"
                                      :call-id "openai-cancel-business-1"
                                      :snapshot (assoc snapshot :tools [])
                                      :required-capabilities #{}})
                                    (:receiver rec))]
        (is (support/wait-for
             #(first (support/events-of (:events rec) :chunk))
             5000))
        (is (:ok (contract/cancel! port handle)))
        (is (support/wait-for
             #(first (support/events-of (:events rec) :cancelled))
             5000))
        (Thread/sleep 200)
        (is (= 1 (count (support/events-of (:events rec) :cancelled))))
        (is (empty? (support/events-of (:events rec) :completed))))
      (finally
        (adapter/stop-port! port)
        ((:stop server))))))

(deftest ollama-provider-builds-without-network
  (let [port (adapter/create-model-port
              {:connections [{:id "local"
                              :kind :ollama
                              :base-url "http://127.0.0.1:1"
                              :timeout-ms 1000
                              :models [{:id "qwen"
                                        :capabilities #{:tools :streaming}
                                        :temperature 0.2
                                        :top-p 0.9
                                        :top-k 40
                                        :stop ["STOP"]
                                        :max-output-tokens 128
                                        :options {:num-ctx 8192
                                                  :num-predict 512
                                                  :seed 1
                                                  :repeat-penalty 1.1
                                                  :repeat-last-n 64
                                                  :think true
                                                  :return-thinking true
                                                  :truncate true
                                                  :min-p 0.05
                                                  :mirostat 2
                                                  :mirostat-tau 5.0
                                                  :mirostat-eta 0.1
                                                  :log-requests? false
                                                  :log-responses? false}}]}]
               :roles {:main {:connection "local" :model "qwen"}}
               :executor-threads 1})]
    (try
      (is (some? (adapter/stats port)))
      (finally
        (adapter/stop-port! port)))))

(deftest openai-provider-builds-with-options
  (let [port (adapter/create-model-port
              {:connections [{:id "local"
                              :kind :openai-compatible
                              :base-url "http://127.0.0.1:1/v1"
                              :api-key "test-key"
                              :timeout-ms 1000
                              :headers {"X-Test" "yes"}
                              :models [{:id "test-model"
                                        :capabilities #{:tools :streaming}
                                        :temperature 0.2
                                        :top-p 0.9
                                        :stop ["STOP"]
                                        :max-output-tokens 128
                                        :frequency-penalty 0.1
                                        :presence-penalty 0.1
                                        :options {:log-requests? false
                                                  :log-responses? false
                                                  :strict-tools? true
                                                  :strict-json-schema? true
                                                  :parallel-tool-calls? false
                                                  :reasoning-effort "low"
                                                  :send-thinking? true
                                                  :return-thinking? true
                                                  :max-tokens-field :max-completion-tokens
                                                  :streaming-buffer-size 32
                                                  :user "test-user"
                                                  :seed 7}}]}]
               :roles {:main {:connection "local" :model "test-model"}}
               :executor-threads 1})]
    (try
      (is (some? (adapter/stats port)))
      (finally
        (adapter/stop-port! port)))))

(def ^:private one-pixel-png
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z2ZkAAAAASUVORK5CYII=")

(defn- attachment-port
  [server capabilities loader]
  (adapter/create-model-port
   {:connections [{:id "local"
                   :kind :openai-compatible
                   :base-url (:base-url server)
                   :api-key "test-key"
                   :timeout-ms 5000
                   :models [{:id "test-model"
                             :capabilities capabilities}]}]
    :roles {:main {:connection "local" :model "test-model"}}
    :attachment-loader loader
    :executor-threads 2}))

(deftest openai-native-image-payload
  (let [server (support/local-openai-server (support/text-script))
        port (attachment-port server
                              #{:tools :streaming :vision}
                              (fn [_] {:base64 one-pixel-png
                                       :mime "image/png"}))
        rec (support/receiver)
        snapshot {:role :main
                  :messages [{:role :user
                              :text "what is this?"
                              :attachments [{:attachment-id "a1"
                                             :name "pixel.png"
                                             :type :image
                                             :send-route :native
                                             :send-blob-id "sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                             :send-mime "image/png"}]}]
                  :stream? true}]
    (try
      (contract/start! port
                       (contract/request {:attempt-id "image-1"
                                          :call-id "image-b1"
                                          :snapshot snapshot})
                       (:receiver rec))
      (is (support/wait-for
           #(first (support/events-of (:events rec) :completed))
           5000))
      (let [body (first @(:requests server))]
        (is (string? body))
        (is (re-find #"\"image_url\"" body))
        (is (re-find #"data:image/png;base64," body))
        (is (re-find #"what is this\?" body)))
      (finally
        (adapter/stop-port! port)
        ((:stop server))))))

(deftest openai-text-attachment-payload
  (let [server (support/local-openai-server (support/text-script))
        port (attachment-port server
                              #{:tools :streaming}
                              nil)
        rec (support/receiver)
        snapshot {:role :main
                  :messages [{:role :user
                              :text "summarize"
                              :attachments [{:attachment-id "a1"
                                             :name "notes.txt"
                                             :send-route :text
                                             :send-text "extracted attachment text"}]}]
                  :stream? true}]
    (try
      (contract/start! port
                       (contract/request {:attempt-id "text-1"
                                          :call-id "text-b1"
                                          :snapshot snapshot})
                       (:receiver rec))
      (is (support/wait-for
           #(first (support/events-of (:events rec) :completed))
           5000))
      (is (re-find #"extracted attachment text"
                   (first @(:requests server))))
      (finally
        (adapter/stop-port! port)
        ((:stop server))))))

(deftest undeclared-attachment-capability-sends-nothing
  (let [server (support/local-openai-server (support/text-script))
        port (attachment-port server
                              #{:tools :streaming}
                              (fn [_] {:base64 one-pixel-png :mime "image/png"}))
        rec (support/receiver)
        snapshot {:role :main
                  :messages [{:role :user
                              :text "what is this?"
                              :attachments [{:attachment-id "a1"
                                             :type :image
                                             :send-route :native
                                             :send-blob-id "sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                             :send-mime "image/png"}]}]
                  :stream? true}]
    (try
      (contract/start! port
                       (contract/request {:attempt-id "no-vision-1"
                                          :call-id "no-vision-b1"
                                          :snapshot snapshot})
                       (:receiver rec))
      (let [result (support/wait-for
                    #(first (support/events-of (:events rec) :failed))
                    5000)]
        (is (some? result))
        (is (= :unsupported-capability (get-in result [:error :kind])))
        (is (empty? @(:requests server))))
      (finally
        (adapter/stop-port! port)
        ((:stop server))))))

(deftest unprepared-attachment-is-rejected-not-stripped
  (let [server (support/local-openai-server (support/text-script))
        port (attachment-port server
                              #{:tools :streaming :vision}
                              (fn [_] {:base64 one-pixel-png :mime "image/png"}))
        rec (support/receiver)
        snapshot {:role :main
                  :messages [{:role :user
                              :text "what is this?"
                              :attachments [{:attachment-id "a1"
                                             :blob-id "sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                             :mime "image/png"}]}]
                  :stream? true}]
    (try
      (contract/start! port
                       (contract/request {:attempt-id "raw-1"
                                          :call-id "raw-b1"
                                          :snapshot snapshot})
                       (:receiver rec))
      (let [result (support/wait-for
                    #(first (support/events-of (:events rec) :failed))
                    5000)]
        (is (some? result))
        (is (= :attachment-unresolved (get-in result [:error :kind])))
        (is (empty? @(:requests server))))
      (finally
        (adapter/stop-port! port)
        ((:stop server))))))

(defn- credential-port
  [server resolver]
  (adapter/create-model-port
   {:connections [{:id "local"
                   :kind :openai-compatible
                   :base-url (:base-url server)
                   :timeout-ms 5000
                   :credentials {"c1" {:alias "default"
                                       :secret-ref "vault://test/c1"}}
                   :models [{:id "test-model"
                             :capabilities #{:tools :streaming}}]}]
    :roles {:main {:connection "local" :model "test-model"}}
    :credential-resolver resolver
    :executor-threads 2}))

(deftest credential-resolver-supplies-real-request-key
  (let [server (support/local-openai-server (support/text-script))
        calls (atom 0)
        port (credential-port
              server
              (fn [{:keys [connection-id]}]
                (is (= "local" connection-id))
                (swap! calls inc)
                {:ok? true :secret "rotated-key"}))
        rec (support/receiver)]
    (try
      (contract/start! port
                       (contract/request
                        {:attempt-id "cred-1"
                         :call-id "cred-b1"
                         :snapshot {:role :main
                                    :messages [{:role :user :text "hello"}]
                                    :stream? true}})
                       (:receiver rec))
      (is (support/wait-for
           #(first (support/events-of (:events rec) :completed))
           5000))
      (is (= 1 @calls))
      (is (= "Bearer rotated-key"
             (get-in (first @(:headers server)) [:authorization])))
      (finally
        (adapter/stop-port! port)
        ((:stop server))))))

(deftest missing-credential-resolver-fails-before-http
  (let [server (support/local-openai-server (support/text-script))
        port (credential-port server nil)
        rec (support/receiver)]
    (try
      (contract/start! port
                       (contract/request
                        {:attempt-id "cred-missing-1"
                         :call-id "cred-missing-b1"
                         :snapshot {:role :main
                                    :messages [{:role :user :text "hello"}]
                                    :stream? true}})
                       (:receiver rec))
      (let [result (support/wait-for
                    #(first (support/events-of (:events rec) :failed))
                    5000)]
        (is (some? result))
        (is (= :credential-resolver-missing (get-in result [:error :kind])))
        (is (empty? @(:requests server))))
      (finally
        (adapter/stop-port! port)
        ((:stop server))))))
