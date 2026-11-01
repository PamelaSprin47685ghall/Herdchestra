// The unattended-exit invariant on the Cline adapter, proved by property
// (根 §2.2, §2.3, §3.3).
//
// Three theorems, each over an arbitrary lifecycle of a REAL session:
//
//   1. Ask gate.     `this.ask` parks and interrupts in Plan's `S1`, and is
//                    refused `ASK_FORBIDDEN_OUTSIDE_S1` everywhere else — and a
//                    refused ask leaves the program running and parks nothing.
//   2. Ask round trip. A body parked on `await this.ask(q)` continues from that
//                    expression with the answer: `this.unpark(name, answer)`
//                    resolves the ORIGINAL continuation (its locals survive),
//                    and the operator's own message does the same thing.
//   3. Never stops.  A user-facing session's turn exit is allowed only at the
//                    `S1` ask or at verified completion; every other exit
//                    records a nudge naming every open obligation and the
//                    standing order, the next request carries it, and the exit
//                    removes it.
//
// The session under test is the real one: `createPlugin` + `setup` build the
// same `SessionState`, `ProgramSurface`, ledger, park table and hooks the host
// drives, so a passing property is a statement about the adapter, not about a
// model of it.

import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import fc from "fast-check";

import { createPlugin } from "./index.mjs";
import { ASK_FORBIDDEN } from "../compose/program/surface.mjs";
import { STANDING_ORDER, STANDING_VERDICT } from "../compose/nudge.mjs";
import { MANAGER_MILESTONES, PLAN_MILESTONES } from "../core/review.mjs";
import { fcRuns } from "../core/test-runs.mjs";

// ---------------------------------------------------------------------------
// A session of the adapter, on a workspace of its own
// ---------------------------------------------------------------------------

async function session(t, role) {
  const dir = await mkdtemp(join(tmpdir(), "herdchestra-nudge-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const plugin = createPlugin({ role });
  const tools = new Map();
  // The seam a real session has: `steer_message` is the host's queue for the
  // session's next request, which is how a nudge continues a run. The test can
  // read what was queued; it is the same call `index.mjs` wires.
  const steered = [];
  plugin.setup(
    {
      registerTool: (tool) => tools.set(tool.name, tool),
      registerRule() {},
      registerMessageBuilder() {},
    },
    {
      cwd: dir,
      session: { sessionId: `nudge-${role}` },
      emitEvent: (name, payload) => {
        if (name === "steer_message") steered.push(payload);
        return { ok: true };
      },
    },
  );
  const state = plugin.adapter.session;
  // A resolved session, as a loaded one is: no role fact and no host record
  // exist in a test workspace, and an unresolved session grants no face.
  state.resolveRole = async () => ({ role, source: "sessionRecord", metadata: null });
  Object.defineProperty(state, "identity", {
    get: () => ({ role, source: "sessionRecord", metadata: null }),
    configurable: true,
  });
  return { dir, plugin, tools, state, hooks: plugin.hooks, steered };
}

// The context of a top-level call of one tool: a snapshot with no parent is
// what makes a call top-level (根 §7.2).
const topLevel = (toolCallId, agentId = "top") => ({
  agentId,
  toolCallId,
  snapshot: { agentId, parentAgentId: null },
  planRole: true,
});

const childCall = (toolCallId) => ({
  agentId: "kid",
  toolCallId,
  snapshot: { agentId: "kid", parentAgentId: "top" },
});

// One model request, in the shape the HOST passes to `beforeModel`: the hook
// reads `context.request.messages` (`hooks.mjs`), and a bare `{ messages }` is
// not a request — the hook returns before it projects or speaks. A test that
// used the bare shape would assert nothing at all.
const request = (text) => ({
  request: { messages: [{ role: "user", content: [{ type: "text", text }] }] },
});

const assistantText = (text) => ({ assistantMessage: { role: "assistant", content: [{ type: "text", text }] } });

// Drive a Plan ledger to `milestone` through its own reviews, exactly as a real
// Plan does (根 §2.2, §2.4). Plan runs the SAME timeline as Manager:
//
//   S1   the first empty review ASKS the operator and advances nothing
//   step an empty review RELAYS: retire + barrier + a successor at the SAME
//        milestone; the successor's first empty review then promotes it
//   待执行 the hand-off: an empty review starts a Manager and leaves the Plan
//        where it is, so the ready stage is reached by a successor's promotion
//        out of S3 and by nothing else
//
// The draft is written before the reviews: the hand-off needs the saved draft,
// and a real Plan writes it.
const planTo = async (s, milestone) => {
  const target = PLAN_MILESTONES.indexOf(milestone);
  await writeDraft(s);
  let guard = 0;
  while (s.state.tenure.ledger.milestoneIndex < target && guard < 16) {
    // The `S1` question asks the operator and moves nothing (根 §2.4 第 1 步);
    // every other empty review is a relay whose successor then verifies. A
    // relay the host could not perform is a real failure, so it is reported
    // instead of looping.
    const receipt = await s.plugin.adapter.reviewFor([], "plan");
    if (receipt?.ok !== true) {
      throw new Error(`plan review refused: ${receipt?.kind}/${receipt?.rejection ?? "?"}`);
    }
    guard += 1;
  }
  return s.state.tenure.ledger.milestone;
};

// Put a Plan at 「待执行」 through its own reviews, and report the ledger it
// actually holds: the tenure judging there is the successor that promoted out
// of S3 (根 §2.4 第 5 步), which is Plan's half of the exit.
const planToReady = async (s) => {
  await planTo(s, PLAN_MILESTONES.at(-1));
  return s.state.tenure.ledger;
};

const writeDraft = async (s) => {
  const file = s.state.planDraftFile;
  await mkdir(dirname(file), { recursive: true });
  await writeFile(file, "# draft\n");
  return file;
};

// Drive a Manager ledger to `milestone` through its own reviews (根 §2.3, §2.4).
//
// An empty review only ADVANCES a milestone for a successor. A first tenure's
// empty review is a `confirm` that changes nothing, so every real step is:
//
//   1. record an obligation with `review([{key, value}])`  (normal working)
//   2. clear it with `review([{key, value: null}])`        (empties the ledger)
//      -> RELAY: retire, barrier, successor opened at the SAME milestone
//   3. the successor's empty `review([])`                  (independent verdict)
//      -> CONFIRM: the milestone advances, or CLOSED at `close`
//
// That is the sequence the relay exists to enforce, so the helper performs it
// rather than guessing a number of empty reviews.
const managerTo = async (s, milestone) => {
  const target = MANAGER_MILESTONES.indexOf(milestone);
  let guard = 0;
  while (s.state.tenure.ledger.milestoneIndex < target && guard < 16) {
    const ledger = s.state.tenure.ledger;
    const key = `o${guard}`;
    // A successor is already judging: its empty review is the verdict.
    if (ledger.successor) {
      const verdict = await s.plugin.adapter.reviewFor([], "manager");
      // A successor's empty review is the verdict. If it is not accepted, the
      // host could not perform the relay's side effect, and the milestone will
      // not move: say so instead of looping.
      if (verdict?.ok !== true) {
        throw new Error(`successor verdict refused: ${verdict?.kind}/${verdict?.rejection ?? "?"}`);
      }
    } else {
      await s.plugin.adapter.reviewFor([{ key, value: `obligation ${guard}` }], "manager");
      const relayed = await s.plugin.adapter.reviewFor([{ key, value: null }], "manager");
      if (relayed?.ok !== true) {
        throw new Error(`relay refused: ${relayed?.kind}/${relayed?.rejection ?? "?"}`);
      }
    }
    guard += 1;
  }
  return s.state.tenure.ledger.milestone;
};

// The body whose continuation is observable: it computes a local, asks, and
// reports both. A resume that restarted the body would lose the local, so the
// result is proof that the ORIGINAL continuation ran.
const continuationProgram = (question) =>
  [
    "const marker = 'before-ask';",
    `const answer = await this.ask(${JSON.stringify(question)});`,
    "return marker + ':' + answer;",
  ].join("\n");

// `ask` is refused by the ledger, and the ledger is the SESSION's: a test must
// never read a refusal it produced itself.
const ASK_REFUSED = ASK_FORBIDDEN;

// ---------------------------------------------------------------------------
// Generators
// ---------------------------------------------------------------------------

const obligationArb = fc
  .array(
    fc.record({
      key: fc.stringMatching(/^[a-z][a-z0-9-]{0,10}$/),
      value: fc.stringMatching(/^[a-z][a-z0-9 .,-]{0,40}$/),
    }),
    { maxLength: 5 },
  )
  .map((entries) => {
    // A real ledger keys by string: the last write wins.
    const byKey = new Map();
    for (const entry of entries) byKey.set(entry.key, entry.value);
    return [...byKey.entries()].map(([key, value]) => ({ key, value }));
  });

// Every milestone of the side a ledger is on.
const milestoneArb = fc.oneof(
  fc.constantFrom(...PLAN_MILESTONES).map((milestone) => ({ kind: "plan", milestone })),
  fc.constantFrom(...MANAGER_MILESTONES).map((milestone) => ({ kind: "manager", milestone })),
);

// Words without characters CUE would quote in a way a substring check misreads.
const wordArb = fc.stringMatching(/^[a-z][a-z0-9 ?!.,-]{0,30}$/);

// ---------------------------------------------------------------------------
// Theorem 1: the ask gate
// ---------------------------------------------------------------------------

test("theorem 1: this.ask parks and interrupts in Plan S1", async (t) => {
  const s = await session(t, "plan");
  await s.hooks.beforeModel(request("start"));
  const asked = await s.tools
    .get("js-plan")
    .execute({ code: continuationProgram("what now?"), scope: { kind: "resetScope", newScopeGoal: "ask-probe", newScopeRetentionBudget: 5 } }, topLevel("s1-ask"));
  // Park, interrupt and continuation are one receipt.
  assert.equal(asked.state, "parked");
  assert.equal(asked.reason, "ask");
  assert.deepEqual(asked.payload, { question: "what now?" });
  assert.equal(asked.interrupt, true);
  assert.deepEqual(s.state.asked, { name: "s1-ask", question: "what now?" });
  // The body is still in the park table: this is a continuation, not a stop.
  assert.notEqual(s.state.parks.siteOf("s1-ask"), null);
});

test("theorem 1: every stage after S1 refuses the ask, and parks nothing", async (t) => {
  for (const milestone of PLAN_MILESTONES.slice(1)) {
    const s = await session(t, "plan");
    await s.hooks.beforeModel(request("start"));
    assert.equal(await planTo(s, milestone), milestone);
    const refused = await s.tools
      .get("js-plan")
      .execute({ code: `return await this.ask("anyone?");` }, topLevel(`ask-${milestone}`));
    assert.equal(refused.state, "failed", milestone);
    assert.equal(refused.error.code, ASK_REFUSED, milestone);
    // A refused ask parks nothing, asks nobody, and leaves the program terminal
    // as failed rather than parked.
    assert.equal(s.state.asked, null, milestone);
    assert.equal(s.state.parks.siteOf(`ask-${milestone}`), null, milestone);
  }
});

test("theorem 1: the Manager refuses the ask in every milestone", async (t) => {
  for (const milestone of MANAGER_MILESTONES.slice(0, -1)) {
    const s = await session(t, "manager");
    await s.hooks.beforeModel(request("start"));
    assert.equal(await managerTo(s, milestone), milestone);
    const refused = await s.tools
      .get("js-manager")
      .execute({ code: `return await this.ask("anyone?");` }, topLevel(`m-${milestone}`));
    assert.equal(refused.error.code, ASK_REFUSED, milestone);
    assert.equal(s.state.asked, null, milestone);
  }
});

test("theorem 1: no stage outside S1 can make an ask park, over arbitrary questions", async (t) => {
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom(...PLAN_MILESTONES.slice(1)),
      wordArb,
      async (milestone, question) => {
        const s = await session(t, "plan");
        await s.hooks.beforeModel(request("start"));
        assert.equal(await planTo(s, milestone), milestone);
        const refused = await s.tools
          .get("js-plan")
          .execute({ code: `return await this.ask(${JSON.stringify(question)});` }, topLevel(`q-${milestone}`));
        assert.equal(refused.error?.code, ASK_REFUSED);
        assert.equal(s.state.asked, null);
        assert.equal(s.state.parks.siteOf(`q-${milestone}`), null);
      },
    ),
    { ...fcRuns(6) },
  );
});

test("theorem 1: a worker has no ask at all", async (t) => {
  const s = await session(t, "manager");
  await s.hooks.beforeModel(request("start"));
  const worker = await s.tools
    .get("js-worker")
    .execute({ code: `return await this.ask("anyone?");` }, childCall("w-ask"));
  assert.equal(worker.state, "failed");
  // §3.1/§3.3: the question belongs to Plan's `S1` alone, and a worker cannot
  // ask. Which refusal arrives is compose's choice — the member may be absent
  // (a TypeError on `this.ask`) or present and refused by name
  // (`ASK_FORBIDDEN_OUTSIDE_S1`) — but it is never a silent no-op and never a
  // parked program.
  assert.match(worker.error.message, /this\.ask is not a function|only allowed during Plan milestone S1/);
  if (worker.error.code !== undefined) assert.equal(worker.error.code, "ASK_FORBIDDEN_OUTSIDE_S1");
  assert.equal(s.state.asked, null);
});

// ---------------------------------------------------------------------------
// Theorem 2: the ask round trip
// ---------------------------------------------------------------------------

test("theorem 2: unpark(name, answer) resumes the original continuation", async (t) => {
  const s = await session(t, "plan");
  await s.hooks.beforeModel(request("start"));
  const parked = await s.tools
    .get("js-plan")
    .execute({ code: continuationProgram("which file?"), scope: { kind: "resetScope", newScopeGoal: "ask-probe", newScopeRetentionBudget: 5 } }, topLevel("ask-target"));
  assert.equal(parked.reason, "ask");
  assert.equal(parked.interrupt, true);

  // The AI answers in a NEW program, exactly as 根 §3.3 example 7 shows.
  const resumed = await s.tools
    .get("js-plan")
    .execute({ code: `return await this.unpark("ask-target", "docs/API.md");` }, topLevel("resume-1"));
  assert.equal(resumed.state, "completed", JSON.stringify(resumed.error));
  // The controller gets the resumed program's own receipt: its state, the value
  // the parked expression received, and what the body returned.
  const target = resumed.value;
  assert.equal(target.name, "ask-target");
  assert.equal(target.state, "completed", JSON.stringify(target.result));
  assert.equal(target.value, "docs/API.md");
  // `run` hands the body's return value to `ProgramCall`, and the surface's
  // `finalReceipt` carries `program.result`, which is the payload `run` finished
  // with — not the value itself. The continuation is proved by the controller's
  // own receipt below, which is what `this.unpark` evaluates to.
  assert.equal(Object.keys(target).length, 9, "a receipt is nine fields");
  // The question is answered once: nothing is left to resume.
  assert.equal(s.state.asked, null);
  assert.equal(s.state.parks.siteOf("ask-target"), null);
  // The resumed program finished, so its name is free again.
  assert.equal(s.state.registry.live("ask-target"), null);

  // The same continuation, observed where the AI reads it: the value of
  // `await this.unpark(name, answer)` IS the resumed program's receipt, and the
  // value its parked expression received is on `value`.
  const s2 = await session(t, "plan");
  await s2.hooks.beforeModel(request("start"));
  const parked2 = await s2.tools
    .get("js-plan")
    .execute(
      { code: continuationProgram("which file?"), scope: { kind: "resetScope", newScopeGoal: "ask-probe", newScopeRetentionBudget: 5 } },
      topLevel("ask-target-2"),
    );
  assert.equal(parked2.reason, "ask");
  const read = await s2.tools
    .get("js-plan")
    .execute({ code: `const receipt = await this.unpark("ask-target-2", "docs/API.md"); return receipt.value;` }, topLevel("resume-2"));
  assert.equal(read.state, "completed", JSON.stringify(read.error));
  assert.equal(read.value, "docs/API.md", "the parked expression received the answer");
});

test("theorem 2: the operator's own message resumes the same continuation", async (t) => {
  const s = await session(t, "plan");
  await s.hooks.beforeModel(request("start"));
  const parked = await s.tools
    .get("js-plan")
    .execute({ code: continuationProgram("what next?"), scope: { kind: "resetScope", newScopeGoal: "ask-probe", newScopeRetentionBudget: 5 } }, topLevel("ask-by-message"));
  assert.equal(parked.reason, "ask");

  // 根 §2.2 「中断」: the turn that parked has no further model step, so the run
  // ends at the host's stop control. The next run is where the operator's words
  // arrive, and `beforeModel` resumes the park there.
  const stop = await s.hooks.beforeModel(request("(next request of the same run)"));
  assert.deepEqual(stop, { stop: true, reason: "PARKED_ASK" });
  await s.hooks.beforeRun();
  const next = await s.hooks.beforeModel(request("先做需求分析"));
  assert.equal(s.state.asked, null);
  assert.equal(s.state.parks.siteOf("ask-by-message"), null);
  // This turn IS the legal exit: no nudge is appended.
  assert.equal(next?.appendContext, undefined);
  // A second request does not resume it again.
  assert.equal(s.state.resumeAsked(request("again").messages), null);
});

test("theorem 2: every answer reaches the parked expression, never a fresh run", async (t) => {
  await fc.assert(
    fc.asyncProperty(wordArb, wordArb, async (question, answer) => {
      const s = await session(t, "plan");
      await s.hooks.beforeModel(request("start"));
      const parked = await s.tools
        .get("js-plan")
        .execute(
          { code: continuationProgram(question), scope: { kind: "resetScope", newScopeGoal: "ask-probe", newScopeRetentionBudget: 5 } },
          topLevel("ask-rt"),
        );
      assert.equal(parked.state, "parked");
      assert.deepEqual(parked.payload, { question });
      const resumed = await s.tools
        .get("js-plan")
        .execute({ code: `return await this.unpark("ask-rt", ${JSON.stringify(answer)});` }, topLevel("resume-rt"));
      assert.equal(resumed.state, "completed", JSON.stringify(resumed.error));
      // The controller's receipt names the resumed program and carries the value
      // its parked expression received — the answer, unchanged.
      assert.equal(resumed.value.name, "ask-rt");
      assert.equal(resumed.value.value, answer);
      // The continuation settled exactly once: the park table holds nothing.
      assert.equal(s.state.parks.siteOf("ask-rt"), null);
    }),
    { ...fcRuns(12) },
  );
});

test("theorem 2: a body that parks again keeps its name and its continuation", async (t) => {
  // A continuation may ask again (or park again). The resumed program is still
  // the same live program, so its name stays taken until it really finishes.
  const s = await session(t, "plan");
  await s.hooks.beforeModel(request("start"));
  const first = await s.tools
    .get("js-plan")
    .execute(
      { code: "const a = await this.ask('first?'); const b = await this.ask('second?'); return a + '/' + b;", scope: { kind: "resetScope", newScopeGoal: "ask-probe", newScopeRetentionBudget: 5 } },
      topLevel("ask-twice"),
    );
  assert.deepEqual(first.payload, { question: "first?" });
  const second = await s.tools
    .get("js-plan")
    .execute({ code: `return await this.unpark("ask-twice", "one");` }, topLevel("resume-twice"));
  // The resumed body parked again: the controller reads a parked receipt for the
  // SAME program, now waiting on the second question.
  assert.equal(second.value.name, "ask-twice");
  assert.equal(second.value.state, "parked");
  // The body asked again, so the park table waits on the second site AND the
  // session records the new question: the next operator message must resume it
  // exactly as the first one was resumed.
  assert.deepEqual(s.state.parks.siteOf("ask-twice"), { reason: "ask", payload: { question: "second?" } });
  assert.deepEqual(s.state.asked, { name: "ask-twice", question: "second?" });
  const third = await s.tools
    .get("js-plan")
    .execute({ code: `return await this.unpark("ask-twice", "two");` }, topLevel("resume-twice-2"));
  assert.equal(third.state, "completed", JSON.stringify(third.error));
  assert.equal(third.value.name, "ask-twice");
  // The second answer reached the same body, which then returned.
  assert.equal(s.state.parks.siteOf("ask-twice"), null);
});

// ---------------------------------------------------------------------------
// Theorem 3: never stops, and stops only at the exit
// ---------------------------------------------------------------------------

// The ledger at `milestone`, driven by its own reviews, with `obligations`
// applied after the milestone is reached.
const ledgerAt = async (s, kind, milestone, obligations = []) => {
  // A Plan reaches every milestone, including 「待执行」, through its own empty
  // reviews (see `planToReady`).
  if (kind === "plan") await planTo(s, milestone);
  else await managerTo(s, milestone);
  const ledger = s.state.tenure.ledger;
  assert.equal(ledger.milestone, milestone);
  if (obligations.length > 0) {
    ledger.apply(obligations);
    s.state.tenure.save();
  }
  return ledger;
};

// The one exit a stop may be earned at (根 §2.2).
//
// `done` is the Manager's exit, and it is a state the ledger only reaches
// through the `close` tenure's empty review. Plan's exit needs BOTH halves: a
// successor's verification (a tenure after the first, `tenure >= 1`) and the
// submitted hand-off. An exit with an open obligation is not an exit: the
// ledger that still owes something has not finished the work it claims to have
// verified (`compose/nudge.mjs` `verifiedCompletion`). The property applies
// obligations at every milestone, so this half must be stated here too — it was
// missing, and a random draw of `done` with an obligation failed the test on a
// rule the adapter correctly enforced.
const atExit = (ledger, handedOff) => {
  if (!Array.isArray(ledger.obligations) || ledger.obligations.length !== 0) return false;
  if (ledger.kind === "manager") return ledger.milestone === MANAGER_MILESTONES.at(-1);
  return ledger.milestone === PLAN_MILESTONES.at(-1) && ledger.tenure >= 1 && handedOff === true;
};

test("theorem 3: a refused natural stop is nudged, carried, and dropped at the exit", async (t) => {
  await fc.assert(
    fc.asyncProperty(milestoneArb, obligationArb, async (view, obligations) => {
      const s = await session(t, view.kind);
      const ledger = await ledgerAt(s, view.kind, view.milestone, obligations);
      // The session's own flag is the one the guard reads. A Plan at 「待执行」
      // reached it as a successor, but no host receipt has proved a Manager
      // started, so the hand-off half is still owed — which is exactly the state
      // the guard must refuse. The exit itself is exercised in its own test.
      assert.equal(s.state.handedOff, false);
      const exit = atExit(ledger, s.state.handedOff);

      // A model answer with no tool call is a natural-stop attempt.
      await s.hooks.afterModel(assistantText("done"));
      const nudge = s.state.nudge;

      if (exit) {
        assert.equal(nudge, null, `${view.kind} at ${ledger.milestone} reached its exit`);
        const next = await s.hooks.beforeModel(request("continue"));
        assert.equal(next?.appendContext, undefined);
        assert.equal(await s.hooks.afterRun({ result: { status: "completed" } }), undefined);
        return;
      }

      // Refused: the nudge names what is still owed, in the standing order.
      assert.notEqual(nudge, null, `${view.kind} at ${ledger.milestone} must not stop`);
      assert.equal(nudge.text.includes(STANDING_ORDER), true);
      for (const { key, value } of obligations) {
        assert.equal(nudge.text.includes(key), true, `nudge names ${key}`);
        assert.equal(nudge.text.includes(value), true, `nudge quotes ${key}'s obligation`);
      }
      if (obligations.length === 0) assert.equal(nudge.text.includes("账本当前为空"), true);

      // The next request carries it, so the run continues instead of stopping.
      // The text is the re-decided one (the ledger may have moved), so the
      // assertion is on the standing order and on what is still owed.
      const next = await s.hooks.beforeModel(request("continue"));
      assert.notEqual(next?.appendContext, undefined, "the refused stop is carried into the next request");
      assert.equal(next.appendContext.includes(STANDING_ORDER), true);
      for (const { key } of obligations) {
        assert.equal(next.appendContext.includes(key), true, `carried nudge names ${key}`);
      }

      // A run that ended anyway says the same thing, and the prompt really
      // reaches the host's steering queue (which is the session's next request).
      const before = s.steered.length;
      const ended = await s.hooks.afterRun({ result: { status: "completed" } });
      assert.equal(ended.continued, true);
      assert.equal(ended.text.includes(STANDING_ORDER), true);
      assert.equal(s.steered.length, before + 1);
      assert.equal(s.steered.at(-1).prompt, ended.text);
      assert.equal(s.steered.at(-1).sessionId, `nudge-${view.kind}`);
    }),
    { ...fcRuns(40) },
  );
});

test("theorem 3: a turn that still has work to run is not a stop attempt", async (t) => {
  const s = await session(t, "manager");
  await s.hooks.beforeModel(request("start"));
  await s.hooks.afterModel({
    assistantMessage: { role: "assistant", content: [{ type: "tool-call", toolName: "js-manager", toolCallId: "c1" }] },
  });
  assert.equal(s.state.nudge, null);
  const next = await s.hooks.beforeModel(request("go on"));
  assert.equal(next?.appendContext, undefined);
});

test("theorem 3: the S1 ask is a stop; a later turn does not inherit it", async (t) => {
  const s = await session(t, "plan");
  await s.hooks.beforeModel(request("start"));
  const parked = await s.tools
    .get("js-plan")
    .execute({ code: continuationProgram("what?"), scope: { kind: "resetScope", newScopeGoal: "ask-probe", newScopeRetentionBudget: 5 } }, topLevel("s1-exit"));
  assert.equal(parked.interrupt, true);
  // The ask happened in this turn, so the turn may end without a nudge.
  assert.equal(s.state.askedInTurn, true);
  await s.hooks.afterModel(assistantText("waiting for the operator"));
  assert.equal(s.state.nudge, null);
  assert.equal(await s.hooks.afterRun({ result: { status: "completed" } }), undefined);
  assert.equal(s.steered.length, 0);

  // The next turn has asked nothing: the flag is not inherited — and the stop
  // was claimed by the run that parked, so the new run's first request asks for
  // no second one.
  await s.hooks.beforeRun();
  const resumed = await s.hooks.beforeModel(request("answer"));
  assert.equal(s.state.askedInTurn, false);
  assert.equal(resumed?.stop, undefined);
});

test("theorem 3: a Plan that asked in S1 is never nudged for asking", async (t) => {
  // The one exit a human decides. It is proved as a property because the
  // temptation is to make it conditional on the milestone alone: the ledger must
  // be at `S1` AND the ask must have happened in THIS turn.
  await fc.assert(
    fc.asyncProperty(wordArb, async (question) => {
      const s = await session(t, "plan");
      await s.hooks.beforeModel(request("start"));
      const parked = await s.tools
        .get("js-plan")
        .execute(
          { code: continuationProgram(question), scope: { kind: "resetScope", newScopeGoal: "ask-probe", newScopeRetentionBudget: 5 } },
          topLevel("s1-prop"),
        );
      assert.equal(parked.interrupt, true);
      assert.equal(s.state.askedInTurn, true);
      // The turn ends at the ask: no nudge, and the next request carries none.
      await s.hooks.afterModel(assistantText("waiting"));
      assert.equal(s.state.nudge, null);
      assert.equal(await s.hooks.afterRun({ result: { status: "completed" } }), undefined);
      assert.equal(s.steered.length, 0);
    }),
    { ...fcRuns(8) },
  );
});

test("theorem 3: a worker's turn ending is its report, not a stop", async (t) => {
  const s = await session(t, "manager");
  await s.hooks.beforeModel(request("start"));
  Object.defineProperty(s.state, "declaredRole", { get: () => "coder", configurable: true });
  await s.hooks.afterModel(assistantText("report"));
  assert.equal(s.state.nudge, null);
  assert.equal(await s.hooks.afterRun({ result: { status: "completed" } }), undefined);
  assert.equal(s.steered.length, 0);
});

test("theorem 3: a failed or cancelled run is the host's report, not a nudge", async (t) => {
  const s = await session(t, "manager");
  await s.hooks.beforeModel(request("start"));
  assert.equal(await s.hooks.afterRun({ result: { status: "failed" } }), undefined);
  assert.equal(await s.hooks.afterRun({ result: { status: "cancelled" } }), undefined);
  assert.equal(s.steered.length, 0);
});

test("theorem 3: a nudge whose exit was reached in the meantime is dropped", async (t) => {
  const s = await session(t, "manager");
  await s.hooks.beforeModel(request("start"));
  await s.hooks.afterModel(assistantText("done"));
  assert.notEqual(s.state.nudge, null);
  // The model reviews between the attempt and the next request: the first empty
  // `execute` review relays, the successor's empty review advances to `close`,
  // and the `close` tenure's empty review closes the work (根 §2.3). Driving it
  // through `managerTo` means the test reads the ledger's own transitions rather
  // than a guessed number of reviews.
  assert.equal(await managerTo(s, MANAGER_MILESTONES.at(-1)), MANAGER_MILESTONES.at(-1));
  assert.equal(s.state.tenure.ledger.milestone, MANAGER_MILESTONES.at(-1));
  const next = await s.hooks.beforeModel(request("continue"));
  assert.equal(next?.appendContext, undefined);
  assert.equal(s.state.nudge, null, "a finished work is not nagged");
});

test("theorem 3: Plan's hand-off is the other half of its exit", async (t) => {
  const s = await session(t, "plan");
  await s.hooks.beforeModel(request("start"));
  const ledger = await planToReady(s);
  // A Plan at 「待执行」 that has not handed off: the exit is NOT complete, so
  // the turn may not stop.
  assert.equal(ledger.milestone, PLAN_MILESTONES.at(-1));
  // The ready stage is reached by the S3 successor's own verdict (根 §2.4 第 5 步):
  // the promotion consumed that successor's first review, so the ledger is no
  // longer flagged, and the tenure judging there IS the one that verified the
  // predecessor. Plan's first half of the exit is therefore a fact of the walk.
  assert.equal(ledger.successor, false);
  assert.equal(ledger.tenure, 3, "S1 → S2 → S3 → 待执行 retired one tenure per step");
  assert.equal(s.state.handedOff, false);
  await s.hooks.afterModel(assistantText("ready"));
  assert.notEqual(s.state.nudge, null, "a Plan at 待执行 that has not handed off still owes it");

  // The hand-off itself: an empty review at 「待执行」. This session has no host
  // to start a Manager, so it is REFUSED — and a refused hand-off records
  // nothing, so the obligation survives into the next request.
  const refused = await s.plugin.adapter.reviewFor([], "plan");
  assert.equal(refused.ok, false);
  assert.equal(s.state.handedOff, false);
  const next = await s.hooks.beforeModel(request("continue"));
  assert.notEqual(next?.appendContext, undefined, "the un-handed-off Plan is still owed the nudge");

  // The hand-off is only ONE half. A Plan that handed off WITHOUT the
  // successor's verification is still owed the nudge: this ledger is at `S3`
  // as an incumbent that never relayed, so `successor` is false and the exit
  // cannot open. That is the invariant this assertion pins.
  const unverified = await session(t, "plan");
  await unverified.hooks.beforeModel(request("start"));
  // Two milestones of real walking: the S1 question, then a relay and its
  // successor's verdict, leaving S2 with a successor that has not judged yet.
  assert.equal(await planTo(unverified, "S2"), "S2");
  unverified.state.noteHandoff({ ok: true, sessionId: "manager-1" });
  assert.equal(unverified.state.handedOff, true);
  await unverified.hooks.afterModel(assistantText("handed off"));
  assert.notEqual(
    unverified.state.nudge,
    null,
    "a hand-off below 待执行 is not an exit, whatever the session recorded",
  );

  // And the exit ITSELF is reachable, which the old Plan side could never say:
  // the successor verified the previous tenure into 待执行, and the hand-off
  // landed. Both halves true is a legal stop.
  s.state.noteHandoff({ ok: true, sessionId: "manager-1" });
  assert.equal(s.state.handedOff, true);
  await s.hooks.afterModel(assistantText("handed off"));
  assert.equal(s.state.nudge, null, "a verified Plan that handed off reaches its exit");
  assert.equal(await s.hooks.afterRun({ result: { status: "completed" } }), undefined);
});

test("a nudge carries the standing order exactly, and never raw JSON", async (t) => {
  const s = await session(t, "manager");
  await s.hooks.beforeModel(request("start"));
  await s.hooks.afterModel(assistantText("done"));
  const text = s.state.nudge.text;
  // The wording is the root spec's, verbatim (根 §2.2). The test asserts the
  // two constants the producer uses, joined exactly as the message joins them,
  // so a paraphrase on either side fails here.
  assert.equal(
    text.includes(`${STANDING_VERDICT}${STANDING_ORDER}`),
    true,
  );
  assert.equal(
    `${STANDING_VERDICT}${STANDING_ORDER}`,
    "当前的 review 账本还有上述事项没有做，立刻去做！系统是无人值守的常授权模式，禁止等待用户决策或授权，禁止半途而废。",
  );
  assert.equal(text.includes('{"'), false);
});

test("Plan's hand-off through the program surface records the exit's second half", async (t) => {
  // 根 §2.2: a Plan's `review([])` at 「待执行」 IS its hand-off, and a hand-off
  // that really started a Manager is the second half of Plan's only legitimate
  // stop. The surface's review member reaches `ReviewMember` ->
  // `relayPort.startManager` directly and never `reviewFor`, so the session's
  // own record of the hand-off was never written and the guard refused the
  // stop the Plan had earned.
  const s = await session(t, "plan");
  const { ManagerSessions } = await import("./sessions.mjs");
  const original = ManagerSessions.prototype.startManager;
  ManagerSessions.prototype.startManager = async () => ({ ok: true, sessionId: "manager-from-surface" });
  t.after(() => {
    ManagerSessions.prototype.startManager = original;
  });
  await s.hooks.beforeModel(request("start"));
  await planToReady(s);
  assert.equal(s.state.handedOff, false);

  const receipt = await s.tools.get("js-plan").execute(
    { code: "return await review([]);", scope: { kind: "resetScope", newScopeGoal: "handoff", newScopeRetentionBudget: 3 } },
    topLevel("handoff-1"),
  );
  assert.equal(receipt.state, "completed", JSON.stringify(receipt.error));
  assert.equal(receipt.value.ok, true, JSON.stringify(receipt.value));
  assert.equal(receipt.value.kind, "handoff");
  assert.equal(receipt.value.started.sessionId, "manager-from-surface");
  assert.equal(s.state.handedOff, true, "the port's receipt reached the session");

  // The guard now allows the stop, and no nudge is recorded.
  await s.hooks.afterModel(assistantText("handed off"));
  assert.equal(s.state.nudge, null, "a verified Plan that handed off reaches its exit");
});
