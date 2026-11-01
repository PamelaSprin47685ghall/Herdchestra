// The live host boundary for the adapter's tools.
//
// The fake-host suites call `tools.get(name).execute(...)` directly. That is the
// adapter's own Promise executor, so it proves what the adapter returns, never
// that the host accepts it. The host has its own contract
// (`packages/core/src/tool/runtime.ts`):
//
//   - a tool that declares an `output` schema must answer `{ output }`, else
//     `Tool did not return its declared output`;
//   - `output` is encoded against the declared JSON Schema, so a receipt that
//     does not match is rejected at the boundary;
//   - the host's Effect path calls `.pipe` on what `execute` returns, so a bare
//     Promise executor fails with `tool.execute(...).pipe is not a function`.
//
// This file runs the adapter's tools through the host's real registry and real
// encoder: `Tool.Service` from `@opencode/core`, registered through
// `spec/tool-registrations.mjs` exactly as production does. Nothing here stands
// in for the host except the services a tool call never reaches.
//
// Run it from a checkout of the host (`../opencode` by default):
//
//   cd ../opencode/packages/core
//   bun test ../../Herdchestra/src/host-opencode/live/tool-boundary.live.ts
//
// or copy it into `packages/core/test/` (the `./lib/...` imports below are the
// host's own test helpers and only resolve from there):
//
//   cp ../../Herdchestra/src/host-opencode/live/tool-boundary.live.ts \
//      test/herd-tool-boundary.test.ts
//   bun test ./test/herd-tool-boundary.test.ts
//
// The adapter's own suite (`node --test "src/host-opencode/**/*.test.mjs"`) does
// not pick this file up: it has no `@opencode/*` dependency and must keep
// running without one.

import { expect } from "bun:test"
import { Effect, Layer } from "effect"
import { AppNodeBuilder } from "@opencode/core/effect/app-node-builder"
import { Environment } from "@opencode/core/environment/index"
import { Image } from "@opencode/core/image"
import { Integration } from "@opencode/core/integration"
import { Location } from "@opencode/core/location"
import { Permission } from "@opencode/core/permission"
import { Session } from "@opencode/core/session"
import { Tool } from "@opencode/core/tool"
import { LayerNode } from "@opencode/util/effect/layer-node"
import { testEffect } from "./lib/effect"
import { hostEnvironmentLayer } from "./fixture/environment"
import { tempLocationLayer } from "./fixture/location"
import { imagePassthrough } from "./lib/image"
import { executeTool } from "./lib/tool"

// The adapter's spec directory. `HERD_SPEC` overrides it; the default assumes
// the sibling checkout `../Herdchestra`.
//
// The spec modules are loaded through a runtime specifier on purpose: this file
// lives in the host's tree while it runs (it is copied into `packages/core/test`
// or pointed at with `bun test <path>`), so the adapter's directory is chosen at
// run time, not at author time. That is the plugin-loading exception, not a
// literal that could have been a static import.
const SPEC = process.env.HERD_SPEC ?? new URL("../../../../Herdchestra/src/host-opencode/spec", import.meta.url).pathname
const loadSpec = (name) => import(`${SPEC}/${name}`)
const { TOOL_NAMES } = await loadSpec("capabilities.mjs")
// The same renderer the adapter uses for the model-visible content (§6.3), so
// this expectation cannot drift from the wrapper it checks.
import { toCue } from "../../core/cue.mjs"
const { TOOL_OUTPUTS } = await loadSpec("tool-outputs.mjs")
const { REFUSALS, refusal } = await loadSpec("refusals.mjs")
const { toolInfo } = await loadSpec("tool-registrations.mjs")

// The host's Effect path calls `.pipe` on the returned value. The adapter's
// executors are Promises (the host's Promise path wraps them itself); this is
// the same wrapping `spec/effect-adapter.mjs` performs at load time.
const asEffect = (tool) => ({
  ...tool,
  execute: (input, context) =>
    Effect.tryPromise({ try: () => tool.execute(input, context), catch: (error) => error }),
})

const registry = AppNodeBuilder.build(LayerNode.group([Tool.node]), [
  Location.node.replace(tempLocationLayer),
  Environment.node.replace(hostEnvironmentLayer),
  Integration.node.replace(Layer.mock(Integration.Service, {})),
  Permission.node.replace(Layer.mock(Permission.Service, { assert: () => Effect.void })),
  Image.node.replace(imagePassthrough),
  Session.node.replace(Layer.mock(Session.Service, {})),
])

const it = testEffect(registry)

// The input the host's own snapshot executes: `call` is the tool call, and the
// session/agent/message ids travel beside it.
const context = {
  sessionID: "ses_live",
  agent: "coder",
  messageID: "msg_live",
  call: { type: "tool-call", id: "call_live", name: "js-worker", input: {} },
  progress: () => Effect.void,
}

// The smallest valid input for each tool, so a call reaches the executor
// instead of being rejected by the input schema.
const INPUTS = {
  [TOOL_NAMES.manager]: { code: "return 1;" },
  [TOOL_NAMES.worker]: { code: "return 1;" },
  [TOOL_NAMES.review]: { code: "return 1;" },
  [TOOL_NAMES.plan]: { code: "return 1;" },
  [TOOL_NAMES.fork]: { name: "w1", brief: "role: coder — b" },
  [TOOL_NAMES.join]: {},
}

const runThroughHost = (name, executor) =>
  Effect.gen(function* () {
    const tools = yield* Tool.Service
    yield* tools.transform((editor) => {
      editor.add(asEffect(toolInfo(name, executor)))
    })
    return yield* executeTool(tools, { ...context, call: { ...context.call, name, input: INPUTS[name] ?? {} } })
  })

it.effect("a program receipt survives the host's output schema", () =>
  Effect.gen(function* () {
    const receipt = {
      name: "call_live",
      state: "completed",
      reason: null,
      payload: null,
      source: "1 | return 1;",
      pendingOperations: 0,
      command: null,
      delta: "",
      remainingWaitMs: 0,
      value: 1,
      error: null,
    }
    const result = yield* runThroughHost(TOOL_NAMES.worker, async () => receipt)
    expect(result.status).toBe("completed")
    expect(result.output).toMatchObject({ name: "call_live", state: "completed", value: 1 })
    // The model-visible content is the receipt as CUE (§6.3), not a stand-in
    // string and not raw JSON: `output` stays the JSON value the host encodes.
    expect(result.content?.[0]).toEqual({ type: "text", text: toCue(result.output) })
  }),
)

it.effect("a program refusal is answered, not rejected", () =>
  Effect.gen(function* () {
    // `ROLE_DENIED` is a receipt, never a thrown error (§4.1, §12.1): the model
    // must be able to read it and correct the call.
    const result = yield* runThroughHost(TOOL_NAMES.worker, async () =>
      refusal(REFUSALS.ROLE, { role: "manager" }),
    )
    expect(result.status).toBe("completed")
    expect(result.output).toMatchObject({ ok: false, rejection: "ROLE_DENIED" })
  }),
)

it.effect("fork and join receipts survive the boundary", () =>
  Effect.gen(function* () {
    const fork = yield* runThroughHost(TOOL_NAMES.fork, async () => ({
      name: "w1",
      steered: false,
      started: true,
    }))
    expect(fork.status).toBe("completed")
    expect(fork.output).toMatchObject({ name: "w1", steered: false, started: true })

    const join = yield* runThroughHost(TOOL_NAMES.join, async () => ({
      ok: true,
      record: { name: "w1" },
      rejection: null,
    }))
    expect(join.status).toBe("completed")
    expect(join.output).toMatchObject({ ok: true })
  }),
)

it.effect("the schema the host enforces is the one the adapter ships", () =>
  Effect.gen(function* () {
    const tools = yield* Effect.gen(function* () {
      const service = yield* Tool.Service
      yield* service.transform((editor) => {
        for (const name of Object.keys(TOOL_OUTPUTS)) editor.add(asEffect(toolInfo(name, async () => ({}))))
      })
      return yield* service.list()
    })
    for (const [name, output] of Object.entries(TOOL_OUTPUTS)) {
      const registered = tools.find((tool) => tool.name === name)
      expect(registered).toBeDefined()
      expect(registered?.output).toEqual(output)
    }
  }),
)
