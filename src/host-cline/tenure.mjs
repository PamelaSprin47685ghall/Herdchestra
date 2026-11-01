// The review ledger of one Manager tenure, persisted (根 §2.3, §2.4).
//
// `core/review.mjs` is the ledger; this module keeps it across requests, writes
// the relay fence, and owns the Scope and Pair Hint books of the session.
//
// Retirement, the context barrier and the successor start land as one record
// (根 §2.4 第 3 步): either all three are in the file or none of them is, and the
// in-memory ledger follows the file. The fence is also what the request
// projection reads: everything up to it is the retired tenure's conversation
// and never reaches the successor.

import { ReviewLedger, ReviewWindow, REVIEW_KINDS, MANAGER_MILESTONES, PLAN_MILESTONES } from "../core/review.mjs";
import { systemClock } from "../core/clock.mjs";
import { PairHint } from "../core/pair-hint/index.mjs";
import { ScopeHooks } from "../core/scope/hooks.mjs";
import { appendJsonLine, readJson, readJsonLines, writeJson } from "./files.mjs";
import { languageLadder } from "./facts.mjs";

export const LEDGER_VERSION = 1;

const EMPTY_FENCE = Object.freeze({ retiredTenure: null, barrier: null, successor: null });

// Rebuild the ledger from its file.
//
// `ReviewLedger` keeps its state private, so the ledger is rebuilt by replaying
// the transitions the file records: one empty review per milestone step, then
// the stored obligations. A file that cannot be replayed stops at the milestone
// actually reached; it never fakes a later one.
//
// THE FENCE IS THE AUTHORITY ON WHO IS JUDGING. `handOver` writes the fence and
// the relay owner advances the ledger (`openSuccessor`) a moment later, so a
// crash between the two leaves a file whose fence names tenure n+1 at milestone
// m while its ledger fields still describe tenure n. Replaying that file the
// naive way produced a retired incumbent: `retired` compared the ledger's tenure
// (n) against the fence's successor (n+1), answered `true`, and refused the
// successor's first review with `TENURE_RETIRED` — forever, because nothing
// ever advanced the ledger again.
//
// So a file whose fence names a successor is restored THROUGH that fence:
// `adoptSuccessor` takes the fence's tenure and milestone verbatim, with the
// successor flag set, which is exactly the state `openSuccessor` produces. The
// obligations are the ones the file holds (the successor's ledger starts empty,
// and a file that recorded some is replayed on top). A fence that cannot be
// adopted — a milestone this ledger does not have, or the finished one, where no
// relay happens — is not papered over: the replay falls back to the plain path
// and the refusal surfaces at the next review.
const restore = ({ file, kind }) => {
  const saved = readJson(file, null);
  const empty = { ledger: new ReviewLedger({ kind }), fence: { ...EMPTY_FENCE } };
  if (saved === null || saved.kind !== kind || saved.v !== LEDGER_VERSION) return empty;
  const fence =
    saved.fence === null || typeof saved.fence !== "object" ? { ...EMPTY_FENCE } : { ...EMPTY_FENCE, ...saved.fence };
  const successorRecord = fence.successor !== null && fence.successor !== undefined;
  // The milestone list is the LEDGER's, never one side's: a Plan's file records
  // `S1`…`待执行`, and indexing it against the Manager's names found `-1` for
  // every Plan restart — the ledger came back fresh at `S1` with its obligations
  // discarded, and the fence branch below was unreachable.
  const milestones = kind === "plan" ? PLAN_MILESTONES : MANAGER_MILESTONES;
  // The fence first: it names the tenure that is judging. It is evaluated BEFORE
  // the milestone lookup because the fence's own milestone is the one that
  // matters, and a file written between the fence and the ledger advance carries
  // the fence's milestone, not the stale ledger field.
  if (successorRecord) {
    const ledger = new ReviewLedger({ kind });
    try {
      ledger.adoptSuccessor({ tenure: fence.successor.tenure, milestone: fence.successor.milestone ?? saved.milestone });
      const obligations = Array.isArray(saved.obligations) ? saved.obligations : [];
      if (obligations.length > 0) ledger.apply(obligations);
      return { ledger, fence };
    } catch {
      // An unusable fence is reported by the plain replay below, which stops at
      // the milestone the file really describes.
    }
  }
  const target = milestones.indexOf(saved.milestone);
  if (target < 0) return { ledger: new ReviewLedger({ kind }), fence };
  const ledger = new ReviewLedger({ kind });
  for (let step = 0; step < target; step += 1) {
    if (ledger.milestone !== milestones[step]) break;
    const receipt = ledger.review([]);
    if (receipt.ok !== true) break;
    if (receipt.kind === REVIEW_KINDS.RELAY) ledger.openSuccessor();
  }
  const obligations = Array.isArray(saved.obligations) ? saved.obligations : [];
  if (obligations.length > 0) ledger.apply(obligations);
  return { ledger, fence };
};

export class TenureState {
  #file;
  #scopeFile;
  #sessionId;
  #ctx;
  #kind = "manager";
  #ledger;
  #fence;
  #turns = 0;
  #parked = new Set();
  #scope = null;
  #pairHint = null;
  // One Pair Hint per delegated runtime of this session (`pairHintFor`). A
  // worker's conversation is its own, so its chain is too.
  #childHints = new Map();
  #anchored = null;
  #window;

  constructor({ file, scopeFile, sessionId, ctx = null, kind = "manager" }) {
    this.#file = file;
    this.#scopeFile = scopeFile;
    this.#sessionId = sessionId;
    this.#ctx = ctx;
    this.#kind = kind;
    const restored = restore({ file, kind });
    this.#ledger = restored.ledger;
    this.#fence = restored.fence;
    // The evaluation window (根 §2.3) of this tenure's side. It is not
    // persisted: a window describes one round of one process, and a restart
    // starts a round nobody has evaluated yet.
    this.#window = new ReviewWindow({ kind: kind === "plan" ? "plan" : "manager" });
  }

  get file() {
    return this.#file;
  }

  get ledger() {
    return this.#ledger;
  }

  get fence() {
    return this.#fence;
  }

  // The evaluation window (根 §2.3): open from a round's first request until
  // that round's first accepted review.
  get window() {
    return this.#window;
  }

  get sessionId() {
    return this.#sessionId;
  }

  get turns() {
    return this.#turns;
  }

  // A retired tenure reviews nothing: after the hand-over the successor judges,
  // and the successor's first review is the next review of this session.
  get retired() {
    // "Retired" describes the TENURE, not the fence. A fence that names a
    // successor only means a hand-over was recorded; once the ledger has been
    // moved to that successor's tenure, this session IS the successor and may
    // review again (根 §2.4 第 5 步: the successor's first review is the next
    // review of this session). Reading the fence alone made every successor's
    // first review answer `TENURE_RETIRED`, so no relay could ever complete.
    const successor = this.#fence.successor;
    if (successor === null || successor === undefined) return false;
    return this.#ledger.tenure !== successor.tenure;
  }

  nextTurn() {
    this.#turns += 1;
    return this.#turns;
  }

  noteParked(name) {
    this.#parked.add(name);
  }

  noteFinished(name) {
    this.#parked.delete(name);
  }

  parkedNames() {
    return [...this.#parked].sort();
  }

  // `save(ledger)` is the shape a surface's `ledgerStore` calls, so a review
  // accepted inside a program is written down (`compose/program/surface.mjs`
  // `saveLedger`). The argument is the ledger the surface judged with; this
  // tenure's own ledger is the same object, and is what gets written.
  save() {
    return writeJson(this.#file, {
      v: LEDGER_VERSION,
      sessionId: this.#sessionId,
      kind: this.#kind,
      milestone: this.#ledger.milestone,
      tenure: this.#ledger.tenure,
      reviews: this.#ledger.reviewCount,
      obligations: this.#ledger.obligations,
      successor: this.#ledger.successor,
      fence: this.#fence,
    });
  }

  // The Scope book for this session (spec §6.2). One instance reaches every hook;
  // a second Scope for one session is a wiring error, not a second budget. The
  // book opens as a cold start from the records on disk, so a persisted reset
  // proves only that the event happened and the notice repeats until this
  // process establishes a budget.
  scope() {
    if (this.#scope === null) {
      const hooks = new ScopeHooks();
      hooks.coldStart({
        sessionId: this.#sessionId,
        records: readJsonLines(this.#scopeFile),
        store: {
          append: async (records) => {
            for (const entry of records) appendJsonLine(this.#scopeFile, entry);
          },
        },
      });
      this.#scope = hooks;
    }
    return this.#scope;
  }

  // The Pair Hint book (spec §6.1). The first request binds the language from the
  // ladder; a session that already has a binding keeps it.
  async pairHint(root) {
    if (this.#pairHint === null) {
      const hint = await PairHint.open({ root, sessionId: this.#sessionId });
      if (!hint.bound) await hint.bind({ ladder: languageLadder(this.#ctx) });
      this.#pairHint = hint;
    }
    return this.#pairHint;
  }

  // The Pair Hint of ONE delegated runtime (a `fork` child, a fission lane).
  //
  // A child receives this session's hooks, so it reaches `projectMessages`; its
  // conversation is its own, and chaining it against the parent's epoch refused
  // its first turn with `PREFIX_DRIFT` on a live fork. Each child gets an epoch
  // keyed by its own agent id, so two workers never share one either. The
  // session id keeps the file per session; the child's key keeps the CHAIN per
  // child.
  async pairHintFor(root, childAgentId) {
    // The key names a Pair Hint epoch, and an epoch name reaches a file path, so
    // it must be a plain file-safe name: no `:`, no separators the host would
    // refuse.
    const key = `${this.#sessionId}-${childAgentId}`;
    if (!this.#childHints.has(key)) {
      const hint = await PairHint.open({ root, sessionId: key });
      if (!hint.bound) await hint.bind({ ladder: languageLadder(this.#ctx) });
      this.#childHints.set(key, hint);
    }
    return this.#childHints.get(key);
  }

  // The fence this tenure's prefix chain hangs from, claimed once (§6.1).
  //
  // The relay barrier cuts the successor's copy: its first request starts with
  // the operator's own words instead of the retired conversation, so it cannot
  // begin with the bytes the predecessor presented. That cut is the one
  // legitimate chain break (根 §2.4 第 3 步): the hint keeps its frozen text and
  // epoch, and the successor's first request becomes the new anchor. Only the
  // first one — re-anchoring a later request would drop the pair the host never
  // stores, and the next request would be compared against a copy the pair is
  // not in. `null` (no fence) keeps the chain the session already has.
  anchorOfFence() {
    const fence = this.#fence;
    if (fence === null || fence === undefined || fence.successor === null || fence.successor === undefined) {
      return null;
    }
    const key = `${fence.retiredTenure}>${fence.successor.tenure}`;
    if (this.#anchored === key) return false;
    this.#anchored = key;
    return true;
  }

  // Hand this tenure over (根 §2.4 steps 3 and 5). Retirement, the barrier and the
  // successor's identity are written as one record. The successor runs in this
  // session, so the ledger moves in the same call; a file that does not land
  // means none of the three happened, and the incumbent stays where it was.
  //
  // The barrier names the last review and the last turn: that is where the
  // successor's view is cut.
  handOver({ lastReview = null, lastTurn = null } = {}) {
    if (this.retired) {
      return {
        ok: false,
        reason: "TENURE_ALREADY_RETIRED",
        milestone: this.#ledger.milestone,
        tenure: this.#ledger.tenure,
      };
    }
    const barrier = {
      kind: "contextBarrier",
      sessionId: this.#sessionId,
      retiresTenure: this.#ledger.tenure,
      lastReview,
      lastTurn,
      at: systemClock.now(),
    };
    const successor = { tenure: this.#ledger.tenure + 1, milestone: this.#ledger.milestone };
    const previous = this.#fence;
    this.#fence = { retiredTenure: this.#ledger.tenure, barrier, successor };
    const written = this.save();
    if (written.ok !== true) {
      this.#fence = previous;
      return {
        ok: false,
        reason: written.code ?? "RELAY_WRITE_FAILED",
        milestone: this.#ledger.milestone,
        tenure: this.#ledger.tenure,
      };
    }
    // The LEDGER is advanced by the relay's owner, not by this port:
    // `compose/relay/review.mjs` calls `ledger.openSuccessor()` unconditionally
    // once the port reports the whole hand-over. Advancing here too would open
    // TWO successors for one relay.
    //
    // What this port owns is the DURABLE record (the fence) and the evaluation
    // window that record implies. The window is keyed to the successor's tenure.
    this.#window.relay(this.#fence.successor.tenure);
    return {
      ok: true,
      retired: true,
      // `compose/relay/review.mjs#handOverCompleted` and `compose/HOST-PORTS.md`
      // both require `barrier: true` — the FIELD SAYS WHETHER THE BARRIER WAS
      // WRITTEN, not what it contains. Reporting the object here made a
      // successful hand-over read as `RELAY_UNAVAILABLE`, and because
      // `openSuccessor()` had already run, the tenure was retired on disk while
      // the ledger was left believing no successor existed. The record itself
      // stays available under `contextBarrier` for a caller that wants it.
      barrier: true,
      contextBarrier: barrier,
      successor,
      // The milestone is the one the relay carried over; the tenure is the
      // successor's, which the relay owner is about to open.
      milestone: this.#ledger.milestone,
      tenure: this.#fence.successor.tenure,
    };
  }
}
