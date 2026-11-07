(ns hc.compose.owner.guards-property-test
  "Property tests for the guard scheduler: only the highest enabled priority
  is selected, selection is deterministic for a fresh scheduler, and quota
  exhaustion re-enqueues instead of spinning."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.guards.scheduler :as scheduler]))

(def ^:private priorities [:p0-control :p1-closure :p2-retry :p3-progress
                           :p4-blocked])

(defn- guard
  [priority]
  {:id (keyword (str (name priority) "-guard"))
   :priority priority
   :enabled? (fn [state _] (when (get state priority) {:work true}))
   :prepare (fn [state _ _ _]
              {:state (update state :n (fnil inc 0))
               :dispatch []})})

(defn- catalog []
  (scheduler/catalog (mapv guard priorities)))

(defspec highest-enabled-priority-wins-deterministically 50
  (prop/for-all [enabled (gen/such-that seq (gen/set (gen/elements priorities))
                                        100)]
    (let [state (into {} (map (fn [p] [p (contains? enabled p)]) priorities))
          cat (catalog)
          sched (scheduler/new-scheduler)
          candidate (scheduler/next-candidate cat state {} sched)
          expected (first (filter enabled priorities))]
      (and (some? candidate)
           (= expected (:priority candidate))
           (= candidate (scheduler/next-candidate cat state {}
                                                  (scheduler/new-scheduler)))))))

(defspec quota-exhaustion-requeues 50
  (prop/for-all [quota (gen/choose 1 5)]
    (let [cat (scheduler/catalog [(guard :p3-progress)])
          sched (scheduler/new-scheduler {:quota quota})
          state {:p3-progress true :n 0}]
      (loop [s sched
             left quota]
        (if (zero? left)
          (let [result (scheduler/step cat state {} s)]
            (and (= :quota-exhausted (:status result))
                 (true? (:continue? result))
                 (= :quota-requeue (get-in result [:scheduler :wakeup :reason]))))
          (let [result (scheduler/step cat state {} s)]
            (if (= :prepared (:status result))
              (let [tid (get-in result [:transition :transition-id])
                    committed (scheduler/after-commit (:scheduler result) tid)
                    acked (scheduler/ack-dispatch (:scheduler committed) tid)]
                (recur (:scheduler acked) (dec left)))
              false)))))))
