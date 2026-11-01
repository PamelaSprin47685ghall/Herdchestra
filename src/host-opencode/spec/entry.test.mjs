// The two entry shapes the host decodes, and the Plan prompt boundary (§7.1).
//
// `packages/core/src/plugin/module.ts` accepts `{ id, effect }` or
// `{ id, setup }`. Only the Effect shape reaches `session`, `tool` and `event`
// on v2.1.4, so the directory entry exports it; the Promise shape stays for
// tests and for a host that supplies a complete Promise context.
//
// `effect` is a declared dependency at the host's version (4.0.1), so the
// entries import normally. The dynamic load below only exists to turn a broken
// install into a named failure instead of an import-time crash of the suite.
//
// What this file checks is behaviour a caller can observe: the entry's shape
// and the Plan prompt boundary. Package wiring (manifest fields, lock files,
// file lists) is not asserted here; it is verified by packing and installing the
// tarball when the packaging changes.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";

import { GAPS, gap } from "./gaps.mjs";
import { SURFACE_MEMBERS } from "../../compose/program/surface.mjs";
import { PLAN_PROMPT } from "../../compose/prompt/plan.mjs";
import { systemPromptFor } from "../../compose/prompt/projection.mjs";

const loadEntry = async (specifier) => {
  try {
    return { module: await import(specifier) };
  } catch (error) {
    if (error?.code === "ERR_MODULE_NOT_FOUND") {
      throw new Error(
        `the adapter entry could not load (${error.message}); run \`npm install\` so the declared effect dependency is present`,
      );
    }
    throw error;
  }
};

test("the directory entry decodes as { id, effect } for the v2 host", async () => {
  const loaded = await loadEntry("../index.js");
  const { default: entry, effect, id, herdchestra } = loaded.module;
  assert.equal(entry.id, "herdchestra");
  assert.equal(typeof entry.effect, "function");
  assert.equal(id, "herdchestra");
  assert.equal(typeof effect, "function");
  assert.equal(herdchestra.id, "herdchestra");

  // The same shape from the explicit test entry: one implementation tree.
  const byPath = await loadEntry("../plugin.mjs");
  assert.equal(byPath.module.default.id, entry.id);
  assert.equal(typeof byPath.module.default.effect, "function");
  // The Promise entry still exists for a complete Promise context.
  assert.equal(typeof byPath.module.setup, "function");
});

test("Plan is outside the five-segment projection and carries its own prompt", () => {
  // §6: Plan is not `common + manager` and not `common + worker`; asking for a
  // composition is a wiring error, not an empty prompt.
  assert.throws(
    () => systemPromptFor("plan"),
    (error) => error.code === "PLAN_OUTSIDE_PROJECTION",
  );
  // Its role segment names only what §6 gives Plan, each in its one spelling
  // (§3.1): members as `this.xxx`, the primitive `review` as a bare name.
  assert.ok(PLAN_PROMPT.startsWith("<plan>"));
  const spellingOf = (member) => (["fork", "join", "review"].includes(member) ? member : `this.${member}`);
  for (const member of SURFACE_MEMBERS.plan) assert.ok(PLAN_PROMPT.includes(`\`${spellingOf(member)}\``), member);
  // `unpark` is NOT absent: `this.ask` is `park` + interrupt, and the operator's
  // answer only reaches the parked expression through `unpark` on this same
  // face (§2.2). The loop above already requires every member to be named, so
  // listing it here contradicted that.
  for (const absent of ["`glob`", "`grep`", "`call`", "`fork`", "`join`"]) {
    assert.equal(PLAN_PROMPT.includes(absent), false, absent);
  }
});

test("the gap list keeps every entry shaped as question, evidence and blocks", () => {
  assert.ok(GAPS.length > 0);
  for (const item of GAPS) {
    assert.equal(typeof item.id, "string");
    assert.equal(typeof item.question, "string");
    assert.equal(typeof item.evidence, "string");
    assert.equal(typeof item.consequence, "string");
    assert.ok(Array.isArray(item.blocks));
  }
  // The two facts the host investigation settled are recorded, not guessed.
  assert.ok(gap("PLAN_ENTRY").consequence.includes("Plan"));
  assert.ok(gap("RELAY_SESSION_CONTROL").consequence.includes("successor"));
});

test("the directory entry file exists where the host looks for it", async () => {
  const directory = path.resolve(import.meta.dirname, "..");
  const files = await fs.readdir(directory);
  assert.ok(files.includes("index.js"), "a local plugin directory resolves index or server");
});
