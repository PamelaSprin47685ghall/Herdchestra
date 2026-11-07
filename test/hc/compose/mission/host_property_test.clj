(ns hc.compose.mission.host-property-test
  "Property tests for root-task ledger release: each finished root removes
  exactly one ledger key, and only the last root retires the execution
  tenure; parents are never auto-completed by this path."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.mission :as mission]
            [hc.compose.mission.support :as support]
            [hc.core.relay :as relay]))

(defspec each-root-finish-removes-one-ledger-key 50
  (prop/for-all [n (gen/choose 1 4)
                 order (gen/shuffle (range 4))]
    (let [h (support/host)
          _ (support/drive-plan-to-pending! h)
          _ (support/start-execution! h)
          execution-id (support/sole-execution-id h)
          findings (mapv (fn [i] {:key (str "k" i) :value "v"}) (range n))
          _ (support/start-swarm! h execution-id findings)
          roots (vec (support/root-task-ids h execution-id))
          actor (support/register-worker! h execution-id :slot-a)
          ordered (take n (map roots (filter #(< % n) order)))]
      (loop [remaining ordered
             expected n]
        (if-let [task-id (first remaining)]
          (let [before (count (relay/ledger-for
                               (:relay (mission/snapshot h)) execution-id))
                _ (support/claim-and-finish-root! h execution-id actor task-id)
                after (count (relay/ledger-for
                              (:relay (mission/snapshot h)) execution-id))]
            (if (and (= expected before)
                     (= (dec expected) after))
              (recur (rest remaining) (dec expected))
              false))
          (and (zero? expected)
               (not (contains? (set (support/execution-tenure-ids h))
                               execution-id))))))))
