// @herdchestra/core/where — package specification.
//
// `TextMembers` implements `where` and `whereAll` on text the caller already
// holds (spec §3.1, §3.3). It never reads a file.
//
// - `where(content, needle)` returns `[begin, end]` only when `needle` occurs
//   exactly once. With zero or several occurrences it calls the injected
//   `park(payload)` and returns that result; the program is expected to park.
// - `whereAll(content, needle)` returns every span, left to right,
//   non-overlapping, and never parks.
// - **The first argument is the CONTENT, never a path.** Spec §3.1 and all five
//   §3.4 examples read `this.where(source, "RetryPolicy")` on text from
//   `this.cat`. An earlier revision took a path first, which made every
//   documented example a `TypeError`; that form is removed. A non-string first
//   argument is `INVALID_ARGUMENT` with a message that says so.
// - Matching is exact. Whitespace, line endings and trailing spaces count;
//   nothing is trimmed, folded or normalized. An empty needle, a non-string text
//   or a non-string needle is `INVALID_ARGUMENT`.
//
// Payload (`text.mjs#whereParkPayload`):
//
// - zero matches: `{ needle, candidates: [], nearest }`. `nearest` is the
//   closest of the first 2000 non-empty lines, comparing at most 200 characters
//   of the needle with at most 200 characters of the line by edit distance;
//   `{ span, text, distance }`, or null when no such line exists.
// - several matches: `{ needle, candidates }`, at most 8 entries of
//   `{ span, context }`; `context` is 80 UTF-16 units on each side.
//
// `nearestLine(text, needle)` is the same hint on its own, for callers that want
// the diagnosis without the payload.
