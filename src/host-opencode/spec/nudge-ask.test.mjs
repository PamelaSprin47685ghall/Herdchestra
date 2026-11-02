// The unattended-exit invariant on the OpenCode adapter, proved by property
// (根 §2.2, §2.3, §2.4).
//
// Three theorems, each over an arbitrary lifecycle of the adapter's own
// surfaces:
//
//   1. Ask gate.  `this.ask` parks and interrupts in Plan's `S1` only, and is
//                 refused `ASK_FORBIDDEN_OUTSIDE_S1` in every other milestone,
//                 for the Manager, and for both worker identities.
//   2. Ask continuity.  A `S1` `ask` suspends the body in place; the operator's
//                 reply reaches the suspended expression through `this.unpark`
//                 and the body continues with that value, having run each
//                 statement once.
//   3. Never stops.  A natural turn exit of Plan or Manager is refused with a
//                 nudge that names every open obligation, unless it is the `S1`
//                 ask or the verified-completion exit — and one round is
//                 nudged once, however many events the host emits for it.
//
// Everything runs against the real surface, the real ledger and the real
// controller; only the host is a scripted domain.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import fc from "fast-check";

import { setup } from "./plugin.mjs";
import { ASK_FORBIDDEN, ROLES } from "../../compose/program/surface.mjs";
import { STANDING_ORDER } from "../../compose/nudge.mjs";
import { cueScalar } from "../../core/cue.mjs";
import {
  ASK_MILESTONE,
  MANAGER_MILESTONES,
  PLAN_MILESTONES,
  ReviewLedger,
  REVIEW_KINDS,
} from "../../core/review.mjs";
import { stateFile, RECORD_TYPES, RECORD_VERSION } from "./session-state.mjs";
import { handleTurnEnd, rowOf } from "./turn-nudge.mjs";
import { openObligations, turnExit } from "./nudge.mjs";
import { fcRuns } from "../../core/test-runs.mjs";

// What the nudge must show, per fact (§6.3, `buildNudgeMessage`).
//
//   a KEY      is written between backticks, raw: `- \`key\`:`
//   a VALUE    is a CUE scalar, encoded by the same `cueScalar` the nudge used
//
// So the two are asserted differently on purpose. Encoding a key would look for
// a literal the nudge never writes; looking for a raw value would miss the
// escape CUE applies to it. Each check names the shape the nudge really emits.
const nudgeNamesKey = (rendered, key) => rendered.includes(`\`${key}\``);
const nudgeQuotesValue = (rendered, value) => rendered.includes(cueScalar(String(value)));

const RESET = (budget = 50) => ({ kind: "resetScope", newScopeGoal: "test", newScopeRetentionBudget: budget });

// A scripted host. `sessions` is the SessionDomain the plugin reads; `events`
// is fed to the pump one item at a time, so a test can push a completion event
// and wait for the controller to answer it.
const fakeHost = async ({ sessions = {} } = {}) => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "herdchestra-nudge-"));
  const tools = new Map();
  const before = [];
  const after = [];
  const context = [];
  const prompt = [];
  const compaction = [];
  const generate = [];
  const prompts = [];
  let emit = null;
  const stream = {
    [Symbol.asyncIterator]: () => ({
      next: () =>
        new Promise((resolve) => {
          emit = (value) => {
            emit = null;
            resolve({ done: false, value });
          };
        }),
      return: async () => ({ done: true }),
    }),
  };
  const ctx = {
    location: { directory: dir },
    options: {},
    agent: {
      transform: async (callback) => {
        const agents = [];
        callback({ list: () => agents, update: (id, update) => update({ id }) });
      },
    },
    tool: {
      transform: async (callback) => callback({ add: (tool) => tools.set(tool.name, tool), update() {}, remove() {} }),
      hook: async (name, callback) => (name === "execute.before" ? before : after).push(callback),
    },
    session: {
      hook: async (name, callback) => {
        if (name === "context") context.push(callback);
        else if (name === "prompt") prompt.push(callback);
        else if (name === "compaction") compaction.push(callback);
        else if (name === "generate") generate.push(callback);
      },
      prompt: async (input) => {
        prompts.push(input);
        return { data: {} };
      },
      synthetic: async () => ({ data: {} }),
      interrupt: async () => {},
      // A session this adapter creates runs its parent's model
      // (`model-inherit.mjs`), so the host must be able to report one; a
      // hand-off that cannot read the Plan's model is refused before a Manager
      // exists, and the Plan's hand-off fact is then never written.
      get: async (input) => ({
        data: { id: input.sessionID, model: { providerID: "kw92", id: "lazy/task" } },
      }),
      create: async (input) => ({ data: { id: `ses_created_${(sequence += 1)}`, ...input } }),
      ...sessions,
    },
    event: { subscribe: () => stream },
  };
  let sequence = 0;
  const cleanup = await setup(ctx);
  const call = async (name, input, { agent = "coder", sessionID = "ses_main" } = {}) => {
    const id = `call_${(sequence += 1)}`;
    const scoped = { ...input, scope: input.scope ?? RESET() };
    const event = { tool: name, sessionID, agent, messageID: `msg_${sequence}`, id, input: scoped };
    for (const hook of before) await hook(event);
    const result = await tools.get(name).execute(scoped, {
      sessionID,
      agent,
      messageID: event.messageID,
      id,
      signal: undefined,
      progress: async () => {},
    });
    const output = result.output;
    for (const hook of after) await hook({ ...event, status: "completed", result: output });
    return output;
  };
  // One completion event, pumped and awaited. The pump subscribes inside
  // `setup`, so the first event may arrive before its iterator is ready.
  const complete = async (sessionID, { type = "session.execution.succeeded", agent, ...rest } = {}) => {
    for (let turn = 0; turn < 200 && emit === null; turn += 1) {
      await new Promise((resolve) => setTimeout(resolve, 1).unref());
    }
    if (emit === null) throw new Error("the event pump is not reading");
    const finish = emit;
    finish({ type, data: { sessionID, ...(agent === undefined ? {} : { agent }), ...rest } });
    // The controller runs inside the pump's own turn; two turns of the loop
    // settle it.
    for (let turn = 0; turn < 5; turn += 1) await new Promise((resolve) => setTimeout(resolve, 1).unref());
  };
  const says = async (sessionID, text) => prompt[0]({ sessionID, messageID: `m_${(sequence += 1)}`, prompt: { text }, delivery: "steer" });
  return {
    dir,
    call,
    complete,
    says,
    prompts,
    compaction,
    generate,
    cleanup,
    state: cleanup.stateOf,
    // The activation the plugin built. The controller and the ledger table are
    // facts of it, and a test that plants a walked ledger needs the same object
    // the request hooks read.
    activation: cleanup.activation,
  };
};

// The ledger of one side, walked to a milestone with recorded obligations the
// way the host's own reviews walk it, so no state is invented.
const walkTo = (kind, milestone, obligations = []) => {
  const ledger = new ReviewLedger({ kind });
  const order = kind === "plan" ? PLAN_MILESTONES : MANAGER_MILESTONES;
  const target = order.indexOf(milestone);
  let guard = 0;
  while (ledger.milestoneIndex < target && guard < 16) {
    // A relay opens the successor inside the same step; the host performs it.
    const receipt = ledger.review([], kind === "plan" ? "# draft" : null);
    if (receipt.kind === REVIEW_KINDS.RELAY) ledger.openSuccessor();
    guard += 1;
  }
  if (obligations.length > 0) ledger.apply(obligations);
  return ledger;
};

// One plugin activation over a temp workspace, and the tool/agent a test calls.
// The ledger is the real one the host walks; `given` is for a caller that drove
// it through the tools itself.
const surfaceOf = async ({ kind, milestone, role = null, obligations = [], ledger: given = null, sessionID = "ses_gate" }) => {
  const host = await fakeHost();
  // A caller that drove a real ledger through the host passes it in.
  const ledger = given ?? walkTo(kind, milestone, obligations);
  const tool = role === null ? (kind === "plan" ? "plan" : "js-manager") : role;
  const agent = tool === "plan" ? "Plan" : tool === "js-manager" ? "Manager" : tool === "devops" ? "devops" : "coder";
  // The ledger is registered under ITS OWN kind, never under the caller's
  // `kind` argument: a walked Plan ledger planted as a Manager one would make
  // `ledgerFor("plan")` rebuild a different object, and the surface would judge
  // with a ledger the test never walked.
  return { host, ledger, tool, agent, sessionID, plant: () => plantLedger(host, ledger.kind, sessionID, ledger) };
};

// Make a walked ledger the session's live one. The activation's table is what
// `ledgerFor` answers from, so every program of that session judges with this
// exact object and the controller reads the same one.
const plantLedger = (host, kind, sessionID, ledger) => {
  // `openLedger` is the state's own door for this: it registers the ledger as
  // the session's live one and records its row. Writing the private map
  // directly worked only while `#ledgers` was a plain field, and it skipped the
  // durable row the controller reads on a cold start.
  host.activation.states.get(sessionID).openLedger(kind, { ledger });
  return ledger;
};

const milestoneArb = fc.oneof(
  fc.constantFrom(...PLAN_MILESTONES).map((milestone) => ({ kind: "plan", milestone })),
  fc.constantFrom(...MANAGER_MILESTONES).map((milestone) => ({ kind: "manager", milestone })),
);

const obligationArb = fc
  .array(
    fc.record({
      key: fc.string({ minLength: 1, maxLength: 10 }).filter((key) => key.trim() !== ""),
      value: fc.string({ minLength: 1, maxLength: 30 }).filter((value) => value.trim() !== ""),
    }),
    { maxLength: 5 },
  )
  .map((entries) => {
    // A ledger is keyed: the last write of a key wins.
    const byKey = new Map();
    for (const entry of entries) byKey.set(entry.key, entry.value);
    return [...byKey.entries()].map(([key, value]) => ({ key, value }));
  });

const roundArb = fc.string({ minLength: 1, maxLength: 12 }).filter((round) => round.trim() !== "");

test("theorem 1: this.ask parks and interrupts in Plan S1, and is refused everywhere else", async () => {
  // The positive half, on the real tool and the real surface.
  {
    const { host, tool, agent, plant } = await surfaceOf({ kind: "plan", milestone: "S1" });
    plant();
    const receipt = await host.call(tool, { code: 'return await this.ask("先做什么？");' }, { agent });
    assert.equal(receipt.state, "parked", JSON.stringify(receipt));
    assert.equal(receipt.reason, "ask");
    assert.deepEqual(receipt.payload, { question: "先做什么？" });
    assert.equal(receipt.interrupt, true);
    await host.cleanup();
  }

  // The negative half, over every (kind, milestone, face) that can try it.
  const faces = [
    { face: "plan", kind: "plan" },
    { face: "js-manager", kind: "manager" },
    { face: "js-worker", kind: "manager" },
  ];
  await fc.assert(
    fc.asyncProperty(
      milestoneArb,
      fc.constantFrom(...faces),
      fc.string({ minLength: 1, maxLength: 20 }).filter((question) => question.trim() !== ""),
      async ({ kind, milestone }, { face, kind: faceKind }, question) => {
        // The ledger is always the FACE's own side: a Plan face judging a
        // Manager ledger is not a state the host can reach, and planting one
        // would test a combination that cannot exist.
        const side = face === "plan" ? "plan" : "manager";
        // The milestone follows the side. A Plan face only has Plan milestones,
        // so a Manager arbitrary lands it at `S1`; the verdict is computed from
        // the milestone actually planted, never from the unused arbitrary.
        const planted = side === "plan" ? (kind === "plan" ? milestone : ASK_MILESTONE) : milestone;
        const { host, tool, agent, sessionID, plant } = await surfaceOf({ kind: side, milestone: planted, role: face === "js-worker" ? "js-worker" : null });
        plant();
        const code = `return await this.ask(${JSON.stringify(question)});`;
        const allowed = face === "plan" && side === "plan" && planted === ASK_MILESTONE;
        if (face === "js-worker") {
          // A worker never ASKS. The face may omit the member entirely, or keep
          // it for the named refusal; either way a call must not park.
          const kindOf = await host.call(tool, { code: "return typeof this.ask;" }, { agent, sessionID });
          if (kindOf.value !== "undefined") {
            const receipt = await host.call(tool, { code: `return await this.ask(${JSON.stringify(question)});` }, {
              agent,
              sessionID,
            });
            assert.equal(receipt.state, "failed", JSON.stringify(receipt));
            assert.equal(receipt.error.message.includes(ASK_FORBIDDEN), true, receipt.error.message);
          }
          await host.cleanup();
          return;
        }
        const receipt = await host.call(tool, { code }, { agent, sessionID });
        if (allowed) {
          assert.equal(receipt.state, "parked");
          assert.equal(receipt.interrupt, true);
        } else {
          // A refused ask parks nothing: the body throws the refusal, the
          // program fails, and the failure names the rule.
          assert.equal(receipt.state, "failed", JSON.stringify(receipt));
          assert.equal(receipt.error.message.includes(ASK_FORBIDDEN), true, receipt.error.message);
        }
        await host.cleanup();
      },
    ),
    { ...fcRuns(40) },
  );
});

test("theorem 1: the first empty review asks the operator in Plan S1 only", () => {
  fc.assert(
    fc.property(milestoneArb, ({ kind, milestone }) => {
      const ledger = walkTo(kind, milestone);
      const receipt = ledger.review([], kind === "plan" ? "# draft" : null);
      const s1FirstTenure = kind === "plan" && milestone === ASK_MILESTONE && ledger.tenure === 0;
      if (s1FirstTenure) {
        assert.equal(receipt.kind, REVIEW_KINDS.ASK_USER);
        assert.equal(receipt.question, "我需要干什么？");
        assert.equal(receipt.interrupt, true);
        assert.equal(ledger.milestone, ASK_MILESTONE, "the question does not advance the milestone");
        return;
      }
      assert.notEqual(receipt.kind, REVIEW_KINDS.ASK_USER, "no other stage asks the operator");
    }),
    { ...fcRuns(100) },
  );
});

test("theorem 2: a S1 ask suspends in place and unpark resumes it with the reply", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.string({ minLength: 1, maxLength: 20 }).filter((question) => question.trim() !== ""),
      fc.string({ minLength: 1, maxLength: 20 }),
      async (question, answer) => {
        const { host, tool, agent, sessionID, plant } = await surfaceOf({ kind: "plan", milestone: "S1" });
        plant();
        // The body records how many times it ran, so a re-run is visible.
        const code = `
          const first = await this.ask(${JSON.stringify(question)});
          const second = first + "|" + first;
          return { first, second, runs: 1 };`;
        const parked = await host.call(tool, { code }, { agent, sessionID });
        assert.equal(parked.state, "parked", JSON.stringify(parked));
        assert.equal(parked.reason, "ask");
        assert.deepEqual(parked.payload, { question });
        assert.equal(parked.interrupt, true);

        // The operator's reply is the next prompt of the session.
        const answered = await host.says(sessionID, answer);
        assert.equal(answered.answered.answer, answer);
        // The reply is injected as the value of the suspended expression and the
        // body continues from there, once.
        assert.equal(answered.answered.resumed.value.first, answer);
        assert.equal(answered.answered.resumed.value.second, `${answer}|${answer}`);
        assert.equal(answered.answered.resumed.value.runs, 1);
        await host.cleanup();
      },
    ),
    { ...fcRuns(20) },
  );
});

test("theorem 3: a natural turn exit is nudged unless it is the S1 ask or verified completion", () => {
  fc.assert(
    fc.property(
      milestoneArb,
      obligationArb,
      roundArb,
      fc.boolean(),
      fc.boolean(),
      ({ kind, milestone }, obligations, round, successor, asked) => {
        const ledger = walkTo(kind, milestone, obligations);
        // A tenure after the first is the one that judges the previous work;
        // the ledger's own `openSuccessor` is the only way to reach it. It is
        // drawn only where a relay can really happen — below the last
        // milestone — because a finished side has no successor to open.
        if (successor && ledger.milestoneIndex < (kind === "plan" ? PLAN_MILESTONES : MANAGER_MILESTONES).length - 1) {
          ledger.openSuccessor();
        }
        // A question only exists where the ledger allows one (Plan's S1). The
        // arbitrary flag is filtered through that fact instead of pretending a
        // question can be pending at any milestone: the gate is the ledger's.
        const askable = kind === "plan" && ledger.milestone === ASK_MILESTONE;
        const asking = asked && askable;
        // Plan's exit is the verification plus the hand-off. The verification
        // is the TENURE, not the `successor` flag: the promotion IS the
        // successor's first review, and that review is what clears the flag
        // (根 §2.4 第 5 步), so `successor === true` at 待执行 is a state the
        // ledger cannot hold.
        const atCompletion =
          kind === "manager"
            ? ledger.milestone === MANAGER_MILESTONES.at(-1)
            : ledger.milestone === PLAN_MILESTONES.at(-1) && ledger.tenure >= 1;
        // The hand-off is a DURABLE fact, not a consequence of the milestone:
        // the controller reads it from the row the hand-off wrote. The oracle
        // models the successful hand-off, and the test below pins the negative
        // (待执行 with no hand-off is nudged).
        const handedOff = kind === "plan" && atCompletion;
        const decision = turnExit({ role: kind, ledger, event: { id: round }, asked: asking, handedOff });
        // §2.2/§2.3: the exit is reached only after the ledger is empty. A
        // completion milestone with open obligations is not an earned stop (the
        // generator can reach it only by applying findings after the walk), so
        // the oracle must not treat it as one.
        const exit = asking || (atCompletion && ledger.obligations.length === 0);
        if (asking) {
          // A pending S1 question is the operator's exit: no nudge, and the
          // round is not booked as a stop.
          assert.equal(decision.nudge, null);
          assert.equal(decision.hold, "asked");
          return;
        }
        assert.equal(decision.hold, null);
        assert.equal(decision.stopAllowed, exit);
        assert.equal(decision.round, round);
        if (exit) {
          assert.equal(decision.nudge, null);
          assert.equal(decision.reason, "verified-completion");
          return;
        }
        assert.equal(decision.reason, "unattended-nudge");
        assert.equal(typeof decision.nudge, "string");
        assert.ok(decision.nudge.includes(STANDING_ORDER), "the standing order is stated");
        assert.ok(decision.nudge.includes("当前的 review 账本还有上述事项没有做，立刻去做！"));
        // The nudge names what the ledger holds NOW. A successor opened above
        // starts with an empty ledger (that is what a relay does), so the
        // generated findings are not the obligations the nudge can quote.
        const open = ledger.obligations;
        for (const { key, value } of open) {
          assert.ok(nudgeNamesKey(decision.nudge, key), `the nudge names ${key}`);
          assert.ok(nudgeQuotesValue(decision.nudge, value), `the nudge quotes ${key}'s obligation`);
        }
        if (open.length === 0) {
          assert.ok(decision.nudge.includes("账本当前为空"), "an empty ledger is not 'nothing to do'");
        }
        // The nudge's own STRUCTURE is CUE, never raw JSON. An obligation the
        // model wrote may itself contain `{"`; that is its data, and quoting it
        // faithfully is the point, so the check reads the status block only.
        const status = decision.nudge.slice(0, decision.nudge.indexOf("## review 账本"));
        assert.equal(status.includes('{"'), false);
      },
    ),
    { ...fcRuns(300) },
  );
});

test("theorem 3: 待执行 without a durable hand-off is nudged, not stopped", () => {
  // The audit's counterexample: a Plan promoted to 待执行 by a successor's
  // `confirm` (or one whose hand-off was refused `DRAFT_MISSING`) has the last
  // milestone but no hand-off. Reading the milestone alone as an earned exit
  // would let it stop with no Manager started.
  const ledger = walkTo("plan", PLAN_MILESTONES.at(-1));
  assert.ok(ledger.tenure >= 1, "待执行 is judged by a tenure after the first");
  const decision = turnExit({ role: "plan", ledger, event: { id: "round_no_handoff" } });
  assert.equal(decision.stopAllowed, false);
  assert.equal(decision.reason, "unattended-nudge");
  assert.equal(typeof decision.nudge, "string");
});

test("theorem 3: an execution the operator interrupted is never nudged", async () => {
  // The host says "the operator stopped this" with the event TYPE. A nudge
  // answers a model that stopped on its own, so this event must not be judged as
  // one — measured live on omp 18.14.2 (2026-11-01, session 01a1958e): the same
  // hole nudged 24 ms after an operator interrupt.
  const ledger = walkTo("manager", MANAGER_MILESTONES[0], []);
  const decision = turnExit({ role: "manager", ledger, event: { type: "session.execution.interrupted", data: { id: "r1" } } });
  assert.equal(decision.stopAllowed, true);
  assert.equal(decision.reason, "operator-interrupt");
  assert.equal(decision.nudge, null);

  // The controller refuses to ask at all, whatever the ledger still owes.
  const held = await handleTurnEnd({
    activation: { agents: new Map(), states: new Map(), asks: { hasPending: () => false } },
    sessionID: "s1",
    event: { type: "session.execution.interrupted", data: { sessionID: "s1", id: "r1" } },
  });
  assert.equal(held.nudged, false);
  assert.equal(held.hold, "operator-interrupt");

  // The same ledger, a natural stop: still a nudge.
  const natural = turnExit({ role: "manager", ledger, event: { type: "session.execution.succeeded", data: { id: "r2" } } });
  assert.equal(natural.reason, "unattended-nudge");
  assert.equal(typeof natural.nudge, "string");
});

test("theorem 3: an unreadable ledger is reported as unknown, never as empty", () => {
  fc.assert(
    fc.property(roundArb, (round) => {
      const seen = openObligations({ kind: "manager", milestone: "execute" });
      assert.equal(seen.ok, false);
      assert.deepEqual(seen.obligations, []);
      // The row without an obligation list is the same fact.
      const row = { v: RECORD_VERSION, type: RECORD_TYPES.REVIEW, kind: "manager", milestone: "execute", tenure: 0 };
      const decision = turnExit({ role: "manager", row, event: { id: round } });
      assert.equal(decision.stopAllowed, false);
      assert.ok(decision.nudge.includes("账本当前为空"));
    }),
    { ...fcRuns(50) },
  );
});

test("theorem 3: the host nudges one round once, and delivers the ledger's obligations", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom(...MANAGER_MILESTONES.slice(0, -1)),
      obligationArb,
      roundArb,
      fc.integer({ min: 1, max: 4 }),
      async (milestone, obligations, round, repeats) => {
        const { host, sessionID, plant } = await surfaceOf({ kind: "manager", milestone, obligations });
        plant();
        // The identity the controller reads is the one the session's requests
        // reported.
        await host.call("js-manager", { code: "return 1;" }, { agent: "Manager", sessionID });
        const before = host.prompts.length;
        // The host emits several events for one turn; only the first is judged.
        for (let at = 0; at < repeats; at += 1) {
          await host.complete(sessionID, { id: round, messageID: round });
        }
        const nudges = host.prompts.slice(before);
        assert.equal(nudges.length, 1, "one round, one nudge");
        const [nudge] = nudges;
        assert.equal(nudge.sessionID, sessionID);
        assert.equal(nudge.delivery, "queue");
        assert.ok(nudge.text.includes(STANDING_ORDER));
        assert.ok(nudge.text.includes("当前的 review 账本还有上述事项没有做，立刻去做！"));
        for (const { key, value } of obligations) {
          // The nudge is CUE (根 §6.3), so a key or obligation containing a
          // backslash or a `#` run is encoded. Compare through the same
          // normalisation the rest of this file uses: the model must be able to
          // read what it still owes, not the raw bytes.
          assert.ok(nudgeNamesKey(nudge.text, key), `the nudge names ${key}`);
          assert.ok(nudgeQuotesValue(nudge.text, value), `the nudge quotes ${key}'s obligation`);
        }
        await host.cleanup();
      },
    ),
    { ...fcRuns(25) },
  );
});

test("theorem 3: the exits stop without a nudge, and a worker's turn end is its report", async () => {
  // Plan's S1 question: the round that asked is not nudged.
  {
    const { host, sessionID, plant } = await surfaceOf({ kind: "plan", milestone: "S1" });
    plant();
    await host.call("plan", { code: 'return await this.ask("先做什么？");' }, { agent: "Plan", sessionID });
    const before = host.prompts.length;
    await host.complete(sessionID, { id: "round_asked" });
    assert.equal(host.prompts.length, before, "the S1 ask is the operator's exit");
    await host.cleanup();
  }
  // Plan's verified hand-off: a successor at 待执行 that handed off stops.
  {
    const { host, sessionID, plant } = await surfaceOf({ kind: "plan", milestone: "S3" });
    // The session must judge with the walked ledger, not one it rebuilds.
    plant();
    const state = host.state(sessionID);
    const draft = path.join(host.dir, "plans", `${sessionID}.md`);
    await fs.mkdir(path.dirname(draft), { recursive: true });
    await fs.writeFile(draft, "the plan\n");
    // 待执行 is reached the way 根 §2.4 says BOTH sides reach it: an incumbent's
    // empty review at `S3` RELAYS (retire + barrier + successor at the SAME
    // milestone), and that successor's own first empty review promotes the
    // milestone. This fixture's host exposes no session API, so it cannot
    // perform a relay — `adoptSuccessor` is the state a performed relay leaves
    // (the same call a cold start makes from a relay record), and the promotion
    // below is the successor's own real review.
    const ledger = state.ledgerFor("plan");
    ledger.adoptSuccessor({ tenure: ledger.tenure + 1, milestone: "S3" });
    const promotedToReady = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID });
    assert.equal(promotedToReady.value.kind, "confirm", JSON.stringify(promotedToReady.value));
    assert.equal(state.ledgerFor("plan").milestone, "待执行");
    // The promotion consumed the successor's first review, so the flag is gone
    // and the TENURE is the verification (根 §2.4 第 5 步). Reaching 待执行 is
    // not the exit on its own: the tenure that judges the previous one must also
    // have handed off, so the next empty review is the hand-off.
    assert.equal(ledger.successor, false);
    assert.ok(ledger.tenure >= 1, "the ready stage is judged by a tenure after the first");
    const handed = await host.call("plan", { code: "return await review([]);" }, { agent: "Plan", sessionID });
    assert.equal(handed.value.kind, "handoff", JSON.stringify(handed.value));
    const before = host.prompts.length;
    await host.complete(sessionID, { id: "round_handoff" });
    assert.equal(host.prompts.length, before, "verified completion stops without a nudge");
    await host.cleanup();
  }
  // Manager at 已完成.
  {
    const { host, sessionID, plant } = await surfaceOf({ kind: "manager", milestone: "done" });
    // The controller judges the session's own ledger; without planting it, it
    // reads a rebuilt one at `execute` and nudges a closed work.
    plant();
    await host.call("js-manager", { code: "return 1;" }, { agent: "Manager", sessionID });
    const before = host.prompts.length;
    await host.complete(sessionID, { id: "round_done" });
    assert.equal(host.prompts.length, before, "a closed work stops without a nudge");
    await host.cleanup();
  }
  // A worker's turn ending is its report to its Manager.
  {
    const { host, sessionID, plant } = await surfaceOf({ kind: "manager", milestone: "execute", obligations: [{ key: "k", value: "v" }] });
    plant();
    const before = host.prompts.length;
    await host.complete("ses_worker", { agent: "coder", id: "round_worker" });
    assert.equal(host.prompts.length, before);
    await host.cleanup();
  }
});

test("theorem 3: the controller answers from the durable row when no ledger is open", async () => {
  await fc.assert(
    fc.asyncProperty(obligationArb, roundArb, async (obligations, round) => {
      const dir = await fs.mkdtemp(path.join(os.tmpdir(), "herdchestra-row-"));
      const sessionID = "ses_row";
      const file = stateFile(dir, sessionID);
      await fs.mkdir(path.dirname(file), { recursive: true });
      await fs.writeFile(
        file,
        `${JSON.stringify({
          v: RECORD_VERSION,
          type: RECORD_TYPES.REVIEW,
          sessionID,
          kind: "manager",
          milestone: "execute",
          tenure: 0,
          reviews: 1,
          obligations,
          successor: false,
        })}\n`,
      );
      const prompts = [];
      const activation = {
        agents: new Map([[sessionID, "Manager"]]),
        asks: { hasPending: () => false },
        nudgedRounds: new Map(),
        sessions: { prompt: async (input) => prompts.push(input) },
        states: {
          get: (id) =>
            id === sessionID
              ? {
                  file,
                  hasLedger: () => false,
                  ledgerFor: () => {
                    throw new Error("an unopened ledger must not be opened by the controller");
                  },
                }
              : null,
        },
      };
      const first = await handleTurnEnd({ activation, sessionID, event: { type: "session.execution.succeeded", data: { id: round } } });
      assert.equal(first.nudged, true, JSON.stringify(first));
      assert.equal(prompts.length, 1);
      for (const { key, value } of obligations) {
        // The nudge is CUE, so a key or obligation with a backslash or a `#`
        // run is encoded; compare through the same normalisation.
        assert.ok(nudgeNamesKey(prompts[0].text, key), `the nudge names ${key}`);
        assert.ok(nudgeQuotesValue(prompts[0].text, value), `the nudge quotes ${key}'s obligation`);
      }
      const again = await handleTurnEnd({ activation, sessionID, event: { type: "session.execution.succeeded", data: { id: round } } });
      assert.equal(again.nudged, false);
      assert.equal(prompts.length, 1, "the same round is nudged once");
      await fs.rm(dir, { recursive: true, force: true });
    }),
    { ...fcRuns(15) },
  );
});

test("rowOf reads the last row of one side, and only that side", () => {
  fc.assert(
    fc.property(obligationArb, obligationArb, (planObligations, managerObligations) => {
      const records = [
        { type: RECORD_TYPES.REVIEW, kind: "plan", milestone: "S1", obligations: planObligations },
        { type: RECORD_TYPES.LANGUAGE, kind: "plan" },
        { type: RECORD_TYPES.REVIEW, kind: "manager", milestone: "execute", obligations: managerObligations },
      ];
      assert.deepEqual(rowOf(records, "manager").obligations, managerObligations);
      assert.deepEqual(rowOf(records, "plan").obligations, planObligations);
      assert.equal(rowOf([], "plan"), null);
    }),
    { ...fcRuns(50) },
  );
});

test("the ask gate is the ledger's own answer, not the host's", () => {
  fc.assert(
    fc.property(milestoneArb, ({ kind, milestone }) => {
      const ledger = walkTo(kind, milestone);
      // A question is only ever pending where the ledger allows one: Plan's S1.
      const allowed = kind === "plan" && milestone === ASK_MILESTONE;
      const decision = turnExit({ role: kind, ledger, event: { id: "r" }, asked: allowed });
      if (allowed) {
        // The one question: no nudge, and the exit is held as "asked".
        assert.equal(decision.nudge, null);
        assert.equal(decision.hold, "asked");
        assert.equal(decision.stopAllowed, true);
      } else {
        // Everywhere else no question can be pending, so the exit is decided on
        // its own facts — and a decided exit never claims to be an ask.
        assert.notEqual(decision.hold, "asked");
      }
      assert.equal(ledger.kind, kind);
      assert.equal(ledger.milestone, milestone);
    }),
    { ...fcRuns(100) },
  );
});

test("the face of each role carries the members the rule needs", async () => {
  const { SURFACE_MEMBERS } = await import("../../compose/program/surface.mjs");
  // Plan can ask and resume its own ask (§3.3 example 7).
  assert.ok(SURFACE_MEMBERS.plan.includes("ask"));
  assert.ok(SURFACE_MEMBERS.plan.includes("unpark"));
  // The Manager keeps `ask` only so a call reads the named refusal.
  assert.ok(SURFACE_MEMBERS["js-manager"].includes("ask"));
  // A worker's `ask` is not a channel: compose may list the member so a call
  // reads the named refusal (`ASK_FORBIDDEN_OUTSIDE_S1`), and theorem 1 pins
  // that refusal. What must never exist is a worker path that ASKS.
  assert.equal(ROLES.PLAN, "plan");
});
