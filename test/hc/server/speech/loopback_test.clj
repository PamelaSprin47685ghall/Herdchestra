(ns hc.server.speech.loopback-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hc.server.speech :as speech]
            [hc.server.speech.json :as json]
            [hc.server.speech.support :as support])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)
           (java.util Base64)))

(defn- base64
  [^bytes value]
  (.encodeToString (Base64/getEncoder) value))

(defonce active-providers
  (atom []))

(defn- provider!
  [state]
  (let [provider (support/start-provider!
                  (fn [{:keys [path headers body]} exchange]
                    (case path
                      "/v1/audio/transcriptions"
                      (let [parts (support/parse-multipart
                                   (get headers "content-type") body)]
                        (swap! state assoc :last-stt parts)
                        (cond
                          (not= "Bearer secret" (get headers "authorization"))
                          (support/write-json! exchange 401
                                               {:error {:message "invalid API key"}})

                          (= :fail (:mode @state))
                          (support/write-json! exchange 503
                                               {:error {:message "provider is busy"}})

                          (= :empty (:mode @state))
                          (support/write-json! exchange 200 {:text ""})

                          :else
                          (support/write-json!
                           exchange 200
                           {:text "hello from loopback"
                            :language "en"
                            :usage {:type "duration" :seconds 1.25}})))

                      "/v1/audio/speech"
                      (let [payload (json/decode
                                     (String. ^bytes body StandardCharsets/UTF_8))]
                        (swap! state assoc :last-tts payload)
                        (case (:mode @state)
                          :fail
                          (support/write-json! exchange 503
                                               {:error {:message "tts is busy"}})

                          :partial
                          (let [partial (support/wav-bytes 16)]
                            (.sendResponseHeaders exchange 200 1000)
                            (with-open [out (.getResponseBody exchange)]
                              (.write out partial)
                              (.flush out))
                            (.close exchange)
                            nil)

                          :stall
                          (let [partial (support/wav-bytes 16)
                                out (.getResponseBody exchange)]
                            (.sendResponseHeaders exchange 200 0)
                            (.write out partial)
                            (.flush out)
                            (Thread/sleep 1000)
                            ;; Leave the exchange open: the adapter must close
                            ;; the response stream after its read timeout.
                            nil)

                          (support/write-bytes! exchange 200
                                                (support/wav-bytes 128)
                                                "audio/wav"
                                                {"x-usage-seconds" "0.75"})))

                      "/v1/chat/completions"
                      (let [payload (json/decode
                                     (String. ^bytes body StandardCharsets/UTF_8))]
                        (swap! state assoc :last-translation payload)
                        (support/write-json!
                         exchange 200
                         {:choices [{:message {:role "assistant"
                                               :content "这是回环翻译。"}}]
                          :usage {:prompt_tokens 4
                                  :completion_tokens 3
                                  :total_tokens 7}}))

                      (support/write-json! exchange 404
                                           {:error "not found"}))))]
    (swap! active-providers conj provider)
    provider))

(defn- stop-active-providers!
  []
  (doseq [provider @active-providers]
    (try (support/stop-provider! provider) (catch Throwable _ nil)))
  (reset! active-providers []))

(use-fixtures :each
  (fn [test-fn]
    (try
      (test-fn)
      (finally
        (stop-active-providers!)))))

(defn- component
  [stt-url tts-url & [extra]]
  (let [dir (support/temp-dir "hc-speech-loopback-")
        usage-port (:usage-port extra)
        test-audio (:test-audio extra)
        extra (dissoc extra :usage-port :test-audio)]
    (speech/start!
     {:data-dir dir
      :usage-port usage-port
      :test-audio test-audio
      :config (merge
               {:stt {:services [{:id "bad"
                                  :base-url stt-url
                                  :model "whisper-test"
                                  :api-key "secret"
                                  :connect-timeout-ms 1000
                                  :request-timeout-ms 3000}]
                      :default-service-id "bad"}
                :tts {:services [{:id "tts"
                                  :base-url tts-url
                                  :model "tts-test"
                                  :voice "alloy"
                                  :format "wav"
                                  :connect-timeout-ms 1000
                                  :request-timeout-ms 3000}]
                      :default-service-id "tts"}}
               extra)})))

(defn- transcribe!
  [component audio & [extra]]
  (speech/handle!
   component
   (merge {:kind :speech/transcribe
           :audio-base64 (base64 audio)
           :content-type "audio/wav"
           :duration-ms 1200
           :language "en"}
          extra)))

(deftest real-loopback-transcription-and-draft
  (let [state (atom {:mode :ok})
        provider (provider! state)
        audio (support/wav-bytes 64)
        usage-records (atom [])
        component (component (:url provider) (:url provider)
                             {:usage-port #(swap! usage-records conj %)})
        result (transcribe! component audio)]
    (try
      (is (true? (:ok? result)) (pr-str result))
      (is (= "hello from loopback" (get-in result [:receipt :transcript])))
      (is (= :complete (get-in result [:receipt :status])))
      (is (= :per-second (get-in result [:receipt :usage :unit])))
      (is (= 1.25 (get-in result [:receipt :usage :quantity])))
      (is (= :provider-reported
             (get-in result [:receipt :usage :quantity-source])))
      (is (= :per-second (:unit (first @usage-records))))
      (is (= 1.25 (:quantity (first @usage-records))))
      (is (= :provider-reported (:quantity-source (first @usage-records))))
      (testing "the transcript is a draft instruction and is never sent"
        (is (= :insert-into-draft (get-in result [:receipt :draft :action])))
        (is (= "hello from loopback" (get-in result [:receipt :draft :text])))
        (is (false? (get-in result [:receipt :draft :auto-send?])))
        (is (false? (get-in result [:receipt :auto-send?]))))
      (testing "the original audio was stored before the provider call"
        (is (true? (get-in result [:receipt :audio :preserved?])))
        (let [parts (:last-stt @state)]
          (is (= "whisper-test" (get parts "model")))
          (is (= "en" (get parts "language")))
          (is (= (seq audio) (seq ^bytes (get parts "file"))))
          (is (= "Bearer secret" (get-in @(:requests provider) [0 :headers
                                                               "authorization"])))))
      (testing "recording metadata keeps the transcript and the audio"
        (let [id (get-in result [:receipt :recording-id])
              recording (:receipt (speech/handle!
                                   component
                                   {:kind :speech/recording
                                    :recording-id id}))]
          (is (= :transcribed (get-in recording [:recording :status])))
          (is (= "hello from loopback"
                 (get-in recording [:recording :transcript])))
          (is (true? (get-in recording [:recording :audio-preserved?])))))
      (finally
        (support/stop-provider! provider)))))

(deftest failed-transcription-preserves-audio-and-retries
  (let [bad-state (atom {:mode :fail})
        good-state (atom {:mode :ok})
        bad (provider! bad-state)
        good (provider! good-state)
        audio (support/wav-bytes 64)
        component (component (:url bad) (:url good)
                             {:stt {:services
                                    [{:id "bad" :base-url (:url bad)
                                      :model "whisper-test" :api-key "secret"
                                      :connect-timeout-ms 1000
                                      :request-timeout-ms 3000}
                                     {:id "good" :base-url (:url good)
                                      :model "whisper-test" :api-key "secret"
                                      :connect-timeout-ms 1000
                                      :request-timeout-ms 3000}]
                                    :default-service-id "bad"}})
        failed (transcribe! component audio)
        recording-id (get-in failed [:error :details :recording-id])]
    (try
      (is (false? (:ok? failed)))
      (is (= :SPEECH_SERVICE_ERROR (get-in failed [:error :code])))
      (is (true? (get-in failed [:error :details :audio-preserved?])))
      (is (string? recording-id))
      (is (= 1 (count @(:requests bad))))
      (is (empty? @(:requests good)))

      (testing "retry uses the stored recording, not a new upload"
        (let [retried (speech/handle!
                       component
                       {:kind :speech/retry-transcription
                        :recording-id recording-id
                        :service-id "good"
                        :language "en"})]
          (is (true? (:ok? retried)) (pr-str retried))
          (is (= "hello from loopback"
                 (get-in retried [:receipt :transcript])))
          (is (= 1 (count @(:requests good))))
          (is (= 1 (count @(:requests bad))))
          (is (= (seq audio)
                 (seq ^bytes (get (:last-stt @good-state) "file"))))))
      (finally
        (support/stop-provider! bad)
        (support/stop-provider! good)))))

(deftest provider-auth-failure-still-preserves-audio
  (let [state (atom {:mode :ok})
        provider (provider! state)
        dir (support/temp-dir "hc-speech-auth-")
        component (speech/start!
                   {:data-dir dir
                    :config {:stt {:services
                                   [{:id "bad-key"
                                     :base-url (:url provider)
                                     :model "whisper-test"
                                     :api-key "wrong"
                                     :connect-timeout-ms 1000
                                     :request-timeout-ms 3000}]
                              :default-service-id "bad-key"}}})
        audio (support/wav-bytes 32)
        result (transcribe! component audio)]
    (try
      (is (= :SPEECH_AUTH_FAILED (get-in result [:error :code])))
      (is (true? (get-in result [:error :details :audio-preserved?])))
      (let [id (get-in result [:error :details :recording-id])
            listing (:receipt (speech/handle! component
                                              {:kind :speech/recordings}))]
        (is (= id (get-in listing [:recordings 0 :recording-id])))
        (is (= :failed (get-in listing [:recordings 0 :status]))))
      (finally
        (support/stop-provider! provider)))))

(deftest five-minute-limit-refuses-before-the-provider
  (let [state (atom {:mode :ok})
        provider (provider! state)
        component (component (:url provider) (:url provider))
        result (transcribe! component (support/wav-bytes 16)
                            {:duration-ms 300001})]
    (try
      (is (= :SPEECH_RECORDING_TOO_LONG (get-in result [:error :code])))
      (is (true? (get-in result [:error :details :audio-preserved?])))
      (is (empty? @(:requests provider)))
      (finally
        (support/stop-provider! provider)))))

(deftest empty-transcript-creates-no-draft
  (let [state (atom {:mode :empty})
        provider (provider! state)
        component (component (:url provider) (:url provider))
        result (transcribe! component (support/wav-bytes 16))]
    (try
      (is (true? (:ok? result)))
      (is (= :empty (get-in result [:receipt :status])))
      (is (nil? (get-in result [:receipt :draft])))
      (is (false? (get-in result [:receipt :auto-send?])))
      (finally
        (support/stop-provider! provider)))))

(deftest real-loopback-synthesis-and-binary-routes
  (let [state (atom {:mode :ok})
        provider (provider! state)
        component (component (:url provider) (:url provider))
        text (str "Hello.\n\n```clojure\n(+ 1 2)\n```\n\n"
                  "Read https://example.com/private now.")
        result (speech/handle! component
                               {:kind :speech/synthesize
                                :text text
                                :message-id "m1"
                                :version-id "m1-v1"})]
    (try
      (is (true? (:ok? result)) (pr-str result))
      (let [receipt (:receipt result)
            audio-id (:audio-id receipt)]
        (is (= :complete (:status receipt)))
        (is (false? (:partial? receipt)))
        (is (string? audio-id))
        (is (str/includes? (:spoken-text receipt) "Hello."))
        (is (str/includes? (:spoken-text receipt) "There is a code block."))
        (is (str/includes? (:spoken-text receipt) "There is a link."))
        (is (not (str/includes? (:spoken-text receipt) "(+ 1 2)")))
        (is (not (str/includes? (:spoken-text receipt) "https://")))
        (is (= "tts-test" (get-in @state [:last-tts :model])))
        (is (= "alloy" (get-in @state [:last-tts :voice])))
        (is (= "wav" (get-in @state [:last-tts :response_format])))
        (is (= (:spoken-text receipt) (get-in @state [:last-tts :input])))
        (is (= :per-second (get-in receipt [:usage :unit])))
        (is (= 0.75 (get-in receipt [:usage :quantity])))

        (testing "the stored audio is served through the binary route"
          (let [request {:request-method :get
                         :uri (str "/speech/audio/" audio-id)
                         :headers {}}
                response ((speech/handler component) request)
                bytes (support/read-body (:body response))]
            (is (= 200 (:status response)))
            (is (= "audio/wav" (get-in response [:headers "content-type"])))
            (is (= (alength ^bytes (support/wav-bytes 128))
                   (alength ^bytes bytes)))
            (is (= (seq (support/wav-bytes 128)) (seq bytes))))))
      (finally
        (support/stop-provider! provider)))))

(deftest partial-synthesis-is-stored-and-playable
  (let [state (atom {:mode :partial})
        provider (provider! state)
        component (component (:url provider) (:url provider))
        result (speech/handle! component
                               {:kind :speech/synthesize :text "Hello partial."})]
    (try
      (is (true? (:ok? result)) (pr-str result))
      (is (= :partial (get-in result [:receipt :status])))
      (is (true? (get-in result [:receipt :partial?])))
      (is (= :SPEECH_SYNTHESIS_PARTIAL
             (get-in result [:receipt :error :code])))
      (let [audio-id (get-in result [:receipt :audio-id])
            request {:request-method :get
                     :uri (str "/speech/audio/" audio-id)
                     :headers {}}
            response ((speech/handler component) request)
            bytes (support/read-body (:body response))]
        (is (= 200 (:status response)))
        (is (= "true" (get-in response [:headers "x-hc-partial"])))
        (is (pos? (alength ^bytes bytes))))
      (let [request-body (.getBytes (json/encode {:text "Hello partial."})
                                    StandardCharsets/UTF_8)
            response ((speech/handler component)
                      {:request-method :post
                       :uri "/speech/tts"
                       :headers {"content-type" "application/json"}
                       :body (ByteArrayInputStream. request-body)})
            bytes (support/read-body (:body response))]
        (is (= 200 (:status response)))
        (is (= "true" (get-in response [:headers "x-hc-partial"])))
        (is (pos? (alength ^bytes bytes))))
      (finally
        (support/stop-provider! provider)))))

(deftest stalled-tts-response-is-bounded-partial
  (let [state (atom {:mode :stall})
        provider (provider! state)
        component (component
                   (:url provider) (:url provider)
                   {:tts {:services [{:id "tts"
                                      :base-url (:url provider)
                                      :model "tts-test"
                                      :voice "alloy"
                                      :connect-timeout-ms 300
                                      :request-timeout-ms 300}]
                          :default-service-id "tts"}})
        result (speech/handle! component
                               {:kind :speech/synthesize
                                :text "Hello stalled."})]
    (try
      (is (true? (:ok? result)) (pr-str result))
      (is (= :partial (get-in result [:receipt :status])))
      (is (true? (get-in result [:receipt :timed-out?])))
      (is (str/includes? (get-in result [:receipt :error :message])
                         "timed out"))
      (is (pos? (get-in result [:receipt :bytes])))
      (finally
        (support/stop-provider! provider)))))

(deftest binary-recording-route-round-trip
  (let [state (atom {:mode :ok})
        provider (provider! state)
        component (component (:url provider) (:url provider))
        audio (support/wav-bytes 64)
        upload ((speech/handler component)
                {:request-method :post
                 :uri "/speech/recordings"
                 :headers {"content-type" "audio/wav"
                           "x-hc-duration-ms" "1200"
                           "x-hc-language" "en"
                           "x-hc-session-id" "s1"}
                 :body (ByteArrayInputStream. audio)})
        result (edn/read-string (:body upload))
        recording-id (get-in result [:receipt :recording-id])]
    (try
      (is (= 200 (:status upload)))
      (is (true? (:ok? result)))
      (is (= "hello from loopback" (get-in result [:receipt :transcript])))
      (let [download ((speech/handler component)
                      {:request-method :get
                       :uri (str "/speech/recordings/" recording-id)
                       :headers {}})
            bytes (support/read-body (:body download))]
        (is (= 200 (:status download)))
        (is (= (seq audio) (seq bytes))))
      (finally
        (support/stop-provider! provider)))))

(deftest service-test-commands-use-real-requests
  (let [state (atom {:mode :ok})
        provider (provider! state)
        base-component (component (:url provider) (:url provider))
        audio-component (component (:url provider) (:url provider)
                                   {:test-audio (support/wav-bytes 16)})
        no-audio (speech/handle! base-component
                                 {:kind :speech/test-service
                                  :area :stt
                                  :service-id "bad"})
        with-audio (speech/handle!
                    audio-component
                    {:kind :speech/test-service
                     :area :stt
                     :service-id "bad"})
        tts-test (speech/handle! base-component
                                 {:kind :speech/test-service
                                  :area :tts
                                  :service-id "tts"})]
    (try
      (is (= :not-run (get-in no-audio [:receipt :layers :test-audio
                                        :status])))
      (is (= "hello from loopback"
             (get-in with-audio [:receipt :layers :transcript :text])))
      (is (= :ok (get-in with-audio [:receipt :layers :auth-ok :status])))
      (is (= :ok (get-in tts-test [:receipt :layers :audio :status])))
      (is (string? (get-in tts-test [:receipt :layers :audio :audio-id])))
      (is (= "Herdchestra speech test." (get-in @state [:last-tts :input])))
      (finally
        (support/stop-provider! provider)))))

(deftest tts-http-route-returns-audio
  (let [state (atom {:mode :ok})
        provider (provider! state)
        component (component (:url provider) (:url provider))
        request-body (.getBytes (json/encode {:text "Hello from HTTP."
                                              :voice "alloy"})
                                StandardCharsets/UTF_8)
        response ((speech/handler component)
                  {:request-method :post
                   :uri "/speech/tts"
                   :headers {"content-type" "application/json"}
                   :body (ByteArrayInputStream. request-body)})
        bytes (support/read-body (:body response))]
    (try
      (is (= 200 (:status response)))
      (is (= "audio/wav" (get-in response [:headers "content-type"])))
      (is (string? (get-in response [:headers "x-hc-audio-id"])))
      (is (= (seq (support/wav-bytes 128)) (seq bytes)))
      (is (= "Hello from HTTP." (get-in @state [:last-tts :input])))
      (finally
        (support/stop-provider! provider)))))

(deftest real-loopback-translation
  (let [state (atom {:mode :ok})
        provider (provider! state)
        component (component
                   (:url provider) (:url provider)
                   {:translation
                    {:services [{:id "translator"
                                 :base-url (:url provider)
                                 :model "translate-test"}]
                     :default-service-id "translator"}})
        result (speech/handle! component
                               {:kind :speech/translate
                                :text "Hello"
                                :source-language "auto"
                                :target-language "zh-CN"
                                :message-id "m1"
                                :version-id "m1-v1"})]
    (try
      (is (true? (:ok? result)) (pr-str result))
      (is (= "这是回环翻译。"
             (get-in result [:receipt :translated-text])))
      (is (= "Hello" (get-in result [:receipt :source-text])))
      (is (= "zh-CN" (get-in result [:receipt :target-language])))
      (is (true? (get-in result [:receipt :original-preserved?])))
      (is (= 7 (get-in result [:receipt :token-usage :total-tokens])))
      (is (nil? (get-in result [:receipt :usage])))
      (is (= "translate-test" (get-in @state [:last-translation :model])))
      (let [body (.getBytes (json/encode {:text "Hello"
                                          :target-language "zh-CN"})
                            StandardCharsets/UTF_8)
            response ((speech/handler component)
                      {:request-method :post
                       :uri "/speech/translate"
                       :headers {"content-type" "application/json"}
                       :body (ByteArrayInputStream. body)})
            parsed (edn/read-string (:body response))]
        (is (= 200 (:status response)))
        (is (= "这是回环翻译。" (get-in parsed [:receipt :translated-text]))))
      (finally
        (support/stop-provider! provider)))))

(deftest handler-does-not-claim-other-paths
  (let [component (component "http://127.0.0.1:9" "http://127.0.0.1:9")]
    (is (nil? ((speech/handler component)
               {:request-method :get :uri "/healthz" :headers {}})))))

(deftest translation-test-command-uses-the-real-provider
  (let [state (atom {:mode :ok})
        provider (provider! state)
        component (component (:url provider) (:url provider)
                             {:translation
                              {:services [{:id "translator"
                                           :base-url (:url provider)
                                           :model "translate-test"}]
                               :default-service-id "translator"}})
        result (speech/handle! component
                               {:kind :speech/test-service
                                :area :translation
                                :service-id "translator"})]
    (try
      (is (true? (:ok? result)) (pr-str result))
      (is (= :ok (get-in result [:receipt :layers :translated :status])))
      (is (= "这是回环翻译。"
             (get-in result [:receipt :layers :translated :text])))
      (finally
        (support/stop-provider! provider)))))

(deftest tts-audio-is-scoped-to-its-session
  (let [state (atom {:mode :ok})
        provider (provider! state)
        component (component (:url provider) (:url provider))
        synthesized (speech/handle! component
                                    {:kind :speech/synthesize
                                     :text "Hello"
                                     :session-id "s1"
                                     :voice "alloy"})
        audio-id (get-in synthesized [:receipt :audio-id])
        own (speech/handle! component {:kind :speech/audios
                                       :session-id "s1"})
        other (speech/handle! component {:kind :speech/audios
                                         :session-id "s2"})
        denied (speech/handle! component {:kind :speech/audio
                                          :audio-id audio-id
                                          :session-id "s2"})]
    (try
      (is (true? (:ok? synthesized)))
      (is (= 1 (count (get-in own [:receipt :audio]))))
      (is (empty? (get-in other [:receipt :audio])))
      (is (false? (:ok? denied)))
      (finally
        (support/stop-provider! provider)))))

(deftest unverifiable-cloud-audio-is-refused-before-the-provider
  (let [dir (support/temp-dir "hc-speech-unverifiable-")
        component (speech/start!
                   {:data-dir dir
                    :config {:stt
                             {:services [{:id "cloud"
                                          :base-url "https://speech.example.com"
                                          :model "whisper-test"}]
                              :default-service-id "cloud"}}})
        result (speech/handle! component
                               {:kind :speech/transcribe
                                :audio-base64 (base64 (.getBytes "not a wav"
                                                                 "UTF-8"))
                                :content-type "audio/webm"
                                :duration-ms 1000})]
    (is (false? (:ok? result)))
    (is (= :SPEECH_DURATION_UNVERIFIABLE
           (get-in result [:error :code])))
    (is (string? (get-in result [:error :details :recording-id])))
    (is (true? (get-in result [:error :details :audio-preserved?])))))
