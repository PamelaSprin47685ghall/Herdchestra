// The unattended-exit rule (根 §2.2, §2.3, §2.4).
//
// 万象术 runs unattended: a user-facing agent (`Plan`, `Manager`) works until
// the work is verified, not until a turn happens to end. Two exits exist and
// only two:
//
//   1. `ask` — Plan's `S1` only. `this.ask(question)` parks the program and
//      interrupts the turn, so control goes to the operator. This is the one
//      moment a human decides.
//   2. verified completion — the tenure that judges the previous one accepts
//      it. For Plan that is `待执行` reached by a successor's empty review,
//      with the hand-off submitted (`review([])` to the Manager). For Manager
//      that is `done`: the `close` milestone with an empty ledger.
//
// Every other turn exit is a stop the agent did not earn. The system answers it
// with a nudge: the obligations still open, in the ledger's own words, and the
// standing instruction that this session is authorized and unattended.
//
// This module is pure. It reads a ledger-shaped record (`kind`, `milestone`,
// `tenure`, `successor`, `obligations`) and answers a decision; it never reads
// a host, a clock or a file, so the invariant is testable as an equation.

import { PLAN_MILESTONES, MANAGER_MILESTONES } from "../core/review.mjs";
import { cueScalar, toCue } from "../core/cue.mjs";

// The roles that face the user. A worker's turn ending is its report, not a
// stop: its Manager joins the record and decides.
export const USER_FACING = Object.freeze(["plan", "manager"]);

// Why a turn may stop.
export const STOP_REASONS = Object.freeze({
  NOT_USER_FACING: "not-user-facing",
  IN_FLIGHT: "in-flight",
  ASK_S1: "ask-s1",
  OPERATOR_INTERRUPT: "operator-interrupt",
  VERIFIED_COMPLETION: "verified-completion",
  NUDGE: "unattended-nudge",
});

// Why an operator ended a turn, as each host spells it on the wire.
//
// A human interrupt is the operator taking control, and §2.2's nudge exists for
// the OPPOSITE event: the model stopping on its own. Answering an interrupt with
// "you are unattended, keep going" overrides the one person the standing order
// tells the model never to wait for. Measured on omp 18.14.2 (2026-11-01,
// session `01a1958e`): the operator interrupted the first turn, the host
// recorded `stopReason: "aborted"` with `errorMessage: "Interrupted by user"`
// (`agent-session.ts:11240`, the `USER_INTERRUPT_LABEL` branch), and the
// adapter nudged 24 ms later.
//
// Every spelling below is a host fact, not a guess:
//
//   "Interrupted by user"  omp's `USER_INTERRUPT_LABEL` on the aborted
//                          assistant message (`packages/tui/src/chat/messages.ts`,
//                          passed at `modes/controllers/extension-ui-controller.ts`
//                          `abort: () => this.ctx.session.abort({ reason: USER_INTERRUPT_LABEL })`)
//   "Request was aborted"  the same host's second spelling for a prompt that
//                          never reached a provider (measured in the same
//                          transcript, 158 ms after the first)
//   interrupted/cancelled  the words a host uses for an execution the operator
//                          stopped (`session.execution.interrupted` on OpenCode,
//                          a cancelled `status` on Cline)
export const OPERATOR_INTERRUPT_PATTERN = /interrupt|abort|cancel/i;

// The one instruction the nudge repeats, word for word (根 §2.2). It is a
// constant so a test can assert the text the model reads, not a paraphrase of
// it. The sentence before it — the verdict on THIS turn's findings — is built
// by `buildNudgeMessage`; this constant is the half that never changes.
export const STANDING_ORDER =
  "系统是无人值守的常授权模式，禁止等待用户决策或授权，禁止半途而废。";

// The verdict in front of the standing order, word for word (根 §2.2:
// 「当前的 review 账本还有上述事项没有做，立刻去做！」). The list of open
// obligations sits between the two, so the sentence points at it.
export const STANDING_VERDICT = "当前的 review 账本还有上述事项没有做，立刻去做！";

// The last milestone of each kind: the only one a stop may be earned at.
export const COMPLETION_MILESTONE = Object.freeze({
  plan: PLAN_MILESTONES[PLAN_MILESTONES.length - 1],
  manager: MANAGER_MILESTONES[MANAGER_MILESTONES.length - 1],
});

// Plan's `S1`: the only milestone an `ask` belongs to.
export const ASK_MILESTONE = PLAN_MILESTONES[0];

// A ledger-shaped view. A real `ReviewLedger` answers all five; a caller that
// has none says so instead of guessing, and `null` means "no ledger yet".
const viewOf = (ledger) => {
  if (ledger === null || ledger === undefined) return null;
  return {
    kind: typeof ledger.kind === "string" ? ledger.kind : null,
    milestone: typeof ledger.milestone === "string" ? ledger.milestone : null,
    tenure: Number.isInteger(ledger.tenure) ? ledger.tenure : 0,
    successor: ledger.successor === true,
    obligations: Array.isArray(ledger.obligations) ? ledger.obligations : [],
  };
};

// Whether the verified-completion exit is reached.
//
// Plan: the work is at `待执行` and a tenure AFTER the first one judges there,
// and the hand-off has been submitted.
//
// `tenure >= 1` is the verification, and it is exactly what 根 §2.4 第 5 步
// makes it: every empty review below `待执行` relays, a relay opens the next
// tenure at the SAME milestone, and the successor's first empty review is what
// promotes that milestone. So the ready stage is only ever entered by a
// successor's verdict, and that successor is tenure 1 or later — the first
// tenure (tenure 0) can never reach it. This is why the test is the tenure and
// NOT the `successor` flag: the promotion is the successor's first review, and
// that review is the one that clears the flag (core keeps it true only until
// the first judgement, so a non-empty first review stays a successor).
//
// The Plan's FIRST tenure therefore never reaches the exit on its own: it can
// only relay, and a relay copies the milestone to a successor. That is the
// point of §2.4 — the tenure that did the work never certifies it.
//
// Manager: `done` is the `close` milestone with an empty ledger that no relay
// follows. The ledger only reaches it through `review([])` after the resources
// settled, so the state IS the proof.
//
// Manager at `execute` is NOT an exit even when the ledger is empty. An empty
// `execute` review relays (`retire`, `openSuccessor`, `barrier`), and the
// successor that judges the tenure advances on its own empty review. Leaving at
// `execute` would end the work on the first tenure's own word — the one thing
// the relay exists to prevent — so the successor must record that judgement
// through `review` before any turn may stop.
export const verifiedCompletion = ({ kind, milestone, tenure }, { handedOff = false } = {}) => {
  if (kind === "plan") {
    return milestone === COMPLETION_MILESTONE.plan && Number.isInteger(tenure) && tenure >= 1 && handedOff === true;
  }
  if (kind === "manager") return milestone === COMPLETION_MILESTONE.manager;
  return false;
};

// The nudge text: the open obligations in the ledger's own words, then the
// standing order. Structured with CUE (根 §6.3), never bare JSON.
//
// An empty ledger is not "nothing to do": at any milestone but the last, an
// empty ledger means the review that would advance it has not been submitted,
// and at the last it means the verification or the hand-off is missing.
export const buildNudgeMessage = ({ role, ledger }) => {
  const view = viewOf(ledger);
  const obligations = view === null ? [] : view.obligations;
  const milestone = view === null ? null : view.milestone;
  const tenure = view === null ? null : view.tenure;
  const successor = view === null ? null : view.successor;

  const lines = [];
  lines.push(`# Nudge（${role ?? "unknown"}）`);
  lines.push("");
  lines.push("本回合在未完成工作前试图自然停止，系统已拦截。");
  lines.push("");
  const facts = {
    role: role ?? null,
    tenure,
    milestone,
    successor,
    obligations: obligations.length,
  };
  lines.push("## 当前状态");
  lines.push("");
  lines.push(toCue(facts));
  lines.push("");
  lines.push("## review 账本中尚未完成的事项");
  lines.push("");
  if (obligations.length === 0) {
    lines.push(
      "账本当前为空：本阶段还没有通过 `review` 登记任何义务，也尚未完成继任验收、阶段晋级或交接。" +
        "空账不等于无事可做——下一步是用 `review(findings)` 登记本阶段真实的待办，或提交能推进里程碑的评审。",
    );
  } else {
    // One obligation per entry, rendered as a CUE value under its own key. A
    // nested array would quote every value and escape the newlines inside a
    // multi-line obligation; the model needs the obligation's own words, so each
    // value is rendered on its own and keeps its line breaks.
    for (const { key, value } of obligations) {
      lines.push(`- \`${key}\`:`);
      lines.push(cueScalar(String(value)));
    }
  }
  lines.push("");
  lines.push("## 指令");
  lines.push("");
  // 根 §2.2 gives the instruction word for word: the verdict points at the list
  // above it, then the standing order. Both are constants, so a test can assert
  // the text the model reads rather than a paraphrase of it.
  lines.push(`${STANDING_VERDICT}${STANDING_ORDER}`);
  lines.push("");
  lines.push(
    "不要向用户提问（`this.ask` 仅在 Plan 的 S1 阶段可用），不要等待授权，不要交付半成品。" +
      "唯一的正常停止是继任者独立验收上一任合格后的终局停止。",
  );
  return lines.join("\n");
};

// Whether a host's record of a turn says the OPERATOR ended it.
//
// The three hosts report the same fact in three shapes, and each shape is read
// here once so no host re-derives the rule:
//
//   omp      `{ stopReason, errorMessage }` of the aborted assistant message:
//            `stopReason: "aborted"` with `errorMessage: "Interrupted by user"`.
//            The stop reason alone is not enough: an internal abort (a
//            compaction, a session transition) is also `aborted`, and nudging
//            there would fight the host's own machinery.
//   OpenCode `{ type: "session.execution.interrupted" }` — the event the host
//            emits for an execution the operator stopped.
//   Cline    a run result whose `status` is cancelled/aborted/interrupted (an
//            operator stop), never `completed`.
//
// Anything else — including a missing record — is NOT an interrupt: reading an
// unknown exit as one would silently disable the nudge for a whole session,
// which is the one outcome §2.2 forbids.
export const operatorInterruptOf = (record = null) => {
  if (record === null || record === undefined) return false;
  // A caller that has already read the host says so with a boolean.
  if (record === true || record === false) return record;
  if (typeof record === "string") return OPERATOR_INTERRUPT_PATTERN.test(record);
  if (typeof record !== "object") return false;
  // omp: the aborted assistant message.
  if (record.stopReason === "aborted") {
    return typeof record.errorMessage === "string" && OPERATOR_INTERRUPT_PATTERN.test(record.errorMessage);
  }
  // OpenCode: the host's own event type.
  if (typeof record.type === "string") {
    return record.type === "session.execution.interrupted" || OPERATOR_INTERRUPT_PATTERN.test(record.type);
  }
  // Cline: the run's terminal status.
  if (typeof record.status === "string") return record.status !== "completed" && OPERATOR_INTERRUPT_PATTERN.test(record.status);
  return false;
};

// Decide what a turn exit means (根 §2.2, §2.3).
//
//   role                the agent that is ending a turn
//   ledger              the review ledger (a `ReviewLedger` or its view), or null
//   handedOff           Plan: the `review([])` hand-off was submitted
//   askedInTurn         this turn made a legal `S1` `ask` (park + interrupt)
//   hasInFlightToolCalls  the turn has not stopped yet
//   interrupted         the OPERATOR ended this turn (a user interrupt, a stop
//                       button, a cancelled run). The host's own spelling, as
//                       `operatorInterruptOf` reads it; a string is accepted as
//                       shorthand and tested against the same pattern.
//
// Returns `{ stopAllowed, reason, nudge }`: `stopAllowed: false` always carries
// a nudge, and `stopAllowed: true` never does.
export const evaluateTurnExit = ({
  role,
  ledger = null,
  handedOff = false,
  askedInTurn = false,
  hasInFlightToolCalls = false,
  interrupted = false,
} = {}) => {
  // A turn that still has work running has not stopped: there is nothing to
  // decide and nothing to say.
  if (hasInFlightToolCalls === true) {
    return { stopAllowed: true, reason: STOP_REASONS.IN_FLIGHT, nudge: null };
  }
  // The operator ended this turn. A human interrupt is control taken, not work
  // abandoned: nudging it would override the person the standing order tells the
  // model never to wait for. This is checked before the role, because the fact
  // is about the exit and not about who may be nudged — and it is never a nudge,
  // whatever the ledger still owes.
  if (operatorInterruptOf(interrupted)) {
    return { stopAllowed: true, reason: STOP_REASONS.OPERATOR_INTERRUPT, nudge: null };
  }
  // A worker's turn ending is its report to its Manager.
  if (!USER_FACING.includes(role)) {
    return { stopAllowed: true, reason: STOP_REASONS.NOT_USER_FACING, nudge: null };
  }
  const view = viewOf(ledger);
  // The `ask` exit: Plan's `S1`, and only there.
  if (askedInTurn === true && role === "plan" && view !== null && view.kind === "plan" && view.milestone === ASK_MILESTONE) {
    return { stopAllowed: true, reason: STOP_REASONS.ASK_S1, nudge: null };
  }
  // The verified-completion exit.
  if (view !== null && verifiedCompletion(view, { handedOff })) {
    return { stopAllowed: true, reason: STOP_REASONS.VERIFIED_COMPLETION, nudge: null };
  }
  return {
    stopAllowed: false,
    reason: STOP_REASONS.NUDGE,
    nudge: buildNudgeMessage({ role, ledger: view }),
  };
};
