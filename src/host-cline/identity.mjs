// Which role a session plays (根 AGENTS.md §6, §7.2).
//
// Cline gives a plugin no "you are the Manager / the Plan" switch: `setup(api,
// ctx)` carries the session id, the client, the workspace, the logger and the
// telemetry, and nothing about the role. The role is therefore a fact of the
// session, and the two ways a session comes to exist decide it:
//
//   1. a session this adapter started — `ManagerSessions.start` writes the role
//      into the session metadata (`ROLE_METADATA_KEY`) and the host stores it
//      and hands it back through `getSession`. That is the only channel from a
//      starter to the plugin of the session it started;
//   2. a session an operator started — no metadata, so the role is the
//      operator's: Manager.
//
// A host that cannot answer `getSession` (no host module reachable) leaves a
// session started by this adapter looking like a Manager. That is the honest
// reading of a missing fact, and the `source` says which reading was used.

import { ROLES } from "../compose/program/surface.mjs";

// The key a started session carries its role under. The host stores session
// metadata as given and hands it back through `getSession`.
export const ROLE_METADATA_KEY = "herdchestra.role";

const ROLES_THAT_MAY_BE_MARKED = new Set([ROLES.MANAGER, ROLES.CODER, ROLES.DEVOPS, ROLES.PLAN]);

// The session-record reader of a host handle, or null when it has none.
//
// `ManagerSessions.host()` answers `{ kind, handle }`; a caller may also hand
// in the host itself. Both are unwrapped here so the caller never has to know
// which it holds. The two method names are one fact on this host: `getSession`
// (`runtime-host.ts:391`) and `get` (`ClineCore.ts:431`, the documented alias).
// A handle that exposes only `get` is still readable — reading only
// `getSession` is what made a live Plan session look like a Manager.
const pickReader = (host) => {
  const candidates = [host, host?.handle];
  for (const candidate of candidates) {
    if (candidate === null || candidate === undefined) continue;
    if (typeof candidate.getSession === "function") return (id) => candidate.getSession(id);
    if (typeof candidate.get === "function") return (id) => candidate.get(id);
  }
  return null;
};

// The role a session plays. `host` is the handle `ManagerSessions` opened, or
// null; `sessionId` is the id the plugin was set up for.
//
// The session's metadata is the first source, because a host that hands it to
// `setup` (a future host, or a test) has already answered. Otherwise the record
// is read through `getSession`.
//
// A session the host did not mark reads as the caller's own (Manager), and the
// `source` says so (`unmarked`, `default`, `noSessionReader`, `missingSession`).
// The ONE case that is not a reading is `unproven`: the caller asked about a
// specific session and every source failed. The gate refuses that session's
// faces instead of granting them on a default (`hooks.mjs`).
export const roleOf = async ({ host = null, sessionId = null, metadata = null, fallback = ROLES.MANAGER } = {}) => {
  const marked = metadata?.[ROLE_METADATA_KEY];
  if (ROLES_THAT_MAY_BE_MARKED.has(marked)) return { role: marked, source: "sessionMetadata", metadata };
  if (host === null || typeof sessionId !== "string" || sessionId.length === 0) {
    // Metadata was handed over and did not name a role: the caller's role is the
    // reading, and `unmarked` says the session said nothing, not that nobody
    // asked. With nothing handed over and no session to ask about, the role is
    // unproven.
    if (metadata !== null) return { role: fallback, source: "unmarked", metadata };
    if (typeof sessionId !== "string" || sessionId.length === 0) return { role: fallback, source: "default", metadata };
    return { role: fallback, source: "unproven", metadata: null };
  }
  let record;
  // The host's record reader. There are two names for the same fact on this
  // host, and which one a handle carries depends on which entry opened it:
  //
  //   - `RuntimeHost.getSession(id)` — `runtime-host.ts:391`, positional;
  //   - `ClineCore.get(id)` — `ClineCore.ts:431`, a documented alias of the
  //     same call, and the ONLY one some builds expose.
  //
  // Reading only `getSession` made the live host answer "no reader", which
  // silently fell back to Manager and refused `js-plan` for a real Plan
  // session. So both names are accepted, on the handle and on the host itself,
  // and a reader that takes `{ sessionId }` is tried after the positional one.
  const reader = pickReader(host);
  if (reader === null) {
    return { role: fallback, source: "unproven", metadata: null };
  }
  try {
    record = await reader(sessionId);
    if (record === undefined || record === null) record = await reader({ sessionId });
  } catch {
    // The positional call threw: the reader may be the object-shaped one.
    try {
      record = await reader({ sessionId });
    } catch {
      return { role: fallback, source: "unproven", metadata: null };
    }
  }
  const role = record?.metadata?.[ROLE_METADATA_KEY];
  if (ROLES_THAT_MAY_BE_MARKED.has(role)) return { role, source: "sessionRecord", metadata: record.metadata };
  // No record at all: the host cannot name this session, so its role is not a
  // reading. A record that exists without a role IS the operator's session.
  if (record === undefined || record === null) return { role: fallback, source: "unproven", metadata: null };
  return {
    role: fallback,
    source: "unmarked",
    metadata: record.metadata ?? null,
  };
};
