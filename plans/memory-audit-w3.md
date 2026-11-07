# W3 内存审查（2026-11-06，OOM 后）

> 范围：我负责/改过的文件。未改根 AGENTS.md，未 git 写。
> 约束：app/test JVM `-Xmx1g -XX:MaxDirectMemorySize=512m`；Chrome
> `--disable-dev-shm-usage`；同一时刻最多 1 个 app JVM + 1 个 Chrome；
> 测试与 E2E 串行；跑完即停实例。

## 1 当前进程

我名下无存活 app/JVM/Chrome/stub。OOM 后已消失的：`hc-w3e5-app`
（pid 13783）、`stub-e2e05`（13777）、`hc-w3e5r-app`（32231）、
`ollama-w3e5`（30949，端口冲突退出）。系统当前 used 4944 MB /
available 58879 MB。未杀其他工人进程。

## 2 热点清单

| 文件 | 热点 | 结论 |
|---|---|---|
| `src/hc/server/history.clj` `append-message` | 每个 chunk 调 `message/normalize-message`，比较新旧正文（`not=`、`starts-with?`），并在增长时 `assoc` 同一版本 | 无无界集合；保留的正文大小 = 回答大小，必要。每 chunk O(n) 扫描，400 个 200KB 重复 chunk 的实测保留堆增量约 0.001 MB |
| 同文件 | `(vec (or (:attachments payload) []))` 只在 payload 带附件时执行 | chunk 载荷不带附件，不触发 |
| `src/hc/client/conversation/timeline.cljc` `body-text/newest-text` | 每次渲染对最多 5 个显示副本做 `str/blank?` 与 `starts-with?`；长消息是 CPU 热点 | 只读、不复制大字符串；未改，避免行为风险。建议后续用长度/identity 快路径并配基准 |
| `src/hc/client/conversation/view.cljs` `version-selector` | 每次渲染 `(vec (:versions message))`，通常 2 项 | 有界；新增 `speech-playback/stop!` 为 O(1) |
| `src/hc/client/conversation/view.cljs` 压缩/上下文面板 | 读取 `context/outbound` 并渲染；不做全量 `into {}`/`pr-str` | 无新增拷贝 |
| E2E stub / 脚本 | stub 日志 append-only；Chrome profile 增长 | 已删除本次 stub 日志与 chrome profile（约 25 MB）；后续日志按大小截断 |
| 测试 | 新增 `stopped` atom 仅计数 | 有界 |

不在我所有权、未改：`snapshot.clj` 的全量投影、`sync/adapter` 的
消息合并、`conversation_state/mirror` 的 drafts/asks 复制（W2/W4/W5）。

## 3 尝试与回退

对 `history/append-message` 试过“identical? 快路径 + 长度先行”：
- 1,000,000 次 200KB 相同字符串比较：旧 `not= + starts-with?`
  11.9 ms；新 `identical? + 长度 + starts-with?` 22.2 ms。
- 400 个 200KB 相同 chunk 经完整命令路径：保留堆增量约 0.001 MB，
  两条路径均无泄漏；耗时主要来自命令分发，快路径无收益。
- 结论：**回退该微优化**，保留最简单的正确实现（前缀增长）。
  不为“看起来优化”增加复杂度。

## 4 修复/措施

1. 回退无收益的 `identical?/长度先行` 微优化；保留前缀增长修复。
2. 删除本次 stub 日志与 Chrome profile；保留证据 JSON/PNG。
3. 后续 app/test JVM 强制 `-Xmx1g -XX:MaxDirectMemorySize=512m`，
   Chrome 加 `--disable-dev-shm-usage`，串行运行。
4. 不在生产代码引入缓存/atom/日志；E2E stub 日志按大小截断。

## 5 验证

- `hc.server.history-test`：22 tests / 267 assertions / 0 failures
  （带 `JAVA_TOOL_OPTIONS=-Xmx1g -XX:MaxDirectMemorySize=512m`）。
- 基准：`/tmp/mem_bench.clj`、`/tmp/scan_bench.clj`（一次性脚本，
  不提交）。
