(ns hc.server.diagnostics.usage
  "Usage arithmetic for statistics.

  This namespace sits above `hc.server.model-port.usage`.  The model port owns
  one attempt's report: coverage merge, provenance, and the rule that a
  missing value is `:unknown`, never zero.  Statistics adds request identity,
  cache scope, and collection totals.

  Cache scope is explicit:

    :input-subset       cache-read and cache-write tokens are already part of
                        :input-tokens; the total is input + output
    :additional-input   cache tokens are separate input-side quantities; the
                        total is input + output + cache-read + cache-write

  A cached-input subset is shown as detail and is never added to the total a
  second time.  The `:input-subset` contract also requires cache-read +
  cache-write <= input when all three are known; an inconsistent record is
  rejected instead of silently producing a wrong total.

  Every request remains countable, including failed and cancelled requests.
  A retry is a new request with `:retry-of`; a resumed service request is the
  same request annotated with `:recovery-of`.  Nothing here rewrites history."
  (:require [hc.server.model-port.usage :as model-usage])
  (:import [java.time Instant]
           [java.util Date]))

(def token-fields
  [:input-tokens :output-tokens :cache-read-tokens :cache-write-tokens])

(def cache-modes
  #{:input-subset :additional-input})

(defn- invalid!
  [reason message data]
  (throw (ex-info message
                  (merge {:error/kind :invalid-usage
                          :reason reason}
                         data))))

(defn- known-token?
  [value]
  (and (integer? value) (not (neg? value))))

(defn- normalized-report
  [raw]
  (cond
    (nil? raw) (model-usage/unknown-report)
    (and (map? raw)
         (contains? raw :usage)
         (contains? raw :sources))
    (let [usage (into {}
                      (map (fn [field]
                             [field (let [value (get-in raw [:usage field])]
                                      (if (and (integer? value)
                                               (not (neg? value)))
                                        value
                                        :unknown))]))
                      token-fields)
          sources (into {}
                        (map (fn [field]
                               [field (if (= :unknown (get usage field))
                                        :unknown
                                        (get-in raw [:sources field] :reported))]))
                        token-fields)]
      {:coverage (or (:coverage raw) :partial)
       :usage usage
       :sources sources})
    :else
    (model-usage/report (merge {:coverage :final} raw))))

(defn request
  "Normalize one model request record.

  Required: `:request-id`.  Optional identity and display fields are kept
  unchanged.  `:usage` is normalized by the model port; `:cache-mode`
  defaults to `:input-subset`.  A subset record whose cache tokens exceed its
  input is rejected because the total would otherwise be a lie."
  [raw]
  (when (nil? (:request-id raw))
    (invalid! :request-id-missing
              "a request needs a request-id"
              {}))
  (let [mode (or (:cache-mode raw) :input-subset)]
    (when-not (contains? cache-modes mode)
      (invalid! :cache-mode-invalid
                "cache mode must be :input-subset or :additional-input"
                {:cache-mode mode}))
    (let [report (normalized-report (:usage raw))
          input (get-in report [:usage :input-tokens])
          cache-read (get-in report [:usage :cache-read-tokens])
          cache-write (get-in report [:usage :cache-write-tokens])]
      (when (and (= :input-subset mode)
                 (known-token? input)
                 (known-token? cache-read)
                 (known-token? cache-write)
                 (> (+ cache-read cache-write) input))
        (invalid! :cache-not-subset
                  "cache tokens must not exceed input tokens in :input-subset mode"
                  {:request-id (:request-id raw)
                   :input-tokens input
                   :cache-read-tokens cache-read
                   :cache-write-tokens cache-write}))
      (assoc raw
             :cache-mode mode
             :usage report))))

(defn- provenance
  [report field]
  (let [value (get-in report [:usage field])]
    (if (= :unknown value)
      :unknown
      (get-in report [:sources field] :reported))))

(defn token-summary
  "Summarize one request's tokens, cache detail, and provenance.

  `:total-tokens` is `:unknown` when any quantity needed by the request's
  cache mode is unknown.  `:cache-read-tokens` and `:cache-write-tokens` are
  always details; in `:input-subset` mode they are not added to the total."
  [request]
  (let [report (:usage request)
        mode (or (:cache-mode request) :input-subset)
        values (into {}
                     (map (fn [field]
                            [field (get-in report [:usage field])]))
                     token-fields)
        input (:input-tokens values)
        output (:output-tokens values)
        cache-read (:cache-read-tokens values)
        cache-write (:cache-write-tokens values)
        input-known? (known-token? input)
        output-known? (known-token? output)
        read-known? (known-token? cache-read)
        write-known? (known-token? cache-write)
        additional? (= mode :additional-input)
        cache-total (if (and read-known? write-known?)
                      (+ cache-read cache-write)
                      :unknown)
        total (cond
                (not (and input-known? output-known?)) :unknown
                (and additional?
                     (not (and read-known? write-known?))) :unknown
                :else (+ input
                         output
                         (if additional? (+ cache-read cache-write) 0)))
        cache-subset? (cond
                        additional? :not-applicable
                        (and input-known? read-known? write-known?)
                        (<= (+ cache-read cache-write) input)
                        :else :unknown)
        sources (into {}
                      (map (fn [field]
                             [field (provenance report field)]))
                      token-fields)
        unknown-fields (into []
                             (keep (fn [field]
                                     (when (= :unknown (get values field))
                                       field)))
                             token-fields)
        total-sources (cond-> []
                        input-known? (conj (:input-tokens sources))
                        output-known? (conj (:output-tokens sources))
                        (and additional? read-known?)
                        (conj (:cache-read-tokens sources))
                        (and additional? write-known?)
                        (conj (:cache-write-tokens sources)))
        total-provenance (cond
                           (= :unknown total) :unknown
                           (some #{:estimated} total-sources) :estimated
                           (seq total-sources) :reported
                           :else :unknown)]
    (assoc values
           :total-tokens total
           :cache-total cache-total
           :cache-mode mode
           :cache-additional? additional?
           :cache-subset? cache-subset?
           :provenance sources
           :total-provenance total-provenance
           :unknown-fields unknown-fields
           :total-known? (not= :unknown total)
           :complete? (empty? unknown-fields))))

(defn- field-total
  [summaries field provenance-path]
  (let [unknown-ids (into []
                          (keep (fn [{:keys [request-id] :as summary}]
                                  (when (= :unknown (get summary field))
                                    request-id)))
                          summaries)
        values (keep (fn [summary]
                       (let [value (get summary field)]
                         (when (not= :unknown value) value)))
                     summaries)]
    {:value (cond
              (empty? summaries) :unknown
              (seq unknown-ids) :unknown
              :else (reduce + 0 values))
     :unknown-requests unknown-ids
     :provenance (cond
                   (or (empty? summaries) (seq unknown-ids)) :unknown
                   (some (fn [summary]
                           (= :estimated (get-in summary provenance-path)))
                         summaries)
                   :estimated
                   :else :reported)}))

(defn summary
  "Aggregate a collection of normalized requests once each.

  A field is an integer only when every request reported it; otherwise it is
  `:unknown` and `:unknown-requests` names the requests that prevented a
  total.  The same rule applies to `:total-tokens`.  No confirmed zero is
  invented for a missing value.

  `:reported-requests`, `:estimated-requests`, and `:unknown-requests` count
  requests by the provenance of their usage, not by their result status.
  Failed and cancelled requests are included when they have usage."
  [requests]
  (let [summaries (mapv (fn [request]
                          (assoc (token-summary request)
                                 :request-id (:request-id request)))
                        requests)
        fields (into {}
                     (map (fn [field]
                            [field (field-total summaries field
                                                [:provenance field])]))
                     token-fields)
        total (field-total summaries :total-tokens [:total-provenance])
        request-class (fn [summary]
                        (cond
                          (seq (:unknown-fields summary)) :unknown
                          (some (fn [field]
                                  (= :estimated
                                     (get-in summary [:provenance field])))
                                token-fields)
                          :estimated
                          :else :reported))
        classes (frequencies (map request-class summaries))
        subset-states (keep :cache-subset? summaries)]
    (merge
     {:request-count (count summaries)
      :complete? (and (seq summaries)
                      (zero? (get classes :unknown 0)))
      :all-reported? (and (seq summaries)
                          (= (count summaries)
                             (get classes :reported 0)))
      :reported-requests (get classes :reported 0)
      :estimated-requests (get classes :estimated 0)
      :unknown-requests (get classes :unknown 0)
      :cache-modes (frequencies (map :cache-mode summaries))
      :cache-subset? (cond
                       (empty? subset-states) :unknown
                       (every? true? subset-states) true
                       (some false? subset-states) false
                       (every? #(= :not-applicable %) subset-states)
                       :not-applicable
                       :else :unknown)
      :requests-with-unknown-usage
      (into []
            (keep (fn [summary]
                    (when (seq (:unknown-fields summary))
                      (:request-id summary))))
            summaries)}
     (into {}
           (map (fn [field]
                  [field (:value (get fields field))]))
           token-fields)
     {:total-tokens (:value total)
      :provenance (into {}
                        (map (fn [field]
                               [field (:provenance (get fields field))]))
                        token-fields)
      :total-provenance (:provenance total)
      :unknown-requests-by-field
      (into {}
            (keep (fn [field]
                    (let [ids (:unknown-requests (get fields field))]
                      (when (seq ids)
                        [field ids]))))
            token-fields)
      :unknown-requests-for-total (:unknown-requests total)})))

(defn turn-summary
  "Group normalized requests by `:turn-id`.

  One user send can contain several model requests (reasoning, tool
  follow-up, summary, retry).  The result keeps each request visible so the
  caller can show a drill-down without collapsing retries into the original."
  [requests]
  (let [time-key (fn [request]
                   (let [value (or (:started-at request) (:at request))]
                     (cond
                       (integer? value) value
                       (instance? Instant value) (.toEpochMilli ^Instant value)
                       (instance? Date value) (.getTime ^Date value)
                       (string? value)
                       (try
                         (.toEpochMilli (Instant/parse value))
                         (catch Exception _ Long/MAX_VALUE))
                       :else Long/MAX_VALUE)))
        groups (group-by :turn-id requests)]
    (into (sorted-map)
          (map (fn [[turn-id requests]]
                 (let [requests (vec (sort-by (juxt (fn [request]
                                                      (time-key request))
                                                    (comp str :request-id))
                                              requests))]
                   [turn-id
                    {:turn-id turn-id
                     :request-count (count requests)
                     :requests requests
                     :usage (summary requests)
                     :retry-count (count (filter :retry-of requests))
                     :failed-count (count (filter #(= :failed (:result %))
                                                  requests))
                     :cancelled-count (count (filter #(= :cancelled (:result %))
                                                     requests))}]))
          groups))))
