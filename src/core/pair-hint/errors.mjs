// One refusal type for Pair Hint (spec §6.1, §6.1 step 7).
//
// Every failure here is a form failure, never a content judgement: a language
// that cannot be resolved, a resource pair with a missing book, an unknown
// resource id, a presentation that would move bytes already shown, a tampered
// stored fact. None of them is recovered by silently switching language.

export const PAIR_HINT_CODES = Object.freeze({
  INVALID_ARGUMENT: "INVALID_ARGUMENT",
  INVALID_LANGUAGE: "INVALID_LANGUAGE",
  RESOURCE_UNKNOWN: "RESOURCE_UNKNOWN",
  RESOURCE_PAIR_INCOMPLETE: "RESOURCE_PAIR_INCOMPLETE",
  RESOURCE_MISSING: "RESOURCE_MISSING",
  PREFIX_DRIFT: "PREFIX_DRIFT",
  EPOCH_CONFLICT: "EPOCH_CONFLICT",
  FACT_CORRUPT: "FACT_CORRUPT",
});

export class PairHintError extends Error {
  constructor(code, message, details = null) {
    super(message);
    this.name = "PairHintError";
    this.code = code;
    this.details = details;
  }
}

export const isPairHintError = (value) =>
  value instanceof PairHintError && typeof value.code === "string";

export const refuse = (code, message, details) => new PairHintError(code, message, details);

export const invalidArgument = (message, details) =>
  refuse(PAIR_HINT_CODES.INVALID_ARGUMENT, message, details);
