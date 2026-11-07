(ns hc.server.artifacts
  "Public facade for delivered artifacts.

  Root `AGENTS.md` §7.1 defines the managed blob store and §7.2/§8.6 define
  the four-step artifact submission.  This namespace owns the second, third
  and fourth steps: completeness/openability checks, durable metadata with
  blob references, and the open/download/provenance projection.

  Bytes never travel on the realtime channel.  An artifact carries a stable
  content ID and a `/blobs/<id>` URL; the client asks the gateway for the
  bytes.  The gateway route is unchanged.

  Typical use:

      (def svc (artifacts/service {:store store :blob-root blob-root}))
      (def result
        (artifacts/register!
         svc {:session-id \"s1\"
              :file \"/work/report.txt\"
              :name \"report.txt\"
              :source-task-id \"t1\"
              :inputs [{:kind :file :path \"data.csv\" :stamp {...}}]}))
      (artifacts/download svc (:artifact result))

  Every expected failure is an `ExceptionInfo` with an `:error` keyword and
  `:checks` where checks were performed."
  (:require [clojure.string :as str]
            [hc.server.artifacts.check :as check]
            [hc.server.artifacts.error :as err]
            [hc.server.artifacts.registry :as registry]
            [hc.server.blob-store :as blob]
            [hc.server.storage :as storage])
  (:import (java.time Instant)
           (java.util UUID)))

;; Construction and registration
(def service registry/service)
(defn register! [service request]
  (registry/register! service request))
(defn register-shell-output! [service request shell-result]
  (registry/register-shell-output! service request shell-result))
(defn retract! [service request]
  (registry/retract! service request))
(defn mark-announced! [service request]
  (registry/mark-announced! service request))
(defn list-artifacts [service request]
  (registry/list-artifacts service request))
(defn get-artifact [service request]
  (registry/get-artifact service request))
(defn public-artifact [doc]
  (registry/public-artifact doc))
(defn announcement [artifact]
  (registry/announcement artifact))
(defn download-url [artifact]
  (registry/download-url artifact))
(defn open-url [artifact]
  (registry/open-url artifact))
(defn annotate-artifact [service artifact]
  (registry/annotate-artifact service artifact))

;; Pure checks, for preflight or diagnostics
(def check-source check/source-snapshot)
(def check-blob check/check-blob!)
(def check-media-type check/media-type-for)
(def safe-download-filename check/safe-download-filename)
(def default-max-bytes check/default-max-bytes)

;; Failure classification
(def expected-error? err/expected?)
(def error-code err/code)
(def error-describe err/describe)

(defn- artifact!
  "Resolve an artifact map or a lookup request.  Never fabricates an entry."
  [service value]
  (cond
    (and (map? value) (:id value) (:content-id value))
    value

    (map? value)
    (or (registry/get-artifact service value)
        (err/fail! :ARTIFACT_NOT_FOUND
                   "artifact metadata does not exist"
                   {:artifact-id (:artifact-id value)
                    :session-id (:session-id value)}))

    :else
    (err/fail! :ARTIFACT_INVALID_REQUEST
               "expected an artifact map or a lookup request"
               {:value value})))

(defn resolve-artifact
  "Resolve an artifact map or lookup request without fabricating an entry."
  [service value]
  (artifact! service value))

(defn- ensure-available!
  [artifact]
  (when-not (= :available (:status artifact))
    (err/fail! :ARTIFACT_NOT_AVAILABLE
               "artifact is not currently available"
               {:artifact-id (:id artifact)
                :status (:status artifact)
                :content-id (:content-id artifact)}))
  (when-not (blob/valid-id? (:content-id artifact))
    (err/fail! :ARTIFACT_INTEGRITY
               "available artifact has no canonical content ID"
               {:artifact-id (:id artifact)
                :content-id (:content-id artifact)}))
  artifact)

(defn- check-current-blob!
  "Re-prove the stored blob against the durable artifact metadata.

  `blob/verify!` alone proves the object still matches its content ID.  It
  does not prove that the artifact document's `:size` and `:sha256` still
  describe those bytes.  A delivery path must check both, or a stale document
  can claim a different size/digest than the URL actually serves."
  [service artifact]
  (check/check-blob! (:blob-root service)
                     (:content-id artifact)
                     {:expected-size (:size artifact)
                      :expected-sha256 (:sha256 artifact)
                      :media-type (:media-type artifact)
                      :text-check-bytes (:text-check-bytes (:opts service))}))

(defn download
  "Return the independent HTTP download descriptor for ARTIFACT.

  `:url` points at the gateway's immutable `/blobs/<content-id>` route.
  `:filename` is the original display name; `:download-filename` is the ASCII
  fallback accepted by the gateway.  The realtime channel only ever carries
  this descriptor, never bytes."
  [service artifact]
  (let [artifact (ensure-available! (artifact! service artifact))]
    (try
      (let [checked (check-current-blob! service artifact)
            metadata (:metadata checked)]
        {:ok? true
         :artifact-id (:id artifact)
         :content-id (:content-id artifact)
         :url (registry/download-url artifact)
         :method :get
         :filename (:filename artifact)
         :download-filename (:download-filename artifact)
         :media-type (:media-type artifact)
         :size (:size metadata)
         :sha256 (:sha256 metadata)
         :etag (str "\"" (:content-id artifact) "\"")
         :range? true
         :cache :immutable
         :provenance {:source-task-id (:source-task-id artifact)
                      :source-call-id (:source-call-id artifact)
                      :inputs (:inputs artifact)
                      :origin (:origin artifact)
                      :storage-version (:storage/version artifact)}})
      (catch clojure.lang.ExceptionInfo t
        (cond
          (err/expected? t) (throw t)
          (blob/expected-error? t)
          (err/fail! (if (= :BLOB_NOT_FOUND (blob/error-code t))
                       :ARTIFACT_BLOB_MISSING
                       :ARTIFACT_INTEGRITY)
                     (.getMessage t)
                     {:artifact-id (:id artifact)
                      :content-id (:content-id artifact)
                      :blob-error (blob/error-code t)}
                     t)
          :else (throw t))))))

(defn open!
  "Open ARTIFACT's finalized bytes for reading.

  Returns `{:ok? true :stream ...}`; the caller owns and must close `:stream`.
  A missing or corrupt blob is an error, never an empty stream."
  [service artifact]
  (let [artifact (ensure-available! (artifact! service artifact))]
    (try
      (let [checked (check-current-blob! service artifact)
            metadata (:metadata checked)
            {:keys [stream size]} (blob/open-stream (:blob-root service)
                                                    (:content-id artifact))]
        (when-not (= (long size) (long (:size metadata)))
          (.close ^java.io.InputStream stream)
          (err/fail! :ARTIFACT_INTEGRITY
                     "blob size changed between verification and open"
                     {:artifact-id (:id artifact)
                      :content-id (:content-id artifact)
                      :expected-size (:size metadata)
                      :actual-size size}))
        {:ok? true
         :stream stream
         :size size
         :sha256 (:sha256 metadata)
         :content-id (:content-id artifact)
         :filename (:filename artifact)
         :media-type (:media-type artifact)
         :etag (str "\"" (:content-id artifact) "\"")
         :artifact artifact})
      (catch clojure.lang.ExceptionInfo t
        (cond
          (err/expected? t) (throw t)
          (blob/expected-error? t)
          (err/fail! (if (= :BLOB_NOT_FOUND (blob/error-code t))
                       :ARTIFACT_BLOB_MISSING
                       :ARTIFACT_INTEGRITY)
                     (.getMessage t)
                     {:artifact-id (:id artifact)
                      :content-id (:content-id artifact)
                      :blob-error (blob/error-code t)}
                     t)
          :else (throw t))))))

(defn read-bytes!
  "Read ARTIFACT into memory with an optional `:max-bytes` guard.

  This is for previews and small exports.  Large files should use `open!` and
  stream through the gateway."
  ([service artifact] (read-bytes! service artifact {}))
  ([service artifact opts]
   (let [artifact (ensure-available! (artifact! service artifact))]
     (try
       (let [bytes (blob/read-bytes (:blob-root service)
                                    (:content-id artifact)
                                    (assoc opts :verify? true))
             size (alength ^bytes bytes)
             expected-size (:size artifact)
             expected-sha (:sha256 artifact)]
         (when (and (integer? expected-size)
                    (not= (long expected-size) (long size)))
           (err/fail! :ARTIFACT_INTEGRITY
                      "artifact metadata size does not match the bytes"
                      {:artifact-id (:id artifact)
                       :content-id (:content-id artifact)
                       :expected-size expected-size
                       :actual-size size}))
         (when (and (string? expected-sha)
                    (not= (str/lower-case expected-sha)
                          (blob/id->hex (:content-id artifact))))
           (err/fail! :ARTIFACT_INTEGRITY
                      "artifact metadata digest does not match its content ID"
                      {:artifact-id (:id artifact)
                       :content-id (:content-id artifact)
                       :expected-sha256 expected-sha}))
         (let [text-check-bytes (long (or (:text-check-bytes (:opts service))
                                          check/default-text-check-bytes))
               prefix (java.util.Arrays/copyOf
                       ^bytes bytes
                       (int (min (long size) text-check-bytes)))]
           (when-not (:ok? (check/encoding-check (:media-type artifact) prefix))
             (err/fail! :ARTIFACT_ENCODING
                        "text artifact does not decode as UTF-8"
                        {:artifact-id (:id artifact)
                         :content-id (:content-id artifact)
                         :media-type (:media-type artifact)})))
         {:ok? true
          :bytes bytes
          :size size
          :content-id (:content-id artifact)
          :media-type (:media-type artifact)
          :artifact artifact})
       (catch clojure.lang.ExceptionInfo t
         (cond
           (err/expected? t) (throw t)
           (blob/expected-error? t)
           (err/fail! (case (blob/error-code t)
                        :BLOB_NOT_FOUND :ARTIFACT_BLOB_MISSING
                        :BLOB_LIMIT_EXCEEDED :ARTIFACT_LIMIT_EXCEEDED
                        :BLOB_INTEGRITY :ARTIFACT_INTEGRITY
                        :ARTIFACT_INTEGRITY)
                      (.getMessage t)
                      {:artifact-id (:id artifact)
                       :content-id (:content-id artifact)
                       :blob-error (blob/error-code t)}
                      t)
           :else (throw t)))))))

(defn verify!
  "Recompute an artifact's blob integrity and openability.

  Returns `{:ok? true :status :available ...}` or
  `{:ok? false :status :missing|:corrupt ...}`.  It does not change durable
  state; use `refresh-status!` for that."
  [service artifact]
  (let [artifact (if (and (map? artifact) (:content-id artifact))
                   artifact
                   (artifact! service artifact))]
    (try
      (let [checked (check-current-blob! service artifact)]
        {:ok? true
         :status :available
         :artifact-id (:id artifact)
         :content-id (:content-id artifact)
         :checks (:checks checked)})
      (catch clojure.lang.ExceptionInfo t
        (let [code (or (err/code t) (blob/error-code t))]
          {:ok? false
           :status (if (contains? #{:BLOB_NOT_FOUND :ARTIFACT_BLOB_MISSING} code)
                     :missing
                     :corrupt)
           :artifact-id (:id artifact)
           :content-id (:content-id artifact)
           :error {:code (or code :ARTIFACT_INTEGRITY)
                   :message (.getMessage ^Throwable t)
                   :data (ex-data t)}
           :checks (or (:checks (ex-data t)) [])})))))

(defn- without-storage-envelope
  [doc]
  (into {} (remove (fn [[k _]] (= "storage" (namespace k)))) doc))

(defn refresh-status!
  "Verify ARTIFACT and optionally persist a changed status.

  `request` identifies the artifact (`:session-id`, `:partition`,
  `:artifact-id`).  With `:persist? true`, a status change is committed as a
  new artifact document; the blob reference is left in place so a corrupt or
  missing object cannot be mistaken for a clean deletion."
  ([service request] (refresh-status! service request {}))
  ([service request {:keys [persist?] :or {persist? false}}]
   (let [raw (or (registry/raw-artifact service request)
                 (err/fail! :ARTIFACT_NOT_FOUND
                            "artifact metadata does not exist"
                            {:artifact-id (:artifact-id request)
                             :session-id (:session-id request)}))
         result (verify! service raw)
         current (:status raw)
         next-status (:status result)]
     (if (and persist? (not= current next-status))
       (let [at (str (Instant/now))
             doc (assoc (without-storage-envelope raw)
                        :status next-status
                        :updated-at at
                        :last-verified-at at
                        :version (inc (long (or (:version raw) 1)))
                        :last-check (:checks result))
             committed (try
                         (storage/commit-migration!
                          (:store service)
                          {:migration-id (str "artifact-status-"
                                              (:id raw) "-"
                                              (UUID/randomUUID))
                           :partition (or (:partition request)
                                          (:storage/partition raw))
                           :session-id (or (:session-id request)
                                           (:storage/session-id raw))
                           :reason :artifact-status
                           :put [{:kind :artifact :id (:id raw) :doc doc}]})
                         (catch clojure.lang.ExceptionInfo t
                           (if (storage/expected-error? t)
                             (err/fail! (or (storage/error-code t)
                                            :ARTIFACT_STATUS_UPDATE_FAILED)
                                        (.getMessage ^Throwable t)
                                        {:storage-data (ex-data t)}
                                        t)
                             (throw t))))]
         {:ok? (:ok? result)
          :status next-status
          :changed? true
          :artifact (registry/public-artifact
                     (or (some #(when (= (:id raw) (:id %)) %)
                               (get-in committed [:view :records :artifact]))
                         doc))
          :checks (:checks result)})
       {:ok? (:ok? result)
        :status next-status
        :changed? false
        :artifact (registry/public-artifact
                   (assoc raw :status next-status))
        :checks (:checks result)}))))
