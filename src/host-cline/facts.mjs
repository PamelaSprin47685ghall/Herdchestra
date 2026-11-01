// Host facts shared by the Cline adapter (spec §7.2).
//
// Everything here is either read from the host context the plugin receives or
// derived from the adapter's own files. Nothing here touches a host module: the
// adapter resolves `@cline/core` at run time and reports a missing capability
// instead of importing it statically.

import path from "node:path";
import { fileURLToPath } from "node:url";

// The working directory the host session runs in. On `@cline/core` 0.2.0 the
// setup context is
//
//   `{ session: { sessionId }, client, user, workspaceInfo: { rootPath, hint },
//      cwd, emitEvent, onDispose }`
//
// and its `cwd` is the PLUGIN's directory (`.../Herdchestra`), not the session's.
// So `workspaceInfo.rootPath` comes first and `ctx.cwd` last; `process.cwd()` is
// the final resort, because a Hub plugin shares one process across sessions.
export const workspaceOf = (ctx) => {
  const candidates = [ctx?.workspaceInfo?.rootPath, ctx?.workspaceInfo?.cwd, ctx?.cwd, process.cwd()];
  for (const candidate of candidates) {
    if (typeof candidate === "string" && candidate.trim().length > 0) return candidate;
  }
  return process.cwd();
};

// The workspace root of the session: the directory the host started it in.
// `workspaceInfo.rootPath` is that root; a sandboxed plugin's `cwd` is the
// plugin's own directory, not the session's, so `cwd` comes last and the
// process directory is not a candidate at all — `null` says the host handed no
// workspace, and the caller decides what that means.
export const workspaceRootOf = (ctx) => {
  const candidates = [ctx?.workspaceInfo?.rootPath, ctx?.workspaceInfo?.cwd, ctx?.cwd];
  for (const candidate of candidates) {
    if (typeof candidate === "string" && candidate.trim().length > 0) return candidate;
  }
  return null;
};

// The durable session id of the plugin's session. `setup(api, ctx)` carries
// `session.sessionId`; a missing one still gets a stable, file-safe name.
export const sessionIdOf = (ctx) => {
  const session = ctx?.session?.sessionId;
  if (typeof session === "string" && session.trim().length > 0) return session;
  return "cline-session";
};

// The language ladder (spec §6.1): explicit environment, then host preference,
// then locale, then English (resolved by core).
export const languageLadder = (ctx = null, env = process.env) => ({
  explicit: env.HERDCHESTRA_LANGUAGE ?? null,
  host: ctx?.client?.language ?? env.HERDCHESTRA_HOST_LANGUAGE ?? null,
  locale: env.LC_ALL ?? env.LC_MESSAGES ?? env.LANG ?? null,
});

// The adapter lives in `.../src/host-cline`. That path is never the project: a
// session runs in its own workspace, and the adapter's location says nothing
// about it. It is reported as the last resort only because a `.Herdchestra/`
// directory needs a parent; every real host hands over a workspace.
export const adapterDir = () => path.dirname(fileURLToPath(import.meta.url));

export const repoRootFromAdapter = () => path.resolve(adapterDir(), "..", "..");

// The project root of a session: the operator's explicit root, else the
// session's workspace root. The adapter's own repository is NOT a fallback —
// a live Plan wrote its ledger and Scope journal into the adapter's repo because
// `process.cwd()` equalled the workspace and the old code fell through to the
// adapter directory.
export const projectRoot = (ctx = null) => {
  const explicit = process.env.HERDCHESTRA_PROJECT_ROOT;
  if (typeof explicit === "string" && explicit.trim().length > 0) return explicit;
  return workspaceRootOf(ctx) ?? workspaceOf(ctx);
};

// A file-safe form of a session id. Pair Hint and the state files use one name
// per session, and a host id may contain characters a path cannot carry.
export const safeName = (value) => String(value).replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120) || "session";
