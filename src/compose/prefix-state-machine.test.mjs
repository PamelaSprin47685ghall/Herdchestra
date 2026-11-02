// The cross-host prefix guarantee, as a state machine (根 §6.1, §6.2, §6.3).
//
// ## What this proves
//
// §6.1 says the next request must begin with the messages the model already
// read; §6.2 says the Scope projection may delete old beats and replace the
// last one in place. Those two rules meet on every turn, and the meeting is
// where the drift bugs lived: a `resetScope` pruned the copy and the next turn
// was refused with `PREFIX_DRIFT`, on all three hosts, with nothing wrong in the
// conversation.
//
// So the property is stated over SEQUENCES, not single requests:
//
//   for every legal sequence of turns, no request is ever refused for a prefix
//   reason, and every request after a legitimate projection boundary begins the
//   chain the projection decided;
//
// and its dual, which is what stops the first from being vacuous:
//
//   for every semantic mutation of a message the model already read, with no
//   projection behind it, the request IS refused with PREFIX_DRIFT.
//
// The first alone would be satisfied by never checking anything; the second
// alone would forbid Scope. Both together say: the chain bends exactly where
// the spec says it bends, and nowhere else.
//
// ## Why a state machine and not examples
//
// Each host's projection is a function of (host history, Scope facts, Pair Hint
// facts). The bugs were all in the INTERACTION — a `removeLast` settled while a
// call was in flight, a restart between two beats, a streaming message finalised
// after the pair was placed. A generator over those interactions finds them;
// hand-written cases only find the ones already suspected.
//
// ## A generated sequence must be one a host can produce
//
// A failure is only evidence when the sequence is legal. `legalTurn` decides
// that for the TURNS, and `Session.compact` decides it for the SHAPE of a
// compaction — both from the hosts' own source, cited at the rule. Getting the
// second one wrong is how a generator reports a bug that does not exist:
// keeping only the last message as a compaction tail produced
// `[summary, tool(c6)]`, a result whose call was summarised away, which all
// three hosts refuse to build (OMP `findValidCutPoints`, Cline
// `isSafeCutBoundary`, OpenCode `recentStart`). The suite failed intermittently
// on that shape — fast-check seed 1221354670 — and the shape was the defect.
// When a generated case fails, first ask which host line makes it reachable.
//
// Run with: `node --test "src/compose/prefix-state-machine.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import { PairHint, MemoryJournal, REBASE_REASONS, REQUEST_KINDS } from "../core/pair-hint/pair-hint.mjs";
import { ScopeHooks } from "../core/scope/hooks.mjs";
import { canonicalKey } from "../core/pair-hint/canonical.mjs";
import { toCueToolResults, toItems as clineItems, selectKept as clineKept, toPairHintMessages as clineChain, isCompactedCopy as clineIsCompacted } from "../host-cline/request.mjs";
import { selectKept as ompKept, toItems as ompItems, toPairHintMessages as ompChain } from "../host-omp/request.mjs";
import { itemsFromMessages as ocItems, messagesFromItems as ocMessages, toHintMessages as ocChain } from "../host-opencode/spec/message-adapter.mjs";
import { projectionBoundary, shouldAnchor } from "./request-boundary.mjs";
import { fcRuns } from "../core/test-runs.mjs";

// ---------------------------------------------------------------------------
// The three hosts, as one interface
// ---------------------------------------------------------------------------

// Each adapter is described by what it does to a request copy, so the state
// machine drives all three through the same steps:
//
//   items(copy)      host messages -> Scope items
//   kept(copy, x)    host messages the projection keeps (x is the Scope answer)
//   chain(copy)     host messages -> the copy the Pair Hint compares
//   history(copy)   the HOST's own copy -> Scope items, before the adapter's
//                   filter took anything away
//
// The three differ in dialect, and that is the point: one property must hold
// for all of them, so the property may not depend on any adapter's internals.
const HOSTS = Object.freeze({
  omp: Object.freeze({
    items: (copy) => ompItems(copy),
    kept: (copy, projected) => ompKept(copy, projected),
    chain: (copy) => ompChain(copy),
    // §6.3: structured results are rendered as CUE before anything else looks.
    present: (copy) => copy,
    // OMP DOES compact (`session.compact`, wired through
    // `buildSideRequestContext`), and the compaction is durable: the next turn
    // is built from the compacted history. OMP puts no tag on the summary
    // message, so the model recognises the rewrite the only way OMP allows —
    // by the shape the compaction produces (a summary standing where the
    // conversation used to begin). See `compactedCopy`.
    compacted: (copy) => compactionShape(copy),
    // What the host held, before `selectKept` removed anything.
    history: (copy) => ompItems(copy),
  }),
  cline: Object.freeze({
    items: (copy) => clineItems(toCueToolResults(copy)),
    kept: (copy, projected) => clineKept(toCueToolResults(copy), projected),
    chain: (copy) => clineChain(toCueToolResults(copy)),
    present: (copy) => toCueToolResults(copy),
    // The adapter's own predicate, imported rather than re-implemented: a second
    // copy could drift, and the suite would then pass a case the real adapter
    // fails.
    compacted: (copy) => clineIsCompacted(copy) || compactionShape(copy),
    // The projection's input is already CUE-rendered (§6.3), so the host's own
    // history is read the way this adapter reads it.
    history: (copy) => clineItems(toCueToolResults(copy)),
  }),
  opencode: Object.freeze({
    items: (copy) => ocItems(copy),
    kept: (copy, projected) => ocMessages(projected),
    chain: (copy) => ocChain(copy),
    present: (copy) => copy,
    // OpenCode's compaction runs through a SEPARATE hook we do not register, so
    // it never reaches the context path.
    compacted: (copy) => compactionShape(copy),
    history: (copy) => ocItems(copy),
  }),
});

// The marker the model's `compact()` writes, and the one every host's predicate
// reads. It is deliberately NOT Cline's `metadata.kind === "compaction_summary"`:
// that is one host's wire format, and a state machine that required it on all
// three would be modelling Cline's tag rather than the fact it stands for.
//
// The fact is "this copy begins with a summary that replaced the earlier
// conversation". `compact()` marks it so the model can tell it from an
// ordinary user message, and each host's predicate accepts either its own
// native tag (Cline's `metadata.kind`) or this neutral one.
const COMPACTED_MARK = "herd:compacted";

const compactionShape = (copy) =>
  Array.isArray(copy) && copy.length > 0 && copy[0]?.metadata?.kind === COMPACTED_MARK;

// The text of the summary a copy opens with, or null. This is the summary's
// IDENTITY: two requests built from the same compacted history carry the same
// text and are the same rewrite; a second compaction merges into new text and is
// a new rewrite.
const summaryOf = (copy) => {
  if (!compactionShape(copy)) return null;
  const first = copy[0];
  const parts = Array.isArray(first?.content) ? first.content : [];
  const text = parts.map((part) => (typeof part?.text === "string" ? part.text : "")).join("");
  return `${first?.metadata?.kind ?? ""}\u0000${text}`;
};

const HOST_NAMES = Object.freeze(Object.keys(HOSTS));

// ---------------------------------------------------------------------------
// One session, driven by turns
// ---------------------------------------------------------------------------

// A host history plus the two books (Scope and Pair Hint) that decide its
// projection. `apply(turn)` performs one turn and returns the request copy the
// provider would receive.
class Session {
  #host;
  #history;
  #hint;
  #scope;
  #sessionId;
  #turn;
  #epochs;
  #anchors;
  #store;
  #sent;
  #lastSent = null;
  #issued = new Set();
  #answered = new Set();
  #multi = 0;

  constructor({ host, sessionId }) {
    this.#host = host;
    this.#sessionId = sessionId;
    this.#history = [];
    this.#turn = 0;
    this.#epochs = 0;
    this.#anchors = [];
    this.#sent = null;
    this.#scope = new ScopeHooks();
    this.#scope.coldStart({ sessionId });
    this.#store = new MemoryJournal();
    this.#hint = new PairHint({ store: this.#store });
  }

  get host() {
    return this.#host;
  }

  get history() {
    return this.#history;
  }

  set history(next) {
    this.#history = next;
  }

  get hint() {
    return this.#hint;
  }

  // The copy the provider last received on a MAIN request. The hint itself no
  // longer keeps one; a host/test that needs the bytes keeps its own view.
  get lastSent() {
    return this.#lastSent;
  }

  // What each request needs to be comparable: the copy the provider receives,
  // the copy the hint compared, and whether a re-base happened.
  async begin() {
    await this.bind();
    return this;
  }

  async bind() {
    if (!this.#hint.bound) await this.#hint.bind({ sessionId: this.#sessionId, ladder: { explicit: "English" } });
  }

  // A restart: a new Pair Hint object over the SAME journal would be the honest
  // form, but the journal is in memory here, so the equivalent is to assert the
  // facts survive on the object itself — which is what a cold start restores.
  restart() {
    return this.#hint.epoch;
  }

  // One tool call settles: it changes the Scope book, and therefore what the
  // next projection keeps.
  async settle(kind, callId) {
    this.#turn += 1;
    return await this.#scope.settle({
      sessionId: this.#sessionId,
      member: "js-worker",
      annotation: kind,
      turnId: this.#turn,
      callId,
    });
  }

  // One request. Returns `{ copy, chain, anchored, reason }`, or throws the
  // refusal the adapter would throw.
  //
  // `kind` is what the host says this request is FOR (core's `REQUEST_KINDS`).
  // A side turn reaches the same hook with a copy of the conversation the host
  // will discard, and it must not become the chain — that is the property the
  // ephemeral cases below exist to pin.
  async request({ kind = REQUEST_KINDS.MAIN, copy: given = null } = {}) {
    const host = this.#host;
    const copy = given ?? this.#history;
    // 1. Scope: the projection's answer, applied the way the adapter applies it.
    const projected = this.#scope.project({ sessionId: this.#sessionId, items: host.items(copy) });
    assert.equal(projected.ok, true, `scope refused: ${projected.code} ${projected.message}`);
    const kept = host.kept(copy, projected.messages);
    // 2. The boundary: does this projection replace what the model read?
    // The chain is compared in the shape the adapter will hand the hint. For
    // Cline that means the CUE rewrite runs first (§6.3), exactly as
    // `projectMessages` orders it; `kept` above is already in that shape.
    const chain = host.chain(kept);
    // The projection's OWN answer, before this host's half-pair filter: a
    // re-base may only be claimed for a replacement the projection made.
    const answered = projected.messages.map((item) => item?.source ?? item?.message).filter((message) => message !== undefined);
    // The projection's input is what the LAST request sent, not the host's raw
    // history: Scope runs on the copy the host rebuilt, and that copy already
    // has the previous prune applied when the host stored what we sent. Feeding
    // the raw history would report the same prune as new on every request.
    const itemsIn = host.items(copy);
    // The host's report, read from the copy exactly as the adapter reads it: a
    // compaction summary at the head of the copy means the host rewrote history
    // and saved it. This is the same predicate `host-cline/request.mjs`
    // `isCompactedCopy` applies, so the state machine cannot pass a case the
    // real adapter would fail.
    //
    // An EPHEMERAL request is a branch: no adapter runs the boundary for one
    // (OMP passes `null`, Cline skips the block), and re-basing on a copy the
    // host discards would make the branch the chain — the failure `01a19547`
    // recorded. The host's compaction report is likewise consumed by the next
    // MAIN request, not by a branch.
    //
    // The rewrite must be NEW. A summary's PRESENCE is not the test: every later
    // request is built from the compacted projection, so a summary is present
    // from the first compaction onward. Reporting it every time would re-base on
    // every request and bless a rewrite inside the compacted history (the defect
    // the generator found).
    //
    // The test is the summary's IDENTITY: the copy carries a summary, and the
    // presented copy did not carry THAT summary. A second compaction merges into
    // a new summary text, so it is new; a request built from the same compacted
    // history is not.
    const ephemeral = kind === REQUEST_KINDS.EPHEMERAL;
    const freshlyCompacted = host.compacted(copy) && summaryOf(copy) !== summaryOf(this.#lastSent ?? []);
    const durableRewrite = ephemeral
      ? null
      : freshlyCompacted
        ? REBASE_REASONS.HISTORY_COMPACTED
        : null;
    const boundary = ephemeral ? null : projectionBoundary({
      durableRewrite,
      presented: this.#hint.chain,
      projected: chain,
      incoming: copy,
      // The projection's INPUT is the item list it was handed for THIS copy.
      // Comparing it with a previous request's list would read an unchanged
      // projection as a fresh replacement on every turn.
      givenItems: itemsIn,
      answeredItems: projected.messages ?? [],
      projectedBy: answered,
      filtered: kept,
      pairFree: (list) => this.#hint.injector.withoutInjection(list),
      erased: this.#scope.erasedCount(this.#sessionId),
    });
    if (shouldAnchor(boundary)) {
      this.#hint.anchor(chain, { reason: boundary.reason });
      this.#anchors.push(boundary.reason);
    }
    // 3. The Pair Hint places or replays its pair. An ephemeral request gets the
    // pair at the chain's own placement and records nothing.
    const sent = await this.#hint.project({ messages: chain, kind });
    if (kind !== REQUEST_KINDS.EPHEMERAL) this.#lastSent = sent.messages;
    this.#sent = projected.messages ?? [];
    return { copy, chain, sent: sent.messages, anchored: shouldAnchor(boundary), reason: boundary?.reason ?? null };
  }

  // The copy OMP hands the `context` hook for an ephemeral turn, built the way
  // `#buildEphemeralSnapshot` builds it (agent-session.ts:12929):
  //
  //   1. the conversation as it stands;
  //   2. while a turn is streaming, the in-flight assistant message is REPLACED
  //      by a normalised partial copy — thinking + joined text only, every
  //      tool-call block dropped (so the main turn's own calls vanish);
  //   3. a `developer` reminder ("Ephemeral side-channel turn; ...");
  //   4. the side prompt as a `user` message (the `<title-request>`).
  //
  // Nothing here is appended to the history: the host discards it.
  //
  // The copy is built from what the SESSION last SENT, not from `#history`: the
  // host's transcript is its own, but the conversation the model is inside of is
  // the request that went out (Scope's projection included). Building from
  // `#history` would make the side turn carry a message the projection already
  // rewrote — a shape no host produces, because the host is the one that sent
  // the projected copy.
  sideTurnCopy({ prompt = "<title-request>", streamedText = null } = {}) {
    const sent = this.#lastSent;
    const base = sent === null ? this.#history : this.#hint.injector.withoutInjection(sent);
    const copy = base.map((message) => message);
    if (streamedText !== null) {
      const partial = { role: "assistant", content: [{ type: "text", text: streamedText }] };
      const last = copy[copy.length - 1];
      if (last?.role === "assistant") copy[copy.length - 1] = partial;
      else copy.push(partial);
    }
    copy.push({
      role: "developer",
      content: [{ type: "text", text: "Ephemeral side-channel turn; reuses current conversation context." }],
    });
    copy.push({ role: "user", content: [{ type: "text", text: prompt }] });
    return copy;
  }

  // Issue a side turn and prove it left the chain EXACTLY as it found it.
  //
  // This is the invariant `01a19547` broke: the side turn was accepted, `project`
  // wrote it to `#presented`, and the main turn four milliseconds later was
  // compared against a conversation the model never read. "Not refused" alone
  // would not have caught it — the side turn was never refused; it was the main
  // turn that broke afterwards. So the chain facts are compared before and after
  // the side request, and any movement is a failure of the run that produced it.
  async sideRequest(copy) {
    const before = {
      presented: JSON.stringify(this.#lastSent),
      chain: JSON.stringify(this.#hint.chain?.digests ?? null),
      after: this.#hint.fact?.after ?? null,
      epoch: this.#hint.epoch,
      anchors: this.#anchors.length,
      handles: JSON.stringify(this.#hint.handles),
    };
    const result = await this.request({ kind: REQUEST_KINDS.EPHEMERAL, copy });
    // The pair still has to go in — the side turn exists to warm the provider's
    // cached prefix — so the copy it sends must carry one.
    assert.ok(
      JSON.stringify(result.sent).includes("pair-hint:"),
      "the side turn was sent WITHOUT the pair; its call would miss the cache",
    );
    const after = {
      presented: JSON.stringify(this.#lastSent),
      chain: JSON.stringify(this.#hint.chain?.digests ?? null),
      after: this.#hint.fact?.after ?? null,
      epoch: this.#hint.epoch,
      anchors: this.#anchors.length,
      handles: JSON.stringify(this.#hint.handles),
    };
    assert.deepEqual(after, before, "the side turn moved the chain it is not a step of");
    return "side";
  }

  // Cline's compaction: the conversation becomes a summary plus its unchanged
  // tail, and the host SAVES that state, so the next turn is built from it.
  // Returns whether anything was compacted — a one-message conversation has no
  // earlier work to summarise, and generating that shape would test the
  // compactor rather than the chain.
  compact(summary) {
    if (this.#history.length < 2) return false;
    // A host MERGES a new summary into the previous one; it does not stack them.
    // OMP folds the earlier summary into the text it writes
    // (`previousSummaryForCompaction`, `compaction/compaction.ts:2136`) and
    // emits ONE `compactionSummary` message; Cline's projection likewise returns
    // `[...compactedMessages, ...tail]`, replacing the prefix. A model that
    // prepended a second summary would produce `[summary2, summary1, tail]` — a
    // shape no host sends, and one the chain rightly refuses because the copy
    // changed in the middle.
    //
    // So the summary replaces whatever prefix is already there, and the tail is
    // the part after it.
    //
    // The CUT is at a turn boundary, and that is not a detail: a `tool` message
    // must never lose the `tool-call` it answers. All three hosts guarantee it,
    // each in its own place:
    //
    //   OMP        `findAnthropicCompactionCut`
    //              (agent/src/compaction/anthropic.ts) walks the boundary
    //              forward past every call whose result lies beyond it;
    //   Cline      `isSafeCutBoundary`
    //              (core/src/extensions/context/compaction-shared.ts:339) never
    //              cuts at a `tool_result`-only message, because its `tool_use`
    //              sits in the preceding assistant message and would be
    //              summarised away;
    //   OpenCode   `recentStart` (core/src/session/compaction.ts) starts the
    //              kept tail at a user message "so an assistant's tool calls and
    //              results stay together".
    //
    // The old model kept only the LAST message, so a conversation ending in
    // `[…, call(c6), result(c6)]` compacted to `[summary, result(c6)]` — an
    // orphan the provider rejects and no host produces. That generated shape is
    // what fast-check seed 1221354670 found.
    //
    // The rule here is Cline's, stated in its own terms: the cut never lands on
    // a message that only carries results, because that message's `tool_use`
    // sits in the assistant message before it. OMP states the same rule from the
    // other side — `findValidCutPoints` never offers a `toolResult` as a cut
    // point — and OpenCode keeps whole exchanges. So the tail starts at the
    // LATEST message that is not a bare result, which is the narrowest tail the
    // three hosts agree on and the one that keeps the case non-vacuous: a
    // compaction that leaves nothing is not a compaction.
    // A SECOND compaction cannot produce the same summary text as the first.
    // The host hands the previous summary to the summarizer
    // (`previousSummaryForCompaction`, `compaction.ts:1800`, passed into
    // `generateSummary` at :2317) and emits ONE merged summary, so the new text
    // contains the old. Two identical summaries are therefore not a shape a host
    // produces — and treating them as one is what makes the chain miss the
    // rewrite (fast-check seed 2082693568). A caller asking for text the history
    // already carries gets it merged, the way the host would merge it.
    const previousSummary = this.#history.find((message) => message?.metadata?.kind === COMPACTED_MARK);
    const mergedSummary =
      previousSummary === undefined
        ? summary
        : `${previousSummary.content.map((part) => (typeof part?.text === "string" ? part.text : "")).join("")}\n\n---\n\n${summary}`;
    const firstUnsummarised = this.#history.findIndex(
      (message) => message?.metadata?.kind !== COMPACTED_MARK,
    );
    const kept = firstUnsummarised < 0 ? this.#history.length : firstUnsummarised;
    // A message that carries ONLY results is not a cut point (`isSafeCutBoundary`
    // in `core/src/extensions/context/compaction-shared.ts`). Anything else —
    // an operator turn, an assistant message, a summary — is.
    const resultsOnly = (message) => {
      const parts = Array.isArray(message?.content) ? message.content : [];
      return parts.length > 0 && parts.every((part) => part?.type === "tool-result");
    };
    let cut = kept;
    for (let at = this.#history.length - 1; at > kept; at -= 1) {
      if (!resultsOnly(this.#history[at])) {
        cut = at;
        break;
      }
    }
    const tail = this.#history.slice(cut);
    this.#history = [
      // A plain string-keyed mark, so it survives whatever cloning a host does
      // and it is the ONLY thing that tells a spec-owned compaction from a rogue
      // rewrite of the same shape. It is host-neutral on purpose: the model
      // exercises the FACT ("history was durably replaced by a summary"), not
      // one host's wire tag. Cline's own tag is exercised separately, by the
      // adapter predicate, in the named regression below.
      { role: "user", content: [{ type: "text", text: mergedSummary }], metadata: { kind: COMPACTED_MARK } },
      ...tail,
    ];
    // Nothing is recorded here on purpose. The rewrite is read from the COPY
    // (the mark written above), which is what the real adapter does — a second,
    // test-only channel would let the state machine pass a case the adapter
    // fails, and it would bypass the "only a NEW compaction re-bases" rule.
    return true;
  }

  // A cold restart: the hint is rebuilt from its journal, exactly as a new
  // process would. The facts it restores are the ones it wrote; anything the
  // journal did not record is gone, which is the point.
  async coldRestart() {
    const records = this.#store.records;
    this.#store = new MemoryJournal(records);
    this.#hint = new PairHint({ store: this.#store });
    await this.#hint.load();
  }

  // Whether this message still reaches the copy after being rewritten to plain
  // text. A result whose call it answered becomes an orphan and is dropped —
  // legitimately, by the half-pair rule — so mutating it is not a test of the
  // prefix chain.
  survivesMutation(index) {
    const host = this.#host;
    const mutated = this.#history.map((message, at) =>
      at === index ? { ...message, content: [{ type: "text", text: "REWRITTEN BY NOBODY" }] } : message,
    );
    const projected = this.#scope.project({ sessionId: this.#sessionId, items: host.items(mutated) });
    if (projected.ok !== true) return false;
    return host.kept(mutated, projected.messages).includes(mutated[index]);
  }

  // Whether the current projection sends this message byte for byte. A message
  // the projection rewrites (a `removeLast` lesson replaces the beat before the
  // eraser) is not something the chain can be asked to catch: the spec decided
  // that rewrite.
  unchangedByProjection(index) {
    const host = this.#host;
    const items = host.items(this.#history);
    const projected = this.#scope.project({ sessionId: this.#sessionId, items });
    if (projected.ok !== true) return false;
    const kept = host.kept(this.#history, projected.messages);
    return kept.includes(this.#history[index]) && kept.length === this.#history.length;
  }

  // Which indices of the history the current projection would still send. A
  // message outside this set was pruned by §6.2 and is not part of what the
  // model reads, so mutating it cannot be a prefix violation.
  survivingIndices() {
    const host = this.#host;
    const projected = this.#scope.project({ sessionId: this.#sessionId, items: host.items(this.#history) });
    if (projected.ok !== true) return [];
    const kept = new Set(host.kept(this.#history, projected.messages));
    return this.#history.map((message, index) => (kept.has(message) ? index : -1)).filter((index) => index >= 0);
  }

  // The host grows its history by one message, the way each host stores it.
  grow(message) {
    this.#history = [...this.#history, message];
  }

  // The call ids the host has issued in this session. A multi-tool turn needs
  // them to answer its own calls: the results must name calls that exist, or
  // the projection refuses a result the model never asked for — a shape no host
  // can send.
  noteIssued(ids) {
    for (const id of ids) this.#issued.add(id);
  }

  // The call ids for one multi-tool turn. A tool-call id names ONE call; a
  // later turn reusing `m0` would make an old result look paired with a new
  // call, a conversation no host sends. The base advances once per turn.
  multiCallIds(count) {
    const base = this.#multi;
    this.#multi += 1;
    return Array.from({ length: count }, (_, n) => `m${base}-${n}`);
  }

  issued() {
    return [...this.#issued];
  }

  noteAnswered(id) {
    this.#answered.add(id);
  }

  answered() {
    return this.#answered;
  }

  // A new epoch, the one change that legitimately rewrites the pair itself.
  async newEpoch() {
    const next = await this.#hint.newEpoch();
    this.#epochs += 1;
    return next;
  }

  get anchors() {
    return [...this.#anchors];
  }

  get epochs() {
    return this.#epochs;
  }

  // The journal the hint writes, so a restart can be built from the same facts.
  get journal() {
    return this.#store;
  }
}

// ---------------------------------------------------------------------------
// The turns a host can take
// ---------------------------------------------------------------------------

// One turn, as data. `apply` mutates the session; `drift` says whether the
// turn is expected to change the conversation the model already read (which is
// the ONLY case a request may be refused for a prefix reason).
const TURNS = Object.freeze({
  // The operator asks something new. The history grows by one user message;
  // every earlier message is untouched.
  operator: (text) => ({
    name: "operator",
    apply: (session) => {
      session.grow({ role: "user", content: [{ type: "text", text }] });
      return "kept";
    },
  }),
  // The assistant answers in prose. Grows the history; nothing is rewritten.
  prose: (text) => ({
    name: "prose",
    apply: (session) => {
      session.grow({ role: "assistant", content: [{ type: "text", text }] });
      return "kept";
    },
  }),
  // The assistant issues a call whose result has not arrived yet. This is the
  // in-flight shape: the message is in the request, its answer is not.
  call: (id) => ({
    name: "call",
    apply: (session) => {
      session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: id, input: { id } }] });
      return "kept";
    },
  }),
  // The call's result arrives, in its own message.
  result: (id, value) => ({
    name: "result",
    apply: (session) => {
      session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: id, output: { type: "text", value } }] });
      return "kept";
    },
  }),
  // A call the host never executed: it carries no result at all.
  failed: (id) => ({
    name: "failed",
    apply: (session) => {
      session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: id, input: { fail: true } }] });
      session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: id }] });
      return "kept";
    },
  }),
  // The host rewrites a streamed message as its stream settles: same words, same
  // call, bookkeeping filled in. §6.1 does not count that as a change.
  finalise: (id, text) => ({
    name: "finalise",
    apply: (session) => {
      session.grow({
        role: "assistant",
        content: [{ type: "text", text }, { type: "tool-call", toolName: "js-worker", toolCallId: id, input: {} }],
        usage: { input: 7, output: 3, totalTokens: 10 },
        responseId: `r-${id}`,
        duration: 12,
        timestamp: 1,
      });
      return "kept";
    },
  }),
  // The host stamps a message it ALREADY SENT with bookkeeping that did not
  // exist before — `usage`, `duration`, `ttft`, `responseId`, `partialArgs`,
  // `streamIndex`, and any field a future version invents.
  //
  // §6.1 is about the conversation, not about the host's bookkeeping, so this
  // must be invisible to the chain. The generator supplies the KEYS as well as
  // the values, so an adapter that grew a new blacklist instead of deferring to
  // core's canonical comparison is caught the moment the host invents a field.
  restamp: (index, keys, value) => ({
    name: "restamp",
    apply: (session) => {
      if (session.history[index] === undefined) return "skip";
      session.history = session.history.map((message, at) => {
        if (at !== index) return message;
        const stamped = { ...message };
        for (const key of keys) stamped[key] = value;
        return stamped;
      });
      return "kept";
    },
  }),
  // The host rebuilds a message's object with its keys in a different order.
  // `JSON.stringify` sees a change; the conversation has none, so the chain's
  // canonical comparison must not. This is the shape that made a rebuilt
  // OpenCode message read as a rewrite.
  reorder: (index) => ({
    name: "reorder",
    apply: (session) => {
      const message = session.history[index];
      if (message === undefined) return "skip";
      const reversed = Object.fromEntries(Object.entries(message).reverse());
      session.history = session.history.map((at, position) => (position === index ? reversed : at));
      return "kept";
    },
  }),
  // SEVERAL calls issued in one assistant message, then their results arriving
  // as ADJACENT `tool` messages — the multi-tool turn from `01a19547` (four
  // calls, four results). A host that merges or reorders those results changes
  // the copy without changing the conversation.
  multiCall: (count) => ({
    name: "multiCall",
    apply: (session) => {
      const ids = session.multiCallIds(count);
      session.grow({
        role: "assistant",
        content: [
          { type: "text", text: "dispatching" },
          ...ids.map((id) => ({ type: "tool-call", toolName: "js-worker", toolCallId: id, input: { id } })),
        ],
      });
      session.noteIssued(ids);
      return "kept";
    },
  }),
  multiResult: (count) => ({
    name: "multiResult",
    apply: (session) => {
      // Answer the multi-tool calls that are still unanswered: a call the
      // projection has already pruned cannot be answered, and a call answered
      // twice is not a shape a host sends.
      const ids = session.issued().filter((id) => id.startsWith("m") && !session.answered().has(id)).slice(0, count);
      if (ids.length === 0) return "skip";
      for (const id of ids) {
        session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: id, output: { type: "text", value: `ok-${id}` } }] });
        session.noteAnswered(id);
      }
      return "kept";
    },
  }),
  // A `resetScope` boundary settles: the projection may, from now on, prune
  // messages that sit before it.
  reset: (id) => ({
    name: "reset",
    apply: async (session) => {
      session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: id, input: {} }] });
      session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: id, output: { type: "text", value: "budget" } }] });
      // The annotation rides on the call, the way the model sends it.
      session.history[session.history.length - 2] = {
        role: "assistant",
        content: [{ type: "tool-call", toolName: "js-worker", toolCallId: id, input: { scope: { kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 6 } } }],
        scope: { kind: "resetScope" },
      };
      const settled = await session.settle({ kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 6 }, id);
      assert.equal(settled.ok, true, `reset refused: ${settled.code}`);
      return "projection";
    },
  }),
  // A `removeLast` erases the beat before it, replacing it with a lesson.
  erase: (id) => ({
    name: "erase",
    apply: async (session) => {
      session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: id, input: {} }] });
      const settled = await session.settle({ kind: "removeLast", replacement: "lesson learned" }, id);
      assert.equal(settled.ok, true, `removeLast refused: ${settled.code}`);
      return "projection";
    },
  }),
  // The presentation must change, so the epoch turns over. The pair itself is
  // rewritten by design; the conversation is not.
  epoch: () => ({
    name: "epoch",
    apply: async (session) => {
      await session.newEpoch();
      return "epoch";
    },
  }),
  // A SIDE TURN: the host runs an ephemeral request (a title fork, a composer
  // prediction, a /btw turn) through the same `context` hook while the main
  // conversation is where it is. The copy it sends is the conversation plus the
  // host's own side-channel messages, and the host appends none of it. The
  // request is a BRANCH: it may not become the chain.
  //
  // This is the shape that wedged `01a19547` (2026-11-01): the side copy
  // extended the pair-free prefix, the chain accepted it and made it the
  // anchor, and the main turn's very next request was refused at index 1.
  sideTurn: (prompt) => ({
    name: "sideTurn",
    apply: async (session) => {
      const copy = session.sideTurnCopy({ prompt });
      return await session.sideRequest(copy);
    },
  }),
  // The same, but the main turn is STREAMING: the in-flight assistant message is
  // truncated to its text (every tool call dropped) before the side messages are
  // appended. This is `#buildEphemeralSnapshot` step 2, and it is what made the
  // side copy differ from the conversation at index 1 rather than merely extend
  // it.
  streamedSideTurn: (prompt, streamedText) => ({
    name: "streamedSideTurn",
    apply: async (session) => {
      const copy = session.sideTurnCopy({ prompt, streamedText });
      return await session.sideRequest(copy);
    },
  }),
  // A message the model already read is rewritten with NO projection behind it.
  // This is the case the chain exists to catch.
  mutate: (index) => ({
    name: "mutate",
    apply: (session) => {
      const target = session.history[index];
      if (target === undefined) return "skip";
      session.history = session.history.map((message, at) =>
        at === index ? { ...message, content: [{ type: "text", text: "REWRITTEN BY NOBODY" }] } : message,
      );
      return "drift";
    },
  }),
  // A COMPACTED history: Cline's context pipeline replaces the conversation with
  // a summary plus its unchanged tail, and SAVES that state
  // (`projectSessionCompactionState` in `session/models/session-compaction.ts`
  // returns `[...compactedMessages, ...tail]`, and `prepareTurnForModelRequest`
  // persists it via `saveState`). The next turn is built from the compacted
  // projection, not from the full transcript.
  //
  // So this is a DURABLE rewrite of history by a part of the spec that owns it —
  // the `scope-reset` shape, not a branch. The chain must re-anchor on it, and
  // the turn after it must be accepted.
  compacted: (summary) => ({
    name: "compacted",
    apply: async (session) => {
      return session.compact(summary) ? "projection" : "skip";
    },
  }),
  // A cold restart: the process ends and comes back over the same journal. The
  // chain it continues is the one it presented before the restart.
  restart: () => ({
    name: "restart",
    apply: async (session) => {
      await session.coldRestart();
      return "skip";
    },
  }),
  // TWO exchanges inside ONE turn, which is what OMP's agent loop actually
  // produces: `agent-loop.ts` pushes each `toolResult` into the turn's context
  // while the run continues, so one turn can be
  //
  //   assistant(call cX) -> tool(cX) -> assistant(text, call cY) -> tool(cY)
  //
  // `findValidCutPoints` (`agent/src/compaction/compaction.ts:440-475`) counts
  // only `user`, `bashExecution`, `branchSummary` and `customMessage` as turn
  // starts, so a compaction cut may land on that SECOND assistant: the summary
  // then takes `call(cY)` and its result, while `call(cX)` stays in the tail
  // with its result gone. That is the half-pair the generator could not build
  // from single-message turns, and it is legal.
  interleaved: (x, y) => ({
    name: "interleaved",
    apply: (session) => {
      session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: x, input: { id: x } }] });
      session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: x, output: { type: "text", value: `ok-${x}` } }] });
      session.grow({
        role: "assistant",
        content: [
          { type: "text", text: "next" },
          { type: "tool-call", toolName: "js-worker", toolCallId: y, input: { id: y } },
        ],
      });
      session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: y, output: { type: "text", value: `ok-${y}` } }] });
      session.noteIssued([x, y]);
      session.noteAnswered(x);
      session.noteAnswered(y);
      return "kept";
    },
  }),
});


// Whether a turn is legal for the session as it now stands, and the bookkeeping
// that makes the next answer decidable. One rule for every driver, so the two
// properties cannot disagree about which sequences exist.
const legalTurn = (spec, state) => {
  // A `result` for a call nobody issued is not a conversation a host can send.
  if (spec.kind === "result" && !state.issued.has(spec.id)) return false;
  // ...and neither is a result whose call the Scope projection has already
  // pruned. The host stores it (its transcript still holds it) but the adapter's
  // half-pair rule drops it, and that drop is the adapter's own decision — the
  // boundary says `filtered` and the chain refuses, correctly. Generating that
  // shape would test the half-pair rule, not the prefix rule.
  if (spec.kind === "result" && state.pruned.has(spec.id)) return false;
  // The SAME rule for the multi-tool turn, which used to bypass both checks
  // above (fast-check seed -77436715). A `resetScope` prunes the calls that had
  // no result yet, and their results can no longer be sent: OMP fabricates a
  // result for every call it KEEPS and strips a call whose result is off the
  // path (`session-context.ts:695-712`), so a copy never carries a result whose
  // call it does not carry. Sending one is the half-pair shape, and the adapter
  // drops it by design.
  if (spec.kind === "multiResult" && spec.count > 0) {
    const answerable = [...state.issued].filter((id) => id.startsWith("m") && !state.pruned.has(id));
    if (answerable.length === 0) return false;
  }
  // §6.2: no budget exists until a `resetScope` establishes one, so a keep or a
  // remove before the first reset is refused by the spec itself. Generating that
  // shape would test the admission rule, not the prefix rule.
  if (spec.kind === "erase" && !state.budget) return false;
  // A tool-call id names ONE call. The provider mints it for one turn, and a
  // second call reusing it is not a conversation a host can send — the second
  // `removeLast` would be booked against a call the first already settled, and
  // the beat it erased is gone. (fast-check seed 162189664 generated `erase e3`
  // twice.) The same rule already governs `result`, which cannot answer a call
  // twice.
  if ((spec.kind === "erase" || spec.kind === "reset") && state.used.has(spec.id)) return false;
  return true;
};

const noteTurn = (spec, state) => {
  if (spec.kind === "erase" || spec.kind === "reset") state.used.add(spec.id);
  if (spec.kind === "call" || spec.kind === "failed" || spec.kind === "finalise") state.issued.add(spec.id);
  // A multi-tool turn issues several calls at once; its results may answer any
  // of them. The base advances once per turn, exactly as `Session` does, so the
  // ids the driver records are the ids the host really issued.
  if (spec.kind === "multiCall") {
    const base = state.multi;
    state.multi += 1;
    for (let n = 0; n < spec.count; n += 1) state.issued.add(`m${base}-${n}`);
  }
  // A result answers its call: answering it twice is not a shape a host sends,
  // and neither is answering a call the reset pruned.
  if (spec.kind === "multiResult") {
    let answered = 0;
    for (const id of state.issued) {
      if (answered >= spec.count) break;
      if (!id.startsWith("m") || state.answered.has(id) || state.pruned.has(id)) continue;
      state.answered.add(id);
      answered += 1;
    }
  }
  // A reset prunes every call that has no result yet: they sit before the new
  // boundary, so their answers can no longer be sent.
  if (spec.kind === "reset") {
    state.budget = true;
    state.pruned = new Set(state.issued);
  }
};

// One generated turn, resolved against the session it is applied to.
const turnArb = fc.oneof(
  fc.record({ kind: fc.constant("operator"), text: fc.stringMatching(/^[a-z ]{1,10}$/) }),
  fc.record({ kind: fc.constant("prose"), text: fc.stringMatching(/^[a-z ]{1,10}$/) }),
  fc.record({ kind: fc.constant("call"), id: fc.stringMatching(/^c[0-9]$/) }),
  fc.record({ kind: fc.constant("result"), id: fc.stringMatching(/^c[0-9]$/), value: fc.stringMatching(/^[a-z ]{0,8}$/) }),
  fc.record({ kind: fc.constant("failed"), id: fc.stringMatching(/^c[0-9]$/) }),
  fc.record({ kind: fc.constant("finalise"), id: fc.stringMatching(/^c[0-9]$/), text: fc.stringMatching(/^[a-z ]{1,8}$/) }),
  fc.record({ kind: fc.constant("reset"), id: fc.stringMatching(/^r[0-9]$/) }),
  fc.record({ kind: fc.constant("erase"), id: fc.stringMatching(/^e[0-9]$/) }),
  fc.record({ kind: fc.constant("epoch") }),
  fc.record({ kind: fc.constant("mutate"), index: fc.nat({ max: 6 }) }),
  // The real host lifecycles that the earlier generator never produced: an
  // ephemeral request arriving at the conversation, and the same arriving while
  // the main turn is streaming (a truncated in-flight assistant message).
  fc.record({ kind: fc.constant("sideTurn"), prompt: fc.stringMatching(/^[a-z ]{1,8}$/) }),
  fc.record({ kind: fc.constant("streamedSideTurn"), prompt: fc.stringMatching(/^[a-z ]{1,8}$/), text: fc.stringMatching(/^[a-z ]{1,8}$/) }),
  // The other two real host lifecycles: a compacted per-call request, and the
  // process dying and coming back over the same journal.
  fc.record({ kind: fc.constant("compacted"), summary: fc.stringMatching(/^[a-z ]{1,10}$/) }),
  fc.record({ kind: fc.constant("restart") }),
  // Two exchanges in one turn: the shape OMP's loop actually produces, and the
  // one whose compaction cut can land on the second assistant message.
  fc.record({
    kind: fc.constant("interleaved"),
    x: fc.stringMatching(/^x[0-9]$/),
    y: fc.stringMatching(/^y[0-9]$/),
  }),
  // Host bookkeeping churn. The KEY names are generated, not chosen: an adapter
  // that keeps its own list of fields to ignore fails the moment the generator
  // produces one the list does not have, which is exactly how the blacklist
  // broke when the host invented a field.
  fc.record({
    kind: fc.constant("restamp"),
    index: fc.nat({ max: 8 }),
    keys: fc.uniqueArray(
      fc.constantFrom("usage", "duration", "ttft", "responseId", "partialArgs", "streamIndex", "completedAt", "contextSnapshot", "futureField"),
      { minLength: 1, maxLength: 3 },
    ),
    value: fc.oneof(fc.integer(), fc.string({ maxLength: 6 }), fc.record({ n: fc.integer() })),
  }),
  fc.record({ kind: fc.constant("reorder"), index: fc.nat({ max: 8 }) }),
  // The multi-tool turn: several calls in one assistant message, then adjacent
  // results.
  fc.record({ kind: fc.constant("multiCall"), count: fc.integer({ min: 2, max: 4 }) }),
  fc.record({ kind: fc.constant("multiResult"), count: fc.integer({ min: 1, max: 4 }) }),
);

const turnOf = (spec) => {
  switch (spec.kind) {
    case "operator":
      return TURNS.operator(spec.text);
    case "prose":
      return TURNS.prose(spec.text);
    case "call":
      return TURNS.call(spec.id);
    case "result":
      return TURNS.result(spec.id, spec.value);
    case "failed":
      return TURNS.failed(spec.id);
    case "finalise":
      return TURNS.finalise(spec.id, spec.text);
    case "reset":
      return TURNS.reset(spec.id);
    case "erase":
      return TURNS.erase(spec.id);
    case "epoch":
      return TURNS.epoch();
    case "sideTurn":
      return TURNS.sideTurn(spec.prompt);
    case "streamedSideTurn":
      return TURNS.streamedSideTurn(spec.prompt, spec.text);
    case "restamp":
      return TURNS.restamp(spec.index, spec.keys, spec.value);
    case "reorder":
      return TURNS.reorder(spec.index);
    case "multiCall":
      return TURNS.multiCall(spec.count);
    case "multiResult":
      return TURNS.multiResult(spec.count);
    case "compacted":
      return TURNS.compacted(spec.summary);
    case "restart":
      return TURNS.restart();
    case "interleaved":
      return TURNS.interleaved(spec.x, spec.y);
    case "mutate":
      return TURNS.mutate(spec.index);
    default:
      throw new Error(`unknown turn ${spec.kind}`);
  }
};

// ---------------------------------------------------------------------------
// The machine
// ---------------------------------------------------------------------------

// Drive one session through one sequence. Answers:
//
//   refusals    the prefix refusals, with the turn that produced them
//   expected    whether a mutation happened that SHOULD have been refused
//   anchored    every re-base reason, in order
//
// A sequence is legal when it never feeds a `result` for a call that was never
// issued: that shape is not a conversation a host can produce, and the Scope
// projection refuses it on purpose (a result the model never asked for).
const drive = async ({ host, sessionId, turns }) => {
  const session = await new Session({ host, sessionId }).begin();
  const refusals = [];
  const anchors = [];
  let mutated = false;
  let expectedAfterMutation = false;
  const issued = new Set();

  const state = {
    issued: new Set(),
    answered: new Set(),
    pruned: new Set(),
    used: new Set(),
    budget: false,
    multi: 0,
  };
  for (const spec of turns) {
    if (!legalTurn(spec, state)) continue;
    noteTurn(spec, state);
    const turn = turnOf(spec);
    const effect = await turn.apply(session);
    if (effect === "skip") continue;
    // A turn that ISSUED ITS OWN request (a side turn) has already been
    // exercised. Sending the history again afterwards would ask a different
    // question — a normal request on a conversation nothing changed — and would
    // hide whether the side turn itself left the chain intact.
    if (effect === "side") {
      refusals.push({ turn: turn.name, refused: false, mutationPending: expectedAfterMutation });
      continue;
    }
    if (effect === "drift") {
      mutated = true;
      expectedAfterMutation = true;
    }
    if (effect === "epoch") continue;
    try {
      await session.request();
      refusals.push({ turn: turn.name, refused: false, mutationPending: expectedAfterMutation });
      if (expectedAfterMutation) expectedAfterMutation = false;
    } catch (error) {
      const isPrefix = error?.code === "PREFIX_DRIFT";
      refusals.push({ turn: turn.name, refused: isPrefix, code: error?.code, mutationPending: expectedAfterMutation, message: error?.message });
      if (isPrefix && expectedAfterMutation) expectedAfterMutation = false;
      else if (!isPrefix) throw error;
    }
  }
  anchors.push(...session.anchors);
  return { refusals, anchors, mutated, session };
};

// The reasons `anchor` may be given, as the one importable list.
const REASONS = new Set(Object.values(REBASE_REASONS));

// ---------------------------------------------------------------------------
// The properties
// ---------------------------------------------------------------------------

test("no legal sequence of turns is ever refused for a prefix reason", async () => {
  // The whole point: keeping, pruning, erasing, streaming, retrying and turning
  // the epoch over are all things the spec allows. None of them may deadlock a
  // session, on any host.
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom(...HOST_NAMES),
      fc.array(turnArb, { minLength: 1, maxLength: 12 }),
      async (hostName, turns) => {
        // A mutation is the one turn that MUST be refused, so a sequence
        // containing one is checked by the dual property instead.
        const legal = turns.filter((turn) => turn.kind !== "mutate");
        const { refusals } = await drive({ host: HOSTS[hostName], sessionId: `sm-${hostName}`, turns: legal });
        const drifted = refusals.filter((entry) => entry.refused);
        assert.deepEqual(
          drifted,
          [],
          `${hostName}: a legal sequence was refused:\n${JSON.stringify(drifted, null, 2)}\nturns: ${JSON.stringify(legal)}`,
        );
      },
    ),
    { ...fcRuns(120) },
  );
});

test("a semantic mutation with no projection behind it is ALWAYS refused", async () => {
  // The dual of the property above. Without it, "no legal sequence drifts"
  // would be satisfied by an adapter that re-anchors on every request and
  // therefore compares nothing.
  //
  // The shape is exact: build a settled conversation, rewrite ONE message the
  // chain will carry, send it, and require PREFIX_DRIFT. Every message of every
  // conversation is tried, so a message the rule fails to protect is named by
  // index rather than found by luck.
  const conversations = [
    [{ role: "user", content: [{ type: "text", text: "go" }] }],
    [
      { role: "user", content: [{ type: "text", text: "go" }] },
      { role: "assistant", content: [{ type: "text", text: "ok" }] },
    ],
    [
      { role: "user", content: [{ type: "text", text: "go" }] },
      { role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c0", input: {} }] },
      { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c0", output: { type: "text", value: "v" } }] },
    ],
    [
      { role: "user", content: [{ type: "text", text: "go" }] },
      { role: "assistant", content: [{ type: "text", text: "a" }, { type: "tool-call", toolName: "js-worker", toolCallId: "c0", input: {} }] },
      { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c0", output: { type: "text", value: "v" } }] },
      { role: "assistant", content: [{ type: "text", text: "done" }] },
      { role: "user", content: [{ type: "text", text: "again" }] },
    ],
  ];

  for (const hostName of HOST_NAMES) {
    for (const [caseIndex, conversation] of conversations.entries()) {
      for (let at = 0; at < conversation.length; at += 1) {
        const session = await new Session({ host: HOSTS[hostName], sessionId: `sm-mut-${hostName}-${caseIndex}-${at}` }).begin();
        session.history = structuredClone(conversation);
        await session.request();
        // Only a message that survives the projection AND still reaches the
        // chain after being rewritten is a fair target: a message the spec
        // prunes is not something the chain can protect.
        if (!session.survivesMutation(at)) continue;
        session.history = session.history.map((message, position) =>
          position === at ? { ...message, content: [{ type: "text", text: "REWRITTEN BY NOBODY" }] } : message,
        );
        await assert.rejects(
          session.request(),
          (error) => error?.code === "PREFIX_DRIFT",
          `${hostName}: conversation ${caseIndex}, message ${at} (${conversation[at].role}) was rewritten and NOT refused`,
        );
      }
    }
  }
});

// The same invariant over GENERATED conversations, not five fixed shapes: any
// message that survives the projection and still reaches the chain must be
// protected, whatever mix of prose, single calls, multi-tool turns and results
// the session produced.
const mutationSpecArb = fc.oneof(
  fc.record({ kind: fc.constant("user"), text: fc.stringMatching(/^[a-z ]{1,10}$/) }),
  fc.record({ kind: fc.constant("assistant"), text: fc.stringMatching(/^[a-z ]{1,10}$/) }),
  fc.record({ kind: fc.constant("call"), id: fc.stringMatching(/^c[0-9]$/) }),
  fc.record({ kind: fc.constant("result"), id: fc.stringMatching(/^c[0-9]$/), value: fc.stringMatching(/^[a-z ]{0,8}$/) }),
  fc.record({ kind: fc.constant("multiCall"), count: fc.integer({ min: 2, max: 3 }) }),
  fc.record({ kind: fc.constant("multiResult"), count: fc.integer({ min: 1, max: 3 }) }),
);

// A legal host history from the specs: a result only answers a call that was
// already issued, and only once.
const historyOfSpecs = (specs) => {
  const history = [];
  const issued = new Set();
  const answered = new Set();
  let multi = 0;
  for (const spec of specs) {
    if (spec.kind === "user") {
      history.push({ role: "user", content: [{ type: "text", text: spec.text }] });
    } else if (spec.kind === "assistant") {
      history.push({ role: "assistant", content: [{ type: "text", text: spec.text }] });
    } else if (spec.kind === "call") {
      history.push({
        role: "assistant",
        content: [{ type: "tool-call", toolName: "js-worker", toolCallId: spec.id, input: { id: spec.id } }],
      });
      issued.add(spec.id);
    } else if (spec.kind === "result") {
      if (!issued.has(spec.id) || answered.has(spec.id)) continue;
      history.push({
        role: "tool",
        content: [
          { type: "tool-result", toolName: "js-worker", toolCallId: spec.id, output: { type: "text", value: spec.value } },
        ],
      });
      answered.add(spec.id);
    } else if (spec.kind === "multiCall") {
      const ids = Array.from({ length: spec.count }, (_, n) => `m${multi}-${n}`);
      multi += 1;
      history.push({
        role: "assistant",
        content: ids.map((id) => ({ type: "tool-call", toolName: "js-worker", toolCallId: id, input: { id } })),
      });
      for (const id of ids) issued.add(id);
    } else if (spec.kind === "multiResult") {
      const ids = [...issued].filter((id) => id.startsWith("m") && !answered.has(id)).slice(0, spec.count);
      if (ids.length === 0) continue;
      for (const id of ids) {
        history.push({
          role: "tool",
          content: [{ type: "tool-result", toolName: "js-worker", toolCallId: id, output: { type: "text", value: "ok" } }],
        });
        answered.add(id);
      }
    }
  }
  return history;
};

test("property: any semantic mutation of a carried message is refused", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom(...HOST_NAMES),
      fc.array(mutationSpecArb, { minLength: 1, maxLength: 6 }),
      fc.nat({ max: 8 }),
      async (hostName, specs, pick) => {
        const history = historyOfSpecs(specs);
        if (history.length === 0) return;
        const session = await new Session({ host: HOSTS[hostName], sessionId: `mut-prop-${hostName}` }).begin();
        session.history = history;
        try {
          await session.request();
        } catch {
          // A shape this host refuses is not a chain to protect.
          return;
        }
        const targets = session
          .survivingIndices()
          .filter((at) => session.unchangedByProjection(at) && session.survivesMutation(at));
        if (targets.length === 0) return;
        const at = targets[pick % targets.length];
        session.history = session.history.map((message, position) =>
          position === at ? { ...message, content: [{ type: "text", text: "REWRITTEN BY NOBODY" }] } : message,
        );
        await assert.rejects(
          session.request(),
          (error) => error?.code === "PREFIX_DRIFT",
          `${hostName}: a generated mutation at ${at} was NOT refused\nturns: ${JSON.stringify(specs)}`,
        );
      },
    ),
    { ...fcRuns(80) },
  );
});

test("a re-base happens only when a projection replaced the copy, and names the fact", async () => {
  // Anchoring is what makes the chain bend. If it fires when nothing was
  // replaced, the chain stops checking anything; if it never fires, the session
  // deadlocks. So every re-base must be attributed to one of the spec's facts.
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom(...HOST_NAMES),
      fc.array(turnArb.filter((turn) => turn.kind !== "mutate"), { minLength: 2, maxLength: 10 }),
      async (hostName, turns) => {
        const { anchors } = await drive({ host: HOSTS[hostName], sessionId: `sm-anchor-${hostName}`, turns });
        for (const reason of anchors) {
          assert.ok(REASONS.has(reason), `${hostName}: a re-base with an unknown reason: ${reason}`);
        }
        // A sequence with no reset and no erase has no legitimate break, so it
        // must not re-base at all.
        //
        // Four facts restart a chain, and only these four:
        //
        //   relay             §2.4 第 3 步: a successor never starts from the
        //                     retired tenure's bytes.
        //   scope-reset       a settled `resetScope` pruned the copy.
        //   scope-remove      a settled `removeLast` replaced a beat in place.
        //   history-compacted the host durably replaced its transcript with a
        //                     summary plus its tail and saved it (Cline's
        //                     `projectSessionCompactionState`), so the next turn
        //                     is built from a history the model has not read in
        //                     that shape.
        //
        // An EPOCH boundary is NOT an anchor. `newEpoch()` freezes new bytes and
        // the next request places the pair again, so it pushes nothing onto
        // `#anchors` — the only push site is the boundary in `request()`.
        //
        // So the gate below asks the narrow question: could an ANCHOR have been
        // legitimate in this sequence? Only the three spec-owned rewrites make
        // one so, and `epoch` is deliberately absent. If `newEpoch` ever starts
        // anchoring, this assertion fires — which is the correct way to find out.
        const canAnchor = turns.some(
          (turn) => turn.kind === "reset" || turn.kind === "erase" || turn.kind === "compacted",
        );
        if (!canAnchor) {
          assert.deepEqual(anchors, [], `${hostName}: re-based a chain with no projection behind it: ${JSON.stringify(anchors)}`);
        }
      },
    ),
    { ...fcRuns(120) },
  );
});

test("the three hosts agree: the same conversation yields the same chain verdict", async () => {
  // The adapters differ in dialect, not in rule. For a conversation built the
  // same way, every host must reach the same verdict — refuse or extend — on
  // the same step. A host that disagrees is a host with its own second rule.
  await fc.assert(
    fc.asyncProperty(
      fc.array(turnArb.filter((turn) => turn.kind !== "mutate"), { minLength: 1, maxLength: 8 }),
      async (turns) => {
        const verdicts = {};
        for (const hostName of HOST_NAMES) {
          const { refusals } = await drive({ host: HOSTS[hostName], sessionId: `sm-agree-${hostName}`, turns });
          verdicts[hostName] = refusals.map((entry, index) => (entry.refused ? `refused@${index}:${entry.code}` : `ok@${index}`));
        }
        const unique = new Set(Object.values(verdicts).map((list) => JSON.stringify(list)));
        assert.equal(
          unique.size,
          1,
          `the hosts disagree on the same conversation:\n${JSON.stringify(verdicts, null, 2)}\nturns: ${JSON.stringify(turns)}`,
        );
      },
    ),
    { ...fcRuns(60) },
  );
});

test("a removeLast request carries the replacement it decided", async () => {
  // §6.2: `removeLast` replaces the previous beat with a lesson the model must
  // read. The projection computed that text and the adapter then dropped it —
  // the replacement never reached the model on any host, so the erase was dead
  // on the live path while every unit test passed. This asserts the visible
  // outcome: the copy contains the replacement and its spin line, and the beat
  // it erased is gone.
  for (const hostName of HOST_NAMES) {
    const session = await new Session({ host: HOSTS[hostName], sessionId: `sm-lesson-${hostName}` }).begin();
    const settled = await session.settle({ kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 6 }, "r0");
    assert.equal(settled.ok, true, `${hostName}: the reset was refused: ${settled.code}`);
    session.history = [
      { role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "r0", input: {} }] },
      { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "r0", output: { type: "text", value: "b" } }] },
      { role: "assistant", content: [{ type: "text", text: "THE BEAT THAT GOES AWAY" }] },
      { role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "e0", input: {} }] },
    ];
    const erased = await session.settle({ kind: "removeLast", replacement: "LESSON REPLACEMENT" }, "e0");
    assert.equal(erased.ok, true, `${hostName}: the removeLast was refused: ${erased.code}`);
    const { sent } = await session.request();
    const text = JSON.stringify(sent);
    assert.match(text, /LESSON REPLACEMENT/, `${hostName}: the replacement never reached the request copy`);
    assert.match(text, /Spin count: 1/, `${hostName}: the replacement carries no spin line`);
    assert.doesNotMatch(text, /THE BEAT THAT GOES AWAY/, `${hostName}: the erased beat survived the replacement`);
  }
});

test("a restarted session continues the chain the projection decided", async () => {
  // 根 §6.1: a restart rebuilds from durable facts and answers the same bytes.
  // Here the durable facts are the Pair Hint's own, so the check is that a
  // restart mid-sequence changes neither the epoch nor the chain's verdicts.
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom(...HOST_NAMES),
      fc.array(turnArb.filter((turn) => turn.kind !== "mutate"), { minLength: 2, maxLength: 8 }),
      async (hostName, turns) => {
        const { session, refusals } = await drive({ host: HOSTS[hostName], sessionId: `sm-restart-${hostName}`, turns });
        const epochBefore = session.hint.epoch;
        const chainBefore = session.hint.chain;
        // A restart is a new process over the same journal: the hint object is
        // replaced, the facts are not.
        const restored = new PairHint({ store: new MemoryJournal(session.journal.records) });
        await restored.load();
        // A cold start must have found the facts, not an empty journal: the
        // epoch is the same one, so a `null` presented copy would mean the
        // records did not survive.
        assert.equal(restored.epoch, epochBefore, `${hostName}: a restart lost the epoch`);
        assert.equal(restored.epoch, epochBefore, `${hostName}: a restart changed the epoch`);
        // A request after the epoch opened is what presents in the new epoch.
        // Without one, the live hint still holds the PREVIOUS epoch's copy —
        // which a cold start correctly does not carry over (§6.1: a new epoch
        // starts a new chain). So the copy is compared only when the live hint's
        // own epoch has presented something.
        if (chainBefore === null || restored.chain === null) {
          assert.equal(restored.chain, null, `${hostName}: a restart invented a chain`);
        } else {
          assert.ok(restored.chain.equals(chainBefore), `${hostName}: a restart changed the chain`);
        }
        assert.equal(refusals.filter((entry) => entry.refused).length, 0, `${hostName}: the sequence was refused`);
      },
    ),
    { ...fcRuns(60) },
  );
});

test("a cold start hands the boundary a Chain, never a presented copy", async () => {
  // The smoke path: a new process folds the journal, then the first request
  // runs the projection boundary. `hint.presented` no longer exists, and no
  // full request copy survives the journal. The boundary must compare the
  // chain's digests for both an extension and a host-reported compaction.
  const journal = new MemoryJournal();
  const live = new PairHint({ store: journal });
  await live.bind({ sessionId: "cold-boundary", ladder: { explicit: "English" } });
  const request = [
    { role: "user", content: "a" },
    { role: "assistant", content: "b" },
  ];
  await live.project({ messages: request });

  const cold = new PairHint({ store: new MemoryJournal(journal.records) });
  await cold.load();
  assert.equal(cold.presented, undefined, "a cold start has no presented copy");
  assert.equal(cold.chain.length, request.length + 2, "the chain carries the request plus the pair");

  const extended = [...request, { role: "user", content: "c" }];
  const extendedBoundary = projectionBoundary({
    presented: cold.chain,
    projected: extended,
    incoming: extended,
    pairFree: (copy) => cold.injector.withoutInjection(copy),
  });
  assert.equal(extendedBoundary.replaced, false, "an extension is not a replacement");
  assert.equal(
    cold.injector.extendsPrefix({ previous: cold.chain, next: extended }),
    true,
    "the chain answers the extension without a stored copy",
  );

  const compacted = [
    { role: "user", content: "summary" },
    extended[extended.length - 1],
  ];
  const compactedBoundary = projectionBoundary({
    durableRewrite: REBASE_REASONS.HISTORY_COMPACTED,
    presented: cold.chain,
    projected: compacted,
    incoming: compacted,
    pairFree: (copy) => cold.injector.withoutInjection(copy),
  });
  assert.equal(compactedBoundary.replaced, true, "a compacted copy is a new chain");
  assert.equal(compactedBoundary.reason, REBASE_REASONS.HISTORY_COMPACTED);
});

// ---------------------------------------------------------------------------
// The live sequences, verbatim
// ---------------------------------------------------------------------------
//
// Every property above is generated. These three are the exact shapes a real
// session produced on 2026-11-01, replayed so a regression names the incident.

test("live 01a19547: an ephemeral title turn mid-stream never becomes the chain", async () => {
  // 04:04:11 main turn 1 (1 message) -> presented 3.
  // 04:04:29 the title fork runs 4 ms before the main turn's four calls finish:
  //           [user, assistant(partial text), developer(reminder), user(<title>)]
  // 04:04:29 the main turn continues: [user, assistant(text + 4 calls), 4 results]
  // The old chain accepted the fork and overwrote `#presented`; the main turn's
  // next request then differed at index 1 and every request refused.
  for (const hostName of HOST_NAMES) {
    const session = await new Session({ host: HOSTS[hostName], sessionId: `live-${hostName}` }).begin();
    session.grow({ role: "user", content: [{ type: "text", text: "请你报告本项目的完成度" }] });
    const first = await session.request();
    assert.equal(first.anchored, false);
    const presentedBefore = session.lastSent.length;

    // The ephemeral turn, with the in-flight assistant message truncated.
    const side = session.sideTurnCopy({ prompt: "<title-request>", streamedText: "我将先调派多路 worker 并行调查 " });
    const sideResult = await session.request({ kind: REQUEST_KINDS.EPHEMERAL, copy: side });
    assert.ok(Array.isArray(sideResult.sent), "the side turn still gets a copy");
    assert.equal(session.lastSent.length, presentedBefore, `${hostName}: the side turn did not become the chain`);
    assert.equal(session.anchors.length, 0, `${hostName}: the side turn did not anchor`);

    // The main turn finishes: one assistant message with four calls, then four
    // results. Nothing about it was rewritten.
    session.grow({
      role: "assistant",
      content: [
        { type: "text", text: "我将先调派多路 worker 并行调查 " },
        ...[1, 2, 3, 4].map((n) => ({ type: "tool-call", toolName: "js-worker", toolCallId: `c${n}`, input: { n } })),
      ],
    });
    for (const n of [1, 2, 3, 4]) {
      session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: `c${n}`, output: { type: "text", value: "ok" } }] });
    }
    const second = await session.request();
    assert.equal(second.anchored, false, `${hostName}: the main turn needed no re-base`);
    assert.ok(Array.isArray(second.sent), `${hostName}: the main turn was not refused`);
  }
});

test("live 7d4063b0: a resetScope prune re-bases instead of refusing", async () => {
  // The last presented copy held 31 messages (15 tool calls). The 15th call
  // carried `resetScope`, so Scope pruned the first 14 beats and the next copy
  // was 3 messages: [user, the 15th call, its result]. The old chain refused at
  // index 1; the copy is legitimately shorter AND its index-1 message is one
  // the model had already read as message #29.
  for (const hostName of HOST_NAMES) {
    const session = await new Session({ host: HOSTS[hostName], sessionId: `prune-${hostName}` }).begin();
    session.grow({ role: "user", content: [{ type: "text", text: "long task" }] });
    await session.request();
    for (let n = 1; n <= 14; n += 1) {
      session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: `c${n}`, input: {} }] });
      session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: `c${n}`, output: { type: "text", value: "ok" } }] });
      await session.request();
    }
    const before = session.lastSent.length;
    // The 15th call opens a new Scope.
    session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c15", input: {} }] });
    session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c15", output: { type: "text", value: "budget" } }] });
    const settled = await session.settle({ kind: "resetScope", newScopeGoal: "g", newScopeRetentionBudget: 6 }, "c15");
    assert.equal(settled.ok, true, `${hostName}: reset refused: ${settled.code}`);
    const after = await session.request();
    assert.equal(after.anchored, true, `${hostName}: the prune was not recognised as the spec's own`);
    assert.equal(after.reason, REBASE_REASONS.SCOPE_RESET);
    assert.ok(session.lastSent.length < before, `${hostName}: the copy was expected to shrink`);
  }
});

test("host bookkeeping is invisible to the chain, whatever field the host invents", async () => {
  // §6.1 is about the CONVERSATION. A host stamps `usage`, `duration`, `ttft`,
  // `responseId`, `partialArgs`, `streamIndex`, and fields that do not exist yet;
  // it rebuilds objects with their keys in another order. None of that is
  // something the model read, so none of it may move the chain.
  //
  // The field names are GENERATED. An adapter that keeps its own blacklist
  // passes for the fields it knows and fails for the next one the host adds —
  // which is exactly how the blacklist broke. Deferring to core's canonical
  // comparison passes for all of them, including `futureField`.
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom(...HOST_NAMES),
      fc.array(
        fc.oneof(
          fc.record({
            kind: fc.constant("restamp"),
            index: fc.nat({ max: 5 }),
            keys: fc.uniqueArray(
              fc.constantFrom("usage", "duration", "ttft", "responseId", "partialArgs", "streamIndex", "completedAt", "contextSnapshot", "futureField", "unknownToAnyAdapter"),
              { minLength: 1, maxLength: 3 },
            ),
            value: fc.oneof(fc.integer(), fc.string({ maxLength: 6 }), fc.record({ n: fc.integer() })),
          }),
          fc.record({ kind: fc.constant("reorder"), index: fc.nat({ max: 5 }) }),
        ),
        { minLength: 1, maxLength: 5 },
      ),
      async (hostName, churn) => {
        const session = await new Session({ host: HOSTS[hostName], sessionId: `churn-${hostName}` }).begin();
        // A conversation with every message kind the hosts produce.
        session.grow({ role: "user", content: [{ type: "text", text: "go" }] });
        session.grow({ role: "assistant", content: [{ type: "text", text: "ok" }] });
        session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: { a: 1 } }] });
        session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c1", output: { type: "text", value: "v" } }] });
        await session.request();
        for (const step of churn) {
          await turnOf(step).apply(session);
          const result = await session.request();
          assert.equal(result.anchored, false, `${hostName}: bookkeeping churn re-based the chain: ${JSON.stringify(step)}`);
        }
      },
    ),
    { ...fcRuns(80) },
  );
});

test("a multi-tool turn with adjacent results never drifts", async () => {
  // The turn from `01a19547`: one assistant message carrying several calls, then
  // their results arriving as ADJACENT `tool` messages. A host that merges,
  // reorders or re-spells those results changes the copy without changing the
  // conversation, and every request of the turn must still be accepted.
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom(...HOST_NAMES),
      fc.integer({ min: 2, max: 5 }),
      fc.constantFrom(1, 2, 3),
      async (hostName, count, batch) => {
        const session = await new Session({ host: HOSTS[hostName], sessionId: `multi-${hostName}` }).begin();
        session.grow({ role: "user", content: [{ type: "text", text: "dispatch" }] });
        await session.request();
        // The calls are issued together; the request that carries them is built
        // before any result exists.
        const ids = Array.from({ length: count }, (_, n) => `m${n}`);
        session.grow({
          role: "assistant",
          content: [
            { type: "text", text: "dispatching" },
            ...ids.map((id) => ({ type: "tool-call", toolName: "js-worker", toolCallId: id, input: { id } })),
          ],
        });
        await session.request();
        // Results arrive in batches, each batch its own request.
        for (let at = 0; at < ids.length; at += batch) {
          for (const id of ids.slice(at, at + batch)) {
            session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: id, output: { type: "text", value: `ok-${id}` } }] });
          }
          const result = await session.request();
          assert.equal(result.anchored, false, `${hostName}: the multi-tool turn re-based`);
        }
      },
    ),
    { ...fcRuns(60) },
  );
});

test("adversarial interleaving: zero drift for every legal sequence, 100% for an unauthorised rewrite", async () => {
  // The property stated as ONE run per sequence, so its two halves cannot be
  // satisfied separately.
  //
  // A sequence mixes every real host pattern: operator turns, calls in flight,
  // results, failed calls, a multi-tool turn with adjacent results, streaming
  // bookkeeping churn, an OMP ephemeral side turn carrying a truncated in-flight
  // assistant message, a `removeLast` beat replacement, a `resetScope` prune, a
  // host compaction, a relay handover and a cold restart.
  //
  // Then, on the SAME session, one message the model has already read is
  // rewritten with no projection behind it, and that request must be refused. If
  // the chain ever stops comparing, the first half still passes and the second
  // fails; if it re-anchors too eagerly, the reverse. Together they pin the rule.
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom(...HOST_NAMES),
      fc.array(
        fc.oneof(
          { weight: 3, arbitrary: fc.record({ kind: fc.constant("operator"), text: fc.stringMatching(/^[a-z ]{1,10}$/) }) },
          { weight: 2, arbitrary: fc.record({ kind: fc.constant("call"), id: fc.stringMatching(/^c[0-9]$/) }) },
          { weight: 2, arbitrary: fc.record({ kind: fc.constant("result"), id: fc.stringMatching(/^c[0-9]$/), value: fc.stringMatching(/^[a-z ]{0,8}$/) }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("failed"), id: fc.stringMatching(/^f[0-9]$/) }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("finalise"), id: fc.stringMatching(/^c[0-9]$/), text: fc.stringMatching(/^[a-z ]{1,8}$/) }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("multiCall"), count: fc.integer({ min: 2, max: 4 }) }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("multiResult"), count: fc.integer({ min: 1, max: 3 }) }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("restamp"), index: fc.nat({ max: 8 }), keys: fc.uniqueArray(fc.constantFrom("usage", "duration", "ttft", "responseId", "partialArgs", "streamIndex", "futureField"), { minLength: 1, maxLength: 2 }), value: fc.integer() }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("reorder"), index: fc.nat({ max: 8 }) }) },
          { weight: 2, arbitrary: fc.record({ kind: fc.constant("streamedSideTurn"), prompt: fc.stringMatching(/^[a-z ]{1,8}$/), text: fc.stringMatching(/^[a-z ]{1,8}$/) }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("sideTurn"), prompt: fc.stringMatching(/^[a-z ]{1,8}$/) }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("erase"), id: fc.stringMatching(/^e[0-9]$/) }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("reset"), id: fc.stringMatching(/^r[0-9]$/) }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("compacted"), summary: fc.stringMatching(/^[a-z ]{1,10}$/) }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("restart") }) },
          { weight: 2, arbitrary: fc.record({ kind: fc.constant("interleaved"), x: fc.stringMatching(/^x[0-9]$/), y: fc.stringMatching(/^y[0-9]$/) }) },
          { weight: 1, arbitrary: fc.record({ kind: fc.constant("epoch") }) },
        ),
        { minLength: 2, maxLength: 10 },
      ),
      fc.nat({ max: 4 }),
      async (hostName, turns, pick) => {
        const { refusals, session } = await drive({ host: HOSTS[hostName], sessionId: `adv-${hostName}`, turns });

        // HALF ONE: no legal sequence is refused for a prefix reason.
        const drifted = refusals.filter((entry) => entry.refused);
        assert.deepEqual(
          drifted,
          [],
          `${hostName}: a legal interleaving was refused:\n${JSON.stringify(drifted, null, 2)}\nturns: ${JSON.stringify(turns)}`,
        );

        // HALF TWO: an unauthorised rewrite of a message the model has read is
        // refused, every time. Only a message that survives the projection and
        // still reaches the chain is a fair target.
        //
        // The chain must first be SETTLED. If the last turn opened a legitimate
        // break (a reset, an erase, a compaction, an epoch), the next request is
        // the first of a new chain and is not compared with anything — a
        // rejection there would be the sequence's own refusal, not the rewrite's.
        // Send requests until the chain stops re-basing.
        let settled = 0;
        for (let attempt = 0; attempt < 4 && settled < 2; attempt += 1) {
          const before = session.anchors.length;
          try {
            await session.request();
          } catch {
            // The sequence's own refusal; the rewrite cannot be judged here.
            return;
          }
          settled = session.anchors.length === before ? settled + 1 : 0;
        }
        if (settled < 2) return;

        const targets = session.survivingIndices().filter((at) => {
          const message = session.history[at];
          if (message?.scope !== undefined) return false;
          const parts = Array.isArray(message?.content) ? message.content : [];
          if (!parts.every((part) => part?.input?.scope === undefined)) return false;
          // A compaction summary is the host's own rewrite: mutating it is not
          // an unauthorised change, it is editing the thing the spec wrote.
          if (message?.metadata?.kind === COMPACTED_MARK) return false;
          if (message?.metadata?.kind === "compaction_summary") return false;
          return session.unchangedByProjection(at) && session.survivesMutation(at);
        });
        if (targets.length === 0) return;
        const at = targets[pick % targets.length];
        session.history = session.history.map((message, position) =>
          position === at ? { ...message, content: [{ type: "text", text: "REWRITTEN BY NOBODY" }] } : message,
        );
        await assert.rejects(
          session.request(),
          (error) => error?.code === "PREFIX_DRIFT",
          `${hostName}: an unauthorised rewrite was NOT refused (message ${at} of ${session.history.length})\nturns: ${JSON.stringify(turns)}`,
        );
      },
    ),
    { ...fcRuns(150) },
  );
});

test("a compaction restarts the chain once, and the compacted history is then compared normally", async () => {
  // The counterexample the adversarial generator found, kept as a named case so
  // the hole cannot reopen quietly:
  //
  //   call c0 -> operator a -> compacted -> [rewrite a surviving message]
  //
  // The first version reported `history-compacted` on EVERY request after the
  // compaction, because `isCompactedCopy` stays true for the rest of the session
  // (every later copy is built from the compacted projection). The boundary then
  // re-based on every request, and the rewrite was blessed instead of refused.
  //
  // The rule: a compaction re-bases once, when the presented copy did not
  // already carry the summary. Afterwards the compacted history IS the
  // conversation, and it is compared like any other.
  for (const hostName of HOST_NAMES) {
    const session = await new Session({ host: HOSTS[hostName], sessionId: `once-${hostName}` }).begin();
    session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c0", input: {} }] });
    await session.request();
    session.grow({ role: "user", content: [{ type: "text", text: "a" }] });
    await session.request();

    // The compaction itself: one re-base, on the copy that first carries the mark.
    assert.equal(session.compact("summary"), true, `${hostName}: nothing to compact`);
    const afterCompaction = await session.request();
    assert.equal(afterCompaction.anchored, true, `${hostName}: the compaction was not recognised`);
    assert.equal(afterCompaction.reason, REBASE_REASONS.HISTORY_COMPACTED);

    // The NEXT request is ordinary: the compacted history is now the
    // conversation, so it must not re-base again.
    session.grow({ role: "assistant", content: [{ type: "text", text: "b" }] });
    const ordinary = await session.request();
    assert.equal(ordinary.anchored, false, `${hostName}: the compacted history re-based a second time`);

    // And an unauthorised rewrite inside it is refused, not blessed.
    const targets = session.survivingIndices().filter((at) => {
      const message = session.history[at];
      if (message?.metadata?.kind === COMPACTED_MARK) return false;
      if (message?.metadata?.kind === "compaction_summary") return false;
      return session.unchangedByProjection(at) && session.survivesMutation(at);
    });
    if (targets.length === 0) continue;
    const at = targets[0];
    session.history = session.history.map((message, position) =>
      position === at ? { ...message, content: [{ type: "text", text: "REWRITTEN BY NOBODY" }] } : message,
    );
    await assert.rejects(
      session.request(),
      (error) => error?.code === "PREFIX_DRIFT",
      `${hostName}: a rewrite inside the compacted history was NOT refused (message ${at})`,
    );
  }
});

test("seed 1221354670: a compaction that leaves a result without its call is still a chain", async () => {
  // The counterexample fast-check found on run 2 of the generated property
  // (`seed: 1221354670`, shrunk to five turns), pinned so the hole cannot reopen
  // quietly:
  //
  //   failed c6 -> compacted " " -> call c0 -> call c6 -> finalise c0
  //
  // What the generator produced was `[summary, tool(c6)]`: the summary stood
  // where `[call(c6), result(c6)]` had been, so the compacted history carried a
  // result whose call was gone. Every adapter drops that orphan (it is half a
  // pair the provider rejects), and `projectionBoundary` refused to let an
  // adapter's own drop authorise a re-base, so the accepted request recorded
  // `[summary, pair]` as the chain while the host's history still held the
  // result. The next request began `[summary, tool(c6), …]` and was refused at
  // index 1 with `PREFIX_DRIFT` — a legal sequence, refused for a prefix reason.
  //
  // NO HOST PRODUCES THAT SHAPE. All three align the compaction cut so a result
  // never loses its call:
  //
  //   OMP        `findValidCutPoints` (agent/src/compaction/compaction.ts:440)
  //              never offers a `toolResult` as a cut point, so the kept tail
  //              never BEGINS with a result;
  //   Cline      `isSafeCutBoundary` (core/src/extensions/context/compaction-shared.ts:339)
  //              never cuts at a `tool_result`-only message, because its
  //              `tool_use` would be folded into the summary;
  //   OpenCode   `recentStart` (core/src/session/compaction.ts) starts the kept
  //              tail at a user message "so an assistant's tool calls and
  //              results stay together".
  //
  // So the generator was modelling a compaction the hosts do not perform, and
  // the fix belongs in `Session.compact` (see the rule there), not in the chain.
  // This case keeps the shape the hosts DO produce, with the same turns:
  // `[call(c6), result(c6), call(c0), result(c0)]` compacts to
  // `[summary, call(c0), result(c0)]`, because the cut skips back over the two
  // result messages to the last legal boundary. What is pinned is that the
  // compaction is recognised once, that the chain it records is the host's
  // history plus the pair (not the adapter's filtered copy, which is what would
  // make the next request drift at index 1), and that the two requests after it
  // extend it.
  //
  // The assertions distinguish the two cut rules, so the case cannot pass by
  // accident: the tail must be exactly `[call(c0), result(c0)]`. A model that
  // kept only the last message would produce `[summary, result(c0)]` — the
  // orphan — and fail on the length and on the tail's first message.
  for (const hostName of HOST_NAMES) {
    const session = await new Session({ host: HOSTS[hostName], sessionId: `seed-1221354670-${hostName}` }).begin();

    // 1. failed c6: a call that never produced output, and the result saying so.
    session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c6", input: { fail: true } }] });
    session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c6" }] });
    await session.request();

    // The next turn is in flight when the compaction runs: a call with no
    // result yet, followed by its answer. The pair sits AFTER the result, so the
    // cut has to skip over the result to reach a legal boundary — a model that
    // kept only the last message would keep the answer alone and orphan it.
    session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c0", input: { id: "c0" } }] });
    session.grow({ role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c0", output: { type: "text", value: "ok" } }] });
    await session.request();

    // 2. The compaction, cut the way every host cuts it: the summary takes the
    //    first turn, the kept tail begins at a turn boundary. `c0` is in flight,
    //    so the tail is a call with no result — the legitimate half-pair shape.
    assert.equal(session.compact(" "), true, `${hostName}: nothing to compact`);
    const afterCompaction = await session.request();
    assert.equal(afterCompaction.anchored, true, `${hostName}: the compaction was not recognised`);
    assert.equal(afterCompaction.reason, REBASE_REASONS.HISTORY_COMPACTED);
    // The summary stands where the conversation began, and the tail is the
    // host's own copy. A chain that recorded the adapter's filtered copy would
    // be one message short of the history the next request is built from.
    assert.equal(session.lastSent[0]?.metadata?.kind, COMPACTED_MARK, `${hostName}: the summary is not at the head`);
    assert.equal(session.history.length, 3, `${hostName}: the tail is not the one the hosts cut`);
    // Compared by what the model reads, not by object identity: Cline's chain
    // copy is the CUE-rendered form (§6.3), so it is a different object carrying
    // the same message.
    assert.equal(
      canonicalKey(session.lastSent[1]),
      canonicalKey(session.history[1]),
      `${hostName}: the chain skipped the message the host's tail begins with`,
    );
    assert.equal(session.lastSent.length - 2, session.history.length, `${hostName}: the chain is not the host's history plus the pair`);

    // 3. The next requests extend it. `c6` is issued again and `c0` is answered
    //    — the sequence the generator shrank to, and the one that used to drift.
    session.grow({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c6", input: { id: "c6" } }] });
    const secondCall = await session.request();
    assert.equal(secondCall.anchored, false, `${hostName}: the second call needed no re-base`);

    session.grow({
      role: "assistant",
      content: [{ type: "text", text: "a" }, { type: "tool-call", toolName: "js-worker", toolCallId: "c0", input: {} }],
      usage: { input: 7, output: 3, totalTokens: 10 },
      responseId: "r-c0",
      duration: 12,
      timestamp: 1,
    });
    const settled = await session.request();
    assert.equal(settled.anchored, false, `${hostName}: the settled stream needed no re-base`);
  }
});

test("an interleaved turn compacted at its second assistant keeps the host's copy", async () => {
  // The legal OMP shape `findValidCutPoints` allows and single-message turns
  // cannot build: one turn holding two exchanges, compacted at the SECOND
  // assistant message.
  //
  //   assistant(call cX) -> tool(cX) -> assistant(text, call cY) -> tool(cY)
  //
  // `agent-loop.ts` pushes each `toolResult` into the turn's context while the
  // run continues, so this is one turn; `findValidCutPoints`
  // (`agent/src/compaction/compaction.ts:440-475`) counts only `user`,
  // `bashExecution`, `branchSummary` and `customMessage` as turn starts, so the
  // cut may land on `assistant(text, call cY)`. The summary then takes
  // `call(cY)` and its result, and the tail begins with `tool(cX)` — a result
  // whose call went into the summary.
  //
  // The adapter drops that orphan (it is half a pair the provider rejects), and
  // the chain must still record the host's own copy: the host's history holds
  // the message, so the next request begins with it. The compaction re-bases
  // exactly once, and `presented` minus the pair equals the host's history.
  for (const hostName of HOST_NAMES) {
    const session = await new Session({ host: HOSTS[hostName], sessionId: `interleaved-${hostName}` }).begin();
    session.history = [
      { role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "x0", input: { id: "x0" } }] },
      { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "x0", output: { type: "text", value: "ok-x0" } }] },
      {
        role: "assistant",
        content: [
          { type: "text", text: "next" },
          { type: "tool-call", toolName: "js-worker", toolCallId: "y0", input: { id: "y0" } },
        ],
      },
      { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "y0", output: { type: "text", value: "ok-y0" } }] },
    ];
    // The request that reads the turn. Nothing is refused.
    const first = await session.request();
    assert.equal(first.anchored, false, `${hostName}: the first request of an epoch must not re-base`);

    // The compaction, cut at the second assistant: summary + `[tool(x0)]`.
    session.history = [
      { role: "user", content: [{ type: "text", text: "summary" }], metadata: { kind: COMPACTED_MARK } },
      session.history[1],
    ];
    const afterCompaction = await session.request();
    assert.equal(afterCompaction.anchored, true, `${hostName}: the compaction was not recognised`);
    assert.equal(afterCompaction.reason, REBASE_REASONS.HISTORY_COMPACTED);
    assert.equal(session.anchors.filter((reason) => reason === REBASE_REASONS.HISTORY_COMPACTED).length, 1, `${hostName}: the compaction anchored more than once`);

    // The invariant: the chain recorded the copy that actually went out, and
    // that copy is what the next request will be measured against.
    //
    // The orphan result does NOT reach the wire — the adapter drops it on every
    // request, not only on the compaction one, because it answers a call the
    // request does not carry. So the invariant is not "the chain equals the
    // host's history": it is "the chain equals the copy the provider receives",
    // which is what §6.1 protects. The failure this pins is a chain that records
    // the PREVIOUS conversation while the request carries a different one.
    const pairFree = session.hint.injector.withoutInjection(session.lastSent);
    assert.deepEqual(
      pairFree.map((message) => canonicalKey(message)),
      afterCompaction.sent
        .filter((message) => !JSON.stringify(message).includes("pair-hint:"))
        .map((message) => canonicalKey(message)),
      `${hostName}: the chain is not the copy that went out`,
    );
    // The summary is the head, and the result the adapter dropped is not in it.
    assert.equal(canonicalKey(pairFree[0]), canonicalKey(session.history[0]), `${hostName}: the summary is not the head`);
    assert.equal(pairFree.length, 1, `${hostName}: the adapter's dropped orphan is back in the chain`);

    // And the next request extends it, with the host's own growth.
    session.grow({ role: "assistant", content: [{ type: "text", text: "done" }] });
    const next = await session.request();
    assert.equal(next.anchored, false, `${hostName}: the request after the compaction needed no re-base`);
  }
});

