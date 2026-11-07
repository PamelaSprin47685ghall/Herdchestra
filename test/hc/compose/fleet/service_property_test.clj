(ns hc.compose.fleet.service-property-test
  "Property tests for the service boundary over a real empty control:
  an empty fleet joins as :none, and status runtime/actor counts are derived
  from the runtime map without inventing actors."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.control :as control]
            [hc.compose.fleet.service :as service])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- empty-service []
  (let [root (str (Files/createTempDirectory "hc-service-" (make-array FileAttribute 0)))
        c (control/new-control {:mission-id "service-property"
                                :worktree-root root
                                :repo "/tmp"
                                :launch-swarm! (fn [_] {:ok? true})})]
    {:control c
     :runtimes (atom {})
     :closed? (atom false)
     :mission-id "service-property"
     :master-ref "master"
     :restored (atom false)
     :persist-error (atom nil)}))

(defspec empty-fleet-join-is-none 50
  (prop/for-all [timeout (gen/elements [nil 1 10])]
    (= :none (service/join! (empty-service)
                            (cond-> {} timeout (assoc :timeout-ms timeout))))))

(defspec status-counts-runtimes-without-inventing-actors 50
  (prop/for-all [n (gen/choose 0 6)]
    (let [runtimes (into {}
                         (map (fn [i]
                                [(str "s" i)
                                 {:status :running
                                  :actor (when (even? i) {:agent-id i})}])
                              (range n)))
          svc (assoc (empty-service) :runtimes (atom runtimes))
          status (service/status svc)]
      (and (= n (:runtime-count status))
           (= (count (filter :actor (vals runtimes))) (:actor-count status))))))
