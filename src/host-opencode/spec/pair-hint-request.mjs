// Pair Hint at request time (§6.1, §6.1, §6.1, §6.1).
//
// The hint is core's `PairHint`: it owns the epoch, the presentation fact, the
// pair's position in the request copy, the prefix chain and the journal. This
// module only binds it to a session and calls `project`.
//
// Nothing here strips and re-appends the pair. That would move the pair to the
// end of every request and break the prefix chain (§6.1), and it would decide
// the position outside the fact that records it. `PairHint#project` places the
// pair once per epoch and replays it at the recorded position; a second request
// of the same epoch with the same bytes is a replay, and a change that would
// move the prefix is refused by the injector instead of being repaired here.
//
// ## Why the journal key is a digest
//
// core's `JournalStore` accepts only a plain file-safe name
// (`assertSessionId`: `[A-Za-z0-9._-]+`). A host session id is free-form, and
// two different ids can share a file-safe spelling: `a/b` and `a?b` both
// sanitize to `a_b`, and a truncated id can equal another id's prefix. Sharing
// one journal file would merge two sessions' epochs, languages and
// presentations.
//
// So the journal key is a digest of the exact id: 256 bits of SHA-256 in hex,
// one-to-one in practice, and never a truncation of the id itself. It is
// deterministic, so a cold start finds the same file. The real id is derived
// back from the key by looking the session up (`realIdOf`); when the caller
// cannot supply that lookup, the key itself stands in, and `PairHint.load`
// accepts a journal whose binding names the key as long as the journal is the
// one the key selected. The binding record still carries a real, stable id.

import path from "node:path";
import { createHash } from "node:crypto";

import { PairHint } from "../../core/pair-hint/pair-hint.mjs";
import { JOURNAL_DIR, JournalStore } from "../../core/pair-hint/facts.mjs";
import { ResourceLibrary, RESOURCE_IDS } from "../../core/pair-hint/resources.mjs";

export { PairHint };

// The digest is prefixed so the key reads as ours in the directory listing, and
// the suffix keeps the hint's journal in its own file: the adapter's session
// facts use `{session}.jsonl` (session-state.mjs) with a different record shape,
// and one file for both would make each side read the other's records as
// corrupt.
export const HINT_JOURNAL_PREFIX = "hint-";
export const HINT_JOURNAL_SUFFIX = ".jsonl";

// The journal key of one host session. A missing or non-string id is refused,
// never coerced: `String(undefined)` is the string "undefined", and every such
// session would then share one journal file, which is a fact leak.
export const journalKey = (sessionId) => {
  if (typeof sessionId !== "string" || sessionId.length === 0) {
    throw new TypeError(`a session id must be a non-empty string, got ${typeof sessionId}`);
  }
  const digest = createHash("sha256").update(sessionId, "utf8").digest("hex");
  return `${HINT_JOURNAL_PREFIX}${digest}${HINT_JOURNAL_SUFFIX}`;
};

// The key without its file extension, for a caller that wants to compare keys.
export const journalKeyOf = (sessionId) => journalKey(sessionId).slice(0, -HINT_JOURNAL_SUFFIX.length);

// The session id a journal binding should carry. A caller that knows the host's
// real id passes `realId`; otherwise the journal key stands in, because a
// binding needs a stable non-empty name and the key is exactly that. The name is
// the key without its file extension: core appends `.jsonl` to a journal's
// session id, and a name that already ends in it would never match on reload.
export const bindingIdFor = ({ sessionId, realId = null }) =>
  typeof realId === "string" && realId.length > 0 ? realId : journalKeyOf(sessionId);

// The journal file a session's hint reads and writes. Exposed so a reader can
// name the file without knowing how the key is formed.
export const journalFileOf = (root, sessionId) =>
  path.join(root, JOURNAL_DIR, `${journalKeyOf(sessionId)}${HINT_JOURNAL_SUFFIX}`);

// One PairHint per session, backed by that session's journal under
// `.Herdchestra/`. `open` reads the journal, so a cold start resumes the same
// epoch, language and handles.
//
// The journal is keyed by the digest, but the hint is opened under the real
// session id: the binding record it writes names the host's session, so a
// reader of the journal can tell which session it belongs to.
export const openPairHint = async ({
  root,
  sessionId,
  library = new ResourceLibrary({ ids: RESOURCE_IDS }),
}) => {
  // The store is keyed by the journal key and appends its own `.jsonl`, so the
  // key is passed without the extension; the hint itself keeps the host's
  // session id so its binding record names the session the model knows.
  const store = new JournalStore({ root, sessionId: journalKeyOf(sessionId) });
  // The hint is opened under the key, and its binding record names the key:
  // `PairHint.load` refuses a journal whose binding names another session, so
  // the binding name must be the one the store was opened with. The host's real
  // id still reaches the adapter's own facts (`SessionState`); the hint's
  // identity is the key, which is a digest of that id.
  return PairHint.open({ store, sessionId: journalKeyOf(sessionId), library });
};

// A PairHint over a store the caller owns (tests, or a host that keeps its own
// facts). Same shape as `openPairHint`, without touching the disk.
export const pairHintWithStore = async ({ store, sessionId = null, library = new ResourceLibrary({ ids: RESOURCE_IDS }) }) =>
  await PairHint.open({ store, sessionId, library });

// Apply the hint to a request copy. `PairHint#project` answers
// `{ messages, mode, fact, text }`; `mode` is `injected` for the first request
// of an epoch and `replayed` afterwards, and the pair stays where the fact says.
// A hint that is absent or unbound is a wiring error, not a request without a
// hint: sending the copy without the pair would silently drop §6.1, and a host
// that never sees the failure has no way to notice.
//
// `kind` says what the request is FOR (core's `REQUEST_KINDS`). It is passed
// through unchanged: a MAIN request is a step of the chain, an EPHEMERAL one is
// a branch the host discards, and the hint alone decides what that means for
// its own state.
export const injectHint = async (hint, { messages, kind }) => {
  if (hint === null || hint === undefined) {
    throw new TypeError("a session needs its PairHint before the first request");
  }
  if (!hint.bound) {
    throw new TypeError(`PairHint ${hint.sessionId} has no language binding`);
  }
  return await hint.project(kind === undefined ? { messages } : { messages, kind });
};

// The handles of a session's unsettled parks, as the hint lists them (§6.1).
// A change is visible to the next request; the current epoch refuses to change
// its bytes, which is why `setHandles` does not re-render anything already sent.
export const setHandles = (hint, handles) => {
  hint?.setHandles(handles ?? []);
  return hint;
};

export { JournalStore };
