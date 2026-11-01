// What the plugin module exports (AGENTS.md §7.1).
//
// The host loads the module and decodes `default` against `{ id, setup }`; it
// keeps nothing else. The named exports exist for tests and for a reader who
// wants the facts without starting a session. Changing a name breaks that
// reader, so a name is only ever added.

export const EXPORT_NAMES = Object.freeze([
  "default",
  "herdchestra",
  "hostFacts",
  "openGaps",
  "narrowTools",
  "ROLE_OF_AGENT",
]);

// Agent ids the adapter creates and the role each one runs as (§2.1, §6, §7.1).
// `Plan` is a top-level agent (§6): it is never forked, and it writes one draft.
export const ROLE_OF_AGENT = Object.freeze({
  Manager: "manager",
  coder: "coder",
  devops: "devops",
  Plan: "plan",
});
