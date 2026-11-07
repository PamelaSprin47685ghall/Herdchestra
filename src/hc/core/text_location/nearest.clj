(ns hc.core.text-location.nearest
  "Bounded nearest-line diagnostic for a zero-match `where`.

  The distance is Levenshtein edit distance over UTF-16 code units.  A
  Ukkonen band of K=64 keeps the DP exact for every distance that can be
  reported.  The scan stops at 2000 non-blank lines and at 10M DP cells."
  (:require [hc.core.text-location.scan :as scan])
  (:import (java.util Arrays)))

(set! *warn-on-reflection* true)

(def default-max-distance 64)
(def default-max-lines 2000)
(def default-dp-budget 10000000)
(def max-text-units 200)
(def max-band 4096)

(defn- banded-middle
  "Banded Levenshtein over the trimmed middles.

  Diagonal `d = i - j`.  Only `|d| <= k` is materialized.  Iterate `d`
  downward so the insertion cell `cur[d+1]` already belongs to this row."
  [^String a a-start n ^String b b-start m k budget]
  (let [inf (int (inc (long k)))
        width (int (inc (* 2 (long k))))
        offset (int k)
        prev (int-array width inf)
        cur (int-array width inf)
        row0 (long (min (long m) (long k)))
        row0-cells (inc row0)]
    (if (> row0-cells budget)
      {:distance nil :cells 0 :exhausted? true}
      (do
        (loop [j 0]
          (when (<= j row0)
            (aset prev (int (- offset j)) (int j))
            (recur (inc j))))
        (loop [i 1
               used row0-cells
               prev prev
               cur cur]
          (if (> i n)
            (let [d (- (long n) (long m))
                  v (aget prev (int (+ offset d)))]
              {:distance (when (<= (long v) (long k)) (long v))
               :cells used
               :exhausted? false})
            (let [d-min (max (- (long k)) (- (long i) (long m)))
                  d-max (min (long k) (long i))]
              (Arrays/fill cur inf)
              (let [step
                    (loop [d d-max
                           used used]
                      (if (< d d-min)
                        {:used used :complete? true}
                        (if (>= used budget)
                          {:used used :complete? false}
                          (let [j (- (long i) d)
                                ;; Deletion comes from prev[d-1], insertion
                                ;; from cur[d+1], substitution from prev[d].
                                v (if (zero? j)
                                    i
                                    (let [sub (if (= (.charAt a (int (+ (long a-start) (dec i))))
                                                     (.charAt b (int (+ (long b-start) (dec j)))))
                                                0
                                                1)
                                          del (if (>= (dec d) (- (long k)))
                                                (min inf (inc (long (aget prev (int (+ offset (dec d)))))))
                                                inf)
                                          ins (if (<= (inc d) (long k))
                                                (min inf (inc (long (aget cur (int (+ offset (inc d)))))))
                                                inf)]
                                      (min inf
                                           (+ (long (aget prev (int (+ offset d)))) (long sub))
                                           (long del)
                                           (long ins))))]
                            (aset cur (int (+ offset d)) (int v))
                            (recur (dec d) (inc used))))))]
                (if (:complete? step)
                  (recur (inc i) (long (:used step)) cur prev)
                  {:distance nil :cells (:used step) :exhausted? true})))))))))

(defn banded-distance
  "Levenshtein distance between A and B, bounded by MAX-DISTANCE.

  Return `{:distance d|nil :cells n :exhausted? bool}`.  `:distance` is nil
  when the true distance exceeds MAX-DISTANCE.  Every evaluated DP cell counts
  against BUDGET.  MAX-DISTANCE must be at most 4096 so the band stays small."
  [^String a ^String b max-distance budget]
  (let [k (long max-distance)
        budget (long budget)
        n (.length a)
        m (.length b)]
    (when (neg? k)
      (throw (ex-info "text-location: max-distance must not be negative"
                      {:status :INVALID_ARGUMENT
                       :error :INVALID_ARGUMENT
                       :field :max-distance
                       :actual max-distance})))
    (when (> k max-band)
      (throw (ex-info (str "text-location: max-distance must be <= " max-band)
                      {:status :INVALID_ARGUMENT
                       :error :INVALID_ARGUMENT
                       :field :max-distance
                       :expected (str "a non-negative integer <= " max-band)
                       :actual max-distance})))
    (when (neg? budget)
      (throw (ex-info "text-location: dp-budget must not be negative"
                      {:status :INVALID_ARGUMENT
                       :error :INVALID_ARGUMENT
                       :field :dp-budget
                       :actual budget})))
    (if (> (Math/abs (long (- n m))) k)
      {:distance nil :cells 0 :exhausted? false}
      (let [prefix (loop [i 0]
                     (if (and (< i n)
                              (< i m)
                              (= (.charAt a (int i)) (.charAt b (int i))))
                       (recur (inc i))
                       i))
            suffix (let [max-s (min (- (long n) (long prefix))
                                    (- (long m) (long prefix)))]
                     (loop [s 0]
                       (if (and (< s max-s)
                                (= (.charAt a (int (- (long n) 1 (long s))))
                                   (.charAt b (int (- (long m) 1 (long s))))))
                         (recur (inc s))
                         s)))
            an (- (long n) (long prefix) (long suffix))
            bm (- (long m) (long prefix) (long suffix))]
        (cond
          (zero? an)
          {:distance (when (<= bm k) bm) :cells 0 :exhausted? false}

          (zero? bm)
          {:distance (when (<= an k) an) :cells 0 :exhausted? false}

          :else
          (banded-middle a (int prefix) (int an) b (int prefix) (int bm) (int k) budget))))))

(defn- nearest-entry
  [start end ^String line distance]
  (let [{:keys [text truncated?]} (scan/truncate-text line max-text-units)]
    (cond-> {:span [(int start) (int end)]
             :text text
             :distance (long distance)}
      truncated? (assoc :truncated true))))

(defn nearest-line
  "Find the closest line within MAX-DISTANCE of NEEDLE.

  Scan the first MAX-LINES non-blank lines in content order.  A line whose
  length differs by more than MAX-DISTANCE is skipped without DP work.  Ties
  keep the earliest line.  An empty needle returns immediately without
  scanning lines.  Return `{:nearest map|nil :budget-exhausted? bool}`."
  [^String content ^String needle opts]
  (let [{:keys [max-distance max-lines dp-budget]
         :or {max-distance default-max-distance
              max-lines default-max-lines
              dp-budget default-dp-budget}} opts
        k (long max-distance)
        needle-length (.length needle)]
    (if (zero? needle-length)
      {:nearest nil :budget-exhausted? false}
      (let [lines (scan/line-spans content (long max-lines))]
        (loop [remaining lines
               best nil
               left (long dp-budget)]
          (if-let [{:keys [start end]} (first remaining)]
            (let [start (int start)
                  end (int end)
                  line-length (- end start)
                  diff (Math/abs (long (- needle-length line-length)))]
              (if (> diff k)
                (recur (next remaining) best left)
                (let [line (subs content start end)
                      {:keys [distance cells exhausted?]}
                      (banded-distance needle line k left)]
                  (if exhausted?
                    {:nearest best :budget-exhausted? true}
                    (let [best' (if (and (some? distance)
                                         (or (nil? best)
                                             (< (long distance) (long (:distance best)))))
                                  (nearest-entry start end line distance)
                                  best)]
                      (recur (next remaining) best' (- left (long cells))))))))
            {:nearest best :budget-exhausted? false}))))))
