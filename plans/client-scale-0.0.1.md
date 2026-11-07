# 客户端发布验证：无模型提示与 §8.5 规模基准（0.0.1）

日期：2026-11-05
范围：真实 release bundle + 空数据目录 + 真实浏览器；大规模数据用最小 jsdom/纯投影复现脚本（见“限制”）。
未改产品源码，未做 git 操作。

> 后续（2026-11-05）：E2E 01–22 已另跑，见
> `plans/e2e-0.0.1-results.md`；本文只保留客户端规模与无模型门槛的结论。

## 环境

| 项 | 值 |
|---|---|
| 主机 | Linux JYSZ 7.3.0-rc6-1-MANJARO x86_64，12 vCPU，62 GiB RAM |
| JVM | OpenJDK 27（`clojure -M:server`） |
| Clojure CLI | 1.12.5.1664 |
| Node | v26.10.0 |
| Chrome | Google Chrome 153.0.8010.36（headless=new） |
| 客户端产物 | `resources/public/js/main.js`，`npm run release` EXIT 0，2,879,316 bytes（08:54:23） |
| 服务端 | `127.0.0.1:7581`，`--data-dir /tmp/hc-scale/data`，`--workspace-root /tmp/hc-scale/work`，`--static-root resources/public` |
| 隔离 | `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/tmp/hc-scale-tmp`，避开机器级 singleton 锁 |
| 模型 | health `:models {:configured? false}` |

启动命令：

```bash
JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/tmp/hc-scale-tmp' \
clojure -M:server -m hc.server.app \
  --host 127.0.0.1 --port 7581 \
  --data-dir /tmp/hc-scale/data \
  --workspace-root /tmp/hc-scale/work \
  --static-root resources/public
```

## 结果总表

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 未配置模型发送提示，不假模型/不静默回退 | **PASS** | `plans/evidence/no-model/` |
| 2 | 1,000 条会话列表打开 | **PASS（jsdom 渲染）**；真实浏览器滚动 **NOT RUN** | `plans/evidence/scale-dom/list.log` |
| 3 | 单会话 2,000 条活动打开 | **PASS（jsdom 渲染）** | `plans/evidence/scale-dom/messages.log` |
| 4 | 恢复阅读位置 | **FAIL** | `plans/evidence/scale-dom/messages.log`（`restore.scrollTop=0`） |
| 5 | 向前查找 | **NOT RUN（无入口）** | `find-controls=0`；见缺陷 2 |
| 6 | 两万字中英/代码混排草稿编辑、选区、撤销 | **PASS** | `plans/evidence/scale-draft/` |
| 7 | 崩溃恢复（reload） | **PASS** | `plans/evidence/scale-draft/scale-draft.json` |
| 8 | 纯模型投影 1,000/2,000 | **PASS** | `plans/evidence/scale-dom/pure-bench.log` |

## 1. 无模型提示（PASS）

真实 release bundle，空数据目录，`#/sessions`。浏览器通过 CDP 连接，创建/选中会话后：

- 会话头模型按钮：`模型：尚未选择模型`（aria-label `打开设置选择模型`）。
- 输入区提示：`Enter 发送 · Shift+Enter 换行 · 先选择一个可用模型，草稿已保留`。
- 发送按钮 `disabled`，`aria-disabled=true`，`aria-label=发送消息`。
- 输入“无模型发布验证草稿”并按 Enter：textarea 保留草稿；`messageCount=0`、`commandRows=0`；无假模型、无静默回退。
- 页面文本包含 `先选择一个可用模型` 与 `尚未选择模型`。

证据：

- `plans/evidence/no-model/no-model.png`（截图）
- `plans/evidence/no-model/no-model.json`（DOM 探测结果）
- `plans/evidence/no-model/no-model-dom.html`（完整 DOM）

命令：

```bash
node test/js/no_model_probe.cjs \
  --url 'http://127.0.0.1:7581/#/sessions' \
  --out plans/evidence/no-model
```

## 2. 1,000 条会话列表（PASS，jsdom）

合成 1,000 个 session（真实 `model/sessions`/`views.session` 渲染路径）：

- 初次挂载渲染：**494 ms**（jsdom，含 shell + 列表）。
- 列表行数：**1,000**，全部挂载（无虚拟化）。
- 列表 `scroll` 事件处理：**1.12 ms**。
- jsdom 堆：**231 MB**（`heapUsed`）。
- 纯投影 `model/sessions`：min 3.05 / median 4.76 / max 8.99 ms（JVM，10 次）。

限制：jsdom 没有布局/绘制；滚动耗时只是事件处理成本，不代表真实 paint。真实浏览器 1,000 行数据未灌入（空数据目录），滚动 **NOT RUN**。

## 3. 单会话 2,000 条活动（渲染 PASS）

合成 2,000 条消息（多版本字段、附件、表格、代码块、工具结果、引用），真实 conversation surface 渲染：

- 初次挂载渲染：**1,956 ms**（jsdom）。
- 消息节点：**2,000**（`[data-message-id]`）。
- 消息区 `scroll` 事件处理：**2.06 ms**。
- jsdom 堆：**760 MB**（`heapUsed`）。
- 纯投影 `message-order`：median 1.18 ms；`messages-for-session`：median 1.46 ms；`session-sources`（400 条）：median 3.67 ms（JVM）。

瓶颈：消息列表一次性挂载全部 2,000 个消息节点，没有窗口化/虚拟列表；jsdom 堆与渲染时间随节点数线性上升。真实浏览器 2,000 条未灌入，因此 paint/内存数据 **NOT RUN**。

## 4. 恢复阅读位置（FAIL）

jsdom 中把消息区 `scrollTop` 设为 12,345，切到 s1 再切回 s0：

- 返回后 `scrollTop = 0`，期望 12,345。
- 返回重渲染耗时 1,827 ms（2,000 条消息）。

结论：会话切换没有按会话保存/恢复滚动位置；现有 `conversation/view.cljs` 只有流式跟随与“返回最新内容”，没有 scrollTop 存储/恢复。缺陷见下。

## 5. 向前查找（NOT RUN，无入口）

DOM 中没有 in-page find 控件（`input/button[aria-label*='查找']`、`placeholder*='查找'` 均为 0）。现有“搜索”是联网搜索 `search-view`，不是会话内查找。因此“向前查找”无法执行；这是功能缺失，不是性能失败。

## 6. 两万字草稿（PASS）

真实浏览器，20,000 字符中英 + 代码混排（含 ```js 片段）：

| 指标 | 结果 |
|---|---|
| 插入 20,000 字符 | 3,794 ms（CDP `Input.insertText`，含协议与浏览器输入路径，上界） |
| 设置选区 100–200 | < 1 ms |
| Ctrl+Z 撤销 | 31 ms；长度 20,000 → 0（原生 undo 生效） |
| 350ms 去抖后 localStorage | 22,037 bytes；无“草稿暂未保存” |
| reload 后草稿 | 20,000 字符恢复，首段 ` ```js\nconst x = 1; ` |
| 浏览器 JS 堆 | 17.7 MB；节点 12,652 |

证据：

- `plans/evidence/scale-draft/scale-draft.png`
- `plans/evidence/scale-draft/scale-draft.json`

命令：

```bash
node test/js/scale_draft_probe.cjs \
  --url 'http://127.0.0.1:7581/#/sessions' \
  --out plans/evidence/scale-draft
```

## 7. 缺陷

1. **没有按会话恢复阅读位置** — `src/hc/client/conversation/view.cljs:1566-1573` 只有流式自动跟随；`:1820-1830` 的 `onScroll` 只更新 `follow?`/`at-bottom?`/`show-jump?`；`:1875` 只跳到底部。没有“切走前保存 scrollTop、切回后恢复”的存储与 effect。影响：2,000 条会话切回后回到顶部，违反 §8.5/§4.9.2 的阅读位置恢复。
2. **没有会话内查找入口** — `src/hc/client/workbench/views/session.cljs:288-301` 只有发送与联网搜索；`src/hc/client/conversation/view.cljs:832` 的 `source-cards` 是来源卡，不是查找。DOM 中 find 控件数为 0。影响：无法“向前查找”，长会话导航缺失。
3. **消息/会话列表无虚拟化** — `src/hc/client/conversation/view.cljs:1832` 的 `for [message (:messages view-model)]` 一次挂载全部消息；`src/hc/client/workbench/views/session.cljs` 的会话列表同样全量 `for`。2,000 条消息在 jsdom 中约 1.96 s、760 MB 堆；真实浏览器 paint 未测，但节点数线性增长是明确风险。
4. **jsdom 规模数据不是真实服务器数据** — 1,000/2,000 场景由 `test/hc/client/workbench/scale_dom_test.cljs` 合成 db，非真实 store；滚动/paint 受 jsdom 无布局限制。真实浏览器只覆盖了无模型与 20k 草稿。

## 8. 限制与未覆盖

- 真实数据目录为空；没有用真实 store 灌入 1,000 会话/2,000 消息。服务端没有公开的批量 seed 命令，直接写 Datalevin 会越过产品边界。
- jsdom 无布局/绘制；`scroll` 指标只是事件处理，不是 paint/FPS。
- 20k 插入耗时包含 CDP `Input.insertText` 协议开销，是上界，不是纯按键延迟。
- 未测屏幕阅读器、真实输入法组合态、移动端触摸滚动。
- 未跑 E2E 01–22 全量；本文件只覆盖发布门槛 7（无模型）与 §8.5 客户端规模。

## 9. 复现脚本

| 脚本 | 用途 |
|---|---|
| `test/js/no_model_probe.cjs` | 真实浏览器无模型提示、发送禁用、草稿保留、截图/DOM |
| `test/hc/client/workbench/scale_bench.clj` | JVM 纯投影 1,000/2,000 基准 |
| `test/hc/client/workbench/scale_dom_test.cljs` + `test/js/verify_workbench_scale_dom.sh` | jsdom 1,000 会话/2,000 消息渲染、滚动、恢复、查找、20k 编辑 |
| `test/js/scale_draft_probe.cjs` | 真实浏览器 20k 插入/选区/撤销/持久化/reload 恢复 |

运行：

```bash
clojure -M:dev -m hc.client.workbench.scale-bench
HC_SCALE_SESSIONS=1000 HC_SCALE_MESSAGES=0 bash test/js/verify_workbench_scale_dom.sh
HC_SCALE_SESSIONS=2 HC_SCALE_MESSAGES=2000 bash test/js/verify_workbench_scale_dom.sh
```

## 10. 结论

- 发布门槛 7（无模型提示、不假模型/不静默回退）：**PASS**。
- §8.5 客户端规模：**部分 PASS**。1,000 会话与 2,000 消息可渲染；20k 草稿编辑/选区/撤销/崩溃恢复 PASS；阅读位置恢复 FAIL；会话内查找 NOT RUN（无入口）；无虚拟化是明确瓶颈。
- 建议在 0.0.1 发布说明中如实标注：大规模真实浏览器滚动/paint 未测；阅读位置恢复与会话内查找未实现。
