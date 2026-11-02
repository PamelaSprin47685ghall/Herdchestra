// Hooks (spec §2.1, §3.3, §2.3, §6.1, §6.2, §7.3).
//
// Every host hook this adapter registers lives here. Each is a small function of
// the host event plus one `RoleSession`:
//
//   tool_call             gate: the Manager may not touch a replaced tool or a
//                         face it does not own; Scope admission for the rest
//   tool_result           Scope settle (the booking happens after the call ran)
//   before_agent_start    the role's system prompt (Manager adds the director)
//   turn_start            Scope turn bookkeeping opens a model turn
//   turn_end              Scope turn bookkeeping closes it (a turn that only
//                         staged `meToo` is refused here, and only here)
//   context               Scope projection, then the Pair Hint pair
//
// The gate is the only place a forbidden call is refused. The visible tool set
// is a per-session narrowing (`setActiveTools`), which the model sees but which
// cannot stop a call that arrives anyway (spec §7.3, "越权调用仍由万象术自己的
// execute 拦截").
//
// The adapter never blocks a call for its own approval. OMP's approval modes
// include `always-ask` and `deny`; a hook that blocked every `js-*` call would
// execute programs the operator denied. The tools declare the approval tier
// they need and the host gate decides.
//
// The Scope turn boundary is the HOST's model turn, not a tool call. OMP emits
// `turn_start` when a model turn begins and `turn_end` after that turn's tool
// batch has fully run (agent-loop.ts:1256 `turn_start` before the model call,
// agent-loop.ts:789 `turn_end` with the batch's `toolResults`), so several tool
// calls racing inside one turn belong to the same turn and must not settle it.
// Closing on a tool result would book a turn before its other calls arrived.

// `before_agent_start` returns the same bytes on every request. OMP re-runs
// policy preparation when a `before_agent_start` handler calls `setActiveTools`
// (spec §7.3), so this handler never calls it, and the prompt it returns is a
// pure function of the role.

import { systemPromptFor } from "../compose/prompt/projection.mjs";
import { directorPromptFor } from "../compose/prompt/director.mjs";
import { PLAN_PROMPT } from "../compose/prompt/plan.mjs";
import { MANAGER_FORBIDDEN, ROLE, isWorkerRole } from "./host-facts.mjs";
import { toItems, selectKept, barrierFor } from "./request.mjs";
import { surfaceAllowed } from "./surface.mjs";
import { present, presentToolText } from "./tool-schema.mjs";

// The tools that run one of our program faces.
const FACE_TOOLS = Object.freeze(new Set(["js-manager", "js-worker", "js-review", "plan"]));

// Every tool this adapter registers, including the two Scope snapshots. A
// result from one of these is already presented by its own definition
// (`textResult`/`receiptResult`), so `tool_result` leaves it exactly as it is.
export const ADAPTER_TOOLS = Object.freeze(new Set([...FACE_TOOLS, "fork", "join", "negotiate", "askPreviousScope"]));

// The result of a tool the adapter did not write, with each structured text
// block presented as CUE (spec §6.3). `content` is returned only when something
// actually changed: OMP treats a returned `content` as a replacement, and a
// replacement identical to the original is still a lie about who wrote it.
//
// Images and every other block type pass through untouched, in order. A block
// the adapter cannot read as one JSON value is the host's own text and stays
// byte-for-byte.
const presentHostResult = (content) => {
  if (!Array.isArray(content)) return undefined;
  let changed = false;
  const next = content.map((block) => {
    if (block === null || typeof block !== "object" || block.type !== "text") return block;
    const rendered = presentToolText(block.text);
    if (rendered === null || rendered === block.text) return block;
    changed = true;
    return { ...block, text: rendered };
  });
  return changed ? next : undefined;
};

// What a scope admission means for this call. `admit` answers a receipt: an
// `ok: false` receipt is a refusal and blocks; anything else — including a
// receipt the Scope book does not issue — is not a refusal and must not block,
// because a hook that blocks on a malformed answer refuses calls nobody
// refused.
const refusalOf = (admission) => {
  if (admission === null || typeof admission !== "object") return null;
  if (admission.ok !== false) return null;
  const code = typeof admission.code === "string" ? admission.code : "SCOPE_REFUSED";
  return { code, message: admission.message ?? "scope refused this call" };
};

// `requestGate` answers whether the last request was actually projected (§8.1
// a). This is the only channel OMP gives us — a `context` handler that throws is
// reported and the host sends the copy it built, so for a successor that copy is
// the retired conversation. The model may still answer from it; it may not ACT
// on it.
//
// The gate is asked, not trusted. `requestRefusal` reads a durable file, and
// every way that read can fail must leave the session unable to act:
//
//   `null`        the last request projected; the gate says nothing
//   `{code,...}`  the last request did not project (or the gate itself could not
//                 be read): refuse with that verdict
//   anything else, including a THROW, a non-object or an object with no message:
//                 the gate could not answer, so the call is refused. Reading an
//                 unreadable gate as "open" is the one direction a safety fact
//                 must never fail in — that is exactly the request this gate
//                 exists to stop.
// The request a tool call belongs to.
//
// OMP's `ToolCallEvent` carries `toolName`, `toolCallId` and `input` only
// (`extensibility/extensions/types.ts` `ToolCallEventBase`): the host gives a
// hook no request id. What it cannot hide is ORDER — a tool call exists only
// for a response the provider produced, and a response only exists after its
// request was projected and sent. The adapter therefore answers with the last
// request it SETTLED, and that is the request whose response issued the call.
//
// `requestId` answers that id, or null when nothing has settled yet. `null` is a
// call nobody can attribute, and the gate refuses it (fail closed).
const requestOfCall = (requestId) => {
  if (typeof requestId !== "function") return null;
  try {
    const id = requestId();
    return typeof id === "string" && id.length > 0 ? id : null;
  } catch {
    return null;
  }
};

export const createToolCallHandler = ({ role, scopeAdmit, reviewWindow = null, requestRefusal = null, requestId = null, refusalLog = null }) => {
  return async (event) => {
    if (event === null || typeof event !== "object") return undefined;
    const name = event.toolName;
    if (typeof requestRefusal === "function") {
      // The id names the request in the refusal record; the gate itself answers
      // for the last settled one (`requestRefusal`).
      const request = requestOfCall(requestId);
      const verdict = requestGateVerdict(requestRefusal);
      if (verdict !== null) {
        // The model was stopped, so the stop leaves a record. This is the line
        // that was missing on 2026-11-01: the stream carried
        // `CONTEXT_PROJECTION_FAILED` while the durable files said `allowed:
        // true`, and nothing in `.requests.jsonl` said a call had been refused.
        // A refusal that leaves no line is the defect; the record names the
        // turn, the tool, the code and the verdict it was read from, never the
        // arguments.
        if (typeof refusalLog === "function") {
          try {
            refusalLog({ request, tool: typeof name === "string" ? name : null, reason: verdict });
          } catch {
            // The refusal stands; a diagnostic that cannot be written must not
            // become a second failure. The gate record already carries the
            // verdict that caused it.
          }
        }
        return { block: true, reason: verdict };
      }
    }
    if (role === ROLE.MANAGER && MANAGER_FORBIDDEN.includes(name)) {
      return { block: true, reason: `Manager has no ${name}: use js-manager, fork, join or review.` };
    }
    // §2.3: the visible set is not an execution grant. A `js-review` call whose
    // evaluation window is shut is refused here even when the host still
    // offers the tool, and a worker never passes the role test at all.
    if (name === "js-review" && !surfaceAllowed(role, name, { window: reviewWindow })) {
      return {
        block: true,
        reason:
          role === ROLE.MANAGER
            ? "REVIEW_WINDOW_CLOSED: this round's first review was already accepted; fork a read-only coder to read the tree"
            : `${name} is not available to this session`,
      };
    }
    // `js-review` was decided above, WITH its window. Asking again here without
    // one would apply the shut-window rule to a face whose window is open, and
    // refuse a legitimate evaluation call.
    if (name !== "js-review" && FACE_TOOLS.has(name) && !surfaceAllowed(role, name)) {
      return { block: true, reason: `${name} is not available to this session` };
    }
    if (typeof scopeAdmit !== "function") return undefined;
    const refusal = refusalOf(await scopeAdmit(event));
    if (refusal === null) return undefined;
    return { block: true, reason: `${refusal.code}: ${refusal.message}` };
  };
};

// What the request gate says about this call: `null` when it says "projected",
// otherwise the reason the call is refused. See `createToolCallHandler`.
const requestGateVerdict = (requestRefusal) => {
  let gate;
  try {
    gate = requestRefusal();
  } catch (error) {
    // A gate that cannot be read is a gate that cannot say "projected".
    const detail = typeof error?.message === "string" && error.message.length > 0 ? error.message : String(error);
    return `CONTEXT_PROJECTION_FAILED: the request gate could not be read (${detail}); this session cannot run work until a request projects`;
  }
  if (gate === null || gate === undefined) return null;
  const code = typeof gate.code === "string" && gate.code.length > 0 ? gate.code : "CONTEXT_PROJECTION_FAILED";
  const message =
    typeof gate.message === "string" && gate.message.length > 0
      ? gate.message
      : "the last request was not judged projected, so this session cannot run work until a request projects";
  return `${code}: ${message}`;
};

// `tool_result`: book the Scope annotation of the call that just ran. A refusal
// cannot undo a call that already happened, so it is reported in the result's
// extra context instead of being thrown (spec §6.2, scope AGENTS.md §5).
//
// The notice is structured data the model reads (`{kind, reason,
// previousResets, message}`), so it is presented as CUE like every other
// LLM-facing payload (spec §6.3). The refusal line stays a single sentence: it
// is one instruction with one code, not a record.
export const createToolResultHandler = ({ scopeSettle }) => {
  return async (event) => {
    // §6.3 applies to every tool's result, not only this adapter's. The host's
    // own tools are presented here, before the result becomes the message the
    // model reads. The Scope booking below runs either way: a call that ran is
    // booked whatever its text looks like.
    const presented = presentHostResult(event?.content);
    const extra = typeof scopeSettle === "function" ? await scopeExtra({ scopeSettle, event }) : undefined;
    if (presented === undefined) return extra;
    return { ...(extra ?? {}), content: presented };
  };
};

// The `additionalContext` a settled call adds, or `undefined` when it adds
// nothing. Split from the handler so the presentation above is one decision and
// the booking is another.
const scopeExtra = async ({ scopeSettle, event }) => {
  if (typeof scopeSettle !== "function") return undefined;
  const result = await scopeSettle(event);
  if (result === null || result === undefined) return undefined;
  if (result.ok === false) {
    return { additionalContext: `scope: ${result.code}: ${result.message ?? "refused"}` };
  }
  const notice = result.notice === null || result.notice === undefined ? null : result.notice;
  if (notice === null) return undefined;
  return { additionalContext: present(notice) };
};

// `before_agent_start`: replace the system prompt with the role's composition,
// and open the turn Scope books per turn. The Manager's director message is a
// separate role segment (spec §5), so it is appended here, once, outside
// `systemPromptFor`. `systemPrompt` is an array of whole segments, which is
// what OMP replaces the prompt with (extensions/types.ts:1733); the composition
// is the first and only segment, so no host text survives beside it.
// The draft path, said plainly, or the reason there is none. A Plan told
// "cannot be prepared" stops and reports; a Plan told nothing invents a path.
// An empty answer appends nothing, so a Plan whose path is not yet known keeps
// the bare prompt instead of gaining a blank heading.
const draftSegment = (draft) => {
  if (draft === null || draft === undefined) return "";
  if (draft.failure) {
    return [
      "# Draft",
      "",
      `The draft directory could not be prepared (\`${draft.failure.code}\`), so \`${draft.path}\` cannot be written.`,
      "Do not invent another path. Report this and stop; no hand-off is possible without a draft.",
    ].join("\n");
  }
  return [
    "# Draft",
    "",
    `Your draft is \`${draft.path}\` — under the workspace-root \`plans/\` directory, never under \`.Herdchestra\`.`,
    "That is the path `cat` and `write` name. It is your only output: apart from it and the operator's attachments, you are read-only.",
  ].join("\n");
};

// A deferred relay that could not run is stated to the model, once, in the same
// place a Plan learns its draft path. Silence was the defect: the in-turn
// receipt said "deferred", and nothing later ever contradicted it.
const relayFailureSegment = (failure) =>
  failure === null || failure === undefined
    ? ""
    : [
        "# Relay",
        "",
        `A relay was deferred and could not be performed (\`${failure.cause}\`): ${failure.detail}`,
        "The work is still under the current tenure. Report this; do not claim the relay happened.",
      ].join("\n");

export const createBeforeAgentStartHandler = ({
  role,
  onTurn = null,
  // Plan's draft, resolved per turn: `{path}` when the directory was prepared,
  // `{path, failure: {code, detail}}` when it could not be. Spec §6 says the
  // system names the draft path to the model; without this the model has a
  // `write` member and no file to name, so it would guess a path, miss the
  // directory, and meet an `ENOENT` it cannot diagnose.
  draft = null,
  // Why the last deferred relay could not run, or null. Resolved per turn: the
  // failure is a fact of this session's own state.
  relayFailure = null,
}) => {
  return async () => {
    if (typeof onTurn === "function") onTurn();
    // No `setActiveTools` here, ever: root §7.3 makes it re-run policy
    // preparation. §2.3's evaluation round opens at `turn_start` instead.
    // §5: Plan is outside the three-file projection, and `systemPromptFor`
    // refuses it by design. Its prompt is its own role segment.
    const base = role === ROLE.PLAN ? PLAN_PROMPT : systemPromptFor(role);
    const segment = role === ROLE.PLAN && typeof draft === "function" ? draftSegment(draft()) : "";
    const withDraft = segment === "" ? base : `${base}\n\n${segment}`;
    const relayed = typeof relayFailure === "function" ? relayFailureSegment(relayFailure()) : "";
    const composed = relayed === "" ? withDraft : `${withDraft}\n\n${relayed}`;
    const prompt = role === ROLE.MANAGER ? directorPromptFor(composed) : composed;
    return { systemPrompt: [prompt] };
  };
};

// A failure's name, said plainly, for the model. A hook cannot refuse a request
// (`runner.ts` `#runHandlerWithTimeout` reports a thrown handler and keeps the
// unmodified request), so a failure that is not written into the copy reaches
// nobody: the model would read a request that silently lost what the failure
// refused to project.
const failureOf = (error) => {
  const code = typeof error?.code === "string" && error.code.length > 0 ? error.code : null;
  const name = code ?? (typeof error?.name === "string" && error.name.length > 0 ? error.name : "Error");
  // The error's own attached facts travel beside the code and message: a
  // `PREFIX_DRIFT` carries its first divergence there, and the request
  // diagnostic must be able to name it without parsing the message.
  return {
    code: name,
    detail: String(error?.message ?? error),
    ...(error?.details === undefined ? {} : { details: error.details }),
  };
};

// The same list when nothing changed, a new array otherwise. `changed` is
// identity, not deep equality: every projection here returns the host's own
// message objects or copies of them.
const replaced = (previous, next) =>
  next.length === previous.length && next.every((message, index) => message === previous[index]);

// The notice that tells the model a projection failed. It is an operator-role
// message because that is the role every host reads as the user's own input; it
// names the projection, the code and the reason, and states the two things the
// model must not do — claim the failed projection happened, and expect the
// missing content to arrive later.
// The notice a request carries while a relay RECORD exists but the transcript
// switch has not run yet.
//
// Why it is needed at all. The record is published inside the turn that decided
// the relay (the only writable window on this host, AGENTS.md §8.1), so from
// that moment the ledger says a successor tenure exists while the live requests
// are still the incumbent's. Without a word about it the model restarts from
// nothing: measured on 18.14.2, a Manager that had already forked read a request
// with no history of its own and forked again — 116 times in one turn, and the
// run never ended.
//
// The notice does NOT carry the succession marker: it is not the barrier's copy,
// and it must not be deleted as one. It says the one fact the request cannot
// show — the relay is decided, the switch is pending — and nothing else.
export const relayOwedNotice = (record) =>
  [
    `[herdchestra] A relay is decided: the record for tenure ${record.tenure} is durable, and the transcript switch is pending.`,
    "Your conversation so far is NOT in this request, by design, and it will not come back. Do not restart the work from scratch and do not fork or review again to find out what you were doing:",
    "the work already started is on disk and in the workspace. Finish this turn with a short answer and stop; the switch runs at the next idle boundary.",
  ].join(" ");

const CONSEQUENCE = Object.freeze({
  request:
    "This request therefore carries only the succession notice and the operator's latest message, and no tool call can run until a request projects.",
  barrier:
    "This request therefore carries only the succession notice and the operator's latest message.",
  scope:
    "This request therefore carries only the operator's latest message: the earlier conversation was NOT sent, because the retention rule that would decide what to keep could not run.",
  "pair hint": "This request therefore carries no Pair Hint.",
});

const projectionFailureNotice = ({ stage, failure }) =>
  [
    `[herdchestra] the ${stage} projection of this request failed (\`${failure.code}\`): ${failure.detail}`,
    CONSEQUENCE[stage] ?? "This request is incomplete.",
    "Do not claim the failed projection happened. Report this failure; it is not something to work around.",
  ].join(" ");

// The one notice a request carries when the gate's own record could not be
// written. It names the missing diagnostic, because the standing rule is that
// every refusal leaves one: a reader of `.requests.jsonl` that finds nothing
// about this request must be able to learn WHY from the request itself.
const GATE_UNRECORDED_NOTICE =
  "[herdchestra] this request's verdict could not be written to the session's `.requests.jsonl` (the record file and its fallback both refused it). " +
  "The gate file still carries the verdict, so this session's tool calls are refused correctly, but no diagnostic line explains it. " +
  "Do not claim the request was projected; report this failure.";

// The last thing the operator said, in the copy the host built. It is the only
// message a failed projection may still send, because it is the one the request
// exists to answer. `null` when the request has no operator message at all.
//
// `from` is the first index a message may come from. A fence passes its own
// `fenceThrough` here, because a user message BEFORE that point was answered by
// the incumbent: it belongs to the retired tenure, and sending it to the
// successor would be the leak the barrier exists to prevent — with the added
// harm of labelling the incumbent's question as the successor's.
const lastOperatorMessage = (messages, from = 0) => {
  const start = Number.isInteger(from) && from > 0 ? from : 0;
  const index = messages.findLastIndex((message, at) => at >= start && message?.role === "user");
  return index < 0 ? null : messages[index];
};

// The copy a FAILED Scope projection leaves: the operator's latest message and
// nothing else.
//
// Why not the request as it stands. Scope's projection is what keeps old work
// out of the provider request (§6.2). A request whose projection failed carries
// every message the host still holds — the retired tenure's conversation after a
// restart, or turns the retention rule would have dropped — so "send it anyway
// and say so" is the leak, not a smaller failure. The only copy that cannot leak
// is the smallest one the request still needs: the operator's own words.
//
// A request with no operator message has no such copy. It is answered with the
// failure notice ALONE: no message of the unprojected history goes out, and the
// gate stays shut, so the model cannot act on a request it never really saw.

// `context`: rebuild the request copy. The relay barrier comes first — it
// decides which messages exist at all for a successor (spec §5.3.2 step 3) —
// then Scope (it decides which of those survive), then the Pair Hint pair (it is
// placed last). All three are projections; the host's stored history is never
// touched.
//
// ## A projection failure must not become a request nobody checked
//
// OMP gives a `context` handler no way to refuse a request: a throw is reported
// to the operator and the host sends the copy it built (measured on 18.14.2,
// AGENTS.md §8.1 a). The handler therefore never lets a failure out. It decides
// the SAFEST copy it can defend and writes a durable verdict the tool gate
// reads:
//
//   - the barrier fails: the successor must never read the retired
//     conversation, so the copy is the succession notice plus the operator
//     message the fence already counted as the successor's. Scope does not run
//     (there is no tenure-local boundary to apply to a first request);
//   - Scope fails or refuses: the copy is the operator's latest message alone,
//     plus a notice. The unpruned request is NOT sent: it is the whole history
//     Scope was asked to prune, which is exactly what a failed retention rule
//     must not leak. A request with no operator message is replaced by the
//     failure notice alone — nothing of the unprojected history goes out;
//   - Pair Hint fails or refuses: the copy is the barriered, Scope-projected
//     request as it stands — the barrier and the retention are KEPT, and only
//     the language hint is missing.
//
// Every failure is named in the copy, and every failure leaves the gate shut:
// `requestGate.settle(failure)` makes the next `tool_call` refuse, so the model
// can report the failure but cannot act on a request that was not projected.
// The gate opens only when a request projects completely; the next request runs
// every projection again from scratch.
//
// ## The gate itself can fail
//
// The gate is a file, and a file can be unwritable or unreadable. A gate that
// cannot say "projected" must not become a request nobody stops, so the two I/O
// directions answer differently:
//
//   - the OPEN fails (`requestGate.open` answers `false`, throws, or answers a
//     promise that rejects): the durable verdict cannot be moved to "refused",
//     so the handler holds a fail-closed verdict IN MEMORY (`gateHeld`) that
//     every `tool_call` of this session reads until a later request settles a
//     real verdict. The copy is the same smallest one a projection failure
//     leaves — nothing of the unprojected history goes out, because this request
//     was never projected either;
//   - the SETTLE fails (`requestGate.settle` throws, answers `false`, or answers
//     a promise that rejects): the durable file still says "refused" (that is
//     what the open wrote), but the handler cannot prove it, so the same
//     in-memory verdict is held. The copy has already been decided and is sent.
//
// Nothing here throws. A throw is NOT a way to stop a request on OMP: the host
// reports the handler failure and sends the copy it built anyway (`runner.ts`
// `emitError`, AGENTS.md §8.1 a), so a throw would send exactly the unprojected
// request this function exists to prevent. Every failure therefore leaves a
// copy this handler decided, and the gate is what stops the work.
export const createContextHandler = ({
  scopeProject,
  pairHintProject,
  barrier = null,
  requestGate = null,
  // The relay record whose switch has not run yet, or null. Read per request:
  // the state changes at the switch, not at the publish.
  relayOwed = null,
  // The durable request diagnostic (see `drift-log.mjs`), or null. It records
  // what this hook received and what it decided, as shapes and digests only, so
  // a later reader can answer "why did this request drift" without the
  // conversation and without the host's own database.
  requestLog = null,
  // Whether this request is a SIDE-CHANNEL turn (title/card/compaction probe)
  // rather than the conversation. OMP fires those through this same hook while
  // the main turn streams (measured 2026-11-01, session 01a19547), and their
  // copy extends the last real request — so a failure in one judged the session
  // and the next real request was refused with PREFIX_DRIFT. See
  // `isSideTurn` below for what a side turn may and may not do.
  sideTurn = null,
  // Told about every change of the durable gate verdict this request makes,
  // with the record it left. `null` is "no gate here".
  //
  // It exists for the one window a request record cannot close on its own: the
  // `open` runs BEFORE the request record is written, and the `settle` runs
  // before it too. A process that dies, or a handler the host abandons, between
  // the gate write and the record write leaves a `refused` verdict that no
  // `.requests.jsonl` line explains — which is exactly the state measured on
  // 2026-11-01 (turn 1, 54 tool results, 233 s): the stream said
  // `CONTEXT_PROJECTION_FAILED`, every gate file said `allowed: true`, and no
  // record carried the refusal. The gate is the durable fact the tools refuse
  // on, so the record of its every change is what has to be durable first.
  onGate = null,
}) => {
  const gate = requestGate ?? {};
  // The in-memory half of the gate. `null` is "the durable file is the whole
  // truth"; anything else is a refusal this session holds, with the turn it
  // speaks for, until a request of that turn settles a verdict the file really
  // carries. It exists because a gate that
  // cannot be written must still refuse: otherwise a full or read-only state
  // directory would leave the session with no gate at all, which is the one
  // direction a safety fact must never fail in.
  let gateHeld = null;
  // A side turn is the host's own UI request, not the operator's work. Two
  // rules follow, and neither weakens the prefix check for the conversation:
  //
  //   - its copy is never reduced by a failure fallback. The fallbacks below
  //     exist because an unprojected CONVERSATION must not be sent; a side turn
  //     carries no new conversation, and cutting it would only corrupt a title.
  //   - its failure never settles the durable gate. The gate stops TOOL calls,
  //     and a title request has none; shutting it because a UI request failed is
  //     what locked a real session out of its own tools.
  // The id of the request being projected. Minted once per `context` call, so
  // the open and the settle speak for the SAME request even when another
  // request's projection interleaves with this one (`session-state.mjs`
  // `requestRefusal`).
  let requestSeq = 0;
  const nextRequestId = () => {
    requestSeq += 1;
    return `req-${requestSeq}-${Date.now().toString(36)}`;
  };
  const openGate = (id) => {
    if (typeof gate.open !== "function") return null;
    // `null` is "this host keeps no gate" (a test or a host without the file).
    // Only an explicit `false` means the gate could not be written.
    try {
      const written = gate.open(id);
      return written === undefined ? null : written;
    } catch {
      return false;
    }
  };
  const settleGate = (failure, id) => {
    if (typeof gate.settle !== "function") return null;
    try {
      const written = gate.settle(failure, id);
      return written === undefined ? null : written;
    } catch {
      return false;
    }
  };
  // Every change of the durable verdict is reported to the caller, which writes
  // it as a record before the request that made it can be abandoned. The answer
  // says whether that record landed; a record that cannot land is stated in the
  // refusal text instead (`gateUnrecorded`).
  //
  // `phase` is the gate's own moment: "settle" after the projections decided
  // AND the verdict changed, "unwritable" when the file could not hold it. An
  // unchanged verdict writes no line (root §1.1 item 1). The request record is
  // separate and is written only for a failure.
  const tellGate = (phase, verdict) => {
    if (typeof onGate !== "function") return true;
    try {
      const outcome = onGate({ phase, verdict });
      return outcome === undefined || outcome === null ? true : outcome !== false;
    } catch {
      return false;
    }
  };
  const handler = async (event) => {
    const messages = Array.isArray(event?.messages) ? event.messages : [];
    // The host marks the messages it adds for one request, so the predicate is
    // asked about THIS request, never about remembered state: two requests can
    // interleave (a side turn fires while the main turn streams).
    const side = typeof sideTurn === "function" ? sideTurn(messages) === true : false;
    if (messages.length === 0) return undefined;
    const barrierNow = typeof barrier === "function" ? barrier() : null;
    const fenced = barrierNow !== null && barrierNow !== undefined;
    // The first message a successor may read: everything before `fenceThrough`
    // was on screen under the incumbent. Both fallbacks below use it, so a
    // fallback copy can never carry the retired tenure's question.
    const through = fenced && Number.isInteger(barrierNow.fenceThrough) && barrierNow.fenceThrough > 0 ? barrierNow.fenceThrough : 0;
    // From here the request is unprojected until this function says otherwise.
    // The open is written first, so a crash between the two leaves "refused" on
    // disk; and when it does not land, the refusal is held in memory instead.
    //
    // A SIDE TURN does not open it. The gate stops TOOL calls, and the host
    // fires a title/card request through this same hook while the main turn is
    // streaming (`isSideChannelRequest`). Opening the gate for one writes
    // "refused, pending" over the verdict the conversation's last request
    // settled, and a crash before this side turn settles leaves the operator's
    // session gated by a UI request that was never theirs. `null` is the
    // handler's "no gate here", so nothing below can read the open as a failure.
    // A side turn is the host's own UI request: it is not a turn of this
    // tenure, so it neither opens nor settles a verdict. The id is minted here,
    // once, and carried through both calls, so the verdict this request writes
    // can only ever be its own request's.
    const gateRequest = side ? null : nextRequestId();
    const gateOpenable = side ? null : await openGate(gateRequest);
    const notices = [];
    // The open writes NOTHING (root §1.1 item 1). The file carries the last
    // SETTLED verdict, which is what a tool call is judged by; a request that is
    // still projecting cannot have issued one. Writing "pending" here and
    // "allowed" at the settle was two whole-file rewrites per request for a
    // verdict that usually did not change. `openGate` still mints the request id
    // and, on a host that keeps no gate, answers `null`.
    let gateUnrecorded = false;
    const gateFailure =
      gateOpenable === false
        ? { stage: "request", code: "REQUEST_GATE_UNWRITABLE", detail: "the request gate could not be opened" }
        : null;
    if (gateFailure !== null) {
      // Nothing can be written, so the refusal lives in this object until a
      // request settles a verdict the file really carries.
      gateHeld = { ...gateFailure, request: gateRequest };
      // The refusal is held in memory, so this record is the only durable trace
      // it can leave: write it before the copy that names it is sent.
      if (!tellGate("unwritable", gateHeld)) gateUnrecorded = true;
      notices.push(projectionFailureNotice({ stage: "request", failure: gateFailure }));
    }
    let failure = gateFailure;
    const fail = (stage, detail) => {
      if (failure === null) failure = { stage, ...detail };
    };
    let current = messages;
    let barriered = false;
    if (fenced) {
      try {
        current = barrierFor(messages, barrierNow);
        barriered = true;
      } catch (error) {
        const detail = failureOf(error);
        current = fencedFallbackCopy(messages, barrierNow, through);
        fail("barrier", detail);
        notices.push(projectionFailureNotice({ stage: "barrier", failure: detail }));
      }
    }
    // A gate that could not be written leaves the request unprojected whatever
    // the projections below answer: "refused" was never recorded, and a copy
    // built by projections nobody could judge must not be the copy that goes
    // out. So the copy is the one a failed barrier leaves — the succession
    // notice the fence carries and the operator message the fence already
    // counted as the successor's, or the operator's latest message alone when
    // there is no fence (the same smallest copy a failed Scope projection
    // leaves). Scope and the Pair Hint are not consulted: their answers would
    // decide a copy the tool gate cannot promise the model is stopped from
    // acting on.
    if (gateFailure !== null) {
      current = fenced ? fencedFallbackCopy(messages, barrierNow, through) : failedScopeCopy(messages, gateFailure, through);
    }
    // The barrier and Scope answer different questions, and the barrier's answer
    // wins. §5.3.2 step 3 guarantees what a successor SEES: the operator's own
    // input and the succession notice. §6.2 retention governs what a tenure keeps
    // of its OWN conversation — and the successor has no settled Scope boundary
    // yet (§6.2 cold start: the model must `resetScope`), so Scope reads the
    // whole request as "after the boundary" and would prune exactly the messages
    // the barrier just decided to preserve.
    //
    // So while a fence is active the barriered request is final: Scope is not
    // consulted, because there is no tenure-local retention to apply to a
    // request that belongs to the successor's first turn.
    // What Scope itself decided, when it ran. `null` means it did not run, and
    // then no Scope replacement can be claimed.
    let scopeAnswer = null;
    let scopeGiven = null;
    let scopeAnswered = null;
    // A side turn is not a turn of this tenure: it spends no retention and
    // opens/closes no Scope turn (the turn hooks fire for the main turn only,
    // so booking the side request here would book a turn that never happened).
    if (!side && gateFailure === null && !fenced && typeof scopeProject === "function") {
      try {
        const scopeItems = toItems(current);
        scopeGiven = scopeItems;
        const projected = await scopeProject({ messages: current, items: scopeItems });
        if (projected !== null && projected !== undefined && projected.ok !== false) {
          // No fence here, so `current` is exactly `messages`: Scope's answer is
          // the request copy, and `selectKept` (an identity filter over the
          // original objects) is what applies it.
          //
          // The whole request is filtered, not only `current`: the identity
          // filter decides by message object, so a Scope that keeps nothing of
          // `current` cannot drop a message `current` never showed it — the
          // ProjectSurface projects the tenure's Scope over this same list, and
          // a copy that lost a message Scope kept would stop being the
          // projection's copy. The filter keeps every message that survived and
          // every tool call of a surviving message, so applying it to the wider
          // list cannot add a message the projection removed.
          current = selectKept(messages, projected.messages ?? []);
          // The projection's OWN answer, before this filter: the Pair Hint may
          // only re-base the chain for a replacement SCOPE made, never for one
          // this filter made by dropping half a pair.
          scopeAnswered = projected.messages ?? [];
          scopeAnswer = (projected.messages ?? [])
            .map((item) => item?.source)
            .filter((message) => message !== undefined);
        } else if (projected !== null && projected !== undefined && projected.ok === false) {
          const detail = {
            code: typeof projected.code === "string" ? projected.code : "SCOPE_PROJECTION_REFUSED",
            detail: String(projected.message ?? "the Scope book refused the projection"),
          };
          fail("scope", detail);
          current = side ? current : failedScopeCopy(current, detail, through);
          notices.push(projectionFailureNotice({ stage: "scope", failure: detail }));
        }
      } catch (error) {
        const detail = failureOf(error);
        fail("scope", detail);
        current = side ? current : failedScopeCopy(current, detail, through);
        notices.push(projectionFailureNotice({ stage: "scope", failure: detail }));
      }
    }
    // The pair hint runs only over a copy that is still the host's own request.
    // After a Scope failure `current` is already the smallest copy the handler
    // decided; placing a pair into that copy and then judging it by its length
    // would read "Scope reduced it" from a reduction Scope never made.
    if (gateFailure === null && failure === null && typeof pairHintProject === "function") {
      // The copy the pair was placed into. Kept so a failure can decide how much
      // of it may still go out (see the fallback below).
      const beforePair = current;
      try {
        // `incoming` is the copy this request held before Scope ran: it is what
        // lets the hint tell a Scope cut it may re-anchor from a rewrite nobody
        // authorised, which it must refuse.
        const projected = await pairHintProject(current, {
          barriered,
          incoming: messages,
          givenItems: scopeGiven,
          answeredItems: scopeAnswered,
          projectedBy: scopeAnswer,
          filtered: current,
          // The fact is computed from the messages the host handed over, not
          // from `current`: the barrier may have stripped the host's own
          // developer/agent reminder, and re-deriving it from the fenced copy
          // would treat a title request as a main turn (the 2026-11-01 drift).
          ephemeral: side,
        });
        if (Array.isArray(projected)) current = projected;
        else {
          const detail = { code: "PAIR_HINT_NO_COPY", detail: "the projection answered no message list" };
          fail("pair hint", detail);
          current = side ? beforePair : pairHintFailureCopy({ fenced, barriered, kept: beforePair, messages, through });
          notices.push(projectionFailureNotice({ stage: "pair hint", failure: detail }));
        }
      } catch (error) {
        const detail = failureOf(error);
        fail("pair hint", detail);
        current = side ? beforePair : pairHintFailureCopy({ fenced, barriered, kept: beforePair, messages, through });
        notices.push(projectionFailureNotice({ stage: "pair hint", failure: detail }));
      }
    }
    // The verdict first: a copy that is about to be sent must already be
    // judged, so a crash between the two leaves the refusal in place.
    // A side turn neither opens nor settles the gate: it has no tool calls to
    // refuse, and its verdict must not speak for the conversation.
    //
    // The gate writes only when the verdict CHANGES (root §1.1 item 1). The
    // diagnostic line follows the same rule: an unchanged "allowed" is not news,
    // and a refusal that repeats is already on disk. The line is written after
    // the verdict lands, because it describes a verdict a reader can find.
    const settled = side ? null : await settleGate(failure, gateRequest);
    const gateWriteFailed = settled === false || (settled !== null && settled?.ok === false);
    if (gateWriteFailed) {
      // The verdict could not be recorded, so it cannot be relied on: the file
      // still says whatever the last request settled, and the memory holds the
      // refusal, until a later request settles one the file really carries.
      gateHeld = { request: gateRequest, stage: "request", code: "REQUEST_GATE_UNWRITABLE", detail: "the request gate verdict could not be written" };
      if (!tellGate("unwritable", gateHeld)) gateUnrecorded = true;
      // The gate is ONE failure, not two. When the open already failed, the
      // request is unprojected for that reason and the verdict failure is the
      // same fact seen twice; reporting it twice would read as two independent
      // faults and bury the copy it describes under duplicate text.
      // `projectionFailureNotice` returns text, so the check is on the text the
      // request-gate failure produces; a notice is only ever this shape.
      const alreadyReported = notices.some(
        (notice) => typeof notice === "string" && notice.startsWith("[herdchestra] the request projection"),
      );
      if (!alreadyReported) {
        notices.push(
          projectionFailureNotice({ stage: "request", failure: { code: "REQUEST_GATE_UNWRITABLE", detail: gateHeld.detail } }),
        );
      }
    } else if (settled !== null) {
      // A verdict the file really carries replaces the held one, whichever way
      // it went: the next request reads the file, not this memory.
      //
      // This is the other half of one rule, and neither half survives alone:
      //
      //   the held verdict outranks the file because it is NEWER, not because
      //   it is stricter — and a real verdict clears it for the same reason.
      //
      // A reader who keeps only the first half gets a session refused forever
      // by a stale memory of a disk that has since recovered; the model would
      // be locked out of work by a write that failed once. A reader who keeps
      // only the second half opens the gate on the stale file the held refusal
      // was there to outrank. `a held refusal outranks a stale on-disk
      // 'allowed' verdict` covers the first direction; a clean request after a
      // failed one covers this one.
      gateHeld = null;
      // `changed` is the gate's own answer; `true` is the older shape (a bare
      // success) and is treated as a change so a host that answers less still
      // records the verdict.
      if (settled === true || settled?.changed === true) {
        const verdict =
          failure === null
            ? { request: gateRequest, allowed: true, stage: null, code: null }
            : { request: gateRequest, allowed: false, stage: failure.stage, code: failure.code, detail: failure.detail };
        if (!tellGate("settle", verdict)) gateUnrecorded = true;
      }
    }
    // The notices go after the copy, in the order the failures happened. They
    // are messages the host does not store, so the next request does not carry
    // them — a failure is reported once, for the request it affected.
    //
    // A notice never carries the succession marker. That marker means "this
    // message IS the barrier's copy" — the barrier drops a marked message from
    // the next request, and only the notice may be marked. A failure notice is
    // not the succession notice: marking it would make the next request treat a
    // failure report as the barrier's own copy and delete it, and it would tell
    // the model a second, false thing about the succession.
    // A relay whose switch is still pending is stated once, after any failure
    // notices: the model is told the history it cannot see is gone on purpose.
    if (relayOwed !== null && typeof relayOwed === "function") {
      const owed = relayOwed();
      if (owed !== null && owed !== undefined) notices.push(relayOwedNotice(owed));
    }
    // A verdict nobody could record is stated in the copy, because the copy is
    // the only channel left once both files refused: the model must know that
    // the session's next refusal may not be explained by `.requests.jsonl`.
    if (gateUnrecorded) notices.push(GATE_UNRECORDED_NOTICE);
    const result = notices.length === 0 ? current : [...current, ...notices.map((text) => ({ role: "user", content: text }))];
    // The request record is written ONLY for a failure (root §1.1 item 1): a
    // clean request leaves no line. It states the request's length, its root
    // digest, the first divergence the prefix rule reported, and (capped) the
    // messages the copy dropped. A reader holding a `refused` record from the
    // Pair Hint journal and this line can name the drifted index in both.
    // Shapes and digests only — never the conversation.
    //
    // `current` is the copy before the notices: the notices are messages the
    // host does not store, and recording them as "presented" would make the
    // next request look shorter than the one before it for a reason that is
    // not a drift.
    if (typeof requestLog === "function" && failure !== null) {
      // `incoming` stays the copy the host handed over — a fallback copy must
      // not be recorded as "the request".
      //
      // The writer reports where the line landed (`drift-log.mjs`): `ok: false`
      // means the session's own file refused it and a fallback file holds it,
      // with the failure named. A writer that THROWS could not report even that,
      // so it is called once more with the failure attached — the one retry this
      // path makes, and the reason a broken disk still leaves a named line.
      const entry = { phase: "settled", incoming: messages, presented: current, failure };
      try {
        const outcome = requestLog(entry);
        if (outcome !== null && typeof outcome === "object" && outcome.ok === false) {
          // The fallback line already carries the failure; nothing more to do.
          // A writer that answers `false` (the older shape) gets one retry.
          if (outcome.failure === undefined) requestLog({ ...entry, writeFailure: null });
        } else if (outcome === false) {
          requestLog({ ...entry, writeFailure: null });
        }
      } catch (error) {
        const writeFailure = {
          code: typeof error?.code === "string" ? error.code : String(error?.name ?? "EIO"),
          detail: String(error?.message ?? error),
        };
        try {
          requestLog({ ...entry, writeFailure });
        } catch (second) {
          // A diagnostic that cannot be written is lost; the request is not.
          // Named so a caller reading the transcript can tell the two apart.
          if (process.env.HERDCHESTRA_DEBUG === "1") {
            console.error("[herd] request record failed", second?.message ?? String(second));
          }
        }
      }
    }
    if (replaced(messages, result)) return undefined;
    return { messages: result };
  };
  // The gate the `tool_call` hook asks, answered in the shape it expects: `null`
  // when the last request projected, otherwise `{code, message}`.
  //
  // The held refusal comes first: it is newer than anything the file can say,
  // and it is the only refusal a gate that cannot be written has. Otherwise the
  // durable verdict decides, and `gate.read` answers it. A read that throws is a
  // refusal, never "projected" — the file exists, it just cannot be believed.
  const requestRefusal = () => {
    if (gateHeld !== null) {
      return {
        code: "CONTEXT_PROJECTION_FAILED",
        message: `the ${gateHeld.stage} projection of request ${String(gateHeld.request)} failed (${gateHeld.code}: ${gateHeld.detail}); this session cannot run work until a request projects`,
      };
    }
    if (typeof gate.read !== "function") return null;
    try {
      // The gate answers for the last SETTLED request (`session-state.mjs`
      // `requestRefusal`): `null` when it projected, otherwise the refusal. A
      // session with no settled request is refused there, never guessed here.
      return gate.read();
    } catch {
      return {
        code: "CONTEXT_PROJECTION_FAILED",
        message:
          "the request gate could not be read (REQUEST_GATE_UNREADABLE); this session cannot run work until a request projects",
      };
    }
  };
  return Object.assign(handler, { requestRefusal });
};

// The copy a failed BARRIER leaves: the succession notice the fence carries and
// the operator message the fence already counted as the successor's.
//
// The barrier is the security boundary: what it keeps, the successor may read.
// Falling back to the unfenced request would hand it the retired conversation,
// so the fallback is the smallest copy the barrier's own contract allows.
//
// A fence that carries no usable notice cannot say who the successor is, so the
// copy is the operator's message alone. Inventing a notice would put words in
// the system's mouth; keeping the unbarriered request would hand over the
// retired conversation.
const fencedFallbackCopy = (messages, fence, from = 0) => {
  const usableNotice = typeof fence?.notice === "string" && fence.notice.length > 0;
  const latest = lastOperatorMessage(messages, from);
  return [
    ...(usableNotice ? [{ role: "user", content: [{ type: "text", text: fence.notice }], herdchestraSuccession: true }] : []),
    ...(latest === null ? [] : [latest]),
  ];
};

// The copy a FAILED Pair Hint projection leaves.
//
// Two cases, and only one of them may keep the request as it stands:
//
//   - a fence is active, or the copy already came from the barrier: the
//     barriered (and, when Scope ran, Scope-projected) request is what §5.3.2
//     step 3 and §6.2 allow, and the pair is the only thing missing. Keep it.
//   - there is NO fence: nothing has bounded this request yet. Scope was the
//     only retention filter, and it does not settle a boundary by itself — a
//     session with no boundary keeps everything, which is the whole history the
//     host still holds. Keeping it because the PAIR failed would ship exactly
//     what a failed Scope projection is forbidden to ship, for a failure that
//     has nothing to do with what may be shown.
//
// "Bounded" is not "Scope was configured". A request that never reached Scope
// (`scopeProject` absent) is unbounded by construction, and the pair hint can
// still run over it — `failure` stays `null` there, because no projection
// failed. That is why the test below asks whether Scope actually SHORTENED the
// request, not whether Scope ran.
//
// Measured live (2026-11-01, omp 18.14.2, DevOps probe A): a corrupt Pair Hint
// journal on an unfenced session settled `allowed:false` for the pair hint and
// still sent fifteen messages of the previous tenure to the provider. The gate
// did its job; the copy did not. So an unfenced pair-hint failure answers the
// same smallest copy a failed Scope projection answers: the operator's latest
// message, and nothing else. `kept` is the Scope answer, and it is used only
// when Scope actually reduced the request — an empty or unchanged `kept` says
// nothing about retention, so the fallback takes the operator's message alone.
//
// `barriered` implies `fenced` (the barrier runs only under a fence), so that
// half of the first test is defensive, not a second case. It is kept because
// the two facts are passed separately and a future change could make them
// disagree; the copy that came from the barrier is a boundary whatever the
// fence says now.
const pairHintFailureCopy = ({ fenced, barriered, kept, messages, through }) => {
  if (fenced || barriered) return kept;
  if (Array.isArray(kept) && kept.length > 0 && kept.length < messages.length) return kept;
  return failedScopeCopy(messages, null, through);
};

// The copy a failed Scope projection sends, or `[]` when the request carries no
// operator message at all.
//
// `from` is the fence's `fenceThrough` when one is active. It is 0 otherwise,
// because without a fence every user message in the copy is this tenure's own.
const failedScopeCopy = (messages, failure, from = 0) => {
  const latest = lastOperatorMessage(messages, from);
  // No operator message means there is nothing the request exists to answer, and
  // no copy of the unprojected history may go out. The caller appends the
  // failure notice, which is then the whole request.
  if (latest === null) return [];
  return [latest];
};

// `turn_start`: a new model turn. Scope opens the turn it will book against.
export const createTurnStartHandler = ({ scopeOpenTurn }) => {
  return async (event) => {
    if (typeof scopeOpenTurn !== "function") return undefined;
    await scopeOpenTurn(event);
    return undefined;
  };
};

// `turn_end`: the model turn is over, after its whole tool batch. Only now may
// Scope refuse a turn that staged `meToo` and nothing else; the refusal is
// reported to the model through the result's extra context, because the turn
// already happened and cannot be blocked retroactively (spec §6.2).
export const createTurnEndHandler = ({
  scopeCloseTurn,
  noteTurnEnd = null,
  closeRequestTurn = null,
  // Root §1.1 item 2: a real operator interrupt at a user-facing session
  // cancels the whole descendant subtree. The adapter supplies the walk; this
  // hook only hands it the host's own ending message, at the moment it exists.
  onOperatorInterrupt = null,
}) => {
  return async (event) => {
    // The request gate's turn is final from here: its verdict may no longer be
    // moved by a later request (`requestRefusal`). Done first, so a slow Scope
    // close cannot leave the turn open while the next one's calls are judged.
    if (typeof closeRequestTurn === "function") closeRequestTurn(event);
    // The turn's own message is the one fact the nudge rule needs and `agent_end`
    // does not carry: an operator interrupt ends the turn with
    // `stopReason: "aborted"` and `errorMessage: "Interrupted by user"`
    // (`agent-session.ts:11240`), and §2.2's nudge must not answer it. The record
    // is handed on before Scope closes the turn, so no path can lose it.
    if (typeof noteTurnEnd === "function") noteTurnEnd(event?.message ?? null);
    // The interrupt is propagated HERE, before Scope closes the turn: the
    // message is this event's own, and the adapter's guard (an S1 ask, a
    // replaced present, a relay) is what decides whether it is the operator.
    if (typeof onOperatorInterrupt === "function") await onOperatorInterrupt(event?.message ?? null);
    if (typeof scopeCloseTurn !== "function") return undefined;
    // Keep the `await`. `ScopeHooks.closeTurn` answers a Promise, and a caller
    // that compares the Promise itself (`refusal === null`, `refusal.ok`) never
    // sees the refusal — it silently reports "no refusal" instead. All three
    // hosts await, so the refusal is delivered in each of them.
    const refusal = await scopeCloseTurn(event);
    if (refusal === null || refusal === undefined) return undefined;
    if (refusal.ok === false) {
      return { additionalContext: `scope: ${refusal.code}: ${refusal.message ?? "refused"}` };
    }
    return undefined;
  };
};

// `agent_end`: the host's idle boundary, and the place a deferred relay runs.
//
// Why here and not `turn_end`: `turn_end` is emitted while the turn's prompt is
// still in flight (`#promptInFlightCount > 0`), so a switch there would hit the
// same `SESSION_STREAMING` wall. `agent_end` with `willContinue` absent is the
// host's own statement that this run is over and the session may be handed
// control (`lifecycle-mirror.ts` maps a terminal `agent_end` to
// `willContinue: undefined`).
//
// A non-terminal `agent_end` (`willContinue: true`) is a CONTINUATION — retry,
// queued input, background work — and the session is not settled, so the relay
// waits for the terminal one. Nothing is reported back to the model: the relay
// is not a tool result, and inventing `additionalContext` here would put text in
// a turn that has already ended.
export const createAgentEndHandler = ({ performDeferredRelay }) => {
  return async (event) => {
    if (typeof performDeferredRelay !== "function") return undefined;
    if (event?.willContinue === true) return undefined;
    await performDeferredRelay(event);
    return undefined;
  };
};

// `agent_end`: the unattended stop rule for a user-facing session (`nudge.mjs`).
//
// A Plan or Manager runs in a standing, always-authorised mode. Its turn may end
// in exactly two ways: it asked the operator in S1 and is waiting for the
// answer, or it reached its tenure's own end. Anything else is the model
// quietly giving up, and the host is the only thing that can say so — the
// handler returns `{ continue: true, prompt }` and OMP runs another turn with
// that prompt as the operator's words.
//
// The relay still runs first: a deferred relay is the tenure's own business and
// must be attempted before any nudge, or the nudge would ask the model to carry
// on inside a tenure that is already over.
//
// A worker session is never nudged: `nudgeFor` answers `{stop:true,
// exit:"not-user-facing"}` for it, and the handler returns nothing.
export const createStopRuleHandler = ({ performDeferredRelay = null, stopRule = null } = {}) => {
  return async (event) => {
    if (typeof performDeferredRelay === "function" && event?.willContinue !== true) {
      await performDeferredRelay(event);
    }
    if (typeof stopRule !== "function") return undefined;
    // A turn that will continue on its own is not a stop: OMP is about to run
    // the next turn anyway, and a nudge would duplicate the work.
    if (event?.willContinue === true) return undefined;
    // The verdict is compose's own shape (`evaluateTurnExit`): a stop is
    // `stopAllowed: true`, and a nudge always comes WITH `stopAllowed: false`.
    // A verdict that says neither is a caller bug, and it must not become a
    // silent stop — the session would go quiet, which is the failure this rule
    // exists to prevent.
    const verdict = stopRule();
    if (verdict === null || verdict === undefined) return undefined;
    if (verdict.stopAllowed === true) return undefined;
    const prompt = typeof verdict.nudge === "string" && verdict.nudge.length > 0 ? verdict.nudge : null;
    if (prompt === null) return undefined;
    return { continue: true, prompt };
  };
};

export const roleInfo = (role) => ({ role, worker: isWorkerRole(role) });
