(ns hc.core.scope.admission
  "The admission rule. It runs before a tool executes.

  An absent annotation refuses every non-exempt tool. A spent or unknown
  budget refuses every non-exempt tool that does not reset. Exempt members
  skip both rules and are never refused for a spent budget."
  (:require [clojure.string :as string]
            [hc.core.scope.annotation :as annotation]
            [hc.core.scope.errors :as errors]))

(defn exempt-set
  "The effective exempt member names for an admission map."
  [admission]
  (into (set errors/default-exempt-members)
        (keep errors/member-name)
        (:exempt admission)))

(defn exempt?
  "True when `member` is exempt. A nil admission map means the defaults."
  ([member] (exempt? member nil))
  ([member admission]
   (let [name (errors/member-name member)]
     (and (some? name)
          (not (string/blank? name))
          (contains? (exempt-set admission) name)))))

(defn admission
  "Build an admission set. The exempt members are the two Scope members."
  ([] (admission {}))
  ([{:keys [exempt]}]
   {:exempt (exempt-set {:exempt exempt})}))

(defn check
  "Return `{:ok true :exempt bool :annotation parsed}` or a refusal.

  `retention` is `{:established? bool :remaining int}`. A missing or nil
  retention map is treated as uninitialized. Refusals carry
  `:phase :admission`: they happen before the tool runs."
  [{:keys [member annotation retention admission]}]
  (let [member-name (errors/member-name member)
        result
        (cond
          (or (nil? member-name)
              (string/blank? member-name))
          (errors/refuse errors/annotation-missing
                         "the tool call has no member name")

          (exempt? member-name admission)
          {:ok true :exempt true :annotation annotation}

          (nil? annotation)
          (errors/refuse errors/annotation-missing
                         (str "tool " member-name
                              " needs a flat :scope annotation; the tool did not run")
                         {:member member-name
                          :field :scope
                          :expected
                          {:kind ["reset-scope" "keep-last" "remove-last" "me-too"]
                           :reset-scope
                           {:new-scope-goal "non-empty string"
                            :new-scope-retention-budget "positive integer"}
                           :remove-last {:replacement "non-empty string"}
                           :keep-last "no other fields"
                           :me-too "no other fields"}
                          :hint "Add :scope {:kind ...} to this tool call and retry."})

          :else
          (let [parsed (annotation/parse-annotation annotation)]
            (if (false? (:ok parsed))
              parsed
              (let [{:keys [established? remaining]}
                    (or retention {:established? false :remaining 0})
                    remaining (or remaining 0)]
                (cond
                  (and (not established?)
                       (not= :reset-scope (:kind parsed)))
                  (errors/refuse errors/uninitialized
                                 (str "the budget is not established; tool "
                                      member-name " must reset")
                                 {:member member-name :kind (:kind parsed)})

                  (and established?
                       (zero? remaining)
                       (not= :reset-scope (:kind parsed)))
                  (errors/refuse errors/budget-spent
                                 (str "the budget is spent; tool "
                                      member-name " must reset")
                                 {:member member-name :kind (:kind parsed)})

                  :else
                  {:ok true :exempt false :annotation parsed})))))]
    (if (false? (:ok result))
      (assoc result :phase :admission)
      result)))
