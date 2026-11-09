# hc.compose AGENTS.md — 组合层

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > 本文件。
> 本文件只定义组合层的装配边界与禁区；协议语义以根 §5、§3.3、§3.5 为准。

## 1 责任

组合层把独立的 core 模块装配成可运行的 Owner 与执行系统：

1. **Owner 邮箱**：每个会话与每个看板协调域一个串行状态入口。接收用户命令、模型片段、工具终态、计时器、子任期报告与取消信号，把它们转换为权威状态迁移。
2. **Dijkstra 守卫命令**：纯读取已提交状态，返回 `{:id :enabled? :prepare}`。微动作按“判断 → 领取 → 提交 → 派发”四步执行，提交完成后才向 Missionary 派发外部动作。
3. **优先级组**：P0 控制与取消、P1 结果归并与责任闭合、P2 重试规划与到期领取、P3 Nudge 与新工作领取、P4 死胡同兜底。每轮设微动作配额；配额耗尽但仍有启用守卫时自动重入队。
4. **Missionary 监督树**：承载模型请求、工具执行与独立并行分支。业务是否已启动以领域状态中的 `attempt-id` 为准，不以 Task 对象存在为准。
5. **取消域**：按 `attempt-id` 中断模型流、计算任务或受管子进程；取消信号不得穿透数据库提交或 `FileChannel` 写入线程。
6. **报告交付**：跨 Owner 的子终态先持久写 `:report-pending`，父方按 `parent-call-id` 幂等确认后清除。进程重启后继续重投。
7. **Nudge 拦截**：代理结束回合但未处于任一合法出口时，注入列出全部未结台账项与常授权硬口令的督促消息，并开启下一轮模型请求。合法出口只有三个：`Plan` 在 `S1` 的 `ask` 挂起、执行层初任空账的 `ask` 挂起、验收合格后的终局结案。

## 2 不得

1. 不在 Owner 内阻塞等待模型响应、文件 I/O、Shell 或长耗时预演。
2. 不在守卫的 `:enabled?` 或 `:prepare` 中启动 Future、写库或产生外部副作用。
3. 不把已领取的 Missionary Task 直接挂在启动守卫的响应式分支下；守卫变假不得取消已领取工作。
4. 不用 `m/join` 的 fail-fast 默认行为处理相互独立的业务分支；独立失败封装为 `{:result/status :failed ...}` 数据值。
5. 不在组合层重新实现看板转换；拆分、认领、完成、令牌竞争调用 `hc.core.swarm-stm`。
6. 不在组合层实现文件预演或 `stamp` 比较；调用 `hc.core.workspace-gate`。
7. 不把工具阻塞、任务等待与主动休眠混为一谈。主动休眠只走 `park-slot` 的全局禁睡规则。
8. 不重试可能产生重复副作用的调用。`report-pending`、`call-id` 与 `attempt-id` 只做幂等协调。

## 3 关键接口

### 3.1 守卫

```clojure
{:id      :claim-model-request
 :enabled? (fn [state] ...)   ; 纯读，返回布尔或候选描述
 :prepare  (fn [state] ...)}  ; 纯数据迁移计划与待派发任务
```

提交迁移后按优先级重新评估。选中微动作先写入 `:claimed` / `:dispatching` 与唯一 `attempt-id`，提交成功后才派发。

### 3.2 报告

```clojure
{:report-id ...
 :parent-call-id ...
 :payload {:kind :llm-report | :raw-tool-output :text ...}
 :resource-ref {...}
 :parent-session-id ...   ; 可选跨 Owner 路由
 :terminal ...}           ; 可选终态负载
```

父方按 `parent-call-id` 幂等接受一次；父责任已终止时返回明确确认，不让子方永久重投。
投递失败只进入 `:retry-wait`（250ms–5s 指数退避），没有次数上限，也不把报告标成
`:failed`。启动恢复把仍为 `:pending` 的报告重新置为可投递；确认后在同一提交中清除
`:report-pending` 并回收资源。LLM 报告全文内联，不截断、不摘要。

### 3.3 取消原因

取消信号携带 `:user-cancel`、`:parent-terminated`、`:sibling-accepted`、`:deadline-exceeded` 或 `:executor-shutdown`。原因决定资源收束方式，不把主动收束误记为上游故障。

## 4 命名空间

当前：`hc.compose.guards.*`、`hc.compose.cancel.*`、`hc.compose.nudge.*`、`hc.compose.reports.*`、`hc.compose.retry.*`、`hc.compose.fleet.*`、`hc.compose.mission.*`、`hc.compose.owner.*`、`hc.compose.scheduler.*`、`hc.compose.steward`、`hc.compose.supervisor`、`hc.compose.workspace.*`。

`hc.compose.owner.*` 包含 `mailbox`（单写者队列）、`store`（整态 CAS）、`commands`（`command-id`/`command-epoch` 门）、`state`（初始态与调用/取消节点）、`handlers`（纯消息迁移）、`guards`（P0–P3 目录）、`dispatch`（Missionary 监督、计时器与报告投递）、`tasks`（三类业务组合器）、`board`（Owner 支撑的 workspace/Shell 看板适配器）与 `runtime`（提交、守卫轮、outbox、通知、生命周期）。细节见 `owner/AGENTS.md`。

`hc.compose.supervisor` 是进程级循环监督器，不是第二个 Owner；它只负责后台循环的启停、退避与状态。`hc.compose.steward` 是仓库守望的纯状态组合层，无 I/O、无时钟、无 Git 调用。命名可以随实现细化，但“单 Owner、纯守卫、显式装配”的边界不变。

## 5 测试

1. Nudge 不变量：除三个合法出口（`S1` 的 `.ask` 挂起、执行层初任空账的 `.ask` 挂起、终局 `:completed`）外，任何回合结束必然触发 Nudge 或守卫推进。
2. 守卫纯度：随机状态序列下，`:enabled?` 与 `:prepare` 不产生外部副作用，且同一状态重复求值结果一致。
3. 领取四步：任何派发都有已提交的 `attempt-id`；重复唤醒不重复启动同一工作。
4. 报告幂等：重复投递不重复追加结果；父方取消时子方仍能结清。
5. 取消隔离：取消一个 `attempt-id` 不影响兄弟分支；数据库提交线程不被中断。
6. 配额与兜底：配额耗尽仍有启用守卫时继续运行；无启用守卫且无在途工作时产生显式 `:blocked` 诊断，不静默停机。

属性测试放在 `test/hc/compose/`。两种入口都可用：

```bash
clojure -X:test:compose
clojure -M:test:compose -n hc.compose.guards-test
```
