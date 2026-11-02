// Cancellation propagation (root §1.1 item 2).
//
// When the operator presses Esc at a user-facing agent (Plan or Manager), the
// host interrupts that session. The adapter must stop every session that agent
// started, or the descendants keep burning tokens in the background after the
// operator asked them to stop.
//
// The traversal is breadth-first over two tables the adapter already owns:
//
//   activation.workers   one WorkerTable per session that forked; its roster
//                        holds every child session, live or idle
//   activation.lanes     every fission lane, keyed by lane id, with its owner
//
// A successor's roster may exist only in its relay record (the table is built
// lazily), so the record's `roster` is read as a third source. Nothing here
// disposes a session: the host owns it, and the operator may want to read what
// it produced. The fact that it was cancelled is recorded in `activation.cancelled`,
// which `forkWorker` consults, and in the worker table, so `join` reports the
// worker as cancelled instead of as a worker that happened to stop.
//
// Guards. Not every `session.execution.interrupted` is the operator stopping
// the work:
//
//   - `this.ask` interrupts the turn to hand control to the operator. The
//     question is still pending, so the session is waiting, not cancelled.
//   - a relay interrupts the incumbent after the record lands. The successor
//     now owns the work; killing the workers would destroy what the record
//     preserved.
//   - a fission present replacement interrupts the owner on purpose; its lanes
//     are the identity's new presents, not descendants to stop.
//
// Only a real operator interrupt at a top-level user-facing session propagates.

import { roleOf } from "./agent-role.mjs";

const USER_FACING = new Set(["plan", "manager"]);

// Why this event is not the operator stopping the work, or null when it is.
export const cancellationRootOf = ({ activation, sessionID, agent = null }) => {
  if (typeof sessionID !== "string" || sessionID.length === 0) return null;
  const named = roleOf(agent) ?? roleOf(activation.agents?.get(sessionID));
  if (named === null || !USER_FACING.has(named)) return null;
  // A child session is never the operator's top-level root. The parent table is
  // the fact the adapter already trusts for original-input capture; an unknown
  // parent is not proof of top-level, but a Plan or Manager the adapter created
  // as a successor is registered as top-level, and an unknown child would have
  // to be a session the host reported as a child.
  const parent = activation.parents?.get(sessionID);
  if (typeof parent === "string" && parent.length > 0) return null;
  // A lane is a worker's parallel present, never a user-facing root.
  for (const lane of activation.lanes?.values?.() ?? []) {
    if (lane.sessionID === sessionID) return null;
  }
  // The three deliberate interrupts.
  if (activation.asks?.hasPending?.(sessionID) === true) return null;
  if (activation.presentReplacements?.has?.(sessionID) === true) return null;
  const record = activation.states?.get?.(sessionID)?.relay?.record?.() ?? null;
  if (record !== null && record.corrupt !== true && record.incumbent === sessionID) return null;
  return named;
};

// Every descendant session, breadth-first: workers of this session, lanes of
// this session, then the workers and lanes of those sessions. A session appears
// once even when a relay record and a live table both name it.
export const descendantsOf = (activation, root) => {
  const seen = new Set([root]);
  const queue = [root];
  const descendants = [];
  const admit = (sessionID, owner) => {
    if (typeof sessionID !== "string" || sessionID.length === 0) return;
    if (seen.has(sessionID)) return;
    seen.add(sessionID);
    descendants.push({ sessionID, owner });
    queue.push(sessionID);
  };
  while (queue.length > 0) {
    const session = queue.shift();
    const table = activation.workers?.get?.(session);
    if (table !== undefined) {
      for (const worker of table.rosterEntries()) admit(worker.sessionID, session);
    }
    // A successor whose table has not been built yet keeps its roster in the
    // relay record. Reading it here is what makes cancellation reach workers
    // the successor inherited but has not touched.
    const record = activation.states?.get?.(session)?.relay?.record?.() ?? null;
    if (record !== null && record.corrupt !== true && record.successor === session) {
      for (const worker of Array.isArray(record.roster) ? record.roster : []) admit(worker?.sessionID, session);
    }
    for (const lane of activation.lanes?.values?.() ?? []) {
      if (lane.owner === session) admit(lane.sessionID, session);
    }
  }
  return descendants;
};

// Stop every descendant of `sessionID`. Returns what really happened: an
// interrupt the host refused is reported, never turned into a silent success.
//
// Idempotent: the root and each descendant are marked before the host call, so a
// second event for the same session — the host emits several per interrupted
// turn — does not interrupt anything twice and does not report a second
// propagation.
export const propagateCancellation = async ({ activation, sessionID, agent = null }) => {
  const root = cancellationRootOf({ activation, sessionID, agent });
  if (root === null) return { propagated: false, reason: "NOT_AN_OPERATOR_INTERRUPT" };
  if (activation.cancelled?.has(sessionID) === true) {
    return { propagated: false, reason: "ALREADY_CANCELLED", role: root };
  }
  if (activation.cancelled === undefined) activation.cancelled = new Set();
  activation.cancelled.add(sessionID);
  const descendants = descendantsOf(activation, sessionID);
  const interrupted = [];
  const failed = [];
  for (const { sessionID: descendant } of descendants) {
    if (activation.cancelled.has(descendant)) {
      interrupted.push({ sessionID: descendant, already: true });
      continue;
    }
    activation.cancelled.add(descendant);
    // The worker table learns first, so a `join` racing this loop already reads
    // the honest outcome instead of a worker that looks merely idle.
    for (const table of activation.workers?.values?.() ?? []) table.markCancelled?.(descendant);
    if (activation.sessions === null || typeof activation.sessions?.interrupt !== "function") {
      failed.push({ sessionID: descendant, reason: "NO_SESSION_INTERRUPT" });
      continue;
    }
    try {
      await activation.sessions.interrupt({ sessionID: descendant });
      interrupted.push({ sessionID: descendant, ok: true });
    } catch (error) {
      failed.push({ sessionID: descendant, reason: "INTERRUPT_FAILED", detail: String(error?.message ?? error) });
    }
  }
  return {
    propagated: true,
    role: root,
    cancelled: [...activation.cancelled],
    interrupted,
    failed,
  };
};
