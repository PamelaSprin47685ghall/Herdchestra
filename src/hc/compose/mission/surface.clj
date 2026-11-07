(ns hc.compose.mission.surface
  "Pure tool-role and model-role surface for a committed mission state.

  The Owner creates model and tool calls with mission roles (`:plan`,
  `:reviewer`, `:worker`, `:holder`, `:plan-worker`).  The Clojure program
  runtime expects program roles (`:clj-plan`, `:clj-review`, `:clj-worker`,
  `:dedicated-worker`) plus dynamic flags that a static role cannot express:
  Plan S1, Plan write unlock, holder eligibility, and the draft path.

  This namespace derives that context from mission state.  It performs no
  I/O and does not mutate state.  The server/app adapter should merge the
  returned `:context` into the tool request before evaluating a program, and
  use `:model-role`/`:model-id` for the model call."
  (:require [clojure.string :as str]
            [hc.compose.mission.model :as model]
            [hc.core.relay :as relay]))

(def program-roles
  "Mission role -> Clojure program role."
  {:plan :clj-plan
   :reviewer :clj-review
   :worker :clj-worker
   :holder :clj-worker
   :plan-worker :dedicated-worker
   :dedicated-worker :dedicated-worker})

(defn program-role-for
  "Return the program role for a mission role, or nil."
  [role]
  (get program-roles (when role (keyword role))))

(defn- fail
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- ok
  [context]
  {:ok? true :context context})

(defn- execution-tenures
  [state]
  (relay/execution-tenures (:relay state)))

(defn- resolve-execution
  "Resolve an execution tenure by id, or the sole active tenure.

  Returns `[tenure error]`.  A completed tenure is returned only when named
  explicitly; an omitted id with several live tenures is ambiguous."
  [state tenure-id]
  (let [tenures (execution-tenures state)]
    (cond
      (and tenure-id (relay/tenure-by-id (:relay state) tenure-id))
      [(relay/tenure-by-id (:relay state) tenure-id) nil]

      tenure-id
      [nil (if (contains? (:retired-tenure-ids (:relay state)) tenure-id)
             (fail :TENURE_RETIRED "tenure has retired"
                   {:tenure-id tenure-id})
             (fail :UNKNOWN_TENURE
                   "tenure-id does not identify a live tenure"
                   {:tenure-id tenure-id}))]

      (= 1 (count tenures))
      [(val (first tenures)) nil]

      (empty? tenures)
      [nil (fail :UNKNOWN_TENURE
                 "no execution tenure exists"
                 {:execution-tenures []})]

      :else
      [nil (fail :AMBIGUOUS_TENURE
                 "several execution tenures exist; pass :tenure-id"
                 {:execution-tenures (vec (sort (keys tenures)))})])))

(defn- plan-context
  [state {:keys [tenure-id readable-paths]}]
  (let [plan (relay/current-tenure (:relay state) :plan)
        binding (get-in state [:bindings :plan])
        draft-path (get-in state [:relay :draft :path])]
    (cond
      (nil? plan)
      (fail :UNKNOWN_TENURE "Plan tenure is missing" {:lane :plan})

      (and tenure-id (not= tenure-id (:tenure-id plan)))
      (fail :TENURE_LANE_MISMATCH
            "tenure-id is not the current Plan tenure"
            {:tenure-id tenure-id
             :plan-tenure-id (:tenure-id plan)})

      :else
      (ok {:role :plan
           :program-role :clj-plan
           :model-role :main
           :model-id (model/model-for (:models state) :plan)
           :lane :plan
           :tenure-id (:tenure-id plan)
           :milestone (:milestone plan)
           :phase (if (= :pending (:milestone plan)) :handoff :review)
           :s1? (= :S1 (:milestone plan))
           :holder? false
           :write-unlocked? (boolean (and (:reviewed-in-tenure? binding)
                                          (not (:awaiting-user? plan))))
           :draft-path draft-path
           :readable-paths
           (when (set? readable-paths)
             (cond-> readable-paths
               draft-path (conj draft-path)))}))))

(defn- reviewer-context
  [state tenure-id]
  (let [[tenure error] (resolve-execution state tenure-id)]
    (cond
      error
      error

      ;; Phase B suspends the centralized Reviewer.  The relay refuses a
      ;; review, but the tool surface must not keep handing out `clj-review`
      ;; read capabilities either.
      (or (= :closed (:review-window tenure))
          (= :active (:swarm-phase tenure)))
      (fail :REVIEW_WINDOW_CLOSED
            "Reviewer is suspended during swarm construction"
            {:tenure-id (:tenure-id tenure)
             :review-window (:review-window tenure)
             :swarm-phase (:swarm-phase tenure)})

      :else
      (ok {:role :reviewer
           :program-role :clj-review
           :model-role :main
           :model-id (model/model-for (:models state) :reviewer)
           :lane :execution
           :tenure-id (:tenure-id tenure)
           :milestone (:milestone tenure)
           :phase (cond
                    (= :completed (:status tenure)) :completed
                    (= :active (:swarm-phase tenure)) :swarm
                    :else :review)
           :s1? false
           :holder? false
           :write-unlocked? false
           :draft-path (get-in state [:relay :draft :path])}))))

(defn- worker-context
  [state {:keys [role tenure-id agent-id]}]
  (let [[tenure error] (resolve-execution state tenure-id)]
    (cond
      error
      error

      (nil? agent-id)
      (fail :AGENT_ID_REQUIRED
            "swarm role context requires :agent-id"
            {:tenure-id (:tenure-id tenure)})

      :else
      (let [binding (get-in state [:bindings :execution
                                   (:tenure-id tenure) :swarm agent-id])
            board (get-in state [:boards (:tenure-id tenure)])
            holder-id (get-in board [:token-slot :holder-id])
            holder? (= agent-id holder-id)
            expected-role (if holder? :holder :worker)]
        (cond
          (nil? binding)
          (fail :UNKNOWN_ACTOR_BINDING
                "agent is not a registered swarm role"
                {:tenure-id (:tenure-id tenure) :agent-id agent-id})

          (not= (keyword role) (:role binding))
          (fail :ROLE_BINDING_MISMATCH
                "requested mission role does not match the committed binding"
                {:tenure-id (:tenure-id tenure)
                 :agent-id agent-id
                 :requested-role role
                 :binding-role (:role binding)
                 :expected-role expected-role})

          (and (= (keyword role) :holder) (not holder?))
          (fail :NOT_HOLDER
                "agent does not hold the token slot"
                {:tenure-id (:tenure-id tenure)
                 :agent-id agent-id
                 :holder-id holder-id})

          :else
          (ok {:role (keyword role)
               :program-role :clj-worker
               :model-role :task
               :model-id (model/model-for (:models state) role)
               :lane :execution
               :tenure-id (:tenure-id tenure)
               :agent-id agent-id
               :milestone (:milestone tenure)
               :phase :swarm
               :s1? false
               :holder? holder?
               :write-unlocked? false
               :draft-path (get-in state [:relay :draft :path])}))))))

(defn- dedicated-worker-context
  [state {:keys [tenure-id]}]
  (let [plan (relay/current-tenure (:relay state) :plan)]
    (cond
      (nil? plan)
      (fail :UNKNOWN_TENURE "Plan tenure is missing" {:lane :plan})

      (and tenure-id (not= tenure-id (:tenure-id plan)))
      (fail :TENURE_LANE_MISMATCH
            "dedicated worker belongs to the current Plan tenure"
            {:tenure-id tenure-id
             :plan-tenure-id (:tenure-id plan)})

      :else
      (ok {:role :plan-worker
           :program-role :dedicated-worker
           :model-role :task
           :model-id (model/model-for (:models state) :plan-worker)
           :lane :plan
           :tenure-id (:tenure-id plan)
           :milestone (:milestone plan)
           :phase (if (= :pending (:milestone plan)) :handoff :review)
           :s1? false
           :holder? false
           :write-unlocked? false
           :draft-path (get-in state [:relay :draft :path])}))))

(defn program-context
  "Return the pure tool/model context for one mission role.

  Request:

    {:role :plan | :reviewer | :worker | :holder | :plan-worker
     :tenure-id \"...\"          ; optional when exactly one applies
     :agent-id ...               ; required for worker/holder
     :readable-paths #{...}}     ; optional Plan allow-list

  Success: `{:ok? true :context {...}}` with `:program-role`, `:model-role`,
  `:model-id`, `:s1?`, `:holder?`, `:write-unlocked?`, `:draft-path`, and
  identity fields.  Failure: `{:ok? false :error {...}}`.

  This function does not check that the caller is the Owner; the Owner must
  call it for the identity it has already authenticated."
  [state {:keys [role] :as request}]
  (case (keyword role)
    :plan (plan-context state request)
    :reviewer (reviewer-context state (:tenure-id request))
    (:worker :holder) (worker-context state request)
    (:plan-worker :dedicated-worker)
    (dedicated-worker-context state request)
    (fail :UNKNOWN_ROLE
          "unknown mission role for a program context"
          {:role role})))
