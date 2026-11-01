// Plan → Manager hand-off (根 AGENTS.md §6.1).
//
// An empty `review([])` at `待执行` (the last `PLAN_MILESTONES` entry,
// `core/review.mjs`) is the only way a Manager starts. What the
// Manager receives is the saved draft bytes at the canonical path — never the
// milestone name, never the Plan's conversation, and never a review note (a
// review carries no text). This module is that step, with the two things the
// host itself does not do for us:
//
//   - a Manager session that started but never took the draft is stopped again,
//     so a refused hand-off leaves no half-open Manager behind;
//   - the receipt is the host's own answer. The Plan's ledger moves only when
//     this is `ok: true`; the caller persists that, not this function.
//
// The session factory is injected (`ManagerSessions` in `sessions.mjs`), so this
// step is testable without a live host and a host that cannot start a session
// says so instead of pretending.

export const planHandoff = async ({ startManager, stopManager = null, draft }) => {
  if (typeof draft !== "string" || draft.length === 0) {
    return { ok: false, reason: "DRAFT_MISSING" };
  }
  if (typeof startManager !== "function") {
    return { ok: false, reason: "HOST_HAS_NO_SESSION_CREATE" };
  }
  let started;
  try {
    started = await startManager(draft);
  } catch (error) {
    return { ok: false, reason: "MANAGER_SESSION_FAILED", message: String(error?.message ?? error) };
  }
  if (started === null || typeof started !== "object") {
    return { ok: false, reason: "MANAGER_SESSION_FAILED", message: "the host answered no session" };
  }
  if (started.ok !== true) {
    const session = started.session ?? null;
    let discarded = null;
    if (session !== null && typeof stopManager === "function") {
      try {
        discarded = await stopManager(session);
      } catch (error) {
        discarded = { ok: false, reason: String(error?.message ?? error) };
      }
    }
    return { ok: false, reason: started.reason ?? "MANAGER_SESSION_FAILED", session, discarded, message: started.message };
  }
  if (typeof started.sessionId !== "string" || started.sessionId.length === 0) {
    return { ok: false, reason: "MANAGER_SESSION_FAILED", message: "the host returned no session id" };
  }
  // Only a non-empty issue list is carried: the receipt says what happened, and
  // an empty array is not a fact.
  const pluginIssues = Array.isArray(started.pluginIssues) ? started.pluginIssues : [];
  return {
    ok: true,
    sessionId: started.sessionId,
    ...(pluginIssues.length > 0 ? { pluginIssues } : {}),
  };
};
