// Tests for the OMP adapter: role resolution, the visible tool set, the program
// runtime, the worker lanes and steer, the hooks, and the ports. No host is
// needed: every host call goes through a fake dispatcher that records what it
// was asked to do and answers with the shape OMP documents.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import fc from "fast-check";

import {
  ROLE,
  roleOfAgent,
  REPLACED_TOOLS,
  MANAGER_FORBIDDEN,
  agentForRole,
  stateDir,
  safeSessionName,
} from "./host-facts.mjs";
import herdchestra, {
  visibleToolsFor,
  MANAGER_TOOLS,
  WORKER_TOOLS,
  WORKER_LANE_TOOLS,
  TOOL_NAMES,
  HostAdapter,
  forkedByHerdchestra,
  workerSessionTools,
  resetAdapters,
  adapterForCall,
  cancelSubtree,
} from "./index.mjs";
import {
  compileProgram,
  startProgram,
  JsProgram,
  bindMembers,
  guardedMembers,
  ParkTable,
  primitiveScope,
} from "./program.mjs";
import { ProgramState } from "../core/program.mjs";
import { LaneTable, workerActions, workerExtensionApi } from "./lane.mjs";
import { TaskPort } from "./worker-port.mjs";
import { laneStart } from "./lane.mjs";
import { HostBinding, CAPABILITY } from "./host-binding.mjs";
import { createToolCallHandler, createBeforeAgentStartHandler, createContextHandler, createTurnEndHandler } from "./hooks.mjs";
import { toItems, selectKept, barrierFor, toPairHintMessages, isSideChannelRequest } from "./request.mjs";
import { canonicalKey } from "../core/pair-hint/canonical.mjs";
import { gateStateRecord, recordRequest, requestLogFile, requestRecord } from "./drift-log.mjs";
import { writeJson } from "./files.mjs";
import { messageShape } from "../core/pair-hint/facts.mjs";
import { textResult, errorResult, receiptResult } from "./tool-schema.mjs";
import { programTool } from "./program-tools.mjs";
import { askPort, devopsPort, fissionPort, handOverPort, laneFraming } from "./ports.mjs";
import { RoleSession, surfaceAllowed, roleForSurface } from "./surface.mjs";
import { languageLadder, SessionState } from "./session-state.mjs";
import { fcRuns } from "../core/test-runs.mjs";

const tmpRoot = () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "host-omp-"));
  // Every root this file creates is tracked, including the many tests that
  // call `tmpRoot()` directly rather than through `withRoot`. The exit sweep
  // below removes them.
  SESSION_ROOTS.push(root);
  return root;
};

// The Pair Hint chain: a frozen `{length, digests, root, pairIndex, …}`.
// `PairHint#presented` is gone with no alias, so there is nothing else to read.
const chainOf = (hint) => hint.chain ?? null;
const sameChain = (left, right) => {
  if (left === right) return true;
  if (left === null || right === null) return false;
  return left.length === right.length && JSON.stringify(left.digests) === JSON.stringify(right.digests);
};

// Every RoleSession a test builds directly owns a temp root. A construction with
// a ctx and no state used `projectRoot()` — the working directory — and wrote
// into the repo's `.Herdchestra/`; this helper gives each one its own tree.
const SESSION_ROOTS = [];
process.once("exit", () => {
  for (const root of SESSION_ROOTS) {
    try {
      fs.rmSync(root, { recursive: true, force: true });
    } catch {
      // A root already gone is fine.
    }
  }
});
let sessionSerial = 0;
function testSession(options = {}) {
  if (options.state != null || options.ctx == null) return new RoleSession(options);
  const root = tmpRoot();
  SESSION_ROOTS.push(root);
  const sessionId = options.ctx?.sessionManager?.getSessionId?.() ?? `test-session-${(sessionSerial += 1)}`;
  const state = new SessionState({ sessionId, role: options.role ?? roleOfAgent(options.ctx?.agent), root });
  return new RoleSession({ ...options, state });
}

// Every worker roster a test opened, so the teardown can release it. A test
// that starts a lane and deliberately never ends its turn leaves that lane
// live: the fake session's listeners, its turn promise and the roster entry all
// outlive the test, and a `join` armed on it stays pending. A roster nobody
// disposes is therefore closed here, once per test, whatever the test did.
//
// This is the harness's own bookkeeping, not the product's: the adapter closes
// its roster on `release()` (`index.mjs`), and a real host disposes its
// sessions. A test that builds a `LaneTable` directly has no adapter, so it is
// tracked at construction instead.
const openRosters = new Set();
const trackedLaneTable = (table) => {
  openRosters.add(table);
  return table;
};

const disposeOpenRosters = async () => {
  const tables = [...openRosters];
  openRosters.clear();
  for (const table of tables) {
    try {
      await table.disposeAll();
    } catch {
      // A teardown that throws must not mask the test's own result.
    }
  }
};

const withRoot = async (fn) => {
  const root = tmpRoot();
  const previous = process.env.HERDCHESTRA_PROJECT_ROOT;
  process.env.HERDCHESTRA_PROJECT_ROOT = root;
  try {
    return await fn(root);
  } finally {
    // Release every lane the test started BEFORE the registry is reset: a lane
    // still holding a turn would otherwise outlive the test that made it.
    await disposeOpenRosters();
    // The adapter registry is process-wide and keyed by session id (see
    // `resetAdapters`). Fixtures here reuse ids across tests, so without this a
    // later test's tool call is routed to an earlier test's session — the
    // failure mode that made `join` answer for the wrong roster.
    resetAdapters();
    if (previous === undefined) delete process.env.HERDCHESTRA_PROJECT_ROOT;
    else process.env.HERDCHESTRA_PROJECT_ROOT = previous;
  }
};

// The structured receipt of one tool result (spec §6.3).
//
// The model now reads CUE in `content[0].text`, so parsing that text is gone on
// purpose: CUE is not JSON, and a receipt is data, not a string to decode. The
// host keeps the plain object beside the call in `details`, and that is where a
// test reads it — the three program faces answer `{result}`, `fork`/`join` and
// the Scope snapshots answer the receipt itself.
//
// A failed receipt is unwrapped exactly like a successful one: a program that
// threw, a relay that was deferred (`ok:false, rejection:"RELAY_DEFERRED"`) and
// a parked program all carry their structured answer in the same place. The
// helper asserts nothing about `ok` — the caller does.
//
// The wrapper is `{result}` with `result` as its ONLY key. That distinction is
// not cosmetic: a program receipt has a `result` field of its own (core attaches
// the run's outcome there), so testing `Object.hasOwn(details, "result")` would
// unwrap a receipt into its own payload and lose every other field — which is
// exactly how the fission rollback assertions broke.
const receiptOf = (result) => {
  const details = result?.details;
  if (details === undefined || details === null || typeof details !== "object") {
    throw new TypeError(`tool result carries no structured receipt: ${JSON.stringify(result?.content?.[0]?.text)}`);
  }
  const keys = Object.keys(details);
  return keys.length === 1 && keys[0] === "result" ? details.result : details;
};

// A dispatcher that answers like OMP and records every call.
// A stand-in for one host session created through the SDK. It records what the
// adapter asked it to do and lets the test decide when a turn ends: exactly the
// surface `fork`/`join`/steer use (prompt, steer, subscribe, getAgentId,
// dispose, extensionRunner).
const fakeSession = ({ agentId = "worker-1" } = {}) => {
  const calls = [];
  const listeners = [];
  const session = {
    calls,
    isStreaming: false,
    getAgentId: () => agentId,
    // The members the host's own runtime wiring forwards to (`runtime-init.ts`).
    // A lane refuses a session that lacks any of them.
    sendCustomMessage: async () => true,
    sendUserMessage: async () => {},
    sessionManager: {
      appendCustomEntry: () => {},
      appendLabelChange: () => {},
      getSessionName: () => "worker",
      setSessionName: async () => {},
    },
    getEnabledToolNames: () => [],
    getAllToolInfos: () => [],
    setActiveToolsByName: async () => {},
    modelRegistry: { getApiKey: async () => "key" },
    setModel: async () => {},
    thinkingLevel: "medium",
    setThinkingLevel: () => {},
    serviceTierByFamily: {},
    setServiceTierFamily: () => {},
    model: { id: "test/model" },
    abort: async () => {
      calls.push(["abort"]);
      session.isStreaming = false;
    },
    queuedMessageCount: 0,
    getContextUsage: () => ({ tokens: 0, contextWindow: 1 }),
    systemPrompt: [],
    runEphemeralTurn: async () => ({ text: "" }),
    compact: async () => {},
    waitForIdle: async () => {},
    // The host's own transition (`agent-session.ts:11341`): a new session record
    // with an empty transcript. The fake keeps the previous file, as the host
    // does (it flushes before switching).
    sessionFile: undefined,
    async newSession() {
      calls.push(["newSession"]);
      session.previousSessionFile = session.sessionFile;
      session.sessionFile = `${agentId}-successor.jsonl`;
      return true;
    },
    branch: async () => ({ cancelled: false }),
    navigateTree: async () => ({ cancelled: false }),
    switchSession: async () => true,
    reload: async () => {},
    async prompt(text) {
      calls.push(["prompt", text]);
      session.isStreaming = true;
      // The host announces the live turn with `agent_start`; `prompt` itself
      // resolves only when the whole turn is over.
      session.emit({ type: "agent_start" });
      return true;
    },
    async steer(text) {
      calls.push(["steer", text]);
      return undefined;
    },
    subscribe(listener) {
      listeners.push(listener);
      return () => {
        const index = listeners.indexOf(listener);
        if (index >= 0) listeners.splice(index, 1);
      };
    },
    async dispose() {
      calls.push(["dispose"]);
    },
    // A runner whose adapter bound and narrowed: this adapter answers the lane's
    // authorization handshake with its own facts.
    extensionRunner: {
      // The lane initializes the runtime before the handshake (the host never
      // does this for a session created through the SDK).
      initialize() {},
      async emit(event) {
        const handshake = event?.herdchestraHandshake;
        if (handshake !== undefined && typeof handshake.reply === "function") {
          handshake.reply({ token: handshake.token, bound: true, narrowed: true, role: "coder", reason: null });
        }
      },
    },
    emit(event) {
      for (const listener of [...listeners]) listener(event);
    },
    endTurn(text, { terminal = true } = {}) {
      session.isStreaming = false;
      if (typeof text === "string") {
        session.emit({ type: "message_end", message: { role: "assistant", content: [{ type: "text", text }] } });
      }
      session.emit({ type: "agent_end", isTerminal: terminal });
    },
  };
  return session;
};

// The SDK shape the host injects as `api.pi`: only `createAgentSession` matters
// here. `script` says which fake session each start gets.
const fakeApi = (script = []) => {
  // `shift` mutates; copy so the caller keeps its own sessions.
  const queue = [...script];
  const created = [];
  const api = {
    created,
    pi: {
      // The `api` a worker's builder receives: its own registration target, so
      // `install` lands the worker's tools in the worker's session, not the
      // Manager's table.
      registerTool: undefined,
      async createAgentSession(options) {
        created.push(options);
        const next = queue.shift() ?? fakeSession({ agentId: `worker-${created.length}` });
        return { session: next };
      },
    },
  };
  return api;
};

const fakeDispatch = (answers = {}) => {
  const calls = [];
  const dispatch = async (name, params) => {
    calls.push({ name, params });
    const answer = answers[name];
    if (answer === undefined) return errorResult(`no fake for ${name}`);
    return typeof answer === "function" ? await answer(params, calls) : answer;
  };
  dispatch.calls = calls;
  return dispatch;
};

// ---------------------------------------------------------------------------
// Roles
// ---------------------------------------------------------------------------

test("role comes from ctx.agent.kind, never from depth", () => {
  assert.equal(roleOfAgent({ kind: "main", id: "Main", name: "main", depth: 0 }), ROLE.MANAGER);
  assert.equal(roleOfAgent({ kind: "sub", id: "c1", name: "coder", depth: 0 }), ROLE.CODER);
  assert.equal(roleOfAgent({ kind: "sub", id: "d1", name: "devops", depth: 1 }), ROLE.DEVOPS);
  assert.equal(roleOfAgent({ kind: "sub", id: "x", name: "explore", depth: 0 }), ROLE.CODER);
  // No `agent` fact is not a Manager: the host fills that object itself, so a
  // session bound before it exists has told us nothing. Reading it as Manager
  // handed out the Manager's face and ledger on a guess.
  assert.equal(roleOfAgent(undefined), ROLE.UNPROVEN);
  assert.equal(roleOfAgent(null), ROLE.UNPROVEN);
  // And an unproven session gets no face and may run none of the faces.
  assert.deepEqual(visibleToolsFor(ROLE.UNPROVEN, ["js-manager", "js-worker", "plan", "fork"]), []);
  assert.equal(surfaceAllowed(ROLE.UNPROVEN, "js-manager"), false);
  assert.equal(surfaceAllowed(ROLE.UNPROVEN, "js-worker"), false);
  assert.equal(surfaceAllowed(ROLE.UNPROVEN, "plan"), false);
});

test("fork picks the devops definition only for the devops name", () => {
  assert.equal(agentForRole(ROLE.DEVOPS), "devops");
  assert.equal(agentForRole(ROLE.CODER), "coder");
  // A lane is created through the SDK, so the start carries no `task` payload.
  assert.deepEqual(laneStart({ name: "d", verboseIntent: "b", agent: "devops" }), {
    name: "d",
    verboseIntent: "b",
    agent: "devops",
  });
});

test("a face runs under the role that owns it", () => {
  assert.equal(roleForSurface("js-manager", ROLE.MANAGER), "manager");
  assert.equal(roleForSurface("js-worker", ROLE.DEVOPS), "devops");
  assert.equal(roleForSurface("js-worker", ROLE.CODER), "coder");
  assert.equal(surfaceAllowed(ROLE.MANAGER, "js-worker"), false);
  assert.equal(surfaceAllowed(ROLE.CODER, "js-manager"), false);
  assert.equal(surfaceAllowed(ROLE.CODER, "js-worker"), true);
  // The planner owns `plan` and nothing else (§2.2): it must not inherit the
  // worker face, which carries `call`, `rm`, `mv` and the rest.
  assert.equal(roleForSurface("plan", ROLE.PLAN), "plan");
  assert.equal(surfaceAllowed(ROLE.PLAN, "plan"), true);
  assert.equal(surfaceAllowed(ROLE.PLAN, "js-worker"), false);
  assert.equal(surfaceAllowed(ROLE.PLAN, "js-manager"), false);
});

test("a top-level session named plan is the planner, not the Manager", () => {
  // Plan is top-level (§2.1), so `agent.kind` is not `sub`; the name decides.
  // Without this the Plan ledger, its draft port and its hand-off were all
  // unreachable — every top-level session was read as a Manager.
  assert.equal(roleOfAgent({ kind: "main", name: "plan", depth: 0 }), ROLE.PLAN);
  assert.equal(roleOfAgent({ kind: "main", name: "herd-manager", depth: 0 }), ROLE.MANAGER);
  assert.equal(roleOfAgent({ kind: "main", name: "main", depth: 0 }), ROLE.MANAGER);
  // A subagent called `plan` is still not the planner: the planner is top-level
  // and is never created by `fork`.
  assert.equal(roleOfAgent({ kind: "sub", name: "plan", depth: 1 }), ROLE.CODER);
});

// ---------------------------------------------------------------------------
// Visible tools
// ---------------------------------------------------------------------------

test("the Manager loses every replaced tool and keeps only its own faces", () => {
  // `ask` stands for a host tool the Manager keeps: it is not replaced.
  const all = [...REPLACED_TOOLS, "ask", "yield", ...TOOL_NAMES];
  const visible = visibleToolsFor(ROLE.MANAGER, all);
  for (const name of MANAGER_FORBIDDEN) assert.equal(visible.includes(name), false, name);
  for (const name of MANAGER_TOOLS) assert.equal(visible.includes(name), true, name);
  assert.equal(visible.includes("js-worker"), false);
  // The review face is registered but only opened during an evaluation (§8).
  assert.equal(visible.includes("js-review"), false);
  assert.equal(visible.includes("ask"), true);
});

test("a worker loses the replaced tools and keeps its own face", () => {
  // `ask` stands for a host tool the worker keeps: it is not replaced.
  const all = [...REPLACED_TOOLS, "ask", ...TOOL_NAMES];
  const visible = visibleToolsFor(ROLE.CODER, all);
  for (const name of REPLACED_TOOLS) assert.equal(visible.includes(name), false, name);
  assert.equal(visible.includes("js-manager"), false);
  // The worker keeps its own face plus the scheduling primitives; the
  // Manager's faces are gone (§2.1, §4: fork/join are not Manager-only).
  const managed = visible.filter((name) => TOOL_NAMES.includes(name));
  assert.equal(managed.includes("js-worker"), true);
  assert.equal(managed.includes("js-manager"), false);
  assert.equal(managed.includes("js-review"), false);
  for (const name of WORKER_TOOLS) assert.equal(managed.includes(name), true, name);
  assert.equal(visible.includes("fork"), true);
  assert.equal(visible.includes("join"), true);
  assert.equal(visible.includes("ask"), true);
});

test("the planner sees only its own face and no dispatch", () => {
  // §2.2 gives the Plan exactly five capabilities, all members of `plan`. It has
  // no fork/join, no call, no glob/grep/exec and no fission; its only agent
  // channel is the bare primitive `devops(verboseIntent)`.
  const all = [...REPLACED_TOOLS, "ask", ...TOOL_NAMES];
  const visible = visibleToolsFor(ROLE.PLAN, all);
  assert.equal(visible.includes("plan"), true);
  for (const name of ["js-worker", "js-manager", "js-review", "fork", "join", "eval", "bash", "glob", "grep"]) {
    assert.equal(visible.includes(name), false, name);
  }
  // The planner's own face is exactly `plan`; `ask` stays because it is the
  // operator channel (§2.2 gives the Plan `this.ask`), not a worker capability.
  assert.deepEqual(visible.filter((name) => TOOL_NAMES.includes(name)), ["plan"]);
  // A Manager and a worker never get the planner's face.
  assert.equal(visibleToolsFor(ROLE.MANAGER, all).includes("plan"), false);
  assert.equal(visibleToolsFor(ROLE.CODER, all).includes("plan"), false);
});

test("the role's own tools are kept even before the host lists them", () => {
  // `getActiveTools()` at `session_start` does not yet contain the tools this
  // extension registered, so the narrowing must not filter its own tools by
  // that set; unrelated names still pass through untouched.
  // Derived from the face constants, not written out: the faces are facts about
  // this adapter, and a literal list here broke every time one was added.
  assert.deepEqual(visibleToolsFor(ROLE.MANAGER, ["ask"]), ["ask", ...MANAGER_TOOLS]);
  // `ask` is a host tool, not one of the replaced set, so a worker keeps it; the
  // worker's own face and the control tools are added.
  assert.deepEqual(visibleToolsFor(ROLE.CODER, ["ask"]), ["ask", ...WORKER_TOOLS.filter((name) => name !== "js-manager")]);
});

test("the review face is visible only while a Manager's evaluation is open", () => {
  // Spec §8: `js-review` is not a permanent face. It appears for the Manager
  // during an evaluation and goes away when the round's review is accepted.
  const all = ["ask", ...TOOL_NAMES];
  // Closed: not offered.
  assert.equal(visibleToolsFor(ROLE.MANAGER, all, [], { evaluating: false }).includes("js-review"), false);
  // Open: offered, beside the Manager's own faces.
  const open = visibleToolsFor(ROLE.MANAGER, all, [], { evaluating: true });
  assert.equal(open.includes("js-review"), true);
  for (const name of MANAGER_TOOLS) assert.equal(open.includes(name), true, name);
  // The review face is read-only: it does not bring `js-worker` with it.
  assert.equal(open.includes("js-worker"), false);
  // A worker never gets it, even if the phase were somehow open for it.
  assert.equal(visibleToolsFor(ROLE.CODER, all, [], { evaluating: true }).includes("js-review"), false);
});

test("a Manager's evaluation opens at the request boundary and closes at the round's first accepted review", async () => {
  // Spec §2.3: the window is driven by the host's own request boundary, not a
  // turn counter. `before_agent_start` opens it; the round's FIRST accepted
  // review shuts it; a refused review leaves it open, because the Manager has
  // not judged.
  const host = new HostAdapter({
    pi: { registerTool: () => {}, getActiveTools: () => [], setActiveTools: async () => {} },
    ctx: null,
  });
  assert.equal(host.evaluating, false);
  assert.equal(host.beginEvaluation(), true);
  assert.equal(host.evaluating, true);
  // Idempotent: a second request of the same round does not flip it back, and
  // it reports that the phase did NOT move — that is what keeps `setActiveTools`
  // out of the second preparation (§7.3).
  assert.equal(host.beginEvaluation(), false);
  assert.equal(host.evaluating, true);
  assert.equal(host.endEvaluation(), false);
  assert.equal(host.evaluating, false);

  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => `sess-review-phase-${Date.now().toString(36)}`, getEntries: () => [] },
    };
    const seen = [];
    const judged = new testSession({
      pi: { registerTool: () => {} },
      api: null,
      ctx,
      role: ROLE.MANAGER,
      onReviewAccepted: (receipt) => seen.push(receipt.kind),
    });
    // A review only means something inside a round: the host opens one.
    const open = () => judged.state.window.beginRound();
    open();
    assert.equal(judged.state.window.state, "open");

    // The FIRST accepted review of the round shuts the window, once.
    const accepted = await judged.review([{ key: "a", value: "x" }]);
    assert.equal(accepted.ok, true);
    assert.deepEqual(seen, ["recorded"]);
    assert.equal(judged.state.window.state, "closed", "the round's first accepted review shut the window");

    // A later accepted review in the SAME round does not notify again: the
    // window is already shut, so the face is already gone.
    const empty = await judged.review([]);
    assert.equal(empty.ok, true);
    assert.equal(empty.added, 0);
    assert.equal(seen.length, 1, "one round, one acceptance, one notification");
    assert.equal(judged.state.window.state, "closed");

    // A NEW round opens the evaluation again, and its first acceptance shuts it.
    open();
    assert.equal(judged.state.window.state, "open");
    const closing = await judged.review([{ key: "a", value: "y" }]);
    assert.equal(closing.ok, true);
    assert.deepEqual(seen, ["recorded", "recorded"]);
    assert.equal(judged.state.window.state, "closed");
  });
});

// ---------------------------------------------------------------------------
// `js-review` evaluation window (root §2.3)
// ---------------------------------------------------------------------------

test("the review face is never offered to a worker, whatever the window says", () => {
  const all = ["ask", ...TOOL_NAMES];
  for (const role of [ROLE.CODER, ROLE.DEVOPS]) {
    assert.equal(visibleToolsFor(role, all, [], { evaluating: true }).includes("js-review"), false, role);
    assert.equal(surfaceAllowed(role, "js-review", { window: { open: true } }), false, role);
    assert.equal(surfaceAllowed(role, "js-review"), false, role);
  }
});

test("surfaceAllowed asks the window, and an absent window is a SHUT window", () => {
  // A worker is refused even with an open window: the role test comes first.
  assert.equal(surfaceAllowed(ROLE.MANAGER, "js-review", { window: { open: true } }), true);
  assert.equal(surfaceAllowed(ROLE.MANAGER, "js-review", { window: { open: false } }), false);
  // An absent window is NOT an open one: a session with no window has not
  // opened an evaluation round, and reading that as "open" admitted the
  // read-only face to a Manager nobody had evaluated with.
  assert.equal(surfaceAllowed(ROLE.MANAGER, "js-review"), false);
  assert.equal(surfaceAllowed(ROLE.MANAGER, "js-review", { window: null }), false);
  // The Manager's other faces are unaffected by the window.
  assert.equal(surfaceAllowed(ROLE.MANAGER, "js-manager", { window: { open: false } }), true);
});

test("the tool gate refuses a js-review call once the round's review is accepted", async () => {
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => `sess-gate-${Date.now().toString(36)}`, getEntries: () => [] },
    };
    const judged = new testSession({ pi: { registerTool: () => {} }, api: null, ctx, role: ROLE.MANAGER });
    // The window is read per call, so the gate sees the round this test opens.
    // A gate given NO window is a shut one (§2.3): an absent window is not an
    // open evaluation.
    const gate = createToolCallHandler({
      role: ROLE.MANAGER,
      scopeAdmit: () => ({ ok: true }),
      reviewWindow: judged.state.window,
    });
    assert.equal((await createToolCallHandler({ role: ROLE.MANAGER, scopeAdmit: () => ({ ok: true }) })({ toolName: "js-review" })).block, true);
    // Open round: the gate lets the call through to Scope admission.
    judged.state.window.beginRound();
    assert.equal(await gate({ toolName: "js-review" }), undefined);
    // Accept a review: the face is gone, and the gate says why.
    await judged.review([{ key: "a", value: "x" }]);
    const blocked = await gate({ toolName: "js-review" });
    assert.equal(blocked.block, true);
    assert.match(blocked.reason, /REVIEW_WINDOW_CLOSED/);
    // A worker's call is refused for the role, not for the window.
    const workerGate = createToolCallHandler({
      role: ROLE.CODER,
      scopeAdmit: () => ({ ok: true }),
      reviewWindow: judged.state.window,
    });
    const workerBlocked = await workerGate({ toolName: "js-review" });
    assert.equal(workerBlocked.block, true);
    assert.doesNotMatch(workerBlocked.reason, /REVIEW_WINDOW_CLOSED/);
    // An unproven role owns no face, so every face call is refused for the
    // missing reading — not for the window, and not as a worker's.
    const unprovenGate = createToolCallHandler({ role: ROLE.UNPROVEN, scopeAdmit: () => ({ ok: true }) });
    for (const toolName of ["js-manager", "js-worker", "plan", "js-review"]) {
      const refused = await unprovenGate({ toolName });
      assert.equal(refused.block, true, toolName);
      assert.match(refused.reason, /is not available to this session/, toolName);
    }
  });
});

test("buildSurface refuses a js-review program through a shut window", async () => {
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => `sess-build-${Date.now().toString(36)}`, getEntries: () => [] },
    };
    const judged = new testSession({ pi: { registerTool: () => {} }, api: null, ctx, role: ROLE.MANAGER });
    judged.state.window.beginRound();
    const open = judged.buildSurface({
      toolCallId: "call-open",
      surfaceName: "js-review",
      source: "class Js extends JsProgram { async run() { return 1; } }",
      intent: "read",
    });
    assert.equal(typeof open.surface.reviewSurface().cat, "function");
    judged.closeSurface("call-open");
    await judged.review([{ key: "a", value: "x" }]);
    assert.throws(
      () =>
        judged.buildSurface({
          toolCallId: "call-shut",
          surfaceName: "js-review",
          source: "class Js extends JsProgram { async run() { return 1; } }",
          intent: "read",
        }),
      (error) => error.code === "REVIEW_WINDOW_CLOSED",
    );
    // A worker session never builds the review face at all.
    const worker = new testSession({ pi: { registerTool: () => {} }, api: null, ctx: { ...ctx, agent: { kind: "sub", id: "c1", name: "coder", parentId: "Main" } }, role: ROLE.CODER });
    assert.throws(
      () =>
        worker.buildSurface({
          toolCallId: "call-worker",
          surfaceName: "js-review",
          source: "class Js extends JsProgram { async run() { return 1; } }",
          intent: "read",
        }),
      (error) => error.code === "REVIEW_WINDOW_CLOSED",
    );
  });
});

test("turn_start opens the round; before_agent_start is idempotent and never calls setActiveTools", async () => {
  // §7.3: `before_agent_start` must NOT call `setActiveTools` (it makes the
  // host re-run policy preparation). §2.3's round therefore opens at the
  // host's model-turn boundary (`turn_start`), once per round. The prompt the
  // request hook returns is still byte-identical on every call.
  await withRoot(async () => {
    const hooks = new Map();
    const activeWrites = [];
    let active = ["read", "grep", "glob", "write", "edit", "bash", "eval", "task", "todo"];
    const api = {
      registerTool: () => {},
      getActiveTools: () => [...active],
      setActiveTools: async (names) => {
        activeWrites.push([...names]);
        active = [...names];
      },
      on: (event, handler) => {
        if (!hooks.has(event)) hooks.set(event, []);
        hooks.get(event).push(handler);
      },
    };
    herdchestra(api);
    // A fresh session id per run: the adapter registry is process-wide, and a
    // reused id would hand this test an adapter another test already narrowed.
    const sessionId = `sess-idem-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
    const ctx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => sessionId, getEntries: () => [] },
    };
    const turn = hooks.get("turn_start")[0];
    const start = hooks.get("before_agent_start")[0];
    // The round opens at the model-turn boundary, and the review face becomes
    // visible there.
    await turn({ type: "turn_start", turnIndex: 0, timestamp: Date.now() }, ctx);
    const writesAfterTurn = activeWrites.length;
    assert.ok(writesAfterTurn >= 1, "the first turn of a session narrows at least once");
    assert.equal(activeWrites.at(-1).includes("js-review"), true, "the round's face is visible after turn_start");
    const first = await start({ type: "before_agent_start", reason: "prompt", prompt: "hi", systemPrompt: [] }, ctx);
    // The request hook itself writes the tool set ZERO times.
    assert.equal(activeWrites.length, writesAfterTurn, "before_agent_start must not call setActiveTools");
    const second = await start({ type: "before_agent_start", reason: "prompt", prompt: "hi", systemPrompt: [] }, ctx);
    // Same bytes, every time: the prompt is a pure function of the role.
    assert.deepEqual(second, first);
    assert.equal(second.systemPrompt.length, 1);
    // A second preparation of the same request writes the tool set ZERO more
    // times.
    assert.equal(activeWrites.length, writesAfterTurn, "a repeated request must not call setActiveTools again");
    // And a third, for good measure: still nothing.
    await start({ type: "before_agent_start", reason: "prompt", prompt: "hi", systemPrompt: [] }, ctx);
    assert.equal(activeWrites.length, writesAfterTurn);
    // A second turn of the SAME round (no review accepted) also writes nothing.
    await turn({ type: "turn_start", turnIndex: 1, timestamp: Date.now() }, ctx);
    assert.equal(activeWrites.length, writesAfterTurn, "a repeated round must not call setActiveTools again");
    // The review face is visible exactly while the round is open.
    assert.equal(active.includes("js-review"), true);
    // The adapter registry is process-wide; a unique session id keeps this test
    // from inheriting another's narrowed adapter, and the id is not reused.
  });
});

test("the adapter's visible set follows the session window, not a flag of its own", async () => {
  // The window is the authority (§2.3): a review accepted while the adapter's
  // phase flag is still set must still hide the face.
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => `sess-visible-${Date.now().toString(36)}`, getEntries: () => [] },
    };
    const host = new HostAdapter({
      pi: { registerTool: () => {}, getActiveTools: () => [], setActiveTools: async () => {} },
      ctx,
    });
    assert.equal(host.beginEvaluation(), true);
    assert.equal(host.evaluating, true);
    // The session's window is shut, so the adapter must not report an open
    // evaluation even though `beginEvaluation` set its own flag.
    host.reviewWindow.noteReview({ ok: true });
    assert.equal(host.reviewWindow.open, false);
    assert.equal(host.evaluating, false);
  });
});

// ---------------------------------------------------------------------------
// Program runtime
// ---------------------------------------------------------------------------

// A minimal program host: one core ProgramState, one park table, and a surface
// whose members are the test's own. It stands in for compose's `ProgramSurface`
// so the runtime is tested without a host.
const runtimeFor = (source, { members = {}, settleState = null } = {}) => {
  const program = new ProgramState("p1");
  const parks = new ParkTable();
  // The `park` member, exactly as compose's members park the program: core's
  // state parks, the member returns the receipt, and the runtime's guard turns
  // that into the pending promise the body waits on.
  const parkMember = (payload) => {
    program.park({ reason: "explicit", payload });
    return { name: "p1", state: "parked", reason: "explicit", payload };
  };
  const surface = {
    program,
    settle: async (state, result) => {
      if (settleState !== null) settleState.push([state, result]);
      if (!program.terminal) program.finish(state, result);
      return program;
    },
    receipt: (extra = {}) => ({ name: "p1", state: program.state, ...extra }),
    finalReceipt: () => ({ name: "p1", state: program.state, result: program.result }),
    surface: () => ({ ...members, park: members.park ?? parkMember }),
  };
  return { run: startProgram({ source, surface, program, parks }), program, parks, surface };
};

test("a program runs with the members the surface has", async () => {
  const { run } = runtimeFor(
    `
      class Js extends JsProgram {
        async run() {
          const text = await this.cat("a.txt");
          const spans = this.whereAll(text, "of");
          return { text, spans };
        }
      }
    `,
    {
      members: {
        cat: async (p) => `text of ${p}`,
        whereAll: (text, needle) => [[text.indexOf(needle), text.indexOf(needle) + needle.length]],
      },
    },
  );
  const outcome = await run.outcome;
  assert.equal(outcome.status, "completed");
  assert.equal(outcome.value.text, "text of a.txt");
  assert.deepEqual(outcome.value.spans, [[5, 7]]);
});

test("a member the surface lacks is a TypeError, and the program fails", async () => {
  const settled = [];
  const { run } = runtimeFor(
    "class Js extends JsProgram { async run() { return this.write('a', 'b'); } }",
    { settleState: settled },
  );
  const outcome = await run.outcome;
  assert.equal(outcome.status, "failed");
  assert.equal(outcome.error.name, "TypeError");
  // A body that threw is a failed run, and it is settled as such.
  assert.equal(settled[0][0], "failed");
});

test("a park leaves the body suspended and unpark continues it in place", async () => {
  const seen = [];
  const { run, program, parks } = runtimeFor(
    `
      class Js extends JsProgram {
        async run() {
          this.before();
          const injected = await this.park({ why: "wait" });
          this.after(injected);
          return { injected };
        }
      }
    `,
    {
      members: {
        before: () => seen.push("before"),
        after: (value) => seen.push(["after", value]),
      },
    },
  );
  const parked = await run.parked;
  assert.equal(parked.status, "parked");
  assert.equal(program.state, "parked");
  // The body is alive: the park answered the call, but `run()` has not ended.
  assert.deepEqual(seen, ["before"]);
  let ended = false;
  void run.outcome.then(() => {
    ended = true;
  });
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(ended, false);
  // `unpark` does what core's `takeSite` does — the program leaves `parked` for
  // `running` — and settles exactly that promise, so the body continues where
  // it stopped.
  program.moveTo("running");
  assert.equal(parks.settle("p1", [7, 9]), true);
  const outcome = await run.outcome;
  assert.equal(outcome.status, "completed");
  assert.deepEqual(outcome.value, { injected: [7, 9] });
  // Nothing before the park ran again: "before" is there once.
  assert.deepEqual(seen, ["before", ["after", [7, 9]]]);
  // The body is over, so its park entry is released: nothing can resume it.
  assert.equal(parks.size, 0);
});

test("source that is not a Js subclass is refused", async () => {
  await assert.rejects(() => compileProgram("const x = 1;"), TypeError);
  await assert.rejects(() => compileProgram("   "), TypeError);
  assert.equal(typeof JsProgram, "function");
});

test("bindMembers copies only the members the surface has", async () => {
  const Program = await compileProgram("class Js extends JsProgram { async run() { return 1; } }");
  const instance = bindMembers(new Program({}), { cat: async () => "x" });
  assert.equal(typeof instance.cat, "function");
  assert.equal(instance.write, undefined);
});

// ---------------------------------------------------------------------------
// Host binding
// ---------------------------------------------------------------------------

test("the binding keeps host state only: no dispatcher, no second agent route", () => {
  const binding = new HostBinding({ pi: { VERSION: "18.14.1" }, ctx: { ui: null } });
  assert.equal(binding.pi.VERSION, "18.14.1");
  assert.equal(binding.ctx.ui, null);
  // Root §1.1 item 3: Plan's devops is a dedicated lane, and fork/join are
  // lanes too. A `task`-based route would be the second, non-dedicated one.
  assert.equal(binding.runAgent, undefined);
  assert.equal(binding.spawn, undefined);
  assert.equal(binding.steer, undefined);
  assert.equal(binding.progressOf, undefined);
  assert.equal(binding.invoke, undefined);
  assert.equal(CAPABILITY.STEER_UNDELIVERED, "STEER_UNDELIVERED");
});

// ---------------------------------------------------------------------------
// Workers: the host's own `task` subagents
// ---------------------------------------------------------------------------

test("fork creates a real session and returns at agent_start, not at turn end", async () => {
  const session = fakeSession({ agentId: "coder-1" });
  const api = fakeApi([session]);
  const lanes = trackedLaneTable(new LaneTable({ api }));
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: () => "coder" });

  const receipt = await port.fork("coder-1", "role: coder — do the thing");
  assert.deepEqual(receipt, { name: "coder-1", steered: false, started: true });
  // One session came from the SDK and its first turn started.
  assert.equal(api.created.length, 1);
  assert.deepEqual(session.calls[0], ["prompt", "role: coder — do the thing"]);
  assert.equal(lanes.live("coder-1")?.agentId, "coder-1");
});

test("a program's bare fork()/join() reach the worker port, which compose reads as ports.fork", async () => {
  // compose reads `ports.fork.fork` and `ports.fork.join` (surface.mjs:307/309).
  await withRoot(async () => {
    const session = fakeSession({ agentId: "w1" });
    const api = fakeApi([session]);
    const ctx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => `sess-port-${Date.now().toString(36)}`, getEntries: () => [] },
    };
    const host = new testSession({ pi: null, api, ctx, role: ROLE.MANAGER });
    const built = host.buildSurface({
      toolCallId: "call-port",
      surfaceName: "js-manager",
      source: "class Js extends JsProgram { async run() {} }",
      intent: "port",
    });
    const members = built.surface.surface();
    // A program member call is not a tool call, so it carries no scope.
    // §2.1: the worker's identity travels in its verboseIntent, so the
    // verboseIntent declares it. An intent that names none is refused by
    // compose, not defaulted here.
    const forked = await members.fork("w1", "role: coder — do the work");
    assert.deepEqual(forked, { name: "w1", steered: false, started: true });
    assert.equal(api.created.length, 1);
    // §3.1: `fork` is a top-level primitive, so it is bare in the body and
    // absent from the instance; every other member is the other way round.
    const source = `
      class Js extends JsProgram {
        async run() {
          const started = await fork("w1", "role: coder — do the work");
          return { started, hasFork: typeof this.fork, hasJoin: typeof this.join, hasReview: typeof this.review, hasCat: typeof this.cat };
        }
      }
    `;
    const primitives = await compileProgram(source, primitiveScope(members));
    const instance = bindMembers(new primitives({}), members);
    // The live worker of that name is steered by the second call, and the
    // Manager face has no file members, so `this.cat` is absent too.
    assert.deepEqual(await instance.run(), {
      started: { name: "w1", steered: true, started: false },
      hasFork: "undefined",
      hasJoin: "undefined",
      hasReview: "undefined",
      hasCat: "undefined",
    });
    host.closeSurface("call-port");
  });
});

test("a session the host refuses to create is reported and tracked as nothing", async () => {
  const api = {
    pi: {
      async createAgentSession() {
        throw new Error("model unavailable");
      },
    },
  };
  const lanes = trackedLaneTable(new LaneTable({ api }));
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: () => "coder" });
  const receipt = await port.fork("w1", "role: coder — work");
  assert.equal(receipt.started, false);
  assert.equal(receipt.reason, "SESSION_CREATE_FAILED");
  assert.match(receipt.detail, /model unavailable/);
  assert.equal(lanes.size, 0);
});

test("a worker session gets the worker face installed on it, built for that worker", async () => {
  // `toolNames` alone would name tools the session has no implementation for,
  // and a session created through the SDK cannot be narrowed afterwards (its
  // runtime is not initialized by the host). So the lane installs the worker's
  // own tools on that session, after the host creates it.
  await withRoot(async () => {
    const managerTools = new Map();
    const workerTools = new Map();
    const created = [];
    const pi = {
      registerTool: (tool) => managerTools.set(tool.name, tool),
      getActiveTools: () => [],
      setActiveTools: async () => {},
      on: () => {},
      pi: {
        async createAgentSession(options) {
          created.push(options);
          return {
            session: {
              ...fakeSession({ agentId: options.agentId }),
              extensionRunner: {
                initialize() {},
                async emit(event) {
                  const handshake = event?.herdchestraHandshake;
                  if (handshake !== undefined) {
                    handshake.reply({ token: handshake.token, bound: true, narrowed: true, role: "coder", reason: null });
                  }
                },
              },
            },
          };
        },
      },
    };
    const ctx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => `sess-face-${Date.now().toString(36)}`, getEntries: () => [] },
    };
    const adapter = new HostAdapter({ pi, ctx });
    for (const tool of adapter.tools()) pi.registerTool(tool);
    await adapter.install();
    // The lane builds the worker's API from the parent's: `registerTool` is the
    // parent's, and the worker's own tools are registered through it.
    const workerApi = workerExtensionApi(pi, null);
    const recordingApi = {
      registerTool: (tool) => workerTools.set(tool.name, tool),
      pi: workerApi.pi,
      lanes: workerApi.lanes,
    };
    const built = workerSessionTools(recordingApi);
    const session = {
      ...fakeSession({ agentId: "w1" }),
      agent: { kind: "sub", id: "w1", name: "w1", depth: 1, parentId: "Main" },
      extensionRunner: { initialize() {}, async emit() {} },
    };
    const installed = await built.install(session);
    assert.equal(installed.ok, true);
    // The WORKER face and nothing else: `js-manager` and `js-review` belong to
    // the Manager, and installing them here was the leak that also handed
    // workers `plan`. The session's table is what the host offers it, so a face
    // its role does not own must never be written there.
    assert.deepEqual([...workerTools.keys()].sort(), ["fork", "join", "js-worker", "negotiate", "askPreviousScope"].sort());
    for (const tool of workerTools.values()) {
      assert.equal(typeof tool.execute, "function", tool.name);
    }
    // The worker's tools are not the Manager's objects.
    assert.notEqual(workerTools.get("js-worker"), managerTools.get("js-worker"));
    // A session created by the SDK receives no `customTools` from the parent:
    // the face is installed on the session that was created.
    assert.equal(created.length, 0);
  });
});

test("an SDK-created worker asks for no replaced host tool, and its own face replaces them", async () => {
  // §7.3 removes the host's own read/grep/glob/write/edit/bash/eval/task from a
  // worker. The host builds that set when it CONSTRUCTS a session
  // (`tools/index.ts:803-812`: a requested name is kept only if
  // `isToolAllowed`, and every `REPLACED_TOOLS` name is a built-in), so the
  // `toolNames` this adapter passes must name none of them — and must name the
  // whole worker face, or the worker would run with no program tool at all.
  await withRoot(async () => {
    const created = [];
    const api = {
      registerTool: () => {},
      getActiveTools: () => [...REPLACED_TOOLS, "js-worker", "fork", "join"],
      setActiveTools: async () => {},
      pi: {
        async createAgentSession(options) {
          created.push(options);
          return { session: fakeSession({ agentId: options.agentId }) };
        },
      },
    };
    const lanes = trackedLaneTable(new LaneTable({ api, workerToolNames: [...WORKER_LANE_TOOLS] }));
    const started = await lanes.start({ name: "w-face-list", role: "coder", verboseIntent: "role: coder — go" });
    assert.equal(started.ok, true, JSON.stringify(started));
    assert.equal(created.length, 1);
    const requested = created[0].toolNames ?? [];
    // Not one replaced host tool is asked for.
    for (const replaced of REPLACED_TOOLS) {
      assert.equal(requested.includes(replaced), false, `the worker must not request ${replaced}`);
    }
    // The worker's own face is asked for, by name, in full.
    for (const name of WORKER_LANE_TOOLS) {
      assert.equal(requested.includes(name), true, `the worker must request ${name}`);
    }
    // The face is fixed at creation: no `customTools`, no post-hoc narrowing.
    assert.equal(created[0].customTools, undefined);
  });
});

test("a worker session is never handed a face its role does not own", async () => {
  // `install` registers onto the SESSION's own tool table, which is what the
  // host offers that session. Registering everything would hand a worker the
  // planner's face — a capability its role must not have, and one the gate
  // would then have to refuse on every call.
  await withRoot(async () => {
    const built = workerSessionTools({ registerTool: () => {} });
    const session = { ...fakeSession({ agentId: "w-face" }), tools: new Map() };
    const installed = await built.install(session, { only: [...WORKER_LANE_TOOLS] });
    assert.equal(installed.ok, true);
    assert.deepEqual([...session.tools.keys()].sort(), [...WORKER_LANE_TOOLS].sort());
    for (const name of ["plan", "js-manager", "js-review"]) {
      assert.equal(session.tools.has(name), false, name);
    }

    // The same must hold when the caller passes NO `only` at all. The face is
    // derived from the SESSION's role, so forgetting the argument cannot leak a
    // foreign face — which is exactly how `plan` reached a coder's table.
    const bare = workerSessionTools({ registerTool: () => {} });
    const bareSession = { ...fakeSession({ agentId: "w-bare" }), tools: new Map() };
    assert.equal((await bare.install(bareSession)).ok, true);
    assert.deepEqual([...bareSession.tools.keys()].sort(), [...WORKER_LANE_TOOLS].sort());
    assert.equal(bareSession.tools.has("plan"), false, "a coder must never be handed the planner's face");
  });
});

test("a worker session names the role the verboseIntent declared, not the worker's name", async () => {
  const api = fakeApi();
  const lanes = trackedLaneTable(new LaneTable({ api }));
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: agentForRole });
  await port.fork("devops", "role: devops — check the build");
  await port.fork("coder-1", "role: coder — edit the file");
  assert.equal(api.created[0].agentName, "devops");
  assert.equal(api.created[0].agentId, "devops");
  assert.equal(api.created[1].agentName, "coder");
  // §2.1, §4.1: the name is not an identity. A worker NAMED devops whose
  // verboseIntent declares coder is a coder, and an intent that declares
  // nothing starts nothing.
  await port.fork("devops-2", "role: coder — edit the file");
  assert.equal(api.created[2].agentName, "coder");
  const refused = await port.fork("w-undeclared", "check the build");
  assert.equal(refused.started, false);
  assert.equal(refused.reason, "UNDECLARED_ROLE");
  const ambiguous = await port.fork("w-both", "role: coder, devops — check the build");
  assert.equal(ambiguous.reason, "AMBIGUOUS_ROLE");
});

test("a worker's runtime actions reach its own session, not a placeholder", async () => {
  // The host never initializes the runtime of a session created through the SDK,
  // so the lane wires it with the host's own action set (`runtime-init.ts`). Each
  // action must reach the session: a placeholder answering "false" would tell the
  // worker's adapter that work happened when it did not.
  const seen = [];
  const session = fakeSession({ agentId: "w6" });
  Object.assign(session, {
    getEnabledToolNames: () => ["js-worker", "js-manager", "fork", "join"],
    getAllToolInfos: () => [{ name: "js-worker", source: "extension" }],
    setActiveToolsByName: async (names) => {
      seen.push(["setActiveTools", names]);
    },
    sendCustomMessage: async (message) => {
      seen.push(["sendCustomMessage", message.customType]);
      return true;
    },
    sendUserMessage: async (content) => {
      seen.push(["sendUserMessage", content]);
    },
    sessionManager: {
      appendCustomEntry: (type, data) => seen.push(["appendCustomEntry", type, data]),
      appendLabelChange: (id, label) => seen.push(["appendLabelChange", id, label]),
      getSessionName: () => "worker six",
      setSessionName: async (name) => seen.push(["setSessionName", name]),
    },
    modelRegistry: { getApiKey: async () => "key" },
    setModel: async (model) => seen.push(["setModel", model]),
    thinkingLevel: "medium",
    setThinkingLevel: (level) => seen.push(["setThinkingLevel", level]),
    serviceTierByFamily: { openai: "flex" },
    setServiceTierFamily: (family, tier) => seen.push(["setServiceTierFamily", family, tier]),
    model: { id: "m" },
    abort: async (options) => seen.push(["abort", options.reason]),
    queuedMessageCount: 0,
    getContextUsage: () => ({ tokens: 1 }),
    systemPrompt: ["system"],
    runEphemeralTurn: async (args) => ({ text: "ephemeral", args }),
    compact: async (instructions) => seen.push(["compact", instructions]),
    waitForIdle: async () => seen.push(["waitForIdle"]),
    newSession: async () => true,
    branch: async () => ({ cancelled: true }),
    navigateTree: async () => ({ cancelled: false }),
    switchSession: async () => false,
    reload: async () => seen.push(["reload"]),
    customCommands: [],
    skills: [],
  });
  let wired = null;
  session.extensionRunner = {
    initialize(actions, contextActions, commandActions) {
      wired = { actions, contextActions, commandActions };
    },
    getRegisteredCommands: () => [{ name: "herd", description: "herd command" }],
    async emit(event) {
      const handshake = event?.herdchestraHandshake;
      if (handshake !== undefined) {
        handshake.reply({ token: handshake.token, bound: true, narrowed: true, role: "coder", reason: null });
      }
    },
  };
  const api = fakeApi([session]);
  const lanes = trackedLaneTable(new LaneTable({ api }));
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: () => "coder" });
  const receipt = await port.fork("w6", "role: coder — work");
  assert.equal(receipt.started, true);
  assert.notEqual(wired, null, "the lane wired the worker's runtime before the handshake");

  // The tool actions the adapter's narrowing and the worker's tools use.
  assert.deepEqual(wired.actions.getActiveTools(), ["js-worker", "js-manager", "fork", "join"]);
  assert.deepEqual(wired.actions.getAllTools(), [{ name: "js-worker", source: "extension" }]);
  await wired.actions.setActiveTools(["js-worker"]);
  assert.deepEqual(seen.at(-1), ["setActiveTools", ["js-worker"]]);
  assert.deepEqual(wired.actions.getCommands().map((command) => [command.name, command.source]), [
    ["herd", "extension"],
  ]);

  // The model action asks the registry first, exactly as the host does.
  assert.equal(await wired.actions.setModel({ id: "m2" }), true);
  assert.deepEqual(seen.at(-1), ["setModel", { id: "m2" }]);
  session.modelRegistry.getApiKey = async () => undefined;
  assert.equal(await wired.actions.setModel({ id: "m3" }), false);
  assert.deepEqual(seen.at(-1), ["setModel", { id: "m2" }], "no model set without a key");

  // Sends and session bookkeeping reach the session's own API. The answer is
  // the session's ("true iff a turn started", agent-session.ts:10054), passed
  // through unchanged.
  assert.equal(await wired.actions.sendMessage({ customType: "herd" }), true);
  assert.deepEqual(seen.at(-1), ["sendCustomMessage", "herd"]);
  wired.actions.appendEntry("herd-note", { a: 1 });
  assert.deepEqual(seen.at(-1), ["appendCustomEntry", "herd-note", { a: 1 }]);
  wired.actions.setLabel("entry-1", "reviewed");
  assert.deepEqual(seen.at(-1), ["appendLabelChange", "entry-1", "reviewed"]);
  assert.equal(wired.actions.getSessionName(), "worker six");
  await wired.actions.setSessionName("renamed");
  assert.deepEqual(seen.at(-1), ["setSessionName", "renamed"]);
  assert.equal(wired.actions.getThinkingLevel(), "medium");
  wired.actions.setThinkingLevel("high");
  assert.deepEqual(seen.at(-1), ["setThinkingLevel", "high"]);
  assert.deepEqual(wired.actions.getServiceTiers(), { openai: "flex" });
  wired.actions.setServiceTier("anthropic", "flex");
  assert.deepEqual(seen.at(-1), ["setServiceTierFamily", "anthropic", "flex"]);

  // Context actions report the session's own state. The lane started the
  // worker's first turn, so the session is streaming and `isIdle` is false —
  // the action mirrors `session.isStreaming`, it does not answer from a constant.
  assert.deepEqual(wired.contextActions.getModel(), { id: "m" });
  assert.equal(wired.contextActions.isIdle(), false);
  session.isStreaming = false;
  assert.equal(wired.contextActions.isIdle(), true);
  assert.equal(wired.contextActions.hasPendingMessages(), false);
  assert.deepEqual(wired.contextActions.getContextUsage(), { tokens: 1 });
  assert.deepEqual(wired.contextActions.getSystemPrompt(), ["system"]);
  assert.deepEqual(await wired.contextActions.runEphemeralTurn({ promptText: "x" }), {
    text: "ephemeral",
    args: { promptText: "x" },
  });
  await wired.contextActions.compact("tighten");
  assert.deepEqual(seen.at(-1), ["compact", "tighten"]);
  wired.contextActions.abort();
  await Promise.resolve();
  assert.deepEqual(seen.at(-1), ["abort", "Interrupted by user"]);

  // Command actions forward to the session's own transitions.
  await wired.commandActions.waitForIdle();
  assert.deepEqual(seen.at(-1), ["waitForIdle"]);
  assert.deepEqual(await wired.commandActions.newSession({}), { cancelled: false });
  assert.deepEqual(await wired.commandActions.branch("entry-1"), { cancelled: true });
  assert.deepEqual(await wired.commandActions.navigateTree("entry-1"), { cancelled: false });
  assert.deepEqual(await wired.commandActions.switchSession("/tmp/other.jsonl"), { cancelled: true });
  await wired.commandActions.reload();
  assert.deepEqual(seen.at(-1), ["reload"]);
});

test("a worker session missing an action's target is refused, not wired with a placeholder", async () => {
  // A placeholder would answer for work that never reached the session. The lane
  // refuses instead and disposes the half-built session.
  const session = fakeSession({ agentId: "w7" });
  delete session.sessionManager;
  let initialized = false;
  session.extensionRunner = {
    initialize() {
      initialized = true;
    },
    async emit(event) {
      const handshake = event?.herdchestraHandshake;
      if (handshake !== undefined) {
        handshake.reply({ token: handshake.token, bound: true, narrowed: true, role: "coder", reason: null });
      }
    },
  };
  const api = fakeApi([session]);
  const lanes = trackedLaneTable(new LaneTable({ api }));
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: () => "coder" });
  const receipt = await port.fork("w7", "role: coder — work");
  assert.equal(receipt.started, false);
  assert.equal(receipt.reason, "RUNTIME_INIT_FAILED");
  assert.match(receipt.detail, /sessionManager/);
  assert.equal(initialized, false, "an incomplete session is never wired");
  assert.equal(lanes.size, 0);
  assert.equal(session.calls.some(([kind]) => kind === "prompt"), false);
  assert.equal(session.calls.some(([kind]) => kind === "dispose"), true);
});

test("a worker whose adapter did not answer the handshake is refused", async () => {
  // `hasHandlers()` is not evidence: another extension can satisfy it (measured
  // on the real host, where a worker started with the host's default face). Only
  // this adapter's own answer authorizes a worker.
  const session = fakeSession({ agentId: "w4" });
  session.extensionRunner = { initialize() {}, async emit() {} };
  const api = fakeApi([session]);
  const lanes = trackedLaneTable(new LaneTable({ api }));
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: () => "coder" });
  const receipt = await port.fork("w4", "role: coder — work");
  assert.equal(receipt.started, false);
  assert.equal(receipt.reason, "WORKER_NOT_AUTHORIZED");
  assert.match(receipt.detail, /no Herdchestra adapter answered/);
  assert.equal(lanes.size, 0);
  assert.equal(session.calls.some(([kind]) => kind === "prompt"), false);
});

test("a worker whose adapter answered without narrowing is refused", async () => {
  const session = fakeSession({ agentId: "w5" });
  session.extensionRunner = {
    initialize() {},
    async emit(event) {
      const handshake = event?.herdchestraHandshake;
      if (handshake === undefined) return;
      handshake.reply({
        token: handshake.token,
        bound: true,
        narrowed: false,
        role: "coder",
        reason: "Extension runtime not initialized",
      });
    },
  };
  const api = fakeApi([session]);
  const lanes = trackedLaneTable(new LaneTable({ api }));
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: () => "coder" });
  const receipt = await port.fork("w5", "role: coder — work");
  assert.equal(receipt.started, false);
  assert.equal(receipt.reason, "WORKER_NOT_AUTHORIZED");
  assert.match(receipt.detail, /did not narrow/);
  assert.match(receipt.detail, /Extension runtime not initialized/);
});

test("a host with no session factory refuses the start and tracks nothing", async () => {
  // The real factory gets only `api` and offers no session factory here.
  const lanes = trackedLaneTable(new LaneTable({ api: { pi: {} } }));
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: () => "coder" });
  const receipt = await port.fork("w1", "role: coder — work");
  assert.equal(receipt.started, false);
  assert.equal(receipt.reason, "NO_SESSION_FACTORY");
  assert.equal(lanes.size, 0);
});

test("ask answers with the asked receipt, and a synchronous dialog adds the answer", async () => {
  const questions = [];
  const ui = { input: async (_title, prompt) => `answer to ${prompt}` };
  const port = askPort({ ui, questions });
  const receipt = await port.ask("which file?");
  // `ask` is park + interrupt: the receipt says the question was asked and the
  // turn must end. A dialog that answered in-call adds its words.
  assert.equal(receipt.ok, true);
  assert.equal(receipt.asked, true);
  assert.equal(receipt.question, "which file?");
  assert.equal(receipt.interrupt, true);
  assert.equal(receipt.answer, "answer to which file?");
  assert.equal(questions[0].answered, true);
});

test("a dismissed dialog still asks: the operator answers in the next message", async () => {
  const questions = [];
  const port = askPort({ ui: { input: async () => undefined }, questions });
  const receipt = await port.ask("which file?");
  assert.equal(receipt.asked, true);
  assert.equal(receipt.interrupt, true);
  assert.equal(receipt.answer, undefined);
  assert.equal(questions[0].dismissed, true);
});

test("ask without a UI is still a question, never a refusal", async () => {
  const questions = [];
  const port = askPort({ ui: null, questions });
  const receipt = await port.ask("which file?");
  // The park and the interrupt carry the question; no dialog is required.
  assert.equal(receipt.ok, true);
  assert.equal(receipt.asked, true);
  assert.equal(receipt.interrupt, true);
  assert.equal(receipt.question, "which file?");
  assert.equal(receipt.reason, undefined);
});

test("a Plan's this.ask parks with interrupt:true, aborts once, and unpark resumes the same body", async () => {
  // §7.3 / §3.3 example 7: `this.ask` is `park + interrupt`. The program's tool
  // call answers the park receipt with `interrupt: true`, the live turn's own
  // `ctx.abort` is called exactly once, and a later program of the SAME session
  // resumes the suspended body with `this.unpark(name, answer)`. The resumed
  // expression receives the answer; nothing is re-run, no new tenure opens, and
  // no second abort is issued.
  await withRoot(async () => {
    const aborts = [];
    const sessionId = `sess-plan-ask-${Date.now().toString(36)}`;
    const ctx = {
      agent: { kind: "main", id: "plan-agent", name: "plan", depth: 0 },
      sessionManager: { getSessionId: () => sessionId, getEntries: () => [] },
      abort: async (options) => {
        aborts.push(options);
      },
    };
    const host = new HostAdapter({
      pi: { registerTool: () => {}, getActiveTools: () => [], setActiveTools: async () => {} },
      ctx,
    });
    const plan = host.session;
    const tool = host.tools().find((candidate) => candidate.name === "plan");
    assert.notEqual(tool, undefined);
    const first = await tool.execute(
      "call-ask",
      {
        source:
          "class Js extends JsProgram { async run() { const answer = await this.ask(\"which file?\"); return { answer }; } }",
        scope: { kind: "resetScope", newScopeGoal: "find the file", newScopeRetentionBudget: 5 },
      },
      undefined,
      undefined,
      ctx,
    );
    const parked = receiptOf(first);
    assert.equal(parked.reason, "ask", JSON.stringify(parked));
    assert.deepEqual(parked.payload, { question: "which file?" });
    assert.equal(parked.interrupt, true, "the model must read the control fact on the park receipt");
    assert.equal(aborts.length, 1, "the live turn is interrupted exactly once");
    assert.equal(plan.state.askedInS1.question, "which file?");
    assert.deepEqual(plan.state.parkedNames(), ["call-ask"], "the parked handle is the Pair Hint's idle list");
    const tenure = plan.state.ledger.tenure;
    const second = await tool.execute(
      "call-answer",
      {
        source:
          "class Js extends JsProgram { async run() { return { resumed: this.unpark(\"call-ask\", \"src/a.mjs\") }; } }",
        scope: { kind: "keepLast" },
      },
      undefined,
      undefined,
      ctx,
    );
    const answered = receiptOf(second);
    assert.equal(answered.error, null, JSON.stringify(answered));
    // Core's unpark receipt names the resumed program and the value its parked
    // expression received; it has no `ok` field of its own.
    assert.equal(answered.value.resumed.state, "running", JSON.stringify(answered.value.resumed));
    assert.equal(answered.value.resumed.value, "src/a.mjs", "the resumed expression receives the operator's answer");
    assert.equal(plan.state.ledger.tenure, tenure, "an ask is not a relay");
    assert.equal(aborts.length, 1, "the resume does not interrupt a second turn");
    // The resumed body finished, so its handle leaves the idle list.
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(plan.state.parkedNames(), []);
    // The turn that asked consumed the marker when it ended; a later stop earns
    // its own exit instead of reading as another S1 ask.
    plan.turnExit();
    assert.equal(plan.state.askedInS1, null);
  });
});

test("the devops port returns the whole investigation report from one dedicated lane", async () => {
  const worker = fakeSession({ agentId: "plan-devops" });
  const api = fakeApi([worker]);
  const lanes = trackedLaneTable(new LaneTable({ api }));
  const port = devopsPort({ lanes });
  setTimeout(() => {
    worker.emit({ type: "message_end", message: { role: "assistant", content: "facts A\nfacts B" } });
    worker.emit({ type: "agent_end", isTerminal: true });
  }, 0);
  const receipt = await port.investigate("find the retry policy");
  assert.equal(receipt.ok, true);
  assert.equal(receipt.report, "facts A\nfacts B");
  assert.equal(receipt.lane, "plan-devops");
  // Root §1.1 item 6: the dedicated DevOps lane runs on the task model.
  assert.equal(api.created[0].agentName, "devops");
  assert.equal(api.created[0].modelPattern, "@task");
});

test("the devops port reuses the same lane instead of starting a new one", async () => {
  const worker = fakeSession({ agentId: "plan-devops" });
  const api = fakeApi([worker]);
  const lanes = trackedLaneTable(new LaneTable({ api }));
  const port = devopsPort({ lanes });
  setTimeout(() => {
    worker.emit({ type: "message_end", message: { role: "assistant", content: "first report" } });
    worker.emit({ type: "agent_end", isTerminal: true });
  }, 0);
  assert.equal((await port.investigate("first question")).report, "first report");
  // The lane is settled now; a second investigation starts its NEXT turn on the
  // same session, never a new `plan-devops-<timestamp>` lane.
  const second = port.investigate("second question");
  setTimeout(() => {
    worker.emit({ type: "message_end", message: { role: "assistant", content: "second report" } });
    worker.emit({ type: "agent_end", isTerminal: true });
  }, 0);
  const receipt = await second;
  assert.equal(receipt.ok, true);
  assert.equal(receipt.report, "second report");
  assert.equal(api.created.length, 1, "one lane for every investigation");
  assert.deepEqual(lanes.names(), ["plan-devops"]);
  const prompts = worker.calls.filter((call) => call[0] === "prompt").map((call) => call[1]);
  assert.deepEqual(prompts, ["first question", "second question"]);
});

test("the devops port refuses a second investigation while one is in flight", async () => {
  const worker = fakeSession({ agentId: "plan-devops" });
  const lanes = trackedLaneTable(new LaneTable({ api: fakeApi([worker]) }));
  const port = devopsPort({ lanes });
  const first = port.investigate("first");
  const refused = await port.investigate("second");
  assert.equal(refused.ok, false);
  assert.equal(refused.reason, "DEVOPS_IN_FLIGHT");
  setTimeout(() => worker.emit({ type: "agent_end", isTerminal: true }), 0);
  assert.equal((await first).ok, true);
});

test("Plan's devops is a bare primitive; this.devops does not exist", async () => {
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "plan-agent", name: "plan", depth: 0 },
      sessionManager: { getSessionId: () => "sess-plan-primitive", getEntries: () => [] },
    };
    const session = new testSession({ pi: { registerTool: () => {} }, api: null, ctx, role: ROLE.PLAN });
    const original = session.ports.bind(session);
    session.ports = () => ({
      ...original(),
      devops: { investigate: async (verboseIntent) => ({ ok: true, report: `report:${verboseIntent}` }) },
    });
    const built = session.buildSurface({
      toolCallId: "call-devops",
      surfaceName: "plan",
      source:
        "class Js extends JsProgram { async run() { const r = await devops('check the tree'); return { report: r.report, own: typeof this.devops }; } }",
      intent: "investigate",
    });
    const run = startProgram({ source: built.surface.source, surface: built.surface, program: built.program, parks: built.parks });
    const outcome = await run.outcome;
    assert.equal(outcome.status, "completed", JSON.stringify(outcome));
    assert.deepEqual(outcome.value, { report: "report:check the tree", own: "undefined" });
    // The primitive is Plan's alone: another role's scope has no `devops`.
    const members = built.surface.surface();
    const guarded = guardedMembers(members, built.program, built.parks);
    // Compose publishes `devops` as a primitive (not a member); a pre-cutover
    // core still lists it among the members. Both routes are the SAME handler,
    // which is why `startProgram` can resolve either.
    const devopsHandler = guarded.devops ?? built.surface.handler("devops", built.surface.core());
    assert.equal(typeof devopsHandler, "function");
    assert.equal(typeof primitiveScope({ devops: devopsHandler }, { role: "plan" }).devops, "function");
    assert.equal(primitiveScope({ devops: devopsHandler }, { role: "manager" }).devops, undefined);
    assert.equal(bindMembers({}, guarded).devops, undefined, "never bound as a member");
  });
});

test("worker lanes run on the task model; a Manager lane keeps the main model", async () => {
  const api = fakeApi([fakeSession({ agentId: "c" }), fakeSession({ agentId: "d" }), fakeSession({ agentId: "m" })]);
  const lanes = trackedLaneTable(new LaneTable({ api }));
  const coder = await lanes.start({ name: "c", role: "coder", verboseIntent: "role: coder — go" });
  const devops = await lanes.start({ name: "d", role: "devops", verboseIntent: "role: devops — go" });
  const manager = await lanes.start({ name: "m", role: ROLE.MANAGER, verboseIntent: "run" });
  assert.equal(coder.ok, true);
  assert.equal(devops.ok, true);
  assert.equal(manager.ok, true);
  assert.equal(api.created[0].modelPattern, "@task");
  assert.equal(api.created[1].modelPattern, "@task");
  assert.equal(api.created[2].modelPattern, undefined, "the Manager keeps the session's main model");
  assert.equal(coder.modelPattern, "@task");
  assert.equal(coder.modelSource, "task-role");
});

test("cancelAll aborts the lane's own session, refuses new work, and reports the cancellation honestly", async () => {
  const worker = fakeSession({ agentId: "w1" });
  const api = fakeApi([worker]);
  const lanes = trackedLaneTable(new LaneTable({ api }));
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: (role) => role });
  assert.equal((await port.fork("w1", "role: coder — go")).started, true);
  const receipt = await lanes.cancelAll("the operator interrupted this session");
  assert.equal(receipt.ok, true);
  assert.deepEqual(receipt.lanes, ["w1"]);
  assert.deepEqual(worker.calls.filter((call) => call[0] === "abort"), [["abort"]], "the child session is aborted directly");
  assert.notEqual(lanes.cancelled, null);
  // A later fork/steer refuses by name.
  assert.equal((await port.fork("w2", "role: coder — more")).reason, "SESSION_CANCELLED");
  assert.equal(await lanes.steer("w1", "more"), false);
  // The aborted turn is a claimable result, and `join` names the cancellation
  // only after handing over everything the worker really produced.
  const ended = await port.join();
  assert.equal(ended.name, "w1");
  assert.equal(ended.error.name, "CANCELLED");
  const after = await port.join();
  assert.equal(after.ok, false);
  assert.equal(after.reason, "SESSION_CANCELLED");
  // Idempotent: a second cancellation does not abort the session again.
  await lanes.cancelAll("again");
  assert.equal(worker.calls.filter((call) => call[0] === "abort").length, 1);
});

test("an unclaimed result survives a cancellation", async () => {
  const worker = fakeSession({ agentId: "w1" });
  const lanes = trackedLaneTable(new LaneTable({ api: fakeApi([worker]) }));
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: (role) => role });
  await port.fork("w1", "role: coder — go");
  worker.emit({ type: "message_end", message: { role: "assistant", content: "first answer" } });
  worker.emit({ type: "agent_end", isTerminal: true });
  await new Promise((resolve) => setImmediate(resolve));
  await lanes.cancelAll("stop");
  const first = await port.join();
  assert.equal(first.text, "first answer", "the turn that already ended is still readable");
  assert.equal((await port.join()).reason, "SESSION_CANCELLED");
});

test("an S1 ask, a replaced present and a worker are not operator interrupts", async () => {
  await withRoot(async () => {
    const plan = new testSession({
      pi: null,
      ctx: { agent: { kind: "main", name: "plan", depth: 0 }, sessionManager: { getSessionId: () => "sess-int-plan" } },
      role: ROLE.PLAN,
    });
    const message = { stopReason: "aborted", errorMessage: "Interrupted by user" };
    assert.deepEqual(plan.operatorInterrupt(message), { operator: true });
    plan.state.markAskedInS1("what now?");
    assert.deepEqual(plan.operatorInterrupt(message), { operator: false, reason: "asked-in-s1" });
    plan.state.clearAskedInS1();
    plan.control.replace({ groupId: "g" });
    assert.deepEqual(plan.operatorInterrupt(message), { operator: false, reason: "present-replaced" });
    const worker = new testSession({
      pi: null,
      ctx: { agent: { kind: "sub", name: "coder", depth: 1 }, sessionManager: { getSessionId: () => "sess-int-worker" } },
      role: ROLE.CODER,
    });
    assert.deepEqual(worker.operatorInterrupt(message), { operator: false, reason: "not-user-facing" });
    // A natural stop is not an interrupt either.
    assert.deepEqual(plan.operatorInterrupt({ stopReason: "endTurn" }), { operator: false, reason: "not-operator" });
  });
});

// ---------------------------------------------------------------------------
// Session: manager start, review, scope, pair hint
// ---------------------------------------------------------------------------

// A session owns its root for its whole LIFE, not just while a helper is
// running. `withRoot` restores `HERDCHESTRA_PROJECT_ROOT` as soon as its
// callback returns, so a session built inside it and used afterwards wrote to
// whatever the next test had set — or to the real working directory, which left
// `.Herdchestra/` files in the repo. The root is therefore passed in as state,
// not through the process environment: unique per call, no shared global, and
// safe when tests run concurrently.
const sessionFor = async ({ role = ROLE.MANAGER, dispatch = null, ui = null, api = null } = {}) => {
  const root = tmpRoot();
  const sessionId = `sess-${role}-${Date.now().toString(36)}`;
  const ctx = {
    agent: { kind: role === ROLE.MANAGER ? "main" : "sub", id: `id-${role}`, name: role, depth: 0 },
    sessionManager: { getSessionId: () => sessionId },
    getAsyncJobSnapshot: () => ({ running: [], recent: [] }),
    ui: ui ?? undefined,
  };
  const state = new SessionState({ sessionId, role, root });
  // Every temp root this helper makes is removed when the process exits. The
  // Scope book and the ledger open lazily and are flushed by later async work
  // (a turn close, a settle), so per-test cleanup would race them; the repo's
  // `.Herdchestra/` staying clean is what this file guards, and that is covered
  // by the roots themselves, never by the working directory.
  TEMP_ROOTS.push(root);
  return new testSession({ pi: api === null ? null : { registerTool: () => {} }, api, ctx, role, dispatch, state });
};

// The temp roots `sessionFor` created, removed once at exit.
const TEMP_ROOTS = [];
process.once("exit", () => {
  for (const root of TEMP_ROOTS) {
    try {
      fs.rmSync(root, { recursive: true, force: true });
    } catch {
      // A root already gone is fine.
    }
  }
});

test("a test session writes under its own temp root, never the working directory", async () => {
  // The repo's `.Herdchestra/` must stay clean after a run. A session that
  // outlives the helper that built it used to resolve `projectRoot()` at write
  // time, so a review landed wherever the working directory happened to be.
  const session = await sessionFor({ role: ROLE.MANAGER });
  await session.review([{ key: "a", value: "x" }]);
  const file = session.state.file;
  assert.equal(file.startsWith(session.state.root), true, file);
  assert.notEqual(session.state.root, process.cwd(), "a test root must never be the repo");
  assert.equal(file.includes(path.join(process.cwd(), ".Herdchestra")), false, file);
});

test("two sessions with different roots never share state, whatever the environment says", async () => {
  // The root is a fact about the session, not about the process. When a session
  // derived it from `projectRoot()` — one process-wide variable — a session
  // built while another test had that variable set wrote into the other test's
  // tree, and the suite passed or failed depending on scheduling. Two sessions
  // alive at once must therefore keep two separate trees.
  const first = await sessionFor({ role: ROLE.PLAN });
  const second = await sessionFor({ role: ROLE.PLAN });
  assert.notEqual(first.state.root, second.state.root, "each session owns its own root");
  // Neither session's draft path may point into the other's tree.
  const firstDraft = first.ports().draft.path();
  const secondDraft = second.ports().draft.path();
  assert.equal(firstDraft.startsWith(first.state.root), true, firstDraft);
  assert.equal(secondDraft.startsWith(second.state.root), true, secondDraft);
  assert.equal(firstDraft.startsWith(second.state.root), false, "roots must not cross");
  fs.writeFileSync(firstDraft, "first");
  assert.equal(second.ports().draft.read(), "", "the other session sees no draft");
});

test("a Plan handoff starts an independent Manager session through the host's own factory", async () => {
  // The host gives an extension no tool dispatcher (`loader.ts:454` passes only
  // `api`), so the `task` route this used before could never run for real. The
  // Manager is started the way `fork` starts a worker: through the SDK factory.
  const managerSession = fakeSession({ agentId: "manager-1" });
  const api = fakeApi([managerSession]);
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "plan-agent", name: "plan", depth: 0 },
      sessionManager: { getSessionId: () => "sess-plan-handoff", getEntries: () => [] },
    };
    // This session is the Plan (§2.1), so it owns the Plan ledger and is the
    // side that hands off.
    const plan = new testSession({ pi: { registerTool: () => {} }, api, ctx, role: ROLE.PLAN });

    // The answer arrives when the Manager's turn ends, not when it starts.
    const started = plan.startManager("the draft");
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(api.created.length, 1, "one Manager session, created by the host factory");
    const created = api.created[0];
    // The draft is the Manager's only starting material: its first verboseIntent.
    const prompts = managerSession.calls.filter(([kind]) => kind === "prompt");
    assert.equal(prompts.length, 1);
    assert.equal(prompts[0][1], "the draft");
    // The face follows the ROLE, not "a lane created this session": a Manager
    // created through the lane must be offered `js-manager`, never `js-worker`.
    assert.deepEqual(created.toolNames, [...MANAGER_TOOLS]);
    assert.equal(created.toolNames.includes("js-worker"), false);
    managerSession.endTurn("the manager's answer");

    const done = await started;
    assert.equal(done.ok, true);
    // The answer is the Manager's own text, never a summary.
    assert.equal(done.report, "the manager's answer");
    assert.equal(done.session, created.agentId);
  });
});

test("a Plan's review carries the saved draft through the host port", async () => {
  // §2.2: `review([])` at 待执行 hands over the SAVED draft. Compose reads it
  // through the `draft` port and passes it as `review`'s second argument; a host
  // that drops that argument turns a written draft into `DRAFT_MISSING`, and the
  // review itself carries no text to stand in for it.
  const managerSession = fakeSession({ agentId: "manager-draft" });
  // A Plan session that can really relay: the journal session manager publishes
  // the one durable record, and the successor's transcript switch is real. A
  // session without it cannot perform a relay at all, and Plan's milestones are
  // now only reachable through one (根 §2.4 第 3–5 步).
  const { session, manager, calls: relayCalls } = journalSession();
  const root = tmpRoot();
  TEMP_ROOTS.push(root);
  const state = new SessionState({ sessionId: "sess-relay-journal", role: ROLE.PLAN, root });
  const plan = new testSession({
    pi: { registerTool: () => {} },
    api: fakeApi([managerSession]),
    role: ROLE.PLAN,
    ctx: session,
    state,
  });
  // The draft on disk is the hand-off's material; nothing else is given.
  const draftPath = plan.ports().draft.path();
  fs.mkdirSync(path.dirname(draftPath), { recursive: true });
  fs.writeFileSync(draftPath, "the saved draft");
  {
    // Walk Plan to 待执行 the way BOTH sides walk (根 §2.4): the S1 question
    // moves nothing, each later empty review RELAYS (retire + barrier +
    // successor at the SAME milestone), and that successor's own first empty
    // review promotes the milestone.
    const ledger = plan.state.ledger;
    assert.equal(ledger.kind, "plan");
    assert.equal((await plan.review([])).kind, "askUser");
    for (let step = 0; step < 3; step += 1) {
      const relayed = await plan.review([]);
      assert.equal(relayed.kind, "relay", JSON.stringify(relayed));
      assert.equal(relayed.ok, true, JSON.stringify(relayed));
      assert.equal(ledger.successor, true, "a relay opens a successor at the same milestone");
      assert.equal(ledger.tenure, step + 1);
      const promoted = await plan.review([]);
      assert.equal(promoted.kind, "confirm", JSON.stringify(promoted));
    }
    assert.equal(ledger.milestone, "待执行");
    // The promotion consumed the successor's first review: the tenure judging
    // at 待执行 is the one that verified the previous one.
    assert.equal(ledger.successor, false);
    assert.equal(ledger.tenure, 3);
    // Every step was ONE atomic hand-over (根 §2.4 第 3 步): the record is
    // published first, and the transcript switch follows it — one switch per
    // relay, and never a switch before a publish.
    const kinds = relayCalls.map(([kind]) => kind);
    assert.equal(kinds.filter((kind) => kind === "newSession").length, 3, "one transcript switch per relay");
    assert.equal(
      kinds.filter((kind) => kind === "appendEntriesAtomically").length >= 3,
      true,
      JSON.stringify(kinds),
    );
    assert.ok(
      kinds.indexOf("appendEntriesAtomically") < kinds.indexOf("newSession"),
      "the record is durable before the incumbent gives up its transcript",
    );
    // The hand-off waits for the Manager's turn; the test ends it from beside
    // the pending review, so the wait is the hand-off's own, not the test's.
    const pending = plan.review([], plan.ports().draft.read());
    for (let attempt = 0; attempt < 50; attempt += 1) {
      if (managerSession.calls.some(([kind]) => kind === "prompt")) break;
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    const prompts = managerSession.calls.filter(([kind]) => kind === "prompt");
    assert.equal(prompts[0][1], "the saved draft");
    managerSession.endTurn("ack");
    const receipt = await pending;
    assert.equal(receipt.ok, true, JSON.stringify(receipt));
    assert.equal(receipt.kind, "handoff");
  }
});

test("a Plan's draft keeps its name across a relay", async () => {
  // §2.4 第 3 步: a relay changes the transcript, not the participant. The draft
  // is the participant's one artifact, so it must be named by the identity that
  // survives the relay. Deriving it from the LIVE session id moved the path when
  // the successor was adopted, so the written draft was never found again and
  // every hand-off after a relay was refused DRAFT_MISSING.
  const { session } = journalSession();
  const root = tmpRoot();
  TEMP_ROOTS.push(root);
  const state = new SessionState({ sessionId: "sess-relay-journal", role: ROLE.PLAN, root });
  const plan = new testSession({ pi: { registerTool: () => {} }, api: fakeApi([]), role: ROLE.PLAN, ctx: session, state });
  const before = plan.ports().draft.path();
  assert.equal(state.lineageId, "sess-relay-journal");
  // Walk through one real relay, which adopts the successor's session id.
  assert.equal((await plan.review([])).kind, "askUser");
  assert.equal((await plan.review([])).kind, "relay");
  assert.notEqual(state.sessionId, "sess-relay-journal", "the successor's id was adopted");
  assert.equal(state.lineageId, "sess-relay-journal", "the lineage is the participant");
  assert.equal(plan.ports().draft.path(), before, "the draft keeps one name across the relay");
});

test("a Plan handoff names the missing factory instead of inventing a Manager", async () => {
  // No SDK, no session. The receipt says so rather than reporting a Manager that
  // never ran.
  const session = await sessionFor({ role: ROLE.MANAGER });
  const started = await session.startManager("the draft");
  assert.equal(started.ok, false);
  assert.equal(started.reason, "NO_SESSION_FACTORY");
  assert.equal(started.report, undefined);
});

test("a fresh Plan can write its advertised draft at once, with no directory to create", async () => {
  // §6: the draft path is the ONE file a Plan writes. A session that advertises
  // a path whose directory does not exist yet hands the model a `write` that
  // fails with `ENOENT` — a cause the model cannot see or repair, and the same
  // missing directory would later make the hand-off read an empty draft.
  const session = await sessionFor({ role: ROLE.PLAN });
  const draftPath = session.ports().draft.path();
  // Nothing here creates the parent: a fresh session must have done it.
  assert.equal(fs.existsSync(path.dirname(draftPath)), true, `missing ${path.dirname(draftPath)}`);
  fs.writeFileSync(draftPath, "the first draft");
  assert.equal(session.ports().draft.read(), "the first draft");
});

test("the Plan's prompt names the draft path the port reads", async () => {
  // "the path the system gives you" is only true if something gives it. The
  // path in the prompt and the path the hand-off reads must be one path, or a
  // Plan writes a file the hand-off never finds.
  const session = await sessionFor({ role: ROLE.PLAN });
  const draftPath = session.ports().draft.path();
  const started = await createBeforeAgentStartHandler({
    role: ROLE.PLAN,
    draft: () => ({ path: draftPath }),
  })();
  assert.equal(started.systemPrompt.length, 1);
  assert.ok(started.systemPrompt[0].includes(draftPath), started.systemPrompt[0]);
});

test("a draft directory that cannot be prepared is stated, and never read as an empty draft", async () => {
  // A file where the directory belongs: every `write` would fail for a cause
  // the model cannot see. The session must SAY so, and the port must not answer
  // `""` — an empty string is "the Plan wrote nothing", which would refuse the
  // hand-off for the wrong reason.
  const root = tmpRoot();
  TEMP_ROOTS.push(root);
  fs.mkdirSync(root, { recursive: true });
  // A file where the workspace-root `plans/` directory belongs (root §1.1
  // item 3: the draft is visible at the workspace root, never under
  // `.Herdchestra`).
  fs.writeFileSync(path.join(root, "plans"), "not a directory");
  const state = new SessionState({ sessionId: "sess-blocked", role: ROLE.PLAN, root });
  assert.notEqual(state.draftDirFailure, null);
  const session = new testSession({ pi: null, ctx: { agent: { kind: "main", name: "plan", depth: 0 } }, role: ROLE.PLAN, state });
  assert.equal(session.ports().draft.read(), null, "cannot tell is not an empty draft");
  const started = await createBeforeAgentStartHandler({
    role: ROLE.PLAN,
    draft: () => ({ path: session.ports().draft.path(), failure: state.draftDirFailure }),
  })();
  assert.match(started.systemPrompt[0], /could not be prepared/);
  assert.match(started.systemPrompt[0], new RegExp(state.draftDirFailure.code));
});

test("a hand-off waits for the Manager's turn, not for its whole work", async () => {
  // §6.1 starts an independent Manager session and leaves Plan holding the same
  // work. The hand-off's answer is the Manager's first result: the lane's end
  // event is one turn, so a Manager that keeps working continues in the same
  // session instead of holding the Plan's review open for the whole task.
  const managerSession = fakeSession({ agentId: "manager-turn" });
  const api = fakeApi([managerSession]);
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "plan-agent", name: "plan", depth: 0 },
      sessionManager: { getSessionId: () => "sess-plan-turn", getEntries: () => [] },
    };
    const plan = new testSession({ pi: { registerTool: () => {} }, api, ctx, role: ROLE.MANAGER });
    const started = plan.startManager("the draft");
    await new Promise((resolve) => setImmediate(resolve));
    // The first turn ends; the Manager's work does not.
    managerSession.endTurn("first result");
    const done = await started;
    assert.equal(done.ok, true);
    assert.equal(done.report, "first result");
    // The session is still in the Plan's roster: a later turn is reachable and
    // the hand-off did not dispose it. The lane ends with its first turn (its
    // end event is one turn, which is what `join` reads), so reachability is the
    // roster, not the ended promise.
    const lane = plan.lanes.live(done.session);
    assert.ok(lane !== null, "the Manager session stays live");
    assert.equal(lane.settled, true, "the first turn is over");
    assert.deepEqual(managerSession.calls.filter(([kind]) => kind === "dispose"), []);
  });
});

test("the first incumbent's question goes through the host dialog", async () => {
  const ui = { input: async () => "do the thing" };
  const session = await sessionFor({ role: ROLE.MANAGER, ui });
  assert.equal(await session.askOperator("我需要干什么？"), "do the thing");
});

// A Manager session shaped like a real one: a control surface (`newSession`,
// `isStreaming`, `abort`) and a transcript. The relay refuses on either shape,
// and this fixture pins that the refusal is the missing atomic record, not the
// session's ability to transition.
// The root is RETURNED, not just used: `withRoot` restores the environment when
// its callback ends, and the caller builds its `RoleSession` afterwards. A
// session that outlived its root wrote its ledger to whatever the working
// directory was — which left `.Herdchestra/` files in the repo.
const managerWithTranscript = async ({ streaming = false, extra = {} } = {}) =>
  await withRoot(async (root) => {
    const session = {
      ...fakeSession({ agentId: "Main" }),
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      isStreaming: streaming,
      sessionFile: "/tmp/incumbent.jsonl",
      // `SessionControl.reason()` reads `session.newSession`, so the incumbent
      // must really offer the transition, and the call must land in ITS call log
      // — a spread copy's closure writes into the copy's array.
      async newSession() {
        session.calls.push(["newSession"]);
        session.previousSessionFile = session.sessionFile;
        session.sessionFile = "Main-successor.jsonl";
        return true;
      },
      sessionManager: {
        getSessionId: () => `sess-relay-${Date.now().toString(36)}`,
        getEntries: () => [
          { type: "message", message: { role: "user", content: [{ type: "text", text: "build the thing" }] } },
        ],
      },
      ...extra,
    };
    return { session, root, api: fakeApi([fakeSession({ agentId: "successor-lane" })]) };
  });

// A Manager session whose session manager is a REAL journal: `getEntries` reads
// the array, `appendCustomEntry` appends to it, and `appendEntriesAtomically`
// stages the callback's entries and publishes them in one step — the shape OMP
// guarantees (`session-manager.ts` `#appendEntriesAtomicallyLocked`). `fail`
// makes the publish throw, which is the one way a commit can refuse.
const journalSession = ({ streaming = false, publish = null } = {}) => {
  const entries = [];
  const calls = [];
  // The batch the current publish is staging, exactly as
  // `#appendToSessionFile` diverts entries while `#atomicEntryBatch` is set.
  let batch = null;
  const manager = {
    entries,
    getSessionId: () => "sess-relay-journal",
    getEntries: () => [...entries],
    appendCustomEntry(customType, data) {
      const target = batch ?? entries;
      const id = `entry-${target.length + 1}`;
      target.push({ type: "custom", customType, data, id });
      return id;
    },
    async appendEntriesAtomically(append) {
      calls.push(["appendEntriesAtomically"]);
      if (batch !== null) throw new Error("Atomic persistence lock ownership was violated.");
      // OMP stages the batch, then publishes the whole journal in one rename; a
      // failed publish removes only the staged entries.
      // The staged batch is a SEPARATE list until the publish succeeds: OMP's
      // `#rollbackAtomicEntryBatch` removes the staged entries, so a reader of
      // the journal never sees them. A fake that appended straight into
      // `entries` would make a failed publish look like a committed record.
      batch = [];
      let result;
      try {
        result = append();
      } finally {
        const staged = batch;
        batch = null;
        const answer = typeof publish === "function" ? publish() : publish === "throw";
        // The host's three shapes, all measured on 18.14.2:
        //   true / "publish"  the batch is published;
        //   "throw"           the storage write itself fails; the batch is
        //                     rolled back (`#rollbackAtomicEntryBatch`);
        //   "rollback"        the publish was superseded AND the batch was rolled
        //                     back — nothing exists anywhere;
        //   "supersede"       the publish was superseded but the entry STAYS in
        //                     the journal, which the host carries into the next
        //                     publish (measured: after a superseded publish the
        //                     journal held the entry and the file did not).
        if (answer === "throw") {
          for (const entry of staged) {
            const at = entries.indexOf(entry);
            if (at >= 0) entries.splice(at, 1);
          }
          throw new Error("publish failed");
        }
        if (answer === "rollback") {
          for (const entry of staged) {
            const at = entries.indexOf(entry);
            if (at >= 0) entries.splice(at, 1);
          }
          throw new Error("Atomic session batch was superseded before commit.");
        }
        entries.push(...staged);
        // Only the explicit word names the superseded publish; a plain truthy
        // answer (the default fixtures) is a normal publish.
        if (answer === "supersede") throw new Error("Atomic session batch was superseded before commit.");
      }
      return result;
    },
  };
  const session = {
    ...fakeSession({ agentId: "Main" }),
    agent: { kind: "main", id: "Main", name: "main", depth: 0 },
    isStreaming: streaming,
    sessionFile: "/tmp/incumbent.jsonl",
    calls,
    sessionManager: manager,
    async newSession() {
      calls.push(["newSession"]);
      session.previousSessionFile = session.sessionFile;
      session.sessionFile = "/tmp/successor.jsonl";
      // The successor's transcript starts empty, exactly as the host resets it.
      entries.length = 0;
      return true;
    },
  };
  return { session, manager, calls };
};

// A LANE-shaped ctx: the session object itself, which is what `bindSession`
// hands a worker and what a test may hand a surface directly.
//
// This is NOT the `session_start` ctx an extension receives. That shape has no
// `newSession`/`sessionFile` of its own — the switch lives on
// `ctx.sessionManager` — and modelling only this one is what let a real relay be
// refused `RELAY_BUSY`/`NO_NEW_SESSION` while 120 offline tests stayed green.
// The host shape is covered by `realCtxHost` below; keep both.
const relayHost = async ({ streaming = false, publish = null } = {}) => {
  const root = tmpRoot();
  const { session, manager, calls } = journalSession({ streaming, publish });
  const state = new SessionState({ sessionId: "sess-relay-journal", role: ROLE.MANAGER, root });
  const host = new testSession({ pi: { registerTool: () => {} }, api: fakeApi([]), role: ROLE.MANAGER, ctx: session, state });
  return { host, state, session, manager, calls, root };
};

const relayRecords = (entries) => entries.filter((entry) => entry.customType === "herdchestra.relay");

test("a relay commits retirement, the barrier and the successor as ONE durable record", async () => {
  // Root §2.4 第 3 步: the three parts are one record, and a crash cannot leave
  // half a tenure. The record is a single custom entry published by
  // `appendEntriesAtomically`; all three facts are fields of that one entry, so
  // no reader can observe one without the others.
  const { host, state, session, manager, calls } = await relayHost();
  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);

  assert.equal(relay.ok, true, JSON.stringify(relay));
  assert.equal(relay.kind, "relay");
  assert.equal(relay.handedOver.ok, true);
  assert.equal(relay.handedOver.retired, true);
  assert.equal(relay.handedOver.barrier, true);
  assert.ok(relay.handedOver.successor !== null);
  // One publish, one record — not three writes.
  assert.equal(calls.filter(([kind]) => kind === "appendEntriesAtomically").length >= 1, true);
  // The incumbent gave up its transcript only AFTER the record was durable.
  const order = calls.map(([kind]) => kind);
  assert.ok(order.indexOf("appendEntriesAtomically") < order.indexOf("newSession"), JSON.stringify(order));
  // The ledger advanced to the successor's tenure at the SAME milestone.
  assert.equal(state.ledger.tenure, 1);
  assert.equal(state.ledger.milestone, "execute");
  assert.equal(state.ledger.obligations.length, 0);
  // The record carries all three facts, and a fence the successor is filtered by.
  assert.equal(relayRecords(manager.entries).length, 1, "the successor transcript carries the record");
  assert.equal(state.fence.successor, 1);
  assert.match(state.fence.notice, /successor in tenure 1/);
  const saved = JSON.parse(fs.readFileSync(state.file, "utf8"));
  assert.equal(saved.tenure, 1);
  assert.equal(saved.fence.successor, 1);
});

// The REAL `session_start` ctx, as omp 18.14.1/18.14.2 hands it to an
// extension: `ui`, `mode`, `cwd`, `sessionManager`, `agent`, `abort`,
// `isIdle()`, … — and NOT the session itself. Measured live: `typeof
// ctx.newSession`, `ctx.sessionFile`, `ctx.isStreaming` are all `undefined`;
// the switch lives on `ctx.sessionManager.newSession`, the transcript path on
// `ctx.sessionManager.getSessionFile()`.
//
// The earlier relay fixture passed an AgentSession-shaped object as `ctx`, so
// the offline suite passed on a shape the host never produces and every real
// relay was refused with RELAY_BUSY/NO_NEW_SESSION.
const realCtxShape = ({ publish = null, streaming = false, transcript = [] } = {}) => {
  // A real session already has a transcript; `fenceThrough` counts its
  // `message` entries, so a fixture with none cannot show the count working.
  const entries = [...transcript];
  const calls = [];
  let batch = null;
  let sessionFile = "/tmp/incumbent-real.jsonl";
  // Each reset opens a DISTINCT transcript. Numbering by the live entry count
  // named the successor after the retired file once the transcript was empty,
  // which made a fixture relay retire the very file it then wrote to.
  let resets = 0;
  // The id the LIVE transcript carries. A real relay mints a new one with the
  // reset, and a reader on the old file reports the old one again — which is
  // what makes the transcript, not the id, the fact that separates them.
  let sessionId = "sess-real-ctx";
  // The successor's own header, as `ensureOnDisk` writes it before
  // `newSession` returns. `parentSession` is written verbatim from the options.
  let successorHeader = null;
  const manager = {
    getSessionId: () => sessionId,
    getSessionFile: () => sessionFile,
    getEntries: () => [...entries],
    appendCustomEntry(customType, data) {
      const target = batch ?? entries;
      // The host mints an entry id and stores it ON the entry
      // (`SessionEntry.id`); the adapter reads the journal back by that id to
      // prove its publish landed, so the fixture must model it.
      const id = `entry-${entries.length + target.length}`;
      target.push({ type: "custom", customType, data, id });
      return id;
    },
    async appendEntriesAtomically(append) {
      calls.push(["appendEntriesAtomically"]);
      batch = [];
      let result;
      try {
        result = append();
      } finally {
        const staged = batch;
        batch = null;
        // `"rollback"` is the shape measured on 18.14.2: the publish is
        // superseded, `#rollbackAtomicEntryBatch` removes the staged entries,
        // and the call throws. A fixture that only threw without removing them
        // would make a refused publish look like a durable one.
        if (publish === "rollback") {
          for (const entry of staged) {
            const at = entries.indexOf(entry);
            if (at >= 0) entries.splice(at, 1);
          }
          throw new Error("Atomic session batch was superseded before commit.");
        }
        if (typeof publish === "function" ? publish() : publish === "throw") throw new Error("publish failed");
        entries.push(...staged);
      }
      return result;
    },
    // `SessionManager.newSession` resets the transcript and returns the new
    // file; `ensureOnDisk` has already written the header by then.
    //
    // The options are modelled, not ignored: the host writes
    // `parentSession: options?.parentSession` VERBATIM into the successor's
    // header (session-manager.ts:2045). A fixture that dropped the argument is
    // why the live run shipped a successor with `parentSession: null` while this
    // suite stayed green — the lineage was never observable here.
    async newSession(options = {}) {
      calls.push(["newSession", options]);
      const previous = sessionFile;
      sessionId = "sess-real-ctx-successor";
      successorHeader = {
        type: "session",
        id: sessionId,
        parentSession: options?.parentSession,
      };
      resets += 1;
      sessionFile = `/tmp/successor-${resets}.jsonl`;
      entries.length = 0;
      return { previous, file: sessionFile };
    },
  };
  const ctx = {
    ui: {},
    mode: "print",
    cwd: "/tmp",
    sessionManager: manager,
    agent: { kind: "main", id: "Main", name: "main", depth: 0 },
    abort: () => {},
    isIdle: () => !streaming,
    hasUI: false,
    model: undefined,
    models: {},
    modelRegistry: { getApiKey: async () => "key" },
    getContextUsage: () => undefined,
    getSystemPrompt: () => [],
    isProjectTrusted: () => true,
    hasPendingMessages: () => false,
    shutdown: () => {},
    compact: async () => {},
    getAsyncJobSnapshot: () => null,
  };
  return { ctx, manager, calls, entries: () => entries, header: () => successorHeader };
};

const realCtxHost = async (options = {}) => {
  const root = tmpRoot();
  const built = realCtxShape(options);
  const state = new SessionState({ sessionId: "sess-real-ctx", role: ROLE.MANAGER, root });
  const host = new testSession({ pi: { registerTool: () => {} }, api: null, role: ROLE.MANAGER, ctx: built.ctx, state });
  return { host, state, ...built, root };
};

test("a relay works on the REAL session_start ctx shape (sessionManager owns the switch)", async () => {
  // Regression for the live failure: every real relay was refused
  // RELAY_BUSY/NO_NEW_SESSION because the control surface asked the ctx for
  // `newSession`, which only ever lives on `ctx.sessionManager`.
  const { host, state, calls, manager, entries } = await realCtxHost();
  assert.equal(host.control.reason(), null, "a settled real ctx is not busy");

  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);

  assert.equal(relay.ok, true, JSON.stringify(relay));
  assert.equal(relay.kind, "relay");
  assert.equal(relay.handedOver.ok, true);
  assert.equal(relay.handedOver.retired, true);
  assert.equal(relay.handedOver.barrier, true);
  assert.equal(relay.handedOver.transcriptReplaced, true);
  // The switch ran on the manager, in that order, after the commit.
  const order = calls.map(([kind]) => kind);
  assert.ok(order.indexOf("appendEntriesAtomically") < order.indexOf("newSession"), JSON.stringify(order));
  assert.equal(state.ledger.tenure, 1);
  assert.equal(state.ledger.milestone, "execute");
  // The record is in the successor transcript and carries the real fence.
  const records = entries().filter((entry) => entry.customType === "herdchestra.relay");
  assert.equal(records.length, 1);
  assert.equal(records[0].data.tenure, 1);
  assert.equal(state.fence.successor, 1);
  assert.ok(manager !== null);
});

test("the successor transcript's header names the transcript it replaced", async () => {
  // Measured live (omp 18.14.2): the relay committed, the successor transcript
  // existed and carried the record — and its header said `parentSession: null`,
  // because `newConversation()` called `newSession({})` with no options. The
  // host writes `parentSession: options?.parentSession` VERBATIM
  // (session-manager.ts:2045), and its own `resume` path puts the replaced
  // session FILE there — so a successor that names nothing is indistinguishable
  // from an unrelated new session, and §10.4 check 3 fails.
  const { host, built } = await (async () => {
    const built = realCtxShape();
    const root = tmpRoot();
    const state = new SessionState({ sessionId: "sess-real-ctx", role: ROLE.MANAGER, root });
    const host = new testSession({
      pi: { registerTool: () => {} },
      api: null,
      role: ROLE.MANAGER,
      ctx: built.ctx,
      state,
    });
    return { host, built };
  })();
  const incumbent = built.manager.getSessionFile();

  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);
  assert.equal(relay.handedOver.ok, true, JSON.stringify(relay.handedOver));

  const header = built.header();
  assert.ok(header !== null, "the host wrote a successor header");
  assert.equal(header.parentSession, incumbent, "the header names the replaced transcript");
  assert.notEqual(header.parentSession, built.manager.getSessionId(), "an id is not a path");
  // The same fact is in the durable record, which is what a restart reads.
  assert.equal(relay.handedOver.record.previousSessionFile, incumbent);
});

test("a streaming real ctx is busy, and a ctx with no liveness is not assumed settled", async () => {
  // `ExtensionContext` has no `isStreaming` (measured), so liveness comes from
  // `isIdle()`. A check that only read session members would call a streaming
  // ctx settled and reset the transcript out from under a live turn.
  const { ctx } = realCtxShape({ streaming: true });
  const root = tmpRoot();
  const state = new SessionState({ sessionId: "sess-live", role: ROLE.MANAGER, root });
  const host = new testSession({ pi: { registerTool: () => {} }, api: null, role: ROLE.MANAGER, ctx, state });
  assert.equal(host.control.reason(), "SESSION_STREAMING");

  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);
  // Deferred, not refused: the busy state is the normal relay case, and the
  // intent is what a restart resumes.
  assert.equal(relay.ok, false);
  assert.equal(relay.rejection, "RELAY_DEFERRED");
  assert.equal(relay.handedOver.busy, "SESSION_STREAMING");
  // The record is committed in the turn; the transcript switch is not.
  assert.equal(relay.handedOver.recordPublished, true);
  assert.equal(state.ledger.tenure, 1);
  assert.equal(relay.handedOver.retired, false);

  // A ctx that can answer neither `isStreaming` nor `isIdle()` is NOT settled:
  // "cannot tell" must not be read as "idle".
  const blind = realCtxShape().ctx;
  delete blind.isIdle;
  const blindState = new SessionState({ sessionId: "sess-blind", role: ROLE.MANAGER, root: tmpRoot() });
  const blindHost = new testSession({ pi: { registerTool: () => {} }, api: null, role: ROLE.MANAGER, ctx: blind, state: blindState });
  assert.equal(blindHost.control.reason(), "NO_SESSION_LIVENESS");
});

test("a real ctx that cannot switch reports NO_SESSION_CONTROL, never RELAY_BUSY", async () => {
  // A session manager without `newSession` is a surface defect, not a busy
  // session: reporting RELAY_BUSY told the operator to wait for something that
  // could never succeed.
  const root = tmpRoot();
  const { ctx } = realCtxShape();
  delete ctx.sessionManager.newSession;
  const state = new SessionState({ sessionId: "sess-no-switch", role: ROLE.MANAGER, root });
  const host = new testSession({ pi: { registerTool: () => {} }, api: null, role: ROLE.MANAGER, ctx, state });
  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);
  assert.equal(relay.ok, false);
  assert.equal(relay.handedOver.reason, "NO_SESSION_CONTROL");
  assert.equal(relay.handedOver.busy, "NO_NEW_SESSION");
  assert.equal(state.ledger.tenure, 0);
});

test("the fence installed in-process is the fence the durable record carries", async () => {
  // The latent bug behind the live blocker: `SessionState.handOver` read
  // `done.record?.fenceThrough ?? 0`, and the port never echoed `record`, so
  // every installed fence counted 0 messages and hid nothing.
  //
  // After a real reset the boundary belongs to the SUCCESSOR's transcript,
  // which starts empty, so both the live fence and the durable one count 0 —
  // and they must agree, or a restart would change what the successor sees.
  const { host, state, manager } = await realCtxHost({
    transcript: [
      { type: "message", message: { role: "user", content: "build the thing" } },
      { type: "message", message: { role: "assistant", content: "working" } },
    ],
  });
  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);
  assert.equal(relay.ok, true, JSON.stringify(relay));
  const written = relay.handedOver.record;
  assert.equal(written.transcriptReplaced, true);
  assert.equal(written.fenceThrough, 0, "the successor transcript starts empty");
  assert.equal(state.fence.fenceThrough, written.fenceThrough);
  const saved = JSON.parse(fs.readFileSync(state.file, "utf8"));
  assert.equal(saved.fence.fenceThrough, written.fenceThrough);
  // The successor's own record, in its own transcript, carries the same count.
  const successorRecord = manager.getEntries().filter((entry) => entry.customType === "herdchestra.relay").at(-1);
  assert.equal(successorRecord.data.fenceThrough, 0);
  assert.equal(successorRecord.data.tenure, 1);
});

test("a fence written before the reset counts the incumbent's transcript", async () => {
  // The other half: when the switch does NOT replace the transcript, the
  // incumbent's file is still live, so the count taken before the attempt is
  // the real boundary and must be what both the live fence and the record use.
  const { host, state, manager } = await realCtxHost({
    transcript: [
      { type: "message", message: { role: "user", content: "build the thing" } },
      { type: "message", message: { role: "assistant", content: "working" } },
    ],
  });
  manager.newSession = async () => false;
  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);
  assert.equal(relay.ok, true, JSON.stringify(relay));
  assert.equal(relay.handedOver.record.transcriptReplaced, false);
  // `review` itself appends no transcript entry: the count is exactly the two
  // messages the fixture holds, which is what the barrier must treat as the
  // incumbent's conversation.
  assert.equal(relay.handedOver.record.fenceThrough, 2);
  assert.equal(state.fence.fenceThrough, 2);
});

test("a successor restored after a reset hides the incumbent's conversation entirely", async () => {
  // After a real reset the transcript is empty, so a restored fence keeps only
  // the notice and the newest operator message. This pins that the count is
  // carried verbatim rather than recomputed against the (now empty) transcript.
  const { host, state, manager } = await realCtxHost();
  await host.review([{ key: "a", value: "x" }]);
  await host.review([{ key: "a", value: null }]);
  // The restart reads the transcript the reset left, under the id that
  // transcript carries — the same facts the host reports on `--resume`.
  const fresh = new SessionState({ sessionId: manager.getSessionId(), role: ROLE.MANAGER, root: state.root });
  const record = fresh.relayRecordIn(manager);
  assert.ok(record !== null, "the successor transcript carries the record");
  fresh.adoptRelayRecord(record);
  assert.equal(fresh.fence.fenceThrough, state.fence.fenceThrough);
  // The successor's own file starts empty (`fenceThrough: 0`), so the barrier
  // keeps the notice plus the NEWEST operator message and nothing before it:
  // the successor's assistant turn from earlier in its own tenure is pruned.
  // That is the §2.4 第 4 步 rule — the successor sees the operator's input, not
  // a conversation that predates the judgement it is making.
  assert.equal(fresh.fence.fenceThrough, 0);
  const context = createContextHandler({ barrier: () => fresh.fence });
  const out = await context({
    messages: [
      { role: "assistant", content: [{ type: "text", text: "successor thinking" }] },
      { role: "user", content: "the operator's new instruction" },
    ],
  });
  assert.ok(out !== undefined);
  assert.equal(out.messages.length, 2);
  // The notice is one text part, and it is the first thing the successor reads.
  assert.deepEqual(out.messages[0].content.map((part) => part.text).join(""), fresh.fence.notice);
  assert.match(out.messages[0].content[0].text, /successor in tenure 1/);
  assert.equal(out.messages[1].content, "the operator's new instruction");
  assert.equal(JSON.stringify(out.messages).includes("successor thinking"), false);
});

test("a switch that did not replace the transcript is reported, not claimed", async () => {
  // A cancelled `session_before_switch` leaves the incumbent's file in place.
  // The record is committed and the tenure advances, but the receipt must not
  // say the transcript was replaced.
  const { host, state, manager } = await realCtxHost();
  manager.newSession = async () => false;
  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);
  assert.equal(relay.ok, true, JSON.stringify(relay));
  assert.equal(relay.handedOver.transcriptReplaced, false);
  assert.equal(relay.handedOver.switchFailure, "SESSION_SWITCH_CANCELLED");
  assert.equal(state.ledger.tenure, 1);
  // The fence still stands: the incumbent's conversation is the live one.
  assert.equal(state.fence.successor, 1);
});

test("a crash before the reset recovers as the successor, with the same barrier", async () => {
  // The window §2.4 第 3 步 is about: the record is durable, the transcript was
  // never replaced. Recovery must come up as the successor (tenure 1) with the
  // fence that counts the INCUMBENT's still-live transcript — otherwise the
  // restart silently un-hides the conversation the relay was meant to hide.
  const { host, state, manager } = await realCtxHost({
    transcript: [
      { type: "message", message: { role: "user", content: "build the thing" } },
      { type: "message", message: { role: "assistant", content: "incumbent plan" } },
    ],
  });
  // The process dies inside the switch, after the record is published.
  manager.newSession = async () => {
    throw new Error("process died before reset");
  };
  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);

  // The record is committed; the switch is honestly reported as not done.
  assert.equal(relay.ok, true, JSON.stringify(relay));
  assert.equal(relay.handedOver.transcriptReplaced, false);
  assert.equal(relay.handedOver.switchFailure, "NEW_SESSION_FAILED");
  assert.equal(state.ledger.tenure, 1);

  // A new process on the same transcript rebuilds the identical fence. The
  // switch died, so the live transcript is still the incumbent's, under the id
  // it always had.
  const fresh = new SessionState({ sessionId: manager.getSessionId(), role: ROLE.MANAGER, root: state.root });
  const record = fresh.relayRecordIn(manager);
  assert.ok(record !== null);
  fresh.adoptRelayRecord(record);
  assert.equal(fresh.ledger.tenure, 1);
  assert.deepEqual(fresh.fence, state.fence);
  // And it really hides the incumbent's conversation.
  const context = createContextHandler({ barrier: () => fresh.fence });
  const out = await context({
    messages: [
      { role: "user", content: "build the thing" },
      { role: "assistant", content: [{ type: "text", text: "incumbent plan" }] },
      { role: "user", content: "the successor's question" },
    ],
  });
  assert.ok(out !== undefined);
  assert.equal(JSON.stringify(out.messages).includes("incumbent plan"), false);
  assert.equal(out.messages.at(-1).content, "the successor's question");
});

test("a failed publish performs none of the three parts", async () => {
  // The publish fails ONCE, so the retry below proves the refusal left the
  // session able to relay again rather than wedged.
  // The publish fails ONCE. "throw" is the host's write failure (the batch is
  // rolled back), not the superseded publish, which keeps its entry.
  let failures = 1;
  const { host, state, session, manager, calls } = await relayHost({ publish: () => (failures-- > 0 ? "throw" : true) });
  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);
  assert.equal(relay.ok, false);
  assert.equal(relay.rejection, "RELAY_UNAVAILABLE");
  assert.equal(relay.handedOver.reason, "RELAY_COMMIT_FAILED");
  // No switch, no record, no tenure.
  assert.equal(calls.some(([kind]) => kind === "newSession"), false);
  assert.equal(relayRecords(manager.entries).length, 0);
  assert.equal(state.ledger.tenure, 0);
  assert.equal(state.fence, null);
  // The incumbent still judges, and the next empty review retries.
  assert.equal((await host.review([{ key: "b", value: "y" }])).ok, true);
  assert.equal((await host.review([{ key: "b", value: null }])).ok, true);
});

test("a publish the host rolls back is not reported as a commit", async () => {
  // Measured on omp 18.14.2 (`/tmp/hc-devops-final/harness`): `appendEntriesAtomically`
  // can DISCARD its staged temp (`session-storage.ts` returns normally when the
  // commit guard fails) and then throw "superseded", rolling the entry out of
  // the journal. `appendCustomEntry` has already answered an id, so an adapter
  // that trusts the id reports a record that exists nowhere — the successor's
  // ledger advanced and no `herdchestra.relay` entry was written at all.
  //
  // Two outcomes must stay apart:
  //
  //   - the entry is rolled back: no commit, and the hand-over refuses;
  //   - the entry survives in the journal while the publish was superseded (the
  //     host keeps it and the next publish carries it): a retry lands it, so the
  //     relay completes.
  const root = tmpRoot();
  // Every publish rolled back: nothing lands, so the hand-over must refuse.
  const rolledBack = journalSession({ streaming: false, publish: () => "rollback" });
  const state = new SessionState({ sessionId: "sess-relay-journal", role: ROLE.MANAGER, root });
  const host = new testSession({ pi: { registerTool: () => {} }, api: fakeApi([]), role: ROLE.MANAGER, ctx: rolledBack.session, state });
  await host.review([{ key: "a", value: "x" }]);
  const refused = await host.review([{ key: "a", value: null }]);
  assert.equal(refused.ok, false, JSON.stringify(refused));
  assert.equal(refused.rejection, "RELAY_UNAVAILABLE");
  assert.equal(refused.handedOver.reason, "RELAY_COMMIT_FAILED");
  assert.equal(relayRecords(rolledBack.manager.entries).length, 0, "a rolled-back publish leaves no record");
  assert.equal(state.ledger.tenure, 0, "and the tenure does not advance");

  // The same publish, superseded once, keeps the entry: the retry lands it.
  let superseded = 1;
  const superseding = journalSession({ streaming: false, publish: () => (superseded-- > 0 ? "supersede" : true) });
  const keptState = new SessionState({ sessionId: "sess-relay-journal", role: ROLE.MANAGER, root: tmpRoot() });
  const keptHost = new testSession({ pi: { registerTool: () => {} }, api: fakeApi([]), role: ROLE.MANAGER, ctx: superseding.session, state: keptState });
  await keptHost.review([{ key: "a", value: "x" }]);
  const relayed = await keptHost.review([{ key: "a", value: null }]);
  assert.equal(relayed.ok, true, JSON.stringify(relayed));
  const records = relayRecords(superseding.manager.entries);
  assert.ok(records.length >= 1, "the record is in the journal after the retry");
  assert.equal(keptState.ledger.tenure, 1);
});

test("a crash between the record and the reset leaves a successor, not half a tenure", async () => {
  // The dangerous window: the record is durable, the transcript was not reset.
  // A cold start must read the RECORD and come up as the successor, with the
  // barrier active — the previous conversation stays hidden even though its
  // transcript is still the one on disk.
  const { host, state, manager } = await relayHost();
  await host.review([{ key: "a", value: "x" }]);
  // Commit only the record: simulate the process dying before `newSession`.
  // The successor id the surface really writes for this session
  // (`SessionControl.relayPayload`: `<sessionId>#tenure-<n>`), so the record
  // identifies the transcript it governs.
  const payload = state.relayPayloadFor({
    successorSessionId: `${state.sessionId}#tenure-1`,
    previousSessionFile: "/tmp/incumbent.jsonl",
    fenceThrough: 3,
  });
  await host.commitRelayRecord(payload);
  const records = relayRecords(manager.entries);
  assert.equal(records.length, 1);

  // A new process on the same transcript.
  const fresh = new SessionState({ sessionId: "sess-relay-journal", role: ROLE.MANAGER, root: state.root });
  const adopted = fresh.relayRecordIn(manager);
  assert.ok(adopted !== null);
  fresh.adoptRelayRecord(adopted);
  assert.equal(fresh.ledger.tenure, 1);
  assert.equal(fresh.ledger.milestone, "execute");
  assert.equal(fresh.fence.successor, 1);
  assert.equal(fresh.fence.fenceThrough, 3);
});

test("a cold start rebuilds the fence from the transcript, and the barrier hides the old conversation", async () => {
  const { host, state, manager } = await relayHost();
  await host.review([{ key: "a", value: "x" }]);
  await host.review([{ key: "a", value: null }]);
  // The successor transcript carries the record (committed again after reset).
  const records = relayRecords(manager.entries);
  assert.equal(records.length, 1, "the reset transcript carries the record too");

  const fresh = new SessionState({ sessionId: "sess-relay-journal", role: ROLE.MANAGER, root: state.root });
  fresh.adoptRelayRecord(fresh.relayRecordIn(manager));
  assert.equal(fresh.fence.successor, 1);
  // The projection keeps the notice and the operator's own input only.
  const messages = [
    { role: "user", content: "build the thing" },
    { role: "assistant", content: [{ type: "text", text: "the incumbent's plan" }] },
    { role: "user", content: "and the successor's question" },
  ];
  const context = createContextHandler({ barrier: () => fresh.fence });
  const out = await context({ messages });
  assert.ok(out !== undefined);
  assert.equal(out.messages.length, 2);
  assert.deepEqual(out.messages[0].content.map((part) => part.text).join(""), fresh.fence.notice);
  assert.match(out.messages[0].content[0].text, /successor in tenure 1/);
  assert.equal(out.messages[1].content, "and the successor's question");
  // The incumbent's assistant text is gone from the successor's request.
  assert.equal(JSON.stringify(out.messages).includes("incumbent's plan"), false);
});

test("a relay on a live turn is DEFERRED, not refused, and nothing changes yet", async () => {
  // A tool call always runs with its prompt in flight
  // (`isStreaming = agent.state.isStreaming || #promptInFlightCount > 0`), so
  // refusing a streaming incumbent made a real relay impossible. The intent is
  // recorded durably and the tenure is untouched until the idle boundary.
  const { host, state, calls } = await relayHost({ streaming: true });
  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);

  // Not a success: nothing was retired, no barrier, no successor.
  assert.equal(relay.ok, false);
  assert.equal(relay.rejection, "RELAY_DEFERRED");
  assert.equal(relay.handedOver.deferred, true);
  assert.equal(relay.handedOver.retired, false);
  assert.equal(relay.handedOver.barrier, false);
  assert.equal(relay.handedOver.successor, null);
  assert.equal(relay.handedOver.busy, "SESSION_STREAMING");
  // Nothing was performed...
  assert.equal(calls.some(([kind]) => kind === "newSession"), false);
  // ...and the RECORD was committed, in this turn. That is the fact §5.3.2
  // needs: the host seals the manager at the terminal `agent_end`, so the turn
  // is the only moment a record can be published at all.
  assert.equal(relay.handedOver.recordPublished, true, JSON.stringify(relay));
  // The record is a tenure, so the LEDGER follows it. The fence does NOT: it
  // is armed at the transcript switch, so that the turn which published the
  // record keeps its own conversation (measured on 18.14.2: arming it here
  // made the model lose its history and fork 116 times in one turn).
  assert.equal(state.ledger.tenure, 1);
  assert.equal(state.fence, null, "the barrier waits for the switch");
  // ...but the obligation is on disk, so a restart resumes it.
  assert.ok(state.relayPending !== null, "the intent is durable");
  assert.equal(state.relayPending.tenure, 0);
  assert.equal(state.relayPending.milestone, "execute");
});

test("the deferred relay performs at the idle boundary, keeping the original review's meaning", async () => {
  // The receipt the incumbent got said "deferred". When the session goes idle
  // the same commit-then-reset runs, so the relay is the one the review asked
  // for — not a second, differently-shaped event.
  const { host, state, calls } = await relayHost({ streaming: true });
  await host.review([{ key: "a", value: "x" }]);
  const deferred = await host.review([{ key: "a", value: null }]);
  assert.equal(deferred.rejection, "RELAY_DEFERRED");
  // The record is already durable; only the switch is outstanding.
  assert.equal(deferred.handedOver.recordPublished, true);
  assert.equal(state.ledger.tenure, 1);
  assert.equal(deferred.handedOver.successor, null, "no successor transcript exists yet");

  // The turn ends: the host is idle now. The control surface reads liveness
  // from the session it wraps, so clearing that is what the host does when the
  // prompt's in-flight bracket drains.
  host.control.session.isStreaming = false;
  const performed = await host.performDeferredRelay();

  assert.equal(performed.ok, true, JSON.stringify(performed));
  assert.equal(performed.retired, true);
  assert.equal(performed.barrier, true);
  assert.ok(performed.successor !== null);
  assert.equal(calls.some(([kind]) => kind === "newSession"), true);
  assert.equal(state.ledger.tenure, 1);
  assert.equal(state.ledger.milestone, "execute");
  assert.equal(state.fence.successor, 1);
  // The intent is discharged: a second idle boundary does nothing.
  assert.equal(state.relayPending, null);
  assert.equal(await host.performDeferredRelay(), null);
});

test("a deferred relay that cannot run is reported, not silently dropped", async () => {
  // The live failure mode: the intent was recorded, the receipt said
  // "deferred", and the deferred step then found no reachable session manager.
  // It cleared the intent and said nothing, so a broken relay looked like a
  // quiet success. It must now keep the intent AND report the cause.
  const { host, state, ctx } = await realCtxHost({ streaming: true });
  await host.review([{ key: "a", value: "x" }]);
  await host.review([{ key: "a", value: null }]);
  assert.ok(state.relayPending !== null);

  // The turn is over, so the step is attempted — but the surface it needs has
  // no atomic publish (the live symptom of a stale or unwired context).
  ctx.isIdle = () => true;
  delete ctx.sessionManager.appendEntriesAtomically;
  const performed = await host.performDeferredRelay();
  // The record was committed in the turn, so the switch still runs. The
  // successor's copy cannot reach the HOST (no atomic primitive here), so it is
  // kept in the adapter's own journal (root §1.3) — the tenure stays rebuildable
  // after a restart, which is what §2.4 第 3 步 actually requires.
  assert.equal(performed.ok, true, JSON.stringify(performed));
  assert.equal(performed.retired, true);
  assert.equal(performed.barrier, true);
  assert.ok(performed.successor !== null);
  assert.equal(performed.successor.recordInSuccessorTranscript.ok, false, "the host refused it");
  assert.equal(performed.successor.recordInAdapterJournal.ok, true, "the adapter journal kept it");
  // The intent is discharged, because the relay really completed.
  assert.equal(state.relayPending, null);
  // The tenure is the record's, because the record really landed.
  assert.equal(state.ledger.tenure, 1);
  // The record really landed, so there is nothing "unreachable" to report: the
  // defect is that the successor's copy could not be published, and the receipt
  // above is what says so. `relayFailure` stays null because the surface was
  // reachable the whole time.
  assert.equal(state.relayFailure, null);
});

test("a deferred relay with no atomic publish at all is reported, not silently dropped", async () => {
  // The shape: the surface never had the primitive (an older host, or a context
  // that lost it before the turn even ran). It is a PERMANENT fact — no
  // boundary can publish through it — so it is named where it is first seen,
  // in the turn, before any intent is written. Reporting it as a refused record
  // would blame the write for a surface that cannot write at all.
  const { host, state, ctx } = await realCtxHost({ streaming: true });
  delete ctx.sessionManager.appendEntriesAtomically;
  await host.review([{ key: "a", value: "x" }]);
  const deferred = await host.review([{ key: "a", value: null }]);
  assert.equal(deferred.handedOver.reason, "NO_ATOMIC_RECORD");
  assert.equal(deferred.handedOver.recordPublished, false);

  // Nothing was recorded, so there is no intent to perform.
  assert.equal(state.relayPending, null);
  assert.equal(state.ledger.tenure, 0);
  ctx.isIdle = () => true;
  assert.equal(await host.performDeferredRelay(), null);
});

test("a later event's fresh context is adopted, so the deferred relay can commit", async () => {
  // The host builds a NEW `ExtensionContext` for every emit (runner.ts
  // `createContext`). Holding the `session_start` one forever left
  // `ctx.sessionManager` undefined for the rest of the session: Scope admission
  // degraded silently and the deferred relay could not commit at `agent_end`.
  await withRoot(async () => {
    // The context the first event carries: identity only, no live manager.
    const bootCtx = {
      ui: {},
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => "fresh-ctx-1", getEntries: () => [] },
    };
    const adapter = new HostAdapter({ pi: { registerTool: () => {}, on: () => {} }, ctx: bootCtx });
    assert.equal(adapter.bind(bootCtx), adapter);
    assert.equal(adapter.ctx, bootCtx, "the first event establishes the identity");

    // Every LATER event carries a different object with the live manager.
    const { ctx: liveCtx } = realCtxShape({ streaming: true });
    assert.notEqual(liveCtx, bootCtx);
    adapter.bind(liveCtx);

    assert.equal(adapter.ctx, liveCtx, "the live context replaced the boot one");
    // The control surface is rebuilt on it, so the session manager is reachable.
    assert.equal(adapter.session.control.reason(), "SESSION_STREAMING");
    assert.notEqual(adapter.session.control.session, bootCtx);

    // The identity is NOT re-derived: rebinding must not move the session state.
    const stateBefore = adapter.session.state;
    adapter.bind(realCtxShape().ctx);
    assert.equal(adapter.session.state, stateBefore, "rebinding never replaces the session state");
  });
});

test("the agent_end hook performs a deferred relay only on a terminal end", async () => {
  // `agent_end` with `willContinue` is a CONTINUATION (retry, queued input,
  // background work): the session is not settled, so the relay must wait for
  // the terminal end rather than switch the transcript mid-continuation.
  const { createAgentEndHandler } = await import("./hooks.mjs");
  const seen = [];
  const handler = createAgentEndHandler({ performDeferredRelay: async (event) => seen.push(event) });
  await handler({ type: "agent_end", willContinue: true });
  assert.equal(seen.length, 0, "a continuation is not the idle boundary");
  await handler({ type: "agent_end" });
  assert.equal(seen.length, 1, "a terminal end is");
  // A session with no handler supplied is a no-op, never a crash.
  assert.equal(await createAgentEndHandler({})({ type: "agent_end" }), undefined);
});

test("the extension registers agent_end, so the deferral has somewhere to run", async () => {
  // Without this registration a deferred relay would sit on disk forever: the
  // receipt promised it would run at the idle boundary.
  await withRoot(async () => {
    const handlers = new Map();
    const api = {
      registerTool: () => {},
      getActiveTools: () => [],
      setActiveTools: async () => {},
      on: (event, handler) => {
        if (!handlers.has(event)) handlers.set(event, []);
        handlers.get(event).push(handler);
      },
      pi: {},
    };
    herdchestra(api);
    assert.equal(handlers.has("agent_end"), true);
    assert.equal(handlers.get("agent_end").length, 1);
  });
});

test("every later event adopts its own context through the adapter's own surface", async () => {
  // Measured live (omp 18.14.2): the extension's stderr carried
  // `this.#session.refreshContext is not a function` on every event after the
  // first. The runner isolates a throwing handler, so the adapter kept reading
  // the `session_start` context for the rest of the session and the deferred
  // relay could not commit. The binding must reach the surface it actually
  // holds, and must survive a surface that lacks the method without throwing.
  await withRoot(async () => {
    const handlers = new Map();
    const api = {
      registerTool: () => {},
      getActiveTools: () => [],
      setActiveTools: async () => {},
      on: (event, handler) => {
        if (!handlers.has(event)) handlers.set(event, []);
        handlers.get(event).push(handler);
      },
      pi: {},
    };
    herdchestra(api);
    const boot = realCtxShape().ctx;
    await handlers.get("context")[0]({ messages: [] }, boot);
    const later = realCtxShape().ctx;
    // The second event must be adopted: no throw, and the surface now reads the
    // live manager the second context carries.
    const answer = await handlers.get("context")[0]({ messages: [] }, later);
    assert.equal(answer, undefined);

    // The adapter's own session is what gets refreshed. A surface that does not
    // implement the method is reported, not thrown — the host swallows the
    // throw, which is exactly how this stayed invisible.
    const adapter = new HostAdapter({ pi: api, ctx: boot });
    adapter.bind(boot);
    const surface = adapter.session;
    let refreshed = null;
    Object.defineProperty(surface, "refreshContext", {
      value: (ctx) => {
        refreshed = ctx;
      },
      configurable: true,
    });
    adapter.bind(later);
    assert.equal(refreshed, later, "the later event's context is what the surface adopts");
    delete surface.refreshContext;
    assert.doesNotThrow(() => adapter.bind(realCtxShape().ctx));
  });
});

test("the scope annotation is read wherever the host carries it", async () => {
  // §6.2 makes the annotation a flat `scope` field. OMP hands a tool's
  // parameters as `input`, so `input.scope` is read first; an event that carries
  // the annotation beside the parameters is read too. Neither is invented: an
  // event with no annotation is refused.
  const session = await sessionFor({ role: ROLE.MANAGER });
  const annotation = { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 3 };
  assert.equal(session.scopeAdmit({ toolName: "js-manager", input: { scope: annotation } }).ok, true);
  assert.equal(session.scopeAdmit({ toolName: "js-manager", scope: annotation }).ok, true);
  assert.equal(session.scopeAdmit({ toolName: "js-manager" }).code, "SCOPE_ANNOTATION_MISSING");
  assert.equal(session.scopeAdmit({ toolName: "js-manager", input: {} }).code, "SCOPE_ANNOTATION_MISSING");
});

test("a deferred relay reaches exactly the state a synchronous one would", async () => {
  // The property that makes deferral safe: WHEN the relay runs must not change
  // WHAT it establishes. Both paths are driven to completion and their durable
  // end state is compared field by field.
  const transcript = [
    { type: "message", message: { role: "user", content: "build the thing" } },
    { type: "message", message: { role: "assistant", content: "incumbent plan" } },
  ];

  // Synchronous: the session is idle when the review relays.
  const direct = await realCtxHost({ transcript: [...transcript] });
  await direct.host.review([{ key: "a", value: "x" }]);
  const syncReceipt = await direct.host.review([{ key: "a", value: null }]);
  assert.equal(syncReceipt.ok, true, JSON.stringify(syncReceipt));

  // Deferred: the session is mid-turn, so the relay waits for the idle boundary.
  const late = await realCtxHost({ transcript: [...transcript], streaming: true });
  await late.host.review([{ key: "a", value: "x" }]);
  const deferredReceipt = await late.host.review([{ key: "a", value: null }]);
  assert.equal(deferredReceipt.rejection, "RELAY_DEFERRED");
  // The RECORD advanced; the transcript did not. That is the deferral: the
  // durable half is done, the switch is not.
  assert.equal(late.state.ledger.tenure, 1, "the record is a tenure");
  assert.equal(deferredReceipt.handedOver.successor, null, "the switch is still outstanding");
  assert.equal(late.state.relayPending !== null, true, "and the intent records that");
  // The turn ends.
  late.ctx.isIdle = () => true;
  const performed = await late.host.performDeferredRelay();
  assert.equal(performed.ok, true, JSON.stringify(performed));
  assert.equal(performed.successor.replaced, true, "the switch ran at the boundary");

  // Same ledger, same fence, same durable file.
  assert.equal(late.state.ledger.tenure, direct.state.ledger.tenure);
  assert.equal(late.state.ledger.milestone, direct.state.ledger.milestone);
  assert.equal(late.state.ledger.successor, direct.state.ledger.successor);
  assert.deepEqual(late.state.ledger.obligations, direct.state.ledger.obligations);
  // Both paths END with the same fence, because both switch and then arm it.
  assert.deepEqual(late.state.fence, direct.state.fence, "the settled fence is the same");
  assert.equal(late.state.fence.successor, 1);
  const a = JSON.parse(fs.readFileSync(direct.state.file, "utf8"));
  const b = JSON.parse(fs.readFileSync(late.state.file, "utf8"));
  assert.deepEqual(b.fence, a.fence);
  assert.equal(b.tenure, a.tenure);
  assert.equal(b.milestone, a.milestone);
  assert.equal(b.obligations.length, a.obligations.length);
  // Both leave exactly one relay record, naming the same tenure.
  const recordOf = (built) => built.manager.getEntries().filter((e) => e.customType === "herdchestra.relay");
  assert.equal(recordOf(direct).length, 1);
  assert.equal(recordOf(late).length, 1);
  assert.equal(recordOf(late)[0].data.tenure, recordOf(direct)[0].data.tenure);
});

test("a pending intent survives a restart and is performed at the next idle boundary", async () => {
  // The intent is published BEFORE the turn ends, so a process that dies inside
  // that turn leaves it on disk. A restart must resume the obligation, not
  // silently forget a relay the incumbent was told had been deferred.
  const { host, state, ctx, manager } = await realCtxHost({ streaming: true });
  await host.review([{ key: "a", value: "x" }]);
  const deferred = await host.review([{ key: "a", value: null }]);
  assert.equal(deferred.rejection, "RELAY_DEFERRED");
  assert.equal(deferred.handedOver.recordPublished, true);
  assert.equal(state.ledger.tenure, 1);

  // A new process over the same transcript and the same state root.
  ctx.isIdle = () => true;
  const restarted = new testSession({
    pi: { registerTool: () => {} },
    api: null,
    role: ROLE.MANAGER,
    ctx,
    state: new SessionState({ sessionId: "sess-real-ctx", role: ROLE.MANAGER, root: state.root }),
  });
  // The intent SURVIVES, and must: the record the turn published sits in the
  // retired transcript, so the switch never ran. The record is the tenure (the
  // ledger and the fence are already the successor's); the intent is the
  // outstanding half, and the first idle boundary performs it.
  assert.ok(restarted.state.relayPending !== null, "the switch is still owed");
  // The transcript carries the record the turn published, so the restart is
  // already the successor tenure — and the fence it carries keeps the
  // incumbent's conversation out of the successor's first request. The
  // transcript switch itself has NOT run, which is what the intent is for.
  assert.equal(restarted.state.ledger.tenure, 1, "the record in the transcript is the tenure");
  assert.equal(restarted.state.fence.successor, 1, "and its fence is active from the first request");
  assert.equal(
    manager.getEntries().filter((e) => e.customType === "herdchestra.relay").length,
    1,
    "the record was published once, in the turn",
  );

  const performed = await restarted.performDeferredRelay();
  assert.equal(performed.ok, true, JSON.stringify(performed));
  assert.equal(restarted.state.ledger.tenure, 1);
  assert.equal(restarted.state.fence.successor, 1);
  assert.equal(performed.successor.replaced, true, "the switch the crash interrupted now ran");
  // The restart published only the successor's copy; the tenure did not advance
  // a second time.
  const records = manager.getEntries().filter((e) => e.customType === "herdchestra.relay");
  assert.equal(records.length, 1);
  assert.equal(records[0].data.retiredTranscript, false, "the copy the successor reads");
  assert.equal(restarted.state.relayPending, null, "and the intent is discharged");
});

// The live shape of the idle boundary, measured on omp 18.14.2: `agent_end`
// reaches an extension from `#endInFlight` → `#flushPendingAgentEnd` →
// `runner.emit`, i.e. while `#promptInFlightCount` is still 1. `ctx.isIdle()`
// reads `!session.isStreaming`, and `isStreaming` is
// `agent.state.isStreaming || #promptInFlightCount > 0` — so the handler's own
// `isIdle()` answers "streaming" for the turn that has just ended, and it
// answers that forever if nothing releases the bracket.
//
// The host releases it after the handler returns. This fake models exactly
// that: the flag clears one macrotask after the handler is entered, never
// before, so a handler that checked once and gave up would fail.
const liveIdleHost = async () => {
  const built = realCtxShape({ streaming: true });
  const root = tmpRoot();
  const state = new SessionState({ sessionId: "sess-real-ctx", role: ROLE.MANAGER, root });
  const host = new testSession({
    pi: { registerTool: () => {} },
    api: null,
    role: ROLE.MANAGER,
    ctx: built.ctx,
    state,
  });
  // The host's own `#endInFlight` tail, as the session runs it.
  const releaseAfterHandler = () => {
    setTimeout(() => {
      built.ctx.isIdle = () => true;
    }, 0);
  };
  return { host, state, built, releaseAfterHandler, root };
};

test("the deferred relay waits out the agent_end bracket instead of reading its own isIdle", async () => {
  // The measured live failure: every idle boundary found `SESSION_STREAMING`
  // and deferred again, so a durable intent never became a tenure.
  const { host, state, built, releaseAfterHandler } = await liveIdleHost();
  await host.review([{ key: "a", value: "x" }]);
  const deferred = await host.review([{ key: "a", value: null }]);
  assert.equal(deferred.rejection, "RELAY_DEFERRED");
  assert.equal(host.control.reason(), "SESSION_STREAMING", "the handler runs inside its own bracket");

  releaseAfterHandler();
  const performed = await host.performDeferredRelay();

  assert.equal(performed.ok, true, JSON.stringify(performed));
  assert.equal(performed.retired, true);
  assert.equal(performed.barrier, true);
  // `transcriptReplaced` is a fact about the SUCCESSOR the record names, and it
  // is reported there — the same field the synchronous path reports.
  assert.equal(performed.successor.replaced, true);
  assert.equal(performed.record.transcriptReplaced, true);
  assert.equal(state.ledger.tenure, 1);
  assert.equal(state.ledger.milestone, "execute");
  assert.equal(state.fence.successor, 1);
  assert.equal(state.relayPending, null);
  // The record is in the transcript the successor reads, and it names the
  // successor as this session continued — not a counter invented here.
  const records = built.entries().filter((entry) => entry.customType === "herdchestra.relay");
  assert.equal(records.length, 1);
  assert.equal(records[0].data.tenure, 1);
  // The reset mints a new id and the re-commit names the file it now sits in;
  // a record naming the old id would be refused by `recordGoverns` in its own
  // transcript on the next start.
  assert.equal(records[0].data.successorSessionId, "sess-real-ctx-successor");
  // The copy the reset left is the successor's own: it sits in the file the
  // successor writes, and says it is NOT a retired transcript's record. The
  // `previousSessionFile` keeps naming the RETIRED transcript: it is the
  // lineage, and `recordGoverns` compares it against the live file.
  assert.equal(records[0].data.previousSessionFile, "/tmp/incumbent-real.jsonl");
  assert.equal(records[0].data.retiredTranscript, false);
});

test("the record is PUBLISHED in the turn, and the idle boundary only switches", async () => {
  // Measured on omp 18.14.2 (`/tmp/hc-devops-final/harness`, three probes): at
  // the terminal `agent_end` the session manager is already sealed
  // (`dispose()` → `seal()` sets `#released` and bumps `#diskEpoch`, and every
  // commit guard is `!released && diskEpoch === epoch`), so NOTHING published
  // there — atomic or plain — ever lands. The only writable window is the live
  // turn.
  //
  // So the RECORD ITSELF is committed in the turn, through the host's own
  // verified atomic publish, and the intent that follows names it. A prepared
  // draft carried by an intent is not a commit, and the boundary must never
  // treat one as such.
  const { host, state, built, releaseAfterHandler } = await liveIdleHost();
  await host.review([{ key: "a", value: "x" }]);
  const deferred = await host.review([{ key: "a", value: null }]);
  assert.equal(deferred.rejection, "RELAY_DEFERRED");

  // The record is in the journal NOW, as the pre-switch copy with the boundary
  // the turn really had — published by the host, in its own right.
  const published = built.entries().filter((entry) => entry.customType === "herdchestra.relay");
  assert.equal(published.length, 1, "exactly one record, written in the turn");
  assert.equal(published[0].data.tenure, 1);
  assert.equal(published[0].data.milestone, "execute");
  assert.equal(published[0].data.retiredTranscript, true, "it sits in the transcript it retires");
  assert.equal(published[0].data.published, true, "and it says the host accepted it");
  assert.equal(deferred.handedOver.recordPublished, true);
  assert.equal(deferred.handedOver.recordId, published[0].id, "the receipt names the real entry");
  // The tenure is the record's; the transcript is not yet the successor's.
  assert.equal(state.ledger.tenure, 1);
  assert.equal(deferred.handedOver.successor, null);

  // The intent carries that same record, so a restart can tell which succession
  // the obligation belongs to.
  const intent = built.entries().filter((entry) => entry.customType === "herdchestra.relay.pending").at(-1);
  assert.ok(intent !== undefined, "the intent is durable");
  const carried = intent.data.record;
  assert.ok(carried !== undefined, "the intent carries the record it published");
  assert.equal(carried.tenure, 1);
  assert.equal(carried.milestone, "execute");
  assert.equal(carried.retiredTranscript, true, "it is the copy that sits in the transcript it retires");
  assert.equal(carried.notice, published[0].data.notice);

  releaseAfterHandler();
  const performed = await host.performDeferredRelay();
  assert.equal(performed.ok, true, JSON.stringify(performed));
  assert.equal(state.ledger.tenure, 1);
  // The boundary did NOT publish a second pre-switch record: the one from the
  // turn is the record, and the only write it makes is the successor's copy.
  assert.equal(performed.record.tenure, carried.tenure);
  assert.equal(performed.record.milestone, carried.milestone);
  assert.equal(performed.record.notice, carried.notice);
  const after = built.entries().filter((entry) => entry.customType === "herdchestra.relay");
  assert.equal(after.length, 1, "one record, re-published into the successor's transcript");
  assert.equal(after[0].data.retiredTranscript, false, "the copy the successor reads");
  assert.equal(after[0].data.fenceThrough, 0, "counting the successor's empty transcript");
  // The id follows the successor the switch minted, so the record that a
  // restart reads names the transcript it sits in (`recordGoverns`).
  assert.equal(after[0].data.successorSessionId, "sess-real-ctx-successor");
  // `previousSessionFile` keeps naming the RETIRED transcript. It is the
  // lineage, and `recordGoverns` reads it to refuse a retired record once the
  // reset has moved the live file — rewriting it to the successor's own path
  // would make a pre-switch record stop governing its own file.
  assert.equal(after[0].data.previousSessionFile, "/tmp/incumbent-real.jsonl");
});

test("a record the host rolls back is never reported as published, and no tenure advances", async () => {
  // The decisive negative case. The host can stage the entry, discard the temp
  // and roll the entry back (`#rollbackAtomicEntryBatch`). The receipt must say
  // the record did NOT land, the ledger must stay where it was, and no intent
  // may be written pointing at a record that exists nowhere.
  const { host, state, ctx, entries } = await realCtxHost({ streaming: true, publish: "rollback" });
  await host.review([{ key: "a", value: "x" }]);
  const deferred = await host.review([{ key: "a", value: null }]);

  assert.equal(deferred.ok, false);
  assert.notEqual(deferred.rejection, "RELAY_DEFERRED", "a refused publish is not a deferral");
  assert.equal(deferred.handedOver.recordPublished, false, "the record is not durable");
  assert.equal(deferred.handedOver.recordId, null, "and no entry id is invented");
  assert.equal(deferred.handedOver.retired, false);
  assert.equal(deferred.handedOver.successor, null);
  // Nothing about the tenure changed, and nothing claims it did.
  assert.equal(state.ledger.tenure, 0);
  assert.equal(state.fence, null);
  assert.equal(state.relayPending, null, "no intent points at a record that does not exist");
  assert.equal(entries().filter((entry) => entry.customType === "herdchestra.relay").length, 0);
  // And the boundary has nothing to perform.
  ctx.isIdle = () => true;
  assert.equal(await host.performDeferredRelay(), null);
  assert.equal(state.ledger.tenure, 0);
});

test("the in-turn record opens the tenure without arming a barrier against its own turn", async () => {
  // The record is published mid-turn (the only writable window), so the LEDGER
  // advances then. The FENCE does not: it governs the successor's requests and
  // is armed at the switch. Arming it at the publish stripped the model's own
  // conversation out of its next request in the same turn (measured on 18.14.2:
  // 116 forks in one turn, 18 worker sessions, killed at the 600s cap).
  const { host, state, calls } = await realCtxHost({ streaming: true });
  await host.review([{ key: "a", value: "x" }]);
  const deferred = await host.review([{ key: "a", value: null }]);

  assert.equal(deferred.rejection, "RELAY_DEFERRED");
  assert.equal(deferred.handedOver.recordPublished, true);
  assert.equal(state.ledger.tenure, 1, "the record opened the tenure");
  assert.equal(state.ledger.milestone, "execute");
  assert.equal(state.fence, null, "no barrier against the turn that published it");
  assert.equal(state.relayOwed, true, "and the successor is recorded as owed");
  // The switch has not run, so the receipt does not claim it did.
  assert.equal(deferred.handedOver.successor, null);
  assert.equal(calls.some(([kind]) => kind === "newSession"), false);
  // The ledger file carries the tenure; it carries no fence, because none is
  // armed yet.
  const saved = JSON.parse(fs.readFileSync(state.file, "utf8"));
  assert.equal(saved.tenure, 1);
  assert.equal(saved.fence, undefined, "the ledger does not claim a barrier that is not armed");
});

test("publishing the record does NOT arm the barrier against the turn that published it", async () => {
  // Measured on omp 18.14.2 (relay-turn2): the record is published inside the
  // live turn, so an earlier version installed the fence then too. Applying it
  // to that same turn's remaining requests stripped the model's own
  // conversation from them — it kept only the operator's message and the
  // notice, lost the fact that it had already forked, and forked 116 times in
  // one turn across 18 worker sessions before the run was killed at the 600s
  // cap.
  //
  // The barrier belongs to the SUCCESSOR's requests (§2.4 第 4 步), so the two
  // halves are now separate: the LEDGER records the tenure at the publish, the
  // FENCE is armed at the transcript switch.
  const { host, state, ctx } = await realCtxHost({ streaming: true });
  await host.review([{ key: "a", value: "x" }]);
  const deferred = await host.review([{ key: "a", value: null }]);
  assert.equal(deferred.rejection, "RELAY_DEFERRED");
  assert.equal(deferred.handedOver.recordPublished, true);

  // The tenure is the record's, but no fence is armed yet: the incumbent is
  // still the one answering.
  assert.equal(state.ledger.tenure, 1, "the record opened the tenure");
  assert.equal(state.fence, null, "the barrier waits for the switch");
  assert.equal(state.relayOwed, true, "the successor is owed, and the model is told");

  // Once the switch runs, the fence is armed and counts the successor's
  // (empty) transcript.
  ctx.isIdle = () => true;
  const performed = await host.performDeferredRelay();
  assert.equal(performed.ok, true, JSON.stringify(performed));
  assert.equal(performed.successor.replaced, true);
  assert.equal(state.fence.successor, 1, "the successor's requests are barriered");
  assert.equal(state.fence.fenceThrough, 0, "against a transcript that starts empty");
  assert.equal(state.relayOwed, false, "and nothing is owed any more");
});

test("a deferred relay tells the caller to stop, so the turn cannot run on", async () => {
  // The other half of the measured loop: a receipt that reported the durable
  // record without saying the decision was complete invited more work. The
  // relay is decided once the record lands; the only thing left is the turn
  // ending, and the switch runs from there.
  const { host, state } = await realCtxHost({ streaming: true });
  await host.review([{ key: "a", value: "x" }]);
  const deferred = await host.review([{ key: "a", value: null }]);

  assert.equal(deferred.rejection, "RELAY_DEFERRED");
  assert.equal(deferred.handedOver.next, "STOP", "the receipt says what to do");
  assert.match(deferred.handedOver.detail, /end your turn now/i);
  assert.match(deferred.handedOver.detail, /do not fork/i);
  // The state the receipt reports is the state that exists AFTER the adoption,
  // not the outgoing one. Reporting `tenure: 0` next to `recordPublished: true`
  // was what made the model believe work remained.
  assert.equal(deferred.tenure, 1, "the receipt names the tenure the record opened");
  assert.equal(deferred.milestone, "execute");
  // No fence yet: it is armed at the switch, so the incumbent's remaining
  // requests are not filtered by the relay it just decided.
  assert.equal(deferred.fence, undefined, "no barrier is claimed before the switch");
  assert.equal(state.ledger.tenure, 1);
});

test("a sealed boundary finds the carried record in the transcript file, not the journal", async () => {
  // Measured on omp 18.14.2 (final-relay-check, instrumented): at the terminal
  // `agent_end` the manager is sealed, and `getEntries()` answers NO relay entry
  // although the record is plainly on disk in that same run
  // (`herdstra.relay id=3cba7f83 tenure=1 published=true fenceThrough=46`).
  // Verifying only against the journal therefore declared a durable record
  // undurable, fell through to a fresh publish that cannot land, and skipped the
  // switch — the one step that needs no write.
  //
  // The file is the durable view at that boundary, so it must be able to answer.
  await withRoot(async () => {
    const sealedDir = fs.mkdtempSync(path.join(os.tmpdir(), "sealed-"));
    SESSION_ROOTS.push(sealedDir);
    const file = path.join(sealedDir, "inc.jsonl");
    const entries = [{ type: "message", message: { role: "user", content: "go" } }];
    const flush = () => fs.writeFileSync(file, entries.map((e) => JSON.stringify(e)).join("\n") + "\n");
    flush();
    let batch = null;
    let sealed = false;
    let currentFile = file;
    const manager = {
      getSessionId: () => "sess-sealed",
      getSessionFile: () => currentFile,
      // The sealed manager's journal no longer reflects the file.
      getEntries: () => (sealed ? [] : [...entries]),
      appendCustomEntry(customType, data) {
        if (sealed) throw new Error("the manager is sealed");
        const target = batch ?? entries;
        const id = `entry-${target.length}`;
        target.push({ type: "custom", customType, data, id });
        return id;
      },
      async appendEntriesAtomically(append) {
        if (sealed) throw new Error("Atomic session batch was superseded before commit.");
        batch = [];
        let result;
        try {
          result = append();
        } finally {
          const staged = batch;
          batch = null;
          entries.push(...staged);
          flush();
        }
        return result;
      },
      async newSession() {
        sealed = true;
        // A real reset moves the transcript AND empties it, so `replaced` is a
        // fact this fixture can actually produce.
        const previous = currentFile;
        currentFile = `${file}.successor`;
        entries.length = 0;
        flush();
        return { previous, file: currentFile };
      },
    };
    const ctx = {
      sessionManager: manager,
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      // Busy for the whole turn, so the relay is DEFERRED rather than performed
      // synchronously; the boundary is where the seal matters.
      isIdle: () => false,
    };
    const state = new SessionState({ sessionId: "sess-sealed", role: ROLE.MANAGER, root: tmpRoot() });
    const host = new testSession({ pi: { registerTool: () => {} }, api: null, role: ROLE.MANAGER, ctx, state });

    await host.review([{ key: "a", value: "x" }]);
    const deferred = await host.review([{ key: "a", value: null }]);
    assert.equal(deferred.handedOver.recordPublished, true, "the record landed in the turn");
    assert.equal(entries.filter((e) => e.customType === "herdchestra.relay").length, 1);

    // The host seals the manager before the idle boundary, exactly as measured.
    sealed = true;
    ctx.isIdle = () => true;
    const performed = await host.performDeferredRelay();

    // The decisive half: the boundary FOUND the carried record without the
    // journal, so it did not declare a durable record undurable and did not
    // fall through to a doomed publish. That is the defect this test pins.
    //
    // It then performs the switch, which succeeds, and attempts the successor's
    // copy — which CANNOT land here, because this fixture models the same seal
    // the host has. The result is the honest `RELAY_RECORD_NOT_DURABLE` with
    // retirement and barrier standing, never a claimed success.
    // The decisive half: the boundary FOUND the carried record without the
    // journal, so it did not declare a durable record undurable and did not
    // fall through to a doomed publish.
    //
    // The successor's copy then cannot reach the sealed host, so it is kept in
    // the adapter's own journal (root §1.3). The relay COMPLETES, and both facts
    // are reported separately so a reader can see which copy exists.
    assert.equal(performed?.ok, true, JSON.stringify(performed));
    assert.equal(performed.retired, true);
    assert.equal(performed.barrier, true);
    assert.ok(performed.successor !== null, "a successor is claimed only with a durable record");
    assert.equal(performed.successor.replaced, true, "the switch itself ran");
    assert.equal(performed.successor.recordInSuccessorTranscript.ok, false, "the sealed host refused the copy");
    assert.equal(performed.successor.recordInAdapterJournal.ok, true, "the adapter journal kept it");
  });
});

test("a restart rebuilds the successor from the adapter's own record when the host kept none", async () => {
  // The sealed boundary cannot take the successor's copy (no write lands), and
  // the host creates no successor transcript there either. Without an
  // adapter-owned copy the tenure would be unrebuildable and the relay would be
  // stuck one step short forever.
  //
  // Root §1.3 licenses exactly this: context-boundary facts may live in
  // `.Herdchestra/<session>.jsonl`. The record is written there with the SAME
  // payload the host published, so either view rebuilds the same fence.
  await withRoot(async () => {
    const root = tmpRoot();
    const incumbent = path.join(root, "inc.jsonl");
    const successor = path.join(root, "succ.jsonl");
    const entries = [{ type: "message", message: { role: "user", content: "go" } }];
    fs.writeFileSync(incumbent, entries.map((e) => JSON.stringify(e)).join("\n") + "\n");
    let current = incumbent;
    let batch = null;
    let sealed = false;
    let id = "sess-lineage";
    const manager = {
      getSessionId: () => id,
      getSessionFile: () => current,
      getEntries: () => (sealed ? [] : [...entries]),
      appendCustomEntry(customType, data) {
        if (sealed) throw new Error("the manager is sealed");
        const target = batch ?? entries;
        const entryId = `entry-${target.length}`;
        target.push({ type: "custom", customType, data, id: entryId });
        return entryId;
      },
      async appendEntriesAtomically(append) {
        if (sealed) throw new Error("Atomic session batch was superseded before commit.");
        batch = [];
        let result;
        try {
          result = append();
        } finally {
          const staged = batch;
          batch = null;
          entries.push(...staged);
        }
        return result;
      },
      async newSession() {
        sealed = true;
        const previous = current;
        current = successor;
        id = "sess-successor";
        fs.writeFileSync(successor, "");
        return { previous, file: current };
      },
    };
    const ctx = {
      sessionManager: manager,
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      isIdle: () => false,
    };
    const state = new SessionState({ sessionId: "sess-lineage", role: ROLE.MANAGER, root });
    const host = new testSession({ pi: { registerTool: () => {} }, api: null, role: ROLE.MANAGER, ctx, state });

    await host.review([{ key: "a", value: "x" }]);
    await host.review([{ key: "a", value: null }]);
    ctx.isIdle = () => true;
    const performed = await host.performDeferredRelay();

    // The relay completes, and the two copies are reported separately.
    assert.equal(performed.ok, true, JSON.stringify(performed));
    assert.equal(performed.successor.recordInSuccessorTranscript.ok, false, "the sealed host refused its copy");
    assert.equal(performed.successor.recordInAdapterJournal.ok, true, "the adapter kept one");
    assert.equal(state.fence.successor, 1);
    assert.equal(state.fence.fenceThrough, 0);

    // A restart rebuilds the same successor from the adapter's journal ALONE.
    //
    // Two traps this deliberately avoids, both of which produce a false reading:
    //   1. bind to the LINEAGE id, not the successor's. The journal is named
    //      after the lineage, so a state built on the successor id looks for a
    //      file that does not exist and then gets silently rescued by the
    //      successor's ledger — the test would pass without proving anything;
    //   2. delete the successor's ledger first, so no other file can supply the
    //      facts. Only `.relay.jsonl` is left.
    const successorLedger = `${stateDir(root)}/${safeSessionName("sess-successor")}.session.json`;
    fs.rmSync(successorLedger, { force: true });
    assert.equal(fs.existsSync(successorLedger), false, "the successor ledger cannot answer");

    const restarted = new SessionState({ sessionId: "sess-lineage", role: ROLE.MANAGER, root });
    const surface = new testSession({ pi: { registerTool: () => {} }, api: null, role: ROLE.MANAGER, ctx, state: restarted });
    assert.equal(restarted.ledger.tenure, 1, "the tenure survives on the journal alone");
    assert.deepEqual(restarted.fence, { notice: state.fence.notice, successor: 1, fenceThrough: 0 });
    assert.equal(restarted.relayOwed, false, "and nothing is owed");
    assert.equal(restarted.relayPending, null, "the stale intent is treated as discharged");
  });
});

test("a bracket that never clears keeps the intent and says so", async () => {
  // The other half of the same rule: waiting is bounded, and a wait that
  // expires changes NOTHING. The intent stays for the next boundary (or the
  // next process), and the reason is reported rather than swallowed.
  const { host, state } = await liveIdleHost();
  await host.review([{ key: "a", value: "x" }]);
  await host.review([{ key: "a", value: null }]);
  assert.ok(state.relayPending !== null);

  const performed = await host.performDeferredRelay({ maxPauseMs: 5 });
  assert.equal(performed.ok, false);
  assert.equal(performed.reason, "OWN_TURN_NOT_RELEASED");
  assert.ok(state.relayPending !== null, "the obligation survives a bracket that did not clear");
  // The record was published in the turn, so the tenure is its; what the
  // expired wait did NOT do is switch the transcript.
  assert.equal(state.ledger.tenure, 1);
  assert.equal(host.control.session.calls?.some?.(([kind]) => kind === "newSession") ?? false, false);
});

test("a deferred relay that is refused keeps its intent, whatever the reason", async () => {
  // A stale context or a lost atomic publish is a defect, not a discharge: the
  // obligation must survive it so a later boundary (or a restart) can finish it.
  // Clearing on `NO_ATOMIC_RECORD` is how a relayed tenure was silently lost.
  const { host, state, built } = await liveIdleHost();
  await host.review([{ key: "a", value: "x" }]);
  await host.review([{ key: "a", value: null }]);
  built.ctx.isIdle = () => true;
  delete built.manager.appendEntriesAtomically;

  const performed = await host.performDeferredRelay();
  // The turn's record is durable and the switch ran. The successor's copy cannot
  // reach a host with no atomic primitive, so it is kept in the adapter's own
  // journal (root §1.3) — the tenure stays rebuildable, so this is a completed
  // relay, not a refusal.
  assert.equal(performed.ok, true, JSON.stringify(performed));
  assert.equal(performed.successor.recordInAdapterJournal.ok, true);
  assert.equal(state.relayPending, null, "the obligation was discharged");
  assert.equal(state.ledger.tenure, 1, "the tenure is the record's");
  // And the adapter's own journal really carries it, so a restart rebuilds it.
  const retained = state.relayRecords();
  assert.equal(retained.length >= 1, true, "the adapter kept its own copy");
  assert.equal(retained[retained.length - 1].data.tenure, 1);
});

test("a deferred relay that cannot reach the host surface is reported to the model", async () => {
  // The receipt said "deferred" and the caller saw nothing else. A relay that
  // then cannot run must reach the next request as a fact, or the operator
  // believes a relay happened that did not.
  const { host, state, built } = await liveIdleHost();
  // The surface loses the atomic publish BEFORE the turn, so the turn cannot
  // publish the record and the obligation is all that exists.
  delete built.manager.appendEntriesAtomically;
  await host.review([{ key: "a", value: "x" }]);
  const deferred = await host.review([{ key: "a", value: null }]);
  // The turn names the permanent cause where it first meets it: a surface with
  // no atomic publish can never record a relay, at any boundary.
  assert.equal(deferred.handedOver.reason, "NO_ATOMIC_RECORD");
  assert.equal(state.relayPending, null, "no intent was recorded, because no publish landed");
  assert.equal(state.ledger.tenure, 0);
  built.ctx.isIdle = () => true;
  // Nothing was recorded, so there is nothing to perform and nothing changed.
  assert.equal(await host.performDeferredRelay(), null);
  assert.equal(state.ledger.tenure, 0);
  // The failure is on the receipt the caller received, with its reason.
  assert.equal(deferred.handedOver.detail.includes("atomic"), true);
});

test("property: no interleaving of commit, reset and refusal leaves half a tenure", async () => {
  // Root §2.4 第 3 步: retirement, the barrier and the successor land together or
  // not at all. The enumerated cases above pin the three crash points; this
  // property covers every combination of the three things that can differ in a
  // real run — whether the commit is published, whether the transcript switch
  // happens, and whether the host's bracket has cleared yet — and asserts the
  // one invariant that must survive all of them:
  //
  //   the ledger, the fence and the transcript agree on the SAME tenure, and an
  //   obligation is discarded only when the record that discharges it exists.
  await fc.assert(
    fc.asyncProperty(
      fc.boolean(), // the host's agent_end bracket has cleared
      fc.boolean(), // the atomic publish succeeds
      fc.boolean(), // the transcript switch replaces the file
      fc.array(fc.string({ minLength: 1, maxLength: 8 }), { minLength: 1, maxLength: 3 }),
      async (bracketCleared, publishOk, switchOk, findings) => {
        const built = realCtxShape({ streaming: !bracketCleared });
        const root = tmpRoot();
        const state = new SessionState({ sessionId: "sess-prop", role: ROLE.MANAGER, root });
        const host = new testSession({
          pi: { registerTool: () => {} },
          api: null,
          role: ROLE.MANAGER,
          ctx: built.ctx,
          state,
        });
        // The publish and the switch are the two host steps the property varies.
        if (!publishOk) built.ctx.sessionManager.appendEntriesAtomically = undefined;
        if (!switchOk) {
          built.ctx.sessionManager.newSession = async () => {
            built.calls.push(["newSession"]);
            return false;
          };
        }

        const obligations = findings.map((key) => ({ key, value: "v" }));
        await host.review(obligations);
        await host.review([]);
        // The bracket is the only thing the deferred step may wait for.
        if (bracketCleared) built.ctx.isIdle = () => true;
        const performed = await host.performDeferredRelay({ maxPauseMs: 200 });

        const tenure = state.ledger.tenure;
        if (performed === null) {
          // No intent at all: the synchronous path already ran, so everything
          // advanced together or nothing did.
          assert.equal(state.relayPending, null);
          assert.ok(tenure === 0 || tenure === 1);
          return;
        }
        if (performed.ok === true) {
          // A performed relay: the ledger, the fence and the record agree.
          assert.equal(tenure, 1);
          assert.equal(state.fence.successor, 1);
          assert.equal(performed.record.tenure, 1);
          assert.equal(state.ledger.obligations.length, 0);
          assert.equal(state.relayPending, null, "a discharged intent is cleared");
          return;
        }
        // A refused relay: NOTHING landed. The tenure is unchanged, no fence
        // was installed, and the obligation that asked for it is still on disk
        // — never silently discarded.
        assert.equal(tenure, 0);
        assert.equal(state.fence, null);
        assert.ok(state.relayPending !== null, `refused with ${performed.reason ?? performed.cause}`);
      },
    ),
    { ...fcRuns(40) },
  );
});

test("a retired intent does not take a newer one with it", async () => {
  // Two relays asked for in sequence: discharging the first must not drop the
  // second obligation, which names a later tenure.
  const { host, state } = await liveIdleHost();
  await host.review([{ key: "a", value: "x" }]);
  await host.review([{ key: "a", value: null }]);
  const pending = state.relayPending;
  assert.ok(pending !== null);
  state.adoptRelayPending({ ...pending, tenure: pending.tenure + 1 });

  state.clearRelayPending({ throughTenure: pending.tenure });
  assert.equal(state.relayPending.tenure, pending.tenure + 1, "the newer intent survives");
  state.clearRelayPending({ throughTenure: pending.tenure + 1 });
  assert.equal(state.relayPending, null, "the discharged intent is gone");
});

test("the successor header records its lineage, so a relay is distinguishable from a new session", async () => {
  // `SessionManager.#resetToNewSession` writes `parentSession: options?.parentSession`
  // into the successor's header, and the listing exposes it as
  // `parentSessionPath`. Passing no options (the earlier code) left the field
  // null, so a relayed successor looked like an unrelated new session.
  //
  // The value is the replaced transcript's PATH, not an id. The host's callers
  // disagree on this field (`fork` writes an id, `resume` writes a file path),
  // but the consumers settle it: `gc-cli` skips any value that is neither
  // absolute nor ends in `.jsonl`, and the host's own hook docs say "Path to
  // parent session". A relay is a transcript replacement, so it writes the path.
  const root = tmpRoot();
  const switches = [];
  const { ctx } = realCtxShape();
  ctx.sessionManager.getSessionFile = () => "/tmp/incumbent-real.jsonl";
  ctx.sessionManager.newSession = async (options) => {
    switches.push(options);
    return { file: "/tmp/successor.jsonl" };
  };
  const state = new SessionState({ sessionId: "lineage-parent", role: ROLE.MANAGER, root });
  const host = new testSession({ pi: { registerTool: () => {} }, api: null, role: ROLE.MANAGER, ctx, state });
  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);
  assert.equal(relay.ok, true, JSON.stringify(relay));
  assert.equal(switches.length, 1);
  assert.equal(switches[0].parentSession, "/tmp/incumbent-real.jsonl", "the successor names the transcript it replaced");
});

test("a record left in the incumbent transcript does not govern the successor", async () => {
  // The reset never rewrites the incumbent file, so it keeps the pre-switch
  // record forever. A later resume of that file must not adopt a tenure it
  // handed away: the record names the successor, which is a different session.
  //
  // Modeled directly, without running a relay first, so the ONLY record present
  // is the one under test.
  const root = tmpRoot();
  const entries = [
    { type: "message", message: { role: "user", content: "the incumbent's work" } },
    {
      type: "custom",
      customType: "herdchestra.relay",
      data: {
        v: 1,
        tenure: 1,
        milestone: "execute",
        // The successor's id: a DIFFERENT session than the file this sits in.
        successorSessionId: "the-successor",
        previousSessionFile: null,
        fenceThrough: 4,
        notice: "n",
      },
    },
  ];
  const manager = { getEntries: () => [...entries] };

  const incumbent = new SessionState({ sessionId: "the-incumbent", role: ROLE.MANAGER, root });
  assert.equal(incumbent.relayRecordIn(manager), null, "another session's record is ignored");
  assert.equal(incumbent.ledger.tenure, 0, "the incumbent does not adopt a tenure it gave away");

  // The same record IS the successor's own, so it governs there.
  const successor = new SessionState({ sessionId: "the-successor", role: ROLE.MANAGER, root });
  const adopted = successor.relayRecordIn(manager);
  assert.ok(adopted !== null, "the record governs the session it names");
  successor.adoptRelayRecord(adopted);
  assert.equal(successor.ledger.tenure, 1);
});

test("a relay's pre-switch record is not adopted once the reset left a different transcript", async () => {
  // Measured on the record a real relay left in `/tmp/hc-devops-relay5`
  // (omp 18.14.2): the pre-switch copy carries `successorSessionId` equal to the
  // `session.id` of the very file it sits in, because the successor is
  // `newSession` on the same manager and the id it reports at commit time is
  // still the incumbent's. So the id rule cannot separate the two copies.
  //
  // Two facts are kept apart, because they are different situations:
  //
  //   - the reset ran: the live transcript is another file, so the record that
  //     sits in the retired file opened a tenure that file no longer holds;
  //   - the reset did not run (a crash between the record and the switch): the
  //     retired file IS the live one, and the record is the crash-recovery fact
  //     §2.4 第 3 步 needs — its fence is what hides the incumbent's conversation.
  const root = tmpRoot();
  const retiredFile = "/tmp/retired-transcript.jsonl";
  const successorFile = "/tmp/successor-transcript.jsonl";
  const notice = "You are the successor in tenure 1 of this work.";
  const preSwitch = {
    v: 1,
    tenure: 1,
    milestone: "execute",
    successorSessionId: "the-session",
    previousSessionFile: retiredFile,
    fenceThrough: 4,
    notice,
    // Written by the commit half of a relay: it sits in the file it retires.
    retiredTranscript: true,
  };
  const managerIn = (file) => ({
    getSessionId: () => "the-session",
    getSessionFile: () => file,
    getEntries: () => [{ type: "custom", customType: "herdchestra.relay", data: preSwitch }],
  });

  // The reset ran: the live transcript is the successor's, so this record does
  // not govern it.
  const afterReset = new SessionState({ sessionId: "the-session", role: ROLE.MANAGER, root });
  assert.equal(afterReset.relayRecordIn(managerIn(successorFile)), null, "a retired record does not govern another transcript");
  assert.equal(afterReset.ledger.tenure, 0);

  // The reset never ran: the same file is still live, and the record governs.
  const beforeReset = new SessionState({ sessionId: "the-session", role: ROLE.MANAGER, root });
  const adopted = beforeReset.relayRecordIn(managerIn(retiredFile));
  assert.ok(adopted !== null, "the crash-recovery case still adopts the record");
  beforeReset.adoptRelayRecord(adopted);
  assert.equal(beforeReset.ledger.tenure, 1);
  assert.equal(beforeReset.fence.fenceThrough, 4, "and keeps the boundary the incumbent's file really had");
});

test("the successor's own record names the successor, so a restart in that file adopts it", async () => {
  // The reset mints a NEW session id. The pre-switch record named the OLD one, so
  // the record written into the successor transcript must be rebuilt with the
  // successor's id — otherwise `recordGoverns` refuses it in its own file and a
  // restart silently loses the fence.
  const root = tmpRoot();
  let currentId = "incumbent-id";
  // A real switch moves BOTH the id and the transcript; a fixture that moves
  // only the id reports `replaced: false` and skips the re-commit entirely.
  let currentFile = "/tmp/incumbent.jsonl";
  // `entries()` is the fixture's LIVE array (`getEntries()` returns a copy), so
  // it is the only handle that can truncate the transcript the manager reads.
  const { ctx, manager, entries: liveEntries } = realCtxShape();
  const clearTranscript = () => {
    liveEntries().length = 0;
  };
  ctx.sessionManager.getSessionId = () => currentId;
  ctx.sessionManager.getSessionFile = () => currentFile;
  ctx.sessionManager.newSession = async () => {
    currentId = "successor-id";
    currentFile = "/tmp/successor.jsonl";
    // The host's reset starts the successor's transcript empty; the record is
    // written again into it, which is the whole point of the re-commit.
    // `getEntries()` returns a copy, so clear through the fixture's own accessor.
    clearTranscript();
    return { file: currentFile };
  };
  const state = new SessionState({ sessionId: "incumbent-id", role: ROLE.MANAGER, root });
  const host = new testSession({ pi: { registerTool: () => {} }, api: null, role: ROLE.MANAGER, ctx, state });
  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);
  assert.equal(relay.ok, true, JSON.stringify(relay));

  const records = manager.getEntries().filter((entry) => entry.customType === "herdchestra.relay");
  assert.equal(records.length, 1, "the successor transcript carries exactly one record");
  assert.equal(records[0].data.successorSessionId, "successor-id", "it names the session it sits in");
  assert.equal(records[0].data.fenceThrough, 0, "and counts that transcript, which is empty");

  // A restart in the successor's file adopts it. The manager reports the file
  // the session now writes to, which is the successor's, and the id that file
  // carries.
  ctx.sessionManager.getSessionFile = () => currentFile;
  const restarted = new SessionState({ sessionId: "successor-id", role: ROLE.MANAGER, root });
  const adopted = restarted.relayRecordIn(manager);
  assert.ok(adopted !== null, "the record governs the successor's own transcript");
  restarted.adoptRelayRecord(adopted);
  assert.equal(restarted.ledger.tenure, 1);
  assert.equal(restarted.fence.successor, 1);
});

test("the successor's ledger is written under the successor's own id", async () => {
  // Measured live on 18.14.2: after a relay the successor tenure's ledger lived
  // in the INCUMBENT's `.session.json`, and `.Herdchestra/<successor>.session.json`
  // was never created. The file path was derived once in the constructor, so it
  // never followed the id the record adopted.
  const root = tmpRoot();
  let currentId = "ledger-incumbent";
  let currentFile = "/tmp/incumbent.jsonl";
  const { ctx, manager } = realCtxShape();
  ctx.sessionManager.getSessionId = () => currentId;
  ctx.sessionManager.getSessionFile = () => currentFile;
  ctx.sessionManager.newSession = async () => {
    currentId = "ledger-successor";
    currentFile = "/tmp/successor.jsonl";
    return { file: currentFile };
  };
  const state = new SessionState({ sessionId: "ledger-incumbent", role: ROLE.MANAGER, root });
  const host = new testSession({ pi: { registerTool: () => {} }, api: null, role: ROLE.MANAGER, ctx, state });
  await host.review([{ key: "a", value: "x" }]);
  const relay = await host.review([{ key: "a", value: null }]);
  assert.equal(relay.ok, true, JSON.stringify(relay));

  // The successor owns a ledger file, and it describes tenure 1.
  const successorFile = `${stateDir(root)}/ledger-successor.session.json`;
  assert.equal(fs.existsSync(successorFile), true, "the successor's ledger file exists");
  const saved = JSON.parse(fs.readFileSync(successorFile, "utf8"));
  assert.equal(saved.tenure, 1);
  assert.equal(saved.milestone, "execute");
  assert.equal(saved.fence.successor, 1);
  assert.equal(saved.obligations.length, 0);
  // And the successor can write to it again (the path followed the id).
  assert.equal(state.file, successorFile);
  assert.ok(manager !== null);
});

test("the barrier never deletes an operator message, even one identical to the notice", async () => {
  // The operator may type anything, including exactly the notice's bytes. Two
  // messages with the same words are still two facts — one written by the
  // operator, one by this adapter — and §5.3.2 step 3 exists to preserve the
  // operator's own input. An earlier version dropped every message whose whole
  // text matched the notice, which deleted real operator input.
  const { barrierFor } = await import("./request.mjs");
  const notice = "You are the successor in tenure 1 of this work.";
  const fence = { notice, successor: 1, fenceThrough: 0 };
  // The notice the barrier writes: one text part plus the adapter's marker. The
  // marker is what makes a replayed copy recognisable; the shape is what the
  // Pair Hint's prefix chain compares, so it is the same on every request.
  const adapterCopy = (text) => ({ role: "user", content: [{ type: "text", text }], herdchestraSuccession: true });

  // An operator message with the notice's exact bytes is KEPT.
  const sameBytes = barrierFor([{ role: "user", content: notice }], fence);
  assert.equal(sameBytes.length, 2, "the operator's message is not deleted for matching the notice");
  assert.deepEqual(sameBytes[0], adapterCopy(notice), "the barrier's own copy comes first");
  assert.equal(sameBytes[1].content, notice, "the operator's identical message survives verbatim");

  // The same holds when it arrives as text parts.
  const parts = barrierFor([{ role: "user", content: [{ type: "text", text: notice }] }], fence);
  assert.equal(parts.length, 2);
  assert.deepEqual(parts[1].content, [{ type: "text", text: notice }]);

  // An ordinary operator message still gets the notice in front.
  const two = barrierFor([{ role: "user", content: "carry on" }], fence);
  assert.equal(two.length, 2);
  assert.deepEqual(two[0], adapterCopy(notice));
  assert.equal(two[1].content, "carry on");

  // No operator message at all: the notice stands alone.
  const alone = barrierFor([], fence);
  assert.equal(alone.length, 1);
  assert.deepEqual(alone[0], adapterCopy(notice));

  // Only a copy this adapter MARKED is dropped — identity, not content.
  // The marked copy is the NEWEST user message here, which is the one the
  // barrier would otherwise keep; the operator's earlier words sit behind it
  // and the barrier's slice rule is what drops those, not this filter.
  const marked = barrierFor(
    [
      { role: "user", content: notice, herdchestraSuccession: true },
      { role: "user", content: "keep going with the migration" },
    ],
    fence,
  );
  assert.equal(marked.length, 2, JSON.stringify(marked));
  assert.deepEqual(marked[0], adapterCopy(notice), "the barrier's own copy");
  assert.equal(marked[1].content, "keep going with the migration", "the operator's words are preserved verbatim");

  // A marked copy is dropped when it is the newest user message too.
  const markedLast = barrierFor(
    [
      { role: "user", content: "keep going with the migration" },
      { role: "user", content: notice, herdchestraSuccession: true },
    ],
    fence,
  );
  assert.equal(markedLast.length, 1, JSON.stringify(markedLast));
  assert.deepEqual(markedLast[0], adapterCopy(notice));

  // The marker is also read from the host's `custom_message` details.
  const markedDetails = barrierFor(
    [{ role: "user", content: notice, details: { herdchestraSuccession: true } }],
    fence,
  );
  assert.equal(markedDetails.length, 1);

  // A message that merely MENTIONS the relay was never a candidate anyway.
  const mentions = "after the relay, run the suite";
  const keptMention = barrierFor([{ role: "user", content: mentions }], fence);
  assert.equal(keptMention.length, 2);
  assert.equal(keptMention[1].content, mentions);
});

test("a completed relay leaves no pending intent behind", async () => {
  // An intent whose record already exists was discharged; replaying it would
  // open a second successor for one review.
  const { host, state, ctx } = await realCtxHost({ transcript: [] });
  await host.review([{ key: "a", value: "x" }]);
  await host.review([{ key: "a", value: null }]);
  assert.equal(state.ledger.tenure, 1);
  assert.equal(state.relayPending, null);
  // A restart over the same files does not resurrect it.
  const restarted = new SessionState({ sessionId: "sess-real-ctx", role: ROLE.MANAGER, root: state.root });
  assert.equal(restarted.relayPending, null);
  assert.ok(ctx !== null);
});

test("a host with no atomic publish refuses and performs none of the three parts", async () => {
  // The honest remaining blocker: a session manager without
  // `appendEntriesAtomically` cannot commit the record, so nothing happens.
  const { host, state } = await relayHost();
  await host.review([{ key: "a", value: "x" }]);
  // The port wired with NO commit is exactly what a host without
  // `appendEntriesAtomically` reaches: it must refuse before any part runs.
  const bare = {
    reason: () => null,
    newConversation: async () => {
      throw new Error("a refused relay must not switch the transcript");
    },
    relayPayload: ({ fenceThrough }) => ({
      v: 1,
      tenure: 1,
      milestone: "execute",
      successorSessionId: "s",
      previousSessionFile: null,
      fenceThrough,
      notice: "n",
    }),
  };
  const refused = await handOverPort({ present: bare, commit: null }).handOver({
    retire: true,
    openSuccessor: true,
    barrier: true,
  });
  assert.equal(refused.ok, false);
  assert.equal(refused.reason, "NO_ATOMIC_RECORD");
  assert.equal(refused.retired, false);
  assert.equal(refused.successor, null);
  assert.equal(state.ledger.tenure, 0);
});

test("the successor's first empty review promotes, and the same callId replays", async () => {
  // §2.4 第 5 步: the successor's first empty review confirms the previous
  // tenure's work and moves the milestone; §2.4 第 6 步: a replayed callId
  // returns the original receipt instead of relaying again.
  const { host, state } = await relayHost();
  await host.review([{ key: "a", value: "x" }]);
  await host.review([{ key: "a", value: null }]);
  assert.equal(state.ledger.tenure, 1);
  const confirm = await host.review([]);
  assert.equal(confirm.ok, true);
  assert.equal(confirm.kind, "confirm");
  assert.equal(confirm.tenure, 1);
  assert.equal(confirm.milestone, "close");
  // The successor is no longer awaiting its judgement.
  assert.equal(state.ledger.successor, false);
});

test("handOver refuses when there is no control surface to carry the barrier", async () => {
  const session = await sessionFor({ role: ROLE.MANAGER });
  const result = await session.state.handOver(null);
  assert.equal(result.ok, false);
  assert.equal(result.reason, "NO_SESSION_CONTROL");
  assert.equal(session.state.ledger.tenure, 0);
});

test("every tool call needs a scope annotation except the two snapshot members", async () => {
  const session = await sessionFor({ role: ROLE.MANAGER });
  // Spec §6.2: an absent annotation is refused for any non-exempt tool.
  // Exempting a tool by class would let calls pass without touching the budget.
  for (const toolName of ["fork", "join", "js-manager", "read", "write", "mcp__semble_search"]) {
    const refused = session.scopeAdmit({ toolName, input: {} });
    assert.equal(refused.ok, false, toolName);
    assert.equal(refused.code, "SCOPE_ANNOTATION_MISSING", toolName);
  }
  // §6.2's two snapshot members are the only exemptions.
  for (const toolName of ["negotiate", "askPreviousScope"]) {
    const admitted = session.scopeAdmit({ toolName, input: {} });
    assert.equal(admitted.ok, true, toolName);
    assert.equal(admitted.exempt, true, toolName);
  }
  // An unknown kind is refused and names the kind it saw.
  const unknown = session.scopeAdmit({ toolName: "js-manager", input: { scope: { kind: "local" } } });
  assert.equal(unknown.code, "SCOPE_KIND_INVALID");
  assert.equal(unknown.details.kind, "local");
});

test("fork and join declare a scope parameter, so the model can annotate them", () => {
  const tools = new HostAdapter({
    pi: { registerTool() {}, getActiveTools: () => [], setActiveTools: async () => {}, on() {} },
    ctx: null,
  }).tools();
  for (const name of ["fork", "join", "js-manager", "js-worker", "js-review"]) {
    const tool = tools.find((candidate) => candidate.name === name);
    assert.ok(tool, name);
    assert.ok(tool.parameters.properties.scope, `${name} has no scope parameter`);
    assert.deepEqual(tool.parameters.properties.scope.properties.kind.enum, [
      "keepLast",
      "removeLast",
      "meToo",
      "resetScope",
    ]);
  }
});

test("the documented first-call resetScope annotation admits and settles a program face", async () => {
  const session = await sessionFor({ role: ROLE.MANAGER });
  session.scopeOpenTurn({ turnIndex: 1 });
  const first = {
    toolName: "js-manager",
    toolCallId: "c1",
    input: { scope: { kind: "resetScope", newScopeGoal: "build", newScopeRetentionBudget: 5 } },
  };
  assert.equal(session.scopeAdmit(first).ok, true);
  assert.equal((await session.scopeSettle(first)).ok, true);
  session.scopeOpenTurn({ turnIndex: 2 });
  const second = { toolName: "js-manager", toolCallId: "c2", input: { scope: { kind: "keepLast" } } };
  assert.equal(session.scopeAdmit(second).ok, true);
  assert.equal((await session.scopeSettle(second)).ok, true);
  assert.equal(await session.scopeCloseTurn(), null);
});

test("a turn is booked at the host's model boundary, not per tool call", async () => {
  const session = await sessionFor({ role: ROLE.CODER });
  const call = async (turnIndex, callId, scope) => {
    session.scopeOpenTurn({ turnIndex });
    const event = { toolName: "js-worker", toolCallId: callId, input: { scope } };
    const admitted = await session.scopeAdmit(event);
    const settled = await session.scopeSettle(event);
    return { admitted: admitted?.ok ?? admitted, settled: settled?.ok ?? settled };
  };
  // A budget must exist before anything books (cold-start rule).
  assert.deepEqual(
    await call(1, "a", { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 5 }),
    { admitted: true, settled: true },
  );
  assert.equal(await session.scopeCloseTurn(), null);

  // Two concurrent meToo calls in ONE model turn: neither settles the turn, and
  // the refusal only arrives at the turn boundary.
  await call(2, "b", { kind: "meToo" });
  await call(2, "c", { kind: "meToo" });
  assert.equal((await session.scopeCloseTurn()).code, "SCOPE_METOO_ONLY");

  // A real annotation settles the turn normally.
  await call(3, "d", { kind: "keepLast" });
  assert.equal(await session.scopeCloseTurn(), null);
});

test("a turn of nothing but meToo is refused at the host's turn boundary, and the refusal reaches the model", async () => {
  // `closeTurn` answers a Promise (the judgement needs a settled book). The
  // refusal is compared after awaiting it; comparing the Promise itself to
  // `null` is never true, which would swallow `SCOPE_METOO_ONLY` silently at the
  // one place it is allowed to be raised.
  await withRoot(async () => {
    const session = await sessionFor({ role: ROLE.CODER });
    const hook = createTurnEndHandler({ scopeCloseTurn: (event) => session.scopeCloseTurn(event) });

    // A turn that settles a real annotation is accepted: no refusal, no context.
    session.scopeOpenTurn({ turnIndex: 1 });
    const reset = { toolName: "js-worker", toolCallId: "r1", input: { scope: { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 5 } } };
    assert.equal((await session.scopeAdmit(reset)).ok, true);
    assert.equal((await session.scopeSettle(reset)).ok, true);
    assert.equal(await hook({ sessionId: session.state.sessionId }), undefined);

    // A turn of staged `meToo` and nothing else is refused at the boundary.
    session.scopeOpenTurn({ turnIndex: 2 });
    for (const callId of ["m1", "m2"]) {
      const event = { toolName: "js-worker", toolCallId: callId, input: { scope: { kind: "meToo" } } };
      assert.equal((await session.scopeAdmit(event)).ok, true);
      assert.equal((await session.scopeSettle(event)).ok, true);
    }
    const refusal = await hook({ sessionId: session.state.sessionId });
    assert.equal(typeof refusal?.additionalContext, "string", JSON.stringify(refusal));
    assert.match(refusal.additionalContext, /SCOPE_METOO_ONLY/);
  });
});

test("scope admission refuses a call without an annotation", async () => {
  const session = await sessionFor({ role: ROLE.CODER });
  const admission = session.scopeAdmit({ toolName: "js-worker", input: {} });
  assert.equal(admission.ok, false);
  // The Scope book opens on first use, so the first refusal names the missing
  // annotation; the budget check follows once a reset establishes one.
  assert.equal(admission.code, "SCOPE_ANNOTATION_MISSING");
});

// ---------------------------------------------------------------------------
// Fission: lanes are real detached sessions of the same identity
// ---------------------------------------------------------------------------

// Same rule as `sessionFor`: the session outlives the helper, so it carries its
// own root rather than depending on an environment variable that is restored
// the moment this returns.
const workerSession = async ({ dispatch, api = null, forked = true, role = ROLE.CODER } = {}) =>
  await withRoot(async (root) => {
    const ctx = {
      agent: {
        kind: "sub",
        id: "worker-agent",
        name: role,
        depth: 1,
        ...(forked ? { parentId: "manager-agent" } : {}),
      },
      sessionManager: { getSessionId: () => `sess-worker-${Date.now().toString(36)}` },
      getAsyncJobSnapshot: () => ({ running: [], recent: [] }),
      // A session this adapter drives offers its own control surface
      // (`session-control.mjs`): replacing a fission owner's present aborts its
      // live turn. A bare object here would be refused `NO_NEW_SESSION`.
      newSession: async () => true,
      isStreaming: false,
      abort: async () => {},
    };
    const state = new SessionState({ sessionId: ctx.sessionManager.getSessionId(), role, root });
    return new testSession({ pi: null, api, ctx, role, dispatch, forked, state });
  });

test("fission starts real worker sessions and replaces the owner's present", async () => {
  // Each lane is a real session created through the SDK, and the group fails on
  // no step: the owner's present is replaced by its own control surface.
  const sessions = [fakeSession({ agentId: "lane-0" }), fakeSession({ agentId: "lane-1" })];
  const api = fakeApi(sessions);
  const injected = await workerSession({ api });
  const started = await injected.fission.fission("worker-agent", ["first lane", "second lane"]);
  assert.equal(started.ok, true);
  assert.equal(api.created.length, 2);
  // The group is registered, and its lanes are the ones it created.
  assert.notEqual(injected.fission.groupOf("worker-agent"), null);
  assert.notEqual(injected.lane("worker-agent:2:1#0"), null);
  assert.notEqual(injected.lane("worker-agent:2:1#1"), null);
  // Spec §4.2 step 3: every lane's start text names the group's lane count, so
  // both lanes print the same number — not their own index plus one.
  const laneTexts = sessions.map((session) => session.calls.find(([kind]) => kind === "prompt")[1]);
  assert.deepEqual(
    laneTexts.map((text) => text.slice(0, laneFraming(2).length)),
    [laneFraming(2), laneFraming(2)],
  );

  // Without a session factory the group is refused before any lane exists.
  const bare = await workerSession({});
  assert.deepEqual(await bare.fission.fission("worker-agent", ["a", "b"]), {
    ok: false,
    rejection: "CapacityExceeded",
  });
});

test("fission is refused for a session no fork started", async () => {
  const dispatch = fakeDispatch({
    task: (params) => ({ ...textResult("started"), details: { progress: [{ id: params.name }] } }),
  });
  const session = await workerSession({ dispatch, forked: false });
  const receipt = await session.fission.fission("worker-agent", ["a", "b"]);
  assert.deepEqual(receipt, { ok: false, rejection: "InvalidOrigin" });
  assert.equal(dispatch.calls.length, 0);
});

test("a lane whose session fails to start rolls the whole group back", async () => {
  let created = 0;
  const good = fakeSession({ agentId: "lane-0" });
  const api = {
    created: [],
    pi: {
      async createAgentSession(options) {
        created += 1;
        api.created.push(options);
        if (created === 2) throw new Error("spawn refused");
        return { session: good };
      },
    },
  };
  const session = await workerSession({ api });
  await assert.rejects(() => session.fission.fission("worker-agent", ["a", "b"]), /did not start/);
  // The group is not registered and no lane record survives; the lane that did
  // start was disposed by the abort.
  assert.equal(session.fission.groupOf("worker-agent"), null);
  assert.equal(session.lane("worker-agent:2:1#0"), null);
  assert.deepEqual(good.calls.at(-1), ["dispose"]);
});

test("a program's review uses the session ledger, so the next review sees it", async () => {
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "manager-agent", name: "manager", depth: 0 },
      sessionManager: { getSessionId: () => `sess-prog-review-${Date.now().toString(36)}` },
      getAsyncJobSnapshot: () => ({ running: [], recent: [] }),
    };
    const host = fakeHost(ctx);
    const adapter = new HostAdapter({ pi: host.pi, ctx });
    for (const tool of adapter.tools()) host.pi.registerTool(tool);
    const record = await host.registered.get("js-manager").execute(
      "call-a",
      {
        source: `class Js extends JsProgram {
          async run() { return review([{ key: "k", value: "v" }]); }
        }`,
      },
      undefined,
      undefined,
      ctx,
    );
    assert.equal(record.isError, undefined);
    const receipt = receiptOf(record);
    // The program's return value is the review receipt; the tool result is the
    // program receipt that carries it.
    assert.equal(receipt.value.kind, "recorded");
    // The obligation is in the session's ledger and on disk, not in a
    // throw-away ledger of the program that just ended.
    assert.deepEqual(adapter.session.state.ledger.obligations, [{ key: "k", value: "v" }]);
    const saved = JSON.parse(fs.readFileSync(adapter.session.state.file, "utf8"));
    assert.deepEqual(saved.obligations, [{ key: "k", value: "v" }]);
    // The name is retired once the run ends, so a second call reuses nothing.
    assert.equal(adapter.session.state.programs.live("call-a"), null);
  });
});

// A refusal keeps its machine-readable code and its details. Both travel from
// the refusal site through the error type to the caller; losing either on the
// way is visible here.
test("a refusal keeps its code and details all the way to the caller", async () => {
  const session = await sessionFor({ role: ROLE.MANAGER });
  // An invalid finding is refused by core; the caller sees the code, not a
  // generic failure.
  await assert.rejects(
    () => session.review([{ key: "", value: "x" }]),
    (error) => error.code === "INVALID_ARGUMENT" && /key/.test(error.message),
  );
  // A refusal that carries details keeps them.
  const { invalidArgument } = await import("../core/errors.mjs");
  const error = invalidArgument("bad lane", { index: 3 });
  assert.equal(error.code, "INVALID_ARGUMENT");
  assert.deepEqual(error.details, { index: 3 });
});

// The receipt's `remainingWaitMs` is the run's real budget, fixed at admission
// from the intent. A program that lost its deadline reports 0 forever; this
// checks the value the model actually reads.
test("a program receipt reports the deadline fixed at admission", async () => {
  const session = await sessionFor({ role: ROLE.CODER });
  const built = session.buildSurface({
    toolCallId: "deadline-call",
    surfaceName: "js-worker",
    source: "class Js extends JsProgram { async run() { return 1; } }",
    intent: "x".repeat(200),
  });
  const receipt = built.surface.receipt();
  // 200 bytes at 50 ms each: a real budget, not the hard-coded zero.
  assert.ok(receipt.remainingWaitMs > 0, `remainingWaitMs was ${receipt.remainingWaitMs}`);
  assert.ok(receipt.remainingWaitMs <= 200 * 50);
  session.closeSurface("deadline-call");
});

test("a thrown program is settled as failed and its error is the outcome", async () => {
  const settled = [];
  const { run, program } = runtimeFor(
    `class Js extends JsProgram { async run() { throw new Error("boom"); } }`,
    { settleState: settled },
  );
  const outcome = await run.outcome;
  assert.equal(outcome.status, "failed");
  assert.equal(outcome.error.message, "boom");
  assert.equal(program.state, "failed");
  assert.equal(settled[0][0], "failed");
});

test("a parked program is not settled and answers with its park receipt", async () => {
  const settled = [];
  const { run, program } = runtimeFor(
    `class Js extends JsProgram { async run() { return await this.park({ q: 1 }); } }`,
    { settleState: settled },
  );
  const parked = await run.parked;
  assert.equal(parked.site.reason, "explicit");
  assert.deepEqual(parked.site.payload, { q: 1 });
  // A parked run keeps its name and is never settled while suspended.
  assert.equal(program.state, "parked");
  assert.equal(program.terminal, false);
  assert.deepEqual(settled, []);
});

test("a compile failure is not a run and settles nothing", async () => {
  const settled = [];
  const { run, program } = runtimeFor("const x = 1;", { settleState: settled });
  const outcome = await run.outcome;
  assert.equal(outcome.status, "failed");
  assert.equal(outcome.error.name, "TypeError");
  // No program was ever admitted, so nothing is finished or retired.
  assert.equal(program.state, "running");
  assert.deepEqual(settled, []);
});

test("the pair hint binds the session language from the ladder", async () => {
  const previous = process.env.HERDCHESTRA_LANGUAGE;
  process.env.HERDCHESTRA_LANGUAGE = "SimplifiedChinese";
  try {
    const session = await sessionFor({ role: ROLE.CODER });
    const hint = await session.state.pairHint();
    assert.equal(hint.bound, true);
    assert.equal(hint.language, "SimplifiedChinese");
    const projected = await session.pairHintProject([{ role: "user", content: "hi" }]);
    assert.equal(projected.length, 3);
    assert.equal(projected[2].role, "tool");
  } finally {
    if (previous === undefined) delete process.env.HERDCHESTRA_LANGUAGE;
    else process.env.HERDCHESTRA_LANGUAGE = previous;
  }
});

test("the language ladder reads explicit, host and locale rungs", () => {
  assert.deepEqual(languageLadder({ HERDCHESTRA_LANGUAGE: "English", LANG: "zh_CN.UTF-8" }), {
    explicit: "English",
    host: null,
    locale: "zh_CN.UTF-8",
  });
});

// ---------------------------------------------------------------------------
// Hooks
// ---------------------------------------------------------------------------

test("the tool gate refuses a Manager's replaced tool and a wrong face", async () => {
  const handler = createToolCallHandler({ role: ROLE.MANAGER, scopeAdmit: null });
  const blocked = await handler({ type: "tool_call", toolName: "bash", input: {} });
  assert.equal(blocked.block, true);
  assert.match(blocked.reason, /bash/);
  const face = await handler({ type: "tool_call", toolName: "js-worker", input: { source: "" } });
  assert.equal(face.block, true);
  assert.equal(await handler({ type: "tool_call", toolName: "js-manager", input: { source: "" } }), undefined);
});

test("a worker is not blocked from bash by the gate", async () => {
  const handler = createToolCallHandler({ role: ROLE.CODER, scopeAdmit: null });
  assert.equal(await handler({ type: "tool_call", toolName: "bash", input: {} }), undefined);
});

test("only a Plan session may run the plan face, and a Plan may not run a worker's", async () => {
  // The visible set is not an execution grant: the gate decides (§2.3 pattern).
  const plan = createToolCallHandler({ role: ROLE.PLAN, scopeAdmit: null });
  assert.equal(await plan({ type: "tool_call", toolName: "plan", input: { source: "" } }), undefined);
  const asWorker = await plan({ type: "tool_call", toolName: "js-worker", input: { source: "" } });
  assert.equal(asWorker.block, true);
  const asManager = await plan({ type: "tool_call", toolName: "js-manager", input: { source: "" } });
  assert.equal(asManager.block, true);
  // And nobody else gets the planner's face.
  for (const role of [ROLE.MANAGER, ROLE.CODER, ROLE.DEVOPS]) {
    const other = createToolCallHandler({ role, scopeAdmit: null });
    const refused = await other({ type: "tool_call", toolName: "plan", input: { source: "" } });
    assert.equal(refused?.block, true, role);
  }
});

test("a refused scope annotation blocks the call", async () => {
  const handler = createToolCallHandler({
    role: ROLE.CODER,
    scopeAdmit: async () => ({ ok: false, code: "SCOPE_METOO_ONLY", message: "only meToo" }),
  });
  const blocked = await handler({ type: "tool_call", toolName: "js-worker", input: { scope: { kind: "meToo" } } });
  assert.equal(blocked.block, true);
  assert.match(blocked.reason, /SCOPE_METOO_ONLY/);
});

test("a scope admission that is not a refusal does not block", async () => {
  const admitted = createToolCallHandler({
    role: ROLE.CODER,
    scopeAdmit: async () => ({ ok: true, mustReset: false }),
  });
  assert.equal(await admitted({ type: "tool_call", toolName: "js-worker", input: { scope: { kind: "keepLast" } } }), undefined);
  // A malformed answer is not a refusal either: blocking on it would refuse a
  // call nobody refused.
  const malformed = createToolCallHandler({ role: ROLE.CODER, scopeAdmit: async () => ({ code: "X" }) });
  assert.equal(await malformed({ type: "tool_call", toolName: "js-worker", input: {} }), undefined);
  const nothing = createToolCallHandler({ role: ROLE.CODER, scopeAdmit: async () => null });
  assert.equal(await nothing({ type: "tool_call", toolName: "js-worker", input: {} }), undefined);
});

test("fork provenance comes from the parent id and the worker definition", () => {
  assert.equal(
    forkedByHerdchestra({ kind: "sub", id: "w", name: "coder", depth: 1, parentId: "main" }),
    true,
  );
  // A plain `task` spawn or a `/tan` clone has no parent id recorded as ours.
  assert.equal(forkedByHerdchestra({ kind: "sub", id: "c", name: "sub", depth: 0 }), false);
  // The Manager session is never a fork origin.
  assert.equal(forkedByHerdchestra({ kind: "main", id: "Main", name: "main", depth: 0 }), false);
});

test("the system prompt is the role's composition, with the director only for the Manager", async () => {
  const manager = await createBeforeAgentStartHandler({ role: ROLE.MANAGER })();
  const coder = await createBeforeAgentStartHandler({ role: ROLE.CODER })();
  const devops = await createBeforeAgentStartHandler({ role: ROLE.DEVOPS })();
  assert.equal(manager.systemPrompt.length, 1);
  assert.match(manager.systemPrompt[0], /<vibe-mode>/);
  assert.equal(coder.systemPrompt[0].includes("<vibe-mode>"), false);
  assert.equal(coder.systemPrompt[0], devops.systemPrompt[0]);
});

test("a Plan session gets its own prompt segment, not the projection's refusal", async () => {
  // §5 puts Plan OUTSIDE the three-file projection, and `systemPromptFor`
  // refuses it by design. A handler that called it unconditionally would throw
  // on every Plan request, so the entry would be present and dead.
  const plan = await createBeforeAgentStartHandler({ role: ROLE.PLAN })();
  assert.equal(plan.systemPrompt.length, 1);
  assert.match(plan.systemPrompt[0], /<plan>/);
  assert.equal(plan.systemPrompt[0].includes("<vibe-mode>"), false);
  // It is not the Manager's or a worker's text.
  const coder = await createBeforeAgentStartHandler({ role: ROLE.CODER })();
  assert.notEqual(plan.systemPrompt[0], coder.systemPrompt[0]);
});

test("context folds Scope first and keeps the original messages that survived", async () => {
  const messages = [
    { role: "user", content: "hi" },
    { role: "assistant", content: [{ type: "text", text: "hello" }] },
  ];
  const items = toItems(messages);
  assert.equal(items.length, 2);
  assert.deepEqual(selectKept(messages, [items[0]]), [messages[0]]);
  const handler = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: [items[0]] }),
    pairHintProject: async (current) => current,
  });
  const result = await handler({ messages });
  assert.deepEqual(result.messages, [messages[0]]);
});

test("context leaves the request alone when nothing changes", async () => {
  const messages = [{ role: "user", content: "hi" }];
  const handler = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: toItems(messages) }),
    pairHintProject: async (current) => current,
  });
  assert.equal(await handler({ messages }), undefined);
});

test("a result whose call the host spells with id is not dropped as an orphan", () => {
  // OMP's assistant parts name the call `id` (`{type:"toolCall", id:"call_…"}`,
  // measured on 18.14.2); the hint's own dialect uses `toolCallId`. `toItems`
  // reads both, so the half-pair rule must read both too: reading one spelling
  // left `surviving` empty and dropped the result the projection kept, which
  // made the ADAPTER the author of a shortened copy. `projectionBoundary` then
  // answered `filtered`, no re-base happened, and the next request drifted.
  const messages = [
    { role: "user", content: [{ type: "text", text: "go" }] },
    { role: "assistant", content: [{ type: "toolCall", id: "call_1", name: "js-manager" }] },
    { role: "tool", content: [{ type: "tool-result", toolCallId: "call_1", output: {} }] },
  ];
  assert.deepEqual(selectKept(messages, toItems(messages)), messages);
  // The same pair in the hint's dialect (the pair hint's own shape) stays whole.
  const hint = [
    { role: "user", content: [{ type: "text", text: "go" }] },
    { role: "assistant", content: [{ type: "tool-call", toolCallId: "pair-hint:0", input: {} }] },
    { role: "tool", content: [{ type: "tool-result", toolCallId: "pair-hint:0", output: {} }] },
  ];
  assert.deepEqual(selectKept(hint, toItems(hint)), hint);
  // A result whose call is really gone still leaves: the orphan rule is not
  // weakened, only taught the second spelling.
  const items = toItems(messages);
  assert.deepEqual(selectKept(messages, [items[0], items[2]]), [messages[0]]);
});

test("the relay barrier keeps the operator's input and the succession notice only", async () => {
  // Spec §5.3.2 step 3: the successor does not inherit the review that
  // triggered the relay or the conversation before it. `fenceThrough` counts the
  // messages that were already on screen, so an operator message before it
  // belonged to the retired tenure and goes with it.
  const messages = [
    { role: "user", content: "plan the change" },
    { role: "assistant", content: [{ type: "text", text: "working" }] },
    { role: "user", content: "continue" },
    { role: "assistant", content: [{ type: "text", text: "relayed" }] },
  ];
  const fence = { notice: "you are the successor", successor: 1, fenceThrough: 2 };
  const handler = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: [] }),
    pairHintProject: async (current) => current,
    barrier: () => fence,
  });
  const result = await handler({ messages });
  assert.deepEqual(result.messages, [
    { role: "user", content: [{ type: "text", text: "you are the successor" }], herdchestraSuccession: true },
    messages[2],
    messages[3],
  ]);
  // Without a fence the barrier adds nothing: the request is exactly what Scope
  // alone decides. Scope keeps nothing here, so the request is emptied — and
  // that IS a change, reported as such rather than as "untouched".
  const open = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: [] }),
    pairHintProject: async (current) => current,
    barrier: () => null,
  });
  assert.deepEqual(await open({ messages }), { messages: [] });
  // With a Scope that keeps everything, the same no-fence request is untouched
  // and the handler answers `undefined` (nothing to change).
  const untouched = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: toItems(messages) }),
    pairHintProject: async (current) => current,
    barrier: () => null,
  });
  assert.equal(await untouched({ messages }), undefined);
});

// ------------------------------------------------- context failure isolation
//
// OMP has no request gate: a `context` handler that throws is reported and the
// host sends the copy it built (measured, AGENTS.md §8.1 a). Every case below is
// about what that means: the handler must decide a defensible copy, never leave
// the request silently unprojected, and leave a verdict the tool gate refuses on.

const gateRecorder = () => {
  const seen = { opened: 0, settled: [] };
  return {
    seen,
    gate: {
      open: () => {
        seen.opened += 1;
        return true;
      },
      settle: (failure) => {
        seen.settled.push(failure);
        return true;
      },
    },
  };
};

test("a failed Pair Hint projection keeps the barrier and the Scope projection", async () => {
  // The defect this covers: the Pair Hint threw PREFIX_DRIFT, the throw left the
  // handler, and the host sent the UNPROJECTED request — the successor's
  // conversation came back with it.
  const retired = [
    { role: "user", content: "the retired request" },
    { role: "assistant", content: [{ type: "text", text: "incumbent answer" }] },
  ];
  const operator = { role: "user", content: "carry on" };
  const fence = { notice: "you are the successor", successor: 1, fenceThrough: 0 };
  const { seen, gate } = gateRecorder();
  const handler = createContextHandler({
    scopeProject: async () => {
      throw new Error("Scope must not be consulted under a fence");
    },
    pairHintProject: async () => {
      const error = new Error("a message already shown would change");
      error.code = "PREFIX_DRIFT";
      throw error;
    },
    barrier: () => fence,
    requestGate: gate,
  });
  const result = await handler({ messages: [...retired, operator] });
  assert.equal(seen.opened, 1);
  // The first two entries are the barriered copy: the notice and the operator's
  // message. Nothing of the retired conversation survives.
  assert.deepEqual(result.messages.slice(0, 2), [
    { role: "user", content: [{ type: "text", text: fence.notice }], herdchestraSuccession: true },
    operator,
  ]);
  assert.equal(
    result.messages.some((message) => message === retired[0] || message === retired[1]),
    false,
    "the retired conversation must not be in the copy",
  );
  // The failure is named in the copy, with its own code.
  const notice = result.messages.at(-1);
  assert.equal(notice.role, "user");
  assert.match(notice.content, /pair hint projection/);
  assert.match(notice.content, /PREFIX_DRIFT/);
  // And the request is judged unprojected, so the tool gate refuses.
  assert.deepEqual(seen.settled, [{ stage: "pair hint", code: "PREFIX_DRIFT", detail: "a message already shown would change" }]);
});

test("a failed Scope projection sends only the operator's message, never the unpruned history", async () => {
  // The unpruned request is the whole history Scope was asked to prune. Sending
  // it "with a notice" would ship exactly what retention removes, so the copy
  // falls back to the smallest one the request still needs.
  const messages = [
    { role: "user", content: "old request" },
    { role: "assistant", content: [{ type: "text", text: "old answer" }] },
    { role: "tool", content: [{ type: "tool-result", toolName: "read", toolCallId: "c1", output: { type: "text", value: "x" } }] },
    { role: "user", content: "do the work" },
  ];
  const { seen, gate } = gateRecorder();
  const thrown = createContextHandler({
    scopeProject: async () => {
      const error = new Error("the book is unreadable");
      error.code = "SCOPE_ACCOUNT_MISSING";
      throw error;
    },
    pairHintProject: async (current) => current,
    requestGate: gate,
  });
  const result = await thrown({ messages });
  assert.deepEqual(result.messages[0], messages[3], "only the operator's latest message survives");
  assert.equal(result.messages.length, 2, "that message and one notice");
  assert.equal(result.messages.some((message) => message === messages[0] || message === messages[1] || message === messages[2]), false);
  assert.match(result.messages.at(-1).content, /SCOPE_ACCOUNT_MISSING/);
  assert.match(result.messages.at(-1).content, /NOT sent/);
  assert.deepEqual(seen.settled, [{ stage: "scope", code: "SCOPE_ACCOUNT_MISSING", detail: "the book is unreadable" }]);

  // A refusal that comes back as a value is the same failure.
  const refused = createContextHandler({
    scopeProject: async () => ({ ok: false, code: "SCOPE_FIELD_INVALID", message: "call and result disagree" }),
    pairHintProject: async (current) => current,
    requestGate: gate,
  });
  const kept = await refused({ messages });
  assert.deepEqual(kept.messages[0], messages[3]);
  assert.equal(kept.messages.length, 2);
  assert.match(kept.messages.at(-1).content, /SCOPE_FIELD_INVALID/);
  assert.equal(seen.settled.at(-1).code, "SCOPE_FIELD_INVALID");
});

test("a failed Scope projection over a request with no operator message sends the failure notice alone", async () => {
  // A throw cannot stop a request on OMP (the host sends the copy it built), so
  // this path must not throw: the unprojected history is replaced by the notice
  // and nothing else, and the gate stays shut.
  const messages = [{ role: "assistant", content: [{ type: "text", text: "continuation" }] }];
  const { seen, gate } = gateRecorder();
  const handler = createContextHandler({
    scopeProject: async () => {
      throw new Error("the book is unreadable");
    },
    pairHintProject: async (current) => current,
    requestGate: gate,
  });
  const result = await handler({ messages });
  assert.equal(result.messages.length, 1, "the failure notice is the whole request");
  assert.match(result.messages[0].content, /scope projection/);
  assert.equal(JSON.stringify(result.messages).includes("continuation"), false);
  assert.equal(seen.settled[0].stage, "scope");
});

test("a barrier that cannot run answers the notice and the operator's own message", async () => {
  // The barrier is the security boundary. Its fallback is the smallest copy it
  // can defend — never the unfenced request.
  const messages = [
    { role: "user", content: "old request" },
    { role: "assistant", content: [{ type: "text", text: "old answer" }] },
    { role: "user", content: "the one that matters" },
  ];
  // A fence with no notice cannot build the copy `barrierFor` promises: the
  // notice IS the successor's first message.
  const fence = { notice: undefined, successor: 1, fenceThrough: 0 };
  const { seen, gate } = gateRecorder();
  const handler = createContextHandler({
    scopeProject: async () => {
      throw new Error("Scope must not run when the barrier failed");
    },
    pairHintProject: async (current) => current,
    barrier: () => fence,
    requestGate: gate,
  });
  const result = await handler({ messages });
  // A fence with no usable notice cannot say who the successor is, so the copy
  // is the operator's own latest message — never the retired conversation — and
  // the failure is named after it.
  // The copy keeps the operator's message verbatim and appends ONE notice of
  // failure; nothing of the retired conversation survives. A fence with no
  // notice cannot produce the adapter's own marked notice (`barrierFor` refuses
  // it), so the only notice here is the failure report — inventing a succession
  // notice would put words in the system's mouth.
  assert.equal(result.messages.length, 2, "the operator's message and one notice of failure");
  const [operator, notice] = result.messages;
  assert.equal(operator.role, "user");
  assert.deepEqual(operator.content, "the one that matters");
  // A failure report is NOT the succession copy: it must not carry the marker,
  // or the next request would treat it as the barrier's own message and delete
  // it (the barrier drops marked messages by identity, §5.3.2 step 3).
  assert.equal(
    result.messages.some((message) => message.herdchestraSuccession === true),
    false,
    "no message claims to be the succession notice when there is none",
  );
  assert.match(notice.content, /barrier projection/);
  assert.equal(result.messages.some((message) => message === messages[0] || message === messages[1]), false);
  assert.deepEqual(seen.settled.map((entry) => entry.stage), ["barrier"]);
});

test("a successor's request is sent as the smallest copy when the gate cannot record it", async () => {
  // The host swallows a handler throw AND sends the copy it built, so a throw
  // is not a refusal here: it is the retired conversation going out unprojected.
  // The handler must therefore answer a copy it can defend — the barrier's own
  // smallest one — and hold the refusal in memory, because a gate that cannot be
  // written cannot refuse anything through the file either.
  const retired = [{ role: "user", content: "the retired request" }];
  const operator = { role: "user", content: "carry on" };
  const fence = { notice: "you are the successor", successor: 1, fenceThrough: 0 };
  const unwritable = { open: () => false, settle: () => false, read: () => null };
  const handler = createContextHandler({
    scopeProject: async () => {
      throw new Error("Scope must not decide a copy nobody can judge");
    },
    pairHintProject: async (current) => current,
    barrier: () => fence,
    requestGate: unwritable,
  });
  const result = await handler({ messages: [...retired, operator] });
  // The barrier's copy, the operator's own words, and one notice. Nothing of the
  // retired conversation, and no Scope answer.
  assert.deepEqual(result.messages.slice(0, 2), [
    { role: "user", content: [{ type: "text", text: fence.notice }], herdchestraSuccession: true },
    operator,
  ]);
  assert.equal(result.messages.length, 3);
  assert.match(result.messages.at(-1).content, /REQUEST_GATE_UNWRITABLE/);
  assert.equal(result.messages.some((message) => message === retired[0]), false);
  // The gate now refuses in memory, whatever the durable file says.
  const gate = createToolCallHandler({
    role: ROLE.CODER,
    scopeAdmit: async () => ({ ok: true }),
    requestRefusal: () => handler.requestRefusal(),
  });
  const blocked = await gate({ toolName: "read" });
  assert.equal(blocked.block, true);
  assert.match(blocked.reason, /REQUEST_GATE_UNWRITABLE/);

  // An ordinary session is no exception: an unwritable gate still leaves a copy
  // it can defend — the operator's latest message alone — never the unpruned
  // history that Scope could not judge.
  const history = [
    { role: "user", content: "old request" },
    { role: "assistant", content: [{ type: "text", text: "old answer" }] },
    { role: "user", content: "hi" },
  ];
  const open = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: [] }),
    pairHintProject: async (current) => current,
    requestGate: unwritable,
  });
  const plain = await open({ messages: history });
  assert.deepEqual(plain.messages[0], history[2]);
  assert.equal(plain.messages.length, 2, "the operator's message and one notice");
  assert.equal(plain.messages.some((message) => message === history[0] || message === history[1]), false);
  assert.match(plain.messages.at(-1).content, /REQUEST_GATE_UNWRITABLE/);
});

test("a gate that throws instead of answering fails closed, never open", async () => {
  // `open` and `settle` are I/O. Either can throw instead of answering `false`,
  // and an exception read as "no gate" would open the session on a broken disk.
  const messages = [{ role: "user", content: "hi" }];
  const throwing = {
    open: () => {
      throw new Error("EACCES");
    },
    settle: () => {
      throw new Error("EACCES");
    },
    read: () => {
      throw new Error("EACCES");
    },
  };
  const handler = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: toItems(messages) }),
    pairHintProject: async (current) => current,
    requestGate: throwing,
  });
  const result = await handler({ messages });
  assert.deepEqual(result.messages[0], messages[0], "the operator's own message is kept");
  assert.match(result.messages.at(-1).content, /REQUEST_GATE_UNWRITABLE/);
  const gate = createToolCallHandler({
    role: ROLE.CODER,
    scopeAdmit: async () => ({ ok: true }),
    requestRefusal: () => handler.requestRefusal(),
  });
  const blocked = await gate({ toolName: "read" });
  assert.equal(blocked.block, true);
  assert.match(blocked.reason, /REQUEST_GATE_UNWRITABLE/);

  // A gate whose SETTLE alone fails is the same: the open landed, the verdict
  // did not, so the session keeps refusing until one really does.
  const halfWritten = {
    open: () => true,
    settle: () => false,
    read: () => ({ v: 1, allowed: true }),
  };
  const partial = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: toItems(messages) }),
    pairHintProject: async (current) => current,
    requestGate: halfWritten,
  });
  const sent = await partial({ messages });
  assert.match(sent.messages.at(-1).content, /REQUEST_GATE_UNWRITABLE/);
  const stillBlocked = createToolCallHandler({
    role: ROLE.CODER,
    scopeAdmit: async () => ({ ok: true }),
    requestRefusal: () => partial.requestRefusal(),
  });
  assert.equal((await stillBlocked({ toolName: "read" })).block, true);
});

test("the tool gate refuses when the request gate cannot answer at all", async () => {
  // Every shape that is not a "projected" answer is a refusal. A gate that
  // throws, answers a bare `{}` or a string cannot say the request was
  // projected, and reading that as "open" is exactly the leak the gate exists
  // to stop.
  for (const answer of [
    () => {
      throw new Error("EIO: i/o error");
    },
    () => ({}),
    () => "open",
    () => ({ code: "CONTEXT_PROJECTION_FAILED" }),
  ]) {
    const handler = createToolCallHandler({ role: ROLE.CODER, scopeAdmit: async () => ({ ok: true }), requestRefusal: answer });
    const blocked = await handler({ toolName: "read" });
    assert.equal(blocked.block, true, "an unanswerable gate blocks");
    assert.match(blocked.reason, /^CONTEXT_PROJECTION_FAILED: /);
  }
  // The one answer that opens the gate is an explicit `null`.
  const open = createToolCallHandler({
    role: ROLE.CODER,
    scopeAdmit: async () => ({ ok: true }),
    requestRefusal: () => null,
  });
  assert.equal(await open({ toolName: "read" }), undefined);
});

test("a held refusal outranks a stale on-disk 'allowed' verdict", async () => {
  // The copy and the verdict are decided by different objects: the copy in
  // `createContextHandler`, the verdict read by `tool_call` through
  // `requestRefusal()`. A wiring that let the tool gate read the FILE directly
  // would look correct in every case where the file happens to say "refused",
  // and wrong only here — a file that still says `allowed: true` from the
  // previous request while this request's gate open fails.
  //
  // The held refusal is the newer fact and must win; reading the file instead
  // would let a call run on a request nobody projected.
  const messages = [{ role: "user", content: "carry on" }];
  const stale = {
    open: () => false,
    settle: () => false,
    read: () => ({ v: 1, allowed: true }),
  };
  const handler = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: toItems(messages) }),
    pairHintProject: async (current) => current,
    requestGate: stale,
  });
  const result = await handler({ messages });
  assert.match(result.messages.at(-1).content, /REQUEST_GATE_UNWRITABLE/);
  const gate = createToolCallHandler({
    role: ROLE.CODER,
    scopeAdmit: async () => ({ ok: true }),
    requestRefusal: () => handler.requestRefusal(),
  });
  const blocked = await gate({ toolName: "read" });
  assert.equal(blocked.block, true, "the held refusal outranks the stale file");
  assert.match(blocked.reason, /REQUEST_GATE_UNWRITABLE/);
  // The file's own verdict would have opened it: that is what makes this case a
  // discriminator rather than a restatement.
  assert.equal(stale.read().allowed, true);
});

test("a real verdict clears the held refusal, so a recovered disk is not refused forever", async () => {
  // The other half of the rule in `a held refusal outranks a stale on-disk
  // 'allowed' verdict`: the held verdict wins because it is NEWER than the file,
  // not because it is stricter. A session whose gate write failed once must not
  // stay locked out after the disk recovers — a stale memory is exactly the
  // defect the durable file exists to avoid.
  const messages = [{ role: "user", content: "carry on" }];
  const seen = { opens: 0, verdicts: [] };
  let writable = false;
  // The file's verdict, as the state would hold it: the last request that
  // SETTLED. A failed write leaves it at "nothing settled", which is a refusal.
  let settled = null;
  const flaky = {
    open: () => {
      seen.opens += 1;
      return writable;
    },
    settle: (failure, id) => {
      seen.verdicts.push(failure);
      if (writable) settled = { request: id, settled: true, allowed: failure === null };
      return writable;
    },
    read: () =>
      settled === null || settled.allowed === true
        ? null
        : { code: "CONTEXT_PROJECTION_FAILED", message: "the last settled request failed" },
  };
  const handler = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: toItems(messages) }),
    pairHintProject: async (current) => current,
    requestGate: flaky,
  });
  const gate = createToolCallHandler({
    role: ROLE.CODER,
    scopeAdmit: async () => ({ ok: true }),
    requestId: () => settled?.request ?? null,
    requestRefusal: () => handler.requestRefusal(),
  });

  // Request 1: the disk is read-only, so the refusal is held.
  const failed = await handler({ messages });
  assert.match(failed.messages.at(-1).content, /REQUEST_GATE_UNWRITABLE/);
  assert.equal((await gate({ toolName: "read" })).block, true);

  // Request 2: the disk recovered and the request projects cleanly. The held
  // refusal is cleared and the ordinary decision is the gate's again.
  writable = true;
  assert.equal(await handler({ messages }), undefined, "a clean request changes nothing");
  assert.equal(seen.verdicts.at(-1), null);
  assert.equal(await gate({ toolName: "read" }), undefined, "the recovered session may work again");
});

test("a failed Pair Hint without a fence does not send the unprojected history", async () => {
  // Measured live (2026-11-01, omp 18.14.2, DevOps probe A): a corrupt Pair Hint
  // journal on an unfenced session settled the gate `allowed:false` and still
  // sent fifteen messages of the previous tenure to the provider. Keeping the
  // request "as it stands" is only safe when something already bounded it — a
  // fence, or a Scope projection that really dropped messages. With no fence and
  // no boundary, Scope keeps everything, so the copy must fall back to the
  // operator's latest message, exactly as a failed Scope projection does.
  const messages = [
    { role: "user", content: "PREDECESSOR-SECRET" },
    { role: "assistant", content: [{ type: "text", text: "OLDCODEX" }] },
    { role: "user", content: "carry on" },
  ];
  const { seen, gate } = gateRecorder();
  const handler = createContextHandler({
    // A Scope book with no settled boundary keeps everything: the whole request
    // comes back unchanged.
    scopeProject: async ({ items }) => ({ ok: true, messages: items }),
    pairHintProject: async () => {
      const error = new Error("the journal is unreadable");
      error.code = "FACT_CORRUPT";
      throw error;
    },
    barrier: () => null,
    requestGate: gate,
  });
  const result = await handler({ messages });
  assert.equal(JSON.stringify(result.messages).includes("PREDECESSOR-SECRET"), false, "no predecessor text");
  assert.equal(JSON.stringify(result.messages).includes("OLDCODEX"), false);
  assert.deepEqual(result.messages[0], messages[2], "only the operator's latest message survives");
  assert.match(result.messages.at(-1).content, /pair hint projection/);
  assert.match(result.messages.at(-1).content, /FACT_CORRUPT/);
  assert.deepEqual(seen.settled, [{ stage: "pair hint", code: "FACT_CORRUPT", detail: "the journal is unreadable" }]);

  // When Scope DID reduce the request, that answer is the boundary and it is
  // kept: the pair hint failing must not throw away a retention decision that
  // actually happened.
  const reduced = createContextHandler({
    scopeProject: async ({ items }) => ({ ok: true, messages: items.slice(-1) }),
    pairHintProject: async () => {
      throw new Error("the journal is unreadable");
    },
    barrier: () => null,
    requestGate: gateRecorder().gate,
  });
  const kept = await reduced({ messages });
  assert.deepEqual(kept.messages[0], messages[2]);
  assert.equal(JSON.stringify(kept.messages).includes("PREDECESSOR-SECRET"), false);

  // The unbounded-by-construction case: no `scopeProject` at all, so nothing
  // failed (`failure` is null) and the pair hint runs over the raw host copy.
  // "Scope did not fail" is not "something bounded this request".
  const unscoped = createContextHandler({
    pairHintProject: async () => {
      const error = new Error("the journal is unreadable");
      error.code = "FACT_CORRUPT";
      throw error;
    },
    barrier: () => null,
    requestGate: gateRecorder().gate,
  });
  const bare = await unscoped({ messages });
  assert.equal(JSON.stringify(bare.messages).includes("PREDECESSOR-SECRET"), false);
  assert.deepEqual(bare.messages[0], messages[2], "only the operator's latest message survives");
  assert.match(bare.messages.at(-1).content, /FACT_CORRUPT/);
});

test("a failed Pair Hint keeps the barriered copy, which a fence already bounded", async () => {
  // The fenced half of the same rule: there the barrier is the boundary, so the
  // barriered copy (the notice and the operator's own message) may be kept as it
  // stands — it already carries nothing of the retired tenure.
  const messages = [
    { role: "user", content: "PREDECESSOR-SECRET" },
    { role: "assistant", content: [{ type: "text", text: "OLDCODEX" }] },
    { role: "user", content: "carry on" },
  ];
  const fence = { notice: "you are the successor", successor: 1, fenceThrough: 2 };
  const { gate } = gateRecorder();
  const handler = createContextHandler({
    scopeProject: async () => {
      throw new Error("Scope must not run under a fence");
    },
    pairHintProject: async () => {
      throw new Error("the journal is unreadable");
    },
    barrier: () => fence,
    requestGate: gate,
  });
  const result = await handler({ messages });
  assert.deepEqual(result.messages[0], {
    role: "user",
    content: [{ type: "text", text: fence.notice }],
    herdchestraSuccession: true,
  });
  assert.deepEqual(result.messages[1], messages[2]);
  assert.equal(JSON.stringify(result.messages).includes("PREDECESSOR-SECRET"), false);
  assert.match(result.messages.at(-1).content, /pair hint projection/);
});

test("a Scope failure does not let the Pair Hint redefine the copy as retained", async () => {
  // After a Scope failure `current` is already the handler's smallest copy.
  // Running the pair hint over it and then judging the result by length would
  // read "Scope reduced this" from a reduction Scope never made.
  const messages = [
    { role: "user", content: "old request" },
    { role: "assistant", content: [{ type: "text", text: "old answer" }] },
    { role: "user", content: "do the work" },
  ];
  const { seen, gate } = gateRecorder();
  let pairHintCalls = 0;
  const handler = createContextHandler({
    scopeProject: async () => {
      throw new Error("the book is unreadable");
    },
    pairHintProject: async (current) => {
      pairHintCalls += 1;
      return current;
    },
    requestGate: gate,
  });
  const result = await handler({ messages });
  assert.equal(pairHintCalls, 0, "the pair is not placed into a copy Scope did not produce");
  assert.deepEqual(result.messages[0], messages[2]);
  assert.equal(result.messages.length, 2, "the operator's message and the Scope failure notice");
  assert.match(result.messages.at(-1).content, /scope projection/);
  assert.deepEqual(seen.settled.map((entry) => entry.stage), ["scope"]);
});

test("a fallback copy never takes the incumbent's question as the operator's", async () => {
  // `fenceThrough` counts what was already on screen under the incumbent. A user
  // message before it belongs to the retired tenure; handing it to the successor
  // would be the leak the barrier exists to prevent.
  const messages = [
    { role: "user", content: "the incumbent's question" },
    { role: "assistant", content: [{ type: "text", text: "the incumbent's answer" }] },
    { role: "user", content: "the successor's question" },
  ];
  // A fence whose notice is unusable sends the barrier down its fallback path.
  const fence = { notice: "", successor: 1, fenceThrough: 2 };
  const { gate } = gateRecorder();
  const handler = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: [] }),
    pairHintProject: async (current) => current,
    barrier: () => fence,
    requestGate: gate,
  });
  const result = await handler({ messages });
  assert.deepEqual(result.messages[0], messages[2], "only the successor's own question");
  assert.equal(
    JSON.stringify(result.messages).includes("the incumbent's question"),
    false,
    "the incumbent's question is never in the copy",
  );
  // When every user message predates the fence, there is nothing the successor
  // may be shown: the copy is the failure notice alone.
  const allRetired = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: [] }),
    pairHintProject: async (current) => current,
    barrier: () => ({ ...fence, fenceThrough: 3 }),
    requestGate: gate,
  });
  const empty = await allRetired({ messages });
  assert.equal(empty.messages.length, 1);
  assert.match(empty.messages[0].content, /barrier projection/);
});

test("a request that projects completely opens the gate and adds nothing", async () => {
  const messages = [{ role: "user", content: "hi" }];
  const { seen, gate } = gateRecorder();
  const handler = createContextHandler({
    scopeProject: async () => ({ ok: true, messages: toItems(messages) }),
    pairHintProject: async (current) => current,
    requestGate: gate,
  });
  assert.equal(await handler({ messages }), undefined);
  assert.equal(seen.opened, 1);
  assert.deepEqual(seen.settled, [null]);
});

test("the tool gate refuses every call while the last request was not projected", async () => {
  const refused = createToolCallHandler({
    role: ROLE.CODER,
    scopeAdmit: async () => ({ ok: true }),
    requestRefusal: () => ({
      code: "CONTEXT_PROJECTION_FAILED",
      message: "the pair hint projection of the last request failed (PREFIX_DRIFT: drift)",
    }),
  });
  const blocked = await refused({ toolName: "read" });
  assert.equal(blocked.block, true);
  assert.match(blocked.reason, /^CONTEXT_PROJECTION_FAILED: /);
  assert.match(blocked.reason, /PREFIX_DRIFT/);
  // An open gate leaves the ordinary decision to the rest of the handler.
  const open = createToolCallHandler({
    role: ROLE.CODER,
    scopeAdmit: async () => ({ ok: true }),
    requestRefusal: () => null,
  });
  assert.equal(await open({ toolName: "read" }), undefined);
});

// The live defect (2026-11-01, omp 18.14.2, resumed successor): the barrier put
// the pair after two messages, the host stored that request, and the successor's
// SECOND request — rebuilt from the host's history, which never holds our pair —
// was compared against a chain the barrier had moved under it. `PREFIX_DRIFT`
// was thrown out of the context handler, OMP swallowed it, and the request went
// out unprojected. These cases drive the real `SessionState.pairHintProject`
// over a real journal, so they fail if the chain and the placement ever disagree
// again.
test("a resumed successor's second request extends the first instead of drifting", async () => {
  await withRoot(async (root) => {
    const sessionId = "successor-chain-1";
    const state = new SessionState({ sessionId, role: ROLE.MANAGER, root });
    const fence = { notice: "you are the successor", successor: 1, fenceThrough: 0 };
    const operator = { role: "user", content: "carry on with the work" };

    // Request 1: the host's history still holds the retired conversation; the
    // barrier cuts it, and the Pair Hint re-anchors there.
    const hostHistory = [
      { role: "user", content: "the retired request" },
      { role: "assistant", content: [{ type: "text", text: "incumbent answer" }] },
      operator,
    ];
    const first = await state.pairHintProject(barrierFor(hostHistory, fence), { barriered: true });
    assert.equal(first.length, 4, "notice, the operator's message, the pair");
    assert.deepEqual(first[0].content, [{ type: "text", text: fence.notice }]);

    // The host stores the request it was sent (without our pair) and then the
    // successor's answer. It stores what the barrier produced, so the fixture
    // takes that output rather than rebuilding a shape of its own.
    const barriered = barrierFor([operator], fence);
    const stored = [...barriered, { role: "assistant", content: [{ type: "text", text: "doing it" }] }];
    const second = await state.pairHintProject(stored, { barriered: false });
    const hint = await state.pairHint();
    // The chain's rule is about the CONVERSATION, not the pair: the pair is
    // presentation and its own placement fact (`input.position`/`after`) changes
    // between requests by design. `PairHint#project` therefore compares the two
    // copies with this epoch's pair removed (`withoutInjection`), and that is
    // the comparison this test has to make — comparing the raw copies compares
    // the pair against whatever message now stands at its old index.
    assert.doesNotThrow(() =>
      hint.injector.assertPrefix({
        previous: hint.injector.withoutInjection(first),
        next: hint.injector.withoutInjection(second),
      }),
    );
    // The pair is placed once, where the chain decided (after the copy's own
    // messages, the answer included) — never a second copy of itself.
    assert.equal(second.length, stored.length + 2);
    assert.equal(second.filter((message) => message.role === "tool").length, 1);
    // Nothing of the retired conversation came back.
    assert.equal(second.some((message) => message.content === "the retired request"), false);
  });
});

test("a successor's chain survives a restart in the middle of its tenure", async () => {
  await withRoot(async (root) => {
    const sessionId = "successor-chain-2";
    const fence = { notice: "you are the successor", successor: 1, fenceThrough: 0 };
    const operator = { role: "user", content: "carry on" };
    const firstState = new SessionState({ sessionId, role: ROLE.MANAGER, root });
    const first = await firstState.pairHintProject(barrierFor([operator], fence), { barriered: true });

    // A second process resumes the successor: the hint is rebuilt from the
    // journal, and the chain comes from the `presented` record.
    const secondState = new SessionState({ sessionId, role: ROLE.MANAGER, root });
    const stored = [
      ...barrierFor([operator], fence),
      { role: "assistant", content: [{ type: "text", text: "working" }] },
    ];
    const second = await secondState.pairHintProject(stored, { barriered: false });
    const hint = await secondState.pairHint();
    assert.doesNotThrow(() => hint.injector.assertPrefix({ previous: first, next: second }));
    assert.equal(second.length, stored.length + 2);
  });
});

test("any successor history the host can grow never drifts from the anchored copy", async () => {
  // The property the defect violated: whatever the host stores between two
  // requests, the next request extends the previous one inside the epoch.
  await fc.assert(
    fc.asyncProperty(
      fc.array(
        fc.record({
          role: fc.constantFrom("user", "assistant"),
          text: fc.stringMatching(/^[a-z ]{1,12}$/),
        }),
        { minLength: 0, maxLength: 6 },
      ),
      async (grown) => {
        await withRoot(async (root) => {
          const state = new SessionState({ sessionId: "successor-prop", role: ROLE.MANAGER, root });
          const fence = { notice: "you are the successor", successor: 1, fenceThrough: 0 };
          const operator = { role: "user", content: "start" };
          const first = await state.pairHintProject(barrierFor([operator], fence), { barriered: true });
          const stored = [
            ...barrierFor([operator], fence),
            ...grown.map((message) => ({ role: message.role, content: [{ type: "text", text: message.text }] })),
          ];
          const next = await state.pairHintProject(stored, { barriered: false });
          const hint = await state.pairHint();
          assert.doesNotThrow(() => hint.injector.assertPrefix({ previous: first, next }));
        });
      },
    ),
    { ...fcRuns(12) },
  );
});

// The live defect (2026-11-01, omp 18.14.2, `--extension … --no-extensions`,
// first request of a session): every request refused with `PREFIX_DRIFT` and
// every tool blocked by the durable gate, in a conversation nobody had changed.
//
// What the hint journal recorded (`.Herdchestra/<session>.jsonl`, `refused`
// records at index 1): the presented copy held the operator message plus the
// turn's assistant message as the provider stream was still producing it —
// `stopReason:"stop"`, `usage` all zero, no `responseId` — and the next request
// carried the same assistant message finalised (`usage` filled in,
// `responseId`, `toolCall` parts with parsed `arguments` and no `streamIndex`).
// OMP rewrites the message it is answering as the stream settles, and its
// journal keeps the streaming copy of the request the hook saw. The chain read
// two shapes of one message and called it drift.
test("a message OMP finalises after streaming is the same message for the chain", async () => {
  await withRoot(async (root) => {
    const state = new SessionState({ sessionId: "streamed-turn-1", role: ROLE.MANAGER, root });
    const operator = { role: "user", content: [{ type: "text", text: "report progress" }] };
    // As the hook received it: mid-stream, with the call still partial.
    const streaming = {
      role: "assistant",
      content: [
        { type: "text", text: "I will check the tree." },
        {
          type: "toolCall",
          id: "call_1",
          name: "fork",
          arguments: { name: "scope" },
          partialArgs: '{"name":"scope"}',
          streamIndex: 1,
        },
      ],
      api: "openai-completions",
      provider: "kw92.cyou",
      model: "lazy/default",
      usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0 },
      stopReason: "stop",
      timestamp: 1793500514165,
    };
    // As the host stores it once the stream is over.
    const finalised = {
      ...streaming,
      content: [{ type: "text", text: "I will check the tree." }, { type: "toolCall", id: "call_1", name: "fork", arguments: { name: "scope" } }],
      usage: { input: 3642, output: 172, cacheRead: 0, cacheWrite: 0, totalTokens: 3814 },
      responseId: "chatcmpl-e0e3d4a0",
      duration: 4402.35,
      ttft: 4377.49,
      completedAt: 1793500518570,
      contextSnapshot: { promptTokens: 3642, nonMessageTokens: 4217, toolSchemaTokens: 2329, compactionEpoch: 0 },
    };
    const first = await state.pairHintProject([operator, streaming], { barriered: false });
    // The host stores the request it sent, then the result of the call.
    const stored = [
      operator,
      finalised,
      { role: "toolResult", toolCallId: "call_1", toolName: "fork", content: [{ type: "text", text: "done" }], isError: false },
    ];
    const second = await state.pairHintProject(stored, { barriered: false });
    const hint = await state.pairHint();
    // The chain's rule is about the CONVERSATION, not the pair: the pair is
    // presentation and its own placement fact (`input.position`/`after`) changes
    // between requests by design. `PairHint#project` therefore compares the two
    // copies with this epoch's pair removed (`withoutInjection`), and that is
    // the comparison this test has to make — comparing the raw copies compares
    // the pair against whatever message now stands at its old index.
    assert.doesNotThrow(() =>
      hint.injector.assertPrefix({
        previous: hint.injector.withoutInjection(first),
        next: hint.injector.withoutInjection(second),
      }),
    );
    // Nothing was refused, so the journal records no refusal for this session.
    assert.equal(hint.refusal, null);
  });
});

// The property behind the live defect: one host message, two shapes. Whatever
// OMP recomputes for a message it is still finishing (usage, responseId,
// duration, ttft, completedAt, contextSnapshot, partialArgs, streamIndex), the
// chain must read the same bytes before and after — otherwise every request of
// a session drifts from the one before it and the durable gate locks the
// session out of its own tools.
test("a message the host finalises keeps one chain shape, whatever it recomputes", () => {
  const part = fc.oneof(
    fc.record({ type: fc.constant("text"), text: fc.string() }),
    fc.record({
      type: fc.constant("toolCall"),
      id: fc.string({ minLength: 1 }),
      name: fc.string({ minLength: 1 }),
      arguments: fc.dictionary(fc.string({ minLength: 1 }), fc.string()),
    }),
  );
  const base = fc.record({
    role: fc.constantFrom("user", "assistant", "developer", "toolResult"),
    content: fc.array(part, { maxLength: 3 }),
  });
  const provider = fc.record({
    responseId: fc.string(),
    duration: fc.double(),
    ttft: fc.double(),
    completedAt: fc.integer(),
    contextSnapshot: fc.record({ promptTokens: fc.nat(), nonMessageTokens: fc.nat(), toolSchemaTokens: fc.nat(), compactionEpoch: fc.nat() }),
    timestamp: fc.integer(),
    usage: fc.record({ input: fc.nat(), output: fc.nat(), cacheRead: fc.nat(), cacheWrite: fc.nat(), totalTokens: fc.nat() }),
  });
  fc.assert(
    fc.property(fc.tuple(base, provider), ([message, metadata]) => {
      // While the stream is open: the call is still partial.
      const streamed = { ...message, ...metadata, partialArgs: "{}", streamIndex: 0 };
      // After it settles: the same call, arguments parsed, nothing partial.
      const finalised = {
        ...message,
        ...metadata,
        usage: { ...metadata.usage, input: metadata.usage.input + 1 },
        content: message.content.map((part) =>
          part.type === "toolCall" ? { type: "toolCall", id: part.id, name: part.name, arguments: part.arguments } : part,
        ),
      };
      // The chain compares canonical content (core's `canonical.mjs`): role and
      // ordered semantic parts, with every provider/bookkeeping key ignored by
      // construction. Asserting that key, not the adapter's own JSON, is what
      // makes this a statement about the CONTRACT rather than about whichever
      // blacklist the adapter happened to carry that week.
      assert.equal(canonicalKey(toPairHintMessages([finalised])[0]), canonicalKey(toPairHintMessages([streamed])[0]));
    }),
    { ...fcRuns(200) },
  );
});

// The live defect of 2026-11-01 (omp 18.14.2, session 01a19547): OMP fires an
// ephemeral title request through the SAME `context` hook 4 ms before the main
// turn's tool results settle. Its copy is the conversation plus the side-channel
// reminder and the title prompt, so it EXTENDS the last real request, and Pair
// Hint made it the chain's anchor. The real request then drifted at index 1 and
// the durable gate refused every tool call.
// The host's own shape, as `#buildEphemeralSnapshot` writes it. The per-call
// symbol it also sets never reaches us: `emitContext` structuredClones the copy
// first, and structuredClone drops symbols (verified).
const sideTurnCopy = (real, title) => [
  ...real,
  { role: "developer", content: [{ type: "text", text: "<system-reminder>\nEphemeral side-channel turn</system-reminder>" }], attribution: "agent" },
  { role: "user", content: [{ type: "text", text: title }], attribution: "agent" },
];

test("a side-channel turn never becomes the chain's anchor", async () => {
  await withRoot(async (root) => {
    const state = new SessionState({ sessionId: "side-turn-1", role: ROLE.MANAGER, root });
    const operator = { role: "user", content: [{ type: "text", text: "report progress" }] };
    const first = await state.pairHintProject([operator], { barriered: false });
    const hint = await state.pairHint();
    const anchored = chainOf(hint);

    // The title turn: the conversation plus the host's marked side messages.
    const streaming = { role: "assistant", content: [{ type: "text", text: "I will dispatch workers " }] };
    const side = sideTurnCopy([operator, streaming], "<title-request>\nSession UI request, not from the user.</title-request>");
    const projected = await state.pairHintProject(side, { barriered: false });
    // It carries the pair (the host's cache wants it), and the chain did not
    // move: an ephemeral request records nothing.
    assert.equal(projected.length, side.length + 2, "the pair went in");
    // The pair stands at the chain's recorded placement (after the operator's
    // message), not at the end of the side copy: that is what keeps the host's
    // cached prefix identical.
    assert.equal(projected[1].role, "assistant", "at the chain's own placement");
    assert.deepEqual(projected[0], side[0], "the operator's message is untouched");
    assert.equal(sameChain(chainOf(hint), anchored), true, "the side turn is not the chain");

    // 4 ms later: the real request with the turn's calls and their results.
    const real = [
      operator,
      {
        role: "assistant",
        content: [
          { type: "text", text: "I will dispatch workers " },
          { type: "tool-call", toolName: "fork", toolCallId: "c1", input: {} },
        ],
      },
      { role: "tool", content: [{ type: "tool-result", toolName: "fork", toolCallId: "c1", output: { type: "text", value: "ok" } }] },
    ];
    const next = await state.pairHintProject(real, { barriered: false });
    assert.doesNotThrow(() => hint.injector.assertPrefix({ previous: first, next }));
    assert.equal(hint.refusal, null, "the real request was never refused");
    // One synthetic pair (its result is a `tool` message too) and one real
    // result: the pair was placed once, into the conversation's own chain.
    assert.equal(next.filter((message) => message.role === "tool").length, 2);
  });
});

test("any side-channel copy the host can build leaves the chain untouched", async () => {
  // The property the live defect violated: whatever the host appends for its own
  // UI request, the chain the conversation continues is the one it presented.
  await fc.assert(
    fc.asyncProperty(
      fc.array(fc.record({ role: fc.constantFrom("user", "assistant"), text: fc.stringMatching(/^[a-z ]{1,12}$/) }), { maxLength: 5 }),
      fc.string({ maxLength: 20 }),
      async (grown, title) => {
        await withRoot(async (root) => {
          const state = new SessionState({ sessionId: "side-turn-prop", role: ROLE.MANAGER, root });
          const operator = { role: "user", content: [{ type: "text", text: "start" }] };
          const first = await state.pairHintProject([operator], { barriered: false });
          const hint = await state.pairHint();
          const anchored = chainOf(hint);
          const conversation = [operator, ...grown.map((message) => ({ role: message.role, content: [{ type: "text", text: message.text }] }))];
          const side = sideTurnCopy(conversation, title);
          const projectedSide = await state.pairHintProject(side, { barriered: false });
          assert.equal(projectedSide.length, side.length + 2, "the pair went in");
          assert.equal(sameChain(chainOf(hint), anchored), true, "an ephemeral request never becomes the chain");
          const next = await state.pairHintProject(conversation, { barriered: false });
          assert.doesNotThrow(() => hint.injector.assertPrefix({ previous: first, next }));
          assert.equal(hint.refusal, null);
        });
      },
    ),
    { ...fcRuns(25) },
  );
});

test("a failed side-channel turn neither cuts its copy nor closes the request gate", async () => {
  const side = [
    { role: "user", content: [{ type: "text", text: "go" }], attribution: "user" },
    { role: "developer", content: [{ type: "text", text: "side reminder" }], attribution: "agent" },
    { role: "user", content: [{ type: "text", text: "<title-request>name this session</title-request>" }], attribution: "agent" },
  ];
  const settlements = [];
  const handler = createContextHandler({
    scopeProject: null,
    pairHintProject: async () => {
      throw new Error("PREFIX_DRIFT: a message already shown would change");
    },
    requestGate: {
      open: () => true,
      settle: (failure) => {
        settlements.push(failure);
        return true;
      },
      read: () => null,
    },
    sideTurn: (messages) => isSideChannelRequest(messages),
  });
  const result = await handler({ messages: side });
  // The copy is the host's own: the side request still gets answered.
  assert.deepEqual(result.messages.slice(0, side.length), side);
  // And the gate never heard about it: no tool call can be refused for a UI request.
  assert.deepEqual(settlements, []);
  assert.equal(handler.requestRefusal(), null);
});

test("the request gate is durable, refuses until a request settles, and opens when one does", async () => {
  await withRoot(async (root) => {
    const state = new SessionState({ sessionId: "gate-1", role: ROLE.MANAGER, root });
    // Nothing has projected, so no call can be attributed to a request: fail
    // closed, and say why.
    assert.match(state.requestRefusal().message, /CONTEXT_PROJECTION_UNKEYED/);
    // A request in flight writes NOTHING and is not a verdict: the call could
    // only have come from a response the request has not produced yet.
    state.openContextRequest();
    const pending = state.requestRefusal();
    assert.equal(pending.code, "CONTEXT_PROJECTION_FAILED");
    assert.match(pending.message, /CONTEXT_PROJECTION_UNKEYED/);
    // The settle is where the verdict lands, and only because it changed.
    assert.equal(
      state.settleContextRequest({ stage: "scope", code: "SCOPE_FIELD_INVALID", detail: "call and result disagree" }).changed,
      true,
    );
    const refused = state.requestRefusal();
    assert.match(refused.message, /scope projection/);
    assert.match(refused.message, /SCOPE_FIELD_INVALID/);
    // The verdict survives a restart: a new state object reads the same file.
    const restarted = new SessionState({ sessionId: "gate-1", role: ROLE.MANAGER, root });
    assert.match(restarted.requestRefusal().message, /SCOPE_FIELD_INVALID/);
    // A later request that is still projecting does not hide the settled
    // verdict of the one before it: its response's calls are still refused.
    restarted.openContextRequest();
    assert.match(restarted.requestRefusal().message, /SCOPE_FIELD_INVALID/, "the last settled verdict answers while a new request projects");
    assert.equal(restarted.settleContextRequest(null).changed, true);
    assert.equal(restarted.requestRefusal(), null);
    // A file that exists but cannot be believed is a refusal, not "no verdict":
    // a torn write, a hand-edited file, and a directory where the file belongs
    // are all answered by the same fail-closed verdict.
    const gateFile = `${stateDir(root)}/gate-1.request.json`;
    fs.writeFileSync(gateFile, "{ not json");
    const torn = new SessionState({ sessionId: "gate-1", role: ROLE.MANAGER, root });
    assert.equal(torn.requestRefusal().code, "CONTEXT_PROJECTION_FAILED");
    assert.match(torn.requestRefusal().message, /REQUEST_GATE_UNREADABLE/);
    fs.rmSync(gateFile, { force: true });
    fs.mkdirSync(gateFile);
    const unreadable = new SessionState({ sessionId: "gate-1", role: ROLE.MANAGER, root });
    assert.match(unreadable.requestRefusal().message, /REQUEST_GATE_UNREADABLE/);
    // And the tool gate refuses on it.
    const gate = createToolCallHandler({
      role: ROLE.CODER,
      scopeAdmit: async () => ({ ok: true }),
      requestRefusal: () => unreadable.requestRefusal(),
    });
    assert.equal((await gate({ toolName: "read" })).block, true);
  });
});

// The gate must be writable in a workspace it has never seen.
//
// Measured on the real host (omp 18.14.2, `/tmp/probe1`): the FIRST request of a
// fresh root answered `REQUEST_GATE_UNWRITABLE`, so every tool of that session
// was refused before any projection had a chance to run. The cause was in
// `files.mjs` `writeJson`, which the gate writes through: the `mkdir` that
// creates `.Herdchestra` sat OUTSIDE its try, so a root whose state directory
// could not be created threw past the answer the caller reads, and the write of
// the gate — the one file a request path must be able to write — had no honest
// failure to report. A failed `mkdir` is an ANSWER (`{ ok: false, code }`), and
// the first write into a root is the ordinary case, not a failure.
test("the request gate is writable in a root that has no state directory yet", async () => {
  await withRoot(async (root) => {
    // A fresh root: no `.Herdchestra` exists, exactly as on the first request.
    assert.equal(fs.existsSync(stateDir(root)), false);
    const state = new SessionState({ sessionId: "gate-fresh", role: ROLE.MANAGER, root });
    // The open writes nothing: no verdict has been decided yet, and an absent
    // file already refuses.
    assert.equal(state.openContextRequest(), true);
    const gateFile = `${stateDir(root)}/gate-fresh.request.json`;
    assert.equal(fs.existsSync(gateFile), false, "an open request writes no verdict");
    // The settle that follows a clean projection is the first write, and it
    // lands in a root whose state directory did not exist.
    assert.equal(state.settleContextRequest(null).written, true);
    const written = JSON.parse(fs.readFileSync(gateFile, "utf8"));
    assert.equal(written.allowed, true);
    assert.equal(written.settled, true);
    assert.equal(state.requestRefusal(), null);

    // The same write where the directory CANNOT be created answers, never
    // throws: a plain file where `.Herdchestra` belongs is the shape a
    // workspace can really have, and it must not reach a hook as a stack trace.
    // The write is asked directly, because a state object cannot even be
    // constructed on such a root (`ensureDir` runs in its constructor).
    const blocked = tmpRoot();
    fs.writeFileSync(stateDir(blocked), "not a directory");
    const target = `${stateDir(blocked)}/gate-blocked.request.json`;
    const answer = writeJson(target, { v: 1, allowed: false });
    assert.equal(answer.ok, false, "an impossible gate write is answered, not thrown");
    assert.ok(typeof answer.code === "string" && answer.code.length > 0, "the answer names the failure");
    // And the refusal that follows is the fail-closed one, not "no verdict":
    // a session with no turn and no file cannot prove any call was projected.
    const verdict = new SessionState({ sessionId: "gate-blocked", role: ROLE.MANAGER, root: tmpRoot() });
    assert.match(verdict.requestRefusal().message, /CONTEXT_PROJECTION_UNKEYED/);
  });
});

test("a lane can be built without its first turn, and is then not in the roster", async () => {
  // A relay needs a successor that exists and has an identity BEFORE the record
  // that names it is committed. `verboseIntent: null` is that shape: the session is
  // created, wired, authorized and narrowed, but produces no turn — so it is not
  // a live tenure and `join`/`steer` must not see it.
  await withRoot(async () => {
    const session = fakeSession({ agentId: "inert-1" });
    const api = fakeApi([session]);
    const lanes = trackedLaneTable(new LaneTable({ api }));
    const started = await lanes.start({ name: "relay-1", role: ROLE.MANAGER, verboseIntent: null });
    assert.equal(started.ok, true, JSON.stringify(started));
    assert.equal(started.inert, true);
    // No turn was requested.
    assert.equal(session.calls.filter(([kind]) => kind === "prompt").length, 0);
    // Not in the roster: nothing can collect a lane that never worked.
    assert.deepEqual(lanes.names(), []);
    assert.equal(lanes.pending().length, 0);
    // No submission promise either: awaiting one nobody resolves would hang.
    assert.equal(started.lane.submitted, undefined);
    // And it can be discarded cleanly, because it never produced a turn.
    await started.lane.dispose();
    assert.equal(session.calls.some(([kind]) => kind === "dispose"), true);
  });
});

// ---------------------------------------------------------------------------
// Tool results and tools
// ---------------------------------------------------------------------------

test("a failed receipt becomes an isError tool result", () => {
  const ok = receiptResult({ ok: true, started: true });
  assert.equal(ok.isError, undefined);
  const failed = receiptResult({ ok: false, rejection: "TooFewLanes" });
  assert.equal(failed.isError, true);
  assert.match(failed.content[0].text, /TooFewLanes/);
  assert.equal(errorResult("x").isError, true);
});

// The record `programTool` expects from a surface builder: the surface, its
// program, and the session's park table. `members` are what the model calls.
const toolSurface = (members, name = "p-tool") => {
  const program = new ProgramState(name);
  const parks = new ParkTable();
  const surface = {
    program,
    settle: async (state, result) => {
      if (!program.terminal) program.finish(state, result);
      return program;
    },
    receipt: (extra = {}) => ({ name, state: program.state, ...extra }),
    finalReceipt: () => ({ name, state: program.state, result: program.result }),
    surface: () => members,
  };
  return { surface, program, parks };
};

test("a program tool runs the source against the surface it is given", async () => {
  let seen = null;
  const tool = programTool({
    surfaceName: "js-worker",
    label: "JS Program (Worker)",
    description: "test",
    surfaceFor: (request) => {
      seen = request;
      return toolSurface({ cat: async (p) => `read ${p}` });
    },
  });
  assert.equal(tool.name, "js-worker");
  assert.equal(tool.approval, "exec");
  const result = await tool.execute(
    "call-1",
    { source: "class Js extends JsProgram { async run() { return await this.cat('a.txt'); } }" },
    undefined,
    undefined,
    { agent: { kind: "sub", name: "coder" } },
  );
  assert.equal(result.isError, undefined);
  const receipt = receiptOf(result);
  // The answer is the program's receipt; the body's return is `value`.
  assert.equal(receipt.value, "read a.txt");
  assert.equal(receipt.state, "completed");
  assert.equal(seen.toolCallId, "call-1");
});

test("a program tool reports a missing member as an error result, not a crash", async () => {
  const tool = programTool({
    surfaceName: "js-worker",
    label: "JS Program (Worker)",
    description: "test",
    surfaceFor: () => toolSurface({}),
  });
  const result = await tool.execute(
    "call-2",
    { source: "class Js extends JsProgram { async run() { return this.write('a','b'); } }" },
  );
  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /TypeError/);
});

test("an empty program source is refused before any surface is built", async () => {
  let built = false;
  const tool = programTool({
    surfaceName: "js-worker",
    label: "JS Program (Worker)",
    description: "test",
    surfaceFor: () => {
      built = true;
      return {};
    },
  });
  const result = await tool.execute("call-3", { source: "   " });
  assert.equal(result.isError, true);
  assert.equal(built, false);
});

test("the adapter's state directory stays under the project root", async () => {
  await withRoot(async (root) => {
    assert.equal(stateDir(), path.join(root, ".Herdchestra"));
  });
});

// ---------------------------------------------------------------------------
// End to end through the registered tools
// ---------------------------------------------------------------------------

// A host that records registrations, like OMP's ExtensionAPI does.
const fakeHost = (ctx) => {
  const registered = new Map();
  const active = [];
  const pi = {
    registerTool: (tool) => registered.set(tool.name, tool),
    getActiveTools: () => ["read", "write", "bash", "todo", ...TOOL_NAMES],
    setActiveTools: async (names) => {
      active.splice(0, active.length, ...names);
    },
    on: () => {},
  };
  return { pi, registered, active, ctx };
};

test("the registered js-worker tool reads a real file through the real surface", async () => {
  await withRoot(async () => {
    const dir = tmpRoot();
    const file = path.join(dir, "sample.txt");
    fs.writeFileSync(file, "alpha\nbeta\ngamma\n");
    const ctx = {
      cwd: dir,
      agent: { kind: "sub", id: "smoke-coder", name: "coder", depth: 0 },
      sessionManager: { getSessionId: () => "smoke-session" },
      getAsyncJobSnapshot: () => ({ running: [], recent: [] }),
      ui: { notify() {}, input: async () => "typed" },
    };
    const host = fakeHost(ctx);
    const adapter = new HostAdapter({ pi: host.pi, ctx });
    for (const tool of adapter.tools()) host.pi.registerTool(tool);
    await adapter.narrowTools();

    // Every face this adapter registers, plus the two control tools. `plan` is
    // registered for every session and stays INACTIVE for a non-Planner: the
    // face is registered once, and the narrowing decides who may run it.
    assert.deepEqual([...host.registered.keys()], [...TOOL_NAMES, "negotiate", "askPreviousScope"]);
    assert.equal(host.active.includes("plan"), false, "a coder must not be offered the planner's face");
    assert.equal(host.active.includes("read"), false);
    assert.equal(host.active.includes("js-worker"), true);
    assert.equal(host.active.includes("js-manager"), false);

    const result = await host.registered.get("js-worker").execute(
      "smoke-call-1",
      {
        source: `
          class Js extends JsProgram {
            async run() {
              const text = await this.cat(${JSON.stringify(file)});
              const spans = this.whereAll(text, "beta");
              const info = await this.stat(${JSON.stringify(file)});
              return { text, spans, exists: info.exists, size: info.size };
            }
          }
        `,
      },
      undefined,
      undefined,
      ctx,
    );
    assert.equal(result.isError, undefined);
    const receipt = receiptOf(result);
    assert.equal(receipt.state, "completed");
    assert.deepEqual(receipt.value, {
      text: "alpha\nbeta\ngamma\n",
      spans: [[6, 10]],
      exists: true,
      size: 17,
    });
  });
});

test("a Manager program on a worker session has no file members", async () => {
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "sub", id: "smoke-coder", name: "coder", depth: 0 },
      sessionManager: { getSessionId: () => "smoke-session-2" },
      getAsyncJobSnapshot: () => ({ running: [], recent: [] }),
    };
    const host = fakeHost(ctx);
    const adapter = new HostAdapter({ pi: host.pi, ctx });
    for (const tool of adapter.tools()) host.pi.registerTool(tool);
    const result = await host.registered.get("js-manager").execute(
      "smoke-call-2",
      { source: "class Js extends JsProgram { async run() { return await this.cat('x'); } }" },
      undefined,
      undefined,
      ctx,
    );
    assert.equal(result.isError, true);
    assert.match(result.content[0].text, /TypeError/);
  });
});

test("a program carries its role's capability set, so unpark asks core the right question", async () => {
  // Core §3.3 checks the CONTROLLER against the TARGET's own capability set
  // (`core/control.mjs` `unpark`): the set is a fact of the program, never a
  // parameter of the call. A program registered without one would carry the
  // empty set, every controller would "hold" it, and the check would mean
  // nothing. This pins the registration side of that contract.
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "cap-main", name: "manager", depth: 0 },
      sessionManager: { getSessionId: () => `sess-cap-${Date.now().toString(36)}` },
    };
    const host = new testSession({ pi: { registerTool: () => {} }, ctx, role: ROLE.MANAGER });
    const built = host.buildSurface({
      toolCallId: "call-cap",
      surfaceName: "js-manager",
      source: 'class Js extends JsProgram { async run() {} }',
    });
    assert.deepEqual(built.program.capabilities, ["manager"]);
    // A worker face registers under the worker's own role, so a worker program
    // is never resumable by a Manager program of the same session object.
    assert.deepEqual(
      host.buildSurface({
        toolCallId: "call-cap-worker",
        surfaceName: "js-worker",
        source: 'class Js extends JsProgram { async run() {} }',
      }).program.capabilities,
      ["coder"],
    );
  });
});

test("the controller's answer carries the value the resumed expression received", async () => {
  // Core attaches `value` as a NON-enumerable field, so a receipt that travels
  // through JSON would drop it. The member's answer must still name it.
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "value-main", name: "manager", depth: 0 },
      sessionManager: { getSessionId: () => `sess-value-${Date.now().toString(36)}` },
    };
    const host = new testSession({ pi: { registerTool: () => {} }, ctx, role: ROLE.MANAGER });
    const target = host.buildSurface({
      toolCallId: "call-target",
      surfaceName: "js-manager",
      source: 'class Js extends JsProgram { async run() {} }',
    });
    target.program.park({ reason: "explicit", payload: { why: "wait" } });
    const controller = host.buildSurface({
      toolCallId: "call-controller",
      surfaceName: "js-manager",
      source: 'class Js extends JsProgram { async run() {} }',
    });
    const answer = controller.surface.surface().unpark("call-target", [7, 8]);
    assert.deepEqual(answer.value, [7, 8]);
    // It survives the trip through JSON, which is how the tool result travels.
    assert.deepEqual(JSON.parse(JSON.stringify(answer)).value, [7, 8]);
  });
});

test("a park through the real tools is resumed in place by a later call", async () => {
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "manager-agent", name: "manager", depth: 0 },
      sessionManager: { getSessionId: () => `sess-park-${Date.now().toString(36)}` },
      getAsyncJobSnapshot: () => ({ running: [], recent: [] }),
    };
    const host = fakeHost(ctx);
    const adapter = new HostAdapter({ pi: host.pi, ctx });
    for (const tool of adapter.tools()) host.pi.registerTool(tool);
    const manager = host.registered.get("js-manager");

    const first = await manager.execute(
      "call-park",
      {
        source: `class Js extends JsProgram {
          async run() { const v = await this.park({ why: "wait" }); return { resumedWith: v }; }
        }`,
      },
      undefined,
      undefined,
      ctx,
    );
    const parked = receiptOf(first);
    assert.equal(parked.state, "parked");
    assert.deepEqual(parked.payload, { why: "wait" });
    // The parked body is alive and its name is held.
    assert.equal(adapter.session.state.programs.live("call-park").state, "parked");
    assert.equal(adapter.session.parks.size, 1);

    const second = await manager.execute(
      "call-resume",
      {
        source: `class Js extends JsProgram {
          async run() { return this.unpark("call-park", [1, 2]); }
        }`,
      },
      undefined,
      undefined,
      ctx,
    );
    const resumed = receiptOf(second);
    assert.equal(resumed.error, null);
    // The injected value reached the parked expression, which continued to its
    // own end: nothing was re-run and the program finished normally.
    assert.deepEqual(resumed.value.value, [1, 2]);
    await new Promise((resolve) => setTimeout(resolve, 30));
    assert.equal(adapter.session.state.programs.get("call-park").state, "completed");
    assert.equal(adapter.session.parks.size, 0);
  });
});

// ---------------------------------------------------------------------------
// The real host call shape (verified against omp 18.14.1)
// ---------------------------------------------------------------------------

test("tools are registered at factory time and survive a narrowing over an empty active set", async () => {
  // The real host's active set at `session_start` does not contain the tools an
  // extension just registered (they enter the table through the runner's
  // late-registration path). Narrowing must therefore never filter the role's
  // own tools by the host's current set — doing so dropped every `js-*` tool on
  // the real host while the hooks still fired.
  await withRoot(async () => {
    const tools = new Map();
    const handlers = new Map();
    let active = [];
    const api = {
      registerTool: (tool) => tools.set(tool.name, tool),
      getActiveTools: () => ["read", "write", "bash", "todo", "yield"],
      setActiveTools: async (names) => {
        active = names;
      },
      on: (event, handler) => {
        if (!handlers.has(event)) handlers.set(event, []);
        handlers.get(event).push(handler);
      },
      pi: {},
    };
    herdchestra(api);
    // Registered before any session exists: every face plus the control tools.
    // Which of them a session may RUN is the narrowing's decision, made later.
    // Every face plus the control and snapshot tools, in registration order.
    // The snapshot queries (§6.2 exemptions) are host tools, so they are
    // registered at factory time like `fork`/`join`.
    assert.deepEqual([...tools.keys()], [...TOOL_NAMES, "negotiate", "askPreviousScope"]);

    const ctx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => "reg-1", getEntries: () => [] },
    };
    await handlers.get("session_start")[0]({ type: "session_start" }, ctx);
    assert.equal(active.includes("js-manager"), true);
    assert.equal(active.includes("fork"), true);
    assert.equal(active.includes("join"), true);
    assert.equal(active.includes("read"), false);
    assert.equal(active.includes("bash"), false);
  });
});

test("the factory works when called as factory(api): no dispatch, honest refusals", async () => {
  await withRoot(async () => {
    const tools = new Map();
    const handlers = new Map();
    let active = [];
    const api = {
      registerTool: (tool) => tools.set(tool.name, tool),
      getActiveTools: () => ["read", "grep", "glob", "write", "edit", "bash", "eval", "task", "todo"],
      setActiveTools: async (names) => {
        active = names;
      },
      on: (event, handler) => {
        if (!handlers.has(event)) handlers.set(event, []);
        handlers.get(event).push(handler);
      },
    };
    // loader.ts:454 calls `factory(api)` with ONE argument.
    herdchestra(api);
    const ctx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => "real-host-1", getEntries: () => [] },
    };
    await handlers.get("session_start")[0]({ type: "session_start" }, ctx);

    assert.deepEqual([...tools.keys()], [...TOOL_NAMES, "negotiate", "askPreviousScope"]);
    // The Manager narrowing really removed the replaced tools.
    for (const name of ["read", "grep", "glob", "write", "edit", "bash", "eval", "task"]) {
      assert.equal(active.includes(name), false, name);
    }
    // A replayed session_start installs nothing twice.
    await handlers.get("session_start")[0]({ type: "session_start" }, ctx);
    assert.equal(tools.size, TOOL_NAMES.length + 2);

    // `fork` needs the SDK session factory on `api.pi`. A host that exposes none
    // is reported, never faked. The call carries the session it runs in: the
    // host passes `ctx` as the fifth argument (`sdk.ts` execute), and the roster
    // a fork writes into is that session's own — a call without a context has
    // no roster to ask, which is the `NO_SESSION` answer below.
    const fork = receiptOf(await tools.get("fork").execute("c1", { name: "w1", verboseIntent: "role: coder — x" }, undefined, undefined, ctx));
    assert.equal(fork.started, false);
    assert.equal(fork.reason, "NO_SESSION_FACTORY");
    const join = receiptOf(await tools.get("join").execute("c2", {}, undefined, undefined, ctx));
    assert.equal(join.reason, "NOTHING_TO_JOIN");

    // The program face still works with no dispatcher.
    const program = await tools
      .get("js-manager")
      .execute("c3", { source: "class Js extends JsProgram { async run() { return 42 } }" }, undefined, undefined, ctx);
    const receipt = receiptOf(program);
    assert.equal(receipt.state, "completed");
    assert.equal(receipt.value, 42);
  });
});

// A host-shaped harness for the worker boundary. The Manager session is real
// (its adapter bound through `session_start`), and `createAgentSession` returns
// a worker session shaped like the SDK's: it carries its coding-agent module on
// `session.pi`, and its own `tools` map holds exactly what the lane installed on
// it (`registerTool` targets that map). A tool call made inside the worker runs
// those definitions, never the Manager's.
const workerBoundaryHarness = () => {
  const managerTools = new Map();
  const handlers = new Map();
  const created = [];
  const sessions = new Map();
  const workerCalls = [];
  const makeSession = ({ agentId, kind, parentId = null, tools = new Map(), pi = null }) => {
    const listeners = [];
    const session = {
      agent: { kind, id: agentId, name: agentId, depth: kind === "sub" ? 1 : 0, ...(parentId === null ? {} : { parentId }) },
      tools,
      // The SDK module this session was created with (`AgentSession.pi`).
      pi,
      isStreaming: false,
      model: { id: "m" },
      thinkingLevel: "medium",
      serviceTierByFamily: {},
      queuedMessageCount: 0,
      systemPrompt: ["prompt"],
      customCommands: [],
      skills: [],
      // The tools registered ON this session (`registerTool` from its own
      // adapter). This is what a tool call inside the session can run.
      registered: tools,
      getAgentId: () => agentId,
      getEnabledToolNames: () => [...tools.keys()],
      getAllToolInfos: () => [...tools.keys()].map((name) => ({ name })),
      setActiveToolsByName: async () => {},
      // The host's contract: true iff the call started a turn, false when the
      // message was queued or appended without one (agent-session.ts:10054).
      sendCustomMessage: async (_message, options) => options?.triggerTurn === true,
      sendUserMessage: async () => {},
      sessionManager: {
        // The session id names this session's own durable state: its roster
        // (`.Herdchestra/<id>.workers.jsonl`), its Pair Hint journal and its
        // request gate. A harness session with NO id falls back to
        // `agent-<id>` — and two sessions that answer the same id share one
        // roster, so a worker's child would appear in its Manager's table.
        // Measured on the real host: this is the same key the adapter uses
        // (`sessionIdOf`), so the fixture states it explicitly.
        getSessionId: () => `sess-${agentId}`,
        appendCustomEntry: () => {},
        appendLabelChange: () => {},
        getSessionName: () => undefined,
        setSessionName: async () => {},
      },
      modelRegistry: { getApiKey: async () => undefined },
      setModel: async () => {},
      setThinkingLevel: () => {},
      setServiceTierFamily: () => {},
      abort: async () => {},
      getContextUsage: () => undefined,
      runEphemeralTurn: async () => ({ text: "" }),
      compact: async () => {},
      waitForIdle: async () => {},
      newSession: async () => true,
      branch: async () => ({ cancelled: false }),
      navigateTree: async () => ({ cancelled: false }),
      switchSession: async () => true,
      reload: async () => {},
      async prompt(text) {
        workerCalls.push(["prompt", text]);
        session.isStreaming = true;
        session.emit({ type: "agent_start" });
      },
      async steer(text) {
        workerCalls.push(["steer", text]);
      },
      subscribe(listener) {
        listeners.push(listener);
        return () => {
          const index = listeners.indexOf(listener);
          if (index >= 0) listeners.splice(index, 1);
        };
      },
      async dispose() {},
      emit(event) {
        for (const listener of [...listeners]) listener(event);
      },
      // The host's turn end: the assistant's text, then the terminal event.
      endTurn(text, { terminal = true } = {}) {
        session.isStreaming = false;
        if (typeof text === "string") {
          session.emit({ type: "message_end", message: { role: "assistant", content: [{ type: "text", text }] } });
        }
        session.emit({ type: "agent_end", isTerminal: terminal });
      },
    };
    return session;
  };
  const api = {
    registerTool: (tool) => managerTools.set(tool.name, tool),
    getActiveTools: () => ["read", "grep", "glob", "write", "edit", "bash", "eval", "task", "todo"],
    setActiveTools: async () => {},
    on: (event, handler) => {
      if (!handlers.has(event)) handlers.set(event, []);
      handlers.get(event).push(handler);
    },
    pi: {
      async createAgentSession(options) {
        // The lane builds the worker face and installs it on this session after
        // creation (`workerBuild.install(session)`); the host only creates it.
        const tools = new Map();
        const session = makeSession({
          agentId: options.agentId,
          kind: "sub",
          parentId: options.parentAgentId,
          tools,
          pi: api.pi,
        });
        created.push({ ...options, session });
        session.extensionRunner = {
          initialize() {},
          async emit(event) {
            const handshake = event?.herdchestraHandshake;
            if (handshake !== undefined) {
              handshake.reply({ token: handshake.token, bound: true, narrowed: true, role: "coder", reason: null });
            }
          },
        };
        sessions.set(options.agentId, session);
        return { session };
      },
    },
  };
  return { api, managerTools, handlers, created, sessions, workerCalls };
};

const managerCtxFor = (id) => ({
  agent: { kind: "main", id: "Main", name: "main", depth: 0 },
  sessionManager: { getSessionId: () => id, getEntries: () => [] },
});

// The ctx a worker's own tools are called with.
//
// The id MUST be the one the harness SESSION reports, because `adapterForCall`
// resolves the adapter by it: a key that misses the registry falls back to
// whichever adapter BUILT the tool definition — the failure mode `surface.mjs`
// warns about ("a worker's tool came to run on the Manager's session"). It is
// read from the session itself rather than re-spelled here, so the format has
// one home (`makeSession`) and the two can never disagree again.
const workerCtxFor = (sessions, id) => ({
  agent: { kind: "sub", id, name: id, depth: 1, parentId: "Main" },
  sessionManager: { getSessionId: () => sessions.get(id).sessionManager.getSessionId(), getEntries: () => [] },
});

test("join with an empty roster says so, and names what the roster holds when it is not empty", async () => {
  // Measured on the real host: `join` answered `NOTHING_TO_JOIN` while `fork`
  // had just said `started: true`. The refusal must distinguish "this session
  // started no worker" from "that worker already reported", or the next
  // measurement cannot tell a wrong roster from a consumed report.
  await withRoot(async () => {
    const { api, managerTools, handlers, sessions } = workerBoundaryHarness();
    herdchestra(api);
    const managerCtx = managerCtxFor("manager-session-empty-join");
    await handlers.get("session_start")[0]({ type: "session_start" }, managerCtx);

    // The call carries the session it runs in: `join` reads THAT session's
    // roster, and a call with no context has none to read (`NO_SESSION`).
    const empty = receiptOf(await managerTools.get("join").execute("j0", {}, undefined, undefined, managerCtx));
    assert.equal(empty.ok, false);
    assert.equal(empty.reason, "NOTHING_TO_JOIN");
    assert.deepEqual(empty.roster, []);
    assert.match(empty.detail, /holds no worker/);

    // After a fork the roster is not empty, so the refusal names the lane.
    await managerTools.get("fork").execute("f1", { name: "w1", verboseIntent: "role: coder — go" }, undefined, undefined, managerCtx);
    sessions.get("w1").endTurn("done");
    assert.equal(receiptOf(await managerTools.get("join").execute("j1", {}, undefined, undefined, managerCtx)).text, "done");
    const consumed = receiptOf(await managerTools.get("join").execute("j2", {}, undefined, undefined, managerCtx));
    assert.equal(consumed.reason, "NOTHING_TO_JOIN");
    assert.deepEqual(consumed.roster, ["w1"]);
    assert.match(consumed.detail, /roster: w1/);
  });
});

test("the Manager's join finds the lane its own fork started, before that lane ends", async () => {
  // Measured on the real host: right after `fork` returned `started: true`, six
  // immediate `join` calls all answered `NOTHING_TO_JOIN`. The Manager's roster
  // must hold the lane its own `fork` started, for as long as that lane has not
  // reported — a running turn is pending, not absent.
  await withRoot(async () => {
    const { api, managerTools, handlers } = workerBoundaryHarness();
    herdchestra(api);
    const managerCtx = managerCtxFor("manager-session-join");
    await handlers.get("session_start")[0]({ type: "session_start" }, managerCtx);

    const forked = receiptOf(
      await managerTools.get("fork").execute("f1", { name: "w1", verboseIntent: "role: coder — go" }, undefined, undefined, managerCtx),
    );
    assert.deepEqual(forked, { name: "w1", steered: false, started: true });

    // The lane is running, so `join` waits rather than reporting an empty
    // roster. It must not answer `NOTHING_TO_JOIN` here.
    for (let i = 0; i < 6; i += 1) {
      const joined = managerTools.get("join").execute(`j${i}`, {});
      // A waiting join must not have settled with a refusal; the fake host ends
      // the turn below, which releases it.
      const settledEarly = await Promise.race([joined.then(() => "settled"), Promise.resolve("waiting")]);
      if (settledEarly === "settled") {
        const text = (await joined).content[0].text;
        assert.doesNotMatch(text, /NOTHING_TO_JOIN/, `join ${i} refused while the lane was still running: ${text}`);
      }
    }
  });
});

test("a join pending when its session is disposed settles with SESSION_DISPOSED, never stays pending", async () => {
  // The wait is armed on a turn ending. A disposed session ends no more turns,
  // so a join left pending would never settle — a real host would hang exactly
  // as the test file once did. The wait must end with a refusal that NAMES the
  // disposal, and must not report the roster as empty: `NOTHING_TO_JOIN` would
  // invite the caller to fork into a table that no longer exists.
  await withRoot(async () => {
    const { api, managerTools, handlers } = workerBoundaryHarness();
    herdchestra(api);
    const managerCtx = managerCtxFor("manager-session-dispose");
    await handlers.get("session_start")[0]({ type: "session_start" }, managerCtx);
    assert.notEqual(adapterForCall(managerCtx), null, "the manager session bound an adapter");

    const forked = receiptOf(
      await managerTools.get("fork").execute("f1", { name: "w1", verboseIntent: "role: coder — go" }, undefined, undefined, managerCtx),
    );
    assert.equal(forked.started, true, JSON.stringify(forked));

    // The lane is running and its turn is never ended: the join below is armed
    // and stays pending until something releases it.
    let settled = false;
    const joining = managerTools.get("join").execute("j1", {}, undefined, undefined, managerCtx).then((result) => {
      settled = true;
      return receiptOf(result);
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(settled, false, "the join waited while the lane was still running");

    // The session goes away, as the host does at the end of a session: the
    // adapter that owns it is released, and that release is what must reach
    // the roster the join is waiting on.
    const adapter = adapterForCall(managerCtx);
    assert.notEqual(adapter, null, "the manager session really bound an adapter");
    adapter.release();
    // The roster is marked before its teardown finishes, so the refusal is
    // available at once; the lanes are then disposed for real.
    await adapter.session.lanes.disposeAll();
    const refused = await joining;
    assert.equal(settled, true, "the join settled once its session was disposed");
    assert.equal(refused.ok, false);
    assert.equal(refused.reason, "SESSION_DISPOSED");
    assert.match(refused.detail, /disposed/);
    // The roster is still named: the caller can see what it was waiting for.
    assert.deepEqual(refused.roster, ["w1"]);
  });
});

test("a worker's js-worker runs against the worker's own session, not the Manager's", async () => {
  // Measured on the real host: the worker's `js-worker` reached a session that
  // was not its own, and the call died on `session.buildSurface`. A worker's
  // programs must run on ITS registry, ledger and parks.
  await withRoot(async () => {
    const { api, managerTools, handlers, created, sessions } = workerBoundaryHarness();
    herdchestra(api);
    const managerCtx = managerCtxFor("manager-session");
    await handlers.get("session_start")[0]({ type: "session_start" }, managerCtx);

    const forked = receiptOf(
      await managerTools.get("fork").execute("f1", { name: "w1", verboseIntent: "role: coder — go" }, undefined, undefined, managerCtx),
    );
    assert.deepEqual(forked, { name: "w1", steered: false, started: true });
    assert.equal(created.length, 1);

    // The worker received definitions of its own, installed into ITS session,
    // not the Manager's objects.
    const workerTools = new Map(
      [...sessions.get("w1").registered].filter(([name]) => ["js-worker", "fork", "join"].includes(name)),
    );
    assert.deepEqual([...workerTools.keys()], ["js-worker", "fork", "join"]);
    for (const name of workerTools.keys()) {
      assert.notEqual(workerTools.get(name), managerTools.get(name), name);
    }

    // The worker's own program face runs on the worker session.
    const result = await workerTools
      .get("js-worker")
      .execute("c1", { source: "class Js extends JsProgram { async run() { return 7 } }" }, undefined, undefined, workerCtxFor(sessions, "w1"));
    const receipt = receiptOf(result);
    assert.equal(receipt.state, "completed");
    assert.equal(receipt.value, 7);

    // The Manager's own face still runs on the Manager session.
    const managerRun = receiptOf(
      await managerTools
        .get("js-manager")
        .execute("c2", { source: "class Js extends JsProgram { async run() { return 11 } }" }, undefined, undefined, managerCtx),
    );
    assert.equal(managerRun.value, 11);
  });
});

test("a worker's programs are booked on the worker's own program registry", async () => {
  // The boundary is observable. A program name belongs to the session that ran
  // it, so the worker's parked program is parked in the WORKER's registry: the
  // Manager's own `unpark` cannot reach it and says so.
  await withRoot(async () => {
    const { api, managerTools, handlers, created, sessions } = workerBoundaryHarness();
    herdchestra(api);
    const managerCtx = managerCtxFor("manager-session-registry");
    await handlers.get("session_start")[0]({ type: "session_start" }, managerCtx);
    await managerTools.get("fork").execute("f1", { name: "w1", verboseIntent: "role: coder — go" }, undefined, undefined, managerCtx);
    const workerTools = new Map(
      [...sessions.get("w1").registered].filter(([name]) => ["js-worker", "fork", "join"].includes(name)),
    );

    // A worker program that parks keeps its name in the WORKER's registry: the
    // receipt reports a parked state, which only a live program of that session
    // can produce.
    const parked = receiptOf(
      await workerTools
        .get("js-worker")
        .execute(
          "call-park",
          { source: "class Js extends JsProgram { async run() { return await this.park({ why: 'wait' }) } }" },
          undefined,
          undefined,
          workerCtxFor(sessions, "w1"),
        ),
    );
    assert.equal(parked.state, "parked");

    // The Manager's registry never held the worker's program: its `unpark` of
    // that name is refused, and the refusal names the missing program.
    const managerUnpark = await managerTools
      .get("js-manager")
      .execute(
        "call-manager",
        { source: "class Js extends JsProgram { async run() { return this.unpark('call-park', { value: 1 }) } }" },
        undefined,
        undefined,
        managerCtx,
      );
    assert.equal(managerUnpark.isError, true);
    assert.match(managerUnpark.content[0].text, /call-park/);
  });
});

test("a worker's sendMessage answers with the session's own result", async () => {
  // The host's contract for `sendCustomMessage` is "true iff this call started a
  // turn" (agent-session.ts:10054). The action passes that answer through; it
  // never answers `false` or `true` on its own.
  const session = fakeSession({ agentId: "w-send" });
  let answer = true;
  let captured = null;
  session.sendCustomMessage = async (message, options) => {
    captured = { message, options };
    return answer;
  };
  const actions = workerActions(session);
  assert.equal(await actions.sendMessage({ customType: "herd" }, { triggerTurn: true }), true);
  assert.deepEqual(captured, { message: { customType: "herd" }, options: { triggerTurn: true } });
  answer = false;
  assert.equal(await actions.sendMessage({ customType: "herd" }), false);
});

test("a worker builder that does not bind the worker's session is refused", async () => {
  // Fail closed: a builder whose adapter is not bound to the session the host
  // created would leave the worker's programs acting on another session (or on
  // none). The lane refuses and disposes instead of starting it.
  const session = fakeSession({ agentId: "w9" });
  const api = fakeApi([session]);
  const lanes = trackedLaneTable(
    new LaneTable({
      api,
      workerToolNames: ["js-worker", "fork", "join"],
      workerToolsFor: () => ({
        tools: [],
        install: async () => {},
        boundSession: () => null,
        dispose: () => {},
      }),
    }),
  );
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: () => "coder" });
  const receipt = await port.fork("w9", "role: coder — work");
  assert.equal(receipt.started, false);
  assert.equal(receipt.reason, "WORKER_TOOLS_FAILED");
  assert.match(receipt.detail, /worker's own session/);
  assert.equal(lanes.size, 0);
  assert.equal(session.calls.some(([kind]) => kind === "prompt"), false);
  assert.equal(session.calls.some(([kind]) => kind === "dispose"), true);
});

test("a worker's own roster is the one its tools fork into", async () => {
  // The worker's RoleSession keeps the roster the lane gave it; it must not open
  // a second table, which would let `fork` fill one table and `join` read another.
  await withRoot(async () => {
    const workerSession = fakeSession({ agentId: "w-roster" });
    const ownLanes = trackedLaneTable(new LaneTable({ api: fakeApi() }));
    const host = new testSession({ pi: { registerTool: () => {} }, api: null, role: ROLE.CODER, lanes: ownLanes });
    host.bindSession({ session: { ...workerSession, agent: { kind: "sub", id: "w-roster", name: "w-roster", depth: 1, parentId: "Main" } } });
    assert.equal(host.lanes, ownLanes);
  });
});

test("a forked session owns a fission of its own and replaces its present", async () => {
  // Spec §4.2 gives fission to the session `fork` started. The lane's own
  // `RoleSession` (opened through `bindSession`, not through the Manager's
  // `ctx`) must therefore be admitted: `origins` sees THIS session as forked,
  // and capacity comes from the real SDK factory, not from a host action.
  await withRoot(async () => {
    const sessions = [fakeSession({ agentId: "lane-a" }), fakeSession({ agentId: "lane-b" })];
    const api = fakeApi(sessions);
    const lanes = trackedLaneTable(new LaneTable({ api }));
    const host = new testSession({ pi: { registerTool: () => {} }, api, role: ROLE.CODER, lanes });
    const worker = {
      ...fakeSession({ agentId: "worker-fission" }),
      agent: { kind: "sub", id: "worker-fission", name: "worker-fission", depth: 1, parentId: "Main" },
      sessionManager: { getSessionId: () => "worker-fission-session", getEntries: () => [] },
    };
    host.bindSession({ session: worker });
    assert.notEqual(host.fission, null);
    // Not `InvalidOrigin` and not `CapacityExceeded`: the group is admitted, the
    // lanes are real sessions, and the present is replaced by the owner's own
    // control surface (abort the live turn, drop the queue).
    const started = await host.fission.fission("worker-fission", ["first", "second"]);
    assert.equal(started.ok, true);
    assert.equal(started.lanes.length, 2);
    assert.equal(api.created.length, 2);
    // The replacement really happened on the owner: its present is marked, so
    // it produces no further turn. The interrupt itself is armed for the turn's
    // end, because this call runs inside the owner's own turn and the host's
    // `abort` waits for that turn to be idle (`agent-session.ts:11215`).
    assert.notEqual(host.control.replaced, null);
    assert.equal(host.control.reason(), "SESSION_REPLACED");
    // The group is registered, and its lanes are the ones it created.
    const group = host.fission.groupOf("worker-fission");
    assert.notEqual(group, null);
    assert.equal(group.state, "replaced");
    assert.notEqual(host.lane("worker-fission:2:1#0"), null);
    assert.notEqual(host.lane("worker-fission:2:1#1"), null);
    // The lanes belong to the group, not to the owner's roster: `join` collects
    // the workers `fork` started, never an unconverged lane.
    assert.deepEqual(lanes.names(), []);
  });
});

test("a Manager session is refused fission, and its lanes are the workers it forked", async () => {
  // The same class is built for every session; the origin check is what decides.
  // A Manager (not forked) is refused before any lane exists.
  await withRoot(async () => {
    const api = fakeApi();
    const managerCtx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => "manager-fission", getEntries: () => [] },
    };
    const host = new testSession({ pi: { registerTool: () => {} }, api, ctx: managerCtx, role: ROLE.MANAGER });
    assert.notEqual(host.fission, null);
    assert.deepEqual(await host.fission.fission("Main", ["a", "b"]), {
      ok: false,
      rejection: "InvalidOrigin",
    });
    assert.equal(api.created.length, 0);
  });
});

test("fission is owned by the session, not by the program's tool-call id", async () => {
  // Measured on the real host: a worker's `this.fission(...)` was refused with
  // `InvalidOrigin` even though the session was a forked worker. The program is
  // named by its tool-call id (`chatcmpl-tool-…`), which never equals the
  // session name, so the origin check compared two unrelated strings. The
  // offline fixtures hid this by naming their calls after the session.
  await withRoot(async () => {
    const api = fakeApi([fakeSession({ agentId: "lane-a" }), fakeSession({ agentId: "lane-b" })]);
    const lanes = trackedLaneTable(new LaneTable({ api }));
    const host = new testSession({ pi: { registerTool: () => {} }, api, role: ROLE.CODER, lanes });
    const worker = {
      getAgentId: () => "w1",
      agent: { kind: "sub", name: "coder", depth: 1, parentId: "Main" },
      sessionManager: { getSessionId: () => "w1-session", getEntries: () => [] },
      tools: new Map(),
      // A session the adapter drives answers its own control surface, so the
      // fission's present replacement has something real to silence.
      newSession: async () => true,
      isStreaming: false,
      abort: async () => {},
    };
    host.bindSession({ session: worker });
    assert.equal(host.name(), "w1");

    // The program runs under a call id that has nothing to do with the session.
    const built = host.buildSurface({
      toolCallId: "chatcmpl-tool-8b68163e91dafb4d",
      surfaceName: "js-worker",
      source: 'class Js extends JsProgram { async run() {} }',
    });
    const members = built.surface.surface();
    // The group is admitted, whatever the program is called: admission asks
    // about the SESSION, never about the call id.
    const admitted = await members.fission(["lane one", "lane two"]);
    assert.equal(admitted.ok, true);
    // The group is named after the SESSION, not the call: a group id built from
    // a tool-call id names nothing a caller can trace back to a session.
    assert.match(admitted.groupId, /^w1:/);
    assert.equal(host.lane("w1:2:1#0")?.index, 0);
  });
});

test("a worker session that answers only getAgentId is still admitted to fission", async () => {
  // Measured on the real host: a worker's `this.fission(...)` was refused with
  // `InvalidOrigin`. A session created through the SDK answers `getAgentId()`
  // and may carry no `agent.id`, so the origin check compared the session id
  // against the name fission was called with and never matched.
  await withRoot(async () => {
    const api = fakeApi([fakeSession({ agentId: "lane-a" }), fakeSession({ agentId: "lane-b" })]);
    const lanes = trackedLaneTable(new LaneTable({ api }));
    // Identity only through `getAgentId`; `agent` is absent, as on the host.
    const worker = {
      getAgentId: () => "w1",
      sessionManager: { getSessionId: () => "w1-session", getEntries: () => [] },
      tools: new Map(),
      newSession: async () => true,
      isStreaming: false,
      abort: async () => {},
    };
    const host = new testSession({ pi: { registerTool: () => {} }, api, role: ROLE.CODER, lanes });
    host.bindSession({ session: worker });

    assert.equal(host.name(), "w1");
    // Admitted: the origin check reads the session's own creator fact, and a
    // session without `agent` still has its identity through `getAgentId()`.
    assert.equal((await host.fission.fission("w1", ["first", "second"])).ok, true);
  });
});

test("fission's lanes go through the calling session's own roster, not a second one", async () => {
  // The group must be built by the SAME roster the caller's `fork` uses: same
  // face, same runtime wiring, same authorization, same names for `join`. A
  // group started any other way would hand the caller sessions it cannot join.
  await withRoot(async () => {
    const api = fakeApi([fakeSession({ agentId: "lane-a" }), fakeSession({ agentId: "lane-b" })]);
    const lanes = trackedLaneTable(new LaneTable({ api }));
    const host = new testSession({ pi: { registerTool: () => {} }, api, role: ROLE.CODER, lanes });
    const worker = {
      ...fakeSession({ agentId: "worker-lanes" }),
      agent: { kind: "sub", id: "worker-lanes", name: "worker-lanes", depth: 1, parentId: "Main" },
      sessionManager: { getSessionId: () => "worker-lanes-session", getEntries: () => [] },
    };
    host.bindSession({ session: worker });
    assert.equal(host.lanes, lanes, "the session kept the roster it was given");
    const started = [];
    const original = lanes.start.bind(lanes);
    lanes.start = async (request) => {
      started.push(request.name);
      return await original(request);
    };
    assert.equal((await host.fission.fission("worker-lanes", ["first", "second"])).ok, true);
    // Two lanes went through the caller's own roster, in order, under the
    // group's lane names.
    assert.equal(started.length, 2);
    assert.match(started[0], /#0$/);
    assert.match(started[1], /#1$/);
    assert.equal(api.created.length, 2);
    // Each lane carries the caller's own worker identity.
    assert.equal(api.created[0].agentName, "coder");
  });
});

test("a worker's fork and join share the worker's own roster, apart from the Manager's", async () => {
  // Measured on the real host: `fork` answered started:true, then every `join`
  // answered NOTHING_TO_JOIN. The port must belong to the CALLING session, so a
  // worker's `fork` writes and its `join` reads the same table, and the
  // Manager's table never holds the worker's child.
  await withRoot(async () => {
    const { api, managerTools, handlers, created, sessions } = workerBoundaryHarness();
    herdchestra(api);
    const managerCtx = managerCtxFor("manager-roster-shared");
    await handlers.get("session_start")[0]({ type: "session_start" }, managerCtx);
    await managerTools.get("fork").execute("f1", { name: "w1", verboseIntent: "role: coder — go" }, undefined, undefined, managerCtx);
    const workerTools = new Map(
      [...sessions.get("w1").registered].filter(([name]) => ["js-worker", "fork", "join"].includes(name)),
    );

    // The worker forks a child of its own.
    const nested = receiptOf(
      await workerTools.get("fork").execute("f2", { name: "w1-child", verboseIntent: "role: coder — help" }, undefined, undefined, workerCtxFor(sessions, "w1")),
    );
    assert.equal(nested.started, true, JSON.stringify(nested));
    assert.equal(created.length, 2);

    // The worker's `join` finds it — the same roster `fork` wrote.
    sessions.get("w1-child").endTurn("child done");
    const workerJoin = receiptOf(await workerTools.get("join").execute("f3", {}, undefined, undefined, workerCtxFor(sessions, "w1")));
    assert.equal(workerJoin.name, "w1-child");
    assert.equal(workerJoin.text, "child done");

    // The Manager's `join` never sees the worker's child: it waits for its own
    // w1, whose report arrives only when its turn ends. The wait is started
    // BEFORE the turn ends, which is what makes it a wait and not a race.
    const managerJoining = managerTools.get("join").execute("f4", {}, undefined, undefined, managerCtx);
    sessions.get("w1").endTurn("parent done");
    const managerJoin = receiptOf(await managerJoining);
    assert.equal(managerJoin.name, "w1");
    assert.equal(managerJoin.text, "parent done");
  });
});

test("a nested fork three levels deep is not capped by task.maxRecursionDepth", async () => {
  // §7.3 asks `fork` to raise or bypass `task.maxRecursionDepth` (default 2).
  // This adapter never goes through the task tool, and the host applies that cap
  // when it CONSTRUCTS the task tool for a session
  // (`tools/index.ts:806` `canSpawnAtDepth(cfgTaskMaxRecursionDepth, session.taskDepth)`),
  // so a lane created through the SDK cannot be capped by it. The property that
  // must hold is therefore behavioural: a worker's worker's worker can still
  // fork, at depths where the default cap would have refused.
  await withRoot(async () => {
    const { api, managerTools, handlers, created, sessions } = workerBoundaryHarness();
    herdchestra(api);
    const managerCtx = managerCtxFor("manager-depth3");
    await handlers.get("session_start")[0]({ type: "session_start" }, managerCtx);

    // Depth 1: the Manager's worker.
    await managerTools.get("fork").execute("d1", { name: "lvl1", verboseIntent: "role: coder — level one" }, undefined, undefined, managerCtx);
    assert.equal(created.length, 1);

    // Depth 2 and 3: each level forks the next through ITS OWN tools.
    let parent = "lvl1";
    for (const level of [2, 3]) {
      const child = `lvl${level}`;
      const tools = new Map(
        [...sessions.get(parent).registered].filter(([name]) => ["js-worker", "fork", "join"].includes(name)),
      );
      assert.equal(tools.has("fork"), true, `${parent} holds fork`);
      const receipt = receiptOf(
        await tools.get("fork").execute(`d${level}`, { name: child, verboseIntent: `role: coder — level ${level}` }, undefined, undefined, workerCtxFor(sessions, parent)),
      );
      assert.equal(receipt.started, true, `${child}: ${JSON.stringify(receipt)}`);
      assert.equal(created.length, level, `one session per level, ${level}`);
      parent = child;
    }

    // The third level really is a live session with a turn, and its own `join`
    // collects its child — the nesting is real, not a receipt.
    const deepest = sessions.get("lvl3");
    assert.notEqual(deepest, undefined);
    deepest.endTurn("level three done");
    const joinTools = new Map([...sessions.get("lvl2").registered].filter(([name]) => name === "join"));
    const joined = receiptOf(await joinTools.get("join").execute("d4", {}, undefined, undefined, workerCtxFor(sessions, "lvl2")));
    assert.equal(joined.name, "lvl3");
    assert.equal(joined.text, "level three done");

    // Every level is a real nesting level, and none was refused for depth: the
    // child of a depth-d session is depth d+1, exactly the number the host's
    // own task cap would have counted.
    //
    // The fixture reports every worker's `agent.depth` as 1, so the level-3
    // child is created at the depth the fixture's own reading implies. What the
    // test proves is that nothing REFUSES the third level: the host's cap is
    // applied to a task tool, and this adapter builds none. (On a real host the
    // reading is the host's own `sdk.ts:3571` fact, and `surface.mjs`
    // `#laneDepth()` passes that session's depth plus one.)
    assert.deepEqual(created.map((entry) => entry.taskDepth), [1, 2, 2]);
    for (const entry of created) assert.equal(entry.agentName, "coder");
  });
});

test("a join with no roster names the missing session instead of inventing a worker", async () => {
  const port = new TaskPort({ lanes: null, sleep: async () => {}, agentFor: () => "coder" });
  assert.deepEqual(await port.join(), { ok: false, reason: "NO_SESSION" });
  assert.deepEqual(await port.fork("w1", "role: coder — go"), {
    name: "w1",
    steered: false,
    started: false,
    reason: "NO_SESSION",
  });
});

test("a fork that returns started:true leaves a lane this session can join", async () => {
  // The user-visible contract: after `fork` answers started:true, a `join` of the
  // SAME session must have something to wait on. It may wait — that is what join
  // is for — but it must not answer NOTHING_TO_JOIN.
  await withRoot(async () => {
    const session = fakeSession({ agentId: "w-late" });
    const api = fakeApi([session]);
    const lanes = trackedLaneTable(new LaneTable({ api }));
    const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: () => "coder" });
    const forked = await port.fork("w-late", "role: coder — go");
    assert.deepEqual(forked, { name: "w-late", steered: false, started: true });
    assert.deepEqual(lanes.names(), ["w-late"]);

    // The worker's turn is still running: join waits for it, then returns it.
    let settled = false;
    const joining = port.join().then((report) => {
      settled = true;
      return report;
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(settled, false, "join waited instead of answering NOTHING_TO_JOIN");
    session.endTurn("late report");
    const report = await joining;
    assert.equal(report.name, "w-late");
    assert.equal(report.text, "late report");

    // Once taken, the same worker reports nothing left — with the roster named,
    // so a caller can tell "already collected" from "another table".
    const again = await port.join();
    assert.equal(again.reason, "NOTHING_TO_JOIN");
    assert.deepEqual(again.roster, ["w-late"]);
    assert.match(again.detail, /w-late/);
  });
});

test("a join never borrows another session's roster", async () => {
  // Parent/child isolation: two rosters, one worker each. Each join takes
  // exactly its own report, and neither roster ever lists the other's worker.
  await withRoot(async () => {
    const parentSession = fakeSession({ agentId: "parent-w" });
    const childSession = fakeSession({ agentId: "child-w" });
    const parentLanes = trackedLaneTable(new LaneTable({ api: fakeApi([parentSession]) }));
    const childLanes = trackedLaneTable(new LaneTable({ api: fakeApi([childSession]) }));
    const parent = new TaskPort({ lanes: parentLanes, sleep: async () => {}, agentFor: () => "coder" });
    const child = new TaskPort({ lanes: childLanes, sleep: async () => {}, agentFor: () => "coder" });
    await parent.fork("parent-w", "role: coder — go");
    await child.fork("child-w", "role: coder — go");
    assert.deepEqual(parentLanes.names(), ["parent-w"]);
    assert.deepEqual(childLanes.names(), ["child-w"]);

    childSession.endTurn("child report");
    const childReport = await child.join();
    assert.equal(childReport.name, "child-w");

    // The parent's worker is untouched by the child's join.
    assert.equal(parentLanes.pending().length, 1);
    // The parent's join is waiting on its own worker before that worker's turn
    // ends: a `join` with work in flight never answers on its own.
    const parentJoining = parent.join();
    parentSession.endTurn("parent report");
    const parentReport = await parentJoining;
    assert.equal(parentReport.name, "parent-w");
    assert.equal(parentReport.text, "parent report");
  });
});

test("a worker's fission is admitted even when the host's agent object has no parentId", async () => {
  // `forked` is a fact about who created the session, not about the host's
  // `agent` object: the host builds that object itself, and a real worker's
  // `fission` was refused with `InvalidOrigin` because the flag was re-derived
  // from it. The lane's `install` must carry `forked: true` explicitly.
  await withRoot(async () => {
    const api = fakeApi([fakeSession({ agentId: "lane-a" }), fakeSession({ agentId: "lane-b" })]);
    const lanes = trackedLaneTable(new LaneTable({ api }));
    const host = new testSession({ pi: { registerTool: () => {} }, api, role: ROLE.CODER, lanes });
    // A session shaped like the SDK's: `agent` without `parentId`, and an id
    // reachable only through `getAgentId()`.
    const worker = {
      ...fakeSession({ agentId: "worker-noparent" }),
      agent: { kind: "sub", name: "coder", depth: 1 },
      sessionManager: { getSessionId: () => "worker-noparent-session", getEntries: () => [] },
    };
    // The worker's own `api` is the lane's: `registerTool` plus the host's SDK,
    // which is what `fission`'s capacity check reads.
    const build = workerSessionTools({ registerTool: () => {}, pi: api.pi }, { lanes });
    await build.install(worker);
    assert.equal(build.adapter.session.name(), "worker-noparent", "the program name is the agent id");
    // Admitted: the creator fact is the one the lane passed in, not something
    // re-derived from the host's agent object.
    assert.equal(
      (await build.adapter.session.fission.fission(build.adapter.session.name(), ["first", "second"])).ok,
      true,
    );
    assert.equal(api.created.length, 2);
  });
});

test("a session the host did not create by fork is still refused fission", async () => {
  // The flag is explicit, so it must also be able to say "no": a Manager session
  // and any other non-forked session are refused, whatever their agent object.
  await withRoot(async () => {
    const api = fakeApi();
    const host = new testSession({ pi: { registerTool: () => {} }, api, role: ROLE.CODER, lanes: trackedLaneTable(new LaneTable({ api })) });
    const session = {
      ...fakeSession({ agentId: "not-forked" }),
      agent: { kind: "sub", id: "not-forked", name: "coder", depth: 1, parentId: "Main" },
      sessionManager: { getSessionId: () => "not-forked-session", getEntries: () => [] },
    };
    host.bindSession({ session, forked: false });
    assert.deepEqual(await host.fission.fission("not-forked", ["a", "b"]), {
      ok: false,
      rejection: "InvalidOrigin",
    });
    assert.equal(api.created.length, 0);
  });
});

test("fission is admitted whatever the program's name is, because admission asks about the session", async () => {
  // `ProgramSurface` names a program after its tool call id and passes that
  // name to `fission`. Comparing it with the session's identity (`w1`) refused
  // every real worker. Admission must be about the SESSION, so any program name
  // is accepted for a forked session and none for any other.
  await withRoot(async () => {
    const api = fakeApi([fakeSession({ agentId: "lane-a" }), fakeSession({ agentId: "lane-b" })]);
    const lanes = trackedLaneTable(new LaneTable({ api }));
    const host = new testSession({ pi: { registerTool: () => {} }, api, role: ROLE.CODER, lanes });
    const worker = {
      ...fakeSession({ agentId: "w1" }),
      agent: { kind: "sub", id: "w1", name: "coder", depth: 1 },
      sessionManager: { getSessionId: () => "w1-session", getEntries: () => [] },
    };
    host.bindSession({ session: worker });

    // A tool-call-id-shaped program name, not the session id.
    const programName = "call_96a5c3d3fb80450fb240f8cd";
    assert.notEqual(programName, host.name());
    assert.equal((await host.fission.fission(programName, ["first", "second"])).ok, true);
    assert.equal(api.created.length, 2);
    // The group's lanes are named after the owner it was given.
    assert.match(api.created[0].agentId, /^call_96a5c3d3fb80450fb240f8cd:2:1#0$/);
  });
});

test("a non-forked session refuses fission under any program name", async () => {
  await withRoot(async () => {
    const api = fakeApi();
    const host = new testSession({ pi: { registerTool: () => {} }, api, role: ROLE.CODER, lanes: trackedLaneTable(new LaneTable({ api })) });
    const session = {
      ...fakeSession({ agentId: "w1" }),
      agent: { kind: "sub", id: "w1", name: "coder", depth: 1, parentId: "Main" },
      sessionManager: { getSessionId: () => "w1-session", getEntries: () => [] },
    };
    host.bindSession({ session, forked: false });
    // Even a program named exactly like the session is refused: the session's
    // own fact decides, not the name.
    assert.deepEqual(await host.fission.fission("w1", ["a", "b"]), { ok: false, rejection: "InvalidOrigin" });
    assert.equal(api.created.length, 0);
  });
});

test("a fission lane cannot fission again, even though its creator was a forked worker", async () => {
  // §4.2 `InvalidOrigin`: a lane is a parallel PRESENT, not a worker `fork`
  // started. It carries `forked: true` (its session was created by a forked
  // worker) but also the lane fact, and only the second closes the door.
  await withRoot(async () => {
    const session = fakeSession({ agentId: "lane-1" });
    const api = fakeApi([]);
    const build = workerSessionTools(
      { registerTool: () => {}, pi: api.pi },
      { lanes: trackedLaneTable(new LaneTable({ api })), forked: true, fissionLane: true },
    );
    await build.install(session);
    const refused = await build.adapter.session.fission.fission("call-lane", ["a", "b"]);
    assert.deepEqual(refused, { ok: false, rejection: "InvalidOrigin" });
    assert.equal(api.created.length, 0, "a refused lane creates no session");
  });
});

test("a program that throws keeps the facts the error carried, not only name and message", async () => {
  // A refusal that reports what it cleaned up must not lose that report. The
  // failure record used to keep only `name` and `message`, so a fission
  // rollback vanished between the throw and the tool result.
  const { run } = runtimeFor(
    `
      class Js extends JsProgram {
        async run() {
          const error = new Error("replacePresent was refused");
          error.code = "PRESENT_NOT_REPLACED";
          error.step = "replacePresent";
          error.rollback = { ok: true, released: ["lane#0", "lane#1"], failed: [] };
          error.abortFailure = new Error("lane #1 did not stop");
          throw error;
        }
      }
    `,
    {},
  );
  const outcome = await run.outcome;
  assert.equal(outcome.status, "failed");
  assert.equal(outcome.error.name, "Error");
  assert.equal(outcome.error.code, "PRESENT_NOT_REPLACED");
  assert.equal(outcome.error.step, "replacePresent");
  assert.deepEqual(outcome.error.rollback, { ok: true, released: ["lane#0", "lane#1"], failed: [] });
  // A rollback that itself failed is reported beside the original refusal, never
  // in place of it, and never dropped.
  assert.equal(outcome.error.abortFailure.message, "lane #1 did not stop");
});

test("a fission called from inside a live turn replaces the present instead of refusing", async () => {
  // The program that calls `fission` is a tool call OF the owner's turn, so the
  // owner is always mid-turn here: waiting for it to settle waits for the call
  // that is waiting. The old port answered `PRESENT_BUSY` for every real
  // fission and rolled the group back after it had started. The present is
  // replaced by a MARK plus an abort: the mark stops the next turn, and the turn
  // in flight is interrupted now.
  await withRoot(async () => {
    const session = fakeSession({ agentId: "w1" });
    session.agent = { kind: "sub", id: "w1", name: "coder", depth: 1, parentId: "Main" };
    session.sessionManager = { getSessionId: () => "w1-session", getEntries: () => [] };
    const api = fakeApi([fakeSession({ agentId: "lane-a" }), fakeSession({ agentId: "lane-b" })]);
    const lanes = trackedLaneTable(new LaneTable({ api }));
    const build = workerSessionTools({ registerTool: () => {}, pi: api.pi }, { lanes });
    await build.install(session);
    session.isStreaming = true;
    const tools = new Map(build.tools.map((tool) => [tool.name, tool]));
    const result = await tools.get("js-worker").execute(
      "call-fission",
      { source: "class Js extends JsProgram { async run() { return await this.fission(['a', 'b']); } }" },
      undefined,
      undefined,
      session,
    );
    assert.equal(result.isError, undefined, JSON.stringify(result.details));
    // A program's receipt carries the run's outcome in `value` (core's
    // `programView`), so the fission answer is read from there.
    assert.equal(result.details.result.value.ok, true);
    // The present is marked, and it produces no further turn whatever its
    // liveness says.
    assert.notEqual(build.adapter.session.control.replaced, null);
    assert.equal(build.adapter.session.control.reason(), "SESSION_REPLACED");
    // The turn that called `fission` is the owner's own turn, so the interrupt
    // the host really offers (`ctx.abort`, then `AgentSession.abort` at the
    // turn's end) runs at that boundary — `agent_end` — and not before it. A
    // session still streaming at the receipt is the expected shape here; what
    // must never happen is a claim that the turn stopped when it did not.
    assert.equal(session.calls.some(([kind]) => kind === "abort"), false);
    // The adapter drains that armed abort at the turn's own boundary
    // (`agent_end`, `index.mjs`), and it runs there — once.
    assert.equal(build.adapter.session.control.drainTurnEnd(), 1);
    assert.equal(session.calls.some(([kind]) => kind === "abort"), true);
    // A second boundary runs nothing: the mark refuses every later turn, so one
    // abort is the whole obligation.
    assert.equal(build.adapter.session.control.drainTurnEnd(), 0);
    session.endTurn("done");
    // The marked session still produces no turn of its own, whatever it reports.
    assert.equal(build.adapter.session.control.reason(), "SESSION_REPLACED");
    // The group is registered, and the owner's roster holds none of the lanes.
    assert.notEqual(build.adapter.session.fission.groupOf("w1"), null);
    assert.deepEqual(lanes.names(), []);
  });
});

test("a fission whose owner exposes no control surface is refused, not claimed", async () => {
  // The one refusal that stays: with no surface there is no mark, so nothing
  // stops the old present and the group must be rolled back.
  const lanes = new Map();
  const port = fissionPort({
    lanes,
    startLane: async () => ({ ok: true }),
    agentFor: () => "coder",
    owner: () => "w1",
    present: null,
  });
  const replaced = await port.replacePresent({ owner: "w1" });
  assert.equal(replaced.ok, false);
  assert.equal(replaced.reason, "PRESENT_NOT_REPLACED");
});

test("a fission whose replacement fails rolls the whole group back and is never ok", async () => {
  // A rollback receipt is not a success. When the present cannot be replaced —
  // a control surface that refuses to mark — the lanes the group already started
  // must be released and the program must fail by name, never answer `ok: true`
  // for a replacement that did not happen.
  await withRoot(async () => {
    const owner = fakeSession({ agentId: "w1" });
    owner.agent = { kind: "sub", id: "w1", name: "coder", depth: 1, parentId: "Main" };
    owner.sessionManager = { getSessionId: () => "w1-session", getEntries: () => [] };
    const laneSessions = [fakeSession({ agentId: "lane-a" }), fakeSession({ agentId: "lane-b" })];
    const api = fakeApi(laneSessions);
    const lanes = trackedLaneTable(new LaneTable({ api }));
    const host = new testSession({ pi: { registerTool: () => {} }, api, role: ROLE.CODER, lanes });
    host.bindSession({ session: owner });
    // The one thing that fails: the mark. Everything else is the real port.
    const control = host.control;
    control.replace = () => ({ ok: false, reason: "MARK_REFUSED" });

    await assert.rejects(() => host.fission.fission("w1", ["a", "b"]), (error) => {
      // The failure names the step, and the rollback really released both lanes.
      assert.equal(error.step, "replacePresent");
      assert.equal(error.code, "PRESENT_NOT_REPLACED");
      assert.deepEqual(error.rollback.released, ["w1:2:1#0", "w1:2:1#1"]);
      assert.deepEqual(error.rollback.failed, []);
      return true;
    });
    // No group is registered, the owner keeps its present, and every lane's
    // session was disposed — a started lane left running would be a present
    // nobody replaced.
    assert.equal(host.fission.groupOf("w1"), null);
    assert.equal(host.control.replaced, null);
    for (const lane of laneSessions) {
      assert.equal(lane.calls.some(([kind]) => kind === "dispose"), true, `${lane.getAgentId()} released`);
    }
    assert.equal(host.lane("w1:2:1#0"), null);
  });
});

test("a pending tool call is never dropped from an assistant message the model already read", () => {
  // The host hands the request over with the turn's assistant message already
  // holding a call whose result has not arrived. Scope keeps the call and has no
  // result to pair it with; the message must therefore still carry the call, or
  // the next request (which carries it again) is a prefix drift (§6.1).
  const turn = {
    role: "assistant",
    content: [
      { type: "text", text: "go" },
      { type: "tool-call", toolName: "js-worker", toolCallId: "c9", input: {} },
    ],
  };
  const messages = [{ role: "user", content: "start" }, turn];
  const items = toItems(messages);
  // The projection keeps the prose and the call, and nothing else.
  const projected = items.filter((item) => item.kind !== "thinking");
  const kept = selectKept(messages, projected);
  assert.deepEqual(kept, messages);
  assert.deepEqual(kept[1].content, turn.content, "the call and its order survive");

  // A call the projection dropped is dropped from the message too: the copy
  // never carries a call nobody kept, and the message's own identity is kept
  // when nothing had to change.
  const dropped = selectKept(messages, [items[0]]);
  assert.deepEqual(dropped, [messages[0]]);

  const twoCalls = {
    role: "assistant",
    content: [
      { type: "text", text: "go" },
      { type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: {} },
      { type: "tool-call", toolName: "js-worker", toolCallId: "c2", input: {} },
    ],
  };
  const both = [{ role: "user", content: "start" }, twoCalls];
  const keptItems = toItems(both).filter((item) => item.callId !== "c2");
  const one = selectKept(both, keptItems);
  // A kept message is kept WHOLE. Scope decides which MESSAGES survive, never
  // which parts of one message: the model already read this message, and
  // re-emitting it without `c2` rewrites it (§6.1). That is the opposite of the
  // rule this case asserted before — the assertion was wrong, not the code:
  // dropping a call here is exactly the drift the prefix check exists to stop.
  assert.deepEqual(
    one[1].content.map((part) => part.type),
    ["text", "tool-call", "tool-call"],
    "a kept message keeps every call it had",
  );
  assert.deepEqual(
    one[1].content.map((part) => part.toolCallId).filter(Boolean),
    ["c1", "c2"],
  );
  // A call whose RESULT the projection dropped still leaves with it: half a
  // pair is not a request.
  const bothWithResults = [
    { role: "user", content: "start" },
    twoCalls,
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c1", output: { type: "text", value: "r1" } }] },
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c2", output: { type: "text", value: "r2" } }] },
  ];
  const withoutSecond = selectKept(
    bothWithResults,
    toItems(bothWithResults).filter((item) => item.callId !== "c2"),
  );
  assert.equal(withoutSecond.length, 3, "the second result left, its call stayed");
  assert.equal(withoutSecond[2].content[0].toolCallId, "c1");
});

// ---------------------------------------------------------------------------
// One physical review call books once (根 §2.4 第 6 步)
// ---------------------------------------------------------------------------
//
// The host may deliver the same tool call twice: a retried request, a replayed
// execution, a transcript it rebuilt. `callId` is the identity of the PHYSICAL
// call, so the second arrival answers with the receipt the first produced —
// same tenure, same milestone, same obligations — and performs no side effect a
// second time.
//
// The surface reaches the session's `review`, so the assertion is on the real
// method with the id the tool handler supplies. The falsification below is the
// half that matters: with no id the adapter cannot tell two arrivals apart, and
// that is exactly why the handler must forward the host's identity.

const reviewSession = (sessionId) => {
  const ctx = {
    agent: { kind: "main", id: "Main", name: "main", depth: 0 },
    sessionManager: { getSessionId: () => sessionId, getEntries: () => [] },
  };
  return new testSession({ pi: { registerTool: () => {} }, api: null, ctx, role: ROLE.MANAGER });
};

test("a review delivered twice with one callId books once and replays its own receipt", async () => {
  await withRoot(async () => {
    const session = reviewSession(`sess-replay-${Date.now().toString(36)}`);
    session.state.window.beginRound();
    const findings = [{ key: "goal", value: "ship it" }];
    const first = await session.review(findings, null, "call-review-1");
    assert.equal(first.ok, true, JSON.stringify(first));
    assert.equal(first.replayed, undefined, "the first arrival is a real review");
    const tenure = first.tenure;
    const milestone = first.milestone;
    const obligations = first.obligations;

    const again = await session.review(findings, null, "call-review-1");
    assert.equal(again.ok, true, JSON.stringify(again));
    assert.equal(again.replayed, true, "the second arrival is a replay");
    assert.equal(again.tenure, tenure, "the receipt names the tenure it judged");
    assert.equal(again.milestone, milestone, "the receipt names the milestone it judged");
    assert.equal(again.obligations, obligations, "the receipt carries the obligations it produced");
    // A replay is not a second booking: the ledger did not move.
    assert.equal(session.state.window.state, "closed", "the round was shut by the first booking only");
    const third = await session.review(findings, null, "call-review-1");
    assert.equal(third.replayed, true, "a call stays replayable however often it arrives");
    assert.equal(third.tenure, tenure);
  });
});

test("a review with no callId is not replayable (falsification of the identity contract)", async () => {
  await withRoot(async () => {
    const session = reviewSession(`sess-replay-none-${Date.now().toString(36)}`);
    session.state.window.beginRound();
    const first = await session.review([], null, null);
    const second = await session.review([], null, null);
    assert.equal(first.replayed, undefined);
    assert.equal(second.replayed, undefined, "without an id the adapter books again");
  });
});

test("a review replays even after the ledger moved on, and the payload must match", async () => {
  await withRoot(async () => {
    const session = reviewSession(`sess-replay-late-${Date.now().toString(36)}`);
    session.state.window.beginRound();
    const first = await session.review([{ key: "a", value: "x" }], null, "call-a");
    const original = { tenure: first.tenure, milestone: first.milestone, obligations: first.obligations };
    // A later, different call moves the ledger.
    await session.review([{ key: "b", value: "y" }], null, "call-b");
    // The first call arrives again: it answers with ITS OWN receipt, not a
    // description of the ledger now (§2.4 第 6 步).
    const replay = await session.review([{ key: "a", value: "x" }], null, "call-a");
    assert.equal(replay.replayed, true);
    assert.deepEqual(
      { tenure: replay.tenure, milestone: replay.milestone, obligations: replay.obligations },
      original,
    );
    // The same id with a different payload is a caller bug, refused, and it
    // books nothing.
    const mismatch = await session.review([{ key: "a", value: "CHANGED" }], null, "call-a");
    assert.equal(mismatch.ok, false);
    assert.equal(mismatch.rejection, "REPLAY_MISMATCH");
  });
});

// ---------------------------------------------------------------------------
// A refused request leaves a durable record (incident of 2026-11-01)
// ---------------------------------------------------------------------------

// A `context` handler that throws does not refuse a request on this host, so
// every failure path here decides a copy and settles a verdict. What it also
// must do — and what a live session proved it once did not — is leave a record:
// a refusal nobody can read afterwards is a refusal nobody can diagnose.
test("a clean request writes no line, and a refused one writes a bounded record", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "herd-record-"));
  const sessionId = "ses_record";
  const target = requestLogFile(root, sessionId);
  const written = [];
  const clean = createContextHandler({
    // Scope keeps only the operator's first message: a prune the chain may
    // re-base on. The record must still show the copy the host handed over.
    scopeProject: async ({ items }) => ({ ok: true, messages: [items[0]] }),
    pairHintProject: async (current) => current,
    requestGate: { open: () => true, settle: () => true, read: () => null },
    requestLog: ({ phase, incoming, presented, failure }) => {
      const record = requestRecord({ phase, sessionId, host: "18.14.2", incoming, presented, failure });
      written.push(record);
      return recordRequest(target, record);
    },
  });
  const messages = [
    { role: "user", content: "go" },
    { role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: {} }] },
  ];
  const out = await clean({ messages });
  assert.deepEqual(out.messages, [messages[0]], "Scope pruned the second message");
  assert.equal(written.length, 0, "a clean request is not a diagnostic");
  assert.equal(fs.existsSync(target), false, "and it leaves no file");

  // A failure is the one request that must leave a line.
  const failing = createContextHandler({
    scopeProject: async ({ items }) => ({ ok: true, messages: [items[0]] }),
    pairHintProject: async () => {
      throw Object.assign(new Error("PREFIX_DRIFT: a message already shown would change"), {
        code: "PREFIX_DRIFT",
        details: { firstDivergence: { index: 1, previousDigest: "aaa", nextDigest: "bbb" } },
      });
    },
    requestGate: { open: () => true, settle: () => true, read: () => null },
    requestLog: ({ phase, incoming, presented, failure }) => {
      const record = requestRecord({ phase, sessionId, host: "18.14.2", incoming, presented, failure });
      written.push(record);
      return recordRequest(target, record);
    },
  });
  await failing({ messages });
  assert.equal(written.length, 1, "a failed request leaves one record");
  const record = written[0];
  assert.equal(record.phase, "settled");
  assert.equal(record.sessionId, sessionId);
  assert.equal(record.length, 2);
  assert.equal(typeof record.root, "string");
  assert.deepEqual(record.firstDivergence, { index: 1, previousDigest: "aaa", nextDigest: "bbb" });
  assert.equal(record.failure.code, "PREFIX_DRIFT");
  assert.equal(record.dropped.length, 1, "the record names the message that left the copy");
  assert.deepEqual(record.dropped[0].calls, ["c1"], "and the call it carried");
  assert.ok(record.pair !== null, "the failure also records where the pair stood");
  // It is on disk, one line, and it carries no message text.
  const lines = fs.readFileSync(target, "utf8").split("\n").filter((line) => line.trim() !== "");
  assert.equal(lines.length, 1);
  assert.equal(lines[0].includes("go"), false, "no message text reaches the log");
  assert.equal(JSON.parse(lines[0]).dropped.length, 1, "the durable line is the record that was decided");
  fs.rmSync(root, { recursive: true, force: true });
});

test("a projection failure is recorded with the stage and code that caused it", async () => {
  // The gate is shut by a Scope refusal, and the copy that goes out is the
  // smallest one. Both facts have to be readable afterwards: which stage failed,
  // and what the copy was reduced to.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "herd-record-fail-"));
  const written = [];
  const handler = createContextHandler({
    scopeProject: async () => ({ ok: false, code: "SCOPE_BUDGET_UNKNOWN", message: "no resetScope yet" }),
    pairHintProject: async (current) => current,
    requestGate: { open: () => true, settle: () => true, read: () => null },
    requestLog: ({ phase, incoming, presented, failure }) => {
      written.push(requestRecord({ phase, sessionId: "ses_fail", host: "18.14.2", incoming, presented, failure }));
      return true;
    },
  });
  const messages = [
    { role: "user", content: "first" },
    { role: "assistant", content: [{ type: "text", text: "middle" }] },
    { role: "user", content: "latest" },
  ];
  const out = await handler({ messages });
  assert.equal(written.length, 1, "a failed request is recorded too — that is when it matters most");
  const record = written[0];
  assert.equal(record.allowed, false);
  assert.equal(record.failure.stage, "scope");
  assert.equal(record.failure.code, "SCOPE_BUDGET_UNKNOWN");
  // The copy really was reduced to the operator's latest message plus the
  // notice, so the record's `dropped` names what the failure removed.
  assert.ok(record.length <= out.messages.length + 1, "the record names the request length, not the reduced copy");
  assert.ok(record.dropped.length >= 1, "the failed request says what it did not send");
  fs.rmSync(root, { recursive: true, force: true });
});

test("a clean side turn writes no line and settles no gate", async () => {
  // A title/card request is the host's own UI turn. It is a request, so it is
  // recorded; it is not the conversation, so it must not open or settle the
  // gate the tools read.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "herd-record-side-"));
  let opened = 0;
  let settled = 0;
  const written = [];
  const handler = createContextHandler({
    scopeProject: async ({ items }) => ({ ok: true, messages: items }),
    pairHintProject: async (current) => current,
    sideTurn: () => true,
    requestGate: {
      open: () => {
        opened += 1;
        return true;
      },
      settle: () => {
        settled += 1;
        return true;
      },
      read: () => null,
    },
    requestLog: ({ phase, incoming, presented, failure }) => {
      written.push(requestRecord({ phase, sessionId: "ses_side", incoming, presented, failure }));
      return true;
    },
  });
  const messages = [
    { role: "user", content: "go" },
    { role: "developer", content: [{ type: "text", text: "side channel" }], attribution: "agent" },
    { role: "user", content: [{ type: "text", text: "<title-request>" }], attribution: "agent" },
  ];
  await handler({ messages });
  assert.equal(written.length, 0, "a clean side turn is not a diagnostic");
  assert.equal(opened, 0, "a side turn does not open the gate");
  assert.equal(settled, 0, "a side turn does not settle the gate");
  fs.rmSync(root, { recursive: true, force: true });
});

// The incident of 2026-11-01 (turn 1, 54 tool results, 233 s): the host stream
// reported `CONTEXT_PROJECTION_FAILED`, every gate file said `allowed: true`,
// and no `.requests.jsonl` line carried the refusal.
//
// Two defects met in that window, and the tests below pin both:
//
//   - the VERDICT was the session's, so a projection in flight for ANOTHER
//     request refused a call that an already-projected request had issued;
//   - a refusal left no line, so by the time anybody looked the next request had
//     settled `allowed: true` and nothing said a call had ever been stopped.
//
// The host fact that makes a per-request verdict possible: OMP's `tool_call`
// event carries no request id (`extensibility/extensions/types.ts`
// `ToolCallEventBase` — `toolName`, `toolCallId`, `input`), and its
// `turn_start`/`turn_end` are drained through an async queue that was measured
// emitting `turn_end 0` BEFORE turn 0's first `context`. What the host cannot
// reorder is the request itself: a tool call exists only for a response the
// provider produced, and a response only exists after its request was projected
// and sent. So a call is judged by the last SETTLED request.
const requestGateStub = () => {
  let verdict = null;
  let seq = 0;
  return {
    get verdict() {
      return verdict;
    },
    gate: {
      open: (id) => {
        seq += 1;
        const previous =
          verdict === null || verdict.settled !== true
            ? null
            : { request: verdict.request, allowed: verdict.allowed === true, stage: verdict.stage ?? null, code: verdict.code ?? null };
        verdict = { v: 3, request: id ?? `req-${seq}`, settled: false, previous, allowed: false, stage: "request", code: "CONTEXT_PROJECTION_PENDING" };
        return true;
      },
      settle: (failure, id) => {
        const previous = verdict?.request === id ? verdict.previous : null;
        verdict =
          failure === null
            ? { v: 3, request: id, settled: true, previous, allowed: true }
            : { v: 3, request: id, settled: true, previous, allowed: false, stage: failure.stage, code: failure.code };
        return true;
      },
      // The same rule the session state applies: the last SETTLED request
      // answers, and a request still projecting does not speak for a call.
      read: () => {
        if (verdict === null) {
          return { code: "CONTEXT_PROJECTION_FAILED", message: "no request has been projected (CONTEXT_PROJECTION_UNKEYED)" };
        }
        const settled = verdict.settled === true ? verdict : verdict.previous ?? null;
        if (settled === null) {
          return { code: "CONTEXT_PROJECTION_FAILED", message: `request ${String(verdict.request)} is still open (CONTEXT_PROJECTION_PENDING)` };
        }
        if (settled.allowed !== false) return null;
        return { code: "CONTEXT_PROJECTION_FAILED", message: `the ${settled.stage} projection of request ${settled.request} failed (${settled.code})` };
      },
    },
  };
};

test("a side-channel projection held open never refuses a main-turn tool call", async () => {
  // The side turn is the host's own title/card request, fired through this same
  // `context` hook while the main turn streams. It is not a request of this
  // tenure: it must not open, settle, or move any verdict. A main-turn call whose
  // own request projected is admitted while that side projection is in flight.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "herd-gate-side-"));
  const target = requestLogFile(root, "ses_side_gate");
  const stub = requestGateStub();
  let release = null;
  const held = new Promise((resolve) => {
    release = resolve;
  });
  const handler = createContextHandler({
    scopeProject: async ({ items }) => ({ ok: true, messages: items }),
    pairHintProject: async (current) => {
      // Only the SIDE turn is held open: the main turn's request has already
      // settled, which is the state a long turn spends its time in.
      if (isSideChannelRequest(current)) await held;
      return current;
    },
    sideTurn: (messages) => isSideChannelRequest(messages),
    requestGate: stub.gate,
    requestLog: ({ phase, incoming, presented, failure }) =>
      recordRequest(target, requestRecord({ phase, sessionId: "ses_side_gate", incoming, presented, failure })),
    onGate: ({ phase, verdict }) =>
      recordRequest(target, gateStateRecord({ phase, sessionId: "ses_side_gate", verdict })).ok === true,
  });
  const gate = createToolCallHandler({
    role: ROLE.MANAGER,
    scopeAdmit: async () => ({ ok: true }),
    requestId: () => stub.verdict?.request ?? null,
    requestRefusal: () => handler.requestRefusal(),
    refusalLog: ({ request, tool, reason }) =>
      recordRequest(
        target,
        gateStateRecord({ phase: "refusal", sessionId: "ses_side_gate", verdict: { request, allowed: false, code: reason.split(":")[0] }, trigger: tool }),
      ),
  });
  const main = [
    { role: "user", content: "go" },
    { role: "assistant", content: [{ type: "tool-call", toolName: "js-manager", toolCallId: "c1", input: {} }] },
  ];
  const side = [
    ...main,
    { role: "developer", content: [{ type: "text", text: "side channel" }], attribution: "agent" },
    { role: "user", content: [{ type: "text", text: "<title-request>" }], attribution: "agent" },
  ];
  // The main turn's request projects and settles `allowed`.
  await handler({ messages: main });
  assert.equal(handler.requestRefusal(), null, "the main request projected");
  // The side turn starts and holds. It is a request in flight, not a verdict.
  const sideRun = handler({ messages: side });
  await new Promise((resolve) => setTimeout(resolve, 0));
  // A main-turn tool call arrives now, from the main request's response.
  assert.equal(await gate({ toolName: "js-manager", toolCallId: "c1", input: {} }), undefined, "the call is admitted while a side projection is open");
  release();
  await sideRun;
  assert.equal(await gate({ toolName: "js-manager", toolCallId: "c2", input: {} }), undefined, "and still admitted after it");
  assert.equal(stub.verdict.settled, true, "the side turn moved no verdict");
  assert.equal(stub.verdict.allowed, true, "the main request's verdict still stands");
  const gates = fs
    .readFileSync(target, "utf8")
    .split("\n")
    .filter((line) => line.trim() !== "")
    .map((line) => JSON.parse(line))
    .filter((line) => line.kind === "gate");
  assert.equal(gates.length, 1, "only the main request's changed verdict was recorded; the side turn wrote none");
  fs.rmSync(root, { recursive: true, force: true });
});

test("a projection in flight for another request never refuses the settled one's calls", async () => {
  // Live steering rewrites the provider context mid-response
  // (`agent-loop.ts` `openLiveSteering.toProvider` -> `transformContext`), so a
  // `context` call for the next request can overlap the current one's tool
  // batch. The current request's settled verdict is what judges its calls.
  const stub = requestGateStub();
  let release = null;
  const held = new Promise((resolve) => {
    release = resolve;
  });
  let first = true;
  const handler = createContextHandler({
    scopeProject: async ({ items }) => ({ ok: true, messages: items }),
    pairHintProject: async (current) => {
      if (!first) await held;
      first = false;
      return current;
    },
    requestGate: stub.gate,
  });
  const gate = createToolCallHandler({
    role: ROLE.MANAGER,
    scopeAdmit: async () => ({ ok: true }),
    requestId: () => stub.verdict?.request ?? null,
    requestRefusal: () => handler.requestRefusal(),
  });
  const messages = [
    { role: "user", content: "go" },
    { role: "assistant", content: [{ type: "tool-call", toolName: "js-manager", toolCallId: "c1", input: {} }] },
  ];
  await handler({ messages });
  assert.equal(await gate({ toolName: "js-manager", toolCallId: "c1", input: {} }), undefined);
  // The next request starts projecting while the previous response's remaining
  // calls are still arriving.
  const next = handler({ messages });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(await gate({ toolName: "js-manager", toolCallId: "c2", input: {} }), undefined, "the settled request still answers");
  release();
  await next;
  assert.equal(handler.requestRefusal(), null, "and the next request projects too");
});

test("a call from a request that failed projection is refused and recorded", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "herd-gate-failed-"));
  const target = requestLogFile(root, "ses_gate_failed");
  const stub = requestGateStub();
  const handler = createContextHandler({
    scopeProject: async ({ items }) => ({ ok: true, messages: items }),
    pairHintProject: async () => {
      throw Object.assign(new Error("chain refused at index 1"), { code: "PREFIX_DRIFT" });
    },
    requestGate: stub.gate,
    requestLog: ({ phase, incoming, presented, failure }) =>
      recordRequest(target, requestRecord({ phase, sessionId: "ses_gate_failed", incoming, presented, failure })),
    onGate: ({ phase, verdict }) =>
      recordRequest(target, gateStateRecord({ phase, sessionId: "ses_gate_failed", verdict })).ok === true,
  });
  const gate = createToolCallHandler({
    role: ROLE.MANAGER,
    scopeAdmit: async () => ({ ok: true }),
    requestId: () => stub.verdict?.request ?? null,
    requestRefusal: () => handler.requestRefusal(),
    refusalLog: ({ request, tool, reason }) =>
      recordRequest(
        target,
        gateStateRecord({ phase: "refusal", sessionId: "ses_gate_failed", verdict: { request, allowed: false, code: reason.split(":")[0] }, trigger: tool }),
      ),
  });
  const messages = [
    { role: "user", content: "go" },
    { role: "assistant", content: [{ type: "text", text: "working" }] },
  ];
  await handler({ messages });
  const blocked = await gate({ toolName: "js-manager", toolCallId: "c1", input: {} });
  assert.equal(blocked.block, true, "the call is refused: its own request failed projection");
  assert.match(blocked.reason, /^CONTEXT_PROJECTION_FAILED/);
  assert.match(blocked.reason, /PREFIX_DRIFT/);
  const records = fs
    .readFileSync(target, "utf8")
    .split("\n")
    .filter((line) => line.trim() !== "")
    .map((line) => JSON.parse(line));
  const refusal = records.find((line) => line.phase === "refusal");
  assert.ok(refusal !== undefined, "the refusal is durable");
  assert.equal(refusal.trigger, "js-manager");
  assert.equal(refusal.code, "CONTEXT_PROJECTION_FAILED");
  assert.ok(typeof refusal.request === "string" && refusal.request.length > 0, "the record names the request");
  assert.ok(records.some((line) => line.kind === "gate" && line.phase === "settle" && line.code === "PREFIX_DRIFT"));
  fs.rmSync(root, { recursive: true, force: true });
});

test("a refused tool call leaves its own line, and an open gate leaves none", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "herd-gate-refusal-"));
  const target = requestLogFile(root, "ses_refusal");
  const refused = [];
  const call = createToolCallHandler({
    role: ROLE.MANAGER,
    requestRefusal: () => ({ code: "CONTEXT_PROJECTION_FAILED", message: "the request projection failed" }),
    refusalLog: ({ tool, reason }) =>
      refused.push(gateStateRecord({ phase: "refusal", sessionId: "ses_refusal", trigger: tool, verdict: { allowed: false, code: reason.split(":")[0] } })),
  });
  const blocked = await call({ toolName: "js-manager", toolCallId: "c1", input: {} });
  assert.equal(blocked.block, true);
  assert.equal(refused.length, 1, "the refusal is recorded at the moment the model is stopped");
  assert.equal(refused[0].kind, "gate");
  assert.equal(refused[0].phase, "refusal");
  assert.equal(refused[0].trigger, "js-manager");
  assert.equal(refused[0].code, "CONTEXT_PROJECTION_FAILED");
  for (const record of refused) recordRequest(target, record);
  const durable = fs.readFileSync(target, "utf8");
  assert.equal(durable.includes("c1"), false, "the record names the tool, never the call's arguments");

  // An open gate refuses nothing and records nothing.
  let opened = 0;
  const open = createToolCallHandler({
    role: ROLE.MANAGER,
    requestRefusal: () => null,
    refusalLog: () => {
      opened += 1;
    },
  });
  assert.equal(await open({ toolName: "js-manager" }), undefined);
  assert.equal(opened, 0);
  fs.rmSync(root, { recursive: true, force: true });
});

test("a gate record that cannot be written is stated in the request it refuses", async () => {
  // The last resort. Both files refused the line, so the copy itself says the
  // diagnostic is missing: a reader of `.requests.jsonl` must not be able to
  // conclude "nothing happened".
  const written = [];
  const handler = createContextHandler({
    scopeProject: async ({ items }) => ({ ok: true, messages: items }),
    pairHintProject: async () => {
      throw Object.assign(new Error("chain refused"), { code: "PREFIX_DRIFT" });
    },
    requestGate: { open: () => true, settle: () => true, read: () => null },
    requestLog: ({ phase, incoming, presented, failure }) => {
      written.push(requestRecord({ phase, sessionId: "ses_unrecorded", incoming, presented, failure }));
      return true;
    },
    onGate: () => false,
  });
  const messages = [
    { role: "user", content: "go" },
    { role: "assistant", content: [{ type: "text", text: "working" }] },
  ];
  const out = await handler({ messages });
  const texts = out.messages.map((message) => (Array.isArray(message.content) ? message.content.map((part) => part.text ?? "").join("") : String(message.content)));
  assert.ok(
    texts.some((text) => text.includes("could not be written to the session's `.requests.jsonl`")),
    "the copy names the missing diagnostic",
  );
  assert.equal(written.length, 1, "the request record is still attempted");
  assert.equal(written[0].failure.code, "PREFIX_DRIFT", "and it still carries the real failure");
});

test("a request record that cannot be written leaves a named fallback record", async () => {
  // The incident of 01a1958f: a request was refused and the state directory
  // held nothing, so the refusal had to be explained from the host's own
  // database. The state directory is only one directory; when it refuses the
  // line, the line goes to the nearest ancestor that takes it, and it says
  // which file it was meant for and why it failed.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "herd-record-unwritable-"));
  // A regular file where the state directory must be: `mkdirSync` fails with
  // EEXIST, the append beneath it with ENOTDIR, and the parent (the temp root)
  // still exists — which is where the line has to land.
  fs.writeFileSync(path.join(root, ".Herdchestra"), "not a directory");
  const sessionId = "ses_unwritable";
  const target = requestLogFile(root, sessionId);
  const record = requestRecord({
    phase: "settled",
    sessionId,
    host: "18.14.2",
    incoming: [{ role: "user", content: "go" }],
    presented: [{ role: "user", content: "go" }],
    failure: { stage: "pair hint", code: "PREFIX_DRIFT", detail: "a message already shown would change" },
  });
  const outcome = recordRequest(target, record);

  assert.equal(outcome.ok, false, "the session's own file did not take the line");
  assert.equal(typeof outcome.file, "string", "and the line landed somewhere that can be read");
  assert.equal(path.dirname(outcome.file), root, "in the nearest directory that accepts it");
  assert.equal(outcome.failure.code, "EEXIST", "the filesystem failure is named, not swallowed");
  assert.equal(outcome.failure.target, target, "beside the file it was meant for");

  const lines = fs.readFileSync(outcome.file, "utf8").split("\n").filter((line) => line.trim() !== "");
  assert.equal(lines.length, 1, "one line, the record");
  const fallback = JSON.parse(lines[0]);
  assert.equal(fallback.allowed, false, "the refusal the request decided is still there");
  assert.equal(fallback.failure.code, "PREFIX_DRIFT");
  assert.equal(fallback.writeFailure.code, "EEXIST", "the failed write is named in the record it kept");
  assert.equal(fallback.writeFailure.target, target);
  assert.equal(lines[0].includes("\"go\""), false, "still no message text, in either record");
  fs.rmSync(root, { recursive: true, force: true });
});

test("a refused request whose journal and state directory cannot be written still leaves a named record", async () => {
  // The whole incident of 01a1958f in one request: the Pair Hint refuses, its
  // journal cannot be written, the gate cannot be written, and the state
  // directory itself is gone. A refusal that leaves no line anywhere is the
  // state that had to be explained from the host's own database — the file that
  // holds the operator's words. So the record must survive every one of those
  // failures, and it must name them.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "herd-record-gate-"));
  fs.writeFileSync(path.join(root, ".Herdchestra"), "not a directory");
  const sessionId = "ses_gate_fail";
  const target = requestLogFile(root, sessionId);
  const written = [];
  const journalFailure = { code: "EACCES", detail: "the journal refused the line", target: "journal.jsonl" };
  const handler = createContextHandler({
    scopeProject: async ({ items }) => ({ ok: true, messages: items }),
    pairHintProject: async () => {
      // What a Pair Hint does when its journal write fails: it refuses the
      // request with the write's failure attached (a refusal it cannot record
      // is not a comparison it can prove).
      throw Object.assign(new Error("a message already shown would change"), {
        code: "PREFIX_DRIFT",
        detail: journalFailure,
      });
    },
    // Both gate writes fail: the refusal can only live in memory, and the file
    // that would have carried it never does.
    requestGate: { open: () => false, settle: () => false, read: () => null },
    // The production shape: the handler retries once with `writeFailure` when
    // the writer says the line did not land.
    requestLog: ({ phase, incoming, presented, failure, writeFailure = null }) => {
      const record = requestRecord({ phase, sessionId, host: "18.14.2", incoming, presented, failure });
      const outcome = recordRequest(target, writeFailure === null ? record : { ...record, writeFailure });
      // Counted only when the line was meant for the session's own file: the
      // fallback line is the same request, kept where it could be kept.
      if (writeFailure === null) written.push({ phase, incoming, presented, failure, outcome });
      return outcome;
    },
  });
  const messages = [{ role: "user", content: "go" }];
  const out = await handler({ messages });

  assert.equal(written.length, 1, "the refused request is recorded exactly once");
  assert.equal(written[0].failure.stage, "request", "the gate failure is the one that shut it");
  assert.equal(written[0].failure.code, "REQUEST_GATE_UNWRITABLE");
  assert.equal(written[0].outcome.ok, false, "the session's own file refused the line");
  // The copy the model reads names the failure, so the model cannot claim the
  // request was projected.
  const notice = out.messages.find((message) => typeof message.content === "string" && message.content.startsWith("[herdchestra]"));
  assert.ok(notice !== undefined, "the copy carries the failure notice");
  assert.match(notice.content, /REQUEST_GATE_UNWRITABLE/);

  // The durable line exists even though the state directory does not.
  const fallback = fs.readdirSync(root).filter((name) => name.endsWith(".fallback"));
  assert.equal(fallback.length, 1, "the record landed in the nearest directory that accepts it");
  const lines = fs.readFileSync(path.join(root, fallback[0]), "utf8").split("\n").filter((line) => line.trim() !== "");
  assert.equal(lines.length, 1, "one durable line");
  const kept = JSON.parse(lines[0]);
  assert.equal(kept.allowed, false, "and it says the request was refused");
  assert.equal(kept.failure.code, "REQUEST_GATE_UNWRITABLE");
  assert.equal(kept.writeFailure.code, "EEXIST", "the failed write is named in the line it kept");
  assert.equal(kept.writeFailure.target, target);
  fs.rmSync(root, { recursive: true, force: true });
});

// ---------------------------------------------------------------------------
// One process, two sessions: a refusal in one is never another's
// ---------------------------------------------------------------------------

// The factory registers one tool definition per face on `pi`. A test harness
// wants the `tool_call` hook those definitions carry, so it takes the hook off
// the definition instead of inventing one: the gate it asks is the gate the
// host would ask.
const toolCallHookOf = (pi, name) => {
  const tool = pi.tools.get(name);
  assert.equal(typeof tool?.execute, "function", `${name} is registered by the factory`);
  return tool.execute;
};

// The host calls the factory once per runner and hands each event to the
// handler it registered. This is that harness, in the two lines that matter.
const runnerFor = () => {
  const pi = {
    VERSION: "18.14.2",
    tools: new Map(),
    hooks: new Map(),
    registerTool(tool) {
      this.tools.set(tool.name, tool);
    },
    on(event, handler) {
      this.hooks.set(event, handler);
    },
    getActiveTools: () => [],
    setActiveTools: async () => {},
  };
  herdchestra(pi);
  return pi;
};

const hostSession = (sessionId) => ({
  agent: { kind: "main", id: sessionId, name: "manager", depth: 0 },
  sessionManager: { getSessionId: () => sessionId, getEntries: () => [] },
});

// Shut one session's gate with the verdict a real Pair Hint refusal settles,
// written where `SessionState#settleContextRequest` writes it. The refusal
// itself is exercised by its own tests; what these two tests need is a session
// whose gate is CLOSED, so that a borrowed verdict is visible.
// A gate file that refuses `request`, in the per-request model the adapter
// writes (`session-state.mjs` `requestRefusal`): a verdict speaks for exactly
// one request, and the last SETTLED one answers a call.
const shutGate = (root, sessionId, request = "1") =>
  fs.writeFileSync(
    path.join(root, ".Herdchestra", `${safeSessionName(sessionId)}.request.json`),
    JSON.stringify({
      v: 3,
      sessionId,
      request,
      settled: true,
      previous: null,
      allowed: false,
      stage: "pair hint",
      code: "PREFIX_DRIFT",
      detail: "a message already shown would change",
    }),
  );

// The incident of 2026-11-01 (13:22-13:24, one omp process): four sessions, three
// that bound and left files, one that did not. The one that did not had no gate
// of its own — and its calls were refused with a sibling's `PREFIX_DRIFT`.
test("a refusal in one session never blocks another session in the same process", async () => {
  await withRoot(async (root) => {
    const refusedId = "01a1958e-refused";
    const cleanId = "01a1958f-unbound";
    const pi = runnerFor();
    const bind = async (sessionId) => {
      const ctx = hostSession(sessionId);
      await pi.hooks.get("session_start")({ type: "session_start" }, ctx);
      return ctx;
    };
    const call = (ctx, toolName) => pi.hooks.get("tool_call")({ toolName, input: {} }, ctx);
    // The host's turn boundary: a request belongs to the turn it opens
    // (`agent-loop.ts`), and the gate keys its verdict by it.
    const startTurn = (ctx, turnIndex) =>
      pi.hooks.get("turn_start")({ type: "turn_start", turnIndex, timestamp: Date.now() }, ctx);

    // Session A: bound, with a refused request on disk.
    const aCtx = await bind(refusedId);
    await startTurn(aCtx, 0);
    await pi.hooks.get("context")({ messages: [{ role: "user", content: "go" }] }, aCtx);
    shutGate(root, refusedId);
    const aBlocked = await call(aCtx, "read");
    assert.match(aBlocked.reason, /CONTEXT_PROJECTION_FAILED/, "A is refused by its own gate");
    assert.match(aBlocked.reason, /PREFIX_DRIFT/);

    // Session B: the same process, its own adapter, its own state. Nothing ever
    // decided a request for it, so it has no gate file and must inherit none.
    const bCtx = await bind(cleanId);
    assert.equal(fs.existsSync(path.join(root, ".Herdchestra", `${safeSessionName(cleanId)}.request.json`)), false);
    await startTurn(bCtx, 0);
    // B's own request projects before B acts: a call from a request that has
    // not projected is refused by its OWN gate, which is the fail-closed rule
    // and says nothing about A.
    await pi.hooks.get("context")({ messages: [{ role: "user", content: "go" }] }, bCtx);
    const bBlocked = await call(bCtx, "read");
    assert.equal(bBlocked?.block, true, "a tool outside B's face is refused for its own reason");
    assert.equal(bBlocked.reason.includes("CONTEXT_PROJECTION_FAILED"), false, `B inherited A's gate: ${bBlocked.reason}`);
    assert.equal(bBlocked.reason.includes("PREFIX_DRIFT"), false);

    // B's own request leaves B's own records, and only B's. One request writes
    // the gate's open, the gate's settle and the request itself (`drift-log.mjs`
    // `gateStateRecord`/`requestRecord`); the counts are the point, not the
    // number three.
    const readLines = (sessionId) =>
      fs
        .readFileSync(requestLogFile(root, sessionId), "utf8")
        .split("\n")
        .filter((line) => line.trim() !== "")
        .map((line) => JSON.parse(line));
    const bRecords = readLines(cleanId);
    assert.deepEqual(
      bRecords.map((record) => `${record.kind}/${record.phase}`),
      ["gate/settle"],
      "B wrote exactly its own request's records",
    );
    assert.ok(
      bRecords.every((record) => record.sessionId === cleanId),
      "and every one of them is B's",
    );

    // A's own records were written by A's request and A's refused call, and B's
    // request did not touch them: the two sessions share a root and never a file.
    const aRecords = readLines(refusedId);
    assert.ok(
      aRecords.every((record) => record.sessionId === refusedId),
      "A's file holds A's records only",
    );
    assert.ok(
      aRecords.some((record) => record.kind === "gate" && record.phase === "refusal" && record.trigger === "read"),
      "A's refused call left its own line",
    );
    // Isolation cuts both ways: A's gate still refuses A.
    const aStill = await call(aCtx, "read");
    assert.match(aStill.reason, /PREFIX_DRIFT/);
  });
});

test("a call whose session never bound is refused by name, never judged by another session's gate", async () => {
  // The incident's shape: a call whose session this process never bound. The
  // old resolution fell back to the adapter that built the tool definition —
  // whichever session happened to own it — so that session's refusal answered
  // for this one, and a program call built its surface on that session. The
  // refusal now comes from the tool definitions themselves, which is the path
  // the host actually executes, and it names the cause.
  await withRoot(async (root) => {
    const boundId = "01a1958e-bound";
    const pi = runnerFor();
    const boundCtx = hostSession(boundId);
    await pi.hooks.get("session_start")({ type: "session_start" }, boundCtx);
    await pi.hooks.get("context")({ messages: [{ role: "user", content: "go" }] }, boundCtx);
    shutGate(root, boundId);
    const durable = JSON.parse(fs.readFileSync(path.join(root, ".Herdchestra", `${safeSessionName(boundId)}.request.json`), "utf8"));
    assert.equal(durable.allowed, false, "the bound session really is refused");
    // The bound session is registered, so a call it makes reaches its gate.
    assert.notEqual(adapterForCall(boundCtx), null);

    // A session this process has never seen. The id must be one nothing
    // registered: `bindSession` also registers under `agent-<agent.id>` (a
    // session with no manager id), so reusing that shape here would bind.
    const ghost = "01a1958f-never-bound";
    const stranger = { sessionManager: { getSessionId: () => ghost }, agent: { id: "ghost-agent" } };
    assert.equal(adapterForCall(stranger), null, "the ghost is unknown to this process");

    // The program face: no surface is built, and the refusal is its own.
    const program = await toolCallHookOf(pi, "js-manager")(
      "call-1",
      { source: "async run() { return 1; }" },
      undefined,
      undefined,
      stranger,
    );
    const text = program?.content?.[0]?.text ?? "";
    assert.match(text, /SESSION_UNBOUND/, `the program face refused by name: ${text.slice(0, 160)}`);
    assert.equal(text.includes("state:"), false, "no program was started");
    assert.equal(text.includes("PREFIX_DRIFT"), false, "never another session's verdict");

    // The worker port: no roster is borrowed either.
    const fork = await toolCallHookOf(pi, "fork")("call-2", { i: "x", name: "w", verboseIntent: "b" }, undefined, undefined, stranger);
    const forkText = fork?.content?.[0]?.text ?? "";
    assert.match(forkText, /SESSION_UNBOUND/, "the fork face refuses the same way");
    assert.equal(forkText.includes("PREFIX_DRIFT"), false);

    // A call with no session id at all is the same refusal, not a free pass.
    const anonymous = await toolCallHookOf(pi, "js-worker")(
      "call-3",
      { source: "async run() { return 1; }" },
      undefined,
      undefined,
      { agent: { id: "x" } },
    );
    assert.match(anonymous?.content?.[0]?.text ?? "", /SESSION_UNBOUND/);
  });
});

// The operator's stop is not a stop the agent earned (根 §2.2).
//
// Measured live on 18.14.2 (2026-11-01, session 01a1958e): the operator
// interrupted the first turn, the host wrote `stopReason: "aborted"` with
// `errorMessage: "Interrupted by user"` on the turn's message
// (`agent-session.ts:11240`), and the idle boundary nudged 24 ms later. The
// nudge answers a MODEL that stopped on its own; answering a human interrupt
// with "you are unattended, keep going" overrides the person the standing order
// tells the model never to wait for.
test("a turn the operator interrupted is never nudged, and a natural stop still is", async () => {
  await withRoot(async () => {
    const session = await sessionFor({ role: ROLE.MANAGER });
    const interrupted = { role: "assistant", content: [], stopReason: "aborted", errorMessage: "Interrupted by user" };
    session.noteTurnEnd(interrupted);
    const stopped = session.turnExit({ turnIndex: 1 });
    assert.equal(stopped.stopAllowed, true, "the operator's own stop is allowed");
    assert.equal(stopped.reason, "operator-interrupt");
    assert.equal(stopped.nudge, null);
    // The record is consumed: the NEXT turn is judged on its own ending.
    const natural = session.turnExit({ turnIndex: 2 });
    assert.equal(natural.stopAllowed, false, "a model that stops on its own is still nudged");
    assert.equal(typeof natural.nudge, "string");
    assert.ok(natural.nudge.includes("无人值守"));

    // An internal abort is not the operator: the host also writes
    // `stopReason: "aborted"` for a compaction or a session transition, and a
    // nudge there would fight the host's own machinery. It is judged normally.
    // (Its `errorMessage` names no operator — `Compatibility` is the host's
    // own wording for a compaction, not "Interrupted by user".)
    session.noteTurnEnd({ role: "assistant", content: [], stopReason: "aborted", errorMessage: "Compaction failed" });
    const internal = session.turnExit({ turnIndex: 3 });
    assert.equal(internal.stopAllowed, false, "an internal abort is not an operator stop");
  });
});

test("the turn end hook hands the host's message to the session before Scope closes", async () => {
  const seen = [];
  const handler = createTurnEndHandler({
    scopeCloseTurn: async () => null,
    noteTurnEnd: (message) => seen.push(message),
  });
  const message = { role: "assistant", stopReason: "aborted", errorMessage: "Interrupted by user" };
  await handler({ turnIndex: 1, message, toolResults: [] });
  assert.deepEqual(seen, [message], "the ending message reached the session");
});

// The request records one session has written, parsed. The product's own
// account of every `context` call (`drift-log.mjs`), read back.
const readRequestLog = (root, sessionId) => {
  const file = requestLogFile(root, sessionId);
  if (!fs.existsSync(file)) return [];
  return fs
    .readFileSync(file, "utf8")
    .split("\n")
    .filter((line) => line.trim() !== "")
    .map((line) => JSON.parse(line));
};

// ---------------------------------------------------------------------------
// One process, N sessions: the drift class of 2026-11-01, by property
// ---------------------------------------------------------------------------

// The incident this file's isolation tests come from was not one bad request:
// it was the factory owning ONE adapter per runner, so the first session to bind
// answered every later session's `context` and `tool_call` — with its own Pair
// Hint chain, its own gate and its own journal. Session `01a1958f` (which left
// no file at all) was refused with session `01a1958e`'s `PREFIX_DRIFT`, and the
// records of six requests were written under the wrong session id.
//
// The theorem below drives the REAL factory, the REAL per-session handlers and
// the REAL Pair Hint journals, with N sessions (2-4) interleaved in one process.
// Each session has its own adapter, its own state, its own gate and its own
// journal; the only shared thing is the runner. Sessions are created in arbitrary
// order, one may be SWITCHED (a `/new`: a new id announced by `session_switch`,
// which is the one transition that does not emit `session_start`), and the turns
// of every session are interleaved arbitrarily.
//
// What is asserted is exactly what the incident violated:
//
//   - no request is ever refused with a prefix reason;
//   - every session's journal holds only its own session's records;
//   - every session leaves its own request log, and no other session's.
const sessionScriptArb = fc.array(fc.constantFrom("turn", "turn", "turn", "switch"), { maxLength: 6 });

const idsFor = (script) => {
  // Two to four participants, named the way a host names them.
  const count = 2 + (script.length % 3);
  return Array.from({ length: count }, (_, index) => `01a1958f-part-${index}`);
};

test("interleaved sessions in one process never share a chain, a journal or a record", async () => {
  await withRoot(async (root) => {
    // The adapter binds under `root`; the records this test writes by hand live
    // in their own tree, so reading the last record never reads the adapter's
    // own write for the request being checked.
    const records = tmpRoot();
    SESSION_ROOTS.push(records);
    let serial = 0;
    // One stream of work, drawn once: each step names the session it belongs
    // to, and its kind is a turn or a switch.
    const stepArb = fc
      .array(
        fc.record({
          session: fc.integer({ min: 0, max: 3 }),
          kind: fc.constantFrom("turn", "turn", "turn", "switch"),
          text: fc.stringMatching(/^[a-z][a-z0-9 ]{0,20}$/),
        }),
        { minLength: 1, maxLength: 10 },
      )
      .map((steps) => steps.filter((step) => step.kind === "turn" || step.session < 3));

    await fc.assert(
      fc.asyncProperty(stepArb, async (steps) => {
        resetAdapters();
        // A fresh runner per iteration; the ids repeat across iterations, and a
        // journal left by the previous iteration would be read as this one's
        // (the previous run's `ses-0-0` was refused by the chain it had left on
        // disk). The root comes from `withRoot`, so the adapter binds here.
        const pi = runnerFor();
        // Each participant's CURRENT id. A switch mints a new one, exactly as
        // the host does for `/new`; the old id is never reused. The iteration
        // counter keeps ids unique across runs of the property, so a stale file
        // can never be read as this run's (and a diagnostic names the run).
        const stamp = `p${(serial += 1).toString(36)}`;
        const current = new Map();
        const mint = (index, serial) => `ses-${stamp}-${index}-${serial}`;
        for (let index = 0; index < 4; index += 1) current.set(index, mint(index, 0));
        const serials = new Map();
        const turns = new Map();

        const ctxOf = (sessionId) => ({
          agent: { kind: "main", id: sessionId, name: "manager", depth: 0 },
          sessionManager: { getSessionId: () => sessionId, getEntries: () => [] },
          // The adapter derives its state root from the context; without this
          // the test's sessions bind in the working directory, not `root`.
          cwd: root,
        });

        for (const step of steps) {
          const before = current.get(step.session);
          if (step.kind === "switch") {
            // `/new`: the host switches the transcript and announces it with
            // `session_switch` — no `session_start` follows, so the adapter must
            // notice the new id from the event itself.
            const serial = (serials.get(step.session) ?? 0) + 1;
            serials.set(step.session, serial);
            const after = mint(step.session, serial);
            current.set(step.session, after);
            await pi.hooks.get("session_start")({ type: "session_start" }, ctxOf(before));
            await pi.hooks.get("session_switch")?.({ type: "session_switch", reason: "new", previousSessionFile: null }, ctxOf(after));
            // A runner without the switch hook still binds the new session on
            // its first request, which is the path below.
            turns.set(after, []);
            continue;
          }
          const sessionId = before;
          const ctx = ctxOf(sessionId);
          const history = turns.get(sessionId) ?? [];
          // One legal turn: the operator's message, and the copy the model reads
          // is everything before it plus that message. The turn's own answer is
          // added only AFTER its request, so the next request extends this one —
          // the prefix rule is about requests, not about what the model wrote
          // between them.
          const incoming = [
            ...history,
            { role: "user", content: [{ type: "text", text: step.text }], attribution: "user" },
          ];
          // The host starts a turn before the request and ends it after the
          // response's tools ran (`agent-loop.ts`); the request gate keys its
          // verdict by that turn, so the test supplies the same boundary.
          const turnIndex = (turns.get(`${sessionId}:turn`) ?? -1) + 1;
          turns.set(`${sessionId}:turn`, turnIndex);
          await pi.hooks.get("turn_start")({ type: "turn_start", turnIndex, timestamp: Date.now() }, ctx);
          const projected = await pi.hooks.get("context")({ messages: incoming.map((message) => ({ ...message })) }, ctx);
          await pi.hooks.get("turn_end")({ type: "turn_end", turnIndex, message: { role: "assistant", content: [] }, toolResults: [] }, ctx);
          const out = projected?.messages ?? incoming;
          // The request projected: no failure notice was added, and the copy the
          // model would read still begins with this session's own conversation —
          // the operator's message of the turn being answered.
          const notice = out.find(
            (message) => typeof message.content === "string" && message.content.startsWith("[herdchestra]"),
          );
          assert.equal(notice, undefined, `${sessionId}: no projection failed (${notice?.content ?? ""})`);
          // The copy the chain holds is what the previous request's record
          // states as `presented`; this request must begin with it. The pair is
          // presentation and the host never stores it, so both sides are
          // compared with the pair removed — the rule's own comparison
          // (`PairHintInjector#withoutInjection` + `canonicalKey`).
          const previous = readRequestLog(records, sessionId).at(-1) ?? null;
          if (previous !== null) {
            assert.equal(previous.allowed, true, `${sessionId}: the request before this one projected`);
          }
          const withoutPair = (messages) =>
            messages
              .map((message) => {
                if (!Array.isArray(message.content)) return message;
                const content = message.content.filter((part) => !String(part?.toolCallId ?? "").startsWith("pair-hint:"));
                return content.length === message.content.length ? message : { ...message, content };
              });
          const sent = withoutPair(out);
          // The chain's own predicate over the two copies, with the pair removed
          // from both: this request extends the one before it.
          if (previous !== null && previous.presented?.shapes?.length > 0) {
            const head = sent.slice(0, Math.min(previous.presented.shapes.length, sent.length));
            const shown = previous.presented.shapes.slice(0, head.length);
            assert.equal(head.length, shown.length, `${sessionId}: this copy is not shorter than the last`);
            for (let at = 0; at < head.length; at += 1) {
              const shape = messageShape(head[at]);
              assert.equal(shape.role, shown[at].role, `${sessionId}: message ${at} keeps its role`);
              assert.equal(shape.digest, shown[at].digest, `${sessionId}: message ${at} keeps its bytes`);
            }
          }
          // The next request is built from what the host STORES. The host
          // stores the REQUEST as it sent it (everything up to and including the
          // operator's message), then the model's answer; it never stores our
          // pair, and it does not store a copy the adapter added for
          // presentation. `incoming` is exactly that stored request, so it is
          // what the next turn's history starts from.
          const stored = incoming.map((message) => ({ ...message }));
          turns.set(sessionId, [...stored, { role: "assistant", content: [{ type: "text", text: `answer ${incoming.length}` }] }]);
        }

        // Every session that ran has its own record, and the record is its own.
        for (const [sessionId, list] of turns) {
          if (list.length === 0) continue;
          // The record path the product uses (`index.mjs` requestLog):
          // `recordRequest(requestLogFile(records, sessionId), …)`.
          const log = requestLogFile(records, sessionId);
          const outcome = recordRequest(
            log,
            requestRecord({
              phase: "settled",
              sessionId,
              host: "18.14.2",
              incoming: list,
              presented: list,
            }),
          );
          assert.equal(outcome.ok, true, `${sessionId} left its own request log`);
          assert.equal(fs.existsSync(log), true, `${sessionId} left its own request log`);
          const lines = fs.readFileSync(log, "utf8").split("\n").filter((line) => line.trim() !== "");
          assert.ok(lines.length > 0);
          for (const line of lines) {
            const record = JSON.parse(line);
            assert.equal(record.sessionId, sessionId, "a record is written under its own session");
            assert.equal(record.allowed, true, `${sessionId}: the record it keeps is this session's projected request`);
          }
          // The journal beside it holds this session's own records only.
          const journal = path.join(root, ".Herdchestra", `${safeSessionName(sessionId)}.jsonl`);
          if (fs.existsSync(journal)) {
            for (const line of fs.readFileSync(journal, "utf8").split("\n").filter((row) => row.trim() !== "")) {
              const entry = JSON.parse(line);
              assert.notEqual(entry.code, "PREFIX_DRIFT", `${sessionId}: its journal never records a drift`);
            }
          }
        }
      }),
      { numRuns: Number(process.env.HC_RUNS ?? 40), ...(process.env.HC_SEED ? { seed: Number(process.env.HC_SEED) } : {}) },
    );
    fs.rmSync(records, { recursive: true, force: true });
    for (const dir of []) void dir;
  });
});

// A review the ledger refuses must come back as an ANSWER.
//
// Measured on the real host (omp 18.14.2, `/tmp/probe2` and `/tmp/r3`): the
// model wrote `const r = review([{"id": "x", ...}])` and did not await it — legal,
// because the value may be read later and a member call may park — and the
// ledger refused the argument list (`CoreError: each finding needs a non-empty
// string key`). The refusal travelled as a REJECTED promise out of the session's
// `review` port, nothing observed it, and the host aborted with `[Unhandled
// Rejection]` and exit 1: the tool call was never answered and the whole run
// died. A program's primitive is called by the model's source, so no caller can
// be assumed to await it.
test("a program's unawaited refused review answers the call instead of crashing the host", async () => {
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => "sess-review-refusal", getEntries: () => [] },
    };
    const session = new testSession({ pi: { registerTool: () => {} }, api: null, ctx, role: ROLE.MANAGER });
    session.state.window.beginRound();
    // The surface a `js-manager` call builds, with the session's own ports: the
    // `review` primitive is the session's review port, which is where the
    // rejection escaped on the real host.
    const built = session.buildSurface({
      toolCallId: "call-refused",
      surfaceName: "js-manager",
      source: "class Js extends JsProgram { async run() { return review([{ id: 'x' }]); } }",
    });
    const members = built.surface.surface();
    const guarded = guardedMembers(
      Object.fromEntries(
        Object.entries(members).map(([name, handler]) => [name, typeof handler === "function" ? handler.bind(members) : handler]),
      ),
      built.program,
      built.parks,
    );
    const review = primitiveScope(guarded).review;
    assert.equal(typeof review, "function", "a Manager program reaches `review` as a primitive");
    const observed = [];
    const processRejection = (reason) => observed.push(reason);
    process.on("unhandledRejection", processRejection);
    try {
      // The exact shape the model produced: an object keyed by `id`, and NOT
      // awaited. The answer must already carry the refusal's fields, because
      // the model reads `r.ok` off the value it was handed (§6.3) — an async
      // member made that `undefined` on the real host.
      const receipt = review([{ id: "x", title: "t", status: "resolved" }]);
      assert.equal(receipt.ok, false, "a refused review answers ok: false");
      assert.equal(receipt.code, "INVALID_ARGUMENT");
      assert.match(receipt.message, /finding/i);
      // Let the microtask queue drain: an unobserved rejection would surface here.
      await new Promise((resolve) => setTimeout(resolve, 0));
      assert.deepEqual(observed, [], "no rejection escaped the member");
      // The awaited spelling receives the same facts.
      const awaited = await review([{ id: "x" }]);
      assert.equal(awaited.ok, false);
      assert.equal(awaited.code, "INVALID_ARGUMENT");
      // The refusal changed nothing: the round is still open, so the Manager has
      // not judged and may review again.
      assert.equal(session.state.window.open, true);
    } finally {
      process.off("unhandledRejection", processRejection);
      session.closeSurface("call-refused");
    }
  });
});

// A member the model did not await must not kill the host when its port rejects.
//
// `fork`, `join`, `fission` and `devops` answer with the port's promise, and a
// program is free not to await one. A port that then rejects left the rejection
// unobserved and the host aborted (measured: one unhandled rejection while the
// program went on as though the worker existed). The member observes the
// promise, so an awaiting caller still sees the rejection and a non-awaiting one
// leaves a live host.
test("an unawaited member whose port rejects leaves the host running", async () => {
  await withRoot(async () => {
    const ctx = {
      agent: { kind: "main", id: "Main", name: "main", depth: 0 },
      sessionManager: { getSessionId: () => "sess-fork-reject", getEntries: () => [] },
    };
    const session = new testSession({ pi: { registerTool: () => {} }, api: null, ctx, role: ROLE.MANAGER });
    // The session's own fork port, replaced with one that refuses: this is the
    // port a `fork(...)` call reaches, and the rejection is what the host would
    // have aborted on.
    const original = session.ports.bind(session);
    session.ports = () => {
      const ports = original();
      return {
        ...ports,
        fork: {
          fork: async () => {
            throw new Error("fork exploded");
          },
          join: async () => null,
        },
      };
    };
    const built = session.buildSurface({
      toolCallId: "call-fork",
      surfaceName: "js-manager",
      source: "class Js extends JsProgram { async run() { return null; } }",
    });
    const members = built.surface.surface();
    const guarded = guardedMembers(
      Object.fromEntries(
        Object.entries(members).map(([name, handler]) => [name, typeof handler === "function" ? handler.bind(members) : handler]),
      ),
      built.program,
      built.parks,
    );
    const fork = primitiveScope(guarded).fork;
    assert.equal(typeof fork, "function", "a Manager program reaches `fork` as a primitive");
    const observed = [];
    const processRejection = (reason) => observed.push(reason);
    process.on("unhandledRejection", processRejection);
    try {
      // Not awaited: the shape a body writes when it starts a worker and reads
      // the result later.
      fork("worker", "role: coder\nread the file");
      await new Promise((resolve) => setTimeout(resolve, 20));
      assert.deepEqual(observed, [], "the port's rejection was observed, not left to the host");
      // An awaiting caller still receives the refusal.
      await assert.rejects(() => fork("worker2", "role: coder\nread the file"), /fork exploded/);
    } finally {
      process.off("unhandledRejection", processRejection);
      session.closeSurface("call-fork");
    }
  });
});
