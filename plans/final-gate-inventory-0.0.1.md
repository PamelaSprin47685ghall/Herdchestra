# 0.0.1 最终门禁预演清单

## final28 冻结（2026-11-08，v0.0.2 候选）

- **最终冻结**：bundle `fd5e1f9003535d9e4dfed2bf358a8055376c233786238c293c40671babd63d39`（3,089,772 B，2026-11-08 22:19:01；CSS `e06ece29…`；manifest `e0ef5e1b…`）+ 源码树 `77d92bc2`（PRE=POST，1060 files）。
- **全量**：3929 tests / 25571 assertions / 0F / 0E / exit 0（`/tmp/hc-full-test-final28.log`）；app-check `:ok true` / `0.0.1`（`/tmp/hc-app-check-final28.log`）；meta `plans/evidence/final28-freeze-meta.json`。
- **末尾修复**：W280–W286（message->items 携带 attachments/quotes/citations、`:plain-response`/`:tools?` 三态、sente dispatcher+closeRingHandler、composer-snapshot 文本竞态、publish-run-events! 首见基线）。
- **历史**：final27b（`92d18d8f`+`43431535`）、final26/27 及更旧作废；E2E/视觉证据双记 `fd5e1f90` + `77d92bc2`。
- **commit subject 口径说明**：commit subject 使用 en_US.utf8 排序得到 `956136d0`；规范口径为 `LC_ALL=C` 排序下的 `77d92bc2`（1060 files）；二者实测为同一棵树（首个排序分歧：`appearance.cljs` vs `appearance/AGENTS.md`，`.`=0x2E < `/`=0x2F）。
- **git 权威值**：annotated tag **v0.0.2**，tag message「Herdchestra 0.0.2 — final28 bundle fd5e1f90 (3,089,772 B) / tree 77d92bc2 (1060 files); 3929 tests 25571 assertions 0F/0E; app-check :ok true 0.0.1; E2E 22/22 PASS」；`git status` 0、未 push。台账内 git 标识为发布时快照；权威以 `git rev-parse HEAD` 与 `git rev-parse 'v0.0.2^{}'` 为准；树 hash 口径 = `find src test -type f | LC_ALL=C sort | xargs sha256sum | sha256sum`（LC_ALL=C 按字节序）。`76ceb3f`/`c814e69`（并发中途）已作废。

### final28 前收口（2026-11-08）

- **E2E 22/22 全部 PASS**：E2E04 42/42 checks（3 连 PASS）、E2E11 30/30；E2E04 根因 publisher 首见 session 把分支复制的 3 条 assistant 全文当流式 delta 广播 → 客户端 `:streaming? true` → composer `:needs-decision` → 第二轮永不发出；修复 `publish-run-events!` 首见基线。
- **D-29 证伪**（teardown SIGTERM，非中途自退）并删除；**D-30 修复**（runtime message->items 携带 attachments/quotes/citations + composer-snapshot 文本竞态）并删除。
- **final28**：W287 clean release rebuild + 全量 + app-check 进行中；之后重冻结并准备 commit/tag **v0.0.2**。
- **DEBT 统计**：开放 12 条。

- 日期：2026-11-06 10:10 CST；11:30 更新 DEBT 对账（D-09 关闭、D-12 开放、master 14/6/2）；12:18 W5 重建 `8b210896` 并成为最终冻结（替换 `55e4e8ea`、`dd968793`）；12:00 新增 D-15（text/plain 附件）；12:10 D-15 代码/单测已修（W8），待 UI 回归；12:26 **最终冻结树全量 PASS**（3746/24286/0F/0E/exit 0/365 s，PRE=POST `3fae6313`），app-check 与版本五处复验通过（W35）；**13:02 冻结失效告警：13:00–13:01 的 4 个 src 改动不在 `8b210896`；13:03 W33 宣布 `ea9e5732`（13:02:42，3,010,646 B；CSS `332f9fd3` 未变）为新最终冻结；W35 13:04:16–13:10:21 全量 PASS（3748/24306/0F/0E/exit 0/365 s，单树 `52547df2`）；13:14:32 D-17 修复后 `52547df2` 基线失效；13:29:02 `main.cljs` 写入使 final5 成混合树；13:37:25 W5 重建并 W33 宣布新冻结 `0c05b7d1`（3,011,096 B；CSS `332f9fd3`；src/test `7667323a`），`ea9e5732` 作废；W35 final6 13:39–13:52 运行中。`8b210896`/`3fae6313` 作废。**
- 范围：当前未关闭 DEBT、发布阻断、门禁命令与预计可跑时间。
- **final7 结果（14:00:12）**：树 `a97c68f7` + bundle `072587a4` 单树全量 **PASS**：3749 tests / 24310 assertions / 0F / 0E / exit 0 / 380 s（13:53:52–14:00:12；PRE=POST、`hs_err` 9、bundle 未变、峰值 1.97 GiB、354 ns）；app-check exit 0/16 s/`:ok true`/0.0.1。final6（`0c05b7d1`/`7667323a`）仅历史；final4 1F、final5 混合树亦仅历史。
- **final14 结果（04:40:42，现历史）**：**曾冻结 bundle `732baf05…`（3,015,403 B，2026-11-07 04:33:20；CSS `332f9fd3`）+ 源码树 `79f21de9…`（已被 `a14d9abd`+`651b9a45` 取代）** 单树全量 **PASS**：**3805 tests / 24650 assertions / 0F / 0E / exit 0 / 352.10 s**（04:34:50–04:40:42；PRE=POST 树 `79f21de9`、git 2025、`hs_err` 9、bundle 未变；04:31:34 后无 src/test 写入；`/tmp/hc-full-test-final14.log`、`-meta.txt`；seed 171、356 ns）；**本轮 VmHWM 峰值未采样**；app-check exit 0/14–15 s/`:ok true`/`0.0.1`/`:component-errors {}`；版本五处 0.0.1。**当时全量门禁条件 5 满足**；final13（`72de4f9a`+`36fddd6d`）与 `2b269e0d` 作废；D-23 仍待 E2E12 验证；证据双记 `732baf05` + `79f21de9`。
- **final15 结果（06:01，现历史）**：bundle `a14d9abd`（3,015,902 B，05:50:40）+ 树 `651b9a45` 单树全量 **PASS**：3808/24669/0F/0E/356 s/峰值 2.05 GiB；app-check `:ok true`。final14 及更早作废。
- **final16 最终冻结（W33/W91，final16b 复核 PASS）**：bundle `efbc465a`（3,016,559 B，08:43:18；CSS `332f9fd3` 未变）+ 源码树 `99aa81a4`（PRE `b9570d04`→POST，差异仅 `run_port_test.clj` 两处定界符修复，修复前该文件 reader 报错阻断整套件；git porcelain 16、`hs_err` 9）；全量 **3813 tests / 24695 assertions / 0F / 0E / exit 0 / 353.44 s**（运行窗 08:52:45–08:58:38）；app-check exit 0/`0.0.1`/`:component-errors {}`/store open；版本五处 0.0.1；meta `plans/evidence/final16-freeze-meta.json` + `plans/evidence/final16b-freeze-meta.json`。**全量门禁条件 5 满足，final16b 同树补跑 3813/24695/0F/0E/355 s 一致、冻结成立**。**复跑目标：E2E 复跑以 bundle `efbc465a` + 树 `99aa81a4` 为准；证据须同时记录两者；旧 hash（含 final15 `a14d9abd`+`651b9a45`、final14 `732baf05`+`79f21de9`）一律前冻结；D-22/D-23 已由 W76 final15 复跑确认，D-24/D-25/D-26 待 final17 E2E 复跑确认后关闭。 **冻结暂停（final17→final18 过渡）**：W99 已落地 D-26 修复（`model.cljc` `:submitted` 非终态/`:not-found`→`:refused`；`materials_bridge` 5s 独立清扫 + dispatch 后清扫；CLJS 4/12 0F/0E；JVM 测试待 W100），final17 树漂移至 `0a62fd2c`，**final17 冻结暂停**；W100 重建 bundle → final18 单树全量 + app-check 后重冻结。E2E05/11/12 等 final18 基线复跑；在此之前 `efbc465a`+`99aa81a4` 证据仅作诊断。**最终冻结（final18，W33/W100）**：bundle `f8c0f53a`（`f8c0f53acc14f237d0a76e44f12e9605a5c472542c4306f54e1439c01c26842a`，3,017,311 B，11:21:45；CSS `332f9fd3` 未变）+ 源码树 `6d1ad03b`（120 s 稳定，PRE=POST）；全量 **3816 tests / 24705 assertions / 0F / 0E / exit 0 / 355 s**；app-check `:ok true`/0.0.1/`:component-errors {}`。D-26 sweep 已入产物（`setInterval(h_b,5E3)` + `install!` receipt-sweep-timer；bundle 内 setInterval 4 处）。final17 及更旧作废。**复跑目标：E2E 复跑以 bundle `f8c0f53a` + 树 `6d1ad03b` 为准；证据双记两者；D-24/D-25/D-26/D-27/D-28 待 final19 E2E 复跑确认后关闭。**final18 作废 → final19 准备中**：W106 已完成 bug2/D-28 修复（`set-materials-state` stale 丢弃 receipt → move 卡 `:submitting`；修复 `merge-local-state` + orphan drain + 5s sweep），bug1/D-27 验证通过；相关测试 106/783 + 10/67 0F/0E。W107 跑 final19 全量 + app-check 后重冻结；E2E 等 final19 基线复跑，`f8c0f53a`+`6d1ad03b` 证据仅作诊断。**final19 最终冻结（W33/W107）**：bundle `168318c1`（3,017,514 B，12:42:52；CSS `332f9fd3` 未变）+ 源码树 `f8477191`（PRE=POST）；全量 **3818 tests / 24714 assertions / 0F / 0E / exit 0**；app-check `:ok true`/`0.0.1`/`:component-errors {}`；final18 及更早作废。**E2E/视觉证据双记 `168318c1` + `f8477191`；E2E 复跑以该对 hash 为准。**并发策略变更（用户指令）**：不再要求全局单例锁/单执行者；多 worktree/实例可并发跑测试与 E2E（独立 data-dir/profile/端口互不干扰）；E2E05/06/11/12/15 可并行；此前单执行者/锁窗口安排作废。证据仍双记 bundle `168318c1` + 树 `f8477191`（final19 最终冻结）。**final19 作废 → final20 准备中**：D-25 根因已由 W113 修复（cached-entry 键 `[nil epoch]` 致 `:context/compressions` 永久 `:applied`、delta 缺失；session-summary 加 `:revision`，测试 394/2761 0F/0E）；W110 修 D-27（draft turn-selection）与 D-28（last-move assoc nil）。树已变（14:18–15:11 客户端/服务端/测试），bundle `20f3b90f`（14:12:20）落后。W114 重建 → final20 全量 + app-check 后重冻结；E2E05/12 等 final20 基线复跑；冻结窗口内停写 src/test、停重建、停 E2E。**最终冻结（final20，W33/W114）**：bundle `87b0dee4cec24e7ffc720c96f25bafcb45fd73ca91b6444a030ed51e0fe6da94`（3,018,665 B，15:26:31；CSS `332f9fd3`、manifest `c2158f6b` 未变）+ 源码树 `5e301ce4`（PRE=POST 120 s 稳定）；全量 **3823 tests / 24731 assertions / 0F / 0E / exit 0 / 354 s**；app-check `:ok true`/0.0.1/`:component-errors {}`；标记核对通过；**D-25/D-27/D-28 修复已入产物**；swarm_ui 2 断言失败为既有。final19 及更早作废。**E2E/视觉证据双记 `87b0dee4` + `5e301ce4`**；E2E 复跑以该对 hash 为准，E2E05/12 待复跑关闭 D-25/D-27/D-28。**final20 作废 → final21 准备中**：D-28 根因=`unwrap-receipt` 对网关信封 `{:ok? :command-id :receipt {..}}` 拒绝解包 → `receipt-result` 只读外层 `:result=nil` → `:undo`/`:last-move` 缺失；修复（W117）=任何 `map? :receipt` 解包+merge+外层回填；测试 3824/24735 0F/0E。final20（`87b0dee4`+`5e301ce4`）作废转历史；W118 clean rebuild → final21 全量 + app-check 后重冻结；E2E12 等 final21 基线复跑（D-25/D-27/D-28 关闭确认）。**
- **final16b 复核 PASS（W92）**：PRE=POST `99aa81a4` 同树补跑全量 **3813/24695/0F/0E/exit 0/355 s**，app-check `:ok true`/0.0.1/`:component-errors {}`/store open；bundle/CSS 未变，git porcelain 16，`hs_err` 9；日志 `/tmp/hc-full-test-final16b.log`、meta `plans/evidence/final16b-freeze-meta.json`。**final16 冻结成立**，E2E 证据双记 `efbc465a` + `99aa81a4` 生效。
- 约束：W1 未跑全量测试；W13 的混合树全量结果按原样记录；未执行 Git 写操作；未改根 `AGENTS.md`；未改源码。

## 0. 当前环境与完整性

| 项 | 结果 |
|---|---|
| `/tmp/pixi` | **临时包装已恢复**：`pixi 0.83.0 (temporary wrapper)`；`run clojure/node/npm` 转发仓库 `.pixi/envs/default/bin`，`run test/app-check/client-*` 已映射；`install --locked` 为 no-op。真实 pixi 二进制仍缺失，最终门禁前建议恢复 |
| `/tmp/hc-pw` | **模块已恢复**：`/tmp/hc-pw/node_modules` 指向仓库 `node_modules`；Playwright 1.64.1 可 require。默认 chromium-1248 不在 `~/.cache/ms-playwright`，使用系统 Chrome 153（`/usr/bin/google-chrome`）或 `CHROME_PATH` |
| `/tmp/hc-e2e-harness` | **仍缺失**（无备份）：`run_app.sh`/`stop_app.sh`/`e2e_driver.cjs`/`endpoint.env` 丢失；手工 E2E 改用仓库 `.pixi/envs/default/bin/clojure` + `test/e2e` Playwright + 系统 Chrome，不再依赖该目录 |
| 锁文件 / 模型端点 | `/tmp/hc-client-release.lock`、`/tmp/hc-e2e-model.lock` 已重建；Ollama 已启动（127.0.0.1:11434，context 16384、NUM_PARALLEL=1，qwen3.6:27b）；18082 代理脚本丢失，直连 11434 |
| 命名空间加载 | **PASS**：`hc.server.app.system`、`hc.server.app`、`hc.server.storage`、`hc.compose.owner`、`hc.server.history`，exit 0，21 s |
| `app-check` | **PASS**：exit 0；`:ok true`、`:version "0.0.1"`、`:component-errors {}`、store `:open? true`；29 s（10:10 轮）/ 16 s（11:54 轮）/ 14 s（12:26 轮，`8b210896`）/ **15 s（13:10 轮，最终冻结树 `ea9e5732`）** |
| `hc.server.app.system-test` | **PASS**：17 tests / 105 assertions，0F/0E，26 s |
| `hc.server.storage*` | **PASS**：46 tests / 189 assertions，0F/0E，97 s |
| `hs_err` | **9 → 9**，无新增；最新仍是 11-05 16:53 的 `hs_err_pid4063480.log` |
| **前冻结 bundle（`0c05b7d1` / `7667323a`，已作废）** | `main.js` **3,011,096 B，mtime 2026-11-06 13:37:25，sha256 `0c05b7d1a42123adb91bf7cdb9eb671b0682a72b155ddaf805c3f84b4e68a0bc`**；CSS `332f9fd3…`；src/test 树 `7667323a…`；final6 曾 PASS 3749/24310/0F/0E/exit 0/365 s（13:38:43–13:44:48）。**13:51:04 `main.cljs` 改动后失效，现冻结为 `072587a4`/`a97c68f7`（见下）**；其上的 E2E/视觉证据一律标前冻结 |
| final6 窗口 | **13:39–13:52**：W35 在 `0c05b7d1 / 7667323a` 上跑单树全量，**PASS 3749/24310/0F/0E/exit 0/365 s**（13:38:43–13:44:48，PRE=POST、`hs_err` 9→9、峰值 1.93 GiB，13:45:29 复核）；窗口内不写 `src/**`/`test/**`、不重建、不跑 E2E。final5 的 3749/24310/0F/0E 仅记混合树（13:29:02 `main.cljs` 写入使 PRE `da2f92d4` → POST `7667323a`） |
| **前冻结 bundle（`072587a4` / `6aa694f1`，已作废；final10 历史基线）** | `main.js` **`072587a4690b6953bae9cae3a06eaaa815a38b13eee9b98dc5ce98e72a54824f`**（3,011,162 B，13:51:04；CSS `332f9fd3` 未变；源码树 **`a97c68f7`**）。W33 核对：`__HC_DB__` 1（sessionIds/workerPrograms/activeId/dashboard 已进构建）、D-08 四标记各 1、AbortController 19、hc-markdown 1、hc-message-stream 2、W18/W19、B14/B15、speech 31、hc-protected-program 14；bundle 与源码同树（W35/W37 独立复核）。**W35 final7 单树全量 PASS**：3749/24310/0F/0E/exit 0/380 s（13:53:52–14:00:12，PRE=POST，峰值 1.97 GiB）；app-check exit 0/16 s/`:ok true`/0.0.1。**0c05b7d1/ea9e5732 及更旧作废**；W5 不得再写 src/test 或重建；若再变本冻结立即失效 | 所有 E2E/视觉证据绑 `072587a4`，旧证据标前冻结；**全量门禁条件 5 满足** |
| **前冻结 bundle（`ea9e5732`，已作废）** | `resources/public/js/main.js` **3,010,646 B，mtime 2026-11-06 13:02:42，sha256 `ea9e5732656e79e738883ba384b1a62b4fe9461d396b83612ad4624dd81a39c2`**；含 13:00–13:01 的 client `conversation/export.cljc` 13:00:55、`conversation/view.cljs` 13:01:43 与 server `history/context.clj` 13:01:51、`app/system.clj` 13:01:51 修复；D-08 四标记、AbortController、hc-markdown/hc-message-stream、W17/W18/W19、B14/B15、speech 与 `hc-protected-program` 标记齐全（W33 13:03 只读复核）。`workbench.css` `332f9fd3f3e759f5ef215dbe1ff9307e774d7f56b262d0ce9839d861e8609c4b`（48,202 B，11:48:17，未变）。新树 src/test hash `52547df287160567db0ecd7fdcd1f948c88aa6c965763b2f3ebb38f51fc4da9b`；W35 13:04:16–13:10:21 全量曾 **3748/24306/0F/0E/exit 0/365 s**（单树），但 13:29:02 `main.cljs` 写入使其失效。**`ea9e5732` 已作废，现冻结为 `0c05b7d1`（见上）；`8b210896`、`55e4e8ea`、`dd968793` 作废；其他人不要再重建**。证据边界：D-08/D-09/E2E15/E2E05/视觉在 `8b210896`/`55e4e8ea` 的证据降为**前冻结**，待 `0c05b7d1` 复跑；E2E13 真实模型生成仍为旧 hash `5addfe1d`（功能源码未变，已标注） |
| 冻结后证据重绑（13:12–13:46） | **D-08/D-09 已在 `0c05b7d1` 最终复跑 PASS**：D-09 branch `[model-b]`/source `[model-a]`/`B-REPLY`/epoch_error=false（`branch-model-switch.json` final_rerun=`0c05b7d1`）；D-08 A 请求含 `Skill: d08-skill`、B 不含且跟随/无 active（`session-skill-ui.json` verified=`0c05b7d1`、ok=true）。视觉已由 W26/W29 重绑 `0c05b7d1`；E2E07 在 `ea9e5732`（现前冻结）复跑 13/13 PASS。仍绑前冻结的：E2E05（`8b210896`）、E2E15 版本切换（`5addfe1d`）；E2E13 真实模型生成仍为 `5addfe1d`（功能源码未变） | 前冻结证据不得写成 `0c05b7d1` 实测；E2E09 自动化 PASS 不等于真实 UI PASS（D-16） |
| 全量门禁（final11，`ab801450` / `72de4f9a`，**当前冻结**） | **PASS**：**3792 tests / 24578 assertions / 0F / 0E / exit 0 / 360 s**（17:50:03–17:56:03，日志 `/tmp/hc-full-test-final11.log`）；PRE=POST 树 hash `ab8014505efe…`、bundle `72de4f9a`（3,014,943 B，17:45:52）、CSS `332f9fd3` 均未变；git 1943→1943；`hs_err` 9→9；峰值 VmHWM 2,120,904 KB≈2.02 GiB；356 ns；seed 171 条 1793958627889–1793958959447。app-check exit 0（`:ok true`/`:version "0.0.1"`/`:component-errors {}`/store `:open? true`，`/tmp/hc-app-check-final11.log`）；`--help` `Herdchestra 0.0.1 server`。final10（`072587a4`/`6aa694f1`，3758/24351）降为历史 | **全量门禁条件 5 在最终冻结树满足**；E2E/视觉证据双记 `72de4f9a` + `ab801450`，旧 hash 一律前冻结 |
| 全量门禁（final6，`7667323a` / `0c05b7d1`） | **PASS**：**3749 tests / 24310 assertions / 0F / 0E / exit 0 / 365 s**（13:38:43–13:44:48）；PRE=POST 树 hash `7667323a…`、bundle `0c05b7d1`、CSS `332f9fd3` 均未变；git 1844→1844；`hs_err` 9→9；峰值 1.93 GiB；13:45:29 复核单树。历史：final4（`a4518891`）3749/24310/**1F**/0E/exit 1（scheduler `[0 0]`，非 D-17）；final5（`da2f92d4`）因 13:29:02 `main.cljs` 写入成混合树，0F 不可作单树基线 | **全量门禁条件 5 满足**；`52547df2` 的 3748/24306/0F 与 final5 的 0F 仅历史；证据绑 `0c05b7d1` |
| 冻结窗口 | **11:26–11:41**、**12:19–12:25**、**13:04–13:15**（实际 13:04:16–13:12，W35 提前宣布结束）：不写 `src/**`/`test/**`、不重建、不跑 E2E；W13/W35 串行全量测试。D-09 的服务端修复（`system.clj` 快照合并 router owners、`commands.clj` 分支回执带 `:command-epoch`）是源码级，不改变客户端 hash |
| 视觉证据绑定 | 会话区第二段已绑定 `a8acf565`（W21 要求不绑定 `4652ef26`）。**最终视觉证据：W29 `audit3-072587a4.json` + `shots3-072587a4/` + `process-evidence3-072587a4.txt`（14:01:28–14:01:37）**；B12/B13/B14/B15/已撤销/暗色 PASS（B14 `#wb-nav-close` 44×44、closedBy=nav-close；B15 360/820 conversation=composer=视口宽、send.right 350/804，1280 send.right 944）。200% 在 `072587a4` 生效：1280 messages 81px、send 90×65、rowOverflow 0；360 200% conversation 360×499、send 90×65 right=350。残留：820 消息操作 41px/版本 32px（<44）、1280 200% 消息可视区 81px（ugly/中，不阻断）、原报告 4 项（W19 看板英文、无模型主操作、默认标题、消息详情英文 token）。`r2-final-*` 13 张仅绑前冻结 `ea9e5732`/`0c05b7d1`，**不绑 `072587a4`**；`8b210896`/`55e4e8ea`/`dd968793` 证据亦前冻结 |
| 视觉 final11（W65，01:55） | `plans/evidence/visual-audit/final11/`：`shots/` 11 张（会话区 1280/820/360、暗色、200%、看板/材料×2/设置）+ `final11.json`（9/9 step OK，`FINAL11_RC=0`；app/Chrome/stub/keeper 已清理）。**bundle `72de4f9a` + CSS `332f9fd3` 未变、视图源码未变**；截图运行时树为 `ab801450`（final11 时段），当前最终树 `7dfcf0d7`（final12），报告已双记 `72de4f9a` + `7dfcf0d7`。**未覆盖项如实标未跑、不宣称 PASS**：B13/B14 逐项、材料深色/200%/扩展小应用、ops 各分区、startup 向导、board 种子态 |
| W13 全量（混合树） | **3707 tests / 24072 assertions / 26F / 10E / exit 1 / 313 s**；3 个 `restart-smoke-test` 失败为混合树伪失败（`system.clj:2670/2675`、`history.clj:377` 半成品），非内存/产品逻辑；峰值 VmHWM **1.97 GiB**（1g 堆 + 512m direct）；app-check 第二次 exit 0 / 21 s / `:ok true`；`hs_err` 9→9。见 `plans/full-test-baseline-current.md`；冻结树必须重跑该 ns |
| W13 全量（前冻结树 `55e4e8ea`） | **PASS**：3741 tests / 24260 assertions / 0F / 0E / exit 0 / 370 s（11:54:12–12:00:22）；seed 逐条记录，峰值 VmHWM 2.05 GiB，无新 `hs_err`；PRE=POST src/test 合并 sha256 `04f859ac40772dd31cd2917b75847c7056eb15166c644afc287373c1412876c8`，窗口内无 src/test 写入；`app-check` exit 0 / 16 s / `:ok true` / `:version "0.0.1"`。见 `plans/full-test-baseline-current.md` 第 2/4/5 节 |
| **W13 全量（`8b210896`，该树已因 13:00–13:01 src 改动失效）** | **PASS**：**3746 tests / 24286 assertions / 0F / 0E / exit 0 / 365 s**（12:19:18–12:25:23，日志 `/tmp/hc-full-test-final2.log`）；PRE=POST=POST2 src/test 合并 sha256 `3fae63139812d55727630cbda3af07dd9c77dcf3f6096482fe531a8f5df440db`，git 1861、`hs_err` 9、bundle `8b210896`/3,009,689 B/12:17:19 三项均未变 → 单树；seed 171 条，范围 1793938782876–1793939118928；峰值 VmHWM 1.90 GiB。`app-check` exit 0 / 14 s / `:ok true` / `:version "0.0.1"`；`--help` 输出 `Herdchestra 0.0.1 server`。见 `plans/full-test-baseline-current.md`。**13:02 起该树失效，需在新树复跑全量** |
| **W35 全量（前冻结 `ea9e5732` / `52547df2`，已作废）** | **PASS**：**3748 tests / 24306 assertions / 0F / 0E / exit 0 / 365 s**（13:04:16–13:10:21）；PRE=POST src/test `52547df2…`，`hs_err` 9、bundle `ea9e5732` 未变 → 单树；峰值 1.94 GiB。`app-check` exit 0 / 15 s / `:ok true` / `:version "0.0.1"`。**该基线已被 13:29:02 `main.cljs` 写入、final6 与 `072587a4` 冻结取代**；当前门禁以 W35 final8（`d1d8d08d` / `072587a4`，3749/24310/0F/0E/445 s）为准，条件 5 满足 |
| W13 全量复跑要求（`8b210896` 已完成，但该树已失效） | 12:19:18 在 `8b210896` 上启动、12:25:23 结束：**3746/24286/0F/0E/exit 0/365 s**，PRE=POST=POST2 `3fae6313…`、git 1861、`hs_err` 9、bundle 三项未变；见上表与 `plans/full-test-baseline-current.md`。**13:00–13:01 的 4 个 src 改动不在该树，须在新构建树复跑全量后才能重新宣称门禁满足** |
| D-08/D-09 最终复跑（`072587a4` + 树 `d1d8d08d`） | D-08：A 自定义勾选后请求含 `Skill: d08-skill`、B 新建不含且跟随/无 active，checks 全 true；`session-skill-ui.json` verified=`072587a4` + `src_tree=d1d8d08d`、ok=true。D-09：branch `[model-b]`/source `[model-a]`/`B-REPLY`/epoch_error=false；`branch-model-switch.json` final_rerun=`072587a4` + `src_tree=d1d8d08d`。**均 PASS**；`0c05b7d1`/`ea9e5732`/`8b210896` 旧证据降为前冻结（E2E15 仍绑 `5addfe1d`） |
| E2E09 证据状态 | W41 根因（`072587a4` + `d1d8d08d`）：snapshot/baseline 的 board-view 产出 `:task/by-id` 等表用 keyword keys（`:task-1`），客户端 `normalize-table` 要求 string entity IDs → `:invalid-entity` → resync → `STATE_SNAPSHOT` 无限重发 → 永远 awaiting-sync、`.hc-protected-program`=0。**修复**：`src/hc/server/app/snapshot.clj` baseline 出口 `normalize-entity-tables` 把 `*/by-id` keys 转 string（feed/delta 同树）+ W23 `:program-status` settle 优先。**复跑 PASS（bundle `072587a4` + 树 `6aa694f1`，证据 `plans/evidence/e2e-09-10/e2e09-final-pass-072587a4.json`、`e2e09-final.json`）**：全部断言 true——冲突卡/stamp 原 20 当前 36/重读后 APPLIED/`worker,7` 落盘/窄视口 360×800 无溢出；D-16 已关闭 | E2E09 升 PASS，D-16 关闭并从 `DEBT.md` 删除；旧 `0c05b7d1`/`ea9e5732`/`d1d8d08d` 证据前冻结 |
| E2E05 复跑时间边界 | W34 复用的 `/tmp/hc-e2e05` app（java 455494）约 12:16 启动；若页面加载早于 12:17:19，实际加载的 main.js 仍是 `55e4e8ea`（前冻结），须标“前冻结”并在 `072587a4` 重跑；以页面实际加载的 hash 为准，不看进程启动时间 |
| E2E05 证据（`8b210896`，前冻结） | W34 以 pageBundle 实测 `8b210896`；压缩预览/编辑/使用/摘要不升级/原消息可读/发送 0 条·摘要 1 PASS；继续两轮消息已发（outbound“发送 4 条·摘要 1”）但 assistant 文本为空、脚本在 digest 处崩溃；撤销/新会话未跑 → **PARTIAL**；锁已于 12:48 释放，不宣称 PASS；`8b210896` 已失效，需在 `072587a4` 重跑 |
| W26 最终视觉复验（前冻结 `ea9e5732`） | 已在 `ea9e5732` / CSS `332f9fd3` 重绑：`plans/evidence/visual-audit/responsive/r2-final-*.png` 13 张 + `r2-final-min.json`/`r2-final-views.json`（运行时 hash=`ea9e5732`）。B14 360/820 `#wb-nav-close` 44×44 可点、关闭后焦点回 `#wb-menu`、顶栏 z50 > 抽屉 z40、backdrop 右侧条可关；B15 360/430/820/1024/1280/窄桌面暗色下 conversation=composer=视口宽、send.right 350/420/804/748/944/350、docScroll=视口、1024 会话列 374；桌面菜单 1024/1280 `display:none`、暗色内容区 rgb(17,22,28)、ARIA tabs 方向键通过；DOM 7/31 0F0E。残留：820 消息操作 41px/版本 32px（<44）、1280 消息高度 300/104（可滚动）；原报告 4 项（W19 看板英文、无模型主操作、默认标题、消息详情英文 token）未修；`ea9e5732` 已作废，需在 `072587a4` 重绑/复跑 |
| 冻结有效性告警（13:02，已由 13:03 重建解决） | 13:00–13:01 的 4 个 src 改动（`conversation/export.cljc` 13:00:55、`conversation/view.cljs` 13:01:43、`history/context.clj` 13:01:51、`app/system.clj` 13:01:51）不在 `8b210896`；W27 已于 13:02:42 重建 `ea9e5732`（3,010,646 B），新树 src/test hash `52547df2…`；W35 13:04:16–13:10:21 全量 PASS（3748/24306/0F/0E/exit 0/365 s），该基线于 13:14:32 D-17 修复后失效，W35 13:16–13:30 重跑中 | `8b210896`/`3fae6313` 作废；新全量出来前不得把 3748/24306 当最终门禁证据 |
| 新树 app-check（13:05） | exit 0 / `:ok true` / `:version "0.0.1"` / `:component-errors {}`（W32，`/tmp/w32-app-check.out`）；版本五处 0.0.1；`--help` 首行 `Herdchestra 0.0.1 server`（13:04 新树） |
| W25 材料视觉（跳过） | 按裁决跳过最终复验；无 `ea9e5732` 证据；已有 21 张材料截图仍绑 `dd968793`/`dda62554`（前冻结）；原因见 `plans/visual-audit-materials.md` §11.8（13:02 base URL 尾斜杠导致 replaceState SecurityError；13:06 按让行裁决停止）；`w25b-failure.json` 为诊断 |
| W11 system.clj 修复 | `component-snapshot` 第二 arity 缺失的关闭括号已在 line 2461 补齐；`(require 'hc.server.app.system)` exit 0（`:system-ok`，22 s）；W11 的 `app-check` exit 0、`:ok true`；未回退其他改动 |
| OOM 根因/caps | 见 `plans/oom-root-cause-0.0.1.md`、`plans/memory-efficiency-0.0.1.md`；`deps.edn` 统一 `-Xmx1g -XX:MaxDirectMemorySize=512m -XX:+ExitOnOOM`；测试子 JVM `test/hc/test_jvm.clj` 512m/256m；E2E app 768m/512m；`/tmp/pixi` 默认 1g/512m（显式 `JAVA_TOOL_OPTIONS` 优先）；Chrome `--disable-dev-shm-usage` |

**工人可用命令（当前环境）：**

```bash
export PATH="$PWD/.pixi/envs/default/bin:$PATH"
# 或统一用临时包装：
/tmp/pixi run clojure -M:server -m hc.server.app ...
/tmp/pixi run test
/tmp/pixi run app-check
/tmp/pixi run client-release
# Playwright（系统 Chrome）：
NODE_PATH=/tmp/hc-pw/node_modules node ...
# launch({executablePath: '/usr/bin/google-chrome'}) 或 CHROME_PATH=/usr/bin/google-chrome
# 不要依赖 /tmp/hc-e2e-harness（仍缺失）；手工 app 用仓库 .pixi 的 clojure
```

## 1. 未关闭的发布阻断项

**E2E 22/22 收口（final22）**：E2E05 final22 42/42、E2E12 final22 48/48×2；final22 全量 3824/24735/0F/0E，app-check `:ok true`/0.0.1，版本五处 0.0.1。W130 正执行最终门禁复核 + commit + annotated tag `v0.0.1`（不 push）；commit/tag 待回填。**D-04 自动化口径仍开放；E2E 22/22 为手工/真实端点口径。**

**提交/tag 状态**：W130 正在执行最终门禁复核 + `git add -A` + commit + annotated tag `v0.0.1`（不 push）；commit/tag hash 待回填。D-04（22 条 E2E 自动化口径）保持开放——22/22 为手工/真实端点口径，不等于新 §8.5 自动化验收。

**E2E12 final22 PASS（W121/W116）**：48/48、`ok:true`、NODE_EXIT=0（两个独立样本）；证据 `e2e12-c9cbfdf6-final22-w121.json`、`-assertfix.json` + `shots-w121-final22`；D-27 关闭保持、D-28 关闭（`:last-move` 落库、撤销链路端到端 PASS）。**统计：22 PASS / 0 PARTIAL / 0 BLOCKED。**

**E2E11 final22 PASS（隔离实例 `/tmp/hc-w22-e2e11:7622`）**：25/25 断言全 PASS、`ok:true`、NODE_EXIT=0（18:22–18:30）；证据 `e2e11-c9cbfdf6-final22.json`；`suggest-switch-on` 关闭（`:memory-suggest?` 置位、整理记忆命令受理执行）、候选审批/拒绝/改范围、归档排除、临时会话一次性读取与自动关闭全 PASS、`frozenGap:null`；双记 `c9cbfdf6` + `1e824c83`。

**E2E05 final22 PASS（W115 v8 并行隔离实例，18:22–18:43）**：42/42 断言全 PASS、`ok:true`；证据 `e2e05-v8-c9cbfdf6-final22.{json,png}` + `-run.log`；D-24/D-25 保持关闭、D-28 `current-with-local` 修复不回归撤销段（撤销后活客户端即时「压缩 已撤销」、撤销按钮消失、outbound 还原原历史不含摘要）；双记 `c9cbfdf6` + `1e824c83`。

**final22 最终冻结 PASS（W33/W125）**：bundle `c9cbfdf6…`（3,018,831 B，2026-11-07 18:12:03；CSS `332f9fd3` 未变；manifest c2158f6b）+ 树 `1e824c83…`（clean rebuild 确定性）；全量 **3824 tests / 24735 assertions / 0F / 0E / exit 0 / 354 s**；app-check `:ok true`/`0.0.1`/`:component-errors {}`。修复入产物已验证：`current-with-local` + `merge-local-state`（D-28）、`bridge/connection` + `bridge/command-allowed?`（E2E11 连接竞态）。标记核对通过（D-08 四标记、AbortController 19、hc-markdown、hc-message-stream、hc-protected-program 14、hc-round-context 2、session-scope 2、resolved-skills 2、speech 31、turn-selection 8、last-move 2）。final21 及更旧作废；E2E12/E2E11/E2E05 已并行复跑全 PASS（见上），证据双记 `c9cbfdf6` + `1e824c83`。

**W124 final22 并入收尾**：`materials_bridge.cljs`——`current-with-local`、6 处 db 读取点、`sync-from-db!` connection 用 `bridge/connection`、timer `command-allowed?` 用 `bridge/command-allowed?`；新增 2 条回归（`sync-from-db-preserves-last-move-behind-a-lagging-db`、`sync-from-db-reads-live-adapter-connection`）。verify_materials_bridge 11/31、model-test 57/253 0F/0E；纯客户端、需重建。W125 clean rebuild → final22 全量 + app-check。

**E2E11 suggest-switch-on 并入 final22（W124）**：同竞态族——`:ui/connection` 异步落库 vs `sync-from-db!` 同步读旧值 → `command-allowed?=false` → `queue-command` 拒绝。修复：`materials_bridge.cljs` 两处改用 `bridge/connection` 与 `bridge/command-allowed?`（adapter atom 同步投影）+ 断言。W124 收尾后 W125 重建再跑 final22 全量。

**E2E11 final21 隔离复跑（W86）**：22 PASS / 1 FAIL——`suggest-switch-on`：`memory-suggest?` 未置位、「整理记忆」恒 disabled、`settings-update memory-suggest?=true` 被 `command-allowed?` 拒绝（“连接尚未完成同步；命令未发送”）；与 final20 同签名，疑与 W124 `transact-state!` 异步/stale-DB 覆盖同源。证据 `e2e11-0fbddd3c-final21.json`；final21 作废，等 final22。

**D-28 运行时根因/修复（W124）**：`transact-state!` 异步提交，`sync-from-db!`/`apply-receipt-event!` 在 mutation 提交前读旧 DB（无 `:last-move`）→ 覆盖丢失。修复：`current-with-local(db)`（DB 投影与 `@last-state` 同会话时 `merge-local-state` 合并 local keys），6 处读取点全改用；测试 57/253 + 10/28 0F/0E。final21 作废；W125 clean rebuild → final22 全量 + app-check。

**final21 最终冻结 PASS（W33/W118）**：bundle `0fbddd3c…`（3,018,796 B，2026-11-07 16:34:21；CSS `332f9fd3`；manifest c2158f6b）+ 树 `319f035f…`（clean rebuild 确定性 PRE=POST）；全量 **3824 tests / 24735 assertions / 0F / 0E / exit 0 / 354 s**；app-check `:ok true`/`0.0.1`/`:component-errors {}`；**unwrap-receipt 修复已验证入产物**。final20 及更旧作废；E2E 证据双记 `0fbddd3c` + `319f035f`。

**E2E05 final20 诊断（W101）**：v8 41/42——D-24/D-25 双关闭（撤销后活客户端显示“压缩 已撤销”），证据 `e2e05-87b0dee4.*`；final20 作废、仅诊断。final21 需复核 D-25 保持且 D-28 修复不回归 E2E05 撤销段。W117 确认 D-28：unwrap-receipt 任何 `map? :receipt` 解包+merge+外层回填；model_test 57/253、bridge 9/26、server 3824/24735 0F/0E；main.js mtime 早于源码编辑，final21 clean rebuild 需核对。

**D-28 真正根因/修复（W117）**：`unwrap-receipt` 拒绝解包网关信封 `{:ok? :command-id :receipt {..}}`（旧代码要求 `(:command-id receipt)` nil 才取 `:receipt`）→ `receipt-result` 只读外层 `:result=nil` → `:undo` 缺失 → `:last-move` 不置位/撤销按钮不渲染（move 成功）。修复：任何 `map? :receipt` 解包 + merge + 外层回填；测试 3824/24735 0F/0E。final20 作废；W118 clean rebuild → final21 全量 + app-check + 验证入产物。

**final20 最终冻结 PASS（W33/W114）**：bundle `87b0dee4…`（3,018,665 B，2026-11-07 15:26:31；CSS `332f9fd3`；manifest c2158f6b）+ 树 `5e301ce4…`（PRE=POST）；全量 **3823 tests / 24731 assertions / 0F / 0E / exit 0 / 354 s**；app-check `:ok true`/`0.0.1`/`:component-errors {}`；**D-25/D-27/D-28 修复已入产物**；swarm_ui 2 失败为既有。final19 及更旧作废；E2E 证据双记 `87b0dee4` + `5e301ce4`。

**D-25 根因/修复（W113）**：snapshot/baseline 非活跃会话 cached-entry 键 `[(:revision session) epoch]`，`session-summary` 不含 `:revision` → 键恒 `[nil epoch]` → `:session/by-id` 的 `:context`/`:compressions` 永久缓存 `:applied`（history 正确）→ delta 无该路径 → 活客户端不更新；重启正确。修复 `session-summary` 加 `:revision` + 缓存/delta 测试 394/2761 0F/0E。W110 也修 D-27/D-28。final19 作废；W114 → final20 重建+全量+app-check。

**E2E05/06/15 final19（W93/W101b）**：E2E05 v8 W101b 38/42——D-24 关闭、D-25 仍 FAIL（撤销命令被拒“命令未送达”、UI 仍摘要1/含压缩摘要/无“已撤销”标记）；W93 轮 26/32（proxy64 被清空）；sessionId 由 v8 DOM fallback 解决。E2E15 PASS（regen+版本切换）；E2E06 PASS（文本腿五轮一致，extract 为 OCR 环境限制）。tree before `f8477191`/after `25beab95`（漂移，bundle 未变）；D-25 待修。

**并发策略（用户指令，取代单执行者/锁窗口）**：多个 worktree/实例可并发跑测试与 E2E，各自独立 data-dir/Chrome profile/端口即可，互不干扰；E2E05/06/11/12/15 可并行（全量峰值约 2 GB，避免同时起过多 app/Chrome）。证据仍双记 `168318c1` + `f8477191`。

**final19 最终冻结 PASS（W33/W107）**：bundle `168318c1…`（3,017,514 B，2026-11-07 12:42:52；CSS `332f9fd3`）+ 树 `f8477191…`；全量 **3818 tests / 24714 assertions / 0F / 0E / exit 0 / 356 s**；app-check `:ok true`/`0.0.1`/`:component-errors {}`；**D-27/D-28 修复已入产物**（materials-bridge harness 8/23 0F/0E；swarm_ui 2 失败为既有）。final18 及更旧作废；E2E 证据双记 `168318c1` + `f8477191`。

**E2E11 final19 复跑 PASS / D-26 关闭（W86）**：25/25 断言全过（`plans/evidence/e2e-11-12/e2e11-168318c1-final19.json`，双记 `168318c1`+`f8477191`）；`suggest-switch-on` 一次点击即 enabled（706 ms），pending 锁关闭，临时会话一次性读取与自动关闭全 PASS，`frozenGap:null`。**D-26 已关闭并从 `DEBT.md` 删除**。**统计更新（final19）**：E2E **20 PASS / 2 PARTIAL（05/12）/ 0 BLOCKED**；E2E12 仍 PARTIAL（D-27 指令卡泄漏、D-28 撤销/:last-move 残留，pending 锁已释放——D-28 pending 锁部分关闭，W108 修复中待 final20）；E2E05 待 v8 撤销段；D-07 子项 05/12/15。

**materials_bridge.cljs 写入归属**：12:36:37 写入者未确认（W106 否认编辑），但 W107 PRE/POST 树 hash 同为 `f8477191`（内容稳定）、bundle 12:42:52 晚于该 mtime、harness 已在该内容上补跑 8/23 0F/0E。**以内容 hash 为准、不追 mtime；final19 冻结不受影响。**

**树漂移（W106 bug1，11:55–12:04）**：final18（`f8c0f53a`+`6d1ad03b`）基线失效，final19 待建；final18 的 E2E05 v8 仅诊断；E2E15/06 结果仍为 final18 上取得。

**D-27/D-28 修复（W106）**：D-27 验证通过；D-28 根因 `set-materials-state` stale 整体丢弃 receipt 写入 → move 卡 `:submitting`；修复 `merge-local-state`（model/materials_bridge/mutations）+ orphan drain + 5s sweep；测试 106/783 + 10/67 0F/0E。final18 作废；W107 完整 client 测试 → 重建 → final19 全量 + app-check。

**D-27/D-28 详细根因（W104）**：D-27——`send-snapshot!` 仅用 draft/edit/attachments/quotes 播种 reducer，`:composer/send` 不带 snapshot，payload 缺 turn 键；version 未进各层 `select-keys`；修法：snapshot 作 `:composer/send` 权威 payload + 补 version，无协议变更。D-28——`set-materials-state` stale 丢弃 receipt 写入（Fulcro transact! 异步 revision），`apply-receipt-event!` 未落库时丢弃不 park；修复 merge-local-state + deferred 重读 + orphan park/drain。测试 verify_materials_bridge 8/23、verify_workbench_session_commands 5/23、JVM 3818/24714 全 0F/0E。

**统计更新（final18）**：E2E **19 PASS / 2 PARTIAL（05/12）/ 1 BLOCKED（11）**；E2E15 PASS（final18 regen/版本切换/朗读停止；麦克风环境限制单列）；E2E12 仍 PARTIAL（D-27/D-28）；D-07 子项 05/12。
**统计更新（final19）**：E2E **20 PASS / 2 PARTIAL（05/12）/ 0 BLOCKED**；E2E11 PASS（25/25，D-26 关闭并从 `DEBT.md` 删除）；E2E12 PARTIAL（D-27/D-28 残留，W108→final20）；D-07 子项 05/12；DEBT 开放 17 条、建议阻断 13 条。
**统计更新（final20）**：E2E **21 PASS / 1 PARTIAL（12）/ 0 BLOCKED**；E2E05 final20 复跑 41/42 PASS、D-24/D-25 双关闭（`e2e05-87b0dee4.json`，双记 `87b0dee4`+`5e301ce4`）；E2E12 仍 PARTIAL（D-27 已修、D-28 move 撤销/`:last-move` 5 项 FAIL，W117 unwrap-receipt 修复待 final21）；D-07 子项仅 12；D-25/D-27 已关闭（final20 复跑确认），D-28 修复已入 final21 树。
**统计更新（final22）**：E2E **22 PASS / 0 PARTIAL / 0 BLOCKED**；E2E12 断言口径修正后 48/48 PASS（`e2e12-c9cbfdf6-final22-assertfix.json`，D-27/D-28 双关闭）、E2E11 25/25 PASS（`e2e11-c9cbfdf6-final22.json`）、E2E05 42/42 PASS（`e2e05-v8-c9cbfdf6-final22.{json,png}`）；证据统一双记 `c9cbfdf6`+`1e824c83`；D-07/D-27/D-28 已关闭并从 `DEBT.md` 删除，DEBT.md 剩 12 条开放、建议阻断 8 条（D-04/D-11/D-12/D-13/D-17/D-18/D-22/D-23）。

**D-26 根因/证据（W97/W99）**：durable gate 探针 `commands/session/w22-s1` 仅 4 条 receipt（1×`settings/update read?`+`suggest?false`、3×memory approve/reject），**suggest 的 `settings/update` 从未到达 `accept!`**；传输层丢失、pending 锁永久持有；自愈仅在 `sync-from-db!` 触发；`receipt!` 在 subscription-id nil 时静默 false。修复 P1 独立 5s 清扫 + `receipt!` 可重试（覆盖 nil 分支）、P2 `:submitted` ack 不释放锁；测试后 **final18** 重建 + 全量。

**D-26 修复已落地（W99）**：`model.cljc`（`:submitted` 非终态、`:not-found`→`:refused`）+ `materials_bridge`（5s 独立清扫 + dispatch 后清扫）；CLJS bridge 4/12 0F/0E，JVM model 测试待 W100。**final17 树漂移 `0a62fd2c`，冻结暂停**；W100 重建 bundle → final18 全量 + app-check 后重冻结；E2E05/11 等 final18。

**final18 最终冻结 PASS（W33/W100）**：bundle `f8c0f53a…`（3,017,311 B，2026-11-07 11:21:45；CSS `332f9fd3` 未变）+ 树 `6d1ad03b…`（120 s 稳定，PRE=POST）；JVM 聚焦 57/271 0F/0E；全量 **3816 tests / 24705 assertions / 0F / 0E / exit 0 / 355 s**（`/tmp/hc-full-test-final18.log`）；app-check `:ok true`/`0.0.1`/`:component-errors {}`（`/tmp/hc-app-check-final18.log`）；meta `plans/evidence/final18-freeze-meta.json`。**D-26 sweep 已入产物**（`setInterval(h_b,5E3)` + `install! receipt-sweep-timer`；bundle 内 `setInterval` 4 处）。final17（`efbc465a`+`99aa81a4`）及更旧作废；E2E 证据双记 `f8c0f53a` + `6d1ad03b`。

**final17 最终冻结 PASS**：bundle `efbc465a`（3,016,559 B，2026-11-07 10:11:35；clean rebuild 逐字节相同，确定性；manifest c2158f6b；CSS `332f9fd3`/`77da0dd4`）+ 树 `99aa81a4`（PRE=POST）；全量 **3813/24695/0F/0E/exit 0/357 s**；app-check `:ok true`/`0.0.1`/`:component-errors {}`；git porcelain 16、`hs_err` 9。W87 修复已在 bundle（`closure.variable.map`：`recover_pending_materials_commands_BANG_`→`e_b`、`receipt_sweep_at`→`d_b`、`merge_local_state`→`c_b`，均定义并被 `sync_from_db`/`transact_state` 调用）；W86 的“bundle 未含修复”为字面 grep 误报（release `:simple` 改名）。E2E11 若仍报“同一目标已有待确认命令”，原因在服务端 pending 锁/时序，不在客户端 bundle。E2E05/06/15 的 final16 run5 结果仅作诊断；**统计保持 18 PASS / 3 PARTIAL（05/12/15）/ 1 BLOCKED（11）**；D-07 仍为 05/11/12/15（4 条）。可复跑 E2E05/06/11/15。

> 说明：**D-05、D-06、D-08、D-09、D-10、D-14 已关闭**；**D-12 仍是开放项（新口径：手动模型绑定缺少 `:tools`，Plan/Reviewer 返回 `MODEL_TOOLS_UNAVAILABLE`）**，旧 D-12（`:unknown` 导致发送禁用）已修复但编号被新缺口复用。D-06 由 W11 final fix 关闭（20 轮/180 assertions、SIGTERM 5/5、`hs_err` 9→9、`app-check` 3/3）；D-05 由 W7 E2E22 final PASS 关闭（`e2e22-final.json`；手工回归，不是新 §8.5 自动化验收）；D-09 由 W9 真实 Chrome 验证关闭（分支切 `model-b`、源会话仍 `model-a`，证据 `plans/evidence/e2e-13/branch-model-switch.json`）；D-14 由 W11 的 `:TOKEN_TASK_REQUIRED` 实现并测试；D-08 已由 `:session/select-skills` + 扩展视图修复。D-10 旧组号残留 0。

当前 `DEBT.md` 开放 **13 条**；其中 **9 条直接阻断打 tag**（D-22/D-23 已由 W30 立条）：

| # | 条目 | 负责人 | 当前证据 | 关闭条件 | 对打 tag 的影响 |
|---:|---|---|---|---|---|
| 1 | **D-13** Plan 账本未清空时会话永不 idle（Nudge 持续注入） | W3/W5 | E2E 04 第三轮 stub 已通过（THIRD-REPLY、只发一次、顺序正确），但 `idleAfter1/idleAfter2/idleBeforeThird` 全 false；毫秒级 stub 仍显示“正在生成/自动继续”，停止后又进入 | Nudge 提供可观察的本轮结束边界（账本清空或明确 `:awaiting-user`）；E2E 04 等待条件改为该边界并通过 | 会话无稳定 idle，停止后仍可能自动继续；E2E 04 及依赖 idle 的自动化不可靠 |
| 2 | **D-07** E2E 05/12 未完成子项（06/07/08/09/11/13/15 已 PASS，移出） | W2–W5/W10/W83/W108 | 05 final19 复跑：D-24 已关闭（撤销后 outbound 含全部原消息）；D-25 残留 FAIL（撤销命令被拒「命令未送达」、标记未渲染，疑 epoch/selection-seq 过期），W111 修复中待 final20；12 final19 复跑 43/48 PASS——指令卡泄漏 D-27（w22-s1 r2/r7 残留）与 move 回执/`:last-move` D-28（pending 锁已释放但回执未应用）未根治，W108 修复中待 final20；D-22/D-26 已关闭；final21 复跑 D-28 残留 3 项 move/undo（运行时状态链疑覆盖 `:last-move`），W123→W125：`transact-state!` 异步提交致 `sync-from-db!`/`apply-receipt-event!` 读旧 DB 覆盖 `:last-move`，修复=`current-with-local(db)`+6 处读取点（57/253+10/28 0F/0E）；final21 作废，W125→final22 重冻结，E2E12 待 final22 复跑关闭 | 每条在稳定树复跑并记录 PASS；无法自动化的平台项标 NOT RUN 并经用户确认；master 状态更新 | E2E 未全 PASS；0.0.1 不能打 tag |
| 3 | **D-11** 24 类失败文案未重组为十类“未交付理由” | W5/W9 | `src/hc/client/copy/failures.cljc` 仍 24 类；`failures_test.clj:66-68` 断言 24；4 个测试仍断言“结果待确认”“已停止”；发布/审计文档仍按 24 类统计 | 保留 24 类内部映射并新增“24→10”断言；4 个测试改为十类/三种状态或明确为操作反馈；文档同步 | UI 仍把内部错误类别当用户状态；测试锁定旧口径 |
| 4 | **D-12** 手动模型绑定缺少 `:tools`，Plan/Reviewer 发送返回 `MODEL_TOOLS_UNAVAILABLE`（开放；**D-AUTO-02 已并入**） | W5/W8/W10 | 保存/composer/选择门禁已修；`handlers.clj:120-160` 已让声明的 `:tools true` 优先于 live 探测，但声明的 `:tools` 仍未进入 binding。W10 13:16 自动化 E2E01 FAIL（33.8 s）：UI 行 `工具：已声明支持`、`bind.toolsDeclared=true`，Plan 仍 `MODEL_TOOLS_UNAVAILABLE`、未产生用户消息（D-AUTO-02 并入 D-12，`plans/e2e-auto-defects.md`、`plans/evidence/e2e-auto/e2e01/e2e01-result.json`）。证据 `plans/d12-fix-report.md` | 确认 binding/config 带 `:tools true`、助手回复出现；补“手动模型绑定后可发送并收到回复”服务端回归 | E2E01 自动化仍 FAIL；新用户按引导新建连接后 Plan/Reviewer 轮次失败 |
| 5 | **D-04** 22 条 E2E 只自动化 3 条，mock 未接入全部剧本 | W10 | `test/e2e/` 仅 01/03/09 三个 spec + `mock-services.spec.mjs`；其余 19 条无 spec；mock 服务未接入这 19 条。W10 13:16 结果（`ea9e5732`）：03 PASS 19/19、09 自动化 PASS 15/15（真实 UI 仍 FAIL/D-16）、01 FAIL 33.8 s（D-AUTO-02） | 22 条全部自动化；mock 模型 + OAuth/WebDAV/S3 接入全部剧本；`npm run e2e` 通过 | 新 §8.5 主验收路径未完成；发布门禁的自动化条件不满足 |

### 1.1 其余开放 DEBT（是否阻断由用户裁决）

| 条目 | 负责人 | 现状 | 建议 |
|---|---|---|---|
| **D-01** `askPreviousScope`/`negotiate` 只有账本快照 | W2/W6 | `scope.clj:313-330` 只有 snapshot；`compose/workspace.clj:257-272` 返回 `:UNSUPPORTED_OPERATION`；无 receipt/答案 steer | 若 0.0.1 不承诺该能力，需在 release notes 明确 NOT RUN/限制；否则按 spec 补齐 |
| **D-03** 用户编辑底稿后无润色/回读 | W3 | `relay/core.clj:379-410` 只存 `{:path :content}`；`start-execution` 用旧 content；无磁盘重读/润色 | 若用户编辑底稿是 0.0.1 承诺，需修复；否则 release notes 标限制 |

**统计：** 直接阻断 9 条（D-04/D-07/D-11/D-12/D-13/D-17/D-18/D-22/D-23）；另有 D-01/D-03/D-19/D-20 四条开放项待裁决。D-02/D-05/D-06/D-08/D-09/D-10/D-14/D-15/D-16 已关闭。**D-23 已修复（W35，send-message 经网关并写 run/input-snapshot），D-22 主因 W63 + 补强 W70（pair-hint compaction boundary/merge-owner-context），代码/测试/全量已入最终树 `79f21de9`；D-22/D-23 均待 E2E 复跑（runner5）确认后关闭。**
**D-23 状态：** **W35 已落地**，代码/测试完成、待 E2E12 验证；final15 已在树 `651b9a45` 上单树全量 PASS（3808/24669/0F/0E）+ app-check `:ok true`/0.0.1；final14（`732baf05`+`79f21de9`）、final13（`72de4f9a`+`36fddd6d`）与 `2b269e0d` 及更旧失效。**W70/W71/W79 已入 final15 树**：W70=`system.clj` compaction boundary/merge-owner-context + `pair_hint/hint.clj`；W71=materials apply-snapshot turn-card/`:instruction-turn`/`reset-draft-for-command`/`drain-orphan-receipts`。

**对账（w30）：** `DEBT.md` 开放 **13 条**（D-01/D-03/D-04/D-07/D-11/D-12/D-13/D-17/D-18/D-19/D-20/D-22/D-23）；master §3 为 **18 PASS / 3 PARTIAL / 1 BLOCKED**（06/07/08/09 升 PASS、11 BLOCKED）；D-07 现为 4 条（05/11/12/15）。D-16 已关闭并从 `DEBT.md` 删除；D-09、D-15 已关闭并移出 `DEBT.md`；D-10 旧组号残留 0，仍有 66 处通用 `§7.4` 引用（35 AGENTS + 31 源码，其中 59 处已写旅程名、7 处为通用引用），不构成旧组号残留。

## 2. 最终门禁命令清单（不执行）

### 0）环境恢复/临时包装（当前状态）

```bash
# /tmp/pixi 缺失：恢复 pixi 0.83.0 到 /tmp/pixi（与 RUNNING.md 一致）
# /tmp/hc-e2e-harness、/tmp/hc-pw 缺失：手工 E2E 需恢复；自动 E2E 用仓库 node_modules
# 浏览器：node_modules/@playwright/test 1.64.1；~/.cache/ms-playwright 现有 chromium-1217/1234，
# 若与 Playwright 版本不匹配，先 npx playwright install chromium
```

### 1）Pixi 锁定安装

```bash
cd /home/kunweiz/Desktop/vibe/Herdchestra
/tmp/pixi install --locked
```

### 2）客户端依赖与构建

```bash
/tmp/pixi run client-install
flock -w 900 /tmp/hc-client-release.lock -c '/tmp/pixi run client-release'
sha256sum resources/public/js/main.js
stat -c '%y %s' resources/public/js/main.js
```

### 3）启动自检

```bash
/tmp/pixi run app-check
# 期望 exit 0；health :ok true、:version "0.0.1"、store open、gateway 计数
```

### 4）真实启动 + healthz/首页 + 重启

```bash
/tmp/pixi run clojure -M:server -m hc.server.app \
  --host 127.0.0.1 --port 0 \
  --data-dir /tmp/hc-final-gate/data \
  --workspace-root /tmp/hc-final-gate/work \
  --static-root resources/public
# 读取日志中的 URL；然后：
curl -fsS http://127.0.0.1:PORT/healthz
curl -fsS http://127.0.0.1:PORT/ | grep -i '<title>'
kill -TERM <PID>
# 同 data-dir 再启动一次，确认 store open、sessions 保留
```

### 5）全量测试（最终树）

```bash
/tmp/pixi run test
# 记录：tests/assertions/failures/errors、退出码、耗时、test.check seed
```

### 6）版本五处一致

```bash
grep -n 'def version' src/hc/version.cljc
grep -n '^version' pixi.toml
grep -n '"version"' package.json | head -1
/tmp/pixi run clojure -M:server -m hc.server.app --help | head -1
curl -fsS http://127.0.0.1:PORT/healthz   # :version
# 五处均期望 0.0.1
```

### 7）工作树清单

```bash
git status --porcelain -uall
git ls-files
# 发布提交后应为空；产品源码/测试/构建清单/文档均已提交，bundle/out-*/target 等被忽略
```

### 8）自动 E2E

```bash
# 先确保 client-install 完成、Playwright 浏览器可用
npm run e2e
# 或按标签：
npm run e2e:core
npm run e2e:mock
```

### 9）DEBT 与发布说明

```bash
grep '^## D-' DEBT.md
# 期望：无 P0/阻断条目；D-01/D-03 若仍开放，需用户裁决并写 release notes
# 核对：
plans/debt-status-0.0.1.md
plans/release-notes-0.0.1.md
plans/e2e-master-status-0.0.1.md
```

## 3. 预计最早可跑时间

- 当前 2026-11-06 11:26 CST，处于 W9 的 15 分钟冻结窗口（11:26–11:41）：不写 `src/**`/`test/**`、不重建、不跑 E2E；W13 串行全量测试。
- D-13、E2E 05/08/09/11/12/15、D-11、D-12（含 D-AUTO-02/E2E01）、D-04 自动化仍未关闭；D-08/D-09/D-14/D-05/D-06/D-10 已关闭。
- **最早不早于 2026-11-06 18:00 CST**，前提：
  1. W11 最终修复复验无新 `hs_err`（当前 `hs_err` 9，无新增）；
  2. D-13 在下午前修复并有回归；
  3. E2E 05/08/09/11/12/15 在稳定树上复跑 PASS 或经用户确认 NOT RUN（06/07 已 PASS）；
  4. D-11 与 D-12（含 D-AUTO-02/E2E01）修复；
  5. W10 的 19 条自动化至少达到可跑状态（若坚持 §8.5 全自动化，可能顺延到 11-07）。
- 若上述任一未满足，最终门禁应顺延；不得用旧证据或手工规避替代。

## 4. 结论

**当前不能打 0.0.1 tag。** 直接阻断 **7 条**（D-04/D-07/D-11/D-12/D-13/D-17/D-18），另有 D-01/D-03/D-19/D-20 四条开放项待裁决；`/tmp/hc-e2e-harness` 仍缺失、`/tmp/pixi` 当前为临时包装，需在最终门禁前恢复真实工具。完整性检查本身通过（load/app-check/system/storage 全 PASS，`hs_err` 9→9）；**final11 在 `72de4f9a`/`ab801450` 上单树 PASS（3792/24578/0F/0E/exit 0/360 s，17:50–17:56），条件 5 满足**；final10（`072587a4`/`6aa694f1`，3758/24351）降为历史基线；但 E2E 未全 PASS，发布门禁仍未满足。

**12:52 更新**：`8b210896` 树的全量测试曾 PASS（3746 tests / 24286 assertions / 0F / 0E / exit 0 / 365 s，PRE=POST 源码哈希 `3fae6313…`，单树），`app-check` exit 0 / 14 s / `:ok true` / `:version "0.0.1"`，版本五处一致；D-15 已关闭（W8 UI/outbound 回归 PASS），D-08/D-09 在 `8b210896` 重跑 PASS（现降为前冻结）。**13:02 告警：13:00–13:01 的 4 个 src 改动不在 `8b210896`，该 hash 与 `3fae6313` 全量均已失效；W27 已于 13:02:42 重建 `ea9e5732`（3,010,646 B，新树 `52547df2…`），W35 已于 13:04:16–13:10:21 全量 PASS。** 剩余阻断仍包括 E2E 未全 PASS（E2E09 已在 `ea9e5732` 运行但 FAIL，D-16；部分证据仍绑 `55e4e8ea`）、D-11/D-12/D-13/D-17/D-18 与工作树未提交。

**13:11 更新**：`ea9e5732` / `52547df2` 的全量测试曾 **PASS**（3748/24306/0F/0E/exit 0/365 s），13:14:32 D-17 修复后失效；`a4518891` final4 为 3749/24310/1F（scheduler `[0 0]`，非 D-17）；final5（`da2f92d4`）因 13:29:02 `main.cljs` 写入成混合树，0F 不可作单树基线。final6 曾在 `7667323a` / `0c05b7d1` 上 PASS（3749/24310/0F/0E/365 s），但 13:50 `main.cljs` 改动使该树失效；**final7 在 `a97c68f7` / `072587a4` 上 PASS**：3749/24310/0F/0E/exit 0/380 s（13:53:52–14:00:12，PRE=POST 单树，`hs_err` 9→9，峰值 1.97 GiB，14:00:56 复核），**条件 5 满足**。`ea9e5732`、`0c05b7d1` 作废；E2E/视觉证据须重绑 `072587a4`（E2E09 在旧树仍 FAIL，见 D-16）。scheduler `[0 0]` 已按测试边界修复（t0 改 `2026-11-02T23:59:59`，final6 0F），不立 DEBT。剩余阻断：D-11/D-12/D-13/D-17/D-18、工作树未提交；不能据全量 PASS 单独打 tag。

**17:56 更新（final11，历史）**：W33 曾宣布最终冻结 **bundle `72de4f9a`（3,014,943 B，17:45:52；CSS `332f9fd3` 未变）+ 源码树 `ab801450…`**；W35 final11 单树全量 **PASS**：3792 tests / 24578 assertions / 0F / 0E / exit 0 / 360 s（17:50:03–17:56:03，PRE=POST 单树，git 1943→1943，`hs_err` 9→9，峰值 2.02 GiB，356 ns，seed 171 条）；app-check exit 0/`:ok true`/0.0.1。**当时全量门禁条件 5 满足**；final10 及更旧降为历史。该冻结后被 final12–final14 取代。

**04:40 更新（final14）**：W33/W58 最终冻结 **bundle `732baf05`（3,015,403 B，04:33:20；CSS `332f9fd3` 未变）+ 源码树 `79f21de9…`**；W75 运行、W35 复核，单树全量 **PASS**：3805 tests / 24650 assertions / 0F / 0E / exit 0 / 352.10 s（04:34:50–04:40:42，PRE=POST，git 2025、`hs_err` 9；VmHWM 未采样）；app-check exit 0/15 s/`:ok true`/0.0.1/`:component-errors {}`，版本五处 0.0.1。**全量门禁条件 5 满足**。final13（`72de4f9a`+`36fddd6d`，3795/24596）与 `2b269e0d`（3,015,393 B，未上盘）作废；final11 及更旧均前冻结。最终树含 W63 D-22、W35 D-23、W70 pair-hint compaction boundary/merge-owner-context、W71 materials apply-snapshot turn-card/`:instruction-turn`/`reset-draft-for-command`/`drain-orphan-receipts`；**D-22/D-23 待 runner5 E2E 复跑确认后关闭**。E2E/视觉证据须双记 `732baf05` + `79f21de9`。

**06:01 更新（final15 最终冻结）**：W74 重建 bundle `a14d9abd`（3,015,902 B，05:50:40；CSS `332f9fd3` 未变）含 W79 client 修复（E2E12 session-receipt：`model.cljc` begin-session/select-book、`materials_bridge.cljs` transact-state!）；W33/W35 冻结 **bundle `a14d9abd` + 源码树 `651b9a45`（05:49:30 后无写入）**；W35 final15 单树全量 **PASS**：3808 tests / 24669 assertions / 0F / 0E / exit 0 / 356 s（05:54:29–06:00:25，PRE=POST 单树，git 2027→2027，`hs_err` 9，峰值 VmHWM 2,153,036 KB≈2.05 GiB，seed 171 条，356 ns）；app-check exit 0/14 s/`:ok true`/`0.0.1`/`:component-errors {}`，`--help` 0.0.1。**全量门禁条件 5 满足**。final14（`732baf05`+`79f21de9`）作废；E2E/视觉证据须双记 `a14d9abd` + `651b9a45`；可复跑 E2E05/12/11/15，不再写 src/test、不再重建。

**final16 更新（W33/W91 最终冻结）**：W83/W87/W89 修复后 W91 重建 bundle `efbc465a`（3,016,559 B）+ 源码树 `99aa81a4`；单树全量 **PASS**：3813 tests / 24695 assertions / 0F / 0E / exit 0 / 353.44 s；app-check `:ok true`/`0.0.1`/`:component-errors {}`，版本五处一致；相对 PRE 树仅多 `test/hc/server/history/run_port_test.clj` 两处定界符修复；meta `plans/evidence/final16-freeze-meta.json`。**全量门禁条件 5 满足**。final15（`a14d9abd`+`651b9a45`）及更早作废；E2E/视觉证据须双记 `efbc465a` + `99aa81a4`；D-24/D-25/D-26 待 final16 E2E 复跑（E2E05/11）确认后关闭。
