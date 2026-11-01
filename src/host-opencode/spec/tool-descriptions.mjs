// Model-facing tool descriptions (§3.1, §4.1, §8).
//
// The member list in each description is compose's `SURFACE_MEMBERS`, rendered
// from it at module load, so a description can never promise a member the
// surface lacks. Prose below says what the members mean, never which exist.
//
// One spelling per capability (§3.1): a member is written `this.xxx`, and the
// top-level primitives (`fork`, `join`, `review`) are written bare. The list is
// therefore rendered with the spelling, not as bare names.

import { SURFACE_MEMBERS } from "../../compose/program/surface.mjs";
import { SCOPE_DESCRIPTION } from "./scope-schema.mjs";

const SCOPE_NOTE =
  `Every call carries a \`scope\` annotation. ${SCOPE_DESCRIPTION} A non-exempt call without one is refused before it runs.`;

// The primitives are the ones the body calls bare (§3.1); everything else is a
// member and is written `this.xxx`.
const PRIMITIVES = Object.freeze(["fork", "join", "review"]);

const memberList = (surface) =>
  SURFACE_MEMBERS[surface]
    .map((member) => (PRIMITIVES.includes(member) ? `\`${member}\`` : `\`this.${member}\``))
    .join(", ");

const SPELLING =
  "Call a member as `this.xxx`; call `fork`, `join` and `review` as bare functions. Each capability has one spelling: there is no bare `cat`, and no `this.fork`.";

export const TOOL_DESCRIPTIONS = Object.freeze({
  "js-manager": [
    "Your programming face. One program per call; use `return` for the value you want back.",
    `Members: ${memberList("js-manager")}.`,
    "You never read, write, move or delete files and you never run a command: there are no such members. Facts about the tree come from workers; the only read channel is the separate js-review tool during evaluation.",
    "`fork` returns at once and says `steered` or `started`; results arrive on their own. `join` waits for the first worker to come back.",
    SPELLING,
    SCOPE_NOTE,
  ].join("\n"),
  "js-worker": [
    "Your programming face. One program per call; use `return` for the value you want back.",
    `Members: ${memberList("js-worker")}.`,
    "`this.write` is an atomic overwrite: readers see old or new bytes, never a mix. `this.where` parks on zero or several matches and returns the park payload. `this.call` runs one shell command after this program's earlier operations settle.",
    SPELLING,
    "`this.fission` splits your own identity into parallel lanes. `fork(name, brief)` starts a separate worker in your own roster and returns at once; the brief must state its identity once (`role: coder` or `role: devops`). `join` waits for the first of YOUR workers to report.",
    SCOPE_NOTE,
  ].join("\n"),
  "js-review": [
    "Read-only evaluation face, open only while a review is being prepared.",
    `Members: ${memberList("js-review")}.`,
    "There is no write, delete, move, run or delegate member. Once the review is accepted this tool closes for the round.",
    SPELLING,
    SCOPE_NOTE,
  ].join("\n"),
  plan: [
    "Your planning face. One program per call; use `return` for the value you want back.",
    `Members: ${memberList("plan")}.`,
    "You write one draft at the path the task fixes, and nothing else. Repository facts come only from `this.devops(intent)`, which waits for one devops worker and returns its report whole. `this.ask` parks the program until the operator answers. `review` records obligations; an empty review at the last milestone hands the draft to a Manager session.",
    SPELLING,
    SCOPE_NOTE,
  ].join("\n"),
  fork: [
    "Start a worker with its own session, or steer one that is already live.",
    "`brief` must stand alone: goal, constraints, interfaces, acceptance, what to report. A worker never sees this conversation.",
    "Returns at once with `{ name, steered, started }`: `steered: true` means the brief went to the live worker, `steered: false` means a new session started. Neither means a result.",
    "The identity is declared in the brief, once: `role: coder` (static work, no bash) or `role: devops` (real commands). There is no separate argument, and a brief that names neither or both is refused. Keep exactly one devops in flight.",
    SCOPE_NOTE,
  ].join("\n"),
  join: [
    "Wait for the first worker that finishes. No argument: it answers with that one record.",
    "Use it only when nothing else can advance. Results also arrive by themselves.",
    SCOPE_NOTE,
  ].join("\n"),
});
