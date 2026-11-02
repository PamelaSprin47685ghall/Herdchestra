// Every host hook this adapter registers (根 AGENTS.md §6.1, §6.2, §7.2).
//
// Cline's hook bag is `beforeRun`, `afterRun`, `beforeModel`, `afterModel`,
// `beforeTool`, `afterTool`, `onEvent` (`AgentRuntimeHooks`). Each handler is a
// small function of the host context plus the session state this adapter owns:
//
//   beforeRun      one turn boundary for the Scope book, and the Scope notice
//                  the host appends as hook context
//   beforeModel    the session's role, the resume of a parked `ask`, the model
//                  reading, the nudge the host appends as hook context, and THE
//                  REQUEST COPY (the session's projection). The host applies a
//                  returned `messages` to this request only
//                  (`agent-runtime.ts:1700-1711`), and never to the transcript;
//                  a returned `systemPrompt` is dropped (same lines), which is
//                  why the role prompt is a registered rule (`rewrite.mjs`)
//   beforeTool     the role's face gate and Scope admission
//   afterTool      Scope settle
//   afterModel     a refusal after a call cannot undo it; it is reported in the
//                  hook context instead of thrown
//
// The request rewrite lives in ONE place (`rewrite.mjs`): the session's
// projection, returned as this hook's `messages` (根 §7.2). It is the
// provider-bound copy of every request and outside the transcript. The role
// prompt is a registered rule: the host appends rule content to every request's
// system prompt, while a hook cannot replace it.
//
// The gate is the only place a forbidden call is refused. The visible tool set
// is a host-side narrowing the plugin cannot perform (spec §7.2), which is
// exactly why the refusal has to live in `beforeTool` (根 §7.2, "两层分离").

import { appendFileSync } from "node:fs";

import { ReviewMember } from "../compose/relay/review.mjs";
import { toCue } from "../core/cue.mjs";
import { REVIEW_KINDS } from "../core/review.mjs";
import { FACE_TOOLS, MANAGER_FORBIDDEN, PLAN_FORBIDDEN, ROLES as ROLE, callRole, surfaceAllowed } from "./roles.mjs";
import { lastOperatorIndex, selectKept, toItems, toPairHintMessages } from "./request.mjs";
import { parkStop, requestNotice, runExit, turnExit } from "./guard.mjs";
import { requestRewrite } from "./rewrite.mjs";

// `appendContext` is model-visible request context, so a structured payload goes
// in as CUE (根 §6.3), never as raw JSON: a `"""` block keeps a multi-line
// message readable and unescaped. A value CUE cannot render is not a notice we
// produce; it must not turn a hook into a crash, so it falls back to `String`.
const contextText = (value) => {
  try {
    return toCue(value);
  } catch {
    return String(value);
  }
};

// The request copy, as the `messages` field of a `beforeModel` answer.
//
// The answer is `{}` when the request carries no message list: the host then
// keeps the messages it built. `requestRewrite` already answers the messages
// unchanged when the projection throws, so a failing copy never breaks a
// request — the model reads what the host was going to send.
const copyOf = async (rewrite, request) => {
  if (!Array.isArray(request?.messages)) return {};
  return { messages: await rewrite(request.messages) };
};

// What a Scope admission means for this call. `admit` answers a receipt: an
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

const nameOf = (context) => context?.tool?.name ?? context?.toolCall?.toolName ?? null;

// Every tool whose `scope` annotation Scope must admit and settle (根 §6.2).
//
// The FACE gate is narrower: `fork` and `join` are primitives, not faces, so a
// role's member list is not what decides them. Both schemas still REQUIRE
// `scope` (`adapter.mjs` `withScope`), and §6.2 says every non-exempt tool books
// its annotation: a `fork` that carries `resetScope` establishes the budget, and
// a `join` that carries `keepLast` spends one. Leaving them out of the gate let
// a turn made only of `fork`/`join` calls close as `SCOPE_METOO_ONLY`, and the
// annotations never reached the book at all.
export const SCOPE_TOOLS = Object.freeze(new Set([...FACE_TOOLS, "fork", "join"]));

// Whether this hook context belongs to a DELEGATED runtime of this session
// rather than to the session's own agent.
//
// A delegated runtime receives this session's hooks (`runtime.mjs` passes
// `extensions.hooks` to the child), so every hook also runs for a child's run
// and request. The session's bookkeeping is the PARENT's fact — its Scope turn,
// its review window, its parked `ask`, its park stop, its nudge — and a child's
// run must not move any of it. Measured consequence: a worker's verboseIntent is a user
// message, so `resumeAsked` answered a question the operator never saw, and the
// child's `beforeRun` cleared the parent's park stop.
const delegatedRuntime = ({ session, context }) => {
  const snapshot = context?.snapshot;
  if (snapshot === null || typeof snapshot !== "object") return false;
  const parent = snapshot.parentAgentId;
  if (parent !== null && parent !== undefined) return true;
  // A host that names the child's agent id is answered from the worker table:
  // a name this table holds is one this session started.
  const agentId = snapshot.agentId;
  return typeof agentId === "string" && session.workers.get(agentId) !== null;
};

// Whether the host snapshot proves this call is a top-level agent: a snapshot
// is present, it is an object, and it carries no parent. The test is the VALUE
// of `parentAgentId`, never the key: `@cline/core` 0.2.0 omits the key entirely
// for a top-level agent (`roleFor` makes the same reading). A missing snapshot
// proves nothing, and `null` is returned so the caller fails closed.
const topLevelProven = (context) => {
  const snapshot = context?.snapshot;
  if (snapshot === null || typeof snapshot !== "object") return null;
  const parent = snapshot.parentAgentId;
  if (parent === null || parent === undefined) return true;
  return false;
};

// The role an operator-started session plays, established from the snapshot of
// the call being decided. This is the ONE fact a Desktop session can give us:
// it has no role fact (nothing started it through this adapter) and `setup`
// runs before the host registers it, so `getSession` answers nothing.
//
// A proven top-level call in a session whose role is not a reading yet is the
// operator's own session, and the operator's session is the Manager — unless it
// is calling `js-plan`, which is the entry that starts Plan's own session and is
// never a Manager call (`callRole` gives the session's Plan role to a top-level
// `js-plan`, so this admits Plan on its first call without inventing a second
// top-level role). A child call proves no session role and stays `unproven`,
// which the gate refuses.
const operatorRoleFor = ({ session, name, context }) => {
  if (session.identity?.source !== "unproven") return null;
  if (topLevelProven(context) !== true) return null;
  const role = name === "js-plan" ? ROLE.PLAN : ROLE.MANAGER;
  session.recordOperatorRole(role);
  return role;
};

// The shape a hook context arrived in, reduced to what a gate decision depends
// on. The host prunes a payload it cannot structured-clone, so the sandbox path
// may deliver a DIFFERENT shape than an in-process load — and the gate's answer
// follows that shape. `snapshotKeys` is the decisive field: if `parentAgentId`
// is missing from it, `roleFor` never sees the caller as top-level.
const describeContext = (context) => {
  if (context === null || typeof context !== "object") return { contextType: typeof context };
  const snapshot = context.snapshot;
  return {
    contextKeys: Object.keys(context),
    snapshotKeys: snapshot !== null && typeof snapshot === "object" ? Object.keys(snapshot) : null,
    snapshotParent: snapshot?.parentAgentId ?? null,
    snapshotAgent: snapshot?.agentId ?? null,
    toolName: context?.tool?.name ?? context?.toolCall?.toolName ?? null,
  };
};

// Where a trace line goes, or null when tracing is off. A file is the route that
// survives the plugin sandbox, where the child's stderr is not forwarded.
const traceSink = () => {
  const file = process.env.HERDCHESTRA_TRACE_FILE;
  if (typeof file === "string" && file.length > 0) return { kind: "file", file };
  if (process.env.HERDCHESTRA_TRACE === "1") return { kind: "stderr" };
  return null;
};

// Best effort by design: a trace that throws must never refuse a real call.
//
// Everything that can throw — `describeContext`, `JSON.stringify`, the write
// itself — happens inside the `try`. A host context carrying a cycle or a
// throwing getter (the same objects that make the plugin sandbox warn) must
// lose its trace line, never the call it describes.
const traceGate = ({ tool, role, declaredRole, sessionId, identitySource, roleFactFailure = null, context }) => {
  const sink = traceSink();
  if (sink === null) return;
  try {
    const line = `${JSON.stringify({
      at: "beforeTool",
      tool,
      role,
      declaredRole,
      sessionId,
      identitySource: identitySource,
      roleFactFailure,
      ...describeContext(context),
    })}\n`;
    if (sink.kind === "file") appendFileSync(sink.file, line);
    else process.stderr.write(line);
  } catch {
    // A trace is a diagnostic, never a gate.
  }
};

export const createHooks = ({ session }) => {
  // The session's request copy, as this hook's answer. Bound once: the copy is
  // the session's, and a request boundary only asks it for the current messages.
  const rewrite = requestRewrite({ project: (messages) => session.projectMessages(messages) });
  return {
    // `AgentRunStartResult` has no `systemPrompt`; the prompt is replaced in
    // the registered rule (`rewrite.mjs`), which is the request-level point the
    // host honours.
    //
    // A run is not a model turn: one run holds many model requests
    // (`snapshot.iteration` in `AgentBeforeModelContext`).
    beforeRun: async (context) => {
      // A child runtime's run is not this session's run: its start must not
      // advance the parent's turn counter or clear the parent's park stop.
      if (delegatedRuntime({ session, context })) return undefined;
      session.beginRun();
      const notice = session.scopeNotice();
      return notice === null ? undefined : { appendContext: contextText(notice) };
    },

    // 根 §2.2: a user-facing run does not end until the work is verified.
    //
    // `afterModel` records the attempt and `beforeModel` speaks it, which is the
    // in-run channel this host has (`guard.mjs` names the measurements). This
    // hook is the last net: a run that finished anyway — the host stopped after
    // the turn `afterModel` saw, a model mode with no further request — is
    // continued with a real prompt into the session, the only channel left.
    //
    // The answer is reported as it is: `continued: true` only when the host
    // confirmed delivery, and the nudge text either way, so a session that could
    // not be continued says so instead of stopping silently. The host discards
    // an `afterRun` answer (`callAfterRunHooks`), so this receipt is for the
    // session's own record and for a test.
    afterRun: async (context) => {
      // A child's run ending is its report (§2.2 `not-user-facing`); the
      // parent's nudge channel is not the child's to drive.
      if (delegatedRuntime({ session, context })) return undefined;
      const role = session.declaredRole;
      const decision = runExit({ session, role, result: context?.result ?? null });
      if (decision.stopAllowed) {
        // A stop the guard allows is no longer owed, whatever an earlier
        // attempt recorded: an operator interrupt or a host failure must not
        // leave a nudge that the next request would replay.
        session.clearNudge();
        return undefined;
      }
      session.noteNudge({ role, reason: decision.reason, text: decision.nudge });
      const delivery = await session.deliver(decision.nudge);
      return { continued: delivery.ok === true, delivery, reason: decision.reason, text: decision.nudge };
    },

    // A request boundary: a FACT this adapter records, and the copy the model
    // reads. Every answer below is merged by the host (`mergeRuntimeHooks`,
    // `session-runtime-orchestrator.ts:228-256`): a `stop` wins outright, and
    // otherwise `messages` and `appendContext` both travel.
    beforeModel: async (context) => {
      // A child's request is its own conversation. The COPY still applies —
      // CUE rendering and the Scope projection are about the bytes the model
      // reads — but nothing of the parent's turn, parked ask, park stop, model
      // reading or nudge may move for a child's request.
      if (delegatedRuntime({ session, context })) return await copyOf(rewrite, context?.request);
      // 根 §2.2 「中断（交还控制权）」: a turn that parked an `ask` has no further
      // model step. The host reads `AgentStopControl` from this hook, so the run
      // ends here instead of asking the model again after the question was put
      // to the operator. Claimed once per park (`SessionState.claimParkStop`).
      const park = parkStop({ session });
      if (park !== null) return park;
      // Root §2.3: one model request is one evaluation round, so the window
      // opens here — before the model can call `js-review`. Opening an already
      // open round changes nothing; a round whose review was accepted stays
      // shut for its remaining requests.
      session.tenure.window.beginRound();
      // The previous model turn closes here, not in `afterModel`.
      //
      // Host order per iteration (`agent-runtime.ts`): `beforeModel` →
      // `afterModel` (line 2079) → `beforeTool`/`afterTool` for each call
      // (`executeToolCalls`, line 974). Closing in `afterModel` would run before
      // the tools of that same turn, so a `meToo` not yet admitted could not be
      // seen and a tool-less turn would close with nothing booked.
      //
      // By the next `beforeModel`, every tool of the previous turn has settled,
      // which is the point Scope's `closeTurn` describes. `closeTurn` answers a
      // Promise: it judges on a still book and queues behind any settlement in
      // flight, so the refusal is only known once it settles.
      const closed = await session.closeTurn();
      if (closed !== null && closed !== undefined && closed.ok === false) {
        // The copy still applies: a refused settle is a notice, not a reason to
        // send the host's unprojected transcript.
        return {
          appendContext: `scope: ${closed.code}: ${closed.message ?? "refused"}`,
          ...(await copyOf(rewrite, context?.request)),
        };
      }
      // One model request is one Scope turn (spec §6.2). The turn that just
      // ended is the one `afterModel` judged, and its ask fact is read BEFORE
      // the new turn clears it: `requestNotice` must re-decide the turn that is
      // owed the nudge, not the one that has not started.
      const endedWithAsk = session.askedInTurn;
      session.openTurn();
      // The session's role is settled before the first request: a Plan session
      // must not carry the Manager prompt even once.
      await session.resolveRole();
      const request = context?.request;
      if (request === null || typeof request !== "object") return undefined;
      // A program waiting on `ask` resumes from the operator's own words, which
      // arrive as the request's user message. This runs before the prompt and
      // the projection, because it is a continuation of the same task: no new
      // tenure, no review (根 §2.2).
      if (Array.isArray(request.messages)) session.resumeAsked(request.messages);
      // The connection this session actually runs. The plugin's setup context
      // carries no provider and no model, so the request is the only reading a
      // hand-off or a worker can reuse without guessing (`session.model`).
      session.noteModel(request);
      // 根 §2.2: the nudge a refused natural stop earned is owed to THIS
      // request. `afterModel` recorded it; the host flushes `appendContext` as a
      // user message before this request, which is what keeps the model working
      // instead of stopping.
      //
      // Root §7.2: the system prompt is the registered rule; the messages are
      // this hook's `messages`, the one field the host honours here
      // (`agent-runtime.ts:1700-1711`). A `systemPrompt` returned from here
      // would be dropped by the host — measured, and the reason the role prompt
      // moved to a rule (`rewrite.mjs`).
      const nudge = requestNotice({ session, role: session.declaredRole, askedInTurn: endedWithAsk });
      const copy = await copyOf(rewrite, request);
      if (nudge === null) return copy;
      return { appendContext: nudge.text, ...copy };
    },

    beforeTool: async (context) => {
      // The session's own role is a fact read from the host, and the gate cannot
      // decide without it: a Plan session whose role was not resolved yet looks
      // like a Manager, and `js-plan` is refused by the Manager face. Every other
      // hook resolves the role first; this one did not, which is exactly the
      // first tool call a Plan session makes.
      await session.resolveRole();
      const name = nameOf(context);
      // The role an operator-started session plays, when its snapshot proves it
      // (`operatorRoleFor`). It runs BEFORE `callRole`, because a Plan admitted
      // here must reach `callRole` as the session's declared role, not as the
      // Manager default `roleFor` reads from a session nobody marked.
      operatorRoleFor({ session, name, context });
      const role = callRole({ session, roleFor: (value) => session.roleFor(value), context });
      // One line per gate decision, when tracing is asked for. The host neither
      // shows nor stores a hook's return value, so without this a refused call
      // has no trace of WHICH role refused it: "js-plan is not available to
      // this session" is produced by several different role/name pairs, and
      // telling them apart from the outside is guesswork.
      //
      // `HERDCHESTRA_TRACE_FILE` is the route that works in the plugin
      // SANDBOX: a sandboxed plugin's stderr never reaches the parent process,
      // so a trace written to stderr is invisible exactly where it is needed
      // most. `HERDCHESTRA_TRACE=1` still writes to stderr for in-process loads.
      traceGate({
        tool: name,
        role,
        declaredRole: session.declaredRole,
        sessionId: session.sessionId,
        // Which reading produced the role. A wrong role and an UNANSWERABLE
        // host look identical from the outside, and they need different fixes;
        // this field is what tells them apart in the sandbox, where the child's
        // stderr is not forwarded.
        identitySource: session.identity?.source ?? null,
        // Why a role fact could not be written, when one was attempted: the
        // snapshot path still decides this call, and the next load will not
        // find a file. `null` when written or not attempted.
        roleFactFailure: session.roleFactWriteFailure?.reason ?? null,
        context,
      });
      if (typeof name !== "string") return undefined;
      // Every refusal below is remembered for `afterTool`. The host still fires
      // that hook for a skipped call, and Scope books on settle, so a call that
      // never ran would otherwise still spend its annotation.
      const refuse = (reason) => {
        session.noteSkipped(context?.toolCall?.toolCallId ?? null);
        return { skip: true, reason };
      };
      // A role nobody proved is not a role. Every source failed (no metadata, no
      // role fact, no host record) AND the snapshot did not prove a top-level
      // caller (`operatorRoleFor`): granting a face on the default would hand a
      // session it was never given. A session admitted from its snapshot is no
      // longer unproven — `recordOperatorRole` adopted the role, so `identity`
      // reads `sessionSnapshot` from here on.
      if (session.identity?.source === "unproven") {
        return refuse("SESSION_ROLE_UNKNOWN: this session's role could not be read, so no face is granted");
      }
      if (role === ROLE.MANAGER && MANAGER_FORBIDDEN.includes(name)) {
        return refuse(`Manager has no ${name}; fork a worker instead`);
      }
      if (role === ROLE.PLAN && PLAN_FORBIDDEN.includes(name)) {
        return refuse(`Plan has no ${name}; use js-plan, which reads and writes only the draft`);
      }
      // Root §2.3: `js-review` is open only while this round's evaluation is.
      // The visible set is not an execution grant, so a call that arrives after
      // the round's first accepted review is refused here.
      if (!surfaceAllowed(role, name, { window: session.tenure.window })) {
        return refuse(
          role === ROLE.MANAGER && name === "js-review"
            ? "REVIEW_WINDOW_CLOSED: this round's first review was already accepted; fork a read-only coder to read the tree"
            : `${name} is not available to this session`,
        );
      }
      if (!SCOPE_TOOLS.has(name)) return undefined;
      const refusal = refusalOf(
        session.admit({
          agentId: context?.snapshot?.agentId ?? null,
          member: name,
          input: context?.input,
        }),
      );
      if (refusal === null) return undefined;
      return refuse(`${refusal.code}: ${refusal.message}`);
    },

    afterTool: async (context) => {
      const name = nameOf(context);
      if (typeof name !== "string" || !SCOPE_TOOLS.has(name)) return undefined;
      // A call the gate refused never ran, so it must not settle: Scope books
      // on settle, and a refused `js-plan` was writing its `resetScope` row on
      // the live host. The entry is taken (not read), because a call id belongs
      // to one call.
      if (session.takeSkipped(context?.toolCall?.toolCallId ?? null)) return undefined;
      const result = await session.settle({
        agentId: context?.snapshot?.agentId ?? null,
        member: name,
        input: context?.input,
        callId: context?.toolCall?.toolCallId ?? null,
      });
      if (result === null || result === undefined) return undefined;
      if (result.ok === false) {
        return { appendContext: `scope: ${result.code}: ${result.message ?? "refused"}` };
      }
      const notice = result.notice === null || result.notice === undefined ? null : result.notice;
      return notice === null ? undefined : { appendContext: contextText(notice) };
    },

    // `afterModel` fires before this turn's tools run (`agent-runtime.ts:2079`
    // vs `executeToolCalls` at 974), so the Scope turn is not over yet: the
    // close happens at the next `beforeModel`.
    //
    // It is, however, the first place the host says what this turn DID — the
    // assistant message and its finish reason. A model answer with no tool call
    // is a natural-stop attempt, and 根 §2.2 refuses it: `turnExit` records the
    // nudge, and `beforeModel` speaks it into the next request (the host reads
    // no text from `afterModel`; `guard.mjs` names the measurement).
    afterModel: async (context) => {
      // A child's assistant message is its own turn's; the parent's natural-stop
      // decision is not made from it.
      if (delegatedRuntime({ session, context })) return undefined;
      turnExit({ session, role: session.declaredRole, assistantMessage: context?.assistantMessage ?? null });
      return undefined;
    },

    onEvent: async () => undefined,
  };
};

export { lastOperatorIndex, selectKept, toItems, toPairHintMessages, ROLE };
export { FACE_TOOLS, MANAGER_FORBIDDEN, PLAN_FORBIDDEN, callRole, surfaceAllowed };

// `review(findings)` for the Manager and Plan surfaces. The ledger is the
// session's tenure, the side effects come from the session's relay port, and the
// result is persisted after every review (根 §2.3).
//
// A retired tenure reviews nothing: after the hand-over the successor judges, and
// the successor's first review is the next review of this session.
//
// The review carries no text. A Plan's hand-off starts its Manager from the
// canonical saved draft, read through the `draft` port (spec §2.2); a Manager has
// no `draft` port, so its source is null and no hand-off can happen.
export const reviewFor = async ({ session, findings, role = ROLE.MANAGER, callId = null }) => {
  // The session's role is a fact of the session (`identity.mjs`). A caller that
  // names no role reviews as the session does; a caller that names one is
  // asserting it, and the two must agree for a Plan review to reach the Plan
  // ledger.
  const resolved = await session.resolveRole();
  const effective = role === ROLE.MANAGER && resolved.role !== ROLE.MANAGER ? resolved.role : role;
  const tenure = session.tenure;
  if (tenure.retired) {
    return {
      ok: false,
      kind: "rejected",
      rejection: "TENURE_RETIRED",
      milestone: tenure.ledger.milestone,
      tenure: tenure.ledger.tenure,
    };
  }
  const ports = session.ports(effective);
  const source = typeof ports.draft?.read === "function" ? await ports.draft.read() : null;
  const member = session.reviewMember(effective);
  // 根 §2.4 第 6 步: one physical call books once. The host's tool-call id is the
  // identity; a replayed call returns the receipt it produced the first time and
  // neither relays nor persists again.
  const receipt = await member.review(findings, source, { callId });
  if (receipt?.replayed === true) return receipt;
  tenure.save();
  // Root §2.2: a Plan's `review([])` at 「待执行」 IS the hand-off to a Manager,
  // and it is Plan's half of its only legitimate stop. The receipt names the
  // Manager session the host started; a refused hand-off names none, so the
  // session keeps owing it.
  if (receipt?.kind === REVIEW_KINDS.HANDOFF) session.noteHandoff(receipt);
  // Root §2.3: the round's first ACCEPTED review shuts the evaluation window,
  // which is what makes `js-review` go away. The window reads the receipt, so a
  // refused review leaves it open — the Manager has not judged.
  tenure.window.noteReview(receipt);
  return receipt;
};
