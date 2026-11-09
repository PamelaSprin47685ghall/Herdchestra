# `hc.server.app.background-loops`

Status: implemented.

Authority: root `AGENTS.md` §3.1, §4, and §5.1; then `src/AGENTS.md`;
then `src/hc/server/AGENTS.md` and `src/hc/server/app/AGENTS.md`. This file
fixes the local adapter contract only.

## 1 Boundary

This namespace converts long-lived background loops into
`hc.compose.supervisor` child specs. The supervisor owns the timer, the
restart policy, the bounded backoff, and the stop order. This namespace owns:

- one loop callback per child;
- one bounded handle per child;
- honest `:runs`, `:failures`, `:consecutive-failures`, `:last-run-at`, and
  `:last-error` facts for health and diagnostics.

It starts no thread, opens no store, calls no model, and owns no domain
state. All effects are injected callbacks.

## 2 Files

| File | Responsibility |
|---|---|
| `background_loops.clj` | Child adapters, bounded tick failures, idempotent stop handles, `child-specs`, and a bounded `status`. |

## 3 Callback contracts

| Loop | Input callback | Call |
|---|---|---|
| `:fleet-gc` | `:gc-fn` | `(gc-fn retention-ms)`; one fleet GC sweep |
| `:retention-gc` | `:purge-fn` | `(purge-fn service)`; one session-retention sweep |
| `:retention-gc` | `:blob-gc-fn` | `(blob-gc-fn service)`; one blob-retention sweep |
| `:steward-loop` | `:tick-fn` | `(tick-fn)`; one Steward advance |

Contract sources:

| Loop | Real source |
|---|---|
| `:fleet-gc` | `src/hc/server/app/fleet_gc.clj`: `start!`, `stop!`, `status`; the `{:ok? true :gc runtime}` start receipt is unwrapped to the handle resource, and `:stop-fn` receives that runtime |
| `:retention-gc` | `src/hc/server/app/retention_gc.clj`: `start!`, `stop!`, `purge-expired-deleted!`, `gc-blobs!`, `status` |
| `:steward-loop` | `src/hc/server/app/steward_runtime.clj`: `start!`, `tick!`, `stop!`, and the existing `child-spec` |

`:retention-gc` needs `:service-ref` (an atom, another `IDeref`, or a plain
runtime), `:start-fn`, or `:start-opts`. A deref value of
`{:ok? true :service runtime}` unwraps to `runtime`; a fleet receipt
`{:ok? true :gc runtime}` also unwraps to `runtime`. A nil service makes the
tick fail with `:BACKGROUND_SERVICE_UNAVAILABLE`; it does not throw.

`retention-gc-child` `:start-opts` is the **real** `retention-gc/start!`
option map. The adapter checks it against `retention-start-opt-keys`
(source: `retention_gc.clj`, `start!`), calls the real `start!`, and defaults
`:stop-fn` to the real `retention-gc/stop!`. Unknown keys throw
`:BACKGROUND_START_OPTS_INVALID` instead of disappearing. Supplying both
`:start-fn` and `:start-opts` is a conflict.

The real sweep functions have two arities: `(f service)` and
`(f service opts)`. The adapter calls the one-argument form through the
injected `:purge-fn`/`:blob-gc-fn`.

Every adapter accepts these optional keys:

| Key | Default | Meaning |
|---|---|---|
| `:start-fn` | `nil` | `(fn [] result)`; an underlying start |
| `:stop-fn` | `nil` | `(fn [resource])`; cleanup for the start resource |
| `:start-opts` | `nil` | Retention only: real `retention-gc/start!` options |
| `:restart` | `:permanent` | Passed to the supervisor |
| `:backoff-ms` | `[1000 60000]` | Bounded exponential backoff |
| `:stop-timeout-ms` | `5000` | Bound for `:stop-fn` |
| `:critical?` | `false` | Startup requirement |

A missing or non-function required callback is a programmer error. The
constructor throws `ex-info` with a `:code` such as
`:BACKGROUND_GC_FN_REQUIRED` or `:BACKGROUND_INTERVAL_INVALID`.

## 4 Child spec

Every adapter returns a supervisor child spec with exactly the required
fields:

```clojure
{:id          :fleet-gc
 :start!      (fn [ctx] handle)
 :stop!       (fn [handle] receipt)
 :tick!       (fn [ctx] receipt)
 :interval-ms 3600000
 :restart     :permanent
 :backoff-ms  [1000 60000]
 :critical?   false
 ::state      <atom>}       ; for `status` on a spec; the supervisor ignores it
```

The handle is a small map:

```clojure
{:id :fleet-gc
 :state <atom>            ; counters and last-error
 :resource <value>        ; from :start-fn or :service-ref
 :stop-fn <fn or nil>
 :stop-timeout-ms 5000
 :started-at epoch-ms}
```

## 5 Tick semantics

`:tick!` never throws. It returns one of:

- `{:ok? true :id id :at epoch-ms}` after a success;
- `{:ok? false :error {:code ... :message ... :at epoch-ms}}` after a
  failure.

A callback result map with `:ok? false` is a failure. Its `:error` is kept
when it is a map; otherwise a bounded `:BACKGROUND_TICK_FAILED` error is
synthesized. A thrown callback becomes `:BACKGROUND_TICK_FAILED`. Any other
return value, including nil, is a success.

On success the handle increments `:runs`, sets `:last-run-at`, and clears
`:consecutive-failures`. On failure it increments `:failures` and
`:consecutive-failures` and stores a bounded `:last-error`. `:last-error` is
historical; it survives a later success.

The supervisor also applies its own tick contract: a returned map with
`:ok? false` is a failed tick there too, so the child enters
`:backing-off` and is never reported `:running`.

## 6 Start and stop

`:start!` calls `:start-fn` when present, otherwise resolves `:service-ref`.
A start result map with `:ok? false` is a rejected start: `:start!` returns
`{:ok? false :error ...}`, records the bounded failure in the handle, and
leaves `:running? false`. A throw becomes `:BACKGROUND_START_FAILED`. The
supervisor treats the returned rejection as a rejected start.

A successful start publishes its unwrapped resource into `:service-ref`
when that value is an atom. A rejected start publishes nothing.

`:stop!` is idempotent, bounded, and never throws. Its receipt is:

```clojure
{:ok? bool
 :stopped? bool
 :timed-out? bool
 :result <underlying value>?   ; when the callback returned
 :error {:code ... :message ... :at ...}?}
```

`:ok?` is true only when `:stop-fn` returned in bounds **and** confirmed
termination:

- timeout => `:ok? false`, `:timed-out? true`,
  `:BACKGROUND_STOP_TIMEOUT`; the callback thread is not interrupted;
- throw => `:ok? false`, `:timed-out? false`, `:BACKGROUND_STOP_FAILED`;
- an underlying map with `:ok? false` => `:ok? false`, its `:error` is
  kept;
- an underlying map with `:terminated? false` => `:ok? false`,
  `:BACKGROUND_STOP_UNCONFIRMED`;
- any other in-bounds return (including a missing `:ok?`) => `:ok? true`.

The supervisor aggregates this receipt. `:ok? false` means the writer is
not confirmed stopped, so the application must not close the Store. A
second call is a no-op with `:stopped? false` and `:ok? true`.

## 7 `child-specs` and `status`

`child-specs` takes a map keyed by `:steward-loop`, `:fleet-gc`, and
`:retention-gc`. An absent or nil entry is omitted. The output order is
fixed: `[:steward-loop :fleet-gc :retention-gc]`. An empty or nil map yields
`[]`.

`status` accepts one handle, one spec, or a sequential collection of either.
It returns one bounded map (or a vector of maps):

```clojure
{:id id
 :running? bool
 :runs n
 :failures n
 :consecutive-failures n
 :last-run-at epoch-ms
 :last-error {:code ... :message ... :at ...}}
```

It never returns a sweep payload, evidence list, or service object.

The adapter `:runs` counts **combined supervisor ticks**, not individual
sweeps. For per-sweep detail (`:session-runs`, `:blob-runs`,
`:session-last-result`, `:blob-last-result`) read the real component
`status` with the handle resource.

### Steward overlap

`hc.server.app.steward-runtime/child-spec` already exists and is the
authoritative adapter when a Steward runtime is assembled:
`(:start! child-spec)` returns the runtime, `:tick!` calls
`steward-runtime/tick!`, and `:stop!` calls `steward-runtime/stop!`.

`background-loops/steward-loop-child` is deliberately a **generic thunk
adapter**: it knows only `(tick-fn)` and owns no Steward runtime lifecycle.
Use it when another component already starts and stops the Steward runtime,
or for a non-Steward periodic thunk that needs the bounded background
status. Use `steward-runtime/child-spec` when this tree owns the Steward
runtime; never install both for the same runtime (double ticking).

Reasons not to delegate: `steward-runtime/child-spec` takes a live runtime
and publishes it as the handle; `steward-loop-child` is constructed before
the runtime and must remain usable for the required `:tick-fn` contract.
The supervisor's tick-return failure contract applies to both.

### 0.0.3 实际装配决定（不是未来 owner change）

- `hc.server.app.system` 在 0.0.3 直接调用 `retention-gc/start!`，**不**把
  它装成 supervisor periodic child；`retention-gc/start!` 自带
  `ScheduledExecutorService`，再加 supervisor tick 会让每个 sweep 翻倍。
  `retention-gc-child` 仍保留给测试/其他宿主，但本组装不用。
- `fleet-gc/start!` likewise always starts its own timer. For
  supervisor-owned cadence, call the fleet service's `gc!` through `:gc-fn`
  and do not call `fleet-gc/start!`. If `fleet-gc/start!` is used as
  `:start-fn`, its `{:ok? true :gc runtime}` is unwrapped and `:stop-fn`
  receives the runtime; the timer also runs, so do not add ticks.
  0.0.3 同样由 `system` 直接启动 fleet-gc，不再叠加 supervisor tick。

## 8 替代宿主 wiring 示例（0.0.3 主 `system` 不使用）

下面的 supervisor child 组装只适用于另一个宿主；本仓库的
`hc.server.app.system` 只把 `steward-loop` 装成 supervisor child，
retention/fleet 直接启动自己的 timer（见上节）。

```clojure
(let [children
      (background-loops/child-specs
       {:steward-loop
        {:tick-fn (fn [] (steward-runtime/tick! steward-rt))
         :interval-ms (get-in config [:steward :interval-ms])}

        :fleet-gc
        {:gc-fn (fn [_retention-ms] (fleet-gc/gc! fleet-service))
         :retention-ms (get-in config [:fleet :retention-ms])
         :interval-ms (get-in config [:fleet :gc-interval-ms])}

        :retention-gc
        {:service-ref retention-ref
         :purge-fn retention-gc/purge-expired-deleted!
         :blob-gc-fn retention-gc/gc-blobs!
         :interval-ms (get-in config [:retention :interval-ms])
         ;; Real retention-gc/start! options; unknown keys are rejected.
         ;; The retention owner still starts an internal timer here. Use a
         ;; timer-less assembly (owner change) or do not also tick.
         :start-opts {:registry registry
                      :blob-root blob-root
                      :clock clock
                      :session-interval-ms (get-in config [:retention :interval-ms])
                      :blob-interval-ms (get-in config [:retention :interval-ms])
                      :session-days (get-in config [:retention :session-days])
                      :blob-days (get-in config [:retention :blob-days])
                      :run-immediately? false
                      :stop-timeout-ms 5000
                      :purge-session! purge-session!
                      :blob-cleanup! blob-cleanup!}}})

      started (supervisor/start! {:children children
                                  :clock (:clock config)})]
  ...)
```

`(:supervisor started)` is the handle for `supervisor/stop!` and `status`.
`background-loops/status` takes the saved `children` vector or live handles.

## 9 Invariants

1. A failed tick never increments `:runs` and never leaves the supervisor
   reporting `:running`.
2. `:runs + :failures` increases by exactly one per executed tick.
3. `:consecutive-failures` is the trailing failure streak and never exceeds
   `:failures`.
4. `:running?` is true exactly between a successful start and the first
   stop.
5. `:stop!` calls `:stop-fn` at most once and never throws.
6. `:stop!` `:ok? true` only when the callback returned in bounds and
   confirmed termination. The supervisor turns `:ok? false` into an overall
   stop failure.
7. Neither `tick!` nor `stop!` returns a full payload.
7. `child-specs` never emits a nil child and never reorders loops.
8. Retention `:start-opts` contains no key the real `start!` does not read;
   the adapter key set equals the real `start!` arglists.
9. A fleet `{:ok? true :gc ...}` or retention `{:ok? true :service ...}`
   start receipt is unwrapped before it reaches `:stop-fn`.
10. A successful start publishes the unwrapped resource into an atom
    `:service-ref`; a rejected start publishes nothing.

## 10 Tests

`test/hc/server/app/background_loops_test.clj` uses the real
`hc.compose.supervisor` and covers start/stop order, sibling isolation,
backoff, rejected starts, idempotent bounded stop, omission/order, and a
`clojure.test.check` property over random tick outcomes. It also checks:

- the retention `:start-opts` key set against `retention-gc/start!` arglists;
- a real `retention-gc/start!` + `stop!` integration through the supervisor
  (temporary dir, fake registry reads, real sweeps, real status counters);
- the fleet `{:ok? true :gc runtime}` unwrap and stop pass-through with the
  real `fleet-gc/start!`/`stop!` signatures.

```bash
clojure -X:test:server :nses '[hc.server.app.background-loops-test]
clojure -M:test:server -n hc.server.app.background-loops-test
```

## 11 契约完备性（0.0.3）

### (a) 公开 API

```clojure
(fleet-gc-child opts)      ; => supervisor child-spec
(retention-gc-child opts)  ; => supervisor child-spec
(steward-loop-child opts)  ; => supervisor child-spec
(child-specs loops)        ; => [child-spec...]（steward → fleet → retention）
```

`status` 由 `hc.compose.supervisor/status` 读取 child handle 后投影；回调与
返回形状见 §3–§7。

### (b) 失败/拒绝码

| code | 触发 |
|---|---|
| `:BACKGROUND_INTERVAL_INVALID` | `:interval-ms` 不是正整数 |
| `:BACKGROUND_RESTART_INVALID` | `:restart` 不是 `:permanent/:transient/:temporary` |
| `:BACKGROUND_BACKOFF_INVALID` | `:backoff-ms` 不是 `[min max]` 且 0 < min ≤ max |
| `:BACKGROUND_STOP_TIMEOUT_INVALID` | `:stop-timeout-ms` 不是非负整数 |
| `:BACKGROUND_START_FN_INVALID` / `:BACKGROUND_STOP_FN_INVALID` | 回调存在但不是函数 |
| `:BACKGROUND_GC_FN_REQUIRED` / `:BACKGROUND_PURGE_FN_REQUIRED` / `:BACKGROUND_BLOB_GC_FN_REQUIRED` / `:BACKGROUND_TICK_FN_REQUIRED` | 对应 adapter 缺少必需回调 |
| `:BACKGROUND_RETENTION_INVALID` | fleet-gc `:retention-ms` 不是非负整数 |
| `:BACKGROUND_START_OPTS_INVALID` / `:BACKGROUND_START_OPTS_CONFLICT` | retention `:start-opts` 非 map、含未知键，或与 `:start-fn` 同时给出 |
| `:BACKGROUND_SERVICE_REF_REQUIRED` | retention child 没有 `:service-ref`/`:start-fn`/`:start-opts` |
| `:BACKGROUND_LOOPS_INVALID` | `child-specs` 输入不是 map |
| `:BACKGROUND_START_FAILED` / `:BACKGROUND_TICK_FAILED` / `:BACKGROUND_STOP_FAILED` | start/tick/stop 抛异常或返回非预期形状 |
| `:BACKGROUND_LOOP_NOT_STARTED` / `:BACKGROUND_LOOP_STOPPED` / `:BACKGROUND_SERVICE_UNAVAILABLE` | tick 时无 handle、已停止，或 retention service 未附着 |
| `:BACKGROUND_LOOP_ERROR` | 回调失败没有自己的 code（兜底） |

### (c) 不变量

保证：一个 adapter 一个 child spec；`:tick!` 永不抛出；失败不会增加 `:runs`
且不会把 child 报成 `:running`；start/stop 有界、stop 幂等；retention 的
session 与 blob sweep 独立汇总；child 顺序固定为 steward → fleet → retention，
stop 反序。

不保证：不拥有 timer/线程（supervisor 拥有）；不保证回调语义正确或幂等
（start/stop 只调用一次）；不保证 retention service 在 tick 时已就绪；不
保证 wall-clock 跳变不影响业务（等待由 supervisor 的 monotonic 时间控制）；
不保证未知 callback 返回值被接受。

### (d) 验证

```bash
clojure -M:test:server -n hc.server.app.background-loops-test
```

代码事实，未复测（2026-11-09 当前文件）：14 deftest + 1 defspec
（15 个测试入口）/ 100 个 `is`。
