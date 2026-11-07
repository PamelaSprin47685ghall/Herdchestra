(ns hc.server.blob-store
  "Public facade for managed blob storage.

  The store owns bytes only.  It deliberately does not own the database
  reference table: §7.1 requires the reference to be established in the same
  domain transaction that publishes the artifact.  The integration contract is

      finalized = (put-bytes! root bytes)
      (with-store-lock root
        (fn []
          ;; commit the domain transaction that records (:id finalized)
          ))

  Cleanup takes a reference set or predicate and runs under the same lock, so
  a reference cannot be committed while its blob is being reclaimed.

  See `src/hc/server/blob_store/AGENTS.md` for the layout, crash windows and
  the reference/cleanup contract."
  (:require [hc.server.blob-store.cleanup :as cleanup]
            [hc.server.blob-store.content :as content]
            [hc.server.blob-store.error :as error]
            [hc.server.blob-store.layout :as layout]
            [hc.server.blob-store.lock :as lock]
            [hc.server.blob-store.refs :as refs]
            [hc.server.blob-store.store :as store]))

;; Stable content identity
(def id-pattern content/id-pattern)
(def algorithm content/algorithm)
(defn valid-id? [id] (content/valid-id? id))
(defn id-of-bytes [bytes] (content/id-of-bytes bytes))
(defn id-of-stream [in] (content/id-of-stream in))
(defn id-of-hex [hex] (content/id-of-hex hex))
(defn id->hex [id] (content/id->hex id))
(defn content-id
  "The stable content ID of a byte array."
  [bytes]
  (content/id-of-bytes bytes))
(defn verify-bytes [id bytes] (content/verify-bytes id bytes))
(defn verify-file [id path] (content/verify-file id path))

;; Staging and atomic finalization
(def stage store/stage)
(def write! store/write!)
(def write-stream! store/write-stream!)
(def commit! store/commit!)
(def abort! store/abort!)
(def put-bytes! store/put-bytes!)
(def put-stream! store/put-stream!)
(def put-file! store/put-file!)

;; Reads and verification
(def metadata store/metadata)
(def exists? store/exists?)
(def open-stream store/open-stream)
(def read-bytes store/read-bytes)
(def verify! store/verify!)
(def scan store/scan)

;; Low-level removal; prefer `cleanup!` unless the last reference is already
;; durably gone.
(def delete! store/delete!)

;; Reference-aware reclamation
(def default-safety-window-ms cleanup/default-safety-window-ms)
(def cleanup! cleanup/cleanup!)
(def cleanup-unlocked!
  "Cleanup pass for a caller that already holds `with-store-lock`."
  cleanup/cleanup-unlocked!)
(def with-store-lock lock/with-store-lock)

;; Reference extraction and diffing (pure; storage coordination lives in
;; `hc.server.blob-store.coordinator`)
(def session-refs refs/session-refs)
(def sessions-refs refs/sessions-refs)
(def storage-doc-refs refs/storage-doc-refs)
(def refs-diff refs/diff)
(def export-refs refs/export-refs)
(def export-ref-entries refs/export-entries)

;; Layout, for diagnostics and artifact metadata
(def root-path layout/root-path)
(def objects-dir layout/objects-dir)
(def temp-dir layout/temp-dir)
(def object-path layout/object-path)
(def object-relative-path layout/object-relative-path)

;; Failure classification
(def expected-error? error/expected?)
(def error-code error/code)
(def error-describe error/describe)
