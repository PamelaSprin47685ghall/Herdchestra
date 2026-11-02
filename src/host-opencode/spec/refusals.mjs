// Refusal receipts of the adapter's own tools (§4.1, §6.2, §7.1).
//
// A refusal is a receipt, never a thrown error: the model must be able to read
// it and correct the call. The codes are stable strings the model can quote;
// the text says what to do next. Nothing here is invented per call site, so a
// reader can see every way a call is turned away in one place.

export const REFUSALS = Object.freeze({
  ROLE: "ROLE_DENIED",
  SCOPE: "SCOPE_REFUSED",
  MEMBER: "MEMBER_DENIED",
  CODE: "CODE_INVALID",
  ROLE_ARG: "WORKER_ROLE_INVALID",
  DEVOPS_BUSY: "DEVOPS_BUSY",
  CANCELLED: "CANCELLED",
  TASK_MODEL_INCOMPLETE: "TASK_MODEL_INCOMPLETE",
  GAP: "HOST_GAP",
  NAME: "NAME_INVALID",
  BUSY: "PROGRAM_BUSY",
  REPLAY_MISMATCH: "CALL_REPLAY_MISMATCH",
  REPLAY_LOST: "CALL_REPLAY_LOST",
  RELAY_RETIRED: "RELAY_RETIRED",
  RELAY_CORRUPT: "RELAY_CORRUPT",
  REVIEW_WINDOW_CLOSED: "REVIEW_WINDOW_CLOSED",
  DRAFT_DIR_UNWRITABLE: "DRAFT_DIR_UNWRITABLE",
});

const text = {
  [REFUSALS.ROLE]: "This tool does not belong to your role.",
  [REFUSALS.SCOPE]: "The scope annotation was refused; the call did not run.",
  [REFUSALS.MEMBER]: "That member is not on your surface.",
  [REFUSALS.CODE]: "`code` must be a non-empty program string.",
  [REFUSALS.ROLE_ARG]: "`role` must be `coder` or `devops`.",
  [REFUSALS.DEVOPS_BUSY]: "A devops worker is already in flight; exactly one may run.",
  // The operator stopped the work (root §1.1 item 2). A cancelled session may
  // not start or steer workers: that would spend tokens the operator just
  // stopped paying for.
  [REFUSALS.CANCELLED]: "This work was cancelled by the operator; no worker may be started or steered.",
  // Root §1.1 item 6: the operator named one half of the task model. Using the
  // parent's model while pretending the task model was configured would hide
  // the misconfiguration, so the fork is refused by name.
  [REFUSALS.TASK_MODEL_INCOMPLETE]:
    "The task model is half configured: set HERDCHESTRA_TASK_PROVIDER_ID and HERDCHESTRA_TASK_MODEL_ID together.",
  [REFUSALS.GAP]: "This behaviour depends on a host fact that is not established.",
  [REFUSALS.NAME]: "A worker name must be a non-empty string.",
  [REFUSALS.BUSY]: "The named program is still running; its receipt is in the result.",
  [REFUSALS.REPLAY_MISMATCH]:
    "This call id was already used with a different payload; the original receipt is kept and nothing ran.",
  // A call id from an earlier process. Its payload and receipt lived in that
  // process's memory, so neither "same call" nor "different call" can be shown:
  // the call is refused instead of being run again under an id that already
  // meant something.
  [REFUSALS.REPLAY_LOST]:
    "This call id was already used in an earlier process; its payload and receipt are not durable, so it cannot be replayed or re-run.",
  // A relay refusal carries the record's own sentence in `reason`: it names the
  // successor and the tenure, which this table cannot.
  [REFUSALS.RELAY_RETIRED]: "This tenure was retired by a relay; the call did not run.",
  [REFUSALS.RELAY_CORRUPT]: "The relay record is unusable; refusing to act until it is repaired.",
  [REFUSALS.REVIEW_WINDOW_CLOSED]:
    "The evaluation window is closed: this round's first review was already accepted. Fork a read-only coder to learn anything about the tree.",
  // The draft directory could not be created. Said at start, because the first
  // write would otherwise report it as `ENOENT` about a temp file the Plan
  // never named — and Plan has no `mkdir` to fix it.
  [REFUSALS.DRAFT_DIR_UNWRITABLE]:
    "The draft directory could not be created; the draft cannot be written until it exists.",
};

export const refusal = (code, extra = {}) => ({
  ok: false,
  rejection: code,
  message: text[code] ?? code,
  ...extra,
});

export const gapRefusal = (gapId, detail) =>
  refusal(REFUSALS.GAP, { gap: gapId, detail });
