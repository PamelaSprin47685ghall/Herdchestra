// The plugin definition handed to the host (AGENTS.md §7.1).
//
// The host decodes the module's `default` export against
// `{ id: string, setup: function }` (`packages/core/src/plugin/module.ts`), so
// the shape is not `Plugin.define` output alone: an object literal with the two
// fields is what the schema accepts, and `Plugin.define` is the identity
// function applied to the same object. Building it here keeps the id in one
// place.

import { PLUGIN_API } from "./capabilities.mjs";

export const PLUGIN_ID = PLUGIN_API.id;

// `setup` is supplied by `plugin.mjs`, which owns the host context.
export const definePlugin = (setup) => {
  if (typeof setup !== "function") {
    throw new TypeError("a plugin needs a setup function");
  }
  return Object.freeze({ id: PLUGIN_ID, setup });
};
