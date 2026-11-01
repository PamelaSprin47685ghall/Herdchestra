// Cline loads a plugin only through an entry file whose extension is `.js` or
// `.ts` (`PLUGIN_FILE_EXTENSIONS` in `@cline/shared`); a `.mjs` path is refused
// before the module is imported. This file is that entry: it re-exports the
// plugin from `index.mjs`, and the host resolves everything below it through
// its own jiti loader.
//
// The default export is the plugin object itself (`{ name, manifest, hooks,
// setup }`). The host reads it as data before setup, then calls `setup` once per
// session.

export {
  default,
  plugin,
  createPlugin,
  PLUGIN_NAME,
  CAPABILITIES,
  startPlan,
  ROLE_METADATA_KEY,
  roleOf,
} from "./index.mjs";
