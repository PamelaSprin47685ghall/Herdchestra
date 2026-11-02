// @herdchestra/core/review — package specification.
//
// `ReviewLedger` holds one side's obligation ledger and the relay decision
// (spec §5). It performs no session work: a `relay` receipt states the facts the
// host must write in one persistent record.
//
// ## Findings
//
// `findings` is an array of `{ key, value }`. A string value adds or replaces the
// key, null deletes it, `[]` changes nothing. Deleting a key that does not exist
// is a no-op.
//
// ## Milestones
//
// Plan: `S1`, `S2`, `S3`, `待执行` (`PLAN_MILESTONES`; root spec §2.2).
// Manager: `execute`, `close`, `done`.
//
// ## Review outcomes
//
// 根 §2.2 gives Plan and Manager each their own ledger and says the relay rules
// apply to both; §2.4 states ONE timeline for both sides.
//
// | situation                                    | kind         | effect                       |
// |----------------------------------------------|--------------|------------------------------|
// | ledger non-empty                             | `recorded`   | tenure continues, milestone never moves |
// | non-empty, first review of a successor tenure | `recorded`  | normal working period; the successor flag clears, so the next empty review relays |
// | empty, Plan `S1`, first review of first tenure | `askUser`  | system asks "我需要干什么？" (`interrupt: true`) |
// | empty, Manager, first review of first tenure | `confirm`    | milestone stays at `execute`, no question |
// | empty, first review of a successor tenure    | `confirm`    | milestone advances, no new tenure |
// | empty, any other tenure, below the last milestone | `relay` | retire + barrier + successor at the SAME milestone |
// | empty, Plan at `待执行`, draft missing/empty | `rejected`   | `DRAFT_MISSING`              |
// | empty, Plan at `待执行`, draft present       | `handoff`    | starts a Manager session     |
// | non-empty, Plan at `待执行`                  | `rejected`   | `ALREADY_READY`, findings rolled back |
// | empty, Manager at `close`, resources open    | `nextReview` | `RESOURCES_UNSETTLED`        |
// | empty, Manager at `close`, resources closed  | `closed`     | done                         |
// | any review while Manager is at `done`        | `rejected`   | `ALREADY_DONE`               |
//
// **Plan relays exactly like Manager.** The one asymmetry is the first empty
// review of the first tenure: at `S1` it asks the operator (根 §2.2, §2.4 第 1
// 步); on the Manager side it is an independent verification (`confirm`) that
// leaves the milestone at `execute`. A first tenure therefore cannot advance on
// its own word on either side — its second empty review RELAYS, and the
// milestone only moves when the successor that relay opened makes its own
// first empty review (`confirm`). `待执行` is reached by a successor's promotion
// out of `S3` and by nothing else, which is what makes Plan's exit meaningful:
// the tenure that did the work never certifies it.
//
// 万象术 runs unattended (根 §2.2): the question is Plan's first stage alone,
// and the answer is a park + interrupt, resumed by `this.unpark(name, answer)`.
// A Plan stays at `待执行` after a handoff: it does not consume a review and may
// hand off again on a later empty review.
//
// `askAllowed(ledger)` is the one predicate behind this rule: `{ ok: true }` only
// for a `plan` ledger at `S1`. The surface refuses `this.ask` with
// `ASK_FORBIDDEN_OUTSIDE_S1` on any other answer.
//
// A refused review (`DRAFT_MISSING`, `ALREADY_DONE`) is decided before the
// review counter and the milestone move, so it changes nothing: a Plan at
// `待执行` that hands off without a draft keeps its tenure's first-review credit.
//
// ## Relay and the successor flag
//
// A relay hands over to a successor at the same milestone, on BOTH sides. The
// successor flag lives only until that tenure's **first review** (根 §2.4 第 5
// 步):
//
// - an empty first review advances the milestone (`confirm`, or `closed` when
//   the milestone was `close`) and clears the flag;
// - a non-empty first review is a normal working period: it records its
//   obligations, clears the flag and changes no milestone. When that tenure
//   later empties the ledger, the empty review RELAYS again instead of
//   promoting — 「等它修完再次清空台账时，依然是触发下一次接力，而不是直接晋级」.
//
// The flag therefore answers exactly one question: "has this successor not
// judged yet?" Anything that asks "was this milestone verified?" must read the
// tenure instead (`tenure >= 1` on a plan ledger, or `done` on a manager
// ledger), because the promotion that proves the verification is the very
// review that clears the flag. A predicate written as `successor === true`
// describes a state the ledger cannot hold.
//
// A relay never happens at the LAST milestone: Manager closes there, and a Plan
// hands its draft over instead of retiring. `openSuccessor()` and
// `adoptSuccessor()` both refuse it with `INVALID_ARGUMENT` before writing
// anything, so a record that names a finished side cannot open a tenure nobody
// can advance.
//
// ## Which text starts a Manager session
//
// The canonical saved draft, and nothing else (根 §2.2). `review` takes no
// note: its second argument is `source`, the bytes read from the canonical
// draft path. A string source is the draft to send, byte for byte; a missing
// source, an empty string or any other value is no draft at all, and the
// handoff is refused with `DRAFT_MISSING` before any Manager session exists.
// Nothing is substituted for a missing draft.
//
// The canonical path is the workspace-root `plans/<name>.md`, never under
// `.Herdchestra/` (根 §1.1 item 3, §2.2). This package does not choose or read
// that path: the host's `draft` port hands the bytes in, and the ledger judges
// only whether they are a non-empty string.
//
// `relay` carries `retire: true`, `openSuccessor: true`, `barrier: true`; the
// host writes all three together. `openSuccessor()` copies the milestone, clears
// the ledger and marks the tenure as a successor; a finished work has no
// successor and throws `INVALID_ARGUMENT` **before writing anything**, so a
// refused successor leaves the ledger as it was. `reopen({ operator })` moves a
// finished **Manager** work back one milestone only for a real operator message
// (`AGENT_MESSAGE` otherwise, `NOT_FINISHED` when the work is not done). A Plan
// at `待执行` is never reopened: §2.2 keeps it there, and a message for a Plan is
// a new planning task, not a mutation of the standing ledger. Reopen is
// one-shot: once the work is back in progress a second message answers
// `NOT_FINISHED` instead of erasing the obligations the reopened tenure recorded.
//
// `draftWritable(ledger)` is false until the tenure's first review.
//
// ## The evaluation window (根 §2.3)
//
// `ReviewWindow` is the second fact this package owns: `js-review` is open from
// a round's first request until that round's FIRST accepted review, and shut
// otherwise. Every adapter gates on it — the visible tool set, the `execute` /
// `tool_call` / `beforeTool` refusal, and `ProgramSurface.reviewSurface()`.
//
// | event                       | effect                                       |
// |-----------------------------|----------------------------------------------|
// | `beginRound()` (no argument)| opens the next round if none is open         |
// | `beginRound(n)`             | moves to round `n` when `n` is later         |
// | `noteReview({ok:true})`     | shuts the open round, once                   |
// | `noteReview(other)`         | no effect at all                             |
// | `relay(tenure)`             | shuts the window; the successor reopens its own |
//
// **`open` is a getter and only a getter.** A method of the same name would
// shadow it (one property key), and every `window.open === true` in every
// adapter would compare a function and answer "shut" — a gate that never opens,
// with nothing thrown. The round is started by `beginRound`, a different name
// for a different fact; `core.test.mjs` and `review/window.test.mjs` assert the
// shape.
//
// `beginRound()` with no argument is idempotent under an open window: a host
// that prepares one request more than once (policy preparation) must not start
// a second round. A program that arrives after an accepted review DOES start
// one, which is what makes "the next request after the review" reopen the face.
// An explicit round number is the host's own boundary and always wins.
//
// The window is not persisted: it describes one round of one process. A restart
// begins a round nobody has evaluated.
