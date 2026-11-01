// The four agents (§7.1, §6). A host agent is `Agent.Info`; the adapter only
// changes the fields it owns and leaves the host's defaults alone.
//
// `mode` is the load-bearing field: the builtin `subagent` tool refuses an
// agent whose mode is `primary`, and Manager and Plan are the only primaries.
// `system` is the composed prompt (§5). `permissions` start from the host
// default and are narrowed here, because the permission list also decides which
// tools the catalog offers (`Tool.snapshot(permissions)`).
//
// Plan (§6) is a top-level planning agent: it writes one draft, asks the
// operator, gets repository facts through `this.devops`, and reviews. It has no
// shell, no edit, no grep and no glob: `cat` and `write` live inside its
// program, and its file reach is the draft path the task fixes.

import { systemPromptFor } from "../../compose/prompt/projection.mjs";
import { PLAN_PROMPT } from "../../compose/prompt/plan.mjs";

const DENY = (action, resource = "*") => ({ action, resource, effect: "deny" });

// Manager: no regular tool may run; the program tools decide for themselves.
// No `question`: §2.3 runs the Manager unattended, so it has no ask channel.
const MANAGER_PERMISSIONS = Object.freeze([
  DENY("*"),
  { action: "js-manager", resource: "*", effect: "allow" },
  { action: "js-review", resource: "*", effect: "allow" },
  { action: "fork", resource: "*", effect: "allow" },
  { action: "join", resource: "*", effect: "allow" },
]);

// Worker: its own program tool only. `call` inside the program is the only
// command path, so no host shell tool is allowed, and §3.1 gives a child agent
// no question channel at all.
const WORKER_PERMISSIONS = Object.freeze([
  DENY("*"),
  { action: "js-worker", resource: "*", effect: "allow" },
]);

// Plan: its own face only. It asks with `this.ask` in `S1` (park + interrupt),
// never with the host's blocking `question` tool. It never runs a command: the
// repository facts it needs arrive through `this.devops(intent)` (§6).
const PLAN_PERMISSIONS = Object.freeze([
  DENY("*"),
  { action: "plan", resource: "*", effect: "allow" },
]);

// Plan's system prompt is its own role segment: §6 keeps Plan outside the five
// `skillDocuments` projection, so it is neither `common + manager` nor
// `common + worker`. `systemPromptFor("plan")` is the empty string by design;
// passing it here would leave Plan with no prompt at all.
export const AGENT_DEFINITIONS = Object.freeze({
  Manager: Object.freeze({
    mode: "primary",
    hidden: false,
    system: systemPromptFor("manager"),
    permissions: MANAGER_PERMISSIONS,
  }),
  Plan: Object.freeze({
    mode: "primary",
    hidden: false,
    system: PLAN_PROMPT,
    permissions: PLAN_PERMISSIONS,
  }),
  coder: Object.freeze({
    mode: "subagent",
    hidden: false,
    system: systemPromptFor("coder"),
    permissions: WORKER_PERMISSIONS,
  }),
  devops: Object.freeze({
    mode: "subagent",
    hidden: false,
    system: systemPromptFor("devops"),
    permissions: WORKER_PERMISSIONS,
  }),
});

// Every other agent the host knows is hidden, so the host has one semantic
// (§2.4). The transform hides rather than removes: a builtin the host still
// needs (title, summary) must keep its definition.
export const OTHER_AGENTS_HIDDEN = true;
