// Property tests for the CUE renderer (spec §6.3, §8.2 step 7).
//
// Two halves of one contract:
// - the bytes a model reads are CUE: no JSON escape noise, blocks aligned under
//   their field, the string ladder chosen by content;
// - the bytes MEAN the value: every string round-trips through a reader that
//   re-implements the parts of CUE's unquote this renderer can emit into — the
//   escape delimiter (`\` + `#`×n), block indent stripping, the dropped
//   carriage return and the discarded newline before a closing delimiter. Those
//   rules were measured against `cue eval` v0.11.0; the test needs no binary,
//   and it fails if the renderer starts relying on something CUE does not do.

import assert from "node:assert/strict";
import test from "node:test";
import fc from "fast-check";

import { cueScalar, isCueIdentifier, toCue } from "./cue.mjs";
import { RECEIPT_FIELDS, buildReceipt, withExtra } from "./receipt.mjs";
import { fcRuns } from "./test-runs.mjs";

const LF = "\n";
const CR = "\r";

// ------------------------------------------------------------------- reader

// Read one CUE string literal at `index`: `{ text, end }`.
//
// A block is read the way CUE reads it: its body starts on the line after the
// opening delimiter and ends before the closing line, which holds only
// whitespace and the delimiter. That whitespace is stripped from every body
// line. Inside the body the only escape is a backslash plus the literal's
// fence, and a bare CR is dropped.
export const readCueString = (source, index = 0) => {
  let open = index;
  let level = 0;
  while (source[open] === "#") {
    level += 1;
    open += 1;
  }
  // A NUMBER is not a string, and an array renders its items bare: `[1, 2]`
  // holds `1`, not `"1"`. Reading it as its literal text is enough for the
  // round-trip a caller checks (the text is what `String(value)` gave).
  if (source[open] !== '"') {
    const literal = /^-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?|^(?:true|false|null)/.exec(source.slice(open));
    assert.notEqual(literal, null, `a value starts at ${index}: ${JSON.stringify(source.slice(index, index + 40))}`);
    return { text: literal[0], end: open + literal[0].length };
  }
  assert.equal(
    source[open],
    '"',
    `a string literal starts at ${index}: ${JSON.stringify(source.slice(index, index + 40))}`,
  );
  const fence = "#".repeat(level);
  // A block opens with three quotes and a newline; a plain literal opens with
  // one quote. The closing delimiter is the quotes first, then the fence, so
  // `"""#` closes a `#"""` block.
  const delimiter = `"""${fence}`;
  // `#"""` followed by a newline opens a block, and nothing else does: CUE
  // answers `expected newline after multiline quote #"""` for any fenced
  // three-quote opener without one, and `#"""""#` is a raw inline string whose
  // body merely starts with quotes.
  const multiline = source.startsWith(`"""${LF}`, open);

  let bodyStart;
  let bodyEnd;
  let end;
  let indent = "";
  if (multiline) {
    // The opening delimiter is `"""` (the fence is on the closing side only).
    assert.equal(source[open + 3], LF, "a multiline literal opens on its own line");
    bodyStart = open + 4;
    bodyEnd = -1;
    end = -1;
    // The closing line is a line that carries only whitespace before the
    // delimiter, and nothing after it: `"""#: 1` closes a label's block with
    // the field's value on the same line, so the line is not required to END
    // with the delimiter. The scan is over indices, so no slice of the source
    // can shift the offsets.
    for (let at = bodyStart; at <= source.length; ) {
      const lineEnd = source.indexOf(LF, at);
      const stop = lineEnd === -1 ? source.length : lineEnd;
      // EVERY occurrence on the line is tried, not just the first: a body line
      // may hold the delimiter itself (`"` in a `#"""` block), and only the one
      // with nothing but whitespace before it closes the literal.
      for (let delimiterAt = source.indexOf(delimiter, at); delimiterAt !== -1 && delimiterAt + delimiter.length <= stop; ) {
        const before = source.slice(at, delimiterAt);
        if (before.trim() === "") {
          // `before` is the whitespace in front of the closing delimiter, and
          // that whitespace is exactly the indent CUE strips from every body
          // line.
          bodyEnd = at - 1;
          end = delimiterAt + delimiter.length;
          indent = before;
          break;
        }
        delimiterAt = source.indexOf(delimiter, delimiterAt + 1);
      }
      if (bodyEnd !== -1) {
        break;
      }
      assert.notEqual(lineEnd, -1, "a multiline literal is closed");
      at = lineEnd + 1;
    }
    assert.notEqual(bodyEnd, -1, "a multiline literal is closed");
  } else {
    bodyStart = open + 1;
    // The terminator is the FIRST `"`+fence that is not itself escaped: in an
    // unfenced literal `\"` is a quote in the body, so the search steps over a
    // run of backslashes of odd length. Under a fence no `\` precedes the
    // delimiter at all, and the loop ends on the first match.
    bodyEnd = source.indexOf(`"${fence}`, bodyStart);
    while (level === 0 && bodyEnd > bodyStart) {
      let slashes = 0;
      while (source[bodyEnd - 1 - slashes] === "\\") slashes += 1;
      if (slashes % 2 === 0) break;
      bodyEnd = source.indexOf(`"${fence}`, bodyEnd + 1);
    }
    assert.notEqual(bodyEnd, -1, `a literal is closed: ${JSON.stringify(source.slice(bodyStart, bodyStart + 60))}`);
    end = bodyEnd + 1 + level;
  }

  // A block's body is the text from the opening line's end to the closing
  // line's start, MINUS the newline that separates the last body line from the
  // closing line: CUE discards exactly that one, and the newline a body ends
  // with is part of its content.
  const body = multiline
    ? source
        .slice(bodyStart, bodyEnd)
        .split(LF)
        .map((line) => (line.startsWith(indent) ? line.slice(indent.length) : line))
        .join(LF)
    : source.slice(bodyStart, bodyEnd);

  let text = "";
  for (let i = 0; i < body.length; i += 1) {
    const char = body[i];
    if (char === CR) continue;
    if (char !== "\\") {
      text += char;
      continue;
    }
    // An UNFENCED literal reads the CUE/JSON escapes: `\\`, `\"`, `\n`, `\r`,
    // `\t`, `\b`, `\f`, `\uXXXX`. A fenced one reads only its own delimiter.
    if (level === 0) {
      const plain = body[i + 1];
      const four = body.slice(i + 2, i + 6);
      if (plain === "\\") text += "\\";
      else if (plain === '"') text += '"';
      else if (plain === "n") text += LF;
      else if (plain === "r") text += CR;
      else if (plain === "t") text += "\t";
      else if (plain === "b") text += "\b";
      else if (plain === "f") text += "\f";
      else if (plain === "u" && /^[0-9a-fA-F]{4}$/.test(four)) text += String.fromCharCode(Number.parseInt(four, 16));
      else throw new Error(`unknown unfenced escape at ${i}: ${JSON.stringify(body.slice(i, i + 6))}`);
      i += plain === "u" ? 5 : 1;
      continue;
    }
    assert.equal(body.slice(i, i + 1 + level), `\\${fence}`, `a backslash outside the escape delimiter at ${i}`);
    const escaped = body[i + 1 + level];
    // `\`+fence+`u0000` is CUE's own NUL escape: a bare NUL in the source is
    // `illegal character NUL`, so the renderer always spells it. It is the one
    // escape whose payload is longer than a character.
    const nul = body.slice(i + 1 + level, i + 6 + level) === "u0000";
    if (escaped === "\\") text += "\\";
    else if (escaped === "r") text += CR;
    else if (escaped === "n") text += LF;
    else if (nul) text += "\0";
    else throw new Error(`unknown escape at ${i}: ${JSON.stringify(body.slice(i, i + 4))}`);
    i += level + (nul ? 5 : 1);
  }
  return { text, end };
};

const readScalar = (rendered) => readCueString(rendered).text;

// ------------------------------------------------------------------ bodies

// Every character class the ladder distinguishes. Nothing is left out: a
// trailing newline, a leading quote and a NUL are all strings CUE can carry,
// and each takes a shape the ladder must choose correctly.
const fixedBodies = [
  "",
  "plain",
  'say "hi"',
  "C:\\tmp",
  "line\r\nwith\n\nblank",
  'x """ y',
  "x \\(y)",
  "end\\",
  "  ind\n   more",
  "trail \r",
  "\r",
  "a\rb",
  'q """# r\ns',
  "tab\there",
  '#"b""#"#a\t)"\\\\a',
  '"""',
  '"""#',
  '\\#"""#',
  // A body that holds the escape delimiter of a SHALLOWER fence. `\#` inside a
  // one-`#` literal would be read as the start of an escape at that level, so
  // the fence has to grow past the run the body already carries.
  "\\#",
  "\\##",
  "a\\#b",
  "a\\##b",
  "a\\#b\nc",
  "\\#\\##",
  // A NUL is illegal in CUE source in every literal shape; it travels as
  // `\`+fence+`u0000`.
  "\0",
  "a\0b",
  "a\n\0\nb",
  '"',
  '"abc',
  '""',
  '"""',
  '\n"""',
  'a\n"""',
  '"""#\n',
  "\n",
  "\n\n",
  "a\n",
  "a\n\n",
];

const bodyArbitrary = fc
  .array(fc.constantFrom("a", "b", '"', "#", "\\", "\n", "\r", " ", "\t", "(", ")", "x", "\\(", "\0", "u", "0"), {
    minLength: 0,
    maxLength: 24,
  })
  .map((parts) => parts.join(""));

// True when the renderer presents this body as a block: a body with a newline,
// or one that OPENS with a quote (no fenced inline literal can), minus the two
// bodies a block cannot carry exactly:
// - one that ENDS in a newline (CUE discards the newline before the closing
//   delimiter, so the last byte would vanish);
// - one holding a bare CR (a block reads it as a line ending and drops it).
// Both stay inline, where those bytes are escapes.
const wantsBlock = (body) =>
  (body.includes(LF) || body.startsWith('"')) && !body.endsWith(LF) && !body.includes(CR);

// The `#` fence a rendered block opened with: the run before its `"""`.
const blockFence = (rendered) => rendered.slice(0, rendered.indexOf('"""'));

// A body line as the renderer writes it under a fence: a backslash, a CR and a
// NUL take the escapes `\`+fence+`\`, `\`+fence+`r`, `\`+fence+`u0000`; nothing
// else moves.
const escapeBody = (line, fence) =>
  line
    .replaceAll("\\", `\\${fence}\\`)
    .replaceAll(CR, `\\${fence}r`)
    .replaceAll("\0", `\\${fence}u0000`);

test("every body round-trips through the string ladder", () => {
  for (const body of fixedBodies) {
    const rendered = cueScalar(body, { indent: 2 });
    assert.equal(readScalar(rendered), body, `round-trip failed for ${JSON.stringify(body)}: ${JSON.stringify(rendered)}`);
  }
  fc.assert(
    fc.property(bodyArbitrary, (body) => {
      const rendered = cueScalar(body, { indent: 2 });
      assert.equal(readScalar(rendered), body, `round-trip failed for ${JSON.stringify(body)}: ${JSON.stringify(rendered)}`);
    }),
    { ...fcRuns(500) },
  );
});

test("a rendered body carries no bare CR, and no escape noise where a block is used", () => {
  fc.assert(
    fc.property(bodyArbitrary, (body) => {
      const rendered = cueScalar(body, { indent: 2 });
      // A CR is always the escape `\r`: CUE drops a bare one from every literal.
      assert.equal(rendered.includes(CR), false, `a bare CR reached the literal: ${JSON.stringify(rendered)}`);
      // Inside a block nothing is escaped but a backslash and a CR, so a JSON
      // `\n` never appears there, and a `\"` appears only when the source text
      // itself holds a backslash followed by a quote. That is the noise §6.3
      // forbids.
      if (wantsBlock(body)) {
        const open = rendered.indexOf('"""') + 3;
        const close = rendered.lastIndexOf('"""');
        const bodyText = rendered.slice(open, close);
        assert.equal(bodyText.includes("\\n"), false, `escaped newline inside a block: ${JSON.stringify(rendered)}`);
        const escapedQuote = bodyText.split('\\"').length - 1;
        const backslashQuote = body.split('\\"').length - 1;
        assert.equal(escapedQuote, backslashQuote, `an invented escaped quote: ${JSON.stringify(rendered)}`);
      }
    }),
    { ...fcRuns(500) },
  );
});

test("a block's lines and its closing delimiter share one indent", () => {
  fc.assert(
    fc.property(bodyArbitrary, (body) => {
      if (!wantsBlock(body)) return;
      const rendered = cueScalar(body, { indent: 2 });
      const lines = rendered.split(LF);
      const close = lines.at(-1);
      // The fence lives on both sides: `##"""` opens with fence `##` and the
      // closing line ends with `"""##`. So the close is checked against the
      // block's OWN fence, not a fixed one — a body holding `"""#` grows it.
      const fence = blockFence(rendered);
      // The closing delimiter carries exactly the indent the body lines carry:
      // one step past the root `cueScalar` renders at. One extra space would be
      // `invalid whitespace` to CUE.
      assert.equal(close, `  """${fence}`, `the closing delimiter is indented: ${JSON.stringify(close)}`);
      const indent = close.slice(0, close.indexOf('"'));
      for (const line of lines.slice(1, -1)) {
        if (line === "") continue;
        assert.equal(line.startsWith(indent), true, `body line is aligned: ${JSON.stringify(line)}`);
      }
      // CUE strips exactly the closing delimiter's indent from every body line,
      // so the rendered line is `indent` followed by the body line AS RENDERED.
      // The body's own indentation is therefore measured on that rendered line,
      // not on the source text: a leading CR becomes the escape `\`+fence+`r`,
      // whose backslash ends the indentation, so `\r ` contributes none.
      //
      // The one line outside the rule is the empty one: it is emitted bare (a
      // padded empty line would put the padding in front of the closing indent,
      // and CUE would leave it behind as trailing content), and CUE strips
      // nothing from a line that does not carry the indent, so its rendered
      // length is zero.
      const firstRendered = escapeBody(body.split(LF)[0], fence);
      const first = lines[1];
      if (firstRendered === "") {
        assert.equal(first, "", `an empty first line is emitted bare: ${JSON.stringify(rendered)}`);
      } else {
        const lead = first.length - first.trimStart().length;
        const own = firstRendered.length - firstRendered.trimStart().length;
        assert.equal(lead, indent.length + own, `the first line keeps its own indent: ${JSON.stringify(rendered)}`);
      }
    }),
    { ...fcRuns(300) },
  );
});

test("a string with no newline stays on the field's line", () => {
  fc.assert(
    fc.property(
      // A body that OPENS with a quote is the one exception, and it is CUE's:
      // `#"""` there is a block opener whatever the fence.
      bodyArbitrary.filter((body) => !body.includes(LF) && !body.startsWith('"')),
      (body) => {
        const rendered = cueScalar(body);
        assert.equal(rendered.includes(LF), false, `no block for ${JSON.stringify(body)}: ${JSON.stringify(rendered)}`);
        assert.equal(readScalar(rendered), body);
      },
    ),
    { ...fcRuns(300) },
  );
});

test("fences grow until the body cannot close the literal early", () => {
  for (const { body, fence } of [
    { body: 'a"b', fence: 1 },
    { body: 'ends"#', fence: 2 },
    { body: 'a"##b', fence: 3 },
    { body: 'x """ y', fence: 1 },
  ]) {
    const rendered = cueScalar(body);
    assert.equal(rendered.startsWith(`${"#".repeat(fence)}"`), true, rendered);
    assert.equal(readScalar(rendered), body);
  }
  const block = cueScalar('a\n"""#\nb', { indent: 2 });
  assert.equal(block.startsWith('##"""'), true, block);
  assert.equal(readScalar(block), 'a\n"""#\nb');
});

test("a body holding a shallower escape delimiter grows the fence too", () => {
  // `\#` in a one-`#` literal is the start of an escape at level one, so the
  // literal would read the body's own backslash as the renderer's. The fence
  // has to pass the run the body carries, exactly as it does for `"` + `#`×n.
  for (const { body, fence } of [
    { body: "a\\#b", fence: 2 },
    { body: "a\\##b", fence: 3 },
  ]) {
    const rendered = cueScalar(body);
    assert.equal(rendered.startsWith(`${"#".repeat(fence)}"`), true, rendered);
    assert.equal(readScalar(rendered), body);
  }
  const block = cueScalar("a\\#b\nc", { indent: 2 });
  assert.equal(block.startsWith('##"""'), true, block);
  assert.equal(readScalar(block), "a\\#b\nc");
});

// ------------------------------------------------------------------- shapes

test("rendering is deterministic and never mutates the value", () => {
  const value = { b: [1, { c: "x\ny" }], a: "plain", n: null };
  const copy = structuredClone(value);
  assert.equal(toCue(value), toCue(value));
  assert.deepEqual(value, copy);
});

test("field order is the object's own insertion order", () => {
  assert.equal(toCue({ z: 1, a: 2 }), "{z: 1, a: 2}");
  assert.equal(toCue({ b: 1, a: "x".repeat(200) }), `{\n  b: 1\n  a: "${"x".repeat(200)}"\n}`);
});

test("a field label is bare when it is an identifier and quoted otherwise", () => {
  assert.equal(toCue({ plain_key: 1 }), "{plain_key: 1}");
  assert.equal(toCue({ "with space": 1 }), '{"with space": 1}');
  assert.equal(toCue({ 'quote"key': 1 }), '{"quote\\"key": 1}');
  assert.equal(toCue({ "a\nb": 1 }), '{"a\\nb": 1}');
  assert.equal(toCue({ "a\\b": 1 }), '{"a\\\\b": 1}');
  assert.equal(isCueIdentifier("plain_key"), true);
  assert.equal(isCueIdentifier("null"), false);
  assert.equal(isCueIdentifier("1abc"), false);
  // A leading `_` is a HIDDEN field in CUE: `{_x: 1}` exports `{}` and
  // `{__proto__: 1}` is refused, so neither may be a bare label.
  assert.equal(isCueIdentifier("_x"), false);
  assert.equal(isCueIdentifier("__proto__"), false);
  assert.equal(toCue({ _x: 1 }), '{"_x": 1}');
  // `{ __proto__: 1 }` is not a field in JavaScript either — the literal sets
  // the prototype — so the key has to be built the way a host builds one.
  const withProto = JSON.parse('{"__proto__": 1}');
  assert.equal(toCue(withProto), '{"__proto__": 1}');
});

test("a label uses the unfenced grammar, not the value ladder", () => {
  // Measured: a fenced label is read differently from a fenced value — CUE
  // refuses a label body holding `"` (`{#"a"b"#: 1}` is a parse error), a
  // backslash, or a `\#` escape. An unfenced literal holds every byte, and a
  // label never sits on a line a model reads as prose, so the escapes cost
  // nothing there.
  for (const key of ['quote"key', "back\\slash", "a\nb", "a\rb", "a\0b", "tab\there", '"""', "#", "_x", ""]) {
    const rendered = toCue({ [key]: 1 });
    const colon = rendered.lastIndexOf(": ");
    assert.equal(readCueString(rendered.slice(1, colon)).text, key, `label round-trips: ${JSON.stringify(rendered)}`);
  }
  assert.equal(toCue({ "a\0b": 1 }), '{"a\\u0000b": 1}');
  assert.equal(toCue({ "a\rb": 1 }), '{"a\\rb": 1}');
});

test("a NUL is escaped, because CUE refuses it in every literal shape", () => {
  assert.equal(cueScalar("\0"), '#"\\#u0000"#');
  assert.equal(cueScalar("a\0b"), '#"a\\#u0000b"#');
  assert.equal(readScalar(cueScalar("\0")), "\0");
  // Inside a block the escape rides the block's own fence.
  assert.equal(readScalar(cueScalar("a\n\0\nb", { indent: 2 })), "a\n\0\nb");
});

test("a text a block cannot hold stays inline, and its newlines are escaped", () => {
  // Two shapes, both measured: a block discards the newline before its closing
  // delimiter (so a trailing newline would vanish) and drops a bare CR (a CRLF
  // body line exports as `\n`). Such a text is a FENCED inline literal, where a
  // newline is `\`+fence+`n` and a CR is `\`+fence+`r`; an UNFENCED one is not
  // an option, since a bare newline there is `string literal not terminated`.
  for (const body of ["a\n", "\n", "a\nb\n", "a\r\nb", "a\r\nb\n", "x\"y\n", "a\0\n"]) {
    const rendered = cueScalar(body);
    assert.equal(rendered.includes(LF), false, `stays on one line: ${JSON.stringify(rendered)}`);
    assert.match(rendered, /^#"/, `a fence, never a bare literal: ${JSON.stringify(rendered)}`);
    assert.equal(readScalar(rendered), body, `round-trip: ${JSON.stringify(rendered)}`);
  }
  assert.equal(cueScalar("a\n"), '#"a\\#n"#');
});

test("a text that opens with a quote AND must stay inline takes the plain grammar", () => {
  // No fenced literal can open with a quote (`#"""` is a block opener whatever
  // the fence), a block cannot hold these texts (a trailing newline, a bare CR),
  // and a fence cannot escape a quote. The unfenced grammar is what is left:
  // `\"` there is a quote.
  for (const body of ['""a\r', '""a\n', '""\r', '""#\r', '""""a\r', '"a\r', '""\n', '"\n']) {
    const rendered = cueScalar(body);
    assert.equal(rendered.includes(LF), false, `stays on one line: ${JSON.stringify(rendered)}`);
    assert.equal(rendered.startsWith('"'), true, `unfenced: ${JSON.stringify(rendered)}`);
    assert.equal(readScalar(rendered), body, `round-trip: ${JSON.stringify(rendered)}`);
  }
  assert.equal(cueScalar('""a\r'), '"\\"\\"a\\r"');
});

test("an inline string that opens with three quotes is a block", () => {
  // `#"""` at the start of a literal is a block opener whatever the fence, and
  // CUE answers `expected newline after multiline quote`. The block form is the
  // only shape that carries such a string, and it is exact because the string
  // holds no newline to lose.
  for (const body of ['"', '""', '"""', '"""#', '"""abc', '"""\r', '"""\0']) {
    const rendered = cueScalar(body);
    assert.equal(rendered.startsWith('"""'), false, `no fenced opener: ${JSON.stringify(rendered)}`);
    assert.equal(readScalar(rendered), body, `round-trip: ${JSON.stringify(rendered)}`);
  }
  // The body line is the quote itself; the closing delimiter is the next line.
  assert.equal(cueScalar('"'), '#"""\n  "\n  """#');
});

test("a multi-line list separates its elements with commas", () => {
  // An object body may omit commas; a list may not. Two elements on separate
  // lines without `,` are `missing ',' before newline in list literal`.
  const rendered = toCue([1, 2], { width: 1 });
  assert.equal(rendered, "[\n  1,\n  2\n]");
  assert.equal(toCue([1], { width: 1 }), "[\n  1\n]");
  // A block element is still followed by the separator.
  const blocks = toCue(["a\nb", { c: "d\ne" }]);
  assert.match(blocks, /\n {4}"""#,\n/);
});

test("a small object or list stays inline; a large or block-bearing one opens up", () => {
  assert.equal(toCue({ a: 1, b: [1, 2, 3] }), "{a: 1, b: [1, 2, 3]}");
  assert.equal(toCue({ text: "x".repeat(200) }).includes(LF), true, "a value past the width opens up");
  const rendered = toCue({ a: { s: "one\ntwo" } });
  assert.equal(rendered.includes('"""'), true);
  assert.equal(rendered.includes("{s:"), false, "an object holding a block cannot stay inline");
});

test("empty containers, null and undefined have a spelling", () => {
  assert.equal(toCue({}), "{}");
  assert.equal(toCue([]), "[]");
  // `undefined` is not a JSON value and is not a field; `null` is a value.
  assert.equal(toCue({ a: null, b: undefined }), "{a: null}");
  assert.equal(toCue(undefined), "null");
  assert.equal(cueScalar(undefined), "null");
});

test("non-finite numbers never reach the output as a non-CUE literal", () => {
  assert.equal(toCue({ a: Number.NaN }), '{a: "NaN"}');
  assert.equal(toCue({ a: Infinity }), '{a: "+Infinity"}');
  assert.equal(toCue({ a: -Infinity }), '{a: "-Infinity"}');
});

test("a value CUE cannot carry is refused, not silently dropped", () => {
  assert.throws(() => toCue({ fn: () => 1 }), TypeError);
  assert.throws(() => toCue({ big: 1n }), TypeError);
  const cycle = {};
  cycle.self = cycle;
  assert.throws(() => toCue(cycle), /cycle/);
});

test("a named root is one field; the default root is bare", () => {
  assert.equal(toCue({ a: 1 }, { name: "result" }), "result: {a: 1}");
  assert.equal(toCue({ a: 1 }), "{a: 1}");
});

test("indent and width are honoured", () => {
  const rendered = toCue({ a: { b: 1, c: 2 } }, { indent: 4, width: 8 });
  assert.equal(rendered, "{\n    a: {\n        b: 1\n        c: 2\n    }\n}");
});

test("a park receipt renders as CUE with its source as a block", () => {
  const receipt = withExtra(
    buildReceipt({
      name: "fix-timeout",
      state: "parked",
      reason: "explicit",
      payload: { needle: 'const batchSize = "10";', candidates: [], nearest: null },
      source: "class Js {\n  async run() {\n    return 1;\n  }\n}",
      remainingWaitMs: 1200,
    }),
    "value",
    null,
  );
  const rendered = toCue(receipt);
  // The nine fields of §3.3, in spec order, and nothing else: `value` is
  // attached non-enumerably and is not part of the shape.
  const labels = [...rendered.matchAll(/^ {2}([A-Za-z_$][A-Za-z0-9_$]*): /gm)].map((match) => match[1]);
  assert.deepEqual(labels, [...RECEIPT_FIELDS]);
  assert.equal(rendered.includes('\\"'), false, "no escaped quotes");
  assert.equal(rendered.includes("\\n"), false, "no escaped newlines");
  const source = rendered.match(/^ {2}source: #"""\n([\s\S]*?)\n {4}"""#$/m);
  assert.notEqual(source, null, "the source is a block at the field indent");
  // The line-numbered program is content, so it sits one step inside the field.
  assert.equal(source[1].startsWith("    1 | class Js {"), true, source[1]);
});

// A null-prototype record renders exactly like a literal one.
//
// The two prototypes are the same thing to a reader — a record of fields — and
// the difference is only how it was made: a literal, or a value rebuilt by a
// host bridge (`JSON.parse`, `Object.assign(Object.create(null), …)`). §6.3 says
// the model reads the value, so both must render identically.
test("property: a null-prototype record renders exactly like its literal", () => {
  const field = fc.tuple(
    fc.stringMatching(/^[a-z][a-z0-9_]{0,7}$/),
    fc.oneof(
      fc.boolean(),
      fc.integer({ min: -1000, max: 1000 }),
      fc.string(),
      fc.constant(null),
      fc.array(fc.integer(), { maxLength: 3 }),
    ),
  );
  fc.assert(
    fc.property(fc.uniqueArray(field, { maxLength: 5, selector: ([key]) => key }), (pairs) => {
      const literal = Object.fromEntries(pairs);
      const bare = Object.assign(Object.create(null), literal);
      assert.equal(Object.getPrototypeOf(bare), null);
      assert.equal(toCue(bare), toCue(literal));
      assert.equal(toCue({ value: bare }), toCue({ value: literal }));
      assert.equal(toCue([bare]), toCue([literal]));
    }),
    { ...fcRuns(60) },
  );
});

// A value that is NOT a record must be refused, never rendered as `{}`.
//
// Measured on the real host (omp 18.14.2, `/tmp/r7`): a program read `r.ok` off
// an un-awaited `review(...)`, which is a Promise. `Object.entries` found no
// fields, so the model read `value: {}` — a claim that the refusal was empty,
// where §6.3 requires its `ok`, `code` and `message`. An empty record and an
// unrenderable value are different facts, and the second one must be named.
test("a value that is not a plain record is refused, never rendered as empty", () => {
  assert.throws(() => toCue(Promise.resolve({ ok: false })), /a Promise/);
  assert.throws(() => toCue({ value: new Map([["a", 1]]) }), /a Map/);
  assert.throws(() => toCue([new Date(0)]), /a Date/);
  // The two record prototypes stay renderable.
  assert.equal(toCue(Object.assign(Object.create(null), { ok: false })), "{ok: false}");
  assert.equal(toCue({ ok: false }), "{ok: false}");
});
