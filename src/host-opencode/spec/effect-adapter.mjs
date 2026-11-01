// The Effect host context as the Promise-shaped calls `setup(ctx)` expects.
//
// The v2 host runs an Effect plugin with a `Context` (`packages/plugin/src/
// effect/plugin.ts`): `agent`, `event`, `session`, `tool`, `location`,
// `options`, plus more. Every method returns an Effect; the adapter's code is
// written in Promises (`await session.create(...)`), so this module is the one
// place the two are bridged.
//
// It does three things and nothing else:
//
//   1. `Effect.runPromise` each call, in the plugin's own fiber context, so a
//      call sees the same services the plugin was loaded with;
//   2. collect the dispose handles of `transform` and `hook` registrations, so
//      the plugin can release them when the host unloads it;
//   3. convert the Effect `Stream` of events into the `AsyncIterable` the
//      adapter's event pump consumes.
//
// Nothing here decides product behaviour: a domain the host does not offer is
// left absent, and the adapter's own ports refuse with the gap they wait on.

import { Effect, Stream } from "effect";

// Run an Effect in the plugin's fiber context and answer a Promise.
//
// The context is a thunk: `Effect.context()` must be evaluated inside the
// plugin's Effect, not at module scope, or it captures an empty fiber context
// and every call fails with "service.key undefined".
const run = (runtime) => (effect) => Effect.runPromiseWith(runtime())(effect);

// A dispose handle list. Each registration is released in reverse order when
// the plugin unloads, which is what the host does with its own scopes.
const makeReleases = () => {
  const releases = [];
  return {
    add: (release) => {
      releases.push(release);
      return release;
    },
    async close() {
      while (releases.length > 0) {
        const release = releases.pop();
        try {
          await release();
        } catch {
          // A release that fails must not stop the others: the host is
          // unloading, and every registration it made should still be taken
          // back.
        }
      }
    },
  };
};

// `transform(callback)` over an Effect domain: the editor is handed to the
// callback synchronously, and the registration's dispose is kept.
const transform = (runtime, domain, releases) => (callback) =>
  run(runtime)(
    domain.transform((editor) => {
      callback(editor);
    }),
  ).then((registration) => {
    releases.add(() => run(runtime)(registration.dispose));
    return registration;
  });

// A failed tool call keeps its own error. The host's `Tool.Error` class cannot
// be constructed here (the adapter imports no `@opencode/*` package), and a
// plain tagged object would not match `instanceof Tool.Error` in the host's
// `executeTool` catch; the host already maps any other failure into a
// `Tool.Error` (`packages/core/src/tool/runtime.ts` `execute`), so the original
// error is passed through untouched and that mapping does the work.
const toolError = (error) => error;

// The Effect host calls `tool.execute` and runs the returned Effect
// (`packages/core/src/tool/runtime.ts` `execute` → `Effect.gen` → `tool.execute(...)`),
// while this adapter's executors are Promises. The host's own Promise path wraps
// them (`packages/plugin/src/promise/adapter.ts` `executePromiseTool`), but the
// Effect path forwards `tool.transform` verbatim, so the wrapping is ours.
//
// `signal` is the fiber's interrupt signal, which `Effect.tryPromise` supplies;
// `progress` is the host's Effect-returning callback, so it is adapted back to
// the Promise shape the adapter's `execute(input, context)` signature declares.
const effectTool = (runtime) => (tool) => {
  if (typeof tool?.execute !== "function") return tool;
  return {
    ...tool,
    execute: (input, context) =>
      Effect.tryPromise({
        try: (signal) =>
          tool.execute(input, {
            ...context,
            signal,
            progress: (update) => run(runtime)(context.progress(update)),
          }),
        catch: toolError,
      }),
  };
};

// `tool.transform` over an Effect domain: every tool the callback adds is
// wrapped once, so a Promise executor never reaches the host's Effect runner.
const toolTransform = (runtime, domain, releases) => (callback) =>
  run(runtime)(
    domain.transform((editor) => {
      callback({
        list: () => editor.list().map(effectTool(runtime)),
        get: (id) => {
          const found = editor.get(id);
          return found === undefined ? undefined : effectTool(runtime)(found);
        },
        namespace: (namespace) => editor.namespace(namespace),
        add: (tool) => editor.add(effectTool(runtime)(tool)),
        update: (id, update) => editor.update(id, update),
        remove: (id) => editor.remove(id),
      });
    }),
  ).then((registration) => {
    releases.add(() => run(runtime)(registration.dispose));
    return registration;
  });

// `hook(name, callback)` over an Effect domain, same shape.
const hook = (runtime, domain, releases) => (name, callback) =>
  run(runtime)(
    domain.hook(name, (event) => Effect.promise(async () => callback(event))),
  ).then((registration) => {
    releases.add(() => run(runtime)(registration.dispose));
    return registration;
  });

// The Effect event stream as an AsyncIterable. The adapter's pump ends when the
// stream ends; a stream failure ends it too, and the pump's catch reports it.
const subscribe = (runtime, domain) => (options = {}) => {
  const stream = domain.subscribe({ types: options.types });
  return Stream.toAsyncIterableWith(stream, runtime())[Symbol.asyncIterator]();
};

export const makeHostAdapter = (host, { context: captured = null } = {}) => {
  // The plugin's fiber context, taken lazily. `effect-entry.mjs` captures it
  // with `Effect.context()` inside its own generator and hands it here, so the
  // adapter runs every call in the same context the host loaded the plugin in.
  const runtime = () => captured ?? Effect.context();
  const releases = makeReleases();

  return {
    // The Promise-shaped context the adapter's `setup` receives.
    open: async () => {
      const session = host.session;
      const tool = host.tool;
      const agent = host.agent;
      const event = host.event;
      const options = host.options ?? {};

      // Every method of the host's session domain, in the Promise shape. The
      // list is explicit: a domain method the adapter does not use is not
      // bridged, so a typo surfaces here instead of at first call.
      const sessionDomain = {};
      for (const name of [
        "list",
        "create",
        "get",
        "remove",
        "switchAgent",
        "switchModel",
        "prompt",
        "generate",
        "command",
        "compact",
        "synthetic",
        "interrupt",
        "update",
        "move",
        "wait",
        "context",
        "instructions",
      ]) {
        if (typeof session[name] !== "function") continue;
        sessionDomain[name] = (input) => run(runtime)(session[name](input));
      }
      sessionDomain.hook = hook(runtime, session, releases);

      const ctx = {
        location: host.location,
        options,
        agent: {
          transform: transform(runtime, agent, releases),
          list: () => run(runtime)(agent.list()),
          get: (input) => run(runtime)(agent.get(input)),
          reload: () => run(runtime)(agent.reload()),
        },
        tool: {
          transform: toolTransform(runtime, tool, releases),
          hook: hook(runtime, tool, releases),
          list: () => run(runtime)(tool.list()),
          reload: () => run(runtime)(tool.reload()),
        },
        session: sessionDomain,
        event: { subscribe: subscribe(runtime, event) },
      };
      return { ctx, close: () => releases.close() };
    },
  };
};
