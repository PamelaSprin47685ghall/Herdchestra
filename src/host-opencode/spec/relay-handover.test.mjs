// The hand-over itself (根 §2.4 第 3 步), against a fake SessionDomain.
//
// These cases drive `makeRelayHost` through the crash windows the record exists
// to close. They assert on what the host was asked to do, not only on the
// receipt, because the receipt of a crashed hand-over must not claim a step that
// did not happen.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { makeRelayHost } from "./relay-host.mjs";
import {
  RELAY_GATE,
  RELAY_PHASES,
  applyBarrier,
  handoffFile,
  readRelay,
  relayFile,
  relayRecord,
  writeRelay,
} from "./relay-record.mjs";
import { captureOriginal, originalFile, readOriginal } from "./original-input.mjs";
import { readIdentityOf } from "./identity.mjs";
import { SessionState } from "./session-state.mjs";

const tempRoot = () => fs.mkdtempSync(path.join(os.tmpdir(), "herd-handover-"));

// The incumbent's transcript, as the host reports it (`Session.context`,
// `GET /api/session/:id/context`). The relay reads it before writing its
// record, and its length becomes `fenceThrough`. The operator's own words are
// NOT taken from here (根 §2.4 第 3 步): they come from the durable fact the
// first operator receipt wrote (`original-input.mjs`), because a transcript
// after a compaction no longer holds them. `seedOperator` writes that fact.
const TRANSCRIPT = Object.freeze([
  { type: "user", text: "ship the parser fix" },
  { type: "assistant", text: "working on it" },
]);

const OPERATOR_INPUT = "ship the parser fix";

// The durable original of a predecessor, written the way the prompt hook writes
// it. Returns the file it wrote, so a test can prove the relay read it.
const seedOperator = (root, sessionID, text = OPERATOR_INPUT) => {
  const captured = captureOriginal(root, sessionID, text);
  assert.equal(captured.ok, true, JSON.stringify(captured));
  return captured.record;
};


// The relay host over a fixture, with the session-state lookup a successor's
// first ledger row needs. Without `stateOf` the hand-over can create and prompt
// the successor but cannot record its tenure, so every fixture would observe
// `SUCCESSOR_LEDGER_NOT_DURABLE` instead of the behaviour under test.
const relayHost = ({ sessions, root }) =>
  makeRelayHost({
    sessions,
    root,
    stateOf: (sessionID) => new SessionState({ sessionID, root }),
  });

// A SessionDomain that records every call. `fail` names the method that throws.
const fakeSessions = ({ fail = null, ids = [] } = {}) => {
  const calls = [];
  let created = 0;
  return {
    calls,
    async create(input) {
      calls.push({ method: "create", input });
      if (fail === "create") throw new Error("create failed");
      created += 1;
      const id = input?.id ?? ids[created - 1] ?? `ses_${created}`;
      return { id };
    },
    async prompt(input) {
      calls.push({ method: "prompt", input });
      if (fail === "prompt") throw new Error("prompt failed");
      return { id: input.id ?? `msg_${calls.length}` };
    },
    async interrupt(input) {
      calls.push({ method: "interrupt", input });
      return undefined;
    },
    async context(input) {
      calls.push({ method: "context", input });
      return { data: TRANSCRIPT };
    },
    // The model of a session, as the host reports it. Every create the adapter
    // makes reads this first: the host does not inherit a parent's model
    // (`model-inherit.mjs`), and the agent default is the free model that
    // answers 429.
    async get(input) {
      calls.push({ method: "get", input });
      return { data: { id: input.sessionID, model: { providerID: "kw92", id: "lazy/task" } } };
    },
    async remove(input) {
      calls.push({ method: "remove", input });
      return undefined;
    },
  };
};

test("a hand-over creates an inert successor, records it, then delivers one brief", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  const host = relayHost({ sessions, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });

  assert.equal(done.ok, true);
  assert.equal(done.retired, true);
  assert.equal(done.barrier, true);
  assert.equal(typeof done.successor, "string");
  // Every fact the record needs is read BEFORE any session is created: the
  // predecessor's model (`model-inherit.mjs`) and its transcript. A refusal
  // must not leave a session behind (根 §2.4 第 3 步： no record, no retirement — and no
  // half-tenure).
  //
  // The assertion is about the ORDER OF FACTS, not about call indices: the
  // reads may be reordered among themselves, but none of them may follow the
  // create. Pinning indices here would fail on a harmless reorder and pass on a
  // create that ran too early.
  const firstCreate = sessions.calls.findIndex((call) => call.method === "create");
  assert.ok(firstCreate >= 0, "the hand-over creates its successor");
  const beforeCreate = sessions.calls.slice(0, firstCreate);
  const read = (method, sessionID) =>
    beforeCreate.some((call) => call.method === method && call.input?.sessionID === sessionID);
  assert.equal(read("get", "ses_p"), true, "the predecessor's model is read before the successor exists");
  assert.equal(read("context", "ses_p"), true, "the predecessor's transcript is read before the successor exists");
  assert.equal(
    sessions.calls.some((call) => call.method === "create" && call.input?.metadata?.herdchestra !== "relay-successor"),
    false,
    "the hand-over creates nothing but its successor",
  );
  const created = firstCreate;
  // No `parentID`: a parent would hand the successor the conversation the
  // barrier exists to cut.
  assert.equal(sessions.calls[created].input.parentID, undefined);
  // The prompt comes after the create and carries the deterministic brief id.
  const prompted = sessions.calls.filter((call) => call.method === "prompt");
  assert.equal(prompted.length, 1);
  assert.equal(prompted[0].input.sessionID, done.successor);
  assert.match(prompted[0].input.id, /^msg_[0-9A-Za-z]{26}$/);
  // A relay carries the operator's original request, never a draft: the draft
  // is Plan's product and belongs to the Plan hand-off (§2.2).
  assert.ok(prompted[0].input.text.includes("ship the parser fix"), "the operator's request travels in the brief");
  assert.equal(prompted[0].input.text.includes("# 底稿"), false);
  // The record is on disk and says `sent`.
  const record = readRelay(relayFile(root, "ses_p"));
  assert.equal(record.phase, RELAY_PHASES.SENT);
  assert.equal(record.successor, done.successor);
  assert.equal(record.incumbent, "ses_p");
  assert.equal("draft" in record, false, "a relay record carries no draft");
  // The operator's original request is a committed fact, not a re-read.
  assert.equal(record.operatorInput, "ship the parser fix");
  assert.equal(record.fenceThrough, TRANSCRIPT.length);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a hand-over whose successor row cannot be written is not reported as complete", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  // The relay reads the operator's durable original before anything else, so a
  // test about the ledger row must still provide it.
  seedOperator(root, "ses_p");
  const host = makeRelayHost({
    sessions,
    root,
    // The successor's state refuses to record its starting row. Without that row
    // the successor's cold start drops to tenure 0, so the hand-over is not
    // complete: `ok` is false, the retirement the gates already enforce is still
    // reported, and `successorRow` names the missing half.
    stateOf: () => ({
      recordRelayStart() {
        throw new Error("journal is read-only");
      },
    }),
  });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, false, JSON.stringify(done));
  assert.equal(done.reason, "SUCCESSOR_LEDGER_NOT_DURABLE");
  assert.equal(done.retired, true);
  assert.equal(done.barrier, true);
  assert.equal(typeof done.successor, "string");
  assert.equal(done.successorRow?.ok, false);
  assert.match(String(done.successorRow?.detail), /journal is read-only/);
  fs.rmSync(root, { recursive: true, force: true });
});

test("the record preserves the whole roster, unclaimed results and the bound devops", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  const host = makeRelayHost({
    sessions,
    root,
    stateOf: (sessionID) => new SessionState({ sessionID, root }),
    workersOf: () => ({
      roster: [
        { name: "w1", role: "coder", sessionID: "ses_w1", state: "running" },
        // An idle worker is still where a name points (根 §2.4: process ownership).
        { name: "w2", role: "devops", sessionID: "ses_w2", state: "idle" },
      ],
      // A finished result nobody joined yet must survive the relay.
      unclaimed: [{ name: "w3", role: "coder", sessionID: "ses_w3", outcome: { text: "42" } }],
      // The road-bound devops does not change hands, busy or not.
      devops: ["w2"],
    }),
  });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, true, JSON.stringify(done));

  const record = readRelay(relayFile(root, "ses_p"));
  assert.deepEqual(
    record.roster.map((worker) => [worker.name, worker.state]),
    [["w1", "running"], ["w2", "idle"]],
    "every worker is preserved, live or idle",
  );
  assert.deepEqual(record.unclaimed.map((worker) => worker.name), ["w3"]);
  assert.deepEqual(record.boundDevops, ["w2"]);
  fs.rmSync(root, { recursive: true, force: true });
});

test("a roster entry with no session id refuses the relay before anything is created", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  const host = makeRelayHost({
    sessions,
    root,
    // A name with no host session cannot be preserved, so the relay must not
    // claim it did: 根 §2.4 requires keeping the roster, and a committed record
    // that dropped a worker would be a false success.
    workersOf: () => ({ roster: [{ name: "w1", role: "coder", sessionID: null }], unclaimed: [], devops: [] }),
  });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, false);
  assert.equal(done.reason, "RELAY_ROSTER_UNPRESERVABLE");
  assert.equal(sessions.calls.filter((call) => call.method === "create").length, 0);
  assert.equal(readRelay(relayFile(root, "ses_p")), null, "no record, no retirement");
  fs.rmSync(root, { recursive: true, force: true });
});

test("a host that cannot show the transcript refuses without creating a session", async () => {
  for (const [label, sessions] of [
    ["no context method", { ...fakeSessions(), context: undefined }],
    ["context throws", { ...fakeSessions(), context: async () => { throw new Error("no transcript"); } }],
  ]) {
    const root = tempRoot();
    // The original fact exists here, so the refusal under test is the
    // transcript one and not a missing original: the two are separate reasons.
    seedOperator(root, "ses_p");
    const host = relayHost({ sessions, root });
    const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
    assert.equal(done.ok, false, label);
    assert.equal(done.retired, false, label);
    assert.equal(done.reason, "RELAY_TRANSCRIPT_UNAVAILABLE", label);
    // The whole point: nothing was created, so nothing can be orphaned.
    assert.equal((sessions.calls ?? []).filter((call) => call.method === "create").length, 0, label);
    assert.equal(readRelay(relayFile(root, "ses_p")), null, `${label}: no record, no retirement`);
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("a transcript with no operator message is not a reason to refuse once the original is stored", async () => {
  // The original is its own fact now, so "the transcript holds no user turn" no
  // longer means the operator's words are lost: after a compaction the summary
  // turn is all the transcript has, and the relay must still work.
  const root = tempRoot();
  seedOperator(root, "ses_p");
  const sessions = { ...fakeSessions(), context: async () => ({ data: [{ type: "assistant", text: "x" }] }) };
  const host = relayHost({ sessions, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, true, JSON.stringify(done));
  assert.equal(readRelay(relayFile(root, "ses_p")).operatorInput, "ship the parser fix");
  fs.rmSync(root, { recursive: true, force: true });
});

test("a failed prompt is a refusal that still names the retired incumbent", async () => {
  const root = tempRoot();
  const sessions = fakeSessions({ fail: "prompt" });
  seedOperator(root, "ses_p");
  const host = relayHost({ sessions, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });

  assert.equal(done.ok, false);
  assert.equal(done.reason, "RELAY_PROMPT_FAILED");
  assert.equal(done.retired, true, "the record landed, so the incumbent is retired");
  assert.equal(done.barrier, true);
  // The record says `sending`: the next process re-delivers the same id.
  const record = readRelay(relayFile(root, "ses_p"));
  assert.equal(record.phase, RELAY_PHASES.SENDING);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a crash between create and record leaves no retirement and one extra session", async () => {
  const root = tempRoot();
  const sessions = fakeSessions({ fail: "create" });
  seedOperator(root, "ses_p");
  const host = relayHost({ sessions, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });

  assert.equal(done.ok, false);
  assert.equal(done.reason, "RELAY_SUCCESSOR_FAILED");
  assert.equal(done.retired, false);
  assert.equal(done.barrier, false);
  assert.equal(readRelay(relayFile(root, "ses_p")), null, "no record, no retirement");
  assert.equal(sessions.calls.filter((call) => call.method === "prompt").length, 0);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a second hand-over for the same incumbent re-delivers instead of starting over", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  const host = relayHost({ sessions, root });

  const first = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  const createsAfterFirst = sessions.calls.filter((call) => call.method === "create").length;
  const second = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });

  assert.equal(first.ok, true);
  assert.equal(second.ok, true);
  assert.equal(second.recovered, true);
  assert.equal(second.successor, first.successor);
  // `done` is not re-sent and no second session is created.
  assert.equal(sessions.calls.filter((call) => call.method === "create").length, createsAfterFirst);
  assert.equal(sessions.calls.filter((call) => call.method === "prompt").length, 1);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a record left in `sending` is recovered by re-delivering the same brief id", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  const host = relayHost({ sessions, root });

  // What a crash after `create` + `record` + a half-delivered prompt leaves.
  const record = relayRecord({
    sessionID: "ses_p",
    incumbent: "ses_p",
    successor: "ses_s",
    tenure: 1,
    milestone: "execute",
    phase: RELAY_PHASES.SENDING,
    // The gates the adapter really writes. Without them the record describes a
    // retirement no channel can enforce, and `recoverRelay` must refuse it —
    // which is a different test (see relay-record.test.mjs "unenforceable").
    requestGate: RELAY_GATE.HOST,
    toolGate: RELAY_GATE.HOST,
  });
  const written = writeRelay(relayFile(root, "ses_p"), record);
  assert.equal(written.ok, true);

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });

  assert.equal(done.ok, true);
  assert.equal(done.recovered, true);
  assert.equal(done.successor, "ses_s");
  // No new session: recovery adopts the successor the record already names.
  assert.equal(sessions.calls.filter((call) => call.method === "create").length, 0);
  const prompted = sessions.calls.filter((call) => call.method === "prompt");
  assert.equal(prompted.length, 1);
  assert.equal(prompted[0].input.id, record.briefId);
  assert.equal(readRelay(relayFile(root, "ses_p")).phase, RELAY_PHASES.SENT);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a corrupt record refuses the hand-over and nobody is retired", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  const host = relayHost({ sessions, root });
  fs.mkdirSync(path.join(root, ".Herdchestra"), { recursive: true });
  fs.writeFileSync(relayFile(root, "ses_p"), "{ not json");

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });

  assert.equal(done.ok, false);
  assert.equal(done.reason, "RELAY_CORRUPT");
  assert.equal(sessions.calls.length, 0, "no host call happens under a corrupt record");

  fs.rmSync(root, { recursive: true, force: true });
});

test("the record keeps the stored original, not the earliest message left after a compaction", async () => {
  // This is the case the durable fact exists for. The host compacted: its
  // transcript no longer starts with the operator's request but with a summary
  // turn. A relay that read "the first user text" would hand the successor the
  // summary as if it were the original.
  const root = tempRoot();
  seedOperator(root, "ses_p", "ship the parser fix");
  const compacted = {
    ...fakeSessions(),
    context: async () => ({
      data: [
        { type: "user", text: "Summary: the operator asked to ship the parser fix." },
        { type: "assistant", text: "continuing" },
      ],
    }),
  };
  const host = relayHost({ sessions: compacted, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, true, JSON.stringify(done));

  const record = readRelay(relayFile(root, "ses_p"));
  assert.equal(record.operatorInput, "ship the parser fix", "the stored fact wins");
  assert.equal(record.operatorInput.includes("Summary"), false);
  // The transcript still supplies the fence length; it is the only thing it is
  // asked for.
  assert.equal(record.fenceThrough, 2);
  // And the successor's brief carries the original, not the summary.
  const prompted = compacted.calls.filter((call) => call.method === "prompt");
  assert.equal(prompted[0].input.text.includes("ship the parser fix"), true);
  assert.equal(prompted[0].input.text.includes("Summary:"), false);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a session with no stored original refuses and creates nothing", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  const host = relayHost({ sessions, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });

  assert.equal(done.ok, false);
  assert.equal(done.reason, "ORIGINAL_INPUT_MISSING");
  assert.equal(done.retired, false);
  // No fallback to the transcript: nothing was read from it and nothing was
  // created, so there is no half-tenure.
  assert.equal(sessions.calls.filter((call) => call.method === "create").length, 0);
  assert.equal(readRelay(relayFile(root, "ses_p")), null);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a stored original that cannot be read refuses instead of being replaced", async () => {
  const root = tempRoot();
  seedOperator(root, "ses_p");
  const sessions = fakeSessions();
  const host = relayHost({ sessions, root });
  fs.writeFileSync(originalFile(root, "ses_p"), "{ not json");

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, false);
  assert.equal(done.reason, "ORIGINAL_INPUT_UNREADABLE");
  // The only copy of the operator's words is left as it was: a rewrite would
  // destroy it.
  assert.equal(fs.readFileSync(originalFile(root, "ses_p"), "utf8"), "{ not json");
  assert.equal(sessions.calls.filter((call) => call.method === "create").length, 0);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a repeat after a failed successor row writes the row and completes the hand-over", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  // The first delivery cannot write the successor's row; the second can. This is
  // the documented recovery: the record is already on disk in phase `sent`, so a
  // repeat delivers nothing twice and only finishes the missing half.
  let readable = false;
  const host = makeRelayHost({
    sessions,
    root,
    stateOf: () => ({
      recordRelayStart() {
        if (!readable) throw new Error("journal is read-only");
        return true;
      },
    }),
  });

  const first = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(first.ok, false, JSON.stringify(first));
  assert.equal(first.reason, "SUCCESSOR_LEDGER_NOT_DURABLE");
  assert.equal(first.retired, true);
  const promptsAfterFirst = sessions.calls.filter((call) => call.method === "prompt").length;
  const createsAfterFirst = sessions.calls.filter((call) => call.method === "create").length;

  readable = true;
  const second = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(second.ok, true, JSON.stringify(second));
  assert.equal(second.recovered, true);
  assert.equal(second.successor, first.successor);
  assert.deepEqual(second.ledger, { ok: true });
  // The brief is not re-delivered and no second successor is created.
  assert.equal(sessions.calls.filter((call) => call.method === "prompt").length, promptsAfterFirst);
  assert.equal(sessions.calls.filter((call) => call.method === "create").length, createsAfterFirst);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a Manager a Plan starts is registered before its draft, so the draft is not the operator's original", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  const host = relayHost({ sessions, root });

  // The Plan this hand-off comes from: the Manager runs its model
  // (`model-inherit.mjs`), so every hand-off names one.
  const started = await host.startManager("# draft\n\nthe plan", {
    operatorInput: "ship the parser fix",
    planSession: "ses_plan",
  });
  assert.equal(started.ok, true, JSON.stringify(started));
  const session = started.session;

  // The registration exists and says the Manager is top-level; it was written
  // before the prompt, which is the whole point: the prompt hook reads it.
  const identity = readIdentityOf(root, session);
  assert.equal(identity?.parentID, null, "a Plan's Manager is a top-level session");
  assert.equal(identity?.origin, "plan-handoff");
  // The host's plain draft prompt is NOT the operator's words: the adapter's own
  // registration and the record are what stop it from being captured.
  assert.equal(readOriginal(root, session).ok, false);

  // 根 §2.4 第 4 步: the Manager sees the operator's original request. The record
  // carries it and the barrier puts it into the Manager's first request.
  const record = readRelay(relayFile(root, session));
  assert.equal(record?.kind, "plan-handoff");
  assert.equal(record?.operatorInput, "ship the parser fix");
  const barrier = applyBarrier([{ role: "user", content: [{ type: "text", text: "# draft" }] }], record);
  const opening = barrier[0].content.map((part) => part.text).join("");
  assert.match(opening, /# 操作员的原始请求/);
  assert.match(opening, /ship the parser fix/);
  // Applying it twice does not duplicate the operator's message.
  assert.equal(applyBarrier(barrier, record).length, barrier.length);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a Plan hand-off retires nobody: the Manager is neither gated nor made a successor", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  const host = relayHost({ sessions, root });
  const started = await host.startManager("# draft", {
    operatorInput: "the operator's words",
    planSession: "ses_plan",
  });
  const record = readRelay(relayFile(root, started.session));
  // `incumbent` must not name the Manager, or it would refuse its own requests.
  assert.notEqual(record.incumbent, started.session);
  // A hand-off record is `done` for recovery: there is no brief to re-deliver.
  const { recoverRelay } = await import("./relay-record.mjs");
  assert.equal(recoverRelay(relayFile(root, started.session)).kind, "done");
  fs.rmSync(root, { recursive: true, force: true });
});

test("a hand-over registers the successor before its brief", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  const host = relayHost({ sessions, root });
  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, true, JSON.stringify(done));
  const identity = readIdentityOf(root, done.successor);
  assert.equal(identity?.parentID, null);
  assert.equal(identity?.origin, "relay-successor");
  // The brief is the adapter's delivery, not the operator's: nothing was captured
  // for the successor as an original input.
  assert.equal(readOriginal(root, done.successor).ok, false);
  fs.rmSync(root, { recursive: true, force: true });
});

test("every session the adapter creates runs its parent's model", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  const host = relayHost({ sessions, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, true, JSON.stringify(done));
  const successorCreate = sessions.calls.find(
    (call) => call.method === "create" && call.input.metadata?.herdchestra === "relay-successor",
  );
  // The successor runs what the predecessor ran. A create without a model falls
  // to the agent default, which on the live host answers 429.
  assert.deepEqual(successorCreate.input.model, { providerID: "kw92", id: "lazy/task" });

  const started = await host.startManager("# draft", { operatorInput: "words", planSession: "ses_plan" });
  assert.equal(started.ok, true, JSON.stringify(started));
  const managerCreate = sessions.calls.find(
    (call) => call.method === "create" && call.input.metadata?.herdchestra === "manager",
  );
  assert.deepEqual(managerCreate.input.model, { providerID: "kw92", id: "lazy/task" });

  fs.rmSync(root, { recursive: true, force: true });
});

test("a parent with no model is a refusal, not a session on the free default", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  sessions.get = async (input) => ({ data: { id: input.sessionID } });
  const host = relayHost({ sessions, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, false);
  assert.equal(done.reason, "HOST_SESSION_HAS_NO_MODEL");
  // Nothing was created: a refusal before `create` leaves no half-tenure.
  assert.equal(sessions.calls.some((call) => call.method === "create"), false);
  assert.equal(done.retired, false);

  fs.rmSync(root, { recursive: true, force: true });
});

// ---------------------------------------------------------------------------
// Model inheritance (§4.1, 根 §2.4 第 3 步, §6, §7.1)
//
// The host does not copy a parent's model into a session the adapter creates,
// and the agent default is the free model that answers 429. So every create
// path reads the parent's model from the host and passes it explicitly; a
// parent without one is a refusal, never a silent default.
//
// These cases assert BEHAVIOUR: which model the created session carries, and
// that a refusal leaves nothing behind. None of them pins call order — a
// reordered read that still happens before the create is not a defect, and an
// index assertion would both fail on it and pass on a create that ran early.

const PARENT_MODEL = Object.freeze({ providerID: "kw92", id: "lazy/task" });

// The create a hand-over made, by the metadata it is required to carry.
const successorCreateOf = (sessions) =>
  sessions.calls.find(
    (call) => call.method === "create" && call.input?.metadata?.herdchestra === "relay-successor",
  );

test("a relay successor runs the predecessor's model, variant included", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  // A model with a variant: the adapter must pass the host's value through
  // unchanged, not rebuild it from the two required fields.
  const variant = { providerID: "kw92", id: "lazy/task", variant: "high" };
  sessions.get = async (input) => ({ data: { id: input.sessionID, model: variant } });
  const host = relayHost({ sessions, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, true, JSON.stringify(done));
  assert.deepEqual(successorCreateOf(sessions).input.model, variant);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a predecessor without a model refuses the relay and creates nothing", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  // The host answers, but the session has no model of its own.
  sessions.get = async (input) => ({ data: { id: input.sessionID } });
  const host = relayHost({ sessions, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, false);
  assert.equal(done.reason, "HOST_SESSION_HAS_NO_MODEL");
  // A refusal before `create` leaves no half-tenure: no session, no retirement.
  assert.equal(sessions.calls.some((call) => call.method === "create"), false);
  assert.equal(done.retired, false);
  assert.equal(readRelay(relayFile(root, "ses_p")), null);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a host that cannot report a model refuses by name instead of guessing", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  delete sessions.get;
  const host = relayHost({ sessions, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, false);
  assert.equal(done.reason, "HOST_HAS_NO_SESSION_GET");
  assert.equal(sessions.calls.some((call) => call.method === "create"), false);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a host whose session read fails refuses the relay and creates nothing", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  sessions.get = async () => {
    throw new Error("session table unavailable");
  };
  const host = relayHost({ sessions, root });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, false);
  assert.equal(done.reason, "HOST_SESSION_UNREADABLE");
  assert.match(done.detail, /session table unavailable/);
  assert.equal(sessions.calls.some((call) => call.method === "create"), false);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a Plan's Manager runs the Plan's model, not the agent default", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  const host = relayHost({ sessions, root });

  const started = await host.startManager("# draft", { planSession: "ses_plan" });
  assert.equal(started.ok, true, JSON.stringify(started));
  const manager = sessions.calls.find(
    (call) => call.method === "create" && call.input?.metadata?.herdchestra === "manager",
  );
  assert.deepEqual(manager.input.model, PARENT_MODEL);
  // The model came from the Plan, not from the Manager it created.
  assert.equal(
    sessions.calls.some((call) => call.method === "get" && call.input.sessionID === "ses_plan"),
    true,
  );

  fs.rmSync(root, { recursive: true, force: true });
});

test("a hand-off with no Plan session refuses instead of falling to the free default", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  const host = relayHost({ sessions, root });

  // No `planSession`: there is no parent whose model could be inherited.
  const started = await host.startManager("# draft");
  assert.equal(started.ok, false);
  assert.equal(started.reason, "NO_PARENT_SESSION");
  assert.equal(sessions.calls.some((call) => call.method === "create"), false);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a Plan without a model refuses the hand-off and leaves no Manager behind", async () => {
  const root = tempRoot();
  const sessions = fakeSessions();
  sessions.get = async (input) => ({ data: { id: input.sessionID } });
  const host = relayHost({ sessions, root });

  const started = await host.startManager("# draft", { planSession: "ses_plan" });
  assert.equal(started.ok, false);
  assert.equal(started.reason, "HOST_SESSION_HAS_NO_MODEL");
  assert.equal(sessions.calls.some((call) => call.method === "create"), false);

  fs.rmSync(root, { recursive: true, force: true });
});

test("a Manager whose draft cannot be delivered is removed again, and says so", async () => {
  const root = tempRoot();
  // `prompt` throws: the session exists but never took the draft.
  const sessions = fakeSessions({ fail: "prompt" });
  const host = relayHost({ sessions, root });

  const started = await host.startManager("# draft", { planSession: "ses_plan" });
  assert.equal(started.ok, false);
  assert.equal(started.reason, "MANAGER_PROMPT_FAILED");
  // The half-open Manager is taken back, not left for a later reader to adopt.
  assert.equal(sessions.calls.some((call) => call.method === "remove"), true);
  assert.equal(started.session, null);
  assert.equal(typeof started.discarded, "string");

  fs.rmSync(root, { recursive: true, force: true });
});

test("only the same call id is deduplicated: a new empty review starts its own Manager", async () => {
  // §2.2 vs §2.4 第 6 步. A NEW empty review always starts an independent
  // Manager — two separate reviews handing over byte-identical drafts still get
  // two Managers. Only the replay of the SAME call id reports what it already
  // started. Deduplicating on the draft's bytes would silently break §2.2, and
  // a crash between "the session exists" and "the Plan learned about it" is
  // exactly when that difference matters.
  const root = tempRoot();
  const sessions = fakeSessions();
  const host = makeRelayHost({
    sessions,
    root,
    stateOf: (sessionID) => new SessionState({ sessionID, root }),
  });

  const first = await host.startManager("# draft", {
    operatorInput: "words",
    planSession: "ses_plan",
    handoffId: "call_1",
  });
  assert.equal(first.ok, true, JSON.stringify(first));
  const createsAfterFirst = sessions.calls.filter((call) => call.method === "create").length;
  const promptsAfterFirst = sessions.calls.filter((call) => call.method === "prompt").length;
  // The dedup fact is ONE deterministic path derived from the call id, not a
  // directory scan (root §1.1 item 1).
  const dedup = readRelay(handoffFile(root, "call_1"));
  assert.equal(dedup?.kind, "plan-handoff");
  assert.equal(dedup?.handoffId, "call_1");
  assert.equal(dedup?.successor, first.session);
  assert.equal(dedup?.phase, RELAY_PHASES.SENT);

  // The same call id again: the Manager it already started, no second session,
  // no second prompt.
  const replay = await host.startManager("# draft", {
    operatorInput: "words",
    planSession: "ses_plan",
    handoffId: "call_1",
  });
  assert.equal(replay.ok, true, JSON.stringify(replay));
  assert.equal(replay.recovered, true);
  assert.equal(replay.session, first.session, "the same Manager, not a second one");
  assert.equal(sessions.calls.filter((call) => call.method === "create").length, createsAfterFirst);
  assert.equal(sessions.calls.filter((call) => call.method === "prompt").length, promptsAfterFirst);

  // A different call id with the SAME draft text is a new hand-off (§2.2).
  const next = await host.startManager("# draft", {
    operatorInput: "words",
    planSession: "ses_plan",
    handoffId: "call_2",
  });
  assert.equal(next.ok, true, JSON.stringify(next));
  assert.notEqual(next.session, first.session, "a new empty review starts its own Manager");
  assert.equal(sessions.calls.filter((call) => call.method === "create").length, createsAfterFirst + 1);

  // A caller that has no call id never deduplicates: it cannot tell a replay
  // from a new review, and inventing a match would be the guess this rule
  // forbids.
  const anonymous = await host.startManager("# draft", { operatorInput: "words", planSession: "ses_plan" });
  assert.equal(anonymous.ok, true, JSON.stringify(anonymous));
  assert.notEqual(anonymous.session, first.session);
  assert.notEqual(anonymous.session, next.session);
  fs.rmSync(root, { recursive: true, force: true });
});

test("a crash between the dedup copy and the Manager copy is repaired, not duplicated", async () => {
  // The dedup copy is written first so a replay can always find the Manager.
  // A crash before the Manager's own copy lands leaves that copy missing; the
  // replay must restore it (its RelayFacts reads it for the barrier) and must
  // not create a second Manager or send a second prompt.
  const root = tempRoot();
  const sessions = fakeSessions();
  const host = makeRelayHost({
    sessions,
    root,
    stateOf: (sessionID) => new SessionState({ sessionID, root }),
  });
  const first = await host.startManager("# draft", {
    operatorInput: "words",
    planSession: "ses_plan",
    handoffId: "call_partial",
  });
  assert.equal(first.ok, true, JSON.stringify(first));
  const managerFile = relayFile(root, first.session);
  fs.rmSync(managerFile, { force: true });
  const creates = sessions.calls.filter((call) => call.method === "create").length;
  const prompts = sessions.calls.filter((call) => call.method === "prompt").length;

  const repaired = await host.startManager("# draft", {
    operatorInput: "words",
    planSession: "ses_plan",
    handoffId: "call_partial",
  });
  assert.equal(repaired.ok, true, JSON.stringify(repaired));
  assert.equal(repaired.recovered, true);
  assert.equal(repaired.session, first.session);
  assert.equal(sessions.calls.filter((call) => call.method === "create").length, creates);
  assert.equal(sessions.calls.filter((call) => call.method === "prompt").length, prompts);
  assert.equal(readRelay(managerFile)?.successor, first.session, "the Manager's own copy is restored");
  fs.rmSync(root, { recursive: true, force: true });
});

test("a hand-off record at another path does not deduplicate: the lookup is one path", async () => {
  // The removed scan used to find any record naming the call id. The fact is now
  // the deterministic path; a record elsewhere is not authority. This is the
  // migration boundary: an old-format record is not read as this call's.
  const root = tempRoot();
  const sessions = fakeSessions();
  const host = makeRelayHost({
    sessions,
    root,
    stateOf: (sessionID) => new SessionState({ sessionID, root }),
  });
  const record = {
    v: 1,
    kind: "plan-handoff",
    successor: "ses_old",
    incumbent: "",
    sessionID: "ses_old",
    milestone: "execute",
    tenure: 0,
    phase: RELAY_PHASES.SENT,
    briefId: "msg_old",
    notice: "",
    operatorInput: "words",
    draft: "# draft",
    handoffId: "call_old",
    at: Date.now(),
  };
  fs.mkdirSync(path.join(root, ".Herdchestra"), { recursive: true });
  assert.equal(writeRelay(relayFile(root, "ses_old"), record).ok, true);
  const started = await host.startManager("# draft", {
    operatorInput: "words",
    planSession: "ses_plan",
    handoffId: "call_old",
  });
  assert.equal(started.ok, true, JSON.stringify(started));
  assert.notEqual(started.session, "ses_old", "the record elsewhere is not this call's fact");
  assert.equal(sessions.calls.filter((call) => call.method === "create").length, 1);
  fs.rmSync(root, { recursive: true, force: true });
});

test("a hand-off whose Manager exists but was never prompted is repaired by the same call id", async () => {
  // The window this record exists for: the session was created, the record
  // landed, and the process died before the draft was delivered. The Plan never
  // learned about it, so the same physical call arrives again. The record makes
  // that a repair — one Manager, one prompt — instead of a second Manager
  // (§2.2: one hand-off, one Manager; §2.4 第 6 步: the same call id replays).
  const root = tempRoot();
  const sessions = fakeSessions();
  const host = makeRelayHost({
    sessions,
    root,
    stateOf: (sessionID) => new SessionState({ sessionID, root }),
  });
  const draft = "# draft\n\nthe plan";
  const first = await host.startManager(draft, {
    operatorInput: "words",
    planSession: "ses_plan",
    handoffId: "call_crash",
  });
  assert.equal(first.ok, true, JSON.stringify(first));
  const manager = first.session;

  // What the crash leaves: the record and the session, no prompt.
  const record = readRelay(relayFile(root, manager));
  assert.equal(record.kind, "plan-handoff");
  assert.equal(record.handoffId, "call_crash");
  assert.equal(record.draft, draft);
  const promptsAfterFirst = sessions.calls.filter((call) => call.method === "prompt").length;

  const repair = await host.startManager(draft, {
    operatorInput: "words",
    planSession: "ses_plan",
    handoffId: "call_crash",
  });
  assert.equal(repair.ok, true, JSON.stringify(repair));
  assert.equal(repair.recovered, true);
  assert.equal(repair.session, manager, "the Manager the record names");
  assert.equal(
    sessions.calls.filter((call) => call.method === "prompt").length,
    promptsAfterFirst,
    "a replay performs no second delivery",
  );
  fs.rmSync(root, { recursive: true, force: true });
});

test("a hand-off writes its record before the Manager is prompted, so the fact survives a failed prompt", async () => {
  const root = tempRoot();
  const sessions = fakeSessions({ fail: "prompt" });
  const host = makeRelayHost({
    sessions,
    root,
    stateOf: (sessionID) => new SessionState({ sessionID, root }),
  });

  const started = await host.startManager("# draft", { operatorInput: "words", planSession: "ses_plan" });
  assert.equal(started.ok, false, JSON.stringify(started));
  assert.equal(started.reason, "MANAGER_PROMPT_FAILED");
  // The half-open Manager is taken back, and nothing claims it started.
  assert.equal(sessions.calls.some((call) => call.method === "remove"), true);
  assert.equal(started.session, null);
  fs.rmSync(root, { recursive: true, force: true });
});

// ---------------------------------------------------------------------------
// The predecessor's workspace (根 §2.4 第 3 步, §6.1)
//
// The plugin resolves a workspace per session (`resolveSessionRoot`: the
// operator's stated root, then the host's session location), and the record is
// written into the INCUMBENT's workspace. The incumbent's original input lives
// beside that record, so a hand-over that wrote the record under one root and
// read its evidence from another refused a predecessor whose facts existed, and
// — worse — retired one on the strength of a different workspace's file.
//
// The assertions are about WHERE the fact came from, never about call order.

test("a hand-over reads the predecessor's original from that session's own workspace", async () => {
  const hostRoot = tempRoot();
  const sessionRoot = tempRoot();
  const sessions = fakeSessions();
  // The operator's words are in the session's workspace only. The plugin host's
  // root holds nothing.
  seedOperator(sessionRoot, "ses_p");
  const host = makeRelayHost({
    sessions,
    root: hostRoot,
    stateOf: (sessionID) => new SessionState({ sessionID, root: hostRoot }),
    rootOfSession: (sessionID) => (sessionID === "ses_p" ? sessionRoot : hostRoot),
  });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, true, JSON.stringify(done));
  // The record lives where the gates look for it: the incumbent's workspace.
  assert.equal(readRelay(relayFile(sessionRoot, "ses_p"))?.operatorInput, OPERATOR_INPUT);
  assert.equal(readRelay(relayFile(hostRoot, "ses_p")), null, "no record is written into the plugin host's root");
  fs.rmSync(hostRoot, { recursive: true, force: true });
  fs.rmSync(sessionRoot, { recursive: true, force: true });
});

test("a record already in the predecessor's workspace is recovered, not repeated", async () => {
  // The recovery read and the write must agree on one root: a record written
  // under the session's workspace and looked up under the plugin host's root
  // would be re-created, retiring an incumbent that was already retired and
  // opening a second successor.
  const hostRoot = tempRoot();
  const sessionRoot = tempRoot();
  const sessions = fakeSessions();
  seedOperator(sessionRoot, "ses_p");
  const existing = relayRecord({
    sessionID: "ses_p",
    incumbent: "ses_p",
    successor: "ses_s",
    tenure: 1,
    milestone: "execute",
    phase: RELAY_PHASES.SENDING,
    requestGate: RELAY_GATE.HOST,
    toolGate: RELAY_GATE.HOST,
  });
  assert.equal(writeRelay(relayFile(sessionRoot, "ses_p"), existing).ok, true);
  const host = makeRelayHost({
    sessions,
    root: hostRoot,
    stateOf: (sessionID) => new SessionState({ sessionID, root: sessionRoot }),
    rootOfSession: () => sessionRoot,
  });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, true, JSON.stringify(done));
  assert.equal(done.recovered, true);
  assert.equal(done.successor, "ses_s");
  assert.equal(sessions.calls.filter((call) => call.method === "create").length, 0, "no second successor");
  assert.equal(readRelay(relayFile(sessionRoot, "ses_p")).phase, RELAY_PHASES.SENT);
  fs.rmSync(hostRoot, { recursive: true, force: true });
  fs.rmSync(sessionRoot, { recursive: true, force: true });
});

test("the record's bound devops is the predecessor's own road-bound worker", async () => {
  // `devopsInFlight` is shared by the whole activation, so the relay must
  // filter it through the predecessor's own roster: handing the successor a
  // devops another Manager started would let it be steered by a tenure that
  // does not own it.
  const root = tempRoot();
  const sessions = fakeSessions();
  seedOperator(root, "ses_p");
  const host = makeRelayHost({
    sessions,
    root,
    stateOf: (sessionID) => new SessionState({ sessionID, root }),
    workersOf: (sessionID, { root: workspace } = {}) => {
      // The lookup names the workspace it was asked about, so a caller that
      // assumed the plugin root is visible here.
      assert.equal(workspace, root, sessionID);
      return {
        roster: [{ name: "w2", role: "devops", sessionID: "ses_w2", state: "idle" }],
        unclaimed: [],
        devops: ["w2"],
      };
    },
  });

  const done = await host.handOver({ predecessor: "ses_p", milestone: "execute", tenure: 0 });
  assert.equal(done.ok, true, JSON.stringify(done));
  assert.deepEqual(readRelay(relayFile(root, "ses_p")).boundDevops, ["w2"]);
  fs.rmSync(root, { recursive: true, force: true });
});
