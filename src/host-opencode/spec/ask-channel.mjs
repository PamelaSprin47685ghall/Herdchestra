// `ask` and the first-incumbent question (§3.3 example 7, 根 §2.4, §6).
//
// `this.ask(question)` IS `this.park({ question })` + `interrupt`. The `ask`
// member parks the program before any port is reached, so the answer is not a
// return value of the member: it arrives later as the resumed value of
// `this.unpark(name, answer)`. The host's `question` tool asks and returns
// inside one tool call, which a parked program is not waiting on. The channel
// that survives a park is the operator's next prompt, so the adapter does
// three things:
//
//   1. records the question as the session's pending ask, with the parked
//      program's name;
//   2. puts the question in front of the operator as a synthetic message, so
//      the operator reads it in the session rather than in a tool result;
//   3. answers the tool call with the asked receipt, whose `interrupt: true`
//      ends the turn and hands control back.
//
// When the operator answers, `session.hook("prompt")` sees the real prompt, and
// the reply is the value `unpark` injects into the parked `ask` (§3.3: an `ask`
// site takes the injected value like any other park).
//
// Nothing here invents an answer, and nothing refuses for a missing channel: a
// synthetic message the host will not take leaves the question recorded and
// still asked — the park and the interrupt carry it either way.

export const PENDING_ASK = Object.freeze({
  QUESTION: "question",
  ANSWERED: "answered",
  DISMISSED: "dismissed",
});

// One ask of one session. `program` is the name of the parked program; a
// program that parks twice cannot, so one pending ask per program.
export class AskChannel {
  #sessions;
  #pending;
  #history;

  constructor({ sessions = null } = {}) {
    this.#sessions = sessions;
    this.#pending = new Map();
    this.#history = [];
  }

  get history() {
    return [...this.#history];
  }

  pending(sessionID) {
    return this.#pending.get(sessionID) ?? null;
  }

  hasPending(sessionID) {
    return this.#pending.has(sessionID);
  }

  // Record the question and show it to the operator. `program` names the parked
  // program so the answer can be routed back to exactly that park.
  async ask({ sessionID, program, question }) {
    const record = {
      sessionID,
      program,
      question,
      state: PENDING_ASK.QUESTION,
      at: Date.now(),
      shown: false,
    };
    this.#pending.set(sessionID, record);
    this.#history.push(record);
    if (this.#sessions !== null && typeof this.#sessions.synthetic === "function") {
      try {
        await this.#sessions.synthetic({
          sessionID,
          text: question,
          description: "Herdchestra asks",
          metadata: { source: "herdchestra.ask", program },
          // The question is shown now; the program resumes when the operator
          // answers, which is the next prompt, not this one.
          resume: false,
        });
        record.shown = true;
      } catch (error) {
        // A message the host would not take is reported on the record, not as
        // a refusal: the question is asked either way.
        record.delivery = { ok: false, detail: String(error?.message ?? error) };
      }
    }
    return { ok: true, asked: true, question, program, interrupt: true };
  }

  // The operator's reply, if this prompt answers a pending ask. The record is
  // consumed here; a second prompt is a new prompt, not a second answer.
  takeAnswer(sessionID) {
    const record = this.#pending.get(sessionID);
    if (record === undefined || record.state !== PENDING_ASK.QUESTION) return null;
    this.#pending.delete(sessionID);
    record.state = PENDING_ASK.ANSWERED;
    return record;
  }

  // The operator went away (interrupt, session end) without answering.
  dismiss(sessionID) {
    const record = this.#pending.get(sessionID);
    if (record === undefined) return null;
    this.#pending.delete(sessionID);
    record.state = PENDING_ASK.DISMISSED;
    return record;
  }
}

// The park site of an `ask`. Core's `resumeValue` gives an `ask` site its own
// `value`, so the answer travels on the site, not through the resume argument.
export const askSite = ({ question, answer = null }) => ({
  reason: "ask",
  payload: { question },
  value: answer,
});
