// Plan's draft path (根 AGENTS.md §6).
//
// §6 fixes one persistent product for Plan and one path, derived from the task,
// which the system names to the model. This module is the single definition of
// that path; the tool rule, the ledger and the entry all read it here.

import { mkdirSync } from "node:fs";
import path from "node:path";

// The draft lives under `.Herdchestra/` beside every other persisted fact
// (spec §1: persistence stays light and lives in one place).
export const PLAN_DRAFT_DIR = path.join(".Herdchestra", "drafts");

// `name` is already file-safe (`safeName`); this function only joins.
export const planDraftPath = (root, name) => path.join(root, PLAN_DRAFT_DIR, `${name}.md`);

// The draft's directory, created before anything writes into it. `write` is an
// atomic replace through a temp file in the target's own directory, so it cannot
// create that directory: a Plan whose `drafts/` did not exist failed every
// write with ENOENT, and the model then made `drafts.md` a plain file, after
// which every write failed with ENOTDIR. Every path that names a draft calls
// this first, before the session can run: `startPlan` (before the host is asked
// to start anything), and the session's `resolveRole` (before the `js-plan`
// rule states the path).
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
