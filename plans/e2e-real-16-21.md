# Herdchestra E2E 16 / 21 实跑报告（2026-11-05）

> 范围：真实 app + 真实 Chrome + 真实本机 Ollama（`qwen3.6:27b`）。
> 工装与证据在 `/tmp/hc-e2e-harness/e2e16/`。未修改产品源码；未执行
> git 写操作。
>
> 结论：E2E 16 **PARTIAL / BLOCKED**（任务创建、预览、手动记录、时区
> 跟随有真实证据；错过/重叠/导入/平台未跑）。E2E 21 **BLOCKED / NOT RUN**
> （诊断页可达，但没有受控的三口径用量与价格场景）。

## 1 E2E 16 定时任务

### 准备

```bash
/tmp/hc-e2e-harness/run_app.sh --name e2e16 \
  --data-dir /tmp/hc-e2e-harness/e2e16/data \
  --workspace-root /tmp/hc-e2e-harness/e2e16/work --model-config
```

真实 Chrome；设置 → 定时任务 → 新建任务。两个任务都选
`Local Ollama (E2E) / qwen3.6:27b`、每天 08:30、保存为暂停。

### 已做与断言

1. **跟随设备任务** `E2E16 跟随设备 08:30`
   - 保存前预览显示：
     `每天 08:30 (Asia/Shanghai)`，
     `2026-11-06 08:30（Asia/Shanghai +08:00）`、
     `2026-11-07 08:30`、`2026-11-08 08:30`。
   - 保存为暂停成功；卡片显示“已暂停 / 下次运行：已暂停，不会自动开始 /
     跟随设备时区（Asia/Shanghai）”。
2. **固定时区任务** `E2E16 固定上海 08:30`
   - 时区固定 `Asia/Shanghai`；预览同样显示未来三次真实日期与
     `+08:00`。
   - 保存为暂停成功；卡片显示“固定时区（Asia/Shanghai）”。
3. **暂停后立即运行**
   - 点击“立即运行”出现确认框，明确“创建独立手动运行记录；不改变原定
     下次时间，也不把暂停任务重新启用”。
   - 确认后生成运行记录 `run-1`：
     `{:trigger :manual :status :needs-attention
       :scheduled-local "2026-11-05T12:00:22.319"}`。
   - 任务仍为 `:paused`；失败原因是
     `SCHEDULE_INCOMPLETE "schedule is missing its frozen model or execution
     configuration"`，不是手动记录改写了计划。该失败是产品/表单缺口。
4. **设备时区变更（浏览器 CDP 模拟，不是 OS 重启）**
   - 用 CDP `Emulation.setTimezoneOverride America/New_York` 后重新加载。
   - 页面提示“时区已变更为 America/New_York；1 个跟随设备任务已重新计算
     下次运行”。
   - store 验证：
     - 跟随设备：`:timezone {:mode :follow-device :zone "America/New_York"}`，
       `:next-run 1793885400000`；
     - 固定上海：`:timezone {:mode :fixed :zone "Asia/Shanghai"}`，
       `:next-run 1793925000000`。
   - 两者下一次时间不同，固定时区未随设备时区改变。
5. **暂停/下次计划**
   - 两个任务 store 均为 `:state :paused`；跟随设备仍有 `:next-run`
     （恢复后计算值），固定任务也有 `:next-run`；手动运行未把任务改为
     `:enabled`。

### 补齐的断言

1. **错过不补跑（PASS）**
   - 受控时钟注入：停 app 后直接用 store 事务把启用任务
     `E2E16 错过测试` 的 `:next-run` 设为当天 08:30（已过去），并写入完整
     `:snapshot`（含 `:model` 与 `:capability-scope`）；重启 app。
   - 重启后卡片显示：
     - `已启用`；
     - `下次运行 2026-11-06 08:30 (Asia/Shanghai, +08:00)`（顺延到未来）；
     - `最近结果 未运行：应用未运行`；
     - `最近一次：未运行：应用未运行 · 2026-11-05 08:30`。
   - store 中新增 `run-3`：`:status :missed :trigger :scheduled
     :reason :process-restart`；没有生成模型请求或新执行 run。
   - 证据：`missed-ui2.json/png`、`verify-missed-overlap.log`。
2. **恢复/导入默认暂停（PASS，历史保留）**
   - 用 `hc.server.backup.cli backup` 备份含启用任务的实例，恢复到
     `B-data`，启动 B。
   - B 的 store：全部 schedule `:state :paused`、`:next-run nil`。
   - 0 个**新** run；历史 run 仍保留（`run-1` needs-attention、
     `run-3` missed、`run-overlap` interrupted）。这不是“清空历史”，
     但满足“恢复后默认暂停、不自动运行”。
   - 证据：`backup.log`、`restore.log`、`B-ui.json/png`、`verify-B.log`。
3. **重叠跳过（NOT PASS / BLOCKED）**
   - 受控注入一个 `:state :enabled`、`next-run` 已到期的任务，并挂一个
     `:status :running` 的 `run-overlap`；重启 app。
   - 实际结果不是“已跳过，上次仍在运行”，而是：
     - `run-overlap` 被恢复为 `:status :interrupted :reason :process-restart`；
     - 到期 occurrence 记为 `:missed`（`run-3`），卡片显示
       `未运行：应用未运行`。
   - 结论：重启恢复路径优先把运行中 run 中断，未走到“重叠跳过”分支。
     要在真实 app 中验证“已跳过”，需要应用不重启、在 scheduler tick
     时仍有 live run；当前 UI 没有 tick 按钮，本轮 NOT PASS。
4. **受限平台文案（NOT RUN）**
   - 桌面无法真实模拟 iOS/Android；未执行。
   - 代码位置：`src/hc/client/schedule/platform.cljs:28-47` 平台判定；
     `src/hc/client/schedule/model.cljc:54-61` iOS `:manual-only? true`；
     `src/hc/client/schedule/view.cljs:233-240,605-613` “仅支持前台手动
     运行”文案与徽标。

### 仍未通过

- 手动运行 `run-1` 仍是 `:needs-attention`，原因
  `SCHEDULE_INCOMPLETE "schedule is missing its frozen model or execution
  configuration"`；表单保存的任务缺 `:capability-scope`，不能启用/恢复。
- 重叠跳过未取得“已跳过，上次仍在运行”记录（见上）。
- 受限平台 NOT RUN。

### 证据

- `/tmp/hc-e2e-harness/e2e16/create-follow.json`
- `/tmp/hc-e2e-harness/e2e16/create-fixed.json`
- `/tmp/hc-e2e-harness/e2e16/manual-tz.json`
- `/tmp/hc-e2e-harness/e2e16/verify-schedule.log`
- `/tmp/hc-e2e-harness/e2e16/follow-preview.png`
- `/tmp/hc-e2e-harness/e2e16/fixed-preview.png`
- `/tmp/hc-e2e-harness/e2e16/follow-saved.png`
- `/tmp/hc-e2e-harness/e2e16/fixed-saved.png`
- `/tmp/hc-e2e-harness/e2e16/tz-after.png`
- `/tmp/hc-e2e-harness/e2e16/missed-ui2.json`
- `/tmp/hc-e2e-harness/e2e16/missed-ui2.png`
- `/tmp/hc-e2e-harness/e2e16/overlap-ui.json`
- `/tmp/hc-e2e-harness/e2e16/overlap-ui.png`
- `/tmp/hc-e2e-harness/e2e16/B-ui.json`
- `/tmp/hc-e2e-harness/e2e16/B-ui.png`
- `/tmp/hc-e2e-harness/e2e16/backup.log`
- `/tmp/hc-e2e-harness/e2e16/restore.log`
- `/tmp/hc-e2e-harness/e2e16/verify-missed-overlap.log`
- `/tmp/hc-e2e-harness/e2e16/verify-B.log`

### 结论

**PARTIAL**。错过不补跑 PASS；恢复默认暂停 PASS（历史 run 保留，0 个新
run）；创建/预览/暂停/时区跟随 PASS。重叠跳过 NOT PASS（重启恢复把
running run 中断并记 missed，未出现“已跳过，上次仍在运行”）；受限平台
NOT RUN；表单任务仍因缺 `:capability-scope` 无法启用。

## 2 E2E 21 费用口径

### 受控代理

`/tmp/hc-e2e-harness/e2e21/proxy.js` 位于 app 与 Ollama 之间：

- 第 1 个 `qwen-priced` 请求返回注入的 `devops` tool_call（带
  `:scope {:kind :reset-scope ...}`），usage `100/20/120`；
- `qwen-unknown` 请求返回文本，删除 `usage`（服务未返回用量）；
- 后续 `qwen-priced` 续答返回文本，usage `50/10/60` 且
  `prompt_tokens_details.cached_tokens=30`；
- 记录每个请求的模型、时间、是否有 usage。

两个连接/模型：`qwen-priced`（main）与 `qwen-unknown`（task）。本地
SearXNG stub 搜索照常启用。真实 app + 真实 Chrome + 真实 Ollama 端点；
受控请求的 completion 由代理生成以保证 usage 形状，报告如实标注。

### 实际 store 数据

`/tmp/hc-e2e-harness/e2e21/evidence/usage-table.md`：

| # | request-id | model | input | output | cache-read | total | provenance |
|---|---|---|---|---|---|---|---|
| 1 | attempt-...--2 | qwen-priced | 100 | 20 | unknown | 120 | reported |
| 2 | attempt-...--101 (代表 60 条) | qwen-priced | 50 | 10 | 30 | 60 | reported |
| 3 | plan-worker-1-call-1/tool-0-1-1 | worker | unknown | unknown | unknown | unknown | unknown |

- 62 条 `qwen-priced` 请求记录；1 条 worker 全未知记录；1 次用户发送；
  1 次 `devops` 工具调用（真实 tool result map）。
- **未知不为 0**：worker 记录明确是 `:input-tokens :unknown`、
  `:output-tokens :unknown`、`:total-tokens :unknown`。
- **缓存不重复加总**：`src/hc/server/diagnostics/usage.clj:118-174` 在
  `:input-subset` 模式下把 cache-read/write 作为明细，不加入
  `total-tokens`；代表行 50+10=60，不是 90。
- **三口径**：服务返回（reported）与未知（unknown）在 store 成立；
  估算（estimated）未成立，因为价格表单不可达（见下）。

### 真实页面结果（2026-11-05 复跑）

client-audit 修复后，真实 Chrome 的“设置 → 数据中心 → 用量与费用”可以
渲染，`crashed=false`。页面显示：

- 用户发送次数 `1`；模型请求次数 `73`；工具调用次数 `1`；活跃天数 `1`。
- 请求用量来源：`服务返回 72；估算 0；未提供 1`。
- Token 汇总表：输入/输出/缓存/总量均为“未提供”；说明
  “存在未提供字段：input-tokens、output-tokens、cache-read-tokens、
  cache-write-tokens。总量保持未知，不按 0 计算。”
  这是因为汇总要求每个字段在所有请求上都已知；1 条 worker unknown
  记录使字段总量保持 unknown。
- 估算费用：`估算覆盖 0 / 73；部分估算 0；未估算 73；服务实收 0`。
- 模型分布：`qwen-priced 72 次请求 未估算`、`未知模型 1 次请求 未估算`。
- 缓存规则文案存在：“缓存命中与缓存写入只作为输入明细；在
  :input-subset 口径下不会再次加进总量。”

### 价格表单阻塞（产品缺陷）

“模型价格”表单的“服务”下拉只有“选择服务”，没有 `priced-conn`/
`qwen-priced` 服务项；“模型”下拉有 `qwen-priced`。没有服务就无法保存
价格（校验要求选择服务），因此：

- 无法设置一个估算单价模型、一个未知单价模型；
- 无法验证“改单价后旧记录保留原依据，主动重算才生成新视图”；
- 估算口径仍为 0。

定位：`src/hc/server/app/system.clj:2939-2940` 的 diagnostics
`identity-fn` 从模型请求 snapshot 取 `:service-id`/`:service-name`：

```clojure
(select-keys (:snapshot request)
             [:session-id :assistant-id :model :service-id
              :service-name :purpose :temporary?])
```

模型请求 snapshot 实际携带的是 `:connection`/`:model`（连接与模型），
没有 `:service-id`；所以 diagnostics 请求记录缺服务身份，统计的
`:services` 为空，价格表单无法选服务。最小修复：在 `identity-fn` 里由
当前 session 的 `:model-binding`/连接配置派生 `service-id` 与
`service-name`，或让模型端口把连接 ID 写进 snapshot。

### 三口径结论

- 服务返回：成立（72 条，store 有 `:coverage :final` 的 usage）。
- 未知：成立（1 条 worker，全字段 `:unknown`，不是 0）。
- 估算：**未成立**，价格表单无服务可选项；E2E 21 仍 BLOCKED。

### 缓存是否重复

按 store + `src/hc/server/diagnostics/usage.clj:118-174`：缓存命中/写入
在 `:input-subset` 模式下是输入明细，不加入 `total-tokens`；代表记录
50 输入 / 10 输出 / 30 缓存，总量 60，不是 90。页面因 1 条 unknown
记录使汇总字段保持 unknown，未显示该 60；不能据此说页面已通过。

### 证据

- `/tmp/hc-e2e-harness/e2e21/proxy.js`
- `/tmp/hc-e2e-harness/e2e21/config.edn`
- `/tmp/hc-e2e-harness/e2e21/evidence/usage-table.md`
- `/tmp/hc-e2e-harness/e2e21/evidence/all-diag.log`
- `/tmp/hc-e2e-harness/e2e21/evidence/verify-diag2.log`
- `/tmp/hc-e2e-harness/e2e21/evidence/diag2.json`
- `/tmp/hc-e2e-harness/e2e21/evidence/diag2.png`
- `/tmp/hc-e2e-harness/e2e21/evidence/002-request.json`
- `/tmp/hc-e2e-harness/e2e21/evidence/003-request.json`
- `/tmp/hc-e2e-harness/e2e21/evidence/039-priced-cache.response.txt`
- `/tmp/hc-e2e-harness/e2e21/diag3.json`
- `/tmp/hc-e2e-harness/e2e21/diag3.png`
- `/tmp/hc-e2e-harness/e2e21/price-probe2.json`

### 结论

**BLOCKED（价格表单缺服务身份）**。诊断页已修复并可渲染；服务返回 72、
未知 1、估算 0。未知不为 0；缓存按 input-subset 不重复。估算口径与
价格变更历史无法验证，因为 diagnostics 请求记录缺 `:service-id`，
价格表单“服务”下拉为空（`src/hc/server/app/system.clj:2939-2940`）。
不能标 PASS。

## 3 总结

| E2E | 结论 | 关键阻塞 |
|---|---|---|
| 16 | PARTIAL | 错过不补跑 PASS；恢复默认暂停 PASS；重叠跳过 NOT PASS；受限平台 NOT RUN；表单缺 capability-scope |
| 21 | PASS（三口径/估算/缓存），金额渲染缺陷 | reported 58、unknown 17、estimated 58，USD 0.003275；未知不为 0；缓存 30 不重复；页面显示 `[TaggedValue: f, ...]`；改价历史未验证 |

未覆盖：E2E 16 的重叠跳过（重启恢复优先中断）、受限平台；E2E 21 的
估算单价、页面三列表格、价格变更历史不变。

## 4 E2E 16 修复复验（2026-11-05 追加）

> 真实 app + Chrome；本地 Ollama `qwen3.6:27b`；新建任务通过 UI 选择
> 本地模型，保存并启用，再“立即运行”。

### 手动运行

- UI 创建 `E2E16 修复验证`，每天 08:30、跟随设备时区、本地模型；
  `保存并启用` 成功，卡片显示“已启用 / 下次运行 2026-11-06 08:30”。
- 点击“立即运行”并确认：
  - 不再出现 `SCHEDULE_INCOMPLETE`；
  - 页面提示“已开始手动运行；它是一条独立记录，不改变原定下次时间”；
  - 任务保持 `:state :enabled`，下次运行仍为 2026-11-06 08:30；
  - run 记录 `run-1`：`:trigger :manual`，但 `:status :failed`，
    `:reason :start-dispatch-failed`，不是 `:preparing`。
- store 中任务与 run 的 snapshot：
  `:model` 与 `:execution` 存在，但 **`:capability-scope nil`**。
  因此“snapshot 含 :capability-scope”断言 **未通过**；手动运行仍失败，
  只是错误从 `SCHEDULE_INCOMPLETE` 变成 `start-dispatch-failed`。

### 重叠跳过

- 受控注入 `E2E16 重叠修复`：enabled + next-run 已到 08:30 + 已有
  `run-overlap :status :running`，snapshot 含 capability-scope。
- 重启 app 后 UI/Store：
  - `run-overlap` → `:status :interrupted :reason :process-restart`；
  - 到期 occurrence → `run-2 :status :missed :reason :process-restart`；
  - 卡片显示“未运行：应用未运行”；
  - **没有** `:skipped :previous-still-running`。
- 对照：无 running 时同样是 `:missed :process-restart`；两者没有区分，
  重叠跳过语义仍未实现/未触发。

### 结论

**PARTIAL，两项修复均未达到断言**：

- 手动运行：`SCHEDULE_INCOMPLETE` 消失，但 `start-dispatch-failed`；
  snapshot `:capability-scope` 仍为 nil。
- 重叠：running run 被重启恢复为 `:interrupted`，occurrence 记
  `:missed`，没有 `:skipped :previous-still-running`。

### 证据

- `/tmp/hc-e2e-harness/e2e16fix/create-enable.json`
- `/tmp/hc-e2e-harness/e2e16fix/manual.json`
- `/tmp/hc-e2e-harness/e2e16fix/overlap-ui.json`
- `/tmp/hc-e2e-harness/e2e16fix/verify.log`
- `/tmp/hc-e2e-harness/e2e16fix/verify-overlap.log`
- `/tmp/hc-e2e-harness/e2e16fix/create-enable.png`
- `/tmp/hc-e2e-harness/e2e16fix/manual.png`
- `/tmp/hc-e2e-harness/e2e16fix/overlap.png`

## 2.1 E2E 21 收口（2026-11-05，服务身份修复后）

### 页面结果

- 服务下拉现在有 `E2E21 Priced`（service-id `priced-conn`），模型下拉有
  `E2E21 Priced / qwen-priced`。
- 保存价格：服务 `priced-conn`、模型 `qwen-priced`、USD、输入 1、
  输出 2、缓存命中 0.5、缓存写入 0，价格来源 `E2E21 controlled`，
  来源日期 2026-11-05。store 中价格文档存在。
- 刷新/重启后页面显示：
  - `请求用量来源：服务返回 58；估算 0；未提供 17`；
  - `估算覆盖 58 / 75；部分估算 0；未估算 16；服务实收 0`；
  - `币种 USD 估算金额 [TaggedValue: f, 0.003275]`；
  - 模型分布：`qwen-priced / E2E21 Priced 74 次请求 USD [TaggedValue:
    f, 0.003275] 估算`，`未知模型/未知服务 1 次请求 未估算`；
  - 用户发送 1、模型请求 75、工具调用 1。
- 未知用量没有被显示为 0：Token 表保持“未提供”，并写明“总量保持未知，
  不按 0 计算”；未估算请求 16。

### 数值对照

| 请求 | usage 原文 | 页面显示 | 期望 |
|---|---|---|---|
| 1 次 priced tool 请求 | 100 input / 20 output | 计入 58 条 reported | 100×1 + 20×2 = 140 单位 |
| 57 次 priced cached 请求 | 50 input / 10 output / 30 cache-read | 计入 58 条 reported；cache 作为输入明细 | uncached 20×1 + cache 30×0.5 + output 10×2 = 55 单位/条 |
| 合计 58 条 | — | USD 0.003275 | 140 + 57×55 = 3275 单位 = 0.003275 USD |
| 17 条 unknown/失败 | usage 缺失或 worker unknown | 未提供 17；未估算 16 | 不显示 0 |

计算核对：`140 + 57×55 = 3275`，页面 `0.003275` 完全一致；缓存 30
没有再次加进 50 输入总量，否则会变成 90 而金额不同。

### 三口径结论

- **服务返回**：成立（58 条 usage reported）。
- **估算**：成立（58 条有价格覆盖，USD 0.003275），但页面金额渲染为
  `[TaggedValue: f, 0.003275]`，不是纯数字；这是展示缺陷。
- **未知**：成立（17 条 unknown，未估算 16，不显示 0）。

### 仍未验证

- 修改单价后旧历史保留原依据、主动重算才生成新视图：本轮没有再次改价
  对比；价格文档与历史请求的 `:price` 快照关系未验证。
- `[TaggedValue: f, ...]` 金额渲染缺陷需要修复为普通十进制数。

### 证据

- `/tmp/hc-e2e-harness/e2e21/diag-price.json`
- `/tmp/hc-e2e-harness/e2e21/evidence/price-final.png`
- `/tmp/hc-e2e-harness/e2e21/evidence/all-diag.log`
- `/tmp/hc-e2e-harness/e2e21/evidence/usage-table.md`
- `/tmp/hc-e2e-harness/e2e21/proxy.js`
- `/tmp/hc-e2e-harness/e2e21/config.edn`

## 2026-11-05 14:1x E2E 21 tagged 金额

- 根因：transit `#f 0.003275` 在客户端解码为 `TaggedValue`，`diagnostics/view/format-amount` 直接 `(str amount)`，输出 `[TaggedValue: f, 0.003275]`。
- 修复：`diagnostics/model` 新增 `tagged-rep`/`unwrap-amount`/`amount-number`/`format-amount-number`；cost rows、service cost、`cost-summary-label` 统一解包；`format-amount` 输出 `USD 0.003275`，普通数值保留原值，非数值显示“未知”。
- 测试：model tagged 解包；UI 渲染断言 `USD 0.003275`、`EUR 0.02`、`GBP 未知` 且无 `TaggedValue`；诊断 UI 19 tests / 120 assertions，0 failures；全量 client 701/4319。
- 真实 Chrome 数据中心复跑被服务端 `command state could not be committed`/`统计读取失败` 阻塞（data3/data4 均复现），非金额渲染路径；待服务端命令门修复后复跑。
