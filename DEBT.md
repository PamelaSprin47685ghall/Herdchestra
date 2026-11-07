# DEBT

本文件记录 `AGENTS.md` 要求但当前实现尚未做到、或实现与 spec 存在已知偏差的缺口。每条包含：条款位置、缺口描述、影响、建议处理方式。只记缺口，不记已完成项；缺口关闭时删除本条。追赶 spec 的 AI 以本文件为工作队列。

编号不重排、不复用：已关闭条目（如 D-02、D-05、D-06、D-08、D-09、D-10、D-14、D-16）从本文件删除，历史状态见 `plans/debt-status-0.0.1.md`。

## D-01 `askPreviousScope` / `negotiate` 只有账本快照

- **条款**：`AGENTS.md` §4.1 能力表、§6.2 第 2 条。
- **spec 要求**：向紧邻前一个 Scope 提澄清问题；同步返回受理 receipt；答案随后以 `role:"user"`、`attribution:agent` 的 steer 到达；receipt 持久化、幂等重放；不扣 retention。
- **实现现状**：`src/hc/core/scope/scope.clj:313-330` 的 `snapshot` 只返回 `{:kind :negotiate :goal :budget :remaining :established? :must-reset :spin :resets :notice}`；`scope.clj:331-344` 的 `previous-scopes`/`preceding-scope` 只读本地 reset 历史；`src/hc/compose/workspace.clj:252-272` 的 `control-port` 把 `:askPreviousScope` 交给 fallback，返回 `{:ok? false :error :UNSUPPORTED_OPERATION}`；无封存档案、摘要、回执、异步答案 steer。
- **影响**：长任务后期，agent 无法向上一任 Scope 追问「为什么这么决定」，只能重读或猜；用户看不到跨 Scope 的决策链。
- **建议**：按 spec 补齐封存视图 + 摘要 + 回执幂等 + 世代失效丢弃；或经人类裁决后修订 spec 降低承诺。

## D-03 用户编辑底稿后无润色与回读

- **条款**：`AGENTS.md` §2.3「用户可编辑底稿」、§7.4 旅程 3.3。
- **spec 要求**：用户可直接编辑 `plans/<name>.md`；Plan 下次读取时看到用户改动，负责润色到与上下文一致；`stamp` 冲突以用户版本为准。
- **实现现状**：`src/hc/core/relay/core.clj:379-412` 的 `set-draft` 只存 `{:path :content}`，此后从不回读磁盘；`src/hc/core/relay/core.clj:790-848` 的 `start-execution` 用 relay 里的旧 `:content` 作为 handoff 载荷；`src/hc/core/workspace_gate/prepare.clj:835-844` 对用户改过的文件以 `:FILE_CONFLICT :stamp-mismatch` 拒绝 Plan 写入（不覆盖用户版本，但也不合并）；全仓库无润色/对齐逻辑。
- **影响**：用户改了底稿，Plan 不会感知并润色；执行层拿到的是旧文本。
- **建议**：Plan 每次读取底稿时以磁盘为准，比较 relay 记录与磁盘差异，生成润色补丁；handoff 前强制重读磁盘。

## D-04 22 条 E2E 只自动化 3 条，mock 服务未接入全部剧本

- **条款**：`AGENTS.md` §8.5「补充：E2E 执行方式」。
- **spec 要求**：22 条剧本全部自动化；配套 mock 外部服务（OAuth、WebDAV、S3）与 mock 模型服务；手工验收只用于无法覆盖的平台差异。
- **实现现状**：`package.json` 有 `@playwright/test ^1.64.1` 与 `e2e` / `e2e:core` / `e2e:mock` / `e2e:memory` 脚本；`test/e2e/` 有 E2E01/03/09、`mock-services.spec.mjs`、`memory.spec.mjs`、`playwright.config.mjs`。2026-11-06 串行实跑：`npm run e2e:mock` PASS（1.2s）；`npm run e2e:memory` PASS（900 请求，heap +19.8MiB，RSS +68.7MiB，354ms）；E2E03 PASS（ea9e5732，前冻结，36.3s）；E2E09 PASS（ea9e5732，前冻结，17.6s）；E2E01 FAIL（ea9e5732，前冻结）：UI 已显示 `工具：已声明支持`，发送仍被 Plan 以 `MODEL_TOOLS_UNAVAILABLE` 拒绝，未产生用户消息（D-12，原 D-AUTO-02 已并入；见 `plans/e2e-auto-defects.md`）。3 条 spec 已存在，其余 19 条未见 spec；mock 服务未接入这 19 条。
- **影响**：E2E03/09 在 `ea9e5732`（前冻结）上通过；E2E01 被 D-12（原 D-AUTO-02 已并入）阻断；其余 19 条仍靠 `plans/e2e-0.0.1-runbook.md` 手工或真实端点补充证据，发布前无法自动回归。
- **建议**：补其余 19 条，优先 E2E 05/08/11/12/15 的核心断言；把 mock 服务接入全部剧本。

## D-11 24 类失败文案未重组为十类“未交付理由”

- **条款**：`AGENTS.md` §7.4 旅程 8.5（排障与 24 类文案重组）、§8.5。
- **缺口**：`src/hc/client/copy/failures.cljc:16` 仍以 24 类 `failure-kinds` 为单一事实来源；`test/hc/client/copy/failures_test.clj:66-68` 直接断言 24 类完整；`test/hc/client/swarm/ui_test.cljs:264,276`、`test/hc/client/conversation/selection_dom_test.cljs:470`、`test/hc/client/conversation_test.clj:740` 仍断言旧并列状态词“结果待确认”“已停止”。新 spec 要求 24 类重组为十类“未交付理由”，用户侧状态收敛为进行中/已交付/未交付（附理由与教训）。
- **影响**：界面仍可能把内部错误类别当成用户侧状态；测试锁定旧口径，阻碍文案收敛。
- **建议**：保留 24 类作为内部映射，新增“24→10”映射与断言；把 4 个测试断言改为十类/三种状态或明确它们是操作反馈而非状态；同步仍按 24 类统计的发布/审计文档（至少 `release-notes`、`release-gate-checklist`、`server-audit`、`ui-contract-audit`）。归 W5/W9。

## D-12 手动模型 `:tools` 声明未进入 binding，E2E01 仍 `MODEL_TOOLS_UNAVAILABLE`（D-AUTO-02 已并入）

- **条款**：`AGENTS.md` §7.4 旅程 1.2/1.3（引导、选模型）、§8.5 E2E 01。
- **缺口**：W8 已修保存/composer/选择门禁：手动模型保存为 `:not-returned`，composer 放行 `:unknown`，设置页允许“用于下一轮”。`src/hc/compose/owner/handlers.clj:120-160` 已让用户声明的 `:tools true` 优先于 live 探测（探测为 nil 时回退声明；显式 false 仍拒绝）。但 E2E01 新建连接证据 `plans/evidence/e2e-auto/e2e01-new-connection.json` 显示模型 `manual-only`、发送按钮 `disabled=false`，保存后的 config 仍是 `manual-only :capabilities {}`——UI 能力编辑没有进入保存载荷。能力声明驱动已在 `test/e2e/support/ui.mjs:238-275` 改为原生 value setter + 冒泡 `change`（11:21），E2E01 spec 在 11:11 复跑超时（`plans/evidence/e2e-auto/playwright-results.json`），尚未重跑。
- **影响**：新用户按首次引导新建本地模型连接后，composer 虽可点发送，但 Plan/Reviewer 轮次失败；E2E01 自动化不能 PASS。
- **建议**：用新驱动重跑 E2E01 新建连接路径，确认保存后 binding/config 的 `:capabilities :tools` 为 true 且助手回复出现；若仍为空，修设置页能力保存链；补“手动模型绑定后可发送并收到回复”的服务端回归。归 W5/W8/W10。
- **证据**：`plans/d12-fix-report.md`、`plans/e2e-auto-defects.md` D-AUTO-02、`plans/evidence/e2e-auto/e2e01/e2e01-result.json`、`plans/evidence/e2e-auto/e2e01-new-connection.json`、`plans/evidence/e2e-auto/playwright-results.json`。
- **裁决（13:20）**：本条**保持开放**。旧的 `:unknown`/composer 禁用部分已关闭（手动模型 `:not-returned`、composer 放行 `:unknown`、设置页“用于下一轮”）；剩余缺口是用户声明“工具：已声明支持”（`bind.toolsDeclared=true`）后，`:tools true` 仍未进入会话 binding，E2E01 在 `ea9e5732`（前冻结）上仍 FAIL 33.8 s、Plan 返回 `MODEL_TOOLS_UNAVAILABLE`、未产生用户消息。D-AUTO-02 已并入本条，不再单列。DEBT 11 条、阻断 7 条（D-04/D-07/D-11/D-12/D-13/D-17/D-18；D-16 已关闭）；最终冻结 bundle `072587a4` + 树 `6aa694f1`。

## D-13 Plan 账本未清空时会话永不 idle（Nudge 持续注入）

- **条款**：`AGENTS.md` §2.5（常授权无人值守与防自然停止）、§7.4 旅程 3（长任务）。
- **缺口**：E2E 04 第三轮在 stub 主模型下通过：只发一次、收到 `THIRD-REPLY`、顺序正确；但 `idleAfter1`、`idleAfter2`、`idleBeforeThird` 全为 false。毫秒级 stub 仍持续显示“正在生成/无人值守·自动继续”，停止后又进入。W3 判定：不 idle 是产品 Nudge 行为——Plan 账本未清空时持续注入督促轮次，不是模型速度。
- **影响**：会话没有稳定 idle 窗口；用户停止后仍可能自动继续；自动化无法以 idle 作为轮次完成信号，容易误判“回复未到/仍在生成”，并影响停止后发送与 E2E 等待条件。
- **建议**：Nudge 需提供可观察的“本轮结束”边界（例如账本清空或明确 `:awaiting-user` 等待态），或 UI 明确显示账本未清空原因与仍需处理项；E2E 04 的等待条件改为可观察的轮次边界而非 idle。归 W3/W5。

## D-17 E2E05 压缩 apply 后 run 仍冻结压缩前 `:input-snapshot`

- **条款**：`AGENTS.md` §8.5 E2E 05 第 7 条（继续时不同时带摘要与被替代历史）、§2.5。
- **缺口**：W34 在 `8b210896` 实测：压缩 apply 后，run 的 `:input-snapshot` 仍是压缩前投影；proxy seq 14 的 19 条仍含被替代历史（“请只回复：事实/假设/取消”、E2E05 事实），摘要出现 0 次；而 store 的 `:context` 投影已正确为 `[摘要 system, 继续一, assistant, 继续二, assistant]` 5 条、`summary-ids=[cmp-708057b8…]`。`run_port/frozen-entries` 在 `:input-snapshot` 缺失时只会发摘要，19 条旧历史说明 run 冻结了压缩前快照；`conversation_bridge/send-snapshot!` 的 `:composer/send` 不带 context。即持久层已切换、运行期快照未失效。
- **影响**：E2E05 第 7 条失败；继续轮会把摘要与被替代历史同时发给模型，压缩语义在真实发送路径上不成立。属发布阻断。
- **建议**：`run_port` 在 apply 回执合并时失效/重建 `:input-snapshot`，或让 `conversation_bridge/send-snapshot!` 的 `:composer/send` 携带 store 当前 `:context`；补“apply 后下一轮 outbound 只含摘要、不含被替代历史”的服务端/客户端回归。证据 `plans/e2e-real-05-06.md` §4.4、`plans/evidence/e2e-05/e2e05.json`。归 W34/W5。
- **修复（全量已验证，E2E05 待复跑）**：W34/W5 于 13:14:32 修改 `src/hc/server/history/run_port.clj` 与 `test/hc/server/history/run_port_test.clj`；W35 final7 在最终冻结 `072587a4`/`a97c68f7` 上 **PASS**（3749 tests / 24310 assertions / 0F / 0E / exit 0 / 380 s，PRE=POST 单树）；E2E05 仍需在 `072587a4` 上复跑“继续时只带摘要、不带被替代历史”后才能关闭。
- **final20 复跑确认关闭（W101 v8，`87b0dee4`+`5e301ce4`）**：41/42 PASS；`outboundExcludesReplaced`/`outboundHasBothContinues`/`outboundSummary`/`originalTurnsReplied`/`afterUndoReply`/`answersSurviveAfterThird`/`answersSurviveAfterUndo` 等断言全 true——继续轮不再同时带摘要与被替代历史，继续两轮有可见 assistant 回复。唯一 FAIL `outboundCounts` 为脚本正则伪阴性（面板 发送10/摘要0/排除0 正确）。证据 `plans/evidence/e2e-05/e2e05-87b0dee4.json`。D-17/D-18 已关闭，随 final21 复核后可从 `DEBT.md` 删除。

## D-18 E2E05 继续轮 assistant 文本为空（模型返回 tool_calls）

- **条款**：`AGENTS.md` §8.5 E2E 05（继续轮回答）、§3 流式阅读。
- **缺口**：W34 实测继续轮模型返回 `tool_calls`，UI 的 `reply1`/`reply2` 为空；消息已发出（outbound“发送 4 条·摘要 1”），但用户看不到 assistant 文本。
- **影响**：E2E05 继续轮回答断言无法通过；真实模型走工具调用时用户可能看不到任何回复。属发布阻断。
- **建议**：UI/投影渲染 `tool_calls` 轮次的可见文本（或明确的工具调用状态），无文本时给可读回执；补“tool_calls 轮次仍有可见 assistant 内容”的回归。证据同上 §4.4/§4.5。归 W34/W5。
- **final20 复跑确认关闭（W101 v8，`87b0dee4`+`5e301ce4`）**：`originalTurnsReplied`/`afterUndoReply`/`answersSurviveAfterThird` 全 true，继续轮与撤销后轮均有可见 assistant 文本。证据 `plans/evidence/e2e-05/e2e05-87b0dee4.json`。D-18 已关闭，随 final21 复核后可从 `DEBT.md` 删除。

## D-19 仅配置 `:main` 时 devops worker 报 unknown model role

- **条款**：`AGENTS.md` §6.2 角色与模型绑定、§8.5 E2E 05。
- **缺口**：W36 诊断：config 只有 `:main` 时，devops worker 报 `snapshot names an unknown model role`，devops 角色无法运行。
- **影响**：单模型配置下 devops 路径不可用；E2E05/06 的 devops 相关步骤不可靠。
- **建议**：角色投影在缺少显式 devops 绑定时回退 `:main`，或在配置/快照层给出可操作错误；补单模型配置回归。证据 `plans/e2e-real-05-06.md` §4.5。归 W5/W36。

## D-20 Plan nudge 轮出现 `:SCOPE_ANNOTATION_MISSING`

- **条款**：`AGENTS.md` §2.5 Nudge、§6.2 Scope 注释。
- **缺口**：W36 诊断：Plan nudge 轮出现 `:SCOPE_ANNOTATION_MISSING`，该轮被拒。
- **影响**：Nudge 自动继续在缺少 scope annotation 时失败，长任务可能停摆。
- **建议**：Nudge 轮生成合法 scope annotation，或允许明确的空注释语义；补“nudge 轮不再因缺少注释被拒”的回归。证据 `plans/e2e-real-05-06.md` §4.5。归 W3/W36。




## D-22 pair-hint placement anchor 在消息集变化后失效，压缩/重生成后模型请求投影失败（W61 发现，W63 已修主因）

- **条款**：`AGENTS.md` §8.5 E2E 05（压缩后继续）、§8.5 E2E 15（重新生成版本）、§6.2 提示注入。
- **缺口（已由 W63 修复）**：`run_port.clj` `model-message` 与 `context.clj` `model-messages` 丢弃 `:message-id` → pair-hint 重放 `PAIR_HINT_ANCHOR_MISSING` → `pair-hint: request projection failed` → run 在模型调用前终结，UI 报"服务器报告本轮未完成"。W61 在 ab801450 上实测：含压缩记录的会话此后所有发送确定性失败（proxy 零请求）；`:history/regenerate` run 同样死亡。W63 修复保留 `:message-id`，新树 `36fddd6d…`，bundle `72de4f9a` 不变。
- **残留缺口**：`src/hc/server/history.clj` `regenerate`/`append-version`/`select-version` 等拒绝分支以 `(error :CODE ...)` 构造错误，但 `error` 被 `{:keys [message error]}` 解构遮蔽为 nil → `:OWNER_ERROR` NPE（`Cannot invoke IFn.invoke(Object,Object,Object) because "error" is null`）。happy path 不受影响；RUN_ALREADY_ACTIVE/目标非法等拒绝路径仍 NPE。
- **影响**：修复前 E2E05 继续两轮/撤销后发送、E2E15 regen 产生 v2 均不可用（发布阻断，已由 W63 主因修复关闭，待新树复跑确认）。
- **建议**：拒绝分支改用独立构造器（如 `command-error`）或重命名解构键；补"压缩后发送投影成功""regenerate 产生 v2""拒绝路径返回结构化错误"回归。证据 `plans/evidence/e2e-05/e2e05-72de4f9a-run6.json`、`e2e05-recheck-72de4f9a.json`、`plans/evidence/e2e-13-15-16-21/e2e15-v6-regen-defect-72de4f9a.json`、`plans/e2e-real-05-06.md` §6。

## D-23 UI 发送不写 session-doc `:runs`，出站面板恒为“预览”

- **条款**：`AGENTS.md` §6.2（上下文与出站投影）、§8.5 E2E 12（世界书多轮/上次发送）。
- **缺口**：UI 发送走 Owner `:send-message`（`src/hc/compose/owner/handlers.clj:1429` → `user-message`），只把 `:history/append-message` 镜像到 session doc，**不写 `:runs`/`:run-order`**；而 `frozen-outbound`（`src/hc/server/history/snapshot.clj:89-103`）只读 `[:runs <last run-order> :input-snapshot :context]`，该字段仅由 `sessions/apply-command :session/send-message`（`src/hc/server/sessions.clj:1074+`）与 `:history/regenerate` 写入。W47 实测 DB：`run-order []`、`runs {}`，但 messages 有内容 → 面板恒显示“本轮上下文 · 预览”，E2E12 七轮的“上次发送”断言在产品层面不可达。
- **影响**：用户看不到“上一次实际发送”的注入上下文；E2E12 多轮/世界书“上次发送”断言不可达，属发布阻断。
- **建议**：按产品缺口修——UI 发送路径在 session doc 写入该轮 `:run-order`/`:runs[:input-snapshot :context]`（或让 `frozen-outbound` 在无 run 时以最近一次用户消息 + 当前投影生成 `:source :last-run`，但需明确语义）；**不**改 E2E12 断言口径。修后 src 变化，需重建新冻结并重跑全量。归 W47/W5；W47 疑似卡死、修复未开始，待 Main 改派 W48/W5 或授权 W35。final12（`72de4f9a`/`7dfcf0d7`）在改动人声明前仍有效。
- **修复（W35，待 E2E12 复跑）**：`src/hc/server/sessions/runs.clj` 新增 `record-outbound`；`sessions.clj` 新增 `:session/record-outbound`；`commands.clj` 登记 metadata kind；`handlers.clj` 在 accepted send 后调用 `mirror-outbound-run!`。单测新增 +2 tests/+11 assertions，相关 ns 0F/0E；final13 全量 **3795/24596/0F/0E/exit 0/356 s**（bundle `72de4f9a` + 树 `36fddd6d`，PRE=POST）。UI 发送后面板 `:source :last-run`；E2E12「上次发送」待真实 UI 复跑确认后关闭本条。
