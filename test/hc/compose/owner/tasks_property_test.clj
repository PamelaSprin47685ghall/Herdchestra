(ns hc.compose.owner.tasks-property-test
  "Property tests for Owner business combinators: every independent branch
  produces one outcome, and required steps succeed only when all succeed."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.owner.tasks :as tasks]
            [missionary.core :as m]))

(defn- task
  [index ok?]
  (m/sp
    (if ok?
      index
      (throw (ex-info "branch failed" {:kind :timeout})))))

(defspec independent-tools-return-one-outcome-per-branch 50
  (prop/for-all [outcomes (gen/vector gen/boolean 1 8)]
    (let [tasks (mapv (fn [[index ok?]] (task index ok?))
                      (map-indexed vector outcomes))
          result (m/? (tasks/run-independent-tools tasks))
          results (:results result)]
      (and (= :succeeded (:result/status result))
           (= (count outcomes) (count results))
           (= (mapv :id results) (range (count outcomes)))
           (= (mapv #(if % :succeeded :failed) outcomes)
              (mapv :result/status results))
           (every? (fn [[ok? outcome]]
                     (if ok?
                       (= (:value outcome) (:id outcome))
                       (= :timeout (get-in outcome [:result/error :kind]))))
                   (map vector outcomes results))))))

(defspec required-steps-succeed-only-when-all-succeed 50
  (prop/for-all [outcomes (gen/vector gen/boolean 1 8)]
    (let [tasks (mapv (fn [[index ok?]] (task index ok?))
                      (map-indexed vector outcomes))
          result (m/? (tasks/run-required-steps tasks))
          all-ok? (every? true? outcomes)]
      (if all-ok?
        (and (= :succeeded (:result/status result))
             (= (vec (range (count outcomes))) (:value result)))
        (and (= :failed (:result/status result))
             (= :timeout (get-in result [:result/error :kind])))))))
