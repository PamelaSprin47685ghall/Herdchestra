// Temporary workspaces for the adapter's tests (§8.2: a suite leaves nothing
// behind).
//
// A test that creates a root and forgets to remove it leaks one directory per
// test — and a property test leaks one per run. The registry below removes
// every root it handed out when the process exits, so no spec file can forget
// it; a test that wants the directory gone sooner calls `removeRoot`.
//
// The helper is NOT a `*.test.mjs` file: `node --test "**/*.test.mjs"` must not
// load it as a suite.

import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const created = new Set();

process.once("exit", () => {
  for (const dir of created) {
    try {
      fs.rmSync(dir, { recursive: true, force: true });
    } catch {
      // A directory that cannot be removed is reported by the next run, not by
      // an exception during process exit.
    }
  }
  created.clear();
});

export const tempRoot = (prefix = "herd-test-") => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  created.add(dir);
  return dir;
};

export const removeRoot = (dir) => {
  if (typeof dir !== "string" || dir.length === 0) return;
  created.delete(dir);
  try {
    fs.rmSync(dir, { recursive: true, force: true });
  } catch {
    // The exit registry still holds the directory if this attempt failed.
    created.add(dir);
  }
};
