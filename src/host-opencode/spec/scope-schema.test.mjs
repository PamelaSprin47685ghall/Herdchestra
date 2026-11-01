// The model-visible `scope` schema against the book that decides (§6.2).
//
// The real host decodes a tool call's arguments against the tool's input schema
// BEFORE `execute.before` runs, and that hook receives the raw, undecoded input.
// So the schema and the book both see the annotation, in that order. Two things
// must hold, and this file pins both:
//
//   1. The schema is never narrower than the parser. Any annotation
//      `parseAnnotation` accepts must decode. A schema that refuses a legal
//      annotation makes a compliant call impossible — that is the defect this
//      file exists for, and its first live instance was the opposite mistake.
//   2. A `resetScope` with no goal or no budget must be refused by the schema
//      with a message naming the missing field. On the real host (live6,
//      2026-11-01) the model wrote `{"kind":"resetScope"}` — the only reading a
//      free-form `{ type: "object" }` permits — and every call died as
//      `SCOPE_REFUSED` before the model could learn what was missing.
//
// The codec is the host's own (`packages/core/src/tool/runtime.ts` `jsonSchema`),
// built the same way, so a schema keyword the host cannot compile fails here
// rather than silently disabling validation in production.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";
import { Effect, JsonSchema, Schema, SchemaRepresentation } from "effect";

import { TOOL_INPUTS, SCOPE_MEMBER } from "./tool-inputs.mjs";
import { SCOPE_SCHEMA, SCOPE_DESCRIPTION } from "./scope-schema.mjs";
import { TOOL_DESCRIPTIONS } from "./tool-descriptions.mjs";
import { parseAnnotation } from "../../core/scope/annotation.mjs";
import { SCOPE_KINDS, SCOPE_KIND_VALUES, isScopeRefusal } from "../../core/scope/errors.mjs";
import { fcRuns } from "../../core/test-runs.mjs";

// The codec the host builds from a tool's JSON Schema. `Cache` in the host
// swallows a compile error into `undefined` (no validation at all), so this
// builds it without the wrapper: a schema that cannot compile must fail loudly.
const codecOf = (schema) => {
  const draft =
    (typeof schema.$schema === "string" && schema.$schema.includes("draft-07")) || "definitions" in schema
      ? JsonSchema.fromSchemaDraft07(schema)
      : JsonSchema.fromSchemaDraft2020_12(schema);
  return Schema.make(SchemaRepresentation.fromJsonSchemaDocument(draft).ast);
};

const decode = (codec, value) =>
  Effect.runPromise(
    Effect.match(Schema.decodeUnknownEffect(codec)(value), {
      onFailure: (error) => ({ ok: false, message: String(error) }),
      onSuccess: (decoded) => ({ ok: true, decoded }),
    }),
  );

// Each tool's scope codec, compiled once.
const CODECS = Object.fromEntries(
  Object.keys(TOOL_INPUTS).map((name) => [name, codecOf(TOOL_INPUTS[name])]),
);

// A call carrying this annotation, with whatever the tool's own required fields
// are. The scope schema is what is under test, not the other properties.
const CALL = {
  "js-manager": (scope) => ({ code: "return 1", [SCOPE_MEMBER]: scope }),
  "js-worker": (scope) => ({ code: "return 1", [SCOPE_MEMBER]: scope }),
  "js-review": (scope) => ({ code: "return 1", [SCOPE_MEMBER]: scope }),
  plan: (scope) => ({ code: "return 1", [SCOPE_MEMBER]: scope }),
  fork: (scope) => ({ name: "w", brief: "role: coder — b", [SCOPE_MEMBER]: scope }),
  join: (scope) => ({ [SCOPE_MEMBER]: scope }),
};

// ------------------------------------------------------- the schema compiles

test("every tool's input schema compiles with the host's own codec", () => {
  for (const [name, schema] of Object.entries(TOOL_INPUTS)) {
    assert.doesNotThrow(() => codecOf(schema), `${name} input schema must compile`);
  }
});

// `pattern` is the keyword the host cannot compile: its importer defaults to
// `patterns: "error"`, and the host turns that throw into "no validation".
// Nothing in the scope schema may use it.
test("the scope schema uses no keyword the host codec rejects", () => {
  const keywords = new Set();
  const walk = (node) => {
    if (Array.isArray(node)) return node.forEach(walk);
    if (node === null || typeof node !== "object") return;
    for (const [key, value] of Object.entries(node)) {
      keywords.add(key);
      walk(value);
    }
  };
  walk(SCOPE_SCHEMA);
  assert.equal(keywords.has("pattern"), false, "a pattern would disable the host's validation");
  assert.equal(keywords.has("patternProperties"), false);
});

// ------------------------------------------------- the schema is not narrower

// An annotation the parser accepts, in the model's own raw shape.
const ACCEPTED = fc.oneof(
  fc.record({
    kind: fc.constant(SCOPE_KINDS.RESET),
    newScopeGoal: fc.string({ minLength: 1 }).filter((goal) => goal.trim().length > 0),
    newScopeRetentionBudget: fc.integer({ min: 1, max: 1_000_000 }),
  }),
  fc.constant({ kind: SCOPE_KINDS.KEEP }),
  fc.record({
    kind: fc.constant(SCOPE_KINDS.REMOVE),
    replacement: fc.string({ minLength: 1 }).filter((text) => text.trim().length > 0),
  }),
  fc.constant({ kind: SCOPE_KINDS.METOO }),
);

test("every annotation the book accepts decodes under the schema", () => {
  fc.assert(
    fc.property(ACCEPTED, fc.constantFrom(...Object.keys(CODECS)), (annotation, tool) => {
      const parsed = parseAnnotation(annotation);
      // The parser answers a frozen record on success and a refusal on failure.
      assert.equal(
        isScopeRefusal(parsed),
        false,
        `the book accepts ${JSON.stringify(annotation)}: ${parsed.code ?? ""}`,
      );
      const result = Effect.runSync(
        Effect.match(Schema.decodeUnknownEffect(CODECS[tool])(CALL[tool](annotation)), {
          onFailure: (error) => ({ ok: false, message: String(error) }),
          onSuccess: () => ({ ok: true }),
        }),
      );
      assert.equal(result.ok, true, `${tool} must decode ${JSON.stringify(annotation)}: ${result.message}`);
    }),
    { ...fcRuns(200) },
  );
});

// ---------------------------------------------- the defect that started this

test("a resetScope without its fields is refused, naming what is missing", async () => {
  for (const [tool, codec] of Object.entries(CODECS)) {
    // The exact live6 shape: the model wrote the kind and nothing else.
    const bare = await decode(codec, CALL[tool]({ kind: SCOPE_KINDS.RESET }));
    assert.equal(bare.ok, false, `${tool} must refuse a bare resetScope`);
    assert.match(bare.message, /newScopeGoal/, `${tool} must name the missing field`);

    const noBudget = await decode(
      codec,
      CALL[tool]({ kind: SCOPE_KINDS.RESET, newScopeGoal: "g" }),
    );
    assert.equal(noBudget.ok, false, `${tool} must refuse a resetScope with no budget`);
    assert.match(noBudget.message, /newScopeRetentionBudget/);
  }
});

test("a resetScope whose budget is not a positive integer is refused", async () => {
  for (const [tool, codec] of Object.entries(CODECS)) {
    for (const budget of [0, -1, 1.5, "5", null]) {
      const result = await decode(
        codec,
        CALL[tool]({ kind: SCOPE_KINDS.RESET, newScopeGoal: "g", newScopeRetentionBudget: budget }),
      );
      assert.equal(result.ok, false, `${tool} must refuse budget ${JSON.stringify(budget)}`);
    }
  }
});

test("an unknown kind is refused by the schema, not by the book", async () => {
  for (const [tool, codec] of Object.entries(CODECS)) {
    const result = await decode(codec, CALL[tool]({ kind: "keep" }));
    assert.equal(result.ok, false, `${tool} must refuse an unknown kind`);
  }
});

// ------------------------------------------------- the schema describes the book

test("the schema's kinds are exactly the book's kinds", () => {
  const declared = SCOPE_SCHEMA.oneOf.map((branch) => branch.properties.kind.enum[0]).sort();
  assert.deepEqual(declared, [...SCOPE_KIND_VALUES].sort());
});

test("the schema requires exactly the fields the parser requires", () => {
  const requiredByKind = Object.fromEntries(
    SCOPE_SCHEMA.oneOf.map((branch) => [branch.properties.kind.enum[0], [...branch.required].sort()]),
  );
  assert.deepEqual(requiredByKind[SCOPE_KINDS.RESET], ["kind", "newScopeGoal", "newScopeRetentionBudget"]);
  assert.deepEqual(requiredByKind[SCOPE_KINDS.REMOVE], ["kind", "replacement"]);
  assert.deepEqual(requiredByKind[SCOPE_KINDS.KEEP], ["kind"]);
  assert.deepEqual(requiredByKind[SCOPE_KINDS.METOO], ["kind"]);
});

// The model reads the description before the schema. It must show the shape
// that runs, not merely name the kinds.
test("the model-facing description shows a complete resetScope", () => {
  assert.match(SCOPE_DESCRIPTION, /newScopeGoal/);
  assert.match(SCOPE_DESCRIPTION, /newScopeRetentionBudget/);
  assert.match(SCOPE_DESCRIPTION, /resetScope/);
  for (const kind of SCOPE_KIND_VALUES) {
    assert.ok(SCOPE_DESCRIPTION.includes(kind), `the description names ${kind}`);
  }
  for (const [name, description] of Object.entries(TOOL_DESCRIPTIONS)) {
    assert.match(description, /newScopeGoal/, `${name}'s description must show a complete resetScope`);
    assert.match(description, /newScopeRetentionBudget/);
  }
});

// Every tool declares the same annotation: a tool that drifted would refuse the
// very shape its siblings accept.
test("every tool declares the same scope schema", () => {
  for (const [name, schema] of Object.entries(TOOL_INPUTS)) {
    assert.equal(schema.properties[SCOPE_MEMBER], SCOPE_SCHEMA, `${name} uses the shared scope schema`);
  }
});

// ------------------------------------------------- fork's model-facing shape

// Root §4.1 gives `fork` two arguments. The schema the model writes against must
// not offer a third: a `role` property would be a channel the adapter does not
// read (the identity comes from the brief), and a `required` entry for it would
// make the compliant call impossible. The same rule holds for `join`, which
// takes nothing but `scope`.
test("fork's schema offers name and brief only; the identity lives in the brief", () => {
  const fork = TOOL_INPUTS.fork;
  assert.deepEqual(Object.keys(fork.properties).sort(), [SCOPE_MEMBER, "brief", "name"].sort());
  assert.equal(fork.properties.role, undefined, "no `role` property: §4.1 has two arguments");
  assert.deepEqual([...fork.required].sort(), [SCOPE_MEMBER, "brief", "name"].filter((n) => n !== SCOPE_MEMBER).sort());
  assert.equal(fork.required.includes("role"), false);
  // The brief is where the identity is declared, so the description must say so;
  // otherwise the model has no way to learn the rule that refuses its call.
  assert.match(fork.properties.brief.description, /role: coder/);
  assert.match(fork.properties.brief.description, /role: devops/);
  assert.match(TOOL_DESCRIPTIONS.fork, /role: coder/);
  assert.equal(/`role` is coder/.test(TOOL_DESCRIPTIONS.fork), false, "no separate `role` argument is described");
});

test("a fork call without a declared identity still compiles and is refused by the brief, not the schema", () => {
  // The schema cannot decide an identity (it is text inside `brief`), so it must
  // accept the call and let the adapter refuse it with the reason that names the
  // rule. A schema that required `role` would answer "invalid arguments" instead.
  const call = { name: "w1", brief: "do the thing" };
  for (const key of TOOL_INPUTS.fork.required) assert.ok(key in call || key === SCOPE_MEMBER, `required ${key} is writable`);
  assert.doesNotThrow(() => codecOf(TOOL_INPUTS.fork));
});
