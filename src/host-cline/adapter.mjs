// The Cline adapter: tools, hooks and ports for one host session (spec §7.2).
//
// `setup(api, ctx)` receives the host's registration API and the session's setup
// context. This module owns one `SessionState` (built by `index.mjs` so the
// operator seam can record on it first), registers the six tools, the request
// rewrite and the role prompt, and returns the hook bag.
//
// Every tool name is registered once. The host dedupes contributions by
// `tool.name` (`session-runtime-orchestrator.ts` keeps a Map keyed by name, the
// later registration wins), so `js-worker` is not registered per role: the role
// is decided per call from `context.agentId`.
//
// Nothing here pretends. A program runs real JavaScript; a member that parks
// answers with the park receipt; a host capability the plugin cannot reach is a
// named refusal (`HostCapabilityMissing`, `HOST_HAS_NO_SESSION_CREATE`), never a
// success receipt.

import { REVIEW_WINDOW_CLOSED, ROLES, ProgramSurface, surfaceNameFor } from "../compose/program/surface.mjs";
import { systemPromptFor } from "../compose/prompt/projection.mjs";
import { Deadline, systemClock } from "../core/clock.mjs";

import { ProgramCall, compile } from "./execution.mjs";
import { SessionState } from "./session.mjs";
import { MANAGER_FORBIDDEN, createHooks, reviewFor } from "./hooks.mjs";
import { withScope } from "./scope-schema.mjs";
import { forkRequest } from "../compose/program/role-brief.mjs";
import { REQUEST_STAGE, canRewrite, rolePrompt, systemPromptRule } from "./rewrite.mjs";

// Every tool this adapter registers is non-exempt (spec §6.2: only `negotiate`
// and `askPreviousScope` are, and neither is a tool here), so every schema names
// `scope`. `additionalProperties: false` stays: the host validates arguments
// against this schema before `beforeTool` runs, so a closed object without
// `scope` would refuse every compliant call before admission could ever see it.
const CODE_SCHEMA = withScope({ code: { type: "string" } });

const TOOL_FORK = [
  "Start a worker with an independent, self-contained task description (`verboseIntent`) and return immediately.",
  "Two arguments: `name` and `verboseIntent`. The text must declare the worker's identity once,",
  "as `role: coder` or `role: devops`; text that names neither, or both, is refused.",
  "A coder does file work and must be told not to use bash; a devops owns real commands, one at a time.",
  "Reusing a live worker name steers that worker instead of starting a new session;",
  "the receipt says which happened (steered: true/false). Do not infer it from the name.",
  "A steer enters the worker's running turn when the host has that channel, and starts its next turn otherwise.",
].join(" ");

const TOOL_JOIN = [
  "Wait for the first worker that finishes and return its record.",
  "Takes no arguments. Report honestly when nothing is pending.",
].join(" ");

const invalid = (message) => ({ ok: false, rejection: "INVALID_ARGUMENT", message });

// The capability set a program is registered with (根 §3.3). A controller must
// hold every capability its TARGET carries, so the sets say who may resume whom:
//
//   plan      `["plan"]` — a Plan resumes its own `ask` park and nothing else.
//   manager   `["manager", "coder", "devops", "js-review"]` — the Manager may
//             resume its own programs AND a worker program's park, because a
//             worker is its to command.
//   worker    `[role, "coder", "devops"]` — a worker resumes its own programs
//             and a sibling worker's, never the Manager's or a Plan's.
//
// The sets are read from the role the program runs as, never from the caller's
// own claims: a controller cannot lower the bar by omitting its set (core reads
// the target's set, not the controller's).
export const CAPABILITIES_FOR = Object.freeze({
  [ROLES.PLAN]: Object.freeze(["plan"]),
  [ROLES.MANAGER]: Object.freeze(["manager", "coder", "devops", "js-review"]),
  [ROLES.CODER]: Object.freeze(["coder", "devops"]),
  [ROLES.DEVOPS]: Object.freeze(["devops", "coder"]),
});

const capabilitiesOf = (role) => CAPABILITIES_FOR[role] ?? Object.freeze([role]);

// `role` is the session's own role: the host runs one session per plugin load,
// and a Plan session and a Manager session are different top-level roles with
// different ledgers (spec §6, §7).
// `state` is the session this adapter owns. `index.mjs` builds it first, because
// the operator seam's prompt channel records the question on it and the seam
// must exist before the adapter does. A caller that passes none gets one.
export function createAdapter({ ctx = {}, operator = null, role = ROLES.MANAGER, state = null } = {}) {
  const session = state ?? new SessionState({ ctx, operator, role });
  // The extension object is filled by `index.mjs` after this adapter exists; the
  // manager-session factory reads it lazily, so a hand-off started later still
  // forwards the real extension.
  const extensionRef = { current: null };
  // Which entry the request rewrite took: the reading `attachExtension` makes,
  // because the hook bag the host calls is the plugin object itself.
  let rewrite = { registered: false, reason: "NOT_ATTEMPTED" };
  session.attachExtension = (extension) => {
    extensionRef.current = extension;
    session.setExtension(extension);
    rewrite = canRewrite(extension)
      ? { registered: true, name: REQUEST_STAGE.ENTRY, callSite: REQUEST_STAGE.HOST_CALL_SITE }
      : { registered: false, reason: "BEFORE_MODEL_UNAVAILABLE" };
  };

  const hooks = createHooks({ session });

  // A session this adapter started says so in its metadata; read it once, before
  // any hook runs, so the ledger and the prompt match the session's role. The
  // host may not answer (`getSession` missing), and then the role this plugin was
  // created with stands.
  const identity = session.resolveRole().then(
    (decided) => {
      session.adoptRole(decided.role);
      return decided;
    },
    () => ({ role, source: "unreadable" }),
  );

  function register(api) {
    const registered = [];
    const record = (tool) => {
      api.registerTool(tool);
      registered.push(tool);
      return tool;
    };
    // A worker's role is decided per call. A top-level session that asks for
    // `js-worker` gets the worker surface too: `hooks.beforeTool` is the one gate
    // that refuses it, exactly as root §7.2 requires ("可见集合不是执行授权").
    record(programTool({ name: "js-manager", role: ROLES.MANAGER }));
    record(programTool({ name: "js-worker", role: null }));
    // Plan is a top-level session of its own (根 §2.2): never created by `fork`,
    // outside the five `skillDocuments`, with one product (the draft). Its face is
    // a separate tool so the gate can tell the two top-level roles apart.
    record(programTool({ name: "js-plan", role: ROLES.PLAN }));
    record(reviewTool());
    record(forkTool());
    record(joinTool());
    // A delegated runtime gets the worker face, not the Manager's: `js-worker`
    // is the programming face. `js-review` is the Manager's evaluation face and
    // `hooks.beforeTool` refuses it for coder and devops, so handing it to a
    // worker would put a tool it can never use in its list.
    session.noteTools(registered.filter((tool) => tool.name === "js-worker"));
    // 根 §7.2: the request rewrite IS the `beforeModel` hook's `messages`
    // (`hooks.mjs`). The host applies it to this request only and never to the
    // transcript (`agent-runtime.ts:1700-1711`); a `registerMessageBuilder`
    // registration never runs here, because the host's own `prepareTurn` owns
    // that stage (`rewrite.mjs` records the measurement). It is read after
    // `attachExtension`, because the hook bag the host calls IS the plugin
    // object (`wrapHooks`), and the reading is reported, not assumed.
    // 根 §5: the role prompt is a RULE, because a hook cannot replace the system
    // prompt on this host — `beforeModel`'s `systemPrompt` is dropped
    // (`agent-runtime.ts:1700-1719`), while registered rule content is appended
    // to every request (`session-runtime-orchestrator.ts:754-770`). The rule
    // reads the session's role on each request, so an operator-started session
    // carries the Manager prompt from its first request instead of only sessions
    // this adapter started. A worker's prompt is its delegated runtime's own
    // `prompt`, so the rule does not apply to it.
    api.registerRule(systemPromptRule({ prompt: () => rolePrompt(session.declaredRole) }));
    // A Plan session is told where its draft lives; the path is derived, not
    // chosen by the model (spec §6: "路径由任务固定派生，系统告知确切路径").
    // The rule is scoped to `js-plan`, so a Manager session that never plans does
    // not carry it.
    api.registerRule({
      id: "herdchestra-plan-draft",
      source: "herdchestra",
      whenToolAvailable: "js-plan",
      // The path, and the two facts a model otherwise learns by failing: the
      // directory already exists (write the file, do not invent a sibling), and
      // a member has one spelling only — `this.write(...)` (§3.1), so a bare
      // `write(...)` is a ReferenceError, not a second way in.
      //
      // A directory that could not be prepared is stated with its reason: a
      // write would fail with ENOENT, and the cause is the directory, not the
      // path the model was given.
      content: () => {
        const failed = session.planDraftDirFailure;
        return [
          `Plan's draft for this session: ${session.planDraftFile}`,
          failed === null
            ? "Write it with `this.write(path, content)`; the directory already exists."
            : `Its directory could NOT be prepared (${failed.reason}: ${failed.detail}); \`this.write\` will fail until an operator fixes ${failed.directory}.`,
          "The draft is the task's only output and lives under `plans/<name>.md` — never under `.Herdchestra/`, which is the adapter's own state. Plan is read-only except this one file and the operator's attachments.",
          "A member has one spelling: `this.write(...)`, `this.cat(...)`. The top-level primitives are bare: `review(...)` and `devops(verboseIntent)`. There is no `this.devops`, no `tools.` and no `plan.` namespace.",
        ].join("\n");
      },
    });
  }

  // The name one physical tool call runs under.
  //
  // The host's tool-call id IS the call's identity (§2.4 第 6 步): the program
  // surface passes it to `review` as `callId`, so a replayed host call returns
  // the receipt it already produced instead of relaying twice. A context that
  // carries no id therefore must NOT borrow the tool's name as one — every
  // `js-manager` call would share the id `js-manager` and the second real review
  // would be answered as a replay of the first. Such a call gets a fresh name,
  // which is its own identity and nothing else's: it books, it never replays.
  //
  // The name is also the program name, and a program name is unique per session
  // (`ProgramRegistry.begin`), so a fresh name cannot collide with a live one.
  let anonymousCalls = 0;
  const callName = (toolName, context) => {
    const id = context?.toolCallId;
    if (typeof id === "string" && id.length > 0) return id;
    anonymousCalls += 1;
    return `${toolName}#${anonymousCalls}`;
  };

  function programTool({ name, role }) {
    return {
      name,
      description:
        role === null
          ? "Run a JS program against the worker surface (js-worker)."
          : `Run a JS program against the ${role} surface (${surfaceNameFor(role)}).`,
      inputSchema: CODE_SCHEMA,
      timeoutMs: 600_000,
      execute: async (input, context) => {
        const decided = role ?? session.roleFor(context);
        return runProgram({
          role: decided,
          name: callName(name, context),
          code: input?.code,
          agentId: context?.agentId ?? null,
        });
      },
    };
  }

  function reviewTool() {
    return {
      name: "js-review",
      description: "Read-only program surface for the Manager's evaluation phase.",
      inputSchema: CODE_SCHEMA,
      execute: async (input, context) =>
        runReview({ name: context?.toolCallId ?? "js-review", code: input?.code }),
    };
  }

  function forkTool() {
    return {
      name: "fork",
      description: TOOL_FORK,
      // Spec §4.1: `fork(name, verboseIntent)`. The identity has no argument of its own;
      // the verboseIntent declares it, and `forkRequest` refuses text that does not
      // decide, so this tool cannot default anyone to coder.
      inputSchema: withScope(
        { name: { type: "string" }, verboseIntent: { type: "string" } },
        ["name", "verboseIntent"],
      ),
      execute: async (input) => {
        if (typeof input?.name !== "string" || input.name.length === 0) return invalid("name is required");
        if (typeof input?.verboseIntent !== "string" || input.verboseIntent.trim().length === 0) {
          return invalid("verboseIntent is required");
        }
        let request;
        try {
          request = forkRequest(input.name, input.verboseIntent);
        } catch (error) {
          return {
            ok: false,
            rejection: error.details?.reason ?? error.code ?? "INVALID_ARGUMENT",
            message: error.message,
          };
        }
        return session.workers.fork({
          name: request.name,
          verboseIntent: input.verboseIntent,
          role: request.role,
        });
      },
    };
  }

  function joinTool() {
    return {
      name: "join",
      description: TOOL_JOIN,
      // `join` takes no arguments of its own, but it is not exempt: the beat it
      // settles is the model's, so the schema must still let the model write the
      // annotation (spec §6.2).
      inputSchema: withScope({}),
      execute: async () => session.workers.join(),
    };
  }

  // One call is one program: compile, run, answer by the program's own state
  // (spec §3.3). The deadline is fixed at admission from the bytes this call
  // actually has: `CODE_SCHEMA` carries `code` and `scope` and nothing else,
  // and the host hands a tool call no request text, so the program source is
  // the only "intent" the adapter can measure (`Deadline.forIntent`, 50 ms per
  // UTF-8 byte). A future schema that carries the request's own `intent` should
  // pass THAT text here instead.
  //
  // One spelling per capability (§3.1): `compile` binds the members as the
  // body's `this` and hands the primitives (`fork`, `join`, `review`, and Plan's
  // `devops`) over as bare parameters, so a member has no second spelling and a
  // primitive has no `this.` form.
  async function runProgram({ role, name, code, agentId }) {
    if (typeof code !== "string" || code.trim().length === 0) return invalid("code must be a non-empty string");
    const deadline = Deadline.forIntent(systemClock, code);
    let program;
    try {
      // Core §3.3: a controller must hold every capability the TARGET carries,
      // and the target's set is a fact of the program. A program is registered
      // with the set its role implies (`CAPABILITIES_FOR`), so `unpark` passes
      // exactly when the controller outranks or equals the target: a Plan
      // resumes its own `ask` park, a Manager resumes its own programs and its
      // workers', and a worker resumes a sibling worker's. Without this the set
      // would be empty, every controller would "hold" it, and the check would
      // mean nothing.
      program = session.registry.begin(name, { deadline, capabilities: [...capabilitiesOf(role)] });
    } catch (error) {
      return { ok: false, rejection: error.code ?? "NAME_TAKEN", message: error.message };
    }
    // The call is built first so its park channel can be handed to the surface,
    // and the surface is then attached to it: the surface is what describes the
    // continuation to a controller that resumes this program later (根 §3.3).
    // `park` is a closure, so it reaches `call` only when a member parks — long
    // after both bindings exist.
    const call = new ProgramCall({ program, parks: session.parks, registry: session.registry });
    const built = session.surface({
      role,
      name,
      program,
      // 根 §3.3: a receipt's `source` is the full program, so the surface is
      // built with the text that runs.
      source: code,
      park: (site) => call.park(site),
    });
    call.attachSurface(built.surface);
    const surface = built.surface;
    const members = built.members;
    session.control.open(name);
    // A program name belongs to the session, not to one call: a later call may
    // name a parked program to resume it, and `unpark` requires the controller
    // to have observed the target in this request (spec §3.3). The call
    // therefore observes every live program of the session, itself included.
    for (const live of session.registry.list()) session.control.observe(name, live.name);
    let result;
    try {
      // A body that does not COMPILE is still a call that ENDED. Without this
      // guard a syntax error escaped `execute` after the registry and the
      // control ledger were opened: the name stayed live, the host saw a
      // rejected tool call, and a replay of the same call id answered
      // `NAME_TAKEN`.
      const fn = compile(code, Object.freeze({ ...members }));
      result = await call.run(() => fn());
    } catch (error) {
      const failure = {
        name: error?.name ?? "Error",
        message: String(error?.message ?? error),
        code: typeof error?.code === "string" ? error.code : "PROGRAM_COMPILE_FAILED",
      };
      // The call's own finishing step: the program reaches FAILED, the park
      // entry closes and the name is retired, so the next call may use it.
      call.settleContinuation({ status: "failed", error: failure });
      session.tenure.noteFinished(name);
      return { ...surface.receipt(), value: null, error: failure };
    } finally {
      session.control.close(name);
    }
    if (result.status === "parked" || program.state === "parked") {
      const site = call.parkedSite ?? program.site ?? null;
      session.tenure.noteParked(name);
      // An `ask` is a question to the operator, and the program waits for the
      // answer (spec §3.3, root §6). `this.ask` IS `this.park` + `interrupt`, so
      // the call stays open in the park table, the receipt says
      // `interrupt: true`, and the turn ends: the operator reads the question
      // and replies in ordinary chat. The reply is the value a later
      // `this.unpark(name, answer)` injects — `SessionState.resumeAsked` performs
      // that resume at the request boundary, and the body continues from the
      // parked expression instead of the task starting over.
      if (site?.reason === "ask") {
        session.noteAsk({
          name,
          question: site?.payload?.question ?? null,
          // The answer is the value of the parked expression (§3.3: an `ask`
          // site takes the injected value like any other park). The SESSION's
          // `resume` owns that state change: it wakes the SAME continuation the
          // AI's `this.unpark(name, answer)` wakes and finishes it the same way,
          // so both routes report the same thing. The site must NOT be consumed
          // here — `resume` does it, and a site consumed twice would leave the
          // body with nothing to continue from.
          resume: (answer) => {
            if (program.state !== "parked") return false;
            // The SESSION's resume is the finishing route (根 §3.3): it wakes
            // the continuation, lets the call reach its terminal state, retires
            // the name, notes the park finished and closes the park entry. The
            // call's own `resume` only answered the continuation, so a program
            // the operator answered stayed parked forever: its name stayed
            // live and the Idle Hint kept listing it.
            //
            // The continuation runs on; its outcome is the resumed program's,
            // and the operator's message does not wait for it.
            void session.resume(name, answer);
            return true;
          },
          surface,
        });
        return {
          ...surface.receipt({
            reason: "ask",
            payload: site?.payload ?? null,
          }),
          interrupt: true,
        };
      }
      return surface.receipt({
        reason: site?.reason ?? "explicit",
        payload: site?.payload ?? null,
      });
    }
    session.tenure.noteFinished(name);
    session.registry.retire(name);
    // A failed body reports the refusal it threw with, including its `code`: a
    // refusal such as `ASK_FORBIDDEN_OUTSIDE_S1` is read by that code, and a
    // receipt that kept only the message would make the one refusal the spec
    // names by code unreadable. `ProgramCall#run` puts the code on the failure
    // it returns, so `result.error` already carries it.
    if (result.status === "failed") {
      return { ...surface.receipt(), value: null, error: result.error };
    }
    return { ...surface.receipt(), value: result.value ?? null, error: null };
  }

  async function runReview({ name, code }) {
    if (typeof code !== "string" || code.trim().length === 0) return invalid("code must be a non-empty string");
    // Root §2.3: the evaluation window is open only until the round's first
    // accepted review. A call after that is refused before a program exists, so
    // a closed window cannot leave a program name behind.
    if (!session.tenure.window.open) {
      return { ok: false, rejection: REVIEW_WINDOW_CLOSED, message: "the evaluation window is closed" };
    }
    // One name, one live program: a second start under a name still in the
    // registry is reported as busy, not as a name the registry never had.
    if (session.registry.live(name) !== null) {
      return { ok: false, rejection: "PROGRAM_BUSY", message: `program ${name} is still live` };
    }
    let program;
    try {
      program = session.registry.begin(name);
    } catch (error) {
      return { ok: false, rejection: error.code ?? "NAME_TAKEN", message: error.message };
    }
    const call = new ProgramCall({ program, parks: session.parks, registry: session.registry });
    const surface = new ProgramSurface({
      name,
      role: ROLES.MANAGER,
      program,
      // 根 §3.3: the receipt names the program that ran.
      source: code,
      clock: session.clock,
      ports: {},
      root: session.root,
      park: (site) => call.park(site),
      views: session.views,
      // Root §2.3: the tenure's window, so the surface itself refuses a shut
      // one. The check above is the same refusal one step earlier.
      window: session.tenure.window,
    });
    // A parked `js-review` program is a continuation like any other: a later
    // `unpark` reads its receipt from the surface this call owns.
    call.attachSurface(surface);
    // The tenure owns the request boundary (§2.3): this call is its round's
    // request, and the window was checked open above.
    let result;
    try {
      const fn = compile(code, Object.freeze({ ...surface.reviewSurface({ openRound: true }) }));
      result = await call.run(() => fn());
    } catch (error) {
      const failure = {
        name: error?.name ?? "Error",
        message: String(error?.message ?? error),
        code: typeof error?.code === "string" ? error.code : "PROGRAM_COMPILE_FAILED",
      };
      call.settleContinuation({ status: "failed", error: failure });
      session.tenure.noteFinished(name);
      return { ...surface.receipt(), value: null, error: failure };
    }
    if (result.status === "parked" || program.state === "parked") {
      session.tenure.noteParked(name);
      return surface.receipt({
        reason: call.parkedSite?.reason ?? program.site?.reason ?? "explicit",
        payload: call.parkedSite?.payload ?? program.site?.payload ?? null,
      });
    }
    session.tenure.noteFinished(name);
    session.registry.retire(name);
    if (result.status === "failed") return { ...surface.receipt(), value: null, error: result.error };
    return { ...surface.receipt(), value: result.value ?? null, error: null };
  }

  return {
    session,
    hooks,
    // What role this session plays and where that fact came from.
    identity,
    register,
    // Which entry the request rewrite actually took (根 §7.2): a registration
    // that happened, or the named reason it could not. A receipt that claims
    // `registered` without the host having the capability would be a lie.
    get rewrite() {
      return rewrite;
    },
    // `review(findings, role?)`: the ledger and the side effects of one session,
    // judged for the role that is reviewing (spec §5, §2.2). The review carries
    // no text; a Plan hand-off sends the canonical saved draft.
    // `callId` is the host's tool-call identity (根 §2.4 第 6 步): a replayed call
    // returns its own receipt instead of booking a second beat.
    reviewFor: (findings, role = ROLES.MANAGER, callId = null) =>
      reviewFor({ session, findings, role, callId }),
    // The tools a delegated runtime receives: the worker's programming face is
    // exactly the host-side tools this plugin contributes, so a worker never
    // gets the host's builtin set through `createSubAgentTools` (spec §7.2).
    get tools() {
      return session.workerTools();
    },
    MANAGER_FORBIDDEN,
    // The one reading of where the request copy is reachable from: the hook bag
    // of the plugin object the host will call (`wrapHooks`).
    attachExtension: session.attachExtension,
  };
}
