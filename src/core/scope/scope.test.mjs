// Checks for `src/core/scope` (spec §6.2). Run with:
//   node --test "src/core/scope/*.test.mjs"
//
// Property tests cover the invariant-shaped claims (annotation totality, one
// settle per turn, budget monotonicity, projection totality, cold-start
// fidelity). Example tests pin the literal cases the spec names.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import {
  SCOPE_CODES,
  SCOPE_KINDS,
  Admission,
  Retention,
  ScopeBook,
  Scope,
  ScopeTable,
  ScopeHooks,
  parseAnnotation,
  readColdState,
  projectRequest,
  DEFAULT_PINNED_MEMBERS,
  ITEM,
  resetRecord,
  budgetRecord,
  keepRecord,
  removeRecord,
  coldStartNotice,
  isScopeRefusal,
} from "./index.mjs";
import { fcRuns } from "../test-runs.mjs";

const SURFACE_MEMBERS = Object.freeze({ "js-review": Object.freeze(["cat", "stat", "ls", "where", "whereAll"]) });

const reset = (goal = "finish the feature", budget = 3) => ({
  kind: SCOPE_KINDS.RESET,
  newScopeGoal: goal,
  newScopeRetentionBudget: budget,
});

const keep = { kind: SCOPE_KINDS.KEEP };
const meToo = { kind: SCOPE_KINDS.METOO };
const remove = (replacement = "lesson learned") => ({
  kind: SCOPE_KINDS.REMOVE,
  replacement,
});

const memoryStore = () => {
  const written = [];
  return {
    written,
    async append(records) {
      for (const entry of records) written.push(entry);
    },
  };
};

const settledBook = (budget = 3) => {
  const book = new ScopeBook();
  book.settle("boot", parseAnnotation(reset("boot", budget)));
  return book;
};

// ---------------------------------------------------------------- annotation

test("annotation: the four kinds parse into frozen records", () => {
  assert.deepEqual(parseAnnotation(reset()), {
    kind: "resetScope",
    goal: "finish the feature",
    budget: 3,
  });
  assert.deepEqual(parseAnnotation(keep), { kind: "keepLast" });
  assert.deepEqual(parseAnnotation(remove()), { kind: "removeLast", replacement: "lesson learned" });
  assert.deepEqual(parseAnnotation(meToo), { kind: "meToo" });
  assert.ok(Object.isFrozen(parseAnnotation(reset())));
});

test("annotation: resetScope requires a non-blank goal and a positive integer budget", () => {
  for (const raw of [
    { kind: "resetScope", newScopeGoal: "", newScopeRetentionBudget: 1 },
    { kind: "resetScope", newScopeGoal: "  \n ", newScopeRetentionBudget: 1 },
    { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 0 },
    { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: -1 },
    { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 1.5 },
    { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: "3" },
    { kind: "resetScope", newScopeGoal: "g" },
  ]) {
    const parsed = parseAnnotation(raw);
    assert.equal(parsed.ok, false);
    assert.equal(parsed.code, SCOPE_CODES.SCOPE_FIELD_INVALID);
  }
  // No upper bound on the budget (spec §6.2).
  assert.equal(parseAnnotation(reset("g", 10 ** 9)).budget, 10 ** 9);
});

test("annotation: removeLast requires a non-blank replacement", () => {
  for (const raw of [
    { kind: "removeLast" },
    { kind: "removeLast", replacement: "" },
    { kind: "removeLast", replacement: " \t" },
    { kind: "removeLast", replacement: 7 },
  ]) {
    const parsed = parseAnnotation(raw);
    assert.equal(parsed.ok, false);
    assert.equal(parsed.code, SCOPE_CODES.SCOPE_FIELD_INVALID);
  }
});

test("annotation: the remaining fields are not accepted, per kind", () => {
  for (const raw of [
    { ...reset(), extra: 1 },
    { ...keep, newScopeGoal: "x" },
    { ...meToo, replacement: "x" },
    { ...remove(), newScopeRetentionBudget: 2 },
  ]) {
    const parsed = parseAnnotation(raw);
    assert.equal(parsed.ok, false);
    assert.equal(parsed.code, SCOPE_CODES.SCOPE_FIELD_UNEXPECTED);
  }
});

test("annotation: absence and unknown kinds are refused", () => {
  assert.equal(parseAnnotation(undefined).code, SCOPE_CODES.SCOPE_ANNOTATION_MISSING);
  assert.equal(parseAnnotation(null).code, SCOPE_CODES.SCOPE_ANNOTATION_MISSING);
  assert.equal(parseAnnotation("keepLast").code, SCOPE_CODES.SCOPE_ANNOTATION_MISSING);
  assert.equal(parseAnnotation([]).code, SCOPE_CODES.SCOPE_ANNOTATION_MISSING);
  assert.equal(parseAnnotation({ kind: "keep" }).code, SCOPE_CODES.SCOPE_KIND_INVALID);
});

test("annotation: parsing is total over arbitrary JSON values", () => {
  fc.assert(
    fc.property(fc.anything(), (value) => {
      const parsed = parseAnnotation(value);
      assert.ok(parsed.ok === true || isScopeRefusal(parsed));
    }),
    { ...fcRuns(300) },
  );
});

// ---------------------------------------------------------------- retention

test("retention: only keepLast spends one unit", async () => {
  const scope = new Scope({ sessionId: "s" });
  assert.equal((await scope.call({ member: "write", annotation: reset("g", 2), turnId: 1 })).remaining, 2);
  assert.equal((await scope.call({ member: "write", annotation: keep, turnId: 2 })).remaining, 1);
  assert.equal((await scope.call({ member: "write", annotation: remove(), turnId: 3 })).remaining, 1);
  const staged = await scope.call({ member: "write", annotation: meToo, turnId: 4 });
  assert.equal(staged.outcome, "staged");
  assert.equal(scope.retention.remaining, 1);
  assert.equal(scope.spinCount, 1);
});

test("retention: any tool may carry resetScope (spec §6.2)", async () => {
  const scope = new Scope({ sessionId: "s" });
  const settled = await scope.call({ member: "write", annotation: reset("g", 3), turnId: 1 });
  assert.equal(settled.outcome, "settled");
  assert.equal(settled.kind, SCOPE_KINDS.RESET);
  assert.equal(scope.retention.remaining, 3);
  assert.equal(scope.budgetKnown, true);
});

test("retention: a spent budget admits only resetScope for non-exempt tools", async () => {
  const scope = new Scope({ sessionId: "s" });
  await scope.call({ member: "write", annotation: reset("g", 1), turnId: 1 });
  await scope.call({ member: "write", annotation: keep, turnId: 2 });
  assert.equal(scope.retention.spent, true);

  const refused = await scope.call({ member: "write", annotation: keep, turnId: 3 });
  assert.equal(refused.code, SCOPE_CODES.SCOPE_BUDGET_SPENT);

  const removed = await scope.call({ member: "write", annotation: remove(), turnId: 4 });
  assert.equal(removed.code, SCOPE_CODES.SCOPE_BUDGET_SPENT);

  // Exempt members answer without spending and without a reset.
  const exempt = await scope.call({ member: "negotiate", annotation: undefined, turnId: 5 });
  assert.equal(exempt.ok, true);
  assert.equal(exempt.outcome, "exempt");

  const resetOk = await scope.call({ member: "write", annotation: reset("next", 2), turnId: 6 });
  assert.equal(resetOk.outcome, "settled");
  assert.equal(scope.retention.remaining, 2);
});

test("retention: a missing annotation refuses a non-exempt tool and changes nothing", async () => {
  const scope = new Scope({ sessionId: "s" });
  await scope.call({ member: "write", annotation: reset("g", 2), turnId: 1 });
  const before = JSON.stringify(scope.retention);
  const refused = await scope.call({ member: "write", annotation: undefined, turnId: 2 });
  assert.equal(refused.code, SCOPE_CODES.SCOPE_ANNOTATION_MISSING);
  assert.equal(JSON.stringify(scope.retention), before);
});

// ------------------------------------------------------------------- turn

test("turn: a meToo first is staged, then the true annotation settles once", () => {
  const book = settledBook(3);
  book.openTurn("t");
  assert.equal(book.settle("t", parseAnnotation(meToo)).outcome, "staged");
  assert.equal(book.turnStaged, true);
  const settled = book.settle("t", parseAnnotation(keep));
  assert.equal(settled.outcome, "settled");
  assert.equal(book.turnStaged, false);
  assert.equal(book.retention.remaining, 2);
  assert.equal(book.settle("t", parseAnnotation(keep)).outcome, "confirmed");
  assert.equal(book.retention.remaining, 2);
});

test("turn: the first true annotation settles the turn; later ones confirm", () => {
  const book = settledBook(2);
  book.openTurn("t1");
  assert.equal(book.settle("t1", parseAnnotation(reset("g", 2))).remaining, 2);
  const confirmedKeep = book.settle("t1", parseAnnotation(keep));
  assert.equal(confirmedKeep.outcome, "confirmed");
  assert.equal(book.retention.remaining, 2);
  assert.equal(book.settle("t1", parseAnnotation(meToo)).outcome, "confirmed");
  assert.equal(book.retention.remaining, 2);
  assert.equal(book.settlements, 2);
});

test("turn: a second resetScope in one turn confirms and opens no new boundary", async () => {
  const scope = new Scope({ sessionId: "s" });
  const first = await scope.call({ member: "write", annotation: reset("one", 2), turnId: "t", callId: "c1" });
  assert.equal(first.outcome, "settled");
  const second = await scope.call({ member: "write", annotation: reset("two", 9), turnId: "t", callId: "c2" });
  assert.equal(second.outcome, "confirmed");
  assert.equal(scope.retention.remaining, 2);
  assert.equal(scope.resetBeats.length, 1);
  assert.equal(scope.resetBeats[0].callId, "c1");
});

test("turn: a refused resetScope opens no boundary", async () => {
  const scope = new Scope({ sessionId: "s" });
  const refused = await scope.call({
    member: "write",
    annotation: { kind: SCOPE_KINDS.RESET, newScopeGoal: "", newScopeRetentionBudget: 2 },
    turnId: "t",
    callId: "c1",
  });
  assert.equal(refused.ok, false);
  assert.equal(scope.resetBeats.length, 0);
});

test("turn: a turn that only ever saw meToo is refused when it closes", () => {
  const book = settledBook(2);
  book.openTurn("t1");
  assert.equal(book.settle("t1", parseAnnotation(meToo)).outcome, "staged");
  const refused = book.closeTurn();
  assert.equal(refused.code, SCOPE_CODES.SCOPE_METOO_ONLY);
  assert.equal(book.retention.remaining, 2);
  // A settled turn closes quietly.
  book.openTurn("t2");
  book.settle("t2", parseAnnotation(keep));
  assert.equal(book.closeTurn(), null);
});

test("turn: meToo refuses while uninitialized and while spent", () => {
  const cold = new ScopeBook();
  cold.openTurn("t1");
  assert.equal(cold.settle("t1", parseAnnotation(meToo)).code, SCOPE_CODES.SCOPE_UNINITIALIZED);

  const spent = new ScopeBook({ retention: new Retention({ established: true, remaining: 0 }) });
  spent.openTurn("t1");
  assert.equal(spent.settle("t1", parseAnnotation(meToo)).code, SCOPE_CODES.SCOPE_BUDGET_SPENT);
});

test("turn: a true annotation is refused while uninitialized", () => {
  const book = new ScopeBook();
  book.openTurn("t1");
  const refused = book.settle("t1", parseAnnotation(keep));
  assert.equal(refused.code, SCOPE_CODES.SCOPE_UNINITIALIZED);
  // A beat the book cannot book leaves the turn open and unbooked, so the reset
  // the notice asks for still settles this same turn (spec §6.2).
  assert.equal(book.settlements, 0);
  assert.equal(book.turnSettled, false);
  assert.equal(book.retention.established, false);
  const resetSettled = book.settle("t1", parseAnnotation(reset("g", 2)));
  assert.equal(resetSettled.outcome, "settled");
  assert.equal(book.settlements, 1);
});

test("turn: an invalid annotation leaves the turn open", () => {
  const book = new ScopeBook({ retention: new Retention({ established: true, remaining: 2 }) });
  book.openTurn("t1");
  // The book is fed parsed records; a hand-built invalid one must not close
  // the turn (spec §6.2: a failed validation changes no state).
  const refused = book.settle("t1", { kind: SCOPE_KINDS.RESET, goal: "", budget: 0 });
  assert.equal(refused.ok, false);
  assert.equal(book.turnSettled, false);
  assert.equal(book.settlements, 0);
  assert.equal(book.settle("t1", parseAnnotation(keep)).outcome, "settled");
});

test("turn: at most one unit is spent per turn, whatever the annotation mix", () => {
  const kinds = fc.array(
    fc.oneof(
      fc.constant(reset("g", 1)),
      fc.constant(keep),
      fc.constant(remove()),
      fc.constant(meToo),
    ),
    { minLength: 1, maxLength: 8 },
  );
  fc.assert(
    fc.property(kinds, (raws) => {
      const book = settledBook(1);
      book.openTurn("t");
      let spentByBook = 0;
      let settledOnce = false;
      for (const raw of raws) {
        const result = book.settle("t", parseAnnotation(raw));
        if (result.ok && result.outcome === "settled") {
          assert.equal(settledOnce, false, "a turn settles at most once");
          settledOnce = true;
          if (result.kind === "keepLast") spentByBook += 1;
        }
      }
      assert.ok(spentByBook <= 1);
    }),
    { ...fcRuns(200) },
  );
});

// ---------------------------------------------------------------- admission

test("admission: exempt members need no annotation and are listed", () => {
  const admission = new Admission();
  assert.deepEqual(admission.exemptMembers, ["negotiate", "askPreviousScope"]);
  const result = admission.check({
    member: "negotiate",
    annotation: undefined,
    retention: { established: true, spent: true, remaining: 0 },
  });
  assert.equal(result.ok, true);
  assert.equal(result.exempt, true);
});

// --------------------------------------------------------------- persistence

test("records: a reset is written with its established budget and callId", async () => {
  const store = memoryStore();
  const scope = new Scope({ sessionId: "s", store });
  await scope.call({ member: "write", annotation: reset("g", 4), turnId: 1, callId: "c1" });
  assert.deepEqual(
    store.written.map((entry) => entry.type),
    ["reset", "budget"],
  );
  assert.equal(store.written[0].callId, "c1");
  assert.equal(store.written[1].remaining, 4);
});

test("records: a staged meToo is provisional and is not persisted", async () => {
  const store = memoryStore();
  const scope = new Scope({ sessionId: "s", store });
  await scope.call({ member: "write", annotation: reset("g", 2), turnId: 1 });
  store.written.length = 0;
  const staged = await scope.call({ member: "write", annotation: meToo, turnId: 2 });
  assert.equal(staged.outcome, "staged");
  assert.deepEqual(store.written, []);
  const settled = await scope.call({ member: "write", annotation: keep, turnId: 2 });
  assert.equal(settled.outcome, "settled");
  assert.deepEqual(
    store.written.map((entry) => entry.type),
    ["keep"],
  );
});

test("records: an erase records the erasing callId, and a cold start replays it", async () => {
  const store = memoryStore();
  const scope = new Scope({ sessionId: "s", store });
  await scope.call({ member: "write", annotation: reset("g", 2), turnId: 1, callId: "c0" });
  await scope.call({
    member: "write",
    annotation: remove("the write was premature"),
    turnId: 2,
    callId: "c2",
  });
  const eraseRecord = store.written.find((entry) => entry.type === "remove");
  assert.equal(eraseRecord.callId, "c2");
  assert.equal(eraseRecord.spin, 1);

  const cold = Scope.coldStart({ sessionId: "s", records: store.written, store: memoryStore() });
  const items = [
    { kind: ITEM.MESSAGE, role: "operator", text: "start" },
    { kind: ITEM.TOOL_CALL, member: "write", callId: "c0" },
    { kind: ITEM.TOOL_RESULT, member: "write", callId: "c0" },
    { kind: ITEM.MESSAGE, role: "assistant", text: "wrote" },
    { kind: ITEM.TOOL_CALL, member: "cat", callId: "c2", scope: remove("the write was premature") },
    { kind: ITEM.TOOL_RESULT, member: "write", callId: "c2" },
  ];
  assert.deepEqual(cold.project(items), scope.project(items));
  assert.equal(cold.project(items)[3].text, "the write was premature\nSpin count: 1");
});

test("records: a failed append is reported, not repaired", async () => {
  const scope = new Scope({
    sessionId: "s",
    store: {
      async append() {
        throw new Error("disk full");
      },
    },
  });
  const result = await scope.call({ member: "write", annotation: reset("g", 1), turnId: 1 });
  assert.equal(result.ok, false);
  assert.equal(result.code, SCOPE_CODES.SCOPE_RECORD_FAILED);
  assert.equal(result.persisted, false);
});

test("records: malformed lines are errors, never silent skips", () => {
  for (const records of [
    [{}],
    [{ v: 99, type: "reset" }],
    [{ v: 1, type: "nope" }],
    [{ v: 1, type: "reset", goal: "", budget: 1 }],
    [{ v: 1, type: "reset", goal: "g", budget: 0 }],
    [{ v: 1, type: "budget", remaining: -1 }],
    [{ v: 1, type: "remove", replacement: "r", spin: 1 }],
    [{ v: 1, type: "remove", callId: "c1", replacement: "r", spin: 0 }],
  ]) {
    assert.throws(() => readColdState(records), (error) => error.name === "ScopeArgumentError");
  }
});

// ------------------------------------------------------------------- cold

test("cold start: a persisted reset does not prove the current budget", () => {
  const records = [resetRecord({ turnId: 1, callId: "c1", goal: "old goal", budget: 5, remaining: 4 })];
  const scope = Scope.coldStart({ sessionId: "s", records });
  assert.equal(scope.budgetKnown, false);
  const notice = scope.notice();
  assert.equal(notice.kind, "scopeColdStart");
  assert.equal(notice.reason, SCOPE_CODES.SCOPE_UNINITIALIZED);
  assert.equal(notice.previousResets, 1);
});

test("cold start: an uninitialized Scope refuses non-reset work and asks for resetScope", async () => {
  const scope = Scope.coldStart({ sessionId: "s", records: [] });
  const refused = await scope.call({ member: "write", annotation: keep, turnId: 1 });
  assert.equal(refused.code, SCOPE_CODES.SCOPE_UNINITIALIZED);
  const admitted = await scope.call({ member: "write", annotation: reset("new", 2), turnId: 2 });
  assert.equal(admitted.ok, true);
  assert.equal(scope.notice(), null);
  assert.equal(scope.budgetKnown, true);
});

test("cold start: a budget record establishes the budget and the reset beat survives", () => {
  const records = [
    resetRecord({ turnId: 1, callId: "c1", goal: "g", budget: 5, remaining: 5 }),
    budgetRecord({ remaining: 2 }),
  ];
  const scope = Scope.coldStart({ sessionId: "s", records });
  assert.equal(scope.budgetKnown, true);
  assert.equal(scope.retention.remaining, 2);
  assert.equal(scope.notice(), null);
  assert.equal(scope.resetBeats[0].callId, "c1");
});

test("cold start: notice is null once the budget is known", () => {
  assert.equal(coldStartNotice({ resets: [], budget: { remaining: 1 }, mustReset: false }), null);
});

test("cold start: a budget record from an older Scope proves nothing", () => {
  // A budget written before the newest reset belongs to the Scope it was
  // written for; the current Scope has no confirmed budget yet.
  const records = [
    budgetRecord({ remaining: 3 }),
    resetRecord({ turnId: 1, callId: "c1", goal: "g", budget: 5, remaining: 5 }),
  ];
  const cold = readColdState(records);
  assert.equal(cold.budgetKnown, false);
  const scope = Scope.coldStart({ sessionId: "s", records });
  assert.equal(scope.budgetKnown, false);
  assert.equal(scope.notice().kind, "scopeColdStart");
});

test("cold start: admitting a keep or remove before the budget exists is refused up front", async () => {
  const scope = Scope.coldStart({ sessionId: "s", records: [] });
  for (const annotation of [keep, remove("r")]) {
    const refused = await scope.call({ member: "write", annotation, turnId: 1 });
    assert.equal(refused.ok, false);
    assert.equal(refused.code, SCOPE_CODES.SCOPE_UNINITIALIZED);
  }
  // Nothing was booked and the turn is still open for the reset that matters.
  assert.equal(scope.resetBeats.length, 0);
  const admitted = await scope.call({ member: "write", annotation: reset("g", 2), turnId: 1 });
  assert.equal(admitted.outcome, "settled");
});

test("cold start: a reset that settled here confirms the budget", async () => {
  const scope = Scope.coldStart({ sessionId: "s", records: [] });
  assert.equal(scope.notice().kind, "scopeColdStart");
  await scope.call({ member: "write", annotation: reset("g", 2), turnId: 1 });
  assert.equal(scope.notice(), null);
  assert.equal(scope.budgetKnown, true);
});

test("records: a failed append changes no fact, and a retry books the beat once", async () => {
  const written = [];
  let fail = false;
  const store = {
    async append(records) {
      if (fail) throw new Error("disk full");
      for (const entry of records) written.push(entry);
    },
  };
  const scope = new Scope({ sessionId: "s", store });
  await scope.call({ member: "write", annotation: reset("g", 2), turnId: "t0", callId: "c0" });
  assert.equal(written.length, 2, "the reset and its budget are persisted");
  const retentionBefore = JSON.stringify(scope.retention);
  const beatsBefore = JSON.stringify(scope.resetBeats);
  const erasedBefore = JSON.stringify(scope.erased);
  const writtenBefore = written.length;

  fail = true;
  const failed = await scope.call({
    member: "write",
    annotation: keep,
    turnId: "t1",
    callId: "c1",
  });
  assert.equal(failed.code, SCOPE_CODES.SCOPE_RECORD_FAILED);
  assert.equal(failed.persisted, false);
  // Nothing moved: the budget, the boundary and the erase list are the ones
  // the persisted records describe, and no record was appended.
  assert.equal(JSON.stringify(scope.retention), retentionBefore);
  assert.equal(JSON.stringify(scope.resetBeats), beatsBefore);
  assert.equal(JSON.stringify(scope.erased), erasedBefore);
  assert.equal(written.length, writtenBefore);
  // The failed turn was never opened on the live book, so the live book still
  // holds the last committed turn and a retry is a fresh settlement.
  assert.equal(scope.book.turnSettled, true);
  assert.equal(scope.book.turnId, "t0");

  // The retry settles once and spends once — not twice.
  fail = false;
  const retried = await scope.call({
    member: "write",
    annotation: keep,
    turnId: "t1",
    callId: "c1",
  });
  assert.equal(retried.outcome, "settled");
  assert.equal(retried.persisted, true);
  assert.equal(retried.remaining, 1);
  assert.equal(scope.retention.remaining, 1);
  assert.deepEqual(
    written.slice(writtenBefore).map((entry) => entry.type),
    ["keep"],
  );
});

test("records: a failed erase never rewrites the request copy", async () => {
  const store = memoryStore();
  const scope = new Scope({ sessionId: "s", store });
  await scope.call({ member: "write", annotation: reset("g", 2), turnId: "t0", callId: "c0" });

  const failing = new Scope({
    sessionId: "s",
    store: {
      async append() {
        throw new Error("disk full");
      },
    },
  });
  // Establish a budget first: a removeLast is refused before the budget exists.
  const seeded = new Scope({ sessionId: "s", store: memoryStore() });
  await seeded.call({ member: "write", annotation: reset("g", 2), turnId: "t0", callId: "c0" });
  failing.book.adopt(seeded.book.clone());
  const failed = await failing.call({
    member: "write",
    annotation: remove("r"),
    turnId: "t1",
    callId: "c2",
  });
  assert.equal(failed.code, SCOPE_CODES.SCOPE_RECORD_FAILED);
  assert.deepEqual(failing.erased, []);
  const items = [
    operator("a"),
    call("cat", "c1"),
    result("cat", "c1"),
    call("cat", "c2", { scope: remove("r") }),
    result("cat", "c2"),
  ];
  // The erased beat is still an ordinary old beat: no replacement message.
  assert.equal(failing.project(items).some((item) => "erasedCallId" in item), false);
});

test("call: a missing model turn is refused, never guessed from the callId", async () => {
  const scope = new Scope({ sessionId: "s" });
  await scope.call({ member: "write", annotation: reset("g", 3), turnId: "t1", callId: "c1" });
  // Booking is per model turn; a callId is not a turn, so no booking happens.
  const refused = await scope.call({ member: "write", annotation: keep, callId: "c2" });
  assert.equal(refused.ok, false);
  assert.equal(refused.code, SCOPE_CODES.SCOPE_TURN_MISSING);
  assert.equal(scope.retention.remaining, 3);
  // Two calls that share a turn settle once and confirm once (spec §6.2).
  const first = await scope.call({ member: "write", annotation: keep, turnId: "t2", callId: "c2" });
  const second = await scope.call({ member: "write", annotation: keep, turnId: "t2", callId: "c3" });
  assert.equal(first.outcome, "settled");
  assert.equal(second.outcome, "confirmed");
  assert.equal(scope.retention.remaining, 2);
});

// -------------------------------------------------------------- projection

const operator = (text) => ({ kind: ITEM.MESSAGE, role: "operator", text });
const prose = (text) => ({ kind: ITEM.MESSAGE, role: "assistant", text });
const call = (member, callId, extra = {}) => ({ kind: ITEM.TOOL_CALL, member, callId, ...extra });
const result = (member, callId, extra = {}) => ({ kind: ITEM.TOOL_RESULT, member, callId, ...extra });
const thinking = () => ({ kind: ITEM.THINKING });
const provider = (text) => ({ kind: ITEM.PROVIDER, text });

const sample = () => [
  operator("do the thing"),
  thinking(),
  call("cat", "c1"),
  result("cat", "c1"),
  prose("looked at the file"),
  call("assume", "c2"),
  result("assume", "c2"),
  call("negotiate", "c3"),
  result("negotiate", "c3"),
  call("write", "c4", { scope: reset("second", 4) }),
  result("write", "c4", { scope: reset("second", 4) }),
  thinking(),
  call("write", "c5"),
  result("write", "c5"),
  prose("wrote it"),
  provider("raw text"),
];

test("projection: everything from the reset boundary on is kept as it is", () => {
  const items = sample();
  const out = projectRequest(items, { resetCallId: "c4" });
  const at = items.findIndex((item) => item.callId === "c4");
  const first = out.findIndex((item) => item.callId === "c4");
  assert.deepEqual(out.slice(first), items.slice(at));
});

test("projection: the boundary may be any tool carrying resetScope", () => {
  // The settled fact names `c4`, a `write`; no item is called resetScope.
  const out = projectRequest(sample(), { resetCallId: "c4" });
  const at = out.findIndex((item) => item.callId === "c4");
  // The boundary is the `write` call, not a member named resetScope. Before it
  // only messages and pinned members survive (spec §6.2).
  const pinnedMembers = ["assume", "negotiate", "askPreviousScope"];
  assert.ok(at > 0);
  assert.equal(
    out
      .slice(0, at)
      .every(
        (item) =>
          item.kind === ITEM.MESSAGE ||
          (item.kind === ITEM.TOOL_CALL && pinnedMembers.includes(item.member)) ||
          (item.kind === ITEM.TOOL_RESULT && pinnedMembers.includes(item.member)),
      ),
    true,
  );
  assert.equal(out[at + 1].kind, ITEM.TOOL_RESULT);
});

test("projection: before the boundary, old tool beats and thinking are removed", () => {
  const out = projectRequest(sample(), { resetCallId: "c4" });
  const members = out.filter((item) => item.kind === ITEM.TOOL_CALL).map((item) => item.member);
  assert.deepEqual(members, ["assume", "negotiate", "write", "write"]);
  // One thinking after the boundary survives; the two before it do not.
  assert.equal(out.filter((item) => item.kind === ITEM.THINKING).length, 1);
  assert.ok(out.some((item) => item.text === "looked at the file"));
  assert.ok(out.some((item) => item.text === "do the thing"));
});

test("projection: a refused resetScope does not open a boundary", () => {
  const items = [
    operator("a"),
    call("cat", "c1"),
    result("cat", "c1"),
    call("write", "c2", { scope: reset("refused", 3) }),
    result("write", "c2", { scope: reset("refused", 3) }),
  ];
  // The settled fact names a call this copy does not hold: there is no
  // boundary here, so everything is current, including the refused reset.
  const out = projectRequest(items, { resetCallId: "c0" });
  assert.deepEqual(out, items);

  // A refused reset cannot open a boundary through the annotation fallback
  // either, once the settled fact names a call inside the copy.
  const withBoundary = [
    operator("a"),
    call("cat", "c1"),
    result("cat", "c1"),
    call("write", "c0", { scope: reset("settled", 2) }),
    result("write", "c0", { scope: reset("settled", 2) }),
    call("write", "c2", { scope: reset("refused", 3) }),
    result("write", "c2", { scope: reset("refused", 3) }),
  ];
  const bounded = projectRequest(withBoundary, { resetCallId: "c0" });
  // Everything after `c0` is inside the current Scope, so both writes stay;
  // what proves `c0` is the boundary is that the older `cat` beat is gone.
  assert.equal(bounded.some((item) => item.callId === "c1"), false);
  assert.equal(bounded.filter((item) => item.callId === "c0").length, 2);
  assert.equal(bounded.filter((item) => item.callId === "c2").length, 2);
});

test("projection: without a settled fact the newest reset annotation is the boundary", () => {
  const out = projectRequest(sample());
  const at = out.findIndex((item) => item.callId === "c4");
  assert.ok(at >= 0);
  assert.equal(out.slice(at).length, sample().length - 9);
});

test("projection: operator messages, pinned members and provider text survive", () => {
  const out = projectRequest(sample(), { resetCallId: "c4" });
  const calls = out.filter((item) => item.kind === ITEM.TOOL_CALL).map((item) => item.member);
  assert.ok(calls.includes("assume"));
  assert.ok(calls.includes("negotiate"));
  assert.ok(out.some((item) => item.kind === ITEM.PROVIDER && item.text === "raw text"));
  assert.ok(out.some((item) => item.kind === ITEM.MESSAGE && item.role === "operator"));
});

test("projection: with no reset everything is kept", () => {
  const items = [operator("a"), call("cat", "c1"), result("cat", "c1"), thinking()];
  assert.deepEqual(projectRequest(items), items);
});

test("projection: removeLast rewrites the beat before the erasing call", () => {
  const items = [
    operator("a"),
    call("cat", "c1"),
    result("cat", "c1"),
    call("write", "c2", { scope: remove("the write was premature") }),
    result("write", "c2"),
  ];
  const out = projectRequest(items, {
    erase: [{ callId: "c2", replacement: "the write was premature", spin: 1 }],
  });
  // The `cat` beat becomes the replacement message; the erasing call itself is
  // an ordinary beat of the current Scope and stays.
  assert.deepEqual(
    out.map((item) => item.kind),
    [ITEM.MESSAGE, ITEM.MESSAGE, ITEM.TOOL_CALL, ITEM.TOOL_RESULT],
  );
  assert.equal(out[1].text, "the write was premature\nSpin count: 1");
  assert.equal(out[1].erasedCallId, "c2");
});

test("projection: removeLast never rewrites across the reset boundary", () => {
  const items = [
    operator("a"),
    call("cat", "c1"),
    result("cat", "c1"),
    call("write", "c9", { scope: reset("g", 3) }),
    result("write", "c9"),
    call("write", "c2", { scope: remove("r") }),
  ];
  const out = projectRequest(items, {
    resetCallId: "c9",
    erase: [{ callId: "c2", replacement: "r", spin: 1 }],
  });
  // The reset call is the last beat of the old Scope; nothing before it may be
  // rewritten into a message after it.
  assert.equal(out.some((item) => item.erasedCallId === "c2"), false);
});

test("projection: an erase without a callId is never applied by guesswork", () => {
  const items = [operator("a"), call("cat", "c1"), result("cat", "c1"), call("write", "c2")];
  const out = projectRequest(items, { erase: [{ callId: null, replacement: "r", spin: 2 }] });
  assert.equal(out.some((item) => "erasedCallId" in item), false);
  // No erase is applied, and the request holds no boundary, so every item is
  // inside the current Scope and stays.
  assert.deepEqual(out, items);
});

test("projection: the input array is never mutated", () => {
  const items = sample();
  const copy = JSON.parse(JSON.stringify(items));
  projectRequest(items, { erase: [{ callId: "c1", replacement: "r", spin: 1 }] });
  assert.deepEqual(items, copy);
});

test("projection: a call and its result that disagree are refused, not thrown", () => {
  const out = projectRequest(
    [call("cat", "c1"), result("negotiate", "c1"), call("write", "c2", { scope: reset("g", 1) })],
    { resetCallId: "c2" },
  );
  assert.equal(out.ok, false);
  assert.equal(out.code, SCOPE_CODES.SCOPE_FIELD_INVALID);
});

test("projection: unknown items never throw and never reach an old Scope", () => {
  const items = [
    { kind: "custom" },
    call("write", "c1", { scope: reset("g", 1) }),
    { kind: "custom" },
  ];
  const out = projectRequest(items);
  assert.equal(out.length, 2);
  assert.deepEqual(out[0], items[1]);
});

test("projection: totality over generated item lists", () => {
  const item = fc.oneof(
    fc.constant(operator("o")),
    fc.constant(prose("p")),
    fc.constant(thinking()),
    fc.constant(provider("v")),
    fc.record({
      kind: fc.constant(ITEM.TOOL_CALL),
      member: fc.constantFrom("cat", "write", "assume"),
      callId: fc.string(),
    }),
    fc.record({
      kind: fc.constant(ITEM.TOOL_RESULT),
      member: fc.constantFrom("cat", "write", "assume"),
      callId: fc.string(),
    }),
  );
  fc.assert(
    fc.property(fc.array(item, { maxLength: 12 }), (items) => {
      const out = projectRequest(items);
      assert.ok(Array.isArray(out) || isScopeRefusal(out));
    }),
    { ...fcRuns(200) },
  );
});

// ------------------------------------------------------------------ hooks

test("hooks: the entry points agree on one Scope", async () => {
  const hooks = new ScopeHooks();
  hooks.open({ sessionId: "s" });
  const admit = hooks.admit({ sessionId: "s", member: "write", annotation: reset("g", 1) });
  assert.equal(admit.ok, true);
  const settled = await hooks.settle({
    sessionId: "s",
    member: "write",
    annotation: reset("g", 1),
    turnId: "t1",
    callId: "c1",
  });
  assert.equal(settled.ok, true);
  assert.equal(settled.retention.remaining, 1);
  const items = [
    operator("x"),
    call("cat", "c0"),
    result("cat", "c0"),
    call("write", "c1", { scope: reset("g", 1) }),
    result("write", "c1", { scope: reset("g", 1) }),
  ];
  const projected = hooks.project({ sessionId: "s", items });
  assert.equal(projected.ok, true);
  assert.deepEqual(projected.messages, [operator("x"), items[3], items[4]]);
  const snapshot = hooks.ask("negotiate", "s");
  assert.equal(snapshot.goal, "g");
  assert.equal(snapshot.remaining, 1);
  assert.equal(hooks.ask("askPreviousScope", "s").budget, 1);
});

test("hooks: an unknown session is refused, and snapshots are synchronous", () => {
  const hooks = new ScopeHooks();
  assert.equal(hooks.project({ sessionId: "nope", items: [] }).code, SCOPE_CODES.SCOPE_UNINITIALIZED);
  assert.equal(hooks.ask("negotiate", "nope").code, SCOPE_CODES.SCOPE_UNINITIALIZED);
  assert.equal(hooks.ask("other", "nope").code, SCOPE_CODES.SCOPE_MEMBER_UNKNOWN);
});

test("hooks: closeTurn reports a meToo-only turn", async () => {
  const hooks = new ScopeHooks();
  hooks.open({ sessionId: "s" });
  await hooks.settle({ sessionId: "s", member: "write", annotation: reset("g", 2), turnId: "t1" });
  await hooks.settle({ sessionId: "s", member: "write", annotation: meToo, turnId: "t2" });
  assert.equal((await hooks.closeTurn({ sessionId: "s" })).code, SCOPE_CODES.SCOPE_METOO_ONLY);
});

test("hooks: settle refuses a missing turn instead of guessing one", async () => {
  const hooks = new ScopeHooks();
  hooks.open({ sessionId: "s" });
  await hooks.settle({ sessionId: "s", member: "write", annotation: reset("g", 3), turnId: "t1" });
  const refused = await hooks.settle({
    sessionId: "s",
    member: "write",
    annotation: keep,
    callId: "c2",
  });
  assert.equal(refused.ok, false);
  assert.equal(refused.code, SCOPE_CODES.SCOPE_TURN_MISSING);
  assert.equal(hooks.ask("negotiate", "s").remaining, 3);
});

test("table: one process cannot own the same session twice", () => {
  const table = new ScopeTable();
  assert.ok(table.create({ sessionId: "s" }) instanceof Scope);
  assert.equal(table.create({ sessionId: "s" }).code, SCOPE_CODES.SCOPE_OWNER_TAKEN);
  table.delete("s");
  assert.ok(table.create({ sessionId: "s" }) instanceof Scope);
});

test("hooks: negotiation answers synchronously from the newest snapshot", () => {
  const scope = new Scope({ sessionId: "s" });
  const answer = scope.negotiate();
  assert.equal(answer.ok, true);
  assert.equal(answer.mustReset, true);
  assert.equal(typeof answer.remaining, "number");
});

// --------------------------------------------------------------- concurrency

test("concurrency: two calls in one turn settle once and confirm once", async () => {
  const scope = new Scope({ sessionId: "s" });
  await scope.call({ member: "write", annotation: reset("g", 2), turnId: "t0" });
  // Started together, not awaited one by one: without the queue both would
  // clone the same book and both would spend a unit.
  const [first, second] = await Promise.all([
    scope.call({ member: "write", annotation: keep, turnId: "t1", callId: "c1" }),
    scope.call({ member: "write", annotation: meToo, turnId: "t1", callId: "c2" }),
  ]);
  assert.equal(first.outcome, "settled");
  assert.equal(second.outcome, "confirmed");
  assert.equal(scope.retention.remaining, 1);
  assert.equal(scope.inFlight, 0);
});

test("concurrency: a staged meToo survives a concurrent true annotation", async () => {
  const scope = new Scope({ sessionId: "s" });
  await scope.call({ member: "write", annotation: reset("g", 3), turnId: "t0" });
  const staged = scope.call({ member: "write", annotation: meToo, turnId: "t1", callId: "c1" });
  const settled = scope.call({ member: "write", annotation: keep, turnId: "t1", callId: "c2" });
  assert.equal((await staged).outcome, "staged");
  assert.equal((await settled).outcome, "settled");
  // One unit for the turn, and the staging is cleared by the settlement.
  assert.equal(scope.retention.remaining, 2);
  assert.equal((await scope.closeTurn()), null);
});

test("concurrency: independent turns still each book exactly one unit", async () => {
  const scope = new Scope({ sessionId: "s" });
  await scope.call({ member: "write", annotation: reset("g", 4), turnId: "t0" });
  const results = await Promise.all([
    scope.call({ member: "write", annotation: keep, turnId: "t1", callId: "c1" }),
    scope.call({ member: "write", annotation: keep, turnId: "t2", callId: "c2" }),
    scope.call({ member: "write", annotation: keep, turnId: "t3", callId: "c3" }),
  ]);
  assert.deepEqual(
    results.map((result) => result.outcome),
    ["settled", "settled", "settled"],
  );
  assert.equal(scope.retention.remaining, 1);
});

test("concurrency: closeTurn waits for an in-flight settle instead of refusing it", async () => {
  const scope = new Scope({ sessionId: "s" });
  await scope.call({ member: "write", annotation: reset("g", 2), turnId: "t0" });
  const staged = scope.call({ member: "write", annotation: meToo, turnId: "t1", callId: "c1" });
  // The turn has a staged meToo and nothing else yet. Closing now would refuse
  // it; the true annotation is already queued behind it, so the close must wait.
  const settled = scope.call({ member: "write", annotation: keep, turnId: "t1", callId: "c2" });
  const closed = scope.closeTurn();
  assert.equal((await staged).outcome, "staged");
  assert.equal((await settled).outcome, "settled");
  assert.equal(await closed, null);
});

test("concurrency: closeTurn with nothing in flight refuses an all-meToo turn", async () => {
  const scope = new Scope({ sessionId: "s" });
  await scope.call({ member: "write", annotation: reset("g", 2), turnId: "t0" });
  await scope.call({ member: "write", annotation: meToo, turnId: "t1", callId: "c1" });
  const closed = scope.closeTurn();
  // Nothing else is queued, so the judgement is immediate.
  assert.equal(scope.inFlight, 0);
  assert.equal((await closed).code, SCOPE_CODES.SCOPE_METOO_ONLY);
});

test("concurrency: a failing append does not block the queue for the next call", async () => {
  let fail = true;
  const written = [];
  const scope = new Scope({
    sessionId: "s",
    store: {
      async append(records) {
        if (fail) throw new Error("disk full");
        for (const entry of records) written.push(entry);
      },
    },
  });
  const failed = await scope.call({ member: "write", annotation: reset("g", 2), turnId: "t0" });
  assert.equal(failed.code, SCOPE_CODES.SCOPE_RECORD_FAILED);
  fail = false;
  const retried = await scope.call({ member: "write", annotation: reset("g", 2), turnId: "t0" });
  assert.equal(retried.outcome, "settled");
  assert.equal(scope.retention.remaining, 2);
  assert.equal(scope.inFlight, 0);
});

// ------------------------------------------------------------------ budget

test("invariant: remaining never leaves [0, budget] across random calls", async () => {
  const annotations = fc.oneof(
    fc.record({
      kind: fc.constant(SCOPE_KINDS.RESET),
      newScopeGoal: fc.string({ minLength: 1 }).filter((value) => value.trim() !== ""),
      newScopeRetentionBudget: fc.integer({ min: 1, max: 20 }),
    }),
    fc.constant(keep),
    fc.constant(remove("r")),
    fc.constant(meToo),
  );
  await fc.assert(
    fc.asyncProperty(fc.array(annotations, { maxLength: 30 }), async (raws) => {
      const scope = new Scope({ sessionId: "s" });
      let turn = 0;
      for (const raw of raws) {
        turn += 1;
        const before = scope.retention;
        await scope.call({ member: "write", annotation: raw, turnId: turn });
        const after = scope.retention;
        if (after.established) {
          assert.ok(after.remaining >= 0);
          assert.ok(after.remaining <= before.remaining || raw.kind === SCOPE_KINDS.RESET);
        }
      }
    }),
    { ...fcRuns(100) },
  );
});

// ---------------------------------------------------------------------------
// The pinned families a host adds (§6.2, §6.1)
// ---------------------------------------------------------------------------

test("a pinned pair survives any projection, and an unpinned beat does not", () => {
  // The Pair Hint's synthetic pair has the empty tool name; `js-review`'s
  // members are the Manager's evaluation channel. Both are ours, not a beat of
  // any Scope, so a `resetScope` must not trim them — while the model's own
  // tool calls before the boundary must go.
  const PINNED = Object.freeze([...DEFAULT_PINNED_MEMBERS, "", ...SURFACE_MEMBERS["js-review"]]);
  fc.assert(
    fc.property(
      fc.array(fc.nat({ max: 5 }), { minLength: 1, maxLength: 6 }),
      fc.nat({ max: 8 }),
      (before, padding) => {
        const beat = (id) => [
          { kind: "toolCall", member: "js-worker", callId: `w${id}` },
          { kind: "toolResult", member: "js-worker", callId: `w${id}` },
        ];
        const pair = [
          { kind: "toolCall", member: "", callId: "hint" },
          { kind: "toolResult", member: "", callId: "hint" },
        ];
        // A `js-review` read: the member name is what a host pins, and the
        // call and its result must agree or the projection refuses the copy.
        const review = [
          { kind: "toolCall", member: "cat", callId: "rv" },
          { kind: "toolResult", member: "cat", callId: "rv" },
        ];
        // A second Scope opens at the reset: everything before it belongs to the
        // old one, and only pinned families survive from there.
        const items = [
          { kind: "message", role: "operator", text: "go" },
          ...before.flatMap(beat),
          // Both families sit in the OLD Scope, the only region where a pinned
          // name decides anything: everything after the boundary is kept
          // regardless, so a copy's tail would prove nothing.
          ...review,
          ...pair,
          { kind: "toolCall", member: "js-manager", callId: "r0", scope: { kind: SCOPE_KINDS.RESET, newScopeGoal: "g", newScopeRetentionBudget: 2 } },
          { kind: "toolResult", member: "js-manager", callId: "r0" },
          ...Array.from({ length: padding }, (_, index) => ({ kind: "message", role: "assistant", text: `p${index}` })),
        ];
        // `projectRequest` returns the item array itself (the callers wrap a
        // refusal in their own envelope), so the copy is the answer.
        const kept = projectRequest(items, { pinned: PINNED });
        assert.ok(Array.isArray(kept), `the projection refused the copy: ${JSON.stringify(kept)}`);
        const ids = kept.map((item) => item.callId).filter((id) => typeof id === "string");
        assert.equal(ids.includes("hint"), true, "the hint pair survives");
        assert.equal(ids.includes("rv"), true, "the review face survives");
        assert.equal(ids.filter((id) => id.startsWith("w")).length, 0, "the model's own pre-boundary work is trimmed");
        // Core's default set drops both: the pair and the review read survive
        // only because the host added the empty name and the face's members.
        const withoutOurs = projectRequest(items, { pinned: DEFAULT_PINNED_MEMBERS });
        assert.ok(Array.isArray(withoutOurs), JSON.stringify(withoutOurs));
        assert.equal(withoutOurs.some((item) => item.callId === "hint"), false);
        assert.equal(withoutOurs.some((item) => item.callId === "rv"), false);
      },
    ),
    { ...fcRuns(30) },
  );
});

// ------------------------------------------------------------- snapshots

test("snapshot: the two exempt members answer synchronously from committed facts", async () => {
  const hooks = new ScopeHooks();
  hooks.open({ sessionId: "s" });
  await hooks.settle({
    sessionId: "s",
    member: "write",
    annotation: reset("first goal", 3),
    turnId: "t1",
    callId: "c1",
  });
  const negotiated = hooks.ask("negotiate", "s");
  assert.equal(negotiated.ok, true);
  assert.equal(negotiated.goal, "first goal");
  assert.equal(negotiated.budget, 3);
  assert.equal(negotiated.remaining, 3);
  assert.equal(negotiated.established, true);
  assert.equal(negotiated.mustReset, false);
  const previous = hooks.ask("askPreviousScope", "s");
  assert.equal(previous.ok, true);
  assert.equal(previous.goal, "first goal");
  assert.equal(previous.turnId, "t1");
});

test("snapshot: a member without a snapshot is refused as an unknown member", () => {
  const hooks = new ScopeHooks();
  hooks.open({ sessionId: "s" });
  for (const member of ["cat", "js-worker", "", "assume"]) {
    const refused = hooks.ask(member, "s");
    assert.equal(refused.ok, false, member);
    assert.equal(refused.code, SCOPE_CODES.SCOPE_MEMBER_UNKNOWN, member);
    assert.equal(refused.details.member, member);
  }
});

test("snapshot: a query during a settle is refused instead of answered from a half-book", async () => {
  // The store is the gate: while its append is pending, the settle has not
  // committed and the snapshot must not describe the book it is about to change.
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const store = {
    async append(records) {
      await gate;
      return records;
    },
  };
  const scope = new Scope({ sessionId: "s", store });
  const settling = scope.call({
    member: "write",
    annotation: reset("g", 4),
    turnId: "t1",
    callId: "c1",
  });
  const during = scope.negotiate();
  assert.equal(during.ok, false);
  assert.equal(during.code, SCOPE_CODES.SCOPE_SNAPSHOT_BUSY);
  assert.equal(scope.askPreviousScope().code, SCOPE_CODES.SCOPE_SNAPSHOT_BUSY);
  release();
  assert.equal((await settling).ok, true);
  // Committed: the same question now answers the settled fact.
  assert.equal(scope.negotiate().remaining, 4);
  assert.equal(scope.askPreviousScope().goal, "g");
});

test("snapshot: any interleaving of settles and queries answers only committed facts", async () => {
  // Property: a query never reports a budget that no settle committed. Between
  // any two committed settles the answer is constant, and every answer is one
  // of the committed values or a busy refusal.
  await fc.assert(
    fc.asyncProperty(
      fc.array(fc.integer({ min: 1, max: 6 }), { minLength: 1, maxLength: 8 }),
      fc.array(fc.boolean(), { minLength: 1, maxLength: 24 }),
      async (budgets, interleave) => {
        let release = null;
        let pending = false;
        // A store whose first append blocks until the test lets it through. The
        // settle holds its result on a clone until that append resolves, so a
        // query taken meanwhile sees the state before the commit.
        const store = {
          async append(records) {
            if (!pending) return records;
            await new Promise((resolve) => {
              release = resolve;
            });
            pending = false;
            return records;
          },
        };
        const scope = new Scope({ sessionId: "s", store });
        const committed = new Set();
        for (let index = 0; index < budgets.length; index += 1) {
          const budget = budgets[index];
          pending = interleave[index % interleave.length] === true;
          const settling = scope.call({
            member: "write",
            annotation: reset(`goal ${index}`, budget),
            turnId: `t${index}`,
            callId: `c${index}`,
          });
          if (pending) {
            // One microtask is enough for the call to reach the store; the
            // snapshot is then taken strictly before the commit.
            await Promise.resolve();
            const busy = scope.negotiate();
            assert.equal(busy.ok, false);
            assert.equal(busy.code, SCOPE_CODES.SCOPE_SNAPSHOT_BUSY);
            release();
          }
          const result = await settling;
          assert.equal(result.ok, true);
          committed.add(budget);
          const answer = scope.negotiate();
          assert.equal(answer.ok, true);
          assert.equal(answer.remaining, budget);
          assert.equal(committed.has(answer.remaining), true);
        }
      },
    ),
    { ...fcRuns(40) },
  );
});

// --------------------------------------------------------------- invariants
//
// The projection is the only thing between the host's history and the provider
// request, so its invariants are the ones a long-run session depends on. Each
// property below is stated as a behaviour, not as a restatement of the code:
// a failure names a request the provider could receive and should not, or a
// fact that disagrees with the record it was rebuilt from.

// One Scope driven through real settles, with the records it persisted.
const settledScope = async ({ budget = 3, turns = [] } = {}) => {
  const store = memoryStore();
  const scope = new Scope({ sessionId: "inv", store });
  await scope.call({ member: "write", annotation: reset("boot", budget), turnId: "boot", callId: "c-boot" });
  for (const [index, annotation] of turns.entries()) {
    await scope.call({ member: "write", annotation, turnId: `t${index}`, callId: `c${index}` });
  }
  return { scope, store };
};

test("invariant: a persisted Scope rebuilds the SAME request copy after a restart", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.array(
        fc.oneof(
          fc.constant(keep),
          fc.constant(remove("lesson")),
          fc.constant(remove("another lesson")),
          fc.constant(reset("next goal", 2)),
        ),
        { maxLength: 5 },
      ),
      async (turns) => {
        const { scope, store } = await settledScope({ budget: 9, turns });
        const items = [
          operator("first ask"),
          call("cat", "c0"),
          result("cat", "c0"),
          prose("prose"),
          thinking(),
          provider("observed"),
          call("write", "c1"),
          result("write", "c1"),
          operator("second ask"),
          call("cat", "c2"),
          result("cat", "c2"),
        ];
        const live = scope.project(items);
        // A cold start over the very records this process wrote.
        const cold = Scope.coldStart({ sessionId: "inv", records: store.written, store: memoryStore() });
        const rebuilt = cold.project(items);
        assert.deepEqual(rebuilt, live, "the same records must rebuild the same copy");
        // The budget is a persisted fact only when a `budget` record was read
        // after the newest reset; a reset alone proves an old event existed.
        if (store.written.filter((entry) => entry.type === "budget").length > 0) {
          assert.equal(cold.budgetKnown, true, "a budget record establishes the budget");
        }
        // The spin count is a persisted fact: every `remove` record carries the
        // count at the moment of the erase, so a restart answers the same
        // number and the `removeLast` projection writes the same replacement.
        assert.equal(cold.spinCount, scope.spinCount, "the spin count survives a restart");
      },
    ),
    { ...fcRuns(40) },
  );
});

test("invariant: a failed append changes no fact, and a retry books the beat exactly once", async () => {
  await fc.assert(
    fc.asyncProperty(fc.constantFrom(keep, remove("lesson"), reset("next", 4)), async (beat) => {
      let appends = 0;
      const written = [];
      const store = {
        async append(records) {
          appends += 1;
          // The boot reset is the first append; the beat under test is the
          // second, and that is the write that fails.
          if (appends === 2) throw new Error("disk full");
          for (const entry of records) written.push(entry);
        },
      };
      const scope = new Scope({ sessionId: "retry", store });
      await scope.call({ member: "write", annotation: reset("g", 5), turnId: "boot", callId: "c-boot" });
      const before = {
        remaining: scope.retention.remaining,
        spin: scope.spinCount,
        erased: scope.erased.length,
        resets: scope.resetBeats.length,
      };
      const refused = await scope.call({ member: "write", annotation: beat, turnId: "t1", callId: "c1" });
      assert.equal(refused.ok, false, "the failed append must be reported");
      assert.equal(refused.code, SCOPE_CODES.SCOPE_RECORD_FAILED);
      assert.equal(refused.persisted, false);
      // Nothing moved while the append failed: memory and the file still
      // describe the same facts.
      assert.deepEqual(
        {
          remaining: scope.retention.remaining,
          spin: scope.spinCount,
          erased: scope.erased.length,
          resets: scope.resetBeats.length,
        },
        before,
      );
      const coldAfterFailure = Scope.coldStart({ sessionId: "retry", records: written, store: memoryStore() });
      // The failed append wrote nothing, so the stream still shows the boot
      // state: no erase was recorded, and no spend either.
      assert.equal(coldAfterFailure.spinCount, before.spin);
      // The retry books the beat once: the same turn is still open, so it
      // settles rather than confirming, and the persisted records agree.
      const retried = await scope.call({ member: "write", annotation: beat, turnId: "t1", callId: "c1" });
      assert.equal(retried.ok, true);
      assert.equal(retried.outcome, "settled");
      if (beat.kind === SCOPE_KINDS.KEEP) assert.equal(scope.retention.remaining, before.remaining - 1);
      if (beat.kind === SCOPE_KINDS.REMOVE) assert.equal(scope.spinCount, before.spin + 1);
      if (beat.kind === SCOPE_KINDS.RESET) assert.equal(scope.resetBeats.length, before.resets + 1);
      // The persisted records carry the beat, and a cold start that knows the
      // budget reads the same remaining. A cold start does NOT know the budget
      // until a `budget` record exists, so a beat that opened no new Scope
      // (keep/remove) leaves it unconfirmed — by design (spec §6.2).
      const cold = Scope.coldStart({ sessionId: "retry", records: written, store: memoryStore() });
      // A `budget` record is written only by a settled reset, so it belongs to
      // the Scope that reset opened. After a later beat, the newest reset is
      // still that boot reset and the budget record still describes it, so the
      // cold start reads the budget — but NOT the spend the later beat made
      // (only the budget record's own value is restored). That is why the
      // request copy, not the number, is the thing a restart must reproduce.
      // The budget a restart proves is the NEWEST fact in the stream: a reset's
      // `budget` record, or a `keep` spend recorded after it. A `keep` writes
      // its remaining number, so the cold start is exact for every beat here.
      assert.equal(cold.budgetKnown, true, "the boot reset's budget record is the newest one");
      assert.equal(cold.retention.remaining, scope.retention.remaining, "the true remaining survives a restart");
      // A second retry of the SAME turn confirms instead of booking twice.
      const again = await scope.call({ member: "write", annotation: beat, turnId: "t1", callId: "c1" });
      assert.equal(again.ok, true);
      assert.equal(again.outcome, "confirmed");
      assert.equal(scope.retention.remaining, scope.retention.remaining);
    }),
    { ...fcRuns(12) },
  );
});

test("invariant: one turn spends at most one unit, whatever the annotation mix and order", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.array(fc.oneof(fc.constant(keep), fc.constant(meToo), fc.constant(remove("lesson"))), {
        minLength: 1,
        maxLength: 6,
      }),
      async (annotations) => {
        const { scope } = await settledScope({ budget: 5 });
        const before = scope.retention.remaining;
        let settled = 0;
        for (const [index, annotation] of annotations.entries()) {
          const result = await scope.call({
            member: "write",
            annotation,
            turnId: "one-turn",
            callId: `call-${index}`,
          });
          if (result.ok === true && result.outcome === "settled") settled += 1;
        }
        assert.ok(settled <= 1, "one model turn settles once");
        const spent = before - scope.retention.remaining;
        assert.ok(spent <= 1, "one model turn spends at most one unit");
        // A `meToo`-only turn is refused at the boundary, and books nothing.
        const refusal = await scope.closeTurn();
        if (settled === 0) {
          assert.equal(refusal?.code, SCOPE_CODES.SCOPE_METOO_ONLY);
          assert.equal(scope.retention.remaining, before);
        } else {
          assert.equal(refusal, null);
        }
      },
    ),
    { ...fcRuns(40) },
  );
});

test("invariant: a settle in flight is never answered by a half-book snapshot", async () => {
  await fc.assert(
    fc.asyncProperty(fc.integer({ min: 1, max: 5 }), async (budget) => {
      let release;
      let pending = true;
      const store = {
        async append(records) {
          if (pending) {
            pending = false;
            await new Promise((resolve) => {
              release = resolve;
            });
          }
          return records;
        },
      };
      const scope = new Scope({ sessionId: "busy", store });
      const settling = scope.call({ member: "write", annotation: reset("g", budget), turnId: "t", callId: "c" });
      await Promise.resolve();
      // While the settle holds its clone, both synchronous members refuse
      // instead of answering from the book that is about to change.
      assert.equal(scope.negotiate().code, SCOPE_CODES.SCOPE_SNAPSHOT_BUSY);
      assert.equal(scope.askPreviousScope().code, SCOPE_CODES.SCOPE_SNAPSHOT_BUSY);
      release();
      assert.equal((await settling).ok, true);
      assert.equal(scope.negotiate().remaining, budget);
    }),
    { ...fcRuns(15) },
  );
});

test("invariant: the projection never mutates its input and drops a beat whole", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.array(
        fc.oneof(
          fc.constant(operator("o")),
          fc.constant(prose("p")),
          fc.constant(thinking()),
          fc.record({ kind: fc.constant(ITEM.TOOL_CALL), member: fc.constantFrom("cat", "assume"), callId: fc.string() }),
          fc.record({ kind: fc.constant(ITEM.TOOL_RESULT), member: fc.constantFrom("cat", "assume"), callId: fc.string() }),
        ),
        { maxLength: 10 },
      ),
      async (items) => {
        const snapshot = JSON.stringify(items);
        const out = projectRequest(items, { resetCallId: null });
        assert.equal(JSON.stringify(items), snapshot, "the input array is never mutated");
        if (isScopeRefusal(out)) return;
        // The projection is a FILTER over the items it was handed: it never
        // invents an item and never reorders one. Every item it emits is one
        // of the input items, in input order.
        const seen = new Set();
        let cursor = -1;
        for (const item of out) {
          const at = items.findIndex((candidate, index) => index > cursor && candidate === item);
          assert.ok(at > cursor, `the projection emitted an item it was not handed (${JSON.stringify(item)})`);
          cursor = at;
          seen.add(at);
        }
        // A beat it drops, it drops whole: a call whose result was dropped is
        // dropped too (see `projectRequest`), so the copy never carries a call
        // whose result the provider already has.
        for (let index = 0; index < items.length; index += 1) {
          const item = items[index];
          const next = items[index + 1];
          if (item.kind !== ITEM.TOOL_CALL || next?.kind !== ITEM.TOOL_RESULT) continue;
          if (next.callId !== item.callId) continue;
          if (seen.has(index)) continue;
          assert.equal(seen.has(index + 1), false, "a dropped call must take its result with it");
        }
      },
    ),
    { ...fcRuns(150) },
  );
});

// The provider contract the adapter must keep, and Scope does not promise: an
// ORPHAN result (a result whose call is not in the copy) is passed through by
// the projection — Scope is a filter, not a repairer — so every adapter drops
// it at its own boundary (`messagesFromItems` in host-opencode,
// `selectKept` in host-cline and host-omp). This property pins the core side of
// that division of labour, so a change that starts inventing a call fails here.
test("invariant: an orphan result is passed through, never repaired with a call", () => {
  const orphan = result("cat", "gone");
  const out = projectRequest([operator("o"), orphan], { resetCallId: null });
  assert.deepEqual(out, [operator("o"), orphan], "Scope drops nothing after the boundary and invents nothing");
});


// ------------------------------------------------- cold start: exact facts
//
// Two facts the record stream carries and the first cold-start implementation
// dropped. Both are observable by the model: the spin line is part of a
// presented replacement, and the remaining budget is what `negotiate` answers.

test("cold start: the spin count is restored, and a removeLast projection keeps its exact number", async () => {
  const store = memoryStore();
  const live = new Scope({ sessionId: "spin", store });
  await live.call({ member: "write", annotation: reset("g", 6), turnId: "t0", callId: "c0" });
  // Three erases, so the third replacement text says "Spin count: 3".
  for (let index = 1; index <= 3; index += 1) {
    await live.call({ member: "write", annotation: remove(`lesson ${index}`), turnId: `t${index}`, callId: `c${index}` });
  }
  assert.equal(live.spinCount, 3);
  const items = [
    operator("o"),
    prose("p1"),
    call("cat", "x1"),
    result("cat", "x1"),
    call("write", "c1", { scope: remove() }),
    result("write", "c1", { scope: remove() }),
    call("write", "c2", { scope: remove() }),
    result("write", "c2", { scope: remove() }),
    call("write", "c3", { scope: remove() }),
    result("write", "c3", { scope: remove() }),
  ];
  const before = live.project(items);

  const cold = Scope.coldStart({ sessionId: "spin", records: store.written, store: memoryStore() });
  assert.equal(cold.spinCount, 3, "the newest remove record carries the count");
  assert.deepEqual(cold.project(items), before, "the replacement text is byte-identical after a restart");
  assert.match(JSON.stringify(cold.project(items)), /Spin count: 3/);
});

test("cold start: a keep spend recorded after the newest reset is the true remaining", async () => {
  const store = memoryStore();
  const live = new Scope({ sessionId: "spend", store });
  await live.call({ member: "write", annotation: reset("g", 5), turnId: "t0", callId: "c0" });
  await live.call({ member: "write", annotation: keep, turnId: "t1", callId: "c1" });
  await live.call({ member: "write", annotation: keep, turnId: "t2", callId: "c2" });
  assert.equal(live.retention.remaining, 3);

  const cold = Scope.coldStart({ sessionId: "spend", records: store.written, store: memoryStore() });
  assert.equal(cold.retention.remaining, 3, "two keeps are two units, not the reset's original budget");
  assert.equal(cold.budgetKnown, true);
  assert.equal(cold.notice(), null, "a proven budget needs no resetScope prompt");
  const answer = cold.negotiate();
  assert.equal(answer.remaining, 3);
  assert.equal(answer.mustReset, false);
});

test("cold start: a keep spend from an older Scope proves nothing about the current one", () => {
  const records = [
    resetRecord({ turnId: "t0", callId: "c0", goal: "old", budget: 4, remaining: 4 }),
    budgetRecord({ remaining: 4 }),
    keepRecord({ turnId: "t1", remaining: 3 }),
    // A second reset opens a new Scope; the spend above belongs to the old one.
    resetRecord({ turnId: "t2", callId: "c2", goal: "new", budget: 2, remaining: 2 }),
  ];
  const cold = readColdState(records);
  assert.equal(cold.spent.scope, 1, "the spend was recorded under the first Scope");
  // No budget record follows the second reset, so the budget is unproven and
  // the stale spend cannot stand in for it (spec §6.2).
  assert.equal(cold.budgetKnown, false);
  assert.equal(cold.remaining, null);
  const scope = Scope.coldStart({ sessionId: "s", records });
  assert.equal(scope.budgetKnown, false);
  assert.equal(scope.notice().kind, "scopeColdStart");
});

test("cold start: a budget record newer than a keep spend wins", () => {
  const records = [
    resetRecord({ turnId: "t0", callId: "c0", goal: "g", budget: 5, remaining: 5 }),
    budgetRecord({ remaining: 5 }),
    keepRecord({ turnId: "t1", remaining: 4 }),
    budgetRecord({ remaining: 1 }),
  ];
  const cold = readColdState(records);
  assert.equal(cold.remaining, 1, "the newest budget fact is the budget record");
  assert.equal(Scope.coldStart({ sessionId: "s", records }).retention.remaining, 1);
});

test("cold start: spend alone never establishes the budget and never locks the session", async () => {
  // A stream with a spend but no budget record and no reset: the cold start
  // must still ask for resetScope, and must not treat the spend as a default.
  const records = [keepRecord({ turnId: "t1", remaining: 0 })];
  const cold = Scope.coldStart({ sessionId: "s", records });
  assert.equal(cold.budgetKnown, false);
  assert.equal(cold.retention.established, false);
  assert.equal(cold.notice().kind, "scopeColdStart");
  const refused = await cold.call({ member: "write", annotation: keep, turnId: "t2", callId: "c2" });
  assert.equal(refused.code, SCOPE_CODES.SCOPE_UNINITIALIZED);
  // The reset the notice asks for settles and confirms the budget.
  const admitted = await cold.call({ member: "write", annotation: reset("g", 2), turnId: "t2", callId: "c2" });
  assert.equal(admitted.ok, true);
  assert.equal(cold.notice(), null);
});

// ------------------------------------------------------- removeLast is live
//
// The `erase` list is the fact: a record is written only when a `removeLast`
// settled, and the projected item never carries the annotation (no adapter
// copies `scope` onto the items it builds). Requiring the item to carry it too
// made the rewrite dead on every live path.

test("projection: an erase record rewrites its beat even when the item carries no annotation", () => {
  const items = [
    operator("ask"),
    prose("plan"),
    call("js-worker", "c1"),
    result("js-worker", "c1"),
    // The eraser, exactly as an adapter emits it: kind, member, callId, source.
    { ...call("js-worker", "c2"), source: {} },
    result("js-worker", "c2"),
  ];
  const out = projectRequest(items, { erase: [{ callId: "c2", replacement: "lesson", spin: 1 }] });
  assert.equal(isScopeRefusal(out), false);
  assert.deepEqual(
    out.map((item) => item.kind),
    [ITEM.MESSAGE, ITEM.MESSAGE, ITEM.MESSAGE, ITEM.TOOL_CALL, ITEM.TOOL_RESULT],
  );
  assert.equal(out[2].text, "lesson\nSpin count: 1", "the beat before the eraser became the lesson");
  assert.equal(out[3].callId, "c2", "the eraser itself stays");
});

test("projection: an erase record names the eraser by callId, whatever its member name is", () => {
  fc.assert(
    fc.property(
      fc.stringMatching(/^[a-z][a-z0-9-]{0,8}$/),
      fc.stringMatching(/^[a-z][a-z0-9-]{0,8}$/),
      (member, callId) => {
        const items = [
          operator("ask"),
          prose("plan"),
          call(member, "prior"),
          result(member, "prior"),
          call(member, callId),
          result(member, callId),
        ];
        const out = projectRequest(items, { erase: [{ callId, replacement: "lesson", spin: 2 }] });
        assert.equal(isScopeRefusal(out), false);
        const replacement = out.find((item) => item.kind === ITEM.MESSAGE && item.text?.startsWith("lesson"));
        assert.ok(replacement !== undefined, `member ${member}: the erase did not apply`);
        assert.equal(replacement.text, "lesson\nSpin count: 2");
      },
    ),
    { ...fcRuns(50) },
  );
});
