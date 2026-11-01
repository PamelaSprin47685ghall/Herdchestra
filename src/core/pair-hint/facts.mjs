// Persisted facts (spec §1, §6.1, §6.1 steps 1, 4 and 6).
//
// Pair Hint keeps a small journal per session under
// `.Herdchestra/{sessionId}.jsonl`. Every line is one record; folding replays
// them in order and the latest record of a kind wins. A record is appended
// whole, so a crash leaves either the old state or the new one.
//
// Record kinds:
//   bind      the session language, written once at creation
//   epoch     a new presentation epoch
//   presented the request copy the last call sent, together with the fact it
//             shows (epoch, digest, handles, position). Bytes and position live
//             in one record so a crash cannot separate them.
//   refused   why the last request was refused, as shapes and digests only.
//             A refusal sends nothing, so it changes no fact: this record is
//             written beside the chain, never folded into it.
//
// The journal is the only thing a cold start needs: it rebuilds the same
// presentation without asking the host for history.
//
// Appends of one journal are serialized. Two calls that append without
// awaiting each other would otherwise both read the cache before either writes,
// fold the same record twice, and land in a state the journal never had.

import fs from "node:fs/promises";
import path from "node:path";
import { refuse, invalidArgument, PAIR_HINT_CODES } from "./errors.mjs";
import { LanguageBinding } from "./language.mjs";
import { digestOf, PresentationFact } from "./presentation.mjs";
import { CANONICAL_PART, canonicalKey, canonicalMessage } from "./canonical.mjs";
import { isNonEmptyString } from "../util.mjs";

export const JOURNAL_DIR = ".Herdchestra";

export const RECORD_KINDS = Object.freeze({
  BIND: "bind",
  EPOCH: "epoch",
  PRESENTED: "presented",
  REFUSED: "refused",
});

// The diagnostic shape of one request message: what a reader can verify without
// reading the conversation.
//
// A refusal used to leave nothing behind but a message string in the host's log
// ("a message already shown would change", index 1) — not the session, not the
// epoch, not the bytes. On 2026-11-01 that was the whole evidence for a live
// drift, and it took the host's own database to say which key differed. So a
// refusal now records the shape: role, part types, lengths and one digest per
// message. Digests are SHA-256 of the exact `JSON.stringify` the prefix rule
// compares, so two digests that differ are a real byte difference, and the same
// digests prove the messages were equal.
//
// What is NOT recorded: message text, tool input, tool output, system prompt,
// credentials. A digest cannot be read back into the bytes, and the lengths
// stop short of enough to reconstruct a short message. The record is meant to
// answer "where and how much", never "what did the user say".
// `calls` and `results` are added last on purpose: an older record lacks them
// and is still valid (`isIdList` accepts `undefined`), so the key list grows
// without invalidating a journal written before it did.
export const MESSAGE_SHAPE_KEYS = Object.freeze(["role", "parts", "calls", "results", "length", "digest"]);

const DIGEST = /^[0-9a-f]{64}$/;

// One message's shape, in the SAME vocabulary the prefix rule compares.
//
// The digest is over `canonicalKey` (canonical.mjs): the semantic content, with
// sorted keys. Two shapes whose digests differ are two messages the model read
// differently, and two messages with equal digests are the same message to the
// rule — which is what makes a refusal record checkable against a later
// `requests.jsonl` line. Digesting the host's raw object instead would have made
// every record name a difference in bookkeeping as if it were a difference in
// the conversation.
//
// `calls` and `results` are the tool-call ids and tool-result ids this message
// carries, sorted. They are handles, not content: a call id is an opaque token
// the host minted, and a result id is the same token. They are recorded because
// the class of drift that took a live session down is invisible without them —
// "message 3 changed" says nothing, while "message 3 lost call c6" and "message
// 4 answers c6, which message 3 no longer carries" name the half-pair exactly.
// Two shapes with the same ids are the same pairing; a result whose id appears
// in no `calls` of the same side is an orphan of that copy, and a refusal that
// cannot say so sends the next reader back to the host's own database.
export const messageShape = (message) => {
  const canonical = canonicalMessage(message);
  const text = canonicalKey(message);
  const calls = [];
  const results = [];
  for (const part of canonical.parts) {
    if (part.type === CANONICAL_PART.TOOL_CALL) calls.push(part.callId);
    else if (part.type === CANONICAL_PART.TOOL_RESULT) results.push(part.callId);
  }
  return Object.freeze({
    role: canonical.role,
    parts: Object.freeze(canonical.parts.map((part) => part.type)),
    calls: Object.freeze(calls.sort()),
    results: Object.freeze(results.sort()),
    length: text.length,
    digest: digestOf(text),
  });
};

// The shapes of a request copy. `limit` caps how many are kept: a long request
// must not turn one refusal into a journal of the conversation's size.
export const messageShapes = (messages, limit = 64) => {
  const list = Array.isArray(messages) ? messages : [];
  const kept = list.slice(0, Math.max(0, limit)).map(messageShape);
  return Object.freeze({ count: list.length, shapes: Object.freeze(kept) });
};

// How many message shapes one refusal keeps. Enough to see a whole turn of a
// long conversation, small enough that a refusal is never a copy of the request.
export const REFUSAL_SHAPE_LIMIT = 64;

const isCount = (value) => Number.isInteger(value) && value >= 0;

const isIndexOrNull = (value) => value === null || isCount(value);

// A list of tool-call ids, sorted and without repeats. `undefined` is an OLDER
// record — written before the ids were kept — and is accepted so a journal from
// an earlier version still folds. A list that IS present must be well formed:
// a reader trusts it, and a malformed one would name a pairing that never
// existed.
const isIdList = (value) =>
  value === undefined ||
  (Array.isArray(value) &&
    value.every((id) => typeof id === "string") &&
    value.every((id, index) => index === 0 || value[index - 1] <= id));

const isShape = (shape) =>
  shape !== null &&
  typeof shape === "object" &&
  (shape.role === null || typeof shape.role === "string") &&
  Array.isArray(shape.parts) &&
  shape.parts.every((part) => typeof part === "string") &&
  isIdList(shape.calls) &&
  isIdList(shape.results) &&
  isCount(shape.length) &&
  typeof shape.digest === "string" &&
  DIGEST.test(shape.digest);

const isSide = (side) =>
  side === null ||
  (side !== undefined &&
    typeof side === "object" &&
    isCount(side.count) &&
    Array.isArray(side.shapes) &&
    side.shapes.length <= side.count &&
    side.shapes.every(isShape));

// One side of a refusal as a reader sees it. A shape from an older record has
// no id lists; it is answered WITHOUT the keys rather than with empty ones,
// because an empty list means "this message carries no tool call", which is a
// fact, and a missing list means "nobody recorded it", which is a different
// one. Collapsing the two would make a stale record look like evidence.
const refusalSide = (side) => {
  if (side === null || side === undefined) return null;
  return Object.freeze({
    count: side.count,
    shapes: Object.freeze(
      side.shapes.map((shape) =>
        shape.calls === undefined && shape.results === undefined
          ? shape
          : Object.freeze({ ...shape, calls: shape.calls ?? Object.freeze([]), results: shape.results ?? Object.freeze([]) }),
      ),
    ),
  });
};

// One refusal, as the journal keeps it. `code` and `message` come from the
// refusal itself; `index` is the first message the prefix rule disagreed on;
// `previous` and `next` are the two sides it compared, shaped. `at` says where
// the refusal happened, in the injector's own vocabulary.
//
// The digest of a message is the digest of the exact text the prefix rule
// compares, so a reader can verify a diagnosis without the bytes: equal digests
// mean equal messages, and a differing digest at index N is the drift.
export const refusalRecord = ({
  code,
  message = null,
  epoch,
  index = null,
  previous = null,
  next = null,
  limit = REFUSAL_SHAPE_LIMIT,
}) => ({
  kind: RECORD_KINDS.REFUSED,
  code,
  message: typeof message === "string" ? message : null,
  epoch,
  index,
  previous: previous === null ? null : messageShapes(previous, limit),
  next: next === null ? null : messageShapes(next, limit),
});

// The refusal a record carries, or null when the record is not one this module
// would have written. Unknown fields are ignored: a reader must survive a
// journal written by a newer version, and none of the ignored fields can move
// the chain.
export const refusalOf = (record) => {
  if (record === null || typeof record !== "object") return null;
  if (record.kind !== RECORD_KINDS.REFUSED) return null;
  if (typeof record.code !== "string" || record.code === "") return null;
  if (!isCount(record.epoch)) return null;
  if (!isIndexOrNull(record.index)) return null;
  if (!isSide(record.previous) || !isSide(record.next)) return null;
  return Object.freeze({
    code: record.code,
    message: typeof record.message === "string" ? record.message : null,
    epoch: record.epoch,
    index: record.index,
    previous: refusalSide(record.previous),
    next: refusalSide(record.next),
  });
};

const SAFE_SESSION_ID = /^[A-Za-z0-9._-]+$/;

export const assertSessionId = (sessionId) => {
  if (!isNonEmptyString(sessionId) || !SAFE_SESSION_ID.test(sessionId) || sessionId === "." || sessionId === "..") {
    throw invalidArgument("session id must be a plain file-safe name", { sessionId });
  }
  return sessionId;
};

export const journalPath = (root, sessionId) =>
  path.join(root, JOURNAL_DIR, `${assertSessionId(sessionId)}.jsonl`);

// Everything a session needs to rebuild its presentation.
export class PairHintState {
  #binding;
  #epoch;
  #fact;
  #presented;
  #rebased;
  #refusal;

  constructor({ binding = null, epoch = 0, fact = null, presented = null, refusal = null, rebased = null } = {}) {
    this.#binding = binding;
    this.#epoch = epoch;
    this.#fact = fact;
    this.#presented = presented;
    this.#refusal = refusal;
    this.#rebased = rebased;
  }

  // The projection that replaced the chain the last presented request
  // continued ("relay" | "scope-reset" | "scope-remove"), or null when it
  // simply extended the chain before it. Diagnostic only: no request path
  // reads it.
  get rebased() {
    return this.#rebased;
  }

  get binding() {
    return this.#binding;
  }

  get epoch() {
    return this.#epoch;
  }

  get fact() {
    return this.#fact;
  }

  get presented() {
    return this.#presented;
  }

  // The last refusal, or null. Diagnostic only: no request path reads it, and
  // nothing in this state depends on it.
  get refusal() {
    return this.#refusal;
  }

  get bound() {
    return this.#binding !== null;
  }

  // Fold one record. Returns a new state; the old one is untouched.
  apply(record) {
    if (record === null || typeof record !== "object") {
      throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "journal record must be an object");
    }
    switch (record.kind) {
      case RECORD_KINDS.BIND: {
        const binding = LanguageBinding.fromRecord(record.binding);
        if (this.#binding !== null && this.#binding.language !== binding.language) {
          throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "journal rebinds a session to another language", {
            sessionId: binding.sessionId,
            was: this.#binding.language,
            now: binding.language,
          });
        }
        return new PairHintState({
          binding: this.#binding ?? binding,
          epoch: this.#epoch,
          fact: this.#fact,
          presented: this.#presented,
          refusal: this.#refusal,
        });
      }
      case RECORD_KINDS.EPOCH: {
        if (!Number.isInteger(record.epoch) || record.epoch < 0) {
          throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "epoch record needs a non-negative epoch");
        }
        // An epoch only moves forward. A repeated or older number is a stale
        // record, not a new generation.
        if (record.epoch < this.#epoch) {
          throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "journal epoch moves backwards", {
            was: this.#epoch,
            now: record.epoch,
          });
        }
        return new PairHintState({
          binding: this.#binding,
          epoch: record.epoch,
          fact: null,
          presented: null,
          refusal: this.#refusal,
        });
      }
      case RECORD_KINDS.PRESENTED:
        if (!Number.isInteger(record.length)) {
          throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "presented record needs length");
        }
        // A record with no `messages` is a superseded copy: compaction removed
        // them because a later `presented` record replaced this one in the
        // fold. It still contributes its fact and length; its copy is never
        // observable, since `apply` overwrites `#presented` on every
        // `presented` record and only the last one survives.
        if (record.messages !== undefined && !Array.isArray(record.messages)) {
          throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "presented record messages must be an array");
        }
        if (Array.isArray(record.messages) && record.messages.length !== record.length) {
          throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "presented record length does not match");
        }
        // A presented record may carry the fact it shows. That keeps the bytes,
        // their placement and the frozen hint text in one record: a crash
        // cannot separate them, and a cold start rebuilds the same bytes even
        // after the resource files changed (§6.1 step 6).
        // A record comes from disk, so a malformed fact is corruption, not a
        // caller mistake; `fromRecord` raises argument errors and they are
        // restated here with the code a journal failure carries.
        let fact = this.#fact;
        if (record.fact !== undefined) {
          try {
            fact = PresentationFact.fromRecord(record.fact);
          } catch (error) {
            throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "presented fact is malformed", {
              epoch: this.#epoch,
              reason: error?.message,
            });
          }
        }
        if (fact !== null && fact.epoch !== this.#epoch) {
          throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "presented fact belongs to another epoch", {
            epoch: this.#epoch,
            record: fact.epoch,
          });
        }
        if (fact !== null && fact.text !== null && digestOf(fact.text) !== fact.digest) {
          throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "frozen text does not match its digest", {
            epoch: fact.epoch,
          });
        }
        return new PairHintState({
          binding: this.#binding,
          epoch: this.#epoch,
          fact,
          // A superseded record leaves the copy it replaced in place: the next
          // `presented` record replaces it anyway, and dropping it here would
          // make a compacted journal fold differently from the journal before
          // compaction if a later record were torn away.
          presented: Array.isArray(record.messages) ? record.messages : this.#presented,
          refusal: this.#refusal,
          // Provenance of the chain this request continued, or null. Optional
          // and additive: an older journal has no such field, and a value from
          // a newer one is kept as a string rather than refused.
          rebased: typeof record.rebased === "string" ? record.rebased : null,
        });
      case RECORD_KINDS.REFUSED: {
        // A refusal is a fact about a request that was never sent. It must not
        // move the chain, the epoch or the presentation — but a malformed one
        // is still corruption, because a reader would trust it.
        const diagnostic = refusalOf(record);
        if (diagnostic === null) {
          throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "refusal record is malformed", {
            epoch: this.#epoch,
          });
        }
        return new PairHintState({
          binding: this.#binding,
          epoch: this.#epoch,
          fact: this.#fact,
          presented: this.#presented,
          refusal: diagnostic,
        });
      }
      default:
        throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "unknown journal record kind", {
          kind: record.kind,
        });
    }
  }
}

export const foldRecords = (records) => {
  let state = new PairHintState();
  for (const record of records) state = state.apply(record);
  return state;
};

// A journal on disk. `append` writes one line with `O_APPEND`, so concurrent
// appenders in different processes do not interleave inside a line; within one
// process the appends of one store run one after another.
export class JournalStore {
  #sessionId;
  #file;
  #cache;
  #chain;

  constructor({ root, sessionId }) {
    if (!isNonEmptyString(root)) throw invalidArgument("journal root must be a non-empty string");
    this.root = root;
    this.#sessionId = assertSessionId(sessionId);
    this.#file = journalPath(root, sessionId);
    this.#cache = null;
    this.#chain = Promise.resolve();
  }

  get file() {
    return this.#file;
  }

  get sessionId() {
    return this.#sessionId;
  }

  // Read and fold the whole journal. A missing file is an empty journal.
  async read() {
    if (this.#cache !== null) return this.#cache;
    let raw = "";
    try {
      raw = await fs.readFile(this.#file, "utf8");
    } catch (error) {
      if (error?.code !== "ENOENT") {
        throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, `cannot read journal ${this.#file}`, {
          code: error?.code,
        });
      }
    }
    const records = [];
    const lines = raw.split("\n");
    for (let index = 0; index < lines.length; index += 1) {
      const line = lines[index];
      if (line.trim() === "") continue;
      let record;
      try {
        record = JSON.parse(line);
      } catch {
        // A torn last line is a crash during append; it carries no fact.
        if (index === lines.length - 1) break;
        throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, `journal line ${index + 1} is not JSON`, {
          file: this.#file,
          line: index + 1,
        });
      }
      records.push(record);
    }
    this.#cache = foldRecords(records);
    return this.#cache;
  }

  // How large a journal may grow before `append` compacts it. One request copy
  // of a long conversation is megabytes, and every request used to re-write the
  // whole copy: 33 requests of a 67-message session produced 34 MB, and the
  // repository's journals reached 133 MB. The fold keeps only the LAST
  // `presented` record (each one replaces `#presented` wholesale), so every
  // earlier copy is dead weight that a reader never reaches.
  static COMPACT_BYTES = 4 * 1024 * 1024;

  // Drop the superseded request copies. The newest `presented` record keeps its
  // messages, because a cold start resumes the prefix chain from it; every older
  // one keeps its fact and length and loses `messages`. Folding the result gives
  // exactly the same state: `apply` overwrites `#presented` on every `presented`
  // record, so only the last one can be observed.
  //
  // The rewrite is atomic (write beside, then rename) and happens inside the
  // append chain, so no append of this store can interleave with it. It is NOT
  // safe against another PROCESS appending at the same moment — that is the same
  // limitation the journal already has (§11.5: one driver per session), and it is
  // why compaction only runs once the file is large rather than on every append.
  async #compact() {
    let raw;
    try {
      raw = await fs.readFile(this.#file, "utf8");
    } catch (error) {
      if (error?.code === "ENOENT") return;
      throw error;
    }
    const lines = raw.split("\n").filter((line) => line.trim() !== "");
    const parsed = [];
    for (const line of lines) {
      try {
        parsed.push(JSON.parse(line));
      } catch {
        // A torn last line carries no fact and is dropped with the rewrite.
      }
    }
    let lastPresented = -1;
    for (let index = 0; index < parsed.length; index += 1) {
      if (parsed[index]?.kind === RECORD_KINDS.PRESENTED) lastPresented = index;
    }
    const kept = parsed.map((record, index) => {
      if (record?.kind !== RECORD_KINDS.PRESENTED || index === lastPresented) return record;
      if (!Array.isArray(record.messages)) return record;
      const { messages, ...rest } = record;
      return rest;
    });
    const body = kept.map((record) => `${JSON.stringify(record)}\n`).join("");
    if (body === raw) return;
    const temporary = `${this.#file}.compact`;
    await fs.writeFile(temporary, body, { encoding: "utf8", mode: 0o644 });
    await fs.rename(temporary, this.#file);
  }

  // Append one record. Calls are serialized: the next append starts only after
  // the previous one settled, so the folded cache never misses a record.
  append(record) {
    if (record === null || typeof record !== "object") {
      return Promise.reject(invalidArgument("a journal record must be an object"));
    }
    const run = async () => {
      // Refuse a record the current state would reject before writing it, so a
      // bad record never reaches the file.
      const current = this.#cache ?? (await this.read());
      current.apply(record);
      await fs.mkdir(path.dirname(this.#file), { recursive: true });
      await fs.appendFile(this.#file, `${JSON.stringify(record)}\n`, {
        encoding: "utf8",
        mode: 0o644,
      });
      this.#cache = current.apply(record);
      // Bound the file once it is large. A failed compaction must not fail the
      // append: the record is already on disk, and the extra bytes are the only
      // cost.
      try {
        const stats = await fs.stat(this.#file);
        if (stats.size > JournalStore.COMPACT_BYTES) await this.#compact();
      } catch {
        // The append stands; the journal is simply not smaller this time.
      }
      return this.#cache;
    };
    const next = this.#chain.then(run, run);
    // Keep the chain alive after a failure, but do not let the rejection leak
    // into the next caller.
    this.#chain = next.then(
      () => undefined,
      () => undefined,
    );
    return next;
  }
}
