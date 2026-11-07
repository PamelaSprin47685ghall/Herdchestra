# DEBT 状态（0.0.1）

- 日期：2026-11-05，更新 2026-11-07（**最终冻结 bundle `c9cbfdf6`（3,018,831 B，18:12:03；CSS `332f9fd3`/manifest `c2158f6b` 未变）+ 源码树 `1e824c83`（clean rebuild 确定性）；全量 3824/24735/0F/0E/exit 0/354 s，app-check `:ok true`/0.0.1/`:component-errors {}`；final21 `0fbddd3c`+`319f035f`、final20 `87b0dee4`+`5e301ce4` 及更早全部作废/前冻结**；**E2E 22 条 final22 全 PASS：E2E12 48/48（断言口径修正后，`e2e12-c9cbfdf6-final22-assertfix.json`）、E2E11 25/25（`e2e11-c9cbfdf6-final22.json`）、E2E05 42/42（D-24/D-25/D-28 修复不回归撤销段）**，证据统一双记 `c9cbfdf6` + `1e824c83`；**D-27/D-28 已关闭并从 `DEBT.md` 删除，D-07 子项清零一并关闭删除**；E2E 统计 **22 PASS / 0 PARTIAL / 0 BLOCKED**）。历史：final15 PASS 3808/24669（已前冻结）；final14 3805/24650（历史基线）。
- 基准：根 `AGENTS.md` §8.1 第 4 条（`DEBT.md` 只记缺口、关闭即删、AI 以它为工作队列）、§7.4 八段旅程、§8.5 E2E 01–22。
- 规则：编号不重排、不复用；已关闭条目从 `DEBT.md` 删除，在本表留状态与证据。
- 约束：未改根 `AGENTS.md`；未写 `src/**`、`test/**`；未执行 Git 写操作（本次处于 11:26–11:41 冻结窗口）。

## 1. 条目状态

| 条目 | 负责人 | 状态 | 证据 |
|---|---|---|---|
| D-01 `askPreviousScope` / `negotiate` 只有账本快照 | W2/W6 | **开放** | `src/hc/core/scope/scope.clj:313-330` 的 `snapshot` 仍只返回账本快照；`scope.clj:331-344` 的 `previous-scopes`/`preceding-scope` 只读本地 reset 历史；`src/hc/compose/workspace.clj:252-272` 的 `control-port` 对 `:askPreviousScope` 走 fallback，返回 `:UNSUPPORTED_OPERATION`；无 receipt/答案 steer |
| D-02 写入结果缺“存储不可用”第四态 | W6 | **已关闭** | 最新 `AGENTS.md` §7.1 裁决存储单点、写入只有成功一个终态；`AGENTS.md` §5.2 明确存储失败不进 `:accept-call-terminal`；见 `HISTORY.md` |
| D-03 用户编辑底稿后无润色/回读 | W3 | **开放** | `src/hc/core/relay/core.clj:379-412` 的 `set-draft` 只存 `{:path :content}`；`relay/core.clj:790-848` 的 `start-execution` 仍用旧 `:content` 作 handoff 载荷；`src/hc/core/workspace_gate/prepare.clj:835-844` 以 `:FILE_CONFLICT :stamp-mismatch` 拒绝 Plan 写入；无磁盘重读/润色 |
| D-04 22 条 E2E 只自动化 3 条 | W10 | **开放** | `test/e2e/` 仅 `e2e-01`、`e2e-03`、`e2e-09` 三个剧本 spec + `mock-services.spec.mjs`、`memory.spec.mjs`；其余 19 条无 spec，mock 服务未接入；E2E01 spec 于 11:11 复跑超时（`plans/evidence/e2e-auto/playwright-results.json`） |
| D-05 E2E 22 no-sync/已删除区完整断言 | W7 | **已关闭** | W7 手工回归 PASS：B `app-check` exit 0、`:ok true`、`:models {:configured? true}`、`:component-errors {}`；`--include-deleted true` 备份/恢复后 B2 UI 已删除区可见，`s-deleted` 的 `:deleted-at` 原样保留；no-sync 以单 app 存储隔离验证（A 临时会话 count 2，B2 仍 1）。证据 `plans/evidence/e2e-regression-01-02-22/e2e22-final.json`、`plans/e2e-regression-01-02-22.md` §4 |
| D-06 Datalevin use-after-close SIGSEGV | W11 | **已关闭** | W11 最终修复：`start-internal!` 打开 store 后调用 `storage/expect-ordered-close!`，`stop!` 幂等标记；不再注销 Datalevin hook。20 轮 start/stop 180 assertions 0F/0E；SIGTERM 5/5 启动成功、exit 143、无新 `hs_err`；`app-check` 3/3 exit 0；`hs_err` 9→9。W12 独立复验：SIGTERM 5/5 PASS、20 轮 PASS、`hs_err` 9→9。后续优化：publisher tick 28.92ms/12.13MB→0.64ms/2.40MB；baseline-cache 命中 0.00ms/0.00MB；per-session 子块缓存单 session 变化 1000 sessions 82.74ms/224.55MB（无缓存 211.96ms/580.56MB）、100 sessions 11.71ms/22.88MB（无缓存 32.32ms/57.89MB）；component-snapshot 按 revision 缓存，空应用 3.69ms/0.30MB→2.04ms/0.26MB；回归 publisher 12/72、system 17/105、gateway 9/41、scheduler 20/241、20 轮 180 assertions 0F/0E；hs_err 9→9。见 `plans/store-shutdown-fix-0.0.1.md`、`plans/oom-root-cause-0.0.1.md` |
| D-07 E2E 05/12 未完成子项 | W2–W5/W10/W115/W116 | **已关闭** | 子项清零：05 final22 复跑 42/42 PASS（D-24/D-25/D-28 修复不回归撤销段）、12 final22 断言口径修正后 48/48 PASS（`e2e12-c9cbfdf6-final22-assertfix.json`，D-27/D-28 双关闭）；E2E 22 条全 PASS。证据双记 `c9cbfdf6`+`1e824c83`，见 `plans/e2e-master-status-0.0.1.md` |
| D-08 会话级技能选择 UI 缺失 | W9 | **已关闭** | `:session/select-skills` 写入 `:mcp-session-scope`（follow/custom + skills），扩展视图返回 `:session-scope`/`:resolved-skills`，outbound 带 `:skills`；测试 client 34/116、sessions 48/307、skill 17/60、app 12/82、outbound 1/30、history 18/63 均 0F/0E。**最终冻结** `ea9e5732…`（13:02:42，3,010,646 B；CSS `332f9fd3…`）；W35 全量 PASS：3748 tests / 24306 assertions / 0F / 0E / exit 0 / 365 s；app-check exit 0；单树 `52547df2`。W33 核对 D-08 四标记各 1、AbortController 19、`hc-markdown` 1、`hc-message-stream` 2。D-08 功能在 `8b210896` 重跑 PASS（A 含 `Skill: d08-skill`，B 不含且跟随/无 active），该 hash 已作废；**待 `ea9e5732` 复跑**。证据 `plans/evidence/e2e-13-15-16-21/e2e13-session-skill.json`、`session-skill-ui.json`、`13-session-skill-*.png` |
| D-09 新分支 UI 切模型未生效 | W5/W8（W9 验证） | **已关闭** | 根因：分支 Owner 只在 router/registry，未并入发布快照；分支回执又剥掉 `:commands`，客户端设置面板拿不到分支 epoch。修复：`src/hc/server/app/system.clj:2322-2338` 把 router live owners 并入快照 owner map；`src/hc/server/app/commands.clj:726-768` 的 `handle-branch!` 回执携带分支 Owner 的 `:command-epoch`；客户端设置桥发布 `:session/command-epochs`（含 `:ui/session-list`）并在设置模型合并。W9 真实 Chrome（`plans/evidence/e2e-13/branch-model-switch.json`）：分支切 `model-b` 后 `branchRequestModels=[model-b]`、源会话 `sourceRequestModels=[model-a]`；分支收到 `B-REPLY`，源会话回复也到达；5 项检查全 true。测试 send-command 7/42、settings 18/90、extensions 35/120、commands-dedup 20/169，0F。**最终冻结** `ea9e5732…`（13:02:42，3,010,646 B；CSS `332f9fd3…`）；W35 全量 PASS：3748 tests / 24306 assertions / 0F / 0E / exit 0 / 365 s；app-check exit 0；单树 `52547df2`。D-09 在 dd968793/55e4e8ea/8b210896 均 PASS，8b210896 已作废；**待 `ea9e5732` 复跑**。E2E13 在 master 仍为 PASS，统计不变 |
| D-10 旧 §7.4 编号引用 | W12 | **已关闭** | 旧组号残留 0；W1 的 66 处 = 35 AGENTS + 31 源码引用，其中 59 已映射旅程名、7 通用 §7.4；字符串/测试 0；历史报告 2 文件/9 处按“旧编号”保留。见 §3.2 |
| D-11 24 类失败文案未重组为十类 | W5/W9 | **开放** | `src/hc/client/copy/failures.cljc:16` 仍 24 类；`test/hc/client/copy/failures_test.clj:66-68` 断言 24；`ui_test.cljs:264,276`、`selection_dom_test.cljs:470`、`conversation_test.clj:740` 仍断言“结果待确认”“已停止” |
| D-12 手动模型绑定缺少 `:tools`，Plan/Reviewer 返回 `MODEL_TOOLS_UNAVAILABLE` | W5/W8/W10 | **开放** | 保存/composer/选择门禁已修：手动模型 `:not-returned`、composer 放行 `:unknown`、设置页允许“用于下一轮”。`src/hc/compose/owner/handlers.clj:120-160` 已让声明的 `:tools true` 优先于 live 探测，但声明的 `:tools` 仍未进入 binding。W10 13:16 自动化 E2E01 FAIL（33.8 s）：UI 行 `工具：已声明支持`、`bind.toolsDeclared=true`，Plan 仍 `MODEL_TOOLS_UNAVAILABLE`、未产生用户消息；**D-AUTO-02 已并入 D-12**（`plans/e2e-auto-defects.md`、`plans/evidence/e2e-auto/e2e01/e2e01-result.json`）。证据 `plans/d12-fix-report.md`、`plans/evidence/e2e-auto/playwright-results.json` |
| D-13 Plan 账本未清空时会话永不 idle | W3/W5 | **开放** | E2E 04 第三轮 stub 通过（THIRD-REPLY、只发一次、顺序正确），但 `idleAfter1/idleAfter2/idleBeforeThird` 全 false（`plans/evidence/e2e-03-04/e2e04-third-stub.json`）；毫秒级 stub 仍显示“正在生成/自动继续”，停止后又进入。W3 判定为 §2.5 Nudge 行为，非模型速度 |
| D-14 无 token 任务时 `take-or-die` 应拒绝 | W2/W4 | **已关闭** | W11 已在 `src/hc/core/swarm_stm/commands.clj:1264` 实现 `:TOKEN_TASK_REQUIRED`；`empty-token-without-token-task-is-refused` 1 test/5 assertions 0F/0E，验证 token 仍 free、actor 仍 alive、无 `:attempt-won` 事件。E2E09 测试前提改为先 split。 |
| D-15 text/plain 附件被判为 native | W8/W34 | **已关闭** | 修复：`native-capability` 对 `:text` 返回 nil（`attachments/model.clj:393-405`），text/plain 走 `:send-route :text`；image/PDF/audio/video native 语义不变。单测 model-test 28/118、request-test 15/56 0F。UI/outbound 回归（W8，bundle `8b210896`，`plans/evidence/e2e-05-06/d15-text-attachment.json`）：chip“note.txt · 37 B / 可发送 / 移除”、sendable=true、nativeOriginal=false；outbound 含 `[Attachment: note.txt]` 且 inlineText=true、nativeTextRejectionSeen=false；pure TEXT-RAW→needs-choice、TEXT-EXTRACTED→`:send-route :text`、prepare-send ok? true。报告 `plans/d15-fix-report.md`。残余：脚本未读 `.hc-attachment-card` 文案，不影响发送结论；W34 的 PNG workaround 可移除 |

| D-16 新会话初始快照未送达/未合并（gateway on-open），swarm/受保护程序卡不渲染 | W23/W5/W41 | **已关闭** | 根因：snapshot/baseline 的 board-view `*/by-id` 表 keyword keys → 客户端 `normalize-table` 要求 string IDs → `:invalid-entity` → resync 循环 → 永远 awaiting-sync。W41 `snapshot.clj normalize-entity-tables` 转 string + W23 `:program-status` settle 优先修复。**E2E09 在最终冻结 bundle `72de4f9a` + 树 `ab801450` 上 PASS**：ok=true、30/30 checks、冲突卡原/当前 stamp、外部编辑保留、重读后 APPLIED、`worker,7` 落盘、server stat 最终、窄视口 360×360 无溢出；证据 `e2e09-final.json`、`e2e09-final-pass-72de4f9a.json`、5 张截图、§17.11 |
统计：`DEBT.md` 当前共 **12 条**（D-01、D-03、D-04、D-11、D-12、D-13、D-17、D-18、D-19、D-20、D-22、D-23）；其中 D-12 保持开放，**D-AUTO-02（E2E01）已并入 D-12**，D-19/D-20 为决策项。建议阻断 **8 条**（D-04/D-11/D-12/D-13/D-17/D-18/D-22/D-23）。D-25 已关闭并从 `DEBT.md` 删除（final20 E2E05 复跑 41/42 PASS 确认）；**D-27/D-28 已由 final22 E2E12 复跑确认关闭并从 `DEBT.md` 删除**（断言口径修正后 48/48 PASS，证据 `e2e12-c9cbfdf6-final22-assertfix.json`，双记 `c9cbfdf6`+`1e824c83`）；**D-07 子项清零（05 final22 42/42、12 final22 48/48）一并关闭删除**。已关闭 D-02、D-05、D-06、D-07、D-08、D-09、D-10、D-14、D-15、D-16、D-25、D-27、D-28 不在 `DEBT.md`。

D-07 的子项集合已清零并关闭删除：05 由 final22 E2E05 复跑 42/42 PASS 移除（D-24/D-25 保持关闭、D-28 `current-with-local` 修复不回归撤销段）；12 由 final22 E2E12 断言口径修正后 48/48 PASS 移除（D-27/D-28 双关闭，证据 `e2e12-c9cbfdf6-final22-assertfix.json`）；11 已由 final19 E2E11 25/25 PASS 移除（D-26 关闭）；15 已由 final18 E2E15 regen/版本切换/朗读 PASS 移除；06/07/08/09/13/14/16/17/18/19/20/21/22 均 PASS。最终冻结 bundle `c9cbfdf6` + 树 `1e824c83`（final22 最终冻结，3824/24735/0F/0E/354 s）；final11–final21 及更旧均前冻结。沿革：15 版本切换根因 D-22 已由 W63 修复 + W70 补强并经 runner5 复跑确认（证据 `e2e05-a14d9abd.json`）；D-24/D-25 已由 W83/W113 修复并于 final20 E2E05 复跑确认关闭（`e2e05-87b0dee4.json`，前冻结）；D-26 已由 W99 修复 + final19 E2E11 关闭；D-27/D-28 已由 W110/W117/W124 修复链 + final22 E2E12 关闭。

### 1.1 D-06 四个失败启动日志（已关闭，根因为 20 秒启动等待超时）

17:00 前后 SIGTERM 回归的 4 个失败轮日志：

| 轮 | 日志 | 首错摘要 |
|---|---|---|
| 1 | `/tmp/hc-sigterm-data-eokNyr/app.log` | 仅 `WARNING: ensure already refers to ... hc.server.extensions.catalog/ensure`；无 `Herdchestra listening`、无异常栈、未生成 `db/` |
| 2 | `/tmp/hc-sigterm-data-zGyGV6/app.log` | 同上，仅 catalog warning；未就绪 |
| 3 | `/tmp/hc-sigterm-data-sf5mv7/app.log` | 同上，仅 catalog warning；未就绪 |
| 4 | `/tmp/hc-sigterm-data-AXxmoD/app.log` | 同上，仅 catalog warning；未就绪 |

四轮都未出现 `Herdchestra listening on`，`app.log` 没有可归因的异常行。W11 复查后确认根因是脚本/harness 的 20 秒启动等待在 CPU 争用下超时，不是 `removeShutdownHook` 或编译错误；等待已改为 90 秒，最终 5/5 SIGTERM 启动成功。结论：**D-06 已关闭**。

## 2. 本轮关闭/改写/新增

| 动作 | 条目 | 说明 |
|---|---|---|
| 先前关闭（保留记录） | D-02 | 存储 fail-fast 裁决，不设第四态；已从 `DEBT.md` 删除 |
| 改写 | D-04 | 明确 3/22 自动化 + mock 服务已落地但未接入 19 条；E2E01 11:11 复跑超时 |
| 关闭 | D-05 | W7 手工回归 PASS：include-deleted 备份/恢复、已删除区保留、no-sync 隔离均通过；新 §8.5 自动化仍归 D-04 |
| 关闭 | D-06 | W11 最终修复并通过 20 轮 start/stop、5 轮 SIGTERM、3 次 app-check；`hs_err` 9→9 |
| 改写 | D-07 | 收窄为 05/06/07/08/09/11/12/15；13 已移除，15 只剩实时麦克风；06/08/11 按最新证据改写 |
| 改写 | D-08 | 补服务端 `:custom` 已支持、客户端仍缺会话选择器 |
| 改写 | D-09 | 补分支绑定复制与 `:session/select-model` 路径，缺回归 |
| 关闭 | D-10 | 旧组号残留 0；66 处 = 35 AGENTS + 31 源码引用，59 已映射旅程、7 通用；历史报告 2 文件/9 处保留 |
| 新增 | D-11 | 24 类文案与旧状态词测试断言未按新 §7.4 旅程 8.5 重组 |
| 部分修复/重开 | D-12 | 保存/composer/选择门禁已修；`handlers.clj` 已让声明优先；仍缺 E2E01 新建连接重跑与 binding/config `:tools` 证据 |
| 新增 | D-13 | W3 证据：Plan 账本未清空时 Nudge 持续注入，会话永不 idle；停止后又会进入 |
| 关闭 | D-14 | W11 实现 `:TOKEN_TASK_REQUIRED`（commands.clj:1264）；测试 1/5 0F/0E，token free/actor alive/无 attempt-won |
| 关闭 | D-08 | 会话技能选择 UI 已实现并通过真实 Chrome；E2E13 子项同步关闭 |
| 关闭 | D-09 | 分支快照/回执 epoch 修复；真实 Chrome 分支 model-b、源会话 model-a；测试 0F；已从 `DEBT.md` 删除 |
| 校正（W30） | D-01/D-03/D-11 | 行号按当前源码校准：`control-port:252-272`、`start-execution:790-848`、`selection_dom_test.cljs:470` |
| 校正（W30） | D-07 | 06 改为 2026-11-06 10:34 真实 Ollama 9 条断言 PASS + 两项残余；08 改为 master 的精确缺口；11 改为待 `8b210896` 复跑 |
| 校正（W30） | D-04/D-12 | 补 11:11 E2E01 超时与 11:21 驱动修复证据 |
| 新增（W33） | D-15 | text/plain 附件被 `route` 判为 native、adapter 拒绝；影响 E2E05/06 文本附件；建议 text 不判 native 或 adapter 接受 native text |
| 修复（W8） | D-15 | `native-capability` 对 `:text` 返回 nil，text/plain 走 `:send-route :text`；单测 model-test 28/118、request-test 15/56 0F；UI/outbound 回归在 `8b210896` 上 PASS |
| 关闭（W8） | D-15 | 证据 `plans/evidence/e2e-05-06/d15-text-attachment.json`：sendable=true、nativeOriginal=false、inlineText=true、nativeTextRejectionSeen=false；已从 `DEBT.md` 删除 |
| 冻结更新（W33） | D-08/D-09/E2E15/视觉/E2E05 | `8b210896`（12:17:19）因 13:00–13:01 src 改动失效；W27 重建 `ea9e5732`（13:02:42，3,010,646 B）为最终冻结；上述证据降级前冻结，待在新 hash 重跑/重绑 |

## 3. 文档旧 §7.4 引用清理

| 范围 | 文件 | 处数 | 处理 |
|---|---:|---:|---|
| `src/**/AGENTS.md` | 30 | 30 | `§7.4-1..8`、`§7.4 第 N 组` 映射为八段旅程名称 |
| `plans/*.md` | 6 | 7 | 审计/诊断/runbook 的权威指针改为旅程名称 |
| `src/**/*.clj{,s,c}` 注释 | 29 | 29 | ns docstring/行注释中的旧编号映射为八段旅程名称；未改逻辑、字符串或协议关键字 |
| `RUNNING.md`、`HACK.md` | 0 | 0 | 已无旧编号，无需改 |
| 历史报告（保留对照） | 2 | 9 | `agents-md-user-edit-impact`、`spec-delta-scan` 已标“旧编号”，不删历史证据 |
| **合计已改** | **65** | **66** | 不含历史报告 |

- 34 个局部 `AGENTS.md` 中，30 个含旧编号已改；4 个只引用通用 `§7.4`（`src/AGENTS.md`、`src/hc/client/AGENTS.md`、`src/hc/client/conversation/AGENTS.md`、`src/hc/client/workbench/AGENTS.md`），无需改。
- 源码只改注释文字，未改任何代码逻辑、测试断言、字符串文案或协议关键字；未改正在被其他工人编辑的文件（29 个文件 mtime 均早于本轮，且改动前逐文件 `git diff` 为空）。
- 映射口径：旧 1→旅程 1；旧 2→1.2–1.3；旧 3→2；旧 4→2.6/3.5；旧 5→5；旧 6→5.6/6.1–6.3；旧 7→6.4–6.8；旧 8→7–8。具体文件按主题落到子段（如附件→6.6、浏览器→6.4、定时→6.8、备份→7、诊断→8.6）。

### 3.2 W1 的 66 处 §7.4 引用核对

W1 报告的 66 处 = **35 处 `src/**/AGENTS.md` + 31 处源码代码引用**，不是 66 处旧组号：

| 分类 | 处数 | 处理 |
|---|---:|---|
| 旧组号（`§7.4-1..8`、`§7.4 第 N 组`、`§7.4 group/item`） | **0** | 已全部映射，无残留 |
| 已映射旅程名（`§7.4 旅程 …` / `§7.4 journeys …`） | 59（AGENTS 30 + 代码 29） | 保留 |
| 通用 `§7.4`（未带组号，如“§7.4 asks for 2048px”） | 7（AGENTS 5 + 代码 2） | 保留；不是旧组号 |
| 运行时字符串文案 | **0** | 未改 |
| 测试断言 | **0** | 未改（D-11 另跟踪 24 类/旧状态词） |
| 历史报告 | 2 文件 / 9 处（`plans/`，不在 `src/`） | 按“旧编号”保留作对照 |

结论：**旧组号残留 0，D-10 保持关闭**；不存在需要恢复 D-10 的注释残留。

### 3.1 源码注释清单（29 文件 / 29 处）

| 文件 | 新引用 |
|---|---|
| `src/hc/client/conversation/attachments.cljc` | 旅程 6.6（多模态附件） |
| `src/hc/client/copy/failures.cljc` | 旅程 8（设置与诊断）/ §7.7 |
| `src/hc/client/schedule/view.cljs` | 旅程 6.8（定时任务） |
| `src/hc/compose/scheduler/time.clj` | 旅程 6.8（定时任务） |
| `src/hc/server/attachments.clj` | 旅程 6.6（多模态附件） |
| `src/hc/server/attachments/model.clj` | 旅程 6.6（多模态附件） |
| `src/hc/server/backup/model.clj` | 旅程 7（导出、备份与迁移） |
| `src/hc/server/browser.clj` | 旅程 6.4（开浏览器） |
| `src/hc/server/browser/model.clj` | 旅程 6.4（开浏览器） |
| `src/hc/server/browser/service.clj` | 旅程 6.4（开浏览器） |
| `src/hc/server/diagnostics.clj` | 旅程 8.6（导出诊断包） |
| `src/hc/server/materials.clj` | 旅程 5.6 / 6.1（记忆与搜索） |
| `src/hc/server/materials/instruction.clj` | 旅程 5.6（记忆、世界书、指令卡） |
| `src/hc/server/materials/memory.clj` | 旅程 5.6（记忆） |
| `src/hc/server/materials/memory_suggest.clj` | 旅程 5.6（记忆） |
| `src/hc/server/materials/search.clj` | 旅程 6.1（搜索） |
| `src/hc/server/materials/worldbook.clj` | 旅程 5.6（世界书） |
| `src/hc/server/search.clj` | 旅程 6.1（搜索） |
| `src/hc/server/search/config.clj` | 旅程 6.1（搜索） |
| `src/hc/server/search/context.clj` | 旅程 6.1（搜索） |
| `src/hc/server/sessions.clj` | journeys 1–5 |
| `src/hc/server/sessions/config.clj` | 旅程 1.2–1.3（引导与选模型） |
| `src/hc/server/sessions/connections.clj` | 旅程 1.2–1.3（引导与选模型） |
| `src/hc/server/sessions/credentials.clj` | 旅程 1.2–1.3（引导与选模型） |
| `src/hc/server/sessions/drafts.clj` | 旅程 2.1（写草稿） |
| `src/hc/server/sessions/secrets.clj` | 旅程 1.2–1.3（引导与选模型） |
| `src/hc/server/speech.clj` | 旅程 6.7（用语音） |
| `src/hc/server/speech/config.clj` | 旅程 6.7（用语音） |
| `src/hc/server/speech/text.clj` | 旅程 6.7（用语音） |

- 跳过：**0 个**。`src/` 全部为未跟踪文件，`git diff <file>` 对 29 个文件均为空，无法据此区分并行改动；改用 mtime（均早于 15:48）与逐文件原文精确匹配判断，未发现正在编辑的文件。

## 4. history 稳定性复验

命令：`/tmp/pixi run clojure -X:test:server :nses '[hc.server.history-live-smoke-test]'`

| 次数 | 结果 | 断言 | 墙钟 |
|---:|---|---:|---:|
| 1 | PASS | 1 test / 31 assertions，0F/0E | 31.40 s |
| 2 | PASS | 1 test / 31 assertions，0F/0E | 37.34 s |
| 3 | PASS | 1 test / 31 assertions，0F/0E | 32.44 s |

结论：**3/3 PASS**。此前 3/3 失败已不复现；不需要协调失败。

## 5. 对账（master / 门禁清单 / 证据）

**E2E 22/22 收口（final22）**：E2E05 final22 42/42、E2E12 final22 48/48×2，全部 PASS；final22 全量 3824/24735/0F/0E，app-check `:ok true`/0.0.1，版本五处 0.0.1。已派 W130 执行最终门禁复核 + `git add -A` + commit + annotated tag `v0.0.1`（不 push）；commit hash/tag 待 W130 报告后回填。**D-04 自动化口径保持开放；E2E 22/22 为手工/真实端点口径。**

**E2E12 final22 PASS（W121/W116）**：48/48、`ok:true`、NODE_EXIT=0（两个独立样本）；证据 `e2e12-c9cbfdf6-final22-w121.json`、`-assertfix.json` + `shots-w121-final22`；D-27 保持关闭、**D-28 关闭**（`:last-move` 落库、撤销链路端到端 PASS）。**统计：22 PASS / 0 PARTIAL / 0 BLOCKED**（E2E05 final21 42/42、E2E12 final22 48/48×2）。

**final22 最终冻结 PASS（W33/W125）**：bundle `c9cbfdf6…`（3,018,831 B，18:12:03；CSS `332f9fd3`；manifest c2158f6b）+ 树 `1e824c83…`（clean rebuild 确定性）；全量 3824/24735/0F/0E/exit 0/354 s；app-check `:ok true`/`0.0.1`/`:component-errors {}`；D-28 `current-with-local`+`merge-local-state`、E2E11 `bridge/connection`+`bridge/command-allowed?` 已验证入产物。final21 及更旧作废；证据双记 `c9cbfdf6` + `1e824c83`。

**W124 final22 并入收尾**：`materials_bridge.cljs`——`current-with-local`、6 处 db 读取点、`sync-from-db!` connection 用 `bridge/connection`、timer `command-allowed?` 用 `bridge/command-allowed?`；新增 2 条回归（`sync-from-db-preserves-last-move-behind-a-lagging-db`、`sync-from-db-reads-live-adapter-connection`）。verify_materials_bridge 11/31、model-test 57/253 0F/0E；纯客户端、需重建。W125 即将 clean rebuild → final22 全量 + app-check。

**E2E11 suggest-switch-on 并入 final22（W124）**：同竞态族——`:ui/connection` 异步落库 vs `sync-from-db!` 同步读旧值 → `command-allowed?=false` → `queue-command` 拒绝。修复：`materials_bridge.cljs` 两处改用 `bridge/connection` 与 `bridge/command-allowed?`（adapter atom 同步投影），并加断言。W124 收尾后 W125 重建再跑 final22 全量。

**E2E11 final21 隔离复跑（W86）**：22 PASS / 1 FAIL——`suggest-switch-on`：`memory-suggest?` 未置位、「整理记忆」按钮恒 disabled、`settings-update memory-suggest?=true` 被 `command-allowed?` 拒绝（“连接尚未完成同步；命令未发送”）。与 final20 同签名；疑与 W124 的 `transact-state!` 异步/stale-DB 覆盖同源。证据 `e2e11-0fbddd3c-final21.json`；final21 作废，等 final22 复跑。

**D-28 运行时根因/修复（W124）**：`transact-state!` 异步提交，`sync-from-db!`/`apply-receipt-event!` 在 mutation 提交前读到旧 DB（无 `:last-move`）→ 覆盖丢失。修复：新增 `current-with-local(db)`（DB 投影与 `@last-state` 同会话时用 `merge-local-state` 合并 local keys），6 处读取点全改用；测试 57/253 + 10/28 0F/0E。**final21 作废**；W125 clean rebuild → final22 全量 + app-check；E2E12 等 final22。

**E2E12 final21 三样本（W121 run1 42/48、run2 43/48；W116 45/48）**：共同 FAIL = `move-receipt-undo-visible`、`undo-button-after-reselect`、`undo-restores-book`；`entry-back-in-source-book`/`undo-restores-injection` 时序敏感；run1 另有 `card-single-turn` 间歇泄漏。**D-28 未关闭**——unwrap-receipt 在产物中但运行时 `:last-move` 未置位；WS 探针证实服务端回执完整含 `:undo`、客户端已受理并补发 snapshot，断点在客户端回执应用链路。证据 `e2e12-0fbddd3c-final21{,-run2}.json`。

**final21 最终冻结 PASS（W33/W118）**：bundle `0fbddd3c…`（3,018,796 B，16:34:21；CSS `332f9fd3`；manifest c2158f6b）+ 树 `319f035f…`（clean rebuild 确定性 PRE=POST）；全量 3824/24735/0F/0E/exit 0/354 s；app-check `:ok true`/`0.0.1`/`:component-errors {}`；unwrap-receipt 修复已验证入产物。final20 及更旧作废；证据双记 `0fbddd3c` + `319f035f`。

**E2E12 final21 复跑**：45/48（final20 43/48）；D-27 保持关闭；D-28 部分好转（`entry-back-in-source-book`、`undo-restores-injection` PASS），残余 `move-receipt-undo-visible`、`undo-button-after-reselect`、`undo-restores-book`。unwrap-receipt 已入产物、模型单测可置位 `:last-move`，但运行时 `:ui/materials` 仍不可见 → 疑 `transact-state!`/`merge-local-state`/`sync-from-db!`/`apply-snapshot` 链覆盖；W123 修复中，final21 冻结保持，修复后重建 + final22。；残余 3 项 move/undo（`move-receipt-undo-visible`/`undo-button-after-reselect`/`undo-restores-book`），根因疑运行时状态链（`transact-state!`/`merge-local-state`/`sync-from-db!`/`apply-snapshot`）覆盖 `:last-move`，W123→W124 修复、W125 重建：D-28 真正根因=`transact-state!` 异步提交致 `sync-from-db!`/`apply-receipt-event!` 读旧 DB 覆盖 `:last-move`；修复=`current-with-local(db)`+6 处读取点（57/253+10/28 0F/0E）；final21 作废，W125→final22 重冻结，E2E12 待 final22 复跑关闭

**E2E05 final20 复跑（W101 v8，验收证据）**：41/42 PASS——**D-24/D-25 双关闭**（撤销后活客户端显示“压缩 已撤销”、撤销按钮消失；outbound 还原原历史不含摘要）；唯一 FAIL `outboundCounts` 为脚本正则伪阴性（面板 发送10/摘要0/排除0 正确）。证据 `plans/evidence/e2e-05/e2e05-87b0dee4.{json,png}` + `-run.log`，双记 `87b0dee4` + `5e301ce4`。final20 作废转历史，final21 需复核 D-25 保持且 D-28 unwrap-receipt 修复不回归 E2E05 撤销段。W117 确认 D-28 修复：`unwrap-receipt` 任何 `map? :receipt` 解包+merge+外层回填；model_test 57/253、bridge harness 9/26、server 3824/24735 0F/0E；main.js mtime 早于源码编辑，final21 clean rebuild 需核对。

**D-28 真正根因/修复（W117）**：`materials.model/unwrap-receipt` 对网关信封 `{:ok? :command-id :receipt {..}}` 拒绝解包（旧代码要求 `(:command-id receipt)` 为 nil 才取 `:receipt`），网关把 command-id 留在外层 → `receipt-result` 只读外层 `:result=nil` → `:undo` 拿不到 → `:last-move` 不置位/撤销按钮不渲染（move 本身成功）。修复：任何 `map? :receipt` 都解包 + merge + 外层回填；测试 3824/24735 0F/0E。**final20 作废**；W118 clean rebuild（清 cljs-runtime）→ final21 全量 + app-check + 验证 unwrap-receipt 入产物；E2E12 等 final21。

**final20 最终冻结 PASS（W33/W114）**：bundle `87b0dee4…`（3,018,665 B，15:26:31；CSS `332f9fd3`；manifest c2158f6b）+ 树 `5e301ce4…`（PRE=POST）；全量 3823/24731/0F/0E/exit 0/354 s；app-check `:ok true`/`0.0.1`/`:component-errors {}`；D-25/D-27/D-28 修复已入产物；swarm_ui 2 失败为既有。final19 及更旧作废；证据双记 `87b0dee4` + `5e301ce4`。

**E2E12 final20 复跑（冻结后新证据）**：43/48；D-27 全过、D-25 已由 W101 E2E05 final20 复跑确认关闭；5 项 FAIL 集中在 move 撤销/pending 锁（undo/`:last-move`/回执应用）→ **D-28 未根治**。final20 冻结保持，E2E12 维持 PARTIAL；run2b 复核中。

**E2E12 final20 run2b（W95）**：43/48，失败集与第一样本一致（move-receipt-undo-visible、undo-button-after-reselect、undo-restores-book、entry-back-in-source-book、undo-restores-injection）→ **D-28 确定性失败（非间歇）**；D-27 已修复（两样本卡断言全过）。证据 `e2e12-87b0dee4-final20-run2.json`；E2E12 维持 PARTIAL，等 final21。

**D-25 根因/修复（W113）**：snapshot/baseline 非活跃会话 cached-entry 键 `[(:revision session) epoch]`，而 `session-summary` 不含 `:revision` → 键恒 `[nil epoch]` → `:session/by-id` 的 `:context`/`:compressions` 永久缓存 `:applied`（`:session/history` 正确）→ delta 无该路径 → 活客户端不更新；重启正确。修复：`session-summary` 加 `:revision` + 缓存/delta 测试（394/2761 0F/0E）。W110 也修 D-27（draft turn-selection）与 D-28（last-move assoc nil）。**final19 作废**；W114 → final20 重建 + 全量 + app-check；E2E05/12 等 final20。

**E2E05/06/15 final19（W93/W101b）**：E2E05 v8 W101b **38/42**——D-24 关闭（撤销后 outbound 含原消息不含摘要）、**D-25 仍 FAIL**（撤销命令被拒“命令未送达”、UI 仍摘要1/含压缩摘要/撤销按钮在、无“压缩 已撤销”标记）；W93 轮 26/32（proxy64 被清空致 outbound 不可判）；sessionId 间歇由 v8 DOM fallback 解决。E2E15 PASS（regen+版本切换，pageErrors []）；E2E06 PASS（文本腿五轮一致，`extractRouteAvailable=false` 为 OCR 环境限制）。证据 `e2e05-v8-168318c1-final19{,-w101b}`、`e2e06-textleg-168318c1-final19`、`e2e15-v6-regen-168318c1-final19`；tree before `f8477191`/after `25beab95`（漂移，bundle 未变）。D-25 待修。

**final19 最终冻结 PASS（W33/W107）**：bundle `168318c1…`（3,017,514 B，12:42:52；CSS `332f9fd3`）+ 树 `f8477191…`；全量 3818/24714/0F/0E/exit 0/356 s；app-check `:ok true`/`0.0.1`/`:component-errors {}`；D-27/D-28 修复已入产物（materials-bridge 8/23 0F/0E；swarm_ui 2 失败为既有）。final18 及更旧作废；证据双记 `168318c1` + `f8477191`。

**E2E12 final19 复跑（W95）**：43/48 PASS（final18 40/48，+3），证据双记 `168318c1`+`f8477191`；剩余 5 FAIL 明细待 W95 补充（确认 D-27/D-28 是否修复、是否新缺陷）。E2E12 保持 PARTIAL。
- **E2E12 final20 复跑×2（W95/W114，87b0dee4+5e301ce4，并行实例）**：两样本各 48 项 43 PASS / 5 FAIL，失败集完全一致。**D-27 已修复**（指令卡单轮语义全过）；**D-28 未根治**——`撤销移动`/`:last-move`/move 回执应用/pending 锁释放 5 项 FAIL（move-receipt-undo-visible、undo-button-after-reselect、undo-restores-book、entry-back-in-source-book、undo-restores-injection）。证据 `e2e12-87b0dee4-final20{,-run2}.json`；E2E12 维持 PARTIAL，已转 W38/W110。

**E2E12 final19 剩余 5 FAIL 明细（W95）**：**D-27 2 项**——r2/r7 面板仍见 “MR-列假设 · turn”（疑 `clear-turn-after-send!`/version 匹配，或服务端 `last-user` 回退 `system.clj:519`）；**D-28 3 项**——move 后 20 s 无撤销、reselect edit 30 s 无、条目未绑回书 B；move 成功但 `:last-move` 未置位；pending 锁已释放。证据 `e2e12-168318c1-final19.json`、`shots/e2e12-move-fail.png` 等。并行实例 run2 进行中。
- **E2E12 final19 复跑×2（W95，168318c1+f8477191，并行独立实例 /tmp/hc-w22p:7595）**：run1 48 项 43 PASS / 5 FAIL（`e2e12-168318c1-final19.json`）；run2 48 项 42 PASS / 6 FAIL（`e2e12-168318c1-final19-run2.json`）。**两缺陷均为间歇性时序问题**：D-27 卡泄漏 run1 见于 r2/r7、run2 仅 r2（r7 已清 → clear 会延迟生效）；D-28 撤销/:last-move 两跑均不出现，但 pending 锁释放与手动移回 run1 PASS/run2 FAIL（sweep 时序竞争）。世界书多轮/预算/窗口/移动提供性/跨会话隔离断言两跑全过。E2E12 维持 PARTIAL。
- **E2E11 final19 复跑 PASS / D-26 关闭（W86，168318c1+f8477191，并行隔离实例 `/tmp/hc-w22-e2e11:7622`）**：**25/25 断言全 PASS，`ok:true`，NODE_EXIT=0**，证据 `plans/evidence/e2e-11-12/e2e11-168318c1-final19.json`（双记 final19）。`suggest-switch-on` 一次点击即 enabled（706 ms）——final15–18 连续 4 轮 9 PASS/1 FAIL 的 pending 锁在 final19 关闭；临时会话一次性读取与自动关闭全 PASS，`frozenGap:null`。**D-26 已关闭并从 `DEBT.md` 删除**；**D-28 pending 锁已关闭**（E2E12 final19 证据，撤销/:last-move 残留仍待 W108→final20）；D-27 仍待 W108。E2E 统计 **20 PASS / 2 PARTIAL（05/12）/ 0 BLOCKED**；D-07 子项剩 05/12/15。

**materials_bridge.cljs 12:36:37 写入**：写入者未确认（W106 否认），但 W107 PRE/POST 树 hash 同 `f8477191`、bundle 12:42:52 晚于 mtime、harness 在该内容上 8/23 0F/0E；**以内容 hash 为准、不追 mtime；final19 不受影响。**

**树漂移（W106 bug1，11:55–12:04）**：final18（`f8c0f53a`+`6d1ad03b`）基线失效，final19 待建；final18 的 E2E05 v8 仅诊断；E2E15/06 结果仍为 final18 上取得。

**E2E05 v8 final18 诊断（W93）**：sessionId DOM fallback 稳定（`hc-session-fbdf5f26`）、claim `took=true`、prepared/applied PASS（摘要1/发送0条）、originalTurns 3/3；中断于 continued-twice `awaitReply`（人为 kill，非产品失败）；撤销段未执行。tree before=`fb8cc294`；仅诊断，等 final19。

**统计更新（final18）**：E2E **19 PASS / 2 PARTIAL（05/12）/ 1 BLOCKED（11）**；E2E15 在 final18 判 PASS（regen/版本切换/朗读停止；麦克风环境限制单列）；E2E12 仍 PARTIAL（D-27 仅本轮指令卡泄漏、D-28 move 撤销/pending 锁）；D-07 子项 **05/12**。
**统计更新（final19）**：E2E **20 PASS / 2 PARTIAL（05/12）/ 0 BLOCKED**；E2E11 PASS（25/25，D-26 关闭并从 `DEBT.md` 删除）；E2E15 PASS；E2E12 PARTIAL（D-27/D-28 残留，W108→final20）；D-07 子项 **05/12**；DEBT 开放 17 条、建议阻断 13 条。

**E2E05（final17）**：e2e05v5 再次失败——`sessionId=null`（新建会话 4 次重试未取到）、browser context 在 `awaitReply` 早期关闭（Target page closed），仅 3 checks；v5 在 final16/final17 两轮均中断。备选 `w93-e05-v8.sh` 已排队（DOM fallback + reuse-blank + takeover claim）；E2E15/06 由 run5 继续；证据双记 `efbc465a`+`99aa81a4`。

**E2E05 v8 诊断（W93，efbc465a，非 final18 验收）**：sessionId DOM fallback 生效（`hc-session-de56348a`）、controller-claim `took=true`、composerStable/attachmentTextSendable PASS；但 `prepared=false`（6 次重试“使用摘要”未出现）、originalTurns 仅 2/3（第三轮 reply missing），按指令终止。fallback/claim 修复有效；prepare/apply 段需 final18 复跑确认。产物 `/tmp/hc-w3e5r4/evidence64/e2e05.json` + png、`evidence-final/e2e05-v8-final17-run.log`。

**E2E12 final18 复跑（W33）**：48 断言 40 PASS / 8 FAIL，证据 `plans/evidence/e2e-11-12/e2e12-f8c0f53a-final18.json`。8 FAIL 均为真实缺陷：**(a) 仅本轮指令卡泄漏 3 项**——client 镜像 command payload 不含 turn 选择，`clear-turn-after-send!` 永不触发；**(b) 条目移动撤销不可用 + 同 key pending 锁不释放 5 项（含级联）**——move 回执未应用、`:last-move` 缺失，疑 D-26 残留（final18 已含 sweep 仍复现）。世界书多轮/预算/窗口/移动提供性断言全过。E2E12 保持 PARTIAL；**D-26 需补 move 路径**。

**D-27/D-28 修复进展（W106）**：bug1（D-27）验证通过；bug2（D-28）根因 `set-materials-state` stale 整体丢弃 receipt 写入 → move 命令卡 `:submitting`；修复 `merge-local-state`（model/materials_bridge/mutations）+ orphan drain + 5s sweep；测试 106/783 + 10/67 0F/0E。**final18 作废**；W107 跑完整 client 测试 → 重建 bundle → **final19** 全量 + app-check；E2E05/11/12 等 final19 复跑。

**D-27/D-28 详细根因（W104）**：**D-27**——`send-snapshot!` 只用 draft/edit/attachments/quotes 播种 reducer，`:composer/send` 不带 snapshot，payload 永缺 `:turn-card-ids`/`:instruction-values`/`:turn-selection-version`；且 version 从未进入 draft/snapshot/view/draft-from-snapshot 的 `select-keys`。修法：捕获的 snapshot 作为 `:composer/send` 权威 payload，并把 version 补进各层 `select-keys`；服务端本就接受 payload turn 键，无协议变更。**D-28**——`set-materials-state` stale 分支整体丢弃 receipt 写入（Fulcro `transact!` 异步致 revision 推进），且 `apply-receipt-event!` 在命令未落库时直接丢弃回执、不 park；修复 `merge-local-state` + deferred 重读 + orphan park/drain。测试：verify_materials_bridge 8/23、verify_workbench_session_commands 5/23、JVM 3818/24714 全 0F/0E。

**D-27 文件定位更正（W106）**：turn 清除逻辑实际在 `materials_bridge.cljs`（`accepted-send-command`/`submitted-turn-selection`/`clear-turn-after-send!`，约 254–300 行）；`conversation_bridge.cljs` 257–260 行只保留 send snapshot 的 `select-keys` 透传。bug2 修复与 `mutations.cljs` stale 合并已在树中；测试 106/783 + 10/67 全 0F/0E。W107 可重建 final19。

**D-26 根因/证据/修复计划**：W97 durable gate 探针 `commands/session/w22-s1` 仅 4 条 receipt（1×`settings/update read?`+`suggest?false`、3×memory approve/reject），**suggest 的 `settings/update` 从未到达 `accept!`**；客户端→服务端传输层丢失，`send!` 返回 true 但无 receipt，pending 锁永久持有；自愈 `recover-pending-materials-commands!` 仅在 `sync-from-db!` 调用，传输死亡期间不触发；`transport/receipt!` 在 subscription-id nil 时静默返回 false 空转。修复（W99）：P1 独立 5s 清扫 + `receipt!` 可重试，且覆盖 subscription-id nil 分支；P2 `:submitted` ack 不释放锁；测试后 **final18** 重建 + 全量。当前 final17 仍为基线，final18 后证据重绑。

**D-26 修复已落地（W99）**：`model.cljc`（`:submitted` 非终态、`:not-found`→`:refused`）+ `materials_bridge`（5s 独立清扫 + dispatch 后清扫）；CLJS bridge 测试 4/12 0F/0E；JVM model 测试待 W100 重跑。**final17 树已漂移（`0a62fd2c`），final17 冻结暂停**；W100 将重建 bundle → **final18** 全量 + app-check 后重冻结。E2E05/11 等 final18；E2E15/06 已有结果待 W93/W98 回填。

**final18 最终冻结 PASS（W33/W100）**：bundle `f8c0f53a…`（3,017,311 B，11:21:45；CSS `332f9fd3`）+ 树 `6d1ad03b…`（120 s 稳定，PRE=POST）；JVM 聚焦 57/271 0F/0E；全量 3816/24705/0F/0E/exit 0/355 s；app-check `:ok true`/`0.0.1`/`:component-errors {}`；meta `plans/evidence/final18-freeze-meta.json`。**D-26 sweep 已入产物**（`setInterval(h_b,5E3)` + `install! receipt-sweep-timer`；bundle 内 `setInterval` 4 处）。final17 及更旧作废；E2E 证据双记 `f8c0f53a` + `6d1ad03b`。

**final17 最终冻结 PASS**：bundle `efbc465a`（3,016,559 B，10:11:35；clean rebuild 逐字节相同；manifest c2158f6b；CSS `332f9fd3`/`77da0dd4`）+ 树 `99aa81a4`（PRE=POST）；全量 3813/24695/0F/0E/exit 0/357 s；app-check `:ok true`/`0.0.1`/`:component-errors {}`；git porcelain 16、`hs_err` 9。W87 修复已在 bundle（closure.variable.map 证实 `e_b`/`d_b`/`c_b`）；W86 的 grep 未命中为 release `:simple` 改名误报。E2E11 若仍报 pending 命令，原因在服务端 pending 锁/时序。**E2E05/06/15 的 final16 run5 结果仅作诊断；统计保持 18 PASS / 3 PARTIAL（05/12/15）/ 1 BLOCKED（11）**；D-07 仍为 05/11/12/15（4 条）；DEBT 13 条开放、阻断 9 条。

**final16b 复核 PASS（W92）**：PRE=POST `99aa81a4` 同树补跑全量 **3813/24695/0F/0E/exit 0/355 s**，app-check `:ok true`/0.0.1/`:component-errors {}`/store open；bundle/CSS 未变，git porcelain 16，`hs_err` 9；日志 `/tmp/hc-full-test-final16b.log`、meta `plans/evidence/final16b-freeze-meta.json`。**final16 冻结成立**，E2E 证据双记 `efbc465a` + `99aa81a4` 生效。

| 项 | 当前值 | 核对结果 |
|---|---|---|
| `plans/e2e-master-status-0.0.1.md` §3 | **18 PASS / 3 PARTIAL / 1 BLOCKED**（最终冻结 efbc465a/99aa81a4；旧证据绑 a14d9abd/651b9a45、72de4f9a/ab801450、732baf05/79f21de9 为前冻结） | 06/07/08/09 升 PASS、11 BLOCKED；PASS=01/02/03/04/06/07/08/09/10/13/14/16/17/18/19/20/21/22；PARTIAL=05/12/15；BLOCKED=11 |
| master §4 | **3 条**（05/12/15） | 06/07/08/09 已升 PASS、11 已转 BLOCKED 并从 §4 删除；与 §3 的 3 PARTIAL 一致 |
| D-08/D-09 关闭 | 不改变 E2E 统计 | D-08 对应 E2E13 早已 PASS；D-09 是 DEBT 缺陷，不新增/减少 E2E 结论 |
| 最终冻结 bundle | **`efbc465a`（3,016,559 B）+ 源码树 `99aa81a4…`**。**final16 单树全量 PASS**：3813 tests / 24695 assertions / 0F / 0E / exit 0 / 353.44 s；app-check `:ok true`/`0.0.1`/`:component-errors {}`。**全量门禁条件 5 满足**。final15（`a14d9abd`/`651b9a45`）及更早作废。最终树含 W41 D-16、W23 `:program-status`、W49 D-11、W50 D-12、W53 D-18、W54 `:chat?`、W55 D-13、W52 materials 一次性只读、W56 E2E12 nonce/回执、W57/W48 orphan watchdog、W63 D-22、W35 D-23、W70 pair-hint compaction boundary + merge-owner-context、W71 apply-snapshot turn-card/`:instruction-turn`/`reset-draft-for-command`/`drain-orphan-receipts`、W79 E2E12 session-receipt | 所有 E2E/视觉证据双记 `efbc465a` + `99aa81a4`，旧证据（含 `a14d9abd`/`651b9a45`、`732baf05`/`79f21de9`、`72de4f9a`/`36fddd6d`、`072587a4`/`6aa694f1`、`2b269e0d`）前冻结；不再写 src/test 或重建 |
| `plans/final-gate-inventory-0.0.1.md` | 已修正 | 对账行统一为 **18 PASS / 3 PARTIAL / 1 BLOCKED（E2E 11）**、master §4 3 条；D-07 为 4 条；DEBT 11 条、阻断 7 条 |
| W13 前冻结树全量（`55e4e8ea`） | **PASS**：3741 tests / 24260 assertions / 0F / 0E / exit 0 / 370 s（11:54:12–12:00:22）；bundle `55e4e8ea`；PRE=POST src/test 合并 sha256 `04f859ac…`；`app-check` exit 0 / 16 s / `:ok true`；峰值 VmHWM 2.05 GiB，无新 `hs_err` | 已核对 `plans/full-test-baseline-current.md` 第 2/4/5 节；冻结窗口内无 src/test 写入；新冻结 `8b210896` 已复跑 PASS（见下方“W13 全量（最终树）”行） |
| D-08/D-09 复跑（`8b210896`/`ea9e5732` 已完成，均失效） | D-08 由 W14 12:20、D-09 由 W9 在 `8b210896` 重跑 PASS：D-08 A 含 `Skill: d08-skill`、B 不含且跟随/无 active；D-09 branch `model-b`/source `model-a`、`B-REPLY`、无 epoch 错误；E2E15 12:23 重跑 PASS；证据绑 `8b210896`（现**前冻结**） | 不满足最终门禁；待 `072587a4` 最终 hash 下由 W9/W14 复跑 |
| E2E09 证据状态 | **已 PASS**（最终冻结 bundle `072587a4` + 树 `6aa694f1`，`e2e09-final-pass-072587a4.json` / `e2e09-final.json` 16:11–16:12，`ok=true`、30/30 checks 全 true；retryApplied、appliedCard=APPLIED、`.hc-protected-program` 渲染、窄视口通过） | E2E09 升 PASS；旧 `ea9e5732`/`0c05b7d1`/`55e4e8ea` 证据仅作历史 |
| E2E05 复跑时间边界 | W34 复用的 `/tmp/hc-e2e05` app（java 455494）约 12:16 启动；若页面加载早于 12:17:19，实际加载的 main.js 仍是 `55e4e8ea`（前冻结），须标“前冻结”并在 `072587a4` 重跑 | 以页面实际加载的 main.js hash 为准，不看进程启动时间 |
| W13 全量（`8b210896`，已失效） | **PASS**：3746 tests / 24286 assertions / 0F / 0E / exit 0 / 365 s；seed 171 条；峰值 VmHWM 1.90 GiB；PRE=POST src/test hash `3fae6313…`；bundle `8b210896` 未变；git 1861→1861；`hs_err` 9→9；`app-check` exit 0 / 14 s / `:ok true` | 该树因 13:00–13:01 的 4 个 src 改动失效；W35 已在 `ea9e5732`/`52547df2` 新树复跑 PASS（见下条） |
| W35 全量（前冻结 `ea9e5732`，已作废） | **PASS**：3748 tests / 24306 assertions / 0F / 0E / exit 0 / 365 s（13:04:16–13:10:21）；PRE=POST src/test `52547df2…`；bundle `ea9e5732` 未变；`hs_err` 9→9；峰值 VmHWM 1.94 GiB | 该树因 13:29 `main.cljs` 写入失效，仅作历史；新冻结见 final6 行 |
| E2E05 证据（`8b210896`，前冻结） | W34 以 pageBundle 实测 `8b210896`；压缩预览/编辑/使用/摘要不升级/原消息可读/发送 0 条·摘要 1 PASS；继续两轮消息已发（outbound“发送 4 条·摘要 1”）但 assistant 文本为空、脚本在 digest 处崩溃；撤销/新会话未跑 → **PARTIAL**；锁已于 12:48 释放 | 不宣称 PASS；`8b210896` 已失效，需在 `072587a4` 重跑 |
| W26 最终视觉复验（`8b210896`，前冻结） | 13 张 `r2-final-*.png` + `r2-final-min.json`/`r2-final-views.json`（运行时写 hash）；B14 360/820 `#wb-nav-close` 44×44、焦点回 `#wb-menu`、backdrop 可关；B15 360/430/820/1024/1280 宽=视口、send.right ≤ 视口；DOM 7/31 0F0E | 残留：820 操作 41px/版本 32px、1280 消息高 300/104；原报告 4 项（W19 英文、无模型主操作、默认标题、消息 token）未修；`8b210896` 已失效，视觉证据需在 `072587a4` 重绑/复跑 |
| 冻结有效性告警（13:02→13:03） | 13:00–13:01 的 4 个 src 改动（`conversation/export.cljc` 13:00:55、`conversation/view.cljs` 13:01:43、`history/context.clj` 13:01:51、`app/system.clj` 13:01:51）不在 `8b210896`；已重建 `ea9e5732`（13:02:42，3,010,646 B；CSS `332f9fd3` 未变）。该 hash 后续因 13:29 `main.cljs` 写入失效，`0c05b7d1` 又因 13:50 改动失效；现冻结为 `072587a4`/`a97c68f7`，final7 13:55–14:08 验证中 | `8b210896`/`3fae6313`、`ea9e5732`、`0c05b7d1` 全量均作废；门禁以 final7 为准 |
| 新树 app-check（13:05） | exit 0 / `:ok true` / `:version "0.0.1"` / `:component-errors {}`（W32，`/tmp/w32-app-check.out`）；版本五处 0.0.1；`--help` 首行 `Herdchestra 0.0.1 server`（13:04 新树） | 已核对 |
| W35 final6 全量（前冻结 `0c05b7d1`，已作废） | **PASS**：3749 tests / 24310 assertions / 0F / 0E / exit 0 / 365 s（13:38:43–13:44:48）；PRE=POST 树 `7667323a…`、bundle `0c05b7d1`、CSS `332f9fd3` 未变；`hs_err` 9→9；峰值 1.93 GiB | 该树因 13:50 `main.cljs` 写入被 `072587a4` 取代，仅作历史 |
| W35 final7 全量（最终冻结 `072587a4` / `a97c68f7`） | **PASS**：3749 tests / 24310 assertions / 0F / 0E / exit 0 / 380 s（13:53:52–14:00:12）；PRE=POST 树 hash `a97c68f7…`、bundle `072587a4`、CSS `332f9fd3` 均未变；git 1877→1879（+2 为 plans/evidence）；`hs_err` 9→9；峰值 1.97 GiB；354 ns；14:00:56 复核单树 | **全量门禁条件 5 在最终冻结树满足**；final6 的 3749/24310 仅旧树历史 |
| W25 材料视觉（跳过） | 按裁决跳过最终复验；无 `ea9e5732` 证据；已有 21 张材料截图仍绑 `dd968793`/`dda62554`（前冻结）；原因见 `plans/visual-audit-materials.md` §11.8（13:02 base URL 尾斜杠导致 replaceState SecurityError；13:06 按让行裁决停止）；`w25b-failure.json` 为诊断 | 不阻断发布；若需严格绑定，待下次锁窗口重拍 |
| `DEBT.md` | 13 条开放（D-01/D-03/D-04/D-07/D-11/D-12/D-13/D-17/D-18/D-19/D-20/D-22/D-23）；D-16 已关闭并从 `DEBT.md` 删除；D-15 已关闭移除 | 已按 W30/W41 定稿核对 |

### 5.1 最终一致性核对

- **E2E 06**：master §1/§2/§3/§4 已一致为 **PASS**（真实 Ollama + 真实浏览器）；§4 的 06“无”行已删除，§2 的 06 裁决行亦已删除，D-07 已移除 06。
- **E2E 08**：最终冻结 72de4f9a/ab801450 上完整 PASS（`:chat?` 修复后 assistant 长文本流产生，`escToolCallPath=false`；Esc 停止/保留/不迟到全 true；S1/提交“42”/unpark/同一 run/composer 15 s 恢复 PASS）；master §1/§2/§3/§4 已一致，§4 已删除 08。
- 最终裁决为 **18 PASS / 3 PARTIAL / 1 BLOCKED**（06/07/08/09 升 PASS、05/12/15 PARTIAL、11 BLOCKED）；master line 10、§3 表与 §4 已一致。
- 06 已按 2026-11-06 10:34 真实 Ollama 复跑升 PASS；D-07 已移除 06。
- **E2E01 自动化（`ea9e5732`）FAIL 33.8 s**：UI 行显示 `工具：已声明支持`、`bind.toolsDeclared=true`，Plan 仍返回 `MODEL_TOOLS_UNAVAILABLE`、未产生用户消息；**D-AUTO-02 已并入 D-12，D-12 保持开放**，不得把 E2E01 自动化写成 PASS。
- **E2E09 两口径**：自动化 spec PASS（17.6 s，15/15，`clientBundle=ea9e5732`）；真实 UI FAIL（`ok=false`、初始快照未送达，D-16）。二者分开记录，自动化 PASS 不升级真实 UI。
- **E2E09 最终 PASS（72de4f9a + ab801450）**：W41 复跑 ok=true、30/30 checks；冲突卡/stamp、外部编辑保留、重读后 APPLIED、worker,7 落盘、窄视口 360×360 全 PASS；D-16 已关闭，D-07 移除 09 子项。
- **D-08/D-09 最终复跑（`072587a4` + 树 `d1d8d08d`）**：D-09 branch `[model-b]`/source `[model-a]`/`B-REPLY`/epoch_error=false（`branch-model-switch.json` final_rerun=`072587a4`、`src_tree=d1d8d08d`）；D-08 A 请求含 `Skill: d08-skill`、B 不含且跟随/无 active（`session-skill-ui.json` verified=`072587a4`、`src_tree=d1d8d08d`、ok=true）。**均 PASS**；`0c05b7d1`/`ea9e5732` 及更旧证据降为前冻结。
- **全量基线（历史）**：**final10 在树 `6aa694f1…` + bundle `072587a4…` 上 PASS**：3758/24351/0F/0E/exit 0/365 s（15:51:13–15:57:18，PRE=POST 单树）；app-check exit 0/15 s/`:ok true`/0.0.1。（当时满足；现由 final11 取代）。历史：final9 候选 `e465f626` 与中间树 `3fa10fd7`/`d6f02477` 作废；final7/6/5/4 与 `52547df2` 均仅历史；`ea9e5732`、`0c05b7d1` 作废。E2E/视觉证据须双记 `072587a4` 与 `6aa694f1`。
- **final16 全量（最终冻结）**：bundle `efbc465a`（3,016,559 B，08:43:18；CSS `332f9fd3` 未变）+ 源码树 `99aa81a4` 单树 **PASS**：3813/24695/0F/0E/exit 0/353.44 s；app-check `:ok true`/0.0.1/`:component-errors {}`；**全量门禁条件 5 满足**；证据双记 `efbc465a` + `99aa81a4`。final15（`a14d9abd`+`651b9a45`）及更早作废。
- **final15 全量（历史）**：bundle `a14d9abd`（3,015,902 B，05:50:40；CSS `332f9fd3`）+ 源码树 `651b9a45` 单树 **PASS**：3808/24669/0F/0E/exit 0/356 s（05:54:29–06:00:25；PRE=POST `651b9a45`、git 2027、`hs_err` 9、峰值 2.05 GiB）；app-check `:ok true`/0.0.1/`:component-errors {}`；**当时全量门禁条件 5 满足**；证据双记 `a14d9abd` + `651b9a45`。
- **final14 全量（历史）**：bundle `732baf05…`（3,015,403 B，04:33:20；CSS `332f9fd3`）+ 源码树 `79f21de9…` 单树 **PASS**：3805/24650/0F/0E/exit 0/352.10 s（04:34:50–04:40:42；PRE=POST 树 `79f21de9`、git 16、`hs_err` 9；04:31:34 后无 src/test 写入）；app-check exit 0/14.16 s/`:ok true`/`0.0.1`/`:component-errors {}`；**全量门禁条件 5 满足**。final13 与 `2b269e0d` 作废；D-23 仍待 E2E12；证据双记 `732baf05` + `79f21de9`。
- **final14 结果（04:40）**：final14 3805/24650/0F/0E（PRE 树 `79f21de9`、git 2025、`hs_err` 9；04:31:34 后无 src/test 写入）；bundle `732baf05` 未变；app-check `:ok true`/`0.0.1`/`:component-errors {}`；**全量门禁条件 5 满足**；final13 及更旧前冻结。
- **D-23 已修复（W35，服务端 only，客户端 bundle 不因该修复重建）**：`:session/send-message` 经 commands 网关/`dispatch`/`default-owner-kinds` 到达 `sessions.clj` 分支，并写 session run + input-snapshot，使“上次发送”冻结快照可达；不改断言。代码/测试/全量已入 final15 树 `651b9a45`（亦含于前冻结 `79f21de9`）；**待 E2E12 复跑验证“上次发送”**。D-22（`history.clj` NPE 残留）仍开放。
- **D-23 状态**：**W35 已落地**，代码/测试完成、待 E2E12 验证；final17 最终冻结树 **`99aa81a4…`**（bundle `efbc465a`）；final15 及更旧失效；final16 E2E 复跑后证据双记 `efbc465a` + `99aa81a4`。
- **final11–final15 内含修复待验证**：W49 D-11（failures 十类）、W50 D-12（tools）、W55 D-13（nudge idle）、W53 D-18（E2E05 空文本）、W56 E2E12、W52 materials 一次性只读、W54 E2E08 `:chat?`、W57/W48 orphan watchdog、W63 D-22、W35 D-23、**W70**（`system.clj` pair-hint `compaction-boundary`/`supersede-chain` + `merge-owner-context` 保留更新压缩记录；`pair_hint/hint.clj` `:compaction` boundary/`reanchor`/`:compaction-cursor`）、**W71**（`materials/model.cljc` apply-snapshot 保留 turn-card/`:instruction-turn`、`reset-draft-for-command` 覆盖 entry-move/entry-delete、`drain-orphan-receipts`；`materials.cljs`/`materials_bridge.cljs`/`sync/adapter.cljc` 接线）、**W79**（`model.cljc` `begin-session`/`select-book`、`materials_bridge.cljs` `transact-state!`，E2E12 session-receipt）已入最终树 `99aa81a4`；W83/W87 修复（D-24/D-25/D-26）状态以 final16 E2E 复跑为准；D-11/D-12/D-13/D-22/D-23 待 UI/E2E 复跑验证后再关闭。
- **D-17/D-21 最终复跑（6aa694f1）**：D-21 已验证生效（新会话请求含摘要）；继续一/二主请求摘要 1、被替代 0；继续两轮 assistant 均有回复（D-18 已修）。**D-17 nudge/devops 请求仍 summary 0、replaced>0（残留未完全消除）**；撤销按钮切回后未渲染、撤销未完成；E2E05 保持 PARTIAL。
- **W86 final15 E2E11 复跑（a14d9abd+651b9a45）**：9 PASS / 1 FAIL；`suggest-switch-on` 失败为产品缺陷 **D-26**（settings-update 命令回执未生效、pending 锁未释放，UI「同一目标已有待确认命令；请等待回执。」，加等待/重试仍失败），W87 修复已入 final16 树 `99aa81a4`（bundle `efbc465a`），E2E11 维持 BLOCKED。**根因（W33 登记）**：传输层丢失——`settings/update send!` 返回 true 但 durable gate 无 receipt → pending 锁永久持有；`recover` 仅 `sync-from-db!` 触发、`receipt!` 在 subscription-id nil 时空转。**W99 实施 P1（独立 5s 清扫 + `receipt!` 可重试）+ P2（`:submitted` ack 不释放锁）→ final18**；final17（`efbc465a`+`99aa81a4`）仍为基线，待 final18 复跑关闭。证据 `plans/evidence/e2e-11-12/e2e11-a14d9abd-final15.json`。
- **W76 final15 E2E 复跑（a14d9abd+651b9a45）**：E2E05 继续两轮/撤销后再发一轮 PASS（D-22 修复确认）；新缺陷 **D-24**（撤销后 outbound 仍含摘要、缺被撤销 3 条原消息）、**D-25**（`压缩 已撤销` 标记未渲染）已入 `DEBT.md`，W83 修复已入 final16 树 `99aa81a4`（bundle `efbc465a`），待 final16 E2E 复跑关闭；E2E06 text leg PASS（OCR 环境限制）；E2E15 regen PASS（D-22/NPE 不再出现）。证据 `plans/evidence/e2e-05/e2e05-a14d9abd.json`。
- **冻结暂停（final17→final18 过渡）**：W99 已落地 D-26 修复（`model.cljc` `:submitted` 非终态/`:not-found`→`:refused`；`materials_bridge` 5s 独立清扫 + dispatch 后清扫；CLJS 4/12 0F/0E；JVM 测试待 W100），final17 树漂移至 `0a62fd2c`，**final17 冻结暂停**；W100 重建 bundle → final18 单树全量 + app-check 后重冻结。E2E05/11/12 等 final18 基线复跑；在此之前 `efbc465a`+`99aa81a4` 证据仅作诊断。
- **最终冻结（final18，W33/W100）**：bundle `f8c0f53a`（3,017,311 B，11:21:45；CSS `332f9fd3` 未变）+ 源码树 `6d1ad03b`（120 s 稳定，PRE=POST）；全量 **3816 tests / 24705 assertions / 0F / 0E / exit 0 / 355 s**；app-check `:ok true`/0.0.1/`:component-errors {}`。D-26 sweep 已入产物（`setInterval(h_b,5E3)` + `install!` receipt-sweep-timer；bundle 内 setInterval 4 处）。final17 及更旧作废。**E2E/视觉证据双记 `f8c0f53a` + `6d1ad03b`**；可复跑 E2E05/06/11/15；D-24/D-25/D-26 待 final18 E2E 复跑确认后关闭。
- **W95 final18 E2E12 复跑（f8c0f53a+6d1ad03b）**：48 项 40 PASS / 8 FAIL；新缺陷 **D-27**（turn-card 泄漏：`:ui/command` payload 无 turn 键 → `submitted-turn-selection` 取空 → `clear-turn-after-send!` 不触发，仅本轮指令卡每轮注入永不清除）、**D-28**（move 成功但 `:last-move` 未置位 → 撤销按钮不出现、手动移回被 pending 锁拒绝、5s sweep 未恢复），W104 修复中，E2E12 维持 PARTIAL，待 final19 复跑关闭。证据 `plans/evidence/e2e-11-12/e2e12-f8c0f53a-final18.json`。
- **W102 final18 E2E11 复跑（f8c0f53a+6d1ad03b）**：9 PASS / 1 FAIL（`suggest-switch-on`，D-26 未关闭）。新证据：服务端 `:settings` 已写入（read-switch 生效）但首个 `[:materials/settings]` 回执未到客户端、5s sweep 未解锁；疑似 receipt scope 不匹配（materials 命令 scope=`app` vs 客户端 `receipt!` 带 `:session-id`；`handle-receipt!` 应回 `:not-found`→`:refused` 解锁，故更可能 `receipt!` 发送失败/事件未投递或 scope 错位）。W105 修复中，待 final19 复跑关闭。
- **final18 作废 → final19 准备中**：W106 已完成 bug2/D-28 修复（`set-materials-state` stale 丢弃 receipt → move 卡 `:submitting`；修复 `merge-local-state` + orphan drain + 5s sweep），bug1/D-27 验证通过；相关测试 106/783 + 10/67 0F/0E。W107 跑 final19 全量 + app-check 后重冻结；E2E 等 final19 基线复跑，`f8c0f53a`+`6d1ad03b` 证据仅作诊断。
- **W95 final19 E2E12 复跑（168318c1+f8477191）**：43 PASS / 5 FAIL。D-27 残留：指令卡仍在 w22-s1 r2/r7 泄漏（疑似 `system.clj:519` last-user 回退或 clear/version 匹配缺口）；D-28 部分生效：pending 锁已释放、手动移回成功，但 move 回执仍未应用、`:last-move` 仍缺失。W108 修复中，待 final20 复跑关闭。证据 `plans/evidence/e2e-11-12/e2e12-168318c1-final19.json`。
- **W93 final19 E2E05/06/15 复跑（168318c1+f8477191）**：**D-24 关闭**（撤销后 outbound 含全部原消息、不含摘要，已从 `DEBT.md` 删除）；**D-25 残留 FAIL**——撤销命令被拒「命令未送达」、UI 仍摘要/撤销按钮、无「压缩 已撤销」标记，疑 epoch/selection-seq 在 new-session 切换后过期，W111 修复中待 final20；E2E06 PASS（OCR 环境限制）、E2E15 PASS；E2E05 仍 PARTIAL。
- **E2E11 final19 PASS / D-26 关闭（168318c1+f8477191）**：25/25 断言全过——`suggest-switch-on` 一次点击即 enabled（706 ms），候选审批/拒绝/改范围、归档排除、临时会话一次性读取与自动关闭全 PASS，`frozenGap:null`；证据 `plans/evidence/e2e-11-12/e2e11-168318c1-final19.json`。**D-26 已关闭并从 `DEBT.md` 删除**；E2E 统计 **20 PASS / 2 PARTIAL（05/12）/ 0 BLOCKED**；D-07 子项剩 05/12/15。
- **最终冻结（final19，W33/W107）**：bundle `168318c1`（3,017,514 B，12:42:52；CSS `332f9fd3` 未变）+ 源码树 `f8477191`（PRE=POST）；全量 **3818 tests / 24714 assertions / 0F / 0E / exit 0**；app-check `:ok true`/`0.0.1`/`:component-errors {}`。final18 及更早作废。**E2E/视觉证据双记 `168318c1` + `f8477191`**。
- **final19 作废 → final20 准备中**：D-25 根因已由 W113 修复（cached-entry 键 `[nil epoch]` 致 `:context/compressions` 永久 `:applied`、delta 缺失；session-summary 加 `:revision`，测试 394/2761 0F/0E）；W110 修 D-27（draft turn-selection）与 D-28（last-move assoc nil）。树已变（14:18–15:11 客户端/服务端/测试），bundle `20f3b90f`（14:12:20）落后。W114 重建 → final20 全量 + app-check 后重冻结；E2E05/12 等 final20 基线复跑；冻结窗口内停写 src/test、停重建、停 E2E。
- **最终冻结（final20，W33/W114）**：bundle `87b0dee4cec24e7ffc720c96f25bafcb45fd73ca91b6444a030ed51e0fe6da94`（3,018,665 B，15:26:31；CSS `332f9fd3` 与 manifest `c2158f6b` 未变）+ 源码树 `5e301ce4`（PRE=POST 120 s 稳定）；全量 **3823 tests / 24731 assertions / 0F / 0E / exit 0 / 354 s**；app-check `:ok true`/0.0.1/`:component-errors {}`。标记核对通过（D-08 四标记、AbortController 19、hc-markdown、hc-message-stream、hc-protected-program 14、hc-round-context 2、session-scope 2、resolved-skills 2、speech 31；turn-selection 8、last-move 2）。**D-25（cached-entry `:revision`）、D-27（draft turn-selection）、D-28（last-move assoc nil）修复已入产物**；swarm_ui 2 断言失败为既有。final19 及更早作废。**E2E/视觉证据双记 `87b0dee4` + `5e301ce4`**；可复跑 E2E05/12（D-25/D-27/D-28 关闭确认）。
- **final20 作废 → final21 准备中**：D-28 根因=`unwrap-receipt` 对网关信封 `{:ok? :command-id :receipt {..}}` 拒绝解包 → `receipt-result` 只读外层 `:result=nil` → `:undo`/`:last-move` 缺失；修复（W117）=任何 `map? :receipt` 解包+merge+外层回填；测试 3824/24735 0F/0E。final20（`87b0dee4`+`5e301ce4`）作废转历史；W118 clean rebuild → final21 全量 + app-check 后重冻结；E2E12 等 final21 基线复跑（D-28 关闭确认）。
- **统计更新（final20）**：E2E **21 PASS / 1 PARTIAL（12）/ 0 BLOCKED**；E2E05 final20 复跑 41/42 PASS、D-24/D-25 双关闭（`plans/evidence/e2e-05/e2e05-87b0dee4.json`，双记 `87b0dee4`+`5e301ce4`；唯一 FAIL `outboundCounts` 为脚本正则伪阴性，面板 发送10/摘要0/排除0 正确）；E2E12 仍 PARTIAL（D-27 已修、D-28 move 撤销/`:last-move` 5 项 FAIL，待 final21）；D-07 子项仅 12；DEBT.md 15 条开放、建议阻断 11 条。
- **统计更新（final22）**：E2E **22 PASS / 0 PARTIAL / 0 BLOCKED**。E2E12 final22 断言口径修正后 **48/48 PASS**（`plans/evidence/e2e-11-12/e2e12-c9cbfdf6-final22-assertfix.json`，含 `assertionRuling` 注记：`move-receipt-undo-visible` 改验「回执应用=编辑器复位 create 模式」，撤销按钮仅 edit 模式渲染属设计差异非缺陷；D-27/D-28 双关闭——`:last-move` 落库、reselect edit 下「撤销移动」出现并成功撤销，`undo-restores-book`/`undo-restores-injection`/`entry-back-in-source-book` 全 PASS）；E2E11 final22 **25/25 PASS**（`e2e11-c9cbfdf6-final22.json`，`suggest-switch-on` 关闭、`:memory-suggest?` 置位、`frozenGap:null`）；E2E05 final22 **42/42 PASS**（D-24/D-25/D-28 修复不回归撤销段）。证据统一双记 `c9cbfdf6` + `1e824c83`；D-07/D-27/D-28 已关闭并从 `DEBT.md` 删除，DEBT.md 剩 12 条开放、建议阻断 8 条（D-04/D-11/D-12/D-13/D-17/D-18/D-22/D-23）。；**提交/tag 状态**：W130 正在执行最终门禁复核 + `git add -A` + commit + annotated tag `v0.0.1`（不 push）；commit/tag hash 待回填。D-04（22 条 E2E 自动化口径）保持开放——22/22 为手工/真实端点口径，不等于新 §8.5 自动化验收。
- **E2E 并发策略（用户指令，取代此前单执行者/锁窗口裁定）**：多个 worktree/实例可并发跑测试与 E2E，独立 data-dir/profile/端口互不干扰；E2E05/06/11/12/15 可并行；此前 W98 run5 单执行者安排作废。证据双记 final19 `168318c1` + `f8477191`（final18 `f8c0f53a`+`6d1ad03b` 已作废）。
- **复跑目标（最终冻结）**：D-07/D-17/D-22/D-23 与 E2E05/12/11/08/15 以 **bundle `efbc465a` + 树 `99aa81a4…`**（最终冻结）为准；证据同时记录两者；旧 hash（含 final15 `a14d9abd`+`651b9a45`、final14 `732baf05`+`79f21de9`、final13 `72de4f9a`+`36fddd6d`、`2b269e0d`）一律前冻结。D-22/D-23 已由 W76 final15 复跑确认修复；D-24/D-25/D-26 待 final16 E2E 复跑确认后关闭。
- **D-16 已关闭（W41 修复 + E2E09 复跑 PASS）**：根因不是 gateway 时序，而是 snapshot/baseline 的 board-view 产出 `:task/by-id` 等表用 keyword keys（`:task-1`），客户端 `normalize/to-db` 的 `normalize-table` 要求 string entity IDs → `:invalid-entity` → resync → `STATE_SNAPSHOT` 无限重发 → 永远 awaiting-sync、db 无 `:session/by-id`。修复在 `src/hc/server/app/snapshot.clj`：baseline 出口 `normalize-entity-tables` 把 `*/by-id` 表 keys 转 string（feed/delta 同树）；回归 `snapshot-unresolved-calls-test` 4/27/0F/0E。E2E09 在 `072587a4`/`6aa694f1` 上 30/30 全 true、`ok=true`，D-16 已从 `DEBT.md` 删除。

## 6. 待跟进

| 工人 | 跟进 |
|---|---|
| W5 | D-11 24→10 映射与测试断言 |
| W10 | D-04 补 19 条 E2E 自动化并接入 mock 服务；D-12 重跑 E2E01 新建连接路径 |
| W3 | D-13 Nudge 永不 idle：补可观察的轮次结束边界或 UI 原因说明 |
| W9 | D-11 文档侧 24 类统计与发布说明同步 |
| W2/W6 | D-01 `askPreviousScope` 回执/答案 steer 或经裁决降低承诺 |
| W3 | D-03 底稿磁盘回读与润色 |
