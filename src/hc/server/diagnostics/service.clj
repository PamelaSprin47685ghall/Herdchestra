(ns hc.server.diagnostics.service
  "Application-facing diagnostics service.

  Composition only: this namespace joins the storage adapter, statistics,
  detailed-log policy, redaction, and local bundle export.  It performs no
  network I/O and has no upload path.  The application injects an
  `:artifact-writer` when it wants exported bundles stored in the managed
  blob area; the writer receives bytes and returns a content ID."
  (:require [clojure.string :as str]
            [hc.server.diagnostics.bundle :as bundle]
            [hc.server.diagnostics.log :as detail-log]
            [hc.server.diagnostics.pricing :as pricing]
            [hc.server.diagnostics.stats :as stats]
            [hc.server.diagnostics.store :as store]
            [hc.server.storage :as storage])
  (:import (java.io ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util UUID)))

(defn service
  "Create a diagnostics service.

  Options:
    :store            open `hc.server.storage` store
    :now-fn           () -> epoch ms
    :app-version      display version
    :platform         OS platform
    :os-version       OS version
    :artifact-writer  optional `(fn [filename bytes] -> {:id ...})`"
  [{:keys [store now-fn app-version platform os-version artifact-writer]}]
  {:store store
   :now-fn (or now-fn #(System/currentTimeMillis))
   :app-version app-version
   :platform platform
   :os-version os-version
   :artifact-writer artifact-writer
   :lock (Object.)
   :policy (atom nil)
   :issued-bundles (atom {})})

(defn- now
  [service]
  ((:now-fn service)))

(defn- snapshot
  [service]
  (try
    (store/snapshot (:store service) (now service))
    (catch clojure.lang.ExceptionInfo t
      (let [code (or (try (storage/error-code t)
                          (catch Throwable _ nil))
                     :DIAGNOSTICS_READ_FAILED)]
        {:status :error
         :error {:code code
                 :message (or (ex-message t) "diagnostics read failed")
                 :details {:cause code
                           :cause-details
                           (not-empty (dissoc (ex-data t)
                                              :error :storage/expected?))}}}))))

(defn statistics
  "Read statistics on demand.  A failed read is explicit and retryable."
  [service query]
  (let [snap (snapshot service)]
    (if (= :ok (:status snap))
      (stats/summary snap (assoc (or query {}) :now (now service)))
      (assoc (stats/unavailable (get-in snap [:error :message]))
             :error (:error snap)))))

(defn revision
  "Cheap partition version for the publisher's component cache.

  Returns nil when the diagnostics view cannot be read; the caller then
  rebuilds the baseline instead of caching a failed read."
  [service]
  (try
    (:version (store/read-view (:store service)))
    (catch Throwable _ nil)))

(defn log-status
  "Cheap baseline status.  The policy is cached after attach and after each
  log command; full statistics remain on demand."
  [service]
  (try
    ;; Retention is a delete guarantee, not only a read filter.  Status is
    ;; the cheap baseline the application already polls, so it is the right
    ;; place to expire old entries without a background thread.
    (try (store/expire-log! (:store service) (now service))
         (catch Throwable _ nil))
    (reset! (:policy service) nil)
    (let [policy (or @(:policy service)
                     (let [loaded (store/log-policy (:store service)
                                                    (now service))]
                       (reset! (:policy service) loaded)
                       loaded))]
      (detail-log/status policy (now service)))
    (catch Throwable t
      {:enabled? false
       :paused? false
       :capturing? false
       :retention-days detail-log/default-retention-days
       :entry-count 0
       :read-error {:code :DIAGNOSTICS_LOG_READ_FAILED
                    :message (or (ex-message t) (str t))
                    :details {:cause (or (:code (ex-data t))
                                         (:error (ex-data t))
                                         :DIAGNOSTICS_LOG_READ_FAILED)
                              :cause-details
                              (not-empty (dissoc (ex-data t)
                                                 :code :error
                                                 :storage/expected?))}}})))

(defn- policy-op!
  [service op]
  (locking (:lock service)
    (let [snap (snapshot service)]
      (if-not (= :ok (:status snap))
        {:ok? false :error (:error snap)}
        (let [policy (:log-policy snap)
              next-policy (op policy)
              result (store/save-log-policy! (:store service) next-policy)]
          (if (= :ok (:status result))
            (do
              (reset! (:policy service) next-policy)
              {:ok? true
             :receipt {:status :accepted
                       :log (detail-log/status next-policy (now service))}})
            {:ok? false :error (:error result)}))))))

(defn enable-log!
  [service opts]
  (policy-op! service #(detail-log/enable % (assoc opts :now (now service)))))

(defn disable-log!
  [service]
  (policy-op! service detail-log/disable))

(defn pause-log!
  [service]
  (policy-op! service detail-log/pause))

(defn resume-log!
  [service]
  (policy-op! service #(detail-log/resume % (now service))))

(defn clear-log!
  [service]
  (locking (:lock service)
    (let [result (store/clear-log! (:store service) (now service))]
      (if (= :ok (:status result))
        (let [policy (detail-log/clear (or @(:policy service)
                                           (store/log-policy (:store service)
                                                             (now service)))
                                       (now service))]
          (reset! (:policy service) policy)
          {:ok? true :receipt {:status :accepted
                               :log (detail-log/status policy (now service))}})
        {:ok? false :error (:error result)}))))

(defn expire-log!
  [service]
  (locking (:lock service)
    (let [result (store/expire-log! (:store service) (now service))]
      (if (= :ok (:status result))
        (let [policy (detail-log/expire
                      (or @(:policy service)
                          (store/log-policy (:store service) (now service)))
                      (now service))]
          (reset! (:policy service) policy)
          {:ok? true :receipt {:status :accepted
                               :deleted (:deleted result)
                               :log (detail-log/status policy (now service))}})
        {:ok? false :error (:error result)}))))

(defn record-log!
  "Record one detailed entry under the current policy."
  [service entry]
  (locking (:lock service)
    (let [result (store/record-log-entry! (:store service) entry
                                          {:now (now service)})]
      (when (and (:recorded? result) (:policy result))
        (reset! (:policy service) (:policy result)))
      result)))

(defn- log-entry
  [request]
  (select-keys request
               [:request-id :turn-id :session-id :assistant-id :service-id
                :service-name :model :purpose :result :retry-of :recovery-of
                :at :started-at :finished-at :usage :error :finish-reason
                :temporary? :request-structure]))

(defn record-request!
  "Persist one request and, when detailed logging is enabled, one redacted
  log entry.  The price snapshot is taken at record time so a later catalog
  change cannot rewrite this request's estimate."
  [service request]
  (let [request (if (:price request)
                  request
                  (if-let [price (store/price-for (:store service)
                                                  (:service-id request)
                                                  (:model request))]
                    (assoc request :price price)
                    request))
        result (store/record-request! (:store service) request)]
    (when (= :ok (:status result))
      (record-log! service (log-entry request)))
    result))

(defn record-user-send!
  [service send]
  (store/record-user-send! (:store service) send))

(defn record-tool-call!
  [service tool]
  (store/record-tool-call! (:store service) tool))

(defn record-service-usage!
  "Persist one non-token usage.  A stored service price is attached only when
  its unit is a service unit; a token price is never applied to seconds,
  images, or calls."
  [service usage]
  (let [usage (if (:price usage)
                usage
                (if-let [price (store/price-for (:store service)
                                                (:service-id usage)
                                                (:name usage))]
                  (if (contains? pricing/service-units (:unit price))
                    (assoc usage :price price)
                    usage)
                  usage))]
    (store/record-service-usage! (:store service) usage)))

(defn save-price!
  [service service-id model price]
  (store/save-price! (:store service) service-id model price))

(defn- log-material
  [snap]
  (when (seq (:log-entries snap))
    {:id :detailed-log
     :title "Detailed request log"
     :purpose "Show request metadata and redacted parameters"
     :kind :log
     :include? false
     :content (:log-entries snap)}))

(defn- merge-log-material
  "Merge one server-owned log material with the client's request by id.

  The server keeps title, kind, purpose default, and content.  The client may
  only set `:include?` and override the purpose with a non-blank value; a
  client-supplied `:content` is never used."
  [materials log]
  (let [requested (some #(when (= (:id log) (:id %)) %) materials)
        purpose (not-empty (str (:purpose requested)))]
    (conj (vec (remove #(= (:id log) (:id %)) materials))
          (cond-> log
            requested (assoc :include? (true? (:include? requested)))
            purpose (assoc :purpose purpose)))))

(defn- bundle-digest
  [bundle]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest
                       (.getBytes (pr-str (bundle/export-view bundle))
                                  StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))))

(defn- issue-bundle!
  "Record the exact preview content under a server-issued bundle id.

  The client may edit the preview, but every edit goes back through this
  function.  Export accepts only a bundle whose current content digest is
  still the one issued here."
  [service bundle]
  (let [bundle-id (or (:bundle-id bundle)
                      (str "diag-" (UUID/randomUUID)))
        bundle (assoc bundle :bundle-id bundle-id)]
    (swap! (:issued-bundles service) assoc bundle-id (bundle-digest bundle))
    bundle))

(defn- edit-bundle!
  [service bundle f]
  (issue-bundle! service (f bundle)))

(defn prepare-bundle
  "Prepare a local-only diagnostic preview with app facts and log material."
  [service request]
  (let [snap (snapshot service)
        request (assoc (or request {})
                       :bundle-id (str "diag-" (UUID/randomUUID)))
        materials (if-let [log (and (= :ok (:status snap))
                                     (log-material snap))]
                    (merge-log-material (vec (:extra-materials request)) log)
                    ;; No server log exists: never let a client fabricate the
                    ;; detailed-log material or its content.
                    (vec (remove #(= :detailed-log (:id %))
                                 (:extra-materials request))))
        prepared (bundle/prepare
                  (merge {:generated-at (now service)
                          :app-version (:app-version service)
                          :platform (:platform service)
                          :os-version (:os-version service)}
                         request
                         {:extra-materials materials}))]
    (issue-bundle! service prepared)))

(defn export-bundle!
  "Write a reviewed bundle to the caller's local artifact sink.

  The service never uploads.  Without `:artifact-writer` it returns the EDN
  artifact map for the caller to save; with a writer it stores bytes in the
  managed blob area and returns only the content ID."
  [service bundle]
  (let [bundle-id (:bundle-id bundle)
        expected (get @(:issued-bundles service) bundle-id)
        actual (when bundle-id (bundle-digest bundle))]
    (cond
      (nil? bundle-id)
      {:status :refused
       :error {:code :DIAGNOSTICS_BUNDLE_NOT_REVIEWED
               :message "a server-issued preview is required before export"}}

      (nil? expected)
      {:status :refused
       :error {:code :DIAGNOSTICS_BUNDLE_NOT_REVIEWED
               :message "this preview was not issued by this diagnostics service"}}

      (not= expected actual)
      {:status :refused
       :error {:code :DIAGNOSTICS_BUNDLE_CHANGED
               :message "the preview changed after it was reviewed; prepare it again"}}

      :else
      (do
        (swap! (:issued-bundles service) dissoc bundle-id)
        (if-let [writer (:artifact-writer service)]
          (let [out (ByteArrayOutputStream.)
                artifact (bundle/write-local-artifact! bundle out)
                bytes (.toByteArray out)
                stored (writer (:filename artifact) bytes)]
            {:status :exported
             :artifact (merge {:filename (:filename artifact)
                               :size (:size artifact)}
                              stored)
             :uploaded? false
             :automatic-upload? false
             :requires-user-action? true})
          {:status :local
           :artifact (bundle/local-artifact bundle)
           :uploaded? false
           :automatic-upload? false
           :requires-user-action? true})))))

(defn handle!
  "Handle one `:diagnostics/*` command payload.

  Returns `{:ok? true :receipt ...}` or `{:ok? false :error ...}`.  The
  application router owns delivery; this namespace owns the result shape."
  [service payload]
  (let [kind (:kind payload)]
    (case kind
      :diagnostics/statistics
      {:ok? true
       :receipt {:status :complete
                 :statistics (statistics service (:query payload))}}

      :diagnostics/log-status
      {:ok? true :receipt {:status :complete :log (log-status service)}}

      :diagnostics/log-enable
      (enable-log! service (select-keys payload [:mode :days]))

      :diagnostics/log-disable
      (disable-log! service)

      :diagnostics/log-pause
      (pause-log! service)

      :diagnostics/log-resume
      (resume-log! service)

      :diagnostics/log-clear
      (clear-log! service)

      :diagnostics/bundle-preview
      (let [preview (prepare-bundle service (:bundle payload))]
        {:ok? true
         :receipt {:status :complete :bundle preview}})

      :diagnostics/bundle-remove-lines
      (if-let [preview (:bundle payload)]
        {:ok? true
         :receipt {:status :complete
                   :bundle (edit-bundle!
                            service preview
                            #(bundle/remove-lines % (:line-ids payload)))}}
        {:ok? false
         :error {:code :DIAGNOSTICS_BUNDLE_REQUIRED
                 :message "a preview bundle is required"}})

      :diagnostics/bundle-rename
      (if-let [preview (:bundle payload)]
        {:ok? true
         :receipt {:status :complete
                   :bundle (edit-bundle!
                            service preview
                            #(bundle/rename % (:rename payload)))}}
        {:ok? false
         :error {:code :DIAGNOSTICS_BUNDLE_REQUIRED
                 :message "a preview bundle is required"}})

      :diagnostics/bundle-hide-hosts
      (if-let [preview (:bundle payload)]
        {:ok? true
         :receipt {:status :complete
                   :bundle (edit-bundle! service preview bundle/hide-hosts)}}
        {:ok? false
         :error {:code :DIAGNOSTICS_BUNDLE_REQUIRED
                 :message "a preview bundle is required"}})

      :diagnostics/bundle-hide-paths
      (if-let [preview (:bundle payload)]
        {:ok? true
         :receipt {:status :complete
                   :bundle (edit-bundle! service preview bundle/hide-paths)}}
        {:ok? false
         :error {:code :DIAGNOSTICS_BUNDLE_REQUIRED
                 :message "a preview bundle is required"}})

      :diagnostics/bundle-export
      (if-let [bundle (:bundle payload)]
        (let [result (export-bundle! service bundle)]
          (if (= :refused (:status result))
            {:ok? false :error (:error result)}
            {:ok? true :receipt result}))
        {:ok? false
         :error {:code :DIAGNOSTICS_BUNDLE_REQUIRED
                 :message "a reviewed preview bundle is required for export"}})

      :diagnostics/save-price
      (let [result (save-price! service (:service-id payload)
                                (:model payload) (:price payload))]
        (if (= :ok (:status result))
          {:ok? true
           :receipt {:status :accepted
                     :service-id (:service-id payload)
                     :model (:model payload)}}
          {:ok? false :error (:error result)}))

      {:ok? false
       :error {:code :DIAGNOSTICS_KIND_UNKNOWN
               :message "unknown diagnostics command kind"
               :details {:kind kind}}})))
