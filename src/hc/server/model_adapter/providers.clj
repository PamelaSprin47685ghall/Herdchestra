(ns hc.server.model-adapter.providers
  "Build concrete LangChain4j provider objects from normalized configuration.

  Provider modules are loaded lazily.  A missing module is an explicit
  `:provider-module-missing` error, never a silent downgrade to a test double.
  A custom connection may inject real `ChatModel`/`StreamingChatModel`
  instances; the adapter does not manufacture providers."
  (:require [clojure.set :as set])
  (:import [dev.langchain4j.model.chat ChatModel StreamingChatModel]))

(def known-capabilities
  "Capability names an application may declare for a model."
  #{:text :vision :audio :video :document :tools :reasoning :streaming
    :json-schema})

(def honoured-capabilities
  "Capabilities this adapter can actually carry through a request.  A
  declaration outside this set stays visible but never satisfies a required
  capability."
  #{:tools :streaming :reasoning :json-schema :vision :document :audio :video})

(defn- invalid!
  [reason message data]
  (throw (ex-info message
                  (merge {:error/kind :invalid-model-adapter-config
                          :reason reason}
                         data))))

(defn- provider-namespace
  [kind]
  (case kind
    (:openai :openai-compatible) 'hc.server.model-adapter.providers.openai
    :ollama 'hc.server.model-adapter.providers.ollama
    nil))

(defn- missing-module!
  [kind]
  (throw (ex-info "the provider module is not on the classpath"
                  {:error/kind :provider-module-missing
                   :provider kind
                   :hint (case kind
                           (:openai :openai-compatible)
                           "add dev.langchain4j/langchain4j-open-ai"
                           :ollama "add dev.langchain4j/langchain4j-ollama"
                           nil)})))

(defn- load-builder
  [kind]
  (let [ns-sym (provider-namespace kind)]
    (when-not ns-sym
      (invalid! :unknown-provider-kind
                "unknown provider kind"
                {:provider kind}))
    (try
      (require ns-sym)
      (or (resolve (symbol (str ns-sym) "build-profile"))
          (throw (ex-info "provider module has no build-profile"
                          {:error/kind :provider-module-load-failed
                           :provider kind
                           :namespace (str ns-sym)})))
      (catch java.io.FileNotFoundException _
        (missing-module! kind))
      (catch ClassNotFoundException _
        (missing-module! kind))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Throwable t
        (throw (ex-info "provider module failed to load"
                        {:error/kind :provider-module-load-failed
                         :provider kind
                         :namespace (str ns-sym)
                         :message (some-> t .getMessage)}
                        t))))))

(defn- explicit-close-fns
  [model]
  (cond-> []
    (fn? (:close-fn model)) (conj (:close-fn model))))

(defn- build-custom
  [connection model]
  (let [chat-model (:chat-model model)
        streaming-model (:streaming-chat-model model)]
    (when (and (nil? chat-model) (nil? streaming-model))
      (invalid! :custom-model-missing
                "a custom connection/model needs :chat-model or :streaming-chat-model"
                {:connection (:id connection) :model (:id model)}))
    (when (and (some? chat-model) (not (instance? ChatModel chat-model)))
      (invalid! :custom-chat-model-invalid
                ":chat-model must implement dev.langchain4j.model.chat.ChatModel"
                {:connection (:id connection) :model (:id model)
                 :class (some-> chat-model class .getName)}))
    (when (and (some? streaming-model)
               (not (instance? StreamingChatModel streaming-model)))
      (invalid! :custom-streaming-model-invalid
                ":streaming-chat-model must implement dev.langchain4j.model.chat.StreamingChatModel"
                {:connection (:id connection) :model (:id model)
                 :class (some-> streaming-model class .getName)}))
    (when (empty? (:capabilities model))
      (invalid! :capabilities-required
                "a custom model must declare its capabilities"
                {:connection (:id connection) :model (:id model)}))
    {:provider-kind :custom
     :chat-model chat-model
     :streaming-chat-model streaming-model
     :close-fns (explicit-close-fns model)}))

(defn- validate-capabilities
  [connection model built]
  (let [declared (set (:capabilities model))
        unknown (set/difference declared known-capabilities)
        has-streaming? (some? (:streaming-chat-model built))
        declared (cond-> declared
                   (and has-streaming? (not (contains? declared :streaming)))
                   (conj :streaming))]
    (when (seq unknown)
      (invalid! :unknown-declared-capability
                "configuration declares an unknown capability"
                {:connection (:id connection)
                 :model (:id model)
                 :capabilities (vec (sort-by str unknown))
                 :known (vec (sort-by str known-capabilities))}))
    (when (and (contains? declared :streaming) (not has-streaming?))
      (invalid! :streaming-model-missing
                "the model declares :streaming but no StreamingChatModel exists"
                {:connection (:id connection) :model (:id model)}))
    {:declared declared
     :honoured (set/intersection declared honoured-capabilities)}))

(defn build-profile
  "Build one provider profile for a normalized connection and model."
  [connection model]
  (let [kind (:kind connection)
        built (try
                (if (= :custom kind)
                  (build-custom connection model)
                  ((load-builder kind) connection model))
                (catch clojure.lang.ExceptionInfo e
                  (throw e))
                (catch Throwable t
                  (throw (ex-info "provider construction failed"
                                  {:error/kind :provider-build-failed
                                   :provider kind
                                   :connection (:id connection)
                                   :model (:id model)
                                   :message (some-> t .getMessage)}
                                  t))))
        capabilities (validate-capabilities connection model built)]
    (merge built
           {:connection-id (:id connection)
            :model-id (:id model)
            :model-name (:model-name model)
            :provider-kind kind
            :owns-models? (not= :custom kind)
            :connection (dissoc connection :api-key :api-key-env)
            :model model
            :requires-credential? (and (contains? #{:openai :openai-compatible}
                                                  kind)
                                       (nil? (:api-key connection))
                                       (true? (:has-multi-credentials?
                                               connection)))
            :capabilities (:declared capabilities)
            :honoured-capabilities (:honoured capabilities)
            :degradations (or (:degradations model) {})
            :implicit-retries (if (= :custom kind)
                                (:implicit-retries model)
                                0)
            :cancel-fn (:cancel-fn model)
            :close-fns (->> (concat (:close-fns built)
                                    (explicit-close-fns model))
                            distinct
                            vec)})))
