// Plan's draft path (根 AGENTS.md §1.1 item 3, §2.2).
//
// §2.2 fixes one persistent product for Plan and one path, derived from the
// task, which the system names to the model. The draft is USER-VISIBLE: it lives
// under the workspace root's `plans/`, never under `.Herdchestra/`, which is the
// adapter's own state and is not part of the operator's tree. This module is the
// single definition of that path; the tool rule, the ledger and the entry all
// read it here.

import { mkdirSync } from "node:fs";
import path from "node:path";

// `plans/<name>.md` at the workspace root: the one output Plan produces, where
// a person can read it.
export const PLAN_DRAFT_DIR = "plans";

// `name` is already file-safe (`safeName`); this function only joins.
export const planDraftPath = (root, name) => path.join(root, PLAN_DRAFT_DIR, `${name}.md`);

// The draft's directory, created before anything writes into it. `write` is an
// atomic replace through a temp file in the target's own directory, so it cannot
// create that directory: a Plan whose `plans/` did not exist failed every write
// with ENOENT, and the model then made `plans.md` a plain file, after which
// every write failed with ENOTDIR. Every path that names a draft calls this
// first, before the session can run: `startPlan` (before the host is asked to
// start anything), and the session's `resolveRole` (before the `js-plan` rule
// states the path).
//
// A directory that cannot be created is a refusal, not a later surprise: the
// Plan could never save the one product it exists to produce.
export const ensureDraftDir = ({ root, file }) => {
  const directory = path.dirname(file ?? planDraftPath(root, "draft"));
  try {
    mkdirSync(directory, { recursive: true });
    return { ok: true, directory };
  } catch (error) {
    return { ok: false, reason: "DRAFT_DIR_UNWRITABLE", directory, detail: String(error?.message ?? error) };
  }
};
