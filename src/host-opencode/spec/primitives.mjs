// Which surface members are TOP-LEVEL primitives (§3.1).
//
// A primitive is called by its bare name (`fork(...)`, `join()`, `review(...)`,
// `devops(...)`); every other member is written `this.xxx`. Root §3.1 gives one
// spelling per capability, so a name is never both.
//
// Compose owns the rule for the surfaces it publishes (`SURFACE_PRIMITIVES`).
// This adapter adds the one primitive it binds itself: Plan's `devops`, which is
// a bare synchronous join over ONE dedicated worker (根 §1.1 item 3), not the
// `this.devops` member the older contract described.

import { SURFACE_MEMBERS, SURFACE_PRIMITIVES } from "../../compose/program/surface.mjs";

// A real host fact, not a fallback: compose still lists `fork`, `join` and
// `review` in `SURFACE_MEMBERS` (so `surface()` supplies their handlers) and
// leaves the spelling to the host, which binds them as primitives per §3.1.
// Plan's `review` is the same rule on its face; `js-review` has none of them.
const HOST_FLOOR = Object.freeze({
  "js-manager": Object.freeze(["fork", "join", "review"]),
  "js-worker": Object.freeze(["fork", "join"]),
  "js-review": Object.freeze([]),
  plan: Object.freeze(["review"]),
});

// Primitives this host binds beyond compose's list. Plan's `devops` is the only
// one: it must be a top-level function so the model reads `devops(verboseIntent)`
// and `this.devops` is absent.
export const HOST_PRIMITIVES = Object.freeze({
  plan: Object.freeze(["devops"]),
});

// The primitives of one surface name: the host floor, compose's published list,
// then this host's own additions. A surface either side does not name has none.
export const primitivesForSurface = (surface) => [
  ...new Set([
    ...(HOST_FLOOR[surface] ?? []),
    ...(SURFACE_PRIMITIVES[surface] ?? []),
    ...(HOST_PRIMITIVES[surface] ?? []),
  ]),
];

// The members a surface still has once its primitives are taken out. Compose
// lists `fork`/`join`/`review` among the members because their handlers live in
// `surface()`; the filter is what keeps the description and the compiled
// program from offering the same capability twice.
export const membersForSurface = (surface) => {
  const primitives = primitivesForSurface(surface);
  return SURFACE_MEMBERS[surface].filter((member) => !primitives.includes(member));
};

// Every name the surface offers, primitives included. `memberAvailable` reads
// this: a primitive is a capability the role has, so refusing it by name would
// be wrong even though compose's member table does not list it.
export const namesForSurface = (surface) => [
  ...new Set([...membersForSurface(surface), ...primitivesForSurface(surface)]),
];
