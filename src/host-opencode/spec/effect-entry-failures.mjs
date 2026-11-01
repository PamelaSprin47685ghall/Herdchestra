// The Effect entry's failure modes, and the one host fact they depend on.
//
// `effect-entry.mjs` runs `setup(ctx)` inside an Effect so the host keeps the
// registrations on its own scope. Two things can go wrong there, and both must
// be reported as a plugin load failure instead of a half-registered plugin:
//
//   - the adapter's `setup` throws (a wiring error in our code);
//   - the host's context is not the one this adapter bridges.
//
// This module keeps those facts in one place so the entry stays short and the
// tests can check them without a live host.
//
// ## The host context fact
//
// `packages/plugin/src/effect/plugin.ts` gives an Effect plugin `agent`, `event`,
// `session`, `tool`, `location` and `options`. The Promise plugin context
// (`packages/plugin/src/promise/plugin.ts`) gives the same names. Both are what
// `effect-adapter.mjs` bridges. A host that offers neither (the v1 CLI, which
// expects `server()`), cannot run this adapter, and that is a fact recorded in
// `gaps.mjs PLUGIN_MODULE_SHAPE` rather than a runtime guess.

export const REQUIRED_DOMAINS = Object.freeze(["agent", "event", "session", "tool"]);

// The domains the bridged context must carry. A missing one is a load failure:
// the adapter would otherwise register hooks that never fire.
export const missingDomains = (ctx) => {
  if (ctx === null || typeof ctx !== "object") return [...REQUIRED_DOMAINS];
  return REQUIRED_DOMAINS.filter((name) => ctx[name] === null || typeof ctx[name] !== "object");
};

// The message a load failure carries. It names the missing domains, because the
// host shows the message to the user.
export const loadFailureMessage = (ctx) => {
  const missing = missingDomains(ctx);
  return missing.length === 0
    ? null
    : `herdchestra: the host context has no ${missing.join(", ")}; this adapter needs a v2 plugin context`;
};

// A cleanup failure is logged, never thrown: the host is unloading the plugin
// and every registration must still be released.
export const closeQuietly = async (close) => {
  try {
    await close?.();
    return null;
  } catch (error) {
    return String(error?.message ?? error);
  }
};
