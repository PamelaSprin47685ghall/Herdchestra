(ns hc.core.steward-sphinx.frontier
  "Pull-only Stewardship Decision Frontier detection.

  The Sphinx never calls this namespace.  A frontier exists only when the
  world changed in one of two ways:

  - a new or modified operator intent;
  - meaningful unallocated execution capacity that admitted work cannot
    absorb.

  A frontier is emitted once per intent signature and capacity signature.
  Publication of a new Current Best Answer alone never creates a frontier."
  (:require [hc.core.steward-sphinx.util :as util]))

(def schema-version 1)

(def absorbing-statuses
  "Mission statuses whose work can still absorb another unit of compute."
  #{:admitted :planning :active})

(def mission-statuses
  #{:admitted :planning :active :completed :abandoned :failed})

(defn initial-frontier-state
  []
  {:schema-version schema-version
   :frontier-seq 0
   :intent-cursor {:revision 0 :text nil :received-at nil}
   :capacity-cursor nil
   :last-frontier nil
   :last-reasons []})

(defn normalize-intent
  "Validate the operator intent presented to the frontier.

  `:revision` is a non-negative integer; `:text` is the instruction itself.
  The revision is not required to increase: a modified text or a new
  `:received-at` at the same revision is still new intent."
  [raw]
  (cond
    (not (map? raw))
    (util/fail :INVALID_INTENT "operator intent must be a map" {:intent raw})

    (not (util/non-negative-integer? (:revision raw)))
    (util/fail :INVALID_INTENT
               "operator intent needs a non-negative integer :revision"
               {:revision (:revision raw)})

    (not (util/non-blank-string? (:text raw)))
    (util/fail :INVALID_INTENT
               "operator intent needs non-blank :text"
               {:text (:text raw)})

    :else
    {:ok? true
     :value (cond-> {:revision (:revision raw)
                     :text (:text raw)}
              (contains? raw :received-at)
              (assoc :received-at (:received-at raw)))}))

(defn intent-changed?
  [state intent]
  (let [cursor (:intent-cursor state)]
    (or (not= (:revision cursor) (:revision intent))
        (not= (:text cursor) (:text intent))
        (not= (:received-at cursor) (:received-at intent)))))

(defn- normalize-mission
  [raw]
  (let [mission-id (:mission-id raw)
        status (:status raw)
        absorbable (:absorbable raw 0)
        demand (:demand raw 0)]
    (cond
      (not (map? raw))
      (util/fail :INVALID_CAPACITY "mission must be a map" {:mission raw})

      (not (util/non-blank-string? mission-id))
      (util/fail :INVALID_CAPACITY
                 "mission needs a non-blank :mission-id"
                 {:mission raw})

      (not (contains? mission-statuses status))
      (util/fail :INVALID_CAPACITY
                 "mission status is not recognized"
                 {:mission-id mission-id
                  :status status
                  :supported mission-statuses})

      (not (util/non-negative-integer? absorbable))
      (util/fail :INVALID_CAPACITY
                 "mission :absorbable must be a non-negative integer"
                 {:mission-id mission-id :absorbable absorbable})

      (not (util/non-negative-integer? demand))
      (util/fail :INVALID_CAPACITY
                 "mission :demand must be a non-negative integer"
                 {:mission-id mission-id :demand demand})

      :else
      {:ok? true
       :value {:mission-id mission-id
               :status status
               :absorbable absorbable
               :demand demand}})))

(defn normalize-capacity
  "Validate a compute-capacity observation and derive spare capacity.

  `:slots-total` and `:slots-allocated` are non-negative integers and
  allocated cannot exceed total.  `:absorbable` is derived from missions whose
  status is in `absorbing-statuses`; it is not trusted as a caller-supplied
  scalar."
  [raw]
  (let [total (:slots-total raw)
        allocated (:slots-allocated raw)
        min-spare (or (:min-spare raw) 1)
        raw-missions (:missions raw [])]
    (cond
      (not (map? raw))
      (util/fail :INVALID_CAPACITY "capacity must be a map" {:capacity raw})

      (not (util/non-negative-integer? total))
      (util/fail :INVALID_CAPACITY
                 "capacity :slots-total must be a non-negative integer"
                 {:slots-total total})

      (not (util/non-negative-integer? allocated))
      (util/fail :INVALID_CAPACITY
                 "capacity :slots-allocated must be a non-negative integer"
                 {:slots-allocated allocated})

      (> allocated total)
      (util/fail :INVALID_CAPACITY
                 "allocated slots cannot exceed total slots"
                 {:slots-total total :slots-allocated allocated})

      (not (util/positive-integer? min-spare))
      (util/fail :INVALID_CAPACITY
                 "capacity :min-spare must be a positive integer"
                 {:min-spare min-spare})

      (not (sequential? raw-missions))
      (util/fail :INVALID_CAPACITY
                 "capacity :missions must be sequential"
                 {:missions raw-missions})

      :else
      (let [mission-results (util/normalize-all normalize-mission raw-missions)]
        (if-not (:ok? mission-results)
          mission-results
          (let [missions (:values mission-results)
                duplicate-ids (->> missions
                                   (map :mission-id)
                                   frequencies
                                   (keep (fn [[mission-id n]]
                                           (when (> n 1) mission-id)))
                                   sort
                                   vec)]
            (if (seq duplicate-ids)
              (util/fail :INVALID_CAPACITY
                         "capacity contains duplicate mission ids"
                         {:mission-ids duplicate-ids})
              (let [spare (- total allocated)
                    absorbable (reduce + 0
                                       (for [mission missions
                                             :when (contains? absorbing-statuses
                                                              (:status mission))]
                                         (:absorbable mission)))]
                {:ok? true
                 :value {:slots-total total
                         :slots-allocated allocated
                         :spare spare
                         :absorbable absorbable
                         :min-spare min-spare
                         :missions (vec (sort-by :mission-id missions))}}))))))))

(defn capacity-signature
  [capacity]
  (select-keys capacity
               [:slots-total :slots-allocated :spare :absorbable
                :min-spare :missions]))

(defn capacity-frontier?
  "True when there is meaningful spare capacity that admitted work cannot
  absorb."
  [capacity]
  (and (util/non-negative-integer? (:spare capacity))
       (util/positive-integer? (:min-spare capacity))
       (util/non-negative-integer? (:absorbable capacity))
       (>= (:spare capacity) (:min-spare capacity))
       (zero? (:absorbable capacity))))

(defn evaluate-frontier
  "Observe the world and return at most one new Decision Frontier.

  The world is `{:intent {...} :capacity {...} :cognition {...}}`.  The
  Sphinx is only read here; it cannot call this function itself.  The returned
  state advances both cursors even when no frontier is emitted, so the same
  world does not trigger again.  Cognition must carry a non-negative integer
  `:cognition-version`; its `:best-answer` must carry the same version and
  `:non-final? true`."
  [state world]
  (let [intent-result (normalize-intent (:intent world))
        capacity-result (normalize-capacity (:capacity world))
        cognition (:cognition world)]
    (cond
      (not (:ok? intent-result))
      (util/fail-state state
                       (get-in intent-result [:error :code])
                       (get-in intent-result [:error :message])
                       (get-in intent-result [:error :details]))

      (not (:ok? capacity-result))
      (util/fail-state state
                       (get-in capacity-result [:error :code])
                       (get-in capacity-result [:error :message])
                       (get-in capacity-result [:error :details]))

      (not (map? cognition))
      (util/fail-state state :INVALID_COGNITION
                       "frontier needs the Sphinx cognition map"
                       {:cognition cognition})

      (not (util/non-negative-integer? (:cognition-version cognition)))
      (util/fail-state state :INVALID_COGNITION
                       "cognition needs a non-negative integer version"
                       {:cognition-version (:cognition-version cognition)})

      (not (map? (:best-answer cognition)))
      (util/fail-state state :INVALID_COGNITION
                       "frontier needs cognition with a Current Best Answer"
                       {:best-answer (:best-answer cognition)})

      (not (util/non-negative-integer?
            (get-in cognition [:best-answer :cognition-version])))
      (util/fail-state state :INVALID_COGNITION
                       "the Current Best Answer needs a non-negative integer version"
                       {:cba-version
                        (get-in cognition [:best-answer :cognition-version])})

      (not= (:cognition-version cognition)
            (get-in cognition [:best-answer :cognition-version]))
      (util/fail-state state :INVALID_COGNITION
                       "cognition and its Current Best Answer disagree on version"
                       {:cognition-version (:cognition-version cognition)
                        :cba-version
                        (get-in cognition [:best-answer :cognition-version])})

      (not (true? (get-in cognition [:best-answer :non-final?])))
      (util/fail-state state :INVALID_COGNITION
                       "the Current Best Answer must be non-final"
                       {:non-final?
                        (get-in cognition [:best-answer :non-final?])})

      :else
      (let [intent (:value intent-result)
            capacity (:value capacity-result)
            intent-reason? (intent-changed? state intent)
            capacity-condition? (capacity-frontier? capacity)
            capacity-reason? (and capacity-condition?
                                  (not= (capacity-signature capacity)
                                        (:capacity-cursor state)))
            reasons (cond-> []
                      intent-reason? (conj :operator-intent)
                      capacity-reason? (conj :capacity))
            state' (-> state
                       (assoc :intent-cursor
                              (select-keys intent [:revision :text :received-at]))
                       (assoc :capacity-cursor (capacity-signature capacity)))
            frontier (when (seq reasons)
                       (let [frontier-seq (inc (:frontier-seq state))]
                         {:frontier-id (str "frontier-" frontier-seq)
                          :frontier-seq frontier-seq
                          :reasons reasons
                          :operator-intent intent
                          :best-answer (:best-answer cognition)
                          :cognition-version (:cognition-version cognition)
                          :cba-version (get-in cognition
                                               [:best-answer :cognition-version])
                          :capacity capacity
                          :pull-only? true}))
            state'' (if frontier
                      (-> state'
                          (assoc :frontier-seq (:frontier-seq frontier))
                          (assoc :last-frontier frontier)
                          (assoc :last-reasons reasons))
                      state')]
        {:ok? true
         :state state''
         :frontier frontier
         :reasons reasons
         :intent-changed? intent-reason?
         :capacity-frontier? capacity-condition?
         :capacity-frontier-emitted? capacity-reason?
         :cognition-version (:cognition-version cognition)}))))
