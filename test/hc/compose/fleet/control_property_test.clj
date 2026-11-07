(ns hc.compose.fleet.control-property-test
  "Property tests for the fleet control queue state machine: completed
  results are FIFO, an abandoned result is not queued, and joining every
  completed result ends with :none."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.control :as control]
            [hc.compose.fleet.state :as state]))

(defn- running-state [n]
  (let [slices (mapv (fn [i] {:slice-id (keyword (str "s" i))
                              :goal (str "g" i)
                              :inputs []
                              :depends-on []
                              :done-when "done"})
                     (range n))
        s (state/new-state {:mission-id "property" :worktree-root "/tmp/hc-fleet-prop"})
        s (:state (state/fork s slices))
        s (:state (state/mark-worktree-ready s (mapv :swarm-id (vals (:swarms s)))))]
    (:state (state/start-ready s))))

(defn- ids [s]
  (mapv :swarm-id (sort-by :created-seq (vals (:swarms s)))))

(defspec completed-queue-is-fifo-and-abandon-is-not-queued 50
  (prop/for-all [n (gen/choose 1 4)
                 completed-count (gen/choose 0 4)
                 abandon? gen/boolean]
    (let [s (running-state n)
          all (ids s)
          completed (vec (take (min completed-count n) all))
          s (reduce (fn [s id]
                      (:state (state/complete-swarm s id "report" {:board-version 0})))
                    s completed)
          abandoned (when (and abandon? (seq all))
                      (first (remove (set completed) all)))
          s (if abandoned
              (:state (state/abandon-swarm s abandoned "test abandon" {:now 0}))
              s)
          queue (:completion-queue s)
          join (state/join-state s)]
      (and (= completed queue)
           (not (some #{abandoned} queue))
           (or (empty? queue)
               (= [:ready (first queue)]
                  ((juxt :status :swarm-id) join)))))))

(defspec joining-all-completed-results-ends-with-none 50
  (prop/for-all [n (gen/choose 1 4)
                 order (gen/shuffle (range 4))]
    (let [s (running-state n)
          all (ids s)
          ordered (take n (map all (filter #(< % n) order)))
          s (reduce (fn [s id]
                      (:state (state/complete-swarm s id "report" {:board-version 0})))
                    s ordered)]
      (loop [s s]
        (let [join (state/join-state s)]
          (if (= :ready (:status join))
            (let [joined (state/joined-swarm s (:swarm-id join)
                                             {:ff-commit "sha"
                                              :rebase-rounds 0
                                              :now 0})]
              (if (:ok? joined)
                (recur (:state joined))
                false))
            (= :none (:status join))))))))
