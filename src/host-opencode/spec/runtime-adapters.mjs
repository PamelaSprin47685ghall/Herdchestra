// Small runtime pieces the composition layer expects, bound to this host.
//
// `ProgramSurface` wants a clock, a command-view map and a root. None of them
// is host-specific except the root: the host session carries its directory in
// `session.location.directory`, and an empty path in a program resolves to it
// (§3.2). Everything else is the core default.

import { systemClock } from "../../core/clock.mjs";

// One map per plugin activation. Command views are keyed by program name and
// exist only while a command runs; the map is cleared by the member itself.
export const makeViews = () => new Map();

export const clock = systemClock;

// The session root: absolute, no trailing slash. A relative path from the model
// joins onto it inside core's `FileMembers`.
export const sessionRoot = (session) => {
  const directory = session?.location?.directory;
  if (typeof directory !== "string" || directory.length === 0) {
    throw new TypeError("a session root needs session.location.directory");
  }
  return directory.replace(/\/+$/, "") || "/";
};

// `remainingWaitMs` of a receipt is the time left of the request's deadline
// (§3.3: the deadline is fixed at admission from the intent's byte count). The
// adapter records the admission instant and subtracts.
export const remainingWait = ({ deadlineMs, startedAt, now = Date.now }) =>
  Math.max(0, Math.round(startedAt + deadlineMs - now()));
