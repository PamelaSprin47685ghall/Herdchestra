import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { COMMON, MANAGER, WORKER, PROMPT_FILES } from "./files.mjs";
import { systemPromptFor, ROLES, isWorkerRole, outsideProjection } from "./projection.mjs";
import { DIRECTOR_MESSAGE, directorPromptFor } from "./director.mjs";

// The root specification is the only source of truth for these three files.
// Reading it here, instead of transcribing its sentences a second time, means a
// root revision fails this test instead of silently disagreeing with the code.
const ROOT = fileURLToPath(new URL("../../../AGENTS.md", import.meta.url));

// The three ```text blocks of root §5, in document order: common, manager,
// worker (§5.1, §5.2, §5.3). §5 ends at the next `## ` heading.
const rootPromptBlocks = () => {
  const lines = readFileSync(ROOT, "utf8").split("\n");
  const start = lines.findIndex((line) => line.startsWith("## 5 "));
  assert.notEqual(start, -1, "root AGENTS.md must keep a `## 5` heading");
  const end = lines.findIndex((line, index) => index > start && line.startsWith("## "));
  assert.notEqual(end, -1, "root §5 must be followed by another `## ` heading");

  const blocks = [];
  for (let i = start; i < end; i++) {
    if (lines[i] !== "```text") continue;
    const body = [];
    let j = i + 1;
    while (j < end && lines[j] !== "```") body.push(lines[j++]);
    assert.ok(j < end, "root §5 has an unterminated ```text block");
    blocks.push(body.join("\n"));
    i = j;
  }
  assert.equal(blocks.length, 3, "root §5 must hold exactly the three files");
  return blocks;
};

test("the three files are byte-identical to root §5.1, §5.2 and §5.3", () => {
  const [common, manager, worker] = rootPromptBlocks();
  assert.equal(COMMON, common, "COMMON must be §5.1 verbatim");
  assert.equal(MANAGER, manager, "MANAGER must be §5.2 verbatim");
  assert.equal(WORKER, worker, "WORKER must be §5.3 verbatim");
});

test("the three files are continuous strings with no markers or branches", () => {
  for (const [name, text] of Object.entries(PROMPT_FILES)) {
    assert.equal(typeof text, "string", name);
    assert.ok(text.length > 0, name);
    // §5: no conditional markers, filter switches or template branches inside
    // any of the three files. Angle brackets may only appear in prose about
    // `AGENTS.md`; a role marker would open a line of its own.
    assert.doesNotMatch(text, /^\s*<[a-z-]+>\s*$/m, name);
    assert.doesNotMatch(text, /<role|<if |\{\{|\$\{/, name);
  }
});

test("the three files carry no duplicated paragraph between them", () => {
  const paragraphs = (text) => text.split("\n\n").map((part) => part.trim()).filter(Boolean);
  const common = new Set(paragraphs(COMMON));
  for (const [name, text] of [
    ["manager", MANAGER],
    ["worker", WORKER],
  ]) {
    for (const paragraph of paragraphs(text)) {
      assert.ok(!common.has(paragraph), `${name} must not repeat a common paragraph`);
    }
  }
  // §5.2 and §5.3 are different texts; the role files must not share a paragraph.
  for (const paragraph of paragraphs(MANAGER)) {
    assert.ok(!paragraphs(WORKER).includes(paragraph), "manager and worker must not share a paragraph");
  }
});

test("the two compositions are plain concatenation", () => {
  assert.equal(systemPromptFor(ROLES.MANAGER), COMMON + MANAGER);
  assert.equal(systemPromptFor(ROLES.CODER), COMMON + WORKER);
  assert.equal(systemPromptFor(ROLES.DEVOPS), COMMON + WORKER);
  // §5: coder and devops receive the byte-identical system prompt; identity is
  // carried by the director message and by the fork brief, not by this text.
  assert.equal(systemPromptFor(ROLES.CODER), systemPromptFor(ROLES.DEVOPS));
  assert.equal(isWorkerRole(ROLES.CODER), true);
  assert.equal(isWorkerRole(ROLES.DEVOPS), true);
  assert.equal(isWorkerRole(ROLES.MANAGER), false);
});

test("the worker composition states the yield rule and the manager composition does not", () => {
  const worker = systemPromptFor(ROLES.CODER);
  const manager = systemPromptFor(ROLES.MANAGER);
  const yieldRule = "Changes complete → yield; verification is main agent's job.";
  assert.ok(worker.includes(yieldRule), "worker must carry the yield rule");
  assert.ok(!manager.includes(yieldRule), "manager must not carry the worker yield rule");
  // §5.2's single integration pass belongs to the manager alone.
  assert.ok(manager.includes("Manager judges results; a single integration pass happens once all workers land"));
  assert.ok(!worker.includes("Manager judges results"));
});

test("Plan is refused, not composed", () => {
  assert.equal(outsideProjection("plan"), true);
  assert.throws(
    () => systemPromptFor("plan"),
    (error) => error.code === "PLAN_OUTSIDE_PROJECTION",
  );
});

test("an unknown role is refused", () => {
  assert.throws(
    () => systemPromptFor("planner"),
    (error) => error.code === "UNKNOWN_ROLE",
  );
});

test("the director message is a separate segment outside all three files", () => {
  assert.match(DIRECTOR_MESSAGE, /^<vibe-mode>\n/);
  assert.match(DIRECTOR_MESSAGE, /\n<\/vibe-mode>$/);
  for (const text of [COMMON, MANAGER, WORKER]) assert.ok(!text.includes("<vibe-mode>"));
  assert.equal(directorPromptFor("S"), `S\n\n${DIRECTOR_MESSAGE}`);
  // §5: the Manager prompt is common + manager, plus the director message once.
  const full = directorPromptFor(systemPromptFor(ROLES.MANAGER));
  assert.equal(full, COMMON + MANAGER + "\n\n" + DIRECTOR_MESSAGE);
});
