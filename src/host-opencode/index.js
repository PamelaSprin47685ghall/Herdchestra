// The directory entry OpenCode v2 resolves (AGENTS.md §7.1).
//
// `packages/plugin/src/host.ts` `resolve()` turns a configured local plugin
// target into `entry(["server", ""])`, i.e. `<dir>/server` or `<dir>/index`,
// and `packages/core/src/plugin/module.ts` then decodes that module's `default`
// against `{ id, effect }` or `{ id, setup }`. A directory that carries only
// `plugin.mjs` is never imported: the host looks for `index.js` or `server.js`
// and finds neither.
//
// This file is that `index.js`. It re-exports the Effect entry
// (`spec/effect-entry.mjs`), which is the shape the loaded module must have:
// the `setup`-only shape does not reach `session`, `tool` or `event` on this
// host. `spec/package.json` names the same module for loading by package name,
// so both routes reach one implementation.
//
// The host imports plugin sources through its own runtime
// (`packages/plugin/src/source.node.ts` / `source.bun.ts`), so this file stays
// plain ESM with no build step.

export { default, effect, id } from "./spec/effect-entry.mjs";
export { herdchestra, hostFacts, openGaps } from "./spec/plugin.mjs";
