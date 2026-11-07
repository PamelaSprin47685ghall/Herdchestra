# hc.server.scheduler — application adapter and durable schedule store

Specification: root `AGENTS.md` §7.1 (current-state store and restart
recovery), §7.4 旅程 6.8 (scheduled tasks), and the §9 schedule clauses.

This package connects `hc.compose.scheduler` to the running application. It
does not replace the pure service. The split is:

- `hc.compose.scheduler` owns recurrence, snapshots, runs, overlap, missed
  facts, and the scheduler state machine;
- `hc.server.scheduler` owns durable storage, the real clock/timer, result
  session creation, Owner commands, notifications, and application lifecycle;
- `hc.server.storage` and `hc.server.recovery` own the database and restart
  classification.

## Files

| File | Responsibility |
|---|---|
| `src/hc/server/scheduler.clj` | Lifecycle, timer, default callbacks, health, CRUD wrappers. |
| `src/hc/server/scheduler/durable_store.clj` | `Store` implementation over an open `hc.server.storage` handle; obligation mirror; standalone opener. |

## Durable layout

Partition `"scheduler"`, partition-scoped documents (no `:session-id`):

    :scheduler-root      id "root"       {:v :revision :seq :device-zone :run-order}
    :scheduler-schedule  id schedule-id  schedule map
    :scheduler-run       id run-id       run map

The whole scheduler state commits in one `commit-migration!` call. Active runs
are mirrored through the storage `:obligations` channel in the same atomic
transaction:

    {:obligation/id <run-id>
     :open? true
     :phase :dispatching | :running | :outcome-unknown
     :executor-generation <process-generation>
     :safe-to-repeat? false
     :scheduler/run-id <run-id>
     :scheduler/schedule-id <schedule-id>
     :scheduler/status <run-status>}

`hc.server.recovery` can therefore classify a stale run on restart instead of
leaving a permanent `:running` document. The scheduler service still makes the
final domain decision. The obligation is removed when the run leaves the
active set.

Save rules:

- the domain revision must be exactly one past the stored root revision;
- the same revision with the same documents is an idempotent replay;
- the same revision with different documents, or a revision gap, is
  `:SCHEDULER_STORE_CONFLICT`;
- a missing root with surviving documents is
  `:SCHEDULER_STORE_CORRUPT`, never an empty scheduler;
- the migration id is deterministic: `scheduler-<revision>`.

`durable-store` never opens or closes storage. `open-durable-store` is the
standalone path; it calls `hc.server.recovery/recovery-entry` and returns a
blocked entry unchanged.

## Exact application hooks

Start after the storage handle, session registry, and Owners exist. Start after
the gateway if `notify!` should reach connected clients.

```clojure
(hc.server.scheduler/start!
 {:storage store
  :registry registry
  :config config
  :gateway gateway
  :tick-ms 1000})
;; => {:ok? true :scheduler runtime :store durable-store
;;     :storage-recovery recovery :recovery service-recovery}
;; => {:ok? false :error {...}}
```

Store the returned runtime in the system map as `:scheduler`.

Stop before Owners and storage close:

```clojure
(hc.server.scheduler/stop! (:scheduler system))
;; => {:stopped? true}
```

`stop!` stops intake, waits up to five seconds for the current tick, unsubscribes
Owner watchers, and shuts down the timer. It does not close storage. It is
idempotent.

Successful CRUD/tick/zone results carry
`:receipt {:status :accepted :result <public result>}` for command idempotency.
Errors carry `:ok? false` and `:error`. Read queries keep their own receipt
payloads (`:schedules`, `:runs`, `:snapshot`, `:health`).

Other exact calls:

```clojure
(hc.server.scheduler/tick-now! runtime)      ; one serialized tick
(hc.server.scheduler/health runtime)         ; timer and recovery diagnostics
(hc.server.scheduler/snapshot runtime)       ; scheduler service state
(hc.server.scheduler/schedules runtime)
(hc.server.scheduler/runs runtime)
(hc.server.scheduler/create-schedule! runtime spec)
(hc.server.scheduler/update-schedule! runtime schedule-id changes)
(hc.server.scheduler/enable! runtime schedule-id)
(hc.server.scheduler/pause! runtime schedule-id)
(hc.server.scheduler/resume! runtime schedule-id)
(hc.server.scheduler/repair! runtime schedule-id [changes])
(hc.server.scheduler/delete-schedule! runtime schedule-id [opts])
(hc.server.scheduler/manual-run! runtime schedule-id [opts])
(hc.server.scheduler/preview runtime schedule-id [opts])
(hc.server.scheduler/set-device-zone! runtime zone)
```

`start!` accepts an optional `:platform` (`:server` by default); `health`
reports it. Platform-specific honesty (for example iOS foreground-manual only)
remains a client contract; the server does not invent a wake-up guarantee.

The app command adapter (`hc.server.app.scheduler`) maps payloads to these
calls. Confirmed payload keys:

- `:scheduler/set-device-zone` -> `{:zone "Area/City"}`
- `:scheduler/preview` -> `{:schedule-id id :opts {:count 3}}`
- `:scheduler/create` -> `{:schedule spec}`; `spec` may carry a client
  `:id`, `:enable?`, `:name`, `:prompt`, `:snapshot`, `:execution`,
  `:recurrence`, `:timezone`, `:output`, and `:state` (`:draft`/`:paused`).
- active-run stop uses the conversation command `:stop-run` with the run's
  `:chat-id`, not a scheduler command.
- `:scheduler/runs` includes `:chat-id`, `:owner-id`, and `:command-id` after
  the start callback attaches the result session.

## Start and stop order

Start:

1. `hc.server.recovery/recovery-entry` opens the store;
2. session recovery and Owners are available;
3. `hc.server.scheduler/start!` calls
   `hc.server.recovery/recover!` for partition `"scheduler"`;
4. `hc.compose.scheduler.service/open-service` loads durable state and applies
   scheduler recovery (interrupts preparing/running runs, records missed
   occurrences);
5. the daemon timer starts and performs its first tick immediately.

Stop:

1. `hc.server.scheduler/stop!` stops new ticks and waits for the current one;
2. Owners stop;
3. storage closes.

## Callbacks

`start!` builds these defaults from `:registry`, `:config`, and `:gateway`.
Each may be overridden for tests or embedding.

`:config` is either a plain map or a zero-argument function.  The application
passes a function that reads the committed settings body, the same source as
`live-session-config`, so a model saved after start reaches the next
preflight and the next result-session creation without a restart.  The
resolver is read once per preflight and once per new-chat start; a nil result
is `{}`.  A map keeps its start-time meaning, which is what isolated tests
use.  A throwing resolver fails the preflight as `:PREFLIGHT_FAILED` and the
session start as `:SCHEDULE_SESSION_FAILED`; it never passes silently.

`start-run!`:

- `:new-chat` creates a result session with
  `registry/create-session!`, passing the frozen
  `{:model {:connection-id ... :model-entry-id ...}}` binding and
  `config[:models]`, plus the frozen `:assistant-id` and
  `:capability-scope`; `:model-id` is accepted as a legacy fallback, but the
  app config is keyed by stable `:model-entry-id`. The scheduler accepts
  `:connections` and `:models` as either an id-keyed map or a sequence of
  entries carrying `:connection-id`/`:id` and `:model-entry-id`/`:id`, and
  normalizes both shapes before preflight and session creation;
- `:continue-chat` uses the saved target chat and its live Owner; the target
  chat's saved model binding is authoritative, so the schedule snapshot does
  not need a second frozen model; a found but non-active target is refused
  with `:RESULT_CHAT_NOT_ACTIVE`;
- `:redo-question` has two real modes:
  - `:redo-mode :branch` (default) targets a **user question** and creates an
    independent branch with `registry/branch-session!` and
    `:before-message-id`; the original question is sent as a fresh
    `:user/message` to the branch Owner. The run records
    `:source-session-id`, `:source-message-id`, and `:branch-session-id`.
  - `:redo-mode :append-version` targets an **assistant reply** and submits
    `:history/regenerate` to the source chat's Owner. The history domain
    starts the run, freezes the outbound prefix before the target, and appends
    a new assistant version to the same message on a successful terminal. The
    run records `:source-session-id`, `:source-message-id`, and
    `:redo-mode :append-version`; no branch is created.
  Both modes require an active source chat, a visible target message, and a
  source model binding. Neither mode fabricates success. The branch inherits
  the source session's saved model binding, and append-version uses it
  directly, so a redo schedule does not need a frozen model in its snapshot;
- submits one `:user/message` command to the Owner with command id
  `schedule-<run-id>` and the frozen prompt (or the original question for a
  redo branch);
- attaches `:chat-id`, `:owner-id`, `:command-id`, and the frozen
  `:capability-scope` to the scheduler run;
- subscribes to Owner notifications before submission and calls
  `finish-run!` when the session run reaches `:completed`, `:failed`,
  `:cancelled`, or `:outcome-unknown`. It also watches the Owner's
  `:awaiting-user?` tenures and reports `:awaiting-user` immediately, without
  waiting for the run timeout; the slot stays held until the run ends.

`stop-run!` submits the Owner `:stop-run` command. `notify!` broadcasts a
CUSTOM `hc/schedule-notification` event with the run id, schedule id/name,
status, reason, chat id, and result files. Without a gateway the notification
stays pending for an explicit retry.

The default preflight checks:

- the frozen snapshot is complete;
- `:new-chat` has a configured model entry;
- `:continue-chat` has a live Owner and, when the committed session is found,
  an `:active` status;
- `:redo-question` needs a source chat and target message; it rejects a
  missing source, inactive chat, hidden message, missing model binding, wrong
  role for the chosen redo mode, an active source run for append-version, or
  an empty question for branch mode. Codes: `:REDO_TARGET_REQUIRED`,
  `:REDO_SOURCE_NOT_FOUND`, `:REDO_SOURCE_NOT_ACTIVE`,
  `:REDO_MESSAGE_NOT_FOUND`, `:REDO_MESSAGE_HIDDEN`,
  `:REDO_MODEL_NOT_SELECTED`, `:REDO_MESSAGE_NOT_USER`,
  `:REDO_MESSAGE_NOT_ASSISTANT`, `:REDO_RUN_ACTIVE`, `:REDO_MESSAGE_EMPTY`.

A busy target is a **skip**, not a broken schedule. When
`:continue-chat` already has a running session run, or
`:redo-mode :append-version` already has an active source run, the preflight
returns `{:ok? false :skip? true :reason :result-chat-busy}` or
`:source-run-active`. The scheduler records a `:skipped` run with that reason
and keeps the schedule enabled; it does not enter `:needs-attention` and does
not start a concurrent turn. `:needs-attention` remains for real configuration
or source problems.

## Restart behavior

`start!` performs storage recovery before opening the service:

- stale scheduler obligations are classified by `hc.server.recovery` (never
  re-run);
- `service/open-service` interrupts `:preparing`/`:running` runs;
- missed occurrences become `:missed` facts;
- `:awaiting-user` and `:outcome-unknown` runs keep their slot;
- pending notifications remain for explicit retry;
- the next occurrence is strictly future;
- backup-restored schedules are forced to `:paused` with no next run, no
  pending DST gaps, no attention issues, and no active run slot, even if a
  storage adapter missed one of those fields;
- stale open obligations for terminal runs are removed even when the domain
  documents did not change (obligation-only reconciliation migration).

## Frozen model and restored connections

- A run freezes its model binding, assistant id, and capability scope when it
  is created.  A later settings change does not rewrite an existing run; a new
  run reads the then-current committed settings.  A restore does not revive a
  pre-restore process handle or a stale binding.
- After a whole-store restore, connection documents are re-projected by the
  settings layer before the scheduler starts; the scheduler only consumes the
  projected model/connection shapes.  A restored connection is unverified by
  default and does not trigger a test request.
- Restored schedules are forced to `:paused`, with no next run, no pending
  DST gap, and no active slot, even if the storage adapter missed one of those
  fields.  No missed occurrence is auto-run after restore.

Recheck: `clojure -M:test:server -n hc.server.scheduler-test` plus
`clojure -X:test:server :nses '[hc.server.backup.maintenance-test]'` for the
restore/restart receipt.  Failure modes: a run reading current settings instead
of its frozen binding, or a restored schedule auto-running a missed occurrence.

## Tests

`test/hc/server/scheduler_test.clj` runs with the server alias and covers:

- durable state round-trip, obligation mirror, recovery classification, and
  restart;
- corruption when documents exist without a root;
- a real `ScheduledExecutorService` triggering a due run, then restarting from
  the same durable store without replay;
- a deterministic clock/timer scenario: due run, active-slot skip, two missed
  facts, and no catch-up;
- real `redo-question` branching through the session registry, preserving the
  source chat and sending the original question to the branch;
- same-session `:history/regenerate` append-version mode, including the exact
  Owner command payload and terminal watcher;
- backup/restore of the scheduler category: restored schedules paused,
  active runs interrupted, history retained;
- default callbacks creating a result session, submitting `:user/message`,
  attaching the chat, passing the frozen assistant/capability scope, and
  finishing the scheduler run from a terminal Owner notification;
- an Owner `:awaiting-user?` tenure finishing the run as `:awaiting-user`
  before the timeout, then correcting to `:completed`;
- the durable obligation phase for an `:awaiting-user` run;
- the standalone `open-durable-store` recovery entry.

```bash
clojure -M:test:server -n hc.server.scheduler-test
```

Compose-only scheduler tests remain under `test/hc/compose/scheduler/` and run
without the server alias.

## Evidence

```bash
clojure -M:test:server -n hc.server.scheduler-test
# 18 tests, 222 assertions, 0 failures

clojure -M:test -n hc.compose.scheduler.time-test \
  -n hc.compose.scheduler.model-test -n hc.compose.scheduler.core-test \
  -n hc.compose.scheduler.service-test -n hc.compose.scheduler.smoke-test
# 49 tests, 437 assertions, 0 failures
```

Current-tree devops verification: scheduler server suite 15/187 and compose
scheduler suite 49/437, both 0 failures. A temp app with a sequence-shaped
`{:models {:connections [{:id ... :models [...]}]}}` config reports
`models.configured? true`, confirming the scheduler and model-adapter shape
normalization. `hc.server.app.system-test` was blocked by unrelated concurrent
shared-file edits at the time; it had passed 1/12 before those edits and
contains no scheduler failure.

The real timer test uses a fixed clock, a real Datalevin store, and a 20 ms
`ScheduledExecutorService`. It advances the clock to a due instant, observes
one `:running` run, attempts a second run while the slot is owned and observes
`:skipped/:previous-run-active`, finishes the first run, advances past two
daily occurrences, and observes exactly two `:missed` facts with no new start.
The backup test creates and restores a real unencrypted backup of the
`:schedules` category and verifies the restored schedule is paused and the
restored active run is `:interrupted/:restored`.

Observed deterministic smoke (fixed clock, real Datalevin, real 20 ms timer):

```text
:create true :enabled? true
:preview [2026-11-04T08:30 2026-11-05T08:30 2026-11-06T08:30]
:due-started true
:due-status :running
:manual-while-active :skipped :previous-run-active :starts 1
:finish true
:missed-wait true
:runs [[:completed nil :scheduled]
       [:skipped :previous-run-active :manual]
       [:missed :missed :scheduled]
       [:missed :missed :scheduled]]
:starts 1
:persisted-runs 4
:stop {:stopped? true}
```

The redo test uses the real `sessions.registry/get-session` and
`branch-session!` paths: the source chat and original user message are read,
the branch is created before that message, the original question is submitted
to the branch Owner, and the run records `:source-session-id`,
`:source-message-id`, and `:branch-session-id`.

Observed redo smoke (real memory persistence + real registry + real storage):

```text
:source-commit :committed
:create true :enabled? true
:manual true :started? true
:command-text What changed in the report?
:run {:status :running
      :source-session-id source-1
      :source-message-id message-1
      :branch-session-id hc-session-...}
:branch {:status :found
         :origin-session-id source-1
         :origin-message-id message-1
         :message-order []}
:source-history (message-1)
```

The source chat keeps its original message; the branch starts empty and
receives the original question as a fresh user message. No original version is
overwritten and no success is fabricated.

Append-version mode submits exactly:

```clojure
{:command-id "schedule-<run-id>"
 :command-epoch <source Owner epoch>
 :payload {:kind :history/regenerate
           :session-id "<source-session-id>"
           :message-id "<assistant-message-id>"}}
```

The history domain appends the regenerated assistant version to that same
message; the scheduler run records `:redo-mode :append-version` and no
`:branch-session-id`.
