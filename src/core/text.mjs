// UTF-8 and span rules for `cat`, `where`, `whereAll` and `js-review`
// (spec §3.1, §3.3, §8).
//
// - Text is UTF-8. Invalid bytes are an error, never a silent replacement.
// - A span is `[begin, end)` in UTF-16 code units of the JS string.
// - Matching is exact: whitespace, line endings and trailing spaces count. No
//   trimming, no case folding, no normalization, no line-ending rewrite.
// - `where` answers only for exactly one match; zero or several matches park
//   the program with the payload `whereParkPayload` builds.

import { isUtf16Span } from "./util.mjs";

export const WINDOW = 80;
export const CANDIDATE_LIMIT = 8;
export const NEAREST_LINE_LIMIT = 2000;
export const NEAREST_NEEDLE_LINES = 200;
export const NEAREST_NEEDLE_CHARS = 200;

const fatal = (message) => {
  throw new TypeError(message);
};

const decoder = new TextDecoder("utf-8", { fatal: true });

// Decode a byte buffer as strict UTF-8. Throws an error whose `code` is
// `INVALID_UTF8` on bad bytes.
export const decodeUtf8 = (bytes) => {
  try {
    return decoder.decode(bytes);
  } catch {
    const error = new Error("INVALID_UTF8");
    error.code = "INVALID_UTF8";
    throw error;
  }
};

export const encodeUtf8 = (text) => Buffer.from(text, "utf8");

// All exact occurrences of `needle` in `text`, left to right, non-overlapping.
export const findSpans = (text, needle) => {
  if (typeof text !== "string") fatal("text must be a string");
  if (typeof needle !== "string") fatal("needle must be a string");
  if (needle === "") fatal("needle must not be empty");
  const spans = [];
  let from = 0;
  while (true) {
    const begin = text.indexOf(needle, from);
    if (begin === -1) return spans;
    spans.push([begin, begin + needle.length]);
    from = begin + needle.length;
  }
};

export const contextAround = (text, span) => {
  const [begin, end] = span;
  return text.slice(Math.max(0, begin - WINDOW), Math.min(text.length, end + WINDOW));
};

// Levenshtein distance with unit cost. Used only for the `nearest` hint of a
// zero-match `where`, where both inputs are bounded by NEAREST_NEEDLE_CHARS.
export const editDistance = (left, right) => {
  if (left === right) return 0;
  if (left.length === 0) return right.length;
  if (right.length === 0) return left.length;
  let previous = new Array(right.length + 1);
  let current = new Array(right.length + 1);
  for (let j = 0; j <= right.length; j += 1) previous[j] = j;
  for (let i = 1; i <= left.length; i += 1) {
    current[0] = i;
    const a = left.charCodeAt(i - 1);
    for (let j = 1; j <= right.length; j += 1) {
      const cost = a === right.charCodeAt(j - 1) ? 0 : 1;
      current[j] = Math.min(previous[j - 1] + cost, previous[j] + 1, current[j - 1] + 1);
    }
    const swap = previous;
    previous = current;
    current = swap;
  }
  return previous[right.length];
};

// Lines with their spans. A trailing newline adds no empty line, so "a\n" is
// one line "a" with span [0, 1).
export const lineSpans = (text) => {
  const lines = [];
  let begin = 0;
  while (begin < text.length) {
    const breakAt = text.indexOf("\n", begin);
    const end = breakAt === -1 ? text.length : breakAt;
    const lineEnd = end > begin && text[end - 1] === "\r" ? end - 1 : end;
    lines.push([begin, lineEnd]);
    if (breakAt === -1) break;
    begin = breakAt + 1;
  }
  return lines;
};

// The closest line to a needle that matched nothing: only the first
// NEAREST_LINE_LIMIT non-empty lines are candidates, and each side is cut to
// NEAREST_NEEDLE_CHARS before the distance is computed.
//
// `start` is the byte offset the search begins at. A caller that knows where
// the needle's match region is (a `where` that found nothing) passes the offset
// of the nearest match, so the hint describes the line the caller meant rather
// than the file's first line.
export const nearestLine = (text, needle, { start = 0 } = {}) => {
  const needleHead = needle.slice(0, NEAREST_NEEDLE_CHARS);
  let best = null;
  let considered = 0;
  for (const span of lineSpans(text)) {
    if (span[1] <= span[0]) continue;
    if (span[0] < start) continue;
    if (considered >= NEAREST_LINE_LIMIT) break;
    considered += 1;
    const line = text.slice(span[0], span[1]);
    const distance = editDistance(needleHead, line.slice(0, NEAREST_NEEDLE_CHARS));
    if (best === null || distance < best.distance) best = { span, text: line, distance };
    if (distance === 0) break;
  }
  return best;
};

// The payload that parks a program when `where` cannot answer.
// Zero matches: `{ needle, candidates: [], nearest }` (`nearest` may be null).
// Several matches: `{ needle, candidates: [{ span, context }] }`, at most 8.
export const whereParkPayload = (text, needle, spans) => {
  if (spans.length === 1) return null;
  const candidates = spans
    .slice(0, CANDIDATE_LIMIT)
    .map((span) => ({ span, context: contextAround(text, span) }));
  const payload = { needle, candidates };
  if (spans.length === 0) payload.nearest = nearestLine(text, needle);
  return payload;
};

export const isSpan = (value) => isUtf16Span(value);
