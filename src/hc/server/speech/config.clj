(ns hc.server.speech.config
  "Explicit speech service configuration.

  Speech input and speech output are separate services.  Neither follows the
  chat model, and neither silently falls back to another provider.  Every
  service has a concrete HTTP endpoint and model; the server adapter does not
  provide a synthetic \"device\" or \"system\" provider.  A loopback endpoint
  may be labelled `:device`; a remote endpoint is `:cloud`.  A client-side
  system voice stays in the client and is not configured here.

  Inline API keys are accepted for the current process but are never written
  to the configuration file or returned in a snapshot.  `:api-key-env` is the
  durable form."
  (:require [clojure.string :as str]
            [hc.server.speech.error :as error]
            [hc.server.speech.files :as files])
  (:import (java.net URI)))

(def max-input-duration-ms
  "One voice input is at most five minutes (AGENTS.md §7.4 旅程 6.7（用语音）)."
  300000)

(def supported-kinds
  #{:openai-compatible})

(def processing-values
  #{:device :cloud})

(def tts-formats
  #{"wav" "mp3" "opus" "aac" "flac" "pcm"})

(def stt-response-formats
  #{"json" "text" "verbose_json"})

(def default-notices
  {:en {:code-block "There is a code block."
        :indented-code "There is a code block."
        :table "There is a table."
        :url "There is a link."
        :formula "There is a formula."
        :image "Image."}
   :zh-CN {:code-block "这里有代码块。"
           :indented-code "这里有代码块。"
           :table "这里有表格。"
           :url "这里有一个网址。"
           :formula "这里有一个公式。"
           :image "图片。"}})

(defn defaults
  []
  {:revision 0
   :stt {:services []
         :default-service-id nil
         :max-duration-ms max-input-duration-ms
         :max-audio-bytes (* 25 1024 1024)
         :language "auto"
         :response-format "json"
         :connect-timeout-ms 5000
         :request-timeout-ms 60000}
   :tts {:services []
         :default-service-id nil
         :max-text-chars 20000
         :max-audio-bytes (* 50 1024 1024)
         :format "wav"
         :speed 1.0
         :skip-code? true
         :skip-urls? true
         :skip-tables? true
         :skip-formulas? true
         :table-min-rows 4
         :notices default-notices
         :test-text "Herdchestra speech test."
         :connect-timeout-ms 5000
         :request-timeout-ms 120000}
   :translation {:services []
                 :default-service-id nil
                 :max-text-chars 20000
                 :connect-timeout-ms 5000
                 :request-timeout-ms 60000}})

(defn- as-keyword
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

(defn- blank->nil
  [value]
  (let [value (some-> value str str/trim)]
    (when-not (str/blank? value) value)))

(defn- path-value
  [value default]
  (let [value (or (blank->nil value) default)]
    value))

(defn- loopback-host?
  [host]
  (let [host (some-> host str/lower-case)]
    (boolean
     (and host
          (or (= host "localhost")
              (= host "::1")
              (= host "[::1]")
              (= host "0:0:0:0:0:0:0:1")
              (re-matches #"127(?:\.\d{1,3}){3}" host))))))

(defn- uri-value
  [value]
  (when-not (str/blank? (str value))
    (try
      (URI/create (str value))
      (catch Exception _ nil))))

(defn- endpoint-violations
  [service]
  (let [base (:base-url service)
        uri (uri-value base)
        path (:transcriptions-path service)
        speech-path (:speech-path service)
        chat-path (:chat-path service)]
    (cond-> []
      (str/blank? (str base))
      (conj {:field :base-url :message "base-url is required"})

      (and (not (str/blank? (str base))) (nil? uri))
      (conj {:field :base-url :message "base-url is not a valid URI"})

      (and uri
           (not (#{"http" "https"}
                 (some-> (.getScheme uri) str/lower-case))))
      (conj {:field :base-url :message "base-url must use http or https"})

      (and uri (str/blank? (.getHost uri)))
      (conj {:field :base-url :message "base-url needs a host"})

      (and uri (.getRawUserInfo uri))
      (conj {:field :base-url
             :message "base-url must not contain credentials"})

      (and uri (or (.getRawQuery uri) (.getRawFragment uri)))
      (conj {:field :base-url
             :message "base-url must not contain a query or fragment"})

      (and (some? path)
           (not (string? path)))
      (conj {:field :transcriptions-path
             :message "transcriptions-path must be a string"})

      (and (string? path)
           (or (not (str/starts-with? path "/"))
               (str/includes? path "..")
               (re-find #"[\r\n]" path)))
      (conj {:field :transcriptions-path
             :message "transcriptions-path must be an absolute path without .."})

      (and (some? speech-path)
           (not (string? speech-path)))
      (conj {:field :speech-path
             :message "speech-path must be a string"})

      (and (string? speech-path)
           (or (not (str/starts-with? speech-path "/"))
               (str/includes? speech-path "..")
               (re-find #"[\r\n]" speech-path)))
      (conj {:field :speech-path
             :message "speech-path must be an absolute path without .."})

      (and (some? chat-path)
           (not (string? chat-path)))
      (conj {:field :chat-path
             :message "chat-path must be a string"})

      (and (string? chat-path)
           (or (not (str/starts-with? chat-path "/"))
               (str/includes? chat-path "..")
               (re-find #"[\r\n]" chat-path)))
      (conj {:field :chat-path
             :message "chat-path must be an absolute path without .."}))))

(defn- service-violations
  [area service]
  (let [processing (:processing service)
        uri (uri-value (:base-url service))
        loopback? (and uri (loopback-host? (.getHost uri)))]
    (cond-> []
      (str/blank? (str (:id service)))
      (conj {:field :id :message "service id is required"})

      (not (string? (:id service)))
      (conj {:field :id :message "service id must be a string"})

      (not (contains? supported-kinds (:kind service)))
      (conj {:field :kind
             :message "kind must be :openai-compatible"})

      (not (string? (:base-url service)))
      (conj {:field :base-url :message "base-url must be a string"})

      (str/blank? (str (:model service)))
      (conj {:field :model :message "model is required"})

      (not (string? (:model service)))
      (conj {:field :model :message "model must be a string"})

      (and (some? processing) (not (contains? processing-values processing)))
      (conj {:field :processing
             :message "processing must be :device or :cloud; system speech stays in the client"})

      (and (= processing :device) (not loopback?))
      (conj {:field :processing
             :message "processing :device requires a loopback base-url"})

      (and (some? (:api-key service)) (not (string? (:api-key service))))
      (conj {:field :api-key :message "api-key must be a string"})

      (and (string? (:api-key service))
           (re-find #"[\r\n]" (:api-key service)))
      (conj {:field :api-key :message "api-key must not contain line breaks"})

      (and (some? (:api-key-env service))
           (not (and (string? (:api-key-env service))
                     (not (str/blank? (:api-key-env service))))))
      (conj {:field :api-key-env :message "api-key-env must be a non-blank string"})

      (not (boolean? (:enabled? service)))
      (conj {:field :enabled? :message "enabled? must be a boolean"})

      (not (integer? (:order service)))
      (conj {:field :order :message "order must be an integer"})

      (not (and (integer? (:connect-timeout-ms service))
                (pos? (:connect-timeout-ms service))))
      (conj {:field :connect-timeout-ms
             :message "connect-timeout-ms must be a positive integer"})

      (not (and (integer? (:request-timeout-ms service))
                (pos? (:request-timeout-ms service))))
      (conj {:field :request-timeout-ms
             :message "request-timeout-ms must be a positive integer"})

      (and (= area :stt)
           (not (contains? stt-response-formats (:response-format service))))
      (conj {:field :response-format
             :message "response-format must be json, text or verbose_json"})

      (and (= area :tts)
           (not (contains? tts-formats (:format service))))
      (conj {:field :format
             :message "format must be wav, mp3, opus, aac, flac or pcm"})

      (and (= area :tts)
           (str/blank? (str (:voice service))))
      (conj {:field :voice
             :message "voice is required for a TTS service"})

      (and (= area :tts)
           (some? (:voice service))
           (not (and (string? (:voice service))
                     (not (str/blank? (:voice service))))))
      (conj {:field :voice :message "voice must be a non-blank string when present"})

      (and (= area :tts)
           (not (and (number? (:speed service))
                     (<= 0.5 (double (:speed service)) 2.0))))
      (conj {:field :speed :message "speed must be between 0.5 and 2.0"})

      (and (= area :stt)
           (not (vector? (:languages service))))
      (conj {:field :languages :message "languages must be a vector"}))))

(defn normalize-service
  [area service index]
  (let [service (or service {})
        string-field (fn [value]
                       (if (string? value)
                         (blank->nil value)
                         value))
        id (string-field (:id service))
        label (or (string-field (:label service)) id)
        kind (as-keyword (or (:kind service) :openai-compatible))
        base-url (if (string? (:base-url service))
                   (-> (:base-url service)
                       str/trim
                       (str/replace #"/+$" ""))
                   (:base-url service))
        processing (some-> (:processing service) as-keyword)
        ;; Never infer "device" from a loopback address alone: a local proxy
        ;; can still forward audio or text.  Omitted processing is treated as
        ;; cloud until the operator explicitly declares device processing.
        processing (or processing :cloud)
        processing-source (if (contains? service :processing)
                            :explicit
                            :inferred)
        common {:id id
                :label label
                :kind kind
                :base-url base-url
                :model (string-field (:model service))
                :api-key-env (string-field (:api-key-env service))
                :processing processing
                :processing-source processing-source
                :enabled? (if (contains? service :enabled?)
                            (:enabled? service)
                            true)
                :order (if (contains? service :order)
                         (:order service)
                         index)
                :connect-timeout-ms (if (contains? service :connect-timeout-ms)
                                      (:connect-timeout-ms service)
                                      5000)
                :request-timeout-ms (if (contains? service :request-timeout-ms)
                                      (:request-timeout-ms service)
                                      (if (= area :tts) 120000 60000))}
        common (cond-> common
                 (contains? service :api-key)
                 (assoc :api-key (:api-key service)))
        service (case area
                  :stt
                  (assoc common
                         :transcriptions-path
                         (path-value (:transcriptions-path service)
                                     "/v1/audio/transcriptions")
                         :response-format
                         (let [value (or (:response-format service) "json")]
                           (if (keyword? value) (name value) value))
                         :languages (let [value (:languages service)]
                                      (if (vector? value)
                                        (vec value)
                                        (or value [])))
                         :test-audio-path (string-field (:test-audio-path service)))

                  :tts
                  (assoc common
                         :speech-path
                         (path-value (:speech-path service)
                                     "/v1/audio/speech")
                         :voice (string-field (:voice service))
                         :format (let [value (or (:format service) "wav")]
                                   (if (keyword? value) (name value) value))
                         :speed (or (:speed service) 1.0)
                         :language (string-field (:language service)))

                  :translation
                  (assoc common
                         :chat-path
                         (path-value (:chat-path service)
                                     "/v1/chat/completions")))]
    (let [violations (into (service-violations area service)
                           (endpoint-violations service))]
      (when (seq violations)
        (error/fail! :SPEECH_CONFIG_INVALID
                     "speech service configuration is invalid"
                     {:area area
                      :service-id (:id service)
                      :violations violations})))
    service))

(defn- normalize-area
  [area value]
  (let [defaults (get (defaults) area)
        value (merge defaults (or value {}))
        services (if (vector? (:services value))
                   (:services value)
                   (error/fail! :SPEECH_CONFIG_INVALID
                                "services must be a vector"
                                {:area area
                                 :field :services
                                 :value (:services value)}))
        services (mapv (fn [index service]
                         (normalize-service area service index))
                       (range)
                       services)
        ids (mapv :id services)
        duplicate (first (for [[id n] (frequencies ids)
                               :when (> n 1)]
                           id))
        default-id (:default-service-id value)
        violations (cond-> []
                     duplicate
                     (conj {:field :services
                            :message (str "duplicate service id " duplicate)})

                     (and (some? default-id)
                          (not (contains? (set ids) default-id)))
                     (conj {:field :default-service-id
                            :message "default-service-id does not name a service"})

                     (and (some? default-id) (not (string? default-id)))
                     (conj {:field :default-service-id
                            :message "default-service-id must be a string"}))]
    (when (seq violations)
      (error/fail! :SPEECH_CONFIG_INVALID
                   "speech area configuration is invalid"
                   {:area area :violations violations}))
    (assoc value :services services :default-service-id default-id)))

(defn- positive-long
  [value field]
  (when-not (or (integer? value)
                (and (string? value)
                     (re-matches #"-?[0-9]+" value)))
    (error/fail! :SPEECH_CONFIG_INVALID
                 (str field " must be an integer")
                 {:field field :value value}))
  (let [n (long value)]
    (when-not (pos? n)
      (error/fail! :SPEECH_CONFIG_INVALID
                   (str field " must be positive")
                   {:field field :value value}))
    n))

(defn- string-leaves?
  [value]
  (cond
    (map? value) (every? string-leaves? (vals value))
    (string? value) true
    :else false))

(defn normalize
  "Normalize and validate one speech configuration map."
  [config]
  (let [config (or config {})
        stt (normalize-area :stt (:stt config))
        tts (normalize-area :tts (:tts config))
        translation (normalize-area :translation (:translation config))
        stt (update stt :max-duration-ms
                    (fn [value]
                      (let [value (positive-long (or value max-input-duration-ms)
                                                 :max-duration-ms)]
                        (when-not (<= 0 value max-input-duration-ms)
                          (error/fail! :SPEECH_CONFIG_INVALID
                                       "max-duration-ms must be between 0 and five minutes"
                                       {:field :max-duration-ms
                                        :value value}))
                        value)))
        stt (update stt :max-audio-bytes
                    #(positive-long (or % (* 25 1024 1024))
                                    :max-audio-bytes))
        tts (update tts :max-audio-bytes
                    #(positive-long (or % (* 50 1024 1024))
                                    :max-audio-bytes))
        tts (update tts :max-text-chars
                    #(positive-long (or % 20000) :max-text-chars))
        tts (update tts :table-min-rows
                    #(positive-long (or % 4) :table-min-rows))
        tts (update tts :speed (fn [value]
                                 (let [value (try
                                               (double (or value 1.0))
                                               (catch Throwable _
                                                 (error/fail! :SPEECH_CONFIG_INVALID
                                                              "tts speed must be a number"
                                                              {:field :speed
                                                               :value value})))]
                                   (when-not (<= 0.5 value 2.0)
                                     (error/fail! :SPEECH_CONFIG_INVALID
                                                  "tts speed must be between 0.5 and 2.0"
                                                  {:field :speed :value value}))
                                   value)))
        tts (update tts :notices
                    (fn [value]
                      (let [value (or value default-notices)]
                        (when-not (and (map? value)
                                       (string-leaves? value))
                          (error/fail! :SPEECH_CONFIG_INVALID
                                       "notices must be a map of strings"
                                       {:field :notices}))
                        value)))
        tts (update tts :test-text
                    (fn [value]
                      (when-not (string? value)
                        (error/fail! :SPEECH_CONFIG_INVALID
                                     "test-text must be a string"
                                     {:field :test-text}))
                      value))
        tts (reduce (fn [value field]
                      (when-not (boolean? (get value field))
                        (error/fail! :SPEECH_CONFIG_INVALID
                                     (str field " must be a boolean")
                                     {:field field}))
                      value)
                    tts
                    [:skip-code? :skip-urls? :skip-tables? :skip-formulas?])
        translation (update translation :max-text-chars
                            #(positive-long (or % 20000)
                                            :max-text-chars))]
    {:revision (long (or (:revision config) 0))
     :updated-at (:updated-at config)
     :stt stt
     :tts tts
     :translation translation}))

(defn load-config
  "Read PATH.  Returns nil when the file is absent."
  [path]
  (when (files/exists? path)
    (when-not (files/regular-file? path)
      (error/fail! :SPEECH_CONFIG_INVALID
                   "speech configuration path is not a regular file"
                   {:path (str path)}))
    (let [value (files/read-edn path)]
      (when-not (map? value)
        (error/fail! :SPEECH_CONFIG_INVALID
                     "speech configuration must be one EDN map"
                     {:path (str path)}))
      (try
        (normalize value)
        (catch clojure.lang.ExceptionInfo t
          (if (error/expected? t)
            (throw (ex-info (ex-message t)
                            (update (ex-data t) :error/details
                                    #(assoc (or % {}) :path (str path)))
                            t))
            (throw t)))))))

(defn save-config!
  "Persist NEXT with a revision guard.  Returns the normalized saved map."
  [path next expected-revision now]
  (let [current (or (load-config path) (defaults))
        current-revision (:revision current)
        expected (long (or expected-revision current-revision))]
    (if (not= expected current-revision)
      {:ok? false
       :error {:code :SPEECH_CONFIG_CONFLICT
               :message "speech configuration changed in another writer; re-read before saving"
               :details {:revision current-revision}}}
      (let [saved (normalize (assoc next
                                    :revision (inc current-revision)
                                    :updated-at now))]
        (files/write-edn! path saved)
        {:ok? true :config saved}))))

(defn find-service
  [config area service-id]
  (first (filter #(= service-id (:id %)) (get-in config [area :services]))))

(defn enabled-services
  [config area]
  (->> (get-in config [area :services])
       (filter :enabled?)
       (sort-by (juxt :order :id))
       vec))

(defn select-service
  "Select an explicit service, the area default, or the only enabled service.
  There is no automatic fallback to a second provider."
  [config area service-id]
  (let [explicit (when service-id (find-service config area service-id))
        default-id (get-in config [area :default-service-id])
        default (when default-id (find-service config area default-id))
        enabled (enabled-services config area)]
    (cond
      (and service-id (nil? explicit))
      (error/fail! :SPEECH_SERVICE_NOT_FOUND
                   "speech service is not configured"
                   {:area area :service-id service-id})

      (and explicit (not (:enabled? explicit)))
      (error/fail! :SPEECH_SERVICE_DISABLED
                   "speech service is disabled"
                   {:area area :service-id service-id})

      explicit explicit

      (and default (not (:enabled? default)))
      (error/fail! :SPEECH_SERVICE_DISABLED
                   "the default speech service is disabled; choose another service"
                   {:area area :service-id default-id :default? true})

      default default
      (= 1 (count enabled)) (first enabled)
      (empty? enabled)
      (error/fail! :SPEECH_SERVICE_NOT_CONFIGURED
                   "no enabled speech service is configured"
                   {:area area})
      :else
      (error/fail! :SPEECH_SERVICE_REQUIRED
                   "choose a speech service; more than one is enabled"
                   {:area area
                    :service-ids (mapv :id enabled)}))))

(defn- key-status
  [area service secret-fn]
  (let [inline? (and secret-fn (secret-fn area service))
        env-value (some-> (:api-key-env service) System/getenv)
        environment? (and env-value (not (str/blank? env-value)))]
    {:has-key? (boolean (or inline? environment?))
     :key-source (cond
                   inline? :inline
                   environment? :environment
                   :else nil)}))

(defn public-service
  [area service secret-fn status-fn]
  (let [key-status (key-status area service secret-fn)
        status (or (status-fn area service) {:state :unknown})]
    (-> service
        (dissoc :api-key :test-audio-path)
        (assoc :has-key? (:has-key? key-status)
               :key-source (:key-source key-status)
               :test-audio-configured? (boolean (:test-audio-path service))
               :status status))))

(defn public-config
  "Return a snapshot without any inline secret."
  [config secret-fn status-fn]
  (let [view (fn [area]
               (-> (get config area)
                   (update :services
                           (fn [services]
                             (mapv #(public-service area % secret-fn status-fn)
                                   (sort-by (juxt :order :id) services))))))]
    {:revision (:revision config)
     :stt (view :stt)
     :tts (view :tts)
     :translation (view :translation)}))

(defn extract-inline-secrets
  "Move inline API keys into SECRETS.  The returned config is safe to persist."
  [config]
  (let [secrets (atom {})]
    (doseq [area [:stt :tts :translation]
            service (get-in config [area :services])
            :let [key (:api-key service)]
            :when (and (string? key) (not (str/blank? key)))]
      (swap! secrets assoc [area (:id service)] key))
    (let [config (reduce (fn [config area]
                           (update config area
                                   (fn [area-config]
                                     (update area-config :services
                                             (fn [services]
                                               (mapv #(dissoc % :api-key)
                                                     services))))))
                         config
                         [:stt :tts :translation])]
      [config @secrets])))
