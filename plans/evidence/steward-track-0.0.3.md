# Steward Track A — 验收证据（0.0.3）

日期：2026-11-09
范围：A 线（Steward 守望层）代码、测试与装配状态
证据原则：本文件只记录本 worker 亲自运行过的数字。他人运行而本 worker 未复跑的项明确标 `PEER-REPORTED` 或 `NOT RUN`，不冒充实测。

## 1. 组件清单与 system.clj 接入状态

| 组件 | 职责 | system.clj 状态 |
|---|---|---|
| `hc.compose.steward` | 纯 Steward 状态层：intent/capacity/cognition/frontier/docket/links；决策与 Mission 生命周期迁移 | `WIRED`。`system.clj` require 并用于 `steward/fleet-capacity`（fleet-state 视图）；`steward-runtime` 使用同一套状态 API。 |
| `hc.compose.steward.loop` | 纯 tick：观测 → meditate → frontier → decide → plan/fork/join/report → persist；effect 指纹与不变量检查 | `WIRED`（经 `steward-runtime`，非 `system.clj` 直接 require）。`steward-runtime` 调用 `loop/tick`、`loop/effect-fingerprint`、`loop/check-invariants`。 |
| `hc.compose.supervisor` | 进程级监督树：子组件启动、重启、停止与状态 | `WIRED`。`system.clj` 创建 `process-supervisor`，`:children [steward-loop-child]`，启动/停止/状态都已接线。 |
| `hc.server.app.steward-store` | 单文档 CAS 持久化：`steward/repository` / `:steward-state` / id `"current"` | `WIRED`。`system.clj` 创建 `steward-store-instance`，写入 `steward-ref` atom；runtime 与 `:steward/*` 命令共享。 |
| `hc.server.app.steward-git` | Git 观测适配：把 `git log` 事实变成 Sphinx evidence | `WIRED`。`system.clj` 构造 `steward-git-log-since`，作为 `:git` 注入 runtime。 |
| `hc.server.app.steward-model` | model-call 适配：meditate/decide effect → ModelPort/gateway → revision/decision + usage | `NOT-WIRED`。`system.clj` 目前仍传 `:model-fn nil`、`:model-port nil`（约 5093–5104 行，且注释仍写 “steward_model.clj is not delivered yet”），也没有 require 该 ns。runtime 会记录 `:MODEL_PORT_UNAVAILABLE`，不会真正调用模型。 |
| `hc.server.app.steward-runtime` | Store/Git/fleet/Plan/effect 运行时；不拥有线程，由 supervisor child 驱动 | `WIRED`。`steward-start-fn` 调用 `start!`，`steward-loop-child` 的 tick/start/stop 接到 runtime；health/snapshot、fork/join 回调、`:steward/*` 命令平面也引用 runtime/store。 |
| `hc.server.app.background-loops` | 周期循环 child spec（Steward loop、Fleet GC、Retention GC） | `WIRED`。`system.clj` 用 `background-loops/steward-loop-child` 构造 `:steward-loop`，交给 `process-supervisor`。 |
| `hc.server.app.retention-gc` | C 线保留/Blob GC 服务 | `WIRED`，但生命周期是**直接启动/直接停止**（自己有 `ScheduledExecutorService`），不是 `process-supervisor` 的周期 child；`process-supervisor` 的 `:children` 只有 `steward-loop-child`。`system.clj` 约 5146 行启动、6038 行停止，注释明确避免第二次 supervisor tick。 |

## 2. 本 worker 亲自实测

命令统一使用串行、`timeout -k 10 300`，CLI 为 `.pixi/envs/default/bin/clojure`。

| ns | 命令 | tests | assertions | failures | errors | Wall | 备注 |
|---|---|---|---:|---:|---:|---:|---|
| `hc.server.app.steward-model-test` | `-M:test:server -n hc.server.app.steward-model-test` | 23 | 250 | 0 | 0 | 2.44s | 当前工作树；包含新增的分类边界测试与 usage 审计测试。 |
| `hc.server.app.prompts-test` | `-M:test:server -n hc.server.app.prompts-test` | 23 | 211 | 0 | 0 | 1.25s | marker/decoration 回归；早于最后一次边界测试新增，但 prompts 侧未再改动。 |
| `hc.server.app.effect-fingerprint-consistency-test` | `-M:test:server:compose -n hc.server.app.effect-fingerprint-consistency-test` | 9 | 152 | 0 | 0 | 2.05s | 对端已把旧 `[:unknown ...]` 断言改为异常语义后的复跑结果。 |

### 2.1 状态冲突裁决（22/245 2F vs 23/250 0F）

`eng-w2-e5-tests` 曾报告 `steward-model-test` 为 22 tests / 245 assertions / 2 failures，失败在 `usage-failure-paths-stay-unknown`，输入 `"{:revision"` 被归为 `:STEWARD_MODEL_INVALID_SHAPE`。

裁决：**测试侧/并发时序问题，不是当前实现 bug。**

- `parse-text` 先读 `(ufss/read-str-safe text {:json true})`；只要 reader 返回 `:error`，就归 `:STEWARD_MODEL_UNPARSEABLE`，不会进入 map 检查。`"{:revision"` 是截断 EDN，reader 返回 EOF error。
- 22/245 这个计数正好对应新增边界测试之前的版本；本 worker 在该窗口前后改过测试文本并立即重跑。之后新增了 `unparseable-vs-invalid-shape-boundary`，当前计数为 23/250。
- 当前实测三档边界全部正确；同一条命令在当前工作树得到 0F/0E。若对端仍看到 2F，说明它跑在旧文件、旧 classpath 或与编辑并发，建议以当前工作树重跑。

## 3. 语义契约要点

| 契约 | 一句话 | 证据位置 |
|---|---|---|
| model-only 指纹 | `effect-fingerprint` 只接受 `:model/meditate` / `:model/decide`；域外抛 `:EFFECT_OUTSIDE_MODEL_DOMAIN`，不再返回 `[:unknown t]`；通用去重在 `hc.compose.steward.loop/effect-fingerprint`。 | `src/hc/server/app/steward_model.clj` `effect-fingerprint`；测试 `effect-fingerprint-is-model-only`。 |
| 角色提示词 marker | 单一事实源是 `hc.server.app.prompts/role-prompt-marker`（`[[hc-role-prompt]]`）；`decorate-request` 精确匹配 `marker` 或 `marker+\n`/`marker+\r\n` 后跳过 role prompt 注入，但仍注入工具。`steward_model` 用该常量写 system 前缀。 | `src/hc/server/app/prompts.clj` `role-prompt-marker` / `role-prompt-present?` / `decorate-request`；`src/hc/server/app/steward_model.clj` `system-text`；测试 `decoration-adds-each-role-prompt-exactly-once`、`decoration-without-program-role-is-a-no-op`、`prompt-overrides-are-used`。 |
| 形状错误统一 | 所有形状/字段拒绝走 `invalid-shape`，返回 `{:ok? false :error {:code :message :details}}`；`:details` 恒含 `:task`、`:missing`、`:invalid`、`:unexpected`（候选错误另含 `:candidate-id`/`:candidate-index`/`:expected`）。 | `src/hc/server/app/steward_model.clj` `invalid-shape` / `validate-meditate` / `validate-decide` / `normalize-candidate`；测试 `invalid-shapes-are-refused`、`invalid-shape-envelopes-are-uniform`。 |
| usage 未知语义 | 缺失 token 恒 `:unknown`，绝不写 `0`/`nil`/`"0"`；`:source` 只 `:provider`/`:unknown`；不做 token 算术。 | `src/hc/server/app/steward_model.clj` `usage-of` / `unknown-usage`；测试 `usage-missing-fields-are-unknown`、`usage-failure-paths-stay-unknown`。 |
| 重试 usage | 只取末次 attempt 的 usage；末次成功但无 usage → `:unknown`；不把前次失败 attempt 的 token 累加。 | `src/hc/server/app/steward_model.clj` `call-with-retries`；测试 `retry-success-without-final-usage-stays-unknown`。 |
| LRU usage | 命中返回原成功 envelope（含原 usage），不新增模型调用、不虚增 token。 | `src/hc/server/app/steward_model.clj` `cache-get`/`cache-put`；测试 `usage-on-cache-hit-is-the-original-cost`。 |
| 解析分类 | 读不出唯一 EDN map → `:STEWARD_MODEL_UNPARSEABLE`；读出来但形状/字段不合规 → `:STEWARD_MODEL_INVALID_SHAPE`。 | `src/hc/server/app/steward_model.clj` `parse-text`；测试 `unparseable-vs-invalid-shape-boundary`。 |

### 3.1 三档解析边界实测

| 输入 | 实测码 | 语义 |
|---|---|---|
| `"{:revision"` | `:STEWARD_MODEL_UNPARSEABLE` | 截断 EDN，reader EOF。 |
| `"not ednn at all"` | `:STEWARD_MODEL_UNPARSEABLE` | 散文/非唯一 EDN。 |
| `"{}"` / `"{:revision 1}"` / decide `"{:request-id \"x\"}"` | `:STEWARD_MODEL_INVALID_SHAPE` | 能读出 map，但缺字段或字段类型不合规。 |

## 4. 已知限制与风险

1. **重试 token 不累加**：usage 是末次 attempt 的 usage；前次失败 attempt 的 token 不会汇总。若需要总成本，调用方必须自行按 attempt 记录聚合；这是已知行为，不是静默清零。
2. **LRU 命中返回原 usage**：适配器不新增 token，但消费方必须把命中视为 replay，而不是第二次计费调用；当前没有 `:cached?` 标记，envelope 保持逐字一致。
3. **flat legacy usage 的 source**：没有 `:sources` 的扁平 token map 被标为 `:provider`（视为调用方直接提供计数）；report-shaped 但缺 provenance 或 `:estimated` 会落为 `:unknown`，适配器不输出 `:estimated`。
4. **无 token 算术**：审计确认没有 `(or x 0)`、`(int ...)`、`(reduce + ...)` 作用在可能为 `:unknown` 的 token 上；唯一的 `+` 是总 deadline。未发现把 unknown 静默记 0 的代码。
5. **`validate-decide` 曾返回裸 error map**：候选字段非法时曾返回 `(:error invalid)` 而不是 `invalid`，导致 `:ok?` 为 nil。已修，并由 `invalid-shape-envelopes-are-uniform` 对四种非法输入做统一键集守卫。
6. **`steward-model` 尚未接入 `system.clj`**：这是当前 A 线最大的真实缺口；runtime 以 nil model port 启动，meditate/decide 只会产生 `:MODEL_PORT_UNAVAILABLE` 诊断，不会产生模型 revision/decision。
7. **raw ModelPort 路径没有 canonical 程序工具**：raw port 不装饰；适配器提供 capability tool face 和自带提示词，但 canonical `clj-steward` 程序工具需要 gateway 路径的 `decorate-request`。生产装配应注入 gateway port。
8. **retention-gc 生命周期**：它被直接启动/停止，不进入 `process-supervisor` 的周期 child 列表；这与“由 supervisor 托管”的口头假设不同，证据见 `system.clj` 的 `:children [steward-loop-child]` 与直接 `retention-gc/start!`/`stop!`。

## 5. 未验证项（需要 `system.clj` 装配完成后验证）

以下项目本 worker **没有**亲自启动真实 system 或对应集成测试；这里只记录代码接线事实，不声称运行通过：

- `steward-loop` 的真实启动与 tick：代码中 `background-loops/steward-loop-child` + `steward-runtime/start!` 已接线，但未由本 worker 跑真实 system。
- `:interval-ms` / `:plan-stall-ms` 透传：代码从 `config` 读取并传入 runtime/child，未做运行时观测。
- `:steward/*` 命令经 router 可用：`steward-ref`、`steward-runtime`、`commands/handle-steward!` 的装配存在，但未做端到端命令验证。
- `stop!` 顺序：`system.clj` 先 supervisor，再 retention-gc，再 steward-runtime，且核对 terminated；未在本 worker 的进程生命周期测试中实际执行。
- 真实 Git/fleet/Plan/model provider 的联合闭环：因 `steward-model` 仍为 `NOT-WIRED`，当前无法验证 meditate/decide 到真实模型再回到 Docket 的完整闭环。

## 6. Peer-reported / not re-run

- `hc.compose.steward-test`：devops（经 Main）曾在累加器修复前报告 2 errors；本 worker 修复后**未亲自复跑**，不声称当前绿。
- `hc.server.app.steward-store-test`：本 worker **未运行**；由 devops/其他 worker 负责。
- `hc.compose.steward.loop-test`、`hc.server.app.steward-runtime-test`、`hc.server.app.steward-git-test`、`hc.server.app.steward-closed-loop-test`、`hc.server.app.background-loops-test`、`hc.server.app.retention-gc-test`：本 worker **未运行**；不写数字。
- `hc.server.app.effect-fingerprint-consistency-test` 的旧版 8/30 0F/6E 是旧断言状态；当前 9/152 0F/0E 为本 worker 亲测。

## 7. 当前判定

**A 线核心代码、Store/loop/supervisor/runtime 装配与适配器测试可交付；但 `steward-model` 尚未接入 `system.clj`，真实守望闭环仍缺少模型调用，因此当前是“代码可交付、闭环未闭合”，需先完成 model-fn/model-port 装配，再验证 runtime/loop 启动、命令平面与 stop 顺序。**

## 8. 0.0.3 冻结后复验（system.clj 接线）

本节取代第 1 节中 `steward-model = NOT-WIRED` 的旧状态与第 7 节“闭环未闭合”的旧判定。以下结论来自冻结树只读检查；`--check` 由 Main 侧运行，本 worker 未重跑 JVM。

冻结证据：`src/hc/server/app/system.clj` mtime `2026-11-09 09:31:22`、305,862 B；`--check` exit 0。
Main 提供的 `:steward` 字段原文：

```text
:steward {:configured? true :started? true :status :started}
```

### 8.1 `steward-model` 接线（WIRED）

- `system.clj:64` 已 require `[hc.server.app.steward-model :as steward-model]`。
- `steward-start-fn`（约 `system.clj:5096-5138`）读取 `@model-port-ref`；port 非 nil 时调用 `steward-model/model-fn`：
  - `:port` = 该真实 port；
  - `:model-id` = `(get-in config [:models :roles :main :model])`（约 `system.clj:5103-5105`）；
  - `:roles` 未在 `system.clj` 显式传入，但 `steward_model.clj` 的 `default-role-targets` 正是 `{:meditator :main :steward :main}`，`model-fn` 合并该默认值；因此有效 roles 与要求一致。
- port 来源：`model-port-ref` 由 `diagnostics-capture/recording-port` 包装真实 `model-adapter/create-model-port` 后写入（`system.clj:4036-4042`；启动路径 `4764-4774`）。gateway 使用同一个 `@model-port-ref`（`2252`、`4928`），所以 Steward 与 gateway 不是两套 provider 连接。
- 过时注释已删除；当前注释明确写“live port enables the real meditate/decide calls；without one, nil keeps startup alive and every effect records :MODEL_PORT_UNAVAILABLE instead of failing”（`system.clj:5098-5100`）。
- `:model-port nil` 仍出现在 runtime start map，但 `:model-fn` 在 port 存在时非 nil；`steward_runtime/model-callable` 优先取 `:model-fn`，因此不是 nil 占位。

### 8.2 nil 兜底

- `@model-port-ref` 为 nil 时，`steward-model-fn` 为 nil；`steward-runtime/start!` 以无模型模式启动，系统启动路径不受阻。
- `:MODEL_PORT_UNAVAILABLE` 只在 effect 真正进入 `dispatch-model!` 且 `model-callable` 返回 nil 时记录（`steward_runtime.clj:369-378`）；`--check` 不产生 effect，因此不会出现该诊断实例。
- `steward-model/model-fn` 构造异常只写 `:steward-model-error` 并返回 nil model-fn，不阻断启动（`system.clj:5101-5112`）。

### 8.3 配置透传与停止顺序（WIRED，待全量复跑）

- `:interval-ms` 同时传给 runtime 与 supervisor child（`system.clj:5127/5129/5149`）；`:plan-stall-ms` 进入 runtime config（`5132`）；`:stop-timeout-ms 5000` 与 child 的 7000ms 上限已对齐（`5135`）。
- 停止顺序（实际行号）：supervisor（含 `steward-loop` child）→ steward runtime 的兜底停止 → retention-gc → fleet-gc → scheduler → fleet service → owners → 其他组件（`system.clj:5991-6082`）。fleet service 的停止先于 owners（`6078-6082`）；store 只在 supervisor/retention/steward 三者给出终止证据后关闭（`6120-6154`）。
- 新增的 `:stop-result` 幂等缓存：`stop!` 入口命中缓存直接返回（`system.clj:5953-5954`），结果末尾写回（`6177-6178`）。这是新加行为，本 worker 尚未复跑，标为 **WIRED + 待全量验证**。

### 8.4 仍未由本 worker 验证

- `:model/meditate` / `:model/decide` 在真实 system 中到达 model-fn 的 end-to-end 计数。
- 真实 system 的 `steward-runtime/health` 在注入真实 port 后不再出现 `:MODEL_PORT_UNAVAILABLE` 的实例级验证（代码路径已确认，但没有本 worker 的运行证据）。
- 全量 stop 顺序与 `:stop-result` 幂等的进程级复跑。

## 9. 0.0.3 收尾期变更（并行追加）

### 9.1 启动期 `:state :building` 与 Steward 的关系（代码阅读判断）

- `ensure-history-index!` 把必需的索引重建放到 daemon 线程，立即返回 `{:deferred? true :state :building}`（`system.clj:4389-4393`；`.setDaemon true` 在 `4450`）。
- `history-index-health` 在 bootstrap 仍 deferred 时把 observation 标成 `:state :building`（`system.clj:4582-4584`），但它是 `health-observations` 而非 `component-errors`（`4586-4605`）。
- `/healthz` 的 `ok?` 只由 `store-open?`、非 nil `gateway-stats`、空 `component-errors` 决定（`health.clj:39-46`）。`:state :building` 不会把 `:ok` 变 false；`history-index-component-error` 的注释也明确这一点。
- Steward 的 fork/join 与 `:steward/*` 命令面不依赖索引就绪：
  - `:steward/*` 走 `steward-store/load-state` / `commit!` 与纯 `steward` 状态（`commands.clj:1825-1871`），不读 history index。
  - fork 走 `fleet/fork!`（`system.clj:5247-5249`），join 回调走 `steward-runtime/on-join-result!`（`5174-5182`）；两者都不依赖 `history-index`。
  - 索引只服务 `:history/search`。
  这是本 worker 读代码所得判断，不是运行时实验。

### 9.2 `stop!` 门控升级（Main 实测 + 本 worker 代码核对）

- 当前 `writers-stopped?` 的证据是：supervisor `:ok?`、retention `:terminated?`、steward `:terminated?`、history-index bootstrap 线程不再 alive（`system.clj:6271-6279`）。
- `stop!` 的 `:stop-result` 幂等缓存：入口命中直接返回（`6081-6082`）；结果末尾写回（`6332-6333`）。
- Main 提供的实测停止顺序：`[:supervisor :steward :retention-gc :fleet-gc :fleet :owner :store]`；`system-lifecycle-test` 从 4/55 变为 **5/65**，新增 `history-index-bootstrap-is-daemon-and-its-abandonment-is-reported`。本 worker **未复跑**该测试，标 `PEER-REPORTED (source: Main)`。
- 代码核对：fleet service 停止在 owners 之前（`6207-6210`），store 在终止证据之后关闭（`6295-6297`）。

### 9.3 历史索引重建风险：代码状态与 Main 描述不一致（重要）

Main 曾要求把以下风险写成“已知未决”：后台重建线程不在 `writers-stopped?` 门控内、`stop!` 先关库再 interrupt、`rebuild!` 在 `index-session!` 前无取消检查、无 single-flight，因此关库后仍可能写入。

本 worker 在**当前冻结树**读到的代码与该描述不一致：

- 取消标志、interrupt、**有界 join 在 store close 之前**执行（`system.clj:6246-6257`）；join 后仍 alive 的线程会进入 `bootstrap-terminated?`，False 时 `close-allowed?` 为 false，store 不关闭（`6272-6297`）。
- `rebuild!` 逐会话检查 `cancelled-now?`（`history/index.clj:875-881`），并在取消时返回 `:cancelled? true` / `:state :building`（`900-908`）。
- `ensure` 在开始前检查取消标志（`history/index.clj:1096-1104`），并把 `cancelled?` 传入 `rebuild!`（`1132-1134`）。
- `rebuild-locks` 按 store 串行化 rebuild，形成 single-flight（`history/index.clj:764-768`）。

因此，这条风险在当前冻结树的代码层面应标为 **CODE-ADDRESSED, RUNTIME-UNVERIFIED**，不能写成“仍然未修”的 OPEN 风险。若 owner 的修复尚未进入目标发布分支，需由 owner 确认后再改回；本 worker 未运行该场景。Steward 侧无论哪种状态都不受影响：Steward 不写 store 的 search 分区。

### 9.4 `steward-model` 状态

保持第 8 节的 **WIRED**：真实 `@model-port-ref`、`model-fn` 构造、`:meditator/:steward → :main` 的有效默认 roles、nil 兜底降级为 effect 到达时的 `:MODEL_PORT_UNAVAILABLE`。本次未发现推翻该结论的新证据。
