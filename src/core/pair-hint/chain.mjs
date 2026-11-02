// The prefix chain's minimal state (spec §6.1 step 4, 根 §1.1 item 1).
//
// One `presented` record used to carry the whole request copy. A long session
// then wrote megabytes per turn: 33 requests of a 67-message session produced
// 34 MB, and the repository's journals reached 133 MB. The chain only ever asks
// one question — does the next request begin with the messages the model
// already read? — so what it needs is one digest per message and a root over
// them, not the bytes.
//
// `Chain` is that state: `length`, the per-message SHA-256 of each message's
// canonical identity, a root digest, and the index of this epoch's synthetic
// pair. Two messages with the same canonical key have the same digest, so the
// prefix rule and a refusal's `details.index` are decided by digest equality.
// The stored copy is never read back, and a cold start can verify a prefix
// without reconstructing any message.

import { createHash } from "node:crypto";
import { canonicalKey } from "./canonical.mjs";
import { invalidArgument } from "./errors.mjs";

const DIGEST = /^[0-9a-f]{64}$/;

export const isDigest = (value) => typeof value === "string" && DIGEST.test(value);

const sha256 = (text) => createHash("sha256").update(text, "utf8").digest("hex");

// One message's digest, over the SAME text the prefix rule compares.
export const messageDigest = (message) => sha256(canonicalKey(message));

// A root over the ordered per-message digests. The length is part of the
// preimage so an empty chain and a chain of one empty string cannot collide.
export const rootOf = (digests) => sha256(`${digests.length}\n${digests.join("\n")}`);

const isIndex = (value) => value === null || (Number.isInteger(value) && value >= 0);

// One immutable chain. `pairIndex` is the index of the synthetic call; the
// result is the message after it. A pair-free view has `pairIndex === null`.
export class Chain {
  #digests;
  #pairIndex;
  #root;

  constructor({ digests, pairIndex = null }) {
    if (!Array.isArray(digests)) throw invalidArgument("a chain needs a digest list");
    for (const digest of digests) {
      if (!isDigest(digest)) throw invalidArgument("every chain entry must be a SHA-256 digest", { digest });
    }
    if (!isIndex(pairIndex)) throw invalidArgument("pairIndex must be a non-negative integer or null");
    if (pairIndex !== null && pairIndex + 1 >= digests.length) {
      throw invalidArgument("pairIndex must name a call and its result", { pairIndex, length: digests.length });
    }
    this.#digests = Object.freeze([...digests]);
    this.#pairIndex = pairIndex;
    this.#root = rootOf(this.#digests);
  }

  static fromMessages(messages, { pairIndex = null } = {}) {
    if (!Array.isArray(messages)) throw invalidArgument("messages must be an array");
    return new Chain({ digests: messages.map(messageDigest), pairIndex });
  }

  // A persisted chain. `length`, `digests` and `digest` are required together:
  // a record with none of them is a superseded copy left by compaction and is
  // not a chain. The caller decides what a missing chain means.
  static fromRecord(record) {
    if (record === null || typeof record !== "object") {
      throw invalidArgument("a chain record must be an object");
    }
    if (!Number.isInteger(record.length) || record.length < 0) {
      throw invalidArgument("a chain record needs a non-negative length");
    }
    if (!Array.isArray(record.digests) || record.digests.length !== record.length) {
      throw invalidArgument("a chain record needs one digest per message", { length: record.length });
    }
    const chain = new Chain({ digests: record.digests, pairIndex: record.pair ?? null });
    if (typeof record.digest !== "string" || record.digest !== chain.root) {
      throw invalidArgument("a chain record's root does not match its digests");
    }
    return chain;
  }

  get length() {
    return this.#digests.length;
  }

  get digests() {
    return this.#digests;
  }

  get root() {
    return this.#root;
  }

  get pairIndex() {
    return this.#pairIndex;
  }

  // The digest of the message at `index`, or null when the chain is shorter.
  // A host that must compare one message with the chain (a compaction summary,
  // a tail) reads this instead of keeping the previous copy.
  digestAt(index) {
    if (!Number.isInteger(index) || index < 0) return null;
    return this.#digests[index] ?? null;
  }

  // The copy without this epoch's pair. That is the shape the prefix rule
  // compares: the pair is presentation, and its own placement changes with
  // every request.
  pairFreeDigests() {
    if (this.#pairIndex === null) return this.#digests;
    const drop = new Set([this.#pairIndex, this.#pairIndex + 1]);
    return Object.freeze(this.#digests.filter((_, index) => !drop.has(index)));
  }

  // A pair-free view of this chain, for a caller that wants the rule's shape.
  withoutPair() {
    return new Chain({ digests: this.pairFreeDigests(), pairIndex: null });
  }

  equals(other) {
    if (!(other instanceof Chain) || other.length !== this.length) return false;
    for (let index = 0; index < this.length; index += 1) {
      if (this.#digests[index] !== other.digests[index]) return false;
    }
    return true;
  }

  toRecord() {
    return {
      length: this.length,
      digests: [...this.#digests],
      digest: this.#root,
      pair: this.#pairIndex,
    };
  }
}

// The first index at which `next` stops extending `previous`, or -1 when it
// extends it. Both sides are digest lists. A shorter `next` diverges at its
// own length.
export const firstDivergence = (previous, next) => {
  const span = Math.min(previous.length, next.length);
  for (let index = 0; index < span; index += 1) {
    if (previous[index] !== next[index]) return index;
  }
  return next.length < previous.length ? next.length : -1;
};

export const startsWith = (previous, next) => firstDivergence(previous, next) === -1;

// A refusal's side, from digests alone. The shape names no text and no tool
// input: a reader can compare it with a later request and see the first
// differing index, which is what `details.index` already states.
export const digestSide = (digests, limit = 64) => ({
  count: digests.length,
  shapes: digests.slice(0, Math.max(0, limit)).map((digest) => Object.freeze({ digest })),
});

