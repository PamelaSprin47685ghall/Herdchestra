# `hc.server.program` — Clojure program sandbox and continuations

Authority: root `AGENTS.md` §4.1–§4.3 and §6.3. This package implements the
`clj-plan`, `clj-review`, `clj-worker`, and Plan-dedicated-worker program
surfaces. It does not own the Swarm STM, the write gate, or the model port.

## 1 Boundary and dependency direction

```text
hc.server.program              facade: runtime, evaluate, unpark, cancel, render
  ├── capability               pure role sets and static gates
  ├── sandbox                  UFSS/EDN parse, macro expansion, whitelist, checkpoints
  ├── receipt                  receipt shapes, EDN result limits, UFSS rendering
  ├── workspace-port           reference :workspace port over hc.core.workspace-gate
  ├── shell-port               reference :call port over hc.server.shell
  └── runtime                  threads, park/unpark, deadlines, call-id registry
```

The package may require `hc.core.*` and the JDK. It must not require
`hc.compose`, `hc.client`, or a storage engine. The workspace, shell, and
control ports are plain maps, so `hc.compose.workspace` can implement them
without depending on this server namespace.

## 2 Public API

```clojure
(require '[hc.server.program :as program])

(def rt (program/runtime opts))
(program/evaluate-program! rt request)   ; primary
(program/execute! rt request)            ; alias
(program/unpark! rt request)             ; synthetic (unpark ...) program
;; unpark! defaults :intent to "unpark" when omitted
(program/cancel! rt call-id-or-attempt-id-or-name)
(program/stop! rt)
(program/program-state rt name-or-call-id-or-attempt-id)
(program/name-active? rt name)
(program/active-programs rt)             ; {:name :call-id :attempt-id :state}
(program/render result)                  ; EDN+UFSS text
(program/render-tool-result payload)     ; executor payload -> EDN+UFSS text
(program/shell-call-port opts)           ; :call port with live command views
```

`program-state` returns `{:name :call-id :attempt-id :state :receipt :result
:commits :control-results}`; `:commits` is the in-order applied-commit
ledger, including pre-call drains.

`cancel!` resolves a target by exact name, then exact `:call-id`, then a
unique `:attempt-id`.  An attempt-id shared by several programs is refused
with `:AMBIGUOUS_TARGET` and the candidate names/call-ids; it never picks one
silently.  `stop!` returns `:active` descriptors (including pending
operations) for programs whose in-flight shell prevented an immediate
terminal.

`runtime` options:

| Key | Meaning |
|---|---|
| `:workspace` | Port map (below). Wins over the reference adapter. |
| `:workspace-root` / `:workspace-gate` / `:gate` / `:board` | Build `workspace-port/workspace-gate-port`. |
| `:call` / `:shell` | `(fn [ctx request] result)` shell port. Optional. A `:call` key inside `:workspace` is also accepted. |
| `:call-stop!` | `(fn [ctx operation-id])` called for each pending shell call when cancellation is requested. Wire it to `shell/request-stop!`. A stop request is not an observation: while a call is in flight `cancel!` returns `:CANCELLING` and keeps the program active until the call returns. |
| `:control` | `:execute!` generic primitive port, or per-primitive functions. Optional. |
| `:clock` | `(fn [] millis)`. Default `System/currentTimeMillis`. |
| `:schedule` | `(fn [delay-ms f] cancel-fn)`. Default one daemon scheduled executor. |
| `:on-terminal` | `(fn [result])` for real programs, exactly once on terminal, including after unpark and after a deferred cancellation. Receives `:call-id`/`:attempt-id`/`:name` for per-program resource cleanup and late terminal delivery. Synthetic unpark wrappers are skipped. |
| `:max-source-bytes` | Default 1 MiB. |

`workspace-gate-port` without `:board` uses the explicit boardless test seam
(`:board nil` + `:allow-boardless? true`).  Pass a real `BoardAdapter` when
the port must enforce actor/claim eligibility.

Request:

```clojure
{:call-id "call-1"                 ; required, non-blank string; :attempt-id is a fallback
 :role :clj-worker                 ; :clj-plan :clj-review :clj-worker :dedicated-worker
                                    ; (:tool is a fallback)
 :source "(defprogram name [] ...)" ; UFSS/EDN string; :program is an alias
                                    ; and may also be one EDN form
 :intent "why this call runs"      ; required string; deadline = UTF-8 bytes * 50 ms
 :agent-id .. :claim-id .. :task-id .. :attempt-id ..
 :tenure-id .. :lane .. :workspace .. ; scalar/Path; part of the fingerprint
 :holder? false :s1? false :phase ..
 :capabilities #{...}              ; optional narrowing of the role set
 :draft-path .. :write-unlocked? .. :readable-paths .. ; Plan policy, also
                                    ; mirrored into :context
 :context {...}                    ; passed to ports; not part of idempotency;
                                    ; Plan uses :draft-path, :write-unlocked?,
                                    ; and :readable-paths here
 :before-terminal (fn [result])    ; internal publication barrier, once on
                                    ; terminal, before the segment is delivered;
                                    ; must not block or reenter the runtime
 :on-terminal (fn [result])        ; optional notification, once on terminal,
                                    ; after the segment is delivered
 :synthetic? false}                ; internal
```

`evaluate-program!` returns at the first park or terminal state. It never
waits for a later unpark. The same `:call-id` with the same fingerprint
replays the original receipt; a changed fingerprint returns
`:CALL_REPLAY_MISMATCH`. Every result also carries `:receipt` (the full
receipt without transport fields) and `:receipts [receipt]` so the bridge and
Owner can preserve the actual park/terminal result in the executor payload.
`render-tool-result` prefers `:receipt`, then a program-like `:receipts` entry,
and falls back safely for non-EDN payloads.

Projection rule for the Owner: a `:tool` conversation message must be
rendered as EDN+UFSS, not `pr-str` or JSON. Prefer
`(program/render-tool-result payload)`, injected as
`:tool-result-renderer` in the Owner options. A generic
`hc.core.ufss/write-str` fallback is acceptable when the payload is EDN and
includes the program `:receipts`; the specialized renderer additionally
selects the receipt itself and gives a correlation-preserving refusal for
non-EDN. Either way the model sees a park's `:reason/:payload/:source/
:pending-operations/:command/:delta/:remaining-wait-ms` and a terminal
receipt's `:commit/:control-results`. Shell/workspace payloads are outside
this package but should follow the same UFSS presentation rule. The
fingerprint covers role, source, intent, agent/claim/task ids,
tenure/lane/workspace, holder/S1 flags, phase, effective capabilities, and
Plan path policy. Ports, `:context`, `:attempt-id`, and callbacks are
excluded.

`:workspace` is a scalar identity (`string`, `keyword`, `symbol`, or
`java.nio.file.Path`), or `:workspace-id` supplies the same identity.
Non-scalar workspace descriptors are rejected with `:INVALID_ARGUMENT`; the
runtime never collapses different workspaces to a shared class name, so two
different workspaces cannot replay the same `:call-id`.

Status values include `:COMPLETED`, `:PARKED`, `:FAILED`, `:CANCELLED`,
`:CANCELLING`, `:OUTCOME_UNKNOWN`,
`:NAME_TAKEN`, `:CALL_REPLAY_MISMATCH`, `:PROGRAM_UNKNOWN`,
`:AMBIGUOUS_TARGET`,
`:STALE_CONTROL`, `:CAPABILITY_DENIED`, `:TOKEN_REQUIRED`,
`:ASK_FORBIDDEN_OUTSIDE_S1`, `:WRITE_LOCKED_BEFORE_FIRST_REVIEW`,
`:INVALID_ARGUMENT`, `:LIMIT_EXCEEDED`, `:UNSUPPORTED_OPERATION`, plus the
workspace-gate commit statuses (`:FILE_CONFLICT`, `:STRUCTURE_CONFLICT`,
`:PREPARE_REJECTED`, `:HARD_FAILURE`, ...). `:status` is the program outcome,
not a boolean success flag. `:state` is the lifecycle state.

## 3 Capability map

The static sets live in `hc.server.program.capability/roles`. They mirror
root §4.1, with two deliberate worker-only extensions that root §4.1's table
does not list yet: `:resolve-action` (close the caller's own `:uncertain`
file action after inspecting the worktree) and `:acknowledge-recovery`
(the swarm kernel's recovery acknowledgement).  Both are required by the
F13 recovery flow and by the worker system prompt, so they stay
program-visible; removing them would break a documented recovery path.  A
spec owner should eventually add them to §4.1 or retire them.  Dynamic gates:

- `ask` requires `:clj-plan` **and** `:s1? true`.
- `call` requires `:dedicated-worker`, or `:clj-worker` with `:holder? true`.
  A normal worker gets `:TOKEN_REQUIRED`.
- `abandon` requires `:clj-worker` with `:holder? true`.
- Plan `cat` requires a readable surface: `:readable-paths` (operator
  attachments) and/or `:draft-path`. The two are unioned; with neither, Plan
  reads are `:CAPABILITY_DENIED` rather than a silent workspace-wide read.
  Plan `write` requires `:write-unlocked? true`. A nil `:draft-path` is the
  first-write case: the candidate must match `plans/[a-z0-9-]{1,80}.md`, its
  `plans/` parent is created when absent, and an existing file is
  `:DRAFT_NAME_TAKEN`. The first accepted write fixes the path for this
  program and the tenure; a later different path is `:DRAFT_PATH_LOCKED`,
  and an unsafe name is `:INVALID_DRAFT_PATH`. After the fix, `cat` uses the
  same fixed path.
- A request `:capabilities` set narrows the role set. It can never widen it.

Every program-visible function takes exactly one EDN map. `checkpoint!` is
internal and injected by the compiler.

## 4 Ports

### 4.1 `:workspace`

```clojure
{:open-session (fn [ctx] session)      ; ctx: actor ids, attempt-id, tenure-id, lane,
                                       ;      workspace, role, call-id, program name
 :cat   (fn [session path] text)
 :stat  (fn [session path] map)
 :ls    (fn [session path] vector)
 :write (fn [session path content] staged)
 :mkdir (fn [session path] staged)
 :rm    (fn [session path] staged)
 :mv    (fn [session source target opts] staged)
 :facts (fn [session] {:ops .. :read-set .. :checks ..})
 :settle! (fn [session request]
            {:status :APPLIED|:FILE_CONFLICT|...
             :session fresh-session
             :result commit-result})}
```

`settle!` request:

```clojure
{:request-id "call-id:settle:1" :settle-id 1
 :call-id .. :program-name .. :role ..
 :agent-id .. :claim-id .. :task-id .. :actor-id ..
 :phase :applied :intent-bytes ..}
```

The port must take the shared write gate `W`, verify the batch, apply it, and
publish the STM action. On `:APPLIED` it must return a fresh empty session.
The reference adapter forwards the whole request to
`hc.core.workspace-gate/commit!` (adding `:batch`); a board-backed commit
needs `:task-id`, and an application may need `:program-name`, `:role`,
`:phase`, and `:intent-bytes`.  Do not re-select only request/actor/claim.
The runtime calls `settle!` only when `:ops` is non-empty: automatic
settlement is for staged file changes. Read-set-only board conclusions belong
to the control port's `finish`, which owns its empty-change verification under
`W`. Before every `(call ...)`, the runtime drains staged changes; a
non-`:APPLIED` drain aborts the program with that commit result and the shell
is not started. A drain consumes the batch, including its read-set. If a
later write depends on a file that the shell may have changed, the program
must `cat` it again after the call. The final receipt's `:commit` is the last
settle; earlier pre-call drains were already applied and are not replayed in
that field.

`finish` does **not** drain or delay writes. The runtime calls the control
port with the staged batch intact, then runs the normal terminal `settle!`
after the body returns. The control port verifies `finish`'s read-set against
the old real filesystem under `W` and commits the board finish immediately;
staged writes remain pending. If `finish` releases the claim, the terminal
settle can fail `:CLAIM_CHANGED`. That is a legitimate program result, not a
silent loss: the finish success is preserved in the receipt's
`:control-results`, and the settle failure is the top-level status. If the
model wants the file on disk before `finish`, it must call `call` (which
drains) or split the program.

### 4.2 `:call`

```clojure
(fn [ctx request] result)
;; ctx: :agent-id :claim-id :task-id :attempt-id :tenure-id :lane :workspace
;;      :program-name :role :call-id :phase :holder? :s1?
;; request: {:command "..." | ["prog" "arg" ...]
;;           | {:program "..." :args [...]}
;;           :operation-id "call-id:call:1"
;;           :on-command-view (fn [view])}
```

The runtime sets `:waiting`, records the pending operation, invokes the port,
stores the result as the command view, and returns it to the program. A port
may call `:on-command-view` with a map carrying `:stdout`/`:stderr`,
`:stdout-bytes`/`:stderr-bytes`, `:duration-ms`, `:exit-code`, `:phase`, and
`:status`. The runtime merges it into the current command atom and derives
`:delta` as the suffix of each stream separately, so a deadline park during
`:waiting` carries the real view and only the new output. Concatenating
stdout and stderr before comparing would re-emit the whole buffer whenever
both grew. `:operation-id` is the pending operation id; use it as the shell
request id if the Owner must stop the session.

A missing `:call` port is a composition error: `call` fails the program with
`:UNSUPPORTED_OPERATION` and `:reason :call-port-missing` before it stages a
pending operation. It must not be reported as a `:FAILED` command result,
which would invite the model to retry a command that never ran.

`hc.server.program.shell-port/shell-call-port` is the reference adapter over
`hc.server.shell`: it starts a managed session, subscribes to live output,
emits views, awaits the terminal result, and returns it. Application assembly
must inject it as `:call` and provide a `:context-fn` for token terms, claim,
cwd, purpose, and output mode. The port owns `W` serialization, session
boundaries, stop semantics, and output collection. A provisional
`:UNCERTAIN` result is not returned while the session still holds `W` or
managed activity: the port waits for the late watchdog terminal, so a
program call keeps its real responsibility until the process is observed
ended. While a command runs, a deadline park does not stop the command; the
program resumes at the next checkpoint with the real result. If the shell
port throws after a park, the runtime turns the throw into a
`{:status :FAILED ...}` command result and resumes it, rather than failing
the program and losing the park. A port that has already started a process
must return an uncertain result instead of throwing when its live view or
await fails; the reference adapter does this for subscription and await
failures. If any port returns an uncertain status while cancellation is
pending, the runtime finishes an `:OUTCOME_UNKNOWN` receipt with the pending
operation and command view, never a clean `:CANCELLED`.

When a call returns an unconfirmed result without a pending cancellation, the
operation moves from `:pending` to an internal `:unsettled` set. It no longer
blocks the program thread, but it stays visible in park receipts and in a
later `cancel!`; that cancellation then finishes `:OUTCOME_UNKNOWN` with the
unsettled operation instead of pretending the external resource ended.

### 4.3 `:control`

Generic form:

```clojure
{:execute! (fn [ctx {:keys [primitive request]}] result)}
;; ctx adds :tenure-id :lane :workspace for review/mission routing.
```

Per-primitive fallback: `{:split (fn [ctx request] ...), ...}`. Primitives:
`devops review split claim finish cancel replace release reopen comment update
take-or-die park-slot resolve-action acknowledge-recovery abandon
askPreviousScope assume`.

## 5 Lifecycle, park, and unpark

States: `:running` → `:draining` → `:waiting` → `:running`; on completion
`:settling` → `:completed`/`:failed`; cancellation gives `:cancelled`.
While a call is in flight, `cancel!` returns the receipt status
`:CANCELLING` but the lifecycle state stays `:waiting` (or `:parked`) until
the call returns; `:CANCELLING` is never a lifecycle state.
`:parked` is entered from any active state. A `call` drain uses
`:draining` while it commits staged file ops, not `:settling`. Final
settlement uses `:settling` and goes directly to a terminal state; it does
not pass back through `:running`. Uncaught exceptions and control aborts
also pass through `:settling` before `:failed`/`:cancelled`, and the deadline
is canceled on that transition.

While a protected batch is applying, or after it applied but before the
program thread has fixed a terminal that carries its commit, `cancel!` must
not produce a clean `:CANCELLED`.  The runtime records this window in
`:settle-pending`; `cancel!` returns
`{:status :CANCELLED :cancel-after-apply? true :settle-status ...}` and
leaves the program active.  The program thread then finishes the batch and
its terminal carries `:commit`/`:commits`.  The marker is cleared when that
terminal is fixed (or when a rejected batch applied nothing), so a later
cancellation is clean again.

Cancellation and abort are private `Error` subclasses, not `ExceptionInfo`.
The sandbox allows a program to catch `ExceptionInfo`/`RuntimeException`, so
an exception-shaped control signal could be swallowed; `Error` is not in
`sandbox/allowed-catch` and cannot be constructed by a program.  An unrelated
`Error` (for example a program-thrown `AssertionError`) is still a real
failure.

A park is a real JVM stack continuation: the program thread blocks on a
promise, and `evaluate-program!` returns the receipt. The thread keeps locals,
the in-memory batch view, and pending operations. A later `unpark` delivers a
value to that promise and the same stack continues. This is not source replay.

Every receipt carries `:call-id` and, when supplied, `:attempt-id`, plus
`:name`/`:role`. Park receipt fields: `:name :state :reason :payload :source
:numbered-source :pending-operations :command :delta :remaining-wait-ms
:control-results`, plus
`:interrupt true` for `ask`, `:park-id`, and `:revision`. `:delta` is output since the previous
receipt; the runtime consumes it when it delivers the receipt, so a second
park with no new output has an empty delta. Terminal receipts also carry
`:control-results`, a vector of `{:primitive ... :result ...}` entries, so a
successful `finish` remains visible when the later terminal settle fails
`:CLAIM_CHANGED`.

`:source` is the line-numbered program text (§4.3.4); `:raw-source` keeps the
submitted text without line prefixes.  `:remaining-wait-ms` is the remaining
deadline budget captured at the park instant, before the deadline is
canceled; a deadline park therefore reports 0 or a few milliseconds, and an
explicit/where/ask park reports the budget it had at that point.

Every receipt also carries `:commits`, the applied commit results in program
order. `:commit` is the final settle only; `:commits` includes earlier
pre-call drains. If a shell call fails after its drain, the program fails and
`:commit` is nil, but `:commits` still shows the file batch that was applied
before the shell started. Each entry is
`{:phase :result :operation-count :read-set-count :checks-count}`; raw staged
byte arrays are not copied, so the ledger remains EDN and can travel through
an unpark wrapper's `force-result`.

`unpark` checks, in order:

1. observation: target exists and is parked at the expected `:park-id`
   (`:STALE_CONTROL` otherwise; a supplied `:observed-park-id` is compared);
2. non-self: a program cannot unpark itself (`:INVALID_ARGUMENT`).  A
   self-reference that is not parked reports `:INVALID_ARGUMENT` rather than
   `:STALE_CONTROL`, because the specific diagnosis is more useful;
3. existence: unknown name is `:PROGRAM_UNKNOWN`;
4. capability: the caller's effective set must contain `:unpark`.

One park point resumes once. A second `unpark` at the same point returns
`:STALE_CONTROL` with the target's current receipt. A new park point may be
resumed normally.

## 6 Deadline

`deadline-ms = UTF-8 byte length of :intent * 50`. The deadline is fixed when
the program starts and re-armed from the new control request's `:intent` when
an unpark resumes the target.

At expiry the runtime marks the program `:parked` with reason `:deadline`,
cancels the old timer, and returns the receipt. The program thread parks at
the next checkpoint: every capability call, and the injected `checkpoint!`
forms at `fn*`, `loop*`, `let*`, `do`, and `letfn*` entry points. A final
checkpoint prevents a deadline-parked body from silently completing.

`call` sets `:waiting` and checks the deadline once more before starting the
shell. If expiry wins, the receipt carries the live `:command` view published
through `:on-command-view` (stdout/stderr, byte counts, duration, exit
status) and `:delta` since the previous receipt; the shell continues and the
program resumes at the wait checkpoint with the real result. If the shell
port throws after the park, the throw becomes a `{:status :FAILED ...}`
command result and is delivered after unpark instead of failing the program.

A pre-call drain is not finalization. `settle!` cancels the deadline while
the protected batch applies; after a successful drain the runtime re-arms it
at the original wall-clock instant, so a staged write before `call` cannot
disable the deadline for the shell wait and drain time still counts. An
instant already in the past parks at the next checkpoint before the process
starts.

`where` and `whereAll` checkpoint before and after their pure scan. If the
deadline timer already parked the program during the scan, `:deadline` wins
and the target resumes from that park point. If the ambiguity result is
produced before the timer runs, `:where-ambiguous` wins even when the nominal
deadline instant has passed; the model gets the candidates, and the unpark
re-arms the deadline from the control request. This is a deliberate
model-facing precedence choice, not a second park point.

When the body returns and any park has resumed, the runtime cancels the
deadline before entering `:settling`. A timer that fires at the evaluation
boundary therefore cannot turn a completed body into a spurious park; the
deadline governs evaluation, not final settlement or the protected commit.

**JVM limitation.** The JVM has no resumable first-class continuation. A
single long-running library call, a regex/CPU-bound primitive, or a tight
computation that never reaches a checkpoint cannot be preempted without
losing the stack. The deadline receipt is still returned on time, and the
thread parks when it next reaches a checkpoint. `Thread.suspend`,
`Thread.stop`, and unsafe stack capture are deliberately not used. If a hard
CPU or memory quota is required, run the evaluator in a separate OS process;
this package is an in-process capability sandbox, not a resource jail.

## 7 Idempotency and names

- Call receipts are keyed by `:call-id`. Same fingerprint replays the stored
  receipt, including a parked receipt after the program later completes.
  Different fingerprint is `:CALL_REPLAY_MISMATCH`.
- The registry is process-local and never persisted. A process restart loses
  replay and all parked continuations.
- An active program name (`:running`, `:waiting`, `:draining`, `:settling`,
  `:parked`) rejects a new start with `:NAME_TAKEN`. A terminal name can be
  reused. Old call receipts still replay by `:call-id`.
- Terminal program records are retained so `unpark` can distinguish
  `:PROGRAM_UNKNOWN` from `:STALE_CONTROL`.

## 8 Sandbox

`hc.server.program.sandbox` creates one namespace that refers an explicit
subset of `clojure.core` and `clojure.string`, then interns the capability
functions. It refuses:

- qualified symbols outside `clojure.core`/`clojure.string` whitelists;
- class symbols, constructor syntax, and all interop except the compiler's
  own `(. chunk nth i)` for `for`/`doseq`;
- `def`, `set!`, `var`, `binding`, `with-redefs`, `eval`, `read-string`,
  `load-string`, `slurp`, `spit`, reflection, threads, atoms, agents, refs,
  promises, futures, and namespaces;
- `new` outside `java.lang.IllegalArgumentException`,
  `java.lang.AssertionError`, and `clojure.lang.LazySeq`;
- `catch` of `Throwable`/`Error`, so runtime control signals cannot be
  swallowed.

The whitelist also denies `hc.compose.mission.proof`, so a program cannot
call `proof/token` or otherwise obtain the in-process W capability.

Sandbox error details store raw EDN values in `:found`/`:form`; they are not
`pr-str` strings. `receipt/render` prints them with UFSS, so a quoted form,
Windows path, or multiline value reaches the model as structured EDN/raw
fences rather than escape noise. Non-EDN receipts and payloads use a small
printable `:UNSUPPORTED_OPERATION` fallback with `:call-id`/`:attempt-id`
correlation where available.

`allowed-core` is the security boundary. Adding a name is a security
decision. Capability names win over same-named core/string vars
(`update`, `comment`, `replace`, `split`); use `clojure.core/update`,
`clojure.core/comment`, and `clojure.string/replace` when the pure function
is intended. `clojure.string/split` is not exposed because it compiles an
arbitrary regex inside the JVM, where the cooperative deadline cannot
interrupt catastrophic backtracking; use `split-lines` or exact `where`
slicing.

The whitelist still admits pure functions that can realize an unbounded lazy
sequence (`pr-str`, `sort`, `frequencies`, `count`, `into`, ...). A single
such call can run past the deadline without reaching a checkpoint. This is
the same cooperative-deadline limit as any long library call; a hard CPU or
memory guarantee requires a separate OS process.

Program results must be EDN. The compiler injects a final `force-result`
call inside the program; it bounds node count, depth, and text bytes (2 MiB
per string/keyword/symbol by default), realizes only bounded lazy sequences,
and rejects functions, classes, atoms, and other opaque values while the
program is still running. Errors are sanitized to
EDN before they enter a receipt. An invalid result fails before settlement,
so a program that writes files and returns a non-EDN value does not apply its
batch; this avoids retries double-applying a change the model could not
observe.

Input is read with `hc.core.ufss/read-input`, so standard EDN, standard
escaped strings, and `#"..."#` / `#"""..."""#` fences are accepted. Output is
rendered with `hc.core.ufss/write-str`.

## 9 Known limits and honest constraints

1. Continuations are process-local. A restart cannot resume a parked program;
   the caller must resubmit from current facts.
2. Deadline parking is cooperative. See §6. There is no hard memory limit.
3. Each parked program holds a daemon thread and its batch session. Many
   parked programs consume stack and heap; `stop!` terminates them.
4. `cancel!` before `:applying` finishes a program with no in-flight call as
   `:cancelled` and wakes a park with a sentinel; the cancelled receipt
   carries `:call-id` and `:attempt-id` for correlation. During a protected
   apply it does not interrupt the commit; the immediate return is
   `{:status :CANCELLED :cancel-after-apply? true ...}`, the minimal batch
   finishes, and the terminal cancelled receipt then carries the commit. A
   call still inside the `:call` port is different: `cancel!` requests a
   stop through `:call-stop!` and returns
   `{:status :CANCELLING :cancel-pending? true ...}` with the pending
   operation ids. It does not fix a terminal, deliver the park sentinel, or
   interrupt the thread. The call's real return settles the program; a
   reliable result becomes `:CANCELLED` with its command view marked
   `:CANCELLED` (exit evidence kept), an uncertain result becomes
   `:OUTCOME_UNKNOWN` with the pending operation and command view retained,
   and a `:FAILED` call result stays `:FAILED`.
   A clean `:CANCELLED` receipt is the program thread's terminal and carries
   the last `:pending-operations`, `:command`, and `:delta` from the program
   view; an operation listed there did not start or already returned.
   `:OUTCOME_UNKNOWN` is the non-settlement that keeps the external resource
   responsible.
   `run-program!` recognizes only its own private `Error` cancellation
   signal, never a program's unrelated exception. `stop!` joins threads for
   at most one second per program and returns `:active` descriptors
   (including pending operations) for programs whose in-flight shell
   prevented an immediate terminal.
5. `call` does not acquire `W` itself. The `:call` port must serialize the
   shell session through the shared write gate and publish its action.
6. The call-id and name registries are not persisted and are not pruned.
7. `:on-terminal` callbacks run on the program thread. Exceptions from a
   callback are swallowed; they never change the program result.
   `:before-terminal` runs before the terminal segment is delivered, so a
   caller that observes the terminal also observes its effects (for example
   workspace alias cleanup before an `unpark!` returns).  It is the
   synchronous publication barrier and must not block or reenter the
   runtime.  Ordinary `:on-terminal` notifications run after publication, so
   they cannot delay the terminal receipt.
8. The sandbox blocks names, not every possible resource path. A whitelisted
   pure function with large input can still allocate heavily. Use a separate
   process for untrusted code.
9. A parked program must always have a resumable park point. If a race leaves
   `:state :parked` with no park promise, the runtime fails the program with
   `:reason :park-point-missing` instead of spinning in `await-unpark!`.

## 10 Tests

```bash
clojure -M:test -n hc.server.program.receipt-test \
  -n hc.server.program.sandbox-test \
  -n hc.server.program.capability-test \
  -n hc.server.program.shell-port-test \
  -n hc.server.program.runtime-test \
  -n hc.server.program.workspace-integration-test

clojure -M:test -n hc.compose.workspace-test \
  -n hc.compose.workspace-integration-test
```

The suite covers UFSS input, line-numbered source, macro expansion, sandbox
escape attempts, role gates, explicit and ambiguous parks, Unicode and
nearest-diagnostic resumes, ask, deadline during a loop and during a shell
wait, four unpark checks, call-id replay and mismatch, name occupancy and
reuse, cancellation, terminal callbacks, park-delta consumption, real
`hc.core.workspace-gate` writes, read-set conflict with an untouched disk, Plan
draft/read policy, pre-shell draining, non-EDN results before settlement,
`finish` preserving the staged batch until terminal settle, raw-EDN sandbox
errors, UFSS receipt round-trips, and the non-EDN render fallback. The
compose bridge regressions (`hc.compose.workspace-test`,
`hc.compose.workspace-integration-test`) cover the real `run-program!` path,
including the parked `where` diagnostic. Tests use only self-created
temporary workspaces and delete them.

Additional behavior tests cover `:commits` ordering across call drains and
final settlement, a shell failure after a drain (the applied commit survives
in `:commits` while `:commit` is nil), and the `:draining`/`:waiting`/
`:settling` phase sequence. Context tests cover `:tenure-id`/`:lane`/
`:workspace` propagation to open-session/call/control and their inclusion in
the call-id fingerprint. Deadline-waiting tests cover a live command view
(stdout, byte count, duration, delta) and a shell throw after the park that
resumes as a `:FAILED` command result. `shell-port-test` covers the reference
adapter's live chunk view, terminal view, request identity, and context
mapping without starting a real process, including a string purpose for
vector commands. New deadline tests cover a capability-call checkpoint and a
deadline that survives a pre-call drain; call-shape tests cover string,
vector, and `{:program :args}` commands. The compose bridge regression also
covers Plan `:draft-path` / `:write-unlocked?` / `:readable-paths`
propagation from the Owner payload. Plan first-write tests cover safe name
creation, `plans/` parent creation, `:DRAFT_NAME_TAKEN` for an existing file,
`:INVALID_DRAFT_PATH` for an unsafe name, and `:DRAFT_PATH_LOCKED` after the
first path is fixed. Cancellation tests cover
`:call-stop!` stopping a pending shell by operation id, the deferred
`:CANCELLING` acknowledgement, unconfirmed-call `:OUTCOME_UNKNOWN`, and an
unrelated `ExceptionInfo` that must remain a failure. Workspace tests cover
different workspace identities producing `:CALL_REPLAY_MISMATCH` and
non-scalar workspace descriptors being rejected. Role-surface tests cover
normal-worker `:TOKEN_REQUIRED`, holder `call`, and dedicated-worker `call`
without a token at the runtime gate.

Regression tests added with the audit fixes cover: the
`:settle-pending`/`cancel!` window (a pending applied commit defers a clean
terminal), a shared `:attempt-id` yielding `:AMBIGUOUS_TARGET`, non-map
`evaluate-program!`/`unpark!` requests returning `:INVALID_ARGUMENT`, a
program that catches `ExceptionInfo` around a cancelled call (the private
`Error` signal still terminates it), per-stream `:delta` when stdout and
stderr both grow, the reference workspace port forwarding `:task-id` and
`:program-name` to a board-backed commit, and `:source`/`:raw-source` plus a
non-zero `:remaining-wait-ms`.
