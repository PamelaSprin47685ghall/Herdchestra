// Plan's programming side on Cline (根 AGENTS.md §6, §6.1).
//
// Plan is a top-level session of its own: never created by `fork`, outside the
// five `skillDocuments`, with one persistent product — the draft at a path the
// system names. This module holds the two parts of that face that belong to the
// session rather than to the host entry:
//
//   - `readDraft(file)` — the saved bytes, `""` when missing;
//   - `planPorts` — the Plan-only ports (`draft`), which is all that is left
//     here: `devops` lives in `devops.mjs`, and the relay is the session's own.
//
// The session that *starts* a Plan is `plan-entry.mjs`, and the hand-off is
// `handoff.mjs`: a CLI or an SDK script embeds Cline itself and needs both
// without a plugin session.
//
// Nothing here invents a result: a missing draft reads as `""` so the ledger
// refuses the hand-off before a Manager session exists (§6.1).

import { readFile } from "node:fs/promises";

export { PLAN_DRAFT_DIR, ensureDraftDir, planDraftPath } from "./drafts.mjs";

// The draft bytes, or `""` when the file does not exist yet. Any other read
// failure is a real error and is reported, not turned into an empty draft.
export const readDraft = async (file) => {
  try {
    return await readFile(file, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return "";
    throw error;
  }
};

// The Plan-only ports of `compose/ports.mjs`. A Plan has no `fork`, no `join`,
// no `fission` and no `command` in its member list, so none is supplied here.
// `devops` and `relay` are the caller's own objects, passed through unchanged.
export const planPorts = ({ file, devops, relay }) => ({
  devops,
  draft: { read: () => readDraft(file) },
  relay,
});
