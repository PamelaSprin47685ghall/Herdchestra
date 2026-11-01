// The OpenCode v2 plugin entry the host loads (§7.1).
//
// `packages/core/src/plugin/module.ts` decodes the module's `default` against
// `{ id, effect }` or `{ id, setup }`. Only the Effect shape receives the full
// context this adapter needs — `session`, `tool`, `event`, `agent` — so this
// file exports `effect`, and the Promise-shaped `setup` lives beside it for
// tests and for a host that supplies a complete Promise context.
//
// The effect is short: it builds the bridge (`effect-adapter.mjs`), checks that
// the bridged context carries the domains the adapter registers on, runs the
// same `setup(ctx)` the Promise entry would receive, and registers one
// finalizer that stops the event pump and releases every registration. The host
// runs the effect on a forked plugin scope, so returning from it is normal: the
// scope keeps the registrations alive and closes them on unload.
//
// `effect` is a runtime dependency at the host's version (4.0.1, the catalog
// version of `../opencode`): the `{ id, effect }` entry the host loads runs
// inside that runtime, so a published install must bring it. The import is
// static and ESM, like the package itself (`"type": "module"`,
// `exports["."] → index.js`); a `createRequire` shim would load a CommonJS path
// this package does not publish.
//
// Nothing from `@opencode/*` is imported: the host supplies the domains, this
// module only uses the runtime it declared.

import { Effect } from "effect";

import { setup } from "./plugin.mjs";
import { makeHostAdapter } from "./effect-adapter.mjs";
import { closeQuietly, loadFailureMessage } from "./effect-entry-failures.mjs";

export const effect = (host) =>
  Effect.gen(function* () {
    // The plugin's fiber context: every host call runs inside it, so the calls
    // see the services the host loaded this plugin with.
    const context = yield* Effect.context();
    const adapter = makeHostAdapter(host, { context });
    const opened = yield* Effect.promise(() => adapter.open());

    // A context without the domains the adapter registers on is a load failure
    // the host reports; registering hooks that never fire would look like a
    // working plugin.
    const missing = loadFailureMessage(opened.ctx);
    if (missing !== null) {
      yield* Effect.promise(() => opened.close());
      return yield* Effect.die(new Error(missing));
    }

    // A thrown setup error is a plugin load failure the host reports, not a
    // silently half-registered plugin.
    const cleanup = yield* Effect.tryPromise({
      try: () => setup(opened.ctx),
      catch: (error) => error,
    });

    yield* Effect.addFinalizer(() =>
      Effect.promise(async () => {
        // The pump first: a stopping subscription must not observe a disposed
        // activation. Then the setup's own cleanup, then the registrations.
        await closeQuietly(cleanup);
        await opened.close();
      }),
    );
  });

export const id = "herdchestra";

export default { id, effect };
