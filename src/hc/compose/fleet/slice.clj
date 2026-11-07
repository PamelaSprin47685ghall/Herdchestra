(ns hc.compose.fleet.slice
  "Slice admission for the independent fork/join fleet.

  Disjointness is a work-assignment property, not a path property.  The
  Steward chooses slices that do not overlap in goal; the runtime gives each
  slice its own worktree and does not inspect `:paths` or police writes.  This
  namespace therefore validates only what the protocol can decide: unique
  slice ids, known dependencies, no self-dependency, and an acyclic
  dependency graph.  It never claims that two goals are disjoint."
  (:require [clojure.string :as str]))

(def required-keys
  "The slice shape named by AGENTS.md §2.2/§5.3."
  [:slice-id :goal :inputs :depends-on :done-when])

(defn- fail
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- slice-id?
  [x]
  (or (and (string? x) (not (str/blank? x)))
      (keyword? x)))

(defn- normalize-depends-on
  [slice]
  (let [deps (:depends-on slice)]
    (cond
      (nil? deps) []
      (sequential? deps) (vec deps)
      :else ::invalid)))

(defn- normalize-slice
  [slice]
  (assoc slice :depends-on (normalize-depends-on slice)))

(defn- find-cycle
  "Return one cycle path, with the repeated id at both ends, or nil."
  [by-id]
  (let [state (atom {:colors {} :path []})]
    (letfn [(visit [id]
              (let [{:keys [colors path]} @state
                    color (get colors id)]
                (cond
                  (= :gray color)
                  (let [idx (first (keep-indexed (fn [i x] (when (= x id) i))
                                                 path))]
                    (conj (subvec (vec path) idx) id))

                  (= :black color)
                  nil

                  :else
                  (do
                    (swap! state assoc-in [:colors id] :gray)
                    (swap! state update :path conj id)
                    (let [found (some visit (:depends-on (get by-id id)))]
                      (swap! state update :path pop)
                      (swap! state assoc-in [:colors id] :black)
                      found)))))]
      (some visit (keys by-id)))))

(defn validate-slices
  "Validate SLICES for one fork.

  EXISTING-IDS are slice ids already admitted by the fleet.  Dependencies may
  point at existing slices, but every dependency must be known.  The function
  intentionally does not compare `:inputs`, `:goal`, or `:paths`: disjointness
  is the caller's assignment decision and the worktree is the boundary."
  ([slices]
   (validate-slices slices #{}))
  ([slices existing-ids]
   (cond
     (not (sequential? slices))
     (fail :INVALID_SLICES "slices must be a sequential collection")

     (empty? slices)
     (fail :EMPTY_SLICES "fork requires at least one slice")

     :else
     (let [existing (set existing-ids)]
       (loop [remaining (seq slices)
              seen #{}
              normalized []]
         (if-let [slice (first remaining)]
           (cond
             (not (map? slice))
             (fail :INVALID_SLICE "each slice must be a map"
                   {:slice slice})

             (not-every? #(contains? slice %) required-keys)
             (fail :MISSING_SLICE_FIELD
                   "slice is missing a required field"
                   {:slice slice
                    :missing (vec (remove #(contains? slice %) required-keys))})

             (not (slice-id? (:slice-id slice)))
             (fail :INVALID_SLICE_ID
                   "slice-id must be a non-blank string or a keyword"
                   {:slice-id (:slice-id slice)})

             (contains? seen (:slice-id slice))
             (fail :DUPLICATE_SLICE_ID
                   "slice-id is repeated in this fork"
                   {:slice-id (:slice-id slice)})

             (contains? existing (:slice-id slice))
             (fail :DUPLICATE_SLICE_ID
                   "slice-id was already admitted by this fleet"
                   {:slice-id (:slice-id slice)})

             (not (and (string? (:goal slice))
                       (not (str/blank? (:goal slice)))))
             (fail :INVALID_SLICE_GOAL
                   "goal must be a non-blank string"
                   {:slice-id (:slice-id slice) :goal (:goal slice)})

             (not (contains? slice :inputs))
             (fail :MISSING_SLICE_FIELD
                   "slice is missing :inputs"
                   {:slice-id (:slice-id slice)})

             (not (and (string? (:done-when slice))
                       (not (str/blank? (:done-when slice)))))
             (fail :INVALID_DONE_WHEN
                   "done-when must be a non-blank string"
                   {:slice-id (:slice-id slice) :done-when (:done-when slice)})

             (= ::invalid (normalize-depends-on slice))
             (fail :INVALID_DEPENDS_ON
                   "depends-on must be a sequential collection"
                   {:slice-id (:slice-id slice) :depends-on (:depends-on slice)})

             (not-every? slice-id? (:depends-on slice))
             (fail :INVALID_DEPENDENCY_ID
                   "every dependency must be a non-blank string or a keyword"
                   {:slice-id (:slice-id slice) :depends-on (:depends-on slice)})

             (some #(= (:slice-id slice) %) (:depends-on slice))
             (fail :SELF_DEPENDENCY
                   "a slice cannot depend on itself"
                   {:slice-id (:slice-id slice)})

             :else
             (recur (next remaining)
                    (conj seen (:slice-id slice))
                    (conj normalized (normalize-slice slice))))

           (let [by-id (into {} (map (juxt :slice-id identity) normalized))
                 unknown (for [slice normalized
                               dep (:depends-on slice)
                               :when (not (or (contains? by-id dep)
                                              (contains? existing dep)))]
                           {:slice-id (:slice-id slice) :dependency dep})]
             (if (seq unknown)
               (fail :UNKNOWN_DEPENDENCY
                     "a slice depends on an id that is not in this fork or fleet"
                     {:dependencies (vec unknown)})
               (if-let [cycle (find-cycle by-id)]
                 (fail :SLICE_DEPENDENCY_CYCLE
                       "slice dependencies must be acyclic"
                       {:cycle cycle})
                 {:ok? true :slices normalized})))))))))


