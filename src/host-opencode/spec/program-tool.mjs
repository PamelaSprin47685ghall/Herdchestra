// The model-facing tool for a role (§3.1, §3.3, §7.1).
//
// One call is one program. The call answers with the program's receipt:
//
//   - a park receipt, when a member parked before the body returned;
//   - a completed receipt with `value`, when the body returned;
//   - a failed receipt with `error`, when the body threw.
//
// A parked program keeps running in the background: its `await` is blocked on a
// promise that `unpark` settles (program-execution.mjs), so the body continues
// from the park site when another program resumes it. Nothing is re-run and no
// effect happens twice. The program's final receipt is recorded when the resumed
// body returns; a later call that reuses the name reports it.
//
// The receipt is core's `programView`; the adapter only adds `value`/`error` and
// never invents a field. The program name is the host call id, so replay and the
// park table agree on identity, and the source is the exact `code` the model
// wrote (program-source.mjs).
//
// The program name is the call id, and the host reuses a call id only when it
// replays the same physical call, so two different tool calls never share a
// name. A resumed program is not a second call: `unpark` settles the suspended
// expression in place.

import { STATES } from "../../core/program.mjs";
import { CoreError, CODES } from "../../core/errors.mjs";
import { Deadline, deadlineFor, systemClock } from "../../core/clock.mjs";
import { withDeadline } from "../../compose/deadline.mjs";
import { startProgram } from "./program-execution.mjs";
import { makeSurface } from "./surface-factory.mjs";
import { programSource } from "./program-source.mjs";
import { roleOf } from "./agent-role.mjs";
import { refusal, REFUSALS } from "./refusals.mjs";
import { storedReceipt } from "./call-record.mjs";
import { REVIEW_WINDOW_CLOSED } from "../../compose/program/surface.mjs";

const isNonEmptyString = (value) => typeof value === "string" && value.trim() !== "";

// One observation ledger per request (§3.3): the programs this call started.
// The host gives a request no identity, so the ledger is keyed by call id and
// dropped when the call returns; `unpark` may only reach programs this call saw.
const openObservation = (activation, controllerId, scope) => {
  scope.open(controllerId);
  return {
    observe: (name) => scope.observe(controllerId, name),
    close: () => scope.close(controllerId),
  };
};

export const makeProgramExecutor =
  (activation, role, { session, review = false }) =>
  async (input, context) => {
    const actual = roleOf(context.agent);
    if (actual !== role) return refusal(REFUSALS.ROLE, { tool: role, role: actual });
    if (!isNonEmptyString(input?.code)) return refusal(REFUSALS.CODE);

    const state = session(context.sessionID);
    // The request deadline is fixed once, at admission, from the intent's byte
    // count (§3.3). The program owns it: a park cancels it, a resume replaces
    // it with the controlling request's, and expiry suspends the run.
    const budgetMs = deadlineFor(input.code);
    const deadline = new Deadline(systemClock, budgetMs);
    const startedAt = Date.now();

    // The draft's canonical path is the fact the Plan must be told (§2.2: "系统会
    // 告知确切路径"), and its system prompt cannot carry it: the prompt is per
    // agent, while the path is per session. The receipt is the one channel this
    // host has, so every Plan answer names the file.
    const extra = role === "plan" && typeof activation.draftPath === "function"
      ? { draft: activation.draftPath(context.sessionID) }
      : null;
    const withDraft = (receipt) => (extra === null ? receipt : { ...receipt, ...extra });
    const observation = openObservation(activation, context.id, state.control);
    const source = programSource(input.code);
    // A program name belongs to the session, not to one call: a later call of
    // the same session may name a parked program to resume it, so the caller
    // observes every live name of the session before its own.
    for (const name of state.calls.liveNames()) observation.observe(name);
    // §2.4 第 6 步: the same physical call replayed answers the original
    // receipt and runs nothing; the same call id with a DIFFERENT payload is
    // refused. The name IS the call id, so a name that already finished is a
    // replay, and replaying is not "running the body again" — for a Plan's
    // empty review that difference is a second Manager session.
    //
    // The payload is the WHOLE tool input (`code`, `scope`, and every field a
    // later schema adds), not the program source: comparing only the source
    // would accept a replay that changed the scope annotation or anything else
    // the call carries. `context.id` is deliberately not part of it — it is the
    // identity being matched, not a payload.
    if (state.calls.retired(context.id)) {
      observation.close();
      if (!state.calls.matchesPayload(context.id, input)) {
        return withDraft({
          ...state.calls.replayOf(context.id),
          value: null,
          error: null,
          rejection: REFUSALS.REPLAY_MISMATCH,
          detail: "this call id was used with a different payload; the original receipt is kept and nothing ran",
        });
      }
      return withDraft(state.calls.replayAnswer(context.id, { source }));
    }
    // §2.3: the evaluation window is open only until the round's first accepted
    // review. The gate runs AFTER the replay check: a replay performs nothing
    // and answers the original receipt (§2.4 第 6 步), so a closed window must
    // not turn the same physical call into a refusal.
    if (review && !state.windowFor("manager").open) {
      observation.close();
      return withDraft(refusal(REFUSALS.REVIEW_WINDOW_CLOSED));
    }
    // The program's capability set is the face's role (core §3.3). Two programs
    // of one session therefore share it, which is what lets a `Plan` resume the
    // `ask` park it made with `this.unpark(name, answer)`.
    const program = state.calls.open(context.id, source, { capabilities: [role], deadline });
    // The payload is recorded with the program, so the replay above can compare
    // it after a restart: the program's own replay records do not survive a
    // process, and a call id that finished in an earlier process is not
    // replayable — it is a new call that happens to reuse an id.
    if (program !== null) program.recordPayload(input);
    if (program === null) {
      observation.close();
      // The name is still live: this physical call is running twice, or its
      // program is parked and waiting for a resume. Report what is known.
      return { ...state.calls.viewOf(context.id), value: null, error: null, rejection: REFUSALS.BUSY };
    }
    state.setHandles(state.calls.parked());

    let built;
    try {
      built = makeSurface({
        role,
        name: context.id,
        program,
        session: { location: { directory: state.root } },
        source,
        ports: activation.portsFor({
          state,
          sessionID: context.sessionID,
          signal: context.signal,
          // The calling session and its physical call id, per call. The relay
          // and devops ports read them from here; nothing is stored on the
          // activation for the duration of a call.
          call: { id: context.id ?? null, agent: context.agent ?? null },
        }),
        views: activation.views,
        // `js-review` is the Manager's separate read-only face (§2.3), not a
        // Manager program: it has `cat`/`stat`/`ls`/`where`/`whereAll` and none
        // of the Manager members. Running it as a Manager program would give the
        // body no `cat` at all.
        review,
      });
    } catch (error) {
      // The surface refuses a shut window itself; that refusal is the same one
      // the check above answers. Anything else is a wiring error and is
      // reported as one. The name is released in both cases: no body will run,
      // and a name left live would refuse every later call that reuses it.
      observation.close();
      program.finish("failed", error);
      state.calls.closeIfTerminal(context.id);
      if (error?.code === REVIEW_WINDOW_CLOSED) return refusal(REFUSALS.REVIEW_WINDOW_CLOSED);
      throw error;
    }

    // A surface that refused before any body existed (the Plan's draft
    // directory could not be prepared) says so as a receipt. No program runs,
    // and the name is released like any other refused call: a name left live
    // would refuse every later call that reuses it.
    // The draft's canonical path is the fact the Plan must be told (§2.2: "系统会
    // 告知确切路径"), and its system prompt cannot carry it: the prompt is
    // per agent, while the path is per session. The receipt is the one channel
    // this host has, so the first answer of a Plan session names the file.
    if (built.surface === null) {
      observation.close();
      program.finish("failed", built.refusal);
      state.calls.closeIfTerminal(context.id);
      return withDraft(built.refusal);
    }

    // A stop channel exists before the body runs: a resumed body's next stop
    // must reach the resumer that woke it.
    state.calls.stop(context.id);
    // The ONE park channel (根 §3.3 example 7, §2.4 第 1 步). A park has one
    // spelling on this host: the surface's `parkChannel` hands the site here,
    // and this registers the promise a resume settles. `this.ask` reaches it
    // through the surface's `ask` member, and a ledger-driven question — asked
    // from the `review` port, where no member returned a promise the wrapper
    // could have parked — reaches it through the same channel, so
    // `answerPendingAsk` resumes the very body that asked.
    //
    // The answer is the pending promise, exactly as the wrapper's own park
    // returns it: `await review([])` suspends where it stands and continues
    // with the operator's reply when the next prompt resumes it.
    //
    // The channel is the ONE place a park becomes a fact, so it is also the
    // place the call is told about it. A member that parks synchronously is
    // reported by the wrapper's own check; a member that parks LATER — a
    // `review` queues its decision, and the first question is asked from that
    // queued step (根 §2.4 第 1 步) — has already returned by the time the
    // channel runs, so nothing would report it and the call would wait forever.
    // Both paths fire the same idempotent signal (`reportPark`).
    let reportPark = () => {};
    // `this.ask(question)` is `this.park` + `interrupt`: the question is
    // recorded, shown to the operator and the turn is interrupted. It runs HERE
    // rather than only in the call's parked branch, because a RESUMED body can
    // park a second time at an `ask` after its call has already answered — and
    // then no call is left to put the question in front of anyone. One promise
    // per site, so the first call can await the same work.
    let askShown = null;
    let askSite = null;
    const showAsk = (site) => {
      if (askShown !== null && askSite === site) return askShown;
      askSite = site;
      askShown = (async () => {
        const asked = await activation.asks.ask({
          sessionID: context.sessionID,
          program: context.id,
          question: site?.payload?.question ?? "",
        });
        // 根 §2.4 第 1 步: the call put the question to the operator, so its
        // ledger row says so. The row is what a restart replays, and an
        // `askUser` that is replayed would ask the operator a second time. The
        // fact is written HERE, where the question is really put — not when a
        // call finishes, which a parked body never reaches.
        if (role === "plan") state.saveLedger?.("plan", { questionAsked: true });
        // The interrupt is the host's own (`Session.interrupt`). A host that
        // refuses it does not undo the park: the program is suspended and the
        // receipt still carries `interrupt: true`.
        if (activation.sessions !== null && typeof activation.sessions?.interrupt === "function") {
          try {
            await activation.sessions.interrupt({ sessionID: context.sessionID });
          } catch {
            // Reported by the absence of a stop, never turned into a refusal.
          }
        }
        return asked;
      })();
      return askShown;
    };
    built.surface.setParkChannel((site) => {
      if (program.terminal) return null;
      // The site is what the resume hands back, so the program holds it before
      // the promise exists. A second park is a caller bug (spec §3.3: one park
      // at a time) and core refuses it rather than replacing the site the
      // operator is about to answer.
      program.park(site);
      const pending = state.calls.parks.park(context.id, program.site);
      state.setHandles(state.calls.parked());
      reportPark(program.site);
      // A park that stopped a RESUMED body must reach the resumer waiting for
      // this program's next stop (`makeResumer` awaits `waitStop`); otherwise a
      // second `where` ambiguity or a second `ask` leaves `unpark` hanging.
      state.calls.wakePark(context.id, program.site);
      // The first `ask` is awaited by the call below; a later one has no call
      // left to do it, so the channel shows it here.
      if (site?.reason === "ask") void showAsk(site).catch(() => {});
      return pending;
    });
    const call = startProgram({
      code: input.code,
      members: built.members,
      program,
      parks: state.calls.parks,
    });
    reportPark = call.reportPark;
    // The body's own outcome is the next stop of a program that is parked; a
    // finished body wakes any waiting resumer too.
    void call.outcome.then((outcome) => state.calls.wake(context.id, outcome));

    // Whichever comes first decides this call: the body finishing, a park, or
    // the request's deadline (§3.3: 一旦到期，控制者与仍在活动的目标程序一同转入
    // `parked`). `withDeadline` races the program's own expiry signal, which a
    // park cancels: a body suspended on its own site is never "expired".
    const raced = await withDeadline(
      program,
      Promise.race([
        call.outcome.then((outcome) => ({ kind: "outcome", outcome })),
        call.parked.then((parked) => ({ kind: "parked", parked })),
      ]),
      {
        onExpire: () => {
          // The program suspends where it is, and the expression it is blocked
          // on takes this entry when it settles (`guardedMembers`), so a later
          // `unpark` resumes the body instead of finding nothing to settle.
          built.surface.expire();
          if (!state.calls.parks.has(context.id)) {
            state.calls.parks.park(context.id, program.site);
          }
          state.setHandles(state.calls.parked());
          return built.surface.receipt({ reason: "explicit", payload: null });
        },
      },
    );
    if (raced.kind === "expired") {
      observation.close();
      return withDraft({ ...(raced.receipt ?? built.surface.receipt({ reason: "explicit", payload: null })) });
    }
    const first = raced.value;

    if (first.kind === "parked") {
      const site = first.parked.site ?? null;
      // `this.ask(question)` is `this.park` + `interrupt`: the channel records
      // the question and shows it, the receipt says `interrupt: true`, and the
      // turn is interrupted so control returns to the operator. The reply
      // arrives as the operator's next prompt, which resumes the parked program
      // through `unpark(name, answer)`.
      let ask = null;
      if (site?.reason === "ask") {
        ask = await showAsk(site);
      }
      state.setHandles(state.calls.parked());
      const receipt = built.surface.receipt({
        reason: site?.reason ?? "explicit",
        payload: site?.payload ?? null,
        remainingWaitMs: Math.max(0, budgetMs - (Date.now() - startedAt)),
      });
      observation.close();
      // The call has answered; the parked program keeps its channel until the
      // resume settles it.
      return withDraft(ask === null ? receipt : { ...receipt, ask, interrupt: true });
    }

    observation.close();
    state.calls.closeIfTerminal(context.id);
    state.setHandles(state.calls.parked());
    // The finished call's receipt goes to the durable record, so a replay after
    // a restart answers the ORIGINAL receipt instead of "cannot replay" (§2.4
    // 第 6 步). It is written after the call, not before: before the run there
    // is no receipt to keep, and the id alone is what stops a second run.
    const finished =
      first.outcome.status === "failed"
        ? { ...built.surface.receipt(), value: null, error: first.outcome.error }
        : { ...built.surface.receipt(), value: first.outcome.value ?? null, error: null };
    if (typeof state.noteReceipt === "function") {
      state.noteReceipt(context.id, storedReceipt(finished, { value: finished.value, error: finished.error }));
    }
    return withDraft(finished);
  };

// Resume a parked program (§3.3). The resuming call settles the promise the
// target's `await` is blocked on, so the original body continues at the park
// site. The answer reports what the target did next: a value when it returned,
// an error when it threw, or a second park when it stopped again.
export const makeResumer =
  (activation, session) =>
  async ({ name, value, site = null, sessionID }) => {
    const state = session(sessionID);
    const program = state.calls.program(name);
    if (program === null) {
      throw new CoreError(CODES.PROGRAM_UNKNOWN, `program ${name} is unknown`, { name });
    }
    if (program.state !== STATES.RUNNING && program.state !== STATES.PARKED) {
      return { name, resumed: false, rejection: REFUSALS.BUSY, state: program.state };
    }
    const settled = state.calls.parks.settle(name, value);
    if (!settled) {
      return { name, resumed: false, rejection: REFUSALS.BUSY, state: program.state };
    }
    // The resume happened. The core site was already consumed by
    // `ControlSurface.unpark` (`takeSite` inside the controller's request), so
    // the body that continues may park again and a second resume of the same
    // site is stale (spec §3.3: 恢复是单次的). Consuming it here a second time
    // would throw on a site that no longer exists.

    // The body continues on its own microtask. Wait for its next stop: a return,
    // a throw, or another park.
    const next = await state.calls.waitStop(name);
    if (next === null) return { name, resumed: true, state: "running", site: null };
    if (next.status === "parked") {
      // The park that stopped the resumed body already registered its own
      // promise at the moment it happened (`guardedMembers` -> `onParked`).
      // Registering it a second time would be refused as a duplicate site; only
      // a park nobody has seen yet needs an entry here.
      if (!state.calls.parks.has(name)) state.calls.parks.park(name, next.site);
      state.setHandles(state.calls.parked());
      return { name, resumed: true, state: "parked", site: next.site ?? null };
    }
    state.calls.closeIfTerminal(name);
    state.setHandles(state.calls.parked());
    return next.status === "completed"
      ? { name, resumed: true, value: next.value ?? null, error: null }
      : { name, resumed: true, value: null, error: next.error };
  };
