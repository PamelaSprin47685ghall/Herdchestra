(ns hc.core.relay.core
  "Relay core: review ledgers, Plan/execution tenures, and the atomic relay
  barrier described by AGENTS.md §2.4.

  The namespace is a pure state machine.  Every public transition returns

    {:ok? true  :state state' :receipt receipt :effects [effect ...]}
    {:ok? false :state state  :error {:code ... :message ...} :effects []}

  The state is plain EDN and can be committed as one storage transaction.
  Effects are advisory: the state already records the successor tenure, the
  barrier, the root-task mirror, and the review-window change.  A replay of
  an accepted `review` call returns the original receipt and no effects."
  (:require [clojure.string :as str]
            [hc.core.relay.barrier :as barrier]
            [hc.core.relay.ledger :as ledger]
            [hc.core.relay.tenure :as tenure]))

(def ^:private plan-ask
  "我需要干什么？")

(def ^:private execution-ask
  "底稿已满足，接下来做什么？")

(def ^:private nudge-directive
  "当前的 review 账本还有上述事项没有做，立刻去做！系统是无人值守的常授权模式，禁止等待用户决策或授权，禁止半途而废。")

(def ^:private draft-path-pattern
  #"plans/[a-z0-9-]{1,80}\.md")

;; ---------------------------------------------------------------------------
;; Small result and state helpers

(defn- fail
  ([state code message]
   (fail state code message nil))
  ([state code message details]
   {:ok? false
    :state state
    :error (cond-> {:code code :message message}
             (some? details) (assoc :details details))
    :effects []}))

(defn- success
  [state receipt effects]
  {:ok? true
   :state state
   :receipt receipt
   :effects (vec effects)})

(defn- normalize-lane
  [lane]
  (cond
    (keyword? lane) lane
    (string? lane) (keyword lane)
    (symbol? lane) (keyword (name lane))
    :else lane))

(defn execution-tenures
  "Return the map of execution tenure-id -> tenure.  Never nil."
  [state]
  (let [executions (get-in state [:lanes :execution])]
    (if (map? executions) executions {})))

(defn current-tenure
  "Return the Plan tenure, or the sole execution tenure.

  When several execution tenures are live, use `tenure-by-id` or
  `execution-tenures`; this two-lane convenience accessor returns nil rather
  than guessing which execution tenure the caller means."
  [state lane]
  (case (normalize-lane lane)
    :plan (get-in state [:lanes :plan])
    :execution (let [tenures (execution-tenures state)]
                 (when (= 1 (count tenures))
                   (val (first tenures))))
    nil))

(defn current-tenure-id
  [state lane]
  (some-> (current-tenure state lane) :tenure-id))

(defn tenure-by-id
  "Find a Plan or execution tenure by its never-reused tenure-id."
  [state tenure-id]
  (or (when (= tenure-id (get-in state [:lanes :plan :tenure-id]))
        (get-in state [:lanes :plan]))
      (get (execution-tenures state) tenure-id)))

(defn- assoc-tenure
  [state lane tenure]
  (if (= :execution (normalize-lane lane))
    (assoc-in state [:lanes :execution (:tenure-id tenure)] tenure)
    (assoc-in state [:lanes :plan] tenure)))

(defn- remove-tenure
  [state lane tenure]
  (if (= :execution (normalize-lane lane))
    (update-in state [:lanes :execution] dissoc (:tenure-id tenure))
    state))

(defn- valid-lane?
  [lane]
  (contains? #{:plan :execution} lane))

(defn- active-tenure?
  [tenure]
  (tenure/active? tenure))

(defn- valid-call-id?
  [call-id]
  (and (string? call-id) (not (str/blank? call-id))))

(defn- valid-draft-path?
  [path]
  (and (string? path)
       (boolean (re-matches draft-path-pattern path))))

(defn- nonblank?
  [value]
  (and (string? value) (not (str/blank? value))))

(def ^:private user-attachment-keys
  #{:id :attachment-id :blob-id :send-blob-id :name :filename :mime :size
    :local-copy? :send-route :url})

(defn- valid-user-attachment?
  [attachment]
  (and (map? attachment)
       (every? user-attachment-keys (keys attachment))
       (or (nonblank? (:attachment-id attachment))
           (nonblank? (:id attachment))
           (nonblank? (:blob-id attachment))
           (nonblank? (:send-blob-id attachment)))
       (or (nil? (:id attachment)) (nonblank? (:id attachment)))
       (or (nil? (:attachment-id attachment))
           (nonblank? (:attachment-id attachment)))
       (or (nil? (:blob-id attachment)) (nonblank? (:blob-id attachment)))
       (or (nil? (:send-blob-id attachment))
           (nonblank? (:send-blob-id attachment)))
       (or (nil? (:name attachment)) (nonblank? (:name attachment)))
       (or (nil? (:filename attachment)) (nonblank? (:filename attachment)))
       (or (nil? (:mime attachment)) (nonblank? (:mime attachment)))
       (or (nil? (:size attachment))
           (and (integer? (:size attachment)) (not (neg? (:size attachment)))))
       (or (nil? (:local-copy? attachment))
           (boolean? (:local-copy? attachment)))
       (or (nil? (:send-route attachment))
           (keyword? (:send-route attachment))
           (nonblank? (:send-route attachment)))
       (or (nil? (:url attachment)) (nonblank? (:url attachment)))))

(defn- attachment-metadata?
  [attachment]
  (and (map? attachment)
       (or (nonblank? (:attachment-id attachment))
           (nonblank? (:id attachment))
           (nonblank? (:blob-id attachment))
           (nonblank? (:send-blob-id attachment)))
       (or (nil? (:id attachment)) (nonblank? (:id attachment)))
       (or (nil? (:attachment-id attachment))
           (nonblank? (:attachment-id attachment)))
       (or (nil? (:blob-id attachment)) (nonblank? (:blob-id attachment)))
       (or (nil? (:send-blob-id attachment))
           (nonblank? (:send-blob-id attachment)))
       (or (nil? (:name attachment)) (nonblank? (:name attachment)))
       (or (nil? (:filename attachment)) (nonblank? (:filename attachment)))
       (or (nil? (:mime attachment)) (nonblank? (:mime attachment)))
       (or (nil? (:size attachment))
           (and (integer? (:size attachment)) (not (neg? (:size attachment)))))
       (or (nil? (:local-copy? attachment))
           (boolean? (:local-copy? attachment)))
       (or (nil? (:send-route attachment))
           (keyword? (:send-route attachment))
           (nonblank? (:send-route attachment)))
       (or (nil? (:url attachment)) (nonblank? (:url attachment)))))

(defn- normalize-user-attachment
  [attachment]
  (let [attachment-id (or (:attachment-id attachment) (:id attachment))
        normalized (select-keys attachment
                                [:blob-id :send-blob-id :name :filename :mime
                                 :size :local-copy? :send-route :url])]
    (cond-> normalized
      (nonblank? attachment-id) (assoc :attachment-id attachment-id))))

(defn normalize-user-attachments
  "Keep only user attachment reference metadata from ATTACHMENTS.

  Unknown keys (including content, extracted text, resolutions, or agent
  reasoning) are discarded.  Entries without a stable attachment/blob
  reference are dropped."
  [attachments]
  (->> attachments
       (keep (fn [attachment]
               (when (attachment-metadata? attachment)
                 (normalize-user-attachment attachment))))
       vec))

(defn- valid-user-attachments?
  [attachments]
  (and (sequential? attachments)
       (every? valid-user-attachment? attachments)))

(defn- initial-user-inputs
  ([user-input user-attachments]
   (initial-user-inputs user-input user-attachments nil))
  ([user-input user-attachments message-id]
   (if (and (string? user-input) (not (str/blank? user-input)))
     [(cond-> {:text user-input}
        (and (string? message-id) (not (str/blank? message-id)))
        (assoc :message-id message-id)
        (seq user-attachments) (assoc :attachments user-attachments))]
     [])))

(defn- valid-user-inputs?
  [inputs]
  (and (sequential? inputs)
       (every? (fn [entry]
                 (and (map? entry)
                      (string? (:text entry))
                      (not (str/blank? (:text entry)))
                      (every? #{:text :message-id :attachments} (keys entry))
                      (or (nil? (:message-id entry))
                          (and (string? (:message-id entry))
                               (not (str/blank? (:message-id entry)))))
                      (or (nil? (:attachments entry))
                          (valid-user-attachments? (:attachments entry)))))
               inputs)))

(defn- with-receipt
  [state call-id payload receipt]
  (assoc-in state [:receipts call-id]
            {:payload payload
             :receipt receipt}))

(defn- receipt-base
  [lane tenure]
  {:status :accepted
   :lane lane
   :tenure-id (:tenure-id tenure)
   :tenure-epoch (:epoch tenure)
   :milestone (:milestone tenure)
   :ledger (:ledger tenure)
   :reviewed? false
   :relayed? false})

(defn- caller-error
  "Return a failure map when `tenure-id` is not a live tenure of `lane`."
  [state lane tenure-id]
  (let [tenure (tenure-by-id state tenure-id)]
    (cond
      (nil? tenure)
      (if (contains? (:retired-tenure-ids state) tenure-id)
        (fail state :TENURE_RETIRED
              "tenure has retired"
              {:lane lane :tenure-id tenure-id})
        (fail state :UNKNOWN_TENURE
              "tenure-id does not identify a live tenure"
              {:lane lane :tenure-id tenure-id}))

      (not= lane (:lane tenure))
      (fail state :TENURE_LANE_MISMATCH
            "tenure belongs to a different lane"
            {:lane lane :tenure-lane (:lane tenure)})

      (tenure/completed? tenure)
      (fail state :MISSION_COMPLETED
            "mission is completed"
            {:lane lane :tenure-id tenure-id})

      (tenure/abandoned? tenure)
      (fail state :TENURE_ABANDONED
            "tenure was abandoned"
            {:lane lane :tenure-id tenure-id
             :reason (:abandoned-reason tenure)})

      (not (active-tenure? tenure))
      (fail state :TENURE_RETIRED
            "tenure is not active"
            {:lane lane :tenure-id tenure-id :status (:status tenure)})

      (not= (:role tenure) (tenure/role-for-lane lane))
      (fail state :ROLE_FORBIDDEN
            "role is not allowed to review this lane"
            {:lane lane :role (:role tenure)})

      :else nil)))

(defn- make-root-tasks
  [state tenure ledger]
  (loop [remaining (seq ledger)
         next-id (:next-task-seq state)
         tasks []]
    (if-let [[k v] (first remaining)]
      (recur (next remaining)
             (inc next-id)
             (conj tasks
                   {:task-id (str "root-task-" next-id)
                    :tenure-id (:tenure-id tenure)
                    :ledger-key k
                    :goal v
                    :remaining-work v
                    :done-when v
                    :state :ready
                    :parent-id nil
                    :owner-id nil
                    :needs-token false
                    :child-ids []
                    :wait-for #{}}))
      {:tasks tasks
       :next-task-seq next-id})))

(defn- mark-task-done
  [tasks task-id]
  (mapv (fn [task]
          (if (= task-id (:task-id task))
            (assoc task :state :done)
            task))
        tasks))

;; ---------------------------------------------------------------------------
;; Construction and draft ownership

(defn new-state
  "Create a session with one initial Plan tenure.

  `:plan-milestone` defaults to `:S1`; tests and restores may open the lane
  at a later Plan milestone.  `:message-id` optionally tags the initial
  input so a later board copy dedupes by message.  Execution is never
  created here."
  [{:keys [session-id user-input user-attachments draft successor-hint
           plan-milestone message-id]
    :or {session-id "session-1"
         plan-milestone :S1}}]
  (when-not (and (string? session-id) (not (str/blank? session-id)))
    (throw (ex-info "session-id must be a non-blank string"
                    {:session-id session-id})))
  (when-not (tenure/valid-milestone? :plan plan-milestone)
    (throw (ex-info "invalid Plan milestone"
                    {:milestone plan-milestone})))
  (when (and (some? user-input) (not (string? user-input)))
    (throw (ex-info "user-input must be a string"
                    {:user-input user-input})))
  (when (and (some? successor-hint) (not (string? successor-hint)))
    (throw (ex-info "successor-hint must be a string"
                    {:successor-hint successor-hint})))
  (when (and (some? message-id)
             (not (and (string? message-id)
                       (not (str/blank? message-id)))))
    (throw (ex-info "message-id must be a non-blank string"
                    {:message-id message-id})))
  (when (and draft
             (not (and (valid-draft-path? (:path draft))
                       (string? (:content draft)))))
    (throw (ex-info "draft must contain a valid plans/<name>.md path and string content"
                    {:draft draft})))
  (when (and (some? user-attachments)
             (not (and (sequential? user-attachments)
                       (every? attachment-metadata? user-attachments))))
    (throw (ex-info "user-attachments must contain only reference metadata"
                    {:user-attachments user-attachments})))
  (let [plan (tenure/create-tenure {:tenure-id "tenure-1"
                                    :epoch 1
                                    :lane :plan
                                    :milestone plan-milestone
                                    :initial? true})]
    {:session-id session-id
     :user-input user-input
     :user-inputs (initial-user-inputs user-input
                                       (normalize-user-attachments
                                        (vec (or user-attachments [])))
                                       message-id)
     :draft draft
     :successor-hint successor-hint
     :lanes {:plan plan
             :execution {}}
     :retired-tenure-ids #{}
     :receipts {}
     :barriers []
     :next-tenure-seq 2
     :next-task-seq 1
     :barrier-seq 0}))

(defn set-draft
  "Record the Plan draft bytes.  The first accepted path is fixed for the
  lifetime of the session; later calls may only replace its content."
  [state {:keys [path content]}]
  (cond
    (seq (execution-tenures state))
    (fail state :DRAFT_LOCKED_AFTER_HANDOFF
          "draft is frozen once an execution tenure exists"
          {:path path
           :execution-tenure-count (count (execution-tenures state))})

    (not (valid-draft-path? path))
    (fail state :INVALID_DRAFT_PATH
          "draft path must match plans/<name>.md"
          {:path path})

    (not (string? content))
    (fail state :INVALID_DRAFT_CONTENT
          "draft content must be a string"
          {:path path})

    (and (get-in state [:draft :path])
         (not= path (get-in state [:draft :path])))
    (fail state :DRAFT_PATH_LOCKED
          "draft path is already fixed"
          {:path path :locked-path (get-in state [:draft :path])})

    :else
    (let [state' (assoc state :draft {:path path
                                     :content content
                                     :version (inc (or (:version
                                                       (get state :draft))
                                                       0))
                                     :source :program
                                     :user-edited? false})]
      (success state'
               {:status :accepted
                :outcome :draft-updated
                :path path
                :draft-version (get-in state' [:draft :version])
                :content-length (count content)}
               []))))

;; ---------------------------------------------------------------------------
;; Review window transitions

(defn- ask!
  [state lane tenure question]
  (let [tenure' (assoc tenure
                       :asked? true
                       :awaiting-user? true)
        state' (assoc-tenure state lane tenure')
        receipt (assoc (receipt-base lane tenure')
                       :outcome :ask
                       :reviewed? false
                       :question question
                       :awaiting-user? true)
        effect {:type :relay/ask
                :lane lane
                :tenure-id (:tenure-id tenure')
                :question question}]
    (success state' receipt [effect])))

(defn- promote!
  [state lane tenure]
  (let [promotion (tenure/promote-tenure tenure)]
    (if-not (:ok? promotion)
      (fail state
            (get-in promotion [:error :code])
            (get-in promotion [:error :message])
            (get-in promotion [:error :details]))
      (let [tenure' (:tenure promotion)
            state' (assoc-tenure state lane tenure')
            completed? (= :completed (:milestone tenure'))
            effect (if completed?
                     {:type :relay/completed
                      :lane lane
                      :tenure-id (:tenure-id tenure')
                      :milestone :completed}
                     {:type :relay/promote
                      :lane lane
                      :tenure-id (:tenure-id tenure')
                      :from-milestone (:from promotion)
                      :to-milestone (:to promotion)})
            receipt (assoc (receipt-base lane tenure')
                           :outcome (if completed? :completed :promoted)
                           :reviewed? true
                           :from-milestone (:from promotion)
                           :to-milestone (:to promotion))]
        (success state' receipt [effect])))))

(defn- merge-input-attachments
  "Attach newly reported references to the existing entry for `message-id`.

  The board may report the same message in stages (text first, attachments
  later).  The message stays deduped, but its attachment references are not
  lost."
  [inputs message-id attachments]
  (if (empty? attachments)
    inputs
    (mapv (fn [input]
            (if (= message-id (:message-id input))
              (update input :attachments
                      (fn [existing]
                        (vec (distinct (concat existing attachments)))))
              input))
          inputs)))

(defn- merge-handled-inputs
  "Carry the board's already-handled raw user texts into the relay state.

  Only `:text` and an optional `:message-id` cross the barrier.  Resolutions,
  summaries, comments, and every other board/agent record stay behind.  A
  non-blank `:message-id` is a stable dedupe key because the Owner broadcasts
  the same message to every live execution board; a blank id is treated as
  no id so it cannot collapse distinct inputs."
  [state board]
  (let [inputs (seq (:user-inputs board))]
    (if-not inputs
      state
      (let [existing (vec (or (seq (:user-inputs state))
                              (initial-user-inputs (:user-input state))))
            existing-ids (set (keep (fn [id]
                                      (when (and (string? id)
                                                 (not (str/blank? id)))
                                        id))
                                    (map :message-id existing)))]
        (assoc state
               :user-inputs
               (loop [acc existing
                      seen existing-ids
                      remaining inputs]
                 (if-let [input (first remaining)]
                   (let [text (:text input)
                         raw-message-id (:message-id input)
                         message-id (when (and (string? raw-message-id)
                                               (not (str/blank? raw-message-id)))
                                      raw-message-id)
                         attachments (normalize-user-attachments
                                      (vec (or (:attachments input) [])))]
                     (cond
                       (not (and (string? text) (not (str/blank? text))))
                       (recur acc seen (next remaining))

                       (and message-id (contains? seen message-id))
                       (recur (merge-input-attachments acc message-id attachments)
                              seen
                              (next remaining))

                       :else
                       (recur (conj acc
                                    (cond-> {:text text}
                                      message-id (assoc :message-id message-id)
                                      (seq attachments)
                                      (assoc :attachments attachments)))
                              (if message-id
                                (conj seen message-id)
                                seen)
                              (next remaining))))
                   acc)))))))

(defn- do-relay
  [state lane tenure reason board reviewed?]
  (let [state (merge-handled-inputs state board)
        next-epoch (:next-tenure-seq state)
        successor (tenure/create-tenure
                   {:tenure-id (str "tenure-" next-epoch)
                    :epoch next-epoch
                    :lane lane
                    :milestone (:milestone tenure)
                    :initial? false})
        retired (tenure/retire-tenure tenure)
        barrier-id (str "barrier-" (inc (:barrier-seq state)))
        barrier-record (barrier/make-barrier state retired successor barrier-id)
        state' (-> state
                   (remove-tenure lane tenure)
                   (assoc-tenure lane successor)
                   (update :retired-tenure-ids conj (:tenure-id tenure))
                   (update :barriers conj barrier-record)
                   (assoc :next-tenure-seq (inc next-epoch))
                   (update :barrier-seq inc))
        receipt (assoc (receipt-base lane successor)
                       :outcome :relayed
                       :reviewed? reviewed?
                       :relayed? true
                       :reason reason
                       :retired-tenure-id (:tenure-id tenure)
                       :barrier-id barrier-id
                       :successor-tenure-id (:tenure-id successor)
                       :successor-epoch (:epoch successor))
        effect {:type :relay/barrier
                :lane lane
                :barrier barrier-record
                :barrier-id barrier-id
                :retired-tenure-id (:tenure-id tenure)
                :successor successor
                :context (:context barrier-record)
                :reason reason}]
    (success state' receipt [effect])))

(defn- relay-pending
  "Keep the tenure live and record why the barrier did not fire.

  The successor context stays in the effect and the barrier record; the
  receipt only points at the barrier, so a large draft is not copied into
  `:receipts` on every relay."
  [state lane tenure reasons reviewed?]
  (let [tenure' (assoc tenure :relay-pending? true)
        state' (assoc-tenure state lane tenure')
        receipt (assoc (receipt-base lane tenure')
                       :outcome :relay-pending
                       :reviewed? reviewed?
                       :reasons reasons)]
    (success state' receipt [])))

(defn- relay!
  "Atomically retire, write the barrier, and open the successor.

  Execution requires a board snapshot.  When the board still reports work,
  the transition records `:relay-pending?` and leaves the tenure live; the
  host retries with `maybe-relay` after the board settles."
  [state lane tenure reason board reviewed?]
  (cond
    ;; Abandoned and completed tenures are terminal records.  A stale
    ;; barrier retry must never retire one or open a successor for it.
    (not (tenure/active? tenure))
    (fail state :TENURE_NOT_ACTIVE
          "only an active tenure can relay"
          {:lane lane
           :tenure-id (:tenure-id tenure)
           :status (:status tenure)})

    (not (ledger/ledger-empty? (:ledger tenure)))
    (fail state :RELAY_NOT_READY
          "ledger is not empty"
          {:ledger (:ledger tenure)})

    ;; Defensive: Plan never owns board root tasks; the check keeps a
    ;; corrupted state from retiring a Plan tenure with a board mirror.
    (and (= lane :plan) (seq (:root-tasks tenure)))
    (fail state :RELAY_NOT_READY
          "Plan tenure cannot own board root tasks"
          {:root-tasks (:root-tasks tenure)})

    (not= lane :execution)
    (do-relay state lane tenure reason board reviewed?)

    (nil? board)
    (fail state :BOARD_SNAPSHOT_REQUIRED
          "execution relay requires a board snapshot"
          {:lane lane})

    (not (barrier/execution-relay-ready? tenure board))
    (relay-pending state lane tenure
                   (barrier/execution-relay-reasons tenure board)
                   reviewed?)

    :else
    (do-relay state lane tenure reason board reviewed?)))

(defn- plan-empty-review
  [state tenure]
  (cond
    ;; Restored/defensive: a Plan that already reviewed and cleared its
    ;; ledger relays for independent verification.  Normal transitions
    ;; reach the clear-and-relay path in `plan-review` instead.
    (not (:first-review? tenure))
    (relay! state :plan tenure :independent-verification nil true)

    (and (:initial? tenure)
         (= :S1 (:milestone tenure))
         (not (:asked? tenure)))
    (ask! state :plan tenure plan-ask)

    (:initial? tenure)
    (relay! state :plan tenure :initial-empty-review nil true)

    :else
    (promote! state :plan tenure)))

(defn- plan-review
  [state tenure findings]
  (let [prior (:ledger tenure)
        prior-empty? (ledger/ledger-empty? prior)
        final (ledger/apply-findings prior findings)
        final-empty? (ledger/ledger-empty? final)
        empty-input? (empty? findings)]
    (cond
      (and (seq findings) (not final-empty?))
      (let [tenure' (assoc tenure
                           :ledger final
                           :first-review? false
                           :awaiting-user? false)
            state' (assoc-tenure state :plan tenure')
            receipt (assoc (receipt-base :plan tenure')
                           :outcome :ledger-updated
                           :reviewed? true
                           :findings findings
                           :changes (ledger/changes prior final))]
        (success state' receipt []))

      (and (seq findings) final-empty? (not prior-empty?))
      (let [tenure' (assoc tenure
                           :ledger final
                           :first-review? false
                           :awaiting-user? false)]
        (relay! (assoc-tenure state :plan tenure')
                :plan tenure' :ledger-cleared nil true))

      (and (seq findings) final-empty? prior-empty?)
      (let [receipt (assoc (receipt-base :plan tenure)
                           :outcome :noop
                           :reviewed? true
                           :findings findings)]
        (success state receipt []))

      (and empty-input? (not prior-empty?))
      (let [receipt (assoc (receipt-base :plan tenure)
                           :outcome :ledger-unchanged
                           :reviewed? true
                           :findings findings)]
        (success state receipt []))

      :else
      (plan-empty-review state tenure))))

(defn- enter-swarm
  [state lane tenure findings ledger*]
  (let [{:keys [tasks next-task-seq]}
        (make-root-tasks state tenure ledger*)
        tenure' (assoc tenure
                       :ledger ledger*
                       :first-review? false
                       :awaiting-user? false
                       :review-window :closed
                       :swarm-phase :active
                       :relay-pending? false
                       :root-tasks tasks)
        state' (-> state
                   (assoc-tenure lane tenure')
                   (assoc :next-task-seq next-task-seq))
        receipt (assoc (receipt-base lane tenure')
                       :outcome :swarm-started
                       :reviewed? true
                       :findings findings
                       :root-tasks tasks
                       :review-window :closed)
        effect {:type :swarm/start
                :lane lane
                :tenure-id (:tenure-id tenure')
                :milestone (:milestone tenure')
                :root-tasks tasks
                :wake :all
                :review-window :closed}]
    (success state' receipt [effect])))

(defn- complete-closing!
  [state tenure board]
  (cond
    (nil? board)
    (fail state :BOARD_SNAPSHOT_REQUIRED
          "closing requires a board snapshot"
          {:lane :execution})

    (not (barrier/closing-quiescent? board))
    (fail state :RESOURCES_NOT_QUIESCENT
          "tenure still owns live resources"
          {:reasons (barrier/closing-reasons board)})

    (not (barrier/ff-ready? board))
    (fail state :FF_NOT_READY
          "fast-forward dry-run is not ready"
          {:lane :execution})

    :else
    (promote! (merge-handled-inputs state board) :execution tenure)))

(defn- execution-empty-review
  [state tenure board]
  (cond
    (and (:initial? tenure)
         (= :execution (:milestone tenure))
         (not (:asked? tenure)))
    (ask! state :execution tenure execution-ask)

    (:initial? tenure)
    (relay! state :execution tenure :initial-empty-review board true)

    (= :closing (:milestone tenure))
    (complete-closing! state tenure board)

    :else
    (promote! state :execution tenure)))

(defn- execution-review
  [state tenure findings board]
  (let [prior (:ledger tenure)
        prior-empty? (ledger/ledger-empty? prior)
        final (ledger/apply-findings prior findings)
        final-empty? (ledger/ledger-empty? final)
        empty-input? (empty? findings)]
    (cond
      (and (seq findings) (not final-empty?))
      (enter-swarm state :execution tenure findings final)

      (and (seq findings) final-empty? (not prior-empty?))
      (fail state :RELAY_NOT_READY
            "execution ledger is released only by root task completion"
            {:ledger prior})

      (and (seq findings) final-empty? prior-empty?)
      (let [receipt (assoc (receipt-base :execution tenure)
                           :outcome :noop
                           :reviewed? true
                           :findings findings)]
        (success state receipt []))

      (and empty-input? (not prior-empty?))
      (let [receipt (assoc (receipt-base :execution tenure)
                           :outcome :ledger-unchanged
                           :reviewed? true
                           :findings findings)]
        (success state receipt []))

      :else
      (execution-empty-review state tenure board))))

(defn- start-execution
  [state plan-tenure findings {:keys [draft-source draft-user-edited?]
                               :or {draft-source :program
                                    draft-user-edited? false}}]
  (let [draft (:draft state)]
    (cond
      (seq findings)
      (fail state :NONEMPTY_FINDINGS_AT_PENDING
            "pending Plan accepts only an empty review"
            {:findings findings})

      (not (ledger/ledger-empty? (:ledger plan-tenure)))
      (fail state :NONEMPTY_LEDGER_AT_PENDING
            "pending Plan must have an empty ledger"
            {:ledger (:ledger plan-tenure)})

      (or (nil? draft)
          (not (valid-draft-path? (:path draft)))
          (str/blank? (str (:content draft))))
      (fail state :DRAFT_MISSING_OR_EMPTY
            "cannot start execution without a non-empty plans/<name>.md draft"
            {:draft-path (get-in state [:draft :path])})

      :else
      (let [next-epoch (:next-tenure-seq state)
            execution (tenure/create-tenure
                       {:tenure-id (str "tenure-" next-epoch)
                        :epoch next-epoch
                        :lane :execution
                        :milestone :execution
                        :initial? true})
            plan' (assoc plan-tenure
                         :first-review? false
                         :awaiting-user? false)
            state' (-> state
                       (assoc-tenure :plan plan')
                       (assoc-tenure :execution execution)
                       (assoc :next-tenure-seq (inc next-epoch)))
            receipt (assoc (receipt-base :plan plan')
                           :outcome :execution-started
                           :reviewed? true
                           :execution-tenure-id (:tenure-id execution)
                           :execution-epoch (:epoch execution)
                           :execution-tenure-count
                           (count (execution-tenures state'))
                           :draft-path (:path draft)
                           :draft-length (count (:content draft)))
            effect {:type :execution/start
                    :lane :execution
                    :tenure execution
                    :draft-path (:path draft)
                    :draft (:content draft)
                    :draft-length (count (:content draft))
                    :draft-source (or (get draft :source) draft-source)
                    :draft-user-edited?
                    (or (get draft :user-edited?) draft-user-edited?)
                    :milestone :execution}]
        (success state' receipt [effect])))))

(defn- review-new
  [state {:keys [lane tenure-id findings board draft-source
                 draft-user-edited?]}]
  (cond
    (not (valid-lane? lane))
    (fail state :INVALID_LANE
          "lane must be :plan or :execution"
          {:lane lane})

    (not (string? tenure-id))
    (fail state :INVALID_REQUEST
          "tenure-id must be a string"
          {:tenure-id tenure-id})

    :else
    (if-let [caller-error (caller-error state lane tenure-id)]
      caller-error
      (let [tenure (tenure-by-id state tenure-id)]
        (cond
          (:awaiting-user? tenure)
          (fail state :AWAITING_USER
                "tenure is suspended awaiting a human answer"
                {:lane lane :tenure-id tenure-id})

          (and (= lane :execution)
               (= :closed (:review-window tenure)))
          (fail state :REVIEW_WINDOW_CLOSED
                "Reviewer is suspended during swarm construction"
                {:lane lane :tenure-id tenure-id})

          (and (= lane :plan)
               (= :pending (:milestone tenure))
               (sequential? findings)
               (seq findings))
          (fail state :NONEMPTY_FINDINGS_AT_PENDING
                "pending Plan accepts only an empty review"
                {:findings findings})

          :else
          (let [normalized (ledger/normalize-findings findings)]
            (if-not (:ok? normalized)
              (fail state
                    (get-in normalized [:error :code])
                    (get-in normalized [:error :message])
                    (get-in normalized [:error :details]))
              (let [findings* (:findings normalized)]
                (cond
                  (and (= lane :plan)
                       (= :pending (:milestone tenure)))
                      (start-execution state tenure findings*
                                       {:draft-source draft-source
                                        :draft-user-edited? draft-user-edited?})

                  (= lane :plan)
                  (plan-review state tenure findings*)

                  :else
                  (execution-review state tenure findings* board))))))))))

(defn review
  "Submit a `(review {:findings [...]})` call.

  Required request fields: `:call-id`, `:lane`, `:tenure-id`, `:findings`.
  Execution relay decisions also require `:board`, a swarm STM snapshot.
  At Plan `:pending`, every distinct call-id creates an independent execution
  tenure; the board snapshot, when needed later, belongs to that tenure.

  The same call-id with the same payload returns the original receipt and
  no effects.  The same call-id with a different payload is rejected."
  [state {:keys [call-id lane tenure-id findings] :as request}]
  (let [lane (normalize-lane lane)
        payload {:lane lane
                 :tenure-id tenure-id
                 :findings findings}]
    (cond
      (not (valid-call-id? call-id))
      (fail state :CALL_ID_REQUIRED
            "review requires a non-blank string call-id"
            {:call-id call-id})

      :else
      (if-let [stored (get-in state [:receipts call-id])]
        (if (= payload (:payload stored))
          {:ok? true
           :state state
           :receipt (assoc (:receipt stored) :replayed true)
           :effects []}
          (fail state :CALL_ID_PAYLOAD_CONFLICT
                "call-id was already used with a different payload"
                {:call-id call-id}))
        (let [result (review-new state (assoc request :lane lane))]
          (if (:ok? result)
            (let [receipt (assoc (:receipt result) :call-id call-id)
                  result' (assoc result :receipt receipt)]
              (update result' :state with-receipt call-id payload receipt))
            result))))))

(defn resume
  "Clear the `:awaiting-user?` suspension after an `(unpark ...)`."
  [state {:keys [lane tenure-id]}]
  (let [lane (normalize-lane lane)]
    (cond
      (not (valid-lane? lane))
      (fail state :INVALID_LANE
            "lane must be :plan or :execution"
            {:lane lane})

      :else
      (if-let [caller-error (caller-error state lane tenure-id)]
        caller-error
        (let [tenure (tenure-by-id state tenure-id)]
          (if-not (:awaiting-user? tenure)
            (fail state :NOT_AWAITING_USER
                  "tenure is not suspended awaiting a human answer"
                  {:lane lane :tenure-id tenure-id})
            (let [tenure' (assoc tenure :awaiting-user? false)
                  state' (assoc-tenure state lane tenure')
                  receipt (assoc (receipt-base lane tenure')
                                 :outcome :resumed)]
              (success state' receipt []))))))))

(defn mark-awaiting
  "Mark a live tenure as suspended on a human answer.

  A program park is executed outside the relay, so the relay does not see it.
  The Owner projects the park and calls this to keep `:awaiting-user?` the
  single authority for `resume`; without it an answer has no parked tenure to
  name."
  [state {:keys [lane tenure-id]}]
  (let [lane (normalize-lane lane)]
    (cond
      (not (valid-lane? lane))
      (fail state :INVALID_LANE
            "lane must be :plan or :execution"
            {:lane lane})

      :else
      (if-let [caller-error (caller-error state lane tenure-id)]
        caller-error
        (let [tenure (tenure-by-id state tenure-id)
              tenure' (assoc tenure :awaiting-user? true :asked? true)]
          (success (assoc-tenure state lane tenure')
                   (assoc (receipt-base lane tenure')
                          :outcome :awaiting-user)
                   []))))))

;; ---------------------------------------------------------------------------
;; Swarm root-task completion and barrier retry

(defn finish-root-task
  "Mark one relay root task done and release its ledger key.

  Identify the task by `:task-id` or, for direct swarm-board integration, by
  `:ledger-key`.

  `:board` is optional.  When supplied and this was the last open root, the
  barrier is attempted in the same returned state.  Without a board the host
  may call `maybe-relay` later with a fresh snapshot."
  [state {:keys [lane tenure-id task-id ledger-key board]}]
  (let [lane (normalize-lane lane)]
    (cond
      (not= lane :execution)
      (fail state :INVALID_LANE
            "only execution root tasks can be finished here"
            {:lane lane})

      :else
      (if-let [caller-error (caller-error state lane tenure-id)]
        caller-error
        (let [tenure (tenure-by-id state tenure-id)]
          (if-not (= :active (:swarm-phase tenure))
            (fail state :NOT_IN_SWARM
                  "tenure is not in swarm construction"
                  {:lane lane :tenure-id tenure-id})
            (if-let [task (cond
                            task-id
                            (some #(when (and (= task-id (:task-id %))
                                              (or (nil? ledger-key)
                                                  (= ledger-key
                                                     (:ledger-key %))))
                                     %)
                                  (:root-tasks tenure))

                            ledger-key
                            (some #(when (= ledger-key (:ledger-key %)) %)
                                  (:root-tasks tenure))

                            :else nil)]
              (let [resolved-task-id (:task-id task)
                    already-done? (= :done (:state task))
                    tenure' (-> tenure
                                (assoc :root-tasks
                                       (mark-task-done (:root-tasks tenure)
                                                       resolved-task-id))
                                (assoc :ledger
                                       (dissoc (:ledger tenure)
                                               (:ledger-key task))))
                    state' (assoc-tenure state lane tenure')
                    receipt (assoc (receipt-base lane tenure')
                                   :outcome (if already-done?
                                              :already-finished
                                              :root-finished)
                                   :reviewed? false
                                   :task-id resolved-task-id
                                   :ledger-key (:ledger-key task))]
                (if (or (nil? board)
                        (not (ledger/ledger-empty? (:ledger tenure'))))
                  (success state' receipt [])
                  (let [relay-result (relay! state' lane tenure'
                                             :all-roots-finished board false)]
                    (if (:ok? relay-result)
                      (update relay-result :receipt merge
                              {:finished-task-id resolved-task-id
                               :finished-ledger-key (:ledger-key task)})
                      (assoc relay-result
                             :state state'
                             :error (update (:error relay-result) :details
                                            assoc :committed? true))))))
              (fail state :UNKNOWN_ROOT_TASK
                    "root task does not belong to the live tenure"
                    {:lane lane :tenure-id tenure-id
                     :task-id task-id :ledger-key ledger-key}))))))))

(defn maybe-relay
  "Retry a pending barrier after the board settles.

  This is the host hook for `finish-root-task` calls that omitted a board
  snapshot and for reviews that returned `:relay-pending`."
  [state {:keys [lane tenure-id board]}]
  (let [lane (normalize-lane lane)]
    (cond
      (not= lane :execution)
      (fail state :NOT_IN_SWARM
            "relay retry is only meaningful for execution"
            {:lane lane})

      :else
      (if-let [caller-error (caller-error state lane tenure-id)]
        caller-error
        (let [tenure (tenure-by-id state tenure-id)]
          (cond
            (and (not (:relay-pending? tenure))
                 (not= :active (:swarm-phase tenure)))
            (fail state :NOT_IN_SWARM
                  "tenure has no pending relay and is not in swarm construction"
                  {:lane lane :tenure-id tenure-id})

            (not (ledger/ledger-empty? (:ledger tenure)))
            (success state
                     (assoc (receipt-base lane tenure)
                            :outcome :relay-pending
                            :reviewed? false
                            :reasons [:ledger-not-empty])
                     [])

            :else
            (relay! state lane tenure :board-settled board false)))))))

;; ---------------------------------------------------------------------------
;; Abandoning one execution tenure

(defn abandon-execution-tenure
  "Mark one execution tenure `:abandoned` after REASON.

  The tenure remains in the execution lane as terminal evidence, so the
  host can still find its board and bindings.  Its ledger and root-task
  mirror are cleared; the mission host removes the board tasks in the same
  transition.  A repeated abandon is an idempotent success.  A Plan tenure
  is never abandonable (`:ABANDON_PLAN_TENURE_FORBIDDEN`), and no other
  lane may be passed here.

  AT is optional; a map REASON may carry `:at` instead."
  ([state tenure-id reason]
   (abandon-execution-tenure state tenure-id reason nil))
  ([state tenure-id reason at]
   (let [tenure (tenure-by-id state tenure-id)]
     (cond
       (nil? tenure)
       (if (contains? (:retired-tenure-ids state) tenure-id)
         (fail state :TENURE_RETIRED
               "tenure has retired"
               {:lane :execution :tenure-id tenure-id})
         (fail state :UNKNOWN_TENURE
               "tenure-id does not identify a live tenure"
               {:lane :execution :tenure-id tenure-id}))

       (= :plan (:lane tenure))
       (fail state :ABANDON_PLAN_TENURE_FORBIDDEN
             "the Plan tenure cannot be abandoned"
             {:lane :plan :tenure-id tenure-id})

       (not= :execution (:lane tenure))
       (fail state :TENURE_LANE_MISMATCH
             "only an execution tenure can be abandoned"
             {:lane :execution :tenure-lane (:lane tenure)})

       (nil? reason)
       (fail state :ABANDON_REASON_REQUIRED
             "abandon requires a reason"
             {:lane :execution :tenure-id tenure-id})

       (tenure/abandoned? tenure)
       (success state
                (assoc (receipt-base :execution tenure)
                       :outcome :already-abandoned
                       :reason (:abandoned-reason tenure)
                       :retired-at (:retired-at tenure))
                [])

       (tenure/completed? tenure)
       (fail state :TENURE_ALREADY_COMPLETED
             "a completed tenure cannot be abandoned"
             {:lane :execution :tenure-id tenure-id})

       (not (active-tenure? tenure))
       (fail state :TENURE_RETIRED
             "tenure is not active"
             {:lane :execution :tenure-id tenure-id
              :status (:status tenure)})

       :else
       (let [tenure' (tenure/abandon-tenure tenure reason at)
             state' (assoc-tenure state :execution tenure')
             receipt (assoc (receipt-base :execution tenure')
                            :outcome :abandoned
                            :reason reason
                            :abandoned-reason reason
                            :retired-at (:retired-at tenure'))]
         (success state' receipt
                  [{:type :relay/tenure-abandoned
                    :lane :execution
                    :tenure-id tenure-id
                    :reason reason
                    :at (:retired-at tenure')}]))))))

;; ---------------------------------------------------------------------------
;; Current-state compaction

(defn prune-barriers
  "Keep only the newest `keep-last` barrier records.

  The barrier log is an audit suffix: pruning removes oldest records first,
  never a hole in the middle.  `barrier-seq` and retired-tenure ids remain,
  so late-request rejection and future barrier ids are unaffected."
  [state keep-last]
  (if-not (and (integer? keep-last) (not (neg? keep-last)))
    (fail state :INVALID_PRUNE_COUNT
          "keep-last must be a non-negative integer"
          {:keep-last keep-last})
    (let [barriers (vec (or (:barriers state) []))
          kept (if (zero? keep-last)
                 []
                 (vec (take-last (min keep-last (count barriers)) barriers)))
          state' (assoc state :barriers kept)]
      (success state'
               {:status :accepted
                :outcome :barriers-pruned
                :kept (count kept)
                :dropped (- (count barriers) (count kept))
                :barrier-seq (:barrier-seq state)}
               []))))

;; ---------------------------------------------------------------------------
;; Nudge support

(defn central-nudge-message
  "Return the §2.5 centralized-phase Nudge text for a ledger map.

  This is the reusable text builder for hosts that already hold a tenure
  ledger but not a whole relay state."
  [ledger]
  (let [entries (ledger/entries (or ledger {}))]
    (if (seq entries)
      (str nudge-directive
           "\n未完成事项：\n"
           (str/join "\n"
                     (map (fn [[k v]] (str "- " k ": " v)) entries)))
      (str nudge-directive
           "\n当前里程碑尚未完成评审、继任验收或交接。"))))

(defn- nudge-for-tenure
  [state lane tenure]
  (cond
    (nil? tenure) nil
    (tenure/completed? tenure) nil
    (not (active-tenure? tenure)) nil
    (:awaiting-user? tenure) nil
    (and (= lane :plan)
         (= :pending (:milestone tenure))
         (seq (execution-tenures state))) nil
    (and (= lane :execution)
         (= :active (:swarm-phase tenure))) nil
    :else
    (central-nudge-message (:ledger tenure))))

(defn nudge-message
  "Return the centralized-phase Nudge text, or nil when Nudge is suppressed.

  With two arguments, `:execution` is only unambiguous when exactly one
  execution tenure exists.  With several handoffs, pass the specific
  `tenure-id` as the third argument.

  Worker Nudge during swarm construction belongs to the swarm/guard layer
  and uses the worker's responsibility set, not the review ledger."
  ([state lane]
   (let [lane (normalize-lane lane)]
     (nudge-for-tenure state lane (current-tenure state lane))))
  ([state lane tenure-id]
   (let [lane (normalize-lane lane)
         tenure (if tenure-id
                  (tenure-by-id state tenure-id)
                  (current-tenure state lane))]
     (when (and tenure (= lane (:lane tenure)))
       (nudge-for-tenure state lane tenure)))))

;; ---------------------------------------------------------------------------
;; Invariants

(defn- violation
  [code message details]
  {:code code :message message :details details})

(defn check-invariants
  "Check structural invariants of a relay state.

  Returns `{:ok? true :violations []}` or `{:ok? false :violations [...]}`.
  This is intentionally independent of the transition functions so tests can
  use it as an oracle after random operation sequences."
  [state]
  (let [plan (get-in state [:lanes :plan])
        execution-lane (get-in state [:lanes :execution])
        executions (execution-tenures state)
        execution-list (vals executions)
        retired (or (:retired-tenure-ids state) #{})
        active-ids (set (keep :tenure-id (cons plan execution-list)))
        barriers (or (:barriers state) [])
        execution-violations
        (fn [execution]
          (concat
           (when-not (and (map? execution)
                          (= :execution (:lane execution))
                          (= :reviewer (:role execution)))
             [(violation :INVALID_EXECUTION_TENURE
                         "execution lane must hold a Reviewer tenure"
                         {:execution execution})])

           (when-not (tenure/valid-milestone? :execution
                                              (:milestone execution))
             [(violation :INVALID_EXECUTION_MILESTONE
                         "execution milestone is not on the execution ladder"
                         {:milestone (:milestone execution)})])

           (when-not (ledger/valid-ledger? (:ledger execution))
             [(violation :INVALID_EXECUTION_LEDGER
                         "execution ledger contains an invalid entry"
                         {:ledger (:ledger execution)})])

           (when (and (map? (:ledger execution))
                      (seq (:ledger execution))
                      (not= :active (:swarm-phase execution)))
             [(violation :EXECUTION_LEDGER_OUTSIDE_SWARM
                         "a non-empty execution ledger requires active swarm construction"
                         {:ledger (:ledger execution)
                          :swarm-phase (:swarm-phase execution)})])

           (when (and (:relay-pending? execution)
                      (map? (:ledger execution))
                      (seq (:ledger execution)))
             [(violation :RELAY_PENDING_WITH_LEDGER
                         "a pending relay tenure must have an empty ledger"
                         {:ledger (:ledger execution)})])

           (when-not (contains? tenure/statuses (:status execution))
             [(violation :INVALID_EXECUTION_STATUS
                         "live execution tenure must be active, completed, or abandoned"
                         {:status (:status execution)})])

           (when (and (tenure/abandoned? execution)
                      (nil? (:abandoned-reason execution)))
             [(violation :ABANDONED_WITHOUT_REASON
                         "an abandoned tenure must record its reason"
                         {:tenure-id (:tenure-id execution)})])

           (when (and (tenure/abandoned? execution)
                      (not= :none (:swarm-phase execution)))
             [(violation :ABANDONED_WITH_SWARM_PHASE
                         "an abandoned tenure has no live swarm phase"
                         {:tenure-id (:tenure-id execution)
                          :swarm-phase (:swarm-phase execution)})])

           (when (and (tenure/abandoned? execution)
                      (not (ledger/ledger-empty? (:ledger execution))))
             [(violation :ABANDONED_WITH_LEDGER
                         "an abandoned tenure has no open ledger"
                         {:tenure-id (:tenure-id execution)
                          :ledger (:ledger execution)})])

           (when (and (tenure/abandoned? execution)
                      (:relay-pending? execution))
             [(violation :ABANDONED_WITH_PENDING_RELAY
                         "an abandoned tenure cannot have a pending relay"
                         {:tenure-id (:tenure-id execution)})])

           (let [ids (map :task-id (:root-tasks execution))]
             (when-not (= (count ids) (count (distinct ids)))
               [(violation :DUPLICATE_ROOT_TASK_ID
                           "root task ids must be unique"
                           {:task-ids ids})]))

           (when-not (every? #(contains? #{:ready :waiting :done}
                                          (:state %))
                             (:root-tasks execution))
             [(violation :INVALID_ROOT_TASK_STATE
                         "root task state must be ready, waiting, or done"
                         {:root-tasks (:root-tasks execution)})])

           (when (and (= :active (:swarm-phase execution))
                      (not= :closed (:review-window execution)))
             [(violation :SWARM_WINDOW_OPEN
                         "swarm construction requires a closed review window"
                         {:review-window (:review-window execution)})])

           (when (and (= :open (:review-window execution))
                      (not= :none (:swarm-phase execution)))
             [(violation :OPEN_WINDOW_IN_SWARM
                         "an open review window cannot coexist with swarm phase"
                         {:swarm-phase (:swarm-phase execution)})])

           (when (and (= :active (:status execution))
                      (= :closed (:review-window execution))
                      (not= :active (:swarm-phase execution)))
             [(violation :CLOSED_WINDOW_WITHOUT_SWARM
                         "an active tenure with a closed window must be in swarm"
                         {:review-window (:review-window execution)
                          :swarm-phase (:swarm-phase execution)})])

           (when (and (= :active (:swarm-phase execution))
                      (not= (set (keys (:ledger execution)))
                            (set (map :ledger-key
                                      (remove #(= :done (:state %))
                                              (:root-tasks execution))))))
             [(violation :ROOT_LEDGER_MISMATCH
                         "open root tasks must mirror the execution ledger"
                         {:ledger-keys (set (keys (:ledger execution)))
                          :open-root-keys
                          (set (map :ledger-key
                                    (remove #(= :done (:state %))
                                            (:root-tasks execution))))})])

           (when (and (= :active (:swarm-phase execution))
                      (not= (count (:ledger execution))
                            (count (remove #(= :done (:state %))
                                           (:root-tasks execution)))))
             [(violation :ROOT_COUNT_MISMATCH
                         "each ledger key must have exactly one open root task"
                         {:ledger-count (count (:ledger execution))
                          :open-root-count
                          (count (remove #(= :done (:state %))
                                         (:root-tasks execution)))})])

           (when (and (not= :active (:swarm-phase execution))
                      (seq (:root-tasks execution)))
             [(violation :STALE_ROOT_TASKS
                         "non-swarm tenure must not retain root tasks"
                         {:root-tasks (:root-tasks execution)})])

           (when (and (= :completed (:status execution))
                      (not (and (= :completed (:milestone execution))
                                (ledger/ledger-empty? (:ledger execution))
                                (empty? (:root-tasks execution))
                                (= :closed (:review-window execution)))))
             [(violation :COMPLETED_NOT_TERMINAL
                         "completed execution must be empty and closed"
                         {:execution execution})])))
        violations
        (concat
         (when-not (and (map? state) (string? (:session-id state)))
           [(violation :INVALID_STATE
                       "state must be a map with a string session-id"
                       {:state state})])

         (when (and (contains? state :user-inputs)
                    (not (valid-user-inputs? (:user-inputs state))))
           [(violation :INVALID_USER_INPUTS
                       "user-inputs may contain only non-blank :text and optional :message-id"
                       {:user-inputs (:user-inputs state)})])

         (when-not (and (map? plan)
                        (= :plan (:lane plan))
                        (= :plan (:role plan)))
           [(violation :INVALID_PLAN_TENURE
                       "Plan lane must hold a Plan tenure"
                       {:plan plan})])

         (when (and (map? plan)
                    (not= :active (:status plan)))
           [(violation :PLAN_TENURE_NOT_ACTIVE
                       "Plan tenure must stay active after handoff"
                       {:status (:status plan)})])

         (when (and (map? plan)
                    (not (tenure/valid-milestone? :plan (:milestone plan))))
           [(violation :INVALID_PLAN_MILESTONE
                       "Plan milestone is not on the Plan ladder"
                       {:milestone (:milestone plan)})])

         (when (and (map? plan)
                    (not= :open (:review-window plan)))
           [(violation :PLAN_WINDOW_CLOSED
                       "Plan review window is always open"
                       {:review-window (:review-window plan)})])

         (when (and (map? plan)
                    (not (ledger/valid-ledger? (:ledger plan))))
           [(violation :INVALID_PLAN_LEDGER
                       "Plan ledger contains an invalid entry"
                       {:ledger (:ledger plan)})])

         (when (and (map? plan)
                    (= :pending (:milestone plan))
                    (not (ledger/ledger-empty? (:ledger plan))))
           [(violation :PENDING_PLAN_LEDGER_OPEN
                       "pending Plan must have an empty ledger"
                       {:ledger (:ledger plan)})])

         (when-not (or (nil? execution-lane) (map? execution-lane))
           [(violation :INVALID_EXECUTION_LANE
                       "execution lane must map tenure-id -> tenure"
                       {:execution-lane execution-lane})])

         (when-not (every? (fn [[tenure-id execution]]
                             (= tenure-id (:tenure-id execution)))
                           executions)
           [(violation :EXECUTION_TENURE_KEY_MISMATCH
                       "execution map keys must equal tenure ids"
                       {:keys (set (keys executions))})])

         (mapcat execution-violations execution-list)

         (when (and (seq execution-list)
                    (not= :pending (get-in state [:lanes :plan :milestone])))
           [(violation :EXECUTION_BEFORE_PENDING
                       "execution may start only after Plan reaches pending"
                       {:plan-milestone (get-in state [:lanes :plan :milestone])})])

         (when (and (seq execution-list)
                    (not (ledger/ledger-empty? (:ledger plan))))
           [(violation :PLAN_LEDGER_AFTER_HANDOFF
                       "Plan must clear its ledger before execution starts"
                       {:plan-ledger (:ledger plan)})])

         (when (and (seq execution-list)
                    (str/blank? (str (get-in state [:draft :content]))))
           [(violation :MISSING_DRAFT
                       "execution requires a non-empty draft"
                       {:draft-path (get-in state [:draft :path])})])

         (when (seq (filter retired active-ids))
           [(violation :RETIRED_TENURE_ACTIVE
                       "retired tenure ids cannot be live"
                       {:retired active-ids})])

         (when-let [draft (:draft state)]
           (when-not (and (valid-draft-path? (:path draft))
                          (string? (:content draft)))
             [(violation :INVALID_DRAFT
                         "draft path or content is invalid"
                         {:draft-path (:path draft)})]))

         (when (and (integer? (:next-tenure-seq state))
                    (some #(when-let [epoch (:epoch %)]
                             (and (integer? epoch)
                                  (>= epoch (:next-tenure-seq state))))
                          (cons plan execution-list)))
           [(violation :EPOCH_NOT_MONOTONIC
                       "tenure epochs must be below next-tenure-seq"
                       {:next-tenure-seq (:next-tenure-seq state)})])

         (when (and (integer? (:barrier-seq state))
                    (< (:barrier-seq state) (count barriers)))
           [(violation :BARRIER_COUNT_MISMATCH
                       "barrier-seq cannot trail the stored barrier count"
                       {:barrier-seq (:barrier-seq state)
                        :barrier-count (count barriers)})])

         (for [barrier barriers
               :let [to (:to-tenure-id barrier)]
               :when (not (or (contains? active-ids to)
                              (contains? retired to)))]
           (violation :BARRIER_TO_UNKNOWN
                      "barrier successor must be live or retired"
                      {:barrier-id (:barrier-id barrier)
                       :to-tenure-id to}))

         (for [barrier barriers
               :let [from (:from-tenure-id barrier)]
               :when (contains? active-ids from)]
           (violation :BARRIER_FROM_LIVE
                      "barrier predecessor must be retired"
                      {:barrier-id (:barrier-id barrier)
                       :from-tenure-id from}))

         (when-let [last-plan (last (filter #(= :plan (:lane %)) barriers))]
           (when (and plan
                      (not= (:to-tenure-id last-plan) (:tenure-id plan)))
             [(violation :BARRIER_SUCCESSOR_MISMATCH
                         "last Plan barrier must point at the live Plan tenure"
                         {:barrier-to (:to-tenure-id last-plan)
                          :live (:tenure-id plan)})]))

         (for [barrier barriers
               :when (and (contains? barrier :from-milestone)
                          (not= (:from-milestone barrier)
                                (:milestone barrier)))]
           (violation :BARRIER_SUCCESSOR_MISMATCH
                      "successor milestone must be copied from the predecessor"
                      {:barrier-id (:barrier-id barrier)
                       :from-milestone (:from-milestone barrier)
                       :milestone (:milestone barrier)}))

         (for [barrier barriers
               :let [to-tenure (tenure-by-id state (:to-tenure-id barrier))]
               :when (and to-tenure
                          (not= (:lane barrier) (:lane to-tenure)))]
           (violation :BARRIER_LANE_MISMATCH
                      "barrier lane must match the live successor lane"
                      {:barrier-id (:barrier-id barrier)
                       :barrier-lane (:lane barrier)
                       :tenure-lane (:lane to-tenure)}))

         (for [barrier barriers
               :when (not (barrier/context-keys-valid? (:context barrier)))]
           (violation :CONTEXT_LEAK
                      "barrier context contains a non-visible key"
                      {:barrier-id (:barrier-id barrier)
                       :context-keys (set (keys (:context barrier)))}))

         (for [barrier barriers
               :when (not (true? (:predecessor-hidden? barrier)))]
           (violation :PREDECESSOR_NOT_HIDDEN
                      "barrier must hide predecessor private state"
                      {:barrier-id (:barrier-id barrier)}))

         (for [[call-id stored] (:receipts state)
               :when (not (and (string? call-id)
                               (map? stored)
                               (= :accepted (get-in stored [:receipt :status]))))]
           (violation :INVALID_RECEIPT
                      "receipt must be keyed by string and accepted"
                      {:call-id call-id})))]
    {:ok? (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Ledger accessors (Plan and execution remain deliberately separate)

(defn ledger-of
  [state lane]
  (case (normalize-lane lane)
    :plan (get-in state [:lanes :plan :ledger])
    :execution (some-> (current-tenure state :execution) :ledger)
    nil))

(defn ledger-for
  [state tenure-id]
  (some-> (tenure-by-id state tenure-id) :ledger))

(defn execution-ledgers
  "Return a map of execution tenure-id -> private ledger."
  [state]
  (into {}
        (map (fn [[tenure-id tenure]]
               [tenure-id (:ledger tenure)]))
        (execution-tenures state)))

(defn plan-ledger
  [state]
  (ledger-of state :plan))

(defn execution-ledger
  "Convenience accessor for the sole execution ledger.

  Returns nil when several execution tenures exist; use `execution-ledgers`
  or `ledger-for` in that case."
  [state]
  (ledger-of state :execution))
