// The Effect host path: `tool.transform` must hand the host Effect-shaped
// executors.
//
// `packages/core/src/tool/runtime.ts` `execute` runs
// `tool.execute(decoded, context)` inside `Effect.gen` and calls `.pipe` on the
// result, so a Promise executor crashes the whole tool call with
// `tool.execute(decoded, context).pipe is not a function`. The host's own
// Promise plugin path wraps executors (`packages/plugin/src/promise/adapter.ts`
// `executePromiseTool`); the Effect path forwards `tool.transform` verbatim, so
// `spec/effect-adapter.mjs` does the wrapping and these tests pin it.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import { Context, Effect } from "effect";

import { makeHostAdapter } from "./effect-adapter.mjs";

// A host whose tool domain records what the adapter adds. `agent`, `event` and
// `session.hook` are present because `open()` bridges them; only the tool domain
// is exercised.
const fakeEffectHost = () => {
  const added = [];
  const editor = {
    list: () => [],
    get: () => undefined,
    namespace() {},
    remove() {},
    update() {},
    add: (tool) => added.push(tool),
  };
  return {
    added,
    host: {
      location: { directory: "/tmp" },
      options: {},
      agent: {},
      event: {},
      session: { hook: () => Effect.succeed({ dispose: Effect.void }) },
      tool: {
        transform: (callback) =>
          Effect.sync(() => {
            callback(editor);
            return { dispose: Effect.void };
          }),
      },
    },
  };
};

const openWith = async (host) => {
  const adapter = makeHostAdapter(host, { context: Context.empty() });
  return await adapter.open();
};

test("a Promise executor added through tool.transform becomes an Effect", async () => {
  const { host, added } = fakeEffectHost();
  const opened = await openWith(host);
  await opened.ctx.tool.transform((tools) => {
    tools.add({
      name: "js-worker",
      description: "d",
      input: { type: "object" },
      execute: async (input) => ({ output: { value: (input?.n ?? 0) + 1 } }),
    });
  });
  assert.equal(added.length, 1);
  const effect = added[0].execute({ n: 41 }, { progress: () => Effect.void });
  // The host calls `.pipe` on the answer; a Promise has none.
  assert.equal(typeof effect?.pipe, "function", "the host runs the returned value as an Effect");
  assert.deepEqual(await Effect.runPromise(effect), { output: { value: 42 } });
});

test("a failing executor fails the Effect instead of rejecting a Promise", async () => {
  const { host, added } = fakeEffectHost();
  const opened = await openWith(host);
  await opened.ctx.tool.transform((tools) => {
    tools.add({
      name: "js-worker",
      description: "d",
      input: { type: "object" },
      execute: async () => {
        throw new Error("boom");
      },
    });
  });
  await assert.rejects(Effect.runPromise(added[0].execute({}, { progress: () => Effect.void })), /boom/);
});

test("the host's progress callback is bridged back to a Promise", async () => {
  const { host, added } = fakeEffectHost();
  const opened = await openWith(host);
  const seen = [];
  await opened.ctx.tool.transform((tools) => {
    tools.add({
      name: "js-worker",
      description: "d",
      input: { type: "object" },
      execute: async (_input, context) => {
        await context.progress({ step: 1 });
        return { output: "ok" };
      },
    });
  });
  const result = await Effect.runPromise(
    added[0].execute({}, { progress: (update) => Effect.sync(() => seen.push(update)) }),
  );
  assert.deepEqual(seen, [{ step: 1 }]);
  assert.deepEqual(result, { output: "ok" });
});
