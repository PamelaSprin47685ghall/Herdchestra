(ns hc.client.files.diff
  "Line diff for the workspace file preview.

  The comparison is honest about its limits:

  * a small changed middle uses an exact longest-common-subsequence walk;
  * a large middle falls back to one coarse replacement and says so;
  * a result that would require more than `max-display-lines` rendered
    lines returns `:too-large? true` with no fabricated hunk.

  The function is pure and runs on the JVM and in the browser."
  (:require [clojure.string :as str]))

(def ^:private max-lcs-cells 400000)
(def ^:private max-display-lines 4000)
(def ^:private context-lines 3)

(defn split-lines
  "Split text into display lines.  An empty text has no lines; a trailing
  newline is a real empty final line, not silently discarded."
  [text]
  (let [text (str (or text ""))]
    (if (= "" text)
      []
      (vec (str/split text #"\n" -1)))))

(defn- common-prefix
  [a b]
  (let [limit (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i limit) (= (nth a i) (nth b i)))
        (recur (inc i))
        i))))

(defn- common-suffix
  [a b from]
  (let [limit (min (- (count a) from) (- (count b) from))]
    (loop [n 0]
      (if (and (< n limit)
               (= (nth a (- (count a) 1 n))
                  (nth b (- (count b) 1 n))))
        (recur (inc n))
        n))))

(defn- lcs-ops
  "Exact LCS edit script for two middle vectors.  O(n*m) time and memory;
  the caller bounds the cell count."
  [a b]
  (let [n (count a)
        m (count b)
        dp (object-array (inc n))]
    (dotimes [i (inc n)]
      (aset dp i (int-array (inc m))))
    (letfn [(get-dp [i j]
              (aget ^ints (aget ^objects dp i) j))]
      (doseq [i (range (dec n) -1 -1)
              j (range (dec m) -1 -1)]
        (aset ^ints (aget ^objects dp i) j
              (if (= (nth a i) (nth b j))
                (inc (get-dp (inc i) (inc j)))
                (max (get-dp (inc i) j)
                     (get-dp i (inc j))))))
      (loop [i 0
             j 0
             ops []]
        (cond
          (and (< i n) (< j m) (= (nth a i) (nth b j)))
          (recur (inc i) (inc j)
                 (conj ops {:op :equal
                            :old i
                            :new j
                            :text (nth a i)}))

          (and (< i n)
               (or (= j m)
                   (>= (get-dp (inc i) j)
                       (get-dp i (inc j)))))
          (recur (inc i) j
                 (conj ops {:op :remove
                            :old i
                            :new nil
                            :text (nth a i)}))

          (< j m)
          (recur i (inc j)
                 (conj ops {:op :add
                            :old nil
                            :new j
                            :text (nth b j)}))

          :else ops)))))

(defn- middle-ops
  [a b]
  (let [n (count a)
        m (count b)]
    (if (> (* n m) max-lcs-cells)
      {:coarse? true
       :ops (concat (map-indexed (fn [i text]
                                   {:op :remove :old i :new nil :text text})
                                 a)
                    (map-indexed (fn [j text]
                                   {:op :add :old nil :new j :text text})
                                 b))}
      {:coarse? false
       :ops (lcs-ops a b)})))

(defn- ops-for
  [old-lines new-lines]
  (let [prefix (common-prefix old-lines new-lines)
        suffix (common-suffix old-lines new-lines prefix)
        old-middle (subvec old-lines prefix (- (count old-lines) suffix))
        new-middle (subvec new-lines prefix (- (count new-lines) suffix))
        middle (middle-ops old-middle new-middle)
        prefix-ops (map-indexed
                    (fn [i text]
                      {:op :equal
                       :text text
                       :old-line (inc i)
                       :new-line (inc i)})
                    (subvec old-lines 0 prefix))
        middle-ops (map (fn [op]
                          (assoc op
                                 :old-line (when (some? (:old op))
                                             (+ prefix (:old op) 1))
                                 :new-line (when (some? (:new op))
                                             (+ prefix (:new op) 1))))
                        (:ops middle))
        suffix-ops (map-indexed
                    (fn [n text]
                      {:op :equal
                       :text text
                       :old-line (+ (- (count old-lines) suffix) n 1)
                       :new-line (+ (- (count new-lines) suffix) n 1)})
                    (subvec old-lines (- (count old-lines) suffix)))]
    {:ops (vec (concat prefix-ops middle-ops suffix-ops))
     :coarse? (:coarse? middle)}))

(defn- hunk-ranges
  [ops]
  (let [total (count ops)
        changed (keep-indexed (fn [index op]
                                (when (not= :equal (:op op)) index))
                              ops)]
    (loop [indexes changed
           ranges []]
      (if-let [index (first indexes)]
        (let [start (max 0 (- index context-lines))
              end (min total (+ index context-lines 1))
              previous (peek ranges)]
          (if (and previous (<= start (:end previous)))
            (recur (rest indexes)
                   (conj (pop ranges)
                         (assoc previous :end (max end (:end previous)))))
            (recur (rest indexes)
                   (conj ranges {:start start :end end}))))
        ranges))))

(defn- hunk-of
  [ops {:keys [start end]}]
  (let [lines (subvec ops start end)
        old-count (count (filter #(contains? #{:equal :remove} (:op %)) lines))
        new-count (count (filter #(contains? #{:equal :add} (:op %)) lines))
        old-start (or (some :old-line lines) 0)
        new-start (or (some :new-line lines) 0)]
    {:header {:old-start old-start
              :old-count old-count
              :new-start new-start
              :new-count new-count}
     :lines lines}))

(defn diff-text
  "Compare OLD-TEXT with NEW-TEXT and return a displayable diff.

  Result keys: `:changed?`, `:coarse?`, `:too-large?`, `:summary`
  `{:added :removed}`, `:hunks`, `:old-lines`, `:new-lines`."
  [old-text new-text]
  (let [old-lines (split-lines old-text)
        new-lines (split-lines new-text)
        {:keys [ops coarse?]} (ops-for old-lines new-lines)
        added (count (filter #(= :add (:op %)) ops))
        removed (count (filter #(= :remove (:op %)) ops))
        changed? (pos? (+ added removed))
        ranges (when changed? (hunk-ranges ops))
        hunks (mapv #(hunk-of ops %) ranges)
        rendered (reduce + 0 (map (comp count :lines) hunks))]
    {:changed? changed?
     :coarse? (boolean coarse?)
     :too-large? (> rendered max-display-lines)
     :summary {:added added :removed removed}
     :hunks (if (> rendered max-display-lines) [] hunks)
     :old-lines (count old-lines)
     :new-lines (count new-lines)}))
