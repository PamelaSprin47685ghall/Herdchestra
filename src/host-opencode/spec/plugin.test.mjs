// End-to-end checks of the OpenCode adapter against a fake host (§7.1).
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.
//
// The fake host is the plugin context: every registration point is an async
// function that records what the adapter registered, and the tools it collects
// are called the way the host calls them. Nothing here imports OpenCode.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { setup, hostFacts, openGaps } from "./plugin.mjs";
import { SURFACE_MEMBERS } from "../../compose/program/surface.mjs";
import { DIRECTOR_MESSAGE } from "../../compose/prompt/director.mjs";
import { fcRuns } from "../../core/test-runs.mjs";

const RESET = (budget = 50) => ({
  kind: "resetScope",
  newScopeGoal: "test",
  newScopeRetentionBudget: budget,
});

// One fake host. `sessions` is a scripted SessionDomain: `wait` resolves at
// once and `get` reports the child as succeeded.
const fakeHost = async ({ directory, sessions } = {}) => {
  const dir = directory ?? (await fs.mkdtemp(path.join(os.tmpdir(), "herdchestra-")));
  const tools = new Map();
  const before = [];
  const after = [];
  const context = [];
  const prompt = [];
  const subscribed = [];
  const ctx = {
    location: { directory: dir },
    options: {},
    agent: {
      transform: async (callback) => {
        const agents = [{ id: "Manager" }, { id: "coder" }, { id: "devops" }, { id: "build" }];
        callback({ list: () => agents, update: (id, update) => update(agents.find((a) => a.id === id) ?? {}) });
      },
    },
    tool: {
      transform: async (callback) => callback({ add: (tool) => tools.set(tool.name, tool), update() {}, remove() {} }),
      hook: async (name, callback) => (name === "execute.before" ? before : after).push(callback),
    },
    session: {
      hook: async (name, callback) => (name === "context" ? context : prompt).push(callback),
      ...(sessions ?? {}),
    },
    event: {
      subscribe: (options) => {
        subscribed.push(options);
        return { [Symbol.asyncIterator]: () => ({ next: async () => ({ done: true }) }), return: async () => ({ done: true }) };
      },
    },
  };
  const cleanup = await setup(ctx);
  let sequence = 0;
  const call = async (name, input, { agent = "coder", sessionID = "ses_main", messageID } = {}) => {
    const id = `call_${(sequence += 1)}`;
    const scoped = { ...input, scope: input.scope ?? RESET() };
    const event = { tool: name, sessionID, agent, messageID: messageID ?? `msg_${sequence}`, id, input: scoped };
    for (const hook of before) await hook(event);
    // The host's result shape: `output` is the receipt the schema guards,
    // `content` is what the model reads. The adapter's tools declare an output
    // schema, so the host requires both (`tool-registrations.mjs`).
    const result = await tools.get(name).execute(scoped, {
      sessionID,
      agent,
      messageID: event.messageID,
      id,
      signal: undefined,
      progress: async () => {},
    });
    const output = result.output;
    for (const hook of after) await hook({ ...event, status: "completed", result: output });
    return output;
  };
  return { dir, tools, before, after, context, prompt, subscribed, call, cleanup };
};

test("registers four agents, six tools, four hooks and one subscription", async () => {
  const host = await fakeHost();
  assert.deepEqual([...host.tools.keys()], ["js-manager", "js-worker", "js-review", "plan", "fork", "join"]);
  assert.equal(host.before.length, 1);
  assert.equal(host.after.length, 1);
  assert.equal(host.context.length, 1);
  assert.equal(host.prompt.length, 1);
  assert.deepEqual(host.subscribed[0].types, [
    "session.created",
    "session.execution.succeeded",
    "session.execution.failed",
    "session.execution.interrupted",
  ]);
  await host.cleanup();
});

test("the spec's class program runs against the registered tool", async () => {
  const host = await fakeHost();
  await fs.writeFile(path.join(host.dir, "retry.mjs"), "const RetryPolicy = 1;\nconst RetryPolicy2 = 2;\n");
  const source = `class Js extends JsProgram {
  async run() {
    const text = await this.cat("retry.mjs");
    const spans = this.whereAll(text, "RetryPolicy");
    let next = text;
    for (const [begin, end] of spans.slice().reverse()) {
      next = next.slice(0, begin) + "RetryPolicyV2" + next.slice(end);
    }
    this.write("retry.mjs", next);
    return { replaced: spans.length };
  }
}`;
  const out = await host.call("js-worker", { code: source });
  assert.equal(out.state, "completed", JSON.stringify(out.error));
  assert.deepEqual(out.value, { replaced: 2 });
  await new Promise((resolve) => setTimeout(resolve, 20));
  const text = await fs.readFile(path.join(host.dir, "retry.mjs"), "utf8");
  // The second literal already ends in `2`, so the replacement reads `V22`;
  // what matters is that both spans were replaced and no literal was missed.
  assert.equal(text, "const RetryPolicyV2 = 1;\nconst RetryPolicyV22 = 2;\n");
  await host.cleanup();
});

test("worker program reads, writes and answers with the nine-field receipt", async () => {
  const host = await fakeHost();
  await fs.writeFile(path.join(host.dir, "a.txt"), "alpha\nbeta\n");
  const read = await host.call("js-worker", { code: 'const t = await this.cat("a.txt"); return this.whereAll(t, "alpha");' });
  assert.equal(read.state, "completed", JSON.stringify(read.error));
  assert.deepEqual(read.value, [[0, 5]]);
  assert.deepEqual(Object.keys(read).sort(), [
    "command",
    "delta",
    "error",
    "name",
    "payload",
    "pendingOperations",
    "reason",
    "remainingWaitMs",
    "source",
    "state",
    "value",
  ]);
  const write = await host.call("js-worker", { code: 'this.write("b.txt", "B"); return 1;' });
  assert.equal(write.state, "completed", JSON.stringify(write));
  // The member is synchronous but the atomic rename lands on the next tick of
  // the event loop; the file is read after it.
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(await fs.readFile(path.join(host.dir, "b.txt"), "utf8"), "B");
  await host.cleanup();
});

test("an ambiguous where parks the program and another program resumes it", async () => {
  const host = await fakeHost();
  await fs.writeFile(path.join(host.dir, "q.txt"), "batchSize\nbatchSize\n");
  const parked = await host.call("js-worker", { code: 'const t = await this.cat("q.txt"); return await this.where(t, "batchSize");' });
  assert.equal(parked.state, "parked");
  assert.equal(parked.reason, "explicit");
  // The receipt carries the exact program the model wrote, with line numbers.
  assert.match(parked.source, /^\s*1 \| const t = await this\.cat\("q\.txt"\);/);
  const resumed = await host.call("js-worker", { code: `return await this.unpark(${JSON.stringify(parked.name)}, [0, 9]);` });
  assert.equal(resumed.state, "completed");
  assert.deepEqual(resumed.value, { name: parked.name, resumed: true, value: [0, 9], error: null });
  // The suspended body kept its async stack: it returned the injected span, and
  // the call that had parked reported the same program afterwards.
  const again = await host.call("js-worker", { code: "return 1;", scope: RESET() }, { messageID: "after_resume" });
  assert.equal(again.state, "completed");
  await host.cleanup();
});

test("a resumed program really continues: its later write happens", async () => {
  const host = await fakeHost();
  await fs.writeFile(path.join(host.dir, "q.txt"), "batchSize\nbatchSize\n");
  const source = `class Js extends JsProgram {
  async run() {
    const text = await this.cat("q.txt");
    const [begin, end] = await this.where(text, "batchSize");
    this.write("resumed.txt", text.slice(0, begin) + "BATCH" + text.slice(end));
    return { begin, end };
  }
}`;
  const parked = await host.call("js-worker", { code: source });
  assert.equal(parked.state, "parked");
  const before = await fs.readdir(host.dir);
  assert.equal(before.includes("resumed.txt"), false, "a parked program has not run its later write");
  const resumed = await host.call("js-worker", { code: `return await this.unpark(${JSON.stringify(parked.name)}, [0, 9]);` });
  assert.equal(resumed.value.resumed, true, JSON.stringify(resumed.value));
  assert.deepEqual(resumed.value.value, { begin: 0, end: 9 });
  await new Promise((resolve) => setTimeout(resolve, 20));
  const text = await fs.readFile(path.join(host.dir, "resumed.txt"), "utf8");
  assert.equal(text, "BATCH\nbatchSize\n", "the resumed program wrote after the park");
  await host.cleanup();
});

test("nothing before the park runs twice when the program resumes", async () => {
  const host = await fakeHost();
  await fs.writeFile(path.join(host.dir, "q.txt"), "batchSize\nbatchSize\n");
  const marker = path.join(host.dir, "count.txt");
  const source = `class Js extends JsProgram {
  async run() {
    const listed = await this.call("printf x >> count.txt; printf 1");
    const text = await this.cat("q.txt");
    const [begin, end] = await this.where(text, "batchSize");
    this.write("after.txt", "done");
    return { listed: listed.stdout, begin, end };
  }
}`;
  const parked = await host.call("js-worker", { code: source });
  assert.equal(parked.state, "parked");
  // The command before the park ran exactly once.
  const before = await fs.readFile(marker, "utf8");
  assert.equal(before, "x", "the pre-park command ran once before the resume");
  const resumed = await host.call("js-worker", { code: `return await this.unpark(${JSON.stringify(parked.name)}, [0, 9]);` });
  assert.equal(resumed.value.resumed, true, JSON.stringify(resumed.value));
  assert.deepEqual(resumed.value.value, { listed: "1", begin: 0, end: 9 });
  // ...and not a second time when the body continued past the park.
  assert.equal(await fs.readFile(marker, "utf8"), "x", "the pre-park command did not run twice");
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(await fs.readFile(path.join(host.dir, "after.txt"), "utf8"), "done");
  await host.cleanup();
});

test("call runs a command and reports its exit code", async () => {
  const host = await fakeHost();
  const out = await host.call("js-worker", { code: 'return await this.call("printf hi");' });
  assert.equal(out.state, "completed");
  assert.deepEqual(out.value, { stdout: "hi", stderr: "", exitCode: 0 });
  await host.cleanup();
});

test("a throwing body fails the program and keeps the error", async () => {
  const host = await fakeHost();
  const out = await host.call("js-worker", { code: 'throw new Error("boom");' });
  assert.equal(out.state, "failed");
  assert.deepEqual(out.error, { name: "Error", message: "boom" });
  await host.cleanup();
});

test("manager surface has no file member and refuses a host read", async () => {
  const host = await fakeHost();
  const out = await host.call("js-manager", { code: "return 1;" }, { agent: "Manager" });
  assert.equal(out.state, "completed");
  await assert.rejects(
    host.call("read", { path: "a.txt" }, { agent: "Manager" }),
    /does not belong to your role/,
  );
  await host.cleanup();
});

test("fork starts, then steers, then join reports the first worker", async () => {
  const prompts = [];
  const sessions = {
    create: async (input) => ({ id: "ses_child", ...input }),
    prompt: async (input) => prompts.push(input),
    wait: async () => undefined,
    // The fork reads its parent's model before creating the child
    // (`model-inherit.mjs`): a session created without one falls to the agent
    // default, which on the live host answers 429.
    get: async () => ({ id: "ses_child", outcome: "succeeded", title: "w1", model: { providerID: "kw92", id: "lazy/task" } }),
  };
  const host = await fakeHost({ sessions });
  const first = await host.call("js-manager", { code: 'return await fork("w1", "role: coder. do it");' }, { agent: "Manager" });

  assert.deepEqual(first.value, { name: "w1", steered: false, started: true });
  const second = await host.call("js-manager", { code: 'return await fork("w1", "role: coder. more");' }, { agent: "Manager" });
  assert.deepEqual(second.value, { name: "w1", steered: true, started: false });
  assert.equal(prompts[0].delivery, "queue");
  const joined = await host.call("js-manager", { code: "return await join();" }, { agent: "Manager" });
  assert.equal(joined.value.ok, true, JSON.stringify(joined.value));
  assert.equal(joined.value.record?.name, "w1", JSON.stringify(joined.value));
  await host.cleanup();
});

test("scope budget is spent by keepLast and a spent budget demands resetScope", async () => {
  const host = await fakeHost();
  // A budget of one: the reset call itself is admitted, one keepLast spends it.
  await host.call("js-worker", { code: "return 0;", scope: RESET(1) }, { messageID: "turn_reset" });
  await host.call("js-worker", { code: "return 1;", scope: { kind: "keepLast" } }, { messageID: "turn_1" });
  await assert.rejects(
    host.call("js-worker", { code: "return 1;", scope: { kind: "keepLast" } }, { messageID: "turn_2" }),
    /scope annotation was refused/,
  );
  await host.cleanup();
});

// A refused annotation must say WHAT was wrong. The host runs `execute.before`
// before it decodes the arguments against the input schema, so this thrown
// message is the only text the model ever sees; the schema's own "Missing key
// at scope.newScopeGoal" never gets a turn. Live6/live7 both showed the model
// receiving the bare table sentence and correcting nothing.
test("a refused scope annotation names the field that was wrong", async () => {
  const host = await fakeHost();
  // `resetScope` with neither field: the live7 shape.
  await assert.rejects(
    host.call("js-worker", { code: "return 1;", scope: { kind: "resetScope" } }, { messageID: "turn_bare" }),
    (error) => {
      assert.match(error.message, /SCOPE_REFUSED/, "the code leads");
      assert.match(error.message, /newScopeGoal/, "the model must learn which field is missing");
      return true;
    },
  );
  // A stray field is a different refusal with its own detail.
  await assert.rejects(
    host.call(
      "js-worker",
      { code: "return 1;", scope: { kind: "keepLast", replacement: "x" } },
      { messageID: "turn_stray" },
    ),
    (error) => {
      assert.match(error.message, /replacement/, "the model must learn which field is unexpected");
      return true;
    },
  );
  await host.cleanup();
});

test("context hook narrows the catalog, appends the director and the hint pair once", async () => {
  const host = await fakeHost();
  const event = {
    sessionID: "ses_main",
    agent: "Manager",
    tools: { read: {}, write: {}, "js-manager": {}, "js-review": {}, fork: {}, join: {}, question: {} },
    system: [],
    messages: [{ role: "user", content: [{ type: "text", text: "hello" }] }],
  };
  await host.context[0](event);
  assert.deepEqual(Object.keys(event.tools).sort(), ["fork", "join", "js-manager", "js-review", "question"]);
  assert.equal(event.system.length, 1);
  assert.equal(event.system[0].text, DIRECTOR_MESSAGE);
  // The host's own shape: the pair is a `tool-call` part whose id carries the
  // hint prefix, not the hint's internal `toolName` key.
  const hint = event.messages.filter((message) =>
    message.content.some((part) => part.type === "tool-call" && String(part.id).startsWith("pair-hint:")),
  );
  assert.equal(hint.length, 1);
  const before = event.messages.length;
  // `event.messages` already carries the pair (the hook above placed it), so
  // re-entering with that same copy is a REPLAY: the copy carries this epoch's
  // pair and the bytes match. The mode is a label — both modes send identical
  // bytes — and `replayed` is the honest one here.
  const first = await host.context[0]({ ...event, messages: [...event.messages] });
  assert.equal(first.hint, "replayed");
  // A request that does NOT carry the pair (the host rebuilt its own history and
  // never stored ours) is placed at the recorded position, once.
  const rebuilt = event.messages.filter(
    (message) => !message.content.some((part) => part.type === "tool-call" && String(part.id).startsWith("pair-hint:")),
  );
  const again = rebuilt.concat([{ role: "assistant", content: [{ type: "text", text: "ok" }] }]);
  // The hook mutates the event it is given, so `again` is the copy the provider
  // would receive once the call returns.
  const rebuiltEvent = { ...event, messages: again };
  const sent = await host.context[0](rebuiltEvent);
  assert.equal(sent.hint, "placed");
  const pairs = rebuiltEvent.messages.filter((message) =>
    message.content.some((part) => part.type === "tool-call" && String(part.id).startsWith("pair-hint:")),
  );
  assert.equal(pairs.length, 1, "one pair, never two");
  await host.cleanup();
});

test("a host agent the adapter does not own is left alone", async () => {
  const host = await fakeHost();
  const event = {
    sessionID: "ses_other",
    agent: "build",
    tools: { bash: {} },
    system: [],
    messages: [{ role: "user", content: [{ type: "text", text: "hi" }] }],
  };
  await host.context[0](event);
  assert.deepEqual(Object.keys(event.tools), ["bash"]);
  assert.equal(event.system.length, 0);
  await host.cleanup();
});

test("tool descriptions and surfaces agree with compose", async () => {
  const host = await fakeHost();
  for (const [tool, surface] of [
    ["js-manager", "js-manager"],
    ["js-worker", "js-worker"],
    ["js-review", "js-review"],
  ]) {
    const description = host.tools.get(tool).description;
    // §3.1: the description names each capability in its one spelling.
    const spellingOf = (member) => (["fork", "join", "review"].includes(member) ? member : `this.${member}`);
    for (const member of SURFACE_MEMBERS[surface]) {
      assert.ok(description.includes(`\`${spellingOf(member)}\``), `${tool} describes ${member} as ${spellingOf(member)}`);
    }
  }
  await host.cleanup();
});

test("host facts and open gaps are data, not prose", async () => {
  const facts = hostFacts();
  assert.equal(facts.pluginApi.id, "herdchestra");
  assert.equal(facts.delivery.default, "steer");
  assert.equal(facts.delivery.fork, "queue");
  for (const gap of openGaps()) {
    assert.equal(typeof gap.id, "string");
    assert.equal(typeof gap.question, "string");
    assert.ok(Array.isArray(gap.blocks));
  }
});

test("three class programs from the spec return 42", async () => {
  const host = await fakeHost();
  await fs.writeFile(path.join(host.dir, "a.txt"), "x\n");
  const samples = [
    `class Js extends JsProgram {
  async run() {
    const spans = this.whereAll(await this.cat("a.txt"), "x");
    return 40 + spans.length + 1;
  }
}`,
    `class Js extends JsProgram {
  async run() {
    const listed = await this.call("printf 2");
    const text = await this.cat("a.txt");
    return 40 + Number(listed.stdout) + (text.length > 0 ? 0 : 1);
  }
}`,
    `class Js extends JsProgram {
  async run() {
    this.write("c.txt", "y");
    // The atomic rename lands on the next event-loop tick; the program reads
    // the file back through the same surface after that.
    await new Promise((resolve) => setTimeout(resolve, 20));
    const info = await this.stat("c.txt");
    return 41 + (info.exists ? 1 : 0);
  }
}`,
  ];
  for (const code of samples) {
    const out = await host.call("js-worker", { code });
    assert.equal(out.state, "completed", JSON.stringify(out.error));
    assert.equal(out.value, 42);
  }
  await host.cleanup();
});

test("the adapter's state and the Pair Hint journal never share a file", async () => {
  const host = await fakeHost();
  const event = {
    sessionID: "ses_main",
    agent: "Manager",
    tools: { "js-manager": {} },
    system: [],
    messages: [{ role: "user", content: [{ type: "text", text: "hello" }] }],
  };
  await host.context[0](event);
  // The host rebuilds the request without the pair it never stored.
  const again = {
    ...event,
    messages: [
      { role: "user", content: [{ type: "text", text: "hello" }] },
      { role: "assistant", content: [{ type: "text", text: "ok" }] },
    ],
  };
  const result = await host.context[0](again);
  // The pair is placed again in the copy, at the position the fact records:
  // the host rebuilt the request without it (the host never stored it), so the
  // hint places it rather than replaying a copy that does not carry it.
  assert.equal(result.hint, "placed", "the pair is placed at the recorded position, once");
  const pairs = again.messages.filter((message) =>
    message.content.some((part) => part.type === "tool-call" && String(part.id).startsWith("pair-hint:")),
  );
  assert.equal(pairs.length, 1, "one pair, never two");
  const files = (await fs.readdir(path.join(host.dir, ".Herdchestra"))).sort();
  assert.equal(files.length, 2, JSON.stringify(files));
  assert.ok(files.includes("ses_main.jsonl"), JSON.stringify(files));
  const hintFile = files.find((name) => name !== "ses_main.jsonl");
  assert.match(hintFile, /^hint-[0-9a-f]{64}\.jsonl$/, "the hint journal has its own, hashed name");
  const state = JSON.parse((await fs.readFile(path.join(host.dir, ".Herdchestra", "ses_main.jsonl"), "utf8")).split("\n")[0]);
  const hint = JSON.parse((await fs.readFile(path.join(host.dir, ".Herdchestra", hintFile), "utf8")).split("\n")[0]);
  assert.equal(state.type, "language", "the state file holds adapter facts only");
  assert.equal(hint.kind, "bind", "the hint file holds presentation facts only");
  await host.cleanup();
});

// ---------------------------------------------------------------------------
// `js-review` evaluation window (根 §2.3)
// ---------------------------------------------------------------------------

test("js-review runs only inside an open evaluation round, and only for the Manager", async () => {
  const host = await fakeHost();
  const code = "class Js extends JsProgram { async run() { return 1; } }";
  // A Manager's program call is a request: it opens the round.
  const manager = await host.call("js-manager", { code: "class Js extends JsProgram { async run() { return 0; } }" }, { agent: "Manager" });
  assert.equal(manager.value, 0);
  const opened = await host.call("js-review", { code }, { agent: "Manager" });
  assert.equal(opened.value, 1, "an open round serves the read-only face");
  // The round's first accepted review shuts it, and the next call is refused
  // with the reason, not with a TypeError over a missing member.
  const reviewed = await host.call("js-manager", { code: "class Js extends JsProgram { async run() { return review([{ key: \"a\", value: \"x\" }]); } }" }, { agent: "Manager" });
  assert.equal(reviewed.value.ok, true);
  const closed = await host.call("js-review", { code }, { agent: "Manager" });
  assert.equal(closed.rejection, "REVIEW_WINDOW_CLOSED");
  // A worker never runs it, whatever the round says. The role refusal is a
  // thrown error here (the executor refuses before it produces a receipt), and
  // it never names the window: a worker has no evaluation to lose.
  await assert.rejects(() => host.call("js-review", { code }, { agent: "coder" }), /ROLE_DENIED/);
});

test("a refused review leaves the evaluation window open", async () => {
  const host = await fakeHost();
  const code = "class Js extends JsProgram { async run() { return 2; } }";
  // A Manager session at `execute` whose review the ledger refuses: an empty
  // ledger on a successor tenure cannot be built here, so the refused receipt
  // is produced by the host's own relay refusal (this host has no atomic
  // hand-over) — the ledger records the obligation first, so the case that
  // matters is the empty one below.
  const first = await host.call("js-manager", { code: "class Js extends JsProgram { async run() { return 0; } }" }, { agent: "Manager" });
  assert.equal(first.value, 0);
  // An accepted review shuts the face…
  const accepted = await host.call("js-manager", { code: "class Js extends JsProgram { async run() { return review([{ key: \"a\", value: \"x\" }]); } }" }, { agent: "Manager" });
  assert.equal(accepted.value.ok, true);
  assert.equal((await host.call("js-review", { code }, { agent: "Manager" })).rejection, "REVIEW_WINDOW_CLOSED");
  // …and the next request is a new round, which opens it again.
  const again = await host.call("js-manager", { code: "class Js extends JsProgram { async run() { return 0; } }" }, { agent: "Manager" });
  assert.equal(again.value, 0);
  assert.equal((await host.call("js-review", { code }, { agent: "Manager" })).value, 2);
});

// ---------------------------------------------------------------------------
// One `js-worker`, two identities (§2.1, §7.1)
// ---------------------------------------------------------------------------

// §2.1: `coder` and `devops` are the same worker face; they differ only in the
// brief they are given. The measured defect was a `devops` call to `js-worker`
// answering `ROLE_DENIED` with `tool: "coder"`: the registration had fixed one
// identity, and the executor's equality check refused the other. These tests
// call the SAME registered tool as both identities and compare what they get.
test("js-worker runs under both worker identities, with the same members", async () => {
  const host = await fakeHost();
  // One spelling per capability (§3.1): a member is probed as `this.xxx`, a
  // top-level primitive as a bare name.
  const PRIMITIVES = ["fork", "join", "review"];
  const spellingOf = (member) => (PRIMITIVES.includes(member) ? member : `this.${member}`);
  const memberProbe = (member) => {
    const spelling = spellingOf(member);
    return `return typeof ${spelling} === "function" ? "${member}:yes" : "${member}:no";`;
  };

  const seen = {};
  for (const agent of ["coder", "devops"]) {
    const answers = [];
    for (const member of SURFACE_MEMBERS["js-worker"]) {
      // `call` would need a command port and `fission` an admitted origin;
      // every member is still probed for presence, which is what the face
      // promises, without running work that belongs to another test.
      const out = await host.call("js-worker", { code: memberProbe(member) }, { agent, messageID: `probe_${agent}_${member}` });
      assert.notEqual(out?.rejection, "ROLE_DENIED", `${agent} was refused js-worker: ${JSON.stringify(out)}`);
      answers.push(out.value);
    }
    seen[agent] = answers;
  }
  // The two identities get the identical member set: no member is reserved to
  // one of them, and none is missing for the other.
  assert.deepEqual(seen.coder, seen.devops);
  assert.equal(seen.coder.length, SURFACE_MEMBERS["js-worker"].length);
  for (const answer of seen.coder) assert.match(answer, /:yes$/, `a worker member was absent: ${answer}`);
  await host.cleanup();
});

test("property: neither worker identity is ever refused ROLE_DENIED on js-worker", async () => {
  const fc = await import("fast-check");
  const host = await fakeHost();
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom("coder", "devops"),
      fc.integer({ min: 0, max: 1000 }),
      fc.constantFrom("cat", "write", "where", "call", "fission", "fork", "join", "park", "unpark"),
      async (agent, value, member) => {
        // §3.1: the probe uses the one spelling the member has.
        const spelling = ["fork", "join", "review"].includes(member) ? member : `this.${member}`;
        const out = await host.call(
          "js-worker",
          { code: `return typeof ${spelling} === "function" ? ${value} : -1;` },
          { agent, messageID: `p_${agent}_${member}_${value}` },
        );
        assert.notEqual(out?.rejection, "ROLE_DENIED");
        assert.equal(out?.value, value, `${agent}/${member}`);
      },
    ),
    { ...fcRuns(60) },
  );
  await host.cleanup();
});

test("js-worker is refused for a role that is not a worker identity", async () => {
  const host = await fakeHost();
  // Manager and Plan own their own faces; `js-worker` is not theirs. The refusal
  // is layer two (`execute.before`), which throws rather than returning a
  // receipt: the host records the reason and no program is created.
  for (const agent of ["Manager", "Plan"]) {
    await assert.rejects(
      () => host.call("js-worker", { code: "return 1;" }, { agent, messageID: `m_${agent}` }),
      /ROLE_DENIED/,
      agent,
    );
  }
  await host.cleanup();
});

// The host's `Agent.Info` is a Schema class: a field it declares is an accessor,
// and assigning to one throws `TypeError: Attempted to assign to readonly
// property.` The throw lands inside the host's transform runner, which then
// disables whichever plugins are still in the chain — live, `opencode.config.agent`
// and `opencode.browser` (2026-11-01). These cases pin the two things that
// matter: no throw, and the fields really land.
const readonlyAgent = (id, { mode = "primary", hidden = false } = {}) => {
  const values = { id, name: id, description: "", mode, hidden, permissions: [{ action: "*", resource: "*", effect: "allow" }] };
  const agent = {};
  for (const [key, value] of Object.entries(values)) {
    Object.defineProperty(agent, key, {
      enumerable: true,
      configurable: false,
      get: () => value,
      set: () => {
        throw new TypeError("Attempted to assign to readonly property.");
      },
    });
  }
  return agent;
};

test("the agent transform never assigns to a host-owned field", async () => {
  const agents = ["Manager", "Plan", "coder", "devops", "build", "general"].map((id) => readonlyAgent(id));
  const seen = new Map();
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "herdchestra-ro-"));
  const ctx = {
    location: { directory: dir },
    options: {},
    agent: {
      transform: async (callback) => {
        callback({
          list: () => agents,
          update: (id, update) => {
            const agent = agents.find((candidate) => candidate.id === id);
            assert.ok(agent, `unknown agent ${id}`);
            update(agent);
            seen.set(id, agent);
          },
        });
      },
    },
    tool: { transform: async () => {}, hook: async () => {} },
    session: { hook: async () => {} },
    event: { subscribe: () => ({ [Symbol.asyncIterator]: () => ({ next: async () => ({ done: true }) }) }) },
  };
  // The old code threw here and the host blamed other plugins for it.
  const cleanup = await setup(ctx);

  // Nothing threw, and the refusals are named rather than swallowed: a host
  // object that refuses a scalar write leaves the host's own value standing and
  // says so, which is a gap a reader can act on instead of a transform failure
  // that disables `opencode.config.agent` and `opencode.browser`.
  const refused = cleanup.readonlyAgentFields ?? [];
  for (const id of ["Manager", "Plan", "coder", "devops"]) {
    assert.ok(
      refused.includes(`${id}.mode`),
      `${id}.mode must be reported when the host refuses the write (got ${refused.join(",") || "nothing"})`,
    );
    // The host's own value stands; the adapter did not pretend to change it.
    assert.equal(seen.get(id).mode, "primary");
  }

  // A list is never refused: it is emptied and refilled in place, so the host's
  // own array object now holds the adapter's rules.
  assert.deepEqual(seen.get("coder").permissions, [
    { action: "*", resource: "*", effect: "deny" },
    { action: "js-worker", resource: "*", effect: "allow" },
    { action: "question", resource: "*", effect: "allow" },
  ]);
  await cleanup();
  await fs.rm(dir, { recursive: true, force: true });
});
