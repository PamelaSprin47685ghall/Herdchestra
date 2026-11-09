(ns hc.compose.steward
  "Pure composition layer for one repository-wide Steward.

  The Steward owns one state value:

    {:schema-version 1
     :steward-id \"steward\"
     :intent {...}
     :capacity {...}
     :cognition ...      ; hc.core.steward-sphinx cognition
     :frontier ...       ; hc.core.steward-sphinx frontier cursors
     :docket ...         ; hc.core.steward-sphinx Docket
     :links {...}        ; mission-id -> runtime linkage
     :last-observation ...}

  Every function is pure.  This namespace calls the `hc.core.steward-sphinx`
  facade and does no I/O, no clock read, and no Git invocation.  A host passes
  the observation time and commits the returned state."
  (:require [clojure.string :as str]
            [hc.core.steward-sphinx :as ss]))

(def schema-version 1)
(def default-steward-id "steward")
(def default-slots-total 4)
(def default-min-spare 1)

(def ^:private fleet-status->mission-status
  {:running :active
   :queued :admitted
   :joined :completed
   :abandoned :abandoned
   :failed :failed})

(defn- error
  [code message details]
  (cond-> {:code code :message message}
    (some? details) (assoc :details details)))

(defn- failure
  [state code message details]
  {:ok? false
   :state state
   :error (error code message details)})

(defn- cognition-of
  [state]
  (or (:cognition state) (ss/initial-cognition)))

(defn- frontier-of
  [state]
  (or (:frontier state) (ss/initial-frontier-state)))

(defn- docket-of
  [state]
  (or (:docket state) (ss/initial-docket)))

;; ---------------------------------------------------------------------------
;; State

(defn initial-state
  "A Steward that has observed nothing yet.

  The intent text is empty, so `evaluate-frontier` refuses the world until
  the host observes a real operator intent."
  ([]
   (initial-state {}))
  ([opts]
   {:schema-version schema-version
    :steward-id (or (:steward-id opts) default-steward-id)
    :intent {:revision 0 :text "" :received-at 0}
    :capacity {:slots-total default-slots-total
               :slots-allocated 0
               :min-spare default-min-spare
               :missions []}
    :cognition (ss/initial-cognition)
    :frontier (ss/initial-frontier-state)
    :docket (ss/initial-docket)
    :links {}
    :last-observation nil}))

;; ---------------------------------------------------------------------------
;; Cognition

(defn ingest-git-evidence
  "Ingest one Git observation into the Standing Sphinx.

  Duplicate evidence is a no-op.  A conflict leaves the Steward state
  unchanged and reports the core refusal."
  [state evidence]
  (let [result (ss/ingest-evidence (cognition-of state) evidence)]
    (if (:ok? result)
      (cond-> {:ok? true :state (assoc state :cognition (:state result))}
        (contains? result :evidence)
        (assoc :evidence (:evidence result))

        (contains? result :new?)
        (assoc :new? (:new? result))

        (contains? result :invalidated-assumption-ids)
        (assoc :invalidated-assumption-ids
               (:invalidated-assumption-ids result))

        (contains? result :newly-invalidated-assumption-ids)
        (assoc :newly-invalidated-assumption-ids
               (:newly-invalidated-assumption-ids result)))
      (failure state
               (get-in result [:error :code])
               (get-in result [:error :message])
               (get-in result [:error :details])))))

(defn revise-cognition
  "Submit a new thought frontier, assumptions, and claims.

  The Sphinx refuses a revision without new evidence."
  [state revision-spec]
  (let [result (ss/revise-best-answer (cognition-of state) revision-spec)]
    (if (:ok? result)
      {:ok? true
       :state (assoc state :cognition (:state result))
       :revision (:revision result)}
      (failure state
               (get-in result [:error :code])
               (get-in result [:error :message])
               (get-in result [:error :details])))))

(defn observe-world
  "Ingest one world observation.

  `git-commits` are ingested in order.  `intent` and `capacity` are validated
  by the Sphinx and replace the stored values when present.  `now` becomes
  `:last-observation :at`.

  The observation is atomic: an invalid intent, an invalid capacity, or an
  evidence shape error returns the unchanged input state.  A conflict found
  while ingesting also discards earlier commits from the same batch."
  [state observation]
  (let [{:keys [git-commits intent capacity now]} observation]
    (cond
      (not (map? observation))
      (failure state :INVALID_OBSERVATION
               "world observation must be a map" {:observation observation})

      (not (or (nil? git-commits) (sequential? git-commits)))
      (failure state :INVALID_OBSERVATION
               "world observation :git-commits must be sequential"
               {:git-commits git-commits})

      :else
      (let [intent-result (if (nil? intent)
                            {:ok? true :value nil}
                            (ss/normalize-intent intent))
            capacity-result (if (nil? capacity)
                              {:ok? true :value nil}
                              (ss/normalize-capacity capacity))]
        (cond
          (not (:ok? intent-result))
          (failure state
                   (get-in intent-result [:error :code])
                   (get-in intent-result [:error :message])
                   (get-in intent-result [:error :details]))

          (not (:ok? capacity-result))
          (failure state
                   (get-in capacity-result [:error :code])
                   (get-in capacity-result [:error :message])
                   (get-in capacity-result [:error :details]))

          :else
          (let [raw-commits (vec (or git-commits []))
                normalized (mapv ss/normalize-evidence raw-commits)
                invalid (first (remove :ok? normalized))]
            (if invalid
              (failure state
                       (get-in invalid [:error :code])
                       (get-in invalid [:error :message])
                       (get-in invalid [:error :details]))
              (let [evidence (mapv :value normalized)
                    ingested
                    (reduce (fn [acc raw]
                              (let [result (ss/ingest-evidence
                                            (cognition-of (:state acc))
                                            raw)]
                                (if (:ok? result)
                                  (-> acc
                                      (assoc-in [:state :cognition]
                                                (:state result))
                                      (update :evidence-ids conj
                                              (get-in result
                                                      [:evidence :evidence-id])))
                                  (reduced (assoc (failure state
                                                           (get-in result
                                                                   [:error :code])
                                                           (get-in result
                                                                   [:error :message])
                                                           (get-in result
                                                                   [:error :details]))
                                                  :evidence-ids
                                                  [])))))
                            {:ok? true :state state :evidence-ids []}
                            evidence)]
                (if (:ok? ingested)
                  (let [observed (cond-> (:state ingested)
                                   (some? intent)
                                   (assoc :intent (:value intent-result))

                                   (some? capacity)
                                   (assoc :capacity (:value capacity-result)))
                        observation-summary
                        (cond-> {:at now
                                 :git-commit-count (count evidence)
                                 :intent-revision (get-in observed
                                                           [:intent :revision])}
                          (seq (:evidence-ids ingested))
                          (assoc :latest-evidence-id
                                 (peek (:evidence-ids ingested)))

                          (some? capacity)
                          (assoc :capacity-signature
                                 (ss/capacity-signature
                                  (:capacity observed))))]
                    {:ok? true
                     :state (assoc observed
                                   :last-observation observation-summary)})
                  (dissoc ingested :evidence-ids))))))))))

;; ---------------------------------------------------------------------------
;; Frontier

(defn evaluate-frontier
  "Let the Steward pull at the current Decision Frontier.

  The world is the stored intent, capacity, and Sphinx cognition.  The
  returned state always advances the frontier cursors, so the same world
  does not trigger twice.  `:triggered?` is true only when a frontier was
  emitted."
  [state]
  (let [result (ss/evaluate-frontier
                (frontier-of state)
                {:intent (:intent state)
                 :capacity (:capacity state)
                 :cognition (cognition-of state)})]
    (if (:ok? result)
      (let [frontier (:frontier result)]
        {:ok? true
         :state (assoc state :frontier (:state result))
         :triggered? (some? frontier)
         :frontier frontier
         :reasons (vec (:reasons result))})
      (failure state
               (get-in result [:error :code])
               (get-in result [:error :message])
               (get-in result [:error :details])))))

;; ---------------------------------------------------------------------------
;; Docket and mission linkage

(defn- admitted-link
  [receipt mission-id status]
  {:mission-id mission-id
   :status status
   :request-id (:request-id receipt)
   :decision-id (:decision-id receipt)
   :frontier-id (:frontier-id receipt)
   :docket-version (:docket-version receipt)})

(defn- link-admitted
  [links receipt docket]
  (reduce (fn [m mission-id]
            (if (contains? m mission-id)
              m
              (assoc m mission-id
                     (admitted-link receipt mission-id
                                    (get-in docket
                                            [:missions mission-id :status]
                                            :admitted)))))
          links
          (:admitted receipt)))

(defn decide
  "Decide the candidate Missions at the last emitted frontier.

  The stored Docket, cognition, and frontier are supplied to the core.  A
  replay by `request-id` returns the original receipt.  An admitted mission
  without a link gets an `:admitted` link entry; an existing link is never
  overwritten."
  [state {:keys [request-id candidates policy] :as request}]
  (if-not (map? request)
    (failure state :INVALID_REQUEST "decision request must be a map"
             {:request request})
    (let [result (ss/decide
                  (docket-of state)
                  {:request-id request-id
                   :frontier (get-in (frontier-of state) [:last-frontier])
                   :cognition (cognition-of state)
                   :candidates candidates
                   :policy policy})]
      (if (:ok? result)
        (let [receipt (:receipt result)]
          (cond-> {:ok? true
                   :state (-> state
                              (assoc :docket (:docket result))
                              (update :links link-admitted
                                      receipt (:docket result)))
                   :decision receipt}
            (contains? result :replayed?)
            (assoc :replayed? (:replayed? result))))
        (failure state
                 (get-in result [:error :code])
                 (get-in result [:error :message])
                 (get-in result [:error :details]))))))

(defn link-mission
  "Merge runtime linkage into one Docket mission.

  `:mission-id` and `:status` are authoritative in the Docket; the caller
  cannot override them here.  Use `transition-mission` to move the status."
  [state mission-id link-map]
  (let [mission (get-in (docket-of state) [:missions mission-id])]
    (cond
      (nil? mission)
      (failure state :UNKNOWN_MISSION
               "mission id is not in the Docket" {:mission-id mission-id})

      (not (map? link-map))
      (failure state :INVALID_LINK
               "mission link must be a map" {:mission-id mission-id
                                             :link link-map})

      :else
      (let [state' (update-in state [:links mission-id]
                              #(merge {:mission-id mission-id
                                       :status (:status mission)}
                                      %
                                      (assoc (dissoc link-map
                                                      :mission-id :status)
                                             :mission-id mission-id
                                             :status (:status mission))))]
        {:ok? true
         :state state'
         :link (get-in state' [:links mission-id])}))))

(defn transition-mission
  "Move one Docket mission and mirror the status into its link entry.

  The transition rules stay in `hc.core.steward-sphinx`."
  [state mission-id next-status reason]
  (let [result (ss/transition-mission (docket-of state)
                                      {:mission-id mission-id
                                       :to next-status
                                       :reason reason})]
    (if (:ok? result)
      (let [unchanged? (true? (:unchanged? result))]
        (let [state' (-> state
                         (assoc :docket (:docket result))
                         (update-in [:links mission-id]
                                    #(cond-> (merge {:mission-id mission-id}
                                                    %
                                                    {:mission-id mission-id
                                                     :status next-status})
                                       (not unchanged?)
                                       (assoc :status-reason reason))))]
          {:ok? true
           :state state'
           :mission (:mission result)
           :link (get-in state' [:links mission-id])}))
      (failure state
               (get-in result [:error :code])
               (get-in result [:error :message])
               (get-in result [:error :details])))))

;; ---------------------------------------------------------------------------
;; Fleet capacity

(defn- non-negative-integer
  [value default]
  (if (and (integer? value) (not (neg? value)))
    value
    default))

(defn- positive-integer
  [value default]
  (if (and (integer? value) (pos? value))
    value
    default))

(defn- mission-id-for
  [row index]
  (let [candidate (or (:mission-id row)
                      (:slice-id row)
                      (:swarm-id row))]
    (if (and (string? candidate) (not (str/blank? candidate)))
      candidate
      (str "fleet-row-" index))))

(defn- fleet-entries
  [fleet-rows]
  (keep-indexed
   (fn [index row]
     (when (map? row)
       (when-let [status (get fleet-status->mission-status (:status row))]
         {:mission-id (mission-id-for row index)
          :status status
          :running? (= :running (:status row))
          :queued? (= :queued (:status row))})))
   fleet-rows))

(defn- mission-capacity
  [mission-id entries]
  (let [statuses (map :status entries)
        status (cond
                 (some #{:active} statuses) :active
                 (some #{:admitted} statuses) :admitted
                 (some #{:failed} statuses) :failed
                 (some #{:abandoned} statuses) :abandoned
                 :else :completed)]
    {:mission-id mission-id
     :status status
     :absorbable (count (filter :queued? entries))
     :demand (count entries)}))

(defn fleet-capacity
  "Aggregate fleet rows into an `ss/normalize-capacity` observation.

  `:running` rows occupy slots.  `:queued` rows are admitted work that can
  absorb spare slots, so they are `:absorbable`.  `:joined`, `:abandoned`,
  and `:failed` rows are finished.  Rows with an unknown or missing status
  are ignored.

  Options:

    :slots-total  configured compute slots, default 4
    :min-spare    meaningful spare slots, default 1

  The total never drops below the running count, so the result always
  normalizes."
  ([fleet-rows]
   (fleet-capacity fleet-rows {}))
  ([fleet-rows {:keys [slots-total min-spare]}]
   (let [entries (vec (fleet-entries (or fleet-rows [])))
         allocated (count (filter :running? entries))
         configured-total (non-negative-integer slots-total
                                                default-slots-total)
         total (max configured-total allocated)
         missions (->> entries
                       (group-by :mission-id)
                       (map (fn [[mission-id group]]
                              (mission-capacity mission-id group)))
                       (sort-by :mission-id)
                       vec)]
     {:slots-total total
      :slots-allocated allocated
      :min-spare (positive-integer min-spare default-min-spare)
      :missions missions})))

;; ---------------------------------------------------------------------------
;; Snapshot

(defn- frontier-view
  [frontier]
  (when frontier
    (select-keys frontier
                 [:frontier-id :frontier-seq :reasons :operator-intent
                  :cognition-version :cba-version :capacity :pull-only?])))

(defn snapshot
  "A bounded view of the Steward for prompts and operators.

  The full cognition, frontier cursors, raw evidence, and Docket payloads
  stay out of the snapshot.  The Current Best Answer and the living Docket
  summary remain readable."
  [state]
  (let [cognition (cognition-of state)]
    {:steward-id (:steward-id state)
     :cognition-version (ss/cognition-version cognition)
     :best-answer (ss/best-answer cognition)
     :frontier (frontier-view (get-in (frontier-of state) [:last-frontier]))
     :docket-summary (ss/docket-summary (docket-of state))
     :links (or (:links state) {})
     :last-observation (:last-observation state)}))
