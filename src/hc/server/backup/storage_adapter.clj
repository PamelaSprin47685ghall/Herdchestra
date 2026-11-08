(ns hc.server.backup.storage-adapter
  "A real `hc.server.backup.port/DomainPort` over `hc.server.storage`.

  The storage facade owns the current state and exposes a complete,
  format-versioned export.  This adapter maps that export into the neutral
  backup categories and maps a restore plan back into a new complete export.

  Mapping (storage partition / kind -> backup category):

    sessions / :session-record           -> :conversations
    session/<id> / :owner-state          -> :conversations (nested)
    config / :app-config                 -> :assistants, :connections,
                                            :appearance
    materials / :materials-memory        -> :memories
    materials / :materials-worldbook-*   -> :worldbooks
    materials / :materials-instruction-* -> :appearance
    backup / :backup-import              -> :imports
    scheduler / :scheduler-*             -> :schedules
    extension catalog :skills            -> :skills (+ managed files)
    extension catalog :mcp :servers      -> :tools
    blob objects referenced by messages  -> :attachments
    workspace registry / managed files   -> :workspaces

  Tools and skills come from the durable extension catalog and the managed
  skill-file root.  Schedules come from the scheduler storage partition.
  Attachment bytes come from the blob store; a referenced blob is verified
  against its content ID, and a missing/ref-only attachment is reported as
  incomplete rather than fabricated.

  `:workspaces` is backed by an explicit `WorkspacePort` (normally
  `hc.server.backup.workspace/store`).  Managed workspace files are copied
  when `:workspaces` is selected.  Linked external workspaces contribute only
  their label and path unless `:include-external-files? true`; even then the
  restored files go to an app-owned import directory, and the registry entry
  is `:relink-required? true :authorized? false`.  The original external path
  is never created or authorized.  A selected `:workspaces` category without
  a workspace port fails `:BACKUP_WORKSPACES_UNAVAILABLE`; it is never
  reported as a fake empty category.

  `apply-restore!` is a whole-store operation.  It reads the current export,
  replaces only the categories in the plan, and calls
  `hc.server.storage/restore-state!` with one atomic transaction.  Storage
  requires quiescence: the application must stop the Owner and every writer
  before calling it.  Extension catalog/skill files and blob objects are
  applied around that transaction; a failure after the storage commit is
  reported as failed with the recovery point, never as success.  The adapter
  accepts either a `:quiescent? true` plan or a domain constructed with
  `{:quiescent? true}` (tests and offline restore)."
  (:require [clojure.string :as str]
            [hc.server.backup.model :as model]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.error :as err]
            [hc.server.backup.port :as port]
            [hc.server.backup.workspace :as workspace]
            [hc.server.blob-store :as blob-store]
            [hc.server.blob-store.refs :as blob-refs]
            [hc.server.extensions.durable :as extensions-durable]
            [hc.server.extensions.skill.store :as skill-store]
            [hc.server.gateway.ports :as blob-port]
            [hc.server.storage :as storage])
  (:import (java.io ByteArrayInputStream)
           (java.time Instant)
           (java.util UUID)))

(defrecord StorageDomain [store opts])

(defn domain
  "Create a DomainPort over an open storage handle.

  Options:
    :quiescent?       true only when the caller has stopped all writers.  A
                      restore plan may also carry `:quiescent? true`.
    :extensions-root  extension catalog/skill root (default disabled)
    :extensions       an existing extension component; its :repository is used
    :blob-store       a `hc.server.gateway.ports/BlobStorePort`
    :blob-root        local blob root; used to build a BlobStorePort when
                      :blob-store is absent
    :workspace-store  a `hc.server.backup.workspace/WorkspacePort`
    :workspace-store-root  local workspace registry root when no port is given
    :workspaces       extra workspace descriptors for the file store"
  ([store] (domain store {}))
  ([store opts]
   (when-not (storage/store-open? store)
     (err/fail! :BACKUP_STORAGE_CLOSED
                "Backup adapter needs an open storage handle"
                {:path (try (storage/store-path store) (catch Exception _ nil))}))
   (let [repository (or (:extensions-repository opts)
                        (some-> (:extensions opts) :repository)
                        (some-> (:extensions-root opts) extensions-durable/open))
         blob-store (or (:blob-store opts)
                        (some-> (:blob-root opts) blob-port/blob-store-port))
         workspace-store (or (:workspace-store opts)
                             (when (:workspace-store-root opts)
                               (workspace/store (:workspace-store-root opts)
                                                {:workspaces (:workspaces opts)})))]
     (->StorageDomain store (assoc opts
                                   :extensions-repository repository
                                   :blob-store blob-store
                                   :workspace-store workspace-store
                                   :max-attachment-bytes
                                   (long (or (:max-attachment-bytes opts)
                                             (get model/default-policy
                                                  :max-attachment-bytes)))
                                   :max-attachment-total-bytes
                                   (long (or (:max-attachment-total-bytes opts)
                                             (get model/default-policy
                                                  :max-attachment-total-bytes))))))))

;; ---------------------------------------------------------------------------
;; Export helpers

(defn- docs
  [export partition kind]
  (filterv #(= kind (:kind %)) (get-in export [:partitions partition :docs] [])))

(defn- doc-entry
  [export partition kind id]
  (first (filter #(and (= kind (:kind %)) (= id (:id %)))
                 (get-in export [:partitions partition :docs] []))))

(defn- doc-value
  [export partition kind id]
  (:doc (doc-entry export partition kind id)))

(defn- config-doc
  [export]
  (or (doc-value export "config" :app-config "config") {}))

(defn- materials-docs
  [export kind]
  (docs export "materials" kind))

(defn- session-records
  [export]
  (docs export "sessions" :session-record))

(defn- owner-state
  [export session-id]
  (doc-value export (str "session/" session-id) :owner-state session-id))

(defn- attachment-ids
  [session]
  (letfn [(values-of [x]
            (cond
              (map? x) (vals x)
              (sequential? x) x
              :else []))
          (ids [refs] (keep :attachment-id (or refs [])))
          (message-ids [message]
            (concat (ids (:attachments message))
                    (mapcat #(ids (:attachments %))
                            (values-of (:versions message)))))]
    (->> (concat (ids (:attachments session))
                 (mapcat #(ids (:attachments %)) (values-of (:drafts session)))
                 (mapcat message-ids (values-of (:messages session))))
       distinct
       vec)))

(defn- conversation-item
  [export session-entry]
  (let [session (:doc session-entry)
        session-id (:session-id session)
        attachments (attachment-ids session)]
    (cond-> (assoc session
                   :id session-id
                   :owner-state (owner-state export session-id)
                   :temporary? (= :temporary (:status session))
                   :deleted? (or (= :deleted (:status session))
                                 (some? (:deleted-at session)))
                   :cache? false)
      (seq attachments) (assoc :attachment-ids attachments))))

(defn- assistant-items
  [config]
  (mapv (fn [[id assistant]]
          (assoc assistant :id id :assistant-id (or (:assistant-id assistant) id)))
        (:assistants config)))

(defn- connection-items
  [config]
  (let [connections (:connections config)
        ;; The settings document stores a vector; older fixtures use an id
        ;; map.  Both shapes must export the same connection identity.
        entries (if (map? connections)
                  (map (fn [[id connection]] [(str id) connection]) connections)
                  (map (fn [connection] [(:id connection) connection])
                       connections))]
    (cond-> (mapv (fn [[id connection]]
                    (assoc connection :id id
                           :connection-id (or (:connection-id connection) id)))
                  entries)
      (seq (:role-bindings config))
      (conj {:id "role-bindings"
             :kind :role-bindings
             :name "Model role bindings"
             :internal? true
             :role-bindings (:role-bindings config)}))))

(defn- config-remainder
  [config]
  ;; `:backup` holds this device's backup-target configuration (including
  ;; remote credentials).  It is device-local like launch provenance: a
  ;; restored device must not inherit the source device's targets, so the
  ;; key never enters the portable remainder.
  (dissoc config :assistants :connections :connection-order :role-bindings
          :backup :revision :created-at :updated-at))

(defn- appearance-items
  [export]
  (let [config (config-doc export)
        remainder (config-remainder config)
        root (doc-value export "materials" :materials-root "root")
        cards (materials-docs export :materials-instruction-card)
        sessions (materials-docs export :materials-instruction-session)]
    (cond-> []
      (seq remainder)
      (conj {:id "app-config"
             :kind :app-config
             :name "Application configuration"
             :internal? true
             :config remainder})

      (some? root)
      (conj {:id "materials-root"
             :kind :materials-root
             :name "Materials settings"
             :internal? true
             :root root})

      (seq cards)
      (into (mapv (fn [entry]
                    {:id (:id entry)
                     :kind :instruction-card
                     :name (str "Instruction card " (:id entry))
                     :internal? true
                     :card (:doc entry)})
                  cards))

      (seq sessions)
      (into (mapv (fn [entry]
                    {:id (:id entry)
                     :kind :instruction-session
                     :name (str "Instruction selection " (:id entry))
                     :internal? true
                     :selection (:doc entry)})
                  sessions)))))

(defn- worldbook-items
  [export]
  (let [books (materials-docs export :materials-worldbook-book)
        entries (materials-docs export :materials-worldbook-entry)
        sessions (materials-docs export :materials-worldbook-session)
        entries-by-book (group-by #(get-in % [:doc :book-id]) entries)
        book-ids (set (map :id books))]
    (vec
     (concat
      (mapv (fn [entry]
              (let [id (:id entry)
                    book (:doc entry)]
                (assoc book
                       :id id
                       :entries (mapv :doc (get entries-by-book id []))
                       :sessions [])))
            books)
      (mapv (fn [entry]
              {:id (:id entry)
               :kind :worldbook-entry
               :entry (:doc entry)})
            (remove #(contains? book-ids (get-in % [:doc :book-id])) entries))
      (mapv (fn [entry]
              {:id (:id entry)
               :kind :worldbook-session
               :session (:doc entry)})
            sessions)))))

(defn- memory-items
  [export]
  (mapv (fn [entry] (assoc (:doc entry) :id (:id entry)))
        (materials-docs export :materials-memory)))

(defn- import-items
  [export]
  (into {}
        (map (fn [entry] [(:id entry) (:doc entry)]))
        (docs export "backup" :backup-import)))

(defn- extension-repository
  [domain]
  (get-in domain [:opts :extensions-repository]))

(defn- load-extensions
  [domain]
  (when-let [repository (extension-repository domain)]
    (let [loaded (extensions-durable/load! repository)]
      (if (:ok? loaded)
        loaded
        (err/fail! :BACKUP_EXTENSIONS_UNAVAILABLE
                   "The extension catalog could not be read"
                   {:error (:error loaded)})))))

(defn- tool-items
  [catalog]
  (mapv (fn [[id server]] (assoc server :id id))
        (get-in catalog [:mcp :servers] {})))

(defn- skill-item
  [domain id skill]
  (let [root (get-in domain [:opts :extensions-root])]
    (when-not root
      (err/fail! :BACKUP_EXTENSIONS_UNAVAILABLE
                 "Skill files cannot be read without :extensions-root"
                 {:skill-id id}))
    (let [read (skill-store/read-files root id)]
      (when-not (:ok? read)
        (err/fail! :BACKUP_EXTENSIONS_INCOMPLETE
                   "A skill directory could not be read"
                   {:skill-id id :error (:error read)}))
      (let [files (:files read)
            content (into {}
                          (map (fn [[path bytes]]
                                 [path (codec/base64-encode bytes)]))
                          files)]
        (doseq [[path metadata] (:files skill)]
          (let [bytes (get files path)]
            (when-not (and bytes
                           (= (:size metadata) (alength ^bytes bytes))
                           (= (:sha256 metadata)
                              (str "sha256-" (codec/sha256-hex bytes))))
              (err/fail! :BACKUP_SNAPSHOT_INCOHERENT
                         "A skill file does not match the catalog metadata"
                         {:skill-id id :path path}))))
        (assoc skill :id id :files-content content)))))

(defn- skill-items
  [domain catalog]
  (mapv (fn [[id skill]] (skill-item domain id skill))
        (:skills catalog)))

(defn- scheduler-items
  [export]
  (->> (get-in export [:partitions "scheduler" :docs] [])
       (keep (fn [entry]
               (case (:kind entry)
                 :scheduler-root
                 {:id (:id entry) :kind :scheduler-root :root (:doc entry)}
                 :scheduler-schedule
                 {:id (:id entry) :kind :scheduler-schedule :schedule (:doc entry)}
                 :scheduler-run
                 {:id (:id entry) :kind :scheduler-run :run (:doc entry)}
                 nil)))
       vec))

(defn- attachment-refs
  [conversations]
  (letfn [(values-of [x]
            (cond
              (map? x) (vals x)
              (sequential? x) x
              :else []))]
    (mapcat (fn [conversation]
              (concat
               (or (:attachments conversation) [])
               (mapcat #(or (:attachments %) [])
                       (values-of (:drafts conversation)))
               (mapcat (fn [message]
                         (concat (or (:attachments message) [])
                                 (mapcat #(or (:attachments %) [])
                                         (values-of (:versions message)))))
                       (values-of (:messages conversation)))))
            conversations)))

(defn- read-blob
  [domain id]
  (let [port (get-in domain [:opts :blob-store])
        max-bytes (get-in domain [:opts :max-attachment-bytes])]
    (when-not port
      (err/fail! :BACKUP_BLOBS_UNAVAILABLE
                 "Attachment bytes need a :blob-store port"
                 {:blob-id id}))
    (try
      (when-let [metadata (blob-port/blob-metadata port id)]
        (when (and (integer? max-bytes)
                   (integer? (:size metadata))
                   (> (long (:size metadata)) (long max-bytes)))
          (err/fail! :BACKUP_ATTACHMENT_TOO_LARGE
                     "An attachment exceeds the backup attachment limit"
                     {:blob-id id
                      :size (:size metadata)
                      :limit max-bytes}))
        (when-let [in (blob-port/open-blob port id)]
          (with-open [^java.io.InputStream in in]
            (let [bytes (.readAllBytes in)
                  actual-id (blob-store/id-of-bytes bytes)]
              (when-not (= actual-id id)
                (err/fail! :BACKUP_INTEGRITY
                           "Blob bytes do not match their content ID"
                           {:blob-id id :actual-id actual-id}))
              (when (and (integer? (:size metadata))
                         (not= (long (:size metadata)) (alength ^bytes bytes)))
                (err/fail! :BACKUP_INTEGRITY
                           "Blob size does not match its metadata"
                           {:blob-id id
                            :expected-size (:size metadata)
                            :actual-size (alength ^bytes bytes)}))
              {:id id
               :size (alength ^bytes bytes)
               :sha256 (codec/sha256-hex bytes)
               :bytes bytes}))))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Throwable e
        (err/fail! :BACKUP_BLOBS_UNAVAILABLE
                   "A referenced attachment blob could not be read"
                   {:blob-id id :message (.getMessage ^Throwable e)}
                   e)))))

(defn- attachment-map
  [domain conversations]
  (let [refs (attachment-refs conversations)
        by-id (reduce (fn [m ref]
                        (if-let [id (:attachment-id ref)]
                          (if-let [existing (get m id)]
                            (if (= (:blob-id existing) (:blob-id ref))
                              m
                              (err/fail! :BACKUP_FORMAT_INVALID
                                         "One attachment ID names different blobs"
                                         {:attachment-id id}))
                            (assoc m id ref))
                          m))
                      {}
                      refs)
        port (get-in domain [:opts :blob-store])
        _ (when (and (some :blob-id (vals by-id)) (nil? port))
            (err/fail! :BACKUP_BLOBS_UNAVAILABLE
                       "Attachment bytes need a :blob-store port"
                       {:attachment-ids (vec (keys by-id))}))
        _ (when port
            (let [ids (distinct (keep :blob-id (vals by-id)))
                  total (try
                          (reduce + 0
                                  (keep (fn [id]
                                          (some-> (blob-port/blob-metadata port id)
                                                  :size))
                                        ids))
                          (catch Throwable e
                            (err/fail! :BACKUP_BLOBS_UNAVAILABLE
                                       "Attachment metadata could not be read"
                                       {:message (.getMessage ^Throwable e)}
                                       e)))
                  limit (get-in domain [:opts :max-attachment-total-bytes])]
              (when (and (integer? limit) (> (long total) (long limit)))
                (err/fail! :BACKUP_ATTACHMENTS_TOO_LARGE
                           "Attachments exceed the total backup attachment limit"
                           {:size total :limit limit
                            :attachment-ids (vec (keys by-id))}))))]
    (reduce (fn [m [attachment-id ref]]
              (if-let [blob-id (:blob-id ref)]
                (if-let [{:keys [size sha256 bytes]} (read-blob domain blob-id)]
                  (assoc m attachment-id
                         {:id attachment-id
                          :size size
                          :sha256 sha256
                          :bytes-base64 (codec/base64-encode bytes)
                          :content-type (:mime ref)})
                  m)
                m))
            {}
            by-id)))

(defn- neutral-state
  [domain export extensions selected sampled-at request]
  (let [config (config-doc export)
        catalog (:catalog extensions)
        conversations? (contains? selected :conversations)
        conversations (if conversations?
                        (mapv #(conversation-item export %) (session-records export))
                        [])
        workspace-store (get-in domain [:opts :workspace-store])
        _ (when (and (contains? selected :workspaces)
                     (nil? workspace-store))
            (err/fail! :BACKUP_WORKSPACES_UNAVAILABLE
                       "Workspace backup needs a :workspace-store port"
                       {}))
        workspaces (if (contains? selected :workspaces)
                     (workspace/read-workspaces
                      workspace-store
                      {:include-external-files?
                       (boolean (:include-external-files? request))})
                     [])
        purpose (:purpose request)
        ;; Keep a temporarily missing managed workspace in preview/restore
        ;; state.  Dropping it here would delete its registry entry during a
        ;; merge.  A backup still refuses to package it as complete.
        workspaces workspaces
        _ (when-let [missing
                     (and (= :backup purpose)
                          (some (fn [ws]
                                  (when (or (and (= :managed (:kind ws))
                                                 (:missing? ws))
                                            (and (= :external (:kind ws))
                                                 (:include-external-files? request)
                                                 (:missing? ws)))
                                    ws))
                                workspaces))]
            (err/fail! :BACKUP_WORKSPACES_INCOMPLETE
                       "A selected workspace is missing its files"
                       {:workspace-id (:id missing)
                        :kind (:kind missing)
                        :reason (:missing-reason missing)}))]
    {:sampled-at sampled-at
     :categories
     {:conversations {:items conversations}
      :assistants {:items (assistant-items config)}
      :connections {:items (connection-items config)}
      :tools {:items (if (contains? selected :tools) (tool-items catalog) [])}
      :skills {:items (if (contains? selected :skills) (skill-items domain catalog) [])}
      :workspaces {:items workspaces}
      :worldbooks {:items (worldbook-items export)}
      :memories {:items (memory-items export)}
      :appearance {:items (appearance-items export)}
      :schedules {:items (if (contains? selected :schedules)
                           (scheduler-items export)
                           [])}}
     :attachments (if conversations? (attachment-map domain conversations) {})
     :imports (import-items export)
     :permissions {}}))

;; ---------------------------------------------------------------------------
;; Restore mapping

(def ^:private transport-keys
  "Fields added by the backup layer to carry storage identity or relation
  metadata.  They are never part of a storage document."
  #{:id :owner-state :temporary? :deleted? :cache? :attachment-ids
    :restored?
    :restored-at :restored-from-backup? :conflict :conflict-resolution
    :display-name :source-note :assistant-placeholder?
    :workspace-relink-required? :internal?
    :entries :sessions :entry :session :card :selection :role-bindings
    :config :root :book :files-content :schedule :run})

(def ^:private connection-restore-metadata
  "Restore markers a connection may carry.  The model adapter has a closed
  connection schema, so they must not reach the persisted settings document."
  #{:imported-from :restored? :restored-from-backup? :verified?
    :needs-verification? :secrets-present? :conflict :conflict-resolution
    :restored-pending-review?})

(def ^:private restore-only-keys
  "Policy fields that describe the restore, not the storage domain.  Legitimate
  domain fields such as a connection's :enabled? or :verified? are preserved.
  Review markers (`:needs-review?`, `:needs-verification?`, `:installed?`) are
  persisted so a restart still shows which imported items await review."
  #{:restored-pending-review? :pending-review?
    :relink-required? :secrets-present?
    :write-enabled?})

(defn- strip-transport
  [item]
  (apply dissoc item transport-keys))

(defn- strip-adapter
  [item]
  (apply dissoc (strip-transport item) restore-only-keys))

(defn- canonical-session
  [item]
  (let [id (:id item)]
    (assoc (strip-adapter item) :session-id id)))

(defn- canonical-owner
  [item session-id]
  (when-let [owner (:owner-state item)]
    (assoc owner :session-id session-id)))

(defn- canonical-assistant
  [item]
  (let [id (:id item)]
    (assoc (strip-adapter item) :assistant-id id)))

(defn- canonical-connection
  [item]
  (let [id (:id item)]
    (assoc (strip-adapter item) :connection-id id)))

(defn- canonical-memory
  [item]
  (let [id (:id item)]
    (assoc (dissoc (strip-adapter item) :enabled? :pending-review?)
           :id id)))

(defn- canonical-book
  [item]
  (let [id (:id item)]
    {:book (assoc (strip-adapter item) :id id)
     :entries (mapv #(assoc (strip-adapter %) :id (:id %) :book-id id)
                    (:entries item))
     :sessions []}))

(defn- canonical-entry
  [item]
  (let [id (:id item)]
    (assoc (strip-adapter (:entry item)) :id id)))

(defn- canonical-worldbook-session
  [item]
  (let [id (:id item)]
    (assoc (strip-adapter (:session item)) :id id)))

(defn- canonical-card
  [item]
  (let [id (:id item)]
    (assoc (strip-adapter (:card item)) :id id)))

(defn- canonical-selection
  [item]
  (let [id (:id item)]
    (assoc (strip-adapter (:selection item)) :id id)))

(defn- safe-catalog-id
  [id]
  (let [safe (str/replace (str id) #"[^A-Za-z0-9._-]" "-")]
    (if (str/blank? safe) "imported" safe)))

(defn- canonical-tool
  [item]
  (assoc (dissoc (strip-adapter item) :permissions)
         :id (safe-catalog-id (:id item))))

(defn- canonical-skill
  [item]
  (assoc (strip-adapter item) :id (safe-catalog-id (:id item))))

(defn- canonical-scheduler-root
  [item current-root _now]
  (let [root (or (some-> (:root item) strip-adapter)
                 current-root
                 {:v 1 :revision 0 :seq 0 :device-zone nil :run-order []})]
    (-> root
        (dissoc :enabled? :paused? :next-run :restored-pending-review?
                :updated-at)
        (assoc :revision (inc (long (or (:revision root) 0)))))))

(defn- canonical-schedule
  [item now-ms pause?]
  (let [schedule (strip-adapter (:schedule item))]
    (if pause?
      (-> schedule
          (dissoc :enabled? :paused? :next-run :restored-pending-review?)
          (assoc :state :paused
                 :active-run-id nil
                 :next-run nil
                 :paused-at now-ms
                 :updated-at now-ms))
      schedule)))

(defn- canonical-run
  [item now-ms interrupt?]
  (let [run (strip-adapter (:run item))
        active? (and interrupt?
                     (contains? #{:preparing :running :awaiting-user
                                  :outcome-unknown}
                                (:status run)))]
    (cond-> run
      active? (assoc :status :interrupted
                     :reason :restored
                     :ended-at now-ms))))

(defn- unique-by
  [items f label]
  (reduce (fn [m item]
            (let [id (f item)]
              (when (contains? m id)
                (err/fail! :BACKUP_STORAGE_DUPLICATE_ID
                           "Restore plan contains duplicate entity IDs"
                           {:kind label :id id}))
              (assoc m id item)))
          {}
          items))

(defn- partition-or-empty
  [export p]
  (or (get-in export [:partitions p])
      {:version 0 :meta nil :last-commit nil
       :docs [] :obligations [] :blob-refs []}))

(defn- replace-docs
  [export p kinds new-docs]
  (let [current (partition-or-empty export p)
        kept (remove #(contains? kinds (:kind %)) (:docs current))
        docs (vec (concat kept new-docs))]
    (assoc-in export [:partitions p]
              (assoc current
                     :version (inc (long (or (:version current) 0)))
                     :docs docs))))

(defn- replace-session-partitions
  [export conversations replace?]
  (let [ids (set (keep :id conversations))
        export (reduce (fn [export item]
                         (let [id (:id item)
                               owner (canonical-owner item id)
                               p (str "session/" id)
                               current (partition-or-empty export p)]
                           (if owner
                             (assoc-in export [:partitions p]
                                       (assoc current
                                              :version (inc (long (or (:version current) 0)))
                                              :docs [{:kind :owner-state
                                                      :id id
                                                      :session-id id
                                                      :doc owner}]))
                             export)))
                       export
                       conversations)]
    (if replace?
      (update export :partitions
              (fn [partitions]
                (into (sorted-map)
                      (remove (fn [[p _]]
                                (and (str/starts-with? p "session/")
                                     (not (contains? ids (subs p (count "session/")))))))
                      partitions)))
      export)))

(defn- conversation-export
  [export items mode]
  (let [sessions (mapv canonical-session items)
        _ (unique-by sessions :session-id :conversations)
        export (replace-docs export "sessions" #{:session-record :session-index}
                             (conj (mapv (fn [s]
                                           {:kind :session-record
                                            :id (:session-id s)
                                            :session-id (:session-id s)
                                            :doc s})
                                         sessions)
                                   {:kind :session-index
                                    :id "sessions"
                                    :doc {:id "sessions"
                                          :ids (mapv :session-id sessions)}}))]
    (let [export (replace-session-partitions export items (= :replace mode))
          docs (get-in export [:partitions "sessions" :docs] [])
          refs (blob-refs/storage-doc-refs docs)]
      ;; Restored attachment bytes must have the same partition-level
      ;; references as a live session commit.  The export replaces the whole
      ;; sessions partition, so its reference table is recomputed from the
      ;; final session documents rather than copied from the source backup.
      (assoc-in export [:partitions "sessions" :blob-refs]
                (blob-refs/export-entries refs)))))

(defn- config-export
  [export categories mode selected]
  (let [current (config-doc export)
        assistants? (contains? selected :assistants)
        connections? (contains? selected :connections)
        appearance? (contains? selected :appearance)
        assistant-items (get categories :assistants [])
        connection-items (get categories :connections [])
        appearance-items (get categories :appearance [])
        role-item (first (filter #(= :role-bindings (:kind %)) connection-items))
        app-item (first (filter #(= :app-config (:kind %)) appearance-items))
        assistants (when assistants?
                     (into {}
                           (map (fn [item]
                                  (let [assistant (canonical-assistant item)]
                                    [(:assistant-id assistant) assistant])))
                           assistant-items))
        ;; The settings document stores connections as a vector of maps
        ;; keyed by `:id`; `settings/merge-model-config` and `settings-view`
        ;; both require that shape.  A map here made every restored
        ;; connection invisible after a restart.
        connection-list (when connections?
                          (mapv (fn [item]
                                  (let [id (:id item)
                                        connection (apply dissoc
                                                          (strip-adapter item)
                                                          connection-restore-metadata)]
                                    (assoc connection
                                           :id id
                                           :connection-id
                                           (or (:connection-id connection) id))))
                                (remove #(= :role-bindings (:kind %))
                                        connection-items)))
        connections connection-list
        order (when connections?
                (mapv :id connection-list))
        role-bindings (cond
                        (and connections? role-item) (:role-bindings role-item)
                        connections? (:role-bindings current)
                        :else nil)
        ;; Launch provenance belongs to the instance whose launch file
        ;; produced it.  A backup from another instance must not carry its
        ;; `:launch-connection-ids` / `:removed-connection-ids` into the
        ;; target: doing so hides a restored connection that the target
        ;; launch never declared (the settings merge reads it as revoked).
        ;; The target's own provenance stays authoritative.
        provenance (select-keys current [:launch-connection-ids
                                         :removed-connection-ids])
        remainder (when (and appearance? app-item)
                    (dissoc (:config app-item)
                            :launch-connection-ids :removed-connection-ids))
        base (cond-> (assoc current
                            :revision (inc (long (or (:revision current) 0)))
                            :updated-at (str (Instant/now)))
               assistants? (assoc :assistants assistants)
               connections? (assoc :connections connections
                                   :connection-order order)
               role-bindings (assoc :role-bindings role-bindings))
        next (cond
               (and appearance? (= mode :replace))
               ;; A replace removes appearance keys that the backup does not
               ;; contain; a merge keeps the local remainder.  The target's
               ;; launch provenance is re-attached in both modes.
               (merge (apply dissoc base
                             (keys (dissoc (config-remainder current)
                                           :backup)))
                      (or remainder {})
                      provenance)

               appearance?
               (merge base (or remainder {}) provenance)

               :else
               base)
        changed? (or assistants? connections? appearance?)]
    (if changed?
      (replace-docs export "config" #{:app-config}
                    [{:kind :app-config :id "config" :doc next}])
      export)))

(defn- materials-export
  [export categories mode selected]
  (let [current-root (doc-value export "materials" :materials-root "root")
        root-item (first (filter #(= :materials-root (:kind %))
                                 (get categories :appearance [])))
        worldbooks? (contains? selected :worldbooks)
        memories? (contains? selected :memories)
        appearance? (contains? selected :appearance)
        worldbook-items (get categories :worldbooks [])
        memory-items (get categories :memories [])
        appearance-items (get categories :appearance [])
        book-items (remove #(contains? #{:worldbook-entry :worldbook-session}
                                        (:kind %))
                           worldbook-items)
        entry-items (filter #(= :worldbook-entry (:kind %)) worldbook-items)
        session-items (filter #(= :worldbook-session (:kind %)) worldbook-items)
        card-items (filter #(= :instruction-card (:kind %)) appearance-items)
        selection-items (filter #(= :instruction-session (:kind %)) appearance-items)
        books (mapv canonical-book book-items)
        entries (into (mapcat :entries books)
                      (map canonical-entry entry-items))
        sessions (mapv canonical-worldbook-session session-items)
        memories (mapv canonical-memory memory-items)
        cards (mapv canonical-card card-items)
        selections (mapv canonical-selection selection-items)
        old-root (or (:root root-item) current-root
                     {:v 1 :revision 0 :seq 0
                      :settings {:memory-read? false :memory-suggest? false}})
        root (assoc old-root
                    :revision (inc (long (or (:revision old-root) 0)))
                    :seq (max (long (or (:seq old-root) 0))
                              (long (or (:seq current-root) 0))))
        changed? (or worldbooks? memories? appearance?)
        current-docs (get-in export [:partitions "materials" :docs] [])
        preserved (remove (fn [entry]
                            (case (:kind entry)
                              :materials-root true
                              (:materials-worldbook-book :materials-worldbook-entry
                               :materials-worldbook-session) worldbooks?
                              :materials-memory memories?
                              (:materials-instruction-card
                               :materials-instruction-session) appearance?
                              false))
                          current-docs)
        new-docs (concat
                  [{:kind :materials-root :id "root" :doc root}]
                  (when worldbooks?
                    (concat
                     (mapv (fn [book] {:kind :materials-worldbook-book
                                       :id (:id (:book book))
                                       :doc (:book book)}) books)
                     (mapv (fn [entry] {:kind :materials-worldbook-entry
                                        :id (:id entry) :doc entry}) entries)
                     (mapv (fn [session] {:kind :materials-worldbook-session
                                          :id (:id session) :doc session}) sessions)))
                  (when memories?
                    (mapv (fn [memory] {:kind :materials-memory
                                        :id (:id memory) :doc memory}) memories))
                  (when appearance?
                    (concat
                     (mapv (fn [card] {:kind :materials-instruction-card
                                       :id (:id card) :doc card}) cards)
                     (mapv (fn [selection] {:kind :materials-instruction-session
                                            :id (:id selection) :doc selection})
                           selections))))]
    (if changed?
      (assoc-in export [:partitions "materials"]
                (assoc (partition-or-empty export "materials")
                       :version (inc (long (or (get-in export [:partitions "materials" :version]) 0)))
                       :docs (vec (concat preserved new-docs))))
      export)))

(defn- scheduler-export
  [export categories selected mode now-ms]
  (if-not (contains? selected :schedules)
    export
    (let [items (get categories :schedules [])
          root-item (first (filter #(= :scheduler-root (:kind %)) items))
          schedule-items (filter #(= :scheduler-schedule (:kind %)) items)
          run-items (filter #(= :scheduler-run (:kind %)) items)
          current-root (doc-value export "scheduler" :scheduler-root "root")
          root (canonical-scheduler-root root-item current-root now-ms)
          run-ids (mapv :id run-items)
          replace? (= :replace mode)
          schedules (mapv (fn [item]
                            (assoc (canonical-schedule
                                    item now-ms
                                    (or replace? (some? (:imported-from item))))
                                   :id (:id item)))
                          schedule-items)
          runs (mapv (fn [item]
                       (assoc (canonical-run
                               item now-ms
                               (or replace? (some? (:imported-from item))))
                              :id (:id item)))
                     run-items)
          numeric-suffix (fn [id]
                           (when-let [[_ n] (re-find #"-(\d+)$" (str id))]
                             (Long/parseLong n)))
          root-seq (max (long (or (:seq root) 0))
                        (long (or (reduce max 0
                                          (keep numeric-suffix
                                                (concat (map :id schedules)
                                                        run-ids)))
                                  0)))
          root (assoc root :run-order run-ids :seq root-seq)
          docs (concat
                [{:kind :scheduler-root :id "root" :doc root}]
                (mapv (fn [schedule]
                        {:kind :scheduler-schedule
                         :id (:id schedule)
                         :doc schedule})
                      schedules)
                (mapv (fn [run]
                        {:kind :scheduler-run :id (:id run) :doc run})
                      runs))
          export (replace-docs export "scheduler"
                               #{:scheduler-root :scheduler-schedule :scheduler-run}
                               docs)]
      (if replace?
        (update-in export [:partitions "scheduler" :obligations]
                   (constantly []))
        export))))

(defn- imports-export
  [export imports]
  (if (map? imports)
    (replace-docs export "backup" #{:backup-import}
                  (mapv (fn [[id doc]]
                          {:kind :backup-import :id id :doc doc})
                        imports))
    export))

(defn- app-window-state
  [export]
  (let [docs (get-in export [:partitions "commands/app" :docs] [])
        entry (first (filter #(and (= :owner-state (:kind %))
                                   (= "app" (:id %)))
                             docs))]
    (:doc entry)))

(defn- valid-app-window?
  [state]
  (let [epoch (:command-epoch state)
        retired (:retired-through state)]
    (and (map? state)
         (or (nil? epoch)
             (and (integer? epoch) (pos? (long epoch))))
         (integer? retired)
         (not (neg? (long retired)))
         (or (nil? epoch) (> (long epoch) (long retired))))))

(defn- app-identity-epoch
  [doc]
  (let [identity (or (:creation-command doc) (:identity doc))]
    (when (and (map? identity)
               (= "app" (:scope identity))
               (integer? (:command-epoch identity))
               (pos? (long (:command-epoch identity))))
      (long (:command-epoch identity)))))

(defn- app-epoch-floor
  [export state]
  (let [gate-floor (when state
                     (max (long (or (:command-epoch state) 0))
                          (long (or (:retired-through state) 0))))
        session-floor (->> (get-in export [:partitions "sessions" :docs] [])
                           (keep #(app-identity-epoch (:doc %)))
                           (reduce max 0))]
    (max (long (or gate-floor 0)) session-floor)))

(defn- fence-app-export
  "Replace the app command gate in EXPORT with a fenced state.

  The new window is above the local gate high-water and every app-scope
  creation/reuse identity in the export.  The gate document is part of the
  same `restore-state!` transaction as the restored categories, so a crash
  cannot leave restored data with the old epoch.  A present but malformed
  gate fails before any write.  A missing gate is refused unless the caller
  explicitly proves that no prior app commands exist."
  [export allow-missing-gate?]
  (let [state (app-window-state export)]
    (when (and state (not (valid-app-window? state)))
      (err/fail! :BACKUP_COMMAND_EPOCH_WINDOW_INVALID
                 "The stored application command window is invalid"
                 {:window (select-keys state [:command-epoch :retired-through])}))
    (when (and (nil? state) (not allow-missing-gate?))
      (err/fail! :BACKUP_COMMAND_EPOCH_WINDOW_MISSING
                 "The application command window is missing; the restore cannot prove the old high-water"
                 {:partition "commands/app"}))
    (let [floor (app-epoch-floor export state)
          epoch (inc floor)
          fenced {:command-epoch epoch
                  :retired-through floor
                  :receipts {}
                  :receipt-index {}}
          export (replace-docs export "commands/app" #{:owner-state}
                               [{:kind :owner-state
                                 :id "app"
                                 :session-id "app"
                                 :doc fenced}])]
      {:export export
       :fence {:command-epoch epoch
               :command-retired-through floor}})))

(declare close-action-obligations)

(defn- build-export
  [current plan allow-missing-gate?]
  (let [selected (set (keys (:apply-categories plan)))
        cats (:apply-categories plan)
        mode (:mode plan)
        now-ms (System/currentTimeMillis)
        export (if (contains? selected :conversations)
                 (conversation-export current (get cats :conversations []) mode)
                 current)
        export (if (or (contains? selected :assistants)
                       (contains? selected :connections)
                       (contains? selected :appearance))
                 (config-export export cats mode selected)
                 export)
        export (if (or (contains? selected :worldbooks)
                       (contains? selected :memories)
                       (contains? selected :appearance))
                 (materials-export export cats mode selected)
                 export)
        export (scheduler-export export cats selected mode now-ms)
        export (imports-export export (:imports plan))
        export (replace-docs export "config" #{:restore-safety}
                             [{:kind :restore-safety
                               :id "restore"
                               :doc {:permissions (or (:permissions plan)
                                                      (model/safe-permission-map nil))
                                     :source-backup-id (:backup-id plan)
                                     :restored-at (str (Instant/now))
                                     :implicit-authority? false}}])
        export (assoc export
                      :complete? true
                      :exported-at (str (Instant/now)))
        export (close-action-obligations export)]
    (fence-app-export export allow-missing-gate?)))

(defn- close-action-obligation
  "A whole-store restore invalidates every in-flight action.

  Keeping a `:ready` or `:running` call obligation would let startup recovery
  dispatch pre-restore work against restored data.  Calls become
  `:outcome-unknown`; reports and retry indexes become `:cancelled`."
  [entry]
  (let [doc (:doc entry)]
    (if (and (true? (:open? doc))
             (contains? #{:call :report :retry-call}
                        (:obligation/kind doc)))
      (assoc entry
             :doc (assoc doc
                         :open? false
                         :phase (if (= :call (:obligation/kind doc))
                                  :outcome-unknown
                                  :cancelled)
                         :restore-cancelled? true
                         :restore-cancelled-at (str (Instant/now))))
      entry)))

(defn- close-action-obligations
  [export]
  (update export :partitions
          (fn [partitions]
            (into (empty partitions)
                  (map (fn [[partition data]]
                         [partition
                          (update data :obligations
                                  (fn [obligations]
                                    (mapv close-action-obligation
                                          (or obligations []))))]))
                  partitions))))

(defn- extension-next
  [current categories selected]
  (let [current (or current {})
        skills (if (contains? selected :skills)
                 (into {}
                       (map (fn [item]
                              (let [skill (canonical-skill item)]
                                [(:id skill) skill])))
                       (get categories :skills []))
                 (:skills current))
        servers (if (contains? selected :tools)
                  (into {}
                        (map (fn [item]
                               (let [tool (canonical-tool item)]
                                 [(:id tool) tool])))
                        (get categories :tools []))
                  (get-in current [:mcp :servers] {}))]
    (-> current
        (assoc :skills skills)
        (assoc-in [:mcp :servers] servers))))

(defn- decode-skill-files
  [item]
  (when-not (contains? item :files-content)
    (err/fail! :BACKUP_EXTENSIONS_INCOMPLETE
               "A skill item has no file content"
               {:skill-id (:id item)}))
  (into {}
        (map (fn [[path content]]
               [path (codec/base64-decode content)]))
        (:files-content item)))

(defn- apply-skill-files!
  [domain categories selected current-skills]
  (when (contains? selected :skills)
    (let [root (get-in domain [:opts :extensions-root])]
      (when-not root
        (err/fail! :BACKUP_EXTENSIONS_UNAVAILABLE
                   "Skill files cannot be restored without :extensions-root"
                   {}))
      (let [final (get categories :skills [])
            installed (reduce
                       (fn [m item]
                         (let [id (safe-catalog-id (:id item))
                               files (decode-skill-files item)
                               deleted (skill-store/delete! root id)]
                           (when-not (:ok? deleted)
                             (err/fail! :BACKUP_EXTENSIONS_RESTORE_FAILED
                                        "An existing skill directory could not be replaced"
                                        {:skill-id id :error (:error deleted)}))
                           (let [result (skill-store/install! root id files)]
                             (when-not (:ok? result)
                               (err/fail! :BACKUP_EXTENSIONS_RESTORE_FAILED
                                          "Skill files could not be restored"
                                          {:skill-id id :error (:error result)}))
                             (assoc m id {:root (:root result)
                                          :storage-root root
                                          :files (:files result)}))))
                       {}
                       final)]
        (doseq [id (remove (set (map :id final)) (keys current-skills))]
          (let [deleted (skill-store/delete! root id)]
            (when-not (:ok? deleted)
              (err/fail! :BACKUP_EXTENSIONS_RESTORE_FAILED
                         "A removed skill directory could not be deleted"
                         {:skill-id id :error (:error deleted)}))))
        installed))))

(defn- restore-attachments!
  [domain attachments]
  (if (empty? attachments)
    {}
    (let [port (get-in domain [:opts :blob-store])
          max-bytes (get-in domain [:opts :max-attachment-bytes])
          max-total (get-in domain [:opts :max-attachment-total-bytes])]
      (when-not port
        (err/fail! :BACKUP_BLOBS_UNAVAILABLE
                   "Attachment bytes need a :blob-store port"
                   {:attachment-ids (vec (keys attachments))}))
      (loop [entries (seq attachments)
             restored {}
             total 0]
        (if-let [[attachment-id attachment] (first entries)]
          (let [encoded (:bytes-base64 attachment)
                _ (when (and (integer? max-bytes)
                             (string? encoded)
                             (> (count encoded)
                                (long (* 4 (quot (+ (long max-bytes) 2) 3)))))
                    (err/fail! :BACKUP_ATTACHMENT_TOO_LARGE
                               "A restored attachment exceeds the attachment limit"
                               {:attachment-id attachment-id
                                :limit max-bytes}))
                bytes (codec/base64-decode encoded)
                size (alength ^bytes bytes)
                _ (when (and (integer? max-bytes)
                             (> (long size) (long max-bytes)))
                    (err/fail! :BACKUP_ATTACHMENT_TOO_LARGE
                               "A restored attachment exceeds the attachment limit"
                               {:attachment-id attachment-id
                                :size size
                                :limit max-bytes}))
                total' (+ (long total) (long size))
                _ (when (and (integer? max-total)
                             (> total' (long max-total)))
                    (err/fail! :BACKUP_ATTACHMENTS_TOO_LARGE
                               "Restored attachments exceed the total attachment limit"
                               {:attachment-id attachment-id
                                :size total'
                                :limit max-total}))
                result (blob-port/put-blob!
                        port
                        (ByteArrayInputStream. bytes)
                        {})
                id (:id result)]
            (when-not (and (string? id)
                           (= id (blob-store/id-of-bytes bytes)))
              (err/fail! :BACKUP_INTEGRITY
                         "Restored attachment blob identity is invalid"
                         {:attachment-id attachment-id
                          :blob-id id}))
            (recur (next entries)
                   (assoc restored attachment-id
                          {:id id
                           :sha256 (codec/sha256-hex bytes)
                           :size size})
                   total'))
          restored)))))

(defn- update-attachment-refs
  [refs blob-map]
  (mapv (fn [ref]
          (if-let [blob (get blob-map (:attachment-id ref))]
            (cond-> (assoc ref :blob-id (:id blob) :local-copy? true)
              (string? (:sha256 blob))
              (assoc :sha256 (:sha256 blob))

              (integer? (:size blob))
              (assoc :size (:size blob))

              ;; The prepared image copy is derived from the original and is
              ;; not packaged.  Keeping its old content ID would leave a
              ;; dangling reference, so the restored descriptor states the
              ;; truth and the client can regenerate it.
              (contains? ref :prepared)
              (-> (dissoc :prepared)
                  (assoc :prepared-status {:status :missing
                                           :reason :not-in-backup})))
            (if (string? (:blob-id ref))
              ;; The backup did not carry these bytes.  Keep the descriptor
              ;; visible but remove the content ID so the restored reference
              ;; table cannot claim a blob that does not exist.
              (-> ref
                  (assoc :restore-status {:status :missing
                                          :reason :not-in-backup}
                         :source-blob-id (:blob-id ref))
                  (dissoc :blob-id))
              ref)))
        (or refs [])))

(defn- update-message
  [message blob-map]
  (cond-> (update message :attachments update-attachment-refs blob-map)
    (vector? (:versions message))
    (update :versions
            (fn [versions]
              (mapv #(update % :attachments update-attachment-refs blob-map)
                    versions)))))

(defn- update-conversation
  [item blob-map]
  (-> item
      (update :messages
              (fn [messages]
                (into {}
                      (map (fn [[id message]]
                             [id (update-message message blob-map)]))
                      (or messages {}))))
      (update :drafts
              (fn [drafts]
                (into {}
                      (map (fn [[id draft]]
                             [id (update draft :attachments
                                         update-attachment-refs blob-map)]))
                      (or drafts {}))))
      (update :attachments update-attachment-refs blob-map)))

(defn- mark-import-applied!
  "Record that the storage transaction and every external step completed.

  The first ledger write happens inside `restore-state!` with
  `:external-applied? false`.  A crash before this second write leaves a
  retryable record instead of a false claim that the import is complete."
  [store plan]
  (let [backup-id (:backup-id plan)
        entry (get (:imports plan) backup-id)]
    (when (and (string? backup-id) (map? entry))
      (storage/commit-migration!
       store
       {:migration-id (str "backup-import-applied-" (UUID/randomUUID))
        :partition "backup"
        :reason :backup-import-applied
        :put [{:kind :backup-import
               :id backup-id
               :doc (assoc entry :external-applied? true)}]}))))

(defn- apply-plan!
  [domain plan]
  (let [store (:store domain)
        quiescent? (or (true? (:quiescent? plan))
                       (true? (:quiescent? (:opts domain))))]
    (when-not quiescent?
      (err/fail! :BACKUP_RESTORE_REQUIRES_QUIESCENCE
                 "Restoring storage requires the Owner and all writers to be stopped"
                 {:path (try (storage/store-path store) (catch Exception _ nil))}))
    (let [retry? (true? (:retry-external? plan))
          apply-storage? (not (and retry?
                                    (empty? (:apply-categories plan))))
          categories (if retry?
                       (into {}
                             (map (fn [[category value]]
                                    [category (if (map? value)
                                                (:items value)
                                                value)]))
                             (:categories plan))
                       (:apply-categories plan))
          selected (if retry?
                     (set (:selected-categories plan))
                     (set (keys categories)))
          blob-map (when apply-storage?
                     (restore-attachments! domain (:attachments plan)))
          plan (if (and (some? (:attachments plan))
                        (contains? categories :conversations))
                 (update-in plan [:apply-categories :conversations]
                            (fn [conversations]
                              (mapv #(update-conversation % blob-map)
                                    conversations)))
                 plan)
          needs-extensions? (or (contains? selected :tools)
                                (contains? selected :skills))
          _ (when (and needs-extensions?
                       (nil? (extension-repository domain)))
              (err/fail! :BACKUP_EXTENSIONS_UNAVAILABLE
                         "Restoring tools/skills needs :extensions-root"
                         {}))
          extensions (when needs-extensions? (load-extensions domain))
          current-catalog (:catalog extensions)
          current-version (:version extensions)
          next-catalog (when extensions
                         (extension-next current-catalog
                                         categories
                                         selected))
          workspace-store (get-in domain [:opts :workspace-store])
          _ (when (and (contains? selected :workspaces)
                       (nil? workspace-store))
              (err/fail! :BACKUP_WORKSPACES_UNAVAILABLE
                         "Workspace restore needs a :workspace-store port"
                         {}))
          allow-missing-gate? (or (true? (:no-prior-app-commands? plan))
                                  (true? (:no-prior-app-commands?
                                          (:opts domain))))
          current (storage/export-state store {})
          built (when apply-storage?
                  (build-export current plan allow-missing-gate?))
          export (:export built)
          fence (:fence built)
          result (if apply-storage?
                   (storage/restore-state! store export
                                          {:quiescent? true
                                           :migration-id (or (:migration-id plan)
                                                             (str "backup-restore-"
                                                                  (UUID/randomUUID)))})
                   {:status :applied
                    :retry-external? true
                    :version (or (:version (storage/store-info store)) 0)})
          skill-results (when (and next-catalog (contains? selected :skills))
                          (apply-skill-files! domain categories
                                              selected (:skills current-catalog)))
          next-catalog (if skill-results
                         (reduce-kv (fn [catalog id installed]
                                      (update-in catalog [:skills id] merge installed))
                                    next-catalog
                                    skill-results)
                         next-catalog)
          _ (when (contains? selected :workspaces)
              (try
                (workspace/restore-workspaces!
                 workspace-store (get categories :workspaces))
                (catch clojure.lang.ExceptionInfo e
                  (err/fail! :BACKUP_WORKSPACE_RESTORE_FAILED
                             "Workspace files could not be restored"
                             {:storage-restored? true
                              :cause-code (err/code e)}
                             e))))
          _ (when next-catalog
              (let [saved (extensions-durable/save!
                           (extension-repository domain)
                           next-catalog
                           current-version)]
                (when-not (:ok? saved)
                  (err/fail! :BACKUP_EXTENSIONS_RESTORE_FAILED
                             "The extension catalog could not be restored"
                             {:error (:error saved)
                              :storage-restored? true}))))
          _ (mark-import-applied! store plan)]
      (assoc result
             :status :applied
             :command-epoch (:command-epoch fence)
             :command-retired-through (:command-retired-through fence)))))

(defn restore-safety
  "Return the durable restore-safety record, or nil.

  The record is written in the same transaction as the restored categories.
  Startup must read it before enabling imported schedules, auto-backup,
  device control, full access or external writes."
  [store]
  (try
    (let [view (storage/read-session-view store {:partition "config"})
          doc (first (get-in view [:records :restore-safety] []))]
      (when doc
        (dissoc doc :storage/id :storage/kind :storage/partition
                :storage/session-id :storage/version :storage/updated-at)))
    (catch Throwable _ nil)))

;; ---------------------------------------------------------------------------
;; Port

(extend-type StorageDomain
  port/DomainPort
  (read-snapshot [domain request]
    (let [store (:store domain)
          needs-extensions? (or (contains? (set (:categories request)) :tools)
                                (contains? (set (:categories request)) :skills))
          _ (when (and needs-extensions?
                       (nil? (extension-repository domain)))
              (err/fail! :BACKUP_EXTENSIONS_UNAVAILABLE
                         "Backing up tools/skills needs :extensions-root"
                         {}))
          stable (loop [attempt 0]
                   (let [before (when needs-extensions? (load-extensions domain))
                         ;; The export records the instant its coherent read
                         ;; completed.  The caller's requested time is only a
                         ;; start hint; using it as the label would let writes
                         ;; after that instant appear inside an "earlier"
                         ;; backup.
                         export (storage/export-state store {})
                         after (when needs-extensions? (load-extensions domain))]
                     (if (and (or (nil? before) (= (:version before) (:version after)))
                              (or (nil? before) (:ok? before))
                              (or (nil? after) (:ok? after)))
                       {:export export :extensions (or before after)}
                       (if (< attempt 3)
                         (recur (inc attempt))
                         (err/fail! :BACKUP_SNAPSHOT_INCOHERENT
                                    "The extension catalog changed during the backup snapshot"
                                    {:attempts attempt})))))]
      (let [export (:export stable)
            sampled-at (or (:exported-at export) (:sampled-at request))]
        (assoc (neutral-state domain export (:extensions stable)
                              (set (:categories request))
                              sampled-at
                              request)
             :storage (storage/store-info store)))))

  (apply-restore! [domain plan]
    (apply-plan! domain plan))

  (storage-info [domain]
    (storage/store-info (:store domain))))
