import test from "node:test";
import assert from "node:assert/strict";

import { directorPromptFor, DIRECTOR_MESSAGE } from "./director.mjs";
import { systemPromptFor, ROLES } from "./projection.mjs";

test("the director prompt is the Manager system prompt plus one director segment", () => {
  const prompt = directorPromptFor(systemPromptFor(ROLES.MANAGER));
  assert.ok(prompt.startsWith(systemPromptFor(ROLES.MANAGER)));
  assert.equal(prompt.split("<vibe-mode>").length, 2);
  assert.ok(prompt.endsWith("</vibe-mode>"));
});

test("the director message names the four manager tools and both worker roles", () => {
  for (const token of ["fork", "join", "review", "ask"]) {
    assert.ok(DIRECTOR_MESSAGE.includes(`\`${token}\``), token);
  }
  assert.ok(DIRECTOR_MESSAGE.includes("`devops`"));
  assert.ok(DIRECTOR_MESSAGE.includes("`coder`"));
  // coder's bash ban is a prompt constraint, not a removed tool (spec §2.2).
  assert.ok(DIRECTOR_MESSAGE.includes("工具面照常含 `call`"));
});
