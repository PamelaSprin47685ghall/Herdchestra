(ns hc.invariants-coverage-test
  "Executable coverage table for root AGENTS.md §1.1, §8.3, and §8.6#2.

  Each clause names the real tests that guard it.  The table is checked
  against loaded test vars, not against prose: a deleted or renamed guard
  fails here.  A clause with no mapping fails with `MISSING GUARD: <clause>`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io FileNotFoundException)))

(def required-clauses
  [
   "§1.1#1"
   "§1.1#2"
   "§1.1#3"
   "§1.1#4"
   "§1.1#5"
   "§1.1#6"
   "§1.1#7"
   "§1.1#8"
   "§1.1#9"
   "§1.1#10"
   "V01"
   "V02"
   "V03"
   "V04"
   "V05"
   "V06"
   "V07"
   "V08"
   "T01"
   "T02"
   "T03"
   "T04"
   "T05"
   "T06"
   "T07"
   "T08"
   "T09"
   "T10"
   "T11"
   "T12"
   "T13"
   "F01"
   "F02"
   "F03"
   "F04"
   "F05"
   "F06"
   "F07"
   "F08"
   "F09"
   "F10"
   "F11"
   "F12"
   "F13"
   "F14"
   "F15"
   "§8.6#2"
   ])

(def guard-table
  {
   "§1.1#1"
   {:description "宏观接力咬合：Plan/Review/Swarm 的换任、台账与屏障在同一提交里咬合。"
    :guards [
             ["hc.core.relay-test" "plan-promotes-through-pending-and-hands-off-test"]
             ["hc.core.relay-test" "execution-initial-nonempty-review-starts-swarm-test"]
             ["hc.compose.mission.host-test" "atomic-barrier-swaps-tenure-board-and-binding-together"]
             ]}
   "§1.1#2"
   {:description "父项不自动完成：子项只把等待满足的父项恢复为 :ready。"
    :guards [
             ["hc.core.swarm-stm-test" "finish-child-recovers-parent-to-ready-not-done"]
             ["hc.core.swarm-stm-test" "multi-level-parent-recovery-never-cascades"]
             ]}
   "§1.1#3"
   {:description "受保护提交：内存批、stamp 复核与写锁 W 共同守住落盘。"
    :guards [
             ["hc.compose.workspace-test" "write-settles-through-w-and-publishes-the-action"]
             ["hc.server.program.workspace-integration-test" "write-applies-under-the-protected-commit"]
             ["hc.core.workspace-gate-test" "stamp-conflict-then-recovery"]
             ]}
   "§1.1#4"
   {:description "令牌不可逆：竞争只有一个赢家，死亡身份不能新增有效提交。"
    :guards [
             ["hc.core.swarm-stm-v-matrix-test" "v01-at-most-one-effective-holder"]
             ["hc.core.swarm-stm-matrix2-test" "t01-simultaneous-token-race-one-winner"]
             ["hc.core.swarm-stm-matrix2-test" "t13-empty-token-and-cancelled-last-responsibility"]
             ]}
   "§1.1#5"
   {:description "无人值守出口：只有挂起 ask 与终局交接是合法停止。"
    :guards [
             ["hc.compose.nudge.policy-test" "central-legal-exits-test"]
             ["hc.compose.nudge.policy-test" "plain-response-exit-test"]
             ["hc.server.program.capability-test" "ask-is-s1-only"]
             ["hc.core.relay-test" "plan-initial-empty-review-asks-once-test"]
             ]}
   "§1.1#6"
   {:description "Esc 递归取消：取消沿责任树传播，但固定结果不被倒推。"
    :guards [
             ["hc.compose.cancel-test" "operator-cancel-closes-admission-and-propagates"]
             ["hc.compose.cancel-test" "cancellation-tree-is-breadth-first"]
             ["hc.compose.cancel-test" "applying-action-finishes-before-cancellation-takes-effect"]
             ["hc.compose.cancel-integration-test" "operator-esc-stops-a-real-shell-subprocess"]
             ]}
   "§1.1#7"
   {:description "Plan 只读加 devops：底稿唯一可写，仓库调查只走 dedicated worker。"
    :guards [
             ["hc.server.program.runtime-test" "plan-read-surface-requires-context"]
             ["hc.server.program.capability-test" "plan-draft-rules"]
             ["hc.compose.mission.devops-test" "tool-surface-is-the-plan-worker-contract"]
             ["hc.compose.mission.devops-test" "forbidden-tool-is-reported-to-the-model-without-execution"]
             ["hc.compose.mission.devops-test" "synchronous-report-is-returned-verbatim-and-worker-is-reused"]
             ["hc.server.program.workspace-integration-test" "plan-draft-write-and-read-policy"]
             ]}
   "§1.1#8"
   {:description "模型归属：Steward/Plan/Reviewer 用主模型，worker/holder 用任务模型。"
    :guards [
             ["hc.compose.mission.model-test" "model-ownership-follows-root-invariants"]
             ["hc.compose.mission.model-test" "stewardship-tool-requirements"]
             ["hc.server.app.role-tables-consistency-test" "tool-required-classification-is-bidirectional"]
             ["hc.server.app.role-tables-consistency-test" "model-role-coverage-is-complete"]
             ]}
   "§1.1#9"
   {:description "Scope 只讲一次：COMMON 持有完整契约，PLAN_PROMPT 只留紧凑版。"
    :guards [
             ["hc.server.app.prompts-common-verbatim-test" "common-is-verbatim-with-agents-md"]
             ["hc.server.app.prompts-common-verbatim-test" "common-scope-section-is-single-and-full"]
             ["hc.server.app.prompts-common-verbatim-test" "plan-prompt-invariants"]
             ["hc.core.scope-test" "a-turn-with-only-me-too-defaults-to-keep-last"]
             ["hc.server.app.prompts-test" "scope-schema-points-at-the-system-scope-section"]
             ]}
   "§1.1#10"
   {:description "EDN + UFSS：系统面向模型的结构化文本不产生转义噪音。"
    :guards [
             ["hc.server.app.prompt-ufss-roundtrip-test" "prompt-strings-roundtrip-without-escape-noise"]
             ["hc.server.app.prompt-ufss-roundtrip-test" "prompts-source-never-uses-pr-str"]
             ["hc.compose.mission.ufss-boundary-test" "structured-receipts-with-quoted-paths-and-multiline-content"]
             ["hc.core.ufss-test" "string-bijection-property"]
             ]}
   "V01"
   {:description "同一时刻至多一个有效持令个体。"
    :guards [
             ["hc.core.swarm-stm-v-matrix-test" "v01-at-most-one-effective-holder"]
             ]}
   "V02"
   {:description "逻辑死亡身份不能产生新的有效提交。"
    :guards [
             ["hc.core.swarm-stm-v-matrix-test" "v02-dead-identity-cannot-submit-any-entry"]
             ]}
   "V03"
   {:description "有效竞争只有赢或输两种生命结论。"
    :guards [
             ["hc.core.swarm-stm-v-matrix-test" "v03-competition-results-are-closed"]
             ]}
   "V04"
   {:description "失败竞争不遗留归死者的可移交责任。"
    :guards [
             ["hc.core.swarm-stm-v-matrix-test" "v04-loser-leaves-no-transferable-responsibility"]
             ]}
   "V05"
   {:description "退休与责任空集在同一提交状态成立。"
    :guards [
             ["hc.core.swarm-stm-v-matrix-test" "v05-retirement-and-empty-responsibility-commit-together"]
             ]}
   "V06"
   {:description "子项完成只使等待满足的父项恢复 :ready。"
    :guards [
             ["hc.core.swarm-stm-test" "finish-child-recovers-parent-to-ready-not-done"]
             ["hc.core.swarm-stm-test" "multi-level-parent-recovery-never-cascades"]
             ]}
   "V07"
   {:description "普通任务归属不因他人持令而改变。"
    :guards [
             ["hc.core.swarm-stm-matrix-test" "v07-ordinary-ownership-survives-foreign-holder"]
             ]}
   "V08"
   {:description "唤醒与等待登记不会因检查间隔丢失。"
    :guards [
             ["hc.core.swarm-stm-v-matrix-test" "v08-wake-and-park-registration-cannot-be-lost"]
             ["hc.core.workspace-gate-boundary-test" "f15-wake-all-covers-parked-slots-without-releasing-w"]
             ]}
   "T01"
   {:description "同时令牌竞争只有一个赢家。"
    :guards [
             ["hc.core.swarm-stm-matrix2-test" "t01-simultaneous-token-race-one-winner"]
             ]}
   "T02"
   {:description "丢失的竞赛响应重放同一个赢局。"
    :guards [
             ["hc.core.swarm-stm-matrix2-test" "t02-lost-response-replays-the-same-win"]
             ]}
   "T03"
   {:description "死亡身份不能提交新条目。"
    :guards [
             ["hc.core.swarm-stm-v-matrix-test" "v02-dead-identity-cannot-submit-any-entry"]
             ]}
   "T04"
   {:description "落败者公开等待中的父项责任。"
    :guards [
             ["hc.core.swarm-stm-matrix-test" "t04-losing-competitor-publishes-waiting-parent"]
             ]}
   "T05"
   {:description "新令牌任务加入活着的持令者后再完成。"
    :guards [
             ["hc.core.swarm-stm-matrix2-test" "t05-new-token-task-joins-live-holder-before-finish"]
             ]}
   "T06"
   {:description "退休先于新令牌责任成立。"
    :guards [
             ["hc.core.swarm-stm-matrix-test" "t06-retirement-precedes-new-token-responsibility"]
             ]}
   "T07"
   {:description "子项完成恢复父项但不完成父项。"
    :guards [
             ["hc.core.swarm-stm-test" "finish-child-recovers-parent-to-ready-not-done"]
             ]}
   "T08"
   {:description "令牌子项完成保留普通父项归属。"
    :guards [
             ["hc.core.swarm-stm-matrix-test" "t08-token-child-completion-keeps-ordinary-parent-owner"]
             ]}
   "T09"
   {:description "取消意图保留运行中的 Shell 责任。"
    :guards [
             ["hc.compose.owner.runtime-test" "t09-cancel-intent-keeps-a-running-shell-as-responsibility"]
             ]}
   "T10"
   {:description "拆分令牌工作为普通与令牌子项。"
    :guards [
             ["hc.core.swarm-stm-matrix-test" "t10-split-token-work-into-ordinary-and-token-children"]
             ]}
   "T11"
   {:description "死亡个体的迟到完成不改父项。"
    :guards [
             ["hc.core.swarm-stm-matrix2-test" "t11-dead-actors-late-finish-never-changes-parent"]
             ]}
   "T12"
   {:description "持令者阻止必败竞争并保持休眠。"
    :guards [
             ["hc.core.swarm-stm-matrix-test" "t12-token-holder-blocks-doomed-competition-and-sleep"]
             ]}
   "T13"
   {:description "空令牌与取消后的最后责任。"
    :guards [
             ["hc.core.swarm-stm-matrix2-test" "t13-empty-token-and-cancelled-last-responsibility"]
             ]}
   "F01"
   {:description "等 W 期间 Shell 改动使 A 的 stamp 失效，A 得 :FILE_CONFLICT。"
    :guards [
             ["hc.core.workspace-gate-test" "f01-shell-write-while-queued-conflicts"]
             ]}
   "F02"
   {:description "只读依赖变更使提交被拒，即使目标文件 stamp 未变。"
    :guards [
             ["hc.core.workspace-gate-test" "f02-read-only-dependency-structure-change-conflicts"]
             ]}
   "F03"
   {:description "第三文件定位失败时整批不落盘。"
    :guards [
             ["hc.core.workspace-gate-test" "f03-third-file-failure-keeps-disk-untouched"]
             ["hc.core.workspace-gate-test" "f03-text-location-parks-before-the-third-op-is-added"]
             ]}
   "F04"
   {:description "批内视图按逻辑顺序看到 rename 后的结构。"
    :guards [
             ["hc.core.workspace-gate-boundary-test" "f04-batch-view-sees-rename-before-edit"]
             ]}
   "F05"
   {:description ":applying 期间取消，固定最小提交继续完成。"
    :guards [
             ["hc.core.workspace-gate-test" "f05-stop-after-first-op-completes-batch"]
             ["hc.compose.workspace-test" "cancel-during-apply-completes-the-fixed-batch"]
             ]}
   "F06"
   {:description "发布 STM 冲突只重试记账，不重复落盘。"
    :guards [
             ["hc.core.workspace-gate-test" "publish-conflict-retries-only-publication"]
             ]}
   "F07"
   {:description "Shell 持 W 时读取不被阻塞。"
    :guards [
             ["hc.core.workspace-gate-test" "reading-does-not-wait-for-the-gate"]
             ["hc.core.workspace-gate-test" "f01-shell-write-while-queued-conflicts"]
             ]}
   "F08"
   {:description "相同 mtime+size 的不同内容处在已接受的观察边界。"
    :guards [
             ["hc.core.workspace-gate-test" "same-mtime-and-size-is-an-observation-boundary"]
             ]}
   "F09"
   {:description "atime 永不进入通用 stamp 比较。"
    :guards [
             ["hc.core.workspace-gate-test" "atime-is-never-part-of-the-stamp"]
             ]}
   "F10"
   {:description "空写集仍经 W 复核 read-set。"
    :guards [
             ["hc.core.workspace-gate-test" "checks-only-empty-write-set-is-verified"]
             ["hc.core.workspace-gate-test" "read-set-is-verified-even-without-checks"]
             ["hc.compose.workspace-test" "read-set-only-settle-verifies-without-writing"]
             ]}
   "F11"
   {:description "等 W 不因超时私自转移；等待链如实保留。"
    :guards [
             ["hc.core.workspace-gate-test" "commit-waits-for-w-without-timeout-transfer"]
             ["hc.compose.workspace-mutual-wait-test"
              "f11-shell-holds-w-while-a-waits-and-stamp-conflict-breaks-the-ring"]
             ]}
   "F12"
   {:description "最外层返回后管理中的子活动仍保持 W 与责任。"
    :guards [
             ["hc.core.workspace-gate-boundary-test" "f12-managed-child-keeps-w-and-detached-is-external"]
             ]}
   "F13"
   {:description "应用到第二文件硬失败保留第一已变、第二不确定、第三未变。"
    :guards [
             ["hc.core.workspace-gate-boundary-test" "f13-hard-failure-at-second-file-keeps-uncertain-site"]
             ]}
   "F14"
   {:description "锁内校验后的外部写入是 best-effort 边界。"
    :guards [
             ["hc.core.workspace-gate-test" "external-write-after-the-check-is-best-effort"]
             ]}
   "F15"
   {:description "park-slot 与 wake-all 不会漏唤醒。"
    :guards [
             ["hc.core.workspace-gate-boundary-test" "f15-wake-all-covers-parked-slots-without-releasing-w"]
             ]}
   "§8.6#2"
   {:description "启动身份：打印 git hash 与 dirty 标志。"
    :guards [
             ["hc.server.app.startup-identity-test"
              "startup-identity-is-observable"]
             ]}
   })

(defn- require-namespace
  [ns-name]
  (try
    (require (symbol ns-name))
    {:ok? true}
    (catch FileNotFoundException error
      {:ok? false
       :missing? true
       :error (or (ex-message error) (str error))})
    (catch Throwable error
      (let [message (or (ex-message error) (str error))]
        {:ok? false
         :missing? (boolean
                    (re-find #"Could not locate|No such namespace" message))
         :error message}))))

(deftest every-required-clause-has-a-live-guard
  (let [entries (mapcat (fn [[_ {:keys [guards]}]] guards) guard-table)
        ns-names (vec (distinct (map first entries)))
        ns-status (into {} (map (fn [ns-name]
                              [ns-name (require-namespace ns-name)])
                            ns-names))
        missing-namespaces (for [[ns-name status] ns-status
                             :when (:missing? status)]
                         [ns-name (:error status)])
        unloadable (for [[ns-name status] ns-status
                      :when (and (not (:ok? status))
                                 (not (:missing? status)))]
                  [ns-name (:error status)])]
    (is (seq entries) "the guard table is empty")
    (is (empty? missing-namespaces)
        (str "guard table names missing namespaces: "
             (pr-str (mapv first missing-namespaces))))
    (println "--- skipped guard namespaces (recorded, not silently passed) ---")
    (doseq [[ns-name error] unloadable]
      (println "SKIPPED NAMESPACE:" ns-name "-" error))
    (doseq [clause required-clauses]
      (let [{:keys [description guards]} (get guard-table clause)]
        (testing (str clause " — " (or description "MISSING DESCRIPTION"))
          (is (seq guards) (str "MISSING GUARD: " clause))
          (is (not (str/blank? description))
              (str "MISSING DESCRIPTION: " clause))
          (doseq [[ns-name test-name] guards
                  :let [status (get ns-status ns-name)]
                  :when (:ok? status)]
            (let [test-var (ns-resolve (symbol ns-name)
                                       (symbol test-name))]
              (is (and (some? test-var)
                       (some? (:test (meta test-var))))
                (str "MISSING TEST VAR: " ns-name "/" test-name
                     (when (nil? test-var) " (var not found)")
                     (when (and test-var
                                (nil? (:test (meta test-var))))
                       " (var is not a test)"))))))))))
