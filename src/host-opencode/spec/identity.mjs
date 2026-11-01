// The identity of sessions this adapter created (根 §2.4 第 3 步, §7.1).
//
// The operator's original input is captured only for a receipt the adapter can
// prove is genuine (`original-input.mjs` `receiptVerdict`). Two facts decide
// that, and both must be known BEFORE the session's first prompt:
//
//   - the session's parent. A `fork`ed worker's first prompt is its brief and a
//     lane's first prompt is its lane input; neither is the operator's words.
//   - whether the receipt is the adapter's own delivery. The relay brief and a
//     Plan's draft are delivered with `delivery: "queue"`, and the host event
//     carries that mode but never the brief id (`live5`: `briefID` is absent).
//
// Waiting for `session.created` is too late: the host may create a session and
// prompt it in the same turn, and a child's `session.created` can arrive after
// its first prompt. So every session this adapter creates is registered here,
// durably, before the prompt call. The file survives a restart, and a session
// the adapter did not create has no entry and therefore captures nothing.
//
// A registration is a fact, not a guess: `parentID: null` means "this adapter
// created a top-level session", and a string means "this adapter created a child
// of that session". A session that is absent is unknown, which is not the same
// as top-level.

import fs from "node:fs";
import path from "node:path";

export const IDENTITY_RECORD_VERSION = 1;

export const IDENTITY_STATE_DIR = ".Herdchestra";

const safeName = (value) =>
  String(value).replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120) || "session";

// One file per session. The name is the session id itself: a registration is
// read on the prompt path of that same id, and a sanitized collision between two
// ids would let one session's identity answer for another, so the id is escaped
// rather than truncated.
export const identityFile = (root, sessionID) =>
  path.join(root, IDENTITY_STATE_DIR, `${safeName(sessionID)}.identity.json`);

const readIdentity = (file) => {
  let raw;
  try {
    raw = fs.readFileSync(file, "utf8");
  } catch {
    return null;
  }
  try {
    const record = JSON.parse(raw);
    if (record === null || typeof record !== "object") return null;
    if (record.v !== IDENTITY_RECORD_VERSION) return null;
    return record;
  } catch {
    // A registration that cannot be read is not a registration: the caller then
    // falls back to the host's own `session.get`, which is the safe direction.
    return null;
  }
};

// Record one adapter-created session. Written before the first prompt, and
// replaced only by a registration of the same session with the same parent (a
// retry); a different parent is a contradiction and is refused.
export const registerIdentity = (
  root,
  sessionID,
  { parentID = null, role = null, origin = null } = {},
) => {
  if (typeof root !== "string" || root.length === 0) {
    return { ok: false, reason: "NO_WORKSPACE_ROOT" };
  }
  if (typeof sessionID !== "string" || sessionID.length === 0) {
    return { ok: false, reason: "NO_SESSION" };
  }
  const parent = typeof parentID === "string" && parentID.length > 0 ? parentID : null;
  const file = identityFile(root, sessionID);
  const existing = readIdentity(file);
  if (existing !== null && existing.parentID !== parent) {
    return { ok: false, reason: "IDENTITY_CONFLICT", file };
  }
  const record = {
    v: IDENTITY_RECORD_VERSION,
    sessionID,
    parentID: parent,
    role: typeof role === "string" && role.length > 0 ? role : null,
    origin: typeof origin === "string" && origin.length > 0 ? origin : null,
    at: Date.now(),
  };
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, `${JSON.stringify(record, null, 2)}\n`, "utf8");
    return { ok: true, written: existing === null, record };
  } catch (error) {
    return { ok: false, reason: "IDENTITY_WRITE_FAILED", detail: String(error?.code ?? error?.message ?? error) };
  }
};

// The registered identity of one session, or `null` when this adapter never
// created it.
export const readIdentityOf = (root, sessionID) => {
  if (typeof root !== "string" || root.length === 0) return null;
  if (typeof sessionID !== "string" || sessionID.length === 0) return null;
  const record = readIdentity(identityFile(root, sessionID));
  if (record === null) return null;
  if (record.sessionID !== sessionID) return null;
  return record;
};

// The activation's table is the in-process half of the same fact: a registration
// made moments ago must be visible to the prompt that follows it, even if the
// file write is slow to be re-read.
export const registerInTable = (activation, sessionID, fact) => {
  if (activation === null || typeof activation !== "object") return;
  if (!(activation.identities instanceof Map)) activation.identities = new Map();
  activation.identities.set(sessionID, fact);
};
