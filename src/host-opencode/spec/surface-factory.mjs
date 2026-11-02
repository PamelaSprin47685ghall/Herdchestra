// One program surface per call (§3.1, §7.1).
//
// The tool's `execute` receives the program source and the role; this module
// builds the `ProgramSurface` compose defines. The member list is compose's
// `SURFACE_MEMBERS`; this file never keeps a second copy, so a member the role
// lacks simply does not exist.
//
// Ports by role, taken from `src/compose/ports.mjs` `PORT_METHODS` (compose
// rejects any name it does not know, so the list below is not a preference):
//
//   manager  fork, join, relay, scope            fork/join/review/ask/negotiate/askPreviousScope
//   coder    fork, join, command, fission, scope fork/join/call/fission/negotiate/askPreviousScope
//   devops   fork, join, command, fission, scope fork/join/call/fission/negotiate/askPreviousScope
//   plan     relay, devops, draft                review/ask/devops
//
// `scope` is the two exempt snapshot queries of §6.2 (`negotiate`,
// `askPreviousScope`). They are synchronous, so the port is `ask(member)` over
// the session's own Scope book — never a promise.
//
// compose reads fork and join through `#ports.fork.fork`/`.join`, and its
// `PORT_METHODS` table names the shapes `fork` and `join`; the surface object
// therefore carries both spellings, pointing at the same functions.
//
// `control` is not a compose port: compose's surface calls
// `this.ports.control.unpark({ controllerId, name, value })` directly, so the
// object is passed through untouched and `requirePorts` never sees it.
//
// `ask` and `park` are parked by the surface itself and need no port.

import { ProgramSurface, ROLES } from "../../compose/program/surface.mjs";
import { requirePorts } from "../../compose/ports.mjs";
import { CoreError, CODES } from "../../core/errors.mjs";
import { memberAvailable, refusalText } from "./member-availability.mjs";
import { primitivesForSurface } from "./primitives.mjs";
import { refusal, REFUSALS } from "./refusals.mjs";
import { clock, sessionRoot } from "./runtime-adapters.mjs";

const PORTS_FOR = Object.freeze({
  manager: ["fork", "join", "relay", "scope"],
  // A worker forks into its own roster (§3.1, §4.1); `fission` is a different
  // member and keeps its own admission (§4.2).
  //
  // The fission port is the ADAPTER's own, not compose's `PORT_METHODS` shape:
  // `activation.portsFor` hands over the caller's `fissionFor(sessionID)` object,
  // whose steps are the ones this host really performs (`buildLane` builds a
  // lane without starting it; `discardGroup` undoes a group that failed to
  // start). Checking it against `PORT_METHODS.fission` refused every worker
  // surface with `MISSING_PORT` after compose gained the two-step build.
  coder: ["fork", "join", "command", "scope"],
  devops: ["fork", "join", "command", "scope"],
  // `draft` is the hand-off's ONLY source (根 §2.2): compose's `review` reads it
  // for a Plan at 待执行. A Plan surface without it could not hand a saved draft
  // to a Manager, so `requirePorts` refuses the build — a wiring error stated
  // where the surface is assembled, not a `DRAFT_MISSING` the model must read as
  // one.
  plan: ["relay", "devops", "draft"],
});

const ROLE_CONST = Object.freeze({
  manager: ROLES.MANAGER,
  coder: ROLES.CODER,
  devops: ROLES.DEVOPS,
  plan: ROLES.PLAN,
});

// `ports` carries every port the adapter supplies; only the ones this role
// names are checked by compose. `control` travels beside them for `unpark`, and
// `state` carries the session facts a Plan's draft and a review need.
export const makeSurface = ({ role, name, program, session, ports, source = null, views, guard, review = false }) => {
  const roleConst = ROLE_CONST[role];
  if (roleConst === undefined) throw new TypeError(`unknown role: ${role}`);
  const needed = PORTS_FOR[role];
  requirePorts(ports, needed);
  // A Plan writes one draft, and `atomicWriteFile` creates its temp file in the
  // draft's own directory. If that directory could not be prepared, every write
  // fails as `ENOENT` about a file the Plan never named — and Plan has no
  // `mkdir` to repair it. The failure is therefore stated here, where the
  // session starts and the reason is still its own, instead of surfacing later
  // as a mystery write error.
  if (roleConst === ROLES.PLAN) {
    const dirFailure = typeof ports.draft?.dirFailure === "function" ? ports.draft.dirFailure() : null;
    if (dirFailure !== null && dirFailure !== undefined) {
      return {
        surface: null,
        members: Object.freeze({}),
        primitives: Object.freeze([]),
        refusal: refusal(REFUSALS.DRAFT_DIR_UNWRITABLE, {
          directory: dirFailure.directory ?? null,
          detail: dirFailure.detail ?? null,
        }),
      };
    }
  }
  const picked = Object.fromEntries(needed.map((port) => [port, ports[port]]));
  // The fission steps are checked by name, not by `PORT_METHODS`: see above.
  if (ports.fission !== null && typeof ports.fission === "object") {
    for (const step of ["fission", "ownerView", "buildLane", "persistGroup", "startLane", "replacePresent", "abortLanes"]) {
      if (typeof ports.fission[step] !== "function") {
        throw new CoreError(CODES.MISSING_PORT ?? "MISSING_PORT", `port fission.${step} is required`, {
          port: "fission",
          method: step,
        });
      }
    }
  }
  picked.fission = ports.fission;
  // compose reads `ports.fork.fork` / `ports.fork.join` (its `PORT_METHODS`
  // names the shapes `fork` and `join`); `worker` is the adapter's own spelling
  // of the same object and travels beside it, unread by compose.
  picked.worker = ports.worker;
  picked.fork = { fork: ports.worker.fork, join: ports.worker.join };
  picked.join = { join: ports.worker.join };
  picked.control = ports.control;
  const surface = new ProgramSurface({
    name,
    role: roleConst,
    program,
    clock,
    source,
    ports: picked,
    root: sessionRoot(session),
    guard,
    views,
    // One ledger per session and side (§5): a review must land in the same
    // ledger the next call reads, not in a fresh one per program.
    ledger: ports.state?.ledgerFor(roleConst === ROLES.PLAN ? "plan" : "manager") ?? null,
    // The session's evaluation window (§2.3): a review accepted by one program
    // keeps `js-review` shut for the next one, and a `js-review` call whose
    // window is shut is refused by the surface itself.
    window: ports.state?.windowFor(roleConst === ROLES.PLAN ? "plan" : "manager") ?? null,
    // 根 §2.3 keeps Plan's draft read-only until the tenure's first review, so the
    // review count must outlive the program that wrote it. Without a store the
    // count lives only in memory, and a restart would let the draft be written
    // again before any review. The row is written by `SessionState`.
    ledgerStore: ports.state ? { save: () => ports.state.saveLedger(roleConst === ROLES.PLAN ? "plan" : "manager") } : null,
  });
  // `js-review` runs the surface's separate read-only face (§2.3). Everything
  // else runs the role's own member list. A shut window makes `reviewSurface()`
  // throw `REVIEW_WINDOW_CLOSED`; that refusal reaches the caller unchanged, so
  // the tool answers the reason instead of a TypeError over a missing member.
  //
  // The round is opened at the REQUEST boundary (`session.hook("context")`,
  // `context-hook.mjs`), not here: a tool call is not a request, and a
  // `js-review` call that opened its own round would reopen the evaluation the
  // round's first accepted review just shut (§2.3). `openRound: false` says
  // exactly that — this call is not a new request.
  const members = review ? surface.reviewSurface({ openRound: false }) : surface.surface();
  // A primitive is a capability the body calls by its bare name (§3.1). Compose
  // owns most of them; this host binds Plan's `devops` itself (根 §1.1 item 3).
  // A compose surface that still lists a primitive among its members supplies
  // the handler, and the compile step takes it out of `this`; a surface that no
  // longer lists it gets the handler here, so both shapes compile the same way.
  const primitives = primitivesForSurface(surface.surfaceName);
  for (const name of primitives) {
    if (members[name] === undefined) members[name] = surface.handler(name, surface.core());
  }
  return Object.freeze({ surface, members: Object.freeze(members), primitives: Object.freeze([...primitives]) });
};

// A member the role's surface does not carry is refused by name, not silently
// absent: the model must learn why (§7.1 layer two).
export const assertMember = (role, member) => {
  if (!memberAvailable(role, member)) {
    throw new CoreError(CODES.INVALID_ARGUMENT, refusalText(role, member), { member, role });
  }
  return member;
};

export { PORTS_FOR };
