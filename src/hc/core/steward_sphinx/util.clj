(ns hc.core.steward-sphinx.util
  "Small total functions shared by the stewardship core.

  These helpers never throw for model input.  They return ordinary values;
  callers decide whether a refusal is a value or a wiring error."
  (:require [clojure.string :as str]))

(defn non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn non-negative-integer?
  [value]
  (and (integer? value) (not (neg? value))))

(defn positive-integer?
  [value]
  (and (integer? value) (pos? value)))

(defn finite-number?
  "True for a finite number: not NaN and not infinite."
  [value]
  (and (number? value)
       (let [value (double value)]
         (and (not (Double/isNaN value))
              (not (Double/isInfinite value))))))

(defn unit-number?
  "True for a number in the closed interval [0, 1]."
  [value]
  (and (finite-number? value) (<= 0 value 1)))

(defn positive-number?
  [value]
  (and (finite-number? value) (pos? value)))

(defn sorted-distinct
  [values]
  (vec (sort (distinct values))))

(defn error
  ([code message]
   (error code message nil))
  ([code message details]
   (cond-> {:code code :message message}
     (some? details) (assoc :details details))))

(defn fail
  ([code message]
   (fail code message nil))
  ([code message details]
   {:ok? false :error (error code message details)}))

(defn fail-state
  ([state code message]
   (fail-state state code message nil))
  ([state code message details]
   {:ok? false
    :state state
    :error (error code message details)}))

(defn normalize-all
  "Apply F to every item, stopping at the first refusal.

  F returns `{:ok? true :value v}` or `{:ok? false :error e}`.  The successful
  result is `{:ok? true :values [v ...]}`."
  [f values]
  (loop [remaining (seq values)
         acc []]
    (if-let [value (first remaining)]
      (let [result (f value)]
        (if (:ok? result)
          (recur (next remaining) (conj acc (:value result)))
          result))
      {:ok? true :values acc})))
