(ns hc.server.model-adapter.providers.openai
  "OpenAI and OpenAI-compatible LangChain4j providers.

  This namespace is loaded lazily by `hc.server.model-adapter.providers`, so
  the adapter core still loads when the OpenAI module is absent.  It constructs
  real `OpenAiChatModel`/`OpenAiStreamingChatModel` instances and always sets
  `maxRetries(0)`; retry policy belongs to the model port."
  (:import [dev.langchain4j.model.openai OpenAiChatModel
            OpenAiStreamingChatModel]
           [java.time Duration]))

(def option-keys
  #{:log-requests? :log-responses? :strict-tools? :strict-json-schema?
    :parallel-tool-calls? :reasoning-effort :send-thinking? :return-thinking?
    :max-tokens-field :streaming-buffer-size :user :seed})

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
                "OpenAI provider received an unknown option"
                {:connection (:id model)
                 :model (:id model)
                 :unknown (vec (sort-by str unknown))}))
    options))

(defn- duration
  [connection]
  (some-> (:timeout-ms connection) Duration/ofMillis))

(defn- with-max-tokens
  [builder model opts]
  (if-let [max-tokens (:max-output-tokens model)]
    (if (= :max-completion-tokens (:max-tokens-field opts))
      (.maxCompletionTokens builder (int max-tokens))
      (.maxTokens builder (int max-tokens)))
    builder))

(defn- common
  [builder connection model]
  (let [opts (options model)
        builder (cond-> builder
                  (:base-url connection) (.baseUrl (:base-url connection))
                  (:api-key connection) (.apiKey (:api-key connection))
                  (:model-name model) (.modelName (:model-name model))
                  (duration connection) (.timeout (duration connection))
                  (seq (:headers connection)) (.customHeaders (:headers connection))

                  (some? (:temperature model))
                  (.temperature (double (:temperature model)))

                  (some? (:top-p model))
                  (.topP (double (:top-p model)))

                  (seq (:stop model))
                  (.stop (vec (:stop model)))

                  (some? (:frequency-penalty model))
                  (.frequencyPenalty (double (:frequency-penalty model)))

                  (some? (:presence-penalty model))
                  (.presencePenalty (double (:presence-penalty model)))

                  (contains? opts :log-requests?)
                  (.logRequests (boolean (:log-requests? opts)))

                  (contains? opts :log-responses?)
                  (.logResponses (boolean (:log-responses? opts)))

                  (contains? opts :strict-tools?)
                  (.strictTools (boolean (:strict-tools? opts)))

                  (contains? opts :strict-json-schema?)
                  (.strictJsonSchema (boolean (:strict-json-schema? opts)))

                  (contains? opts :parallel-tool-calls?)
                  (.parallelToolCalls (boolean (:parallel-tool-calls? opts)))

                  (some? (:reasoning-effort opts))
                  (.reasoningEffort (str (:reasoning-effort opts)))

                  (contains? opts :send-thinking?)
                  (.sendThinking (boolean (:send-thinking? opts)))

                  (contains? opts :return-thinking?)
                  (.returnThinking (boolean (:return-thinking? opts)))

                  (some? (:user opts))
                  (.user (str (:user opts)))

                  (some? (:seed opts))
                  (.seed (int (:seed opts))))]
    (with-max-tokens builder model opts)))

(defn build-profile
  "Build the real OpenAI chat and streaming models."
  [connection model]
  (let [opts (options model)
        chat (-> (OpenAiChatModel/builder)
                 (common connection model)
                 (.maxRetries (int 0)))
        streaming (common (OpenAiStreamingChatModel/builder) connection model)
        streaming (cond-> streaming
                    (some? (:streaming-buffer-size opts))
                    (.streamingBufferSize (int (:streaming-buffer-size opts))))]
    {:chat-model (.build chat)
     :streaming-chat-model (.build streaming)
     :close-fns []}))
