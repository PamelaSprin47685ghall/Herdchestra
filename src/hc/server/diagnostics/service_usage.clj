(ns hc.server.diagnostics.service-usage
  "Provider-reported non-token usage records.

  These helpers only accept a quantity that the provider actually reported.
  They never derive a quantity from local bytes, elapsed time, text length,
  result count, or the number of requests sent.  When a provider omits usage,
  the extractor returns nil and the caller records nothing; statistics then
  shows the unknown value, never zero."
  (:require [clojure.string :as str]
            [hc.server.diagnostics.pricing :as pricing]))

(def quantity-sources
  #{:provider-reported :estimated :unknown})

(defn- invalid!
  [reason message data]
  (throw (ex-info message
                  (merge {:error/kind :invalid-service-usage
                          :reason reason}
                         data))))

(defn provider-reported
  "Build one validated service-usage record from explicit provider facts.

  Required: `:usage-id`, `:unit`, `:quantity`, and `:quantity-source`.
  Automatic provider hooks must use `:quantity-source :provider-reported`.
  `:price` and `:billed` are optional; an absent price stays unestimated."
  [{:keys [usage-id unit quantity quantity-source] :as usage}]
  (when (str/blank? (str usage-id))
    (invalid! :usage-id-missing
              "a service usage needs a usage-id"
              {:usage usage}))
  (when-not (contains? pricing/service-units unit)
    (invalid! :unit-invalid
              "a service usage needs a known unit"
              {:unit unit}))
  (when-not (contains? quantity-sources quantity-source)
    (invalid! :quantity-source-invalid
              "quantity source must be :provider-reported, :estimated, or :unknown"
              {:quantity-source quantity-source}))
  (when-not (or (= :unknown quantity)
                (and (number? quantity) (not (neg? (bigdec quantity)))))
    (invalid! :quantity-invalid
              "quantity must be a non-negative number or :unknown"
              {:quantity quantity}))
  (when (and (= :provider-reported quantity-source)
             (not (number? quantity)))
    (invalid! :provider-quantity-missing
              "provider-reported usage needs a numeric quantity"
              {:quantity quantity}))
  usage)

(defn stt-duration
  "Extract an explicit speech-to-text duration usage from a provider response.

  Accepts a raw response map with `:usage` containing `:seconds` or
  `:duration`, or a top-level `:duration` only when `:usage` marks it as a
  billed duration.  Returns nil when the provider did not report usage.
  Local recording duration must not be passed here."
  [response meta]
  (let [usage (:usage response)
        seconds (cond
                  (and (map? usage) (number? (:seconds usage))) (:seconds usage)
                  (and (map? usage) (number? (:duration usage))) (:duration usage)
                  (and (map? usage)
                       (= :duration (:type usage))
                       (number? (:value usage))) (:value usage)
                  :else nil)]
    (when (and (number? seconds) (not (neg? (bigdec seconds))))
      (provider-reported
       (assoc meta
              :unit :per-second
              :quantity seconds
              :quantity-source :provider-reported)))))

(defn search-credits
  "Extract explicit search credits from a provider response.

  Accepts `:usage {:credits n}` or a top-level `:credits` number.  Returns nil
  when the provider did not report credits."
  [response meta]
  (let [credits (or (get-in response [:usage :credits])
                    (when (number? (:credits response)) (:credits response)))]
    (when (and (number? credits) (not (neg? (bigdec credits))))
      (provider-reported
       (assoc meta
              :unit :per-credit
              :quantity credits
              :quantity-source :provider-reported)))))

(defn explicit-quantity
  "Extract an explicit unit/quantity pair from a provider response.

  The caller names the provider field and unit.  A missing field returns nil.
  This is the only generic escape hatch; it still does not infer anything."
  [response field unit meta]
  (let [quantity (get-in response field)]
    (when (and (number? quantity) (not (neg? (bigdec quantity))))
      (provider-reported
       (assoc meta
              :unit unit
              :quantity quantity
              :quantity-source :provider-reported)))))
