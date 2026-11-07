# hc.server.sessions

Package-local specification for server-side session, draft, model-binding,
and provider-connection management.

Authority: root `AGENTS.md` §5.1 (single Owner, four-layer lifecycle,
multi-client command epochs, edit-during-run) and §7.4 journeys 1–5 (session
drafts, model selection, history versions, deletion retention). Root
`AGENTS.md` wins on conflict. `src/AGENTS.md` and `src/hc/server/AGENTS.md`
apply above this file.

## 1 Boundary

This package is the server-side domain for:

1. session lifecycle, listing, pinning, archive, soft delete, restore, purge;
2. per-session, per-client drafts;
3. versioned message history and visible/deleted projections;
4. immutable model-binding and run input snapshots;
5. provider connections, credentials, model entries, capabilities, pricing,
   availability, and refresh semantics;
6. assistant defaults and main/task role bindings;
7. command-id/command-epoch deduplication through the Owner gate;
8. persistence and Owner ports for the assembly layer.

It performs no database I/O outside `SessionPersistence`, starts no thread,
opens no socket, and keeps no process-global mutable state. `registry` holds
only `{session-id -> Owner handle}` plus one lock for its own routing table.

### 1.1 One session, one Owner mailbox, one command epoch

The compose Owner (`hc.compose.owner.*`) is the single serial writer and the
single client-command epoch authority. The session record carries **no**
command gate. Both metadata and conversation commands pass through the same
Owner mailbox and the same `:commands` gate:

```text
:session/rename
:session/update-draft
:session/pin
:session/archive
:session/restore
:session/delete
:session/transfer
:session/purge   ; router-gated, then registry purge after Owner stop
:send-message
:stop-run
:answer-ask
...
```

`hc.server.sessions.registry/update-session!` accepts only the metadata
types and delegates the full envelope to `OwnerPort/submit-command!`. It does
not fill or validate an epoch and does not write session state. A missing
Owner is `:NO_OWNER`, never a bypass. `OwnerPort/enqueue!` carries internal
lifecycle messages (`:session/close`, `:session/stop`), never client
envelopes.

Callers must pass the full envelope with business fields under `:payload`.
`update-session!` reads `(:payload command)` and adds `:kind`; handing it a
flat client payload silently drops `:title`, `:pinned?`, `:confirm?` and the
other fields, so rename/pin/archive/delete would all reach the Owner as only
`{:kind ...}`.

`:session/purge` is the one metadata-shaped command that is **not** routed
through `update-session!`: the app command router's durable gate validates
the per-session epoch, then stops and forgets any live Owner, and only then
calls `registry/purge-session!`. The registry still refuses a live Owner, a
non-deleted session, but any soft-deleted session is eligible for an explicit
purge. The 30-day window governs default retention/cleanup, not the
operator's confirmed deletion. The purge removes only the session record; it
never deletes workspace files.

The purge precondition reads only the committed record.  An accepted
`:session/delete` has already written `:deleted` there before its receipt,
so the committed record is the lifecycle authority.  A live Owner that still
says `:deleted` over a committed `:active` record is not evidence of a
business delete: purge refuses it and does not persist the lagging live
value.  That refusal is deliberate; hiding a missing commit behind a purge
write would make a non-durable deletion look accepted.  The Owner is still
stopped and must explicitly report both `:stopped?` and `:drained?` before
the record is removed.  The 30-day retention window is not a purge
precondition.

After its gate accepts a metadata command, the Owner calls
`hc.server.sessions/apply-command` on the session state it owns, then stores
the business receipt in its gate.  Before that receipt leaves the Owner, the
`commit-session-change!` barrier writes the changed session to
`SessionPersistence` and only then commits the owner state.  A session
change without a `:commit-session!` port is refused with
`:SESSION_PERSISTENCE_UNAVAILABLE`; a failed or unknown session commit is
never reported as accepted.  `sessions/execute` is the standalone helper
that takes an explicit `:gate` state and returns `:gate-state`; the
assembled Owner uses the port barrier instead of calling the gate and
`apply-command` separately.

`(sessions/session-port)` returns the exact `:session-port` map for
`hc.compose.owner`:

```clojure
{:apply-command apply-command
 :confirm-draft-saved confirm-draft-saved-port
 :fail-draft-save fail-draft-save-port}
```

The draft callbacks accept `{:draft-id client-id :revision n :error ... :now t}`;
`:revision` defaults to the current draft revision. All three functions are
pure and fast and may run on the Owner thread.

With `:persistence`, the port also exposes the Owner commit barrier:

```clojure
:commit-session!    (fn [previous session now]) ; CAS commit + revision bump
:reconcile-session! (fn [session])              ; startup live/committed repair
```

`commit-session!` reads the committed record first, uses its revision as the
CAS token, and bumps the live session exactly once for the accepted change.
`reconcile-session!` runs before the Owner accepts work after a restart: a
live session that differs from the committed record is written back, and a
committed record that is ahead of the live session is adopted.  A missing
committed record fails startup; the Owner never resurrects a purged session.

`(sessions/session-port {:delegate history/apply-command
                         :outbound-context history/context/outbound-context})`
routes unknown command types to a separate pure domain and uses the history
send projection. Known session commands still use the built-in behavior, so a
history domain should use distinct types such as `:history/select-version`
rather than replacing `:session/edit-message`.

The assembled application advertises the Owner epoch as
`:sync/command-epoch`. There is no separate session-metadata epoch.

`hc.server.sessions/execute` still understands conversation command types for
standalone use and tests. In the assembled application those commands go to
the compose Owner, which may call `execute` with a `:delegate` for
compose-owned command types after the single Owner gate has accepted them.

## 2 Session state

```clojure
{:session-id "hc-session-1"       ; stable, never reused
 :owner-id "hc-owner-1"           ; stable route to the serial Owner
 :revision 4                      ; current state revision
 :persisted-revision 4            ; last acknowledged commit, nil before first
 :status :active                  ; :active | :archived | :deleted
 :title "New session"
 :title-source :auto              ; :auto | :manual
 :language :en                    ; :en | :zh-CN
 :assistant-id "asst-1"
 :origin-session-id nil           ; branch origin, if any
 :created-at 1000
 :updated-at 2000
 :activity-at 2000                ; content activity only
 :pinned? false
 :temporary? false               ; temporary sessions stay out of deleted area
 :capability-scope nil           ; explicit session capability policy, or nil
 :deleted-at nil
 :context {}                     ; history domain normalizes this
 :model-binding {...}             ; immutable snapshot, or nil
 :pending-model-binding nil       ; {:binding ... :mode ... :requested-at ...}
 :drafts {"window-1" {...}}       ; per-client
 :messages {"m-1" {...}}
 :message-order ["m-1"]
 :runs {"r-1" {...}}
 :run-order ["r-1"]
 :current-run-id nil
 :calls {"c-1" {...}}
 :attempts {"a-1" {...}}}
```

The session has no `:commands` field. The Owner's gate lives in Owner state.

Rules:

- `:session-id`, `:owner-id`, `:run-id`, `:call-id`, `:attempt-id`, and
  `:message-id` are non-blank strings and are never reused.
- A session copies its model binding at creation. Changing an assistant
  default affects only new sessions. An explicit `:session/select-model`
  changes the current session, never an existing run snapshot.
- New-session binding precedence is: explicit `:connection-id` +
  `:model-entry-id`, then `:assistant-id` default, then the configured
  `:main` role, then unbound. The app adapter must normalize the settings
  UI's `{:connection ... :model ...}` role shape to
  `{:connection-id ... :model-entry-id ...}` before this lookup.
- `app/snapshot` publishes the immutable binding as both `:model-binding`
  and `:model-snapshot` so the settings UI can show the selected model
  immediately.
- The app must pass a **fresh** session-config map to every
  `registry/create-session!` and every Owner `:apply-command` call.  Read the
  committed settings configuration per call; `hc.server.app.system` does it
  with one resolver
  (`(fn [] (session-config/session-config (settings/config settings-service)))`)
  shared by `commands/router` `:session-config` and the Owner
  `:session-port`.  A map captured once at startup goes stale after a live
  settings save and leaves new sessions unbound until restart.
- `:activity-at` changes on message append, edit, fold, delete, and send.
  Reading, scrolling, renaming, pinning, and switching message versions do
  not change it.
- A manual title (`:title-source :manual`) can never be overwritten by a late
  automatic title. `suggest-title` returns `:ignored :manual-title`.
- Soft deletion keeps the record for the default 30-day retention period
  (`deletion-retention-ms`). A `:temporary?` session is excluded from the
  deleted area. An explicit `purge` needs `:confirm? true` and is available
  immediately for any soft-deleted session; it removes only the session
  record and never deletes external workspace files.
- `find-reusable-blank-session` returns an active session with no messages,
  no non-blank draft, and no active run. `registry/create-session!` with
  `:reuse-blank? true` returns such a session instead of creating another
  empty history entry. Explicit model/assistant/language choices and
  temporary sessions disable reuse; an explicit `:capability-scope` also
  disables reuse, and a provided title is ignored on reuse.
- `branch-session` copies messages strictly before `:before-message-id` with
  fresh message and version IDs, keeps the source model-binding snapshot and
  language and `:capability-scope`, records
  `:origin-session-id`/`:origin-message-id`, and starts with `:context {}`.
  The branch has no drafts, runs, calls, or attempts; deleting the source
  later does not affect it.

### 2.1 Drafts

```clojure
{:client-id "window-1"
 :text "..."
 :attachments [{:attachment-id "a1" :size 10}]
 :citations [{:url "..."}]
 :cursor 12
 :revision 3
 :persisted-revision 2
 :save-status :pending          ; :saved | :pending | :error
 :updated-at 2000
 :error nil}
```

- Drafts are keyed by client ID. Two browser windows editing the same session
  do not overwrite each other.
- `:revision` advances on every accepted local change. `:persisted-revision`
  advances only after the enclosing session commit succeeds.
- `saved?` is derived: it is true only when the current revision equals the
  persisted revision and `:save-status` is `:saved`.
- A failed save keeps the text and sets `:save-status :error`. The UI must
  show "draft not saved" and offer copy/save-as. It must never claim saved.
- `:expected-revision` guards against an out-of-order update and returns
  `:DRAFT_REVISION_CONFLICT` without changing the draft.
- One draft supports at most 20 attachments and 100 MiB per attachment.
  Violations are refused before mutation.
- Sending clears the draft text, attachments, citations, and cursor, and sets
  the cleared draft to `:pending` until the session commit is acknowledged.
  `commit-session!` and `commit-owner-session!` mark every `:pending` draft
  saved at its current revision as part of the successful commit, so a
  cleared send draft becomes saved without a second command. A failed or
  unknown commit leaves it pending.
- Before a message with attachments is created, `:session/send-message` calls
  `hc.server.attachments.model/prepare-send` with the selected model entry
  (including connection `:attachment-limits`/`:limits`). A blocked item
  returns `:ATTACHMENT_NOT_SENDABLE` with `:issues` and `:summary`, starts no
  run, and preserves the draft. The message stores the `:sendable`
  descriptors; a `:text` route carries confirmed `:send-text`, never a
  preview. When every attachment is explicitly excluded, a blank message
  still returns `:MESSAGE_EMPTY`; non-blank text may send with zero
  attachments.

## 3 Messages and history

A message keeps every version:

```clojure
{:message-id "m-1"
 :role :user                  ; :user | :assistant | :system
 :versions [{:version-id "m-1-v1" :content "..." :edited? false}]
 :current-version 0
 :attachments []
 :citations []
 :quotes []
 :created-at 1000
 :updated-at 1000
 :deleted? false
 :folded? false
 :manual-edit? false}
```

- Editing appends a version; the original remains readable.
- `:session/send-message` accepts `:quotes` on the payload and stores them on
  the new user message and its first version.
- `:session/edit-message` accepts optional `:attachments`, `:citations`,
  `:tool-records`, and `:usage` and stores them on the new version snapshot.
  It still appends the version, preserves the original, selects the new
  version, marks an assistant edit as manual, cancels an affected run, and
  starts no new run.
- A version may carry its own `:attachments`/`:citations`/`:quotes` snapshot;
  `message-view` prefers the current version and falls back to message-level
  values for older messages.
- Regenerated versions carry `:source :regenerated`, `:parent-version-id`,
  `:edited? false`, and `:manual-edit? false`. The parent is the
  `:target-version-id` frozen in the run snapshot, not whatever version is
  selected when the terminal arrives.
- Editing an assistant reply sets `:manual-edit? true`; it never pretends to
  be original model output.
- `visible-messages` excludes deleted and folded messages.
  `deleted-messages` returns the recoverable deleted area.
- `initial-user-input` projects the first visible user message to
  `{:user-input <raw text> :user-attachments <current-version descriptors>}`.
  Attachment-only input uses the stable `[attachments]` placeholder.  The app
  passes this map into the Owner's relay seed; the same descriptors remain in
  `blob-refs/session-refs` (including soft-deleted messages), so their
  `:blob-id` references stay alive without a second reference table.
- A deleted or folded message keeps its run/call records until resource
  cleanup. Late model events never resurrect it in the visible chat.

## 4 Runs, calls, and attempts

The four lifecycle layers are separate:

```text
session  long-lived workspace
run      one cancellable execution activity
call     one business model/tool work record
attempt  one concrete try of a call
```

Call states: `:ready`, `:dispatching`, `:running`, `:retry-wait`,
`:succeeded`, `:failed`, `:cancelled`, `:outcome-unknown`.

Rules:

1. `start-run` refuses a second `:running` run. A cancelled run may be
   followed by a new run while old attempts are still closing.
2. A run snapshot contains `:message-ids`, `:model-binding`, `:policy`,
   `:language`, `:context`, optional `:target-message-id`/`:target-mode`, and
   `:frozen-at`. It is deep-frozen with
   `hc.server.model-port.contract/freeze-snapshot`. A regeneration run uses
   `:target-mode :append-version`; on a successful, non-late terminal the
   facade appends a new version to that assistant message with
   `:source :regenerated`, selects it, and does not create a second assistant
   message. A deleted/folded target or cancelled/late run appends nothing. When the caller supplies
   an `:outbound-context` function to `:session/send-message`, its
   `:message-ids` become the automatic send range and its projection is
   frozen under `:context`. Without one, all visible message IDs are sent and
   `:context` is `{}`.
3. `claim-call` moves `:ready` to `:dispatching` and creates a new attempt
   **before** the caller submits the dispatch effect (§5.2).
4. A retry keeps the call-id and creates a new attempt-id. Attempt-ids are
   never reused.
5. `accept-event` fences by the call's current attempt-id:
   - chunks and tool calls from a stale attempt are ignored;
   - the first terminal fixes the call; duplicate terminals are ignored;
   - a different terminal after the first is a diagnostic, never a reopen;
   - tool calls are deduplicated by project `:tool-call-id`; a different
     payload is `:TOOL_CALL_PAYLOAD_CONFLICT`.
6. `cancel-run` marks the run `:cancelled` immediately, keeps calls and
   attempts, and emits `:cancel-attempt` for every active attempt. A ready
   call becomes `:cancelled` without an external effect.
7. A terminal result that arrives after cancellation is stored with
   `:late? true`; the session facade does not append it to the visible chat.
8. Editing, folding, or deleting a message included in the active run's
  snapshot cancels that run with reason `:input-changed`. A regeneration
  snapshot includes its `:target-message-id` even when that id is not in
  `:message-ids`; editing, folding, or deleting the target therefore cancels
  the run as well. The snapshot itself remains unchanged.

## 5 Configuration and connections

### 5.1 Connections

A connection never stores a raw secret. It stores an opaque `:secret-ref` and
a display-safe `:masked-key`. `:official?` is true only for a preset
connection whose host was not replaced. A custom host is shown truthfully.

Each connection supports multiple credentials with:

- alias, enabled flag, priority, use count, last-used time;
- `:rate-limited-until` (temporary) and `:auth-failed?` (until a new secret
  or an explicit success) as separate exclusions;
- rotation policy `:priority`, `:round-robin`, `:least-used`, or `:random`.

`choose-credential` returns the chosen credential plus the updated connection.
It never writes a secret to state or logs.

Model entries are keyed by stable project ID, never by display name. They
keep `:provider-model-id`, display name, local alias, enabled flag, local
notes, capabilities, pricing, source (`:listed`/`:manual`), availability, and
last-seen time.

`refresh-models`:

- preserves enabled flag, alias, and notes for an existing provider model;
- updates provider display name and reported capabilities/pricing;
- adds newly observed models;
- marks a listed model that was not returned as `:not-returned`, never
  deletes it;
- leaves manual entries present;
- records `:list-status`.

`mark-model-list-unavailable` keeps all entries and allows a manual model ID.
Capability and availability are separate: `:unknown` capability is not
`false`, and `:unknown` pricing is not zero.

### 5.2 Assistants and roles

Assistants store a default-model **reference** (`:connection-id`,
`:model-entry-id`). A new session resolves it into a binding snapshot. Main
and task roles are separate bindings:

- `:main` — Steward, Plan, Reviewer;
- `:task` — Plan dedicated worker, Swarm workers and holders.

`models-by-display-name` groups without merging; two connections offering the
same name stay distinct.

`binding-usable?` is true for `:available`, `:not-returned`, and
`:unconfirmed`. It is false for `:connection-disabled`, `:connection-removed`,
`:auth-expired`, `:disabled`, and `:model-removed`.

## 6 Commands

`execute` accepts a command envelope plus an explicit Owner gate:

```clojure
(sessions/execute session
                  {:command-id "cmd-1"
                   :command-epoch 1
                   :client-id "window-1"
                   :type :session/update-draft
                   :payload {...}}
                  {:gate owner-gate-state
                   :now 1700000000000
                   :id-source source})
```

It returns `:gate-state` and never reads or writes a gate in the session.
The assembled Owner keeps that gate in its own state. `apply-command` applies
the business transition without a gate; the Owner calls it only after its
gate accepted the command.

Deduplication happens before the business transition:

| Situation | Result |
|---|---|
| New command-id, active epoch | apply once, store receipt |
| Same command-id, same payload | return stored receipt, `:replayed? true`, no effects |
| Same command-id, different payload | `:COMMAND_ID_PAYLOAD_CONFLICT` |
| Retired epoch | `:COMMAND_EPOCH_RETIRED`, `:action :resync-state` |
| Epoch never issued | `:COMMAND_EPOCH_UNKNOWN` |
| Invalid envelope | `:COMMAND_REQUEST_INVALID`, `:COMMAND_ID_REQUIRED`, `:COMMAND_EPOCH_INVALID` |

`execute` accepts a `:dispatching` placeholder before it calls
`apply-command`, then replaces it with the final receipt. A retired or
unknown epoch is therefore refused before any business transition runs.
`:scope` in the options, or `:command-key` in the request, selects the
`[scope command-id]` key shape used by the durable gate; without either the
key is the bare command-id.

A deleted session accepts only `:session/restore` and `:session/delete`.
Every other type is refused with `:SESSION_DELETED` inside `apply-command`,
so the assembled Owner path and the standalone `execute` path share the
guard.

A refused business command still consumes its command-id and stores a
`:refused` receipt; a replay returns the same refusal. This prevents a
different payload from reusing an accepted ID.

Unknown command types go to the optional `:delegate`
`(fn [session request opts] -> business-result)`. The delegate is called only
for a new command, never for a replay.

## 7 Persistence and Owner ports

`hc.server.sessions.ports/SessionPersistence`:

```clojure
(read-session persistence session-id)
(commit-session! persistence expected-revision session)
(list-session-ids persistence)
(delete-session! persistence expected-revision session-id)
(read-config persistence)
(commit-config! persistence expected-revision config)
```

`hc.server.sessions.ports/SessionCommandLookup` is the optional indexed
capability used for crash recovery:

```clojure
(find-session-by-command persistence identity)
```

It returns `{:status :found :session s}`, `{:status :missing}`, or
`{:status :error :error e}`.  `identity` is
`{:scope s :command-epoch n :command-id id}`: the epoch is part of the
identity, so the same command-id in a new epoch is a different command.
`ports/lookup-session-by-command` uses the index when the adapter provides it
and otherwise scans the session ids, so custom test adapters remain
compatible.  A scan that cannot read a session returns that error; an
unreadable session is never treated as missing.  The storage adapter commits
the command index in the same partition and the same transaction as the
session record, and checks the identity's uniqueness in that transaction, so
a committed session is always findable and a concurrent second session for
the same identity is refused.

`hc.server.sessions.ports/SessionCommandClaim` is the optional durable claim
used by blank-session reuse:

```clojure
(claim-session-command! persistence identity session-id)
```

It returns `:claimed`, `:unchanged`, `:duplicate` (with the existing
session), `:missing`, `:conflict`, or `:unknown`.  The storage adapter writes
the same `:session-command` document as a creation index, with
`:source :reuse`, in the sessions partition.  The claim is written before the
Owner starts; it is the durable business point for a reuse command.  A
second identity owner is refused, and deleting the session removes every
claim that points to it.

Revision contract:

- a new value has `:revision 0` and is not stored yet;
- `expected-revision` is the revision the caller observed, 0 for a new key;
- success returns `{:status :committed :revision n}`;
- a stale commit returns `{:status :conflict :revision stored}`;
- a commit whose `:creation-command` identity is already owned by another
  session returns `{:status :duplicate :session existing}` and stores
  nothing; the identity includes scope, epoch, and command-id;
- an unknown outcome returns `{:status :unknown :error ...}` and is never
  reported as committed;
- a committed session value has `:persisted-revision = :revision`;
- a conflict, duplicate, or unknown outcome leaves the caller's session
  dirty.

`hc.server.sessions/commit-session!` implements this boundary. A caller must
read the stored value or retry after conflict/unknown; it must not claim the
draft or session was saved.

The Owner is the live writer after creation, so its in-memory `:session` can
be newer than `SessionPersistence`. `registry/get-session` and
`registry/list-sessions` merge the live Owner session when its revision is at
least the committed revision.  The Owner commit barrier
(`commit-session-change!` in `hc.compose.owner.runtime`) writes every changed
session back through `commit-owner-session!` before it commits owner state
and before it returns an accepted receipt.  That is why a purge can read the
committed record immediately after a delete receipt.  On startup,
`reconcile-owner-session!` repairs a lag left by a previous process: a live
session that differs from the committed record is written back, and a
committed record that is ahead is adopted.  Seeding only from the registry
store after a restart can lose the last Owner-side metadata/draft updates;
seeding only from owner state can hide an accepted deletion, so the startup
reconciliation is required.

The app router starts resumed Owners directly from its factory at process
start and then registers each handle through `registry/register-owner!`.
That registration is required, not cosmetic: without it `owner-for` is nil
after a restart, so `get-session`/`list-sessions` fall back to the stale
creation-time record and a committed `:model-binding` or
`:pending-model-binding` appears nil even though the Owner holds it.  The
router keeps its own handle map for dispatch and must update both maps in
`commands/register-owner!`.

`hc.server.sessions.ports/OwnerPort`:

```clojure
(enqueue! owner message)          ; internal :session/close / :session/stop
(submit-command! owner request)   ; synchronous client command receipt
```

Messages are plain EDN. The Owner is the only writer of live session state.
`submit-command!` may block the caller while the Owner mailbox processes the
command; it must never block the Owner thread itself.
`memory-persistence` and `recording-owner` are reference adapters for tests
and the first assembly step. `owner-port` adapts a compose Owner handle map
(`:submit-command!`, `:submit!`) to `OwnerPort` without adding another
serialization layer.

`hc.server.sessions.secrets` stores raw provider credentials behind
`:secret-ref`; the file adapter uses AES-GCM, a 0600 master key, and a 0600
data file. `credentials/apply-credentials!` applies raw additions/removals
to one connection without putting a secret in connection state.
`apply-settings-credentials!` is the app-settings adapter: it merges `:keys`
metadata with one-time `:credentials` values, updates alias/enablement/priority
for existing keys without requiring the secret again, honors
`:clear-credentials`, and strips registry-only display fields from the
returned connection. App settings merges the stored connection before this
adapter runs, so saving a rename or model edit preserves the credential table.
`delete-connection-credentials!` removes every credential and its stored
secret when the connection itself is deleted; app settings refuses the delete
if that cleanup fails.
App settings also strips nested `:auth`, `:headers`, and `:options` secrets
before persistence, and the settings snapshot applies the same redaction
again. A client cannot persist an access token or Authorization header through
the connection editor, even if it sends unknown fields.
`resolve-credential` accepts either the sessions connection shape or an
app-shaped connection (`:id`, `:key-strategy`), derives missing
`:credential-order`, and returns one raw secret for one request. None of
these functions logs or returns raw secrets in listings.

## 8 Registry

`hc.server.sessions.registry` is the assembly adapter:

- `registry` creates a routing table;
- `create-session!` accepts `:creation-command` identity; it returns an
  already committed session for the same identity instead of creating a
  second one, reuses a blank session when asked, otherwise commits first,
  then creates and registers the Owner; a commit-time duplicate returns the
  existing session; a reuse claim is written before the Owner starts, and a
  claim failure is an unknown outcome rather than a refusal; a reuse-candidate
  read failure (`:error` or `:unknown` from the persistence adapter) is also an
  unknown outcome because it happens before any business effect; a scan never
  silently drops an unreadable session and then reports a shorter list;
- `session-by-command` is the read-only recovery lookup; it never starts an
  Owner;
- `ensure-owner-for-session!` is the crash-recovery Owner seam: it ensures a
  committed `:active` session has a live Owner, never creates or mutates a
  session record, and refuses missing, archived, or deleted sessions with
  `:SESSION_INVALID` / `:SESSION_NOT_ACTIVE`;
- `ensure-owner-for!` is the retry-recovery lookup: it reuses a registered
  Owner, otherwise starts one only from a committed `:active` record and
  refuses missing, archived, or deleted sessions with `:SESSION_NOT_FOUND` /
  `:SESSION_NOT_ACTIVE`; a refused start leaves the retry obligation open;
- `branch-session!` loads the live source, commits the branch, then registers
  its Owner with the same order;
- `purge-session!` permanently removes any committed deleted record; a live
  Owner blocks it.  The app `:session/purge` handler stops and forgets the
  Owner first, then calls this primitive; the registry's own live-Owner check
  remains the final guard.  The committed record is the only deletion
  authority; a live-Owner view is never merged in or persisted here;
- `discard-session!` stops a live Owner, requires explicit `:stopped?` and
  `:drained?` evidence, forgets it, then hard-removes a temporary session; a
  bare `{:ok? true}` or a nil stop result from an existing Owner is refused;
- `get-session` merges a live Owner `:session` when its revision is at least
  the committed revision, so a non-active session does not lag title,
  binding, drafts, or history; `get-committed-session` reads the store only;
- `list-sessions` reads and orders committed summaries; a `:status :error`
  is a read failure, never an empty session set.  `system/start!` blocks
  startup rather than creating a default session over unreadable state;
- the app `:session/list` projection adds the Owner-issued `:command-epoch`
  to deleted rows so restore and permanent purge can address the same
  per-session epoch after the session left the visible baseline;
- `update-session!` submits one **metadata** command through
  `OwnerPort/submit-command!`; it never executes or commits session state
  itself.  The Owner's commit barrier commits the accepted session change
  before the receipt;
- `close-session!` enqueues internal `:session/close` when an Owner exists,
  otherwise closes and commits synchronously;
- `owner-for` returns the handle;
- `stop-all!` enqueues internal `:session/stop` and clears routes.

`update-session!` fills only a missing `:command-id`; the client must send the
Owner-issued `:command-epoch`. The `:session/update-draft` business result
includes `:draft-id` (the client ID) so the Owner can call
`confirm-draft-saved` in the same business transition. It refuses
conversation commands before any Owner call. Draft acknowledgement and the
session change commit together through the Owner barrier; a failed or
unknown commit returns a refused receipt, never `:draft-confirmed? true`.

## 9 Public API

```clojure
;; session lifecycle and state
(sessions/new-session opts)
(sessions/open! persistence opts)
(sessions/commit-session! persistence session)
(sessions/commit-owner-session! persistence previous session now)
(sessions/reconcile-owner-session! persistence session now)
(sessions/close session opts)
(sessions/close! persistence session opts)
(sessions/branch-session source opts)
(sessions/apply-command session request opts)
(sessions/apply-command session payload)  ; Owner payload with :kind
(sessions/session-port)                   ; :apply-command + draft callbacks
(sessions/execute session request opts)   ; opts must contain :gate
(sessions/accept-model-event session event opts)
(sessions/confirm-draft-saved session client-id revision now)
(sessions/fail-draft-save session client-id revision failure now)
(sessions/draft-view session client-id now)
(sessions/suggest-title session title now)
(sessions/issue-command-epoch gate-state now)
(sessions/command-epoch gate-state)

;; projections
(sessions/visible-messages session)
(sessions/deleted-messages session)
(sessions/initial-user-input session)
(sessions/message-history session message-id)
(sessions/session-summary session)
(sessions/list-sessions sessions opts)
(sessions/blank-session? session)
(sessions/temporary? session)
(sessions/find-reusable-blank-session sessions)
(sessions/restore session now)
(sessions/purge session opts)
(sessions/expired-deletion? session now)
(sessions/check-invariants session)

;; configuration and connections
(config/new-config)
(config/add-connection config connection now)
(config/add-credential config connection-id credential now)
(config/refresh-models config connection-id observed id-source now)
(config/add-manual-model config connection-id model id-source now)
(config/add-assistant config assistant now)
(config/set-assistant-default-model config assistant-id connection-id model-entry-id now)
(config/set-role-binding config role connection-id model-entry-id now)
(config/resolve-binding config connection-id model-entry-id)
(config/binding-status config binding)
(config/binding-usable? config binding)
(config/check-invariants config)

;; registry and ports
(registry/registry opts)
(registry/create-session! registry opts)
(registry/ensure-owner-for-session! registry session)
(registry/ensure-owner-for! registry session-id)
(registry/branch-session! registry source-session-id opts)
(registry/get-session registry session-id)
(registry/list-sessions registry opts)
(registry/update-session! registry session-id command)
(registry/close-session! registry session-id opts)
(registry/purge-session! registry session-id)
(registry/discard-session! registry session-id)
(registry/owner-for registry session-id)
(registry/stop-all! registry)
(ports/memory-persistence)
(ports/recording-owner)
(ports/owner-port compose-owner-handle)

;; secure credential lifecycle
(secrets/memory-secret-store)
(secrets/file-secret-store {:directory "..."})
(credentials/apply-credentials! connection {:store store
                                             :credentials [...]
                                             :clear [...]
                                             :now t})
(credentials/apply-settings-credentials! store connection payload now)
(credentials/resolve-credential store connection now)
```

## 10 Invariants

`hc.server.sessions/check-invariants`, `runs/check-invariants`,
`drafts/check-invariants`, `config/check-invariants`, and
`connections/check-connection-invariants` return vectors; empty is valid.

They check, among other things:

- stable IDs are non-blank strings;
- revisions are non-negative and persisted never exceeds current;
- message order matches message keys, versions are non-empty, current version
  is in range;
- draft saved state has a matching persisted revision;
- at most one running run;
- call snapshots equal their run snapshot;
- dispatching/running calls have an attempt;
- terminal attempts have a terminal event;
- model bindings and pending bindings contain no secret fields;
- connection order matches keys, credential/model order matches keys, no raw
  secret enters credential state, model entry IDs are globally unique;
- assistant defaults and role bindings resolve.

## 11 Tests

Focused smoke:

```bash
clojure -X:test :nses '[hc.server.sessions.ids-test
                        hc.server.sessions.drafts-test
                        hc.server.sessions.connections-test
                        hc.server.sessions.config-test
                        hc.server.sessions.runs-test
                        hc.server.sessions.sessions-test
                        hc.server.sessions.ports-test
                        hc.server.sessions.registry-test]'
```

The suite covers deterministic IDs, draft save safety, credential rotation
and refresh preservation, binding resolution, run/call/attempt fencing,
edit-during-run, deleted-message non-resurrection, model switching,
soft-delete/restore/purge, metadata command deduplication, registry routing,
and persistence CAS semantics. `test.check` properties cover random draft
sequences, command replay, and terminal-event idempotency. Secret-store tests
assert encrypted-at-rest bytes and 0600 permissions; credential tests assert
raw values never enter connection state. A multi-client
race test submits metadata commands from concurrent futures through one
serialized test Owner and checks both drafts survive and a shared
command-id produces exactly one acceptance and one replay.
