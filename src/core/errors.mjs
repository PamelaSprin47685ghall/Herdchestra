// One error type for every refusal in the core. `code` is the machine-readable
// reason; `details` carries the extra facts a caller needs (for example the
// offending index). Messages are short English.

export const CODES = Object.freeze({
  INVALID_ARGUMENT: "INVALID_ARGUMENT",
  NAME_TAKEN: "NAME_TAKEN",
  STALE_CONTROL: "STALE_CONTROL",
  PROGRAM_UNKNOWN: "PROGRAM_UNKNOWN",
  CAPABILITY_DENIED: "CAPABILITY_DENIED",
  CALL_REPLAY_MISMATCH: "CALL_REPLAY_MISMATCH",
  INVALID_UTF8: "INVALID_UTF8",
});

// Fission rejections are receipt values, not thrown errors (spec §4.2).
export const REJECTIONS = Object.freeze({
  TooFewLanes: "TooFewLanes",
  EmptyLanePrompt: "EmptyLanePrompt",
  AlreadyActive: "AlreadyActive",
  InvalidOrigin: "InvalidOrigin",
  CapacityExceeded: "CapacityExceeded",
  ConflictingGroup: "ConflictingGroup",
  DuplicateOwnerActive: "DuplicateOwnerActive",
});

export class CoreError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "CoreError";
    this.code = code;
    this.details = details;
  }
}

export const isCoreError = (value) =>
  value instanceof CoreError && typeof value.code === "string";

export const invalidArgument = (message, details) =>
  new CoreError(CODES.INVALID_ARGUMENT, message, details);
