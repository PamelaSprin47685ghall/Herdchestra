// The relay record (§5.3.2): ONE durable fact that decides which tenure holds
// the work.
//
// The host gives no primitive that writes retirement, the barrier and the
// successor start as one commit. What it does give is enough to make our own
// record authoritative, provided the record is ENFORCED:
//
//   - the record names both identities (`incumbent`, `successor`), so a gate
//     has a second id to compare against — the incumbent and the successor are
//     different sessions, not two stretches of one;
//   - the record says what the host can refuse (`requestGate`, `toolGate`).
//     A retirement needs BOTH channels: a retired tenure that can still send
//     requests keeps participating even when every tool call is blocked;
//   - the successor is created INERT (no turn), so nothing observable happens
//     before the record lands;
//   - the record is written with a single atomic replacement, so a reader sees
//     either the old tenure or the new one, never both;
//   - the successor's first prompt carries a deterministic message id, and the
//     host's admission table is durable, so replaying that prompt after a crash
//     is idempotent rather than a duplicate.
//
// This module owns the record only. It writes, reads and classifies; it never
// talks to the host and never decides that a hand-over happened.

import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";

export const RELAY_RECORD_VERSION = 1;

export const RELAY_PHASES = Object.freeze({
  COMMITTED: "committed",
  SENDING: "sending",
  SENT: "sent",
});

// What a host can actually enforce. A retirement is only real when BOTH the
// tool channel and the request channel can be refused: a retired tenure that can
// still send requests keeps participating, and one that can still call tools
// keeps acting. The two are separate host capabilities, so they get separate
// fields rather than one overloaded flag.
//
// Measured, not assumed:
//   - OpenCode: a throwing `context` hook fails the execution and no request
//     reaches the provider -> "host". `execute.before` throws -> "host".
//   - OMP: a throwing `context` hook is caught by the runner, logged, and the
//     request proceeds unchanged -> "none". `tool_call` can block -> "host".
export const RELAY_GATE = Object.freeze({
  HOST: "host",
  NONE: "none",
});

export const RELAY_STATE_DIR = ".Herdchestra";

// The first line of a Plan hand-off's opening message. A hand-off carries the
// operator's original request into the Manager it starts (§5.3.2 step 4); the
// marker is what lets a later request tell "already present" from "still
// missing" without comparing the operator's text, which the retired conversation
// could also contain.
export const PLAN_HANDOFF_MARKER = "# 操作员的原始请求";

// The opening message of a Plan hand-off, or `null` when the hand-off carried no
// operator input. `record.draft` is the Manager's own first task and already
// lives in its history as the prompt; only the operator's words are added.
export const planHandoffOpening = (record) => {
  if (record === null || typeof record !== "object") return null;
  if (record.kind !== "plan-handoff") return null;
  const input = typeof record.operatorInput === "string" ? record.operatorInput.trim() : "";
  if (input.length === 0) return null;
  return `${PLAN_HANDOFF_MARKER}\n\n${input}`;
};

// The default succession notice (spec §5.3.2 step 3): what the successor is
// told instead of the conversation before the relay.
export const SUCCESSION_NOTICE =
  "A relay just happened in this work: you are the successor. The conversation before this point was cut on purpose; " +
  "continue the same work from the operator's input, the saved draft or the work tree, and judge the previous tenure's result yourself.";

const safeName = (value) =>
  String(value).replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120) || "session";

export const relayFile = (root, sessionID) =>
  path.join(root, RELAY_STATE_DIR, `${safeName(sessionID)}.relay.json`);

// The deterministic message id of one successor brief.
//
// `msg_` plus 26 characters of base62, the shape the host's own
// `SessionMessage.ID` uses (`packages/schema/src/session-message.ts`: it checks
// only the `msg_` prefix; its own suffix is 26 chars from `0-9A-Za-z`). The id
// is a function of the identities and the tenure, so a process that crashed
// before writing `sent` derives the SAME id and the host's durable admission
// table reconciles the second delivery instead of admitting a duplicate.
const BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

const base62 = (bytes) => {
  let value = 0n;
  for (const byte of bytes) value = value * 256n + BigInt(byte);
  let out = "";
  while (value > 0n) {
    out = BASE62[Number(value % 62n)] + out;
    value /= 62n;
  }
  return out;
};

export const briefIdFor = ({ incumbent, successor, tenure }) => {
  if (typeof incumbent !== "string" || incumbent.length === 0) {
    throw new TypeError("a brief id needs the incumbent session id");
  }
  if (typeof successor !== "string" || successor.length === 0) {
    throw new TypeError("a brief id needs the successor session id");
  }
  if (!Number.isInteger(tenure) || tenure < 1) {
    throw new TypeError("a brief id needs a positive tenure");
  }
  const digest = createHash("sha256").update(`relay:${incumbent}:${successor}:${tenure}`).digest();
  // 22 bytes carry more than 26 base62 digits, so the slice is padded to a fixed
  // length: the host's id grammar does not require a length, but a fixed one
  // keeps two different seeds from colliding by truncation.
  const encoded = base62(digest.subarray(0, 22)).padStart(26, "0").slice(-26);
  return `msg_${encoded}`;
};

// The record one hand-over writes.
// One worker entry, normalised. `state` is kept because §5.2 preserves process
// ownership for idle workers too: a name that points at a finished session is
// still a fact the successor needs to read the result behind it.
const asRoster = (entries) =>
  Array.isArray(entries)
    ? entries
        .filter((entry) => entry !== null && typeof entry === "object")
        .map((entry) =>
          Object.freeze({
            name: typeof entry.name === "string" ? entry.name : null,
            role: typeof entry.role === "string" ? entry.role : null,
            sessionID: typeof entry.sessionID === "string" ? entry.sessionID : null,
            state: typeof entry.state === "string" ? entry.state : null,
            outcome: entry.outcome ?? null,
          }),
        )
    : [];

export const relayRecord = ({
  sessionID,
  incumbent,
  successor,
  tenure,
  milestone,
  notice = SUCCESSION_NOTICE,
  operatorInput = null,
  roster = [],
  unclaimed = [],
  boundDevops = [],
  fenceThrough = 0,
  phase = RELAY_PHASES.COMMITTED,
  at = Date.now(),
  briefId = undefined,
  requestGate = RELAY_GATE.NONE,
  toolGate = RELAY_GATE.HOST,
}) => {
  if (typeof sessionID !== "string" || sessionID.length === 0) throw new TypeError("a relay record needs a session id");
  if (typeof successor !== "string" || successor.length === 0) throw new TypeError("a relay record needs a successor");
  return {
    v: RELAY_RECORD_VERSION,
    sessionID,
    incumbent,
    successor,
    inheritFrom: incumbent,
    tenure,
    milestone,
    retired: true,
    barrier: true,
    notice: typeof notice === "string" && notice.length > 0 ? notice : SUCCESSION_NOTICE,
    // The operator's ORIGINAL request (§5.3.2 step 3). It is a committed fact,
    // not a re-read of a transcript: the barrier cuts the conversation the
    // retired tenure answered, so the successor could not recover this from its
    // request copy. `null` means the host could not supply it, which the record
    // is allowed to say; `handOver` refuses before writing such a record.
    operatorInput: typeof operatorInput === "string" && operatorInput.trim().length > 0 ? operatorInput : null,
    // §5.2 keeps accepted work, process ownership and unclaimed results across
    // a relay. A worker is a host session, and after the retirement nobody else
    // holds its name, so the record is where the whole roster survives — not
    // only the live workers: an idle worker is still where a name points, and a
    // finished one may hold a result nobody has joined.
    roster: asRoster(roster),
    unclaimed: asRoster(unclaimed),
    // The road-bound `devops` does not change hands, even when it is idle.
    boundDevops: Array.isArray(boundDevops) ? boundDevops.filter((name) => typeof name === "string") : [],
    fenceThrough: Number.isInteger(fenceThrough) && fenceThrough > 0 ? fenceThrough : 0,
    phase,
    // `null` means "this host cannot deliver idempotently": the record still
    // exists, but a replay cannot be reconciled and recovery must refuse.
    briefId: briefId === undefined ? briefIdFor({ incumbent, successor, tenure }) : briefId,
    // What this host can refuse. A record that claims retirement while either
    // channel is `none` describes a retirement the adapter cannot enforce.
    requestGate: Object.values(RELAY_GATE).includes(requestGate) ? requestGate : RELAY_GATE.NONE,
    toolGate: Object.values(RELAY_GATE).includes(toolGate) ? toolGate : RELAY_GATE.NONE,
    at,
  };
};

// One atomic replacement: temp file in the same directory, flushed, renamed,
// then the directory itself is flushed. A record the caller believes is written
// but is not is exactly the crash window this file exists to close.
export const writeRelay = (file, record) => {
  const directory = path.dirname(file);
  fs.mkdirSync(directory, { recursive: true });
  const temporary = `${file}.${process.pid}.${Date.now()}.tmp`;
  let handle = null;
  try {
    handle = fs.openSync(temporary, "w");
    fs.writeFileSync(handle, `${JSON.stringify(record, null, 2)}\n`, "utf8");
    fs.fsyncSync(handle);
    fs.closeSync(handle);
    handle = null;
    fs.renameSync(temporary, file);
  } catch (error) {
    if (handle !== null) {
      try {
        fs.closeSync(handle);
      } catch {}
    }
    try {
      fs.rmSync(temporary, { force: true });
    } catch {}
    return { ok: false, reason: "RELAY_RECORD_FAILED", detail: String(error?.message ?? error) };
  }
  try {
    const dir = fs.openSync(directory, "r");
    try {
      fs.fsyncSync(dir);
    } finally {
      fs.closeSync(dir);
    }
  } catch (error) {
    // The rename is already visible; an unsynced directory is reported, not
    // hidden, because the next power loss could lose it.
    return { ok: true, durable: false, detail: String(error?.message ?? error) };
  }
  return { ok: true, durable: true };
};

const INVALID = (reason, detail = null) => ({ corrupt: true, reason, detail });

// A record, or a statement that there is none, or that the one on disk cannot be
// trusted. A corrupt record is NOT "no record": the two mean opposite things for
// a gate (nobody retired vs. somebody retired and we cannot tell who).
export const readRelay = (file) => {
  let text;
  try {
    text = fs.readFileSync(file, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    return INVALID("RELAY_RECORD_UNREADABLE", String(error?.message ?? error));
  }
  if (text.trim() === "") return INVALID("RELAY_RECORD_EMPTY");
  let record;
  try {
    record = JSON.parse(text);
  } catch (error) {
    return INVALID("RELAY_RECORD_TORN", String(error?.message ?? error));
  }
  if (record === null || typeof record !== "object") return INVALID("RELAY_RECORD_NOT_AN_OBJECT");
  if (record.v !== RELAY_RECORD_VERSION) return INVALID("RELAY_RECORD_VERSION", `v=${record.v}`);
  for (const field of ["sessionID", "incumbent", "successor", "tenure", "milestone", "phase"]) {
    if (record[field] === undefined || record[field] === null) return INVALID("RELAY_RECORD_INCOMPLETE", field);
  }
  if (!Object.values(RELAY_PHASES).includes(record.phase)) {
    return INVALID("RELAY_RECORD_PHASE", String(record.phase));
  }
  return record;
};

// Whether this record describes a retirement the adapter can actually enforce.
// Both channels must be refusable: blocking tool calls alone leaves a retired
// tenure that keeps producing requests, which is not retirement.
export const enforceable = (record) =>
  record !== null &&
  record !== undefined &&
  record.corrupt !== true &&
  record.requestGate === RELAY_GATE.HOST &&
  record.toolGate === RELAY_GATE.HOST;

// What a session must do about this record.
//
//   none          no record: nothing to recover, nothing to gate
//   done          phase is `sent`: the inbox holds the brief
//   resume        phase is `committed`/`sending` AND the host can deliver
//                 idempotently: re-send with `record.briefId`; the host
//                 reconciles the duplicate
//   undeliverable phase is `committed`/`sending` but `briefId` is null (the
//                 host has no idempotent delivery): re-sending could duplicate,
//                 not re-sending could never start the successor
//   unenforceable the host cannot refuse one of the two channels, so the
//                 retirement this record declares cannot be carried out
//   corrupt       the file cannot be trusted: fail closed
export const recoverRelay = (file) => {
  const record = readRelay(file);
  if (record === null) return { kind: "none" };
  if (record.corrupt === true) return { kind: "corrupt", reason: record.reason, detail: record.detail ?? null, record };
  // A Plan hand-off retires nobody, so it has no gates to enforce and no brief
  // to re-deliver: the Manager it started already holds the draft. It is `done`
  // for the same reason a relay's `sent` record is: there is nothing left to do
  // but let the barrier carry the operator's words into the request.
  if (record.kind === "plan-handoff") return { kind: "done", record };
  // Before the delivery question: a successor that starts while its predecessor
  // can still act is worse than a successor that never starts.
  if (!enforceable(record)) return { kind: "unenforceable", record };
  if (record.phase === RELAY_PHASES.SENT) return { kind: "done", record };
  if (record.briefId === null || record.briefId === undefined) return { kind: "undeliverable", record };
  return { kind: "resume", record };
};

// The gate: what this session may do, given the record.
//
// Returns `null` when nothing is refused, or a refusal the caller turns into the
// host's own refusal channel (a block on a tool call, a thrown request).
export const relayGate = (record, sessionID) => {
  if (record === null || record === undefined) return null;
  if (record.corrupt === true) {
    // A record we cannot read cannot say who was retired, so nobody may act.
    return {
      block: true,
      code: "RELAY_CORRUPT",
      reason: `the relay record is unusable (${record.reason ?? "unknown"}); refusing to act until it is repaired`,
    };
  }
  // A Plan hand-off retires nobody; it only carries the operator's words to the
  // Manager it started.
  if (record.kind === "plan-handoff") return null;
  if (record.incumbent === sessionID) {
    return {
      block: true,
      code: "RELAY_RETIRED",
      reason: `this tenure was retired by a relay (successor ${record.successor}, tenure ${record.tenure})`,
    };
  }
  return null;
};

// The barrier (spec §5.3.2 step 3): the successor sees the operator's own input
// and the succession notice, never the conversation that triggered the relay.
//
// `fenceThrough` counts how many leading messages were already on screen when
// the record was written. Everything before that point belonged to the retired
// tenure and is dropped whole.
//
// Zero (the default) means the count was not taken, so no message from the
// retired transcript survives the cut.
//
// Either way the successor keeps the operator's ORIGINAL request, because the
// record carries it as `operatorInput` (§5.3.2 step 3). That is the point of
// storing it: the barrier drops the conversation the retired tenure answered,
// so the successor could not recover the request from its own copy, and picking
// "the first user message" out of the transcript is the guess that leaks that
// very conversation when the two are the same turn.
export const applyBarrier = (messages, record) => {
  if (record === null || record === undefined || record.corrupt === true) return messages;
  // A Plan hand-off is not a relay: no tenure retires, so no predecessor
  // conversation has to be cut. What it still owes the Manager is the
  // operator's original words (§5.3.2 step 4), and the Manager's own history
  // may already hold the opening message the first prompt carried.
  if (record.kind === "plan-handoff") {
    const opening = planHandoffOpening(record);
    if (opening === null) return messages;
    const list = Array.isArray(messages) ? messages : [];
    const present = list.some((message) => {
      if (message?.role !== "user") return false;
      const text = messageText(message);
      return text !== null && text.startsWith(PLAN_HANDOFF_MARKER);
    });
    if (present) return list;
    return [{ role: "user", content: textContent(opening) }, ...list];
  }
  const list = Array.isArray(messages) ? messages : [];
  const through = Number.isInteger(record.fenceThrough) && record.fenceThrough > 0 ? record.fenceThrough : 0;
  let kept;
  if (through > 0) {
    // A counted fence. The count is the incumbent's transcript length, and this
    // list is the SUCCESSOR's own request copy — the two are different
    // transcripts, so `through` is not an index into `list`.
    //
    // The successor's own turns begin where the barrier put them: the notice
    // message it received, then the operator's request. Both are recognised by
    // their own text, so a later request keeps everything from that point on —
    // including the successor's answers, which the host stores and which the
    // hint's prefix chain needs to see unchanged.
    //
    // The fallback (neither marker found) is the counted cut, which is right
    // when the copy is the host's inherited history and nothing else.
    const anchor = firstAnchor(list, record);
    kept = anchor >= 0 ? list.slice(anchor) : (list.length > through ? list.slice(through) : []);
  } else {
    // No count: nothing of the retired transcript survives.
    kept = [];
  }
  // The notice and the operator's work travel together: the successor must not
  // need the retired conversation to know what it is continuing.
  //
  // The operator's original request is part of this one message, never a
  // message of its own: it is a committed fact (`operatorInput`), and a separate
  // operator turn would be textually identical to the retired tenure's first
  // user turn — the very conversation the barrier exists to cut.
  const parts = [record.notice];
  if (typeof record.operatorInput === "string" && record.operatorInput.length > 0) {
    parts.push(`# 操作员的原始请求\n\n${record.operatorInput}`);
  }
  const opening = parts.join("\n\n");
  // The opening message carries the operator's request inside it, so the
  // successor never needs the retired copy to know what it is continuing. It is
  // written as one message on purpose: a separate operator message would be
  // indistinguishable from the retired tenure's own first user turn, which is
  // usually the very same text — and anything that could confuse the two is a
  // way for the cut conversation to survive.
  return [{ role: "user", content: textContent(opening) }, ...kept];
};

// One text, in the request-copy shape the host requires.
//
// The host's request builder reads `message.content.map(...)` unconditionally
// (`packages/core/src/session/model-request.ts` `replaceMedia`), so a bare string
// content crashes the whole model request. Every message this module synthesizes
// therefore carries `[{ type: "text", text }]`, and the string form the barrier
// used to emit is gone.
export const textContent = (text) => [{ type: "text", text: typeof text === "string" ? text : "" }];

// The plain text of a message's content, for comparisons that must work on both
// shapes the adapter meets: a host copy (`[{type:"text",text}]`) and a stored
// record (a string).
export const textOfContent = (content) => {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content.map((part) => (typeof part?.text === "string" ? part.text : "")).join("");
};

// The plain text of one request-copy message, for the duplicate check above.
const messageText = (message) => {
  if (typeof message?.content === "string") return message.content;
  if (!Array.isArray(message?.content)) return null;
  return message.content.map((part) => (typeof part?.text === "string" ? part.text : "")).join("");
};

// Where the successor's own view begins: the first message that carries the
// operator's original request.
//
// The barrier already put that message in front of the successor's first
// request, and the host stores what it sent, so from the second request on the
// copy contains it — followed by the successor's own turns. Cutting at that
// point keeps those turns instead of dropping them with the retired prefix.
const firstAnchor = (messages, record) => {
  const notice = typeof record?.notice === "string" && record.notice.length > 0 ? record.notice : null;
  // Only the notice. The operator's request is NOT a usable marker: the retired
  // tenure's own first user turn can be exactly that text (it usually is — the
  // relay happens while answering it), so matching it would anchor on the
  // conversation the barrier exists to cut.
  if (notice === null) return -1;
  for (let index = 0; index < messages.length; index += 1) {
    const message = messages[index];
    if (message?.role !== "user") continue;
    const text = messageText(message);
    if (text === null) continue;
    if (notice !== null && text.startsWith(notice)) return index;
  }
  return -1;
};

// The successor's first brief: the record's own facts, so a process that
// re-delivers after a crash builds the same text under the same message id.
export const relayBrief = (record) => {
  if (record === null || typeof record !== "object") {
    throw new TypeError("a relay brief needs a record");
  }
  const parts = [record.notice];
  if (typeof record.operatorInput === "string" && record.operatorInput.length > 0) {
    parts.push(`# 操作员的原始请求\n\n${record.operatorInput}`);
  }
  if (typeof record.milestone === "string" && record.milestone.length > 0) {
    parts.push(`里程碑：${record.milestone}`);
  }
  return parts.join("\n\n");
};
