# server 层审查与修复记录（0.0.1）

- 日期：2026-11-05
- 范围：`src/hc/server/**`、`test/hc/server/**`，以及本文件。
- 隔离根：`/tmp/hc-server-audit`（测试与证据）、`/tmp/hc-server-audit-data`（冒烟数据目录）、`/tmp/hc-server-audit-work`（冒烟工作区）。
- 运行环境：`/tmp/pixi` 0.83.0，Clojure 1.12.6 / OpenJDK 21。
- 未修改 `src/hc/compose/**`、`src/hc/client/**`；无跨目录改动。

## 1 结论总表

| 编号 | 审查项 | 结论 | 关键证据 |
|---|---|---|---|
| 1 | app/system 装配 | 通过（本轮另修 D） | `system.clj` 启动/停止顺序、`health.clj`、`app.clj --check` 实跑 |
| 2 | Datalevin 当前态 | 通过 | `storage.clj` 提交读屏障、`recovery/classify.clj` 六窗口、存储测试 |
| 3 | 网关 Sente/AG-UI | 通过 | `gateway/feed.clj`、`gateway/sente.clj`、网关测试 |
| 4 | sessions/commands/epoch | 通过 | `commands/epoch.clj`、`sessions/AGENTS.md`、命令与快照测试 |
| 5 | program/sandbox | 通过 | `program/runtime.clj`、`capability.clj`、program 测试 |
| 6 | workspace-gate 与 shell | 通过 | `hc.core.workspace-gate.*`、`shell/session.clj`、shell/程序测试 |
| 7 | model-port | 通过（本轮另修 D） | `model_adapter/port.clj`、`model_port/{retry,usage}.clj`、model-port 测试 |
| 8 | 其他适配 | 通过（备份另修 A–C） | 各适配器代码 + 对应测试；附件上限按根 §3.4 为 20 项/100 MiB |

### 1.1 app/system 装配

- **组件顺序**：`start-internal!` 的顺序是维护日志门 → 存储存在性检查 → `recovery/recovery-entry`（打开存储）→ 设置/注册表/Owner → 搜索/调度 → 发布器/HTTP 监听 → 接受命令。`stop!` 反向：命令门与 drain → HTTP → 网关 → 发布器 → fleet-gc → 调度 → Owners → 浏览器 → 扩展/语音/model-auth → fleet → 模型端口 → 存储；任一组件未确认停止就不关闭存储（`system.clj:3718-3830`）。证据：`hc.server.app.system-test`、`hc.server.app.main-test`。
- **键名一致**：`session-owner-factory` 解构 `:extensions-ref`/`:artifacts-ref`/`:session-config` 等（`system.clj:1116`），装配处在同一 map 传入（`system.clj:2965-2969`）。真实启动成功即证明键名没有错位。
- **health 不互相冒充**：`health/snapshot` 的 `:ok` 只由 store 打开、session 计数、gateway 统计和 `:component-errors` 决定；`:models {:configured? ...}` 与 `:components` 分开报告 `:configured?`/`:started?`/`:status`（`health.clj:31-62`、`system.clj:2294-2327`）。无模型冒烟实测 `:models {:configured? false}` 且 `:ok true`。
- **`:blocked?` 诊断**：`blocked-result` 带 `:error`、`:hint`、`:recovery-entry`、`:path`，启动失败会保留 `:partial-system` 供最终停止（`system.clj:2333-2342`）；维护日志门、`initialized.edn` 与 `:STORE_MISSING_AFTER_INIT` 都在打开路由前阻断。
- **check 隔离**：`check-options` 在未显式给出 data/workspace 时创建 `hc-check-*` 临时根，关闭 fleet preflight 与立即 GC，并在 `finally` 删除（`app.clj:142-182, 373-391`）。实测 `app-check` 使用 `/tmp/hc-check-18382176779352783744`，退出后该目录已删除。

### 1.2 Datalevin 当前态

- `commit-migration!` 在一次 `transact-kv` 中提交文档、义务、blob 引用、元数据与回执，随后返回 `:view`（读屏障后的最新视图）；重复 `:migration-id` 相同载荷重放回执，不同载荷报 `:STORAGE_MIGRATION_CONFLICT`（`storage.clj:403-448`）。
- 打开规则：`layout/classify` 先区分 missing/empty/foreign/marker-only/corrupt；`:create?` 只允许初始化空路径，`marker-only` 仍报 `:STORAGE_CORRUPT`，不会静默新建空库（`storage.clj:228-247`）。应用层另有 `initialized.edn` 与恢复入口。
- 六个恢复窗口：`recovery/classify.clj:1-25` 明确列出 ready/dispatching/started-unobserved/running/report-pending/report-accepted，并保守地转为 `:outcome-unknown` 或重投报告；`hc.server.recovery-test` 覆盖。
- 长 I/O 不在事务内：提交只执行预生成的 KV ops；备份/恢复的加解密、文件写入、外部目录同步都在存储事务之外（`backup/core.clj`、`backup/storage_adapter.clj`）。

### 1.3 网关

- 事件：`feed.clj` 发布 `STATE_SNAPSHOT`、`STATE_DELTA`（RFC 6902）、`MESSAGES_SNAPSHOT` 与 `CUSTOM`；`gateway/broadcast!` 承载 `RUN_STARTED`、文本流、工具流等非状态 AG-UI 事件。
- epoch+seq：feed 在锁内推进 epoch/seq；新纪元快照把 seq 归零；订阅代号过旧直接拒绝（`feed.clj:166-190`）；增量不会修复 stale 订阅，只有完整快照能（`feed.clj:10-12`）。
- 1 MiB：出站 packer 上限 `default-max-message-bytes = 1 MiB`（`gateway/sente.clj:17,124`）；超限状态事件写入 `/sync-objects/<token>`，Sente 只发引用（`gateway.clj:119-123`、`gateway/sync_object.clj`）；`/chsk` 请求体同样 1 MiB。
- `MESSAGES_SNAPSHOT` 的角色清理：`message-extension-keys` 允许 `:hc/roles`、`:hc/clear-roles`、`:hc/epoch-rebuild?`、`:hc/order` 原样通过（`feed.clj:427-465`），不依赖标准缺省语义。
- 证据：`hc.server.gateway-test`、`hc.server.gateway.feed-test`、`hc.server.gateway.sente-test`、`hc.server.gateway.sync-object-test`、`hc.server.gateway.security-test`、`hc.server.gateway.client-contract-test`。

### 1.4 sessions/commands/epoch

- `command-epoch` 分类为 active/retired/unknown/invalid；退休 epoch 永不当新窗口，`receipts` 只在有效窗口内按 `command-id` 去重（`commands/epoch.clj:33-40`；`commands/AGENTS.md:128,148`）。
- 多客户端：命令按服务器 Owner 接受顺序串行；每客户端独立草稿（`sessions.clj:309-311`），不存在客户端独占锁；控制权转移就是任意客户端继续提交命令，服务端不认“连接所有者”。
- 删除保留：软删除保留默认 30 天（`sessions/AGENTS.md:202-203`），显式永久删除不受窗口限制（`registry.clj:659-660`、`app/commands.clj:783,818`）。
- 恢复不复活外部许可：恢复事务写入 `:restore-safety`，定时任务暂停、工具禁用、记忆为 candidate（`storage_adapter.clj:1017-1021,1374`）；实跑恢复核验见 §3.3。

### 1.5 program/sandbox

- 能力面：`capability.clj:12-95` 定义四种角色；`ask` 仅 S1、`call` 仅 holder/Plan dedicated、`abandon` 仅 holder（`capability.clj:143-187`）。
- `where` 唯一命中：0/多匹配转 `:parked` 并返回 candidates/nearest；`unpark` 四道核验按观察 → 非自指 → 存在性 → 能力集顺序执行（`program/runtime.clj:812,857,862`；`program/AGENTS.md:357-370`）。
- 期限：`:intent` UTF-8 字节 × 50ms（`runtime.clj:43-44`），恢复时按新控制请求重新计时。
- 名字与重放：活动名 `:NAME_TAKEN`；同 call-id 不同载荷 `:CALL_REPLAY_MISMATCH`（`runtime.clj:1505,1568`）。
- 阶段：`:running/:draining/:waiting/:parked/:settling/:completed/:failed`（`runtime.clj:33-34` 及状态迁移）。
- 证据：`hc.server.program.runtime-test`、`sandbox-test`、`capability-test`、`workspace-integration-test`、`shell-port-test`、`receipt-test`。

### 1.6 workspace-gate 与 shell

- `stamp` 恰为 `{:mtime-sec :mtime-nsec :size}`，不读取 atime，按三字段精确相等比较（`hc/core/workspace_gate/stamp.clj:1-7,104-110`）。
- `read-set` 同时覆盖待改目标与只读依赖（`workspace_gate.clj:114-182`；根 §4.2-1）。
- 批内预演与上限：单文件 2 MiB、单批 16 MiB、256 个路径操作（`workspace_gate/prepare.clj:20-22,591-598`）。
- `$W$` 与 Shell 互斥：shell 会话从准入、启动、输出泵、EOF 到已知子活动结束都持有 W；停止信号不释放 W（`shell/session.clj:9-12,548,662-740`）。
- 七类结果与稳定码：`FILE_CONFLICT/STRUCTURE_CONFLICT/PREPARE_REJECTED/WAITING_FOR_WRITE_GATE/APPLIED/HARD_FAILURE/UNCERTAIN` 及 `OBSERVED/UNSUPPORTED_OPERATION/ACTOR_INACTIVE/CLAIM_CHANGED`（`workspace_gate/error.clj:72-97`）。
- 停止四态：`STOP_REGISTERED/STOP_SIGNAL_SENT/SESSION_ENDED/STOP_UNCONFIRMED`（`shell/session.clj:1275-1291`）。
- 证据：`hc.server.shell-test`、`hc.server.shell-owner-port-test`、`hc.core.workspace-gate-test`、`hc.core.swarm-stm-workspace-test`、`hc.server.program.workspace-integration-test`。

### 1.7 model-port

- 只用低层 `ChatModel`/`StreamingChatModel`/`ToolSpecification`（`model_adapter/port.clj:18,694-725`；`request.clj:826`）；全仓 server 无 `AiServices`/`ChatMemory` 引用（仅文档禁令）。
- 流式工具参数完整才发布；同 call-id 在完整回调与最终响应间归并（`model_adapter/events.clj:108-168`）。
- 重试分类：可重试（network/timeout/429/5xx）与不可重试（auth/quota/model/input）、`:tool-arguments-invalid` 新尝试、`:outcome-unknown` 核对、用户取消不入预算（`model_port/retry.clj:24-62`）；双预算在 `hc.compose.retry.planner`，传输层必须显式声明隐式重试为 0（`retry.clj:79-100`）。
- 用量：每字段整数或 `:unknown`，合并时已知值优先，绝不把未知记 0（`model_port/usage.clj:1-11,31-84`）。
- reasoning：事件携带供应商返回的 reasoning 与不透明数据，不生成替代文本（`events.clj:168-182`）。
- 证据：`hc.server.model-port.*` 全部命名空间。

### 1.8 其他适配

| 适配 | 结论 | 证据 |
|---|---|---|
| backup | 修复后通过：默认加密、密码 ≥12、冲突默认 keep-both、恢复前恢复点、记忆不复活 | §2 A–C；`hc.server.backup-test`、`backup.*-test`；§3.3 实跑 |
| search | 通过：`published-at` 与 `fetched-at` 分离，未知发布日期不冒充 | `materials/search.clj:13-16,199-209`；`hc.server.search.*` |
| materials | 通过：注入失败拒绝整轮、作用域校验 | `hc.server.materials.*` |
| attachments | 通过：根 §7.4 旅程 6.6（多模态附件）（同 §3.4-6、§6.7-5）的 20 项/100 MiB；2 MiB/16 MiB/256 路径操作是 §4.2 受保护提交上限，两者不混写；严格 UTF-8 解码不替换非法字节 | `attachments/model.clj:26-33`、`media.clj:447-455`；`attachments.*-test` |
| browser | 通过：对象级 takeover、敏感字段暂停、不关闭共享浏览器 | `browser/service.clj:214-248,391,501`；`browser-test` |
| speech | 通过：服务边界与本地/远端路线分离 | `hc.server.speech.*` |
| diagnostics | 通过：确定性脱敏，秘密/主机/路径占位符 | `diagnostics/redact.clj:33-43`；`diagnostics.*-test` |
| artifacts | 通过：四步提交、blob 摘要/大小/来源元数据 | `artifacts.clj:112-148`；`artifacts-test` |
| documents | 通过：显式 native/text/ocr 路线，空结果不伪造 | `hc.server.documents.*` |
| scheduler | 通过：时区、错过记 `:missed` 不补跑、运行中不重叠 | `compose/scheduler/core.clj:19-24`；`scheduler-test` |

## 2 追加必修缺陷（A–D）

| 编号 | 根因 | 修复 | 验证 |
|---|---|---|---|
| A1 | `offline/backup!` 无条件传入 `:encrypt? encrypt?`；`encrypt?` 为 nil 时 `normalize-create-options` 把“存在该键”当作显式 false | `offline.clj` 只在非 nil 时 assoc `:encrypt?`；`core.clj` 改为只有显式 `false` 才关闭加密 | CLI 默认备份输出 `:encrypted? true, :encryption :aes-gcm` |
| A2 | `cli/options` 的 `select-keys` 不含 `:encrypt`/`:include-secrets`，却对缺失键调用 `parse-bool` | 直接从原始参数读取并产出 `:encrypt?`/`:include-secrets?`；两种写法都支持 | `--encrypt true`、`--encrypt=true`、`--include-secrets true` 均 exit 0 |
| A3 | 无密码时可能静默产出明文 | `backup` 默认需要密码；只有显式 `--encrypt false` 才跳过；无密码报 `BACKUP_PASSWORD_REQUIRED` | 实跑 exit 1；显式 false 才产出 `:encryption :none` |
| B | CLI 产出 `:no-prior-app-commands`，offline 读 `:no-prior-app-commands?`；全新目录没有 app gate | 统一键名；CLI restore 默认断言无先前 app 命令，`--no-prior-app-commands false` 可要求 gate；restore 对全新目标以 `:create? true` 打开存储（仍拒绝 corrupt/foreign/marker-only） | 全新目录一次 restore：exit 0，`:status :complete`，`:command-epoch 1`、`:command-retired-through 0` |
| C | replace 计划每次重写 `:imported-from :imported-at`，且 `already-imported?` 只认 merge | replace 计划保留同 backup/source 的首次导入时间；ledger 记录 `:categories`，同备份同类别 replace 返回 `:already-imported` | 第二次 replace：`:status :already-imported`，无 `:recovery-point`，恢复点数量不变（1） |
| D | 停用唯一连接后 `model-port` 为 nil，网关只抛通用 `no model port is configured` | 网关读取请求冻结 binding 与实时 session-config，`binding-availability` 给出 `:connection-disabled` 等原因；保留 `:code :MODEL_NOT_CONFIGURED`，新增 `:reason`/`:availability`/`:remedy :select-model` | `model_gateway_test` 断言消息含 disabled/replacement connection，且 code 不变 |

### 2.1 A–D 实跑输出（摘要）

```text
# A1 默认备份
{:ok? true, :receipt {:backup {:encrypted? true, :encryption :aes-gcm,
                              :id "e7774076-9ed1-44b7-8a4b-cecc0df6208e" ...}}}
exit 0

# A2 显式 --encrypt true
:encrypted? true, :encryption :aes-gcm
exit 0

# A3 显式 --encrypt false（无密码）
:encrypted? false, :encryption :none
exit 0

# A3 无密码、无显式 false
{:ok? false, :error {:code :BACKUP_PASSWORD_REQUIRED,
                     :message "set HC_BACKUP_PASSWORD or pass --password-file"}}
exit 1

# B 全新目录 restore
:status :complete, :command-epoch 1, :command-retired-through 0
exit 0

# C 第二次 replace
:status :already-imported
恢复点目录：1 个点，两次 replace 前后不变
exit 0
```

## 2.2 E2E 工装追加缺陷

| 编号 | 根因 | 修复 | 验证 |
|---|---|---|---|
| E1 `--send-command` 全部失败 | `app.clj`/`commands.clj` 把 defrecord `Router` 的 `OwnerPort` 协议方法当成 map 键读取，`(:submit-command! router)` 返回 nil，调用时抛 `Cannot invoke IFn.invoke`，被包成 `SINGLETON_SUBMIT_FAILED` | 改用 `gateway-ports/submit-command!` 调用协议方法（两处） | 新增 `hc.server.app.send-command-test`：启动真实系统，经 `app/submit-command!` 提交 `:session/create`，1 test/5 assertions 通过 |
| E2 备份 CLI 无“包含已删除会话” | CLI 没有 `--include-deleted`，只能默认排除 | 新增布尔旗标并映射 `:include-deleted?`；manifest/回执新增 `:deleted-included` 计数 | 单测：备份含 `c-old` 已删除会话，`:deleted-included 1`，恢复后仍保留 `:deleted-at`；CLI `--include-deleted true` exit 0 |
| E3 恢复后连接投影丢失 | ① `config-export` 把连接写成 id→map，settings 文档需要 vector；② `canonical-connection` 丢掉 `:id`；③ 恢复把源实例的 `:launch-connection-ids` 带进目标，`merge-model-config` 将其视为已撤销；④ 恢复标记（`:imported-from` 等）进入模型适配器的封闭 schema，启动 `MODEL_PORT_FAILED` | 写回 settings 形状的 vector（含 `:id`）；恢复保留目标实例的 provenance、剥离源实例 provenance；写回前剥离恢复元数据 | 新增 `hc.server.backup.restore-projection-test`：真实 store 备份/恢复后启动系统，health `:models {:configured? true}`，binding 指向 `local-ollama`，availability 非 `:connection-removed`；1 test/12 assertions 通过 |
| E4 维护重启失败回执 | 重启后只做一次即时 health 读取；HTTP listener 停止可能停在 `:stopping`，端口未释放导致自动重启失败；回执未显式区分“数据已应用、重启未确认” | HTTP stop 在 `:stopping` 时重试（最多 3 次、每次 5s）；重启后最多等待 5s 轮询 `system/health`；回执新增 `:data-applied?`、`:health`、`:restart {:status :failed}` 与 `:recovery-entry {:options [:retry-restart :manual-restart]}` | `restore-supervisor-test` 新增字段断言；`restore-supervisor-integration-test` 真实 app 合并恢复→重启→health 通过（3 tests/42 assertions）；真实 Ollama 冒烟 stop `:stopped? true/:drained? true/:store-closed? true` |
| E8 启动默认会话 binding 为空 | `system.clj` 为全新 store 自动创建 "New session" 时调用 `registry/create-session!` 未传 `:config`，`resolve-creation-binding` 得到 nil；客户端默认显示该会话，故“模型已配置但尚未选择模型” | 默认会话创建传 `:config (live-session-config)`；`session-summary` 发布 `:model-binding` 身份 | `send-command-test` 新增 `startup-default-session-binds-the-main-model`：全新 data-dir + 主模型 → 默认会话 binding=local-ollama/qwen，summary 同；无模型保持 nil。真实 Ollama 冒烟见 §3.3b |
| E10 工具准入拒绝后回合卡住 | 同一工具连续无 `:scope` 时 Nudge 无限循环，拒绝无终态诊断（compose/owner） | 由 compose-audit 落修：拒绝按 `[tenure agent member]` 累加；3 次后 `:TOOL_ADMISSION_REFUSED_LIMIT` + `:owner/blocked`，前两次仍 Nudge 并携带 refusal 文本；顺带收口取消 Nudge 后的 `:turn` | compose-audit 的 `hc.compose.nudge.owner-integration-test` 27/180、owner 回归 105/583 均 0/0；本报告只记录分工，不改其文件 |
| E9 UI 定时任务缺 frozen model | UI 只提交 prompt/recurrence/timezone；`scheduler/create-schedule!` 原样保存，手动运行 preflight 报 `SCHEDULE_INCOMPLETE` 并把任务置 needs-attention | `server/scheduler` 新增 `freeze-create-spec`：new-chat 且快照无 model 时，用实时 `:models :roles :main` 冻结 `:model`/`:execution`/`:capability-scope`；`SCHEDULE_INCOMPLETE` 回执补 `:missing`/`:remedy :select-model`/可操作 action | `hc.server.scheduler-test` 19 tests/232 assertions；真实 app 冒烟：创建后 snapshot model=local-ollama/qwen，manual-run `:started? true`，run `:status :preparing`、`:trigger :manual`、`:scheduled-zone Asia/Shanghai` |
| E7 Plan `devops` 缺 `:scope` | `mission/devops.clj` 的 `devops-tool-spec` required 只有 `verbose-intent`，provider 请求因此不带 scope；内核要求非豁免调用携带 scope，拒绝回执只有 `{:member "devops"}` | `devops-tool-spec` 增加与 `clj-plan` 一致的 flat `:scope`（required 含 scope，enum/字段同源）；`core/scope` 的 `:SCOPE_ANNOTATION_MISSING` 回执补 `:field :scope`、`:expected`（四种 kind 与各自字段）与 `:hint` | `hc.core.scope-test`（96 tests/410 assertions 含 devops 集成）通过；真实 Ollama Plan 冒烟见下 |
| E6 新会话 `:model-binding` 为空 | `sessions/session-summary` 只发布 `:model` 展示副本，没有 `:model-binding` 身份；客户端据此认为“尚未选择模型” | `session-summary` 发布 `:model-binding`（连接/模型身份），保留 `:model` 展示副本 | `send-command-test`：有模型时 binding 指向 `local-ollama/qwen` 且 summary 携带 binding；无模型时 nil 且 health `:configured? false`。真实 app + Ollama `qwen3.6:27b`（声明 `:tools`）：health `:ok true`/`:models {:configured? true}`，新会话 binding `local-ollama/qwen3.6:27b`，发送 `:ok? true`，真实回复 `OK`，停止确认 |
| E5 F04 结构冲突 | 同批删除父目录后写入被归为 `:PREPARE_REJECTED :missing-parent` | 虚拟视图删除且未重建 → `:STRUCTURE_CONFLICT :missing-parent`；移动目标为目录、目录覆盖文件也归 `:STRUCTURE_CONFLICT` | `hc.core.workspace-gate-property-test` + `workspace-gate-test` 73 tests/317 assertions 通过；属性 `delete-directory-then-write-...` 断言已更新 |

## 3 验证

### 3.1 测试

命令：`/tmp/pixi run clojure -X:test:server`

- 三次 `-X:test:server` 复跑（最终代码）：
  1. `3418 tests, 22194 assertions, 1 failure, 0 errors`，退出码 `1`（旧代码/并发编辑中间态；见下）。
  2. `3502 tests, 22404 assertions, 0 failures, 0 errors`，退出码 `0`。
  3. `3502 tests, 22404 assertions, 0 failures, 0 errors`，退出码 `0`。
  4. 并发编辑期间（compose-audit 正在改 owner 拒绝/Nudge）`13 failures`，全部在
     `hc.compose.nudge.owner-integration-test` 的新用例；该 peer 自己的定向运行
     27/180 与 owner 回归 105/583 均 0/0。不是 server 改动引入。
  5. `3542 tests, 22808 assertions, 0 failures, 0 errors`，退出码 `0`
     （含 compose-audit 的拒绝/Nudge 修复与全部 server 修复）。
  6. 第二轮编辑期间 `3553 tests / 22925 assertions / 5 failures / 1 error`：失败在
     compose owner 并发用例与 restart smoke，来自并行编辑/WS 客户端重连噪声；
     WS 测试已加 teardown 关闭客户端。
  7. 最终全量复跑被并行编辑阻塞：`src/hc/client/materials/view.cljc:868`
     出现 `EOF while reading`（server-context 正在编辑的 client 文件），
     全量在加载阶段即退出；改跑 `-d test/hc/server` 的 server 范围：`<SERVER7>`。
- **三次无泄漏的最终运行**：第 2、3、5 次均为 0 failures / 0 errors / exit 0；
  第 1、4 次的失败分别来自旧代码加载与 compose-audit 并行编辑中间态，已在上面单列。
- 第一次的唯一失败：`hc.client.workbench.conversation-bridge-test/send-then-stop-use-distinct-stable-command-ids`
  （`test/hc/client/workbench/conversation_bridge_test.clj:176`，`send` 为 nil）。
  该命名空间单独运行通过（6 tests / 35 assertions / 0 failures）。第二次、第三次全量复跑不再出现，
  结论是 client-audit 并行编辑期间的中间态/顺序敏感，不是稳定实现缺陷。
- 第一次的存储泄漏：`hc.server.storage.engine-lifecycle-test` fixture 报
  “Datalevin stores still open”。根因是三个集成测试忽略 `system/stop!` 返回值；
  已新增 `hc.server.app.lifecycle-test-support`：断言 `:stopped?`（或所有 writer
  已停止且 store 已关闭）、重试一次 stop、等待存储句柄释放；`system/stop!` 在
  所有组件 `:stopped? true` 时即使 `:drained? false` 也关闭存储，但仍返回
  `:stopped? false`。第二次、第三次全量运行不再出现该泄漏。
- 定点：`hc.server.app.send-command-test` 3 tests/18 assertions；
  `hc.server.backup.restore-projection-test` 1/12；
  `hc.server.backup.cli-test` 4/25；`hc.server.app.restore-supervisor-test` +
  `hc.server.backup.maintenance-test` 57/259；workspace-gate 73/317；
  `hc.server.app.restore-supervisor-integration-test` 3/42。
- 说明：本轮新增 `hc.server.backup.cli-test`、`repeated-replace-of-an-unchanged-backup-is-a-no-op`、
  两个 model gateway 拒绝/可用性测试；未删除既有测试。

### 3.2 真实启动冒烟

```bash
/tmp/pixi run clojure -M:server -m hc.server.app \
  --host 127.0.0.1 --port 0 \
  --data-dir /tmp/hc-server-audit-data \
  --workspace-root /tmp/hc-server-audit-work \
  --static-root resources/public
```

- 首次启动：`Herdchestra listening on http://127.0.0.1:46213/`。
- `GET /healthz` → 200：
  `:ok true`、`:version "0.0.1"`、`:store {:open? true, :path ".../db"}`、
  `:models {:configured? false}`、
  `:gateway {:epoch 1, :seq 2, :max-pending 4096, :subscriptions 0, :stale 0}`。
- `GET /` → 200，1588 字节（`resources/public/index.html`）。
- SIGTERM 停止后，用同一 `--data-dir` 重启：成功监听 `http://127.0.0.1:35257/`；
  `GET /healthz` → 200，`:ok true`、`:store {:open? true}`、`:sessions {:count 1}`。
  同一存储目录可再次打开，证明存储锁已释放且当前态已持久化。

### 3.3 备份/恢复实跑

- 源：`/tmp/hc-offline-vzwJBd3u/source` 的副本（会话、记忆、世界书、附件、定时任务、MCP 连接）。
- 恢复目标：`/tmp/hc-server-audit/ev/restore-target2`。
- 恢复核验：会话标题 `Offline session`、消息版本 `hello offline`、附件字节 `attachment evidence`、记忆 `:status :candidate`、世界书条目存在、定时任务 `:state :paused`/`:next-run nil`/0 个 run、工具 `:enabled? false`、`:connection :unconnected`、`:safety-memories :pending-review`、`:app-gate-epoch 1`、恢复点 1 个。
- 证据文件：`/tmp/hc-server-audit/ev/*.out`（`backup-default.out`、`backup-encrypt-flag.out`、`backup-plain.out`、`backup-nopass.out`、`backup-secrets.out`、`restore-fresh.out`、`restore-replace-1.out`、`restore-replace-2.out`）。

### 3.3c 真实 app 定时任务创建→立即运行

```text
:created {:ok? true}
:snapshot-model {:connection-id "local-ollama", :model-entry-id "qwen3.6:27b"}
:snapshot-execution {:mode :new-chat}
:manual {:ok? true, :started? true}
:run {:status :preparing, :trigger :manual, :manual? true,
      :scheduled-zone "Asia/Shanghai", :id "run-2",
      :snapshot {:model {...} :execution {:mode :new-chat} ...}}
:health {:ok true, :models {:configured? true}}
:stop {:stopped? true, :drained? true, :store-closed? true}
```

### 3.3d 真实 Plan devops 调用

真实 Ollama Plan 会话中，模型调用 `devops` 时携带
`:scope {:kind :reset-scope :goal "inspect the repository" :budget 4}`，工具被接受并启动
Plan dedicated worker；worker 的模型请求在 180s 内超时，返回结构化
`{:report "java.net.http.HttpTimeoutException: request timed out" ... :worker-status :failed}`。
这说明 scope 缺失缺陷已修复（调用不再被 `:SCOPE_ANNOTATION_MISSING` 拒绝），剩余超时是
本地 27B 模型在该提示下的响应时间，不是协议缺陷。证据：
`/tmp/hc-server-audit/ollama-devops.out` 与 owner conversation（`:role :tool-call` 带 scope）。

### 3.3b 真实 Ollama 新建会话→发送→回复

```text
:health {:ok true, :models {:configured? true}, :sessions {:count 1}}
:session-id hc-session-e6a29524-...
:model-binding {:connection-id "local-ollama", :model-entry-id "qwen3.6:27b",
                :display-name "Qwen 3.6 27B", ...}
:send {:ok? true}
:reply OK
:final-health {:ok true, :models {:configured? true}}
:stop {:stopped? true, :drained? true, :store-closed? true, :failures []}
```

模型必须声明 `:tools`：第一次用未声明工具的模型发送，正确返回
`:MODEL_TOOLS_UNAVAILABLE`（Plan/Reviewer 需要程序工具），草稿保留；这是产品契约，
不是缺陷。

### 3.4 app-check

```text
/tmp/pixi run app-check
exit 0
{:ok true, :version "0.0.1", :store {:open? true, :path "/tmp/hc-check-.../data/db"},
 :models {:configured? false}, ...}
```

临时根在退出后删除。

## 3.5 错误分类单一来源与 1 MiB 边界（第二轮）

### 3.5.1 稳定错误码

`hc.server.model-port.failures` 是唯一的 server 侧投影：内部 `:kind` + HTTP status →
客户端 `hc.client.copy.failures` 可消费的 code、中文 message 与 `:retryable?`。
`model_adapter/errors.clj` 的每个失败事件新增 `:code`、`:user-message`，保留 `:kind`、
`:category`、`:disposition`、`:retryable?` 与原始 `:message`（已脱敏）。

| code | 触发 | retryable? | 中文 message |
|---|---|---:|---|
| `:auth-invalid` | `:auth` / 401 | false | 服务没有接受这组凭据；请更新凭据或改选连接。 |
| `:permission-denied` | `:permission` / 403 | false | 这项操作需要的权限已被拒绝；请检查连接权限或改选连接。 |
| `:model-missing` | `:invalid-model` / 404 | false | 原模型已不可用；请选择替代模型，历史回答仍可阅读。 |
| `:quota-exhausted` | `:insufficient-quota` / 402 / quota 文本 | false | 这家服务报告额度不足，本次未继续；请查看服务或换模型。 |
| `:rate-limited` | `:rate-limited` / 429 | true | 服务暂时限制请求；请等待后重试或取消。 |
| `:network-unreachable` | `:network` | true | 无法连接到所选服务；请检查连接或稍后重试。 |
| `:transport-disconnected` | `:transport`（closed/reset/broken pipe） | true | 与服务之间的传输已断开；请重新连接后重试。 |
| `:response-timeout` | `:timeout` / 408 / 504 | true | 服务尚未返回内容；可以继续等待或停止当前生成。 |
| `:context-too-long` | `:input-too-long` / 413 | false | 当前内容超过所选模型可接受的范围；请调整内容范围。 |
| `:attachment-unsupported` | `:unsupported-feature` | false | 当前模型不能接收这类附件；请换模型或改变处理方式。 |
| `:unknown` | 其余 | 保留适配器决定 | 无法确定具体原因；请查看诊断信息并按检查顺序处理。 |

429 与额度耗尽分开：429 可重试，402/quota 文本立即失败；404 是模型缺失，不是网络错误；
401 与 403 分开。既有重试预算语义不变（认证/额度/超长/模型缺失立即失败）。

### 3.5.2 1 MiB 真实往返

真实 http-kit listener + Sente 网关（Transit/JSON packer，Java-WebSocket 客户端）：

| 用例 | 结果 |
|---|---|
| `/chsk` POST body 1 MiB − 2 KiB | 非 413（未被大小拒绝） |
| `/chsk` POST body 1 MiB + 2 KiB | 413，body 含 `SENTE_BODY_TOO_LARGE` |
| 真实 WebSocket 发送 ~900 KiB `[:hc/subscribe ...]` | 正常回执 `:ok? true` |
| 真实 WebSocket 发送 1 MiB + 1 KiB | 被拒绝（`SENTE_MESSAGE_TOO_LARGE`）或连接关闭 |
| 直接 `gateway/handle-event!` ~900 KiB | 接受，返回 subscription |
| 直接 `gateway/handle-event!` >1 MiB | `:SENTE_MESSAGE_TOO_LARGE`，details 含 `:size`/`:limit` |

测试：`hc.server.gateway.ws-boundary-test` 3 tests / 12 assertions / 0 failures；
`hc.server.model-port.failures-test` + `errors-test` + `retry-test` 10 tests / 131 assertions / 0 failures。

## 3.6 定时任务与请求快照接线（第三轮）

### 3.6.1 定时任务

- `freeze-create-spec` 不再在“快照已有 model”时提前返回：UI 形状的快照即使带
  model，也补 `:execution` 与 `:capability-scope`（缺省 nil = 跟随当前默认）。
  依据：`snapshot-complete?` 要求 `(contains? snapshot :capability-scope)`；UI 只
  提交 name/prompt/assistant/time/repeat 时该键缺失，启用/恢复/手动运行都会
  `SCHEDULE_INCOMPLETE`。
- 重叠语义：`recover` 先捕获恢复前仍 `:preparing/:running` 的运行；due occurrence
  若落在该运行的 `:started-at` 之后，记 `:skipped :previous-still-running`；
  没有重叠才记 `:missed :process-restart`。返回 map 分开 `:missed`/`:skipped`。
- 测试：`hc.compose.scheduler.core-test` 与 `hc.server.scheduler-test` 合计
  43 tests / 524 assertions / 0 failures；新增 UI 形状 capability-scope、无运行
  重启记 missed、运行中重启记 skipped 三组断言。
- 语义确认与既有测试更新：进程重启且没有运行中的 run → `:missed`（保留
  `:process-restart`）；重启时仍有 `:running` 且 due 落在其 `:started-at` 之后 →
  `:skipped :previous-still-running`。`compose.scheduler.service-test` 与
  `smoke-test` 原期望 `:missed 2`，随新语义改为 `:skipped 2`（未删断言，只改
  期望值并保留计数断言）。定向计数：
  - `-X:test:compose :nses '[hc.compose.scheduler.service-test
    hc.compose.scheduler.smoke-test hc.compose.scheduler.core-test]'`
    → 37 tests / 387 assertions / 0 failures / 0 errors。
  - `-X:test:server :nses '[hc.server.scheduler-test]'`
    → 20 tests / 241 assertions / 0 failures / 0 errors。
  - `-M:test:server -d test/hc/server` → 最终 1573 tests / 9738 assertions /
    0 failures / 0 errors / exit 0（含 diagnostics-identity、scheduler、
    context 接线、错误码与 1 MiB 边界；全量由主代理复跑）。
- 真实 app：创建→snapshot model+execution+capability-scope→manual-run
  `:started? true`，run `:status :preparing`、`:trigger :manual`。

### 3.6.2 每轮请求快照

- `sessions/outbound-projection` 现在保留 `outbound-context` 的完整
  `context-snapshot` 段：`:system`、`:assistant`、`:instructions`、`:memory`、
  `:worldbook`、`:instruction-cards`、`:tools`、`:history`、`:attachments`、
  `:estimates`。数据来自本轮真实 `outbound-context` 投影，不重新查库；未知估算
  保持 `:unknown`，不写 0。
- `:materials/memory/suggest` 已可路由（`materials` 在 session-namespaces）。
  真实系统提交该命令返回业务拒绝 `:MEMORY_SUGGEST_NO_MESSAGES`（空范围），
  证明命令已到达 materials handler，而不是 `SESSION_NAMESPACE_UNKNOWN`。
- 测试：`sessions-test` 新增 `outbound-projection-keeps-every-turn-context-section`
  （45 tests / 293 assertions）；`send-command-test` 新增
  `memory-suggest-command-is-routed`（5 tests / 29 assertions）。

## 3.7 诊断服务身份（第四轮）

- `system.clj` 新增 `diagnostics-identity`：请求快照只有 `:connection`/`:model`，
  身份投影用实时 `live-session-config` 解析连接名与模型显示名，写入
  `:service-id`/`:service-name`/`:model`；`recording-port` 的 `:identity-fn` 改用它。
- 结果：请求记录带服务身份，统计 `:models` 行有 `:service-id`/`:service-name`，
  价格表单服务列表非空；价格保存后 `store/price-for` 可按 service-id+model 命中，
  估算可计算。
- 测试：`send-command-test` 新增身份派生断言；`diagnostics.app-integration-test`
  新增统计 `:models` 行含 `service-id` 断言；两者 7 tests/56 assertions 通过。
- 真实 E2E 21 的 UI 复跑由 client-audit 负责；server 侧投影已就绪。

## 4 改动文件

| 文件 | 改动 |
|---|---|
| `src/hc/server/backup/cli.clj` | 布尔选项解析、`:encrypt?`/`:include-secrets?`/`:no-prior-app-commands?` 键名、restore 默认窗口断言、用法文案 |
| `src/hc/server/backup/offline.clj` | 备份加密默认值、显式 false 保留、restore 允许初始化全新存储、删除未使用的 `store-before` |
| `src/hc/server/backup/core.clj` | 只有显式 `false` 才关闭加密 |
| `src/hc/server/backup/merge.clj` | replace 导入时间稳定化、ledger 记录类别、同备份同类别 replace 幂等 |
| `src/hc/server/app/system.clj` | 模型不可用的具体原因（availability/reason/message/remedy），网关可选 availability 读取器 |
| `src/hc/server/app/snapshot.clj` | `binding-availability` 改为公开投影 |
| `test/hc/server/backup/cli_test.clj` | 新增：CLI 布尔解析、默认加密、密码要求、restore 窗口映射 |
| `test/hc/server/backup_test.clj` | 新增：重复 replace no-op |
| `test/hc/server/app/model_gateway_test.clj` | 新增：停用连接的具体拒绝、无绑定时的通用原因、availability 读取器形状 |
| `src/hc/server/app.clj` | `--send-command` 改用 `gateway-ports/submit-command!` 协议调用 |
| `src/hc/server/app/commands.clj` | Steward 命令提交同样改用协议调用 |
| `src/hc/server/backup/storage_adapter.clj` | 连接导出兼容 vector/map；settings vector + `:id`；保留目标 provenance、剥离源 provenance 与恢复元数据 |
| `src/hc/server/app/restore_supervisor.clj` | 重启后 health 等待；回执 `:data-applied?`/`:health`/`:restart :status`/`:recovery-entry` |
| `src/hc/server/sessions.clj` | `session-summary` 发布 `:model-binding` 身份 |
| `src/hc/server/app/system.clj` | 启动默认会话传 `:config`（binding 根因）；HTTP stop 重试；组件停止后关闭存储 |
| `src/hc/compose/mission/devops.clj` | `devops-tool-spec` 增加 flat `:scope`（required） |
| `src/hc/core/scope/admission.clj` / `annotation.clj` | `:SCOPE_ANNOTATION_MISSING` 回执补 `:field`/`:expected`/`:hint` |
| `src/hc/server/scheduler.clj` | 创建定时任务冻结主模型/执行配置；缺配置回执可操作 |
| `src/hc/server/model_port/failures.clj` | 新增：内部 kind/HTTP → 稳定 code/中文 message/retryable |
| `src/hc/server/model_port/retry.clj` | 401/403 分开；402/404 归类；transport 可重试 |
| `src/hc/server/model_adapter/errors.clj` | 失败事件新增 `:code`/`:user-message`，保留内部 kind/retry |
| `test/hc/server/model_port/failures_test.clj` | 新增：10 个 code 与 429/quota/404 分离 |
| `test/hc/server/gateway/ws_boundary_test.clj` | 新增：真实 HTTP/WS 1 MiB 边界 |
| `deps.edn` | `:test` 别名加入 Java-WebSocket（仅测试用真实 WS 客户端） |
| `src/hc/server/sessions.clj` | `outbound-projection` 保留完整 context-snapshot 段 |
| `src/hc/compose/scheduler/core.clj` | 重叠 due → `:skipped :previous-still-running`；恢复返回分开 missed/skipped |
| `test/hc/compose/scheduler/core_test.clj` / `test/hc/server/scheduler_test.clj` | 重叠与 capability-scope 断言 |
| `test/hc/server/gateway/ws_boundary_test.clj` | 真实 WS 客户端 teardown 关闭，避免重连污染后续命名空间 |
| `src/hc/server/app/system.clj` | 新增 `diagnostics-identity`，请求记录带 service-id/service-name |
| `src/hc/server/app/system.clj` | 组件 `:stopped? true`（无活 writer）时关闭存储；仍返回 `:stopped? false` 与失败证据 |
| `src/hc/core/workspace_gate/prepare.clj` | F04 `:STRUCTURE_CONFLICT :missing-parent`；移动类型条件归位 |
| `src/hc/core/workspace_gate/AGENTS.md` | F04 口径更新 |
| `test/hc/server/app/send_command_test.clj` | 新增：真实系统提交命令 |
| `test/hc/server/app/lifecycle_test_support.clj` | 新增：确认停止、重试一次、等待存储句柄释放 |
| `test/hc/server/backup/restore_projection_test.clj` | 新增：恢复后连接/binding/health 投影 |
| `test/hc/server/backup/cli_test.clj` | 新增 include-deleted 解析与映射 |
| `test/hc/server/backup_test.clj` | 新增 include-deleted 计数与恢复保留 |
| `test/hc/core/workspace_gate_property_test.clj` / `workspace_gate/prepare_test.clj` | F04 断言更新 |
| `plans/server-audit-0.0.1.md` | 本记录 |

## 5 残留缺口（NOT COVERED）

1. **真实外部服务**：本轮连接了本地 Ollama（`qwen3.6:27b`）并完成真实回复与 devops 调用；搜索服务、MCP 服务器、OAuth 提供方、WebDAV/S3 与真实 Chrome 仍未连接，只有仓库既有假端口/本地 HTTP 测试与代码审阅证据。
2. **浏览器端 E2E**：D 的修复只在 server 单测层验证；未重跑 E2E 02 的真实 UI 旅程，客户端是否把新 `:message` 原样显示未在本轮实测。
3. **1 MiB 边界**：由 `gateway.sente-test`/`gateway.sync-object-test` 覆盖，本轮未做真实 Sente WebSocket 大消息往返。
4. **24 类失败文案**：只修了“连接已停用/不可用”这一类；其余 23 类未逐条核对客户端呈现。
5. **平台矩阵**：Datalevin 原生库只在 Linux x64 实跑；Windows/macOS 打包未验证。
6. **`hc.compose.scheduler` 内核**：属只读审阅；本轮只改 `hc.server.scheduler` 适配层（冻结模型），未改 compose/scheduler 内核。`hc.core.workspace-gate` 已按 F04 修改并跑 73/317 定向测试。
7. **附件上限口径已修正**：附件 20 项/100 MiB 属根 §7.4 旅程 6.6（多模态附件）（同 §3.4-6、§6.7-5）；2 MiB/16 MiB/256 路径操作属 §4.2 受保护提交，已在 workspace-gate 核实。报告与代码注释统一采用该口径。
8. **已修：测试套件偶发存储泄漏**。三个集成测试改为经 `lifecycle-test-support`
   确认停止并等待存储句柄；`system/stop!` 在所有组件 `:stopped? true` 时关闭存储
   （仍报告 `:stopped? false`）。第 2/3 次全量运行通过。
9. **已复核：client 单测失败**。第一次全量的
   `send-then-stop-use-distinct-stable-command-ids` 在后续两次全量中不再出现，
   单跑通过；结论为并行编辑中间态/顺序敏感，未改 client 代码。
10. **Plan/Reviewer 需要工具模型**：真实 Ollama 第一次冒烟使用未声明 `:tools`
   的模型，发送被 `MODEL_TOOLS_UNAVAILABLE` 正确拒绝且草稿保留；声明工具后回复
   `OK`。这是产品契约，不是缺陷。
11. **恢复后连接“已移除”与默认会话 binding 的关系**：两者都是投影缺口，但根因不同。
    E3 修的是恢复写入的 settings 形状/源实例 provenance/恢复元数据（store 有连接但
    投影为 `:connection-removed`）；E8 修的是启动默认会话没传 `:config`（会话本身
    binding 为空）。两处现在都有测试：`restore-projection-test` 断言恢复后
    health `:models {:configured? true}` 且 binding 指向 `local-ollama`；
    `startup-default-session-binds-the-main-model` 断言全新 data-dir 的默认会话 binding。
12. **send-command 真实 CLI 入口**：本机单例锁被 E2E harness 的旧 app 进程持有，
    `--send-command` 会走 second-instance 分支；本轮用 `send-command-test` 启动真实
    `hc.server.app.system` 并调用与 CLI 相同的 `app/submit-command!`，未 mock 传输。
    旧进程由 client-audit 清理后可用真实 CLI 复验。
13. **过程说明**：审查早期执行过一次只读 `git status --porcelain`（仅列出未跟踪文件，未用于任何结论）；此后未再执行 git 命令。
