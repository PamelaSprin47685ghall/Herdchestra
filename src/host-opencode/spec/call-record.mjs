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
//   { v: 1, type: "call", sessionID, id, at, payload, receipt }
// Two sessions never share a file (`{session}.calls.jsonl`), because a call id
// is an identity inside one session.
//
// `payload` and `receipt` are the replay's evidence, written in the same append
// that records the id:
//   - `payload` is the whole tool input the first call carried, so a cold start
//     can answer `CALL_REPLAY_MISMATCH` exactly as the same process would;
//   - `receipt` is the program's receipt with its return value already encoded
//     (`json` when it is plain JSON, `text` when it is not, `error` when the run
//     threw). A replay answers the ORIGINAL receipt; re-running is what the rule
//     forbids, and "cannot replay" is only honest when nothing was kept.
// A value that cannot be represented (a function, a symbol, a cycle) is kept as
// `text` of its description: the model reads the same nine fields, and the
// value is stated to be textual instead of being silently replaced by null.
//
// The last line may be torn by a crash; a torn line is dropped, exactly as the
// session's own record file treats it. Any other bad line is an error: silently
// skipping one would forget a spent call id, which is the fact this file exists
// to keep.

import fs from "node:fs";
import path from "node:path";

export const CALL_RECORD_VERSION = 1;

export const CALL_STATE_DIR = ".Herdchestra";

const safeName = (value) =>
  String(value).replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120) || "session";

export const callFile = (root, sessionID) =>
  path.join(root, CALL_STATE_DIR, `${safeName(sessionID)}.calls.jsonl`);

// Every call id this session used, as a Set. A missing file is an empty set —
// a session that never called a tool has no calls — and any other read failure
// is reported to the caller, which then refuses instead of pretending.
export const readCalls = (file) => {
  let text;
  try {
    text = fs.readFileSync(file, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return { ok: true, ids: new Set(), payloads: new Map(), receipts: new Map() };
    return { ok: false, reason: "CALL_RECORD_UNREADABLE", detail: String(error?.code ?? error?.message ?? error) };
  }
  if (text === "") return { ok: true, ids: new Set(), payloads: new Map(), receipts: new Map() };
  // A trailing line without a newline is a torn append: the crash happened
  // while writing it, so it carries no fact.
  const complete = text.endsWith("\n") ? text : text.slice(0, text.lastIndexOf("\n") + 1);
  const ids = new Set();
  const payloads = new Map();
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
    if (record.payload !== undefined) payloads.set(record.id, record.payload);
    if (record.receipt !== undefined) receipts.set(record.id, record.receipt);
  }
  return { ok: true, ids, payloads, receipts };
};

// Record one used call id. Called when a program is admitted, BEFORE it runs:
// a call that crashed halfway is still a call that happened, and a restart must
// not perform it a second time. An append that fails is reported; the caller
// refuses the call rather than running one it cannot remember.
export const noteCall = (file, { sessionID, id, payload = null, receipt = null, at = Date.now() }) => {
  if (typeof id !== "string" || id.length === 0) return { ok: false, reason: "NO_CALL_ID" };
  const record = { v: CALL_RECORD_VERSION, type: "call", sessionID, id, at };
  if (payload !== null) record.payload = payload;
  if (receipt !== null) record.receipt = receipt;
  let line;
  try {
    // The value may hold anything; a payload that cannot be encoded is reported
    // rather than silently recorded without it.
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

// One receipt as the record keeps it: the nine fields plus the return value in
// the shape the record can hold. `kind` says what `value` is, so a reader never
// has to guess whether `"42"` is the number or the string.
export const storedReceipt = (receipt, { value = null, error = null } = {}) => {
  if (receipt === null || typeof receipt !== "object") return null;
  let encoded;
  try {
    encoded = { kind: "json", value: value === undefined ? null : JSON.parse(JSON.stringify(value)) };
  } catch {
    encoded = { kind: "text", value: String(value) };
  }
  return {
    name: receipt.name ?? null,
    state: receipt.state ?? null,
    reason: receipt.reason ?? null,
    payload: receipt.payload ?? null,
    source: receipt.source ?? null,
    pendingOperations: receipt.pendingOperations ?? 0,
    command: receipt.command ?? null,
    delta: receipt.delta ?? "",
    remainingWaitMs: receipt.remainingWaitMs ?? 0,
    value: encoded,
    error: error === null || error === undefined ? null : { name: error.name ?? "Error", message: String(error.message ?? error) },
  };
};
