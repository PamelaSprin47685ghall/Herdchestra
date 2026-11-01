// `review(findings)` for Plan and Manager (spec §5).
//
// The ledger itself is core's `ReviewLedger`: it applies the findings and says
// what kind of turn this review is (recorded, relay, confirm, closed, askUser,
// handoff, rejected). Composition adds only the side effects the host must
// perform and reports them back unchanged:
//
//   relay      -> port.handOver({ retire: true, openSuccessor: true, barrier: true })
//   handoff    -> port.startManager(draft)   (the canonical saved draft only)
//   askUser    -> port.askOperator(question)
//
// A host that cannot perform a side effect fails the review instead of
// pretending it happened. For a relay that means the ledger stays where it is:
// retirement, barrier and successor open in one step (根 §2.4 第 3 步), so a host
// that did not retire the incumbent cannot open its successor either. The
// review is then not accepted (`ok: false`) and the incumbent may review again.

import { ReviewLedger, REVIEW_KINDS } from "../../core/review.mjs";
import { PARK_REASONS } from "../../core/receipt.mjs";

/**
 * True only when the host reports the whole hand-over: retirement, the context
 * barrier and the successor start. A missing part means the step did not
 * happen, whatever `ok` says.
 */
export const handOverCompleted = (done) =>
  done !== null &&
  typeof done === "object" &&
  done.ok === true &&
  done.retired === true &&
  done.barrier === true &&
  done.successor !== null &&
  done.successor !== undefined;

export class ReviewMember {
  #ledger;
  #port;
  #queue;
  #park;

  // The evaluation window is NOT booked here (spec §2.3). It has one owner:
  // `ProgramSurface.noteReview`, which every face of a review reaches — the
  // surface's own `review` and a program's `review` alike — because the surface
  // is what built this member. A second booking inside the member was a second
  // verification of one fact.
  // `park` is the SURFACE's park channel, and the first review's question goes
  // through it (根 §2.4 第 1 步, §3.3 example 7): the question IS a park, so it
  // suspends the body the same way `this.ask` does. A member without one parks
  // in place, which is what a host with no suspension channel can offer.
  constructor({ ledger, port, park = null }) {
    this.#ledger = ledger;
    this.#port = port;
    this.#park = typeof park === "function" ? park : null;
    // One review at a time. The ledger decides before the host acts, so two
    // overlapping reviews would both read the same empty ledger: both would
    // relay, and the second would retire the successor the first just opened.
    // Serializing keeps the decision and its side effect one step.
    this.#queue = Promise.resolve();
  }

  get ledger() {
    return this.#ledger;
  }

  // `source` is the canonical saved draft, read only for a Plan handoff.
  // `callId` is the host's identity for the physical tool call (§2.4 第 6 步): a
  // replayed call returns the receipt it already produced, and the same id with
  // another payload is refused by the ledger. A host without a call identity
  // omits it and books exactly as before.
  //
  // The returned promise is this review's own; a failure does not stall the
  // queue behind it.
  review(findings, source = null, { callId = null } = {}) {
    const run = this.#queue.then(() => this.#reviewNow(findings, source, callId));
    this.#queue = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  async #reviewNow(findings, source, callId = null) {
    return await this.#decide(findings, source, callId);
  }

  async #decide(findings, source, callId = null) {
    const receipt = this.#ledger.review(findings, source, { callId });
    // A replayed call is answered by the receipt the first one produced, and
    // its side effect already happened: the port must not run again (根 §2.4 第 6
    // 步). The ledger cannot know whether this side of the call was performed —
    // it only knows the review — so the flag is honoured here, where the port is
    // called. `replayed` is stated on the answer so the caller can tell the two
    // apart without comparing ids itself.
    if (!receipt.ok) return receipt;
    // A replay is answered by the ledger alone: the side effects of this call
    // already happened the first time it arrived, and performing them again
    // would retire a second tenure or start a second Manager. Stated once, on
    // the answer, so the caller can tell the two apart without comparing ids.
    if (receipt.replayed === true) return receipt;
    if (receipt.kind === REVIEW_KINDS.RELAY) {
      const done = await this.#port.handOver({
        retire: receipt.retire === true,
        openSuccessor: receipt.openSuccessor === true,
        barrier: receipt.barrier === true,
        // The findings that emptied the ledger. A host that cannot perform the
        // relay inside this turn records them with its deferred intent, so the
        // later step acts on the obligation this review actually established.
        findings,
      });
      // A host may DEFER rather than perform: a session cannot switch its own
      // transcript while its turn is live. That is not a failure of the review —
      // the intent is durable and the relay will run at the host's idle
      // boundary — but nothing has changed yet either, so the tenure does not
      // advance and the receipt must say `deferred`, not success.
      if (done?.deferred === true && done?.ok !== true) {
        return {
          ok: false,
          kind: REVIEW_KINDS.RELAY,
          rejection: "RELAY_DEFERRED",
          milestone: receipt.milestone,
          tenure: receipt.tenure,
          obligations: receipt.obligations,
          handedOver: done,
        };
      }
      // Retirement, barrier and successor open are one step (根 §2.4 第 3 步). The
      // host must report `ok: true` *and* the three parts actually done; an
      // `ok: true` next to `{retired: false, successor: null}` is a partial
      // hand-over, and accepting it would advance the tenure while nothing
      // changed.
      if (!handOverCompleted(done)) {
        return {
          ok: false,
          kind: REVIEW_KINDS.RELAY,
          rejection: "RELAY_UNAVAILABLE",
          milestone: receipt.milestone,
          tenure: receipt.tenure,
          obligations: receipt.obligations,
          handedOver: done,
        };
      }
      this.#ledger.openSuccessor();
      return { ...receipt, handedOver: done };
    }
    if (receipt.kind === REVIEW_KINDS.HANDOFF) {
      const started = await this.#port.startManager(receipt.draft);
      // §2.2: the handoff starts a Manager session or it fails. A host that
      // reports `{ok: false}` (or a missing receipt) did not start one, and
      // saying "handoff" would tell the Plan its draft is running when it is
      // not. The Plan stays at `ready`, so the next empty review retries.
      if (started === null || started === undefined || started.ok === false) {
        return {
          ok: false,
          kind: REVIEW_KINDS.HANDOFF,
          rejection: "HANDOFF_UNAVAILABLE",
          milestone: receipt.milestone,
          tenure: receipt.tenure,
          obligations: receipt.obligations,
          started,
        };
      }
      return { ...receipt, started };
    }
    if (receipt.kind === REVIEW_KINDS.ASK_USER) {
      // The park comes FIRST and it is the suspension: a body that wrote
      // `await review([])` must not run on with an answer nobody gave. The
      // promise the channel returns is the parked expression's value, so the
      // answer arrives when the operator's next prompt resumes it — not from
      // this call.
      const site = { reason: PARK_REASONS.ASK, payload: { question: receipt.question } };
      const parked = this.#park === null ? null : this.#park(site);
      if (parked !== null && typeof parked?.then === "function") {
        // The refusal of a body that never resumes is not an unhandled
        // rejection: the parked program owns that outcome.
        parked.catch?.(() => {});
        return parked;
      }
      const answer = await this.#port.askOperator(receipt.question);
      return { ...receipt, answer };
    }
    return receipt;
  }

  // A real operator message reopens a finished work; an agent message does not.
  reopen({ operator }) {
    return this.#ledger.reopen({ operator });
  }
}

export { ReviewLedger, REVIEW_KINDS };
