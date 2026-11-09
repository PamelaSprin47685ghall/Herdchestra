# `hc.compose.supervisor` — process-level loop supervision

Status: implemented.

Authority: root `AGENTS.md` §3.1 and §4, then `src/AGENTS.md`, then
`src/hc/compose/AGENTS.md`. This file fixes the local contract only.

## 1 Boundary

One supervisor keeps a small set of long-lived background loops alive for
one process. Typical children are `:sphinx-loop`, `:steward-loop`, and
`:fleet-gc`; a host may register any other loop with the same child contract.

The supervisor is not an Owner. It never reads or writes session state,
never commits a board transition, and never calls a model. A child gets a
small context map and runs its own work. The Owner and the application
remain the authorities for domain state.

The namespace depends only on Clojure and `hc.compose.retry.policy`. It
does not require `hc.server.*` or `hc.client.*`.

## 2 Files

| File | Responsibility |
|---|---|
| `supervisor.clj` | Child validation, one daemon worker per child, bounded restart, reverse-order stop, honest status, synchronous trigger. |

## 3 Child contract

```clojure
{:id          :sphinx-loop
 :start!      (fn [ctx] handle)
 :stop!       (fn [handle])
 :tick!       (fn [ctx])              ; optional; makes the child periodic
 :interval-ms 1000                    ; required with :tick!
 :restart     :permanent              ; :permanent | :transient | :temporary
 :backoff-ms  [500 30000]
 :critical?   false
 :alive?      (fn [handle])           ; optional lifecycle liveness probe
 :poll-ms     100}                    ; optional liveness poll slice
```

`ctx` is `{:id :clock :stop-requested?}`. The `:tick!` context also holds
`:handle`, the value that `:start!` returned.

`start!` returns a handle of any type. `stop!` receives that exact value.
Both callbacks are required for every child, periodic or lifecycle.

A `:start!` return value of `{:ok? false :error ...}` is a rejected start,
not a handle. This matches the start style used by the server adapters.

A nil handle is valid. A lifecycle child with a nil or unrecognized handle
has no observable liveness; it stays `:running` until `stop!` or
`trigger!` changes the entry.

## 4 Kinds

### 4.1 Periodic

The child provides `:tick!` and a positive `:interval-ms`. The supervisor
owns one daemon worker thread for the child and calls the tick on a fixed
delay.

A thrown tick changes the child to `:backing-off`, increments `:failures`
and `:consecutive-failures`, and waits `backoff-delay` before the next
tick. A successful tick returns the child to `:running` and clears the
streak. Tick recovery does not depend on `:restart`; a broken loop keeps
trying inside its bounded backoff.

A returned map with `:ok? false` is a failed tick too. It increments the
same counters, stores its `:error` (or synthesizes
`{:code :CHILD_TICK_FAILED :message "child tick returned :ok? false"}`
when the error is absent or not a map), and enters the same
`:backing-off`. Any other return value, including nil, is a successful
tick. A tick result is never ignored.

One child's tick exception never stops a sibling and never stops the
supervisor.

### 4.2 Lifecycle

The child provides `:start!` and `:stop!`. The worker starts the child once,
then watches the handle:

- `java.util.concurrent.Future`: normal completion is a normal exit;
  `ExecutionException` is an abnormal exit.
- `java.lang.Thread`: the thread ending is an abnormal exit.
- `:alive?` probe: a false result is an abnormal exit. The probe cannot
  report a normal exit, so a clean end must use a `Future` handle. A throw
  inside the probe counts as dead.
- Anything else: not observable. The child stays `:running`.

The `:alive?` probe wins over the handle type when both could apply.

On an exit the supervisor calls `:stop!` once for cleanup and then applies
the restart policy:

| `:restart` | normal exit | abnormal exit | first-start failure |
|---|---|---|---|
| `:permanent` (default) | restart | restart | retry |
| `:transient` | stop | restart | retry |
| `:temporary` | stop | failed | failed |

A restart waits the bounded backoff and increments the failure counters.
A normal exit without a restart becomes `:stopped`; an abnormal exit
without a restart becomes `:failed`.

An exception that escapes the supervisor's own worker loop marks the child
`:failed` and is reported with phase `:worker`. A live handle from that
child is released by the next `stop!` or `trigger!`.

`:failures` counts operations that required supervision: a thrown tick, a
failed start, and an exit that forced a restart. A normal exit of a
`:permanent` child counts because it still forces a restart. The
consecutive counter drives the backoff and clears on the next success.

## 5 Backoff

`backoff-delay` maps the N-th consecutive failure to a delay. N is
1-based. Delay N is `min-ms * 2^(N-1)`, capped at `max-ms`. The function is
pure, monotone, and cannot spin for a huge N. It delegates to
`hc.compose.retry.policy/backoff-delay`, so the repository keeps one
implementation of this arithmetic.

Bounds must satisfy `0 < min-ms <= max-ms`. The defaults are
`[500 30000]`. A successful unit (tick, or lifecycle start) clears the
consecutive-failure streak.

Waits use `System/nanoTime` with bounded slices. `:clock` only labels
facts (`:last-run-at`, `:last-error`, `trigger!` receipts). A wall-clock
jump cannot burst or stall a loop.

## 6 Public API

```clojure
(supervisor/start! {:children [...] :clock #(System/currentTimeMillis)
                    :on-error (fn [event] ...)})
;; => {:ok? true :supervisor sup :started [ids] :degraded [ids]}
;;    {:ok? false :error {:code ...}}

(supervisor/stop! sup)
(supervisor/stop! sup {:timeout-ms 5000})
;; => {:ok? true|false :stopped [ids] :timed-out [ids]
;;     :failures [{:child id :error {...}} ...]}

(supervisor/status sup)
;; => {:running? bool :stopped? bool
;;     :children {id {:id id :state :running|:backing-off|:stopped|:failed
;;                    :runs n :failures n :consecutive-failures n
;;                    :backoff-ms n :last-run-at epoch-ms :last-error map}}}

(supervisor/trigger! sup child-id)
;; => {:ok? true :child-id id :action :tick|:start|:restart|:start-and-tick
;;     :at epoch-ms :result value}
;;    {:ok? false :child-id id :action ... :error {...} :stop-error ...}
```

### 6.1 `start!`

`start!` validates every child first. It then calls `:start!` for each
child once, in declaration order, before it launches any worker. A failed
non-critical start becomes `:degraded` and the worker retries it. A
`:critical?` child that fails its first start aborts the whole call; the
children that already started are stopped in reverse order.

`:started` lists the ids that started. `:degraded` lists the ids whose
first start failed and are retried by their worker (or are `:failed`
because `:restart` is `:temporary`).

`:critical?` applies to startup only. A later failure of a critical child
is handled like any other child failure and never stops a sibling.

`on-error` receives `{:child-id :phase :error :at :consecutive-failures
:backoff-ms}`. A throw inside `on-error` is ignored.

### 6.2 `stop!`

`stop!` signals every child first, then stops children in reverse
**successful start** order. A child that never started has no position in
that order and is handled after the started children.

For each child the supervisor:

1. sets the stop flag; the worker will not start a new unit;
2. waits for an in-flight tick to finish (`ReentrantLock.tryLock`);
3. calls `:stop!` once and detaches the handle;
4. joins the worker thread.

The whole call shares `:timeout-ms` (default 5000 ms). A worker that is
still running is reported in `:timed-out`; it is never interrupted. A
worker whose timeout expired calls `:stop!` itself when its tick returns,
so the resource is still released.

`:stop!` runs on the stop caller thread and cannot be interrupted. It must
return promptly; the supervisor bounds lock acquisition and the worker
join, not the callback body.

A `:stop!` throw is recorded in the child `:last-error` (phase `:stop`).
When its worker joined, the child still appears in `:stopped`.

The stop result is:

```clojure
{:ok? bool
 :stopped [child-id ...]
 :timed-out [child-id ...]
 :failures [{:child child-id :error {...}} ...]}
```

`:ok?` is true only when **every** child stop confirmed termination. The
confirmation is the child's own evidence, not the lock or join:

- a returned map with `:ok? false` is an unconfirmed stop;
- a throw is an unconfirmed stop;
- a lock acquisition timeout is an unconfirmed stop;
- any other return, including a missing `:ok?`, confirms the stop.

A joined worker with an unconfirmed stop stays in `:stopped` (the callback
ran) and is also in `:failures`. A running worker stays in `:timed-out`
and is never interrupted. The first call caches this result.

`stop!` is idempotent. The first call caches its result; later calls return
the same map and repeat no callback. `stop!` on a non-supervisor value
returns an explicit `:SUPERVISOR_INVALID` failure.

### 6.3 `status`

`status` reads atoms only. `:running?` describes the supervisor: it still
accepts supervision. It does not mean every child runs. Per-child
`:state` is always one of the four public values.

`runs` counts successful ticks for a periodic child and successful starts
for a lifecycle child. `last-run-at` records the last success.
`last-error` is historical; it survives a later recovery.

A child that threw is never reported as `:running`. A child in backoff is
`:backing-off`. A child whose restart policy refused to retry is
`:failed`.

### 6.4 `trigger!`

`trigger!` runs one unit synchronously and returns a receipt. The caller
can use it for deterministic tests and event-driven wake-ups.

- A periodic child runs one tick.
- A lifecycle child restarts: it stops the current handle and starts a new
  one. A stopped or failed child starts without a stop.
- A pending backoff wait is cleared by the resulting wake-up.

A child exception becomes `{:ok? false ...}` and never escapes. The
supervisor state is still updated with the failure and backoff.

`trigger!` and `stop!` may race. The worker re-checks the stop flag inside
the child lock, and `trigger!` refuses under that lock when the supervisor
is stopping. A control action never starts a handle after shutdown began.

## 7 Invariants

1. One child action at a time. `:start!`, `:tick!`, and `:stop!` for one
   child run under one `ReentrantLock`.
2. One child failure never affects a sibling's worker, handle, or state.
3. Every worker is a daemon thread. A forgotten supervisor cannot keep the
   JVM alive.
4. No callback is interrupted. Stop waits; a timeout is reported, never
   hidden.
5. `status` never upgrades a failure to `:running`.
6. `stop!` releases each handle at most once and repeats no callback.
7. Success clears the consecutive-failure streak; failure grows it.
8. A worker re-checks the stop flag inside the child lock before it starts
   or ticks, so a stop signal cannot leak a new handle after shutdown.
9. A tick that returns `{:ok? false ...}` is a failure. It never increments
   `:runs` and never appears as `:running` before its backoff.
10. Callbacks must not re-enter the same supervisor synchronously. The
   per-child lock is held around callbacks, so a nested `trigger!` or
   `stop!` for the same child would deadlock.
11. The supervisor holds no domain state and calls no model or storage.
12. `stop!` `:ok?` is the conjunction of every child's stop confirmation.
    A successful lock or join never substitutes for a child's `:ok? true`.
    A child `:stop!` that returns `{:ok? false}` is unconfirmed termination
    and must appear in `:failures`.

## 8 Failure codes

Validation: `:CHILDREN_INVALID`, `:CHILDREN_EMPTY`, `:CHILD_SPEC_INVALID`,
`:CHILD_ID_INVALID`, `:CHILD_ID_DUPLICATE`, `:CHILD_START_REQUIRED`,
`:CHILD_STOP_REQUIRED`, `:CHILD_TICK_INVALID`, `:CHILD_INTERVAL_REQUIRED`,
`:CHILD_INTERVAL_WITHOUT_TICK`, `:CHILD_ALIVE_INVALID`,
`:CHILD_ALIVE_NOT_PERIODIC`, `:CHILD_POLL_INVALID`,
`:CHILD_POLL_NOT_PERIODIC`, `:CHILD_RESTART_INVALID`,
`:CHILD_BACKOFF_INVALID`, `:CHILD_CRITICAL_INVALID`, `:CLOCK_INVALID`,
`:ON_ERROR_INVALID`, `:STOP_TIMEOUT_INVALID`.

Runtime: `:CRITICAL_CHILD_START_FAILED`, `:CHILD_START_FAILED`,
`:CHILD_START_REJECTED`, `:CHILD_TICK_FAILED`, `:CHILD_STOP_FAILED`,
`:CHILD_STOP_LOCK_TIMEOUT`, `:CHILD_EXITED`,
`:CHILD_EXITED_ABNORMALLY`, `:CHILD_LIVENESS_FAILED`,
`:CHILD_THREAD_EXITED`, `:CHILD_WORKER_FAILED`, `:CHILD_NOT_FOUND`,
`:CHILD_NOT_RUNNING`, `:SUPERVISOR_STOPPED`, `:SUPERVISOR_INVALID`,
`:SUPERVISOR_STOP_FAILED`, `:SUPERVISOR_WORKER_START_FAILED`.

A throwable `ex-data` `:code` replaces the default runtime code. The rest
of `ex-data` is kept in `:details`.

## 9 Tests

`test/hc/compose/supervisor_test.clj` mixes deterministic `deftest` cases
with `clojure.test.check` `defspec` properties:

- start order and reverse stop order;
- one child failure never kills a sibling;
- bounded, monotone backoff with an exact cap;
- repeated stop runs each stop callback once;
- a failed child is never reported `:running`.

Run:

```bash
clojure -X:test:compose :nses '[hc.compose.supervisor-test]'
clojure -M:test:compose -n hc.compose.supervisor-test
```

## 10 契约完备性补注（0.0.3）

### (a) 公开 API

`start!`、`stop!`、`status`、`trigger!` 的签名与返回形状见 §6；每个子项
回调和重启策略见 §3–§5。

### (b) 失败/拒绝码

校验与运行时 code 的完整清单见 §8。它们分为三类：启动参数校验
（`:CHILDREN_INVALID`、`:CHILD_*`、`:CLOCK_INVALID` 等）、首次启动/回调失败
（`:CRITICAL_CHILD_START_FAILED`、`:CHILD_START_FAILED`、
`:CHILD_TICK_FAILED`、`:CHILD_STOP_FAILED`、`:CHILD_EXITED*`）与监督器自身
（`:SUPERVISOR_STOPPED`、`:SUPERVISOR_INVALID`、`:SUPERVISOR_STOP_FAILED`、
`:SUPERVISOR_WORKER_START_FAILED`）。

### (c) 不变量

保证：一个子项同一时刻只执行一个动作；子项失败不杀兄弟；worker 是 daemon
线程；停止先发信号再按成功启动逆序执行；`stop!` 幂等且最多释放一次句柄；
超时会报告但不会中断回调；`status` 绝不把失败升级成 `:running`。

不保证：不保证回调在超时内返回（超时只报告，不强行中断）；不保证回调不
互相阻塞或可重入（同一子项的重入会死锁）；不负责子项资源的语义清理
（`stop!` 只调用一次）；不保证墙钟跳变不触发 sleep 提前/延后（等待用
monotonic 时间，clock 只记事实）；不保证进程外资源被回收。

### (d) 验证

```bash
clojure -M:test:compose -n hc.compose.supervisor-test
```

用户提供的实测：`30 tests / 180 assertions / 2.27s`，0F/0E（2026-11-09
工作树，加测试前）。当前文件代码事实，未复测：`26 deftest + 6 defspec`
（32 个测试入口）/ `164` 个 `is`。
