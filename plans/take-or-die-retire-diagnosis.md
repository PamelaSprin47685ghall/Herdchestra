# take-or-die 赢后立即退休诊断（0.0.1，2026-11-05）

- 结论：**不是 `:attempt-won` 回执错误，也不是 token 轮转丢失；是
  `maybe-retire-holder` 在 take-or-die 同一事务的 `finalize` 中按 R(a)
  立即退休新 holder。触发条件是“先竞争 token，后创建 needs-token 任务”
  的错误顺序。** 先创建 token 任务再 take-or-die 时 holder 正常存活
  （`:attempt-won` + `:retired? false`），已真实复验。

## 1. 复现与证据

干净实例：`/tmp/hc-w5-final/data`、`/tmp/hc-w5-final/work`，session
`hc-session-17f7ed73-85d3-4b21-9aad-ccc36fda5d43`，swarm tenure-3。

### 1.1 错误顺序（先 take-or-die，后 split）

调用前 board：

- `token-slot {:holder-id nil :term-id 4 :state :free}`；
- `task-1 {:state :ready :needs-token false :owner-id nil}`；
- 没有 `:needs-token true` 的未完成任务；
- actor-4 `:life-state :alive`、`:role :normal`，无 claim、无 action。

调用：

```clojure
{:op :take-or-die :tenure-id "tenure-3" :actor-id :agent-4
 :request-id "e2e09-final-token-4"
 :confirm :i-hereby-confirm-i-die-without-regret}
```

回执：

```clojure
{:status :accepted :outcome :take-or-die
 :result :attempt-won :term-id 5
 :retired? true :retired-agent-id :agent-4}
```

事务后：actor-4 `:life-state :dead`、token slot 再次 `:free`（term 5），
后续 `:claim` 返回 `:ACTOR_INACTIVE`。同样行为在 agent-6 复现。

### 1.2 正确顺序（先 split token 任务，再 take-or-die）

1. `:split task-1` 生成 `task-2 {:needs-token true :state :ready}`；
2. `:take-or-die agent-7` 回执
   `{:result :attempt-won :term-id 7 :retired? false :retired-agent-id nil}`；
3. `:claim task-2` → `:claim-1`；holder 存活，后续受保护写入可执行。

## 2. 根因

`src/hc/core/swarm_stm/commands.clj`：

- `dispatch-take-or-die` 的空 token 分支只设置 holder/term/role，不退休；
- 但每次命令结束都进入 `finalize`，它无条件调用
  `maybe-retire-holder`（`commands.clj:1695-1699`）；
- `maybe-retire-holder` 在 holder alive 且
  `(empty? (board/responsibility b holder))` 时释放 token、退休身份
  （`commands.clj:1631-1666`）；
- `board/responsibility`（`board.clj:100-115`）对 holder 的集合是：
  - 自己拥有的未完成普通任务（`needs-token false`）；
  - **所有**未完成的 `needs-token true` 任务。
  没有 token 任务、也没有 owned 普通任务时 R(a) = ∅。

因此“先竞争、后 split”时，take-or-die 赢家在同一事务里被
`maybe-retire-holder` 立即退休。回执同时给出 `:attempt-won` 与
`:retired? true` 是诚实的两个事实，不是字段矛盾。

## 3. 最小修复点

### 3.1 测试/操作顺序（无需改代码）

先 `:split` 出 `:needs-token true` 的子任务，再 `:take-or-die`。这是
规范 §3.4 的 holder 责任模型：holder 的 R(a) 必须包含 token 任务。
E2E 09 已按此顺序复验通过 holder 存活。

### 3.2 产品可选改进（core，属 W2/W4）

若希望“无 token 任务时竞争”给出可读拒绝而不是赢后即死，可在
`dispatch-take-or-die` 的空 token 分支增加：

```clojure
(when-not (some #(and (unfinished? %) (:needs-token %)) (vals (:tasks b)))
  (fail :TOKEN_TASK_REQUIRED {...}))
```

这会改变 core 行为，**不在 W5 所有权内**；本轮只记录，不修改。

## 4. 结论

- take-or-die 的 token 轮转与退休逻辑本身自洽；E2E 09 的阻断来自测试
  顺序错误，不是发布阻断级产品缺陷。
- 建议 DEBT 记录“take-or-die 无 token 任务时赢后即死”的 UX 缺口，由
  W2/W4 决定是否加 `:TOKEN_TASK_REQUIRED`。
- E2E 09 的正确操作顺序与真实 UI 证据见
  `plans/e2e-real-09-10.md` 与 `plans/evidence/e2e-09-10/`。

## 5. 实现状态（2026-11-06）

- `:TOKEN_TASK_REQUIRED` 已在 `src/hc/core/swarm_stm/commands.clj:1264`
  实现：没有未完成的 `:needs-token true` 任务时，`take-or-die` 在改变看板前
  返回该拒绝，actor 保持 `:alive`、token 保持 `:free`。
- 回归：`hc.core.swarm-stm-test/empty-token-without-token-task-is-refused`
  1 test / 5 assertions，0F/0E（日志 `/tmp/hc-token-task-required-test.log`）。
- 本文第 3 节的“建议”已落地，不再是缺口。D-14 记录的“存在 token 任务时
  winner 被立即退休”是另一条路径，仍由 W5 处理。

## 5. 产品修复与回归（2026-11-06）

- `src/hc/core/swarm_stm/commands.clj` 的 `dispatch-take-or-die` 在空 token
  且没有任何未完成 `:needs-token true` 任务时返回
  `:TOKEN_TASK_REQUIRED`，不改变 board；有 token 任务时
  `:attempt-won` + `:retired? false`。
- 回归：
  - `hc.core.swarm-stm-test` 50 tests / 259 assertions / 0F / 0E；
  - `hc.core.swarm-stm-property-test` 9 / 9 / 0 / 0；
  - `hc.compose.mission.actor-smoke-test` 8 / 118 / 0 / 0；
  - `hc.compose.mission.host-test` 24 / 296 / 0 / 0；
  - `hc.compose.mission.owner-actor-runtime-test` 仍 5F：该测试依赖
    “空 holder 直接 take-or-die 触发退休/rebirth”，新契约下需改为
    claim+finish token task 或 finish/cancel 责任触发退休。
- 测试前提已统一为“先 split/create token task，再 take-or-die”；空 token
  无 token task 的测试断言 `:TOKEN_TASK_REQUIRED`、actor 存活、token free。
