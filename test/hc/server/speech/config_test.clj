(ns hc.server.speech.config-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.speech :as speech]
            [hc.server.speech.config :as config]
            [hc.server.speech.store :as store]
            [hc.server.speech.support :as support]))

(defn- service
  ([id] (service id {}))
  ([id opts]
   (merge {:id id
           :base-url "http://127.0.0.1:8080"
           :model "test-model"}
          opts)))

(defn- error-code
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo t
      (:error/code (ex-data t)))))

(deftest defaults-are-empty-and-explicit
  (let [normalized (config/normalize {})]
    (is (= 0 (:revision normalized)))
    (is (empty? (get-in normalized [:stt :services])))
    (is (empty? (get-in normalized [:tts :services])))
    (is (= 300000 (get-in normalized [:stt :max-duration-ms])))
    (is (nil? (get-in normalized [:stt :default-service-id])))
    (is (nil? (get-in normalized [:tts :default-service-id])))))

(deftest loopback-device-and-remote-cloud
  (let [normalized (config/normalize
                    {:stt {:services [(service "local")]}
                     :tts {:services [(service "remote"
                                               {:base-url "https://speech.example.com"
                                                :voice "alloy"})]}})]
    (is (= :cloud (get-in normalized [:stt :services 0 :processing])))
    (is (= :inferred (get-in normalized [:stt :services 0
                                         :processing-source])))
    (is (= :cloud (get-in normalized [:tts :services 0 :processing])))
    (testing "device processing requires an explicit loopback declaration"
      (let [device (config/normalize
                    {:stt {:services [(assoc (service "local")
                                             :processing :device)]}})]
        (is (= :device (get-in device [:stt :services 0 :processing])))
        (is (= :explicit (get-in device [:stt :services 0
                                         :processing-source])))))
    (testing "a remote endpoint cannot claim device processing"
      (is (= :SPEECH_CONFIG_INVALID
             (error-code
              (fn []
                (config/normalize
                 {:stt {:services [(service "bad"
                                            {:base-url "https://speech.example.com"
                                             :processing :device})]}}))))))
    (testing "a client-side system voice is not a server provider"
      (is (= :SPEECH_CONFIG_INVALID
             (error-code
              (fn []
                (config/normalize
                 {:stt {:services [(service "system"
                                            {:processing :system})]}}))))))))

(deftest configuration-rejects-ambiguous-or-unsafe-services
  (doseq [bad [{:id "no-model" :base-url "http://127.0.0.1:9"}
               {:id "bad-scheme" :base-url "ftp://127.0.0.1:9" :model "m"}
               {:id "credentials"
                :base-url "http://user:pass@127.0.0.1:9" :model "m"}
               {:id "query"
                :base-url "http://127.0.0.1:9?key=1" :model "m"}
               {:id "fake" :base-url "http://127.0.0.1:9" :model "m"
                :kind :fake}]]
    (is (= :SPEECH_CONFIG_INVALID
           (error-code (fn [] (config/normalize {:stt {:services [bad]}}))))
        (pr-str bad)))
  (testing "a single input is limited to five minutes"
    (is (= :SPEECH_CONFIG_INVALID
           (error-code (fn []
                         (config/normalize
                          {:stt {:max-duration-ms 300001}})))))))

(deftest service-selection-never-falls-back
  (let [config (config/normalize
                {:stt {:services [(service "one")
                                  (service "two")]}})]
    (is (= :SPEECH_SERVICE_REQUIRED
           (error-code (fn [] (config/select-service config :stt nil)))))
    (is (= "one" (:id (config/select-service config :stt "one"))))
    (is (= :SPEECH_SERVICE_NOT_FOUND
           (error-code (fn [] (config/select-service config :stt "missing"))))))
  (let [config (config/normalize
                {:stt {:services [(service "one" {:enabled? false})]
                       :default-service-id "one"}})]
    (is (= :SPEECH_SERVICE_DISABLED
           (error-code (fn [] (config/select-service config :stt nil)))))))

(deftest inline-keys-are-not-persisted-or-snapshotted
  (let [dir (support/temp-dir "hc-speech-config-")
        component (speech/start! {:data-dir dir :config {}})
        saved (speech/handle! component
                              {:kind :speech/save-service
                               :area :stt
                               :service (service "local" {:api-key "top-secret"})})
        file (slurp (str (:config-path component)))]
    (is (true? (:ok? saved)))
    (is (not (str/includes? file "top-secret")))
    (is (not (str/includes? file ":api-key ")))
    (let [snapshot (:receipt (speech/handle! component
                                             {:kind :speech/snapshot}))]
      (is (not (str/includes? (pr-str snapshot) "top-secret")))
      (is (true? (get-in snapshot [:snapshot :speech :stt :services 0
                                   :has-key?])))
      (is (= :inline (get-in snapshot [:snapshot :speech :stt :services 0
                                       :key-source]))))
    (testing "the persisted config is readable EDN"
      (is (= 1 (:revision (edn/read-string file)))))))

(deftest revision-conflicts-are-reported
  (let [dir (support/temp-dir "hc-speech-config-")
        component (speech/start! {:data-dir dir :config {}})
        first-result (speech/handle! component
                                     {:kind :speech/save-service
                                      :area :stt
                                      :service (service "one")
                                      :expected-revision 0})
        conflict (speech/handle! component
                                 {:kind :speech/save-service
                                  :area :stt
                                  :service (service "two")
                                  :expected-revision 0})]
    (is (true? (:ok? first-result)))
    (is (false? (:ok? conflict)))
    (is (= :SPEECH_CONFIG_CONFLICT (get-in conflict [:error :code])))
    (is (= 1 (get-in conflict [:error :details :revision])))))

(deftest persisted-config-survives-restart-without-inline-key
  (let [dir (support/temp-dir "hc-speech-restart-")
        first-component (speech/start! {:data-dir dir :config {}})
        saved (speech/handle! first-component
                              {:kind :speech/save-service
                               :area :stt
                               :service (service "local" {:api-key "temporary"})})
        second-component (speech/start! {:data-dir dir :config {}})
        snapshot (:receipt (speech/handle! second-component
                                           {:kind :speech/snapshot}))]
    (is (true? (:ok? saved)))
    (is (= 1 (count (get-in snapshot
                            [:snapshot :speech :stt :services]))))
    (is (= "local" (get-in snapshot
                           [:snapshot :speech :stt :default-service-id])))
    (is (false? (get-in snapshot
                        [:snapshot :speech :stt :services 0 :has-key?])))))

(deftest deleting-a-service-does-not-delete-recordings
  (let [dir (support/temp-dir "hc-speech-delete-")
        component (speech/start! {:data-dir dir :config {}})
        audio (support/wav-bytes 16)
        created (store/create-recording!
                 (:root component)
                 {:bytes audio
                  :content-type "audio/wav"
                  :duration-ms 1000
                  :max-bytes 100000})
        recording-id (get-in created [:recording :recording-id])]
    (speech/handle! component
                    {:kind :speech/save-service
                     :area :stt
                     :service (service "local")})
    (let [deleted (speech/handle! component
                                  {:kind :speech/delete-service
                                   :area :stt
                                   :service-id "local"})
          listing (:receipt (speech/handle! component
                                            {:kind :speech/recordings}))]
      (is (true? (:ok? deleted)))
      (is (true? (get-in deleted [:receipt :recordings-preserved?])))
      (is (= recording-id
             (get-in listing [:recordings 0 :recording-id]))))))

(deftest ordering-and-defaults-are-explicit
  (let [dir (support/temp-dir "hc-speech-order-")
        component (speech/start! {:data-dir dir :config {}})]
    (speech/handle! component
                    {:kind :speech/save-service
                     :area :stt
                     :service (service "one")})
    (speech/handle! component
                    {:kind :speech/save-service
                     :area :stt
                     :service (service "two")})
    (let [reordered (speech/handle! component
                                    {:kind :speech/reorder-services
                                     :area :stt
                                     :service-ids ["two" "one"]})
          defaulted (speech/handle! component
                                    {:kind :speech/set-default
                                     :area :stt
                                     :service-id "two"})
          snapshot (:receipt (speech/handle! component
                                             {:kind :speech/snapshot}))]
      (is (true? (:ok? reordered)))
      (is (true? (:ok? defaulted)))
      (is (= ["two" "one"]
             (mapv :id (get-in snapshot
                               [:snapshot :speech :stt :services]))))
      (is (= "two" (get-in snapshot
                           [:snapshot :speech :stt :default-service-id])))
      (is (= :SPEECH_SERVICE_ORDER_INVALID
             (get-in (speech/handle! component
                                     {:kind :speech/reorder-services
                                      :area :stt
                                      :service-ids ["two" "two"]})
                     [:error :code]))))))

(deftest manually-written-inline-key-is-scrubbed-on-start
  (let [dir (support/temp-dir "hc-speech-scrub-")
        path (str dir "/speech/config.edn")
        _ (doto (java.io.File. (str dir "/speech")) (.mkdirs))
        written {:revision 0
                 :stt {:services [{:id "local"
                                   :label "Local"
                                   :kind :openai-compatible
                                   :base-url "http://127.0.0.1:8080"
                                   :model "whisper-test"
                                   :api-key "disk-secret"
                                   :enabled? true
                                   :order 0
                                   :connect-timeout-ms 5000
                                   :request-timeout-ms 60000
                                   :response-format "json"
                                   :languages []}]
                       :default-service-id "local"}}
        _ (spit path (pr-str written))
        component (speech/start! {:data-dir dir})
        file (slurp path)
        snapshot (:receipt (speech/handle! component
                                           {:kind :speech/snapshot}))]
    (is (not (str/includes? file "disk-secret")))
    (is (not (str/includes? file ":api-key ")))
    (is (true? (get-in snapshot [:snapshot :speech :stt :services 0
                                 :has-key?])))
    (is (= :inline (get-in snapshot [:snapshot :speech :stt :services 0
                                     :key-source])))))
