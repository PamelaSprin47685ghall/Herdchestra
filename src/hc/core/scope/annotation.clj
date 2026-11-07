(ns hc.core.scope.annotation
  "Parse one raw `:scope` annotation into a normalized record or a refusal.

  The parser is total for model input. It enforces the exact fields of each
  kind. It never accepts an alias or an unknown field."
  (:require [clojure.string :as string]
            [hc.core.scope.errors :as errors]))

(defn- non-blank-string? [value]
  (and (string? value) (not (string/blank? value))))

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn- unexpected-field
  "Return a refusal for the first field that `kind` does not accept."
  [kind raw allowed]
  (when-let [field (->> (keys raw)
                        (remove allowed)
                        (sort-by pr-str)
                        first)]
    (errors/refuse errors/field-unexpected
                   (str "scope." (name kind) " does not accept " (pr-str field))
                   {:kind kind :field field})))

(defn- parse-reset [raw]
  (or (unexpected-field :reset-scope raw
                        #{:kind :new-scope-goal :new-scope-retention-budget})
      (let [goal (:new-scope-goal raw)
            budget (:new-scope-retention-budget raw)]
        (cond
          (not (non-blank-string? goal))
          (errors/refuse errors/field-invalid
                         "new-scope-goal must be a non-blank string"
                         {:kind :reset-scope :field :new-scope-goal})

          (not (positive-integer? budget))
          (errors/refuse errors/field-invalid
                         "new-scope-retention-budget must be a positive integer"
                         {:kind :reset-scope :field :new-scope-retention-budget})

          :else
          {:kind :reset-scope :goal goal :budget budget}))))

(defn- parse-keep [raw]
  (or (unexpected-field :keep-last raw #{:kind})
      {:kind :keep-last}))

(defn- parse-remove [raw]
  (or (unexpected-field :remove-last raw #{:kind :replacement})
      (let [replacement (:replacement raw)]
        (if (non-blank-string? replacement)
          {:kind :remove-last :replacement replacement}
          (errors/refuse errors/field-invalid
                         "replacement must be a non-blank string"
                         {:kind :remove-last :field :replacement})))))

(defn- parse-me-too [raw]
  (or (unexpected-field :me-too raw #{:kind})
      {:kind :me-too}))

(defn parse-annotation
  "Return a normalized annotation record, or a refusal value."
  [raw]
  (cond
    (nil? raw)
    (errors/refuse errors/annotation-missing
                   "the tool call has no :scope annotation; the tool did not run"
                   {:field :scope
                    :expected {:kind ["reset-scope" "keep-last"
                                      "remove-last" "me-too"]}
                    :hint "Add :scope {:kind ...} to this tool call and retry."})

    (not (map? raw))
    (errors/refuse errors/annotation-missing
                   "the :scope annotation must be a flat map with :kind"
                   {:field :scope :actual (some-> raw class str)
                    :expected {:kind ["reset-scope" "keep-last"
                                      "remove-last" "me-too"]}})

    :else
    (let [kind (:kind raw)]
      (case kind
        :reset-scope (parse-reset raw)
        :keep-last (parse-keep raw)
        :remove-last (parse-remove raw)
        :me-too (parse-me-too raw)
        (errors/refuse errors/kind-invalid
                       (str "unknown scope kind: " (pr-str kind))
                       {:kind kind})))))

(defn reset? [annotation]
  (= :reset-scope (:kind annotation)))

(defn keep? [annotation]
  (= :keep-last (:kind annotation)))

(defn remove? [annotation]
  (= :remove-last (:kind annotation)))

(defn me-too? [annotation]
  (= :me-too (:kind annotation)))

(defn true-annotation?
  "True for the one annotation of a turn that can book a beat."
  [annotation]
  (contains? errors/true-kinds (:kind annotation)))
