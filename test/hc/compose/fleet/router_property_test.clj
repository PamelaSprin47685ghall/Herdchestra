(ns hc.compose.fleet.router-property-test
  "Property tests for the pure fleet router boundary: fork results are
  preserved, worktree shortage is an explicit error, and join errors are not
  disguised as success."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.router :as router]))

(defspec fork-result-or-shortage-is-explicit 50
  (prop/for-all [n (gen/choose 0 6)
                 shortage? gen/boolean]
    (let [slices (mapv (fn [i] {:slice-id (keyword (str "s" i))
                                :goal (str "g" i)})
                       (range n))
          service {:fork (fn [_ _]
                           (if shortage?
                             :INSUFFICIENT_WORKTREES
                             slices))}
          result (router/handle service {:kind :fleet/fork :slices slices})]
      (if shortage?
        (and (false? (:ok? result))
             (= :INSUFFICIENT_WORKTREES (get-in result [:error :code])))
        (and (:ok? result)
             (= slices (:result result))
             (not (contains? result :state)))))))

(defspec join-error-is-not-success 50
  (prop/for-all [code (gen/elements [:JOIN_TIMEOUT :FLEET_DEADLOCK :JOIN_INTERRUPTED])]
    (let [service {:join! (fn [_ _] {:fleet/error {:code code}})}
          result (router/handle service {:kind :fleet/join})]
      (and (false? (:ok? result))
           (= code (get-in result [:error :code]))))))
