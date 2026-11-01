// Park receipts (spec §3.3).
//
// One shape for every park and every program view:
//   { name, state, reason, payload, source, pendingOperations,
//     command, delta, remainingWaitMs }
//
// `reason` is `explicit` for a program's own `park` and for a `where` that
// cannot answer, `ask` for `this.ask`, and `command`/`sdk` for the other two
// wait sites. `source` is the full source with line numbers, or null when the
// host captured none. `command` is null when no command is running.

import { invalidArgument } from "./errors.mjs";
import { STATES } from "./program.mjs";

export const PARK_REASONS = Object.freeze({
  EXPLICIT: "explicit",
  ASK: "ask",
  COMMAND: "command",
  SDK: "sdk",
});

const REASON_VALUES = Object.values(PARK_REASONS);

export const sourceWithLineNumbers = (source) => {
  if (typeof source !== "string") return null;
  const width = String(source.split("\n").length).length;
  return source
    .split("\n")
    .map((line, index) => `${String(index + 1).padStart(width, " ")} | ${line}`)
    .join("\n");
};

// A live view of one command. `append` and `end` are the only writers; `view`
// takes no arguments, so a caller cannot pass a second, contradictory copy of
// the same facts.
export class CommandView {
  #stdout;
  #stderr;
  #stdoutBytes;
  #stderrBytes;
  #startedAt;
  #clock;
  #endedAt;
  #exitCode;

  constructor({ clock }) {
    if (clock === undefined || typeof clock.now !== "function") {
      throw invalidArgument("CommandView needs a clock");
    }
    this.#clock = clock;
    this.#startedAt = clock.now();
    this.#stdout = "";
    this.#stderr = "";
    this.#stdoutBytes = 0;
    this.#stderrBytes = 0;
    this.#endedAt = null;
    this.#exitCode = null;
  }

  append(stream, chunk) {
    if (typeof chunk !== "string") throw invalidArgument("chunk must be a string");
    const bytes = Buffer.byteLength(chunk, "utf8");
    if (stream === "stderr") {
      this.#stderr += chunk;
      this.#stderrBytes += bytes;
    } else if (stream === "stdout") {
      this.#stdout += chunk;
      this.#stdoutBytes += bytes;
    } else {
      throw invalidArgument(`unknown stream: ${stream}`);
    }
    return this;
  }

  end(exitCode) {
    if (this.#endedAt !== null) throw invalidArgument("command already ended");
    this.#endedAt = this.#clock.now();
    this.#exitCode = exitCode;
    return this;
  }

  get running() {
    return this.#endedAt === null;
  }

  get stdout() {
    return this.#stdout;
  }

  get stderr() {
    return this.#stderr;
  }

  get exitCode() {
    return this.#exitCode;
  }

  // Bytes written so far, both streams together.
  get bytes() {
    return this.#stdoutBytes + this.#stderrBytes;
  }

  view() {
    return {
      stdout: this.#stdout,
      stderr: this.#stderr,
      stdoutBytes: this.#stdoutBytes,
      stderrBytes: this.#stderrBytes,
      durationMs: (this.#endedAt ?? this.#clock.now()) - this.#startedAt,
      running: this.running,
      exitCode: this.#exitCode,
    };
  }
}

// Build a receipt. Absent facts are null (or "" for `delta`), never missing keys.
export const buildReceipt = ({
  name,
  state,
  reason = null,
  payload = null,
  source = null,
  pendingOperations = 0,
  command = null,
  delta = "",
  remainingWaitMs = 0,
}) => {
  if (typeof name !== "string" || name.length === 0) {
    throw invalidArgument("a receipt needs a program name");
  }
  if (!Object.values(STATES).includes(state)) {
    throw invalidArgument(`unknown program state: ${state}`);
  }
  if (reason !== null && !REASON_VALUES.includes(reason)) {
    throw invalidArgument(`unknown park reason: ${reason}`);
  }
  return {
    name,
    state,
    reason,
    payload,
    source: sourceWithLineNumbers(source),
    pendingOperations,
    command,
    delta,
    remainingWaitMs,
  };
};

// The nine fields of spec §3.3, in spec order. `value` and `result` travel on
// receipts for the host to read, but they are not part of the shape: a key
// count that grows with every convenience field stops being the contract.
export const RECEIPT_FIELDS = Object.freeze([
  "name",
  "state",
  "reason",
  "payload",
  "source",
  "pendingOperations",
  "command",
  "delta",
  "remainingWaitMs",
]);

// Attach a field that the receipt shape does not carry (`value`, `result`).
// The key stays non-enumerable, so `Object.keys`, spread and deep equality see
// the nine fields only, while a caller that knows the key still reads it.
export const withExtra = (receipt, key, value) => {
  Object.defineProperty(receipt, key, {
    value,
    enumerable: false,
    configurable: true,
    writable: true,
  });
  return receipt;
};

// A receipt for a program view (running/waiting, or a terminal result). The
// parked site's reason and payload come from the program itself, so a view can
// never disagree with the state it reports. A caller that has just consumed the
// site (a resume) passes it as `site`: the program no longer holds it, and the
// receipt of that resume must still say what was resumed.
export const programView = (program, { source = null, command = null, delta = "", site: consumed = null } = {}) => {
  const site = consumed ?? program.site;
  return buildReceipt({
    name: program.name,
    state: program.state,
    reason: site === null ? null : site.reason,
    payload: site === null ? null : (site.payload ?? null),
    source,
    pendingOperations: program.pendingOperations,
    command,
    delta,
    remainingWaitMs: program.remainingWaitMs() ?? 0,
  });
};

export const isTerminalReceipt = (receipt) =>
  receipt.state === STATES.COMPLETED || receipt.state === STATES.FAILED;

// The receipt of an `ask` (§3.3 example 7). `this.ask(question)` IS
// `this.park({ question })` + `interrupt`, so the asking half answers with the
// park receipt and `interrupt: true` on the side. The same shape is used by
// every host and by the first-incumbent question of §2.4 第 1 步, so a caller
// never has to know which host asked.
//
// `interrupt` is attached with `withExtra`: it is a control fact beside the
// nine receipt fields, not a tenth field of the program view.
export const askedReceipt = ({ question, program = null, toolCallId = null, parked = null }) => {
  if (typeof question !== "string" || question.trim().length === 0) {
    throw invalidArgument("an ask needs a question");
  }
  const receipt = {
    ok: true,
    asked: true,
    question,
    program,
    toolCallId,
    ...(parked === null ? {} : { receipt: parked }),
    note: "the operator's reply arrives as the next message; resume this program with unpark(name, answer)",
  };
  return withExtra(receipt, "interrupt", true);
};
