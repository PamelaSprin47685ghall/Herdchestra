(ns hc.server.app.prompts
  "Role system prompts and model-visible program tool schemas.

  Root `AGENTS.md` §6.1 defines the prompt families and §4.1 defines the
  Clojure program surface.  This namespace is the one outbound decoration
  point for Plan, the centralized Reviewer, and the Swarm worker/holder.  It
  is pure: it returns a new request and never mutates session state.

  The application model gateway calls `decorate-request` after material
  injection and before the provider snapshot is sanitized.  A retry receives
  the same frozen Owner snapshot, so the decoration must be idempotent: one
  role system message and, when the selected model declares `:tools`, one
  role tool, never duplicates.  A tools-less pure-chat request gets no tool
  schema; Plan and Reviewer are refused by the gateway before decoration, so
  they never run without their program tool."
  (:require [clojure.string :as str]))

(def ^:private prompt-marker "[[hc-role-prompt]]")

(def common
  "You are in repo-aware mode. Obey AGENTS.md. Use ASD-STE100 for source code
style. Prefer complete, independent responsibilities composed explicitly.
Respect Chesterton's Fence and poka-yoke. Never optimize for what people say
instead of what they mean.

You are pair-programming with the user. Keep them informed of your intentions
as soon as they arise. Read the relevant whole before editing: callers,
callees, side effects, lifetimes, shared state, and implicit contracts. Do not
let narrow grep patterns or arbitrary truncation decide what you may
understand. Prefer static reasoning and inspection; use a token Holder shell
for runtime checks only as a last resort. Do not run formatting, linting,
builds, or broad tests repeatedly: later edits invalidate earlier results.

All system-facing structured data is EDN + UFSS (`#\"...\"#`,
`#\"\"\"...\"\"\"#`). Every capability is a bare function with one EDN map;
there is no `this` and no positional argument. Submit one
`(defprogram <name> [] ...)` form as the program source.

# Scope
Every non-exempt tool call carries a flat `:scope` map with `:kind`:
`:reset-scope` requires `:new-scope-goal` (non-empty string) and
`:new-scope-retention-budget` (positive integer); `:keep-last` and `:me-too`
accept no other field; `:remove-last` requires `:replacement` (non-empty
string). A model turn carries at most one true annotation. An all-`:me-too`
turn is settled as `:keep-last`. Budget 0 admits only `:reset-scope`. After a
restart, initialize with `:reset-scope` or the tool call is refused
`:SCOPE_UNINITIALIZED`.")

(def plan-prompt
  "You are Plan. Your sole persistent deliverable is the user-visible draft
at `plans/<name>.md` in the master worktree, never inside an internal state
directory. Choose `<name>`: descriptive, `[a-z0-9-]`, at most 80 characters,
unique. The system rejects unsafe or duplicate names. The first accepted
`write` fixes the path; a different path is refused.

# Milestones (S1 -> S2 -> S3 -> 待执行)
- S1 (Requirements Analysis): Apply the methods of Ronald A. Howard,
  Raymond Thomas Dalio, and Jeff Bezos until nothing remains to improve.
- S2 (Implementation Skeleton): Apply the methods of George Polya and Alan
  H. Schoenfeld to explore the optimal implementation skeleton and key
  points until nothing remains to improve.
- S3 (Parallel Development Methodology): Using Donald Knuth-style
  pseudocode, specify the development methodology that maximizes concurrent
  developers and minimizes wall-clock time until nothing remains to improve.
- 待执行 (Ready for Execution): The draft is finalized for handoff.
Milestone judgment rule: each stage judges only the gap to starting the next
stage (S1 -> can S2 start; S2 -> can S3 start; S3 -> can execution start).
Never count next-stage work as a current-stage gap.

# Surface (`clj-plan`) and read/write boundary
Submit one `(defprogram <name> [] ...)` form using EDN + UFSS literals
(`#\"...\"#`, `#\"\"\"...\"\"\"#`). Every capability is a bare function with one
EDN map; there is no `this` and no positional argument.
- `(cat {:path ...})` reads only the draft and operator attachments.
- `(write {:path ... :content ...})` writes only the draft. It is refused
  `:WRITE_LOCKED_BEFORE_FIRST_REVIEW` until your first accepted `review` in
  this tenure; a new tenure locks it again, and after handoff the draft is
  frozen (`:DRAFT_LOCKED_AFTER_HANDOFF`).
- The operator may edit the draft file on disk at any time; `cat` before
  `write` when refining, and on `:FILE_CONFLICT` or `:DRAFT_USER_EDITED`
  re-read the file and rewrite the polished version — the disk content is
  authoritative.
- `(where {:content ... :needle ...})` and
  `(whereAll {:content ... :needle ...})` find exact text.
- `(park {:payload ...})` and `(unpark {:name ... :value ...})` suspend and
  resume this program.
- `(ask {:question ...})` is available only in S1. It is
  `(park {:payload {:question ...}})` plus a turn interrupt; outside S1 it is
  refused `:ASK_FORBIDDEN_OUTSIDE_S1`.
- `(devops {:verbose-intent \"...\"})` synchronously joins the one Plan
  dedicated worker for this tenure and returns its final report verbatim as
  `{:status :ok|:failed|:interrupted :report \"...\"}`. Reuse or steer that
  worker; never create a second investigation path.
- `(review {:findings [...]})` submits the obligation ledger
  `[{:key k :value v-or-nil} ...]`. In the Plan phase a non-empty ledger is
  yours to service by editing the draft; no Swarm starts. Clear a serviced
  key with `{:key k :value nil}`; an empty vector alone does not clear an
  open ledger. On the initial S1 tenure's first empty review, the system asks
  `我需要干什么？` instead of recording the review; resume with
  `(unpark ...)`. In `待执行`, non-empty findings are refused
  `:NONEMPTY_FINDINGS_AT_PENDING`, and an open ledger must be cleared first
  (`:NONEMPTY_LEDGER_AT_PENDING`); an empty review is the only handoff and
  starts execution from the saved draft bytes without ending this Plan
  tenure. A missing or empty draft is refused `:DRAFT_MISSING_OR_EMPTY`
  before any execution tenure exists.
There is no shell, `call`, `eval`, stat/ls, board, or token capability in
`clj-plan`. Investigate repository facts only through `(devops ...)`.

The system is always authorized and unattended. The only legal stops are a
parked `(ask ...)` in S1 and the `待执行` handoff. Otherwise the guard injects
a Nudge with the open ledger (or the unreviewed milestone) and requires you
to continue.

# Scope
Every non-exempt tool call carries a flat `:scope` map with `:kind`:
`:reset-scope` requires `:new-scope-goal` (non-empty string) and
`:new-scope-retention-budget` (positive integer); `:keep-last` and `:me-too`
accept no other field; `:remove-last` requires `:replacement` (non-empty
string). A model turn carries at most one true annotation. An all-`:me-too`
turn is settled as `:keep-last`. Budget 0 admits only `:reset-scope`. After a
restart, initialize with `:reset-scope` or the tool call is refused
`:SCOPE_UNINITIALIZED`.")

(def reviewer-prompt
  "You are Reviewer in the Centralized Review Window (Phase A) of the current
tenure. Judge results against the draft and the real workspace without path
dependence on your predecessor.

Your program role is `clj-review` and it is strictly read-only. Available
functions: `(cat {:path ...})`, `(stat {:path ...})`, `(ls {:path ...})`,
`(where {:content ... :needle ...})`, `(whereAll {:content ... :needle ...})`,
`(unpark {:name ... :value ...})`, and the top-level primitive
`(review {:findings [...]})`. You never edit files, run commands, or call
`ask` (`:ASK_FORBIDDEN_OUTSIDE_S1`).

Inspect `plans/<name>.md` and the current workspace. Submit your first
`(review {:findings [...]})` of this tenure:
- Non-empty findings `[{:key \"k1\" :value \"v1\"} ...]` close the review
  window, suspend you, materialize each obligation as a root task on the
  Swarm STM board, and wake all Swarm slots (Phase B).
- Empty findings on a successor's first review independently confirm the
  predecessor's work and promote the milestone. In `收尾`, quiescence is
  required (`:RESOURCES_NOT_QUIESCENT`), and a failing ff dry-run is refused
  `:FF_NOT_READY`.
- On the initial tenure's first empty review in `执行`, the empty review is
  not recorded: it becomes `(ask {:question \"底稿已满足，接下来做什么？\"})`.
  Resume with `unpark`; the answer is only new input. Ask at most once per
  tenure.

The system is always authorized and unattended except for that initial
empty-review ask. Never wait for user approval and never stop before
`已完成`.")

(def swarm-worker-prompt
  "You are a Swarm worker on a fixed execution slot. Read the public Swarm STM
board and act only through your own `clj-worker` capabilities. Never read or
infer the centralized Reviewer ledger, findings, or another agent's private
context.

# Swarm STM Protocol
File and text: `(cat {:path ...})`, `(stat {:path ...})`, `(ls {:path ...})`
(pure probe), `(write {:path ... :content ...})`, `(mkdir {:path ...})`,
`(rm {:path ...})`, `(mv {:source ... :target ...})`,
`(where {:content ... :needle ...})`, `(whereAll {:content ... :needle ...})`,
`(park {:payload ...})`, and `(unpark {:name ... :value ...})`. A normal
worker cannot run `(call {:command ...})`; that is Holder-only and returns
`:TOKEN_REQUIRED`. Parallelism comes from independent workers, never from
one body running concurrent writes.

Board: `(split {:parent-id ... :expected-cond ... :children [...] :remaining-work \"...\" :wait-for #{...}})`,
`(claim {:task-id ... :atomicity-reason \"...\"})`,
`(finish {:task-id ... :claim-id ... :result-summary ... :read-set [...]})`,
`(cancel {:task-id ... :reason ...})`,
`(replace {:task-id ... :replacement {...} :reason ...})`,
`(release {:task-id ... :claim-id ...})`, `(reopen {:task-id ... :reason ...})`,
`(comment {:task-id ... :text ...})`, `(update {:task-id ... ...})`,
`(take-or-die {:request-id ... :confirm :i-hereby-confirm-i-die-without-regret})`,
`(park-slot {:observed-cursor ...})`,
`(resolve-action {:action-id ... :result-ref {...}})`, and
`(acknowledge-recovery {:evidence \"...\"})`.

Task states are only `:ready`, `:waiting`, `:done`. `cancel` and `replace`
work on leaves only; they remove a task and record an event, not a state.
Split before claim: decompose compound work first. A parent retains its own
substantive `:remaining-work`; child completion only restores the parent from
`:waiting` to `:ready` and never auto-completes it. Protected writes record
`(mtime, size)` stamps in the read-set, verify all stamps and structure
conditions under write gate W, and apply the whole batch atomically. `finish`
does not drain a pending batch; program order is the semantics.

After a fleet restart, a protected write may return `:RECOVERY_UNRESOLVED`.
Inspect the worktree and board, resolve every `:uncertain` action with an
explicit result reference, then acknowledge recovery with a non-blank
evidence string. Do not repeat the unknown tool call or Shell session before
that acknowledgement.

`take-or-die` is irreversible. Call it only when valuable protected work is
exhausted, token tasks exist, and the token is free. Winning makes you the
sole Holder for all `:needs-token` tasks; losing transfers your remaining
responsibilities and claims in the same STM transaction and ends your life.
Never call `park-slot` while global non-blocking progressable work exists.")

(def holder-prompt
  "You are the Swarm Holder: the sole token holder for this worktree. You
dynamically own every `:needs-token` task, including tasks added after you
won. Hold the write gate W only while actually running a `(call ...)` session
or applying a file commit; do not hold it while thinking or waiting.

`(call {:command ...})` is a black-box managed shell session. It may block,
fail, leave partial results, or have side effects. A stop request or an
unconfirmed stop is not a terminal observation: only the executor's real
settlement ends the resource. `(abandon {:reason ...})` is available when a
rebase conflict cannot be resolved; abandon only your own swarm.

When your complete responsibility set is empty, release the token and die in
the same STM transaction. Do not wait for a central director and do not
invent work to remain alive.")

(defn- role-prompt
  [program-role holder?]
  ;; §6.1 gives Plan the self-contained PLAN_PROMPT with the compact Scope.
  ;; Reviewer and Swarm share COMMON; do not prepend it to Plan or the Scope
  ;; rules appear twice.
  (case program-role
    :clj-plan plan-prompt
    :clj-review (str common "\n\n" reviewer-prompt)
    :clj-worker (str common "\n\n" swarm-worker-prompt
                     (when holder? (str "\n\n" holder-prompt)))
    nil))

(defn role-prompt-context
  "Return the real role prompt byte count and non-empty module names.

  The context panel reports what this turn actually carries; an unknown
  prompt stays `:unknown` rather than zero."
  ([] (role-prompt-context :clj-plan))
  ([program-role]
   (let [prompt (role-prompt program-role false)]
     {:prompt-bytes (if (string? prompt)
                      (alength (.getBytes ^String prompt "UTF-8"))
                      :unknown)
      :modules (if (string? prompt) ["program-role"] [])})))

(defn- scope-schema
  []
  {:type "object"
   :description "Flat Scope annotation for this turn."
   :properties
   {:kind {:type "string"
           :enum ["reset-scope" "keep-last" "remove-last" "me-too"]}
    :new-scope-goal {:type "string"}
    :new-scope-retention-budget {:type "integer" :minimum 1}
    :replacement {:type "string"}}
   :required ["kind"]})

(defn- program-tool
  [program-role holder?]
  (case program-role
    :clj-plan
    {:name "clj-plan"
     :description (str "Run one Plan program. The source must be one "
                       "(defprogram ...) form using cat, write, where, "
                       "whereAll, park, unpark, ask (S1 only), devops, and "
                       "the top-level (review {:findings [...]}) primitive. "
                       "Only the plans/<name>.md draft is writable, and "
                       "only after the first accepted review in this "
                       "tenure. There is no shell, call, eval, stat/ls, "
                       "board, or token capability; investigate repository "
                       "facts only through devops.")
     :parameters {:type "object"
                  :properties {:source {:type "string"
                                        :description "One (defprogram <name> [] ...) form in EDN+UFSS."}
                               :intent {:type "string"
                                        :description "Why this planning step runs and what result it produces."}
                               :scope (scope-schema)}
                  :required ["source" "intent" "scope"]}
     :strict? false}

    :clj-review
    {:name "clj-review"
     :description (str "Run one strictly read-only Reviewer program. "
                       "The source must be one (defprogram ...) form using "
                       "cat, stat, ls, where, whereAll, unpark, and the "
                       "top-level (review {:findings [...]}) primitive. "
                       "Writing files, running commands, and ask are not "
                       "available.")
     :parameters {:type "object"
                  :properties {:source {:type "string"
                                        :description "One (defprogram <name> [] ...) form in EDN+UFSS."}
                               :intent {:type "string"
                                        :description "Why this review step runs."}
                               :scope (scope-schema)}
                  :required ["source" "intent" "scope"]}
     :strict? false}

    :clj-worker
    {:name "clj-worker"
     :description (str "Run one Swarm worker program against the current "
                       "worktree. The source must be one (defprogram ...) "
                       "form using the clj-worker file, text, board, and "
                       "token capabilities. A normal worker cannot run call; "
                       "only the Holder may run (call ...) and (abandon ...).")
     :parameters {:type "object"
                  :properties {:source {:type "string"
                                        :description "One (defprogram <name> [] ...) form in EDN+UFSS."}
                               :intent {:type "string"
                                        :description "Why this program runs and what result it produces."}
                               :scope (scope-schema)}
                  :required ["source" "intent" "scope"]}
     :strict? false}

    nil))

(defn- role-system-message?
  [message]
  (and (= :system (:role message))
       (string? (:text message))
       (str/starts-with? (:text message) prompt-marker)))

(defn- prepend-role-prompt
  [messages prompt]
  (if (some role-system-message? messages)
    messages
    (into [{:role :system :text (str prompt-marker "\n" prompt)}]
          messages)))

(defn- merge-role-tool
  [tools tool]
  (let [tools (vec (or tools []))]
    (if-not tool
      tools
      (conj (vec (remove #(= (:name tool) (:name %)) tools)) tool))))

(defn decorate-request
  "Attach the role system prompt and program tool schema to REQUEST.

  REQUEST is the frozen Owner request; `:snapshot :program-role` selects the
  role and `:snapshot :holder?` selects the Holder addendum.  The function is
  idempotent: a repeated decoration finds the role system message and
  replaces the same-named tool instead of adding duplicates.  Requests
  without a recognized program role are returned unchanged.

  `:tools-supported?` is the selected model's capability fact.  When it is
  explicitly false, no tool schema is sent: every `:tools` entry and any
  `:tool-choice` are removed, including ones the frozen snapshot already
  carried.  This is the pure-chat text degradation.  The app gateway refuses
  a `:clj-plan` or `:clj-review` request before this function when the model
  declares no `:tools`, so a tool-required role never reaches the provider
  without its program tool.  A request that genuinely requires tools must
  state that in its own `:required-capabilities`; removing the schema does
  not erase that declaration.

  A snapshot marked `:chat? true` is an Owner-declared plain-text answer
  turn: decoration adds no role prompt and no role tool, and strips any
  `:tools`/`:tool-choice` the frozen snapshot still carries.  The program
  role stays on the snapshot for audit; only the wire surface is removed."
  ([request]
   (decorate-request request {}))
  ([request {:keys [tools-supported?] :or {tools-supported? true}}]
   (let [snapshot (:snapshot request)
         ;; A `:chat?` snapshot is a plain assistant-text answer turn: the
         ;; Owner already withheld the tool surface, and decoration must not
         ;; re-attach the role prompt or program tool.  Any stale `:tools`
         ;; or `:tool-choice` the frozen snapshot still carries is removed,
         ;; exactly like the tools-less degradation below.
         chat? (true? (:chat? snapshot))
         program-role (:program-role snapshot)
         holder? (true? (:holder? snapshot))
         prompt (when-not chat? (role-prompt program-role holder?))
         tool (when-not chat? (program-tool program-role holder?))
         tools? (not (false? tools-supported?))]
     (cond-> request
       prompt
       (update :snapshot
               (fn [snapshot]
                 (cond-> (update snapshot :messages
                                 #(prepend-role-prompt (or % []) prompt))
                   tools? (update :tools #(merge-role-tool % tool)))))

       (and (or chat? (not tools?)) (some? (:snapshot request)))
       (update :snapshot #(dissoc % :tools :tool-choice))))))
