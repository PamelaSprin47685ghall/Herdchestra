# Server mini-app generation (0.0.1)

Scope: close the server side of the client's “生成小应用” entry. The client
already POSTs `/api/workspace/miniapp-generate`; this change adds the real
route, a low-level model request, a protected workspace write, durable
registration, and tests. No client, compose, materials, history, or scheduler
file is edited here.

## 1. Interface

### POST `/api/workspace/miniapp-generate`

Request (JSON, `application/json`; EDN response):

```clojure
{:prompt "描述要生成的小应用"        ; required, non-blank, <= 20000 chars
 :filename "reading-log"             ; required, safe leaf, .html appended
 :workspace-root "/abs/workspace"}   ; optional; must equal the server root
```

Success:

```clojure
{:ok? true
 :status :ok
 :app {:app-id "app-<uuid>"
       :name "reading-log.html"
       :path "miniapps/reading-log.html"
       :created-at "2026-11-05T…Z"
       :storage-namespace "miniapp:app-<uuid>"
       :workspace-root "/abs/workspace"
       :status :available
       :data-version 1
       :source-version 1
       :data-bytes 0
       :network-allowed []
       :grants []}}
```

Failure (HTTP status in brackets):

```clojure
{:ok? false :status :error
 :error {:code :INVALID_FILENAME|:INVALID_PROMPT|:INVALID_JSON|…
         :message "可读说明"
         :details {…}}}
```

### GET `/api/workspace/miniapps`

Returns the same app records for the configured workspace:

```clojure
{:ok? true :status :ok :apps [app …]}
```

This is a read-only registration view. The existing client list still reads
IndexedDB; `client-audit` consumes the POST `:app` record and writes it to the
`apps` store. The GET route is available for a later refresh merge.

## 2. Generation

- One `hc.server.model-port.contract/ModelPort` attempt, never `AiServices`.
- Snapshot: `:role :task`, one system message with the self-contained HTML
  rules, one user message with the prompt, `:max-tokens 1024`, `:stream? false`.
- The receiver accepts only `:completed`, `:failed`, and `:cancelled`; the
  default attempt deadline is 300 s.  The launch connection's `:timeout-ms`
  raises it when larger (`max(300 s, connection timeout)`).  A request
  `:timeout-ms` overrides it (1..600000, capped at 600 s); a
  non-positive/non-integer value is `:INVALID_TIMEOUT`.  On expiry the attempt is cancelled and the receipt is
  `:MODEL_TIMEOUT` with `:error {:kind :timeout :remedy :retry}`.  A provider
  failure is `:MODEL_FAILED` with `:kind :model-failed` and `:remedy :retry`.
  A completed event with `:finish-reason :length` is `:OUTPUT_TRUNCATED`,
  never a partial file.
- The live `model-port-ref` atom is read per request, so a settings save
  reaches the next generation without a restart.

## 3. Output validation

`hc.server.miniapps/validate-html` enforces:

1. Markdown fences are stripped; prose before `<html>` is ignored.
2. A complete `<html>…</html>` document is required. A missing start or end
   marker is `:INVALID_HTML`.
3. A missing `<!DOCTYPE html>` is prepended; an existing one is kept.
4. UTF-8 size is at most 2 MiB (`:OUTPUT_TOO_LARGE`).
5. Any `<script src=…>` is rejected (`:EXTERNAL_RESOURCE`); inline scripts are
   allowed.
6. Any external `<form action=…>` or `<button|input formaction=…>` is rejected
   (`:EXTERNAL_RESOURCE`). `http://`, `https://`, `//`, and other URI schemes
   are external; relative targets are allowed.
7. Output is otherwise written unchanged.

## 4. Protected write

`miniapps/<safe-name>.html` only. The service:

1. Rejects a filename with `/`, `\`, `..`, NUL, blank, or a leading dot;
   strips control/reserved punctuation and appends `.html`.
2. Rejects an optional `:workspace-root` that is not the server root, and a
   `miniapps/` symlink that resolves outside the workspace.
3. Refuses an existing target before calling the model.
4. Builds a `hc.core.workspace-gate` session, observes `miniapps/` and the
   target as missing, stages `mkdir` when needed, writes the full HTML, runs
   `preview`, and commits with `:actor-id`/`:claim-id` through the shared
   write gate.
5. The batch records the target as missing, so a file created after the
   observation is a `:STRUCTURE_CONFLICT`/`:FILE_CONFLICT` (mapped to
   `:APP_EXISTS`), never a replacement. The original file is preserved.
6. A `miniapps/` directory race retries once with a fresh batch.
7. `:HARD_FAILURE` is reported as `:WRITE_UNCERTAIN`; no claim of atomicity is
   made beyond the gate's documented contract.

### 4.1 Per-app storage injection

`hc.server.miniapps/inject-storage` is applied to every generated mini-app
document before the protected write.  It inserts one
`<script data-hc-miniapp-storage="<app-id>">` immediately after `<head>`:

- `window.miniappAppId` is the registered app ID.
- `window.miniappStorage` exposes `get`, `set`, `remove`, and `clear`.
- `window.localStorage` is replaced by a namespaced view.  Every key is
  stored as `miniapp:<app-id>:<key>`; `clear` removes only keys with that
  prefix, and `length`/`key` expose only the current app's keys.
- If the client sandbox bridge is present, it runs first (the client inserts
  it at the start of `<head>`), so the wrapper delegates to the bridge and the
  prefixed keys are persisted in that app's IndexedDB namespace.
- A marker makes injection idempotent.  A document that already carries the
  marker is returned unchanged.
- Injection is called only from the generation path.  Ordinary workspace
  files and the file browser never pass through it.  A manually added
  mini-app keeps the client sandbox's per-app IndexedDB isolation but does
  not receive the server prefix script.

## 5. Registration

- Record keyed by `app-id`, with `name`, `path`, `created-at`,
  `workspace-root`, and `storage-namespace` (`miniapp:<app-id>`).
- Persisted atomically at `<data-dir>/miniapps/registry.edn` (temp file +
  `ATOMIC_MOVE`, fallback `REPLACE_EXISTING`), and held in memory for the
  process. A corrupt registry reads as empty rather than blocking startup.
- If the registry write fails, the just-created HTML is removed through a
  second protected commit and the response is `:REGISTRY_FAILED` with
  `:file-removed?` in the details.

## 6. Error mapping

| Code | HTTP | Meaning |
|---|---:|---|
| `:INVALID_PROMPT`, `:INVALID_FILENAME`, `:INVALID_JSON`, `:INVALID_BODY`, `:INVALID_TIMEOUT` | 400 | request is not usable |
| `:WORKSPACE_MISMATCH`, `:PATH_ESCAPE` | 403 | root or path is outside the configured workspace |
| `:APP_EXISTS`, `:NOT_A_DIRECTORY` | 409 | name already used / `miniapps` is a file |
| `:OUTPUT_TOO_LARGE`, `:BODY_TOO_LARGE` | 413 | 2 MiB output / 64 KiB request |
| `:UNSUPPORTED_MEDIA_TYPE` | 415 | not JSON |
| `:INVALID_HTML`, `:EXTERNAL_RESOURCE`, `:OUTPUT_TRUNCATED` | 422 | model output fails validation or hit the token limit |
| `:MODEL_FAILED`, `:MODEL_EMPTY` | 502 | provider or transport failure; `:kind :model-failed`, `:remedy :retry` |
| `:MODEL_NOT_CONFIGURED` | 503 | no model port |
| `:MODEL_TIMEOUT` | 504 | attempt cancelled after the deadline; `:kind :timeout`, `:remedy :retry` |
| `:WRITE_FAILED`, `:WRITE_UNCERTAIN`, `:REGISTRY_FAILED`, `:INTERNAL_ERROR` | 500 | structured internal failure; no bare exception |

## 7. Tests

`test/hc/server/miniapps_test.clj` — 16 tests, 127 assertions, all pass:

1. Success writes one HTML file, returns the app record, lists it, survives a
   new service instance, and serves the HTTP route.
2. Non-HTML, external script, external form, and fenced-valid output.
3. Filename traversal, path separator, blank name, foreign workspace root,
   and `miniapps` symlink escape.
4. Existing file refused before the model call; a file created by the model
   call is a conflict and keeps its bytes.
5. Unconfigured model, generator failure, port `:failed`, and port timeout all
   leave no file; timeout cancels the handle.
6. The low-level port path freezes one `:task` request with 1024 max tokens.
7. A registry write failure removes the new file.
8. A declared `herdchestra-data-version` and the client-compatible source digest are mirrored.
9. A `:finish-reason :length` port result is `:OUTPUT_TRUNCATED`, not a file.
10. An output over 2 MiB is `:OUTPUT_TOO_LARGE`, not a file.
11. Timeout resolution: 300 s default, request override, 600 s cap, invalid values.
12. The service default deadline is 300 s; a larger connection timeout
    raises it, and both are capped at 600 s.
13. A request `:timeout-ms` cancels the port and returns `:kind :timeout` /
    `:remedy :retry`; the HTTP route returns 504 with the same fields.
14. Injection is idempotent, namespaced per app, and absent from validated
    non-mini-app HTML.
15. A Node probe runs the injected script in two fake windows sharing one
    native storage: prefixes differ, each app reads its own record, and
    `clear` on one leaves the other intact.
16. Wrong HTTP methods, unknown paths, and invalid JSON.

Run:

```bash
clojure -M:test:server -n hc.server.miniapps-test
```

Result: `Ran 16 tests containing 127 assertions. 0 failures, 0 errors.`

## 8. Real run

App: isolated data `/tmp/hc-miniapp/data`, workspace
`/tmp/hc-miniapp/work`, local Ollama `http://127.0.0.1:11434/v1`,
`qwen3.6:27b`, `:options {:reasoning-effort "none"}`, max tokens 1024.
Evidence directory: `/tmp/hc-miniapp/`.

- Health: `/tmp/hc-miniapp/healthz.edn` (`:store {:open? true}`).
- Request: `/tmp/hc-miniapp/request.json`.
- Response: `/tmp/hc-miniapp/response.edn`.
- Timing: `/tmp/hc-miniapp/curl.meta`.
- Assertions: `/tmp/hc-miniapp/assertions.edn`,
  `/tmp/hc-miniapp/assertions-negative.edn`, `/tmp/hc-miniapp/real-run.edn`.
- Durable registry: `/tmp/hc-miniapp/data/miniapps/registry.edn`; restart
  view `/tmp/hc-miniapp/list-after-restart.edn`.
- Logs: `/tmp/hc-miniapp/app3.log`, `/tmp/hc-miniapp/app4.log`,
  `/tmp/hc-miniapp/tests.log`.
- File, registry, and list assertions: see §8.1.

### 8.1 Result

Real app `http://127.0.0.1:35109/`, isolated data/workspace, local Ollama
`qwen3.6:27b` with `:options {:reasoning-effort "none"}` and
`max_tokens 1024`.

**Positive call** (`request.json` → `response.edn`, `curl.meta`):

- HTTP 200 in 82.88 s.
- `:app-id app-751b1b30-fdc6-4028-832d-f7d2f95fcee6`.
- Path `miniapps/reading-log.html`; 2480 UTF-8 bytes.
- SHA-256 `a54e585155390ce9cb902e02dfaa70cb4e021182485a6afab85e4476a8609ab0`.
- `assertions.edn`: `:doctype? true`, `:html-open? true`,
  `:html-close? true`, `:under-2mib? true`,
  `:no-external-script? true`, `:no-external-form? true`,
  `:registered? true`, `:listed? true`.
- `list.edn` and `registry.edn` both contain the same app ID; the registry is
  the durable record at `/tmp/hc-miniapp/data/miniapps/registry.edn`.
- The workspace contains exactly one file: `miniapps/reading-log.html`.
- After a full app restart, `GET /api/workspace/miniapps` returned the same
  app ID (`list-after-restart.edn`: `:restart-list-ok? true`, count 1), so the
  EDN registry is durable across processes.

**Negative call** (`request-negative.json` → `response-negative.edn`,
`curl-negative.meta`):

- Prompt asked for plain text and no HTML tags.
- HTTP 422 in 31.44 s; `:code :INVALID_HTML`; preview
  `"这不是一个 HTML 文档。"`.
- `assertions-negative.edn`: `:file-exists? false`,
  `:registered-count 1` (the positive app only).

**Size limit**: the positive file is 2480 bytes; the unit test
`an-output-over-two-mib-is-rejected-without-a-file` feeds a valid document
over 2 MiB and gets `:OUTPUT_TOO_LARGE` with no file.

**First attempt (historical)**: before the connection timeout was set, the
provider returned `request timed out` at 60 s. No file was left. The rerun
set `:timeout-ms 300000` on the connection; the service deadline is 300 s
(600 s maximum, request override allowed).

### 8.2 Timeout rerun (300 s default)

The same app route was rerun with the new default deadline and a fresh
data/workspace.  The provider connection carried `:timeout-ms 300000`.

- Request `{"prompt":"做一个记录读书条目的小应用，含输入框与列表，数据存本地","filename":"reading-log"}`.
- HTTP 200 in **218.82 s** (the old 180 s service deadline would have
  cancelled this attempt); response has `:ok? true`.
- File `miniapps/reading-log.html`, 2582 bytes.
- Evidence: `/tmp/hc-miniapp-timeout/request.json`,
  `/tmp/hc-miniapp-timeout/response.edn`, `/tmp/hc-miniapp-timeout/curl.meta`,
  `/tmp/hc-miniapp-timeout/work/miniapps/reading-log.html`,
  `/tmp/hc-miniapp-timeout/app.log`.

A provider-level timeout is now mapped to `:MODEL_TIMEOUT` with
`:error {:kind :timeout :remedy :retry}`; the route no longer reports it as a
generic `:MODEL_FAILED`.  The provider connection's own client timeout still
must be at least the desired attempt deadline; a connection left at the
LangChain4j 60 s default will time out at 60 s regardless of this service
deadline.

### 8.3 Storage injection rerun (two apps)

A fresh app (`http://127.0.0.1:46257/`) with a fresh data/workspace generated
two apps through the real route.  The provider connection used
`:timeout-ms 300000` and `:options {:reasoning-effort "none"}`.

| App | HTTP | Time | File | Bytes |
|---|---:|---:|---|---:|
| `reading-a` | 200 | 292.80 s | `miniapps/reading-a.html` | 4535 |
| `reading-b` | 200 | 79.06 s | `miniapps/reading-b.html` | 5453 |

The 292.80 s generation also confirms the 300 s default: the old 180 s
deadline would have cancelled it.

Injection checks (`/tmp/hc-miniapp-storage/evidence/injection-check.edn`):

- Both files carry `data-hc-miniapp-storage` with their own app ID.
- Both contain `window.miniappStorage` and `miniapp:<app-id>:`.
- Injecting each file a second time returns the same bytes (idempotent).
- `normal.html` has no marker and no `window.miniappAppId`.

Browser isolation (Chrome, same origin static server, screenshots and JSON in
`/tmp/hc-miniapp-storage/evidence/`):

- App A `app-31273611-3ef1-437b-adff-8e059804a3dc`, prefix
  `miniapp:app-31273611-...:`; app B `app-59bdc5b5-005c-433a-b3e3-fc444d172f40`,
  prefix `miniapp:app-59bdc5b5-...:`.
- A wrote one record `A书`; B wrote one record `B书`.
- A's storage contained only `A书`; B's contained only `B书`; neither saw the
  other's value.
- `localStorage.clear()` on A left B's record and `probe` value intact.
- `window.miniappStorage.set/get` shared the same prefix as the wrapped
  `localStorage`.
- `normal.html` had no `window.miniappAppId`, no injected marker, and no
  unprefixed mini-app keys.

Evidence:

- `/tmp/hc-miniapp-storage/response-reading-a.edn`,
  `/tmp/hc-miniapp-storage/response-reading-b.edn`,
  `/tmp/hc-miniapp-storage/curl-reading-a.meta`,
  `/tmp/hc-miniapp-storage/curl-reading-b.meta`.
- `/tmp/hc-miniapp-storage/work/miniapps/reading-a.html`,
  `reading-b.html`, `normal.html`.
- `/tmp/hc-miniapp-storage/evidence/storage-isolation.json`,
  `storage-isolation.js`, `storage-a-before-clear.png`,
  `storage-b-before-clear.png`, `storage-a-after-clear.png`,
  `storage-b-after-clear.png`, `injection-check.edn`,
  `node-probe.json`, `tests.log`.
- `/tmp/hc-miniapp-storage/app.log`, `/tmp/hc-miniapp-storage/list.edn`,
  `/tmp/hc-miniapp-storage/data/miniapps/registry.edn`.

Note: the direct static page shares the origin, so its native storage contains
the prefixed keys (the normal page's own keys are unaffected).  In the client,
the injected script runs inside the `allow-scripts` iframe and wraps the
client bridge; the bridge persists to that app's IndexedDB namespace, so
normal workbench pages do not receive mini-app localStorage keys.

### 8.4 UI E2E 13 (real Chrome, 2026-11-05)

The client bundle loaded without page errors (`curl` 200; Playwright topbar
present; `pageerror` count 0).  The run used a fresh app/data/workspace and
the real Chrome UI, not curl for generation.

- Material page → “小应用” → “生成小应用”.
- A prompt `记录读书条目：书名输入框和列表，数据存本地` → app
  `app-4aa9d520-0a8a-4b5e-ae45-e8d7d7894b4f`,
  `miniapps/reading-ui-a.html`.
- B prompt `记录观影条目：电影名输入框和列表，数据存本地` → app
  `app-9d7162ef-7449-411d-9d64-89df441d7c26`,
  `miniapps/movie-ui-b.html`.
- A added `A书`; after closing and reopening A, `A书` was still visible.
- B added `B书`; B's text did not contain `A书`, and A's text did not contain
  `B书`.
- A's iframe storage: prefix
  `miniapp:app-4aa9d520-0a8a-4b5e-ae45-e8d7d7894b4f:`, key
  `miniapp:app-4aa9d520-...:myBooks`, value `["A书"]`.
- B's iframe storage: prefix
  `miniapp:app-9d7162ef-7449-411d-9d64-89df441d7c26:`, key
  `miniapp:app-9d7162ef-...:myMovies`, value `["B书"]`.
- “数据 → 清空数据 → 确认清空” on A emptied A's iframe storage and text;
  B still had `B书`.  The final IndexedDB `kv` listing contained only B's
  prefixed key; both `apps` records remained.
- Negative prompt `不要生成 HTML，只输出纯文本：这不是一个 HTML 文档`:
  HTTP 422, UI banner `INVALID_HTML · 模型输出不是完整的 HTML 文档（需要
  <html> 和 </html>）`, list stayed at two rows, and the workspace contained
  only the two generated files.
- `pageErrors` was empty.  Console errors were one 404 (favicon) and the
  expected 422 negative response.

Evidence: `/tmp/hc-miniapp-ui/evidence/` — `ui-e2e.json`,
`key-lists.json`, `generate-reading-ui-a.edn`, `generate-movie-ui-b.edn`,
`generate-not-html.edn`, screenshots `01`–`09`, and `ui-e2e.log`; workspace
`/tmp/hc-miniapp-ui/work/miniapps/`.

This closes the mini-app part of E2E 13.  The skills part was run afterwards
in the same app; see §8.5.

### 8.5 Skills E2E and default assistant (real Chrome)

**Default assistant**

- `session-config/session-config` injects
  `{"default" {:id "default" :assistant-id "default"
               :name "通用助手" :revision 1}}` when the settings document has
  no assistant; an existing assistant map is preserved.
- `new-session` binds the default assistant when the caller supplies none.
- `session-summary` publishes `:assistant-id`; the outbound context publishes
  `:assistant {:id :name :revision}`.
- `settings-view`/`settings/snapshot` expose `:settings/assistants` and
  `:settings/assistant-order` (the built-in default appears when empty).
- Fresh data-dir real app: the default session showed `助手范围 default`; a
  new worldbook was bound to `default` and the card showed `绑定 default`.
- Tests: `hc.server.app.assistant-default-test` 5 tests / 26 assertions;
  combined settings/outbound/sessions run 86 tests / 504 assertions,
  0 failures / 0 errors.

**Skills**

- Archive `e2e-skill.zip` (`SKILL.md` + `notes.txt` +
  `templates/report.md`) installed through the real UI; card showed
  `未启用`, `绑定 0`, description, and all three files.
- Enable + bind to `default`: card showed `已启用`, `绑定 1`; catalog recorded
  `:enabled? true`, `:bindings #{"default"}`.
- Traversal archive (`SKILL.md` + `../../evil.txt`) rejected with
  `path must not contain '..'`; skill list stayed at one card.
- Update conflict: local `SKILL.md` edit + upstream `UPSTREAM V2` produced
  `冲突 1`; `应用并保留本地` completed with `已应用，待复核`, and the disk
  still held `LOCAL EDIT`, not `UPSTREAM V2`.
- Client-audit fixed the archive selector and the disabled apply button; the
  rerun used the real UI with no DOM workaround.
- Remaining client gap: worldbook/skill binding is still a free-text
  assistant ID, not a selector showing `通用助手`. The server exposes
  `:settings/assistants`; a client selector can consume it.

Evidence: `/tmp/hc-default-assistant/evidence/skills-e2e.json`,
`skills-fixed.log`, screenshots `10`–`14`, `worldbook-bound.png`,
`worldbook-binding.txt`, `tests.log`; catalog and skill root under
`/tmp/hc-default-assistant/data/extensions/`.

## 9. Residual gaps

- `client-audit` wired the client callback to `model/normalize-app` →
  `store/put-app!` → list upsert → `load-app!`; the real Chrome UI journey
  passed: both generated apps appeared in the list, opened, persisted, and
  isolated their IndexedDB data.
- The skills portion of E2E 13 is covered by §8.5; install/enable/bind/
  traversal/update-keep-local pass.  Worldbook/skill binding still uses a
  free-text assistant ID; the client selector for `:settings/assistants` is
  the remaining gap.
- One generation attempt only; invalid output is returned, not repaired by a
  second model call. The compact system prompt keeps the 1024-token budget,
  and `:finish-reason :length` is reported as `:OUTPUT_TRUNCATED`.
- External images, stylesheets, and iframes are not rejected; the assignment
  only required script sources and form actions.
- The route is synchronous on the HTTP thread; a long generation occupies one
  worker. The service deadline is 300 s (request override up to 600 s).
- Registry persistence is one EDN file per data directory. The in-process
  service cache serializes registrations for one app process; separate
  processes sharing a data directory are not coordinated.
- The model provider connection needs an explicit `:timeout-ms` above the
  client default when the model is slow; the real run used 300000 ms.
- The server prefix script is written into generated mini-app HTML.  A
  manually added mini-app (the “打开为小应用” path) keeps the client sandbox's
  per-app IndexedDB isolation but does not receive the server prefix script.
- The dedicated storage-isolation browser test loads the generated files
  directly from a same-origin static server.  That normal page can enumerate
  the prefixed native keys; in the client, the injected script runs inside the
  `allow-scripts` iframe and wraps the client bridge, which persists to the
  app's IndexedDB namespace.  The real client UI E2E (§8.4) exercised that
  sandbox path with generated apps and passed.
- The injected script is part of the stored file.  Removing or editing it
  drops the server prefix; the client bridge still isolates by app ID.
- The earlier skills client gaps (archive select labels and disabled
  keep-local apply) were fixed by client-audit and passed the rerun.  The
  remaining gap is the worldbook/skill binding control: it is still a
  free-text assistant ID, not a selector for the exposed
  `:settings/assistants` list.  The built-in `default` / `通用助手` is
  available and was bound successfully.
