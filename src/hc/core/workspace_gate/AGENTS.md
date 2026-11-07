# `hc.core.workspace-gate` 局部规格

本包实现根 `AGENTS.md` §4.2 的受保护文件提交合同：精确 `stamp`、批内内存视图、完整 dry-run、全局写入口 `$W$`、确定性应用与结果分类。它只依赖 JDK 与 `clojure.core`，不依赖看板、模型或组合层。

## 1 模块边界与单向依赖

```text
stamp      (mtime-sec, mtime-nsec, size) 与路径解析；不比较 atime
  ↑
reader     无锁 cat / stat / ls；读取前后 stamp 变化标记 :changed-during-read
  ↑
prepare    批内虚拟视图、write/mkdir/rm/mv、结构条件、dry-run、Prepared
  ↑
gate       全局 FIFO 写入口 W；提交与受管 Shell 共用同一实例
  ↑
applier    只安装 Prepared 的确定字节与路径操作；不重新解释请求
  ↑
commit     七阶段编排、request-id 幂等、看板适配器、结果发布
  └── board   BoardAdapter 协议与内存参考实现
```

`applier` 不调用 `prepare` 的搜索/解释逻辑，只读取 `Prepared`。`commit` 在持有 `$W$` 期间调用 `prepare`、`applier` 与 `board` 协议；看板适配器必须把 `begin-apply!` 与 `publish-applied!` 放在自己的 STM 事务里。

## 2 公开 API

门面命名空间：`hc.core.workspace-gate`。

| 面 | 函数 |
|---|---|
| 无锁读取 | `read-file`, `stat-path`, `list-dir`, `current-stamp`, `verify-read-set`, `read-set-verifier` |
| 批内视图 | `session`, `snapshot`, `cat`, `stat`, `ls`, `write`, `mkdir`, `rm`, `mv`, `new-batch` |
| dry-run | `preview`, `preview?`, `prepared?` |
| 写入口 | `write-gate`, `acquire-write-gate!`, `try-acquire-write-gate!`, `release-write-gate!`, `with-write-gate`, `write-gate-held?`, `write-gate-owner`, `write-gate-queued`, `set-write-gate-admission!`, `clear-write-gate-admission!`, `write-gate-admission` |
| 提交 | `workspace-gate`, `commit!`, `action`, `retry-publish!`, `request-stop!`, `clear-stop!`, `apply-plan!` |
| 参考看板 | `memory-board`, `set-actor!`, `set-claim!`, `fail-publishes!`, `fail-failure-publishes!`, `board-action` |
| 结果分类 | `rejection?`, `applied?`, `hard-failure?`, `describe-result` |

与根规格 §4.4/§5.5 的工具名映射：`read_file` → `read-file`，`preview_edit` → `preview`，`commit_edit` → `commit!`，`get_action` → `action`，`request_stop` → `request-stop!`。`run_shell` 不在此包内；Shell 适配器复用 `write-gate` 并在启动前做令牌/认领核验。

`session` 是单线程的。一次工具调用失败时，session 的上一版视图保持不变；真实磁盘在此之前从未被修改。

## 3 批内视图语义

1. `cat`/`stat` 记录观察与结构条件；`ls` 是纯探查，不进入 `read-set`、不产生 `checks`。
2. `write`/`mkdir`/`rm`/`mv` 只修改内存视图，按程序顺序解释。后一步看到前一步的结果：
   - `mv old new` 后 `cat new` 返回 `old` 的内容；
   - `ls` 同样反映移动：moved-away 的真实子项从原目录消失，moved-in 的真实子项出现在新目录；删除后的路径不再列出；
   - `write` 同一路径多次按顺序语义，最终计划保存最后一次字节；
   - `rm` 后读取该路径拒收 `:PREPARE_REJECTED :deleted-in-batch`；
   - 删除目录后向其中写入而未 `mkdir` 重建，拒收 `:STRUCTURE_CONFLICT :missing-parent`（F04：同批结构条件不成立，不是普通预演拒绝）。
3. 路径按工作区根解析；空字符串是根，绝对路径原样使用。不设人为根边界。
4. 已存在文件的替换会额外观察当前 `stamp`；`write` 提交的是完整最终内容，不是补丁，因此不强制先 `cat`。但先前的读取观察仍保留在 `read-set`： “先读旧内容、外部已改、再读一次只为刷新 stamp”不会洗掉旧依据（F17-04）。
   `write` 在计划中标记 `:create?`：目标缺失或本批删除后重建为 create，既有文件为 replace。create 在应用前用 `NOFOLLOW_LINKS` 复核目标仍缺失；外部创建或断链符号链接存在时返回硬失败并保留现场。`mkdir` 也做同样的 NOFOLLOW 预检。replace 使用同目录临时文件 + `REPLACE_EXISTING` 原子替换。
5. 同一路径的多次观察若 stamp 不同，`check-current` 要求当前 stamp 同时等于所有观察；实际结果是 `:FILE_CONFLICT`，必须在新程序中重读并重建修改。
6. 创建要求目标缺失；删除只支持已观察文件或空目录；非空目录删除留给 Shell 路径（`:UNSUPPORTED_OPERATION :non-empty-directory`）。
7. 移动要求显式覆盖政策：目标存在时默认 `:PREPARE_REJECTED :target-exists`，`{:overwrite? true}` 只允许文件覆盖文件。目录移动到自身子树拒收 `:PREPARE_REJECTED :move-cycle`。

默认上限：单文件 2 MiB、单批 16 MiB（按实际 `write` 操作累计）、单批 256 个路径操作。`write` 的字符串必须能无损往返 UTF-8；不可解码字节或会被静默替换的代理码元拒收 `:UNSUPPORTED_OPERATION`。

`session` 会同时维护 `read-set` 与由其派生的 `checks`。`prepare-plan` 在复核前还会把 `read-set` 中的每个观察补入 `checks`，因此手工 batch 不能靠省略 `checks` 绕过依据复核；`checks` 仍是结构条件的权威集合。

`verify-read-set` / `read-set-verifier` 是 `hc.core.swarm-stm` 的 `:verify-read-set` 第二期接缝：接受 `{:path :stamp}`、`{:path :kind}` 或 `path -> stamp` 映射，返回 `{:ok? true :observation-basis :mtime+size}` 或 `{:ok? false :reason :FILE_CONFLICT|:STRUCTURE_CONFLICT :conflicts [...]}`。stamp 不符是 `:FILE_CONFLICT`，kind 不符是 `:STRUCTURE_CONFLICT`（冲突项带 `:structure? true`）。带 `:unstable?` 或 `:changed-during-read?` 的条目一律返回 `:changed-during-read`，不能作为有效提交依据。它本身不申请 `$W$`；`finish` 的调用方必须在受保护提交边界内调用它。

## 4 `$W$` 与提交七阶段

`WriteGate` 是公平信号量，按等待顺序准入；没有超时自动转交。等待者可因显式 `request-stop!` 在准入前离开，结果是 `:WAITING_FOR_WRITE_GATE` 且 `:cancelled? true`；不会把锁转给第二个人。释放需要当前 `:grant-id`，重复释放或旧 permit 释放会被拒绝；`apply-plan!` 同样只接受当前 grant 的 `Prepared`。

`WriteGate` 还可安装 admission 谓词。`acquire!` / `try-acquire!` 在排队前
调用它；返回分类拒绝时直接抛出普通拒绝，不占用 W。恢复流程用它在新 `$W$`
上保留旧 gate/未决动作证据：门显示 `:held? true`、owner
`:fleet-recovery-hold`，新写入得到 `:RECOVERY_UNRESOLVED`，直到显式
`acknowledge-recovery` 记录非空证据。

提交阶段：

1. 输入：`request-id`、`actor-id`、`batch`；同一 `request-id` 同载荷重放缓存结果，不同载荷 `:CALL_REPLAY_MISMATCH`。指纹包含 `:limits`、操作、`read-set` 与 `checks`。
2. 等待 `$W$`：`gate/acquire!`，可被显式停止。
3. 最终检查：看板适配器 `check-eligibility!`；可选 `:pre-apply!` 钩子在
   `$W$` 内、`prepare-plan` 前运行，用于预留看板侧名称或在真实写前检查目标
   缺失。钩子的普通拒绝不落盘，钩子本身不进入 request 指纹。随后
   `prepare-plan` 复核全部 `checks`（含只读依赖）并重放计划。`:board nil`
   是显式 test-only 接缝，必须同时传 `:allow-boardless? true`；否则
   `workspace-gate` 构造失败。
4. 完整预演：`prepare-plan` 返回 `Prepared`，并绑定当前 `$W$` permit（`:gate-token`）。旧 `Preview` 或旧 permit 的 `Prepared` 不能应用。
5. 固定资格：`begin-apply!` 在短 STM 中重新核验并登记 `:applying`。停止检查与 `begin-apply!` 共用同一 transition lock：停止先赢则不应用；`begin-apply!` 先赢则返回 `:STOP_AFTER_APPLYING`，当前最小批次完成并标 `:cancel-after-apply?`。`begin-apply!` 的普通拒绝属于第 5 步，结果阶段标 `:final-check`，不是 `:preparing`。看板返回值也在此校验：`{:ok? false ...}`、带 `:status` 的拒绝、或非 map 的 permit 都按普通拒绝处理，不进入 apply。
6. 实际应用：`applier/apply-plan!` 按顺序安装；普通拒绝发生在第 3 步或第 5 步，应用期只处理硬失败。写文件使用同目录临时文件、`force`、原子 `rename`；create 与 replace 分别保留各自的目标存在性语义。新建文件使用进程默认（umask）权限，替换保留目标权限。`apply-fn` 抛出的异常也按硬失败发布，不能绕过看板 `publish-failure!`；`apply-fn` 返回的普通拒绝直接返回，不发布 `:uncertain`。
7. 结果发布：只重试 `publish-applied!`/`publish-failure!` 这类纯控制更新，绝不重放文件写入。两类发布都可能耗尽重试：`:APPLIED` 返回 `:published? false`，`:HARD_FAILURE` 返回 `:published? false` 且看板动作保持 `:applying`；`retry-publish!` 按 `:retry-data :kind` 重试对应发布，不再次应用文件。硬失败发布成功后看板动作进入 `:uncertain`，不是 `:finished`；结果中的 `:phase` 也随之为 `:uncertain`。`finish-request!` 与 `request-stop!` 共用 transition lock，因此停止先赢时结果一定带 `:cancel-after-apply?`。

`apply` 调用次数严格为 1；发布冲突重试不会再次落盘。硬失败保留已应用操作、失败操作、剩余操作与异常，返回 `:HARD_FAILURE` / `:outcome :unknown`，不回滚。

## 5 结果码

| 状态 | 真实文件可能已改变 | 含义 |
|---|---|---|
| `:OBSERVED` | 否 | 读取成功（`read-file`） |
| `:FILE_CONFLICT` | 否 | stamp 不符或读取不稳定 |
| `:STRUCTURE_CONFLICT` | 否 | 存在性/类型/空目录条件不成立 |
| `:PREPARE_REJECTED` | 否 | 批内操作不能完整预演 |
| `:UNSUPPORTED_OPERATION` | 否 | 超出受保护能力（含二进制、非空目录、旧 permit） |
| `:LIMIT_EXCEEDED` | 否 | 超出单文件/单批/路径数上限 |
| `:ACTOR_INACTIVE` / `:CLAIM_CHANGED` | 否 | 身份或认领资格不符 |
| `:WAITING_FOR_WRITE_GATE` | 否 | 未取得 `$W$`；`:cancelled?` 区分停止 |
| `:ACTION_IN_PROGRESS` | 未知 | `retry-publish!` 时请求仍在 `:applying`；不得声称未写盘 |
| `:RECOVERY_UNRESOLVED` | 否 | 恢复证据未解决；新写入被拒绝 |
| `:BOARD_EVENT_PERSIST_FAILED` | 否 | 看板事件未持久化；write-ahead 未成立 |
| `:CALL_REPLAY_MISMATCH` | 否 | 同一 request-id 换了载荷 |
| `:INVALID_ARGUMENT` / `:UNKNOWN_REQUEST` | 否 | 输入或查询不成立；普通拒绝 |
| `:APPLIED` | 是 | 计划已应用；`:published? false` 表示仅看板发布待重试；带 `:observation-basis :mtime+size` 与 `:content-equality :not-claimed` |
| `:HARD_FAILURE` | 可能部分 | 正常保证之外的失败；先核对现场 |

普通拒绝必须发生在任何真实修改之前；`:APPLIED` 只表示本批落盘，不表示父任务完成。`rejection?` 以 `:outcome :not-written` 为主，并兼容上表的普通状态；`:ACTION_IN_PROGRESS` 不是普通拒绝。

## 6 已知边界与限制

- `(mtime, size)` 相同不证明内容相同（F08）。本包不添加隐藏哈希或内容历史；相同二元组的内容变化会被接受。
- 当前只提供整文件读取；未来若加入局部范围读取，必须沿用整文件 `stamp`，不得引入行级版本。
- `write` 是整文件替换，不要求先解码旧内容；对二进制文件的显式整文件替换会成功。需要确认旧内容是文本时，先 `cat`（会拒收不可解码字节）。
- 目录观察只检查存在性与类型（删除时额外检查直接子项为空），不把目录时间当作子树版本。
- 符号链接按操作系统默认跟随读取；原子替换会替换链接路径本身，而不是链接目标。需要编辑链接目标时，请使用真实路径。
- 断链符号链接在 dry-run 中按“目标缺失”处理；若 create 计划命中该路径，应用期用 `NOFOLLOW_LINKS` 发现链接仍在并返回硬失败，保留链接。
- 手工 batch 不是绕过 dry-run 的入口：`validate-ops` 会重放移动环/自移、删除目录的非空子项、write 的 byte[] 与 UTF-8 往返；这些普通拒绝都在真实写前返回。手工 batch 删除真实目录时仍须带 `:empty?` 检查。
- `ls` 对断链符号链接仍按目录项显示；`mkdir` 命中断链符号链接会在应用期返回硬失败并保留链接。
- 读取使用 `max-file-bytes + 1` 的流式读取；文件在读取期间增长会立即 `:LIMIT_EXCEEDED`，不会先分配完整文件。目标在读取期间变为目录/非普通文件时按 after kind 返回 `:FILE_CONFLICT` 或 `:STRUCTURE_CONFLICT`。
- `preview` 提供操作序列、受影响路径与大小，不生成文本 diff；其 `:ops` 已去除原始字节，不能作为写许可。
- 同一批内先删除真实目录的子文件、再删除该目录，会因提交前目录在磁盘上仍非空而被拒；请拆成两次提交。
- 原子替换要求文件系统支持 `ATOMIC_MOVE`；不支持时返回硬失败，不降级为非原子写入。
- `commit!` 的 request 注册表是进程内内存；长期运行的生产适配器应把它持久化/修剪。看板适配器在内存实现中若 owner 线程被杀死，等待者可能一直等待；生产实现应使用可恢复的动作记录。`retry-publish!` 的待发布记录也只在该进程内。
- `begin-apply!` 的 `:applying` 记录必须先持久化。看板事件 hook 返回
  `{:ok? false ...}` 时适配器应抛出普通拒绝；本包也把适配器直接返回的
  `{:ok? false ...}`/`:status` 拒绝当作失败，不进入 apply，返回
  `:BOARD_EVENT_PERSIST_FAILED`；同 request-id 的重放不会再次落盘。发布
  重试通过 `:checkpoint` 事件确认已提交 board 已持久化，不只凭内存中的
  `:finished`。
- 停止只保证在 `begin-apply!` 之前生效；`request-stop!` 与 `begin-apply!` 通过 transition lock 线性化。已进入应用的最小批次会完成并发布；停止后的缓存结果不会自动清除，重试请使用新的 `request-id`。
- `memory-board` 是严格参考实现：actor 必须存在且 alive，`:claim-id` 必须存在且有效；`begin-apply!` 的资格检查与动作写入在同一个 `swap!` 中，publish 要求动作存在、request-id 匹配且处于 `:applying`。`workspace-gate` 的显式 `:board nil` 仅用于测试，必须同时传 `:allow-boardless? true`。
- 根 §8.3 F04 的“反向在预演中报 `:STRUCTURE_CONFLICT`”与根 §4.2.5/本包 §3.2 的 `:PREPARE_REJECTED` 存在表述差异。本包保留 `:PREPARE_REJECTED`：删除目录后未重建父目录属于批内路径约束，不是真实结构观察冲突。
- create 的存在性检查是 applier 内的 best-effort：检查与 `ATOMIC_MOVE` 之间仍可能被外部创建覆盖，按根 §3.6 F14 属于接受的外部写风险；不能把该窗口宣传成硬失败保证。
- 本包不实现 Shell 会话；受管 Shell 必须复用同一个 `write-gate` 实例，并按 §4.4 在启动前完成令牌/认领核验。

## 7 测试

属性测试与确定性交错测试位于：

- `test/hc/core/workspace_gate_test.clj`：stamp 精度、F01/F02/F07/F08/F09/F10、`$W$` FIFO/重复释放、停止（含 stop/begin-apply/finish 线性化）、幂等与 limits 指纹、applied 与 hard-failure 发布重试、硬失败、apply-fn 普通拒绝与异常、permit/grant 绑定、无 checks 的 read-set 复核、checks-only 空写集、unstable/kind-mismatch verifier、boardless 显式开关、严格 memory-board、手工 plan 父目录检查、板返回值拒绝、读取增长/IO 分类。
- `test/hc/core/workspace_gate/prepare_test.clj`：批内顺序与 `ls` 移动视图、F03/F04、删除/重建、限制、二进制拒绝、空目录、手工 batch 环/非空删除/非法字节、断链符号链接 mkdir、属性测试。
- `test/hc/compose/workspace_write_ahead_test.clj`：begin 持久化失败不
  apply、同 request-id 不重放；发布持久化失败经 checkpoint 重试。

真实临时文件场景包括：文件系统实际纳秒精度的 `mtime` 往返、读取后外部修改触发 `:FILE_CONFLICT` 且磁盘保留外部内容、读取期间 stamp 变化标记、读取增长超过上限立即拒绝、读取期间目标变目录被归类、`$W$` 被持有时普通提交排队且不超时转交、Shell 持 W 改写后 A 取得 W 冲突、停止等待不落盘、应用第一个文件后停止仍完成整批、应用到一半注入硬失败保留部分现场、发布重试耗尽后 `retry-publish!` 不再落盘、create 目标在检查后出现时硬失败、断链符号链接不被 create/mkdir 替换、replace 在检查后遭外部写入时按 best-effort 覆盖且不宣称内容相等、只读上限在解码前拒收。

聚焦运行：

```bash
clojure -M:test -n hc.core.workspace-gate-test -n hc.core.workspace-gate.prepare-test
```

全仓测试使用 `clojure -X:test`。`clojure.test.check` 的属性由 `clojure.test` 承载。

## 8 审计对照（根 §8.3 / §5.1–§5.5）

| 条款 | 本包落点 | 证据 |
|---|---|---|
| F01/F02/F10/F17/F18 | 无锁 `cat`/`stat`、完整 `read-set`、`check-current`、冲突详情 | `f01-shell-write-while-queued-conflicts`、`f02-read-only-dependency-structure-change-conflicts`、`stamp-conflict-then-recovery`、`empty-changes-still-verify-file-facts`、`read-set-is-verified-even-without-checks` |
| F03/F04/F19 | 批内顺序视图、`ls` 移动视图、`validate-ops` 重放、完整 dry-run | `f03-third-file-failure-keeps-disk-untouched`、`list-reflects-real-moves`、`hand-built-dry-run-rejects-move-cycle-and-non-empty-rm` |
| F05/F20 | `request-stop!` 在 `begin-apply!` 前取消；进入应用后完成并标 `:cancel-after-apply?` | `request-stop-leaves-the-queue-without-writing`、`f05-stop-after-first-op-completes-batch`、`finish-request-adds-cancel-marker-under-stop` |
| F06/F20-03 | 发布只重试纯控制；`retry-publish!` 不重放文件 | `publish-conflict-retries-only-publication`、`publish-retry-after-exhaustion-does-not-reapply` |
| F07/F17-01 | 读取不等待 `$W$` | `reading-does-not-wait-for-the-gate`、`f01-shell-write-while-queued-conflicts` |
| F08/F09/F18-01 | 只用 `mtime`+`size`，不用 `atime`；同二元组内容变化不声称已证明 | `same-mtime-and-size-is-an-observation-boundary`、`atime-is-never-part-of-the-stamp` |
| F11/F21 | FIFO 写入口、无超时转交、等待链 | `write-gate-is-fifo-and-mutually-exclusive`、`commit-waits-for-w-without-timeout-transfer` |
| F13 | 应用硬失败保留部分现场与不确定结果 | `hard-failure-leaves-the-partial-site` |
| F14 | 锁内检查后的外部写入按 best-effort 覆盖，不宣称内容相等 | `external-write-after-the-check-is-best-effort`、`create-does-not-replace-a-file-that-appears-after-the-check` |
| F19-04/F20-04 | `Preview`/`Prepared` 类型分离、permit/grant 绑定 | `preview-and-prepared-are-distinct`、`prepared-is-bound-to-its-gate-permit` |
| F12/F15 | Shell 子活动、Owner 唤醒/休眠属于 Shell/调度层，不在本包 | 由对应包覆盖 |
| F22–F24 | 结果码与四类结局；Shell 会话不在本包 | 本包返回 `:APPLIED` / `:HARD_FAILURE` / `:outcome :unknown` / `:ACTION_IN_PROGRESS`；Shell 由对应包覆盖 |
