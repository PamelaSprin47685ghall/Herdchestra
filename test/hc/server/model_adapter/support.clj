(ns hc.server.model-adapter.support
  "Deterministic test doubles and a local OpenAI-compatible protocol server.

  The custom models are real LangChain4j interfaces, not provider mocks.  The
  HTTP server speaks the OpenAI chat-completions streaming protocol so the
  smoke test exercises the actual OpenAiStreamingChatModel adapter path without
  credentials or a public network."
  (:require [clojure.java.io :as io]
            [hc.server.model-port.contract :as contract]
            [hc.server.model-adapter.json :as json])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [dev.langchain4j.agent.tool ToolExecutionRequest]
           [dev.langchain4j.data.message AiMessage]
           [dev.langchain4j.model.chat ChatModel StreamingChatModel]
           [dev.langchain4j.model.chat.response ChatResponse
            StreamingChatResponseHandler]
           [dev.langchain4j.model.output FinishReason TokenUsage]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors TimeUnit]))

(defn receiver
  "A receiver that records events and never blocks."
  []
  (let [events (atom [])]
    {:events events
     :receiver (reify contract/Receiver
                 (deliver! [_ event]
                   (swap! events conj event)
                   {:ok true}))}))

(defn wait-for
  "Poll `pred` until it returns truthy or the timeout expires."
  ([pred] (wait-for pred 3000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (if-let [value (pred)]
         value
         (when (< (System/currentTimeMillis) deadline)
           (Thread/sleep 10)
           (recur)))))))

(defn events-of
  [events kind]
  (filterv #(= kind (:event %)) @events))

(defn streaming-model
  "A real StreamingChatModel whose doChat is a deterministic script."
  [script]
  (reify StreamingChatModel
    (doChat [_ request handler]
      (script request handler))))

(defn chat-model
  "A real ChatModel whose doChat is a deterministic script."
  [script]
  (reify ChatModel
    (doChat [_ request]
      (script request))))

(defn tool-request
  ([name arguments] (tool-request nil name arguments))
  ([id name arguments]
   (-> (ToolExecutionRequest/builder)
       (cond-> (some? id) (.id id))
       (.name name)
       (.arguments (if (string? arguments)
                     arguments
                     (json/encode arguments)))
       (.build))))

(defn response
  [{:keys [text thinking tool-calls finish-reason usage id model-name]
    :or {finish-reason FinishReason/STOP}}]
  (let [message (cond-> (AiMessage/builder)
                  (some? text) (.text text)
                  (some? thinking) (.thinking thinking)
                  (seq tool-calls) (.toolExecutionRequests (vec tool-calls)))]
    (-> (ChatResponse/builder)
        (.aiMessage (.build message))
        (.finishReason finish-reason)
        (cond-> (some? usage) (.tokenUsage usage))
        (cond-> (some? id) (.id id))
        (cond-> (some? model-name) (.modelName model-name))
        (.build))))

(defn token-usage
  [input output]
  (TokenUsage. (int input) (int output)))

(defn- sse-chunk
  [^HttpExchange exchange data]
  (let [text (str "data: " data "\n\n")]
    (.write (.getResponseBody exchange)
            (.getBytes text StandardCharsets/UTF_8))
    (.flush (.getResponseBody exchange))))

(defn- write-sse!
  [^HttpExchange exchange chunks]
  (doseq [chunk chunks]
    (sse-chunk exchange chunk)
    (Thread/sleep 5))
  (sse-chunk exchange "[DONE]")
  (.close (.getResponseBody exchange)))

(defn- openai-chunk
  [delta finish-reason]
  {:id "chatcmpl-local"
   :object "chat.completion.chunk"
   :created 0
   :model "test-model"
   :choices [{:index 0 :delta delta :finish_reason finish-reason}]})

(defn- openai-usage-chunk
  [input output]
  {:id "chatcmpl-local"
   :object "chat.completion.chunk"
   :created 0
   :model "test-model"
   :choices []
   :usage {:prompt_tokens input
           :completion_tokens output
           :total_tokens (+ input output)}})

(defn- text-and-tool-script
  []
  [(json/encode (openai-chunk {:role "assistant" :content "Hel"} nil))
   (json/encode (openai-chunk {:content "lo"} nil))
   (json/encode (openai-chunk
                 {:tool_calls [{:index 0
                                :id "provider-call-1"
                                :type "function"
                                :function {:name "cat"
                                           :arguments "{\"path\":"}}]}
                 nil))
   (json/encode (openai-chunk
                 {:tool_calls [{:index 0
                                :function {:arguments "\"a.txt\"}"}}]}
                 nil))
   (json/encode (openai-chunk {} "tool_calls"))
   (json/encode (openai-usage-chunk 10 5))])

(defn text-script
  "A text-only OpenAI streaming response for payload-focused tests."
  []
  [(json/encode (openai-chunk {:role "assistant" :content "seen"} nil))
   (json/encode (openai-chunk {} "stop"))])

(defn local-openai-server
  "Start a local OpenAI-compatible streaming endpoint.

  Returns `{:base-url ... :port ... :requests atom-of-bodies :stop fn}`.  The
  server is intentionally deterministic and never reads credentials."
  ([] (local-openai-server (text-and-tool-script)))
  ([chunks]
   (let [requests (atom [])
        headers (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newCachedThreadPool)
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (try
                      (let [body (slurp (io/reader (.getRequestBody exchange)))]
                        (swap! requests conj body)
                        (swap! headers conj
                               {:authorization
                                (.getFirst (.getRequestHeaders exchange)
                                           "Authorization")})
                        (.sendResponseHeaders exchange 200 0)
                        (.set (.getResponseHeaders exchange)
                              "Content-Type" "text/event-stream")
                        (write-sse! exchange chunks))
                      (finally
                        (.close exchange)))))]
    (.createContext server "/v1/chat/completions" handler)
    (.setExecutor server executor)
    (.start server)
    {:base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")
     :port (.getPort (.getAddress server))
     :requests requests
     :headers headers
     :stop (fn []
             (.stop server 0)
             (.shutdownNow executor)
             (.awaitTermination executor 2 TimeUnit/SECONDS))})))

(defn local-slow-openai-server
  "A local OpenAI-compatible stream that pauses after the first token.

  The pause gives the test time to cancel while the HTTP response is still
  open, exercising the real provider's StreamingHandle path."
  []
  (let [requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newCachedThreadPool)
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (try
                      (swap! requests conj
                             (slurp (io/reader (.getRequestBody exchange))))
                      (.sendResponseHeaders exchange 200 0)
                      (.set (.getResponseHeaders exchange)
                            "Content-Type" "text/event-stream")
                      (sse-chunk exchange
                                 (json/encode
                                  (openai-chunk {:role "assistant"
                                                 :content "first"}
                                                nil)))
                      (Thread/sleep 2000)
                      (sse-chunk exchange
                                 (json/encode
                                  (openai-chunk {:content "late"} nil)))
                      (sse-chunk exchange "[DONE]")
                      (.close (.getResponseBody exchange))
                      (catch Throwable _)
                      (finally
                        (try (.close exchange) (catch Throwable _))))))]
    (.createContext server "/v1/chat/completions" handler)
    (.setExecutor server executor)
    (.start server)
    {:base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")
     :port (.getPort (.getAddress server))
     :requests requests
     :stop (fn []
             (.stop server 0)
             (.shutdownNow executor)
             (.awaitTermination executor 2 TimeUnit/SECONDS))}))

(defn custom-config
  "A configuration with injected real LangChain4j models."
  [streaming chat capabilities]
  {:connections [{:id "local"
                  :kind :custom
                  :models [{:id "test-model"
                            :implicit-retries 0
                            :capabilities capabilities
                            :chat-model chat
                            :streaming-chat-model streaming}]}]
   :roles {:main {:connection "local" :model "test-model"}
           :task {:connection "local" :model "test-model"}}
   :executor-threads 2})
