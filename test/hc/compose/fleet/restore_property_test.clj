(ns hc.compose.fleet.restore-property-test
  "Property tests for fleet snapshot validation and restart recovery:
  a valid FIFO snapshot validates, a reversed completion queue is rejected,
  and a running swarm without a recovered runtime becomes explicitly
  uncertain instead of being replayed."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.restore :as restore]
            [hc.compose.fleet.state :as state]))

(defn- running-state [n]
  (let [s (state/new-state {:mission-id "restore-property"
                            :worktree-root "/tmp/hc-fleet-restore"})
        s (:state (state/fork
                   s (mapv (fn [i] {:slice-id (keyword (str "s" i))
                                    :goal "g" :inputs []
                                    :depends-on [] :done-when "d"})
                           (range n))))
        s (:state (state/mark-worktree-ready
                   s (mapv :swarm-id (vals (:swarms s)))))]
    (:state (state/start-ready s))))

(defn- snapshot [s]
  {:fleet/snapshot-version restore/snapshot-version
   :fleet/version (:version s)
   :mission-id (:mission-id s)
   :master-ref (:master-ref s)
   :state s
   :runtimes {}
   :worktrees (mapv (fn [sw]
                      {:swarm-id (:swarm-id sw)
                       :slice-id (:slice-id sw)
                       :workspace (:workspace sw)
                       :branch (:branch sw)
                       :status (:status sw)
                       :worktree-removed? false})
                    (vals (:swarms s)))})

(defspec valid-snapshot-validates-and-reversed-queue-is-rejected 50
  (prop/for-all [n (gen/choose 2 4)]
    (let [s (running-state n)
          ids (mapv :swarm-id (sort-by :created-seq (vals (:swarms s))))
          completed (reduce (fn [s id]
                              (:state (state/complete-swarm
                                       s id "report" {:board-version 0})))
                            s ids)
          good (snapshot completed)
          bad (snapshot (update completed :completion-queue
                                #(vec (reverse %))))]
      (and (:ok? (restore/validate good))
           (false? (:ok? (restore/validate bad)))
           (= :INVALID_SWARM_STATE
              (get-in (restore/validate bad) [:error :code]))))))

(defspec running-without-runtime-is-explicitly-uncertain 50
  (prop/for-all [n (gen/choose 1 4)]
    (let [s (running-state n)
          result (restore/recover-state
                  s {:now 1
                     :worktree-exists? (constantly true)
                     :runtime-ids #{}})]
      (and (:ok? result)
           (= n (count (:failed-uncertain result)))
           (every? (fn [id]
                     (= :failed (get-in result [:state :swarms id :status])))
                   (mapv :swarm-id (vals (:swarms s))))))))
