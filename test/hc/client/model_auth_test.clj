(ns hc.client.model-auth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.model-auth :as model-auth]))

(def providers
  [{:id :local
    :name "Local OAuth"
    :flows [:authorization-code :device-code]
    :configured? true
    :client-id-env "HC_MODEL_AUTH_LOCAL_CLIENT_ID"}
   {:id :unconfigured
    :name "Unconfigured"
    :flows [:authorization-code]
    :configured? false}])

(defn- step
  [state action]
  (model-auth/reduce state action))

(deftest begin-effect-and-device-code-test
  (let [state (-> model-auth/initial-state
                  (assoc :providers providers
                         :selected-provider :local
                         :connection-id "conn-1"))
        {:keys [state effects]} (step state
                                      {:type :model-auth/begin
                                       :provider-id :local
                                       :connection-id "conn-1"})
        submit (first (filter #(= :submit (:effect %)) effects))]
    (is (= :starting (:status state)))
    (is (= :model-auth/begin (:kind submit)))
    (is (= "conn-1" (get-in submit [:payload :connection-id])))
    (is (not (contains? (:payload submit) :password)))
    (is (not (contains? (:payload submit) :access-token)))
    (let [{:keys [state effects]}
          (step state
                {:type :model-auth/begin-result
                 :receipt {:ok? true
                           :receipt {:status :pending
                                     :flow-id "flow-1"
                                     :provider-id :local
                                     :provider-name "Local OAuth"
                                     :flow-type :device-code
                                     :user-code "ABCD-EFGH"
                                     :verification-uri "https://example.test/device"
                                     :expires-at-ms 9999999999999
                                     :interval-seconds 2}}})]
      (is (= :pending (:status state)))
      (is (= "ABCD-EFGH" (get-in state [:flow :user-code])))
      (is (some #(= :schedule-poll (:effect %)) effects))
      (is (not (contains? state :access-token)))
      (let [tree (model-auth/login-panel state)]
        (is (= :section (:tag tree)))
        (is (not (clojure.string/includes? (pr-str tree) "password")))
        (is (not (clojure.string/includes? (pr-str tree) "username")))
        (is (clojure.string/includes? (pr-str tree) "ABCD-EFGH"))))))

(deftest receipt-action-and-connection-patch-test
  (is (= {:type :model-auth/begin-result
          :receipt {:status :pending :flow-id "f"}}
         (model-auth/receipt-action
          :model-auth/begin
          {:ok? true :receipt {:status :pending :flow-id "f"}})))
  (is (= :model-auth/status-result
         (:type (model-auth/receipt-action
                 :model-auth/poll
                 {:ok? true :receipt {:status :pending :flow-id "f"}}))))
  (is (= :model-auth/providers
         (:type (model-auth/receipt-action
                 :model-auth/providers
                 {:ok? true :receipt {:providers providers}}))))
  (let [state (-> model-auth/initial-state
                  (assoc :providers providers
                         :selected-provider :local
                         :connection-id "conn-1"
                         :status :authorized
                         :account {:connection-id "conn-1"
                                   :provider-id :local
                                   :issuer "https://issuer.test"
                                   :subject "user-1"
                                   :account-label "alice@example.test"
                                   :masked-account "a***@example.test"
                                   :scopes ["openid"]}))
        patch (model-auth/connection-patch state)]
    (is (= :account-login (:service-kind patch)))
    (is (= "alice@example.test" (:account-label patch)))
    (is (= "conn-1" (:auth-ref patch)))
    (is (= :local (get-in patch [:auth :oauth :provider-id])))
    (is (not (contains? patch :access-token)))
    (is (not (contains? patch :refresh-token)))
    (is (not (contains? patch :device-code)))))

(deftest expiry-and-error-test
  (let [state (-> model-auth/initial-state
                  (assoc :flow {:flow-id "flow-1"
                                :status :pending
                                :expires-at-ms 1000})
                  (assoc :now-ms 1001))
        {:keys [state effects]} (step state
                                      {:type :model-auth/tick
                                       :now-ms 1001})]
    (is (= :expired (:status state)))
    (is (some #(= :cancel-poll (:effect %)) effects)))
  (let [{:keys [state]} (step (assoc model-auth/initial-state
                                     :providers providers)
                              {:type :model-auth/select-provider
                               :provider-id :unconfigured})
        {:keys [state]} (step (assoc state :connection-id "conn-1")
                              {:type :model-auth/begin
                               :provider-id :unconfigured})]
    (is (= :MODEL_AUTH_PROVIDER_NOT_CONFIGURED (get-in state [:error :code])))))

(deftest account-masking-test
  (is (= "a***@example.test"
         (model-auth/masked-account "alice@example.test")))
  (is (= "ab***yz"
         (model-auth/masked-account "abcdefghijklmnopqrstuvwxyz")))
  (is (= "***" (model-auth/masked-account ""))))

(deftest react-adapter-value-selects-provider-and-flow-test
  (let [state (-> model-auth/initial-state
                  (assoc :providers providers
                         :selected-provider :local
                         :connection-id "conn-1"))
        provider (-> state
                     (step {:type :model-auth/select-provider
                            :value "unconfigured"})
                     :state)
        flow (-> state
                 (step {:type :model-auth/select-flow-type
                        :value "device-code"})
                 :state)]
    (is (= :unconfigured (:selected-provider provider)))
    (is (= :device-code (:selected-flow-type flow)))))

(deftest begin-validation-error-is-visible-test
  (let [{:keys [state]} (step (assoc model-auth/initial-state
                                     :providers providers)
                              {:type :model-auth/begin
                               :provider-id :unconfigured
                               :connection-id "conn-1"})]
    (is (= :error (:status state)))
    (is (= :MODEL_AUTH_PROVIDER_NOT_CONFIGURED (get-in state [:error :code])))))

(deftest logged-out-removes-only-that-account-test
  (let [state (assoc model-auth/initial-state
                     :connection-id "conn-1"
                     :status :logging-out
                     :flow {:flow-id "flow-1"
                            :connection-id "conn-1"}
                     :accounts [{:connection-id "conn-1"
                                 :account-label "one@example.com"}
                                {:connection-id "conn-2"
                                 :account-label "two@example.com"}])
        {:keys [state]} (step state
                              {:type :model-auth/status-result
                               :receipt {:status :logged-out
                                         :connection-id "conn-1"}})]
    (is (= :not-connected (:status state)))
    (is (nil? (:account state)))
    (is (= ["conn-2"] (mapv :connection-id (:accounts state))))))

(deftest authorized-panel-shows-sync-time-and-refresh-test
  (let [state (assoc model-auth/initial-state
                     :providers providers
                     :selected-provider :local
                     :connection-id "conn-1"
                     :status :authorized
                     :account {:connection-id "conn-1"
                               :provider-id :local
                               :account-label "alice@example.test"
                               :masked-account "a***@example.test"
                               :last-synced-at "2026-11-05T10:00:00Z"})
        text (pr-str (model-auth/login-panel state))]
    (is (str/includes? text "最近同步"))
    (is (str/includes? text "刷新账号状态"))))
