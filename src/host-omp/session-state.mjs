// Per-session state (spec §3.3, §5, §7, §6.2, §7.3).
//
// OMP creates one extension runner per session and rebinds the extension
// factory to every subagent session (types.ts `ExtensionAgentIdentity`), so this
// module is built once per session from the host context. It owns the facts that
// outlive a single tool call:
//
//   - the program registry and the control surface (`unpark`);
//   - the review ledger for the session's side (Plan or Manager);
//   - the Scope and Pair Hint books.
//
// A parked program's continuation is an in-memory promise. A process restart
// cannot resume it (spec §3.3: an interrupted turn is not replayed), so the
// registry lives in memory. Two facts must survive a restart and are written to
// `.Herdchestra/`:
//
//   - the review ledger, because the relay rules depend on it;
//   - the Pair Hint journal (its own file, owned by `core/pair-hint`), because a
//     cold start must rebuild the same presentation bytes.
//
// Relay. Spec §5.3.2 makes retirement, the context barrier and the successor
// start ONE record, and "中断不会留下半个任期". The record that counts is the
// TENURE's, and only the host can make one: the adapter's own ledger is not it,
// because after a private write the host still believes two sessions hold the
// work — the half-tenure the spec forbids, measured in a file the spec never
// mentions. OMP has no primitive that commits those three session facts as one
// durable record (`newSession` is an in-place reset with no rollback; a separate
// successor session is a second commit; there is no session journal), so this
// adapter performs none of the three parts and writes nothing; the review fails
// with `RELAY_UNAVAILABLE` and the incumbent keeps the tenure.

import path from "node:path";

import { ProgramRegistry } from "../core/program.mjs";
import { ControlSurface } from "../core/control.mjs";
import { CoreError, CODES } from "../core/errors.mjs";
import { ReviewLedger, ReviewWindow, PLAN_MILESTONES, MANAGER_MILESTONES, REVIEW_KINDS } from "../core/review.mjs";
import { systemClock } from "../core/clock.mjs";
import { expireRequest } from "../compose/deadline.mjs";
import { ScopeHooks } from "../core/scope/hooks.mjs";
import { Admission } from "../core/scope/admission.mjs";
import { DEFAULT_EXEMPT_MEMBERS } from "../core/scope/errors.mjs";
import { PairHint, REBASE_REASONS, REQUEST_KINDS } from "../core/pair-hint/pair-hint.mjs";
import { RECORD_KINDS } from "../core/pair-hint/facts.mjs";
import { messageDigest } from "../core/pair-hint/chain.mjs";
import { resolveLanguage } from "../core/pair-hint/language.mjs";
import { projectionBoundary, shouldAnchor } from "../compose/request-boundary.mjs";
import { readJson, readJsonOrError, writeJson, ensureDir, appendJsonLine, readJsonLines } from "./files.mjs";
import { stateDir, safeSessionName, PLAN_DRAFT_DIR } from "./host-facts.mjs";
import { isSideChannelRequest, toPairHintMessages } from "./request.mjs";
import {
  RELAY_RECORD_TYPE,
  RELAY_PENDING_TYPE,
  RELAY_LEDGER_TYPE,
  fenceOf,
  isPendingPayload,
  isRelayPayload,
  pendingPayload,
  recordGoverns,
  relayPayload,
  successionNotice,
} from "./relay-record.mjs";

// Version 1 is the only shape this adapter writes: ledger facts only. No
// hand-over record, no fence (this adapter never hands a tenure over; see
// `handOver`).
// The Scope annotation surface of this host.
//
// Spec §6.2: every tool call carries a flat `scope` annotation, and an absent
// annotation is refused at admission for any non-exempt tool. §6.2 names the
// only exempt members — `negotiate` and `askPreviousScope`, both synchronous
// snapshot queries — and leaves the rest to the host's judgement, which is not a
// licence to exempt whole classes: exempting every host tool would let calls
// pass without ever touching the budget, which is exactly what the budget is
// for. So this adapter exempts only those two and annotates everything else.
//
// The host tools this adapter registers (`fork`, `join`) carry `scope` in their
// own parameter schema, because the model has to be able to write it: the host
// gate reads `input.scope` before the tool runs. Host tools the adapter does not
// own (`read`, `write`, `bash`, MCP tools) cannot be given a schema here, so a
// call to one of them that arrives without an annotation is still refused —
// that is the spec's rule, not a gap this adapter may paper over.
export const hostExemptMembers = () => Object.freeze([...DEFAULT_EXEMPT_MEMBERS]);

const LEDGER_FILE_VERSION = 1;

export class SessionState {
  #sessionId;
  #role;
  #programs;
  #control;
  #ledger;
  #root;
  #file;
  #parked;
  #books;
  #window;
  #draftDirFailure = null;
  // The active relay fence (`null` outside a succession) and whether this
  // transcript already carries the record for the current tenure.
  #fence = null;
  #relayCommitted = false;
  // A relay the incumbent asked for while its own turn was live. It is an
  // INTENT, not a tenure: nothing is retired and no successor exists until the
  // commit runs at the idle boundary (`relay-record.mjs` `RELAY_PENDING_TYPE`).
  #relayPending = null;
  // Why the last deferred attempt could not run (a defect, not a busy session).
  #relayFailure = null;
  // The session id the relay journal is named after: the lineage, fixed on the
  // first adoption so a successor's tenure writes and reads the same file.
  #relayLineage = null;
  // The host's own version, as the binding saw it, or null. Recorded beside a
  // request so a drift report names the host it happened on: the 2026-11-01
  // incident needed a hand-read of the host database partly because no durable
  // record said which omp was running.
  #hostVersion = null;
  // The question this tenure asked in Plan's S1, or null (`nudge.mjs`).
  #askedInS1 = null;
  // The request in flight (see `requestRefusal`), and this session's sequence of
  // request ids. The id is what a verdict speaks for; the host gives none.
  #requestId = null;
  #requestSeq = 0;
  // The last settled verdict this process produced, and the `previous` verdict
  // the in-flight request must carry. They are held in memory because the file
  // is now written only when the VERDICT changes: an unchanged "allowed" across
  // two requests costs no write, and the turn boundary flushes the request id.
  #lastVerdict = null;
  #gatePrevious = null;

  constructor({ sessionId, role, root }) {
    this.#sessionId = sessionId;
    this.#role = role;
    this.#root = root;
    this.#programs = new ProgramRegistry();
    this.#control = new ControlSurface(this.#programs);
    this.#parked = new Set();
    this.#books = new Map();
    // The root this state was CONSTRUCTED with, never `projectRoot()`. Reading
    // the environment here made the bound root a lie: a caller that passed a
    // root (a test, or any session whose working directory differs from the
    // project root) still wrote to whatever `process.cwd()` happened to be.
    ensureDir(stateDir(this.#root));
    this.#file = `${stateDir(this.#root)}/${safeName(sessionId)}.session.json`;
    this.#ledger = restoreLedger({ file: this.#file, role });
    // A ledger written after a committed relay carries the fence and the
    // successor tenure. Reading it here means a session whose transcript was
    // replaced by the reset still filters its requests; the transcript record
    // (`adoptRelayRecord`) overrides this when the two disagree.
    const savedFence = readJson(this.#file, null)?.fence ?? null;
    if (savedFence !== null && typeof savedFence === "object" && typeof savedFence.notice === "string") {
      this.#fence = {
        notice: savedFence.notice,
        successor: Number.isInteger(savedFence.successor) ? savedFence.successor : this.#ledger.tenure,
        fenceThrough: Number.isInteger(savedFence.fenceThrough) ? savedFence.fenceThrough : 0,
      };
    }
    // Plan's draft directory (root §1.1 item 3): the workspace-root `plans/`,
    // never `.Herdchestra`. The draft is the one file a Plan writes, and its
    // parent must exist BEFORE the path is advertised: a fresh Plan
    // whose first `write` met `ENOENT` would see a failure it cannot diagnose,
    // and the hand-off would later read an empty draft for the same reason.
    //
    // The failure is recorded rather than thrown. A session that cannot prepare
    // its draft directory is still a session — it must be able to SAY so, so the
    // refusal reaches the model instead of the process dying at construction.
    // A session is a Plan by its ledger's side, not only by the string its
    // caller passed: `SessionState` is constructed with the default Manager role
    // and adopts Plan later, so the draft directory must follow the LEDGER. A
    // Plan that advertised its draft under a directory nobody created would
    // hand the model an ENOENT it cannot diagnose.
    if (this.#ledger.kind === "plan" || role === "plan") {
      try {
        ensureDir(path.join(this.#root, PLAN_DRAFT_DIR));
        this.#draftDirFailure = null;
      } catch (error) {
        this.#draftDirFailure = {
          // A filesystem error carries its own code; anything else (a bug, a
          // missing binding) is reported under its own name instead of being
          // filed as an I/O failure that hides it.
          code: typeof error?.code === "string" ? error.code : String(error?.name ?? "EIO"),
          detail: String(error?.message ?? error),
        };
      }
    }
    // The evaluation window (§2.3) of this session's side. It is not persisted:
    // a window describes one round of one process, and a restart starts a round
    // that nobody has evaluated yet.
    this.#window = new ReviewWindow({ kind: role === "manager" ? "manager" : "plan" });
  }

  get sessionId() {
    return this.#sessionId;
  }

  // The identity that survives a relay: the FIRST id this state knew (§2.4: a
  // relay changes the participant's transcript, not the participant). Before
  // any adoption it is the live id, so a session that never relayed answers
  // exactly what `sessionId` does.
  //
  // Anything a Plan writes and later reads back must be named by THIS, not by
  // the live id. The draft is the case that matters: `adoptSessionId` moves
  // `#sessionId` to the successor's, so a draft path derived from the live id
  // named a different file after the first relay — the written draft was never
  // found again, and every hand-off after a relay was refused `DRAFT_MISSING`.
  get lineageId() {
    return this.#relayLineage ?? this.#sessionId;
  }

  // Adopt the id of the transcript now judging.
  //
  // A relay's successor keeps the participant but opens a NEW transcript, and
  // the host's durable header for it names its own id. The manager may still
  // answer with the outgoing id until it re-reports, so the record (published
  // atomically, and the copy a restart reads) is the authority. Only a string
  // the record carries is adopted; nothing is invented.
  adoptSessionId(sessionId) {
    if (typeof sessionId !== "string" || sessionId.length === 0) return this.#sessionId;
    // The FIRST id this state knew is the lineage: a relay adopts the
    // successor's id afterwards, and the relay journal must stay under the
    // lineage name so every tenure of this session shares one file.
    if (this.#relayLineage === null) this.#relayLineage = this.#sessionId;
    this.#sessionId = sessionId;
    // The ledger FILE follows the id. It was derived once in the constructor,
    // so after a relay the successor's tenure was written into the INCUMBENT's
    // file: `.Herdchestra/<successor>.session.json` never existed, and the
    // successor tenure's ledger lived only in the transcript record. Measured
    // live (omp 18.14.2): after the relay plus three resumes of the successor,
    // only the incumbent's `.session.json` was on disk.
    this.#file = `${stateDir(this.#root)}/${safeName(sessionId)}.session.json`;
    return this.#sessionId;
  }

  get role() {
    return this.#role;
  }

  get root() {
    return this.#root;
  }

  get hostVersion() {
    return this.#hostVersion;
  }

  // Called once, by the binding, when the host reports its version.
  adoptHostVersion(version) {
    if (typeof version === "string" && version.length > 0) this.#hostVersion = version;
    return this.#hostVersion;
  }

  // `null` when the Plan's draft directory is ready, otherwise `{code, detail}`.
  // A non-null answer means no draft can be written, so no hand-off can read
  // one; the port and the write member report it instead of letting an
  // undiagnosable ENOENT surface at the first `write`.
  get draftDirFailure() {
    return this.#draftDirFailure;
  }

  get programs() {
    return this.#programs;
  }

  get control() {
    return this.#control;
  }

  get ledger() {
    return this.#ledger;
  }

  // Whether this tenure asked the operator a question in Plan's S1, and is
  // therefore WAITING for the answer rather than finished. It is the one legal
  // stop a Plan has besides its hand-off (`nudge.mjs`), so it is a fact the
  // session keeps rather than something the stop rule infers from text.
  //
  // The fact is set by `ask` itself (`markAskedInS1`) and cleared when the
  // answer resumes the program: a resumed question is no longer a reason to
  // stay quiet, and a second stop after it must be nudged like any other.
  get askedInS1() {
    return this.#askedInS1;
  }

  markAskedInS1(question) {
    this.#askedInS1 = { at: new Date().toISOString(), question: String(question) };
    return this.#askedInS1;
  }

  clearAskedInS1() {
    this.#askedInS1 = null;
    return this.#askedInS1;
  }

  // The evaluation window of this session (§2.3): open from a round's first
  // request until that round's first accepted review.
  get window() {
    return this.#window;
  }

  get file() {
    return this.#file;
  }

  // One observation ledger per request (spec §3.3). The host opens it when a
  // program tool call starts and closes it when the call returns.
  openRequest(controllerId) {
    return this.#control.open(controllerId);
  }

  closeRequest(controllerId) {
    this.#control.close(controllerId);
  }

  observe(controllerId, name) {
    return this.#control.observe(controllerId, name);
  }

  // Resume a parked program.
  //
  // Core §3.3 asks one question — does the controller hold every capability the
  // TARGET carries? — and answers it from the target's own set
  // (`core/control.mjs` `unpark`). Both programs of this session are registered
  // with the role their face runs as (`surface.mjs` `buildSurface`), so the
  // controller's set is its own role, and the check passes exactly when the two
  // programs belong to this session under one role.
  //
  // `targetCapabilities` is NOT passed. It is an override for a host that
  // mirrors the target's set elsewhere, and core now compares it with the
  // target's real set; a controller that stated its own set there was refused
  // `CAPABILITY_DENIED` even when it held exactly what the target needed.
  // Leaving it `null` means "read the target's set", which is the only correct
  // answer available here.
  //
  // An expired CONTROLLER cannot be resumed into a new request (根 §3.3: 一旦
  // 到期，控制者与仍在活动的目标程序一同转入 `parked`，随后只恢复控制者). The
  // controller's own deadline is what says so: a request that ran out is over,
  // so the resume is refused with the receipt of the state that exists — and the
  // programs still active are parked with it, because their request ended too.
  // A controller with no deadline, or with time left, resumes exactly as before.
  unpark(controllerId, name, value = null, deadline = null) {
    const controller = this.#programs.get(controllerId);
    if (controller !== null && controller !== undefined && controller.deadline !== null && controller.deadline.expired) {
      expireRequest(controller, this.#programs.list());
      throw new CoreError(
        CODES.STALE_CONTROL,
        `controller ${controllerId}'s request expired; it must be resumed by a later request`,
        { name: controllerId, expired: true },
      );
    }
    return this.#control.unpark({
      controllerId,
      name,
      value,
      deadline,
      capabilities: [this.#role],
    });
  }

  noteParked(name) {
    this.#parked.add(name);
  }

  noteFinished(name) {
    this.#parked.delete(name);
    this.#programs.retire(name);
  }

  parkedNames() {
    return [...this.#parked].sort();
  }

  // Hand this tenure over (root §2.4 第 3 步).
  //
  // The port commits retirement, the barrier and the successor start as ONE
  // durable record (see `relay-record.mjs` for the primitive and its crash
  // ordering). This method owns the LEDGER side of the same step: the record is
  // written only after the host accepted the commit, and the successor is
  // opened only after the record exists. Anything short of that is a refusal
  // that leaves the tenure, the milestone and both files exactly as they were.
  async handOver(port = null, decision = null) {
    if (port === null || typeof port?.handOver !== "function") {
      return {
        ok: false,
        retired: false,
        barrier: false,
        successor: null,
        reason: "NO_SESSION_CONTROL",
        detail: "this session exposes no relay port; retirement, barrier and successor cannot land as one record",
        milestone: this.#ledger.milestone,
        tenure: this.#ledger.tenure,
      };
    }
    // The decision's own fields are carried through: `findings` is what a
    // deferred intent must record, and the three flags are what the ledger's
    // receipt asked for. They are fixed here rather than taken from the caller,
    // because a relay is always all three.
    const done = await port.handOver({
      retire: true,
      openSuccessor: true,
      barrier: true,
      findings: decision?.findings ?? null,
    });
    const committed =
      done !== null &&
      typeof done === "object" &&
      done.ok === true &&
      done.retired === true &&
      done.barrier === true &&
      done.successor !== null &&
      done.successor !== undefined;
    if (!committed) {
      // Nothing landed: the port commits all three parts or none, so a partial
      // answer is the host's own refusal and must not advance the ledger.
      return {
        ...(done ?? {}),
        ok: false,
        retired: done?.retired === true,
        barrier: done?.barrier === true,
        successor: null,
        milestone: this.#ledger.milestone,
        tenure: this.#ledger.tenure,
      };
    }
    // The ledger's successor is opened by the composition layer
    // (`compose/relay/review.mjs` calls `openSuccessor` once, after this port
    // reports a complete hand-over), so it must NOT be opened again here: a
    // second call would advance the tenure twice for one relay.
    //
    // What this side owns is the FENCE the record establishes. The port wrote
    // it into the durable record; installing it here is what makes the very
    // next request a successor's request, in the same process.
    //
    // Only the FENCE is installed. The tenure is advanced by the composition
    // layer right after this port returns (`openSuccessor`), so touching the
    // ledger here would open a second successor for one relay.
    //
    // The fence is rebuilt from the record the port PUBLISHED, never from a
    // fresh computation. `done.record` is the payload as written; reading
    // `fenceThrough` with a `0` default (the earlier bug) installed a fence
    // that hid nothing, while the transcript carried the real count — so the
    // same successor saw a different request before and after a restart.
    const record = done.record ?? null;
    if (record === null) {
      // A port that reports a complete hand-over without the record it wrote
      // cannot be trusted to describe the barrier. Refuse instead of installing
      // a fence nobody can verify.
      return {
        ok: false,
        retired: true,
        barrier: false,
        successor: null,
        reason: "RELAY_RECORD_MISSING",
        detail: "the hand-over reported success without the record it published; no fence is installed",
        milestone: this.#ledger.milestone,
        tenure: this.#ledger.tenure,
      };
    }
    // The fence the successor's requests are filtered by. It is the count the
    // RECORD carries, which for a replaced transcript is 0 — the successor's
    // file starts empty, and a count from the retired file would claim
    // boundaries that file never had. `fenceOf` reads exactly that field, so
    // every path (synchronous, boundary, cold start) arms the same fence.
    this.armFence(record);
    this.#relayCommitted = true;
    // The ledger belongs to the SUCCESSOR now, and so does its file. This must
    // happen BEFORE `saveLedger()`: `#file` is derived from the session id, and
    // the manager still answers with the incumbent's id at this instant, so
    // saving first wrote the successor's tenure and fence into
    // `.Herdchestra/<incumbent>.session.json` and never created the
    // successor's own file (measured live, omp 18.14.2: after the relay and
    // three resumes of the successor, only the incumbent's `.session.json`
    // existed). The record names the successor, and the record is the durable
    // fact, so the id comes from there.
    this.adoptSessionId(record.successorSessionId);
    // The ledger file describes the tenure that is now judging, and carries the
    // fence so a cold start rebuilds the same barrier.
    this.saveLedger();
    // The barrier's strength is a FACT, not a constant. It is complete when the
    // transcript was really replaced: the successor then reads from an empty
    // file and the fence's count keeps everything out. When the switch did not
    // happen (the session went busy, or a handler cancelled it), the incumbent's
    // conversation is still the live transcript and the fence is what hides it —
    // so the barrier stands, but `transcriptReplaced` says the successor is
    // still answering from the old file. Reporting `replaced` as success would
    // be the false claim §2.4 第 3 步 forbids.
    return {
      ...done,
      ok: true,
      retired: true,
      barrier: true,
      transcriptReplaced: done.successor?.replaced === true,
      switchFailure: done.successor?.switchFailure ?? null,
      milestone: this.#ledger.milestone,
      tenure: this.#ledger.tenure,
    };
  }

  // The active relay fence, or null. The `context` hook reads it on every
  // request: a non-null fence is what keeps the previous tenure's conversation
  // out of a successor's request copy (§2.4 第 4 步).
  get fence() {
    return this.#fence;
  }

  // Whether the request the host is about to send was actually projected.
  //
  // Why this is a gate and not a notice. OMP gives a `context` handler no way
  // to refuse a request: a throw is reported to the operator and the host keeps
  // the copy it built (measured on 18.14.2, AGENTS.md §8.1 a). For a successor
  // that copy is the retired tenure's whole conversation, so a projection that
  // failed silently hands it back — the barrier is gone and nothing on the
  // request says so. Writing a notice into the copy is not enough either: the
  // pre-relay messages would still be in it.
  //
  // ## The verdict belongs to a REQUEST, not to the session and not to a turn
  //
  // The first version of this gate was one session-wide file, moved to
  // "refused" at the start of every `context` call and settled when the copy was
  // decided. That refused tool calls for requests that had projected: a
  // side-channel title request, or the next request's projection, put the file
  // to "refused" while a call from the LAST good request was arriving. By the
  // time anybody looked the next request had settled `allowed: true` and the
  // refusal had left no trace (reported incident: turn 1, 54 tool results, 233 s).
  //
  // The turn is not the right key either. OMP emits `turn_start`/`turn_end`
  // through a queue that is drained asynchronously, and the observed order on
  // 18.14.2 is `turn_start 0`, `turn_end 0`, then turn 0's first `context`
  // (measured on this incident). A verdict keyed by the host's turn number
  // therefore lands in a turn that has already "ended".
  //
  // What the host DOES order is the request itself, and this adapter owns it:
  //
  //   openContextRequest()   the request starts: a new REQUEST ID is minted and
  //                          its verdict is "refused" until the copy is decided.
  //   settleContextRequest() the copy was decided: allow, or keep the refusal
  //                          and name the projection that failed.
  //   verdictForCall()       a tool call is judged by the last SETTLED request.
  //
  // A `tool_call` can only exist for a response the provider produced, and a
  // response only exists after its request was projected and sent. So "the last
  // settled request" IS the request that issued the call, and a projection that
  // is merely in flight for another request cannot speak for it. A call arriving
  // while NO request has settled is refused: nothing can have issued it.
  //
  // Live steering (`agent-loop.ts` `openLiveSteering.toProvider`) runs the
  // `context` hook again for the SAME request while its response streams. That
  // second projection opens a new request id, so the first id's settled verdict
  // is preserved as `previous` and still answers calls until the new one
  // settles. A steering projection that fails therefore refuses only the calls
  // that follow it, never the ones already in flight.
  //
  // Fail-closed is unchanged: an unreadable file, an unknown request, or a
  // request whose verdict is a failure are all refusals, and each is recorded.
  get requestGate() {
    // A file that exists but cannot be read is NOT "no verdict". Reading it as
    // `null` would open the gate on a broken disk, which is the one direction a
    // safety fact must never fail in. Only a genuinely absent file means "no
    // verdict yet" (this session never projected a request).
    //
    // A torn or hand-edited file is the same case: `readJson` answers its
    // fallback for text it cannot parse, and the fallback here is a REFUSAL, not
    // `null`. A file whose verdict cannot be believed is a file that says no.
    const unreadable = {
      v: 3,
      // A file that cannot be believed is its own settled refusal: it names
      // itself rather than reading as "a request is still pending", which would
      // tell a reader the wrong thing about a broken disk.
      settled: true,
      allowed: false,
      stage: "request",
      code: "REQUEST_GATE_UNREADABLE",
      detail: "the request gate file could not be read",
    };
    // `readJsonOrError`, not `readJson`: the latter answers `null` for both
    // "absent" and "unparsable", which would read a torn verdict as "no verdict
    // yet" — the open direction.
    const read = readJsonOrError(this.#gateFile());
    if (!read.ok) return unreadable;
    if (read.value === null) return null;
    if (typeof read.value !== "object" || Array.isArray(read.value)) return unreadable;
    // A file written by an older model (a session-wide verdict, or one keyed by
    // the host's turn) cannot say which request it speaks for, so it is read as
    // a refusal of whatever request asks next — never as an open gate.
    if (read.value.v !== 3) {
      return { ...unreadable, code: "REQUEST_GATE_UNKEYED", detail: "the request gate file predates per-request verdicts" };
    }
    // The SHAPE is part of the safety fact. A hand-edited or foreign-schema
    // gate such as `{v:3, settled:true, allowed:"no"}` used to read as
    // projected, because only `allowed === false` refused. A verdict this
    // adapter cannot believe is a refusal, never an open gate.
    const verdict = read.value;
    if (
      typeof verdict.settled !== "boolean" ||
      typeof verdict.allowed !== "boolean" ||
      typeof verdict.request !== "string" ||
      verdict.request.length === 0
    ) {
      return { ...unreadable, code: "REQUEST_GATE_UNREADABLE", detail: "the request gate file carries no believable verdict" };
    }
    if (verdict.previous !== null && verdict.previous !== undefined) {
      const previous = verdict.previous;
      if (
        typeof previous !== "object" ||
        Array.isArray(previous) ||
        typeof previous.request !== "string" ||
        previous.request.length === 0 ||
        typeof previous.allowed !== "boolean"
      ) {
        return {
          ...unreadable,
          code: "REQUEST_GATE_UNREADABLE",
          detail: "the request gate file carries no believable previous verdict",
        };
      }
    }
    return read.value;
  }

  // The id of the request in flight, or null when none has started.
  get requestInFlight() {
    return this.#requestId;
  }

  // The request has started. `id` is the caller's token when it has one (so a
  // retried call can be told apart from a new request), otherwise the next
  // number of this session's sequence.
  //
  // NOTHING is written here (root §1.1 item 1). The file carries the last
  // SETTLED verdict, which is the verdict a tool call is judged by; a request
  // that is still projecting cannot have issued one. Writing "refused" at every
  // open and "allowed" at every settle was two whole-file rewrites per request
  // for a verdict that usually did not change. Crash safety is unchanged: an
  // absent file refuses (`requestRefusal`), and a stale verdict is the last
  // request that really completed, whose response is the only thing that could
  // have issued a surviving call.
  //
  // The in-flight request carries `previous`: the id and verdict of the last
  // SETTLED request. That is what keeps a call from the response already
  // streaming admissible while a steering projection for the same request is in
  // flight.
  openContextRequest(id = null) {
    // The sequence continues from the file, not from this object: a restart
    // starts at zero, and an id minted there would collide with the one on disk
    // and be mistaken for the request already recorded.
    const settled = this.requestGate;
    const onDisk = Number.parseInt(settled?.request ?? "", 10);
    if (Number.isInteger(onDisk) && onDisk > this.#requestSeq) this.#requestSeq = onDisk;
    const next = typeof id === "string" && id.length > 0 ? id : String(this.#requestSeq + 1);
    this.#requestSeq += 1;
    this.#requestId = next;
    this.#gatePrevious =
      settled === null ||
      typeof settled.request !== "string" ||
      settled.request.length === 0 ||
      settled.request === next ||
      settled.settled !== true
        ? null
        : { request: settled.request, allowed: settled.allowed === true, stage: settled.stage ?? null, code: settled.code ?? null, detail: settled.detail ?? null };
    return true;
  }

  // The request was decided. `failure` is `null` when every projection ran, or
  // `{stage, code, detail}` for the one that did not.
  //
  // The file is written ONLY when the verdict changes. The answer says whether
  // it changed (`changed`) and whether the write landed (`ok`), so the caller
  // can record a diagnostic exactly when a reader would see a different verdict
  // — and a write that fails still fails closed, because the caller holds the
  // refusal in memory.
  settleContextRequest(failure = null, id = null) {
    const request = typeof id === "string" && id.length > 0 ? id : this.#requestId;
    if (request === null) return false;
    this.#requestId = request;
    const verdict =
      failure === null
        ? { allowed: true, stage: null, code: null, detail: null }
        : {
            allowed: false,
            stage: typeof failure.stage === "string" ? failure.stage : "request",
            code: typeof failure.code === "string" ? failure.code : "CONTEXT_PROJECTION_FAILED",
            detail: String(failure.detail ?? ""),
          };
    this.#lastVerdict = { request, ...verdict };
    const onDisk = this.requestGate;
    const unchanged =
      onDisk !== null &&
      onDisk.settled === true &&
      onDisk.allowed === verdict.allowed &&
      (onDisk.stage ?? null) === verdict.stage &&
      (onDisk.code ?? null) === verdict.code &&
      (onDisk.detail ?? null) === verdict.detail;
    if (unchanged) return { ok: true, changed: false, written: false };
    const written = this.#writeGate({ request, settled: true, previous: this.#gatePrevious, ...verdict });
    return { ok: written, changed: true, written };
  }

  // Persist the latest request id at a turn/agent boundary, without writing on
  // every request. The verdict is already on disk whenever it changed; this only
  // refreshes the request id a reader uses to match the line to a transcript.
  // It never changes a verdict, and a failed write leaves the previous (equally
  // valid) verdict in place.
  flushContextGate() {
    if (this.#lastVerdict === null) return true;
    const onDisk = this.requestGate;
    if (onDisk !== null && onDisk.request === this.#lastVerdict.request && onDisk.settled === true) return true;
    return this.#writeGate({
      request: this.#lastVerdict.request,
      settled: true,
      previous: null,
      allowed: this.#lastVerdict.allowed,
      stage: this.#lastVerdict.stage,
      code: this.#lastVerdict.code,
      detail: this.#lastVerdict.detail,
    });
  }

  // A refusal that stops the tool calls of the request in flight, in the shape
  // the gate hook answers. `null` when the calls may run.
  //
  // The LAST SETTLED request answers. A request still projecting cannot speak
  // for a call it cannot have issued; the previous settled one can, because the
  // call came from its response.
  requestRefusal() {
    const gate = this.requestGate;
    if (gate === null) {
      return {
        code: "CONTEXT_PROJECTION_FAILED",
        message:
          "no request has been projected in this session (CONTEXT_PROJECTION_UNKEYED); this session cannot run work until a request projects",
      };
    }
    const settled = gate.settled === true ? gate : gate.previous ?? null;
    if (settled === null) {
      return {
        code: "CONTEXT_PROJECTION_FAILED",
        message: `no request has finished projecting (request ${String(gate.request)} is still open: CONTEXT_PROJECTION_PENDING); this session cannot run work until a request projects`,
      };
    }
    // ONLY an explicit `allowed === true` opens the gate. A missing or
    // non-boolean value is not a pass: this is a safety fact, and every other
    // answer refuses.
    if (settled.allowed !== true) {
      const stage = typeof settled.stage === "string" ? settled.stage : "request";
      const code = typeof settled.code === "string" && settled.code.length > 0 ? settled.code : "CONTEXT_PROJECTION_FAILED";
      const detail = typeof settled.detail === "string" && settled.detail.length > 0 ? `: ${settled.detail}` : "";
      return {
        code: "CONTEXT_PROJECTION_FAILED",
        message: `the ${stage} projection of request ${String(settled.request)} failed (${code}${detail}); this session cannot run work until a request projects`,
      };
    }
    return null;
  }

  // Whether the verdict now on disk is the one that was asked for. The gate is a
  // safety fact, so a write that does not land must not look like one that did:
  // the caller treats `false` as "the gate could not be updated" and refuses the
  // work. A read-only or full disk therefore fails CLOSED — the previous verdict
  // stays (or, with no file at all, `requestRefusal` refuses), and the hook holds
  // the refusal in memory.
  #writeGate(value) {
    const target = this.#gateFile();
    try {
      // `writeJson` answers a result and does not throw; ignoring it would read
      // a failed write as a successful one.
      return writeJson(target, { v: 3, sessionId: this.#sessionId, ...value })?.ok === true;
    } catch {
      return false;
    }
  }

  #gateFile() {
    return `${stateDir(this.#root)}/${safeName(this.#sessionId)}.request.json`;
  }

  // The notice the successor's first request carries in front of the operator's
  // input, or null when no fence is active.
  successionNotice() {
    return this.#fence === null ? null : this.#fence.notice;
  }

  // Rebuild the fence and the successor tenure from a relay record found in the
  // session's transcript.
  //
  // This is the cold-start half of the commit. The record is the durable fact;
  // the ledger file is a convenience that may be older than it (a crash between
  // the commit and the ledger write, or a transcript that outlived its
  // `.Herdchestra` mirror). When the two disagree, the RECORD wins: it is the
  // one the host published atomically.
  //
  // `record` is `{ tenure, milestone, fenceThrough, notice }`, already decoded
  // and trusted (`relay-record.mjs` `isRelayPayload`).
  //
  // The RECORD is adopted in two separable steps, and they are separable for a
  // measured reason (omp 18.14.2, `/tmp/hc-devops-final/relay-turn1`):
  //
  //   - the LEDGER (`adoptLedgerRecord`) says a record exists for a successor
  //     tenure. It is safe at any moment: it claims nothing about which
  //     requests may be shown.
  //   - the FENCE (`armFence`) decides what a request may carry. Arming it
  //     inside the turn that published the record stripped the Manager's own
  //     conversation out of its next request in that same turn, so it never
  //     learned it had already forked: 116 `fork` calls in one turn, and the
  //     run never ended. The barrier belongs to the SUCCESSOR's requests, so it
  //     is armed at the transcript switch (`armFence`), not at the publish.
  //
  // `adoptRelayRecord` keeps its old meaning for cold starts: a record read
  // from a transcript means the barrier applies from the first request.
  adoptRelayRecord(record) {
    this.adoptLedgerRecord(record);
    this.armFence(record);
    return { tenure: this.#ledger.tenure, milestone: this.#ledger.milestone };
  }

  // The ledger half only: the successor tenure and the milestone the record
  // names, with nothing said about the request barrier.
  adoptLedgerRecord(record) {
    const milestones = MILESTONES[this.#ledger.kind] ?? [];
    const milestone = milestones.includes(record.milestone) ? record.milestone : this.#ledger.milestone;
    this.#ledger.adoptSuccessor({ tenure: record.tenure, milestone });
    this.#relayCommitted = true;
    return { tenure: this.#ledger.tenure, milestone: this.#ledger.milestone };
  }

  // The barrier half: the fence this tenure's requests are filtered by. It is
  // armed when the transcript switches, because until then the live requests
  // belong to the tenure that published the record.
  armFence(record) {
    this.#fence = fenceOf(record);
    return this.#fence;
  }

  // Whether a relay record exists for a successor tenure while this tenure is
  // still the one answering. `context` uses it to say so, once, instead of
  // silently restarting the model on a request it cannot recognise.
  get relayOwed() {
    return this.#relayCommitted && this.#fence === null;
  }

  // The newest relay record this session's own transcript carries, or null.
  //
  // Read through the host's session manager (`getEntries`), never by parsing
  // the JSONL here: the host owns its format, and this adapter only reads the
  // custom entries it wrote itself. A record whose payload this version does
  // not trust is ignored rather than half-applied (`isRelayPayload`).
  relayRecordIn(sessionManager) {
    if (sessionManager === null || typeof sessionManager?.getEntries !== "function") return null;
    let entries;
    try {
      entries = sessionManager.getEntries();
    } catch {
      return null;
    }
    if (!Array.isArray(entries)) return null;
    for (let index = entries.length - 1; index >= 0; index -= 1) {
      const entry = entries[index];
      if (entry === null || typeof entry !== "object") continue;
      if (entry.type !== "custom" || entry.customType !== RELAY_RECORD_TYPE) continue;
      if (!isRelayPayload(entry.data)) return null;
      // A record that names a DIFFERENT session governs that session's
      // transcript, not this one. The incumbent's file keeps the pre-switch
      // record forever (the reset never rewrites it), so without this check a
      // resume of the old file would adopt a tenure it handed away.
      //
      // The transcript this state writes to is the third fact: the pre-switch
      // record names the session id the manager still reports at commit time,
      // so the id alone cannot separate the two copies.
      if (!recordGoverns(entry.data, this.#sessionId, this.#transcriptFile(sessionManager))) continue;
      // The PRE-SWITCH copy marks itself (`retiredTranscript: true`). Adopting it
      // is the crash-recovery half of §2.4 第 3 步: the record is durable and the
      // reset never ran, so this transcript still holds the incumbent's
      // conversation and the record's fence is what hides it. What must never
      // happen is adopting it once the reset DID run — the successor's file
      // carries its own copy, which the loop reaches first.
      return entry.data;
    }
    return null;
  }

  // The transcript the host reports for this session, or null when it reports
  // none. Read here rather than from `ctx`: the ledger and the transcript are
  // two different files, and only the host knows which one is live.
  #transcriptFile(sessionManager) {
    const read = sessionManager?.getSessionFile;
    if (typeof read !== "function") return null;
    try {
      const file = read.call(sessionManager);
      return typeof file === "string" && file.length > 0 ? file : null;
    } catch {
      return null;
    }
  }

  // A relay asked for during a live turn, waiting for the idle boundary.
  get relayPending() {
    return this.#relayPending;
  }

  // Record the intent. Durable first: the entry is published through the same
  // atomic path the record uses, so a crash inside the turn still leaves the
  // obligation on disk and a restart resumes it instead of forgetting it.
  // `record` is the relay record PREPARED in the live turn (`relay-record.mjs`
  // `RECORD_FIELD`). It travels inside the intent because the turn is the last
  // writable moment on this host; the idle boundary publishes it in its own
  // right before it touches the transcript.
  async noteRelayPending({ findings, commitPending, record = null }) {
    const payload = pendingPayload({
      tenure: this.#ledger.tenure,
      milestone: this.#ledger.milestone,
      findings,
      at: Date.now(),
      record,
    });
    const written = await commitPending(payload);
    if (written?.ok !== true) {
      return { ok: false, detail: written?.detail ?? "the pending intent was not recorded" };
    }
    this.#relayPending = { ...payload, id: written.id ?? null };
    return { ok: true, id: written.id ?? null };
  }

  // The newest pending intent this session's transcript carries, or null.
  // Read the same way as the record: only our own `customType`, only a payload
  // this version trusts.
  pendingRelayIn(sessionManager) {
    if (sessionManager === null || typeof sessionManager?.getEntries !== "function") return null;
    let entries;
    try {
      entries = sessionManager.getEntries();
    } catch {
      return null;
    }
    if (!Array.isArray(entries)) return null;
    for (let index = entries.length - 1; index >= 0; index -= 1) {
      const entry = entries[index];
      if (entry === null || typeof entry !== "object") continue;
      if (entry.type !== "custom" || entry.customType !== RELAY_PENDING_TYPE) continue;
      return isPendingPayload(entry.data) ? entry.data : null;
    }
    return null;
  }

  // A pending intent is satisfied once the record for its successor exists in
  // the same transcript: the deferred step ran. Anything else keeps it pending,
  // because the obligation it names has not been discharged.
  //
  // `throughTenure` names the intent the caller DISCHARGED. An intent recorded
  // for a LATER tenure (a second relay asked for while the first was running) is
  // a different obligation and survives; only the discharged one is dropped.
  clearRelayPending({ throughTenure = null } = {}) {
    if (Number.isInteger(throughTenure) && this.#relayPending !== null && this.#relayPending.tenure > throughTenure) {
      return this.#relayPending;
    }
    this.#relayPending = null;
    this.#relayFailure = null;
    return null;
  }

  // Why the last deferred attempt could not run, or null.
  //
  // This is the fact that must not be swallowed. A deferred relay whose host
  // surface is unreachable is a defect in this adapter (a stale context, a lost
  // atomic publish), and the receipt that said "deferred" is the only thing the
  // caller ever saw. Keeping the reason here lets the next `review` and the
  // `agent_end` handler report it instead of retrying silently forever.
  get relayFailure() {
    return this.#relayFailure;
  }

  noteRelayFailure(failure) {
    this.#relayFailure = failure === null ? null : { ...failure };
    return this.#relayFailure;
  }

  adoptRelayPending(payload) {
    this.#relayPending = payload === null ? null : { ...payload };
    return this.#relayPending;
  }

  // Whether the transcript already carries the record for the tenure now
  // judging. A committed relay whose record is missing from THIS transcript
  // (the reset replaced it) must write the record again before it may claim the
  // successor is fully recorded.
  get relayCommitted() {
    return this.#relayCommitted;
  }

  // Mark the record as present in the current transcript.
  noteRelayCommitted() {
    this.#relayCommitted = true;
  }

  // The payload a commit writes. `tenure` is the SUCCESSOR's, which is this
  // tenure + 1: the record describes the tenure that starts when it is
  // published, and the ledger only advances to it after the publish succeeded.
  // The milestone is this tenure's, because a relay changes the participant and
  // not the milestone (root §2.4 第 3 步).
  // `retiredTranscript` says which copy the caller is writing: the pre-switch
  // record is the one that sits in the file the relay retires, and the copy
  // written into the successor after the reset is not. Only the caller knows
  // which moment it is in, so it is a parameter and never inferred.
  relayPayloadFor({ successorSessionId, previousSessionFile, fenceThrough, tenure = null, retiredTranscript = true }) {
    const successor = Number.isInteger(tenure) ? tenure : this.#ledger.tenure + 1;
    return relayPayload({
      tenure: successor,
      milestone: this.#ledger.milestone,
      successorSessionId,
      previousSessionFile,
      fenceThrough,
      notice: successionNotice({ tenure: successor }),
      retiredTranscript,
    });
  }

  // Persist the review ledger after every change. The record carries the
  // tenure, the milestone and the obligations, and — once a relay has been
  // committed — the fence the successor's requests are filtered by.
  //
  // The fence lives in TWO places on purpose: here, so a ledger read without
  // the transcript still knows the session is a successor, and in the session
  // transcript itself (`relay-record.mjs`), which is the atomic commit. When
  // they disagree the transcript wins, because that is the copy the host
  // published in one rename.
  saveLedger() {
    return writeJson(this.#file, {
      v: LEDGER_FILE_VERSION,
      sessionId: this.#sessionId,
      role: this.#role,
      kind: this.#ledger.kind,
      milestone: this.#ledger.milestone,
      tenure: this.#ledger.tenure,
      reviews: this.#ledger.reviewCount,
      obligations: this.#ledger.obligations,
      successor: this.#ledger.successor,
      ...(this.#fence === null
        ? {}
        : {
            fence: { notice: this.#fence.notice, successor: this.#fence.successor, fenceThrough: this.#fence.fenceThrough },
          }),
    });
  }

  // The Scope book for this session. One object reaches every hook, as the
  // Scope package requires (`ScopeTable.create` refuses a second owner).
  //
  // The book opens on first use from the records already on disk (a cold
  // start, so a persisted `reset` proves only that the event happened and the
  // budget stays unknown until this process establishes one), and every later
  // record is appended to the same sidecar. A restart therefore repeats the
  // cold-start notice instead of inventing a budget (spec §6.2).
  scopeHooks() {
    if (!this.#books.has("scope")) {
      const hooks = new ScopeHooks();
      hooks.coldStart({
        sessionId: this.#sessionId,
        // Scope governs the MANAGED members only: our program faces and the two
        // worker-control tools. Every other host tool (read, grep, write, bash,
        // MCP tools) is the host's own business and is exempt, so a model is
        // never forced to invent a scope annotation for a tool the spec never
        // asked it to annotate (spec §6.2).
        admission: new Admission({ exempt: hostExemptMembers() }),
        records: this.scopeRecords(),
        store: {
          append: async (records) => {
            for (const entry of records) appendJsonLine(this.scopeFile(), entry);
          },
        },
      });
      this.#books.set("scope", hooks);
    }
    return this.#books.get("scope");
  }

  scopeFile() {
    // The SAME root as the ledger: one session keeps one directory, whether it
    // was bound to a temp root or to the project.
    return `${stateDir(this.#root)}/${safeName(this.#sessionId)}.scope.jsonl`;
  }

  // The adapter's own relay journal for this session (root §1.3).
  //
  // One file, appended to, never rewritten: a relay record is a fact about a
  // context boundary, and the same root holds the ledger and the Scope journal
  // so one session keeps one directory.
  relayFile() {
    // The LINEAGE name, not the live session id. A relay changes `#sessionId`
    // when the successor is adopted, so a file named after the live id would
    // take the pre-switch write and the post-switch read to two different
    // files — the record would be written and then never found. The lineage is
    // the identity that survives a relay (§2.4: the participant is the same),
    // so every tenure of one session shares one relay journal.
    return `${stateDir(this.#root)}/${safeName(this.#relayLineage ?? this.#sessionId)}.relay.jsonl`;
  }

  // Write the adapter's own copy of a relay record.
  //
  // `writeJson` semantics, not a bare append: the file is replaced whole, so a
  // reader sees the complete set of records or the previous complete set, never
  // half a record. The host's atomic publish still happens first; this is the
  // second, independent durable view (see `RELAY_LEDGER_TYPE`).
  //
  // Returns `{ok, code}` and never throws: a disk that cannot take the record
  // must not turn a relay into a crash, and the caller reports the refusal.
  writeRelayRecord(record) {
    const existing = this.relayRecords();
    // One record per tenure: a later write for the same tenure replaces the
    // earlier one (the successor's copy supersedes the pre-switch copy), so the
    // file never accumulates two claims about one succession.
    const kept = existing.filter((entry) => entry?.data?.tenure !== record.tenure);
    const written = writeJson(this.relayFile(), [...kept, { type: RELAY_LEDGER_TYPE, data: record }]);
    return { ok: written?.ok === true, code: written?.code ?? null };
  }

  // The adapter's own relay records, oldest first. A file that cannot be read
  // is an empty list: the transcript is still the primary view, so a damaged
  // sidecar must not crash a start.
  relayRecords() {
    const read = readJsonOrError(this.relayFile());
    if (!read.ok || !Array.isArray(read.value)) return [];
    return read.value.filter((entry) => entry?.type === RELAY_LEDGER_TYPE && isRelayPayload(entry?.data));
  }

  // The newest adapter-owned record, or null. Used at the sealed boundary, where
  // the host's journal is gone and no successor transcript exists.
  latestRelayRecord() {
    const records = this.relayRecords();
    return records.length === 0 ? null : records[records.length - 1].data;
  }

  // The records of this session, read from the sidecar. A torn last line is a
  // crash during append and carries no record; any other bad line is an error
  // the Scope package reports rather than silently skipping.
  scopeRecords() {
    return readJsonLines(this.scopeFile(), (decoded) => decoded);
  }

  // The Pair Hint book for this session. The first request of a session binds
  // the language from the ladder (explicit env -> host preference -> locale ->
  // English); a session that already has a binding keeps it.
  async pairHint() {
    if (!this.#books.has("pairHint")) {
      const hint = await PairHint.open({ root: this.#root, sessionId: this.#sessionId });
      if (!hint.bound) {
        await hint.bind({ ladder: languageLadder() });
      }
      this.#books.set("pairHint", hint);
    }
    return this.#books.get("pairHint");
  }

  // A new presentation epoch. Called when the hint's content must change
  // outside a prefix chain (spec §6.1).
  async newPairHintEpoch() {
    const hint = await this.pairHint();
    return hint.newEpoch();
  }

  // Project the request copy through the session's Pair Hint (spec §6.1).
  //
  // `setHandles` alone never changes the bytes of the epoch it is called in:
  // the presentation is frozen for the whole epoch (§6.1 step 4), so a handle
  // that appeared or settled after the freeze would be listed by no request,
  // and the idle hint would be silently dead for the rest of the session. The
  // epoch therefore moves exactly when the handle list differs from the one the
  // current presentation froze. Moving it costs one full prefix; not moving it
  // costs the §6.1 idle hint, so the fact decides, not a caller's guess.
  //
  // `messages` is the copy the pair is placed into. `barriered` says the relay
  // barrier produced it.
  //
  // The hint's prefix chain starts again ONLY where a new epoch starts: the
  // barrier's cut is the one legitimate break (§2.4 第 3 步), and an epoch
  // change already opens a fresh chain (§6.1). Re-anchoring on every request
  // would replace the copy the pair was last compared against — the pair the
  // host never stores would vanish from the anchor, and the next request would
  // be judged against a copy that lacks it.
  //
  // Why anchoring at all: the successor's first request places the pair after
  // two messages, the host stores that request, and the successor's second
  // request — rebuilt from the host's history, which never holds our pair — is
  // compared against a chain the barrier moved under it and refuses with
  // `PREFIX_DRIFT`. The barrier is where that chain has to start again.
  // `incoming` is the copy the request held BEFORE any projection ran. It is
  // what tells a chain restart apart from a rewrite nobody authorised: only a
  // projection that actually changed its input may open a new chain. A caller
  // that omits it makes every difference look like a rewrite, which is the safe
  // direction — the chain then refuses rather than blessing it.
  async pairHintProject(
    messages,
    {
      barriered = false,
      incoming = null,
      givenItems = null,
      answeredItems = null,
      projectedBy = null,
      filtered = null,
      // Whether this request is a SIDE-CHANNEL turn, decided by the CALLER from
      // the messages the host handed over. It must not be re-derived from
      // `messages`: a fenced successor tenure's copy is the barrier's output,
      // and the barrier keeps only the last operator message — the host's
      // developer/agent reminder that marks a title request is gone. Re-deriving
      // read that copy as the conversation, so the side turn could open an
      // epoch, set handles and anchor the prefix chain; the next real request
      // then drifted (`PREFIX_DRIFT`), which is the incident the ephemeral rule
      // exists to prevent.
      ephemeral = null,
    } = {},
  ) {
    // A side-channel turn is not the conversation (§6.1). OMP fires ephemeral
    // title/card requests through this same hook while the main turn streams
    // (measured 2026-11-01, session 01a19547): the side copy extended the
    // pair-free prefix of the last real request, so Pair Hint accepted it and
    // made it the chain's anchor — and the real request that followed four
    // milliseconds later was refused with `PREFIX_DRIFT`.
    //
    // It is an EPHEMERAL request: the pair still goes in, at the chain's own
    // placement and with the frozen text, so the host's cached prefix is hit
    // (the side turn exists to warm that cache); and it records NOTHING and
    // moves nothing, so the conversation continues the chain it presented. Core
    // owns that rule (`REQUEST_KINDS.EPHEMERAL`); this function only says which
    // request is which.
    const sideChannel = typeof ephemeral === "boolean" ? ephemeral : isSideChannelRequest(messages);
    const hint = await this.pairHint();
    // An ephemeral request never opens an epoch and never changes the handle
    // list: both are facts of the conversation, and a UI request is not one.
    const handles = sideChannel ? hint.handles : this.parkedNames();
    const frozen = hint.text === null ? null : [...hint.handles].sort().join("\n");
    const next = [...handles].sort().join("\n");
    const opensEpoch = !sideChannel && frozen !== null && frozen !== next;
    if (opensEpoch) await hint.newEpoch();
    if (!sideChannel) hint.setHandles(handles);
    if (!hint.bound) return messages;
    // The barrier broke the copy and no epoch opened here to start a new chain:
    // that is the case the anchor exists for. An epoch change above already
    // decided the chain (`newEpoch` keeps the old pair's place), so anchoring
    // again would throw that decision away.
    //
    // The anchor takes the SAME shape the projection will compare against —
    // `toPairHintMessages(messages)`, not the raw host copy. Anchoring the raw
    // copy compared a bare-string message against a text-part one at index 0 and
    // refused the successor's own second request (`PREFIX_DRIFT`) with nothing
    // changed in the conversation.
    const chain = toPairHintMessages(messages);
    // A copy the SPEC's own projection replaced is a new chain, exactly like
    // the barrier's cut (§6.2's `resetScope` pruning and `removeLast` rewrite
    // are decisions we make, not rewrites we suffer). The test is the observed
    // output, never "a resetScope record exists": a settled reset whose
    // boundary is outside this copy prunes nothing, and anchoring there would
    // throw away the comparison that catches a real rewrite.
    //
    // An epoch opened above already decided the chain (`newEpoch` keeps the old
    // pair's place), so anchoring again would throw that decision away.
    // An ephemeral request is a BRANCH of the chain, never a step of it: it
    // anchors nothing and records nothing (core's `REQUEST_KINDS.EPHEMERAL`).
    // The chain the journal keeps: a frozen `Chain` of digests, not the full
    // presented copy. It survives a cold start, which is what lets the boundary
    // be judged without re-reading the conversation. `PairHint#presented` is
    // gone with no alias; a core without a chain answers `null`, and the
    // boundary then treats this request as the chain's first.
    const presented = hint.chain ?? null;
    const boundary = sideChannel ? null : projectionBoundary({
      presented,
      projected: chain,
      incoming,
      givenItems,
      answeredItems,
      projectedBy,
      filtered,
      pairFree: (copy) => hint.injector.withoutInjection(copy),
      erased: this.scopeHooks().erasedCount(this.#sessionId),
      // A compaction REPLACED the conversation with a summary and its tail, and
      // OMP stores that replacement (`createCompactionSummaryMessage` writes
      // `{ role: "compactionSummary" }`). The next request is built from a
      // history the model only read part of, so the chain would refuse at index
      // 0 forever — that is a rewrite the spec owns, like a `resetScope` prune,
      // but Scope is not its author, so the item-level test cannot see it.
      //
      // It re-bases ONCE: only when the copy that was presented did NOT already
      // carry a summary. Afterwards the compacted history IS the conversation
      // and is compared normally, so a rewrite inside it is still refused. The
      // one-shot rule is what makes this a chain break and not a standing
      // permission — without it every later request would re-base and bless any
      // rewrite (found by `src/compose/prefix-state-machine.test.mjs`).
      durableRewrite: freshCompaction(presented, chain),
    });
    if (!sideChannel && !opensEpoch) {
      if (barriered) hint.anchor(chain, { reason: REBASE_REASONS.RELAY });
      else if (shouldAnchor(boundary)) hint.anchor(chain, { reason: boundary.reason });
    }
    const projected = await hint.project({
      messages: chain,
      kind: sideChannel ? REQUEST_KINDS.EPHEMERAL : REQUEST_KINDS.MAIN,
    });
    return projected.messages;
  }
}

// Whether this request is the FIRST one after a compaction, and therefore a
// legitimate new chain (`projectionBoundary`'s `durableRewrite`).
//
// OMP writes a compaction as a message of its own: `createCompactionSummaryMessage`
// (`packages/agent/src/compaction/messages.ts`) returns `{ role: "compactionSummary", … }`.
// The mark is the host's, not a text guess.
//
// The rule is IDENTITY, not presence. "The copy carries a summary" stays true
// for the rest of the session — every later request is built from the compacted
// history — so a presence test would re-base once and then bless any rewrite
// inside that history. Comparing the summary's DIGEST at the same index makes it
// a one-shot per compaction while still allowing a SECOND one: a host merges the
// earlier summary into the new one (`compaction.ts` builds
// `previousSummaryForCompaction` and emits one summary), so a second compaction
// changes the digest and is a new chain, while the same compacted history
// compares equal and is compared normally.
const summaryIndex = (messages) =>
  Array.isArray(messages) ? messages.findIndex((message) => message?.role === "compactionSummary") : -1;

// Whether this request is the FIRST one after a compaction. `chain` is the
// frozen `Chain` the journal keeps (digests only, no text, and it survives a
// cold start).
//
// The comparison is IDENTITY, not presence: "the copy carries a summary" stays
// true for the rest of the session, so a presence test would re-base once and
// then bless any rewrite inside that history. Comparing the summary's digest at
// the same index makes it one-shot per compaction while still allowing a
// SECOND one: the host merges the earlier summary into the new one, so the
// digest changes, while the same compacted history compares equal and is
// compared normally.
const freshCompaction = (chain, projected) => {
  const index = summaryIndex(projected);
  if (index < 0) return null;
  // No chain yet: `projectionBoundary` already answers "first", so there is no
  // presented copy this summary could have replaced.
  if (chain === null || chain === undefined) return null;
  // A chain shorter than this index never presented a summary there, so this
  // request carries the first one: a new chain.
  if (index >= chain.digests.length) return REBASE_REASONS.HISTORY_COMPACTED;
  return chain.digests[index] === messageDigest(projected[index]) ? null : REBASE_REASONS.HISTORY_COMPACTED;
};

// The file-name sanitiser lives in `host-facts.mjs` (`safeSessionName`): the
// ledger and Plan's draft must name one session the same way, and two copies
// would drift the moment one of them changed.
const safeName = safeSessionName;

const MILESTONES = Object.freeze({
  plan: PLAN_MILESTONES,
  manager: MANAGER_MILESTONES,
});

// Rebuild the review ledger from its file.
//
// `ReviewLedger` keeps its state private, so the ledger is rebuilt by replaying
// the transitions the file records: the stored milestone index is reached with
// one empty review per step, and the stored obligations are then re-applied. A
// file that cannot be replayed stops at the milestone actually reached; it never
// fakes a later one.
//
// A file written after a committed relay describes the SUCCESSOR's tenure: the
// stored tenure is applied after the milestone replay, and the obligations it
// carries are the successor's own (an empty ledger at the same milestone). A
// file whose stored tenure is below the replay's is left alone — the replay is
// the older fact.
const restoreLedger = ({ file, role }) => {
  const kind = role === "manager" ? "manager" : "plan";
  const ledger = new ReviewLedger({ kind });
  const saved = readJson(file, null);
  if (saved === null || saved.kind !== kind) return ledger;
  if (saved.v !== 1 && saved.v !== LEDGER_FILE_VERSION) return ledger;
  const milestones = MILESTONES[kind];
  const target = milestones.indexOf(saved.milestone);
  if (target < 0) return ledger;
  // A stored successor tenure: the relay already happened, so the replay must
  // not stop at the relay it would meet — the successor's milestone is the same
  // milestone, and its ledger is the obligations stored beside it.
  const successorTenure = Number.isInteger(saved.tenure) && saved.tenure > 0 ? saved.tenure : 0;
  if (successorTenure > 0) {
    ledger.adoptSuccessor({ tenure: successorTenure, milestone: milestones[target] });
    if (Array.isArray(saved.obligations) && saved.obligations.length > 0) {
      ledger.apply(saved.obligations);
    }
    return ledger;
  }
  for (let step = 0; step < target; step += 1) {
    if (ledger.milestone !== milestones[step]) break;
    // A Plan at `ready` hands off and needs a draft; a replay without one
    // cannot pass that step, so the replay stops before it.
    if (kind === "plan" && milestones[step] === PLAN_MILESTONES[PLAN_MILESTONES.length - 1]) break;
    const receipt = ledger.review([], kind === "plan" ? "" : null);
    if (receipt.ok !== true) break;
    // A replay that reaches a relay stops there: the file stored no successor
    // tenure, so this tenure never handed over, and replaying the relay would
    // invent one. (A file WITH a successor tenure took the branch above.)
    if (receipt.kind === REVIEW_KINDS.RELAY) break;
  }
  if (Array.isArray(saved.obligations) && saved.obligations.length > 0) {
    ledger.apply(saved.obligations);
  }
  return ledger;
};

// The language ladder of spec §6.1. The explicit rung reads the adapter's own
// variable; the host preference and the locale are read from the environment
// the host process runs in, which is the only place a headless session can see
// them.
export const languageLadder = (env = process.env) => ({
  explicit: env.HERDCHESTRA_LANGUAGE ?? null,
  host: env.HERDCHESTRA_HOST_LANGUAGE ?? null,
  locale: env.LC_ALL ?? env.LC_MESSAGES ?? env.LANG ?? null,
});

export { systemClock, RECORD_KINDS, resolveLanguage };
