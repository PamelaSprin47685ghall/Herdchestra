# Herdchestra 0.0.4 发布说明

发布日期：2026-11-12
基线：`0.0.3`（`9c95364`）

## 判定标准

本版把「最小可运行」的判定从「四条主链路能手工跑通」升级为：

1. `AGENTS.md` §8.5 的 **22 条 E2E：21 PASS / 1 SKIP，0 FAIL**（单进程串行，真实浏览器 + 真实 JVM + mock 模型/外部服务）。唯一 SKIP 是 E2E 18（磁盘满注入），因为 macOS 无 `unshare -Ur -m`；该条上次在 Linux 上为 39/39 PASS，见 `plans/e2e-master-status-0.0.3.md`。逐条状态见 `plans/e2e-master-status-0.0.4.md`。
2. **全量 JVM 测试 0 失败 / 0 错误**。

升级理由：抽样代表制掩盖了 E2E 11 与 19 两条长期失败。单独立项后，它们暴露出五处真实产品缺陷——抽样的成本节约是假的。

## 修复的产品缺陷

每条都在修前有可复现的失败证据。

### 1. 进程树终止留下孤儿（`12fb34c`）

`stop-tree!` 只对启动瞬间的进程快照发信号。`sh -c` 在约 5ms 内 fork 子进程，而轮询间隔是 50ms，因此入口快照必然漏掉它；父进程一死，子进程 reparent 到 init，此后 `.descendants` 再也看不到它。

改为每轮重扫、先等 fork 完成（`:settle-ms` 默认 150ms）、子进程先于父进程发信号。`hc.server.shell-test` 30 tests / 207 assertions 通过，无残留。

### 2. 发布管道被提交密度饿死（`00d8b5a`）

流式输出每来一个 chunk 就触发一次全量 baseline 重建，且与 5 秒心跳共用同一个单线程执行器。提交越快，发布越排队，客户端观察到 166 秒无事件后判定连接中断。

改为：Owner 通知只标脏（25ms CAS 去抖），心跳移到独立线程 `hc-heartbeat`。心跳的语义是「通道还活着」，不能与发布共用线程，否则「发布慢」会伪装成「断线」。

### 3. Enter 被静默丢弃（`00d8b5a`）

`request-send!` 用上一渲染帧的 `intent` 决定是否发送。textarea 是非受控的，`fill` 立刻改 DOM 但 view-model 慢一帧，于是 intent 仍是 `:disabled`，代码返回 `nil`——草稿留在框里，没有气泡，没有解释。

改为：当可见字段确实有文本时，把 DOM 快照交给桥接层按提交时的状态裁决。`:duplicate` 与 `:wait` 仍短路，因为它们描述的是在途命令。

### 4. 通知遮挡右上角控件（`83ee20a`）

通知栈 `z-index: 50` 与顶栏同层且 DOM 在后，其「关闭通知」按钮精确压在 `#wb-aux-toggle` 上。通知永不过期，于是该按钮永久点不动；Playwright 会重试命中测试直到整个测试预算耗尽（实测 1047 次），报错里看不到真正原因。

通知栈从 `top: 16px` 移到 `68px`，清开 56px 顶栏。抽屉（`.wb-nav`、`.wb-aux`）从 `z-index: 40` 提到 55：抽屉是模态浮层，必须高于通知。层级序列固定为 **抽屉 > 通知 > 顶栏**。

### 5. 材料快照缓存不区分会话（本版新增）

材料快照是按会话生成的：它带临时会话标记、项目范围与该会话的回合选择，并且在临时会话下把记忆行清空。而缓存校验只用全局材料 revision，这个值只在资料库变更时前进。切换会话时 revision 不变，于是返回的是上一个会话的快照。

客户端正确地把它判为跨会话并丢弃，但没有任何一方重新对齐：页面停在「材料库已就绪」+ 0 行，而服务端持有另一个会话的数据。

改为把会话标识纳入缓存版本：`[revision, active-session]`。回归测试 `materials-component-cache-keys-on-the-session-too` 固定住「同 revision 不同会话必须重建」。

### 6. 快照被丢弃后没有重新对齐的出口（本版新增）

第 5 条修好了「缓存返回错会话」，但没修「错会话的快照到了以后怎么办」。三处叠加，使面板永久停在空白：

1. `begin-session` 清空投影，而 `@fetched-session` 已经记着这个会话；`fetch?` 的 `not=` 判据不成立，1500ms 节流又没到，刷新永不排队。
2. `sync-from-db!` 只由三个 watch 触发（`server-revision`、`connection`、会话或 epoch 变化）。空闲界面一个都不触发，于是节流「到期后重试」这条路径永远不被求值。
3. 刷新定时器重读数据库，`reduce-action` 从排队中的旧投影取 `:session-id`，请求会指向旧会话，回来再被 `cross-session?` 丢弃。

修法：

- `apply-snapshot` 丢弃时记录 `:last-rejected`（理由 + 两个会话 id），使「被拒绝」与「没到达」可区分（关闭 D-54，满足 §3.10-7）。
- 桥接层见到新的拒绝或会话切换就忘掉 `fetched-session`/`fetched-at`；对未就绪投影排一次 200ms 自愈，不再依赖「事件恰好到来」。
- 刷新命令的 `:session-id` 钉死为已校验的会话，请求与投影不可能分歧。

回归测试：`cross-session-snapshot-is-ignored` 增加拒绝理由断言；新增 `a-stale-snapshot-is-recorded-as-stale`。

## 修复的测试侧假设错误

这三处曾把产品缺陷伪装成竞态，也把测试自身的错误伪装成产品缺陷。

| 位置 | 错误假设 | 正确判据 |
|---|---|---|
| `e2e-19` `sendLongReply` | 「上一条助手消息稳定」等于「本条已开始」 | 本条用户气泡出现且文本匹配 |
| `e2e-19` 各相位 | `ensureIdle` 可以用于「准备发下一条」 | `ensureIdle` 会点停止；准备态用 `stopAndSettle` |
| `e2e-11` `sendAndSettle` | mock 静默 = UI 空闲 | 决策条缺席 + pending 命令列表为空 |

`aria-label="停止当前生成"` 常驻 DOM（空闲时标签文本变成「停止」），因此**它是否存在、是否 disabled 都不能判断回合是否在跑**。

## 可移植性

| 改动 | 原因 |
|---|---|
| `release-gate.sh` 支持 `date -u`、`stat -c`/`stat -f` 双分支、`shasum` 回退 | 原脚本只在 GNU 工具链下工作 |
| `orphan_watchdog_test` 探测 `flock`，缺失则 SKIP | macOS 默认没有 |
| `e2e-06` 的 Pillow 与字体缺失改为可读报错 + 退出码 2，字体候选扩到 7 条 | 原实现直接抛栈 |
| 删除 `@cljs-oss/module-deps` 依赖，新增 `verify:js` | 该依赖已无用户 |
| `live-model-entry` 接受 vector | 真 bug：配置形状不只一种 |

## 验证记录

| 项目 | 结果 |
|---|---|
| 全量 JVM（`clojure -X:test:compose:server:client`） | 4502 tests / 33065 assertions，0 failures / 0 errors |
| `verify:js`（26 脚本） | passed=25 failed=0 |
| E2E 11（记忆候选/归档/临时会话） | 31/31 checks，`ok: true` |
| E2E 19（键盘/200% 字号/输入法/焦点/AX 树） | 20/20 checks，`ok: true` |
| 全量 22 条 E2E | 见 `plans/e2e-master-status-0.0.4.md` |

## 已知边界

见 `RUNNING.md` 的「已知边界」，以及 `DEBT.md` 中仍未关闭的条目。以下与 E2E 相关但不阻塞本轮判定：

- **D-44** 新建会话后立即发送存在回执竞态；产品侧仍无门控，E2E 用显式等待绕开。
- **D-45** WebM 时长字段未实现。
- **D-46** E2E 21 的哈希口径与实际计价口径不一致。
- **D-47** devops worker 的 shell 不进 Owner 账本。
- **D-48** `:settle-ms` 未随 `:stop-grace-ms` 收敛。
- **D-49** 通知永不过期，只能逐条手动关闭。
- **D-50** 右上角控件缺少遮挡防护的测试约束。
- **D-51** 发送被拒时只有草稿留在框里，界面不说明原因。
- **D-52** `:duplicate` 与快照路径的语义冲突没有测试固定。
- **D-53**（已收窄）材料页不显示它服务哪个会话。重新对齐与拒绝诊断已在本版第 6 条修复；只剩「视图显示会话身份」一项。

D-54（`cross-session?` 静默丢弃）已在本版第 6 条关闭。
