(ns hc.server.blob-store.refs
  "Reference lifecycle helpers shared by attachments, artifacts and backup.

  The blob store owns bytes.  The current-state store owns the durable
  reference table.  This namespace is the pure part of the bridge:

  - `session-refs` extracts the canonical blob IDs a session document claims,
    including prepared image copies;
  - `diff` turns before/after reference maps into one atomic `:blob-refs`
    add/release operation;
  - `export-refs` reads every partition's reference table from one coherent
    storage export;
  - `export-entries` converts references back to the storage export shape.

  It deliberately does not require `hc.server.storage`: the blob-store core
  stays usable without the Datalevin server classpath.  The coordinated
  cleanup that reads storage lives in `hc.server.blob-store.coordinator`.

  Reference extraction is structural.  It never scans user text for something
  that merely looks like a content ID; only `:blob-id` fields inside known
  attachment positions count."
  (:require [hc.server.blob-store.content :as content]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Session attachment references

(defn- attachment-ref
  "One reference map for an attachment descriptor, or nil.

  A descriptor without a canonical `:blob-id` is a workspace association or
  an unfinished item; it has no blob to retain."
  [attachment]
  (when (map? attachment)
    (let [id (:blob-id attachment)]
      (when (content/valid-id? id)
        (cond-> {:id id :kind :attachment}
          (string? (:attachment-id attachment))
          (assoc :attachment-id (:attachment-id attachment))

          (integer? (:size attachment))
          (assoc :size (:size attachment))

          (string? (:sha256 attachment))
          (assoc :sha256 (:sha256 attachment)))))))

(defn- collect-attachment!
  [state attachment]
  (when-let [ref (attachment-ref attachment)]
    (swap! state update (:id ref) #(or % ref)))
  (when-let [prepared (:prepared attachment)]
    (when (map? prepared)
      (when-let [ref (attachment-ref
                      (assoc prepared :attachment-id (:attachment-id attachment)))]
        (swap! state update (:id ref) #(or % ref)))))
  state)

(defn- collect-attachments!
  [state attachments]
  (doseq [attachment (or attachments [])]
    (collect-attachment! state attachment))
  state)

(defn- values-of
  [value]
  (cond
    (map? value) (vals value)
    (sequential? value) value
    :else []))

(defn session-refs
  "Return `{content-id reference-map}` for every blob one session document
  claims.

  Covered positions: top-level attachments, each draft's attachments, each
  message's attachments, each message version's attachments, and each
  descriptor's `:prepared` image copy.  Duplicate content IDs keep the first
  metadata map; the ID is the identity that matters."
  [session]
  (let [state (atom {})]
    (when (map? session)
      (collect-attachments! state (:attachments session))
      (doseq [draft (values-of (:drafts session))]
        (collect-attachments! state (:attachments draft)))
      (doseq [message (values-of (:messages session))]
        (collect-attachments! state (:attachments message))
        (doseq [version (values-of (:versions message))]
          (collect-attachments! state (:attachments version)))))
    @state))

(defn sessions-refs
  "Union of `session-refs` over a collection of session documents."
  [sessions]
  (reduce (fn [refs session]
            (merge refs (session-refs session)))
          {}
          (or sessions [])))

(defn storage-doc-refs
  "Union of `session-refs` over storage export documents.

  Accepts either `{:doc session}` export entries or bare session maps, so a
  caller can use the same helper before and after export."
  [docs]
  (reduce (fn [refs entry]
            (merge refs (session-refs (or (:doc entry) entry))))
          {}
          (or docs [])))

;; ---------------------------------------------------------------------------
;; Diff and export shapes

(defn diff
  "Compare two `{id ref}` maps.

  Returns `{:add [ref ...] :release [id ...]}` for one atomic storage
  migration.  Add entries carry the richer map; release entries carry only the
  canonical ID."
  [before after]
  (let [before-ids (set (keys before))
        after-ids (set (keys after))]
    {:add (mapv after (sort (remove before-ids after-ids)))
     :release (vec (sort (remove after-ids before-ids)))}))

(defn export-entries
  "Convert `{id ref}` or a reference sequence to storage export blob-ref
  entries `{:id .. :doc ..}`."
  [refs]
  (->> (if (map? refs) (vals refs) refs)
       (map (fn [ref] {:id (:id ref) :doc ref}))
       (sort-by :id)
       vec))

(defn export-refs
  "Read every canonical blob ID from one `storage/export-state` package.

  The package may be reference-only (`:sections #{:blob-refs}`), which avoids
  copying every document into the export map."
  [export]
  (->> (:partitions export)
       vals
       (mapcat :blob-refs)
       (keep (fn [entry]
               (let [id (or (:id entry) (get-in entry [:doc :id]))]
                 (when (content/valid-id? id) id))))
       set))
