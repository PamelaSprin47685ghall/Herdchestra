// What a relay record means for one session, read once per hook (根 §2.4 第 3 步).
//
// `relay-record.mjs` owns the file: it writes, reads and classifies. This module
// is the adapter's use of it, and it exists so the two gates and the session
// state read the record through ONE path:
//
//   - the read is cached per session, because the gates run on every tool call
//     and every model request;
//   - the cache is keyed by the file's mtime and size, so a record written by
//     another process (or by this one after a write) is read again instead of
//     serving a stale tenure. Nothing here decides when to invalidate: the disk
//     decides, and a writer only has to make the file change;
//   - a record that cannot be read is NOT "no record". `readRelay` answers
//     `{corrupt: true}` and `relayGate` refuses everybody, because a corrupt
//     record means somebody retired and we cannot tell who.

import fs from "node:fs";
import path from "node:path";

import { readRelay, relayFile, relayGate, RELAY_STATE_DIR } from "./relay-record.mjs";

// The record of a relay names BOTH identities, but it is written under the
// incumbent's file (§6.1 step 2: the write is what retires the incumbent). A
// successor therefore has no file of its own, and looking only at
// `{sessionID}.relay.json` would answer "no record" for the very session the
// record was written for — the barrier would never cut its request and it would
// never inherit the predecessor's facts.
//
// So a lookup that finds no own file scans the state directory for a record
// whose `successor` is this session.
//
// The scan fails closed in three ways, because a record that is half-known is
// worse than no record at all:
//
//   - two or more files name this session as their successor: this is a
//     multi-generation chain whose link to this session is ambiguous, and
//     picking one would decide the barrier from a guess;
//   - a file naming this session cannot be read: somebody retired us and the
//     terms are unreadable, which `RELAY_CORRUPT` is for;
//   - the state directory itself cannot be listed for a reason other than
//     "absent": the same uncertainty.
//
// The answer is therefore a record-shaped value, not a path: `{ file }` on
// success, `{ corrupt }` when the discovery itself cannot be trusted, `null`
// when nothing names this session.
const DISCOVERY_CODES = Object.freeze({
  AMBIGUOUS: "RELAY_RECORD_AMBIGUOUS",
  UNREADABLE: "RELAY_RECORD_UNREADABLE",
});

const successorRecordIn = (root, sessionID) => {
  if (typeof root !== "string" || root.length === 0) return null;
  if (typeof sessionID !== "string" || sessionID.length === 0) return null;
  const directory = path.join(root, RELAY_STATE_DIR);
  let entries;
  try {
    entries = fs.readdirSync(directory);
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    return {
      corrupt: true,
      reason: DISCOVERY_CODES.UNREADABLE,
      detail: String(error?.code ?? error?.message ?? error),
    };
  }
  const candidates = [];
  for (const entry of entries) {
    if (!entry.endsWith(".relay.json")) continue;
    const file = path.join(directory, entry);
    const record = readRelay(file);
    if (record === null) continue;
    if (record.corrupt === true) {
      // A file we cannot parse might be the one that retired us. Treat it as
      // ours: refusing is recoverable, guessing who was retired is not.
      candidates.push({ file, corrupt: true, reason: record.reason ?? DISCOVERY_CODES.UNREADABLE });
      continue;
    }
    // A Plan hand-off names this session as its successor but retires nobody;
    // it is not the record a gate or a barrier looks for. Counting it here would
    // make a Manager that was started by a Plan look like it also inherited a
    // relay, which is the ambiguity this scan refuses.
    if (record.kind === "plan-handoff") continue;
    if (record.successor === sessionID) candidates.push({ file, corrupt: false });
  }
  if (candidates.length === 0) return null;
  if (candidates.length > 1) {
    return {
      corrupt: true,
      reason: DISCOVERY_CODES.AMBIGUOUS,
      detail: `${candidates.length} records name this session as successor: ${candidates
        .map((candidate) => path.basename(candidate.file))
        .join(", ")}`,
    };
  }
  const [only] = candidates;
  if (only.corrupt === true) return { corrupt: true, reason: only.reason, detail: path.basename(only.file) };
  return { file: only.file };
};

const signatureOf = (file) => {
  try {
    const stat = fs.statSync(file);
    return `${stat.mtimeMs}:${stat.size}:${stat.ino}`;
  } catch (error) {
    if (error?.code === "ENOENT") return "absent";
    return `unreadable:${String(error?.code ?? error?.message ?? error)}`;
  }
};

// One session's record, as the gates see it. `root` is the workspace the plugin
// was loaded for; a session without one cannot have a record.
export class RelayFacts {
  #root;
  #file;
  #signature = null;
  #record = null;
  #sessionID;

  constructor({ root, sessionID }) {
    this.#root = root;
    this.#sessionID = typeof sessionID === "string" && sessionID.length > 0 ? sessionID : null;
    this.#file = typeof root === "string" && root.length > 0 ? relayFile(root, sessionID) : null;
  }

  get file() {
    return this.#file;
  }

  // The record on disk, or null, or `{corrupt: true, ...}`. Re-read whenever the
  // file changed; never repaired here.
  record() {
    // Own file first. A corrupt own file is never replaced by a discovered one:
    // two records for one identity is the ambiguity the gate refuses to guess
    // at, so the corrupt file wins and `readRelay` reports it.
    const own = this.#file;
    let file = own !== null && signatureOf(own) !== "absent" ? own : null;
    let discovered = null;
    if (file === null) {
      discovered = successorRecordIn(this.#root, this.#sessionID);
      if (discovered !== null && discovered.corrupt === true) {
        // The discovery itself cannot be trusted (two records name this session,
        // or one of them is unreadable). Report a corrupt record rather than a
        // missing one: a gate must refuse, not proceed as if nobody retired.
        this.#signature = `discovery:${discovered.reason}:${discovered.detail ?? ""}`;
        this.#record = discovered;
        return this.#record;
      }
      file = discovered?.file ?? null;
    }
    if (file === null) {
      // Nothing governs this session. Forget the last read, so a record that
      // appears later (this process or the next) is not masked by a stale hit.
      this.#signature = null;
      this.#record = null;
      return null;
    }
    const signature = signatureOf(file);
    if (signature === this.#signature) return this.#record;
    this.#signature = signature;
    this.#record = readRelay(file);
    return this.#record;
  }

  // The refusal this session faces, or null. `sessionID` is passed in rather
  // than kept, so one record answers for both identities it names.
  gate(sessionID) {
    return relayGate(this.record(), sessionID);
  }
}
