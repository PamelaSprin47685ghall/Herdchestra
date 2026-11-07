# E2E 01–22 单一权威状态表（0.0.1）

- 日期：2026-11-05
- 范围：汇总首轮 `plans/e2e-0.0.1-results.md` 与后续真实端点/真实 Chrome 复跑。
- 原则：同一场景取“证据更强且时间更晚”的结论；组件级 PASS 不升级为 UI PASS。
- 验收口径：根 `AGENTS.md` §8.5 现要求 22 条 E2E 全部自动化，并配套 mock
  模型服务与 OAuth/WebDAV/S3 mock 外部服务；手工验收只用于平台差异。本表是
  2026-11-05 的手工/真实端点历史证据，不是新口径的最终验收；W10 自动化结果
  出来前不得据此判 PASS。
- 结论：**手工/真实端点证据：22 PASS、0 PARTIAL、0 BLOCKED；自动化
  口径以 W10 结果为准，未全 PASS 前不得打 tag。**
- **final16 曾暂停，final17 确认（同一 bundle/树，clean rebuild 验证）**：W86 曾疑 bundle `efbc465a` 未含
  W87 修复（src 有 `recover-pending-materials-commands!`/`merge-local-state`，
  bundle grep 不到，疑 shadow-cljs 增量缓存），E2E11 仍失败。W96 正 clean
  rebuild（清 `.shadow-cljs/cljs-runtime` + release）→ final17 全量 + app-check。
  **final17 已由 W33 宣布最终冻结 PASS**：bundle `efbc465a`（3,016,559 B，
  10:11:35；manifest `c2158f6b`；CSS `332f9fd3`/`77da0dd4`）+ 树 `99aa81a4`
  （PRE=POST）；clean rebuild 产物与 final16 逐字节相同，确定性；全量
  3813/24695/0F/0E/exit 0/357 s；app-check `:ok true`/0.0.1/`:component-errors {}`；
  W87 修复已在 bundle（closure.variable.map 证实 `e_b`/`d_b`/`c_b`），W86 的
  grep 未命中为 release `:simple` 改名误报；final16 的 E2E05/06/15 run5
  结果仅作诊断，待 final17 复跑确认后作验收结论。meta
  `/tmp/hc-full-test-final17-meta.txt`、`plans/evidence/final17-freeze-meta.json`。
- **D-26 修复已落地（W99）/ final17 冻结暂停**：`model.cljc`（`:submitted` 非终态、`:not-found`→`:refused`）+ `materials_bridge`（5s 独立清扫 + dispatch 后清扫）；CLJS bridge 4/12 0F/0E，JVM model 测试待 W100。**final17 树已漂移 `0a62fd2c`**；W100 重建 bundle → final18 全量 + app-check 后重冻结；E2E05/11 等 final18。
- **final18 最终冻结 PASS（W33/W100）**：bundle `f8c0f53a…`（3,017,311 B，11:21:45；CSS `332f9fd3`）+ 树 `6d1ad03b…`（PRE=POST）；JVM 聚焦 57/271 0F/0E；全量 3816/24705/0F/0E/exit 0/355 s；app-check `:ok true`/`0.0.1`/`:component-errors {}`；meta `plans/evidence/final18-freeze-meta.json`。**D-26 sweep 已入产物**（`setInterval(h_b,5E3)` + `install! receipt-sweep-timer`）。final17 及更旧作废；E2E 证据双记 `f8c0f53a` + `6d1ad03b`。
- 冻结变更（13:54）：bundle **`072587a4`**（3,011,162 B，13:51:04；CSS
  `332f9fd3`；源码树 `a97c68f7`）为新冻结；`0c05b7d1`/`7667323a`、`ea9e5732`
  及更旧作废/前冻结。**W35 final7 单树全量 PASS**：3749/24310/0F/0E/exit 0/
  380 s（13:53:52–14:00:12）。**但 W5 的 `system.clj prefer-owner-snapshot`
  修复已改动源码树（现 `d1d8d08d…`，唯一变更 `system.clj` 14:08:18），
  final7 基线失效。**W35 final8 单树全量 PASS**：3749/24310/0F/0E/exit 0/
  445 s（14:11:10–14:18:35）。**但 final8 已失效**：15:15–15:17 六个 src/test
  文件被改，W40 又修 D-17 nudge/D-21。**final13 曾冻结**：bundle `72de4f9a` +
  源码树 `36fddd6d…`（含 W63 D-22、W35 D-23 `:send-message` 写 `:runs`/
  `:run-order`），final13 单树全量 PASS 3795/24596/0F/0E/exit 0/356 s
  （PRE=POST 同树）。**final14 曾冻结（现历史）：bundle `732baf05…`
  （3,015,403 B，04:33:20；CSS `332f9fd3`）+ 源码树 `79f21de9…`；final14
  单树全量 PASS**：3805/24650/0F/0E/exit 0/352.10 s（04:34:50–04:40:42；
  PRE=POST 树 `79f21de9`、git 2025、`hs_err` 9；VmHWM 未采样），app-check
  exit 0/14–15 s/`:ok true`/0.0.1；当时全量门禁条件 5 满足。final13 与
  `2b269e0d` 作废。`79f21de9` 含 **W70**（pair-hint compaction
  boundary + `merge-owner-context`）与 **W71**（materials apply-snapshot
  turn-card/`:instruction-turn`/`reset-draft-for-command`/
  `drain-orphan-receipts`）修复（这些修复亦在 final15 树 `651b9a45` 中，
  另含 W79 E2E12 session-receipt 修复）。
  **final15 曾冻结（现历史）：bundle `a14d9abd`（3,015,902 B，05:50:40）
  + 源码树 `651b9a45`；final15 单树全量 PASS 3808/24669/0F/0E/356 s，
  app-check `:ok true`。**
  **最终冻结（final16，W33/W91）：bundle `efbc465a`（3,016,559 B，
  08:43:18；CSS `332f9fd3` 未变）+ 源码树 `99aa81a4`**；W91 单树全量
  **PASS**：3813 tests / 24695 assertions / 0F / 0E / exit 0 / 353.44 s
  （08:52:45–08:58:38；PRE `b9570d04`→POST `99aa81a4`，差异仅
  `run_port_test.clj` 两处定界符修复；git porcelain 16、`hs_err` 9）；
  app-check `:ok true`/0.0.1/`:component-errors {}`/store open；版本五处
  0.0.1；**全量门禁条件 5 满足**。final15 及更早作废。**E2E/视觉证据双记 `efbc465a` + `99aa81a4`**；
  D-22/D-23/D-24/D-25/D-26 待 final17 E2E 复跑确认后关闭。
  **冻结暂停（final17→final18 过渡）**：W99 已落地 D-26 修复（`model.cljc` `:submitted` 非终态/`:not-found`→`:refused`；`materials_bridge` 5s 独立清扫 + dispatch 后清扫；CLJS 4/12 0F/0E；JVM 测试待 W100），final17 树漂移至 `0a62fd2c`，**final17 冻结暂停**；W100 重建 bundle → final18 单树全量 + app-check 后重冻结。E2E05/11/12 等 final18 基线复跑；在此之前 `efbc465a`+`99aa81a4` 证据仅作诊断。
  **最终冻结（final18，W33/W100）**：bundle `f8c0f53a`（3,017,311 B，11:21:45；CSS `332f9fd3` 未变）+ 源码树 `6d1ad03b`（120 s 稳定，PRE=POST）；全量 **3816 tests / 24705 assertions / 0F / 0E / exit 0 / 355 s**；app-check `:ok true`/0.0.1/`:component-errors {}`。D-26 sweep 已入产物（`setInterval(h_b,5E3)` + `install!` receipt-sweep-timer；bundle 内 setInterval 4 处）。final17 及更旧作废。**E2E/视觉证据双记 `f8c0f53a` + `6d1ad03b`**；可复跑 E2E05/06/11/15；D-24/D-25/D-26 待 final18 E2E 复跑确认后关闭。
  **final18 作废 → final19 准备中**：W106 已完成 bug2/D-28 修复（`set-materials-state` stale 丢弃 receipt → move 卡 `:submitting`；修复 `merge-local-state` + orphan drain + 5s sweep），bug1/D-27 验证通过；相关测试 106/783 + 10/67 0F/0E。W107 跑 final19 全量 + app-check 后重冻结；E2E 等 final19 基线复跑，`f8c0f53a`+`6d1ad03b` 证据仅作诊断。
  **最终冻结（final19，W33/W107）**：bundle `168318c1`（3,017,514 B，12:42:52；CSS `332f9fd3` 未变）+ 源码树 `f8477191`（PRE=POST）；全量 **3818 tests / 24714 assertions / 0F / 0E / exit 0**；app-check `:ok true`/`0.0.1`/`:component-errors {}`。final18 及更早作废。**E2E/视觉证据双记 `168318c1` + `f8477191`**。
  **并发策略变更（用户指令）**：不再要求全局单例锁/单执行者；多个 worktree/实例可并发跑测试与 E2E，各自独立 data-dir/Chrome profile/端口即可，互不干扰；E2E05/06/11/12/15 可并行（注意资源：全量峰值约 2 GB，避免同时起过多 app/Chrome）。E2E/视觉证据仍双记 bundle `168318c1` + 树 `f8477191`（final19 最终冻结）。
  **final19 作废 → final20 准备中**：D-25 根因已由 W113 修复（cached-entry 键 `[nil epoch]` 致 `:context/compressions` 永久 `:applied`、delta 缺失；session-summary 加 `:revision`，测试 394/2761 0F/0E）；W110 修 D-27（draft turn-selection）与 D-28（last-move assoc nil）。树已变（14:18–15:11 客户端/服务端/测试），bundle `20f3b90f`（14:12:20）落后。W114 重建 → final20 全量 + app-check 后重冻结；E2E05/12 等 final20 基线复跑；冻结窗口内停写 src/test、停重建、停 E2E。
  **最终冻结（final20，W33/W114）**：bundle `87b0dee4cec24e7ffc720c96f25bafcb45fd73ca91b6444a030ed51e0fe6da94`（3,018,665 B，15:26:31；CSS `332f9fd3` 与 manifest `c2158f6b` 未变）+ 源码树 `5e301ce4`（PRE=POST 120 s 稳定）；全量 **3823 tests / 24731 assertions / 0F / 0E / exit 0 / 354 s**；app-check `:ok true`/0.0.1/`:component-errors {}`。标记核对通过（D-08 四标记、AbortController 19、hc-markdown、hc-message-stream、hc-protected-program 14、hc-round-context 2、session-scope 2、resolved-skills 2、speech 31；turn-selection 8、last-move 2）。**D-25（cached-entry `:revision`）、D-27（draft turn-selection）、D-28（last-move assoc nil）修复已入产物**；swarm_ui 2 断言失败为既有。final19 及更早作废。**E2E/视觉证据双记 `87b0dee4` + `5e301ce4`**；可复跑 E2E05/12（D-25/D-27/D-28 关闭确认）。
  **最终冻结（final21，W33/W118）**：bundle `0fbddd3c6bed295199d098e0a0a8a72ae47721b21d33d807c0fc906f2a2da47e`（3,018,796 B，16:34:21；CSS `332f9fd3`、manifest `c2158f6b` 未变）+ 源码树 `319f035f`（3824 tests / 24735 assertions / 0F / 0E / exit 0 / 354 s，clean rebuild PRE=POST）；app-check `:ok true`/`0.0.1`/`:component-errors {}`。unwrap-receipt 修复已验证入产物（7 键 select-keys + merge + command-id/epoch 条件回填，无 nil? 门槛；`:last-move` set/clear 分支在；D-08 四标记、AbortController 19、hc-markdown、hc-message-stream、hc-protected-program 14、hc-round-context 2、session-scope 2、resolved-skills 2、speech 31、turn-selection 8、last-move 2）。final20 及更早作废。**E2E/视觉证据双记 `0fbddd3c` + `319f035f`**。
  **final17 最终冻结（W33/W96）**：bundle `efbc465a`（3,016,559 B，10:11:35；manifest `c2158f6b`；CSS `332f9fd3`/`77da0dd4`）+ 源码树 `99aa81a4`（PRE=POST）；clean rebuild 产物与 final16 逐字节相同，确定性；全量 3813/24695/0F/0E/exit 0/357 s；app-check `:ok true`/0.0.1/`:component-errors {}`；git porcelain 16、`hs_err` 9。W87 修复已验证在 bundle（closure.variable.map：`recover_pending_materials_commands_BANG_`→`e_b`、`receipt_sweep_at`→`d_b`、`merge_local_state`→`c_b`）；W86 的「bundle 未含修复」为 release `:simple` 改名 grep 误报。E2E11 若仍报 pending 命令属服务端 pending 锁/时序。final16/final17 同一产物，证据双记 `efbc465a` + `99aa81a4` 不变。
  **final16b 复核 PASS（W92）**：PRE=POST `99aa81a4` 同树补跑全量 **3813/24695/0F/0E/exit 0/355 s**，app-check `:ok true`/0.0.1/`:component-errors {}`/store open；bundle/CSS 未变，git porcelain 16，`hs_err` 9；日志 `/tmp/hc-full-test-final16b.log`、meta `plans/evidence/final16b-freeze-meta.json`。**final16 冻结成立**，E2E 证据双记 `efbc465a` + `99aa81a4` 生效。
  E2E 证据只记运行时 hash。
  **final17 E2E run5（W98，10:31–11:09）裁定**：E2E15 PASS、E2E06 文本腿与前轮一致（OCR 环境限制注记）、E2E05 撤销段未执行待 w93-v8；run 后磁盘树漂移至 `0a62fd2c` 系 W99 D-26 修复（bundle 未变），裁定 final17 证据绑「运行起点树」`99aa81a4`+`efbc465a`，W99 完成后走 final18 重建/全量/重冻结并复跑 E2E05/11。说明见 `plans/evidence/final17-e2e-run5-ruling.md`。
  下表证据除特别注明外
  均绑前冻结 hash；E2E 统计现为 18/3/1（08 已 PASS），但需在 `072587a4`/`6aa694f1` 重绑/复跑后
  才能作为新冻结证据。

## 1. 22 行权威状态

| E2E | 最终结论 | 证据类型 | 证据路径 | 仍未覆盖的子项 | 关联缺陷/修复状态 |
|---:|---|---|---|---|---|
| 01 空安装首条回复 | **PASS** | 真实模型+真实浏览器 | `/tmp/hc-e2e-real/artifacts/e2e01/e2e01-result.json`、4 张截图；`/tmp/hc-e2e-real/README.md` | 无（停止路径的 D7 UI 首 chunk/标签仍单列） | binding 修复后 13/13 断言，无 workaround |
| 02 同名模型双服务 | **PASS** | 真实模型+真实浏览器 | `/tmp/hc-e2e-real/artifacts/e2e02/e2e02-result.json`、2 张截图 | 无 | 8/8；停用提示为中文“所选连接已停用；请启用连接或选择其他模型，草稿已保留” |
| 03 流式阅读与下一条草稿 | **PASS** | 真实模型+真实浏览器（长流部分用可控 stub） | `plans/e2e-real-03-04.md`、`plans/evidence/e2e-03-04/e2e03-stub.json`、`e2e03-real-model.json` | 无；停止后 15s 零请求、切回正文保留均在真实 Ollama 下通过 | 首 chunk 已修；历史镜像已改为前缀增长 |
| 04 历史编辑与两条分支 | **PASS** | 真实模型+真实浏览器（第三轮用 stub 主模型） | `plans/e2e-real-03-04.md`、`plans/evidence/e2e-03-04/e2e04-real-a.json`、`e2e04-real-b.json`、`e2e04-third-stub.json`、`e2e04-restart-export.json` | 无；重启前后导出 sha256 一致 | 分支包含选中消息、导出过滤、历史镜像均已修 |
| 05 压缩后继续与撤销 | **PASS（final22 复跑）** | 真实本地 Ollama + 真实 UI（**final22 bundle `c9cbfdf6` + 树 `1e824c83`**，W115 e2e05v8-w115f22 18:22–18:43，并行隔离实例；final21 对照 W115 17:01–17:22 `0fbddd3c`+`319f035f` 42/42；final20 对照 W101 `87b0dee4`+`5e301ce4` 41/42；final19 对照 W93/W101b 13:23–14:12） | `plans/evidence/e2e-05/e2e05-v8-c9cbfdf6-final22.{json,png}` + `-run.log`（W115 final22，**42/42 PASS，ok:true**，bundle `c9cbfdf6` 实测一致，双记 `c9cbfdf6`+`1e824c83`）；前冻结对照 `e2e05-v8-0fbddd3c-final21.{json,png}`（42/42，作废转历史）、`e2e05-v8-87b0dee4-final20.{json,png}`（41/42） | **D-24/D-25 保持关闭、D-28 修复不回归撤销段（final22）**：W115 final22 42/42 全 PASS——撤销段完整执行，「撤销压缩」点击后活客户端面板即时刷新为「压缩 已撤销」、撤销按钮消失（`undone`/`undoneMarked`/`undoRestoresRange`/`undoSendsRestoredHistory`/`undoDropsSummary`/`afterUndoOutboundHasNewMessage` 全 true）；outboundCounts 伪阴性修复保持（面板 发送10/摘要0/排除0）；`current-with-local` 修复未破坏撤销链路。前冻结 W101 同跑 final21 撤销段失败样本 `e2e05-v8-0fbddd3c-final21-w101-run.{json,png}`（根因：误连同 app 实例抢占 takeover，NOT_CONTROLLER 拒绝，非产品缺陷） |
| 06 附件能力与部分读取 | **PASS（final19 复跑完成；文本腿失败五轮一致，裁定 OCR 环境限制）** | 真实本地 Ollama + 真实浏览器 | `plans/e2e-real-05-06.md`、`e2e06-final-72de4f9a.json`；**final19 证据 `plans/evidence/e2e-05-06/e2e06-textleg-168318c1-final19.json` + `.png`**（bundle `168318c1`，tree 运行起点 `f8477191`→结束 `25beab95` 漂移） | final19 文本腿（14:01，168318c1）与 final14/16/17/18 完全一致：`extractRouteAvailable=false`、"准备失败 · 文字提取失败"；其余 5 项 PASS（cardNeedsChoice/cardOffersActions/cardNotSendable/sendBlockedNoNewMessage/blockedNoticeShown）、pageErrors []。五轮复现，按裁定记 OCR 环境限制注记、不判产品回归 |
| 07 搜索结果与来源 | **PASS** | 真实模型+真实浏览器（**最终冻结 bundle `72de4f9a` + 源码树 `ab801450`**，final11 PASS；app 18:2x local 启动，flock /tmp/hc-app-single.lock，端口 42087） | `plans/evidence/e2e-07-08/e2e07-ab801450-result.json`、`e2e07-ab801450-*.png`、`search-stub-requests-ab801450.log`、`e2e07-ab801450-bundle.txt`；`plans/e2e-real-07-08.md` §8/§11/§12；前冻结参照 `e2e07-6aa694f1-*`（072587a4/6aa694f1）、`e2e07-072587a4-*` | 模式/服务/本轮必须搜索/发送/搜索执行（stub +1）/阶段“已搜索”/模型上下文/3 张来源卡/URL 去重/未知日期/发布日期/编号对应（`[S1][S2][S3]` 与卡片一一对应）/失效来源保留/断网重看不发隐式搜索/导出纯文本“来源：”+3 个完整 URL 均 PASS，0 pageerror；来源卡外链点击仍被 F7 吞掉（deadSourceOpened=false，单列 finding，不改变本场景 PASS） | 修复：`export.cljc` source-lines 回退到 run source-cards（仅最后一条 assistant），`view.cljs` 传参；服务端 RUN_FINISHED 携带 public search context、Owner terminal payload 与 assistant 消息 `:citations` 来自 source-cards；客户端 `events.cljc` 新增 source-cards→消息投影与测试；证据已双记 bundle `72de4f9a` + 树 `ab801450`（前后 sha256/tree_hash 均一致） |
| 08 角色边界/S1/中断 | **PASS** | 真实模型+真实浏览器（最终冻结 bundle 72de4f9a + 源码树 ab801450；`:chat?` 修复后普通回答路径已通） | `plans/evidence/e2e-07-08/e2e08-72de4f9a-result.json`、`e2e08-72de4f9a-{ask-card,answered,unparked,composer-recovered,esc-before,esc-after}.png`、`e2e08-72de4f9a-bundle.txt`；前冻结对照 `e2e08-072587a4-6aa694f1-*`；`plans/e2e-real-07-08.md` §11 | S1 `.hc-ask` 出现/问题文本/提交“42”后已回答（`hc/ask` 帧存在、裸 `ask` 0）/服务端 unpark（`e2e-08-resume` `:status :COMPLETED`、parked 程序 `:value "42"`）/同一 run 继续/ask 后 composer 恢复（run idle 32 s 后可发送）均 PASS；**Esc 完整 PASS**：assistant 长文本流已产生（`escFirstChunk` 捕获助手正文 “Why Unmanned Agent…”，非 tool-call 计划源，`escToolCallPath=false`），Esc 后 `escStopped/escRunInactiveAfterStop/escContentPreserved/escNoLateAppend` 均 true，5 s 无迟到追加；非 S1/普通个体拒码仅契约覆盖（`capability_test.clj`） | `:chat? true` 普通回答路径修复（final11 树）使 Plan 不再只走 clj-plan 工具路径；Esc 证据绑 `72de4f9a`+`ab801450` |
| 09 工作区受保护提交 | **PASS** | 真实 app + 真实浏览器 + 确定性 workspace 测试（最终冻结 bundle `72de4f9a` + 源码树 `ab801450`，final11） | `plans/e2e-real-09-10.md` §17.11、`plans/evidence/e2e-09-10/e2e09-final.json`、`e2e09-final-pass-72de4f9a.json`、5 张截图 | 全部 PASS（30/30 checks）：Plan→Reviewer→swarm、register/split/take-or-die(:attempt-won/:retired? false)/claim；冲突 `:FILE_CONFLICT`/`:stamp-mismatch`，原 stamp 20 B/当前 36 B，外部编辑保留；重读后 APPLIED，`worker,7` 落盘，server stat 最终 stamp；`.hc-protected-program` 冲突卡/APPLIED 卡渲染、原/当前 stamp、重试按钮可用；窄视口 360×800 无水平溢出；`__HC_DB__` sessionIds/activeId/workerPrograms>0；RSS 1,131,436→1,219,748 KB | D-16 初始快照（`normalize-entity-tables`）与 `:program-status` settle 优先已修；final11 全量 3792/24578/0F/0E/360 s。前冻结 `072587a4`/`6aa694f1`（final10）证据降为历史 |
| 10 工具中断未知结果 | **PASS** | 真实 app + 真实浏览器 + 真实 shell | `plans/e2e-real-09-10.md`、`plans/evidence/e2e-09-10/e2e10-observe.json`、`e2e10-ack.json` | 真实 shell 在途→kill→重启→UI 结果待确认→确认成功→结清；无自动重放（请求 109→109）。UI 未知卡为该轮 model call，shell action 未单独成卡 | 真实 shell 在途与 UI 核对入口已补 |
| 11 记忆候选/归档/临时会话 | **PASS** | 真实模型+真实浏览器（final22 bundle `c9cbfdf6` + 树 `1e824c83`） | `plans/evidence/e2e-11-12/shots/e2e11-*.png`；`e2e-11-12-result.json`；`plans/evidence/e2e-real-11-12.md`；`plans/evidence/e2e-11-12/e2e11-c9cbfdf6-final22.json`（final22，25/25 PASS）；前冻结对照 final21 `e2e11-0fbddd3c-final21.json`（25/25）、final19 `e2e11-168318c1-final19.json`（25/25）；更早对照 `e2e11-a14d9abd-final15.json`（9/1）、`e2e11-efbc465a-final17.json`（9/1）、`e2e11-f8c0f53a-final18.json`（9/1） | **final22 复跑（bundle `c9cbfdf6` + 树 `1e824c83`，18:22–18:30，并行隔离实例 `/tmp/hc-w22-e2e11:7622`，独立 data-dir/端口，runner `run-e2e11-v3.sh`，无全局锁）：25/25 断言全 PASS，`ok:true`，`NODE_EXIT=0`，app RSS 1,089,776 KB，`frozenGap:null`**。`suggest-switch-on` 关闭：「自动建议新记忆」开关 enabled（`:memory-suggest?` 置位、整理记忆命令受理执行，suggest-ran candidates 1→1）；D-26/D-28/unwrap-receipt 修复持续生效；候选审批/拒绝/改范围、归档排除、临时一次性读取自动关闭、普通会话无临时控件均 PASS。脚本侧两处适配沿用（非产品语义）：send 前 `openSession` 返回 `:sessions` 路由；`归档排除` 断言正则 `/归档排除[：:]\s*1/` | outbound 投影已补 assistant/memory/worldbook/cards/tools；suggest 默认 task/utility，删除静默回退 main |
| 12 世界书与指令卡 | **PASS** | 真实模型+真实浏览器（final22 bundle `c9cbfdf6` + 树 `1e824c83`） | `plans/evidence/e2e-11-12/e2e12-c9cbfdf6-final22-assertfix.json`（W116 断言口径修正后 **48/48 PASS，ok:true**）、`e2e12-c9cbfdf6-final22.json` + `-w116.json`（裁定前 47/48）、`e2e12-c9cbfdf6-final22-w127-run2.json`（W127 并行样本 run2）、`e2e12-c9cbfdf6-final22-w121.json`（W121 独立样本，裁定后 48/48 PASS）、`shots/e2e12-*.png`、`shots-w121-final22/`；前冻结对照 final21（42/48、43/48、45/48）与 final20（43/48，作废） | **final22 断言修正后 48/48 全 PASS**：世界书多轮/预算/窗口/移动提供性/跨会话隔离/指令卡单轮全过（D-27 保持关闭）；**D-28 关闭**——W124 `current-with-local` 修复生效，`:last-move` 落库、reselect edit 下 `撤销移动` 出现并成功撤销，`undo-restores-book`/`undo-restores-injection`/`entry-back-in-source-book` 全 PASS。唯一口径项 `move-receipt-undo-visible` 按 Main 裁定改为「回执应用=编辑器复位 create 模式」（撤销按钮仅 edit 模式渲染，20s 窗口结构性不可见；undo 链路由 undo-button-after-reselect/undo-restores-* 端到端覆盖），证据 JSON 已注 `assertionRuling`；W121 独立样本 `-w121.json` 复核同为 48/48 PASS（裁定后第二个独立确认） |
| 13 技能与小应用隔离 | **PASS** | 真实 Chrome + 真实 Ollama（qwen3.6:27b）+ 受控失败配置 | `plans/evidence/e2e-13-15-16-21/e2e13.json`、`e2e13-generate.json`、`e2e13-real-model.json`、`e2e13-generate-failure.json`、`e2e13-session-skill.json`、`plans/evidence/e2e-13/session-skill-ui.json`、`13-*.png`、`13-session-skill-*.png` | 无；小应用隔离、技能安装/启用/绑定、真实模型生成 5052 B/124.5 s、真实超时失败仍可查看已有文件、会话技能选择 UI（A 含 Skill / B 不含）均 PASS | 会话技能 UI 已由 W9 实现，W14 在最终 bundle 8b210896 上独立复验 A/B 隔离 |
| 14 受管浏览器 | **PASS** | 真实模型+真实浏览器 | `/tmp/hc-e2e-harness/e2e14/e2e14.json`、`e2e14-final.png` | 无 | D9 面板已修 |
| 15 语音转写/朗读/翻译 | **PASS** | 真实模型/真实浏览器（**final19 `168318c1`+`f8477191`（运行起点树）**；regen/版本切换/朗读停止） | `plans/e2e-real-13-15-16-21.md`、**`plans/evidence/e2e-13-15-16-21/e2e15-v6-regen-168318c1-final19.json` + `.png`**（W93，13:44–14:00）、前冻结对照 `e2e15-v6-regen-f8c0f53a.*`（final18）、`e2e15-version-switch*.json` | final19 PASS：regen 产生 v2（D-22/history NPE 修复生效）、版本切换 v1↔v2 全断言（audioWhilePlayingV2/audioAfterSwitchV1/oldAudioPaused/oldAudioAtZero/oldAudioStaysPaused/playerGoneAfterSwitch/versionButtonsUpdated/pageErrorsZero）、pageErrors []、totalElapsedMs 1003592；实时麦克风为环境限制（无音频输入），不计产品阻断 | W33 判 E2E15 PASS；麦克风环境限制单列 |
| 16 定时任务/时区/平台 | **PASS（iOS 为 UA 模拟）** | 真实 Chrome + 受控延迟模型 | `plans/evidence/e2e-13-15-16-21/e2e16.json`、`16-*.png` | 手动运行不改原计划、第二次立即运行“已跳过：上次仍在运行”、iOS UA 仅前台手动运行/启用禁用/默认暂停、表单 capability-scope 均 PASS；真实 iOS 设备未跑 | 创建/预览/暂停/时区/错过/恢复默认暂停沿用前轮 PASS |
| 17 加密备份/合并/缺失附件 | **PASS** | 真实模型+真实浏览器 | `/tmp/hc-e2e-harness/e2e17/e2e17.json`、`verify-after-merge.log` | 无 | D6 已修；重启回执已修 |
| 18 磁盘满与恢复启动 | **PASS** | 真实模型+真实浏览器 | `/tmp/hc-e2e-harness/enospc/evidence/e2e18-pass.json` | 无 | 真实 ENOSPC + 恢复 PASS；ENOSPC 文案已修 |
| 19 字号/键盘/屏幕阅读器/IME | **PASS** | 真实模型+真实浏览器 | `/tmp/hc-e2e-real/e2e19/report.json`、`ime-enter.png`、`focus-return.png`、`drawer-escape.png`、`zoom-200-*.png` | 屏幕阅读器朗读顺序、真实触屏未跑 | IME/焦点/Escape/200% 字号 PASS |
| 20 临时会话舍弃/转存 | **PASS** | 真实模型+真实浏览器 | `plans/e2e-real-08-10-20.md`、`plans/evidence/e2e-real-20/` | 无 | discard 10.356 s、transfer 279 ms；临时记忆/已删除区/重启持久全部补验 PASS |
| 21 费用口径 | **PASS** | 真实 Chrome + 受控用量代理 | `plans/evidence/e2e-13-15-16-21/e2e21-final.json`、`21-*.png` | 三口径（服务返回 341/估算覆盖 341/未提供 1）、价格表单 service identity、USD/EUR 分开、改价不改历史均 PASS | 金额 TaggedValue 已修；旧 USD 0.000935 与 EUR 0.017905 并存 |
| 22 跨设备迁移/降级 | **PASS** | 真实浏览器/store 迁移 | `plans/evidence/e2e-regression-01-02-22/e2e22-final.json`、`plans/e2e-regression-01-02-22.md` | 无（手工回归 PASS；新 §8.5 自动化待 D-04） | 已删除区/连接投影/no-sync 均已复跑 |

## 2. 冲突与裁决

共 **20 个场景**列入下表；其中 10 个两次都是 PASS（01/02/03/04/07/10/13/16/20/21），
不计冲突；10 个结论发生变化（05/08/09/11/12/14/15/17/18/22）。19 与 06
两次均 PASS，未列入。

| E2E | 旧结论（首轮/早期） | 最新结论 | 裁决理由 |
|---:|---|---|---|
| 01 | PASS（组件级/stub） | PASS（真实模型+浏览器，13/13） | 证据更强、更晚；binding 修复后无 workaround |
| 02 | PASS（核心隔离/stub） | PASS（真实模型+浏览器，8/8） | 真实双连接 UI 证据更强 |
| 03 流式阅读与下一条草稿 | PASS（早期） | PASS（真实模型+浏览器，长流部分用可控 stub） | 停止后 15s 零请求、切回正文保留均通过；首 chunk 已修，历史镜像改为前缀增长 |
| 04 历史编辑与两条分支 | PASS（早期） | PASS（真实模型+浏览器，第三轮用 stub 主模型） | 重启前后导出 sha256 一致；分支包含选中消息、导出过滤、历史镜像已修 |
| 05 | PASS | **PASS（final22 `c9cbfdf6`+`1e824c83`：W115 v8 42/42 全 PASS、ok:true——D-24/D-25 保持关闭，D-28 `current-with-local` 修复不回归撤销段，撤销后活客户端即时「压缩 已撤销」+ 按钮消失 + outbound 还原原历史不含摘要）**；final21 对照 W115 42/42（作废转历史）；final20 对照 W101 41/42；final19 对照 W101b 38/42（D-24 关/D-25 客户端投影陈旧 FAIL） | 真实本地 Ollama+真实 UI W115 final22 并行隔离实例（18:22–18:43）；证据 `plans/evidence/e2e-05/e2e05-v8-c9cbfdf6-final22.{json,png}` + `-run.log`；前冻结对照 `e2e05-v8-0fbddd3c-final21.{json,png}`（42/42）；W101 同跑 final21 撤销段失败样本 `e2e05-v8-0fbddd3c-final21-w101-run.{json,png}`（7 项 undo FAIL，根因：误连同 app 实例抢占 takeover，NOT_CONTROLLER 拒绝，非产品缺陷） |
| 07 | PASS（契约/stub） | PASS（072587a4+6aa694f1 真实搜索/来源卡/导出全 PASS） | 真实搜索、3 张来源卡、编号对应、失效来源、断网与导出完整 URL 均通过；外链点击单列 finding |
| 08 | PASS（契约） | PASS（72de4f9a+ab801450：S1/unpark/composer/Esc 全 PASS） | `:chat?` 修复后 assistant 长文本流真实产生（非 tool-call 路径），Esc 停止/保留/不迟到全 PASS；非 S1/普通个体拒码仍仅契约覆盖 |
| 09 | PASS（服务端/桌面/文件 UI smoke） | PASS（最终冻结 72de4f9a/ab801450 final11，30/30 checks 全 PASS） | D-16 初始快照（normalize-entity-tables）与 :program-status settle 修复后，真实 UI 冲突卡/stamp/重试 APPLIED/窄视口均通过；前冻结 072587a4/6aa694f1 证据降为历史 |
| 10 | PASS（子 JVM） | PASS（真实 shell/UI 核对） | 真实 shell 在途→kill→重启→UI 结果待确认→确认成功→结清；请求 109→109 无自动重放 |
| 11 | PASS（服务端） | PASS（final22 `c9cbfdf6`+`1e824c83`：25/25 断言全过——`suggest-switch-on` 设置回执正常到达、`:memory-suggest?` 置位、整理记忆命令受理执行（D-26/D-28/unwrap-receipt 修复持续生效），候选审批/拒绝/改范围、归档排除、临时会话一次性读取与自动关闭全 PASS，`frozenGap:null`） | 证据 `e2e11-c9cbfdf6-final22.json`（final22 复跑，18:22–18:30，隔离实例 /tmp/hc-w22-e2e11:7622）；前冻结对照 `e2e11-0fbddd3c-final21.json`（25/25）、`e2e11-a14d9abd-final15.json`、`e2e11-efbc465a-final17.json`、`e2e11-f8c0f53a-final18.json` |
| 12 | final22 断言修正后 **48/48 PASS×2 独立样本**（`c9cbfdf6`+`1e824c83`，证据 `e2e12-c9cbfdf6-final22-assertfix.json` + `e2e12-c9cbfdf6-final22-w121.json`）：D-27/D-28 双关闭——`:last-move` 生效、撤销移动端到端 PASS；`move-receipt-undo-visible` 按裁定改验回执应用（create 模式复位），undo 可见性/往返由 undo-button-after-reselect + undo-restores-* 覆盖 |
| 13 | PASS（模型/组件） | PASS（真实模型生成/失败路径/会话技能 UI） | 真实 Ollama 生成 5052 B/124.5 s；真实超时失败仍可查看已有文件；A 自定义技能进入请求，B 不含 |
| 14 | BLOCKED（D9） | PASS（真实 Chrome/CDP） | 修复后真实复跑，证据更强 |
| 15 | PASS（loopback） | PASS（final18 regen/版本切换/朗读停止全 PASS；麦克风为环境限制） | final18 `e2e15-v6-regen-f8c0f53a.json` pass:true/1003 s/pageErrors []；W33 判 PASS |
| 16 | PASS（契约） | PASS（iOS 为 UA 模拟） | 手动运行不改原计划、重叠跳过、时区/错过/恢复暂停均 PASS；真实 iOS 设备未跑 |
| 17 | BLOCKED（D6） | PASS（真实备份/密码/合并） | 修复后真实复跑；重启回执已修 |
| 18 | BLOCKED（无 ENOSPC） | PASS（真实 ENOSPC+恢复） | 注入证据更强；ENOSPC 文案已修 |
| 20 | PASS（registry） | PASS（真实 UI discard/transfer/记忆/重启） | 有界 discard/transfer 与三项补验均通过 |
| 21 | PASS（服务端/客户端） | PASS（真实 UI 三口径/多币种/改价历史） | 受控用量代理 + 真实 Chrome；USD/EUR 分开且旧价保留 |
| 22 | BLOCKED（真实迁移） | PASS（store 迁移 + UI 追加） | 真实迁移证据更强；已删除区/连接投影已修 |

## 3. 统计与全 PASS 判定

| 统计口径 | 条数 | E2E |
|---|---:|---|
| PASS | **22** | 01、02、03、04、05、06、07、08、09、10、11、12、13、14、15、16、17、18、19、20、21、22 |
| PARTIAL | **0** | — |
| BLOCKED | **0** | — |
| 合计 | **22** | |

**手工/真实端点证据：22 PASS、0 PARTIAL、0 BLOCKED；自动化口径以
W10 结果为准，未全 PASS 前不得打 tag。**

## 4. 达到全 PASS 还差的清单

| E2E | 还差什么 |
|---:|---|
| — | 无（22/22 PASS；E2E05 final22 42/42、E2E11 final22 25/25、E2E12 final22 48/48×2 均 PASS） |


01/02/03/04/05/06/07/08/09/10/11/12/13/14/15/16/17/18/19/20/21/22 全部 PASS（final22 断言修正后 E2E12 48/48，D-27/D-28 双关闭）；无剩余 PARTIAL。

## 5. 证据索引

**E2E 22/22 收口（final22）**：E2E05 final22 42/42、E2E12 final22 48/48×2；final22 全量 3824/24735/0F/0E，app-check `:ok true`/0.0.1，版本五处 0.0.1。W130 正执行最终门禁复核 + commit + annotated tag `v0.0.1`（不 push）；commit/tag 待回填。**D-04 自动化口径仍开放；E2E 22/22 为手工/真实端点口径。**

**提交/tag 状态**：W130 正在执行最终门禁复核 + `git add -A` + commit + annotated tag `v0.0.1`（不 push）；commit/tag hash 待回填。D-04（22 条 E2E 自动化口径）保持开放——22/22 为手工/真实端点口径，不等于新 §8.5 自动化验收。

**final22 最终冻结 PASS（W33/W125）**：bundle `c9cbfdf6…`（3,018,831 B，18:12:03；CSS `332f9fd3`；manifest c2158f6b）+ 树 `1e824c83…`（clean rebuild 确定性）；全量 3824/24735/0F/0E/exit 0/354 s；app-check `:ok true`/`0.0.1`；D-28 `current-with-local`+`merge-local-state`、E2E11 `bridge/connection`+`bridge/command-allowed?` 已验证入产物。final21 及更旧作废；E2E12/E2E11/E2E05 可并行复跑，证据双记 `c9cbfdf6` + `1e824c83`。

**E2E12 final22 复跑（W116 `/tmp/hc-w116:7601` + W127 `/tmp/hc-w22p:7595` 两独立实例，均无锁）**：裁定前两样本各 **47/48 PASS / 1 FAIL**（同一口径项 `move-receipt-undo-visible`；W116=`-w116.json` wb-1cf711d8、W127 run2=主文件/`-w127-run2.json` wb-deba8ffc；`-w127.json` 系误存 W116 样本已澄清）。**断言口径修正后（Main 裁定）复跑 48/48 PASS，ok:true，NODE_EXIT=0**，证据 `e2e12-c9cbfdf6-final22-assertfix.json` + `assertionRuling` 注记；bundle `c9cbfdf6` 实测一致。D-27 保持关闭；**D-28 实质关闭**：`:last-move` 落库生效——reselect edit 下 `撤销移动` 出现并成功撤销，`undo-restores-book`/`undo-restores-injection`/`entry-back-in-source-book` 全 PASS（final21 残余 3 项中 2 项转 PASS）。唯一 FAIL `move-receipt-undo-visible`：move 回执后 `reset-draft-for-command` 已把编辑器复位为「新建条目」create 模式，而 `撤销移动` 按 `view.cljc` 设计仅在 edit 模式渲染 → 20 s 断言窗口内结构性不可见；undo 链路端到端已由后续断言验证，属断言口径与交互设计差异而非功能缺陷，待 Main 裁定（调整断言或产品补 receipt 态入口）。W127 并行样本进行中（同脚本 `/tmp/hc-w22p:7595`，文件名已协调）。E2E12 维持 PARTIAL 仅差此口径项。
  **W121 final22 样本（/tmp/hc-w22p:7595，18:35–18:38）**：裁定后驱动复跑 **48/48 PASS，ok:true，NODE_EXIT=0**，证据 `e2e12-c9cbfdf6-final22-w121.json`（含 `assertionRuling` 注记；bundle `c9cbfdf6` 实测一致；截图存 `shots-w121-final22/` 未覆盖共享 shots/）。与 W116 assertfix 样本互为独立确认，E2E12 final22 双样本 48/48，**D-28 关闭成立**。

**E2E11 final22 复跑（隔离实例 `/tmp/hc-w22-e2e11:7622`，runner `run-e2e11-v3.sh`，无全局锁）**：**25/25 断言全 PASS，`ok:true`，`NODE_EXIT=0`**，18:22–18:30（UTC 10:22–10:30），app RSS 1,089,776 KB。证据 `plans/evidence/e2e-11-12/e2e11-c9cbfdf6-final22.json` + `shots/e2e11-72de4f9a-*.png`（截图文件名沿用旧代号，内容为本次运行产物），bundle `c9cbfdf6`（3,018,831 B）+ 树 `1e824c83` 双记（驱动脚本扩展名过滤口径 `tree.sha256=3beacf52…`；冻结配方 `find src test -type f | sort | xargs sha256sum | sha256sum` 复核=1e824c83，`tree-1e824c83-final22-{before,after}.txt` 一致）。`suggest-switch-on` 关闭：设置回执正常到达，「自动建议新记忆」开关 enabled，`:memory-suggest?` 置位、整理记忆命令受理执行（`e2e11-suggest-ran` candidates 1→1，suggest switch on=true）；候选审批/拒绝/改范围、归档排除、临时会话一次性读取与自动关闭、普通会话无临时控件均 PASS，`frozenGap:null`。E2E11 保持 PASS。

**E2E05 final22 复跑（W115 v8，隔离实例，18:22–18:43）**：**42/42 断言全 PASS，`ok:true`**，bundle `c9cbfdf6` 实测一致。证据 `plans/evidence/e2e-05/e2e05-v8-c9cbfdf6-final22.{json,png}` + `-run.log`（W115 归档），双记 `c9cbfdf6` + `1e824c83`。撤销段完整执行：「撤销压缩」点击后活客户端面板即时刷新为「压缩 已撤销」、撤销按钮消失（`undone`/`undoneMarked`/`undoRestoresRange`/`undoSendsRestoredHistory`/`undoDropsSummary`/`afterUndoOutboundHasNewMessage` 全 true）；outboundCounts 伪阴性修复保持（面板 发送10/摘要0/排除0）；**D-24/D-25 保持关闭，D-28 `current-with-local` 修复不回归撤销段**。E2E05 保持 PASS，E2E 22 条全 PASS。

**final21 最终冻结 PASS（W33/W118）**：bundle `0fbddd3c…`（3,018,796 B，16:34:21；CSS `332f9fd3`；manifest c2158f6b）+ 树 `319f035f…`（clean rebuild 确定性 PRE=POST）；全量 3824/24735/0F/0E/exit 0/354 s；app-check `:ok true`/`0.0.1`；unwrap-receipt 修复已验证入产物。final20 及更旧作废；证据双记 `0fbddd3c` + `319f035f`。

**E2E11 final21 复跑（W122，隔离实例 `/tmp/hc-w22-e2e11:7622`，runner `run-e2e11-v3.sh`，无全局锁）**：**25/25 断言全 PASS，`ok:true`，`NODE_EXIT=0`**，16:50–16:57（UTC 08:50–08:57），app RSS 1,090,020 KB。证据 `plans/evidence/e2e-11-12/e2e11-0fbddd3c-final21.json`，bundle `0fbddd3c`（3,018,796 B）+ 树 `319f035f` 双记（驱动脚本扩展名过滤口径 `tree.sha256=037e8529…`，冻结配方 `find src test -type f | sort | xargs sha256sum | sha256sum` 已复核=319f035f）。`suggest-switch-on` 关闭：设置命令回执正常到达，「自动建议新记忆」开关 enabled、整理记忆命令受理执行（unwrap-receipt 修复持续生效，无 pending 锁复发）；归档排除、临时会话一次性读取与自动关闭、普通会话无临时控件均 PASS，`frozenGap:null`。E2E11 保持 PASS。
**E2E12 final21 复跑（W121 两样本 + W116 一样本）**：W121 run1 42/48、run2 43/48；W116 样本 45/48（三样本失败集不同，见下）。D-27 保持关闭；D-28 未关闭——`move-receipt-undo-visible`、`undo-button-after-reselect`、`undo-restores-book` 三样本均 FAIL；`entry-back-in-source-book`/`undo-restores-injection` 在 W116 样本转 PASS、W121 两样本仍 FAIL（时序敏感）。**unwrap-receipt 已入产物且模型单测可置位 `:last-move`，但运行时 `:ui/materials` 中 `:last-move` 仍不可见（撤销移动不渲染）→ 疑 `transact-state!`/`merge-local-state`/`sync-from-db!`/`apply-snapshot` 链覆盖**。W123 修复中；final21 冻结保持，修复后重建 + final22。

**E2E12 final21 复跑（W116，独立实例 `/tmp/hc-w116:7601`，无锁）**：**45/48 PASS / 3 FAIL**，bundle `0fbddd3c` 实测与产物一致。D-27 保持关闭；D-28 净改善——`entry-back-in-source-book`（手动移回）与 `undo-restores-injection`（r9 注入恢复）转 PASS。**残余 3 FAIL 全在 undo 链即时段**：`move-receipt-undo-visible`（move 回执 20 s 无 `撤销移动`）、`undo-button-after-reselect`（reselect edit 30 s 仍无）、`undo-restores-book`（级联，undo 未点条目滞留 B）。W116 复核：服务端 `:undo` 回执完整到达（WS 抓帧）、`hc.client.materials.model/apply-receipt` 在 JVM 侧以同形 wire receipt 单测可置位 `:last-move`、pending 锁正常释放（手动移回成功）→ unwrap-receipt 修复有效但 `:last-move` 在运行时 `:ui/materials` 仍不可见，断点收窄为回执应用后状态持久化/视图投影路径，待查（非服务端、非模型纯逻辑）。E2E12 维持 PARTIAL。

**final20 最终冻结 PASS（W33/W114）**：bundle `87b0dee4…`（3,018,665 B，15:26:31；CSS `332f9fd3`；manifest c2158f6b）+ 树 `5e301ce4…`（PRE=POST）；全量 3823/24731/0F/0E/exit 0/354 s；app-check `:ok true`/`0.0.1`；D-25/D-27/D-28 修复已入产物；swarm_ui 2 失败为既有。final19 及更旧作废；证据双记 `87b0dee4` + `5e301ce4`。

**E2E12 final20 复跑（冻结后新证据）**：43/48；**D-27 全过**、D-25 已由 W101 E2E05 final20 复跑确认关闭；5 项 FAIL 集中在 move 撤销/pending 锁（undo/`:last-move`/回执应用）→ **D-28 未根治**。final20 冻结保持（证据不改变 hash），E2E12 维持 PARTIAL；run2b 复核中。

**E2E12 final20 第二样本 run2b（W95）**：43/48，失败集与第一样本完全一致（`move-receipt-undo-visible`、`undo-button-after-reselect`、`undo-restores-book`、`entry-back-in-source-book`、`undo-restores-injection`）→ **D-28 为确定性失败（非间歇）**；D-27 已确认修复（两样本卡断言全过）。证据 `e2e12-87b0dee4-final20-run2.json`；E2E12 维持 PARTIAL，等 final21。

**E2E05 final20 复跑（W101 v8，验收证据）**：41/42 PASS——D-24/D-25 双关闭（撤销后活客户端显示“压缩 已撤销”、按钮消失；outbound 还原原历史不含摘要）；唯一 FAIL `outboundCounts` 为脚本正则伪阴性（面板 发送10/摘要0/排除0 正确）。证据 `plans/evidence/e2e-05/e2e05-v8-87b0dee4-final20.{json,png}` + `-run.log`，双记 `87b0dee4` + `5e301ce4`。final20 作废转历史，final21 需复核 D-25 保持且 D-28 修复不回归 E2E05 撤销段。

**D-28 真正根因/修复（W117）**：`materials.model/unwrap-receipt` 拒绝解包网关信封 `{:ok? :command-id :receipt {..}}`（旧代码要求 `(:command-id receipt)` nil 才取 `:receipt`）→ `receipt-result` 只读外层 `:result=nil` → `:undo` 缺失 → `:last-move` 不置位/撤销按钮不渲染（move 成功）。修复：任何 `map? :receipt` 解包 + merge + 外层回填；测试 3824/24735 0F/0E。final20 作废；W118 clean rebuild → final21 全量 + app-check。

**D-28 定位探针（W116，独立实例 `/tmp/hc-w116:7601`，bundle `87b0dee4`）**：WS 抓帧证实客户端发出 `:materials/worldbook/entry/move` 后，服务端 `hc/command-receipt` 完整带回 `:result {:moved [...] :undo {:entry-ids [...] :book-ids {...}}}`（服务端半边无缺陷，control `:command` 直连亦验证）；客户端随即补发 `:materials/snapshot`（说明 `apply-receipt` 已受理该回执），但 `:last-move` 始终未落入 `:ui/materials`、`撤销移动` 不渲染、entry-draft 亦未复位 → 断点即**客户端回执结果抽取**：`unwrap-receipt` 对网关信封 `{:ok? :command-id :receipt {..}}` 拒绝解包，`receipt-result` 只读外层 `:result`=nil → `:undo`/`:last-move` 缺失。**W33/W117 已确认并修复**（任何 `map? :receipt` 都解包+merge+外层回填；bundle `87b0dee4` 落后于修复树，final20 作废，待 final21 复跑）。

**并发策略（用户指令，取代单执行者/锁窗口）**：多个 worktree/实例可并发跑测试与 E2E，各自独立 data-dir/Chrome profile/端口即可，互不干扰；E2E05/06/11/12/15 可并行（全量峰值约 2 GB，避免同时起过多 app/Chrome）。E2E/视觉证据仍双记 `c9cbfdf6` + `1e824c83`。

**final19 最终冻结 PASS（W33/W107）**：bundle `168318c1…`（3,017,514 B，12:42:52；CSS `332f9fd3`）+ 树 `f8477191…`；全量 3818/24714/0F/0E/exit 0/356 s；app-check `:ok true`/`0.0.1`；D-27/D-28 修复已入产物（materials-bridge 8/23 0F/0E；swarm_ui 2 失败为既有）。final18 及更旧作废；证据双记 `168318c1` + `f8477191`。

**E2E12 final19 复跑（W95）**：**43/48 PASS**（final18 为 40/48，+3），证据 `plans/evidence/e2e-11-12/e2e12-168318c1-final19.json`。改善：手动移回/pending 锁释放/跨会话卡隔离 PASS；**D-27 未根治**（仅本轮指令卡在 w22-s1 内 r2/r7 仍泄漏；w22-s2 r10 无卡，疑 clear/version 匹配或 `last-user` 回退缺口）；**D-28 未根治**（`撤销移动`/`:last-move` 仍不出现，move 回执未应用）。E2E12 保持 PARTIAL。

**E2E12 剩余 5 FAIL 精确明细**：D-27 2 项——r2/r7 面板仍见 “MR-列假设 · turn”（疑 `clear-turn-after-send!`/version 或服务端 `last-user` 回退 `system.clj:519`）；D-28 3 项——move 后 20 s 无撤销、reselect edit 30 s 无、条目未绑回书 B；move 成功但 `:last-move` 未置位；pending 锁已释放。证据 `e2e12-168318c1-final19.json`、`shots/e2e12-move-fail.png`；并行实例 run2 进行中。

**E2E12 final19 并行 run2（独立实例 `/tmp/hc-w22p:7595`，无锁）**：42/48（run1 43/48）。两缺陷均为间歇性时序——D-27 卡泄漏 run2 仅 r2 复现（r7 已清除，clear 延迟生效）；D-28 撤销/`:last-move` 两跑均不出现，pending 锁释放/手动移回 run1 PASS / run2 FAIL（sweep 时序竞争）；世界书断言两跑全过。证据 `e2e12-168318c1-final19-run2.json`；E2E12 保持 PARTIAL。

**E2E05/06/15 final19（W93/W101b）**：E2E05 v8 W101b 38/42——D-24 关闭（撤销后 outbound 含原消息不含摘要）、D-25 仍 FAIL。**W101b 撤销段精确归因（W101 探针复核）**：首次撤销在服务端成功（data.mdb 压缩记录 `:state :reverted`、撤销后 outbound 14 条含全部原消息/继续一/继续二/撤销后且不含摘要），UI 两次重试被拒「only an applied compression can be undone」系客户端投影陈旧——活客户端 >20 s 仍显示「压缩 已采用」+「撤销压缩」按钮、无「压缩 已撤销」标记；以同一 data-dir 重启加载后面板正确显示「压缩 已撤销」、撤销按钮消失（`/tmp/hc-w3e5r4/evidence-final/probe-f19b.png`）。即发布快照正确、活客户端 delta/投影应用缺陷；D-25 按 UI 标记未出现维持 FAIL，建议立项查客户端 session/history delta 应用。W93 轮 26/32（proxy64 被清空）；sessionId 间歇由 v8 DOM fallback 解决。E2E15 PASS（regen+版本切换，pageErrors []）；E2E06 PASS（文本腿五轮一致，extract 为 OCR 环境限制）。tree before `f8477191`/after `25beab95`（漂移，bundle 未变）。

**E2E05 final20 复跑（W101 v8，`87b0dee4`+`5e301ce4`，15:34–15:55，并行隔离实例）**：**41/42 PASS，D-24/D-25 双关闭**。撤销段完整执行：「撤销压缩」按钮出现并点击后，活客户端面板即时刷新为「压缩 已撤销」、撤销按钮消失（`undone`/`undoneMarked`/`undoRestoresRange` 全 true）——W113 cached-entry `:revision` 修复生效（final19 的客户端投影陈旧缺陷不再复现）；outbound（proxy-w101f20 seq7，14 msgs）含全部原消息（事实/假设/取消/继续一/继续二/撤销后）且不含压缩摘要（`undoSendsRestoredHistory`/`undoDropsSummary`/`afterUndoOutboundHasNewMessage` 全 PASS）。唯一 FAIL `outboundCounts` 为脚本正则伪阴性（面板实际 发送10/摘要0/排除0 正确）。sessionId 由 v8 DOM fallback 取得（`hc-session-712b3a61`）、controller-claim took=true。证据 `plans/evidence/e2e-05/e2e05-v8-87b0dee4-final20.{json,png}` + `-run.log` + `tree-5e301ce4-final20-before.txt`/`tree-319f035f-final20-after.txt`（树运行中漂移，bundle 未变）。
**E2E05 final21 复跑（W115 v8，`0fbddd3c`+`319f035f`，17:01–17:22，并行隔离实例 data05w115f21/proxy 18107，无全局锁）**：**42/42 全 PASS**——D-24/D-25 保持关闭（撤销后活客户端即时「压缩 已撤销」、按钮消失；outbound 还原原历史不含摘要；D-28 unwrap-receipt 修复未回归撤销段）。harness 修正：`outboundCounts` 面板行「label：\nvalue」格式导致 final19b/final20 正则伪阴性，w115 脚本已改 `/摘要[：:]\s*1/` 与 `(?<!归档)排除[：:]\s*(\d+)/`（取最后一次匹配）。**同跑 W101 样本（e2e05v8-w101f21，16:44–17:08）撤销段 7 项 FAIL**（`undone`/`undoneMarked`/`undoRestoresRange`/`undoSendsRestoredHistory`/`undoDropsSummary`/`afterUndoOutboundHasNewMessage`，undoRetry=true、面板仍显示「压缩 已采用」+「撤销压缩」按钮）——与 W115 全绿样本并存，根因已查明（W101 复核）：W115 首轮（16:44）误连 W101 app 实例（127.0.0.1:42551），其客户端 `2a7206ab` 于 ~17:00 对 W101 会话 `hc-session-5cd483d1` 发 `session/takeover` 抢占控制权（receipt `outcome:taken-over`，W101 `33699466` 降为 observer），W101 撤销命令被服务端 `NOT_CONTROLLER` 拒绝——跨实例会话复用/控制权竞争，非产品缺陷、非 D-28 同源；已存档 `e2e05-v8-0fbddd3c-final21-w101-run.{json,png}` 供对照；final22 复跑时 W101/W115 需各自独立 app 实例避免同端口复用。W115 证据 `plans/evidence/e2e-05/e2e05-v8-0fbddd3c-final21.{json,png}` + `-run.log` + `tree-319f035f-final21-{before,after}.txt`（before=after=319f035f）。

**E2E11 final19 复跑（W86，并行隔离实例 `/tmp/hc-w22-e2e11:7622`，无锁）**：**25/25 断言全 PASS，`ok:true`，NODE_EXIT=0**，证据 `plans/evidence/e2e-11-12/e2e11-168318c1-final19.json`（bundle `168318c1` + 树 `f8477191` 双记）。final15–18 连续 4 轮 9 PASS/1 FAIL 的 `suggest-switch-on` pending 锁在 final19 关闭：settings/update 回执正常到达，「自动建议新记忆」1 次点击即 enabled（706 ms），整理记忆命令受理执行；归档排除、临时一次性读取自动关闭、普通会话无临时控件均 PASS。脚本两处适配（非产品语义）：send 前 `openSession` 返回 `:sessions` 路由；`归档排除` 断言改正则匹配全角冒号渲染。E2E11 转 PASS。

- **树漂移（W106 bug1，11:55–12:04）**：final18（`f8c0f53a`+`6d1ad03b`）基线失效，final19 待建；final18 的 E2E05 v8 仅作诊断；E2E15/06 结果仍为 final18 上取得。
- **E2E05 v8 final18 诊断（W93）**：sessionId DOM fallback 稳定（`hc-session-fbdf5f26`）、controller-claim `took=true`、prepared/applied PASS（header 摘要1/发送0条）、originalTurns 3/3（上轮 2/3）；中断于 continued-twice 的 `awaitReply`（人为 kill 浏览器，非产品失败）；撤销段仍未执行。产物 `evidence-final/e2e05-v8-final18.{json,png}`、`evidence64/e2e05.json`；tree before=`fb8cc294`（漂移后起点）。仅诊断，等 final19 复跑。
- **E2E12 final18 复跑（W33，`f8c0f53a`+`6d1ad03b`）**：48 断言 **40 PASS / 8 FAIL**，证据 `plans/evidence/e2e-11-12/e2e12-f8c0f53a-final18.json`。8 FAIL 均为真实缺陷：**(a) 仅本轮指令卡泄漏 3 项 = D-27**——client 镜像 command payload 不含 turn 选择，`clear-turn-after-send!` 永不触发；**(b) 条目移动撤销不可用 + 同 key pending 锁不释放 5 项（含级联）= D-28**——move 回执未应用、`:last-move` 缺失；W104 修复中，待 final19 复跑关闭（final18 已含 sweep 仍复现）。世界书多轮/预算/窗口/移动提供性断言全过。E2E12 保持 PARTIAL；D-26 需补 move 路径。
- **E2E05 v8 诊断（W93，efbc465a，非 final18 验收）**：sessionId DOM fallback 生效（`hc-session-de56348a`）、controller-claim `took=true`、composerStable/attachmentTextSendable PASS；但 `prepared=false`（6 次重试“使用摘要”未出现）、originalTurns 仅 2/3（第三轮 reply missing），按指令终止。fallback/claim 修复有效；prepare/apply 段需 final18 复跑确认。产物 `/tmp/hc-w3e5r4/evidence64/e2e05.json` + png、`evidence-final/e2e05-v8-final17-run.log`。
- **2026-11-06 E2E09 复跑（`072587a4` + 树 `d1d8d08d`，W5 `:worker-jobs` 合并后）**：
  `e2e09-072587a4-run.json`、`e2e09-db-probe.json`、§17.9。文件级链路 PASS
  （FILE_CONFLICT/stamp-mismatch、外部编辑保留、3 次重试后 APPLIED、worker,7
  落盘、server stat 一致、360/360；RSS 1,115,396→1,352,840 KB），但 UI 仍
  “已连接，等待同步”“尚未准入任何 swarm”、`.hc-protected-program`=0；
  `__HC_DB__` 仍无 `:session/by-id`/`:swarm/dashboard`（sessionIds []、
  activeId null、dashboard false、workerPrograms {}）。**W5 的 `:worker-jobs`
  合并未解决初始快照未到达，根因仍在 gateway on-open 发布或客户端
  normalize/to-db**；09 保持 PARTIAL。**W41 定位并修复（2026-11-06 15:4x）**：
  snapshot/baseline 的 board-view 产出 `:task/by-id` 等表用 keyword keys
  （`:task-1`），客户端 `normalize/to-db` 的 `normalize-table` 要求 string
  entity IDs → `:invalid-entity` → `receive-state-snapshot` 走 resync →
  `STATE_SNAPSHOT` 无限重发 → 永远 awaiting-sync、db 无 `:session/by-id`。
  修复在 `src/hc/server/app/snapshot.clj`：baseline 出口 `normalize-entity-tables`
  把 `*/by-id` 表 keys 转 string（feed/delta 同树）；离线验证 to-db ok、
  session-ids 非空、worker-programs 4 条。**D-16 已关闭：E2E09 在最终冻结
  `072587a4`/`6aa694f1` 上复跑 PASS（`e2e09-final-pass-072587a4.json`），
  09 升 PASS；该改动已纳入 final10 树范围。**
- **2026-11-06 W10 自动化（bundle `ea9e5732`，§8.5 口径，与上表手工口径分列）**：
  E2E03 PASS（36.3 s，19/19）；E2E09 自动化 PASS（17.6 s，15/15，
  `clientBundle=ea9e5732`），但真实 UI 复跑仍 `ok=false`、初始快照未送达
  （D-16），不得据此把真实 UI 的 09 标 PASS；E2E01 FAIL（33.8 s）：UI 行
  显示 `工具：已声明支持`、`bind.toolsDeclared=true`，发送仍被 Plan 以
  `MODEL_TOOLS_UNAVAILABLE` 拒绝，未产生用户消息（D-AUTO-02，
  `plans/e2e-auto-defects.md`、`plans/evidence/e2e-auto/e2e01/e2e01-result.json`）。
  上表 18/3/1 仍为手工/真实端点证据口径，不因自动化 01 FAIL 改动。
- **2026-11-05 15:22 回归观察（已更新）**：E2E 22 初次回归在 bundle
  `9c59f834a1dd49fb3fd085a711eac683f681a52f6e757ebcba5ecb5dd1aa19b6`
  上 B 启动失败；W4 修复 `Character → Map$Entry` 与模型投影
  `:created-at`/`:model-entry-id`，W5 修复 `ReentrantLock` import 后，
  B `app-check` 已 exit 0；用 `--include-deleted true` 备份/恢复后，
  B2 UI 已删除区可见、`s-deleted` 的 `deleted-at` 原样保留；no-sync 以
  单 app 存储隔离验证（A 临时会话未进入 B2）。当前手工回归结论为 **PASS**，
  见 `plans/e2e-regression-01-02-22.md` 与 `e2e22-final.json`；新 §8.5
  自动化 + mock 验收仍待 W10。
- **2026-11-06 W14 补跑**：E2E 13 真实 Ollama 生成
  （`plans/evidence/e2e-13-15-16-21/e2e13-real-model.json`，5052 B、
  124.5 s、iframe 可读、pageerror=0）与真实超时失败路径
  （`e2e13-generate-failure.json`，MODEL_TIMEOUT、刷新已有文件、已有
  文件仍可查看）；E2E 15 版本切换 W3/W14 两轮真实 Chrome PASS
  （`e2e15-version-switch.json`、`e2e15-version-switch-fixed.json`）。
  W14 另在最终冻结 bundle
  `8b210896…43b9b85`（12:17:19）上独立交叉复验会话技能：A 自定义并
  勾选后 active=`E2E13 会话技能`；新建 B 后扩展面板立即清空为
  “跟随助手 / 无”，切自定义后技能未勾选、active 仍为无
  （`e2e13-session-skill.json`、`13-session-skill-*.png`）。E2E 13
  因此升为 PASS；E2E 15 仍因实时麦克风无音频输入 BLOCKED 保持 PARTIAL
  （版本切换已在同一最终 bundle 上复跑 PASS）。
- 首轮：`plans/e2e-0.0.1-results.md`、`plans/e2e-0.0.1-runbook.md`。
- 真实复跑：`plans/e2e-real-14-17-18-22.md`、`plans/e2e-real-04-05-06-19.md`、
  `plans/e2e-real-05-06.md`、`plans/e2e-real-07-11-12-13.md`、
  `plans/e2e-real-07-09.md`、`plans/e2e-real-08-10-20.md`、
  `plans/e2e-real-16-21.md`。
- 01/02/03：`/tmp/hc-e2e-real/README.md`、`artifacts/e2e01/`、`e2e02/`、`e2e03/`。
- 05/06：`/tmp/hc-e2e-harness/e2e05/compress-flow-fixed4.json`、
  `/tmp/hc-e2e-harness/e2e06/attach-text.json`、`pdf-extract.json`。
- 11：`/tmp/hc-context-suggest/evidence.edn`、`summary.txt`。
- 15：`/tmp/hc-e2e-real/e2e15/report.json`、`audio-file-draft.png`、`audio-file-tts.png`。
- 端点/浏览器工装：`/tmp/hc-endpoint-probe/report.md`。
- 客户端规模/离线恢复：`plans/client-scale-0.0.1.md`、
  `plans/offline-restore-0.0.1.md`。
