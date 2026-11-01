// The three host side effects of `review` (根 §2.3, §2.4, §6.1).
//
// compose's `ReviewMember` decides what a review is and calls exactly one of
// these three ports. This module performs the host part and reports what it
// really did. It never reports a step it did not take: `startManager` and
// `askOperator` answer `ok: true` only after the host call succeeded.
//
// ## How a hand-over becomes one durable fact
//
// 根 §2.4 第 3 步 asks for ONE record: the incumbent is retired, the context barrier is
// written and the successor opens; a crash must not leave half a tenure. The
// host has no such record (`gaps.mjs RELAY_SESSION_CONTROL`), so the adapter
// keeps one — `relay-record.mjs` — and ENFORCES it, because a record nobody
// reads is not an authority:
//
//   1. create the successor session WITHOUT prompting it, so it holds no turn
//      and no history of its own: nothing observable exists yet;
//   2. write the record atomically, naming the incumbent, the successor, the
//      barrier and the brief's message id. From this instant the incumbent is
//      retired, because both gates read the record (`execute-hooks.mjs`,
//      `context-hook.mjs`), whether or not this process survives;
//   3. deliver the brief with that message id. The host's admission table is
//      durable and keyed by the id, so delivering it again after a crash
//      reconciles the earlier delivery instead of repeating the prompt;
//   4. write `sent`, which only removes the uncertainty of step 3;
//   5. interrupt the incumbent. Its requests are already refused, so this only
//      stops work that is still running.
//
// The order is the whole point. A crash before step 2 leaves an unused session
// and no retirement: the incumbent still holds the work. A crash between 2 and 4
// leaves a record that says the successor holds it, and the successor's brief is
// re-delivered under the same id, which the host recognises. A crash after 5 is
// the same state as a finished hand-over.
//
// A record that cannot be written is not a hand-over: the successor created in
// step 1 is removed and the receipt says so. A host without `remove` cannot be
// compensated, and the receipt says that instead of pretending.
//
// ## What is not guaranteed, and is said so
//
//   - the incumbent may still produce one turn's text before it stops;
//   - a request the incumbent makes after the record lands is refused, and the
//     host books that refusal as a failed execution (it is not a silent skip);
//   - the barrier trims the successor's REQUEST copy, not the host's stored
//     history: the successor's own transcript is what the host gave it.

import fs from "node:fs";
import path from "node:path";

import { REVIEW_KINDS } from "../../core/review.mjs";
import {
  RELAY_GATE,
  RELAY_PHASES,
  RELAY_RECORD_VERSION,
  RELAY_STATE_DIR,
  briefIdFor,
  readRelay,
  recoverRelay,
  relayBrief,
  relayFile,
  relayRecord,
  writeRelay,
} from "./relay-record.mjs";
import { requireOriginal } from "./original-input.mjs";
import { registerIdentity } from "./identity.mjs";
import { requireModelOf } from "./model-inherit.mjs";

const failure = (reason, extra = {}) => ({ ok: false, reason, ...extra });

const idOf = (created) => {
  const id = created?.data?.id ?? created?.id ?? null;
  return typeof id === "string" && id.length > 0 ? id : null;
};

// Remove a session this module created. The host's `remove` is the only way to
// take it back: `packages/core/src/session.ts Session.remove` interrupts the
// session, waits for it to go idle, drops its children and deletes it. A host
// without it cannot be compensated, and that is reported.
const discard = async (sessions, sessionID) => {
  if (typeof sessions.remove !== "function") {
    return { removed: false, reason: "NO_SESSION_REMOVE" };
  }
  try {
    await sessions.remove({ sessionID });
    return { removed: true };
  } catch (error) {
    return { removed: false, reason: "SESSION_REMOVE_FAILED", detail: String(error?.message ?? error) };
  }
};

// The Manager this PHYSICAL CALL already started, or `null`.
//
// §2.2: every new empty review starts an independent Manager, and only the
// replay of the SAME call id is deduplicated (§2.4 第 6 步). So the identity
// that decides a repeat is the call id — never the draft's bytes. Two separate
// empty reviews may hand over identical drafts and must each start a Manager;
// one call id that already started one must not start another.
//
// The record is the fact, not a memory: it is written before the Manager is
// prompted, so a crash between "the session exists" and "the Plan learned about
// it" leaves the call id here, and the replay finds it. `handoffId` is null on a
// record written by a caller that had no call id; such a record never matches,
// because it cannot tell a replay from a new review.
export const planHandoffOf = (root, handoffId) => {
  if (typeof root !== "string" || root.length === 0) return null;
  if (typeof handoffId !== "string" || handoffId.length === 0) return null;
  const directory = path.join(root, RELAY_STATE_DIR);
  let entries;
  try {
    entries = fs.readdirSync(directory);
  } catch {
    return null;
  }
  for (const entry of entries) {
    if (!entry.endsWith(".relay.json")) continue;
    const record = readRelay(path.join(directory, entry));
    if (record === null || record.corrupt === true) continue;
    if (record.kind !== "plan-handoff") continue;
    if (record.handoffId !== handoffId) continue;
    if (typeof record.successor !== "string" || record.successor.length === 0) continue;
    return record;
  }
  return null;
};

const NOT_GUARANTEED = Object.freeze([
  "the retired session may still produce one turn's text before it stops",
  "a request from the retired session is refused by the adapter's gates and booked by the host as a failed execution",
  "the barrier trims the successor's request copy, not the history the host already stored for it",
]);

// The operator's original request (根 §2.4 第 3 步) comes from its own durable
// fact, never from the transcript.
//
// The transcript is not the origin: the host compacts, and the earliest user
// message in a request copy after a compaction is the summarised remainder.
// Reading "the first user turn" here would hand the successor the latest text
// while calling it the original — the exact confusion original-input.mjs
// exists to prevent. The fact is captured on the first genuine operator receipt
// (prompt-hook.mjs) and read back here.
//
// A missing or unreadable fact refuses the hand-over. There is no fallback: a
// successor without the operator's own words would be starting a different
// task, and a substitute would make that difference invisible.

// What this host can refuse. Both channels are facts about the host, not wishes:
//
//   - `toolGate`: `execute.before` runs before the tool's executor and a throw
//     stops the call (`plugin.mjs`), so a retired tenure cannot act;
//   - `requestGate`: a throwing `context` hook fails the execution and no
//     request reaches the provider (`hooks.trigger` runs the callback inside the
//     session drain, so the throw becomes a defect of the drain).
//
// A record that claimed retirement without both would describe a retirement the
// adapter cannot carry out, so `recoverRelay` refuses to resume it.
const HOST_GATES = Object.freeze({ requestGate: RELAY_GATE.HOST, toolGate: RELAY_GATE.HOST });

// `sessions` is the host SessionDomain; `root` is the workspace the record lives
// under; `successorAgent` is the agent id a successor runs as; `stateOf` answers
// one session's `SessionState`, which the successor's ledger is recorded on.
// `rootOfSession` answers the workspace the PREDECESSOR's own facts live in.
// The two roots are not interchangeable: the record is written into the
// incumbent's workspace, and the incumbent's original input, its roster and the
// devops bound to its road are all facts of that same workspace. A hand-over
// that wrote its record under one root and read its evidence from another would
// either refuse a predecessor whose facts exist or retire one on the strength of
// another workspace's file.
export const makeRelayHost = ({
  sessions,
  root = null,
  successorAgent = "Manager",
  stateOf = null,
  workersOf = null,
  registerCreated = null,
  rootOfSession = null,
  draftOf = null,
}) => {
  const unusable = (reason) => ({
    handOver: async () => failure(reason),
    startManager: async () => failure(reason),
    askOperator: async () => failure(reason),
  });
  if (sessions === null || sessions === undefined) return unusable("NO_SESSION_DOMAIN");
  if (typeof root !== "string" || root.length === 0) return unusable("NO_WORKSPACE_ROOT");
  // Record a session this host created, before its first prompt. The relay's own
  // registration is the file one, so a host built without the plugin still
  // protects a successor; a caller that supplies the activation's registration
  // gets the in-process table updated too.
  const noteCreated = (sessionID, fact) => {
    if (typeof registerCreated === "function") registerCreated(sessionID, fact);
    else registerIdentity(root, sessionID, fact);
  };

  // The workspace a predecessor's own facts live in. The plugin resolves it per
  // session (`resolveSessionRoot`: the operator's stated root, then the host's
  // session location), so a hand-over must ask rather than assume the plugin
  // host's root. An answer that is not a usable path falls back to `root`: the
  // record is written under that same value, so writer and reader still agree.
  const workspaceOf = (sessionID) => {
    if (typeof rootOfSession !== "function") return root;
    let answered;
    try {
      answered = rootOfSession(sessionID);
    } catch {
      return root;
    }
    return typeof answered === "string" && answered.length > 0 ? answered : root;
  };

  // The incumbent's transcript, read through the host's own API.
  //
  // `Session.context` (`packages/protocol/src/groups/session.ts:506`) is
  // `GET /api/session/:sessionID/context`, "all messages after the last
  // compaction", and the plugin's `SessionDomain` exposes it as
  // `session.context` (`packages/plugin/src/promise/session.ts:170`). It is the
  // only route a plugin has to the transcript, so it is the one used here.
  //
  // Returns `{ messages }` on success and `{ unavailable }` when the host gives
  // no such method or the call fails. The caller turns "unavailable" into a
  // refusal: 根 §2.4 第 3 步 needs the operator's original input in the record, and a
  // relay that cannot name it must not pretend it did.
  const readIncumbentMessages = async (domain, sessionID) => {
    if (typeof domain.context !== "function") return { unavailable: "NO_SESSION_CONTEXT" };
    try {
      const listed = await domain.context({ sessionID });
      const messages = Array.isArray(listed) ? listed : (listed?.data ?? []);
      if (!Array.isArray(messages)) return { unavailable: "NO_SESSION_CONTEXT" };
      return { messages };
    } catch (error) {
      return { unavailable: "SESSION_CONTEXT_FAILED", detail: String(error?.message ?? error) };
    }
  };

  // Deliver one brief and settle the record. `record.briefId` is the message id
  // the host reconciles on, so a second delivery of the same brief is the same
  // input, not a second one.
  const deliver = async (file, record) => {
    const sending = writeRelay(file, { ...record, phase: RELAY_PHASES.SENDING });
    if (sending.ok !== true) {
      return { ok: false, reason: "RELAY_RECORD_FAILED", detail: sending.detail ?? null, record };
    }
    try {
      await sessions.prompt({
        sessionID: record.successor,
        id: record.briefId,
        text: relayBrief(record),
        delivery: "queue",
      });
    } catch (error) {
      // The record says the successor holds the work, and the host may or may
      // not have admitted the brief. `sending` is exactly that uncertainty, and
      // recovery resolves it by delivering the same id again.
      return { ok: false, reason: "RELAY_PROMPT_FAILED", detail: String(error?.message ?? error), record };
    }
    const sent = writeRelay(file, { ...record, phase: RELAY_PHASES.SENT });
    return { ok: true, record: { ...record, phase: RELAY_PHASES.SENT }, durable: sent.ok === true };
  };

  // The successor's starting row, written on every path that can complete a
  // hand-over — the first delivery and both recoveries. `recordRelayStart` is
  // idempotent per tenure, so calling it again on a `done` record costs nothing
  // and repairs a row the first attempt could not write.
  //
  // The answer is `{ ok: true }` or `{ ok: false, detail }`; it is never thrown,
  // because the caller turns a failure into a receipt rather than a stack.
  const recordSuccessorRow = (lookup, successor, milestone, kind = "manager") => {
    if (typeof lookup !== "function") return { ok: false, detail: "NO_SESSION_STATE" };
    if (typeof successor !== "string" || successor.length === 0) {
      return { ok: false, detail: "NO_SUCCESSOR" };
    }
    try {
      const wrote = lookup(successor).recordRelayStart(milestone, kind);
      return wrote === false ? { ok: false, detail: "recordRelayStart answered false" } : { ok: true };
    } catch (error) {
      return { ok: false, detail: String(error?.message ?? error) };
    }
  };

  // The ledger side a record continues. A record written before the side was
  // stored describes a Manager relay: that was the only relay then.
  const sideOf = (record) => (record?.kind === "plan" ? "plan" : "manager");
  const agentFor = (side) => (side === "plan" ? "Plan" : successorAgent);

  // The Plan hand-off is the exit a Plan earns (根 §2.2), so the Plan's own
  // tenure records it. The fact is durable and names the Manager it started;
  // the turn-exit controller reads it instead of inferring a hand-off from the
  // milestone, which a successor's `confirm` can also reach.
  const noteHandoff = (planSession, managerSession, handoffId) => {
    if (typeof planSession !== "string" || planSession.length === 0) return;
    if (typeof stateOf !== "function") return;
    try {
      stateOf(planSession).noteHandoff?.({ to: managerSession, handoffId });
    } catch {
      // The Manager is already started; a fact that cannot be written is
      // reported by the next turn's nudge (the hand-off is not proven), never
      // by undoing a session the operator can already see.
    }
  };

  // Deliver a Plan hand-off's draft and settle its record (根 §2.2, §2.4 第 4 步).
  //
  // The record is written in `sending` BEFORE the prompt and moved to `sent`
  // after it. A crash between the two therefore leaves a record that says
  // "the draft was not confirmed", and the same call re-delivers it under the
  // SAME deterministic message id — the host's durable admission table
  // reconciles the duplicate. A record written `sent` before the prompt could
  // instead leave an inert Manager nobody ever delivers to.
  const deliverHandoff = async (file, record, draft) => {
    const sending = writeRelay(file, { ...record, phase: RELAY_PHASES.SENDING });
    if (sending.ok !== true) {
      return failure("MANAGER_RECORD_FAILED", {
        session: null,
        record,
        detail: sending.detail ?? sending.reason ?? null,
      });
    }
    try {
      await sessions.prompt({
        sessionID: record.successor,
        ...(typeof record.briefId === "string" ? { id: record.briefId } : {}),
        text: draft,
        delivery: "queue",
      });
    } catch (error) {
      // The Manager never took the draft. It is taken back, and the record that
      // named it is removed when the session really is gone: a record naming a
      // session nobody can find would make a repeat report a Manager that does
      // not exist.
      const cleanup = await discard(sessions, record.successor);
      if (cleanup.removed === true) {
        try {
          fs.rmSync(file, { force: true });
        } catch {
          // The record is left in `sending`; a repeat re-delivers it, which is
          // the safe direction.
        }
      }
      return failure("MANAGER_PROMPT_FAILED", {
        session: null,
        discarded: record.successor,
        cleanup,
        detail: String(error?.message ?? error),
      });
    }
    const sent = writeRelay(file, { ...record, phase: RELAY_PHASES.SENT });
    return { ok: true, record: { ...record, phase: RELAY_PHASES.SENT }, durable: sent.ok === true };
  };

  return {
    // Retirement, barrier and successor start (根 §2.3 step 2).
    async handOver({ predecessor, milestone, tenure, notice, kind = "manager" } = {}) {
      if (typeof predecessor !== "string" || predecessor.length === 0) {
        return failure("NO_PREDECESSOR");
      }
      // Which side is retiring (根 §2.4 第 3 步). The same port serves the Plan
      // and the Manager surface, and the successor must run the side the
      // predecessor ran: a Plan replaced by a Manager loses its tenure and its
      // milestone. The side is a committed field of the record below.
      const side = kind === "plan" ? "plan" : "manager";
      // Everything about the predecessor is read from ITS workspace: the record
      // (which is also where the gates look for it), the operator's original
      // input and the worker roster. See the header.
      const workspace = workspaceOf(predecessor);
      const file = relayFile(workspace, predecessor);

      // A record that already exists is a hand-over this workspace has seen. It
      // is recovered, never repeated: `resume` delivers the same brief under the
      // same id, `done` reports the successor, and `corrupt` refuses because
      // nobody can tell who was retired.
      const existing = recoverRelay(file);
      if (existing.kind === "corrupt") {
        return failure("RELAY_CORRUPT", { detail: existing.reason ?? null, notGuaranteed: NOT_GUARANTEED });
      }
      if (existing.kind === "done") {
        // A record that already finished still owes its successor's row. The
        // first delivery may have ended with `SUCCESSOR_LEDGER_NOT_DURABLE`, and
        // this repeat is the recovery: `recordRelayStart` is idempotent, so it
        // writes the missing row and leaves an existing one alone.
        const row = recordSuccessorRow(
          stateOf,
          existing.record.successor,
          existing.record.milestone,
          sideOf(existing.record),
        );
        if (row.ok !== true) {
          return {
            ok: false,
            reason: "SUCCESSOR_LEDGER_NOT_DURABLE",
            retired: true,
            barrier: true,
            successor: existing.record.successor,
            successorRow: row,
            record: existing.record,
            recovered: true,
            notGuaranteed: NOT_GUARANTEED,
          };
        }
        return {
          ok: true,
          retired: true,
          barrier: true,
          successor: existing.record.successor,
          record: existing.record,
          ledger: row,
          recovered: true,
          notGuaranteed: NOT_GUARANTEED,
        };
      }
      if (existing.kind === "unenforceable") {
        // The record names a retirement this host cannot carry out on both
        // channels. Starting a successor would leave a predecessor that still
        // acts, so the hand-over is refused before any host call.
        return failure("RELAY_UNENFORCEABLE", {
          retired: false,
          barrier: false,
          successor: null,
          record: existing.record,
          detail: "the record's requestGate/toolGate do not match what this host can refuse",
          notGuaranteed: NOT_GUARANTEED,
        });
      }
      if (existing.kind === "undeliverable") {
        return failure("RELAY_UNDELIVERABLE", {
          record: existing.record,
          detail: "the record names no idempotent message id, so a repeated delivery cannot be recognised",
          notGuaranteed: NOT_GUARANTEED,
        });
      }
      if (existing.kind === "resume") {
        const delivered = await deliver(file, existing.record);
        if (delivered.ok !== true) return { ...delivered, recovered: true, notGuaranteed: NOT_GUARANTEED };
        const row = recordSuccessorRow(
          stateOf,
          existing.record.successor,
          existing.record.milestone,
          sideOf(existing.record),
        );
        if (row.ok !== true) {
          return {
            ok: false,
            reason: "SUCCESSOR_LEDGER_NOT_DURABLE",
            retired: true,
            barrier: true,
            successor: existing.record.successor,
            successorRow: row,
            record: delivered.record,
            recovered: true,
            notGuaranteed: NOT_GUARANTEED,
          };
        }
        return {
          ok: true,
          retired: true,
          barrier: true,
          successor: existing.record.successor,
          record: delivered.record,
          ledger: row,
          recovered: true,
          notGuaranteed: NOT_GUARANTEED,
        };
      }

      // Step 1: an inert successor. No `parentID`: a parent would give it the
      // family and with it the conversation the barrier exists to cut.
      //
      // The transcript is read FIRST. Both facts the record needs come from it
      // (`fenceThrough` and `operatorInput`), and a host that cannot show the
      // transcript cannot supply them. Reading it after `create` would leave an
      // orphan successor on every refusal, which is exactly the half-tenure
      // 根 §2.4 第 3 步 forbids: no record, no retirement, and no session either.
      // The operator's original is read from its own fact, and the transcript
      // only supplies the fence length. Both are read before any host call, so a
      // refusal leaves no successor behind.
      const original = requireOriginal(workspace, predecessor);
      if (original.ok !== true) {
        return failure(original.refusal, {
          retired: false,
          barrier: false,
          successor: null,
          detail: original.detail ?? null,
        });
      }
      const operatorInput = original.text;
      // The successor runs what the predecessor ran (根 §2.4 第 3 步). The host does not
      // inherit a model for a created child (`model-inherit.mjs`), and the agent
      // default is the free model that answers 429, so the model is read from the
      // predecessor and passed explicitly. A predecessor without one is a refusal
      // before any session exists.
      //
      // This is the hand-over's FIRST host read: it is one cheap `session.get`,
      // it can refuse, and a refusal must not spend a transcript read on a
      // successor that can never be created. The transcript follows.
      const inherited = await requireModelOf(sessions, predecessor);
      if (inherited.ok !== true) {
        return failure(inherited.refusal.reason, {
          retired: false,
          barrier: false,
          successor: null,
          detail: inherited.refusal.detail ?? null,
        });
      }
      const transcript = await readIncumbentMessages(sessions, predecessor);
      if (transcript.unavailable !== undefined) {
        return failure("RELAY_TRANSCRIPT_UNAVAILABLE", {
          retired: false,
          barrier: false,
          successor: null,
          detail: transcript.unavailable,
          hostDetail: transcript.detail ?? null,
        });
      }
      const fenceThrough = transcript.messages.length;

      // What 根 §2.4 says a relay keeps: accepted work, process ownership and
      // unclaimed results, with the road-bound `devops` unchanged. All of it is
      // held in host sessions the incumbent alone could name, so the record
      // commits the whole roster — not only the live workers. An idle worker is
      // still where a name points, and a finished one may hold a result nobody
      // joined yet.
      // The roster is keyed by the predecessor's session id inside this
      // activation, and its worker sessions belong to the same workspace, so the
      // lookup takes the workspace the record is written under.
      const carried =
        typeof workersOf === "function" ? (workersOf(predecessor, { root: workspace }) ?? {}) : {};
      const roster = Array.isArray(carried.roster) ? carried.roster : [];
      const unclaimed = Array.isArray(carried.unclaimed) ? carried.unclaimed : [];
      const devops = Array.isArray(carried.devops) ? carried.devops : [];
      if (roster.some((worker) => typeof worker?.sessionID !== "string")) {
        // A name with no host session cannot be preserved. Failing here, before
        // the record and before `create`, is the only way to keep the promise:
        // a committed relay that silently dropped it would be a false success.
        return failure("RELAY_ROSTER_UNPRESERVABLE", {
          retired: false,
          barrier: false,
          successor: null,
          detail: "a worker in the predecessor's roster has no session id",
        });
      }

      let created;
      try {
        created = await sessions.create({
          title: side === "plan" ? "plan" : "manager",
          agent: agentFor(side),
          model: inherited.model,
          metadata: { herdchestra: "relay-successor", from: predecessor, kind: side },
        });
      } catch (error) {
        return failure("RELAY_SUCCESSOR_FAILED", {
          retired: false,
          barrier: false,
          successor: null,
          detail: String(error?.message ?? error),
        });
      }
      const successor = idOf(created);
      if (successor === null) {
        return failure("RELAY_SUCCESSOR_FAILED", {
          retired: false,
          barrier: false,
          successor: null,
          detail: "the host returned no session id",
        });
      }
      // Registered before the brief is delivered: the brief is this adapter's own
      // delivery, and the successor is a top-level session (`identity.mjs`).
      noteCreated(successor, { parentID: null, role: agentFor(side), origin: "relay-successor" });

      // Step 2: the record. This write is what retires the incumbent; if it
      // fails, the session created in step 1 is taken back and nothing retired.
      //
      // `fenceThrough` and `operatorInput` were read from the incumbent's
      // transcript before step 1, so no refusal can leave a session behind.
      let record;
      try {
        // A Plan's saved draft is one persistent product (§2.2). The successor
        // reads and writes the same file, so the name and the workspace travel
        // in the record; a successor keyed by its own id would look for a draft
        // nobody wrote.
        const draft = side === "plan" && typeof draftOf === "function" ? draftOf(predecessor) : null;
        record = relayRecord({
          sessionID: predecessor,
          incumbent: predecessor,
          successor,
          tenure: Number.isInteger(tenure) ? tenure + 1 : 1,
          milestone: typeof milestone === "string" && milestone.length > 0 ? milestone : "execute",
          kind: side,
          ...(draft === null || draft === undefined
            ? {}
            : { draft: draft.name ?? null, draftRoot: draft.root ?? null }),
          notice,
          fenceThrough,
          operatorInput,
          roster,
          unclaimed,
          boundDevops: devops,
          // The gates this host really has. Without them the record would claim
          // a retirement neither channel can enforce, and `recoverRelay` would
          // refuse to resume the very record this adapter just wrote.
          ...HOST_GATES,
        });
      } catch (error) {
        const cleanup = await discard(sessions, successor);
        return failure("RELAY_RECORD_INVALID", {
          retired: false,
          barrier: false,
          successor: null,
          cleanup,
          detail: String(error?.message ?? error),
        });
      }
      const written = writeRelay(file, record);
      if (written.ok !== true) {
        const cleanup = await discard(sessions, successor);
        return failure("RELAY_RECORD_FAILED", {
          retired: false,
          barrier: false,
          successor: null,
          cleanup,
          detail: written.detail ?? written.reason ?? null,
        });
      }

      // Steps 3-4. From here the record is the authority even if this process
      // dies: the incumbent is refused by the gates, and the successor's brief
      // is recognised by its id when it is delivered again.
      const delivered = await deliver(file, record);
      if (delivered.ok !== true) {
        return {
          ...delivered,
          retired: true,
          barrier: true,
          successor,
          notGuaranteed: NOT_GUARANTEED,
        };
      }

      // The successor judges from the milestone the relay named (根 §2.4 第 3 步), and
      // that fact is durable only once its own row is on disk. A row that cannot
      // be written therefore makes the hand-over incomplete: the receipt says
      // `ok: false` with `retired`/`barrier` true, because the gates already
      // refuse the incumbent and cannot be un-refused, while `successorRow`
      // names the missing half.
      //
      // Retrying is safe and is the recovery: the record is on disk in phase
      // `sent`, so a repeat finds it, delivers nothing twice, and writes the row
      // it could not write. That is why the failure keeps the record instead of
      // inventing a second one.
      const ledger = recordSuccessorRow(stateOf, successor, record.milestone, side);

      // Step 5: stop what the incumbent is still running. A refusal here does
      // not undo the retirement: the gates already refuse its requests.
      let interrupted = null;
      if (typeof sessions.interrupt === "function") {
        try {
          await sessions.interrupt({ sessionID: predecessor });
          interrupted = { ok: true };
        } catch (error) {
          interrupted = { ok: false, detail: String(error?.message ?? error) };
        }
      } else {
        interrupted = { ok: false, reason: "NO_SESSION_INTERRUPT" };
      }

      if (ledger?.ok !== true) {
        // The successor holds the work, but it cannot restore its tenure. The
        // receipt must not claim a complete hand-over: a caller that read
        // `ok: true` would go on as if the successor could judge from the
        // relay's milestone, and a cold start would silently drop to tenure 0.
        return {
          ok: false,
          reason: "SUCCESSOR_LEDGER_NOT_DURABLE",
          retired: true,
          barrier: true,
          successor,
          successorRow: ledger,
          record: delivered.record,
          interrupted,
          notGuaranteed: NOT_GUARANTEED,
        };
      }

      return {
        ok: true,
        retired: true,
        barrier: true,
        successor,
        record: delivered.record,
        ledger,
        interrupted,
        notGuaranteed: NOT_GUARANTEED,
      };
    },

    // A Plan handoff starts an independent Manager session from the saved draft
    // (§2.2). The Plan stays where it was; the Manager starts at `execute`. A
    // failure after creation removes the session, so a refused handoff leaves no
    // half-open Manager behind.
    //
    // `planSession` is the Plan this hand-off comes from, and the Manager runs
    // that session's model (`model-inherit.mjs`). A hand-off without one has no
    // parent to inherit from: it is refused by name, before any host call, so a
    // Manager can never fall to the agent default (the free model that answers
    // 429). Every refusal below names its own cause; none of them is silent.
    async startManager(draft, { operatorInput = null, planSession = null, handoffId = null } = {}) {
      if (typeof draft !== "string" || draft.trim() === "") return failure("DRAFT_MISSING");
      if (typeof planSession !== "string" || planSession.length === 0) {
        return failure("NO_PARENT_SESSION", { session: null, detail: "NO_SESSION" });
      }
      // A Manager that restarts must judge from `execute` as a session the Plan
      // started. Its row is the durable half of that, so a hand-off that cannot
      // write it is not reported as complete — the same rule `handOver` keeps
      // for its successor. The record stays, so a repeat repairs the row.
      const settledHandoff = (session, record = null) => {
        const row = recordSuccessorRow(stateOf, session, "execute", "manager");
        if (row.ok !== true) {
          return failure("SUCCESSOR_LEDGER_NOT_DURABLE", {
            session,
            successorRow: row,
            record,
          });
        }
        // The Plan's own tenure records the hand-off (根 §2.2: the hand-off is
        // the exit it earns). The turn-exit controller reads this fact instead
        // of inferring it from the milestone.
        noteHandoff(planSession, session, handoffId);
        return { ok: true, session, ledger: row };
      };
      // The replay of THIS call id reports the Manager it already started
      // instead of starting a second one (see `planHandoffOf`). No host call
      // happens here: the fact is the record on disk. A different call id is a
      // different hand-off, even with byte-identical draft text (§2.2).
      const existing = planHandoffOf(root, handoffId);
      if (existing !== null) {
        // A record in `sent` is a finished hand-off; its row and the Plan's own
        // hand-off fact are repaired here because the crash may have happened
        // between the delivery and either write.
        if (existing.phase === RELAY_PHASES.SENT) {
          return { ...settledHandoff(existing.successor, existing), recovered: true };
        }
        // The record landed but the delivery was never confirmed: re-deliver the
        // same draft under the same message id, then settle.
        const file = relayFile(root, existing.successor);
        const text =
          typeof existing.draft === "string" && existing.draft.length > 0 ? existing.draft : draft;
        const delivered = await deliverHandoff(file, existing, text);
        if (delivered.ok !== true) return { ...delivered, recovered: true };
        return { ...settledHandoff(existing.successor, delivered.record), recovered: true };
      }
      // 根 §2.4 第 4 步: a successor sees the operator's original words. A Plan's
      // Manager is that successor — the Plan never spoke to the operator's task
      // without it. The text is carried in the first prompt, because the host
      // has no way to hand a session a fact other than through its messages.
      const carried = typeof operatorInput === "string" && operatorInput.trim().length > 0 ? operatorInput : null;
      // The Manager runs what the Plan ran (`model-inherit.mjs`): a session
      // created without a model falls to the agent default, which on the live host
      // is the free model that answers 429. `planSession` is guaranteed non-empty
      // above, so this reads a real session and never an absent one.
      const inherited = await requireModelOf(sessions, planSession);
      if (inherited.ok !== true) {
        return failure(inherited.refusal.reason, {
          session: null,
          detail: inherited.refusal.detail ?? null,
        });
      }
      let created;
      try {
        created = await sessions.create({
          title: "manager",
          agent: successorAgent,
          model: inherited.model,
          metadata: { herdchestra: "manager", from: "plan" },
        });
      } catch (error) {
        return failure("MANAGER_CREATE_FAILED", { detail: String(error?.message ?? error) });
      }
      const session = idOf(created);
      if (session === null) {
        return failure("MANAGER_CREATE_FAILED", { detail: "the host returned no session id" });
      }
      // The Manager's starting point is a durable fact, not a prompt that
      // happens to have been sent (§2.2). It is written BEFORE the session is
      // registered and prompted, so the step that has no way back — creating a
      // Manager session — is the only step whose repeat cannot be detected, and
      // a crash after it is repaired by the same call instead of leaving a
      // Manager nobody can find.
      //
      // `plan-handoff` names no incumbent and retires nobody; it carries the
      // operator's original words (§2.4 第 4 步) so the barrier can put them
      // into the Manager's first request.
      const handoffFile = relayFile(root, session);
      // The message id is a function of the two identities, so a process that
      // crashed before `sent` derives the same id and the host reconciles the
      // second delivery instead of admitting a duplicate. The record itself is
      // written in `sending`: nothing has been delivered yet.
      const record = {
        v: RELAY_RECORD_VERSION,
        kind: "plan-handoff",
        successor: session,
        // No session is retired by a hand-off. The field is required by
        // `readRelay` and must not name the successor, or the successor would
        // refuse itself; a value that cannot be a session id is the honest
        // "nobody".
        incumbent: "",
        sessionID: session,
        milestone: "execute",
        tenure: 0,
        phase: RELAY_PHASES.SENDING,
        briefId: briefIdFor({ incumbent: planSession, successor: session, tenure: 1 }),
        notice: "",
        operatorInput: carried,
        draft,
        // The Plan this hand-off came from (audit only) and the physical call id
        // that asked for it. The call id is what a replay matches on
        // (`planHandoffOf`); the Plan alone must never deduplicate, because the
        // same Plan starts a new Manager on every new empty review (§2.2).
        from: planSession,
        handoffId: typeof handoffId === "string" && handoffId.length > 0 ? handoffId : null,
        at: Date.now(),
      };
      const wrote = writeRelay(handoffFile, record);
      if (wrote.ok !== true) {
        const cleanup = await discard(sessions, session);
        return failure("MANAGER_RECORD_FAILED", {
          session: null,
          discarded: session,
          cleanup,
          detail: wrote.detail ?? wrote.reason ?? null,
        });
      }
      // Registered before the draft: the draft is this adapter's own delivery,
      // not the operator's original input, and the Manager it starts is a
      // top-level session (`identity.mjs`). Without this the host's plain prompt
      // is captured as the operator's words (`live5`).
      noteCreated(session, { parentID: null, role: successorAgent, origin: "plan-handoff" });
      const delivered = await deliverHandoff(handoffFile, record, draft);
      if (delivered.ok !== true) return delivered;
      return settledHandoff(session, delivered.record);
    },

    // The first review of the first tenure is not a review: the system asks the
    // operator what to do (根 §2.3 step 3). The question is shown in the incumbent
    // session as a synthetic message and its text is the answer the ledger
    // receives. The host has no blocking question for a plugin
    // (`gaps.mjs ASK_CHANNEL`), so the answer is the next operator prompt; this
    // call only makes the question visible and says that it is pending.
    async askOperator({ sessionID, question }) {
      if (typeof sessionID !== "string" || sessionID.length === 0) {
        return failure("NO_SESSION");
      }
      if (typeof question !== "string" || question.trim() === "") {
        return failure("NO_QUESTION");
      }
      try {
        await sessions.synthetic({
          sessionID,
          text: question,
          description: "herdchestra: question",
          metadata: { herdchestra: "askOperator" },
          resume: false,
        });
      } catch (error) {
        return failure("ASK_FAILED", { detail: String(error?.message ?? error) });
      }
      return { ok: true, pending: true, question };
    },
  };
};

// Which side a ledger belongs to. `ReviewLedger.kind` is the authority.
export const ledgerKind = (ledger) => ledger?.kind ?? null;

export { REVIEW_KINDS };
