# `hc.compose.mission.devops` — real Plan dedicated worker

Local specification for the execution half of the Plan dedicated worker.  The
contract and the host-side request/complete transitions remain in
`hc.compose.mission.worker` and `hc.compose.mission.host`.

## Purpose

Each Plan tenure owns at most one dedicated worker.  This module runs that
worker on the task model and returns its final report verbatim.

- `hc.compose.mission.worker` decides *when* a worker is spawned or steered.
- `hc.compose.mission.devops` owns the live model session, the read-only tool
  loop, cancellation, and report delivery.
- `hc.compose.mission.host/devops!` commits the request, calls the runner
  between commits, and commits the completion.

## Model ownership

The runner refuses an effect whose `:model-role` is not `:task` or whose
`:model-id` is blank.  The model gateway receives a snapshot with
`:role :plan-worker`; the application maps that role to the task model.  The
worker never uses the main model.

## Reuse and steering

The host gives every request the same `:worker-id` while the Plan tenure is
alive.  The runner keys one session by `:worker-id`:

- `:worker/spawn` creates the system prompt and first user message;
- `:worker/steer` appends a user message to the same session and starts a new
  turn;
- a session for another tenure is replaced, never silently mixed.

The model request and tool results stay in the session history, so the next
`(devops ...)` call is a steer on the same context.  The runner serializes one
run per session.

## Read-only tool surface

The runner advertises exactly:

```text
cat stat ls where whereAll park unpark call
```

`write`, `mkdir`, `rm`, `mv`, `split`, `claim`, `finish`, `cancel`, `replace`,
`release`, `reopen`, `comment`, `update`, `take-or-die`, `review`, `board`,
`token`, `wake-all`, and `ask` are forbidden.  A forbidden tool call is
returned to the model as an error tool result; it is never executed.

The tool executor is injected.  A result is
`{:status :ok|:failed :text "..."}`.  `hc.server.app.devops/tool-executor`
implements the real read-only workspace adapter.

### Continuations (`park` / `unpark`)

`park` and `unpark` are worker-loop continuations, not adapter no-ops:

- `park` stores a named continuation with its payload, appends the parked
  receipt to the session, and ends the current devops call with
  `{:status :interrupted :report "<parked receipt>"}`;
- the next `(devops ...)` steer reuses the same worker session, so the model
  can call `unpark` with the parked name;
- `unpark` resumes that continuation exactly once and returns the injected
  value in the tool result;
- a second resume returns `:STALE_CONTROL`; an unknown name returns
  `:PROGRAM_UNKNOWN`; a second `park` with an occupied name returns
  `:NAME_TAKEN`.

The continuation survives across devops calls in the same live worker
session.  It does not survive worker death or process restart; the next
devops call creates a fresh worker and no old parked name is silently
resumed.

## Report and cancellation

`run!` returns the host completion shape:

```clojure
{:status :ok | :failed | :interrupted
 :report "<full text>"}
```

The report is the final assistant text, unchanged.  It is not summarized,
truncated, or moved to an artifact.  A tool loop is bounded by `:max-turns`
(default 16) only to stop runaway loops; the report itself has no size limit.

`cancel!` accepts `{:worker-id ...}` or `{:call-id ...}`.  It cancels the
current model attempt, wakes the waiting run, and returns
`{:status :interrupted :report "<partial or explicit interruption text>"}`.
The host commits that report through `devops-complete!`; the completion
receipt exposes `:worker-result-status` so a caller can distinguish
`:ok`, `:failed`, and `:interrupted`.

## Unknown outcomes and retry

A failed `:devops` tool execution is committed as `:outcome-unknown`, not as
a retryable failure.  The Owner must not automatically replay a worker call:
the model may have produced external observations or side effects before the
failure.  The projected tool result is the reconciliation material; a new
call is a new decision, not a blind retry.

## Public API

```clojure
(devops/runner {:model {:start! f :cancel! f}
                :tools {:execute! f}
                :max-turns 16
                :timeout-ms nil
                :clock f})
;; -> {:run! f :cancel! f :close! f :sessions f}

devops/devops-tool-spec
```

`hc.server.app.devops` adds:

```clojure
(app-devops/runner {:model-gateway gw :files-service fs})
(app-devops/run-devops! {:runner r :command! f}
                        {:tenure-id ... :call-id ... :verbose-intent ...})
```

## Application integration boundary

`run-devops!` does not commit state.  `:command!` is supplied by the
application's single Owner commit boundary and must accept the mission
commands `:devops-request` and `:devops-complete`.  This keeps the worker
outside the Owner mailbox while request and completion remain Owner commits.

The Owner wiring is present:

- `hc.compose.owner.guards/open-user-turn-guard` injects
  `devops-tool-spec` from `:config :plan-tools` into the Plan model snapshot;
  the app model gateway refuses a Plan turn with `:model-tools-unavailable`
  when the selected model does not declare `:tools`, so this injection never
  becomes a required capability the model cannot honour;
- `hc.compose.owner.handlers/apply-model-event` merges the model tool-call
  `:arguments` and `:tool-name` into the call request and marks `devops` as
  `:executor :devops`;
- `:devops` is a recognized Owner tool kind in `state/tool-kinds`,
  `dispatch/effect-kinds`, tool-result projection, and Scope settlement;
- `hc.server.app.system` builds the per-session runner with the real model
  gateway and read-only files service, and supplies a `:devops` executor
  that calls `run-devops!` through the Owner command port;
- `:devops-request` and `:devops-complete` are Owner command kinds that
  delegate to the mission host's pure transitions.

The synchronous worker still runs outside the Owner mailbox.  Request and
completion are separate Owner commits; the runner reuses the same worker
session for a steer.

## Tests

```bash
clojure -M:test -n hc.compose.mission.devops-test
clojure -M:test -n hc.server.app.devops-test
clojure -M:test -n hc.server.app.devops-integration-test
clojure -M:test:compose -n hc.server.app.devops-owner-integration-test
```

The integration test runs a real mission host, a real read-only files service,
a real tool call, a reused worker session, and a synchronous report.  The
Owner integration test starts a real Owner, sends a user turn, observes the
Plan model request with the devops tool, dispatches the real worker executor,
and checks the projected tool result and the Plan worker's task-model binding.
