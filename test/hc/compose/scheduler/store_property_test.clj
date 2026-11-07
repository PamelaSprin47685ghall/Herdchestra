(ns hc.compose.scheduler.store-property-test
  "Property tests for the scheduler persistence boundary: absent state is
  nil, saved state round-trips unchanged, last save wins, and pending runs
  are not dropped by the store."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.scheduler.store :as store]))

(defspec memory-store-roundtrips-arbitrary-state 50
  (prop/for-all [state gen/any]
    (let [s (store/memory-store)]
      (and (nil? (store/load-state s))
           (nil? (store/save-state! s state))
           (= state (store/load-state s))
           (= state (store/stored-state s))))))

(defspec last-save-wins-and-pending-runs-survive 50
  (prop/for-all [first-state gen/any
                 runs (gen/vector gen/any 0 5)
                 pending (gen/vector gen/any 0 5)
                 next-id gen/nat]
    (let [s (store/memory-store first-state)
          state {:runs runs
                 :pending-notifications pending
                 :next-run-id next-id
                 :schedules {:s1 {:state :enabled}}}]
      (store/save-state! s state)
      (let [loaded (store/load-state s)]
        (and (= state loaded)
             (= runs (:runs loaded))
             (= pending (:pending-notifications loaded))
             (= next-id (:next-run-id loaded)))))))
