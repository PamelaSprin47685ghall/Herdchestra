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
//
// ## Why there is no scan
//
// The record names BOTH identities and is written under the incumbent's file.
// A successor therefore has no file of its own unless the hand-over puts one
// there. It used to find its record by listing the whole `.Herdchestra`
// directory and reading every `*.relay.json` on every miss — 32k entries on a
// long-lived workspace, on the tool-call path (root §1.1 item 1 forbids this).
//
// Now the hand-over writes a COPY of the record under the successor's own name
// (`relay-host.mjs`), in the same step that commits the incumbent's file. A
// successor reads exactly one path. There is no fallback scan and no negative
// cache that rescans: an absent file means no record, and a corrupt file means
// fail closed.
//
// A pointer is overwritten when its owner later becomes an incumbent and relays
// in its own right; from that point the file is its own record, which is the
// fact its gates need.

import fs from "node:fs";

import { readRelay, relayFile, relayGate } from "./relay-record.mjs";

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
  #file;
  #signature = null;
  #record = null;

  constructor({ root, sessionID }) {
    this.#file = typeof root === "string" && root.length > 0 ? relayFile(root, sessionID) : null;
  }

  get file() {
    return this.#file;
  }

  // The record on disk, or null, or `{corrupt: true, ...}`. Re-read whenever the
  // file changed; never repaired here.
  //
  // The file is either the successor's pointer (written by the hand-over) or the
  // session's own record (written when it relays). Both are the same shape, and
  // the gate reads the identities to decide which one it is holding.
  record() {
    const file = this.#file;
    if (file === null) return null;
    const signature = signatureOf(file);
    if (signature === "absent") {
      // Nothing governs this session. Forget the last read, so a record that
      // appears later (this process or the next) is not masked by a stale hit.
      this.#signature = null;
      this.#record = null;
      return null;
    }
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
