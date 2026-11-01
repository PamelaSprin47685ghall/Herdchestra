// The operator's original input, kept as a durable fact per task (根 §2.4 第 3 步).
//
// The host keeps a transcript, and it compacts. After a compaction the earliest
// user message in a request copy is the summarised remainder, not what the
// operator asked: reading "the first user turn" at relay time can therefore
// return the latest post-compaction text and present it as the original. That is
// the failure this module exists to make impossible.
//
// So the original is captured ONCE, on the first genuine operator receipt, and
// written before anything else in that receipt happens. Every later reader —
// the relay record, a successor's brief, a restarted process — reads the stored
// fact instead of guessing from a transcript.
//
// ## What counts as genuine is a fact about the call, not about the words
//
// A receipt is genuine when it is the operator speaking to a top-level session:
//
//   - it is a `prompt` event with text;
//   - it is not one of the adapter's own deliveries (the relay brief, the
//     succession notice). That is decided from the delivery's identity — the
//     deterministic brief id the relay record wrote, or the `queue` delivery the
//     adapter always uses for its own briefs — never from the text. Text belongs
//     to the operator and may begin with anything, including a sentence that
//     reads like ours;
//   - the session is not a child of another session. A `fork`ed worker's first
//     prompt is its brief from the Manager (§4.1) and a fission lane's first
//     prompt is its lane input (§4.2). Neither is the operator's words.
//
// The parent fact is the hard part: the `prompt` hook's event carries no
// `parentID`, and a child's `session.created` event may arrive after its first
// prompt. The adapter records every parent id it sees. A session whose parent is
// unknown is NOT treated as top-level: capturing on a guess is the mistake this
// module exists to prevent.
//
// ## Old sessions cannot capture later prompts
//
// A session that was already running when this build started has no stored
// original, and its next prompt is not necessarily the operator's first: the
// task may have been going for hours. Capturing that prompt would label a later
// message as the origin. Such a session is marked `unrecorded` once (a durable
// marker), and from then on it never captures; its relays refuse with
// `ORIGINAL_INPUT_MISSING` instead of inventing an origin.
//
// ## Crash-safe publication
//
// The fact is published by writing a temporary file in the same directory,
// flushing it, and linking it into place with `link(2)`, which fails with
// `EEXIST` when the fact already exists. A reader therefore sees either no fact
// or one complete fact, never half a JSON document. The first writer wins, and a
// writer that loses keeps the winner's fact. A leftover temporary file from a
// crash carries no fact and is ignored by readers; the capture that created it
// removes it.
//
// ## Fail closed
//
// A reader that needs the original and finds no fact refuses. It never falls
// back to the transcript and never substitutes the newest user message.
// `ORIGINAL_INPUT_MISSING` means the task never had a recorded original (or was
// marked unrecorded); `ORIGINAL_INPUT_UNREADABLE` means a fact exists but cannot
// be trusted.

import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";

export const ORIGINAL_RECORD_VERSION = 1;

export const ORIGINAL_STATE_DIR = ".Herdchestra";

export const ORIGINAL_REFUSALS = Object.freeze({
  MISSING: "ORIGINAL_INPUT_MISSING",
  UNREADABLE: "ORIGINAL_INPUT_UNREADABLE",
});

const safeName = (value) =>
  String(value).replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120) || "session";

// The file name a host session gets. The key is derived from the exact id, never
// from a sanitized spelling: a host id is free-form, and two different ids can
// share a sanitized form, which would make one session's original input answer
// for another. A short readable prefix keeps the directory listing legible.
export const originalKey = (sessionID) =>
  `original-${safeName(String(sessionID).slice(0, 16))}-${createHash("sha256")
    .update(String(sessionID), "utf8")
    .digest("hex")
    .slice(0, 16)}`;

// One file per session: the first receipt wins, and nothing rewrites it.
export const originalFile = (root, sessionID) =>
  path.join(root, ORIGINAL_STATE_DIR, `${originalKey(sessionID)}.json`);

// The marker that says "this session started before the fact existed, so its
// next prompt is not its first". Its presence forbids every capture.
export const unrecordedFile = (root, sessionID) =>
  path.join(root, ORIGINAL_STATE_DIR, `${originalKey(sessionID)}.unrecorded`);

const readJson = (file) => {
  let raw;
  try {
    raw = fs.readFileSync(file, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return { absent: true };
    return { error: String(error?.code ?? error?.message ?? error) };
  }
  try {
    return { value: JSON.parse(raw) };
  } catch {
    return { error: "not JSON" };
  }
};

// The stored fact, or a refusal. A missing file is `MISSING`; a file that exists
// but cannot be parsed, names another session, or holds no text is `UNREADABLE`.
// There is no third answer: a caller that cannot tell the two apart would be
// back to guessing.
export const readOriginal = (root, sessionID) => {
  if (typeof root !== "string" || root.length === 0) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.MISSING, detail: "no workspace root" };
  }
  if (typeof sessionID !== "string" || sessionID.length === 0) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.MISSING, detail: "no session id" };
  }
  const read = readJson(originalFile(root, sessionID));
  if (read.absent === true) {
    const marked = readJson(unrecordedFile(root, sessionID));
    return {
      ok: false,
      refusal: ORIGINAL_REFUSALS.MISSING,
      detail:
        marked.absent === true
          ? "no original input recorded"
          : "the session started before the original was recorded",
    };
  }
  if (read.error !== undefined) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.UNREADABLE, detail: read.error };
  }
  const record = read.value;
  if (record === null || typeof record !== "object") {
    return { ok: false, refusal: ORIGINAL_REFUSALS.UNREADABLE, detail: "the record is not an object" };
  }
  if (record.v !== ORIGINAL_RECORD_VERSION) {
    return {
      ok: false,
      refusal: ORIGINAL_REFUSALS.UNREADABLE,
      detail: `record version ${String(record.v)} is not ${ORIGINAL_RECORD_VERSION}`,
    };
  }
  if (record.sessionID !== sessionID) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.UNREADABLE, detail: "the record names another session" };
  }
  if (typeof record.text !== "string" || record.text.trim().length === 0) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.UNREADABLE, detail: "the record holds no text" };
  }
  return { ok: true, record };
};

// Publish one file at `target` so a reader never sees a partial document. The
// content is written and flushed to a temporary name first, then linked into
// place; `link` refuses an existing target, which is what makes the first writer
// win without a lock.
const publishOnce = (target, content) => {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const temporary = `${target}.${process.pid}.${Date.now()}.tmp`;
  try {
    const handle = fs.openSync(temporary, "wx");
    try {
      fs.writeFileSync(handle, content, "utf8");
      fs.fsyncSync(handle);
    } finally {
      fs.closeSync(handle);
    }
    try {
      fs.linkSync(temporary, target);
      return { published: true };
    } catch (error) {
      if (error?.code === "EEXIST") return { published: false };
      // A filesystem without hard links cannot offer this guarantee; report it
      // rather than falling back to a non-atomic write.
      return { error: String(error?.code ?? error?.message ?? error) };
    }
  } catch (error) {
    return { error: String(error?.code ?? error?.message ?? error) };
  } finally {
    try {
      fs.rmSync(temporary, { force: true });
    } catch {
      // A leftover temporary file carries no fact and readers ignore it.
    }
  }
};

// Write the fact once. An existing fact is left alone: the first receipt is the
// original, and a later one — a steer, a reopen, a compaction, a restart — is a
// new message, not a new origin. Returns `{ written }` or a refusal.
export const captureOriginal = (root, sessionID, text, { at = Date.now(), messageID = null } = {}) => {
  if (typeof root !== "string" || root.length === 0) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.MISSING, detail: "no workspace root" };
  }
  if (typeof sessionID !== "string" || sessionID.length === 0) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.MISSING, detail: "no session id" };
  }
  if (typeof text !== "string" || text.trim().length === 0) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.MISSING, detail: "the receipt has no text" };
  }
  // A session marked unrecorded never captures: its next prompt is not its first.
  if (readJson(unrecordedFile(root, sessionID)).absent !== true) {
    return {
      ok: false,
      refusal: ORIGINAL_REFUSALS.MISSING,
      detail: "the session started before the original was recorded",
    };
  }
  const existing = readOriginal(root, sessionID);
  if (existing.ok === true) return { ok: true, written: false, record: existing.record };
  if (existing.refusal === ORIGINAL_REFUSALS.UNREADABLE) {
    // Never overwrite a fact that exists but cannot be read: the operator's
    // words may be in it, and a rewrite would destroy the only copy.
    return existing;
  }
  const record = {
    v: ORIGINAL_RECORD_VERSION,
    sessionID,
    text,
    at,
    messageID: typeof messageID === "string" && messageID.length > 0 ? messageID : null,
  };
  const published = publishOnce(originalFile(root, sessionID), `${JSON.stringify(record, null, 2)}\n`);
  if (published.error !== undefined) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.UNREADABLE, detail: published.error };
  }
  if (published.published === false) {
    // Another writer published first; its fact is the original.
    const raced = readOriginal(root, sessionID);
    return raced.ok === true ? { ok: true, written: false, record: raced.record } : raced;
  }
  return { ok: true, written: true, record };
};

// Mark a session that predates this fact. Idempotent, and it never marks a
// session that already has an original.
export const markUnrecorded = (root, sessionID) => {
  if (typeof root !== "string" || root.length === 0) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.MISSING, detail: "no workspace root" };
  }
  if (typeof sessionID !== "string" || sessionID.length === 0) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.MISSING, detail: "no session id" };
  }
  if (readOriginal(root, sessionID).ok === true) return { ok: true, marked: false };
  const published = publishOnce(unrecordedFile(root, sessionID), `${JSON.stringify({ sessionID })}\n`);
  if (published.error !== undefined) {
    return { ok: false, refusal: ORIGINAL_REFUSALS.UNREADABLE, detail: published.error };
  }
  return { ok: true, marked: published.published === true };
};

// The text, or a refusal. This is the only reader a relay or a successor uses.
export const requireOriginal = (root, sessionID) => {
  const read = readOriginal(root, sessionID);
  if (read.ok === true) return { ok: true, text: read.record.text, record: read.record };
  return read;
};

// Whether a receipt may capture the original. Every input is a fact about the
// call, never about its words:
//
//   `parentID`   a string for a child, `null` for a proven top-level session,
//                `undefined` when the parent is not known.
//   `briefID`    the deterministic id the relay record wrote for its brief, or
//                null. A receipt carrying it is the adapter's own delivery.
//   `delivery`   the host's delivery mode. The adapter delivers its briefs as
//                `queue`; an operator's own prompt arrives as `steer`.
export const receiptVerdict = ({ text, parentID, briefID = null, delivery = null }) => {
  if (typeof text !== "string" || text.trim().length === 0) return { capture: false, reason: "NO_TEXT" };
  if (typeof briefID === "string" && briefID.length > 0) return { capture: false, reason: "OWN_DELIVERY" };
  if (delivery === "queue") return { capture: false, reason: "OWN_DELIVERY" };
  if (typeof parentID === "string" && parentID.length > 0) return { capture: false, reason: "CHILD_SESSION" };
  if (parentID === null) return { capture: true, reason: "TOP_LEVEL" };
  return { capture: false, reason: "PARENT_UNKNOWN" };
};
