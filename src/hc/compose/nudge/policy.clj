(ns hc.compose.nudge.policy
  "Pure unattended Nudge policy for AGENTS.md §2.5 and §5.2.

  The policy reads committed state and host facts.  It never performs I/O,
  reads a clock, generates randomness, or mutates state.  `decide` is the
  guard: it returns `:enabled?` plus the candidate Nudge.  `prepare` turns an
  enabled decision into a pure transition plan and effect list.  The host
  commits the transition before it dispatches the next model request."
  (:require [clojure.string :as str]
            [hc.core.relay :as relay]
            [hc.core.relay.ledger :as ledger]
            [hc.core.swarm-stm.board :as board]))

(def worker-directive
  "The Phase B directive.  It never claims a review ledger exists."
  "立刻去做！系统是无人值守的常授权模式，禁止等待用户决策或授权，禁止半途而废。")

(def role-lanes
  "The lane that owns each task-cycle role."
  {:plan :plan
   :reviewer :execution
   :worker :execution
   :holder :execution})

(def central-roles
  "Roles that run in a centralized review window."
  #{:plan :reviewer})

(def worker-roles
  "Roles that run in Phase B swarm construction."
  #{:worker :holder})

(def permanent-failure-kinds
  "Terminal model failures that need configuration or contract repair.

  A Nudge repeats the same request.  Repeating one of these failures would
  only burn calls and hide the cause, so the policy blocks instead."
  #{:invalid-snapshot :unknown-field :invalid-request :invalid-model
    :model-not-found
    :auth :permission :unauthorized :forbidden :invalid-credentials
    :unsupported-feature :model-tools-unavailable :content-refused
    :input-invalid})

(def context-failure-kinds
  "Failures caused by a request that exceeds the model context."
  #{:input-too-long :context-length-exceeded :payload-too-large})

(def snapshot-failure-kinds
  "Snapshot-contract reasons that the adapter reports as permanent.

  `hc.server.model-adapter.port/normalize-error` removes `:error/kind` and
  maps `:invalid-snapshot` reasons to `:kind`.  The real shape therefore
  carries `:kind`/`:reason` such as `:unknown-field`, not `:invalid-snapshot`."
  #{:unknown-field :not-a-map :unknown-role :invalid-quotes :invalid-citations
    :messages-not-sequential :messages-empty :attachments-not-sequential
    :tool-calls-not-sequential :empty-assistant-message :invalid-tool-arguments
    :arguments-unparsable :arguments-not-a-string :arguments-not-a-map
    :invalid-tool-call-id :blank-tool-name :invalid-role :invalid-identifier})

(defn- snapshot-failure?
  [failure]
  (and (map? failure)
       (or (= :invalid-snapshot (:error/kind failure))
           (= :invalid-snapshot (:kind failure))
           (and (keyword? (:reason failure))
                (not (true? (:retryable? failure)))
                (or (vector? (:path failure))
                    (contains? snapshot-failure-kinds (:reason failure))))
           (contains? snapshot-failure-kinds (:kind failure)))))

(defn- role-name
  [role]
  (if (keyword? role) (name role) (str role)))

(defn- reason-label
  [reason]
  (if (keyword? reason) (name reason) (str reason)))

(defn nudge-id
  "Return a deterministic Nudge identifier.

  The selected tenure id is used when available.  Phase B workers and
  holders also include their agent id because several agents share one
  execution tenure.  Hosts should pass `:nudge-seq` or `:turn-id` so
  repeated nudges in one tenure remain distinct.  The policy never
  generates randomness."
  [{:keys [role tenure-id nudge-seq turn-id agent-id] :as observation}]
  (let [tenure-id (or tenure-id (get-in observation [:tenure :tenure-id]))
        role (when role (keyword role))
        agent-part (when (and (contains? worker-roles role) (some? agent-id))
                     (str "-" (role-name agent-id)))]
    (str "nudge-"
         (role-name (or role :unknown))
         "-"
         (or tenure-id "unknown")
         (or agent-part "")
         "-"
         (or nudge-seq turn-id "1"))))

(defn observation
  "Normalize committed relay state and host facts into a policy observation.

  `state` may be nil when the host passes `:tenure` and `:execution-tenure`
  directly.  The committed tenure's `:awaiting-user?` always wins over a
  caller-supplied false value.  When several execution tenures exist, a
  Reviewer/worker/holder must pass its specific `:tenure` or `:tenure-id`;
  `:execution-tenures` exposes the full set for Plan handoff checks.

  An inline `:kind`/`:error/kind`/`:status`/`:category` in the facts is
  carried as `:failure` when the host passed no failure map, so
  `failure-class` can still see a cause the host reported in place.  The
  fields are copied verbatim; nothing is invented."
  [state {:keys [role agent-id board in-flight? turn-ended? awaiting-user?
                 nudge-seq turn-id tenure tenure-id execution-tenure
                 failure failure-outcome permanent-failure? session-id]
          :as facts}]
  (let [role (when role (keyword role))
        lane (get role-lanes role)
        execution-tenures (relay/execution-tenures state)
        tenure (or tenure
                   (when state
                     (case lane
                       :plan (relay/current-tenure state :plan)
                       :execution (if tenure-id
                                    (relay/tenure-by-id state tenure-id)
                                    (when (= 1 (count execution-tenures))
                                      (val (first execution-tenures))))
                       nil)))
        execution-tenure (or execution-tenure
                              (when (= 1 (count execution-tenures))
                                (val (first execution-tenures))))]
    {:role role
     :lane lane
     :tenure tenure
     :tenure-id (:tenure-id tenure)
     :execution-tenure execution-tenure
     :execution-tenures execution-tenures
     :board board
     :agent-id agent-id
     :turn-ended? (if (nil? turn-ended?) true (boolean turn-ended?))
     :in-flight? (boolean in-flight?)
     :awaiting-user? (boolean (or awaiting-user?
                                  (:awaiting-user? tenure)))
     ;; A classified cause may arrive without a `:failure` map: the inline
     ;; `:kind`/`:category`/`:status` on the facts is enough for
     ;; `failure-class`.  Dropping the facts made such a stop look like a
     ;; plain natural stop, so the Nudge repeated a request that can never
     ;; succeed.  The failure stays exactly as reported.
     :failure (or failure
                  (when (or (:kind facts) (:error/kind facts) (:status facts))
                    (select-keys facts
                                 [:kind :error/kind :reason :category :status
                                  :retryable? :path :message])))
     :failure-outcome (or failure-outcome
                          (when (some? (:failure-outcome facts))
                            (:failure-outcome facts)))
     :permanent-failure? (boolean permanent-failure?)
     :nudge-seq nudge-seq
     :turn-id turn-id
     :session-id (or session-id (:session-id state))}))

(defn user-wait-kind
  "Classify an awaiting-user tenure by the exit it represents.

  `:plan-s1-ask` and `:execution-initial-ask` are the two legal user-wait
  exits in §2.5.  Any other awaiting-user state is `:user-wait`; the policy
  still suppresses Nudge because the committed state already says a human
  answer is required."
  [{:keys [lane tenure]}]
  (cond
    (and (= :plan lane)
         (= :S1 (:milestone tenure)))
    :plan-s1-ask

    (and (= :execution lane)
         (= :execution (:milestone tenure))
         (true? (:initial? tenure))
         (true? (:first-review? tenure))
         (true? (:asked? tenure))
         (ledger/ledger-empty? (:ledger tenure)))
    :execution-initial-ask

    :else
    :user-wait))

(defn legal-exit
  "Return the legal stop exit for OBSERVATION, or nil.

  A retired tenure is not an exit; it is inactive and has a successor.
  `:awaiting-user?` is authoritative: the policy never fights an explicit
  human wait, even if its shape is not one of the two §2.5 asks."
  [{:keys [role lane tenure execution-tenure execution-tenures]
    :as observation}]
  (when tenure
    (let [awaiting? (boolean (or (:awaiting-user? observation)
                                 (:awaiting-user? tenure)))]
      (cond
        (= :retired (:status tenure))
        nil

        (or (= :completed (:status tenure))
            (= :completed (:milestone tenure)))
        {:kind :terminal-completed
         :role role
         :lane lane
         :tenure-id (:tenure-id tenure)
         :milestone (:milestone tenure)}

        (and (= :plan lane)
             (= :pending (:milestone tenure))
             (or (some? execution-tenure)
                 (seq execution-tenures)))
        {:kind :handoff-complete
         :role role
         :lane lane
         :tenure-id (:tenure-id tenure)
         :milestone (:milestone tenure)
         :execution-tenure-id (or (:tenure-id execution-tenure)
                                  (some-> (first execution-tenures)
                                          val
                                          :tenure-id))}

        awaiting?
        {:kind (user-wait-kind observation)
         :role role
         :lane lane
         :tenure-id (:tenure-id tenure)
         :milestone (:milestone tenure)}

        :else
        nil))))

(defn central-message
  "Return the centralized-phase Nudge text for LEDGER.

  Delegates to `hc.core.relay/central-nudge-message` so the relay and the
  compose policy cannot drift."
  [ledger]
  (relay/central-nudge-message ledger))

(defn worker-message
  "Return the Phase B worker Nudge text.

  The worker text uses the worker's responsibility set and the global
  progressable reasons.  It never mentions the review ledger."
  [role responsibility reasons]
  (let [label (if (= :holder role) "持令者" "微观蜂群施工个体")
        responsibility (sort-by str responsibility)
        reasons (sort-by reason-label reasons)
        responsibility-line
        (if (seq responsibility)
          (str "你的未结责任：" (str/join ", " (map str responsibility)) "。")
          "你在看板上暂无个人未结责任，但仍不得休眠。")
        work-line
        (if (seq reasons)
          (str "全局仍有可推进工作："
               (str/join ", " (map reason-label reasons))
               "。")
          "全局可推进集合为空。")]
    (str "你是" label "。\n"
         responsibility-line "\n"
         work-line "\n"
         "重新观察看板并继续推进。" worker-directive)))

(defn- base-decision
  [observation]
  {:enabled? false
   :role (:role observation)
   :lane (:lane observation)
   :agent-id (:agent-id observation)
   :tenure-id (get-in observation [:tenure :tenure-id])
   :exit nil
   :reason nil})

(defn- disabled
  ([base reason]
   (disabled base reason nil))
  ([base reason details]
   (cond-> (assoc base :enabled? false :reason reason)
     (some? details) (assoc :details details))))

(defn- effective-turn-ended?
  [observation]
  (if (contains? observation :turn-ended?)
    (boolean (:turn-ended? observation))
    true))

(defn- effective-in-flight?
  [observation]
  (boolean (:in-flight? observation)))

(defn failure-class
  "Classify a terminal model failure as `:permanent`, `:context`, or nil.

  The original failure stays in the diagnostic.  A cancelled attempt is not
  a configuration failure; admission handles it.  An unknown failure is not
  assumed permanent.

  `:permanent-failure?` is the host's own verdict, so the policy honours it
  first.  Otherwise a failure reaches this function under the name its
  producer used: `turn-observation` forwards the whole turn, so the failure
  sits in `:failure` (a call terminal error) or among the top-level
  observation facts (a host fact such as a required search refusal).
  Reading the failure only from `:failure` left those turns with no failure
  at all, so the guard never blocked them and the agent kept retrying a
  request that cannot succeed."
  [{:keys [failure failure-outcome permanent-failure?] :as observation}]
  (let [;; The failure itself, or the observation facts when the host
        ;; carries the failure inline.
        failure (or (when (map? failure) failure)
                    (when (map? observation) observation))
        kind (or (:error/kind failure) (:kind failure))
        category (:category failure)
        status (:status failure)]
    (cond
      (true? permanent-failure?) :permanent
      (= :cancelled failure-outcome) nil
      (snapshot-failure? failure) :permanent
      (contains? permanent-failure-kinds kind) :permanent
      (contains? context-failure-kinds kind) :context
      (= :permanent category) :permanent
      (= :context category) :context
      (contains? #{400 401 403 404 422} status) :permanent
      (= 413 status) :context
      :else nil)))

(declare prepare)

(defn- blocked-decision
  [base observation failure-class]
  (let [failure (:failure observation)
        tenure (:tenure observation)
        context? (= :context failure-class)
        code (if context?
               :NUDGE_BLOCKED_CONTEXT_OVERFLOW
               :NUDGE_BLOCKED_PERMANENT_FAILURE)
        message (if context?
                  "Nudge stopped: the model request exceeds the context window. Adjust Scope/context or the model; do not repeat the same request."
                  "Nudge stopped: the model request failed permanently. Fix the configuration or request contract; do not repeat the same request.")
        diagnostic {:code code
                    :severity :error
                    :message message
                    :remedy (or (:remedy failure)
                                (if context?
                                  :adjust-context
                                  :fix-configuration))
                    :role (:role observation)
                    :lane (:lane observation)
                    :tenure-id (:tenure-id tenure)
                    :failure failure}]
    (let [decision (assoc base
                          :enabled? false
                          :blocked? true
                          :kind :blocked
                          :reason (if context?
                                    :context-overflow
                                    :permanent-failure)
                          :failure failure
                          :diagnostic diagnostic)]
      (assoc decision :prepare (prepare decision)))))

(defn- reviewer-suspended?
  [{:keys [swarm-phase review-window relay-pending?]}]
  (or (= :active swarm-phase)
      (= :closed review-window)
      (true? relay-pending?)))

(defn- nudge-record
  [observation kind reason message extra]
  (merge {:nudge-id (nudge-id observation)
          :type :unattended-nudge
          :kind kind
          :role (:role observation)
          :lane (:lane observation)
          :agent-id (:agent-id observation)
          :tenure-id (get-in observation [:tenure :tenure-id])
          :milestone (get-in observation [:tenure :milestone])
          :reason reason
          :message message}
         extra))

(defn prepare
  "Return the pure transition plan for an enabled or blocked DECISION.

  The host commits `:transition` in the Owner transaction, then dispatches
  `:effects`.  `prepare` itself has no side effects."
  [decision]
  (cond
    (:enabled? decision)
    (let [{:keys [nudge role lane agent-id tenure-id message]} decision
          nudge-id (:nudge-id nudge)]
      {:transition {:op :inject-unattended-nudge
                    :nudge-id nudge-id
                    :lane lane
                    :agent-id agent-id
                    :tenure-id tenure-id
                    :nudge nudge}
       :effects [{:type :nudge/inject
                  :nudge-id nudge-id
                  :nudge nudge}
                 {:type :model/request
                  :nudge-id nudge-id
                  :role role
                  :lane lane
                  :agent-id agent-id
                  :tenure-id tenure-id
                  :reason :unattended-nudge
                  :prompt message}]})

    (:blocked? decision)
    {:transition {:op :record-nudge-blocked
                  :lane (:lane decision)
                  :tenure-id (:tenure-id decision)
                  :diagnostic (:diagnostic decision)}
     :effects [{:type :diagnostic
                :diagnostic (:diagnostic decision)}
               {:type :nudge/blocked
                :diagnostic (:diagnostic decision)}]}

    :else nil))

(defn- central-decision
  [observation base]
  (let [tenure (:tenure observation)
        ledger* (or (:ledger tenure) {})
        entries (mapv (fn [[k v]] {:key k :value v})
                      (ledger/entries ledger*))
        message (central-message ledger*)
        reason (if (seq entries) :ledger-open :milestone-open)
        nudge (nudge-record observation :central reason message
                            {:ledger entries})
        decision (assoc base
                        :enabled? true
                        :kind :central
                        :reason reason
                        :ledger entries
                        :message message
                        :nudge nudge)]
    (assoc decision :prepare (prepare decision))))

(defn- worker-decision
  [observation base]
  (let [tenure (:tenure observation)
        board* (:board observation)
        agent-id (:agent-id observation)
        role (:role observation)]
    (cond
      (not= :active (:swarm-phase tenure))
      (disabled base :not-in-swarm
                {:swarm-phase (:swarm-phase tenure)})

      (not (map? board*))
      (disabled base :missing-board)

      (nil? agent-id)
      (disabled base :missing-agent-id)

      :else
      (let [responsibility (vec (sort-by str
                                         (board/responsibility board* agent-id)))
            reasons (vec (sort-by reason-label
                                  (board/progressable-reasons board*)))
            progressable? (boolean (seq reasons))]
        (if-not progressable?
          (assoc (disabled base :no-progressable-work)
                 :responsibility responsibility
                 :progressable-reasons reasons)
          (let [message (worker-message role responsibility reasons)
                nudge (nudge-record observation :worker :global-progressable
                                    message
                                    {:responsibility responsibility
                                     :progressable-reasons reasons})
                decision (assoc base
                                :enabled? true
                                :kind :worker
                                :reason :global-progressable
                                :responsibility responsibility
                                :progressable-reasons reasons
                                :message message
                                :nudge nudge)]
            (assoc decision :prepare (prepare decision))))))))

(defn decide
  "Evaluate the unattended Nudge guard for one turn-end observation.

  Returns a map with `:enabled?`.  An enabled decision contains the Nudge
  candidate and a pure `:prepare` plan.  A disabled decision contains a
  `:reason` and, when the stop is legal, an `:exit` classification.

  This function is pure.  The host must commit the prepared transition
  before it dispatches the model request."
  [observation]
  (let [role (when (:role observation) (keyword (:role observation)))
        lane (or (:lane observation) (get role-lanes role))
        observation (assoc observation :role role :lane lane)
        base (base-decision observation)
        tenure (:tenure observation)
        failure-class (failure-class observation)]
    (cond
      (not (effective-turn-ended? observation))
      (disabled base :turn-not-ended)

      (not (contains? role-lanes role))
      (disabled base :unsupported-role {:role role})

      (not= lane (get role-lanes role))
      (disabled base :role-lane-mismatch
                {:role role
                 :lane lane
                 :expected-lane (get role-lanes role)})

      (and (nil? tenure)
           (not= role :plan)
           (> (count (:execution-tenures observation)) 1))
      (disabled base :ambiguous-tenure
                {:execution-tenures (sort (keys (:execution-tenures
                                                 observation)))})

      (nil? tenure)
      (disabled base :inactive-tenure)

      :else
      (if-let [exit (legal-exit observation)]
        (assoc (disabled base (:kind exit)) :exit exit)
        (cond
          (not= :active (:status tenure))
          (disabled base :inactive-tenure {:status (:status tenure)})

          failure-class
          (blocked-decision base observation failure-class)

          (effective-in-flight? observation)
          (assoc (disabled base :in-flight-work) :deferred? true)

          (and (= role :reviewer)
               (reviewer-suspended? tenure))
          (disabled base :reviewer-suspended-in-swarm
                    {:swarm-phase (:swarm-phase tenure)
                     :review-window (:review-window tenure)
                     :relay-pending? (:relay-pending? tenure)})

          (contains? central-roles role)
          (central-decision observation base)

          (contains? worker-roles role)
          (worker-decision observation base)

          :else
          (disabled base :unsupported-role {:role role}))))))
