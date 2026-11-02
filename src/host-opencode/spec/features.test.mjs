// The parts of the adapter that were refused before and are real now:
// `review` on the Manager face, Plan's face, `ask`, fission and relay
// (§3.3 example 7, §4.2, §5, §6, §7.1).
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.
//
// The host is a fake: `session` is a scripted SessionDomain, every registration
// point records what the adapter registered, and the tools are called the way
// the host calls them.

import test from "node:test";
import assert from "node:assert/strict";
import fsSync from "node:fs";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { eventsOf, setup, statedRoot, workspaceRootOf } from "./plugin.mjs";
import { devopsRoadOf } from "./activation.mjs";
import { SURFACE_MEMBERS } from "../../compose/program/surface.mjs";
import { planDraftPath } from "./program-ports.mjs";
import { PORTS_FOR, makeSurface } from "./surface-factory.mjs";
import { RELAY_GATE, RELAY_PHASES, relayFile, relayRecord, writeRelay } from "./relay-record.mjs";

const RESET = (budget = 50) => ({
  kind: "resetScope",
  newScopeGoal: "test",
  newScopeRetentionBudget: budget,
});

// What the host reports for the incumbent's context (`Session.context`). Shape
// from `PublicSessionMessage` (`packages/schema/src/session-message.ts:77-82`):
// a user turn carries `type: "user"` and `text`.
//
// The relay turns this into the record's `fenceThrough` (the length) and
// `operatorInput` (the first user text), so a fake host without a transcript
// would only exercise the refusal path.
const DEFAULT_TRANSCRIPT = Object.freeze([
  { type: "user", text: "ship the parser fix" },
  { type: "assistant", text: "working on it" },
]);

// A fake host whose session domain records every call and answers what the test
// scripts. `events` feeds the subscription.
const fakeHost = async ({ directory, sessions = {}, events = [] } = {}) => {
  const dir = directory ?? (await fs.mkdtemp(path.join(os.tmpdir(), "herdchestra-")));
  const tools = new Map();
  const before = [];
  const after = [];
  const contextHooks = [];
  const promptHooks = [];
  const compactionHooks = [];
  const generateHooks = [];
  const subscribed = [];
  const calls = [];
  // What each session has on screen, as the host would report it. The relay
  // counts these before writing its record; a test seeds a session here when it
  // wants the counted barrier path instead of the uncounted fallback.
  const messagesSeen = new Map();
  let sequence = 0;

  const domain = {
    create: async (input) => {
      sequence += 1;
      const id = `ses_${sequence}`;
      calls.push(["create", { ...input, id }]);
      return { data: { id, ...input } };
    },
    prompt: async (input) => {
      calls.push(["prompt", input]);
      return { data: {} };
    },
    synthetic: async (input) => {
      calls.push(["synthetic", input]);
      return { data: {} };
    },
    wait: async (input) => {
      calls.push(["wait", input]);
    },
    get: async (input) => {
      calls.push(["get", input]);
      // Every session this fake reports runs a model: a session created without
      // one falls to the agent default, which on the live host answers 429
      // (`model-inherit.mjs`).
      return { data: { id: input.sessionID, model: { providerID: "kw92", id: "lazy/task" } } };
    },
    interrupt: async (input) => {
      calls.push(["interrupt", input]);
    },
    // The host's own transcript for one session (`Session.context`,
    // `GET /api/session/:id/context`). The relay reads it before writing its
    // record: the count becomes `fenceThrough` and the first user turn becomes
    // the record's `operatorInput` (根 §2.4 第 3 步).
    //
    // `messagesSeen` is what a test seeds; `seedTranscript` below is the
    // default the plugin sees for the incumbent.
    context: async (input) => {
      calls.push(["context", input]);
      const seeded = messagesSeen.get(input.sessionID);
      if (seeded !== undefined) return { data: seeded };
      return { data: input.sessionID === "ses_main" ? DEFAULT_TRANSCRIPT : [] };
    },
    ...sessions,
  };

  const ctx = {
    location: { directory: dir },
    options: {},
    agent: {
      transform: async (callback) => {
        const agents = [{ id: "Manager" }, { id: "coder" }, { id: "devops" }, { id: "Plan" }, { id: "build" }];
        callback({
          list: () => agents,
          update: (id, update) => update(agents.find((agent) => agent.id === id) ?? { id }),
        });
      },
    },
    tool: {
      transform: async (callback) =>
        callback({ add: (tool) => tools.set(tool.name, tool), update() {}, remove() {} }),
      hook: async (name, callback) => {
        if (name === "execute.before") before.push(callback);
        else if (name === "execute.after") after.push(callback);
      },
    },
    session: {
      // One array per host hook. A single "not context, so prompt" fallback
      // silently filed `compaction` and `generate` under `prompt`, and
      // `operatorSays` then called a projection instead of the prompt hook.
      hook: async (name, callback) => {
        if (name === "context") contextHooks.push(callback);
        else if (name === "prompt") promptHooks.push(callback);
        else if (name === "compaction") compactionHooks.push(callback);
        else if (name === "generate") generateHooks.push(callback);
      },
      ...domain,
    },
    event: {
      subscribe: () => {
        let index = 0;
        return {
          [Symbol.asyncIterator]: () => ({
            next: async () =>
              index < events.length ? { done: false, value: events[index++] } : { done: true },
            return: async () => ({ done: true }),
          }),
          return: async () => ({ done: true }),
        };
      },
    },
  };
  subscribed.push(ctx.event);
  const cleanup = await setup(ctx);
  const call = async (
    name,
    input,
    { agent = "coder", sessionID = "ses_main", messageID, scope = null, id = null } = {},
  ) => {
    // The call id is this physical call's identity, and a program name is the
    // call's id. A test that needs to read a program back by name passes the id
    // it used; otherwise the helper numbers each call once.
    sequence += 1;
    const callID = id ?? `call_${sequence}`;
    // An annotation passed IN the input is the payload's own: a test about the
    // replay payload must not have the helper silently replace it with the
    // default one.
    const scoped = input?.scope === undefined ? { ...input, scope: scope ?? RESET() } : { ...input };
    const event = { tool: name, sessionID, agent, messageID: messageID ?? `msg_${sequence}`, id: callID, input: scoped };
    for (const hook of before) await hook(event);
    // The host's result shape: `output` is the receipt the schema guards,
    // `content` is what the model reads. The adapter's tools declare an output
    // schema, so the host requires both (`tool-registrations.mjs`).
    const result = await tools.get(name).execute(scoped, {
      sessionID,
      agent,
      messageID: event.messageID,
      id: callID,
      signal: undefined,
      progress: async () => {},
    });
    const output = result.output;
    for (const hook of after) await hook({ ...event, status: "completed", result: output });
    return output;
  };

  return {
    dir,
    tools,
    calls,
    // Seed a session's host-side message list, so the relay's counted barrier
    // path is exercised instead of its uncounted fallback.
    messagesSeen,
    contextHooks,
    promptHooks,
    compactionHooks,
    generateHooks,
    call,
    cleanup,
    // The session state the plugin keeps for a session: the close shape is a
    // property of that state, so a test reads it from the same object the
    // request hooks use.
    state: (sessionID) => cleanup.stateOf(sessionID),
    relayHost: cleanup.relayHost,
    // The worker table the plugin keeps for a session. It is created lazily, so
    // a test that wants to seed the predecessor's roster asks for it first.
    table: (sessionID) => cleanup.workerTableOf(sessionID),
    // The road-bound devops of one session. The road is keyed by the session
    // that owns it (§2.3), so two Managers never see each other's devops.
    bindDevops: (name, sessionID = "ses_main") => devopsRoadOf(cleanup.activation, sessionID).add(name),
    isDevopsBound: (name, sessionID = "ses_main") =>
      devopsRoadOf(cleanup.activation, sessionID).has(name),
    // One genuine operator receipt, as the host delivers it: the prompt hook is
    // where the operator's original input is captured (original-input.mjs).
    // A test that relays must speak first, because the relay refuses without a
    // stored original instead of reading one out of a transcript.
    async operatorSays(text, { sessionID = "ses_main", messageID = "msg_operator" } = {}) {
      const [hook] = promptHooks;
      return await hook({ sessionID, messageID, prompt: { text }, delivery: "steer" });
    },
    // A session the activation has seen, as if its `session.created` event had
    // been pumped. The original-input capture needs this: a session the
    // activation has never seen predates the fact and never captures, because
    // its next prompt is not necessarily its first (original-input.mjs).
    seesSession(sessionID, { parentID = null } = {}) {
      cleanup.observeSession(sessionID, { parentID });
    },
  };
};

test("a relay writes one durable record before the successor's brief, then retires the incumbent", async () => {
  const host = await fakeHost();
  // The operator speaks first: the relay keeps the operator's original input,
  // which only a real receipt records (original-input.mjs).
  host.seesSession("ses_main");
  await host.operatorSays("ship the parser fix");
  const recorded = await host.call(
    "js-manager",
    { code: 'return await review([{ key: "tests", value: "add tests" }]);' },
    { agent: "Manager" },
  );
  assert.equal(recorded.state, "completed", JSON.stringify(recorded.error));
  assert.equal(recorded.value.kind, "recorded");

  const relayed = await host.call("js-manager", { code: "return await review([{ key: \"tests\", value: null }]);" }, {
    agent: "Manager",
  });
  // The hand-over is performed and reported whole (根 §2.4 第 3 步): compose advances the
  // tenure only for `ok` + `retired` + `barrier` + `successor`.
  assert.equal(relayed.state, "completed", JSON.stringify(relayed.error));
  assert.equal(relayed.value.ok, true, JSON.stringify(relayed.value));
  assert.equal(relayed.value.kind, "relay");
  const handed = relayed.value.handedOver;
  assert.equal(handed.retired, true);
  assert.equal(handed.barrier, true);
  assert.equal(typeof handed.successor, "string");
  assert.equal(handed.record.incumbent, "ses_main");
  assert.equal(handed.record.successor, handed.successor);
  assert.equal(handed.record.phase, "sent");

  // The order is the record: create (inert), the record, the brief, the stop.
  const kinds = host.calls.map(([kind]) => kind);
  const created = kinds.indexOf("create");
  const prompted = kinds.indexOf("prompt");
  const stopped = kinds.indexOf("interrupt");
  assert.ok(created >= 0 && prompted > created, kinds.join(","));
  assert.ok(stopped > prompted, kinds.join(","));
  const prompt = host.calls.find(([kind]) => kind === "prompt")[1];
  assert.equal(prompt.sessionID, handed.successor);
  // The brief carries the record's message id: a repeated delivery is the same
  // input, which is what makes recovery after a crash idempotent.
  assert.equal(prompt.id, handed.record.briefId);
  assert.match(prompt.id, /^msg_[0-9A-Za-z]{26}$/);

  // The record on disk names both identities and the barrier.
  const record = JSON.parse(await fs.readFile(path.join(host.dir, ".Herdchestra", "ses_main.relay.json"), "utf8"));
  assert.equal(record.incumbent, "ses_main");
  assert.equal(record.successor, handed.successor);
  assert.equal(record.retired, true);
  assert.equal(record.barrier, true);
  assert.equal(record.inheritFrom, "ses_main");
  assert.equal(record.briefId, prompt.id);
  // The successor reads its OWN copy, not a directory scan (root §1.1 item 1):
  // the hand-over writes the record under the successor's name before the brief
  // so its first request already applies the barrier.
  const pointer = JSON.parse(
    await fs.readFile(path.join(host.dir, ".Herdchestra", `${handed.successor}.relay.json`), "utf8"),
  );
  assert.equal(pointer.incumbent, "ses_main");
  assert.equal(pointer.successor, handed.successor);
  assert.equal(pointer.phase, "sent");
  await host.cleanup();
});

test("a record that is not at the session's own path is not found by a scan", async () => {
  // The scan fallback was removed with the pointer: a successor reads exactly
  // one path. A stray record that names this session but lives under another
  // name is not authority, and must not gate or cut this request.
  const host = await fakeHost();
  const record = relayRecord({
    sessionID: "ses_other",
    incumbent: "ses_other",
    successor: "ses_main",
    tenure: 1,
    milestone: "execute",
    phase: "sent",
    requestGate: "host",
    toolGate: "host",
  });
  await fs.mkdir(path.join(host.dir, ".Herdchestra"), { recursive: true });
  assert.equal(writeRelay(relayFile(host.dir, "ses_other"), record).ok, true);
  const out = await host.call("js-worker", { code: "return 1;" }, { agent: "coder", sessionID: "ses_main" });
  assert.equal(out.state, "completed", JSON.stringify(out.error));
  await host.cleanup();
});

test("a retired tenure is refused at both gates and the successor is cut at the barrier", async () => {
  const host = await fakeHost();
  host.seesSession("ses_main");
  await host.operatorSays("fix the parser");
  await host.call("js-manager", { code: 'return await review([{ key: "tests", value: "add tests" }]);' }, {
    agent: "Manager",
  });
  const relayed = await host.call("js-manager", { code: 'return await review([{ key: "tests", value: null }]);' }, {
    agent: "Manager",
  });
  assert.equal(relayed.value.ok, true, JSON.stringify(relayed.value));
  const successor = relayed.value.handedOver.successor;

  // The tool gate: the retired session may not call a tool at all, whatever its
  // role or budget.
  await assert.rejects(
    host.call("js-worker", { code: "return 1;" }, { agent: "coder", sessionID: "ses_main" }),
    (error) => error.message.startsWith("RELAY_RETIRED"),
  );

  // The request gate: the retired session's request is refused, not trimmed —
  // a trimmed copy would let it keep working on an empty memory.
  await assert.rejects(
    host.contextHooks[0]({
      sessionID: "ses_main",
      agent: "Manager",
      tools: {},
      system: [],
      messages: [{ role: "user", content: [{ type: "text", text: "keep going" }] }],
    }),
    (error) => error.code === "RELAY_RETIRED",
  );

  // The successor is not refused; its copy starts with the succession notice
  // and carries nothing of the conversation before the relay.
  const copy = await host.contextHooks[0]({
    sessionID: successor,
    // The successor's agent is the one a relay starts; the adapter owns it, so
    // the hook builds the request instead of leaving a foreign agent alone.
    agent: "Manager",
    agent: "Manager",
    tools: {},
    system: [],
    messages: [
      { role: "user", content: [{ type: "text", text: "old work" }] },
      { role: "assistant", content: [{ type: "text", text: "done" }] },
    ],
  });
  const texts = copy.event.messages.map((message) =>
    Array.isArray(message.content) ? message.content.map((part) => part.text ?? "").join("") : String(message.content),
  );
  assert.match(texts[0], /you are the successor/);
  assert.equal(texts.includes("old work"), false, JSON.stringify(texts));
  await host.cleanup();
});

test("a counted barrier keeps only what the incumbent had not already seen", async () => {
  const host = await fakeHost();
  // The operator's own words, recorded where they belong: the prompt hook.
  host.seesSession("ses_main");
  await host.operatorSays("fix the parser");
  await host.call("js-manager", { code: 'return await review([{ key: "tests", value: "add tests" }]);' }, {
    agent: "Manager",
  });
  // The host reports two messages on screen for the incumbent when the relay
  // happens: the operator's request and the incumbent's answer. The successor's
  // copy must start after both, so the third message is the first one it sees.
  //
  // `Session.context` reports `PublicSessionMessage` (`type: "user"`/`"assistant"`
  // with `text`), not the request-copy shape the hook receives later. The first
  // user turn here is also what the record keeps as `operatorInput`.
  host.messagesSeen.set("ses_main", [
    // The operator's original request: 根 §2.4 第 4 步 keeps it for the successor.
    { type: "user", text: "fix the parser" },
    // The conversation the retired tenure answered: 根 §2.4 第 4 步 cuts it.
    { type: "assistant", text: "working on it" },
    { type: "user", text: "old work" },
    { type: "assistant", text: "done" },
  ]);
  const relayed = await host.call("js-manager", { code: 'return await review([{ key: "tests", value: null }]);' }, {
    agent: "Manager",
  });
  assert.equal(relayed.value.ok, true, JSON.stringify(relayed.value));
  const successor = relayed.value.handedOver.successor;

  const copy = await host.contextHooks[0]({
    sessionID: successor,
    agent: "Manager",
    tools: {},
    system: [],
    messages: [
      // The four messages the incumbent had already answered (`fenceThrough`
      // is the transcript length, 4). They are the retired tenure's; the cut
      // removes all of them.
      { role: "user", content: [{ type: "text", text: "fix the parser" }] },
      { role: "assistant", content: [{ type: "text", text: "working on it" }] },
      { role: "user", content: [{ type: "text", text: "old work" }] },
      { role: "assistant", content: [{ type: "text", text: "done" }] },
      // Everything after the fence is the successor's own turn.
      { role: "user", content: [{ type: "text", text: "the new request" }] },
    ],
  });
  const texts = copy.event.messages.map((message) =>
    Array.isArray(message.content) ? message.content.map((part) => part.text ?? "").join("") : String(message.content),
  );
  // The counted window starts at index 4: the notice (carrying the operator's
  // original request), then the successor's own turn — never anything the
  // incumbent had already answered.
  assert.match(texts[0], /you are the successor/);
  // 根 §2.4 第 4 步's original input travels INSIDE the notice message, taken from the
  // record's `operatorInput`: a separate operator turn would be textually
  // identical to the retired tenure's own first user turn.
  assert.match(texts[0], /fix the parser/, JSON.stringify(texts));
  // Everything the incumbent had on screen is gone — including the original
  // turn as a transcript entry: it survives only inside that committed message.
  assert.equal(texts.filter((text) => text === "fix the parser").length, 0, JSON.stringify(texts));
  assert.equal(texts.includes("working on it"), false, JSON.stringify(texts));
  assert.equal(texts.includes("old work"), false, JSON.stringify(texts));
  assert.equal(texts.includes("done"), false, JSON.stringify(texts));
  // The successor's own turn survives the cut.
  assert.equal(texts.includes("the new request"), true, JSON.stringify(texts));
  await host.cleanup();
});

test("an unreadable relay record refuses everybody instead of nobody", async () => {
  const host = await fakeHost();
  await fs.mkdir(path.join(host.dir, ".Herdchestra"), { recursive: true });
  await fs.writeFile(path.join(host.dir, ".Herdchestra", "ses_main.relay.json"), "{ not json");
  // A record we cannot read says somebody retired, not that nobody did: both a
  // tool call and a request are refused.
  await assert.rejects(
    host.call("js-worker", { code: "return 1;" }, { agent: "coder", sessionID: "ses_main" }),
    (error) => error.message.startsWith("RELAY_CORRUPT"),
  );
  await assert.rejects(
    host.contextHooks[0]({
      sessionID: "ses_main",
      agent: "Manager",
      tools: {},
      system: [],
      messages: [{ role: "user", content: [{ type: "text", text: "go" }] }],
    }),
    (error) => error.code === "RELAY_CORRUPT",
  );
  await host.cleanup();
});

test("a successor adopts the predecessor's roster, unclaimed results and bound devops", async () => {
  const host = await fakeHost();
  host.seesSession("ses_main");
  await host.operatorSays("keep the workers");
  // The incumbent holds one live worker, one idle worker, one finished result
  // nobody joined, and a road-bound devops (根 §2.4).
  const table = host.table("ses_main");
  table.adopt([
    { name: "w1", role: "coder", sessionID: "ses_w1" },
    { name: "w2", role: "devops", sessionID: "ses_w2" },
  ]);
  table.settled("ses_w2", { text: "unread result" });
  host.bindDevops("w2");

  await host.call("js-manager", { code: 'return await review([{ key: "tests", value: "add tests" }]);' }, {
    agent: "Manager",
  });
  const relayed = await host.call("js-manager", { code: 'return await review([{ key: "tests", value: null }]);' }, {
    agent: "Manager",
  });
  assert.equal(relayed.value.ok, true, JSON.stringify(relayed.value));
  const successor = relayed.value.handedOver.successor;

  // The successor's own table, created on its first fork/join, holds what the
  // record preserved: no re-fork, no re-sent brief.
  const adoptedTable = host.table(successor);
  assert.equal(adoptedTable.sessionIDOf("w1"), "ses_w1", "the live worker keeps its session");
  assert.equal(adoptedTable.sessionIDOf("w2"), "ses_w2", "the idle worker keeps its name");
  assert.deepEqual(
    adoptedTable.unclaimed().map((entry) => [entry.name, entry.sessionID]),
    [["w2", "ses_w2"]],
    "the result nobody joined survives the relay",
  );
  assert.equal(
    host.isDevopsBound("w2", successor),
    true,
    "the road-bound devops does not change hands: the successor's own road holds it",
  );
  await host.cleanup();
});

test("a successor's first join receives the result its adopted roster carries", async () => {
  // 根 §2.4 keeps unclaimed results across a relay, and the record carries them.
  // The successor's table is created lazily, and creating it is what adopts the
  // roster; `join` read the raw table map instead, answered PROGRAM_BUSY (a
  // refusal about a program, for a session with no table at all), and the
  // preserved result stayed unreachable through the one member that reads
  // results. This is the first `join`, not a later one.
  const host = await fakeHost();
  host.seesSession("ses_main");
  await host.operatorSays("keep the workers");
  const table = host.table("ses_main");
  table.adopt([{ name: "w1", role: "coder", sessionID: "ses_w1" }]);
  // The worker finished before the relay; nobody joined it.
  table.settled("ses_w1", { text: "the parser is fixed" });

  await host.call("js-manager", { code: 'return await review([{ key: "tests", value: "add tests" }]);' }, {
    agent: "Manager",
  });
  const relayed = await host.call("js-manager", { code: 'return await review([{ key: "tests", value: null }]);' }, {
    agent: "Manager",
  });
  assert.equal(relayed.value.ok, true, JSON.stringify(relayed.value));
  const successor = relayed.value.handedOver.successor;
  assert.deepEqual(
    relayed.value.handedOver.record.unclaimed.map((entry) => entry.sessionID),
    ["ses_w1"],
    "the record carries the result",
  );

  const joined = await host.call("js-manager", { code: "return await join();" }, {
    agent: "Manager",
    sessionID: successor,
  });
  assert.equal(joined.value.ok, true, JSON.stringify(joined.value));
  assert.equal(joined.value.record.name, "w1");
  assert.equal(joined.value.record.sessionID, "ses_w1");
  assert.deepEqual(joined.value.record.outcome, { text: "the parser is fixed" });
  // The claim was taken: the queue behind it is empty. The worker itself is
  // still in the roster, so a second `join` waits on that session instead of
  // answering twice — an empty queue is not a completion, and this case does
  // not wait for the host to say one happened.
  assert.equal(host.table(successor).unclaimed().length, 0, "a claimed result leaves the queue");
  await host.cleanup();
});

test("a relay writes its record into the predecessor's own workspace, not the plugin host's", async () => {
  // `resolveSessionRoot` answers per session (the operator's stated root, then
  // the host's session location), while `ctx.location` describes the plugin
  // host. Writing the record under the host root left the two gates looking in
  // the incumbent's workspace for a record that was never there.
  const sessionRoot = await fs.mkdtemp(path.join(os.tmpdir(), "herdchestra-session-"));
  const host = await fakeHost({
    sessions: {
      // The host's own answer for this session's location. The plugin asks
      // before the session's state exists, so this is the only fact it has.
      get: async (input) => ({
        data: {
          id: input.sessionID,
          model: { providerID: "kw92", id: "lazy/task" },
          location: { directory: sessionRoot },
        },
      }),
    },
  });
  host.seesSession("ses_main");
  await host.operatorSays("ship the parser fix");
  await host.call("js-manager", { code: 'return await review([{ key: "tests", value: "add tests" }]);' }, {
    agent: "Manager",
  });
  const relayed = await host.call("js-manager", { code: 'return await review([{ key: "tests", value: null }]);' }, {
    agent: "Manager",
  });
  assert.equal(relayed.value.ok, true, JSON.stringify(relayed.value));

  const inSession = path.join(sessionRoot, ".Herdchestra", "ses_main.relay.json");
  assert.equal(fsSync.existsSync(inSession), true, "the record is in the incumbent's workspace");
  const record = JSON.parse(fsSync.readFileSync(inSession, "utf8"));
  assert.equal(record.operatorInput, "ship the parser fix");
  assert.equal(
    fsSync.existsSync(path.join(host.dir, ".Herdchestra", "ses_main.relay.json")),
    false,
    "the plugin host's root holds no record for this session",
  );
  await host.cleanup();
  await fs.rm(sessionRoot, { recursive: true, force: true });
});

test("a session is only marked unrecorded when the activation really never saw it", async () => {
  // The live defect this pins: when the event pump could not read the host's
  // stream, no `session.created` was ever processed, so every session looked
  // like one that predates the fact and every relay refused
  // ORIGINAL_INPUT_MISSING. A session the activation has seen must capture on
  // its first operator prompt; one it has never seen must not.
  const host = await fakeHost();
  host.seesSession("ses_main");
  const seen = await host.operatorSays("ship the parser fix");
  assert.equal(seen.original.captured, true, JSON.stringify(seen.original));

  // A session the host does not know either: its parent stays unknown, so the
  // receipt is not the operator's first by any fact the adapter holds.
  const unknown = await fakeHost({ sessions: { get: async () => { throw new Error("unknown session"); } } });
  const stranger = await unknown.operatorSays("a later prompt", { sessionID: "ses_unseen", messageID: "msg_2" });
  assert.equal(stranger.original.captured, false);
  assert.equal(stranger.original.reason, "PREDATES_RECORDING");
  assert.equal(
    fsSync
      .readdirSync(path.join(unknown.dir, ".Herdchestra"))
      .some((name) => name.startsWith("original-") && name.endsWith(".json")),
    false,
    "no original fact was published",
  );
  await unknown.cleanup();
  await host.cleanup();
});

test("a prompt that arrives before session.created still captures, because the host reports the parent", async () => {
  // The live defect: the pump is asynchronous, so the first prompt can run
  // before the session's `session.created` is drained. The table alone then
  // answers "unknown" and the capture is skipped on every real session. The
  // host's own `get` answers the parent, and a top-level answer is a fact.
  const host = await fakeHost();
  const seen = await host.operatorSays("ship the parser fix");
  assert.equal(seen.original.captured, true, JSON.stringify(seen.original));
  await host.cleanup();
});

test("a child session still never captures, even when the host reports its parent", async () => {
  const host = await fakeHost({
    sessions: {
      get: async (input) => ({
        data: { id: input.sessionID, parentID: "ses_manager", model: { providerID: "kw92", id: "lazy/task" } },
      }),
    },
  });
  const child = await host.operatorSays("do the slice", { sessionID: "ses_child", messageID: "msg_c" });
  assert.equal(child.original.captured, false);
  assert.equal(child.original.reason, "CHILD_SESSION");
  await host.cleanup();
});

test("a successor's first empty review confirms instead of asking the operator", async () => {
  // The live defect this pins: `recordRelayStart` used to throw on a successor
  // (core refuses a successor for a ledger that already finished), the throw was
  // swallowed, and the successor came back as tenure 0 — so its first empty
  // review was the first tenure's `askUser` instead of the successor's
  // `confirm` (根 §2.4 第 3 步).
  const host = await fakeHost();
  host.seesSession("ses_main");
  await host.operatorSays("relay me");
  await host.call("js-manager", { code: 'return await review([{ key: "tests", value: "add tests" }]);' }, {
    agent: "Manager",
  });
  const relayed = await host.call("js-manager", { code: 'return await review([{ key: "tests", value: null }]);' }, {
    agent: "Manager",
  });
  assert.equal(relayed.value.ok, true, JSON.stringify(relayed.value));
  const successor = relayed.value.handedOver.successor;

  // The successor's row names its own tenure and the milestone it is at.
  const rows = (await fs.readFile(path.join(host.dir, ".Herdchestra", `${successor}.jsonl`), "utf8"))
    .split("\n")
    .filter((line) => line !== "")
    .map((line) => JSON.parse(line));
  const row = rows.filter((record) => record.type === "review").at(-1);
  assert.equal(row.tenure, 1, "the row names the successor's tenure, not the predecessor's");
  assert.equal(row.milestone, "execute");
  assert.equal(row.successor, true);

  // And the successor's own ledger answers a first empty review with `confirm`,
  // never with the first tenure's question to the operator.
  const ledger = host.state(successor).ledgerFor("manager");
  assert.equal(ledger.tenure, 1);
  const receipt = ledger.review([]);
  assert.equal(receipt.kind, "confirm", JSON.stringify(receipt));
  assert.equal(ledger.milestone, "close");
  await host.cleanup();
});

test("an empty review at close does not close the work while a worker is still running", async () => {
  // 根 §2.3: an empty review at `close` closes the work ONLY when every real
  // execution resource is settled. The ledger core builds defaults that answer
  // "settled", so the adapter must inject the real fact or a Manager can close
  // a work whose workers are still running — a "done" nobody performed.
  const host = await fakeHost();
  host.seesSession("ses_main");
  await host.operatorSays("close when the workers are done");
  const ledger = host.state("ses_main").ledgerFor("manager");
  // Walk the ledger to `close` the way the host does: one review with an
  // obligation, then the empty review that relays, then the successor's
  // confirm. Driving the ledger directly keeps this test about the closing
  // gate, not about the relay.
  ledger.review([{ key: "work", value: "finish it" }]);
  const relayed = ledger.review([{ key: "work", value: null }]);
  assert.equal(relayed.kind, "relay", JSON.stringify(relayed));
  ledger.openSuccessor();
  const confirmed = ledger.review([]);
  assert.equal(confirmed.kind, "confirm", JSON.stringify(confirmed));
  assert.equal(ledger.milestone, "close");

  // A worker the Manager started is still running.
  const table = host.table("ses_main");
  table.adopt([{ name: "w1", role: "coder", sessionID: "ses_w1" }]);
  assert.equal(table.liveCount(), 1);

  const blocked = ledger.review([]);
  assert.equal(blocked.kind, "nextReview", JSON.stringify(blocked));
  assert.equal(blocked.reason, "RESOURCES_UNSETTLED");
  assert.equal(ledger.milestone, "close", "the work did not close");

  // The worker reports back: nothing is running, and the next empty review
  // closes the work.
  table.settled("ses_w1", { text: "finished" });
  assert.equal(table.liveCount(), 0);
  const closed = ledger.review([]);
  assert.equal(closed.kind, "closed", JSON.stringify(closed));
  assert.equal(ledger.milestone, "done");
  await host.cleanup();
});

test("the workspace root is the operator's project, not the server's launch directory", async () => {
  // Measured on a live host: a server launched with `--cwd packages/cli` reports
  // `location.directory` as that package dir while the operator's project is
  // elsewhere. Persisted facts must land in the project, or the writer and the
  // reader of `.Herdchestra/` disagree and a capture looks lost.
  const project = path.join(os.tmpdir(), "herd-project");
  assert.equal(
    workspaceRootOf({ location: { directory: "/tmp/launch/dir", project: { directory: project } } }),
    path.resolve(project),
  );
  // A host that reports no project keeps the single directory it gives.
  assert.equal(workspaceRootOf({ location: { directory: "/tmp/launch/dir" } }), "/tmp/launch/dir");
  // A relative directory is resolved, never used as-is.
  assert.equal(workspaceRootOf({ location: { directory: "rel" } }), path.resolve("rel"));
});

test("a session's facts live in the workspace the session reports, not the plugin host's", async () => {
  // Measured live: the plugin context describes the plugin host (its launch
  // directory), while the session's own record names the workspace the operator
  // opened. A fact written under the wrong root is a fact the reader never finds.
  const workspace = path.join(os.tmpdir(), "herd-session-workspace");
  const host = await fakeHost({
    sessions: {
      get: async (input) => ({
        data: { id: input.sessionID, location: { directory: workspace }, model: { providerID: "kw92", id: "lazy/task" } },
      }),
    },
  });
  host.seesSession("ses_main");
  await host.operatorSays("ship the parser fix");
  assert.equal(
    fsSync.existsSync(path.join(workspace, ".Herdchestra")) &&
      fsSync.readdirSync(path.join(workspace, ".Herdchestra")).some((name) => name.endsWith(".json")),
    true,
    "the original input was written under the session's workspace",
  );
  await host.cleanup();
});

test("an operator-stated root wins over every host answer", async () => {
  // Measured live: the host reported the server's launch directory as the
  // session's location, so no host answer names the workspace. The operator's
  // own statement is the fact when it exists.
  const stated = path.join(os.tmpdir(), "herd-stated-root");
  const previous = process.env.HERDCHESTRA_ROOT;
  process.env.HERDCHESTRA_ROOT = stated;
  try {
    assert.equal(statedRoot({ location: { directory: "/tmp/launch" } }), path.resolve(stated));
    assert.equal(
      statedRoot({ location: { directory: "/tmp/launch" }, options: { root: "/tmp/option" } }),
      path.resolve("/tmp/option"),
      "the plugin option wins over the environment",
    );
    // The function that promises "the workspace" must answer the same way, or a
    // reader takes the host's launch directory for the operator's workspace.
    assert.equal(
      workspaceRootOf({ location: { directory: "/tmp/launch", project: { directory: "/tmp/project" } } }),
      path.resolve(stated),
    );
    assert.equal(
      workspaceRootOf({ location: { directory: "/tmp/launch" }, options: { root: "/tmp/option" } }),
      path.resolve("/tmp/option"),
    );
  } finally {
    if (previous === undefined) delete process.env.HERDCHESTRA_ROOT;
    else process.env.HERDCHESTRA_ROOT = previous;
  }
  assert.equal(statedRoot({ location: { directory: "/tmp/launch" } }), null, "no statement, no override");
});

test("a host that cannot report a session's workspace keeps the plugin root", async () => {
  const host = await fakeHost({ sessions: { get: async () => { throw new Error("no session"); } } });
  host.seesSession("ses_main");
  const seen = await host.operatorSays("ship the parser fix");
  // The capture still happens: the plugin root is the documented fallback.
  assert.equal(seen.original.captured, true, JSON.stringify(seen.original));
  await host.cleanup();
});

test("the event pump consumes the subscription shape the host really gives", async () => {
  // An async iterable (the bridge's shape) and a plain iterable (a fake host's)
  // are both consumed. Anything else is reported, never read as an empty stream:
  // an idle pump is what made every session look unrecorded on the live host.
  const collect = async (subscription) => {
    const events = eventsOf(subscription);
    if (events.ok !== true) return { ok: false, reason: events.reason };
    const seen = [];
    for await (const event of events.iterable) seen.push(event);
    return { ok: true, seen };
  };
  const asyncIterable = (items) => ({
    [Symbol.asyncIterator]: () => {
      let index = 0;
      return {
        next: async () => (index < items.length ? { done: false, value: items[index++] } : { done: true }),
      };
    },
  });
  const fromAsync = await collect(asyncIterable([{ type: "session.created", data: { sessionID: "a" } }]));
  assert.equal(fromAsync.ok, true);
  assert.equal(fromAsync.seen.length, 1);
  const fromSync = await collect([{ type: "session.created", data: { sessionID: "b" } }]);
  assert.equal(fromSync.seen.length, 1);
  const unusable = await collect({ not: "a stream" });
  assert.equal(unusable.ok, false);
  assert.equal(unusable.reason, "UNSUPPORTED_EVENT_STREAM");
  assert.equal((await collect(null)).reason, "NO_EVENT_SUBSCRIPTION");
});

test("a successor inherits the predecessor's language once and starts a fresh ledger", async () => {
  const host = await fakeHost();
  host.seesSession("ses_main");
  await host.operatorSays("inherit the language");
  await host.call("js-manager", { code: 'return await review([{ key: "tests", value: "add tests" }]);' }, {
    agent: "Manager",
  });
  // Give the incumbent a language before the relay.
  host.state("ses_main").bindLanguage({ ladder: { explicit: "SimplifiedChinese" } });
  // The successor inherits only from a predecessor whose own facts are on disk,
  // so the incumbent's file is read here rather than assumed: a first review
  // persists its row, and the state's Scope writes its resets on the same file.
  const relayed = await host.call("js-manager", { code: 'return await review([{ key: "tests", value: null }]);' }, {
    agent: "Manager",
  });
  assert.equal(relayed.value.ok, true, JSON.stringify(relayed.value));
  const successor = relayed.value.handedOver.successor;

  // The successor's own facts: the inherited language, no Scope of its own, and
  // the review row that starts the new tenure.
  const read = async (id) =>
    (await fs.readFile(path.join(host.dir, ".Herdchestra", `${id}.jsonl`), "utf8"))
      .split("\n")
      .filter((line) => line !== "")
      .map((line) => JSON.parse(line));
  const records = await read(successor);
  // The hand-over copies the predecessor's facts into the successor's file
  // before the receipt: the language travels (rebound to the new id), the Scope
  // does not, and the review row names the new tenure.
  const language = records.filter((record) => record.type === "language").at(-1);
  assert.equal(language.binding.language, "SimplifiedChinese");
  assert.equal(language.binding.source, "fork");
  assert.equal(language.binding.sessionId, successor);
  assert.equal(language.binding.ownerSessionId, "ses_main");
  // §6.2: a persisted `resetScope` proves an old event existed, not that a
  // budget is current, so no Scope record crosses the relay. The successor is
  // told to `resetScope` like any cold start.
  const scopes = records.filter((record) => record.type === "scope");
  assert.deepEqual(scopes, [], "no Scope record is inherited");
  const review = records.filter((record) => record.type === "review").at(-1);
  assert.equal(review.tenure, 1, "the row names the successor's tenure");
  // The row names the milestone the successor is already at: the relay record
  // opened it at `execute`, and its first empty review is the `confirm` that
  // moves it on. A row one step behind would make the restart replay a step the
  // successor already took.
  assert.equal(review.milestone, "execute");
  assert.equal(review.successor, true, "the row marks the successor tenure");
  assert.deepEqual(review.obligations, []);

  // Inheritance happens once: the copy is already on disk, so a second
  // construction copies nothing more and the file does not grow.
  const before = records.length;
  assert.equal(host.state(successor).inherit(), false);
  assert.equal((await read(successor)).length, before);
  await host.cleanup();
});

test("Plan's first empty review in S1 asks the operator; a Manager's never does", async () => {
  // 根 §2.2, §2.4 第 1 步: the question belongs to Plan's S1 alone.
  const host = await fakeHost();
  const asked = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan" });
  // The question IS an `ask`: the program parks for the answer and the turn is
  // interrupted, exactly as `this.ask` does (根 §3.3 example 7).
  assert.equal(asked.state, "parked", JSON.stringify(asked.error));
  assert.equal(asked.reason, "ask");
  assert.equal(asked.payload.question, "我需要干什么？");
  assert.equal(asked.ask.program, asked.name);
  // The question is shown through the synthetic channel, not invented as an
  // answer.
  const synthetic = host.calls.filter(([kind]) => kind === "synthetic");
  assert.equal(synthetic.length, 1);
  assert.equal(synthetic[0][1].text, "我需要干什么？");

  // A Manager runs unattended: its first empty review verifies, and nobody is
  // asked.
  const manager = await host.call("js-manager", { code: "return await review([]);" }, { agent: "Manager" });
  assert.equal(manager.state, "completed", JSON.stringify(manager.error));
  assert.equal(manager.value.kind, "confirm");
  assert.equal(host.calls.filter(([kind]) => kind === "synthetic").length, 1, "the Manager asked nobody");
  await host.cleanup();
});

test("ask parks the program, shows the question and resumes with the operator's reply", async () => {
  const host = await fakeHost();
  // 根 §2.2: the question exists only in Plan's S1, so the program that asks is
  // a Plan program.
  const parked = await host.call("plan", {
    code: 'const answer = await this.ask("which file?"); return { answer };',
  }, { agent: "Plan" });
  assert.equal(parked.state, "parked");
  assert.equal(parked.reason, "ask");
  assert.equal(parked.payload.question, "which file?");
  assert.equal(parked.ask.ok, true);
  const synthetic = host.calls.filter(([kind]) => kind === "synthetic");
  assert.equal(synthetic.at(-1)[1].text, "which file?");

  // The operator's next prompt answers it. The payload is the host's
  // (`packages/core/src/session/prompt.ts` prepare): `{ sessionID, messageID,
  // prompt: { text }, metadata, delivery }`.
  const [promptHook] = host.promptHooks;
  const answered = await promptHook({
    sessionID: "ses_main",
    messageID: "msg_ask",
    prompt: { text: "src/api.mjs" },
    delivery: "steer",
  });
  assert.equal(answered.answered.answer, "src/api.mjs");
  assert.equal(answered.answered.resumed.value.answer, "src/api.mjs");
  await host.cleanup();
});

test("a malformed prompt payload leaves the ask pending instead of answering it", async () => {
  const host = await fakeHost();
  await host.call("plan", { code: 'const answer = await this.ask("which file?"); return { answer };' }, {
    agent: "Plan",
  });
  const [promptHook] = host.promptHooks;
  // A payload the host does not document is a host change, not an empty reply.
  const answered = await promptHook({ sessionID: "ses_main", text: "src/api.mjs" });
  assert.equal(answered.answered.answered, false);
  assert.equal(answered.answered.reason, "PROMPT_HOOK_PAYLOAD");

  // The question is still pending, so the real prompt still answers it.
  const real = await promptHook({
    sessionID: "ses_main",
    messageID: "msg_ask",
    prompt: { text: "src/api.mjs" },
    delivery: "steer",
  });
  assert.equal(real.answered.answer, "src/api.mjs");
  await host.cleanup();
});

test("Plan writes its draft and hands it to a Manager session on an empty review", async () => {
  const host = await fakeHost();
  // 根 §2.4 第 1 步: the first empty review of the first tenure is the operator's
  // question, so the walk answers it and relays the way a live host does. The
  // session that ends at 待执行 is the LAST successor.
  const sessionID = await walkPlanToReady(host);
  // The draft path is derived from the session that STARTED the work (§2.2), and
  // a Plan relay carries it to its successor: every successor of this walk reads
  // and writes the same file its predecessor wrote, so the canonical path is
  // still the first session's.
  const draftPath = planDraftPath(host.dir, "ses_main");
  await fs.mkdir(path.dirname(draftPath), { recursive: true });
  // 根 §2.2: "系统会告知确切路径". Plan's system prompt is per agent and cannot
  // carry a per-session path, so the receipt names it — every Plan answer, not
  // only the first, because the model may need it after a compaction.
  const first = await host.call("plan", { code: 'return await review([{ key: "open", value: "x" }]);' }, {
    agent: "Plan",
    sessionID,
  });
  assert.equal(first.draft, draftPath, "the receipt names the draft the hand-off reads");
  // The draft is read-only until the tenure's first review (根 §2.3); that
  // review has just happened, so the write is allowed.
  const write = await host.call(
    "plan",
    { code: `return await this.write(${JSON.stringify(draftPath)}, "the plan\\n");` },
    { agent: "Plan", sessionID },
  );
  assert.equal(write.state, "completed", JSON.stringify(write.error));
  assert.equal(await fs.readFile(draftPath, "utf8"), "the plan\n");
  // 待执行: an empty review hands the SAVED draft to a Manager session. The
  // review carries no text of its own; the bytes come from the draft port.
  const handoff = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID });
  assert.equal(handoff.value.kind, "handoff", JSON.stringify(handoff.value));
  assert.equal(handoff.value.draft, "the plan\n");
  const created = host.calls.filter(
    ([kind, input]) => kind === "create" && input.agent === "Manager" && input.metadata?.herdchestra === "manager",
  );
  assert.equal(created.length, 1);
  const prompted = host.calls.filter(([kind]) => kind === "prompt");
  assert.equal(prompted.at(-1)[1].text, "the plan\n");
  await host.cleanup();
});

test("two separate empty reviews start two Managers; a replayed call id starts one", async () => {
  // 根 §2.2: 每次新的空账 review 都会创建一个独立的 Manager 会话. §2.4 第 6 步:
  // only the SAME physical call replayed is deduplicated. So two reviews with
  // identical draft bytes must create two Managers, and the same call id
  // answered twice must create one — the difference is the call id, never the
  // draft's content.
  const host = await fakeHost();
  // 根 §2.4 第 1 步: the walk answers the first tenure's question and relays the
  // way a live host does, so the session it returns is the one at 待执行.
  const sessionID = await walkPlanToReady(host);
  // The draft is one persistent product across the relay chain (§2.2).
  const draftPath = planDraftPath(host.dir, "ses_main");
  await fs.mkdir(path.dirname(draftPath), { recursive: true });
  await fs.writeFile(draftPath, "the plan\n");

  const managersCreated = () =>
    host.calls.filter(
      ([kind, input]) =>
        kind === "create" && input.agent === "Manager" && input.metadata?.herdchestra === "manager",
    ).length;

  // A new empty review: one Manager, from the saved draft.
  const first = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID });
  assert.equal(first.value.kind, "handoff", JSON.stringify(first.value));
  assert.equal(managersCreated(), 1);

  // Another new empty review, same draft bytes, different call id: §2.2 says a
  // second independent Manager, not a deduplication.
  const second = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID });
  assert.equal(second.value.kind, "handoff", JSON.stringify(second.value));
  assert.equal(managersCreated(), 2, "each new empty review starts its own Manager");

  // The same physical call answered twice: one Manager, and the replay says so.
  const again = await host.call(
    "plan",
    { code: "return await review([]);" },
    { agent: "Plan", sessionID, id: "call_replayed" },
  );
  assert.equal(again.value.kind, "handoff", JSON.stringify(again.value));
  const afterReplayable = managersCreated();
  const replay = await host.call(
    "plan",
    { code: "return await review([]);" },
    { agent: "Plan", sessionID, id: "call_replayed" },
  );
  assert.equal(replay.replayed, true, JSON.stringify(replay));
  assert.equal(managersCreated(), afterReplayable, "a replayed call id starts nothing");
  await host.cleanup();
});

test("a replayed call id with a different payload is refused, and every field counts", async () => {
  // 根 §2.4 第 6 步: 同一物理调用（同一 callId）重放时直接返回原回执；若同一
  // callId 换用不同载荷，立即报错拒绝。The payload is the whole tool input —
  // comparing only the program source would let a replay that changed the scope
  // annotation (or any field added later) through as if it were the same call.
  //
  // A mismatch is answered through the host's own failure channel: the host
  // admits a call id once, and `execute.before` is the seam that throws for a
  // refused call (`plugin.mjs`), so the model reads `CALL_REPLAY_MISMATCH` with
  // the reason instead of a receipt that looks like a successful replay.
  const host = await fakeHost();
  const code = "return 1;";
  // The first call carries the payload every later one is compared against: the
  // scope annotation included, so a replay must repeat it exactly. The scope is
  // fixed per call because the annotation is part of the payload — a different
  // annotation IS a different call (§2.4 第 6 步), even under one id.
  const scopeOf = (goal, budget) => ({ kind: "resetScope", newScopeGoal: goal, newScopeRetentionBudget: budget });
  const scope = scopeOf("test", 50);
  const first = await host.call("js-manager", { code, scope }, { agent: "Manager", id: "call_same" });
  assert.equal(first.state, "completed", JSON.stringify(first));

  // Same call id, same payload: the original receipt, nothing runs again.
  const replay = await host.call("js-manager", { code, scope }, { agent: "Manager", id: "call_same" });
  assert.equal(replay.replayed, true, JSON.stringify(replay));
  assert.equal(replay.state, "completed", JSON.stringify(replay));

  // A different payload is refused BEFORE anything runs: the host's gate
  // (`execute.before`) is the refusal channel for a call the adapter will not
  // perform, and it runs before the tool's `execute` (host: `beforeExecute`
  // precedes `executeTool`). The same gate decides `scope` admission, so the
  // comparison lives where every other refusal already lives.
  await assert.rejects(
    host.call("js-manager", { code: "return 2;", scope }, { agent: "Manager", id: "call_same" }),
    (error) => error.message.startsWith("CALL_REPLAY_MISMATCH"),
  );
  // Same call id, same code, different SCOPE: also refused. This is the field a
  // source-only comparison would have missed.
  await assert.rejects(
    host.call("js-manager", { code, scope: scopeOf("other", 7) }, { agent: "Manager", id: "call_same" }),
    (error) => error.message.startsWith("CALL_REPLAY_MISMATCH"),
  );

  // A payload that differs only in key order is the same payload: the
  // comparison is on the encoded value, not on the JSON text the host sent.
  const reordered = await host.call(
    "js-manager",
    { scope: scopeOf("test", 50), code },
    { agent: "Manager", id: "call_same" },
  );
  assert.equal(reordered.replayed, true, JSON.stringify(reordered));

  // Nothing ran twice, and no refused replay left a program behind.
  assert.deepEqual(host.state("ses_main").calls.liveNames(), []);
  await host.cleanup();
});

test("after a cold start a call id replays its original receipt, or refuses a changed payload", async () => {
  // 根 §2.4 第 6 步 does not stop at the process boundary. The program's
  // receipt and payload are memory, so the adapter keeps them in
  // `.Herdchestra/<session>.calls.jsonl`: the id is written before the call
  // runs, the finished receipt after it. A new process therefore replays the
  // ORIGINAL receipt, refuses a changed payload with CALL_REPLAY_MISMATCH, and
  // refuses an id whose call never finished with CALL_REPLAY_LOST — instead of
  // running any of them again.
  const first = await fakeHost();
  const code = "return { fixed: 1 };";
  const scope = { kind: "resetScope", newScopeGoal: "test", newScopeRetentionBudget: 50 };
  const done = await first.call("js-manager", { code, scope }, { agent: "Manager", id: "call_cold" });
  assert.equal(done.state, "completed", JSON.stringify(done));
  // An id that was admitted but whose receipt never landed (the process died
  // mid-call) is the other half of the same fact.
  first.state("ses_main").noteCall("call_unfinished", { code: "return 2;", scope });
  // The journal keeps a payload DIGEST, not the tool input (root §1.1 item 1):
  // the admission line names no program text, and the finished receipt carries
  // only the fields a replay cannot rebuild.
  const journal = (await fs.readFile(path.join(first.dir, ".Herdchestra", "ses_main.calls.jsonl"), "utf8"))
    .trim()
    .split("\n")
    .map((line) => JSON.parse(line));
  const admission = journal.find((row) => row.id === "call_cold" && row.receipt === undefined);
  assert.match(admission.payloadDigest, /^sha256:[0-9a-f]{64}$/);
  assert.equal("payload" in admission, false, "the full tool input is not stored");
  assert.equal(JSON.stringify(admission).includes("fixed: 1"), false, "no program text in the admission line");
  const finished = journal.filter((row) => row.id === "call_cold").at(-1);
  assert.equal(finished.receipt.state, "completed");
  assert.deepEqual(finished.receipt.value, { kind: "json", value: { fixed: 1 } });
  for (const redundant of ["name", "pendingOperations", "delta", "remainingWaitMs"]) {
    assert.equal(redundant in finished.receipt, false, `${redundant} is not kept`);
  }
  const dir = first.dir;
  await first.cleanup();

  // A new process over the same workspace: no program table, only the record.
  const second = await fakeHost({ directory: dir });
  const replay = await second.call("js-manager", { code, scope }, { agent: "Manager", id: "call_cold" });
  assert.equal(replay.replayed, true, JSON.stringify(replay));
  assert.equal(replay.state, "completed");
  assert.deepEqual(replay.value, { fixed: 1 }, "the original return value comes back");
  // The source line numbers are the same program text. A restored receipt may
  // report it without the line-number gutter the first process added, so the
  // comparison is on the body, which is the fact the model acts on.
  assert.ok(String(replay.source ?? "").includes(code), JSON.stringify(replay.source));

  await assert.rejects(
    second.call("js-manager", { code: "return { fixed: 2 };", scope }, { agent: "Manager", id: "call_cold" }),
    (error) => error.message.startsWith("CALL_REPLAY_MISMATCH"),
    "a changed payload after a restart is the same mismatch as in-process",
  );
  await assert.rejects(
    second.call("js-manager", { code: "return 2;", scope }, { agent: "Manager", id: "call_unfinished" }),
    (error) => error.message.startsWith("CALL_REPLAY_LOST"),
    "an id whose call never finished is neither replayed nor re-run",
  );
  // A brand-new id in the new process is an ordinary call.
  const fresh = await second.call("js-manager", { code, scope }, { agent: "Manager", id: "call_new" });
  assert.equal(fresh.state, "completed", JSON.stringify(fresh));
  await second.cleanup();
});

test("a hand-off with no saved draft is refused instead of starting a Manager", async () => {
  const host = await fakeHost();
  // 根 §2.4 第 1 步: the walk answers the first tenure's question and relays the
  // way a live host does, so the session it returns is the one at 待执行.
  const sessionID = await walkPlanToReady(host);
  // No draft on disk: an empty review at 待执行 must not start a session from
  // nothing, and the review carries no text to substitute (§2.2).
  const refused = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID });
  assert.equal(refused.value.rejection, "DRAFT_MISSING", JSON.stringify(refused.value));
  assert.equal(
    host.calls.filter(
      ([kind, input]) => kind === "create" && input.agent === "Manager" && input.metadata?.herdchestra === "manager",
    ).length,
    0,
    "a missing draft starts no Manager",
  );
  await host.cleanup();
});

test("a review carries the host's physical call id into the ledger, which replays it alone", async () => {
  // 根 §2.4 第 6 步 is unconditional: 同一物理调用（同一 callId）重放时直接返回原回执。
  //
  // The program gate (`ProgramCalls#retired`) normally answers a replay before
  // the body runs, so a test that calls the tool twice proves the GATE, not that
  // the review carried its call id into the LEDGER. The two are separate
  // memories, and only the ledger's one makes the rule unconditional — a review
  // reached from anywhere but a fresh tool call still books once.
  //
  // The id is the host's call id for the physical call: `context.id` →
  // `makeSurface({ name })` (`program-tool.mjs`) → compose's
  // `review(findings, source, { callId: this.name })`. Dropping it anywhere on
  // that chain makes the second review book a second beat — a second relay, or
  // for Plan a second Manager session.
  const host = await fakeHost();
  const callId = "call_manager_review";
  const first = await host.call("js-manager", { code: "return await review([]);" }, { agent: "Manager", id: callId });
  assert.equal(first.state, "completed", JSON.stringify(first.error));
  // 根 §2.2: a Manager's first empty review is its own verification; it asks
  // nobody.
  assert.equal(first.value.kind, "confirm", JSON.stringify(first.value));
  assert.equal(host.calls.filter(([kind]) => kind === "synthetic").length, 0, "the Manager asks nobody");

  // The session's own ledger — the object the program's surface judged with —
  // answers the repeat from its `#calls` map. No program is live here, so
  // nothing but the ledger can answer.
  const ledger = host.state("ses_main").ledgerFor("manager");
  const replay = await ledger.review([], null, { callId });
  assert.equal(replay.replayed, true, "the same physical call id answers its own receipt");
  assert.equal(replay.kind, "confirm", "the replay is the receipt the first call produced");
  assert.equal(ledger.reviewCount, 1, "a replay books no second review");

  // The id passed down is the physical call id, not the session name: a review
  // under another id is a different physical call and books again.
  const other = await ledger.review([], null, { callId: "call_other" });
  assert.equal(other.replayed, undefined, "a different call id is a different call");
  assert.equal(ledger.reviewCount, 2, "a different call id books its own review");
  await host.cleanup();
});

test("Plan has no file member other than cat and write, and no command", async () => {
  const host = await fakeHost();
  const description = host.tools.get("plan").description;
  // §3.1: each capability is named in its one spelling — members as `this.xxx`,
  // the primitives as bare names. Plan's `devops` is a primitive (root §1.1
  // item 3), so `this.devops` must not exist.
  const spellingOf = (member) =>
    ["fork", "join", "review", "devops"].includes(member) ? member : `this.${member}`;
  for (const member of [...SURFACE_MEMBERS.plan, "devops"]) {
    assert.ok(description.includes(`\`${spellingOf(member)}\``), member);
  }
  // The prose says `this.devops` does NOT exist; the member list must not
  // advertise it as a member.
  const membersLine = description.split("\n").find((line) => line.startsWith("Members:"));
  assert.equal(membersLine.includes("`this.devops`"), false);
  assert.equal(membersLine.includes("`devops`"), true);
  // The output's place and naming rule is model-facing text (root §1.1 item 3):
  // workspace-root `plans/<name>.md`, never `.Herdchestra`, and Plan is read-only
  // apart from that output and operator attachments.
  assert.match(description, /plans\/<name>\.md/);
  assert.match(description, /never lives under `\.Herdchestra`/);
  assert.match(description, /read-only/);
  assert.equal(description.includes("`this.call`"), false);
  assert.equal(description.includes("`call`"), false);
  assert.equal(description.includes("`glob`"), false);
  // A host read is refused for Plan, like for the Manager.
  await assert.rejects(host.call("read", { path: "x" }, { agent: "Plan" }), /does not belong to your role/);
  await host.cleanup();
});

test("Plan's devops primitive waits for the one dedicated answer, returns it whole and reuses the worker", async () => {
  // The devops child is created through the fake domain, which records it.
  const host = await fakeHost({
    sessions: {
      context: async () => ({
        data: [{ role: "assistant", content: [{ type: "text", text: "report: two files changed" }] }],
      }),
    },
  });
  const report = await host.call("plan", { code: 'return await devops("list the files");' }, { agent: "Plan" });
  assert.equal(report.state, "completed", JSON.stringify(report.error));
  assert.equal(report.value.ok, true);
  assert.equal(report.value.report, "report: two files changed");
  // The devops child was created and waited for; the report is not summarised.
  // The worker table sends `agent`, `title` and `parentID` (session-creation).
  const created = host.calls.find(([kind]) => kind === "create")[1];
  assert.equal(created.agent, "devops");
  // Root §1.1 item 3: ONE dedicated worker, under a fixed name.
  assert.equal(created.title, "plan-devops");
  assert.ok(host.calls.some(([kind]) => kind === "wait"));

  // The host reports the worker finished; the next investigation reuses the
  // SAME session instead of creating a replacement.
  host.table("ses_main").settled(created.id, "succeeded");
  const again = await host.call("plan", { code: 'return await devops("now the tests");' }, { agent: "Plan" });
  assert.equal(again.state, "completed", JSON.stringify(again.error));
  assert.equal(again.value.ok, true, JSON.stringify(again.value));
  assert.equal(host.calls.filter(([kind]) => kind === "create").length, 1, "the dedicated worker is not replaced");
  const prompts = host.calls.filter(([kind, input]) => kind === "prompt" && input.sessionID === created.id);
  assert.equal(prompts.length, 2);
  assert.match(prompts[1][1].text, /now the tests/);
  // `this.devops` does not exist: the capability has exactly one spelling.
  const wrong = await host.call("plan", { code: 'return await this.devops("x");' }, { agent: "Plan" });
  assert.equal(wrong.state, "failed", JSON.stringify(wrong));
  assert.match(String(wrong.error.message), /this\.devops is not a function/);
  await host.cleanup();
});

test("fission on a non-worker origin is refused and creates nothing", async () => {
  const host = await fakeHost();
  const out = await host.call("js-worker", { code: 'return await this.fission(["a", "b"]);' });
  assert.equal(out.state, "completed", JSON.stringify(out.error));
  assert.equal(out.value.ok, false);
  assert.equal(out.value.rejection, "InvalidOrigin");
  assert.equal(host.calls.filter(([kind]) => kind === "create").length, 0);
  await host.cleanup();
});

test("fission builds one lane per prompt, persists the group and silences the owner", async () => {
  const host = await fakeHost();
  // A worker started by `fork` is the only origin that may fission (§4.2). The
  // fork creates the child; the worker then runs in its own session.
  const forked = await host.call(
    "js-manager",
    { code: 'return await fork("w1", "role: coder. do it");' },
    { agent: "Manager" },
  );
  assert.equal(forked.value.started, true, JSON.stringify(forked.value));
  const childSession = host.calls.find(([kind]) => kind === "create")[1];
  const workerSession = childSession.id;
  assert.equal(childSession.title, "w1");

  const out = await host.call("js-worker", { code: 'return await this.fission(["lane one", "lane two"]);' }, {
    sessionID: workerSession,
  });
  assert.equal(out.state, "completed", JSON.stringify(out.error));
  assert.equal(out.value.ok, true, JSON.stringify(out.value));
  assert.equal(out.value.lanes.length, 2);
  // Two lanes were created, both were started with the lane text, and the owner
  // was interrupted after the whole group started.
  const created = host.calls.filter(([kind, input]) => kind === "create" && input.metadata?.herdchestra === "lane");
  assert.equal(created.length, 2);
  const lanePrompts = host.calls.filter(([kind, input]) => kind === "prompt" && input.text.includes("parallel presents"));
  assert.equal(lanePrompts.length, 2);
  // Spec §4.2 step 3: both lanes name the group's count, not their own index.
  for (const [, input] of lanePrompts) {
    assert.equal(input.text.startsWith("You are one of 2 parallel presents"), true, input.text);
  }
  assert.ok(lanePrompts[0][1].text.includes("lane one"));
  assert.ok(lanePrompts[1][1].text.includes("lane two"));
  assert.ok(host.calls.some(([kind, input]) => kind === "interrupt" && input.sessionID === workerSession));
  // The group is recorded once, in the owner's state file.
  const record = await fs.readFile(path.join(host.dir, ".Herdchestra", `${workerSession}.jsonl`), "utf8");
  assert.ok(record.includes('"type":"fission"'), record);
  await host.cleanup();
});

test("a second fission while a group is live is refused", async () => {
  const host = await fakeHost();
  await host.call("js-manager", { code: 'return await fork("w1", "role: coder. do it");' }, { agent: "Manager" });
  const workerSession = host.calls.find(([kind]) => kind === "create")[1].id;
  const first = await host.call("js-worker", { code: 'return await this.fission(["a", "b"]);' }, { sessionID: workerSession });
  assert.equal(first.value.ok, true, JSON.stringify(first.value));
  const second = await host.call("js-worker", { code: 'return await this.fission(["c", "d"]);' }, { sessionID: workerSession });
  assert.equal(second.value.rejection, "AlreadyActive");
  await host.cleanup();
});

test("a build failure is a failed group, not CapacityExceeded, and leaves no lane", async () => {
  let lanes = 0;
  let created = 0;
  const host = await fakeHost({
    sessions: {
      create: async (input) => {
        if (input.metadata?.herdchestra === "lane") {
          lanes += 1;
          // The third lane cannot be built. Admission already passed: the group
          // was admitted with three prompts and two lanes exist.
          if (lanes === 3) throw new Error("lane create failed");
          created += 1;
          const id = `ses_lane_${created}`;
          host.calls.push(["create", { ...input, id }]);
          return { data: { id, ...input } };
        }
        created += 1;
        const id = `ses_${created}`;
        host.calls.push(["create", { ...input, id }]);
        return { data: { id, ...input } };
      },
    },
  });
  await host.call("js-manager", { code: 'return await fork("w1", "role: coder. do it");' }, { agent: "Manager" });
  const workerSession = host.calls.find(([kind]) => kind === "create")[1].id;
  const out = await host.call("js-worker", { code: 'return await this.fission(["a", "b", "c"]);' }, { sessionID: workerSession });
  // Root §4.2: `CapacityExceeded` is decided at admission, before any lane is
  // built. A build failure after admission is a failed group, surfaced as the
  // program's own failure with the real reason — never rewritten into a refusal.
  assert.equal(out.state, "failed", JSON.stringify(out));
  assert.equal(out.value, null, "a build failure is not a refusal receipt");
  assert.match(out.error.message, /lane create failed/);
  assert.doesNotMatch(out.error.message, /CapacityExceeded/);
  // Every lane that was built was silenced, and no group record was committed.
  const interrupted = host.calls.filter(
    ([kind, input]) => kind === "interrupt" && String(input.sessionID).startsWith("ses_lane"),
  );
  assert.equal(interrupted.length, 2, "the two lanes that were built are silenced");
  assert.deepEqual(host.state(workerSession).fissionGroups, [], "no partial group is recorded");
  // The failed group never reached the old present's replacement.
  assert.equal(host.calls.filter(([kind, input]) => kind === "interrupt" && input.sessionID === workerSession).length, 0);
  // No lane, group or admission survives: the owner may fission again.
  const retried = await host.call("js-worker", { code: 'return await this.fission(["x", "y"]);' }, { sessionID: workerSession });
  assert.equal(retried.state, "completed", JSON.stringify(retried.error));
  assert.equal(retried.value.ok, true, JSON.stringify(retried.value));
  assert.equal(retried.value.lanes.length, 2);
  assert.equal(host.state(workerSession).fissionGroups.length, 1);
  await host.cleanup();
});

test("the context hook narrows Plan's catalog and leaves it without read or bash", async () => {
  const host = await fakeHost();
  const event = {
    sessionID: "ses_plan",
    agent: "Plan",
    tools: { read: {}, bash: {}, glob: {}, plan: {}, question: {}, "js-manager": {}, fork: {} },
    system: [],
    messages: [{ role: "user", content: [{ type: "text", text: "plan it" }] }],
  };
  for (const hook of host.contextHooks) await hook(event);
  // No `question`: Plan asks with `this.ask` in `S1` (park + interrupt), and
  // §2.2 gives it no blocking host question tool.
  assert.deepEqual(Object.keys(event.tools).sort(), ["plan"]);
  await host.cleanup();
});

test("a worker session's language is inherited from its starter", async () => {
  const host = await fakeHost({
    events: [
      { type: "session.created", data: { sessionID: "ses_parent" } },
      { type: "session.created", data: { sessionID: "ses_child", parentID: "ses_parent" } },
    ],
  });
  // The pump consumes the events asynchronously; wait for the file it writes.
  await new Promise((resolve) => setTimeout(resolve, 50));
  const child = await fs.readFile(path.join(host.dir, ".Herdchestra", "ses_child.jsonl"), "utf8");
  const parent = await fs.readFile(path.join(host.dir, ".Herdchestra", "ses_parent.jsonl"), "utf8");
  const childLanguage = JSON.parse(child.trim().split("\n").at(-1)).binding;
  const parentLanguage = JSON.parse(parent.trim().split("\n").at(-1)).binding;
  assert.equal(childLanguage.language, parentLanguage.language);
  assert.equal(childLanguage.source, "fork");
  await host.cleanup();
});

test("a turn is closed at the request boundary, not after one tool call", async () => {
  const host = await fakeHost();
  // One model turn with two parallel calls: the first stages `meToo`, the
  // second carries the true annotation. Closing after the first would refuse a
  // turn that is still in progress. The budget is established by an earlier
  // turn, because a `meToo` cannot arrive before it (§6.2).
  await host.call("js-worker", { code: "return 0;" }, {
    messageID: "turn_open",
    sessionID: "ses_parallel",
    scope: RESET(5),
  });
  const first = await host.call("js-worker", { code: "return 1;" }, {
    messageID: "turn_parallel",
    sessionID: "ses_parallel",
    scope: { kind: "meToo" },
  });
  assert.equal(first.state, "completed", JSON.stringify(first.error));
  const second = await host.call("js-worker", { code: "return 2;" }, {
    messageID: "turn_parallel",
    sessionID: "ses_parallel",
    scope: RESET(5),
  });
  assert.equal(second.state, "completed", JSON.stringify(second.error));
  // The turn closes when the next request of the same session is built.
  const event = {
    sessionID: "ses_parallel",
    agent: "coder",
    tools: { "js-worker": {} },
    system: [],
    messages: [{ role: "user", content: [{ type: "text", text: "next" }] }],
  };
  const result = await host.contextHooks[0](event);
  assert.notEqual(result?.closeTurn?.code ?? result?.notice?.code, "SCOPE_METOO_ONLY", "the true annotation settled before the close");
  await host.cleanup();
});

test("a turn that only staged meToo is refused when the turn closes", async () => {
  const host = await fakeHost();
  // A `meToo` needs an established budget: the first call resets it. The
  // session is its own, so the turn ids cannot collide with another test's.
  const sessionID = "ses_metoo_only";
  await host.call("js-worker", { code: "return 0;" }, { messageID: "turn_reset", sessionID, scope: RESET(5) });
  // The annotation travels as the call's own `scope` field. Passing it through
  // the helper's option object is what the helper reads; a `scope` inside the
  // program input would be shadowed by the default.
  const staged = await host.call("js-worker", { code: "return 1;" }, {
    messageID: "turn_metoo_only",
    sessionID,
    scope: { kind: "meToo" },
  });
  assert.equal(staged.state, "completed", JSON.stringify(staged.error));
  const event = {
    sessionID,
    agent: "coder",
    tools: { "js-worker": {} },
    system: [],
    messages: [{ role: "user", content: [{ type: "text", text: "next" }] }],
  };
  const result = await host.contextHooks[0](event);
  const reported = result?.closeTurn?.code ?? result?.notice?.code;
  assert.equal(reported, "SCOPE_METOO_ONLY", JSON.stringify({ closeTurn: result?.closeTurn, notice: result?.notice }));
  await host.cleanup();
});

test("the close answers a refusal, not null, and a settled turn answers null", async () => {
  const host = await fakeHost();
  const sessionID = "ses_close_shape";
  await host.call("js-worker", { code: "return 0;" }, { messageID: "turn_open", sessionID, scope: RESET(5) });
  const staged = await host.call("js-worker", { code: "return 1;" }, {
    messageID: "turn_staged",
    sessionID,
    scope: { kind: "meToo" },
  });
  assert.equal(staged.state, "completed", JSON.stringify(staged.error));

  // A staged `meToo` writes no record of its own (§6.2: it is provisional
  // until the turn's true annotation settles), so the journal cannot show it.
  // A rebuilt `SessionState` would therefore answer null here for the wrong
  // reason. The close is read through the host's own hook instead: the same
  // object the plugin closes over.
  const event = {
    sessionID,
    agent: "coder",
    tools: { "js-worker": {} },
    system: [],
    messages: [{ role: "user", content: [{ type: "text", text: "next" }] }],
  };
  const refused = (await host.contextHooks[0](event))?.closeTurn;
  assert.equal(refused?.ok, false);
  assert.equal(refused?.code, "SCOPE_METOO_ONLY");
  assert.equal(refused?.details?.turnId, "turn_staged");

  // A turn whose true annotation settled answers null: the other branch.
  await host.call("js-worker", { code: "return 2;" }, { messageID: "turn_true", sessionID, scope: RESET(5) });
  const settled = (await host.contextHooks[0](event))?.closeTurn;
  assert.equal(settled, null, JSON.stringify(settled));
  await host.cleanup();
});

test("a close that races an in-flight settle waits for it instead of refusing", async () => {
  const host = await fakeHost();
  const sessionID = "ses_close_race";
  await host.call("js-worker", { code: "return 0;" }, { messageID: "turn_open", sessionID, scope: RESET(5) });
  // The true annotation of this turn is still settling when the close is asked.
  // Core answers on a settled book, so the close is a promise that resolves
  // after the settle: the turn is booked, not refused, and the close says null.
  const settling = host.call("js-worker", { code: "return 1;" }, {
    messageID: "turn_racing",
    sessionID,
    scope: RESET(5),
  });
  const closing = host.state(sessionID).closeTurn();
  assert.equal(typeof closing.then, "function", "the close is a promise");
  const [called, closed] = await Promise.all([settling, closing]);
  assert.equal(called.state, "completed", JSON.stringify(called.error));
  assert.equal(closed, null, JSON.stringify(closed));
  await host.cleanup();
});

// ---------------------------------------------------------------------------
// Model inheritance on the two worker create paths (§4.1, §4.2)
//
// The host does not copy a parent's model into a session the adapter creates,
// and the agent default is the free model that answers 429 (`model-inherit.mjs`).
// A forked worker and a fission lane therefore carry their parent's model.
// These assert what the created session carries, never the order of calls.

const PARENT_MODEL = Object.freeze({ providerID: "kw92", id: "lazy/task" });

test("a forked worker is created with its parent's model", async () => {
  const host = await fakeHost();
  const out = await host.call("js-manager", { code: 'return await fork("w1", "role: coder. do it");' }, {
    agent: "Manager",
  });
  assert.equal(out.value.started, true, JSON.stringify(out.value));
  const created = host.calls.find(([kind, input]) => kind === "create" && input.title === "w1");
  assert.ok(created, "the fork created its worker");
  assert.deepEqual(created[1].model, PARENT_MODEL);
  // The model was read from the parent, which is the session that forked.
  assert.equal(
    host.calls.some(([kind, input]) => kind === "get" && input.sessionID === "ses_main"),
    true,
    "the parent's model is read from the host",
  );
  await host.cleanup();
});

test("a fork whose parent has no model is refused and creates no worker", async () => {
  const host = await fakeHost({
    sessions: {
      get: async (input) => ({ data: { id: input.sessionID } }),
    },
  });
  const out = await host.call("js-manager", { code: 'return await fork("w1", "role: coder. do it");' }, {
    agent: "Manager",
  });
  assert.equal(out.value.ok, false, JSON.stringify(out.value));
  assert.equal(out.value.reason, "HOST_SESSION_HAS_NO_MODEL");
  assert.equal(host.calls.some(([kind]) => kind === "create"), false);
  await host.cleanup();
});

test("fission lanes are created with the owner's model", async () => {
  const host = await fakeHost();
  const forked = await host.call("js-manager", { code: 'return await fork("w1", "role: coder. do it");' }, {
    agent: "Manager",
  });
  assert.equal(forked.value.started, true, JSON.stringify(forked.value));
  const workerSession = host.calls.find(([kind, input]) => kind === "create" && input.title === "w1")[1].id;

  const out = await host.call("js-worker", { code: 'return await this.fission(["lane one", "lane two"]);' }, {
    sessionID: workerSession,
  });
  assert.equal(out.value.ok, true, JSON.stringify(out.value));
  const lanes = host.calls.filter(([kind, input]) => kind === "create" && input.metadata?.herdchestra === "lane");
  assert.equal(lanes.length, 2);
  // Every lane runs what its owner runs. A lane created without a model would
  // answer 429 under the agent default, which is the failure this rule exists
  // to prevent.
  for (const [, input] of lanes) assert.deepEqual(input.model, PARENT_MODEL);
  await host.cleanup();
});

test("a fission whose owner has no model refuses and builds no lane", async () => {
  // A host that reports every session WITHOUT a model, from the first read: the
  // fork itself is then refused, so this test builds the owner through the
  // worker path that does not consult the model (a lane) and checks the refusal
  // there instead. What matters is the same invariant on every path: no session
  // is created when the parent has no model to inherit.
  const host = await fakeHost({
    sessions: {
      get: async (input) => ({ data: { id: input.sessionID } }),
    },
  });
  const forked = await host.call("js-manager", { code: 'return await fork("w1", "role: coder. do it");' }, {
    agent: "Manager",
  });
  // No parent model, so no worker exists to fission from.
  assert.equal(forked.value.ok, false, JSON.stringify(forked.value));
  assert.equal(forked.value.reason, "HOST_SESSION_HAS_NO_MODEL");
  assert.equal(host.calls.some(([kind]) => kind === "create"), false, "no session was created without a model");
  await host.cleanup();
});

// ---------------------------------------------------------------------------
// The task model (root §1.1 item 6)
//
// Subagents — a forked worker, a fission lane and Plan's dedicated devops — run
// the operator's task model when it is configured. Manager and Plan keep the
// parent model, and so do the relay successor and the Manager a Plan hands to.

const TASK_ENV = Object.freeze({
  PROVIDER: "HERDCHESTRA_TASK_PROVIDER_ID",
  MODEL: "HERDCHESTRA_TASK_MODEL_ID",
});
const TASK_MODEL = Object.freeze({ providerID: "task-provider", id: "task-model" });

const withTaskModel = async (providerID, id, run) => {
  const saved = { provider: process.env[TASK_ENV.PROVIDER], model: process.env[TASK_ENV.MODEL] };
  if (providerID === null) delete process.env[TASK_ENV.PROVIDER];
  else process.env[TASK_ENV.PROVIDER] = providerID;
  if (id === null) delete process.env[TASK_ENV.MODEL];
  else process.env[TASK_ENV.MODEL] = id;
  try {
    return await run();
  } finally {
    if (saved.provider === undefined) delete process.env[TASK_ENV.PROVIDER];
    else process.env[TASK_ENV.PROVIDER] = saved.provider;
    if (saved.model === undefined) delete process.env[TASK_ENV.MODEL];
    else process.env[TASK_ENV.MODEL] = saved.model;
  }
};

test("a forked worker runs the task model and reports its source", async () => {
  await withTaskModel(TASK_MODEL.providerID, TASK_MODEL.id, async () => {
    const host = await fakeHost();
    const out = await host.call("js-manager", { code: 'return await fork("w1", "role: coder. do it");' }, {
      agent: "Manager",
    });
    assert.equal(out.value.started, true, JSON.stringify(out.value));
    assert.equal(out.value.modelSource, "task");
    const created = host.calls.find(([kind, input]) => kind === "create" && input.title === "w1");
    assert.deepEqual(created[1].model, TASK_MODEL);
    await host.cleanup();
  });
});

test("a fission lane runs the task model", async () => {
  await withTaskModel(TASK_MODEL.providerID, TASK_MODEL.id, async () => {
    const host = await fakeHost();
    const forked = await host.call("js-manager", { code: 'return await fork("w1", "role: coder. do it");' }, {
      agent: "Manager",
    });
    const workerSession = host.calls.find(([kind, input]) => kind === "create" && input.title === "w1")[1].id;
    const out = await host.call("js-worker", { code: 'return await this.fission(["one", "two"]);' }, {
      sessionID: workerSession,
    });
    assert.equal(out.value.ok, true, JSON.stringify(out.value));
    const lanes = host.calls.filter(([kind, input]) => kind === "create" && input.metadata?.herdchestra === "lane");
    assert.equal(lanes.length, 2);
    for (const [, input] of lanes) assert.deepEqual(input.model, TASK_MODEL);
    await host.cleanup();
  });
});

test("Plan's dedicated devops runs the task model", async () => {
  await withTaskModel(TASK_MODEL.providerID, TASK_MODEL.id, async () => {
    const host = await fakeHost({
      sessions: {
        context: async () => ({ data: [{ role: "assistant", content: [{ type: "text", text: "report" }] }] }),
      },
    });
    const out = await host.call("plan", { code: 'return await devops("look");' }, { agent: "Plan" });
    assert.equal(out.value.ok, true, JSON.stringify(out.value));
    assert.equal(out.value.modelSource, "task");
    const created = host.calls.find(([kind, input]) => kind === "create" && input.agent === "devops");
    assert.deepEqual(created[1].model, TASK_MODEL);
    await host.cleanup();
  });
});

test("half a task model is a refusal, not a silent parent fallback", async () => {
  await withTaskModel(TASK_MODEL.providerID, null, async () => {
    const host = await fakeHost();
    const out = await host.call("js-manager", { code: 'return await fork("w1", "role: coder. do it");' }, {
      agent: "Manager",
    });
    assert.equal(out.value.ok, false, JSON.stringify(out.value));
    assert.equal(out.value.rejection, "TASK_MODEL_INCOMPLETE");
    assert.equal(host.calls.some(([kind]) => kind === "create"), false, "no session was created");
    await host.cleanup();
  });
});

test("a relay successor and a Plan's Manager keep the parent model even with a task model set", async () => {
  await withTaskModel(TASK_MODEL.providerID, TASK_MODEL.id, async () => {
    const host = await fakeHost();
    host.seesSession("ses_main");
    await host.operatorSays("ship the parser fix");
    const handed = await host.relayHost.handOver({ predecessor: "ses_main", milestone: "execute", tenure: 0 });
    assert.equal(handed.ok, true, JSON.stringify(handed));
    const successor = host.calls.find(
      ([kind, input]) => kind === "create" && input.metadata?.herdchestra === "relay-successor",
    );
    assert.deepEqual(successor[1].model, PARENT_MODEL, "the successor continues the parent's model");

    const manager = await host.relayHost.startManager("the saved plan\n", {
      planSession: "ses_main",
      handoffId: "call_handoff",
    });
    assert.equal(manager.ok, true, JSON.stringify(manager));
    const created = host.calls.find(
      ([kind, input]) => kind === "create" && input.metadata?.herdchestra === "manager",
    );
    assert.deepEqual(created[1].model, PARENT_MODEL, "the Manager runs the Plan's model");
    await host.cleanup();
  });
});

// ---------------------------------------------------------------------------
// The four defects the audit found, each proved by behaviour over the real
// adapter: no fixture reaches into a private field to make the answer true.
// ---------------------------------------------------------------------------

// Walk a Plan session to 待执行 the way 根 §2.4 says it is reached: the S1
// question asks and moves nothing, each later empty review RELAYS, and the
// successor's own first empty review promotes the milestone. The relay is the
// host's real one (record + brief + interrupt), so the ledger this leaves is
// the one a live host leaves.
const walkPlanToReady = async (host, sessionID = "ses_main") => {
  // A relay carries the operator's ORIGINAL input into its successor (根 §2.4 第
  // 4 步), so the operator speaks first. Without that fact every relay is
  // refused `ORIGINAL_INPUT_MISSING` before anything is written.
  const spoken = "先把重试改成指数退避";
  host.seesSession(sessionID);
  const asked = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID });
  // 根 §2.4 第 1 步 with 根 §3.3: the first empty review of the first tenure is
  // not a review; it is the same `ask` the surface performs — park + interrupt —
  // so the receipt is the park, and there is no `value` to read.
  assert.equal(asked.state, "parked", JSON.stringify(asked));
  assert.equal(asked.reason, "ask", JSON.stringify(asked));
  assert.equal(asked.payload.question, "我需要干什么？", JSON.stringify(asked));
  assert.equal(asked.interrupt, true, JSON.stringify(asked));
  // The question is answered by the operator's next prompt, which resumes the
  // program the review was asked for (根 §2.4 第 1 步). The resume carries the
  // program to its return, so its receipt states the final value: `review([])`
  // evaluated to the operator's answer, exactly as `await this.ask(q)` does.
  const answered = await host.operatorSays(spoken, { sessionID, messageID: "msg_first_answer" });
  assert.equal(answered.answered.answered, true, JSON.stringify(answered.answered));
  assert.equal(answered.answered.resumed.resumed, true, JSON.stringify(answered.answered));
  assert.equal(answered.answered.resumed.value, spoken, JSON.stringify(answered.answered));
  // The ledger did not advance: the question moves no milestone and opens no
  // tenure, so the same S1 tenure is still the one judging.
  const ledger = host.state(sessionID).ledgerFor("plan");
  assert.equal(ledger.milestone, "S1", JSON.stringify(ledger));
  assert.equal(ledger.tenure, 0, JSON.stringify(ledger));
  // Each later empty review RELAYS (retire + barrier + successor at the same
  // milestone), and the successor's own first empty review promotes it. The
  // successor is a NEW session, so the walk follows it — and it speaks as the
  // operator first, because the NEXT relay reads its predecessor's original
  // input, which only a genuine operator prompt records.
  let current = sessionID;
  for (let step = 0; step < 3; step += 1) {
    const relayed = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID: current });
    assert.equal(
      relayed.value?.kind,
      "relay",
      JSON.stringify({ value: relayed.value, error: relayed.error }),
    );
    // The successor the relay really opened, from its own receipt: the record
    // names it too (`handedOver.record.successor`), and the host's session
    // state is keyed by the id the relay created.
    current = relayed.value.handedOver.successor;
    assert.equal(typeof current, "string", JSON.stringify(relayed.value));
    host.seesSession(current);
    await host.operatorSays(spoken, { sessionID: current, messageID: `msg_operator_${step}` });
    const promoted = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID: current });
    assert.equal(
      promoted.value?.kind,
      "confirm",
      JSON.stringify({ receipt: promoted, value: promoted.value, error: promoted.error }),
    );
  }
  assert.equal(host.state(current).ledgerFor("plan").milestone, "待执行");
  return current;
};

test("compose reads the saved draft for the hand-off: no host wires it itself", async () => {
  // 根 §2.2: an empty review at 待执行 hands over the SAVED draft, and the review
  // carries no text of its own. The read belongs to compose (`surface.mjs`
  // `#reviewMember`), so every host behaves alike; a host that passed its own
  // bytes would be the only one whose hand-off works.
  const host = await fakeHost();
  // A relay hands the work to a NEW session, but the draft it continues is the
  // file its predecessor wrote: the walk returns the session now judging, and
  // the canonical path is still the first session's.
  const sessionID = await walkPlanToReady(host);
  const draftPath = planDraftPath(host.dir, "ses_main");
  await fs.mkdir(path.dirname(draftPath), { recursive: true });
  await fs.writeFile(draftPath, "the saved plan\n");

  // The call writes no draft text: the bytes can only come from the port.
  const handoff = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID });
  assert.equal(handoff.value.ok, true, JSON.stringify(handoff.value));
  assert.equal(handoff.value.kind, "handoff", JSON.stringify(handoff.value));
  assert.equal(handoff.value.draft, "the saved plan\n");
  // The walk's relays start Manager-role SUCCESSORS (`herdchestra:
  // "relay-successor"`); the hand-off's own Manager is the one metadata names
  // `herdchestra: "manager"` (relay-host.mjs `startManager`).
  const managers = host.calls.filter(
    ([kind, input]) => kind === "create" && input.agent === "Manager" && input.metadata?.herdchestra === "manager",
  );
  assert.equal(managers.length, 1, "one Manager session per hand-off");
  const brief = host.calls.filter(([kind, input]) => kind === "prompt" && input.sessionID === managers[0][1].id);
  assert.equal(brief.length, 1);
  assert.equal(brief[0][1].text, "the saved plan\n", "the Manager's brief is the saved draft");
  await host.cleanup();
});

test("the saved draft decides the hand-off across separate program calls", async () => {
  // The program of one call is not the program of the next: the adapter builds a
  // fresh surface per call (`program-tool.mjs` `makeSurface`). The draft is read
  // at the call that hands off, so a draft written by an EARLIER call is the one
  // a later call hands over — and a draft deleted between the two is refused
  // instead of being replaced by the bytes of the first call.
  const host = await fakeHost();
  const sessionID = await walkPlanToReady(host);
  // The draft survives the relay chain: every successor writes the same file.
  const draftPath = planDraftPath(host.dir, "ses_main");
  await fs.mkdir(path.dirname(draftPath), { recursive: true });
  await fs.writeFile(draftPath, "first version\n");
  const first = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID });
  assert.equal(first.value.draft, "first version\n", JSON.stringify(first.value));
  await fs.writeFile(draftPath, "second version\n");
  const second = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID });
  assert.equal(second.value.draft, "second version\n", JSON.stringify(second.value));
  await fs.rm(draftPath);
  const refused = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID });
  assert.equal(refused.value.ok, false, JSON.stringify(refused.value));
  assert.equal(refused.value.rejection, "DRAFT_MISSING", JSON.stringify(refused.value));
  await host.cleanup();
});

test("a Plan's draft unlocks after the first review, even in a later program call", async () => {
  // 根 §2.2: the draft is read-only until the tenure's first review. The count is
  // a fact of the TENURE, so it must survive the program that wrote it: the
  // adapter builds a fresh surface per call, and a count that lived only in that
  // surface locked the draft forever after the first call.
  const host = await fakeHost();
  const draftPath = planDraftPath(host.dir, "ses_main");
  await fs.mkdir(path.dirname(draftPath), { recursive: true });
  const refused = await host.call("plan", { code: `return await this.write(${JSON.stringify(draftPath)}, "x");` }, {
    agent: "Plan",
  });
  // A refused member stops the program: the receipt is `failed` and the code
  // leads the error, which is the shape the model reads.
  assert.equal(refused.state, "failed", JSON.stringify(refused));
  assert.equal(refused.error.code, "DRAFT_READ_ONLY", JSON.stringify(refused.error));

  // One non-empty review in program 1.
  const reviewed = await host.call("plan", { code: 'return await review([{ key: "open", value: "x" }]);' }, {
    agent: "Plan",
  });
  assert.equal(reviewed.value.kind, "recorded", JSON.stringify(reviewed.value));

  // A DIFFERENT program, built from the session's ledger: the write is allowed.
  const written = await host.call("plan", { code: `return await this.write(${JSON.stringify(draftPath)}, "the plan\\n");` }, {
    agent: "Plan",
  });
  assert.equal(written.state, "completed", JSON.stringify(written.error));
  // The write member answers the file receipt: the path and the bytes written.
  assert.equal(written.value.path, draftPath, JSON.stringify(written.value));
  assert.equal(written.value.bytes, "the plan\n".length);
  assert.equal(await fs.readFile(draftPath, "utf8"), "the plan\n");
  // The count the next surface reads is the tenure's, not a fresh zero.
  assert.equal(host.state("ses_main").ledgerFor("plan").reviewCount, 1);
  await host.cleanup();
});

test("fission is admitted for a fork-started worker session, and its lanes run its role", async () => {
  // 根 §4.2: only a worker that `fork` started may fission, and a lane is a
  // parallel present of that worker — so a devops that fissions starts devops
  // lanes. The worker is reached the way a live host reaches it: the fork
  // creates a CHILD session, and the worker's call runs in that child session
  // with an agent the host reports. No session id is ever reused as a program
  // name.
  const host = await fakeHost();
  const forked = await host.call("js-manager", { code: 'return await fork("w1", "role: devops. audit the parser");' }, {
    agent: "Manager",
  });
  assert.equal(forked.value.started, true, JSON.stringify(forked.value));
  const child = host.calls.find(([kind, input]) => kind === "create" && input.title === "w1")[1];
  assert.equal(child.agent, "devops");

  const out = await host.call("js-worker", { code: 'return await this.fission(["lane one", "lane two"]);' }, {
    agent: "devops",
    sessionID: child.id,
  });
  assert.equal(out.state, "completed", JSON.stringify(out.error));
  assert.equal(out.value.ok, true, JSON.stringify(out.value));
  const lanes = host.calls.filter(([kind, input]) => kind === "create" && input.metadata?.herdchestra === "lane");
  assert.equal(lanes.length, 2);
  for (const [, lane] of lanes) {
    assert.equal(lane.agent, "devops", "a lane keeps its owner's identity");
    assert.equal(lane.model.providerID, "kw92", "a lane inherits the owner's model");
  }
  await host.cleanup();
});

test("Plan's first empty review asks the operator, parks the program and resumes it", async () => {
  // 根 §2.4 第 1 步: the first empty review of the first tenure is not a review;
  // it is the same `ask` the surface performs — park + interrupt — and the
  // operator's next prompt resumes the SAME program with the answer as its
  // value. Recording the question without a program left the answer with
  // nothing to wake.
  const host = await fakeHost();
  const asked = await host.call(
    "plan",
    { code: 'const r = await review([]); return { value: r };' },
    { agent: "Plan", id: "call_plan_first" },
  );
  assert.equal(asked.state, "parked", JSON.stringify(asked));
  assert.equal(asked.reason, "ask");
  assert.equal(asked.payload.question, "我需要干什么？");
  assert.equal(asked.interrupt, true);
  // The question is shown to the operator, and the turn is stopped.
  assert.ok(host.calls.some(([kind, input]) => kind === "synthetic" && input.text === "我需要干什么？"));
  assert.ok(host.calls.some(([kind, input]) => kind === "interrupt" && input.sessionID === "ses_main"));
  // The receipt says which program the answer will resume.
  assert.equal(asked.ask.program, "call_plan_first");
  // `ask` is the park: the receipt itself says the program is suspended, and
  // `ask` names the program the answer will resume. No second field repeats it.
  assert.equal(asked.state, "parked");

  const [promptHook] = host.promptHooks;
  const answered = await promptHook({
    sessionID: "ses_main",
    messageID: "msg_answer",
    prompt: { text: "先把重试改成指数退避" },
    delivery: "steer",
  });
  assert.equal(answered.answered.answered, true, JSON.stringify(answered.answered));
  assert.equal(answered.answered.program, "call_plan_first", "the answer resumes the asking program");
  assert.equal(answered.answered.resumed.resumed, true, JSON.stringify(answered.answered.resumed));
  // 根 §3.3 with 根 §2.4 第 1 步: the question IS an `ask`, so the resume's
  // injected value is the value of the parked expression — `await review([])`
  // evaluates to the operator's answer itself, exactly as `await this.ask(q)`
  // does. It is NOT a receipt: the review never became a review, so there is no
  // `kind` and no `ok` to read, and nothing was booked.
  assert.equal(answered.answered.resumed.value.value, "先把重试改成指数退避");
  // The ledger did not advance and did not relay: the same tenure, still S1,
  // and no second session was started.
  const ledger = host.state("ses_main").ledgerFor("plan");
  assert.equal(ledger.tenure, 0, "the question does not open a tenure");
  assert.equal(ledger.milestone, "S1", "the question moves no milestone");
  assert.equal(
    host.calls.filter(([kind, input]) => kind === "create" && input.agent === "Manager").length,
    0,
    "the question starts no Manager",
  );
  await host.cleanup();
});

test("a Plan surface without a draft port is refused at build time", async () => {
  // `PORTS_FOR.plan` names `draft`, so `requirePorts` refuses the surface before
  // a program can run: the hand-off's only source is missing, and that is a
  // wiring error, not a model-visible `DRAFT_MISSING`.
  assert.deepEqual(PORTS_FOR.plan.includes("draft"), true);
  assert.throws(
    () =>
      makeSurface({
        role: "plan",
        name: "call_no_draft",
        program: null,
        session: { location: { directory: os.tmpdir() } },
        ports: {
          relay: { handOver: async () => null, startManager: async () => null, askOperator: async () => null },
          devops: { investigate: async () => null },
          control: {},
          state: null,
        },
        views: new Map(),
        guard: () => null,
      }),
    (error) => error.code === "MISSING_PORT" && error.details?.port === "draft",
  );
});
