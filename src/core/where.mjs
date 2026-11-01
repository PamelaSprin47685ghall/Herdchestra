// Text members: `where` and `whereAll` (spec §3.1, §3.3 example 3).
//
// Both members are synchronous and work on text the caller already holds; they
// never read a file. `whereAll` always answers with the full span list. `where`
// answers only when exactly one span matches; otherwise it hands the payload to
// the injected `park` and returns that call's result unchanged.

import { invalidArgument } from "./errors.mjs";
import { findSpans, whereParkPayload } from "./text.mjs";

const checkArgs = (member, text, needle) => {
  if (typeof text !== "string") throw invalidArgument(`${member}: text must be a string`);
  if (typeof needle !== "string" || needle.length === 0) {
    throw invalidArgument(`${member}: needle must be a non-empty string`);
  }
};

// Spec §3.1, §3.4 examples 1–5: the first argument is the CONTENT, the second
// the needle — `this.where(source, "RetryPolicy")` on text from `this.cat`.
// The earlier reading took a path first, which made every documented example a
// `TypeError` and turned a text API into a file API. The path form is gone; the
// members never read a file (§3.2: text the caller already holds).
const contentOf = (text) => {
  if (typeof text !== "string") {
    throw invalidArgument("the first argument must be the content, not a path");
  }
  return text;
};

export class TextMembers {
  constructor({ park = () => null } = {}) {
    if (typeof park !== "function") throw new TypeError("park must be a function");
    this.park = park;
  }

  // `[begin, end]` when unique, else the park result.
  where(content, needle) {
    checkArgs("where(content, needle)", contentOf(content), needle);
    const spans = findSpans(content, needle);
    const payload = whereParkPayload(content, needle, spans);
    if (payload !== null) return this.park(payload);
    return spans[0];
  }

  whereAll(content, needle) {
    checkArgs("whereAll(content, needle)", contentOf(content), needle);
    return findSpans(content, needle);
  }
}
