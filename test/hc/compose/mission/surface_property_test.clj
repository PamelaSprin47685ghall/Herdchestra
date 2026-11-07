(ns hc.compose.mission.surface-property-test
  "Property tests for the pure Plan program context: model/program role,
  S1 flag, draft path, and write-unlock are derived from committed state."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.mission :as mission]
            [hc.compose.mission.surface :as surface]))

(defspec plan-context-matches-committed-state 50
  (prop/for-all [milestone (gen/elements [:S1 :S2 :S3 :pending])
                 reviewed? gen/boolean
                 awaiting? gen/boolean
                 draft-path (gen/elements [nil "plans/a.md" "plans/b.md"])]
    (let [state (mission/initial-state
                 {:session-id "surface-property"
                  :models {:main "main-model" :task "task-model"}
                  :plan-milestone milestone})
          plan-id (-> state :relay :lanes :plan :tenure-id)
          state (cond-> state
                  true (assoc-in [:bindings :plan :reviewed-in-tenure?] reviewed?)
                  true (assoc-in [:relay :lanes :plan :awaiting-user?] awaiting?)
                  draft-path (assoc-in [:relay :draft :path] draft-path))
          result (surface/program-context state {:role :plan})]
      (and (:ok? result)
           (= :plan (get-in result [:context :role]))
           (= :clj-plan (get-in result [:context :program-role]))
           (= :main (get-in result [:context :model-role]))
           (= (contains? #{:S1} milestone)
              (get-in result [:context :s1?]))
           (= draft-path (get-in result [:context :draft-path]))
           (= (boolean (and reviewed? (not awaiting?)))
              (get-in result [:context :write-unlocked?]))))))
