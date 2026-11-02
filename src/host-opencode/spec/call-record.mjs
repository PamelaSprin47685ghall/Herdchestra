// Which call ids this session already used, kept across a process (§2.4 第 6 步).
//
// The rule has three parts, and the third is the one a restart breaks:
//
//   1. the same call id, same payload, replays: the ORIGINAL receipt comes back
//      and nothing runs;
//   2. the same call id, different payload, is refused: `CALL_REPLAY_MISMATCH`;
//   3. a call id that was used before this process started can be neither
//      replayed nor re-run. The program, its receipt and its payload were
//      memory, so "same call" cannot be shown — and running it again would
//      perform a call that already happened. It is refused as
//      `CALL_REPLAY_LOST`.
//
// Part 3 needs one durable bit per call id: "this id was used". That is all this
// file keeps. The receipt itself is not persisted: `value` can be any JS value,
// a function or a class instance among them, and a JSON copy would be a lie
// about what the program returned. So a cold start answers honestly — it knows
// the id is spent, and says it cannot replay it — instead of guessing.
//
// The file is append-only JSONL, one line per call:
//   { v: 1, type: "call", sessionID, id, at, payloadDigest, receipt }
// Two sessions never share a file (`{session}.calls.jsonl`), because a call id
// is an identity inside one session.
//
// `payloadDigest` and `receipt` are the replay's evidence, written in the same
// append that records the id:
//   - the digest is `sha256(stableJson(input))`. The whole tool input used to be
//     copied here, which duplicated the program source and the scope annotation
//     on every call and grew the journal without adding a fact: the only
//     question a cold start asks is "is this the same payload?" (root §1.1 item
//     1). A legacy line that still carries `payload` is read by hashing it, so
//     old journals keep working.
//   - `receipt` is what the call answered: the state, the original source, the
//     return value (`json` when it is plain JSON, `text` when it is not, `error`
//     when the run threw) and the few view fields a replay must not invent. A
//     replay answers the ORIGINAL receipt; re-running is what the rule forbids,
//     and "cannot replay" is only honest when nothing was kept.
// A value that cannot be represented (a function, a symbol, a cycle) is kept as
// `text` of its description: the model reads the same fields, and the value is
// stated to be textual instead of being silently replaced by null.
//
// The last line may be torn by a crash; a torn line is dropped, exactly as the
// session's own record file treats it. Any other bad line is an error: silently
// skipping one would forget a spent call id, which is the fact this file exists
// to keep.

import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";

import { stableJson } from "../../core/util.mjs";

export const CALL_RECORD_VERSION = 1;

export const CALL_STATE_DIR = ".Herdchestra";

const safeName = (value) =>
  String(value).replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120) || "session";

export const callFile = (root, sessionID) =>
  path.join(root, CALL_STATE_DIR, `${safeName(sessionID)}.calls.jsonl`);

// The identity of one tool input, as the replay compares it. `stableJson` makes
// key order irrelevant, so two spellings of one payload are one digest.
export const payloadDigest = (payload) =>
  `sha256:${createHash("sha256").update(stableJson(payload)).digest("hex")}`;

// Every call id this session used, as a Set, with its payload digest and its
// receipt. A missing file is an empty set — a session that never called a tool
// has no calls — and any other read failure is reported to the caller, which
// then refuses instead of pretending.
export const readCalls = (file) => {
  let text;
  try {
    text = fs.readFileSync(file, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return { ok: true, ids: new Set(), digests: new Map(), receipts: new Map() };
    return { ok: false, reason: "CALL_RECORD_UNREADABLE", detail: String(error?.code ?? error?.message ?? error) };
  }
  if (text === "") return { ok: true, ids: new Set(), digests: new Map(), receipts: new Map() };
  // A trailing line without a newline is a torn append: the crash happened
  // while writing it, so it carries no fact.
  const complete = text.endsWith("\n") ? text : text.slice(0, text.lastIndexOf("\n") + 1);
  const ids = new Set();
  const digests = new Map();
  const receipts = new Map();
  for (const line of complete.split("\n")) {
    if (line === "") continue;
    let record;
    try {
      record = JSON.parse(line);
    } catch {
      return { ok: false, reason: "CALL_RECORD_CORRUPT", detail: line.slice(0, 120) };
    }
    if (record === null || typeof record !== "object" || record.v !== CALL_RECORD_VERSION) {
      return { ok: false, reason: "CALL_RECORD_CORRUPT", detail: line.slice(0, 120) };
    }
    if (record.type !== "call") {
      return { ok: false, reason: "CALL_RECORD_CORRUPT", detail: `unknown type ${record.type}` };
    }
    if (typeof record.id !== "string" || record.id.length === 0) {
      return { ok: false, reason: "CALL_RECORD_CORRUPT", detail: "a call record needs an id" };
    }
    ids.add(record.id);
    if (typeof record.payloadDigest === "string" && record.payloadDigest.length > 0) {
      digests.set(record.id, record.payloadDigest);
    } else if (record.payload !== undefined) {
      // A line written before the digest existed. Reading it by hashing keeps a
      // spent call id usable for the mismatch check without keeping the bytes
      // in memory or writing them again.
      digests.set(record.id, payloadDigest(record.payload));
    }
    if (record.receipt !== undefined) receipts.set(record.id, record.receipt);
  }
  return { ok: true, ids, digests, receipts };
};

// Record one used call id. Called when a program is admitted, BEFORE it runs:
// a call that crashed halfway is still a call that happened, and a restart must
// not perform it a second time. An append that fails is reported; the caller
// refuses the call rather than running one it cannot remember.
//
// `payload` is the whole tool input. Only its digest reaches the disk.
export const noteCall = (file, { sessionID, id, payload = null, payloadDigest: digest = null, receipt = null, at = Date.now() }) => {
  if (typeof id !== "string" || id.length === 0) return { ok: false, reason: "NO_CALL_ID" };
  const record = { v: CALL_RECORD_VERSION, type: "call", sessionID, id, at };
  if (payload !== null) {
    try {
      record.payloadDigest = payloadDigest(payload);
    } catch (error) {
      // A payload the digest cannot encode is reported rather than recorded
      // without it: a spent id with no digest could never prove a mismatch.
      return { ok: false, reason: "CALL_RECORD_UNENCODABLE", detail: String(error?.message ?? error) };
    }
  } else if (typeof digest === "string" && digest.length > 0) {
    record.payloadDigest = digest;
  }
  if (receipt !== null) record.receipt = receipt;
  let line;
  try {
    line = JSON.stringify(record);
  } catch (error) {
    return { ok: false, reason: "CALL_RECORD_UNENCODABLE", detail: String(error?.message ?? error) };
  }
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.appendFileSync(file, `${line}\n`, "utf8");
    return { ok: true };
  } catch (error) {
    return { ok: false, reason: "CALL_RECORD_UNWRITABLE", detail: String(error?.code ?? error?.message ?? error) };
  }
};

// One receipt as the record keeps it: what a replay cannot rebuild and nothing
// else. `name` is the call id (the record's own key), `pendingOperations` is 0
// for a finished program, and `delta`/`remainingWaitMs` describe one request's
// window, not the finished run — keeping them would add bytes that a cold start
// would have to pretend are still current. `reason` and `payload` are kept only
// when non-null, which is the only case a receipt carries them.
//
// `kind` says what `value` is, so a reader never has to guess whether `"42"` is
// the number or the string.
export const storedReceipt = (receipt, { value = null, error = null } = {}) => {
  if (receipt === null || typeof receipt !== "object") return null;
  let encoded;
  try {
    encoded = { kind: "json", value: value === undefined ? null : JSON.parse(JSON.stringify(value)) };
  } catch {
    encoded = { kind: "text", value: String(value) };
  }
  return {
    state: receipt.state ?? null,
    ...(receipt.reason === null || receipt.reason === undefined ? {} : { reason: receipt.reason }),
    ...(receipt.payload === null || receipt.payload === undefined ? {} : { payload: receipt.payload }),
    source: receipt.source ?? null,
    ...(receipt.command === null || receipt.command === undefined ? {} : { command: receipt.command }),
    value: encoded,
    error: error === null || error === undefined ? null : { name: error.name ?? "Error", message: String(error.message ?? error) },
  };
};
