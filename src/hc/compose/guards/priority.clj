(ns hc.compose.guards.priority
  "Priority groups for the guarded-command scheduler (AGENTS.md §5.2).

  A lower rank runs first.  The scheduler evaluates the enabled guards of one
  rank before it evaluates the next rank.

    P0  control and cancellation
    P1  result acceptance and responsibility closure
    P2  retry planning and due retry claims
    P3  unattended progress and new work claims
    P4  blocked assertion

  P4 is a diagnostic rank.  The scheduler synthesizes its condition from the
  absence of every other enabled guard.  A domain guard may still declare P4
  when it needs to run after all progress guards become false.")

(def p0-control 0)
(def p1-closure 1)
(def p2-retry 2)
(def p3-progress 3)
(def p4-blocked 4)

(def rank-by-keyword
  {:p0 p0-control
   :p0-control p0-control
   :p1 p1-closure
   :p1-closure p1-closure
   :p2 p2-retry
   :p2-retry p2-retry
   :p3 p3-progress
   :p3-progress p3-progress
   :p4 p4-blocked
   :p4-blocked p4-blocked})

(defn rank
  "Return the integer rank of PRIORITY, or nil when PRIORITY is unknown.

  PRIORITY is either a keyword in `rank-by-keyword` or an integer from 0 to 4."
  [priority]
  (cond
    (integer? priority)
    (when (<= p0-control priority p4-blocked)
      (long priority))

    (keyword? priority)
    (get rank-by-keyword priority)

    :else
    nil))

(defn valid?
  "True when PRIORITY names a known rank."
  [priority]
  (some? (rank priority)))

(defn rotate-after
  "Rotate CANDIDATES so that the candidate after LAST-ID comes first.

  CANDIDATES is a vector of maps with a `:guard-id` key.  A nil or unknown
  LAST-ID starts at the first candidate.  This rotation gives every guard in
  one priority group a turn before an earlier guard repeats."
  [candidates last-id]
  (let [candidates (vec candidates)
        n (count candidates)
        index (when last-id
                (first (keep-indexed
                        (fn [i candidate]
                          (when (= last-id (:guard-id candidate)) i))
                        candidates)))]
    (if (and index (< (inc index) n))
      (into (subvec candidates (inc index))
            (subvec candidates 0 (inc index)))
      candidates)))
