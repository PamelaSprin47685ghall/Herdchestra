(ns hc.compose.mission.worktree
  "Worktree-scoped tool execution for swarm actors.

  The Owner creates one `hc.compose.workspace` runtime per swarm worktree.
  This namespace is the small adapter the app/fleet launcher should call for
  a worker tool request:

  - select the runtime by `:tenure-id` or by exact `:workspace` path;
  - reject a request whose `:workspace` does not match the selected runtime;
  - pass the program role and dynamic flags through to `run-program!`.

  It never falls back to the session/master workspace.  If no runtime
  matches, it returns an explicit failure instead of writing somewhere else."
  (:require [hc.compose.workspace :as ws]))

(defn- tool-runtime
  "Return the `hc.compose.workspace` runtime inside a fleet runtime map."
  [runtime]
  (or (:workspace-runtime runtime) runtime))

(defn- runtime-root
  [runtime]
  (or (some-> (tool-runtime runtime) :root str)
      (some-> (:workspace runtime) str)))

(defn runtime-for
  "Select a worktree runtime from a map of tenure-id -> runtime.

  A runtime may be a raw `hc.compose.workspace` runtime or a fleet runtime
  map with `:workspace-runtime` and `:workspace`.  Preference: exact
  `:tenure-id`, then exact `:workspace` path, then the sole runtime when only
  one exists.  Never guesses among several."
  [runtimes {:keys [tenure-id workspace]}]
  (or (when tenure-id (get runtimes tenure-id))
      (when workspace
        (some (fn [[_ runtime]]
                (when (= (str workspace) (runtime-root runtime))
                  runtime))
              runtimes))
      (when (= 1 (count runtimes))
        (val (first runtimes)))))

(defn- fail
  [code message details]
  {:status :failed
   :error (cond-> {:kind code :message message}
            (some? details) (assoc :details details))})

(def ^:private program-roles
  "Mission role -> program role for worktree tool execution.

  The Steward and Meditator normally run outside worktree execution, but an
  explicit `execute-program!` request must still resolve their role instead
  of falling through to an unknown program role."
  {:plan :clj-plan
   :reviewer :clj-review
   :worker :clj-worker
   :holder :clj-worker
   :plan-worker :dedicated-worker
   :dedicated-worker :dedicated-worker
   :steward :clj-steward
   :meditator :clj-meditator})

(defn program-role-for
  "Return the program role for a worktree tool REQUEST.

  A holder is a clj-worker with `:holder? true`.  The Steward and Meditator
  are never holders, so a spoofed holder flag on them is ignored instead of
  promoting the request to a swarm worker.  A caller-supplied
  `:program-role` wins."
  [request]
  ;; A holder must never inherit the Plan dedicated-worker capability set:
  ;; that role has no write/split/claim/finish/take-or-die and would also
  ;; skip the static holder Shell gate.  Correct an inconsistent caller
  ;; instead of leaking the role.
  (let [raw-role (:role request)
        role (if (or (string? raw-role) (symbol? raw-role))
               (keyword raw-role)
               raw-role)
        stewardship? (contains? #{:steward :meditator} role)]
    (if (and (:holder? request) (not stewardship?))
      :clj-worker
      (or (:program-role request)
          (get program-roles role)
          role
          :clj-worker))))

(defn execute-program!
  "Run one actor tool program against the matching worktree runtime.

  REQUEST is the Owner tool-call request enriched with `:workspace`,
  `:program-role`, `:holder?`, `:s1?`, `:write-unlocked?`, `:draft-path`,
  `:agent-id`, `:claim-id`, `:task-id`, and `:attempt-id`.

  Returns the `hc.compose.workspace/run-program!` result verbatim, or an
  explicit `:WORKSPACE_NOT_FOUND` / `:WORKSPACE_MISMATCH` failure."
  [runtimes request]
  (let [runtime (runtime-for runtimes request)
        request-workspace (:workspace request)
        selected-root (runtime-root runtime)]
    (cond
      (nil? runtime)
      (fail :WORKSPACE_NOT_FOUND
            "no worktree runtime matches the request"
            {:tenure-id (:tenure-id request)
             :workspace request-workspace
             :runtimes (vec (keys runtimes))})

      (and request-workspace
           selected-root
           (not= (str request-workspace) selected-root))
      (fail :WORKSPACE_MISMATCH
            "the request workspace does not match the selected runtime"
            {:workspace request-workspace
             :runtime-root selected-root
             :tenure-id (:tenure-id request)})

      :else
      (ws/run-program!
       (tool-runtime runtime)
       (assoc request :role (program-role-for request))))))
