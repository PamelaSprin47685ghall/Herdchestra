# P-BENCH2 跨路由滚动恢复验收（0.0.3）

## 1. 问题陈述

0.0.2 的 P-BENCH2 失败项：

> 恢复的是新 `scrollHeight` 底部，而非原阅读位置。

复现条件：单会话 2000 条消息，窗口 200；读者滚动到第 950 条附近，切走路由
（surface 卸载）再切回（窗口重置为 200）。旧实现用像素启发式恢复，切回后
回到错误的窗口/消息位置，而不是读者离开时的消息。

## 2. 根因

两个缺陷叠加：

1. **像素启发式**：旧恢复只保存 `scrollTop`，并按固定 48px/条估算窗口：
   `needed = min(total, max(200, 200 + ceil(saved / 48)))`。真实消息高度不是
   48px 时，估算出的尾窗口可能仍不包含读者位置；浏览器随后把 `scrollTop`
   clamp 到更短内容，落到新窗口的错误位置。
2. **pending write 与扩展渲染的顺序**：在 mount 的 layout effect 里设置
   `message-window` 后，同一 commit 的 pending effect 会先运行。它用**旧的
   200 条布局**写 `scrollTop`，被 clamp；随后扩展渲染才发生，但 pending 已被
   消费，锚点再也没有被正确恢复。

确定性 jsdom 布局下，旧实现的实际结果是：

| 指标 | 旧实现 | 期望 |
|---|---|---|
| 恢复后顶端消息 | `m1574` | `m950` |
| 需要的窗口 | 596 条 | 1250 条 |
| 恢复后 scrollTop | 3400（由 m1574 + 确定性布局反推：先按 200 条布局 clamp，再扩展）`[INFERENCE]` | 4000 |
| 页面底部 | 24400 | 24400 |

> 注：旧实现没有稳定落在“底部”，而是落在错误的 `m1574`；这说明像素启发式
> 本身已经不可靠。旧计划中的“到底部”是真实浏览器 clamp 时序下的一种表现，
> 本 fixture 证明的是同一根因下的“错误阅读位置”。

修复前测试结果（临时恢复旧 restore 代码，修复前后源文件 hash 已校验）：

```
FAIL route-switch-restores-the-same-message-anchor
restored top message m1574, expected m950
2 failures, 0 errors
```

## 3. 方案与不变量

采用**方案 3：消息 id + 相对偏移为权威**。

### 3.1 锚点

- 模块级 `defonce scroll-anchors`，按 `session-id` 保存
  `{:message-id id :delta px}`。它位于 React 树之外，路由卸载/重挂不影响。
- `delta` 是消息顶端到视口顶端的像素差；只有消息 id 是稳定语义，delta 是
  同一布局内的微调。
- 滚动事件记录“视口顶端第一条消息”。像素 `scrollTop` 仍通过 `:scroll!` 持久化，
  但只作为**新页面**（没有锚点）的兜底。

### 3.2 窗口

`scroll-window-size(total, anchor-index, current-window)`：

- `min(total, max(current, 200, (total - anchor-index) + 200))`
- 至少 200 条、锚点下方再留 200 条缓冲；
- **永不缩窗**；
- 锚点在第 0 条时允许扩到全量。

### 3.3 DOM 侧

- 滚动事件用 `getBoundingClientRect` 对有序消息节点做**二分查找**，O(log n)
  次 rect 读取，不做全量 geometry 映射。
- 恢复时按锚点 id 计算目标 `scrollTop = 锚点 top + delta`。
- `anchor-ready?` 门：展开窗口的 pending 写入必须等当前 DOM 真的包含锚点；
  避免再次在旧窗口布局上 clamp。
- 恢复成功后写 `scroll-restored-ref`，follow effect 不会把读者拉到底部。

### 3.4 阅读位置保护（AC-08-003）

`follow? false` 时，流式更新不写 `scrollTop`；新增测试断言滚动位置和顶端消息
都不变，且不等于底部。

## 4. 修复后实测

同一确定性 jsdom fixture（2000 条 × 20px，视口 600px，自定义 scrollTop clamp）：

| 指标 | 旧实现 | 新实现 |
|---|---|---|
| 顶端消息 | `m1574` | `m950` |
| 恢复窗口 | 596 条 | 1250 条（start 750） |
| scrollTop | 3400（clamp 后） | 4000 |
| 页面底部 | 24400 | 24400 |
| “到底部”负向断言 | 旧实现可偶然通过 | 通过（4000 != 24400） |

纯函数断言另外覆盖：首条可见消息选择、`delta`、锚点在变化后的窗口切片中
重算、窗口永不缩、空渲染。

## 5. 测试与命令

### 5.1 客户端 jsdom 回归

```bash
# 见 6. Harness 交接；核心是 cljs.main 编译 + Node runner
node test/js/run_session_controls_dom_tests.cjs "$OUT"
```

结果：

```
Testing hc.client.conversation.scroll-restore-test
Ran 3 tests containing 22 assertions.
0 failures, 0 errors.
Wall time: 142.02 seconds（含 cljs.main 编译）
```

覆盖：

- `anchor-helpers-are-message-id-based`：纯函数；
- `route-switch-restores-the-same-message-anchor`：2000 条、窗口 200 → 扩展到
  2000、scrollTop 19000（m950）、卸载/重挂、断言 m950 ±1 且非底部；
- `streaming-does-not-pull-a-reader-back-to-the-bottom`：AC-08-003。

### 5.2 CLJ 侧回归

```bash
.pixi/envs/default/bin/clojure -M:test:server -n hc.client.conversation-test
```

```
Ran 52 tests containing 529 assertions.
0 failures, 0 errors.
Wall time: 2.23 seconds
```

## 6. Harness 交接（给 eng-a3-primitives）

### 6.1 `cljs.main` 完整 `-co`

```clojure
{:target :nodejs
 :output-to "$OUT/test.js"
 :output-dir "$OUT"
 :optimizations :none
 :main hc.client.conversation.scroll-restore-test/-main
 :language-in :ecmascript-2017
 :rewrite-polyfills false
 :npm-deps false
 :foreign-libs
 [{:file "test/js/react_shim.js"
   :provides ["react"]
   :module-type :commonjs}
  {:file "test/js/react_dom_shim.js"
   :provides ["react-dom"]
   :module-type :commonjs}
  {:file "test/js/react_dom_server_shim.js"
   :provides ["react-dom/server"]
   :module-type :commonjs}
  {:file "test/js/react_dom_client_shim.js"
   :provides ["react-dom/client"]
   :module-type :commonjs}
  {:file "test/js/f7_global_shim.js"
   :provides ["framework7-react"]
   :module-type :commonjs}]}
```

编译入口：

```bash
.pixi/envs/default/bin/clojure \
  -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.12.145"}}}' \
  -M:dev:client -m cljs.main \
  -co "<上面的 EDN>" \
  -c hc.client.conversation.scroll-restore-test
```

### 6.2 jsdom / shim 安装位置

- jsdom 由 **Node runner** 在加载 React 之前安装：
  `test/js/run_session_controls_dom_tests.cjs`。
- 测试文件内的 `setup-dom!` 只是独立运行时的兜底；在正式 runner 下会检测到
  `js.window`/`js.document` 已存在并直接返回。
- 确定性布局（`scrollTop` clamp、`scrollHeight`/`clientHeight`、
  `getBoundingClientRect`）安装在测试文件的 `install-layout!` 中，作用于
  `HTMLElement.prototype`，仅此 test jsdom 实例。
- `foreign-libs` 全部指向 `test/js/*_shim.js`；不要把 `node_modules` 放进
  foreign libs（Closure 会把包的 `package.json` 当 JavaScript 编译）。

### 6.3 runner 调用与 npm 包

```bash
node test/js/run_session_controls_dom_tests.cjs "$OUT"
```

runner 需要的 npm 包（已在 `package.json` 的 dependencies/devDependencies）：

- `react`、`react-dom`、`framework7-react`
- `jsdom`（devDependency）
- `react-dom/server`、`react-dom/client.js`（由 `react-dom` 提供）

runner 还会：

- 重写输出目录里 shim 的 `var module$...` 为 `goog.global....`；
- 写入 `{"type":"commonjs"}`；
- 设置 `global.__HC_REACT__` / `__HC_REACT_DOM__` / `__HC_F7__` 等；
- `require("$OUT/test.js")`，失败时保持非零退出码。

### 6.4 输出目录与清理

```bash
OUT="$(mktemp -d -p . "out-scroll-restore.XXXXXX")"
OUT="${OUT#./}"
trap 'status=$?; if [ "$status" -eq 0 ]; then rm -rf "$OUT"; else printf "scroll restore output kept: %s\n" "$OUT" >&2; fi; exit "$status"' EXIT
```

- 成功：删除 `OUT`；
- 失败：保留 `out-scroll-restore.*` 供检查，并打印路径。

### 6.5 实测耗时

- cljs.main 编译 + jsdom 运行：**142.02s**（另一次 119.09s）。
- 其中测试本身 3 tests / 22 assertions；耗时主要来自 cljs.main。

## 7. jsdom 局限

jsdom 没有真实布局引擎：`offsetHeight`/`scrollHeight`/`clientHeight` 恒为 0，
`scrollTop` 不 clamp。测试因此在 `install-layout!` 中安装确定性布局：

- 每条消息 20px；
- 视口 600px；
- `scrollHeight = 渲染节点数 × 20`；
- `scrollTop` setter 按 `scrollHeight - clientHeight` clamp；
- `getBoundingClientRect` 按节点在渲染切片中的位置返回 top/height。

因此本证据是**逻辑/时序正确性**证据，不是真实浏览器的重绘/图片加载/字体
加载时序证据。真实浏览器下的滚动恢复仍需 E2E 覆盖（尤其是图片撑高、
Web 字体切换、`ResizeObserver` 迟到）。

## 8. 剩余风险

1. **锚点表无上限**：`defonce scroll-anchors` 按 `session-id` 增长，未加清理。
   每条仅两个字段（message-id、delta），量级 = 用户本次页面会话中打开过的
   会话数；1000 条约数十 KB 量级 `[INFERENCE]`。当前可接受。后续建议在 session
   discard/列表裁剪时删除对应 key，或加一个固定上限（例如保留最近 64 个）。
2. **像素兜底仍存在**：没有锚点的新页面（刷新后首次进入）仍用
   `scrollTop + 48px` 估算；这是读取位置未建立时的已知近似，不影响路由切换
   路径。后续可用持久化的 anchor 字段替代。
3. **真实浏览器时序**：见 §7；需要 E2E 在图片/字体加载后验证锚点仍稳定。
