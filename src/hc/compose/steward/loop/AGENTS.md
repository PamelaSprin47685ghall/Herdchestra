# `hc.compose.steward.loop` — Steward 的纯推进循环

Status: implemented.

Authority: 根 `AGENTS.md` §2.2 > `src/AGENTS.md` > `src/hc/compose/AGENTS.md` >
`src/hc/compose/steward/AGENTS.md` > 本文件。本文件固定运行循环的局部契约，
不新增产品义务。

## 1 边界

`hc.compose.steward.loop` 只做两件事：

1. 把当前 Steward state 与一次世界观测组合成下一个 state；
2. 以显式、顺序固定的 effect 数据描述主机必须执行的外部动作。

本层是纯函数：不读时钟、文件、Git、模型、Store，不启动线程，不 require
`hc.server.*`。所有 I/O 由 `hc.server.app.steward-runtime` 执行。

依赖方向：

```text
hc.core.steward-sphinx
        ↑
hc.compose.steward
        ↑
hc.compose.steward.loop
        ↑
hc.server.app.steward-runtime
```

## 2 公开 API

```clojure
(loop/tick state world)
;; => {:state state' :effects [...] :observations {...} :skipped [...]}

(loop/effect-fingerprint effect)          ; 单个 effect
(loop/effect-fingerprint effects)         ; effect 序列，返回指纹向量
;; => vector（有界、稳定、忽略 :at 与大型载荷）

(loop/check-invariants state)
;; => {:ok? true :errors []}
;; => {:ok? false :errors [{:code ... :message ... :details ...}]}
```

### 2.1 `world`

```clojure
{:now epoch-ms
 :git {:head "sha" :new-commits [evidence...] :master-version n}
 :intent {:revision n :text "..."}        ; 可缺省
 :capacity {:rows [fleet-row...] :slots-total n :min-spare n}
 :stale? bool                             ; true 时只观测，不拉 frontier
 :decision {:request-id "..." :candidates [...] :policy {...}}
 :fleet {:results [{:mission-id "..." :result {...}}]}}
```

* `:new-commits` 可含原始或已归一化的 evidence；`tick` 对每个元素调用
  `ss/normalize-evidence`，整批全有或全无。
* `:intent` 缺省时保留 state 的原 intent；空 text 的 intent 永不进入
  `observe-world`，也不会触发 frontier。
* `:capacity` 含 `:rows` 时由 `steward/fleet-capacity` 聚合；否则原样交给
  `steward/observe-world` 校验。
* `:plan-stall-ms` 是停滞阈值，缺省 600000；必须为正整数。
* `:decision` 可以是单个 map 或 map 序列；用 state 的 `:frontier
  :last-frontier` 裁决，不使用调用方伪造的 frontier。
* `:fleet :results` 的顺序就是 report 的发射顺序。

### 2.2 七个阶段（顺序固定）

| # | 阶段 | 动作 | effect |
|---|---|---|---|
| 1 | 观测 | `steward/observe-world` 摄取 commits，更新 intent/capacity 与 `:last-observation` | 无；新证据进入阶段 2 |
| 2 | 认识 | `evidence-seq` 增长时唤醒 Meditator | `:model/meditate` |
| 3 | 前沿 | `steward/evaluate-frontier`；仅 `:stale? false` 且 intent 合法时 | `:model/decide` |
| 4 | 裁决 | `steward/decide` 落 Docket；扫描所有 `:admitted` 且无 Plan session 的 Mission | `:plan/start` |
| 5 | 派发 | link 的 `:plan-status = :plan-ready` 且无 `:swarm-ids` | `:fleet/fork` |
| 5b | 停滞 | 在 join 之后计算，避免同 tick 完成的 mission 误报；`:session-id` 已有、`:plan-status` 非 `:plan-ready`、`now - :plan-started-at > :plan-stall-ms` | `:diagnostic :MISSION_PLAN_STALLED` |
| 6 | 收割 | 消费 `:fleet :results`，迁移 Mission 并报告 | `:report/emit` |
| 7 | 提交 | state 与输入不同 | `:persist`，恒为最后一个 |

失败只进入 `:skipped` 与 `:diagnostic`，不抛异常。`:stale? true` 仍摄取
evidence，只跳过阶段 3。

### 2.3 effects

```clojure
{:type :model/meditate :role :meditator
 :evidence-ids [...] :evidence-count n :evidence-seq n
 :prompt-version 1 :at ms}

{:type :model/decide :role :steward
 :frontier-id "..." :frontier {...} :cba {...}
 :candidates-hint {...} :prompt-version 1 :at ms}

{:type :plan/start :mission-id "..." :candidate-id "..."
 :title "..." :intent-version n :at ms}

{:type :fleet/fork :mission-id "..." :slices [...]
 :intent-version n :at ms}

{:type :report/emit :mission-id "..." :outcome :completed|:failed|:abandoned
 :status ... :report "..." :ff-commit "..." :rebase-rounds n
 :error {...} :swarm-id "..." :at ms}

{:type :diagnostic :code :STAGE ... :message "..." :stage :...
 :details {...} :mission-id "..." :at ms}

{:type :diagnostic :code :MISSION_PLAN_STALLED :mission-id "..."
 :stalled-ms n :message "..." :at ms}

{:type :persist :state state' :at ms}
```

`evidence-ids` 最多 64 个，`evidence-count` 保留精确数量；`candidates-hint`
的每个列表最多 32 项。`plan-start` 对每个 Mission 在单次 tick 内至多一个；
已带 `:session-id`、`:plan-status` 或 `:swarm-ids` 的 link 不再发射。

### 2.4 幂等

`effect-fingerprint` 是主机去重的唯一依据：

```clojure
[:model/meditate :meditator evidence-seq]
[:model/decide :steward frontier-id]
[:plan/start mission-id intent-version]
[:fleet/fork mission-id [slice-id...]]
[:report/emit mission-id outcome swarm-id ff-commit]
[:diagnostic code mission-id request-id]
[:persist]
```

`tick` 是 at-least-once：同一 world 重复喂入会重复得到同一 effects。
`:persist` 永不去重；其余 effect 由主机在配置窗口内按指纹去重。
`:report/emit` 的指纹包含 join 身份（`swarm-id`/`ff-commit`），因此同一
mission 的不同 swarm 不会互相吞掉；主机对它使用独立的有界永久投递台账
（默认 4096 项，按最旧条目淘汰），而不是 300 秒窗口。

`plan-stall` 使用 state 的 `:stall-reported`（`mission-id -> reported-at`）
做进程生命周期内的幂等台账：最多 256 项，超出时按最旧 `reported-at`
淘汰并递增 `:stall-ledger-dropped`。诊断只发一次；它不改变 Mission 状态，
不重发 `:plan/start`，也不 fork。`plan-start` effect 发出时把
`:plan-started-at` 写入 link；已有该键时绝不覆盖，因此重试仍以第一次
尝试计时。

### 2.5 `check-invariants`

检查：

* state、cognition、frontier、docket 的 `:schema-version` 均为 1；
* docket 有 `:missions` map，links 是 map；
* `:cognition-version` 是非负整数，且与 Current Best Answer 的版本一致；
* Current Best Answer 的 `:non-final?` 为 true；
* links 的每个 mission id 都在 docket 中存在。

失败返回具体 `:errors`，不折叠成一个布尔。

## 3 测试

`test/hc/compose/steward/loop_test.clj` 覆盖：纯函数性、空 intent、stale、
重复 evidence、persist 位置、单个 plan-start 与 fork、缺失 slices、
三种 join 结果、unknown mission、links 不变量，以及两个
`defspec`（随机 evidence/fleet 行下不变量恒真、effect 顺序合法；连续 tick
保持不变量）。

## 4 Wave 3 装配清单（`hc.server.app.system`）

运行循环本身不装配；以下在 0.0.3 已由 `hc.server.app.system` 装配完成，
本清单保留为契约核对项（`steward-runtime/child-spec` + process supervisor）：

1. **创建 runtime**：在 `steward-store` 就绪、fleet service 就绪之后：

   ```clojure
   (steward-runtime/start!
    {:store steward-store
     :git {:log-since-fn (fn [{:since :ref :now}] ...)}
     :fleet fleet-service
     :plan-start-fn (fn [{:mission-id :intent-version}] ...)
     :on-report-fn (fn [report] ...)
     :model-port {:meditate! ... :decide! ...}
     :clock (:clock fleet-service)
     :config {:interval-ms ... :slots-total ... :min-spare ...
              :git-ref "master"}})
   ```

2. **加入 process supervisor**：把
   `(steward-runtime/child-spec rt {:interval-ms ...})` 放在现有
   `:children` 向量中，排在 `:fleet-gc` 之后（顺序无依赖，stop 反序）。
   `:restart :permanent`、`:critical? false` 保持默认。

3. **fleet join 回调**：fleet service 构造时的 `:on-join-result!` 必须调用
   `(steward-runtime/on-join-result! rt service result opts)`。如果已有
   mission Owner 回写回调，先调用它，再调用 runtime；两者都不得阻塞。
   不得把 join 回调改成同步 tick。

4. **Plan handoff**：Plan 完成 S3 后调用
   `(steward-runtime/on-plan-ready! rt mission-id {:session-id ... :slices [...] :plan-version ...})`。
   必须在 Mission 仍为 `:admitted`/`:planning` 时调用；迟到的手返回
   `:MISSION_NOT_PLANNING`。

5. **命令平面**：`commands/handle-steward!` 的现有 CAS 路径保持不变；
   需要模型/Plan 回填时，通过 runtime 的 `submit-intent!`、
   `submit-evidence!`、`submit-decision!`、`submit-revision!` 进入同一
   Store 串行区，禁止第二条直写路径。

6. **health**：在 `system-health` 增加 `:steward (steward-runtime/health rt)`
   与 `:steward-runtime-error`（start! 失败时保存 `:error`）。snapshot 面板
   用 `(steward-runtime/snapshot rt)`，不要展开 evidence 表。

7. **stop 顺序**：`process-supervisor`（含 `:steward-loop`）先于 fleet
   service 与 Owners 停止；runtime 的 `stop!` 由 supervisor 反序调用。
   `stop!` 返回 `{:ok? :stopped? :terminated? :pending-ticks
   :pending-models :store-closed? ...}`；超时为 `:ok? false`、
   `:terminated? false`、错误码 `:STEWARD_STOP_TIMEOUT`。**只有
   `:terminated? true` 才能关闭 Store**；`steward-store` 是命令路由共享
   句柄，默认不关闭；只有 runtime 独占 Store 时才设 `:close-store? true`，
   且未终止时 `stop!` 拒绝关闭（`:store-close-deferred? true`）。

8. **暂停语义**：`steward_runtime/stop!` 置 `:running? false`，之后的
   `submit-*!` 返回 `:STEWARD_RUNTIME_STOPPED`；重启进程时以 Store 中的
   文档重新 `start!`，不重放旧 effects。在途 tick 在 Store 临界区内二次
   检查 `running?`，停止后放弃写入；失败的 tick 把取出的 pending 输入
   按原顺序放回（超限时丢弃最旧并计数），并把 state 回退到最近一次成功
   提交的 `persisted-state`。`snapshot` 暴露 `:pending-count` 与
   `:dropped-count`。

## 5 契约完备性（0.0.3）

### (a) 公开 API

```clojure
(tick state world)
;; => {:state state' :effects [effect...] :observations {...}
;;     :skipped [{:stage .. :code .. :details ..}...]
;;     :diagnostics [{:type :diagnostic :stage .. :code .. ...}...]}
(effect-fingerprint effect)   ; => vector
(effect-fingerprint effects)  ; => [vector...]
(check-invariants state)      ; => {:ok? true :errors []}
                              ; |  {:ok? false :errors [{:code :message :details?}...]}
```

`tick` 不抛异常；失败进入 `:skipped`/`:diagnostics`，state 保持输入值或
已成功推进的前缀。

### (b) 失败/拒绝码

| code | 触发 |
|---|---|
| `:INVALID_EVIDENCE` / `:INVALID_OBSERVATION` / `:INVALID_INTENT` / `:INVALID_CAPACITY` | 观测阶段 core 或 `observe-world` 拒绝 |
| `:INTENT_MISSING` | 没有非空 operator intent，frontier 阶段跳过 |
| `:STALE_OBSERVATION` | `:stale? true`，只观测不拉 frontier |
| `:STALE_DECISION_FRONTIER` | decision 指向的 frontier 不是当前 last-frontier |
| `:MISSION_SLICES_MISSING` | plan-ready mission 没有 slices，无法 fork |
| `:UNKNOWN_MISSION` / `:UNKNOWN_JOIN_MISSION` | join 结果指向 Docket 中不存在的 mission/link |
| `:INVALID_JOIN_RESULT` | join result 不是 map |
| `:STEWARD_REFUSED` | 上游失败没有携带自己的 code |
| `:INVALID_STATE` / `:INVALID_SCHEMA_VERSION` | state 不是 map，或顶层 schema-version 不是 1 |
| `:INVALID_COGNITION` / `:INVALID_COGNITION_SCHEMA` | cognition 不是 map，或 schema-version 不是 1 |
| `:INVALID_FRONTIER` / `:INVALID_FRONTIER_SCHEMA` | frontier 不是 map，或 schema-version 不是 1 |
| `:INVALID_DOCKET` / `:INVALID_DOCKET_SCHEMA` | docket 不是 map，或 schema-version 不是 1 |
| `:INVALID_LINKS` | links 不是 map |
| `:INVALID_COGNITION_VERSION` | cognition-version 不是非负整数 |
| `:STALE_BEST_ANSWER` | Current Best Answer 的 cognition-version 与当前不一致 |
| `:FINAL_BEST_ANSWER` | Current Best Answer 的 `:non-final?` 不是 true |
| `:LINKS_UNKNOWN_MISSION` | links 指向 Docket 中不存在的 mission |

`:MISSION_PLAN_STALLED` 是 effect 诊断码，不是拒绝码；它只追加 diagnostic
并写入 `:stall-reported`，不改变 Mission 状态。

### (c) 不变量

保证：纯函数；相同 state+world 产生相同结果（含 effect 顺序）；七个阶段
顺序固定；`:persist` 恒为最后一个 effect；`effect-fingerprint` 忽略 `:at`
与大型载荷；同一 world 重复喂入是 at-least-once，交给宿主按指纹去重。

不保证：不执行 effect、不写 Store、不调度 host、不调用模型/Git；不保证
模型输出合法（非法输出进入 skipped/diagnostic）；不保证跨会话事务或崩溃
恢复；不保证 `check-invariants` 覆盖 core 的全部 schema（core facade 才是
权威），也不保证 caller 一定在提交前运行它。

### (d) 验证

```bash
clojure -M:test:compose -n hc.compose.steward.loop-test
```

代码事实（2026-11-09 当前文件）：`14 deftest + 3 defspec`
（17 个测试入口）/ `59` 个 `is`；属性测试的 assertion 数随迭代次数变化。
