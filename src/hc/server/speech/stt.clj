(ns hc.server.speech.stt
  "OpenAI-compatible speech-to-text adapter.

  The request is one multipart POST to the configured transcription path.
  The adapter returns a transcript or a classified provider error.  It does
  not create a message, send a chat request, or try another provider."
  (:require [clojure.string :as str]
            [hc.server.speech.error :as error]
            [hc.server.speech.files :as files]
            [hc.server.speech.http :as http]
            [hc.server.speech.json :as json]
            [hc.server.speech.usage :as usage])
  (:import (java.nio.charset StandardCharsets)
           (java.util UUID)))

(defn endpoint
  [service]
  (str (:base-url service) (:transcriptions-path service)))

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
    (= 413 status) :SPEECH_AUDIO_TOO_LARGE
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
                    (str "speech provider returned HTTP " status))]
    {:ok? false
     :error {:code code
             :message message
             :retryable? (provider-retryable? status)
             :details {:http-status status
                       :service-id (:id service)
                       :provider-message (error/redact-message
                                          (provider-message body))}}}))

(defn- parse-success
  [service response response-format]
  (let [body (:body response)
        text-body (when body (String. ^bytes body StandardCharsets/UTF_8))
        content-type (http/content-type (:headers response))
        json? (or (str/includes? (str content-type) "json")
                  (= "json" response-format)
                  (= "verbose_json" response-format)
                  (and text-body (str/starts-with? (str/trim text-body) "{")))]
    (cond
      (:timed-out? response)
      {:ok? false
       :error {:code :SPEECH_TIMEOUT
               :message "speech provider response timed out"
               :retryable? true
               :details {:service-id (:id service)}}}

      (:read-error response)
      {:ok? false
       :error {:code :SPEECH_STREAM_FAILED
               :message "speech provider response ended early"
               :retryable? true
               :details {:service-id (:id service)}}}

      (:truncated? response)
      {:ok? false
       :error {:code :SPEECH_RESPONSE_TOO_LARGE
               :message "speech provider response exceeded the local limit"
               :retryable? false
               :details {:service-id (:id service)}}}

      json?
      (try
        (let [value (json/decode text-body)
              text (cond
                     (string? (:text value)) (:text value)
                     (string? (:transcription value)) (:transcription value)
                     :else nil)]
          (if (nil? text)
            {:ok? false
             :error {:code :SPEECH_RESPONSE_INVALID
                     :message "speech provider response has no transcript"
                     :retryable? false
                     :details {:service-id (:id service)
                               :keys (when (map? value) (vec (keys value)))}}}
            {:ok? true
             :text text
             :language (or (:language value) (:detected_language value))
             :usage (usage/stt-usage value)
             :raw value}))
        (catch clojure.lang.ExceptionInfo t
          {:ok? false
           :error (assoc (error/error-map t)
                         :code :SPEECH_RESPONSE_INVALID
                         :retryable? false)}))

      :else
      {:ok? true
       :text (str/trim (or text-body ""))
       :language nil
       :raw text-body})))

(defn transcribe
  "Transcribe AUDIO with SERVICE.

  Options: `:language`, `:api-key`, `:response-format`, `:filename`,
  `:content-type`, `:timeout-ms`, `:connect-timeout-ms`, `:max-response-bytes`."
  [service ^bytes audio {:keys [language api-key response-format filename
                                content-type timeout-ms connect-timeout-ms
                                max-response-bytes]}]
  (let [boundary (str "hc-" (UUID/randomUUID))
        response-format (or response-format (:response-format service) "json")
        parts (cond-> [{:name "file"
                        :filename (or filename
                                      (str "speech."
                                           (files/content-extension content-type)))
                        :content-type (or content-type
                                          "application/octet-stream")
                        :value audio}
                       {:name "model" :value (:model service)}
                       {:name "response_format" :value response-format}]
                (and language
                     (not (str/blank? (str language)))
                     (not= "auto" language))
                (conj {:name "language" :value language}))
        body (http/multipart-body boundary parts)
        headers (cond-> {"content-type"
                         (str "multipart/form-data; boundary=" boundary)
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
      (if (http/ok-status? (:status response))
        (parse-success service response response-format)
        (provider-error service (:status response) (:body response))))))
