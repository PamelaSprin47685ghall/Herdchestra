(ns hc.client.swarm.ui-test
  "ClojureScript render tests for the real React/Framework7 dashboard.

  Run with the shadow-cljs :swarm-test build (see src/hc/client/swarm/AGENTS.md)
  or any CLJS node-test runner that can resolve the npm dependencies."
  (:require ["react" :as react]
            ["react-dom/server" :as rds]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.swarm.fixture :as fixture]
            [hc.client.swarm.model :as model]
            [hc.client.swarm.ui :as ui]))

(defn- render
  [dashboard]
  (rds/renderToStaticMarkup (ui/dashboard-element dashboard)))

(defn- html
  []
  (render (model/dashboard-from-db fixture/fixture)))

(deftest fleet-overview-renders
  (let [markup (html)]
    (testing "fleet and worktree identity are visible"
      (is (str/includes? markup "Swarm 总览"))
      (is (str/includes? markup "auth-core"))
      (is (str/includes? markup "ui-shell"))
      (is (str/includes? markup "/worktrees/mission/swarm-1"))
      (is (str/includes? markup "运行中"))
      (is (str/includes? markup "已合入")))
    (testing "root counts and master version are visible"
      (is (str/includes? markup "2 个就绪"))
      (is (str/includes? markup "1 个等待"))
      (is (str/includes? markup "Master 版本")))
    (testing "the root is a labelled, live status region"
      (is (str/includes? markup "aria-label=\"Swarm fleet 看板\""))
      (is (str/includes? markup "role=\"status\""))
      (is (str/includes? markup "aria-live=\"polite\""))
      (is (str/includes? markup "<section")))
    (testing "the selected swarm is open and marked"
      (is (str/includes? markup "data-selected=\"true\""))
      (is (str/includes? markup "autofocus")))))

(deftest task-tree-and-waits-render
  (let [markup (html)]
    (testing "task goals, remaining work, and done-when are visible"
      (is (str/includes? markup "Integrate authentication core"))
      (is (str/includes? markup "Wire the session owner and run the integration test"))
      (is (str/includes? markup "auth integration test passes"))
      (is (str/includes? markup "Implement token parsing")))
    (testing "a released wait is distinct from a done parent"
      (is (str/includes? markup "等待已解除"))
      (is (str/includes? markup "不会自动标记完成"))
      (is (str/includes? markup "已完成 — 显式完成")))
    (testing "the concrete wait chain is visible"
      (is (str/includes? markup "Wait for parser"))
      (is (str/includes? markup "Deep dependency"))
      (is (str/includes? markup "等待链")))
    (testing "native disclosure controls provide keyboard access"
      (is (str/includes? markup "<details"))
      (is (str/includes? markup "<summary")))))

(deftest slots-holder-and-w-render
  (let [markup (html)]
    (testing "fixed slots and current actors are visible"
      (is (str/includes? markup "slot-a"))
      (is (str/includes? markup "slot-b"))
      (is (str/includes? markup "slot-c"))
      (is (str/includes? markup "agent-holder"))
      (is (str/includes? markup "agent-worker"))
      (is (str/includes? markup "令牌持有者")))
    (testing "normal token rotation is not presented as a crash"
      (is (str/includes? markup "令牌竞争落败"))
      (is (str/includes? markup "正常的生命周期轮换"))
      (is (str/includes? markup "已终止 — 失败")))
    (testing "the holder responsibility set and W are distinguished"
      (is (str/includes? markup "R(holder)"))
      (is (str/includes? markup "action-shell"))
      (is (str/includes? markup "工作区写入口 W"))
      (is (str/includes? markup "托管 shell"))
      (is (str/includes? markup "action-edit"))
      (is (str/includes? markup "个写者在 W 的 FIFO 队列中等待")
          "the queued writer is described, not only counted")
      (is (not (str/includes? markup "aria-label=\"恢复写屏障\""))
          "a normal W does not show a recovery section"))))

(defn- with-recovery-gate
  [gate]
  (assoc-in fixture/fixture [:write-gate/by-id "swarm-1"] gate))

(deftest recovery-barrier-renders-complete-accessible-text
  (let [markup (render
                (model/dashboard-from-db
                 (with-recovery-gate
                   {:held? true
                    :owner "fleet-recovery-hold"
                    :queue 0
                    :recovery-blocked? true
                    :recovery-reasons #{:uncertain-actions :write-gate-held}
                    :recovery-actions ["action-recover-1" "action-recover-2"]
                    :recovery-write-gate {:held? true
                                          :owner "agent-old"
                                          :queue 2
                                          :outcome-unknown? true
                                          :queue-abandoned? true
                                          :reason :process-restart
                                          :recovered-at 1700000000000}})))]
    (testing "the barrier is a labelled text section, not a color-only signal"
      (is (str/includes? markup "aria-label=\"恢复写屏障\""))
      (is (str/includes? markup "恢复屏障生效中"))
      (is (str/includes? markup "W 被阻塞的原因"))
      (is (str/includes? markup "未结清的动作"))
      (is (str/includes? markup
                         "上一个进程持有 W 时未给出已证实的结局")))
    (testing "unresolved action IDs and old-gate evidence stay visible"
      (is (str/includes? markup "action-recover-1"))
      (is (str/includes? markup "action-recover-2"))
      (is (str/includes? markup "agent-old"))
      (is (str/includes? markup "队列 2 个"))
      (is (str/includes? markup "旧结局"))
      (is (str/includes? markup "未知；未证实已写或未写"))
      (is (str/includes? markup "已放弃；未重放"))
      (is (str/includes? markup "1700000000000"))
      (is (not (str/includes? markup "W 空闲"))))))

(deftest acknowledged-recovery-renders-persisted-evidence
  (let [markup (render
                (model/dashboard-from-db
                 (with-recovery-gate
                   {:held? false
                    :queue 0
                    :recovery-blocked? false
                    :recovery-acknowledgement
                    {:acknowledged? true
                     :actor-id "operator-7"
                     :evidence "worktree inspected and action resolved"
                     :write-gate-held? true
                     :at-version 42}})))]
    (testing "the acknowledged barrier shows the persisted evidence"
      (is (str/includes? markup "恢复已确认"))
      (is (str/includes? markup "恢复确认"))
      (is (str/includes? markup "worktree inspected and action resolved"))
      (is (str/includes? markup "operator-7"))
      (is (str/includes? markup "42"))
      (is (str/includes? markup "确认时 W 状态"))
      (is (not (str/includes? markup "恢复屏障生效中"))))))

(deftest join-report-is-rendered-in-full
  (let [markup (html)]
    (is (str/includes? markup "合入报告 — 完整文本"))
    (is (str/includes? markup "REPORT-END-9f3c"))
    (is (str/includes? markup "Joined after rebase."))
    (is (str/includes? markup "abc123"))
    (is (str/includes? markup "Rebase 轮次"))
    (testing "the report is in a focusable scroll region"
      (is (str/includes? markup "aria-label=\"完整合入报告，可滚动\""))
      (is (str/includes? markup "tabindex=\"0\"")))))

(deftest responsive-and-accessible-styles-are-present
  (let [markup (html)]
    (is (str/includes? markup "@media (min-width: 820px)"))
    (is (str/includes? markup "@media (max-width: 480px)"))
    (is (str/includes? markup "min-height: 44px"))
    (is (str/includes? markup "prefers-color-scheme: dark"))
    (is (str/includes? markup ":focus-visible"))
    (is (str/includes? markup "overflow-wrap: anywhere"))
    (is (str/includes? markup ".hc-recovery"))))

(deftest documented-component-renders-from-a-database-prop
  (let [component-markup
        (rds/renderToStaticMarkup
         (react/createElement ui/SwarmDashboard #js {:db fixture/fixture}))]
    (is (str/includes? component-markup "Swarm 总览"))
    (is (str/includes? component-markup "REPORT-END-9f3c"))
    (is (str/includes? component-markup "aria-label"))))

(deftest documented-component-opens-selected-swarm
  (let [component-markup
        (rds/renderToStaticMarkup
         (react/createElement ui/SwarmDashboard
                              #js {:db fixture/fixture
                                   "swarm/selected-id" "swarm-2"}))]
    (is (str/includes? component-markup "data-selected=\"true\""))
    (is (str/includes? component-markup "open"))))

(deftest documented-component-renders-recovery-from-a-database-prop
  (let [db (with-recovery-gate
             {:held? true
              :owner "fleet-recovery-hold"
              :queue 0
              :recovery-blocked? true
              :recovery-reasons #{:write-gate-held}
              :recovery-actions ["action-component-1"]
              :recovery-write-gate {:held? true
                                    :owner "old-agent"
                                    :queue 0
                                    :outcome-unknown? true
                                    :reason :process-restart}})
        component-markup
        (rds/renderToStaticMarkup
         (react/createElement ui/SwarmDashboard #js {:db db}))]
    (is (str/includes? component-markup "恢复屏障生效中"))
    (is (str/includes? component-markup
                       "上一个进程持有 W 时未给出已证实的结局"))
    (is (str/includes? component-markup "action-component-1"))
    (is (str/includes? component-markup "old-agent"))))

(deftest actual-server-board-shape-renders
  (let [markup (render (model/dashboard-from-db fixture/server-board-fixture))]
    (testing "the real server shape is shown as a board-only row, not an empty fleet"
      (is (str/includes? markup "状态未上报"))
      (is (str/includes? markup "未上报 fleet 行"))
      (is (str/includes? markup "Integrate authentication core"))
      (is (str/includes? markup "Implement token parsing")))
    (testing "unreported token/W/fleet facts stay explicitly unknown"
      (is (str/includes? markup "令牌槽位未由服务器上报。"))
      (is (str/includes? markup "W 状态未上报"))
      (is (str/includes? markup "队列</dt>"))
      (is (str/includes? markup "未上报</dd>"))
      (is (not (str/includes? markup "没有写者在 W 的 FIFO 队列中等待")))
      (is (str/includes? markup "未上报 fleet 合入队列"))
      (is (str/includes? markup "看板版本"))
      (is (str/includes? markup "等待已解除")))))

(deftest declared-counts-render-without-a-board
  (let [markup (render
                (model/dashboard
                 {:swarm/by-id
                  {"s" {:swarm-id "s" :slice-id "fleet-only"
                        :status :running
                        :root-ready 2 :root-waiting 1 :root-done 3}}}))]
    (is (str/includes? markup "根任务（声明值）"))
    (is (str/includes? markup "2 个就绪"))
    (is (str/includes? markup "1 个等待"))
    (is (str/includes? markup "3 个完成"))))

(deftest unacknowledged-recovery-evidence-is-not-called-acknowledged
  (let [markup (render
                (model/dashboard
                 {:swarm/by-id
                  {"s" {:swarm-id "s" :slice-id "recovery"
                        :status :running}}
                  :write-gate/by-id
                  {"s" {:held? false :queue 0
                        :recovery-acknowledgement
                        {:acknowledged? false
                         :evidence "not accepted"}}}}))]
    (is (str/includes? markup "已上报恢复证据"))
    (is (not (str/includes? markup
                            "恢复已确认 — 屏障已解除")))))

(deftest board-only-with-token-and-w-renders
  (let [markup (render (model/dashboard-from-db fixture/server-board-with-token))]
    (testing "real token/W/slot records are shown even without a fleet row"
      (is (str/includes? markup "W 持有中 — 托管 shell 运行中"))
      (is (str/includes? markup "agent-1"))
      (is (str/includes? markup "slot-d"))
      (is (str/includes? markup "空缺"))
      (is (not (str/includes? markup "令牌槽位未由服务器上报。"))))))

(deftest unresolved-calls-render-a-check-entry-and-acknowledged-state
  (let [markup (render (model/dashboard-from-db fixture/unresolved-calls))]
    (testing "the open call explains the unknown outcome and offers both checks"
      (is (str/includes? markup "结果待确认"))
      (is (str/includes? markup "权限被关闭，或执行结果无法确认")
          "the ten-category reason precedes the concrete copy")
      (is (str/includes? markup "call-1"))
      (is (str/includes? markup "确认已成功"))
      (is (str/includes? markup "确认未发生"))
      (is (str/includes? markup "绝不自动重放"))
      (is (str/includes? markup "data-ack-outcome=\"succeeded\""))
      (is (str/includes? markup "data-ack-outcome=\"not-occurred\"")))
    (testing "an acknowledged call keeps the operator conclusion visible"
      (is (str/includes? markup "call-2"))
      (is (str/includes? markup "已确认成功"))
      (is (str/includes? markup "checked the target file")))
    (testing "the panel is a labelled region with 44px actions"
      (is (str/includes? markup "aria-label=\"结果待确认\""))
      (is (str/includes? markup ".hc-unresolved-call__actions button")))))

(deftest worker-programs-render-conflict-card-and-retry
  (let [dashboard (assoc (model/dashboard-from-db fixture/unresolved-calls)
                         :on-retry-program (fn [_] nil))
        markup (render dashboard)]
    (is (str/includes? markup "受保护提交"))
    (is (str/includes? markup "hc-protected-program"))
    (is (str/includes? markup "job-1"))
    (is (str/includes? markup "FILE_CONFLICT"))
    (is (str/includes? markup "原 stamp:"))
    (is (str/includes? markup "当前 stamp:"))
    (is (str/includes? markup "stamp-mismatch"))
    (is (str/includes? markup "重读并重试"))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.swarm.ui-test))

(set! *main-cli-fn* -main)

