(ns hc.client.schedule.model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.schedule.model :as model]))

(defn- synced
  ([] (synced {}))
  ([opts]
   (model/initial-state
    (merge {:platform :desktop
            :device-zone "Asia/Shanghai"
            :sync {:phase :synced :command-epoch 7}
            :now 1793752200000
            :catalog {:models [{:connection-id "conn-1"
                                :connection-name "Conn 1"
                                :model-entry-id "model-1"
                                :label "Model 1"}]}}
           opts))))

(defn- schedule
  [overrides]
  (merge {:id "schedule-1"
          :name "Morning report"
          :prompt "Summarize yesterday."
          :snapshot {:prompt "Summarize yesterday."
                     :execution {:mode :new-chat}
                     :model {:connection-id "conn-1"
                             :model-entry-id "model-1"}}
          :execution {:mode :new-chat}
          :recurrence {:kind :daily :time {:hour 8 :minute 30}}
          :timezone {:mode :fixed :zone "Asia/Shanghai"}
          :state :enabled
          :next-run 1793752200000
          :revision 1
          :attention nil}
         overrides))

(defn- submit-effect
  [result]
  (-> result :effects (first)))

(deftest initial-state-is-empty-and-honest
  (let [state (model/initial-state)]
    (is (empty? (get-in state [:server :schedules])))
    (is (empty? (get-in state [:server :runs])))
    (is (false? (get-in state [:server :loaded?])))
    (is (= :disconnected (get-in state [:sync :phase])))
    (is (= "仅支持前台手动运行" (get-in (model/initial-state {:platform :ios})
                                        [:platform :label])))
    (is (false? (get-in (model/initial-state {:platform :ios})
                        [:platform :automatic?])))))

(deftest form-validation-rejects-blank-and-invalid
  (let [state (synced)
        form (model/new-form state)]
    (testing "blank name and prompt"
      (let [validation (model/validate-form state form)]
        (is (false? (:ok? validation)))
        (is (contains? (:errors validation) :name))
        (is (contains? (:errors validation) :prompt))))
    (testing "invalid hour and weekly days"
      (let [form (-> form
                     (assoc :name "Report")
                     (assoc :prompt "Do work")
                     (assoc-in [:recurrence :kind] :weekly)
                     (assoc-in [:recurrence :days] [])
                     (assoc-in [:recurrence :time :hour] 24))
            validation (model/validate-form state form)]
        (is (false? (:ok? validation)))
        (is (contains? (:errors validation) :time))
        (is (contains? (:errors validation) :days))))
    (testing "an impossible calendar date is rejected"
      (let [form (-> form
                     (assoc :name "Report")
                     (assoc :prompt "Do work")
                     (assoc-in [:recurrence :kind] :once)
                     (assoc-in [:recurrence :local-date] "2026-02-31"))
            validation (model/validate-form state form)]
        (is (false? (:ok? validation)))
        (is (contains? (:errors validation) :recurrence-date))))
    (testing "a complete form validates"
      (let [form (-> form
                     (assoc :name "Report")
                     (assoc :prompt "Do work"))
            validation (model/validate-form state form)]
        (is (true? (:ok? validation)))))))

(deftest create-spec-follows-assistant-by-default
  (let [state (synced)
        form (-> (model/new-form state)
                 (assoc :name "  Report  ")
                 (assoc :prompt "Do work")
                 (assoc :save-mode :enable))
        spec (model/form->create-spec state form)]
    (is (= :follow-assistant (:model-mode form)))
    (is (nil? (:model form)))
    (is (= "Report" (:name spec)))
    (is (true? (:enable? spec)))
    (is (= :draft (:state spec)))
    (is (not (contains? (:snapshot spec) :model)))
    (testing "the form carries an explicit capability-scope key, even when nil"
      (is (contains? (:snapshot spec) :capability-scope))
      (is (nil? (get-in spec [:snapshot :capability-scope]))))
    (testing "an explicit capability scope is preserved"
      (let [spec (model/form->create-spec
                  state (assoc form :capability-scope #{"mcp:read"}))]
        (is (= #{"mcp:read"} (get-in spec [:snapshot :capability-scope])))))
    (testing "an explicit fixed model is frozen"
      (let [form (assoc form
                        :model-mode :fixed
                        :model {:connection-id "conn-1"
                                :model-entry-id "model-1"
                                :label "Model 1"})
            spec (model/form->create-spec state form)]
        (is (= {:connection-id "conn-1" :model-entry-id "model-1"}
               (get-in spec [:snapshot :model])))))
    (testing "switching back to follow-assistant removes the frozen model"
      (let [form (assoc form
                        :model-mode :follow-assistant
                        :model {:connection-id "conn-1"
                                :model-entry-id "model-1"
                                :label "Model 1"}
                        :snapshot {:model {:connection-id "conn-1"
                                           :model-entry-id "model-1"}})
            spec (model/form->create-spec state form)]
        (is (not (contains? (:snapshot spec) :model)))))
    (testing "iOS never asks the server to enable an automatic schedule"
      (let [ios (synced {:platform :ios})
            form (assoc form :save-mode :enable)
            spec (model/form->create-spec ios form)]
        (is (false? (:enable? spec)))
        (is (= :paused (:state spec)))))))

(deftest catalog-refresh-does-not-override-an-explicit-model-choice
  (let [state (synced)
        form (assoc (model/new-form state) :model nil)
        result (model/reduce-action
                (assoc-in state [:ui :form] form)
                {:type :schedule/set-catalog
                 :catalog {:models [{:connection-id "conn-2"
                                     :model-entry-id "model-2"
                                     :label "Other"}]}})]
    (is (nil? (get-in result [:state :ui :form :model])))))

(deftest save-mode-radio-updates-the-form
  (let [state (assoc-in (synced) [:ui :form]
                        (-> (model/new-form (synced))
                            (assoc :name "Report")
                            (assoc :prompt "Do work")))
        result (model/reduce-action state
                                    {:type :schedule/save-mode
                                     :value "enable"})]
    (is (= :enable (get-in result [:state :ui :form :save-mode])))
    (let [spec (model/form->create-spec (:state result)
                                        (get-in result [:state :ui :form]))]
      (is (true? (:enable? spec)))
      (is (= :draft (:state spec))))))

(deftest redo-and-continue-inherit-the-target-model
  (let [state (synced)
        base (-> (model/new-form state)
                 (assoc :name "Report")
                 (assoc :prompt "Do work")
                 (assoc :model nil)
                 (assoc :save-mode :enable))
        redo (assoc base :execution {:mode :redo-question
                                     :chat-id "source-1"
                                     :message-id "message-1"})
        continue (assoc base :execution {:mode :continue-chat
                                         :chat-id "source-1"})
        new-chat base]
    (testing "redo with both ids can enable without a frozen model"
      (is (true? (:ok? (model/validate-form state redo))))
      (is (false? (:enable-blocked? (model/validate-form state redo))))
      (is (true? (:enable? (model/form->create-spec state redo)))))
    (testing "continue can inherit the target chat model"
      (is (true? (:ok? (model/validate-form state continue))))
      (is (true? (:enable? (model/form->create-spec state continue)))))
    (testing "new-chat follows the assistant default and can enable"
      (is (true? (:ok? (model/validate-form state new-chat))))
      (is (false? (:enable-blocked? (model/validate-form state new-chat))))
      (is (true? (:enable? (model/form->create-spec state new-chat))))
      (is (not (contains? (get-in (model/form->create-spec state new-chat)
                                  [:snapshot])
                          :model))))
    (testing "redo requires both the source chat and message ids"
      (is (contains? (:errors (model/validate-form
                               state (assoc redo :execution {:mode :redo-question
                                                             :message-id "message-1"})))
                     :chat-id))
      (is (contains? (:errors (model/validate-form
                               state (assoc redo :execution {:mode :redo-question
                                                             :chat-id "source-1"})))
                     :message-id)))))

(deftest save-queues-real-server-command
  (let [state (-> (synced)
                  (assoc-in [:ui :form]
                            (-> (model/new-form (synced))
                                (assoc :name "Report")
                                (assoc :prompt "Do work")
                                (assoc :save-mode :paused))))
        result (model/reduce-action state {:type :schedule/save})
        effect (submit-effect result)
        payload (get-in effect [:envelope :payload])]
    (is (= :scheduler/create (:kind payload)))
    (is (= :scheduler/create
           (get-in result [:state :commands (:command-id effect) :kind])))
    (is (string? (get-in payload [:schedule :id])))
    (is (= "Report" (get-in payload [:schedule :name])))
    (is (false? (get-in payload [:schedule :enable?])))
    (is (= :paused (get-in payload [:schedule :state])))))

(deftest disconnect-marks-pending-command-unknown
  (let [state (-> (synced)
                  (assoc-in [:ui :form]
                            (-> (model/new-form (synced))
                                (assoc :name "Report")
                                (assoc :prompt "Do work")
                                (assoc :save-mode :paused))))
        saved (model/reduce-action state {:type :schedule/save})
        command-id (get-in saved [:effects 0 :command-id])
        disconnected (model/reduce-action (:state saved)
                                          {:type :schedule/set-sync
                                           :phase :interrupted})]
    (is (= :outcome-unknown
           (get-in disconnected [:state :commands command-id :status])))
    (is (false? (get-in disconnected [:state :ui :form-submitting?])))
    (is (= :warn (get-in disconnected [:state :ui :notice :tone])))))

(deftest feed-epoch-does-not-authorize-commands
  (let [state (-> (synced)
                  (assoc-in [:sync :command-epoch] nil)
                  (assoc-in [:sync :epoch] 7)
                  (assoc-in [:ui :form]
                            (-> (model/new-form (synced))
                                (assoc :name "Report")
                                (assoc :prompt "Do work")
                                (assoc :save-mode :paused))))
        result (model/reduce-action state {:type :schedule/save})]
    (is (empty? (:effects result)))
    (is (= :warn (get-in result [:state :ui :notice :tone])))))

(deftest unsynced-save-is-refused-without-command
  (let [state (model/initial-state)
        result (model/reduce-action state {:type :schedule/save})]
    (is (empty? (:effects result)))
    (is (= :warn (get-in result [:state :ui :notice :tone])))))

(deftest create-receipt-closes-form-only-on-server-result
  (let [state (-> (synced)
                  (assoc-in [:ui :form]
                            (-> (model/new-form (synced))
                                (assoc :name "Report")
                                (assoc :prompt "Do work")
                                (assoc :save-mode :paused))))
        saved (model/reduce-action state {:type :schedule/save})
        command-id (get-in saved [:effects 0 :command-id])
        created (schedule {:id "schedule-new" :state :paused :next-run nil})
        receipt {:command-id command-id
                 :ok? true
                 :receipt {:status :accepted
                           :result {:ok? true
                                    :outcome :created
                                    :enabled? false
                                    :schedule created
                                    :preview {:zone "Asia/Shanghai"
                                              :times []}}}}
        applied (model/apply-receipt (:state saved) receipt)]
    (is (nil? (get-in applied [:state :ui :form])))
    (is (contains? (get-in applied [:state :server :schedules]) "schedule-new"))
    (is (= "schedule-new" (get-in applied [:state :ui :highlight-id])))
    (is (= :info (get-in applied [:state :ui :notice :tone])))))

(deftest enable-failure-is-reported-as-paused
  (let [state (-> (synced)
                  (assoc-in [:ui :form]
                            (-> (model/new-form (synced))
                                (assoc :name "Report")
                                (assoc :prompt "Do work")
                                (assoc :save-mode :enable))))
        saved (model/reduce-action state {:type :schedule/save})
        command-id (get-in saved [:effects 0 :command-id])
        paused (schedule {:id "schedule-new" :state :paused :next-run nil})
        receipt {:command-id command-id
                 :ok? true
                 :receipt {:status :accepted
                           :result {:ok? true
                                    :outcome :created
                                    :enabled? false
                                    :schedule paused
                                    :enable-error {:code :MODEL_UNAVAILABLE
                                                   :message "the frozen model binding is not configured"}}}}
        applied (model/apply-receipt (:state saved) receipt)]
    (is (= :warn (get-in applied [:state :ui :notice :tone])))
    (is (= "paused" (some-> (get-in applied [:state :server :schedules
                                              "schedule-new" :state]) name)))))

(deftest rejected-receipt-keeps-draft
  (let [state (-> (synced)
                  (assoc-in [:ui :form]
                            (-> (model/new-form (synced))
                                (assoc :name "Report")
                                (assoc :prompt "Do work")
                                (assoc :save-mode :paused))))
        saved (model/reduce-action state {:type :schedule/save})
        command-id (get-in saved [:effects 0 :command-id])
        applied (model/apply-receipt
                 (:state saved)
                 {:command-id command-id
                  :ok? false
                  :error {:code :INVALID_SCHEDULE
                          :message "a schedule needs a non-blank :name"}})]
    (is (some? (get-in applied [:state :ui :form])))
    (is (= :danger (get-in applied [:state :ui :notice :tone])))
    (is (= :failed (get-in applied [:state :commands command-id :status])))))

(deftest pause-receipt-updates-confirmed-state
  (let [state (assoc-in (synced) [:server :schedules]
                        {"schedule-1" (schedule {})})
        queued (model/reduce-action state
                                    {:type :schedule/pause :id "schedule-1"})
        command-id (get-in queued [:effects 0 :command-id])
        paused (schedule {:state :paused})
        applied (model/apply-receipt
                 (:state queued)
                 {:command-id command-id
                  :ok? true
                  :receipt {:status :accepted
                            :result {:ok? true
                                     :outcome :paused
                                     :schedule paused}}})]
    (is (= :paused (get-in applied [:state :server :schedules
                                    "schedule-1" :state])))
    (is (= :ok (get-in applied [:state :ui :notice :tone])))))

(deftest refresh-during-pending-snapshot-is-coalesced
  (let [state (synced)
        first-refresh (model/reduce-action state {:type :schedule/refresh})
        command-id (get-in first-refresh [:effects 0 :command-id])
        second-refresh (model/reduce-action (:state first-refresh)
                                            {:type :schedule/refresh})]
    (is (empty? (:effects second-refresh)))
    (is (true? (get-in second-refresh [:state :ui :refresh-pending?])))
    (let [applied (model/apply-receipt
                   (:state second-refresh)
                   {:command-id command-id
                    :ok? true
                    :receipt {:status :accepted
                              :snapshot {:schedules {}
                                         :runs {}
                                         :run-order []
                                         :device-zone "Asia/Shanghai"
                                         :revision 1}}})]
      (is (false? (get-in applied [:state :ui :refresh-pending?])))
      (is (= :scheduler/snapshot
             (get-in applied [:effects 0 :envelope :payload :kind]))))))

(deftest snapshot-does-not-discard-unsaved-form
  (let [state (-> (synced)
                  (assoc-in [:ui :form]
                            (-> (model/new-form (synced))
                                (assoc :name "Draft" :dirty? true))))
        applied (model/apply-snapshot
                 state
                 {:schedules {"schedule-1" (schedule {})}
                  :runs {}
                  :run-order []
                  :device-zone "Asia/Shanghai"
                  :revision 2
                  :seq 9})]
    (is (= "Draft" (get-in applied [:ui :form :name])))
    (is (true? (get-in applied [:server :loaded?])))
    (is (= 2 (get-in applied [:server :revision])))))

(deftest missed-and-skipped-labels-are-explicit
  (is (= "未运行：应用未运行"
         (model/run-status-label {:status :missed :reason :process-restart})))
  (is (= "已跳过：上次仍在运行"
         (model/run-status-label {:status :skipped :reason :previous-run-active})))
  (is (= "未运行：本地时间不存在（夏令时）"
         (model/run-status-label {:status :missed
                                  :reason :nonexistent-local-time}))))

(deftest notification-label-uses-status-and-reason
  (let [state (assoc-in (synced)
                        [:server :schedules "schedule-1"]
                        (schedule {:name "Morning report"}))
        result (model/reduce-action
                state
                {:type :schedule/notification
                 :notification {:schedule-id "schedule-1"
                                :status :failed
                                :reason :process-restart}})]
    (is (= "定时任务“Morning report”：失败：应用未运行"
           (get-in result [:state :ui :notice :text])))
    (is (= :warn (get-in result [:state :ui :notice :tone])))
    (is (= :refresh (get-in result [:effects 0 :effect])))))

(deftest notification-label-normalizes-strings-and-unknown-reasons
  (let [result (model/reduce-action
                (synced)
                {:type :schedule/notification
                 :notification {:schedule-id "missing"
                                :schedule-name "Nightly"
                                :status "completed"
                                :reason "mystery"}})]
    (is (= "定时任务“Nightly”：已完成：mystery"
           (get-in result [:state :ui :notice :text])))
    (is (= :info (get-in result [:state :ui :notice :tone])))))

(deftest notification-label-tolerates-missing-fields
  (let [result (model/reduce-action
                (synced)
                {:type :schedule/notification
                 :notification {:schedule-id "missing"
                                :schedule-name "Orphan"}})]
    (is (= "定时任务“Orphan”：状态更新"
           (get-in result [:state :ui :notice :text])))
    (is (= :info (get-in result [:state :ui :notice :tone])))))

(deftest groups-sort-active-by-next-run-and-separate-paused
  (let [state (-> (synced)
                  (assoc-in [:server :schedules]
                            {"a" (schedule {:id "a" :name "A" :next-run 300})
                             "b" (schedule {:id "b" :name "B" :next-run 100})
                             "c" (schedule {:id "c" :name "C" :state :paused
                                            :next-run nil})}))
        cards (model/schedule-cards state)]
    (is (= ["b" "a"] (mapv :id (:active cards))))
    (is (= ["c"] (mapv :id (:paused cards))))))

(deftest manual-run-does-not-change-next-run-in-client
  (let [state (-> (synced)
                  (assoc-in [:server :schedules]
                            {"schedule-1" (schedule {:next-run 1793752200000})}))
        result (model/reduce-action state
                                   {:type :schedule/manual-run
                                    :id "schedule-1"})
        payload (get-in (submit-effect result) [:envelope :payload])]
    (is (= :scheduler/manual-run (:kind payload)))
    (is (= "schedule-1" (:schedule-id payload)))
    (is (= 1793752200000
           (get-in result [:state :server :schedules
                           "schedule-1" :next-run])))))

(deftest stop-run-uses-the-result-chat-and-real-owner-command
  (let [run {:id "run-1" :schedule-id "schedule-1" :status :running
             :chat-id "chat-1"}
        state (-> (synced)
                  (assoc-in [:server :schedules]
                            {"schedule-1" (schedule {:active-run-id "run-1"})})
                  (assoc-in [:server :runs] {"run-1" run})
                  (assoc-in [:ui :stop-confirm] {:run-id "run-1"}))
        result (model/reduce-action state {:type :schedule/stop-confirm})
        payload (get-in (submit-effect result) [:envelope :payload])]
    (is (= :stop-run (:kind payload)))
    (is (= "chat-1" (:session-id payload)))
    (is (= :user-cancel (:reason payload)))))

(deftest refused-receipt-is-not-success
  (let [run {:id "run-1" :schedule-id "schedule-1" :status :running
             :chat-id "chat-1"}
        state (-> (synced)
                  (assoc-in [:server :schedules]
                            {"schedule-1" (schedule {:active-run-id "run-1"})})
                  (assoc-in [:server :runs] {"run-1" run})
                  (assoc-in [:ui :stop-confirm] {:run-id "run-1"}))
        queued (model/reduce-action state {:type :schedule/stop-confirm})
        command-id (get-in queued [:effects 0 :command-id])
        applied (model/apply-receipt
                 (:state queued)
                 {:command-id command-id
                  :ok? true
                  :receipt {:status :refused
                            :message "the source chat has no live Owner"}})]
    (is (= :danger (get-in applied [:state :ui :notice :tone])))
    (is (= :failed (get-in applied [:state :commands command-id :status])))
    (is (not (str/includes? (get-in applied [:state :ui :notice :text])
                            "已请求停止")))))

(deftest unknown-receipt-is-not-success-or-failure
  (let [run {:id "run-1" :schedule-id "schedule-1" :status :running
             :chat-id "chat-1"}
        state (-> (synced)
                  (assoc-in [:server :schedules]
                            {"schedule-1" (schedule {:active-run-id "run-1"})})
                  (assoc-in [:server :runs] {"run-1" run})
                  (assoc-in [:ui :stop-confirm] {:run-id "run-1"}))
        queued (model/reduce-action state {:type :schedule/stop-confirm})
        command-id (get-in queued [:effects 0 :command-id])
        applied (model/apply-receipt
                 (:state queued)
                 {:command-id command-id
                  :ok? true
                  :receipt {:status :unknown}})]
    (is (= :warn (get-in applied [:state :ui :notice :tone])))
    (is (= :outcome-unknown
           (get-in applied [:state :commands command-id :status])))
    (is (= :refresh (get-in applied [:effects 0 :effect])))))

(deftest mutation-without-schedule-is-not-success
  (let [state (assoc-in (synced) [:server :schedules]
                        {"schedule-1" (schedule {})})
        queued (model/reduce-action state
                                    {:type :schedule/enable :id "schedule-1"})
        command-id (get-in queued [:effects 0 :command-id])
        applied (model/apply-receipt
                 (:state queued)
                 {:command-id command-id
                  :ok? true
                  :receipt {:status :accepted
                            :result {:ok? true :outcome :enabled}}})]
    (is (= :warn (get-in applied [:state :ui :notice :tone])))
    (is (= :scheduler/snapshot
           (get-in applied [:effects 0 :envelope :payload :kind])))))

(deftest device-zone-receipt-updates-follow-device-schedule-immediately
  (let [state (-> (synced)
                  (assoc-in [:server :loaded?] true)
                  (assoc-in [:server :device-zone] "Asia/Shanghai")
                  (assoc-in [:ui :browser-zone] "Asia/Shanghai")
                  (assoc-in [:server :schedules]
                            {"schedule-1"
                             (schedule {:timezone {:mode :follow-device
                                                   :zone "Asia/Shanghai"}
                                        :next-run 1000})}))
        queued (model/reduce-action state
                                    {:type :schedule/set-browser-zone
                                     :zone "Europe/Paris"})
        command-id (get-in queued [:effects 0 :command-id])
        applied (model/apply-receipt
                 (:state queued)
                 {:command-id command-id
                  :ok? true
                  :receipt {:status :accepted
                            :result {:ok? true
                                     :device-zone "Europe/Paris"
                                     :changes [{:schedule-id "schedule-1"
                                                :old-zone "Asia/Shanghai"
                                                :new-zone "Europe/Paris"
                                                :old-next-run 1000
                                                :new-next-run 2000}]
                                     :missed []}}})]
    (is (= "Europe/Paris"
           (get-in applied [:state :server :schedules
                            "schedule-1" :timezone :zone])))
    (is (= 2000 (get-in applied [:state :server :schedules
                                 "schedule-1" :next-run])))
    (is (false? (get-in applied [:state :ui :zone-sync-pending?])))
    (is (= :info (get-in applied [:state :ui :notice :tone])))))

(deftest delete-receipt-removes-schedule-and-keeps-runs-by-default
  (let [state (-> (synced)
                  (assoc-in [:server :schedules]
                            {"schedule-1" (schedule {})})
                  (assoc-in [:server :runs]
                            {"run-1" {:id "run-1" :schedule-id "schedule-1"
                                      :status :completed}}))
        command (get-in (model/reduce-action
                         state
                         {:type :schedule/delete-request :id "schedule-1"})
                        [:state :ui :delete-confirm])
        state (assoc-in state [:ui :delete-confirm] command)
        result (model/reduce-action state {:type :schedule/delete-confirm})
        command-id (get-in result [:effects 0 :command-id])
        applied (model/apply-receipt
                 (:state result)
                 {:command-id command-id
                  :ok? true
                  :receipt {:status :accepted
                            :result {:ok? true
                                     :outcome :deleted
                                     :schedule (schedule {})}}})]
    (is (empty? (get-in applied [:state :server :schedules])))
    (is (contains? (get-in applied [:state :server :runs]) "run-1"))))

(deftest missing-schedule-receipt-clears-submitting-and-keeps-form
  (let [state (-> (synced)
                  (assoc-in [:ui :form]
                            (-> (model/new-form (synced))
                                (assoc :name "Report")
                                (assoc :prompt "Do work")
                                (assoc :save-mode :paused))))
        saved (model/reduce-action state {:type :schedule/save})
        command-id (get-in saved [:effects 0 :command-id])
        applied (model/apply-receipt
                 (:state saved)
                 {:command-id command-id
                  :ok? true
                  :receipt {:status :accepted
                            :result {:ok? true :outcome :created}}})]
    (is (some? (get-in applied [:state :ui :form])))
    (is (false? (get-in applied [:state :ui :form-submitting?])))
    (is (= :warn (get-in applied [:state :ui :notice :tone])))
    (is (= :scheduler/snapshot
           (get-in applied [:effects 0 :envelope :payload :kind]))))
  (testing "update without a schedule also releases the form"
    (let [base (assoc-in (synced) [:server :schedules]
                         {"schedule-1" (schedule {})})
          form (model/form-from-schedule base (schedule {}))
          saved (model/reduce-action (assoc-in base [:ui :form] form)
                                     {:type :schedule/save})
          command-id (get-in saved [:effects 0 :command-id])
          applied (model/apply-receipt
                   (:state saved)
                   {:command-id command-id
                    :ok? true
                    :receipt {:status :accepted
                              :result {:ok? true :outcome :updated}}})]
      (is (some? (get-in applied [:state :ui :form])))
      (is (false? (get-in applied [:state :ui :form-submitting?])))
      (is (= :scheduler/snapshot
             (get-in applied [:effects 0 :envelope :payload :kind]))))))

(deftest catalog-normalizes-assistants-and-session-targets
  (let [state (model/initial-state
               {:catalog
                {:assistants [{:id "a1" :name "写作助手"}
                              {:assistant-id "a2" :title "分析助手"}]
                 :sessions [{:id "chat-1"
                             :title "标题"
                             :assistant-name "写作助手"
                             :last-message {:text "最后一句"}
                             :messages [{:id "m1" :role "user" :content "问题"}
                                        {:id "m2" :role :assistant
                                         :versions [{:content "回答"}]}]}]}})]
    (is (= [{:id "a1" :name "写作助手"}
            {:id "a2" :name "分析助手"}]
           (get-in state [:catalog :assistants])))
    (let [session (model/catalog-session state "chat-1")]
      (is (= "写作助手" (:assistant-name session)))
      (is (= "最后一句" (model/message-text (:last-message session))))
      (is (= ["m1" "m2"] (mapv :id (:messages session))))
      (is (= :user (get-in session [:messages 0 :role])))
      (is (= "回答"
             (model/message-text (model/session-message session "m2")))))))

(deftest create-spec-carries-assistant-when-chosen
  (let [state (synced)
        form (-> (model/new-form state)
                 (assoc :name "Report")
                 (assoc :prompt "Do work")
                 (assoc :assistant-id "assistant-1"))
        spec (model/form->create-spec state form)
        changes (model/form->changes state form)]
    (is (= "assistant-1" (:assistant-id spec)))
    (is (= "assistant-1" (get-in spec [:snapshot :assistant-id])))
    (is (= "assistant-1" (:assistant-id changes)))
    (testing "an absent assistant is not sent as nil"
      (let [spec (model/form->create-spec
                  state (assoc form :assistant-id nil))]
        (is (not (contains? spec :assistant-id)))))))

(deftest assistant-and-model-labels-are-honest
  (let [state (model/initial-state
               {:catalog {:assistants [{:id "a1" :name "写作助手"}]}})]
    (is (= "写作助手"
           (model/assistant-label state {:assistant-id "a1"})))
    (is (= "missing"
           (model/assistant-label state {:assistant-id "missing"})))
    (is (= "未指定助手" (model/assistant-label state {})))
    (is (= "跟随助手默认"
           (model/model-label state (schedule
                                     {:snapshot {:execution {:mode :new-chat}}}))))
    (is (= "继承目标会话模型"
           (model/model-label
            state
            (schedule {:execution {:mode :continue-chat
                                   :chat-id "chat-1"}
                       :snapshot {:execution {:mode :continue-chat
                                              :chat-id "chat-1"}}}))))
    (is (= "继承源会话模型"
           (model/model-label
            state
            (schedule {:execution {:mode :redo-question
                                   :chat-id "chat-1"
                                   :message-id "m1"}
                       :snapshot {:execution {:mode :redo-question
                                              :chat-id "chat-1"
                                              :message-id "m1"}}}))))))

(deftest choosing-a-model-fixes-it-and-follow-clears-it
  (let [state (assoc-in (synced) [:ui :form] (model/new-form (synced)))
        fixed (model/reduce-action
               state
               {:type :schedule/change
                :path [:model]
                :value "conn-1|model-1"})
        follow (model/reduce-action
                (:state fixed)
                {:type :schedule/change
                 :path [:model]
                 :value ""})]
    (is (= :fixed (get-in fixed [:state :ui :form :model-mode])))
    (is (= "model-1"
           (get-in fixed [:state :ui :form :model :model-entry-id])))
    (is (= :follow-assistant
           (get-in follow [:state :ui :form :model-mode])))
    (is (nil? (get-in follow [:state :ui :form :model])))))

(deftest manual-run-requires-confirmation
  (let [state (assoc-in (synced) [:server :schedules]
                        {"schedule-1" (schedule {})})
        requested (model/reduce-action
                   state
                   {:type :schedule/request-manual-run :id "schedule-1"})
        confirm (get-in requested [:state :ui :manual-confirm])]
    (is (empty? (:effects requested)))
    (is (= "schedule-1" (:schedule-id confirm)))
    (is (false? (:retry? confirm)))
    (is (= "Morning report" (get-in confirm [:summary :name])))
    (is (string? (get-in confirm [:summary :model])))
    (is (string? (get-in confirm [:summary :execution])))
    (is (string? (get-in confirm [:summary :destination])))
    (is (= 1793752200000 (get-in confirm [:summary :next-run])))
    (let [confirmed (model/reduce-action
                     (:state requested)
                     {:type :schedule/confirm-manual-run})
          payload (get-in (submit-effect confirmed) [:envelope :payload])]
      (is (= :scheduler/manual-run (:kind payload)))
      (is (= "schedule-1" (:schedule-id payload)))
      (is (= {} (:opts payload)))
      (is (nil? (get-in confirmed [:state :ui :manual-confirm]))))
    (testing "cancel sends nothing"
      (let [cancelled (model/reduce-action
                       (:state requested)
                       {:type :schedule/manual-run-cancel})]
        (is (nil? (get-in cancelled [:state :ui :manual-confirm])))
        (is (empty? (:effects cancelled)))))))

(deftest failed-run-retry-is-marked-as-a-new-attempt
  (let [run {:id "run-failed" :schedule-id "schedule-1"
             :status :failed :attempt 1}
        state (-> (synced)
                  (assoc-in [:server :schedules]
                            {"schedule-1" (schedule {})})
                  (assoc-in [:server :runs] {"run-failed" run}))
        requested (model/reduce-action
                   state
                   {:type :schedule/request-manual-run
                    :id "schedule-1"
                    :retry? true
                    :run-id "run-failed"})
        confirm (get-in requested [:state :ui :manual-confirm])]
    (is (true? (:retry? confirm)))
    (is (= 2 (:attempt confirm)))
    (is (empty? (:effects requested)))
    (let [confirmed (model/reduce-action
                     (:state requested)
                     {:type :schedule/confirm-manual-run})
          payload (get-in (submit-effect confirmed) [:envelope :payload])]
      (is (= :scheduler/manual-run (:kind payload)))
      (is (= {:attempt 2} (:opts payload)))
      (is (= :info (get-in confirmed [:state :ui :notice :tone]))))))

(deftest enable-summary-labels-cover-scope-and-destination
  (is (= "未声明" (model/tool-scope-label {})))
  (is (= "只读工具"
         (model/tool-scope-label {:tool-scope "只读工具"})))
  (is (= "capability"
         (model/tool-scope-label
          {:snapshot {:capability-scope "capability"}})))
  (is (str/includes? (model/result-destination-label {:mode :new-chat})
                     "新对话"))
  (is (str/includes? (model/result-destination-label
                      {:mode :continue-chat :chat-id "chat-1"})
                     "目标会话"))
  (is (str/includes? (model/result-destination-label
                      {:mode :redo-question :redo-mode :branch})
                     "分支"))
  (is (str/includes? (model/result-destination-label
                      {:mode :redo-question :redo-mode :append-version})
                     "新版本")))

(deftest unknown-platform-falls-back-to-the-honest-unknown-policy
  (doseq [kind [:toaster "toaster" nil]]
    (let [policy (model/platform-policy kind)]
      (is (= :unknown (:kind policy)))
      (is (not (str/blank? (:label policy))))
      (is (not (str/blank? (:notice policy)))))))

(deftest scheduler-action-text-is-total
  (doseq [[kind expected]
          [[:scheduler/enable "已启用"]
           [:scheduler/pause "已暂停"]
           [:scheduler/resume "已恢复"]
           [:scheduler/repair "已修复"]
           [:future-kind "未识别"]
           [nil "未识别"]]]
    (let [text (#'model/scheduler-action-text kind)]
      (is (string? text) (str kind))
      (is (not (str/blank? text)) (str kind))
      (is (str/includes? text expected) (str kind)))))
