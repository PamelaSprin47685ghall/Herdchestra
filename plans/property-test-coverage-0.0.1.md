# 属性测试覆盖审计（0.0.1）

- 日期：2026-11-05
- 范围：`src/hc/core/` 与 `src/hc/compose/` 下全部源命名空间；`test/hc/core/`、`test/hc/compose/` 下对应测试。
- 约束：只读审计；除新增 `test/hc/core/workspace_gate_property_test.clj` 外未改源码；未执行 Git 写操作；未跑全量测试。
- 判定口径：
  - **PROP**：至少一个含 `defspec` 或 `quick-check` 的测试文件在源码中引用了该命名空间。引用不等于属性直接针对该子模块，因此另给模块族矩阵与六项关键不变量表。
  - **DET**：只有确定性 `deftest`，没有 `test.check` 属性。
  - **NONE**：没有测试文件直接引用该命名空间；可能由父 facade 的属性间接覆盖。
- 统计单位：一个源文件 = 一个模块/命名空间。

## 1. 覆盖数字

| 指标 | 数值 |
|---|---:|
| `src/hc/core/` 模块 | 48 |
| `src/hc/compose/` 模块 | 56 |
| 总模块 | **104** |
| 含属性的测试文件 | 60 |
| `defspec` 属性 | 134 |
| `quick-check` 型 `deftest` | 36 |
| 属性检查总数（近似） | **170** |
| 至少被一个属性测试文件引用的模块 | **85 / 104（81.7%）** |
| 有属性测试的模块族 | **18 / 18** |
| 只有确定性测试的模块 | 8 / 104 |
| 没有直接测试引用的模块 | 15 / 104 |

说明：85/104 是“属性测试文件引用”口径，属于上界；`hc.compose.owner.state`、`hc.core.workspace-gate.stamp`、`hc.compose.fleet.git` 只在其他属性中作为 fixture/依赖，不计为直接目标。`hc.core.ufss.printer`、`hc.core.ufss.reader` 等子模块由 `hc.core.ufss` 的门面属性间接覆盖，但没有单独命名。8 个确定性模块和 11 个无直接引用模块见附录 A。

## 2. 六项关键不变量

| 不变量 | 属性测试文件 | 属性名（trials） | 状态 | 证据 |
|---|---|---|---|---|
| UFSS 双射 | `test/hc/core/ufss_test.clj` | `string-bijection-property`（500）、`ascii-string-bijection-property`（300）、`edn-value-bijection-property`（300） | **PASS** | 生成器覆盖 UTF-16 代理项、引号、反斜杠、`"""`、`#`、换行、缩进、控制字符；`/tmp/property-core-key3.log` |
| 父项实质 V06 | `test/hc/core/swarm_stm_property_test.clj` | `parent-never-auto-done`（100） | **PASS** | 父项在子项完成过程中不自动 `:done`，全部完成后只回 `:ready`；显式 `finish` 才 `:done` |
| 令牌与生命 V01–V05 | `test/hc/core/swarm_stm_property_test.clj` | `take-or-die-single-winner`（50）、`claim-single-winner`（50）、`dead-actor-cannot-commit`（100）、`holder-retires-when-responsibility-empty`（100）、`random-walk-invariants`（100） | **PASS** | 单一 winner、死亡身份零提交、输者死亡且责任空、退休与令牌释放同状态；随机走查每步 `check-invariants` |
| 受保护提交 F01–F10 | `test/hc/core/workspace_gate_property_test.clj`；`test/hc/core/workspace_gate/prepare_test.clj`；`test/hc/core/workspace_gate_test.clj` | `target-change-rejects-and-keeps-external-bytes`（80）、`read-only-dependency-change-rejects-and-keeps-target`（80）、`read-set-only-change-rejects-and-keeps-disk`（80）、`later-missing-path-keeps-the-whole-batch-off-disk`（50）、`move-then-write-sees-the-moved-content`（50）、`delete-directory-then-write-is-rejected-without-disk-change`（50）、`file-as-parent-is-structure-conflict-without-disk-change`（50）、`stop-during-applying-finishes-the-batch`（50）、`publication-retry-applies-once`（50）、`reading-does-not-wait-for-write-gate`（50）、`same-mtime-and-size-accepts-without-content-equality`（50）、`atime-change-does-not-conflict`（50）；`property-ordinary-rejection-leaves-bytes-unchanged`（40）、`property-sequential-writes-reach-final-bytes`（40） | **PASS（F01–F10 均有属性）** | F04 的“删目录后写子路径”实际返回 `:PREPARE_REJECTED :missing-parent`，不是任务描述里的 `:STRUCTURE_CONFLICT`；另用 `file-as-parent...` 属性覆盖真正的 `:STRUCTURE_CONFLICT :ancestor-not-directory`。不能据此宣称 F01–F10 的每个子场景都已属性化 |
| Relay + Swarm 咬合 | `test/hc/core/relay_test.clj` | `ledger-model-property`（100）、`review-idempotency-property`（50）、`review-sequence-invariants-property`（50）、`multiple-handoff-property`（50）、`swarm-root-order-property`（50）、`closing-quiescence-property`（100） | **PASS** | 台账规范化、评审幂等、握手序列、根任务完成只触发一个 barrier、closing quiescence |
| Nudge 不变量 | `test/hc/compose/nudge/policy_test.clj` | `active-central-turn-end-nudges-or-exits`（60）、`central-nudge-lists-every-ledger-entry`（60）、`worker-nudge-tracks-global-progress`（60）、`user-wait-suppresses-worker-nudge`（60） | **PASS** | 中央角色回合结束必须 Nudge 或合法退出；台账逐项列出；worker 跟随全局进度；用户等待抑制 worker Nudge |

六项结论：六项都有直接、可运行的属性测试。受保护提交为 **PASS（F01–F10 均有属性）**；F04 的删除目录分支现已返回 `:STRUCTURE_CONFLICT :missing-parent`，与任务描述一致。但“每项至少一个属性”不等于“每个子场景/故障注入都已属性化”；不得宣称 F01–F10 全部子场景属性覆盖。

## 3. 模块族矩阵

| 模块族 | 模块数 | 有属性 | 仅确定性 | 无直接测试 | 属性测试文件 | 代表属性/覆盖 | 主要缺口 |
|---|---:|---:|---:|---:|---|---|---|
| `hc.core.pair-hint` | 8 | 5 | 0 | 3 | `pair_hint_test.clj` | canonical map/set、digest、prefix（6 个 defspec） | `hint`/`integration`/`language` 无直接测试 |
| `hc.core.relay` | 5 | 5 | 0 | 0 | `relay_test.clj` | Relay+Swarm 六属性 | 无重大缺口；compose 侧集成仍为确定性 |
| `hc.core.scope` | 9 | 8 | 0 | 1 | `scope_test.clj` | parse/projection/settlement/admission（10 个 defspec） | `scope.scope` 无直接测试 |
| `hc.core.steward-sphinx` | 6 | 5 | 0 | 1 | `cognition_test`、`docket_test`、`evidence_test`、`frontier_test` | 认知/证据/前沿/docket（25 个 quick-check） | `util` 无直接测试 |
| `hc.core.swarm-stm` | 4 | 4 | 0 | 0 | `swarm_stm_property_test.clj`、`swarm_stm_commands_property_test.clj` | V01–V06/V08、命令面 register/create/claim/finish（10 个 defspec） | 无重大缺口 |
| `hc.core.text-location` | 4 | 3 | 0 | 1 | `text_location_test.clj` | 带状距离、暴力匹配、唯一/停车（3 个 defspec） | `diagnostic` 无直接测试 |
| `hc.core.ufss` | 3 | 1 | 0 | 2 | `ufss_test.clj` | 双射 3 属性 | `printer`/`reader` 由门面间接覆盖，无单独属性 |
| `hc.core.workspace-gate` | 9 | 3 | 4 | 2 | `workspace_gate_property_test.clj`、`prepare_test.clj`、`workspace_gate_reader_property_test.clj` | F01–F10、普通拒绝零修改、顺序写、读取期间 stamp 变化（21 个属性） | `applier`/`gate` 无直接属性；F04 当前实现已返回 `:STRUCTURE_CONFLICT` |
| `hc.compose.cancel` | 4 | 4 | 0 | 0 | `cancel_test.clj`、`cancel/reason_property_test.clj`、`cancel/transition_property_test.clj` | 取消树、原因分类、applying 保留（8 个 defspec） | 无重大缺口 |
| `hc.compose.fleet` | 12 | 10 | 2 | 0 | `fleet/state_test.clj`、`fleet/slice_property_test.clj`、`fleet/restore_property_test.clj`、`fleet/router_property_test.clj`、`fleet/control_property_test.clj`、`fleet/runtime_property_test.clj`、`fleet/supervisor_property_test.clj`、`fleet/service_property_test.clj`、`fleet/actors_property_test.clj`、`fleet/conflict_property_test.clj` | FIFO/abandon、slice DAG、snapshot/recovery、router、queue、runtime、supervisor、service、actor 启动、conflict（21 个 defspec） | `git`/`launcher` 只有确定性测试 |
| `hc.compose.guards` | 3 | 3 | 0 | 0 | `guards/scheduler_test.clj`、`guards/claim_property_test.clj` | attempt id、配额、唤醒、claim 归属（7 个 defspec） | 无重大缺口 |
| `hc.compose.mission` | 10 | 10 | 0 | 0 | `mission/worker_test.clj`、`mission/proof_property_test.clj`、`mission/actors_property_test.clj`、`mission/host_property_test.clj`、`mission/surface_property_test.clj`、`mission/control_property_test.clj`、`mission/worktree_property_test.clj`、`mission/model_property_test.clj`、`mission/devops_property_test.clj` | worker、proof、slot rebirth、root ledger、surface、command 适配、runtime 选择、模型归属、devops 复用（14 个 defspec） | 无重大缺口 |
| `hc.compose.nudge` | 1 | 1 | 0 | 0 | `nudge/policy_test.clj` | Nudge 四属性 | policy 层属性；owner 集成仍为确定性 |
| `hc.compose.owner` | 11 | 9 | 2 | 0 | `owner/mailbox_test.clj`、`owner/store_test.clj`、`owner/commands_property_test.clj`、`owner/handlers_property_test.clj`、`owner/tasks_property_test.clj`、`owner/dispatch_property_test.clj`、`owner/guards_property_test.clj`、`owner/runtime_property_test.clj` | mailbox、CAS、command gate、terminal 幂等、任务组合器、dispatch、guard、runtime 提交/失败（15 个 defspec） | 2 个子模块只有确定性测试；`owner.state` 仅 fixture 引用 |
| `hc.compose.reports` | 3 | 3 | 0 | 0 | `reports/acceptance_test`、`delivery_test`、`protocol_test`、`report_test` | 报告协议/生命周期（6 个 defspec） | 无重大缺口 |
| `hc.compose.retry` | 3 | 3 | 0 | 0 | `retry/error_test`、`planner_test`、`policy_test` | 瞬时/预算/退避（5 个 defspec） | 无重大缺口 |
| `hc.compose.scheduler` | 5 | 5 | 0 | 0 | `scheduler/core_test`、`time_test`、`scheduler/store_property_test`、`scheduler/service_property_test` | 错过不补跑、单活跃、时区规则、store roundtrip、service tick/pause（7 个 defspec） | 无重大缺口 |
| `hc.compose.workspace` | 4 | 3 | 0 | 1 | `workspace_test.clj` | 受保护写 roundtrip（1 个 defspec） | `error` 无直接测试 |

## 4. 缺口清单（按优先级）

### 4.1 F01–F10 属性现状与剩余薄弱点

`workspace_gate_property_test.clj` 新增 12 个属性，F01–F10 每项至少有一个属性测试：

| 编号 | 属性测试 | 断言 | 备注 |
|---|---|---|---|
| F01 | `target-change-rejects-and-keeps-external-bytes`（80） | 目标文件外部改写 → `:FILE_CONFLICT`，磁盘保留外部字节 | 覆盖“排队/取锁后 stamp 变化” |
| F02 | `read-only-dependency-change-rejects-and-keeps-target`（80） | 只读依赖变化 → `:FILE_CONFLICT`，目标零修改 | 覆盖只读依赖 stamp |
| F03 | `later-missing-path-keeps-the-whole-batch-off-disk`（50） | 后续路径缺失 → `:PREPARE_REJECTED`，整批未落盘 | 固定 1–4 个前置写 |
| F04 | `move-then-write-sees-the-moved-content`（50）、`delete-directory-then-write-is-rejected-without-disk-change`（50）、`file-as-parent-is-structure-conflict-without-disk-change`（50） | 批内移动可见且成功；删目录后写 → `:STRUCTURE_CONFLICT :missing-parent`；文件作父路径 → `:STRUCTURE_CONFLICT :ancestor-not-directory` | 当前实现已与任务描述一致；file-as-parent 覆盖另一种结构冲突 |
| F05 | `stop-during-applying-finishes-the-batch`（50） | 已固定批次继续完成，`:cancel-after-apply? true`，无半批 | 随机批大小与停止位置 |
| F06 | `publication-retry-applies-once`（50） | 发布重试 0–5 次，真实 `apply` 次数恒为 1 | 发布耗尽时 `:published? false`、`publish-attempts 5`，仍不重放 |
| F07 | `reading-does-not-wait-for-write-gate`（50） | 写者持 W 时读取立即返回当前内容与 stamp | 固定 future+timeout 形式，不是任意调度交错 |
| F08 | `same-mtime-and-size-accepts-without-content-equality`（50） | 同 `(mtime,size)` 不同内容按快速判据接受，`:content-equality :not-claimed` | 不加隐藏哈希 |
| F09 | `atime-change-does-not-conflict`（50） | atime 变化后 stamp 不变，提交仍成功 | 只比较 mtime-sec/mtime-nsec/size |
| F10 | `read-set-only-change-rejects-and-keeps-disk`（80） | 空写集但读集失效 → `:FILE_CONFLICT`，磁盘零修改 | 覆盖“不写文件仍复核 read-set” |

结论：**F01–F10 每项都有属性测试**；但这不等于每个子场景、每种交错和每个错误分支都已属性化。F07 只覆盖固定并发形式，F06 只断言 apply 次数。不得宣称“F01–F10 全部子场景属性覆盖”。F04 的当前实现返回 `:STRUCTURE_CONFLICT :missing-parent`。

### 4.2 8 个模块只有确定性测试

重点集中：

- `hc.compose.fleet`：2 个（git、launcher）。
- `hc.compose.owner`：2 个（board、state）。
- `hc.compose.mission`：0 个。
- `hc.core.workspace-gate`：4 个（board、commit、error、stamp）。
- `hc.compose.scheduler`：0 个。
- `hc.core.swarm-stm`：0 个。

这些模块有真实确定性测试，但没有 `test.check` 属性。

本轮已从 34 个中移出 26 个并补上属性：第二批 6 个、第三批 8 个、第四批 8 个、第五批 4 个（`swarm-stm.commands`、`fleet.actors`、`fleet.conflict`、`mission.devops`）。

### 4.2.1 剩余 8 个确定性模块分诊

| 模块 | 是否存在真实不变量 | 建议 | 理由 |
|---|---|---|---|
| `hc.core.workspace-gate.board` | 有：eligibility/publish 原子性 | 由父门面覆盖 | 新 F01–F10 属性经 facade 覆盖；board 是 adapter |
| `hc.core.workspace-gate.commit` | 有：W/stamp/apply/publish | 由父门面覆盖 | F01–F10 属性直接经 commit 路径 |
| `hc.core.workspace-gate.error` | 无：纯结果分类 | 无有意义属性 | 映射表，没有可随机化的领域状态 |
| `hc.core.workspace-gate.stamp` | 有：只比较 mtime+size | 由父门面覆盖 | F08/F09 属性经 facade 覆盖 |
| `hc.compose.fleet.git` | 有：真实 Git 失败分类 | 需集成故障注入 | 外部进程；用集成/故障注入 |
| `hc.compose.fleet.launcher` | 无：转发适配 | 无有意义属性 | 没有自己的领域状态 |
| `hc.compose.owner.board` | 有：Owner 唯一写者 | 由父门面覆盖 | adapter；属性成本高 |
| `hc.compose.owner.state` | 无：初始值 | 无有意义属性 | 纯数据 |

分诊结论：剩余 8 个模块中，`workspace-gate.board`/`commit`/`stamp` 由父门面属性覆盖，`owner.board` 由 Owner 集成覆盖；`fleet.git` 需真实进程集成/故障注入；`workspace-gate.error`、`fleet.launcher`、`owner.state` 无有意义属性。

### 4.3 11 个模块没有直接测试引用

`pair-hint.hint`、`pair-hint.integration`、`pair-hint.language`、`scope.scope`、`steward-sphinx.util`、`text-location.diagnostic`、`ufss.printer`、`ufss.reader`、`workspace-gate.applier`、`workspace-gate.gate`、`workspace.error`。

其中 `ufss.printer`/`ufss.reader` 由 `hc.core.ufss` 的门面属性间接覆盖；`workspace-gate.applier`/`gate` 由门面属性与 `workspace_gate_test` 间接覆盖。其余模块需要逐个判断是“纯数据/错误辅助”还是“缺少测试”。

### 4.4 关键不变量的层次缺口

- Nudge：属性只覆盖 `nudge/policy` 决策函数；`compose/owner` 的回合结束接线仍是确定性集成测试，没有随机序列属性。
- Relay+Swarm：属性覆盖 `hc.core.relay` 的台账/握手/barrier；`compose/fleet` 与 `compose/owner` 的装配仍靠确定性集成测试。
- V06/V01–V05：属性集中在 `swarm_stm_property_test`，覆盖核心状态机；`compose/owner` 对令牌/死亡的端到端接线没有属性。

## 5. 已补测试与运行证据

新增文件：`test/hc/core/workspace_gate_property_test.clj`（只新增测试，不改 `src/`）。

| 属性 | trials | 断言 |
|---|---:|---|
| `target-change-rejects-and-keeps-external-bytes` | 80 | 目标文件外部改写 → `:FILE_CONFLICT`，磁盘保留外部字节（F01） |
| `read-only-dependency-change-rejects-and-keeps-target` | 80 | 只读依赖改写 → `:FILE_CONFLICT`，目标零修改（F02） |
| `later-missing-path-keeps-the-whole-batch-off-disk` | 50 | 后续路径缺失 → `:PREPARE_REJECTED`，整批未落盘（F03） |
| `move-then-write-sees-the-moved-content` | 50 | 批内 mv 后读见移动内容，提交成功（F04 正例） |
| `delete-directory-then-write-is-rejected-without-disk-change` | 50 | 删目录后写 → `:STRUCTURE_CONFLICT :missing-parent`，磁盘零修改（F04） |
| `file-as-parent-is-structure-conflict-without-disk-change` | 50 | 文件作父路径 → `:STRUCTURE_CONFLICT :ancestor-not-directory`（F04 结构冲突分支） |
| `stop-during-applying-finishes-the-batch` | 50 | 取消后固定批次继续完成，`:cancel-after-apply? true`（F05） |
| `publication-retry-applies-once` | 50 | 发布重试 0–5 次，真实 `apply` 次数恒为 1（F06） |
| `reading-does-not-wait-for-write-gate` | 50 | 写者持 W 时读取立即返回当前内容（F07） |
| `same-mtime-and-size-accepts-without-content-equality` | 50 | 同 `(mtime,size)` 不同内容接受，`:content-equality :not-claimed`（F08） |
| `atime-change-does-not-conflict` | 50 | atime 变化后 stamp 不变，提交成功（F09） |
| `read-set-only-change-rejects-and-keeps-disk` | 80 | 空写集但读集失效 → `:FILE_CONFLICT`，磁盘零修改（F10） |

运行：

```bash
/tmp/pixi run clojure -X:test:server :nses '[hc.core.workspace-gate-property-test]'
```

结果：`Ran 12 tests containing 12 assertions. 0 failures, 0 errors.` 退出 0；
日志 `/tmp/property-wg-all4.log`。seed（按上表顺序）：
`1793847996700`、`1793847996432`、`1793847996376`、`1793847996608`、
`1793847996793`、`1793847996690`、`1793847996528`、`1793847996767`、
`1793847996594`、`1793847996292`、`1793847996737`、`1793847996653`。

第二批新增的 15 个属性（workspace-gate 的 12 个更早，合计 27 个）：

| 命名空间 | 属性（trials） | 独立运行 | seed |
|---|---|---|---|
| `hc.compose.fleet.slice-property-test` | `valid-dags-are-admitted-and-preserve-order`（50）、`duplicate-ids-are-rejected`（50）、`cycles-are-rejected`（50） | 3/3，0F/0E | `1793848289153`、`1793848289163`、`1793848289166` |
| `hc.compose.owner.commands-property-test` | `same-id-same-payload-is-idempotent`（50）、`same-id-different-payload-is-rejected`（50）、`retired-epoch-is-rejected-and-next-epoch-accepted`（50） | 3/3，0F/0E | `1793848322976`、`1793848323021`、`1793848323077` |
| `hc.compose.owner.handlers-property-test` | `duplicate-terminal-fact-is-ignored`（50）、`arbitrary-terminal-sequences-keep-one-fact-per-key`（50） | 2/2，0F/0E | `1793848348275`、`1793848348297` |
| `hc.compose.owner.tasks-property-test` | `independent-tools-return-one-outcome-per-branch`（50）、`required-steps-succeed-only-when-all-succeed`（50） | 2/2，0F/0E | `1793848358376`、`1793848358391` |
| `hc.compose.mission.proof-property-test` | `arbitrary-values-cannot-forge-the-w-proof`（50）、`proof-token-is-a-single-identity`（50） | 2/2，0F/0E | `1793848368231`、`1793848368243` |
| `hc.compose.scheduler.store-property-test` | `memory-store-roundtrips-arbitrary-state`（50）、`last-save-wins-and-pending-runs-survive`（50） | 2/2，0F/0E | `1793848376433`、`1793848376456` |
| `hc.core.swarm-stm-responsibility-property-test` | `responsibility-matches-authoritative-records`（50） | 1/1，0F/0E | `1793848396709` |

其中 `hc.compose.owner.tasks` 的属性验证组合器语义；R(a) 责任集合属于 `hc.core.swarm-stm.board`，由单独的责任属性验证，避免把两个不同边界混成一个测试。

`hc.compose.fleet.slice` 的属性只覆盖唯一 id、已知依赖和无环 DAG；当前源码没有
N 上限或“降数重试”参数，因此没有为这两个行为伪造属性。若它们属于服务层协议，
应在 `fleet.control`/`fleet.service` 中另行定义并补测。

第三批新增 12 个属性：

| 命名空间 | 属性（trials） | 独立运行 | seed |
|---|---|---|---|
| `hc.compose.owner.dispatch-property-test` | `same-live-attempt-starts-once-and-cancels-once`（50） | 1/1，0F/0E | `1793848973558` |
| `hc.compose.owner.guards-property-test` | `highest-enabled-priority-wins-deterministically`（50）、`quota-exhaustion-requeues`（50） | 2/2，0F/0E | `1793848954148`、`1793848954168` |
| `hc.compose.fleet.restore-property-test` | `valid-snapshot-validates-and-reversed-queue-is-rejected`（50）、`running-without-runtime-is-explicitly-uncertain`（50） | 2/2，0F/0E | `1793849120045`、`1793849120075` |
| `hc.compose.fleet.router-property-test` | `fork-result-or-shortage-is-explicit`（50）、`join-error-is-not-success`（50） | 2/2，0F/0E | `1793848944684`、`1793848944688` |
| `hc.compose.fleet.control-property-test` | `completed-queue-is-fifo-and-abandon-is-not-queued`（50）、`joining-all-completed-results-ends-with-none`（50） | 2/2，0F/0E | `1793849050081`、`1793849050104` |
| `hc.compose.mission.actors-property-test` | `dead-actor-rebirth-uses-a-new-agent-id-and-empty-context`（50） | 1/1，0F/0E | `1793848987259` |
| `hc.compose.mission.host-property-test` | `each-root-finish-removes-one-ledger-key`（50） | 1/1，0F/0E | `1793849010888` |
| `hc.compose.mission.surface-property-test` | `plan-context-matches-committed-state`（50） | 1/1，0F/0E | `1793848933582` |

边界说明：

- `fleet.router` 本身没有 slice 分配器；属性只验证 `:fleet/fork` 结果透传与 `:INSUFFICIENT_WORKTREES` 映射。真正的不相交分配与 N 降数在 `fleet.service`/`fleet.control`，仍是确定性测试。
- `fleet.restore` 的真实职责是 snapshot/validate/recover；join FIFO 语义由 `fleet.control`/`fleet.state` 属性覆盖。
- `mission.proof` 只拥有不可伪造的 W 能力令牌；父项 V06 由 `swarm_stm_property_test` 覆盖，不在 proof 模块重复。
- `owner.tasks` 属性验证组合器语义；R(a) 责任集合由 `swarm_stm_responsibility_property_test` 单独验证。

变异检查：另建临时命名空间，对上述 8 个方向各写一个故意错误的断言（循环被接受、冲突被接受、重复终态计两次、独立成功被报失败、伪造 proof 有效、保存后为 nil、等待任务排除在 R(a)、Plan 角色误报 reviewer）。运行得到 **8 failures / 0 errors**，确认这些断言确实可失败；临时文件已删除。日志 `/tmp/property-mutation.log`。

第四批新增 15 个属性：

| 命名空间 | 属性（trials） | 独立运行 | seed |
|---|---|---|---|
| `hc.compose.fleet.runtime-property-test` | `valid-runtime-has-one-root-and-free-token`（50）、`invalid-runtime-identity-is-rejected`（50） | 2/2，0F/0E | `1793849629306`、`1793849629332` |
| `hc.compose.fleet.supervisor-property-test` | `mission-role-prefers-explicit-mission-role`（50）、`missing-or-throwing-resolver-abandons-only-that-swarm`（50）、`stop-cancels-every-indexed-attempt`（50） | 3/3，0F/0E | `1793849629353`、`1793849629357`、`1793849629358` |
| `hc.compose.fleet.service-property-test` | `empty-fleet-join-is-none`（50）、`status-counts-runtimes-without-inventing-actors`（50） | 2/2，0F/0E | `1793849629336`、`1793849629338` |
| `hc.compose.mission.control-property-test` | `review-command-uses-context-identity`（50）、`draft-command-rejects-invalid-path-or-missing-tenure`（50） | 2/2，0F/0E | `1793849629414`、`1793849629417` |
| `hc.compose.mission.worktree-property-test` | `runtime-selection-never-guesses-among-several`（50） | 1/1，0F/0E | `1793849629486` |
| `hc.compose.mission.model-property-test` | `every-known-role-binds-to-its-configured-model`（50）、`wrong-model-class-or-id-is-reported`（50） | 2/2，0F/0E | `1793849629478`、`1793849629479` |
| `hc.compose.owner.runtime-property-test` | `accepted-command-is-committed-before-the-conversation-effect`（50）、`failed-initial-commit-produces-no-runtime`（50） | 2/2，0F/0E | `1793849630044`、`1793849630416` |
| `hc.compose.scheduler.service-property-test` | `due-daily-schedule-starts-once-and-pause-stops-future`（50） | 1/1，0F/0E | `1793849630433` |

第四批变异检查：临时文件对 supervisor 的缺失 resolver 与 model binding 方向各写一个故意错误断言，运行得到 **2 failures / 0 errors**，临时文件已删除；日志 `/tmp/property-batch3-mutation.log`。

第五批新增 12 个属性：

| 命名空间 | 属性（trials） | 独立运行 | seed |
|---|---|---|---|
| `hc.compose.cancel.reason-property-test` | `five-reasons-are-exact-and-disjoint`（50）、`operator-cancel-requires-user-cancel-and-operator-origin`（50）、`every-descendant-is-parent-terminated`（50） | 3/3，0F/0E | `1793850084861`、`1793850084864`、`1793850084866` |
| `hc.compose.cancel.transition-property-test` | `applying-actions-are-preserved-during-operator-cancel`（50） | 1/1，0F/0E | `1793850084868` |
| `hc.compose.guards.claim-property-test` | `claim-attempt-id-is-propagated-to-every-dispatch-effect`（50）、`mismatched-dispatch-attempt-is-rejected`（50） | 2/2，0F/0E | `1793850085050`、`1793850085053` |
| `hc.core.workspace-gate-reader-property-test` | `changed-stamp-is-not-a-stable-basis`（50） | 1/1，0F/0E | `1793850089548` |
| `hc.core.swarm-stm-commands-property-test` | `register-create-claim-finish-reaches-done`（50） | 1/1，0F/0E | `1793850086575` |
| `hc.compose.fleet.actors-property-test` | `two-slots-produce-two-unique-model-starts`（50） | 1/1，0F/0E | `1793850084875` |
| `hc.compose.fleet.conflict-property-test` | `resolved-decision-stages-only-the-requested-worktree`（50）、`throwing-holder-abandons-only-that-swarm`（50） | 2/2，0F/0E | `1793850084929`、`1793850084938` |
| `hc.compose.mission.devops-property-test` | `worker-is-reused-and-report-is-verbatim`（50） | 1/1，0F/0E | `1793850085141` |

第五批变异检查：临时文件对“程序化 origin 误判为 operator cancel”和“错误 attempt-id 的 dispatch 被接受”各写一个故意错误断言，运行得到 **2 failures / 0 errors**，临时文件已删除；日志 `/tmp/property-batch5-mutation.log`。

关键不变量合并复跑：

```bash
/tmp/pixi run clojure -X:test:compose:server \
  :nses '[hc.core.ufss-test hc.core.swarm-stm-property-test \
          hc.core.relay-test hc.core.workspace-gate-property-test \
          hc.core.swarm-stm-responsibility-property-test \
          hc.compose.fleet.slice-property-test \
          hc.compose.owner.commands-property-test \
          hc.compose.owner.handlers-property-test \
          hc.compose.owner.tasks-property-test \
          hc.compose.mission.proof-property-test \
          hc.compose.scheduler.store-property-test \
          hc.compose.owner.dispatch-property-test \
          hc.compose.owner.guards-property-test \
          hc.compose.fleet.restore-property-test \
          hc.compose.fleet.router-property-test \
          hc.compose.fleet.control-property-test \
          hc.compose.mission.actors-property-test \
          hc.compose.mission.host-property-test \
          hc.compose.mission.surface-property-test \
          hc.compose.fleet.runtime-property-test \
          hc.compose.fleet.supervisor-property-test \
          hc.compose.fleet.service-property-test \
          hc.compose.mission.control-property-test \
          hc.compose.mission.worktree-property-test \
          hc.compose.mission.model-property-test \
          hc.compose.owner.runtime-property-test \
          hc.compose.scheduler.service-property-test \
          hc.compose.cancel.reason-property-test \
          hc.compose.cancel.transition-property-test \
          hc.compose.guards.claim-property-test \
          hc.core.workspace-gate-reader-property-test \
          hc.core.swarm-stm-commands-property-test \
          hc.compose.fleet.actors-property-test \
          hc.compose.fleet.conflict-property-test \
          hc.compose.mission.devops-property-test]'
```

结果：`Ran 137 tests containing 1566 assertions. 0 failures, 0 errors.` 退出 0；
日志 `/tmp/property-combined-final.log`。

Nudge：

```bash
/tmp/pixi run clojure -X:test:compose :nses '[hc.compose.nudge.policy-test]'
```

结果：`Ran 13 tests containing 157 assertions. 0 failures, 0 errors.` 退出 0；
四个属性各 60 trials，seed `1793847684145`、`1793847684179`、`1793847684291`、
`1793847684368`。日志 `/tmp/property-nudge.log`。

受保护提交确定性回归：

```bash
/tmp/pixi run clojure -X:test \
  :nses '[hc.core.workspace-gate-test hc.core.workspace-gate.prepare-test]'
```

结果：`Ran 88 tests containing 435 assertions. 0 failures, 0 errors.` 退出 0；
日志 `/tmp/property-wg-deterministic.log`。

## 6. 结论

- 模块级“有属性测试文件引用”：**85 / 104（81.7%）**（排除仅 fixture 的 `owner.state`/`workspace-gate.stamp`/`fleet.git`）。
- 模块族级：**18 / 18** 都有至少一个属性测试文件。
- 六项关键不变量：UFSS、V06、V01–V05、Relay+Swarm、Nudge 为 **PASS**；
  受保护提交 F01–F10 每项都有属性测试；F04 当前返回 `:STRUCTURE_CONFLICT`，
  但仍不能宣称每个子场景都已属性化。
- 仍有 8 个模块只有确定性测试：由父门面覆盖 4（workspace-gate board/commit/
  stamp、owner.board）、需集成故障注入 1（fleet.git）、无有意义属性 3
  （workspace-gate.error、fleet.launcher、owner.state）；另有 11 个模块没有
  直接测试引用。F07/F08/F09 的任意调度/交错覆盖仍是薄弱点。
- 第五批新增 12 个真实属性测试并实跑通过；加上前四批 54 个，共 66 个新增
  属性。没有修改 `src/`，没有伪造领域终态。

## 附录 A：全部 104 个模块状态

状态：PROP = 有属性测试文件引用；DET = 只有确定性测试；NONE = 无直接测试文件。
“代表属性”只列前三个，完整列表见对应测试文件。

| 模块 | 状态 | 属性/测试文件 | 代表属性 | 不变量/缺口 |
|---|---|---|---|---|
| `hc.core.pair-hint.canon` | PROP | `test/hc/core/pair_hint_test.clj` | canonical-map-order-is-irrelevant, canonical-set-order-is-irrelevant, digest-is-insertion-order-independent 等 6 项 | canonical/digest/prefix（非六项） |
| `hc.core.pair-hint.hint` | NONE | — | 无直接测试文件 | — |
| `hc.core.pair-hint.integration` | NONE | — | 无直接测试文件 | — |
| `hc.core.pair-hint.language` | NONE | — | 无直接测试文件 | — |
| `hc.core.pair-hint.observation` | PROP | `test/hc/core/pair_hint_test.clj` | canonical-map-order-is-irrelevant, canonical-set-order-is-irrelevant, digest-is-insertion-order-independent 等 6 项 | canonical/digest/prefix（非六项） |
| `hc.core.pair-hint.prefix` | PROP | `test/hc/core/pair_hint_test.clj` | canonical-map-order-is-irrelevant, canonical-set-order-is-irrelevant, digest-is-insertion-order-independent 等 6 项 | canonical/digest/prefix（非六项） |
| `hc.core.pair-hint.resources` | PROP | `test/hc/core/pair_hint_test.clj` | canonical-map-order-is-irrelevant, canonical-set-order-is-irrelevant, digest-is-insertion-order-independent 等 6 项 | canonical/digest/prefix（非六项） |
| `hc.core.pair-hint` | PROP | `test/hc/core/pair_hint_test.clj` | canonical-map-order-is-irrelevant, canonical-set-order-is-irrelevant, digest-is-insertion-order-independent 等 6 项 | canonical/digest/prefix（非六项） |
| `hc.core.relay.barrier` | PROP | `test/hc/core/relay_test.clj` | ledger-model-property, review-idempotency-property, review-sequence-invariants-property 等 6 项 | Relay+Swarm |
| `hc.core.relay.core` | PROP | `test/hc/core/relay_test.clj` | ledger-model-property, review-idempotency-property, review-sequence-invariants-property 等 6 项 | Relay+Swarm |
| `hc.core.relay.ledger` | PROP | `test/hc/core/relay_test.clj` | ledger-model-property, review-idempotency-property, review-sequence-invariants-property 等 6 项 | Relay+Swarm |
| `hc.core.relay.tenure` | PROP | `test/hc/core/relay_test.clj` | ledger-model-property, review-idempotency-property, review-sequence-invariants-property 等 6 项 | Relay+Swarm |
| `hc.core.relay` | PROP | `test/hc/core/relay_test.clj`, `test/hc/compose/mission/host_property_test.clj`, `test/hc/compose/nudge/policy_test.clj` | ledger-model-property, review-idempotency-property, review-sequence-invariants-property 等 11 项 | Relay+Swarm,root ledger/barrier,Nudge |
| `hc.core.scope.admission` | PROP | `test/hc/core/scope_test.clj` | valid-annotations-always-parse, parse-is-total, one-settlement-per-turn-and-monotone-budget 等 10 项 | scope 解析/投影/结算 |
| `hc.core.scope.annotation` | PROP | `test/hc/core/scope_test.clj` | valid-annotations-always-parse, parse-is-total, one-settlement-per-turn-and-monotone-budget 等 10 项 | scope 解析/投影/结算 |
| `hc.core.scope.errors` | PROP | `test/hc/core/scope_test.clj` | valid-annotations-always-parse, parse-is-total, one-settlement-per-turn-and-monotone-budget 等 10 项 | scope 解析/投影/结算 |
| `hc.core.scope.ledger` | PROP | `test/hc/core/scope_test.clj` | valid-annotations-always-parse, parse-is-total, one-settlement-per-turn-and-monotone-budget 等 10 项 | scope 解析/投影/结算 |
| `hc.core.scope.projection` | PROP | `test/hc/core/scope_test.clj` | valid-annotations-always-parse, parse-is-total, one-settlement-per-turn-and-monotone-budget 等 10 项 | scope 解析/投影/结算 |
| `hc.core.scope.records` | PROP | `test/hc/core/scope_test.clj` | valid-annotations-always-parse, parse-is-total, one-settlement-per-turn-and-monotone-budget 等 10 项 | scope 解析/投影/结算 |
| `hc.core.scope.runtime` | PROP | `test/hc/core/scope_test.clj` | valid-annotations-always-parse, parse-is-total, one-settlement-per-turn-and-monotone-budget 等 10 项 | scope 解析/投影/结算 |
| `hc.core.scope.scope` | NONE | — | 无直接测试文件 | — |
| `hc.core.scope` | PROP | `test/hc/core/scope_test.clj` | valid-annotations-always-parse, parse-is-total, one-settlement-per-turn-and-monotone-budget 等 10 项 | scope 解析/投影/结算 |
| `hc.core.steward-sphinx.cognition` | PROP | `test/hc/core/steward_sphinx/cognition_test.clj` | withdrawing-a-claim-removes-it-without-staleness, root-question-never-resolves, local-questions-open-and-resolve 等 7 项 | Sphinx 认知 |
| `hc.core.steward-sphinx.docket` | PROP | `test/hc/core/steward_sphinx/docket_test.clj` | docket-summary-shows-the-living-record, policy-normalization, candidate-normalization 等 4 项 | Sphinx docket |
| `hc.core.steward-sphinx.evidence` | PROP | `test/hc/core/steward_sphinx/evidence_test.clj` | git-evidence-identity, git-evidence-rejects-mismatched-id, non-git-evidence-needs-identity-and-summary 等 7 项 | Sphinx 证据 |
| `hc.core.steward-sphinx.frontier` | PROP | `test/hc/core/steward_sphinx/frontier_test.clj` | planning-work-prevents-capacity-frontier, capacity-change-emits-a-new-frontier, both-reasons-are-reported 等 7 项 | Sphinx 前沿 |
| `hc.core.steward-sphinx.util` | NONE | — | 无直接测试文件 | — |
| `hc.core.steward-sphinx` | PROP | `test/hc/core/steward_sphinx/cognition_test.clj`, `test/hc/core/steward_sphinx/docket_test.clj`, `test/hc/core/steward_sphinx/evidence_test.clj`, `test/hc/core/steward_sphinx/frontier_test.clj` | withdrawing-a-claim-removes-it-without-staleness, root-question-never-resolves, local-questions-open-and-resolve 等 25 项 | Sphinx 认知,Sphinx docket,Sphinx 证据,Sphinx 前沿 |
| `hc.core.swarm-stm.board` | PROP | `test/hc/core/swarm_stm_commands_property_test.clj`, `test/hc/core/swarm_stm_property_test.clj`, `test/hc/core/swarm_stm_responsibility_property_test.clj`, `test/hc/compose/nudge/policy_test.clj` | register-create-claim-finish-reaches-done, parent-never-auto-done, dead-actor-cannot-commit 等 15 项 | 命令面,V01–V06/V08,R(a) 责任集合,Nudge |
| `hc.core.swarm-stm.commands` | PROP | `test/hc/core/swarm_stm_commands_property_test.clj` | register-create-claim-finish-reaches-done | 命令面 |
| `hc.core.swarm-stm.kernel` | PROP | `test/hc/core/swarm_stm_property_test.clj` | parent-never-auto-done, dead-actor-cannot-commit, take-or-die-single-winner 等 9 项 | V01–V06/V08 |
| `hc.core.swarm-stm` | PROP | `test/hc/core/swarm_stm_commands_property_test.clj`, `test/hc/core/swarm_stm_property_test.clj`, `test/hc/core/swarm_stm_responsibility_property_test.clj`, `test/hc/compose/mission/proof_property_test.clj`, `test/hc/compose/nudge/policy_test.clj`, `test/hc/compose/workspace_test.clj` | register-create-claim-finish-reaches-done, parent-never-auto-done, dead-actor-cannot-commit 等 18 项 | 命令面,V01–V06/V08,R(a) 责任集合,W proof 不可伪造,Nudge,受保护写 roundtrip |
| `hc.core.text-location.diagnostic` | NONE | — | 无直接测试文件 | — |
| `hc.core.text-location.nearest` | PROP | `test/hc/core/text_location_test.clj` | banded-distance-matches-full-dp, where-all-matches-brute-force, where-is-unique-or-parks | 文本定位/查找 |
| `hc.core.text-location.scan` | PROP | `test/hc/core/text_location_test.clj` | banded-distance-matches-full-dp, where-all-matches-brute-force, where-is-unique-or-parks | 文本定位/查找 |
| `hc.core.text-location` | PROP | `test/hc/core/text_location_test.clj` | banded-distance-matches-full-dp, where-all-matches-brute-force, where-is-unique-or-parks | 文本定位/查找 |
| `hc.core.ufss.printer` | NONE | — | 无直接测试文件 | — |
| `hc.core.ufss.reader` | NONE | — | 无直接测试文件 | — |
| `hc.core.ufss` | PROP | `test/hc/core/pair_hint_test.clj`, `test/hc/core/ufss_test.clj`, `test/hc/compose/workspace_test.clj` | canonical-map-order-is-irrelevant, canonical-set-order-is-irrelevant, digest-is-insertion-order-independent 等 10 项 | canonical/digest/prefix（非六项）,UFSS 双射,受保护写 roundtrip |
| `hc.core.workspace-gate.applier` | NONE | — | 无直接测试文件 | — |
| `hc.core.workspace-gate.board` | DET | `test/hc/core/workspace_gate_test.clj`, `test/hc/compose/owner/board_test.clj` | 无属性测试 | — |
| `hc.core.workspace-gate.commit` | DET | `test/hc/core/workspace_gate_test.clj` | 无属性测试 | — |
| `hc.core.workspace-gate.error` | DET | `test/hc/core/workspace_gate_test.clj` | 无属性测试 | — |
| `hc.core.workspace-gate.gate` | NONE | — | 无直接测试文件 | — |
| `hc.core.workspace-gate.prepare` | PROP | `test/hc/core/workspace_gate/prepare_test.clj` | real-directory-delete-after-child-delete-is-a-separate-commit, stat-missing-is-a-structure-basis, ls-is-a-pure-probe 等 8 项 | F01/F03 相邻；顺序写 |
| `hc.core.workspace-gate.reader` | PROP | `test/hc/core/workspace_gate_reader_property_test.clj` | changed-stamp-is-not-a-stable-basis | 读取期间 stamp 变化 |
| `hc.core.workspace-gate.stamp` | DET | `test/hc/core/workspace_gate_reader_property_test.clj`, `test/hc/core/workspace_gate_test.clj` | 无属性测试 | — |
| `hc.core.workspace-gate` | PROP | `test/hc/core/workspace_gate/prepare_test.clj`, `test/hc/core/workspace_gate_property_test.clj`, `test/hc/core/workspace_gate_reader_property_test.clj`, `test/hc/compose/workspace_test.clj` | real-directory-delete-after-child-delete-is-a-separate-commit, stat-missing-is-a-structure-basis, ls-is-a-pure-probe 等 22 项 | F01/F03 相邻；顺序写,F01–F10,读取期间 stamp 变化,受保护写 roundtrip |
| `hc.compose.cancel.model` | PROP | `test/hc/compose/cancel_test.clj` | operator-cancel-preserves-fixed-outcomes, independent-failure-changes-only-the-failed-node, first-accepted-preserves-fixed-and-unknown-siblings 等 4 项 | 取消树 |
| `hc.compose.cancel.reason` | PROP | `test/hc/compose/cancel/reason_property_test.clj` | five-reasons-are-exact-and-disjoint, operator-cancel-requires-user-cancel-and-operator-origin, every-descendant-is-parent-terminated | 取消原因分类 |
| `hc.compose.cancel.transition` | PROP | `test/hc/compose/cancel/transition_property_test.clj` | applying-actions-are-preserved-during-operator-cancel | applying 保留 |
| `hc.compose.cancel` | PROP | `test/hc/compose/cancel/reason_property_test.clj`, `test/hc/compose/cancel/transition_property_test.clj`, `test/hc/compose/cancel_test.clj` | five-reasons-are-exact-and-disjoint, operator-cancel-requires-user-cancel-and-operator-origin, every-descendant-is-parent-terminated 等 8 项 | 取消原因分类,applying 保留,取消树 |
| `hc.compose.fleet.actors` | PROP | `test/hc/compose/fleet/actors_property_test.clj` | two-slots-produce-two-unique-model-starts | actor 启动 |
| `hc.compose.fleet.conflict` | PROP | `test/hc/compose/fleet/conflict_property_test.clj` | resolved-decision-stages-only-the-requested-worktree, throwing-holder-abandons-only-that-swarm | conflict |
| `hc.compose.fleet.control` | PROP | `test/hc/compose/fleet/control_property_test.clj`, `test/hc/compose/fleet/service_property_test.clj` | completed-queue-is-fifo-and-abandon-is-not-queued, joining-all-completed-results-ends-with-none, empty-fleet-join-is-none 等 4 项 | fleet queue,service 边界 |
| `hc.compose.fleet.git` | DET | `test/hc/compose/fleet/actors_test.clj`, `test/hc/compose/fleet/conflict_property_test.clj`, `test/hc/compose/fleet/e2e_conflict_test.clj` 等 | 无属性测试 | — |
| `hc.compose.fleet.launcher` | DET | `test/hc/compose/fleet/launcher_test.clj` | 无属性测试 | — |
| `hc.compose.fleet.restore` | PROP | `test/hc/compose/fleet/restore_property_test.clj` | valid-snapshot-validates-and-reversed-queue-is-rejected, running-without-runtime-is-explicitly-uncertain | snapshot/recovery |
| `hc.compose.fleet.router` | PROP | `test/hc/compose/fleet/router_property_test.clj` | fork-result-or-shortage-is-explicit, join-error-is-not-success | router 映射 |
| `hc.compose.fleet.runtime` | PROP | `test/hc/compose/fleet/actors_property_test.clj`, `test/hc/compose/fleet/runtime_property_test.clj` | two-slots-produce-two-unique-model-starts, valid-runtime-has-one-root-and-free-token, invalid-runtime-identity-is-rejected | actor 启动,runtime 隔离 |
| `hc.compose.fleet.service` | PROP | `test/hc/compose/fleet/service_property_test.clj` | empty-fleet-join-is-none, status-counts-runtimes-without-inventing-actors | service 边界 |
| `hc.compose.fleet.slice` | PROP | `test/hc/compose/fleet/slice_property_test.clj` | valid-dags-are-admitted-and-preserve-order, duplicate-ids-are-rejected, cycles-are-rejected | slice 唯一 id/DAG |
| `hc.compose.fleet.state` | PROP | `test/hc/compose/fleet/control_property_test.clj`, `test/hc/compose/fleet/restore_property_test.clj`, `test/hc/compose/fleet/state_test.clj` | completed-queue-is-fifo-and-abandon-is-not-queued, joining-all-completed-results-ends-with-none, valid-snapshot-validates-and-reversed-queue-is-rejected 等 6 项 | fleet queue,snapshot/recovery,fleet join/abandon |
| `hc.compose.fleet.supervisor` | PROP | `test/hc/compose/fleet/supervisor_property_test.clj` | mission-role-prefers-explicit-mission-role, missing-or-throwing-resolver-abandons-only-that-swarm, stop-cancels-every-indexed-attempt | supervisor |
| `hc.compose.guards.claim` | PROP | `test/hc/compose/guards/claim_property_test.clj` | claim-attempt-id-is-propagated-to-every-dispatch-effect, mismatched-dispatch-attempt-is-rejected | claim 归属 |
| `hc.compose.guards.priority` | PROP | `test/hc/compose/guards/scheduler_test.clj` | attempt-ids-are-unique, quota-bounds-one-round, wake-notifications-coalesce 等 5 项 | 调度/配额/唤醒 |
| `hc.compose.guards.scheduler` | PROP | `test/hc/compose/guards/scheduler_test.clj`, `test/hc/compose/owner/guards_property_test.clj` | attempt-ids-are-unique, quota-bounds-one-round, wake-notifications-coalesce 等 7 项 | 调度/配额/唤醒,guard 优先级/配额 |
| `hc.compose.mission.actors` | PROP | `test/hc/compose/mission/actors_property_test.clj` | dead-actor-rebirth-uses-a-new-agent-id-and-empty-context | slot rebirth |
| `hc.compose.mission.control` | PROP | `test/hc/compose/mission/control_property_test.clj` | review-command-uses-context-identity, draft-command-rejects-invalid-path-or-missing-tenure | command 适配 |
| `hc.compose.mission.devops` | PROP | `test/hc/compose/mission/devops_property_test.clj` | worker-is-reused-and-report-is-verbatim | devops 复用 |
| `hc.compose.mission.host` | PROP | `test/hc/compose/mission/host_property_test.clj` | each-root-finish-removes-one-ledger-key | root ledger/barrier |
| `hc.compose.mission.model` | PROP | `test/hc/compose/mission/model_property_test.clj` | every-known-role-binds-to-its-configured-model, wrong-model-class-or-id-is-reported | 模型归属 |
| `hc.compose.mission.proof` | PROP | `test/hc/compose/mission/proof_property_test.clj` | arbitrary-values-cannot-forge-the-w-proof, proof-token-is-a-single-identity | W proof 不可伪造 |
| `hc.compose.mission.surface` | PROP | `test/hc/compose/mission/surface_property_test.clj` | plan-context-matches-committed-state | surface 映射 |
| `hc.compose.mission.worker` | PROP | `test/hc/compose/mission/worker_test.clj` | worker-lifecycle-keeps-one-task-model-identity | worker 生命周期 |
| `hc.compose.mission.worktree` | PROP | `test/hc/compose/mission/worktree_property_test.clj` | runtime-selection-never-guesses-among-several | runtime 选择 |
| `hc.compose.mission` | PROP | `test/hc/compose/mission/actors_property_test.clj`, `test/hc/compose/mission/control_property_test.clj`, `test/hc/compose/mission/devops_property_test.clj`, `test/hc/compose/mission/host_property_test.clj`, `test/hc/compose/mission/model_property_test.clj`, `test/hc/compose/mission/proof_property_test.clj`, `test/hc/compose/mission/surface_property_test.clj`, `test/hc/compose/mission/worker_test.clj`, `test/hc/compose/mission/worktree_property_test.clj` | dead-actor-rebirth-uses-a-new-agent-id-and-empty-context, review-command-uses-context-identity, draft-command-rejects-invalid-path-or-missing-tenure 等 12 项 | slot rebirth,command 适配,devops 复用,root ledger/barrier,模型归属,W proof 不可伪造,surface 映射,worker 生命周期,runtime 选择 |
| `hc.compose.nudge.policy` | PROP | `test/hc/compose/nudge/policy_test.clj` | active-central-turn-end-nudges-or-exits, central-nudge-lists-every-ledger-entry, worker-nudge-tracks-global-progress 等 4 项 | Nudge |
| `hc.compose.owner.board` | DET | `test/hc/compose/owner/board_test.clj` | 无属性测试 | — |
| `hc.compose.owner.commands` | PROP | `test/hc/compose/owner/commands_property_test.clj` | same-id-same-payload-is-idempotent, same-id-different-payload-is-rejected, retired-epoch-is-rejected-and-next-epoch-accepted | command id/epoch |
| `hc.compose.owner.dispatch` | PROP | `test/hc/compose/owner/dispatch_property_test.clj` | same-live-attempt-starts-once-and-cancels-once | dispatch 单次 |
| `hc.compose.owner.guards` | PROP | `test/hc/compose/owner/guards_property_test.clj` | highest-enabled-priority-wins-deterministically, quota-exhaustion-requeues | guard 优先级/配额 |
| `hc.compose.owner.handlers` | PROP | `test/hc/compose/owner/handlers_property_test.clj` | duplicate-terminal-fact-is-ignored, arbitrary-terminal-sequences-keep-one-fact-per-key | terminal 幂等 |
| `hc.compose.owner.mailbox` | PROP | `test/hc/compose/owner/mailbox_test.clj` | every-accepted-message-is-processed | mailbox 处理 |
| `hc.compose.owner.runtime` | PROP | `test/hc/compose/owner/runtime_property_test.clj` | accepted-command-is-committed-before-the-conversation-effect, failed-initial-commit-produces-no-runtime | runtime 提交/失败 |
| `hc.compose.owner.state` | DET | `test/hc/compose/guards/owner_integration_test.clj`, `test/hc/compose/nudge/owner_integration_test.clj`, `test/hc/compose/owner/assistant_prefix_test.clj` 等 | 无属性测试 | — |
| `hc.compose.owner.store` | PROP | `test/hc/compose/owner/runtime_property_test.clj`, `test/hc/compose/owner/store_test.clj` | accepted-command-is-committed-before-the-conversation-effect, failed-initial-commit-produces-no-runtime, compare-and-set-matches-version | runtime 提交/失败,CAS |
| `hc.compose.owner.tasks` | PROP | `test/hc/compose/owner/tasks_property_test.clj` | independent-tools-return-one-outcome-per-branch, required-steps-succeed-only-when-all-succeed | 任务组合器 |
| `hc.compose.owner` | PROP | `test/hc/compose/owner/commands_property_test.clj`, `test/hc/compose/owner/dispatch_property_test.clj`, `test/hc/compose/owner/guards_property_test.clj`, `test/hc/compose/owner/handlers_property_test.clj`, `test/hc/compose/owner/mailbox_test.clj`, `test/hc/compose/owner/runtime_property_test.clj`, `test/hc/compose/owner/store_test.clj`, `test/hc/compose/owner/tasks_property_test.clj` | same-id-same-payload-is-idempotent, same-id-different-payload-is-rejected, retired-epoch-is-rejected-and-next-epoch-accepted 等 14 项 | command id/epoch,dispatch 单次,guard 优先级/配额,terminal 幂等,mailbox 处理,runtime 提交/失败,CAS,任务组合器 |
| `hc.compose.reports.acceptance` | PROP | `test/hc/compose/reports/acceptance_test.clj`, `test/hc/compose/reports/protocol_test.clj` | repeated-delivery-appends-once, terminated-parent-acknowledges-every-delivery, report-protocol-loses-nothing | 报告协议 |
| `hc.compose.reports.delivery` | PROP | `test/hc/compose/reports/delivery_test.clj`, `test/hc/compose/reports/protocol_test.clj` | report-lifecycle-stays-consistent, report-protocol-loses-nothing | 报告协议 |
| `hc.compose.reports.report` | PROP | `test/hc/compose/reports/delivery_test.clj`, `test/hc/compose/reports/report_test.clj` | report-lifecycle-stays-consistent, llm-report-text-survives-validation, raw-truncation-never-exceeds-the-original | 报告协议 |
| `hc.compose.retry.error` | PROP | `test/hc/compose/retry/error_test.clj` | only-transient-is-retryable | 重试策略 |
| `hc.compose.retry.planner` | PROP | `test/hc/compose/retry/planner_test.clj` | retry-at-is-bounded-and-fixed, claim-increments-both-budgets-once, unknown-effects-never-retry-without-opt-in | 重试策略 |
| `hc.compose.retry.policy` | PROP | `test/hc/compose/retry/planner_test.clj`, `test/hc/compose/retry/policy_test.clj` | retry-at-is-bounded-and-fixed, claim-increments-both-budgets-once, unknown-effects-never-retry-without-opt-in 等 4 项 | 重试策略 |
| `hc.compose.scheduler.core` | PROP | `test/hc/compose/scheduler/core_test.clj` | missed-days-never-catch-up, at-most-one-active-run-per-schedule | 调度错过/单活跃 |
| `hc.compose.scheduler.model` | PROP | `test/hc/compose/scheduler/core_test.clj` | missed-days-never-catch-up, at-most-one-active-run-per-schedule | 调度错过/单活跃 |
| `hc.compose.scheduler.service` | PROP | `test/hc/compose/scheduler/service_property_test.clj` | due-daily-schedule-starts-once-and-pause-stops-future | service tick/pause |
| `hc.compose.scheduler.store` | PROP | `test/hc/compose/scheduler/service_property_test.clj`, `test/hc/compose/scheduler/store_property_test.clj` | due-daily-schedule-starts-once-and-pause-stops-future, memory-store-roundtrips-arbitrary-state, last-save-wins-and-pending-runs-survive | service tick/pause,store roundtrip |
| `hc.compose.scheduler.time` | PROP | `test/hc/compose/scheduler/time_test.clj` | next-daily-times-stay-on-the-rule, weekly-times-fall-on-selected-days | 时区规则 |
| `hc.compose.workspace.board` | PROP | `test/hc/compose/workspace_test.clj` | protected-write-roundtrip | 受保护写 roundtrip |
| `hc.compose.workspace.error` | NONE | — | 无直接测试文件 | — |
| `hc.compose.workspace.session` | PROP | `test/hc/compose/workspace_test.clj` | protected-write-roundtrip | 受保护写 roundtrip |
| `hc.compose.workspace` | PROP | `test/hc/compose/workspace_test.clj` | protected-write-roundtrip | 受保护写 roundtrip |
