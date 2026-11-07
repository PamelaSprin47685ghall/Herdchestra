# hc.server.app — application lifecycle and supervised maintenance

Authority: root `AGENTS.md` §1.1, §5, §7.1, §7.4 旅程 8（设置与诊断） > `src/AGENTS.md` >
`src/hc/server/AGENTS.md` > this file.

## 1 Responsibility

`hc.server.app.system` owns the process lifecycle. It opens the store through
the recovery entry, scans open obligations before accepting work, starts the
Owners, the gateway and the HTTP listener, and closes them in reverse order.
A failed start unwinds every component it constructed and returns a blocked
result that keeps the partial-system refs for a final stop. `hc.server.app` is
the process entry point and owns the machine-wide instance lock and loopback
control channel (`hc.server.app.singleton`): a second start request focuses or
creates a session or submits one command, and never opens a second store or
listener. `hc.server.app.backup` adapts live backup commands and refuses a live
whole-store restore.

The per-session model gateway starts search on a worker after the Owner has
committed the accepted turn. The Owner thread never blocks on the external
request; a stop signal cancels the search and prevents the model request.

`hc.server.app.restore-supervisor` owns the one process-level maintenance path:
validate and preview, stage the selected backup, block new work, drain command
handlers, stop every writer, run the offline restore, start a fresh system, and
publish a durable status that a reconnecting client can read.

## 2 Start and stop contracts

`system/start!` has two arities:

```clojure
(system/start! config)
(system/start! config {:maintenance {:handle! f :status g}})
```

The optional `:maintenance` map is passed to the backup service. A start
without it is honest offline-only mode: `:backup/status` reports
`:maintenance {:available? false :mode :offline-only
:reason :NO_IN_PROCESS_QUIESCE}` and maintenance commands fail
`:BACKUP_MAINTENANCE_UNAVAILABLE`.

Before opening the store or a router, `system/start!` reads the durable
maintenance journal through `journal/startup-blocking-state`.  The gate covers
both writers.  An active offline CLI restore (an active record with
`:request {:source :offline-cli}`) returns
`:BACKUP_OFFLINE_RESTORE_INCOMPLETE`; an active supervisor record returns
`:BACKUP_MAINTENANCE_RESTORE_FAILED`.  Both block with the maintenance ID and
journal path; no store, Owner, or router opens, and the restore is never
re-run.  A corrupt journal blocks the same way with
`:BACKUP_MAINTENANCE_JOURNAL_INVALID`.

The start gate also reads `backup.storage-adapter/restore-safety` from the
restored `config` partition and exposes it as `:restore-safety`; imported
schedules, auto-backup, tools and external workspaces stay paused/disabled
until the operator enables them. The system carries one
`:process-generation` shared by startup recovery and the scheduler; a second
generation must never be minted for the same process. An initialized data
directory (`<data-dir>/initialized.edn`) whose store directory is missing is
`:STORE_MISSING_AFTER_INIT`, not a fresh store.

A terminal failure keeps blocking until an operator acknowledges it when
storage may have been reached: `:failed-needs-recovery`,
`:failed-commit-unproven`, and any `:failed` / `:failed-needs-attention`
record without an explicit pre-restore proof.  The error carries `:status`,
`:needs-recovery?`, `:commit-unproven?` and the journal path, and the recovery
entry lists `:acknowledge-restore-failure`.  The start message names what is
known: a proven commit, an unprovable commit, or no evidence.  `:failed` with
`:restore-attempted? false` is a pre-restore failure (preview, staging, worker
start, or an interrupted `:accepted` / `:validating` / `:stopping` record): no
restore thunk ran, so it is history.  `:failed-before-commit` is history too:
the restore was observed not to reach a commit.  `:stop-failed`, `:complete`,
`:no-changes`, `:already-imported`, `:cancelled` and `:restart-failed` do not
block.  An operator compares `state.edn` with the store and records the
comparison through `journal/acknowledge!` (CLI `acknowledge`); the record then
keeps its failure status and stops blocking.  The same CLI acknowledges a
supervisor record; the supervisor journal stores `:storage-identity` so the
acknowledgement binds the journal state, the plan and the store.

The supervisor's own in-process restart is the one exception to the active
record gate: `start-after-maintenance!` marks the record `:restarting` and
passes its `:maintenance-id` to `system/start!`; the gate lets only that
matching record through.  A cold start never supplies an ID.
`restore-supervisor/prepare!` reconciles an interrupted supervisor record
before `system/start!` reads the gate, so a crash during restore becomes a
terminal decision rather than an unopenable active record.  A record that will
block is not allowed to rotate the command epoch first; the store stays
untouched until the operator acknowledges it.  The offline restore writes that
journal through `hc.server.backup.maintenance-journal`, the same document,
cross-process lock and atomic-write path as the supervisor.

`system/stop!` first closes the command gate and drains in-flight handlers,
then stops the listener, stops the publisher and waits for its in-flight
Datalevin read, retires the gateway, drains Owners, stops their dependencies,
and finally closes the store through `storage/open-close-store!` (intake
barrier, ordered close, closed verification). The publisher sets its stopped
flag before `shutdownNow`, then waits for executor termination and for the
read lock held by direct `publish-now!` callers. A timeout returns
`:stopped? false`, `:terminated? false` and the timeout value; the store then
stays open instead of closing under a native read. `:stopped? true` and
`:drained? true`
only when every writer-facing component stopped, the store close was verified,
and no component reported `:ok? false`, `:stopped? false`, `:drained? false`,
`:closed? false`, `:terminated? false` or `:persisted? false`. It also returns evidence for maintenance:

```clojure
{:stopped? true
 :drained? true
 :failures []
 :components {:http {...} :gateway {...} [:owner "s1"] {...} :store {...}}
 :store-closed? true}
```

A component that throws or reports it did not stop keeps `:stopped? false`.
The store is closed only when all earlier components stopped.
`gateway/stop!` is one of those writers: it returns `:stopped? false` when
the Sente receive loop does not drain within its timeout. `system/stop!`
records the failure and leaves the store open; a second `gateway/stop!`
retries the stop and can observe a later drain. `/healthz` is GET-only; the
composed app route returns 405 for other methods, matching the gateway route.
`system/health` reads the live system atom, so publish/browser/component
failures after startup are visible. `:component-errors` makes health `:ok`
false for construction failures. Fleet reports `:configured?`/`:status`;
a non-Git workspace is `:unavailable` (not a failure), while a corrupt fleet
state is `:failed`. `--check` uses an isolated data directory and workspace
unless the operator supplies them, and exits non-zero when health or the stop
is not confirmed.
A drain failure does not abort the remaining component stops; the store stays
open and maintenance restore is refused. Retry stop or exit the process.
The HTTP listener stop returns
`{:status :stopped|:running|:stopping :stopped? boolean}`; `:running` or
`:stopping` is a failure and also keeps the store open.

Storage retry boundary: `system/start!` calls `recovery/recover-all!`
without a retry scheduler.  The owner-store adapter mirrors every call in
`:retry-wait` into a `:retry-call` obligation.  Recovery returns those in
`:pending-retries`, and startup starts the owning session for each one; the
Owner runtime re-arms the persisted timer, claims through P2/P3, and removes
the index in the same owner-state commit that leaves `:retry-wait`.  A failed
owner start or refused recovery wake is recorded in `:retry-owner-errors` and
leaves the index open; it is never reported as recovered.  A legacy
`:retry-wait` obligation without `:retry-call` is returned in
`:blocked-retries` and left unchanged; `system/start!` refuses to start and
returns a blocked result.  A future non-owner retry producer must add a real
timer, claimer, and terminal closure together; a wake alone is not a claim.

Startup session-list failure: `registry/list-sessions` returning `:error` is
not an empty list.  `system/start!` closes the store and returns a blocked
result carrying the registry error; it never creates a default session over
committed sessions it could not read.  A later publisher listing failure is
recorded as `:publish-error` instead of publishing an empty session set.

The app publisher is incremental.  Each projection is compared with the last
successfully published tree: an unchanged tree (including a tick whose only
difference is `:server/now`) does not consume a feed sequence; a changed tree
is sent as an RFC 6902 `STATE_DELTA` when that event is not larger than the
complete snapshot.  Full `STATE_SNAPSHOT` is reserved for the first publish,
subscription baselines, resync, and deltas that are not smaller.  A publish
that the gateway rejects is recorded in `:publish-error` with the gateway
error; it is never treated as delivered.  A delta fallback to a full snapshot
is recorded in `:publish-fallback`.  A five-second `CUSTOM hc/sync-heartbeat`
broadcast lets clients re-query pending command receipts without a state
snapshot; it does not consume a feed sequence.  The 1 MiB Sente boundary is
unchanged.  A state snapshot or delta that exceeds that boundary is stored as
an EDN object and published as a reference; the publisher's success still
means the feed advanced, while a missing blob store or an object over
`:max-state-object-bytes` remains a recorded `:publish-error`.

Owner-state preflight: after obligation recovery and before model auth, the
model port, speech, materials, the gateway, or any session Owner starts,
`system/start!` reads each visible session's `session/<id>` partition.  A positive partition
version without its `:owner-state` document is `:OWNER_STORE_CORRUPT`; the
store is closed and the start returns the same blocked recovery entry used
for an unopenable store.  A new session with no authoritative partition yet
(version 0) is legitimately empty and passes.  Starting the gateway with a
missing Owner would hide the loss and accept commands no writer can serve.

Existing sessions also seed the new Owner's relay from the first visible
user message (`hc.server.sessions/initial-user-input`): raw text plus
attachment reference metadata.  The same attachments are already covered by
`blob-refs/session-refs`, so passing those refs keeps their blobs alive
without a second reference table.

Program control primitives: the application-owned fallback handles `:devops`
through the devops executor and `:review` through a trusted Owner
`:review/submit` command (`:submit-command!` with the Owner's current
`command-epoch`, no browser operator marker).  After a successful `:plan`
program result, the app reads each applied `plans/<name>.md` file from the
same workspace root and submits an Owner `:plan/write-draft` command; other
roles and failed results do not sync drafts.  The command carries the tool
call's pinned `:tenure-id`; the mission refuses a retired tenure instead of
recording the old bytes in the successor's draft.  Sync errors are recorded
under `:plan-draft-errors`.

Both control primitives take the pinned `:tenure-id` and `:call-id` from the
program control context, not from the model's primitive arguments: the model
supplies only intent or findings.  The `:devops` executor starts the worker
for that exact tenure and call, and the `:review` command names that exact
tenure.  A missing tenure is not filled from "the current" lane: the mission
refuses a retired or unknown tenure instead of applying a late call to the
successor.

Fleet worktree shell: `worktree-evaluate-factory` gives each fleet runtime
its own shell runner over that swarm's board and write gate, with `:call` and
`:call-stop!` wired to the program runtime.  A holder's `(call ...)` runs in
its worktree; the main workspace runner is separate and unchanged.  A holder
attempt is mapped to `:role :clj-worker` / `:program-role :clj-worker` with
`:holder? true`, never to the Plan `:dedicated-worker` role; the shell board
still enforces token, claim, task, and W dynamically.
Fleet model cancellation: `fleet-start-ref` handles `:model/cancel` by
looking up the real gateway handle in `fleet-attempts` and calling the
gateway's `:cancel!`; the attempt index drops the handle on any terminal
event and on cancel.  A missing handle means the attempt already ended, so
cancel is an idempotent no-op.  The supervisor receives the same
`fleet-start-effect!` as both `:start-effect!` and `:cancel-effect!`.
The cancel signal is not a terminal observation: it never synthesizes a call
terminal or clears a resource on its own.

Program cancellation: `program/cancel!` returns a non-terminal
`:CANCELLING` receipt while a `:call` is in flight.  The program runtime's
`:on-terminal` posts the real terminal for `:cancel-requested?` receipts to
the Owner, so a parked program whose shell later ends still closes its
resource.  An `:OUTCOME_UNKNOWN` receipt is not a settlement and keeps
`:resource-alive?` set; `program-stop-terminal` synthesizes a call terminal
only for a fixed `:CANCELLED` receipt.

Shell publication: a `:COMPLETED` shell with `:published? false` is
`:outcome-unknown`, not success.  The payload keeps `:retry :publish-only`
and the board action responsibility.  The executor accepts
`{:op :retry-publish :request-id id}` and routes to
`shell/retry-publish!`, which retries only the board publication and never
reruns the process.  Shell runners use the default bounded
`:publish-retries` (5); the application does not raise it to mask a
sequencing defect.

### 2.1 Gateway policy

`system/gateway-options` is the single policy map derived from the validated
`:gateway` configuration section. `system/start!` passes it to
`gateway/start!` and reuses it for the application-owned routes
(`/attachments`, `/api/workspace`, `/speech`) and `/healthz`. The keys are
`:allowed-origins`, `:trusted-hosts`, `:allow-remote?`, `:max-pending`, and
the four Sente/blob size boundaries plus `:max-state-object-bytes` and
`:max-state-objects` for large state objects. The EDN section rejects
`:allow-remote? true`: the
gateway has no built-in authentication and Host/Origin are not credentials.
Remote access must terminate authentication at a local reverse proxy (the
application then sees a loopback peer) or use a code-level hook.
Binding `:host` to a non-loopback address still exposes the static fallback;
the command and byte routes remain fail-closed.
Authorization hooks stay code-level; the EDN section is a closed schema and
rejects them. `hc.server.app.config/normalize` canonicalizes origins and
hosts before the store opens, and
`merge-persisted-config` never lets the persisted model document override
launch keys such as `:host` or `:gateway`.

`merge-persisted-config` intentionally merges only model fields
(`:connections`, `:roles`, `:default-role`); it does not carry the persisted
config document's `:revision` into the effective config.  The settings
service therefore reads the CAS revision directly from the same persistence
port at construction.  Without that read, a restart starts with empty models
and revision 0; the first save either loses the persisted connections or
conflicts with every persisted revision above zero.

The launch configuration is the instance-level authorization for connection
existence (root `AGENTS.md` §4).  `settings/merge-model-config` therefore
starts from the launch connections, overlays the persisted fields of the
connections the launch still declares, and appends connections created
through the settings UI.  The persisted document records the launch-declared
ids under `:launch-connection-ids` (the union of the stored set and the
current launch set); an id in that set that the current launch configuration
no longer lists is hidden from the effective config, so editing the launch
file to remove a provider revokes it instead of letting the settings copy
resurrect it.  The persisted document keeps the hidden connection, so a
later launch declaration restores its saved fields and an unrelated save
never erases them.  `:removed-connection-ids` records an operator deletion
and keeps it effective even while the launch file still declares the
connection.  Both keys are provenance, not provider configuration:
`model-configuration` drops them before the model adapter or any client
sees the map.  A legacy document without
`:launch-connection-ids` keeps every connection: its origin is unknown, and
an inline `:api-key` is never evidence of launch origin.  A stale row is
recoverable; deleting an operator connection is not.
The config document is partition-scoped and its caller document need not
carry `:id`; `read-config` identifies it by the storage envelope's
`:storage/id`.  A lookup that only checks `:id`/`:session-id` reports
`:missing` immediately after a successful commit.

Session configuration is read live, never captured at start.  `start-internal!`
passes one resolver — `(fn [] (session-config/session-config
(settings/config settings-service)))` — to both sinks: `commands/router`'s
`:session-config` (read once per `:session/create`) and `system/session-port`'s
config argument (read once per Owner `:apply-command`, which is what
`:session/select-model` runs through).  A settings save therefore reaches the
next new session and the next model selection without a restart.  Nothing
back-writes: an existing session keeps its creation-time `:model-binding`, and
a run keeps the snapshot it froze.  A plain map or a captured atom would
return the startup configuration and leave both stale until restart.  Keep
`session-port`'s argument a zero-argument function; a map breaks the same
contract silently.

Startup resumes one Owner per committed session through the owner factory,
then `commands/register-owner!` must register that handle in both the router
and the session registry.  The registry map is what `get-session` and
`list-sessions` merge the live `:session` from; a router-only registration
leaves every resumed session reading its stale creation-time record, so
`:model-binding`/`:pending-model-binding` look nil after a restart.

Extension commands derive their trusted scope from the committed session, not
from the browser payload: `:role` comes from the session capability scope
(`:mcp/write` → `:operator`, `:mcp/destructive` → `:admin`, otherwise
`:viewer`), `:assistant-id`/`:assistant-servers`/`:session` from the session
record, and `:capability-scope` is passed as a hard limit. Session
persistence preserves `:mcp-role`, `:mcp-servers` and `:mcp-session-scope`
when a writer does not include them. The Owner gets
`:extension-tools-fn`/`:skill-context-fn` and an MCP-aware tool executor;
a tool request carrying `:server-id` and `:tool` calls
`extensions-app/call-mcp-tool!`, mapping `:ok?` to `:succeeded`/`:failed`.
Callbacks (`:on-user-send!`, `:on-call-terminal!`) are advisory and never
change the Owner receipt.

The Owner session port also carries the business commit barrier.
`system/session-port` supplies `:commit-session!` and `:reconcile-session!`
backed by the same `SessionPersistence`.  The Owner writes a changed session
record before it commits owner state and before it returns an accepted
metadata receipt, so `:session/delete` is committed as `:deleted` before the
client sees acceptance.  On startup the Owner reconciles its loaded session
with the committed record before accepting work.  The purge precondition and
`registry/purge-session!` therefore read the committed record only; a live
Owner value never creates or hides a deletion.  A live Owner must report
explicit `:stopped?` and `:drained?` before purge or temporary discard may
remove a record.

`commands/router`'s `:session-config` accepts either a zero-argument function
or a plain configuration map.  A map is data, not a resolver: it is never
invoked with zero arguments.  A plain map is fixed at creation, so it cannot
deliver a live settings save; the production assembly always passes the
function above.

`system/model-configuration` is the one projection from the application's
`:models` section to the model adapter's closed schema.  It keeps
`:connections`, `:roles`, and `:default-role` and drops everything else.  The
launch configuration and the persisted settings body share the `:models` key,
and an older launch default carried `:main`/`:task` role placeholders; the
adapter rejects unknown top-level fields, so forwarding the launch map
unchanged made `model-configured?` false and no model port was built even
though the settings UI showed a saved model.  Every adapter-facing boundary
uses the projection: `merge-persisted-config`, `settings/service`'s bootstrap
`:config`, the boot `model-config`/`create-model-port` path, and
`apply-models!` after a live save.  `:attachment-loader` and
`:credential-resolver` are assoc'd after the projection, never before it.
A live save that removes every connection also clears `model-port-ref`.
Keeping the retired port would let the next turn silently reach a model the
operator just deleted; the retired port remains in `model-ports` only so
in-flight attempts keep their handle and the ordered stop path can close it.
`model-configuration` also drops nil optional connection/model fields
(`:headers`, `:options`, `:api-key`, and similar) that settings documents
legitimately leave nil; the adapter's closed schema treats a present
`:headers` as a map and would otherwise reject an otherwise valid save.

The scheduler receives a zero-argument config resolver, not a captured map.
It reads `system/model-configuration` of `settings/config` at each preflight
and each result-session creation, so a model saved after start is usable by
the next scheduled attempt.  A captured map would freeze the startup model
set and leave preflight at `:MODEL_UNAVAILABLE` until restart.

`:settings/test-connection` follows the four-layer contract in root §6.4.
`settings/probe-connection` first GETs `<base-url>/models` for reachability,
authentication, and listing.  That GET never proves generation.  When the
connection has an enabled model and an OpenAI-compatible provider, the probe
then sends one non-streaming `POST <base-url>/chat/completions` with the fixed
text `Herdchestra connection test. Reply with OK.` and `stream:false`.  It
forces no sampling parameter: a token cap that is too small can make a
reasoning model return an empty reply, a false failure.  The request carries
no session history, attachment, tool schema, or user draft.  A non-2xx
status, unparseable body, or empty reply marks `:minimal-request-ok :failed`;
a 2xx status alone is not success.  A failed `/models` call does not prevent
the model request, so a manually added model remains testable when the service
does not implement listing.  A 401/403 on the GET stops before the model
request so a rejected credential is not replayed to a paid endpoint.  With no
enabled model, or a protocol without a probe, the fourth layer stays
`:not-run` with a reason.  The probe uses the same ephemeral credential as a
real request and never persists it.

Role prompts and program tools: `hc.server.app.prompts` owns the outbound
Plan, Reviewer, and Swarm role decoration.  `model-gateway` calls
`prompts/decorate-request` after material injection and before
`sanitize-model-request`, so every provider attempt sees the role system
message even when the frozen Owner snapshot does not carry it.  For a
main-role request, the matching program tool is attached only when the
selected model declares `:tools`.
The gateway resolves that fact per request from the session's committed
`:model-binding` identity and the **current committed settings model entry**
(`:capabilities :tools`).  The binding fixes model attribution; it must not
freeze a capability the operator later declares.  When the live entry is
missing, the binding's copied fact is the fallback, then the startup
main-role configuration before a session has selected a model.  A Plan or
Reviewer request that needs its program tool is
refused before the model port starts when the selected main model declares no
`:tools`: the gateway delivers `:model-tools-unavailable` with code
`:MODEL_TOOLS_UNAVAILABLE`, category `:permanent`, and remedy `:select-model`.
The Owner's central turn and Nudge snapshots carry the same session binding
identity, and the gateway prefers that frozen identity when the request names
it.  This keeps the capability fact aligned with the provider the request
will actually use after a session selection changes.  When a session binding
is present and the current model declares no `:tools`, the Owner send handler
applies the same check through its injected `:model-tools-supported` resolver
before appending a message and returns `:MODEL_TOOLS_UNAVAILABLE`, so no
conversation entry or pending turn is created and the draft stays with the
user.  A pure-chat request (no program role) may still degrade: decoration
removes every `:tools` entry and any `:tool-choice`, and the text request
proceeds.  A request that genuinely needs tools must state it in its own
`:required-capabilities`, and that explicit declaration still fails at the
adapter when the model cannot honour it.  The Owner always prepares the Plan
tool surface (`:config :plan-tools`); the gateway is the wire-level
capability authority, so a session created before settings can select a
tools-capable model later without a restart.  Task-role requests (workers,
holders, and the Plan dedicated worker) are outside the main binding's fact:
they keep their explicit `:required-capabilities [:tools]` contract, so a
task model that cannot honour it fails explicitly instead of silently losing
its program surface.  Reviewer gets `COMMON` + `REVIEWER_PROMPT` and the
read-only `clj-review` schema; worker and holder get `COMMON` +
`SWARM_WORKER_PROMPT` and `clj-worker`, with the Holder addendum selected by
`:snapshot :holder?`.  Plan top-level gets the self-contained `PLAN_PROMPT`
(compact Scope, not `COMMON`) and the `clj-plan` schema; that schema exposes
only `cat`, `write`, `where`, `whereAll`, `park`, `unpark`, `ask` (S1 only),
`devops`, and `review`, and it has no shell, `call`, `eval`, stat/ls, board,
or token capability.  The schema requires
`source`, `intent`, and a flat `:scope`; the program runtime, not the tool
schema, remains the capability authority (`call`/`abandon` only for a holder,
writes only for a worker, read-only Reviewer, Plan draft-only writes).
Decoration is idempotent: a retry replaces the same role tool and does not
add a second role system message.  The Plan dedicated worker is not decorated
here; its own surface owns its prompt and tools.

A snapshot marked `:chat? true` is an Owner-declared plain-text answer turn
(a user message sent with `:chat? true`): decoration adds no role prompt and
no role tool and strips any `:tools`/`:tool-choice`, and the gateway does not
apply the tool-required refusal to it.  The `:program-role` stays on the
snapshot for audit only.

The router resolves `:session-config` once **before** the durable app gate
accepts a new `:session/create`.  A read failure is therefore a clean
`SESSION_CONFIG_UNAVAILABLE` refusal with no session commit, no `:dispatching`
placeholder, and no receipt to replay.  A replay checks the stored receipt
first and does not read configuration: the committed session already holds the
binding it copied.  The resolved map is carried only on the in-process request
so dispatch never reads the resolver a second time.

`hc.server.app.session-config/session-config` converts the provider-oriented
settings body into the normalized session-config shape.  It normalizes model
pricing through `hc.server.sessions.connections/normalize-pricing`, so the
converted value satisfies `hc.server.sessions.config/check-invariants` even
when a provider model carries no pricing information.  Effective capabilities
stay a boolean `true`/`false` map; the client's `:capability-sources` and
`:capability-baseline` provenance fields are metadata and never enter the
session binding.

`snapshot/settings-model-view` is the one model projection for both
`settings-view` and the `:settings/list-models` receipt.  It keeps
`:display-name` next to the legacy `:name`, plus the capability, capacity,
price, and lifecycle fields the settings UI renders; provider transport
fields such as `:options` and `:chat-model` stay server-side.  Connection
identity remains the `:settings/by-id` and `:settings/models` map key, so two
connections with the same display name never merge their model rows.  The
projection also carries `:capability-sources` and `:capability-baseline`, so
the settings UI can mark a user declaration and restore known information
without inventing support.  The session-config adapter prefers
`:display-name` over `:name` when it freezes a new binding, while older
configs and stored snapshots keep the `:name` fallback.

`snapshot/baseline` also resolves every visible session's stored
`:model-binding` against that same live settings projection and publishes
`:model-availability`.  A binding is an immutable run snapshot, so it cannot
answer whether the connection is currently enabled or still exists; the
client needs that separate fact to explain a blocked send and offer a
replacement.  The projection uses the settings row facts: missing connection
is `:connection-removed`, disabled connection is `:connection-disabled`, a
failed auth status is `:auth-expired`, a missing model is `:model-removed`, a
disabled model is `:disabled`, and otherwise the model's own availability or
`:unconfirmed`.  The field is omitted for sessions without a binding.

The summary carries the wire shape the client consumes:

```clojure
{:model-binding     {:connection-id "alpha" :model-entry-id "same-model" ...}
 :model             {:connection-id "alpha" :model-entry-id "same-model" ...}
 :model-availability :connection-removed}
```

`hc.client.workbench.model/session-with-model` attaches `:model-availability`
to `:model` as `:availability`; `conversation.composer/send-blocker` checks it
before the empty-draft rule, and `conversation.surface` appends the
connection explanation to `#hc-composer-hint`.  A binding whose connection is
still configured keeps `:unconfirmed`, which is usable: the explanation only
appears when the live projection says the connection or model is actually
unavailable.  Editing the launch file to remove a connection must therefore
reach this projection (see the launch-authority merge above); otherwise the
wire honestly says `:unconfirmed` and the composer correctly reports only
the empty draft.

The fleet retention scheduler (`fleet_gc.clj`) is started only when the fleet
service started. It calls the service's `gc!` with a fixed delay, serializes
timer and manual ticks, and records the last result/error. `system/stop!`
stops it before `:fleet-supervisor` and `:fleet`, waits for an in-flight
sweep, and reports `:terminated?` in the component evidence. The scheduler is
application lifecycle, not a fleet command: the explicit `:fleet/gc` command
remains available and uses the same control.

Fleet current-state restore: `config/normalize` derives
`[:fleet :state-file]` under `<data-dir>/fleet/state.edn`, and
`system/start!` passes it to `fleet/start!`.  The fleet service restores the
persisted swarms, FIFO completion queue, master version, and each worktree's
board, token slot, actor state, and old write-gate evidence before returning;
a corrupt or mission-mismatched file becomes `:fleet-error` and no fleet
service, never an empty fleet.  A restarted running swarm whose runtime
survived resumes from its restored board/actors with in-flight actions and
model attempts fixed as `:uncertain`/`:outcome-unknown`; nothing is replayed.
A running swarm without runtime state becomes an explicit
`:outcome-unknown` failure; completed queue results remain joinable and
queued swarms resume after runtime reattachment.  A legacy v1 state file is
accepted and upgraded on the next write; only its queued swarms (or a
queued swarm whose v2 runtime entry is missing) get a fresh runtime, marked
`:runtime-source :recreated`.  `system/stop!` stops the GC
timer, supervisor, and fleet service in that order; the service persists its
final control and runtime state on `stop!`.

### 2.2 Client baseline anti-resurrection

`snapshot/baseline` is the only client-facing current-state projection. It
must not let permanently deleted content return through an older or richer
source:

- `:session/by-id` contains visible sessions only. `:session/active-id` is
  validated against that set; a deleted active session falls back to the
  first visible session and is never re-added from its Owner state.
- The application `baseline` validates the router active id against the same
  visible session list before calling `component-snapshot`, so materials and
  artifact views cannot be built for a hidden active session.
- Owner snapshots are filtered to visible session ids before conversations,
  runs, command epochs, or message orders are read. A soft-deleted session's
  Owner handle can remain in `@owners`, so iterating `@owners` directly is
  forbidden.
- Messages marked `:deleted?`, `:folded?`, `:nudge?`, or `:nudge-id` in the
  session record are removed from the merged message table even when the
  Owner conversation still carries the assistant record.
- Owner-only conversation extras that are not tombstoned (for example a live
  report message) are preserved. Do not use "absent from history" alone as a
  deletion signal; it would drop legitimate live extras.
- `:message/order` is filtered through the final `:message/by-id` set, so a
  removed entity cannot survive as an orphan order entry.

The client additionally prunes messages whose `:session-id` is absent from
`:session/by-id` in `hc.client.sync.normalize/to-db`; the server projection
remains the primary authority.


## 3 Supervised maintenance

`restore-supervisor/prepare!` installs the process config and the maintenance
handler. `install!` publishes each live system. `startup!` reconciles the
durable journal after a cold process start. `stop-current!` is the shutdown
hook and stops whichever system is live after a restart.

### 3.1 Request

`:backup/maintenance-restore` and
`:backup/maintenance-restore-recovery-point` require:

- a safe `:maintenance-id` (`[A-Za-z0-9][A-Za-z0-9._-]{0,127}`);
- explicit `:confirm? true`;
- optionally the `:preview-fingerprint` the client showed; when present it
  must equal the fresh preview or the request is refused
  `:BACKUP_PREVIEW_STALE` before any stop;
- for a backup: `:backup-id`, `:mode :merge|:replace`, at least one category,
  and the password in memory only;
- for a recovery point: `:recovery-point-id` with `:integrity :verified`.

The supervisor previews through the live backup service. A backup preview
returns a `:fingerprint` of the confirmed plan. The selected backup object is
downloaded from its real target and written to
`<data-dir>/maintenance/staging/<maintenance-id>/` as a verified local target,
so the offline restore does not need a live network target. The staged copy is
owner-only and remains for operator inspection; the original target remains
authoritative. The password and the recovery-point `:state` never enter the
journal.

The accepted receipt is returned before the potentially slow preview and
staging. The worker performs them under the closed gate; a precheck failure
is a terminal `:failed` journal entry with `:retryable? true`, and the gate is
reopened without stopping the application. This keeps the maintenance ID
visible to the client even when a remote target or a large backup would
otherwise exceed the command reply window.

The same maintenance ID with the same non-secret request is idempotent and
returns the existing status without a second preview or restore. The same ID
with a different request is `:BACKUP_MAINTENANCE_ID_CONFLICT`. A different ID
while a request is active is `:BACKUP_MAINTENANCE_ACTIVE`.  The same-ID
decision is re-run inside the journal lock: a record that arrived after the
unlocked pre-check is either an idempotent receipt or a conflict, never a
silently overwritten request.  The worker starts from the state actually
written, so a replaced terminal record stays under `:previous`.

A preview with `:no-changes?` or `:already-imported?` records a terminal
`:complete` no-op and does not stop the application.

### 3.2 Quiesce and restore

After the journal is written, the supervisor closes the command gate. Only
`:backup/status`, `:backup/maintenance-status` and the maintenance control
commands remain allowed; ordinary work is rejected
`:MAINTENANCE_IN_PROGRESS`. It drains in-flight command handlers up to
`:maintenance :drain-timeout-ms` (default 5000). A drain timeout returns an
unconfirmed stop, so restore never runs.

The stop thunk calls `system/stop!`. Only `:stopped? true` reaches the restore
thunk, and the supervisor also requires `:store-closed? true`; a positive
`:stopped?` without a closed store becomes `:STORE_NOT_CLOSED` and restore
does not run. The restore thunk runs `hc.server.backup.offline/restore!` or
`restore-recovery-point!` with `:quiescent? true`, the staged local target and
the preview fingerprint. The core rejects a changed plan with
`:BACKUP_PREVIEW_STALE` before creating a recovery point or writing. The
offline restore always creates a recovery point before applying; a complete
result without a recovery-point ID is downgraded to `:failed`
(`:BACKUP_RECOVERY_POINT_MISSING`).

The start thunk starts a fresh system with the same maintenance handler,
installs it, and checks `system/health`. A start result that is blocked, has an
error, or fails health is a restart failure, never `:complete`.

### 3.3 Journal and status

The journal is `<data-dir>/maintenance/state.edn`. It is canonical EDN with a
SHA-256 digest, written through a temporary file, fsync and one atomic rename,
with owner-only permissions when supported. A corrupt or newer journal is
reported `:BACKUP_MAINTENANCE_JOURNAL_INVALID` /
`:BACKUP_VERSION_UNSUPPORTED` and is never overwritten.

Statuses are `:accepted`, `:validating`, `:stopping`, `:restoring`,
`:restarting`, `:complete`, `:failed`, `:stop-failed`, `:restart-failed` and
`:failed-needs-attention`, plus `:failed-needs-recovery` for an offline or
supervised record whose failure proved a commit, and
`:failed-commit-unproven` when the commit can be neither proven nor refuted.
`:failed-before-commit` is the terminal history state for a store that was
observed before and after with the same version; it does not block the next
start. `:failed` remains blocking because it also covers records written before
this evidence existed, but a `:failed` record that explicitly says
`:restore-attempted? false` was written before the restore thunk ran and is
history too.  Preview, staging, worker-start and interrupted
`:accepted`/`:validating`/`:stopping` failures write that flag.
Only `:complete` means the restore and restart were both confirmed. A failed
restore still attempts restart. The storage evidence decides the status even
when that restart also failed: a proven commit becomes
`:failed-needs-recovery`, an unprovable one `:failed-commit-unproven`, and an
observed unchanged store `:failed-before-commit`. `:failed-needs-attention`
remains only when no storage evidence exists (or the restore outcome is
unknown); it blocks. The `:restart` field keeps the restart failure in every
case. Supervised terminal writes run inside `journal/locked`; a finish that
meets a record another
process acknowledged keeps the acknowledgment and records its own evidence
under `:previous`. A guarded write whose decision function returns nil leaves
the journal unchanged; it never publishes an empty record. A stop failure does not
restore or restart. `:acknowledged?` is orthogonal to the status: it records
an operator's comparison of a failed offline restore with the store and only
decides whether the start gate keeps refusing; it never turns a failure into
`:complete`.

The HTTP/Sente listener is down during the stop/restore/start window. The
client reconnects and sends `:backup/maintenance-status` with the same
`:maintenance-id`. That receipt is the only progress and terminal authority.
The first such query after a restart marks `:observed-by-client? true` and
`:observed-at`.

`restore-supervisor/status` returns the capability and the latest state:

```clojure
{:available? true
 :mode :supervised
 :active <state or nil>
 :last <state or nil>
 :journal {:path "..." :ok? true}
 :quiesce {:supported? true :drain-timeout-ms 5000}
 :restart {:supported? true}
 :reconnect {:supported? true}
 :command "pixi run backup"}
```

`:backup/status` includes this map under `:maintenance`. Maintenance command
receipts are `{:ok? true :receipt <state>}` with top-level `:status`,
`:phase`, `:needs-attention?`, `:result`, `:recovery-point`, `:preview` and
`:observed-by-client?`.

### 3.4 Crash reconciliation

`prepare!` reconciles before `system/start!` reads the start gate; `startup!`
repeats the same guarded reconciliation after a successful start and is
idempotent for a terminal record. Neither re-runs a restore.  An active record
still owned by this process's worker or by a live recorded PID is left
untouched; the start gate blocks that second start instead of reinterpreting a
live operation.  If the journal
was `:accepted`, `:validating` or `:stopping`, it records `:failed` with
`:retryable? true`, `:restore-attempted? false` and no restore attempt. If it
was `:restoring` or `:restarting` and a successful restore result was durably
recorded, it records `:complete` because the new process is running. A failed
recorded result is classified by its evidence: a proven commit is
`:failed-needs-recovery`, an unprovable commit is
`:failed-commit-unproven`, and an observed unchanged store is
`:failed-before-commit`. A missing result becomes `:failed-needs-attention`
with `:BACKUP_MAINTENANCE_OUTCOME_UNKNOWN` and blocks the gate.  A blocked
record is not rotated first; the store stays untouched until the operator
acknowledges it.  The same rule applies after a process crash or an external
supervisor restart.  An offline CLI record is never reinterpreted by the
supervisor.

## 4 Files

| File | Responsibility |
|---|---|
| `app.clj` | process entry, instance lock/control channel wiring, supervisor wiring, shutdown hook |
| `singleton.clj` | machine-wide lock, owner-only endpoint state, loopback EDN control channel |
| `system.clj` | store recovery, components, start/stop evidence |
| `prompts.clj` | role system prompts and model-visible program tool schemas |
| `fleet_gc.clj` | app-owned periodic fleet worktree retention sweep |
| `backup.clj` | live backup adapter and maintenance command delegation |
| `restore_supervisor.clj` | preview/stage/gate/drain/stop/restore/start/journal |
| `commands.clj` | router, durable command dedup, maintenance gate and drain |

## 5 Tests

```text
clojure -M:test:server -n hc.server.app.restore-supervisor-test
clojure -M:test:server -n hc.server.app.restore-supervisor-integration-test
clojure -M:test:server -n hc.server.app.system-test
clojure -M:test:server -n hc.server.app.singleton-test
clojure -M:test:server -n hc.server.app.main-test
clojure -M:test:server -n hc.server.app.extension-scope-test
clojure -M:test:server -n hc.server.app.publisher-test
clojure -M:test:server -n hc.server.app.snapshot-command-epoch-test
clojure -M:test:server -n hc.server.app.snapshot-deletion-test
clojure -M:test:server -n hc.server.history-snapshot-test
clojure -M:test:server -n hc.server.app.fleet-gc-test
clojure -M:test:server -n hc.server.app.model-gateway-test
clojure -M:test:server -n hc.server.app.prompts-test
clojure -M:test:server -n hc.server.backup.maintenance-test
clojure -M:test:server -n hc.server.backup.fingerprint-test
```

The pure tests cover idempotence, conflicts, confirmation, journal tampering,
client observation, recovery-point redaction, no-op completion and crash
reconciliation. The integration tests start the real system, create a real
encrypted backup, run the supervised stop/restore/restart cycle, verify the
restarted health and maintenance-status receipt, and prove that a wrong
password or an unconfirmed stop never reaches restore.  The cold-start gate
regressions prove that an unacknowledged supervisor
`:failed-needs-recovery` / `:failed-commit-unproven` record blocks
`system/start!`, that `:failed-before-commit`, an explicit
`:restore-attempted? false` pre-restore failure and an acknowledged record do
not, that an active record is allowed only for the supervisor's own
`:restarting` ID, and that `prepare!` reconciles an interrupted supervisor
record before the gate without rotating a blocked store.  They also cover the
same-ID request race inside the journal lock, a crash record that must not
relabel another operation, a nil guarded write that must leave the journal
unchanged, a live maintenance record that reconciliation must leave alone, and
the storage-evidence classification when the restart also failed.

## 6 Limits

- The supervisor reads the selected backup once for preview and once for
  staging. The backup module already reads a whole container into memory; a
  very large backup pays that cost twice.
- A supervised restart needs a stable configured port. With `:port 0` the
  restarted listener gets a new port and a browser using the old URL cannot
  reconnect automatically; use a fixed free port for the browser roundtrip.
- If the restarted application is blocked or unhealthy, no HTTP listener is
  available to answer `:backup/maintenance-status`; the journal under
  `<data-dir>/maintenance/state.edn` remains the operator-visible evidence.
