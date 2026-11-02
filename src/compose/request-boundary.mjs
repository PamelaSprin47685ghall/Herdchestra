// When a projection legitimately replaced the request copy (§6.1, §6.2).
//
// §6.1 asks one thing of every request: the next one begins with the messages
// the model already read. §6.2 asks something that contradicts it on its face —
// the Scope projection DELETES old beats and REPLACES the last one in place. A
// copy that lost messages cannot begin with a copy that had them, so the strict
// prefix rule refuses the next request with `PREFIX_DRIFT` and the session
// wedges. That was live on all three hosts: a `resetScope` pruned the copy and
// the very next turn was refused, and a `removeLast` rewrote a beat with no
// re-anchoring anywhere.
//
// The two rules do not actually conflict, because they are about different
// things. §6.1 is a promise about BYTES THE MODEL HAS READ: it may not change
// under the model. §6.2 is a decision the SPEC owns about which of those
// messages the next request carries. So there are exactly two legal outcomes:
//
//   kept      the projection returned a copy that begins with what was
//             presented. Nothing to do: the chain continues by itself.
//   replaced  the projection produced a copy that is NOT an extension — it
//             removed messages, or rewrote one in place. That is a new chain,
//             and the caller must re-anchor on the copy it is about to send.
//             Everything AFTER the anchor is strict again, so a later rewrite
//             with no projection behind it is still refused.
//
// This module answers which of the two happened, from the copies alone. It
// deliberately does NOT ask "is there a resetScope record?" or "did a
// removeLast settle?": a record proves the model ASKED, not that this request's
// copy changed. A settled reset whose boundary is outside this copy prunes
// nothing, and re-anchoring there would throw away the one comparison that
// catches a real rewrite. Only the bytes decide.
//
// Comparison is by the same rule the chain uses — `canonicalKey`, the semantic
// identity of a message (core's `pair-hint/canonical.mjs`) — so "the projection
// changed the copy" and "the chain would refuse" can never disagree. Comparing
// raw objects here was a second, stricter rule: the host's own metadata or key
// order made an untouched message look changed, the boundary said "replaced",
// and a re-base was performed for a copy nobody had touched.

import { Chain, messageDigest } from "../core/pair-hint/chain.mjs";

// The first index at which `next` stops extending `previous`, or -1 when it
// extends it. A longer `next` whose leading messages all match is an extension;
// a shorter one, or one whose message at some index differs, is not.
//
// A side may be a `Chain` (the persisted chain state) or a message list. Both
// become digest lists here, so a cold start compares exactly what the live
// process compared: the chain's own per-message digests, never a stored copy.
const digestsOf = (side) => {
  if (side instanceof Chain) return side.digests;
  if (Array.isArray(side)) return side.map(messageDigest);
  throw new TypeError("a request-boundary side must be a Chain or a message list");
};

const divergenceAt = (previous, next) => {
  const before = digestsOf(previous);
  const after = digestsOf(next);
  const span = Math.min(before.length, after.length);
  for (let index = 0; index < span; index += 1) {
    if (before[index] !== after[index]) return index;
  }
  return after.length < before.length ? after.length : -1;
};

// The first index at which two ITEM lists differ structurally, or -1 when they
// match up to the shorter one's length.
//
// Items are Scope's own vocabulary (`kind`, `role`, `text`, `callId`, …), not
// chat messages, so they are compared by value: the canonical form used for
// messages ignores keys it does not recognise, which would make two different
// items compare equal.
const firstItemDifference = (previous, next) => {
  const span = Math.min(previous.length, next.length);
  for (let index = 0; index < span; index += 1) {
    if (JSON.stringify(previous[index]) !== JSON.stringify(next[index])) return index;
  }
  return next.length < previous.length ? next.length : -1;
};


// The identity of the MESSAGE an item describes: the host message object it
// kept (`source`), or the projection-written item's `erasedCallId`. Two items
// of one assistant message share one source and are one message; two distinct
// messages are two, even when their bytes are identical.
const messageIdentity = (item) => {
  if (item === null || typeof item !== "object") return item;
  if (item.erasedCallId !== undefined) return `erased:${String(item.erasedCallId)}`;
  if (item.source !== undefined && item.source !== null) return item.source;
  return item;
};

// The messages an item list describes, in order and without repeats. One
// assistant message may contribute several items (its prose and its calls); the
// model reads it once. Dedupe is by IDENTITY, not by value: two genuinely
// distinct host messages that happen to be byte-identical must stay two, or a
// reset boundary that pruned one of them would look like no change at all.
const distinctMessages = (items) => {
  const seen = new Set();
  const out = [];
  for (const item of items) {
    const key = messageIdentity(item);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(itemMessage(item));
  }
  return out;
};

// Whether `next` is `previous` with some items removed, order kept. That is
// exactly what a `resetScope` prune does: it takes messages away and changes
// none of the ones it keeps.
const isSubsequence = (previous, next) => {
  let at = 0;
  for (const item of previous) {
    if (at >= next.length) break;
    if (JSON.stringify(item) === JSON.stringify(next[at])) at += 1;
  }
  return at === next.length;
};

// What the projection did to the copy, and whether the chain must restart.
//
//   presented   the copy the last request sent (with this epoch's pair), or
//               null when nothing has been presented in this epoch yet
//   projected   the copy the projection produced, before the pair is placed
//   incoming    the copy the projection was GIVEN (the host's own request)
//   givenItems  the item list the projection was HANDED (its input)
//   answeredItems the item list it answered
//   filtered    what the adapter's filter kept of that answer. When both are
//               given, the filter must have kept EVERYTHING the projection
//               answered: an adapter that drops a message on its own (half a
//               pair, a result whose call went away) is not the spec deciding
//               anything, and must not be able to authorise a chain restart.
//   pairFree    the projection that removes this epoch's synthetic pair from a
//               copy (core's `PairHintInjector#withoutInjection`)
//   erased      how many `removeLast` beats the Scope book has recorded. It
//               only LABELS a rewrite, never decides one: a rewrite is already
//               proven by the bytes, and this says whether the spec's own
//               `removeLast` is the fact behind it (a beat was replaced in
//               place) or a `resetScope` boundary pruned messages away.
//
// `incoming` is what makes this decidable. Without it, "the copy changed" has
// two indistinguishable causes: the projection decided it (a `resetScope`
// pruning, a `removeLast` rewrite — the spec's own retention), or something
// else rewrote the host's request under us (a bug, a different plugin, a
// compaction we did not ask for). Anchoring on the second case would bless
// exactly the mutation §6.1 exists to refuse. So a replacement counts only when
// the projection is the one that made it: the copy it returned must differ from
// the copy it received. A projection that changed nothing cannot authorise a
// chain restart, whatever else changed.
//
// `presented` carries the pair and `projected` does not, so the comparison is
// made pair-free on both sides: the pair is presentation, and its own placement
// fact changes with every request.
export const projectionBoundary = ({
  presented,
  projected,
  incoming = null,
  givenItems = null,
  answeredItems = null,
  filtered = null,
  pairFree,
  erased = 0,
  // The host's OWN durable rewrite of history, when the adapter can prove one:
  // Cline's compaction replaces the transcript with a summary plus its tail and
  // SAVES it (`session/models/session-compaction.ts`), so the next turn is built
  // from a history the model already read part of. That is a rewrite the spec
  // owns — like a `resetScope` prune — but Scope is not its author, so the
  // item-level test above cannot see it.
  //
  // The caller must PROVE it, not assert it: pass the reason only when the host
  // reported the rewrite (a compaction result it produced), never because the
  // copy merely looks different. An adapter that cannot tell the two apart must
  // pass `null`, and the chain then refuses — the safe direction.
  durableRewrite = null,
}) => {
  if (presented === null || presented === undefined) {
    // Nothing was shown in this epoch, so no chain exists to break. The next
    // request opens it, which is `project`'s job, not an anchor's.
    return Object.freeze({ replaced: false, shared: 0, reason: "first" });
  }
  if (typeof pairFree !== "function") {
    throw new TypeError("projectionBoundary needs the projection that removes the pair");
  }
  // A durable rewrite the host performed: the copy that goes out is a new
  // chain, whatever Scope answered. Checked before the item-level tests because
  // those compare Scope's input with Scope's output, and Scope changed nothing.
  if (typeof durableRewrite === "string" && durableRewrite.length > 0) {
    const before = pairFree(presented);
    const after = pairFree(projected);
    // The rewrite must actually have changed the copy. A host that reports a
    // compaction but hands back the same messages did not rewrite anything, and
    // re-basing there would throw away the comparison that catches a real
    // rewrite.
    if (divergenceAt(before, after) >= 0 || after.length !== before.length) {
      return Object.freeze({ replaced: true, shared: 0, reason: durableRewrite });
    }
    // The report is authoritative about the copy, but the filter guard below
    // must not be able to veto it: after a compaction the adapter's own
    // half-pair rule can drop an orphan result (the call it answered was
    // summarised away), and that drop is a CONSEQUENCE of the rewrite the host
    // reported, not a second author. Falling through to `filtered` would refuse
    // a turn the host legitimately produced.
    return Object.freeze({ replaced: false, shared: 0, reason: "unchanged" });
  }
  // The adapter's filter must not be the author of a replacement.
  //
  // A projection answers a list; the adapter then applies it (keeping the
  // original objects, dropping half a pair). If that filter removed a message
  // the projection KEPT, the copy that goes out is the adapter's doing, not the
  // spec's — and an adapter that drops a message on its own must not be able to
  // restart the chain. That is the case this catches: a host message rewritten
  // under us stops matching its pair, the filter drops the result, and the
  // shortened copy would otherwise look exactly like a Scope prune.
  if (Array.isArray(answeredItems) && Array.isArray(filtered)) {
    // Only the messages the projection ANSWERED WITH count. An item the
    // projection WROTE (`erasedCallId`, a `removeLast` lesson) has no source by
    // construction: there is no host message it stands for, so the adapter
    // cannot have dropped it and its absence from `filtered` says nothing. It
    // reaches the copy as a NEW message the adapter builds, which is why
    // `selectKept` and `messagesFromItems` splice it in rather than keep it.
    //
    // Counting it was a real defect (fast-check seed -77436715): a `removeLast`
    // answered one written item beside the host's messages, this guard read the
    // written item as a dropped message, returned `filtered`, and the
    // projection's own in-place rewrite never re-based. The next request
    // differed at the erased beat and was refused — on a sequence the spec
    // itself decided.
    const answeredMessages = answeredItems
      .filter((item) => item?.erasedCallId === undefined)
      .map((item) => item?.source ?? item?.message)
      .filter((message) => message !== undefined);
    const keptMessages = new Set(filtered);
    if (answeredMessages.some((message) => !keptMessages.has(message))) {
      return Object.freeze({ replaced: false, shared: 0, reason: "filtered" });
    }
  }
  // THE decisive test, when the caller can supply it: did the PROJECTION remove
  // or rewrite something? It is asked of the projection's own input and output,
  // which are item lists in one vocabulary, so nothing the adapter does before
  // or after can be mistaken for it.
  //
  // This is what keeps a re-base honest. A host message rewritten under us
  // changes what the adapter's reader produces, which changes the copy — but
  // Scope did not decide that, so the chain must refuse, not restart. Only an
  // answer SHORTER than its input, or one that differs where the input had an
  // item, is the projection deciding.
  //
  if (Array.isArray(givenItems) && Array.isArray(answeredItems)) {
    // The comparison is over what the projection did to the MESSAGES, because
    // that is what the model reads. A `removeLast` rewrites one beat: it answers
    // as many items as it was given, but the message carrying that beat is
    // GONE and a projection-written one stands in its place. Counting items
    // alone would call that "unchanged" — the counts match — while the copy the
    // model reads lost a message and gained another.
    //
    // `itemMessage` names the message an item came from, or a fresh marker for
    // an item the projection wrote itself. Two item lists describe the same copy
    // exactly when their message sequences are equal.
    // Distinct MESSAGES, not items: one assistant message emits both a
    // `MESSAGE` item and a `TOOL_CALL` item, so an item list is longer than the
    // message list it describes and comparing them index by index would see a
    // difference that is not in the conversation.
    const before = distinctMessages(givenItems);
    const after = distinctMessages(answeredItems);
    // Structural equality, not `canonicalKey`: these are SCOPE items, not chat
    // messages, and the canonical form deliberately ignores anything it does not
    // recognise as a message part — which would make every item look alike.
    const at = firstItemDifference(before, after);
    const dropped = after.length < before.length;
    // THE decisive question: is Scope the author of the difference? If its
    // answer equals its input, Scope changed nothing — so whatever differs
    // between the copy the model read and the copy going out came from the
    // adapter's reader (a message it re-spelled or reclassified). That is a
    // rewrite nobody authorised: the chain must REFUSE, not restart. Returning
    // `replaced: false` here makes the caller fall through to the chain's own
    // comparison, which is where a refusal comes from.
    if (at < 0 && !dropped) {
      // Scope changed nothing, so no re-base is authorised. That is NOT the same
      // as "the copy is intact": the adapter's reader can reclassify a message
      // without Scope seeing anything. `replaced: false` sends the caller to its
      // own chain comparison, which is where such a rewrite is refused.
      return Object.freeze({ replaced: false, shared: 0, reason: "unchanged" });
    }
    {
    // A cut and a replacement are different facts, and the difference is whether
    // every item the projection kept is one it was GIVEN, in order. A
    // `resetScope` prune keeps a subsequence — it removes, it does not rewrite.
    // A `removeLast` erases a beat and puts a projection-written item in its
    // place, so its answer holds something the input never had.
    const rewritten = !isSubsequence(before, after);
    return Object.freeze({ replaced: true, shared: 0, reason: rebaseReason({ erased, rewritten }) });
    }
  }
  // Without the projection's own lists the caller cannot prove authorship, so
  // the weaker question is all that is left: does the copy that goes out still
  // extend what was presented? A difference here is reported as a replacement
  // because the caller has told us a projection ran; a caller that cannot
  // supply `givenItems` is accepting that weaker evidence.
  const before = pairFree(presented);
  const after = pairFree(projected);
  // Even the weaker branch must prove that the PROJECTION made the change: the
  // copy it answered has to differ from the copy it was given. Without
  // `incoming` (or with an unchanged one) a difference between `presented` and
  // `projected` could be the adapter's own rewrite, and re-basing there would
  // bless exactly the mutation §6.1 refuses.
  if (incoming === null || incoming === undefined) {
    return Object.freeze({ replaced: false, shared: 0, reason: "unchanged" });
  }
  if (divergenceAt(pairFree(incoming), after) < 0) {
    return Object.freeze({ replaced: false, shared: 0, reason: "unchanged" });
  }
  const at = divergenceAt(before, after);
  if (at < 0) return Object.freeze({ replaced: false, shared: before.length, reason: null });
  if (after.length < before.length) {
    return Object.freeze({ replaced: true, shared: at, reason: rebaseReason({ erased, rewritten: false }) });
  }
  return Object.freeze({ replaced: true, shared: at, reason: rebaseReason({ erased, rewritten: true }) });
};

// Which §6.2 fact is behind a replacement the projection made.
//
// A copy that kept its length but changed a message is a beat replaced in
// place, and only `removeLast` does that. A copy that lost messages was pruned
// by a `resetScope` boundary — unless a `removeLast` also collapsed a two-item
// beat into one, which shortens the copy too, so an erase on the book wins.
const rebaseReason = ({ erased, rewritten }) =>
  rewritten || erased > 0 ? "scope-remove" : "scope-reset";

// The message an item stands for, as a value the comparison can use.
//
// An item the projection KEPT carries its `source` — the host's own message — so
// two lists that kept the same messages compare equal. An item the projection
// WROTE (`erasedCallId`) has no source: it is new bytes by definition, and it is
// tagged so it can never compare equal to anything the host sent.
const itemMessage = (item) => {
  if (item === null || typeof item !== "object") return item;
  // An item the projection WROTE is new bytes by definition, and it is tagged so
  // it can never compare equal to anything the host sent.
  if (item.erasedCallId !== undefined) return Object.freeze({ projectionWrote: item.erasedCallId });
  // Every other item is compared by WHAT IT SAYS, not by which object it came
  // from: a projection may rewrite an item in place (`removeLast` turns a beat
  // into a lesson) while the item still points at the host message it replaced,
  // so identity would read that rewrite as no change at all.
  const { source, ...rest } = item;
  return rest;
};

// Whether a copy may be re-anchored. `replaced` is the only answer that allows
// it, and the caller passes the copy it is ABOUT TO SEND, never an earlier one:
// anchoring on anything else would bless a rewrite nobody made.
export const shouldAnchor = (boundary) => boundary !== null && boundary !== undefined && boundary.replaced === true;
