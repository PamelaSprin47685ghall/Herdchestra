(ns hc.server.blob-store.coordinator
  "Coordinated blob cleanup over the current-state reference table.

  The blob store's cleanup must observe a stable reference set while it
  decides what to delete.  Reference commits in `hc.server.storage` use
  `hc.server.blob-store.lock/with-store-lock`; this namespace takes the same
  lock, reads one coherent storage export, and runs the cleaner without
  re-acquiring the lock.

  The export is a full current-state read, so this is a maintenance operation,
  not a hot-path call.  A storage failure aborts before any deletion."
  (:require [clojure.string :as str]
            [hc.server.blob-store.cleanup :as cleanup]
            [hc.server.blob-store.error :as err]
            [hc.server.blob-store.lock :as lock]
            [hc.server.blob-store.refs :as refs]
            [hc.server.storage :as storage]))

(set! *warn-on-reflection* true)

(defn storage-refs
  "Read the current reference set from storage without deleting anything.

  This is a diagnostic/read helper.  A cleanup must use `cleanup-storage!`
  so the read and the delete share one lock."
  [store]
  (refs/export-refs (storage/export-state store {:sections #{:blob-refs}})))

(defn cleanup-storage!
  "Reclaim blobs that no current-state partition references.

  The reference set comes from one `storage/export-state` read under the
  blob-store lock.  Options are passed to
  `blob-store.cleanup/cleanup-unlocked!` except `:refs`, which this function
  always derives from storage.

  A storage export failure aborts before any deletion.  The result includes
  `:storage {:partitions n :refs n}` so an operator can see what was read."
  ([store blob-root]
   (cleanup-storage! store blob-root {}))
  ([store blob-root opts]
   (when-not store
     (err/fail! :BLOB_CLEANUP_OPTIONS_INVALID
                "Coordinated cleanup needs a storage handle"
                {:store store}))
   (when-not (and (string? (str blob-root)) (not (str/blank? (str blob-root))))
     (err/fail! :BLOB_CLEANUP_OPTIONS_INVALID
                "Coordinated cleanup needs a blob root"
                {:blob-root blob-root}))
   (lock/with-store-lock
    blob-root
    (fn []
      (let [export (storage/export-state store {:sections #{:blob-refs}})
            refs (refs/export-refs export)
            result (cleanup/cleanup-unlocked! blob-root
                                             (assoc opts :refs refs))]
        (assoc result
               :storage {:partitions (count (:partitions export))
                         :refs (count refs)}))))))
