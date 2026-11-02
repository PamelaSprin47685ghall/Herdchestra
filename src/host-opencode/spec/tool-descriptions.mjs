// Model-facing tool descriptions (§3.1, §4.1, §8).
//
// The member list in each description is compose's `SURFACE_MEMBERS`, rendered
// from it at module load, so a description can never promise a member the
// surface lacks. Prose below says what the members mean, never which exist.
//
// One spelling per capability (§3.1): a member is written `this.xxx`, and the
// top-level primitives are written bare. The list is therefore rendered with
// the spelling, not as bare names. Plan's `devops` is a primitive of this host
// (根 §1.1 item 3): it is `devops(verboseIntent)`, and `this.devops` does not
// exist.
//
// The Scope annotation is described once, in the system prompt. Repeating the
// whole language in every tool description spends the model's context on the
// same paragraph six times, so this file points at the schema instead.

import { membersForSurface, primitivesForSurface } from "./primitives.mjs";

const spelling = (name, primitives) => (primitives.includes(name) ? `\`${name}\`` : `\`this.${name}\``);

const memberList = (surface) => {
  const primitives = primitivesForSurface(surface);
  return [...new Set([...membersForSurface(surface), ...primitives])]
    .map((member) => spelling(member, primitives))
    .join(", ");
};

const primitiveList = (surface) => primitivesForSurface(surface);

const SPELLING = (surface) => {
  const primitives = primitiveList(surface);
  const bare = primitives.length === 0 ? "there are no bare primitives on this surface" : `${primitives.map((name) => `\`${name}\``).join(", ")} as bare functions`;
  return `Call a member as \`this.xxx\`; call ${bare}. Each capability has one spelling: there is no bare \`cat\`, and no \`this.fork\`.`;
};

export const TOOL_DESCRIPTIONS = Object.freeze({
  "js-manager": [
    "Your programming face. One program per call; use `return` for the value you want back.",
    `Members: ${memberList("js-manager")}.`,
    "You never read, write, move or delete files and you never run a command: there are no such members. Facts about the tree come from workers; the only read channel is the separate js-review tool during evaluation.",
    "`fork` returns at once and says `steered` or `started`; results arrive on their own. `join` waits for the first worker to come back.",
    SPELLING("js-manager"),
  ].join("\n"),
  "js-worker": [
    "Your programming face. One program per call; use `return` for the value you want back.",
    `Members: ${memberList("js-worker")}.`,
    "`this.write` is an atomic overwrite: readers see old or new bytes, never a mix. `this.where` parks on zero or several matches and returns the park payload. `this.call` runs one shell command after this program's earlier operations settle.",
    SPELLING("js-worker"),
    "`this.fission` splits your own identity into parallel lanes. `fork(name, verboseIntent)` starts a separate worker in your own roster and returns at once; the verboseIntent must state its identity once (`role: coder` or `role: devops`). `join` waits for the first of YOUR workers to report.",
  ].join("\n"),
  "js-review": [
    "Read-only evaluation face, open only while a review is being prepared.",
    `Members: ${memberList("js-review")}.`,
    "There is no write, delete, move, run or delegate member. Once the review is accepted this tool closes for the round.",
    SPELLING("js-review"),
  ].join("\n"),
  plan: [
    "Your planning face. One program per call; use `return` for the value you want back.",
    `Members: ${memberList("plan")}.`,
    "Your only output is the draft at `plans/<name>.md` under the workspace root, and it never lives under `.Herdchestra`. Apart from that file and operator attachments you are read-only: `this.cat` and `this.write` name the draft, and nothing else.",
    "`devops(verboseIntent)` is a bare top-level function, not `this.devops`: it is synchronous (join semantics), waits for ONE dedicated DevOps and returns its whole report unchanged. A second investigation while one runs is refused.",
    "`this.ask` parks the program until the operator answers. `review` records obligations; an empty review at the last milestone hands the saved draft to a Manager session.",
    SPELLING("plan"),
  ].join("\n"),
  fork: [
    "Start a worker with its own session, or steer one that is already live.",
    "`verboseIntent` must stand alone: goal, constraints, interfaces, acceptance, what to report. A worker never sees this conversation.",
    "Returns at once with `{ name, steered, started }`: `steered: true` means the verboseIntent went to the live worker, `steered: false` means a new session started. Neither means a result.",
    "The identity is declared in the verboseIntent, once: `role: coder` (static work, no bash) or `role: devops` (real commands). There is no separate argument, and a verboseIntent that names neither or both is refused. Keep exactly one devops in flight.",
    "The model is chosen for you: the task model when the operator configured one, otherwise the parent's. There is no model argument.",
  ].join("\n"),
  join: [
    "Wait for the first worker that finishes. No argument: it answers with that one record.",
    "Use it only when nothing else can advance. Results also arrive by themselves.",
  ].join("\n"),
});
