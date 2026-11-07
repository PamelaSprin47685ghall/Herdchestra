(ns hc.server.blob-store.cleanup
  "Reference-aware reclamation of managed blobs.

  Cleanup is conservative by construction:

  - A reference source is mandatory.  Without one, cleanup refuses rather than
    guessing that every old object is garbage.
  - Only objects older than the safety window are considered.  A finalized
    object may briefly exist before its database reference is committed, so
    the window must cover that transaction and a crash-recovery window.
  - Young objects, referenced objects, and unrecognized filesystem entries are
    never deleted.
  - The whole decision runs under `blob-store.lock/with-store-lock`.  The
    database adapter that commits or removes references must hold the same
    lock; otherwise a reference can race the deletion decision.
  - Every reference is resolved before the first deletion.  A reference lookup
    failure in that first pass aborts the pass with nothing removed.
    Immediately before each delete the reference is checked once more; a
    recheck failure retains that object and stops the pass.  This narrows the
    race for callers that have not yet adopted the shared lock.

  `:dry-run? true` reports the plan without deleting anything."
  (:require [hc.server.blob-store.content :as content]
            [hc.server.blob-store.error :as err]
            [hc.server.blob-store.lock :as lock]
            [hc.server.blob-store.store :as store])
  (:import (java.time Instant)
           (java.util Date)))

(set! *warn-on-reflection* true)

(def default-safety-window-ms
  "Seven days.  Long enough to cover a reference transaction, a process
  restart and an operator noticing a missing artifact."
  (* 7 24 60 60 1000))

(defn- normalize-refs
  [refs]
  (let [ids (cond
              (map? refs) (keys refs)
              (coll? refs) refs
              :else (err/fail! :BLOB_REFERENCE_INVALID
                               "Blob references must be a collection or map of IDs"
                               {:refs refs}))]
    (doseq [id ids]
      (content/assert-id! id))
    (set ids)))

(defn- reference-fn
  "Build an ID predicate from `:refs` or `:referenced?`.

  `:referenced?` wins when both are supplied.  It must be callable with one
  canonical content ID and must return truthy when a current reference exists."
  [{:keys [refs referenced?]}]
  (cond
    (some? referenced?)
    (if (ifn? referenced?)
      referenced?
      (err/fail! :BLOB_REFERENCE_INVALID
                 ":referenced? must be callable with one content ID"
                 {:referenced? referenced?}))

    (some? refs)
    (let [ids (normalize-refs refs)]
      (fn [id] (contains? ids id)))

    :else
    (err/fail! :BLOB_REFERENCE_SOURCE_REQUIRED
               "Blob cleanup requires :refs or :referenced?"
               {:required #{:refs :referenced?}})))

(defn- now-ms
  [now]
  (cond
    (nil? now) (System/currentTimeMillis)
    (number? now) (long now)
    (instance? Instant now) (.toEpochMilli ^Instant now)
    (instance? Date now) (.getTime ^Date now)
    :else (err/fail! :BLOB_CLEANUP_OPTIONS_INVALID
                     ":now must be milliseconds, Instant or Date"
                     {:now now})))

(defn- safety-window-ms
  [opts]
  (let [window (get opts :safety-window-ms default-safety-window-ms)]
    (if (and (number? window) (not (neg? window)))
      (long window)
      (err/fail! :BLOB_CLEANUP_OPTIONS_INVALID
                 ":safety-window-ms must be a non-negative number"
                 {:safety-window-ms window}))))

(defn- dry-run?
  [opts]
  (boolean (get opts :dry-run? false)))

(defn- include-temp?
  [opts]
  (boolean (get opts :include-temp? true)))

(defn- age-ms
  [now entry]
  (- now (:mtime-ms entry)))

(defn- reference-decision
  "Resolve one reference.  A lookup failure aborts the whole pass."
  [ref? entry]
  (try
    (assoc entry :referenced? (boolean (ref? (:id entry))))
    (catch Exception t
      (err/fail! :BLOB_REFERENCE_ERROR
                 "Blob reference lookup failed"
                 {:id (:id entry)}
                 t))))

(defn- delete-error
  [id ^Throwable t]
  {:id id
   :error (or (err/code t) :BLOB_IO)
   :message (.getMessage t)})

(defn- apply-object-deletes
  "Delete planned objects, rechecking each reference first.

  Returns `{:deleted [...] :retained [...] :errors [...]}` and stops at the
  first failure.  A reference that appears during cleanup is retained, not
  deleted."
  [root ref? planned retained]
  (loop [ids (seq planned)
         deleted []
         retained retained
         errors []]
    (if-let [id (first ids)]
      (let [recheck (try
                      [:ok (boolean (ref? id))]
                      (catch Exception t
                        [:error t]))]
        (if (= :error (first recheck))
          ;; The object is not deleted when its reference cannot be checked.
          {:deleted deleted
           :retained (conj retained {:id id :reason :reference-error})
           :errors (conj errors (delete-error id (second recheck)))}
          (if (second recheck)
            (recur (next ids)
                   deleted
                   (conj retained {:id id :reason :referenced-during-cleanup})
                   errors)
            (let [deleted? (try
                             (store/delete! root id)
                             [:ok]
                             (catch Exception t
                               [:error t]))]
              (if (= :error (first deleted?))
                {:deleted deleted
                 :retained retained
                 :errors (conj errors (delete-error id (second deleted?)))}
                (recur (next ids) (conj deleted id) retained errors))))))
      {:deleted deleted
       :retained retained
       :errors errors})))

(defn- apply-temp-deletes
  [root planned errors]
  (loop [paths (seq planned)
         deleted []
         errors errors]
    (if-let [path (first paths)]
      (let [step (try
                   (store/delete-temp! root path)
                   {:kind :deleted}
                   (catch Exception t
                     {:kind :error
                      :error {:path path
                              :error (or (err/code t) :BLOB_IO)
                              :message (.getMessage t)}}))]
        (if (= :error (:kind step))
          {:deleted deleted
           :errors (conj errors (:error step))}
          (recur (next paths) (conj deleted path) errors)))
      {:deleted deleted
       :errors errors})))

(defn cleanup-unlocked!
  "Reclaim old, unreferenced blobs and stale staging files.

  The caller MUST already hold `blob-store.lock/with-store-lock` for `root`.
  This is the entry point for a coordinator that must read the reference set
  and delete under one lock acquisition.  Prefer `cleanup!` otherwise.

  Options:
  - `:refs`             collection or map of currently referenced content IDs
  - `:referenced?`      predicate `(fn [id] ...)`; wins over `:refs`
  - `:safety-window-ms` minimum object age; defaults to seven days
  - `:now`              milliseconds, `Instant`, or `Date` for deterministic tests
  - `:dry-run?`         when true, report the plan and delete nothing (default false)
  - `:include-temp?`    also reclaim stale staging files (default true)

  A reference source is mandatory.  The pass returns a result map with
  `:outcome` one of `:completed`, `:dry-run` or `:partial`.

  A `:refs` collection is a snapshot.  Read it under `with-store-lock`, or
  pass `:referenced?` so the current state is queried while the lock is held.
  `:referenced?` must not call `with-store-lock` again."
  ([root]
   (cleanup-unlocked! root {}))
  ([root opts]
   (let [ref? (reference-fn opts)
            window (safety-window-ms opts)
            now (now-ms (:now opts))
            dry? (dry-run? opts)
            include-temp (include-temp? opts)
            {:keys [objects temp unrecognized]} (store/scan root)
            old? (fn [entry] (>= (age-ms now entry) window))
            old-objects (filterv old? objects)
            young-objects (remove old? objects)
            decisions (mapv #(reference-decision ref? %) old-objects)
            referenced (filter :referenced? decisions)
            candidates (->> decisions (remove :referenced?) (map :id) vec)
            planned-temp (if include-temp
                           (->> temp (filter old?) (map :path) vec)
                           [])
            retained-temp (if include-temp
                            (->> temp
                                 (remove old?)
                                 (mapv #(assoc % :reason :young)))
                            (mapv #(assoc % :reason :temp-cleanup-disabled) temp))
            retained (into (mapv #(assoc (select-keys % [:id :size :path :mtime-ms])
                                         :reason :young)
                                 young-objects)
                           (map #(assoc (select-keys % [:id :size :path :mtime-ms])
                                        :reason :referenced)
                                referenced))
            base {:outcome (if dry? :dry-run :completed)
                  :dry-run? dry?
                  :safety-window-ms window
                  :now now
                  :scanned {:objects (count objects)
                            :temp (count temp)
                            :unrecognized (count unrecognized)}
                  :retained retained
                  :temp-retained retained-temp
                  :unrecognized unrecognized
                  :planned {:objects candidates
                            :temp planned-temp}
                  :deleted []
                  :temp-deleted []
                  :errors []}]
        (if dry?
          base
          (let [object-result (apply-object-deletes root ref? candidates retained)
                temp-result (apply-temp-deletes root planned-temp (:errors object-result))
                errors (:errors temp-result)]
            (assoc base
                   :outcome (if (seq errors) :partial :completed)
                   :retained (:retained object-result)
                   :deleted (:deleted object-result)
                   :temp-deleted (:deleted temp-result)
                   :errors errors))))))

(defn cleanup!
  "Reclaim old, unreferenced blobs and stale staging files.

  This function acquires the root's reference/cleanup lock and then calls
  `cleanup-unlocked!`.  Options are the same as `cleanup-unlocked!`."
  ([root]
   (cleanup! root {}))
  ([root opts]
   (lock/with-store-lock root #(cleanup-unlocked! root opts))))
