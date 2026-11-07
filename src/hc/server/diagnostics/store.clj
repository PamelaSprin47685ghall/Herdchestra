(ns hc.server.diagnostics.store
  "Persist diagnostics records in the current-state store.

  All records are partition-scoped in the `diagnostics` partition, so one
  read returns requests, sends, tool calls, service usage, prices, and the
  detailed-log policy without scanning user session partitions.  This
  namespace uses only the public `hc.server.storage` facade.

  Append-only records use a deterministic migration ID derived from their
  domain ID.  A replay with the same payload is idempotent; a different
  payload is a storage conflict.  Policy and price updates use a fresh UUID.
  A log entry and its policy change commit in one migration."
  (:require [clojure.string :as str]
            [hc.server.diagnostics.log :as detail-log]
            [hc.server.diagnostics.pricing :as pricing]
            [hc.server.diagnostics.usage :as usage]
            [hc.server.storage :as storage])
  (:import (java.util UUID)))

(def partition-id "diagnostics")

(def kinds
  {:request :diagnostics-request
   :user-send :diagnostics-user-send
   :tool-call :diagnostics-tool-call
   :service-usage :diagnostics-service-usage
   :price :diagnostics-price
   :log-policy :diagnostics-log-policy
   :log-entry :diagnostics-log-entry})

(def request-keys
  [:request-id :turn-id :session-id :assistant-id :service-id :service-name
   :model :purpose :result :retry-of :recovery-of :at :started-at
   :first-token-at :finished-at :temporary? :cache-mode :price :billed
   :usage])

(def send-keys
  [:send-id :turn-id :assistant-id :model :service-id :at :temporary?])

(def tool-keys
  [:tool-call-id :turn-id :session-id :assistant-id :name :index :at
   :service-id :result :temporary?])

(def service-usage-keys
  [:usage-id :turn-id :session-id :assistant-id :service-id :service-name
   :name :unit :quantity :quantity-source :at :price :billed :result])

(defn- now-ms
  [value]
  (or value (System/currentTimeMillis)))

(defn- records
  [view kind]
  (get-in view [:records kind] []))

(defn- first-record
  [view kind id]
  (first (filter #(= id (:storage/id %)) (records view kind))))

(defn read-view
  "Read the partition-scoped diagnostics view."
  [store]
  (storage/read-session-view store {:partition partition-id}))

(declare retained-log-entries)

(defn snapshot
  "Return all diagnostics records plus the effective log policy.

  Log entries older than the policy retention window are omitted from the
  returned entries.  They remain on disk until `expire-log!` or
  `clear-log!` commits their deletion."
  [store now]
  (let [now (now-ms now)
        view (read-view store)
        policy (or (first-record view (:log-policy kinds) "policy")
                   (detail-log/new-policy))
        policy (detail-log/expire policy now)
        entries (retained-log-entries view policy now)
        policy (assoc policy :entries entries)
        updated (keep :storage/updated-at
                      (concat (records view (:request kinds))
                              (records view (:user-send kinds))
                              (records view (:tool-call kinds))
                              (records view (:service-usage kinds))
                              (records view (:log-entry kinds))))]
    {:status :ok
     :version (:version view)
     :requests (mapv #(dissoc % :storage/id :storage/kind :storage/session-id
                              :storage/version :storage/partition
                              :storage/updated-at)
                     (records view (:request kinds)))
     :user-sends (mapv #(dissoc % :storage/id :storage/kind :storage/session-id
                                :storage/version :storage/partition
                                :storage/updated-at)
                       (records view (:user-send kinds)))
     :tool-calls (mapv #(dissoc % :storage/id :storage/kind :storage/session-id
                                :storage/version :storage/partition
                                :storage/updated-at)
                       (records view (:tool-call kinds)))
     :service-usages (mapv #(dissoc % :storage/id :storage/kind
                                    :storage/session-id :storage/version
                                    :storage/partition :storage/updated-at)
                           (records view (:service-usage kinds)))
     :prices (into {}
                   (map (fn [record]
                          [[(:service-id record) (:model record)]
                           (dissoc record :storage/id :storage/kind
                                   :storage/session-id :storage/version
                                   :storage/partition :storage/updated-at)]))
                   (records view (:price kinds)))
     :log-policy (dissoc policy :storage/id :storage/kind :storage/session-id
                         :storage/version :storage/partition
                         :storage/updated-at)
     :log-entries (mapv #(dissoc % :storage/id :storage/kind
                                 :storage/session-id :storage/version
                                 :storage/partition :storage/updated-at)
                        entries)
     :last-updated (when (seq updated) (last (sort (map str updated))))}))

(defn- error-result
  [t]
  (let [code (try (storage/error-code t) (catch Throwable _ nil))]
    {:status (if (= :STORAGE_VERSION_CONFLICT code) :conflict :error)
     :error {:code (or code :DIAGNOSTICS_STORE_FAILED)
             :message (or (ex-message t) "diagnostics storage operation failed")}}))

(defn- commit!
  [store migration]
  (try
    {:status :ok
     :result (storage/commit-migration! store migration)}
    (catch clojure.lang.ExceptionInfo t
      (error-result t))))

(defn- put-one!
  [store kind id doc migration-id reason]
  (commit! store
           {:migration-id migration-id
            :partition partition-id
            :reason reason
            :put [{:kind kind :id id :doc doc}]}))

(defn- put-one-with-policy!
  [store kind id doc policy migration-id reason]
  (commit! store
           {:migration-id migration-id
            :partition partition-id
            :reason reason
            :put [{:kind kind :id id :doc doc}
                  {:kind (:log-policy kinds) :id "policy" :doc policy}]}))

(defn record-request!
  "Persist one normalized model request."
  [store request]
  (let [request (usage/request request)
        doc (select-keys request request-keys)]
    (put-one! store (:request kinds) (:request-id request) doc
              (str "diagnostics-request-" (:request-id request))
              :diagnostics-request)))

(defn record-user-send!
  "Persist one user-send metadata record."
  [store send]
  (let [id (or (:send-id send) (:turn-id send))]
    (when (str/blank? (str id))
      (throw (ex-info "a user send needs a send-id or turn-id"
                      {:error/kind :invalid-diagnostics-record
                       :reason :send-id-missing})))
    (put-one! store (:user-send kinds) (str id)
              (select-keys send send-keys)
              (str "diagnostics-user-send-" id)
              :diagnostics-user-send)))

(defn record-tool-call!
  "Persist one tool-call metadata record."
  [store tool]
  (let [id (or (:tool-call-id tool)
               (when (:turn-id tool)
                 (str (:turn-id tool) ":" (:name tool) ":" (:index tool))))]
    (when (str/blank? (str id))
      (throw (ex-info "a tool call needs a tool-call-id"
                      {:error/kind :invalid-diagnostics-record
                       :reason :tool-call-id-missing})))
    (put-one! store (:tool-call kinds) (str id)
              (select-keys tool tool-keys)
              (str "diagnostics-tool-call-" id)
              :diagnostics-tool-call)))

(defn record-service-usage!
  "Persist one non-token service usage record.

  The caller must supply a provider-reported or explicitly estimated
  quantity.  This function never infers a quantity from bytes, text length,
  or elapsed time."
  [store usage]
  (let [id (:usage-id usage)
        unit (:unit usage)
        quantity (:quantity usage)
        source (or (:quantity-source usage) :unknown)]
    (when (str/blank? (str id))
      (throw (ex-info "a service usage needs a usage-id"
                      {:error/kind :invalid-diagnostics-record
                       :reason :usage-id-missing})))
    (when-not (contains? pricing/service-units unit)
      (throw (ex-info "a service usage needs a known unit"
                      {:error/kind :invalid-diagnostics-record
                       :reason :unit-invalid
                       :unit unit})))
    (when-not (contains? #{:provider-reported :estimated :unknown} source)
      (throw (ex-info "quantity source must be :provider-reported, :estimated, or :unknown"
                      {:error/kind :invalid-diagnostics-record
                       :reason :quantity-source-invalid
                       :quantity-source source})))
    (when-not (or (= :unknown quantity)
                  (and (number? quantity) (not (neg? (bigdec quantity)))))
      (throw (ex-info "quantity must be a non-negative number or :unknown"
                      {:error/kind :invalid-diagnostics-record
                       :reason :quantity-invalid
                       :quantity quantity})))
    (put-one! store (:service-usage kinds) (str id)
              (select-keys (assoc usage :quantity-source source)
                           service-usage-keys)
              (str "diagnostics-service-usage-" id)
              :diagnostics-service-usage)))

(defn save-price!
  "Persist a token or service price for `[service-id model]`."
  [store service-id model raw-price]
  (let [unit (:unit raw-price)
        price (if (= :per-million-tokens unit)
                (pricing/price raw-price)
                (pricing/service-price raw-price))
        doc (assoc price :service-id service-id :model model)]
    (put-one! store (:price kinds) (str service-id "|" model) doc
              (str "diagnostics-price-" (UUID/randomUUID))
              :diagnostics-price)))

(defn price-for
  "Return one stored price for [SERVICE-ID MODEL], without storage metadata.

  The returned map is the price snapshot a request should keep; it is never
  mutated when the catalog changes later."
  [store service-id model]
  (when (and (some? service-id) (some? model))
    (let [view (read-view store)
          record (first-record view (:price kinds)
                               (str service-id "|" model))]
      (when record
        (dissoc record :storage/id :storage/kind :storage/session-id
                :storage/version :storage/partition :storage/updated-at)))))

(defn- retained-log-entries
  [view policy now]
  (let [cutoff (- now (* (or (:retention-days policy)
                             detail-log/default-retention-days)
                         detail-log/day-ms))]
    (filterv (fn [entry]
               (let [at (:recorded-at entry)]
                 (and (integer? at) (>= at cutoff))))
             (records view (:log-entry kinds)))))

(defn log-policy
  "Read the effective log policy with its retained entries.

  The policy document itself never duplicates entries; the entries are
  reconstructed from their append-only records."
  [store now]
  (let [now (now-ms now)
        view (read-view store)
        policy (detail-log/expire
                (or (first-record view (:log-policy kinds) "policy")
                    (detail-log/new-policy))
                now)]
    (assoc policy :entries (retained-log-entries view policy now))))

(defn save-log-policy!
  [store policy]
  (put-one! store (:log-policy kinds) "policy"
            (assoc policy :entries [])
            (str "diagnostics-log-policy-" (UUID/randomUUID))
            :diagnostics-log-policy))

(defn record-log-entry!
  "Redact and persist one detailed entry with its policy change.

  Returns `{:recorded? false :reason ...}` when the policy refuses it, or
  `{:recorded? true :entry ... :policy ...}` after one atomic commit."
  [store entry opts]
  (let [now (now-ms (:now opts))
        view (read-view store)
        policy (detail-log/expire
                (or (first-record view (:log-policy kinds) "policy")
                    (detail-log/new-policy))
                now)
        id (str (or (:entry-id entry) (:request-id entry)
                    (UUID/randomUUID)))
        existing (first-record view (:log-entry kinds) id)]
    (if existing
      {:recorded? false :reason :duplicate :entry existing :policy policy}
      (let [result (detail-log/record policy entry (assoc opts :now now))]
        (if (:recorded? result)
          (let [commit (put-one-with-policy! store (:log-entry kinds) id
                                             (:entry result)
                                             (assoc (:policy result) :entries [])
                                             (str "diagnostics-log-entry-" id)
                                             :diagnostics-log-entry)]
            (if (= :ok (:status commit))
              (merge commit (select-keys result [:recorded? :reason :entry :policy]))
              commit))
          result)))))

(defn clear-log!
  "Remove detailed entries only; usage records stay."
  [store now]
  (let [now (now-ms now)
        view (read-view store)
        policy (or (first-record view (:log-policy kinds) "policy")
                   (detail-log/new-policy))
        cleared (detail-log/clear policy now)
        ids (mapv :storage/id (records view (:log-entry kinds)))]
    (commit! store
             {:migration-id (str "diagnostics-log-clear-" (UUID/randomUUID))
              :partition partition-id
              :reason :diagnostics-log-clear
              :put [{:kind (:log-policy kinds) :id "policy" :doc cleared}]
              :delete (mapv (fn [id] {:kind (:log-entry kinds) :id id}) ids)})))

(defn expire-log!
  "Delete entries older than the retention window."
  [store now]
  (let [now (now-ms now)
        view (read-view store)
        policy (or (first-record view (:log-policy kinds) "policy")
                   (detail-log/new-policy))
        expired (detail-log/expire policy now)
        cutoff (- now (* (:retention-days expired) detail-log/day-ms))
        keep-ids (set (->> (records view (:log-entry kinds))
                           (filter (fn [entry]
                                     (let [at (:recorded-at entry)]
                                       (and (integer? at) (>= at cutoff)))))
                           (map :storage/id)))
        ids (->> (records view (:log-entry kinds))
                 (remove #(contains? keep-ids (:storage/id %)))
                 (mapv :storage/id))]
    (if (empty? ids)
      {:status :ok :deleted 0}
      (let [commit (commit! store
                            {:migration-id (str "diagnostics-log-expire-"
                                                (UUID/randomUUID))
                             :partition partition-id
                             :reason :diagnostics-log-expire
                             :put [{:kind (:log-policy kinds)
                                    :id "policy"
                                    :doc (assoc expired :entries [])}]
                             :delete (mapv (fn [id]
                                             {:kind (:log-entry kinds)
                                              :id id})
                                           ids)})]
        (assoc commit :deleted (count ids))))))
