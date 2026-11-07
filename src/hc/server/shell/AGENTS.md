# hc.server.shell

Managed black-box shell sessions for root `AGENTS.md` §4.4 and §6. This
package starts real operating-system processes, shares the workspace write
gate `W` with the protected file contract, and reuses the board-adapter
boundary. It never calls the file preparer and never guesses that a command is
read-only.

## Boundary

```text
error      ordinary rejections and EDN-safe throwable descriptions
output     bounded inline capture + artifact log
process    command interpretation, process tree, pumps, stop signalling
read-only  best-effort Plan-worker command policy (not a sandbox)
board      ShellBoard protocol, memory reference, swarm and owner adapters
session    request identity, W lifecycle, result classification, stop API
shell      public facade
```

`hc.server.shell` may require `hc.core.workspace-gate` and
`hc.core.swarm-stm`; it does not require `hc.compose` or `hc.client`.

## Public API

```clojure
(require '[hc.server.shell :as shell])

(def gate (workspace-gate/write-gate))
(def runner
  (shell/runner {:root workspace-root
                 :gate gate                         ; required, shared W
                 :board (shell/swarm-board kernel)  ; real board adapter
                 :artifact-dir artifact-root
                 :resolve-secret (fn [ref] ...)
                 :limits {:inline-output-bytes 1048576
                          :artifact-output-bytes 67108864
                          :stop-grace-ms 2000
                          :drain-timeout-ms 500
                          :child-poll-ms 50
                          :wait-poll-ms 50
                          :publish-retries 5}}))

;; Plan dedicated worker: no board, token or claim; shared W and the same
;; bounded output/stop/uncertain machinery, with best-effort read-only policy.
(def read-only-runner
  (shell/read-only-runner {:root workspace-root
                           :gate gate
                           :artifact-dir artifact-root}))

(def session (shell/start! runner request))
(shell/await session)                 ; first terminal result
(shell/await session 5000)            ; {:status :TIMEOUT ...} past deadline
(shell/run! runner request)           ; start! + await
(shell/call! runner request)          ; alias for run!
(shell/status session)                ; bounded live status
(shell/result session)                ; latest result (watchdog may update)
(shell/request-stop! runner "req-1")
(shell/request-stop! runner {:actor-id :agent-1})
(shell/action runner "req-1")
(shell/retry-publish! runner "req-1")
```

`runner` requires the workspace's existing `WriteGate` or a
`WorkspaceGate` whose `:gate` is used. It deliberately does not create a
private gate: a private gate would not serialize with protected commits.

`read-only-runner` is the Plan dedicated-worker path.  It has no board,
token, claim or register authority, and its results are
`:published? :not-applicable`.  It still acquires the shared W and uses the
same process, output, stop and uncertain-result machinery.  Normal `runner`
remains the only Swarm-holder path and keeps holder/term/claim checks.
The application program runtime selects this runner for
`:dedicated-worker`/`:plan-worker` call contexts and the normal runner for
Swarm holder/worker contexts; `call-stop!` uses the same role split.

`start!` returns immediately. The same `:request-id` with the same payload
replays the original session; a different payload yields
`:CALL_REPLAY_MISMATCH`. Invalid requests and entry refusals return an
already-finished session whose `await` result is the ordinary rejection.

The caller must drain pending in-memory file changes and complete the
protected commit before calling `start!`/`run!`. This package starts the
process; it does not inspect or drain a program's batch view.

## Request

```clojure
{:request-id "req-1"
 :actor-id :agent-1
 :token-term 3
 :task-id :task-1
 :claim :claim-1
 :command ["sh" "-c" "printf hello"]
 :cwd "."
 :purpose "check the retry path"
 :output-mode {:inline-limit 1048576
               :artifact-limit 67108864
               :artifact-path "logs/req-1.log"}
 :env {"NON_SECRET" "value"}
 :env-refs {:DEMO :demo-secret}}
```

`command` is fixed when accepted:

- a non-blank string runs through `/bin/sh -c` (`cmd.exe /c` on Windows);
- a non-empty string vector runs directly without a shell;
- `{:program "..." :args [...]}` runs directly without a shell.

The program `shell-call-port` forwards all three forms. When a program
supplies a vector or map and the context does not supply a string purpose,
the adapter derives a printable label; a collection never enters the
required `:purpose` field.

For `read-only-runner`, `:actor-id`, `:token-term`, `:task-id` and `:claim`
are optional and never validated, because that path has no board or token.
`:request-id`, `:command`, `:cwd` and `:purpose` remain required.  Results
carry `:published? :not-applicable`.

Relative `:cwd` resolves against `:root`; absolute `:cwd` is used as given,
matching the file contract's no-artificial-root rule. The directory must exist
before W is acquired. `:env` is for non-secret values only. `:env-refs` values
come from `:resolve-secret`; only names appear in results and board summaries.

## Entry and start validation

Before waiting for W, the board adapter checks the actor, current holder,
current term, claim owner, task/claim relation and `:ready` state, and returns
an eligibility snapshot (`:task-rev`, `:term-id`, `:claim-id`). After W is
acquired and before the process starts, `begin-run!` repeats the authoritative
check, refuses `:task-meaning-changed` if the task revision moved while the
request was queued, and registers the `:running` action responsibility. A
refusal before the process starts is `:not-started` with
`:file-changed? false`. Once `ProcessBuilder.start` returns, the session is
black-box execution; it can no longer be reported as a refusal that erased
side effects.

The swarm adapter also requires that the shell task is a token task or is
owned by the holder, so the holder cannot retire between action creation and
the running transition.

## W lifecycle

1. `accepted` -> `waiting-gate`.
2. Acquire the shared W in FIFO order. A stop request can leave the queue;
   the result is `:WAITING_FOR_WRITE_GATE` with `:cancelled? true`.  A
   recovery admission predicate can refuse before queueing with
   `:RECOVERY_UNRESOLVED`; no process starts and W is not acquired.
3. Revalidate and register `:running` while W is held.
   If the board event for `:begin-action`/`:advance-action` cannot be
   persisted, the start fails with `:BOARD_EVENT_PERSIST_FAILED`; no process
   is started and the residual action is reported instead of a fake run.
   If `:begin-action` succeeded but the advance to `:running` failed, the
   adapter throws `:SHELL_ACTION_NOT_RUNNING` with the action's `:permit` in
   the exception data.  The session keeps that permit and the pre-start
   failure path publishes the refusal; publication from `:claimed` or
   `:waiting-gate` first advances the action to `:running` and then to
   `:finished`/`:uncertain`.  If the actor or claim changed so that even the
   `:running` advance is refused, the action stays pending with
   `:published? false` and `:retry :publish-only`; it is never silently
   dropped.
4. Start the process, pump stdout/stderr and observe descendants.
5. Hold W until the main process, both pumps and every known managed child
   have ended. If the main process and all known children have ended but an
   unmanaged pipe holder still keeps output open, drain for
   `:drain-timeout-ms` (default 500 ms), then close with
   `:detached-activity? true`, `:output-complete? false` and
   `:external-best-effort? true`. A detached child that escaped observation
   is external best-effort, not a managed guarantee.
6. Release W before returning a `:COMPLETED` result. W is never held between
   two `call` sessions.

A stop request signals the main process and known descendants. It does not
release W, does not roll back side effects, and does not shorten the holder's
life. If managed activity cannot be confirmed ended after the stop grace, the
result is `:UNCERTAIN` and W remains held by a watchdog until that managed
activity actually ends. If only an unmanaged pipe holder remains, the drain
window applies and W is released with explicit output incompleteness.

## Results

`:COMPLETED` means the process ended; `:exit-code-known?` says whether its
exit value was observed. It does not mean the command succeeded, the task
goal is met, or no file changed.
There is deliberately no `:success?` key. Inspect `:exit-code`,
`:cancelled?`, `:stop-signal-sent?`, `:output` and the task state.

`:COMPLETED` with `:published? false` means the process facts are known but
the shell board action still owns a pending publication.  A caller/executor
must treat that as `:outcome-unknown` (not success) until
`retry-publish!` returns `:published? true`; never rerun the process for
this.  Any terminal result with `:published? false` (`:CANCELLED`,
`:SHELL_START_FAILED`, extension refusals) also retains board-action
responsibility for the publish-only retry, even though the process outcome
itself may already be fixed.

`:UNCERTAIN` means the process started but the session cannot confirm its end
or complete output. `:outcome` is `:unknown` and `:file-changed?` is
`:unknown`. The caller must query or inspect before deciding to redo anything.

Ordinary refusals use the stable codes from root §5.4 where applicable:
`:ACTOR_INACTIVE`, `:CLAIM_CHANGED`, `:TOKEN_REQUIRED`,
`:ACTION_IN_FLIGHT`, `:WAITING_FOR_WRITE_GATE`, `:STRUCTURE_CONFLICT`,
`:UNSUPPORTED_OPERATION`, `:RECOVERY_UNRESOLVED`.
Only the gate's stop/interrupt results become `:WAITING_FOR_WRITE_GATE`;
an admission refusal such as `:RECOVERY_UNRESOLVED` keeps its own status and
details instead of being relabelled as a wait.
Shell-specific `:SHELL_START_FAILED` and `:CANCELLED` also have
`:outcome :not-started` and `:file-changed? false`.

Result shape (abridged):

```clojure
{:request-id "req-1"
 :status :COMPLETED | :UNCERTAIN | ...
 :outcome :completed | :unknown | :not-started
 :file-changed? :unknown | false
 :exit-code 0 | 137 | nil
 :exit-code-known? true
 :cancelled? false
 :stop-signal-sent? false
 :interrupted? false
 :cwd "..."
 :command ["sh" "-c" "..."]
 :purpose "..."
 :env-names ["DEMO"]
 :started-at #inst "..."        ; java.util.Date, EDN-readable
 :ended-at #inst "..."          ; java.util.Date, EDN-readable
 :duration-ms 12
 :output {:stdout {:inline "..." :bytes 5 :inline-bytes 5 :truncated? false}
          :stderr {...}
          :inline-bytes 5
          :output-truncated? false
          :artifact {:path "..." :bytes 5 :limit 67108864
                     :truncated? false :error nil}}
 :output-truncated? false
 :output-complete? true
 :output-errors []
 :detached-activity? false
 :known-child-activities [{:pid 123 :command-line "..." :alive? false}]
 :external-best-effort? true
 :write-gate-held? false
 :published? true
 :publish-attempts 1}
```

## Bounded output

Inline output defaults to 1 MiB. The artifact log has a hard per-session cap
of 64 MiB; a larger requested limit is clamped to 64 MiB. Both limits are
enforced while the pumps keep draining to EOF, so a chatty process cannot
block on a full pipe. Truncation is explicit in `:stdout`, `:stderr`,
`:artifact` and the top-level `:output-truncated?`. The artifact receives the
interleaved raw bytes in arrival order. Without an explicit
`:artifact-path`, a temporary file is created under `:artifact-dir` or the
system temporary directory.

`subscribe!` delivers live `{:stream :stdout|:stderr :bytes n :data bytes
:text string}` chunks. The listener runs on the pump thread and must return
quickly. Chunk boundaries may split a multi-byte UTF-8 character; the final
inline text is decoded as UTF-8.

The program adapter's live command view (`shell-call-port`) bounds retained
text by UTF-16 code units, defaulting to the same 1 MiB number as the byte
limit.  For non-ASCII output it can therefore retain more bytes than the
inline cap; the shell's own byte counters and the final inline/artifact
limits stay exact.

## Stop semantics

`request-stop!` returns exactly one of:

| Result | Meaning | W | Responsibility |
|---|---|---|---|
| `:STOP_REGISTERED` | request accepted; no process to signal yet | still held | still counted |
| `:STOP_SIGNAL_SENT` | signal sent; process may not have exited | still held | still counted |
| `:SESSION_ENDED` | observed activity ended and result recorded | released or watchdog-owned | closed |
| `:STOP_UNCONFIRMED` | no session, or the stop effect cannot be confirmed | still held | `:UNCERTAIN` |

A detached pipe holder is not signalled through an invented process handle.
The session closes as external best-effort after the drain window; later file
reads and commits see its effects only through `stamp`.

Stop is not rollback. A cancelled process still gets `:COMPLETED` with
`:cancelled? true` if its exit value is observed; `:UNCERTAIN` if not. An
executor `Thread/interrupt` is treated as an operator cancel path: pending
interrupts are checked before the process starts, and an interrupt during the
wait loop sets `:interrupted? true` and requests the same managed process-tree
stop. Explicit `request-stop!` is preferred because it reports the
registered/sent/ended/unconfirmed distinction.

The Owner cancel-resource hook must call `request-stop!` by the shell
request-id.  Once the session ends, `shell/action` publishes
`:finished`/`:uncertain` with `:result :cancelled? true` and the observed
`:exit-code`; a running session must never be left at `:pending` after the
Owner has cancelled its call tree.  `hc.compose.cancel-integration-test/
operator-esc-stops-a-real-shell-subprocess` is the end-to-end regression for
this path.

While live, `shell/action` reports the phase as status
(`:waiting-gate`/`:starting`/`:running`/`:uncertain`); after a terminal result
it reports `:finished` or `:uncertain`.  `:pending` is only the pre-worker
accepted state.

The hook synthesizes a call terminal only for `:SESSION_ENDED` **with a
result map**, and only for a recognized inner status: `:COMPLETED` maps to
`:succeeded`/`:cancelled` by the observed exit, `:UNCERTAIN` maps to
`:outcome-unknown`, and any other inner status yields no terminal.  A stop
signal, a registered stop, and an unconfirmed stop are not evidence that the
process ended; the Owner keeps `:resource-alive?` set until the executor's
own terminal arrives.  `hc.server.app.cancel-hook-test` pins this mapping.

## Board adapters

`ShellBoard`:

```clojure
(check-eligibility! board request)
(begin-run! board request summary)     ; returns a permit
(publish-finished! board permit result)
(publish-uncertain! board permit result)
```

`memory-board` is deterministic and for tests. `swarm-board` accepts a kernel
map from `hc.core.swarm-stm/create` or a zero-argument provider so an
application can wire the board before the Owner creates the kernel.

`owner-board` adapts a synchronous Owner command function:

```clojure
(shell.board/owner-board
 (fn [command] ...)   ; real Owner: command result with :ok? true and
                      ; :status :accepted, or {:ok? false :error {:code ...}}
                      ; wrapper: {:ok? true :result command-result}
 (fn [] board))       ; current immutable Owner board snapshot
```

It has the same eligibility, running and publication semantics as
`swarm-board`, but every `:begin-action`/`:advance-action` goes through the
Owner's command function. `check-eligibility!` returns
`{:task-rev :claim-id :term-id :actor-id}`; `begin-run!` revalidates that
snapshot under W and refuses `:task-meaning-changed` or `:term-mismatch`
before starting the process. The Owner remains the only board writer.

`hc.compose.owner.board/shell-port` mirrors the same shell contract for the
Owner's board port.  It and the server `owner-board` accept both real Owner
receipts (command result with `:ok? true`/`:status :accepted`, refusal
with `:ok? false`/`:error`) and the legacy wrapper
`{:ok? true :result command-result}`. A wrapper must never leave an action
stranded at `:claimed` after `:begin-action` succeeded; receipt normalization
is covered by tests.

Both adapters use `:begin-action :shell`, advance through `:waiting-gate` to
`:running`, and publish `:finished` or `:uncertain`. Board summaries contain
the purpose, program name, argument count, cwd, exit code, artifact path and
byte counts; they never contain stdout/stderr text or environment values.

The workspace-gate `BoardAdapter` is the same adapter boundary for file
commits; the shell protocol differs only in the running/publication methods
because a process is not a prepared file plan.

## Tests

```bash
clojure -M:test -n hc.server.shell-test
clojure -M:test -n hc.server.shell-owner-port-test
clojure -X:test:server :nses '[hc.server.shell-test hc.server.shell-owner-port-test]'
```

Tests use self-created temporary roots and real `sh` processes. They cover a
real command, a boardless/tokenless read-only Plan-worker command plus obvious
write rejection, nonzero exit with a changed file, entry refusals before start,
shared-W waiting (including a protected commit queued behind a running shell
and a shell waiting for a file while a commit waits for W), bounded output and
artifact truncation plus the 64 MiB cap, start failure, process-tree
cancellation with and without known children, a known child that survives the
outer process, publication retry without a second process, request-id replay,
forced publish failure retaining the action and resuming via `retry-publish!`
without a second process, queued task-meaning change, uncertain outcome plus
late watchdog confirmation,
live output subscription, the swarm adapter, the server owner-board adapter
with real Owner receipt shapes, the compose Owner shell-port with a real swarm
board (term, holder, claim and task-revision changes while queued), both
direct and wrapper Owner receipt shapes, and controlled environment
references.  Every pre-start refusal is normalized to `:not-started` with
`:file-changed? false`, including refusals from the workspace-gate error
namespace.  A manual smoke against a real
`hc.compose.owner/start-owner!` handle also verified `owner-board` shell
cancellation (`sleep 30 & wait` -> stop -> `COMPLETED`/`:cancelled? true`) and
a queued real `:finish` holder/claim change refused before process start.

## Known limits

- A child that detaches before the child monitor observes it is outside the
  managed session boundary. The session records known activities; it does not
  invent a complete list. Later reads and commits see such external effects
  only through `stamp`.
- `ProcessHandle` cannot prove that a command made no file change. Exit code 0
  is only an exit fact.
- The artifact is a single interleaved byte stream; use the inline stdout and
  stderr views when the distinction matters.
- The artifact file is owned by the caller after `finish!`; this package does
  not reclaim it.  A production composition should move it into the managed
  blob store or clean it with the session record.
- The managed process receives EOF on stdin; this path is not an interactive
  terminal.  Commands that require input must be split into a non-interactive
  form or use a future explicit input API.
- `read-only-runner` is best-effort, not a sandbox.  It rejects obvious
  mutating programs, output redirection, package installers, Git write
  subcommands, `sed -i`, and mutating `find -exec/-delete` before the process
  starts.  It also inspects combined short flags that carry a command string
  (`bash -lc`, `bash -ic`) and unwraps `env`/`nohup`/`timeout`/`xargs`/
  `stdbuf`/`nice`/`ionice`/`command`/`exec`/`setsid` to check the wrapped
  program and arguments.  An unlisted program or argument form can still
  write, and the redirection pattern also rejects harmless fd duplication
  such as `2>&1`; both are deliberate conservative limits.  The Plan
  dedicated-worker role remains the primary policy; do not use this path as
  a security boundary.
- Publication is a pure control retry. A failed publication leaves the
  process result intact and returns `:published? false` with
  `:retry :publish-only`.  `shell/retry-publish!` retries only that pending
  board publication from the retained permit/result; it never reruns the
  process and never reapplies files.  `action`/`status` expose
  `:publish-retry?` while the update is pending.  If the in-memory action is
  already `:finished`/`:uncertain`, the retry sends a `:checkpoint` event so
  a prior event-persist failure is not mistaken for a durable publication.
- The request registry is in-process memory. A production composition should
  persist or prune it with the session record; this package does not pretend
  that an in-memory request map is durable recovery.
- While a request waits for W, its `:waiting-gate` phase is exposed by
  `status`/`action`; the board action itself is created only after W is
  acquired. This keeps a queued pre-start cancellation from creating an
  action at all. If an action is created and the advance to `:running` then
  fails, the session publishes from `:claimed`/`:waiting-gate` by advancing
  to `:running` first (the table has no direct
  `:waiting-gate -> :finished` edge); only a changed actor/claim leaves it
  pending with `:retry :publish-only`. The session record is authoritative
  for queued shell requests.

Regression tests added with the audit fixes cover a `begin-run!` failure that
carries a residual permit (the pre-start refusal is published instead of
stranding the action), the `:waiting-gate -> :running -> :finished` advance
used by pre-run publication, and combined `-lc`/`env`/`xargs` read-only
refusals.
