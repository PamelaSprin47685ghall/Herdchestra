// Host facts for the Oh My Pi adapter (spec §7.3).
//
// Everything in this file was read from ../oh-my-pi at the commit the adapter
// targets; each entry names its source so a later reader can re-check it after
// an upstream change. Nothing here is a guess about OMP's behaviour.
//
// | fact                                    | value                        | source                                                        |
// |-----------------------------------------|------------------------------|---------------------------------------------------------------|
// | extension factory signature             | `(pi: ExtensionAPI) => void` | extensibility/extensions/types.ts (`ExtensionFactory`)        |
// | tool registration                       | `pi.registerTool(def)`       | types.ts `ExtensionAPI.registerTool`                          |
// | active tool set                         | `pi.getActiveTools()/setActiveTools()` | types.ts, loader.ts                                 |
// | one runner per session                  | `new ExtensionRunner(...)` per session | sdk.ts:3560, task/executor.ts wiring                |
// | subagent identity                       | `ctx.agent = {kind, id, name, ...}` | types.ts `ExtensionAgentIdentity`; runner.ts default TOP_LEVEL_AGENT |
// | system prompt replacement               | `before_agent_start` result `systemPrompt: string[]` | types.ts:1733; runner.ts:2373 |
// | request message replacement             | `context` result `messages`  | shared-events.ts:192, types.ts:1686                           |
// | tool gate / input rewrite               | `tool_call` result `{block, reason, input}` | shared-events.ts:331                             |
// | tool result rewrite                     | `tool_result` result `{content, details, isError}` | shared-events.ts:404                  |
// | delivery of a message into a session    | `pi.sendMessage(..., {deliverAs})` | types.ts:2054                                          |
// | user prompt into the current session    | `pi.sendUserMessage(content, {deliverAs})` | types.ts:2069                                 |
// | shell execution                         | `pi.exec(command, args)`     | types.ts `ExtensionAPI.exec`                                  |
// | subagent spawn                          | `task` tool, `{agent, name?, task, context?}` | task/index.ts, task/types.ts                        |
// | detached subagents                      | `async.enabled` default true | tools/settings.ts:963                                         |
// | job listing                              | `ctx.getAsyncJobSnapshot()`  | types.ts `ExtensionContext.getAsyncJobSnapshot`               |
// | recursion cap                           | `task.maxRecursionDepth` default 2 | task/settings.ts:343                                   |
// | shell tool name                         | `bash` (no `shell` tool)     | tools/bash.ts:514                                             |
// | subagent default approval               | `yolo` (`resolveApproval(..., "yolo", ...)`) | session/agent-session.ts `#beforeToolCall`   |

import path from "node:path";
import { fileURLToPath } from "node:url";

export const EXTENSION_NAME = "herdchestra";
export const ADAPTER_VERSION = "0.1.0";

// The host tools that Herdchestra replaces with its own programming face.
// Names are the host's own (`tools/builtin-names.ts` BUILTIN_TOOL_NAMES), not a
// guess: the real host offers `apply_patch`, `ast_edit`, `debug`, `lsp`,
// `web_search`, `wait` and `todo` as well, and a name that is missing here is a
// tool the worker keeps.
export const REPLACED_TOOLS = Object.freeze([
  "read",
  "grep",
  "glob",
  "write",
  "edit",
  "apply_patch",
  "ast_grep",
  "ast_edit",
  "bash",
  "eval",
  "debug",
  "lsp",
  "task",
  "wait",
  "todo",
  "web_search",
]);

// Tools the Manager may never call (spec §2.1, §7.3). `subagent` is listed
// too even though this OMP version registers `task`: a tool name in the list
// that OMP does not have costs nothing, and an older build that has it is
// covered without a second table.
export const MANAGER_FORBIDDEN = Object.freeze([
  ...REPLACED_TOOLS,
  // An older build's name for `task`; a name the host lacks costs nothing.
  "subagent",
]);

// The face a session is created with, by role. These are host FACTS (which tool
// names this adapter registers), so they live here rather than in the entry
// module: `surface.mjs` needs them to build a lane roster, and importing the
// entry module from the surface would close an import cycle.
//
// `MANAGER_TOOLS`: what the Manager sees after the narrowing. `js-review` is
// registered but in neither list: spec §2.3 opens it only while the session's
// evaluation window is open, and the window is not a role (see `index.mjs`
// `visibleToolsFor` and `ReviewWindow` in `core/review.mjs`).
// `WORKER_LANE_TOOLS`: what a worker session is created with (spec §2.1 gives a
// worker `call` through `js-worker`; §4.1 gives every worker `fork`/`join`).
// The two Scope snapshot queries (§6.2) are part of every face that books
// Scope: exempt from retention, so keeping them visible costs no budget.
export const SCOPE_QUERY_TOOLS = Object.freeze(["negotiate", "askPreviousScope"]);
export const MANAGER_TOOLS = Object.freeze(["js-manager", "fork", "join", ...SCOPE_QUERY_TOOLS]);
export const WORKER_LANE_TOOLS = Object.freeze(["js-worker", "fork", "join", ...SCOPE_QUERY_TOOLS]);
// A worker's own tools: its program face plus the scheduling primitives.
export const WORKER_TOOLS = Object.freeze(["js-worker", "fork", "join"]);
// The planner's own face (root spec §2.2): one tool, and no dispatch. The five
// capabilities it exposes are members of `plan`, not separate tools.
export const PLAN_TOOLS = Object.freeze(["plan"]);

// Worker identity names (spec §2.1). Both are OMP agent definition names.
export const WORKER_AGENTS = Object.freeze(["coder", "devops"]);

export const ROLE = Object.freeze({
  // The planner (root spec §2.1). It is a TOP-LEVEL agent — not created by
  // `fork` — so it cannot be read from `agent.kind`; it is read from the agent
  // name, the same way `coder` and `devops` are read for subagents.
  PLAN: "plan",
  MANAGER: "manager",
  CODER: "coder",
  DEVOPS: "devops",
  // No `agent` fact at all: the session's role was never proven. It is not a
  // fifth role a session may run as — it is the ABSENCE of a reading, and every
  // face check refuses it. Without this, `roleOfAgent(undefined)` answered
  // MANAGER, so a session whose agent the host had not filled yet silently
  // received the Manager's face and its review ledger.
  UNPROVEN: "unproven",
});

// The role an OMP session plays. The check is `ctx.agent.kind`, never `depth`
// (types.ts:553): `/tan` clones are subagents at depth 0.
export const roleOfAgent = (agent) => {
  // A missing `agent` is not a Manager: the host fills that object itself
  // (`sdk.ts:3571`), and a session bound before it exists has told us nothing
  // about who it is. Reading it as Manager handed out the Manager's face and
  // ledger on a guess; `UNPROVEN` says the reading failed, and every face check
  // refuses it (see `faceAllows` / `laneFaceFor`).
  if (agent === null || typeof agent !== "object") return ROLE.UNPROVEN;
  // The KIND comes first, always. Plan is top-level (§2.1) and is never created
  // by `fork`, so a `sub` is a worker whatever it is called — a subagent named
  // `plan` is a coder, not the planner. Checking the name first made any
  // subagent that happened to be called `plan` the planner, which would hand it
  // the planner's ledger and its single-file reach.
  if (agent.kind === "sub") {
    if (agent.name === ROLE.DEVOPS) return ROLE.DEVOPS;
    // A subagent that is neither coder nor devops is not one of our workers. It
    // still gets the worker face, never the Manager's.
    return ROLE.CODER;
  }
  // Top level: the planner is the one top-level role named for it; every other
  // top-level session is the Manager that receives its draft.
  if (agent.name === ROLE.PLAN) return ROLE.PLAN;
  return ROLE.MANAGER;
};

export const isWorkerRole = (role) => role === ROLE.CODER || role === ROLE.DEVOPS;

// The OMP agent definition that runs a worker of this role. It reads the ROLE
// and nothing else: a worker's NAME is not an identity (§2.1, §4.1). The role
// itself comes from the brief's explicit declaration, which `roleOfBrief` reads
// in the composition layer; this function only maps it to a host definition.
export const agentForRole = (role) => {
  // The planner is top-level (§2.1) and is never created by `fork`. Answering
  // "coder" for it would silently start the wrong identity, so an unknown role
  // is refused here and `fork` reports it as an undeclared role.
  if (!isWorkerRole(role)) {
    throw new RangeError(`role ${String(role)} is not a worker role: only coder and devops are forked`);
  }
  return role === ROLE.DEVOPS ? "devops" : "coder";
};

export const projectRoot = () => process.env.HERDCHESTRA_PROJECT_ROOT || process.cwd();

// `.Herdchestra/` under the project root holds every persisted fact (spec §1).
//
// `root` is the directory the caller BOUND its state to. A caller that has one
// must pass it: defaulting to `projectRoot()` here is what let a session with an
// explicit root write to the working directory anyway. The name `.Herdchestra`
// has one definition, so no caller can invent a second spelling.
export const stateDir = (root = projectRoot()) => path.join(root, ".Herdchestra");

export const statePath = (...segments) => path.join(stateDir(), ...segments);

// Plan's draft (root spec §6): the one persistent product of a planning tenure,
// and the only file a Plan may read or write. §6 says the path is derived from
// the task and the system names it to the model; the adapter has no task
// metadata, so the path is derived from the SESSION — the same rule `host-cline`
// uses (`drafts.mjs`), so one work item keeps one draft across both hosts.
//
// The Plan may still `write` any path it names (§6 calls the single-file reach a
// responsibility boundary, not a runtime gate). This is the path the adapter
// reads when a hand-off names no note, so a Plan that wrote somewhere else is
// refused `DRAFT_MISSING` rather than silently handed an empty draft.
export const PLAN_DRAFT_DIR = path.join(".Herdchestra", "drafts");

export const planDraftPath = (root, name) => path.join(root, PLAN_DRAFT_DIR, `${name}.md`);

// The durable record of a fission group (§4.2 step 4). One append-only journal
// per session lineage, so a group committed before its lanes ran can be
// replayed after a crash, and a rolled-back group leaves a withdrawal line.
export const fissionRecordFile = (root, name) =>
  path.join(stateDir(root), `${safeSessionName(name)}.fission.jsonl`);

// A session id made safe for a file name. One definition, because the ledger
// file and the draft file must agree on what a session is called: a second
// sanitiser would let the two name different files for the same session.
export const safeSessionName = (value) =>
  String(value).replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120) || "session";

// Host paths: the adapter is loaded from inside the repository, so its own
// directory is `.../src/host-omp`. Two levels up is the repository root.
export const adapterDir = () => path.dirname(fileURLToPath(import.meta.url));

export const repoRootFromAdapter = () => path.resolve(adapterDir(), "..", "..");

// The extension entry file: the module the host loaded as this extension. A
// worker session is created with it as an explicit extension root, so the
// worker loads the same tools and hooks instead of starting bare.
export const adapterEntry = () => path.join(adapterDir(), "index.mjs");
