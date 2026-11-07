(ns hc.client.browser-model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.browser.model :as model]
            [hc.client.browser.view :as view]))

(defn- synced
  []
  (model/apply-connection (model/initial-state) :synced 3 "s1"))

(defn- snapshot
  []
  {:session-id "s1"
   :tabs {"tab-1" {:tab-id "tab-1"
                   :url "https://example.test/form"
                   :title "Example Form"
                   :site "example.test"
                   :takeover :agent-active
                   :agent-authorized? true
                   :generation 2
                   :last-observation
                   {:observation-id "obs-1"
                    :tab-id "tab-1"
                    :url "https://example.test/form"
                    :title "Example Form"
                    :generation 2
                    :text "Step 1"
                    :nodes [{:ref "hc-1" :tag "input" :type "email"
                             :label "Email" :sensitive? false}
                            {:ref "hc-2" :tag "input" :type "password"
                             :label "Password" :sensitive? true}]}}}
   :active-tab-id "tab-1"
   :permission {:enabled? true :scope :site :sites ["example.test"]
                :private-read-approved? true}
   :cards [{:card-id "card-1" :seq 1 :action :navigate
            :site "example.test" :target-summary "page"
            :result {:status :ok}
            :screenshot {:kind :historical :live? false}}]
   :cards-dropped 0
   :limits {:screenshot :historical-not-live}
   :stopped? false})

(deftest snapshot-projects-tabs-and-observation
  (let [state (model/apply-snapshot (synced) (snapshot))]
    (is (= "tab-1" (:active-tab-id state)))
    (is (= "https://example.test/form" (:address state)))
    (is (= "obs-1" (get-in state [:observation :observation-id])))
    (is (= 2 (:generation (model/active-tab state))))
    (is (= 1 (count (:cards state))))
    (is (= :historical (get-in state [:cards 0 :screenshot :kind])))
    (is (= :historical-not-live (get-in state [:limits :screenshot])))))

(deftest commands-stay-in-outbox-until-transport-accepts
  (let [state (synced)
        {:keys [ok? state command]} (model/queue-command
                                     state :browser/open-tab
                                     {:url "https://example.test/form"})]
    (is ok?)
    (is (= :queued (get-in state [:commands (:command-id command) :status])))
    (is (= 1 (count (model/pending-outbox state))))
    (is (= "https://example.test/form"
           (get-in state [:outbox 0 :payload :url])))
    (is (= "s1" (get-in state [:outbox 0 :payload :session-id])))
    (let [state (model/mark-submitting state (:command-id command))]
      (is (= :submitting (get-in state [:commands (:command-id command) :status])))
      (is (empty? (model/pending-outbox state))))))

(deftest unsynced-commands-are-refused
  (let [{:keys [ok? state error]} (model/queue-command
                                   (model/initial-state)
                                   :browser/observe {:tab-id "tab-1"})]
    (is (false? ok?))
    (is (string? error))
    (is (empty? (model/pending-outbox state)))))

(deftest session-and-single-flight-gates
  (let [no-session (model/apply-connection
                    (model/initial-state) :synced 3 nil)
        {:keys [ok?]} (model/queue-command
                       no-session :browser/snapshot {})]
    (is (false? ok?)))
  (let [{:keys [state]} (model/queue-command
                         (synced) :browser/observe {:tab-id "tab-1"})
        {:keys [ok? error]} (model/queue-command
                             state :browser/observe {:tab-id "tab-1"})]
    (is (false? ok?))
    (is (re-find #"在途" error))))

(deftest sensitive-fields-cannot-be-typed
  (let [state (-> (model/apply-snapshot (synced) (snapshot))
                  (assoc :selected-ref "hc-2"
                         :type-text "hunter2"))
        {:keys [ok? state error]} (model/reduce-action
                                   state {:type :browser/type})]
    (is (false? ok?))
    (is (re-find #"敏感" (:message (:last-error state))))
    (is (string? (:message (:last-error state))))))

(deftest form-inputs-use-target-values
  (let [state (synced)
        state (:state (model/reduce-action
                       state {:type :browser/set-type-text
                              :value "ordinary text"}))
        state (:state (model/reduce-action
                       state {:type :browser/set-permission-scope
                              :value "current-tab"}))
        state (:state (model/reduce-action
                       state {:type :browser/set-resume-mode
                              :value "read-only"}))]
    (is (= "ordinary text" (:type-text state)))
    (is (= :current-tab (get-in state [:permission-form :scope])))
    (is (= :read-only (:resume-mode state)))))

(deftest takeover-gates-agent-actions
  (let [state (-> (model/apply-snapshot (synced) (snapshot))
                  (assoc-in [:tabs "tab-1" :takeover] :operator))]
    (is (false? (model/agent-action-allowed? state)))
    (is (false? (model/read-allowed? state)))
    (let [state (assoc-in state [:tabs "tab-1" :takeover] :read-only)]
      (is (false? (model/agent-action-allowed? state)))
      (is (true? (model/read-allowed? state))))))

(deftest receipt-projects-accepted-result
  (let [state (synced)
        {:keys [state command]} (model/queue-command
                                 state :browser/observe {:tab-id "tab-1"})
        receipt {:command-id (:command-id command)
                 :status :accepted
                 :result {:status :ok
                          :observation {:observation-id "obs-2"
                                        :tab-id "tab-1"
                                        :url "https://example.test/form"
                                        :title "Example Form"
                                        :generation 3
                                        :text "Step 2"
                                        :nodes []}}}
        state (model/apply-receipt state receipt)]
    (is (= :accepted (get-in state [:commands (:command-id command) :status])))
    (is (= "obs-2" (get-in state [:observation :observation-id])))
    (is (= "Step 2" (get-in state [:observation :text])))
    (testing "a refused receipt is not projected as a success"
      (let [state (synced)
            {:keys [state command]} (model/queue-command
                                     state :browser/act {:tab-id "tab-1"})
            state (model/apply-receipt
                   state
                   {:command-id (:command-id command)
                    :status :accepted
                    :result {:status :refused
                             :error {:code :takeover-active
                                     :message "operator controls the page"}}})]
        (is (= :takeover-active (get-in state [:last-error :code])))
        (is (= :refused (get-in state [:commands (:command-id command) :status])))))))

(deftest view-tree-exposes-real-controls
  (let [state (model/apply-snapshot (synced) (snapshot))
        tree (view/browser-panel-tree state)
        rendered (pr-str tree)]
    (is (re-find #"受管浏览器" rendered))
    (is (re-find #"观察" rendered))
    (is (re-find #"我来操作" rendered))
    (is (re-find #"页面卡" rendered))
    (is (re-find #"历史截图" rendered))
    (is (re-find #"敏感，操作员输入" rendered))
    (is (re-find #"实例内浏览器" rendered))
    (is (re-find #"模型可读" rendered))
    (is (re-find #"站点：example.test" rendered))))

(deftest history-commands-queue-and-clear-stale-observation
  (let [state (model/apply-snapshot (synced) (snapshot))
        {:keys [ok? state command]} (model/queue-command
                                     state :browser/back {:tab-id "tab-1"})
        tab (assoc (model/active-tab state)
                   :url "https://example.test/step2"
                   :title "Example Step 2"
                   :generation 3
                   :last-observation nil)
        state (model/apply-receipt
               state
               {:command-id (:command-id command)
                :status :accepted
                :result {:status :ok
                         :tab tab
                         :navigation {:url "https://example.test/step2"
                                      :title "Example Step 2"
                                      :document-id "doc-2"}}})]
    (is ok?)
    (is (= :browser/back (get-in state [:outbox 0 :payload :kind])))
    (is (= "https://example.test/step2" (:address state)))
    (is (nil? (:observation state)))
    (is (nil? (:selected-ref state)))
    (is (= "https://example.test/step2" (get-in state [:tabs "tab-1" :url])))))


(deftest refused-result-without-error-is-not-success
  (let [state (synced)
        {:keys [state command]} (model/queue-command state :browser/stop {})
        state (model/apply-receipt
               state
               {:command-id (:command-id command)
                :status :accepted
                :result {:status :refused}})]
    (is (false? (:stopped? state)))
    (is (= :refused (get-in state [:commands (:command-id command) :status])))
    (is (some? (:last-error state)))))

(deftest partial-snapshot-does-not-clear-tabs
  (let [state (model/apply-snapshot (synced) (snapshot))
        after (model/apply-snapshot state {:status :ok})]
    (is (= (:tabs state) (:tabs after)))
    (is (= (:observation state) (:observation after)))
    (is (= (:active-tab-id state) (:active-tab-id after)))))

(deftest takeover-blocks-navigation-even-from-address-enter
  (let [state (-> (model/apply-snapshot (synced) (snapshot))
                  (assoc-in [:tabs "tab-1" :takeover] :operator))
        {:keys [ok? state]} (model/reduce-action
                             state
                             {:type :browser/navigate
                              :url "https://example.test/other"})]
    (is (false? ok?))
    (is (empty? (model/pending-outbox state)))
    (is (re-find #"禁止" (get-in state [:last-error :message])))))

(deftest read-permission-requires-an-authorized-tab
  (let [state (-> (model/apply-snapshot (synced) (snapshot))
                  (assoc-in [:permission :scope] :current-tab)
                  (assoc-in [:tabs "tab-1" :agent-authorized?] false))]
    (is (false? (model/read-allowed? state)))
    (is (false? (model/agent-action-allowed? state)))
    (let [authorized (assoc-in state [:tabs "tab-1" :agent-authorized?] true)]
      (is (true? (model/read-allowed? authorized)))
      (is (true? (model/agent-action-allowed? authorized))))))

(deftest private-read-requires-an-explicit-approval
  (is (false? (get-in (model/initial-state) [:permission :private-read-approved?])))
  (let [state (synced)
        {:keys [command]} (model/reduce-action
                           state
                           {:type :browser/permission-enable})]
    (is (not (contains? (:payload command) :private-read-approved?)))
    (is (false? (get-in (model/initial-state) [:permission :private-read-approved?]))))
  (let [state (synced)
        {:keys [command]} (model/reduce-action
                           state
                           {:type :browser/approve-private-read})]
    (is (true? (get-in command [:payload :private-read-approved?])))))


(deftest browser-command-kinds-cover-the-server-surface
  (is (= #{:browser/permission :browser/snapshot :browser/cards
           :browser/open-tab :browser/navigate :browser/back :browser/forward
           :browser/reload :browser/observe :browser/act
           :browser/takeover :browser/operator-input :browser/resume
           :browser/close-tab :browser/stop}
         model/browser-kinds)))
