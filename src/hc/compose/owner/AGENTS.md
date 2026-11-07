# hc.compose.owner — single Owner mailbox and Missionary runtime

Authority: root `AGENTS.md` §5.1–§5.3, §2.4, §2.5; then `src/AGENTS.md`;
then `src/hc/compose/AGENTS.md`. This file fixes the local contract only.

## 1 Boundary

One Owner exists per session or board coordination domain. The Owner is the
only writer of its committed state. Browser commands, model events, tool
terminals, timers, child reports, and cancellation signals may arrive from
any thread; only the mailbox thread turns them into state transitions.

The Owner owns `:mission` (relay, per-tenure boards, bindings, model ids)
and delegates every mission command to `hc.compose.mission/apply-command`.
The mission host is the authority for relay and board transitions; the Owner
is the authority for calls, conversation, reports, cancellation, and the
single commit. The Owner never blocks on a model response, file I/O, Shell,
or report sink. Those run in Missionary tasks or in the caller's thread.
Their results return as discrete messages. The one compare-and-set store
commit and the `:commit-session!` barrier run on the mailbox thread by
design; both adapters must be local and bounded. Every injected seam that
the mailbox calls directly (`:model-gateway` `:start!`/`:cancel!`,
`:cancel-resource!`, `:on-effect!`, `:on-error!`, `:tool-result-renderer`,
`:on-user-send!`, `:on-call-terminal!`, `:attachment-policy`,
`:session-run-port`) must not block; a host that
needs slow work posts a message or runs it in a task. The Owner never
acquires the workspace write gate `W` and never runs a Git command.

This package requires only `hc.core.*`, `hc.compose.*`, Clojure, and
Missionary. It does not require `hc.server.*` or `hc.client.*`. Server
adapters are injected as functions.

### 1.1 Multi-client, cancellation, and tool admission (contract index)

These rules are already normative in the sections below; this index prevents a
new caller from missing them:

- **One controller, many observers.**  The first client that submits session
  work becomes `:controller-id`; later clients are `:observer-ids`.  An
  observer may only attach, take over, or detach.  A takeover moves the old
  controller to the front of the observer queue; detach never cancels a run.
- **Cancel the model stream, not the domain.**  Cancellation closes the
  admission domain, cancels the live model attempt, preserves already streamed
  text and late tool results, and opens the successor only after the old run
  is cancelled.  A pending cancel blocks new model/tool claims.
- **Tool admission refusal limit 3.**  The same tenure/agent/member key gets
  at most three consecutive refusals; the third records
  `:TOOL_ADMISSION_REFUSED_LIMIT` with `:remedy :fix-request` and one
  `:owner/blocked` event, then clears the turn instead of Nudging forever.
  Central turns are settled by `settle-turn`; actor turns are settled by
  `wake-actors`, which skips the blocked agent (`wake-all-skipping`), keeps
  its slot idle, and emits the same diagnostic with
  `:source :wake-actors`.  A successful admission clears the count and the
  reported marker; a new tenure or a reborn agent has a new key.
- **`:owner/blocked` payload** is a stable event, not a silent log line:

  ```clojure
  {:type :owner/blocked
   :source :nudge | :wake-actors | :tool-result
   :diagnostic {:code :TOOL_ADMISSION_REFUSED_LIMIT
                :severity :error
                :message "Tool devops was refused at admission 3 times (...). Add the missing :scope annotation or stop calling it; do not repeat the same request."
                :remedy :fix-request
                :role :plan | :reviewer | :worker | :holder
                :lane :plan | :execution
                :tenure-id "tenure-1"
                :agent-id :plan | :worker-id
                :member "devops" | "clj-worker"
                :count 3
                :last-code :SCOPE_ANNOTATION_MISSING
                :at 1793851349694
                :turn-id "call-1"}     ; central turns only
   :at 1793851349694}
  ```

  The client renders `:message` as the prominent banner, `:remedy` as the
  next action, and `:member`/`:count`/`:last-code` as the detail line.  It
  must not collapse the event to a transient toast or invent a different
  remedy.

## 2 Namespaces

| Namespace | Responsibility |
|---|---|
| `hc.compose.owner` | Public handle: `start-owner!`, submit, snapshot, subscribe, stop. |
| `hc.compose.owner.mailbox` | Serial single-writer queue and loop. Never interrupts its thread. |
| `hc.compose.owner.store` | `Store` protocol and `memory-store` compare-and-set reference. |
| `hc.compose.owner.commands` | Client `command-id` / `command-epoch` gate. |
| `hc.compose.owner.state` | Initial state, call records, cancellation nodes, board summary. |
| `hc.compose.owner.handlers` | Pure message-to-state transitions. |
| `hc.compose.owner.guards` | P0–P3 Dijkstra guard catalog. |
| `hc.compose.owner.dispatch` | Missionary task, model gateway, timer, and report supervision. |
| `hc.compose.owner.tasks` | `run-required-steps`, `run-independent-tools`, `run-first-accepted`. |
| `hc.compose.owner.board` | Owner-backed workspace and Shell board adapters. |
| `hc.compose.owner.runtime` | Commit, guard round, outbox, notifications, lifecycle. |

## 3 Handle

```clojure
(hc.compose.owner/start-owner!
 {:session-id "s1"
  :store <Store>                 ; or :ephemeral? true
  :models {:main "..." :task "..."} ; defaults are test-only
  :session-port {:apply-command f :confirm-draft-saved f :fail-draft-save f}
  :attachment-contract {:resolve? false}
  :model-tools-supported (fn [binding] true|false|nil) ; live tool fact
  :tool-result-renderer (fn [value] "...") ; e.g. hc.server.program/render-tool-result
  :user-input "first user text"       ; optional, seeds relay input history
  :user-attachments [...]             ; optional, reference metadata only
  :initial-session map|nil
  :executors {:tool f :shell f :workspace f :devops f}
  :config {:plan-tools [devops-tool-spec] ; optional Plan tool surface
           :extension-tools-fn (fn [{:keys [session-id role tenure-id]}]
                                 [mcp-tool-spec])
           :skill-context-fn (fn [{:keys [session-id role tenure-id]}]
                               [{:id ".." :name ".." :text ".."}])}
  :model-gateway {:start! (fn [request deliver!] handle)
                  :cancel! (fn [handle])}
  :report-sink! (fn [report] result)
  :nudge-suppress (fn [state turn observation] reason|nil) ; test seam, see settle-turn
  :clock (fn [] epoch-ms)
  :quota 32
  :on-effect! (fn [notification])
  :on-user-send! (fn [{:keys [send-id turn-id session-id at]}])
  :on-call-terminal! (fn [{:keys [tool-call-id session-id name at result]}])
  :on-error! (fn [error-info])})
```

The handle is a map:

| Key | Contract |
|---|---|
| `:submit!` | Async enqueue. Returns queue acceptance. |
| `:submit-command!` | Sync client command. Waits for the Owner receipt. |
| `:execute-board!` | Sync board command, optional `{:write-gate-proof :verify-read-set}`. |
| `:board-snapshot` | Last committed swarm board map. |
| `:subscribe!` | Ordered notifications on a separate notifier thread; returns unsubscribe. |
| `:snapshot` | `{:version :state :stats}` from the committed atom. |
| `:stats` | Counters, mailbox, dispatcher. |
| `:await-idle!` | Mailbox empty and no live attempt. Timers may still be pending. |
| `:stop!` | Close admission, cancel attempts, drain, close store. Call as `((:stop! handle) opts)`; `{:close-store? false}` leaves the store open. Never interrupts the Owner. |
| `:workspace-board` | `hc.core.workspace-gate.board/BoardAdapter` over this Owner. |
| `:shell-board` | Four functions for a server-side `ShellBoard` reify. |
| `:board-port` | `{:execute! :snapshot}` for custom adapters. |

`hc.compose.owner/report-bridge` builds the `:report-sink!` and
`:on-effect!` pair. A report with `:parent-session-id` routes to that
session's Owner; otherwise it stays with this Owner. The sink adds
`:child-session-id`; the parent stores it and returns it in the
acknowledgement, which routes back to the child Owner. A missing target is
a retryable sink failure.

`submit-command!` returns `{:status :accepted ...}` or
`{:status :refused :error {...}}`. A timeout returns `{:status :unknown
:action :resync-state}`; it never fabricates acceptance. The Owner does not
block on the reply promise; the caller does.

## 4 Committed state

Plain EDN only. No thread, task, socket, store handle, or write permit.

```clojure
{:session-id string
 :mission map                ; hc.compose.mission state (authority)
 :relay map                  ; projection of (:relay mission)
 :board map|nil              ; projection of the sole execution tenure board
 :session map|nil            ; hc.server.sessions metadata, via :session-port
 :calls {call-id call}
 :runs {run-id run}          ; per model attempt: started/streaming/finished
 :scope {:book map :records []} ; hc.core.scope ledger
 :drafts {draft-id draft}    ; preserved composer drafts
 :cancel map                 ; hc.compose.cancel
 :reports {:reports {report-id report}}
 :acceptance {:terminated? bool :accepted {parent-call-id entry}}
 :commands {:command-epoch int :retired-through int :receipts {..}}
 :controller-id string|nil   ; the one client allowed to submit session work
 :observer-ids [string]      ; other attached clients, in attach order
 :conversation [message]
 :pending-turns [turn]
 :terminals [terminal]
 :pending-cancels [cancel]
 :nudges {nudge-id nudge}
 :nudge-seq int
 :nudge-budget {[tenure-id agent-id] {:count int :progress int :last-failure map}}
 :progress-seq int
 :turn map|nil
 :turn-history [map]
 :report-claims {report-id claim}
 :tool-refusals {[tenure-id agent-id member] {:count n :last-code kw}}
                              ; consecutive admission refusals; a successful
                              ; admission clears the key
 :diagnostics [map]          ; last 32
 :outbox [notification]      ; last 256, each with :outbox-seq
 :counters {:next-call int :outbox-seq int}
 :system-agent-id keyword
 :retry-policy map
 :config {:max-tool-concurrency int}
 :created-at epoch-ms
 :updated-at epoch-ms}
```

`:mission` is the authority for relay, per-tenure boards, role bindings,
model ids, and request idempotency.  `:relay` and `:board` are derived in
the same transition by `state/project`; they are never written
independently.  With several live execution tenures `:board` is nil; use
`state/board-summary` with a `tenure-id`.  A missing board returns nil; the
relay barrier records `:RELAY_BOARD_MISSING` and refuses to treat absence as
quiescence.

A call record carries `:kind` (`:model`, `:tool`, `:shell`, `:workspace`),
`:state` (`:ready`, `:dispatching`, `:running`, `:retry-wait`,
`:succeeded`, `:failed`, `:cancelled`, `:outcome-unknown`), `:attempt-id`,
the frozen `:request`, the retry planner record `:retry`, `:last-error`,
`:result`, and `:timer`. The call node and each attempt node live in the
cancellation tree under `:cancel`; the attempt node id is
`"<call-id>#<attempt-id>"`.

**Outbound context is frozen at the model claim.**  The app injects
`:outbound-context-fn`; at `:claim-model-request` (before dispatch) compose
calls it with

```clojure
{:session-id ... :call-id ... :attempt-id ...
 :role ... :lane ... :tenure-id ... :agent-id ...
 :messages <the frozen :request :snapshot :messages>
 :session <committed session metadata>
 :conversation <committed conversation>}
```

and accepts `{:ok? true :context map}`.  The map is stored on the call as
`:context` with `:attempt-id` bound inside, plus `:context-attempt-id`, and
mirrored onto the model run (`:runs <call-id>#<attempt-id> :context`).  A
retry reuses the same map and does not call the provider again; a new model
call computes a new snapshot.  A missing or failed provider leaves the call
without a context and never blocks the request.

A pending turn records `:role`, `:agent-id`, and the lane `:tenure-id` at
message acceptance.  The tenure id is a pin, not a hint: if the lane relays
before `:open-user-turn` runs, the guard consumes the turn with one
`:TURN_TENURE_STALE` diagnostic and one `:owner/blocked` event instead of
opening a model call under the successor.  A tool call created by that model
call carries the same pinned id through the program control context, so a
late `(review ...)` is refused by the mission with `:TENURE_RETIRED` and
cannot touch the successor's ledger or write lock.

`:outbox` is advisory. The state is authoritative. Each appended entry gets
a monotonic `:outbox-seq`, and the runtime drains only entries above the
last drained sequence. The vector keeps the last 256 entries. A restart
clears the persisted vector but keeps `:counters :outbox-seq`, so old
notifications are not replayed and the next effect is still numbered above
every earlier one. A handler may append an outbox entry even when the
business state is otherwise unchanged; `apply-message` commits that entry.
This is how a duplicate report delivery still returns its acknowledgement.

When a message changes `:session`, the runtime commits that session through
the injected `:commit-session!` before it commits the owner state and before
it returns the receipt.  The session record is the business authority for
lifecycle decisions such as purge eligibility, so an accepted receipt must
not precede it.  A failed or unknown session commit refuses or marks the
receipt unknown; it never reports acceptance.  At startup,
`:reconcile-session!` repairs a lag between the loaded owner state and the
committed session record before the Owner accepts work.

## 5 Messages

Client commands go through `submit-command!` and the epoch gate:

```clojure
{:command-id string :command-epoch int
 :payload {:kind kw ...}}
```

| Kind | Effect |
|---|---|
| `:send-message` / `:user/message` | Append a user message, open a pending turn that pins the current lane tenure, wake the board. |
| 同上，载荷带 `:chat? true` | Chat turn：该用户轮以普通 assistant 文本回答，不带任何工具面（无 `:plan-tools`、无扩展工具、无 `clj-plan`/`clj-review` schema 与角色提示）。快照保留 `:program-role` 供审计，`:chat?` 标记随请求冻结；`open-user-turn` 据此跳过 `request-tools`。`:answer-ask` 不接受该标记——挂起恢复必须留在程序路径。 |
| `:stop-run` / `:operator/cancel` | Queue an operator cancel; P0 applies it. Origin is `:operator-esc` or `:operator-stop`. |
| `:answer-ask` | Resume the selected awaiting tenure and append the answer as a new user message. With several awaiting tenures, pass `:tenure-id` or `:lane`; otherwise `:AMBIGUOUS_ASK`. |
| `:review/submit` | Submit `(review {:findings ...})` for a lane and tenure. |
| `:plan/write-draft` | Record Plan draft bytes through the mission relay ownership record; the mission enforces the pinned Plan tenure, the first-review write lock and the post-handoff freeze. |
| `:plan/claim-draft` | Reserve the first Plan draft path in the Owner transaction for the pinned Plan tenure; a concurrent different first-write path is `:DRAFT_NAME_TAKEN`, a retired tenure is `:TENURE_RETIRED`. |
| `:plan/release-draft` | Release a first-write claim by request-id after a failed apply or program; never changes final draft bytes. |
| `:user/unpark` | Resume a tenure without an answer. |
| `:board/command` | Apply one swarm board command. `:check-draft`, `:claim-draft` and `:release-draft` are mission draft authority, not swarm actions; `:check-draft` is read-only. |
| `:report/ack` / `:report/register` | Child-side report acknowledgement or registration. |
| `:session/issue-epoch` | Retire the current epoch and open the next. |
| `:session/attach-client` | Register the command's `:client-id`: the first client becomes the controller, later clients become observers. Returns the current controller, observer list, run id, and run state. |
| `:session/takeover` | Transfer control to the command's `:client-id`; the previous controller becomes the first observer. This is the only command an observer may use to become the controller. |
| `:session/detach-client` | Remove the command's `:client-id` from presence. If the controller leaves, the first observer is promoted. Detach never cancels a run or changes business state. |
| `:session/rename`, `:session/update-draft`, `:session/pin`, `:session/archive`, `:session/restore`, `:session/delete`, `:session/edit-message`, `:history/*` | Delegate to the injected `:session-port`; metadata/history only, never a run start or effect replay.  The Owner's `commit-session-change!` barrier persists the changed session through `:commit-session!` before the accepted receipt. |

### 5.0 Multi-client presence (tmux semantics)

The gateway stamps every browser command with `:client-id` (stable handshake
identity) and `:uid` (subscription identity).  `:client-id` wins; a command
with neither is internal or legacy and is not fenced by presence.

* The first client that submits session work becomes `:controller-id`.  Every
  other client is an observer in `:observer-ids`.
* An observer may submit only `:session/attach-client`, `:session/takeover`,
  and `:session/detach-client`.  Every other command is refused with
  `:NOT_CONTROLLER` and details `{:client-id :controller-id :action
  :takeover}`.  The refusal is returned before the command gate, so it never
  mutates state and never stores a receipt.
* Takeover is explicit.  The previous controller moves to the front of the
  observer queue; detaching the new controller promotes the longest-waiting
  observer.
* Presence is separate from execution.  Detach, unsubscribe, and a closed
  page never cancel a run, close a call, or release a write gate.  An attach
  receipt carries the active run id and state, so a reopening client reads
  the real state; it never restarts a run that another window stopped.
* The client identity is part of the command gate payload.  The same
  `command-id` from a different client is a `:COMMAND_ID_PAYLOAD_CONFLICT`,
  not a replay of another client's receipt.  `:session/issue-epoch` remains
  controller-only; an old epoch returns `:COMMAND_EPOCH_RETIRED` and the
  client must re-read before retrying.

A send whose central role is Plan or Reviewer is refused with
`:MODEL_TOOLS_UNAVAILABLE` when the session has a model binding and the
current model fact does not declare `:tools`.  The handler reads the live fact
through the injected `:model-tools-supported` resolver (binding identity plus
the current committed settings entry), falling back to the binding snapshot.
The refusal happens before the message is appended, so no conversation entry
or pending turn is committed and the draft stays with the user.  A nil
binding is left to the UI's no-model gate, and the app gateway still refuses
any tool-required request that reaches it without a tool surface.

A send carrying `:chat? true` is exempt from this gate: the turn is a plain
assistant-text answer with no program tool, so a tools-less model is a legal
target.  The gateway applies the same exemption before refusing a
tool-required program role.

A central send freezes the session's current `:model-binding` connection and
model entry into its request snapshot; a pending `:next-round` binding is
promoted to current for that turn.  The Nudge created when the turn stops
inherits the same identity from the ended turn, never a selection made while
the turn was streaming.  The global main role target is only the fallback for
a session without a binding, so changing the main role for another session
cannot reroute this one.

Internal messages carry no client epoch:

| Type | Effect |
|---|---|
| `:model/event` | Chunk, tool call, completion, failure, or cancellation from a model attempt. |
| `:call/terminal` | Executor terminal for one attempt. |
| `:timer/fire` | Wake P2 after a retry delay. |
| `:dispatch/failed` / `:dispatch/settled` | Dispatcher lifecycle. |
| `:child/report` | Parent-side acceptance of a cross-Owner report. |
| `:report/delivered` | Report sink result. |
| `:turn/ended` | The model turn ended; Nudge decides whether a stop is legal. |
| `:git/ff-ready` | Record the Git fast-forward dry-run result (`:ready?`). |
| `:recover` | Repair orphaned attempts and retry waits after a restart. |
| `:wake` | Re-evaluate guards. |

A stale `:attempt-id` is diagnostic only. It never rewrites a call or a
board task.

### 5.1 Completed mission and operator reopen

A mission is completed when no execution tenure is active and at least one
has reached `:completed` (`state/mission-completed?`).  A completed mission
accepts exactly one reopening input: `:send-message` or `:user/message`
marked with `:operator? true` on the command envelope (or inside the
payload).  The gateway sets that marker only for a genuine human message.

The Owner reopens with the typed mission op
`{:op :reopen :reason :operator-message :user-input ... :user-attachments ...}`.  This creates a
new execution tenure and board with `:initial? false` and
`:first-review? false`, so its first empty review relays to an independent
successor instead of asking "底稿已满足" again.  The completed tenure stays
as evidence.  The operator text is appended to `:conversation` and a
Reviewer turn is opened for the new tenure.

The following never reopen a completed mission:

- `:review/submit`, `:board/command`, `:user/unpark`, `:answer-ask`;
- a `:send-message` without the operator marker;
- model events, call terminals, child reports, report acks, turn-ended
  messages, timers, or wakes;
- a replay of an old command id (the gate returns the stored receipt and
  performs no business transition).

The gateway is the trust boundary.  The Owner cannot distinguish a forged
operator marker, so agents must not be able to set it.

### 5.2 User message during cancellation

`:send-message` / `:user/message` reopens only a run that is already
`:cancelled`.  A run that is still `:cancelling` keeps its closed admission
and stays the active run; `cancel/finish-run` closes it only after
`cancel/cancellation-quiescent?` is true, that is, after every Shell, workspace
action, and `:applying` program reported its exit.  Starting the successor run
earlier would let a live Shell or write-gate holder act under a second
admission while its own cancellation domain is still draining.

The message is not lost: the user entry, the pending turn, the committed
`:cancel :reopen-requested?` marker, and the `wake-all` outbox entry commit
in the same transition.  The marker is decided from the run state *before*
`state/open-successor-run` runs, because that call opens the successor of an
already `:cancelled` run and clears the marker with it.  `:open-user-turn`,
`:claim-model-request`, and `:claim-tool-call` all gate on
`state/admission-open?` and `:pending-cancels`, so the turn's model call is
created as soon as the old run closes.  Only a `:cancelled` run opens the
`<run-id>-r<n>` successor, and `:open-successor-run` consumes the marker in the
same transition.

`:outbox` entries get a monotonic `:outbox-seq` derived from both the counter
and the highest entry already present.  A recovered state therefore cannot
leave the runtime's drained pointer above a freshly appended
`:cancel/resource` entry, and a restart still replays nothing: `start!`
clears the persisted outbox and the sequence together.

## 6 Guards

The catalog composes existing modules:

| Rank | Guard | Action |
|---|---|---|
| P0 | `:accept-operator-cancel` | `hc.compose.cancel/accept-operator-cancel`; mirror call states; emit abort/timer effects. |
| P0 | `:cancel-late-nodes` | Signal a `:retry-wait` call under a `:cancelling` run that the operator walk left unsignalled (`cancel/actionable-resources`). Not time-based: a grace period would only end on an unrelated message and a dispatched timer would own the same decision twice. The expired transition is idempotent by `:cancel-requested?`, and its `:cancel/resource` effect is numbered into `:outbox` in the same commit. |
| P1 | `:accept-call-terminal` | `hc.compose.cancel/accept-terminal` on the attempt node; success fixes the call; failure leaves it for P2. |
| P1 | `:recover-lost-model-terminal` | Close a model call whose attempt is gone and no terminal arrived after the grace period. |
| P1 | `:project-tool-result` | Project one terminal tool/shell/workspace receipt once and mark `:result-projected?`. A central call appends it to the conversation as EDN+UFSS; a tool call under an actor-owned model call appends it to that agent's private `:context` instead (`actors/accept-tool-result`), never to the conversation. The tool-call record and a Scope refusal follow the same owner, so an actor's tool traffic never enters the central snapshot. Delivery records `:result-delivery` on the call: `:delivered`, `:stale-agent`, or `:undeliverable`; the last records `:TOOL_RESULT_UNDELIVERABLE` and one `:owner/blocked` event, and the call stays projected so the guard cannot spin. |
| P1 | `:finish-cancelled-run` | `cancel/finish-run` after every live resource is gone. |
| P1 | `:ensure-report-claim` | Repair a pending report with a missing or legacy `:failed` claim. |
| P1 | `:deliver-child-report` | Claim `:report-claims`, dispatch the report sink. |
| P1 | `:reclaim-child-report` | `delivery/mark-reclaimed` after acknowledgement. |
| P1 | `:schedule-report-retry` | Arm one timer for a failed report sink attempt. |
| P1 | `:wake-report-retry` | Make a due report claim pending again. |
| P1 | `:project-accepted-report` | Append a model-legal `:user` message with the report's raw `:text`, terminal status, and full `:payload`/`:payload-edn`, then `mark-projected`. |
| P1 | `:trigger-relay-barrier` | `mission/apply-command {:op :maybe-relay}` per pending tenure. |
| P2 | `:plan-retry` | `retry/plan-retry`; `:retry-wait` schedules a timer. |
| P2 | `:claim-due-retry` | State-only move to `:ready`; the P3 claim calls `retry/claim-due`. |
| P3 | `:wake-actors` | Sync fixed slots and start one `@task` model turn per idle actor. |
| P3 | `:open-user-turn` | Create a ready model call from one pending user turn. A turn pinned to a retired/unknown tenure is consumed with a `:TURN_TENURE_STALE` diagnostic and one `:owner/blocked` event; it is never rebound to the successor. |
| P3 | `:claim-model-request` | Claim one model call per agent; freeze the request. |
| P3 | `:claim-tool-call` | Claim tool/shell/workspace calls up to the per-agent limit. |
| P3 | `:settle-turn` | `nudge/decide`; create a Nudge call, record an explicit blocked diagnostic/event, or clear a legal exit. The turn stays until the agent has no pending/live call, so dispatch cannot lose it. |

`:settle-turn` has one gate, readiness. It runs only when the turn ended, no
pending user turn or uncommitted operator cancel is waiting, and no pending
model call or live call owns the agent. A
Nudge's own turn is not exempt: it stopped naturally, and only the three §2.5
exits end the prompt to continue. The next Nudge is a distinct candidate
because the previous one advanced `:nudge-seq`, not because a call marker
bans it.

`:settle-turn` enables on readiness, not on the decision, and the enabled
predicate never spends it: a round
that drains before the decision runs (a quota boundary, an abandoned
handshake) keeps the same turn, and the next cause re-decides it.
`:prepare` is the one place that commits an answer:

- a legal exit, an operator or admission stop, and every committed
  suppression record one `:turn-history` entry and clear `:turn`, so a
  settled stop is never re-answered and never lingers as a stale turn;
- an enabled decision keeps the turn for the Nudge call it creates; that
  call owns the agent, and its own terminal starts the next turn;
- a deferred decision keeps the turn eligible (it records `:deferred-at`, not
  a disabling marker), so the stop is judged again once the live work that
  deferred it ends.

A turn the policy cannot place in a task cycle (unsupported role, role/lane
mismatch, ambiguous or retired tenure) never owed a Nudge, but it still
stopped naturally, so it is cleared through the same history entry as any
other suppression; nothing is left pending for a later turn to overwrite.
`set-model-turn` also preserves an ended, unsettled turn instead of
overwriting it, so two terminals cannot drop the stop that still owes a
decision.  There is no separate "settled" marker to test: every committed
answer clears the turn, so an ended turn still present in state is by
definition unanswered, and any later terminal replaces it as a new stop.

The same guard honours a `:nudge-suppress` runtime hook (an option, never
committed state).  When the policy enables a Nudge and the hook returns a
non-nil reason, the turn is cleared and `:turn-history` records
`:reason :nudge-suppressed` with `:suppress-reason`; no call is created.
Legal exits, permanent/context blocks, and deferrals are answered before the
hook runs, and the hook is level-triggered, so every later natural stop is
judged again.  The app wires `:nudge-enabled? false` (config or
`HC_NUDGE_ENABLED=false`) to this hook for tests that need an observable
idle window; the default leaves §2.5 continuation unchanged.

The scheduler context and its P4 verdict are unchanged: an ended turn that
no guard derived work from is not a new dead-end class.

The policy reads the failure from the whole observation.  `turn-observation`
forwards `:failure`, `:failure-facts` (the cause that reached the Owner when
the call's own terminal error is the retry plan's shape), and the host's
`:permanent-failure?` verdict; the policy also classifies a failure it finds
in those facts by `:kind`/`:category`/`:status`.  A terminal cause therefore
reaches `NUDGE_BLOCKED_PERMANENT_FAILURE` or
`NUDGE_BLOCKED_CONTEXT_OVERFLOW` whichever field carries it, and the host
never repeats a request that cannot succeed.

Dispatch is gated separately from the decision. A closed admission keeps
every call, Nudge included, undispatchable; a cancelling run records
`:operator-cancelled` in `:turn-history` and dispatches nothing. A closed
admission without a live cancel domain records `:admission-closed` the same
way. Neither is a natural stop, so §2.5 owes them no Nudge.

Every claim commits `:ready` → `:dispatching` and the attempt id before
`after-commit` releases dispatch effects. The retry timer uses its own claim
slot `[:calls id :timer]`. Report delivery uses `[:report-claims id]`; a
failed sink attempt moves that claim to `:retry-wait` with a bounded
exponential `:retry-at`. The schedule guard arms one timer and the wake
guard returns the claim to `:pending` at the due time. There is no attempt
cap and no `:failed` report claim. A successful sink call moves the claim to
`:delivered` and arms a 5 s ack timeout; a missing acknowledgement returns
the claim to `:pending` and redelivers. The parent returns its stored ack.
A pending report with a missing or legacy `:failed` claim is repaired by
`:ensure-report-claim` before delivery.

The scheduler quota requeues a `:wake` message. A dead end with an active
tenure, no in-flight work, and no timer records one explicit `:BLOCKED`
diagnostic per signature and emits `:owner/blocked` through `:on-effect!`;
the handling path is woken without re-enqueueing the same scheduler. The
signature is fixed only after the diagnostic commit succeeds; a failed
commit leaves it unset so the next blocked observation retries. A
round that drained, or that exhausted its quota, re-enters once before the
dead-end verdict when a timer is pending, so arming the late-node timer is
never reported as `no timer is pending`. The guard context also treats any
live dispatcher timer (retry, report redelivery, or late-node) as
`:timed-wait?`, even when no call is `:retry-wait`; a pending timer is a
legal wait, not a dead end. A recorded `:BLOCKED` entry is a point-in-time
audit observation, not a live status: `:inflight?` reads the committed calls
and live dispatcher tasks, so a later `:dispatching` call suppresses the
dead-end verdict and the older entry remains only as history.

### Esc / stop contract

- Executor values may be `{:run fn :cancel fn}`; the injected
  `:cancel-resource!` hook handles settled or parked attempts that the
  dispatcher no longer owns.
- When no `:cancel-resource!` is supplied, the runtime installs a default
  that acknowledges the stop (`{:ok? true :cancelled? true}`) rather than
  failing with `:NO_CANCEL_RESOURCE_HOOK`.  A missing hook is a
  configuration fact, and a hard error there left `:resource-alive? true`
  forever, so `finish-run` refused and every later user turn waited on a
  run that could never close.  A host that owns a managed process must still
  pass a real hook; the dispatcher cancels the live attempt task itself in
  either case.
- `:cancel/resource` outbox entries carry `:call-id`, `:node-kind`, and the
  executor/resource `:kind` (`:shell`, `:tool`, `:workspace`, `:devops`).
  The Owner prefers `:node-kind` over the legacy `:kind`, routes by
  `:executor-kind`/`:resource-kind`, and preserves `:resource-id`.
- A P1 late terminal is accepted on the call node before attempt matching and
  stores the payload/error in bounded `:late-results`. It never reopens the
  call and never appends conversation.
- **Only a settlement outcome ends a resource.** `:succeeded`, `:failed`, and
  `:cancelled` clear `:resource-alive?`; an arriving `:outcome-unknown`
  reports that no executor result exists and leaves the flag set. A cancel
  signal, a task teardown, and a `:dispatch/settled` for a non-model attempt
  are therefore not evidence that a Shell, workspace action, or devops
  worker ended.
- `cancel/finish-run` blocks while any node still has `:resource-alive?
  true`; only a settlement observation or an explicit reconciliation clears
  it.
- `recover-state` drops process-local claims: terminal nodes lose
  `:resource-alive?`, live action/process nodes become
  `:uncertain`/`:outcome-unknown`, and outcome plus evidence are preserved.
  Recovery never re-signals a node it cannot observe, so a restart neither
  replays a stop nor leaves the domain unclosable.

### Dispatch identity

A prepared call effect is abandoned before commit when an operator stop is
already pending; the guard is skipped for the rest of the round and
`:dispatch/skipped :reason :stop-pending` is emitted. Report and timer
effects still run so their claims close.

`after-commit` replay is at-least-once. Model and executor dispatch are
idempotent by attempt id. A timer belongs to one attempt id: replaying the
same handshake does not move `:retry-at`, and a new attempt id replaces the
old timer. A model gateway that returns nil instead of a handle posts a
`:call/terminal :failed :invalid-request` before returning
`:MODEL_START_FAILED`, so no actor slot or call stays `:dispatching`.

`drain-outbox!` advances `:outbox-drained` only after every pending entry
has run. A thrown entry leaves the index behind; cancel, timer-cancel, and
timer-rearm are idempotent. A guard plan rejection records
`:guard-rejected` through the runtime error path.

If the mailbox refuses a `:wake` (quota requeue, guard-commit conflict, or
scheduler recovery), the runtime schedules a dispatcher timer with the
distinct `owner/wake-fallback` call id. The timer posts `:timer/fire`; it
never synthesizes a call terminal. A guard-commit conflict therefore never
strands level-triggered work after its bounded retries.

A fallback timer that is itself refused because the mailbox is full is not a
lost wake: every accepted message ends with a guard round, so a due retry is
observed when the queue drains.  If the mailbox is closed, the Owner is
stopping; the durable `:retry-call` index and owner-state recovery handle the
next start.  Neither path fabricates a terminal outcome.

`apply-settled` keeps the model-only outcome re-observation. A non-model
`:dispatch/settled` without an executor terminal marks the attempt node
`:external-outcome-unobserved?` (with `:unobserved-at`) and mirrors the
boolean on the call, plus one `:EXTERNAL_OUTCOME_UNOBSERVED` diagnostic per
attempt. It never writes the outcome and never clears
`:resource-alive?`; task settlement is not evidence that a Shell, workspace,
or devops resource ended.

An entry is pending when its `:outbox-seq` is above the pointer, so the
pointer is a high-water mark, never a vector index. Restart clears the
persisted vector only; the counter is committed state and survives. The
drained pointer lives only in the runtime atom and resets to 0 whenever it
differs from the committed counter, so a restart replays nothing and skips
nothing. `state/append-outbox` derives its next sequence from the highest
existing entry as well as the counter, so a recovered or hand-built state
cannot leave the pointer above a fresh `:cancel/resource`. A guard that
produces cancellation effects without a claim or a dispatch entry numbers
them into `:outbox` itself; a sibling `:outbox` plan key is never delivered.

A settled or parked `:cancel/resource` hook may return a terminal receipt.
The dispatcher normalizes raw program/shell receipts into `:terminal`; the
runtime then submits one synthetic `:call/terminal` with
`:source :cancel-resource`.

Only an observed end is evidence.  These never synthesize a terminal:

- a stop signal, a registered stop, or an unconfirmed stop
  (`:STOP_SIGNAL_SENT` / `:STOP_REGISTERED` / `:STOP_UNCONFIRMED`);
- a `SESSION_ENDED` receipt with no result map, or with an unrecognized
  inner status (an unknown status is `:outcome-unknown`, never
  `:cancelled`);
- a still-committing apply (`:cancel-after-apply?`), whose minimal batch
  still owns the write;
- a cooperative workspace stop, which reports `:ended? false` by contract.

`shell-stop-terminal` and `program-stop-terminal` in the app assembly are
the single mapping points; a hook that returns nil leaves
`:resource-alive?` set, and the executor's own terminal remains the only
release.

Model snapshots use the adapter role, not the mission role: actor turns use
`:role :task` plus `:mission-role (:worker/:holder)`; central turns use
`:role :main` plus `:mission-role (:plan/:reviewer)`. The Owner call
record keeps the mission role.

**A turn that renders the shared conversation never opens mid-stream.**  The
call's snapshot freezes `(conversation-tail state)`, so if any run in the
shared `:conversation` is still `:started` or `:streaming`, its assistant
message text is still growing.  Freezing that text records a digest no later
request can match, and the next request that reads the final text reports
`:PREFIX_DRIFT` at that assistant index — a real content change caused by a
torn read, not by any reader.  `next-user-turn` therefore requires
`state/conversation-stable?` in addition to `live-model-for-agent?` (which
only covers the same agent).

Two exemptions, both because the run cannot move this conversation:

- **Actor runs** — swarm slots and the Plan dedicated worker — are ignored.
  `put-assistant` writes no message for a call with `:actor-attempt-id`
  (`state/actor-call?`), because that text already lives in the agent's
  private `:context`.  Their runs therefore neither gate a user turn nor
  enter the prefix chain, and workers keep running concurrently.
- **Nudge requests** are ephemeral: a request carrying `:snapshot :nudge-id`
  records no chain entry.  The Nudge *reply*, however, is an ordinary
  assistant message in `:conversation`, and the next user request includes
  it — so a streaming Nudge run **is** gated.  Only the request is exempt,
  not the reply.

The gate has no timeout of its own: it depends on every run reaching a
terminal status.  A model attempt that stays live but reports nothing keeps
its run `:started`, and `recover-lost-model-terminal-guard` deliberately
waits for the attempt to be gone, so such a turn parks until the gateway
reports.  Do not add a second timeout here; the run lifecycle owns that
guarantee.

## 7 Missionary supervision

`dispatch` starts exactly one task per claimed attempt:

- executors run on `m/blk` and return a status map or a plain success value;
- the model gateway receives `{:attempt-id :call-id :snapshot
  :required-capabilities}` and a `deliver!` function; it must not block;
- timers use one daemon `ScheduledExecutorService`;
- report sinks run on `m/blk`.

A terminal posts `:call/terminal` (executor) or `:model/event`
(model). Cancellation records the reason, resolves the attempt by canonical
attempt id (the scheduler keys tasks by keyword, the cancellation tree
stores the normalized string), calls the task's cancel thunk or the
gateway's cancel function, and posts `:cancelled`. The advisory outbox
entry carries the executor kind (`:tool`, `:shell`, `:workspace`,
`:devops`) in `:kind` and the cancellation node kind in `:node-kind`, so
the fallback `:cancel-resource!` hook can route a settled or parked
resource without guessing. Cancellation interrupts only the executor
thread. It never touches the Owner thread, a store commit, or a
FileChannel writer.

Dispatch is idempotent by `:attempt-id`. A scheduler replay of
`after-commit` that reaches an already-running attempt returns
`:already-running? true`; it does not start a second provider call and does
not post a second terminal. Every non-ok start of a claimed model or
executor effect posts exactly one `:call/terminal` with status `:failed`.
If the dispatcher did not post it, the runtime posts the fallback using the
committed call's `:attempt-id`. A claimed call therefore cannot remain
`:dispatching` because its start failed.

An executor result is normalized fail-closed for a side-effecting kind
(`:tool`, `:shell`, `:workspace`, `:devops`).  A nil return, a non-map, or
a map whose `:status` is not one of the four Owner outcomes becomes
`:outcome-unknown` with an explicit error, never `:succeeded`: silence
proves nothing about a process that may still be running.  A model attempt
owns no external resource, so an unrecognized value stays a success payload
there.

A start failure that is an `ExceptionInfo` keeps its ex-data in the
terminal error.  `:code`, `:kind`/`:error/kind`, `:category`, `:details`,
and `:message` survive, so the retry planner, the Nudge classifier, and the
client receipt see the real cause instead of an anonymous executor error.
The gateway must mark a required-search refusal permanent at the throw
site; the Owner does not guess that an arbitrary search HTTP 500 is safe to
repeat.

A required-search refusal may also arrive as a `:model/event :failed` with
`:kind :search-required-failed` and `:category :permanent` inside
`:error`.  The failed run keeps that error; P1 stores it as the call's
`last-error` for P2, and P2 must preserve it as the terminal cause instead
of replacing it with a generic retry-exhausted map.  The client receipt and
the search failure label read that cause.

The runtime also finishes a scheduler handshake that was interrupted after
`step`. `:awaiting-commit` commits the candidate state retained in the
scheduler's pending dispatch when the store version still matches;
`:awaiting-dispatch` replays `after-commit` and `ack-dispatch` and hands the
same effects to the idempotent execution layer. A version conflict abandons
the pending handshake and wakes the Owner so the level-triggered guard can
re-prepare from committed state. If the reloaded state is exactly the
candidate, the earlier commit succeeded and only its result was lost; the
runtime replays the dispatch handshake instead of orphaning the claim.

Before starting a model, tool, Shell, workspace, or devops effect, the
runtime checks that the committed call still has the same attempt id and is
still `:dispatching` or `:running`. A stale effect (for example one replayed
after a stop or retry) is recorded as `:dispatch/skipped` and never starts
external work. Timers and reports keep their own claim slots.

A prepared handshake is also abandoned when it would start a model, tool,
Shell, workspace, or devops call while `:pending-cancels` is non-empty or
the active run is `:cancelling`/`:cancelled`. P0 cancellation then runs
before any stale claim can start a provider request. Report and timer
effects still run so their claims close. This is a runtime gate, not a
successful-turn cap.

When a cancel hook observes a final result for a settled/parked resource,
it returns `{:terminal {:status ... :payload ... :error ...}}`. The runtime
posts that as a synthetic `:call/terminal` with `:source :cancel-resource`
only when the dispatcher attempt is no longer live. The normal P1 late
branch accepts it, clears the attempt/call `:resource-alive?` flag, and lets
`finish-run` close. A hook that only sent a stop signal must not return a
terminal; the executor's late terminal remains authoritative.

`tasks` provides the three §5.3.4 combinators. They complete with data and
never fail-fast across independent branches. Multi-worktree join is not
`run-first-accepted`; it stays in `hc.compose.fleet`.

### 7.1 Model run lifecycle

A model attempt is one run (`:runs "<call-id>#<attempt-id>"`).  A claim
creates it as `:started`; each `:chunk` appends to the run text and updates
one assistant message (`:message-id`, `:streaming? true`) in the same
commit.  A completion/failure/cancellation finalizes the run and the
message.  A late chunk or terminal for a finished run is kept in
`:late-events` and never changes content.  Retries create a new run because
the attempt id changes.

An **actor run** (`:actor-attempt-id` on its call) is the exception: the run
still records its own text and terminal, but `put-assistant` writes no
assistant message for it, and `mirror-assistant-history!` mirrors nothing.
That text belongs to the agent's private `:context`
(`hc.compose.mission.actors/accept-model-event`); writing it into the shared
conversation would leak a worker reply into the central Plan/Reviewer
snapshot and move the Pair Hint chain.  Actor runs are also exempt from
`conversation-stable?`, so workers keep running beside a user turn.

**Recorded text never shrinks to blank.**  A terminal that carries no text
keeps the text already streamed, and `put-assistant` will not replace a
non-blank assistant message with blank text.  The outbound copy is reused
across turns and Pair Hint verifies it as a prefix, so an empty overwrite
would report `:PREFIX_DRIFT` at that message for a change the operator never
made.  A cancellation therefore keeps the visible partial answer, marked
`:status :cancelled` / `:cancelled? true`; text that a terminal does carry
still replaces the streamed text.

### 7.2 Scope and attachments

Tool-call events run Scope admission before a tool call is created.  A
refusal creates no call and no Scope settlement; it appends one synthetic
`:tool-call` plus its `:tool` refusal result, so strict providers never see an
orphan tool result.  Duplicate tool-call ids are ignored before admission, so
a replay without an annotation cannot turn into a refusal.  The Owner call id
is derived from the parent model call and the provider output index, never
from the model port's project id, so a port id spelled `call-1` cannot be
mistaken for the Owner model call of the same spelling.  When a retry at the
same output index would collide with an older attempt, the Owner appends the
normalized attempt id; a late result for the old attempt therefore cannot
enter the new call.
The tool request carries the parent model call's `:tenure-id` and `:lane` at
the top level.  The program bridge must preserve them into its evaluator
request: `(review ...)`, draft commands, and board commands use that identity
verbatim.  Re-resolving the current tenure at tool-call time would apply a
late call to a successor tenure after a relay.

The Plan tool surface merges `:config :plan-tools` with the injected
`:extension-tools-fn` result.  `:skill-context-fn` returns
`{:id :name :text}` maps; the Owner prepends one `:system` message per active
skill before the conversation tail (or before a worker's private context).
Both functions receive `{:session-id :role :tenure-id}` and must be pure and
non-blocking.  A tool request's `:metadata {:server-id .. :tool ..}` is
preserved for the executor; app assembly routes it to the MCP call path
instead of the Clojure program runtime.
The Owner merges the model's argument map only for program content and Scope.
Tenure, lane, actor, call id, executor, program role, and Plan policy
(`write-unlocked?`, `draft-path`, `readable-paths`, `s1?`, `plan-draft`)
are Owner-derived; a model argument cannot override them.
A tool terminal settles the Scope annotation in the same transition as the
terminal.  A model terminal closes the turn only when no live child with the
same `:scope-turn-id` remains; a child terminal closes it when the model call
is already terminal and no sibling remains.  A late model terminal for a
different turn is ignored (`:SCOPE_CLOSE_STALE`), never a newer turn's
default.  The model claim guard treats ready or live calls as in-flight
(except its own candidate), so the next model turn cannot start before the
previous child settles.

**A refused tool admission never hangs the turn.**  The refusal appends a
synthetic assistant `:tool-call` plus its `:tool` refusal (so strict
providers see a valid pair), increments
`:tool-refusals [tenure-id agent-id member]`, and leaves no executable call
record or Scope settlement.  A successful admission for the same key clears
the count.  The model turn still ends through its normal terminal; §2.5 then
owes a Nudge, and the Nudge request carries the refusal so the model can add
the missing `:scope` and retry.  After
`guards/tool-admission-refusal-limit` (3) consecutive refusals for the same
key, `settle-turn` records `:TOOL_ADMISSION_REFUSED_LIMIT` with
`:remedy :fix-request`, the member/count/last-code, and one `:owner/blocked`
event, then clears the turn instead of Nudging again.  A model that ignores
the tool result therefore reaches an actionable terminal state rather than
an infinite "正在生成" loop.

On restart, `state/restore` cold-starts `[:scope]` through
`hc.core.scope.runtime/cold-start-state`: records and book history stay, but
the retention budget and the in-memory turn are cleared.  The model
conversation is projected through `hc.core.scope.projection` before the
snapshot, keeping operator messages and the current reset boundary.

User messages persist `:attachments`, `:quotes`, `:citations`, and `:draft`
in the conversation entry; attachment-only messages are valid.  A model
claim checks `:attachment-contract` and fails the call with
`:ATTACHMENT_UNRESOLVED` rather than dropping a native attachment that the
gateway cannot resolve.  A `:text` route with `:send-text` needs no
resolver.  The gateway/model adapter is responsible for loading native bytes
and must fail explicitly.

## 8 Store

`Store` is a whole-state compare-and-set:

```clojure
(load-state store)              ; {:version n :state map|nil}
(commit! store expected state)  ; {:ok? true :version n} | {:ok? false :error}
(close! store)
```

The Owner commits only on its thread. A version conflict reloads and
re-applies the pure message transition; it never dispatches before commit.
`memory-store` is the reference. A real adapter must store one document per
session and refuse a stale expected version with `:VERSION_CONFLICT`.

## 9 Board adapters

The Owner is the only writer, and the mission host is the board authority.
`:execute-board!` submits `{:op :board-command ...}` to
`hc.compose.mission/apply-command`; the mission host updates the tenure board
and its bindings in one transition. `hc.compose.owner.board` never creates a
second kernel.

Plan draft commits carry `:plan-draft` from the workspace session. The
workspace bridge's Owner adapter (`hc.compose.workspace.board`) routes them
to the mission's current Plan tenure, `:draft-claim` and
`reviewed-in-tenure?` binding, never a swarm actor. The first draft claims in
the under-W pre-apply and rechecks read-only after the dry-run; a fixed-path
revision claims after the dry-run. Either Owner reply is the permit: a
retired tenure or a failed persistence refuses before the file apply.

Root task creation belongs to the mission host: workers split existing
tasks. A root `:cancel` and `:replace` are auto-routed by mission to its typed
root transitions: cancel removes the board task, relay mirror, and ledger key
in one transition and may trigger the barrier; replace preserves the ledger
key and swaps the root binding without releasing the obligation. A root
`:finish` releases the ledger key and may trigger the atomic relay barrier.

- `workspace-adapter` implements `hc.core.workspace-gate.board/BoardAdapter`.
  `begin-apply!` submits `:begin-action` kind `:edit` and advances to
  `:applying`; `publish-applied!` advances to `:finished`.
- `shell-port` returns `:check-eligibility!`, `:begin-run!`,
  `:publish-finished!`, `:publish-uncertain!`. A server reify of
  `ShellBoard` delegates to it.
- `:finish` with a non-empty `:read-set` needs the code-level
  `:write-gate-proof` capability plus `:verify-read-set f`. The capability is
  the opaque singleton from `hc.compose.mission.proof`; only the workspace
  bridge attaches it, and only while it holds `W`. A wire boolean, string,
  keyword, map, or any other EDN value is never trusted. A caller that
  already verified stamps inside `W` may submit `:verified-read-set` with
  the same proof instead; the board records that evidence in `result-ref`.
  Without the proof the board refuses `:WRITE_GATE_REQUIRED`. The proof is
  never stored in committed state, receipts, program results, diagnostics,
  or `:owner/committed` notifications; the runtime strips it from any
  message it records or forwards.
- A refusal before a write is an ordinary rejection; a refusal after a write
  is a hard failure. The Owner never classifies a file effect as safe.

## 10 Invariants

1. One committed state writer per session; only the mailbox thread commits.
2. Every dispatch has a committed claim and attempt id.
3. A terminal for an old attempt id never rewrites current state.
4. A failed attempt is not a failed call until P2 fixes the call terminal.
5. Cancellation is per attempt; sibling branches keep running.
6. A report is pending until a valid acknowledgement; a terminated parent
   still acknowledges.
7. A report payload written by an LLM is delivered in full.
8. Nudge is suppressed for a legal exit and for `:awaiting-user?`, and
   dispatch is suppressed while admission is closed or a cancel is
   uncommitted. No Nudge call is exempt from the prompt to continue: a
   settled Nudge turn is judged by `nudge/decide` again. A
   permanent/context failure records one `:owner/blocked` diagnostic with
   the raw failure and creates no model call. Ordinary natural stops keep
   nudging; the anti-loop budget applies only to failure turns, is keyed by
   tenure+agent, and emits `:NUDGE_BUDGET_EXHAUSTED` when exhausted.
   Nudge assistant responses are retained in the conversation with
   `:nudge?`/`:nudge-id` for publisher/UI merge or filtering; the Owner does
   not discard a real response.
9. No Owner path acquires `W`, runs Git, or blocks on I/O.
10. A restart reads one committed state; it never replays history.
11. Restart recovery preserves a call whose matching terminal is already in
    `:terminals` (P1 consumes it normally), and marks the remaining
    `:dispatching` or `:running` call
    `:outcome-unknown` (it has no live task and may have acted), resumes a
    `:retry-wait` call immediately (a due retry becomes `:ready`; a future
    retry re-arms its fixed timer without moving `:retry-at`), and rebuilds
    a `:pending` claim for every report that is still pending. A claim left
    in `:dispatching`, `:delivered`, or `:retry-wait` is reset; claims for
    acknowledged, reclaimed, or absent reports are removed. `:recover` is the
    first message the Owner processes.
12. A terminal tool, Shell, or workspace result is projected exactly once
    and marked `:result-projected?` before a model claim for the same agent
    can start.  A central call's result goes to the conversation as EDN+UFSS;
    a tool call under an actor-owned model call goes to that agent's private
    `:context` with its `:tool-call-id` and name, and never to the shared
    conversation.  Delivery is recoverable: a missing parent attempt falls
    back to the live `:tenure-id`/`:agent-id`, so the result still reaches the
    agent.  Only when no live agent can receive it is the failure terminal:
    the call records `:result-delivery {:status :undeliverable}`, a
    `:TOOL_RESULT_UNDELIVERABLE` diagnostic and one `:owner/blocked` event,
    and stays projected, so it neither leaks nor spins.  The model claim
    guard waits for every live call of that agent, including tool calls and
    retry waits.  A structured result renders as one EDN+UFSS value even
    when it contains a `:text` field; only an already-string result bypasses
    the structured renderer.  The `:tool` message carries `:error?` for any
    non-`:succeeded` state.  A central Scope refusal appends a synthetic
    `:tool-call` before the `:tool` refusal so strict providers see a valid
    pair; an actor-owned refusal goes only to that agent's private context.
    If Scope projection refuses, `conversation-tail` rebuilds model-valid
    roles with `hc.core.scope.runtime` instead of sending `:tool-call` to the
    adapter.
13. One controller per session.  The first client that submits work becomes
    the controller; other clients are observers.  An observer's session
    command is refused with `:NOT_CONTROLLER` before the gate, and only
    `:session/takeover` transfers control.  Detach, unsubscribe, and a closed
    page never cancel a run; an attach receipt reports the current run state
    instead of restarting it.

## 11 Tests

`test/hc/compose/owner/` contains deterministic tests and properties for the
store, mailbox, dispatch, handlers, guards, board adapters, combinators, and
the full runtime. The runtime tests use real threads, real Missionary tasks,
real timers, and a real in-memory store.

Dispatch tests cover timer idempotency by attempt id, nil model-gateway
handles, and raw program/shell cancel receipts normalized into synthetic
`:call/terminal` messages. Actor and Nudge integration tests pin the
provider-role contract: `:role :task` for actor turns, `:role :main` for
central turns, with `:mission-role` carrying the mission role.

`terminal_contract_test` covers the visible failure mode: a completed model
event must leave the call `:succeeded`, clear the assistant streaming flag,
and preserve citations; a failed model start must never leave the call
`:dispatching`; duplicate dispatch by attempt id is idempotent; and the
runtime recovers an interrupted scheduler handshake.

`hc.compose.cancel-integration-test` is the real Esc path: a managed shell
subprocess must stop, the Owner call must remain `:outcome-unknown`, the run
must close `:cancelled`, and the shell session must publish `:cancelled?
true` with an observed exit code. `dispatch_test` pins the canonical
attempt identity that lets the cancellation outbox find the scheduler's
keyword-keyed live task; without it the executor cancel thunk is skipped and
the fallback hook sees only the node kind.

```bash
clojure -M:dev:compose -e \
  "(require 'hc.compose.owner.runtime-test) \
   (clojure.test/run-tests 'hc.compose.owner.runtime-test)"
```

The repository `:test` alias also discovers these namespaces.

## 12 Known gaps

1. The model gateway is a function seam. App assembly must adapt
   `hc.server.model-port.contract` to `{:start! :cancel!}` because compose
   cannot require `hc.server.*`.
2. Chunks are committed one message at a time. The adapter should coalesce
   adjacent chunks before `deliver!` when throughput matters.
3. `:ff-ready?` in the closing check is a state flag. Git fast-forward
   dry-run belongs to the integration layer; the Owner never runs Git.
4. Reopening a completed mission creates a new execution tenure with
   `:initial? false` and `:first-review? false`; it does not rewind the
   completed tenure or the draft.  A non-empty first review enters swarm; an
   empty first review relays to an independent successor.
5. `:calls`, `:conversation`, and `:receipts` grow with the session. They
   are current-state records, not history replay, but compaction policy is
   not implemented yet.
6. The Owner trusts the opaque `hc.compose.mission.proof` capability. Only
   the runtime/workspace bridge attaches it after acquiring `W`; a bare
   `:under-write-gate? true`, or any EDN value in `:write-gate-proof`, is
   never trusted. The Owner cannot observe `W` ownership from another
   thread, so in-process code that calls `proof/token` directly can still
   lie; the program sandbox denies that namespace, and the token is never
   persisted or returned.  The runtime strips it from commit diagnostics and
   `:owner/committed` notifications before they reach observers.
7. The scheduler's attempt ids are keywords; the retry planner stores the
   string form. The two identities are intentionally separate and are
   documented in `claim-call`. Cancellation nodes store the normalized
   string; `dispatch/cancel-resource!` resolves it to the live keyword-keyed
   task by canonical identity before invoking the cancel thunk.
8. Several execution tenures may be live. `:board` is a convenience
   projection only when exactly one exists. A board command without
   `:tenure-id` is accepted only in that case; with several, every command
   must carry `:tenure-id`. The relay-barrier guard iterates every pending
   tenure, and each barrier is independent.
9. The session metadata port is injected as `:session-port`
    (`:apply-command`, `:confirm-draft-saved`, `:fail-draft-save`, and the
    optional persistence barrier `:commit-session!` / `:reconcile-session!`).
    The Owner stores `:session` and gates the command with its existing
    `:commands` epoch.  A changed session is committed through
    `:commit-session!` before the owner state and before the accepted
    receipt; a port without it refuses the business change with
    `:SESSION_PERSISTENCE_UNAVAILABLE`.  `:reconcile-session!` runs at start
    and repairs a lag between owner state and the committed session record.
    App assembly adapts `hc.server.sessions`; compose never requires
    `hc.server.*`.
10. Root task creation belongs to the mission host's review/relay contract;
    workers split existing tasks. Root cancel/replace are typed mission
    transitions and keep the board, relay mirror, and ledger consistent.
11. Accepted report payloads are stored in full (the protocol forbids
    truncating an LLM report).  The Owner has no payload compaction policy;
    a store adapter must bound document growth or move large payloads to an
    explicit retention layer outside the Owner transaction.
