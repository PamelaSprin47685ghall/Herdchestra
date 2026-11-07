# `hc.client.settings` — provider connections, model selection, and drafts

Authority: root `AGENTS.md` §7.4 旅程 1.2–1.3（引导与选模型）, the source-manual §6 rules,
`src/AGENTS.md`, and `src/hc/client/AGENTS.md`. Root wins on conflict.

## 1 Boundary

This package owns the settings workspace state and view. It does not open a
Sente channel, write storage, or call a provider. The caller supplies:

- the current server baseline as plain data;
- a `submit!` function for command envelopes;
- an effect callback for draft persistence and explicit secret copy.

The package has no global singleton and no component-local business state.
Closing or unmounting the page never clears a draft.

```text
connection.cljc   connection normalization, host/official labels, config digest
catalog.cljc      model rows, capabilities, prices, capacity, reasoning
draft.cljc        editable draft, secret rules, save receipt merge
commands.cljc     command envelope, outbox, receipt bookkeeping
model.cljc        state, reducer, effects, roles, test layers
view.cljc         pure hiccup tree with event descriptors
react.cljs        React adapter; SettingsPage / SettingsView
settings.cljs     public facade, atom dispatch, outbox flush
```

## 2 Component

`hc.client.settings/SettingsView` (alias `SettingsPage`) is a plain React
function component. Mount it under route `#/settings`:

```clojure
(require '[hc.client.settings :as settings])

(settings/settings-element state dispatch! transport-available?)
```

Props:

| prop | meaning |
|---|---|
| `:settings` | settings state map |
| `:dispatch!` | `(fn [action] ...)` |
| `:transport-available?` | optional; disables command buttons when false |

Subroutes dispatch `{:type :settings/select-section :section :providers}`,
`:models`, `:data`, or `:appearance`. The data section remains a placeholder;
the workbench mounts diagnostics and backup there. The appearance section is
implemented by `hc.client.appearance`; this package does not invent usage
values.

The providers section names each new-connection entry (`新建服务密钥连接`,
`使用账号登录`, `新建本地模型服务连接`) and states that every click opens
an independent draft. The editor heading names the target: a new draft says
`新建连接：<path>` and appends its display name once named; a saved draft
says `编辑连接：<name>`. The unsaved-draft list exposes new and orphaned
drafts that have no connection card, so a
second new-connection click cannot strand the first draft. A new draft shows
no inherited model chips and an empty manual-model input. An untouched new
draft with no typed model input and no active login offers
`撤销新建并返回`; otherwise the same control says `返回上一条草稿` and
keeps the new draft in the list. The list and editor footer show a compact
draft id so two unnamed drafts remain distinguishable.

The workbench mounts diagnostics and the backup workspace only under `:data`.
Their state lives in `:ui/diagnostics` and `:ui/backup`; leaving the data
center unmounts the panels but does not clear drafts or running commands.

## 3 State and snapshot paths

```clojure
{:server {:connections {"conn-a" connection}
          :models {"conn-a" [model ...]}
          :model-list-status {"conn-a" :listed}
          :roles {:main binding, :task binding, :utility binding,
                  :embedding binding, :vision binding}
          :session-model-snapshots {"s1" snapshot}}
 :drafts {"conn-a" draft "draft-old" draft}
 :role-drafts {:main binding}
 :ui {:open? true :section :providers :selected-draft-id "conn-a"
      :selected-role :main :selection {:connection-id "a" :model-id "m"}
      :revealed #{["conn-a" "key-1"]}
      :manual-model-inputs {"conn-a" "half-typed-model"}
      :draft-history ["draft-old"]
      :transport-available? true :message nil}
 :sync {:phase :synced :command-epoch 7 :server-revision 12}
 :commands {"cmd-1" command}
 :outbox [{:command-id "cmd-1" :command-epoch 7 :payload {...}}]
 :tests {"conn-a" {:status :pending :config-digest "v1-..." :layers {} }}
 :orphan-receipts []}
```

`apply-snapshot` reads `[:settings/by-id]`, `[:settings/models]`,
`[:settings/model-list-status]`, `[:settings/roles]`, `[:session/by-id]`, and
`[:sync]`; `[:server-revision]` identifies the confirmed Fulcro server copy.
Connections are replaced, not shallow-merged. Removed connections do not
resurrect; a draft for a removed connection becomes `:orphaned?` and remains
editable. No test is started by a snapshot. A snapshot whose
`:server-revision` is older than `[:sync :server-revision]` is ignored, so a
delayed baseline cannot restore removed connections or reset a newer local
section or role selection. The workbench bridge applies a projection against
the settings state that is current when the transaction runs; it must not
write a whole state captured before an operator click.

The server publishes `:credentials` as a vector of credential rows. The
connection normalizer accepts both that vector and a legacy map keyed by
credential id; ignoring the vector collapses a multi-key connection into one
synthetic key and can lose saved key metadata on the next edit. `client-safe`
strips `:secret` and `:secret-ref` from either shape. The server also strips
nested `:auth`, `:headers`, and `:options` secrets before snapshot and save;
the browser does not independently re-redact arbitrary imported JSON.

## 4 Draft rules

- A draft lives in `:drafts` keyed by `:draft-id`. Closing the page only sets
  `:ui :open?` to false.
- `:settings/new-connection` always creates a fresh draft; it never writes
  into the selected draft. A caller-supplied `:draft-id` is used only when
  it does not collide with an existing draft or connection id, so a stale
  action cannot overwrite unsaved input. The previous selection goes to
  `:ui :draft-history`, and `:settings/back-draft` restores it.
- Back discards the new draft only when it is still untouched
  (`:mode :new`, version 1, no UI-only model input, no active account-login
  flow). An edited new draft stays in `:drafts` and appears in the
  unsaved-draft list. No path silently drops a draft or its typed input.
- `:settings/select-draft` selects a draft from the unsaved-draft list.
  `model/draft-cards` returns new and orphaned drafts, which have no saved
  connection card; selecting a saved connection never hides them.
- The manual model input is keyed by draft in `:ui :manual-model-inputs`.
  A new draft starts with an empty input and empty `:manual-models` /
  `:enabled-models`; it never inherits chips or half-typed text from another
  connection.
- The connection editor exposes a `停用此连接` / `启用此连接` toggle
  independently of model rows. A launch-config connection may have no
  `:settings/models` rows yet; the operator can still disable it here. The
  draft digest includes `:enabled?`, so the toggle is dirty and saveable.
  Disabling a connection does not delete its models or stored keys.
  The button carries the next value (`:enabled? false` when disabling), not
  the current state. `touch` defaults a missing flag to enabled but keeps an
  explicit false, so a later draft edit cannot re-enable the connection.
- Every edit increments `:version`. `dirty?` compares the current config
  digest with `:base-digest`, so reverting a field becomes clean again.
  `status` treats a stored `:dirty` marker as stale when the digest matches
  the base; `:saving`, `:refused`, `:unknown`, and saved statuses survive.
- The config digest contains non-secret fields, auth-key revisions, and a
  per-secret edit token. It never contains a secret value.
- A saved secret is never returned by the server. The editor shows
  `已保存（不可回显）`; replacing or clearing it is explicit.
- An empty visible input does not clear a saved secret. Clearing requires the
  separate `:auth-state :clear` action.
- Removing a saved key from the draft adds its key ID to
  `:clear-credentials`; removing a key that was never saved sends no clear.
- A `:cloud-key` connection needs at least one enabled key with `:has-key?`
  or a non-blank `:auth-state :set` secret. `:local-service` and
  `:account-login` are exempt.
- The `:account-login` path uses the OAuth token stored by
  `hc.server.model-auth`. It does not use API-key rows and does not require an
  enabled key; the editor hides key and rotation controls for that path.
- A save receipt clears the draft only when the submitted draft version and
  digest still equal the current draft. A newer edit stays dirty and is
  rebased on the saved configuration.
- A receipt with a different config digest is refused into the draft and does
  not clear it.
- Test results are matched by `:config-digest`. A late result for an older
  digest goes to `:stale-results`; it never overwrites the current selection,
  fields, or test status.
- Test success does not save. Save does not start a test. Cancel test does not
  delete the form.
- A role/connection receipt with `:restart-required` is reported as saved but
  not yet effective; the UI must not claim the model chip or new session
  binding is live until the server applies the new config. `:session/select-model`
  refusals leave the session binding unchanged and keep the draft.
- The caller persists drafts through the `:persist-drafts` effect and may use
  `export-drafts`/`import-drafts`. The workbench bridge must persist the
  export envelope, never the raw draft map, and refuse a payload whose
  `secret-leak?` check still finds `:secret`/`:secret-ref`. If storage fails, dispatch
  `:settings/set-draft-storage-status` with `:error`; the view shows
  `草稿暂未保存` and never claims the draft is safe.

## 5 Commands

The outbox uses the canonical envelope:

```clojure
{:command-id "uuid"
 :command-epoch 7
 :payload {:kind :settings/save-connection ...}}
```

Kinds: `:settings/save-connection`, `:settings/test-connection`,
`:settings/cancel-test`, `:settings/list-models`, `:settings/save-role`,
`:settings/delete-connection`, and the account-login kinds
`:model-auth/providers|begin|poll|status|cancel|logout`.

The save/test payload is `connection/draft-payload`:

```clojure
{:draft-id "conn-a" :connection-id "conn-a" :draft-version 3
 :config-digest "v1-..."
 :fields {:name ... :base-url ... :service-kind ... :protocol ...
          :location ... :account-label ... :key-strategy ...
          :enabled? true}
 :keys [{:key-id "k1" :alias "main" :enabled? true :priority 1
         :auth-state :unchanged|:set|:clear
         :auth-edit-id "..."       ; no secret for :unchanged
         :secret "..."             ; only for :set
         :clear-secret? true}]     ; only for :clear
 :manual-models ["..."]
 :enabled-models ["..."]
 :credentials [{:key-id "k1" :alias "main" :enabled? true
                :priority 1 :secret "raw-once"}]
 :clear-credentials ["k2"]}
```

Each entry in `:fields :models` carries the effective `:capabilities` map.
Only explicit `true`/`false` declarations are sent; a missing key means
unknown.  A user-edited capability also carries
`:capability-sources {:tools :user}` and the prior value in
`:capability-baseline`.  These fields are display and provenance metadata:
the server session invariant accepts the boolean capability map, and the
model adapter accepts the provenance keys without treating them as provider
capabilities.

`:fields` is the server-compatible connection.  Raw secrets appear only in
`:credentials`; they are never written into `:fields`, drafts, snapshots, or
logs.  `redact-payload` and `commands/redact-secret-payload` replace them with
`***` before diagnostics or after the transport accepts the command.  The
server credential store returns per-key `:credential-id`, `:masked-key`,
`:alias`, `:enabled?`, `:priority`, `:use-count`, `:auth-failed?`, and
`:rate-limited-until`; `:secret-ref` and raw values stay server-side.  The
client maps that snapshot into auth-key rows with masked labels.

Saving an existing connection must not resend every stored secret.  The
server merges the stored connection and applies metadata-only key changes;
`draft-payload` sends `:clear-credentials` for keys removed from the draft.

`:settings/test-connection` uses the same payload.  The server merges the
stored connection, resolves a stored API key through the `SecretStore`, uses a
one-time submitted secret when the draft has one, or asks the live
`hc.server.model-auth` component for an OAuth access token.  A missing OAuth
account reports `:auth-ok :failed` without making an unauthenticated probe.
The resolved token and the test's use-count update are ephemeral.

The test is a four-layer server probe; the client never calls a provider and
never treats `:reachable`, `:auth-ok`, or `:models-listed` as proof that the
selected model can generate.  For an OpenAI-compatible connection with an
enabled model, the server sends one non-streaming request with the fixed text
`Herdchestra connection test. Reply with OK.`; it carries no user draft,
attachment, session history, or tool schema.  Only `:minimal-request-ok :ok`
produces the success conclusion.  A failed minimal request is reported as
`最小请求失败`, not as a list-only result.  If no enabled model is selected,
or the protocol has no probe, layer four stays `:not-run` with a reason.

Before either test button (`测试连接` on a card or `测试当前配置` in the
editor), the UI states that the test sends one fixed request, may incur a
small charge (`可能产生少量费用`), and does not carry the draft, attachments,
session history, or tool schema. It also shows the exact fixed text
`Herdchestra connection test. Reply with OK.` so the operator can judge the
cost before clicking. A no-probe connection keeps the no-side-effect
warning and shows no request text.

Deleting a connection also deletes its stored API-key secrets and, for
`:account-login`, logs out the model-auth credential.  The server refuses the
delete if that cleanup fails, so a saved credential is not silently orphaned.

Successful `:settings/save-connection`, `:settings/save-role`, and
`:settings/delete-connection` commands notify the publisher.  The publisher
projects the committed settings configuration, not the launch configuration
captured at startup, so the next baseline shows the saved connections and
roles without a restart.  Read-only list/test commands do not schedule a
publish.

`commands/queue` refuses unless the phase is `:synced` and
`:sync :command-epoch` is a positive integer. One logical target may have one
pending command. The caller flushes `:outbox`; a thrown transport error is
`:outcome-unknown`, never success. An unknown command receipt is kept in
`:orphan-receipts` and changes no draft.

`settings/dispatch!` applies a pure action to an atom, runs effects, and
flushes the outbox through `:submit!`. A `nil` submit result means the
transport is asynchronous; call `settings/apply-receipt` later. A Promise is
handled automatically.

## 6 Model rows

Rows are keyed by `[connection-id model-id]`. Same display names and same
model ids under different connections remain separate rows; every row shows
the connection source and actual host.

- Opening a saved connection seeds its draft from the authoritative
  `[:server :models connection-id]` rows, not only from fields embedded in
  the connection card.  The base digest is computed from that same seeded
  model list, so an untouched draft stays clean.  A legacy draft with no
  rows falls back to the server rows rather than hiding them.
- The server projects `:display-name` next to the legacy `:name`.
  `normalize-model` prefers `:display-name` and falls back to `:name` or the
  model id, so an older snapshot or config without the new field still
  renders its configured label.
- Capability badges cover vision, audio, video, document, tools, and
  reasoning. Every badge names its state in the visible text:
  `视觉：已声明支持`, `视觉：尚未确认`, or `视觉：不支持`. Missing values say
  `尚未确认`; a known value never renders as the bare label. The badges are
  separate pills, but `innerText` concatenates them without a separator, so
  a bare `工具` next to `推理：尚未确认` read as `工具推理：尚未确认`. The
  explicit suffix keeps the text unambiguous for operators, tests, and
  assistive technology. A disconnected connection does not hide badges.
- A selected connection draft exposes one discrete capability selector per
  model and capability (`尚未确认`, `声明支持`, `不支持`).  A change marks the
  capability `用户设置`, records the prior value as the baseline, and offers
  `恢复已知信息`.  A row without an open draft shows `编辑能力`, which opens
  the connection draft without leaving the models section.  The editor also
  offers `保存连接`, so a capability declaration does not require a trip back
  to the provider form.  A manual model
  starts with no declarations, so tools stay
  unknown until the operator declares them; saving a declaration is what
  lets a Plan or Reviewer send proceed.  A declaration equal to the baseline
  removes the override instead of making the draft dirty.  Refresh preserves
  user declarations over the provider's new list.
- A selected connection draft exposes a model-detail table with
  `值 / 来源 / 限制` columns for display name, model id, context capacity,
  capacity source, max output, temperature, top-p, input/output price,
  currency/unit, and reasoning. An empty numeric field means `未知` for
  capacity/price and `使用默认` for service-decided parameters; numeric `0`
  stays a real zero. Model parameters and identity are part of the config
  digest. A model-id rename moves the enabled/manual id lists; duplicate ids
  inside one connection are refused rather than merged.
- `未知` pricing is never rendered as `免费`. `免费` appears only for an
  explicit free declaration or when every declared price side is zero.
- Capacity shows `未知` or a known value with `用户设置`/`估算`/`服务返回`.
  The over-capacity warning says the numbers are estimates and keeps history.
- Reasoning shows a discrete level. A model that cannot disable reasoning
  shows `最低可用`, never `关闭`. Numeric `0` is distinct from `使用默认`.
- A test result is stale as soon as the draft digest differs from the tested
  digest; the panel keeps the old layers only as `配置已修改；旧结果仅参考`.
- Refresh preserves `:enabled?`, `:alias`, and `:notes`; a model missing from
  a successful listing is `此次列表未返回`, not deleted. An unavailable
  listing keeps the existing rows and allows manual model ids.
- Each connection group shows `已启用` or `已停用` even when it has no model
  rows. A disabled group with no rows names the editor path for re-enabling
  it; a refresh cannot restore a disabled connection.

## 7 Roles and round attribution

Roles are `:main`, `:task`, `:utility`, `:embedding`, and `:vision`.
`:main` serves Steward, Plan, and Reviewer; `:task` serves the Plan dedicated
worker and every Swarm worker/holder. The task role must be selected
explicitly; an unset task role is omitted from the saved role map so the
server adapter does not receive a mode-only `{:mode :unset}` binding. The
workbench assistants view reads the same role cards, so an unset task model is
visible as a configuration gap rather than a fake model. Utility follow-main
needs confirmation.
Embedding unset says `检索不可用` without blocking text chat. Vision follow-main
uses the main model's declared vision capability; explicit vision shows the
destination host. `catalog/round-switch-plan` returns `用于下一轮`; a running
reply keeps its original model attribution. The model row renders
`用于下一轮` and, while the session reports a live run,
`停止当前轮并切换`; the stop effect carries the session id. Stopping the current round is a
separate alternative, never an implicit switch.

First-run completion requires one saved connection and at least one enabled
chat model. A test is evidence, not a completion gate. The settings panel is
not the first-run entry; the conversation surface owns that guide.

## 8 Tests

Pure JVM tests:

```bash
clojure -M:test -d test/hc/client/settings
```

React/Node smoke. Use a small `node_modules` directory containing React 18
so the compiler does not index the whole client dependency tree:

```bash
mkdir -p /tmp/hc-settings-ui/node_modules
ln -sfn "$PWD/node_modules/react" /tmp/hc-settings-ui/node_modules/react
ln -sfn "$PWD/node_modules/react-dom" /tmp/hc-settings-ui/node_modules/react-dom

clojure -Sdeps '{:paths ["src" "resources" "test"]}' -M:cljs:client \
  -co '{:target :nodejs
        :output-to "target/settings-ui/main.cjs"
        :output-dir "target/settings-ui"
        :node-modules-dirs ["/tmp/hc-settings-ui/node_modules"]}' \
  -m hc.client.settings.ui-smoke
```

The smoke renders the real component with `react-dom/server` and asserts
same-name disambiguation, unknown capability/price labels, draft status,
receipt-gated draft clearing, a synchronous accepted receipt, onboarding,
appearance controls, the account-login panel, and the pre-click test-charge
disclosure with the fixed request text.

Release-build interop: `SettingsPage` receives custom React props as `#js`
string keys. Read them with `aget` or the `prop` helper; `.-camelCase`
access is renamed by advanced compilation while the string key is not, and a
`:none`/jsdom smoke cannot catch the mismatch. Mount `#/settings` in a
shadow release build before claiming the route works.

The pure suite includes the server-revision regressions in `model_test.clj`.
It also checks that the `:task` role is visible, that an unset `:main` or
`:task` binding is omitted from the saved role map, and that a concrete task
binding is sent as `{:connection ... :model ...}`. New-connection regressions
in `draft_test.clj`, `model_test.clj`, and `view_test.clj` assert that a
second click keeps the first draft and its typed model input, that back
restores an untouched new draft's predecessor, that an edited new draft
survives back, and that the view labels the new target without old model
chips. The same view suite checks that both test entry points disclose
`可能产生少量费用` and the fixed request text before the click.
It also asserts that a connection with no model rows still exposes the
`停用此连接` button, that the button sends the next `:enabled?` value so the
draft becomes dirty, that the disabled editor offers `启用此连接`, that
re-enabling restores the saved digest, and that the saved payload carries
`:enabled? false` without dropping its models. `integration_test.clj` also
checks that an unrelated edit does not re-enable a disabled connection.
New regressions cover base-url edits recomputing the actual host, cloud-key
secret requirements, model-detail zero-vs-default values and id renames,
unknown availability staying unknown, stale test digests, onboarding without
a test, storage-failure warnings, and the `停止当前轮并切换` action.
The shadow browser build is not run by this package because `shadow-cljs` is
not on the project deps classpath; workbench owns that build and previously
mounted `#/settings` in headless Chrome.

## 9 Non-goals

- Account login is delegated to `hc.client.model-auth`; this package stores
  its state at `[:ui :model-auth]`, queues its `:submit` effects, applies
  `receipt-action` receipts, and merges `connection-patch` into the draft.
  Tokens never enter settings state.
- No Sente, Fulcro, storage, HTTP, or provider client here.
- No second source of truth for server entities; this is a display copy.
- No fake local save or test success.
- No automatic chat, title generation, or paid request on save.
- No deletion of history, queue content, or drafts on a transient failure.
