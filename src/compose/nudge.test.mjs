// The unattended-exit invariant, proved by property (根 §2.2, §2.3, §2.4).
//
// Three theorems, each an equation over an arbitrary lifecycle:
//
//   1. Ask gate.      `this.ask` parks + interrupts exactly in Plan's `S1`, and
//                     is refused `ASK_FORBIDDEN_OUTSIDE_S1` everywhere else.
//   2. Never stops.   A user-facing agent's turn exit is allowed only at the
//                     `S1` ask or at verified completion; every other exit is
//                     refused with a nudge that names every open obligation.
//   3. Done means stopped.  At either exit the answer is `stopAllowed: true`
//                     with no nudge.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import {
  ReviewLedger,
  PLAN_MILESTONES,
  MANAGER_MILESTONES,
  REVIEW_KINDS,
  askAllowed,
} from "../core/review.mjs";
import { ProgramSurface, ROLES, ASK_FORBIDDEN, SURFACE_MEMBERS } from "./program/surface.mjs";
import { ProgramState } from "../core/program.mjs";
import { fakeClock } from "../core/clock.mjs";
import {
  STANDING_ORDER,
  STOP_REASONS,
  buildNudgeMessage,
  evaluateTurnExit,
  operatorInterruptOf,
  verifiedCompletion,
} from "./nudge.mjs";
import { fcRuns } from "../core/test-runs.mjs";
import { cueScalar } from "../core/cue.mjs";

// The program these surfaces run. It is never executed here: the tests read the
// members the face offers, which is where §2.2's rules live.
const PLAN_SOURCE = "class Js extends JsProgram {\n  async run() { return null; }\n}\n";

const planSurface = ({ milestone = "S1", kind = "plan", name = "p", role = null } = {}) => {
  const ledger = new ReviewLedger({ kind });
  // Drive the real ledger to the milestone, so the test never invents a state
  // the ledger could not reach.
  //
  // 根 §2.4 gives both sides ONE timeline: the first tenure's first empty review
  // asks (Plan `S1`) or verifies (Manager), every later empty review relays, and
  // the successor's first empty review promotes it. The Plan's hand-off at the
  // last milestone is not a milestone step at all, so the walk stops there.
  const order = kind === "plan" ? PLAN_MILESTONES : MANAGER_MILESTONES;
  const target = order.indexOf(milestone);
  let guard = 0;
  while (ledger.milestoneIndex < target && guard < 16) {
    // Plan's hand-off needs the saved draft, exactly as the host supplies it.
    const receipt = ledger.review([], kind === "plan" ? "# draft" : null);
    if (receipt.kind === REVIEW_KINDS.RELAY) ledger.openSuccessor();
    guard += 1;
  }
  const state = new ProgramState(name);
  const parked = [];
  const surface = new ProgramSurface({
    name,
    // The ledger's side decides the default face; a caller may name another
    // role to ask what that face does with the same ledger.
    role: role ?? (kind === "plan" ? ROLES.PLAN : ROLES.MANAGER),
    program: state,
    clock: fakeClock(0),
    // 根 §3.3: every receipt names the program it ran, so a surface is built
    // with one.
    source: PLAN_SOURCE,
    root: "/tmp",
    ledger,
    views: new Map(),
    // The host's park channel: it parks the program (the state change is core's)
    // and answers the receipt the tool call returns. The surface's own `park`
    // does both, so this only records the site the host would see.
    park: (site) => {
      parked.push(site);
      return surface.park(site.payload ?? null, site.reason ?? "explicit");
    },
    ports: {},
  });
  return { ledger, surface, parked, state };
};

// A ledger view the evaluator reads, with arbitrary obligations.
const ledgerView = (kind, milestone, obligations, { tenure = 0, successor = false } = {}) => ({
  kind,
  milestone,
  tenure,
  successor,
  obligations,
});

const milestoneArb = fc.oneof(
  fc.constantFrom(...PLAN_MILESTONES).map((milestone) => ({ kind: "plan", milestone })),
  fc.constantFrom(...MANAGER_MILESTONES).map((milestone) => ({ kind: "manager", milestone })),
);

const obligationArb = fc
  .array(
    fc.record({
      // `validateFindings` accepts any non-empty string, so the generator must
      // too: quotes, backticks, backslashes, CR/LF, NUL and CJK are reachable
      // and are interpolated into the nudge as written.
      key: fc
        .array(fc.constantFrom(..."abcdefghijklmnopqrstuvwxyz0123456789 -_'\"`\\\n\r\0测试"), {
          minLength: 1,
          maxLength: 16,
        })
        .map((chars) => chars.join("")),
      value: fc.string({ minLength: 1, maxLength: 40 }),
    }),
    { maxLength: 6 },
  )
  .map((entries) => {
    // Keys are unique in a real ledger: the last write wins.
    const byKey = new Map();
    for (const entry of entries) byKey.set(entry.key, entry.value);
    return [...byKey.entries()].map(([key, value]) => ({ key, value }));
  });

test("theorem 1: ask parks and interrupts in Plan S1, and only there", () => {
  // The positive half, on the real surface.
  const first = planSurface({ milestone: "S1" });
  const receipt = first.surface.surface().ask("先做什么？");
  assert.equal(first.state.state, "parked");
  assert.equal(receipt.reason, "ask");
  assert.deepEqual(receipt.payload, { question: "先做什么？" });
  assert.equal(receipt.interrupt, true);
  assert.deepEqual(first.parked, [{ reason: "ask", payload: { question: "先做什么？" } }]);

  // The negative half, over every (kind, milestone, role) a surface can be in.
  fc.assert(
    fc.property(
      milestoneArb,
      fc.constantFrom(ROLES.PLAN, ROLES.MANAGER, ROLES.CODER, ROLES.DEVOPS),
      fc.string({ minLength: 1, maxLength: 30 }),
      ({ kind, milestone }, role, question) => {
        const surfaceName = role === ROLES.PLAN ? "plan" : role === ROLES.MANAGER ? "js-manager" : "js-worker";
        // A worker's face has no `ask` member at all: the question is a
        // top-level channel. The member table is the single answer, so the test
        // reads it rather than assuming.
        const hasAsk = SURFACE_MEMBERS[surfaceName].includes("ask");
        const { surface, state, parked } = planSurface({ milestone, kind, name: "p", role });
        if (!hasAsk) {
          assert.equal(surface.surface().ask, undefined);
          return;
        }
        // The gate is the ROLE's and the ledger's: only Plan's `S1` allows;
        // a Manager carrying a plan ledger is still refused.
        const allowed = role === ROLES.PLAN && kind === "plan" && milestone === PLAN_MILESTONES[0];
        if (allowed) {
          surface.surface().ask(question);
          assert.equal(state.state, "parked");
          return;
        }
        assert.throws(
          () => surface.surface().ask(question),
          (error) => error?.code === ASK_FORBIDDEN,
        );
        // A refused ask parks nothing: the program keeps running.
        assert.equal(state.state, "running");
        assert.deepEqual(parked, []);
      },
    ),
    { ...fcRuns(200) },
  );
});

test("theorem 1: askAllowed answers from the ledger alone", () => {
  fc.assert(
    fc.property(milestoneArb, (view) => {
      const answer = askAllowed(ledgerView(view.kind, view.milestone, []));
      const allowed = view.kind === "plan" && view.milestone === PLAN_MILESTONES[0];
      assert.equal(answer.ok, allowed);
      if (!allowed) assert.equal(typeof answer.reason, "string");
    }),
    { ...fcRuns(200) },
  );
});

test("theorem 2: a user-facing agent never stops on its own", () => {
  fc.assert(
    fc.property(
      milestoneArb,
      obligationArb,
      fc.boolean(),
      fc.boolean(),
      fc.constantFrom("plan", "manager"),
      (view, obligations, handedOff, askedInTurn, role) => {
        // A Plan that handed off is judging as a tenure after the first one:
        // 根 §2.4 第 5 步 gives 待执行 to the successor's own verdict, and that
        // verdict is the tenure's first review (which clears the `successor`
        // flag). The pair is drawn as one fact so the view describes a ledger a
        // host can really hold.
        const tenure = view.kind === "plan" && handedOff ? 1 : 0;
        const ledger = ledgerView(view.kind, view.milestone, obligations, { tenure });
        const decision = evaluateTurnExit({ role, ledger, handedOff, askedInTurn });

        const askExit =
          askedInTurn && role === "plan" && view.kind === "plan" && view.milestone === PLAN_MILESTONES[0];
        // The exit is the ledger's own: Plan needs 待执行 judged by a tenure
        // after the first, with the hand-off submitted; Manager needs `done`.
        // Both require an EMPTY ledger: a stop with work still owed is exactly
        // the exit §2.2/§2.3 forbid.
        const completion =
          obligations.length === 0 &&
          (view.kind === "manager"
            ? view.milestone === MANAGER_MILESTONES.at(-1)
            : view.milestone === PLAN_MILESTONES.at(-1) && handedOff);
        const expected = askExit || completion;

        assert.equal(decision.stopAllowed, expected);
        if (expected) {
          assert.equal(decision.nudge, null);
          assert.ok([STOP_REASONS.ASK_S1, STOP_REASONS.VERIFIED_COMPLETION].includes(decision.reason));
          return;
        }
        // The refusal names every open obligation, and states the standing order.
        assert.equal(decision.reason, STOP_REASONS.NUDGE);
        assert.equal(typeof decision.nudge, "string");
        assert.ok(decision.nudge.includes(STANDING_ORDER));
        for (const { key, value } of obligations) {
          // A key is written raw between backticks, so the exact bytes must be
          // there; a value is rendered by `cueScalar`, so its exact literal must
          // be there.
          assert.ok(decision.nudge.includes(key), `nudge names ${key}`);
          assert.ok(
            decision.nudge.includes(cueScalar(String(value))),
            `nudge quotes ${key}'s obligation`,
          );
        }
        if (obligations.length === 0) {
          assert.ok(decision.nudge.includes("账本当前为空"));
        }
      },
    ),
    { ...fcRuns(300) },
  );
});

// The three hosts' spellings of "the OPERATOR ended this turn", as each one
// writes them on the wire (see `OPERATOR_INTERRUPT_PATTERN`). Drawn as data so
// the theorem covers every host's shape, not the one that was measured.
const interruptArb = fc.oneof(
  // omp: the aborted assistant message.
  fc.record({
    stopReason: fc.constant("aborted"),
    errorMessage: fc.constantFrom("Interrupted by user", "Request was aborted", "Interrupted by user "),
  }),
  // OpenCode: the host's event type.
  fc.record({ type: fc.constant("session.execution.interrupted") }),
  // Cline: the run's terminal status.
  fc.record({ status: fc.constantFrom("cancelled", "canceled", "aborted", "interrupted") }),
);

// A turn that ended with no interrupt: the model stopped on its own.
const naturalArb = fc.oneof(
  // `errorMessage` is OMITTED rather than drawn: an absent field is the natural
  // stop, and drawing an empty string would make the generator, not the rule,
  // decide what a host wrote.
  fc.record({ stopReason: fc.constantFrom("stop", "end_turn") }),
  fc.record({ type: fc.constantFrom("session.execution.succeeded", "session.execution.failed") }),
  fc.record({ status: fc.constant("completed") }),
  fc.constant(null),
);

test("theorem 4: an operator interrupt never produces a nudge", () => {
  // A human interrupt is control taken, not work abandoned. The standing order
  // tells the model never to wait for the operator; answering an interrupt with
  // it would override the one person it names. Measured live on omp 18.14.2
  // (2026-11-01, session 01a1958e): the nudge went out 24 ms after the operator
  // stopped the turn.
  fc.assert(
    fc.property(
      interruptArb,
      milestoneArb,
      obligationArb,
      fc.boolean(),
      fc.boolean(),
      fc.constantFrom("plan", "manager"),
      (record, view, obligations, handedOff, askedInTurn, role) => {
        const ledger = ledgerView(view.kind, view.milestone, obligations, { tenure: 0 });
        assert.equal(operatorInterruptOf(record), true, "the record really names an operator interrupt");
        const decision = evaluateTurnExit({ role, ledger, handedOff, askedInTurn, interrupted: record });
        assert.equal(decision.stopAllowed, true);
        assert.equal(decision.reason, STOP_REASONS.OPERATOR_INTERRUPT);
        assert.equal(decision.nudge, null);
        // The shorthand spellings are the same fact: a boolean, or the reason
        // string a caller already read from the host.
        assert.equal(evaluateTurnExit({ role, ledger, interrupted: true }).reason, STOP_REASONS.OPERATOR_INTERRUPT);
        assert.equal(evaluateTurnExit({ role, ledger, interrupted: "Interrupted by user" }).nudge, null);
      },
    ),
    { ...fcRuns(300) },
  );
});

// A view that is below the exit: every milestone but the last, at tenure 0,
// with no hand-off. `verifiedCompletion` needs the last milestone AND a tenure
// after the first, so these views can only reach the nudge.
const belowExitArb = fc
  .constantFrom(...PLAN_MILESTONES.slice(0, -1).map((milestone) => ({ kind: "plan", milestone })))
  .chain((view) =>
    fc.constantFrom(...MANAGER_MILESTONES.slice(0, -1)).map((milestone) => [
      view,
      { kind: "manager", milestone },
    ]),
  )
  .chain((pair) => fc.constantFrom(...pair));

test("theorem 5: a natural stop below the exit always produces a nudge", () => {
  // The other half, drawn from the same facts: every way a host can record a
  // turn that ended WITHOUT the operator is judged by the rule, and below the
  // exit that rule is a nudge — one that names the open obligations.
  fc.assert(
    fc.property(
      naturalArb,
      belowExitArb,
      obligationArb,
      fc.constantFrom("plan", "manager"),
      (record, view, obligations, role) => {
        const ledger = ledgerView(view.kind, view.milestone, obligations, { tenure: 0 });
        // Below the exit: neither an S1 ask nor verified completion is reachable
        // here, because the tenure is 0, nothing was handed off and nothing was
        // asked.
        const decision = evaluateTurnExit({ role, ledger, handedOff: false, askedInTurn: false, interrupted: record });
        assert.equal(operatorInterruptOf(record), false, "the record does not name an interrupt");
        assert.equal(decision.stopAllowed, false);
        assert.equal(decision.reason, STOP_REASONS.NUDGE);
        assert.ok(decision.nudge.includes(STANDING_ORDER));
        for (const { key } of obligations) assert.ok(decision.nudge.includes(key), `nudge names ${key}`);
      },
    ),
    { ...fcRuns(300) },
  );
});

test("theorem 2: the nudge is a pure function of role and ledger", () => {
  fc.assert(
    fc.property(milestoneArb, obligationArb, (view, obligations) => {
      const ledger = ledgerView(view.kind, view.milestone, obligations);
      assert.equal(buildNudgeMessage({ role: "manager", ledger }), buildNudgeMessage({ role: "manager", ledger }));
      const decision = evaluateTurnExit({ role: "manager", ledger });
      // A state that reached the exit has no nudge at all; every other state
      // answers with exactly the message the builder produces for it.
      assert.equal(
        decision.nudge,
        decision.stopAllowed ? null : buildNudgeMessage({ role: "manager", ledger }),
      );
    }),
    { ...fcRuns(100) },
  );
});

test("theorem 3: verified completion and the S1 ask are the only stops", () => {
  fc.assert(
    fc.property(milestoneArb, obligationArb, (view, obligations) => {
      const ledger = ledgerView(view.kind, view.milestone, obligations, {
        // A first tenure never reaches 待执行; the walk to it opens tenure 1 at
        // least (根 §2.4 第 3–5 步).
        tenure: view.kind === "plan" && view.milestone === PLAN_MILESTONES.at(-1) ? 1 : 0,
      });
      // Plan reaches the exit only after the hand-off.
      const handedOff = view.kind === "plan";
      const decision = evaluateTurnExit({ role: view.kind, ledger, handedOff });
      const completion = verifiedCompletion(ledger, { handedOff });
      assert.equal(decision.stopAllowed, completion);
      if (completion) assert.equal(decision.reason, STOP_REASONS.VERIFIED_COMPLETION);
      else assert.equal(decision.stopAllowed, false);
    }),
    { ...fcRuns(200) },
  );

  // A verified Plan at 待执行 that has NOT handed off still owes the hand-off.
  const atReady = ledgerView("plan", PLAN_MILESTONES[PLAN_MILESTONES.length - 1], [], { tenure: 1 });
  assert.equal(verifiedCompletion(atReady, { handedOff: false }), false);
  assert.equal(verifiedCompletion(atReady, { handedOff: true }), true);
  // A first-tenure Plan at 待执行 verified nothing — and it is unreachable, so
  // the predicate refuses it whether or not a hand-off was recorded.
  const firstTenure = ledgerView("plan", PLAN_MILESTONES[PLAN_MILESTONES.length - 1], [], { tenure: 0 });
  assert.equal(verifiedCompletion(firstTenure, { handedOff: true }), false);
  // Manager `done` is the exit; `execute` and `close` are not.
  for (const milestone of MANAGER_MILESTONES.slice(0, -1)) {
    assert.equal(verifiedCompletion(ledgerView("manager", milestone, []), { handedOff: true }), false);
  }
  assert.equal(verifiedCompletion(ledgerView("manager", MANAGER_MILESTONES.at(-1), []), {}), true);
});

test("a worker's turn ending is its report, and an in-flight turn has not stopped", () => {
  fc.assert(
    fc.property(milestoneArb, obligationArb, fc.constantFrom("coder", "devops"), (view, obligations, role) => {
      const decision = evaluateTurnExit({ role, ledger: ledgerView(view.kind, view.milestone, obligations) });
      assert.equal(decision.stopAllowed, true);
      assert.equal(decision.reason, STOP_REASONS.NOT_USER_FACING);
      assert.equal(decision.nudge, null);
    }),
    { ...fcRuns(100) },
  );
  // An unknown or missing role is NOT a worker: failing open there would
  // silently disable the nudge for a top-level agent.
  for (const role of ["worker", "Plan", undefined]) {
    const decision = evaluateTurnExit({ role, ledger: ledgerView("manager", MANAGER_MILESTONES[0], []) });
    assert.equal(decision.stopAllowed, false, String(role));
    assert.equal(decision.reason, STOP_REASONS.NUDGE, String(role));
  }
  const busy = evaluateTurnExit({
    role: "manager",
    ledger: ledgerView("manager", MANAGER_MILESTONES[0], []),
    hasInFlightToolCalls: true,
  });
  assert.equal(busy.stopAllowed, true);
  assert.equal(busy.reason, STOP_REASONS.IN_FLIGHT);
  assert.equal(busy.nudge, null);
});

test("an exit with open obligations is a nudge, not a completion", () => {
  const owed = [{ key: "open", value: "still working" }];
  const done = ledgerView("manager", MANAGER_MILESTONES.at(-1), owed);
  assert.equal(verifiedCompletion(done, {}), false);
  const decision = evaluateTurnExit({ role: "manager", ledger: done });
  assert.equal(decision.stopAllowed, false);
  assert.equal(decision.reason, STOP_REASONS.NUDGE);
  assert.ok(decision.nudge.includes("open"));

  const ready = ledgerView("plan", PLAN_MILESTONES.at(-1), owed, { tenure: 1 });
  assert.equal(verifiedCompletion(ready, { handedOff: true }), false);
  assert.equal(evaluateTurnExit({ role: "plan", ledger: ready, handedOff: true }).stopAllowed, false);
});

test("an internal abort type is not an operator interrupt", () => {
  assert.equal(operatorInterruptOf({ type: "compaction.aborted" }), false);
  assert.equal(operatorInterruptOf({ type: "session.execution.aborted" }), false);
  // The shapes are read conjunctively: a record carrying an internal stop
  // reason beside the operator's cancelled status still names the operator.
  assert.equal(operatorInterruptOf({ stopReason: "aborted", status: "cancelled" }), true);
});

test("the first empty review asks the operator in Plan S1 only", () => {
  // Plan, first tenure, empty review, S1: the one system question.
  const plan = new ReviewLedger({ kind: "plan" });
  const asked = plan.review([]);
  assert.equal(asked.kind, REVIEW_KINDS.ASK_USER);
  assert.equal(asked.question, "我需要干什么？");
  assert.equal(asked.interrupt, true);
  assert.equal(plan.milestone, PLAN_MILESTONES[0], "the question does not advance the milestone");

  // Manager, first tenure, empty review: no question. It is an independent
  // verification (`confirm`), and an unattended Manager never waits for a
  // human. The milestone itself does not move here — the first tenure's own
  // empty review is its verification, and the relay that follows is what the
  // next empty review triggers.
  const manager = new ReviewLedger({ kind: "manager" });
  const confirmed = manager.review([]);
  assert.notEqual(confirmed.kind, REVIEW_KINDS.ASK_USER);
  assert.equal(confirmed.kind, REVIEW_KINDS.CONFIRM);
  assert.equal(manager.milestone, MANAGER_MILESTONES[0]);
  // The next empty review is the relay: retirement, barrier and a successor.
  assert.equal(manager.review([]).kind, REVIEW_KINDS.RELAY);
});

test("a nudge lists the live ledger's obligations, not a copy made earlier", () => {
  const ledger = new ReviewLedger({ kind: "manager" });
  ledger.apply([
    { key: "retry-timeout", value: "make the retry back off exponentially" },
    { key: "docs", value: "update docs/API.md" },
  ]);
  const nudge = evaluateTurnExit({ role: "manager", ledger }).nudge;
  assert.ok(nudge.includes("retry-timeout"));
  assert.ok(nudge.includes("make the retry back off exponentially"));
  assert.ok(nudge.includes("docs"));
  assert.ok(nudge.includes("update docs/API.md"));
  assert.ok(nudge.includes(STANDING_ORDER));
  assert.ok(!nudge.includes('{"'));
});
