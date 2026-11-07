# hc.compose.scheduler — scheduled tasks, timezones, missed runs, and overlap

Specification: root `AGENTS.md` §7.4 旅程 6.8（定时任务） and the §9 support clauses in the
appendix (schedule object, snapshot, preflight, no overlap, no catch-up,
timezone, run records, pause/delete, timeout).

This package turns a saved schedule into explicit, persisted run facts. It
never starts a model request itself. The service commits state first and then
hands a `:start-run` effect to the host.

## Files

| File | Responsibility |
|---|---|
| `time.clj` | Recurrence arithmetic in IANA zones, DST gaps/overlaps, next-window and preview computation. Pure; no clock. |
| `model.clj` | Schedule vocabulary, validation, snapshots, previews, enable/pause/update rules. Pure; no clock. |
| `store.clj` | `Store` protocol and an in-memory implementation. The service persists the whole state after every accepted transition. |
| `core.clj` | Pure transitions over `{:schedules :runs :run-order :seq :revision :device-zone}`: tick, manual run, finish, notification, recovery, timezone change, import, delete. |
| `service.clj` | Effectful wrapper: clock, lock, store commit, preflight, host callbacks, restart recovery. |

The package requires only `hc.core.*`-free plain data and the JDK. It does not
require the Owner, Missionary, server storage, or the client.

## Schedule and run facts

A schedule is plain EDN data:

```clojure
{:id "schedule-1"
 :name "Morning report"
 :prompt "..."
 :snapshot {:project ... :profile ... :model {:connection-id ... :model-id ...}
            :capability-scope ... :prompt ... :execution ... :output ...}
 :execution {:mode :new-chat}
 :recurrence {:kind :weekly :days [1 3] :time {:hour 8 :minute 30}}
 :timezone {:mode :fixed :zone "Asia/Shanghai"}
 :state :draft | :paused | :enabled | :needs-attention
 :next-run 1793752200000
 :pending-gaps [...]
 :active-run-id nil
 :last-run-id nil
 :revision 1
 :attention nil}
```

A run records the facts required by §9.5:

```clojure
{:id "run-2" :schedule-id "schedule-1"
 :trigger :scheduled | :manual
 :status :preparing | :running | :awaiting-user | :outcome-unknown
         | :completed | :partial | :failed | :stopped | :timed-out
         | :missed | :skipped | :needs-attention | :interrupted
 :scheduled-at 1793752200000
 :scheduled-local "2026-11-04T08:30"
 :scheduled-zone "Asia/Shanghai"
 :started-at ... :started-local "..."
 :ended-at ... :ended-local "..." :ended-zone "..."
 :revision 1
 :snapshot {...} :execution {...}
 :chat-id nil :result-files []
 :notification {:state :pending | :sent | :failed :attempts n}
 :reason ... :issues [...]
 ;; redo-question runs only:
 :redo-mode :branch | :append-version
 :source-session-id ... :source-message-id ... :branch-session-id ...}
```

A run is **active** while its status is `:preparing`, `:running`,
`:awaiting-user`, or `:outcome-unknown`. A schedule owns at most one active
run. Active runs block the next occurrence; they are never cancelled by a new
schedule tick.

## Snapshot rules

- `new-schedule` merges the editable task body (`:prompt`, `:execution`,
  `:output`) into the snapshot.
- Ambient project, profile, or model defaults never rewrite a snapshot.
- Only `update-schedule` with an explicit `:snapshot` adopts a new model,
  profile, project, or capability scope. Prompt/execution/output edits update
  the snapshot because those fields are the task body itself.
- Secret keys anywhere in the snapshot are rejected with
  `:SECRET_IN_SNAPSHOT`. Record credential names and scopes in
  `:credentials` instead.
- `:continue-chat` and `:redo-question` inherit the target chat's saved model
  binding; their snapshots do not need a second frozen model. `:new-chat`
  still requires a complete model binding and an explicit
  `:capability-scope`, so the enable-time tool ceiling is frozen with the
  snapshot. `:redo-question` carries
  `:redo-mode :branch` (default; target is a user question) or
  `:redo-mode :append-version` (target is an assistant reply; the app adapter
  submits `:history/regenerate` to the source Owner).
- The snapshot and every run must contain only plain EDN data so the whole
  state can be persisted and reopened.

## Enable, pause, import, delete

- Creation is `:draft` by default. `create-schedule` with `:enable? true`
  attempts an explicit enable; if the frozen model or execution is missing it
  saves the schedule as `:paused` and returns `:enabled? false` with
  `:enable-error`.
- A blank prompt or blank name cannot be saved. An empty body cannot enable.
- Enabling recomputes the next occurrence strictly after `now`; enabling never
  fires immediately.
- `pause` stops future automatic starts. A running item keeps its slot and is
  visible. `resume` records occurrences missed while paused with reason
  `:paused`, then schedules the next future occurrence.
- `needs-attention` is entered when preflight fails. Automatic triggers stop
  until `repair-schedule` adopts fixes and clears the issues. The failed
  occurrence and later blocked occurrences are recorded as `:missed` facts
  with reason `:needs-attention`; each later tick records the occurrence it
  passed and advances `:next-run`, so the facts appear even before a repair.
- `import-schedule` and `copy-schedule!` always create a `:paused` schedule
  with a new id. They never inherit an enabled state or active run.
- Deleting a schedule stops future triggers. Run history and result files are
  kept by default; `:delete-runs? true` removes the run facts. An active run
  requires `:force?`, which records it `:stopped` with reason
  `:schedule-deleted` and dispatches a `:stop-run` effect so the host stops
  the still-running work.
- Backup-restored schedules (`:restored-from-backup?` or
  `:restored-pending-review?`) are always forced paused on open. A restore
  never revives automatic execution.

## Timezones and DST

- `{:mode :fixed :zone "Asia/Shanghai"}` keeps the saved zone forever.
- `{:mode :follow-device :zone "..."}` adopts a new device/server zone on
  `set-device-zone!` and recomputes the next occurrence. Fixed schedules are
  untouched.
- Historical runs keep their recorded `:scheduled-zone`, `:scheduled-local`,
  and offset. Travel never rewrites history.
- An explicit recurrence or timezone edit returns `:previous-preview` and
  `:preview`, so the client can show the old and new next times before saving.
- A `set-device-zone!` call with the unchanged zone reports no change and
  leaves `:last-zone-change` untouched. A backup-restored paused schedule
  keeps `:next-run` empty when a new device zone is adopted.
- A nonexistent local time (spring-forward gap) becomes a `:missed` run with
  reason `:nonexistent-local-time`. It is never shifted to a neighbouring
  time.
- An ambiguous local time (fall-back overlap) runs once, using the earlier
  offset. The run records `:ambiguous? true`.
- Gaps between `now` and the next real occurrence are kept in
  `:pending-gaps`. A tick records a pending gap once its nominal instant has
  passed, even when the next real run is still in the future.
- A one-shot rule whose local date is already past returns an empty window
  immediately instead of scanning the recurrence horizon.

## Missed occurrences and no catch-up

`tick` computes occurrences in `[next-run, now]`. Occurrences older than the
grace window (`:grace-ms`, default 60 s) become `:missed` facts with no
effect. The occurrence inside the grace window may start one run. The next
run is always strictly after `now`.

Restart recovery (`core/recover`, called by `open-service`) does the same for
every enabled schedule whose next run passed, using reason
`:process-restart`. It never starts a run and never creates a paid request for
a missed occurrence. Calling recovery twice at the same `now` is a no-op.

Multiple missed occurrences are multiple facts, not multiple runs. A manual
run is the explicit user choice to do the work now; it is an independent
`:manual` record and does not change `:next-run`.

## Overlap and timeout

- A due occurrence while a run is active becomes `:skipped` with reason
  `:previous-run-active`. Nothing is queued.
- `manual-run` also respects the active slot and records the same skip.
- The default run limit is 10 minutes; the service accepts up to 30 minutes
  (`:timeout-ms`, 1..1,800,000). A running run past the limit becomes
  `:timed-out`, reason `:timeout`, and produces a `:stop-run` effect. The host
  stops the work; written files and receipts stay.
- A later `finish-run!` may correct `:timed-out` to `:partial` or
  `:completed` when the host learns how far it got.

## Persistence, effects, and restart

`service/tick!`:

1. reads the committed state and finds due schedules;
2. calls the host `:preflight` only for schedules that could start;
3. commits one transition that creates run facts and advances `:next-run`;
4. dispatches `:start-run`, `:stop-run`, and `:notify` effects.

Every accepted transition is handed to the store, even when the domain state
compares equal. The durable adapter mirrors obligation-only rows, so skipping
an equal write would leave those rows stale.

`start-run!` accepts the run (`truthy` or `{:accepted true}`). The service
then marks it `:running` in a second commit. A synchronous `finish-run!` from
the callback is tolerated; the service does not force the run back to
`:running`. The host may call `attach-run!` with `:chat-id`, `:owner-id`,
`:command-id`, or `:execution-target` after creating the result chat; that
records execution identity without creating a second run.

`finish-run!` records the host result. Terminal statuses clear the schedule's
active slot. `:awaiting-user` and `:outcome-unknown` keep it. A terminal
result queues a notification fact; `:awaiting-user` also queues one because
the user must act. Notification retries only send the notification again and
never create a new run.

`hc.server.scheduler.durable-store` is the production adapter over
`hc.server.storage`; it stores each schedule and run as a document and mirrors
active runs through the storage obligation channel. `hc.server.scheduler`
provides the real timer and app callbacks. Compose still owns the state
machine and never opens storage itself.

`open-service` loads the persisted state, forces backup-restored schedules to
`:paused` (clearing next-run, pending DST gaps, attention, and the active slot),
then runs recovery. It always hands the final state to the store so the durable
adapter can reconcile obligation-only differences even when no domain document
changed.

`open-service` loads the persisted state and runs recovery before returning.
`load-state` returning `nil` means a new store. A store that exists but throws
is reported as `:SCHEDULER_STORE_UNREADABLE`; the service never replaces it
with an empty state. A structurally invalid state is
`:CORRUPT_SCHEDULER_STATE`. A write failure is
`:SCHEDULER_STORE_WRITE_FAILED` and leaves the in-memory state unchanged.

Recovery sets `:ended-at` on an interrupted run to the instant recovery
observed it; `:interrupted-at` and `:recovered-at` record the same event.

## Preflight contract

```clojure
(fn [{:keys [schedule now device-zone]}]
  {:ok? true})
;; or
(fn [{:keys [schedule now device-zone]}]
  {:ok? false
   :issues [{:code :MISSING_CREDENTIAL
             :target :model
             :message "..."}]})
;; or a transient skip: keep the schedule enabled, record :skipped
(fn [{:keys [schedule now device-zone]}]
  {:ok? false
   :skip? true
   :reason :result-chat-busy
   :issues [{:code :RESULT_CHAT_BUSY}]})
```

The host checks instance availability, model/credentials, files, and current
permissions. The scheduler never switches to another service or guesses a
same-name path. A thrown preflight is recorded as `:PREFLIGHT_FAILED`.
`{:skip? true}` is different: the due occurrence becomes `:skipped` with the
given reason, the schedule stays enabled, and the next occurrence is still
computed. It is used for a busy target chat or an active same-session run, not
for a configuration error.

## Non-goals

- Starting model streams or tools. The service only dispatches an effect.
- Rendering UI strings or converting local times for display. `time/local-view`
  provides the data; `hc.client.schedule-view` owns presentation.
- Choosing a storage engine. `Store` is the seam; `hc.server.storage` can
  implement it.
- Replaying an interrupted run. A run interrupted by process death is
  `:interrupted` and must be explicitly started again as a manual run.

## Tests

`test/hc/compose/scheduler/` covers:

- recurrence and DST in `time_test.clj` (fixed deterministic cases plus
  `test.check` properties);
- snapshot, preview, and validation in `model_test.clj`;
- missed/no-catch-up, overlap, pause/resume, preflight, timeout, restart,
  timezone, import, and delete in `core_test.clj` (with properties);
- persistence, store failures, notification retry, manual runs, and restart
  zone adoption in `service_test.clj`;
- a real EDN-file, real result-file restart smoke in `smoke_test.clj`.

Run only this package:

```bash
clojure -M:dev -e "(require 'hc.compose.scheduler.time-test \
  'hc.compose.scheduler.model-test 'hc.compose.scheduler.core-test \
  'hc.compose.scheduler.service-test 'hc.compose.scheduler.smoke-test \
  :reload-all) \
  (let [r (apply clojure.test/run-tests \
    '[hc.compose.scheduler.time-test hc.compose.scheduler.model-test \
      hc.compose.scheduler.core-test hc.compose.scheduler.service-test \
      hc.compose.scheduler.smoke-test])] \
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
```

Run the repository test alias:

```bash
clojure -X:test
```

## Acceptance mapping (appendix §9 assertions)

1. Weekly Monday/Wednesday preview before save, real dates and zone:
   `model/preview`, `time/preview-times`.
2. Snapshot survives a changed project default; only an explicit update
   adopts new configuration: `model/update-schedule`, core snapshot test.
3. Advancing the clock past two occurrences records missed facts and never
   catches up; a manual run is independent: `core/tick`,
   `core/manual-run`, `missed-days-never-catch-up`.
4. Fixed zone survives device travel; follow-device recomputes and reports the
   change: `core/set-device-zone`.
5. Previous run still active at the next occurrence becomes `:skipped` with
   `:previous-run-active`: `core/tick`.
6. Invalid credentials enter `:needs-attention` without endless retries or a
   silent service switch: preflight + `core/tick` + `core/repair-schedule`.
7. Notification failure after saved work retries only the notification:
   `core/retry-notification`, `core/mark-notification`.
