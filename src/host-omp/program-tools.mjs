// The `js-manager`, `js-worker` and `js-review` tools (spec §3, §7.2, §2.3).
//
// A program is source text the model writes: `class Js extends JsProgram { async
// run() { ... } }`. The tool compiles it, binds the role's surface, and runs it.
// The surface decides which members exist; this file decides nothing about them.
//
// One call answers with the program's receipt:
//
//   - a park receipt, when a member parked before the body returned. The body is
//     still suspended on the promise `unpark` will settle; nothing is settled
//     here and the program keeps its name;
//   - a completed receipt with `value`, when the body returned;
//   - a failed receipt with `error`, when the body threw.
//
// The call never awaits the body: it races the body's outcome against a park
// signal (`startProgram`), so a park answers at once while the async stack stays
// alive for the resume (spec §3.3 "恢复不重跑").
//
// Tool approval. OMP's `tool_call` hook runs before the approval gate and may
// return `{block, reason}`, so an approval that runs a program would ask twice.
// The adapter blocks that second ask for its own tools only (see
// `isOwnApprovalCall` in hooks.mjs). Every `js-*` tool is declared `approval:
// "exec"`, matching what it can do.

import { objectSchema, STRING, ANY, guard, present, receiptObject, textResult, errorResult } from "./tool-schema.mjs";
import { startProgram } from "./program.mjs";
import { withDeadline } from "../compose/deadline.mjs";

const SOURCE_PROPERTY = Object.freeze({
  ...STRING,
  description: "The program: `class Js extends JsProgram { async run() { ... } }`.",
});

const OPTIONS_PROPERTY = Object.freeze({
  ...ANY,
  description: "Optional { intent } for the request deadline; defaults to the program source.",
});

// The scope annotation is a top-level parameter, not part of `source`: the host
// gate reads `input.scope` before the program runs (spec §6.2). The mechanism
// is explained ONCE in the system prompt's Scope section; this description only
// points there and states the required field pairs, because every tool repeats
// its own copy and a second full explanation costs tokens on every call.
export const SCOPE_PROPERTY = Object.freeze({
  type: "object",
  description:
    "Scope annotation; the system prompt's Scope section explains the mechanism. " +
    "Required fields by kind: keepLast, meToo — only `kind`; " +
    "removeLast — `kind` and a non-empty `replacement`; " +
    "resetScope — `kind`, a non-empty `newScopeGoal` and a positive integer `newScopeRetentionBudget` " +
    "(required on the first call of a session).",
  properties: {
    kind: { type: "string", enum: ["keepLast", "removeLast", "meToo", "resetScope"] },
    newScopeGoal: { type: "string" },
    newScopeRetentionBudget: { type: "integer", minimum: 1 },
  },
  required: ["kind"],
});

const parameters = objectSchema(
  { source: SOURCE_PROPERTY, options: OPTIONS_PROPERTY, scope: SCOPE_PROPERTY },
  ["source"],
);

// The deadline budget is 50 ms per UTF-8 byte of the intent (core/clock.mjs).
const intentOf = (params) => {
  const intent = params?.options?.intent;
  return typeof intent === "string" && intent.length > 0 ? intent : params?.source;
};

// The receipt as the model reads it (spec §6.3). Core attaches `value` and
// `result` as NON-enumerable fields (`core/receipt.mjs` `withExtra`), so the
// receipt goes through `receiptObject` first: a plain spread drops exactly the
// two fields the model must see. `details` receives that same object, so a
// caller reads the structured receipt there instead of parsing the text.
//
// One tool per surface. `surfaceFor(request)` is supplied by the host binding:
// it builds the composition surface for the calling session and the program
// name. The program name is the tool call id, which is stable across a replay of
// the same physical call. `onClose(name, state, ctx)` runs once the call is over
// and tells the CALLING session's adapter whether the program is still live
// (parked) or finished. `ctx` is passed because the same definitions travel as
// `customTools` into worker sessions, and the session that owns the call is the
// one that must be told.
export const programTool = ({
  surfaceName,
  label,
  description,
  surfaceFor,
  onClose = null,
  defaultInactive = false,
}) => ({
  name: surfaceName,
  label,
  description,
  parameters,
  approval: "exec",
  // Only the role's own face is active by default; `setActiveTools` shows it.
  defaultInactive,
  async execute(toolCallId, params, _signal, _onUpdate, ctx) {
    return await guard(async () => {
      const source = params?.source;
      if (typeof source !== "string" || source.trim() === "") {
        return errorResult("INVALID_ARGUMENT: source must be a non-empty string", {
          code: "INVALID_ARGUMENT",
        });
      }
      const surface = surfaceFor({
        ctx,
        toolCallId,
        surfaceName,
        source,
        intent: intentOf(params),
      });
      // The builder returns the surface, its program and the session's park
      // table; a builder that returns only members is still accepted for tests
      // that drive a bare surface.
      const built = surface?.surface === undefined ? { surface, program: surface?.program ?? null, parks: surface?.parks ?? null } : surface;
      const program = built.program;
      if (program === undefined || program === null) {
        return errorResult("INVALID_ARGUMENT: no program surface was built", { code: "INVALID_ARGUMENT" });
      }
      try {
        const run = startProgram({
          source,
          surface: built.surface,
          program,
          parks: built.parks,
        });
        // The request's deadline is the third way this call can end (spec §3.3:
        // 一旦到期). `withDeadline` races the run against it; on expiry the
        // controller and every still-active program of the request park
        // together, and this call answers the parked receipt instead of waiting
        // for a body whose request no longer exists.
        const raced = await withDeadline(
          program,
          Promise.race([
            run.outcome.then((outcome) => ({ kind: "outcome", outcome })),
            run.parked.then((parked) => ({ kind: "parked", parked })),
          ]),
          {
            onExpire: () => {
              if (typeof built.expire === "function") built.expire();
              return built.surface.receipt({ reason: "explicit", payload: null });
            },
          },
        );
        if (raced.kind === "expired") {
          const expired = receiptObject(raced.receipt);
          return textResult(present(expired), { result: expired });
        }
        const first = raced.value;
        if (first.kind === "parked") {
          const site = first.parked.site ?? null;
          // The body is suspended, not finished: the receipt is the answer and
          // the program keeps its name for the resume.
          const receipt = built.surface.receipt({
            reason: site?.reason ?? "explicit",
            payload: site?.payload ?? null,
          });
          // A park receipt carries `value` too: the resumed expression's value
          // is the one fact a parked call has already received, and the model
          // must see it under the same name it will see at completion.
          //
          // §3.3: an `ask` park is the one park that hands the turn back to the
          // operator. The host's asked receipt carries `interrupt: true`
          // (`ports.mjs` `askedReceipt`), but the program body is suspended and
          // this receipt is what the model reads, so the control fact must be
          // stated here too — otherwise the model sees a park and no reason to
          // stop.
          const parked = {
            ...receiptObject(receipt),
            ...(site?.reason === "ask" ? { interrupt: true } : {}),
          };
          // The parked set is the Pair Hint's handle list (§6.1 item 7): a
          // handle enters it when the program parks and leaves it when the
          // resumed body finishes (the outcome callback below).
          if (typeof built.noteParked === "function") built.noteParked();
          void run.outcome.then(
            () => built.noteFinished?.(),
            () => built.noteFinished?.(),
          );
          return textResult(present(parked), { result: parked });
        }
        const outcome = first.outcome;
        // `settle` retired the name, so the terminal receipt is the last view of
        // the run and carries its result.
        const receipt =
          typeof built.surface.finalReceipt === "function" ? built.surface.finalReceipt() : built.surface.receipt();
        if (outcome.status === "failed") {
          // A failed run is an error result, so the model sees the failure as a
          // failure rather than as a value it must inspect for an `error` key.
          const failed = { ...receiptObject(receipt), value: null, error: outcome.error };
          return errorResult(present(failed), failed);
        }
        const result = { ...receiptObject(receipt), value: outcome.value ?? null, error: null };
        return textResult(present(result), { result });
      } finally {
        if (typeof onClose === "function") await onClose(toolCallId, program.state, ctx);
      }
    });
  },
});

// The member list is stated in the one spelling §3.1 allows: members as
// `this.xxx`, the top-level primitives as bare names. Saying "park, unpark,
// fork" without the distinction is what taught the model to write `this.fork`.
//
// `devops` is Plan's alone (root §1.1 item 3): it is a bare primitive, never a
// member, so the Plan face's spelling says `devops(verboseIntent)` and says that
// `this.devops` does not exist. Every other face leaves it out entirely.
const spelling = ({ devops = false } = {}) =>
  "Call members as this.xxx (this.cat(...), this.park(...)). Call the top-level primitives " +
  "fork(name, verboseIntent), join(), review(findings)" +
  (devops ? " and devops(verboseIntent)" : "") +
  " as bare functions; there is no this.fork, this.join or this.review" +
  (devops ? ", and no this.devops" : "") +
  ".";

export const MANAGER_DESCRIPTION =
  "Run a JavaScript program against the Manager programming face. Members: this.park, this.unpark, this.ask; " +
  "primitives: fork, join, review. There is no file access, no this.call and no this.fission. " +
  spelling() +
  " Write `class Js extends JsProgram { async run() { ... } }`; return a JSON value from run().";

export const WORKER_DESCRIPTION =
  "Run a JavaScript program against the worker programming face. Members: this.cat, this.stat, this.ls, " +
  "this.write, this.mkdir, this.rm, this.mv, this.where, this.whereAll, this.park, this.unpark, this.call, " +
  "this.fission; primitives: fork, join. There is no this.ask. " +
  spelling() +
  " Write `class Js extends JsProgram { async run() { ... } }`; return a JSON value from run().";

export const PLAN_DESCRIPTION =
  "Run a JavaScript program against the Plan programming face. Members: this.cat, this.write, this.ask, " +
  "this.unpark; primitives: review and devops. " +
  "You read and write ONE file — the draft at plans/<name>.md, the path the system gives you — and nothing " +
  "else in the repository. Use the bare primitive devops(verboseIntent) to have the single dedicated DevOps " +
  "investigate the repo and return its whole report; it is synchronous and reused across calls, and there is " +
  "no this.devops. Use this.ask(question) to put a question to the operator (the program parks until the " +
  "answer arrives as this.unpark(name, answer) from a later program). The draft is read-only until your " +
  "first accepted review(this round's findings); after that this.write is allowed. At the final milestone, " +
  "review([]) hands the saved draft to a Manager. " +
  spelling({ devops: true }) +
  " Write `class Js extends JsProgram { async run() { ... } }`; return a JSON value from run()." +
  "\n" +
  "There is no fork, no join, no this.call, no glob/grep/exec, and no this.fission.";

export const REVIEW_DESCRIPTION =
  "Read-only evaluation face, open only until this round's first review is accepted. Members: this.cat, " +
  "this.stat, this.ls, this.where, this.whereAll. " +
  spelling() +
  " Write `class Js extends JsProgram { async run() { ... } }`; return a JSON value " +
  "from run(). After the review is accepted this tool closes for the round: fork a read-only coder instead.";
