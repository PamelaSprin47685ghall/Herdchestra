// The operator channel on Cline (根 §2.2, §2.4, §6.1).
//
// `this.ask(question)` IS `this.park({ question })` + `interrupt`. On Cline the
// question is not answered inside the tool call — it cannot be, because the
// program is parked and the answer is a later operator turn. What this module
// does is the ASKING half:
//
//   1. park the program (the surface already did: `reason: "ask"`,
//      `payload: { question }`) and answer the tool call with a receipt that
//      carries `interrupt: true`;
//   2. interrupt the current agent turn, so control returns to the operator and
//      they can read the question and reply in ordinary chat;
//   3. on the operator's next real message, resume the parked program through
//      `unpark(name, answer)` — `SessionState.resumeAsked` does that, and the
//      original `await this.ask(...)` evaluates to the reply.
//
// So no host capability is missing here, and nothing is refused. The operator's
// own message is a real channel on every Cline session: it arrives as the
// session's next request, which `hooks.beforeModel` already reads. A question is
// therefore never left unanswered and never blocked on a dialog Cline does not
// have.
//
// What the seam still does when a caller injects a channel (an embedder, a
// test): it uses it. A direct `ask(question)` that returns words answers the
// question in-call; a delivered question is reported as pending. Neither path is
// required for a real session, and neither can fail it.

export const NO_PER_RUNTIME_STEER = "NO_PER_RUNTIME_STEER";
// The question reached the operator, and the answer is their next message. This
// is the shape every real session answers with; `interrupt: true` is the
// control hand-back that makes it a question rather than a note.
export const OPERATOR_QUESTION_ASKED = "OPERATOR_QUESTION_ASKED";

const isFunction = (value) => typeof value === "function";

// The approval request one question becomes. `toolName` is this adapter's, so a
// host log or an operator prompt names the thing that is asking.
export const approvalRequestFor = ({ question, approvalId, toolCallId = null, sessionId = null }) => ({
  approvalId,
  toolCallId: toolCallId ?? approvalId,
  toolName: "herdchestra:ask",
  ...(sessionId === null ? {} : { sessionId }),
  // The host shows `reason` to the operator as the question body; the wording
  // asks for words, because words are the only thing that can come back.
  reason: `${question}\n\nAnswer with your words in the reply (a bare approval carries no answer).`,
});

// The operator's answer as this seam reports it: the words, or the parked ask
// itself (the reply will arrive as the next message).
export const answerFromApproval = (approval) => {
  const words = typeof approval?.reason === "string" ? approval.reason.trim() : "";
  if (words.length > 0) return { ok: true, answer: words };
  return null;
};

// The receipt of an `ask` on this host: the question is on its way to the
// operator, the turn is interrupted, and the answer will arrive as
// `unpark(name, answer)`. Identical shape on all three hosts (§3.3).
export const askedReceipt = ({ question, program = null, toolCallId = null }) => ({
  ok: true,
  asked: true,
  question,
  program,
  toolCallId,
  interrupt: true,
  reason: OPERATOR_QUESTION_ASKED,
  note: "the operator's reply arrives as the next message; resume this program with unpark(name, answer)",
});

// One session's operator seam.
//
//   `ask(question, { toolCallId, program })`   park + interrupt; the answer comes
//                                              back through `unpark`
//   `steer(sessionId, text)`                   a steer the host can address
//
// A channel injected by an embedder is used when it can answer in-call; a
// channel that only DELIVERS the question is reported as pending. Neither is
// needed for a real session: the operator's next message is the channel, and
// this seam always answers with the asked receipt rather than a refusal.
export const createOperatorSeam = ({
  ask = null,
  requestApproval = null,
  request = null,
  steer = null,
  sessionId = null,
} = {}) => {
  let sequence = 0;
  return {
    async ask(question, { toolCallId = null, program = null } = {}) {
      if (typeof question !== "string" || question.trim().length === 0) {
        return { ok: false, reason: "INVALID_QUESTION" };
      }
      const text = question.trim();
      const asked = () => askedReceipt({ question: text, program, toolCallId });
      // A direct channel answers as written: no shape of the seam's own.
      if (isFunction(ask)) {
        const answer = await ask(question);
        return answer?.ok === true && typeof answer.answer === "string" ? answer : asked();
      }
      if (isFunction(requestApproval)) {
        sequence += 1;
        const approval = approvalRequestFor({
          question: text,
          approvalId: `herdchestra-ask-${Date.now().toString(36)}-${sequence}`,
          toolCallId,
          sessionId,
        });
        let answer = null;
        try {
          answer = answerFromApproval(await requestApproval(approval));
        } catch {
          // A channel that throws is not a reason to lose the question: the
          // operator's next message still answers it.
          answer = null;
        }
        return answer ?? asked();
      }
      if (isFunction(request)) {
        try {
          await request(text);
        } catch {
          // Delivery is best effort: the park and the interrupt are what carry
          // the question to the operator, and they already happened.
        }
      }
      return asked();
    },
    steer(sessionIdForSteer, prompt) {
      if (!isFunction(steer)) return { ok: false, reason: NO_PER_RUNTIME_STEER };
      return steer(sessionIdForSteer, prompt);
    },
  };
};
