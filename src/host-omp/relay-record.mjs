// The relay record (root spec §5.3.2): retirement, the context barrier and the
// successor start land as ONE durable record, so a crash never leaves half a
// tenure.
//
// ## Why this is atomic on OMP
//
// The record is a `custom` entry appended to the SESSION's own transcript
// through `SessionManager.appendEntriesAtomically`. That call stages the entry
// and publishes the WHOLE journal with one `rename`
// (`session-manager.ts` `#appendEntriesAtomicallyLocked` →
// `#rewriteAtomically` → `session-storage.ts` `#publishAtomic`: stage to a temp
// file in the same directory, then one `renameSync`). A reader therefore sees
// the journal without the record or with it — never a journal that carries one
// of the three facts and not the others, because all three are fields of ONE
// entry. A failed publish rolls the staged entry back and rethrows, so a
// refused commit leaves no record at all.
//
// A `custom` entry does not participate in LLM context (`session/messages.ts`
// converts `message` and `custom_message`; a `custom` entry is a transcript
// fact only), so writing the record cannot leak into the successor's request —
// the barrier stays the `context` hook's job.
//
// ## The successor is a SESSION of its own
//
// Retirement is `incumbent.newSession({ parentSession })`: OMP's own in-place
// reset, which clears the transcript AND writes the successor's header durably
// before it returns (`SessionManager.newSession` → `ensureOnDisk`). So the
// successor is a real OMP session with a lineage (`parentSession` names the
// transcript it replaced), and the incumbent keeps its own identity, ledger and
// role — the succession is a new tenure, not a new agent.
//
// A separate session object started through the SDK is NOT used: it would give
// the successor another agent id, and §2.4 keeps the participant the same. The
// in-place reset is the host's own primitive for exactly this ("Start a new
// session … Listeners are preserved and will continue receiving events",
// `agent-session.ts:11330`).
//
// ## Crash safety, stated exactly
//
// The order is chosen so every crash point leaves a consistent state:
//
//   1. record committed (fence active, tenure advanced, successor named);
//   2. incumbent reset to the successor transcript.
//
//   - crash between 1 and 2: the journal carries the record and the old
//     transcript. On the next start `SessionState` sees the record, so the
//     session is already a successor with the fence active; the reset had not
//     happened yet, and the `context` hook still keeps the incumbent's
//     conversation out of every request. No half tenure is visible: the
//     successor exists in the record, and the old transcript is the material
//     the barrier hides.
//   - crash during 2: OMP publishes the successor header with the same
//     temp-write + rename, so the file is the old transcript or the empty
//     successor — never a partial one.
//   - crash after 2: the successor transcript carries the record (the reset
//     copies nothing, so the record must be re-committed into the new
//     transcript — `commitRelay` runs once per transcript, see
//     `SessionState.handOver`).
//
// The reverse order is NOT used. Resetting first and recording second would
// leave, after a crash in between, a switched transcript with no record: the
// successor would run with no barrier and no tenure, which is exactly the
// "successor started, incumbent not retired" window the spec forbids.

// The entry type this adapter writes. A reader that meets another type ignores
// it: the record is ours, and nothing else may be mistaken for it.
export const RELAY_RECORD_TYPE = "herdchestra.relay";

// A relay the incumbent ASKED FOR but could not perform yet.
//
// Why this entry exists. `AgentSession.isStreaming` is
// `agent.state.isStreaming || #promptInFlightCount > 0`, and a tool call runs
// inside the turn whose prompt is in flight — so at the instant `review()`
// relays, the session is ALWAYS streaming. Refusing there (the previous
// behaviour) made a real relay impossible: the host's own liveness source says
// the session is busy, and it is.
//
// So the relay is DEFERRED: the review records its intent durably here and
// returns a receipt that says exactly that, and the successor transition runs at
// the host's next idle boundary (`agent_end` with `willContinue` absent). The
// intent is written BEFORE the receipt returns, so a crash inside the turn still
// leaves the obligation on disk; a restart that finds it completes the relay
// instead of silently forgetting it.
//
// This is an INTENT, never a tenure: nothing about retirement, the barrier or
// the successor is true until `RELAY_RECORD_TYPE` is written. A reader must
// never derive a fence, a tenure or a successor from this entry.
export const RELAY_PENDING_TYPE = "herdchestra.relay.pending";

// The ADAPTER'S OWN copy of a relay record, in `.Herdchestra/<session>.jsonl`.
//
// ## Why the adapter needs one at all
//
// Root §1.3 licenses exactly this: "需要跨越进程生命周期保存我们自己的投递、语言、
// 账本与上下文边界事实时，轻量地追加写入工作区下的 `.Herdchestra/{session标识符}
// .jsonl`". A relay record is a context-boundary fact, and on omp 18.14.2 the
// host cannot carry it: `dispose()` → `sessionManager.seal()` runs before the
// terminal `agent_end`, so no write lands there, and `getEntries()` afterwards
// reports nothing while the file still holds the entry (measured,
// final-relay-check). The host also creates no successor transcript at that
// boundary, so there is no host file to write the successor's copy into.
//
// Without an adapter-owned copy the relay could publish its record and then
// never complete: the successor's copy could not be written, so the port
// answered `RELAY_RECORD_NOT_DURABLE` and left the switch half-done forever.
//
// ## What this entry is and is not
//
// It is a FACT, not a substitute for the host's atomic publish:
//
//   - the host's publish still happens first, in the live turn, and the entry it
//     produces is still the record `recordGoverns` reads from a transcript;
//   - this entry repeats the SAME payload, byte for byte, so the two can be
//     compared and either one can rebuild the fence;
//   - it is written with the adapter's own atomic file replace
//     (`files.mjs` `writeJson`: temp + fsync + rename), never as a bare append,
//     so a reader sees the whole record or none of it.
//
// A reader therefore has two independent durable views of one fact. When they
// disagree, the transcript wins (it is the copy the host published); when the
// transcript has none — the sealed boundary — this one answers.
export const RELAY_LEDGER_TYPE = "herdchestra.relay.ledger";

// The succession notice the barrier puts in front of the successor's request
// copy (§5.3.2 step 3). It names the facts the successor must know and nothing
// about the conversation it replaced.
export const successionNotice = ({ tenure }) =>
  [
    `You are the successor in tenure ${tenure} of this work.`,
    "The previous tenure's conversation is not available to you, by design: judge the work from the workspace, not from what the previous participant believed.",
    "Its obligations, its received work, its processes and its road-bound DevOps carry over unchanged.",
  ].join(" ");

// The record's payload. Every field is a fact the successor needs and that a
// later start must be able to rebuild without the process that wrote it.
//
// `successorSessionId` names the tenure the record opened; `previousSessionFile`
// names the transcript it retired. Together they say which files the record
// governs and which one it must never be adopted in (`recordGoverns`).
//
// The transcript is recorded because the successor id cannot separate the two
// copies by itself: the successor is `newSession` ON the same manager, so the
// pre-switch payload carries the id the session still reports at commit time —
// the incumbent's own. Written with only the id, the pre-switch record matched
// its own file and a resume of the retired transcript adopted the tenure it had
// handed away (measured on the record a real relay left in
// `/tmp/hc-devops-relay5/sessions`: `successorSessionId` equals that file's
// `session.id`). The transcript path is the fact the reset really changes, and
// the host writes it into the successor's header as `parentSession`, so the
// successor's own copy names the retired file while the retired copy names
// itself.
//
// `retiredTranscript` is WHICH COPY this is, and only its writer can say:
// `true` for the pre-switch record, which sits in the file the relay retires;
// `false` for the copy written into the successor after the reset. It is not
// derived from the paths, because both copies of a relay whose switch did not
// replace the file name that same file (`recordGoverns` explains why the path
// comparison lost the fence).
export const relayPayload = ({
  tenure,
  milestone,
  successorSessionId,
  previousSessionFile,
  fenceThrough,
  notice,
  retiredTranscript = false,
}) => ({
  v: 1,
  tenure,
  milestone,
  successorSessionId,
  previousSessionFile,
  fenceThrough,
  notice,
  retiredTranscript: retiredTranscript === true,
});

// The payload field that says a record sits in the transcript it RETIRED.
//
// The pre-switch copy and the successor's copy differ only in `fenceThrough`
// and in which file they sit in, so a reader needs one fact that names the
// copy's own home. The session id cannot: the successor is `newSession` on the
// same manager, so at commit time the session still reports the id the
// incumbent had. Measured on the record a real relay left
// (`/tmp/hc-devops-relay5/sessions/…-a0c7….jsonl`): `successorSessionId` is that
// file's own `session.id`, and `previousSessionFile` is the file itself.
//
// So the pre-switch record carries `retiredTranscript: true`, and the copy
// written into the successor carries `retiredTranscript: false` (absent means
// "not retired", which is how every record written before this field existed
// reads — the successor's re-commit is the copy that survives a reset, and a
// record from an older version has no marker to say otherwise).
//
// The marker is not a filter on adoption. A record still has to GOVERN the
// transcript it is read in (`recordGoverns`), and a pre-switch record in the
// live file is exactly the crash-recovery case §2.4 第 3 步 needs: the reset
// never ran, so the fence it carries is what hides the incumbent's conversation
// from the successor.
export const RETIRED_TRANSCRIPT_FIELD = "retiredTranscript";

// Whether a record describes the transcript it was found in.
//
// Two independent facts decide it, and each answers only its own question:
//
//   1. the successor id: the successor's copy names the id the session carries
//      in that file, so it governs; a record naming some other session does not.
//      The `<id>#tenure-<n>` form (written when the host exposed no id) belongs
//      to the same session. A record with no id at all is accepted, because
//      refusing it would drop a real succession. In a real relay both copies
//      carry the same id, so this rule alone cannot separate them;
//   2. the retired-transcript marker, for the case the id cannot answer. A
//      record marked `retiredTranscript: true` says "I sit in the file I
//      retired", and it is refused when the host reports a DIFFERENT live
//      transcript — that is the reset having run, so the tenure it opened is no
//      longer this file's. A host that reports no transcript, or the same one
//      (a switch that did not replace the file), is the crash-recovery case
//      §2.4 第 3 步 requires: the record is durable, the reset never ran, and the
//      fence it carries is what hides the incumbent's conversation. An older
//      record carries no marker and is read as the successor's copy.
//
// `sessionFile` is not consulted. An earlier attempt compared it against
// `previousSessionFile`, which is the same path in BOTH copies of a relay whose
// reset kept the file — so the comparison refused the successor's own record and
// lost its fence on resume. Which file a record sits in is a fact only its
// writer knows; `payload.retiredTranscript` is how the writer says it.
export const recordGoverns = (payload, sessionId, sessionFile = null) => {
  // A retired-transcript record governs only while that transcript is still the
  // live one. A DIFFERENT live file means the reset ran, so the tenure this
  // record opened belongs to the successor's file, not to this one.
  if (
    payload?.[RETIRED_TRANSCRIPT_FIELD] === true &&
    typeof sessionFile === "string" &&
    sessionFile.length > 0 &&
    typeof payload?.previousSessionFile === "string" &&
    payload.previousSessionFile.length > 0 &&
    payload.previousSessionFile !== sessionFile
  ) {
    return false;
  }
  return (
    payload?.successorSessionId === undefined ||
    payload?.successorSessionId === null ||
    typeof sessionId !== "string" ||
    sessionId.length === 0 ||
    payload.successorSessionId === sessionId ||
    String(payload.successorSessionId).startsWith(`${sessionId}#`)
  );
};

// The fence a record yields. It is the same shape `barrierFor` reads
// (`request.mjs`), so a successor rebuilt after a restart is filtered exactly
// like one in the process that committed the record.
//
// `fenceThrough` is counted against the transcript the record was WRITTEN IN,
// and that is not always the transcript the successor reads from:
//
//   - committed, reset done: the successor's transcript starts empty, so the
//     recorded count exceeds every message it will ever hold. `barrierFor` then
//     keeps nothing but the notice and the newest operator message — which is
//     exactly right, and is why an over-large count is safe rather than a bug;
//   - committed, reset NOT done (a crash in between): the old transcript is
//     still the live one, so the recorded count is the real boundary and keeps
//     the incumbent's conversation out.
//
// So the count is carried verbatim and never re-derived. `fenceOf` therefore
// trusts the record's own number; a caller that recomputed it from the current
// transcript would get 0 after a reset and hide nothing.
export const fenceOf = (payload) => ({
  notice: payload.notice,
  successor: payload.tenure,
  fenceThrough: Number.isInteger(payload.fenceThrough) && payload.fenceThrough > 0 ? payload.fenceThrough : 0,
});

// The deferred intent's payload. It carries only what the later commit needs:
// which tenure asked, and the findings that emptied the ledger, so the deferred
// step can verify the obligation still stands instead of replaying blindly.
export const pendingPayload = ({ tenure, milestone, findings, at, record = null }) => ({
  v: 1,
  tenure,
  milestone,
  findings: Array.isArray(findings) ? findings : [],
  at: Number.isInteger(at) ? at : Date.now(),
  ...(record !== null && typeof record === "object" ? { [RECORD_FIELD]: record } : {}),
});

// Whether a decoded entry is a pending intent this version understands.
export const isPendingPayload = (data) =>
  data !== null &&
  typeof data === "object" &&
  data.v === 1 &&
  Number.isInteger(data.tenure) &&
  data.tenure >= 0 &&
  typeof data.milestone === "string";

// `pendingPayload` carries the record the turn PUBLISHED. The extra key is
// ignored by readers that do not know it, so an older intent still decodes.

// The record a pending intent already CARRIED, or null.
//
// Why the record travels inside the intent. Measured on omp 18.14.2
// (`/tmp/hc-devops-final/harness`, three independent probes): at the terminal
// `agent_end` the session manager is already sealed (`dispose()` → `seal()` sets
// `#released` and bumps `#diskEpoch`, and every commit guard is
// `!released && diskEpoch === epoch`), so NOTHING published there — atomic or
// plain — ever lands. The only writable window is the live turn, which is
// exactly where the intent is written.
//
// So the record is PUBLISHED in the turn, in its own right
// (`RELAY_RECORD_TYPE`, through the host's atomic commit), and this field
// carries a COPY of it into the intent. The copy is what lets a later process
// name the obligation's tenure without rebuilding a record from a surface that
// can no longer observe the moment it describes.
//
// A record here is a COMMIT only when it says `published: true` — and even then
// the boundary checks the journal before trusting it (`#carriedRecordEntryId`).
// A record without the flag is a draft from an older version, and it is never
// adopted as a tenure.
export const RECORD_FIELD = "record";

// The record prepared inside a pending intent, or null when the intent carries
// none (an older intent, or one written when the record could not be built).
export const preparedRecord = (payload) => {
  const record = payload?.[RECORD_FIELD];
  if (record === null || record === undefined || typeof record !== "object") return null;
  return isRelayPayload(record) ? record : null;
};

// The payload field that says the prepared record IS the record: it was
// published by the host's own atomic commit, in its own right, inside the live
// turn.
//
// Why a prepared record can be the real one. §5.3.2 wants ONE durable record
// holding retirement, the barrier and the successor. The host's atomic publish
// is what makes a record durable, and on 18.14.2 the only window in which that
// publish lands is the live turn (see `RECORD_FIELD`). So the turn publishes the
// record itself, not a draft of one, and the intent that names it is a second
// record in the same journal saying "this record has not been switched to yet".
//
// The flag is what makes that claim checkable. A reader must not treat a
// prepared-but-unpublished record (an intent written by a version that only
// carried a draft) as a tenure: only `published: true` says the host accepted
// the commit and the journal carries the entry.
export const PUBLISHED_FIELD = "published";

// The record the idle boundary publishes, derived from the one the turn already
// published.
//
// What changes and what does not:
//
//   - `retiredTranscript` becomes `false`: the copy the turn published sat in
//     the transcript the relay retires, and the idle boundary publishes it into
//     the successor's transcript. That marker is the writer's own statement
//     about which file it sits in, so it is the one field that must move;
//   - `fenceThrough` becomes 0 when the transcript was really replaced: it is a
//     boundary IN a transcript, and the successor's file starts empty;
//   - `successorSessionId` follows the id the successor answers under, when the
//     host reports one. `recordGoverns` refuses a record naming another session,
//     so the copy must name its own file's id;
//   - `previousSessionFile` does NOT move. It names the transcript the relay
//     RETIRED — the lineage a reader follows, and the value `recordGoverns`
//     compares against the live file to refuse a retired record once the reset
//     has run. Rewriting it to the successor's own path makes that comparison
//     vacuous: a pre-switch record would stop governing the very file it sits
//     in, and the crash-recovery case §2.4 第 3 步 depends on would lose its
//     fence. Both copies of one relay name the same retired file; which copy a
//     record is, is `retiredTranscript`'s job.
//
// Everything else — the tenure, the milestone, the notice — is carried
// verbatim. Rebuilding them would let the idle boundary describe a moment that
// has already passed, which is exactly what the sealed manager makes
// unobservable.
export const successorCopy = (record, { successorSessionId = null, fenceThrough = 0 } = {}) => ({
  ...record,
  successorSessionId:
    typeof successorSessionId === "string" && successorSessionId.length > 0
      ? successorSessionId
      : record.successorSessionId,
  fenceThrough: Number.isInteger(fenceThrough) && fenceThrough >= 0 ? fenceThrough : 0,
  retiredTranscript: false,
  [PUBLISHED_FIELD]: true,
});

// Whether a decoded custom entry is a relay record this adapter can trust.
// A record from a future version, or one missing a field the fence needs, is
// not trusted: rebuilding half a fence would hide the wrong messages.
export const isRelayPayload = (data) =>
  data !== null &&
  typeof data === "object" &&
  data.v === 1 &&
  Number.isInteger(data.tenure) &&
  data.tenure > 0 &&
  typeof data.milestone === "string" &&
  typeof data.successorSessionId === "string" &&
  data.successorSessionId.length > 0 &&
  Number.isInteger(data.fenceThrough) &&
  data.fenceThrough >= 0 &&
  typeof data.notice === "string" &&
  data.notice.length > 0 &&
  // A record says whether the host really published it. A record with the flag
  // unset is a DRAFT carried by an intent, and a reader that
  // treats a draft as a tenure opens a successor nobody recorded.
  (data[PUBLISHED_FIELD] === undefined || typeof data[PUBLISHED_FIELD] === "boolean");
