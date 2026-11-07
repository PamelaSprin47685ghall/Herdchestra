# steward_sphinx — Standing Sphinx and Steward Decision Frontier

Local specification for `src/hc/core/steward_sphinx`.  The root
`AGENTS.md` §2.2 is authoritative; this file records how the package realizes
that section and where its boundary ends.

## Purpose

The package is the repository-watch layer:

- `Standing Sphinx` cognition: evidence-coupled beliefs, stale-assumption
  invalidation, local sub-questions, and a Current Best Answer that never
  becomes final.
- `Stewardship Decision Frontier`: pull-only detection of new operator intent
  or meaningful unspent compute.
- `Steward` Docket: admission, deferral, rejection, and legitimate idleness.

It is a pure core.  It does not call a model, start Plan, fork worktrees,
write files, or persist state.  State is plain EDN and can be committed by the
host as one value.

## Boundary

- No dependency on another `hc/core` package.
- No Git process invocation and no filesystem access.  A server adapter turns
  a Git observation into a map and calls `ingest-evidence`.
- No scheduling, worktree, or swarm-runtime logic.  `decide` only updates the
  Docket; the runtime owns fork/join and Plan handoff.
- The package never edits shared project files.  Tests live under
  `test/hc/core/steward_sphinx/` and in the facade test
  `test/hc/core/steward_sphinx_test.clj`.

## Model

### Evidence

A Git commit is identified by `git:<commit>`.  Re-submitting the same identity
with different content is `:EVIDENCE_CONFLICT`; re-submitting identical
content is a no-op.  Other evidence kinds (`:operator-intent`,
`:observation`) must carry a non-blank `:evidence-id`.

A commit records `:commit`, `:parents`, `:changed-paths`, and optional
`:subject`, `:tree`, `:author`, `:committed-at`, `:insertions`, `:deletions`.
The Sphinx tracks `:head`, `:previous-head`, `:last-observed-commit`,
observed commits, and a continuity label (`:initial`, `:linear`,
`:discontinuous`) for each Git observation.  `:last-observed-commit` is the
most recently ingested commit.  `:head` advances when the incoming commit is
not an ancestor of the current head (the initial commit, a descendant, or a
discontinuous observation); re-ingesting an ancestor leaves `:head` unchanged
while the evidence log and `:last-observed-commit` still advance.
`:best-answer :based-on-commit`
uses `:head`, so out-of-order evidence cannot regress it.  Discontinuous
history is still evidence; it is not rejected.

### Assumptions and claims

An assumption has `:assumption-id`, `:statement`, `:confidence` in `[0, 1]`,
`:watch-paths`, and a non-empty `:basis` of evidence ids.  Its standing is
derived:

- `:active` when no evidence newer than its newest basis evidence touched a
  watched path;
- `:invalidated` when such evidence exists;
- `:retired` when explicitly retired.

No watch paths means repository-wide.  Evidence with no changed paths does
not invalidate anything.  An invalidated assumption is not deleted; it can be
revalidated by a later revision that gives it a newer basis.

A claim has `:claim-id`, `:statement`, `:confidence`, a positive finite
`:weight`, non-empty `:evidence-ids`, optional `:assumption-ids`, and a
`:dimension` keyword drawn from `known-dimensions`.  `:open` is the explicit
escape for a dimension nobody has named yet.  Claims that rest on invalidated
or retired assumptions leave the Current Best Answer; they are reported under
`:excluded-claims` and make the answer `:stale? true`.  A retired assumption
cited by an excluded claim remains visible in `:assumptions` with
`:status :retired`, even when no active claim cites it.

### Current Best Answer

`rebuild-best-answer` always returns an answer:

- root question `"How can this be better?"`, always `:open`;
- summary and frontier of thought from the latest revision;
- active claims with weighted confidence;
- cited evidence summaries;
- cited, invalidated, and retired assumption details;
- open and resolved local sub-questions, including resolved answers;
- `:non-final? true` and the current cognition version.

The initial answer is an explicit no-answer.  It exists before any evidence
arrives.

### Revision discipline

`revise-best-answer` is accepted only when `:evidence-seq` is greater than
`:reasoning :last-revised-evidence-seq`.  Repeated reasoning without new
evidence is refused with `:NO_NEW_EVIDENCE`.  Confidence is not monotonic:
a later revision may raise or lower it.  Confidence, weights, and policy
thresholds must be finite numbers; NaN and infinity are refused.

### Decision Frontier

`evaluate-frontier` reads `{ :intent :capacity :cognition }` and emits a
frontier only when:

1. the intent revision, text, or `:received-at` differs from the observed
   intent cursor; or
2. spare capacity is at least `:min-spare` and admitted work has zero
   `:absorbable` capacity.

A new Current Best Answer alone never creates a frontier.  The Sphinx cannot
call the frontier; only the world observation does.  A frontier is emitted
once per intent signature and capacity signature.  Both cursors advance even
when no frontier is emitted, so the same world does not trigger repeatedly.

The frontier carries the current intent, the Current Best Answer snapshot,
the capacity observation, the reasons, and `:pull-only? true`.  The cognition
must expose a non-negative integer `:cognition-version`, and its
`:best-answer` must expose the same non-negative integer version and
`:non-final? true`; otherwise the observation is `:INVALID_COGNITION`.

### Docket and admission

`decide` requires a real frontier and a Sphinx cognition.  The frontier must
carry a non-blank `:frontier-id`, a non-empty `:reasons` sequence drawn only
from `:operator-intent` and `:capacity`, `:pull-only? true`, a consistent
`:cba-version` and `:cognition-version`, and a `:best-answer` whose
`:cognition-version` matches the supplied cognition and is `:non-final? true`;
the cognition's own best answer must also be non-final.
An optional `:frontier-seq` must be a positive integer.  A `:capacity` reason
also requires a capacity observation that passes `normalize-capacity` and
`capacity-frontier?`; its validated `:spare` is the value used for admission.

Candidate Missions carry consequence, evidence strength, dependency cost,
reversibility, opportunity cost, demand, evidence ids, optional assumption
ids, optional `:depends-on` mission ids, and an optional boolean
`:operator-aligned?`.  `:operator-aligned? false` always contradicts current
operator intent and is rejected, on any frontier.  `:operator-aligned? true`
waives the minimum-reversibility gate only on an operator-intent frontier;
the explicit policy override
`:allow-irreversible-without-intent?` remains available.  The score is:

```text
consequence*wc + evidence*we + reversibility*wr
- dependency*wd - opportunity*wo
```

Weights are finite, non-negative, and normalized to sum to one.  The default
threshold is `0.5`, the default minimum evidence strength is `0.5`, and the
default minimum reversibility is `0.5`.

The Steward admits, defers, rejects, or stays idle:

- **admit**: every gate passes and the score reaches the threshold; a Mission
  is added to the Docket with status `:admitted`;
- **defer**: evidence is weak or stale, a dependency is incomplete, capacity
  is insufficient, or an irreversible change needs operator intent;
- **reject**: the candidate contradicts current operator intent, has no
  evidence, or scores below the threshold; it enters `:do-not-do` and remains
  reconsiderable;
- **idle**: no candidate is admitted.  This is legitimate: the system is
  work-conserving without inventing work.

Dependency ids must refer to existing Docket missions; an unknown id is
`:INVALID_CANDIDATE`, not an eternal wait.  A dependency is satisfied only
when its status is `:completed`.  A capacity-only frontier also requires
`demand <= spare`; an operator-intent frontier does not.
A candidate whose id already belongs to an `:admitted`, `:planning`, or
`:active` mission is deferred as `:already-admitted` rather than admitted
twice; that reason takes precedence when choosing the deferral bucket.  A
candidate that contradicts operator intent is rejected even when it is also
already admitted.

The frontier's Current Best Answer and the supplied cognition must name the
same cognition version.  A mismatch is `:STALE_FRONTIER`; the Steward decides
on the answer it actually observed.

Decisions are idempotent by `:request-id`.  The same payload replays the
original receipt; a different payload is `:DECISION_REQUEST_CONFLICT`.
Duplicate candidate ids, duplicate assumption ids in one revision, and
duplicate claim ids in one revision are refused rather than silently
collapsed.  A rejected candidate stays in `:do-not-do` when a later decision
merely defers it; a deferred entry records `:previously-rejected? true` in
that case.

`transition-mission` supports
`:admitted -> :planning|:active|:abandoned`,
`:planning -> :active|:abandoned|:failed`, and
`:active -> :completed|:abandoned|:failed`.

`score-candidate` normalizes finite weights defensively; an invalid policy
returns `{:score nil :components {} :error ...}` instead of producing NaN.

## Public API

Facade namespace: `hc.core.steward-sphinx`.

Evidence:

- `normalize-evidence`, `normalize-path`, `normalize-paths`,
  `path-prefix?`, `repo-wide-path?`, `affects-assumption?`,
  `evidence-summary`.

Sphinx:

- `initial-cognition`, `ingest-evidence`, `revise-best-answer`,
  `open-question`, `resolve-question`, `best-answer`,
  `rebuild-best-answer`, `assumption-status`, `assumption-view`,
  `claim-evaluation`, `claim-view`, `cognition-version`, `evidence-count`.

Frontier:

- `initial-frontier-state`, `normalize-intent`, `normalize-capacity`,
  `capacity-frontier?`, `capacity-signature`, `evaluate-frontier`.

Docket:

- `initial-docket`, `normalize-policy`, `normalize-candidate`,
  `score-candidate`, `decide`, `transition-mission`, `docket-summary`.

## Result shapes

Every fallible operation returns either:

```clojure
{:ok? true ...}
```

or:

```clojure
{:ok? false :state state :error {:code ... :message ... :details ...}}
```

`decide` returns `{:ok? true :state docket' :docket docket' :receipt receipt}`;
a replay adds `:replayed? true`.  `evaluate-frontier` returns
`{:ok? true :state state' :frontier frontier-or-nil :reasons [...]}`.
`score-candidate` is a calculation rather than a state transition: a valid
policy returns `{:score ... :components ...}`, and an invalid policy returns
`{:score nil :components {} :error ...}`.

## Tests

`test/hc/core/steward_sphinx/` contains deterministic state-machine tests and
`clojure.test.check` properties for evidence idempotence, path invalidation,
confidence bounds, score monotonicity, and pull-only frontier behavior.  The
facade end-to-end test lives at `test/hc/core/steward_sphinx_test.clj`; both
locations are part of the package suite.

Run the package tests with:

```bash
clojure -X:test :nses '[hc.core.steward-sphinx-test
                        hc.core.steward-sphinx.evidence-test
                        hc.core.steward-sphinx.cognition-test
                        hc.core.steward-sphinx.frontier-test
                        hc.core.steward-sphinx.docket-test]'
```

The full suite is `clojure -X:test`; package tests must not require another
core package to be complete.

## Limitations

- The Sphinx does not reason by itself.  A model or caller supplies summaries,
  assumptions, claims, and confidence; this package enforces evidence
  discipline and derives the answer.
- Git evidence is data, not a Git client.  Parsing `git log`, ancestry
  queries, and worktree state belong to a server adapter.  Head ancestry is
  derived only from observed `:parents`; an unobserved intermediate commit
  cannot be classified.
- The Docket records admission and a minimal mission lifecycle.  It does not
  perform Plan handoff, fork/join, worktree allocation, or swarm scheduling.
- Decision payloads are retained in `:decisions` for request-id idempotence.
  A host that needs a hard bound may prune entries it will never retry.
- There is no persistence, event replay, or clock.  The host commits the EDN
  state and supplies observations.
- Candidate scores are the Steward's declared inputs, not a substitute for
  judgment.  The core makes the gates and the Docket auditable; it does not
  prove that a candidate is a good idea.
- Operator intent is compared by revision, text, and `:received-at`.  A
  caller that edits the text or supplies a new `:received-at` without
  changing the revision still produces a frontier, which is intentional.
