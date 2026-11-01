// The role a worker runs as, read from its own brief (根 AGENTS.md §2.1,
// §4.1).
//
// §4.1 gives `fork` exactly two arguments: `fork(name, brief)`. There is no
// third argument to name the worker's identity, so the identity has to travel
// in the one place a worker receives whole: the brief. §2.1 states the rule this
// module enforces — "启动任何子代理时，都必须明确它是 `Coder` 还是 `DevOps`".
//
// "Explicit" is the whole point. A name is not a role (`devops` as a worker
// name says nothing about its rights), a missing declaration is not a quiet
// default, and a brief that names both identities does not pick one. All three
// are refused, because the identity decides whether a worker holds real command
// execution (§2.1) and a guessed role either hands that power to a coder or
// takes it from the single devops.
//
// What counts as explicit: one role keyword used as a WORD, outside any code
// span, in exactly one identity of `role`/`identity`/`as`. Text inside
// backticks, quotes, code fences or URLs is what the brief talks ABOUT, not
// what it declares — a brief that says "do not use `bash`; the devops worker
// owns commands" must not become a devops.

import { invalidArgument } from "../../core/errors.mjs";

// The two worker identities (§2.1). Manager and Plan are never forked (§4.1,
// §2.2), so they are not accepted here. The literals are the same strings
// `surface.mjs` publishes as `ROLES`; this module is imported BY that one, so it
// must not import it back.
export const WORKER_ROLES = Object.freeze(["coder", "devops"]);

const KEYWORD = /\b(coder|devops)\b/gi;

// One identity field and its value. `role: devops`, `role is coder`,
// `identity — Devops`, `as: CODER`. Everything up to the end of the sentence or
// line is the value, so a field that names two roles is caught as one field.
const FIELD = /\b(?:role|identity|as)\b\s*(?:[:=—-]|\bis\b)([^\n.;]*)/i;

const FENCE = /```[\s\S]*?```/g;
const INLINE_CODE = /`[^`]*`/g;
// A quote is a delimiter only at a token boundary. An apostrophe INSIDE a word
// (`worker's`, `it's`) is ordinary prose, and treating it as an opening quote
// swallowed the `role: coder` declaration that followed it.
const QUOTED = /"[^"\n]*"|(?<![\p{L}\p{N}])'[^'\n]*'(?![\p{L}\p{N}])/gu;
const URL = /\b[a-z][a-z0-9+.-]*:\/\/\S+/gi;

// The brief with everything that only talks about identities removed.
const prose = (brief) => brief.replace(FENCE, " ").replace(INLINE_CODE, " ").replace(QUOTED, " ").replace(URL, " ");

// A declared role is valid when it is one of the two worker roles.
export const isWorkerRole = (role) => WORKER_ROLES.includes(role);

// The role a brief declares, or null when it declares none and when it declares
// both. The caller decides what a missing or ambiguous answer means; this
// function never guesses one.
export const roleOfBrief = (brief) => {
  if (typeof brief !== "string" || brief.length === 0) return null;
  // The field comes first: `role: coder, devops` contains a well-formed
  // declaration (`role: coder`) that the field then contradicts. Reading the
  // field's own values keeps that contradiction visible instead of letting the
  // first keyword win.
  const field = FIELD.exec(prose(brief));
  if (field !== null) {
    const values = new Set();
    for (const match of field[1].matchAll(KEYWORD)) values.add(match[1].toLowerCase());
    return values.size === 1 ? [...values][0] : null;
  }
  const mentions = new Set();
  for (const match of prose(brief).matchAll(KEYWORD)) mentions.add(match[1].toLowerCase());
  return mentions.size === 1 ? [...mentions][0] : null;
};

// The refusal a fork gets when its brief does not name one worker identity.
// The code is the one compose already uses for a member call it cannot perform;
// `details` names the two reasons a reader must be able to tell apart.
export const roleRefusal = (brief) => {
  if (typeof brief !== "string" || brief.trim().length === 0) {
    return invalidArgument("fork needs a non-empty brief", { reason: "INVALID_BRIEF" });
  }
  const declared = roleOfBrief(brief);
  const mentions = new Set();
  for (const match of prose(brief).matchAll(KEYWORD)) mentions.add(match[1].toLowerCase());
  const reason = mentions.size > 1 && declared === null ? "AMBIGUOUS_ROLE" : "UNDECLARED_ROLE";
  return invalidArgument(
    `the brief must declare exactly one worker identity, \`role: coder\` or \`role: devops\` (§2.1, §4.1); got ${
      mentions.size > 1 ? [...mentions].join(" and ") : "none"
    }`,
    { reason, declared: [...mentions] },
  );
};

// `fork(name, brief)`: the two arguments §4.1 defines, validated here so every
// host refuses the same way and none of them can invent an identity.
export const forkRequest = (name, brief) => {
  if (typeof name !== "string" || name.trim().length === 0) {
    throw invalidArgument("fork(name) must be a non-empty string", { reason: "INVALID_NAME" });
  }
  const role = roleOfBrief(brief);
  if (role === null) throw roleRefusal(brief);
  return Object.freeze({ name, brief, role });
};
