// `session.hook("context")`: one request copy, built once (§6.2, §6.1).
//
// The hook runs for every model request of a session, including compaction and
// title requests. The adapter changes only sessions whose agent it owns:
//
//   1. translate the host messages into Scope items;
//   2. project them (old Scope beats removed);
//   3. cut the copy at the relay barrier (根 §2.4 第 3 步): a successor reads the
//      operator's input and the succession notice, never the conversation
//      before the relay; a retired predecessor is refused outright;
//   4. translate back into host messages;
//   5. hand the copy to the session's `PairHint#project`, which places the pair
//      once per epoch and replays it at the recorded position;
//   6. append the director segment to the Manager's system prompt;
//   7. narrow `event.tools` to the role's catalog.
//
// Nothing is written to the host's history: `event` is a copy and every step
// produces a new value. A step that refuses leaves the request as it was, so a
// refusal never becomes a silently altered prompt.
//
// The hint is never stripped and re-appended here. Its position belongs to the
// presentation fact, and moving it would break the prefix chain (§6.1); a
// request that would move the prefix is refused by the injector, and that
// refusal is reported, not repaired.
//
// ## Two shapes, one copy
//
// The host speaks `{type:"tool-call", id, name, input}` / `{type:"tool-result",
// id, name, result}`; core's Pair Hint speaks `toolName` / `toolCallId` /
// `output`. The copy therefore crosses the boundary twice
// (`message-adapter.mjs`): the host's messages are read, the hint works on its
// own shape, and the result is written back in the host's shape before the
// request leaves. Writing the hint's parts straight into `event.messages` is
// what crashed the host's request builder (`part.result.type` of undefined).
//
// A copy that cannot be built is not sent at all: the hook throws, the host
// fails the execution, and no request reaches the provider (§6.1). The adapter
// never falls back to the host's unprojected messages, because a request without
// the hint would silently drop §6.1.

import { DEFAULT_PINNED_MEMBERS } from "../../core/scope/errors.mjs";
import { messageShapes } from "../../core/pair-hint/facts.mjs";
import { digestSide } from "../../core/pair-hint/chain.mjs";
import { REBASE_REASONS } from "../../core/pair-hint/pair-hint.mjs";
import { SURFACE_MEMBERS } from "../../compose/program/surface.mjs";
import { itemsFromMessages, messagesFromItems, toHintMessages, toHostMessages } from "./message-adapter.mjs";
import { closeTurn, projectRequest } from "./scope-bridge.mjs";
import { injectHint } from "./pair-hint-request.mjs";
import { withDirector } from "./director-injection.mjs";
import { narrowTools } from "./narrowing.mjs";
import { roleOf } from "./agent-role.mjs";
import { applyBarrier } from "./relay-record.mjs";
import { projectionBoundary, shouldAnchor } from "../../compose/request-boundary.mjs";

// A successor's first request opens the session's prefix chain. The barrier
// rewrites the copy (根 §2.4 第 3 步): the predecessor's conversation leaves, and
// the operator's input plus the succession notice take its place. The host's
// stored history still begins with the retired conversation, so comparing the
// successor's first request against whatever the hint last presented in this
// session would always drift.
//
// The barrier is the one place where a new prefix legitimately begins: the
// successor is a different tenure reading a different conversation, and 根 §2.4 第 3 步
// requires that cut. The hint is therefore re-anchored to the successor's own
// first request, once, at the moment the barrier applies.
export const opensPrefixChain = (record, sessionID) =>
  record !== null &&
  record !== undefined &&
  record.corrupt !== true &&
  record.successor === sessionID;

// One side of a refusal, reduced to what a log may keep. A request side is a
// message list (roles, part types, lengths, digests); a chain side is the
// persisted state and carries digests only. Anything else is dropped rather
// than copied: the host log must never receive the conversation.
const shapedSide = (side) => {
  if (side === null || side === undefined) return null;
  if (Array.isArray(side)) return messageShapes(side);
  if (Array.isArray(side.digests)) return digestSide(side.digests);
  return null;
};

const shapedComparison = (compared) => {
  if (compared === null || compared === undefined) return null;
  return { previous: shapedSide(compared.previous), next: shapedSide(compared.next) };
};

// The Pair Hint's synthetic pair carries the empty tool name, which no pinned
// member list names, and it is not a beat of any Scope: it is presentation.
// §6.2 keeps the pinned members and their results; the pair belongs to that
// class, so it is pinned by name. The `js-review` members are pinned too: they
// are the Manager's evaluation channel (§8), not work the Scope removes.
const PINNED = Object.freeze([...DEFAULT_PINNED_MEMBERS, "", ...SURFACE_MEMBERS["js-review"]]);


// The synthetic pair this adapter placed: an assistant message whose only part
// is a tool call with the hint's call id, followed by the tool message that
// answers it. `rest` is the request without them; `at` is where they stood.
export const PAIR_CALL = /^pair-hint:/;

// The call id of one part, in the host's own vocabulary.
//
// The hint builds the pair with `toolCallId` and no `id`
// (`core/pair-hint/presentation.mjs` `syntheticMessages`), and the adapter's
// `toHostMessages` passes the part through unchanged. Reading `part.id` alone
// therefore found no pair at all: `extractPair` returned `at: -1`, the copy went
// to the hint with the pair still inside it, the hint replayed it at its own
// index, and the prefix check compared the host's copy against the hint's and
// refused with `PREFIX_DRIFT` at index 1 — on every replay of a request that
// already carried the pair (live: `ses_e6b69caa1ffeegDxoYLb1Gcrkj`, 2026-11-01).
// `message-adapter.mjs` reads both keys for the same reason; a real host tool
// call carries both, ours carries only the one.
export const callIdOf = (part) => {
  if (part === null || typeof part !== "object") return "";
  if (typeof part.toolCallId === "string") return part.toolCallId;
  return typeof part.id === "string" ? part.id : "";
};

export const extractPair = (messages) => {
  const list = Array.isArray(messages) ? messages : [];
  for (let index = 0; index < list.length; index += 1) {
    const message = list[index];
    if (message?.role !== "assistant" || !Array.isArray(message.content)) continue;
    const call = message.content.find((part) => part?.type === "tool-call" && PAIR_CALL.test(callIdOf(part)));
    if (call === undefined) continue;
    const callId = callIdOf(call);
    const next = list[index + 1];
    const isResult =
      next?.role === "tool" &&
      Array.isArray(next.content) &&
      next.content.some((part) => part?.type === "tool-result" && callIdOf(part) === callId);
    const remove = isResult ? 2 : 1;
    return {
      at: index,
      pair: list.slice(index, index + remove),
      rest: [...list.slice(0, index), ...list.slice(index + remove)],
    };
  }
  return { at: -1, pair: [], rest: [...list] };
};

// Put the pair back where it stood. The hint's copy does not contain it, so the
// pair is inserted at the recorded index; a request shorter than that index puts
// it at the end.
export const withPair = (messages, carried) => {
  if (carried.pair.length === 0) return messages;
  const at = Math.min(carried.at < 0 ? messages.length : carried.at, messages.length);
  return [...messages.slice(0, at), ...carried.pair, ...messages.slice(at)];
};

// The pair in the copy the hint just returned, or null. The hint replays a pair
// it still holds (a request that already carried it) and places one it does not
// (the host rebuilt the request without it).
export const pairOf = (messages) => {
  const list = Array.isArray(messages) ? messages : [];
  for (const message of list) {
    if (message?.role !== "assistant" || !Array.isArray(message.content)) continue;
    const call = message.content.find((part) => part?.type === "tool-call" && PAIR_CALL.test(callIdOf(part)));
    if (call !== undefined) return [message];
  }
  return null;
};

// A failure of the request projection. §6.1 draws the line: a resource pair
// with a missing book, a language that cannot be bound and a prefix that would
// drift are failures, not reasons to send a request without the hint. The host
// gives `session.hook("context")` no failure channel, so a request the adapter
// cannot build is stopped by throwing: the host's `hooks.trigger` runs the
// callback inside the session drain, the throw becomes a defect of the drain,
// and the host records a failed execution and logs it
// (`packages/core/src/plugin/hooks.ts` `trigger`, `packages/core/src/session/
// execution.ts` "Failed to drain Session"). Nothing reaches the provider, and
// nothing is silently altered: that is the only honest outcome for a broken
// projection.
export class ContextRefusal extends Error {
  constructor(code, message, details = {}) {
    super(`${code}: ${message}`);
    this.name = "ContextRefusal";
    this.code = code;
    this.details = details;
  }
}

const refuse = (code, message, details) => new ContextRefusal(code, message, details);

// The relay gate of one request (根 §2.4 第 3 步).
//
// A predecessor whose tenure was retired is refused: trimming its request to the
// operator's last words would let it keep working on an empty memory, which is a
// continuation nobody asked for. An unreadable record refuses everybody, because
// it cannot say who was retired.
//
// A successor is cut at the barrier instead: its copy is the operator's own
// input (from `fenceThrough` on) plus the succession notice, and nothing of the
// conversation that triggered the relay. A record that names a session as
// neither the incumbent nor the successor leaves the copy alone: it is not about
// this session.
export const applyRelay = (messages, record, sessionID) => {
  if (record === null || record === undefined) return messages;
  if (record.corrupt === true) {
    throw refuse("RELAY_CORRUPT", "the relay record is unusable; the request is not sent", {
      reason: record.reason ?? null,
      detail: record.detail ?? null,
    });
  }
  if (record.incumbent === sessionID) {
    throw refuse("RELAY_RETIRED", "this tenure was retired by a relay; the request is not sent", {
      successor: record.successor,
      tenure: record.tenure,
    });
  }
  // A Plan hand-off names no incumbent: no tenure was retired, so nothing is
  // refused here. Its record exists only to carry the operator's original words
  // to the Manager it started (根 §2.4 第 4 步).
  if (record.kind === "plan-handoff") {
    return record.successor === sessionID ? applyBarrier(messages, record) : messages;
  }
  if (record.successor !== sessionID) return messages;
  return applyBarrier(messages, record);
};

// The host's own copy, kept for the turn close and for a projection that has
// nothing to change.
const original = (event) => (Array.isArray(event.messages) ? [...event.messages] : []);

// What this request is FOR (spec §6.1: the chain is the conversation).
//
// The host fires this hook for requests that borrow the conversation and are
// then discarded — `session.generate` (warming and one-shot generation, built
// from the session history) — and for the session's own model requests. Only
// the latter are steps of the chain; the former are branches that must not
// overwrite what the main conversation presented.
//
// The host marks the difference on the event it passes the hook. Absence of the
// marker is a MAIN request: the chain is the default, and a host that grows a
// new request kind without marking it keeps the stricter behaviour rather than
// silently escaping the rule.
export const requestKind = (event) => {
  const kind = event?.kind;
  return kind === "ephemeral" || kind === "branch" ? kind : "main";
};

export const applyContextHook = async (event, { state, hint }) => {
  const role = roleOf(event.agent);
  if (role === null) return { event, removed: [], fact: null, notice: null, hint: null };

  // §2.3: the Manager's evaluation round opens at the REQUEST boundary — this
  // hook — and nowhere else. Opening it from a tool call let a `js-review` call
  // reopen the round its own accepted review had just shut, so the same turn
  // could review twice. `beginRound` is idempotent under an open round: the
  // host may prepare the same request more than once, and only a request that
  // follows an accepted review advances the round.
  if (
    role === "manager" &&
    requestKind(event) === "main" &&
    typeof state.windowFor === "function"
  ) {
    state.windowFor("manager").beginRound();
  }

  // The turn that just ended is closed first, at the request boundary: every
  // tool call of that turn settled before this hook runs, so a turn whose only
  // annotation was `meToo` is refused here (§6.2). The refusal is a value
  // beside the copy, never a reason to change what the model reads.
  const closed = await closeTurn(state.scopeOf(), event.sessionID);

  const host = original(event);
  // Only the turn close and the Scope notice reach the caller: the request
  // itself either builds or throws.
  const notice = closed?.ok === false ? closed : null;
  let fact = null;
  let hintMode = null;

  // 1-3. Scope projection, in the host's own vocabulary. A copy the adapter
  // cannot translate is a failure, not a reason to send the request unprojected:
  // §6.2 makes the projection the request, and a refusal from core (a call
  // and its result disagreeing on retention) is a fact the model must not work
  // around.
  const hooks = state.scopeOf();
  let items;
  try {
    items = itemsFromMessages(host);
  } catch (error) {
    throw refuse("MESSAGE_COPY_FAILED", "the request copy could not be read", {
      detail: String(error?.message ?? error),
    });
  }
  const projected = projectRequest(hooks, { sessionId: event.sessionID, items, pinned: PINNED });
  if (projected.ok === false) {
    throw refuse(
      projected.code ?? "SCOPE_PROJECTION_FAILED",
      projected.message ?? "the Scope projection refused the request",
      { refusal: projected },
    );
  }
  // The barrier runs on the projected copy, in the host's own vocabulary, and
  // before the Pair Hint: the barrier decides which messages exist, the hint
  // decides what stands at the end of them.
  const projectedMessages = messagesFromItems(projected.messages);
  const relayRecord = state.relay?.record() ?? null;
  const relayed = applyRelay(projectedMessages, relayRecord, event.sessionID);
  event.messages = relayed;
  // A Scope projection that removed or rewrote messages is a new chain (§6.2):
  // `resetScope` prunes old beats and `removeLast` replaces the last one in
  // place, and neither can extend what was presented — handing that copy
  // straight to `project` refuses with `PREFIX_DRIFT` on the very next turn.
  //
  // The test is the observed copy, never "a resetScope record exists": a
  // settled reset whose boundary is outside this copy prunes nothing, and
  // anchoring there would throw away the comparison that catches a real
  // rewrite. It is asked of the copy BEFORE the barrier, because the barrier's
  // own cut is decided below and a barrier that kept the copy is not a break.
  const boundary = projectionBoundary({
    presented: hint.chain,
    projected: toHintMessages(projectedMessages),
    incoming: host,
    givenItems: items,
    answeredItems: projected.messages ?? [],
    projectedBy: projectedMessages,
    filtered: projectedMessages,
    pairFree: (copy) => hint.injector.withoutInjection(copy),
    erased: state.scopeOf().erasedCount(event.sessionID),
  });
  // The successor's copy no longer starts where the session's prefix chain
  // started: the barrier replaced the retired conversation with the operator's
  // input and the notice. The hint keeps its frozen bytes (same epoch, same
  // text) and takes the successor's first request as the new anchor, so the
  // next request of this tenure extends it instead of drifting from a
  // conversation the successor never sees.
  //
  // Once per tenure. The barrier cuts every request of the successor, but only
  // the first one opens the chain: re-anchoring a later request would replace
  // the copy the pair was already placed in, and the pair (which the host never
  // stores) would vanish from the anchor the next request is compared against.
  //
  // The anchor takes the SAME shape the projection will compare against:
  // `toHintMessages`, not the raw host copy. `PairHint#project` checks the next
  // request against `#presented` in the hint's vocabulary, so anchoring the raw
  // copy compares a host part against the hint part the same message becomes —
  // a bare `{type:"tool-call", id, name, input}` against
  // `{...toolCallId, toolName}`, or a `tool-result` without `output` against one
  // with it. That is a drift on a message nobody rewrote. The OMP adapter
  // measured exactly this failure (`host-omp/AGENTS.md`, 2026-11-01: drift at
  // index 0 with the conversation byte-identical).
  if (opensPrefixChain(relayRecord, event.sessionID) && state.anchorPrefix(relayRecord)) {
    // The constant, not the literal: `anchor` refuses a reason outside
    // `REBASE_REASONS`, so a renamed constant must fail here at build time
    // rather than at runtime on a live request.
    hint.anchor(toHintMessages(event.messages), { reason: REBASE_REASONS.RELAY });
  } else if (shouldAnchor(boundary)) {
    // The Scope projection replaced the copy: re-anchor on what is about to be
    // sent, in the shape the projection will compare against.
    hint.anchor(toHintMessages(event.messages), { reason: boundary.reason });
  }

  // 4. The Pair Hint, in its own shape, then back to the host's.
  //
  // A pair the adapter itself placed in an earlier request is removed first and
  // kept aside: the hint places or replays it at its recorded position, and the
  // copy the host rebuilt does not contain it (the host never stored it).
  // Removing it before the hint runs also keeps the hint's own prefix check
  // honest: the remaining messages are the host's, in the host's key order.
  const carried = extractPair(event.messages);
  // The presentation is frozen for its whole epoch (§6.1 step 4), so a handle
  // list that differs from the frozen one is only visible after a new epoch.
  // `setHandles` alone would leave the idle hint listing the handles of the
  // moment the epoch froze, and a park that appeared later would never be
  // listed at all. The epoch moves exactly when the list differs, so an
  // unchanged list costs no prefix.
  const handles = state.handles;
  if (hint.text !== null && [...hint.handles].sort().join("\n") !== [...handles].sort().join("\n")) {
    await hint.newEpoch();
  }
  hint.setHandles(handles);
  let shown;
  try {
    shown = await injectHint(hint, { messages: toHintMessages(carried.rest), kind: requestKind(event) });
  } catch (error) {
    // A missing resource book, a language the session never bound, a prefix that
    // would change what the model already read: each one is a failure of §6.1,
    // and none of them may become a request without the hint (§6.1).
    //
    // The hint's own `details` are carried through. They hold what the refusal
    // compared (`index`, and the two sides) — the journal records them, and the
    // host log is the other place an operator looks. Re-wrapping the error with
    // only its message left the live drift of 2026-11-01 readable nowhere: the
    // log said "the Pair Hint could not be placed" and nothing else.
    //
    // The sides are reduced to shapes before they leave the hook. The host logs
    // the thrown value as the drain's cause (`session/execution.ts`), so a
    // message list here would put the conversation, its tool I/O and any
    // credential in the request into the log. Core's `refusalRecord` shapes
    // each side the way its journal does: a request side keeps role, part
    // types, lengths and one digest per message; a chain side keeps only the
    // digests, because the full copy is gone by design (root §1.1 item 1).
    // Either way there is enough to find the differing index and nothing to
    // read.
    const hintDetails = error?.details !== null && typeof error?.details === "object" ? error.details : {};
    const compared = hintDetails.compared ?? null;
    throw refuse(
      error?.code ?? "PAIR_HINT_FAILED",
      "the Pair Hint could not be placed",
      {
        detail: String(error?.message ?? error),
        hint: hint?.sessionId ?? null,
        epoch: hint?.epoch ?? null,
        index: Number.isInteger(hintDetails.index) ? hintDetails.index : null,
        compared: shapedComparison(compared),
      },
    );
  }
  if (shown.ok === false) {
    throw refuse(shown.code ?? "PAIR_HINT_REFUSED", "the Pair Hint refused the request", {
      refusal: shown,
    });
  }
  const rebuilt = toHostMessages(shown.messages);
  // The copy carries the pair already when the hint replayed one; adding the
  // carried pair again would be a second pair in one request. Otherwise the
  // pair goes back where it stood.
  event.messages = pairOf(shown.messages) !== null ? rebuilt : withPair(rebuilt, carried);
  // The pair must be in the request this hook returns: a hint that reported
  // success while producing no pair would be a request without §6.1.
  if (pairOf(toHintMessages(event.messages)) === null) {
    throw refuse("PAIR_HINT_MISSING", "the request copy carries no Pair Hint pair", {
      mode: shown.mode,
      hint: hint.sessionId ?? null,
    });
  }
  fact = shown.fact ?? null;
  hintMode = shown.mode;

  // 5-6. The request's own system prompt and catalog.
  event.system = withDirector(event.system, event.agent);
  const removed = narrowTools(event.tools, role, {
    window: role === "manager" && typeof state.windowFor === "function" ? state.windowFor("manager") : null,
  });
  // The turn boundary is closed by the plugin after this hook; the copy is the
  // only thing this function decides.
  return { event, removed, fact, notice, hint: hintMode, closeTurn: closed };
};
