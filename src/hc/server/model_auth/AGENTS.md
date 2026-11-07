# `hc.server.model-auth` — third-party model account login

Authority: root `AGENTS.md` §1.1, §7.4 旅程 1.2–1.3（引导与选模型）, and the login rules in
`src/hc/server/AGENTS.md`. Root wins on conflict.

## 1 Boundary

This package connects a model connection to a real third-party account. It
owns:

- the provider catalog and endpoint validation (`provider.clj`);
- OAuth 2.0 authorization-code + PKCE, device authorization, refresh,
  userinfo, and revocation (`oauth.clj`);
- the encrypted credential store (`store.clj`);
- pending flows, the loopback callback listener, the device poller, command
  handling, and public projections (`service.clj`);
- the application adapter (`app.clj`).

It does not own session history, model request retries, role selection, or the
settings workspace. It never receives a third-party password. A request that
contains `:password`, `:username`, `:secret`, `:token`, `:access-token`,
`:refresh-token`, `:client-secret`, or `:api-key` is refused with
`:MODEL_AUTH_PASSWORD_FORBIDDEN` before any network call.

## 2 Real providers and configuration

Built-in descriptors use real endpoints:

- `:google` — Google OAuth 2.0 for Gemini; authorization-code with loopback
  redirect and device authorization; default scope includes
  `https://www.googleapis.com/auth/generative-language.retriever`.
- `:microsoft` — Microsoft Entra ID for Azure OpenAI / Foundry; authorization
  code and device code; default scope includes
  `https://cognitiveservices.azure.com/.default`.
- `:oidc` — a registered OIDC/OAuth provider. It stays `:configured? false`
  until the deployment supplies endpoints and a client id.

A deployment registers its own OAuth client. Client ids and secrets come from
provider fields or the named environment variables:

```text
HC_MODEL_AUTH_GOOGLE_CLIENT_ID       HC_MODEL_AUTH_GOOGLE_CLIENT_SECRET
HC_MODEL_AUTH_MICROSOFT_CLIENT_ID    HC_MODEL_AUTH_MICROSOFT_CLIENT_SECRET
HC_MODEL_AUTH_OIDC_CLIENT_ID         HC_MODEL_AUTH_OIDC_CLIENT_SECRET
```

The provider map may override a built-in by id:

```clojure
{:model-auth
 {:providers {:google {:default-scopes ["openid" "email" "profile"
                                        "https://www.googleapis.com/auth/generative-language.retriever"]}}
  :callback-mode :loopback}}
```

Endpoints must use HTTPS. Loopback HTTP is accepted only with
`:allow-insecure-http? true`; tests use that switch for a local OAuth service.
Redirect URIs must use HTTPS or loopback HTTP.

## 3 Security invariants

1. **No passwords.** The browser sends only a provider id, flow type,
   connection id, scopes, and a replace flag. Password-like keys are rejected.
2. **Secrets stay server-side.** Access tokens, refresh tokens, device codes,
   PKCE verifiers, and client secrets never enter a receipt, snapshot, command
   payload, log line, or error body. `store/public-credential` is the only
   credential projection allowed to leave the server.
3. **State is one-shot and constant-time checked.** A callback must match the
   flow's random state. A wrong state does not complete or cancel the flow,
   and it does not close the loopback listener. Callback query keys use a
   bounded whitelist (`state`, `code`, `error`, `error_description`, and
   common provider extras); unknown keys are rejected without keywordizing
   them, and malformed percent escapes never throw.
4. **Provider identity is preserved.** A credential records `:provider-id`,
   `:issuer`, `:subject`, `:account-label`, `:scopes`, and the token
   timestamps. A flow cannot silently replace a connection linked to another
   provider or another subject. Re-login requires `:replace? true`.
5. **Issuer and audience are checked.** A token response is accepted only when
   the identity issuer matches the provider and the audience matches the
   client id. ID-token signatures are not verified; userinfo over TLS is the
   primary identity source, and ID-token claims are a hint.
6. **Storage fails closed.** Credentials are encrypted with AES-GCM. The key
   and ciphertext files are owner-only. If ciphertext exists without its key,
   the store refuses to open instead of creating a new empty key.
7. **No redirect following.** OAuth HTTP calls use `HttpClient$Redirect/NEVER`,
   bounded response bodies, and explicit timeouts.

## 4 Public API

```clojure
(require '[hc.server.model-auth.service :as auth])

(def svc (auth/start! {:data-dir "/var/lib/herdchestra"
                       :providers {:google {}}
                       :env (System/getenv)}))

(auth/handle! svc {:kind :model-auth/providers})
(auth/handle! svc {:kind :model-auth/begin
                   :connection-id "conn-1"
                   :provider-id :google
                   :flow-type :authorization-code})
(auth/handle! svc {:kind :model-auth/poll :flow-id "..."})
(auth/handle! svc {:kind :model-auth/status :connection-id "conn-1"})
(auth/handle! svc {:kind :model-auth/logout :connection-id "conn-1"})

(auth/access-token svc "conn-1")       ; server-side model request only
(auth/inject-access-tokens svc models) ; ephemeral model config
(auth/ring-handler svc)                ; external callback route
(auth/stop! svc)
```

Command receipts use the same `{:ok? true :receipt ...}` shape as settings.
`receipt` fields are safe: `:flow-id`, `:provider-id`, `:provider-name`,
`:flow-type`, `:status`, `:authorize-url`, `:user-code`, `:verification-uri`,
`:verification-uri-complete`, `:expires-at`, `:expires-at-ms`,
`:expires-in-seconds`, `:interval-seconds`, `:account`, and sanitized
`:error`/`:last-error`.

`inject-access-tokens` returns an ephemeral model configuration. Never persist
it. The durable connection keeps identity metadata only. An OAuth connection
is normalized to `:openai-compatible` with the provider base URL so the model
adapter can build it; a failed token resolution removes any stale `:api-key`
and marks `:auth-status :reauthorization-required`.

`hc.server.app.settings` also calls `inject-access-tokens` for
`:settings/test-connection` when the tested connection is `:account-login`.
That uses the same refresh and reauthorization path as a model request; the
resolved token is used for one probe and is not saved or logged. A missing
account reports `:MODEL_AUTH_NOT_CONNECTED` in the `:auth-ok` test layer
without sending an unauthenticated request.

Deleting an `:account-login` connection calls the model-auth logout path first.
The local encrypted credential is removed; remote revocation remains
best-effort and is reported only by that path. If model-auth is unavailable,
settings refuses the delete rather than leaving an orphaned account token.

## 5 Application wiring

`hc.server.model-auth.app` adapts an application config map:

```clojure
(require '[hc.server.model-auth.app :as model-auth-app])

(def model-auth (model-auth-app/start! config))
(model-auth-app/handle! model-auth payload)       ; route :model-auth/*
(model-auth-app/ring-handler model-auth)          ; mount external callback
(model-auth-app/inject-models model-auth models)  ; {:models ... :errors ...}
(model-auth-app/snapshot model-auth)              ; safe provider/account tables
(model-auth-app/stop! model-auth)
```

The application shell owns the route and lifecycle. In `:loopback` mode the
service opens its own `127.0.0.1` listener and no application route is
required. In `:external` mode pass the exact registered redirect URI and mount
the Ring handler before the gateway/static fallback. Mount it directly, not
behind the gateway's same-origin browser check: an OAuth callback is a
top-level cross-origin navigation and may carry the provider's `Referer`.
The one-shot random state bound to the flow is the callback's authorization
check.

## 6 Tests

The local OAuth test service implements authorization-code + PKCE, device
authorization, refresh, userinfo, and revocation over real loopback HTTP:

```bash
clojure -M:test:server -d test/hc/server/model_auth
clojure -M:test:server -n hc.client.model-auth-test
clojure -M:test:server -n hc.client.settings.integration-test
```

Covered: callback completion, device completion, external callback route,
state tampering, password refusal, provider/subject conflict, token refresh,
encrypted persistence, restart, file permissions, public redaction, and
token injection into an ephemeral model config. `app_system_integration_test`
starts the real `hc.server.app.system`, routes commands through its router,
completes both a loopback and an externally mounted callback, and checks that
the gateway baseline carries provider metadata.

## 7 Known limits

- A real provider needs a deployment-registered OAuth client. The repository
  ships no client id or secret.
- ID-token signatures are not verified. The token endpoint is reached over
  TLS and userinfo is preferred; this is documented rather than silently
  treated as full OIDC verification.
- Device-code polling is process-local. A restart cancels pending flows; saved
  credentials survive and refresh normally.
- Remote revocation is best-effort. A provider that rejects or lacks a
  revocation endpoint still loses the local credential; the receipt reports
  `:remote-revocation :failed` or `:unsupported`.
- Callback pages are `no-store` and contain only a result sentence and an
  error code. State, authorization code, user code, and tokens are never
  echoed into the response. Pending flow state and callback listeners are
  process-local; terminal flows are removed by the cleanup timer.
- The browser client never receives a token. Any feature that needs a token
  must ask this service on the server.
