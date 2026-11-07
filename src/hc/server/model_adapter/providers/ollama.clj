(ns hc.server.model-adapter.providers.ollama
  "Native Ollama LangChain4j providers.

  Loaded lazily so the adapter core does not depend on the Ollama module.
  Both models are real LangChain4j objects and always use `maxRetries(0)`."
  (:import [dev.langchain4j.model.ollama OllamaChatModel
            OllamaStreamingChatModel]
           [java.time Duration]))

(def option-keys
  #{:log-requests? :log-responses? :num-ctx :num-predict :seed :repeat-penalty
    :repeat-last-n :think :return-thinking :truncate :min-p :mirostat
    :mirostat-tau :mirostat-eta})

(defn- invalid!
  [reason message data]
  (throw (ex-info message
                  (merge {:error/kind :invalid-model-adapter-config
                          :reason reason}
                         data))))

(defn- options
  [model]
  (let [options (or (:options model) {})
        unknown (seq (remove option-keys (keys options)))]
    (when unknown
      (invalid! :unknown-provider-option
                "Ollama provider received an unknown option"
                {:connection (:id model)
                 :model (:id model)
                 :unknown (vec (sort-by str unknown))}))
    options))

(defn- common
  [builder connection model]
  (let [opts (options model)
        timeout (some-> (:timeout-ms connection) Duration/ofMillis)]
    (cond-> builder
      (:base-url connection) (.baseUrl (:base-url connection))
      (:model-name model) (.modelName (:model-name model))
      (some? timeout) (.timeout timeout)
      (seq (:headers connection)) (.customHeaders (:headers connection))

      (some? (:temperature model))
      (.temperature (double (:temperature model)))

      (some? (:top-p model))
      (.topP (double (:top-p model)))

      (some? (:top-k model))
      (.topK (int (:top-k model)))

      (seq (:stop model))
      (.stop (vec (:stop model)))

      (some? (:max-output-tokens model))
      (.numPredict (int (:max-output-tokens model)))

      (contains? opts :num-ctx)
      (.numCtx (int (:num-ctx opts)))

      (contains? opts :num-predict)
      (.numPredict (int (:num-predict opts)))

      (contains? opts :seed)
      (.seed (int (:seed opts)))

      (contains? opts :repeat-penalty)
      (.repeatPenalty (double (:repeat-penalty opts)))

      (contains? opts :repeat-last-n)
      (.repeatLastN (int (:repeat-last-n opts)))

      (contains? opts :think)
      (.think (boolean (:think opts)))

      (contains? opts :return-thinking)
      (.returnThinking (boolean (:return-thinking opts)))

      (contains? opts :truncate)
      (.truncate (boolean (:truncate opts)))

      (contains? opts :min-p)
      (.minP (double (:min-p opts)))

      (contains? opts :mirostat)
      (.mirostat (int (:mirostat opts)))

      (contains? opts :mirostat-tau)
      (.mirostatTau (double (:mirostat-tau opts)))

      (contains? opts :mirostat-eta)
      (.mirostatEta (double (:mirostat-eta opts)))

      (contains? opts :log-requests?)
      (.logRequests (boolean (:log-requests? opts)))

      (contains? opts :log-responses?)
      (.logResponses (boolean (:log-responses? opts))))))

(defn build-profile
  "Build the real Ollama chat and streaming models."
  [connection model]
  {:chat-model (.build (-> (OllamaChatModel/builder)
                           (common connection model)
                           (.maxRetries (int 0))))
   :streaming-chat-model (.build (common (OllamaStreamingChatModel/builder)
                                         connection model))
   :close-fns []})
