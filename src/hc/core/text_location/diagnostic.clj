(ns hc.core.text-location.diagnostic
  "Structured zero-match and multiple-match payloads for `where`."
  (:require [hc.core.text-location.nearest :as nearest]
            [hc.core.text-location.scan :as scan]))

(def max-candidates 8)

(defn- invalid-option!
  [field value expected]
  (throw (ex-info (str "text-location: " field " must be " expected)
                  {:status :INVALID_ARGUMENT
                   :error :INVALID_ARGUMENT
                   :field field
                   :expected expected
                   :actual (some-> value class .getName)})))

(defn- option
  [opts field default upper]
  (let [value (get opts field default)]
    (when-not (and (integer? value)
                   (not (neg? value))
                   (<= value upper))
      (invalid-option! field value (str "a non-negative integer <= " upper)))
    (long value)))

(defn normalize-options
  "Validate diagnostic bounds.  Defaults are the §4.3 values."
  [opts]
  (when-not (or (nil? opts) (map? opts))
    (throw (ex-info "text-location: :options must be a map"
                    {:status :INVALID_ARGUMENT
                     :error :INVALID_ARGUMENT
                     :field :options
                     :expected "a map"
                     :actual (some-> opts class .getName)})))
  {:max-distance (option opts :max-distance nearest/default-max-distance
                         nearest/max-band)
   :max-lines (option opts :max-lines nearest/default-max-lines Long/MAX_VALUE)
   :dp-budget (option opts :dp-budget nearest/default-dp-budget Long/MAX_VALUE)})

(defn zero-payload
  "Build the §4.3 payload for a unique-anchor miss.

  `opts` must already be normalized by `normalize-options`."
  [^String content ^String needle opts]
  (let [{:keys [nearest budget-exhausted?]} (nearest/nearest-line content needle opts)]
    {:needle needle
     :candidates []
     :nearest nearest
     :max-distance (long (:max-distance opts))
     :budget-exhausted (boolean budget-exhausted?)}))

(defn ambiguous-payload
  "Build the §4.3 payload for two or more exact matches.

  List at most eight candidates in content order, including overlapping
  occurrences.  Each context holds up to 80 UTF-16 code units before and
  after the match."
  [^String content ^String needle spans]
  {:needle needle
   :candidates (->> spans
                    (take max-candidates)
                    (mapv (fn [[b e]]
                            {:span [(int b) (int e)]
                             :context (scan/context content b e)})))})

(defn locate
  "Classify a request as `:unique`, `:none`, or `:ambiguous`.

  Return `{:kind :unique :span [b e]}`, `{:kind :none :payload {...}}`, or
  `{:kind :ambiguous :payload {...}}`.  `opts` overrides diagnostic bounds
  for tests and embedding; the tool surface uses the defaults.  Uniqueness
  counts every literal occurrence, including overlapping ones."
  ([content needle] (locate content needle {}))
  ([^String content ^String needle opts]
   (let [opts (normalize-options opts)
         spans (scan/find-up-to-overlapping content needle (inc max-candidates))]
     (cond
       (empty? spans)
       {:kind :none :payload (zero-payload content needle opts)}

       (= 1 (count spans))
       {:kind :unique :span (first spans)}

       :else
       {:kind :ambiguous :payload (ambiguous-payload content needle spans)}))))

(defn park-signal?
  "True when `where` returned a zero-match or multiple-match park signal."
  [value]
  (and (map? value)
       (= :parked (:state value))
       (= :where-ambiguous (:reason value))
       (contains? value :payload)))

(defn where
  "Return the unique `[begin end]` span, or a park signal.

  A needle is unique only when every literal occurrence is the same span, so
  overlapping occurrences count.  A zero-match or multiple-match result parks
  with reason `:where-ambiguous`.  The runtime adds `:name`, `:source`, and
  the other receipt fields; this core supplies the `:payload`."
  [^String content ^String needle]
  (let [result (locate content needle)]
    (if (= :unique (:kind result))
      (:span result)
      {:state :parked
       :reason :where-ambiguous
       :payload (:payload result)})))

(defn whereAll
  "Return every non-overlapping exact match.  Never parks."
  [^String content ^String needle]
  (scan/find-all content needle))
