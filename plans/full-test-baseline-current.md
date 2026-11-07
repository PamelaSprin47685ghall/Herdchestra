# 0.0.1 全量测试基线（最终冻结 bundle `c9cbfdf6` / 源码树 `1e824c83`；final21 `0fbddd3c`/`319f035f`、final20 `87b0dee4`/`5e301ce4` 及更早全部作废）

- 日期：2026-11-07 08:52:45–08:58:38 CST（冻结窗口内）。
- 工作目录：`/home/kunweiz/Desktop/vibe/Herdchestra`；HEAD `379b021`（产品文件未提交）。
- 命令：`/tmp/pixi run test`（即 `clojure -X:test:compose:server:client`），串行；启动前 120 s 首尾 hash 一致检查通过（08:50:45 与 08:52:45 均为 `b9570d04…`，见 `plans/evidence/final16-freeze-meta.json` `stability`）。
- 资源限制：`JAVA_TOOL_OPTIONS='-Xmx1g -XX:MaxDirectMemorySize=512m'`。
- 运行者：W91（脚本 `/tmp/hc-full-run-final16.sh`）+ W92 final16b 同树补跑（PRE=POST `99aa81a4`）**PASS**，冻结成立。
- 日志：`/tmp/hc-full-test-final16.log`；元数据：`plans/evidence/final16-freeze-meta.json`；app-check：`/tmp/hc-app-check-final16.log`。
- 结论先行：**全量通过（0F/0E，exit 0）**；PRE 树 `b9570d04…` → POST `99aa81a4…`（差异仅 `test/hc/server/history/run_port_test.clj` 两处定界符修复，该文件在 PRE 树 reader 报错、阻断整套件；测试不进 bundle）；bundle 与 CSS 在窗口内未变，`git porcelain` 16、`hs_err` 9。

## 1. 数字

| 项 | 实测 |
|---|---|
| tests | **3813**（final15 3808 + 5） |
| assertions | **24695** |
| failures | **0** |
| errors | **0** |
| 退出码 | **0** |
| 墙钟 | 353.44 s（5 分 53 秒，08:52:45–08:58:38） |
| test.check seed | 171 条 property，范围 1794012788695–1794013117970 |
| app-check | **exit 0**；`:ok true`、`:version "0.0.1"`、`:component-errors {}`、store `:open? true` |
| `--help` | `Herdchestra 0.0.1 server`，exit 0 |
| 父 JVM 峰值 VmHWM | 本轮未采样（W91 未记录；final15 为 2,153,036 KB ≈ 2.05 GiB） |
| 命名空间数 | 357 个 `Testing hc.*` |

## 2. 冻结窗口完整性（PRE≠POST 说明）

| 项 | PRE（08:52:45） | POST（08:58:38） |
|---|---|---|
| src+test 合并 sha256 | `b9570d0476f704ace4425988acc0ebe6dab73e8abaae171218a73616b1481836` | `99aa81a4d150efa4cef70fb70052c9517d39705f872558be62c74d2cf0f96580` |
| 树差异 | — | **仅 `test/hc/server/history/run_port_test.clj` 两处定界符修复**：第 94 行补缺失的 `}`、第 124 行删多余 `)`；该文件在 PRE 树无法被 Clojure reader 读取、阻断整套件；测试文件不进 bundle，bundle 两侧不变 |
| `git status --porcelain` 行数 | 16 | 16 |
| `hs_err_pid*.log` 数 | 9 | 9 |
| bundle sha256 / 大小 / mtime | `efbc465adf88139896949461fe0c10469bb3f3c2d5802de3ec7262473f61cd7f` / 3,016,559 B / 08:43:18 | 同左（未变） |

- bundle 由 W91 于 08:43:18 重建（shadow-cljs release，23.05 s），含 W83/W87 修复（D-24/D-25 撤销投影/标记、D-26 settings-update 回执/pending 锁）及 W89 测试修复；`manifest-edn` sha256 `c2158f6bdb365d28fc85cd8351db797facc99d0fb2a2abc641d26d5d107ba930`。
- `workbench.css` `332f9fd3f3e759f5ef215dbe1ff9307e774d7f56b262d0ce9839d861e8609c4b`（48,202 B，11-06 11:48:17），未变；`framework7-bundle.css` `77da0dd4…` 未变。
- 重建前 120 s 稳定窗口内 PRE 树哈希 `b9570d04…` 前后一致（meta `stability`）；全量测试期间 `src/**`/`test/**` 仅有上述定界符修复一处差异，且该文件 reader 报错在修复前即阻断套件，故 3813/24695 为修复后完整套件数字。
- **final16b 确认 PASS（W92）**：PRE=POST `99aa81a4` 同树补跑全量 **3813/24695/0F/0E/exit 0/355 s**（09:10:00–09:15:55；seeds 171、峰值 2.28 GB；app-check `:ok true`/0.0.1/`:component-errors {}`/store open；git porcelain 16、`hs_err` 9；日志 `/tmp/hc-full-test-final16b.log`、meta `plans/evidence/final16b-freeze-meta.json`），与 final16 完全一致 → **冻结成立**。E2E/视觉证据双记 `efbc465a` + `99aa81a4`。
- **final17 最终冻结（W33/W96）**：clean rebuild 产物与 final16 逐字节相同——bundle `efbc465a`（3,016,559 B，10:11:35；manifest `c2158f6b`）+ 树 `99aa81a4`；全量 3813/24695/0F/0E/exit 0/357 s，app-check `:ok true`/0.0.1/`:component-errors {}`；git porcelain 16、`hs_err` 9。W87 修复已验证在 bundle（closure.variable.map `e_b`/`d_b`/`c_b`），W86 grep 未命中为 release `:simple` 改名误报。final16 run5 E2E 仅诊断，待 final17 复跑；证据双记不变。**冻结暂停（final17→final18 过渡）**：W99 D-26 修复已落地（`model.cljc` `:submitted`/`:not-found`→`:refused`；`materials_bridge` 5s 独立清扫 + dispatch 后清扫；CLJS 4/12 0F/0E；JVM 测试待 W100），树漂移 `0a62fd2c`、bundle `efbc465a` 落后，**final17 冻结暂停**；W100 重建 → final18 全量 + app-check 后重冻结；E2E 证据在 final18 前仅作诊断。
- **最终冻结（final18，W33/W100）**：bundle `f8c0f53acc14f237d0a76e44f12e9605a5c472542c4306f54e1439c01c26842a`（3,017,311 B，11:21:45；CSS `332f9fd3` 未变）+ 树 `6d1ad03b`（120 s 稳定，PRE=POST）；全量 **3816 tests / 24705 assertions / 0F / 0E / exit 0 / 355 s**；app-check `:ok true`/0.0.1/`:component-errors {}`；meta `plans/evidence/final18-freeze-meta.json`。D-26 sweep 已入产物（`setInterval(h_b,5E3)` + `install!` receipt-sweep-timer；bundle 内 setInterval 4 处）。final17 及更早作废；**E2E/视觉证据双记 `f8c0f53a` + `6d1ad03b`**；可复跑 E2E05/06/11/15。
- **final18 作废 → final19 准备中**：W106 完成 D-28 修复（set-materials-state stale 丢 receipt → move 卡 :submitting；merge-local-state + orphan drain + 5s sweep），D-27 验证通过；测试 106/783 + 10/67 0F/0E。W107 final19 全量 + app-check 后重冻结；E2E 等 final19，`f8c0f53a`+`6d1ad03b` 证据仅作诊断。
- **最终冻结（final19，W33/W107）**：bundle `168318c1`（3,017,514 B，12:42:52；CSS `332f9fd3` 未变）+ 树 `f8477191`（PRE=POST）；全量 **3818 tests / 24714 assertions / 0F / 0E / exit 0**；app-check `:ok true`/`0.0.1`/`:component-errors {}`；meta `plans/evidence/final19-freeze-meta.json`。final18 及更早作废；**E2E/视觉证据双记 `168318c1` + `f8477191`**。
- **并发策略变更（用户指令）**：不再要求全局单例锁/单执行者；多实例可并发跑测试与 E2E（独立 data-dir/profile/端口互不干扰）；E2E05/06/11/12/15 可并行；此前单执行者/锁窗口安排作废。证据仍双记 `168318c1` + `f8477191`。
- **final19 作废 → final20 准备中**：D-25 根因已由 W113 修复（cached-entry 键 `[nil epoch]` 致 `:context/compressions` 永久 `:applied`、delta 缺失；session-summary 加 `:revision`，394/2761 0F/0E）；W110 修 D-27/D-28；树已变（14:18–15:11），bundle `20f3b90f`（14:12:20）落后；W114 重建 → final20 全量 + app-check 后重冻结；E2E05/12 等 final20。
- **最终冻结（final20，W33/W114，已作废）**：bundle `87b0dee4cec24e7ffc720c96f25bafcb45fd73ca91b6444a030ed51e0fe6da94`（3,018,665 B，15:26:31；CSS `332f9fd3`、manifest `c2158f6b` 未变）+ 树 `5e301ce4`（PRE=POST 120 s 稳定）；全量 **3823 tests / 24731 assertions / 0F / 0E / exit 0 / 354 s**（`/tmp/hc-full-test-final20.log`）；app-check `:ok true`/0.0.1/`:component-errors {}`；聚焦定向 270+167 0F/0E；标记核对通过；**D-25/D-27/D-28 修复已入产物**；swarm_ui 2 断言失败为既有；meta `plans/evidence/final20-freeze-meta.json`。final19 及更早作废；**E2E/视觉证据双记 `87b0dee4` + `5e301ce4`**；**E2E05 final20 复跑 41/42 PASS、D-24/D-25 双关闭**（`plans/evidence/e2e-05/e2e05-87b0dee4.json`，双记 `87b0dee4`+`5e301ce4`；唯一 FAIL `outboundCounts` 为脚本正则伪阴性，面板 发送10/摘要0/排除0 正确）；E2E12 仍 PARTIAL（D-27 已修、D-28 move 撤销 5 项 FAIL，W117 unwrap-receipt 修复待 final21）。
- **final20 作废 → final21 准备中**：D-28 根因=`unwrap-receipt` 拒绝解包网关信封 `{:ok? :command-id :receipt {..}}` → `receipt-result` 只读外层 `:result=nil` → `:undo`/`:last-move` 缺失；修复（W117）=任何 `map? :receipt` 解包+merge+外层回填；测试 3824/24735 0F/0E。final20（`87b0dee4`+`5e301ce4`）作废转历史；W118 clean rebuild → final21 全量 + app-check 后重冻结；E2E12 等 final21 基线复跑。

- **final21 最终冻结（W33/W118，已作废）**：bundle `0fbddd3c`（3,018,796 B，16:34:21；CSS `332f9fd3`）+ 树 `319f035f`；E2E12 复跑 45/48，D-28 残留 3 项 move/undo（运行时状态链疑覆盖 `:last-move`），W123→W124 修复、W125 重建：D-28 真正根因=`transact-state!` 异步提交致 `sync-from-db!`/`apply-receipt-event!` 读旧 DB 覆盖 `:last-move`；修复=`current-with-local(db)`+6 处读取点（57/253+10/28 0F/0E）；**final21 作废**，W125→final22 重冻结。
- **最终冻结（final22，W33/W125）**：bundle `c9cbfdf6cec24e7ffc720c96f25bafcb45fd73ca91b6444a030ed51e0fe6da94`（3,018,831 B，2026-11-07 18:12:03；CSS `332f9fd3` 未变；manifest c2158f6b）+ 树 `1e824c83`（clean rebuild 确定性）；全量 **3824 tests / 24735 assertions / 0F / 0E / exit 0 / 354 s**；app-check `:ok true`/`0.0.1`/`:component-errors {}`。修复入产物已验证：`current-with-local` + `merge-local-state`（D-28）、`bridge/connection` + `bridge/command-allowed?`（E2E11 连接竞态）。标记核对通过（D-08 四标记、AbortController 19、hc-markdown、hc-message-stream、hc-protected-program 14、hc-round-context 2、session-scope 2、resolved-skills 2、speech 31、turn-selection 8、last-move 2）。meta `plans/evidence/final22-freeze-meta.json`。final21 及更早作废；**E2E/视觉证据双记 `c9cbfdf6` + `1e824c83`**。E2E final22：12 断言口径修正后 48/48 PASS（`e2e12-c9cbfdf6-final22-assertfix.json`，D-27/D-28 双关闭）、11 25/25 PASS（`e2e11-c9cbfdf6-final22.json`）、05 42/42 PASS（W115 复跑，证据 `e2e05-v8-c9cbfdf6-final22.json`）；E2E 统计 **22 PASS / 0 PARTIAL / 0 BLOCKED**。
## 3. 本轮相对 final15 的变更

| 变更 | 位置 | 说明 |
|---|---|---|
| W83 | `src/hc/**`（D-24/D-25） | 撤销压缩后 outbound 投影恢复被撤销原消息、不再带摘要；`压缩 已撤销` 标记渲染修复；测试 +5 断言增量 |
| W87 | `src/hc/**`（D-26） | `settings-update` 命令回执生效、`[:materials/settings]` pending 锁释放修复；E2E11 `suggest-switch-on` 阻断解除候选 |
| W89 | `test/hc/server/history/run_port_test.clj`（08:52 前后） | 第 94 行补 `}`、第 124 行删多余 `)` 定界符修复；该文件在 PRE 树 reader 报错阻断整套件 |
| bundle | `resources/public/js/main.js` | `a14d9abd`（3,015,902 B）→ `efbc465a`（3,016,559 B），08:43:18；CSS 不变 |

- final15 的 W79 客户端修复（E2E12 session-receipt）与 W71/W70 服务端修复仍在本树中。

## 4. 版本五处（本轮实测）

| 位置 | 值 |
|---|---|
| `src/hc/version.cljc:8` | `(def version "0.0.1")` |
| `pixi.toml:3` | `version = "0.0.1"` |
| `package.json:3` | `"version": "0.0.1"` |
| `--help` | `Herdchestra 0.0.1 server`，exit 0 |
| health（app-check） | `:version "0.0.1"`、`:ok true` |

**五处一致，均为 0.0.1。**

## 5. 历史对照

| 时间 | 树（src/test hash） | bundle | tests | assertions | F | E | exit | 墙钟 | 峰值 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 02:29 | `36fddd6d` | `72de4f9a` | 3795 | 24596 | 0 | 0 | 0 | — | 1.95 GiB |
| 04:34 | `79f21de9` | `732baf05` | 3805 | 24650 | 0 | 0 | 0 | 352.10 s | 未采样 |
| 05:54 | `651b9a45` | `a14d9abd` | 3808 | 24669 | 0 | 0 | 0 | 356 s | 2.05 GiB |
| **08:52** | **`99aa81a4`**（PRE `b9570d04`） | **`efbc465a`** | **3813** | **24695** | **0** | **0** | **0** | **353.44 s** | **未采样** |

- 前冻结（作废）：`651b9a45`（final15）、`79f21de9`（final14）、`36fddd6d`、`7dfcf0d7`、`ab801450`、`6aa694f1`、`e465f626`、`d1d8d08d`、`a97c68f7`、`7667323a`、`0c05b7d1`、`ea9e5732`、`8b210896`、`55e4e8ea`、`dd968793`；`2b269e0d` 与 `732baf05`/`a14d9abd` 两个旧 bundle 均已被覆盖。

## 6. 结论

1. **全量测试在最终冻结 bundle `efbc465a` / 源码树 `99aa81a4` 上 PASS**：3813 tests / 24695 assertions / 0F / 0E / exit 0 / 353.44 s，seed 171 条逐条记录，无新 `hs_err`。
2. **PRE≠POST 已说明**：差异仅 `run_port_test.clj` 两处定界符修复（修复前该文件 reader 报错阻断整套件），测试不进 bundle；bundle `efbc465a` 在 PRE/POST 两侧不变。
3. **app-check PASS**：exit 0、`:ok true`、`:version "0.0.1"`、`:component-errors {}`、store `:open? true`。
4. **版本五处一致 0.0.1**。
5. **final16b 同树补跑 PASS**：W92 3813/24695/0F/0E/355 s 与 final16 一致，冻结成立（09:10:00–09:15:55，峰值 2.28 GB）。
6. 本报告只覆盖全量测试、启动自检与版本核对；**E2E/视觉/运行时证据必须同时记录 bundle `c9cbfdf6` 与源码树 `1e824c83`**（更旧 hash 均为前冻结）。**D-22/D-23 已由 W76 final15 复跑确认修复；D-24/D-25/D-26/D-27/D-28 均已由 final22 复跑确认关闭（E2E05 42/42 PASS、E2E11 25/25 PASS、E2E12 断言口径修正后 48/48 PASS）；E2E 22 条全部 PASS。** 发布仍需工作树/提交干净、发布说明等门禁（见 `plans/release-gate-checklist-0.0.1.md`）。

> **更正（final22 后）**：以上 §6 前半为 final16（`efbc465a`/`99aa81a4`）历史基线；当前最终冻结为 final22 `c9cbfdf6`/`1e824c83`（3824/24735/0F/0E/354 s），§6 第 6 条已按 final22 E2E 证据更新。
