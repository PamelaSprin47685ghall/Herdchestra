# hc.server.extensions

Local specification for skills and MCP configuration.

Authority: root `AGENTS.md` §5.6, §5.7 and §7.4 旅程 6.2–6.3（MCP 与技能）; `src/hc/server/AGENTS.md`
defines the adapter boundary. This package owns configuration and activation
only. It does not execute a skill, call an MCP tool, approve an action, or
write a domain session.

## 1 Responsibility

The package has two independent capability families in one catalog value:

```clojure
{:version 1
 :skills {skill-id skill-record}
 :mcp {:servers {server-id server-record}}}
```

A caller owns the catalog and commits the returned value with its surrounding
domain state. Neither family can grant the other family's permission. Every
newly installed skill and every newly added or imported MCP server starts
disabled.

The package provides:

- safe skill preview from text, a file map, a directory, a ZIP archive or a direct link;
- managed skill installation, binding, activation, copy, deletion and
  three-way update;
- MCP configuration for streamable HTTP, SSE and local processes;
- short-lived connection probes that perform the MCP handshake and
  `tools/list`;
- connection/tool state transitions, OAuth attempt binding, redacted views and
  session-scope resolution;
- safe MCP configuration import with duplicate-name policy and per-candidate
  failure reporting.

It deliberately does not provide:

- `tools/call` or any other business-tool invocation;
- skill script execution;
- a persistent MCP process or connection owner;
- per-tool approval (the always-authorized mode forbids it);
- assistant, session or provider persistence.

A probe proves only that a server answered and declared a tool list. It never
enables a tool and never proves that a tool is safe or authorized.

## 2 Defaults and state

### Skills

```clojure
{:id "skill-<uuid>"
 :name "weekly-report"
 :description "..."
 :entry "SKILL.md"
 :files {"SKILL.md" {:path "SKILL.md" :size 123 :sha256 "sha256-..." :text? true}}
 :root "<storage-root>/skills/<id>"
 :storage-root "<storage-root>"
 :source {:kind :text|:files|:archive|:url|:copy ...}
 :conditions {:requires-workspace? false
              :requires-tools []
              :requires-network? false
              :requires-user-input []}
 :enabled? false
 :bindings #{}
 :local-modified? false
 :review-required? false
 :installed-at 0
 :updated-at 0}
```

Rules:

1. `install!` always sets `:enabled? false` and `:bindings #{}`; it ignores any
   caller attempt to install pre-enabled.
2. A global disable wins over every assistant binding and every session
   custom list.
3. A new skill is never added to an existing assistant's selection.
4. `resolve-skills` returns only selected skills. Unselected skills are not
   returned as context. Selected skills with unmet conditions are returned in
   `:blocked`, not `:active`.
5. Deletion removes only `<storage-root>/skills/<id>`. User files outside that
   directory are untouched. A failed deletion leaves the catalog unchanged.
   A skill id must be a direct child name; `.` and `..` are refused.
6. Update uses three sides: the installed digest map, the current files on
   disk, and the new upstream file map. Conflicts keep the local file by
   default, set `:local-modified?` and `:review-required?`, and never enable
   the skill. `:take-upstream` deletes a locally modified file when upstream
   deleted it; it never writes nil bytes.
7. A caller-supplied update candidate is revalidated by the preview rules
   (`SKILL.md`, UTF-8, entry and total size) before any write.
8. `read-file` returns one UTF-8 file inside an installed skill; it validates
   the relative path, refuses symbolic links and bounds the read.

### MCP

```clojure
{:id "mcp-<uuid>"
 :name "filesystem"
 :transport {:type :http|:sse :url "..." :headers {}}
            ;; or
            {:type :stdio :command "npx" :args ["-y" "..."] :env {} :cwd "..."}
 :enabled? false
 :connection {:status :unconnected|:connected|:needs-login|:connection-failed
              :checked-at nil :duration-ms nil
              :server-info nil :protocol-version nil :error nil}
 :auth {:state :none|:pending-login|:needs-login|:authenticated
        :token nil :server-id nil :account nil}
 :tools {"tool-name" {:name "tool-name"
                      :description "..."
                      :input-schema {...}
                      :annotations {...}
                      :read-only? false
                      :destructive? false
                      :idempotent? false
                      :open-world? false
                      :description-digest "sha256-..."
                      :enabled? false
                      :available? true
                      :removed? false
                      :review-required? true
                      :description-changed? false
                      :new? true}}
 :source {:kind :manual|:import ...}
 :created-at 0
 :updated-at 0}
```

The user-visible server states are computed, not stored:

| State | Meaning |
|---|---|
| `:unconnected` | no successful probe, or explicitly disconnected |
| `:connected-not-enabled` | connected, but the server or every tool is disabled |
| `:available` | enabled server with at least one enabled, available tool |
| `:needs-login` | authentication is pending or required |
| `:connection-failed` | the last probe failed |

Rules:

1. A successful probe merges the tool list. Existing tool selections survive;
   new tools are disabled and `:review-required? true`; removed tools become
   `:available? false` and never appear in a callable list.
2. Enabling a tool that is unavailable is refused with
   `:MCP_TOOL_UNAVAILABLE`. Enabling a tool that has not been reviewed is
   refused with `:MCP_TOOL_REVIEW_REQUIRED`; pass
   `{:approve-review? true}` to record the review and enable it.
3. A changed description sets `:description-changed? true` and requires
   review again. The previous selection is retained, not silently widened.
4. Session scope is `{:assistant-servers #{id} :session {:mode :follow|:custom
   :servers #{id}}}`. `:custom` replaces the assistant list; it cannot
   override a global disable, failed connection or disabled tool.
   A browser command may choose the assistant/session selection but cannot
   assert `:role` or `:capabilities`; those come from the server. A trusted
   caller may pass `{:capability-scope ...}` as a hard limit.
5. Changing the transport target/command/arguments/environment/working
   directory clears the old tool list and authentication. Rotating credentials
   resets the connection but keeps the tool selection.
6. `logout!` removes secret-looking header and environment entries, retains
   non-secret entries, and requires a new probe. `disconnect!` marks the server
   unconnected and keeps the tool selection.
7. OAuth callbacks are bound to one `[server-id token]` attempt. A callback for
   another server, or an older token, is refused with
   `:MCP_OAUTH_CALLBACK_MISMATCH`.
8. `hc.server.extensions.mcp.connection` owns live connections.  `open!` keeps
   the transport after the handshake; `close!` and `shutdown!` close only
   connections this component started.  Calls are serialized per server.
9. A tool call requires the server to be enabled, the tool to be enabled and
   available, the server to be selected in the session scope, and the session
   role/capabilities to cover the tool's annotations:
   `:mcp/call` always, `:mcp/write` for non-read-only tools, and
   `:mcp/destructive` when `destructiveHint` is true.
10. Authorization is only the configured session scope and role.  There is no
    per-tool approval: always-authorized mode treats an authorized write as
    authorized.  A session cannot widen a global disable, a disabled tool, an
    unavailable tool or a missing capability.
11. A sent non-read-only call that loses its transport is `:unknown`, never
    `:failed` or `:completed`; the caller must check the remote state.  A
    read-only call may be retried safely.

## 3 Transports

### Local process (`:stdio`)

- `command` and `args` are separate. `ProcessBuilder` receives a list; no shell
  is used. A command containing spaces is reported with
  `:MCP_COMMAND_NOT_SPLIT` and is not split automatically.
- `env` is merged into the inherited environment; `cwd` must be absolute.
- stdout is newline-delimited JSON-RPC. stderr is drained into a bounded buffer
  and redacted before appearing in a diagnostic.
- Server-initiated requests receive JSON-RPC error `-32601`; they are not
  executed.
- A probe closes the process. A timeout returns `:MCP_TIMEOUT`; the probe's
  `finally` destroys the process.

### Streamable HTTP (`:http`)

- Each JSON-RPC message is a POST. The response may be JSON or an SSE body.
- `Mcp-Session-Id` is captured and sent on later requests.
- 401/403 becomes `:MCP_AUTH_REQUIRED`; other non-2xx responses keep the
  status and a bounded, redacted body.

### SSE (`:sse`)

- A GET stream must announce an `endpoint` event. Requests are POSTed to that
  endpoint and responses arrive as `message` events.
- The stream is closed when the probe ends.

## 4 Managed calls and cancellation

```clojure
(def manager (connection/manager))
(def opened (connection/open! manager catalog server-id {:timeout-ms 5000}))
(def result (connection/call! manager (:catalog opened)
                              {:role :admin :assistant-servers #{server-id}}
                              server-id "write_file" args))
```

`call!` opens a live connection if none exists, starts the call, and waits for
the default timeout.  `start-call!` returns a handle immediately; `await!` can
be repeated.  `refresh!` closes the old transport and performs a fresh
handshake; `open!` reuses a live connection.  `cancel!` sets the cancellation
flag and, after the request was sent, closes the transport.  The final result
for a sent non-read-only call is `:unknown`; a request cancelled before send is
`:not-started`.  Every call records its id, server, tool, arguments, start
time, duration and outcome in a bounded in-process history.

The role map is:

| Role | Capabilities |
|---|---|
| `:viewer` | `:mcp/call` |
| `:operator` | `:mcp/call`, `:mcp/write` |
| `:admin` | `:mcp/call`, `:mcp/write`, `:mcp/destructive` |

An explicit `:capabilities` set replaces the role map.  A session cannot widen
a global disable, a disabled tool or an unavailable tool.

## 5 Safe import

### Skill archives

- Backslashes are rejected, not translated; absolute paths, drive letters, UNC
  paths, empty segments and `..` are refused.
- Duplicate paths (case-insensitive) and more than one `SKILL.md` are refused.
- Entry and total size limits: 5 MiB per entry, 20 MiB total, 200 entries.
- If all files share one root directory, the root is stripped so `SKILL.md`
  sits at the skill root. Files outside that root are reported as warnings.
- `preview-candidates` lists every distinct skill root in an archive so the
  caller can select one; `preview` still refuses an ambiguous archive.
- `SKILL.md` must be valid UTF-8. Companion files are inert data.
- A failed preview writes nothing. A failed install removes its temporary
  directory.

### MCP configuration

- Input may be JSON or EDN. JSON is tried first.
- Recognized roots: `mcpServers`, `servers` (map or vector), a named single
  server, or a top-level vector of named servers.
- Every candidate is normalized with `mcp.config/normalize`. Unknown fields
  stay in the report and are not copied into the live configuration.
- Secret-looking header/env values are reported as `:has-secrets?` and masked
  in public views.
- Duplicate names default to `:rename` (`name-2`); `:skip` and `:update` are
  explicit choices. An update preserves the existing enable flag; a new server
  is always disabled.
- Import never starts a process and never connects. One bad candidate does not
  discard the valid candidates; the result lists created, updated, skipped and
  failed items.

## 6 Durable catalog and application wiring

`hc.server.extensions.durable` stores the catalog as one EDN document:

```text
<root>/catalog.edn
```

Writes go to a same-directory temporary file, are fsynced, then atomically
renamed; the file is owner-only where POSIX permissions exist.  The document
has a monotonically increasing version.  `save!` takes an expected version and
returns `:EXTENSION_CATALOG_CONFLICT` instead of overwriting a newer writer.
`update!` retries a pure function on a conflict; application mutations that
touch the filesystem use a non-retrying load/apply/save path. A catalog
conflict after a filesystem mutation returns the applied skill/diff/deletion
fields plus `:filesystem-applied? true` and the old catalog; the caller must
reconcile instead of blindly retrying.

`hc.server.extensions.app` attaches the durable catalog and connection manager
to an application system map.  `start!` resets persisted `:connected`
observations to `:unconnected` because no transport survives a process; it
persists that reset.  Updating, removing, disabling, logging out or completing
a login closes the live connection first, so the catalog cannot keep pointing
at an old process:

```clojure
(def system (assoc system :extensions (extensions-app/start! config)))
;; ...
(extensions-app/stop! (:extensions system))
```

`hc.server.app.system` starts this component after the store is open and stops
it before the store closes.  `hc.server.app.commands` delegates any command
whose kind starts with `extensions/` to `extensions-app/handle!`.  The handler
supports status/view, skill install/enable/bind/unbind/delete/compare/update/
resolve/preview/candidates/copy/file,
MCP add/update/remove/enable/tool-enable/tool-approve/open/refresh/probe/
import-preview/import/close/tools/call/cancel/login/login-complete/logout/
disconnect.
A missing component returns `:EXTENSIONS_UNAVAILABLE`.

Skill install/update sources accept `:text`, `:files`, `:url` and
`:archive-base64`. Browser commands additionally reject `:directory`,
`:allow-private?` and caller-supplied fetchers; those remain trusted local
capabilities.
The app decodes base64 into bytes and then reuses the same safe archive preview
as a local ZIP; malformed or oversized base64 is refused before any write.
URL sources go through `hc.server.extensions.safe-fetch`: it resolves every
address, refuses loopback/private/link-local/multicast/reserved ranges, pins
the socket to the checked address (no DNS rebinding), refuses 3xx, keeps the
original host for Host/SNI/certificate verification, and bounds time/body.
`:allow-private?` is test/local-only and is never sent by the browser.

## 7 Public entry points

`hc.server.extensions` is the facade. The main operations are:

```text
empty-catalog, summary

preview-skill, preview-skill-candidates, install-skill!, install-skill-source!
enable-skill!, bind-skill!, unbind-skill!, delete-skill!, copy-skill!
resolve-skills, compare-skill-update, apply-skill-update!
list-skills, public-skill
active-skill-context, public-skill-candidate, validate-skill-candidate,
read-skill-file

normalize-mcp-server, add-mcp-server!, update-mcp-server!, remove-mcp-server!
enable-mcp-server!, disconnect-mcp-server!
probe-mcp-config!, probe-mcp-server!
set-mcp-tool-enabled!, approve-mcp-tool!
begin-mcp-login!, complete-mcp-login!, logout-mcp!
resolve-mcp-tools, mcp-tool-callable?
mcp-tool-definitions
mcp-server-view, mcp-catalog-view
preview-mcp-import, import-mcp!

create-mcp-connection-manager, open-mcp-connection!, refresh-mcp-connection!,
close-mcp-connection!,
shutdown-mcp-connections!, start-mcp-call!, await-mcp-call!,
call-mcp-tool!, cancel-mcp-call!, mcp-call-history

hc.server.extensions.durable: open, load!, save!, update!
hc.server.extensions.app: start!, stop!, attach!, detach!, catalog,
  install-skill!, preview-skill!, preview-skill-candidates!, enable-skill!,
  bind-skill!, unbind-skill!, delete-skill!, copy-skill!, read-skill-file!,
  compare-skill-update!, apply-skill-update!, update-skill-from-source!,
  add-mcp-server!, update-mcp-server!, preview-mcp-import!,
  import-mcp-text!, remove-mcp-server!,
  enable-mcp-server!, set-mcp-tool-enabled!, approve-mcp-tool!,
  begin-mcp-login!, complete-mcp-login!, logout-mcp!, probe-mcp!, open-mcp!,
  refresh-mcp!, close-mcp!, call-mcp-tool!, cancel-mcp-call!,
  status, view, handle!
```

State-changing operations return either:

```clojure
{:ok? true :catalog catalog' ...}
{:ok? false :catalog catalog :error {:code :KEYWORD :message "..." :details {...}}}
```

A refusal leaves the caller's catalog unchanged. I/O failures may report the
paths already applied; the caller must treat that as the real filesystem state,
not retry blindly.

## 8 Error codes

Common:

- `:EXTENSION_NAME_REQUIRED`, `:EXTENSION_NAME_TOO_LONG`, `:EXTENSION_NAME_INVALID`
- `:EXTENSION_PATH_INVALID`, `:EXTENSION_PATH_ABSOLUTE`, `:EXTENSION_PATH_TRAVERSAL`
- `:EXTENSION_TOO_LARGE`

Skills:

- `:SKILL_TEXT_EMPTY`, `:SKILL_TEXT_INVALID`
- `:SKILL_ENTRY_MISSING`, `:SKILL_DUPLICATE_ENTRY`, `:SKILL_ENTRY_NESTED`
- `:SKILL_DUPLICATE_PATH`, `:SKILL_FILE_TOO_LARGE`, `:SKILL_TOO_LARGE`
- `:SKILL_ENCODING_INVALID`, `:SKILL_NAME_REQUIRED`, `:SKILL_NAME_MISSING`
- `:SKILL_ARCHIVE_INVALID`, `:SKILL_ARCHIVE_TOO_LARGE`,
  `:SKILL_ARCHIVE_TOO_MANY_ENTRIES`
- `:SKILL_NOT_FOUND`, `:SKILL_ID_TAKEN`, `:SKILL_ALREADY_INSTALLED`
- `:SKILL_ID_INVALID`, `:SKILL_FILE_NOT_FOUND`, `:SKILL_FILE_INVALID`
- `:SKILL_FILES_MISSING`, `:SKILL_INSTALL_IO`, `:SKILL_READ_IO`,
  `:SKILL_DELETE_IO`, `:SKILL_UPDATE_IO`
- `:SKILL_UPDATE_STRATEGY_INVALID`, `:SKILL_CANDIDATE_REQUIRED`
- `:SKILL_SOURCE_INVALID`, `:SKILL_SOURCE_UNREACHABLE`,
  `:SKILL_SOURCE_HTTP`, `:SKILL_SOURCE_AUTH`,
  `:SKILL_SOURCE_NEEDS_SELECTION`, `:SKILL_SOURCE_PRIVATE_ADDRESS`,
  `:SKILL_SOURCE_REDIRECT_REFUSED`, `:SKILL_SOURCE_TIMEOUT`

MCP:

- `:MCP_TRANSPORT_REQUIRED`, `:MCP_TRANSPORT_INVALID`
- `:MCP_URL_REQUIRED`, `:MCP_URL_INVALID`
- `:MCP_HEADER_INVALID`, `:MCP_HEADERS_INVALID`
- `:MCP_COMMAND_REQUIRED`, `:MCP_COMMAND_INVALID`, `:MCP_ARGS_INVALID`
- `:MCP_ENV_INVALID`, `:MCP_CWD_INVALID`
- `:MCP_JSON_PARSE`, `:MCP_JSON_WRITE`
- `:MCP_PROCESS_START`, `:MCP_PROCESS_IO`, `:MCP_PROCESS_EXITED`
- `:MCP_TIMEOUT`, `:MCP_PROTOCOL`, `:MCP_EMPTY_RESPONSE`,
  `:MCP_HTTP_STATUS`, `:MCP_AUTH_REQUIRED`, `:MCP_TRANSPORT`
- `:MCP_SSE_ENDPOINT_TIMEOUT`, `:MCP_SSE_ENDPOINT_MISSING`
- `:MCP_SERVER_NOT_FOUND`, `:MCP_SERVER_ID_TAKEN`
- `:MCP_TOOL_INVALID`, `:MCP_TOOL_NOT_FOUND`, `:MCP_TOOL_UNAVAILABLE`,
  `:MCP_TOOL_REVIEW_REQUIRED`
- `:MCP_TOOL_PAGE_LIMIT`
- `:MCP_LOGIN_NOT_PENDING`, `:MCP_OAUTH_CALLBACK_MISMATCH`
- `:MCP_IMPORT_PARSE`, `:MCP_IMPORT_EMPTY`, `:MCP_IMPORT_SHAPE`,
  `:MCP_IMPORT_CANDIDATE`, `:MCP_IMPORT_DUPLICATE_POLICY`,
  `:MCP_IMPORT_TAGGED_VALUE`
- `:MCP_SERVER_DISABLED`, `:MCP_NOT_CONNECTED`, `:MCP_CONNECTION_FAILED`
- `:MCP_TOOL_DISABLED`, `:MCP_SCOPE_DENIED`, `:MCP_CAPABILITY_REQUIRED`
- `:MCP_CALL_COMPLETED`, `:MCP_PROTOCOL`, `:MCP_TRANSPORT`
- `:EXTENSION_CATALOG_INVALID`, `:EXTENSION_CATALOG_CONFLICT`,
  `:EXTENSION_CATALOG_IO`, `:EXTENSION_CATALOG_OPEN_FAILED`,
  `:EXTENSION_STORE_CLOSED`
- `:EXTENSION_COMMAND_UNKNOWN`, `:EXTENSIONS_UNAVAILABLE`

## 9 Tests

```bash
clojure -M:test -n hc.server.extensions.json-test
clojure -M:test -n hc.server.extensions.mcp-config-test
clojure -M:test -n hc.server.extensions.mcp-state-test
clojure -M:test -n hc.server.extensions.mcp-import-test
clojure -M:test -n hc.server.extensions.skill-test
clojure -M:test -n hc.server.extensions.mcp-integration-test
clojure -M:test -n hc.server.extensions.mcp-connection-test
clojure -M:test -n hc.server.extensions.durable-test
clojure -M:test -n hc.server.extensions.app-integration-test
```

`mcp-integration-test` compiles a tiny Java MCP server into a temporary
directory and runs it as a real local process. It also uses loopback HTTP and
SSE servers. It asserts that probing sends no `tools/call` message.

`mcp-connection-test` runs the same real local process and exercises
`tools/call`, read-only/write role checks, authorized write without per-tool
approval, tool errors and cancellation after send (`:unknown`, not success).
`app-integration-test` drives the same call through
`hc.server.extensions.app/handle!` after a real `extensions/mcp/add`, `enable`,
`open` and `tool-enable` sequence, and covers restart reset, update-close,
failed-connection persistence and scoped tool/skill resolution.  The tests use
only temporary paths and loopback ports.

Recent coverage also asserts: browser source policy rejects `:directory`;
skill ids `.`/`..` are refused; `:take-upstream` deletes a locally modified
file that upstream deleted; a catalog conflict reports the applied files;
skill preview/copy/file commands work; MCP import can select candidates;
MCP login/logout commands work; and a client-supplied `:role :admin` cannot
bypass the server-derived scope.

The real Sente smoke uses `test/hc/server/extensions/sente_smoke_server.clj`
and `test/hc/client/extensions/sente_smoke.cljs`.  It sends add -> enable ->
open -> tool-enable -> scoped tools -> read call -> viewer write rejection ->
admin write completion over a real Sente WebSocket and a real local MCP
process.  `handle!` wraps every map result under
`:receipt {:status :accepted|:rejected :result ...}` so the gateway's public
receipt carries the view or operation result; this is what makes
`:extensions/view`, `:extensions/mcp/tools` and `:extensions/skills/resolve`
usable by the client.

## 10 Known limits

1. A probe is short-lived.  A managed connection is process-local and is not
   restored after a server restart; `app/start!` resets persisted `:connected`
   observations to `:unconnected`, and the next call opens a new connection.
2. Skill link import accepts direct text/Markdown/ZIP URLs. An HTML project
   page is refused with `:SKILL_SOURCE_NEEDS_SELECTION`; the caller must choose
   a concrete file or directory. Whole-repository crawling is not hidden in
   this adapter.
3. `preview` installs one skill at a time. A multi-skill archive must go
   through `preview-candidates`; the single-install path refuses it rather than
   silently choosing the first candidate.
4. Call history is in-process and bounded; it is not persisted across restart.
   A live MCP connection is closed on application shutdown; a user's
   separately managed service is never closed.
5. Tool calls are request/response.  MCP progress notifications and streaming
   tool results are not projected into a UI; a call either completes, errors,
   or remains `:unknown`.
6. HTTP streamable cancellation is best effort: closing an SSE stream or a
   stdio process aborts it, but an in-flight HTTP POST may still complete on
   the remote side, which is why the outcome is `:unknown`.
7. OAuth is a local state machine (`begin-login!`, callback binding,
   `complete-login!`, `logout!`).  The browser authorization page and provider
   callback listener are application UI, not this adapter.
8. Skill backup/restore is not implemented here.  The durable catalog file can
   be exported by the backup component, but skill files and MCP credential
   categories still need their own backup policy.
