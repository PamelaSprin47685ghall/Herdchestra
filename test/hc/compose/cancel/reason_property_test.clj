(ns hc.compose.cancel.reason-property-test
  "Property tests for the fixed cancellation vocabulary: five reasons,
  operator/programmatic origins disjoint, and descendants always
  :parent-terminated."
  (:require [clojure.set :as set]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.cancel.reason :as reason]))

(defspec five-reasons-are-exact-and-disjoint 50
  (prop/for-all [r (gen/elements (vec reason/reasons))]
    (and (reason/reason? r)
         (= 5 (count reason/reasons))
         (empty? (set/intersection reason/operator-origins
                                   reason/programmatic-origins)))))

(defspec operator-cancel-requires-user-cancel-and-operator-origin 50
  (prop/for-all [origin (gen/elements (concat reason/operator-origins
                                              reason/programmatic-origins))
                 r (gen/elements (conj (vec reason/reasons) :not-a-reason))]
    (= (and (reason/operator-origin? origin) (= :user-cancel r))
       (reason/operator-cancel? {:origin origin :reason r}))))

(defspec every-descendant-is-parent-terminated 50
  (prop/for-all [root-reason (gen/elements (vec reason/reasons))]
    (= :parent-terminated (reason/descendant-reason root-reason))))
