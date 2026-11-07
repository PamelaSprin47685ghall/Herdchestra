(ns hc.server.diagnostics.pricing
  "Deterministic per-request price records and cost estimates.

  Prices are user-supplied or service-supplied facts, never a bundled
  \"latest price\" table.  A rate is quoted per 1,000,000 tokens; the unit is
  part of the record so a per-1,000 price cannot be mistaken for a
  per-1,000,000 price.  A missing rate is nil, never zero.

  A cost estimate has one of three statuses:

    :complete      every billable component was known
    :partial       some components were known; `:amount` covers only those
    :unestimated   no component could be priced; `:amount` is nil

  Cache tokens follow the usage record's declared mode.  In `:input-subset`
  mode cache-read and cache-write tokens are already inside `:input-tokens`,
  so only the uncached remainder pays the input rate.  In
  `:additional-input` mode they are separate input-side quantities and each
  pays its own rate.  A cache quantity is never added to a total twice.

  All amounts are BigDecimal values rounded to six decimal places.  Different
  currencies are never added together; callers group by `:currency`."
  (:require [clojure.string :as str])
  (:import [java.math BigDecimal RoundingMode]))

(def tokens-per-unit
  "Rates are quoted for this many tokens."
  1000000)

(def money-scale
  "Decimal places kept for a displayed amount."
  6)

(def price-fields
  [:input :output :cache-read :cache-write])

(def price-units
  #{:per-million-tokens})

(def service-units
  #{:per-image :per-second :per-call :per-request :per-item
    :per-character :per-megabyte :per-credit})

(defn- invalid!
  [reason message data]
  (throw (ex-info message
                  (merge {:error/kind :invalid-price
                          :reason reason}
                         data))))

(defn- decimal
  "Coerce a non-negative rate or amount to BigDecimal, or nil when unknown."
  [field value]
  (cond
    (nil? value) nil
    (= :unknown value) nil
    (instance? BigDecimal value)
    (if (neg? (.signum ^BigDecimal value))
      (invalid! :negative
                "price and amount values must be non-negative"
                {:field field :value value})
      value)

    (number? value)
    (let [number (try
                   (bigdec value)
                   (catch Exception _
                     (invalid! :number-invalid
                               "price and amount values must be finite numbers"
                               {:field field :value value})))]
      (if (neg? (.signum ^BigDecimal number))
        (invalid! :negative
                  "price and amount values must be non-negative"
                  {:field field :value value})
        number))

    :else
    (invalid! :number-invalid
              "price and amount values must be finite numbers"
              {:field field :value value})))

(defn- text-or-nil
  [value]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    (string? value) value
    :else (str value)))

(defn price
  "Normalize one price record.

  `:currency` is required and non-blank.  `:unit` is fixed at
  `:per-million-tokens`.  `:input`, `:output`, `:cache-read`, and
  `:cache-write` are non-negative BigDecimal rates or nil when unknown.
  `:source` and `:source-date` are display facts and never inferred."
  [raw]
  (let [raw (or raw {})
        currency (some-> (:currency raw) text-or-nil str/trim str/upper-case)
        unit (or (:unit raw) :per-million-tokens)
        source (some-> (:source raw) text-or-nil)
        source-date (some-> (:source-date raw) text-or-nil)]
    (when (str/blank? currency)
      (invalid! :currency-missing
                "a price needs a non-blank currency"
                {:currency (:currency raw)}))
    (when-not (contains? price-units unit)
      (invalid! :unit-invalid
                "prices are quoted per 1,000,000 tokens"
                {:unit unit}))
    (into {:currency currency
           :unit unit
           :source source
           :source-date source-date}
          (map (fn [field]
                 [field (decimal field (get raw field))]))
          price-fields)))

(defn catalog
  "Build a price catalog keyed by `[service-id model]`.

  The service id is part of the key so two connections that expose the same
  model name never share a price by accident."
  [prices]
  {:prices (into {}
                 (map (fn [[[service-id model] raw-price]]
                        [[service-id model] (price raw-price)]))
                 prices)})

(defn lookup
  "Look up a price for SERVICE-ID and MODEL, or nil."
  [catalog service-id model]
  (get-in catalog [:prices [service-id model]]))

(defn with-price
  "Associate one normalized price with `[service-id model]`."
  [catalog service-id model raw-price]
  (assoc-in catalog [:prices [service-id model]] (price raw-price)))

(defn- round-money
  [^BigDecimal amount]
  (.setScale amount (int money-scale) RoundingMode/HALF_UP))

(defn- known-token?
  [value]
  (and (integer? value) (not (neg? value))))

(defn- component
  "Cost of TOKENS at RATE, or :unknown.

  Zero tokens cost zero even when the rate is unknown: no usage exists to
  price.  A positive quantity with an unknown rate stays unknown, never zero."
  [tokens rate]
  (cond
    (and (known-token? tokens) (zero? tokens)) 0M
    (not (known-token? tokens)) :unknown
    (nil? rate) :unknown
    :else (round-money
           (.divide (.multiply (bigdec tokens) ^BigDecimal rate)
                    (BigDecimal/valueOf (long tokens-per-unit))
                    (int money-scale)
                    RoundingMode/HALF_UP))))

(defn- sum-known
  [components]
  (let [known (filter #(not= :unknown %) (vals components))]
    (if (seq known)
      (reduce + 0M known)
      :unknown)))

(defn- status-of
  [components]
  (let [known (filter #(not= :unknown %) (vals components))]
    (cond
      (empty? known) :unestimated
      (= (count known) (count components)) :complete
      :else :partial)))

(defn- missing-reasons
  [components reasons]
  (into []
        (keep (fn [field]
                (when (= :unknown (get components field))
                  (get reasons field))))
        [:uncached-input :cache-read :cache-write :output]))

(defn- estimate-input-subset
  [p usage]
  (let [input (:input-tokens usage)
        output (:output-tokens usage)
        cache-read (:cache-read-tokens usage)
        cache-write (:cache-write-tokens usage)
        input-known? (known-token? input)
        read-known? (known-token? cache-read)
        write-known? (known-token? cache-write)
        breakdown-known? (and input-known? read-known? write-known?)
        subset? (and breakdown-known?
                     (<= (+ cache-read cache-write) input))
        uncached (when subset? (- input cache-read cache-write))
        components {:uncached-input (if subset?
                                      (component uncached (:input p))
                                      :unknown)
                    :cache-read (if read-known?
                                  (component cache-read (:cache-read p))
                                  :unknown)
                    :cache-write (if write-known?
                                   (component cache-write (:cache-write p))
                                   :unknown)
                    :output (if (known-token? output)
                              (component output (:output p))
                              :unknown)}
        reasons {:uncached-input (cond
                                   (not input-known?) :input-tokens
                                   (not breakdown-known?) :cache-breakdown
                                   (not subset?) :cache-breakdown
                                   (nil? (:input p)) :input-price
                                   :else :input-tokens)
                 :cache-read (cond
                               (not read-known?) :cache-read-tokens
                               (nil? (:cache-read p)) :cache-read-price
                               :else :cache-read-tokens)
                 :cache-write (cond
                                (not write-known?) :cache-write-tokens
                                (nil? (:cache-write p)) :cache-write-price
                                :else :cache-write-tokens)
                 :output (cond
                           (not (known-token? output)) :output-tokens
                           (nil? (:output p)) :output-price
                           :else :output-tokens)}]
    {:components components
     :missing (missing-reasons components reasons)}))

(defn- estimate-additional-input
  [p usage]
  (let [input (:input-tokens usage)
        output (:output-tokens usage)
        cache-read (:cache-read-tokens usage)
        cache-write (:cache-write-tokens usage)
        components {:uncached-input (component input (:input p))
                    :cache-read (component cache-read (:cache-read p))
                    :cache-write (component cache-write (:cache-write p))
                    :output (component output (:output p))}
        reasons {:uncached-input (if (known-token? input)
                                   :input-price
                                   :input-tokens)
                 :cache-read (if (known-token? cache-read)
                               :cache-read-price
                               :cache-read-tokens)
                 :cache-write (if (known-token? cache-write)
                                :cache-write-price
                                :cache-write-tokens)
                 :output (if (known-token? output)
                           :output-price
                           :output-tokens)}]
    {:components components
     :missing (missing-reasons components reasons)}))

(defn estimate
  "Estimate one request's cost from PRICE and a usage summary.

  USAGE uses `:input-tokens`, `:output-tokens`, `:cache-read-tokens`,
  `:cache-write-tokens`, and `:cache-mode`.  Any token field may be
  `:unknown`.  The result never invents a zero for missing usage or a missing
  rate.  `:amount` is the sum of known components only; `:missing` names why a
  component could not be priced."
  [raw-price usage]
  (let [p (price raw-price)
        usage (or usage {})
        mode (or (:cache-mode usage) :input-subset)
        {:keys [components missing]}
        (case mode
          :input-subset (estimate-input-subset p usage)
          :additional-input (estimate-additional-input p usage)
          (invalid! :cache-mode-invalid
                    "cache mode must be :input-subset or :additional-input"
                    {:cache-mode mode}))
        amount (sum-known components)]
    {:status (status-of components)
     :currency (:currency p)
     :unit (:unit p)
     :amount (when (not= :unknown amount) (round-money amount))
     :components components
     :missing (vec (distinct missing))
     :cache-mode mode
     :price-source (:source p)
     :price-source-date (:source-date p)}))

(defn unit-preview
  "Show what PRICE means with a fixed one-million-token example.

  The example makes a per-million rate visible: a rate of 3 costs 3.000000
  for 1,000,000 tokens, not 3,000.  The preview is deterministic and never
  calls a provider."
  [raw-price]
  (let [example {:input-tokens 1000000
                 :output-tokens 1000000
                 :cache-read-tokens 0
                 :cache-write-tokens 0
                 :cache-mode :input-subset}]
    {:example example
     :estimate (estimate raw-price example)
     :note (str "Rates are per " tokens-per-unit
                " tokens; this example uses "
                "1,000,000 input and 1,000,000 output tokens.")}))

(defn billed
  "Normalize a service-reported actual charge.

  A service-reported amount is not an estimate.  It is kept separate so a
  statistics view can show both without adding them together."
  [raw]
  (when raw
    (let [currency (some-> (:currency raw) text-or-nil str/trim str/upper-case)
          amount (decimal :amount (:amount raw))
          source (or (some-> (:source raw) text-or-nil) "service-reported")]
      (when (str/blank? currency)
        (invalid! :currency-missing
                  "a billed amount needs a non-blank currency"
                  {:currency (:currency raw)}))
      (when (nil? amount)
        (invalid! :amount-missing
                  "a billed amount needs a known amount"
                  {:amount (:amount raw)}))
      {:amount (round-money amount)
       :currency currency
       :source source})))

(defn service-price
  "Normalize a non-token service price.

  `:unit` is one of `service-units`; `:rate` is the non-negative price for
  one unit.  A missing rate stays nil and is never treated as free."
  [raw]
  (let [raw (or raw {})
        currency (some-> (:currency raw) text-or-nil str/trim str/upper-case)
        unit (:unit raw)
        rate (decimal :rate (:rate raw))
        source (some-> (:source raw) text-or-nil)
        source-date (some-> (:source-date raw) text-or-nil)]
    (when (str/blank? currency)
      (invalid! :currency-missing
                "a service price needs a non-blank currency"
                {:currency (:currency raw)}))
    (when-not (contains? service-units unit)
      (invalid! :unit-invalid
                "service unit must be one of :per-image, :per-second, :per-call, :per-request, :per-item, :per-character, :per-megabyte, or :per-credit"
                {:unit unit}))
    {:currency currency
     :unit unit
     :rate rate
     :source source
     :source-date source-date}))

(defn estimate-service
  "Estimate a non-token service charge from PRICE and QUANTITY.

  The amount is quantity x rate in the price's own unit.  A missing quantity
  or rate is `:unestimated`, never zero."
  [raw-price quantity]
  (let [price (service-price raw-price)
        quantity (cond
                   (nil? quantity) :unknown
                   (= :unknown quantity) :unknown
                   (and (number? quantity)
                        (not (neg? (bigdec quantity)))) (bigdec quantity)
                   :else
                   (invalid! :quantity-invalid
                             "service quantity must be a non-negative number or :unknown"
                             {:quantity quantity}))
        rate (:rate price)
        known? (and (not= :unknown quantity) (some? rate))
        amount (when known?
                 (round-money (.multiply ^BigDecimal quantity
                                         ^BigDecimal rate)))
        missing (cond-> []
                  (= :unknown quantity) (conj :quantity)
                  (nil? rate) (conj :rate))]
    {:status (if known? :complete :unestimated)
     :currency (:currency price)
     :unit (:unit price)
     :quantity quantity
     :rate rate
     :amount amount
     :missing missing
     :price-source (:source price)
     :price-source-date (:source-date price)}))

(defn service-unit-preview
  "Show what a service price means for one unit."
  [raw-price]
  (let [estimate (estimate-service raw-price 1)]
    {:example {:quantity 1}
     :estimate estimate
     :note (str "One unit at " (name (:unit estimate)) " costs the stated rate.")}))
