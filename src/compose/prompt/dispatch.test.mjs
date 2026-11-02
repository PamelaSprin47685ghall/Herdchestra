import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import fc from "fast-check";

import { COMMON, MANAGER, WORKER } from "./files.mjs";
import { systemPromptFor } from "./projection.mjs";
import { DIRECTOR_MESSAGE, directorPromptFor } from "./director.mjs";
import { PLAN_PROMPT } from "./plan.mjs";
// The canonical role set, including `plan`. `projection.mjs` deliberately omits
// `plan` because Plan is outside the projection, so it cannot supply it here.
import { ROLES } from "../program/surface.mjs";
import { fcRuns } from "../../core/test-runs.mjs";

// Root §5.4 is the only source of truth for the director segment, exactly as
// §5.1-§5.3 are for the three files. `projection.test.mjs` stops at §5.4 so the
// three files stay three; this file owns the fourth part and reads it here, so a
// root revision fails a test instead of silently disagreeing with the code.
const ROOT = fileURLToPath(new URL("../../../AGENTS.md", import.meta.url));

const rootDirectorBlock = () => {
  const lines = readFileSync(ROOT, "utf8").split("\n");
  const start = lines.findIndex((line) => line.startsWith("### 5.4 "));
  assert.notEqual(start, -1, "root AGENTS.md must keep a `### 5.4 ` heading");
  const fence = lines.findIndex((line, index) => index > start && line === "```text");
  assert.notEqual(fence, -1, "root §5.4 must open a ```text block");
  const body = [];
  let j = fence + 1;
  while (j < lines.length && lines[j] !== "```") body.push(lines[j++]);
  assert.ok(j < lines.length, "root §5.4 has an unterminated ```text block");
  return body.join("\n");
};

// Which composition each role receives, checked through the seam that actually
// hands prompts to the host (`SessionState.systemPrompt`), not only through the
// composition functions. The bug this guards against was in that seam: a
// top-level session marked `coder` or `devops` used to receive
// `COMMON + MANAGER` plus the director message, because the dispatch asked
// "is this top-level?" instead of "which role is this?".

// `SessionState` writes its ledger under the project root; a temp root keeps the
// repository clean. `sessionId` is the host's id for the session, defaulted by
// `sessionIdOf` when absent; passing one proves the id cannot change the prompt.
const withSession = async (role, body, sessionId = undefined) => {
  const root = await mkdtemp(join(tmpdir(), "herdchestra-prompt-"));
  const previous = process.env.HERDCHESTRA_PROJECT_ROOT;
  process.env.HERDCHESTRA_PROJECT_ROOT = root;
  try {
    const { SessionState } = await import("../../host-cline/session.mjs");
    const ctx = sessionId === undefined ? {} : { session: { sessionId } };
    await body(new SessionState({ role, ctx }));
  } finally {
    if (previous === undefined) delete process.env.HERDCHESTRA_PROJECT_ROOT;
    else process.env.HERDCHESTRA_PROJECT_ROOT = previous;
    await rm(root, { recursive: true, force: true });
  }
};

test("the Manager session receives common + manager plus the director message", async () => {
  await withSession(ROLES.MANAGER, async (session) => {
    assert.equal(await session.systemPrompt(), directorPromptFor(COMMON + MANAGER));
  });
});

test("the director message is byte-identical to root §5.4", () => {
  assert.equal(DIRECTOR_MESSAGE, rootDirectorBlock());
});

test("a coder session receives common + worker, never the manager composition", async () => {
  await withSession(ROLES.CODER, async (session) => {
    const prompt = await session.systemPrompt();
    assert.equal(prompt, COMMON + WORKER);
    assert.equal(prompt, systemPromptFor(ROLES.CODER));
    // The director segment belongs to the Manager alone.
    assert.equal(prompt.includes("<vibe-mode>"), false);
    assert.equal(prompt.includes(MANAGER), false);
  });
});

test("a devops session receives common + worker, byte-identical to a coder's", async () => {
  await withSession(ROLES.DEVOPS, async (session) => {
    const prompt = await session.systemPrompt();
    assert.equal(prompt, COMMON + WORKER);
    assert.equal(prompt, systemPromptFor(ROLES.CODER));
    assert.equal(prompt.includes("<vibe-mode>"), false);
  });
});

test("a Plan session receives its own role segment and nothing else", async () => {
  await withSession(ROLES.PLAN, async (session) => {
    const prompt = await session.systemPrompt();
    assert.equal(prompt, PLAN_PROMPT);
    assert.equal(prompt.includes("<vibe-mode>"), false);
  });
});

test("only the Manager carries the director message, for every role", async () => {
  for (const role of [ROLES.CODER, ROLES.DEVOPS, ROLES.PLAN]) {
    await withSession(role, async (session) => {
      const prompt = await session.systemPrompt();
      assert.equal(prompt.includes(DIRECTOR_MESSAGE), false, `${role} must not carry the director message`);
    });
  }
});

test("the prompt is returned once per session, then null", async () => {
  await withSession(ROLES.CODER, async (session) => {
    assert.equal(await session.systemPrompt(), COMMON + WORKER);
    // The host keeps what a request carried; returning it again would repeat it.
    assert.equal(await session.systemPrompt(), null);
  });
});

// The composition each role must receive, as data. A property test over this
// table states the invariant once: the role alone decides the prompt.
const EXPECTED = new Map([
  [ROLES.MANAGER, directorPromptFor(COMMON + MANAGER)],
  [ROLES.CODER, COMMON + WORKER],
  [ROLES.DEVOPS, COMMON + WORKER],
  [ROLES.PLAN, PLAN_PROMPT],
]);

test("property: the role alone decides the prompt, whatever else the session was given", async () => {
  // Every role, plus whatever session id the host might hand us: the id must not
  // change the prompt, and neither must the order in which sessions are built.
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom(...EXPECTED.keys()),
      fc.stringMatching(/^[A-Za-z0-9._-]{0,24}$/),
      async (role, sessionId) => {
        await withSession(role, async (session) => {
          const prompt = await session.systemPrompt();
          assert.equal(prompt, EXPECTED.get(role), `${role} must receive its own composition`);
        }, sessionId);
      },
    ),
    { ...fcRuns(24) },
  );
});

test("property: the director message appears exactly when the role is the Manager", async () => {
  await fc.assert(
    fc.asyncProperty(fc.constantFrom(...EXPECTED.keys()), async (role) => {
      await withSession(role, async (session) => {
        const prompt = await session.systemPrompt();
        const carries = prompt.includes(DIRECTOR_MESSAGE);
        assert.equal(carries, role === ROLES.MANAGER, `${role} director message presence`);
        // Exactly once, never twice: the segment is appended, not concatenated.
        assert.equal(prompt.split("<vibe-mode>").length - 1, carries ? 1 : 0);
      });
    }),
    { ...fcRuns(16) },
  );
});

test("property: coder and devops are indistinguishable by prompt, for any session id", async () => {
  await fc.assert(
    fc.asyncProperty(fc.stringMatching(/^[A-Za-z0-9._-]{0,24}$/), async (sessionId) => {
      let coder;
      let devops;
      await withSession(ROLES.CODER, async (session) => {
        coder = await session.systemPrompt();
      }, sessionId);
      await withSession(ROLES.DEVOPS, async (session) => {
        devops = await session.systemPrompt();
      }, sessionId);
      // §5: the two worker roles receive byte-identical prompts; identity is
      // carried by the director message and the fork verboseIntent, not by this text.
      assert.equal(coder, devops);
      assert.equal(coder, COMMON + WORKER);
    }),
    { ...fcRuns(12) },
  );
});

test("property: an unknown role is refused rather than given the worker prompt", async () => {
  // The old dispatch treated every non-Manager, non-Plan role as `coder`. A
  // broken role string must fail loudly instead of looking like a healthy
  // session with worker privileges.
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom("", "planner", "CODER", "Coder", "worker", "subagent", "primary"),
      async (role) => {
        await withSession(role, async (session) => {
          await assert.rejects(
            () => session.systemPrompt(),
            (error) => error.code === "UNKNOWN_ROLE",
          );
        });
      },
    ),
    { ...fcRuns(7) },
  );
});
