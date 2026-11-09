(ns hc.server.storage.view
  "Consistent read views and the format-versioned export package.

  Root AGENTS.md §7.1 asks `commit-migration!` for a state view that guards can
  use, and §5.2 forbids publishing a state before its commit is visible.  A
  view is assembled only when the global store version is unchanged before and
  after the reads.  A concurrent commit makes the check fail and the read is
  retried; after a bounded number of attempts the adapter reports
  `:STORAGE_READ_UNSTABLE` instead of returning a mixed snapshot.

  Documents are stored with a small storage envelope:

      {:storage/kind :task :storage/id \"t1\" :storage/partition \"p\"
       :storage/session-id \"s\" :storage/version 42
       :storage/updated-at \"...\" :doc <caller document>}

  Views return the caller document, never the envelope.  The envelope exists so
  a derived index can prove which commit produced an entity (§7.1)."

  (:require [clojure.string :as str]
            [hc.server.storage.engine.datalevin :as engine]
            [hc.server.storage.error :as err]
            [hc.server.storage.keys :as keys]))

(def ^:private max-read-attempts 4)

(defn unwrap
  "Return the caller document with its storage identity attached.
  The `:storage/*` keys are reserved and may be ignored by domain code."
  [entry]
  (let [v (second entry)]
    (assoc (:doc v)
           :storage/id (:storage/id v)
           :storage/kind (:storage/kind v)
           :storage/partition (:storage/partition v)
           :storage/session-id (:storage/session-id v)
           :storage/version (:storage/version v)
           :storage/updated-at (:storage/updated-at v))))

(defn read-stable
  "Run `f` and return its result only if the global store version did not
  change across the read.  Retries a bounded number of times."
  [engine f]
  (loop [attempt 1]
    (let [before (or (engine/get-value engine (keys/meta-key "store-version")) 0)
          result (f)
          after  (or (engine/get-value engine (keys/meta-key "store-version")) 0)]
      (if (= before after)
        (assoc result :store-version before)
        (if (< attempt max-read-attempts)
          (recur (inc attempt))
          (err/raise :STORAGE_READ_UNSTABLE
                     "the store changed repeatedly during a read"
                     {:attempts attempt :before before :after after}))))))

(defn read-doc
  "Return one stored document unwrapped, or nil when the key is absent.

  This is the point read for callers that need exactly one document and must
  not pay the partition scan `session-view` performs.  `session-id` nil
  addresses the partition-scoped (`_`) record."
  [engine {:keys [partition session-id kind id]}]
  (when-let [entry (engine/get-value engine
                                     (keys/doc-key partition session-id kind id))]
    (unwrap [nil entry])))

(defn read-documents
  "Return every document of one KIND in one session scope.

  The key prefix includes both the partition and the scope, so this never
  deserializes a document of another kind.  Use it for compact, index-like
  kinds; session records and message bodies belong to `session-view`."
  [engine {:keys [partition session-id kind]}]
  (->> (read-stable
        engine
        (fn []
          {:docs (mapv unwrap
                       (engine/scan engine
                                    (keys/kind-doc-prefix partition session-id kind)))}))
       :docs))

(defn read-partition-documents
  "Return every document of one KIND in PARTITION, across all scopes.

  This is a full partition scan.  It exists only for one-time derived-index
  rebuilds; a steady-state caller must use `read-doc` or `read-documents`."
  [engine {:keys [partition kind]}]
  (->> (read-stable
        engine
        (fn []
          {:docs (->> (engine/scan engine (keys/partition-doc-prefix partition))
                      (keep (fn [[k v]]
                              (when (= kind (:storage/kind v))
                                (unwrap [k v]))))
                      vec)}))
       :docs))

(defn- group-by-kind [entries]
  (group-by (fn [[_ v]] (:storage/kind v)) entries))

(defn- docs-of [grouped kind]
  (mapv unwrap (get grouped kind [])))

(defn- sorted-docs [docs]
  (vec (sort-by (fn [doc] [(or (:seq doc) 0) (str (:message-id doc ""))]) docs)))

(defn- board [grouped]
  {:tasks (docs-of grouped :task)
   :actions (docs-of grouped :action)
   :actors (docs-of grouped :actor)
   :token-slots (docs-of grouped :token-slot)})

(defn- relay [grouped]
  {:tenures (docs-of grouped :tenure)
   :ledgers (docs-of grouped :ledger)
   :runs (docs-of grouped :run)})

(defn- records [grouped known-kinds]
  (into {}
        (keep (fn [[kind entries]]
                (when-not (contains? known-kinds kind)
                  [kind (mapv unwrap entries)])))
        grouped))

(defn partition-head
  "Return `{:version n :state doc}` for one session's owner partition.

  This is the point read behind `state-from-view`'s corruption check: the
  partition version and the `:owner-state`/`:owner-session` document, without
  scanning the partition's obligations or board documents."
  [engine {:keys [partition session-id]}]
  (let [scoped (fn [session-scope]
                 (let [grouped (group-by-kind
                                (engine/scan engine
                                             (keys/session-doc-prefix partition session-scope)))]
                   (or (first (docs-of grouped :owner-session))
                       (first (docs-of grouped :owner-state)))))
        state-doc (or (scoped session-id) (scoped nil))
        version (or (engine/get-value engine
                                      (keys/partition-meta-key partition "version"))
                    0)]
    {:version version :state state-doc}))

(def ^:private session-kinds
  #{:session :owner-session :owner-state :message :tenure :ledger :run})

(def ^:private partition-kinds
  #{:task :action :actor :token-slot})

(def known-document-kinds
  "Every document kind a `session-view` caller reads in this build.

  The default view scans these kind prefixes and never deserializes another
  kind.  A new current-state kind must be added here, or its reader must pass
  `:include-unknown-kinds? true` and accept the wider scan.  The explicit
  escape exists so a reader can still see a document written by a newer
  writer instead of silently losing it."
  (into
   #{;; session persistence documents
     :session-record :session-summary :session-index :session-command
     :session-ref-index
     ;; derived full-text index documents; read point-wise today, registered
     ;; here so a future view reader cannot silently lose them
     :search-index :search-posting :search-session
     ;; artifacts and the durable command gate use the owner partition too
     :artifact
     ;; app configuration and backup restore safety
     :app-config :restore-safety :backup-import
     ;; materials
     :materials-root :materials-memory
     :materials-worldbook-book :materials-worldbook-entry
     :materials-worldbook-session
     :materials-instruction-card :materials-instruction-session
     :materials-instruction-turn
     ;; scheduler
     :scheduler-root :scheduler-schedule :scheduler-run
     ;; diagnostics
     :diagnostics-request :diagnostics-user-send :diagnostics-tool-call
     :diagnostics-service-usage :diagnostics-price
     :diagnostics-log-policy :diagnostics-log-entry
     ;; repository Steward
     :steward-state
     ;; storage disk-smoke documents
     :smoke}
   (concat session-kinds partition-kinds)))

(defn- scan-document-scope
  "Scan one document scope, either by known kind or across every kind."
  [engine partition session-id include-unknown-kinds?]
  (if include-unknown-kinds?
    (engine/scan engine (keys/session-doc-prefix partition session-id))
    (mapcat (fn [kind]
              (engine/scan engine
                           (keys/kind-doc-prefix partition session-id kind)))
            known-document-kinds)))

(defn session-view
  "Return the consistent view for one session inside one partition.

  `opts`: `:partition` (required), `:session-id` (optional),
  `:include-partition-scope?` (default true), `:include-unknown-kinds?`
  (default false).

  Without a session the view covers the partition-scoped board and documents.
  With true a session view also merges the partition scope.  The scan is
  narrowed to `known-document-kinds`, so a board or diagnostics view does not
  deserialize unrelated session records or messages.  A caller that must see
  a kind that is not in that set passes `:include-unknown-kinds? true`, which
  scans the full scopes instead.

  `:include-partition-scope? false` skips the partition-scope documents and
  obligations entirely; callers that need one partition-scoped document use
  `read-doc` instead."
  [engine {:keys [partition session-id include-partition-scope?
                  include-unknown-kinds?]
           :or {include-partition-scope? true
                include-unknown-kinds? false}
           :as opts}]
  (when-not (and (string? partition) (seq partition))
    (err/raise :STORAGE_INVALID_OPTIONS "read-session-view needs :partition"
               {:opts opts}))
  (read-stable
   engine
   (fn []
     (let [session-entries   (if session-id
                               (scan-document-scope engine partition session-id
                                                    include-unknown-kinds?)
                               [])
           partition-entries (when include-partition-scope?
                               (scan-document-scope engine partition nil
                                                    include-unknown-kinds?))
           ob-entries        (cond
                               (not include-partition-scope?)
                               (if session-id
                                 (engine/scan engine
                                              (keys/session-obligation-prefix partition session-id))
                                 [])

                               session-id
                               (concat
                                (engine/scan engine
                                             (keys/session-obligation-prefix partition session-id))
                                (engine/scan engine
                                             (keys/session-obligation-prefix partition nil)))

                               :else
                               (engine/scan engine
                                            (keys/session-obligation-prefix partition nil)))
           grouped           (merge-with into
                                         (group-by-kind session-entries)
                                         (group-by-kind partition-entries))
           session-doc       (first (docs-of grouped :session))
           state-doc         (or (first (docs-of grouped :owner-session))
                                 (first (docs-of grouped :owner-state)))
           open-obligations  (->> ob-entries
                                  (map unwrap)
                                  (filter :open?)
                                  (sort-by (fn [o] [(or (:due-at o) Long/MAX_VALUE)
                                                    (str (:obligation/id o))]))
                                  vec)
           partition-version (or (engine/get-value engine
                                                   (keys/partition-meta-key partition "version"))
                                 0)]
       {:version partition-version
        :partition partition
        :session-id session-id
        :state state-doc
        :session session-doc
        :messages (sorted-docs (docs-of grouped :message))
        :board (board grouped)
        :relay (relay grouped)
        :open-obligations open-obligations
        :records (records grouped (into session-kinds partition-kinds))
        :snapshot {:store-id (engine/get-value engine (keys/meta-key "store-id"))
                   :format-version (engine/get-value engine (keys/meta-key "format-version"))
                   :version partition-version
                   :commit (engine/get-value engine
                                             (keys/partition-meta-key partition "last-commit"))}}))))

(declare partitions)

(defn open-obligations
  "Return open obligations.

  `opts`:
    :partition                one partition, or nil with :all-partitions? true
    :session-id               optional session scope
    :include-partition-scope? include partition-scoped obligations when a
                              session is named (default true)
    :all-partitions?          scan every initialized partition
    :now-ms, :due-only?       due filter
    :open-only?               default true

  A session scan includes the partition scope by default because board,
  token and report obligations are partition-scoped, not session-scoped."
  [engine {:keys [partition session-id now-ms due-only? open-only?
                  include-partition-scope? all-partitions?]
           :or   {open-only? true include-partition-scope? true}}]
  (when-not (or all-partitions?
                (and (string? partition) (seq partition)))
    (err/raise :STORAGE_INVALID_OPTIONS
               "list-open-obligations needs :partition or :all-partitions? true"
               {:opts {:partition partition :session-id session-id}}))
  (read-stable
   engine
   (fn []
     (let [ps        (if all-partitions?
                       (vec (sort (keys (partitions engine))))
                       [partition])
           prefixes  (mapcat (fn [p]
                               (cond
                                 (nil? session-id)
                                 [(keys/partition-obligation-prefix p)]

                                 include-partition-scope?
                                 [(keys/session-obligation-prefix p session-id)
                                  (keys/session-obligation-prefix p nil)]

                                 :else
                                 [(keys/session-obligation-prefix p session-id)]))
                             ps)
           docs      (mapcat (fn [prefix]
                               (map unwrap (engine/scan engine prefix)))
                             prefixes)
           docs      (if open-only? (filter :open? docs) docs)
           docs      (if due-only?
                       (filter (fn [o]
                                 (let [due (or (:due-at o) (:retry-at o))]
                                   (and (number? due)
                                        (number? now-ms)
                                        (<= due now-ms))))
                               docs)
                       docs)
           docs      (vec (sort-by (fn [o] [(str (:storage/partition o))
                                            (or (:due-at o) (:retry-at o)
                                                Long/MAX_VALUE)
                                            (str (:obligation/id o))])
                                   docs))
           version   (when-not all-partitions?
                       (or (engine/get-value engine
                                             (keys/partition-meta-key partition "version"))
                           0))]
       {:version version
        :partition partition
        :partitions ps
        :session-id session-id
        :obligations docs
        :count (count docs)}))))

(defn partitions
  "Return `{partition version}` for every initialized partition."
  [engine]
  (->> (engine/scan engine (keys/partition-meta-prefix))
       (keep (fn [[k v]]
               (when (str/ends-with? (str k) "/version")
                 [(keys/partition-from-meta-key k) v])))
       (into {})))

(defn- export-docs [engine partition]
  (mapv (fn [[k v]]
          (let [{:keys [kind id session-id]} (keys/doc-key-parts k)]
            {:kind kind :session-id session-id :id id :doc (:doc v)}))
        (engine/scan engine (keys/partition-doc-prefix partition))))

(defn- export-obligations [engine partition]
  (mapv (fn [[k v]]
          (let [{:keys [session-id id]} (keys/obligation-key-parts k)]
            {:session-id session-id :id id :doc (:doc v)}))
        (engine/scan engine (keys/partition-obligation-prefix partition))))

(defn- export-blob-refs [engine partition]
  (mapv (fn [[k v]]
          (let [{:keys [id]} (keys/blob-ref-key-parts k)]
            {:id id :doc (:doc v)}))
        (engine/scan engine (keys/partition-blob-ref-prefix partition))))

(def ^:private export-sections
  "The parts of a partition that `export-state` can copy independently."
  #{:docs :obligations :blob-refs})

(defn- export-partition
  [engine partition version sections]
  (cond-> {:version version
           :meta (engine/get-value engine
                                   (keys/partition-meta-key partition "meta"))
           :last-commit (engine/get-value engine
                                          (keys/partition-meta-key partition "last-commit"))}
    (contains? sections :docs)
    (assoc :docs (export-docs engine partition))

    (contains? sections :obligations)
    (assoc :obligations (export-obligations engine partition))

    (contains? sections :blob-refs)
    (assoc :blob-refs (export-blob-refs engine partition))))

(defn export-state
  "Build a self-describing, format-versioned export package.

  `opts`:
    `:partition` optional (nil exports every partition)
    `:at` optional
    `:sections` optional set of `:docs`, `:obligations`, `:blob-refs`
                (default all).  A caller that only needs references can
                avoid copying every document.
    `:exclude-partition?` optional predicate applied before any partition is
                assembled.  A whole-store export uses it to skip rebuildable
                derived partitions instead of reading and then discarding
                them.  An explicit `:partition` selection is always honored."
  [engine {:keys [partition at sections exclude-partition?]}]
  (let [sections (or sections export-sections)]
    (when-not (and (set? sections) (every? export-sections sections))
      (err/raise :STORAGE_INVALID_OPTIONS
                 "export sections must be a subset of docs/obligations/blob-refs"
                 {:sections sections}))
    (read-stable
     engine
     (fn []
       (let [all-partitions (partitions engine)
             selected      (if partition
                             (if (contains? all-partitions partition)
                               {partition (get all-partitions partition)}
                               {})
                             (if exclude-partition?
                               (into (sorted-map)
                                     (remove (fn [[p _]]
                                               (exclude-partition? p)))
                                     all-partitions)
                               all-partitions))
             exported      (into (sorted-map)
                                 (map (fn [[p version]]
                                        [p (export-partition engine p version sections)]))
                                 selected)]
         {:format-version 1
          :engine :datalevin-kv
          :store-id (engine/get-value engine (keys/meta-key "store-id"))
          :store-format-version (engine/get-value engine (keys/meta-key "format-version"))
          :exported-at (or at (str (java.time.Instant/now)))
          :complete? (nil? partition)
          :partitions exported})))))
