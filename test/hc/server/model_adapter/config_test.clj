(ns hc.server.model-adapter.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.model-adapter.config :as config]))

(def app-shaped-config
  {:connections
   [{:id "a"
     :connection-id "a"
     :kind :openai-compatible
     :base-url "http://127.0.0.1:11434/v1"
     :name "Local"
     :enabled? true
     :models [{:model-id "qwen"
               :display-name "Qwen"
               :capabilities {:tools true :vision :unknown}
               :pricing {:status :unknown}
               :context-window :unknown
               :max-output :unknown}]}
    {:id "b"
     :kind :openai-compatible
     :base-url "http://127.0.0.1:11435/v1"
     :enabled? false
     :models [{:model-id "other"
               :capabilities {:tools true}}]}]
   :roles {:main {:connection-id "a"
                  :model-id "qwen"
                  :mode :explicit
                  :confirmed? true}
           :task {:connection-id "a"
                  :model-id "qwen"}}})

(deftest accepts-application-shaped-configuration
  (let [normalized (config/normalize-config app-shaped-config)]
    (testing "model display metadata does not become provider capabilities"
      (is (= #{:tools}
             (get-in normalized
                     [:connections "a" :models "qwen" :capabilities])))
      (is (nil? (get-in normalized
                        [:connections "a" :models "qwen" :context-window])))
      (is (nil? (get-in normalized
                        [:connections "a" :models "qwen" :max-output-tokens]))))
    (testing "role aliases and display fields are accepted"
      (is (= {:connection "a" :model "qwen"}
             (get-in normalized [:roles :main])))
      (is (nil? (:default-role normalized))
          "two roles require an explicit role on each request"))
    (testing "disabled connections remain visible but are not built"
      (is (false? (get-in normalized [:connections "b" :enabled?]))))))

(deftest rejects-a-role-that-names-a-disabled-target
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"disabled"
       (config/normalize-config
        (assoc app-shaped-config :roles
               {:main {:connection-id "b" :model-id "other"}})))))

(deftest accepts-session-credential-metadata
  (let [connection (-> (first (:connections app-shaped-config))
                       (assoc :credentials {"c1" {:alias "default"
                                                  :secret-ref "vault://c1"}}
                              :credential-order ["c1"]
                              :key-strategy :priority
                              :updated-at 123
                              :custom-base-url "http://127.0.0.1:11434/v1"
                              :effective-base-url "http://127.0.0.1:11434/v1"
                              :official? false
                              :display-host "127.0.0.1"
                              :list-status :ready
                              :last-test {:status :ok}
                              :rotation-policy :priority
                              :auth-status :ok))
        normalized (config/normalize-config
                    (assoc app-shaped-config :connections [connection]))]
    (is (true? (get-in normalized
                       [:connections "a" :has-multi-credentials?])))
    (is (= {"c1" {:alias "default" :secret-ref "vault://c1"}}
           (get-in normalized [:connections "a" :credentials])))
    (is (= :priority (get-in normalized [:connections "a" :key-strategy])))))

(deftest accepts-capability-provenance-metadata
  (let [model (-> (first (get-in app-shaped-config [:connections 0 :models]))
                  (assoc :capability-sources {:tools :user}
                         :capability-baseline {:tools :unknown}))
        normalized (config/normalize-config
                    (assoc-in app-shaped-config [:connections 0 :models 0]
                              model))]
    (is (= #{:tools}
           (get-in normalized
                   [:connections "a" :models "qwen" :capabilities]))
        "provenance metadata is accepted but never becomes a capability")
    (is (nil? (get-in normalized
                      [:connections "a" :models "qwen"
                       :capability-sources])))))

(deftest accepts-session-base-url-aliases
  (let [connection (-> (first (:connections app-shaped-config))
                       (dissoc :base-url)
                       (assoc :effective-base-url "http://127.0.0.1:11434/v1"))
        normalized (config/normalize-config
                    (assoc app-shaped-config :connections [connection]))]
    (is (= "http://127.0.0.1:11434/v1"
           (get-in normalized [:connections "a" :base-url])))))

(deftest accepts-runtime-injection-keys
  (let [loader (fn [_] {:base64 "AA=="})
        resolver (fn [_] {:ok? true :secret "rotated"})
        normalized (config/normalize-config
                    (assoc app-shaped-config
                           :attachment-loader loader
                           :credential-resolver resolver))]
    (is (identical? loader (:attachment-loader normalized)))
    (is (identical? resolver (:credential-resolver normalized))))
  (let [loader (fn [_] {:bytes (byte-array [1 2 3])})
        normalized (config/normalize-config
                    (assoc app-shaped-config :open-blob loader))]
    (is (identical? loader (:attachment-loader normalized))
        ":open-blob is an alias for :attachment-loader")))

(deftest rejects-known-provider-connection-level-injected-models
  (doseq [field [:chat-model :streaming-chat-model]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"connection-level injected models"
         (config/normalize-config
          (assoc app-shaped-config :connections
                 [(assoc (first (:connections app-shaped-config))
                         field (Object.))]))))))

(defn- custom-config
  [model]
  {:connections [{:id "c"
                  :kind :custom
                  :models [(merge {:id "m"
                                   :capabilities #{:tools}
                                   :chat-model (Object.)}
                                  model)]}]
   :roles {:main {:connection "c" :model "m"}}})

(deftest custom-models-must-declare-no-implicit-retries
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"implicit-retries 0"
       (config/normalize-config (custom-config {}))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"implicit-retries 0"
       (config/normalize-config (custom-config {:implicit-retries 2}))))
  (let [normalized (config/normalize-config
                    (custom-config {:implicit-retries 0}))]
    (is (= 0 (get-in normalized [:connections "c" :models "m"
                                 :implicit-retries])))))

(deftest custom-models-reject-ignored-max-output-alias
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"owns its own parameters"
       (config/normalize-config
        (custom-config {:implicit-retries 0
                        :max-output 512})))))

(deftest model-configured-is-structural
  (is (true? (config/model-configured? app-shaped-config)))
  (is (true? (config/model-configured?
              (assoc app-shaped-config
                     :roles {:main {:connection "missing"
                                    :model "missing"}})))
      "an invalid role is a create-model-port error, not a silent absence")
  (is (false? (config/model-configured? {})))
  (is (false? (config/model-configured? {:connections []})))
  (is (true? (config/model-configured?
              {:connections [{:id "c"
                              :models [{:id "m"}]}]}))
      "a single connection/model is structurally configured without :roles"))
