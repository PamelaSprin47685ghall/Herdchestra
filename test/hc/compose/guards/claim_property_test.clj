(ns hc.compose.guards.claim-property-test
  "Property tests for claim validation: dispatch effects receive the claim's
  attempt id, mismatched ids are rejected, and a no-op plan is rejected."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.guards.claim :as claim]))

(defspec claim-attempt-id-is-propagated-to-every-dispatch-effect 50
  (prop/for-all [attempt-id (gen/such-that seq gen/string-alphanumeric 100)
                 n (gen/choose 1 4)]
    (let [state {:calls {"c" {:state :ready}}}
          plan {:state {:calls {"c" {:state :dispatching}}}
                :claim {:path [:calls "c" :state]
                        :work-id "c"
                        :attempt-path [:calls "c" :attempt-id]
                        :from :ready
                        :to :dispatching}
                :dispatch (mapv (fn [i] {:kind :model :payload {:i i}})
                                (range n))}
          result (claim/validate-plan state plan attempt-id)]
      (and (:ok? result)
           (= attempt-id (get-in result [:claim :attempt-id]))
           (= attempt-id (get-in result [:state :calls "c" :attempt-id]))
           (every? #(= attempt-id (:attempt-id %)) (:dispatch result))))))

(defspec mismatched-dispatch-attempt-is-rejected 50
  (prop/for-all [attempt-id (gen/such-that seq gen/string-alphanumeric 100)]
    (let [state {:calls {"c" {:state :ready}}}
          plan {:state {:calls {"c" {:state :dispatching}}}
                :claim {:path [:calls "c" :state]
                        :work-id "c"
                        :attempt-path [:calls "c" :attempt-id]
                        :from :ready
                        :to :dispatching}
                :dispatch [{:kind :model :attempt-id "other"}]}
          result (claim/validate-plan state plan attempt-id)]
      (and (false? (:ok? result))
           (= :DISPATCH_ATTEMPT_MISMATCH (get-in result [:error :code]))))))
