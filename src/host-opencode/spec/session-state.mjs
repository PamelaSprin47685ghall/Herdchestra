// What one host session owns between calls (§3.3, §5, §6.1, §6.2).
//
// The plugin is loaded once per host, but a session's facts must outlive any
// single tool call: the parked programs, the review ledger of the session's
// side, the Scope book, the Pair Hint binding and the unsettled park handles.
// This module is the one place those facts live, keyed by session id.
//
// Two shapes of state exist:
//
//   - per program: the registry, the control surface and the park stops. A
//     parked program's continuation is an in-memory promise; a process restart
//     cannot resume it (§3.3: an interrupted turn is not replayed), so nothing
//     here is persisted.
//   - per session: the review ledger, the Scope book and the language binding.
//     These are facts the next request must agree with, so they are persisted
//     under `.Herdchestra/{session}.jsonl` and rebuilt on a cold start.
//
// ## A successor's facts come from its predecessor once
//
// A relay opens a NEW session (根 §2.4 第 3 步), so every fact keyed by session id starts
// empty for it. `inheritFrom` in the relay record says which tenure it continues,
// and the first construction of a successor whose own file is missing or empty
// copies what 根 §2.4 第 3 步 says a successor keeps:
//
//   language   copied as-is: a successor speaks the language its work was
//              written in (§6.1), and re-resolving the ladder could move it.
//   scope      only `reset` records, and no `budget`: §6.2 makes a budget
//              from another process an unknown fact, so the successor is told to
//              `resetScope` like any cold start. A `budget` copied here would
//              claim a remaining count nobody established.
//   pair hint  not copied: the hint opens under the successor's own id and
//              binds from the inherited language, so its first epoch starts
//              empty (§6.1: a new presentation generation needs a new epoch).
//              The epoch itself belongs to the hint's own journal
//              (`{session}.hint.jsonl`), never to this file.
//   review     not copied: a new tenure judges from an empty ledger (根 §2.3).
//
// The copy happens once. After it, the successor's own file is the authority,
// and the record's `inheritFrom` is never read again: a second copy would replay
// the predecessor's facts over anything the successor recorded since.
//
// The file is append-only JSONL: one record per event, `{ v, type, ... }`. A
// torn last line is a crash during append and carries no record; any other bad
// line is an error, because silently dropping a record would silently change a
// ledger or a budget (§6.2).

import fs from "node:fs";
import path from "node:path";

import { ProgramRegistry } from "../../core/program.mjs";
import { ControlSurface } from "../../core/control.mjs";
import { ReviewLedger, ReviewWindow, PLAN_MILESTONES, MANAGER_MILESTONES } from "../../core/review.mjs";
import { ScopeHooks } from "../../core/scope/index.mjs";
import { LanguageBinding, bindSession } from "../../core/pair-hint/language.mjs";
import { ProgramCalls } from "./program-calls.mjs";
import { closeTurn } from "./scope-bridge.mjs";
import { RelayFacts } from "./relay-facts.mjs";
import { captureOriginal, markUnrecorded, receiptVerdict, requireOriginal } from "./original-input.mjs";
import { callFile, noteCall, readCalls } from "./call-record.mjs";
import { isWorkerRecord, restoreRoster } from "./worker-sessions.mjs";

export const RECORD_VERSION = 1;

export const RECORD_TYPES = Object.freeze({
  REVIEW: "review",
  SCOPE: "scope",
  LANGUAGE: "language",
  FISSION: "fission",
  // A fission group that was rolled back before it ran (§4.2 step 4). It
  // withdraws the group's record; the record of why stays visible.
  FISSION_DISCARDED: "fissionDiscarded",
  // The worker roster of a Manager session (§4.1): which host sessions this
  // session forked, what the host reported about each, which results a `join`
  // already handed over. It lives in the same journal as the rest because a
  // worker outlives the plugin that started it: OpenCode reloads a plugin on a
  // watched-file change, and an in-memory roster made every later `join` answer
  // "nothing to collect" while the child sessions were still running.
  WORKER: "worker",
});

export const STATE_DIR = ".Herdchestra";

// The adapter's own facts and the Pair Hint journal are two writers with two
// record shapes. They live in separate files: `{session}.jsonl` for these
// facts, `{session}.hint.jsonl` for the hint (core's `JOURNAL_DIR` plus
// `assertSessionId`, see pair-hint-request.mjs). A single file would make each
// side try to interpret the other's records, and the first foreign record is a
// `FACT_CORRUPT` for the hint and an unknown type for this reader.
export const HINT_SUFFIX = ".hint";

const safeName = (value) =>
  String(value).replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120) || "session";

// The state file of one session, under the workspace root.
export const stateFile = (root, sessionID) =>
  path.join(root, STATE_DIR, `${safeName(sessionID)}.jsonl`);

// The language binding a relayed successor starts from, or null.
//
// This is the one fact the Pair Hint cannot take from `SessionState`'s records:
// the hint binds when it is opened, and the plugin opens it BEFORE the Scope is
// touched, so the inherited language must be answerable without opening
// anything. It answers from the record's `inheritFrom` and the predecessor's
// own file, and it copies nothing: the successor's binding is written by
// `inherit()` in its own record shape when its Scope opens.
export const inheritedBinding = (root, sessionID) => {
  if (typeof root !== "string" || root.length === 0) return null;
  const relay = new RelayFacts({ root, sessionID });
  const record = relay.record();
  if (record === null || record.corrupt === true) return null;
  const from = record.inheritFrom;
  if (typeof from !== "string" || from.length === 0 || from === sessionID) return null;
  const saved = readRecords(stateFile(root, from))
    .filter((entry) => entry?.type === RECORD_TYPES.LANGUAGE)
    .at(-1);
  if (saved?.binding?.language === undefined) return null;
  try {
    return LanguageBinding.fromRecord({ ...saved.binding, sessionId: sessionID }).fork(sessionID);
  } catch {
    // A binding the predecessor wrote that this build cannot read is not a
    // reason to invent one: the caller resolves the ladder instead.
    return null;
  }
};

// Read every complete record. A trailing line without a newline is a torn
// append and is dropped; any other undecodable line is reported.
export const readRecords = (file) => {
  let text;
  try {
    text = fs.readFileSync(file, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return [];
    throw error;
  }
  if (text === "") return [];
  const complete = text.endsWith("\n") ? text : text.slice(0, text.lastIndexOf("\n") + 1);
  const records = [];
  for (const line of complete.split("\n")) {
    if (line === "") continue;
    records.push(JSON.parse(line));
  }
  return records;
};

// Append records in one write. A failure is reported to the caller: a ledger
// that did not reach the disk is not a ledger the next request may trust.
export const appendRecords = (file, records) => {
  if (records.length === 0) return;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.appendFileSync(file, records.map((record) => `${JSON.stringify(record)}\n`).join(""), "utf8");
};

// Rebuild a review ledger from its records.
//
// `ReviewLedger` keeps its state private, so the ledger is rebuilt by replaying
// the transitions the records describe: the recorded milestone index is reached
// one empty review per step, and the recorded obligations are then applied. A
// record set that cannot be replayed stops at the milestone actually reached;
// it never fakes a later one.
//
// The row also records how many reviews the tenure had. That number is a fact of
// the tenure, not of the replay: 根 §2.2 keeps Plan's draft read-only until the
// tenure's FIRST review, and a review that recorded an obligation and then
// unrecorded it leaves the same (empty) ledger as a tenure that never reviewed.
// Replaying transitions alone therefore restored `reviews: 0` and locked the
// draft forever — the adapter builds a fresh surface per call, so every later
// call read a ledger whose count had been forgotten. The row's count is
// restored below by booking reviews the replay can prove: the obligations it
// applied, and — for the rest — a review that records nothing and changes
// nothing, which is exactly what the empty reviews between obligations were.
//
// A relay stops the replay. `openSuccessor` is called by compose after the host
// reports a whole hand-over, and a record written before that point describes
// the relay, not a successor: an empty review that reaches a relay in the replay
// is a transition that did not happen, and adopting a successor for it would
// invent a tenure nobody started. The replay therefore stops before the relay.
export const restoreLedger = (kind, records, { from = null, asSuccessor = false, resourcesSettled = null } = {}) => {
  const ledger = new ReviewLedger({ kind, ...(resourcesSettled === null ? {} : { resourcesSettled }) });
  const milestones = kind === "manager" ? MANAGER_MILESTONES : PLAN_MILESTONES;
  // A session the relay record names as its successor starts as a successor even
  // with no row of its own. `openSuccessor` is the only way into that state, and
  // without it the first empty review would be the first tenure's `askUser`
  // instead of the successor's `confirm` (根 §2.4 第 3 步). A first-tenure ledger has no
  // finished work yet, so `openSuccessor` cannot throw here.
  if (asSuccessor === true && ledger.tenure === 0) ledger.openSuccessor();
  // A relay names the milestone its successor continues (根 §2.4 第 3 步). The
  // record is the authority, so the replay reaches that milestone even when this
  // session has no review row of its own yet; a row that went further still wins
  // below.
  const named = typeof from === "string" ? milestones.indexOf(from) : -1;
  // A successor ADOPTS the milestone the relay copied to it; it does not replay
  // its way there. Replaying means calling `review([])`, and the successor's own
  // first empty review is exactly the `confirm` of 根 §2.4 第 5 步 — spending it
  // here left a successor that was not one, so its first real review relayed a
  // second time instead of promoting the work.
  if (named >= 0 && ledger.successor === true && milestones.indexOf(ledger.milestone) < named) {
    ledger.adoptSuccessor({ tenure: ledger.tenure, milestone: from });
  }
  for (let step = 0; named >= 0 && ledger.successor !== true && step < named; step += 1) {
    if (ledger.milestone !== milestones[step]) break;
    if (kind === "plan" && milestones[step] === PLAN_MILESTONES[PLAN_MILESTONES.length - 1]) break;
    const receipt = ledger.review([], kind === "plan" ? "" : null);
    if (receipt.ok !== true) break;
    if (kind === "manager" && receipt.kind === "relay") break;
  }
  let saved = null;
  for (const record of records) {
    if (record?.type === RECORD_TYPES.REVIEW) saved = record;
  }
  if (saved === null || saved.v !== RECORD_VERSION || saved.kind !== kind) return ledger;
  const target = milestones.indexOf(saved.milestone);
  if (target < 0) return ledger;
  // 根 §2.4 第 1 步: the first tenure's question was put to the operator and
  // answered. The answer does not advance the ledger, so the row is the only
  // record that it happened; without it the replay would reach the question
  // again and the operator would be asked a second time. The count is the
  // review the answer belongs to, which is exactly what the question spent.
  if (saved.questionAsked === true && kind === "plan" && saved.tenure === 0) {
    ledger.apply([{ key: "operator-answer", value: "answered" }]);
  }
  // A successor's tenure starts at the milestone the relay named (根 §2.4 第 3 步), and
  // core's `openSuccessor` is the only way to reach that state: the first empty
  // review of the new tenure is the `confirm` that moves onto `at`, not the
  // `askUser` a first tenure gets. A record with a tenure beyond the first is
  // therefore replayed as a successor, and the relay that opened it is not
  // replayed (it is the predecessor's, and the host already performed it).
  const relayed = Number.isInteger(saved.tenure) && saved.tenure > 0;
  if (relayed && ledger.tenure === 0) ledger.openSuccessor();
  // A relay may already have moved this ledger to `from`; the row's own replay
  // continues from there instead of repeating steps it took.
  const start = Math.max(0, milestones.indexOf(ledger.milestone));
  for (let step = start; step < target; step += 1) {
    if (ledger.milestone !== milestones[step]) break;
    // A Plan at `ready` hands off and needs a draft; a replay without one
    // cannot pass that step, so the replay stops before it.
    if (kind === "plan" && milestones[step] === PLAN_MILESTONES[PLAN_MILESTONES.length - 1]) break;
    // A milestone below `ready` is reached by an empty review, and every empty
    // review is one of the recorded reviews.
    const receipt = ledger.review([], kind === "plan" ? "" : null);
    if (receipt.ok !== true) break;
    if (kind === "manager" && receipt.kind === "relay") break;
  }
  // A successor's row carries the reviews its tenure really had; reaching them
  // is a plain recorded review, because the `askUser`/`relay` transitions belong
  // to the first tenure and `openSuccessor` already moved past them.
  if (Array.isArray(saved.obligations) && saved.obligations.length > 0) ledger.apply(saved.obligations);
  // The row's review COUNT is a fact of the tenure, not of the replay (根 §2.2:
  // Plan's draft is read-only until the tenure's first review, and the adapter
  // builds a fresh surface per call, so the count must outlive the program that
  // wrote it). A review that recorded an obligation and then cleared it leaves
  // the same (empty) ledger as a tenure that never reviewed, so the replay alone
  // cannot recover the number. The missing reviews are booked as EMPTY reviews:
  // they record nothing, add no entry and change no milestone — they restore
  // only the count `draftWritable` reads. A Plan at 待执行 is never reached this
  // way: an empty review there is a hand-off, which starts a Manager.
  const counted = Number.isInteger(saved.reviews) && saved.reviews > 0 ? saved.reviews : 0;
  for (let taken = ledger.reviewCount; taken < counted; taken += 1) {
    if (ledger.milestone === milestones[milestones.length - 1]) break;
    const receipt = ledger.review([], null);
    if (receipt.ok !== true) break;
  }
  return ledger;
};

// One tenure's row.
//
// `questionAsked` says the FIRST tenure already put 根 §2.4 第 1 步's question to
// the operator. An `askUser` review does not consume the review count, so the
// row alone cannot tell "never asked" from "asked and answered": the adapter
// states it when it records the answer, and the replay then does not ask again.
const ledgerRecord = (
  sessionID,
  ledger,
  { milestone = ledger.milestone, tenure = ledger.tenure, questionAsked = false } = {},
) => ({
  v: RECORD_VERSION,
  type: RECORD_TYPES.REVIEW,
  sessionID,
  kind: ledger.kind,
  milestone,
  tenure,
  reviews: ledger.reviewCount,
  obligations: ledger.obligations,
  successor: ledger.successor,
  // Only the first tenure can ask (根 §2.2: the question exists only in `S1`).
  ...(questionAsked === true && ledger.kind === "plan" && tenure === 0 ? { questionAsked: true } : {}),
});

// One session's facts.
export class SessionState {
  #sessionID;
  #root;
  #file;
  #registry;
  #control;
  #calls;
  #scopes;
  #scopeOpened;
  #ledgers;
  #windows;
  #bindings;
  #handles;
  #lastView;
  #groups;
  #relay;
  #inherited;
  #anchored;
  #resourcesSettled;
  #callFile;

  // `resourcesSettled` answers whether this session's real execution resources
  // are closed (§2.3 closing). It is a function, never a snapshot: the answer is
  // read at the moment a review asks, and the roster changes between reviews.
  // The default answers "settled", which is what a session that never had a
  // roster means; the activation supplies the real one (`makeActivation`).
  constructor({ sessionID, root, resourcesSettled = null }) {
    if (typeof sessionID !== "string" || sessionID.length === 0) {
      throw new TypeError("a session state needs a session id");
    }
    this.#resourcesSettled =
      typeof resourcesSettled === "function" ? resourcesSettled : () => true;
    this.#sessionID = sessionID;
    this.#root = root;
    this.#file = stateFile(root, sessionID);
    this.#registry = new ProgramRegistry();
    this.#control = new ControlSurface(this.#registry);
    // The durable half of "this call id was used" (§2.4 第 6 步). Read once,
    // here, because the gate asks on every call and the file only grows; the
    // in-memory set is kept in step by `noteCall` below.
    this.#callFile = callFile(root, sessionID);
    this.#calls = new ProgramCalls({
      registry: this.#registry,
      calls: readCalls(this.#callFile),
    });
    this.#scopes = new ScopeHooks();
    this.#scopeOpened = false;
    this.#ledgers = new Map();
    this.#windows = new Map();
    this.#bindings = new Map();
    this.#handles = new Map();
    this.#lastView = null;
    this.#groups = new Map();
    this.#relay = new RelayFacts({ root, sessionID });
    this.#inherited = false;
    this.#anchored = null;
  }

  get sessionID() {
    return this.#sessionID;
  }

  // The workspace this session's facts live in.
  get root() {
    return this.#root;
  }

  get file() {
    return this.#file;
  }

  get registry() {
    return this.#registry;
  }

  get control() {
    return this.#control;
  }

  get calls() {
    return this.#calls;
  }

  // Record one used call id on disk. The gate calls this before a program is
  // admitted, so a crash in the middle of a call still leaves the fact that the
  // id was spent; a cold start then refuses a replay instead of running it.
  noteCall(id, payload = null) {
    return noteCall(this.#callFile, { sessionID: this.#sessionID, id, payload });
  }

  // The finished receipt of one call, appended to the same record. A replay
  // after a restart answers it; the id line written at admission stays as it
  // was, so the file is an append-only history of what happened.
  noteReceipt(id, receipt) {
    return noteCall(this.#callFile, { sessionID: this.#sessionID, id, receipt });
  }

  get scopes() {
    return this.#scopes;
  }

  // The relay record of this session, as the two gates read it (根 §2.4 第 3 步). The
  // reader re-reads whenever the file changed, so a record written during this
  // process is seen by the next hook without anybody clearing a cache.
  get relay() {
    return this.#relay;
  }

  // Whether this request is the one that opens the successor's prefix chain
  // (根 §2.4 第 3 步, §6.1).
  //
  // The barrier cuts every request of a successor, but only its FIRST request
  // starts a new chain. Re-anchoring a later request would drop the pair the
  // host never stored, and the next request would be compared against a copy
  // the pair is not in. The marker is the record's identity, so a new record
  // (a new relay) opens a new chain while the same record answers `false` after
  // the first request.
  anchorPrefix(record) {
    const key = `${record.incumbent}>${record.successor}@${record.tenure}`;
    if (this.#anchored === key) return false;
    this.#anchored = key;
    return true;
  }

  // Copy a predecessor's facts once, when this session is a successor whose own
  // file is missing or empty. See the header: language yes, Scope resets yes,
  // budget no, Pair Hint no, review no.
  inherit() {
    if (this.#inherited) return false;
    this.#inherited = true;
    const record = this.#relay.record();
    if (record === null || record.corrupt === true) return false;
    const from = record.inheritFrom;
    if (typeof from !== "string" || from.length === 0 || from === this.#sessionID) return false;
    // Only an empty file inherits: anything this session already recorded is
    // newer than the predecessor's facts.
    if (readRecords(this.#file).length > 0) return false;
    const source = readRecords(stateFile(this.#root, from));
    if (source.length === 0) return false;
    const copied = [];
    const language = source.filter((entry) => entry?.type === RECORD_TYPES.LANGUAGE).at(-1);
    if (language?.binding !== undefined) {
      // The binding names its own session; a copy under the successor's id would
      // claim a binding the predecessor wrote, so the record is rebound. The
      // owner is kept for audit only, exactly as `LanguageBinding#fork` keeps it.
      copied.push({
        v: RECORD_VERSION,
        type: RECORD_TYPES.LANGUAGE,
        sessionID: this.#sessionID,
        binding: {
          sessionId: this.#sessionID,
          language: language.binding.language,
          source: "fork",
          ownerSessionId: from,
        },
      });
    }
    // No Scope record crosses a relay (§6.2). A persisted `resetScope` is a
    // record that an event happened, not proof that a budget is still live; a
    // successor that inherited the row would cold-start as if the predecessor's
    // retention had been established in this tenure, and the model would never
    // have to establish its own. The successor therefore starts as any cold
    // start: it must `resetScope` before ordinary work.
    if (copied.length === 0) return false;
    appendRecords(this.#file, copied);
    return true;
  }

  // The successor's starting row (根 §2.4 第 3 步): the milestone the relay named, under
  // the new tenure. Written before the hand-over reports success, so a successor
  // that restarts restores its own tenure instead of the predecessor's.
  //
  // The inherited facts are copied first: the row belongs to this session's own
  // file, and a copy that landed after it would look like a later event.
  recordRelayStart(at, kind = "manager") {
    if (typeof at !== "string" || at.length === 0) return false;
    // The ledger side the relay named (根 §2.4 第 3 步). A Plan relay starts a
    // Plan successor; a Manager relay a Manager one. The record carries the
    // side, so the successor's row and its restored ledger agree on the kind.
    const side = kind === "plan" ? "plan" : "manager";
    this.inherit();
    // The ledger is restored first, because the row must name the tenure the
    // ledger really holds. A relayed successor's ledger already starts as a
    // successor (tenure 1) from the relay record; reading it before writing the
    // row keeps the row and the ledger from disagreeing, which would make the
    // next cold start restore a tenure that never opened.
    const ledger = this.ledgerFor(side);
    // A row for this tenure is written once. A second hand-over delivery finds
    // the row and leaves it alone.
    if (
      readRecords(this.#file).some(
        // The file holds every row of every tenure this session ever had. A row
        // counts as this tenure's only when it names this session and this
        // tenure: a `kind`-only test would treat the first tenure's row as
        // proof that the successor's row already exists and write nothing.
        (record) =>
          record?.type === RECORD_TYPES.REVIEW &&
          record.kind === side &&
          record.sessionID === this.#sessionID &&
          record.tenure === ledger.tenure,
      )
    ) {
      return true;
    }
    this.openLedger(side, { at, ledger });
    return true;
  }

  // The Plan hand-off is a durable fact of the tenure that performed it
  // (根 §2.2: the hand-off is the exit a Plan earns). It is written on the
  // Plan's own review row, so a cold start and the turn-exit controller read
  // the same fact instead of inferring it from the milestone.
  noteHandoff({ to = null, handoffId = null } = {}) {
    const ledger = this.ledgerFor("plan");
    appendRecords(this.#file, [
      {
        ...ledgerRecord(this.#sessionID, ledger),
        handoff: true,
        ...(typeof to === "string" && to.length > 0 ? { handoffTo: to } : {}),
        ...(typeof handoffId === "string" && handoffId.length > 0 ? { handoffId } : {}),
      },
    ]);
    return ledger;
  }

  /**
   * Take over what the relay record preserved (根 §2.4).
   *
   * The record holds the predecessor's roster, the results nobody joined and
   * the road-bound `devops`. Registering them here is what makes the record
   * real: a record written but never read would let a successor fork a second
   * worker under a name the predecessor still holds, and lose the results in
   * between.
   *
   * Adoption is registration, not restart: no brief is re-sent and no lane is
   * reset. A worker that already finished registers as finished with its
   * outcome, which is exactly the unclaimed result the successor may still
   * join.
   *
   * Returns `{ adopted, unclaimed, devops, refused }`, or `null` when this
   * session is not a successor.
   */
  adoptRoster(table, devops) {
    const record = this.#relay.record();
    if (record === null || record.corrupt === true) return null;
    if (record.successor !== this.#sessionID) return null;
    const adopted = table.adopt?.(record.roster ?? []) ?? { adopted: [], refused: [] };
    const unclaimed = table.adoptUnclaimed?.(record.unclaimed ?? []) ?? { adopted: [], refused: [] };
    const bound = Array.isArray(record.boundDevops) ? record.boundDevops : [];
    for (const name of bound) devops?.add?.(name);
    return {
      adopted: adopted.adopted ?? [],
      unclaimed: unclaimed.adopted ?? [],
      devops: bound,
      refused: [...(adopted.refused ?? []), ...(unclaimed.refused ?? [])],
    };
  }

  // The Scope of this session, opened once from the records on disk. A cold
  // start means a persisted `resetScope` proves only that an old event existed;
  // the budget stays unknown until this process establishes one (§6.2).
  scope() {
    if (this.#scopeOpened) return this.#scopes;
    this.#scopeOpened = true;
    // The inherited facts must be on disk before the Scope is read from them:
    // a successor that inherits after its book is opened would project an empty
    // Scope for its whole life.
    this.inherit();
    const records = this.recordsOf(RECORD_TYPES.SCOPE);
    const opened = this.#scopes.coldStart({
      sessionId: this.#sessionID,
      records: records.map((record) => record.record),
      store: { append: async (entries) => this.appendScope(entries) },
    });
    // A taken owner is a wiring error, not a model-facing refusal: every hook of
    // one session must share this one object.
    if (opened?.ok === false) throw new Error(`${opened.code}: ${opened.message}`);
    return this.#scopes;
  }

  // End of a model turn (§6.2): a turn that only staged `meToo` is refused
  // here. The host's request-level `context` hook is the only seam that runs
  // once per model turn and after every tool call of that turn settled, so the
  // adapter closes the turn there — never after a single tool call, which would
  // close a turn whose parallel calls have not been seen.
  async closeTurn() {
    // The bridge's `closeTurn(hooks, sessionId)` answers a promise that settles
    // to the refusal or null; the state exposes the same shape, awaited, so a
    // caller can never compare a promise to null and lose the refusal.
    return await closeTurn(this.scope(), this.#sessionID);
  }

  // The scope a session reads and writes. One instance per session: the
  // registry and the hooks must never hold two books for one id.
  scopeOf() {
    return this.scope();
  }

  // The review ledger of one side (Plan or Manager), restored from disk.
  ledgerFor(kind) {
    const existing = this.#ledgers.get(kind);
    if (existing !== undefined) return existing;
    // A successor starts at the milestone the relay named (根 §2.4 第 3 步： the
    // successor's milestone is copied from the retired tenure). The record is
    // the authority for that, and it outlives any ledger row this session
    // writes: `openLedger` records the step BEFORE `at`, so the replay below
    // reaches `at` on its own.
    const relay = this.#relay.record();
    // A record written before the side was stored describes a Manager relay:
    // the only relay that existed then. The side decides which milestone list
    // the record's `milestone` belongs to, so it is read before the restore.
    const side = relay?.kind === "plan" ? "plan" : "manager";
    const inherited =
      relay !== null && relay.corrupt !== true && relay.successor === this.#sessionID &&
      side === kind && typeof relay.milestone === "string" && relay.milestone.length > 0
        ? relay.milestone
        : null;
    const ledger = restoreLedger(kind, this.recordsOf(RECORD_TYPES.REVIEW), {
      from: inherited,
      // A relayed successor's first tenure is the predecessor's, so its ledger
      // starts as a successor even before it has a row of its own.
      asSuccessor: relay !== null && relay.corrupt !== true && side === kind && relay.successor === this.#sessionID,
      // §2.3 closing: an empty review at `close` closes the work only when the
      // real execution resources are settled. The host cannot answer that
      // (workers are sessions this adapter started, not host bookkeeping), so
      // the answer is injected by the caller that owns the roster
      // (`resourcesSettled`). Without it core's default `() => true` would
      // close a work while its workers still run — a false "done".
      resourcesSettled: this.#resourcesSettled,
    });
    this.#ledgers.set(kind, ledger);
    return ledger;
  }

  // Whether this process has opened one side's ledger. A ledger is opened by a
  // review or a program, and the turn-exit controller reads this to tell "the
  // live ledger" from "no ledger yet": a ledger it opens itself would be a
  // second object judging the same work, and the two could disagree.
  hasLedger(kind) {
    return this.#ledgers.has(kind);
  }

  // Persist the ledger after every change. A relay is refused before it can
  // change anything (relay-host.mjs), so this only ever writes the tenure that
  // is judging.
  //
  // `questionAsked` records that 根 §2.4 第 1 步's question has been put to the
  // operator and answered (see `ledgerRecord`). It is passed by the call that
  // asked, and it is sticky: a later save of the same tenure keeps the fact.
  saveLedger(kind, { questionAsked = false } = {}) {
    const ledger = this.ledgerFor(kind);
    const already = readRecords(this.#file).some(
      (record) =>
        record?.type === RECORD_TYPES.REVIEW &&
        record.kind === kind &&
        record.sessionID === this.#sessionID &&
        record.tenure === ledger.tenure &&
        record.questionAsked === true,
    );
    appendRecords(this.#file, [
      ledgerRecord(this.#sessionID, ledger, { questionAsked: questionAsked === true || already }),
    ]);
    return ledger;
  }

  // The evaluation window of one side (§2.3). It is NOT persisted: the window
  // describes one round of one process ("every round's first request"), and a
  // restart starts a new round with no round in progress. A window restored from
  // disk would either keep `js-review` shut for a round that already ended, or
  // open it for a round nobody started.
  //
  // The Manager's window belongs to the tenure that judges: a relayed successor
  // starts its own evaluation, so the window starts fresh there.
  windowFor(kind = "manager") {
    const existing = this.#windows.get(kind);
    if (existing !== undefined) return existing;
    const window = new ReviewWindow({ kind });
    this.#windows.set(kind, window);
    return window;
  }

  // The ledger of this session, restored from disk and then recorded.
  //
  // `restoreLedger` stops a replay at the step that needs a fact it does not
  // have, so a ledger read here can sit at a milestone earlier than the one the
  // predecessor reached. A relay makes that the successor's problem: the new
  // tenure must judge from the milestone the relay named, or the successor
  // repeats work the relay already decided was done. Recording the restored
  // state keeps one row per tenure, so a later cold start restores the last one.
  openLedger(kind, { at = null, ledger: restored = null } = {}) {
    const ledger = restored ?? this.ledgerFor(kind);
    // A ledger handed in IS this session's live ledger for that side. Registering
    // it is what makes `ledgerFor` and `hasLedger` agree with the caller: without
    // it, a later `ledgerFor` rebuilt a second object from the rows and the two
    // judged the same work independently.
    this.#ledgers.set(kind, ledger);
    // A record without a relay is the ledger's own row.
    if (typeof at !== "string" || at.length === 0) {
      appendRecords(this.#file, [ledgerRecord(this.#sessionID, ledger)]);
      return ledger;
    }
    // `at` is the milestone the relay NAMED: the one whose empty review opened
    // the successor. 根 §2.4 第 3 步 gives the successor the same milestone, and core's
    // `openSuccessor` is what advances it — the first empty review of the
    // successor is `confirm`, which moves one step past the relay. So the row
    // records the step before `at`, which is where the successor really starts.
    const milestones = kind === "plan" ? PLAN_MILESTONES : MANAGER_MILESTONES;
    const named = milestones.indexOf(at);
    // The row names the ledger's own tenure: a relayed successor's ledger is
    // already the successor (tenure 1), and a row that counted up from the
    // predecessor's 0 would name a tenure the ledger never opened. Only a ledger
    // that is still the first tenure counts up to the one the relay opens.
    const start = named > 0 ? milestones[named - 1] : ledger.milestone;
    const tenure = ledger.tenure > 0 ? ledger.tenure : ledger.tenure + 1;
    // A row for this tenure is written once. A second hand-over delivery finds
    // it and leaves it alone.
    if (
      readRecords(this.#file).some(
        // The check names the row this call would write — this session, this
        // kind, this tenure. A `kind`-only test is satisfied by the first
        // tenure's row in the same file, so the successor's starting row would
        // never be written and a cold start would restore tenure 0.
        (record) =>
          record?.type === RECORD_TYPES.REVIEW &&
          record.kind === kind &&
          record.sessionID === this.#sessionID &&
          record.tenure === tenure,
      )
    ) {
      return ledger;
    }
    appendRecords(this.#file, [
      ledgerRecord(this.#sessionID, ledger, { milestone: start, tenure }),
    ]);
    return ledger;
  }

  // The language binding, inherited from a parent when the child has none.
  // `ladder` is `{ explicit, host, locale }` for a top-level session.
  bindLanguage({ parentID = null, parent = null, ladder = {} } = {}) {
    const existing = this.#bindings.get(this.#sessionID);
    if (existing !== undefined) return existing;
    // A binding written by an earlier process is the session's language; it is
    // restored, never re-resolved, so a changed global preference cannot move
    // a session that already spoke (§6.1).
    const saved = this.recordsOf(RECORD_TYPES.LANGUAGE).at(-1)?.binding ?? null;
    if (saved !== null && saved.language !== undefined) {
      const kept = LanguageBinding.fromRecord({ ...saved, sessionId: this.#sessionID });
      this.#bindings.set(this.#sessionID, kept);
      return kept;
    }
    // The starter may bind after its child (events can arrive in any order),
    // so a child that arrives first is rebound once its starter is known.
    const starter = parent ?? (parentID === null ? null : (this.#bindings.get(parentID) ?? null));
    // A child copies the starter's language (§6.1); it does not re-run the
    // ladder. A top-level session resolves the ladder once.
    const binding = bindSession({
      sessionId: this.#sessionID,
      forkOf: starter,
      ladder,
    });
    this.#bindings.set(this.#sessionID, binding);
    appendRecords(this.#file, [
      {
        v: RECORD_VERSION,
        type: RECORD_TYPES.LANGUAGE,
        sessionID: this.#sessionID,
        binding: binding.toRecord(),
      },
    ]);
    return binding;
  }

  get language() {
    return this.#bindings.get(this.#sessionID)?.language ?? null;
  }

  // The binding itself, so a child can copy it without re-resolving the ladder.
  get languageBinding() {
    return this.#bindings.get(this.#sessionID) ?? null;
  }

  // Unsettled park handles of this session (§6.1). They are listed by the Pair
  // Hint; the hint never resumes one on the model's behalf.
  setHandles(names) {
    this.#handles.set(this.#sessionID, [...names].sort());
    return this.#handles.get(this.#sessionID);
  }

  get handles() {
    return this.#handles.get(this.#sessionID) ?? [];
  }

  // The text of this session's last completed answer: what a fission lane
  // receives as the owner's view (§4.2 step 3). A session that has not answered
  // yet has none, and an empty view is not a refusal.
  get lastView() {
    return this.#lastView;
  }

  /**
   * The operator's original input of this task, as a durable fact (根 §2.4 第 3 步).
   *
   * It is captured once, on the first genuine operator receipt, and read from
   * disk afterwards. A transcript cannot answer this: the host compacts, and the
   * earliest user message of a request copy after a compaction is the summarised
   * remainder, not what the operator asked. A session that never recorded one
   * refuses — it never falls back to the newest user message.
   *
   * Every input is a fact about the call, never about its words: `parentID` (a
   * string for a child, `null` for a proven top-level session, `undefined` when
   * unknown), `briefID` (the relay's deterministic brief id, when this receipt
   * carries one) and `delivery` (the adapter delivers its own briefs as
   * `queue`). Text is never inspected.
   *
   * `startedBeforeRecording` marks a session that was already running when this
   * fact was introduced: its next prompt is not its first, so it is marked
   * unrecorded once and never captures afterwards.
   */
  captureOriginalInput({
    text,
    messageID = null,
    parentID,
    registeredParentID = undefined,
    briefID = null,
    delivery = null,
    startedBeforeRecording = false,
  } = {}) {
    // A session that predates the fact gets one durable marker and never
    // captures: labelling a later prompt as the origin is the confusion this
    // module exists to prevent.
    if (startedBeforeRecording === true) {
      const marked = markUnrecorded(this.#root, this.#sessionID);
      return { ok: false, captured: false, reason: "PREDATES_RECORDING", marked: marked.marked === true };
    }
    // A session this adapter created is known from its registration, which was
    // written before its first prompt (`identity.mjs`). That fact outranks a
    // parent the host has not reported yet: the registration says whether the
    // session is top-level, and a child's brief is never the operator's words.
    const effectiveParent = registeredParentID === undefined ? parentID : registeredParentID;
    const verdict = receiptVerdict({ text, parentID: effectiveParent, briefID, delivery });
    if (verdict.capture !== true) return { ok: false, captured: false, reason: verdict.reason };
    const written = captureOriginal(this.#root, this.#sessionID, text, { messageID });
    if (written.ok !== true) {
      return { ok: false, captured: false, reason: written.refusal, detail: written.detail ?? null };
    }
    return { ok: true, captured: written.written === true, record: written.record };
  }

  // The recorded original, or a refusal (`ORIGINAL_INPUT_MISSING` /
  // `ORIGINAL_INPUT_UNREADABLE`). Never a transcript fallback.
  originalInput() {
    return requireOriginal(this.#root, this.#sessionID);
  }

  set lastView(text) {
    this.#lastView = typeof text === "string" ? text : null;
  }

  // The fission group this session owns, as one record (§4.2 step 4). Writing
  // it before any lane starts means a crash between the steps leaves a record
  // that names exactly the lanes that were built.
  recordFissionGroup(group) {
    this.#groups.set(group.groupId, group);
    appendRecords(this.#file, [
      {
        v: RECORD_VERSION,
        type: RECORD_TYPES.FISSION,
        sessionID: this.#sessionID,
        groupId: group.groupId,
        owner: group.owner,
        lanes: group.lanes,
      },
    ]);
    return group;
  }

  // The group was rolled back after its record was written: the withdrawal is
  // its own line, applied in order by a reader.
  discardFissionGroup({ groupId }) {
    if (typeof groupId !== "string" || groupId.length === 0) return { ok: false, reason: "NO_GROUP_ID" };
    this.#groups.delete(groupId);
    appendRecords(this.#file, [
      {
        v: RECORD_VERSION,
        type: RECORD_TYPES.FISSION_DISCARDED,
        sessionID: this.#sessionID,
        groupId,
      },
    ]);
    return { ok: true, groupId };
  }

  get fissionGroups() {
    return [...this.#groups.values()];
  }

  // The worker roster this session's journal holds (§4.1), in the shape
  // `WorkerTable` restores from. Read once when the table is built, because a
  // reloaded activation must not answer "nothing to collect" for a worker the
  // previous activation started.
  get workerRoster() {
    return restoreRoster(
      this.recordsOf(RECORD_TYPES.WORKER)
        .map((record) => record.entry)
        .filter((entry) => entry !== undefined && entry !== null),
    );
  }

  // Append one roster record. `WorkerTable` calls this for every fact `join`
  // decides on, so the roster survives a plugin reload and a process restart.
  recordWorker(entry) {
    if (isWorkerRecord(entry) !== true) return false;
    appendRecords(this.#file, [
      { v: RECORD_VERSION, type: RECORD_TYPES.WORKER, sessionID: this.#sessionID, entry },
    ]);
    return true;
  }

  recordsOf(type) {
    return readRecords(this.#file).filter((record) => record?.type === type);
  }

  appendScope(entries) {
    appendRecords(
      this.#file,
      entries.map((record) => ({ v: RECORD_VERSION, type: RECORD_TYPES.SCOPE, sessionID: this.#sessionID, record })),
    );
  }
}

// The session table of one plugin activation.
export class SessionStates {
  #root;
  #rootOf;
  #resourcesSettled;
  #states = new Map();

  // `root` is the fallback workspace; `rootOf` answers the workspace of one
  // session and wins when it is given. The host's plugin context describes the
  // plugin host, not the session: measured live, `location.directory` and
  // `location.project.directory` were both the directory the server was launched
  // from, while the session itself reports the workspace the operator opened.
  // Facts must live next to the session they belong to, so the session's own
  // answer is used when the adapter has it.
  constructor({ root, rootOf = null, resourcesSettled = null }) {
    this.#root = root;
    this.#rootOf = typeof rootOf === "function" ? rootOf : null;
    // Per session, because the answer is about that session's own roster. A
    // snapshot is not enough: the roster changes between reviews, and the
    // question is asked at the moment of the review.
    this.#resourcesSettled = typeof resourcesSettled === "function" ? resourcesSettled : null;
  }

  get(sessionID) {
    const existing = this.#states.get(sessionID);
    if (existing !== undefined) return existing;
    const state = new SessionState({
      sessionID,
      root: this.#rootOf === null ? this.#root : this.#rootOf(sessionID),
      ...(this.#resourcesSettled === null
        ? {}
        : { resourcesSettled: () => this.#resourcesSettled(sessionID) }),
    });
    this.#states.set(sessionID, state);
    return state;
  }

  has(sessionID) {
    return this.#states.has(sessionID);
  }

  clear() {
    this.#states.clear();
  }
}
