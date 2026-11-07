# hc.client.extensions — MCP and skill management UI

Authority: root `AGENTS.md` §5.6, §5.7, §7.4 旅程 6.2–6.3（MCP 与技能）; `src/hc/client/AGENTS.md`;
`src/hc/client/workbench/AGENTS.md`.

This package owns the client-side MCP and skill management surface.  It is
mounted in the existing `材料与扩展 → 扩展` tab.  The server catalog is the only
authority; this package keeps a display copy plus local drafts and command
status.

## 1 Responsibility

- show MCP servers, their computed connection state, enabled flag and tools;
- show installed skills, enabled flag, bindings, source and file metadata;
- submit real `extensions/*` commands through the workbench Sente transport;
- apply a server receipt and then request one authoritative
  `:extensions/view` snapshot;
- keep drafts and pending command ids across re-renders.

It does not:

- call MCP tools (that is the server execution boundary);
- execute skill files;
- invent an enabled switch before the server view says so;
- keep a second server-state database in component-local atoms.

## 2 State

```clojure
{:ready? false
 :version 0
 :skills [] :mcp [] :connections []
 :skill-updates {skill-id {:diff {...} :skill {...} :applied? false}}
 :commands {command-id {:status :queued|:submitting|:accepted|:rejected|:failed
                        :payload ... :error ...}}
 :outbox [command ...]
 :last-error nil
 :draft {:mcp {:name "" :type :http :url "" :command "" :args "" :env ""}
         :skill {:name "" :text ""}
         :skill-updates {skill-id "upstream SKILL.md text"}
         :assistant-id ""}
 :selected {:mcp nil :skill nil}}
```

The Fulcro key is `:ui/extensions`.  `set-extensions-state` rejects an older
`:version` so a late view cannot undo a newer one.

## 3 Commands

Local UI action keywords use single-slash names (`:extensions/mcp-draft`,
`:extensions/mcp-add`, `:extensions/skill-install`) so ClojureScript `case`
accepts them.  Wire kinds remain multi-segment keywords built with
`(keyword "extensions" "mcp/add")`.

Every command is an envelope:

```clojure
{:command-id "uuid"
 :payload {:kind :extensions/mcp/add :server {...}}}
```

`flush-outbox!` marks a command `:submitting` before handing it to Sente.  If
Sente rejects it, it becomes `:failed` with a visible error; it is never shown
as applied.  A server receipt with `:ok? true` and `:receipt :status
:accepted` updates the command, then queues one `:extensions/view` refresh.
An accepted `:extensions/skill/compare` receipt stores only the returned
path/status/conflict diff under `:skill-updates <id> :diff`; no file bytes or
writes reach the client.  The user must then choose `保留本地` (default) or
`采用上游`; the choice becomes `:extensions/skill/update` with `:strategy
:keep-local` or `:take-upstream`.

The server's `hc.server.extensions.app/handle!` wraps results under
`:receipt {:status ... :result ...}` so the gateway's public receipt carries
the view or operation result.  `:ok? false` is `:rejected`.

## 4 Views

- MCP form: name, type (`HTTP`/`SSE`/local process), URL or program + one
  argument per line + `KEY=VALUE` environment lines.  Program and arguments
  stay separate; no shell string is built.
- MCP import: paste JSON/EDN, preview candidates without raw headers/secrets,
  then import.  New imports are disabled and keep a report of unknown fields
  and failed candidates.
- Server card: state label, current-session availability
  (`:session-available?`/`:session-scope`; absent means “当前会话可用性未报告”),
  enable checkbox, open/test, refresh tools, close, remove; per-tool
  read/write/destructive badges, enabled toggle, and "审阅并启用" for a new or
  materially changed tool.  The four-stage text is
  “新增配置 → 测试连通 → 启用工具 → 会话可用”.
- Skill form: optional name and `SKILL.md` text; install is disabled until the
  extension view is synchronized.  The source selector also accepts a direct
  URL and a local `.zip` file; archive bytes are read as base64 in the browser
  and decoded/validated again by the server.  Every install is disabled and
  unbound.
- Skill card: enabled checkbox, bindings count, source kind plus URL/filename,
  file metadata,
  bind/unbind with an explicit assistant id, delete, and an upstream update
  section.
- Skill update section: choose text, direct URL or `.zip`, compare, review
  per-path statuses (conflict/local-changed/upstream-changed/add/delete), then
  apply with the local-preserving default or explicitly adopt upstream.  The
  three-way comparison stays server-side; the client never sends local file
  bytes, only the chosen upstream source.
- A control is disabled with the server-reported reason when the server is
  not connected, a tool is unavailable, or a command is already pending.  No
  switch is optimistic.  The enable checkboxes themselves are disabled while
  any extension command is pending.

## 5 Integration

`hc.client.workbench.extensions-bridge`:

- installs an event listener for `:hc/command-receipt`;
- after the connection is `:synced` and command-allowed, asks once for
  `:extensions/view`;
- applies receipts outside the Sente receive callback;
- submits through `transport/command!` with the current command epoch and
  subscription id.

`hc.client.workbench.main` installs and syncs the bridge; `workbench.db` and
the Fulcro root query include `:ui/extensions`; `views/materials` mounts the
panel in the `:extensions` tab.

## 6 Verification

```bash
clojure -M:test -n hc.client.extensions-test
clojure -Sdeps '{:deps {thheller/shadow-cljs {:mvn/version "3.5.11"}}}' \
  -M:client -m shadow.cljs.devtools.cli compile app
```

The real Sente smoke is `test/hc/client/extensions/sente_smoke.cljs` plus
`test/hc/server/extensions/sente_smoke_server.clj`:

```bash
# terminal 1
clojure -M:dev:server -m hc.server.extensions.sente-smoke-server
# terminal 2: pass the printed port, command and EDN args to
clojure -Sdeps '{:paths ["test"] :deps {org.clojure/clojurescript {:mvn/version "1.12.145"} com.taoensso/sente {:mvn/version "1.22.0"}}}' \
  -M:client -m cljs.main -re node -e "(require '[hc.client.extensions.sente-smoke :as s]) (s/-main \"PORT\" \"COMMAND\" \"ARGS-EDN\")"
```

The smoke sends add → enable → open → tool-enable (echo and write) → scoped
tools → read call → viewer write rejection → admin write completion over a
real Sente WebSocket and a real local MCP process.

## 7 Limits

- The panel manages activation and configuration; it does not render a tool
  call action card or invoke a tool.  Tool calls belong to the conversation
  execution boundary.
- OAuth is shown as `需登录`; the browser authorization flow is not in this
  panel.
- Skill install/update source pickers support text, direct URL and `.zip`
  file input.  A project homepage that needs candidate listing is not treated
  as a skill; the server returns `:SKILL_SOURCE_NEEDS_SELECTION`.  The panel
  must show the specific “选择仓库中的具体 SKILL.md 或目录” message rather than
  a generic read failure.
- URL sources are fetched by the server's pinned safe fetch: only public
  http(s) addresses, no redirects, and the socket is connected to the checked
  address while TLS still verifies the original host.  Local/private URLs are
  refused in the product UI.
- Archive bytes travel through the Sente command envelope as base64.  The
  gateway's 1 MiB message limit therefore caps a practical ZIP below roughly
  750 KiB; larger archives need the HTTP/Blob upload path, which is not
  implemented in this panel.
- No optimistic enable state: a click is visible as pending until the receipt
  and refreshed view arrive.

## 8 渲染硬约束（防回归）

- **React child 只能是可渲染值**：字符串、数字、nil、React element 或这些的
  序列。不得把 keyword、map、set 或其他任意 CLJS 值直接作为 child；否则 React
  抛 `Objects are not valid as a React child`，整个扩展面板白屏。显示名称/路径/
  状态一律经 `text`/`str`/`pr-str` 转成字符串。
- **`:files` 兼容两种形状**：map（按 key 排序）与 sequential（每项取
  `:path`/`:name`，缺省“文件”）。其他形状显示空列表，不遍历任意对象。
  每个条目仍只显示路径、大小与文本/二进制标记，不渲染原始 map。
- **失败模式**：服务端返回 keyword 或嵌套 map 作为 `:files`/`:name` 时，旧实现
  会把它们直接塞进 `el`，导致 React 运行时错误；测试必须覆盖 map 和 vector
  两种 `:files`，并断言输出节点文本不是 `:keyword`/`{...}`。
- **复检**：

```bash
clojure -M:test -n hc.client.extensions-test
```

新增的渲染测试至少包含：map `:files`、vector `:files`、keyword 名称、nil 文件
列表；真实 React 渲染不得抛 child 类型错误。
