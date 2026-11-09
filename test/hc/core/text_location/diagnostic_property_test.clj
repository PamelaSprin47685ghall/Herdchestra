(ns hc.core.text-location.diagnostic-property-test
  "Property tests for unique/none/ambiguous anchor classification.

  A unique result must be the only literal occurrence (overlaps count), a
  zero-match or multiple-match result must park with a readable payload, and
  every returned span must lie inside the input text."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.text-location.diagnostic :as diagnostic]))

(defn- overlapping-count
  "Count literal occurrences of NEEDLE in TEXT, including overlaps."
  [^String text ^String needle]
  (let [n (.length needle)
        limit (inc (- (.length text) n))]
    (count (for [start (range limit)
                 :when (= needle (.substring text start (+ start n)))]
             start))))

(defn- span-in-bounds?
  [^String text [begin end :as span]]
  (and (vector? span)
       (= 2 (count span))
       (<= 0 begin end (.length text))))

(defn- match-at-span?
  [^String text ^String needle [begin end]]
  (= needle (.substring text begin end)))

(defspec classification-is-sound-and-exhaustive 100
  (prop/for-all [text (gen/fmap #(apply str %)
                                (gen/vector (gen/elements [\a \b \c]) 0 40))
                 needle (gen/fmap #(apply str %)
                                  (gen/vector (gen/elements [\a \b]) 1 4))]
    (let [occurrences (overlapping-count text needle)
          result (diagnostic/locate text needle)
          parked (diagnostic/where text needle)
          kind (:kind result)
          payload (:payload result)]
      (and
       (contains? #{:unique :none :ambiguous} kind)
       ;; The class follows from the literal occurrence count.
       (= (cond
            (zero? occurrences) :none
            (= 1 occurrences) :unique
            :else :ambiguous)
          kind)
       (case kind
         :unique
         (let [span (:span result)]
           (and (span-in-bounds? text span)
                (match-at-span? text needle span)
                (not (diagnostic/park-signal? parked))
                (= span parked)))

         :none
         (and (map? payload)
              (= needle (:needle payload))
              (= [] (:candidates payload))
              (contains? payload :nearest)
              (integer? (:max-distance payload))
              (boolean? (:budget-exhausted payload))
              (diagnostic/park-signal? parked)
              (= payload (:payload parked)))

         :ambiguous
         (let [candidates (:candidates payload)]
           (and (<= 1 (count candidates) diagnostic/max-candidates)
                (every? (fn [{:keys [span context]}]
                          (and (span-in-bounds? text span)
                               (match-at-span? text needle span)
                               (string? context)))
                        candidates)
                (diagnostic/park-signal? parked)
                (= payload (:payload parked))
                (= :where-ambiguous (:reason parked)))))))))
