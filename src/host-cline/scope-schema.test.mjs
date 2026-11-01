// The tool schemas of the Cline adapter against the Scope book (spec §6.2, §7.2).
//
// The defect this file pins: a tool declared `additionalProperties: false`
// without `scope` cannot receive the annotation §6.2 requires, so the host's own
// argument validation refuses the call before `beforeTool` can admit it. The
// schema and the book must agree on one language: everything the schema lets
// through is an annotation the book parses, and every annotation the book
// accepts can be written against the schema.
//
// The `validate` below is the JSON Schema subset the adapter's schemas use
// (`type`, `enum`, `minLength`, `pattern`, `minimum`, `properties`, `required`,
// `additionalProperties`, `oneOf`). It is deliberately strict about unknown keys
// so a schema that is not closed fails here rather than at the host.

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import fc from "fast-check";

import { createPlugin } from "./index.mjs";
import { SCOPE_SCHEMA, withScope } from "./scope-schema.mjs";
import { SCOPE_CODES, SCOPE_KINDS, parseAnnotation } from "../core/scope/index.mjs";
import { fcRuns } from "../core/test-runs.mjs";

const NAMES = ["js-manager", "js-worker", "js-plan", "js-review", "fork", "join"];

async function registered(t) {
  const dir = await mkdtemp(join(tmpdir(), "herdchestra-cline-schema-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const plugin = createPlugin({ role: "manager" });
  const tools = new Map();
  plugin.setup(
    {
      registerTool: (tool) => tools.set(tool.name, tool),
      registerRule() {},
      registerMessageBuilder() {},
    },
    { cwd: dir, session: { sessionId: "schema-test" } },
  );
  return tools;
}

// Returns the list of violations; empty means the value validates.
const validate = (schema, value, path = "$") => {
  const errors = [];
  if (schema.type === "object") {
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
      return [`${path} is not an object`];
    }
    for (const key of schema.required ?? []) {
      if (!(key in value)) errors.push(`${path}.${key} is required`);
    }
    for (const [key, child] of Object.entries(value)) {
      const property = schema.properties?.[key];
      if (property === undefined) {
        if (schema.additionalProperties === false) errors.push(`${path}.${key} is not accepted`);
        continue;
      }
      errors.push(...validate(property, child, `${path}.${key}`));
    }
  }
  if (schema.type === "string") {
    if (typeof value !== "string") return [`${path} is not a string`];
    if (schema.minLength !== undefined && value.length < schema.minLength) {
      errors.push(`${path} is shorter than ${schema.minLength}`);
    }
    if (schema.pattern !== undefined && !new RegExp(schema.pattern, "u").test(value)) {
      errors.push(`${path} does not match ${schema.pattern}`);
    }
  }
  if (schema.type === "integer") {
    if (!Number.isInteger(value)) return [`${path} is not an integer`];
    if (schema.minimum !== undefined && value < schema.minimum) {
      errors.push(`${path} is below ${schema.minimum}`);
    }
  }
  if (schema.enum !== undefined && !schema.enum.includes(value)) {
    errors.push(`${path} is not one of ${schema.enum.join(", ")}`);
  }
  if (schema.oneOf !== undefined) {
    const branches = schema.oneOf.filter((branch) => validate(branch, value, path).length === 0);
    if (branches.length !== 1) errors.push(`${path} matches ${branches.length} oneOf branches, expected 1`);
  }
  return errors;
};

const reset = (goal = "finish", budget = 3) => ({
  kind: SCOPE_KINDS.RESET,
  newScopeGoal: goal,
  newScopeRetentionBudget: budget,
});
const keep = { kind: SCOPE_KINDS.KEEP };
const meToo = { kind: SCOPE_KINDS.METOO };
const remove = (replacement = "lesson") => ({ kind: SCOPE_KINDS.REMOVE, replacement });

test("every tool declares `scope` in a closed object, so a compliant call is expressible", async (t) => {
  const tools = await registered(t);
  assert.deepEqual([...tools.keys()].sort(), [...NAMES].sort());
  for (const name of NAMES) {
    const schema = tools.get(name).inputSchema;
    assert.equal(schema.type, "object", name);
    assert.equal(schema.additionalProperties, false, `${name} stays closed`);
    assert.ok(schema.required.includes("scope"), `${name} requires scope`);
    assert.deepEqual(validate(schema, {}).filter((error) => error === "$.scope is required"), [
      "$.scope is required",
    ]);
  }
});

test("a first call with resetScope, and later calls with keepLast/removeLast/meToo, validate", async (t) => {
  const tools = await registered(t);
  const argumentsOf = {
    "js-manager": { code: "return 1" },
    "js-worker": { code: "return 1" },
    "js-plan": { code: "return 1" },
    "js-review": { code: "return 1" },
    fork: { name: "w", brief: "do the thing" },
    join: {},
  };
  for (const name of NAMES) {
    for (const scope of [reset(), keep, remove(), meToo]) {
      const value = { ...argumentsOf[name], scope };
      assert.deepEqual(validate(tools.get(name).inputSchema, value), [], `${name} ${scope.kind}`);
    }
  }
});

test("the schema and the book accept the same annotations (property)", () => {
  const field = fc.oneof(
    fc.constant("goal"),
    fc.constant(""),
    fc.constant("   "),
    fc.string({ maxLength: 8 }),
    fc.integer({ min: -2, max: 5 }),
    fc.double(),
    fc.constant(null),
    fc.boolean(),
  );
  const annotation = fc.oneof(
    fc.record({
      kind: fc.constantFrom(...Object.values(SCOPE_KINDS)),
      newScopeGoal: field,
      newScopeRetentionBudget: field,
      replacement: field,
    }),
    fc.record({ kind: fc.constantFrom(...Object.values(SCOPE_KINDS)), replacement: field }),
    fc.record({ kind: fc.constantFrom(...Object.values(SCOPE_KINDS)) }),
    fc.record({ kind: fc.string({ maxLength: 12 }), replacement: field }),
    fc.record({ kind: fc.constantFrom(...Object.values(SCOPE_KINDS)), stray: field }),
    fc.constant(null),
    fc.array(field, { maxLength: 3 }),
  );

  fc.assert(
    fc.property(annotation, (raw) => {
      const schemaAccepts = validate(SCOPE_SCHEMA, raw).length === 0;
      const parsed = parseAnnotation(raw);
      // The parser is total: a refusal is a value, never a throw.
      if (parsed.ok === false) {
        assert.equal(schemaAccepts, false, `schema accepted what the book refused: ${JSON.stringify(raw)}`);
        return;
      }
      // Everything the book admits must be writable against the schema. The
      // parser trims blank strings; a `resetScope` with a blank goal is refused,
      // so the surviving records are exactly the schema's language.
      assert.equal(schemaAccepts, true, `schema refused what the book accepted: ${JSON.stringify(raw)}`);
    }),
    { ...fcRuns(300) },
  );
});

test("a stray field on any kind is refused by the schema and by the book", async (t) => {
  const tools = await registered(t);
  const cases = [
    { kind: SCOPE_KINDS.KEEP, replacement: "x" },
    { kind: SCOPE_KINDS.METOO, newScopeGoal: "x" },
    { kind: SCOPE_KINDS.REMOVE, replacement: "x", newScopeGoal: "y" },
    { kind: SCOPE_KINDS.RESET, newScopeGoal: "g", newScopeRetentionBudget: 2, replacement: "x" },
  ];
  for (const scope of cases) {
    assert.notDeepEqual(validate(tools.get("js-manager").inputSchema, { code: "return 1", scope }), []);
    const parsed = parseAnnotation(scope);
    assert.equal(parsed.ok, false);
    assert.equal(parsed.code, SCOPE_CODES.SCOPE_FIELD_UNEXPECTED);
  }
});

test("a scope outside the four kinds is refused by both", async (t) => {
  const tools = await registered(t);
  for (const kind of ["reset", "keep", "", null, 3]) {
    const scope = { kind };
    assert.notDeepEqual(validate(tools.get("js-manager").inputSchema, { code: "return 1", scope }), [], String(kind));
    const parsed = parseAnnotation(scope);
    assert.equal(parsed.ok, false);
    assert.equal(parsed.code, SCOPE_CODES.SCOPE_KIND_INVALID);
  }
});

test("withScope keeps the tool's own fields and adds nothing else", () => {
  const schema = withScope({ code: { type: "string" } });
  assert.deepEqual(Object.keys(schema.properties).sort(), ["code", "scope"]);
  assert.deepEqual([...schema.required].sort(), ["code", "scope"]);
  assert.equal(schema.additionalProperties, false);
  // Empty own properties: `join` takes only the annotation.
  const bare = withScope({});
  assert.deepEqual(Object.keys(bare.properties), ["scope"]);
  assert.deepEqual([...bare.required], ["scope"]);
});
