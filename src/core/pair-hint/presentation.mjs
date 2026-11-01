// Presentation facts and rendering (spec §6.1, §6.1, §6.1).
//
// One presentation is one pair of synthetic messages that carries the session's
// language, its pair resources and, when there are any, the session's unsettled
// park handles. The pair is: a synthetic tool call with the empty tool name and
// its synthetic result. Both messages are derived from a fact, never from the
// previous request, so rebuilding after a cold start yields the same bytes.
//
// A fact is `{ epoch, digest, handles, after, inserted, text, entries }`:
// - `epoch` is the presentation generation. Bytes never change inside one epoch,
//   which is why `text` is frozen with the fact instead of re-rendered.
// - `digest` is a hash of `text`; a stored fact whose text does not match is
//   corruption, not a rebuild.
// - `handles` is the sorted list of unsettled park handles, as frozen.
// - `after` is how many messages of the next request come before the pair.
// - `inserted` is where the pair sat in the request as last sent.
// - `text` and `entries` are the frozen presentation itself.

import { createHash } from "node:crypto";
import { invalidArgument } from "./errors.mjs";
import { assertLanguage, LANGUAGES, RESOURCE_NAMES } from "./language.mjs";
import { isNonEmptyString } from "../util.mjs";

export const SYNTHETIC_TOOL_NAME = "";

// The one place in the hint where the model is told which language to think and
// speak in (§6.1). It is rendered in the language the session was bound to, so
// the directive is the first thing the model reads about language.
const THINKING_RULE = Object.freeze({
  [LANGUAGES.ENGLISH]:
    "Think and speak in Simplified Chinese: your reasoning and every outward-facing output use Simplified Chinese.",
  [LANGUAGES.SIMPLIFIED_CHINESE]:
    "思考过程与全部对外输出，一律用简体中文。工具名、参数名、字段、枚举字面量、路径与命令保持原样，不翻译。",
});

// One resource id per line, in the order they are rendered (§6.1). A line that
// does not name one of them is a resource failure, not a silent skip.
const RESOURCE_LINE = /^<!-- pair-hint: resource=([a-z0-9-]+) -->$/;

export const EMPTY_HANDLES_TEXT = Object.freeze({
  English: "None: no park of this session is unsettled.",
  SimplifiedChinese: "无：本会话没有尚未结清的 park。",
});

const HANDLES_HEADING = Object.freeze({
  English: "Unsettled park handles of this session:",
  SimplifiedChinese: "本会话尚未结清的 park handles：",
});

const HANDLES_ORDER = Object.freeze({
  English:
    "These handles are listed for one reason: state your position on each. They do not force a resume, and nothing is restored on your behalf.",
  SimplifiedChinese:
    "列出它们只为一件事：对每一个表态。这不强制恢复；系统也不会替你恢复任何一处。",
});

export const digestOf = (text) => createHash("sha256").update(text, "utf8").digest("hex");

const CALL_ID_PREFIX = "pair-hint:";
const CALL_ID_PATTERN = /^pair-hint:(\d+):([0-9a-f]+)$/;

const requireText = (name, value) => {
  if (typeof value !== "string" || value.trim() === "") {
    throw invalidArgument(`${name} must be non-empty text`);
  }
  return value.trim();
};

// Sorted, unique, non-empty handle names. The order is lexical so the fact does
// not depend on how the caller collected them.
export const normaliseHandles = (handles = []) => {
  if (!Array.isArray(handles)) throw invalidArgument("handles must be an array");
  for (const handle of handles) {
    if (!isNonEmptyString(handle)) {
      throw invalidArgument("each handle must be a non-empty string", { handle });
    }
  }
  return Object.freeze([...new Set(handles)].sort());
};

// The handles section. Sorted and unique, so the text does not depend on the
// order the surface collected the handles in (§6.1).
export const renderHandles = ({ language, handles = [] }) => {
  const name = assertLanguage(language);
  const ordered = normaliseHandles(handles);
  const list =
    ordered.length === 0
      ? EMPTY_HANDLES_TEXT[name]
      : [
          HANDLES_HEADING[name],
          ...ordered.map((handle) => `- ${handle}`),
          "",
          HANDLES_ORDER[name],
        ].join("\n");
  return `# handles\n\n${list}`;
};

// Render the hint from a parsed presentation: `entries` is the resource list in
// order, `blocks` the resource texts keyed by id. The handles section comes
// last, so a change to it only ever moves the tail of the request.
export const renderPresentation = ({ language, entries, blocks, handles = [] }) => {
  const name = assertLanguage(language);
  if (!Array.isArray(entries) || entries.length === 0) {
    throw invalidArgument("a presentation needs a non-empty resource list");
  }
  const body = [`# language\n\n${THINKING_RULE[name]}`];
  for (const id of entries) {
    // The marker line is how `parseHint` finds the resource again; without it
    // a stored hint could not be rebuilt after a restart (§6.1 step 6).
    body.push(`<!-- pair-hint: resource=${id} -->\n\n${requireText("resource block", blocks?.[id])}`);
  }
  body.push(renderHandles({ language: name, handles }));
  return body.join("\n\n");
};

// Split a rendered hint back into its resources. This is the whole cold-start
// contract (§6.1 step 6): the journal keeps the hint text, and the next process
// parses the same text instead of re-reading resources that may have changed
// since. The parse is exact: an unknown or repeated resource line is a failure,
// never a silent skip.
export const parseHint = (text) => {
  if (typeof text !== "string" || text.trim() === "") {
    throw invalidArgument("hint text must be non-empty");
  }
  const lines = text.split("\n");
  const entries = [];
  const blocks = {};
  let current = null;
  let buffer = [];
  let handlesBlock = null;
  let handlesBuffer = null;

  const flush = () => {
    if (current === null) return;
    // The renderer writes `marker + blank + text`, so the block's own text
    // starts after the blank line; trimming it makes render(parse(x)) == x.
    blocks[current] = buffer.join("\n").trim();
    buffer = [];
  };

  for (const line of lines) {
    const marker = RESOURCE_LINE.exec(line);
    if (marker !== null) {
      flush();
      const id = marker[1];
      if (blocks[id] !== undefined || handlesBlock !== null) {
        throw invalidArgument("hint text repeats or reorders a resource", { id });
      }
      current = id;
      entries.push(id);
      continue;
    }
    if (line.trim() === "# handles" && current !== null) {
      // The last resource's text ends before the handles section: flush it now,
      // and stop collecting into it.
      flush();
      current = null;
      handlesBlock = "";
      handlesBuffer = [];
      continue;
    }
    if (handlesBuffer !== null) handlesBuffer.push(line);
    else if (current !== null) buffer.push(line);
  }
  flush();
  if (entries.length === 0) throw invalidArgument("hint text carries no resource markers");
  if (handlesBuffer === null) throw invalidArgument("hint text carries no handles section");
  handlesBlock = handlesBuffer.join("\n").trim();
  return { entries, blocks, handles: handlesBlock };
};

// The facts that decide where the pair goes (§6.1, "位置由持久事实决定").
//
// - `after` is how many request messages came before the pair when the pair was
//   first placed in this epoch. Every later request of the epoch puts the pair
//   back after that many of its own messages. This is what makes the prefix rule
//   hold for a host that keeps its own history: its later requests begin with
//   exactly the messages that came before the pair, so the pair lands in the
//   same place and every earlier message keeps its bytes.
// - `inserted` is the index of the pair in the request as last sent. It is only
//   meaningful together with the exact request; the next request is a different
//   list, and its own indices differ.
// - `position` is the index of the pair in the request as last sent, kept so a
//   reader can see where it sat without the request.
export const CALL_POSITIONS = Object.freeze({
  AFTER: "after",
  INSERTED: "inserted",
});

export const assertIndex = (name, value) => {
  if (!Number.isInteger(value) || value < 0) {
    throw invalidArgument(`${name} must be a non-negative integer`, { [name]: value });
  }
  return value;
};

// One presentation generation.
export class PresentationFact {
  #after;
  #digest;
  #epoch;
  #entries;
  #handles;
  #inserted;
  #text;

  constructor({ epoch, digest, handles = [], after = null, inserted = null, text = null, entries = null }) {
    if (!Number.isInteger(epoch) || epoch < 0) {
      throw invalidArgument("epoch must be a non-negative integer", { epoch });
    }
    if (!isNonEmptyString(digest)) throw invalidArgument("digest must be a non-empty string");
    this.#epoch = epoch;
    this.#digest = digest;
    this.#handles = normaliseHandles(handles);
    this.#after = after === null ? null : assertIndex("after", after);
    this.#inserted = inserted === null ? null : assertIndex("inserted", inserted);
    if (text !== null && digestOf(text) !== digest) {
      throw invalidArgument("frozen text does not match the digest", { epoch });
    }
    this.#text = text;
    this.#entries = entries === null ? null : Object.freeze([...entries]);
  }

  get epoch() {
    return this.#epoch;
  }

  get digest() {
    return this.#digest;
  }

  get handles() {
    return this.#handles;
  }

  // The hint text frozen for this epoch, or null before the first request.
  get text() {
    return this.#text;
  }

  // The resource ids in presentation order, or null.
  get entries() {
    return this.#entries;
  }

  // Where the next request puts the pair: after this many of its messages, or
  // null when this epoch has not placed the pair yet.
  get after() {
    return this.#after;
  }

  // Where the pair sat in the request as last sent, or null.
  get inserted() {
    return this.#inserted;
  }

  // The position recorded in the call argument. It names the last sent request,
  // so it is `inserted`; it is also what a reader of the request sees.
  get position() {
    return this.#inserted;
  }

  // A copy that says where the next request puts the pair, and where it sat in
  // the one just sent. The digest and handles are unchanged: this decides
  // placement, never content.
  at({ after, inserted }) {
    return new PresentationFact({
      epoch: this.#epoch,
      digest: this.#digest,
      handles: this.#handles,
      after,
      inserted,
      text: this.#text,
      entries: this.#entries,
    });
  }

  toRecord() {
    return {
      epoch: this.#epoch,
      digest: this.#digest,
      handles: [...this.#handles],
      after: this.#after,
      inserted: this.#inserted,
      text: this.#text,
      entries: this.#entries === null ? null : [...this.#entries],
    };
  }

  static fromRecord(record) {
    if (record === null || typeof record !== "object") {
      throw invalidArgument("a fact record must be an object");
    }
    return new PresentationFact({
      epoch: record.epoch,
      digest: record.digest,
      handles: record.handles ?? [],
      after: record.after ?? null,
      inserted: record.inserted ?? record.position ?? null,
      text: record.text ?? null,
      entries: record.entries ?? null,
    });
  }
}

// Build the fact for a rendered hint. `epoch` is supplied by the session's
// epoch owner (the presentation surface); the fact only records it.
export const factFor = ({
  epoch,
  text,
  handles = [],
  after = null,
  inserted = null,
  entries = null,
  freeze = false,
}) =>
  new PresentationFact({
    epoch,
    digest: digestOf(text),
    handles,
    after,
    inserted,
    text: freeze ? text : null,
    entries: freeze ? entries : null,
  });

// The synthetic pair. The call argument is the fact itself, so the result is
// derived from the call and nothing else.
export const syntheticMessages = ({ fact, text }) => {
  if (!(fact instanceof PresentationFact)) {
    throw invalidArgument("syntheticMessages needs a PresentationFact");
  }
  if (digestOf(text) !== fact.digest) {
    throw invalidArgument("hint text does not match the fact digest", { epoch: fact.epoch });
  }
  const call = {
    role: "assistant",
    content: [
      {
        type: "tool-call",
        toolName: SYNTHETIC_TOOL_NAME,
        toolCallId: syntheticCallId(fact),
        input: {
          epoch: fact.epoch,
          digest: fact.digest,
          handles: [...fact.handles],
          position: fact.inserted,
          after: fact.after,
        },
      },
    ],
  };
  const result = {
    role: "tool",
    content: [
      {
        type: "tool-result",
        toolName: SYNTHETIC_TOOL_NAME,
        toolCallId: syntheticCallId(fact),
        output: { type: "text", value: text },
      },
    ],
  };
  return Object.freeze([call, result]);
};

// Stable call id: the same fact always yields the same id, so a replayed
// request carries the same pair.
export const syntheticCallId = (fact) => `${CALL_ID_PREFIX}${fact.epoch}:${fact.digest.slice(0, 16)}`;

// Read `{ epoch, digest }` out of a synthetic call id, or null when the id is
// not ours. Epoch and digest are numbers and hex only, so the parse cannot be
// fooled by a stray colon.
export const callIdParts = (callId) => {
  if (typeof callId !== "string") return null;
  const match = CALL_ID_PATTERN.exec(callId);
  if (match === null) return null;
  return { epoch: Number(match[1]), digest: match[2] };
};

// Every injected pair in a message list, in order. A pair is an assistant
// message whose content holds the synthetic call, plus the tool message right
// after it that carries the matching result.
export const findAllInjections = (messages) => {
  if (!Array.isArray(messages)) throw invalidArgument("messages must be an array");
  const found = [];
  for (let index = 0; index < messages.length; index += 1) {
    const message = messages[index];
    if (message?.role !== "assistant" || !Array.isArray(message.content)) continue;
    const call = message.content.find(
      (part) => part?.type === "tool-call" && part.toolName === SYNTHETIC_TOOL_NAME,
    );
    if (call === undefined) continue;
    const next = messages[index + 1];
    const result =
      next?.role === "tool" && Array.isArray(next.content)
        ? next.content.find((part) => part?.type === "tool-result" && part.toolCallId === call.toolCallId)
        : undefined;
    found.push({ index, call, result: result ?? null });
  }
  return found;
};

// The first injected pair, or null.
export const findInjection = (messages) => findAllInjections(messages)[0] ?? null;
