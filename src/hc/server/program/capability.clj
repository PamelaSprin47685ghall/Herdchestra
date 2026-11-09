(ns hc.server.program.capability
  "Role capability sets for the Clojure program sandbox.

  Authority: root `AGENTS.md` §4.1.  A role grants a set of program-visible
  capability keywords.  The runtime additionally checks dynamic conditions
  that a static set cannot express: `ask` only in Plan S1, `call` only for the
  Swarm holder or the Plan dedicated worker, `abandon` only for the holder,
  and Plan's draft/read path restrictions.  The Steward and Meditator roles
  reflect root §2.1–§2.2: the Steward may read, fork/join disjoint worktree
  swarms, and observe the fleet aggregate; the Meditator is read-only.

  This namespace is pure data and predicates.  It performs no I/O.")

(def roles
  "The program roles and their static capability sets."
  {:clj-plan
   {:label "Plan"
    :capabilities #{:cat :write :where :whereAll :park :unpark :ask
                    :devops :review}}

   :clj-review
   {:label "Reviewer"
    :capabilities #{:cat :stat :ls :where :whereAll :unpark :review
                    :askPreviousScope :assume :negotiate}}

   :clj-worker
   {:label "Swarm worker"
    ;; `:resolve-action` and `:acknowledge-recovery` are deliberate
    ;; program-surface extensions required by the F13 recovery flow and the
    ;; worker system prompt; root §4.1's table does not list them yet.  The
    ;; rest of this set mirrors §4.1 exactly.
    :capabilities #{:cat :stat :ls :write :mkdir :rm :mv
                    :where :whereAll :park :unpark :call
                    :split :claim :finish
                    :cancel :replace :release :reopen :comment :update
                    :take-or-die :park-slot :abandon
                    :resolve-action :acknowledge-recovery
                    :askPreviousScope :assume :negotiate}}

   :clj-steward
   {:label "Steward"
    ;; The Steward owns the Docket and the fork/join queue.  It reads the
    ;; repository and the bounded fleet aggregate; it never writes product
    ;; code and never runs a shell.
    :capabilities #{:cat :stat :ls :where :whereAll :park :unpark
                    :fork :join :fleet-state :assume}}

   :clj-meditator
   {:label "Meditator"
    ;; The Standing Sphinx is strictly read-only: it publishes its Current
    ;; Best Answer without touching the workspace.
    :capabilities #{:cat :stat :ls :where :whereAll}}

   :dedicated-worker
   {:label "Plan dedicated worker"
    :capabilities #{:cat :stat :ls :where :whereAll :park :unpark :call}}})

(def program-functions
  "Program-visible function name -> capability keyword.

  The single-call-shape law is enforced by the sandbox: every function takes
  exactly one EDN map.  `checkpoint!` is internal and injected by the
  compiler, not part of the capability map."
  '{cat :cat
    stat :stat
    ls :ls
    negotiate :negotiate
    write :write
    mkdir :mkdir
    rm :rm
    mv :mv
    where :where
    whereAll :whereAll
    park :park
    unpark :unpark
    ask :ask
    call :call
    devops :devops
    review :review
    fork :fork
    join :join
    fleet-state :fleet-state
    split :split
    claim :claim
    finish :finish
    cancel :cancel
    replace :replace
    release :release
    reopen :reopen
    comment :comment
    update :update
    take-or-die :take-or-die
    park-slot :park-slot
    resolve-action :resolve-action
    acknowledge-recovery :acknowledge-recovery
    abandon :abandon
    askPreviousScope :askPreviousScope
    assume :assume})

(def draft-path-pattern
  "The only safe Plan draft path: plans/<lower-case-name>.md."
  #"plans/[a-z0-9-]{1,80}\.md")

(defn draft-path?
  [path]
  (boolean (and (string? path) (re-matches draft-path-pattern path))))

(def control-primitives
  "Top-level primitives delegated through the runtime's `:control` port."
  #{:devops :review :fork :join :fleet-state
    :split :claim :finish :cancel :replace :release :reopen
    :comment :update :take-or-die :park-slot
    :resolve-action :acknowledge-recovery :abandon
    :askPreviousScope :assume :negotiate})

(defn role?
  [role]
  (contains? roles role))

(defn capabilities
  "Static capability set for ROLE, or nil for an unknown role."
  [role]
  (get-in roles [role :capabilities]))

(defn capability?
  [role capability]
  (contains? (capabilities role) capability))

(defn narrowed
  "Apply an optional caller-supplied narrowing set.

  A missing set means no narrowing.  Unknown capability keywords are kept
  only when the role already grants them, so a request cannot widen a role."
  [role requested]
  (let [base (capabilities role)]
    (if (nil? requested)
      base
      (into #{} (filter base) requested))))

(defn denial
  "Build a structured capability refusal."
  ([status message]
   (denial status message {}))
  ([status message details]
   {:status status
    :error status
    :message message
    :details details}))

(defn check-static
  "Return nil when ROLE grants CAPABILITY, otherwise a denial map."
  [role capability]
  (when-not (role? role)
    (denial :INVALID_ARGUMENT
            "Unknown program role"
            {:role role :known-roles (set (keys roles))}))
  (when (and (role? role) (not (capability? role capability)))
    (denial :CAPABILITY_DENIED
            "This role does not grant the capability"
            {:role role :capability capability})))

(defn check-ask
  "`ask` is Plan S1 only."
  [role s1?]
  (cond
    (not (capability? role :ask))
    (denial :CAPABILITY_DENIED
            "Only Plan may call ask"
            {:role role :capability :ask})

    (not s1?)
    (denial :ASK_FORBIDDEN_OUTSIDE_S1
            "ask is available only in Plan S1"
            {:role role :phase :not-s1})

    :else nil))

(defn check-call
  "`call` is holder-only for Swarm workers and always available to the Plan
  dedicated worker."
  [role holder?]
  (cond
    (= role :dedicated-worker) nil

    (= role :clj-worker)
    (when-not holder?
      (denial :TOKEN_REQUIRED
              "Only the Swarm holder may run a black-box shell session"
              {:role role :capability :call}))

    :else
    (denial :CAPABILITY_DENIED
            "This role may not run a black-box shell session"
            {:role role :capability :call})))

(defn check-abandon
  "`abandon` is the holder's one-way exit."
  [role holder?]
  (cond
    (not (capability? role :abandon))
    (denial :CAPABILITY_DENIED
            "Only a Swarm worker may abandon"
            {:role role :capability :abandon})

    (not holder?)
    (denial :TOKEN_REQUIRED
            "Only the Swarm holder may abandon"
            {:role role :capability :abandon})

    :else nil))

(defn check-plan-write
  "Plan writes only the draft, and only after the first review unlocks it.

  A nil draft path is the first-write case: the candidate must be a safe
  `plans/<name>.md` path and the caller must still prove the name is not
  already taken in the workspace.  Once a path is fixed, a different path is
  `:DRAFT_PATH_LOCKED`."
  ([context path]
   (check-plan-write context path (:draft-path context)))
  ([context path draft-path]
   (let [unlocked? (:write-unlocked? context)]
     (cond
       (not unlocked?)
       (denial :WRITE_LOCKED_BEFORE_FIRST_REVIEW
               "Plan may write the draft only after its first review"
               {:path path :draft-path draft-path})

       (not (draft-path? path))
       (denial :INVALID_DRAFT_PATH
               "Plan draft path must match plans/<name>.md"
               {:path path :draft-path draft-path})

       (nil? draft-path)
       nil

       (not= draft-path path)
       (denial :DRAFT_PATH_LOCKED
               "Plan may write only its fixed draft path"
               {:path path :draft-path draft-path})

       :else nil))))

(defn check-plan-read
  "Plan reads only the draft and operator attachments.

  The caller supplies `:readable-paths` (attachments) and/or `:draft-path`.
  The two are unioned.  With neither, Plan has no readable file surface and
  the read is denied; the runtime must not silently widen Plan to the whole
  workspace."
  ([context path] (check-plan-read context path (:draft-path context)))
  ([context path draft-path]
   (let [readable (:readable-paths context)
         draft draft-path
         allowed (cond
                   (set? readable) readable
                   (sequential? readable) (set readable)
                   :else nil)
         allowed (if (some? draft)
                   (if allowed (conj allowed draft) #{draft})
                   allowed)]
     (cond
       (nil? allowed)
       (denial :CAPABILITY_DENIED
               "Plan has no readable file paths in this program context"
               {:path path :draft-path draft :readable-paths readable})

       (not (contains? allowed path))
       (denial :CAPABILITY_DENIED
               "Plan may read only the draft and operator attachments"
               {:path path :allowed (vec allowed)})

       :else nil))))
