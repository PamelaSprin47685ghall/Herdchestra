// Review ledger and relay decision (spec §2.4).
//
// `review(findings)` submits a keyed obligation ledger. A string value adds or
// replaces the key, null deletes it, an empty array changes nothing. The ledger
// becoming empty is the decision point, and BOTH sides run the same timeline
// (§2.4): the first tenure's first empty review asks (Plan `S1`) or verifies
// (Manager); any later empty review RELAYS — retirement, barrier and a
// successor at the same milestone; the successor's first empty review promotes
// it one milestone. Only the last milestone differs: Plan hands its draft over
// instead of retiring, Manager closes. The decision is a receipt; the host
// performs the session handover.
//
// The review carries no note. A Plan handoff sends the canonical saved draft
// (spec §2.2) and nothing else, so the only text a Manager can start from is
// the draft this ledger was given as `source`.
//
// The evaluation window (spec §2.3) is the other fact this module owns:
// `js-review` is open from the first request of a round until the round's
// FIRST accepted review, and shut otherwise. The window reads the ledger, not a
// counter beside it, so a review that the ledger refused cannot close it.

import { invalidArgument } from "./errors.mjs";
import { stableJson } from "./util.mjs";

export const PLAN_MILESTONES = Object.freeze(["S1", "S2", "S3", "待执行"]);

export const MANAGER_MILESTONES = Object.freeze(["execute", "close", "done"]);

// The one question the system asks on its own (根 §2.4 第 1 步).
export const INITIAL_EMPTY_QUESTION = "我需要干什么？";

// The milestone `this.ask(question)` belongs to (根 §2.2, §3.1).
//
// 万象术 runs unattended: a user-facing agent asks the operator exactly once,
// while it is still learning what the work is. That is Plan's `S1`. Every other
// stage — Plan's `S2`/`S3`/`待执行`, every Manager milestone, every worker —
// decides for itself and never waits for a human.
export const ASK_MILESTONE = PLAN_MILESTONES[0];

// Whether an `ask` is allowed on this ledger right now, and why not.
export const askAllowed = (ledger) => {
  if (ledger === null || ledger === undefined) return { ok: false, reason: "NO_LEDGER" };
  if (ledger.kind !== "plan") return { ok: false, reason: "NOT_PLAN", role: ledger.kind };
  if (ledger.milestone !== ASK_MILESTONE) {
    return { ok: false, reason: "OUTSIDE_S1", milestone: ledger.milestone };
  }
  return { ok: true };
};

export const REVIEW_KINDS = Object.freeze({
  RECORDED: "recorded",
  NEXT_REVIEW: "nextReview",
  RELAY: "relay",
  CONFIRM: "confirm",
  CLOSED: "closed",
  ASK_USER: "askUser",
  HANDOFF: "handoff",
  REOPENED: "reopened",
  REJECTED: "rejected",
});

export const REVIEW_REJECTIONS = Object.freeze({
  DRAFT_MISSING: "DRAFT_MISSING",
  ALREADY_DONE: "ALREADY_DONE",
  // A Plan at `待执行` accepts the hand-off and nothing else (根 §2.2).
  ALREADY_READY: "ALREADY_READY",
  AGENT_MESSAGE: "AGENT_MESSAGE",
  NOT_FINISHED: "NOT_FINISHED",
  // One physical call id, two different payloads.
  REPLAY_MISMATCH: "REPLAY_MISMATCH",
});

// What a review window answers when it is asked whether it is open.
export const WINDOW_STATES = Object.freeze({
  OPEN: "open",
  CLOSED: "closed",
  NOT_MANAGER: "notManager",
});

// The draft a Manager session starts from (spec §2.2): the saved draft at the
// canonical path, and nothing else. The result is the text to send, or null
// when the caller supplied none.
const draftFor = (source) => (typeof source === "string" ? source : null);

// The findings a review accepts, validated before anything is booked. Exported
// so a face that must ANSWER a refusal without an await (a program's bare
// `review(findings)`, which the model writes un-awaited) can ask the same
// question first: one definition of "a valid finding", whichever door is used.
export const validateFindings = (findings) => {
  if (!Array.isArray(findings)) throw invalidArgument("findings must be an array");
  for (const finding of findings) {
    if (finding === null || typeof finding !== "object") {
      throw invalidArgument("each finding must be an object");
    }
    if (typeof finding.key !== "string" || finding.key.length === 0) {
      throw invalidArgument("each finding needs a non-empty string key");
    }
    if (finding.value !== null && typeof finding.value !== "string") {
      throw invalidArgument("a finding value must be a string or null");
    }
  }
  return findings;
};

export class ReviewLedger {
  #kind;
  #milestones;
  #milestone;
  #tenure;
  #successor;
  #entries;
  #reviews;
  #resourcesSettled;
  // The ledger as it stood before the review being decided. A refusal restores
  // it, so a rejected review leaves nothing behind — not an obligation the
  // milestone forbids.
  #beforeFindings = new Map();
  // Every physical call this ledger has booked, by call id: `{ payload,
  // receipt }`. §2.4 第 6 步 makes one physical call book once, so a repeated
  // arrival must answer with the receipt it already produced — after a relay,
  // after a milestone moved, after other calls were booked. This map is that
  // memory, and it is not the ledger: it never affects a decision.
  #calls;

  // `kind` is "plan" or "manager". `resourcesSettled()` answers whether the real
  // execution resources are closed; only Manager closing reads it.
  constructor({ kind, resourcesSettled = () => true } = {}) {
    if (kind === "plan") {
      this.#kind = "plan";
      this.#milestones = PLAN_MILESTONES;
    } else if (kind === "manager") {
      this.#kind = "manager";
      this.#milestones = MANAGER_MILESTONES;
    } else {
      throw invalidArgument("ledger kind must be plan or manager");
    }
    if (typeof resourcesSettled !== "function") {
      throw invalidArgument("resourcesSettled must be a function");
    }
    this.#milestone = 0;
    this.#tenure = 0;
    this.#successor = false;
    this.#entries = new Map();
    this.#reviews = 0;
    this.#resourcesSettled = resourcesSettled;
    this.#calls = new Map();
  }

  get kind() {
    return this.#kind;
  }

  get milestone() {
    return this.#milestones[this.#milestone];
  }

  get milestoneIndex() {
    return this.#milestone;
  }

  get tenure() {
    return this.#tenure;
  }

  get successor() {
    return this.#successor;
  }

  get reviewCount() {
    return this.#reviews;
  }

  get empty() {
    return this.#entries.size === 0;
  }

  get obligations() {
    return [...this.#entries.entries()].map(([key, value]) => ({ key, value }));
  }

  // Apply findings and return the new ledger size.
  apply(findings) {
    for (const { key, value } of validateFindings(findings)) {
      if (value === null) this.#entries.delete(key);
      else this.#entries.set(key, value);
    }
    // Applying findings says nothing about physical calls: a replay is answered
    // from `#calls`, so a call stays replayable however the ledger moves after
    // it (§2.4 第 6 步).
    return this.#entries.size;
  }

  #receipt(kind, after, extra = {}) {
    return { ok: true, kind, milestone: this.milestone, tenure: this.#tenure, obligations: after, ...extra };
  }

  // The one review entry point. `source` is the saved draft of a Plan handoff;
  // it is only read at the handoff.
  //
  // `callId` is the host's identity for the physical tool call (§2.4 第 6 步).
  // With one, the call books ONCE and every later arrival of the same id is
  // answered with the receipt it produced the first time — whatever has happened
  // since, including obligations another call added, a relay that moved the
  // tenure, or a later milestone. A replayed call never books, never relays,
  // never hands off and never moves a counter again; the only answer a replay
  // can give is the original receipt, marked `replayed: true`. The same id with
  // a different payload is refused (`REPLAY_MISMATCH`) and changes nothing.
  //
  // Without a call id the review is booked exactly as before: a host that has
  // no call identity cannot be told apart from a second call.
  review(findings, source = null, { callId = null } = {}) {
    if (callId !== null && callId !== undefined) {
      const replay = this.#replay(findings, source, callId);
      if (replay !== null) return replay;
    }
    const receipt = this.#reviewNow(findings, source);
    if (callId !== null && callId !== undefined) {
      // Every call is remembered, not only the last one: a replay may arrive
      // after other calls have been booked, and it must still answer with its
      // own receipt. The map is the ledger's memory of physical calls, which is
      // a different fact from the ledger itself.
      this.#calls.set(callId, {
        payload: stableJson({ findings, source: typeof source === "string" ? source : null }),
        receipt,
      });
    }
    return receipt;
  }

  // The replay answer for `callId`, or null when this id has not been seen.
  //
  // The payload is compared FIRST and in every state: the same id with other
  // findings is a caller bug whether or not the ledger moved since, and booking
  // it would perform side effects a call that never happened asked for.
  //
  // Otherwise the recorded receipt is returned as it was produced. It names the
  // tenure and milestone it judged, and that is correct: it is the receipt of
  // THAT call, not a description of the ledger now. The `replayed` flag is the
  // only field added, so a caller can tell a fresh answer from a repeat.
  #replay(findings, source, callId) {
    const known = this.#calls.get(callId);
    if (known === undefined) return null;
    const payload = stableJson({ findings, source: typeof source === "string" ? source : null });
    if (payload !== known.payload) {
      return {
        ok: false,
        kind: REVIEW_KINDS.REJECTED,
        rejection: REVIEW_REJECTIONS.REPLAY_MISMATCH,
        milestone: this.milestone,
        tenure: this.#tenure,
        obligations: this.#entries.size,
      };
    }
    return { ...known.receipt, replayed: true };
  }

  // The body of one review. Split out so `review` can record the call it booked.
  #reviewNow(findings, source) {
    if (this.#kind === "manager" && this.#milestone === MANAGER_MILESTONES.length - 1) {
      return {
        ok: false,
        kind: REVIEW_KINDS.REJECTED,
        rejection: REVIEW_REJECTIONS.ALREADY_DONE,
        milestone: this.milestone,
        tenure: this.#tenure,
        obligations: this.#entries.size,
      };
    }
    const before = this.#entries.size;
    this.#beforeFindings = new Map(this.#entries);
    const after = this.apply(findings);
    // A Plan at `ready` that hands off without a draft did not review: the
    // handoff is refused before a Manager exists (§2.2), and counting it would
    // spend this tenure's first-review credit on a call that changed nothing.
    // The refusal is therefore decided here, ahead of the counters and before
    // any milestone moves.
    if (this.#kind === "plan" && this.#milestone === PLAN_MILESTONES.length - 1 && after === 0) {
      const draft = draftFor(source);
      // A missing or empty saved draft refuses the handoff. Nothing is
      // substituted: §2.2 says the Manager receives the saved draft, and an
      // empty draft would start a session with no work in hand.
      if (draft === null || draft === "") {
        return {
          ok: false,
          kind: REVIEW_KINDS.REJECTED,
          rejection: REVIEW_REJECTIONS.DRAFT_MISSING,
          milestone: this.milestone,
          tenure: this.#tenure,
          obligations: after,
        };
      }
      // The handoff does not consume a review: the Plan stays at `ready` and
      // may hand off again on a later empty review.
      return this.#receipt(REVIEW_KINDS.HANDOFF, after, { draft });
    }
    // 待执行 is final: the only review it accepts is the hand-off, decided
    // above. Anything that reaches here — a non-empty ledger — is refused
    // before the counters move, and before the findings stay applied: an
    // obligation recorded at 待执行 would be work the milestone forbids, and
    // the refusal would leave a ledger the nudge then quotes forever.
    //
    // This is decided before the non-empty branch, because a ready Plan has
    // nothing left to record: the draft is done, and the only next step is the
    // Manager.
    if (this.#kind === "plan" && this.#milestone === PLAN_MILESTONES.length - 1) {
      this.#entries = new Map(this.#beforeFindings);
      return {
        ok: false,
        kind: REVIEW_KINDS.REJECTED,
        rejection: REVIEW_REJECTIONS.ALREADY_READY,
        milestone: this.milestone,
        tenure: this.#tenure,
        obligations: this.#entries.size,
      };
    }

    // A Plan's handoff returned above without reaching here, so it never spends
    // a review: it starts a Manager session and leaves the Plan where it was.
    this.#reviews += 1;
    const isFirst = this.#tenure === 0 && this.#reviews === 1;

    if (after > 0) {
      // A relayed successor that records obligations is still the successor:
      // 根 §2.4 says a non-empty review is a normal working period, and the first
      // empty review that follows advances the milestone. Clearing the flag
      // here would relay again instead of advancing.
      return this.#receipt(REVIEW_KINDS.RECORDED, after, { added: after - before });
    }

    if (this.#kind === "plan") {
      // The first empty review of the first tenure is not a review: the system
      // asks the operator what to do and the same tenure continues. The
      // question is an `ask` — park + interrupt — and it exists only in `S1`
      // (根 §2.2, §2.4 第 1 步).
      if (isFirst) {
        return this.#receipt(REVIEW_KINDS.ASK_USER, after, {
          question: INITIAL_EMPTY_QUESTION,
          interrupt: true,
        });
      }
      // The successor's first empty review promotes it to the next milestone,
      // with no new tenure (根 §2.4 第 5 步). Plan reaches 待执行 this way and
      // only this way: an empty review by an incumbent RELAYS.
      if (this.#successor) {
        this.#milestone += 1;
        this.#successor = false;
        return this.#receipt(REVIEW_KINDS.CONFIRM, after);
      }
      return this.#receipt(REVIEW_KINDS.RELAY, after, {
        retire: true,
        openSuccessor: true,
        barrier: true,
      });
    }

    // Manager side. An unattended Manager never asks: an empty first review is
    // a claim that the first tenure verified the inherited work, which is the
    // `confirm` step — it advances the milestone like any other successor's
    // empty review.
    if (isFirst) return this.#receipt(REVIEW_KINDS.CONFIRM, after);
    if (this.#successor) {
      this.#milestone += 1;
      this.#successor = false;
      if (this.#milestone === MANAGER_MILESTONES.length - 1) {
        return this.#receipt(REVIEW_KINDS.CLOSED, after);
      }
      return this.#receipt(REVIEW_KINDS.CONFIRM, after);
    }
    if (this.#milestone === MANAGER_MILESTONES.indexOf("close")) {
      if (!this.#resourcesSettled()) {
        return this.#receipt(REVIEW_KINDS.NEXT_REVIEW, after, { reason: "RESOURCES_UNSETTLED" });
      }
      this.#milestone = MANAGER_MILESTONES.length - 1;
      return this.#receipt(REVIEW_KINDS.CLOSED, after);
    }
    return this.#receipt(REVIEW_KINDS.RELAY, after, {
      retire: true,
      openSuccessor: true,
      barrier: true,
    });
  }

  // The successor starts at the same milestone with an empty ledger. The host
  // calls this inside the same persistent record as the relay.
  //
  // A relay never happens at the LAST milestone: Manager closes there, and a
  // Plan hands its draft over instead of retiring. So a successor opened on a
  // finished side is a caller bug, and it is refused before anything is
  // written — not only on the Manager's `done`.
  openSuccessor() {
    if (this.#milestone >= this.#milestones.length - 1) {
      throw invalidArgument("a finished work has no successor");
    }
    // The check is complete before the first write: a refused successor must
    // leave the ledger exactly as it was, not half-opened.
    this.#tenure += 1;
    this.#successor = true;
    this.#entries = new Map();
    this.#reviews = 0;
    return this;
  }

  // Rebuild a tenure that ALREADY handed over, from a durable record.
  //
  // `openSuccessor` is the live step (it refuses a finished work). This is the
  // cold-start step: the host read a record that says "tenure n is judging at
  // milestone m", so the ledger must describe exactly that, without replaying
  // the relay that produced it. `successor` stays true because the successor
  // has not yet made its first judgement (§2.4 第 5 步: its first empty review
  // promotes; a non-empty one returns to normal work).
  adoptSuccessor({ tenure, milestone } = {}) {
    if (!Number.isInteger(tenure) || tenure < 1) {
      throw invalidArgument("an adopted successor tenure must be a positive integer");
    }
    const index = this.#milestones.indexOf(milestone);
    if (index < 0) throw invalidArgument("an adopted successor milestone must be one of this ledger's milestones");
    // The milestone is copied from the relay, and no relay happens at the last
    // one: a record that names it describes a hand-over this side never
    // performs, and adopting it would open a tenure nobody can advance.
    if (index >= this.#milestones.length - 1) {
      throw invalidArgument("a finished milestone has no successor");
    }
    // ABSOLUTE, not relative: `tenure` is the tenure that is judging, so this
    // call is idempotent and never opens a second successor. Opening is
    // `openSuccessor`'s job, and it runs once, inside the relay.
    this.#tenure = tenure;
    this.#milestone = index;
    this.#successor = true;
    this.#entries = new Map();
    this.#reviews = 0;
    return this;
  }

  // A finished work reopens only for a real operator message.
  //
  // One message reopens one work: `reopen` also empties the ledger, so a second
  // operator message while the work is already back in progress would erase the
  // obligations the reopened tenure just recorded.
  reopen({ operator } = {}) {
    if (this.#milestone !== this.#milestones.length - 1) {
      return { ok: false, rejection: REVIEW_REJECTIONS.NOT_FINISHED };
    }
    if (operator !== true) return { ok: false, rejection: REVIEW_REJECTIONS.AGENT_MESSAGE };
    const milestone = Math.max(0, this.#milestones.length - 2);
    this.#entries = new Map();
    this.#milestone = milestone;
    return { ok: true, kind: REVIEW_KINDS.REOPENED, milestone: this.milestone };
  }

  // A stable fingerprint of the ledger, for tests and persisted records.
  fingerprint() {
    return stableJson({ kind: this.#kind, milestone: this.milestone, entries: this.obligations });
  }
}

// Plan keeps the draft read-only until its first review of the tenure.
export const draftWritable = (ledger) => ledger.reviewCount > 0;

// The Manager's evaluation window (spec §2.3).
//
// A round begins at the first request of a turn and ends when the round's
// first review is accepted. While it is open, `js-review` is the Manager's only
// read channel; once it closes, the Manager must fork a read-only worker to
// learn anything about the tree.
//
// The window is opened and closed by facts the ledger already holds, so there
// is no second counter that could drift from the ledger it describes:
//
//   - opening is the host's word: `beginRound()` runs at the request boundary;
//   - closing is the round's first ACCEPTED review: `noteReview(receipt)` is
//     fed every receipt the ledger answers, and only `ok: true` closes it.
//
// A refused review leaves it open: the Manager has not judged, so the round's
// evaluation did not conclude. A review recorded while the window is already
// closed cannot reopen it — the round's first acceptance was the one that
// closed it.
//
// The name `open` belongs to the boolean getter alone. A method called `open`
// would shadow that getter (a method and a getter share one property key), and
// every `window.open === true` in every adapter would then compare a function
// and answer "shut" — a gate that never opens, with no error to show for it.
export class ReviewWindow {
  #kind;
  #round;
  #open = false;
  #closedRound = null;
  #reviews = 0;
  #tenure = 0;

  constructor({ kind } = {}) {
    if (kind !== "manager" && kind !== "plan") {
      throw invalidArgument("window kind must be plan or manager");
    }
    this.#kind = kind;
    this.#round = 0;
  }

  get kind() {
    return this.#kind;
  }

  // The round the host is currently preparing. Counts the host's request
  // boundaries; it is the identity a closing review is booked against.
  get round() {
    return this.#round;
  }

  // Accepted reviews this window has seen, across rounds.
  get reviews() {
    return this.#reviews;
  }

  get state() {
    if (this.#kind !== "manager") return WINDOW_STATES.NOT_MANAGER;
    return this.#open ? WINDOW_STATES.OPEN : WINDOW_STATES.CLOSED;
  }

  // The one boolean every gate reads. `open` is a GETTER and only a getter: a
  // method of the same name would shadow it, and every `window.open === true`
  // would compare a function to `true` and silently answer "shut". The round is
  // started by `beginRound`, which is a different name for a different fact.
  get open() {
    return this.state === WINDOW_STATES.OPEN;
  }

  // The request boundary: a new round starts, and the evaluation is open.
  //
  // Without an argument this is "the next request of this session", and it is
  // idempotent within one round: a host that prepares the SAME request more
  // than once (policy preparation) must not reopen a round a review just shut.
  // A round therefore advances only when the previous one was still open — a
  // request that follows an accepted review is a new round.
  //
  // With an explicit `round` the caller names the round it is preparing, and
  // the window follows it forward. A round at or before the current one is the
  // round already in progress: it neither moves the window nor reopens it.
  beginRound(round = null) {
    if (round !== null && (!Number.isInteger(round) || round < 1)) {
      throw invalidArgument("a window round must be a positive integer");
    }
    if (round !== null) {
      if (round > this.#round) {
        this.#round = round;
        this.#open = true;
      }
      return this.state;
    }
    if (this.#open) return this.state;
    if (this.#round === 0 || this.#closedRound === this.#round) {
      this.#round += 1;
      this.#open = true;
    }
    return this.state;
  }

  // One review receipt, as the ledger answered it. Only an accepted review of
  // the open round closes the window, and it closes it once.
  noteReview(receipt) {
    if (this.#kind !== "manager") return this.state;
    if (receipt === null || typeof receipt !== "object" || receipt.ok !== true) return this.state;
    this.#reviews += 1;
    if (!this.#open || this.#closedRound === this.#round) return this.state;
    this.#closedRound = this.#round;
    this.#open = false;
    return this.state;
  }

  // The tenure a successor starts with. §2.4 hands the successor a fresh
  // evaluation: its first round opens a window of its own.
  relay(tenure) {
    if (!Number.isInteger(tenure) || tenure < 0) {
      throw invalidArgument("a window tenure must be a non-negative integer");
    }
    this.#tenure = tenure;
    this.#open = false;
    this.#closedRound = this.#round;
    return this.state;
  }

  get tenure() {
    return this.#tenure;
  }

  // A stable fingerprint, for tests and persisted records.
  fingerprint() {
    return stableJson({
      kind: this.#kind,
      round: this.#round,
      open: this.#open,
      closedRound: this.#closedRound,
      reviews: this.#reviews,
      tenure: this.#tenure,
    });
  }
}
