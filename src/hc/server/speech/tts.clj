(ns hc.server.speech.tts
  "OpenAI-compatible text-to-speech adapter.

  The request is one JSON POST to the configured speech path.  The response
  is audio bytes.  A stream that ends early is returned as partial audio; the
  caller stores it and marks it partial instead of pretending it is complete."
  (:require [clojure.string :as str]
            [hc.server.speech.error :as error]
            [hc.server.speech.files :as files]
            [hc.server.speech.http :as http]
            [hc.server.speech.json :as json]
            [hc.server.speech.usage :as usage])
  (:import (java.nio.charset StandardCharsets)))

(defn endpoint
  [service]
  (str (:base-url service) (:speech-path service)))

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
                    (str "speech provider returned HTTP " status))]
    {:ok? false
     :error {:code code
             :message message
             :retryable? (provider-retryable? status)
             :details {:http-status status
                       :service-id (:id service)
                       :provider-message (error/redact-message
                                          (provider-message body))}}}))

(defn- error-response?
  [content-type]
  (or (str/includes? (str content-type) "json")
      (str/starts-with? (str content-type) "text/")))

(defn synthesize
  "Synthesize TEXT with SERVICE.

  Options: `:voice`, `:format`, `:speed`, `:api-key`, `:timeout-ms`,
  `:connect-timeout-ms`, `:max-audio-bytes`."
  [service text {:keys [voice format speed api-key timeout-ms
                        connect-timeout-ms max-audio-bytes]}]
  (let [format (or format (:format service) "wav")
        speed (double (or speed (:speed service) 1.0))
        voice (or voice (:voice service))
        payload (cond-> {"model" (:model service)
                         "input" text
                         "response_format" format
                         "speed" speed}
                  (and voice (not (str/blank? (str voice))))
                  (assoc "voice" voice))
        body (.getBytes (json/encode payload) StandardCharsets/UTF_8)
        headers (cond-> {"content-type" "application/json"
                         "accept" "audio/*"}
                  (and api-key (not (str/blank? (str api-key))))
                  (assoc "authorization" (str "Bearer " api-key)))
        response (http/post-audio
                  (endpoint service)
                  {:headers headers
                   :body body
                   :timeout-ms (or timeout-ms (:request-timeout-ms service) 120000)
                   :connect-timeout-ms (or connect-timeout-ms
                                           (:connect-timeout-ms service)
                                           5000)
                   :limit (or max-audio-bytes (* 50 1024 1024))})]
    (if-not (:ok? response)
      response
      (let [status (:status response)
            content-type (:content-type response)
            bytes (:bytes response)]
        (if-not (http/ok-status? status)
          (provider-error service status bytes)
          (cond
            (and (error-response? content-type)
                 (pos? (alength ^bytes bytes)))
            {:ok? false
             :error {:code :SPEECH_PROVIDER_ERROR
                     :message (or (error/redact-message
                                   (provider-message bytes))
                                  "speech provider returned an error body")
                     :retryable? false
                     :details {:http-status status
                               :service-id (:id service)
                               :provider-message (error/redact-message
                                                  (provider-message bytes))}}}

            (zero? (alength ^bytes bytes))
            {:ok? false
             :error {:code (cond
                             (:timed-out? response) :SPEECH_TIMEOUT
                             (:read-error response) :SPEECH_STREAM_FAILED
                             :else :SPEECH_EMPTY_AUDIO)
                     :message (cond
                                (:timed-out? response)
                                "speech provider response timed out before audio arrived"
                                (:read-error response)
                                "speech provider stream ended before audio arrived"
                                :else
                                "speech provider returned no audio")
                     :retryable? (boolean (or (:timed-out? response)
                                              (:read-error response)))
                     :details {:http-status status
                               :service-id (:id service)}}}

            :else
            {:ok? true
             :status status
             :bytes bytes
             :content-type (or content-type
                               (files/content-type-for-format format))
             :format format
             :partial? (boolean (or (:partial? response)
                                    (:truncated? response)))
             :truncated? (boolean (:truncated? response))
             :timed-out? (boolean (:timed-out? response))
             :headers (:headers response)
             :usage (usage/tts-usage (:headers response))
             :billed (usage/billed (:headers response))
             :read-error (:read-error response)}))))))
