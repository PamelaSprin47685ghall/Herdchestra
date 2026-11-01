// The surface of one program (spec §3.1, §4, §5, §6, §7, §8).
//
// A program is the model's whole programming face for one request. The surface
// object it receives has exactly the members its role allows; a member the role
// lacks is absent, so calling it is a TypeError, not a silent no-op.
//
// Two facts are owned here:
// - the park channel: `park` suspends the program and returns a receipt; the
//   parked expression resumes later through another program's `unpark`;
// - the member list: core members (files, text, call) plus the port-backed
//   members (ask, fork, join, fission, review, devops) that this role has.
//
// Two lifecycle facts are owned here too, because they are about the run, not
// about a member:
// - the deadline: `Deadline.forIntent(clock, intent)` fixes it once when the
//   surface is built, and the program owns it from then on (core cancels it on
//   park and replaces it on resume). A receipt reports what is left;
// - settlement: `settle()` closes the run after the body returned or threw and
//   retires the name, so a settled program is not left live forever. A run that
//   is still draining its own operations settles when the last one ends.

import { CoreError, CODES, invalidArgument } from "../../core/errors.mjs";
import { COMPOSE_CODES, ComposeError } from "../errors.mjs";
import { programView, withExtra } from "../../core/receipt.mjs";
import { validateFindings } from "../../core/review.mjs";
import { Deadline } from "../../core/clock.mjs";
import { PLAN_MILESTONES, ReviewLedger, ReviewWindow, askAllowed, draftWritable } from "../../core/review.mjs";
import { expireRequest, expiredReceipt } from "../deadline.mjs";
import { ReviewMember } from "../relay/review.mjs";
import { coreMembers, reviewMembers } from "./members.mjs";
import { forkRequest } from "./role-brief.mjs";

export const ROLES = Object.freeze({
  MANAGER: "manager",
  CODER: "coder",
  DEVOPS: "devops",
  PLAN: "plan",
});

// The refusal of a `js-review` run whose window is shut (spec §2.3).
export const REVIEW_WINDOW_CLOSED = "REVIEW_WINDOW_CLOSED";

// A program surface built without the program it runs (根 §3.3: every receipt
// carries the full line-numbered program). Raised at the build, so no host can
// answer the model with a receipt that cannot say what ran.
export const SOURCE_REQUIRED = "PROGRAM_SOURCE_REQUIRED";

// The program a receipt describes (根 §3.3). A host may hand the text in
// directly, or derive it from the fields it composes a program from; what it may
// not do is hand nothing, because `source` is part of the receipt's shape and
// `null` there tells the model the program is unknown.
export const programSource = (source) => {
  const text = typeof source === "function" ? source() : source;
  if (typeof text !== "string" || text.trim() === "") {
    throw new ComposeError(SOURCE_REQUIRED, "a program surface needs the program it runs", {
      field: "source",
    });
  }
  return text;
};

// `this.ask` called outside Plan's `S1` (根 §2.2, §3.1). The system runs
// unattended: only the first stage of a plan may wait for a human, and every
// other stage — Plan's later milestones, the Manager, every worker — decides
// for itself.
export const ASK_FORBIDDEN = COMPOSE_CODES.ASK_FORBIDDEN_OUTSIDE_S1;

// Which members a surface has, in display order.
//
// `negotiate` and `askPreviousScope` are the two exempt members of §6.2: they
// ask the session's Scope for a snapshot, spend no retention and are never
// refused for a spent budget. Both are on every surface that has work to book —
// the Manager's and the worker's — because the budget they report is the one
// those surfaces spend. They are not on `js-review` (a read-only evaluation is
// not a beat of any Scope) and not on Plan (Plan has no Scope).
const SCOPE_QUERIES = Object.freeze(["negotiate", "askPreviousScope"]);

// A member call the model wrote without `await` must never leave a rejection
// unobserved.
//
// `fork`, `join`, `fission` and `devops` all answer with the port's promise, and
// a program is free to call one without awaiting it (the spec's examples read a
// value later, and a member may park). When the port then rejects, nothing
// observes the rejection and the host aborts — measured on this shape, a
// `fork(...)` whose port threw produced one unhandled rejection while the
// program went on as though the worker existed (omp 18.14.2).
//
// So the promise is OBSERVED here, and only observed: an awaiting caller still
// receives the rejection, and a caller that did not await is left with the
// unobserved-failure problem the model created, not a dead host. This is the
// same rule `this.ask` already follows for a park nobody resumes.
const observed = (value) => {
  if (value !== null && typeof value === "object" && typeof value.then === "function") value.catch?.(() => {});
  return value;
};

// A thrown refusal as the receipt every review answer has. The code and the
// message are the two facts a program reads (`r.ok`, `r.code`), so they travel
// in the same fields a successful receipt uses.
const refusalReceipt = (error) => ({
  ok: false,
  code: typeof error?.code === "string" ? error.code : String(error?.name ?? "ERROR"),
  message: error instanceof Error ? error.message : String(error),
  ...(error?.details === undefined ? {} : { details: error.details }),
});

export const SURFACE_MEMBERS = Object.freeze({
  // Manager neither reads nor runs (spec §2.1, §2.4, §8): no file members, no
  // `call`. Its only read channel is the separate `js-review` tool, and it
  // writes nothing. `fission` belongs to fork-started workers only (§4.2).
  //
  // `ask` stays in the list so a Manager that tries it reads the named refusal
  // (`ASK_FORBIDDEN_OUTSIDE_S1`) instead of a TypeError: 根 §2.2 gives the
  // question to Plan's `S1` alone, and the Manager runs unattended.
  "js-manager": ["park", "unpark", "fork", "join", "review", "ask", ...SCOPE_QUERIES],
  // A worker has no `ask`: that channel belongs to a top-level program only
  // (§3.1, §3.3 example 7). It has no `review`: the ledger is the Manager's.
  //
  // `fork`/`join` are here because §3.1 marks them for `js-worker` as well as
  // `js-manager`: a worker that the brief allows to dispatch starts children in
  // its OWN roster (§4.1), which is a different thing from `fission` (§4.2).
  // The surface exposes them; whether a given worker may use them is the brief's
  // business, not the member table's.
  "js-worker": [
    "cat",
    "stat",
    "ls",
    "write",
    "mkdir",
    "rm",
    "mv",
    "where",
    "whereAll",
    "park",
    "unpark",
    "call",
    "fission",
    "fork",
    "join",
    ...SCOPE_QUERIES,
  ],
  "js-review": ["cat", "stat", "ls", "where", "whereAll"],
  // Plan asks the operator in `S1`, so Plan must also be able to resume the
  // program it parked: `this.ask` IS `this.park` + `interrupt`, and the answer
  // arrives as `this.unpark(name, answer)` from a later program of the same
  // session (§2.2, §3.3 example 7). Without `unpark` here the ask could never be
  // answered on this face.
  //
  // `ask` is listed because the member must exist to be refused by name after
  // `S1`; the handler decides from the ledger's milestone.
  plan: ["cat", "write", "park", "unpark", "ask", "devops", "review"],
});

export const surfaceNameFor = (role) => {
  if (role === ROLES.MANAGER) return "js-manager";
  if (role === ROLES.CODER || role === ROLES.DEVOPS) return "js-worker";
  if (role === ROLES.PLAN) return "plan";
  throw invalidArgument(`unknown role: ${role}`);
};

const text = (name, value) => {
  if (typeof value !== "string" || value.length === 0) {
    throw invalidArgument(`${name} must be a non-empty string`);
  }
  return value;
};

// One program surface. `ports` carries the host ports this role needs; a port
// the role does not use is not read.
export class ProgramSurface {
  // `intent` is the request's intent text; the deadline is 50 ms per UTF-8
  // byte of it (spec §3.3). `registry` is optional: when given, `settle()`
  // retires the finished name so it can be reused.
  constructor({
    name,
    role,
    program,
    clock,
    source = null,
    ports,
    root,
    guard = () => null,
    views,
    ledger = null,
    // Where the ledger is stored, when the host keeps it across programs
    // (spec §2.2: Plan's draft is read-only until the tenure's first review, so
    // the count must survive the program that wrote it).
    ledgerStore = null,
    // The session's evaluation window (spec §2.3). A host that gates `js-review`
    // across requests hands in the session's window, so a review accepted by one
    // program keeps the face shut for the next one. Without one, the surface
    // owns a window for its own run, opened at admission — which is exactly the
    // round the program itself belongs to.
    window: reviewWindow = null,
    intent = null,
    registry = null,
    // The host's park channel (spec §3.3). compose's members call
    // `park({reason, payload})`; a host that suspends the body passes its own
    // function here, so the member call waits on the host's promise instead of
    // the surface's in-process park.
    park = null,
  }) {
    text("program name", name);
    surfaceNameFor(role);
    // `programName`: `name` is an accessor, and a same-named data field would
    // shadow it (the accessor would recurse).
    this.programName = name;
    this.roleName = role;
    this.programState = program;
    this.clock = clock;
    this.source = source;
    this.views = views;
    this.ports = ports;
    // The review's canonical source (§2.2): the saved draft, read through the
    // host's `draft` port. Captured here so a surface built without that port
    // is the same surface it always was, and a Plan's review never reads a
    // different port object than the one it was assembled with.
    this.draftPort = ports?.draft ?? null;
    this.root = root;
    this.guard = guard;
    this.registry = registry;
    // The host's park function; `park` is this surface's member method.
    this.hostPark = park;
    // The deadline is fixed once, at admission. A program that arrived with
    // one already keeps it.
    if (intent !== null && program.deadline === null) {
      program.attachDeadline(Deadline.forIntent(clock, intent));
    }
    // A receipt's `source` is the program (根 §3.3), so a surface without one is
    // refused here rather than handing the model a receipt that answers
    // `source: null`. `programSource` derives the line-numbered text, so the one
    // host that composes its program from several fields can still say what it
    // ran.
    this.source = programSource(source);
    // The review ledger outlives one program: a host that persists it hands the
    // session's ledger in through `ports.ledger`, so two programs of one tenure
    // judge the same obligations. Without one, the surface owns a ledger for its
    // own run. The host's relay port performs the side effects (spec §5).
    this.ledger =
      ledger ??
      ports?.ledger ??
      new ReviewLedger({ kind: role === ROLES.PLAN ? "plan" : "manager" });
    // The window belongs to the same side as the ledger, and it is the ledger
    // that decides what a review means: a window for the other side would close
    // on a Plan review and never on a Manager's. The host passes the session's
    // window in; `ports` is not the place for it, because no member calls it.
    // A host that hands no window in gets a SHUT window of the right side, never
    // a fresh open one: §2.3's face must not exist merely because the adapter
    // forgot to say where its session keeps the round.
    this.window = reviewWindow ?? new ReviewWindow({ kind: role === ROLES.PLAN ? "plan" : "manager" });
    // Whether that window is the session's. The host owns the request boundary
    // of a session window; a per-program window's own request is the program,
    // and `reviewSurface` opens its round when the face is first asked for.
    this.holdsSessionWindow = reviewWindow !== null;
    // A program that arrives is a request of its round, and §2.3 opens the
    // evaluation there. A host with its own request boundary has already opened
    // it, and `beginRound()` with no argument is idempotent under an open
    // window: a repeated preparation of one request never starts a second
    // round, while a program that follows an accepted review does — which is
    // exactly "the next request after the review".
    if (this.holdsSessionWindow) this.window.beginRound();
    // Every accepted review is persisted once, by the store's owner, so the
    // count and the milestone a later program reads are the ones this one left.
    this.ledgerStore = ledgerStore;
  }

  // Persist the ledger after a review changed it. `receipt` is the review's
  // answer; a review the ledger refused changes nothing and is not written.
  saveLedger(receipt) {
    if (this.ledgerStore === null || typeof this.ledgerStore?.save !== "function") return receipt;
    if (receipt === null || typeof receipt !== "object" || receipt.ok !== true) return receipt;
    this.ledgerStore.save(this.ledger);
    return receipt;
  }

  // One review receipt, as the ledger answered it: the window reads it here so
  // the round's first acceptance closes the face for every later program of the
  // same session (spec §2.3). Called for refused receipts too, which is why the
  // window itself decides what counts.
  noteReview(receipt) {
    this.window.noteReview(receipt);
    return receipt;
  }

  // One `review(findings)` from a program, answered as a RECEIPT whenever that
  // is possible and as a promise only when it is not.
  //
  // Why the distinction: the ledger decides synchronously, so a review that
  // records obligations (or refuses the arguments) already has its answer before
  // this function returns. Handing the body a promise would hide that answer —
  // `r.ok` off an unsettled promise is `undefined`, which is how a refusal
  // reached the model as `value: {}` (omp 18.14.2, `/tmp/r7`).
  //
  // Two paths stay asynchronous, and both MUST:
  //
  //   - a Plan handoff reads the canonical saved draft (`ports.draft.read`);
  //   - a relay performs the host's side effect (`ReviewMember` -> `port`).
  //
  // A body that awaits either one receives the same receipt, so the two
  // spellings (`const r = review(f)` and `await review(f)`) agree on every field.
  #reviewMember(findings) {
    // The arguments are judged FIRST, before any queue is touched: a refused
    // list is an answer this member can give without an await, which is the
    // whole point — the model reads `r.ok` off the value it was handed.
    try {
      validateFindings(findings);
    } catch (error) {
      return refusalReceipt(error);
    }
    // The member parks through the SAME channel every other member uses
    // (`parkChannel`), so the first review's question suspends the body exactly
    // as `this.ask` does (根 §2.4 第 1 步, §3.3 example 7).
    const relay = new ReviewMember({
      ledger: this.ledger,
      port: this.ports.relay,
      park: (site) => this.parkChannel()(site),
    });
    // A Plan at 待执行 hands its SAVED draft to the Manager, and the review
    // carries no text of its own (§2.2): `source` is the bytes the draft port
    // reads at the canonical path, not a note the model wrote. Reading it here,
    // in the composition every host shares, is what makes every host hand over
    // the same bytes; a host that wired its own draft into `review` would
    // otherwise be the only one whose hand-off works.
    //
    // Only an EMPTY review of a Plan AT 待执行 is read: every other review
    // answers without touching the file system, and a hand-off that is not one
    // must not wait for a read. A port that cannot read the draft leaves
    // `source` null, and the ledger answers `DRAFT_MISSING` — the honest
    // refusal, never an empty draft substituted for a real one.
    const awaitingDraft =
      this.ledger?.kind === "plan" &&
      this.ledger?.milestone === PLAN_MILESTONES[PLAN_MILESTONES.length - 1] &&
      Array.isArray(findings) &&
      findings.length === 0;
    const draft = awaitingDraft ? this.readDraft() : null;
    if (draft !== null && typeof draft.then === "function") {
      return draft.then((bytes) => this.#decideReview(relay, findings, bytes));
    }
    return this.#decideReview(relay, findings, draft);
  }

  // The saved draft of this surface's session, or null when no port answers.
  // A promise is returned as it is, so the caller decides whether to wait.
  readDraft() {
    const port = this.draftPort;
    if (port === null || typeof port.read !== "function") return null;
    return port.read();
  }

  // One review, decided and booked. Split from `#reviewMember` so the
  // hand-off's draft read happens first and the decision below stays the single
  // place a receipt is booked.
  #decideReview(relay, findings, source) {
    // The ledger judges BEFORE any await: `relay.review` queues the call, but
    // the decision itself is synchronous, so the refusal (a thrown
    // `CoreError`) is caught here and answered as a receipt rather than as a
    // rejected promise nobody observes.
    let settled = null;
    try {
      settled = relay.review(findings, source, { callId: this.name });
    } catch (error) {
      return refusalReceipt(error);
    }
    // A promise is returned as it is when a side effect must run: the caller
    // awaits it (or not) exactly as before.
    if (settled !== null && typeof settled.then === "function") {
      return settled.then(
        (receipt) => {
          if (receipt?.replayed === true) return receipt;
          return this.noteReview(this.saveLedger(receipt));
        },
        // A refusal that arrived asynchronously is still an ANSWER.
        (error) => refusalReceipt(error),
      );
    }
    if (settled?.replayed === true) return settled;
    return this.noteReview(this.saveLedger(settled));
  }

  get name() {
    return this.programName;
  }

  get role() {
    return this.roleName;
  }

  get program() {
    return this.programState;
  }

  get surfaceName() {
    return surfaceNameFor(this.role);
  }

  // This request's deadline (a core `Deadline`, or null when the host runs
  // without one). `unpark` hands it to the resumed program (spec §3.3).
  get deadline() {
    return this.program.deadline;
  }

  // Park the program. Returns the receipt; the resumed expression gets `value`
  // (explicit park and `where` ambiguity only, decided by core's `resumeValue`).
  park(payload = null, reason = "explicit") {
    if (this.program.terminal) {
      throw invalidArgument(`program ${this.name} is ${this.program.state}`);
    }
    // A second park is refused as a double park, not as a generic state error:
    // the program is suspended on the first site, and a caller needs to know
    // that rather than merely that it is "parked" (spec §3.3, single-shot).
    if (this.program.state === "parked") {
      throw invalidArgument(`program ${this.name} already has a parked site`);
    }
    this.program.park({ reason, payload });
    return this.receipt({ reason, payload });
  }

  // What the members call to suspend the program. Without a host channel the
  // surface parks in place and returns the receipt; with one, the host decides
  // how the body waits (it receives the site and returns the promise the
  // resumed expression gets).
  parkChannel(site) {
    if (typeof this.hostPark === "function") return this.hostPark;
    return (site) => this.park(site?.payload ?? null, site?.reason ?? "explicit");
  }

  // State the host's park channel AFTER the surface was built, or clear it with
  // `null`. A host that learns the channel only once the program exists (the
  // promise a resume settles is registered with the park table, which is built
  // per call) states it here instead of holding a second way to park.
  setParkChannel(park) {
    this.hostPark = typeof park === "function" ? park : null;
    return this;
  }

  // The current view of this program, shaped like a park receipt. `value` is
  // the payload a resumed expression receives; it rides along for the host but
  // is not a tenth field (spec §3.3 names nine).
  receipt({ reason = null, payload = null, value = null, delta = "" } = {}) {
    return withExtra({ ...this.view(delta), reason, payload }, "value", value);
  }

  // The request's deadline ran out (spec §3.3: 一旦到期). The controller and
  // every still-active target program of this request suspend TOGETHER, and only
  // the controller is resumed afterwards; `expireRequest` is that one step, and
  // it lives in compose so every host reaches the same answer.
  //
  // `programs` is the host's list of this request's live programs (the
  // controller included; it is skipped by name). A host that names none expires
  // exactly the controller, which is the narrowest honest reading.
  //
  // `release` is the host's park channel: a body blocked on a member (a
  // command, a host tool) waits on a promise only the host can settle, and
  // settling it with the receipt is what makes the suspension visible to the
  // model instead of a hang. It runs for every program the expiry suspended.
  expire({ programs = [], release = null } = {}) {
    const outcome = expireRequest(this.program, programs);
    const suspended = new Set([...outcome.expired, ...outcome.targets]);
    for (const program of programs ?? []) {
      if (program === null || program === undefined) continue;
      if (!suspended.has(program.name)) continue;
      if (typeof release === "function") release(program);
    }
    return outcome;
  }

  // The receipt this program answers with once its request ran out.
  get expiredReceipt() {
    return expiredReceipt(this);
  }

  // The terminal receipt. The host reads it after `settle` retired the name, so
  // it is the last view of the run and carries the run's result, not null.
  finalReceipt({ reason = null, payload = null, value = null, delta = "" } = {}) {
    return withExtra(this.receipt({ reason, payload, value, delta }), "result", this.program.result);
  }

  // Every receipt is built here, so the default is the whole guarantee: a
  // caller that passes no delta gets "", never a `ReferenceError` (the explicit
  // park path reaches this through `receipt()` with no argument).
  view(delta = "") {
    return programView(this.program, {
      source: this.source,
      command: this.views.get(this.name)?.view() ?? null,
      delta: delta ?? "",
      // What is left of the run's deadline. A park cancelled it, so a parked
      // receipt honestly says zero; a resume reports the new request's rest.
      remainingWaitMs: this.program.remainingWaitMs() ?? 0,
    });
  }

  // Close the run. The host calls this once the body returned or threw:
  // `state` is `completed` or `failed`, `result` is the value or the failure.
  // The name is retired only after the run stops draining, so a command that
  // outlives the body still reports under its own name and the name is not
  // reused while it runs. Returns the promise that settles the close.
  async settle(state, result) {
    if (!this.program.terminal) this.program.finish(state, result);
    await this.program.drainedPromise();
    if (this.registry !== null) this.registry.retire(this.name);
    return this.program;
  }

  // The members object the model receives. Only allowed names are present.
  surface() {
    const allowed = SURFACE_MEMBERS[this.surfaceName];
    const core = this.core();
    const built = {};
    for (const member of allowed) {
      const handler = this.handler(member, core);
      if (handler !== null) built[member] = handler;
    }
    return built;
  }

  // The separate read-only surface of the evaluation phase (spec §2.3). It is
  // not part of `js-manager`; the host opens it for one round at a time, and a
  // shut window is refused here rather than read through.
  //
  // `openRound` is the request boundary, and the host that owns it states it
  // here: the Manager's request is what opens the round (§2.3: the evaluation
  // opens at the round's FIRST request). `false` is the host saying "this call
  // is not a new request", which is how a program of a round already judged
  // stays shut. `null` leaves the decision to this surface: a session window
  // belongs to the host's boundary, and a per-program window is its own.
  reviewSurface({ openRound = true } = {}) {
    // The round opens at the FIRST request (§2.3), and a surface the host built
    // without a session window is a surface whose request is its own: the round
    // begins here, once, and `beginRound()` is idempotent under an open window.
    // A session window belongs to the HOST's request boundary, so a host that
    // owns it opens it (`openRound`) and a host that says this call is not a new
    // request passes `false`.
    if (openRound === true) this.window.beginRound();
    // §2.3: the window is shut unless this round's evaluation is open. The
    // check is here, at the build, so a host that forgot its own gate still
    // cannot read through a closed window: the surface exists or it does not.
    if (this.role !== ROLES.MANAGER) {
      throw new ComposeError(REVIEW_WINDOW_CLOSED, "js-review belongs to the Manager only", {
        role: this.role,
      });
    }
    if (!this.window.open) {
      throw new ComposeError(REVIEW_WINDOW_CLOSED, "the evaluation window is closed", {
        round: this.window.round,
        state: this.window.state,
      });
    }
    const members = reviewMembers({
      root: this.root,
      guard: this.guard,
      // `TextMembers` already hands the full site (`{reason, payload}`); the
      // surface parks exactly that site.
      park: (site) => this.parkChannel()(site),
    });
    const built = {};
    for (const member of SURFACE_MEMBERS["js-review"]) built[member] = members[member];
    return built;
  }

  core() {
    if (this.surfaceName === "js-review") {
      return reviewMembers({
        root: this.root,
        guard: this.guard,
        park: (site) => this.parkChannel()(site),
      });
    }
    const members = coreMembers({
      root: this.root,
      guard: this.guard,
      program: this.program,
      clock: this.clock,
      views: this.views,
      park: (site) => this.parkChannel()(site),
      commandPort: this.ports.command,
    });
    if (this.surfaceName === "plan") {
      // Plan writes one draft and reads one file; no other file members.
      // Before the tenure's first review the draft is read-only (spec §2.2),
      // and a tenure opened by a relay starts read-only again.
      return {
        cat: members.cat,
        write: (path, content) => {
          if (!draftWritable(this.ledger)) {
            throw new ComposeError(
              COMPOSE_CODES.DRAFT_READ_ONLY,
              "the draft is read-only until this tenure's first review",
              { path },
            );
          }
          return members.write(path, content);
        },
      };
    }
    return members;
  }

  handler(member, core) {
    if (member in core) return core[member];
    if (member === "park") {
      // An explicit park suspends the SAME way every other park does (§3.3):
      // through the host's channel when there is one. `this.park(payload,
      // reason)` is the receipt builder — it marks the program parked and
      // returns a view — so calling it here would leave the body running with a
      // receipt instead of a suspension, and nothing would ever resume it. A
      // host without a channel parks in place, which is exactly what
      // `parkChannel` falls back to.
      return (payload) => this.parkChannel()({ reason: "explicit", payload: payload ?? null });
    }
    if (member === "unpark") {
      // `unpark` resumes another named program; the port holds the control
      // surface and the capability sets (core's `ControlSurface.unpark`). The
      // controller's own deadline goes along: the target continues on this
      // request's new deadline, not on the one its park cancelled.
      //
      // The capability check reads the *target* program's own set (spec §3.3);
      // the controller's set is its program's, so it is passed explicitly.
      // Leaving it to the port's default would let a controller resume a
      // program that requires more than it holds.
      return (target, value) =>
        this.ports.control.unpark({
          controllerId: this.name,
          name: target,
          value,
          capabilities: this.program.capabilities,
          deadline: this.program.deadline,
        });
    }
    if (member === "ask") {
      // `ask(question)` IS `this.park({ question })` + `interrupt` (§2.2,
      // §3.3 example 7). It parks on the same channel an explicit park uses,
      // with `reason: "ask"`, and the receipt carries `interrupt: true`: the
      // host must stop the current agent turn so control returns to the
      // operator. The operator's reply comes back as `this.unpark(name,
      // answer)`, which injects it as this expression's value — no separate
      // answer channel exists.
      return (question) => {
        const asked = text("ask(question)", question);
        // `this.ask` exists only in Plan's `S1` (根 §2.2, §3.1). The member is
        // present on every face that could try it, so a call from the wrong
        // role or the wrong stage is a named refusal instead of a TypeError —
        // the model reads which rule it broke.
        const allowed = askAllowed(this.ledger);
        if (!allowed.ok) {
          throw new ComposeError(
            ASK_FORBIDDEN,
            "this.ask is only allowed during Plan milestone S1",
            {
              role: this.role,
              kind: this.ledger?.kind ?? null,
              milestone: this.ledger?.milestone ?? null,
              reason: allowed.reason,
            },
          );
        }
        const parked = this.parkChannel()({ reason: "ask", payload: { question: asked } });
        // The host's asking half (`ports.ask`): it records the question where
        // the operator can read it and answers with the asked receipt. The park
        // is already a fact, so a host that supplies no port still gets the
        // park receipt with `interrupt: true` rather than a failure.
        //
        // The side effect runs in BOTH cases — a host that can show the
        // question to someone must be asked to, whatever shape the park took.
        const port = this.ports?.ask;
        const receipt =
          port !== null && port !== undefined && typeof port.ask === "function"
            ? port.ask(asked, { program: this.name, toolCallId: this.name })
            : parked;
        // A host channel that returns a PROMISE has not parked yet: it is the
        // suspension the body awaits, and `await this.ask(...)` must receive the
        // answer that resumes it (§3.3 example 7). Returning a receipt there
        // would make the body run on as though the question were already
        // answered. Only a channel that parked in place (a sync receipt) is
        // described by the receipt.
        if (parked !== null && typeof parked?.then === "function") {
          // The refusal of a body that never resumes is not an unhandled
          // rejection: the parked program owns that outcome.
          parked.catch?.(() => {});
          return parked;
        }
        return withExtra(receipt, "interrupt", true);
      };
    }
    if (member === "review") {
      // `review(findings)` ANSWERS THE RECEIPT, not a promise for one.
      //
      // The spec's own examples write `const r = review(findings)` and read
      // `r.ok` afterwards, and a member call is allowed to park, so no caller
      // can be assumed to `await`. An async member answers with a promise, and a
      // promise carries no fields until it settles: on the real host every field
      // read off it was `undefined` and the model received `value: {}` where
      // §6.3 requires the refusal's `ok`, `code` and `message` (omp 18.14.2,
      // `/tmp/r7`).
      //
      // The LEDGER decides synchronously (`ReviewLedger.review` either returns
      // its receipt or throws), so the answer exists before this member returns.
      // Only the relay side effect and a Plan's draft read are asynchronous, and
      // a review that performs one of those answers with its promise instead —
      // a body that awaits it still receives the same receipt.
      //
      // A Plan handoff reads the canonical saved draft first (spec §2.2); the
      // review carries no text of its own.
      return (findings) => this.#reviewMember(findings);
    }
    if (member === "fork") {
      // `fork(name, brief)` takes two arguments (§4.1). The worker's identity
      // travels in the brief, and only an explicit declaration in it names one
      // (§2.1); `forkRequest` refuses a brief that names none or both, so no
      // host can fall back to a default role. The port receives the resolved
      // role and never re-derives it.
      //
      // The same member exists on `js-manager` and `js-worker` (§3.1). The port
      // is the CALLING session's own roster, so a worker's children are the
      // worker's and the Manager's `join` never sees them (§4.1). A port that is
      // absent says so at the call instead of at build time.
      return (workerName, brief) => {
        const request = forkRequest(workerName, brief);
        return observed(this.port("fork").fork(request.name, request.brief, request.role));
      };
    }
    if (member === "join") return () => observed(this.port("fork").join());
    if (member === "fission") {
      // The owner is the SESSION, not this program: a program is named by its
      // tool-call id, which never equals the session name, so passing `this.name`
      // refused every real worker with `InvalidOrigin`.
      //
      // The owner is resolved when `fission` is called, not when the surface is
      // built: the session outlives the surface, and a surface that reads the
      // session name at build time would freeze one call's answer into every
      // later one. A port that cannot name its session answers `null`, which
      // the fission layer refuses as `InvalidOrigin`; the program's own name is
      // never used as a stand-in, because no session answers to it.
      return (prompts) => {
        const port = this.port("fission");
        const owner = typeof port.ownerName === "function" ? port.ownerName() : null;
        return observed(port.fission(owner, prompts));
      };
    }
    if (member === "devops") {
      return (intent) => observed(this.port("devops").investigate(text("devops(intent)", intent)));
    }
    if (member === "negotiate" || member === "askPreviousScope") {
      // §6.2: a snapshot query, answered synchronously from the newest
      // committed facts. It never parks and never awaits: the model asks inside
      // a running program and goes on with the answer. The scope port returns
      // core's snapshot, or a refusal when the book is mid-settle
      // (`SCOPE_SNAPSHOT_BUSY`) — the answer is never guessed, and a caller that
      // wants it again simply asks again.
      return () => {
        const answer = this.port("scope").ask(member);
        if (answer === null || typeof answer !== "object") {
          throw new CoreError(CODES.INVALID_ARGUMENT, `the scope port answered no ${member} snapshot`, {
            member,
          });
        }
        // A refusal is a value: `SCOPE_SNAPSHOT_BUSY` while a settle is in
        // flight, `SCOPE_UNINITIALIZED` for a session with no book. It stops the
        // program with its own code, so the model reads the reason instead of
        // planning on a budget nobody confirmed.
        if (answer.ok === false) {
          throw new CoreError(answer.code ?? CODES.INVALID_ARGUMENT, answer.message ?? `no ${member} snapshot`, {
            member,
            refusal: answer,
          });
        }
        return answer;
      };
    }
    throw new CoreError(CODES.INVALID_ARGUMENT, `no implementation for member ${member}`);
  }

  // A port a member needs. `requirePorts` checks the ports a role uses at
  // assembly; this is the same refusal for a surface built without that check,
  // so a missing port is `MISSING_PORT` at the member call instead of a
  // `TypeError` on `undefined`.
  port(name) {
    const port = this.ports?.[name];
    if (port === null || port === undefined || typeof port !== "object") {
      throw new ComposeError(COMPOSE_CODES.MISSING_PORT, `port ${name} is required`, {
        port: name,
      });
    }
    return port;
  }
}
