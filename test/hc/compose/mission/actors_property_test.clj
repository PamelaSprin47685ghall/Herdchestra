(ns hc.compose.mission.actors-property-test
  "Property tests for slot rebirth: a dead board actor is replaced with a
  fresh, never-reused agent id and an empty private context; the old running
  attempt is cancelled."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.mission :as mission]
            [hc.compose.mission.actors :as actors]
            [hc.compose.mission.support :as support]))

(defn- active-swarm []
  (let [h (support/host)]
    (support/drive-plan-to-pending! h)
    (support/start-execution! h)
    (support/start-swarm! h (support/sole-execution-id h)
                          [{:key "k" :value "v"}])
    h))

(defspec dead-actor-rebirth-uses-a-new-agent-id-and-empty-context 50
  (prop/for-all [old-context (gen/vector gen/any 1 3)]
    (let [h (active-swarm)
          tenure-id (support/sole-execution-id h)
          mission-state (mission/snapshot h)
          actors-state (actors/new-state {:models {:main "main" :task "task"}
                                          :slot-ids [:slot-a]})
          synced (actors/sync-tenure mission-state actors-state tenure-id)]
      (if-not (:ok? synced)
        false
        (let [old-agent (get-in synced [:actors :tenures tenure-id
                                        :slots :slot-a :agent-id])
              old-actors (-> (:actors synced)
                             (assoc-in [:tenures tenure-id :slots :slot-a
                                        :context] old-context)
                             (assoc-in [:tenures tenure-id :slots :slot-a
                                        :status] :running)
                             (assoc-in [:tenures tenure-id :slots :slot-a
                                        :attempt-id] "old-attempt"))
              dead-mission (assoc-in (:mission synced)
                                     [:boards tenure-id :actors old-agent
                                      :life-state] :dead)
              reborn (actors/sync-tenure dead-mission old-actors tenure-id)]
          (and (:ok? reborn)
               (not= old-agent (get-in reborn [:actors :tenures tenure-id
                                               :slots :slot-a :agent-id]))
               (= (inc (get-in synced [:actors :tenures tenure-id
                                       :slots :slot-a :generation]))
                  (get-in reborn [:actors :tenures tenure-id
                                  :slots :slot-a :generation]))
               (empty? (get-in reborn [:actors :tenures tenure-id
                                       :slots :slot-a :context]))
               (some #(= :actor/rebirth (:type %)) (:effects reborn))
               (some #(= :model/cancel (:type %)) (:effects reborn))))))))
