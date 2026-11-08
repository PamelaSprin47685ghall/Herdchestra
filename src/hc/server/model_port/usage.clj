(ns hc.server.model-port.usage
  "Per-attempt usage, finish reason, and reasoning records.

  Usage fields are either a non-negative integer or `:unknown`.  A provider
  that reports nothing is unknown, never zero.  Streaming partial reports and
  the final complete report merge by coverage, not by addition: the final
  value replaces the partial value for the same attempt, so retries and
  duplicated callbacks cannot inflate the cost.

  Every attempt stays in the ledger, including failed and cancelled attempts.
  The aggregate sums each attempt once and reports a field as `:unknown` when
  any attempt leaves that field unknown.  Reasoning text is kept as given;
  opaque continuity data is marked non-editable and never treated as prose.")

(def token-fields
  [:input-tokens :output-tokens :cache-read-tokens :cache-write-tokens])

(def coverages #{:partial :final})
(def sources #{:reported :estimated :unknown})
(def terminal-statuses #{:succeeded :failed :cancelled})

(defn- invalid! [reason message data]
  (throw (ex-info message
                  (merge {:error/kind :invalid-usage
                          :reason reason}
                         data))))

(defn- token-value
  [value]
  (cond
    (nil? value) :unknown
    (= :unknown value) :unknown
    (and (integer? value) (not (neg? value))) value
    :else
    (invalid! :token-invalid
              "token counts must be non-negative integers or :unknown"
              {:value value})))

(defn- source-value
  [value known?]
  (cond
    (not known?) :unknown
    (nil? value) :reported
    (contains? sources value) value
    :else
    (invalid! :source-invalid
              "usage source must be :reported, :estimated, or :unknown"
              {:source value})))

(defn- coverage-value
  [value]
  (cond
    (nil? value) :partial
    (contains? coverages value) value
    :else
    (invalid! :coverage-invalid
              "usage coverage must be :partial or :final"
              {:coverage value})))

(defn report
  "Normalize one usage report.

  Accepts either a nested `:usage` map or flat token fields.  Every token field
  is present in the result as an integer or `:unknown`; `:sources` records the
  provenance of each field."
  [{:keys [coverage source usage] :as raw}]
  (let [flat (or usage (select-keys raw token-fields))
        coverage (coverage-value coverage)
        usage' (into {}
                     (map (fn [field]
                            [field (token-value (get flat field))]))
                     token-fields)
        sources' (into {}
                       (map (fn [field]
                              (let [value (get usage' field)
                                    known? (not= :unknown value)]
                                [field (source-value source known?)])))
                       token-fields)]
    {:coverage coverage
     :usage usage'
     :sources sources'}))

(defn unknown-report
  "A report that knows nothing.  Used when a transport supplies no usage."
  []
  (report {:coverage :partial :source :unknown :usage {}}))

(def ^:private display-fields
  "Token fields the conversation usage label reads."
  [:input-tokens :output-tokens])

(defn- display-source
  "One provenance for the displayed pair.

  Mirrors `aggregate-field`: an estimated displayed field marks the pair
  estimated, a reported one marks it reported, and a pair with no known
  provenance stays unknown."
  [sources]
  (let [known (keep #(get sources %) display-fields)]
    (cond
      (empty? known) :unknown
      (some #{:estimated} known) :estimated
      (some #{:reported} known) :reported
      :else :unknown)))

(defn flat-usage
  "Project one stored usage value onto the flat shape conversation
  surfaces read.

  A `report`-shaped map keeps its report under `:usage`/`:sources`/
  `:coverage` and additionally carries each token field at top level —
  `:unknown` becomes nil so labels can render 未知 — plus one `:source`
  summarizing the displayed pair's provenance.  Values that are not
  report-shaped pass through unchanged, so hand-written or legacy usage
  maps round-trip byte-for-byte."
  [usage]
  (if (and (map? usage) (map? (:usage usage)))
    (let [inner (:usage usage)
          flat (into {}
                     (map (fn [field]
                            [field (let [value (get inner field)]
                                     (if (= :unknown value) nil value))]))
                     token-fields)]
      (merge usage flat {:source (display-source (:sources usage))}))
    usage))

(defn merge-report
  "Merge a later report over an earlier report for the same attempt.

  A known incoming value replaces the earlier value.  An unknown incoming
  value never erases a known earlier value.  `:final` coverage wins over
  `:partial`.  Values are replaced, never added, so repeated partial callbacks
  and the final callback count once."
  [current incoming]
  (let [incoming (report incoming)
        current (or current (unknown-report))
        coverage (if (or (= :final (:coverage current))
                         (= :final (:coverage incoming)))
                   :final
                   :partial)
        usage' (into {}
                     (map (fn [field]
                            (let [old (get-in current [:usage field])
                                  new (get-in incoming [:usage field])
                                  source (if (= :unknown new)
                                           (get-in current [:sources field])
                                           (get-in incoming [:sources field]))]
                              [field (cond
                                       (not= :unknown new) new
                                       (not= :unknown old) old
                                       :else :unknown)]))
                     token-fields))
        sources' (into {}
                       (map (fn [field]
                              (let [new (get-in incoming [:usage field])
                                    old (get-in current [:usage field])]
                                [field (cond
                                         (not= :unknown new)
                                         (get-in incoming [:sources field])

                                         (not= :unknown old)
                                         (get-in current [:sources field])

                                         :else :unknown)]))
                       token-fields))]
    {:coverage coverage
     :usage usage'
     :sources sources'}))

(defn new-ledger
  "An empty usage ledger."
  []
  {:attempts {}
   :order []})

(defn- require-attempt
  [ledger attempt-id]
  (or (get-in ledger [:attempts attempt-id])
      (invalid! :attempt-unknown
                "usage must attach to an opened attempt"
                {:attempt-id attempt-id})))

(defn open-attempt
  "Open one attempt record.  Opening the same attempt twice is a no-op."
  [ledger {:keys [attempt-id call-id]}]
  (when (nil? attempt-id)
    (invalid! :attempt-id-missing
              "an attempt needs an attempt-id"
              {}))
  (if (get-in ledger [:attempts attempt-id])
    ledger
    (-> ledger
        (assoc-in [:attempts attempt-id]
                  {:attempt-id attempt-id
                   :call-id call-id
                   :status :running
                   :usage nil
                   :finish-reason nil
                   :reasoning nil
                   :continuity nil
                   :error nil})
        (update :order conj attempt-id))))

(defn record-usage
  "Merge one partial or final usage report into an opened attempt."
  [ledger attempt-id incoming]
  (require-attempt ledger attempt-id)
  (let [current (get-in ledger [:attempts attempt-id :usage])
        merged (merge-report current incoming)]
    {:ok true
     :report merged
     :ledger (assoc-in ledger [:attempts attempt-id :usage] merged)}))

(defn record-finish
  "Record the terminal response metadata for one attempt.

  `:reasoning` is the provider's public summary, if any.  `:continuity` is
  opaque provider data; it is stored under a non-editable marker and is never
  rewritten into user prose."
  [ledger attempt-id {:keys [finish-reason reasoning continuity status]}]
  (require-attempt ledger attempt-id)
  (let [status (or status :succeeded)]
    (when-not (contains? terminal-statuses status)
      (invalid! :status-invalid
                "finish status must be :succeeded, :failed, or :cancelled"
                {:status status}))
    (when-not (or (nil? reasoning) (string? reasoning))
      (invalid! :reasoning-invalid
                "reasoning must be a string or nil"
                {:reasoning reasoning}))
    (assoc-in ledger [:attempts attempt-id]
              (cond-> (-> (get-in ledger [:attempts attempt-id])
                          (assoc :status status
                                 :finish-reason finish-reason
                                 :reasoning reasoning))
                (some? continuity)
                (assoc :continuity {:data continuity
                                    :opaque? true
                                    :editable? false})))))

(defn record-failure
  "Record a failed attempt.  Its usage and cost stay in the ledger."
  [ledger attempt-id error]
  (require-attempt ledger attempt-id)
  (-> (assoc-in ledger [:attempts attempt-id :status] :failed)
      (assoc-in [:attempts attempt-id :error] error)))

(defn record-cancel
  "Record a cancelled attempt.  Its usage and cost stay in the ledger."
  [ledger attempt-id reason]
  (require-attempt ledger attempt-id)
  (-> (assoc-in ledger [:attempts attempt-id :status] :cancelled)
      (assoc-in [:attempts attempt-id :cancel-reason] reason)))

(defn attempt
  "One attempt record, or nil."
  [ledger attempt-id]
  (get-in ledger [:attempts attempt-id]))

(defn- aggregate-field
  [attempts field]
  (let [unknown? (fn [value] (or (nil? value) (= :unknown value)))
        entries (map (fn [attempt-id] [attempt-id (get attempts attempt-id)])
                     (keys attempts))
        unknown-ids (->> entries
                         (keep (fn [[attempt-id record]]
                                 (when (unknown?
                                        (get-in record [:usage :usage field]))
                                   attempt-id)))
                         (sort-by str)
                         vec)
        values (keep (fn [[_ record]]
                       (let [value (get-in record [:usage :usage field])]
                         (when-not (unknown? value) value)))
                     entries)
        sources (keep (fn [[_ record]]
                        (let [value (get-in record [:usage :usage field])]
                          (when-not (unknown? value)
                            (get-in record [:usage :sources field]))))
                      entries)]
    {:value (cond
              (empty? entries) :unknown
              (seq unknown-ids) :unknown
              :else (reduce + 0 values))
     :provenance (cond
                   (or (empty? entries) (seq unknown-ids)) :unknown
                   (some #{:estimated} sources) :estimated
                   (some #{:reported} sources) :reported
                   :else :unknown)
     :unknown-attempts unknown-ids}))

(defn aggregate
  "Aggregate every attempt once.

  Returns flat token totals, per-field provenance, unknown attempt ids, and
  the preserved attempt details.  A field with any unknown attempt is
  `:unknown`; no confirmed zero is invented."
  [ledger]
  (let [order (:order ledger)
        attempts (select-keys (:attempts ledger) order)
        fields (into {}
                     (map (fn [field]
                            [field (aggregate-field attempts field)]))
                     token-fields)
        records (map #(get-in attempts [%]) order)]
    (merge
     {:attempts (vec (map (fn [record]
                            (select-keys record
                                         [:attempt-id :call-id :status
                                          :usage :finish-reason
                                          :reasoning :continuity :error]))
                          records))
      :finish-reasons (vec (keep :finish-reason records))
      :reasoning (vec (keep :reasoning records))
      :continuity (vec (keep :continuity records))
      :failed-attempts (vec (keep (fn [record]
                                    (when (= :failed (:status record))
                                      (:attempt-id record)))
                                  records))
      :cancelled-attempts (vec (keep (fn [record]
                                       (when (= :cancelled (:status record))
                                         (:attempt-id record)))
                                   records))
      :complete? (and (seq records)
                      (every? (fn [field]
                                (not= :unknown (:value (get fields field))))
                              token-fields))}
     (into {}
           (map (fn [field]
                  [field (:value (get fields field))]))
           token-fields)
     {:provenance (into {}
                        (map (fn [field]
                               [field (:provenance (get fields field))]))
                        token-fields)
      :unknown-attempts (into {}
                              (map (fn [field]
                                     [field (:unknown-attempts (get fields field))]))
                              token-fields)})))
