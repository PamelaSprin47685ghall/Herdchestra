// Ports the composition layer needs, implemented over OMP (spec §3.3, §4.2,
// §5, §6, §7, §7.3).
//
// `src/compose/ports.mjs` names the ports a surface reads. This module supplies
// each one from host facts and from the session that owns it. A side effect the
// host cannot perform is reported as a receipt with `ok: false` and a named
// reason, never as success and never as a silent `null`.

import { nodeCommandRunner } from "../core/command.mjs";
import { PUBLISHED_FIELD, successorCopy } from "./relay-record.mjs";
import { groupIdOfLane } from "./fission-fanout.mjs";

// `call(command)` (spec §3.3). OMP's `pi.exec(command, args)` takes argv, not a
// shell string, and returns `{ stdout, stderr, code }` (exec/exec.ts:21). The
// command runs through `/bin/sh -c` to keep the member's documented meaning:
// every call starts one shell command. `onData` receives the completed output in
// one chunk per stream, because `pi.exec` resolves only after the process exits.
export const execCommandPort = (pi) => ({
  async run(command, { cwd, env, onData = () => {}, signal } = {}) {
    const result = await pi.exec("/bin/sh", ["-c", command], {
      ...(cwd === undefined ? {} : { cwd }),
      ...(env === undefined ? {} : { env }),
      ...(signal === undefined ? {} : { signal }),
    });
    const stdout = typeof result?.stdout === "string" ? result.stdout : "";
    const stderr = typeof result.stderr === "string" ? result.stderr : "";
    if (stdout !== "") onData({ stream: "stdout", chunk: stdout });
    if (stderr !== "") onData({ stream: "stderr", chunk: stderr });
    const exitCode = typeof result?.code === "number" ? result.code : -1;
    return { stdout, stderr, exitCode };
  },
});

// The command port used when no host is bound (tests, print mode).
export const fallbackCommandPort = {
  run: (command, options) => nodeCommandRunner(command, options),
};

// `ask` (spec §3.3, §6): the only user channel, top-level only.
//
// `this.ask(question)` IS `this.park({ question })` + `interrupt`. The surface
// parks the program before any port is reached, and this port is the asking
// half: it records the question and returns the asked receipt, which carries
// `interrupt: true`. The caller then interrupts the current turn, so control
// returns to the operator; the operator replies in ordinary chat, and a later
// program of the same session resumes the parked program with
// `this.unpark(name, answer)` — the reply becomes the value of
// `await this.ask(...)`, and the original body continues.
//
// A synchronous dialog is used only when one is really available: an injected
// `ui.input` answers in-call, and the answer is returned as `answer`. That is a
// convenience, not the channel: the park and the interrupt carry the question
// either way, so nothing here refuses for a missing dialog.
//
// The receipt says three things and no more: the question was ASKED (`asked`),
// it is the operator's (`question`), and the host must end the turn
// (`interrupt`). `program` and `toolCallId` name what will be resumed.
export const askedReceipt = ({ question, program = null, toolCallId = null }) => ({
  ok: true,
  kind: "asked",
  asked: true,
  question,
  interrupt: true,
  program,
  toolCallId,
  answer: undefined,
});

export const askPort = ({ ui, questions }) => ({
  async ask(question, { program = null, toolCallId = null } = {}) {
    const record = { question, at: Date.now(), answered: false, dismissed: false };
    questions.push(record);
    if (ui !== null && ui !== undefined && typeof ui.input === "function") {
      const answer = await ui.input("Herdchestra asks", question);
      record.answered = typeof answer === "string";
      record.dismissed = answer === undefined;
      if (record.answered) return { ...askedReceipt({ question, program, toolCallId }), answer };
    }
    return askedReceipt({ question, program, toolCallId });
  },
});

// `devops` (spec §6): one synchronous investigation for Plan. It starts one
// devops session through the host's `task` tool and waits for that session's one
// answer, then returns the report whole; the system adds no summary.
export const devopsPort = ({ binding }) => ({
  async investigate(intent) {
    const name = `plan-devops-${Date.now().toString(36)}`;
    const run = await binding.runAgent({ agent: "devops", name, brief: intent });
    if (run.ok !== true) return run;
    return { ok: true, report: run.text, agentId: run.agentId ?? null };
  },
});

// `worker` (spec §4.1): the Manager's own fork and join.
export const workerPort = (worker) => ({
  fork: (name, brief) => worker.fork(name, brief),
  join: () => worker.join(),
});

// The three sentences that open every lane (spec §4.2, step 3). They state the
// one fact a lane could otherwise misread: it is a parallel present of the same
// participant, not a delegate with siblings to command.
//
// `laneCount` is the group's lane count, which compose fixes once for every
// lane. It is never the lane's index or `index + 1`: those would print a
// different number in each lane's context.
export const laneFraming = (laneCount) =>
  [
    `You are one of ${laneCount} parallel presents of the same worker identity, not a new delegate.`,
    "Your input below is your whole current responsibility.",
    "Continue that same responsibility; do not treat the sibling lanes as agents delegated to you.",
  ].join("\n");

// `fission` (spec §4.2). A lane is a worker of the owner's identity started
// through the host's own session factory (`lane.mjs`), so the whole group is
// built and started with capabilities this host really has.
//
// Replacing the owner's present: the owner's own session stops producing turns
// (`session-control.mjs` `silence` — abort the live turn, drop the queue, keep
// the session object so its transcript stays readable). The owner is ALWAYS
// mid-turn here — `fission` is a tool call of its turn — so no step of the
// replacement waits for the owner to settle. Root spec §4.2 gives fission
// exactly one rejection for a host-side step and no time limit, and the only
// way to honour both is to do what can be done from inside the turn (the mark,
// the dropped queue) and arm the host's own interrupt for the turn's end.
//
// `abortLanes` disposes each lane's session: a lane is a session this adapter
// created, so it can really be torn down.
//
// `fission` is the live compose `Fission` of the session that owns the group,
// and `fanout` (`fission-fanout.mjs`) is the translator that turns a settled
// lane into `Fission.settle(owner, index, result)`. Every §4.2 decision —
// delivery, `ConvergenceReady`, the merge order, the takeover announcement —
// stays in compose; this port only says WHICH lane ended. A port built without
// them (a test that checks admission only) still builds and starts lanes, and
// its `laneTurnEnd` reports `NO_GROUP` instead of inventing a delivery.
export const fissionPort = ({
  lanes,
  startLane,
  agentFor,
  owner,
  present = null,
  fission = () => null,
  fanout = null,
  onEvent = null,
}) => ({
    // The session that owns this group. Fission's owner is a SESSION identity,
    // never the name of the program that called it: a program is named by its
    // tool-call id, and comparing that against the session name refused every
    // real worker with `InvalidOrigin`.
    ownerName: () => owner(),
    async ownerView() {
      // The owner's last delivered view is its session's transcript, which this
      // adapter does not read for another session. A lane therefore starts from
      // the framing plus its own input: the documented empty-view case.
      return null;
    },
    // §4.2 step 3: build the lane INERT. The session is created, wired,
    // authorized and narrowed, but its first turn is not sent, so the group
    // record can be committed before any lane runs (step 4).
    //
    // The lane count is compose's fact (§4.2 step 3), carried by the lane it
    // hands over; the index is not a count and must never stand in for one.
    async buildLane(lane) {
      const context = [laneFraming(lane.laneCount)];
      if (typeof lane.view === "string" && lane.view.length > 0) context.push(lane.view);
      context.push(lane.prompt);
      const built = await startLane({
        name: lane.laneId,
        role: agentFor(),
        // `null` builds without a first turn (`lane.mjs` `start`).
        brief: null,
        // The group owns its lanes; the owner's roster keeps only its `fork`
        // workers, so `join` never collects an unconverged lane.
        register: false,
      });
      if (built.ok !== true) {
        // A lane whose session cannot be created did not start; the reason
        // names the host step that refused (`SESSION_CREATE_FAILED`, …).
        throw Object.assign(new Error(`lane ${lane.index} did not start: ${built.reason}`), {
          code: built.reason ?? "SPAWN_FAILED",
        });
      }
      // The prompt the lane will run is kept beside its session: `startLane`
      // sends it after the commit, and this port is the only place that holds
      // both.
      const record = { laneId: lane.laneId, index: lane.index, lane: built.lane, brief: context.join("\n\n") };
      lanes.set(lane.laneId, record);
      // §4.2 step 8: this lane's turn end is a delivery to its group. Armed
      // HERE, where the lane is built, so no later call can forget it; the
      // group owns the lane (`register: false`), so nothing else observes it.
      built.lane?.onSettled?.((ended) =>
        this.laneTurnEnd(lane.laneId, {
          name: built.lane.name ?? lane.laneId,
          agentId: built.lane.agentId ?? lane.laneId,
          text: typeof ended?.text === "string" ? ended.text : built.lane.text,
          error: ended?.error ?? null,
        }),
      );
      return { ok: true, laneId: lane.laneId };
    },
    // §4.2 step 5: the first turn of a lane whose record is already durable.
    async startLane(lane) {
      const laneId = typeof lane === "string" ? lane : lane?.laneId;
      const record = lanes.get(laneId);
      if (record === undefined) {
        throw Object.assign(new Error(`lane ${laneId} was not built`), { code: "LANE_MISSING" });
      }
      // A lane built without a turn has its first turn here; a lane whose host
      // already ran it has nothing to send and this step confirms it.
      if (record.brief === null) return { ok: true, laneId, lane: record.lane };
      const brief = record.brief;
      record.brief = null;
      record.lane.start(brief);
      return { ok: true, laneId, lane: record.lane };
    },
    async persistGroup({ groupId, owner: groupOwner, lanes: groupLanes }) {
      return { ok: true, groupId, owner: groupOwner, lanes: groupLanes.map((lane) => lane.laneId) };
    },
    // The group was rolled back before it existed: there is no record here to
    // withdraw, and saying so is not a failure.
    async discardGroup({ groupId }) {
      return { ok: true, groupId, withdrawn: false };
    },
    // The group a lane id belongs to. A lane the group never created answers
    // null, and a turn end from it feeds nothing.
    laneRecord(laneId) {
      return lanes.get(laneId) ?? null;
    },
    // §4.2 steps 8–10: one lane's turn ended, by lane id. The group is the one
    // that owns the lane, and compose decides whether this end delivered, held,
    // or announced.
    laneTurnEnd(laneId, result = null) {
      if (fanout === null) return { ok: false, reason: "NO_GROUP" };
      const record = lanes.get(laneId) ?? null;
      if (record === null) return { ok: false, reason: "LANE_UNKNOWN", laneId };
      return fanout.feedLaneEnd({
        fission: fission(),
        owner: owner(),
        record,
        groupId: groupIdOfLane(laneId),
        result,
        onEvent,
      });
    },
    // The owner's old present stops producing turns (spec §4.2 step 4).
    //
    // `fission` runs INSIDE the owner's own turn — the program that calls it is a
    // tool call of that turn — so "wait until the owner is between turns" is not
    // a wait this call can survive: the turn cannot end while its tool call is
    // still running. That is why an earlier version answered `PRESENT_BUSY` for
    // every real fission and the group was rolled back after it had started.
    //
    // Replacing a present is therefore a MARK plus two aborts, and only one of
    // them can run from inside the turn:
    //
    //   mark     the owner is replaced from this instant. A turn admitted after
    //            it starts nothing, and the turn in flight is stopped. This is
    //            what stops the old present producing new turns, whether it is
    //            idle or mid-turn when `fission` is called.
    //   stop     `control.stop()`: drop every queued turn and read the session's
    //            own liveness back. This is the part of the interrupt that is
    //            safe from inside the turn, because it awaits nothing the host's
    //            run must finish first.
    //   abort    the host's own interrupt, armed at the turn's end
    //            (`control.atTurnEnd`). It cannot run now: on the real host
    //            `AgentSession.abort` ends with `await agent.waitForIdle()`
    //            (agent-session.ts:11215), which cannot settle while the turn
    //            that is calling `fission` is still on the stack. The armed
    //            abort runs at `agent_end`, which is the host's own boundary for
    //            it (`index.mjs` drains `control.drainTurnEnd()` there).
    //
    // The receipt is `ok: false` ONLY when the mark did not land — that is, when
    // there is no control surface at all, or the surface cannot mark. A busy owner
    // is not a failure: the group exists and the mark is what makes the owner
    // stop, so answering `PRESENT_NOT_REPLACED` for it would be the false report
    // the group code turns into a rollback.
    async replacePresent({ owner: groupOwner }) {
      const target = groupOwner ?? owner();
      if (present === null) {
        return {
          ok: false,
          reason: "PRESENT_NOT_REPLACED",
          owner: target,
          detail: "this session exposes no control surface; the owner keeps its present",
        };
      }
      // The mark is the replacement. A surface that cannot mark cannot replace,
      // and that refusal is reported by name before any abort is attempted.
      let marked = null;
      if (typeof present.replace === "function") {
        try {
          marked = await present.replace();
        } catch (error) {
          marked = { ok: false, reason: "REPLACE_FAILED", detail: String(error?.message ?? error) };
        }
      } else {
        marked = {
          ok: false,
          reason: "NO_REPLACE_SURFACE",
          detail: "this session's control surface cannot mark its present as replaced",
        };
      }
      if (marked === null || marked.ok !== true) {
        return {
          ok: false,
          reason: "PRESENT_NOT_REPLACED",
          owner: target,
          detail: `the owner's present was not marked: ${marked?.detail ?? marked?.reason ?? "unknown"}`,
          replaced: marked,
        };
      }
      // The part of the stop that is safe from inside the turn. It is confirmed
      // against the session's own liveness, never against a promise: a session
      // that is still streaming here is one whose turn is this very call, and
      // that turn ends when its tool results are in.
      let settled = null;
      if (typeof present.stop === "function") {
        try {
          settled = await present.stop();
        } catch (error) {
          settled = { ok: false, reason: "STOP_FAILED", detail: String(error?.message ?? error) };
        }
      }
      // The host's own interrupt, armed for the turn's end. It is armed whether or
      // not the session reads as streaming right now: the reading is taken from
      // inside the turn that is about to end, and `atTurnEnd` runs the callback at
      // once when no turn is in flight, so the same call covers both cases and a
      // queued turn that starts between here and the boundary.
      //
      // The callback aborts ONCE and never re-arms itself: the mark already
      // refuses every turn after this one.
      let deferred = null;
      if (typeof present.atTurnEnd === "function") {
        try {
          deferred = present.atTurnEnd(() => {
            void present.silence().catch(() => {});
          });
        } catch (error) {
          deferred = { ok: false, reason: "DEFERRED_ABORT_FAILED", detail: String(error?.message ?? error) };
        }
      }
      // A surface that can neither settle its turns nor arm the boundary abort
      // has no way to stop the old present, so the receipt must not claim one.
      if (settled === null && deferred === null) {
        return {
          ok: false,
          reason: "PRESENT_NOT_REPLACED",
          owner: target,
          detail: "the owner's control surface can neither settle its turns nor arm an abort at the turn's end",
          replaced: marked,
        };
      }
      return {
        ok: true,
        owner: target,
        replaced: marked,
        settled,
        deferredAbort: deferred,
        // True when the old present really stopped now: the mark landed AND the
        // session is not streaming. A `false` here is not a refusal — the mark
        // still governs and the boundary abort is armed — it says the turn in
        // flight is the one that asked for the replacement.
        stoppedNow: settled?.stillStreaming === false,
      };
    },
    async abortLanes({ groupId, laneIds }) {
      const released = [];
      const failed = [];
      for (const laneId of laneIds) {
        const record = lanes.get(laneId);
        if (record === undefined) continue;
        lanes.delete(laneId);
        // A lane is a session this adapter created, so it can really be torn down
        // (`dispose()` on its own session). A failed teardown is reported rather
        // than counted as released.
        try {
          await record.lane.dispose();
          released.push(laneId);
        } catch (error) {
          failed.push({ laneId, reason: String(error?.message ?? error) });
        }
      }
      return { ok: failed.length === 0, groupId, released, failed };
    },
});

// `relay` (root §2.4 第 3 步). Retirement, the context barrier and the
// successor start land as ONE durable record: "三者不可分割，进程中断也不会留下半个任期".
// The receipt says `ok: true` only when all three parts really landed AND they
// landed as one record.
//
// The record is a `custom` entry (`relay-record.mjs`) published by
// `SessionManager.appendEntriesAtomically`, whose whole-journal temp-write +
// rename makes the three fields appear together or not at all. The successor
// transcript is the host's own `newSession({ parentSession })` — an in-place
// reset that writes its header durably before returning, so the participant
// keeps its identity, ledger and role, and §2.4's "the relay changes the
// person, not the milestone" holds without a second session object.
//
// Order, and why it is this way round (see `relay-record.mjs` for the full
// crash table): the record is committed FIRST, then the transcript is reset. A
// crash between the two leaves a journal that already names the successor and
// still holds the old transcript — the barrier keeps that transcript out of
// every request, so nothing is half-visible. The reverse order would leave a
// switched transcript with no record: a successor with no barrier and no
// tenure.
//
// `present` is the session's control surface (`session-control.mjs`). It must
// offer `newConversation`; a session that cannot switch transcripts has no
// successor to start, and the port refuses instead of writing a record that
// asserts one.
//
// `commit` is the durable write: `(payload) => Promise<{ok, id}>`. It is a
// parameter, not an import, so the port stays testable without a host and so a
// caller can see exactly which primitive performs the commit.
// Publish the record and RETRY only when the host kept the entry.
//
// The commit answers `recovered: true` when its atomic batch was superseded but
// the entry survived in the journal: the host carries it into the next publish,
// and a retry is idempotent for one tenure (a second record for the same tenure
// describes the same fact). `recovered: false` means the entry was rolled back,
// and no retry of that call can produce it.
//
// Measured on omp 18.14.2: at the terminal `agent_end` the first attempt is
// routinely superseded (the host's commit guard is dropped when the run moves
// toward dispose), while the entry stays in the journal; the retry lands it.
const RECOMMIT_ATTEMPTS = 2;

const commitVerified = async ({ commit, payload }) => {
  let last = null;
  for (let attempt = 0; attempt < RECOMMIT_ATTEMPTS; attempt += 1) {
    last = await commit(payload);
    if (last?.ok === true) return { ok: true, id: last.id ?? null, attempts: attempt + 1 };
    // A rolled-back entry cannot be revived by retrying this call. The host's
    // `unavailable` flag (no atomic publish at all) travels with it: it is a
    // different fact from a refused publish, and the caller names it separately.
    if (last?.recovered !== true) {
      return {
        ok: false,
        id: null,
        detail: last?.detail ?? "the publish did not land",
        unavailable: last?.unavailable === true,
        attempts: attempt + 1,
      };
    }
  }
  return { ok: false, id: null, detail: last?.detail ?? "the publish did not land", attempts: RECOMMIT_ATTEMPTS };
};

export const handOverPort = ({
  present = null,
  commit = null,
  defer = null,
  fenceThrough = () => 0,
  carried = null,
  carriedDurable = null,
  retain = null,
} = {}) => ({
  async handOver(decision) {
    if (decision?.retire !== true || decision?.openSuccessor !== true || decision?.barrier !== true) {
      return { ok: false, retired: false, barrier: false, successor: null, reason: "INCOMPLETE_RELAY" };
    }
    if (present === null || typeof present !== "object") {
      return {
        ok: false,
        retired: false,
        barrier: false,
        successor: null,
        reason: "NO_SESSION_CONTROL",
        detail: "this session exposes no control surface; retirement, barrier and successor cannot land as one record",
      };
    }
    // The two capabilities come before the busy check: a host that cannot
    // publish the record, or cannot start a successor at all, is refused for
    // THAT reason whatever the incumbent's turn state is. Reporting "busy"
    // would tell a caller to wait for something that would still not work.
    if (typeof commit !== "function") {
      return {
        ok: false,
        retired: false,
        barrier: false,
        successor: null,
        reason: "NO_ATOMIC_RECORD",
        detail:
          "the host exposes no atomic entry publish (SessionManager.appendEntriesAtomically); three separate writes " +
          "would leave half a tenure after a crash, so none is performed",
      };
    }
    if (typeof present.newConversation !== "function") {
      return {
        ok: false,
        retired: false,
        barrier: false,
        successor: null,
        reason: "NO_SESSION_CONTROL",
        detail: "this session cannot start a successor conversation (no newSession); refusing to record one that does not exist",
      };
    }
    // The successor must be able to take control. Two different refusals live
    // here and must not be merged: a session that CANNOT switch (no
    // `newSession` on any reachable object) is a defect of the surface, while a
    // session that is busy will work later. Reporting the first as
    // `RELAY_BUSY`/`NO_NEW_SESSION` is what made the real host look like it was
    // merely mid-turn — it was not, and no retry could ever have succeeded.
    const busy = present.reason();
    if (busy === "NO_NEW_SESSION" || busy === "NO_SESSION") {
      return {
        ok: false,
        retired: false,
        barrier: false,
        successor: null,
        reason: "NO_SESSION_CONTROL",
        busy,
        detail:
          "this session exposes no object that can start a successor conversation " +
          "(`newSession` on the session or on its session manager); refusing to record a successor that cannot exist",
      };
    }
    // Busy because the incumbent is mid-turn is NOT a refusal: a tool call
    // always runs with its prompt in flight (`isStreaming` is
    // `agent.state.isStreaming || #promptInFlightCount > 0`), so this is the
    // NORMAL relay case, not an exceptional one. The relay is deferred: the
    // intent is recorded durably here, the receipt says `deferred: true`, and
    // `performDeferred` runs at the host's idle boundary.
    //
    // A transition or queued work is different: neither clears by itself the
    // moment this turn ends, and a deferred intent would then race the very
    // transition it is waiting for. Those stay refusals.
    // The fence counts the messages already on screen, so an operator message
    // that arrives AFTER the relay is what the successor answers (§2.4 第 4 步).
    const through = Math.max(0, Number.isInteger(fenceThrough()) ? fenceThrough() : 0);
    const deferred = busy === "SESSION_STREAMING";
    if (busy !== null && !deferred) {
      return {
        ok: false,
        retired: false,
        barrier: false,
        successor: null,
        reason: "RELAY_BUSY",
        busy,
        detail: `the incumbent cannot switch transcripts while ${busy}; the relay is retried when it settles`,
      };
    }
    if (deferred) {
      if (typeof defer !== "function") {
        // A port without `defer` is the DEFERRED attempt itself: it is only
        // called once the host is idle, so reaching this branch means the
        // session turned busy again (a new prompt arrived in between). That is
        // transient, and the caller keeps the intent for the next boundary.
        return {
          ok: false,
          retired: false,
          barrier: false,
          successor: null,
          reason: "RELAY_BUSY",
          busy,
          detail: `the session is streaming again (${busy}); the deferred relay waits for the next idle boundary`,
        };
      }
      // The record is COMMITTED here, in the live turn, because this is the last
      // moment the host accepts a write: at the terminal `agent_end` the manager
      // is already sealed (measured on 18.14.2 — both the atomic and the plain
      // append return an id and land nowhere). So the turn publishes the record
      // itself, through the same verified commit the synchronous path uses, and
      // the intent that follows names it.
      //
      // Publishing before the intent is the order that survives a crash in
      // between: a record with no intent is a succession whose switch has not
      // run yet, and the fence it carries already hides the incumbent's
      // conversation — the safe direction. The reverse would leave an intent
      // pointing at nothing.
      let prepared =
        typeof present.relayPayload === "function" ? present.relayPayload({ fenceThrough: through }) : null;
      if (prepared !== null) prepared = { ...prepared, [PUBLISHED_FIELD]: true };
      let published = null;
      if (prepared !== null) {
        try {
          published = await commitVerified({ commit, payload: prepared });
        } catch (error) {
          published = { ok: false, detail: String(error?.message ?? error) };
        }
        if (published?.ok !== true) {
          // A record that did not land is not carried as if it had. The intent
          // is still recorded — the obligation survives — but the receipt says
          // the record itself was refused, and the idle boundary must publish a
          // record of its own (or report that it cannot).
          //
          // A host with NO atomic publish is a different fact from a publish
          // that was refused: it will never succeed, at any boundary. It is
          // named here, before anything is written, so the receipt does not
          // blame the record for a surface that cannot publish at all.
          if (published?.unavailable === true) {
            return {
              ok: false,
              retired: false,
              barrier: false,
              successor: null,
              reason: "NO_ATOMIC_RECORD",
              busy,
              detail: published.detail ?? "the host exposes no atomic entry publish",
              recordPublished: false,
              recordId: null,
            };
          }
          published = { ok: false, detail: published?.detail ?? "the atomic publish reported no commit" };
          prepared = null;
        }
      }
      let intent;
      try {
        // The decision's own findings travel with the intent: they are what
        // emptied the ledger, and the deferred step must be able to verify the
        // obligation still stands before it retires anyone.
        intent = await defer({
          findings: decision.findings ?? null,
          record: prepared,
        });
      } catch (error) {
        return {
          ok: false,
          retired: false,
          barrier: false,
          successor: null,
          reason: "RELAY_DEFER_FAILED",
          busy,
          detail: String(error?.message ?? error),
          // The record may already be durable. Saying so is not success: the
          // tenure has not been switched to, and the ledger has not advanced.
          recordPublished: published?.ok === true,
          recordId: published?.ok === true ? (published.id ?? null) : null,
        };
      }
      if (intent?.ok !== true) {
        return {
          ok: false,
          retired: false,
          barrier: false,
          successor: null,
          reason: "RELAY_DEFER_FAILED",
          busy,
          detail: intent?.detail ?? "the deferred intent was not recorded",
          // Why the intent could not be written, when it was the record that
          // could not be published first. Without it the caller cannot tell a
          // missing primitive from a full disk.
          cause: published?.ok === true ? null : (published?.unavailable === true ? "NO_ATOMIC_RECORD" : "RELAY_COMMIT_FAILED"),
          recordPublishFailure: published?.ok === true ? null : (published?.detail ?? null),
          recordPublished: published?.ok === true,
          recordId: published?.ok === true ? (published.id ?? null) : null,
        };
      }
      return {
        ok: false,
        retired: false,
        barrier: false,
        successor: null,
        reason: "RELAY_DEFERRED",
        busy,
        deferred: true,
        // The receipt is honest about the state: nothing was retired, no
        // barrier is installed and no successor exists yet. `pendingId` names
        // the durable intent a restart would resume.
        pendingId: intent.id ?? null,
        // What IS durable: the record. It is not a tenure yet — the ledger, the
        // fence and the transcript move at the idle boundary — but a reader of
        // this receipt can tell a record that landed from one that did not.
        recordPublished: published?.ok === true,
        recordId: published?.ok === true ? (published.id ?? null) : null,
        recordPublishFailure: published?.ok === true ? null : (published?.detail ?? null),
        // What the caller must DO. The relay is decided and durable; there is
        // no further work this turn can do toward it, and the switch runs only
        // after the turn ends. Measured on 18.14.2: a receipt that reported the
        // record without this invited the model to keep working — 116 `fork`
        // calls in one turn — because nothing said the decision was complete.
        next: "STOP",
        detail:
          "the incumbent is mid-turn, and a session cannot switch transcripts while its own turn is live. " +
          "The record was " +
          (published?.ok === true
            ? "committed and the transcript switch is pending; it runs automatically at the end of this turn. " +
              "The relay is decided: end your turn now and report this receipt. Do not start new work, do not fork, " +
              "and do not retry the relay"
            : `NOT committed (${published?.detail ?? "no atomic publish"}); the intent is on disk and the idle boundary retries. ` +
              "Report this failure; do not retry it in this turn") +
          ". The transcript has not been switched yet.",
      };
    }
    const payload = typeof present.relayPayload === "function"
      ? present.relayPayload({ fenceThrough: through })
      : null;
    if (payload === null) {
      return {
        ok: false,
        retired: false,
        barrier: false,
        successor: null,
        reason: "NO_SESSION_CONTROL",
        detail: "the control surface cannot describe the record to write",
      };
    }
    // The record may already be durable: the live turn published it, because
    // this host seals the manager at the terminal `agent_end` and nothing can be
    // published here. When the turn's commit really landed, its record is the
    // one §5.3.2 wants, and re-publishing it from a surface whose transcript
    // count describes a moment it can no longer record would describe a
    // DIFFERENT boundary. So the carried record is used as written.
    //
    // It is not trusted on the intent's word: `carriedDurable` answers the id of
    // the journal entry that holds the record, or null when the journal does not
    // hold it. A prepared draft is not evidence of anything, and for one the
    // boundary publishes a record of its own.
    const carriedEntryId =
      carried !== null && typeof carriedDurable === "function" ? carriedDurable(carried) : null;
    const useCarried = typeof carriedEntryId === "string" && carriedEntryId.length > 0;
    const carriedId = useCarried ? carriedEntryId : null;
    const committedPayload = useCarried ? carried : payload;
    // ONE commit, verified. A rolled-back publish leaves the journal exactly as
    // it was and is NOT retried (the entry does not exist); a publish that was
    // superseded while the journal kept the entry is retried once, because the
    // host carries that entry into the next publish. Measured on 18.14.2: the
    // terminal `agent_end` commit is routinely superseded while its entry stays
    // in the journal, so without this the record never became durable.
    let written = useCarried
      ? { ok: true, id: null, carried: true }
      : null;
    if (!useCarried) {
      try {
        written = await commitVerified({ commit, payload: committedPayload });
      } catch (error) {
        return {
          ok: false,
          retired: false,
          barrier: false,
          successor: null,
          reason: "RELAY_COMMIT_FAILED",
          detail: String(error?.message ?? error),
        };
      }
    }
    if (written?.ok !== true) {
      // A host with NO atomic publish is a different fact from a publish that
      // failed: the first is a wiring defect that will never succeed, the second
      // may. They must not share one reason, or a permanently broken surface
      // looks like a transient write error and gets retried forever.
      const unavailable = written?.unavailable === true;
      return {
        ok: false,
        retired: false,
        barrier: false,
        successor: null,
        reason: unavailable ? "NO_ATOMIC_RECORD" : "RELAY_COMMIT_FAILED",
        detail: written?.detail ?? "the atomic publish reported no commit",
      };
    }
    // The record is durable; only now does the incumbent give up its
    // transcript. A refusal here is reported as what it is, and the fence the
    // record established still stands: the old conversation stays hidden from
    // the successor's requests even though its transcript was not replaced.
    const switched = await present.newConversation();
    // The reset replaces the transcript, so the record that names this successor
    // is no longer in the file it now writes to. It is written again into the
    // successor's own journal, with the same fields, so a crash after this point
    // still leaves a successor whose fence and tenure can be rebuilt from the
    // transcript alone. A failure here is reported, not hidden: the tenure is
    // already committed in the previous transcript and in the ledger file, so
    // the record still exists — just not in the newest file.
    //
    // A re-commit that does not land is a REFUSAL, not a footnote. Measured on
    // omp 18.14.2 (`/tmp/hc-devops-final/harness`): at the terminal `agent_end`
    // the host can discard an in-flight atomic publish and roll its entry back
    // (`session-storage.ts` returns normally on a failed commit guard,
    // `#appendEntriesAtomicallyLocked` then throws "superseded"). An earlier
    // version recorded that as `recordInSuccessorTranscript: {ok:false}` and
    // still answered `ok:true`, so the tenure advanced with no record anywhere.
    // §2.4 第 3 步 needs the three facts to exist, not to have been attempted.
    let recommitted = null;
    // The successor's copy when the host could not take it (declared outside the
    // branch: the receipt reports it whether or not the switch replaced a file).
    let retained = null;
    if (switched?.ok === true && switched?.replaced !== false) {
      // `fenceThrough` is a boundary IN A TRANSCRIPT, so the successor's copy
      // starts it at 0: its file is empty, and a count carried over from the
      // incumbent's file would be a claim about messages that file never had.
      // The successor's own first operator message is what the barrier keeps.
      // `successorSessionId` must name the session the record now sits in, or
      // `recordGoverns` would refuse it in its own transcript: the reset mints a
      // NEW session id, and the pre-switch record carried the OLD one. The
      // surface reads the current id after the reset, so the copy takes it from
      // there rather than reusing the stale value.
      //
      // It is also the copy that is NOT retired: `retiredTranscript: false` is
      // the writer's own statement about where this record sits, which is the
      // fact `recordGoverns` reads instead of comparing paths.
      //
      // The copy is derived from the record that really landed, not rebuilt
      // from the surface: the surface describes the moment the record was
      // published, and this host seals the manager before the reset, so a fresh
      // payload here would describe a moment nobody can verify. Only the facts
      // the reset itself changes are rewritten (`successorCopy`).
      const successorPayload = successorCopy(committedPayload, {
        successorSessionId:
          typeof present.sessionId === "function" ? (present.sessionId() ?? null) : null,
        fenceThrough: 0,
      });
      try {
        recommitted = await commitVerified({ commit, payload: successorPayload });
      } catch (error) {
        recommitted = { ok: false, detail: String(error?.message ?? error) };
      }
      // The host cannot always take the successor's copy: on omp 18.14.2 the
      // manager is sealed by the time the switch runs, so every publish here is
      // superseded (measured). The tenure is not lost for that reason — the
      // record is already durable in two places (the transcript the turn wrote,
      // and this adapter's own journal). `retain` writes the successor's copy to
      // the adapter-owned journal, which root §1.3 licenses for exactly this
      // kind of cross-process fact. It is reported separately from the host
      // publish, so a reader can tell which copy exists.
      if (recommitted.ok !== true) {
        if (typeof retain !== "function") {
          return {
            ok: false,
            retired: true,
            barrier: true,
            successor: null,
            reason: "RELAY_RECORD_NOT_DURABLE",
            detail:
              "the successor transcript does not carry the relay record, so the tenure it opened cannot be rebuilt after a restart. " +
              "Retirement and the barrier still stand; the record was not written.",
            successorDetail: {
              sessionId: switched?.sessionFile ?? null,
              previousSessionFile: switched?.previousSessionFile ?? null,
              replaced: true,
              recordInSuccessorTranscript: recommitted,
            },
          };
        }
        try {
          retained = await retain(successorPayload);
        } catch (error) {
          retained = { ok: false, detail: String(error?.message ?? error) };
        }
        if (retained?.ok !== true) {
          // Neither copy exists. This IS the refusal: the tenure cannot be
          // rebuilt, and claiming otherwise would be the false success §2.4 第 3 步
          // forbids.
          return {
            ok: false,
            retired: true,
            barrier: true,
            successor: null,
            reason: "RELAY_RECORD_NOT_DURABLE",
            detail:
              "neither the successor transcript nor the adapter's own journal carries the relay record, so the tenure it opened " +
              "cannot be rebuilt after a restart. Retirement and the barrier still stand.",
            successorDetail: {
              sessionId: switched?.sessionFile ?? null,
              previousSessionFile: switched?.previousSessionFile ?? null,
              replaced: true,
              recordInSuccessorTranscript: recommitted,
              recordInAdapterJournal: retained,
            },
          };
        }
      }
    }
    return {
      ok: true,
      retired: true,
      barrier: true,
      successor: {
        tenure: committedPayload.tenure,
        sessionId: switched?.sessionFile ?? null,
        previousSessionFile: switched?.previousSessionFile ?? null,
        // `replaced: false` means the transcript was not swapped. The record is
        // committed, so the tenure did advance; this field says the successor is
        // still answering from the old transcript, which the barrier hides.
        replaced: switched?.ok === true && switched?.replaced !== false,
        switchFailure: switched?.ok === true ? null : (switched?.reason ?? "NEW_SESSION_FAILED"),
        recordInSuccessorTranscript: recommitted,
        // Where the successor's copy really lives when the host could not take
        // it. `null` means the host's own publish carried it.
        recordInAdapterJournal: retained,
      },
      // The record AS WRITTEN, echoed whole. `SessionState.handOver` installs
      // the fence from these fields, and reading `fenceThrough` from anywhere
      // else (a fresh computation, or `0` as a default) would install a fence
      // that disagrees with the one a later start rebuilds from the transcript.
      // The fence the LIVE process installs. When the transcript was replaced,
      // the boundary is the successor's file, which starts empty: `fenceThrough`
      // is 0 there, and the successor's first operator message is what survives.
      // When the switch did not happen, the incumbent's file is still live, so
      // the count written before the attempt is the real boundary.
      record: {
        id: written.id ?? carriedId ?? null,
        tenure: committedPayload.tenure,
        milestone: committedPayload.milestone,
        fenceThrough: switched?.ok === true && switched?.replaced !== false ? 0 : committedPayload.fenceThrough,
        notice: committedPayload.notice,
        // The id the SUCCESSOR now answers under. The pre-switch payload names
        // the id this session had BEFORE the reset, which is the incumbent's;
        // the host mints a new one, and the ledger file (and the record that a
        // restart reads) must follow the successor. Reading it back from the
        // surface after the switch is the only source that has it.
        successorSessionId:
          switched?.ok === true && switched?.replaced !== false && typeof present.sessionId === "function"
            ? (present.sessionId() ?? committedPayload.successorSessionId)
            : committedPayload.successorSessionId,
        previousSessionFile: committedPayload.previousSessionFile,
        transcriptReplaced: switched?.ok === true && switched?.replaced !== false,
      },
    };
  },
});

// Run a relay that was DEFERRED (`RELAY_DEFERRED`) once the session is idle.
//
// This is the same commit-then-reset step the synchronous path performs; the
// only difference is WHEN it runs. The ordering is unchanged and so is its
// crash behaviour: the record is published first, and only then is the
// transcript reset (`relay-record.mjs`).
//
// The wait for idleness is the HOST's own boundary, not a timer this adapter
// invents: the caller (the `agent_end` hook) already knows the turn is over. If
// the session turns out to be busy anyway — a new prompt arrived in between —
// the port answers `RELAY_BUSY` and the intent stays on disk, so the next idle
// boundary tries again rather than losing the relay.
//
// `attempt` performs one attempt and answers a receipt; it is supplied by the
// caller so the idle boundary and the restart recovery share exactly one
// implementation.
export const deferredRelay = ({ attempt }) => ({
  async perform(decision) {
    if (typeof attempt !== "function") {
      return { ok: false, reason: "RELAY_NOT_DEFERRABLE", detail: "no attempt implementation was supplied" };
    }
    const done = await attempt(decision);
    // A deferred attempt is never reported as a fresh success: `deferred` marks
    // that this answer came from the scheduled step, so a caller reading a
    // receipt can tell "the relay happened now" from "the relay was requested".
    return { ...(done ?? {}), deferred: true };
  },
});

// `control` (spec §3.3): `unpark`. The port delegates to the session state,
// which owns the program registry and the observation ledgers. The controlling
// request's deadline travels with the resume.
//
// After core accepted the resume, the parked member call is released with the
// value core decided (`resumeValue`): the body continues at the park site. The
// order matters — a promise settled before core's checks would resume a program
// whose control the caller does not hold.
export const controlPort = (state, parks) => ({
  unpark: ({ controllerId, name, value, deadline = null }) => {
    const resumed = state.unpark(controllerId, name, value, deadline);
    // §3.3: the resumed run continues on the CONTROLLING request's deadline
    // (`takeSite` sets it). The tool gate below reads the deadline off the
    // program's receipt and needs the same object the program now holds; a
    // host-side `Deadline` created beside it would be a second clock that
    // expires at a different moment.
    if (deadline !== null) {
      const resumedProgram = state.programs.get(name);
      if (resumedProgram !== null && resumedProgram !== undefined) resumedProgram.attachDeadline(deadline);
    }
    // Core attaches the resumed expression's value as a NON-enumerable field
    // (`core/receipt.mjs` `withExtra`), so `JSON.stringify` of the receipt drops
    // it and a controller would read `value: undefined`. The member's answer is
    // built from this object, and the controller has to see the value the
    // resumed expression actually received, so the key is made enumerable here.
    if (Object.prototype.hasOwnProperty.call(resumed, "value")) {
      Object.defineProperty(resumed, "value", {
        value: resumed.value,
        enumerable: true,
        configurable: true,
        writable: true,
      });
    }
    if (parks !== null && parks !== undefined) parks.settle(name, resumed.value);
    // The program left `parked` for `running` when core consumed its site
    // (`takeSite`); nothing else moves it, and a program left parked would make
    // every later member call of the resumed body suspend again.
    const program = state.programs.get(name);
    if (program !== null && program !== undefined && program.state === "parked" && resumed.site !== null) {
      program.moveTo("running");
    }
    return resumed;
  },
});
