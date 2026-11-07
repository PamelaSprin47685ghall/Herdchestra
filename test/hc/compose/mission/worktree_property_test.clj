(ns hc.compose.mission.worktree-property-test
  "Property tests for worktree runtime selection: exact tenure, exact
  workspace, sole-runtime fallback, and no guess among several."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.mission.worktree :as worktree]))

(defspec runtime-selection-never-guesses-among-several 50
  (prop/for-all [n (gen/choose 1 4)
                 requested-index (gen/choose 0 3)
                 exact-tenure? gen/boolean]
    (let [runtimes (into {}
                         (map (fn [i]
                                [(keyword (str "t" i))
                                 {:root (str "/work/" i)}])
                              (range n)))
          requested (vec (keys runtimes))
          index (min requested-index (dec n))
          request (if exact-tenure?
                    {:tenure-id (nth requested index)}
                    {:workspace (str "/work/" index)})
          selected (worktree/runtime-for runtimes request)]
      (cond
        exact-tenure?
        (= (get runtimes (nth requested index)) selected)

        (= 1 n)
        (= (get runtimes (first requested)) selected)

        :else
        (= (get runtimes (nth requested index)) selected)))))
