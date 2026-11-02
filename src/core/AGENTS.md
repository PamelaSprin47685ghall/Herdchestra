// @herdchestra/core — package specification (spec §3, §5, §8.1).
//
// This package holds the pure, host-independent parts of Herdchestra: text and
// span rules, atomic file operations, program lifecycle state, park receipts,
// resume control, the review ledger, and a portable command runner. It imports
// nothing outside `node:` and never imports `src/compose` or a host adapter.
//
// ## Module map
//
// | module       | exports                                                              |
// |--------------|----------------------------------------------------------------------|
// | `util.mjs`   | argument checks, `normalizePath`/`isInsideRoot`, stable JSON, bytes    |
// | `errors.mjs` | `CoreError` (`.code`, `.details`), `CODES`, `REJECTIONS`              |
// | `clock.mjs`  | `deadlineFor(intent)`, `Deadline`, `systemClock`, `fakeClock`          |
// | `command.mjs`| `nodeCommandRunner`, `fakeCommandRunner`                              |
// | `text.mjs`   | `findSpans`, `whereParkPayload`, `nearestLine`, `decodeUtf8`, helpers  |
// | `fs.mjs`     | `FileMembers`, `atomicWriteFile`, `CORE_PATH_MEMBERS`                 |
// | `where.mjs`  | `TextMembers` (`where`, `whereAll`)                                   |
// | `program.mjs`| `ProgramState`, `ProgramRegistry`, `STATES`, `isTerminal`             |
// | `receipt.mjs`| `buildReceipt`, `programView`, `CommandView`, `PARK_REASONS`, `RECEIPT_FIELDS`, `withExtra` |
// | `control.mjs`| `ControlSurface`, `ObservationLedger`, `resumeValue`                  |
// | `review.mjs` | `ReviewLedger`, `ReviewWindow`, `REVIEW_KINDS`, `WINDOW_STATES`       |
// | `cue.mjs`    | `toCue`, `cueScalar`, `isCueIdentifier`, `DEFAULT_INDENT`, `DEFAULT_WIDTH` |
// | `index.mjs`  | re-exports the above                                                 |
//
// `src/core/scope/` and `src/core/pair-hint/` belong to separate owners and are
// deliberately not re-exported here.
//
// `pair-hint/` keeps a session's language, its frozen presentation and its
// prefix chain under `.Herdchestra/{sessionId}.jsonl`. One `presentation`
// record per epoch/digest holds the hint text; one `presented` record per
// request holds `length`, one SHA-256 per message's canonical identity, a root
// over them and the synthetic pair's index — never the request copy. A cold
// start verifies the prefix from those digests, and an old journal whose
// `presented` records carry `messages` folds into the same chain and is
// migrated by compaction. `scope/` owns the retention book and the outbound
// projection; neither subpackage changes the host's stored history.
//
// ## Contracts the composition layer relies on
//
// 1. **Spans are UTF-16 offsets** `[begin, end)` into the JS string. Matching is
//    exact: no trim, no case folding, no line-ending rewrite (spec §3.1).
//    `where`/`whereAll` take the CONTENT first and the needle second
//    (`this.where(source, needle)`, spec §3.1 and §3.4); they never read a
//    path. `stat` answers exactly `{ exists, size, mtime, isDir }`, and a
//    missing path is `{ exists: false }` — a fact, not an error.
// 2. **`where` never parks by itself.** It calls the injected `park(payload)`
//    and returns that call's result. The payload is built by
//    `whereParkPayload`: zero matches give `{ needle, candidates: [], nearest }`
//    (`nearest` may be null, from the first 2000 non-empty lines, each side cut
//    to 200 chars); several matches give `{ needle, candidates: [{ span,
//    context }] }` with at most 8 candidates and 80 UTF-16 units of context on
//    each side. `whereAll` always answers.
// 3. **`write` is atomic** (spec §3.2): temp file in the target directory,
//    written, flushed, closed, renamed. The temp file is always created with
//    `wx`, so a stale temp file is never reused; an `EEXIST` on the temp name
//    fails the write and leaves that file untouched, because this call did not
//    create it. The target itself is replaced, never refused. A failure after
//    the temp file exists removes it and leaves the target intact. There is no
//    cross-write transaction and no lock.
// 4. **The registry owns facts, not execution.** `begin(name)` throws
//    `NAME_TAKEN` for a live name. Replay is a separate step: `record(program,
//    callId, payload, compute)` returns the stored receipt for the same callId
//    and payload, and throws `CALL_REPLAY_MISMATCH` for a different payload. A
//    retired program keeps its records, every generation retired under a reused
//    name keeps its own, and `replayRetired` answers from the newest generation
//    that booked the call id or throws `PROGRAM_UNKNOWN`.
// 5. **Draining** is a transition, not a stored state, and it GATES admission.
//    `program.beginOperation()` registers one operation and returns its **id**;
//    `program.endOperation(id)` settles exactly that id and requires it: no
//    "newest open" fallback, because operations complete in any order and a
//    guess settles the wrong one. `program.drainedPromise(before)` resolves when
//    every operation
//    with an id smaller than `before` has settled — the predecessors by
//    IDENTITY, never by count. While any such waiter is outstanding,
//    `program.draining` is true and `program.acceptsExpressions` is false:
//    spec §3.3 says a draining program "不再接受新的表达式推进", so a second
//    expression arriving mid-drain is refused rather than allowed to overtake
//    the work the drain protects. Draining means "someone waits for earlier
//    operations", never "operations are open" — an open operation is why its
//    owner may continue, so counting operations would deadlock it. `call`
//    registers its own operation first and drains to its own id: that excludes
//    itself and includes every predecessor, without a count-based level that
//    could skip one. `finish` is the close and
//    releases every remaining drain waiter, because a finished run accepts no
//    new expression and nothing it waits for can still arrive. `endOperation`
//    after `finish` is allowed, because an operation that started earlier still
//    has to settle.
// 5a. **Lifecycle**: `moveTo` moves between `running` and `waiting` only; a
//    terminal target and `parked` are refused (`INVALID_ARGUMENT`), because a
//    terminal state is reached through `finish` and `parked` through
//    `park(site)`. On a terminal program it is a no-op that returns the current
//    state, so a late `finally` cannot reopen a finished run. `park` on a
//    terminal program throws, and it requires a non-null object site with one of
//    the four known `reason` values; `finish` on a terminal program throws as
//    well.
// 5b. **Deadline**: `begin(name, { deadline })` gives the run one `Deadline`
//    fixed at admission. `park` cancels it, `takeSite({ deadline })` replaces it
//    with the controlling request's — or clears it when the resume names none,
//    so a spent deadline cannot re-expire at once — and `finish` cancels it.
//    `remainingWaitMs()` is null when there is none; receipts then report 0.
// 6. **Resume is single-shot.** `ControlSurface.unpark` checks, in order:
//    observation (`STALE_CONTROL`), self-target (`INVALID_ARGUMENT`), existence
//    (`PROGRAM_UNKNOWN`), capability set (`CAPABILITY_DENIED`), still parked
//    (`STALE_CONTROL`). The order is user-visible: capability precedes
//    parked-state, so a controller that does not hold the set always learns
//    `CAPABILITY_DENIED` and never learns a program's state by distinguishing
//    it from `STALE_CONTROL`. The required set is the target program's own
//    (`ProgramState#capabilities`, set at `registry.begin`); a `targetCapabilities`
//    argument is an override that must equal it, so a controller cannot lower
//    the bar by stating an empty set. The check is a subset test: the controller
//    MUST hold every capability the target requires, and extra capabilities are
//    allowed. Then the parked site is consumed exactly once, and the answer is
//    the target's receipt (`state: running`, the consumed site's `reason` and
//    `payload`, the controlling request's `remainingWaitMs`) with the resumed
//    value as a non-enumerable `value` key. An explicit park, a `where`
//    ambiguity **and an `ask`** all receive the injected value: `this.ask` IS
//    `this.park({ question })` + `interrupt`, so the operator's reply travels
//    through `unpark(name, answer)` like any other injected value. Only a `call`
//    site keeps its own result (`site.value`): a command receipt is produced by
//    the command, and letting a controller inject one would forge an execution
//    that never happened. An `ask` site that recorded an answer and is resumed
//    with none falls back to `site.value`; otherwise the injected value (or
//    `null`) is what the expression receives. `handles(controllerId)` lists that
//    controller's unsettled parks in lexical order and resumes nothing.
// 7. **Receipts** always have exactly the nine fields of spec §3.3, in spec
//    order (`RECEIPT_FIELDS`); absent facts are `null` (or `""` for `delta`),
//    never missing keys. Facts the host needs but the shape does not carry
//    (`value` on a resume, `result` on a final receipt) are attached with
//    `withExtra`, which keeps them non-enumerable so the key set stays nine.
//    `programView` reads the reason and payload from the program itself, or
//    from the `site` a resume just consumed, so a view cannot disagree with its
//    state. `CommandView#view()` takes no arguments.
// 8. **Review** applies findings (string sets, null deletes, `[]` no-ops) and
//    answers with `{ ok, kind, milestone, tenure, obligations }`.
//    `review(findings, source?, { callId? })`: with a host call id the call
//    books ONCE and every later arrival of the same id answers with the receipt
//    it already produced (`replayed: true`) — unconditionally, whatever moved
//    since: obligations added by other calls, a relay that opened a successor, a
//    milestone advanced. A replay never books, never relays, never hands off and
//    never moves a counter; the receipt it returns names the tenure and
//    milestone of the call that produced it, because that is the receipt of that
//    call. The same id with another payload is refused (`REPLAY_MISMATCH`) and
//    changes nothing. Every booked call id is remembered for the life of the
//    ledger (`#calls`); that map never affects a decision. Kinds:
//    `recorded`, `relay`, `confirm`, `closed`, `askUser`, `handoff`,
//    `nextReview`, `reopened`, `rejected`. Plan's milestones are `S1`, `S2`,
//    `S3`, `待执行`. Plan relays exactly like Manager: an incumbent's empty
//    review below 「待执行」 relays (the first tenure's first review is the
//    exception — it asks on Plan's `S1` and verifies in place on the Manager
//    side), a successor's first empty review advances one milestone
//    (`confirm`), and an empty review at 「待执行」 is the handoff
//    (`review(findings, source?)`). The review carries no text:
//    `source` is the saved draft read from the canonical path, and the handoff
//    sends it byte for byte. A handoff with no draft, an empty one, or a source
//    that is not a string is rejected with `DRAFT_MISSING`; nothing is
//    substituted. A relay receipt states the three facts that must land in one
//    persistent record: `retire`, `openSuccessor`, `barrier`. Manager closing
//    asks `resourcesSettled()`; while it is false the review answers
//    `nextReview` with `RESOURCES_UNSETTLED`. A Manager at `done` rejects every
//    review (`ALREADY_DONE`) until an operator message reopens the work.
//    `askUser` exists in one place only: a `plan` ledger at `S1`, first empty
//    review of the first tenure. Its receipt carries `question` and
//    `interrupt: true` (root §2.4 第 1 步). `askAllowed(ledger)` is the same
//    predicate the surface uses to refuse `this.ask` elsewhere
//    (`ASK_FORBIDDEN_OUTSIDE_S1`). A `manager` ledger's first empty review is
//    `confirm`: an unattended Manager verifies without moving the milestone
//    (it stays at `execute`), and it never asks.
// 9. **A refused review changes nothing.** `DRAFT_MISSING` and `ALREADY_DONE`
//    are decided before any counter or milestone moves, so a Plan that reaches
//    「待执行」 and hands off without a draft keeps its first-review credit, and
//    the handoff itself never consumes a review (the Plan stays at 「待执行」).
// 10. **The successor flag lives only until the successor's first review.** An
//    empty first review advances the milestone; a non-empty one is the normal
//    working period, clears the flag and changes no milestone. When that tenure
//    later empties the ledger, the empty review RELAYS again rather than
//    promoting (根 §2.4 第 5 步). `openSuccessor()` validates before it writes,
//    so a refused successor leaves the ledger untouched, and `reopen` is
//    one-shot and Manager-only: a Plan at 「待执行」 is never reopened, and once a
//    Manager work is back in progress a second message answers `NOT_FINISHED`
//    instead of erasing the ledger.
// 11. **The evaluation window's `open` is a getter, never a method.**
//    `ReviewWindow.open` answers a boolean and is the gate every adapter reads;
//    a method of the same name would share the property key and shadow it, so
//    `window.open === true` would compare a function and answer "shut" with no
//    error to show for it. The round boundary is `beginRound(round?)`
//    (idempotent within a round), the close is `noteReview(receipt)` (the
//    round's first accepted review, once), and `relay(tenure)` closes the
//    current round for a successor. A `plan` window is `notManager` forever,
//    so its `open` is false by construction.
//
// ## CUE presentation (`cue.mjs`, spec §6.3)
//
// Every structured value a model reads is rendered here, never by
// `JSON.stringify` (§6.3, §8.2 step 7). `toCue(value, { name, indent, width })`
// renders a bare value, or one field when `name` is given; `cueScalar(value)`
// renders one value alone (a diagnostic line). Both read enumerable own keys
// only, in insertion order, and never mutate the value.
//
// The string ladder, and the rules behind it, were measured against `cue eval`
// v0.11.0 — every rule below has a case in `cue.test.mjs` and was checked
// against the real binary:
// - plain `"text"` when the body holds no `"`, no `\` and no CR;
// - otherwise a fence of one or more `#`, grown past every `"`+`#`×n run (a
//   closing run) and past a leading `"`+`#`×n (an opening run);
// - a body with a newline is a block. Its lines sit at `indent + step` and its
//   closing `"""`+fence sits there too, so CUE strips a non-empty indent from
//   every body line and the body's own indentation survives;
// - a body that STARTS with `"` is a block whatever the fence (`#""""#` is a
//   block opener to CUE), so such a body is written as a one-line block;
// - under a fence the only escape is `\` + fence: a backslash is written that
//   way, a CR is `\`+fence+`r` (CUE DISCARDS a bare CR from every literal, and
//   a command's CRLF output is ground truth), and a newline inside an inline
//   fenced literal is `\`+fence+`n`. `\(` needs nothing: a fence is present
//   exactly when the body holds a backslash;
// - an empty body line is emitted bare; a whitespace-only line keeps its spaces.
//
// `undefined` is not a JSON value: it is not a field (an array item renders
// `null`, because an index is a position). A function, symbol, bigint, class
// instance or cycle throws `TypeError` — refused, never silently dropped.
// Non-finite numbers render as the strings `"NaN"`/`"+Infinity"`/`"-Infinity"`
// so the output never holds a non-CUE literal. Output is deterministic and
// byte-stable; the fences are linear scans, so a large `source` or `stdout` is
// cheap.
//
// Non-enumerable keys are invisible here, exactly as they were to
// `JSON.stringify`: a receipt's `value`/`result` (attached by `withExtra`) must
// be lifted by the caller before it is presented.
//
// ## Failures
//
// Expected conditions return a receipt (`{ ok: false, code }` for files). Only
// contract violations throw: wrong argument types (`INVALID_ARGUMENT`) and the
// control codes listed above. Fission rejections are PascalCase values in
// `REJECTIONS`, returned in a receipt, never thrown.
//
// ## Not in this package
//
// Host adapters, the `JsProgram` class, member projection per role, fission
// lanes, prompt assembly, persistence, and the Scope/Pair Hint renderers.
