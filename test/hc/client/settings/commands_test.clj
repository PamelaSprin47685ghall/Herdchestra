(ns hc.client.settings.commands-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.settings.commands :as commands]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.draft :as draft]
            [hc.client.settings.model :as model]
            [hc.client.settings.support :as support]))

(defn- normalized-connection []
  (connection/normalize-connection
   (support/connection "a" "A" "https://api.example.com/v1")))

(deftest command-allowed-test
  (is (not (commands/command-allowed?
            (support/state {:sync {:phase :connecting :command-epoch 7}}))))
  (is (not (commands/command-allowed?
            (support/state {:sync {:phase :connected-awaiting-sync
                                   :command-epoch 7}}))))
  (is (not (commands/command-allowed?
            (support/state {:sync {:phase :synced :command-epoch nil}}))))
  (is (not (commands/command-allowed?
            (support/state {:sync {:phase :synced :epoch 7}})))
      "the AG-UI feed epoch is not a command epoch")
  (is (commands/command-allowed?
       (support/state {:sync {:phase :synced :command-epoch 7}}))))

(deftest queue-refuses-before-sync-test
  (let [state (support/state {:sync {:phase :connecting :command-epoch 7}})
        result (commands/queue state :settings/save-connection
                               {:draft-id "a" :draft-version 1}
                               {:command-id "cmd-1"})]
    (is (false? (:ok? result)))
    (is (= :settings/not-synced (get-in result [:error :code])))
    (is (empty? (:outbox (:state result))))))

(deftest envelope-shape-test
  (let [state (support/state)
        result (commands/queue state :settings/save-connection
                               {:draft-id "a" :draft-version 2
                                :config-digest "digest"}
                               {:command-id "cmd-1" :now "now"})
        envelope (first (:outbox (:state result)))]
    (is (:ok? result))
    (is (= "cmd-1" (:command-id envelope)))
    (is (= 7 (:command-epoch envelope)))
    (is (= :settings/save-connection (get-in envelope [:payload :kind])))
    (is (= "a" (get-in envelope [:payload :draft-id])))
    (is (= :queued (get-in (:state result) [:commands "cmd-1" :status])))))

(deftest duplicate-pending-refused-test
  (let [state (support/state)
        first (commands/queue state :settings/save-connection
                              {:draft-id "a" :draft-version 1}
                              {:command-id "cmd-1"})
        second (commands/queue (:state first) :settings/save-connection
                               {:draft-id "a" :draft-version 2}
                               {:command-id "cmd-2"})]
    (is (false? (:ok? second)))
    (is (= :settings/command-pending (get-in second [:error :code])))))

(deftest record-receipt-test
  (let [state (support/state)
        queued (commands/queue state :settings/save-connection
                               {:draft-id "a" :draft-version 1}
                               {:command-id "cmd-1"})
        receipt {:command-id "cmd-1" :status :accepted}
        result (commands/record-receipt (:state queued) receipt)]
    (is (= :accepted (get-in result [:command :status])))
    (is (empty? (:outbox (:state result))))
    (is (= receipt (get-in result [:state :commands "cmd-1" :receipt])))))

(deftest unknown-command-receipt-does-not-touch-drafts-test
  (let [state (-> (support/state {:connections {"a" (normalized-connection)}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Draft"}))
        result (model/apply-receipt state {:command-id "missing"
                                           :status :accepted})]
    (is (= "Draft" (get-in (:state result) [:drafts "a" :fields :name])))
    (is (seq (get-in (:state result) [:orphan-receipts])))))

(deftest transport-error-is-unknown-test
  (let [state (support/state)
        queued (commands/queue state :settings/save-connection
                               {:draft-id "a" :draft-version 1}
                               {:command-id "cmd-1"})
        next-state (commands/mark-outcome-unknown
                    (:state queued) "cmd-1" (ex-info "timeout" {}))]
    (is (= :outcome-unknown (get-in next-state [:commands "cmd-1" :status])))
    (is (empty? (:outbox next-state)))))

(deftest save-does-not-auto-test-and-test-does-not-save-test
  (let [state (-> (support/state {:connections {"a" (normalized-connection)}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Changed"}))
        saved (support/apply-action state
                                    {:type :settings/save-connection
                                     :draft-id "a"
                                     :command-id "save-1"})
        tested (support/apply-action state
                                     {:type :settings/test-connection
                                      :draft-id "a"
                                      :command-id "test-1"})]
    (is (= :settings/save-connection
           (get-in saved [:commands "save-1" :kind])))
    (is (empty? (:tests saved)))
    (is (= :settings/test-connection
           (get-in tested [:commands "test-1" :kind])))
    (is (= "Changed" (get-in tested [:drafts "a" :fields :name])))
    (is (draft/dirty? (get-in tested [:drafts "a"])))
    (is (= :pending (get-in tested [:tests "a" :status])))))

(deftest late-test-result-is-stale-test
  (let [state (-> (support/state {:connections {"a" (normalized-connection)}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Current"}))
        current-digest (connection/draft-digest (get-in state [:drafts "a"]))
        result {:draft-id "a"
                :config-digest "old-digest"
                :status :complete
                :layers {:reachable {:status :ok}
                         :minimal-request-ok {:status :ok}}}
        next-state (support/apply-action state
                                         {:type :settings/receive-test-result
                                          :result result})]
    (is (= "Current" (get-in next-state [:drafts "a" :fields :name])))
    (is (not= :complete (get-in next-state [:tests "a" :status])))
    (is (= "old-digest"
           (get-in next-state [:tests "a" :stale-results 0 :config-digest])))
    (is (= current-digest
           (connection/draft-digest (get-in next-state [:drafts "a"]))))))

(deftest matching-test-result-does-not-save-test
  (let [state (-> (support/state {:connections {"a" (normalized-connection)}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Current"}))
        digest (connection/draft-digest (get-in state [:drafts "a"]))
        result {:draft-id "a"
                :config-digest digest
                :status :complete
                :layers {:reachable {:status :ok}
                         :auth-ok {:status :ok}
                         :models-listed {:status :ok}
                         :minimal-request-ok {:status :ok}}}
        next-state (support/apply-action state
                                         {:type :settings/receive-test-result
                                          :result result})]
    (is (= :complete (get-in next-state [:tests "a" :status])))
    (is (re-find #"最小请求" (get-in next-state [:tests "a" :conclusion])))
    (is (= "Current" (get-in next-state [:drafts "a" :fields :name])))
    (is (draft/dirty? (get-in next-state [:drafts "a"])))))

(deftest failed-minimal-request-conclusion-is-not-success-test
  (let [state (-> (support/state {:connections {"a" (normalized-connection)}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Current"}))
        digest (connection/draft-digest (get-in state [:drafts "a"]))
        result {:draft-id "a"
                :config-digest digest
                :status :complete
                :layers {:reachable {:status :ok}
                         :auth-ok {:status :ok}
                         :models-listed {:status :ok}
                         :minimal-request-ok
                         {:status :failed
                          :message "model response contained no reply text"}}}
        next-state (support/apply-action state
                                         {:type :settings/receive-test-result
                                          :result result})
        conclusion (get-in next-state [:tests "a" :conclusion])]
    (is (= :complete (get-in next-state [:tests "a" :status])))
    (is (re-find #"最小请求失败" conclusion))
    (is (not (re-find #"可以生成" conclusion))
        "a failed model request must not be reported as a successful test")))

(deftest save-receipt-digest-mismatch-keeps-draft-test
  (let [state (-> (support/state {:connections {"a" (normalized-connection)}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Draft"})
                  (support/apply-action {:type :settings/save-connection
                                         :draft-id "a"
                                         :command-id "cmd-3"}))
        receipt {:command-id "cmd-3"
                 :status :accepted
                 :draft-id "a"
                 :connection-id "a"
                 :draft-version 2
                 :config-digest "different-digest"
                 :saved-config-digest "different-digest"}
        next-state (:state (model/apply-receipt state receipt))]
    (is (some? (get-in next-state [:drafts "a"])))
    (is (= "Draft" (get-in next-state [:drafts "a" :fields :name])))
    (is (= :refused (get-in next-state [:drafts "a" :status])))))

(deftest refresh-models-receipt-merges-and-keeps-old-test
  (let [state (support/state {:connections {"a" (normalized-connection)}
                              :models {"a" [(support/model "old" "Old"
                                                           {:enabled? false
                                                            :alias "kept"})]}})
        state (support/apply-action state {:type :settings/open})
        state (support/apply-action state {:type :settings/select-connection
                                           :connection-id "a"})
        state (support/apply-action state {:type :settings/refresh-models
                                           :connection-id "a"
                                           :command-id "refresh-1"})
        receipt {:command-id "refresh-1"
                 :status :accepted
                 :models [{:model-id "new" :display-name "New"
                           :capabilities {:vision :known}}]}
        next-state (:state (model/apply-receipt state receipt))
        models (get-in next-state [:server :models "a"])]
    (is (= :listed (get-in next-state [:server :model-list-status "a"])))
    (is (= #{"old" "new"} (set (map :model-id models))))
    (is (= false (:enabled? (first (filter #(= "old" (:model-id %)) models)))))
    (is (= "kept" (:alias (first (filter #(= "old" (:model-id %)) models)))))
    (is (= :not-returned (:availability (first (filter #(= "old" (:model-id %)) models)))))))

(deftest command-payload-secret-redacted-after-send-test
  (let [state (support/state)
        queued (commands/queue state :settings/save-connection
                               {:draft-id "a" :draft-version 1
                                :credentials [{:key-id "k" :secret "raw"}]}
                               {:command-id "cmd-secret"})
        next-state (commands/mark-submitting (:state queued) "cmd-secret")]
    (is (= "***" (get-in next-state [:commands "cmd-secret" :payload
                                     :credentials 0 :secret])))
    (is (not (re-find #"raw" (pr-str (get-in next-state [:commands "cmd-secret"])))))))

