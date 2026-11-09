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

(def ^:private max-ledger-item-bytes
  "UTF-8 byte cap for one rendered review-ledger item."
  2048)

(def ^:private max-nudge-message-bytes
  "UTF-8 byte cap for one complete Nudge message."
  16384)

(def ^:private central-directive
  "§2.5 hard directive used when a capped message must be rebuilt."
  "当前的 review 账本还有上述事项没有做，立刻去做！系统是无人值守的常授权模式，禁止等待用户决策或授权，禁止半途而废。")

(defn- utf8-byte-length
  ^long [^String s]
  (alength (.getBytes s "UTF-8")))

(defn- utf8-code-point-length
  ^long [^long cp]
  (cond
    (< cp 0x80) 1
    (< cp 0x800) 2
    (< cp 0x10000) 3
    :else 4))

(defn- truncate-utf8
  "Return the longest prefix of S with at most LIMIT UTF-8 bytes.

  Iteration advances by whole code points, so a surrogate pair is never
  split.  A malformed lone surrogate stops the prefix."
  [^String s limit]
  (let [n (.length s)]
    (loop [i 0
           used 0]
      (if (>= i n)
        s
        (let [c (.charAt s i)
              pair? (and (Character/isHighSurrogate c)
                         (< (inc i) n)
                         (Character/isLowSurrogate (.charAt s (inc i))))
              cp (if pair?
                   (Character/toCodePoint c (.charAt s (inc i)))
                   (int c))
              size (if (and (Character/isSurrogate c) (not pair?))
                     0
                     (utf8-code-point-length cp))
              next-i (if pair? (+ i 2) (inc i))]
          (cond
            (zero? size) (subs s 0 i)
            (> (+ used size) limit) (subs s 0 i)
            :else (recur next-i (+ used size))))))))

(defn- truncation-suffix
  [n]
  (str "…[+" n " bytes] (truncated)"))

(defn- cap-ledger-line
  "Cap one rendered ledger item at max-ledger-item-bytes.

  The suffix reports the number of UTF-8 bytes removed from the original
  rendered item, so a reader can see how much text is on the ledger."
  [^String line]
  (if (<= (utf8-byte-length line) max-ledger-item-bytes)
    line
    (let [full-bytes (utf8-byte-length line)
          suffix (truncation-suffix full-bytes)
          budget (max 0 (- max-ledger-item-bytes
                           (utf8-byte-length suffix)))
          kept (truncate-utf8 line budget)
          cut (- full-bytes (utf8-byte-length kept))]
      (str kept (truncation-suffix cut)))))

(defn- central-directive-for
  "Recover the relay's directive prefix from one non-empty relay message."
  [^String full]
  (let [marker "\n未完成事项：\n"
        index (str/index-of full marker)]
    (if index (subs full 0 index) central-directive)))

(defn- assemble-central-message
  [directive lines omitted]
  (str directive
       "\n未完成事项："
       (when (seq lines)
         (str "\n" (str/join "\n" lines)))
       (when (pos? omitted)
         (str "\n(另有 " omitted " 项未列出，见 review 台账)"))))

(defn- cap-central-message
  "Fit the capped ledger lines under max-nudge-message-bytes.

  The directive and the omission footer occupy the budget first.  Items are
  kept in key order; the lexicographically last items are omitted first.
  The binary search finds the largest kept prefix that still fits."
  [directive lines]
  (let [total (count lines)
        assemble (fn [kept-count]
                   (assemble-central-message
                    directive
                    (subvec lines 0 kept-count)
                    (- total kept-count)))
        fits? (fn [text]
                (<= (utf8-byte-length text) max-nudge-message-bytes))]
    (loop [lo 0
           hi total
           best 0]
      (if (> lo hi)
        (let [text (assemble best)]
          (if (fits? text)
            text
            (throw (ex-info "nudge message cannot fit its fixed directive and footer"
                            {:code :NUDGE_MESSAGE_TOO_LARGE
                             :limit max-nudge-message-bytes
                             :bytes (utf8-byte-length text)}))))
        (let [mid (quot (+ lo hi) 2)
              text (assemble mid)]
          (if (fits? text)
            (recur (inc mid) hi mid)
            (recur lo (dec mid) best)))))))

(defn- cap-worker-message
  "Keep a worker message under the whole-message cap without losing the
  Phase B directive."
  [^String head]
  (let [full (str head worker-directive)]
    (if (<= (utf8-byte-length full) max-nudge-message-bytes)
      full
      (let [head-bytes (utf8-byte-length head)
            suffix (str (truncation-suffix head-bytes) "\n")
            budget (max 0 (- max-nudge-message-bytes
                             (utf8-byte-length worker-directive)
                             (utf8-byte-length suffix)))
            kept (truncate-utf8 head budget)
            cut (- head-bytes (utf8-byte-length kept))]
        (str kept (truncation-suffix cut) "\n" worker-directive)))))

(def default-round-budget
  "Maximum consecutive Nudge rounds for one [tenure-id agent-id] before the
  policy answers `:round-boundary`.

  The boundary is a host budget, not a task verdict: §2.5 still asks the
  agent to clear its ledger, but the host stops injecting Nudge calls once
  the same actor has been nudged this many times without a legal exit, so
  an unattended session can go idle instead of looping forever.  The next
  `:turn/ended`, user message, or board/ledger change re-enters the same
  judgement; nothing is spent permanently."
  8)

(def round-boundary-reason
  "The disabled `:reason` that marks an exhausted Nudge round budget."
  :round-boundary)

(def role-lanes
  "The lane that owns each task-cycle role.

  `:steward` and `:meditator` are repository-stewardship roles.  They run
  from evidence events and the Stewardship Decision Frontier, not from a
  task-cycle turn, so they are deliberately absent here.  `decide` answers
  `:unsupported-role` for them: a turn-end observation must never
  manufacture a Nudge for a role that has no lane."
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
  #{:unknown-field :not-a-map :unknown-role :role-unconfigured :invalid-quotes
    :invalid-citations :messages-not-sequential :messages-empty
    :attachments-not-sequential
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
                 nudge-seq turn-id tenure tenure-id execution-tenure tools?
                 round-seq round-budget
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
     ;; `:tools?` is tri-state: `false` is the host's proof that the ended
     ;; turn made no tool call, `true` that it did, and nil that the host
     ;; cannot say (a manually reported `:turn/ended`).  `legal-exit`
     ;; requires the proven `false` before it may answer `:plain-response`.
     :tools? (when (some? tools?) (boolean tools?))
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
     ;; Consecutive settled rounds this actor has already consumed.  The host
     ;; counts committed Nudge calls in `[:nudge-rounds key]`; the policy
     ;; only compares the number against the budget.
     :round-seq (long (or round-seq 0))
     :round-budget (let [budget (or round-budget default-round-budget)]
                     (if (and (integer? budget) (pos? budget))
                       budget
                       default-round-budget))
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
  [{:keys [role lane tenure execution-tenure execution-tenures tools?
           failure-outcome]
    :as observation}]
  (when tenure
    (let [awaiting? (boolean (or (:awaiting-user? observation)
                                 (:awaiting-user? tenure)))]
      (cond
        (= :retired (:status tenure))
        nil

        ;; A plain answer is a legal stop: the turn ended `:succeeded`, made
        ;; no tool call, and left no ledger work.  Nudge exists to push
        ;; task-cycle progress; with neither tools nor ledger entries there
        ;; is nothing to push, so the session can idle.  Worker/holder
        ;; turns keep their board check in `worker-decision`, a failed or
        ;; unknown outcome still owes the failure-driven decision, and a
        ;; host that cannot prove the turn was tool-free (`:tools?` nil,
        ;; e.g. a manually reported `:turn/ended`) keeps the old judgement.
        (and (contains? central-roles role)
             (= :succeeded failure-outcome)
             (false? tools?)
             (ledger/ledger-empty? (:ledger tenure)))
        {:kind :plain-response
         :role role
         :lane lane
         :tenure-id (:tenure-id tenure)
         :milestone (:milestone tenure)}

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

  A ledger that fits both byte caps delegates verbatim to
  `hc.core.relay/central-nudge-message`, so the relay and the compose policy
  cannot drift.  A longer ledger caps each rendered item at
  max-ledger-item-bytes, then omits the lexicographically last items until
  the whole message fits max-nudge-message-bytes.  The §2.5 directive and
  an accurate omission footer stay in every capped message."
  [ledger]
  (let [ledger (or ledger {})
        entries (vec (ledger/entries ledger))
        full (relay/central-nudge-message ledger)]
    (if (empty? entries)
      full
      (let [raw-lines (mapv (fn [[k v]] (str "- " k ": " v)) entries)
            item-too-large? (some #(> (utf8-byte-length %)
                                      max-ledger-item-bytes)
                                  raw-lines)
            message-too-large? (> (utf8-byte-length full)
                                  max-nudge-message-bytes)]
        (if-not (or item-too-large? message-too-large?)
          full
          (cap-central-message
           (central-directive-for full)
           (mapv cap-ledger-line raw-lines)))))))

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
    (cap-worker-message
     (str "你是" label "。\n"
          responsibility-line "\n"
          work-line "\n"
          "重新观察看板并继续推进。"))))

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

(defn- boundary-decision
  "Answer `:round-boundary` when OBSERVATION already consumed its budget.

  The boundary only applies to a decision the policy would otherwise
  enable: a legal exit, a block, a deferral, or a lane that never nudges
  keeps its own answer.  `details` carries the count and the ledger keys
  the agent still owes, so the snapshot/diagnostic can show what stopped."
  [observation base]
  (let [ledger-keys (->> (or (get-in observation [:tenure :ledger]) {})
                         ledger/entries
                         (mapv first))]
    (disabled base round-boundary-reason
              {:round-seq (:round-seq observation)
               :round-budget (:round-budget observation)
               :ledger-keys ledger-keys})))

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
        budget (let [b (or (:round-budget observation)
                           default-round-budget)]
                 (if (and (integer? b) (pos? b)) b default-round-budget))
        observation (-> observation
                        (assoc :role role :lane lane)
                        (assoc :round-seq (long (or (:round-seq observation) 0))
                               :round-budget budget))
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
          (if (>= (:round-seq observation) (:round-budget observation))
            (boundary-decision observation base)
            (central-decision observation base))

          (contains? worker-roles role)
          (if (>= (:round-seq observation) (:round-budget observation))
            (boundary-decision observation base)
            (worker-decision observation base))

          :else
          (disabled base :unsupported-role {:role role}))))))
