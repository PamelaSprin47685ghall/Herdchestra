// The plugin entry file a started session must load (根 AGENTS.md §7.2).
//
// Cline refuses a plugin path whose extension is not `.js` or `.ts`
// (`PLUGIN_FILE_EXTENSIONS` in `@cline/shared`), so `index.mjs` cannot be the
// path a session is started with; `index.js` re-exports this plugin and is.
//
// This module exists on its own so the session factory, the Plan entry and the
// hand-off name the same file without importing each other in a cycle.

import path from "node:path";
import { fileURLToPath } from "node:url";

export const pluginEntryPath = () => path.join(path.dirname(fileURLToPath(import.meta.url)), "index.js");
