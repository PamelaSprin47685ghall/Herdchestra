# `hc.client.browser` — managed-browser workbench surface

Authority: root `AGENTS.md` §7.4 旅程 6.4（开浏览器）, §6.4 and E2E 14; then `src/AGENTS.md`;
then `src/hc/client/AGENTS.md`. This file fixes the local client contract.

## 1 Boundary

The browser pane is a thin display of server current state. It never invents
a page, a tab, a takeover state or a completed action.

```text
hc.client.browser          facade: state, actions, React element
  ├── model                pure state, command outbox, receipt projection
  ├── view                 pure view tree
  └── react                controlled React adapter

hc.client.workbench.browser-bridge
  ├── transport/command!   submit browser command envelopes
  ├── sync-bridge          connection phase, command epoch, receipts
  └── mutations            set-browser-state
```

The module must not require `hc.server.*`. The bridge may require workbench
transport, sync bridge and mutations.

## 2 State

`:ui/browser` holds:

- `:permission`, `:tabs`, `:active-tab-id`, `:observation`, `:cards`,
  `:limits`, `:stopped?` from the latest confirmed `:browser/snapshot`;
- local address, selected node, type text, resume mode, tab filter and
  permission form;
- `:commands` and `:outbox` for envelopes not yet accepted by Sente.
- `:permission` keeps the server's `:scope`, `:sites`, `:private-read-approved?`
  and `:model-provider`; the form defaults to `:current-tab`.  The local
  initial `:private-read-approved?` is false; enabling the browser does not
  send `:private-read-approved? true`.  Only the explicit
  `:browser/approve-private-read` action sends true.

`apply-snapshot` preserves a local address draft unless the active tab
changes.  A snapshot without `:tabs` is ignored instead of clearing the pane.
`apply-receipt` projects a result only for an accepted receipt; a result with
`:error` or `:status :refused` is shown as a refusal even when the envelope
status is `:accepted`.

## 3 Commands

Every server-bound action uses the existing workbench command envelope:

```clojure
{:command-id ... :command-epoch ... :payload {:kind :browser/... ...}}
```

Kinds: `:browser/permission`, `:browser/snapshot`, `:browser/cards`,
`:browser/open-tab`, `:browser/navigate`, `:browser/back`,
`:browser/forward`, `:browser/reload`, `:browser/observe`, `:browser/act`,
`:browser/takeover`, `:browser/operator-input`, `:browser/resume`,
`:browser/close-tab`, `:browser/stop`. The server adapter maps the three
history kinds to the service's history port; a successful history result
clears the local observation and selected target because the page changed.

A local click only queues the command. `browser-bridge` marks it
`:submitting` when Sente accepts it. After an accepted receipt it requests one
`:browser/snapshot` when no snapshot request is already in flight. The bridge
also asks for one snapshot when the confirmed server revision moves, so page
cards produced by model tool calls appear without a manual refresh. The
server does not publish a revision for `:browser/snapshot` or
`:browser/cards`; that is what keeps the fetch from looping. A snapshot
receipt does not request another snapshot.

A command is queued only when the connection is synced and the active session
id is resolved. `:browser/snapshot`, `observe`, `permission`, `takeover`,
`resume` and `stop` are single-flight: a second command of the same kind is
refused while one is queued or submitting.  A refused or failed command stays
in the status list; it is not hidden by a later success. A failed snapshot sets a terminal
fetch block; only an explicit operator refresh or a session/connection reset
may retry it. Failed sends are marked `:failed` and are never retried
automatically.

## 4 Takeover and sensitive input

The pane reads takeover from the active tab. While `:operator`,
`:paused-manual` or `:sensitive-paused`, navigation and agent actions are
disabled in the UI and in `reduce-action`; the server refuses them too.
`:read-only` permits observe only.  Observe and agent actions additionally
require `:permission :enabled?` and an approved active tab
(`:agent-authorized?` or a matching `:site` scope); the pane shows the
per-tab “模型可读/未授权” state. Resume always sends `:browser/resume`; the server rereads the page and
returns a new observation stamped with the new generation. Old targets are
invalid. The pane reports a trusted operator gesture with
`:browser/operator-input`; the server-side CDP adapter also reports trusted
pointer/key/input events from the controlled document, suppressing its own
agent input. The pane itself never infers a human from a DOM mutation alone.
`close-tab` and `stop` are operator/workbench actions and are absent from the
model tool surface.

A node marked `:sensitive?` cannot be typed by the pane. The type control is
disabled and the pane says the operator must enter the value in the visible
page. The server independently refuses the same action.

## 5 Honest presentation

- Page cards show action, site, target, time and result in server order.
- A screenshot is labelled historical and loaded from `/blobs/<id>` when the
  server stored it; otherwise the card says it is unavailable.
- `:limits` is rendered verbatim; the pane does not hide unknown outcomes or
  iframe gaps.
- The address bar shows the server-reported URL and site, not a page title;
  Enter is ignored while agent navigation is not allowed.
- Tabs show authorization and loading state, can be filtered by title/URL,
  and a closed tab offers a real `:browser/reopen-tab` action.
- Page cards link back to their `:tab-id`; the header marks the pane as the
  instance browser.
- A refused or failed command stays visible in the command status; the pane
  never turns it into success.

## 6 Tests

`test/hc/client/browser_model_test.clj` covers snapshot projection, outbox
and receipt semantics, sensitive-field refusal, takeover gating and the view
tree. `test/js/verify_browser_ui.cjs` is the live Chromium verification: it
launches Chrome, a local HTTP page and the real app on dynamic ports, then
enables the browser, opens/observes, drives the managed tab directly to prove
trusted human input auto-pauses the agent, resumes and checks that the human
value survives, then selects an email field, types ordinary text, clicks,
takes over, resumes and checks the typed page state.
It uses the stable `#hc-browser-*` action IDs and fails loudly on any
missing step.
