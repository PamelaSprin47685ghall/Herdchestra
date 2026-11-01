import test from "node:test";
import assert from "node:assert/strict";

import { PORT_METHODS, requirePorts, optionalPort } from "./ports.mjs";
import { COMPOSE_CODES, ComposeError, isComposeError, refuse } from "./errors.mjs";

test("every declared port method is checked", () => {
  for (const [name, methods] of Object.entries(PORT_METHODS)) {
    const port = Object.fromEntries(methods.map((method) => [method, async () => null]));
    assert.doesNotThrow(() => requirePorts({ [name]: port }, [name]), name);
  }
});

test("a missing port or method is refused at build time", () => {
  assert.throws(() => requirePorts({}, ["command"]), (error) => error.code === "MISSING_PORT");
  assert.throws(
    () => requirePorts({ command: {} }, ["command"]),
    (error) => error.code === "MISSING_PORT" && error.details.method === "run",
  );
  assert.throws(() => requirePorts({ command: {} }, ["nope"]), /unknown port/);
});

test("an optional port is null when absent and checked when present", () => {
  assert.equal(optionalPort({}, "fork"), null);
  assert.equal(optionalPort({ fork: { fork: async () => null } }, "fork") !== null, true);
  assert.throws(
    () => optionalPort({ fork: {} }, "fork"),
    (error) => error.code === "MISSING_PORT",
  );
});

test("a compose refusal carries its code", () => {
  const error = refuse(COMPOSE_CODES.UNKNOWN_ROLE, "bad role", { role: "x" });
  assert.ok(isComposeError(error));
  assert.ok(error instanceof ComposeError);
  assert.equal(error.code, "UNKNOWN_ROLE");
  assert.deepEqual(error.details, { role: "x" });
});
