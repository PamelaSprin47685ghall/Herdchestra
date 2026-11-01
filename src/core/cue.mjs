// CUE pretty print for anything a model reads (spec §6.3, §8.2 step 7).
//
// Raw JSON shown to a model is escape noise: `"` becomes `\"`, a newline
// becomes `\n`, and a second layer of the same for every string inside a
// string. A park receipt carries a line-numbered program, a command receipt
// carries stdout, a relay receipt carries a draft; JSON escapes all of them
// and the model miscounts lines and offsets.
//
// CUE renders the same data without escapes:
//
// - a single-line string without `"`, `\`, CR or NUL is `"text"`;
// - a single-line string that holds one of them is wrapped in one or more `#`
//   (`#"a"b"#`, growing the count until the body needs no escape at all);
// - a string with a newline is a `"""` block, unless it ends in a newline or
//   holds a bare CR (see `wantsBlock`: CUE would drop one byte either way). The
//   body lines and the closing `"""` sit at the field's indent, and CUE strips
//   the closing delimiter's indent from every body line, so the content keeps
//   its exact bytes and its own indentation;
// - a block whose body holds `"""` is a raw block `#"""` ... `"""#`, with the
//   `#` count grown until the body cannot close the delimiter early;
// - a string that OPENS with a quote is a block too, because no fenced inline
//   literal may open with one;
// - a field name takes the UNFENCED string grammar, not the value ladder: CUE
//   refuses a fenced label body holding `"`, `\` or a `\#` escape.
//
// Five facts about CUE that the shapes above do not state, all measured against
// the reference implementation (`cue eval` on every case below):
//
// - inside a fenced literal the ONLY escape is `\` + the same `#` run as the
//   fence: `\` alone, `\n`, `\(`, `\"` are plain text there. So a body writes
//   each backslash as `\` + fence + `\`, and `\(` needs nothing.
// - the fence of a block may never be empty, and an empty-fence block turns
//   `\n` into a newline instead of the two characters. Every block therefore
//   carries at least one `#`.
// - a carriage return inside any literal is DISCARDED. A command's stdout can
//   carry CRLF (the command port passes bytes through unchanged), and the model
//   reads that output as ground truth, so a lost CR would be a silent lie. A
//   CR is therefore always written as the escape `\r` — and a text holding one
//   stays INLINE, because a block has no way to keep it.
// - a NUL is ILLEGAL in source in every literal shape (`illegal character
//   NUL`), so it is written as the escape `\u0000` at the literal's own level.
// - the fence must also pass any `\` + `#`×m run the body already holds: under
//   a one-`#` fence, the body's own `\#` reads as the start of an escape.
//
// The renderer is total over JSON values, never mutates its input, reads
// enumerable own keys only (core attaches `value`/`result` non-enumerably), and
// is deterministic: the same value always gives the same bytes. Field order is
// the object's own insertion order — nothing here re-sorts or renames a field,
// because a host receipt gains fields as the host learns them.

import { isObject } from "./util.mjs";

export const DEFAULT_INDENT = 2;
export const DEFAULT_WIDTH = 100;

// A bare field name. A leading `_` is excluded on purpose: CUE reads `_x` as a
// HIDDEN field and `__x` as a reserved name, so `{_x: 1}` exports `{}` and
// `{__proto__: 1}` is refused. Neither is the field a caller meant.
const IDENTIFIER = /^[A-Za-z$][A-Za-z0-9_$]*$/;

// CUE keywords and predeclared names: a field labelled with one of them has to
// be quoted, or the render would not read back as a label.
const RESERVED = new Set([
  "package", "import", "for", "in", "if", "let", "true", "false", "null",
  "bool", "int", "float", "string", "number", "bytes", "struct", "list",
  "and", "or", "div", "mod", "quo", "rem",
]);

const fatal = (message) => {
  throw new TypeError(message);
};

const spaces = (count) => " ".repeat(count);

// A plain data carrier: an object whose prototype is `Object.prototype` or
// `null`. The two are the same thing to a reader — a record of fields — and the
// difference is only how it was made: a literal, or a value rebuilt by a host
// bridge (`JSON.parse`, `Object.assign(Object.create(null), …)`).
//
// A Promise, a Map, a Date or a class instance is NOT one. Its state lives
// behind methods, so it has no fields to render: reporting `{}` for it is not a
// rendering of the value but a claim that the value is empty. That claim is
// what reached a model on the real host (omp 18.14.2, `/tmp/r7`): a program
// read `r.ok` off an unawaited `review(...)`, which is a Promise, so every field
// was `undefined` and the refusal arrived as `value: {}`. The renderer refuses
// such a value instead, so the transport bug that produced it is named rather
// than hidden behind an empty record.
const isRecord = (value) => {
  const proto = Object.getPrototypeOf(value);
  return proto === null || proto === Object.prototype;
};

// A string that can be written as it stands: no quote, no backslash, no CR, no
// NUL and no newline. A string holding none of them is plain; every other
// string takes a fence, so the body never carries a bare backslash, a bare CR,
// a bare NUL or a bare newline — the four characters an unfenced literal cannot
// hold as themselves.
const plainInline = (text) =>
  !text.includes('"') && !text.includes("\\") && !text.includes("\r") && !text.includes("\0") && !text.includes("\n");

// The smallest `#` fence for a raw inline string: one run must not appear in
// the body, `"` + `#`×n, because it would close the literal. The body's own
// escape delimiter is `\` + the literal's fence, so a body holding
// `\` + `#`×m needs a fence past m as well — otherwise the renderer's escapes
// and a run the body already holds are read at different levels.
const inlineFence = (text) => {
  let level = 1;
  while (text.includes(`"${"#".repeat(level)}`) || text.includes(`\\${"#".repeat(level)}`)) level += 1;
  return level;
};

// The `#` count a block needs. A block body is read literally except for its
// own escape delimiter (`\` + `#`×n), so the count must cover every `"""` run
// the closing delimiter could end on — and, for the same reason as an inline
// literal, every `\` + `#`×n run the body already holds. The fence never drops
// to zero: a zero fence would turn every backslash in the body into an escape.
const blockLevel = (text) => {
  let level = 1;
  while (text.includes(`"""${"#".repeat(level)}`) || text.includes(`\\${"#".repeat(level)}`)) level += 1;
  return level;
};

// A body spelled so the literal holds exactly the given text. Under a fence the
// only escape is `\` + fence + the escaped character, so a backslash is
// written `\`+fence+`\`, a carriage return `\`+fence+`r` (CUE DROPS a bare CR
// from every literal, and a command's CRLF output is a fact the model must see)
// and a NUL `\`+fence+`u0000` (a bare NUL is `illegal character NUL`, in a
// literal of any shape). Every body that needs one of them carries a fence:
// `plainInline` refuses all three, so a fenced literal is what gets chosen. A
// newline is the fourth escape: `inlineString` writes it, because a block keeps
// its line breaks as line breaks.
const escapeBody = (text, fence) =>
  text
    .replaceAll("\\", `\\${fence}\\`)
    .replaceAll("\r", `\\${fence}r`)
    .replaceAll("\0", `\\${fence}u0000`);

// One scalar string on one line: the plain literal when the bytes allow it, a
// fenced one otherwise. The caller decides when a block is needed — this
// function never emits a newline.
const inlineString = (text) => {
  if (plainInline(text)) return `"${text}"`;
  // The fence must cover every run the body holds: `"` + `#`×n would close the
  // literal, and `\` + `#`×n would move the escape delimiter. A CR or a NUL is
  // escaped as `\` + fence + `r` / `\` + fence + `u0000`, which any level ≥ 1
  // reads.
  const level = inlineFence(text);
  const fence = "#".repeat(level);
  // A newline is the escape `\`+fence+`n`, exactly like a backslash or a CR:
  // under a fence, a bare `\n` would be plain text.
  return `${fence}"${escapeBody(text, fence).replaceAll("\n", `\\${fence}n`)}"${fence}`;
};

// One string as the value of a field at `indent`. A string with no newline
// stays on the field's line. A block opens on the field's line, carries its
// body one step deeper, and closes with a delimiter at `indent`: CUE strips the
// closing delimiter's indentation from every body line, so the body's own bytes
// are preserved and the block lines up under its field.
//
// Two details follow from that rule and from what the language accepts:
// - an empty body line is emitted bare, because padding it would add trailing
//   spaces to the content;
// - the closing delimiter carries EXACTLY the indent the body lines carry. A
//   closing line with even one more space is `invalid whitespace` to CUE
//   (measured: `#"""` / `x` / `  """#` fails, `#"""` / `  x` / `  """#` reads
//   `"x"`). A whitespace-only line is the exception: CUE strips nothing from a
//   line that is shorter than the indent, so the bare line keeps its length.
const renderBlock = (text, indent, step) => {
  const level = blockLevel(text);
  const fence = "#".repeat(level);
  const body = escapeBody(text, fence).split("\n");
  const pad = spaces(indent + step);
  // An empty line is emitted bare: padding it would put spaces in front of the
  // closing indent, and CUE would strip only the indent, leaving the padding as
  // trailing content the source never held.
  return [`${fence}"""`, ...body.map((line) => (line === "" ? "" : `${pad}${line}`)), `${pad}"""${fence}`].join("\n");
};

// True when the text takes a block: it holds a newline, or it OPENS with a
// quote — no fenced inline literal can open with one, since CUE reads `#"""`
// there as a block opener and answers `expected newline after multiline quote`,
// whatever the fence.
//
// Two texts keep the inline form although they hold a newline, and both are
// CUE's rules, not preferences:
// - a text that ENDS in a newline: CUE discards the newline in front of the
//   closing delimiter, so a block would lose the last byte;
// - a text holding a bare carriage return: a block reads a bare CR as a line
//   ending and DROPS it (measured: a CRLF body line exports as `\n`), while an
//   inline literal can spell it `\`+fence+`r`.
// Inline, the newlines are the escape `\`+fence+`n` — exact, just not pretty.
const wantsBlock = (text) =>
  (text.includes("\n") || text.startsWith('"')) && !text.endsWith("\n") && !text.includes("\r");

// A text that must stay on one line but OPENS with a quote cannot be a fenced
// inline literal either (`#"""` is a block opener, whatever the fence), and a
// fenced literal cannot escape a quote. The unfenced grammar can: `\"` is a
// quote there. It is the noisy spelling, so it is the last resort, and it is
// the only exact one left.
const renderString = (text, indent, step) => {
  if (wantsBlock(text)) return renderBlock(text, indent, step);
  if (text.startsWith('"')) return plainString(text);
  return inlineString(text);
};

// The escapes of an UNFENCED literal, and only those: `\n` is a newline here,
// where under a fence it is two characters. This is the CUE/JSON string
// grammar, and the only spelling available to a label.
const PLAIN_ESCAPES = {
  "\\": "\\\\",
  '"': '\\"',
  "\n": "\\n",
  "\r": "\\r",
  "\t": "\\t",
  "\0": "\\u0000",
  "\b": "\\b",
  "\f": "\\f",
};

const plainString = (text) => `"${[...text].map((char) => PLAIN_ESCAPES[char] ?? char).join("")}"`;

// A field label. A label takes the JSON string grammar, NOT the value ladder,
// and the difference is measured: in a fenced label CUE refuses a body that
// holds `"`, `\` or `#` (a `\#` escape is not read back at a label's position),
// so `{#"a"b"#: 1}` is a parse error and the escaped forms are too. An unfenced
// literal holds every byte through the standard escapes.
const label = (key) => {
  if (IDENTIFIER.test(key) && !RESERVED.has(key)) return key;
  return plainString(key);
};

// A number or boolean or null is its own literal. `undefined` is null: a host
// executor may legitimately answer nothing, and a rendering that throws would
// turn "no value" into a tool failure.
const scalarText = (value) => {
  if (value === null || value === undefined) return "null";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") {
    if (Number.isNaN(value)) return '"NaN"';
    if (!Number.isFinite(value)) return value > 0 ? '"+Infinity"' : '"-Infinity"';
    return String(value);
  }
  return null;
};

const describe = (value) => {
  if (typeof value === "function") return "a function";
  if (typeof value === "symbol") return "a symbol";
  if (typeof value === "bigint") return "a bigint";
  // A promise, a map, a date: name the KIND, not `a object`. The name is what
  // tells a caller which value it handed over — "a Promise" points straight at
  // the un-awaited call that produced it.
  if (value !== null && typeof value === "object") {
    const proto = Object.getPrototypeOf(value);
    if (proto === Promise.prototype) return "a Promise";
    if (proto === Map.prototype) return "a Map";
    if (proto === Set.prototype) return "a Set";
    if (proto === Date.prototype) return "a Date";
  }
  return `a ${value === null ? "null" : typeof value}`;
};

// Render a value at `indent`. `inline` asks for the single-line form and answers
// null when the value cannot fit on one line (a block string, or a nested
// container that does not fit the width).
//
// A container is tried on one line first, but only when it holds no block: a
// block string inside an inline object would put a newline in the middle of a
// `{...}` and break the line. So the inline attempt reports "impossible" as
// soon as a string needs a block, instead of joining around it.
const renderValue = (value, { indent, step, width, seen, inline }) => {
  const scalar = scalarText(value);
  if (scalar !== null) return scalar;
  if (typeof value === "string") {
    // The same predicate the ladder uses, so "this string is a block" has one
    // definition: `inline` asks whether a single-line form exists at all, and a
    // container's inline attempt reports "impossible" as soon as a value needs
    // a block.
    if (wantsBlock(value) && inline) return null;
    return renderString(value, indent, step);
  }
  if (typeof value === "object") {
    if (seen.has(value)) fatal("toCue cannot render a cycle");
    const isArray = Array.isArray(value);
    // A RECORD is any non-array object that is a plain data carrier: its
    // prototype is `Object.prototype` (an object literal, a class instance's
    // data) or `null` (what `JSON.parse` gives when a host bridge rebuilds a
    // value, and what an unawaited async member resolves to). Anything else —
    // a Promise, a Map, a Date, a class with its own prototype — is NOT a
    // record, and rendering it as `{}` would silently drop every field.
    //
    // This is the measured failure: a program that read `r.ok` from an
    // unawaited `review(...)` got `undefined`, because the value the host
    // carried was a Promise, `Object.entries` found no fields, and the model
    // read `value: {}` where §6.3 requires the refusal's `ok`, `code` and
    // `message`. A null-prototype object is the shape a receipt takes on its
    // way back from a host, so it must render like any other record.
    if (!isArray && !isRecord(value)) {
      fatal(`toCue cannot render ${describe(value)}: it is not a plain record`);
    }
    // `undefined` is not a JSON value: it is not a field. An array keeps its
    // positions (an index is a position, not a field name), so an undefined item
    // renders as `null` there.
    const entries = isArray
      ? value.map((item, index) => [String(index), item])
      : Object.entries(value).filter(([, item]) => item !== undefined);
    seen.add(value);
    try {
      if (entries.length === 0) return isArray ? "[]" : "{}";
      const open = isArray ? "[" : "{";
      const close = isArray ? "]" : "}";
      const flat = entries.map(([key, item]) => {
        const rendered = renderValue(item, { indent, step, width, seen, inline: true });
        if (rendered === null) return null;
        return isArray ? rendered : `${label(key)}: ${rendered}`;
      });
      if (!flat.includes(null)) {
        const joined = `${open}${flat.join(", ")}${close}`;
        if (indent + joined.length <= width) return joined;
      }
      if (inline) return null;
      const pad = spaces(indent + step);
      const lines = entries.map(([key, item], index) => {
        const rendered = renderValue(item, { indent: indent + step, step, width, seen, inline: false });
        // An object body may omit its commas, but a LIST may not: two elements
        // on separate lines need `,` between them, or CUE answers `missing ','
        // before newline in list literal`. A last element needs none.
        if (isArray) return `${pad}${rendered}${index === entries.length - 1 ? "" : ","}`;
        // A block value already carries its own closing indent; only its first
        // line belongs after the label.
        return `${pad}${label(key)}: ${rendered}`;
      });
      return [open, ...lines, `${spaces(indent)}${close}`].join("\n");
    } finally {
      seen.delete(value);
    }
  }
  fatal(`toCue cannot render ${describe(value)}`);
};

// `toCue(value)` renders a bare value; `toCue(value, { name })` renders one
// field. A bare object is `{`/`}` at column zero with its fields one step in;
// a named value is `name: ...` with the same indentation inside.
export const toCue = (value, options = {}) => {
  if (options === null || typeof options !== "object") fatal("toCue options must be an object");
  const { name = null, indent = DEFAULT_INDENT, width = DEFAULT_WIDTH } = options;
  if (name !== null && (typeof name !== "string" || name.length === 0)) {
    fatal("toCue name must be a non-empty string or null");
  }
  if (!Number.isInteger(indent) || indent < 1) fatal("toCue indent must be a positive integer");
  if (!Number.isInteger(width) || width < 1) fatal("toCue width must be a positive integer");
  if (name === null) {
    return renderValue(value, { indent: 0, step: indent, width, seen: new Set(), inline: false });
  }
  const rendered = renderValue(value, { indent: 0, step: indent, width, seen: new Set(), inline: false });
  return `${label(name)}: ${rendered}`;
};

// One value on its own line, with no container around it: the ladder for a
// single string or scalar, used where the caller already names the field in its
// own prose (an error message, a diagnostic line).
export const cueScalar = (value, options = {}) => {
  if (options === null || typeof options !== "object") fatal("cueScalar options must be an object");
  const { indent = DEFAULT_INDENT, width = DEFAULT_WIDTH } = options;
  if (!Number.isInteger(indent) || indent < 1) fatal("cueScalar indent must be a positive integer");
  if (!Number.isInteger(width) || width < 1) fatal("cueScalar width must be a positive integer");
  return renderValue(value, { indent: 0, step: indent, width, seen: new Set(), inline: false });
};

export const isCueIdentifier = (key) => typeof key === "string" && IDENTIFIER.test(key) && !RESERVED.has(key);

// Kept for callers that want the plain-object test core already uses.
export const isCueObject = (value) => isObject(value);
