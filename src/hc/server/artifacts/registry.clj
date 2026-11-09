(ns hc.server.artifacts.registry
  "Durable artifact metadata and the four-step submission pipeline.

  The domain transaction is the only publish point.  Bytes are finalized in
  the managed blob store first; then one `commit-migration!` writes the
  artifact document and its blob reference together, under the blob store's
  reference lock.  A crash before that commit leaves an unreferenced object
  for the cleaner; a crash after it leaves a complete artifact.  There is no
  third state in which a user sees a half-written file.

  Artifacts are session-scoped: the document lives in `session/<id>` and the
  blob reference is added to the same partition, matching the Owner partition
  convention."
  (:require [clojure.string :as str]
            [hc.server.artifacts.check :as check]
            [hc.server.artifacts.error :as err]
            [hc.server.blob-store :as blob]
            [hc.server.storage :as storage])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Instant)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(def allowed-kinds
  #{:file :log :binary :export :diagnostic :report})

(def allowed-environments
  #{:instance :instance-browser :host-browser :host-desktop :client-browser})

(def defaults
  {:max-bytes check/default-max-bytes
   :session-limit-bytes (* 64 1024 1024)
   :text-check-bytes check/default-text-check-bytes
   :now-fn (fn [] (str (Instant/now)))
   :allow-empty? false
   :follow-links? false})

(defrecord Service [store blob-root opts])

(defn service
  "Build an artifact service.

  Required: `:store` (an open `hc.server.storage` store) and `:blob-root`.
  Options: `:max-bytes`, `:session-limit-bytes`, `:text-check-bytes`,
  `:now-fn`, `:allow-empty?`, `:follow-links?`."
  [{:keys [store blob-root] :as opts}]
  (when-not (and store blob-root)
    (err/fail! :ARTIFACT_INVALID_REQUEST
               "artifact service needs :store and :blob-root"
               {:has-store? (some? store) :has-blob-root? (some? blob-root)}))
  (->Service store (str blob-root) (merge defaults (dissoc opts :store :blob-root))))

(defn- now [service]
  (str ((:now-fn (:opts service)))))

(defn- non-empty-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- check-string [label value max-len]
  (when-not (non-empty-string? value)
    (err/fail! :ARTIFACT_INVALID_REQUEST
               (str label " must be a non-empty string")
               {label value}))
  (when (> (count value) max-len)
    (err/fail! :ARTIFACT_INVALID_REQUEST
               (str label " is too long")
               {label value :max max-len}))
  value)

(defn- partition-for
  [service request]
  (or (:partition request)
      (when-let [session-id (:session-id request)]
        (str "session/" session-id))
      (:default-partition (:opts service))
      "master"))

(defn- normalize-input
  [input]
  (when-not (map? input)
    (err/fail! :ARTIFACT_INVALID_REQUEST
               "each input reference must be a map"
               {:input input}))
  (let [kind (or (:kind input) :reference)
        id (or (:id input) (:path input) (:content-id input))]
    (when-not (or (keyword? kind) (string? kind))
      (err/fail! :ARTIFACT_INVALID_REQUEST
                 "input :kind must be a keyword or string"
                 {:input input}))
    (when-not (non-empty-string? id)
      (err/fail! :ARTIFACT_INVALID_REQUEST
                 "input reference needs an :id, :path or :content-id"
                 {:input input}))
    (assoc input :kind kind)))

(defn- stable-artifact-id
  [content-id file name kind]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest
                       (.getBytes (pr-str [(str content-id) (str file)
                                           (str name) (str kind)])
                                  StandardCharsets/UTF_8))
        hex (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))]
    (str "art-" (subs hex 0 24))))

(defn- normalize-request
  [service request]
  (when-not (map? request)
    (err/fail! :ARTIFACT_INVALID_REQUEST
               "artifact registration needs a request map"
               {:request request}))
  (let [session-id (check-string :session-id (:session-id request) 200)
        file (:file request)
        content-id (:content-id request)
        _ (when (and (nil? file) (nil? content-id))
            (err/fail! :ARTIFACT_INVALID_REQUEST
                       "artifact registration needs :file or :content-id"
                       {:request (select-keys request [:file :content-id])}))
        _ (when (and (some? file) (some? content-id))
            (err/fail! :ARTIFACT_INVALID_REQUEST
                       "artifact registration accepts :file or :content-id, not both"
                       {:request (select-keys request [:file :content-id])}))
        _ (when (and (some? content-id) (not (blob/valid-id? content-id)))
            (err/fail! :ARTIFACT_INVALID_CONTENT_ID
                       "content ID is not a canonical sha256 ID"
                       {:content-id content-id}))
        name (or (:name request)
                 (some-> file (check/path-of) (.getFileName) (str))
                 content-id)
        name (check-string :name name 255)
        filename (check-string :filename (or (:filename request) name) 255)
        kind (or (:kind request) :file)
        artifact-id (or (:artifact-id request)
                        (stable-artifact-id content-id file name kind))
        _ (check-string :artifact-id artifact-id 200)
        _ (when-not (contains? allowed-kinds kind)
            (err/fail! :ARTIFACT_INVALID_REQUEST
                       "unknown artifact kind"
                       {:kind kind :allowed allowed-kinds}))
        environment (or (:environment request) :instance)
        _ (when-not (contains? allowed-environments environment)
            (err/fail! :ARTIFACT_INVALID_REQUEST
                       "unknown environment"
                       {:environment environment :allowed allowed-environments}))
        inputs (mapv normalize-input (or (:inputs request) []))
        tags (mapv str (or (:tags request) []))
        producer (:producer request)]
    (when (and (seq producer)
               (not (non-empty-string? (:source-task-id request))))
      (err/fail! :ARTIFACT_INVALID_REQUEST
                 "a produced artifact needs :source-task-id"
                 {:producer producer
                  :artifact-id artifact-id}))
    {:session-id session-id
     :partition (partition-for service request)
     :artifact-id artifact-id
     :migration-id (or (:migration-id request)
                       (str "artifact-register-" artifact-id))
     :name name
     :filename filename
     :kind kind
     :media-type (:media-type request)
     :file file
     :content-id content-id
     :size (:size request)
     :sha256 (:sha256 request)
     :source-task-id (:source-task-id request)
     :source-call-id (:source-call-id request)
     :producer (or (:producer request) {})
     :truncated? (boolean (:truncated? request))
     :truncated-reason (some-> (:truncated-reason request) str)
     :source-total-bytes (when (and (integer? (:source-total-bytes request))
                                    (not (neg? (long (:source-total-bytes request)))))
                           (:source-total-bytes request))
     :project-id (:project-id request)
     :workspace-root (:workspace-root request)
     :relative-path (:relative-path request)
     :environment environment
     :inputs inputs
     :tags tags
     :allow-empty? (if (contains? request :allow-empty?)
                     (:allow-empty? request)
                     (:allow-empty? (:opts service)))
     :max-bytes (or (:max-bytes request) (:max-bytes (:opts service)))
     :text-check-bytes (or (:text-check-bytes request)
                           (:text-check-bytes (:opts service)))
     :follow-links? (if (contains? request :follow-links?)
                      (:follow-links? request)
                      (:follow-links? (:opts service)))
     :notes (:notes request)}))

;; ---------------------------------------------------------------------------
;; Public projections

(defn download-url
  "Gateway URL for an artifact's immutable bytes.

  The `filename` query parameter is only added when the ASCII fallback is
  accepted by the gateway.  The original name stays in metadata; a browser
  download attribute can still use it."
  [{:keys [status content-id filename download-filename]}]
  (when (and (or (nil? status) (= :available status))
             (string? content-id)
             (blob/valid-id? content-id))
    (let [base (str "/blobs/" content-id)
          safe (or download-filename (check/safe-download-filename filename))]
      (if safe (str base "?filename=" safe) base))))

(defn open-url
  "Inline gateway URL for an artifact's immutable bytes.

  This URL deliberately omits the `filename` query parameter.  The gateway
  turns that parameter into `Content-Disposition: attachment`, which makes a
  browser download the bytes instead of displaying them.  The download action
  keeps `download-url`; the open action uses this URL."
  [{:keys [status content-id]}]
  (when (and (or (nil? status) (= :available status))
             (string? content-id)
             (blob/valid-id? content-id))
    (str "/blobs/" content-id)))

(defn public-artifact
  "The user-visible artifact map.  Durable fields are returned unchanged;
  transport URLs and presentation flags are derived.  This is a projection,
  not a byte proof: `list-artifacts` adds the blob-presence check, and
  `download`/`open!`/`verify!` rehash before delivery."
  [doc]
  (let [media-type (:media-type doc)
        content-id (:content-id doc)
        content-ok? (blob/valid-id? content-id)
        declared-status (:status doc)
        status (if (and (= :available declared-status) (not content-ok?))
                 :incomplete
                 declared-status)
        available? (and (= :available status) content-ok?)
        doc (assoc doc :status status)]
    (cond-> (assoc doc
                   :download-url (when available? (download-url doc))
                   :open-url (when available? (open-url doc))
                   :openable? (and available? (check/browser-openable? media-type))
                   :previewable? (and available? (check/browser-openable? media-type))
                   :downloadable? (and available? content-ok?)
                   :truncated? (boolean (:truncated? doc))
                   :complete? (and available?
                                   (not (boolean (:truncated? doc)))))
      (nil? (:filename doc)) (assoc :filename (:name doc))
      (nil? (:download-filename doc))
      (assoc :download-filename (check/safe-download-filename (:filename doc)))
      (= :incomplete status)
      (assoc :missing-reason "缺少规范内容 ID"))))

(defn announcement
  "CUSTOM event payload that announces a completed artifact.  The caller owns
  the channel; this namespace only builds the event."
  [artifact]
  {:type "CUSTOM"
   :name "hc/artifact-available"
   :value artifact})

;; ---------------------------------------------------------------------------
;; Reads

(defn- view-records
  [service {:keys [partition session-id]}]
  (storage/read-session-view (:store service)
                             (cond-> {:partition partition}
                               session-id (assoc :session-id session-id))))

(defn- without-delivery
  [artifact status reason]
  (assoc artifact
         :status status
         :blob-present? false
         :download-url nil
         :open-url nil
         :openable? false
         :previewable? false
         :downloadable? false
         :complete? false
         :missing-reason reason))

(defn- annotate-blob
  "Make a listed artifact honest about the bytes it still has.

  The durable document may say `:available` while the blob was removed or
  truncated outside the domain transaction.  A snapshot must not advertise a
  link in that case.  A size mismatch is enough to stop delivery; a same-size
  digest corruption is caught by `verify!` or by opening the object."
  [service artifact]
  (if-not (= :available (:status artifact))
    artifact
    (let [id (:content-id artifact)]
      (if-not (blob/valid-id? id)
        (without-delivery artifact :incomplete "缺少规范内容 ID")
        (try
          (let [metadata (blob/metadata (:blob-root service) id)]
            (cond
              (nil? metadata)
              (without-delivery artifact :missing "Blob 不在受管区")

              (not= (:size metadata) (:size artifact))
              (without-delivery artifact :corrupt "Blob 大小与登记不符")

              :else
              (assoc artifact :blob-present? true)))
          (catch clojure.lang.ExceptionInfo t
            (if (blob/expected-error? t)
              (without-delivery artifact :corrupt
                                (or (.getMessage ^Throwable t) "Blob 校验失败"))
              (throw t))))))))

(defn annotate-artifact
  "Apply the same blob-presence projection used by `list-artifacts`."
  [service artifact]
  (annotate-blob service (public-artifact artifact)))

(defn list-artifacts
  "List artifacts for one session (or one partition when `:session-id` is
  absent).  `:status` optionally filters.  Returns `{:ok? true :artifacts
  [...] :version partition-version}`."
  [service {:keys [partition session-id status] :as request}]
  (let [partition (or partition
                      (when session-id (str "session/" session-id))
                      (:default-partition (:opts (:store service)))
                      "master")
        view (view-records service {:partition partition :session-id session-id})
        artifacts (->> (get-in view [:records :artifact] [])
                       (map public-artifact)
                       (map #(annotate-blob service %))
                       (filter (fn [a] (or (nil? status) (= status (:status a)))))
                       (sort-by (fn [a] [(or (:registered-at a) "")
                                         (str (:name a ""))]))
                       vec)]
    {:ok? true
     :artifacts artifacts
     :version (:version view)
     :partition partition
     :session-id session-id}))

(defn raw-artifacts
  "Return the stored artifact documents for one session or partition.

  This is an internal read for status transitions; callers that present an
  artifact to a user should use `list-artifacts` or `public-artifact`."
  [service {:keys [partition session-id]}]
  (let [partition (or partition
                      (when session-id (str "session/" session-id))
                      (:default-partition (:opts (:store service)))
                      "master")
        view (view-records service {:partition partition :session-id session-id})]
    {:ok? true
     :artifacts (vec (get-in view [:records :artifact] []))
     :version (:version view)
     :partition partition
     :session-id session-id}))

(defn raw-artifact
  "Return one stored artifact document, or nil."
  [service {:keys [artifact-id] :as request}]
  (when-let [artifact-id (some-> artifact-id str)]
    (->> (:artifacts (raw-artifacts service request))
         (some #(when (= artifact-id (:id %)) %)))))

(defn get-artifact
  "Return one public artifact, or nil."
  [service {:keys [artifact-id] :as request}]
  (when-let [artifact-id (some-> artifact-id str)]
    (->> (:artifacts (list-artifacts service request))
         (some #(when (= artifact-id (:id %)) %)))))

(defn- session-content-bytes
  [service session-id partition exclude-content-id]
  (->> (:artifacts (list-artifacts service {:session-id session-id
                                            :partition partition}))
       (keep :content-id)
       distinct
       (remove #{exclude-content-id})
       (reduce (fn [total id]
                 (if-let [metadata (try (blob/metadata (:blob-root service) id)
                                        (catch Throwable _ nil))]
                   (+ total (long (:size metadata)))
                   total))
               0)))

;; ---------------------------------------------------------------------------
;; Registration

(defn- doc-for
  [request blob-metadata media-type checks source at]
  (cond-> {:id (:artifact-id request)
           :name (:name request)
           :filename (:filename request)
           :download-filename (check/safe-download-filename (:filename request))
           :kind (:kind request)
           :media-type media-type
           :content-id (:id blob-metadata)
           :sha256 (:sha256 blob-metadata)
           :size (:size blob-metadata)
           :save-location {:kind :managed-blob-store
                           :content-id (:id blob-metadata)
                           :filename (:filename request)
                           :size (:size blob-metadata)}
           :status :available
           :environment (:environment request)
           :producer (:producer request)
           :truncated? (boolean (:truncated? request))
           :inputs (:inputs request)
           :checks checks
           :registered-at at
           :updated-at at
           :version 1
           :announced? false}
    (:source-task-id request) (assoc :source-task-id (:source-task-id request))
    (:source-call-id request) (assoc :source-call-id (:source-call-id request))
    (:project-id request) (assoc :project-id (:project-id request))
    (:relative-path request) (assoc :relative-path (:relative-path request))
    (:workspace-root request) (assoc :workspace-root (:workspace-root request))
    (:notes request) (assoc :notes (:notes request))
    (:truncated-reason request) (assoc :truncated-reason (:truncated-reason request))
    (:source-total-bytes request) (assoc :source-total-bytes (:source-total-bytes request))
    (seq (:tags request)) (assoc :tags (:tags request))
    source (assoc :source-path (str (:path source))
                  :source-stamp (:stamp source)
                  :source-modified-at (:modified-at source)
                  :origin {:workspace-root (:workspace-root request)
                           :path (str (:path source))
                           :real-path (str (:real-path source))
                           :relative-path (:relative-path request)
                           :stamp (:stamp source)
                           :modified-at (:modified-at source)})))

(defn- blob-ref
  [request doc]
  {:id (:content-id doc)
   :size (:size doc)
   :sha256 (:sha256 doc)
   :artifact-id (:id doc)
   :session-id (:session-id request)
   :kind (:kind doc)
   :registered-at (:registered-at doc)})

(defn- storage-failure
  [t]
  (let [code (try (storage/error-code t) (catch Throwable _ nil))]
    (err/fail! (or code :ARTIFACT_REGISTRATION_FAILED)
               (or (.getMessage ^Throwable t) "artifact registration failed")
               {:storage-error code
                :storage-data (ex-data t)
                :details {:cause code
                          :cause-details
                          (not-empty (dissoc (ex-data t)
                                             :error :storage/expected?))}}
               t)))

(defn- blob-failure
  "Translate an expected blob-store failure into the artifact vocabulary.
  Other exceptions keep their identity."
  [t]
  (cond
    (err/expected? t) (throw t)
    (blob/expected-error? t)
    (let [code (blob/error-code t)]
      (err/fail! (case code
                   :BLOB_NOT_FOUND :ARTIFACT_BLOB_MISSING
                   :BLOB_INTEGRITY :ARTIFACT_INTEGRITY
                   :BLOB_LIMIT_EXCEEDED :ARTIFACT_LIMIT_EXCEEDED
                   :ARTIFACT_REGISTRATION_FAILED)
                 (or (.getMessage ^Throwable t) "blob operation failed")
                 {:blob-error code
                  :blob-data (ex-data t)
                  :details {:cause code
                            :cause-details
                            (not-empty (dissoc (ex-data t)
                                               :error :storage/expected?))}}
                 t))
    :else (throw t)))

(defn- commit-artifact!
  [service request doc]
  (try
    (storage/commit-migration!
     (:store service)
     {:migration-id (:migration-id request)
      :partition (:partition request)
      :session-id (:session-id request)
      :reason :artifact-registration
      :put [{:kind :artifact :id (:id doc) :doc doc}]
      :blob-refs {:add [(blob-ref request doc)]}})
    (catch clojure.lang.ExceptionInfo t
      (if (storage/expected-error? t)
        (storage-failure t)
        (throw t)))))

(defn- persisted-artifact
  [service request result doc]
  (or (some #(when (= (:id doc) (:id %)) %)
            (get-in result [:view :records :artifact]))
      (assoc doc
             :storage/version (:version result)
             :storage/partition (:partition request)
             :storage/session-id (:session-id request))))

(defn- ensure-session-capacity!
  [service request size]
  (let [limit (:session-limit-bytes (:opts service))
        existing (session-content-bytes service (:session-id request)
                                        (:partition request)
                                        (:content-id request))]
    (when (> (+ (long existing) (long size)) (long limit))
      (err/fail! :ARTIFACT_SESSION_LIMIT
                 "artifact would exceed the session artifact limit"
                 {:existing-bytes existing
                  :incoming-bytes size
                  :limit limit
                  :session-id (:session-id request)}))))

(defn- source-failure
  [source-check]
  (let [{:keys [error check]} source-check]
    (err/fail! (:code error)
               (:message error)
               (assoc (dissoc error :code :message)
                      :checks [check]))))

(defn- check-source!
  [service request]
  (let [source (or (:source request)
                   (check/source-snapshot
                    (:file request)
                    {:follow-links? (:follow-links? request)}))]
    (when-not (:ok? source)
      (source-failure source))
    (let [size (long (get-in source [:stamp :size]))
          max-bytes (long (:max-bytes request))]
      (when (and (zero? size) (not (:allow-empty? request)))
        (err/fail! :ARTIFACT_EMPTY
                   "empty file is not a delivered artifact; pass :allow-empty? true to accept it"
                   {:path (str (:path source))
                    :checks [{:check :non-empty :ok? false :size 0}]}))
      (when (> size max-bytes)
        (err/fail! :ARTIFACT_LIMIT_EXCEEDED
                   "artifact exceeds the per-artifact limit"
                   {:path (str (:path source))
                    :size size
                    :limit max-bytes}))
      (when (and (some? (:size request))
                 (not= (long (:size request)) size))
        (err/fail! :ARTIFACT_SIZE_MISMATCH
                   "declared size does not match the source file"
                   {:path (str (:path source))
                    :declared (:size request)
                    :actual size}))
      (ensure-session-capacity! service request size)
      source)))

(defn- same-registration?
  "True when an existing artifact is the same submission, not a new one that
  merely reused its ID.  A changed source stamp is a new artifact and must
  conflict, never silently replace delivered bytes."
  [existing request source]
  (and (= :available (:status existing))
       (= (:name existing) (:name request))
       (= (:filename existing) (:filename request))
       (= (:kind existing) (:kind request))
       (= (:environment existing) (:environment request))
       (= (:source-task-id existing) (:source-task-id request))
       (= (:source-call-id existing) (:source-call-id request))
       (= (:project-id existing) (:project-id request))
       (= (:workspace-root existing) (:workspace-root request))
       (= (:relative-path existing) (:relative-path request))
       (= (vec (:inputs existing)) (vec (:inputs request)))
       (= (:producer existing) (:producer request))
       (= (vec (:tags existing)) (vec (:tags request)))
       (or (nil? (:size request))
           (= (:size existing) (:size request)))
       (or (nil? (:sha256 request))
           (= (:sha256 existing) (:sha256 request)))
       (= (boolean (:truncated? existing))
          (boolean (:truncated? request)))
       (= (:truncated-reason existing) (:truncated-reason request))
       (= (:source-total-bytes existing) (:source-total-bytes request))
       (if (:content-id request)
         (= (:content-id existing) (:content-id request))
         (= (get-in existing [:origin :stamp]) (:stamp source)))))

(defn- assert-blob-current!
  "Fail unless ARTIFACT's durable metadata still names its finalized bytes."
  [service artifact]
  (let [id (:content-id artifact)]
    (when-not (blob/valid-id? id)
      (err/fail! :ARTIFACT_INTEGRITY
                 "artifact metadata has no canonical content ID"
                 {:artifact-id (:id artifact) :content-id id}))
    (let [metadata (try
                     (blob/verify! (:blob-root service) id)
                     (catch clojure.lang.ExceptionInfo t
                       (blob-failure t)))]
      (when (nil? metadata)
        (err/fail! :ARTIFACT_BLOB_MISSING
                   "artifact metadata exists but its blob is missing"
                   {:artifact-id (:id artifact) :content-id id}))
      (when-not (:verified? metadata)
        (err/fail! :ARTIFACT_INTEGRITY
                   "blob verifier did not confirm the object"
                   {:artifact-id (:id artifact)
                    :content-id id
                    :verified metadata}))
      (when (not= (:size metadata) (:size artifact))
        (err/fail! :ARTIFACT_INTEGRITY
                   "artifact metadata size does not match the blob"
                   {:artifact-id (:id artifact)
                    :content-id id
                    :expected-size (:size artifact)
                    :actual-size (:size metadata)}))
      (when (and (:sha256 artifact)
                 (not= (:sha256 artifact) (:sha256 metadata)))
        (err/fail! :ARTIFACT_INTEGRITY
                   "artifact metadata digest does not match the blob"
                   {:artifact-id (:id artifact)
                    :content-id id
                    :expected-sha256 (:sha256 artifact)
                    :actual-sha256 (:sha256 metadata)}))
      metadata)))

(defn- replay-result
  "Return an existing artifact only after proving its bytes are still there.

  A durable document can outlive its blob after an out-of-band delete or a
  failed restore.  Replaying the document without this check would let a
  caller announce a result that cannot be downloaded."
  [service existing]
  (assert-blob-current! service existing)
  (let [id (:content-id existing)]
    {:ok? true
     :artifact existing
     :announcement (announcement existing)
     :checks (:checks existing)
     :blob {:id id
            :size (:size existing)
            :sha256 (:sha256 existing)}
     :replayed? true}))

(defn- detect-media-type
  [request prefix]
  (check/normalize-media-type (:media-type request) (:name request) prefix))

(defn- finalize-checks!
  "Replace the provisional encoding check with one that knows the detected
  media type, and append the media-type decision."
  [request media-type prefix checks]
  (let [encoding (check/encoding-check media-type prefix)]
    (when-not (:ok? encoding)
      (err/fail! :ARTIFACT_ENCODING
                 "text artifact does not decode as UTF-8"
                 {:path (:file request)
                  :media-type media-type
                  :checks (conj (vec (remove #(= :encoding (:check %)) checks))
                                encoding)}))
    (conj (vec (remove #(= :encoding (:check %)) checks))
          encoding
          {:check :media-type :ok? true :media-type media-type})))

(defn- register-file!
  [service request]
  (let [source (check-source! service request)
        at (now service)
        result
        (try
          (blob/with-store-lock
            (:blob-root service)
            (fn []
              (let [blob-metadata (blob/put-file! (:blob-root service)
                                                  (:path source)
                                                  {:expected-size (get-in source [:stamp :size])})
                  stable (check/source-stable? source)
                  _ (when-not (:ok? stable)
                      (err/fail! :ARTIFACT_SOURCE_UNSTABLE
                                 "source changed while it was being delivered"
                                 {:path (str (:path source))
                                  :before (:stamp source)
                                  :after (:after stable)
                                  :checks [stable]}))
                  checked (check/check-blob!
                           (:blob-root service)
                           (:id blob-metadata)
                           {:expected-size (:size blob-metadata)
                            :expected-sha256 (or (:sha256 request)
                                                 (:sha256 blob-metadata))
                            :media-type (:media-type request)
                            :text-check-bytes (:text-check-bytes request)})
                  media-type (detect-media-type request (:prefix checked))
                  checks (finalize-checks! request media-type
                                           (:prefix checked) (:checks checked))
                  doc (doc-for request blob-metadata media-type checks source at)
                  _ (ensure-session-capacity!
                     service (assoc request :content-id (:id blob-metadata))
                     (:size blob-metadata))
                  committed (commit-artifact! service request doc)]
                {:blob-metadata blob-metadata
                 :checks checks
                 :doc doc
                 :committed committed})))
          (catch clojure.lang.ExceptionInfo t
            (blob-failure t))
          (catch java.io.IOException t
            (err/fail! :ARTIFACT_INTEGRITY
                       "artifact bytes could not be read during delivery"
                       {:path (str (:path source))}
                       t)))
        committed (:committed result)
        doc (:doc result)
        artifact (persisted-artifact service request committed doc)]
    {:ok? true
     :artifact (public-artifact artifact)
     :announcement (announcement (public-artifact artifact))
     :checks (:checks result)
     :blob (select-keys (:blob-metadata result) [:id :size :sha256])
     :replayed? (boolean (:replayed? committed))}))

(defn- register-existing!
  [service request]
  (let [id (:content-id request)
        at (now service)
        result
        (try
          (blob/with-store-lock
            (:blob-root service)
            (fn []
              (let [metadata (or (blob/metadata (:blob-root service) id)
                                 (err/fail! :ARTIFACT_BLOB_MISSING
                                            "content ID is not present in the managed blob store"
                                            {:content-id id}))
                    size (long (:size metadata))
                    max-bytes (long (:max-bytes request))]
                (when (and (zero? size) (not (:allow-empty? request)))
                  (err/fail! :ARTIFACT_EMPTY
                             "empty blob is not a delivered artifact; pass :allow-empty? true to accept it"
                             {:content-id id :checks [{:check :non-empty :ok? false :size 0}]}))
                (when (> size max-bytes)
                  (err/fail! :ARTIFACT_LIMIT_EXCEEDED
                             "artifact exceeds the per-artifact limit"
                             {:content-id id :size size :limit max-bytes}))
                (ensure-session-capacity! service request size)
                (when (and (some? (:size request))
                           (not= (long (:size request)) size))
                  (err/fail! :ARTIFACT_SIZE_MISMATCH
                             "declared size does not match the blob"
                             {:content-id id :declared (:size request) :actual size}))
                (let [checked (check/check-blob!
                               (:blob-root service) id
                               {:expected-size size
                                :expected-sha256 (:sha256 request)
                                :media-type (:media-type request)
                                :text-check-bytes (:text-check-bytes request)})
                      media-type (detect-media-type request (:prefix checked))
                      checks (finalize-checks! request media-type
                                               (:prefix checked) (:checks checked))
                      doc (doc-for request metadata media-type checks nil at)
                      committed (commit-artifact! service request doc)]
                  {:metadata metadata
                   :checks checks
                   :doc doc
                   :committed committed}))))
          (catch clojure.lang.ExceptionInfo t
            (blob-failure t)))
        committed (:committed result)
        doc (:doc result)
        artifact (persisted-artifact service request committed doc)]
    {:ok? true
     :artifact (public-artifact artifact)
     :announcement (announcement (public-artifact artifact))
     :checks (:checks result)
     :blob (select-keys (:metadata result) [:id :size :sha256])
     :replayed? (boolean (:replayed? committed))}))

(defn register!
  "Run the four-step artifact submission.

  Request keys:
    :session-id      required, stable string
    :file            source file, or
    :content-id      already-finalized canonical blob ID
    :name            display name (defaults to the source filename)
    :kind            :file :log :binary :export :diagnostic :report
    :media-type      optional; detected from the name or prefix otherwise
    :source-task-id  task that produced the artifact
    :source-call-id  tool call that produced it
    :inputs          vector of references (`:kind :id/:path/:content-id`)
    :producer        `{:kind :tool :name ...}` or another stable map
    :project-id, :workspace-root, :relative-path, :environment
    :artifact-id     optional idempotency identity
    :migration-id    optional idempotency key (defaults from :artifact-id)

  Returns `{:ok? true :artifact ... :announcement ... :checks ... :blob ...}`.
  Every expected refusal is an `ExceptionInfo` with an `:error` keyword and
  the checks performed."
  [service request]
  (let [request (normalize-request service request)
        source (when (:file request)
                 (try
                   (check/source-snapshot
                    (:file request)
                    {:follow-links? (:follow-links? request)})
                   (catch java.io.IOException t
                     (err/fail! :ARTIFACT_SOURCE_UNREADABLE
                                "artifact source could not be observed"
                                {:path (str (:file request))}
                                t))))
        _ (when (and source (not (:ok? source)))
            (source-failure source))
        request (cond-> request source (assoc :source source))
        existing (get-artifact service {:session-id (:session-id request)
                                        :partition (:partition request)
                                        :artifact-id (:artifact-id request)})]
    (cond
      (and existing (same-registration? existing request source))
      (replay-result service existing)

      (and existing (not= :available (:status existing)))
      (err/fail! :ARTIFACT_NOT_AVAILABLE
                 "artifact metadata exists but is not available; retract it or use a new artifact ID"
                 {:artifact-id (:artifact-id request)
                  :status (:status existing)
                  :content-id (:content-id existing)})

      existing
      (err/fail! :ARTIFACT_REGISTRATION_CONFLICT
                 "artifact ID is already registered with different metadata"
                 {:artifact-id (:artifact-id request)
                  :existing-content-id (:content-id existing)
                  :incoming-content-id (:content-id request)})

      (:content-id request)
      (register-existing! service request)

      :else
      (register-file! service request))))

(defn register-shell-output!
  "Register the managed capture from a Shell result as a log artifact.

  `shell-result` is the map returned by `hc.server.shell/run!`; its
  `[:output :artifact]` may carry `:path`, `:truncated?`, `:limit` and
  `:error`.  The helper refuses a missing capture or a capture write error,
  and forces `:truncated?` when the capture was cut at the artifact limit.
  Callers still provide session/task/call provenance."
  [service request shell-result]
  (let [capture (get-in shell-result [:output :artifact])
        path (:path capture)
        failure (:error capture)]
    (when failure
      (err/fail! :ARTIFACT_OUTPUT_UNAVAILABLE
                 "Shell artifact capture failed"
                 {:shell-error failure :path path}))
    (when-not (string? path)
      (err/fail! :ARTIFACT_OUTPUT_UNAVAILABLE
                 "Shell result has no artifact capture path"
                 {:shell-result-keys (keys shell-result)}))
    (let [request (merge {:kind :log
                          :name "shell-output.log"
                          :producer {:kind :tool :name "shell"}}
                         request)
          request (assoc request :file path)
          request (if (:truncated? capture)
                    (assoc request
                           :truncated? true
                           :truncated-reason
                           (or (:truncated-reason request)
                               (str "shell artifact limit "
                                    (or (:limit capture) "unknown")
                                    " bytes")))
                    request)]
      (register! service request))))

(defn retract!
  "Remove one artifact document and release its blob reference when no other
  artifact in the same partition still uses the content ID.

  The document deletion and the reference release share one migration, so a
  concurrent cleaner cannot observe a document that still claims the blob
  after the reference is gone.  Bytes are not deleted here; the reference-aware
  cleaner reclaims them after the safety window."
  [service {:keys [session-id partition artifact-id] :as request}]
  (let [outcome
        (try
          (blob/with-store-lock
            (:blob-root service)
            (fn []
              (let [raw (or (raw-artifact service request)
                            (err/fail! :ARTIFACT_NOT_FOUND
                                       "artifact metadata does not exist"
                                       {:artifact-id artifact-id
                                        :session-id session-id}))
                    content-id (:content-id raw)
                    partition (or partition (:storage/partition raw))
                    session-id (or session-id (:storage/session-id raw))
                    others (->> (:artifacts (raw-artifacts
                                            service
                                            {:partition partition
                                             :session-id session-id}))
                                (remove #(= (:id raw) (:id %))))
                    shared? (and content-id
                                 (some #(= content-id (:content-id %)) others))
                    migration (cond-> {:migration-id (str "artifact-retract-"
                                                          (:id raw) "-"
                                                          (UUID/randomUUID))
                                       :partition partition
                                       :session-id session-id
                                       :reason :artifact-retraction
                                       :delete [{:kind :artifact
                                                 :id (:id raw)}]}
                                (and content-id (not shared?))
                                (assoc :blob-refs {:release [content-id]}))
                    result (storage/commit-migration! (:store service)
                                                      migration)]
                {:raw raw
                 :content-id content-id
                 :released? (boolean (and content-id (not shared?)))
                 :shared? (boolean shared?)
                 :version (:version result)})))
          (catch clojure.lang.ExceptionInfo t
            (cond
              (err/expected? t) (throw t)
              (storage/expected-error? t) (storage-failure t)
              (blob/expected-error? t) (blob-failure t)
              :else (throw t))))]
    {:ok? true
     :artifact-id (:id (:raw outcome))
     :content-id (:content-id outcome)
     :released? (:released? outcome)
     :shared? (:shared? outcome)
     :version (:version outcome)}))

(defn- without-storage-envelope
  [doc]
  (into {} (remove (fn [[k _]] (= "storage" (namespace k)))) doc))

(defn mark-announced!
  "Record that the caller has actually announced ARTIFACT to the user.

  Registration itself only produces the `:announcement` event; it must not
  claim the event was delivered.  This transition is idempotent and does not
  change the blob reference."
  [service request]
  (let [raw (or (raw-artifact service request)
                (err/fail! :ARTIFACT_NOT_FOUND
                           "artifact metadata does not exist"
                           {:artifact-id (:artifact-id request)
                            :session-id (:session-id request)}))]
    (when-not (= :available (:status raw))
      (err/fail! :ARTIFACT_NOT_AVAILABLE
                 "only an available artifact may be announced"
                 {:artifact-id (:id raw)
                  :status (:status raw)}))
    (assert-blob-current! service raw)
    (if (:announced? raw)
      {:ok? true
       :artifact (public-artifact raw)
       :changed? false}
      (let [at (now service)
            doc (assoc (without-storage-envelope raw)
                       :announced? true
                       :announced-at at
                       :updated-at at
                       :version (inc (long (or (:version raw) 1))))
            result (try
                     (storage/commit-migration!
                      (:store service)
                      {:migration-id (str "artifact-announced-"
                                          (:id raw) "-"
                                          (UUID/randomUUID))
                       :partition (or (:partition request)
                                      (:storage/partition raw))
                       :session-id (or (:session-id request)
                                       (:storage/session-id raw))
                       :reason :artifact-announcement
                       :put [{:kind :artifact :id (:id raw) :doc doc}]})
                     (catch clojure.lang.ExceptionInfo t
                       (if (storage/expected-error? t)
                         (storage-failure t)
                         (throw t))))
            persisted (or (some #(when (= (:id raw) (:id %)) %)
                                (get-in result [:view :records :artifact]))
                          doc)]
        {:ok? true
         :artifact (public-artifact persisted)
         :changed? true}))))
