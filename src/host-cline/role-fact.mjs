// The identity of a session this adapter starts (根 AGENTS.md §2.2, §6, §7.2).
//
// Cline gives a plugin no role switch, and its plugin setup context carries the
// session id and nothing else (`AgentExtensionSessionContext` = `{ sessionId }`).
// The host does keep `sessionMetadata`, but the plugin loader never hands it to
// the plugin: `resolveAndLoadAgentPlugins` and the sandbox loader `Ls` both drop
// it, and the plugin's own `getSession` cannot see a session that has not been
// registered yet — the plugin is set up during `kO`, before
// `startResolvedSession` registers the session and before any turn runs. So a
// sandboxed plugin asks the host and gets nothing, and a Plan session used to
// read as a Manager and refuse its own `js-plan` face.
//
// The adapter therefore decides BOTH halves of the identity before the host is
// asked to start anything, and carries them where the plugin can always read
// them:
//
//   - the session id, passed as `config.sessionId`. The host uses a caller's id
//     verbatim (`startSession`: `e.config.sessionId?.trim() || createSessionId()`;
//     hub `session.create`: `sessionConfig.sessionId || createSessionId()`), and
//     hands it to the plugin's setup context. This is the atomicity: the plugin
//     knows the id before its own setup runs, so a fact keyed by that id is
//     there on the very first hook;
//   - the role (and the Plan's draft), written to `.Herdchestra/<id>.role.json`
//     before the start. A session whose file cannot be written is not started,
//     because a started session that cannot be told its role would run as the
//     default and refuse its own face.
//
// The file is read once, in setup. It never overrides a role that is not one of
// ours: an unreadable, malformed or unmarkable file reads as absent, and the
// caller falls back to the other sources (`identity.mjs`).

import { randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

import { ROLES } from "../compose/program/surface.mjs";
import { safeName } from "./facts.mjs";

export const ROLE_FACT_DIR = ".Herdchestra";

// The roles this adapter may record. A file naming anything else is ignored
// rather than trusted: a wrong role is worse than no role.
const MARKABLE = new Set([ROLES.MANAGER, ROLES.CODER, ROLES.DEVOPS, ROLES.PLAN]);

// A session id this adapter can address before the host mints one: the role
// (`plan` / `manager`), the caller's name, and a unique suffix. File-safe by
// construction, and unique without asking the host anything.
//
// The suffix is `_` + 5 base-36 characters, which is the host's own
// `createSessionId` shape (`@cline/shared`: `${Date.now()}_${5 chars}`). Anything
// downstream that reads a session id expects that shape, so an id we mint
// ourselves must not be the one thing on disk that looks foreign.
const SESSION_SUFFIX = "0123456789abcdefghijklmnopqrstuvwxyz";

const suffix = () => {
  const bytes = randomBytes(5);
  let out = "";
  for (const byte of bytes) out += SESSION_SUFFIX[byte % SESSION_SUFFIX.length];
  return out;
};

export const sessionIdFor = (role, name) => `${role}-${safeName(name)}_${Date.now()}_${suffix()}`;

export const roleFactPath = (root, sessionId) => path.join(root, ROLE_FACT_DIR, `${safeName(sessionId)}.role.json`);

// Write the identity of one session before it is started. `sessionId` is the id
// the start request carries. Returns `{ ok: true, file }` or
// `{ ok: false, reason, detail }`; the caller reports a failure and starts
// nothing, because a session nobody can identify is worse than no session.
export const writeRoleFact = ({ root, sessionId, role, draft = null }) => {
  if (typeof sessionId !== "string" || sessionId.length === 0) {
    return { ok: false, reason: "SESSION_ID_MISSING", detail: null };
  }
  if (!MARKABLE.has(role)) return { ok: false, reason: "UNKNOWN_ROLE", detail: String(role) };
  const file = roleFactPath(root, sessionId);
  try {
    mkdirSync(path.dirname(file), { recursive: true });
    writeFileSync(
      file,
      `${JSON.stringify({
        v: 1,
        sessionId,
        role,
        ...(typeof draft === "string" && draft.length > 0 ? { draft } : {}),
      })}\n`,
      "utf8",
    );
    return { ok: true, file };
  } catch (error) {
    return { ok: false, reason: "ROLE_FACT_UNWRITABLE", detail: String(error?.message ?? error) };
  }
};

// The identity recorded for one session, or `null`. Never throws: an unreadable
// or malformed file is the same as an absent one, because the caller has other
// sources and a crash here would take down a hook.
export const readRoleFact = ({ root, sessionId }) => {
  if (typeof root !== "string" || root.length === 0) return null;
  if (typeof sessionId !== "string" || sessionId.length === 0) return null;
  let parsed;
  try {
    parsed = JSON.parse(readFileSync(roleFactPath(root, sessionId), "utf8"));
  } catch {
    return null;
  }
  if (parsed === null || typeof parsed !== "object") return null;
  if (parsed.v !== 1) return null;
  if (!MARKABLE.has(parsed.role)) return null;
  // A file that names another session is not this session's identity.
  if (typeof parsed.sessionId === "string" && parsed.sessionId !== sessionId) return null;
  return {
    role: parsed.role,
    ...(typeof parsed.draft === "string" && parsed.draft.length > 0 ? { draft: parsed.draft } : {}),
  };
};
