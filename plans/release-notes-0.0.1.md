# Herdchestra 0.0.1 发布说明（草案）

> 状态：**草案，不是已发布版本**。截至 2026-11-05 本稿生成时，仓库 `HEAD`
> 只跟踪 `AGENTS.md`，产品源码、测试、构建清单与文档仍未提交（发布条件 6 /
> G8 为 FAIL）。本文件用于满足 `RUNNING.md` 发布条件 8，并作为提交与打标前的
> 核对清单；它不表示 0.0.1 已经发布。
>
> 版本源：`src/hc/version.cljc` 的 `"0.0.1"`。2026-11-05 复检时
> `pixi.toml`、`package.json`、`--help` 与 `/healthz` 均为 `0.0.1`。
>
> 基线证据树：`HEAD 015229e28bd0fdddc14060a28f1348185c6e765b`。本文件引用
> 该树上的门禁结果；此后 `src/` 与 `test/` 仍被并行修改，最终发布前必须在
> 合并后的树上重跑，不能把旧结果直接当最终放行证据。
>
> **final17（W96 clean rebuild，final16 确认）**：bundle `efbc465a` 逐字节相同（mtime 10:11:35）+ 树 `99aa81a4`；W87 修复已在 bundle（closure.variable.map 证实，W86 字面 grep 误报）；全量 3813/24695/0F/0E/exit 0/357 s；app-check `:ok true`/0.0.1/`:component-errors {}`；**W33 已宣布 final17 最终冻结 PASS**（manifest `c2158f6b`，CSS `332f9fd3`/`77da0dd4`，git porcelain 16、`hs_err` 9）。W87 修复已验证在 bundle（closure.variable.map：`recover_pending_materials_commands_BANG_`→`e_b`、`receipt_sweep_at`→`d_b`、`merge_local_state`→`c_b`），W86 的 grep 未命中为 release `:simple` 改名误报；E2E11 若仍报 pending 命令属服务端 pending 锁/时序。final16 的 E2E05/06/15 run5 仅诊断，待 final17 复跑确认。
> **E2E 状态（醒目）：22 PASS、0 PARTIAL、0 BLOCKED、0 NOT RUN（final22）。**
> **E2E 22/22 为手工/真实端点口径**：E2E05 final22 42/42、E2E12 final22 48/48×2；final22 全量 3824/24735/0F/0E，app-check `:ok true`/0.0.1，版本五处 0.0.1。W130 正执行最终门禁复核 + commit + annotated tag `v0.0.1`（不 push）。**D-04（22 条全自动化 + mock）仍开放，不因 22/22 手工 PASS 而关闭。**
> **E2E12 final22 PASS（W121/W116）**：48/48、`ok:true`、NODE_EXIT=0（两个独立样本）；证据 `e2e12-c9cbfdf6-final22-w121.json`、`-assertfix.json`；D-27 关闭、D-28 关闭（`:last-move` 落库、撤销链路端到端 PASS）。
> **提交/tag 状态**：W130 正在执行最终门禁复核 + `git add -A` + commit + annotated tag `v0.0.1`（不 push）；commit/tag hash 待回填。D-04（22 条 E2E 自动化口径）保持开放——22/22 为手工/真实端点口径，不等于新 §8.5 自动化验收。
> **E2E11 final22 PASS（W22 隔离实例 `/tmp/hc-w22-e2e11:7622`）**：25/25 断言全 PASS、`ok:true`、NODE_EXIT=0；证据 `e2e11-c9cbfdf6-final22.json`；`suggest-switch-on` 关闭（`:memory-suggest?` 置位、整理记忆命令受理执行）、候选审批/拒绝/改范围、归档排除、临时会话一次性读取与自动关闭全 PASS、`frozenGap:null`；双记 `c9cbfdf6` + `1e824c83`。
> **E2E05 final22 PASS（W115 v8 并行隔离实例）**：42/42 PASS——D-24/D-25 保持关闭、D-28 `current-with-local` 修复不回归撤销段（撤销后活客户端即时「压缩 已撤销」、撤销按钮消失、outbound 还原原历史不含摘要）；证据 `e2e05-v8-c9cbfdf6-final22.{json,png}` + `-run.log`；双记 `c9cbfdf6` + `1e824c83`。
> **final22 最终冻结 PASS（W33/W125）**：bundle `c9cbfdf6…`（3,018,831 B，18:12:03；CSS `332f9fd3`；manifest c2158f6b）+ 树 `1e824c83…`（clean rebuild 确定性）；全量 3824/24735/0F/0E/exit 0/354 s；app-check `:ok true`/0.0.1；D-28 `current-with-local`+`merge-local-state`、E2E11 `bridge/connection`+`bridge/command-allowed?` 已验证入产物。final21 及更旧作废；证据双记 `c9cbfdf6` + `1e824c83`。
> **E2E12 final21 三样本（W121 run1 42/48、run2 43/48；W116 45/48）**：共同 FAIL = move-receipt-undo-visible、undo-button-after-reselect、undo-restores-book；entry-back-in-source-book/undo-restores-injection 时序敏感；run1 另有 card-single-turn 间歇泄漏。D-28 未关闭——服务端回执完整含 :undo、客户端已受理并补发 snapshot，断点在客户端回执应用链路。
> **D-28 运行时根因/修复（W124）**：`transact-state!` 异步提交，`sync-from-db!`/`apply-receipt-event!` 在 mutation 提交前读旧 DB（无 `:last-move`）→ 覆盖丢失。修复：`current-with-local(db)`（DB 投影与 `@last-state` 同会话时 `merge-local-state` 合并 local keys），6 处读取点全改用；测试 57/253 + 10/28 0F/0E。final21 作废；W125 clean rebuild → final22。
> **E2E11 final21 隔离复跑（W86）**：22 PASS / 1 FAIL——`suggest-switch-on`：`memory-suggest?` 未置位、「整理记忆」恒 disabled、`settings-update memory-suggest?=true` 被 `command-allowed?` 拒绝（“连接尚未完成同步；命令未发送”）；与 final20 同签名，疑与 W124 `transact-state!` 异步/stale-DB 覆盖同源；final21 作废，等 final22。
> **E2E11 suggest-switch-on 并入 final22（W124）**：同竞态族——`:ui/connection` 异步落库 vs `sync-from-db!` 同步读旧值 → `command-allowed?=false` → `queue-command` 拒绝；修复 `materials_bridge.cljs` 两处改用 `bridge/connection` 与 `bridge/command-allowed?`（adapter atom 同步投影）+ 断言。W124 收尾后 W125 重建再跑 final22。
> **W124 final22 并入收尾**：`materials_bridge.cljs` current-with-local、6 处 db 读取点、sync-from-db! connection 用 bridge/connection、timer command-allowed? 用 bridge/command-allowed?；新增 2 条回归（sync-from-db-preserves-last-move-behind-a-lagging-db、sync-from-db-reads-live-adapter-connection）。verify_materials_bridge 11/31、model-test 57/253 0F/0E；纯客户端、需重建。W125 clean rebuild → final22。
> **final21 最终冻结 PASS（W33/W118）**：bundle `0fbddd3c…`（3,018,796 B，16:34:21；CSS `332f9fd3`；manifest c2158f6b）+ 树 `319f035f…`（clean rebuild 确定性 PRE=POST）；全量 3824/24735/0F/0E/exit 0/354 s；app-check `:ok true`/0.0.1；unwrap-receipt 修复已验证入产物。final20 及更旧作废；证据双记 `0fbddd3c` + `319f035f`。
> **E2E12 final21 复跑**：45/48（final20 43/48）；D-27 关闭；D-28 部分好转（entry-back-in-source-book、undo-restores-injection PASS），残余 move-receipt-undo-visible、undo-button-after-reselect、undo-restores-book。unwrap-receipt 已入产物、模型单测可置位 :last-move，但运行时 :ui/materials 不可见 → 疑 transact-state!/merge-local-state/sync-from-db!/apply-snapshot 链覆盖；W123→W124 修复、W125 重建：D-28 真正根因=`transact-state!` 异步提交致 `sync-from-db!`/`apply-receipt-event!` 读旧 DB 覆盖 `:last-move`；修复=`current-with-local(db)`+6 处读取点（57/253+10/28 0F/0E）；**final21 作废**，W125→final22 全量 + app-check 后重冻结，E2E12 等 final22 基线复跑。
> **final20 最终冻结 PASS（W33/W114）**：bundle `87b0dee4…`（3,018,665 B，15:26:31；CSS `332f9fd3`；manifest c2158f6b）+ 树 `5e301ce4…`（PRE=POST）；全量 3823/24731/0F/0E/exit 0/354 s；app-check `:ok true`/0.0.1；D-25/D-27/D-28 修复已入产物；swarm_ui 2 失败为既有。final19 及更旧作废；证据双记 `87b0dee4` + `5e301ce4`。
> **E2E12 final20 复跑（冻结后新证据）**：43/48；D-27 全过、D-25 已由 W101 E2E05 final20 复跑确认关闭；5 项 FAIL 集中 move 撤销/pending 锁（undo/`:last-move`/回执应用）→ D-28 未根治；final20 冻结保持，E2E12 维持 PARTIAL；run2b 复核中。
> **E2E12 final20 run2b（W95）**：43/48，失败集与第一样本完全一致 → **D-28 确定性失败（非间歇）**；D-27 已修复（两样本卡断言全过）。证据 `e2e12-87b0dee4-final20-run2.json`；E2E12 维持 PARTIAL，等 final21。
> **D-28 真正根因/修复（W117）**：`unwrap-receipt` 拒绝解包网关信封 `{:ok? :command-id :receipt {..}}` → `receipt-result` 只读外层 `:result=nil` → `:undo` 缺失 → `:last-move` 不置位/撤销按钮不渲染（move 成功）。修复：任何 `map? :receipt` 解包 + merge + 外层回填；测试 3824/24735 0F/0E。final20 作废；W118 clean rebuild → final21。
> **E2E05 final20 复跑（W101 v8，验收证据）**：41/42 PASS——D-24/D-25 双关闭（撤销后活客户端显示“压缩 已撤销”、撤销按钮消失；outbound 还原原历史不含摘要）；唯一 FAIL `outboundCounts` 为脚本正则伪阴性（面板 发送10/摘要0/排除0 正确）。证据 `plans/evidence/e2e-05/e2e05-87b0dee4.{json,png}` + `-run.log`，双记 `87b0dee4` + `5e301ce4`。final20 作废转历史，final21 需复核 D-25 保持且 D-28 修复不回归 E2E05 撤销段；W117 确认 model_test 57/253、bridge 9/26、server 3824/24735 0F/0E；main.js mtime 早于源码编辑，final21 clean rebuild 需核对。
> **E2E05/06/15 final19（W93/W101b）**：E2E05 v8 38/42——D-24 关闭、D-25 仍 FAIL（撤销命令被拒“命令未送达”、UI 仍摘要1/含压缩摘要/无“已撤销”标记）；E2E15 PASS（regen+版本切换）；E2E06 PASS（文本腿五轮一致，extract 为 OCR 环境限制）；tree before `f8477191`/after `25beab95`（漂移，bundle 未变）。
> **D-25 根因/修复（W113）**：cached-entry 键 `[(:revision session) epoch]` 因 `session-summary` 无 `:revision` 恒 `[nil epoch]` → `:session/by-id` 的 `:context`/`:compressions` 永久缓存 `:applied`（history 正确）→ delta 无路径、活客户端不更新；重启正确。修复 `session-summary` 加 `:revision` + 缓存/delta 测试 394/2761 0F/0E。W110 也修 D-27/D-28；final19 作废，W114 → final20。
> **final19 最终冻结 PASS（W33/W107）**：bundle `168318c1…`（3,017,514 B，12:42:52；CSS `332f9fd3`）+ 树 `f8477191…`；全量 3818/24714/0F/0E/exit 0/356 s；app-check `:ok true`/0.0.1；D-27/D-28 修复已入产物（materials-bridge 8/23 0F/0E；swarm_ui 2 失败为既有）。final18 及更旧作废；证据双记 `168318c1` + `f8477191`。
> **E2E12 final19 复跑（W95）**：43/48 PASS（final18 40/48，+3），证据 `e2e12-168318c1-final19.json`；手动移回/pending 锁释放/跨会话卡隔离 PASS；**D-27 未根治**（仅本轮指令卡在 w22-s1 内 r2/r7 仍泄漏）、**D-28 未根治**（`撤销移动`/`:last-move` 不出现）；E2E12 保持 PARTIAL。
> **剩余 5 FAIL 明细**：D-27 2 项（r2/r7 仍见 “MR-列假设 · turn”；疑 clear/version 或服务端 last-user 回退 `system.clj:519`）；D-28 3 项（move 后 20 s 无撤销、reselect edit 30 s 无、条目未绑回书 B；move 成功但 `:last-move` 未置位；pending 锁已释放）。证据 `e2e12-168318c1-final19.json`、`shots/e2e12-move-fail.png`。
> **并行 run2（独立实例 `/tmp/hc-w22p:7595`，无锁）**：42/48（run1 43/48）；D-27 仅 r2 复现（r7 已清除，clear 延迟）；D-28 撤销/`:last-move` 两跑均无，pending 锁/手动移回 run1 PASS、run2 FAIL（sweep 时序竞争）；世界书两跑全过。证据 `e2e12-168318c1-final19-run2.json`；E2E12 保持 PARTIAL。
> **materials_bridge.cljs 12:36:37 写入**：写入者未确认（W106 否认），但 W107 PRE/POST 树 hash 同 `f8477191`、bundle 12:42:52 晚于 mtime、harness 在该内容上 8/23 0F/0E；以内容 hash 为准、不追 mtime；final19 不受影响。
> **树漂移（W106 bug1，11:55–12:04）**：final18 基线失效，final19 待建；final18 的 E2E05 v8 仅诊断。
> **E2E05 v8 final18 诊断（W93）**：sessionId DOM fallback 稳定（`hc-session-fbdf5f26`）、claim `took=true`、prepared/applied PASS（摘要1/发送0条）、originalTurns 3/3；中断于 continued-twice `awaitReply`（人为 kill，非产品失败）；撤销段未执行；仅诊断，等 final19。
> **D-27/D-28（W106）**：D-27 验证通过；D-28 根因 `set-materials-state` stale 丢弃 receipt 写入 → move 卡 `:submitting`；修复 merge-local-state + orphan drain + 5s sweep；测试 106/783 + 10/67 0F/0E。final18 作废；W107 完整 client 测试 → 重建 → final19 全量。
> **D-27/D-28 详细根因（W104）**：D-27——`send-snapshot!` 仅用 draft/edit/attachments/quotes 播种 reducer，`:composer/send` 不带 snapshot，payload 缺 turn 键，version 未进 `select-keys`；修法：snapshot 作权威 payload + 补 version。D-28——`set-materials-state` stale 丢弃 receipt 写入（Fulcro transact! 异步 revision），`apply-receipt-event!` 未落库时丢弃不 park；修复 merge-local-state + deferred 重读 + orphan park/drain。测试 8/23、5/23、JVM 3818/24714 全 0F/0E。
> PASS=01/02/03/04/05/06/07/08/09/10/11/13/14/15/16/17/18/19/20/21/22；PARTIAL=12；
> BLOCKED=无（E2E 11 已于 final19 PASS）。E2E 未全部 PASS，0.0.1 不得打 tag；逐条状态见
> `plans/e2e-master-status-0.0.1.md`。根 `AGENTS.md` §8.5 要求“22 条全部
> 自动化 + mock 模型/OAuth/WebDAV/S3”；上述手工/真实端点结果不是新口径的
> 最终验收，自动化结果待 W10。
>
> **前冻结 bundle（历史）**：`072587a4`（3,011,162 B，13:51:04；CSS `332f9fd3`；
> 源码树 `a97c68f7`）。W33 核对 `__HC_DB__` 1（sessionIds/workerPrograms/
> activeId/dashboard 已进构建）、D-08 四标记、AbortController 19、
> hc-markdown 1、hc-message-stream 2、W18/W19、B14/B15、speech 31、
> hc-protected-program 14；bundle 与源码同树。**W35 final7 单树全量 PASS**：
> 3749 tests / 24310 assertions / 0F / 0E / exit 0 / 380 s
> （13:53:52–14:00:12；PRE=POST、`hs_err` 9→9、峰值 1.97 GiB、14:00:56
> 复核），**全量门禁条件 5 满足**。
> **final7 基线失效（W5 服务端修复）**：`system.clj` `prefer-owner-snapshot`
> 修复（选中 owner snapshot 缺 `:worker-jobs` 时从另一份合并；router handle
> revision 更高但只带 session context，Board snapshot 发布 0 个 worker-programs；
> 客户端 `__HC_DB__` 确认 `:session/by-id` 有 session 但 worker-programs=0）。
> bundle `072587a4` 不变、源码树变为 `d1d8d08d…`（唯一变更
> `src/hc/server/app/system.clj` 14:08:18），final7 基线失效。**W35 final8
> 单树全量 PASS**：3749 tests / 24310 assertions / 0F / 0E / exit 0 / 445 s
> （14:11:10–14:18:35；PRE=POST、`hs_err` 9→9、峰值 2.00 GiB），app-check
> exit 0/31 s/`:ok true`/0.0.1；D-16 根因指向该服务端合并逻辑。
> **最终冻结：bundle `72de4f9a`（3,014,943 B，2026-11-06 17:45:52；CSS
> `332f9fd3`）+ 源码树 `36fddd6d…`。W35 final13 单树全量 PASS**：3795 tests /
> 24596 assertions / 0F / 0E / exit 0 / 356 s（02:29:42–02:35:38，PRE=POST
> 同树 `36fddd6d`；`/tmp/hc-full-test-final13.log`、`-meta.txt`）；git 2020、
> `hs_err` 9、峰值 1.95 GiB；bundle 未变；app-check exit 0/14 s/`:ok true`/
> 0.0.1/`:component-errors {}`。**全量门禁条件 5 满足**。D-23 已由 W35 落地（服务端
> only，`:send-message` 写 `:runs`/`:run-order`），代码/测试完成、待 E2E12
> 验证；final12 的 `7dfcf0d7` 及更旧前冻结。
> **final14 曾冻结（现历史）：bundle `732baf05…`（3,015,403 B，2026-11-07
> 04:33:20；CSS `332f9fd3`）+ 源码树 `79f21de9…`。final14 单树全量 PASS**：
> 3805 tests / 24650 assertions / 0F / 0E / exit 0 / 352.10 s（04:34:50–
> 04:40:42；PRE=POST 树 `79f21de9`、git 2025、`hs_err` 9；VmHWM 未采样），
> app-check `:ok true`/`0.0.1`/`:component-errors {}`；当时全量门禁条件 5
> 满足。final13（`72de4f9a`+`36fddd6d`）与 `2b269e0d` 作废；D-23 仍待
> E2E12 验证。
> **D-23 已修复（W35，服务端 only，客户端 bundle 不因该修复重建）**：`:session/send-message`
> 经 commands 网关/`dispatch`/`default-owner-kinds` 到达 `sessions.clj` 分支，
> 并写 session run + input-snapshot，使“上次发送”冻结快照可达；不改断言。
> 代码/测试/全量已入最终树 `651b9a45`（亦含于前冻结 `79f21de9`）；待
> E2E12 复跑验证“上次发送”。
> **W70/W71/W79 已入 final15 树 `651b9a45`**：W70=服务端 pair-hint
> compaction boundary（`applied-compression-set`/`compaction-boundary`/
> `supersede-chain`/`pair-request-boundary`，`system.clj`）+
> `merge-owner-context` 保留更新压缩记录 + `pair_hint/hint.clj`
> `:compaction` boundary/`reanchor`/`:compaction-cursor`；W71=客户端
> materials `apply-snapshot` 保留 turn-card/`:instruction-turn`、
> `reset-draft-for-command` 覆盖 entry-move/entry-delete、
> `drain-orphan-receipts` 接线；W79=`model.cljc` `begin-session`/
> `select-book` + `materials_bridge.cljs` `transact-state!`（E2E12
> session-receipt）；D-22/D-23 已由 W76 final15 复跑确认修复；
> D-24/D-25/D-26 待 final17 E2E 复跑确认后关闭。
> **final15 曾冻结（现历史）：bundle `a14d9abd`（3,015,902 B，05:50:40；
> CSS `332f9fd3` 未变）+ 源码树 `651b9a45`（05:49:30 后无写入）；W35
> final15 单树全量 **PASS**：3808 tests / 24669 assertions / 0F / 0E /
> exit 0 / 356 s / 峰值 2.05 GiB（PRE=POST `651b9a45`，git 2027，
> `hs_err` 9）；app-check `:ok true`/0.0.1/`:component-errors {}`。
> final14（`732baf05`+`79f21de9`）作废。
> **最终冻结（final16，W33/W91）**：bundle `efbc465a`（3,016,559 B，
> 2026-11-07 08:43:18；CSS `332f9fd3` 未变）+ 源码树 `99aa81a4`；
> 单树全量 **PASS**：3813 tests / 24695 assertions / 0F / 0E / exit 0 /
> 353.44 s（08:52:45–08:58:38；PRE 树 `b9570d04`→POST `99aa81a4`，差异仅
> `run_port_test.clj` 两处定界符修复——修复前该文件 reader 报错、阻断整
> 套件；git porcelain 16、`hs_err` 9）；app-check exit 0/`:ok true`/
> `0.0.1`/`:component-errors {}`/store open；版本五处 0.0.1；meta
> `plans/evidence/final16-freeze-meta.json`。**全量门禁条件 5 满足**。
> **final16b 复核 PASS**：W92 PRE=POST `99aa81a4` 同树补跑
> 3813/24695/0F/0E/exit 0/355 s，冻结成立。
> final15 及更早作废；**E2E/视觉证据双记 `efbc465a` + `99aa81a4`**。
> **冻结暂停（final17→final18 过渡）**：W99 已落地 D-26 修复
> （`model.cljc` `:submitted` 非终态/`:not-found`→`:refused`；
> `materials_bridge` 5s 独立清扫 + dispatch 后清扫；CLJS 4/12 0F/0E；
> JVM 测试待 W100），final17 树漂移至 `0a62fd2c`，**final17 冻结暂停**；
> W100 重建 → final18 单树全量 + app-check 后重冻结。E2E05/11/12 等
> final18 基线复跑；此前 `efbc465a`+`99aa81a4` 证据仅作诊断。
> **视觉 final11（W65，01:55）**：`plans/evidence/visual-audit/final11/` 11 张
> （会话区 1280/820/360、暗色、200%、看板/材料×2/设置）+ `final11.json`（9/9
> step OK）；bundle `72de4f9a` + CSS `332f9fd3` 未变、视图源码未变；截图运行时
> 树为 `ab801450`（final11），当前树 `7dfcf0d7`，报告双记两者。未覆盖项
> （B13/B14 逐项、材料深色/200%/扩展小应用、ops 各分区、startup 向导、board
> 种子态）如实标未跑，不宣称 PASS。
> `0c05b7d1`/`7667323a`（final6 PASS 3749/24310/0F/0E）与 `ea9e5732`
> 及更旧全部作废/前冻结；所有 E2E/视觉证据须绑 `72de4f9a` + final12 树。历史：
> `52547df2` 的 3748/24306/0F 已随 D-17 失效；`a4518891` final4 为
> 3749/24310/1F（scheduler `[0 0]`，非 D-17）；final5 的 0F 属混合树。
> `app-check` exit 0 / `:ok true`；版本五处 0.0.1。**


## 1. 0.0.1 的构成

> **E2E05（final17）**：e2e05v5 再次失败——`sessionId=null`（新建会话 4 次重试未取到）、browser context 在 `awaitReply` 早期关闭（Target page closed），仅 3 checks；v5 在 final16/final17 两轮均中断。备选 `w93-e05-v8.sh` 已排队（DOM fallback + reuse-blank + takeover claim）；E2E15/06 由 run5 继续。

> **E2E05 v8 诊断（W93，efbc465a，非 final18 验收）**：DOM fallback 生效（`hc-session-de56348a`）、controller-claim `took=true`、composerStable/attachmentTextSendable PASS；但 `prepared=false`（6 次“使用摘要”未出现）、originalTurns 2/3（第三轮 reply missing），按指令终止；fallback/claim 有效，prepare/apply 待 final18 复跑。

> **E2E12 final18 复跑（W33）**：48 断言 40 PASS / 8 FAIL（`plans/evidence/e2e-11-12/e2e12-f8c0f53a-final18.json`）。真实缺陷：仅本轮指令卡泄漏 3 项（client 镜像 payload 不含 turn 选择，`clear-turn-after-send!` 不触发）；条目移动撤销不可用 + 同 key pending 锁不释放 5 项（move 回执未应用、`:last-move` 缺失，疑 D-26 残留）。世界书多轮/预算/窗口/移动提供性全过；E2E12 保持 PARTIAL，D-26 需补 move 路径。

> **D-26 修复已落地（W99）**：`model.cljc`（`:submitted` 非终态、`:not-found`→`:refused`）+ `materials_bridge`（5s 独立清扫 + dispatch 后清扫）；CLJS bridge 4/12 0F/0E，JVM model 测试待 W100。**final17 树漂移 `0a62fd2c`，冻结暂停**；W100 重建 bundle → final18 全量 + app-check 后重冻结。

> **final18 最终冻结 PASS（W33/W100）**：bundle `f8c0f53a…`（3,017,311 B，11:21:45；CSS `332f9fd3`）+ 树 `6d1ad03b…`（PRE=POST）；JVM 聚焦 57/271 0F/0E；全量 3816/24705/0F/0E/exit 0/355 s；app-check `:ok true`/`0.0.1`/`:component-errors {}`；meta `plans/evidence/final18-freeze-meta.json`。**D-26 sweep 已入产物**（`setInterval(h_b,5E3)` + `install! receipt-sweep-timer`；bundle 内 `setInterval` 4 处）。final17 及更旧作废；E2E 证据双记 `f8c0f53a` + `6d1ad03b`。
> **final18 作废 → final19 准备中**：W106 完成 D-28 修复（`set-materials-state`
> stale 丢弃 receipt → move 卡 `:submitting`；修复 `merge-local-state` +
> orphan drain + 5s sweep），D-27 验证通过；测试 106/783 + 10/67 0F/0E。
> W107 跑 final19 全量 + app-check 后重冻结；E2E 可并发复跑（独立 data-dir/
> profile/端口），`f8c0f53a`+`6d1ad03b` 证据仅作诊断；证据双记 `168318c1` +
> `f8477191`。
> **最终冻结（final19，W33/W107）**：bundle `168318c1`（3,017,514 B，
> 12:42:52；CSS `332f9fd3` 未变）+ 源码树 `f8477191`（PRE=POST）；全量
> **3818 tests / 24714 assertions / 0F / 0E / exit 0**；app-check `:ok true`/
> `0.0.1`/`:component-errors {}`。final18 及更早作废；**E2E/视觉证据双记
> `168318c1` + `f8477191`**。
> **并发策略变更（用户指令）**：不再要求全局单例锁/单执行者；多
> worktree/实例可并发跑测试与 E2E（独立 data-dir/profile/端口），
> E2E05/06/11/12/15 可并行；此前单执行者/锁窗口安排作废。证据仍双记
> `168318c1` + `f8477191`。
> **final19 作废 → final20 准备中**：D-25 根因已由 W113 修复（cached-
> entry 键 `[nil epoch]` 致 `:context/compressions` 永久 `:applied`、
> delta 缺失；session-summary 加 `:revision`，测试 394/2761 0F/0E）；
> W110 修 D-27（draft turn-selection）与 D-28（last-move assoc nil）。
> 树已变（14:18–15:11），bundle `20f3b90f`（14:12:20）落后；W114 重建 →
> final20 全量 + app-check 后重冻结；E2E05/12 等 final20 基线复跑。
> **最终冻结（final20，W33/W114）**：bundle `87b0dee4`（3,018,665 B，
> 15:26:31；CSS `332f9fd3`、manifest `c2158f6b` 未变）+ 源码树 `5e301ce4`
> （PRE=POST 120 s 稳定）；全量 **3823 tests / 24731 assertions / 0F /
> 0E / exit 0 / 354 s**；app-check `:ok true`/0.0.1/`:component-errors {}`；
> 聚焦定向 270+167 0F/0E；标记核对通过；**D-25（cached-entry `:revision`）、
> D-27（draft turn-selection）、D-28（last-move assoc nil）修复已入产物**；
> swarm_ui 2 断言失败为既有。final19 及更早作废；**E2E/视觉证据双记
> `87b0dee4` + `5e301ce4`**；可复跑 E2E05/12（D-25/D-27/D-28 关闭确认）。
> **final20 作废 → final21 准备中**：D-28 根因=`unwrap-receipt` 对网关
> 信封 `{:ok? :command-id :receipt {..}}` 拒绝解包 → `receipt-result`
> 只读外层 `:result=nil` → `:undo`/`:last-move` 缺失；修复=任何
> `map? :receipt` 解包+merge+外层回填；测试 3824/24735 0F/0E。final20
> （`87b0dee4`+`5e301ce4`）作废转历史；W118 clean rebuild → final21 全量
> + app-check 后重冻结；E2E12 等 final21 基线复跑。

> **D-26（W97/W99）**：durable gate 探针 `commands/session/w22-s1` 仅 4 条 receipt（1×`settings/update read?`+`suggest?false`、3×memory approve/reject），suggest 的 `settings/update` 从未到达 `accept!`；传输层丢失、pending 锁永久持有；自愈仅在 `sync-from-db!` 触发；`receipt!` 在 subscription-id nil 时静默 false。修复：独立 5s 清扫 + `receipt!` 可重试（覆盖 nil 分支）；`:submitted` ack 不释放锁；测试后 final18 重建 + 全量。

> **final16b 复核 PASS（W92）**：PRE=POST `99aa81a4` 同树补跑全量 **3813/24695/0F/0E/exit 0/355 s**，app-check `:ok true`/0.0.1/`:component-errors {}`/store open；bundle/CSS 未变，git porcelain 16，`hs_err` 9；日志 `/tmp/hc-full-test-final16b.log`、meta `plans/evidence/final16b-freeze-meta.json`。**final16 冻结成立**，E2E 证据双记 `efbc465a` + `99aa81a4` 生效。

0.0.1 是**源码级本地发布**，不是签名安装包，也不是远程多用户部署。

| 项 | 0.0.1 的内容 |
|---|---|
| 交付形态 | Git 工作树中的源码、测试、Pixi 清单、`shadow-cljs` 客户端构建与文档；不产出 `.dmg`/`.msi`/`.deb`/Flatpak 或签名安装包 |
| 运行时 | Pixi 0.83.0 供应 JDK 21.0.10、Clojure 1.12.6、Node 22.23.4；宿主 JDK/Node 不是承诺运行时 |
| 服务端 | 单机单例进程；Datalevin 1.2.10 当前态存储；每会话 Owner；模型端口；workspace/program/shell 执行器；Sente/HTTP 网关 |
| 客户端 | shadow-cljs `:app` 构建的浏览器工作台，从 `resources/public` 提供；实时通道与静态资源同源 |
| 已装配模块 | 存储、会话、Owner、Mission、模型端口（LangChain4j OpenAI/Ollama）、workspace/program/shell、Sente/AG-UI 网关、静态工作台、设置、materials、diagnostics、scheduler、backup、extensions、speech、search、documents、attachments、artifacts、files |
| 数据目录 | 默认按 OS 决定；Linux 为 `~/.local/share/herdchestra`。`--check` 默认使用临时根 |
| 网络边界 | 默认 `127.0.0.1` + 同源检查；远程部署需要额外的认证与 TLS，不能只改 `--host` |
| 模型 | 不内置模型、不打包权重；启动不需要模型，聊天需要显式配置连接。没有模型时明确提示，不使用假模型或静默回退 |
| GPU | 不使用 GPU；OCR 走 CPU Tesseract，不依赖系统安装 |

版本号只有一个运行时源：`src/hc/version.cljc`。`pixi.toml`、`package.json`
与用户可见的 `--help`/`/healthz` 必须与它同值。

## 2. 已验证项

以下结果均在基线树 `015229e` 上取得；命令中的 `pixi` 指 `/tmp/pixi`
（0.83.0）。**表中的耗时、文件大小、包数、测试/断言数与性能数字都是该次
测量的基线值，不是最终树的当前值；发布门禁以最终 `pixi run test`、
`pixi run app-check` 与 `pixi run client-release` 日志为准。**

| # | 项目 | 命令 | 结果 | 证据 |
|---|---|---|---|---|
| 1 | 版本一致性 | `cat src/hc/version.cljc`；`grep '^version' pixi.toml`；`grep '"version"' package.json`；`pixi run clojure -M:server -m hc.server.app --help`；`pixi run app-check` | 五处均为 `0.0.1`；`--help` 首行为 `Herdchestra 0.0.1 server`；health 为 `:version "0.0.1"` | 本文件 §7；`plans/docs-version-audit-0.0.1.md`；`/tmp/docs-version-help.log`、`/tmp/docs-version-app-check.log` |
| 2 | Pixi 锁定安装 | `/tmp/pixi install --locked` | 退出 0；该次仓库内约 2 s（以最终日志为准）；另在 `/tmp/hc-baseline-clean` 用同一 `pixi.toml`/`pixi.lock` 复验退出 0，JDK/Clojure 正确。rattler 缓存已预热，未验证冷下载 | `plans/release-0.0.1-baseline.md` §2 G1 |
| 3 | 客户端依赖 | `/tmp/pixi run client-install`（`npm ci`） | 退出 0；该次 `added 134 packages`（以最终 `npm ci` 日志为准）；有 `core-js@2.6.12` 与 `whatwg-encoding@3.1.1` 传递依赖弃用警告，不阻断 | 同上 §2 G2 |
| 4 | 客户端 release 构建 | `/tmp/pixi run client-release` | 退出 0；基线树 `main.js` 2,753,029 bytes、CSS 570,383 bytes（以最终 `client-release` 日志为准）；该次 17 个 `:infer-warning`，不阻断 | 同上 §2 G3 |
| 5 | 启动自检 | `/tmp/pixi run app-check` | 退出 0；基线 14 s、2026-11-05 复检 18.79 s（以最终 `app-check` 日志为准）；health `:ok true`、`:version "0.0.1"`、`:store {:open? true}`、`:gateway {:epoch 1 :seq 1 :max-pending 4096 :subscriptions 0 :stale 0}`、`:models {:configured? false}`（`:components :model {:configured? true}` 只表示模型端口组件存在，不代表模型连接已配置）；`:fleet-gc`/`:fleet` 均 `:started`；该次自动隔离到 `/tmp/hc-check-3580104557462498968`（临时路径每次不同） | 同上 §2 G4；`/tmp/final-app-check4.log`、`/tmp/docs-version-app-check.log` |
| 6 | 全量测试 | `/tmp/pixi run test`（`clojure -X:test:compose:server:client`） | 基线树退出 0，343 s；**3406 tests / 22121 assertions，0 failures / 0 errors**（以最终 `pixi run test` 日志为准；当前树未重跑）；运行窗口内 `src/`、`test/` 无改动；该次 106 次 test.check 属性运行全部 `:result true`，seed 每次随机并记录在日志 | 同上 §2 G5；`/tmp/final-full-test3.log` |
| 7 | 真实启动冒烟 | `pixi run clojure -M:server -m hc.server.app --host 127.0.0.1 --port 0 --data-dir /tmp/hc-baseline-data --workspace-root /tmp/hc-baseline-work --static-root resources/public` + `curl` | 该次实际端口 39697（`--port 0` 每次不同）；`GET /healthz` 200 且 `:ok true`/`:version "0.0.1"`；`GET /` 200 且返回 `<title>Herdchestra 工作台</title>`、`/css/workbench.css`、`/js/main.js`；SIGTERM 后可用同一数据目录再次启动并打开存储 | 同上 §2 G6 |
| 8 | 无模型门槛 | `node test/js/no_model_probe.cjs --url 'http://127.0.0.1:7581/#/sessions' --out plans/evidence/no-model` | 真实 release bundle + 空数据目录：显示“尚未选择模型/先选择一个可用模型”，发送按钮 `disabled`，Enter 后草稿保留、`messageCount=0`，无假模型、无静默回退 | `plans/client-scale-0.0.1.md` §1；`plans/evidence/no-model/` |
| 9 | 离线备份核心 | `/tmp/pixi run clojure -M:server -m hc.server.backup.cli backup ...`（隔离根 `/tmp/hc-offline-vzwJBd3u`） | 库路径 AES-GCM 加密、密码 >=12、秘密默认排除、错误密码不改资料、merge 重复导入幂等、恢复后计划暂停且不自动运行：PASS；CLI 的早期缺陷见 §4.4，当前树复检见 §2 第 10 项 | `plans/offline-restore-0.0.1.md` §2–§3；`/tmp/hc-offline-vzwJBd3u/evidence/` |
| 10 | 备份 CLI 当前树复检 | 隔离根 `/tmp/hc-docs-verify-final`；`backup`（默认）、`backup --encrypt true`、全新目录 `restore --mode replace`、重复 replace、无密码 backup；另在 `/tmp/hc-prep-restore-0.0.1` 复测完全空目录 restore | 默认备份 `:encrypted? true`/`:encryption :aes-gcm`；`--encrypt true` 退出 0；全新目录 restore exit 0、`:status :complete`（CLI 自带 `:no-prior-app-commands? true`）；重复 replace 返回 `:status :already-imported`，不新增恢复点；无密码且未显式 `--encrypt false` 报 `:BACKUP_PASSWORD_REQUIRED`。**完全空目录（无任何 store）也可由 CLI 创建 store 并 restore，exit 0**；本次空备份回执 `:store-observed? true`、`:status :no-changes`。旧“完全空目录报 `STORAGE_NOT_FOUND`”已被本次复测否定 | `/tmp/hc-docs-final-restore.log`、`/tmp/hc-docs-final-restore-repeat.log`、`/tmp/hc-docs-final-nopassword.log`、`/tmp/hc-prep-restore-0.0.1/restore-empty.log` |
| 11 | 客户端规模（jsdom） | `HC_SCALE_SESSIONS=1000 HC_SCALE_MESSAGES=0 bash test/js/verify_workbench_scale_dom.sh`；`HC_SCALE_SESSIONS=2 HC_SCALE_MESSAGES=2000 ...` | 该次 jsdom 测量：1,000 会话初次渲染 494 ms、1,000 行、滚动事件 1.12 ms、堆 231 MB；2,000 消息初次渲染 1,956 ms、2,000 节点、滚动事件 2.06 ms、堆 760 MB；纯投影基准 PASS。不是最终树基准 | `plans/client-scale-0.0.1.md` §2–§3；`plans/evidence/scale-dom/` |
| 12 | 两万字草稿 | `node test/js/scale_draft_probe.cjs --url 'http://127.0.0.1:7581/#/sessions' --out plans/evidence/scale-draft` | 该次真实浏览器测量：插入 20,000 字符 3,794 ms（CDP 上界）、选区 <1 ms、Ctrl+Z 31 ms、localStorage 22,037 bytes、reload 后 20,000 字符恢复、堆 17.7 MB。不是最终树基准 | `plans/client-scale-0.0.1.md` §6；`plans/evidence/scale-draft/` |
| 13 | 网关安全边界 | HACK.md §9 的 JDK HTTP 冒烟 | 默认 loopback + 同源；evil `Origin` 对 `/chsk`、`/blobs`、`/attachments` 返回 403；同源/无 Origin 的 loopback 请求 200；`:allow-remote? true` 没有认证钩子时启动拒绝 | `HACK.md` §9 第 9、12 条 |
| 14 | 小应用生成与存储隔离 | 真实 app `POST /api/workspace/miniapp-generate`；两应用注入检查 | 三次真实生成 218.82 s / 292.80 s / 79.06 s；默认 300 s、上限 600 s；超时 `:MODEL_TIMEOUT` HTTP 504；注入 `data-hc-miniapp-storage` 前缀 `miniapp:<app-id>:`，两应用互不可见、clear 只清本应用；手动小应用只有客户端 IndexedDB 隔离 | `/tmp/hc-miniapp-timeout/request.json`、`response.edn`、`curl.meta`、`/tmp/hc-miniapp-storage/evidence/injection-check.edn`；`plans/server-miniapp-0.0.1.md` |

属性测试现状：85/104 模块有至少一个属性测试文件引用（60 个属性测试文件，
134 个 defspec + 36 个 quick-check，共 170 次属性检查）。其余 8 个确定性模块
与 11 个无直接测试模块按 `plans/property-test-coverage-0.0.1.md` 分类，不宣称
全部覆盖。F01–F10 每项至少一条属性；F04 当前实际错误码为
`:STRUCTURE_CONFLICT :missing-parent`，但不宣称覆盖全部子场景。

24 类失败文案现状：**10 符合 / 13 偏差 / 1 缺失**。缺失项是“额度不足 UI
接线”（copy 模块已有文案，用户界面尚未接线）；13 项偏差的可执行补丁清单见
`plans/ui-contract-audit-0.0.1.md` §9。

> 第 6 项是基线树的结果。2026-11-05 之后 `src/`/`test/` 仍被并行编辑；
> 发布提交前必须在最终树重跑 `pixi run test`、`pixi run app-check` 与
> `pixi run client-release`，并把新的测试/断言数、seed 与退出码写回本文件。

## 3. E2E 状态：22/22 全部 PASS（手工/真实端点口径）

早先批次曾统计为 18 PASS / 4 BLOCKED；该统计已过时。最新汇总见
`plans/e2e-master-status-0.0.1.md`。**当前统计为 22 PASS / 0 PARTIAL /
0 BLOCKED（final22，`c9cbfdf6` + `1e824c83`）；该口径为手工/真实端点
证据，不是 §8.5 自动化验收口径，自动化结果待 W10（D-04 仍开放）。**

**验收口径变更（根 `AGENTS.md` §8.5）**：22 条 E2E 全部自动化，配套 mock
模型服务与 OAuth/WebDAV/S3 mock 外部服务；手工验收只用于 mock 无法覆盖的
宿主平台差异。下表是 2026-11-05 的手工/真实端点证据，不是新口径下的最终
验收；W10 的自动化结果出来前，不得把任何一条写成自动化 PASS。
验收按 §7.4 八段用户旅程（首次启动、日常对话、长任务、中断与恢复、
历史与知识、扩展能力、导出/备份/迁移、设置与诊断）组织；旧八组 UI 规范
不再是验收单位。

统计：**PASS 22（01/02/03/04/05/06/07/08/09/10/11/12/13/14/15/16/17/18/19/20/21/22）；
PARTIAL 0；BLOCKED 0；NOT RUN 0。**

| E2E | 最新结论 | 证据 | 仍存 finding / 待复跑 |
|---|---|---|---|
| 01 | PASS（13/13，无 workaround） | `/tmp/hc-e2e-real/artifacts/e2e01/e2e01-result.json` | 无（D7 UI 首 chunk/标签仍单列） |
| 02 | PASS（8/8） | `/tmp/hc-e2e-real/artifacts/e2e02/e2e02-result.json` | 停用提示为中文“所选连接已停用；请启用连接或选择其他模型，草稿已保留” |
| 03 | PASS | `plans/e2e-real-03-04.md`、`plans/evidence/e2e-03-04/e2e03-stub.json`、`e2e03-real-model.json` | 停止后 15 s 零请求、切回正文保留均 PASS；首 chunk 已修 |
| 04 | PASS | `plans/e2e-real-03-04.md`、`plans/evidence/e2e-03-04/e2e04-*.json` | 重启前后导出 sha256 一致；分支包含选中消息、导出过滤、历史镜像已修 |
| 05 | PASS | `plans/evidence/e2e-05/e2e05-v8-c9cbfdf6-final22.{json,png}` + `-run.log` | final22 复跑（W115 v8，`c9cbfdf6`+`1e824c83` 双记，18:22–18:43，并行隔离实例）：**42/42 PASS，ok:true，D-24/D-25 保持关闭、D-28 `current-with-local` 修复不回归撤销段**——撤销后活客户端面板即时显示「压缩 已撤销」、撤销按钮消失（`undone`/`undoneMarked`/`undoRestoresRange`/`undoSendsRestoredHistory`/`undoDropsSummary`/`afterUndoOutboundHasNewMessage` 全 true）；outbound 含全部原消息且不含压缩摘要；outboundCounts 伪阴性修复保持（面板 发送10/摘要0/排除0）。沿革：final21（W115 v8，`0fbddd3c`+`319f035f`）42/42；final20（W101 v8，`87b0dee4`+`5e301ce4`）41/42（唯一 FAIL 为脚本正则伪阴性）；final19（W93/W101b v8）38/42，D-24 关闭、D-25 因客户端投影陈旧 FAIL；W113 根因修复（session-summary 加 `:revision`）后逐轮关闭。 |
| 06 | PASS（final17 冻结成立；final16 run5 仅诊断，待 final17 复跑定论） | `plans/e2e-real-05-06.md`、`e2e06-final-72de4f9a.json`；诊断 `/tmp/hc-w3e5r4/evidence-final/e2e06-textleg-final16.json` | final16 run5 文本腿 `extractRouteAvailable=false`、提取失败仅作诊断（final16 暂停，不作验收）；待 W30 判定环境（无 OCR）vs 产品、final17 复跑后定论 |
| 07 | PASS | `plans/evidence/e2e-07-08/e2e07-ab801450-*` | 最终冻结 72de4f9a/ab801450 上 13/13 PASS（27 项断言全 true、0 pageerror；stub +1；含导出“来源：”+3 完整 URL、来源卡、去重、未知日期、编号、失效来源、断网）；来源卡外链点击被 F7 吞掉（deadSourceOpened=false），单列产品缺口；final10 证据降前冻结 |
| 08 | PASS | `plans/evidence/e2e-07-08/e2e08-72de4f9a-*` | 最终冻结 72de4f9a/ab801450 上完整 PASS：`:chat?` 修复后 assistant 长文本流真实产生（`escToolCallPath=false`），Esc 停止/run inactive/内容保留/无迟到追加全 true；S1 `.hc-ask`/提交“42”/服务端 unpark/同一 run 继续/composer 15 s 恢复均 PASS |
| 09 | PASS | `plans/e2e-real-09-10.md` §17.11、`plans/evidence/e2e-09-10/e2e09-final.json`、`e2e09-final-pass-72de4f9a.json`、5 张截图 | 最终冻结 72de4f9a/ab801450 上 ok=true、30/30 checks：Plan→Reviewer→swarm、冲突卡 FILE_CONFLICT/stamp-mismatch（原/当前 stamp、外部编辑保留）、重读后 APPLIED、`worker,7` 落盘、server stat 最终、窄视口 360×360 冲突/APPLIED 卡与重试按钮可见；D-16 关闭 |
| 10 | PASS | `plans/e2e-real-09-10.md`、`plans/evidence/e2e-09-10/e2e10-observe.json`、`e2e10-ack.json` | 真实 shell 在途→kill→重启→UI 确认→结清；请求 109→109 无自动重放 |
| 11 | PASS | `plans/evidence/e2e-11-12/e2e11-c9cbfdf6-final22.json` | final22 25/25 断言全 PASS、`ok:true`、NODE_EXIT=0（隔离实例 `/tmp/hc-w22-e2e11:7622`，18:22–18:30）：suggest-switch-on 关闭（`:memory-suggest?` 置位、整理记忆命令受理执行）、候选审批/拒绝/改范围、归档排除、临时会话一次性读取与自动关闭全 PASS、`frozenGap:null`；双记 `c9cbfdf6`+`1e824c83`；前冻结对照 `e2e11-0fbddd3c-final21.json`（25/25）、`e2e11-168318c1-final19.json`（25/25） |
| 12 | PASS | `plans/evidence/e2e-11-12/e2e12-c9cbfdf6-final22-w121.json`、`e2e12-c9cbfdf6-final22-assertfix.json` | final22 两个独立样本均 48/48 PASS、`ok:true`、NODE_EXIT=0；D-27 关闭、D-28 关闭（`:last-move` 落库、撤销链路端到端 PASS）；世界书断言全过 |
| 13 | PASS | `plans/evidence/e2e-13-15-16-21/e2e13-real-model.json`、`e2e13-generate-failure.json`、`plans/evidence/e2e-13/session-skill-ui.json` | 小应用隔离、技能安装/启用/绑定、真实模型生成 5052 B/124.5 s、真实超时失败、会话技能 A/B 隔离均 PASS |
| 14 | PASS | `/tmp/hc-e2e-harness/e2e14/e2e14.json`、`e2e14-final.png` | 无（D9 已修） |
| 15 | PASS | `plans/evidence/e2e-13-15-16-21/e2e15-v6-regen-f8c0f53a.json` + `.png` | final18 PASS：regen 产生 v2（D-22/history NPE 修复生效）、版本切换后旧朗读 paused=true/currentTime=0/3 s 不继续、切回 v2 可重读、版本按钮更新、pageErrorsZero；实时麦克风为环境限制（无音频输入），不计产品阻断 |
| 16 | PASS（iOS 为 UA 模拟） | `plans/evidence/e2e-13-15-16-21/e2e16.json`、`16-*.png` | 手动运行不改原计划、重叠跳过、时区/错过/恢复暂停、iOS UA 前台手动/启停/默认暂停 PASS；真实 iOS 设备未跑 |
| 17 | PASS | `/tmp/hc-e2e-harness/e2e17/e2e17.json`、`verify-after-merge.log` | 无（D6/重启回执已修） |
| 18 | PASS | `/tmp/hc-e2e-harness/enospc/evidence/e2e18-pass.json` | 无（ENOSPC 文案已修） |
| 19 | PASS | `/tmp/hc-e2e-real/e2e19/report.json`、`ime-enter.png`、`focus-return.png`、`drawer-escape.png`、`zoom-200-*.png` | 屏幕阅读器朗读顺序、真实触屏未跑 |
| 20 | PASS | `plans/e2e-real-08-10-20.md`、`plans/evidence/e2e-real-20/` | 无（discard 10.356 s、transfer 279 ms、记忆/已删除区/重启均 PASS） |
| 21 | PASS | `plans/evidence/e2e-13-15-16-21/e2e21-final.json`、`21-*.png` | 三口径（341/341/未提供 1）、service identity、USD/EUR 分开、改价不改历史 PASS |
| 22 | PASS（手工回归） | `plans/evidence/e2e-regression-01-02-22/e2e22-final.json`、`plans/e2e-regression-01-02-22.md` | 已删除区/连接投影/no-sync 已复跑；新 §8.5 自动化待 D-04 |

结论：**E2E 22/22 全部 PASS（final22；E2E05 final22 42/42、E2E11 final22 25/25、E2E12 final22 48/48×2；D-24/D-25/D-26/D-27/D-28 均关闭）。**
0.0.1 打标前仍须完成 §8.5 自动化（D-04）与其余 DEBT 收口；E2E12 的 D-28 已在 final22 关闭。不得把组件级、store 级或 PARTIAL 证据写成
完整 UI 通过。07 已按 ea9e5732 的 13/13 验收断言升 PASS，来源卡外链点击
（F7 `preventDefault` 后走内部路由）单列为产品缺口，不改变 07 的 PASS。

**2026-11-05 15:22 回归观察（已更新）**：E2E 22 初次回归在 bundle
`9c59f834a1dd49fb3fd085a711eac683f681a52f6e757ebcba5ecb5dd1aa19b6`
上 B 启动失败；W4 修复 `Character → Map$Entry` 与模型投影
`:created-at`/`:model-entry-id`，W5 修复 `ReentrantLock` import 后，
B `app-check` 已 exit 0，B UI 历史可读；已删除区与 no-sync 已复跑。
当前手工回归结论为 **PASS**（已删除区、include-deleted 恢复、no-sync 隔离），见 `plans/e2e-regression-01-02-22.md`；新 §8.5 自动化仍待 W10。
新 §8.5 自动化 + mock 验收未完成，不能据此打 tag。

## 4. 明确 NOT RUN / BLOCKED 项

以下项目没有运行，或运行后仍被阻塞。不得把它们写成通过。

### 4.1 E2E 14、17、18、22 的 findings

这四条已从 BLOCKED 更新为 PASS，但 PASS 不等于无 finding：

- **E2E 14 PASS**：真实 Chrome/CDP 完成观察/接管/终止；D9 浏览器面板已修。
  证据 `/tmp/hc-e2e-harness/e2e14/e2e14.json`、`e2e14-final.png`。
- **E2E 17 PASS**：备份创建、错误密码、合并数据语义通过；D6 与重启回执
  已修。证据 `e2e17/e2e17.json`、`verify-after-merge.log`。
- **E2E 18 PASS**：真实 ENOSPC + 恢复通过；ENOSPC 文案已修。证据
  `enospc/evidence/e2e18-pass.json`。
- **E2E 22 PASS（手工回归）**：B 启动/恢复健康；显式
  `--include-deleted true` 备份/恢复后已删除区可见、`deleted-at` 未延期；
  no-sync 以单 app 存储隔离验证。证据
  `plans/evidence/e2e-regression-01-02-22/e2e22-final.json`、
  `plans/e2e-regression-01-02-22.md`。新 §8.5 自动化仍待 D-04。

### 4.2 性能与客户端规模

- 1,000 会话与 2,000 消息只在 jsdom 中渲染；**真实浏览器滚动、paint、FPS、
  内存未测**。
- 阅读位置恢复：**FAIL**。jsdom 中切走再切回后 `scrollTop` 从 12,345 变为 0；
  当前实现没有按会话保存/恢复 scrollTop。证据
  `plans/evidence/scale-dom/messages.log`、`plans/client-scale-0.0.1.md` §4。
- 会话内“向前查找”：**NOT RUN（无入口）**。DOM 中 find 控件数为 0；现有
  “搜索”是联网搜索，不是会话内查找。
- 消息/会话列表没有虚拟化；2,000 条消息一次性挂载，节点与堆线性增长。
- 没有真实 store 灌入 1,000 会话/2,000 消息的服务端规模基准。

### 4.3 发布形态、平台与硬件

- **签名安装包：NOT RUN/未产出**。0.0.1 只做源码级本地发布。
- **GPU 路径：NOT RUN/未声明**。项目不使用 GPU；OCR 为 CPU Tesseract。
- **远程部署、认证与 TLS：NOT RUN**。默认 loopback-only；远程需要额外认证与
  TLS，不能只改 `--host`。
- **macOS / Windows 运行时：NOT RUN**。本机只验证 linux-64；`osx-arm64`
  没有 conda-forge Clojure 构建，Apple Silicon 走 `osx-64`（Rosetta）或宿主
  Clojure CLI。
- **屏幕阅读器朗读顺序、对比度、真实触屏：NOT RUN**。E2E 19 已覆盖
  IME/焦点/Escape/200% 字号（自动化 DOM/模型层）；屏幕阅读器朗读顺序与真实
  触屏仍未跑。
- **真实硬件（麦克风、扬声器、蓝牙来电、手机真机、360×800 窄视口）：NOT RUN**。
- **真实设备时区切换、受限移动平台：NOT RUN**。
- **两个小应用与普通浏览页的真实 IndexedDB 隔离：NOT RUN**。
- **真实“清晰/倾斜/不可识别”混合扫描件：NOT RUN**。
- **真实联网搜索供应商、断网重看 UI：NOT RUN**。
- **真实云语音、真实模型质量/费用/供应商行为：NOT RUN**。E2E 01/02 已用
  真实本地 Ollama（`127.0.0.1:11434/v1`，`qwen3.6:27b`）复跑；真实云供应商
  的质量、费用与错误行为仍未验证。首轮 08:11 批次的 stub 只属于历史证据。

### 4.4 备份/恢复的已知缺陷与边界

早期离线实跑（`plans/offline-restore-0.0.1.md`）记录了三项发布阻断：

| 编号 | 早期现象 | 当前树复检 |
|---|---|---|
| D1 | CLI 默认备份生成 `:encryption :none` | 已修复：默认备份返回 `:encrypted? true`/`:encryption :aes-gcm`（`/tmp/hc-docs-verify-default.log`） |
| D2 | `--encrypt true`/`--include-secrets true` 报 `expected true or false {:value nil}` | 已修复：`--encrypt true` 退出 0，返回 AES-GCM（`/tmp/hc-docs-verify-enc.log`）；`:include-secrets` 键已映射，未单独复检 |
| D3 | 全新目标恢复报 `BACKUP_COMMAND_EPOCH_WINDOW_MISSING`；`--no-prior-app-commands true` 无效 | 已修复：CLI 自带 `:no-prior-app-commands? true`；全新目录 restore exit 0、`:status :complete`（`/tmp/hc-docs-final-restore.log`） |
| D4 | 重复 replace 重新应用并新增恢复点 | 已修复：同一备份同一类别第二次 replace 返回 `:status :already-imported`，不新增恢复点（`/tmp/hc-docs-final-restore-repeat.log`）；旧“重新应用”记录是修复前历史 |

当前仍存在的边界：

- **CLI restore 可在完全空目录创建 store**，不需要预先 `storage/open-store`。
  本次隔离复测（`/tmp/hc-prep-restore-0.0.1`）：完全空目录 restore exit 0，
  CLI 生成 `db/` 下的 store 文件，回执 `:store-observed? true`、
  `:status :no-changes`（该备份无类别可导入）。旧“完全空目录报
  `STORAGE_NOT_FOUND`”已被本轮复测否定；HISTORY 的旧假设按任务约束未修改，
  读者应以上述复测为准，不要再写成当前限制。
- 重复 `replace` 现在是幂等的 `:already-imported`；只有修复前的
  `plans/offline-restore-0.0.1.md` §3.11 保留“重新应用”的历史记录。
- 备份恢复需要停机窗口；活服务拒绝在线整库恢复。维护恢复走受监督入口，
  客户端以 `:backup/maintenance-status` 终态为准。
- 备份恢复需要停机窗口；活服务拒绝在线整库恢复。维护恢复走受监督入口，
  客户端以 `:backup/maintenance-status` 终态为准。

### 4.5 UI findings 与稳定性风险

- **D6 备份面板本地动作 no-op**：已修；E2E 17 真实浏览器 PASS，重启回执
  已修。
- **D7 停止命令 command-id 冲突**：客户端已改为 `cmd-<random-uuid>`，服务端
  停止/迟到文本路径 PASS；UI 仍只渲染首个 chunk、不显示字面“正在停止/已停止”，
  这是客户端流式投影缺口，不是服务端状态丢失。
- **D8 停用唯一模型连接后的提示**：仍是通用 `no model port is configured`，
  没有引导用户选择替代连接。
- **D9 受管浏览器启用流程**：已修；E2E 14 真实 Chrome/CDP PASS。
- **D-12 手动模型绑定 `:tools`**：仍开放；声明的 `:tools` 未进入 binding，
  E2E01 自动化 33.8 s FAIL（`MODEL_TOOLS_UNAVAILABLE`，未产生用户消息）；
  D-AUTO-02 已并入 D-12，不单独计条。
- **E2E 18 ENOSPC UI 文案**：真实 ENOSPC + 恢复 PASS；ENOSPC 文案已修。
- **E2E 21 诊断页/service identity**：崩溃与 service identity 已修；三口径/
  价格 UI 已在真实 Chrome 复跑 PASS（`e2e21-final.json`），E2E 21 升 PASS。
- **E2E 13 扩展页崩溃**：React keyword child 崩溃已修；真实 Ollama 生成
  5052 B/124.5 s、真实超时失败仍可查看已有文件、会话技能 A/B 隔离均 PASS，
  E2E 13 升 PASS。E2E 11 的临时一次性只读清除仍被 bundle 阻断，保持
  BLOCKED；E2E 12 上下文面板已实现，多轮计数/冷却/预算未跑，保持 PARTIAL。
- **E2E 04/05/06/09/11/15/16**：04 编辑/分支/导出/重启均 PASS；05 压缩
  预览/编辑/使用/摘要不升级/原消息可读 PASS，继续两轮/outbound 去重/
  撤销压缩已在 final20 复跑全 PASS（D-17/D-22/D-24/D-25 均关闭，41/42，
  唯一 FAIL 为脚本正则伪阴性）；06 附件与部分读取
  PASS；09 受保护提交/冲突卡/stamp/重试/窄视口在最终冻结
  `072587a4`/`6aa694f1` 上全部 PASS（D-16 已关闭）；11 保持 BLOCKED；15 文件路径转写/朗读停止/版本
  切换 PASS（前冻结 `5addfe1d`，未在 `ea9e5732` 重绑），实时麦克风
  BLOCKED，保持 PARTIAL；16 定时任务/时区/平台 PASS（iOS 为 UA 模拟）。
- **9 个 JVM `SIGSEGV` 崩溃日志**：`hs_err_pid2905205.log`、
  `hs_err_pid3003787.log`、`hs_err_pid3597664.log`、`hs_err_pid455536.log`
  （2026-11-04 01:33 至 2026-11-05 00:20），以及新增的
  `hs_err_pid1928617.log`（2026-11-05 12:07）、`hs_err_pid3566148.log`
  （2026-11-05 14:23）、`hs_err_pid3871452.log`（15:18）、
  `hs_err_pid4054882.log`（16:52）、`hs_err_pid4063480.log`（16:53）。崩溃
  根因是 Datalevin 读/store 关闭竞争；W1/W11 已修 `shutdownNow` +
  `awaitTermination` + publish-lock，D-06 的 20 轮/180 assertions、
  SIGTERM 5/5、`hs_err` 9→9 已通过；若最终复验新增则重开 D-06。
- **全量测试与 app-check 的最终树**：final6 在 `7667323a` / `0c05b7d1` 上
  **PASS**：3749 tests / 24310 assertions / 0F / 0E / exit 0 / 365 s
  （13:38:43–13:44:48；PRE=POST 单树、`hs_err` 9→9、峰值 1.93 GiB；
  13:45:29 复核）。**全量门禁条件 5 满足。** 历史：`52547df2` 的
  3748/24306/0F 随 D-17 失效；`a4518891` final4 为 3749/24310/1F
  （scheduler `[0 0]`，非 D-17）；final5 的 0F 因 13:29:02 `main.cljs`
  写入属混合树，不可作单树基线。app-check 仍 exit 0 / `:ok true` / 0.0.1。
  任何后续 `src/test` 写入都会使该基线失效。

## 4.6 未挂载/未交付的 UI 与功能缺口（8 条）

1. **迷你地图/话题概览未挂载**：纯逻辑
   `src/hc/client/conversation/minimap.cljc` 已实现 `overview`/`locate`/
   `nearest-anchor`，但 `src/hc/client/conversation/view.cljs` 没有 require
   或渲染入口；用户看不到话题概览/迷你地图。接线说明见
   `plans/ui-contract-audit-0.0.1.md` §8.3。
2. **长图导出与导出取消未挂载**：
   `src/hc/client/conversation/export_plan.cljc` 已实现选择、取消状态机与
   分段计划，但导出视图仍走现有 Markdown/纯文本下载，没有调用
   `export-plan`，也没有取消按钮。接线说明见
   `plans/ui-contract-audit-0.0.1.md` §8.2；`conversation/view.cljs` 的导出
   区没有长图/取消入口。
3. **24 类失败文案仍有缺口**：`src/hc/client/copy/failures.cljc` 提供单一
   来源，但当前 10 类符合、13 类偏差、1 类（额度不足）缺 UI 接线。13 项
   补丁清单见 `plans/ui-contract-audit-0.0.1.md` §9；不得写“全部覆盖”。
4. **holder shell / `$W$` 手动操作面缺失**：工作台没有用户可见的 holder
   shell/运行命令入口；`src/hc/client/workbench/views/shell.cljs` 只是应用
   外壳。E2E 10 已用真实 shell 与 UI 核对入口复跑 PASS，该缺口不再作为
   E2E 10 的阻断，见 `plans/e2e-real-09-10.md`。
5. **移交执行层按钮缺失**：服务端里程碑/执行层入口尚未发布，UI 没有
   “移交/运行 worker 程序”按钮；E2E 09 的受保护提交/冲突/重试/窄视口
   已在最终冻结 `072587a4`/`6aa694f1` 上 PASS（D-16 关闭），但移交执行层
   入口本身仍缺，见 `plans/e2e-real-07-09.md`。Plan 程序只能写 `plans/<name>.md` 草稿
   （`src/hc/server/app/prompts.clj:245-260`），不能写工作区。
6. **实时麦克风 BLOCKED**：环境无音频输入（fake device/WAV/虚拟设备均
   0 B）；E2E 15 用音频文件路径覆盖转写→草稿→发送与 TTS（8044 B），
   版本切换在前冻结 `5addfe1d` 上 PASS、未在 `ea9e5732` 重绑，故保持
   PARTIAL。证据 `plans/evidence/e2e-13-15-16-21/e2e15-version-switch*.json`。
7. **小应用打开崩溃 / release `react is not defined` 待复验**：旧真实
   Chrome 复跑仍出现 `ReferenceError: react is not defined`，小应用面板
   不可用；源码已在 `src/hc/client/search/view.cljs:7` 与
   `src/hc/client/swarm/ui.cljs:24` 补 `["react" :as react]`。2026-11-05
   当前 bundle（SHA-256 `9a304c02…ecf2a6`）在 `/`、`/#/materials`、
   `/#/settings` 加载无 pageerror/console error；但小应用打开/生成后的页面
   尚未复验。**不得写“已修复”**，只能写“首页/材料/设置已加载，小应用页待复验”。
   证据 `plans/evidence/bundle-health/`、`plans/e2e-real-07-11-12-13.md`
   E2E11/13、`plans/evidence/e2e-real-11/phaseC.log`。
8. **本地 27B 视觉/长文本超时属环境限制**：本机 Ollama `qwen3.6:27b`
   CPU 推理下，E2E 06 视觉答案 >180 s 超时，E2E 03 长文本被 Plan 工具
   路径阻断；真实模型行为受本机性能/端点影响，不是发布运行时承诺。
   证据 `plans/e2e-real-05-06.md`、`plans/e2e-real-04-05-06-19.md`、
   `/tmp/hc-e2e-real/e2e03-result.json`。

## 5. 已知边界

- **loopback-only**：默认只监听 `127.0.0.1`，并做同源 Origin/Host 检查；
  这不是远程多用户安全模型。远程部署必须提供认证与 TLS。
- **备份需要停机窗口**：活服务拒绝在线整库恢复；离线恢复走
  `hc.server.backup.cli` 或受监督的维护恢复入口。备份/恢复是保护手段，
  不构成“数据绝不丢失”的承诺；缺失附件、完全空目录恢复等边界见 §4.4。
- **客户端构建顺序**：必须先 `pixi run client-install`（`npm ci`），再
  `pixi run client-release`；Pixi 不自动管理 npm 依赖。
- **`osx-arm64` 限制**：conda-forge 没有该平台的 Clojure 构建；
  `pixi.toml` 只声明 `linux-64`、`osx-64`、`win-64`。Apple Silicon 走
  `osx-64`（Rosetta）或宿主 Clojure CLI。
- **Pixi 引导**：宿主 `PATH` 没有 `pixi`；本次使用 `/tmp/pixi` 0.83.0。
  二进制大小 71,427,392 bytes，SHA-256
  `50571451e96f4a82cf675cd83baaf66fff9b718f0aaf153900b9faf4c29bd401`。
  `/tmp` 被清理后需要重新取得并核对校验和。
- **Playwright/Chrome**：仓库内 `require('playwright')` 不可解析；E2E 用
  `/tmp/hc-pw/node_modules` + `NODE_PATH` 加载 Playwright 1.64.1。Chrome 为
  `/usr/bin/google-chrome` 153.0.8010.36。`test/js/verify_browser_ui.cjs`
  硬编码该路径，不读 `HC_CHROME`；换机需临时副本。
- **测试入口**：`pixi run test` 使用 `-X:test:compose:server:client`。
  `-M:test` 单独使用缺少 compose/server/client 别名，会报
  `Could not locate missionary/core...`；这是别名集差异，不是产品缺陷。
- **没有虚拟化**：1,000 会话与 2,000 消息列表全量挂载；真实浏览器
  paint/内存未测，节点数线性增长是明确风险。
- **阅读位置与会话内查找**：按会话恢复 scrollTop 未实现；会话内 find 没有
  入口。长会话导航仍不完整。
- **重复 replace 恢复**：同一备份同一类别第二次 replace 返回
  `:already-imported`，不新增恢复点（server-audit 已修）；修复前“重新应用”
  记录只作为历史。
- **CLI restore 目标**：全新目录（包括完全空目录）restore 已验证 exit 0；
  CLI 自带 `:no-prior-app-commands? true`，并在需要时创建 store。本次空备份
  复测回执为 `:store-observed? true`、`:status :no-changes`；非空备份的
  `:status :complete` 仍引用旧证据。
- **版本源**：`src/hc/version.cljc` 是唯一运行时源；`pixi.toml`、
  `package.json`、`--help`、`/healthz` 必须同值。改版本时不要只改清单。
- **文档布局**：项目不设根目录 `README.md`/`LEMMA.md`；运行入口在
  `RUNNING.md`，实测限制与复检命令在 `HACK.md`，历史取舍在 `HISTORY.md`。
- **单机单例**：每机器一个应用实例；第二个实例向首实例发消息，不是多主
  部署。
- **资源上限**：`deps.edn` 的 app/test 别名统一 `-Xmx1g` +
  `-XX:MaxDirectMemorySize=512m`；测试子 JVM 512m/256m；E2E app 768m/512m。
  同一时刻最多 1 个重资源 app JVM + 1 个 Chrome，全量测试与 E2E 串行。
  OOM 根因与修复见 `plans/oom-root-cause-0.0.1.md`。

## 6. 发布前必须完成

1. **提交产品与文档**：本稿生成时 `git ls-files` 只有 `AGENTS.md`；`src/`、`test/`、
   `resources/`、构建清单、`.gitignore`、`RUNNING.md`、`HACK.md`、
   `HISTORY.md`、`plans/` 全部未跟踪。发布条件 6 仍 FAIL。
2. **最终树重跑**：`pixi run test`、`pixi run app-check`、`pixi run client-release`
   与真实启动冒烟；把测试/断言数、seed、退出码写回本文件。
3. **重跑 E2E 03/04/05/06/07/08/09/10/11/12/13/15/16/21**，并复验 D7
   的真实点击停止路径；01/02/14/17/18/19/20/22 已 PASS。
4. **在最终树复验备份 CLI**：默认加密、`--encrypt true`、无 app gate 的新
   store 恢复、错误密码、merge 幂等、replace 重复行为；对“完全空目录恢复”
  记录为“CLI 可创建 store”（本次隔离复测已证）；非空备份的 `:status
  :complete` 仍需在最终树复验。
5. **处理稳定性证据**：确认 9 个 `hs_err_pid*.log` 的来源；不要只删除日志。
6. **确认测试产物忽略**：`plans/evidence/*/chrome-profile/` 已由
   `.gitignore` 的 `chrome-profile/` 与 `**/chrome-profile/` 忽略；提交前用
   `git status` 确认没有 profile 文件混入。
7. **最后才打标**：只有上述完成、且 `plans/docs-version-audit-0.0.1.md` 的
   残留不一致清单清零后，才建立发布提交与 tag。

## 7. 复现命令与证据索引

```bash
# 版本
cat src/hc/version.cljc
grep '^version' pixi.toml
grep '"version"' package.json
/tmp/pixi run clojure -M:server -m hc.server.app --help
/tmp/pixi run app-check

# 发布门禁
/tmp/pixi install --locked
/tmp/pixi run client-install
/tmp/pixi run client-release
/tmp/pixi run app-check
/tmp/pixi run test

# 真实启动冒烟
/tmp/pixi run clojure -M:server -m hc.server.app \
  --host 127.0.0.1 --port 0 \
  --data-dir /tmp/hc-baseline-data \
  --workspace-root /tmp/hc-baseline-work \
  --static-root resources/public
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:<实际端口>/healthz
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:<实际端口>/

# E2E
RUN=/tmp/hc-e2e/20261105-0811-exec
cat "$RUN/env/probe.log"
cat "$RUN/env/playwright.log"
# 各条命令见 plans/e2e-0.0.1-results.md §6

# 备份 CLI 当前树复检
HC_BACKUP_PASSWORD='docs-verify-pass-123' /tmp/pixi run clojure -M:server \
  -m hc.server.backup.cli backup \
  --db-path /tmp/hc-docs-verify/db --data-dir /tmp/hc-docs-verify \
  --target-root /tmp/hc-docs-verify/backups \
  --recovery-root /tmp/hc-docs-verify/recovery \
  --extensions-root /tmp/hc-docs-verify/extensions \
  --blob-root /tmp/hc-docs-verify/blobs \
  --workspace-store-root /tmp/hc-docs-verify/workspaces \
  --now 2026-11-05T00:00:00Z

# 显式加密（同一组路径参数，另给 target-root 与 --encrypt true）
HC_BACKUP_PASSWORD='docs-verify-pass-123' /tmp/pixi run clojure -M:server \
  -m hc.server.backup.cli backup \
  --db-path /tmp/hc-docs-verify/db --data-dir /tmp/hc-docs-verify \
  --target-root /tmp/hc-docs-verify/backups-enc \
  --recovery-root /tmp/hc-docs-verify/recovery-enc \
  --extensions-root /tmp/hc-docs-verify/extensions \
  --blob-root /tmp/hc-docs-verify/blobs \
  --workspace-store-root /tmp/hc-docs-verify/workspaces \
  --encrypt true --now 2026-11-05T00:00:00Z

# 建一个无 app gate 的新 store（仅验证用）
/tmp/pixi run clojure -M:server -e '(require (quote [hc.server.storage :as storage])) (let [s (storage/open-store {:path "/tmp/hc-docs-verify/target4/db" :create? true})] (println (storage/store-info s)) (storage/close-store! s))'

# 恢复到新 store
HC_BACKUP_PASSWORD='docs-verify-pass-123' /tmp/pixi run clojure -M:server \
  -m hc.server.backup.cli restore \
  --db-path /tmp/hc-docs-verify/target4/db \
  --data-dir /tmp/hc-docs-verify/target4 \
  --target-root /tmp/hc-docs-verify/backups-enc \
  --recovery-root /tmp/hc-docs-verify/target4/recovery \
  --extensions-root /tmp/hc-docs-verify/target4/extensions \
  --blob-root /tmp/hc-docs-verify/target4/blobs \
  --workspace-store-root /tmp/hc-docs-verify/target4/workspaces \
  --backup-id <backup-id> --mode replace

# 客户端规模
HC_SCALE_SESSIONS=1000 HC_SCALE_MESSAGES=0 bash test/js/verify_workbench_scale_dom.sh
HC_SCALE_SESSIONS=2 HC_SCALE_MESSAGES=2000 bash test/js/verify_workbench_scale_dom.sh
node test/js/no_model_probe.cjs --url 'http://127.0.0.1:7581/#/sessions' --out plans/evidence/no-model
node test/js/scale_draft_probe.cjs --url 'http://127.0.0.1:7581/#/sessions' --out plans/evidence/scale-draft
```

证据文件：

- `plans/release-0.0.1-baseline.md`：G1–G8 门禁、全量测试、启动冒烟、版本一致性。
- `plans/e2e-0.0.1-results.md`：E2E 01–22 逐条结果、缺陷 D1–D10、复现命令。
- `plans/e2e-0.0.1-runbook.md`：E2E 前置条件与未覆盖项。
- `plans/e2e-real-14-17-18-22.md`、`plans/e2e-real-04-05-06-19.md`、
  `plans/e2e-real-07-11-12-13.md`、`plans/e2e-real-16-21.md`、
  `plans/e2e-real-08-10-20.md`：最新真实端点/Chrome 复跑与证据路径。
- `plans/offline-restore-0.0.1.md`：离线备份/恢复早期实跑与 D1–D4。
- `plans/client-scale-0.0.1.md`：无模型门槛、jsdom 规模、两万字草稿、阅读位置/查找缺陷。
- `plans/evidence/scale-dom/*.log` 已通过 `.gitignore` 的否定规则
  `!plans/evidence/**/*.log` 变为可跟踪，可随提交复核；
  `plans/evidence/*/chrome-profile/` 是浏览器缓存，已忽略。
- `HACK.md` §9–§14：网关、CDP、OCR、E2E stub、Playwright、ENOSPC、备份 CLI、Pixi 校验和、Ollama/浏览器/ENOSPC 实测。
- `/tmp/final-full-test3.log`、`/tmp/final-app-check4.log`、`/tmp/docs-version-help.log`、
  `/tmp/docs-version-app-check.log`、`/tmp/hc-docs-verify-*.log`、
  `/tmp/hc-docs-final-restore*.log`、`/tmp/hc-docs-final-nopassword.log`。
