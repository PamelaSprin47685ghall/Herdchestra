// `session.hook("prompt")`: the operator's own message (根 §2.3, §3.3, §6.2).
//
// The hook sees a prompt before it is admitted. Three facts matter to the
// adapter and none of them rewrites the text:
//
//   - a prompt that answers a pending `ask` resumes the parked program with the
//     reply text (§3.3 example 7). The question is consumed once; a second
//     prompt is a new prompt, not a second answer;
//   - a real operator message reopens a finished work (根 §2.4 第 6 步): the
//     Manager ledger is asked, and a reopened work starts again at `close`;
//   - the prompt is the operator's voice, so Scope's projection keeps it in
//     every request copy (§6.2); nothing here removes or rewords it.
//
// The language is bound at session creation (§6.1 step 1), not here, so a
// prompt never changes it.
//
// ## The payload, from the host source
//
// `SessionPrompt.prepare` triggers this hook with
// (`packages/core/src/session/prompt.ts`):
//
//   { sessionID, messageID,
//     prompt: { text, files?, agents?, skills? },
//     metadata?, delivery }
//
// `prompt.text` is the operator's words, already cloned; the hook may replace
// `prompt`, and the host then admits the replacement. `delivery` is `"steer"`
// unless the caller asked otherwise. So the text is `event.prompt.text` — not
// `event.text`, which this hook never receives.
//
// A payload that does not match is a host change, not a prompt without text: it
// is reported as a gap value instead of being read as empty. Resuming an `ask`
// with an invented empty answer would close the operator's question with
// nothing.

import { ReviewLedger } from "../../core/review.mjs";

// The operator's text of this prompt event, or null when the event is not a
// prompt event at all (the same handler is registered once and may be probed).
export const promptText = (event) => {
  const text = event?.prompt?.text;
  return typeof text === "string" ? text : null;
};

// Which session and delivery the host named. `sessionID` is required by the
// host type; a missing one means the event is not a prompt.
export const promptSessionOf = (event) => (typeof event?.sessionID === "string" ? event.sessionID : null);

// True when the event has the shape the host documents. A false answer is
// reported to the caller, which then answers the ask with a named gap instead of
// pretending the operator said nothing.
export const isPromptEvent = (event) =>
  promptSessionOf(event) !== null && typeof event?.prompt === "object" && event.prompt !== null;

// Answer a pending ask. Returns what happened, so the caller can log it; the
// resume itself is the same `unpark` path every parked program uses.
export const answerPendingAsk = async ({ event, activation, state, sessionID, resume }) => {
  const pending = activation.asks.pending(sessionID);
  if (pending === null) return null;
  if (!isPromptEvent(event)) {
    // The ask stays pending: a malformed payload must not consume the question.
    return { answered: false, reason: "PROMPT_HOOK_PAYLOAD", program: pending.program };
  }
  const answer = promptText(event);
  activation.asks.takeAnswer(sessionID);
  if (pending.program === null) {
    // A question recorded with no program has nothing to resume. The adapter
    // never asks that way any more — Plan's first empty review records the
    // program of the call that asked (`program-ports.mjs` `askOperator`) — so
    // this branch only answers a record written before that, and says so.
    return { answered: true, program: null, answer, resumed: null, resume: "NONE" };
  }
  // The site the program is parked on NOW: an `ask` park keeps it until the
  // resume consumes it. Reading it from the program record is what tells an
  // `ask` park (which takes its own value) from any other.
  const site = typeof state.calls.siteOf === "function" ? state.calls.siteOf(pending.program) : null;
  if (site !== null && site.reason === "ask") {
    // An `ask` site takes its own result, not the injected value (§3.3).
    site.value = answer;
  }
  const resumed = await resume({ name: pending.program, value: answer, site, sessionID });
  return { answered: true, program: pending.program, answer, resumed };
};

// The operator's original input is captured FIRST (根 §2.4 第 3 步).
//
// Every other step of this hook can consume, resume or reopen something, and a
// crash between them must not lose the operator's words: they are the one fact
// no later reader can recover, because the host compacts and a post-compaction
// request copy no longer holds them. So the capture runs before the ledger is
// asked anything, and it runs only on a receipt the adapter can prove is
// genuine (`receiptVerdict`): a child's first prompt is a brief (§4.1), and our
// own relay brief is not the operator's voice.
//
// A capture that cannot be made is reported, never substituted: `original` in
// the receipt says `captured`, `kept` (a fact already on disk), or why not.
export const applyPromptHook = (
  event,
  { ledgers, state = null, parentID = undefined, registeredParentID = undefined, startedBeforeRecording = false },
) => {
  const original =
    state === null
      ? { ok: false, captured: false, reason: "NO_STATE" }
      : state.captureOriginalInput({
          text: promptText(event),
          messageID: typeof event?.messageID === "string" ? event.messageID : null,
          parentID,
          // The adapter's own registration of a session it created, written
          // before the first prompt (`identity.mjs`). `undefined` means the
          // session was not created here, and the host's parent fact is used.
          registeredParentID,
          // Identity of the delivery, never of its words. A relay brief is the
          // only delivery the adapter itself sends, and its id is the
          // deterministic `briefId` the relay record wrote; an operator prompt's
          // `messageID` is just the host's message id and says nothing about
          // who spoke.
          briefID: typeof event?.briefID === "string" ? event.briefID : null,
          delivery: typeof event?.delivery === "string" ? event.delivery : null,
          startedBeforeRecording,
        });
  const ledger = ledgers.get(event.sessionID);
  if (!(ledger instanceof ReviewLedger)) return { original, reopened: null };
  const reopened = ledger.reopen({ operator: true });
  return { original, reopened };
};
