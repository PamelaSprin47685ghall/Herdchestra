# E2E 09 / 10 诊断与修复（0.0.1，2026-11-05）

- 范围：E2E 09 工作区受保护提交；E2E 10 工具中断未知结果。
- 结论先行：E2E 09 的受保护提交核心与冲突语义已有确定性证据；真实 UI
  仍缺“执行角色/worker 程序”的可观察入口，本轮不新增浏览器命令白名单，
  改由真实模型或服务端脚本化 worker 驱动、真实 UI 观察。E2E 10 新增
  operator 命令 `:call/ack`、snapshot 未结 call 投影与看板核对入口。

## 1. E2E 09：owner-state 体积与受保护提交入口

### 1.1 观察到的事实

- 真实命令链路（`plans/e2e-real-07-09.md` §2.6/§2.7）：Plan 底稿 →
  Reviewer 台账 → S1/S2/S3 → `:pending` → `:execution-started` →
  `:swarm-started` 均成功；`register-actor` 在 owner state
  1,993,642 chars 时返回 `:OWNER_ERROR
  "document is too large for the current-state store"`。
- 体积构成：`:calls` 1,236,936、`:cancel` 437,224、`:runs` 248,166。
  同一程序源在 call request、result、numbered-source、receipts 中重复内联。
- 守卫：`src/hc/server/storage.clj:58` 默认 `:max-doc-chars 2000000`；
  `src/hc/server/storage/migration.clj:181-185` 按 `(count (pr-str doc))`
  拒绝并提示 blob。
- 既有压缩：`src/hc/compose/owner/compact.clj` 在 commit 前把大字段替换为
  `{:source-ref .. :source-preview ..}`，阈值 1,000,000，blob 由
  `system.clj` 注入。真实压缩后 owner state 768,811，register/claim 贯通。
- 仍未解决：4 KiB 预览 × 大量 call 仍会累积；`compact-request` 对已压缩
  request 会再次包裹（非幂等）；默认 2 MB 上限对“压缩前的一次提交”没有
  余量；缺少“大程序不再触发 OWNER_ERROR”的回归测试。

### 1.2 修复

1. `src/hc/server/storage.clj`：默认 `:max-doc-chars` 2,000,000 →
   8,000,000；文档说明这是“当前态工作集”上限，超过仍拒绝并提示 blob，
   不静默截断。
2. `src/hc/server/storage/migration.clj`：`normalize` 默认上限同步为
   8,000,000。已有显式 `:max-doc-chars` 的调用者不受影响；恢复/导出格式
   不变，旧库无需迁移。
3. `src/hc/compose/owner/compact.clj`：
   - 预览上限 4 KiB → 1 KiB；终态 call 只保留可识别前缀，完整源/回执在
     blob。
   - 覆盖 `:stdout/:stderr/:output/:body/:details/:receipt/:terminal/:node`
     等回执字段；递归进入 `:retry/:last-error/:last-failure/:nodes`。
   - `compact-request` 幂等：已含 `:request-ref` 的 request 不再二次包裹。
   - 新增 `restore-state` / `restore-value`：按 `:source-ref` 取回原文，
     按 `:request-ref` + `:request-encoding :edn` 解析回 map；blob 缺失时
     保留引用 map，不伪造内容。恢复是显式审计/导出操作，commit 路径不调用；
     恢复后的状态必须再次压缩再提交。
   - blob 引用仍未登记到 storage 的 `blob-refs` 表（该表只提取附件）。
     影响：备份导出不包含这些审计 blob，cleanup 可能回收它们。当前态语义
     不受影响（终态 call 不重放，预览可读）；长期应把 owner compact refs
     纳入 blob 引用表，属于 owner-store 适配器与备份协调的后续工作。
4. 测试：
   - `test/hc/server/storage/owner_state_size_test.clj`：2.5 MB owner state
     经真实 `owner-store` 提交成功；9 MB 仍返回
     `:OWNER_STATE_TOO_LARGE`（limit 8,000,000）；20 × 200 KB 程序源压缩
     后 < 1 MB 并可提交；12 MB 内联源压缩前拒绝、压缩后提交成功。
   - `test/hc/compose/owner/compact_test.clj`：1 KiB 预览、request 幂等、
     `restore-state` 取回源与 request EDN。

### 1.3 受保护提交的 UI 入口

规范路径不变：Plan 写底稿 → Reviewer 非空台账 → swarm → worker 用
`clj-worker` 受保护提交。浏览器只发 operator 命令（发送/停止/回答 ask），
不放行 `:worker/run-program`、`:board/command`、`:review/submit`。

缺的是“可观察入口”，不是命令能力：

- Board 视图当前只读（`src/hc/client/workbench/views/board.cljs` 明示
  “不提供认领或写文件操作”）。
- 会话工具卡能显示 worker 程序回执，但没有把 FILE_CONFLICT、重读重试、
  文件结果串成一次可核对的旅程。

本轮处理：E2E 09 真实 UI 复跑用真实模型或服务端脚本化 worker 执行提交，
浏览器只观察 Board/会话结果；报告如实标注模型多步链路是否稳定。若模型
链路仍不稳定，结论写成“冲突语义与 UI 展示 PASS，模型多步链路未稳定”，
不写成“浏览器直接发 worker 命令”。

## 2. E2E 10：未知结果与核对入口

### 2.1 观察到的事实

- 真实宿主 kill/重启后，in-flight model call 被恢复为
  `:outcome-unknown`，没有自动重放（`plans/e2e-real-08-10-20.md` §3）。
- 客户端 `timeline/tool-status-label` 已把 `:outcome-unknown` 显示为
  “结果未确认”，但没有核对动作；`snapshot` 不向客户端发布 owner calls。
- 真实 shell 子进程 in-flight 未跑到：普通 worker shell 需要完整 swarm/
  令牌旅程；Plan 的 devops 是只读 dedicated worker。

### 2.2 修复

1. 新 operator 命令 `:call/ack`：
   - `src/hc/server/app/commands.clj`：加入 `conversation-kinds` 与
     `operator-kinds`（浏览器白名单只加这一条）。
   - `src/hc/compose/owner/handlers.clj`：`ack-call` 只接受 operator；
     payload `{:call-id :outcome :expected-state :note}`，
     `:outcome ∈ #{:succeeded :not-occurred}`。
     - call 不存在 → `:UNKNOWN_CALL`；
     - `:expected-state` 不符 → `:CALL_STATE_CHANGED`（带当前状态）；
     - 非 `:outcome-unknown` → `:CALL_NOT_UNKNOWN`；
     - 已确认且结论相同 → `:already-acknowledged`（幂等）；
     - 成功 → call 状态改为 `:succeeded` 或 `:failed`，写
       `:ack {:outcome :by :at :note :command-id}`，保留
       `:last-error` 证据；不产生 outbox，不重放动作。
2. `src/hc/server/app/snapshot.clj`：`session/by-id` 增加
   `:unresolved-calls`，只发布 `:outcome-unknown` 与已 ack 的 call，
   最多 20 条；字段 `call-id/kind/role/lane/tenure-id/agent-id/member/
   task-id/phase/last-known-stage/outcome-unknown?/created-at/ack`；不发布
   request、程序源、模型快照或密钥。
3. 客户端：
   - `src/hc/client/swarm/model.cljc` 投影 `:unresolved-calls` 与
     `:unresolved-summary`；`id-string` 归一化。
   - `src/hc/client/swarm/ui.cljs` 新增“结果待确认”面板：未结 call 显示
     “确认已成功/确认未发生”两个 44px 按钮；已 ack 显示结论、操作者、
     备注；面板说明“只结清记录，不自动重放”。
   - `src/hc/client/workbench/views/board.cljs` 把点击转成
     `:call/ack` 命令（`transport/command!`，带 session 的
     command-epoch 与 `:expected-state "outcome-unknown"`）。
   - `src/hc/client/conversation/view.cljs` 的工具卡在
     `:outcome-unknown` 时显示“先核对目标状态”与“去看板核对并结清”
     入口。

### 2.3 兼容与恢复

- 旧 owner state 不含 `:ack`；snapshot 投影按缺失处理，客户端显示未结。
- `:call/ack` 是新增命令，不影响既有命令 epoch/回执；重复请求幂等。
- 已 ack 的 call 保持终态，guard 的恢复逻辑不会再把它当 in-flight；
  不自动重放。
- 8 MB 上限是粗守卫，不是内容版本；恢复/导出格式未变。

## 3. 待真实复跑

- E2E 09：外部编辑器改文件 → 提交前 FILE_CONFLICT（回执含原/当前
  stamp）→ 重读 → 提交成功；文件卡指向真实结果；窄视口；真实 UI 的
  Plan→Reviewer→swarm→worker 路径。
- E2E 10：真实 shell 子进程在途时 kill/重启 → UI 显示“结果待确认” →
  核对确认成功 → 结清；不自动重放。

## 4. term 修复与真实复跑结果（2026-11-05 下午）

### 4.1 `:token-term` 根因

1. 主 workspace 的 `holder-shell-call` 从 `board-snapshot` 取 term，但
   `hc.compose.owner/:board-snapshot` 只返回单 active tenure 的 `:board`
   投影；swarm 有 tenure-3/tenure-4 两个 active tenure 时为 nil。
2. worktree 的 `shell-call-port` 从 swarm kernel 取 term；owner board 的
   token-slot 不在 kernel。
3. `worker/run-program` 的 `run-program!` 只把 Plan context 合并进
   request；即使 host 解析了 term，`control-context` 也不传给 call port。

修复：`shell-call-port` 新增 `:term-fn`；`control-context` 传递
`:context :token-term`；主 workspace 与 worktree 注入 host term；
`worker-run-program-fn` 用 `board-snapshot` 的 `[:token-slot :term-id]`
填入 `:context`；`owner/:board-snapshot` 回退到最新 active execution
tenure 的 board；`tenure-id-for` 先按 actor 解析 tenure。旧 term 返回
`:TOKEN_TERM_STALE`，非 holder 仍 `:TOKEN_REQUIRED`。

### 4.2 真实结果

- token task-2 + claim-2 的 `(call {:command "sleep 300"})` 真实启动
  `sleep 300`（pid 3905544）；kill app 后重启，UI 显示“结果待确认 ·
  1 个未结”，call-6 为 `:outcome-unknown`；点击“确认已成功”后变为
  “0 未结 / 1 已核对”，call-6 阶段 `succeeded`；模型请求数 109→109，
  无自动重放。证据 `plans/evidence/e2e-09-10/e2e10-observe.json`、
  `e2e10-ack.json`。
- E2E 09 的冲突程序执行到 `write` 后 settle 被
  `:ACTION_IN_FLIGHT` 拒：task-2 的旧 shell action 在恢复后仍为
  `:running`，`reject-in-flight!` 阻止同一 actor 的新提交。冲突语义
  仍由 `workspace-test` 与 `workspace-conflict-stamp-test` 确定性覆盖；
  真实 UI 的受保护写入未完成。
