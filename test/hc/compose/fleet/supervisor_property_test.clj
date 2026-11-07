(ns hc.compose.fleet.supervisor-property-test
  "Property tests for supervisor boundaries: mission role precedence, a
  missing/throwing conflict resolver abandons only the named swarm, and stop
  cancels every indexed attempt."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.supervisor :as supervisor]))

(defspec mission-role-prefers-explicit-mission-role 50
  (prop/for-all [role (gen/elements [:worker :holder :main :task])
                 mission-role (gen/elements [nil :worker :holder])]
    (let [request (cond-> {:role role}
                    mission-role (assoc :mission-role mission-role))]
      (= (or mission-role role) (supervisor/mission-role request)))))

(defspec missing-or-throwing-resolver-abandons-only-that-swarm 50
  (prop/for-all [swarm-id gen/string-alphanumeric]
    (let [missing (supervisor/make {:service-ref (atom {})
                                    :start-effect! (fn [_] {:ok? true})})
          throwing (supervisor/make {:service-ref (atom {})
                                     :start-effect! (fn [_] {:ok? true})
                                     :resolve-conflict! (fn [_]
                                                          (throw (ex-info "boom" {})))})]
      (and (= :abandoned (:status (supervisor/resolve-conflict!
                                   missing {:swarm-id swarm-id})))
           (= :abandoned (:status (supervisor/resolve-conflict!
                                   throwing {:swarm-id swarm-id})))
           (= swarm-id (get-in (supervisor/resolve-conflict!
                                     missing {:swarm-id swarm-id})
                                    [:reason :swarm-id]))))))

(defspec stop-cancels-every-indexed-attempt 50
  (prop/for-all [attempt-id (gen/such-that seq gen/string-alphanumeric 100)]
    (let [cancelled (atom [])
          sup (supervisor/make
               {:service-ref (atom {})
                :start-effect! (fn [_] {:ok? true})})
          launched ((:launch sup)
                    {:swarm-id "s"
                     :actor {:state {}
                             :effects [{:type :model/start
                                        :attempt-id attempt-id
                                        :swarm-id "s"}]}})
          stopped (supervisor/stop! sup {:cancel-attempt!
                                         (fn [id] (swap! cancelled conj id))})]
      (and (:ok? launched)
           (true? (:stopped? stopped))
           (= 1 (:attempts stopped))
           (= 1 (:cancelled stopped))
           (= [attempt-id] @cancelled)))))
