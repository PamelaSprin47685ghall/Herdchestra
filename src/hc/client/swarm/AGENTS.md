# `hc.client.swarm` — fleet and board dashboard

Authority: root `AGENTS.md` §7.3–§7.4 > `src/AGENTS.md` >
`src/hc/client/AGENTS.md` > this file.

## Purpose

`hc.client.swarm` renders the read-only Swarm/fleet topology required by root
`AGENTS.md` §7.4 旅程 3.5（蜂群施工） and the board-view assertions in §7.5 of the source-manual
reconciliation:

- fleet overview: `slice-id`, worktree, status, root task counts, master
  version, join queue;
- root tasks and the full child hierarchy, with goal, `:remaining-work`,
  `:done-when`, state, `:wait-for` chain, owner, claim, token flag, and review
  ledger key;
- fixed slots with the current `agent-id`, role, life state, and a lifecycle
  explanation;
- the unique worktree token holder, `term-id`, and the complete
  `R(holder)` responsibility set;
- the worktree write entrance W, its current action, and its FIFO queue;
- joined results with the full report text, fast-forward commit, and rebase
  rounds;
- abandoned and failed results with their reason or error detail.

The package does not submit commands and does not change domain state.  Swarm
selection and board drill-down use native `<details>`/`<summary>`; the host
route supplies `:swarm/selected-id` when a deep link is wanted.

## Files

| File | Responsibility |
|---|---|
| `model.cljc` | Pure projection from a Fulcro database or dashboard snapshot to fleet rows, task trees, wait chains, slot rows, holder/W views, reports, labels, and summaries. Runs on JVM and CLJS. |
| `ui.cljs` | Fulcro component, pure renderer, Framework7 React element tree, accessible markup, responsive scoped CSS. |

## Public entry points

```clojure
(require '[hc.client.swarm.model :as swarm-model]
         '[hc.client.swarm.ui :as swarm-ui])
```

| Name | Contract |
|---|---|
| `swarm-ui/SwarmDashboard` | Documented plain React component. Accepts `#js {:db normalized-db}` or `#js {:swarm/dashboard projected :swarm/selected-id id}`. Renders without a Fulcro app context, including server-side rendering. |
| `swarm-ui/SwarmDashboardFromDb` | Alias for `SwarmDashboard`; explicit name for hosts that pass `#js {:db db}`. |
| `swarm-ui/SwarmDashboardFulcro` | Optional Fulcro query wrapper. Query `[:swarm/dashboard :swarm/selected-id]`; requires a Fulcro app context. |
| `swarm-ui/dashboard-element` | Pure renderer for an already projected dashboard. Returns a React element; used by tests and by hosts that project outside the component. |
| `swarm-ui/dashboard-css` | Scoped CSS string embedded in the component. Hosts may override the custom properties. |
| `swarm-model/dashboard` | Project a dashboard snapshot. |
| `swarm-model/from-db` | Extract the projection input from a Fulcro database. |
| `swarm-model/dashboard-from-db` | `from-db` followed by `dashboard`. |

Route integration: mount `SwarmDashboardFromDb` (or `SwarmDashboard`) with
`(react/createElement swarm-ui/SwarmDashboardFromDb #js {:db db})` for both
`#/board` and `#/board/:swarm-id`; pass `:swarm/selected-id` (string key
`"swarm/selected-id"` on the JS props object) when a deep link should select a
swarm.  The selected row opens, carries `data-selected="true"`, and focuses its
summary.  The workbench board adapter also reads `#/swarm/:swarm-id` as a
selection alias; the router must map that alias to `:board` before the page is
mounted.  Use `SwarmDashboardFulcro` only when the host already
keeps the projected map at `:swarm/dashboard` and wants Fulcro query
composition.  A separate `hc.client.views.swarm` namespace is unnecessary; if a
route adapter wants one, it should be a two-line wrapper, not a second
implementation.

## Accepted snapshot paths

Canonical normalized tables:

| Path | Fields used |
|---|---|
| `:swarm/by-id` | `:swarm-id`/`:id`, `:slice-id`, `:worktree`/`:workspace`, `:status`, `:root-ready`, `:root-waiting`, `:root-done`, `:queued-count`, `:last-event`, `:master-version`; joined: `:report`, `:ff-commit`, `:rebase-rounds`; terminal: `:abandon-reason`, `:error`. |
| `:task/by-id` | `:task-id`/`:id`, `:swarm-id` or `:worktree`, `:parent-id`, `:child-ids`, `:ledger-key`, `:goal`, `:remaining-work`, `:done-when`, `:state`, `:wait-for`, `:needs-token`, `:owner-id`, `:claim-id`, `:handoff-note`, `:created-version`. |
| `:action/by-id` | `:action-id`/`:id`, `:swarm-id`, `:task-id`, `:agent-id` (also `:actor`/`:actor-id`), `:kind`, `:phase`, `:request-id`. |
| `:agent/by-id` or `:actor/by-id` | `:agent-id`/`:id`, `:swarm-id`, `:slot-id`, `:role`, `:life-state`, `:generation`, `:handoff-note`, optional `:death-reason`. |
| `:slot/by-id` | `:slot-id`/`:id`, `:swarm-id`, optional `:agent-id`, `:role`, `:life-state`, `:handoff-note`. |
| `:token-slot/by-id` | Keyed by swarm ID (or worktree). `:holder-id`, `:term-id`, `:state`, optional authoritative `:responsibility`, optional `:last-retirement`. |
| `:write-gate/by-id` | Keyed by swarm ID (or worktree). `:held?`, `:owner`, `:current-action` (action map or ID), `:queue` (action maps, IDs, or count), optional `:reported?`, and optional recovery evidence `:recovery-blocked?`, `:recovery-actions`, `:recovery-reasons`, `:recovery-write-gate`, `:recovery-acknowledgement`. |
| `:fleet/master-version` | Optional fleet-wide master version. |
| `:board/version` | Optional board revision. It is not a fleet master version and must never be labeled as one. |
| `:ui/selected-swarm-id` or `:swarm/selected-id` | Optional selected swarm. |

Recovery evidence is passed through and normalized.  `:recovery-write-gate`
keeps the old gate's `:held?`, `:owner`, `:queue`, `:outcome-unknown?`,
`:queue-abandoned?`, `:reason`, and `:recovered-at`.  An acknowledged barrier
keeps `:acknowledged?`, `:actor-id`, `:evidence`, `:write-gate-held?`, and
`:at-version`.  `write-gate-view` adds `:recovery-state` (`:blocked`,
`:acknowledged`, or `:none`) and `:recovery-reason-labels`, so hosts do not
invent their own reason wording.

A swarm may instead carry a nested `:board` map with `:tasks`, `:actors` (or
`:agents`), `:slots`, `:token-slot`, `:write-gate`, and `:actions`.  Nested
board data wins over global tables.  Global entities without a `:swarm-id` are
attributed to the only swarm; with several swarms they must name their swarm or
worktree.

IDs are normalized to non-blank strings.  A keyword ID loses its leading
colon (`:agent-1` becomes `"agent-1"`), so keyword IDs in tests and string IDs
on the wire address the same entity.

## Semantics the view must preserve

1. **Wait released is not done.**  A parent whose children are all done is
   shown as `:ready` with the text “Wait released … not automatically done”.
   Only an explicit `:done` state is shown as done.  No child count closes a
   parent.
2. **Waiting chains are concrete.**  `:wait-for` targets are resolved
   recursively.  Missing targets and cycles remain visible as marked nodes.
   A chain deeper than 32 nodes ends with a visible truncated node; it is not
   silently cut.
3. **Normal lifecycle is not a crash.**  `:attempt-lost`, holder retirement,
   slot recycling, and deliberate operator termination are described as normal
   or deliberate transitions.  Only unexpected failure reasons raise
   `:attention?`.
4. **Holder and W are separate.**  A token holder that owns waiting tasks or
   actions but is not running a shell is shown as holding the token, not W.
   W reports its current action and FIFO queue.  An omitted W report is shown
   as “not reported”, never as free; when W is unreported, queue depth and
   whether the holder owns W are unknown, so the view must not say “no writer
   is waiting” or “holder is not writing”.  A recovery-blocked W is shown as
   held for recovery with its unresolved action IDs and old-gate evidence,
   never as idle; an acknowledged barrier shows the persisted evidence and
   actor.  The view must not imply that new writes are admitted while the
   server reports `:recovery-blocked? true`.
   An explicitly reported empty `:responsibility` is authoritative and raises
   the inconsistency flag; an absent responsibility field is computed from the
   board and is not proof that the holder may retire.  A slot record that names
   an agent without an actor row is “actor state not reported”, not vacant.
5. **Board version is not master version.**  `:master-version` comes only from
   a fleet row.  A board-only row shows `:board/version` under a “Board
   version” label.  It never promotes the board revision to a fleet master
   version.
6. **Board-only snapshots are honest.**  When the server publishes task,
   action, or agent tables without a fleet row, the view creates one
   display-only row with `:fleet-reported? false`, status `:unknown`, and a
   “Fleet row not reported” note.  Root counts and the task tree come from the
   synchronized board; join queue, report, worktree, holder, and W remain
   explicitly unknown.  It does not hide the board behind an empty state and
   does not invent a running swarm.
7. **Reports are complete.**  `:report` is passed through byte-for-byte into a
   `<pre>` inside a focusable scroll region.  It is never truncated,
   summarized, or moved to an artifact.
8. **Counts are cross-checked.**  Computed root counts are compared with the
   declared `:root-*` fields.  A disagreement is shown as attention instead of
   silently picking one number.  When no task table has arrived but declared
   counts exist, the row shows the declared counts with a “declared” label and
   does not report a false mismatch against zero.
9. **No fabricated success.**  A joined swarm without report text says the
   server omitted it.  A failed or abandoned swarm keeps its reason visible.

## Accessibility and responsive contract

- Fleet rows and task nodes are native `<details>`/`<summary>`; Enter/Space
  toggles them, and focus order follows the visual order.
- The root is a labelled `<section>`; the summary is a `role="status"` live
  region with polite updates.
- Status is always written as text.  `hc-status--*` color is supplemental, not
  the only signal.
- A recovery barrier is a labelled `<section aria-label="Recovery write
  barrier">` with a heading, a reason list, the unresolved action IDs, the
  previous W evidence, and the persisted acknowledgement facts.  The section
  is present for both a blocked barrier and an acknowledged one; it is never
  reduced to color or a bare count.
- Every disclosure summary and the report scroll region has a focus-visible
  outline.  Interactive rows are at least 44 logical pixels high.
- Worktree paths and reports use `overflow-wrap: anywhere`; report `<pre>` is
  horizontally and vertically scrollable.
- CSS has a single-column layout below 480px, a two-column board layout at
  820px and above, `repeat(auto-fit, minmax(...))` fact grids, and a dark-mode
  palette with `prefers-color-scheme: dark`.
- No animation is required; reduced-motion users lose nothing.

## Dependencies and build

`package.json` at the repository root is the client package manifest.  It
declares `react`, `react-dom`, `framework7`, and `framework7-react`.  The
Fulcro dependency stays in `deps.edn` under `:client`; npm packages stay in
`package.json`.

The intended browser build is the shadow-cljs `:app` build.  For the focused
dashboard render test, a `:node-test` or `:node-script` build with
`["react" "react-dom/server" "framework7-react"]` must be able to resolve npm
modules.  The exact verification command used in this workspace is recorded
below.

## Tests

JVM projection tests (no npm required):

```bash
clojure -M:dev -e "(require 'hc.client.swarm-test) (clojure.test/run-tests 'hc.client.swarm-test)"
```

The projection tests pin `:recovery-state`, reason labels, old-gate evidence,
acknowledgement fields, string-key wire input, and the dashboard-level
consumer path, plus authoritative empty/absent responsibility sets, declared
counts without a task table, unacknowledged recovery evidence, wait-chain
truncation/cycles, and slot records whose actor state is missing.

CLJS render tests use the same fixture and render the real React/Framework7
tree with `react-dom/server`:

```bash
test/js/verify_swarm_ui.sh
```

The script compiles `hc.client.swarm.ui-test` with `cljs.main`, then
`test/js/run_swarm_ui_tests.cjs` injects one React/Framework7 instance and runs
the compiled test entry.  The shims in `test/js/` are verification-only: they
avoid `cljs.main` wrapping the CommonJS npm packages.  The production browser
build is the shadow-cljs `:app` build, which resolves npm modules directly.
The render tests include blocked and acknowledged recovery barriers and check
the reason text, unresolved action IDs, old-gate evidence, and acknowledgement
fields in the rendered markup.  They also check the `<section>` root, the
selected swarm's `open`/`data-selected`/focus state, and declared-count rows
without a board.

The smoke runner `test/hc/client/swarm/ui_smoke.cljs` prints FOUND/MISSING
checks and the complete HTML for inspection.  A run that only checks source
strings is not sufficient: the render tests call `renderToStaticMarkup` on the
actual `dashboard-element` and on the documented `SwarmDashboard` component.

## Limits

- The view is read-only.  It submits no `:board/command` or `:session/cancel`
  payload.  Selection and expansion are native DOM behavior; a deep link is a
  route prop.
- The server snapshot shape is a client contract, not a second authority.  If
  a field is absent, the view says so; it does not infer a terminal state.
- W is only as accurate as the `:write-gate/by-id` report.  If the server does
  not publish W, the view cannot distinguish “free” from “not observed”; it
  reports the queue and holder relation as unknown.
- `hc.server.app.snapshot/board-view` emits `:token-slot/by-id`,
  `:write-gate/by-id`, and `:slot/by-id` only when the caller supplies a real
  `:swarm-id` and, for W, a gate status.  `system/baseline` currently does not
  pass a gate status, so a live snapshot may still omit W; fleet rows remain
  absent until a real fleet adapter supplies them.  The view therefore shows a
  board-only row and marks the missing facts unreported.  It needs no client
  change when those tables appear.
- The component uses Framework7 React primitives but its own scoped CSS, so it
  does not depend on a particular Framework7 theme bundle to be readable.
- No virtualized rendering is attempted.  The board is expected to show the
  current worktree, not an unbounded history; large histories belong to the
  server's folded current state.
