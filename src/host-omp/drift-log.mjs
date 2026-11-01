// The request record: what the host handed the `context` hook, and what the
// adapter made of it (spec §6.1; incident of 2026-11-01, omp 18.14.2).
//
// ## Why this file exists
//
// On 2026-11-01 a live session refused every request with `PREFIX_DRIFT` and
// every tool with `CONTEXT_PROJECTION_FAILED`, in a conversation nobody had
// changed. The only durable evidence was the Pair Hint journal's `refused`
// record: shapes and digests, which say WHERE the chain broke but not WHY — a
// digest difference is compatible with a hundred causes. Answering "why" took
// reading the host's own session database by hand, and that database holds the
// operator's words, so the answer could not be shared.
//
// So every request writes one line here, next to the gate it settles:
//
//   .Herdchestra/<session>.requests.jsonl
//
// One line is one `context` call: the shapes the chain compares, the verdict,
// and the reason. It is the third part of one rule — the host's log names the
// hook, the gate names the stage and the code, this file names the request.
//
// ## What it records, and what it never records
//
// Recorded: the session id, the host version, the message shapes the Pair Hint
// chain compares (role, part types, length, SHA-256 of the exact text the
// prefix rule compares — the same shape `core/pair-hint/facts.mjs` writes into a
// `refused` record), the injection mode, the gate verdict, and the failure.
//
// NOT recorded: message text, tool input, tool output, the system prompt, the
// notice text, credentials, file contents. A digest cannot be read back, and a
// length stops short of enough to rebuild a short message. This record answers
// "where and how much", never "what did the operator say" — the same rule the
// Pair Hint refusal record follows, so the two can be cross-checked by digest
// without either of them carrying the conversation.
//
// ## Why the file is bounded
//
// A session makes one request per turn, and every request of a long session
// would otherwise leave a line forever. The file is trimmed from the front when
// it passes `DRIFT_LOG_LIMIT` bytes, keeping the newest lines, so a session's
// diagnostic is its recent history and not a transcript of its life.
//
// Writing never throws and never changes a decision: a diagnostic that cannot
// be stored is lost, and the request it describes is not. The same rule the Pair
// Hint refusal record follows.

import fs from "node:fs";
import path from "node:path";
import { messageShape } from "../core/pair-hint/facts.mjs";
import { stateDir, safeSessionName } from "./host-facts.mjs";

// How many shapes one request keeps. The Pair Hint refusal record keeps the
// same count, so the two records describe one comparison in one vocabulary.
export const REQUEST_SHAPE_LIMIT = 64;

// The size past which the oldest lines are dropped. 2 MiB is roughly ten
// thousand request lines of a normal session: enough to see a drift's history,
// small enough that the file is never the reason a disk fills.
export const DRIFT_LOG_LIMIT = 2 * 1024 * 1024;

export const requestLogFile = (root, sessionId) =>
  path.join(stateDir(root), `${safeSessionName(sessionId)}.requests.jsonl`);

const shapesOf = (messages) => {
  const list = Array.isArray(messages) ? messages : [];
  return {
    count: list.length,
    shapes: list.slice(0, REQUEST_SHAPE_LIMIT).map(messageShape),
  };
};

// The message roles OMP gives the two rewrites it performs on its own history.
// `compactionSummary` is `createCompactionSummaryMessage` (agent/src/compaction/
// messages.ts); `branchSummary` is `createBranchSummaryMessage` (same file). A
// request whose copy carries either was built from a history the model only
// read part of, which is the ONE fact that makes a shortened copy legitimate —
// and the fact whose absence in an old record sent a reader to the host's own
// database. They are role NAMES, not text: recording them exposes nothing.
const REWRITE_ROLES = Object.freeze(["compactionSummary", "branchSummary"]);

// Where the pair stands in a request copy, in the injector's own vocabulary:
// the assistant tool-call part whose call id carries `pair-hint:<epoch>:…`, and
// the index of the message holding it. `null` when the copy carries none.
//
// `summary` is the index of the first message whose role is a host rewrite
// marker, or null. It is recorded beside the pair because the two facts decide
// together what the next reader has to ask: a request with a summary at index 0
// is the first request after a compaction, and a request without one cannot
// have been shortened by one.
const pairPlacementOf = (messages) => {
  const list = Array.isArray(messages) ? messages : [];
  let pair = null;
  let summary = null;
  for (let index = 0; index < list.length; index += 1) {
    if (summary === null && REWRITE_ROLES.includes(list[index]?.role)) summary = index;
    if (pair !== null) continue;
    const content = Array.isArray(list[index]?.content) ? list[index].content : [];
    for (const part of content) {
      if (typeof part?.toolCallId !== "string" || !part.toolCallId.startsWith("pair-hint:")) continue;
      pair = { index, epoch: part.toolCallId.split(":")[1] ?? null };
      break;
    }
  }
  return { ...(pair ?? { index: null, epoch: null }), summary };
};

// The messages the host handed over and the adapter did not send, as shapes.
//
// This is the one fact the old record could not state. A copy that goes out
// shorter than the one received has two possible authors — the Scope projection
// (the spec deciding) or the adapter's own half-pair rule (the adapter deciding)
// — and only the second one is a defect when it is allowed to re-base the chain.
// Comparing the two shapes position by position, the same way the prefix rule
// does, names every message that left. A message whose shape appears in
// `incoming` but in no `presented` slot is dropped; nothing about its content is
// recorded beyond the shape every other line already carries.
const droppedOf = (incoming, presented) => {
  if (presented === null) return null;
  const before = Array.isArray(incoming) ? incoming : [];
  const after = new Set((Array.isArray(presented) ? presented : []).map((message) => messageShape(message).digest));
  const dropped = [];
  for (const message of before) {
    const shape = messageShape(message);
    if (after.has(shape.digest)) continue;
    dropped.push({ ...shape, at: dropped.length });
  }
  return dropped;
};

// The one line one request leaves. `entry` is what the handler knows:
//
//   phase       "open" (the request is starting) or "settled" (it was decided)
//   host        the host's own version string, or null
//   incoming    the copy the host handed over
//   presented   the copy the adapter will send (settled only)
//   mode        the injector's answer ("placed" | "replayed"), or null
//   failure     `{stage, code, detail}` of the one projection that failed
//
// A digest of `incoming` is not enough to tell two requests apart when the
// difference is which message the host grew, so both sides are recorded as
// shapes and the reader compares them position by position — exactly what the
// prefix rule does.
//
// `dropped` is the answer to "who shortened this copy". It is written only on a
// settled line, because only then is there a copy to compare with; `null` means
// the comparison did not happen, never "nothing was dropped".
export const requestRecord = ({ phase, sessionId, host = null, incoming, presented = null, mode = null, failure = null }) => {
  const before = shapesOf(incoming);
  const after = presented === null ? null : shapesOf(presented);
  return {
    v: 1,
    kind: "request",
    phase,
    at: new Date().toISOString(),
    sessionId,
    host,
    incoming: before,
    presented: after,
    dropped: droppedOf(incoming, presented),
    pair: pairPlacementOf(presented),
    mode,
    allowed: failure === null,
    failure: failure === null ? null : { stage: failure.stage ?? null, code: failure.code ?? null, detail: failure.detail ?? null },
  };
};

// The record of one change of the request gate's verdict.
//
// Why this line exists. The gate is what refuses every tool call of a session
// (`CONTEXT_PROJECTION_FAILED`), and until this record the only trace of a
// refusal was the request record written at the END of the `context` hook. The
// measured failure of 2026-11-01 (turn 1, 54 tool results, 233 s) is the window
// between the two: the stream refused the calls, every gate file said
// `allowed: true`, and no `.requests.jsonl` line carried the refusal at all. A
// refusal with no record is a defect even when it is transient, so the verdict
// is recorded at the moment it changes, before the request that changed it can
// be abandoned.
//
// `phase` is the gate's own moment: "open" (the request starts, verdict
// `refused`/pending), "settle" (the request decided), "unwritable" (the gate
// file itself could not be written, so the refusal is held in memory only),
// "refusal" (a tool call was refused from this verdict — the one line that
// proves the model was stopped). No message list is recorded: the gate's
// record is about the VERDICT, and the shapes belong to the request record.
export const gateStateRecord = ({ phase, sessionId, host = null, verdict = null, trigger = null }) => ({
  v: 1,
  kind: "gate",
  phase,
  at: new Date().toISOString(),
  sessionId,
  host,
  trigger,
  // The request this verdict governs. A reader needs it to tell "this
  // request's projection failed" from "another request was projecting" — the
  // two shapes that used to look identical in this file.
  request: typeof verdict?.request === "string" && verdict.request.length > 0 ? verdict.request : null,
  allowed: verdict === null ? null : verdict.allowed !== false,
  stage: typeof verdict?.stage === "string" ? verdict.stage : null,
  code: typeof verdict?.code === "string" ? verdict.code : null,
  detail: typeof verdict?.detail === "string" ? verdict.detail : null,
});

// Trim a log that grew past the limit, keeping whole lines from the end. A torn
// first line is dropped with the rest of the excess, so the file always parses
// line by line.
const trim = (target) => {
  const size = fs.statSync(target).size;
  if (size <= DRIFT_LOG_LIMIT) return;
  const raw = fs.readFileSync(target, "utf8");
  const lines = raw.split("\n").filter((line) => line.trim() !== "");
  // A gate record is never dropped. Every other line describes one request and
  // the newest ones say all that is worth knowing; a `refused` verdict is the
  // reason a session stopped working, and a log that trimmed it away would
  // restore exactly the state this file exists to end — a refusal with no
  // record. There are a few per request, so keeping them all cannot grow the
  // file past the limit by much.
  const isGate = (line) => line.includes('"kind":"gate"');
  const kept = [];
  let bytes = 0;
  for (let index = lines.length - 1; index >= 0; index -= 1) {
    const line = lines[index];
    const gate = isGate(line);
    const length = Buffer.byteLength(line, "utf8") + 1;
    if (!gate && bytes + length > DRIFT_LOG_LIMIT) break;
    bytes += length;
    kept.push(line);
  }
  const temp = `${target}.${process.pid}.tmp`;
  fs.writeFileSync(temp, kept.length === 0 ? "" : `${kept.reverse().join("\n")}\n`, "utf8");
  fs.renameSync(temp, target);
};

// Where the record goes when its own file cannot be written: the nearest
// directory that already exists, one dotfile per process.
//
// The incident this file exists for (01a1958f) is the case: a request was
// refused, and the only place the refusal could have been written was the
// state directory. A refusal that leaves no line anywhere is the state that
// took a hand-read of the host's database to explain — and that database holds
// the operator's words. So a write that fails is not the end of the record: it
// becomes a record of its own, naming the failure, in the first ancestor of the
// target that accepts it.
const fallbackTarget = (target) => {
  const name = path.basename(target);
  let dir = path.dirname(target);
  while (true) {
    try {
      if (fs.statSync(dir).isDirectory()) return path.join(dir, `.${name}.${process.pid}.fallback`);
    } catch {
      // Not there: climb.
    }
    const parent = path.dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
};

const appendLine = (target, record) => {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.appendFileSync(target, `${JSON.stringify(record)}\n`, { encoding: "utf8", mode: 0o644 });
};

// Append one record. Never throws: the caller is deciding a request, and a
// diagnostic must not be able to change that decision.
//
// The answer says WHERE the record landed, because a failure that is itself
// unreported is the defect this function was extended for:
//
//   { ok: true, file }            the record is in `target`
//   { ok: false, file, failure }  `target` could not be written; the line is in
//                                 `file`, and it names the failure and the
//                                 intended target. `file` is null only when no
//                                 ancestor directory accepted it either.
//
// The fallback line is a request record with `record.failure` untouched (the
// request's own verdict) plus `writeFailure`, so a reader sees both: what the
// request decided, and that its file could not hold the answer.
export const recordRequest = (target, record) => {
  try {
    appendLine(target, record);
    trim(target);
    return { ok: true, file: target, failure: null };
  } catch (error) {
    const failure = {
      code: typeof error?.code === "string" ? error.code : String(error?.name ?? "EIO"),
      detail: String(error?.message ?? error),
      target,
    };
    const fallback = fallbackTarget(target);
    if (fallback === null) return { ok: false, file: null, failure };
    const line = { ...record, writeFailure: failure, at: new Date().toISOString() };
    try {
      appendLine(fallback, line);
      return { ok: false, file: fallback, failure };
    } catch {
      return { ok: false, file: null, failure };
    }
  }
};
