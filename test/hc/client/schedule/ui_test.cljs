(ns hc.client.schedule.ui-test
  "ClojureScript render tests for the real React schedule tree.

  The tests render the documented `SchedulePage` component and the pure tree
  with `react-dom/server`.  They check the user-visible honesty rules, not
  source strings."
  (:require ["react" :as react]
            ["react-dom/server" :as rds]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.schedule :as schedule]
            [hc.client.schedule.fixture :as fixture]
            [hc.client.schedule.model :as model]
            [hc.client.schedule.react :as schedule-react]
            [hc.client.schedule.view :as view]))

(defn- html
  [state]
  (let [tree (try
               (view/schedule-tree state)
               (catch :default error
                 (println "TREE-ERROR" (.-stack error))
                 (throw error)))]
    (rds/renderToStaticMarkup
     (schedule-react/render-node tree identity))))

(defn- component-html
  [state]
  (rds/renderToStaticMarkup
   (schedule-react/schedule-element state identity)))

(defn- tree-nodes
  [node]
  (cond
    (and (map? node) (:tag node))
    (cons node (mapcat tree-nodes (:children node)))

    (sequential? node)
    (mapcat tree-nodes node)

    :else []))

(deftest list-renders-real-schedule-facts
  (let [markup (html (fixture/list-state))]
    (testing "the workspace and both groups are present"
      (is (str/includes? markup "定时任务"))
      (is (str/includes? markup "每日摘要"))
      (is (str/includes? markup "每周复盘"))
      (is (str/includes? markup "活动任务"))
      (is (str/includes? markup "已暂停")))
    (testing "times always carry a zone"
      (is (str/includes? markup "Asia/Shanghai"))
      (is (str/includes? markup "固定时区"))
      (is (str/includes? markup "跟随设备时区")))
    (testing "the assistant name is visible without opening the prompt"
      (is (str/includes? markup "助手"))
      (is (str/includes? markup "写作助手")))
    (testing "missed and skipped facts are explicit"
      (is (str/includes? markup "错过的计划记为“未运行”"))
      (is (str/includes? markup "未运行：应用未运行"))
      (is (str/includes? markup "已跳过：上次仍在运行")))
    (testing "the list does not expose the full prompt"
      (is (not (str/includes? markup "总结今天的会话与待办。"))))
    (testing "a running item keeps a separate stop action"
      (is (str/includes? markup "当前运行"))
      (is (str/includes? markup "停止当前运行")))
    (testing "the toolbar keeps exactly one new-task action"
      (is (= 1 (count (re-seq #"新建任务" markup)))))))

(deftest detail-shows-prompt-runs-and-stop
  (let [markup (html (fixture/detail-state "schedule-active"))]
    (is (str/includes? markup "长任务"))
    (is (str/includes? markup "总结今天的会话与待办。"))
    (is (str/includes? markup "提示词（点击展开）"))
    (is (str/includes? markup "运行记录"))
    (is (str/includes? markup "打开结果对话"))
    (is (str/includes? markup "停止当前运行"))
    (is (str/includes? markup "暂停只阻止未来开始"))
    (is (str/includes? markup "写作助手"))))

(deftest attention-is-not-a-fake-success
  (let [markup (html (fixture/detail-state "schedule-attention"))]
    (is (str/includes? markup "需要处理"))
    (is (str/includes? markup "目标模型未配置凭据"))
    (is (str/includes? markup "修复并启用"))))

(deftest form-preview-updates-with-recurrence
  (let [monday (-> (fixture/form-state)
                   (assoc-in [:ui :form :recurrence]
                             {:kind :weekly
                              :days [1 3]
                              :time {:hour 8 :minute 30}}))
        daily (-> (fixture/form-state)
                  (assoc-in [:ui :form :recurrence]
                            {:kind :daily
                             :time {:hour 7 :minute 15}}))
        monday-markup (html monday)
        daily-markup (html daily)]
    (testing "the weekly preview shows real dates and the zone"
      (is (str/includes? monday-markup "预览"))
      (is (str/includes? monday-markup "2026-11-09 08:30"))
      (is (str/includes? monday-markup "Asia/Shanghai"))
      (is (str/includes? monday-markup "每周一、三 08:30")))
    (testing "editing the rule updates the preview without saving"
      (is (str/includes? daily-markup "每天 07:15"))
      (is (str/includes? daily-markup "2026-11-05 07:15"))
      (is (not (str/includes? daily-markup "2026-11-09 08:30"))))
    (testing "the unsaved marker is visible"
      (is (str/includes? monday-markup "未保存的修改")))))

(deftest ios-is-honest-and-never-promises-background-wakeups
  (let [list-markup (html fixture/ios-state)
        form-markup (html fixture/ios-form-state)]
    (testing "the platform limitation is visible"
      (is (str/includes? list-markup "仅支持前台手动运行"))
      (is (str/includes? form-markup "仅支持前台手动运行"))
      (is (str/includes? form-markup "保存为暂停")))
    (testing "no background promise is rendered"
      (is (not (str/includes? form-markup "已保证将在后台执行")))
      (is (not (str/includes? form-markup "绝不漏跑"))))
    (testing "the enable radio itself is disabled"
      (let [nodes (tree-nodes (view/schedule-tree fixture/ios-form-state))
            input-value (fn [value]
                          (some #(when (and (= :input (:tag %))
                                            (= value (get-in % [:attrs :value])))
                                   %)
                                nodes))
            enable-radio (input-value "enable")
            paused-radio (input-value "paused")]
        (is (some? enable-radio))
        (is (= "true" (get-in enable-radio [:attrs :disabled])))
        (is (some? paused-radio))
        (is (not= "true" (get-in paused-radio [:attrs :disabled])))))
    (testing "automatic resume/enable actions are not offered"
      (is (not (str/includes? list-markup ">恢复<")))
      (is (not (str/includes? list-markup "修复并启用"))))))

(deftest feed-epoch-does-not-enable-the-command-banner
  (let [state (-> (fixture/list-state)
                  (assoc-in [:sync :command-epoch] nil)
                  (assoc-in [:sync :epoch] 9))
        markup (html state)]
    (is (str/includes? markup "命令基线尚未取得"))
    (is (not (str/includes? markup "调度器命令通道可用")))))

(deftest empty-state-example-only-fills-the-form
  (let [markup (html (assoc-in (fixture/base-state) [:server :schedules] {}))]
    (is (str/includes? markup "尚无定时任务"))
    (is (str/includes? markup "用示例填入表单"))
    (is (str/includes? markup "点击示例只会填入表单，不会替你开启任务。"))
    (testing "the empty state owns the only primary new-task action"
      (is (= 1 (count (re-seq #"新建任务" markup)))))))

(deftest edit-form-hides-save-mode
  (let [base (fixture/detail-state "schedule-daily")
        form (model/form-from-schedule base
                                       (get-in base [:server :schedules
                                                     "schedule-daily"]))
        state (-> base
                  (assoc-in [:ui :view] :form)
                  (assoc-in [:ui :form] form))
        markup (html state)]
    (is (not (str/includes? markup "保存方式")))
    (is (str/includes? markup "保存修改"))))

(deftest delete-confirm-keeps-runs-by-default
  (let [state (-> (fixture/detail-state "schedule-daily")
                  (assoc-in [:ui :delete-confirm]
                            {:id "schedule-daily"
                             :delete-runs? false
                             :force? false}))
        markup (html state)]
    (is (str/includes? markup "确认删除"))
    (is (str/includes? markup "同时删除运行记录（默认保留）"))
    (is (str/includes? markup "已有对话与结果文件默认保留"))))

(deftest form-preview-shows-tool-scope-and-result-destination
  (let [continue-markup (html (-> (fixture/form-state
                                   {:execution {:mode :continue-chat
                                                :chat-id "chat-1"}})
                                  (assoc-in [:ui :form :tool-scope] "只读工具")))
        new-chat-markup (html (fixture/form-state))
        redo-markup (html (-> (fixture/form-state
                               {:execution {:mode :redo-question
                                            :redo-mode :append-version
                                            :chat-id "chat-1"
                                            :message-id "message-user-1"}})
                             (assoc-in [:ui :form :tool-scope] "只读工具")))]
    (testing "the enable summary names the tool ceiling and destination"
      (is (str/includes? continue-markup "工具范围"))
      (is (str/includes? continue-markup "只读工具"))
      (is (str/includes? continue-markup "结果去向"))
      (is (str/includes? continue-markup "目标会话 chat-1")))
    (testing "an undeclared ceiling is not invented"
      (is (str/includes? new-chat-markup "未声明"))
      (is (str/includes? new-chat-markup "新对话")))
    (testing "redo names the branch or version destination"
      (is (str/includes? redo-markup "新版本")))))

(deftest form-shows-assistant-choice-or-an-honest-absence
  (let [markup (html (fixture/form-state))]
    (is (str/includes? markup "写作助手"))
    (is (str/includes? markup "分析助手")))
  (let [markup (html (-> (fixture/form-state)
                         (assoc-in [:catalog :assistants] [])))]
    (is (str/includes? markup "未提供助手列表"))))

(deftest continue-and-redo-show-target-context
  (let [continue-markup (html (fixture/form-state
                               {:execution {:mode :continue-chat
                                            :chat-id "chat-1"}}))
        redo-markup (html (fixture/form-state
                           {:execution {:mode :redo-question
                                        :redo-mode :branch
                                        :chat-id "chat-1"
                                        :message-id "message-user-1"}}))]
    (testing "continue shows title, assistant and last message"
      (is (str/includes? continue-markup "昨天的摘要"))
      (is (str/includes? continue-markup "助手 写作助手"))
      (is (str/includes? continue-markup "最后：昨天的摘要已经生成。")))
    (testing "redo selects a real message and shows its text"
      (is (str/includes? redo-markup "原消息"))
      (is (str/includes? redo-markup "用户 · 昨天的重点是什么？"))
      (is (str/includes? redo-markup "所选消息：昨天的重点是什么？")))))

(deftest detail-shows-the-redo-question
  (let [state (-> (fixture/detail-state "schedule-daily")
                  (assoc-in [:server :schedules "schedule-daily" :execution]
                            {:mode :redo-question
                             :redo-mode :branch
                             :chat-id "chat-1"
                             :message-id "message-user-1"})
                  (assoc-in [:server :schedules
                             "schedule-daily" :snapshot :execution]
                            {:mode :redo-question
                             :redo-mode :branch
                             :chat-id "chat-1"
                             :message-id "message-user-1"}))
        markup (html state)]
    (is (str/includes? markup "原问题"))
    (is (str/includes? markup "昨天的重点是什么？"))))

(deftest redo-without-catalog-messages-keeps-the-text-input
  (let [state (-> (fixture/form-state
                   {:execution {:mode :redo-question
                                :redo-mode :branch
                                :chat-id "chat-1"
                                :message-id "message-user-1"}})
                  (assoc-in [:catalog :sessions]
                            [{:id "chat-1" :title "昨天的摘要"}]))
        markup (html state)]
    (is (str/includes? markup "原用户问题标识"))
    (is (str/includes? markup "message-user-1"))))

(deftest manual-run-confirmation-summarises-the-run
  (let [state (-> (fixture/list-state)
                  (assoc-in [:ui :manual-confirm]
                            {:schedule-id "schedule-daily"
                             :retry? false
                             :summary {:name "每日摘要"
                                       :model "本地网关 / 主力模型"
                                       :execution "新建对话"
                                       :destination "新对话"
                                       :next-run 1793755800000
                                       :zone "Asia/Shanghai"}}))
        markup (html state)]
    (is (str/includes? markup "立即运行任务“每日摘要”"))
    (is (str/includes? markup "本地网关 / 主力模型"))
    (is (str/includes? markup "新建对话"))
    (is (str/includes? markup "新对话"))
    (is (str/includes? markup "下次时间"))
    (is (str/includes? markup "Asia/Shanghai"))
    (is (str/includes? markup "确认立即运行"))
    (is (str/includes? markup "取消"))))

(deftest retry-confirmation-marks-a-new-attempt
  (let [state (-> (fixture/list-state)
                  (assoc-in [:ui :manual-confirm]
                            {:schedule-id "schedule-daily"
                             :retry? true
                             :attempt 2
                             :summary {:name "每日摘要"
                                       :model "本地网关 / 主力模型"
                                       :execution "新建对话"
                                       :destination "新对话"
                                       :next-run 1793755800000
                                       :zone "Asia/Shanghai"}}))
        markup (html state)]
    (is (str/includes? markup "新的尝试"))
    (is (str/includes? markup "第 2 次"))
    (is (str/includes? markup "确认重试"))))

(deftest failed-run-offers-a-retry
  (let [state (-> (fixture/detail-state "schedule-daily")
                  (assoc-in [:server :runs "run-failed"]
                            {:id "run-failed"
                             :schedule-id "schedule-daily"
                             :status :failed
                             :attempt 1
                             :scheduled-at 1793766600000
                             :scheduled-local "2026-11-04T11:30"
                             :scheduled-zone "Asia/Shanghai"})
                  (update-in [:server :run-order] conj "run-failed"))
        markup (html state)]
    (is (str/includes? markup "失败"))
    (is (str/includes? markup "重试"))))

(deftest documented-component-renders-the-same-tree
  (let [markup (component-html (fixture/list-state))]
    (is (str/includes? markup "定时任务"))
    (is (str/includes? markup "未运行：应用未运行"))
    (is (str/includes? markup "aria-label=\"定时任务\""))))

(deftest public-facade-projects-cards-and-preview
  (let [cards (schedule/schedule-cards (fixture/list-state))]
    (is (= ["schedule-daily" "schedule-active" "schedule-attention"]
           (mapv :id (:active cards))))
    (is (= ["schedule-paused"] (mapv :id (:paused cards)))))
  (let [result (schedule/preview {:kind :daily :time {:hour 8 :minute 30}}
                                 {:mode :fixed :zone "Asia/Shanghai"}
                                 fixture/now
                                 {:count 2})]
    (is (true? (:ok? result)))
    (is (= 2 (count (get-in result [:preview :times]))))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.schedule.ui-test))

(set! *main-cli-fn* -main)
