// Outbound injection (spec §6.1, §6.1, §6.1).
//
// The injector works on a copy of the request. It never touches the host's
// stored history and never edits the user's own words: it only places, replays
// or replaces the one synthetic pair.
//
// Where the pair goes is a persisted fact (§6.1 "位置由持久事实决定"), and the
// fact is a count, not an index: `after` says how many of the request's own
// messages came before the pair. An index would be wrong for the next request,
// because the host rebuilds its request from its own history and the pair is not
// in that history: every message after the pair shifts up by two. A count is
// stable across that rebuild, so the pair lands between the same two messages
// every time and each request starts with the bytes of the one before it.
//
// Rules, in the order they are checked:
//   1. No pair in the copy -> place it after `fact.after` messages; with no
//      `after` yet, at the end, and this placement decides it.
//   2. Same epoch, same bytes -> the request is a replay; the pair stays where
//      it is and nothing is appended.
//   3. Same epoch, different bytes -> PREFIX_DRIFT. Bytes already shown must
//      never change; a changed presentation needs a new epoch.
//   4. A different epoch -> the old pair leaves the copy and the new pair goes
//      at the new fact's place (the end when it has none).
//   5. A copy that carries the pair twice is the caller's bug: it cannot be
//      replayed without guessing, so it is refused, never silently repaired.
//
// A pair does not have to sit at its recorded `after` for its own epoch to be
// honest. §6.1 asks for a byte prefix, not a fixed index: a copy that already
// carries the pair — the host passing back the request it sent, a caller that
// re-projects a copy — may have the pair anywhere, as long as every message
// before it is unchanged and the pair itself still shows the frozen bytes. The
// pair's place then decides `after`, so the next copy that does not carry it
// lands between the same two messages. Refusing that shape would reject the
// one copy that is already a prefix of everything after it.

import { refuse, invalidArgument, PAIR_HINT_CODES } from "./errors.mjs";
import { findAllInjections, syntheticMessages, digestOf, callIdParts } from "./presentation.mjs";
import { canonicalKey } from "./canonical.mjs";

export const INJECTION_MODES = Object.freeze({
  PLACED: "placed",
  REPLAYED: "replayed",
});

// Remove every synthetic pair from a copy. A pair is the assistant message
// holding the synthetic call plus the tool message right after it.
const withoutPairs = (messages, injections) => {
  const drop = new Set();
  for (const injection of injections) {
    drop.add(injection.index);
    if (injection.result !== null) drop.add(injection.index + 1);
  }
  return messages.filter((_, index) => !drop.has(index));
};

// The text the request currently presents for the pair, or null.
const presentedText = (injection) => {
  if (injection.result === null) return null;
  const output = injection.result.output;
  if (output === null || typeof output !== "object") return null;
  return typeof output.value === "string" ? output.value : null;
};

// Where the pair goes in a copy that does not carry it.
//
// A placement beyond the copy is refused for a MAIN request: the copy no longer
// contains the messages the placement counted, so it is not an extension of what
// was shown. `clamp` is set only for an EPHEMERAL branch, whose copy the host
// builds per call and never stores (Cline's per-call compaction hands a shorter
// copy). The branch shares the chain's prefix only as far as it shares it, so
// the pair goes at its own end and the provider's cached prefix is that shared
// part. A branch never moves the chain, so it cannot corrupt the placement the
// next main request is measured against.
const cutFor = (messages, after, { clamp = false } = {}) => {
  if (after === null) return messages.length;
  if (after > messages.length) {
    if (clamp) return messages.length;
    throw refuse(PAIR_HINT_CODES.PREFIX_DRIFT, "the stored placement lies beyond the request", {
      after,
      length: messages.length,
    });
  }
  return after;
};

export class PairHintInjector {
  // `messages` is the request copy as the host built it. Returns a new array;
  // the input is never mutated.
  //
  // `after` is the placement, never `null` once it has been decided: `null`
  // means "this epoch has not placed the pair yet". A caller that passes
  // `null` for an epoch whose placement IS decided would move the pair to the
  // end of the copy, and the request would stop being an extension of the one
  // before it — that is `PREFIX_DRIFT` below, not a placement decision.
  inject({ messages, fact, text, branch = false }) {
    if (!Array.isArray(messages)) throw invalidArgument("messages must be an array");
    if (typeof text !== "string") throw invalidArgument("text must be a string");
    const incoming = digestOf(text);
    if (incoming !== fact.digest) {
      throw invalidArgument("text does not match the fact digest", { epoch: fact.epoch });
    }
    const injections = findAllInjections(messages);

    if (injections.length === 0) {
      const cut = cutFor(messages, fact.after, { clamp: branch });
      // The recorded placement counts messages the model has ALREADY read. A
      // copy shorter than that count is not the same conversation: it dropped
      // one of them (a compaction, a barrier without an anchor, a host that
      // trimmed its own history). Placing the pair at the end of such a copy
      // would move it, and would report success for a request whose earlier
      // bytes are gone — the drift the prefix rule exists to refuse.
      //
      // An empty copy is the extreme case: it has no messages at all, so the
      // pair lands at index 0 although the placement said the model read one
      // or more messages before it. Live (2026-11-01, `01a19501`): the host
      // rebuilt a one-message copy while the epoch's placement counted three,
      // the pair moved to the head of the request, and the next copy was
      // refused at index 0. A host never sends an empty request, so this
      // branch can only be a shortened copy, never a legitimate first one.
      if (!branch && fact.after !== null && messages.length < fact.after) {
        throw refuse(PAIR_HINT_CODES.PREFIX_DRIFT, "the request dropped messages the pair was placed after", {
          epoch: fact.epoch,
          after: fact.after,
          length: messages.length,
          compared: { previous: null, next: messages },
        });
      }
      // The pair's bytes must be the ones already shown: the fact that decides
      // the placement is the same fact, only the placement is now decided.
      // Emitting the undecided form here would rewrite a message the host
      // already saw, which is exactly what the prefix rule forbids.
      const placedFact = fact.at({ after: cut, inserted: cut });
      const placedPair = syntheticMessages({ fact: placedFact, text });
      return {
        messages: [...messages.slice(0, cut), ...placedPair, ...messages.slice(cut)],
        mode: INJECTION_MODES.PLACED,
        fact: placedFact,
        text,
      };
    }
    if (injections.length > 1) {
      throw refuse(PAIR_HINT_CODES.PREFIX_DRIFT, "the request carries the pair more than once", {
        epoch: fact.epoch,
        positions: injections.map((injection) => injection.index),
        // The copy as it was handed over, so a refusal record can say where the
        // pair stood without guessing at the caller's shape.
        compared: { previous: null, next: messages },
      });
    }

    const injection = injections[0];
    // The recorded `after` is the placement of a copy that does NOT carry the
    // pair. When the copy carries it, its own place is the fact: every message
    // before the pair is unchanged, and the pair goes where it already stands.
    // That is what keeps the request a prefix of the next one instead of
    // demanding the pair at an index this copy does not have.
    const shownAfter = injection.index;
    const shown = presentedText(injection);
    if (shown === null) {
      // A pair without readable text cannot be trusted as a prefix. Replacing
      // it would rewrite shown bytes, so it is a drift.
      throw refuse(PAIR_HINT_CODES.PREFIX_DRIFT, "the injected pair has no readable result", {
        epoch: fact.epoch,
        index: injection.index,
        compared: { previous: null, next: messages },
      });
    }

    const parts = callIdParts(injection.call.toolCallId);
    const shownEpoch = parts === null ? null : parts.epoch;

    if (shownEpoch === fact.epoch) {
      if (
        digestOf(shown) === incoming &&
        injection.call.toolCallId === syntheticMessages({ fact, text })[0].content[0].toolCallId
      ) {
        // Same epoch, same bytes: a replay of an already injected request. The
        // text that is already shown wins, so the caller never sees a hint that
        // differs from the bytes in the request.
        //
        // The replay also tells us how many messages the pair came after. That
        // number, not the absolute index, is what the next request uses: the
        // pair's own index counts the host's later messages too, and those are
        // not in the copy that follows. A copy that carries the pair may have
        // it after any number of messages; that place is now the placement.
        return {
          messages: [...messages],
          mode: INJECTION_MODES.REPLAYED,
          fact: fact.at({ after: shownAfter, inserted: injection.index }),
          text: shown,
        };
      }
      throw refuse(
        PAIR_HINT_CODES.PREFIX_DRIFT,
        "the presentation would change inside one epoch; open a new epoch instead",
        {
          epoch: fact.epoch,
          index: injection.index,
          // The pair that is already shown is the side that must not move; the
          // copy carrying it is what the caller sent. Both go into the record.
          compared: { previous: null, next: messages },
        },
      );
    }

    // A new epoch. The old pair leaves the copy; the new one goes at the new
    // fact's place, or at the end when the new epoch has none yet.
    const rest = withoutPairs(messages, injections);
    const cut = cutFor(rest, fact.after);
    const placedFact = fact.at({ after: cut, inserted: cut });
    const placedPair = syntheticMessages({ fact: placedFact, text });
    return {
      messages: [...rest.slice(0, cut), ...placedPair, ...rest.slice(cut)],
      mode: INJECTION_MODES.PLACED,
      fact: placedFact,
      text,
    };
  }

  // Check that `next` (already injected) starts with `previous` (already
  // injected). Returns true or throws PREFIX_DRIFT with the first differing
  // message index.
  //
  // The comparison is over `canonicalMessage` (canonical.mjs), never over the
  // host's own objects. The prefix rule is about what the model READ — the role,
  // the text, the tool identity and its arguments — not about the bookkeeping a
  // host stamps beside it. Comparing whole host objects is what made this rule
  // refuse requests nobody changed, on every host, every time the host added or
  // rewrote a runtime field. A change to the semantic content still refuses; a
  // change to anything else is not a change to the conversation.
  assertPrefix({ previous, next }) {
    if (!Array.isArray(previous) || !Array.isArray(next)) {
      throw invalidArgument("both message lists must be arrays");
    }
    if (next.length < previous.length) {
      throw refuse(PAIR_HINT_CODES.PREFIX_DRIFT, "the new request is shorter than the old one", {
        index: next.length,
        previousLength: previous.length,
        nextLength: next.length,
        compared: { previous, next },
      });
    }
    for (let index = 0; index < previous.length; index += 1) {
      if (canonicalKey(previous[index]) !== canonicalKey(next[index])) {
        throw refuse(PAIR_HINT_CODES.PREFIX_DRIFT, "a message already shown would change", {
          index,
          compared: { previous, next },
        });
      }
    }
    return true;
  }

  // Whether `next` starts with `previous`, message for message. The predicate
  // form of `assertPrefix`, for a caller that wants to decide instead of
  // refusing. Same canonical comparison, so the two can never disagree.
  extendsPrefix({ previous, next }) {
    if (!Array.isArray(previous) || !Array.isArray(next)) {
      throw invalidArgument("both message lists must be arrays");
    }
    if (next.length < previous.length) return false;
    for (let index = 0; index < previous.length; index += 1) {
      if (canonicalKey(previous[index]) !== canonicalKey(next[index])) return false;
    }
    return true;
  }

  // Whether two messages are the same message to the chain. The single-message
  // form of the canonical comparison, for a caller that needs the first
  // divergence rather than a verdict.
  sameMessage(previous, next) {
    return canonicalKey(previous) === canonicalKey(next);
  }

  // The semantic identity of a message list, for a caller that wants to compare
  // two copies without refusing. `equalPrefix` is the value form of the rule.
  samePrefix({ previous, next }) {
    if (!Array.isArray(previous) || !Array.isArray(next)) {
      throw invalidArgument("both message lists must be arrays");
    }
    if (next.length !== previous.length) return false;
    for (let index = 0; index < previous.length; index += 1) {
      if (canonicalKey(previous[index]) !== canonicalKey(next[index])) return false;
    }
    return true;
  }

  // How many messages of `messages` come before its synthetic pair, or null
  // when the copy carries none. This is the placement the copy itself shows.
  shownPlacement(messages) {
    if (!Array.isArray(messages)) throw invalidArgument("messages must be an array");
    const injection = findAllInjections(messages)[0];
    return injection === undefined ? null : injection.index;
  }

  // The copy without this epoch's pair, or the copy itself when it carries
  // none. This is the shape §6.1 compares: the pair is presentation, and two
  // requests stand in one prefix chain when the messages AROUND the pair are
  // unchanged. `assertPrefix` on the pair-carrying forms would compare the pair
  // against whatever message the host rebuilt at its old index.
  withoutInjection(messages) {
    if (!Array.isArray(messages)) throw invalidArgument("messages must be an array");
    const injections = findAllInjections(messages);
    if (injections.length === 0) return [...messages];
    return withoutPairs(messages, injections);
  }
}
