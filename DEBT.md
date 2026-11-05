# DEBT

本文件记录 `AGENTS.md` 要求但当前实现尚未做到、或实现与 spec 存在已知偏差的缺口。每条包含：条款位置、缺口描述、影响、建议处理方式。只记缺口，不记已完成项；缺口关闭时删除本条。追赶 spec 的 AI 以本文件为工作队列。

D-02 已裁决为「存储写入 fail-fast，不设第四态」，不再作为缺口；裁决记录见 HISTORY.md。

## D-01 `askPreviousScope` / `negotiate` 只有账本快照

- **条款**：`AGENTS.md` §4.1 能力表、§6.2 第 2 条。
- **spec 要求**：向紧邻前一个 Scope 提澄清问题；同步返回受理 receipt；答案随后以 `role:"user"`、`attribution:agent` 的 steer 到达；receipt 持久化、幂等重放；不扣 retention。
- **实现现状**：`src/hc/core/scope/scope.clj:313-330` 的 snapshot 只返回 `{:kind :negotiate :goal :budget :remaining :established? :must-reset :spin :resets :notice}`；`src/hc/compose/workspace.clj:256-262` 的 `askPreviousScope` 返回 `:UNSUPPORTED_OPERATION`；无封存档案、摘要、回执、异步答案 steer。
- **影响**：长任务后期，agent 无法向上一任 Scope 追问「为什么这么决定」，只能重读或猜；用户看不到跨 Scope 的决策链。
- **建议**：按 spec 补齐封存视图 + 摘要 + 回执幂等 + 世代失效丢弃；或经人类裁决后修订 spec 降低承诺。

## D-03 用户编辑底稿后无润色与回读

- **条款**：`AGENTS.md` §2.3「用户可编辑底稿」。
- **spec 要求**：用户可直接编辑 `plans/<name>.md`；Plan 下次读取时看到用户改动，负责润色到与上下文一致；`stamp` 冲突以用户版本为准。
- **实现现状**：`src/hc/core/relay/core.clj:379-410` 的 `set-draft` 只存 `{:path :content}`，此后从不回读磁盘；`src/hc/core/relay/core.clj:833-840` 的 `start-execution` 用 relay 里的旧 `:content` 作为 handoff 载荷；`src/hc/core/workspace_gate/prepare.clj:835-844` 对用户改过的文件以 `:FILE_CONFLICT :stamp-mismatch` 拒绝 Plan 写入（不覆盖用户版本，但也不合并）；全仓库无润色/对齐逻辑。
- **影响**：用户改了底稿，Plan 不会感知并润色；执行层拿到的是旧文本。
- **建议**：Plan 每次读取底稿时以磁盘为准，比较 relay 记录与磁盘差异，生成润色补丁；handoff 前强制重读磁盘。

## D-04 22 条 E2E 只覆盖 3 条，mock 外部服务不完整

- **条款**：`AGENTS.md` §8.5「补充：E2E 执行方式」。
- **spec 要求**：22 条剧本全部自动化；配套 mock 外部服务（OAuth、WebDAV、S3）；mock 模型服务；手工验收只用于无法覆盖的平台差异。
- **实现现状**：`package.json` 已有 `@playwright/test ^1.64.1` 与 `e2e` / `e2e:core` / `e2e:mock` 脚本；`test/e2e/` 已有 `e2e-01-empty-install.spec.mjs`、`e2e-03-streaming-draft.spec.mjs`、`e2e-09-protected-commit.spec.mjs`、`mock-services.spec.mjs`、`playwright.config.mjs`；其余 19 条未见 spec。以上为静态核对，未运行。
- **影响**：核心 3 条可重复验证；其余 19 条仍靠 `plans/e2e-0.0.1-runbook.md` 手工，发布前无法自动回归。
- **建议**：补其余 19 条，优先 E2E 04（历史编辑）、07（搜索来源）、08（S1/角色边界）、10（结果未知）。
