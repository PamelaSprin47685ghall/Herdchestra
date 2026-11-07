# final17 E2E run5 证据说明与裁定（2026-11-07，W98）

## 运行

- 执行者：W98（w98-e2e05-06-15-final17），脚本 `/tmp/hc-w3e5r4/w76-run5.sh`（E2E05→E2E15→E2E06 text leg 串行，flock `/tmp/hc-app-single.lock`）。
- 窗口：2026-11-07 10:31:14–11:09:40（+08:00），RUNNER_EXIT=0；三段各自全新 data-dir/profile（config05w76b/config06w76b、chrome05w76b/chrome15w76b）。
- 基线：bundle `efbc465adf88139896949461fe0c10469bb3f3c2d5802de3ec7262473f61cd7f`（3,016,559 B，mtime 10:11:35；CSS `332f9fd3`/`77da0dd4`）。

## 结果

| 段 | 结论 | 要点 |
|---|---|---|
| E2E05 | ok=false，PARTIAL 维持 | sessionId 正常取得、9 phases 全跑到 after-undo；撤销段未执行（undoMissing：重选后面板处"本轮上下文 · 预览"零值态，无"撤销压缩"按钮），undoneMarked/undoSendsRestoredHistory/undoDropsSummary FAIL → D-24/D-25 无法判定；outboundCounts=false 系脚本 `/排除/` 正则先命中"归档排除：0"的伪阴性（真实面板 发送消息10/摘要1/排除6/含压缩摘要 均正确） |
| E2E15 | PASS | pass=true，elapsedMs=1003338，pageErrors []；bundle 字段实测 efbc465a；regen→v2→版本切换→旧朗读停止→切回重播全断言 PASS |
| E2E06 text leg | ok=false（与前轮一致） | extractRouteAvailable=false、"准备失败 · 文字提取失败"；与 final14/final16 逐字段一致，三轮复现，裁定为 OCR 环境限制注记、不判产品回归（W30 口径维持） |

## 树哈希漂移裁定（Main/W33，2026-11-07）

- run 前树哈希 = `99aa81a4…`（= final17 冻结树）；run 后 = `0a62fd2c…`。
- 漂移来源已确认为 **W99 的 D-26 修复**：`src/hc/client/workbench/materials_bridge.cljs`、`src/hc/client/materials/model.cljc` + 3 个 test 文件（11:02–11:10 修改），非意外。
- bundle `main.js` 未变（efbc465a，mtime 10:11:35），服务端 `.clj` 未动 → 本轮 E2E 行为不受影响。
- **裁定：final17 证据绑定「运行起点树」`99aa81a4` + bundle `efbc465a`**；磁盘树后续由 W99 完成 → final18 重建 + 全量 + 重冻结，E2E05/E2E11 在 final18 复跑。

## 后续

- E2E05 撤销段：w93-v8（DOM fallback + reuse-blank + takeover claim）补跑，判定 D-24/D-25；final18 冻结后再复跑确认。
- E2E06：保持环境限制注记，不判回归。
- E2E15：版本切换（编辑产生 v2 的回退路径）证据仍绑前冻结 `5addfe1d`；实时麦克风 BLOCKED（环境无音频输入）。

## 证据清单

- `e2e-05/e2e05-efbc465a.{json,png,run.log,bundle.txt}`、`tree-efbc465a-{before,after}.txt`、`w76-run5-final17.log`
- `e2e-05-06/e2e06-textleg-efbc465a.{json,png,run.log,bundle.txt}`
- `e2e-13-15-16-21/e2e15-v6-regen-efbc465a.{json,png,run.log}`
- 原始产物：`/tmp/hc-w3e5r4/evidence-final/`、`/tmp/hc-w3e5r4/evidence/e2e05.{json,png}`

---

# 追记：final18 复跑（2026-11-07 11:40–12:18，W98 同脚本 w76-run5.sh）

final18 冻结（w33 PASS）：bundle `f8c0f53acc14f237d0a76e44f12e9605a5c472542c4306f54e1439c01c26842a`（3,017,311 B，11:21:45；CSS `332f9fd3`）+ 树 `6d1ad03b`（3816/24705/0F/0E/355 s；app-check ok）。final17 及更旧作废。

- E2E05（11:40:50–12:00:50）：ok=false。sessionId=null 复现（间歇缺陷），prepared/applied/continued-twice/new-session 全 PASS（30 checks，仅 outboundCounts 伪阴性），new-session 后 `selectSession(null)` 定位 `[data-session-id="null"]` 超时中止；撤销段未执行，D-24/D-25 无法判定 → w93/w101 v8 在 final18 补。
- E2E15（12:00:50–12:17:33）：PASS，pass=true/1003 s/pageErrors[]，bundle 实测 f8c0f53a，regenCount=1、v1↔v2 断言齐全。
- E2E06 text leg（12:17:48–12:18:11）：ok=false，与前四轮完全一致（extractRouteAvailable=false、"文字提取失败"），OCR 环境限制注记维持。
- 树漂移：run 前=6d1ad03b（=冻结），run 后=cb08d0fa；11:54–12:18 src/hc/client/{workbench/transport,conversation/*,workbench/conversation_bridge,workbench/mutations,workbench/materials_bridge} 与 test 并发修改（W93/W101/W104/W105 修复活动）。bundle 未变，证据有效绑运行起点树 6d1ad03b。
- 证据：`e2e-05/e2e05-f8c0f53a.*`、`tree-f8c0f53a-{before,after}.txt`、`w76-run5-final18.log`；`e2e-05-06/e2e06-textleg-f8c0f53a.*`；`e2e-13-15-16-21/e2e15-v6-regen-f8c0f53a.*`。
