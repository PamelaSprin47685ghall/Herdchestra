(ns hc.server.speech
  "Speech input and text-to-speech service adapters.

  Authority: AGENTS.md §7.4 旅程 6.7（用语音） and the detailed §6.8 contract.

  The component owns two independent service lists, one for speech input and
  one for read-aloud.  Every provider is a real HTTP endpoint with an explicit
  model.  There is no synthetic provider and no automatic fallback: a failed
  transcription keeps the original recording and the client chooses whether to
  retry with another configured service.

  A transcript is returned as a draft instruction with `:auto-send? false`.
  The server never creates a user message from speech.  Read-aloud audio is
  stored as an artifact and fetched through the binary HTTP route, so the
  Sente channel is never used for large bytes.

  Integration:

      (def component (start! {:data-dir data-dir :config (:speech config)}))
      (handle! component {:kind :speech/snapshot})
      (handler component) ; Ring handler for /speech/* only, nil otherwise
      (stop! component)"
  (:require [clojure.string :as str]
            [hc.server.speech.audio :as audio]
            [hc.server.speech.config :as config]
            [hc.server.speech.error :as error]
            [hc.server.speech.files :as files]
            [hc.server.speech.http :as http]
            [hc.server.speech.json :as json]
            [hc.server.speech.store :as store]
            [taoensso.timbre :as log]
            [hc.server.speech.stt :as stt]
            [hc.server.speech.text :as speech-text]
            [hc.server.speech.translation :as translation]
            [hc.server.speech.tts :as tts]
            [hc.server.speech.usage :as usage])
  (:import (java.net URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.util Base64)))

;; ---------------------------------------------------------------------------
;; Component

(defn- byte-array?
  [value]
  (instance? (Class/forName "[B") value))

(defn start!
  "Create the speech component.  `:data-dir` supplies the default root
  `<data-dir>/speech`; `:config` is the root `:speech` map.  A persisted
  config file wins over the bootstrap map."
  [{:keys [data-dir root config config-path recordings-root audio-root
           clock test-audio usage-port]}]
  (let [root (or root
                 (when-not (str/blank? (str data-dir))
                   (str data-dir "/speech")))]
    (when (str/blank? (str root))
      (error/fail! :SPEECH_ROOT_REQUIRED
                   "speech component needs a data directory"
                   {}))
    (let [root (files/ensure-dir! root)
          config-path (files/path (or config-path (str root "/config.edn")))
          persisted (config/load-config config-path)
          initial (if persisted
                    persisted
                    (config/normalize (or config {})))
          clock (or clock #(System/currentTimeMillis))
          [initial secrets] (config/extract-inline-secrets initial)
          ;; A manually edited config file may still contain an inline key.
          ;; Keep it only in process memory and rewrite the durable file
          ;; without it; otherwise the secret remains on disk forever.
          scrubbed (when (and persisted (seq secrets))
                     (let [result (config/save-config! config-path initial
                                                       (:revision initial)
                                                       (clock))]
                       (when-not (:ok? result)
                         (error/fail! :SPEECH_CONFIG_SCRUB_FAILED
                                      "speech configuration could not be rewritten without its inline key"
                                      {:error (:error result)}))
                       (:config result)))
          initial (or scrubbed initial)
          component {:root root
                     :config-path config-path
                     :recordings-root (files/ensure-dir!
                                       (or recordings-root
                                           (str root "/recordings")))
                     :audio-root (files/ensure-dir!
                                  (or audio-root (str root "/audio")))
                     :config (atom initial)
                     :secrets (atom secrets)
                     :status (atom {})
                     :store-lock (Object.)
                     :clock clock
                     :test-audio test-audio
                     :usage-port usage-port
                     :started-at (clock)}]
      component)))

(defn stop!
  [_component]
  {:stopped? true})

(defn- now
  [component]
  ((:clock component)))

(defn- inline-secret
  [component area service]
  (get @(:secrets component) [area (:id service)]))

(defn- service-status
  [component area service]
  (get @(:status component) [area (:id service)]))

(defn- public-service
  [component area service]
  (config/public-service area service
                         (partial inline-secret component)
                         (fn [area service]
                           (service-status component area service))))

(defn snapshot
  [component]
  (let [cfg @(:config component)
        public (config/public-config
                cfg
                (partial inline-secret component)
                (fn [area service]
                  (service-status component area service)))
        configured? (boolean
                     (some :enabled?
                           (concat (get-in cfg [:stt :services])
                                   (get-in cfg [:tts :services])
                                   (get-in cfg [:translation :services]))))]
    {:speech (assoc public :configured? configured?)}))

;; ---------------------------------------------------------------------------
;; Configuration commands

(defn- area!
  [payload]
  (let [area (:area payload)]
    (when-not (contains? #{:stt :tts :translation} area)
      (error/fail! :SPEECH_AREA_INVALID
                   "area must be :stt, :tts or :translation"
                   {:area area}))
    area))

(defn- service-id!
  [payload]
  (let [id (or (get-in payload [:service :id])
               (:service-id payload))]
    (when (str/blank? (str id))
      (error/fail! :SPEECH_SERVICE_ID_REQUIRED
                   "service id is required"
                   {}))
    (str id)))

(defn- commit-config!
  [component next-config expected-revision]
  (let [expected (or expected-revision
                     (:revision @(:config component)))
        result (config/save-config! (:config-path component)
                                    next-config
                                    expected
                                    (now component))]
    (when (:ok? result)
      (reset! (:config component) (:config result)))
    result))

(defn- save-service!
  [component payload]
  (let [area (area! payload)
        incoming (:service payload)]
    (when-not (map? incoming)
      (error/fail! :SPEECH_SERVICE_INVALID
                   "service must be a map"
                   {:area area}))
    (let [cfg @(:config component)
          id (service-id! payload)
          existing (config/find-service cfg area id)
          merged (merge existing incoming {:id id})
          services (get-in cfg [area :services])
          index (if existing
                  (:order existing)
                  (count services))
          normalized (config/normalize-service area merged index)
          persisted (dissoc normalized :api-key)
          next-services (if existing
                          (mapv #(if (= id (:id %)) persisted %) services)
                          (conj (vec services) persisted))
          next-config (assoc-in cfg [area :services] next-services)
          next-config (if (and (nil? (get-in next-config
                                             [area :default-service-id]))
                               (:enabled? normalized))
                        (assoc-in next-config
                                  [area :default-service-id] id)
                        next-config)
          result (commit-config! component next-config
                                 (:expected-revision payload))]
      (if-not (:ok? result)
        result
        (let [clear-key? (or (:clear-api-key? payload)
                             (and (contains? incoming :api-key)
                                  (str/blank? (str (:api-key incoming)))))
              secret-key [area id]]
          (cond
            clear-key? (swap! (:secrets component) dissoc secret-key)
            (and (contains? incoming :api-key)
                 (string? (:api-key incoming))
                 (not (str/blank? (:api-key incoming))))
            (swap! (:secrets component) assoc secret-key (:api-key incoming)))
          (swap! (:status component) assoc secret-key {:state :unknown})
          {:ok? true
           :receipt {:status :accepted
                     :config-revision (get-in result [:config :revision])
                     :restart-required false
                     :service (public-service
                               component area
                               (config/find-service (:config result)
                                                    area id))}})))))

(defn- delete-service!
  [component payload]
  (let [area (area! payload)
        id (service-id! payload)
        cfg @(:config component)]
    (when-not (config/find-service cfg area id)
      (error/fail! :SPEECH_SERVICE_NOT_FOUND
                   "speech service is not configured"
                   {:area area :service-id id}))
    (let [next-config (-> cfg
                          (update-in [area :services]
                                     (fn [services]
                                       (vec (remove #(= id (:id %))
                                                    services))))
                          (update-in [area :default-service-id]
                                     #(when-not (= % id) %)))
          result (commit-config! component next-config
                                 (:expected-revision payload))]
      (if-not (:ok? result)
        result
        (do
          (swap! (:secrets component) dissoc [area id])
          (swap! (:status component) dissoc [area id])
          {:ok? true
           :receipt {:status :accepted
                     :config-revision (get-in result [:config :revision])
                     :service-id id
                     :deleted? true
                     :recordings-preserved? true}})))))

(defn- set-default!
  [component payload]
  (let [area (area! payload)
        id (service-id! payload)
        cfg @(:config component)
        service (config/find-service cfg area id)]
    (when-not service
      (error/fail! :SPEECH_SERVICE_NOT_FOUND
                   "speech service is not configured"
                   {:area area :service-id id}))
    (when-not (:enabled? service)
      (error/fail! :SPEECH_SERVICE_DISABLED
                   "a disabled service cannot be the default"
                   {:area area :service-id id}))
    (let [result (commit-config!
                  component
                  (assoc-in cfg [area :default-service-id] id)
                  (:expected-revision payload))]
      (if-not (:ok? result)
        result
        {:ok? true
         :receipt {:status :accepted
                   :config-revision (get-in result [:config :revision])
                   :area area
                   :default-service-id id}}))))

(defn- reorder-services!
  [component payload]
  (let [area (area! payload)
        ids (:service-ids payload)
        cfg @(:config component)
        services (get-in cfg [area :services])]
    (when-not (vector? ids)
      (error/fail! :SPEECH_SERVICE_ORDER_INVALID
                   "service-ids must be a vector"
                   {:area area}))
    (when-not (and (= (count ids) (count (set ids)))
                   (= (set ids) (set (map :id services))))
      (error/fail! :SPEECH_SERVICE_ORDER_INVALID
                   "service-ids must name every configured service once"
                   {:area area
                    :configured (mapv :id services)
                    :provided ids}))
    (let [by-id (into {} (map (juxt :id identity)) services)
          reordered (mapv (fn [index id]
                            (assoc (get by-id id) :order index))
                          (range)
                          ids)
          result (commit-config!
                  component
                  (assoc-in cfg [area :services] reordered)
                  (:expected-revision payload))]
      (if-not (:ok? result)
        result
        {:ok? true
         :receipt {:status :accepted
                   :config-revision (get-in result [:config :revision])
                   :area area
                   :service-ids ids}}))))

;; ---------------------------------------------------------------------------
;; Test commands

(defn- read-test-audio
  [component service payload]
  (let [limit (* 5 1024 1024)
        checked (fn [^bytes bytes]
                  (when (> (alength bytes) limit)
                    (error/fail! :SPEECH_TEST_AUDIO_TOO_LARGE
                                 "test audio is too large"
                                 {:bytes (alength bytes) :limit limit}))
                  bytes)
        from-path (fn [path]
                    (when-not (str/blank? (str path))
                      (let [path (files/path path)]
                        (when-not (files/regular-file? path)
                          (error/fail! :SPEECH_TEST_AUDIO_MISSING
                                       "test audio file does not exist"
                                       {:path (str path)}))
                        (let [size (Files/size path)]
                          (when (> size limit)
                            (error/fail! :SPEECH_TEST_AUDIO_TOO_LARGE
                                         "test audio is too large"
                                         {:bytes size :limit limit}))
                          (checked (Files/readAllBytes path))))))]
    (cond
      (byte-array? (:test-audio component)) (checked (:test-audio component))
      (string? (:test-audio-path service)) (from-path (:test-audio-path service))
      :else nil)))

(defn- update-status!
  [component area service status]
  (swap! (:status component) assoc [area (:id service)] status)
  status)

(declare synthesize-command!)

(defn- stt-test!
  [component payload]
  (let [cfg @(:config component)
        service (config/select-service cfg :stt (:service-id payload))
        audio (read-test-audio component service payload)
        now (now component)]
    (if (nil? audio)
      (do
        (update-status! component :stt service
                        {:state :not-run
                         :reason "no neutral test audio is configured"
                         :last-tested-at now})
        {:ok? true
         :receipt {:status :complete
                   :service (public-service component :stt service)
                   :layers {:reachable {:status :not-run}
                            :auth-ok {:status :not-run}
                            :transcript {:status :not-run}
                            :test-audio {:status :not-run
                                         :reason "no neutral test audio is configured"}}}})
      (let [result (stt/transcribe
                    service audio
                    {:language (or (:language payload)
                                   (first (:languages service))
                                   "en")
                     :api-key (inline-secret component :stt service)
                     :response-format "json"
                     :filename "speech-test.wav"
                     :content-type "audio/wav"})
            error (:error result)
            auth-failed? (= :SPEECH_AUTH_FAILED (:code error))
            layers {:reachable {:status (if (:ok? result) :ok :failed)}
                    :auth-ok {:status (if auth-failed? :failed :ok)}
                    :transcript (if (:ok? result)
                                  {:status (if (str/blank? (:text result))
                                             :empty
                                             :ok)
                                   :text (:text result)
                                   :language (:language result)}
                                  {:status :failed
                                   :error error})
                    :test-audio {:status :ok
                                 :bytes (alength ^bytes audio)}}]
        (update-status! component :stt service
                        (if (:ok? result)
                          {:state :ok :last-tested-at now}
                          {:state :failed
                           :last-tested-at now
                           :last-error (select-keys error [:code :message])}))
        {:ok? true
         :receipt {:status :complete
                   :service (public-service component :stt service)
                   :layers layers}}))))

(defn- tts-test!
  [component payload]
  (let [cfg @(:config component)
        service (config/select-service cfg :tts (:service-id payload))
        text (or (get-in cfg [:tts :test-text])
                 "Herdchestra speech test.")
        result (synthesize-command!
                component
                {:text text
                 :service-id (:id service)
                 :purpose :test
                 :notice-language :en})
        layers (if (:ok? result)
                 {:reachable {:status :ok}
                  :auth-ok {:status :ok}
                  :audio {:status (if (= :partial (get-in result [:receipt :status]))
                                    :partial
                                    :ok)
                          :audio-id (get-in result [:receipt :audio-id])
                          :bytes (get-in result [:receipt :bytes])
                          :content-type (get-in result [:receipt :content-type])}}
                 {:reachable {:status :failed}
                  :auth-ok {:status (if (= :SPEECH_AUTH_FAILED
                                          (get-in result [:error :code]))
                                      :failed
                                      :ok)}
                  :audio {:status :failed
                          :error (:error result)}})]
    {:ok? true
     :receipt {:status :complete
               :service (public-service component :tts service)
               :layers layers}}))

(defn- translation-test!
  [component payload]
  (let [cfg @(:config component)
        service (config/select-service cfg :translation
                                       (:service-id payload))
        text "The quick brown fox jumps over the lazy dog."
        result (translation/translate
                service text
                {:source-language "en"
                 :target-language "zh-CN"
                 :api-key (inline-secret component :translation service)})
        error (:error result)
        auth-failed? (= :SPEECH_AUTH_FAILED (:code error))
        layers {:reachable {:status (if (:ok? result) :ok :failed)}
                :auth-ok {:status (if auth-failed? :failed :ok)}
                :translated (if (:ok? result)
                              {:status (if (str/blank? (:translated-text result))
                                         :empty
                                         :ok)
                               :text (:translated-text result)}
                              {:status :failed :error error})}]
    (update-status! component :translation service
                    (if (:ok? result)
                      {:state :ok :last-tested-at (now component)}
                      {:state :failed
                       :last-tested-at (now component)
                       :last-error (select-keys error [:code :message])}))
    {:ok? true
     :receipt {:status :complete
               :service (public-service component :translation service)
               :layers layers}}))

;; ---------------------------------------------------------------------------
;; Transcription

(defn- recording-view
  [meta]
  (-> meta
      (select-keys [:recording-id :session-id :client-id :content-type
                    :filename :bytes :duration-ms :language :status
                    :transcript :transcript-language :service-id :attempts
                    :error :created-at :updated-at])
      (assoc :audio-preserved? (not= :corrupt (:status meta)))))

(defn- draft-instruction
  [payload transcript]
  (when-not (str/blank? transcript)
    (cond-> {:action :insert-into-draft
             :text transcript
             :auto-send? false}
      (:session-id payload) (assoc :session-id (:session-id payload))
      (some? (:insert-at payload)) (assoc :insert-at (:insert-at payload))
      (and (nil? (:insert-at payload))
           (some? (:cursor payload))) (assoc :insert-at (:cursor payload))
      (some? (:expected-draft-revision payload))
      (assoc :expected-revision (:expected-draft-revision payload)))))

(defn- duration-error
  [duration max-duration]
  (cond
    (nil? duration)
    {:code :SPEECH_DURATION_REQUIRED
     :message "duration-ms is required for a voice input"}

    (not (and (number? duration)
              (== (double duration) (double (long duration)))))
    {:code :SPEECH_DURATION_INVALID
     :message "duration-ms must be a whole number"}

    (neg? (long duration))
    {:code :SPEECH_DURATION_INVALID
     :message "duration-ms must not be negative"}

    (> (long duration) (long max-duration))
    {:code :SPEECH_RECORDING_TOO_LONG
     :message "a single voice input is limited to five minutes"
     :details {:duration-ms duration
               :max-duration-ms max-duration}}

    :else nil))

(defn- cloud-service?
  [cfg payload]
  (try
    (= :cloud (:processing (config/select-service cfg :stt (:service-id payload))))
    (catch Throwable _ false)))

(defn- effective-duration
  "Use the larger of the declared duration and the container duration.  A
  client may overstate a recording; it must not be able to understate one.
  A cloud provider additionally requires a verifiable container duration."
  [component payload audio-bytes content-type]
  (let [cfg @(:config component)
        declared (:duration-ms payload)
        computed (audio/duration-ms audio-bytes content-type)
        duration (cond
                   (and (number? declared) (number? computed))
                   (max (long declared) (long computed))
                   (number? declared) declared
                   (number? computed) computed
                   :else nil)
        max-duration (get-in cfg [:stt :max-duration-ms])
        problem (duration-error duration max-duration)]
    (cond
      problem {:error problem}

      (and (cloud-service? cfg payload) (nil? computed))
      {:error {:code :SPEECH_DURATION_UNVERIFIABLE
               :message "the recording duration cannot be verified; a cloud service will not receive it"
               :details {:content-type content-type
                         :declared-duration-ms declared}}}

      :else {:duration duration})))

(defn- fail-recording!
  [component recording error-map]
  (let [id (:recording-id recording)
        updated (try
                  (locking (:store-lock component)
                    (store/update-recording!
                     (:root component) id
                     (fn [meta]
                       (assoc meta
                              :status :failed
                              :error error-map))
                     (now component)))
                  true
                  (catch Throwable _ false))]
    {:ok? false
     :error (update error-map :details
                    #(merge (or % {})
                            {:recording-id id
                             :audio-preserved? true
                             :recording-persisted? updated}))}))

(defn- attempt-transcription!
  [component recording audio payload]
  (let [cfg @(:config component)
        id (:recording-id recording)
        started (now component)
        service (config/select-service cfg :stt (:service-id payload))]
    (let [result (stt/transcribe
                  service audio
                  {:language (or (:language payload)
                                 (:language recording)
                                 (get-in cfg [:stt :language]))
                   :api-key (inline-secret component :stt service)
                   :response-format (or (:response-format service)
                                        (get-in cfg [:stt :response-format]))
                   :filename (:filename recording)
                   :content-type (:content-type recording)})
          finished (now component)
          attempt {:service-id (:id service)
                   :started-at started
                   :finished-at finished
                   :status (if (:ok? result) :ok :failed)
                   :http-status (get-in result [:error :details :http-status])
                   :error (when-not (:ok? result)
                            (select-keys (:error result)
                                         [:code :message :retryable?]))}]
      (if (:ok? result)
        (let [text (or (:text result) "")
              empty? (str/blank? text)
              status (if empty? :empty :transcribed)
              updated? (try
                         (locking (:store-lock component)
                           (store/update-recording!
                            (:root component) id
                            (fn [meta]
                              (-> meta
                                  (assoc :status status
                                         :transcript text
                                         :transcript-language
                                         (or (:language result)
                                             (:language payload)
                                             (:language recording))
                                         :service-id (:id service)
                                         :error nil)
                                  (update :attempts (fnil conj []) attempt)))
                            (now component)))
                         true
                         (catch Throwable _ false))
              usage-id (str "stt-" id "-" (:id service) "-"
                            (count (:attempts recording)))
              usage-record (usage/stt-record
                            (:raw result)
                            {:usage-id usage-id
                             :session-id (or (:session-id payload)
                                             (:session-id recording))
                             :turn-id (:turn-id payload)
                             :service-id (:id service)
                             :service-name (or (:label service)
                                               (:id service))
                             :name "stt"
                             :at finished})
              usage-recorded? (usage/record! component usage-record)
              receipt {:status (if empty? :empty :complete)
                       :recording-id id
                       :transcript text
                       :transcript-language (:language result)
                       :service (public-service component :stt service)
                       :attempt attempt
                       :usage (usage/stt-usage (:raw result))
                       :usage-recorded? usage-recorded?
                       :recording-persisted? updated?
                       :audio {:preserved? true
                               :bytes (:bytes recording)
                               :content-type (:content-type recording)}
                       :draft (when-not empty?
                                (draft-instruction payload text))
                       :auto-send? false}]
          (update-status! component :stt service
                          {:state :ok :last-used-at finished})
          {:ok? true :receipt receipt})
        (let [error-map (:error result)
              updated? (try
                         (locking (:store-lock component)
                           (store/update-recording!
                            (:root component) id
                            (fn [meta]
                              (-> meta
                                  (assoc :status :failed
                                         :error error-map)
                                  (update :attempts (fnil conj []) attempt)))
                            (now component)))
                         true
                         (catch Throwable _ false))
              details (merge (or (:details error-map) {})
                             {:recording-id id
                              :audio-preserved? true
                              :recording-persisted? updated?
                              :service (public-service component :stt service)})]
          (update-status! component :stt service
                          {:state :failed
                           :last-used-at finished
                           :last-error (select-keys error-map
                                                    [:code :message :retryable?])})
          {:ok? false
           :error (assoc error-map :details details)})))))

(defn- transcribe-bytes!
  [component payload ^bytes audio]
  (let [cfg @(:config component)
        max-duration (get-in cfg [:stt :max-duration-ms])
        created (locking (:store-lock component)
                  (store/create-recording!
                   (:root component)
                   {:bytes audio
                    :content-type (or (:content-type payload)
                                      "application/octet-stream")
                    :filename (:filename payload)
                    :duration-ms (:duration-ms payload)
                    :session-id (:session-id payload)
                    :client-id (:client-id payload)
                    :language (:language payload)
                    :max-bytes (get-in cfg [:stt :max-audio-bytes])
                    :now (now component)}))
        recording (:recording created)
        checked (effective-duration component payload audio
                                    (or (:content-type payload)
                                        "application/octet-stream"))]
    (if-let [duration-problem (:error checked)]
      (fail-recording! component recording
                       (assoc duration-problem
                              :details (merge (:details duration-problem)
                                              {:max-duration-ms max-duration})))
      (try
        (attempt-transcription! component recording audio
                                (assoc payload :duration-ms (:duration checked)))
        (catch clojure.lang.ExceptionInfo t
          (if (error/expected? t)
            (fail-recording! component recording (error/error-map t))
            (throw t)))))))

(defn- decode-audio!
  [payload]
  (cond
    (byte-array? (:audio payload)) (:audio payload)
    (string? (:audio-base64 payload))
    (try
      (.decode (Base64/getDecoder) ^String (:audio-base64 payload))
      (catch IllegalArgumentException _
        (error/fail! :SPEECH_AUDIO_INVALID
                     "audio-base64 is not valid base64"
                     {})))
    :else
    (error/fail! :SPEECH_AUDIO_REQUIRED
                 "audio-base64 or audio bytes are required"
                 {})))

(defn- transcribe-command!
  [component payload]
  (transcribe-bytes! component payload (decode-audio! payload)))

(defn- retry-transcription!
  [component payload]
  (let [id (:recording-id payload)
        _ (when (str/blank? (str id))
            (error/fail! :SPEECH_RECORDING_ID_REQUIRED
                         "recording-id is required"
                         {}))
        cfg @(:config component)
        loaded (store/read-recording-audio
                (:root component) id
                {:max-bytes (get-in cfg [:stt :max-audio-bytes])})
        recording (:meta loaded)
        checked (effective-duration
                 component
                 (assoc payload :duration-ms
                        (or (:duration-ms payload) (:duration-ms recording)))
                 (:bytes loaded)
                 (:content-type recording))]
    (if-let [duration-problem (:error checked)]
      (fail-recording! component recording
                       (assoc duration-problem
                              :details (merge (:details duration-problem)
                                              {:max-duration-ms
                                               (get-in cfg [:stt :max-duration-ms])})))
      (try
        (attempt-transcription! component recording (:bytes loaded)
                                (assoc payload
                                       :duration-ms (:duration checked)
                                       :content-type (:content-type recording)))
        (catch clojure.lang.ExceptionInfo t
          (if (error/expected? t)
            (fail-recording! component recording (error/error-map t))
            (throw t)))))))

(defn- recordings-command!
  [component payload]
  (let [limit (try
                (min 200 (max 1 (long (or (:limit payload) 50))))
                (catch Throwable _ 50))
        items (store/list-recordings
               (:root component)
               {:session-id (:session-id payload) :limit limit})]
    {:ok? true
     :receipt {:status :accepted
               :recordings (mapv recording-view items)}}))

(defn- recording-command!
  [component payload]
  (let [id (:recording-id payload)
        meta (store/read-recording-meta (:root component) id)]
    (if meta
      {:ok? true :receipt {:status :accepted :recording (recording-view meta)}}
      {:ok? false
       :error {:code :SPEECH_RECORDING_NOT_FOUND
               :message "recording does not exist"
               :details {:recording-id id}}})))

(defn- delete-recording!
  [component payload]
  (let [id (:recording-id payload)
        deleted? (locking (:store-lock component)
                   (store/delete-recording! (:root component) id))]
    {:ok? true
     :receipt {:status :complete
               :recording-id id
               :deleted? deleted?
               :transcript-preserved? true}}))

;; ---------------------------------------------------------------------------
;; Synthesis

(defn- audio-view
  [meta]
  (-> meta
      (select-keys [:audio-id :content-type :format :bytes :purpose
                    :partial? :message-id :version-id :service-id :error
                    :created-at :updated-at])))

(defn- partial-audio-message
  [result]
  (cond
    (:timed-out? result)
    "the provider response timed out; the received part is playable"

    (:truncated? result)
    "audio exceeded the local limit; the received part is playable"

    :else
    "the provider stream ended early; the received audio is playable"))

(defn- synthesize-command!
  [component payload]
  (let [cfg @(:config component)
        tts-cfg (:tts cfg)
        text (:text payload)]
    (when-not (and (string? text) (not (str/blank? text)))
      (error/fail! :SPEECH_TEXT_REQUIRED
                   "text is required for read-aloud"
                   {}))
    (let [prepared (speech-text/prepare
                    text
                    {:notices (:notices tts-cfg)
                     :language (or (:notice-language payload)
                                   (:language payload)
                                   :en)
                     :max-chars (:max-text-chars tts-cfg)
                     :skip-code? (:skip-code? tts-cfg)
                     :skip-urls? (:skip-urls? tts-cfg)
                     :skip-tables? (:skip-tables? tts-cfg)
                     :skip-formulas? (get tts-cfg :skip-formulas? true)
                     :table-min-rows (:table-min-rows tts-cfg)})]
      (when (str/blank? (:text prepared))
        (error/fail! :SPEECH_TEXT_EMPTY
                     "there is no speakable text after skipping code, URLs and tables"
                     {:skipped (:skipped prepared)}))
      (let [service (config/select-service cfg :tts (:service-id payload))
            voice (let [value (or (:voice payload) (:voice service))]
                    (when (str/blank? (str value))
                      (error/fail! :SPEECH_VOICE_INVALID
                                   "voice must not be blank"
                                   {}))
                    value)
            format (let [value (or (:format payload) (:format service)
                                   (:format tts-cfg) "wav")]
                     (if (keyword? value) (name value) (str value)))
            speed (let [value (or (:speed payload) (:speed service)
                                  (:speed tts-cfg) 1.0)]
                    (if (number? value)
                      (double value)
                      (error/fail! :SPEECH_SPEED_INVALID
                                   "speed must be a number"
                                   {:speed value})))]
        (when-not (contains? config/tts-formats format)
          (error/fail! :SPEECH_FORMAT_INVALID
                       "unsupported audio format"
                       {:format format
                        :allowed (vec (sort config/tts-formats))}))
        (when-not (<= 0.5 speed 2.0)
          (error/fail! :SPEECH_SPEED_INVALID
                       "speed must be between 0.5 and 2.0"
                       {:speed speed}))
        (let [result (tts/synthesize
                      service (:text prepared)
                      {:voice voice
                       :format format
                       :speed speed
                       :api-key (inline-secret component :tts service)
                       :max-audio-bytes (get-in cfg [:tts :max-audio-bytes])})]
          (if-not (:ok? result)
            (do
              (update-status! component :tts service
                              {:state :failed
                               :last-used-at (now component)
                               :last-error (select-keys (:error result)
                                                        [:code :message
                                                         :retryable?])})
              {:ok? false
               :error (update (:error result) :details
                              #(merge (or % {})
                                      {:service (public-service
                                                 component :tts service)}))})
            (let [partial? (boolean (:partial? result))
                  stored (store/create-audio!
                          (:root component)
                          {:bytes (:bytes result)
                           :content-type (:content-type result)
                           :format format
                           :purpose (or (:purpose payload) :read-aloud)
                           :partial? partial?
                           :message-id (:message-id payload)
                           :version-id (:version-id payload)
                           :service-id (:id service)
                           :session-id (:session-id payload)
                           :client-id (:client-id payload)
                           :error (when partial?
                                    {:code :SPEECH_SYNTHESIS_PARTIAL
                                     :message (partial-audio-message result)
                                     :retryable? true})
                           :max-bytes (get-in cfg [:tts :max-audio-bytes])
                           :now (now component)})
                  audio (:audio stored)
                  usage-record (usage/tts-record
                                (:headers result)
                                {:usage-id (str "tts-" (:audio-id audio))
                                 :session-id (:session-id payload)
                                 :turn-id (:turn-id payload)
                                 :service-id (:id service)
                                 :service-name (or (:label service)
                                                   (:id service))
                                 :name "tts"
                                 :at (now component)})
                  usage-recorded? (usage/record! component usage-record)
                  receipt {:status (if partial? :partial :complete)
                           :audio-id (:audio-id audio)
                           :content-type (:content-type audio)
                           :format format
                           :bytes (:bytes audio)
                           :partial? partial?
                           :timed-out? (boolean (:timed-out? result))
                           :spoken-text (:text prepared)
                           :skipped (:skipped prepared)
                           :truncated? (:truncated? prepared)
                           :service (public-service component :tts service)
                           :voice voice
                           :language (:language service)
                           :speed speed
                           :usage (:usage result)
                           :billed (:billed result)
                           :usage-recorded? usage-recorded?
                           :auto-read? false}]
              (update-status! component :tts service
                              (if partial?
                                {:state :failed
                                 :last-used-at (now component)
                                 :last-error {:code :SPEECH_SYNTHESIS_PARTIAL}}
                                {:state :ok :last-used-at (now component)}))
              (cond-> {:ok? true :receipt receipt}
                partial?
                (assoc :receipt
                       (assoc receipt
                              :error {:code :SPEECH_SYNTHESIS_PARTIAL
                                      :message (partial-audio-message result)
                                      :retryable? true}))))))))))

(defn- audio-session-ok?
  [meta session-id]
  (or (nil? session-id)
      (nil? (:session-id meta))
      (= session-id (:session-id meta))))

(defn- audio-command!
  [component payload]
  (let [id (:audio-id payload)
        meta (store/read-audio-meta (:root component) id)]
    (if (and meta (audio-session-ok? meta (:session-id payload)))
      {:ok? true :receipt {:status :accepted :audio (audio-view meta)}}
      {:ok? false
       :error {:code :SPEECH_AUDIO_NOT_FOUND
               :message "audio does not exist"
               :details {:audio-id id}}})))

(defn- audios-command!
  [component payload]
  (let [limit (try
                (min 200 (max 1 (long (or (:limit payload) 50))))
                (catch Throwable _ 50))]
    {:ok? true
     :receipt {:status :accepted
               :audio (mapv audio-view
                            (store/list-audio (:root component)
                                              {:limit limit
                                               :session-id (:session-id payload)}))}}))

(defn- delete-audio!
  [component payload]
  (let [id (:audio-id payload)
        meta (store/read-audio-meta (:root component) id)]
    (if (and meta (audio-session-ok? meta (:session-id payload)))
      (let [deleted? (store/delete-audio! (:root component) id)]
        {:ok? true
         :receipt {:status :complete
                   :audio-id id
                   :deleted? deleted?}})
      {:ok? false
       :error {:code :SPEECH_AUDIO_NOT_FOUND
               :message "audio does not exist"
               :details {:audio-id id}}})))

(defn- translate-command!
  [component payload]
  (let [cfg @(:config component)
        text (:text payload)
        target (:target-language payload)
        source (or (:source-language payload) "auto")
        max-chars (get-in cfg [:translation :max-text-chars] 20000)]
    (when-not (and (string? text) (not (str/blank? text)))
      (error/fail! :SPEECH_TEXT_REQUIRED
                   "text is required for translation"
                   {}))
    (when-not (and (string? target) (not (str/blank? target)))
      (error/fail! :SPEECH_TARGET_LANGUAGE_REQUIRED
                   "target-language is required"
                   {}))
    (when (> (count text) max-chars)
      (error/fail! :SPEECH_TEXT_TOO_LARGE
                   "translation text exceeds the configured limit"
                   {:chars (count text) :limit max-chars}))
    (let [service (config/select-service cfg :translation
                                         (:service-id payload))
          result (translation/translate
                  service text
                  {:source-language source
                   :target-language target
                   :api-key (inline-secret component :translation service)})
          at (now component)]
      (if-not (:ok? result)
        (do
          (update-status! component :translation service
                          {:state :failed
                           :last-used-at at
                           :last-error (select-keys (:error result)
                                                    [:code :message
                                                     :retryable?])})
          {:ok? false
           :error (update (:error result) :details
                          #(merge (or % {})
                                  {:service (public-service
                                             component :translation service)}))})
        (let [usage-record (usage/translation-record
                            (:raw result)
                            {:usage-id (str "translation-"
                                            (:id service) "-"
                                            (or (:message-id payload) "adhoc")
                                            "-"
                                            (or (:version-id payload) "v0")
                                            "-"
                                            (Integer/toHexString (hash text)))
                             :session-id (:session-id payload)
                             :turn-id (:turn-id payload)
                             :service-id (:id service)
                             :service-name (or (:label service)
                                               (:id service))
                             :name "translation"
                             :at at})
              usage-recorded? (usage/record! component usage-record)]
          (update-status! component :translation service
                          {:state :ok :last-used-at at})
          {:ok? true
           :receipt {:status :complete
                     :translated-text (:translated-text result)
                     :source-text text
                     :source-language source
                     :target-language target
                     :service (public-service component :translation service)
                     :message-id (:message-id payload)
                     :version-id (:version-id payload)
                     :created-at at
                     :original-preserved? true
                     :usage (:usage result)
                     :token-usage (:token-usage result)
                     :usage-recorded? usage-recorded?}})))))

;; ---------------------------------------------------------------------------
;; Command router

(defn handle!
  "Handle one `:speech/*` command.  Returns a receipt or a classified error."
  [component payload]
  (try
    (case (:kind payload)
      :speech/snapshot
      {:ok? true :receipt {:status :accepted :snapshot (snapshot component)}}

      :speech/save-service (save-service! component payload)
      :speech/delete-service (delete-service! component payload)
      :speech/set-default (set-default! component payload)
      :speech/reorder-services (reorder-services! component payload)
      :speech/test-service
      (let [area (area! payload)]
        (case area
          :stt (stt-test! component payload)
          :tts (tts-test! component payload)
          :translation (translation-test! component payload)))

      :speech/transcribe (transcribe-command! component payload)
      :speech/retry-transcription (retry-transcription! component payload)
      :speech/recordings (recordings-command! component payload)
      :speech/recording (recording-command! component payload)
      :speech/delete-recording (delete-recording! component payload)
      :speech/synthesize (synthesize-command! component payload)
      :speech/translate (translate-command! component payload)
      :speech/audio (audio-command! component payload)
      :speech/audios (audios-command! component payload)
      :speech/delete-audio (delete-audio! component payload)

      {:ok? false
       :error {:code :SPEECH_KIND_UNKNOWN
               :message "unknown speech command kind"
               :details {:kind (:kind payload)}}})
    (catch clojure.lang.ExceptionInfo t
      (if (error/expected? t)
        {:ok? false :error (error/error-map t)}
        (throw t)))
    (catch Throwable t
      {:ok? false :error (error/error-map t)})))

;; ---------------------------------------------------------------------------
;; Ring handler for binary audio

(defn- edn-response
  [status body]
  {:status status
   :headers {"content-type" "application/edn; charset=utf-8"
             "cache-control" "no-store"}
   :body (pr-str body)})

(defn- error-status
  [error-map]
  (let [code (:code error-map)]
    (cond
      (contains? #{:SPEECH_AUDIO_TOO_LARGE :SPEECH_TEXT_TOO_LARGE} code)
      413

      (contains? #{:SPEECH_AUDIO_EMPTY :SPEECH_AUDIO_INVALID
                   :SPEECH_AUDIO_REQUIRED :SPEECH_DURATION_REQUIRED
                   :SPEECH_DURATION_INVALID :SPEECH_DURATION_UNVERIFIABLE
                   :SPEECH_RECORDING_TOO_LONG
                   :SPEECH_TEXT_REQUIRED :SPEECH_TEXT_EMPTY
                   :SPEECH_TARGET_LANGUAGE_REQUIRED
                   :SPEECH_FORMAT_INVALID
                   :SPEECH_SPEED_INVALID :SPEECH_VOICE_INVALID
                   :SPEECH_CONFIG_INVALID
                   :SPEECH_MULTIPART_INVALID :SPEECH_AREA_INVALID
                   :SPEECH_SERVICE_ID_REQUIRED :SPEECH_SERVICE_INVALID
                   :SPEECH_SERVICE_ORDER_INVALID :SPEECH_REQUEST_INVALID
                   :SPEECH_BODY_READ_FAILED :SPEECH_PATH_INVALID
                   :SPEECH_JSON_DECODE_FAILED}
                 code) 400

      (contains? #{:SPEECH_RECORDING_NOT_FOUND :SPEECH_AUDIO_NOT_FOUND
                   :SPEECH_SERVICE_NOT_FOUND} code) 404

      (contains? #{:SPEECH_SERVICE_DISABLED :SPEECH_SERVICE_NOT_CONFIGURED
                   :SPEECH_SERVICE_REQUIRED :SPEECH_CONFIG_CONFLICT}
                 code) 409

      (contains? #{:SPEECH_TIMEOUT :SPEECH_UNREACHABLE} code) 504

      (= :SPEECH_RATE_LIMITED code) 429

      (contains? #{:SPEECH_AUTH_FAILED :SPEECH_SERVICE_ERROR
                   :SPEECH_HTTP_ERROR :SPEECH_PROVIDER_ERROR
                   :SPEECH_HTTP_FAILED :SPEECH_STREAM_FAILED
                   :SPEECH_EMPTY_AUDIO :SPEECH_RESPONSE_INVALID
                   :SPEECH_RESPONSE_TOO_LARGE :SPEECH_ENDPOINT_INVALID}
                 code) 502

      :else 422)))

(defn- binary-response
  [opened status extra-headers]
  {:status status
   :headers (merge {"content-type" (or (:content-type opened)
                                       "application/octet-stream")
                    "content-length" (str (:size opened))
                    "cache-control" "no-store"}
                   extra-headers)
   :body (:stream opened)})

(defn- request-header
  [request name]
  (let [headers (:headers request)]
    (or (get headers name)
        (get headers (str/lower-case name))
        (some (fn [[key value]]
                (when (= (str/lower-case (str key))
                         (str/lower-case name))
                  value))
              headers))))

(defn- header-string
  [request name]
  (let [value (some-> (request-header request name) str)]
    (when-not (str/blank? value) value)))

(defn- header-long
  [request name]
  (when-let [value (header-string request name)]
    (try (Long/parseLong value)
         (catch NumberFormatException _ nil))))

(defn- body-bytes
  [request limit]
  (let [body (:body request)]
    (if (nil? body)
      (byte-array 0)
      (let [read (http/read-limited body limit)]
        (cond
          (:limit-exceeded? read)
          (error/fail! :SPEECH_AUDIO_TOO_LARGE
                       "request body exceeds the local limit"
                       {:limit limit})

          (:error read)
          (error/fail! :SPEECH_BODY_READ_FAILED
                       "request body ended before it was complete"
                       {:message (some-> (:error read) .getMessage)})

          :else (:bytes read))))))

(defn- request-payload
  [request]
  {:content-type (header-string request "content-type")
   :duration-ms (header-long request "x-hc-duration-ms")
   :language (header-string request "x-hc-language")
   :service-id (header-string request "x-hc-service-id")
   :session-id (header-string request "x-hc-session-id")
   :client-id (header-string request "x-hc-client-id")
   :filename (header-string request "x-hc-filename")
   :insert-at (header-long request "x-hc-insert-at")
   :expected-draft-revision
   (header-long request "x-hc-expected-draft-revision")})

(defn- segments
  [uri]
  (->> (str/split (or uri "") #"/")
       (remove str/blank?)
       (mapv (fn [segment]
               (try
                 (URLDecoder/decode segment "UTF-8")
                 (catch Throwable _
                   (error/fail! :SPEECH_PATH_INVALID
                                "request path is not valid UTF-8"
                                {})))))))

(defn- speech-path?
  [uri]
  (or (= uri "/speech")
      (str/starts-with? (str uri) "/speech/")))

(defn- method-not-allowed
  [allowed]
  (edn-response 405 {:ok? false
                     :error {:code :SPEECH_METHOD_NOT_ALLOWED
                             :message "method is not allowed for this route"
                             :details {:allow allowed}}}))

(defn- route-recordings
  [component request method rest-segments]
  (cond
    (and (empty? rest-segments) (= method :get))
    (edn-response 200 (recordings-command!
                       component
                       {:session-id (header-string request "x-hc-session-id")
                        :limit (header-long request "x-hc-limit")}))

    (and (empty? rest-segments) (= method :post))
    (let [cfg @(:config component)
          audio (body-bytes request (get-in cfg [:stt :max-audio-bytes]))
          result (transcribe-bytes! component (request-payload request) audio)]
      (if (:ok? result)
        (edn-response 200 result)
        (edn-response (error-status (:error result)) result)))

    (and (= 1 (count rest-segments)) (= method :get))
    (let [id (first rest-segments)
          opened (store/open-recording-audio (:root component) id)
          meta (store/read-recording-meta (:root component) id)]
      (binary-response opened 200
                       {"x-hc-recording-id" id
                        "x-hc-status" (str (:status meta))}))

    (and (= 1 (count rest-segments)) (= method :delete))
    (let [result (delete-recording!
                  component {:recording-id (first rest-segments)})]
      (edn-response 200 result))

    (and (= 2 (count rest-segments))
         (= "metadata" (second rest-segments))
         (= method :get))
    (let [result (recording-command!
                  component {:recording-id (first rest-segments)})]
      (edn-response (if (:ok? result) 200 (error-status (:error result)))
                    result))

    (and (= 2 (count rest-segments))
         (= "retry" (second rest-segments))
         (= method :post))
    (let [result (retry-transcription!
                  component
                  (assoc (request-payload request)
                         :recording-id (first rest-segments)))]
      (edn-response (if (:ok? result) 200 (error-status (:error result)))
                    result))

    :else
    (method-not-allowed "GET, POST, DELETE")))

(defn- route-audio
  [component request method rest-segments]
  (cond
    (and (empty? rest-segments) (= method :get))
    (edn-response 200 (audios-command!
                       component
                       {:limit (header-long request "x-hc-limit")
                        :session-id (header-string request "x-hc-session-id")}))

    (and (= 1 (count rest-segments)) (= method :get))
    (let [id (first rest-segments)
          session-id (header-string request "x-hc-session-id")
          meta (store/read-audio-meta (:root component) id)]
      (if (audio-session-ok? meta session-id)
        (let [opened (store/open-audio (:root component) id)]
          (binary-response opened 200
                           {"x-hc-audio-id" id
                            "x-hc-partial" (str (boolean (:partial? meta)))}))
        (edn-response 404 {:ok? false
                           :error {:code :SPEECH_AUDIO_NOT_FOUND
                                   :message "audio does not exist"
                                   :details {:audio-id id}}})))

    (and (= 1 (count rest-segments)) (= method :delete))
    (let [result (delete-audio! component
                                {:audio-id (first rest-segments)
                                 :session-id (header-string request "x-hc-session-id")})]
      (edn-response (if (:ok? result) 200 (error-status (:error result))) result))

    (and (= 2 (count rest-segments))
         (= "metadata" (second rest-segments))
         (= method :get))
    (let [result (audio-command!
                  component {:audio-id (first rest-segments)})]
      (edn-response (if (:ok? result) 200 (error-status (:error result)))
                    result))

    :else
    (method-not-allowed "GET, DELETE")))

(defn- route-tts
  [component request method]
  (if-not (= method :post)
    (method-not-allowed "POST")
    (let [body (body-bytes request (* 1024 1024))
          payload (json/decode (String. ^bytes body StandardCharsets/UTF_8))]
      (when-not (map? payload)
        (error/fail! :SPEECH_REQUEST_INVALID
                     "text-to-speech request must be a JSON object"
                     {}))
      (let [result (synthesize-command! component payload)]
        (if-not (:ok? result)
          (edn-response (error-status (:error result)) result)
          (let [receipt (:receipt result)
                id (:audio-id receipt)
                opened (store/open-audio (:root component) id)
                meta (store/read-audio-meta (:root component) id)
                partial? (boolean (:partial? receipt))
                headers (cond-> {"x-hc-audio-id" id
                                 "x-hc-partial" (str partial?)
                                 "x-hc-format" (str (:format receipt))
                                 "x-hc-service-id" (str (get-in receipt
                                                               [:service :id]))
                                 "x-hc-spoken-text-chars"
                                 (str (count (str (:spoken-text receipt))))}
                          (:voice receipt)
                          (assoc "x-hc-voice"
                                 (str/replace (str (:voice receipt))
                                              #"[\r\n]" " ")))]
            (binary-response opened 200 headers)))))))

(defn- route-translate
  [component request method]
  (if-not (= method :post)
    (method-not-allowed "POST")
    (let [body (body-bytes request (* 1024 1024))
          payload (json/decode (String. ^bytes body StandardCharsets/UTF_8))]
      (when-not (map? payload)
        (error/fail! :SPEECH_REQUEST_INVALID
                     "translation request must be a JSON object"
                     {}))
      (let [result (translate-command! component payload)]
        (edn-response (if (:ok? result) 200 (error-status (:error result)))
                      result)))))

(defn- route
  [component request]
  (let [method (:request-method request)
        parts (segments (:uri request))
        parts (if (and (seq parts) (= "speech" (first parts)))
                (rest parts)
                parts)]
    (cond
      (and (empty? parts) (= method :get))
      (edn-response 200 {:ok? true :snapshot (snapshot component)})

      (and (= ["snapshot"] (vec parts)) (= method :get))
      (edn-response 200 {:ok? true :snapshot (snapshot component)})

      (= "recordings" (first parts))
      (route-recordings component request method (rest parts))

      (= "audio" (first parts))
      (route-audio component request method (rest parts))

      (= ["tts"] (vec parts))
      (route-tts component request method)

      (= ["translate"] (vec parts))
      (route-translate component request method)

      :else
      (edn-response 404 {:ok? false
                         :error {:code :SPEECH_ROUTE_NOT_FOUND
                                 :message "speech route does not exist"
                                 :details {:uri (:uri request)}}}))))

(defn handler
  "Ring handler for `/speech/*`.  Returns nil for every other path so the
  application can fall through to the gateway."
  [component]
  (fn [request]
    (when (speech-path? (:uri request))
      (try
        (route component request)
        (catch clojure.lang.ExceptionInfo t
          (if (error/expected? t)
            (let [error-map (error/error-map t)]
              (edn-response (error-status error-map)
                            {:ok? false :error error-map}))
            (edn-response 500
                          {:ok? false
                           :error {:code :SPEECH_INTERNAL_ERROR
                                   :message "speech request failed"}})))
        (catch Throwable t
          (log/warn t "speech request failed")
          (edn-response 500
                        {:ok? false
                         :error {:code :SPEECH_INTERNAL_ERROR
                                 :message "speech request failed"}}))))))
