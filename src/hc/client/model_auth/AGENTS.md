# `hc.client.model-auth` — account-login state and panel

Authority: root `AGENTS.md` §7.4 旅程 1.2–1.3（引导与选模型）, and `src/hc/client/settings/AGENTS.md`.
This module is pure browser state. It never holds a token, device code, or
third-party password.

## 1 Contract

`hc.client.model-auth/reduce` takes one action and returns:

```clojure
{:state next-state
 :effects [{:effect :submit :kind :model-auth/begin :payload {...}}
           {:effect :open-url :url "..."}
           {:effect :copy-text :text "..."}
           {:effect :schedule-poll :flow-id "..." :after-ms 2000}
           {:effect :cancel-poll :flow-id "..."}]}
```

The settings workspace maps `:submit` to `hc.client.settings.commands/queue`
and passes the other effects to the application effect handler.

`receipt-action` maps one gateway receipt to an action:

| command kind | action |
|---|---|
| `:model-auth/begin` | `:model-auth/begin-result` |
| `:model-auth/poll`, `:model-auth/status` | `:model-auth/status-result` |
| `:model-auth/providers` | `:model-auth/providers` |
| `:model-auth/cancel`, `:model-auth/logout` | `:model-auth/status-result` |

`connection-patch` returns nil until the state is `:authorized` or
`:connected`. Otherwise it returns the non-secret draft fields:

```clojure
{:service-kind :account-login
 :auth {:oauth {:provider-id ... :issuer ... :subject ...
                :account-label ... :connection-id ...}}
 :account-label "..."
 :auth-ref "conn-1"
 :base-url "https://..."}
```

It never returns `:access-token`, `:refresh-token`, `:device-code`,
`:client-secret`, or `:password`.

## 2 Actions

- `:model-auth/providers` — server provider list.
- `:model-auth/select-provider`, `:model-auth/select-flow-type`.
- `:model-auth/begin` — needs `:connection-id`; returns a begin command.
- `:model-auth/begin-result`, `:model-auth/status-result` — server receipts.
- `:model-auth/status`, `:model-auth/poll`, `:model-auth/cancel`,
  `:model-auth/logout`.
- `:model-auth/tick` — `{:now-ms n}` for local expiry display.
- `:model-auth/copy-code`, `:model-auth/reveal-account`,
  `:model-auth/dismiss-error`, `:model-auth/reset`.

The module schedules a poll while a flow is pending. A terminal status cancels
the poll. The server is still authoritative: a missed poll only delays the
display, not the credential.

Login state is scoped to one draft connection. Selecting another connection
resets the flow, account, and error; it never shows the previous account or
lets its logout button target the wrong connection. A snapshot with saved
accounts hydrates an idle panel whose connection ID matches one of them, so a
restart does not make an already-connected account look disconnected. A
logged-out receipt removes only that connection from the cached account list.

## 3 Panel

`login-panel` returns a hiccup-style tree with inline event descriptors:

```clojure
{:tag :button
 :attrs {:on-click {:action {:type :model-auth/begin
                             :connection-id "conn-1"
                             :provider-id :google}}}
 :children ["开始账号登录"]}
```

`settings/view.cljc` mounts it when the draft `:service-kind` is
`:account-login`. `hc.client.workbench.settings-bridge` handles `:open-url`,
`:schedule-poll`, and `:cancel-poll`, and forwards `:model-auth/providers` and
`:model-auth/accounts` from the server snapshot. The panel has no password or
username input. It shows the
provider name, flow choice, device code and verification URL, expiry, masked
account, full-account reveal, logout, and retry/cancel controls.

## 4 Tests

```bash
clojure -M:test:server -n hc.client.model-auth-test
clojure -M:test:server -n hc.client.settings.integration-test
```

Covered: begin effect shape, no secret fields in payloads, device-code panel,
receipt mapping, connection patch, expiry, masked account, and settings
integration through a real command outbox.
