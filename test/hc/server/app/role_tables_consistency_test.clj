(ns hc.server.app.role-tables-consistency-test
  "Cross-table consistency guard for the role vocabulary.

  The role names live in six places because each answers a different
  question: the program capability table grants primitives, the mission
  worker table narrows program roles to their contracts, the model table
  owns model class and tool requirements, the surface and worktree tables
  map mission roles to program roles, prompt decoration decides which
  program role gets a system prompt and schema, and the Nudge policy owns
  the task-cycle lanes.

  Every set below is derived from a public table (or from a documented
  private function through its var).  A new role therefore joins the guard
  automatically: the test fails with the exact missing/extra lists instead
  of letting the tables drift apart.  Deliberate subsets carry an explicit
  whitelist."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.mission.devops :as devops]
            [hc.compose.mission.model :as model]
            [hc.compose.mission.surface :as surface]
            [hc.compose.mission.worker :as worker]
            [hc.compose.mission.worktree :as worktree]
            [hc.compose.nudge.policy :as policy]
            [hc.server.app.prompts :as prompts]
            [hc.server.program.capability :as capability]))

;; ---------------------------------------------------------------------------
;; Derivation and reporting helpers

(def ^:private program-roles
  "The authority set: every role the program runtime can execute."
  (set (keys capability/roles)))

(def ^:private role-prompt
  (var-get #'prompts/role-prompt))

(def ^:private program-tool
  (var-get #'prompts/program-tool))

(def ^:private
  prompt-uncovered-whitelist
  "The Plan dedicated worker is not decorated by `hc.server.app.prompts`;
  its own runner owns its prompt and tool surface."
  #{:dedicated-worker})

(def ^:private
  non-task-cycle-program-roles
  "Program roles that deliberately have no task-cycle lane.

  `:dedicated-worker` belongs to one Plan tenure and is not Nudged;
  `:clj-steward` and `:clj-meditator` are event-driven repository
  stewardship roles, not mission turn roles."
  #{:dedicated-worker :clj-steward :clj-meditator})

(def ^:private
  non-task-cycle-mission-roles
  "Mission roles that deliberately have no Nudge lane.

  The Steward and Meditator are event-driven; the Plan dedicated worker has
  no board and no task-cycle turn."
  #{:steward :meditator :plan-worker :dedicated-worker})

(def ^:private
  worker-unmodeled-whitelist
  "Program roles whose capability sets stay in `hc.server.program.capability`
  and are not duplicated in `hc.compose.mission.worker`."
  #{:clj-plan :clj-review :clj-worker})

(def ^:private
  worker-role-aliases
  "Mission role -> program role for the names that differ across the two
  tables.  `:plan-worker` is the mission identity of the
  `:dedicated-worker` program role."
  {:plan-worker :dedicated-worker})

(def ^:private worktree-program-roles
  (var-get #'worktree/program-roles))

(defn- sorted
  [values]
  (sort-by str values))

(defn- diff-report
  [label expected actual]
  (str label
       "\n  missing: " (pr-str (sorted (set/difference expected actual)))
       "\n  extra:   " (pr-str (sorted (set/difference actual expected)))))

(defn- worker-program-role
  [role]
  (get worker-role-aliases role role))

(defn- worker-modeled-program-roles
  []
  (set (map worker-program-role (keys worker/role-capabilities))))

(defn- mission-roles
  []
  (set/union model/main-roles model/task-roles))

;; ---------------------------------------------------------------------------
;; 1. One program-role vocabulary

(deftest program-role-vocabulary-is-one-set
  (let [surface-roles (set (vals surface/program-roles))
        surface-mission-roles (set (keys surface/program-roles))
        worktree-roles (set (vals worktree-program-roles))
        worktree-mission-roles (set (keys worktree-program-roles))
        prompt-roles (set (filter #(role-prompt % false) program-roles))
        policy-roles (set (keep surface/program-role-for
                                (keys policy/role-lanes)))]
    (testing "mission/surface maps exactly the program roles"
      (is (= program-roles surface-roles)
          (diff-report "surface/program-roles" program-roles surface-roles)))
    (testing "mission/worktree resolves exactly the program roles"
      (is (= program-roles worktree-roles)
          (diff-report "worktree/program-roles values" program-roles
                       worktree-roles))
      (is (= surface-mission-roles worktree-mission-roles)
          (diff-report "worktree/program-roles mission keys"
                       surface-mission-roles worktree-mission-roles)))
    (testing "prompt decoration covers every role except the documented subset"
      (let [expected (set/difference program-roles prompt-uncovered-whitelist)]
        (is (= expected prompt-roles)
            (diff-report "prompts role-prompt coverage" expected prompt-roles))))
    (testing "the Nudge policy covers exactly the task-cycle program roles"
      (let [expected (set/difference program-roles
                                     non-task-cycle-program-roles)]
        (is (= expected policy-roles)
            (diff-report "policy/role-lanes program roles" expected policy-roles))))))

;; ---------------------------------------------------------------------------
;; 2. Capability registration

(deftest every-role-capability-is-registered
  (let [registered (set/union (set (vals capability/program-functions))
                              capability/control-primitives)
        missing (for [[role {:keys [capabilities]}] capability/roles
                      cap capabilities
                      :when (not (contains? registered cap))]
                  [role cap])]
    (is (empty? missing)
        (str "role capabilities without a program function or control primitive: "
             (pr-str (sort-by pr-str missing))))
    (testing "every control primitive is also a callable program function"
      (let [functions (set (vals capability/program-functions))
            orphans (set/difference capability/control-primitives functions)]
        (is (empty? orphans)
            (str "control primitives missing from program-functions: "
                 (pr-str (sorted orphans))))))))

;; ---------------------------------------------------------------------------
;; 3. mission/worker agrees with the capability table and stays disjoint

(deftest worker-contract-sets-are-capability-exact-and-disjoint
  (let [modeled (worker-modeled-program-roles)
        role-mismatches (for [role (keys worker/role-capabilities)
                              :let [program-role (worker-program-role role)
                                    expected (set (get-in capability/roles
                                                          [program-role :capabilities]))
                                    actual (set (worker/capabilities-for role))]
                              :when (not= expected actual)]
                          {:role role
                           :program-role program-role
                           :missing (sorted (set/difference expected actual))
                           :extra (sorted (set/difference actual expected))})
        overlaps (for [role (keys worker/role-capabilities)
                       :let [caps (set (worker/capabilities-for role))
                             forbidden (set (worker/forbidden-capabilities-for role))
                             overlap (set/intersection caps forbidden)]
                       :when (seq overlap)]
                   [role (sorted overlap)])]
    (is (= (set (keys worker/role-capabilities))
           (set (keys worker/role-forbidden-capabilities)))
        "every worker capability table has a matching forbidden table")
    (is (empty? (set/difference modeled program-roles))
        (str "mission/worker roles unknown to capability/roles: "
             (pr-str (sorted (set/difference modeled program-roles)))))
    (is (empty? role-mismatches)
        (str "mission/worker capability sets differ from capability/roles: "
             (pr-str role-mismatches)))
    (is (empty? overlaps)
        (str "capability and forbidden sets overlap: " (pr-str overlaps)))
    (doseq [[role caps] worker/role-capabilities]
      (is (= caps (worker/capabilities-for role))
          (str "capabilities-for " role))
      (is (= (get worker/role-forbidden-capabilities role)
             (worker/forbidden-capabilities-for role))
          (str "forbidden-capabilities-for " role)))))

;; ---------------------------------------------------------------------------
;; 4. Tool requirement is bidirectional

(deftest tool-required-classification-is-bidirectional
  (let [program-mismatches
        (for [role program-roles
              :let [requires? (boolean
                               (model/program-role-requires-tools? role))
                    has-tool? (some? (program-tool role false))]
              :when (not= requires? has-tool?)]
          {:level :program
           :role role
           :requires-tools? requires?
           :program-tool? has-tool?})
        mission-mismatches
        (for [role (mission-roles)
              :let [program-role (surface/program-role-for role)
                    mission? (boolean (model/role-requires-tools? role))
                    program? (boolean
                              (model/program-role-requires-tools? program-role))]
              :when (not= mission? program?)]
          {:level :mission
           :role role
           :program-role program-role
           :role-requires-tools? mission?
           :program-requires-tools? program?})
        mismatches (concat program-mismatches mission-mismatches)]
    (is (empty? mismatches)
        (str "tool-required drift: program-role-requires-tools? must equal "
             "the presence of a program tool schema, and role-requires-tools? "
             "must equal its program role's answer: "
             (pr-str (sort-by (fn [mismatch]
                                (str (:level mismatch)
                                     (:role mismatch)))
                              mismatches))))))

;; ---------------------------------------------------------------------------
;; 5. Model-role coverage

(deftest model-role-coverage-is-complete
  (let [roles (mission-roles)
        mapped (set (keys model/role->model-role))
        bad-model-roles (for [role mapped
                              :let [model-role (model/model-role-for role)]
                              :when (not (contains? #{:main :task} model-role))]
                          [role model-role])
        missing-program-roles (for [role roles
                                    :let [program-role (surface/program-role-for role)]
                                    :when (or (nil? program-role)
                                              (not (contains? program-roles
                                                               program-role)))]
                                [role program-role])]
    (is (= roles mapped)
        (diff-report "role->model-role vs main/task sets" roles mapped))
    (is (empty? (set/intersection model/main-roles model/task-roles))
        "main and task role sets overlap")
    (is (empty? bad-model-roles)
        (str "roles without a usable model class: " (pr-str bad-model-roles)))
    (is (empty? missing-program-roles)
        (str "mission roles without a program role: "
             (pr-str (sort-by pr-str missing-program-roles))))
    (doseq [role model/main-roles]
      (is (= :main (model/model-role-for role)) (str "main role " role)))
    (doseq [role model/task-roles]
      (is (= :task (model/model-role-for role)) (str "task role " role)))))

;; ---------------------------------------------------------------------------
;; 6. Nudge role classification

(deftest nudge-classification-covers-only-task-cycle-roles
  (let [classified (set/union policy/central-roles policy/worker-roles)
        expected (set/difference (mission-roles)
                                 non-task-cycle-mission-roles)]
    (is (= expected classified)
        (diff-report "nudge central/worker roles" expected classified))
    (is (= classified (set (keys policy/role-lanes)))
        (diff-report "nudge role-lanes" classified
                     (set (keys policy/role-lanes))))
    (doseq [role [:steward :meditator]]
      (is (not (contains? classified role))
          (str role " must not be a Nudge role"))
      (is (not (contains? (set (keys policy/role-lanes)) role))
          (str role " must not have a Nudge lane"))
      (let [decision (policy/decide {:role role :turn-ended? true})]
        (is (false? (:enabled? decision)) (str role))
        (is (= :unsupported-role (:reason decision)) (str role))
        (is (= role (get-in decision [:details :role])) (str role))))))

;; ---------------------------------------------------------------------------
;; 7. Directional counterexample guard

(deftest no-program-role-exists-in-only-one-table
  (let [modeled (worker-modeled-program-roles)
        expected-modeled (set/difference program-roles
                                         worker-unmodeled-whitelist)
        missing-from-worker (set/difference expected-modeled modeled)
        extra-in-worker (set/difference modeled expected-modeled)
        unaliased (for [role (keys worker/role-capabilities)
                        :let [program-role (worker-program-role role)]
                        :when (not (contains? program-roles program-role))]
                    [role program-role])]
    (is (empty? missing-from-worker)
        (str "capability/roles has roles that mission/worker lacks: "
             (pr-str (sorted missing-from-worker))))
    (is (empty? extra-in-worker)
        (str "mission/worker has roles that capability/roles lacks: "
             (pr-str (sorted extra-in-worker))))
    (is (empty? unaliased)
        (str "mission/worker roles without a program-role alias: "
             (pr-str (sort-by pr-str unaliased))))))

;; ---------------------------------------------------------------------------
;; The Plan dedicated worker's devops tool surface

(def ^:private devops-expected-allowed-tools
  "Hard-coded from root AGENTS.md §4.1, Plan dedicated worker column:
  cat, stat, ls, where, whereAll, park, unpark, call.

  This is deliberately NOT derived from mission.devops or mission.worker.
  If both implementations move together, the derived comparisons below
  still pass; this literal set fails first."
  #{:cat :stat :ls :where :whereAll :park :unpark :call})

(def ^:private devops-expected-forbidden-tools
  "Hard-coded from root AGENTS.md §4.1: the Plan dedicated worker has no
  write/mkdir/rm/mv, no task-cycle or board authority, and no shell token.

  These names are also expected in `mission.worker/forbidden-capabilities`;
  an undeclared name such as `devops` or `abandon` is checked separately."
  #{:write :mkdir :rm :mv :ask :review
    :split :claim :finish :cancel :replace :release :reopen :comment :update
    :take-or-die :board :token :wake-all})

(deftest devops-tool-surface-matches-the-dedicated-worker
  (let [tool-keywords (set (map keyword devops/tool-names))
        allowed (set (map keyword (filter devops/allowed-tool?
                                          (seq devops/tool-names))))
        expected (set (filter (fn [capability]
                                (and (contains? worker/capabilities capability)
                                     (not (contains? worker/forbidden-capabilities
                                                     capability))))
                              tool-keywords))]
    (is (seq tool-keywords)
        "the devops tool spec must not be empty")
    (is (seq devops-expected-forbidden-tools)
        "the hard-coded forbidden set must not be empty")
    (testing "the hard-coded §4.1 surface catches whole-table drift"
      (is (= devops-expected-allowed-tools tool-keywords)
          (diff-report "devops tool specs vs AGENTS.md §4.1"
                       devops-expected-allowed-tools tool-keywords))
      (is (= devops-expected-allowed-tools allowed)
          (diff-report "devops allowed-tool? vs AGENTS.md §4.1"
                       devops-expected-allowed-tools allowed)))
    (testing "the forbidden table is enforced, not decorative"
      (is (every? #(contains? worker/forbidden-capabilities %)
                  devops-expected-forbidden-tools)
          "the hard-coded forbidden names are missing from the worker table")
      (doseq [tool devops-expected-forbidden-tools]
        (is (not (devops/allowed-tool? (name tool)))
            (str "devops admitted forbidden tool " tool)))
      (doseq [name ["not-a-real-tool" "devops" "abandon" "park-slot"]]
        (is (not (devops/allowed-tool? name))
            (str "devops admitted undeclared/out-of-scope tool " name))))
    (testing "derived comparison: local drift inside the two tables"
      (is (= expected allowed)
          (diff-report "mission.devops allowed tools" expected allowed))
      (is (empty? (set/difference allowed (set worker/capabilities)))
          (str "devops admits tools outside the dedicated worker set: "
               (pr-str (sorted (set/difference allowed
                                               (set worker/capabilities))))))
      (is (= worker/capabilities
             (set (get-in worker/contract [:capabilities])))
          "the contract and the devops surface share one capability set"))))
