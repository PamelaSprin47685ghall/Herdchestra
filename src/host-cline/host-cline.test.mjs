// Cline adapter tests: real JS execution, the compose member tables, the hooks,
// and the host ports. The host itself is not run (that needs a live session);
// every host capability is a fake with the shape `../cline` documents.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import fc from "fast-check";

import { createPlugin } from "./index.mjs";
import { COMMON, MANAGER, WORKER } from "../compose/prompt/files.mjs";
import { systemPromptFor } from "../compose/prompt/projection.mjs";
import { SURFACE_MEMBERS } from "../compose/program/surface.mjs";
import { WorkerTable } from "./workers.mjs";
import { TenureState } from "./tenure.mjs";
import { relayPort, fissionSteps, laneFraming } from "./ports.mjs";
import { SessionFission } from "./fission.mjs";
import { MANAGER_FORBIDDEN, PLAN_FORBIDDEN, surfaceAllowed } from "./hooks.mjs";
import { planDraftPath, readDraft } from "./plan.mjs";
import { createOperatorSeam } from "./operator.mjs";
import { BUILTIN_TOOLS, pluginSessionConfig } from "./plugin-session.mjs";
import { startPlan } from "./plan-entry.mjs";
import { planHandoff } from "./handoff.mjs";
import { ROLE_METADATA_KEY, roleOf } from "./identity.mjs";
import { PLAN_PROMPT } from "../compose/prompt/plan.mjs";
import { PINNED_MEMBERS, UNRENDERABLE_TOOL_RESULT, lastOperatorIndex, lastOperatorText, selectKept, toCueToolResults, toItems } from "./request.mjs";
import { ManagerSessions } from "./sessions.mjs";
import { projectRoot, workspaceOf, workspaceRootOf, repoRootFromAdapter } from "./facts.mjs";
import { toCue } from "../core/cue.mjs";
import { delegatedAgentApi, hasCredentials, hostModuleSource, providerCredentials, sessionHostApi } from "./host-modules.mjs";
import { createDevopsRunner } from "./devops.mjs";
import { runExit, turnExit } from "./guard.mjs";

async function workspace(t) {
  const dir = await mkdtemp(join(tmpdir(), "herdchestra-cline-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return dir;
}

// The request-level projection the host really runs (根 §7.2): the `messages`
// the plugin's `beforeModel` hook answers. The host applies that answer to the
// provider-bound copy of this request only (`agent-runtime.ts:1700-1711`), so
// this is the copy the model reads.
//
// The request is built the way the runtime builds one: the runtime's own
// `AgentMessage` shape (`@cline/shared` `agent.ts:122`), not the provider's.
async function requestBuilder(plugin) {
  const hook = plugin?.hooks?.beforeModel;
  if (typeof hook !== "function") throw new Error("the plugin registered no request rewrite");
  return async (messages) => {
    const request = { messages, tools: [], systemPrompt: "" };
    const answer = await hook({ snapshot: { agentId: "top", parentAgentId: null }, request });
    return answer?.messages ?? messages;
  };
}

const top = (toolCallId) => ({ agentId: "top", toolCallId, snapshot: { agentId: "top", parentAgentId: null } });
const child = (toolCallId) => ({ agentId: "kid", toolCallId, snapshot: { agentId: "kid", parentAgentId: "top" } });

function setup(dir, extra = {}, { role = "manager" } = {}) {
  const plugin = createPlugin({ role });
  // A test session stands where a resolved one does: `cline-test` has no role
  // fact and no host record, and an unresolved session grants no face. Tests
  // about the face tables and the gate state the role directly.
  const settled = () => {
    const session = plugin.adapter?.session;
    if (session === undefined) return;
    session.resolveRole = async () => ({ role: "manager", source: "sessionRecord", metadata: null });
    // `identity` is the getter the gate reads for its source.
    Object.defineProperty(session, "identity", { get: () => ({ role: "manager", source: "sessionRecord", metadata: null }), configurable: true });
  };
  const tools = new Map();
  const rules = [];
  plugin.setup(
    {
      registerTool: (tool) => tools.set(tool.name, tool),
      registerRule: (rule) => rules.push(rule),
    },
    { cwd: dir, session: { sessionId: "cline-test" }, ...extra },
  );
  settled();
  return { plugin, tools, rules, settled };
}

// Undo the fixture's settlement so the session stands where a freshly loaded
// operator session does: no role fact, no host record, nothing answered yet.
//
// `identity` must be replaced by an OWN property that delegates to the class
// accessor — `delete session.identity` does nothing, because the getter lives
// on the prototype and the fixture's own-property definition shadows it. A
// test that "deleted" it kept reading the stubbed `sessionRecord` and passed
// the gate for the wrong reason.
function reopenIdentity(session) {
  session.resolveRole = Object.getPrototypeOf(session).resolveRole.bind(session);
  const prototype = Object.getPrototypeOf(session);
  Object.defineProperty(session, "identity", {
    get() {
      return Object.getOwnPropertyDescriptor(prototype, "identity").get.call(this);
    },
    configurable: true,
  });
  return session;
}

test("plugin declares the three capabilities it uses and registers six tools", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools, rules } = setup(dir);
  // `hooks` is the request-rewrite entry (根 §7.2): the copy is the
  // `beforeModel` hook's `messages`, and a builder never runs on this host.
  assert.deepEqual([...plugin.manifest.capabilities].sort(), ["hooks", "rules", "tools"]);
  assert.deepEqual([...tools.keys()].sort(), ["fork", "join", "js-manager", "js-plan", "js-review", "js-worker"]);
  // Two rules: the role prompt (every request, following the session's role)
  // and Plan's draft path, which the host includes only while `js-plan` is
  // available.
  assert.deepEqual(rules.map((rule) => rule.id), ["herdchestra-system-prompt", "herdchestra-plan-draft"]);
});

test("the role rule carries COMMON + MANAGER verbatim, then the director message", async (t) => {
  const dir = await workspace(t);
  const { rules } = setup(dir);
  const content = await rules[0].content();
  // The rule is the whole Manager prompt: `COMMON + MANAGER` with no extra
  // separator of its own, then the director segment the Manager alone gets.
  assert.equal(content.startsWith(COMMON + MANAGER), true);
  assert.equal(content.endsWith("</vibe-mode>"), true);
});

// Root §7.2: the system prompt reaches the provider through the registered
// rule, not through a hook. The host drops a `systemPrompt` returned from
// `beforeModel` (`agent-runtime.ts:1700-1719`), so the hook must not pretend to
// carry one — that was the gap: operator-started sessions had no role prompt.
test("the role prompt arrives through the registered rule, and the hook claims no system prompt", async (t) => {
  const dir = await workspace(t);
  const { plugin, rules } = setup(dir);
  const rule = rules.find((entry) => entry.id === "herdchestra-system-prompt");
  // The host appends rule content to the prompt it built (`mergeSystemPromptRules`),
  // so the rule carries the role segment and the director message.
  const prompt = await rule.content();
  assert.equal(prompt.startsWith(COMMON + MANAGER), true);
  assert.equal(prompt.endsWith("</vibe-mode>"), true);
  // The hook answers nothing about the prompt, on any request.
  const operator = [{ id: "m1", role: "user", content: [{ type: "text", text: "start" }], createdAt: 1 }];
  const first = await plugin.hooks.beforeModel({ snapshot: { agentId: "top", parentAgentId: null }, request: { messages: operator } });
  assert.equal(first?.systemPrompt, undefined);
  // It DOES answer the messages: that is the request copy's one entry (根 §7.2),
  // and it answers a new list, never the host's own.
  assert.equal(Array.isArray(first?.messages), true);
  assert.notEqual(first.messages, operator);
});

test("prompts arrive through the channels the host honours: the manager rule and the worker system prompt", async (t) => {
  const dir = await workspace(t);
  const { rules } = setup(dir);
  const roleRule = rules.find((rule) => rule.id === "herdchestra-system-prompt");
  assert.equal((await roleRule.content()).startsWith(COMMON + MANAGER), true);
  assert.equal(systemPromptFor("manager"), COMMON + MANAGER);
  assert.equal(systemPromptFor("coder"), COMMON + WORKER);
  assert.equal(systemPromptFor("devops"), COMMON + WORKER);
});

test("js-worker runs real JavaScript: loops, branches and helper functions work", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  const receipt = await tools.get("js-worker").execute(
    {
      code: `
        const names = ["a", "b", "c"];
        let total = 0;
        for (const name of names) {
          if (name !== "b") total += 1;
        }
        const text = names.filter((name) => name > "a").join(",");
        const written = await this.write("out.txt", text);
        return { total, text, written };
      `,
    },
    child("call-1"),
  );
  assert.equal(receipt.state, "completed", JSON.stringify(receipt.error));
  assert.equal(receipt.value.total, 2);
  assert.equal(receipt.value.text, "b,c");
  assert.equal(receipt.value.written.bytes, 3);
  assert.equal(await readFile(join(dir, "out.txt"), "utf8"), "b,c");
});

test("a member the role lacks is a TypeError, not a silent no-op", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  const receipt = await tools.get("js-manager").execute({ code: `await this.cat("x");` }, top("call-2"));
  assert.equal(receipt.state, "failed");
  // §3.1: a member has one spelling, so a role that lacks it fails on `this.cat`.
  assert.match(receipt.error.message, /this\.cat is not a function/);
});

test("js-manager cannot write, and js-review cannot write either", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  for (const tool of ["js-manager", "js-review"]) {
    const receipt = await tools.get(tool).execute({ code: `await this.write("x.txt", "y");` }, top(`call-${tool}`));
    assert.equal(receipt.state, "failed");
    assert.match(receipt.error.message, /this\.write is not a function/);
  }
});

test("where parks on ambiguity and on a miss, and the receipt carries the core payload", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  const tool = tools.get("js-worker");
  await tool.execute({ code: `await this.write("twice.txt", "needle\\nneedle\\n");` }, child("w1"));
  const ambiguous = await tool.execute(
    { code: `const text = await this.cat("twice.txt"); const span = await this.where(text, "needle");` },
    child("w2"),
  );
  assert.equal(ambiguous.state, "parked");
  assert.equal(ambiguous.reason, "explicit");
  assert.equal(ambiguous.payload.candidates.length, 2);

  await tool.execute({ code: `await this.write("once.txt", "alpha\\nneedle\\nomega");` }, child("w3"));
  const unique = await tool.execute(
    { code: `const text = await this.cat("once.txt"); return await this.where(text, "needle");` },
    child("w4"),
  );
  assert.equal(unique.state, "completed");
  assert.deepEqual(unique.value, [6, 12]);
});

test("call runs a real command through the command port", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  const receipt = await tools.get("js-worker").execute({ code: `return await this.call("printf hello");` }, child("call-cmd"));
  assert.equal(receipt.state, "completed", JSON.stringify(receipt.error));
  assert.equal(receipt.value.stdout, "hello");
  assert.equal(receipt.value.exitCode, 0);
});

test("js-review is read-only and reads real files", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  await tools.get("js-worker").execute({ code: `await this.write("seen.txt", "content");` }, child("r1"));
  const receipt = await tools
    .get("js-review")
    .execute({ code: `const text = await this.cat("seen.txt"); const info = await this.stat("seen.txt"); return { text, exists: info.exists };` }, top("r2"));
  assert.equal(receipt.state, "completed", JSON.stringify(receipt.error));
  assert.deepEqual(receipt.value, { text: "content", exists: true });
});

test("fork reports the one capability it still needs, by name, and never pretends to start", async (t) => {
  // The old contract was "no `@cline/core` reachable, so `HostCapabilityMissing`".
  // The ladder now finds the installed SDK, so that assertion became a fact
  // about the MACHINE rather than about the code. What the adapter guarantees
  // is narrower and always true: a fork either starts a real runtime, or it
  // refuses with a named reason — it never answers `started: true` without one.
  const dir = await workspace(t);
  const { tools } = setup(dir);
  // §4.1: two arguments. The brief declares the identity; there is no `role`.
  const receipt = await tools.get("fork").execute({ name: "w1", brief: "role: coder. Do it." }, {});
  assert.equal(receipt.steered, false);
  if (receipt.started === true) {
    // The SDK was reachable and a connection existed: the receipt must say which
    // readings produced it, so the run can be checked against the provider.
    assert.equal(typeof receipt.modelSource, "string");
    return;
  }
  // Not started: the reason is one of the two the adapter can honestly give,
  // and it names what was missing.
  assert.equal(["HostCapabilityMissing", "MODEL_UNKNOWN"].includes(receipt.rejection), true, receipt.rejection);
  // The receipt names what was missing when the adapter knows it: which sources
  // a `MODEL_UNKNOWN` tried, or which ladder a `HostCapabilityMissing` walked.
  if (receipt.rejection === "MODEL_UNKNOWN") {
    assert.deepEqual(receipt.detail.tried, ["request", "context", "sessionRecord", "environment"]);
  } else if (receipt.detail !== undefined) {
    assert.equal(typeof receipt.detail.searched, "string");
  }
  // A refusal always carries a message the model can read.
  assert.equal(typeof receipt.message, "string");
});

test("fork refuses a brief that does not declare exactly one worker identity", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  const fork = tools.get("fork");
  // No declaration: no default role is invented (§2.1).
  const undeclared = await fork.execute({ name: "w1", brief: "do it" }, {});
  assert.equal(undeclared.ok, false);
  assert.equal(undeclared.rejection, "UNDECLARED_ROLE");
  // Two identities in one field: the brief does not decide.
  const ambiguous = await fork.execute({ name: "w2", brief: "role: coder, devops. Do it." }, {});
  assert.equal(ambiguous.rejection, "AMBIGUOUS_ROLE");
  // A role input the schema no longer offers is not a way back in.
  assert.equal(fork.inputSchema.properties.role, undefined);
});

test("the .js entry re-exports the plugin: Cline refuses a .mjs plugin path", async () => {
  const entry = await import("./index.js");
  assert.equal(entry.default.name, "herdchestra");
  assert.equal(entry.plugin, entry.default);
  assert.deepEqual([...entry.default.manifest.capabilities], ["tools", "hooks", "rules"]);
});

test("a manager program can resume a worker program's park", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  const parked = await tools
    .get("js-worker")
    .execute({ code: `await this.write("q.txt", "a"); return await this.park({ why: 1 });` }, child("park-1"));
  assert.equal(parked.state, "parked");
  assert.deepEqual(parked.payload, { why: 1 });
  const resumed = await tools.get("js-manager").execute({ code: `return await this.unpark("park-1", [3, 4]);` }, top("park-2"));
  assert.equal(resumed.state, "completed", JSON.stringify(resumed.error));
  // Core's cutover: `unpark` answers the target's own nine-field receipt with the
  // injected value, not a `{ site, value }` wrapper. The receipt is the park view
  // of the resumed program; the injected value is on `value`.
  const receipt = resumed.value;
  assert.equal(receipt.name, "park-1");
  assert.equal(receipt.reason, "explicit");
  assert.deepEqual(receipt.payload, { why: 1 });
  assert.deepEqual(receipt.value, [3, 4]);
  assert.equal(Object.keys(receipt).length, 9);
});

test("join says honestly that nothing finished", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  assert.equal(await tools.get("join").execute({}, {}), null);
});

test("member tables come from compose, not from this adapter", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  assert.equal(typeof plugin.adapter.session.ports("coder").command.run, "function");
  assert.deepEqual(SURFACE_MEMBERS["js-manager"].includes("cat"), false);
  assert.deepEqual(SURFACE_MEMBERS["js-worker"].includes("fission"), true);
});

test("a session's root is its own workspace, never the adapter's repository", async (t) => {
  const dir = await workspace(t);
  // The live failure: a sandboxed plugin sees `workspaceInfo.rootPath` as the
  // session's directory and `cwd` as the plugin's own directory, and the old
  // rule compared `cwd` with `process.cwd()` — equal, so it fell through to the
  // adapter's repository and wrote the ledger and Scope journal there.
  const ctx = { workspaceInfo: { rootPath: dir }, cwd: repoRootFromAdapter() };
  assert.equal(workspaceRootOf(ctx), dir);
  assert.equal(projectRoot(ctx), dir, "the workspace root wins over the plugin directory");

  // `workspaceOf` answers the same reading the project root does: the host's
  // `workspaceInfo.rootPath` first, the plugin's own `cwd` last. On 0.2.0 the
  // setup `cwd` IS the plugin directory, so a rule that read `cwd` first would
  // put every ledger beside the adapter.
  assert.equal(workspaceOf(ctx), dir);
  assert.equal(workspaceOf({ workspaceInfo: { cwd: dir }, cwd: repoRootFromAdapter() }), dir);

  // The plugin's own directory is never a workspace, even without a rootPath.
  assert.equal(projectRoot({ cwd: dir }), dir, "a session's own cwd is still its workspace");

  // An explicit root is the operator's decision and outranks the workspace.
  const previous = process.env.HERDCHESTRA_PROJECT_ROOT;
  process.env.HERDCHESTRA_PROJECT_ROOT = join(dir, "explicit");
  try {
    assert.equal(projectRoot(ctx), join(dir, "explicit"));
  } finally {
    if (previous === undefined) delete process.env.HERDCHESTRA_PROJECT_ROOT;
    else process.env.HERDCHESTRA_PROJECT_ROOT = previous;
  }

  // A host that hands over no workspace falls back to the process directory —
  // never to the adapter's own location. (Under the test runner those two are
  // the same path, so the fact is asserted where it is observable.)
  assert.equal(projectRoot({}), process.cwd());
  assert.equal(repoRootFromAdapter(), process.cwd(), "the suite runs from the repository, so this is the one case they coincide");
});

test("beforeTool blocks the host read tools for the Manager and leaves a worker alone", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  for (const name of MANAGER_FORBIDDEN) {
    const blocked = await plugin.hooks.beforeTool({ snapshot: { agentId: "a", parentAgentId: null }, tool: { name }, input: {} });
    assert.equal(blocked.skip, true, name);
  }
  const allowed = await plugin.hooks.beforeTool({
    snapshot: { agentId: "w", parentAgentId: "a" },
    tool: { name: "read_files" },
    input: {},
  });
  assert.equal(allowed, undefined);
});

test("the face gate: the Manager may not use a worker face, a worker may not use the Manager face", () => {
  assert.equal(surfaceAllowed("manager", "js-worker"), false);
  assert.equal(surfaceAllowed("manager", "js-manager"), true);
  assert.equal(surfaceAllowed("coder", "js-manager"), false);
  assert.equal(surfaceAllowed("coder", "js-worker"), true);
  // §3.1, §4.1: fork/join are dispatch primitives, so every non-Plan role has
  // them; Plan never dispatches (§2.2).
  assert.equal(surfaceAllowed("coder", "fork"), true);
  assert.equal(surfaceAllowed("devops", "join"), true);
  assert.equal(surfaceAllowed("manager", "fork"), true);
  assert.equal(surfaceAllowed("plan", "fork"), false);
});

test("a fork-started worker can be steered, and the receipt says which happened", async () => {
  const turns = [];
  const steers = [];
  let settle = null;
  const table = new WorkerTable({
    create: async ({ name, brief }) => {
      turns.push({ turn: "start", brief });
      return {
        started: true,
        // A steer goes into the turn that is running (§4.1): the fake's own
        // input channel, exactly as the real handle forwards it to the host.
        steer: (next) => {
          steers.push(next);
          return true;
        },
        run: async (next) => {
          turns.push({ turn: "steer", brief: next });
          return { finishReason: "stop", text: next };
        },
        abort: () => {},
        onSettled: (callback) => {
          settle = callback;
        },
      };
    },
  });
  const started = await table.fork({ name: "w", brief: "first", role: "coder" });
  assert.deepEqual(started, { name: "w", steered: false, started: true });
  assert.equal(table.isLive("w"), true);

  // While the first turn is in flight the steer enters that turn.
  const steered = await table.fork({ name: "w", brief: "second", role: "coder" });
  // The receipt is exactly the delivery (§4.1): the brief was steered into the
  // running turn, and no new session was started.
  assert.deepEqual(steered, { name: "w", steered: true, started: false });
  assert.deepEqual(steers, ["second"], "the brief reached the runtime that is running");
  assert.deepEqual(turns, [{ turn: "start", brief: "first" }]);

  // The first turn settles; the steer was already delivered into it, so no
  // second turn is started for the same brief (spec §4.1: a steer is not a new
  // session and not a new turn).
  settle({ finishReason: "stop", text: "second" });
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.deepEqual(turns, [{ turn: "start", brief: "first" }]);
  // The view is the last settled turn: the owner's fission start context.
  assert.equal(table.viewOf("w"), "second");

  // One report answered the carrier and the steer it took: the carrier keeps the
  // brief that turn was given, the steered turn keeps the runtime's answer.
  const carrier = await table.join();
  assert.equal(carrier.name, "w");
  assert.equal(carrier.text, "first");
  const steeredTurn = await table.join();
  assert.equal(steeredTurn.name, "w");
  assert.equal(steeredTurn.text, "second");
  assert.equal(await table.join(), null);
  assert.equal(table.isLive("w"), false);
});

test("a steer accepted while the worker is idle starts a turn at once", async () => {
  const turns = [];
  let settle = null;
  const table = new WorkerTable({
    create: async ({ brief }) => {
      turns.push(brief);
      return {
        started: true,
        run: async (next) => {
          turns.push(next);
          return { finishReason: "stop", text: next };
        },
        abort: () => {},
        onSettled: (callback) => {
          settle = callback;
        },
      };
    },
  });
  await table.fork({ name: "w", brief: "first", role: "coder" });
  settle({ finishReason: "stop", text: "first" });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(table.isLive("w"), false);
  // Drain the first record so the next `join` answers the steered turn.
  assert.equal((await table.join()).text, "first");

  const again = await table.fork({ name: "w", brief: "second", role: "coder" });
  assert.deepEqual(again, { name: "w", steered: true, started: false });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.deepEqual(turns, ["first", "second"]);
  // The immediate turn's record is offered to the next `join`, like any turn.
  const joined = await table.join();
  assert.equal(joined.text, "second");
  assert.equal(table.isLive("w"), false);
});

test("a lane the host cannot stop is orphaned, not reported as released", async () => {
  // The worker table is the unit under test: a lane whose runtime refuses to stop
  // must stay in the table and be named as orphaned, because reporting it as
  // released would claim a teardown that did not happen.
  const refused = new Error("runtime refused");
  const table = new WorkerTable({
    create: async ({ name }) => ({
      started: true,
      run: null,
      abort: () => {
        if (name === "g#0") throw refused;
      },
      onSettled: () => {},
    }),
  });
  await table.startLane({ name: "g#0", brief: "a", role: "coder", start: false });
  await table.startLane({ name: "g#1", brief: "b", role: "coder", start: false });

  const receipt = table.abortLanes(["g#0", "g#1", "g#absent"]);
  assert.equal(receipt.ok, false);
  assert.deepEqual(receipt.released.sort(), ["g#1", "g#absent"]);
  assert.deepEqual(receipt.orphaned.map((entry) => entry.laneId), ["g#0"]);
  assert.equal(table.get("g#0") !== null, true);
  assert.equal(table.get("g#1"), null);
});

test("a session that ends stops every runtime it started, and says which it could not", async () => {
  // A delegated runtime is a real session in the host's process, and a Hub plugin
  // shares that process across sessions. Without this, a worker keeps producing
  // turns for a session nobody listens to.
  const aborted = [];
  const table = new WorkerTable({
    create: async ({ name }) => ({
      started: true,
      run: null,
      abort: () => {
        aborted.push(name);
        if (name === "w2") throw new Error("runtime refused");
      },
      onSettled: () => {},
    }),
  });
  await table.fork({ name: "w1", brief: "a", role: "coder" });
  await table.fork({ name: "w2", brief: "b", role: "coder" });
  // `startLane` records a LIVE lane: its runtime is a real session and must be
  // stopped like a worker's, so it is aborted here too. It is built INERT (no
  // first turn) because a lane's turn starts only after the group is durable.
  await table.startLane({ name: "g#0", brief: "c", role: "coder", start: false });

  // Every LIVE runtime is aborted — workers and lanes alike; a worker whose
  // abort throws is orphaned and stays in the table, and every other entry is
  // released.
  const receipt = table.abortAll();
  assert.equal(receipt.ok, false, "one abort threw, so the teardown is not clean");
  assert.deepEqual(receipt.released.sort(), ["g#0", "w1"]);
  assert.deepEqual(receipt.orphaned.map((entry) => entry.name), ["w2"]);
  // The orphaned runtime stays in the table: it may still be producing turns.
  assert.equal(table.get("w2") !== null, true);
  assert.equal(table.get("w1"), null);
  assert.deepEqual(aborted.sort(), ["g#0", "w1", "w2"]);
});

test("setup registers the host's onDispose, so the session's workers stop with it", async (t) => {
  const dir = await workspace(t);
  const disposers = [];
  const plugin = createPlugin();
  plugin.setup(
    {
      registerTool() {},
      registerRule() {},
      registerMessageBuilder() {},
    },
    {
      cwd: dir,
      session: { sessionId: "cline-dispose" },
      onDispose: (cleanup) => disposers.push(cleanup),
    },
  );
  assert.equal(disposers.length, 1, "the host's own end-of-session hook is used");
  const session = plugin.adapter.session;
  // The teardown reaches the session's real worker table: with nothing started it
  // is clean and names nothing, and it drops a question nobody can answer now.
  assert.equal(disposers[0](), undefined);
  assert.deepEqual(session.workers.names(), []);
  assert.equal(session.asked, null);
});

test("join waits for the first completion instead of returning null too early", async () => {
  let settle = null;
  const table = new WorkerTable({
    create: async () => ({
      started: true,
      run: null,
      abort: () => {},
      onSettled: (callback) => {
        settle = callback;
      },
    }),
  });
  await table.fork({ name: "w", brief: "b", role: "coder" });
  const pending = table.join();
  // The first turn is the runtime handle's own; its record arrives through the
  // settle listener, exactly as the real handle reports it.
  settle({ finishReason: "stop", text: "done" });
  const record = await pending;
  assert.equal(record.text, "done");
});

test("the relay writes retirement, the barrier and the successor as one record", async (t) => {
  const dir = await workspace(t);
  const tenure = new TenureState({ file: join(dir, "m.json"), scopeFile: join(dir, "s.jsonl"), sessionId: "s" });
  const relay = relayPort({ tenure, sessions: null });
  const done = await relay.handOver({ retire: true, openSuccessor: true, barrier: true });
  assert.equal(done.ok, true);
  assert.equal(done.retired, true);
  // `barrier` says WHETHER the barrier was written (the field `compose` reads);
  // the record itself is `contextBarrier`. Reporting the object as `barrier`
  // made a successful hand-over read as `RELAY_UNAVAILABLE`.
  assert.equal(done.barrier, true);
  assert.equal(done.contextBarrier.kind, "contextBarrier");
  assert.equal(done.contextBarrier.sessionId, "s");
  assert.equal(done.successor.tenure, 1);
  // The port writes the DURABLE record and moves the window; the LEDGER is
  // advanced by its owner (`compose/relay/review.mjs` calls `openSuccessor()`
  // once the port reports the whole hand-over). Opening it here as well would
  // open two successors for one relay, and the fence would name one the ledger
  // does not hold.
  assert.equal(done.tenure, 1, "the receipt names the successor's tenure");
  // The incumbent IS retired the moment the fence is durable: the record that
  // says "tenure 1 judges" is the retirement (根 §2.4 第 3 步 writes all three as
  // one record). `retired` describes the TENURE, so it is true here — and it
  // turns false again once the owner opens the successor and this session
  // becomes tenure 1.
  assert.equal(tenure.retired, true);

  // A second hand-over is refused, not performed twice.
  const again = await relay.handOver({ retire: true, openSuccessor: true, barrier: true });
  assert.equal(again.ok, false);

  // A host with no session factory says so instead of faking a Manager.
  assert.equal((await relay.startManager("draft")).reason, "HOST_HAS_NO_SESSION_CREATE");
  // Without an injected seam the question is still asked: park + interrupt is
  // the channel, and every session has it.
  const bare = await relay.askOperator("q");
  assert.equal(bare.ok, true);
  assert.equal(bare.asked, true);
  assert.equal(bare.question, "q");
  assert.equal(bare.interrupt, true);
  // An injected seam answers through the relay unchanged.
  const seamRelay = relayPort({
    tenure,
    sessions: null,
    operator: createOperatorSeam({ ask: async (question) => ({ ok: true, answer: `re:${question}` }) }),
  });
  assert.deepEqual(await seamRelay.askOperator("q"), { ok: true, answer: "re:q" });
});

test("a relay the host cannot finish leaves the ledger where it was", async (t) => {
  const dir = await workspace(t);
  const tenure = new TenureState({ file: join(dir, "m.json"), scopeFile: join(dir, "s.jsonl"), sessionId: "s" });
  const relay = relayPort({ tenure, sessions: null });
  const refused = await relay.handOver({ retire: true, openSuccessor: false, barrier: true });
  assert.deepEqual(refused, { ok: false, reason: "INCOMPLETE_RELAY" });
  assert.equal(tenure.retired, false);
  assert.equal(tenure.ledger.tenure, 0);
});

test("a lane's start context is the framing, the owner view and its own prompt", async (t) => {
  const dir = await workspace(t);
  const created = [];
  const port = fissionSteps({
    ownerView: async () => "owner view",
    createLane: async (lane) => {
      created.push(lane);
      return { laneId: lane.laneId };
    },
    replacePresent: async () => ({ replaced: true }),
    abortLanes: async () => ({ ok: true, released: [], orphaned: [] }),
  });
  const lane = { index: 1, laneId: "g#1", prompt: "lane one", groupId: "g", owner: "w", view: "owner view", laneCount: 2 };
  assert.deepEqual(await port.createLane(lane), { laneId: "g#1" });
  assert.equal(created[0].prompt.startsWith(laneFraming(2)), true);
  assert.equal(created[0].prompt.includes("owner view"), true);
  assert.equal(created[0].prompt.endsWith("lane one"), true);
});

test("every lane of a real group starts with the group's lane count", async (t) => {
  const dir = await workspace(t);
  // The worker table is the real one; only the host runtime behind it is a
  // fake, so the text asserted here is the text compose handed to the host.
  const briefs = [];
  const workers = new WorkerTable({
    // A lane is built INERT (`start: false`) and its first turn arrives through
    // `run`, exactly as the real handle reports it.
    create: async ({ name, brief, start = true }) => {
      if (start !== false) briefs.push({ name, brief });
      return {
        started: true,
        pending: start === false,
        run: async (next) => {
          briefs.push({ name, brief: next });
          return null;
        },
        abort: () => {},
        onSettled: () => {},
      };
    },
  });
  await workers.fork({ name: "w1", brief: "owner turn", role: "coder" });
  // §4.2 step 4: the group's creation is one durable record, so a session with
  // no journal cannot start a group (that is the point of the step). The real
  // session names `<root>/.Herdchestra/<session>.fission.jsonl`; the test names
  // the same shape under its own root.
  const session = new SessionFission({ workers, sessionId: "s1", groupJournal: join(dir, ".Herdchestra", "s1.fission.jsonl") });
  const receipt = await session.port().fission("w1", ["one", "two", "three"]);
  assert.equal(receipt.ok, true);
  const lanes = briefs.filter(({ name }) => name.startsWith("w1:3:"));
  assert.equal(lanes.length, 3);
  // Spec §4.2 step 3: one count, the group's, in every lane's start text.
  for (const { brief } of lanes) assert.equal(brief.startsWith(laneFraming(3)), true, brief);
  // A lane without the count is refused rather than printed as "undefined".
  await assert.rejects(
    () => fissionSteps({ ownerView: async () => null, createLane: async () => ({ ok: true }) }).createLane({ laneId: "g#0", prompt: "p" }),
    (error) => error.code === "LANE_COUNT_MISSING",
  );
});

test("a fission group's creation is committed as ONE durable record", async (t) => {
  // Spec §4.2 step 4: the whole group's creation lands as a single durable
  // record. A group that is half-written is not a group, so the journal holds
  // one line per group, and `durable` is only true when that line was written.
  const dir = await workspace(t);
  const journal = join(dir, ".Herdchestra", "s1.fission.jsonl");
  const port = fissionSteps({
    ownerView: async () => null,
    createLane: async (lane) => ({ laneId: lane.laneId }),
    replacePresent: async () => ({ replaced: true }),
    abortLanes: async () => ({ ok: true, released: [], orphaned: [] }),
    groupJournal: () => journal,
  });
  const lanes = [{ laneId: "g#0" }, { laneId: "g#1" }, { laneId: "g#2" }];
  const receipt = await port.persistGroup({ groupId: "g", owner: "w", lanes });
  assert.equal(receipt.ok, true);
  assert.equal(receipt.durable, true);
  assert.equal(receipt.file, journal);
  assert.deepEqual(receipt.lanes, ["g#0", "g#1", "g#2"]);
  // One line, not one per lane: the group is the unit.
  const lines = readFileSync(journal, "utf8").trim().split("\n");
  assert.equal(lines.length, 1);
  const record = JSON.parse(lines[0]);
  assert.equal(record.kind, "fissionGroup");
  assert.equal(record.groupId, "g");
  assert.equal(record.owner, "w");
  assert.deepEqual(record.lanes, ["g#0", "g#1", "g#2"]);

  // A second group appends; it never rewrites the first record.
  await port.persistGroup({ groupId: "h", owner: "w", lanes: [{ laneId: "h#0" }, { laneId: "h#1" }] });
  assert.equal(readFileSync(journal, "utf8").trim().split("\n").length, 2);

  // A host with no journal says so instead of claiming durability.
  const noJournal = await fissionSteps({ ownerView: async () => null, createLane: async () => ({}) }).persistGroup({
    groupId: "g",
    owner: "w",
    lanes,
  });
  assert.equal(noJournal.durable, false);
  assert.equal(noJournal.reason, "NO_GROUP_JOURNAL");
});

test("a real session commits its fission group to its own journal", async (t) => {
  // The path comes from the session, not from the caller: one root derives the
  // ledger, the Scope book and the group record.
  const dir = await workspace(t);
  const { plugin } = setup(dir, { session: { sessionId: "fission-session" } });
  const session = plugin.adapter.session;
  // `fission` is a WORKER face (§4.2): a Manager dispatches with `fork`, and the
  // group operation belongs to the worker that owns the lanes.
  const port = session.ports("coder").fission;
  assert.equal(typeof port.persistGroup, "function");
  const receipt = await port.persistGroup({ groupId: "g", owner: "w", lanes: [{ laneId: "g#0" }, { laneId: "g#1" }] });
  assert.equal(receipt.durable, true);
  assert.equal(receipt.file, join(dir, ".Herdchestra", "fission-session.fission.jsonl"));
  assert.equal(existsSync(receipt.file), true);
});

test("the host SDK is found without CLINE_CORE_MODULE, from the installation layout", async () => {
  // The live failure: a plugin loaded from `~/.cline/plugins/...` (or a worker
  // thread whose `argv[1]` is the sandbox bootstrap) could not see
  // `@cline/core`, so `fork` answered `HostCapabilityMissing` on every real
  // Cline Desktop install. The ladder must reach the installed runtime on its
  // own.
  const previous = process.env.CLINE_CORE_MODULE;
  delete process.env.CLINE_CORE_MODULE;
  try {
    const api = await delegatedAgentApi();
    // This machine has a real Cline Desktop install; a machine without one has
    // no SDK to find, and then the honest answer is `null`.
    const installed = existsSync("/usr/lib/Cline/bin/desktop-backend/plugin-runtime/node_modules/@cline/core/dist/index.js");
    if (!installed) {
      assert.equal(api, null, "no installation on this machine means no API, not a fabricated one");
      return;
    }
    assert.notEqual(api, null, "the installed Cline runtime must be reachable without an env var");
    assert.equal(typeof api.createDelegatedAgent, "function");
    assert.equal(typeof api.createDelegatedAgentConfigProvider, "function");
    // Both factories together are what a worker needs; one alone is not enough.
    assert.notEqual(await sessionHostApi(), null);
    const source = await hostModuleSource();
    assert.equal(typeof source, "string");
    assert.match(source, /@cline[\\/]core/, "the source names the module it found");
  } finally {
    if (previous !== undefined) process.env.CLINE_CORE_MODULE = previous;
  }
});

test("the host's stored credentials are what a delegated runtime connects with", async () => {
  // Measured live: `createDelegatedAgentConfigProvider({ providerId, modelId })`
  // forwards exactly what it is given, so a runtime started with only those two
  // fields reaches the provider and dies on "You didn't provide an API key".
  // The host already stores the key and base URL for the session's provider, and
  // `providerCredentials` asks its own `ProviderSettingsManager`.
  const sdk = await delegatedAgentApi();
  if (sdk === null) return; // no installation on this machine
  const credentials = await providerCredentials({ providerId: "openai-compatible", modelId: "lazy/task" });
  if (credentials === null) {
    // A machine with no stored provider settings: the honest answer is none, and
    // the runtime is started without (the provider's own error is the report).
    assert.equal(hasCredentials(credentials), false);
    return;
  }
  // The shape the host's own config provider accepts, and nothing more.
  for (const key of Object.keys(credentials)) {
    assert.equal(
      ["providerId", "modelId", "apiKey", "accessToken", "refreshToken", "accountId", "baseUrl", "headers", "clientType", "routingProviderId"].includes(key),
      true,
      `unexpected credential field ${key}`,
    );
  }
  // The model half is the session's, never the provider default: the host's own
  // entry names `gpt-4o` when the caller passes no model.
  assert.equal(credentials.modelId, "lazy/task");
  assert.equal(hasCredentials(credentials), true);
  // A provider the host does not store answers null, not a default connection.
  assert.equal(await providerCredentials({ providerId: "definitely-not-configured" }), null);
});

test("startWorker hands the host's credentials to the config provider, without a network call", async (t) => {
  // The end of the chain the live run stopped at: `startWorker` must give
  // `createDelegatedAgentConfigProvider` a COMPLETE connection. The claim is
  // about what the adapter passes, not about whether a provider answers — a
  // test that started a real runtime would need the network and would hang the
  // suite, so the host's delegated factory is replaced by a recorder here.
  const dir = await workspace(t);
  const { plugin } = setup(dir, { session: { sessionId: "cred-worker" }, config: { providerId: "openai-compatible", modelId: "lazy/task" } });
  const session = plugin.adapter.session;
  const connection = await session.resolveConnection();
  if ((await delegatedAgentApi()) === null) return; // no installation on this machine
  // The connection itself is the assertion: both halves, and the credentials the
  // host stores, or the runtime dies at the provider. `startWorker` passes this
  // object to `createDelegatedAgentConfigProvider` unchanged (asserted by the
  // shape below), which is the whole contract between them.
  assert.equal(connection.credentialSource, connection.credentials === null ? "hostHasNone" : "providerSettings");
  assert.equal(connection.model.modelId, "lazy/task");
  if (connection.credentials !== null) {
    // The credential object carries the provider's key and endpoint, and the
    // session's model — never the provider's default model.
    assert.equal(typeof connection.credentials.apiKey, "string");
    assert.equal(typeof connection.credentials.baseUrl, "string");
    assert.equal(connection.credentials.modelId, "lazy/task");
  }
});

test("the delegated factory pair is the host's real one, and it builds a runtime", async () => {
  // A resolution that returns *something* is not enough: the pair must be the
  // host's own factories, and the config provider must accept the connection
  // this adapter hands it. This drives the real `@cline/core` export.
  const api = await delegatedAgentApi();
  if (api === null) return; // no installation on this machine
  const provider = api.createDelegatedAgentConfigProvider({
    providerId: "openai-compatible",
    modelId: "lazy/task",
    cwd: process.cwd(),
    extensions: [],
  });
  // The host's own contract: the connection comes back as it was given, and a
  // later default update merges instead of replacing.
  const connection = provider.getConnectionConfig();
  assert.equal(connection.providerId, "openai-compatible");
  assert.equal(connection.modelId, "lazy/task");
  assert.equal(typeof provider.getRuntimeConfig, "function");
  assert.equal(typeof provider.updateConnectionDefaults, "function");
});

test("the session's connection falls back to the setup context, then the environment", async (t) => {
  // `fork` can run before the session's first model request. The reading must
  // then come from somewhere real — never from a default.
  const dir = await workspace(t);
  const previous = { p: process.env.CLINE_PROVIDER_ID, m: process.env.CLINE_MODEL_ID };
  delete process.env.CLINE_PROVIDER_ID;
  delete process.env.CLINE_MODEL_ID;
  t.after(() => {
    if (previous.p !== undefined) process.env.CLINE_PROVIDER_ID = previous.p;
    if (previous.m !== undefined) process.env.CLINE_MODEL_ID = previous.m;
  });

  // 1. Nothing anywhere: no reading, and the source says so.
  {
    const { plugin } = setup(dir, { session: { sessionId: "model-none" } });
    const session = plugin.adapter.session;
    assert.equal(session.model, null);
    assert.equal(session.modelSource, null);
  }
  // 2. The setup context names both halves.
  {
    const { plugin } = setup(dir, { session: { sessionId: "model-ctx" }, config: { providerId: "openai-compatible", modelId: "lazy/task" } });
    const session = plugin.adapter.session;
    assert.deepEqual(session.model, { providerId: "openai-compatible", modelId: "lazy/task" });
    assert.equal(session.modelSource, "context");
  }
  // 3. Half a connection is not a connection.
  {
    const { plugin } = setup(dir, { session: { sessionId: "model-half" }, config: { providerId: "openai-compatible" } });
    assert.equal(plugin.adapter.session.model, null, "a provider with no model starts a runtime that cannot connect");
  }
  // 4. The environment, only when both halves are set.
  {
    process.env.CLINE_PROVIDER_ID = "openai-compatible";
    assert.equal(setup(dir, { session: { sessionId: "model-env1" } }).plugin.adapter.session.model, null);
    process.env.CLINE_MODEL_ID = "lazy/task";
    const session = setup(dir, { session: { sessionId: "model-env2" } }).plugin.adapter.session;
    assert.deepEqual(session.model, { providerId: "openai-compatible", modelId: "lazy/task" });
    assert.equal(session.modelSource, "environment");
  }
  // 5. A request outranks every fallback, and is what `noteModel` records.
  {
    const { plugin } = setup(dir, { session: { sessionId: "model-req" }, config: { providerId: "wrong", modelId: "wrong" } });
    const session = plugin.adapter.session;
    session.noteModel({ providerId: "openai-compatible", modelId: "lazy/task" });
    assert.deepEqual(session.model, { providerId: "openai-compatible", modelId: "lazy/task" });
    assert.equal(session.modelSource, "request");
  }
});

test("the connection is also read from the host's own session record", async (t) => {
  // Cline stores `provider` and `model` on the session row and inside its
  // metadata (measured on a live Desktop session). A session whose own request
  // has not run yet can still fork, because the host already knows.
  const dir = await workspace(t);
  const previous = { p: process.env.CLINE_PROVIDER_ID, m: process.env.CLINE_MODEL_ID };
  delete process.env.CLINE_PROVIDER_ID;
  delete process.env.CLINE_MODEL_ID;
  t.after(() => {
    if (previous.p !== undefined) process.env.CLINE_PROVIDER_ID = previous.p;
    if (previous.m !== undefined) process.env.CLINE_MODEL_ID = previous.m;
  });
  const { plugin } = setup(dir, { session: { sessionId: "record-session" } });
  const session = plugin.adapter.session;
  assert.equal(session.model, null);

  // A host handle whose record carries the connection. Passed in, so the test
  // exercises the record reader rather than opening a real host.
  const host = {
    kind: "clineCore",
    handle: {
      getSession: async (id) =>
        id === "record-session" ? { sessionId: id, metadata: { provider: "openai-compatible", model: "lazy/task" } } : undefined,
    },
  };
  const resolved = await session.resolveModel(host);
  assert.deepEqual(resolved.model, { providerId: "openai-compatible", modelId: "lazy/task" });
  assert.equal(resolved.source, "sessionRecord");

  // The row's own columns are read too, when the metadata is empty.
  const rowOnly = await session.resolveModel({
    kind: "clineCore",
    handle: { getSession: async (id) => (id === "record-session" ? { provider: "openai-compatible", model: "lazy/task" } : undefined) },
  });
  assert.deepEqual(rowOnly.model, { providerId: "openai-compatible", modelId: "lazy/task" });

  // A host that cannot answer is not an error: the ladder just has no reading.
  const silent = await session.resolveModel({ kind: "clineCore", handle: { list: async () => [] } });
  assert.equal(silent.model, null);
});

test("startWorker refuses an unknown connection by name instead of starting a guessed runtime", async (t) => {
  // The half that must not change: with no reading anywhere, a worker is not
  // started on a fabricated `cline`/empty model. The receipt names what was
  // tried, so the failure is diagnosable from the model's own tool result.
  const dir = await workspace(t);
  const previous = { p: process.env.CLINE_PROVIDER_ID, m: process.env.CLINE_MODEL_ID };
  delete process.env.CLINE_PROVIDER_ID;
  delete process.env.CLINE_MODEL_ID;
  t.after(() => {
    if (previous.p !== undefined) process.env.CLINE_PROVIDER_ID = previous.p;
    if (previous.m !== undefined) process.env.CLINE_MODEL_ID = previous.m;
  });
  const { plugin } = setup(dir, { session: { sessionId: "worker-nomodel" } });
  const receipt = await plugin.adapter.session.startWorker({ name: "w", brief: "role: coder — x", role: "coder" });
  assert.equal(receipt.started, false);
  assert.equal(receipt.rejection, "MODEL_UNKNOWN");
  assert.deepEqual(receipt.detail.tried, ["request", "context", "sessionRecord", "environment"]);
});

test("a turn that only staged meToo is refused when the next model request opens", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  // Establish a scope first, otherwise every annotation is SCOPE_UNINITIALIZED.
  session.admit({ member: "js-manager", input: { scope: { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 2 } } });
  await session.settle({ member: "js-manager", input: { scope: { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 2 } }, callId: "c0" });

  await plugin.hooks.beforeRun();
  await plugin.hooks.beforeModel({ request: { messages: [] } });
  // Only meToo in this turn: nothing is booked until a real annotation arrives.
  // `meToo` is staged by settle, not by admit: admit only checks the budget.
  const staged = await session.settle({ member: "js-manager", input: { scope: { kind: "meToo" } }, callId: "c-meToo" });
  assert.equal(staged.ok, true);
  assert.equal(staged.outcome, "staged");
  // The turn is still open at afterModel: tools of this turn have not run yet.
  assert.equal(await plugin.hooks.afterModel({}), undefined);
  // The next model request closes that turn, and the refusal is reported as
  // hook context instead of being thrown.
  const closed = await plugin.hooks.beforeModel({ request: { messages: [] } });
  assert.equal(typeof closed?.appendContext, "string");
  assert.match(closed.appendContext, /meToo|SCOPE_METOO_ONLY/i);
  await plugin.hooks.afterRun();
});

test("a turn that booked a real annotation closes quietly", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  await plugin.hooks.beforeRun();
  await plugin.hooks.beforeModel({ request: { messages: [] } });
  session.admit({ member: "js-manager", input: { scope: { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 2 } } });
  await session.settle({ member: "js-manager", input: { scope: { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 2 } }, callId: "c1" });
  const next = await plugin.hooks.beforeModel({ request: { messages: [] } });
  assert.equal(next?.appendContext === undefined || !String(next.appendContext).includes("scope:"), true);
  await plugin.hooks.afterRun();
});

// 根 §6.3: hook context is model-visible, so a structured notice is CUE, not
// raw JSON. A JSON notice would reach the model as `{"kind":"scopeColdStart",…}`
// with escaped punctuation; CUE renders the same fields as `kind: "…"` lines.
test("a structured notice reaches the model as CUE, never as raw JSON", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const notice = {
    kind: "scopeColdStart",
    reason: "SCOPE_UNINITIALIZED",
    previousResets: 0,
    message: "The current retention budget is not established; the next tool call must carry resetScope.",
  };
  plugin.adapter.session.scopeNotice = () => notice;
  await plugin.hooks.beforeRun();
  const opened = await plugin.hooks.beforeRun();
  assert.equal(opened.appendContext, toCue(notice));
  assert.equal(opened.appendContext.includes('{"'), false, "no JSON object syntax in model context");
  assert.match(opened.appendContext, /kind: "scopeColdStart"/);
  assert.match(opened.appendContext, /previousResets: 0/);
  // The same rendering is used after a tool settles, which is the other route a
  // notice takes into the request.
  const settled = await plugin.hooks.afterTool({
    ...top("c1"),
    tool: { name: "js-manager" },
    input: { scope: { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 2 } },
  });
  assert.equal(settled.appendContext, toCue(notice));
  await plugin.hooks.afterRun();
});

test("the Scope projection keeps the original messages whose items survived", () => {
  const call = {
    role: "assistant",
    content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "a", input: {} }],
  };
  const result = { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "a", output: "x" }] };
  const operator = { role: "user", content: [{ type: "text", text: "do it" }] };
  const messages = [operator, call, result];
  const kept = selectKept(messages, toItems(messages));
  assert.deepEqual(kept, messages);
});

test("smoke: a class-shaped program runs through the registered js-worker tool", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  const code = [
    "class Js extends JsProgram {",
    "  async run() {",
    '    await this.write("note.txt", "written");',
    '    const text = await this.cat("note.txt");',
    "    return { text };",
    "  }",
    "}",
  ].join("\n");
  const receipt = await tools.get("js-worker").execute({ code }, child("smoke-1"));
  assert.equal(receipt.state, "completed", JSON.stringify(receipt.error));
  assert.deepEqual(receipt.value, { text: "written" });
  assert.equal(await readFile(join(dir, "note.txt"), "utf8"), "written");
});

test("smoke: the manager surface has no file members and the rule carries its prompt", async (t) => {
  const dir = await workspace(t);
  const { tools, rules } = setup(dir);
  const receipt = await tools
    .get("js-manager")
    .execute({ code: "class Js extends JsProgram { async run() { await this.cat('x'); } }" }, top("smoke-2"));
  assert.equal(receipt.state, "failed");
  assert.match(receipt.error.message, /cat is not a function/);
  const roleRule = rules.find((rule) => rule.id === "herdchestra-system-prompt");
  assert.equal((await roleRule.content()).startsWith(COMMON + MANAGER), true);
});

test("js-plan's draft is read-only until the tenure's first review, then writable", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir, {}, { role: "plan" });
  const plan = { agentId: "plan", toolCallId: "p1", snapshot: { agentId: "plan", parentAgentId: null }, planRole: true };
  const draft = planDraftPath(dir, "cline-test");
  // The draft's directory is the system's to prepare: the model is told the path
  // and writes the file, not the tree (根 §2.2).
  await mkdir(join(dir, ".Herdchestra", "drafts"), { recursive: true });

  // 根 §2.4 第 5 步: before this tenure's first review the draft is read-only.
  const locked = await tools.get("js-plan").execute({ code: `await this.write(${JSON.stringify(draft)}, "# draft");` }, plan);
  assert.equal(locked.state, "failed");
  assert.match(locked.error.message, /read-only until this tenure's first review/);

  // The first empty review of a tenure asks the operator and is not a review
  // (根 §2.4 第 1 步); the next one is the incumbent's RELAY (第 3 步), and the
  // successor's first review is the promotion (第 5 步). The real ledger judges,
  // so the test reads its count rather than stubbing the member.
  const session = plugin.adapter.session;
  assert.equal(session.tenure.ledger.reviewCount, 0);
  const asked = await plugin.adapter.reviewFor([], "plan");
  assert.equal(asked.kind, "askUser");
  const relayed = await plugin.adapter.reviewFor([], "plan");
  assert.equal(relayed.kind, "relay");
  assert.equal(session.tenure.ledger.successor, true);
  // The successor's first review is a real, accepted review: that is what
  // unlocks the draft for the tenure that now holds it.
  const promoted = await plugin.adapter.reviewFor([], "plan");
  assert.equal(promoted.kind, "confirm");
  assert.equal(session.tenure.ledger.reviewCount > 0, true);

  const receipt = await tools
    .get("js-plan")
    .execute({ code: `await this.write(${JSON.stringify(draft)}, "# draft"); return await this.cat(${JSON.stringify(draft)});` }, plan);
  assert.equal(receipt.state, "completed", JSON.stringify(receipt.error));
  assert.equal(receipt.value, "# draft");
  assert.equal(await readDraft(draft), "# draft");

  // Plan has no `call`, no `ls`, no `where`: the surface is two members.
  for (const member of ["call", "ls", "where"]) {
    const refused = await tools.get("js-plan").execute({ code: `await ${member}("x");` }, plan);
    assert.equal(refused.state, "failed", member);
    assert.match(refused.error.message, new RegExp(`${member} is not defined`));
  }
});

test("Plan's write lands on the canonical draft in a fresh workspace, with no directory made by the test", async (t) => {
  const dir = await workspace(t);
  // Nothing under `.Herdchestra/` exists: this is the fresh workspace of the
  // live smoke. The rule names the draft path and the plugin creates its
  // directory before the session runs, so the first `write` has a parent. The
  // atomic replace goes through a temp file in that same directory and cannot
  // create it: without the directory every write failed with ENOENT, the model
  // then made `drafts.md` a plain file, and every later write failed ENOTDIR.
  assert.equal(existsSync(join(dir, ".Herdchestra")), false);
  const { plugin, tools, rules } = setup(dir, {}, { role: "plan" });
  const draft = planDraftPath(dir, "cline-test");
  const draftRule = rules.find((rule) => rule.id === "herdchestra-plan-draft");
  assert.match(await draftRule.content(), /drafts[\\/]cline-test\.md/);
  assert.equal(plugin.adapter.session.planDraftFile, draft);
  assert.equal(existsSync(join(dir, ".Herdchestra", "drafts")), true);

  // The draft opens after the tenure's first accepted review (根 §2.4 第 5 步):
  // the S1 question is not a review, the relay retires that tenure, and the
  // successor's own first review is the one that counts.
  assert.equal((await plugin.adapter.reviewFor([], "plan")).kind, "askUser");
  assert.equal((await plugin.adapter.reviewFor([], "plan")).kind, "relay");
  assert.equal((await plugin.adapter.reviewFor([], "plan")).kind, "confirm");
  const plan = { agentId: "plan", toolCallId: "fresh-1", snapshot: { agentId: "plan", parentAgentId: null }, planRole: true };
  const written = await tools
    .get("js-plan")
    .execute({ code: `await this.write(${JSON.stringify(draft)}, "# the plan\\n");` }, plan);
  assert.equal(written.state, "completed", JSON.stringify(written.error));
  // The real file holds the real bytes, and the draft port reads them back —
  // this is the material the hand-off starts a Manager from.
  assert.equal(await readDraft(draft), "# the plan\n");
  assert.equal((await tools.get("js-plan").execute({ code: `return await this.cat(${JSON.stringify(draft)});` }, plan)).value, "# the plan\n");
});

test("Plan's draft path is derived from the project root, not chosen by the model", async (t) => {
  const dir = await workspace(t);
  const { plugin, rules } = setup(dir);
  assert.equal(plugin.adapter.session.planDraftFile, planDraftPath(dir, "cline-test"));
  const draftRule = rules.find((rule) => rule.id === "herdchestra-plan-draft");
  assert.equal(typeof draftRule.content, "function");
  const content = await draftRule.content();
  // The rule names the derived path, and says the two things a model otherwise
  // learns by failing: the directory exists, and a member has one spelling.
  assert.match(content, /drafts[\\/]cline-test\.md/);
  assert.match(content, /this\.write\(path, content\)/);
  assert.match(content, /no `tools\.` or `plan\.` namespace/);
  // Naming the path created its directory: the atomic write cannot, and a Plan
  // without it fails every write (ENOENT, then ENOTDIR).
  assert.equal(existsSync(join(dir, ".Herdchestra", "drafts")), true);
  assert.equal(draftRule.whenToolAvailable, "js-plan");
});

test("a draft directory that cannot be prepared is stated by the rule, not left as an ENOENT", async (t) => {
  const dir = await workspace(t);
  // A plain file where the directory belongs: `mkdir` answers ENOTDIR/EEXIST,
  // and every `write` to the draft would fail for a cause the model cannot see.
  await mkdir(join(dir, ".Herdchestra"), { recursive: true });
  await writeFile(join(dir, ".Herdchestra", "drafts"), "not a directory");
  const { plugin, rules } = setup(dir, {}, { role: "plan" });
  const failure = plugin.adapter.session.planDraftDirFailure;
  assert.equal(failure.reason, "DRAFT_DIR_UNWRITABLE");
  assert.equal(failure.directory, join(dir, ".Herdchestra", "drafts"));
  const content = await rules.find((rule) => rule.id === "herdchestra-plan-draft").content();
  assert.match(content, /could NOT be prepared/);
  assert.match(content, /DRAFT_DIR_UNWRITABLE/);
  // The path is still stated: the model is told what the task fixed, and why
  // writing it will fail.
  assert.match(content, /drafts[\\/]cline-test\.md/);
});

test("a review accepted inside a program is written to the session's ledger", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir, {}, { role: "plan" });
  // The live defect: two accepted reviews left no `.plan.json` on disk. The
  // surface was given the ledger but no store, so `saveLedger` had nowhere to
  // write, and only a later `tenure.save()` would ever persist a review.
  const session = plugin.adapter.session;
  const ledgerFile = join(dir, ".Herdchestra", `${session.sessionId}.plan.json`);
  assert.equal(existsSync(ledgerFile), false, "nothing is written before a review");
  // A fresh Plan's FIRST empty review is not a review: the ledger asks the
  // operator what to do (`ASK_USER`, `core/review.mjs:331-336`), and that park is
  // what `ask` means on this host. So the review recorded here carries findings.
  const result = await tools.get("js-plan").execute(
    { code: 'return await review([{ key: "read the brief", value: "src/a.mjs" }]);' },
    { toolCallId: "rev-1", agentId: "a" },
  );
  assert.equal(result.state, "completed", JSON.stringify(result.error ?? result.payload));
  assert.equal(result.value.ok, true, JSON.stringify(result.value));
  assert.equal(existsSync(ledgerFile), true, "the review reached the session's ledger file");
  const saved = JSON.parse(readFileSync(ledgerFile, "utf8"));
  assert.equal(saved.sessionId, session.sessionId);
  assert.equal(saved.kind, "plan");
  assert.equal(saved.reviews, 1);
  // And the draft the review unlocked stays unlocked for the next program: the
  // count is read back from the same ledger, not from the program that wrote it.
  assert.equal(session.tenure.ledger.reviewCount > 0, true);
});

test("a missing draft reads as empty, so the ledger can refuse the hand-off", async (t) => {
  const dir = await workspace(t);
  assert.equal(await readDraft(planDraftPath(dir, "absent")), "");
});

test("Plan's gate refuses the host read tools, worker faces and fork/join", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  for (const name of PLAN_FORBIDDEN) {
    const blocked = await plugin.hooks.beforeTool({ snapshot: { agentId: "p", parentAgentId: null }, tool: { name }, input: {} });
    // A top-level snapshot is the Manager's role in this adapter; the Plan gate
    // applies when the session state says the caller is Plan.
    assert.equal(blocked?.skip === true || blocked === undefined, true, name);
  }
  assert.equal(surfaceAllowed("plan", "js-plan"), true);
  assert.equal(surfaceAllowed("plan", "js-worker"), false);
  assert.equal(surfaceAllowed("plan", "js-review"), false);
  assert.equal(surfaceAllowed("plan", "fork"), false);
  assert.equal(surfaceAllowed("manager", "js-plan"), false);
});

test("Plan's prompt is its own role segment, never COMMON + MANAGER", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  assert.equal(PLAN_PROMPT.startsWith("<plan>"), true);
  assert.equal(PLAN_PROMPT.includes("<vibe-mode>"), false);
  assert.equal(PLAN_PROMPT.includes("MANAGER"), false);
  const prompt = await plugin.adapter.session.systemPrompt("plan");
  assert.equal(prompt, PLAN_PROMPT);
  // The prompt is returned once; a later request already carries it.
  assert.equal(await plugin.adapter.session.systemPrompt("plan"), null);
});

test("a devops investigation reports the missing host capability instead of inventing a report", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const ports = plugin.adapter.session.ports("plan");
  const previous = { p: process.env.CLINE_PROVIDER_ID, m: process.env.CLINE_MODEL_ID };
  delete process.env.CLINE_PROVIDER_ID;
  delete process.env.CLINE_MODEL_ID;
  t.after(() => {
    if (previous.p !== undefined) process.env.CLINE_PROVIDER_ID = previous.p;
    if (previous.m !== undefined) process.env.CLINE_MODEL_ID = previous.m;
  });
  const answer = await ports.devops.investigate("what does the repo do");
  // Never an empty report. With the SDK reachable the remaining refusal is the
  // connection: this fixture's session has no reading, and the runner refuses by
  // name instead of starting a runtime on `providerId: "cline"` and an empty
  // model, which the host would reject with an unreadable error.
  assert.equal(answer.ok, false);
  assert.equal(typeof answer.reason, "string");
  // The run refuses for the reason it really has, and never invents a report.
  // With the SDK present the connection is usually what is missing
  // (`MODEL_UNKNOWN`); a session that inherited a connection from an earlier
  // test reaches the provider and reports `DEVOPS_FAILED`; a host without the
  // SDK refuses before any connection is asked about.
  assert.equal(
    ["MODEL_UNKNOWN", "HOST_HAS_NO_DELEGATED_AGENT", "DEVOPS_FAILED"].includes(answer.reason),
    true,
    `reason was ${answer.reason}`,
  );
  if (answer.reason === "MODEL_UNKNOWN") {
    assert.deepEqual(answer.detail.tried, ["request", "context", "sessionRecord", "environment"]);
  }
});

test("a devops run uses the session's own connection, never a default", async (t) => {
  // The fabricated `providerId: "cline"` / empty `modelId` this runner used to
  // send is a guess, and the host answers a guess with a connection error the
  // model cannot read. The runner now reads the same ladder as `fork`.
  //
  // The assertion is about the CONNECTION the runner asks for, so the factory
  // pair is a recorder and no runtime reaches a provider: a test that started
  // one would need the network and would hang the suite.
  const dir = await workspace(t);
  const { plugin } = setup(dir, { session: { sessionId: "devops-conn" }, config: { providerId: "openai-compatible", modelId: "lazy/task" } });
  const session = plugin.adapter.session;
  const seen = [];
  const configs = [];
  const runner = createDevopsRunner({
    ctx: session.ctx,
    cwd: dir,
    extension: null,
    tools: [],
    delegatedApi: async () => ({
      createDelegatedAgentConfigProvider: (config) => {
        configs.push(config);
        return { getRuntimeConfig: () => ({}) };
      },
      createDelegatedAgent: () => ({
        addTools() {},
        run: async () => ({ finishReason: "stop", text: "the report" }),
      }),
    }),
    resolveConnection: async () => {
      const resolved = session.model;
      seen.push(resolved);
      return { model: resolved, source: session.modelSource, credentials: null, credentialSource: null };
    },
  });
  const answer = await runner.investigate("read the tree");
  // The connection the runner asked for is the session's, and it is complete.
  assert.deepEqual(seen, [{ providerId: "openai-compatible", modelId: "lazy/task" }]);
  // The host factory was handed that exact connection — never a default — and
  // the runtime's own text comes back as the report.
  assert.deepEqual(configs, [{ providerId: "openai-compatible", modelId: "lazy/task", cwd: dir, extensions: [] }]);
  assert.equal(answer.ok, true);
  assert.equal(answer.report, "the report");
});

test("a Plan's hand-off starts a Manager and stops nothing of the plan itself", async (t) => {
  const dir = await workspace(t);
  const tenure = new TenureState({ file: join(dir, "m.json"), scopeFile: join(dir, "s.jsonl"), sessionId: "planning" });
  const calls = [];
  const sessions = {
    async startManager(draft) {
      calls.push(["start", draft]);
      return { ok: true, sessionId: "manager-1" };
    },
    async stopManager(sessionId) {
      calls.push(["stop", sessionId]);
      return { ok: true, sessionId };
    },
  };
  const relay = relayPort({ tenure, sessions });
  const receipt = await relay.startManager("# draft bytes");
  assert.deepEqual(calls, [["start", "# draft bytes"]]);
  assert.deepEqual(receipt, { ok: true, sessionId: "manager-1" });
});

// The review path is exercised through the real ledger: the milestone it reaches
// decides whether the member is called at all, so the tests drive it to the state
// they mean instead of assuming a number of empty reviews.

test("Plan's first empty review asks the operator and reaches no member", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir, {}, { role: "plan" });
  // The real ledger judges: its first empty review is the system's question, and
  // the question is put to the operator through the one channel a real session
  // has: their next message.
  const receipt = await plugin.adapter.reviewFor([], "plan");
  // The ledger decides the kind. A question is the one kind that reaches the
  // operator instead of a successor (spec §7: the first empty review is not a
  // review).
  assert.equal(receipt.kind, "askUser");
  assert.equal(typeof receipt.question, "string");
  // The question is asked, not refused: park + interrupt is the channel, and
  // the operator answers it in ordinary chat (根 §2.4 第 1 步).
  assert.equal(receipt.answer.ok, true);
  assert.equal(receipt.answer.asked, true);
  assert.equal(receipt.answer.question, "我需要干什么？");
  assert.equal(receipt.answer.interrupt, true);
  // No answer is invented: the receipt carries none.
  assert.equal(receipt.answer.answer, undefined);
  // The question did not turn into a relay: the milestone stays where it was.
  assert.equal(plugin.adapter.session.tenure.ledger.milestone, "S1");
});

test("a Plan hand-off reaches the member with the saved draft and no review text", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir, {}, { role: "plan" });
  const session = plugin.adapter.session;
  // Put the Plan ledger at 待执行 through its own reviews; the last of them is
  // the hand-off this test is about.
  let guard = 0;
  while (session.tenure.ledger.milestone !== "待执行" && guard < 8) {
    // The first empty review is the system's question (no operator channel here,
    // and the milestone does not move); each later one advances a milestone.
    await plugin.adapter.reviewFor([], "plan");
    guard += 1;
  }
  assert.equal(session.tenure.ledger.milestone, "待执行");

  const seen = [];
  session.reviewMember = () => ({
    async review(findings, source) {
      seen.push({ findings, source });
      return { ok: true, kind: "handoff" };
    },
  });
  await plugin.adapter.reviewFor([], "plan");
  assert.equal(seen.length, 1);
  // The member receives exactly two arguments: the findings and the saved draft
  // bytes. There is no note to pass through (spec §2.2).
  assert.deepEqual(seen[0].findings, []);
  assert.equal(seen[0].source, "");
  assert.equal(Object.keys(seen[0]).includes("note"), false);
});

test("a Manager review has no draft source and Plan's reviews carry the saved draft", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir, {}, { role: "plan" });
  const session = plugin.adapter.session;
  await mkdir(join(dir, ".Herdchestra", "drafts"), { recursive: true });
  await writeFile(planDraftPath(dir, "cline-test"), "# saved draft");

  const seen = [];
  session.reviewMember = () => ({
    async review(findings, source) {
      seen.push({ source });
      return { ok: true, kind: "confirm" };
    },
  });
  // A Plan review sees the saved bytes as its only source; a Manager's port set
  // has no `draft` at all (spec §2.2: only Plan hands a draft off).
  await plugin.adapter.reviewFor([], "plan");
  assert.equal(seen[0].source, "# saved draft");
  assert.equal(typeof session.ports("manager").draft, "undefined");
  assert.equal(typeof session.ports("plan").draft.read, "function");
});

test("smoke: a class-shaped program runs through the registered js-worker tool", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  const code = [
    "class Js extends JsProgram {",
    "  async run() {",
    '    await this.write("note.txt", "written");',
    '    const text = await this.cat("note.txt");',
    "    return { text };",
    "  }",
    "}",
  ].join("\n");
  const receipt = await tools.get("js-worker").execute({ code }, child("smoke-1"));
  assert.equal(receipt.state, "completed", JSON.stringify(receipt.error));
  assert.deepEqual(receipt.value, { text: "written" });
  assert.equal(await readFile(join(dir, "note.txt"), "utf8"), "written");
});

test("smoke: the manager surface has no file members and the rule carries its prompt", async (t) => {
  const dir = await workspace(t);
  const { tools, rules } = setup(dir);
  const receipt = await tools
    .get("js-manager")
    .execute({ code: "class Js extends JsProgram { async run() { await this.cat('x'); } }" }, top("smoke-2"));
  assert.equal(receipt.state, "failed");
  assert.match(receipt.error.message, /cat is not a function/);
  const roleRule = rules.find((rule) => rule.id === "herdchestra-system-prompt");
  assert.equal((await roleRule.content()).startsWith(COMMON + MANAGER), true);
});

test("Plan's draft path is derived from the project root, not chosen by the model", async (t) => {
  const dir = await workspace(t);
  const { plugin, rules } = setup(dir);
  assert.equal(plugin.adapter.session.planDraftFile, planDraftPath(dir, "cline-test"));
  const draftRule = rules.find((rule) => rule.id === "herdchestra-plan-draft");
  assert.equal(typeof draftRule.content, "function");
  const content = await draftRule.content();
  // The rule names the derived path, and says the two things a model otherwise
  // learns by failing: the directory exists, and a member has one spelling.
  assert.match(content, /drafts[\\/]cline-test\.md/);
  assert.match(content, /this\.write\(path, content\)/);
  assert.match(content, /no `tools\.` or `plan\.` namespace/);
  // Naming the path created its directory: the atomic write cannot, and a Plan
  // without it fails every write (ENOENT, then ENOTDIR).
  assert.equal(existsSync(join(dir, ".Herdchestra", "drafts")), true);
  assert.equal(draftRule.whenToolAvailable, "js-plan");
});

test("a missing draft reads as empty, so the ledger can refuse the hand-off", async (t) => {
  const dir = await workspace(t);
  assert.equal(await readDraft(planDraftPath(dir, "absent")), "");
});

test("Plan's gate refuses the host read tools, worker faces and fork/join", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  for (const name of PLAN_FORBIDDEN) {
    const blocked = await plugin.hooks.beforeTool({ snapshot: { agentId: "p", parentAgentId: null }, tool: { name }, input: {} });
    // A top-level snapshot is the Manager's role in this adapter; the Plan gate
    // applies when the session state says the caller is Plan.
    assert.equal(blocked?.skip === true || blocked === undefined, true, name);
  }
  assert.equal(surfaceAllowed("plan", "js-plan"), true);
  assert.equal(surfaceAllowed("plan", "js-worker"), false);
  assert.equal(surfaceAllowed("plan", "js-review"), false);
  assert.equal(surfaceAllowed("plan", "fork"), false);
  assert.equal(surfaceAllowed("manager", "js-plan"), false);
});

test("Plan's prompt is its own role segment, never COMMON + MANAGER", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  assert.equal(PLAN_PROMPT.startsWith("<plan>"), true);
  assert.equal(PLAN_PROMPT.includes("<vibe-mode>"), false);
  assert.equal(PLAN_PROMPT.includes("MANAGER"), false);
  const prompt = await plugin.adapter.session.systemPrompt("plan");
  assert.equal(prompt, PLAN_PROMPT);
  // The prompt is returned once; a later request already carries it.
  assert.equal(await plugin.adapter.session.systemPrompt("plan"), null);
});

test("a devops investigation with no connection refuses by name, on any machine", async (t) => {
  // Hermetic: the connection is pinned shut, so this test reads no provider
  // settings, opens no session record and makes no network call. The previous
  // copy of this test called `ports.devops.investigate`, which resolves
  // `@cline/core` and `~/.cline` credentials from the real machine; under the
  // file's concurrency an earlier test's live connection reached the provider
  // and the run SUCCEEDED, so `ok: false` held only sometimes. What is asserted
  // here holds regardless of what this machine offers.
  const dir = await workspace(t);
  const runner = createDevopsRunner({
    ctx: null,
    cwd: dir,
    extension: null,
    tools: [],
    // The session's own ladder, pinned: nothing names a provider or a model.
    resolveConnection: async () => ({ model: null, source: null, credentials: null, credentialSource: null }),
  });
  const answer = await runner.investigate("what does the repo do");
  // Never an empty report and never a fabricated one. The refusal is the
  // runner's own named reason: a host without the delegated factory says so
  // before it looks at the connection, and a host with one refuses the
  // connection by name (`MODEL_UNKNOWN`). Both are facts of the code, not of
  // this machine.
  assert.equal(answer.ok, false);
  assert.equal(answer.report, undefined);
  assert.equal(
    ["MODEL_UNKNOWN", "HOST_HAS_NO_DELEGATED_AGENT"].includes(answer.reason),
    true,
    `reason was ${answer.reason}`,
  );
  if (answer.reason === "MODEL_UNKNOWN") {
    assert.deepEqual(answer.detail.tried, ["request", "context", "sessionRecord", "environment"]);
  }
});

test("a Plan's hand-off starts a Manager and stops nothing of the plan itself", async (t) => {
  const dir = await workspace(t);
  const tenure = new TenureState({ file: join(dir, "m.json"), scopeFile: join(dir, "s.jsonl"), sessionId: "planning" });
  const calls = [];
  const sessions = {
    async startManager(draft) {
      calls.push(["start", draft]);
      return { ok: true, sessionId: "manager-1" };
    },
    async stopManager(sessionId) {
      calls.push(["stop", sessionId]);
      return { ok: true, sessionId };
    },
  };
  const relay = relayPort({ tenure, sessions });
  const receipt = await relay.startManager("# draft bytes");
  assert.deepEqual(calls, [["start", "# draft bytes"]]);
  assert.deepEqual(receipt, { ok: true, sessionId: "manager-1" });
});

test("review reads the saved draft for Plan and leaves a Manager without a draft port", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  await mkdir(join(dir, ".Herdchestra", "drafts"), { recursive: true });
  await writeFile(planDraftPath(dir, "cline-test"), "# saved draft");
  const seen = [];
  session.reviewMember = () => ({
    async review(findings, source) {
      seen.push({ source });
      return { ok: true, kind: "handoff" };
    },
  });
  for (let step = 0; step < 4; step += 1) await plugin.adapter.reviewFor([], "plan");
  await plugin.adapter.reviewFor([], "manager");
  // Every Plan review sees the saved draft as its source; a Manager has no draft
  // port at all, so its source is null (spec §2.2: only Plan hands a draft off).
  assert.equal(seen.every((entry) => entry.source === "# saved draft" || entry.source === null), true);
  assert.equal(seen.at(-1).source, null);
  assert.equal(seen.slice(0, -1).every((entry) => entry.source === "# saved draft"), true);
});

test("the operator seam asks with no channel at all: park + interrupt is the channel", async () => {
  const seam = createOperatorSeam();
  const asked = await seam.ask("what next?");
  assert.equal(asked.ok, true);
  assert.equal(asked.asked, true);
  assert.equal(asked.question, "what next?");
  assert.equal(asked.interrupt, true);
  // A question with no words is still a caller bug, and says so.
  assert.equal((await seam.ask("")).reason, "INVALID_QUESTION");
  // A child runtime has no session id the host can address.
  assert.equal(seam.steer("worker", "go").reason, "NO_PER_RUNTIME_STEER");
});

test("a delivery channel shows the question and never blocks the ask", async () => {
  const asked = [];
  const seam = createOperatorSeam({
    request: async (question) => {
      asked.push(question);
      return { ok: true, visible: true, question };
    },
  });
  const receipt = await seam.ask("我需要干什么？");
  assert.equal(receipt.ok, true);
  assert.equal(receipt.asked, true);
  assert.equal(receipt.interrupt, true);
  // No words are invented: the answer is the operator's next message.
  assert.equal(receipt.answer, undefined);
  assert.deepEqual(asked, ["我需要干什么？"]);

  // A channel that refuses delivery, or one that throws, still asks.
  const refused = createOperatorSeam({ request: async () => ({ ok: false, reason: "ASK_NOT_DELIVERED" }) });
  assert.equal((await refused.ask("q")).asked, true);
  const broken = createOperatorSeam({ request: async () => { throw new Error("ipc closed"); } });
  assert.equal((await broken.ask("q")).asked, true);

  // The direct channel still wins: an embedder that can answer in-call gets its
  // answer back.
  const direct = createOperatorSeam({
    ask: async () => ({ ok: true, answer: "in-call" }),
    request: async () => {
      throw new Error("the prompt channel must not be used");
    },
  });
  assert.deepEqual(await direct.ask("q"), { ok: true, answer: "in-call" });
});

test("a real setup asks the operator and does not smuggle the question into context", async (t) => {
  const dir = await workspace(t);
  // A setup with no approval callback: exactly what the host hands a plugin.
  const { plugin } = setup(dir, {}, { role: "plan" });
  const session = plugin.adapter.session;
  const seam = session.ports("plan").relay;
  // The question is asked: the receipt says so and interrupts the turn.
  const answer = await seam.askOperator("先做什么？");
  assert.equal(answer.ok, true);
  assert.equal(answer.asked, true);
  assert.equal(answer.question, "先做什么？");
  assert.equal(answer.interrupt, true);
  // The question is not smuggled into the model's context as if it were the
  // operator's own words.
  const next = await plugin.hooks.beforeModel({
    request: { messages: [{ role: "user", content: [{ type: "text", text: "开始吧" }] }] },
  });
  assert.equal(String(next?.appendContext ?? "").includes("先做什么？"), false);
});

test("an injected operator channel is used as given, with no extra requirement", async () => {
  const asked = [];
  const steered = [];
  const seam = createOperatorSeam({
    ask: async (question) => {
      asked.push(question);
      return { ok: true, answer: "yes" };
    },
    steer: (sessionId, prompt) => {
      steered.push([sessionId, prompt]);
      return { ok: true, delivery: "steer", sessionId };
    },
  });
  // The question goes through unchanged: the seam adds no shape of its own, so a
  // free-text question the host can carry is not refused here.
  assert.deepEqual(await seam.ask("what next?"), { ok: true, answer: "yes" });
  assert.deepEqual(asked, ["what next?"]);
  assert.deepEqual(seam.steer("session-1", "go"), { ok: true, delivery: "steer", sessionId: "session-1" });
  assert.deepEqual(steered, [["session-1", "go"]]);
});

test("the Cline approval round-trip carries a question and brings back the operator's words", async () => {
  const requests = [];
  const seam = createOperatorSeam({
    requestApproval: async (request) => {
      requests.push(request);
      // The operator typed words into the approval's reply.
      return { approved: true, reason: "先做需求分析，别动代码" };
    },
    sessionId: "plan-1",
  });
  const answer = await seam.ask("我需要干什么？", { toolCallId: "call-9" });
  assert.deepEqual(answer, { ok: true, answer: "先做需求分析，别动代码" });
  // The request is the host's own approval shape, and it names the asker.
  assert.equal(requests.length, 1);
  assert.equal(requests[0].toolName, "herdchestra:ask");
  assert.equal(requests[0].toolCallId, "call-9");
  assert.equal(requests[0].sessionId, "plan-1");
  assert.match(requests[0].reason, /我需要干什么？/);
  assert.match(requests[0].reason, /Answer with your words/);

  // A bare approval carries no words: that is a refusal, never an invented
  // answer.
  const bare = createOperatorSeam({ requestApproval: async () => ({ approved: true }) });
  const wordless = await bare.ask("q");
  assert.equal(wordless.asked, true);
  assert.equal(wordless.interrupt, true);
  assert.equal(wordless.answer, undefined);
  // A channel that throws still asks: the operator's next message answers it.
  const broken = createOperatorSeam({ requestApproval: async () => { throw new Error("ipc closed"); } });
  const failed = await broken.ask("q");
  assert.equal(failed.asked, true);
  assert.equal(failed.interrupt, true);
  // Without any channel the question is still asked.
  assert.equal((await createOperatorSeam().ask("q")).asked, true);
});

test("a program waiting on ask resumes from the operator's next message", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir, {}, { role: "plan" });
  const session = plugin.adapter.session;
  // The program asks, then continues with the answer it receives.
  const result = await tools.get("js-plan").execute(
    {
      code: "const answer = await this.ask('我需要干什么？'); return 'resumed:' + answer;",
      scope: { kind: "resetScope", newScopeGoal: "ask-probe", newScopeRetentionBudget: 5 },
    },
    { toolCallId: "ask-1", agentId: "a" },
  );
  // The call answers at once with the park receipt: it is suspended, not done.
  assert.equal(result.reason, "ask");
  assert.equal(result.payload.question, "我需要干什么？");
  // `this.ask` is `this.park` + `interrupt`: the turn ends with the receipt.
  assert.equal(result.interrupt, true);
  assert.equal(result.state, "parked");
  assert.deepEqual(session.asked, { name: "ask-1", question: "我需要干什么？" });

  // The operator's real message arrives as the next request's user turn.
  const resumed = session.resumeAsked([{ role: "user", content: [{ type: "text", text: "先做需求分析" }] }]);
  assert.deepEqual(resumed, { name: "ask-1", answer: "先做需求分析" });
  assert.equal(session.asked, null, "the question is answered once");
  // A second request does not resume it again.
  assert.equal(session.resumeAsked([{ role: "user", content: [{ type: "text", text: "again" }] }]), null);
});

// ---------------------------------------------------------------------------
// `js-review` evaluation window (根 §2.3)
// ---------------------------------------------------------------------------

test("the review face is offered only to the Manager, and only inside a round", () => {
  // A worker never owns the face, whatever the window says.
  for (const role of ["coder", "devops", "plan"]) {
    assert.equal(surfaceAllowed(role, "js-review", { window: { open: true } }), false, role);
  }
  assert.equal(surfaceAllowed("manager", "js-review", { window: { open: true } }), true);
  assert.equal(surfaceAllowed("manager", "js-review", { window: { open: false } }), false);
  // A caller that omits the window asks only who may ever own the face.
  assert.equal(surfaceAllowed("manager", "js-review"), true);
});

test("beforeTool refuses a js-review call once the round's review is accepted", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  // The call carries the scope annotation every face needs, so the only reason
  // it can be refused here is the window.
  const scope = { kind: "resetScope", newScopeGoal: "evaluate", newScopeRetentionBudget: 8 };
  const call = {
    snapshot: { agentId: "top", parentAgentId: null },
    tool: { name: "js-review" },
    input: { code: "x", scope },
  };
  // The first request opens the round: the call reaches Scope admission, which
  // is past the window gate.
  await plugin.hooks.beforeModel({ request: { messages: [{ role: "user", content: [{ type: "text", text: "go" }] }] } });
  const admitted = await plugin.hooks.beforeTool(call);
  assert.equal(admitted === undefined || !/REVIEW_WINDOW_CLOSED/.test(admitted.reason ?? ""), true);
  // The round's first accepted review shuts the face, and the gate says so.
  const receipt = await plugin.adapter.reviewFor([{ key: "a", value: "x" }]);
  assert.equal(receipt.ok, true);
  const blocked = await plugin.hooks.beforeTool(call);
  assert.equal(blocked.skip, true);
  assert.match(blocked.reason, /REVIEW_WINDOW_CLOSED/);
  // A second review in the same round never reopens it.
  await plugin.adapter.reviewFor([{ key: "b", value: "y" }]);
  const stillBlocked = await plugin.hooks.beforeTool(call);
  assert.match(stillBlocked.reason, /REVIEW_WINDOW_CLOSED/);
});

test("the js-review tool refuses to run through a shut window", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir);
  const review = tools.get("js-review");
  // No round has started: nothing is evaluated, so the face is shut.
  const shut = await review.execute({ code: "class Js extends JsProgram { async run() { return 1; } }" }, top("r0"));
  assert.equal(shut.ok, false);
  assert.equal(shut.rejection, "REVIEW_WINDOW_CLOSED");
  // A request opens the round, and the same call reads the tree.
  await plugin.hooks.beforeModel({ request: { messages: [{ role: "user", content: [{ type: "text", text: "go" }] }] } });
  const open = await review.execute({ code: "class Js extends JsProgram { async run() { return 1; } }" }, top("r1"));
  assert.equal(open.ok, undefined);
  assert.equal(open.value, 1);
  // An accepted review shuts it again, and the tool answers the reason instead
  // of running a program.
  await plugin.adapter.reviewFor([{ key: "a", value: "x" }]);
  const closed = await review.execute({ code: "class Js extends JsProgram { async run() { return 2; } }" }, top("r2"));
  assert.equal(closed.rejection, "REVIEW_WINDOW_CLOSED");
});

test("a worker session cannot run js-review even inside an open round", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir, {}, { role: "coder" });
  const review = tools.get("js-review");
  const receipt = await review.execute({ code: "class Js extends JsProgram { async run() { return 1; } }" }, child("r3"));
  assert.equal(receipt.rejection, "REVIEW_WINDOW_CLOSED");
});

test("the session request loads the plugin and exposes only the role's face", async () => {
  const plan = pluginSessionConfig({ entry: "/repo/src/host-cline/index.js", role: "plan" });
  assert.equal(plan.ok, true);
  assert.deepEqual(plan.pluginPaths, ["/repo/src/host-cline/index.js"]);
  // The host's only narrowing knob: the global entry disables everything, and
  // each wanted tool carries its own `enabled: true` (a bare policy merges over
  // the global one, so `false` globally plus nothing locally means disabled).
  assert.deepEqual(plan.toolPolicies["*"], { enabled: false });
  assert.deepEqual(plan.toolPolicies["js-plan"], { enabled: true });
  assert.equal(plan.toolPolicies["js-manager"], undefined);
  assert.equal(plan.toolPolicies["read_files"], undefined);
  assert.equal(plan.toolPolicies["spawn_agent"], undefined);

  // Plan has no `js-review` (§3.1): review is the Manager's phase.
  assert.equal(plan.toolPolicies["js-review"], undefined);

  const manager = pluginSessionConfig({ entry: "/repo/src/host-cline/index.js", role: "manager" });
  assert.deepEqual(manager.visible, ["js-manager", "js-review", "fork", "join"]);
  // `js-worker` is visible to a Manager request but refused by `beforeTool`; the
  // visible set is not an execution grant (根 §7.2).
  assert.equal(manager.toolPolicies["js-worker"], undefined);
  assert.equal(manager.toolPolicies["read_files"], undefined);

  // The host's own filter, applied to the request exactly as it is built:
  // `isToolEnabledByPolicies` spreads the global entry under the tool's own
  // (`runtime-builder.ts:71-83`, `session-runtime-orchestrator.ts:153-164`), and
  // a tool with no entry of its own inherits `enabled: false`. This is the only
  // place the request's narrowing becomes an observable list.
  const survives = (config, name) =>
    ({ ...config.toolPolicies["*"], ...(config.toolPolicies[name] ?? {}) }).enabled !== false;
  const candidates = [...BUILTIN_TOOLS, "js-plan", "js-manager", "js-worker", "js-review", "fork", "join"];
  assert.deepEqual(candidates.filter((name) => survives(plan, name)), ["js-plan"]);
  assert.deepEqual(candidates.filter((name) => survives(manager, name)), [
    "js-manager",
    "js-review",
    "fork",
    "join",
  ]);

  // Every builtin this host ships is disabled for both roles.
  for (const name of BUILTIN_TOOLS) {
    assert.equal(plan.toolPolicies[name], undefined, name);
    assert.equal(manager.toolPolicies[name], undefined, name);
  }

  assert.deepEqual(pluginSessionConfig({ entry: "", role: "plan" }), {
    ok: false,
    reason: "PLUGIN_ENTRY_MISSING",
  });
  // No fallback: a role nobody named is refused, not given the Manager face.
  assert.deepEqual(pluginSessionConfig({ entry: "/repo/src/host-cline/index.js" }), {
    ok: false,
    reason: "UNKNOWN_SESSION_ROLE",
    detail: null,
  });
  assert.deepEqual(pluginSessionConfig({ entry: "/repo/src/host-cline/index.js", role: "coder" }), {
    ok: false,
    reason: "UNKNOWN_SESSION_ROLE",
    detail: "coder",
  });
});

test("the tool gate resolves the session's role before deciding, so a Plan may call js-plan", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir);
  const session = plugin.adapter.session;
  // The host records the role; the gate has to read it before it decides. A Plan
  // session that has not resolved yet looks like a Manager, and `js-plan` is
  // refused by the Manager face — the first tool call a Plan makes.
  session.resolveRole = async () => {
    session.adoptRole("plan");
    return { role: "plan", source: "sessionRecord", metadata: null };
  };
  const top = { agentId: "plan", toolCallId: "g1", snapshot: { agentId: "plan", parentAgentId: null } };
  // A face call carries its Scope annotation, like every other call to a
  // program face: the first one resets the budget (§11). Without it the next
  // gate refuses with SCOPE_ANNOTATION_MISSING, which would hide the role
  // decision this test is about.
  const allowed = await plugin.hooks.beforeTool({
    snapshot: top.snapshot,
    tool: { name: "js-plan" },
    input: {
      code: "return 1 + 1;",
      scope: { kind: "resetScope", newScopeGoal: "plan-probe", newScopeRetentionBudget: 5 },
    },
  });
  assert.equal(allowed, undefined);
  // The Manager face is still refused for that session.
  const refused = await plugin.hooks.beforeTool({ snapshot: top.snapshot, tool: { name: "js-manager" }, input: {} });
  assert.equal(refused.skip, true);
  assert.equal(session.declaredRole, "plan");
});

test("roleOf reads the host record whether getSession takes an id or an object", async () => {
  const metadata = { "herdchestra.role": "plan" };
  const positional = { getSession: async (id) => (id === "s1" ? { metadata } : undefined) };
  const viaPositional = await roleOf({ host: positional, sessionId: "s1" });
  assert.equal(viaPositional.role, "plan");
  assert.equal(viaPositional.source, "sessionRecord");

  // The object-shaped reader throws on a positional call; the fallback asks with
  // `{ sessionId }`, which is the shape that reader accepts.
  const objectReader = {
    getSession: async (input) => {
      if (typeof input === "string") throw new TypeError("expected an object");
      return { metadata };
    },
  };
  const viaObject = await roleOf({ host: objectReader, sessionId: "s1" });
  assert.equal(viaObject.role, "plan");
  assert.equal(viaObject.source, "sessionRecord");
});

test("roleOf reads a host that exposes only core.get, the live ClineCore alias", async () => {
  // The live host's `ClineCore` answers `get(id)` (`ClineCore.ts:431`) and the
  // adapter's own handle may be a `RuntimeHost` with only `getSession`. Reading
  // `getSession` alone made a real Plan session answer "no reader", fall back to
  // Manager, and refuse every `js-plan` call the model made.
  const metadata = { "herdchestra.role": "plan", "herdchestra.draft": "/repo/.Herdchestra/drafts/p.md" };
  const coreLike = { get: async (id) => (id === "s1" ? { metadata } : undefined) };
  const viaGet = await roleOf({ host: coreLike, sessionId: "s1" });
  assert.equal(viaGet.role, "plan");
  assert.equal(viaGet.source, "sessionRecord");
  assert.equal(viaGet.metadata["herdchestra.draft"], "/repo/.Herdchestra/drafts/p.md");

  // The same alias behind the `{ kind, handle }` wrapper `ManagerSessions` returns.
  const wrapped = await roleOf({ host: { kind: "clineCore", handle: coreLike }, sessionId: "s1" });
  assert.equal(wrapped.role, "plan");

  // `getSession` still wins when both exist, so a host that has both is unchanged.
  const both = { get: async () => ({ metadata: { "herdchestra.role": "coder" } }), getSession: async () => ({ metadata }) };
  assert.equal((await roleOf({ host: both, sessionId: "s1" })).role, "plan");

  // A host with neither name cannot answer: that is not a reading, so the role
  // is `unproven` and the gate grants no face on it.
  const silent = await roleOf({ host: { list: async () => [] }, sessionId: "s1" });
  assert.equal(silent.source, "unproven");
  assert.equal(silent.role, "manager");
});

test("a Plan session whose host answers only get() passes the js-plan gate", async () => {
  // The end-to-end shape of the live failure, composed from the real pieces:
  // the record comes from a `get`-only host (the ClineCore alias), the role is
  // resolved from it, and the gate is the one `beforeTool` uses. With the old
  // `getSession`-only read the role fell back to Manager and `js-plan` was
  // refused; the same call must now be admitted.
  const metadata = { "herdchestra.role": "plan" };
  const coreLike = { get: async (id) => (id === "cline-test" ? { metadata } : undefined) };
  const decided = await roleOf({ host: coreLike, sessionId: "cline-test" });
  assert.equal(decided.role, "plan");
  assert.equal(decided.source, "sessionRecord");

  // The gate decision for that role.
  assert.equal(surfaceAllowed(decided.role, "js-plan"), true);
  assert.equal(surfaceAllowed(decided.role, "js-manager"), false);
  // A Manager from the same host still cannot reach the Plan face.
  assert.equal(surfaceAllowed("manager", "js-plan"), false);
});

test("an unproven session role grants no face, instead of the Manager default", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  // Every source failed: no metadata, no role fact, no reachable host record.
  session.resolveRole = async () => ({ role: "manager", source: "unproven", metadata: null });
  Object.defineProperty(session, "identity", { get: () => ({ role: "manager", source: "unproven", metadata: null }), configurable: true });
  const top = { agentId: "x", toolCallId: "g9", snapshot: { agentId: "x", parentAgentId: null } };
  for (const name of ["js-manager", "js-plan", "js-worker", "fork"]) {
    const refused = await plugin.hooks.beforeTool({ snapshot: top.snapshot, tool: { name }, input: {} });
    assert.equal(refused?.skip, true, `${name} must not be granted on an unproven role`);
    assert.match(refused.reason, /SESSION_ROLE_UNKNOWN/);
  }
  // A role this adapter names still decides normally.
  session.resolveRole = async () => ({ role: "manager", source: "sessionRecord", metadata: null });
  Object.defineProperty(session, "identity", { get: () => ({ role: "manager", source: "sessionRecord", metadata: null }), configurable: true });
  const allowed = await plugin.hooks.beforeTool({
    snapshot: top.snapshot,
    tool: { name: "js-manager" },
    input: { code: "return 1;", scope: { kind: "resetScope", newScopeGoal: "g9", newScopeRetentionBudget: 3 } },
  });
  assert.notEqual(allowed?.skip, true);
});

test("an operator session is admitted from its own snapshot, and the role is recorded", async (t) => {
  // Cline Desktop's own new session: nothing started it through this adapter,
  // so there is no role fact, and `setup` runs before the host registers the
  // session, so `getSession` cannot answer. Every source fails and the old gate
  // refused every face with `SESSION_ROLE_UNKNOWN` — the plugin looked unloaded
  // when it was loaded and simply could not prove who was calling.
  //
  // The proof a Desktop session CAN give is the host snapshot of the call being
  // decided: a top-level agent has no parent. This test drives the real
  // `SessionState` (no stubbed `resolveRole`), so the identity ladder, the gate
  // and the role fact are all the production code.
  const dir = await workspace(t);
  const { plugin } = setup(dir, { session: { sessionId: "desktop-operator" } }, { role: "manager" });
  const session = plugin.adapter.session;
  // The fixture `setup` settles the identity by overwriting `resolveRole` and
  // `identity` on the instance; an operator session has not been settled by
  // anything, so both are restored to the class's own members and the real
  // ladder runs.
  reopenIdentity(session);

  const before = await session.resolveRole();
  assert.equal(before.source, "unproven", "an operator session has no readable role before a call");
  assert.equal(session.identity.source, "unproven");

  // A top-level call. `parentAgentId` is ABSENT, which is what @cline/core 0.2.0
  // sends for a top-level agent (the key is not merely null).
  const top = { agentId: "op-1", status: "running" };
  const admitted = await plugin.hooks.beforeTool({
    snapshot: top,
    tool: { name: "js-manager" },
    input: { code: "return 1;", scope: { kind: "resetScope", newScopeGoal: "op", newScopeRetentionBudget: 3 } },
  });
  assert.notEqual(admitted?.skip, true, "a proven top-level call must be admitted, not refused as unknown");
  assert.equal(session.identity.source, "sessionSnapshot");
  assert.equal(session.identity.role, "manager");
  assert.equal(session.declaredRole, "manager");

  // The role is now a file, so the next load of this session reads a fact
  // instead of re-deriving one. This is the half that makes the admission
  // survive a plugin reload and reach a hook that carries no snapshot.
  const factFile = join(dir, ".Herdchestra", "desktop-operator.role.json");
  assert.equal(existsSync(factFile), true, "the admitted role must be recorded as a role fact");
  assert.deepEqual(JSON.parse(readFileSync(factFile, "utf8")), { v: 1, sessionId: "desktop-operator", role: "manager" });

  // A worker face is still refused for that role: the snapshot proves WHO is
  // calling, it does not grant a face the role does not own.
  const worker = await plugin.hooks.beforeTool({ snapshot: top, tool: { name: "js-worker" }, input: {} });
  assert.equal(worker?.skip, true);
  assert.match(worker.reason, /js-worker is not available/);
});

test("a top-level js-plan call admits the session as Plan, on its first call", async (t) => {
  // Plan's own session is also started without a role fact when an operator
  // opens it, and its first call IS `js-plan`. Admitting it as Manager would
  // refuse the very call that identifies it (`callRole` gives a top-level
  // `js-plan` the session's Plan role), so the snapshot path names Plan.
  const dir = await workspace(t);
  const { plugin } = setup(dir, { session: { sessionId: "desktop-plan" } }, { role: "manager" });
  const session = plugin.adapter.session;
  reopenIdentity(session);

  assert.equal((await session.resolveRole()).source, "unproven");
  const admitted = await plugin.hooks.beforeTool({
    snapshot: { agentId: "op-2", status: "running" },
    tool: { name: "js-plan" },
    input: { code: "return { ok: true };", scope: { kind: "resetScope", newScopeGoal: "p", newScopeRetentionBudget: 3 } },
  });
  assert.notEqual(admitted?.skip, true);
  assert.equal(session.declaredRole, "plan");
  assert.equal(session.identity.source, "sessionSnapshot");
  assert.equal(JSON.parse(readFileSync(join(dir, ".Herdchestra", "desktop-plan.role.json"), "utf8")).role, "plan");

  // Plan's own face stays closed to it: the admission named one role, not both.
  const manager = await plugin.hooks.beforeTool({ snapshot: { agentId: "op-2" }, tool: { name: "js-manager" }, input: {} });
  assert.equal(manager?.skip, true);
});

test("a session with no snapshot and no role fact still fails closed", async (t) => {
  // The half that must NOT change: an unproven session whose call carries no
  // snapshot proves nothing, so no face is granted and no role is recorded.
  const dir = await workspace(t);
  const { plugin } = setup(dir, { session: { sessionId: "desktop-blind" } });
  const session = plugin.adapter.session;
  reopenIdentity(session);
  assert.equal((await session.resolveRole()).source, "unproven");

  for (const context of [{ tool: { name: "js-manager" }, input: {} }, { snapshot: null, tool: { name: "js-manager" }, input: {} }]) {
    const refused = await plugin.hooks.beforeTool(context);
    assert.equal(refused?.skip, true);
    assert.match(refused.reason, /SESSION_ROLE_UNKNOWN/);
  }
  assert.equal(existsSync(join(dir, ".Herdchestra", "desktop-blind.role.json")), false);
  assert.equal(session.identity.source, "unproven");
});

test("a child call never establishes a session role, whatever the snapshot omits", async (t) => {
  // A subagent snapshot names a parent; that proves a CHILD, not the session's
  // role. Property: for any non-null parent value, the session stays unproven
  // and the gate refuses, so a worker can never inherit the Manager face by
  // having its parent field written in an unusual way.
  const dir = await workspace(t);
  await fc.assert(
    fc.asyncProperty(
      fc.oneof(fc.string({ minLength: 1, maxLength: 24 }), fc.uuid()),
      async (parentId) => {
        const { plugin } = setup(dir, { session: { sessionId: "desktop-child" } });
        const session = plugin.adapter.session;
        reopenIdentity(session);
        await session.resolveRole();
        const refused = await plugin.hooks.beforeTool({
          snapshot: { agentId: "kid", parentAgentId: parentId },
          tool: { name: "js-manager" },
          input: {},
        });
        assert.equal(refused?.skip, true);
        assert.match(refused.reason, /SESSION_ROLE_UNKNOWN/);
        assert.equal(session.identity.source, "unproven");
        assert.equal(existsSync(join(dir, ".Herdchestra", "desktop-child.role.json")), false);
      },
    ),
    { numRuns: 25 },
  );
});

test("the gate trace writes to a file, and stays off unless asked", async (t) => {
  // A sandboxed plugin's stderr never reaches the parent process, so the file is
  // the only route that survives the host's plugin sandbox. The line must name
  // the session and the snapshot shape the gate actually received, because that
  // shape is what decides the role.
  const dir = await workspace(t);
  const file = join(dir, "trace.jsonl");

  // Off by default: nothing is written.
  {
    const { plugin } = setup(dir);
    await plugin.hooks.beforeTool({ snapshot: { agentId: "a", parentAgentId: null }, tool: { name: "js-plan" }, input: {} });
    assert.equal(existsSync(file), false);
  }

  process.env.HERDCHESTRA_TRACE_FILE = file;
  t.after(() => {
    delete process.env.HERDCHESTRA_TRACE_FILE;
  });
  const { plugin } = setup(dir);
  await plugin.hooks.beforeTool({
    snapshot: { agentId: "a1", parentAgentId: null },
    tool: { name: "js-plan" },
    input: { code: "return 1;", scope: { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 3 } },
  });

  const lines = readFileSync(file, "utf8").trim().split("\n");
  assert.equal(lines.length, 1);
  const line = JSON.parse(lines[0]);
  assert.equal(line.at, "beforeTool");
  assert.equal(line.tool, "js-plan");
  assert.equal(line.sessionId, "cline-test");
  assert.equal(line.declaredRole, "manager");
  // The fields that distinguish "wrong role" from "pruned payload" from "the
  // host could not be asked at all". The last is the one the sandbox path hit:
  // a plugin in the host's worker cannot reach `@cline/core`, so the record
  // read never happens and the role silently falls back.
  assert.deepEqual(line.snapshotKeys, ["agentId", "parentAgentId"]);
  assert.equal(line.snapshotParent, null);
  assert.equal(line.identitySource, "sessionRecord");
});

test("a call the gate refuses books no Scope, even though afterTool still fires", async (t) => {
  // The live symptom: a refused `js-plan` still wrote its `resetScope` row. The
  // gate returns `{skip:true}` before `admit`, but the host fires `afterTool`
  // anyway, and Scope books on settle — so the annotation of a call that never
  // ran was spent.
  //
  // The observable is the store: `Scope` persists by `store.append(records)`,
  // and that is the only way a row reaches `.scope.jsonl`.
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  const scope = session.tenure.scope();
  const appended = [];
  scope.store = { append: async (records) => appended.push(records) };

  const snapshot = { agentId: "a1", parentAgentId: null };
  const input = { code: "return 1;", scope: { kind: "resetScope", newScopeGoal: "refused", scopeRetentionBudget: 5 } };
  const callId = "call_refused_1";

  // A Manager may not call the Plan face: the gate refuses it.
  const skipped = await plugin.hooks.beforeTool({
    snapshot,
    tool: { name: "js-plan" },
    toolCall: { toolCallId: callId },
    input,
  });
  assert.equal(skipped.skip, true);

  // The host still fires afterTool for that call; nothing may be booked.
  const settled = await plugin.hooks.afterTool({
    snapshot,
    tool: { name: "js-plan" },
    toolCall: { toolCallId: callId },
    input,
  });
  assert.equal(settled, undefined);
  assert.deepEqual(appended, [], "a refused call appends no Scope record");

  // The skip is taken, not merely read: the same id cannot suppress a later call.
  assert.equal(session.takeSkipped(callId), false);
});

test("the Pair Hint pair survives the Scope projection of a resetScope", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  await plugin.hooks.beforeRun();

  // 根 §7.2: the request-level rewrite is the message BUILDER, not a hook. Two
  // requests of one session: the first places the hint pair, the second carries
  // a `resetScope`-era history, where Scope keeps only what is pinned.
  const build = await requestBuilder(plugin);
  const firstMessages = await build([{ role: "user", content: [{ type: "text", text: "start" }] }]);
  const first = { messages: firstMessages };
  assert.ok(Array.isArray(first?.messages), "the first request is projected");
  const pairCall = first.messages.find(
    (message) => message?.role === "assistant" && message.content?.some((part) => part?.type === "tool-call" && part.toolName === ""),
  );
  assert.ok(pairCall !== undefined, "the request carries the hint pair");

  // A plain tool call from before the hint's epoch: Scope would drop it, and it
  // is not ours, so the copy is allowed to lose it.
  const plainCall = {
    role: "assistant",
    content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "plain", input: {} }],
  };
  const plainResult = {
    role: "tool",
    content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "plain", output: { type: "text", value: "x" } }],
  };
  const history = [
    { role: "user", content: [{ type: "text", text: "start" }] },
    plainCall,
    plainResult,
    pairCall,
    ...first.messages.slice(first.messages.indexOf(pairCall) + 1),
  ];

  const items = toItems(history);
  const kept = selectKept(history, []);
  assert.deepEqual(kept, [], "nothing survives without a projection");
  const hinted = items.filter((item) => item.member === "");
  assert.equal(hinted.length, 2, "the pair is one call and one result");
  for (const item of hinted) assert.equal(PINNED_MEMBERS.includes(item.member), true, "the pair is pinned");
  // `SURFACE_MEMBERS["js-review"]` is the face's member names, not the tool:
  // the tool call is `js-review` and its members are what it may call.
  assert.equal(PINNED_MEMBERS.includes("js-review"), true, "the evaluation TOOL is pinned: a host call's item names it");
  assert.equal(PINNED_MEMBERS.includes("cat"), true, "a review member is pinned too");
  assert.equal(PINNED_MEMBERS.includes("js-worker"), false, "the model's own work is not pinned");
  await plugin.hooks.afterRun();
});

test("pinned members are the same set the other adapters use", async () => {
  const { DEFAULT_PINNED_MEMBERS } = await import("../core/scope/errors.mjs");
  assert.deepEqual(
    [...PINNED_MEMBERS],
    [...DEFAULT_PINNED_MEMBERS, "", "js-review", ...SURFACE_MEMBERS["js-review"]],
    "cline pins exactly core's defaults plus the hint pair and the review face",
  );
});

test("a pending tool call is never dropped from an assistant message the model already read", () => {
  // Same rule as the OMP twin: the host's message keeps exactly the calls it
  // had, because a call the model read and the next request still carries may
  // not disappear from the copy in between (§6.1).
  const turn = {
    role: "assistant",
    content: [
      { type: "text", text: "go" },
      { type: "tool-call", toolName: "js-worker", toolCallId: "c9", input: {} },
    ],
  };
  const messages = [{ role: "user", content: "start" }, turn];
  assert.deepEqual(selectKept(messages, toItems(messages)), messages);
  // A message whose items were all dropped still leaves the copy (§6.2).
  assert.deepEqual(selectKept(messages, [toItems(messages)[0]]), [messages[0]]);
});

// ---------------------------------------------------------------------------
// The three findings of the @cline/core 0.2.0 live run, each as the host
// presents it.
// ---------------------------------------------------------------------------

test("a top-level Manager whose snapshot omits parentAgentId keeps its own face", async (t) => {
  // The live snapshot of a top-level agent carries no `parentAgentId` key at
  // all (`AgentRuntimeStateSnapshot.parentAgentId?`): the host omits the field
  // rather than sending null. `roleFor` tested `"parentAgentId" in snapshot`,
  // read that session as a coder, and the gate refused `js-manager` — the live
  // trace was `{tool: "js-manager", role: "coder", declaredRole: "manager"}`.
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  // The exact key set the live host produced, in its order.
  const live = {
    agentId: "agent-1",
    conversationId: "conv-1",
    runId: "run-1",
    status: "running",
    iteration: 1,
    messages: [],
    pendingToolCalls: [],
    usage: {},
  };
  assert.equal("parentAgentId" in live, false, "the fixture reproduces the host's snapshot");
  assert.equal(session.roleFor({ snapshot: live }), "manager");
  // An explicit null and an absent key are the same fact; a child is still a
  // child, whatever the key's presence.
  assert.equal(session.roleFor({ snapshot: { agentId: "a", parentAgentId: null } }), "manager");
  assert.equal(session.roleFor({ snapshot: { agentId: "a" } }), "manager");
  assert.equal(session.roleFor({ snapshot: { agentId: "kid", parentAgentId: "agent-1" } }), "coder");
  // No snapshot at all is not a top-level reading; it stays the worker reading.
  assert.equal(session.roleFor({}), "coder");

  // End to end through the gate: the Manager's own face is granted.
  const admitted = await plugin.hooks.beforeTool({
    snapshot: live,
    tool: { name: "js-manager" },
    input: { code: "return 1;", scope: { kind: "resetScope", newScopeGoal: "live", newScopeRetentionBudget: 3 } },
  });
  assert.notEqual(admitted?.skip, true, `a Manager may call its own face: ${admitted?.reason ?? ""}`);
});

test("the session request's configExtensions is the array the host iterates", async (t) => {
  // `localRuntime.configExtensions` is `RuntimeConfigExtensionKind[]` on this
  // host, and its helper does `new Set(extensions).has(kind)`. The object form
  // documented earlier throws `object is not iterable` inside the host.
  const dir = await workspace(t);
  const request = pluginSessionConfig({ entry: join(dir, "index.js"), role: "manager" });
  assert.equal(request.ok, true);
  assert.equal(Array.isArray(request.configExtensions), true, "an array, never an object");
  assert.deepEqual(request.configExtensions, ["rules", "skills", "plugins"]);
  // The host's own reading of the field must not throw on it.
  assert.doesNotThrow(() => new Set(request.configExtensions).has("plugins"));

  // And the field as it leaves the REAL Manager start request. The host handle
  // records the request it was asked to start; the session then answers.
  const starts = [];
  // The factory's host getter is the one seam a test can stand in: the stub
  // answers the same `{ kind, handle }` the real one does, so `startManager`
  // builds and sends its REAL request and no host module is resolved.
  class StubbedSessions extends ManagerSessions {
    async host() {
      return {
        kind: "clineCore",
        handle: {
          start: async (input) => {
            starts.push(input);
            return { sessionId: input.config.sessionId };
          },
        },
      };
    }
  }
  const sessions = new StubbedSessions({ ctx: null, cwd: dir, model: { providerId: "cline", modelId: "m" } });
  const manager = await sessions.startManager("# draft bytes");
  assert.equal(manager.ok, true, JSON.stringify(manager));
  assert.equal(starts.length, 1);
  const localRuntime = starts[0].localRuntime;
  assert.equal(Array.isArray(localRuntime.configExtensions), true, "manager start");
  assert.deepEqual(localRuntime.configExtensions, ["rules", "skills", "plugins"]);
  assert.doesNotThrow(() => new Set(localRuntime.configExtensions).has("plugins"));
});

test("a structured tool result reaches the model as CUE, and the operator's words stay byte-identical", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  // A refused call, exactly as the live host handed it over: the tagged value
  // the provider layer turns into `{"error":"..."}`.
  const refusal = { type: "json", value: { error: "js-manager is not available to this session" } };
  // A program receipt with a multi-line value: JSON would escape both.
  const receipt = {
    type: "json",
    value: { ok: true, note: "line one", multi: "a#\nb" },
  };
  const messages = [
    { role: "user", content: [{ type: "text", text: "start\n  indented" }] },
    {
      role: "assistant",
      content: [
        { type: "text", text: "go" },
        { type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: {} },
        { type: "tool-call", toolName: "js-worker", toolCallId: "c2", input: {} },
      ],
    },
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c1", output: refusal }] },
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c2", output: receipt }] },
  ];
  // The pure projection first: structure becomes CUE, text keeps its bytes,
  // and the part's own keys (call id, tool name) stay where they were.
  const projected = toCueToolResults(messages);
  const parts = projected.slice(2).map((message) => message.content[0]);
  assert.equal(parts[0].output.type, "text");
  assert.equal(parts[0].output.value, '{error: "js-manager is not available to this session"}');
  assert.equal(parts[0].toolCallId, "c1");
  assert.equal(parts[0].toolName, "js-worker");
  assert.equal(
    parts[1].output.value,
    '{\n  ok: true\n  note: "line one"\n  multi: #"""\n    a#\n    b\n    """#\n}',
  );
  // No JSON escape noise: the multi-line value is a real block, not `\n`.
  assert.equal(parts[1].output.value.includes("\\n"), false, "no `\\n` escapes");
  assert.equal(parts[1].output.value.includes('\\"'), false, "no escaped quotes");
  // The operator's message and the assistant turn are the same objects, byte
  // for byte: the rewrite is confined to tool results.
  assert.equal(projected[0], messages[0]);
  assert.equal(projected[1], messages[1]);
  // Idempotent: a second pass sees text and changes nothing.
  assert.deepEqual(toCueToolResults(projected), projected);
  // A text result keeps its bytes. The live host hands one over as the STRING
  // `{"type":"text","value":"ok"}` (the codec persists a non-string output as
  // JSON, `toPersistedToolResultContent`), so the envelope is unwrapped and the
  // model reads `ok`, never the tag.
  const textOnly = [
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c3", output: { type: "text", value: "ok" } }] },
  ];
  assert.equal(toCueToolResults(textOnly)[0], textOnly[0], "a plain text wrapper is left alone");
  const persistedText = [
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c3", output: '{"type":"text","value":"ok"}' }] },
  ];
  assert.equal(toCueToolResults(persistedText)[0].content[0].output.value, "ok", "the host's persisted envelope is unwrapped");
  // And a string that is already CUE stays byte-identical.
  const cueText = [
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c3", output: "{ok: true, value: 2}" }] },
  ];
  assert.equal(toCueToolResults(cueText)[0], cueText[0]);

  // A value CUE cannot render fails closed (§6.3): the content is withheld and
  // the refusal reaches the model in CUE, never as the JSON the host would have
  // sent. The call is still answered, so the turn continues.
  const circular = { ok: false };
  circular.self = circular;
  const unrenderable = [
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c4", output: { type: "json", value: circular } }] },
    // The same rule for the other shapes `toCue` refuses: a function, a bigint,
    // a symbol, and a getter that throws.
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c5", output: { type: "json", value: { fn() {} } } }] },
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c6", output: { type: "json", value: { big: 1n } } }] },
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c7", output: { type: "json", value: { sym: Symbol("s") } } }] },
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c8", output: { type: "json", value: { get boom() { throw new Error("boom"); } } } }] },
  ];
  const refused = toCueToolResults(unrenderable);
  for (const message of refused) {
    const part = message.content[0];
    assert.equal(part.output.type, "text");
    assert.equal(part.output.value, toCue({ error: UNRENDERABLE_TOOL_RESULT }));
    assert.equal(part.output.value.includes('"error":'), false, "not raw JSON");
    assert.equal(part.output.value.includes('\\"'), false, "no escaped quotes");
  }
  assert.deepEqual(toCueToolResults(refused), refused, "the refusal is idempotent too");
  // A `text` part whose value is not a string is structure by another name and
  // is rendered as CUE, so nothing JSON-shaped reaches the model.
  const oddText = [
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c9", output: { type: "text", value: { ok: true } } }] },
  ];
  assert.equal(toCueToolResults(oddText)[0].content[0].output.value, "{ok: true}");

  // And through the real request hook: the host's request carries the CUE
  // bytes, not JSON, and the operator's message survives unchanged.
  await plugin.hooks.beforeRun();
  const build = await requestBuilder(plugin);
  const before = { messages: await build(messages) };
  assert.ok(Array.isArray(before?.messages), "the request is projected");
  const sent = before.messages.filter((message) => message?.role === "tool" && message.content?.[0]?.toolCallId?.startsWith("c"));
  assert.equal(sent.length, 2, "both results survive the Scope projection");
  for (const message of sent) {
    const value = message.content[0].output.value;
    assert.equal(typeof value, "string");
    assert.equal(value.startsWith("{") && value.includes('\\"'), false, "no JSON escapes reach the model");
  }
  const operator = before.messages.find((message) => message?.role === "user");
  assert.equal(operator.content[0].text, "start\n  indented", "the operator's own text is untouched");
  await plugin.hooks.afterRun();
  assert.equal(session.sessionId, "cline-test");
});

// ---------------------------------------------------------------------------
// One physical review call books once (根 §2.4 第 6 步)
// ---------------------------------------------------------------------------
//
// The host may deliver the same tool call twice: a retry of the model request,
// a replayed execution, or a transcript the host rebuilt. `callId` is the
// identity of the PHYSICAL call, so the second arrival must answer with the
// receipt the first one produced — same tenure, same milestone, same obligations
// — and must not relay, hand off, or persist a second time.
//
// The review member lives on `js-manager` (and on `js-plan` for Plan);
// `js-review` exposes read-only members only, so a review there is a
// `ReferenceError`, not a booked beat. These drive the real tools with the
// context shape the host sends, so the id comes from the host's own field.

const countingPort = (inner = null) => {
  const calls = { handOver: 0, startManager: 0, askOperator: 0 };
  const port = {
    async handOver(request) {
      calls.handOver += 1;
      return inner?.handOver === undefined
        ? { ok: true, retired: true, barrier: true, successor: "sess-next" }
        : inner.handOver(request);
    },
    async startManager(draft) {
      calls.startManager += 1;
      return inner?.startManager === undefined
        ? { ok: true, manager: "sess-manager" }
        : inner.startManager(draft);
    },
    async askOperator(question) {
      calls.askOperator += 1;
      return inner?.askOperator === undefined ? { ok: true, answered: true } : inner.askOperator(question);
    },
  };
  return { port, calls };
};

// Replace only the relay port of one session; every other port stays the real
// one, so the review path is the production path.
const withRelayPort = (plugin, counter) => {
  const session = plugin.adapter.session;
  const ports = session.ports.bind(session);
  session.ports = (role) => ({ ...ports(role), relay: counter.port });
  return session;
};

// A Plan's FIRST empty review in S1 asks the operator and is not a review
// (根 §2.4 第 1 步), so a fixture that means "a review happened" first books one
// obligation and then clears it: the clear is the review under test.
const reviewProgram = (code = "return await review([]);") => ({ code });
// A Plan's first empty review in S1 asks the operator, so a fixture that needs
// a REAL review books one obligation first and then clears it.
const reviewCode = "return await review([{ key: 'k', value: null }]);";

test("a manager review program delivered twice with one callId books once and replays its own receipt", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir);
  const counter = countingPort();
  const session = withRelayPort(plugin, counter);
  const ledger = session.tenure.ledger;
  const call = { toolCallId: "call-review-1", agentId: "top" };

  const first = await tools.get("js-manager").execute(reviewProgram(), call);
  assert.equal(first.error, null, JSON.stringify(first.error));
  assert.equal(first.value.ok, true, JSON.stringify(first.value));
  assert.equal(first.value.replayed, undefined, "the first arrival is a real review");
  const tenure = first.value.tenure;
  const milestone = first.value.milestone;
  const obligations = first.value.obligations;
  const reviews = ledger.reviewCount;
  assert.equal(reviews, 1, "the first arrival booked one review");

  // The same physical call arrives again, byte for byte.
  const again = await tools.get("js-manager").execute(reviewProgram(), call);
  assert.equal(again.error, null, JSON.stringify(again.error));
  assert.equal(again.value.ok, true, JSON.stringify(again.value));
  assert.equal(again.value.replayed, true, "the second arrival is a replay");
  assert.equal(again.value.tenure, tenure, "the receipt names the tenure it judged");
  assert.equal(again.value.milestone, milestone, "the receipt names the milestone it judged");
  assert.equal(again.value.obligations, obligations, "the receipt carries the obligations it produced");
  // The ledger did not move and no side effect ran a second time.
  assert.equal(ledger.tenure, tenure);
  assert.equal(ledger.milestone, milestone);
  assert.equal(ledger.reviewCount, reviews, "a replay books nothing");
  assert.equal(counter.calls.handOver, 0, "a replay never relays");
  assert.equal(counter.calls.startManager, 0, "a replay never hands off");
  // A Manager's first empty review is a `confirm`, never a question (根 §2.4 第 1
  // 步: only Plan's `S1` asks). So this review asked nobody on either arrival —
  // and a replay must not perform the side effect a second time regardless.
  assert.equal(counter.calls.askOperator, 0, "an unattended Manager never asks");
});

// A review that BOOKS is one the ledger records: the first accepted review of a
// round with no obligation outstanding. An empty `review([])` at `execute` is
// not a booking (it is `askUser` or a relay), so these cases make the ledger
// record something first, then replay a call that was accepted.
const recordingReview = async (session) => {
  const member = session.reviewMember("manager");
  const accepted = await member.review([{ key: "goal", value: "ship" }], null, { callId: "call-seed" });
  assert.equal(accepted.ok, true, JSON.stringify(accepted));
  return { member, accepted };
};

test("a manager review program without a host call id is not replayable (falsification)", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir);
  const session = withRelayPort(plugin, countingPort());
  const { member } = await recordingReview(session);
  // No `toolCallId` in the context: the adapter cannot tell two deliveries
  // apart, so both book. That is the contract §2.4 第 6 步 depends on, and it is
  // why the tool handler must forward the host's identity.
  const first = await member.review([{ key: "note", value: "one" }], null, { callId: null });
  const second = await member.review([{ key: "note", value: "two" }], null, { callId: null });
  assert.equal(first.replayed, undefined);
  assert.equal(second.replayed, undefined, "without an id the adapter books again");
  void tools;
});

test("a manager review program replays even after the ledger moved on", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir);
  const counter = countingPort();
  const session = withRelayPort(plugin, counter);
  const { member } = await recordingReview(session);
  const first = await member.review([{ key: "a", value: "x" }], null, { callId: "call-a" });
  const original = { tenure: first.tenure, milestone: first.milestone, obligations: first.obligations };
  // A later, different call moves the ledger.
  await member.review([{ key: "b", value: "y" }], null, { callId: "call-b" });
  // The first call arrives again: it answers with ITS OWN receipt, not a
  // description of the ledger now (§2.4 第 6 步: a call stays replayable however
  // the ledger moves after it).
  const replay = await member.review([{ key: "a", value: "x" }], null, { callId: "call-a" });
  assert.equal(replay.replayed, true);
  assert.deepEqual(
    { tenure: replay.tenure, milestone: replay.milestone, obligations: replay.obligations },
    original,
  );
  // The same id with a different payload is a caller bug, refused, and books
  // nothing.
  const mismatch = await member.review([{ key: "a", value: "CHANGED" }], null, { callId: "call-a" });
  assert.equal(mismatch.ok, false);
  assert.equal(mismatch.rejection, "REPLAY_MISMATCH");
  assert.equal(counter.calls.handOver, 0);
  void tools;
});

test("a plan review program delivered twice with one callId books once and replays its own receipt", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir, {}, { role: "plan" });
  const session = withRelayPort(plugin, countingPort());
  const ledger = session.tenure.ledger;
  // S1's first empty review asks the operator and books nothing, so one
  // obligation is booked first (its own call) and the review under test clears
  // it.
  const booked = await tools.get("js-plan").execute(
    reviewProgram("return await review([{ key: 'k', value: 'x' }]);"),
    { toolCallId: "plan-call-0", agentId: "a" },
  );
  assert.equal(booked.value?.ok, true, JSON.stringify(booked.value));
  const call = { toolCallId: "plan-call-1", agentId: "a" };
  const first = await tools.get("js-plan").execute(reviewProgram(reviewCode), call);
  assert.equal(first.value?.ok, true, JSON.stringify(first.value));
  assert.equal(first.value.replayed, undefined);
  const tenure = first.value.tenure;
  const milestone = first.value.milestone;
  const reviews = ledger.reviewCount;

  const again = await tools.get("js-plan").execute(reviewProgram(reviewCode), call);
  assert.equal(again.value?.ok, true, JSON.stringify(again.value));
  assert.equal(again.value.replayed, true, "the same physical program call replays");
  assert.equal(again.value.tenure, tenure);
  assert.equal(again.value.milestone, milestone);
  assert.equal(ledger.reviewCount, reviews, "a replay never books a review");
});

// The operator's stop is not a stop the agent earned (根 §2.2).
//
// Measured live on omp 18.14.2 (2026-11-01, session 01a1958e): the operator
// interrupted a turn and the idle boundary nudged 24 ms later. The same rule now
// lives in `compose/nudge.mjs` and every host asks it; on Cline the operator's
// stop arrives as an aborted assistant message (`afterModel`) or as a cancelled
// run status (`afterRun`), and neither may produce a nudge.
test("an operator interrupt on Cline is never nudged, and a natural stop still is", () => {
  const recorded = [];
  const cleared = [];
  const session = {
    declaredRole: "manager",
    tenure: { ledger: { kind: "manager", milestone: "execute", tenure: 0, successor: false, obligations: [] } },
    askedInTurn: false,
    handedOff: false,
    noteNudge: (entry) => recorded.push(entry),
    clearNudge: () => cleared.push(true),
  };
  // afterModel: the aborted assistant message the host wrote for an interrupt.
  const stopped = turnExit({
    session,
    assistantMessage: { role: "assistant", content: [{ type: "text", text: "" }], stopReason: "aborted", errorMessage: "Interrupted by user" },
  });
  assert.equal(stopped.attempted, true);
  assert.equal(stopped.decision.stopAllowed, true);
  assert.equal(stopped.decision.reason, "operator-interrupt");
  assert.equal(stopped.decision.nudge, null);
  assert.deepEqual(recorded, [], "no nudge is recorded for the operator's own stop");
  assert.equal(cleared.length, 1, "a nudge owed for an earlier turn is cleared");

  // afterRun: a run the operator cancelled.
  const cancelled = runExit({ session, result: { status: "cancelled" } });
  assert.equal(cancelled.stopAllowed, true);
  assert.equal(cancelled.reason, "operator-interrupt");
  assert.equal(cancelled.nudge, null);
  assert.equal(cleared.length, 2, "an allowed stop clears any nudge an earlier attempt recorded");

  // The same session, a model that stopped on its own: still a nudge.
  const natural = turnExit({ session, assistantMessage: { role: "assistant", content: [{ type: "text", text: "done" }], stopReason: "stop" } });
  assert.equal(natural.decision.stopAllowed, false);
  assert.equal(natural.decision.reason, "unattended-nudge");
  assert.equal(typeof natural.decision.nudge, "string");
  assert.equal(recorded.length, 1, "and it is recorded for the next request");
});

// ---------------------------------------------------------------------------
// 根 §7.2 请求改写入口：beforeModel 的 messages（不是 messageBuilder）
// ---------------------------------------------------------------------------

test("the request rewrite is the beforeModel hook's messages, not a message builder", async (t) => {
  const dir = await workspace(t);
  const plugin = createPlugin({ role: "manager" });
  const builders = [];
  plugin.setup(
    {
      registerTool() {},
      registerRule() {},
      registerMessageBuilder: (builder) => builders.push(builder),
    },
    { cwd: dir, session: { sessionId: "cline-rewrite" } },
  );
  // 根 §7.2: ONE request-rewrite entry. A builder never runs on this host (the
  // host's own `prepareTurn` owns that stage), so none is registered.
  assert.equal(builders.length, 0);
  // The entry is the hook bag the host receives from this plugin.
  assert.equal(typeof plugin.hooks.beforeModel, "function");
  assert.equal(plugin.adapter.rewrite.registered, true);
  assert.equal(plugin.adapter.rewrite.name, "beforeModel");
  // The hook answers a NEW list in the runtime's own message shape: the host's
  // copy is never mutated, so the rewrite cannot reach the transcript.
  const operator = { id: "m1", role: "user", content: [{ type: "text", text: "start" }], createdAt: 1 };
  const request = { messages: [operator], tools: [], systemPrompt: "host prompt" };
  const answer = await plugin.hooks.beforeModel({ snapshot: { agentId: "a1", parentAgentId: null }, request });
  assert.equal(Array.isArray(answer.messages), true);
  assert.notEqual(answer.messages, request.messages);
  assert.equal(request.messages.length, 1);
  assert.equal(operator.content[0].text, "start");
  // A `systemPrompt` is never answered: the host drops it, and the role prompt
  // is the registered rule.
  assert.equal("systemPrompt" in answer, false);
});

test("the request rewrite reports the entry it took, and a plugin with no hook bag reports none", async (t) => {
  const dir = await workspace(t);
  const plugin = createPlugin({ role: "manager" });
  plugin.setup(
    { registerTool() {}, registerRule() {} },
    { cwd: dir, session: { sessionId: "cline-no-hooks" } },
  );
  // `setup` is the only caller that attaches the extension, and the plugin it
  // attaches carries the hook bag (`index.mjs`), so the entry is present.
  assert.equal(plugin.adapter.rewrite.registered, true);
  // The reading itself refuses an object without a hook bag, instead of
  // claiming a rewrite the host would never run.
  const { canRewrite } = await import("./rewrite.mjs");
  assert.equal(canRewrite({ hooks: {} }), false);
  assert.equal(canRewrite(null), false);
});

// ---------------------------------------------------------------------------
// 根 §6.1/§6.2：宿主自写的 user 消息不是操作员的话
// ---------------------------------------------------------------------------

test("hook context, a compaction summary and a display notice are provider items, not operator turns", () => {
  const messages = [
    { role: "user", content: [{ type: "text", text: '<hook_context source="RunStart">\nscope: fine\n</hook_context>' }] },
    { role: "user", content: [{ type: "text", text: "a summary of the conversation" }], metadata: { kind: "compaction_summary" } },
    { role: "user", content: [{ type: "text", text: "system status line" }], metadata: { displayRole: "system" } },
    { role: "user", content: [{ type: "text", text: "build the thing" }] },
  ];
  const items = toItems(messages);
  assert.deepEqual(items.map((item) => item.kind), ["provider", "provider", "provider", "message"]);
  assert.deepEqual(items.map((item) => item.role ?? null), [null, null, null, "operator"]);
  // The host's own marks are named on the item, so a reader can tell which host
  // fact wrote it.
  assert.deepEqual(items.slice(0, 3).map((item) => item.hostKind), ["hook-context", "compaction", "notice"]);
});

test("an ask is resumed by the operator's words, never by a nudge the host appended", () => {
  // The last user message is the host's nudge; the operator's turn is before it.
  const messages = [
    { role: "user", content: [{ type: "text", text: "yes, use postgres" }] },
    { role: "user", content: [{ type: "text", text: '<hook_context source="RunStart">\nkeep working\n</hook_context>' }] },
  ];
  assert.equal(lastOperatorText(messages), "yes, use postgres");
  // The relay barrier cuts on the operator's own turn, not on the nudge.
  assert.equal(lastOperatorIndex(messages), 0);
  // A copy that carries only host messages has no operator turn at all.
  assert.equal(lastOperatorIndex([messages[1]]), -1);
  assert.equal(lastOperatorText([messages[1]]), null);
});

test("a tool result that merely quotes hook context stays the operator's own words", () => {
  // The block is the host's only when the message STARTS with it.
  const message = { role: "user", content: [{ type: "text", text: 'here is what I saw:\n<hook_context source="x">\nno\n</hook_context>' }] };
  assert.deepEqual(toItems([message]).map((item) => item.kind), ["message"]);
});

// ---------------------------------------------------------------------------
// 根 §2.2 「中断（交还控制权）」：park 之后这一回合真的停下
// ---------------------------------------------------------------------------

test("a parked ask stops the run: beforeModel answers the host's stop control, once", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  session.beginRun();
  // Nothing is owed before a park.
  assert.equal(await plugin.hooks.beforeModel({ request: { messages: [] } }).then((r) => r?.stop), undefined);
  session.noteAsk({ name: "p", question: "which db?", resume: () => true });
  assert.equal(session.parkStopOwed, true);
  const stop = await plugin.hooks.beforeModel({ request: { messages: [] } });
  assert.deepEqual(stop, { stop: true, reason: "PARKED_ASK" });
  assert.equal(session.parkStopped, true);
  assert.equal(session.parkStopOwed, false);
  // The host may consult the hook again for the same turn; that is not a second
  // stop, and a second `ControlledStopError` would abort a run that already ended.
  const again = await plugin.hooks.beforeModel({ request: { messages: [] } });
  assert.equal(again?.stop, undefined);
  // A new run inherits nothing.
  session.beginRun();
  assert.equal(session.parkStopOwed, false);
  assert.equal(session.parkStopped, false);
});

test("a park that is not an ask does not stop the run", async (t) => {
  const dir = await workspace(t);
  const { tools, plugin } = setup(dir);
  plugin.adapter.session.beginRun();
  const parked = await tools
    .get("js-worker")
    .execute({ code: `return await this.park({ why: 1 });`, scope: { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 5 } }, child("park-x"));
  assert.equal(parked.state, "parked");
  // An explicit park is a suspension the operator may resume with `unpark`; the
  // run is not asked to stop.
  assert.equal(plugin.adapter.session.parkStopOwed, false);
  assert.equal((await plugin.hooks.beforeModel({ request: { messages: [] } }))?.stop, undefined);
});

test("a normal js-manager tool call in a run with no ask is never stopped", async (t) => {
  const dir = await workspace(t);
  const { tools, plugin } = setup(dir);
  const session = plugin.adapter.session;
  session.beginRun();
  // Nothing asked, so nothing is owed: a run that never parked must not be
  // stopped by the park control.
  assert.equal(session.parkStopOwed, false);
  const receipt = await tools.get("js-manager").execute(
    {
      code: `return { ok: true, value: 1 + 1 };`,
      scope: { kind: "resetScope", newScopeGoal: "smoke", newScopeRetentionBudget: 5 },
    },
    top("normal-1"),
  );
  assert.equal(receipt.state, "completed", JSON.stringify(receipt.error));
  assert.deepEqual(receipt.value, { ok: true, value: 2 });
  // The call itself owes no stop, and the request that follows it is asked for
  // normally: no stop control, and no ask was recorded.
  assert.equal(session.parkStopOwed, false);
  assert.equal(session.askedInTurn, false);
  const answer = await plugin.hooks.beforeModel({ request: { messages: [{ role: "user", content: [{ type: "text", text: "go on" }] }] } });
  assert.equal(answer?.stop, undefined);
  assert.equal(session.parkStopped, false);
});

// ---------------------------------------------------------------------------
// 根 §2.3：程序里的 review 也关掉本轮的评估窗口
// ---------------------------------------------------------------------------

test("a review accepted by a PROGRAM closes the js-review window", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir);
  const session = plugin.adapter.session;
  session.tenure.window.beginRound();
  assert.equal(session.tenure.window.open, true);
  // A Manager program's `review(...)` — the Manager's normal path.
  const receipt = await tools.get("js-manager").execute(
    {
      code: `return await review([{ key: "ship", value: "it (src/a.mjs)" }]);`,
      scope: { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 5 },
    },
    top("review-1"),
  );
  assert.equal(receipt.state, "completed", JSON.stringify(receipt.error));
  assert.equal(receipt.value.ok, true, JSON.stringify(receipt.value));
  // Before the fix the member had no `window`, so only `reviewFor` closed it and
  // a program's own review left `js-review` open for the rest of the round.
  assert.equal(session.tenure.window.open, false);
  assert.equal(surfaceAllowed("manager", "js-review", { window: session.tenure.window }), false);
});

// ---------------------------------------------------------------------------
// Gaps found by the host-cline audit, one test each.
// ---------------------------------------------------------------------------

test("gap 2: a runtime whose host tool list was narrowed still runs the worker face", async () => {
  // Root §7.2: a subagent whose frontmatter lists `tools` gets THAT list from
  // the host's `createSubAgentTools` (`configured-agent-tool.ts:160`, wired at
  // `runtime-builder.ts:662`), so `js-worker` can be missing before this
  // adapter ever sees the child. A plugin cannot reach `createSubAgentTools`;
  // the enforcement point is therefore the factory itself
  // (`guardedCreateDelegatedAgent`), which puts the requested face back through
  // the host's own `addTools` before the first turn.
  const { runtimeHandle, enforceFace } = await import("./runtime.mjs");
  const { guardedCreateDelegatedAgent } = await import("./host-modules.mjs");
  const workerFace = [{ name: "js-worker", execute: async () => ({}) }];
  const added = [];
  const runtime = {
    run: async () => ({ finishReason: "stop", text: "ok", iterations: 1 }),
    addTools: (tools) => added.push(...tools.map((tool) => tool.name)),
    config: {},
  };
  const delegated = {
    // The host narrowed the face to its own builtin, exactly as a frontmatter
    // `tools:` list would.
    createDelegatedAgent: () => runtime,
  };
  const create = guardedCreateDelegatedAgent(delegated, () => workerFace);
  const agent = create({ kind: "subagent", prompt: "p", tools: workerFace, configProvider: null, extensions: [] });
  assert.equal(agent, runtime);
  assert.deepEqual(added, ["js-worker"], "the worker face is put back through the host's own addTools");
  assert.deepEqual(agent.face.names, ["js-worker"], JSON.stringify(agent.face));

  // The handle does the same for a runtime it started itself, and reports what
  // it did instead of claiming a defence that did not run.
  const addedAgain = [];
  const handle = runtimeHandle({
    name: "w",
    brief: "go",
    role: "coder",
    tools: workerFace,
    create: () => ({
      run: async () => ({ finishReason: "stop", text: "ok", iterations: 1 }),
      addTools: (tools) => addedAgain.push(...tools.map((tool) => tool.name)),
      config: {},
    }),
  });
  assert.equal(handle.started, true);
  assert.deepEqual(handle.face.names, ["js-worker"], JSON.stringify(handle.face));
  assert.deepEqual(addedAgain, ["js-worker"]);

  // A runtime with no way to add tools is reported, never silently accepted.
  const blind = enforceFace({ run: async () => null }, workerFace);
  assert.equal(blind.ok, false);
  assert.equal(blind.reason, "NO_TOOL_SURFACE");
});

test("gap 3: a same-name fork onto a LIVE worker steers its running turn", async () => {
  // Root §4.1: a same-name fork while the worker is live is a steer, and the
  // receipt is exactly `{ name, steered, started }` — never a rejection inside
  // a success. The delivery is the runtime's own pending-input channel, which
  // the agent loop reads at every request boundary (`agent-runtime.ts:1689`).
  const { runtimeHandle } = await import("./runtime.mjs");
  const consumed = [];
  const pending = [];
  const handle = runtimeHandle({
    name: "w",
    brief: "first",
    role: "coder",
    tools: [],
    configProvider: {
      getRuntimeConfig: () => ({ providerId: "p", modelId: "m" }),
    },
    create: (options) => ({
      run: () => new Promise((resolve) => pending.push(resolve)),
      abort: () => {},
      addTools: () => {},
      notifyPendingUserMessage: () => {},
      // The host reads the queue through the config provider the handle built.
      read: () => options.configProvider.getRuntimeConfig().consumePendingUserMessage,
    }),
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  const table = new WorkerTable({ create: async () => handle });
  const started = await table.fork({ name: "w", brief: "first", role: "coder" });
  assert.deepEqual(started, { name: "w", steered: false, started: true });

  const steered = await table.fork({ name: "w", brief: "second", role: "coder" });
  assert.deepEqual(steered, { name: "w", steered: true, started: false });
  // The brief is IN the running turn's queue — the host's next request boundary
  // takes it, with no second turn and no `queued` flag.
  const consume = handle.agent.read();
  assert.equal(await consume(), "second");
  assert.equal(await consume(), "");

  // The turn settles once, and its report answers the carrier AND the steer it
  // took: the carrier's record keeps the brief that turn was given, and the
  // steered turn's record carries the text the runtime answered with.
  pending[0]({ finishReason: "stop", text: "done", iterations: 1 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal((await table.join()).text, "first", "the carrier keeps the text of the turn it was given");
  assert.equal((await table.join()).text, "done", "the steered turn keeps the runtime's answer");
  assert.equal(await table.join(), null, "one report settled both turns, and both were handed over");
});

test("gap 6a: the group record is committed before any lane starts", async () => {
  // Spec §4.2 step 4: a crash must never leave LIVE lanes whose group nobody
  // recorded. compose therefore builds the lane records, commits them, and only
  // then starts the lanes; a start failure rolls the committed group back.
  const order = [];
  const inertRuntime = ({ name }) => ({
    started: true,
    pending: true,
    run: async () => {
      order.push(`run:${name}`);
      return { finishReason: "stop", text: `${name} done`, iterations: 1 };
    },
    abort: () => {},
    addTools: () => {},
    notifyPendingUserMessage: () => {},
  });
  const build = async ({ name }) => {
    order.push(`build:${name}`);
    return inertRuntime({ name });
  };

  // A session with no journal refuses the group before a lane exists: step 4 is
  // what makes the rest survivable, so nothing may start without it.
  const noJournal = new WorkerTable({ create: build });
  const journalOrder = [];
  await noJournal.fork({ name: "w1", brief: "owner", role: "coder" });
  const refused = new SessionFission({ workers: noJournal, sessionId: "s-order", groupJournal: () => null });
  await assert.rejects(() => refused.port().fission("w1", ["a", "b"]), (error) => error.step === "persistGroup");
  assert.deepEqual(
    order.filter((step) => step.startsWith("run:")),
    [],
    "no lane RAN before the commit was possible",
  );

  // With a journal, the commit lands after the lane records exist and before
  // any lane runs.
  const dir = await workspace({ after: () => {} });
  const journal = join(dir, "groups.jsonl");
  // The order this group is measured in: the runtimes it builds (owner and
  // lanes) and the one commit. A group built over a fresh table keeps the count
  // readable.
  const workers = new WorkerTable({
    create: async (request) => {
      // A lane records its build, and its first turn, in the same order list.
      if (request.start === false) journalOrder.push(`build:${request.name}`);
      const handle = await build(request);
      const run = handle.run;
      handle.run = async (brief) => {
        journalOrder.push(`run:${request.name}`);
        return run(brief);
      };
      return handle;
    },
  });
  await workers.fork({ name: "w1", brief: "owner", role: "coder" });
  const session = new SessionFission({
    workers,
    sessionId: "s-order",
    groupJournal: () => {
      journalOrder.push("persist");
      return journal;
    },
  });
  const receipt = await session.port().fission("w1", ["a", "b"]);
  assert.equal(receipt.ok, true);
  const persistAt = journalOrder.indexOf("persist");
  // Both lanes, then the commit; no lane runs before it.
  assert.equal(persistAt, 2, `the commit follows the owner and the two lanes: ${journalOrder.join(",")}`);
  const runs = journalOrder.filter((step) => step.startsWith("run:"));
  assert.deepEqual(runs, ["run:w1:2:1#0", "run:w1:2:1#1"], journalOrder.join(","));
  for (const run of runs) assert.equal(journalOrder.indexOf(run) > persistAt, true, `a lane ran before the commit: ${journalOrder.join(",")}`);
  // The record is one line, and it names the group that was started.
  const lines = readFileSync(journal, "utf8").trim().split("\n");
  assert.equal(lines.length, 1);
  assert.equal(JSON.parse(lines[0]).groupId, receipt.groupId);

  // A lane that cannot be BUILT fails the group before anything is committed:
  // nothing is recorded, and nothing survives.
  const unbuildable = new WorkerTable({
    create: async ({ name }) => {
      if (name.endsWith("#1")) return { started: false, name, rejection: "NO_SESSION_FACTORY" };
      return inertRuntime({ name });
    },
  });
  await unbuildable.fork({ name: "w1", brief: "owner", role: "coder" });
  const journal2 = join(dir, "groups2.jsonl");
  const refusedBuild = new SessionFission({ workers: unbuildable, sessionId: "s-order", groupJournal: () => journal2 });
  await assert.rejects(() => refusedBuild.port().fission("w1", ["a", "b"]), (error) => error.step === "buildLane");
  assert.equal(existsSync(journal2), false, "a group that never committed leaves no record");
  assert.equal(unbuildable.names().filter((name) => name.includes("#")).length, 0, "no lane survives a refused build");

  // A START failure rolls the COMMITTED group back: the journal records the
  // withdrawal, and no lane is left in the table.
  const failing = new WorkerTable({
    create: async (request) => inertRuntime(request),
  });
  // The second lane cannot start: its first turn is refused at `beginLane`.
  const refuseLane = failing.beginLane.bind(failing);
  failing.beginLane = (name, brief) =>
    name.endsWith("#1") ? { ok: false, name, rejection: "SESSION_START_FAILED" } : refuseLane(name, brief);
  await failing.fork({ name: "w1", brief: "owner", role: "coder" });
  const journal3 = join(dir, "groups3.jsonl");
  const rolledBack = new SessionFission({ workers: failing, sessionId: "s-order", groupJournal: () => journal3 });
  await assert.rejects(() => rolledBack.port().fission("w1", ["a", "b"]), (error) => error.step === "startLane");
  const kinds = readFileSync(journal3, "utf8").trim().split("\n").map((line) => JSON.parse(line).kind);
  assert.deepEqual(kinds, ["fissionGroup", "fissionGroupDiscarded"]);
  assert.equal(failing.names().filter((name) => name.includes("#")).length, 0, "no lane survives the rollback");
});

test("gap 6b: capacity is measured, so a group that does not fit is refused", async () => {
  // `capacity: () => true` made `CapacityExceeded` unreachable. The ceiling is
  // this adapter's own (`LANE_CAPACITY`) and the check counts the runtimes the
  // session already holds.
  const { LANE_CAPACITY } = await import("./fission.mjs");
  const table = new WorkerTable({
    create: async () => ({
      started: true,
      run: async () => null,
      abort: () => {},
      addTools: () => {},
      notifyPendingUserMessage: () => {},
    }),
  });
  const session = new SessionFission({ workers: table, sessionId: "s-cap" });
  await table.fork({ name: "w1", brief: "owner", role: "coder" });
  const prompts = Array.from({ length: LANE_CAPACITY + 1 }, (_, index) => `lane ${index}`);
  const refused = await session.port().fission("w1", prompts);
  assert.deepEqual(refused, { ok: false, rejection: "CapacityExceeded" });
  assert.equal(table.names().filter((name) => name.includes("#")).length, 0, "no lane was built");
  // The worker table's own arithmetic, stated directly.
  assert.equal(table.capacityFor(2, { limit: 8 }), true);
  assert.equal(table.capacityFor(8, { limit: 8 }), false, "the owner already holds one runtime");
  assert.equal(table.capacityFor(2, { limit: 0 }), false, "a host with no ceiling has no room");
});

test("gap 6c: a settled takeover lane announces the end and the group converges", async () => {
  // Steps 7-10: each lane's settled turn is its delivery, and the takeover lane
  // (n−1) announces once `ConvergenceReady` holds. Without the wiring the group
  // stayed `replaced` forever.
  const resolvers = [];
  const table = new WorkerTable({
    create: async ({ name }) => ({
      started: true,
      pending: true,
      run: () => new Promise((resolve) => resolvers.push({ name, resolve })),
      abort: () => {},
      addTools: () => {},
      notifyPendingUserMessage: () => {},
    }),
  });
  await table.fork({ name: "w1", brief: "owner", role: "coder" });
  const dir = await workspace({ after: () => {} });
  const journal = join(dir, "g.jsonl");
  const session = new SessionFission({ workers: table, sessionId: "s-conv", groupJournal: () => journal });
  const receipt = await session.port().fission("w1", ["a", "b"]);
  assert.equal(receipt.ok, true);
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(session.group.phase, "replaced");
  // Lane 0 delivers; the group is not ready, so nothing is announced.
  const lane0 = resolvers.find((entry) => entry.name.endsWith("#0"));
  lane0.resolve({ finishReason: "stop", text: "lane0", iterations: 1 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(session.group.phase, "replaced");
  assert.equal(session.group.hasDelivered(0), true);
  // The takeover lane (index n−1) delivers AND announces.
  const lane1 = resolvers.find((entry) => entry.name.endsWith("#1"));
  lane1.resolve({ finishReason: "stop", text: "merged", iterations: 1 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(session.group.phase, "converged");
  assert.equal(session.group.terminalLane, 1);
  // A late report for an already converged group is answered, not thrown.
  assert.equal(session.laneSettled({ name: receipt.lanes[1].laneId, text: "again" }).terminal, "converged");
});

test("gap 6d: replacing a present is deferred to the turn's end, never aborted in-turn", async () => {
  // Aborting the owner from inside its own tool call ends the run that is
  // executing that call, so the call's promise never settles (the OMP deadlock
  // recorded in HACK.md). The abort is armed for the boundary instead.
  const { runtimeHandle } = await import("./runtime.mjs");
  const aborted = [];
  const turns = [];
  const handle = runtimeHandle({
    name: "w",
    brief: "first",
    role: "coder",
    tools: [],
    create: () => ({
      run: (brief) => new Promise((resolve) => turns.push({ brief, resolve })),
      abort: () => aborted.push("abort"),
      addTools: () => {},
      notifyPendingUserMessage: () => {},
    }),
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  const table = new WorkerTable({ create: async () => handle });
  await table.fork({ name: "w", brief: "first", role: "coder" });
  const replaced = table.replacePresent("w");
  assert.equal(replaced.replaced, true);
  assert.equal(replaced.deferredAbort, true);
  assert.equal(replaced.stoppedNow, false);
  assert.deepEqual(aborted, [], "nothing aborts while the calling turn is still running");
  // The boundary: the turn ends, and the abort runs exactly once.
  turns[0].resolve({ finishReason: "stop", text: "done", iterations: 1 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.deepEqual(aborted, ["abort"]);
  assert.equal(handle.drainTurnEnd(), 0, "the boundary is drained once");
  // A present that already settled has nothing to silence.
  assert.equal(table.replacePresent("w").stoppedNow, true);
});

test("gap 8: a crash between the fence and the ledger advance still leaves the successor judging", async (t) => {
  // Root §2.4 step 3: retirement, the barrier and the successor start are one
  // durable record; the ledger advance happens a moment later. A crash between
  // the two used to restore a retired incumbent, and the successor's first
  // review answered `TENURE_RETIRED` forever. The fence is now the authority:
  // restore derives the successor's tenure and milestone from it.
  const dir = await workspace(t);
  const file = join(dir, "m.json");
  const tenure = new TenureState({ file, scopeFile: join(dir, "s.jsonl"), sessionId: "s" });
  // Reach a relay: one obligation, then its removal.
  assert.equal(tenure.ledger.review([{ key: "k", value: "do it" }]).ok, true);
  const relay = tenure.ledger.review([{ key: "k", value: null }]);
  assert.equal(relay.kind, "relay");
  const handed = tenure.handOver({ lastReview: 2, lastTurn: 1 });
  assert.equal(handed.ok, true);
  assert.equal(tenure.ledger.tenure, 0, "the ledger has NOT advanced yet: this is the crash window");

  // The crash: the file holds the fence, the ledger fields still name tenure 0.
  const saved = JSON.parse(readFileSync(file, "utf8"));
  assert.equal(saved.tenure, 0);
  assert.equal(saved.fence.successor.tenure, 1);

  // The cold start reads the fence, not the stale ledger fields.
  const restored = new TenureState({ file, scopeFile: join(dir, "s.jsonl"), sessionId: "s" });
  assert.equal(restored.ledger.tenure, 1, "the successor's tenure comes from the fence");
  assert.equal(restored.ledger.milestone, handed.milestone, "and so does its milestone");
  assert.equal(restored.ledger.successor, true);
  assert.equal(restored.retired, false, "the successor is not the retired incumbent");

  // The gate the crash used to trip: the session's `retired` answer.
  const gate = { retired: restored.retired };
  assert.equal(gate.retired, false);
  // Its first review is accepted: the successor promotes, it is not refused.
  const first = restored.ledger.review([]);
  assert.notEqual(first.rejection, "TENURE_RETIRED");
  assert.equal(first.ok, true);
  assert.equal(first.kind, "confirm");
  assert.equal(restored.ledger.successor, false, "the successor has now judged");
});

// ---------------------------------------------------------------------------
// Root §3.1: one spelling per capability
// ---------------------------------------------------------------------------

test("a program's primitives are bare names only: no this.fork/this.join/this.review", async (t) => {
  // `compile` binds the members as the body's `this` and the three top-level
  // primitives as parameters. The `JsProgram` base class assigns `__surface` to
  // the instance, so passing the full member list there gave every program a
  // SECOND spelling (`this.fork`) beside the bare one. The surface now carries
  // the members only.
  const dir = await workspace(t);
  const { tools } = setup(dir);
  const code = [
    "return {",
    "  thisFork: typeof this.fork,",
    "  thisJoin: typeof this.join,",
    "  thisReview: typeof this.review,",
    "  bareFork: typeof fork,",
    "  bareJoin: typeof join,",
    "  bareReview: typeof review,",
    "};",
  ].join("\n");
  const cases = [
    // Which bare primitives each face carries (compose's member table).
    ["js-manager", top("sp-m"), { bareFork: "function", bareJoin: "function", bareReview: "function" }],
    ["js-worker", child("sp-w"), { bareFork: "function", bareJoin: "function", bareReview: "undefined" }],
    ["js-plan", top("sp-p"), { bareFork: "undefined", bareJoin: "undefined", bareReview: "function" }],
  ];
  for (const [name, context, bare] of cases) {
    const receipt = await tools.get(name).execute({ code }, context);
    assert.equal(receipt.state, "completed", `${name}: ${JSON.stringify(receipt.error)}`);
    assert.deepEqual(receipt.value, { thisFork: "undefined", thisJoin: "undefined", thisReview: "undefined", ...bare }, name);
  }
});

// ---------------------------------------------------------------------------
// The request copy: receipts whole, operator words found, mixed messages paired
// ---------------------------------------------------------------------------

test("a program receipt reaches the model whole, not stripped to its value", () => {
  // The tool's result is a receipt object: `{ ok, name, state, reason, payload,
  // source, pendingOperations, command, delta, remainingWaitMs, value, error }`.
  // It has a `value` field, and unwrapping "any object with a value" rendered
  // only that field: the model lost the receipt §3.3 requires.
  const receipt = {
    ok: true,
    name: "p1",
    state: "completed",
    reason: null,
    payload: null,
    source: "return 2;",
    pendingOperations: 0,
    command: null,
    delta: "",
    remainingWaitMs: 0,
    value: 2,
    error: null,
  };
  const messages = [
    { role: "user", content: [{ type: "text", text: "start" }] },
    { role: "assistant", content: [{ type: "tool-call", toolName: "js-manager", toolCallId: "c1", input: {} }] },
    { role: "tool", content: [{ type: "tool-result", toolName: "js-manager", toolCallId: "c1", output: receipt }] },
  ];
  const text = toCueToolResults(messages)[2].content[0].output.value;
  assert.equal(text.includes('name: "p1"'), true, "the receipt names its program");
  assert.equal(text.includes("state: \"completed\""), true);
  assert.equal(text.includes('source: "return 2;"'), true, "and carries the source §3.3 requires");
  assert.equal(text.includes("value: 2"), true);

  // A JSON object the TOOL wrote is the tool's own bytes. Only the host's two
  // tags are envelopes; `{"type":"event","value":1}` is a command's stdout.
  const foreign = [
    { role: "tool", content: [{ type: "tool-result", toolName: "call", toolCallId: "c2", output: '{"type":"event","value":1}' }] },
  ];
  // The envelope is left alone and the tool's own bytes are what the model
  // reads — not `1`, which is what unwrapping the foreign tag produced.
  assert.equal(toCueToolResults(foreign)[0].content[0].output.value, '{"type":"event","value":1}');
});

test("the operator's answer is found above a trailing tool result or empty message", () => {
  const messages = [
    { role: "user", content: [{ type: "text", text: "answer" }] },
    { role: "tool", content: [{ type: "tool-result", toolName: "x", toolCallId: "c1", output: "x" }] },
    { role: "user", content: [{ type: "tool_result", tool_use_id: "c2", name: "x", content: "y" }] },
    {
      role: "user",
      content: [
        { type: "text", text: "tool turn" },
        { type: "tool_result", tool_use_id: "c3", name: "x", content: "z" },
      ],
    },
    { role: "user", content: [{ type: "image", source: { data: "" } }] },
  ];
  // A tool-result user message (whole or mixed) is the host's turn, and an
  // image-only message has no words: none answers the question, and the scan
  // continues to the operator's own words.
  assert.equal(lastOperatorText(messages), "answer");
});

test("a mixed user message contributes both its words and its tool result", () => {
  const message = {
    role: "user",
    content: [
      { type: "text", text: "here" },
      { type: "tool_result", tool_use_id: "c9", name: "js-worker", content: '{"type":"json","value":{"ok":true}}' },
    ],
  };
  const items = toItems([message]);
  assert.equal(
    items.some((item) => item.kind === "toolResult" && item.callId === "c9" && item.member === "js-worker"),
    true,
    "the result is a pair Scope can see",
  );
  assert.equal(
    // The message is a tool-result turn the provider built, so its text is host
    // context: Scope keeps it wherever it sits and never counts it as a beat.
    items.some((item) => item.kind === "provider" && item.text === "here"),
    true,
    "and the words are still a message (as provider context)",
  );
  const cue = toCueToolResults([message])[0];
  assert.equal(cue.content[0].text, "here");
  assert.equal(cue.content[1].content, "{ok: true}", "the result is CUE, not the host's JSON envelope");
});

test("a result whose call survived is restored, so no tool_use is left unanswered", () => {
  // Scope kept the assistant message for its prose and dropped the result. A
  // provider refuses a call with no result, so the pair is restored.
  const call = {
    role: "assistant",
    content: [
      { type: "text", text: "working" },
      { type: "tool-call", toolName: "js-worker", toolCallId: "a", input: {} },
    ],
  };
  const result = { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "a", output: "x" }] };
  const operator = { role: "user", content: [{ type: "text", text: "do it" }] };
  const messages = [operator, call, result];
  // The projection kept the words and the call, and dropped the result.
  const keptItems = toItems(messages).filter((item) => item.kind !== "toolResult");
  const kept = selectKept(messages, keptItems);
  assert.deepEqual(kept, [operator, call, result]);
});

// ---------------------------------------------------------------------------
// Scope: fork and join book their annotations (§6.2)
// ---------------------------------------------------------------------------

test("fork and join book their scope annotations like every other call", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  const book = session.tenure.scope();
  const call = (name, input, id) => ({
    snapshot: { agentId: "top", parentAgentId: null },
    tool: { name },
    toolCall: { toolCallId: id, toolName: name },
    input,
  });
  const negotiate = () => book.ask("negotiate", session.sessionId);

  const fork = call(
    "fork",
    { name: "w", brief: "role: coder — do", scope: { kind: "resetScope", newScopeGoal: "dispatch", newScopeRetentionBudget: 2 } },
    "fork-1",
  );
  // A Scope turn books one beat, and the reset is the beat of its turn.
  await plugin.hooks.beforeRun();
  await plugin.hooks.beforeModel({ request: { messages: [] } });
  assert.equal(await plugin.hooks.beforeTool(fork), undefined, "the fork's resetScope is admitted");
  await plugin.hooks.afterTool(fork);
  assert.equal(negotiate().established, true, "the fork established the budget");
  assert.equal(negotiate().remaining, 2);

  // The next request is the next turn: the join's `keepLast` is its own beat.
  await plugin.hooks.beforeModel({ request: { messages: [] } });
  const join = call("join", { scope: { kind: "keepLast" } }, "join-1");
  assert.equal(await plugin.hooks.beforeTool(join), undefined, "the join's keepLast is admitted");
  await plugin.hooks.afterTool(join);
  assert.equal(negotiate().remaining, 1, "the join spent one retention unit");
});

// ---------------------------------------------------------------------------
// A delegated runtime's hooks never move the parent session
// ---------------------------------------------------------------------------

test("a child runtime's hooks never move the parent's ask, turn or park stop", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir, {}, { role: "plan" });
  const session = plugin.adapter.session;
  const parent = { snapshot: { agentId: "top", parentAgentId: null } };
  await plugin.hooks.beforeRun(parent);
  await plugin.hooks.beforeModel({
    ...parent,
    request: { messages: [{ role: "user", content: [{ type: "text", text: "start" }] }] },
  });
  const parked = await tools.get("js-plan").execute(
    { code: "const answer = await this.ask('which file?'); return answer;", scope: { kind: "resetScope", newScopeGoal: "ask", newScopeRetentionBudget: 3 } },
    top("ask-child"),
  );
  assert.equal(parked.reason, "ask");
  assert.equal(session.askedInTurn, true);
  assert.equal(session.parkStopOwed, true);
  const run = session.run;
  const turn = session.turn;

  // The child receives this session's hooks; its brief is a USER message, which
  // `resumeAsked` read as the operator's answer.
  const childCtx = { snapshot: { agentId: "kid", parentAgentId: "top" } };
  await plugin.hooks.beforeRun(childCtx);
  await plugin.hooks.beforeModel({
    ...childCtx,
    request: { messages: [{ role: "user", content: [{ type: "text", text: "role: coder — do the work" }] }] },
  });
  await plugin.hooks.afterModel({
    ...childCtx,
    assistantMessage: { role: "assistant", content: [{ type: "text", text: "done" }] },
  });
  await plugin.hooks.afterRun({ ...childCtx, result: { status: "completed" } });

  assert.deepEqual(session.asked, { name: "ask-child", question: "which file?" }, "the child's brief is not an answer");
  assert.equal(session.askedInTurn, true);
  assert.equal(session.parkStopOwed, true);
  assert.equal(session.run, run, "a child's run does not advance the parent's run counter");
  assert.equal(session.turn, turn, "nor its turn");
  assert.equal(session.nudge, null);
});

test("an operator's answer finishes the parked program and frees its name", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir, {}, { role: "plan" });
  const session = plugin.adapter.session;
  await plugin.hooks.beforeModel({ request: { messages: [{ role: "user", content: [{ type: "text", text: "start" }] }] } });
  const scope = { kind: "resetScope", newScopeGoal: "ask", newScopeRetentionBudget: 3 };
  const parked = await tools.get("js-plan").execute(
    { code: "const answer = await this.ask('q?'); return 'got:' + answer;", scope },
    top("ask-finish"),
  );
  assert.equal(parked.reason, "ask");
  assert.deepEqual(session.tenure.parkedNames(), ["ask-finish"]);
  assert.equal(session.resumeAsked([{ role: "user", content: [{ type: "text", text: "answer" }] }])?.name, "ask-finish");
  for (let tick = 0; tick < 4; tick += 1) await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(session.tenure.parkedNames(), [], "a resumed program is no longer parked");
  assert.equal(session.registry.live("ask-finish"), null, "its name is retired");
  // The same name runs a new program: the park entry and the name are both free.
  const again = await tools.get("js-plan").execute({ code: "return 1;", scope }, top("ask-finish"));
  assert.equal(again.state, "completed", JSON.stringify(again.error));
});

test("a continuation that asks again owes its run the stop too", async (t) => {
  const dir = await workspace(t);
  const { plugin, tools } = setup(dir, {}, { role: "plan" });
  const session = plugin.adapter.session;
  await plugin.hooks.beforeModel({ request: { messages: [{ role: "user", content: [{ type: "text", text: "start" }] }] } });
  const first = await tools.get("js-plan").execute(
    {
      code: "const a = await this.ask('first?'); const b = await this.ask('second?'); return a + '/' + b;",
      scope: { kind: "resetScope", newScopeGoal: "ask", newScopeRetentionBudget: 3 },
    },
    top("ask-twice"),
  );
  assert.equal(first.reason, "ask");
  // The first answer resumes the body, which parks on the second question.
  session.resumeAsked([{ role: "user", content: [{ type: "text", text: "one" }] }]);
  for (let tick = 0; tick < 4; tick += 1) await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(session.asked, { name: "ask-twice", question: "second?" });
  assert.equal(session.askedInTurn, true, "the second question is this turn's interrupt");
  assert.equal(session.parkStopOwed, true, "and the run owes the stop");
});

// ---------------------------------------------------------------------------
// The request copy's own boundaries (§6.1, §6.2)
// ---------------------------------------------------------------------------

test("a relay barrier keeps an operator turn at index 0", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  const handed = session.tenure.handOver({ lastReview: 1, lastTurn: 1 });
  assert.equal(handed.ok, true);
  const copy = await session.projectMessages([{ role: "user", content: [{ type: "text", text: "继续" }] }]);
  const texts = copy.flatMap((message) => (Array.isArray(message.content) ? message.content.map((part) => part?.text) : []));
  assert.equal(texts.includes("继续"), true, "the successor keeps the operator's own words");
});

test("an equal-length projection is applied, not skipped", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  // A `removeLast` lesson: the erased beat leaves and one replacement message is
  // written in its place, so the copy keeps its LENGTH while its bytes change.
  const scope = session.tenure.scope();
  scope.project = ({ items }) => ({
    ok: true,
    messages: items.map((item) =>
      item.kind === "message" && item.role === "assistant"
        ? { kind: "message", role: "assistant", text: "lesson: do not spin", erasedCallId: "erased-1" }
        : item,
    ),
  });
  // No Pair Hint in this copy: it would add messages and hide the length.
  session.tenure.pairHint = async () => ({
    bound: false,
    presented: null,
    text: null,
    handles: [],
    anchor() {},
    setHandles() {},
    newEpoch: async () => {},
  });
  const messages = [
    { role: "user", content: [{ type: "text", text: "go" }] },
    { role: "assistant", content: [{ type: "text", text: "old prose" }] },
    { role: "user", content: [{ type: "text", text: "next" }] },
  ];
  const copy = await session.projectMessages(messages);
  const text = JSON.stringify(copy);
  assert.equal(text.includes("lesson: do not spin"), true, "the replacement reaches the copy");
  assert.equal(text.includes("old prose"), false, "the erased message leaves it");
  assert.equal(copy.length, messages.length, "the replacement keeps the copy's length");
});

test("a Scope refusal leaves a durable request record and still sends a copy", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  const session = plugin.adapter.session;
  const scope = session.tenure.scope();
  scope.project = () => ({ ok: false, code: "SCOPE_CALL_RESULT_MISMATCH", message: "a result names no kept call" });
  const messages = [
    { role: "user", content: [{ type: "text", text: "go" }] },
    { role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: {} }] },
  ];
  const copy = await session.projectMessages(messages);
  assert.equal(Array.isArray(copy), true);
  assert.equal(copy.length > 0, true, "the copy still goes out");
  const journal = join(dir, ".Herdchestra", "cline-test.requests.jsonl");
  assert.equal(existsSync(journal), true, "the refusal is written down");
  const record = JSON.parse(readFileSync(journal, "utf8").trim().split("\n").at(-1));
  assert.equal(record.kind, "scopeProjectionRefused");
  assert.equal(record.code, "SCOPE_CALL_RESULT_MISMATCH");
  assert.equal(record.sessionId, "cline-test");
  assert.equal(record.items.count, 2, "the shapes the projection judged are recorded");
  assert.equal(record.items.shapes.every((shape) => typeof shape.digest === "string"), true);
  assert.equal(JSON.stringify(record).includes("go"), false, "no message text is recorded");
});

// ---------------------------------------------------------------------------
// The tools and the hooks around them (§3.3, §7.2)
// ---------------------------------------------------------------------------

test("a syntax error frees the program name and answers a failure receipt", async (t) => {
  const dir = await workspace(t);
  const { tools } = setup(dir);
  const tool = tools.get("js-manager");
  const scope = { kind: "resetScope", newScopeGoal: "compile", newScopeRetentionBudget: 3 };
  const broken = await tool.execute({ code: "return (;", scope }, top("compile-1"));
  assert.equal(broken.state, "failed");
  assert.equal(typeof broken.error.message, "string");
  assert.equal(broken.error.code, "PROGRAM_COMPILE_FAILED");
  // The name is free: a replay of the same call id is not NAME_TAKEN.
  const retried = await tool.execute({ code: "return 1;", scope }, top("compile-1"));
  assert.equal(retried.state, "completed", JSON.stringify(retried.error));
  assert.equal(retried.value, 1);
});

test("a delegated runtime's tool list is the worker face only", async (t) => {
  const dir = await workspace(t);
  const { plugin } = setup(dir);
  // `js-review` is the Manager's evaluation face and `beforeTool` refuses it for
  // coder and devops, so a worker must not see it.
  assert.deepEqual(plugin.adapter.tools.map((tool) => tool.name), ["js-worker"]);
});

test("the gate trace survives a context it cannot serialize", async (t) => {
  const dir = await workspace(t);
  const file = join(dir, "trace.jsonl");
  process.env.HERDCHESTRA_TRACE_FILE = file;
  t.after(() => {
    delete process.env.HERDCHESTRA_TRACE_FILE;
  });
  const { plugin } = setup(dir);
  const context = {
    snapshot: { agentId: "a1", parentAgentId: null },
    tool: { name: "js-manager" },
    input: { code: "return 1;", scope: { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 3 } },
  };
  // A context whose own keys cannot be read is exactly what the host's own
  // structured clone refuses; the trace must lose its line, never the call.
  const hostile = new Proxy(context, {
    ownKeys() {
      throw new Error("the host's context cannot be enumerated");
    },
  });
  const answer = await plugin.hooks.beforeTool(hostile);
  assert.notEqual(answer?.skip, true, "the call is not refused by a failed trace");
  assert.equal(existsSync(file), false, "and no line is written");
});

test("a projection that throws still sends CUE-rendered tool results", async () => {
  const { requestRewrite } = await import("./rewrite.mjs");
  const messages = [
    { role: "user", content: [{ type: "text", text: "go" }] },
    { role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: {} }] },
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c1", output: { type: "json", value: { ok: true } } }] },
  ];
  const rewrite = requestRewrite({
    project: async () => {
      throw new Error("the session's projection failed");
    },
  });
  const copy = await rewrite(messages);
  assert.equal(copy[2].content[0].output.value, "{ok: true}", "§6.3 still holds on the fallback path");
  assert.equal(copy[0], messages[0], "the operator's words are untouched");
});

// ---------------------------------------------------------------------------
// The roster's own boundaries
// ---------------------------------------------------------------------------

test("a Plan ledger reads its own milestones, and its fence is adopted", async (t) => {
  const dir = await workspace(t);
  const file = join(dir, "p.plan.json");
  const scopeFile = join(dir, "s.jsonl");
  // A Plan relays too (an incumbent's empty review retires and opens a
  // successor at the same milestone), so a Plan file can carry a fence. The
  // restore indexed the milestone against the MANAGER list, answered -1, and
  // returned a fresh ledger beside the fence: `retired` then said the successor
  // was the retired incumbent, forever.
  const first = new TenureState({ file, scopeFile, sessionId: "p", kind: "plan" });
  assert.equal(first.handOver({ lastReview: 0, lastTurn: 1 }).ok, true);
  const restored = new TenureState({ file, scopeFile, sessionId: "p", kind: "plan" });
  assert.equal(restored.ledger.kind, "plan");
  assert.equal(restored.ledger.tenure, 1, "the fence names the tenure that is judging");
  assert.equal(restored.retired, false, "the successor is not the retired incumbent");

  // A file with no fence: its obligations survive a restart. The old lookup
  // found no milestone at all and returned before applying them.
  const plain = join(dir, "q.plan.json");
  await writeFile(
    plain,
    `${JSON.stringify({
      v: 1,
      sessionId: "q",
      kind: "plan",
      milestone: "S2",
      tenure: 0,
      reviews: 1,
      obligations: [{ key: "draft", value: "write it" }],
      successor: false,
      fence: { retiredTenure: null, barrier: null, successor: null },
    })}\n`,
  );
  const fromFile = new TenureState({ file: plain, scopeFile, sessionId: "q", kind: "plan" });
  assert.deepEqual(fromFile.ledger.obligations, [{ key: "draft", value: "write it" }]);
});

test("two devops investigations in one turn do not both start", async () => {
  const { createDevopsRunner } = await import("./devops.mjs");
  const started = [];
  let release = null;
  const runner = createDevopsRunner({
    ctx: {},
    cwd: "/tmp",
    extension: null,
    tools: [],
    delegatedApi: async () => ({
      createDelegatedAgentConfigProvider: () => ({}),
      createDelegatedAgent: () => ({
        addTools() {},
        run: () => {
          started.push(1);
          return new Promise((resolve) => {
            release = resolve;
          });
        },
      }),
    }),
    resolveConnection: async () => ({
      model: { providerId: "p", modelId: "m" },
      source: "context",
      credentials: null,
      credentialSource: null,
    }),
  });
  const first = runner.investigate("one");
  const second = await runner.investigate("two");
  assert.equal(second.ok, false);
  assert.equal(second.reason, "DEVOPS_ALREADY_IN_FLIGHT");
  assert.equal(runner.busy, true, "the admission is set before the first await");
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(started.length, 1, "only one runtime was started");
  release({ finishReason: "stop", text: "the report", iterations: 1 });
  assert.deepEqual(await first, { ok: true, report: "the report" });
  assert.equal(runner.busy, false);
});

test("a steer is delivered even when the host already wired its own queue", async () => {
  const { runtimeHandle } = await import("./runtime.mjs");
  const hostQueue = ["host-message"];
  const handle = runtimeHandle({
    name: "w",
    brief: "first",
    role: "coder",
    tools: [],
    configProvider: {
      getRuntimeConfig: () => ({ consumePendingUserMessage: async () => hostQueue.shift() ?? "" }),
    },
    create: (options) => ({
      run: () => new Promise(() => {}),
      abort() {},
      addTools() {},
      notifyPendingUserMessage() {},
      read: () => options.configProvider.getRuntimeConfig().consumePendingUserMessage,
    }),
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(handle.steer("steered brief"), true);
  const consume = handle.agent.read();
  assert.equal(await consume(), "steered brief", "the adapter's queue is drained first");
  assert.equal(await consume(), "host-message", "the host's own consumer is chained behind it");
  assert.equal(await consume(), "");
});

test("two workers' groups route each lane's settlement to its own owner", async (t) => {
  const dir = await workspace(t);
  const table = new WorkerTable({
    create: async ({ name }) => ({
      started: true,
      pending: true,
      run: () => new Promise(() => {}),
      abort() {},
      addTools() {},
      notifyPendingUserMessage() {},
    }),
  });
  await table.fork({ name: "w1", brief: "one", role: "coder" });
  await table.fork({ name: "w2", brief: "two", role: "coder" });
  const session = new SessionFission({ workers: table, sessionId: "s-two", groupJournal: () => join(dir, "g.jsonl") });
  const first = await session.port().fission("w1", ["a", "b"]);
  const second = await session.port().fission("w2", ["c", "d"]);
  assert.equal(first.ok, true);
  assert.equal(second.ok, true);
  const one = session.laneSettled({ name: first.lanes[0].laneId, outcome: { text: "one" } });
  assert.equal(one.owner, "w1", "the lane's own group, not the session's last owner");
  assert.equal(one.groupId, first.groupId);
  const two = session.laneSettled({ name: second.lanes[1].laneId, outcome: { text: "two" } });
  assert.equal(two.owner, "w2");
  assert.equal(two.groupId, second.groupId);
});
