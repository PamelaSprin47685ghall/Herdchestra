// One refusal type for the composition layer. A composition refusal is a
// programming error in the wiring or a refused member call; it is never a
// program result. Program results travel as receipts, not as thrown errors.

export const COMPOSE_CODES = Object.freeze({
  UNKNOWN_ROLE: "UNKNOWN_ROLE",
  UNKNOWN_MEMBER: "UNKNOWN_MEMBER",
  MISSING_PORT: "MISSING_PORT",
  MEMBER_NOT_AVAILABLE: "MEMBER_NOT_AVAILABLE",
  LANE_TERMINAL: "LANE_TERMINAL",
  // Spec §2.2: a tenure writes Plan's draft only after its first review.
  DRAFT_READ_ONLY: "DRAFT_READ_ONLY",
  // 根 §2.2, §3.1: `this.ask` belongs to Plan's `S1` alone. Every other stage
  // runs unattended and decides for itself.
  ASK_FORBIDDEN_OUTSIDE_S1: "ASK_FORBIDDEN_OUTSIDE_S1",
  // 根 §2.2: at most one pending DevOps per task. A second
  // `devops(verboseIntent)` while the first is in flight is refused by name.
  DEVOPS_IN_FLIGHT: "DEVOPS_IN_FLIGHT",
  // Spec §4.2 uses the PascalCase spelling for this refusal; the code field
  // keeps that spelling so a reader can compare it with the spec directly.
  TERMINAL_ALREADY_SET: "TERMINAL_ALREADY_SET",
});

export class ComposeError extends Error {
  constructor(code, message, details = undefined) {
    super(message);
    this.name = "ComposeError";
    this.code = code;
    this.details = details;
  }
}

export const isComposeError = (value) =>
  value instanceof ComposeError && typeof value.code === "string";

export const refuse = (code, message, details) => new ComposeError(code, message, details);
