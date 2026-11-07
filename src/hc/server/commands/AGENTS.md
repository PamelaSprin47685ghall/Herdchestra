# hc.server.commands

Package-local specification for command-id and command-epoch deduplication.

Authority: root `AGENTS.md` §5.1 (single Owner, multi-client `tmux`
semantics), §2.5 (unattended operation), and the source manual §5.2
(command deduplication). Root `AGENTS.md` wins on conflict.

## Boundary

The core namespaces (`hc.server.commands`, `.epoch`, and `.receipts`) are a
pure state machine. They require only Clojure and their own namespaces. The
optional `hc.server.commands.storage` adapter requires `hc.server.storage`.
The `hc.server.commands.dispatch` wrapper requires only the durable gate.
No namespace starts threads or keeps process-local mutable state.

The session Owner or the durable gate is the only writer. It calls this
package inside one serial step and persists the returned state. The package
does not provide business locking. The Owner mailbox or the gate monitor
provides serialization.

The package does not execute business commands. It records that a command
was accepted and returns the receipt. The Owner composes the business
transition with the returned state.

The state is plain EDN and can be committed as one storage transaction.

## State

```clojure
{:command-epoch 3
 :retired-through 2
 :receipts {["s-1" "cmd-1"] {:command-epoch 3
                             :payload-digest "9f..."
                             :payload-redacted? true
                             :receipt {:status :accepted
                                       :command-id "cmd-1"
                                       :command-epoch 3}}}
 :receipt-index {"cmd-1" {:command-epoch 3
                          :payload-digest "9f..."
                          :payload-redacted? true
                          :receipt {:status :accepted
                                    :command-id "cmd-1"
                                    :command-epoch 3}
                          :command-id "cmd-1"
                          :command-key ["s-1" "cmd-1"]}}}
```

- `:command-epoch` is the current server-issued epoch, or `nil`.
- `:retired-through` is the highest retired epoch. Every epoch at or below
  it is retired.
- `:receipts` maps a command-key to its accepted payload and receipt. A key
  is a command-id string or a `[scope command-id]` vector. It is scoped to
  the current epoch.
- `:receipt-index` is optional. The durable gate keeps it so a reconnect
  query by command-id alone can find a scoped receipt. It is a derived
  projection: every epoch transition clears it together with `:receipts`,
  `accept` and `complete` keep an existing index aligned, and the durable
  gate rebuilds it from the authoritative registry when it opens.

`new-state` returns an empty domain. `issue-epoch` and `retire-epoch` return
the complete next state.

## Public API

```clojure
(require '[hc.server.commands :as commands])

(commands/new-state)
(commands/issue-epoch state)
(commands/retire-epoch state)
(commands/active-epoch state)
(commands/retired-through state)
(commands/receipt-for state command-key)
(commands/accept state request)
(commands/accept state request receipt)
(commands/complete state command-key receipt)
(commands/check-invariants state)
```

`receipt-for` accepts a command-id string, a `[scope command-id]` vector, or
the OwnerPort query map `{:command-id .. :scope .. :session-id ..}`.  A query
map tries `:command-key`, then the scoped key, then the bare command-id.  This
lets the gateway pass its receipt query through unchanged while legacy
unscoped receipts still resolve.

`issue-epoch` returns
`{:ok? true :state state' :epoch n :retired-epoch old :effects []}`.
`retire-epoch` returns
`{:ok? true :state state' :retired-epoch old :effects []}`.
`old` is nil when no epoch was active.
Both transitions clear `:receipts` and, when present, `:receipt-index`.

### `accept`

```clojure
{:command-id "cmd-1"
 :command-epoch 3
 :payload {:text "hello"}}
```

The payload is any EDN value. The package compares payloads with `=`.
The optional `:command-key` defaults to `:command-id`. A host that shares one
gate across sessions SHOULD pass `[session-id command-id]`. Envelope fields
other than `:command-id`, `:command-epoch`, `:payload`, and `:command-key`
are not part of the idempotency key.

When the request sets `:redact-payload? true`, the registry stores only a
canonical SHA-256 `:payload-digest` plus `:payload-redacted? true`.  Replay
compares digests; mismatch details contain `:accepted-payload-digest` and
`:received-payload-digest`, never the payload.

Success:

```clojure
{:ok? true
 :state state'
 :receipt receipt
 :replayed? false
 :effects []}
```

Refusal:

```clojure
{:ok? false
 :state state
 :error {:code :COMMAND_EPOCH_RETIRED
         :message "..."
         :details {...}}
 :effects []}
```

A refusal never changes the state. The Owner MUST persist the returned state
on success. The Owner MUST NOT run the business transition on refusal.

The optional `receipt` argument is stored verbatim. Without it, the receipt
is `{:status :accepted :command-id ... :command-epoch ...}`. A supplied
receipt must be EDN because the Owner persists it.

### Deduplication rules

| Situation | Result |
|---|---|
| New command-key in the current epoch | Accept. Store the payload and receipt. |
| Same command-key and same payload | Return the original receipt. Set `:replayed? true`. Do not change the state. |
| Same command-key and different payload | Refuse `:COMMAND_ID_PAYLOAD_CONFLICT`. Do not change the state. |
| Command epoch is retired | Refuse `:COMMAND_EPOCH_RETIRED`. Do not look up the command-key. Do not execute. |
| Command epoch was never issued | Refuse `:COMMAND_EPOCH_UNKNOWN`. Do not execute. |
| Invalid request | Refuse `:COMMAND_REQUEST_INVALID`, `:COMMAND_ID_REQUIRED`, or `:COMMAND_EPOCH_INVALID`. Do not change the state. |
| Invalid internal state | Refuse `:COMMAND_STATE_INVALID`. Do not change the state. |

Epoch refusals include `:action :resync-state` in `:details`. The client MUST
read the current state before it sends another command.

A plaintext payload conflict includes `:accepted-payload` and
`:received-payload` in `:details`.  When the accepted payload was redacted,
the conflict includes only `:accepted-payload-digest` and
`:received-payload-digest`, never either payload value.

### `complete`

`complete` replaces the receipt of an already accepted command.  It keeps
the payload and epoch unchanged.  Use it after a durable gate stores a
placeholder receipt and the business transition produces the final receipt.
A replay then returns the final receipt.

An unknown command-key, an invalid command-key, or a receipt from a
non-current epoch is refused and leaves the state unchanged.

### Epoch windows

Only the current epoch accepts commands. `issue-epoch` retires the current
epoch and opens the next one. `retire-epoch` closes the current epoch without
opening a new one. Both transitions clear `:receipts` and the command-id
index.

A new epoch is a fresh receipt window. A command-id accepted in an old epoch
is not replayed from that epoch. The old command is refused by its epoch.
The client MUST NOT reuse a command-id across epochs.

The high-water mark gives O(1) classification. The package keeps no retired
receipt archive, so state does not grow with retired epochs.

## Durable gate

`hc.server.commands.durable/gate` wraps the pure state machine with an
injected compare-and-set store.  The store contract is:

```clojure
(load-state store)            ;=> {:version n :state map|nil}
(commit! store expected new)  ;=> {:ok? true :version n}
                              ;   | {:ok? false :error {:code ...}}
(close-store! store)
```

The gate serializes calls with a monitor.  The caller supplies the
authoritative epoch window on every accept:

```clojure
{:command-epoch 3 :retired-through 2}
```

The window normally comes from the active session Owner's committed
`:commands` state.  A newer window clears receipts and the command-id index.
An older window never rolls the gate back.

```clojure
(require '[hc.server.commands.durable :as durable])

(def store (durable/memory-store))
(def gate (durable/gate store))
(def window {:command-epoch 1 :retired-through 0})

(durable/accept! gate window "s-1" request {:status :dispatching})
(durable/complete! gate "s-1" "cmd-1" {:status :accepted :session-id "s-1"})
(durable/receipt-for gate "s-1" "cmd-1") ; scoped lookup
(durable/receipt-for gate "cmd-1")       ; command-id index
```

`accept!` returns the same result shape as `hc.server.commands/accept`.
`sync-window!` adopts a new window without a command.  `issue-epoch!` retires
the current epoch and opens the next one in one committed transition; with an
expected epoch it refuses unless that epoch is current.  `complete!` updates a
placeholder.  `receipt-for` survives a restart because the window and
receipts are one stored document.

`sync-window!` never adopts an epoch at or below `:retired-through`, even
when a stale snapshot still names it active.  The merged state therefore
always satisfies the epoch invariants.

On open, the gate migrates its document before it accepts calls:

1. legacy plaintext secret payloads in `:receipts` and `:receipt-index`
   become canonical digests;
2. every `:dispatching` placeholder becomes an `:outcome-unknown` receipt
   with `:action :resync-state`, because no process is dispatching it after
   the gate is reopened;
3. the command-id index is rebuilt from the authoritative registry.

The migration commits before the gate is usable; a failed migration refuses
to open rather than serve a password or claim that a dead process is still
dispatching.  Opening a gate therefore claims exclusive ownership of its
store partition.  A store exception during a later commit becomes
`{:ok? false :error {:code :COMMAND_STORE_FAILED ...}}`; the gate does not
throw past its caller.

`hc.server.commands.storage/state-from-view` treats a partition with a
positive version but no state document as `:COMMAND_STORE_CORRUPT`.  An empty
gate would silently forget accepted command-ids and allow a repeated side
effect; the adapter refuses instead.

Gate open also runs `commands/check-invariants` on the migrated document.
An invalid receipt/epoch graph is refused as `:COMMAND_STORE_CORRUPT`; it is
never served and then silently repaired by the first accept.

`hc.server.commands.storage/store` adapts `hc.server.storage` to this store
contract.  It uses one partition and one document, so the window and
receipts commit atomically.

## Dispatch wrapper

`hc.server.commands.dispatch` wraps a multi-destination router:

```clojure
(require '[hc.server.commands.dispatch :as dispatch])

(def wrapper
  (dispatch/wrap
   {:gate-for (fn [scope] (gate-for-scope scope))
    :window-fn (fn [scope] (window-for-scope scope))
    :scope-fn (fn [request]
                (if (app-command? request)
                  "app"
                  {:ok? true
                   :scope (get-in request [:payload :session-id])}))
    :dispatch! (fn [request reply-fn] (router-dispatch request reply-fn))
    :fallback-receipt (fn [scope command-id]
                        (owner-receipt owner command-id))}))
```

Options:

- `:gate` is one gate for every command.  `:gate-for` selects a gate by
  scope.  A host that mixes application and per-session commands SHOULD use
  `:gate-for`.
- `:window-fn` may be zero-arity or one-arity.  The one-arity form receives
  the scope.
- `:scope-fn` may return a scope string or
  `{:ok? true :scope scope}` / `{:ok? false :error error}`.  The error form
  refuses before any gate or dispatch.
- `:fallback-receipt` may be one-arity or two-arity.  The two-arity form
  receives the scope and command-id.
- `:sensitive-request?` is `(fn [request] bool)`.  A true result marks the
  gate request with `:redact-payload? true` and redacts secret-keyed values
  from the stored receipt.  The immediate reply is unchanged.
- `:resolve-replay` is `(fn [request stored-result] result-or-nil)`.  On a
  `:dispatching` or `:outcome-unknown` replay it may return a final result;
  the wrapper completes the placeholder and replies once.  A nil result
  keeps the stored placeholder.
- `wrap` keeps the options and adds `:submit!` and `:receipt` closures.

For every non-owner, non-read-only kind the wrapper performs:

1. `accept!` with a `{:status :dispatching}` placeholder;
2. `dispatch!`;
3. `complete!` with the final receipt and the host's current window.

A replay returns the stored receipt without dispatching.  A mismatch or a
retired or unknown epoch is refused before dispatch.  If the router returns
`:outcome :unknown`, the stored receipt is `:status :outcome-unknown`; the
client must re-read state.  If the final receipt cannot be committed after a
side effect, the result is `:outcome :unknown`, never a false failure.
The reply is still delivered once when the gate's completion throws; the
stored placeholder remains available for a later reconciliation.

A replay whose stored receipt is still `:dispatching` or `:outcome-unknown`
is a recovery placeholder.  `:resolve-replay` may resolve it from the
authoritative business store; the wrapper then completes the gate and
returns the resolved receipt with `:replayed? true`.  A nil resolution
returns the placeholder unchanged.

Completion adopts the current window in the same transition.  If the epoch
retired while the command was running, the old receipt is already cleared and
completion returns `:COMMAND_ID_UNKNOWN`; the result is `:outcome :unknown`,
not a final receipt under a dead epoch.

Owner-routed kinds are already gated by the session Owner.  Read-only kinds
do not change durable state.  Both are delegated unchanged.  The host may
override `:owner-kind?` and `:read-only-kind?`.  Conversation commands with
router-side side effects (`:send-message` and `:user/message` run web search)
are gated at the router first, then the Owner gate runs.

`dispatch/owner-window` and `dispatch/owner-receipt` read the active Owner's
committed `:commands` window and receipts.  A retired Owner yields
`{:command-epoch nil :retired-through n}` rather than nil, so the gate
classifies old commands as retired instead of keeping the last active window.
`dispatch/receipt` adopts the host's current window before it answers, so a
receipt query never serves an epoch the Owner already retired.

## App router integration

The actual router is `hc.server.app.commands`.  It is the single place that
translates the transport envelope into Owner, session metadata, settings,
and component destinations.

Router wiring:

1. The router uses an application gate for `"app"` scope and one durable
   gate per session scope.
2. `:session/create` and the application namespaces (`settings`,
   `model-auth`, `search`, `documents`, `speech`, `extensions`,
   `diagnostics`, `backup`, `scheduler`) use the application gate and its
   command epoch.
3. Conversation, metadata, history, browser, and materials commands use the
   session gate and the Owner's command epoch.  They require an explicit
   session-id; a live Owner supplies the epoch.  A retired Owner's durable
   gate still classifies its replays, but a new command cannot execute
   without its destination.  The router never substitutes a server-global
   active session.
4. `command-receipt` accepts a command-id or a query map with `:scope` or
   `:session-id`, defaults to `"app"`, checks the matching durable gate,
   then the Owner's `:commands` receipts, and returns the explicit
   `{:status :not-found :command-id id :scope scope}` shape on a miss.
5. The application gate is initialized to epoch 1 when it has no active
   epoch and no retired high-water mark.  When the stored gate is retired,
   initialization issues the next epoch instead of resurrecting epoch 1.
   `app-command-epoch` exposes the current epoch to the snapshot.
6. `:app/issue-epoch` rotates the application epoch through
   `hc.server.commands.durable/issue-epoch!`.  It is server-controlled: the
   browser gateway forbids it by default, and an application may allowlist it
   only behind an independent operator check.  It requires the current
   `:command-epoch`; a missing command-id is `:COMMAND_ID_REQUIRED`, a
   missing epoch is `:COMMAND_EPOCH_INVALID`, and a stale epoch is
   `:COMMAND_EPOCH_RETIRED`.  The old receipt window is cleared in the same
   commit.

Crash boundary:

- The session record, its list entry, and its creation-command index commit
  in one storage transaction.  A crash between that commit and the gate's
  final receipt leaves a `:dispatching` placeholder; the next gate open
  reconciles it to `:outcome-unknown`.
- `:session/create` supplies a `:creation-command` identity
  `{:scope s :command-epoch n :command-id id}`; the session persistence
  indexes it atomically with the session and rejects a second session for
  the same identity in the commit transaction.  The epoch is part of the
  identity, so the same command-id in a new epoch is a new command, not a
  replay.  The app router's `:resolve-replay` reads the session by identity
  and completes the gate with the reconstructed creation receipt.  A replay
  returns the same session and never creates a second one.  The
  reconstructed `:session` reflects the committed current state, so later
  session updates may appear; the session identity and owner do not change.
  If the session is absent, the stored unknown outcome is kept; the client
  must resync and use a new command-id.
- The blank-session reuse path writes a `:session-command` claim
  (identity -> reused session) before it starts the Owner.  The claim is the
  durable business point: a crash after it is resolved by the same
  receipt-query/replay path, and a second session for the same identity is
  refused.  A crash before the claim has no persisted business effect and
  stays `:outcome-unknown`.
- Infrastructure outcomes (commit unknown or failed, lookup/claim failure,
  a reuse-candidate read failure, and an Owner start failure after the
  session or claim was committed) are stored as `:outcome-unknown`, never as
  `:refused`.  Business and envelope validation refusals remain final
  `:refused` receipts.

Maintenance gate:

- `begin-maintenance!` closes the router gate; `end-maintenance!` reopens it.
- While closed, only `:backup/status`, `:backup/maintenance-status`,
  `:backup/maintenance-restore`, and
  `:backup/maintenance-restore-recovery-point` pass.  Other commands are
  refused with `:MAINTENANCE_IN_PROGRESS` before any gate accept, so they are
  not counted as in-flight.
- `drain!` waits for the in-flight handler count to reach zero before its
  deadline.  A handler counts from the maintenance check through its reply.
- `maintenance-state` returns `{:active? :since :reason :in-flight}` for the
  restore supervisor's journal and diagnostics.
- Sensitive commands (`:backup/create`, `:backup/preview`, `:backup/verify`,
  `:backup/restore`, both maintenance restore kinds, and any payload with a
  secret-named key) keep durable dedup but store only a canonical SHA-256
  `:payload-digest` with `:payload-redacted? true`.  The stored receipt is
  redacted recursively.  Replay compares digests; a mismatch returns
  `:accepted-payload-digest`/`:received-payload-digest`, never the password.

Fleet commands:

- `:fleet/*` is app-scoped.  The router calls
  `hc.compose.fleet.router/handle` and stores the result under the app gate's
  `[app command-id]` receipt.
- The gateway forbids every fleet kind.  The router accepts only
  `steward-command-envelope` requests: `:program-role :steward`,
  `:origin :steward-tool`, and no browser `:subscription-id` or `:uid`.
  Everything else is `:FLEET_FORBIDDEN`.
- `:fleet/join` runs on a worker thread.  The router returns immediately and
  completes the receipt later; a replay while the join is in flight returns
  the `:dispatching` placeholder and never starts a second join.

Epoch rotation:

- The app gate rotates only through `:app/issue-epoch` or
  `hc.server.commands.durable/issue-epoch!`; it never advances implicitly.
  Receipts from a retired window are cleared atomically with the new epoch.
- Session epoch rotation remains `:session/issue-epoch` in the Owner.  The
  browser gateway forbids it, so a client learns the new epoch from the next
  snapshot and must not reuse old command-ids across epochs.
- A receipt query adopts the current window first, so a reconnect after
  rotation gets `:not-found` and `:action :resync-state`, not a stale receipt.

Search ordering:

- `:send-message`/`:user/message` carry the `:search` intent unchanged.  The
  router never runs search.  They stay outside `default-owner-kinds` so the
  session command epoch and the durable router receipt are checked before
  the Owner business gate.
- The Owner appends the user message, persists the draft and turn, and
  commits before it dispatches the model call.  The app model gateway starts
  a worker at model start; the worker runs
  `hc.server.search.integration/run-intent!` and only then starts the model
  request.  The Owner thread never blocks on search, and a stop signal
  cancels both the search and the model attempt.  A business refusal (blank
  text, an invalid attachment or quote, or a completed mission without
  operator input) never starts the gateway, so it cannot make an external
  request.
- A retired or unknown epoch is refused before dispatch.  A command-id
  replay returns the stored receipt and does not search again.  A required
  search failure prevents the model request; an auto failure continues with
  a truthful degraded context.
- Regression: `test/hc/server/search/app_path_test.clj` uses a real Owner
  and a loopback search stub.  `business-refused-send-does-not-search`
  asserts zero external requests; the accepted and replay tests assert the
  post-commit order.

Gateway contract:

- A submission with a retired or unknown epoch returns
  `{:ok? false :error {:code :COMMAND_EPOCH_RETIRED|:COMMAND_EPOCH_UNKNOWN
  :action :resync-state}}`.
- A receipt query returns `{:ok? true :command-id id :receipt receipt}` when
  found.  A miss returns the router's
  `{:status :not-found :command-id id :scope scope}`; the gateway adds
  `:ok? true`, `:receipt nil`, and `:action :resync-state`.  Never fake a
  success receipt.
- The gateway forwards `:command-epoch` verbatim and does not compare it to
  the feed epoch.
- Scope the dedup key by `[session-id command-id]`, not command-id alone.

Session creation commits business state after the gate stores a
`:dispatching` placeholder.  A crash leaves the placeholder; the next gate
open reconciles it to `:outcome-unknown`, so a retry does not create a second
session and does not pretend the command is still in flight.

## Invariants

`check-invariants` returns a vector. An empty vector is valid.

- The state is a map.
- `:retired-through` is a non-negative integer.
- `:command-epoch` is `nil` or a positive integer.
- An active `:command-epoch` is greater than `:retired-through`.
- `:receipts` is a map.
- Every command-key is a non-blank string or a `[scope command-id]` vector.
- Every receipt entry has `:command-epoch`, `:receipt`, and either `:payload`
  or a non-blank `:payload-digest`.
- When `:receipt-index` exists, it is a map keyed by non-blank command-id.
- Every index entry is a map whose `:command-key` names an existing
  `:receipts` entry, whose `:receipt` equals that entry's receipt, and whose
  optional `:command-id` equals the index key.
- Every receipt entry belongs to the current epoch.
- No receipts exist when no epoch is active.

## Integration

1. The app router creates one application gate for `"app"` scope and one
   gate per session scope.  A fresh application gate starts at epoch 1; each
   session gate adopts the Owner's committed window.  A stored app gate with
   no active epoch is opened at the next epoch, never at a retired one.
2. Application commands (`:session/create` and the application namespaces)
   use the app gate and the app command epoch.  Session commands use the
   session gate and the session Owner's command epoch.
3. The router accepts a `{:status :dispatching}` placeholder, runs the
   business transition, then completes the final receipt.  A replay returns
   the stored receipt without a second transition.
4. On `:COMMAND_EPOCH_RETIRED` or `:COMMAND_EPOCH_UNKNOWN`, the router sends
   a state snapshot with the current app or session `command-epoch`.  The
   client re-reads the state and does not replay the old command.
5. On reconnect, `command-receipt` checks the matching durable gate, then
   the Owner's `:commands` receipts.  The gate adopts the current window
   before the lookup.  A miss returns
   `{:status :not-found :command-id id :scope scope}`; the client must
   re-read state.
6. Persist the app gate in its own partition and each session gate in a
   per-session partition.  `hc.server.commands.storage/store` supplies the
   compare-and-set adapter.
7. `:app/issue-epoch` is the only application-level rotation command.  It
   validates the request epoch, rotates the app gate atomically, clears the
   old receipt window, and publishes a state change.

## Tests

Focused smoke:

```bash
clojure -X:test:server :nses '[hc.server.commands-test]'
```

The test namespace covers deterministic mismatch and retired-epoch cases.
It also covers property tests for replay idempotency, payload mismatch,
retired and unknown epochs, epoch monotonicity, mixed command/epoch
sequences, receipt retention, scoped replay, mixed string/vector receipt
keys, OwnerPort query-map lookup, durable restart replay, placeholder
completion, storage-backed store reopen, and dispatch-wrapper replay without
a repeated side effect.

Additional regressions cover receipt-index clearing on epoch transitions,
index invariant detection, `issue-epoch!` fencing and receipt clearing,
stale-window non-resurrection, stale `:dispatching` reconciliation to
`:outcome-unknown`, receipt-commit failure delivery, completion against a
rotated window, receipt queries after rotation, and retired-Owner window
reporting.  They also cover `:resolve-replay` completing a recovery
placeholder and the session-creation crash window (session committed,
receipt missing) resolving to the original receipt without a second session.
Reuse-specific regressions cover the request identity in a reuse receipt, a
reuse claim resolving after restart, a reuse-candidate read failure, and
infrastructure failures (commit unknown, Owner start failure) stored as
`:outcome-unknown` rather than `:refused`.

`test/hc/server/commands_gateway_test.clj` drives the real gateway
`:hc/command` and `:hc/receipt` path through the durable gate and through the
actual app router.  It verifies that a fresh gate returns the stored receipt
and that a replay does not dispatch or create a second session.

`test/hc/server/app/commands_dedup_test.clj` drives the actual app router for
`:session/create`: replay returns the original session, a payload mismatch is
refused, a retired app epoch is refused before any session is created, and an
app receipt is not visible through a session scope.  It
also verifies that an app receipt is invisible through a session scope and
that a temporary-session delete replays from its durable session gate after
the Owner has retired.  It also rotates the app epoch through
`:app/issue-epoch`, checks that the old window is fenced and its receipts are
gone, checks that a retired stored gate opens at the next epoch, and proves
that a session receipt query follows the Owner epoch while a retired-epoch
replay is refused.

`test/hc/server/app/snapshot_command_epoch_test.clj` verifies that the global
`:sync :command-epoch` is the app epoch and that each session publishes its
own Owner epoch; the app epoch never substitutes for a session epoch.

`test/hc/server/app/session_command_index_test.clj` uses a real Datalevin
store to verify that the creation-command index commits with the session,
survives a reopen, is removed when the session is deleted, treats the same
command-id in another epoch as a new command, and refuses a second session
for the same identity at commit time even from a second persistence handle.

`test/hc/server/commands_sensitive_test.clj` verifies that sensitive payloads
are stored only as canonical digests, mismatch details contain digests rather
than values, and stored receipts are recursively redacted.
