(ns hc.compose.mission.proof-property-test
  "Property tests for the opaque W proof: only the in-process token is valid,
  and arbitrary EDN values cannot forge it.  Parent-completion V06 is covered
  by hc.core.swarm-stm-property-test, not by this capability token."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.mission.proof :as proof]))

(defspec arbitrary-values-cannot-forge-the-w-proof 50
  (prop/for-all [value gen/any]
    (and (not (proof/valid? value))
         (not (proof/valid? nil))
         (not (proof/valid? true))
         (not (proof/valid? {:under-write-gate? true}))
         (proof/valid? (proof/token))
         (identical? (proof/token) (proof/token)))))

(defspec proof-token-is-a-single-identity 50
  (prop/for-all [_ (gen/return nil)]
    (let [a (proof/token)
          b (proof/token)]
      (and (identical? a b)
           (proof/valid? a)
           (proof/valid? b)
           (not (proof/valid? (Object.)))))))
