// Pair Hint: one session's language, its pair resources, and the injection of
// the resulting hint into every outbound request (spec §6.1).
//
// This class is the only entry a host adapter needs. It owns four steps and
// nothing else:
//   1. bind     the session language once, from the ladder or from a fork owner
//   2. render   the hint text from the resource pair of the bound language
//   3. place    one synthetic call/result pair into the request copy
//   4. record   the presentation fact, so a cold start rebuilds the same bytes
//
// It does not judge language use, does not summarise, and does not touch the
// host's stored history (spec §6.1).
//
// Requests of one session are serialized. Two overlapping requests would each
// read the same "last request", each inject, and then one of them would be
// rejected as drift although the host did nothing wrong; running them one after
// another keeps the prefix chain honest.

import { refuse, invalidArgument, PairHintError, PAIR_HINT_CODES } from "./errors.mjs";
import {
  bindSession,
  LanguageBinding,
  LANGUAGES,
  RESOURCE_NAMES,
} from "./language.mjs";
import { ResourceLibrary, RESOURCE_IDS } from "./resources.mjs";
import {
  assertIndex,
  digestOf,
  factFor,
  findInjection,
  parseHint,
  renderPresentation,
  syntheticMessages,
} from "./presentation.mjs";
import { INJECTION_MODES, PairHintInjector } from "./injection.mjs";
import { foldRecords, JournalStore, PairHintState, RECORD_KINDS, refusalRecord } from "./facts.mjs";
import { isNonEmptyString } from "../util.mjs";

// Why a chain was re-based. A re-base is always a projection the SPEC owns
// replacing the copy, and the reason is recorded so an audit of the journal can
// tell a legitimate break from a panic that anchored. Exactly these four:
//
//   relay               the §2.4 第 3 步 barrier cut the retired conversation
//   scope-reset         a settled `resetScope` pruned beats the model had read
//   scope-remove        a settled `removeLast` rewrote a beat in place
//   history-compacted   the HOST durably replaced its transcript with a summary
//                       plus a tail (Cline's compaction, which it saves). Scope
//                       is not the author, so this is the one reason a host
//                       reports rather than a projection decides.
export const REBASE_REASONS = Object.freeze({
  RELAY: "relay",
  SCOPE_RESET: "scope-reset",
  SCOPE_REMOVE: "scope-remove",
  HISTORY_COMPACTED: "history-compacted",
});

// What a request is FOR. The chain (§6.1) is the session's conversation, and
// only a MAIN request belongs to it.
//
// A host also sends requests that borrow the conversation but are not part of
// it: OMP's `<title-request>` (a forked side turn that names the session while
// the main turn is still streaming), OpenCode's `generate` (warming and
// generation, built from the session history), Cline's hook-driven model calls.
// They share the `context` hook, they reuse the context prefix ON PURPOSE (the
// OMP side turn says so: "reuses current conversation context ... to keep
// prompt cache warm"), and they are DISCARDED: the host appends none of them to
// the transcript.
//
// Such a request is a BRANCH of the chain, not a step of it. Pair Hint still
// places the pair exactly where the main chain would put it — same epoch, same
// `after`, same bytes — so the provider's prefix cache is hit; it then records
// nothing, so the branch can never become the chain the next main request is
// measured against.
//
// Live (2026-11-01, session `01a19547`): a title fork ran 4 ms before the main
// turn's four tool calls finished. It extended the pair-free prefix, so the
// chain accepted it and OVERWROTE `#presented` with the fork's copy (partial
// assistant text + the side-channel reminder + the title prompt). The main
// turn's next request then differed at index 1 — the real assistant message
// with its four calls, against the fork's 76-byte partial — and every request
// of the session refused `PREFIX_DRIFT` at index 1.
export const REQUEST_KINDS = Object.freeze({
  MAIN: "main",
  // A side turn / side channel: shares the context, appends nothing.
  EPHEMERAL: "ephemeral",
  // A request whose messages are a view of history that the host rebuilds and
  // never stores (a summary, a compaction prompt). It is compared with the
  // chain when it extends it, and ignored when it does not.
  BRANCH: "branch",
});

const isRequestKind = (value) => Object.values(REQUEST_KINDS).includes(value);

const isEphemeral = (kind) => kind === REQUEST_KINDS.EPHEMERAL;

const isRebaseReason = (value) => value === null || Object.values(REBASE_REASONS).includes(value);

// How many messages of a presented request come before its pair, or null when
// it carries none. This is where the NEXT epoch puts its pair: the old request
// is the new one minus the old pair, so the new pair belongs exactly there.
const shownPlacementOf = (messages) => findInjection(messages)?.index ?? null;

export class PairHint {
  #after;
  #chain;
  #digest;
  #entries;
  #epoch;
  #fixed;
  #handles;
  #injector;
  #language;
  #library;
  #loaded;
  #presented;
  #presentedEpoch;
  #rebased;
  #sessionId;
  #state;
  #store;
  #text;

  // `store` is a JournalStore (or anything with the same read/append shape).
  // `library` is a ResourceLibrary; both are injectable for tests.
  constructor({ store, library = new ResourceLibrary(), injector = new PairHintInjector() }) {
    if (
      store === null ||
      typeof store !== "object" ||
      typeof store.read !== "function" ||
      typeof store.append !== "function"
    ) {
      throw invalidArgument("PairHint needs a journal store with read and append");
    }
    this.#store = store;
    this.#library = library;
    this.#injector = injector;
    this.#sessionId = typeof store.sessionId === "string" ? store.sessionId : null;
    this.#state = new PairHintState();
    this.#language = null;
    this.#handles = [];
    this.#epoch = 0;
    this.#presented = null;
    this.#presentedEpoch = null;
    // The reason the NEXT presented record carries, or null. Set by `anchor`
    // and cleared by the append that records it, so one re-base is named once.
    this.#rebased = null;
    this.#loaded = false;
    this.#chain = Promise.resolve();
    // The presentation that is frozen for this epoch, once one exists. Both
    // fields are set together and cleared together.
    this.#text = null;
    this.#digest = null;
    this.#entries = null;
    this.#fixed = null;
    this.#after = null;
  }

  // `sessionId` is the session the hint speaks for. It may differ from the
  // store's own key (`store.sessionId`): a host whose ids are not file-safe keys
  // the journal by a digest and passes the real id here. The journal's binding
  // record is the authority once one exists.
  static async open({ root, sessionId, store = null, library, injector } = {}) {
    const journal = store ?? new JournalStore({ root, sessionId });
    const instance = new PairHint({ store: journal, library, injector });
    await instance.load();
    if (instance.#sessionId === null && typeof sessionId === "string" && sessionId.length > 0) {
      instance.#sessionId = sessionId;
    }
    return instance;
  }

  // Read the journal into memory. After this, the instance answers from facts.
  async load() {
    const state = await this.#store.read();
    if (
      state.binding !== null &&
      this.#sessionId !== null &&
      state.binding.sessionId !== this.#sessionId
    ) {
      throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "journal belongs to another session", {
        journal: this.#sessionId,
        record: state.binding.sessionId,
      });
    }
    this.#state = state;
    this.#sessionId = state.binding === null ? this.#sessionId : state.binding.sessionId;
    this.#language = state.binding === null ? null : state.binding.language;
    this.#epoch = state.epoch;
    this.#presented = state.presented;
    this.#presentedEpoch = state.presented === null ? null : state.epoch;
    this.#adopt(state);
    this.#loaded = true;
    return state;
  }

  // Take the frozen presentation out of a stored fact. The hint text and the
  // resource list are stored in the journal, not re-read from the resources
  // directory: a cold start must reproduce the same bytes even if the resource
  // files changed after the epoch began (§6.1 step 6).
  #adopt(state) {
    const fact = state.fact;
    if (fact === null) {
      this.#text = null;
      this.#digest = null;
      this.#entries = null;
      this.#fixed = null;
      this.#after = null;
      this.#handles = [];
      return;
    }
    if (typeof fact.text !== "string" || !Array.isArray(fact.entries)) {
      throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "a stored fact carries no frozen presentation", {
        epoch: fact.epoch,
      });
    }
    this.#text = fact.text;
    this.#digest = fact.digest;
    this.#entries = [...fact.entries];
    this.#fixed = fact;
    this.#after = fact.after;
    this.#handles = [...fact.handles];
  }

  get sessionId() {
    return this.#sessionId;
  }

  get language() {
    return this.#language;
  }

  get epoch() {
    return this.#epoch;
  }

  get bound() {
    return this.#language !== null;
  }

  get binding() {
    return this.#state.binding;
  }

  get handles() {
    return [...this.#handles];
  }

  // The fact of the last request, or null before the first request of this
  // epoch.
  get fact() {
    return this.#state.fact;
  }

  // The request copy the last call sent, or null. This is the anchor of the
  // prefix chain.
  get presented() {
    return this.#presented;
  }

  // The last refusal this session recorded, or null. Diagnostic only: no
  // request path reads it, and a refusal never moves the chain.
  get refusal() {
    return this.#state.refusal;
  }

  // The projection that replaced the chain the last presented request
  // continued ("relay" | "scope-reset" | "scope-remove"), or null. Diagnostic
  // only: a cold start reads it back so an audit can tell a legitimate break
  // from a panic that anchored.
  get rebased() {
    // The pending reason (an anchor whose next request has not been recorded
    // yet) or the one the newest presented record carries. Either way the
    // answer names the last re-base the journal knows about.
    return this.#rebased ?? this.#state.rebased;
  }

  // Re-base the prefix chain on the copy this request is ABOUT TO SEND.
  //
  // §6.1 makes one epoch one chain, and the chain exists so the model never
  // reads two different versions of the same position. It has one legitimate
  // break: the request copy itself is replaced by a fact the host owns — the
  // relay barrier (根 §2.4 第 3 步) cuts the retired conversation out of a successor's
  // first request, so that request cannot begin with the bytes of a
  // conversation the successor will never see.
  //
  // `base` is that new copy BEFORE this epoch's pair is placed. The anchor is
  // `base` with this epoch's pair after it, because that is the shape the first
  // request of the new chain will send — and the prefix rule compares two
  // requests that both carry the pair, so the anchor must carry it too.
  //
  // Two mistakes are worth naming, because both were made here:
  //
  //   - anchoring on `base` alone leaves the chain without the pair the model
  //     actually read;
  //   - anchoring on `base` alone AND letting the next `project` place the pair
  //     at the end of a copy the host has since grown moves the pair on every
  //     request, which is `PREFIX_DRIFT` on the successor's own second request.
  //
  // So the placement is decided HERE (`#after = base.length`), the pair stands
  // after the copy's own messages, and `project` replays it there.
  //
  // The anchor takes the copy it is GIVEN, even when that copy is shorter than
  // what was presented before. That is not a weakening: the caller only reaches
  // `anchor` when a fact the host owns replaced the copy (the relay barrier), and
  // the shortened copy is exactly what the successor will be sent. Keeping the
  // longer earlier copy here would anchor the chain on a conversation the
  // successor never sees. Every request that does NOT pass through the barrier
  // is still compared against the previous one, so a rewritten message inside
  // the epoch is still drift.
  //
  // The anchor OPENS the chain: the next request is the first of a new chain and
  // is not compared with the request that preceded the break. Without that, the
  // anchor did nothing a prefix check could see — `#presentedEpoch` still named
  // the current epoch, so the very next `project` compared the anchored copy
  // with the bytes of the conversation the caller had just declared broken and
  // refused with `PREFIX_DRIFT` (isolated reproduction, 2026-11-01). A caller
  // that means "start again from this copy" gets exactly that.
  //
  // The chain break is the caller's claim about a fact the host owns. It does
  // not rewrite any byte: `base` is what the caller is about to send, and the
  // pair still carries this epoch's frozen text. Nothing else moves — the text,
  // the epoch, the handles and the placement all stay — so the requests that
  // follow extend the anchored copy and the bytes already shown are untouched.
  //
  // An epoch change is a chain break of its own (§6.1): a host that has just
  // opened an epoch must not anchor as well, or the placement `newEpoch`
  // decided would be thrown away. That guard belongs to the caller, because
  // only it knows which fact replaced the copy.
  anchor(base, { reason = null } = {}) {
    if (!Array.isArray(base)) throw invalidArgument("anchor needs the request copy");
    if (!isRebaseReason(reason)) throw invalidArgument(`unknown re-base reason: ${String(reason)}`);
    this.#rebased = reason;
    if (!this.bound || this.#text === null) {
      // Nothing is shown in this epoch yet, so there is no pair to anchor: the
      // next `project` decides the placement and starts the chain itself.
      this.#presented = null;
      this.#presentedEpoch = null;
      this.#after = null;
      return this;
    }
    const copy = (message) => (message !== null && typeof message === "object" ? { ...message } : message);
    const cut = base.length;
    const fact = factFor({
      epoch: this.#epoch,
      text: this.#text,
      handles: this.#handles,
      after: cut,
      inserted: cut,
    });
    this.#presented = [...base.map(copy), ...syntheticMessages({ fact, text: this.#text })];
    // The new chain has no predecessor: the next `project` skips the prefix
    // check against what stood before the break, and records its own request as
    // the chain's first member.
    this.#presentedEpoch = null;
    this.#after = cut;
    return this;
  }

  get injector() {
    return this.#injector;
  }

  // The hint text frozen for this epoch, or null before its first request.
  get text() {
    return this.#text;
  }

  // Bind at creation. `forkOf` is the owner's binding (or another PairHint);
  // `ladder` is `{ explicit, host, locale }`. A session binds once: a second
  // bind with another language is refused, and the same language is a no-op, so
  // a restart cannot move a bound session.
  async bind({ sessionId = null, forkOf = null, ladder = {} } = {}) {
    if (!this.#loaded) await this.load();
    if (this.#language !== null) {
      if (sessionId !== null && this.#sessionId !== null && sessionId !== this.#sessionId) {
        throw invalidArgument("a session cannot be rebound to another id", { sessionId });
      }
      return this.binding;
    }
    const owner = forkOf instanceof PairHint ? forkOf.binding : forkOf;
    if (owner !== null && !(owner instanceof LanguageBinding)) {
      throw invalidArgument("forkOf must be a LanguageBinding or a bound PairHint");
    }
    if (owner !== null && sessionId === null) {
      throw invalidArgument("binding a fork needs the child session id");
    }
    const target = sessionId ?? this.#sessionId;
    if (!isNonEmptyString(target)) {
      throw invalidArgument("binding needs the session id");
    }
    const binding =
      owner === null ? bindSession({ sessionId: target, ladder }) : owner.fork(target);
    this.#state = await this.#store.append({
      kind: RECORD_KINDS.BIND,
      binding: binding.toRecord(),
    });
    this.#language = binding.language;
    this.#sessionId = binding.sessionId;
    return binding;
  }

  // Replace the unsettled park handles of this session. Handles are facts the
  // surface supplies; Pair Hint only lists them (§6.1). The change is only
  // visible to the next epoch: the current epoch refuses to change its bytes.
  setHandles(values = []) {
    if (!Array.isArray(values)) throw invalidArgument("handles must be an array");
    this.#handles = values;
    return this;
  }

  // Render the hint text from the resource pairs of the bound language. This is
  // the text the *next* epoch would show; the current epoch keeps the text it
  // already froze.
  async render() {
    if (!this.bound) {
      throw refuse(PAIR_HINT_CODES.INVALID_LANGUAGE, "session has no language binding", {
        sessionId: this.#sessionId,
      });
    }
    const pairs = await this.#library.loadAll();
    const blocks = {};
    for (const pair of pairs) {
      blocks[pair.id] = pair.book(this.#language);
    }
    const entries = pairs.map((pair) => pair.id);
    return renderPresentation({
      language: this.#language,
      entries,
      blocks,
      handles: this.#handles,
    });
  }

  // Freeze the presentation for the current epoch. Called once, by the first
  // request of an epoch, and again only by a new epoch.
  async #freeze() {
    if (this.#text !== null) return;
    const text = await this.render();
    this.#text = text;
    this.#digest = digestOf(text);
    this.#entries = (await this.#library.loadAll()).map((pair) => pair.id);
  }

  // The presentation fact of the next request in the current epoch.
  async currentFact() {
    await this.#freeze();
    return factFor({
      epoch: this.#epoch,
      text: this.#text,
      handles: this.#handles,
      after: this.#after,
    });
  }

  // Run `step` after every earlier request of this session settled. A failure
  // does not break the chain for later callers.
  #serialize(step) {
    const run = () => step();
    const next = this.#chain.then(run, run);
    this.#chain = next.then(
      () => undefined,
      () => undefined,
    );
    return next;
  }

  // The request path. Returns `{ messages, mode, fact, text }`; `messages` is a
  // new array and the input is never mutated.
  //
  // `kind` says what the request is FOR (`REQUEST_KINDS`). A MAIN request is a
  // step of the chain and is recorded; an EPHEMERAL one (a side turn the host
  // discards) is a branch: it gets the pair at the chain's own placement, so it
  // shares the provider's cached prefix, and records nothing, so it can never
  // become the chain. An unknown kind is a caller bug.
  async project({ messages, kind = REQUEST_KINDS.MAIN }) {
    if (!isRequestKind(kind)) throw invalidArgument(`unknown request kind: ${String(kind)}`);
    return await this.#serialize(async () => {
      // Every failure here refuses the request, so nothing is sent and no fact
      // moves. The only trace would be the caller's error, which carries a
      // sentence and an index — enough to know that something drifted, never
      // enough to know what. The journal is the one place a later reader can
      // reach, so the refusal is written there before it is raised, and it is
      // raised unchanged: this is a record, not a recovery.
      try {
        return await this.#project(messages, kind);
      } catch (error) {
        await this.#recordRefusal(error, messages);
        throw error;
      }
    });
  }

  // Write one `refused` record for a refused request. Never throws: a journal
  // that cannot be written must not replace the real failure with its own.
  async #recordRefusal(error, messages) {
    if (!(error instanceof PairHintError)) return;
    const details = error.details ?? {};
    try {
      // The sides are recorded exactly as the refusing check saw them: the
      // refusal carries them (`details.compared`), so nothing here has to guess
      // which copy an `index` counts. `index` is read in `next`. A refusal that
      // compared nothing (a missing placement, a language with no binding) still
      // records its code and epoch, with `previous`/`next` null.
      const compared = details.compared ?? null;
      const previous = compared?.previous ?? null;
      const next = compared?.next ?? null;
      await this.#store.append(
        refusalRecord({
          code: error.code,
          message: error.message,
          epoch: this.#epoch,
          index: Number.isInteger(details.index) ? details.index : null,
          previous,
          next,
        }),
      );
    } catch {
      // A diagnostic that cannot be stored is lost; the refusal is not.
    }
  }

  async #project(messages, kind = REQUEST_KINDS.MAIN) {
    await this.#freeze();
    const fact = factFor({
      epoch: this.#epoch,
      text: this.#text,
      handles: this.#handles,
      after: this.#after,
    });
    const { messages: projected, mode, fact: decided, text: shown } = this.#injector.inject({
      messages,
      fact,
      text: this.#text,
      // A branch may hand a copy shorter than the recorded placement; only a
      // branch is allowed to clamp (see `cutFor`).
      branch: isEphemeral(kind),
    });
    // A pair-free copy can still be a replay.
    //
    // The host never stores our pair, so the next request arrives WITHOUT it and
    // `inject` takes its "no pair in this copy" branch, which can only answer
    // `placed`: it knows the placement is decided (`fact.after !== null`) but not
    // whether this epoch has already shown anything. The mode is observable
    // (`mode`, and the tests that pin a retry), so it must say what happened.
    //
    // This epoch already presented a copy, that copy was pair-free at the same
    // placement, and the bytes this request will carry are the same ones: that is
    // a replay by definition — the host rebuilt the same request and nothing
    // changed. Any other case stays `placed`: a first request of an epoch, a copy
    // that grew, or one whose placement moved.
    //
    // "Same request" is ONE rule, not two: `samePrefix` is the chain's own
    // canonical comparison applied to two equal-length copies. A lookalike
    // `JSON.stringify(a) === JSON.stringify(b)` here would be a second
    // definition that could drift from the chain's — and it already did once.
    const replayed =
      mode === INJECTION_MODES.PLACED &&
      this.#presentedEpoch === this.#epoch &&
      this.#presented !== null &&
      this.#injector.shownPlacement(messages) === null &&
      this.#injector.samePrefix({ previous: this.#presented, next: projected });
    // Only requests of one epoch form one prefix chain: a later request must
    // extend the earlier one (§6.1). The first request of a new epoch starts
    // a fresh chain, and nothing already shown is rewritten there.
    //
    // No mode is exempt. A replay is still a request that goes out: if the
    // host changed a message it already presented, the next request would not
    // start with the previous bytes, whatever the mode says. The check runs on
    // every request of the epoch, replay or not.
    //
    // A copy that already starts with the presented request extends the chain
    // by construction, so its pair may stand at another index: the host
    // handed back the request it sent, or a caller grew the copy. That place
    // becomes the placement, so the next copy without the pair lands between
    // the same two messages instead of being refused.
    // An EPHEMERAL request is a BRANCH of the chain, never a step of it, and a
    // branch may diverge from the chain in exactly one way.
    //
    // A side turn is built from the conversation AS IT STANDS, which may include
    // a turn that is still streaming. OMP's `#buildEphemeralSnapshot`
    // (agent-session.ts:12929-12973) does one thing to the conversation: it
    // replaces the message that is still being written with a text-only copy of
    // it — its tool calls have not arrived yet — and then appends its own
    // reminder and prompt. Cline's per-call compaction instead hands a SHORTER
    // copy (the host never stores it; the next turn is built from the full
    // transcript again).
    //
    // So a branch may do exactly two things, and nothing else:
    //
    //   replace  ONE message — the last one the chain presented, which is the
    //            only message a host can still be writing — and append after it;
    //   truncate drop a TAIL of the chain, keeping a prefix of it unchanged.
    //
    // Everything else is drift, and a branch label must not excuse it:
    //
    //   - a rewrite anywhere except that last message (the model read it as
    //     final, so it may not change under it);
    //   - a deletion in the middle (no host trims the middle of a request);
    //   - a copy that is neither a prefix nor a single-tail replacement.
    //
    // The pair is then placed at the branch's own end when the copy is too
    // short for the chain's placement: the branch shares the chain's prefix
    // only as far as it shares it, and the provider's cached prefix is that
    // shared part either way. A MAIN request never clamps (see `cutFor`).
    let placement = this.#after ?? decided.after;
    if (isEphemeral(kind) && this.#presentedEpoch === this.#epoch && this.#presented !== null) {
      const anchor = this.#injector.withoutInjection(this.#presented);
      const request = this.#injector.withoutInjection(projected);
      let shared = 0;
      while (shared < anchor.length && shared < request.length && this.#injector.sameMessage(anchor[shared], request[shared])) {
        shared += 1;
      }
      // The three shapes a host's own side copy takes, and nothing else:
      //
      //   appended   the branch extends the chain (the common case: the host
      //              appended its reminder and prompt to the conversation);
      //   truncated  the branch is a strict prefix of the chain (Cline's
      //              per-call compaction hands a shorter copy);
      //   replaced   the chain's LAST message is the one the host was still
      //              writing, and the branch replaces it and appends from there
      //              (OMP's in-flight assistant message, tool calls dropped).
      const appended = shared === anchor.length;
      const truncated = request.length < anchor.length && shared === request.length;
      const replaced = shared === anchor.length - 1 && anchor.length > 0;
      if (!appended && !truncated && !replaced) {
        throw refuse(PAIR_HINT_CODES.PREFIX_DRIFT, "the ephemeral copy rewrote a message the model read as final", {
          epoch: this.#epoch,
          index: shared,
          compared: { previous: anchor, next: request },
        });
      }
      // The pair goes where the branch can carry it: the chain's placement when
      // the copy reaches it, its own end when the branch is shorter.
      if (placement !== null) placement = Math.min(placement, request.length);
    }
    if (!isEphemeral(kind) && this.#presentedEpoch === this.#epoch && this.#presented !== null) {
      if (this.#injector.extendsPrefix({ previous: this.#presented, next: projected })) {
        const shown = this.#injector.shownPlacement(projected);
        if (shown !== null) placement = shown;
      }
      // The request that goes out carries this epoch's pair, at an index the
      // host rebuilt from a history that never stored it. Comparing it with
      // the anchor message-for-message would compare the pair against
      // whatever message now stands at its old index — a drift that is not
      // one. What §6.1 requires is the copy MINUS that pair: the anchor's own
      // messages, unchanged and in order, must be a prefix of the request's.
      //
      // A request that does not carry the pair is not a request of this
      // epoch at all — `inject` places or replaces the pair before this check
      // runs — so only the pair-free comparison is reachable here.
      const anchor = this.#injector.withoutInjection(this.#presented);
      const request = this.#injector.withoutInjection(projected);
      const span = Math.min(anchor.length, request.length);
      // The two sides are handed to the refusal as well as to the check: a
      // refusal records what was actually compared, so its `index` and its
      // shapes share one coordinate system and a reader never reconstructs it.
      const compared = { previous: anchor.slice(0, span), next: request.slice(0, span) };
      try {
        this.#injector.assertPrefix(compared);
      } catch (error) {
        if (error instanceof PairHintError && error.details !== null && typeof error.details === "object") {
          error.details = { ...error.details, compared };
        }
        throw error;
      }
    }
    // An EPHEMERAL request is a branch: it leaves the chain exactly as it
    // found it. The pair is already placed in `projected` (same epoch, same
    // `after`, same bytes, so the provider's cached prefix is hit), but nothing
    // is recorded and nothing in memory moves — not `#presented`, not
    // `#presentedEpoch`, not `#after`, not the pending re-base. The host
    // discards this request, so making the main chain remember it would measure
    // the next real request against words that were never spoken.
    //
    // Its prefix is still checked (a branch that rewrites a message the model
    // read is drift like any other), and the check uses the SAME anchor, so an
    // unapproved rewrite cannot hide behind the branch label.
    if (isEphemeral(kind)) {
      return { messages: projected, mode, fact: decided.at({ after: placement, inserted: decided.inserted }), text: shown, kind };
    }
    // One append carries the bytes, the placement and the frozen presentation.
    // Split across records, a crash would leave one without the others.
    // A re-base is named on the request it produced: `rebased` says which
    // projection replaced the chain, so a reader of the journal can tell a
    // legitimate break (the spec trimmed the copy) from a panic that anchored.
    // It is optional and additive: an older journal folds unchanged.
    this.#state = await this.#store.append({
      kind: RECORD_KINDS.PRESENTED,
      messages: projected,
      length: projected.length,
      ...(this.#rebased === null ? {} : { rebased: this.#rebased }),
      fact: {
        ...decided.at({ after: placement, inserted: decided.inserted }).toRecord(),
        text: this.#text,
        entries: [...this.#entries],
      },
    });
    this.#rebased = null;
    this.#presented = projected;
    this.#presentedEpoch = this.#epoch;
    this.#after = placement;
    // The fact this request shows carries the placement it used, not the
    // undecided one: the caller (and a cold start reading the record) must
    // see where the pair stands, and where the next copy puts it.
    this.#fixed = decided.at({ after: placement, inserted: decided.inserted });
    // `mode` says what happened to the pair, so a byte-identical retry of a
    // pair-free copy reports `replayed`, not `placed`.
    return { messages: projected, mode: replayed ? INJECTION_MODES.REPLAYED : mode, fact: this.#fixed, text: shown };
  }

  // Open a new epoch. The next request renders from current facts, freezes that
  // text, and places the pair again; nothing already shown is rewritten.
  //
  // The new epoch opens where the last request of the old one ended. §6.1 asks
  // for a byte prefix, and a request that does not carry our pair is the
  // previous request minus that pair — a prefix, not an extension. So the new
  // pair goes after all of the new copy's own messages (`after = length`), and
  // the last request of the old epoch is a prefix of it by construction.
  //
  // Without this the new pair would land at the end of the copy while the old
  // pair stood earlier, and the first request of the new epoch would rewrite
  // the position of the old one: an epoch change would break the prefix the
  // epoch exists to preserve, for every host that rebuilds its request from its
  // own history (all three).
  async newEpoch() {
    return await this.#serialize(async () => {
      const next = this.#epoch + 1;
      const openedAt = this.#presented === null ? null : shownPlacementOf(this.#presented);
      this.#state = await this.#store.append({ kind: RECORD_KINDS.EPOCH, epoch: next });
      this.#epoch = next;
      this.#text = null;
      this.#digest = null;
      this.#entries = null;
      this.#fixed = null;
      // The count, not `null`: `null` means "wherever the next copy ends", and a
      // copy that grew past the old pair would then push the new pair behind
      // messages the old pair preceded — the old request would stop being a
      // prefix. The count is where the old pair stood, which is also where the
      // new pair belongs.
      this.#after = openedAt;
      return next;
    });
  }
}

// A journal store over an in-memory list, for tests and for hosts that keep
// their own facts. Same shape as JournalStore.
export class MemoryJournal {
  #chain;
  #records;

  constructor(records = []) {
    this.#records = records.map((record) => JSON.parse(JSON.stringify(record)));
    this.#chain = Promise.resolve();
  }

  get records() {
    return this.#records.map((record) => JSON.parse(JSON.stringify(record)));
  }

  async read() {
    return foldRecords(this.#records);
  }

  append(record) {
    if (record === null || typeof record !== "object") {
      return Promise.reject(invalidArgument("a journal record must be an object"));
    }
    const run = async () => {
      const state = foldRecords(this.#records).apply(record);
      this.#records.push(JSON.parse(JSON.stringify(record)));
      return state;
    };
    const next = this.#chain.then(run, run);
    this.#chain = next.then(
      () => undefined,
      () => undefined,
    );
    return next;
  }
}

export { PairHintState, RESOURCE_IDS, LANGUAGES, RESOURCE_NAMES, assertIndex, parseHint };
