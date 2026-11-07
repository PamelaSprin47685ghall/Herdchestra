(ns hc.server.diagnostics.stats
  "Usage and cost statistics over explicit records.

  The statistics page must not turn missing data into a plausible zero curve.
  This namespace therefore separates four states:

    :ok             records were selected
    :no-data        the selection is genuinely empty
    :invalid-query  the requested dates or options are not valid
    :read-failed    the caller could not read the records; retry is possible

  Counts are distinct facts:

    :user-sends      one accepted user submission
    :model-requests  one provider request, including retries
    :tool-calls      one executed tool call

  One user send can contain several model requests.  Editing history without
  sending and switching a reply version do not create request records, so
  they never increase usage.

  Date buckets use one timezone for the whole query.  `:zone-mode :record`
  uses each record's own occurrence zone instead.  A record without a usable
  timestamp goes to `:unknown-time`; it is never assigned to an import day or
  to \"today\".  An explicit range cannot prove such a record belongs inside
  it, so the record is reported as excluded.  The `:all` range includes it in
  the totals and still shows it in the unknown-time bucket.

  Cost is grouped by currency.  A service-reported actual amount and a local
  estimate are separate fields; they are never added together.  A request
  with no price or no usage is counted as unestimated, never as zero."
  (:require [hc.server.diagnostics.pricing :as pricing]
            [hc.server.diagnostics.usage :as usage])
  (:import [java.time Instant LocalDate ZoneId ZonedDateTime]
           [java.time.format DateTimeParseException]
           [java.util Date]))

(def filter-keys
  [:assistant-id :model :service-id :purpose :result :session-id :unit])

(defn- error-result
  [code message data]
  (merge {:status :invalid-query
          :retry? false
          :error {:code code :message message}}
         data))

(defn- epoch-ms
  [value]
  (cond
    (nil? value) nil
    (instance? Instant value) (.toEpochMilli ^Instant value)
    (instance? Date value) (.getTime ^Date value)
    (instance? ZonedDateTime value)
    (.toEpochMilli (.toInstant ^ZonedDateTime value))
    (integer? value) value
    (string? value)
    (try
      (.toEpochMilli (Instant/parse value))
      (catch Exception _ nil))
    :else nil))

(defn- zone-of
  [value]
  (try
    (ZoneId/of (if (keyword? value) (name value) (str value)))
    (catch Exception _ nil)))

(defn- query-zone
  [query]
  (if (some? (:zone query))
    (zone-of (:zone query))
    (or (zone-of (.getId (ZoneId/systemDefault)))
        (zone-of "UTC"))))

(defn- parse-date
  [value]
  (cond
    (nil? value) nil
    (instance? LocalDate value) value
    (string? value)
    (try
      (LocalDate/parse value)
      (catch DateTimeParseException _ nil))
    :else nil))

(defn- resolve-range
  [query now zone]
  (let [all? (true? (:all query))
        from-raw (:from query)
        to-raw (:to query)]
    (cond
      (and all? (or (some? from-raw) (some? to-raw)))
      (error-result :range-conflict
                    ":all cannot be combined with :from or :to"
                    {:range {:all? true}})

      all?
      {:ok? true :all? true :from nil :to nil}

      :else
      (let [today (.toLocalDate (.atZone (Instant/ofEpochMilli now) zone))
            from (if (some? from-raw)
                   (parse-date from-raw)
                   (.minusDays today 29))
            to (if (some? to-raw)
                 (parse-date to-raw)
                 today)]
        (cond
          (nil? from)
          (error-result :date-invalid
                        ":from must be an ISO local date"
                        {:from from-raw})

          (nil? to)
          (error-result :date-invalid
                        ":to must be an ISO local date"
                        {:to to-raw})

          (.isAfter ^LocalDate from ^LocalDate to)
          (error-result :range-reversed
                        ":from must not be after :to"
                        {:from (str from) :to (str to)})

          :else
          {:ok? true :all? false :from from :to to})))))

(defn- record-zone
  [record zone-mode default-zone]
  (if (= :record zone-mode)
    (or (zone-of (:occurred-zone record)) default-zone)
    default-zone))

(defn- record-day
  [record zone-mode default-zone]
  (let [at (epoch-ms (:at record))]
    (when at
      (.toLocalDate
       (.atZone (Instant/ofEpochMilli at)
                (record-zone record zone-mode default-zone))))))

(defn- in-range?
  [day range]
  (or (:all? range)
      (and day
           (not (.isBefore ^LocalDate day ^LocalDate (:from range)))
           (not (.isAfter ^LocalDate day ^LocalDate (:to range))))))

(defn- classify
  [records range zone-mode zone]
  (reduce (fn [acc record]
            (let [at (epoch-ms (:at record))
                  day (record-day record zone-mode zone)]
              (cond
                (and (some? (:at record)) (nil? at))
                (if (:all? range)
                  (-> acc
                      (update :selected conj record)
                      (update :invalid conj record))
                  (update acc :invalid conj record))

                (nil? at)
                (if (:all? range)
                  (-> acc
                      (update :selected conj record)
                      (update :missing conj record))
                  (update acc :missing conj record))

                (in-range? day range)
                (update acc :selected conj record)

                :else acc)))
          {:selected [] :invalid [] :missing []}
          records))

(defn- dedupe-by
  [id-fn records]
  (let [seen (volatile! #{})]
    (into []
          (filter (fn [record]
                    (let [id (id-fn record)]
                      (cond
                        (nil? id) true
                        (contains? @seen id) false
                        :else (do (vswap! seen conj id) true)))))
          records)))

(defn- matches?
  [record filters]
  (every? (fn [[key value]]
            (or (nil? value)
                (= value (get record key))))
          filters))

(defn- selected
  [filters records]
  (filterv #(matches? % filters) records))

(defn- request-with-price
  "Attach a catalog price only when the stored request has no snapshot.

  A request that already carries `:price` keeps it forever.  The catalog is
  therefore a fallback for old records, never a rewrite of a recorded price."
  [request catalog]
  (if (or (:price request) (nil? catalog))
    request
    (if-let [price (get catalog [(:service-id request) (:model request)])]
      (assoc request :price price)
      request)))

(defn- request-records
  [snapshot filters catalog]
  (->> (:requests snapshot)
       (dedupe-by :request-id)
       (selected filters)
       (map usage/request)
       (map #(request-with-price % catalog))
       vec))

(defn- send-records
  [snapshot filters]
  (->> (:user-sends snapshot)
       (dedupe-by #(or (:send-id %) (:turn-id %)))
       (selected filters)
       vec))

(defn- tool-records
  [snapshot filters]
  (->> (:tool-calls snapshot)
       (dedupe-by #(or (:tool-call-id %)
                       (when (:turn-id %)
                         [(:turn-id %) (:name %) (:index %)])))
       (selected filters)
       vec))

(defn- service-usage-records
  [snapshot filters]
  (->> (:service-usages snapshot)
       (dedupe-by #(or (:usage-id %)
                       [(:turn-id %) (:name %) (:unit %)]))
       (selected filters)
       vec))

(defn- heatmap
  [sends requests tools zone-mode zone]
  (let [add (fn [m records field]
              (reduce (fn [m record]
                        (if-let [day (record-day record zone-mode zone)]
                          (update-in m [(str day) field] (fnil inc 0))
                          m))
                      m
                      records))]
    (-> (sorted-map)
        (add sends :user-sends)
        (add requests :model-requests)
        (add tools :tool-calls))))

(defn- active-days
  [heat]
  (count (filter (fn [[_ counts]]
                   (some pos? (vals counts)))
                 heat)))

(def send-view-keys
  [:send-id :turn-id :assistant-id :model :service-id :at :temporary?])

(def tool-view-keys
  [:tool-call-id :turn-id :name :index :at :service-id :result])

(defn- send-view
  [send]
  (select-keys send send-view-keys))

(defn- tool-view
  [tool]
  (select-keys tool tool-view-keys))

(defn- safe-billed
  [billed]
  (when billed
    (try
      (pricing/billed billed)
      (catch Exception _
        {:invalid? true}))))

(defn- safe-estimate
  [price usage]
  (try
    (pricing/estimate price usage)
    (catch Exception _
      {:status :unestimated
       :currency nil
       :amount nil
       :missing [:invalid-price]})))

(defn- request-cost
  [request]
  (let [billed (safe-billed (:billed request))
        estimate (when (:price request)
                   (safe-estimate
                    (:price request)
                    (assoc (usage/token-summary request)
                           :cache-mode (:cache-mode request))))
        invalid-price? (boolean (some #{:invalid-price} (:missing estimate)))]
    {:request-id (:request-id request)
     :estimate estimate
     :billed (when-not (:invalid? billed) billed)
     :invalid-price? invalid-price?
     :invalid-billing? (true? (:invalid? billed))}))

(defn- empty-currency
  []
  {:estimated-amount nil
   :actual-amount nil
   :estimated-requests 0
   :partial-requests 0
   :unestimated-requests 0
   :actual-requests 0
   :pricing-sources #{}
   :price-dates #{}})

(defn- add-estimate
  [entry cost]
  (let [estimate (:estimate cost)
        status (:status estimate)
        amount (:amount estimate)]
    (cond-> entry
      (and estimate (not= :unestimated status))
      (update :estimated-amount (fnil + 0M) (or amount 0M))

      (and estimate (not= :unestimated status))
      (update :estimated-requests inc)

      (and estimate (= :partial status))
      (update :partial-requests inc)

      (and estimate (:price-source estimate))
      (update :pricing-sources conj (:price-source estimate))

      (and estimate (:price-source-date estimate))
      (update :price-dates conj (:price-source-date estimate)))))

(defn- add-billed
  [entry billed]
  (cond-> entry
    billed (update :actual-amount (fnil + 0M) (:amount billed))
    billed (update :actual-requests inc)))

(defn- cost-summary
  [requests]
  (let [initial {:by-currency {}
                 :unknown-currency-requests 0
                 :invalid-billing-requests 0
                 :invalid-price-requests 0
                 :currency-mismatch-requests 0
                 :unestimated-requests 0
                 :estimated-requests 0
                 :partial-requests 0
                 :actual-requests 0}
        summary
        (reduce
         (fn [acc request]
           (let [cost (request-cost request)
                 estimate (:estimate cost)
                 billed (:billed cost)
                 estimate-currency (:currency estimate)
                 billed-currency (:currency billed)
                 currency (or estimate-currency billed-currency)
                 currency-mismatch? (and estimate-currency
                                         billed-currency
                                         (not= estimate-currency
                                               billed-currency))
                 unestimated? (and (not billed)
                                   (or (nil? estimate)
                                       (= :unestimated (:status estimate))))
                 estimated? (and estimate
                                 (not= :unestimated (:status estimate)))
                 acc (cond-> acc
                       (:invalid-billing? cost)
                       (update :invalid-billing-requests inc)

                       (:invalid-price? cost)
                       (update :invalid-price-requests inc)

                       unestimated?
                       (update :unestimated-requests inc)

                       estimated?
                       (update :estimated-requests inc)

                       (and estimated? (= :partial (:status estimate)))
                       (update :partial-requests inc)

                       billed
                       (update :actual-requests inc))]
             (cond
               (:invalid-billing? cost) acc

               currency-mismatch?
               (cond-> acc
                 estimate-currency
                 (update-in [:by-currency estimate-currency]
                            (fn [entry]
                              (add-estimate (or entry (empty-currency))
                                            cost)))

                 billed-currency
                 (update-in [:by-currency billed-currency]
                            (fn [entry]
                              (add-billed (or entry (empty-currency))
                                          billed)))

                 (and estimate-currency unestimated?)
                 (update-in [:by-currency estimate-currency
                             :unestimated-requests]
                            inc)

                 true
                 (update :currency-mismatch-requests inc))

               (nil? currency)
               (update acc :unknown-currency-requests inc)

               :else
               (let [entry (get-in acc [:by-currency currency]
                                   (empty-currency))
                     entry (add-estimate entry cost)
                     entry (add-billed entry billed)
                     entry (cond-> entry
                             unestimated?
                             (update :unestimated-requests inc))]
                 (assoc-in acc [:by-currency currency] entry)))))
         initial
         requests)]
    (assoc summary :request-count (count requests))))

(defn- model-rows
  [requests]
  (let [groups (group-by (juxt :service-id :model) requests)]
    (->> groups
         (map (fn [[[service-id model] requests]]
                {:service-id service-id
                 :service-name (some :service-name requests)
                 :model model
                 :requests (count requests)
                 :usage (usage/summary requests)
                 :cost (cost-summary requests)}))
         (sort-by (fn [row]
                    [(- (:requests row))
                     (str (:service-id row))
                     (str (:model row))]))
         vec)))

(defn- safe-service-estimate
  [price quantity]
  (try
    (pricing/estimate-service price quantity)
    (catch Exception _
      {:status :unestimated
       :currency nil
       :unit nil
       :quantity :unknown
       :amount nil
       :missing [:invalid-price]})))

(defn- empty-service-entry
  []
  {:estimated-amount nil
   :actual-amount nil
   :estimated-usages 0
   :unestimated-usages 0
   :actual-usages 0
   :reported-quantity-usages 0
   :estimated-quantity-usages 0
   :unknown-quantity-usages 0
   :units {}})

(defn- add-service-quantity
  [entry unit quantity amount]
  (let [known-quantity? (and (number? quantity)
                             (not (neg? (bigdec quantity))))]
    (cond-> entry
      (and unit known-quantity?)
      (-> (update-in [:units unit :quantity]
                     (fnil + 0M) (bigdec quantity))
          (update-in [:units unit :usages] (fnil inc 0)))
      (and unit amount)
      (update-in [:units unit :estimated-amount]
                 (fnil + 0M) amount))))

(defn- add-service-estimate
  [entry estimate unit quantity]
  (let [status (:status estimate)
        estimated? (not= :unestimated status)
        amount (:amount estimate)]
    (cond-> (add-service-quantity entry unit quantity amount)
      estimated? (update :estimated-usages inc)
      (= :unestimated status) (update :unestimated-usages inc)
      amount (update :estimated-amount (fnil + 0M) amount))))

(defn- add-service-billed
  [entry billed]
  (cond-> entry
    billed (-> (update :actual-amount (fnil + 0M) (:amount billed))
               (update :actual-usages inc))))

(defn- service-cost-summary
  "Cost of non-token service usage, grouped by currency and by unit.

  A service unit is never converted into a token quantity or vice versa.
  `:by-currency` sums amounts in one currency; `:by-unit` preserves the
  billing unit and quantity.  An estimate currency and a provider-reported
  charge currency are never added together: a mismatch puts each amount in
  its own bucket and increments `:currency-mismatch-usages`."
  [usages]
  (reduce
   (fn [acc usage]
     (let [estimate (safe-service-estimate (:price usage) (:quantity usage))
           billed (safe-billed (:billed usage))
           estimate-currency (:currency estimate)
           billed-currency (:currency billed)
           currency (or estimate-currency billed-currency)
           mismatch? (and estimate-currency billed-currency
                          (not= estimate-currency billed-currency))
           unit (or (:unit estimate) (:unit usage))
           quantity (:quantity estimate)
           known-quantity? (and (number? quantity)
                                (not (neg? (bigdec quantity))))
           quantity-source (or (:quantity-source usage) :unknown)
           reported-quantity? (= :provider-reported quantity-source)
           estimated-quantity? (= :estimated quantity-source)
           unknown-quantity? (not (or reported-quantity?
                                      estimated-quantity?))
           estimated? (not= :unestimated (:status estimate))
           unestimated? (and (not billed) (not estimated?))
           amount (:amount estimate)
           acc (cond-> acc
                 estimated? (update :estimated-usages inc)
                 unestimated? (update :unestimated-usages inc)
                 billed (update :actual-usages inc)
                 reported-quantity?
                 (update :reported-quantity-usages inc)
                 estimated-quantity?
                 (update :estimated-quantity-usages inc)
                 unknown-quantity?
                 (update :unknown-quantity-usages inc)
                 (some #{:invalid-price} (:missing estimate))
                 (update :invalid-price-usages inc)
                 (and unit known-quantity?)
                 (-> (update-in [:by-unit unit :quantity]
                                (fnil + 0M) (bigdec quantity))
                     (update-in [:by-unit unit :usages] (fnil inc 0)))
                 (and unit amount)
                 (update-in [:by-unit unit :estimated-amount]
                            (fnil + 0M) amount))]
       (cond
         mismatch?
         (cond-> (update acc :currency-mismatch-usages inc)
           estimate-currency
           (update-in [:by-currency estimate-currency]
                      #(add-service-estimate (or % (empty-service-entry))
                                             estimate unit quantity))
           billed-currency
           (update-in [:by-currency billed-currency]
                      #(add-service-billed (or % (empty-service-entry))
                                           billed)))

         (nil? currency) (update acc :unknown-currency-usages inc)

         :else
         (let [entry (get-in acc [:by-currency currency]
                             (empty-service-entry))
               entry (-> entry
                         (add-service-estimate estimate unit quantity)
                         (add-service-billed billed)
                         (cond-> reported-quantity?
                           (update :reported-quantity-usages inc)
                           estimated-quantity?
                           (update :estimated-quantity-usages inc)
                           unknown-quantity?
                           (update :unknown-quantity-usages inc)))]
           (assoc-in acc [:by-currency currency] entry)))))
   {:usage-count (count usages)
    :by-currency {}
    :by-unit {}
    :estimated-usages 0
    :unestimated-usages 0
    :actual-usages 0
    :reported-quantity-usages 0
    :estimated-quantity-usages 0
    :unknown-quantity-usages 0
    :invalid-price-usages 0
    :unknown-currency-usages 0
    :currency-mismatch-usages 0}
   usages))

(defn- top-models
  [rows top-n]
  (if (and top-n (< top-n (count rows)))
    {:top (vec (take top-n rows))
     :other {:requests (reduce + (map :requests (drop top-n rows)))
             :models (count (drop top-n rows))}}
    {:top (vec rows)
     :other nil}))

(defn- request-detail
  [request]
  (let [cost (request-cost request)
        token-summary (usage/token-summary request)]
    {:request-id (:request-id request)
     :turn-id (:turn-id request)
     :model (:model request)
     :service-id (:service-id request)
     :service-name (:service-name request)
     :purpose (:purpose request)
     :result (:result request)
     :started-at (:started-at request)
     :finished-at (:finished-at request)
     :retry? (some? (:retry-of request))
     :retry-of (:retry-of request)
     :recovery? (some? (:recovery-of request))
     :recovery-of (:recovery-of request)
     :usage token-summary
     :cost {:estimate (:estimate cost)
            :billed (:billed cost)
            :invalid-price? (:invalid-price? cost)
            :invalid-billing? (:invalid-billing? cost)}}))

(defn- service-usage-detail
  [usage]
  (let [estimate (safe-service-estimate (:price usage) (:quantity usage))
        billed (safe-billed (:billed usage))]
    (assoc (select-keys usage
                        [:usage-id :turn-id :name :service-id :service-name
                         :unit :quantity :quantity-source :at :result])
           :cost {:estimate estimate
                  :billed billed})))

(defn- turn-rows
  [requests sends tools service-usages]
  (let [request-groups (group-by :turn-id requests)
        send-groups (group-by :turn-id sends)
        tool-groups (group-by :turn-id tools)
        service-groups (group-by :turn-id service-usages)
        turn-ids (set (concat (keys request-groups)
                              (keys send-groups)
                              (keys tool-groups)
                              (keys service-groups)))]
    (->> turn-ids
         (map (fn [turn-id]
                (let [requests (vec (sort-by (fn [request]
                                               (or (epoch-ms (:started-at request))
                                                   (epoch-ms (:at request))
                                                   Long/MAX_VALUE))
                                             (get request-groups turn-id)))
                      sends (vec (get send-groups turn-id))
                      tools (vec (get tool-groups turn-id))
                      service-usages (vec (get service-groups turn-id))
                      times (concat (keep #(or (epoch-ms (:started-at %))
                                               (epoch-ms (:at %)))
                                          requests)
                                    (keep #(epoch-ms (:at %)) sends)
                                    (keep #(epoch-ms (:at %)) tools)
                                    (keep #(epoch-ms (:at %)) service-usages))]
                  {:turn-id turn-id
                   :user-send (some-> (first sends) send-view)
                   :user-sends (count sends)
                   :requests (mapv request-detail requests)
                   :tool-calls (mapv tool-view tools)
                   :service-usages (mapv service-usage-detail service-usages)
                   :counts {:user-sends (count sends)
                            :model-requests (count requests)
                            :tool-calls (count tools)}
                   :service-usage-count (count service-usages)
                   :retry-count (count (filter :retry-of requests))
                   :failed-count (count (filter #(= :failed (:result %))
                                                requests))
                   :cancelled-count (count (filter #(= :cancelled (:result %))
                                                   requests))
                   :usage (when (seq requests) (usage/summary requests))
                   :cost (when (seq requests) (cost-summary requests))
                   :service-cost (when (seq service-usages)
                                   (service-cost-summary service-usages))
                   :at (when (seq times) (apply min times))})))
         (sort-by (fn [turn]
                    [(or (:at turn) Long/MAX_VALUE)
                     (str (:turn-id turn))]))
         vec)))

(defn- unknown-time
  [sends requests tools service-usages]
  {:user-sends (count sends)
   :model-requests (count requests)
   :tool-calls (count tools)
   :service-usages (count service-usages)
   :records {:user-sends (mapv send-view sends)
             :model-requests (mapv #(select-keys %
                                                  [:request-id :turn-id
                                                   :assistant-id :model
                                                   :service-id :purpose
                                                   :result])
                                   requests)
             :tool-calls (mapv tool-view tools)
             :service-usages (mapv #(select-keys %
                                                  [:usage-id :turn-id
                                                   :name :service-id
                                                   :unit :quantity :at])
                                   service-usages)}})

(defn summary
  "Build one statistics view over SNAPSHOT and QUERY.

  SNAPSHOT keys: `:requests`, `:user-sends`, `:tool-calls`, optional
  `:last-updated`, and optional `:read-error`.  QUERY keys: `:from`, `:to`,
  `:all`, `:zone`, `:zone-mode`, `:top-n`, `:now`, plus any filter in
  `filter-keys`.  The same selected sets feed counts, heatmap, model rows,
  turn details, and cost, so one view cannot mix ranges."
  [snapshot query]
  (let [query (or query {})
        zone (query-zone query)
        zone-mode (or (:zone-mode query) :query)
        now (or (epoch-ms (:now query))
                (System/currentTimeMillis))
        range (when zone (resolve-range query now zone))
        filters (select-keys query filter-keys)
        top-n (:top-n query)]
    (cond
      (nil? zone)
      (error-result :zone-invalid
                    ":zone must be a valid timezone id"
                    {:zone (:zone query)})

      (not (:ok? range))
      (merge (dissoc range :ok? :from :to)
             {:timezone (str zone)
              :zone-mode zone-mode
              :filters filters
              :range {:all? (:all? range)
                      :from (some-> (:from range) str)
                      :to (some-> (:to range) str)}
              :counts nil
              :tokens nil
              :cost nil
              :service-cost nil
              :service-usage-count nil
              :models nil
              :turns nil
              :heatmap nil})

      (:read-error snapshot)
      {:status :read-failed
       :retry? true
       :message (or (:read-error snapshot)
                    "statistics could not be read")
       :timezone (str zone)
       :zone-mode zone-mode
       :filters filters
       :range {:all? (:all? range)
               :from (some-> (:from range) str)
               :to (some-> (:to range) str)}
       :counts nil
       :tokens nil
       :cost nil
       :service-cost nil
       :service-usage-count nil
       :models nil
       :turns nil
       :heatmap nil}

      (not (contains? #{:query :record} zone-mode))
      (error-result :zone-mode-invalid
                    ":zone-mode must be :query or :record"
                    {:zone-mode zone-mode})

      (and (some? top-n)
           (not (and (integer? top-n) (not (neg? top-n)))))
      (error-result :top-n-invalid
                    ":top-n must be a non-negative integer"
                    {:top-n top-n})

      :else
      (let [sends (send-records snapshot filters)
            catalog (:prices snapshot)
            requests (request-records snapshot filters catalog)
            tools (tool-records snapshot filters)
            service-usages (service-usage-records snapshot filters)
            send-time (classify sends range zone-mode zone)
            request-time (classify requests range zone-mode zone)
            tool-time (classify tools range zone-mode zone)
            service-time (classify service-usages range zone-mode zone)
            selected-sends (:selected send-time)
            selected-requests (:selected request-time)
            selected-tools (:selected tool-time)
            selected-service-usages (:selected service-time)
            unknown-sends (concat (:missing send-time) (:invalid send-time))
            unknown-requests (concat (:missing request-time)
                                     (:invalid request-time))
            unknown-tools (concat (:missing tool-time) (:invalid tool-time))
            unknown-service-usages (concat (:missing service-time)
                                           (:invalid service-time))
            unknown (unknown-time unknown-sends unknown-requests unknown-tools
                                  unknown-service-usages)
            heat (heatmap selected-sends selected-requests selected-tools
                          zone-mode zone)
            token-totals (when (seq selected-requests)
                           (usage/summary selected-requests))
            costs (when (seq selected-requests)
                    (cost-summary selected-requests))
            service-costs (when (seq selected-service-usages)
                            (service-cost-summary selected-service-usages))
            rows (model-rows selected-requests)
            top (top-models rows top-n)
            counts {:user-sends (count selected-sends)
                    :model-requests (count selected-requests)
                    :tool-calls (count selected-tools)
                    :active-days (active-days heat)}
            excluded-unknown (when-not (:all? range)
                               {:user-sends (count unknown-sends)
                                :model-requests (count unknown-requests)
                                :tool-calls (count unknown-tools)
                                :service-usages (count unknown-service-usages)
                                :invalid-user-sends (count (:invalid send-time))
                                :invalid-model-requests (count (:invalid request-time))
                                :invalid-tool-calls (count (:invalid tool-time))
                                :invalid-service-usages
                                (count (:invalid service-time))})
            empty? (and (zero? (:user-sends counts))
                        (zero? (:model-requests counts))
                        (zero? (:tool-calls counts))
                        (zero? (count selected-service-usages)))]
        {:status (if empty? :no-data :ok)
         :empty? empty?
         :range {:all? (:all? range)
                 :from (some-> (:from range) str)
                 :to (some-> (:to range) str)}
         :timezone (str zone)
         :zone-mode zone-mode
         :filters filters
         :counts counts
         :heatmap heat
         :tokens token-totals
         :cost costs
         :service-cost service-costs
         :service-usage-count (count selected-service-usages)
         :models rows
         :top-models top
         :turns (turn-rows selected-requests selected-sends selected-tools
                           selected-service-usages)
         :unknown-time unknown
         :excluded-unknown-time excluded-unknown
         :last-updated (:last-updated snapshot)
         :cached? (boolean (:cached? snapshot))
         :read-error nil}))))

(defn reprice
  "Return a new request vector with prices looked up in CATALOG.

  The input requests are unchanged, so the original estimate remains
  available.  A request with no catalog entry keeps its recorded price."
  [requests catalog]
  (mapv (fn [request]
          (if-let [price (pricing/lookup catalog
                                         (:service-id request)
                                         (:model request))]
            (assoc request :price price)
            request))
        requests))

(defn unavailable
  "A statistics view for a failed read.  It contains no zero curve."
  [message]
  {:status :read-failed
   :retry? true
   :message (or message "statistics could not be read")
   :counts nil
   :tokens nil
   :cost nil
   :models nil
   :turns nil
   :heatmap nil})
