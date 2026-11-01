// The request copy: which persisted items reach the provider (spec §6.2,
// §6.2).
//
// The host keeps its history untouched. This module takes the items the host
// would send and returns a filtered copy:
//
// - everything after the beat that opened the current Scope is kept as it is;
// - before it, operator messages, that beat and its paired result, pinned
//   members (`assume`, `negotiate`, `askPreviousScope`) and their paired
//   results, assistant prose and observed provider output are kept;
// - ordinary tool calls and results of an old Scope are removed, together with
//   their thinking/reasoning;
// - an erased beat becomes one assistant message whose text is the
//   `replacement` plus a spin-count line.
//
// Two facts can name the boundary. `resetCallId` is the settled fact: the call
// whose `resetScope` actually replaced the budget. Without it the request copy
// falls back to the newest call carrying a `resetScope` annotation, which is
// the only evidence the items themselves offer. A refused `resetScope`, or one
// that only confirmed an already settled turn, must not open a new boundary.
//
// Pairing is positional, not by id: a call keeps its result when the item
// directly after it is its result. The input is never mutated. Nothing here
// throws for a host item it does not recognize: an unknown kind is kept when it
// is after the boundary and dropped when it is before it.

import { DEFAULT_PINNED_MEMBERS, REMOVE_MEMBER, SCOPE_CODES, SCOPE_KINDS, refuse } from "./errors.mjs";

export const ITEM = Object.freeze({
  MESSAGE: "message",
  TOOL_CALL: "toolCall",
  TOOL_RESULT: "toolResult",
  THINKING: "thinking",
  PROVIDER: "provider",
});

const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);

const kindOf = (item) => (isObject(item) && typeof item.kind === "string" ? item.kind : null);

const isMessage = (item) => kindOf(item) === ITEM.MESSAGE;

const isOperatorMessage = (item) => isMessage(item) && item.role === "operator";

// Assistant prose is kept; an assistant message that only carries a tool call
// is not prose. The host marks prose with `text`; empty text is not prose.
const isAssistantProse = (item) =>
  isMessage(item) && item.role === "assistant" && typeof item.text === "string" && item.text.length > 0;

// Observed provider output is kept wherever it sits: it is the model's own
// words, not a tool beat of an old Scope.
const isProvider = (item) => kindOf(item) === ITEM.PROVIDER;

// The annotation the call carried. The host may attach it as `scope` on the
// call and on its result; both count, because a request can hold either.
const isResetCarrier = (item) => isObject(item) && item.scope?.kind === SCOPE_KINDS.RESET;

// True for the call that carried a `removeLast` annotation.
const isEraseCarrier = (item) =>
  isObject(item) && (item.scope?.kind === SCOPE_KINDS.REMOVE || item.member === REMOVE_MEMBER);

const isPinned = (item, pinned) =>
  (kindOf(item) === ITEM.TOOL_CALL || kindOf(item) === ITEM.TOOL_RESULT) &&
  typeof item.member === "string" &&
  pinned.has(item.member);

// The call an annotation carrier stands for. A host may attach `scope` to the
// result instead of (or besides) the call; the boundary is still the call.
// An orphan result cannot open a Scope: there is no beat to keep.
const carrierCall = (items, index) => {
  const item = items[index];
  const kind = kindOf(item);
  if (kind === ITEM.TOOL_CALL) return index;
  if (kind !== ITEM.TOOL_RESULT) return -1;
  const previous = items[index - 1];
  if (previous === undefined || kindOf(previous) !== ITEM.TOOL_CALL) return -1;
  if (item.callId !== undefined && previous.callId !== undefined && item.callId !== previous.callId) {
    return -1;
  }
  return index - 1;
};

// Index of the call that opened the current Scope, or -1 when the whole request
// copy is inside it.
//
// An explicit `resetCallId` is the settled fact and is authoritative: when that
// call is not in this copy, the copy holds no boundary and everything in it is
// current. Falling back to an annotation there would let a refused reset open a
// Scope. Only a caller with no settled fact at all falls back to the newest
// `resetScope` annotation in the items.
export const newestResetIndex = (items, resetCallId = null) => {
  if (resetCallId !== null && resetCallId !== undefined) {
    for (let index = items.length - 1; index >= 0; index -= 1) {
      const item = items[index];
      if (kindOf(item) === ITEM.TOOL_CALL && item.callId === resetCallId) return index;
    }
    return -1;
  }
  for (let index = items.length - 1; index >= 0; index -= 1) {
    if (!isResetCarrier(items[index])) continue;
    const call = carrierCall(items, index);
    if (call >= 0) return call;
  }
  return -1;
};

// The result paired with the boundary call, or -1. A reset's paired result is
// part of the boundary even when the result carries no annotation of its own.
const resetResultIndex = (items, resetAt) => {
  if (resetAt < 0) return -1;
  const next = items[resetAt + 1];
  if (kindOf(next) !== ITEM.TOOL_RESULT) return -1;
  const call = items[resetAt];
  if (next.callId !== undefined && call.callId !== undefined && next.callId !== call.callId) return -1;
  return resetAt + 1;
};

// `spinText` renders the spin line of a `removeLast` replacement. The spec
// asks for the replacement plus one line of spin count; the wording is ours.
export const spinLine = (count) => `Spin count: ${count}`;

// The projection itself.
//
// `erase` is the list of erased beats (`{ callId, replacement, spin }`): the
// call named by `callId` carried the `removeLast` annotation, and the beat
// immediately before it is the one rewritten. `resetCallId` names the beat that
// opened the current Scope. See the module comment.
export const projectRequest = (
  items,
  {
    erase = [],
    pinned = DEFAULT_PINNED_MEMBERS,
    spinText = spinLine,
    resetCallId = null,
  } = {},
) => {
  if (!Array.isArray(items)) {
    return refuse(SCOPE_CODES.SCOPE_FIELD_INVALID, "request items must be an array");
  }
  const pinnedSet = new Set(pinned);
  const eraseByCall = new Map();
  for (const entry of erase) {
    if (entry.callId === null || entry.callId === undefined) continue;
    eraseByCall.set(entry.callId, entry);
  }
  const resetAt = newestResetIndex(items, resetCallId);
  const resetResultAt = resetResultIndex(items, resetAt);

  const keep = (item, index) => {
    if (isProvider(item)) return true;
    if (index > resetAt) return true;
    if (index === resetAt || index === resetResultAt) return true;
    const kind = kindOf(item);
    switch (kind) {
      case ITEM.MESSAGE:
        return isOperatorMessage(item) || isAssistantProse(item);
      case ITEM.TOOL_CALL:
      case ITEM.TOOL_RESULT:
        return isPinned(item, pinnedSet);
      case ITEM.THINKING:
        return false;
      default:
        return false;
    }
  };

  // The beat a `removeLast` rewrites: the call immediately before the erasing
  // call, together with its paired result when the result follows it.
  //
  // A host may keep the annotation on the call and its result: the eraser is
  // then a `removeLast` member or a call carrying that annotation. Either way
  // the eraser itself stays in the copy; only the beat before it is rewritten.
  // The eraser is the call named by the `erase` record. The record is the
  // fact (it is written only when a `removeLast` settled), and the projected
  // item does not carry the annotation: no adapter copies `scope` onto the
  // items it builds. Requiring the item to carry it too made this branch dead
  // on every live path — the `erase` list arrived, `beatBefore` re-checked the
  // item, found no annotation, and returned null, so the lesson was never
  // written. The `member`/`scope` check stays for a caller that passes no
  // records at all and expects the items alone to say what they are.
  const beatBefore = (at, namedByRecord) => {
    if (at <= 0) return null;
    const eraser = items[at];
    if (kindOf(eraser) !== ITEM.TOOL_CALL) return null;
    if (!namedByRecord && eraser.member !== REMOVE_MEMBER && eraser.scope?.kind !== SCOPE_KINDS.REMOVE) {
      return null;
    }
    const previous = items[at - 1];
    const previousKind = kindOf(previous);
    const floor = resetAt + 1;
    if (at - 1 < floor) return null;
    if (previousKind === ITEM.THINKING) return [at - 1];
    if (previousKind === ITEM.MESSAGE) {
      return isAssistantProse(previous) ? [at - 1] : null;
    }
    if (previousKind === ITEM.TOOL_RESULT) {
      const call = items[at - 2];
      // The boundary is the last beat of the old Scope; a beat that starts
      // before it may not be rewritten, and the boundary's own result may not
      // be detached from its call.
      if (kindOf(call) === ITEM.TOOL_CALL) {
        if (at - 2 < floor) return null;
        return [at - 2, at - 1];
      }
      return at - 1 >= floor ? [at - 1] : null;
    }
    if (previousKind === ITEM.TOOL_CALL) {
      return previous === items[resetAt] ? null : [at - 1];
    }
    return null;
  };

  // Precompute the rewritten beats: each is a contiguous index run, and the
  // assistant message takes the place of its first index.
  const beats = [];
  for (const entry of eraseByCall.values()) {
    const eraserAt = items.findIndex(
      (item) => kindOf(item) === ITEM.TOOL_CALL && item.callId === entry.callId,
    );
    if (eraserAt < 0) continue;
    const beat = beatBefore(eraserAt, true);
    if (beat === null) continue;
    beats.push({ indices: beat, entry });
  }
  const beatAt = new Map();
  for (const beat of beats) for (const index of beat.indices) beatAt.set(index, beat);

  const output = [];
  for (let index = 0; index < items.length; index += 1) {
    const beat = beatAt.get(index);
    if (beat !== undefined) {
      if (index === beat.indices[0]) {
        output.push(
          Object.freeze({
            kind: ITEM.MESSAGE,
            role: "assistant",
            text: `${beat.entry.replacement}\n${spinText(beat.entry.spin)}`,
            erasedCallId: beat.entry.callId,
          }),
        );
      }
      continue;
    }
    const item = items[index];
    const next = items[index + 1];
    const isCall = kindOf(item) === ITEM.TOOL_CALL;
    const nextIsResult = next !== undefined && kindOf(next) === ITEM.TOOL_RESULT;
    if (isCall && nextIsResult && keep(item, index) !== keep(next, index + 1)) {
      return refuse(
        SCOPE_CODES.SCOPE_FIELD_INVALID,
        `tool call ${String(item.callId)} and its result disagree on retention`,
        { callId: item.callId },
      );
    }
    if (!keep(item, index)) {
      if (isCall && nextIsResult) index += 1;
      continue;
    }
    output.push(item);
  }
  return output;
};
