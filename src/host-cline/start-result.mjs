// Reading a `startSession` answer (根 AGENTS.md §7.1).
//
// The host can fail a start in three shapes, and only one of them is an
// exception:
//
//   { ok: false, name: "ZodError", issues: [...] }   the session record could not
//                                                    be written (schema refusal)
//   { ok: false, error: "..." }                      the host's own failure code
//   { sessionId, manifest, ... }                     a session exists
//
// The adapter used to accept anything without a `sessionId` as "no session" and
// anything with one as success. That hid the first shape completely: a start
// refused before any session record existed still produced `ok: true` with an
// invented id, and the caller went on as if a turn had run. `refusalFromStart`
// reads the answer the way the host writes it, so a refusal is reported as one.

const issueText = (issue) => {
  if (issue === null || typeof issue !== "object") return String(issue);
  const path = Array.isArray(issue.path) ? issue.path.join(".") : String(issue.path ?? "");
  const message = typeof issue.message === "string" ? issue.message : "";
  return path.length > 0 ? `${path}: ${message}` : message;
};

// `null` when the answer describes a started session; otherwise the refusal to
// put in the receipt: `{ reason, message, issues? }`.
export const refusalFromStart = (started) => {
  if (started === null || started === undefined) {
    return { reason: "START_RETURNED_NOTHING", message: "the host returned no answer" };
  }
  if (typeof started !== "object") {
    return { reason: "START_RETURNED_NON_OBJECT", message: String(started) };
  }
  const sessionId = typeof started.sessionId === "string" ? started.sessionId : "";
  if (started.ok === false) {
    const issues = Array.isArray(started.issues) ? started.issues.map(issueText) : [];
    return {
      reason: "START_REFUSED",
      message:
        typeof started.error === "string"
          ? started.error
          : issues.length > 0
            ? `${started.name ?? "refusal"}: ${issues.join("; ")}`
            : String(started.name ?? "the host refused the start"),
      ...(issues.length > 0 ? { issues } : {}),
    };
  }
  if (sessionId.length === 0) {
    return { reason: "START_RETURNED_NO_SESSION", message: "the host returned no session id" };
  }
  return null;
};
