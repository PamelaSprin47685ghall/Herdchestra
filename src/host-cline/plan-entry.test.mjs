// The Plan entry and hand-off tests (根 AGENTS.md §6, §6.1, §7.2).
//
// These are the two paths a real Cline session needs and that no other test
// covered:
//
//   - `startPlan` — the operator's entry. Before it, a Plan session could only
//     exist in a test: nothing on the host loaded this plugin as a Plan;
//   - `planHandoff` — the Manager a Plan starts at `待执行`, and the clean-up of a
//     session that started but took no draft.
//
// The host is a fake with the shape `../cline` documents: a session factory
// (`start` / `startSession`), a `getSession` record carrying the metadata the
// starter wrote, and `stop` / `stopSession`. Every refusal is asserted as a
// refusal; none of these tests accepts a made-up session.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { ROLES } from "../compose/program/surface.mjs";
import { PLAN_PROMPT } from "../compose/prompt/plan.mjs";
import { startPlan } from "./plan-entry.mjs";
import { ManagerSessions, managerStartRequest } from "./sessions.mjs";
import { planHandoff } from "./handoff.mjs";
import { ROLE_METADATA_KEY, roleOf } from "./identity.mjs";
import { planDraftPath } from "./drafts.mjs";
import { createPlugin } from "./index.mjs";
import { readRoleFact, roleFactPath } from "./role-fact.mjs";
import { SessionState } from "./session.mjs";

// A real, writable root. `startPlan` records the session's role on disk before
// it starts the session (`role-fact.mjs`), so a test that passes a path which
// does not exist is testing a refusal, not a start.
const tempRoot = () => mkdtempSync(join(tmpdir(), "herd-plan-"));

// A host that records every start and can answer `getSession` with what it
// stored, exactly like `LocalRuntimeHost` does with `sessionMetadata`.
function fakeHost({ failStart = null, noSessionId = false, startAnswer = null } = {}) {
  const starts = [];
  const stopped = [];
  const records = new Map();
  let sequence = 0;
  const start = async (input) => {
    starts.push(input);
    if (failStart !== null) throw new Error(failStart);
    // A refusal the host *answers* rather than throws (a schema refusal before
    // the session record exists).
    if (startAnswer !== null) return startAnswer;
    if (noSessionId) return {};
    sequence += 1;
    // The host uses the caller's id verbatim and mints one only when it is
    // absent (`startSession`: `config.sessionId?.trim() || createSessionId()`).
    const sessionId = input.config?.sessionId ?? `sess-${sequence}`;
    records.set(sessionId, { sessionId, metadata: input.sessionMetadata ?? {} });
    return { sessionId, pluginIssues: [] };
  };
  return {
    starts,
    stopped,
    records,
    start,
    getSession: async (sessionId) => records.get(sessionId),
    stop: async (sessionId) => {
      stopped.push(sessionId);
      return true;
    },
  };
}

test("startPlan opens a Plan session whose first prompt is the operator's text", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const host = fakeHost();
  const started = await startPlan({
    host,
    text: "把重试改成指数退避",
    root,
    name: "retry",
    modelId: "anthropic/claude-sonnet-4.6",
  });
  assert.equal(started.ok, true);
  // The id is the adapter's own, chosen before the start and carried in the
  // request: the host uses it verbatim and hands it to the plugin's setup.
  assert.match(started.sessionId, /^plan-retry_\d+_[0-9a-z]{5}$/);
  assert.equal(host.starts[0].config.sessionId, started.sessionId);
  assert.equal(started.draft, planDraftPath(root, "retry"));

  const [request] = host.starts;
  // The operator's words are the first prompt; the Plan is told its own role
  // segment and nothing of the Manager composition (根 §2.2).
  assert.equal(request.prompt, "把重试改成指数退避");
  assert.equal(request.config.systemPrompt, PLAN_PROMPT);
  assert.equal(request.config.systemPrompt.includes("<vibe-mode>"), false);
  // The session loads this plugin through the field the session loader reads:
  // `localRuntime.pluginPaths` (`local-runtime-bootstrap.ts:463`). The same
  // paths under `config` load nothing — that was the live defect where a Plan
  // ran the bare prompt with no face and no rule.
  assert.equal(request.localRuntime.pluginPaths.length, 1);
  assert.match(request.localRuntime.pluginPaths[0], /index\.js$/);
  assert.equal(request.config.pluginPaths, undefined, "config.pluginPaths loads no plugin");
  assert.equal(request.config.agentPluginPaths, undefined);
  // The plugin machinery must be enabled, or the loader is never reached.
  for (const extension of ["rules", "skills", "plugins"]) {
    assert.equal(request.localRuntime.configExtensions.includes(extension), true, extension);
  }
  // The request carries this plugin's face and no builtin tool: the host's only
  // narrowing knob is `toolPolicies` (`runtime-builder.ts:85-99`).
  assert.deepEqual(request.config.toolPolicies["*"], { enabled: false });
  assert.deepEqual(request.config.toolPolicies["js-plan"], { enabled: true });
  assert.equal(request.config.toolPolicies["js-manager"], undefined);
  assert.equal(request.config.toolPolicies["read_files"], undefined);
  assert.equal(request.config.toolPolicies["run_commands"], undefined);
  // The plugin is selected by name where the host honours it.
  assert.deepEqual(request.plugins, { "*": { enabled: true }, herdchestra: { enabled: true } });
  // The host's own delegation tools stay off: `fork` is the plugin's.
  assert.equal(request.config.enableSpawnAgent, false);
  assert.equal(request.config.enableAgentTeams, false);
  // The role and the draft travel in the metadata the plugin reads back.
  assert.equal(request.sessionMetadata[ROLE_METADATA_KEY], ROLES.PLAN);
  assert.equal(request.sessionMetadata["herdchestra.draft"], started.draft);
  // The draft's directory exists before the session does: `write` replaces the
  // file atomically through a temp file in that directory and cannot create it,
  // so a fresh workspace would fail every write with ENOENT — and the hand-off,
  // which reads the saved draft, would then never start a Manager.
  assert.equal(statSync(join(root, "plans")).isDirectory(), true);
});

test("startPlan prepares the canonical draft directory before the session exists", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  // A fresh workspace: nothing under `.Herdchestra/` at all.
  assert.equal(existsSync(join(root, ".Herdchestra")), false);
  const host = fakeHost();
  const started = await startPlan({ host, text: "plan it", root, name: "fresh", modelId: "m" });
  assert.equal(started.ok, true, JSON.stringify(started));
  // The host was asked to start AFTER the directory existed, so the session's
  // first `write` has a parent to replace the file in.
  assert.equal(host.starts.length, 1);
  assert.equal(existsSync(join(root, "plans")), true);

  // A path where the directory must be is a refusal, not a start whose every
  // write would fail: the Plan exists to produce that one file.
  const blocked = tempRoot();
  t.after(() => rmSync(blocked, { recursive: true, force: true }));
  writeFileSync(join(blocked, "plans"), "not a directory");
  const idle = fakeHost();
  const refused = await startPlan({ host: idle, text: "plan it", root: blocked, name: "n", modelId: "m" });
  assert.equal(refused.ok, false);
  assert.equal(refused.reason, "DRAFT_DIR_UNWRITABLE");
  assert.equal(refused.message.includes("plans"), true);
  assert.equal(idle.starts.length, 0, "a session that could never save its draft is not started");
});

test("startPlan refuses a missing host, prompt or model instead of inventing a session", async () => {
  // Order is part of the contract: capability first, then the arguments. An
  // object with no session factory is not "a host missing a model".
  assert.equal((await startPlan({ host: null, text: "x" })).reason, "HOST_HAS_NO_SESSION_CREATE");
  assert.equal((await startPlan({ host: {}, text: "x" })).reason, "HOST_HAS_NO_SESSION_CREATE");
  const host = fakeHost();
  assert.equal((await startPlan({ host, text: "   " })).reason, "PROMPT_MISSING");
  // The host's CoreModelConfig requires a model id; the entry refuses before the
  // host's schema can bury the reason in a stringified zod array.
  const noModel = await startPlan({ host, text: "go" });
  assert.equal(noModel.reason, "MODEL_MISSING");
  assert.equal(host.starts.length, 0);
});

test("startPlan reports a host that throws or returns no id, and starts nothing else", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const failing = fakeHost({ failStart: "provider not configured" });
  const failed = await startPlan({ host: failing, text: "go", root, modelId: "m" });
  assert.equal(failed.ok, false);
  assert.equal(failed.reason, "PLAN_SESSION_FAILED");
  assert.match(failed.message, /provider not configured/);

  const idle = fakeHost({ noSessionId: true });
  const noId = await startPlan({ host: idle, text: "go", root, modelId: "m" });
  assert.equal(noId.ok, false);
  assert.equal(noId.reason, "PLAN_SESSION_FAILED");
  assert.match(noId.message, /no session id/);

  // The host can refuse a start by *answering*, not throwing: a schema refusal
  // before the session record exists. That answer used to read as success.
  const refusing = fakeHost({
    startAnswer: {
      ok: false,
      name: "ZodError",
      issues: [{ path: ["enable_tools"], message: "Invalid input: expected boolean, received undefined" }],
    },
  });
  const refused = await startPlan({ host: refusing, text: "go", root, modelId: "m" });
  assert.equal(refused.ok, false);
  assert.equal(refused.reason, "PLAN_SESSION_FAILED");
  assert.equal(refused.issues[0], "enable_tools: Invalid input: expected boolean, received undefined");
  // The entry's own reason wins; the host's finer reason travels in `detail`.
  assert.equal(refused.detail, "START_REFUSED");
  assert.equal(noId.detail, "START_RETURNED_NO_SESSION");
});

test("startPlan speaks the RuntimeHost vocabulary too (startSession)", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const starts = [];
  const host = {
    // The host starts under the id the request carried; a different id is a
    // reported failure, not a silently unreachable role fact.
    startSession: async (request) => {
      starts.push(request);
      return { sessionId: request.config.sessionId };
    },
  };
  const started = await startPlan({ host, text: "plan it", root, name: "n", modelId: "m" });
  assert.equal(started.ok, true);
  assert.equal(started.sessionId, starts[0].config.sessionId);
  assert.equal(starts[0].config.systemPrompt, PLAN_PROMPT);
});

test("a Plan session is read back from its metadata, and an unmarked one is the operator's Manager", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const host = fakeHost();
  const started = await startPlan({ host, text: "go", root, name: "n", modelId: "m" });
  const plan = await roleOf({ host, sessionId: started.sessionId });
  assert.equal(plan.role, ROLES.PLAN);
  assert.equal(plan.source, "sessionRecord");
  assert.equal(plan.metadata["herdchestra.draft"], started.draft);

  const unmarked = await roleOf({ host, sessionId: "operator-session" });
  assert.equal(unmarked.role, ROLES.MANAGER);
  // The host has no record for this id: the role is unproven, not a reading.
  assert.equal(unmarked.source, "unproven");

  // A host that hands the metadata to `setup` answers without a record read.
  const handed = await roleOf({ host: null, sessionId: null, metadata: { [ROLE_METADATA_KEY]: ROLES.PLAN } });
  assert.equal(handed.role, ROLES.PLAN);
  assert.equal(handed.source, "sessionMetadata");
  // Metadata that names no role is the caller's role, and the source says the
  // session was asked and said nothing.
  const unmarkedMetadata = await roleOf({ host: null, sessionId: null, metadata: {}, fallback: ROLES.MANAGER });
  assert.equal(unmarkedMetadata.role, ROLES.MANAGER);
  assert.equal(unmarkedMetadata.source, "unmarked");
  // A host with no answer at all is not a reading: the role is unproven and the
  // gate grants nothing on it.
  const silent = await roleOf({ host: null, sessionId: "x", fallback: ROLES.MANAGER });
  assert.equal(silent.role, ROLES.MANAGER);
  assert.equal(silent.source, "unproven");
});

test("planHandoff starts one Manager from the draft bytes and reports the session", async () => {
  const calls = [];
  const receipt = await planHandoff({
    draft: "# draft\n\nstep one\n",
    startManager: async (draft) => {
      calls.push(draft);
      return { ok: true, sessionId: "manager-1" };
    },
  });
  assert.deepEqual(calls, ["# draft\n\nstep one\n"]);
  assert.deepEqual(receipt, { ok: true, sessionId: "manager-1" });
});

test("planHandoff refuses an empty draft before any session exists", async () => {
  let called = false;
  const receipt = await planHandoff({
    draft: "",
    startManager: async () => {
      called = true;
      return { ok: true, sessionId: "manager-1" };
    },
  });
  assert.equal(receipt.ok, false);
  assert.equal(receipt.reason, "DRAFT_MISSING");
  assert.equal(called, false);
});

test("a Manager session that took no prompt is stopped again", async () => {
  const stopped = [];
  const receipt = await planHandoff({
    draft: "# draft",
    startManager: async () => ({ ok: false, reason: "MANAGER_PROMPT_FAILED", session: "half-open" }),
    stopManager: async (sessionId) => {
      stopped.push(sessionId);
      return { ok: true, sessionId };
    },
  });
  assert.equal(receipt.ok, false);
  assert.equal(receipt.reason, "MANAGER_PROMPT_FAILED");
  assert.equal(receipt.session, "half-open");
  assert.deepEqual(stopped, ["half-open"]);
  assert.deepEqual(receipt.discarded, { ok: true, sessionId: "half-open" });
});

test("a hand-off with no session factory says so", async () => {
  const receipt = await planHandoff({ draft: "# draft", startManager: null });
  assert.equal(receipt.ok, false);
  assert.equal(receipt.reason, "HOST_HAS_NO_SESSION_CREATE");
});

test("a host that throws during the hand-off is a refusal, not a crash", async () => {
  const receipt = await planHandoff({
    draft: "# draft",
    startManager: async () => {
      throw new Error("no provider");
    },
  });
  assert.equal(receipt.ok, false);
  assert.equal(receipt.reason, "MANAGER_SESSION_FAILED");
  assert.match(receipt.message, /no provider/);
});

test("a hand-off without a model reading is refused, never started on a default", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  // The plugin's setup context carries no provider and no model
  // (`ctxKeys: [session, client, user, workspaceInfo, cwd, emitEvent, onDispose]`),
  // so the only reading is the session's own model request. Before the first one
  // there is nothing to start a successor on.
  const blind = new ManagerSessions({ ctx: { modelId: "", providerId: "cline" }, cwd: root });
  // Capability first: a host that cannot start a session is reported as such.
  blind.host = async () => ({ kind: "clineCore", handle: { start: async () => ({}) } });
  const refused = await blind.startManager("# draft");
  assert.equal(refused.ok, false);
  assert.equal(refused.reason, "MODEL_UNKNOWN");
  // `startManager` refuses before the host is asked to start anything.
  assert.equal(blind.started.length, 0);

  // The same rule on the Plan entry a live session calls.
  const plan = await blind.openPlan("plan it");
  assert.equal(plan.ok, false);
  assert.equal(plan.reason, "MODEL_UNKNOWN");

  // A reading makes the config the real connection, not a fallback.
  const seen = new ManagerSessions({ ctx: null, cwd: root, model: { providerId: "openai-compatible", modelId: "lazy/task" } });
  const config = seen.config({ systemPrompt: "p", sessionId: "s", model: seen.model });
  assert.equal(config.providerId, "openai-compatible");
  assert.equal(config.modelId, "lazy/task");
});

test("a session records the model of its own request, and workers use that reading", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const session = new SessionState({ ctx: { cwd: root, session: { sessionId: "model-1" } } });
  assert.equal(session.model, null, "nothing is known before a request");
  // The host names the connection on every request; both spellings are accepted.
  session.noteModel({ providerId: "openai-compatible", modelId: "lazy/task" });
  assert.deepEqual(session.model, { providerId: "openai-compatible", modelId: "lazy/task" });
  // A request that names neither changes nothing, rather than clearing the fact.
  session.noteModel({ messages: [] });
  assert.deepEqual(session.model, { providerId: "openai-compatible", modelId: "lazy/task" });
  // `model` is a copy: a caller cannot rewrite the session's reading.
  session.model.modelId = "other";
  assert.equal(session.model.modelId, "lazy/task");
});

test("a fresh workspace gets a writable draft directory before the session starts", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  // The live defect: `write` is an atomic replace through a temp file in the
  // target's directory, so it cannot create that directory. In a fresh root the
  // first draft write failed with ENOENT, the model then made `drafts.md` a
  // plain file, and every later write failed with ENOTDIR.
  const host = fakeHost();
  const started = await startPlan({ host, text: "go", root, name: "fresh", modelId: "m" });
  assert.equal(started.ok, true, JSON.stringify(started));
  const directory = planDraftPath(root, "fresh").replace(/[/\\][^/\\]+$/, "");
  assert.equal(statSync(directory).isDirectory(), true, "the draft's directory exists before the first write");
  assert.equal(existsSync(planDraftPath(root, "fresh")), false, "the draft file itself is the model's to write");

  // A directory that cannot be created is a named refusal, and nothing starts.
  const blocked = join(root, "blocked");
  writeFileSync(blocked, "a file where a directory would go");
  const refused = await startPlan({ host, text: "go", root: blocked, name: "fresh", modelId: "m" });
  assert.equal(refused.ok, false);
  assert.equal(refused.reason, "DRAFT_DIR_UNWRITABLE");
  assert.equal(host.starts.length, 1, "the refused start asked the host for nothing");
});

test("every session the adapter starts carries the host's three required booleans", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  // The host's manifest schema declares `enable_tools` / `enable_spawn` /
  // `enable_teams` as `z.boolean()` and fills them from the request's
  // `enableTools` / `enableSpawnAgent` / `enableAgentTeams`. A request missing
  // one is a ZodError raised inside the host, where the only trace is a
  // stringified issue array — the failure an operator cannot act on.
  //
  // This is a contract test over OUR request, not over the host: whatever else
  // changes, these three keys must be present and boolean on every start the
  // adapter makes.
  const required = ["enableTools", "enableSpawnAgent", "enableAgentTeams"];

  const host = fakeHost();
  const started = await startPlan({
    host,
    text: "plan it",
    root,
    name: "contract",
    modelId: "anthropic/claude-sonnet-4.6",
  });
  assert.equal(started.ok, true, JSON.stringify(started));
  const [request] = host.starts;
  for (const key of required) {
    assert.equal(typeof request.config[key], "boolean", `startPlan must send ${key} as a boolean`);
  }
  // The snake_case spellings are the HOST's, not the request's: sending them
  // leaves the camelCase keys absent, which is the same ZodError.
  for (const key of ["enable_tools", "enable_spawn", "enable_teams"]) {
    assert.equal(key in request.config, false, `the request must not send ${key}`);
  }
  // `systemPrompt` is required too, and it must not be empty.
  assert.equal(typeof request.config.systemPrompt, "string");
  assert.equal(request.config.systemPrompt.trim().length > 0, true);

  // The Manager a hand-off starts goes through `ManagerSessions#config`, which
  // is the other create the adapter makes. Both must agree on the contract, or
  // a hand-off fails where a Plan start succeeds.
  // Setup carries no provider and no model; the factory starts on the reading a
  // real session takes from its own model request, so the test states one.
  const sessions = new ManagerSessions({ ctx: null, cwd: root, model: { providerId: "p", modelId: "m" } });
  const managerConfig = sessions.config({ systemPrompt: "manager prompt", sessionId: "sid", model: sessions.model });
  assert.ok(managerConfig !== null, "the Manager config is built");
  for (const key of required) {
    assert.equal(typeof managerConfig[key], "boolean", `startManager must send ${key} as a boolean`);
  }
  for (const key of ["enable_tools", "enable_spawn", "enable_teams"]) {
    assert.equal(key in managerConfig, false, `the Manager request must not send ${key}`);
  }
  assert.equal(typeof managerConfig.systemPrompt, "string");
  assert.equal(managerConfig.systemPrompt.trim().length > 0, true);
});

test("the plugin paths travel in localRuntime on both create paths, never in config", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  // The session loader reads `localRuntime.pluginPaths` and builds `localConfig`
  // from `localRuntime` (`local-runtime-bootstrap.ts:356-360`, `:463`). Paths
  // placed under `config` load no plugin: the session then runs the bare prompt
  // with no face, no rule and no hooks — the live defect where a Plan turn ran
  // but every `js-plan` call was refused.
  const assertPlacement = (request, label) => {
    assert.equal(Array.isArray(request.localRuntime?.pluginPaths), true, `${label}: localRuntime.pluginPaths`);
    assert.equal(request.localRuntime.pluginPaths.length, 1, `${label}: one plugin entry`);
    assert.match(request.localRuntime.pluginPaths[0], /index\.js$/, label);
    assert.equal(request.config.pluginPaths, undefined, `${label}: config.pluginPaths loads nothing`);
    assert.equal(request.config.agentPluginPaths, undefined, `${label}: agentPluginPaths loads nothing`);
    assert.equal(
      request.localRuntime.configExtensions.includes("plugins"),
      true,
      `${label}: the plugin machinery must be enabled`,
    );
  };

  // Plan.
  const host = fakeHost();
  const started = await startPlan({
    host,
    text: "plan it",
    root,
    name: "placement",
    modelId: "anthropic/claude-sonnet-4.6",
  });
  assert.equal(started.ok, true, JSON.stringify(started));
  assertPlacement(host.starts[0], "startPlan");

  // Manager: the config factory no longer carries the paths, and the start
  // request must supply them through localRuntime instead.
  const sessions = new ManagerSessions({ ctx: null, cwd: root, model: { providerId: "p", modelId: "m" } });
  const config = sessions.config({ systemPrompt: "manager prompt", sessionId: "sid", model: sessions.model });
  assert.ok(config !== null);
  assert.equal(config.pluginPaths, undefined, "the Manager config carries no plugin paths");
  // The request the hand-off sends puts them where the loader reads them.
  const request = managerStartRequest({
    config,
    draft: "# draft",
    localRuntime: { extensions: [], pluginPaths: ["/repo/src/host-cline/index.js"], configExtensions: ["plugins"] },
  });
  assertPlacement(request, "startManager");
  assert.equal(request.prompt, "# draft");
  assert.equal(request.sessionMetadata[ROLE_METADATA_KEY], ROLES.MANAGER);
});

test("a sandboxed plugin resolves its role from the recorded fact when the host is unreachable", async (t) => {
  // The live sandbox path: the plugin runs in the host's worker, cannot resolve
  // `@cline/core`, so `ManagerSessions.host()` answers null and `roleOf` has
  // nothing to read. Before the role fact, that session silently became a
  // Manager and refused its own `js-plan` face.
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const host = fakeHost();
  const started = await startPlan({ host, text: "go", root, name: "sandbox", modelId: "m" });
  assert.equal(started.ok, true, JSON.stringify(started));

  // The entry recorded the identity BEFORE the start, keyed by the id the
  // request carries. That is the atomicity: the plugin is set up before the
  // session is registered, so nothing written after the start is readable.
  assert.deepEqual(readRoleFact({ root, sessionId: started.sessionId }), {
    role: ROLES.PLAN,
    draft: started.draft,
  });
  // The request carried that id, and the fake host used it verbatim.
  assert.equal(host.starts[0].config.sessionId, started.sessionId);
  // No second copy exists under the caller's name: one identity, one file.
  assert.equal(readRoleFact({ root, sessionId: "sandbox" }), null);

  // A session whose setup context carries no metadata and whose host is
  // unreachable still resolves to Plan, and says which source answered.
  const session = new SessionState({
    ctx: { cwd: root, session: { sessionId: started.sessionId } },
    role: ROLES.MANAGER,
  });
  const decided = await session.resolveRole();
  assert.equal(decided.role, ROLES.PLAN);
  assert.equal(decided.source, "roleFact");
  assert.equal(session.declaredRole, ROLES.PLAN);
  // The draft the entry named travels with the fact, so the rule and the ledger
  // agree with the session that was started.
  assert.equal(decided.metadata["herdchestra.draft"], started.draft);
});

test("a session with no recorded fact and no reachable host stays the honest default", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  // Nothing recorded: the role fact must not invent one.
  assert.equal(readRoleFact({ root, sessionId: "unknown" }), null);
  const session = new SessionState({ ctx: { cwd: root, session: { sessionId: "unknown" } }, role: ROLES.MANAGER });
  const decided = await session.resolveRole();
  assert.equal(decided.role, ROLES.MANAGER);
  assert.notEqual(decided.source, "roleFact");

  // A malformed or unmarkable file is ignored, never trusted.
  const file = roleFactPath(root, "broken");
  mkdirSync(join(root, ".Herdchestra"), { recursive: true });
  writeFileSync(file, "{not json");
  assert.equal(readRoleFact({ root, sessionId: "broken" }), null);
  writeFileSync(file, JSON.stringify({ v: 1, sessionId: "broken", role: "root" }));
  assert.equal(readRoleFact({ root, sessionId: "broken" }), null, "an unmarkable role is ignored");
});

test("the plugin's openPlan passes the same derived root and name on both branches, and never invents a model", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  // Setup carries no connection (`@cline/core` 0.2.0 hands over `session`,
  // `client`, `user`, `workspaceInfo`, `cwd`, `emitEvent`, `onDispose`), so a
  // session that has not run a request has no reading of its own. The entry must
  // report that, and a caller that knows the connection must have it forwarded.
  const seen = [];
  const plugin = createPlugin({
    openPlan: async (text, options) => {
      seen.push({ text, options });
      return { ok: true, sessionId: "s-plan" };
    },
  });
  plugin.adapter = { session: { sessions: {}, root, sessionId: "s-1" } };
  const explicit = { providerId: "openai-compatible", modelId: "lazy/task" };
  assert.equal((await plugin.openPlan("plan it", { model: explicit })).ok, true);
  assert.deepEqual(seen[0].options.model, explicit);
  assert.equal(seen[0].text, "plan it");
  // The caller named no root and no name: the entry derives both from this
  // session, and `startPlan` turns them into the draft path and the session id.
  assert.equal(seen[0].options.root, root);
  assert.equal(seen[0].options.name, "s-1");
  // A call without a model forwards nothing: the entry the caller supplied
  // decides, and the adapter's own factory reports MODEL_UNKNOWN.
  await plugin.openPlan("plan it");
  assert.equal("model" in seen[1].options, false);
  // The second call lands on the same derived root and name as the first: two
  // Plans of one session share one draft file, not one per call.
  assert.equal(seen[1].options.root, seen[0].options.root);
  assert.equal(seen[1].options.name, seen[0].options.name);
  // The adapter that owns the host handle travels too, so a supplied entry can
  // start on the same handle the built-in branch uses.
  assert.equal(seen[0].options.adapter, plugin.adapter);

  // The built-in branch (no supplied entry) hands the adapter's own factory the
  // SAME derived root and name, not the raw options. Before, it passed
  // `root: forwarded.root, name: forwarded.name` from the caller's raw call, so
  // a call that named neither started a Plan whose draft was derived from the
  // factory's cwd and a minted name — a second draft beside the first.
  const forwarded = [];
  const builtin = createPlugin();
  builtin.adapter = {
    session: {
      root,
      sessionId: "s-1",
      sessions: {
        openPlan: async (text, options) => {
          forwarded.push({ text, options });
          return { ok: true, sessionId: "s-plan" };
        },
      },
    },
  };
  assert.equal((await builtin.openPlan("plan it", { model: explicit })).ok, true);
  await builtin.openPlan("plan it");
  for (const call of forwarded) {
    assert.equal(call.text, "plan it");
    assert.equal(call.options.root, root);
    assert.equal(call.options.name, "s-1");
  }
  assert.deepEqual(forwarded[0].options.model, explicit);
  assert.equal("model" in forwarded[1].options, false);
});
