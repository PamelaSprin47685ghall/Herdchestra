// Test entry only. This file is NOT a load path on any tested host build.
//
// The host resolves a configured local plugin *directory* to `index.js` or
// `server.js` (`packages/plugin/src/host.ts` `resolve`), never to `main` and
// never to this file by name; a direct path to this file is not imported either.
// Real-host probes of OpenCode 1.19.4 (a v1-plugin host) and of the v2.1.4
// sources both failed to load herdchestra: the installed host rejects the v2
// shape, and the directory has no `index.js`. Keep this module for tests; do
// not document it as a way to install the plugin.
//
// The Promise-shaped `setup` stays reachable for tests and for a host that hands
// a complete Promise context (the v2.1.4 promise adapter does not offer
// `session`, `tool` or `event` to it in the loaded shape). Both entries run one
// implementation tree, so neither can drift from the other.

export { default, effect, id } from "./spec/effect-entry.mjs";
export { herdchestra, setup, hostFacts, openGaps } from "./spec/plugin.mjs";
