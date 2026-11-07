(ns hc.client.schedule.fixture
  "Shared schedule fixture for projection and render tests."
  (:require [hc.client.schedule.model :as model]))

(def now 1793752200000)                    ; 2026-11-04T01:30Z

(defn schedule
  [overrides]
  (merge {:id "schedule-daily"
          :name "每日摘要"
          :prompt "总结今天的会话与待办。"
          :snapshot {:prompt "总结今天的会话与待办。"
                     :execution {:mode :new-chat}
                     :assistant-id "assistant-1"
                     :model {:connection-id "conn-1"
                             :model-entry-id "model-1"}}
          :assistant-id "assistant-1"
          :execution {:mode :new-chat}
          :recurrence {:kind :daily :time {:hour 8 :minute 30}}
          :timezone {:mode :fixed :zone "Asia/Shanghai"}
          :state :enabled
          :next-run 1793755800000
          :revision 3
          :attention nil}
         overrides))

(def runs
  {"run-1" {:id "run-1"
            :schedule-id "schedule-daily"
            :trigger :scheduled
            :status :completed
            :scheduled-at 1793676600000
            :scheduled-local "2026-11-03T08:30"
            :scheduled-zone "Asia/Shanghai"
            :started-at 1793676605000
            :started-local "2026-11-03T08:30"
            :ended-at 1793676660000
            :ended-local "2026-11-03T08:31"
            :chat-id "chat-1"
            :result-files ["report.md"]}
   "run-2" {:id "run-2"
            :schedule-id "schedule-daily"
            :trigger :scheduled
            :status :missed
            :reason :process-restart
            :scheduled-at 1793755800000
            :scheduled-local "2026-11-04T08:30"
            :scheduled-zone "Asia/Shanghai"}
   "run-3" {:id "run-3"
            :schedule-id "schedule-active"
            :trigger :scheduled
            :status :running
            :scheduled-at 1793759400000
            :scheduled-local "2026-11-04T09:30"
            :scheduled-zone "Asia/Shanghai"
            :started-at 1793759401000
            :started-local "2026-11-04T09:30"
            :chat-id "chat-2"}
   "run-4" {:id "run-4"
            :schedule-id "schedule-active"
            :trigger :scheduled
            :status :skipped
            :reason :previous-run-active
            :scheduled-at 1793763000000
            :scheduled-local "2026-11-04T10:30"
            :scheduled-zone "Asia/Shanghai"}})

(defn base-state
  ([] (base-state {}))
  ([overrides]
   (merge
    (model/initial-state
     {:platform :desktop
      :device-zone "Asia/Shanghai"
      :sync {:phase :synced :command-epoch 4}
      :now now
      :catalog {:models [{:connection-id "conn-1"
                          :connection-name "本地网关"
                          :model-entry-id "model-1"
                          :label "主力模型"}]
                :roles {:main {:connection-id "conn-1"
                               :model-id "model-1"}}
                :assistants [{:id "assistant-1" :name "写作助手"}
                             {:id "assistant-2" :name "分析助手"}]
                :sessions [{:id "chat-1"
                            :title "昨天的摘要"
                            :assistant-name "写作助手"
                            :last-message {:text "昨天的摘要已经生成。"}
                            :messages [{:id "message-user-1"
                                        :role :user
                                        :text "昨天的重点是什么？"}
                                       {:id "message-assistant-1"
                                        :role :assistant
                                        :text "昨天的重点是完成审核。"}]}]}})
    {:server {:schedules {"schedule-daily" (schedule {})
                          "schedule-paused" (schedule
                                             {:id "schedule-paused"
                                              :name "每周复盘"
                                              :state :paused
                                              :next-run nil
                                              :recurrence {:kind :weekly
                                                           :days [1 3]
                                                           :time {:hour 18 :minute 0}}
                                              :timezone {:mode :follow-device
                                                         :zone "Asia/Shanghai"}})
                          "schedule-active" (schedule
                                             {:id "schedule-active"
                                              :name "长任务"
                                              :state :enabled
                                              :active-run-id "run-3"
                                              :next-run 1793763000000})
                          "schedule-attention" (schedule
                                                {:id "schedule-attention"
                                                 :name "需要登录的任务"
                                                 :state :needs-attention
                                                 :next-run 1793763000000
                                                 :attention [{:code :MISSING_CREDENTIAL
                                                              :message "目标模型未配置凭据"}]})}
     :runs runs
     :run-order ["run-1" "run-2" "run-3" "run-4"]
     :device-zone "Asia/Shanghai"
     :revision 5
     :loaded? true
     :load-error nil}}
    overrides)))

(defn list-state
  ([] (list-state {}))
  ([overrides]
   (base-state overrides)))

(defn detail-state
  [schedule-id]
  (-> (base-state)
      (assoc-in [:ui :view] :detail)
      (assoc-in [:ui :selected-id] schedule-id)))

(defn form-state
  ([] (form-state {}))
  ([overrides]
   (let [state (base-state)
         form {:id nil
               :mode :create
               :name "新任务"
               :prompt "写一份摘要。"
               :assistant-id "assistant-1"
               :execution {:mode :new-chat}
               :recurrence {:kind :weekly
                            :days [1 3]
                            :time {:hour 8 :minute 30}}
               :timezone {:mode :fixed :zone "Asia/Shanghai"}
               :model-mode :fixed
               :model {:connection-id "conn-1"
                       :model-entry-id "model-1"
                       :label "主力模型"}
               :save-mode :enable
               :dirty? true}]
     (-> state
         (assoc-in [:ui :view] :form)
         (assoc-in [:ui :form] (merge form overrides))))))

(def ios-state
  (-> (list-state)
      (assoc :platform (model/platform-policy :ios))))

(def ios-form-state
  (-> (form-state)
      (assoc :platform (model/platform-policy :ios))))
