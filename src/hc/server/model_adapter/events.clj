(ns hc.server.model-adapter.events
  "Translate LangChain4j response data into model-port events.

  Events are plain data.  This namespace never calls a receiver and never
  changes an attempt; the port owns ordering and terminal transitions."
  (:require [clojure.string :as str]
            [hc.server.model-port.contract :as contract]
            [hc.server.model-port.retry :as retry]
            [hc.server.model-port.usage :as usage])
  (:import [dev.langchain4j.data.message AiMessage]
           [dev.langchain4j.model.chat.response ChatResponse]
           [dev.langchain4j.model.output FinishReason TokenUsage]))

(defn chunk-event
  [attempt-id channel text]
  (contract/event {:kind :chunk
                   :attempt-id attempt-id
                   :channel channel
                   :text text}))

(defn finish-reason->keyword
  [^FinishReason reason]
  (when reason
    (case (.name reason)
      "STOP" :stop
      "LENGTH" :length
      "TOOL_EXECUTION" :tool-execution
      "CONTENT_FILTER" :content-filter
      "OTHER" :other
      (keyword (str/lower-case (.name reason))))))

(defn- invoke-optional
  [target method]
  (try
    (clojure.lang.Reflector/invokeInstanceMethod
     target method (object-array 0))
    (catch Throwable _ nil)))

(defn- openai-cache-usage
  "Read cache details from OpenAiTokenUsage without a compile-time dependency.

  The base TokenUsage API in LangChain4j 1.23 does not expose cache fields;
  the OpenAI subclass does.  Reflection keeps the OpenAI module optional."
  [token-usage]
  (when (= "dev.langchain4j.model.openai.OpenAiTokenUsage"
           (some-> token-usage class .getName))
    (when-let [details (invoke-optional token-usage "inputTokensDetails")]
      {:cache-read (invoke-optional details "cachedTokens")
       :cache-write (invoke-optional details "cacheWriteTokens")})))

(defn usage-report
  "Normalize one LangChain4j token usage.

  A missing field stays `:unknown`; it never becomes zero."
  [^TokenUsage token-usage]
  (if (nil? token-usage)
    (usage/unknown-report)
    (let [cache (openai-cache-usage token-usage)]
      (usage/report {:coverage :final
                     :source :reported
                     :usage {:input-tokens (or (.inputTokenCount token-usage)
                                               :unknown)
                             :output-tokens (or (.outputTokenCount token-usage)
                                                :unknown)
                             :cache-read-tokens (or (:cache-read cache)
                                                    :unknown)
                             :cache-write-tokens (or (:cache-write cache)
                                                     :unknown)}}))))

(defn response-text
  [^ChatResponse response assembled]
  (let [text (some-> response .aiMessage .text)]
    (if (and (string? text) (not (str/blank? text)))
      text
      assembled)))

(defn response-reasoning
  [^ChatResponse response assembled]
  (let [thinking (some-> response .aiMessage .thinking)]
    (if (and (string? thinking) (not (str/blank? thinking)))
      thinking
      assembled)))

(defn- argument-error
  [request index message]
  (let [classification (retry/classify {:kind :tool-arguments-invalid})]
    {:kind :tool-arguments-invalid
     :category (:category classification)
     :disposition (:disposition classification)
     :retryable? (:retryable? classification)
     :message message
     :tool-name (some-> request .name)
     :provider-call-id (some-> request .id)
     :output-index index}))

(defn- invalid-response
  [message data]
  {:ok false
   :error (merge {:kind :invalid-response
                  :category :unclassified
                  :disposition :fail
                  :retryable? false
                  :code :INVALID_MODEL_RESPONSE
                  :message message}
                 data)})

(defn- parse-tool-request
  [request index parse-arguments]
  (cond
    (nil? request)
    {:ok false
     :error (argument-error nil index "tool request is missing")}

    (str/blank? (str (.name request)))
    {:ok false
     :error (argument-error request index "tool request has no name")}

    :else
    (let [parsed (try
                   (let [text (.arguments request)
                         arguments (if (or (nil? text) (str/blank? text))
                                     {}
                                     (parse-arguments text))]
                     {:ok true :arguments arguments})
                   (catch Throwable t
                     {:ok false
                      :message (or (.getMessage t)
                                   "tool arguments could not be parsed")}))]
      (if (:ok parsed)
        (if (map? (:arguments parsed))
          {:ok true
           :call {:provider-call-id (.id request)
                  :tool-name (.name request)
                  :arguments (:arguments parsed)}}
          {:ok false
           :error (argument-error request index
                                  "tool arguments did not parse to a map")})
        {:ok false
         :error (argument-error request index (:message parsed))}))))

(defn response-tool-calls
  "Convert the final AiMessage's tool calls into identity-registry input.

  Returns `{:ok true :calls [...]}` or `{:ok false :error ...}`.  Every call
  must parse to a map; one bad call rejects the whole final response so the
  adapter never executes a partially understood batch.  The final response
  list position is not an output index; the port assigns that only when the
  provider explicitly supplies one."
  [^ChatResponse response parse-arguments]
  (if (nil? response)
    (invalid-response "provider returned no final response" {})
    (let [^AiMessage message (.aiMessage response)]
      (if (nil? message)
        (invalid-response "provider returned no final message" {})
        (let [requests (or (some-> message .toolExecutionRequests) [])]
          (loop [index 0
                 remaining (seq requests)
                 calls []]
            (if-let [request (first remaining)]
              (let [parsed (parse-tool-request request index parse-arguments)]
                (if (:ok parsed)
                  (recur (inc index)
                         (next remaining)
                         (conj calls (:call parsed)))
                  {:ok false :error (:error parsed)}))
              {:ok true :calls calls})))))))

(defn completed-event
  [attempt-id call-id {:keys [text reasoning finish-reason usage
                              response-id model-name published-calls
                              published-call-ids]}]
  (contract/event
   (cond-> {:kind :completed
            :attempt-id attempt-id
            :call-id call-id
            :text text
            :reasoning reasoning
            :finish-reason finish-reason
            :usage usage
            :response-id response-id
            :model-name model-name
            :published-call-ids (vec published-call-ids)}
     (seq published-calls) (assoc :tool-calls (vec published-calls)))))

(defn failed-event
  [attempt-id call-id {:keys [error published-call-ids published-calls text-chunks
                              usage
                              reasoning-chunks]}]
  (contract/event
   (cond-> {:kind :failed
            :attempt-id attempt-id
            :call-id call-id
            :error error
            :partial? (boolean (seq published-call-ids))
            :published-call-ids (vec published-call-ids)
            :text-chunks (long (or text-chunks 0))
            :reasoning-chunks (long (or reasoning-chunks 0))}
     (some? (:retryable? error)) (assoc :retryable? (:retryable? error))
     (some? (:disposition error)) (assoc :disposition (:disposition error))
     (some? (:category error)) (assoc :category (:category error))
     (some? usage) (assoc :usage usage)
     (seq published-calls) (assoc :published-calls (vec published-calls)))))

(defn cancelled-event
  [attempt-id call-id {:keys [reason published-call-ids published-calls usage]}]
  (contract/event
   (cond-> {:kind :cancelled
            :attempt-id attempt-id
            :call-id call-id
            :reason (or reason :user-cancel)
            :published-call-ids (vec published-call-ids)}
     (some? usage) (assoc :usage usage)
     (seq published-calls) (assoc :published-calls (vec published-calls)))))
