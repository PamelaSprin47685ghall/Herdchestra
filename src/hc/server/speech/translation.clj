(ns hc.server.speech.translation
  "OpenAI-compatible text translation adapter.

  Translation is a derived text result.  The adapter sends one non-streaming
  chat request to an explicitly configured endpoint and returns the translated
  text; it never overwrites the source and never sends the source as a new
  chat message."
  (:require [clojure.string :as str]
            [hc.server.speech.error :as error]
            [hc.server.speech.http :as http]
            [hc.server.speech.json :as json]
            [hc.server.speech.usage :as usage])
  (:import (java.nio.charset StandardCharsets)))

(defn endpoint
  [service]
  (str (:base-url service) (:chat-path service)))

(defn- provider-message
  [body]
  (when (and body (pos? (alength ^bytes body)))
    (try
      (let [value (json/decode (String. ^bytes body StandardCharsets/UTF_8))]
        (cond
          (map? value) (or (get-in value [:error :message])
                           (:message value)
                           (when (string? (:error value)) (:error value)))
          (string? value) value
          :else nil))
      (catch Throwable _ nil))))

(defn- provider-code
  [status]
  (cond
    (contains? #{401 403} status) :SPEECH_AUTH_FAILED
    (= 404 status) :SPEECH_ENDPOINT_NOT_FOUND
    (= 408 status) :SPEECH_TIMEOUT
    (= 413 status) :SPEECH_TEXT_TOO_LARGE
    (= 429 status) :SPEECH_RATE_LIMITED
    (<= 500 status 599) :SPEECH_SERVICE_ERROR
    :else :SPEECH_HTTP_ERROR))

(defn- provider-retryable?
  [status]
  (or (= status 408)
      (= status 429)
      (<= 500 status 599)))

(defn- provider-error
  [service status body]
  (let [code (provider-code status)
        message (or (error/redact-message (provider-message body))
                    (str "translation provider returned HTTP " status))]
    {:ok? false
     :error {:code code
             :message message
             :retryable? (provider-retryable? status)
             :details {:http-status status
                       :service-id (:id service)
                       :provider-message (error/redact-message
                                          (provider-message body))}}}))

(defn- content-text
  [content]
  (cond
    (string? content) content
    (vector? content)
    (->> content
         (keep (fn [part]
                 (cond
                   (string? part) part
                   (map? part) (or (:text part) (:content part))
                   :else nil)))
         (str/join ""))
    :else nil))

(defn- response-text
  [value]
  (or (content-text (get-in value [:choices 0 :message :content]))
      (content-text (get-in value [:choices 0 :text]))
      (get-in value [:output_text])
      (get-in value [:translation])))

(defn- system-prompt
  [source-language target-language]
  (str "Translate the user's text"
       (if (or (nil? source-language)
               (str/blank? (str source-language))
               (= "auto" source-language))
         " from its detected language"
         (str " from " source-language))
       " into " target-language
       ". Preserve paragraph order, links, numbers, and formatting. "
       "Return only the translation, without notes or quotation marks."))

(defn translate
  "Translate TEXT with SERVICE.

  Options: `:source-language`, `:target-language`, `:api-key`, `:timeout-ms`,
  `:connect-timeout-ms`, `:max-response-bytes`."
  [service text {:keys [source-language target-language api-key timeout-ms
                        connect-timeout-ms max-response-bytes]}]
  (let [payload {"model" (:model service)
                 "messages" [{"role" "system"
                              "content" (system-prompt source-language
                                                       target-language)}
                             {"role" "user" "content" text}]
                 "temperature" 0
                 "stream" false}
        body (.getBytes (json/encode payload) StandardCharsets/UTF_8)
        headers (cond-> {"content-type" "application/json"
                         "accept" "application/json"}
                  (and api-key (not (str/blank? (str api-key))))
                  (assoc "authorization" (str "Bearer " api-key)))
        response (http/post-bytes
                  (endpoint service)
                  {:headers headers
                   :body body
                   :timeout-ms (or timeout-ms (:request-timeout-ms service) 60000)
                   :connect-timeout-ms (or connect-timeout-ms
                                           (:connect-timeout-ms service)
                                           5000)
                   :limit (or max-response-bytes (* 8 1024 1024))})]
    (if-not (:ok? response)
      response
      (if-not (http/ok-status? (:status response))
        (provider-error service (:status response) (:body response))
        (cond
          (:timed-out? response)
          {:ok? false
           :error {:code :SPEECH_TIMEOUT
                   :message "translation provider response timed out"
                   :retryable? true
                   :details {:service-id (:id service)}}}

          (:read-error response)
          {:ok? false
           :error {:code :SPEECH_STREAM_FAILED
                   :message "translation provider response ended early"
                   :retryable? true
                   :details {:service-id (:id service)}}}

          (:truncated? response)
          {:ok? false
           :error {:code :SPEECH_RESPONSE_TOO_LARGE
                   :message "translation provider response exceeded the local limit"
                   :retryable? false
                   :details {:service-id (:id service)}}}

          :else
          (let [text (String. ^bytes (:body response) StandardCharsets/UTF_8)
                value (try
                        (json/decode text)
                        (catch Throwable _
                          (error/fail! :SPEECH_RESPONSE_INVALID
                                       "translation provider returned invalid JSON"
                                       {:service-id (:id service)})))
                translated (response-text value)]
            (if (str/blank? (str translated))
              {:ok? false
               :error {:code :SPEECH_RESPONSE_INVALID
                       :message "translation provider returned no text"
                       :retryable? false
                       :details {:service-id (:id service)}}}
              {:ok? true
               :translated-text translated
               :usage (usage/translation-usage value)
               :token-usage (usage/token-usage value)
               :raw value})))))))
