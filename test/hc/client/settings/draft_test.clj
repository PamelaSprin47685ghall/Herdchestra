(ns hc.client.settings.draft-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.settings.catalog :as catalog]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.draft :as draft]
            [hc.client.settings.model :as model]
            [hc.client.settings.support :as support]))

(defn- normalized-connection []
  (connection/normalize-connection
   (support/connection "a" "A" "https://api.example.com/v1")))

(deftest new-drafts-have-independent-identity-test
  (let [local (draft/blank-draft :local-service {:draft-id "local-1"
                                                 :key-id "local-key"
                                                 :auth-edit-id "local-edit"})
        cloud (draft/blank-draft :cloud-key {:draft-id "cloud-1"
                                             :key-id "cloud-key"
                                             :auth-edit-id "cloud-edit"})]
    (is (= :new (:mode local)))
    (is (nil? (:connection-id local)))
    (is (= :local-service (get-in local [:fields :service-kind])))
    (is (empty? (:models local)))
    (is (empty? (:manual-models local)))
    (is (empty? (:enabled-models local)))
    (is (draft/untouched-new? local))
    (is (not= (:draft-id local) (:draft-id cloud)))
    (is (= "本地模型服务" (draft/path-label local)))
    (is (= "未命名新连接" (draft/display-name local)))
    (is (= "local-1" (draft/short-id local)))))

(deftest edited-new-draft-is-not-untouched-test
  (let [base (draft/blank-draft :local-service {:draft-id "local-1"})
        edited (draft/edit-field base :name "Workstation" {})]
    (is (draft/untouched-new? base))
    (is (not (draft/untouched-new? edited)))
    (is (= "Workstation" (draft/display-name edited)))))

(deftest draft-preserved-across-close-and-open-test
  (let [state (-> (support/state {:connections {"a" (normalized-connection)}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Renamed"}))
        draft (get-in state [:drafts "a"])
        closed (support/apply-action state {:type :settings/close})
        reopened (support/apply-action closed {:type :settings/open})]
    (is (= "Renamed" (get-in reopened [:drafts "a" :fields :name])))
    (is (= (:version draft) (get-in reopened [:drafts "a" :version])))
    (is (draft/dirty? (get-in reopened [:drafts "a"])))
    (is (= :dirty (draft/status (get-in reopened [:drafts "a"]))))))

(deftest revert-to-base-is-clean-test
  (let [base (draft/draft-from-connection (normalized-connection))
        changed (draft/edit-field base :name "Other" {})
        reverted (draft/edit-field changed :name "A" {})]
    (is (draft/dirty? changed))
    (is (not (draft/dirty? reverted)))
    (is (= :clean (draft/status reverted)))
    (is (= (connection/connection-digest (normalized-connection))
           (connection/draft-digest reverted)))))

(deftest secret-payload-test
  (let [base (draft/draft-from-connection (normalized-connection))
        set (draft/edit-key-secret base "a-key" "new-secret" {:auth-edit-id "e1"})
        cleared (draft/clear-key-secret base "a-key" {})
        set-payload (connection/draft-payload set)
        cleared-payload (connection/draft-payload cleared)]
    (is (not (contains? (first (:keys set-payload)) :secret)))
    (is (not (contains? (first (:keys cleared-payload)) :secret)))
    (is (= "new-secret" (:secret (first (:credentials set-payload)))))
    (is (= ["a-key"] (:clear-credentials cleared-payload)))
    (is (not (re-find #"new-secret"
                      (pr-str (connection/redact-payload set-payload)))))
    (is (empty? (connection/draft-validation-errors set)))
    (is (empty? (connection/draft-validation-errors cleared)))))

(deftest save-accepted-clears-only-exact-version-test
  (let [base (draft/draft-from-connection (normalized-connection))
        submitted (connection/draft-payload base)
        receipt {:status :accepted
                 :draft-id "a"
                 :connection-id "a"
                 :draft-version 1
                 :config-digest (:config-digest submitted)
                 :saved-config-digest (:config-digest submitted)
                 :connection (assoc (normalized-connection) :name "A")}
        exact (draft/apply-save-accepted base receipt submitted)
        newer (draft/edit-field base :name "Newer" {})
        newer-result (draft/apply-save-accepted newer receipt submitted)]
    (is (true? (:clear? exact)))
    (is (false? (:clear? newer-result)))
    (is (= "Newer" (get-in newer-result [:draft :fields :name])))
    (is (draft/dirty? (:draft newer-result)))
    (is (= :saved-older (:status (:draft newer-result))))))

(deftest newer-edit-survives-model-receipt-test
  (let [state (-> (support/state {:connections {"a" (normalized-connection)}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Submitted"})
                  (support/apply-action {:type :settings/save-connection
                                         :draft-id "a"
                                         :command-id "cmd-1"}))
        command (get-in state [:commands "cmd-1"])
        payload (:payload command)
        state (support/apply-action state
                                    {:type :settings/edit-field
                                     :draft-id "a"
                                     :field :name
                                     :value "Newer"})
        receipt {:command-id "cmd-1"
                 :status :accepted
                 :draft-id "a"
                 :connection-id "a"
                 :draft-version (:draft-version payload)
                 :config-digest (:config-digest payload)
                 :saved-config-digest (:config-digest payload)
                 :connection (assoc (normalized-connection) :name "Submitted")}
        result (model/apply-receipt state receipt)
        next-state (:state result)]
    (is (= "Newer" (get-in next-state [:drafts "a" :fields :name])))
    (is (draft/dirty? (get-in next-state [:drafts "a"])))
    (is (= "Submitted" (get-in next-state [:server :connections "a" :name])))))

(deftest refused-save-keeps-draft-test
  (let [state (-> (support/state {:connections {"a" (normalized-connection)}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Nope"})
                  (support/apply-action {:type :settings/save-connection
                                         :draft-id "a"
                                         :command-id "cmd-2"}))
        receipt {:command-id "cmd-2"
                 :status :refused
                 :error {:message "server says no"}}
        next-state (:state (model/apply-receipt state receipt))]
    (is (= "Nope" (get-in next-state [:drafts "a" :fields :name])))
    (is (= :refused (get-in next-state [:drafts "a" :status])))
    (is (draft/dirty? (get-in next-state [:drafts "a"])))))

(deftest export-import-test
  (let [base (draft/draft-from-connection (normalized-connection))
        secret (draft/edit-key-secret base "a-key" "raw-secret" {:auth-edit-id "e1"})
        payload (draft/export-drafts {"a" secret})]
    (is (= "raw-secret" (:secret (first (:keys secret)))))
    (is (not (re-find #"raw-secret" (pr-str payload))))
    (is (= :unchanged (get-in payload [:drafts "a" :keys 0 :auth-state])))
    (is (= {} (draft/import-drafts {:version 99 :drafts {"a" base}})))))

(deftest removing-a-saved-key-clears-its-server-credential-test
  (let [base (draft/draft-from-connection (normalized-connection))
        removed (draft/remove-key base "a-key" {})
        payload (connection/draft-payload removed)
        added (draft/add-key base {:key-id "new-key"
                                   :auth-edit-id "new-edit"})
        added-removed (draft/remove-key added "new-key" {})
        added-payload (connection/draft-payload added-removed)]
    (is (= ["a-key"] (:clear-credentials payload)))
    (is (empty? (:keys payload)))
    (is (empty? (:clear-credentials added-payload)))))

(deftest account-login-draft-needs-no-api-key-test
  (let [draft (-> (draft/blank-draft :account-login
                                     {:draft-id "auth-draft"})
                  (draft/edit-field :name "Account Service")
                  (draft/edit-field :base-url "https://auth.example.com/v1"))]
    (is (empty? (:keys draft)))
    (is (empty? (connection/draft-validation-errors draft)))))

(deftest manual-model-capability-starts-unknown-test
  (let [base (draft/draft-from-connection (normalized-connection))
        with-model (draft/add-manual-model base "manual-model" {})
        model (first (:models with-model))
        payload (connection/draft-payload with-model)]
    (is (= "manual-model" (:model-id model)))
    (is (= :manual (:source model)))
    (is (= {} (:capabilities model))
        "a manual model never claims tool support by default")
    (is (= :unknown (get-in (catalog/normalize-capabilities
                             (:capabilities model))
                            [:tools]
                            :unknown)))
    (is (= ["manual-model"] (:manual-models payload)))
    (is (= {} (get-in payload [:fields :models 0 :capabilities])))
    (is (empty? (:models (draft/remove-manual-model with-model
                                                     "manual-model" {}))))))

(deftest declared-model-capability-is-persisted-and-restorable-test
  (let [base (draft/draft-from-connection (normalized-connection))
        with-model (draft/add-manual-model base "manual-model" {})
        declared (draft/set-model-capability with-model "manual-model"
                                             :tools "known" {})
        model (first (:models declared))
        payload (connection/draft-payload declared)
        restored (draft/restore-model-capabilities declared
                                                   "manual-model" :tools {})]
    (is (true? (get-in model [:capabilities :tools])))
    (is (= :known
           (get-in (catalog/normalize-capabilities (:capabilities model))
                   [:tools])))
    (is (= :user (get-in model [:capability-sources :tools])))
    (is (= :unknown (get-in model [:capability-baseline :tools])))
    (is (draft/dirty? declared))
    (is (true? (get-in payload [:fields :models 0 :capabilities :tools])))
    (is (= :user
           (get-in payload [:fields :models 0 :capability-sources :tools])))
    (is (= :unknown
           (get-in payload [:fields :models 0 :capability-baseline :tools])))
    (is (nil? (get-in restored [:models 0 :capabilities :tools]))
        "restoring known information removes the invented declaration")
    (is (empty? (get-in restored [:models 0 :capability-sources])))
    (is (= (connection/draft-digest with-model)
           (connection/draft-digest restored)))))

(deftest declaring-the-baseline-value-removes-the-override-test
  (let [base (draft/draft-from-connection
              (connection/normalize-connection
               (support/connection "a" "A" "https://api.example.com/v1"
                                   {:models [{:id "m"
                                              :name "M"
                                              :capabilities {:tools :unknown}}]})))
        declared (draft/set-model-capability base "m" :tools :known {})
        reverted (draft/set-model-capability declared "m" :tools :unknown {})]
    (is (= :user (get-in declared [:models 0 :capability-sources :tools])))
    (is (empty? (get-in reverted [:models 0 :capability-sources])))
    (is (= (connection/draft-digest base)
           (connection/draft-digest reverted)))))

(deftest edit-model-fields-preserve-default-and-zero-test
  (let [base (-> (draft/draft-from-connection (normalized-connection))
                 (draft/add-manual-model "m" {}))
        zero (-> base
                 (draft/edit-model-field "m" :temperature "0" {})
                 (draft/edit-model-field "m" :top-p "0" {})
                 (draft/edit-model-field "m" :max-output "0" {}))
        default (-> zero
                    (draft/edit-model-field "m" :temperature "" {})
                    (draft/edit-model-field "m" :top-p "" {})
                    (draft/edit-model-field "m" :max-output "" {}))
        priced (-> base
                   (draft/edit-model-field "m" :pricing-input "1.5" {})
                   (draft/edit-model-field "m" :pricing-output "0" {})
                   (draft/edit-model-field "m" :pricing-currency "USD" {})
                   (draft/edit-model-field "m" :pricing-unit
                                           :per-million-tokens {}))]
    (is (zero? (:temperature (first (:models zero)))))
    (is (zero? (:top-p (first (:models zero)))))
    (is (zero? (:max-output (first (:models zero)))))
    (is (= :default (:temperature (first (:models default)))))
    (is (= :default (:top-p (first (:models default)))))
    (is (= :default (:max-output (first (:models default)))))
    (is (= 1.5 (get-in priced [:models 0 :pricing :input])))
    (is (zero? (get-in priced [:models 0 :pricing :output])))
    (is (not= (connection/draft-digest zero)
              (connection/draft-digest default)))))

(deftest model-id-rename-moves-enabled-and-manual-ids-test
  (let [base (-> (draft/draft-from-connection (normalized-connection))
                 (draft/add-manual-model "old-model" {}))
        renamed (draft/edit-model-field base "old-model" :model-id
                                        "new-model" {})]
    (is (= "new-model" (get-in renamed [:models 0 :model-id])))
    (is (contains? (set (:enabled-models renamed)) "new-model"))
    (is (not (contains? (set (:enabled-models renamed)) "old-model")))
    (is (= ["new-model"] (:manual-models renamed)))))

(deftest toggle-enabled-model-syncs-row-flag-test
  (let [base (-> (draft/draft-from-connection (normalized-connection))
                 (draft/add-manual-model "m" {}))
        disabled (draft/toggle-enabled-model base "m" {})
        enabled (draft/toggle-enabled-model disabled "m" {})]
    (is (false? (get-in disabled [:models 0 :enabled?])))
    (is (not (contains? (set (:enabled-models disabled)) "m")))
    (is (true? (get-in enabled [:models 0 :enabled?])))
    (is (contains? (set (:enabled-models enabled)) "m"))))

(deftest export-drafts-has-no-secret-and-detector-catches-one-test
  (let [base (draft/draft-from-connection (normalized-connection))
        secret (draft/edit-key-secret base "a-key" "raw-secret"
                                      {:auth-edit-id "e1"})
        payload (draft/export-drafts {"a" secret})]
    (is (not (draft/secret-leak? payload)))
    (is (draft/secret-leak? {:drafts {"a" {:keys [{:secret "raw"}]}}}))
    (is (draft/secret-leak? {:drafts {"a" {:saved-connection
                                           {:secret-ref "ref"}}}}))))

(deftest manual-models-are-not-unknown
  (let [base (draft/blank-draft :local-service {:draft-id "manual-1"})
        added (draft/add-manual-model base "manual-model" {})
        model (first (:models added))]
    (is (= :not-returned (:availability model))
        "a typed id was not returned by a listing, not proven unusable")))
