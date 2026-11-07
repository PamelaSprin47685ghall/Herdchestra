# `hc.server.browser` — managed browser, observation and operator takeover

Authority: root `AGENTS.md` §7.4 旅程 6.4（开浏览器）, §6.4 and E2E 14; then `src/AGENTS.md`;
then `src/hc/server/AGENTS.md`. This file fixes the local contract only.

## 1 Boundary

The package is a server adapter. It owns browser pages, page cards, the
session permission scope and object-level takeover state. It does not own the
Owner, the model port, the workspace gate or the operator's Chrome process.

```text
hc.server.browser              facade: service, executor, launch/connect
  ├── model                    pure state, takeover, redaction, reports
  ├── port                     BrowserPort protocol
  ├── service                  navigation, observation, actions, takeover
  ├── executor                 Owner executor + model tool specs
  └── cdp                      Chrome DevTools Protocol adapter
```

The service requires only the `BrowserPort` protocol. Tests inject a fake
port; production injects `cdp`. The package must not require `hc.compose`,
`hc.client`, a storage engine or the model adapter.

## 2 Public API

```clojure
(require '[hc.server.browser :as browser])

(def port (browser/connect! {:endpoint "http://127.0.0.1:9222"}))
(def svc  (browser/service {:port port
                            :session-id "s1"
                            :publish! (fn [event] ...)
                            :screenshot-store (fn [bytes opts] ...)}))
(def exec (browser/executor svc))
```

`launch!` starts an isolated headless Chrome for tests and smoke checks. It
owns that process and stops it in `shutdown!`. `connect!` attaches to an
already-running Chrome and never stops it; `shutdown!` only releases this
adapter's WebSocket connections. `endpoint`/`connect-config!` resolve
`[:browser :endpoint]` or `HC_BROWSER_ENDPOINT`.

`command!` is the workbench command adapter for `:browser/*` payloads. It
preserves the concrete `:action` for `:browser/act`, returns the router shape
`{:ok? ... :receipt ...}`, and accepts operator actions. The model executor
is separate and refuses operator-only actions.

The executor request is `{:tool-name "browser_navigate" :arguments {...}}`
or `{:action :navigate ...}`. The executor returns the Owner contract:
`:succeeded`, `:failed`, `:cancelled` or `:outcome-unknown`, with `:payload`
and a structured `:error`.

## 3 Object-level takeover

Each page has one takeover state:

| State | Read | Act | Entry |
|---|---|---|---|
| `:agent-active` | yes | yes | page opened; explicit resume |
| `:read-only` | yes | no | resume with `:mode :read-only` |
| `:paused-manual` | no | no | operator input/click/navigation detected |
| `:operator` | no | no | explicit takeover |
| `:sensitive-paused` | no | no | agent reached a sensitive field/form |

Rules:

1. Operator input pauses the page immediately. The workbench reports a
   trusted operator gesture with `operator-input!`, and the CDP adapter also
   reports trusted `pointerdown`/`keydown`/`input`/`click`/`wheel` events from
   the controlled document through a runtime binding. The hook, its
   suppression flag and the binding live in an isolated CDP world
   (`hc-operator`), never in the page's main world. A page script cannot set
   `__hcAgentInput`, overwrite `__hcOperatorInput` or remove the listeners.
   The adapter suppresses only its own dispatch by setting the isolated-world
   flag immediately before `Input.dispatch*` and clearing it in the same
   `finally`; there is no settle window. Focus, blur, a closed popup or a
   short silence never resumes the page.
2. Resume is explicit. The service reads the current page first and returns
   that observation stamped with the new page generation. The old
   observation and every target from it become invalid even when the URL
   looks unchanged. A failed reread leaves the page paused; it never resumes
   on a partial observation. If coverage is uncovered or detection is
   unavailable, an `:agent` resume is downgraded to `:read-only`; the result
   carries `:downgraded? true` and the UI must not claim agent control.
3. A page that keeps changing after repeated rereads hands control to the
   operator (`:max-rereads`, default 2). The service never clicks a
   name-similar element.
4. Stop does not close pages, clear unsent form content or erase cards.
   Closing a page is separate and also keeps the cards.
5. Permission is object-scoped: one page can be under operator control while
   another remains agent-active.
6. Operator-input detection is fail-closed. If the isolated world, binding,
   hook, context or suppression clear cannot be established, `act!` refuses
   before dispatching, returns `:operator-input-unprotected`, and the page is
   paused. `subscribe-operator-input!` returns `{:ok? true :unsubscribe f}`
   only when detection is available; otherwise the service records
   `:operator-input-detection :unavailable` and refuses agent actions.
7. Frame coverage is fail-closed. `Page.getFrameTree` must be readable and
   have no child frames; any iframe (including a cross-origin/OOPIF iframe)
   or an unknown frame tree makes `:operator-input-coverage :uncovered`.
   Observations report `:child-frames`, `:frame-tree-known?`,
   `:operator-input-coverage` and `:operator-input-uncovered`; `act!`,
   `navigate!` and `history!` refuse with `:operator-input-uncovered` and do
   not dispatch. The adapter re-checks the frame tree immediately before every
   action, so a frame added after observation is also refused. This is
   deliberately conservative until per-frame isolated-world monitoring exists;
   it never claims frame coverage that is not verified.

## 4 Sensitive-input boundary

`model/sensitive-target?` classifies a field from `:type`, `:autocomplete`,
`:field-kind`, name, id, label and placeholder. The classifier is
conservative and best-effort.

- The agent may type only ordinary text.
- A `:type` action into a sensitive field is refused with
  `:SENSITIVE_INPUT_REQUIRES_OPERATOR`; the page enters
  `:sensitive-paused` and the operator types in the visible page.
- A `:submit` action for a form containing password, one-time-code or payment
  fields is refused for the same reason.
- Page cards store the target description and the text length, never the
  typed value. Sensitive actions keep no text preview.
- The product does not claim to recognize every sensitive value. That limit
  appears in every observation and report.

## 5 Observation and page cards

`observe!` reads the visible top document: URL, title, visible text, and up
to 200 interactive nodes with a semantic `data-hc-ref`. It never reads field
values. Cross-origin iframes are not read; `:iframes`, `:child-frames`,
`:frame-tree-known?` and `:operator-input-coverage` are reported. Any frame
or unknown frame tree yields `:operator-input-coverage :uncovered` and
`:operator-input-uncovered`; agent actions are refused rather than claiming
the page is protected.

Each observation carries an `:observation-id` and a `:generation`. An action
must name the observation it was planned from. The port re-inspects the
current DOM immediately before acting and compares the document identity.
Stale targets are refused; the caller must observe again.

Every navigation, observation, action and refusal appends one ordered page
card with action, site, target summary, time and result. A screenshot is
always marked `:kind :historical` and `:live? false`. The application must
store screenshots through `ref-screenshot-store` (or add an equivalent blob
reference); an unreferenced screenshot is reclaimed by the blob cleaner.
Cards are bounded
(`:max-cards`, default 200); the report keeps the dropped count and never
claims completeness.

## 6 Permission scope

The browser tool is disabled by default.

- `:scope :current-tab` authorizes one tab.
- `:scope :session-tabs` authorizes all session tabs.
- `:scope :site` authorizes tabs whose host is in `:sites`.
- Navigation to a host outside `:sites` is refused before any request is
  sent. The operator must extend the permission.
- Private-looking pages (login form or sensitive field) require
  `:private-read-approved?`. It defaults to false; enabling the browser no
  longer grants it implicitly. The operator must call `approve-private-read!`
  or send an explicit `:private-read-approved? true`. Changing
  `:model-provider` clears that approval.
- Permission is ephemeral. It is not persisted, not inherited after restart,
  branching or migration, and not implied by a retained site login.

## 7 Error taxonomy and honest limits

Error codes include `:connection-failed`, `:certificate-error`,
`:login-required`, `:not-found`, `:access-denied`, `:unreadable-content`,
`:blocked-by-page`, `:stale-target`, `:ambiguous-target`,
`:sensitive-input-requires-operator`, `:takeover-active`, `:read-only`,
`:permission-required`, `:site-not-approved`, `:tab-closed`,
`:private-read-confirmation-required`, `:outcome-unknown` and
`:navigation-timeout`, `:operator-input-uncovered` and
`:operator-input-unprotected`. Every error carries `:next-step`,
`:retryable?` and `:requires-operator?`.

Hard limits, reported by `model/default-limits`:

- screenshots are historical, not live;
- sensitive-field detection is best-effort;
- observation covers the visible top document only;
- iframes are not read;
- trusted human-input detection is an isolated-world, top-document hook; any
  child frame or unknown frame tree makes coverage uncovered and agent
  actions are refused;
- if the isolated world, binding, hook or suppression clear is unavailable,
  agent actions are refused rather than claiming detection;
- input coordinates are resolved against the current DOM, not cached;
- `scroll` and targetless `press` use page identity; a targetless Enter on a
  sensitive form is refused;
- clicking a submit button inside a sensitive form is refused;
- a connected external browser is not controlled beyond its CDP endpoint;
- browser permission is ephemeral;
- an unknown outcome is never retried automatically;
- page content may contain personal data.

`report-text` repeats the applicable limits in plain language.

## 8 Tests

`test/hc/server/browser_test.clj` covers the pure model, the fake-port
service, the executor, the workbench command adapter and health. The CDP
smoke test starts a local HTTP server and an isolated headless Chrome, then
exercises real navigation, observation, sensitive refusal, explicit takeover,
resume reread, page-state preservation, HTTP error classification, a page
that tries to overwrite the main-world detection globals, a cross-origin
iframe page that must fail closed, and tab close.
`test/hc/server/browser_app_integration_test.clj` starts the real
`hc.server.app.system` with `[:browser :endpoint]`, verifies the session
browser service and drives the app command adapter through open/observe/type,
takeover, resume and snapshot/cards/health. Both real tests are skipped when
no Chrome executable exists; they are not replaced by a mock when Chrome is
available.

`test/js/verify_browser_ui.cjs` drives the real workbench and a real managed
page through Playwright. Its page deliberately sets the main-world
`__hcAgentInput` and `__hcOperatorInput` globals before the human click; the
test must still observe `:paused-manual`. This is the regression that proves
the isolated-world hook is not page-writable.

Run:

```bash
clojure -M:test:server -n hc.server.browser-test
clojure -M:test:server -n hc.server.browser-cdp-smoke-test
clojure -M:test:server -n hc.server.browser-app-integration-test
node test/js/verify_browser_ui.cjs
```

## 9 Integration notes

`hc.server.app.system` can add a `:browser` executor per session:

```clojure
(let [port (browser/connect! {:endpoint endpoint})
      svc  (browser/service {:port port :session-id session-id
                             :publish! publish-fn
                             :screenshot-store store-fn})]
  (assoc executors :browser (browser/executor svc)))
```

The Owner currently admits `:tool`, `:shell` and `:workspace` call kinds.
Two integration seams are provided:

- add `:browser` to the Owner allow-list and use `(browser/executor svc)`;
- keep the `:tool` kind and use
  `(browser/routing-executor (browser/executor svc) program-tool-executor)`.
  It sends payloads with `:tool-name "browser_*"`, `:executor :browser` or
  `:tool :browser` to the browser and delegates everything else.

`browser/tool-specs` returns the 13 model-visible JSON-schema definitions.
`permission`, `takeover`, `resume`, `operator-input`, `close-tab` and `stop`
are operator-only: the default executor refuses them with `:operator-only`.
A workbench executor may opt in with `{:allow-operator-actions? true}`. The
browser package does not change the Owner; composition decides.
