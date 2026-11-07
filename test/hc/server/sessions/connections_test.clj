(ns hc.server.sessions.connections-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.sessions.connections :as connections]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.secrets :as secrets]))

(defn- new-connection
  ([]
   (new-connection {}))
  ([opts]
   (let [result (connections/new-connection
                 (merge {:connection-id "conn-1"
                         :name "Personal"
                         :kind :preset
                         :default-base-url "https://api.example.com"
                         :now 1000}
                        opts))]
     (is (:ok? result) (pr-str result))
     (:connection result))))

(defn- add-credential
  ([connection credential]
   (add-credential connection credential 1000))
  ([connection credential now]
   (let [result (connections/add-credential connection credential now)]
     (is (:ok? result) (pr-str result))
     (:connection result))))

(deftest preset-connection-is-official-until-the-host-changes
  (let [connection (new-connection)]
    (is (true? (:official? connection)))
    (is (= "https://api.example.com" (:effective-base-url connection)))
    (is (= "api.example.com" (:display-host connection)))
    (let [changed (:connection
                   (connections/set-custom-base-url
                    connection "https://gateway.internal.example/v1" 2000))]
      (is (false? (:official? changed)))
      (is (= "https://gateway.internal.example/v1"
             (:effective-base-url changed)))
      (is (= "gateway.internal.example" (:display-host changed)))
      (is (= "Personal" (:name changed))))))

(deftest custom-connections-are-never-official
  (let [connection (new-connection {:connection-id "conn-2"
                                    :kind :custom
                                    :name "Work gateway"
                                    :default-base-url "https://work.example"})]
    (is (false? (:official? connection)))
    (is (= "work.example" (:display-host connection)))))

(deftest invalid-base-urls-are-refused
  (doseq [url [nil "" "not-a-url" "ftp://example.com" "https://"]]
    (let [result (connections/new-connection
                  {:connection-id "conn-x"
                   :name "X"
                   :kind :preset
                   :default-base-url url
                   :now 1})]
      (is (not (:ok? result)) (pr-str url))
      (is (= :INVALID_BASE_URL (get-in result [:error :code]))))))

(deftest raw-secrets-never-enter-state
  (let [connection (new-connection)]
    (doseq [secret-field [:secret :api-key :token :password]]
      (let [result (connections/add-credential
                    connection
                    {:credential-id "cred-x"
                     :secret-ref "vault://x"
                     :masked-key "sk-...x"
                     secret-field "raw-secret"}
                    1000)]
        (is (not (:ok? result)))
        (is (= :SECRET_IN_STATE (get-in result [:error :code])))))
    (let [with-key (add-credential
                    connection
                    {:credential-id "cred-1"
                     :alias "primary"
                     :secret-ref "vault://test/cred-1"
                     :masked-key "sk-...abcd"})
          public (connections/public-credential
                  (get-in with-key [:credentials "cred-1"]))]
      (is (= "sk-...abcd" (:masked-key public)))
      (is (not (contains? public :secret-ref)))
      (is (not (contains? public :secret))))))

(deftest auth-failure-and-rate-limit-are-separate-exclusions
  (let [connection (-> (new-connection)
                       (add-credential {:credential-id "cred-1"
                                        :secret-ref "vault://1"
                                        :masked-key "sk-...1"})
                       (add-credential {:credential-id "cred-2"
                                        :secret-ref "vault://2"
                                        :masked-key "sk-...2"}))
        failed (:connection (connections/record-auth-failure
                             connection "cred-1"
                             {:code :invalid-api-key}
                             2000))
        limited (:connection (connections/record-rate-limit
                              failed "cred-2" 5000 2000))
        at-3000 (connections/choose-credential limited 3000)
        at-6000 (connections/choose-credential limited 6000)]
    (is (false? (connections/credential-eligible?
                 (get-in limited [:credentials "cred-1"]) 6000)))
    (is (false? (connections/credential-eligible?
                 (get-in limited [:credentials "cred-2"]) 3000)))
    (is (true? (connections/credential-eligible?
                (get-in limited [:credentials "cred-2"]) 6000)))
    (is (false? (:ok? at-3000)))
    (is (= :NO_ELIGIBLE_CREDENTIAL (get-in at-3000 [:error :code])))
    (is (true? (:ok? at-6000)))
    (is (= "cred-2" (get-in at-6000 [:credential :credential-id])))))

(deftest auth-success-re-enables-a-key
  (let [connection (-> (new-connection)
                       (add-credential {:credential-id "cred-1"
                                        :secret-ref "vault://1"
                                        :masked-key "sk-...1"}))
        failed (:connection (connections/record-auth-failure
                             connection "cred-1" {:code :expired} 2000))
        success (:connection (connections/record-auth-success
                              failed "cred-1" 3000))
        chosen (connections/choose-credential success 4000)]
    (is (true? (get-in failed [:credentials "cred-1" :auth-failed?])))
    (is (false? (get-in success [:credentials "cred-1" :auth-failed?])))
    (is (true? (:ok? chosen)))
    (is (= "cred-1" (get-in chosen [:credential :credential-id])))))

(deftest rotation-policies-pick-as-expected
  (let [base (-> (new-connection)
                 (add-credential {:credential-id "a"
                                  :secret-ref "vault://a"
                                  :masked-key "sk-a"
                                  :priority 2})
                 (add-credential {:credential-id "b"
                                  :secret-ref "vault://b"
                                  :masked-key "sk-b"
                                  :priority 1}))
        priority (connections/choose-credential base 1000)
        round-robin (connections/choose-credential
                     (assoc base :rotation-policy :round-robin) 1000)
        least-used (connections/choose-credential
                    (assoc base :rotation-policy :least-used) 1000)
        random (connections/choose-credential
                (assoc base :rotation-policy :random) 1000
                {:random-int (fn [_] 1)})]
    (is (= "b" (get-in priority [:credential :credential-id])))
    (is (= "a" (get-in round-robin [:credential :credential-id])))
    (is (= "b" (get-in least-used [:credential :credential-id])))
    (is (= "b" (get-in random [:credential :credential-id])))
    (is (= 1 (get-in priority [:credential :use-count])))))

(deftest refresh-preserves-local-state-and-marks-missing-models
  (let [source (ids/counter-source)
        connection (-> (new-connection)
                       (add-credential {:credential-id "cred-1"
                                        :secret-ref "vault://1"
                                        :masked-key "sk-...1"}))
        first-refresh (:connection
                       (connections/refresh-models
                        connection
                        [{:provider-model-id "model-a"
                          :display-name "Model A"
                          :capabilities {:text true}}
                         {:provider-model-id "model-b"
                          :display-name "Model B"
                          :capabilities {:vision :unknown}}]
                        source 2000))
        model-a (first (:model-order first-refresh))
        model-b (second (:model-order first-refresh))
        customized (-> first-refresh
                       (connections/set-model-enabled model-a false 2100)
                       (:connection)
                       (connections/set-model-alias model-a "local-a" 2200)
                       (:connection)
                       (connections/set-model-notes model-a "keep me" 2300)
                       (:connection))
        second-refresh (:connection
                        (connections/refresh-models
                         customized
                         [{:provider-model-id "model-a"
                           :display-name "Model A2"
                           :capabilities {:text true :tools true}}]
                         source 3000))]
    (is (false? (get-in second-refresh [:models model-a :enabled?])))
    (is (= "local-a" (get-in second-refresh [:models model-a :alias])))
    (is (= "keep me" (get-in second-refresh [:models model-a :notes])))
    (is (= "Model A2" (get-in second-refresh [:models model-a :display-name])))
    (is (= true (get-in second-refresh [:models model-a :capabilities :tools])))
    (is (= :not-returned (get-in second-refresh [:models model-b :availability])))
    (is (contains? (:models second-refresh) model-b))
    (is (= 2 (count (:model-order second-refresh))))))

(deftest an-unavailable-list-keeps-models-and-allows-manual-entry
  (let [source (ids/counter-source)
        connection (:connection
                    (connections/refresh-models
                     (new-connection)
                     [{:provider-model-id "model-a" :display-name "Model A"}]
                     source 2000))
        unavailable (:connection
                     (connections/mark-model-list-unavailable
                      connection :listing-endpoint-down 3000))
        manual (connections/add-manual-model
                unavailable
                {:provider-model-id "manual-model"
                 :display-name "Manual"}
                source 4000)]
    (is (= :unavailable (get-in unavailable [:list-status :state])))
    (is (= 1 (count (:models unavailable))))
    (is (:ok? manual))
    (is (= :manual (get-in manual [:connection :models
                                   (:model-entry-id manual)
                                   :source])))
    (is (= :unconfirmed (get-in manual [:connection :models
                                        (:model-entry-id manual)
                                        :availability])))))

(deftest capability-and-availability-are-separate
  (let [source (ids/counter-source)
        connection (:connection
                    (connections/refresh-models
                     (new-connection)
                     [{:provider-model-id "model-a"
                       :capabilities {:text true :vision false}}]
                     source 2000))
        model (get-in connection [:models (first (:model-order connection))])
        public (connections/public-model connection model)
        disabled (connections/set-model-enabled connection
                                                 (:model-entry-id model)
                                                 false 3000)]
    (is (= false (connections/model-capability model :vision)))
    (is (= :unknown (connections/model-capability model :reasoning)))
    (is (= :available (:availability public)))
    (is (= #{:text} (connections/confirmed-capabilities model)))
    (is (false? (get-in disabled [:connection :models
                                  (:model-entry-id model) :enabled?])))
    (is (= :disabled
           (connections/model-availability
            (:connection disabled)
            (get-in (:connection disabled)
                    [:models (:model-entry-id model)]))))))

(deftest unknown-pricing-is-not-zero
  (let [source (ids/counter-source)
        connection (:connection
                    (connections/refresh-models
                     (new-connection)
                     [{:provider-model-id "model-a"}]
                     source 2000))
        model (get-in connection [:models (first (:model-order connection))])]
    (is (= {:status :unknown} (:pricing model)))
    (is (= {:status :unknown} (:pricing (connections/public-model connection model))))))

(deftest a-binding-snapshot-carries-no-secret
  (let [source (ids/counter-source)
        connection (-> (new-connection)
                       (add-credential {:credential-id "cred-1"
                                        :secret-ref "vault://1"
                                        :masked-key "sk-...1"}))
        connection (:connection
                    (connections/refresh-models
                     connection
                     [{:provider-model-id "model-a"
                       :display-name "Model A"
                       :capabilities {:text true}}]
                     source 2000))
        model (get-in connection [:models (first (:model-order connection))])
        binding (connections/model-binding-snapshot connection model)]
    (is (= "conn-1" (:connection-id binding)))
    (is (= "model-a" (:provider-model-id binding)))
    (is (= true (get-in binding [:capabilities :text])))
    (is (not (connections/secret-field? binding)))
    (is (nil? (:secret-ref binding)))
    (is (nil? (:credential-id binding)))))

(deftest connection-invariants-hold-after-edits
  (let [source (ids/counter-source)
        connection (-> (new-connection)
                       (add-credential {:credential-id "cred-1"
                                        :secret-ref "vault://1"
                                        :masked-key "sk-...1"}))
        connection (:connection
                    (connections/refresh-models
                     connection
                     [{:provider-model-id "model-a"}]
                     source 2000))]
    (is (empty? (connections/check-connection-invariants connection)))))

(deftest connection-auth-status-drives-model-availability
  (let [source (ids/counter-source)
        connection (:connection
                    (connections/refresh-models
                     (new-connection)
                     [{:provider-model-id "model-a"}]
                     source 2000))
        model (get-in connection [:models (first (:model-order connection))])
        failed (:connection (connections/set-auth-status connection :failed 3000))
        public (connections/public-connection failed)]
    (is (= :auth-expired (connections/model-availability failed model)))
    (is (= :failed (:auth-status public)))
    (is (= :auth-expired (get-in public [:models 0 :availability])))
    (is (not (contains? (get-in public [:credentials 0]) :secret-ref)))))

(deftest connection-test-is-pending-then-recorded
  (let [connection (new-connection)
        pending (:connection (connections/request-connection-test connection 2000))
        failed (:connection (connections/record-test-result
                             pending {:status :failed
                                      :message "401 from provider"}
                             3000))
        succeeded (:connection (connections/record-test-result
                                failed {:status :ok}
                                4000))]
    (is (= :pending (get-in pending [:last-test :status])))
    (is (= :failed (get-in failed [:last-test :status])))
    (is (= "401 from provider" (get-in failed [:last-test :message])))
    (is (true? (:enabled? failed)))
    (is (= :ok (get-in succeeded [:last-test :status])))))

(deftest secret-masking-and-credential-lifecycle
  (let [store (secrets/memory-secret-store)
        connection (new-connection)
        raw "sk-live-super-secret-value"
        stored (secrets/put-secret! store "cred-1" raw)
        credential (connections/credential-from-secret
                    {:credential-id "cred-1"
                     :alias "primary"
                     :secret raw
                     :secret-ref (:secret-ref stored)
                     :priority 0})
        added (connections/add-credential-from-secret
               connection
               {:credential-id "cred-1"
                :alias "primary"
                :secret raw
                :secret-ref (:secret-ref stored)
                :priority 0}
               1000)
        rotated (connections/rotate-credential-secret
                 (:connection added) "cred-1" "sk-rotated-value"
                 (:secret-ref (secrets/put-secret! store "cred-1"
                                                   "sk-rotated-value"))
                 2000)
        public (connections/public-credential
                (get-in rotated [:connection :credentials "cred-1"]))
        removed (connections/remove-credential (:connection rotated)
                                               "cred-1" 3000)]
    (is (:ok? credential))
    (is (not (contains? (:credential credential) :secret)))
    (is (not (contains? (:credential credential) :api-key)))
    (is (clojure.string/starts-with? (connections/mask-secret raw) "sk-..."))
    (is (not (clojure.string/includes? (connections/mask-secret raw) raw)))
    (is (:ok? added))
    (is (= (:secret-ref stored)
           (get-in added [:connection :credentials "cred-1" :secret-ref])))
    (is (not (contains? public :secret-ref)))
    (is (clojure.string/starts-with? (:masked-key public) "sk-..."))
    (is (:ok? rotated))
    (is (false? (get-in rotated [:connection :credentials "cred-1"
                                 :auth-failed?])))
    (is (:ok? removed))
    (is (empty? (get-in removed [:connection :credentials])))))

(deftest binding-snapshot-normalizes-set-capabilities
  (let [connection {:connection-id "c1" :name "Provider" :kind :custom
                    :enabled? true :auth-status :unknown}
        model {:model-entry-id "m1" :provider-model-id "m1"
               :capabilities #{:tools :text}}]
    (is (= {:tools true :text true}
           (:capabilities (connections/model-binding-snapshot connection model)))
        "a scheduler/launch set must not crash the binding snapshot")))
