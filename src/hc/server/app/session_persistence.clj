(ns hc.server.app.session-persistence
  "Adapt the selected storage engine to
  `hc.server.sessions.ports/SessionPersistence`.

  Session records, the session index, the creation-command index, the compact
  per-session summary and the blob-reference counts live in one partition, so
  a session commit, its list entry, its command-id lookup and its derived
  documents are atomic.  The partition version is the compare-and-set token;
  the adapter additionally checks the session's own `:revision`, which is
  what the session domain observes.

  Steady-state operations are point operations:

    - `read-session` and the commit precondition point-read one
      `:session-record`;
    - `list-session-ids` point-reads the `:session-index`;
    - `read-session-summary` point-reads the compact `:session-summary` and
      falls back to one record only when that derived document is absent;
    - the creation-command check point-reads one `:session-command`;
    - blob references change by the delta of one session against the compact
      `:session-ref-index` count map.  The count map keeps the previous union
      semantics: a blob shared by two sessions is released only when the last
      owner drops it;
    - `read-config` and `commit-config!` point-read the partition-scoped
      `:app-config` document instead of scanning the config partition.

  A whole-partition read remains only as the one-time compatibility fallback
  for a store whose index document predates this adapter or whose derived
  documents a restore just removed.  Every normal commit writes the index and
  the summary, so that path cannot recur."
  (:require [hc.server.blob-store.refs :as blob-refs]
            [hc.server.sessions.drafts :as drafts]
            [hc.server.sessions.ports :as ports]
            [hc.server.storage :as storage])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util UUID)))

(def ^:private sessions-partition "sessions")
(def ^:private config-partition "config")
(def ^:private session-kind :session-record)
(def ^:private summary-kind :session-summary)
(def ^:private index-kind :session-index)
(def ^:private ref-index-kind :session-ref-index)
(def ^:private session-command-kind :session-command)
(def ^:private config-kind :app-config)
(def ^:private index-id "sessions")
(def ^:private config-id "config")

(def ^:private last-message-text-limit
  "Maximum characters of the last visible message kept in the summary."
  240)

(defn- command-index-id
  "Stable document id for one creation command identity.

  The identity includes the scope and epoch, so the same command-id in a new
  epoch is a different index entry.  A SHA-256 digest keeps the id within the
  storage segment limit even for long command-ids."
  [identity]
  (let [canonical (pr-str [(:scope identity)
                           (:command-epoch identity)
                           (:command-id identity)])
        digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest
                       (.getBytes ^String canonical StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xFF)) bytes))))

(defn- command-index-entry
  [session]
  (when-let [identity (:creation-command session)]
    (let [id (command-index-id identity)]
      {:kind session-command-kind
       :id id
       :doc {:id id
             :identity identity
             :session-id (:session-id session)
             :source :creation}})))

(defn- read-doc
  [storage session-id kind id]
  (storage/read-document storage
                         {:partition sessions-partition
                          :session-id session-id
                          :kind kind
                          :id id}))

(defn- read-session-record-doc
  "Read one `:session-record` document by exact key.

  Session records are stored partition-scoped (`session-id` nil, the `_`
  segment).  Some seeded or migrated stores also carry a session-scoped copy;
  the partition-scoped record is authoritative because every commit writes
  it, so it is checked first and the scoped copy is only a fallback.  This
  point read replaces the former full `sessions` partition scan performed for
  every session on every list or preflight pass."
  [storage session-id]
  (or (read-doc storage nil session-kind session-id)
      (read-doc storage session-id session-kind session-id)))

(defn- read-index-doc
  [storage]
  (read-doc storage nil index-kind index-id))

(defn- read-command-doc
  [storage command-doc-id]
  (read-doc storage nil session-command-kind command-doc-id))

(defn- read-summary-doc
  [storage session-id]
  (read-doc storage nil summary-kind session-id))

(defn- read-ref-index-doc
  [storage]
  (read-doc storage nil ref-index-kind index-id))

(defn- read-config-doc
  "Point-read the partition-scoped `:app-config` document."
  [storage]
  (storage/read-document storage
                         {:partition config-partition
                          :session-id nil
                          :kind config-kind
                          :id config-id}))

(defn- put-index
  [ids]
  {:kind index-kind :id index-id :doc {:id index-id :ids (vec (distinct ids))}})

(defn- message-listed?
  "True when the message exists and is not deleted or folded.

  This matches the historical summary count in `ports`, which did not count
  nudge messages as hidden."
  [session id]
  (let [message (get-in session [:messages id])]
    (and message
         (not (:deleted? message))
         (not (:folded? message)))))

(defn- last-visible-message
  "Short projection of the last visible message.

  Nudge messages are internal and never visible.  The text is clipped so a
  large message cannot turn the summary document into a content store."
  [session]
  (let [id (last (filter (fn [id]
                           (let [message (get-in session [:messages id])]
                             (and message
                                  (not (:deleted? message))
                                  (not (:folded? message))
                                  (not (:nudge? message))
                                  (not (:nudge-id message)))))
                         (:message-order session)))]
    (when id
      (let [message (get-in session [:messages id])
            content (get-in message [:versions (:current-version message) :content])
            text (when (string? content)
                   (subs content 0
                         (int (min last-message-text-limit (count content)))))]
        (cond-> {:message-id id :role (:role message)}
          (some? (:created-at message))
          (assoc :created-at (:created-at message))

          text
          (assoc :text text))))))

(defn- visible-message-count
  [session]
  (long (or (:visible-message-count session)
            (count (filter #(message-listed? session %) (:message-order session))))))

(defn- has-draft?
  [session]
  (boolean (some #(not (drafts/blank? %)) (vals (:drafts session)))))

(defn- summary-doc
  "Build the compact `:session-summary` document.

  It carries the fields the list projections need
  (`hc.server.sessions/session-summary` and the app snapshot row), plus the
  visible-message count, the draft flag and the last visible message
  projection.  Message bodies, message order, drafts, runs, calls and the
  context snapshot never enter it."
  [session]
  (let [last-message (last-visible-message session)]
    (cond-> (-> (select-keys session
                             [:session-id :owner-id :title :title-source
                              :status :pinned? :temporary? :deleted-at
                              :created-at :activity-at :revision :updated-at
                              :origin-session-id :origin-message-id
                              :assistant-id :model-binding :language
                              :current-run-id :workspace-id :client-id])
                (assoc :visible-message-count (visible-message-count session)
                       :has-draft? (has-draft? session)))
      last-message
      (assoc :last-visible-message last-message))))

(defn- all-session-records
  "Return `{session-id record}` for every session in the partition.

  A partition-scoped record wins over a session-scoped legacy copy.  This is
  a full partition scan and exists only as the one-time fallback for a store
  whose session index or reference-count index is absent."
  [storage]
  (->> (storage/read-partition-documents storage
                                         {:partition sessions-partition
                                          :kind session-kind})
       (reduce (fn [records doc]
                 (let [session-id (or (:session-id doc)
                                      (:storage/session-id doc)
                                      (:storage/id doc))]
                   (if (nil? session-id)
                     records
                     (if (nil? (:storage/session-id doc))
                       (assoc records session-id doc)
                       (update records session-id #(or % doc))))))
               {})))

(defn- ref-counts-from-sessions
  "Count how many stored sessions reference each blob id."
  [records]
  (reduce (fn [counts session]
            (reduce (fn [counts id] (update counts id (fnil inc 0)))
                    counts
                    (keys (blob-refs/session-refs session))))
          {}
          (vals records)))

(defn- session-index-state
  "Read the session-id index and, when REFS? is true, the reference counts.

  A missing index document triggers at most one full partition scan, shared
  by the ids and the counts.  The index is written by every commit, so this
  scan runs at most once per legacy store; steady-state commits never reach
  it.  `:ref-index-present? false` tells the caller that the counts were
  rebuilt from the records rather than read from a maintained count index."
  [storage refs?]
  (let [index (read-index-doc storage)
        ref-index (when refs? (read-ref-index-doc storage))
        ref-counts-known? (and refs? (some? (:counts ref-index)))
        scan? (or (nil? index)
                  (nil? (:ids index))
                  (and refs? (not ref-counts-known?)))
        records (when scan? (all-session-records storage))]
    {:ids (cond
            (:ids index) (vec (distinct (:ids index)))
            records (vec (distinct (keys records)))
            :else [])
     :ref-counts (when refs?
                   (if ref-counts-known?
                     (:counts ref-index)
                     (ref-counts-from-sessions records)))
     :ref-index-present? ref-counts-known?}))

(defn- ref-change
  "Compute one session's blob-reference delta against the count index.

  Returns nil when the session claims the same references as before.  The
  result keeps the old union semantics: a released id is dropped from the
  reference table only when its count reaches zero, so a blob shared with a
  branched session stays alive.

  The count document is bounded by distinct blob references, not by message
  count; the store's document guard refuses an implausibly large index
  instead of silently truncating it."
  [before-refs after-refs {:keys [ref-counts ref-index-present?]}]
  (let [{:keys [add release]} (blob-refs/diff before-refs after-refs)]
    (when (or (seq add) (seq release))
      (let [counts ref-counts
            added (mapv :id add)
            removed release
            with-adds (reduce (fn [counts id]
                                (update counts id (fnil inc 0)))
                              counts
                              added)
            final (reduce (fn [counts id]
                            (let [n (dec (long (or (get counts id) 0)))]
                              (if (pos? n)
                                (assoc counts id n)
                                (dissoc counts id))))
                          with-adds
                          removed)
            add-refs (filterv (fn [ref]
                                (zero? (long (or (get counts (:id ref)) 0))))
                              add)
            keep-refs (vec (filter (fn [id]
                                     (and (zero? (long (or (get final id) 0)))
                                          ;; A count index that has never seen
                                          ;; this id cannot prove the other
                                          ;; owners are gone.  Keep the
                                          ;; reference rather than risk
                                          ;; deleting a blob another session
                                          ;; still uses.
                                          (or (not ref-index-present?)
                                              (contains? counts id))))
                                   removed))]
        {:add add-refs
         :release keep-refs
         :index-doc {:kind ref-index-kind
                     :id index-id
                     :doc {:id index-id :counts final}}}))))

(defn- error-status
  [t]
  (let [code (try (storage/error-code t) (catch Throwable _ nil))
        data (ex-data t)]
    {:status :unknown
     :error (cond-> {:code (or code :PERSISTENCE_ERROR)
                     :message (or (ex-message t) "storage operation failed")}
              data (assoc :details (dissoc data :error)))}))

(defn- notify!
  "Run one advisory index callback.  A callback failure never changes the
  storage outcome: its index can always be rebuilt from current state."
  [f & args]
  (when (ifn? f)
    (try
      (apply f args)
      (catch Throwable _ nil)))
  nil)

(defn persistence
  "Create a SessionPersistence backed by STORAGE.

  The optional second argument carries advisory hooks for a derived history
  index:

    :on-session-committed  (fn [session]) after a session commit succeeds
    :on-session-deleted    (fn [session-id]) after a session delete succeeds
    :history-index         an object satisfying
                           `hc.server.sessions.ports/SessionHistoryIndex`;
                           used only when the matching callback is absent

  A hook runs after the storage transaction.  Its exception is swallowed, so
  it can never turn a committed write into an unknown outcome."
  ([storage]
   (persistence storage nil))
  ([storage {:keys [on-session-committed on-session-deleted history-index]}]
   (let [lock (Object.)
         history? (satisfies? ports/SessionHistoryIndex history-index)
         committed! (or on-session-committed
                        (when history?
                          (fn [session]
                            (ports/session-committed! history-index session))))
         deleted! (or on-session-deleted
                      (when history?
                        (fn [session-id]
                          (ports/session-deleted! history-index session-id))))]
     (reify ports/SessionPersistence
       (read-session [_ session-id]
         (try
           (if-let [session (read-session-record-doc storage session-id)]
             {:status :found :session session}
             {:status :missing})
           (catch clojure.lang.ExceptionInfo t
             (error-status t))))

       (commit-session! [_ expected-revision session]
         (locking lock
           (try
             (let [session-id (:session-id session)
                   ;; Read the partition version first: any commit that lands
                   ;; after this read makes the compare-and-set fail instead
                   ;; of silently overwriting a newer record.
                   partition-version (storage/read-partition-version
                                      storage sessions-partition)
                   stored (read-session-record-doc storage session-id)
                   ;; Extension scope is session state.  Preserve the fields a
                   ;; writer did not include in this commit; a legacy record
                   ;; or a create path that does not know them must not erase
                   ;; them.
                   session (merge (select-keys stored
                                               [:mcp-role :mcp-servers
                                                :mcp-session-scope])
                                  session)
                   actual (long (or (:revision stored) 0))
                   expected (long (or expected-revision 0))
                   identity (:creation-command session)
                   command-doc-id (when identity
                                    (command-index-id identity))
                   command-entry (when command-doc-id
                                   (read-command-doc storage command-doc-id))
                   command-session (when command-entry
                                     (read-session-record-doc
                                      storage (:session-id command-entry)))
                   duplicate? (and command-entry
                                   command-session
                                   (not= session-id
                                         (:session-id command-entry)))]
               (cond
                 duplicate?
                 {:status :duplicate :session command-session}

                 (not= actual expected)
                 {:status :conflict :revision actual}

                 :else
                 (let [before-refs (blob-refs/session-refs stored)
                       after-refs (blob-refs/session-refs session)
                       refs? (not= (set (keys before-refs))
                                   (set (keys after-refs)))
                       index-state (session-index-state storage refs?)
                       ref-change (when refs?
                                    (ref-change before-refs after-refs
                                                index-state))
                       puts (cond-> [{:kind session-kind
                                      :id session-id
                                      :doc session}
                                     {:kind summary-kind
                                      :id session-id
                                      :doc (summary-doc session)}
                                     (put-index (conj (:ids index-state)
                                                      session-id))]
                              identity
                              (conj (command-index-entry session))

                              ref-change
                              (conj (:index-doc ref-change)))
                       result
                       (storage/commit-migration!
                        storage
                        (cond-> {:migration-id (str (UUID/randomUUID))
                                 :partition sessions-partition
                                 :expected-version partition-version
                                 :reason :session-commit
                                 :lightweight-return? true
                                 :put puts}
                          (seq (:add ref-change))
                          (assoc-in [:blob-refs :add] (:add ref-change))

                          (seq (:release ref-change))
                          (assoc-in [:blob-refs :release]
                                    (:release ref-change))))]
                   (notify! committed! session)
                   {:status :committed :revision (:version result)})))
             (catch clojure.lang.ExceptionInfo t
               (if (= :STORAGE_VERSION_CONFLICT
                      (try (storage/error-code t) (catch Throwable _ nil)))
                 {:status :conflict :revision nil}
                 (error-status t))))))

       (list-session-ids [_]
         (try
           {:status :ok :ids (:ids (session-index-state storage false))}
           (catch clojure.lang.ExceptionInfo t
             (error-status t))))

       (delete-session! [_ expected-revision session-id]
         (locking lock
           (try
             (let [stored (read-session-record-doc storage session-id)]
               (cond
                 (nil? stored)
                 (let [erased (storage/erase-session-derived! storage
                                                              session-id)]
                   {:status :missing
                    :external-effects (:external-effects erased)})

                 (not= (long (or expected-revision 0))
                       (long (or (:revision stored) 0)))
                 {:status :conflict :revision (:revision stored)}

                 :else
                 (let [erased (storage/erase-session-derived! storage
                                                               session-id)
                       ;; The erasure commits the session-index update; read
                       ;; the partition version afterwards for the compare.
                       partition-version (storage/read-partition-version
                                          storage sessions-partition)
                       before-refs (blob-refs/session-refs stored)
                       refs? (boolean (seq before-refs))
                       index-state (session-index-state storage refs?)
                       ids (vec (remove #{session-id} (:ids index-state)))
                       command-deletes
                       (->> (storage/read-documents
                             storage
                             {:partition sessions-partition
                              :session-id nil
                              :kind session-command-kind})
                            (filter #(= session-id (:session-id %)))
                            (mapv (fn [entry]
                                    {:kind session-command-kind
                                     :id (or (:id entry)
                                             (:storage/id entry))})))
                       ref-change (when refs?
                                    (ref-change before-refs {} index-state))
                       result
                       (storage/commit-migration!
                        storage
                        (cond-> {:migration-id (str (UUID/randomUUID))
                                 :partition sessions-partition
                                 :expected-version partition-version
                                 :reason :session-delete
                                 :lightweight-return? true
                                 :put (cond-> [(put-index ids)]
                                        ref-change
                                        (conj (:index-doc ref-change)))
                                 :delete (into [{:kind session-kind
                                                 :id session-id}
                                                {:kind summary-kind
                                                 :id session-id}]
                                               command-deletes)}
                          (seq (:release ref-change))
                          (assoc :blob-refs {:release
                                             (:release ref-change)})))]
                   (notify! deleted! session-id)
                   {:status :deleted
                    :external-effects (:external-effects erased)})))
             (catch clojure.lang.ExceptionInfo t
               (if (= :STORAGE_VERSION_CONFLICT
                      (try (storage/error-code t) (catch Throwable _ nil)))
                 {:status :conflict :revision nil}
                 (error-status t))))))

       (read-config [_]
         (try
           (let [config (some-> (read-config-doc storage)
                                (dissoc :session-id))]
             (if config
               {:status :found :config config}
               {:status :missing}))
           (catch clojure.lang.ExceptionInfo t
             (error-status t))))

       (commit-config! [_ expected-revision config]
         (locking lock
           (try
             (let [partition-version (storage/read-partition-version
                                      storage config-partition)
                   stored (read-config-doc storage)
                   actual (long (or (:revision stored) 0))
                   expected (long (or expected-revision 0))]
               (if (not= actual expected)
                 {:status :conflict :revision actual}
                 (let [doc (assoc config :revision (inc actual))
                       result
                       (storage/commit-migration!
                        storage
                        {:migration-id (str (UUID/randomUUID))
                         :partition config-partition
                         :expected-version partition-version
                         :reason :config-commit
                         :lightweight-return? true
                         :put [{:kind config-kind :id config-id :doc doc}]})]
                   {:status :committed :revision (:version result)})))
           (catch clojure.lang.ExceptionInfo t
             (if (= :STORAGE_VERSION_CONFLICT
                    (try (storage/error-code t) (catch Throwable _ nil)))
               {:status :conflict :revision nil}
               (error-status t))))))

       ports/SessionSummaryRead
       (read-session-summary-doc [_ session-id]
         (try
           (if-let [summary (or (read-summary-doc storage session-id)
                                (some-> (read-session-record-doc storage
                                                                 session-id)
                                        ports/session-summary-view))]
             {:status :found :session summary}
             {:status :missing})
           (catch clojure.lang.ExceptionInfo t
             (error-status t))))

       ports/SessionCommandLookup
       (find-session-by-command [_ identity]
         (try
           (let [entry (read-command-doc storage (command-index-id identity))]
             (if-let [session-id (:session-id entry)]
               (if-let [session (read-session-record-doc storage session-id)]
                 {:status :found :session session}
                 {:status :missing})
               {:status :missing}))
           (catch clojure.lang.ExceptionInfo t
             (error-status t))))

       ports/SessionCommandClaim
       (claim-session-command! [_ identity session-id]
         (locking lock
           (try
             (let [partition-version (storage/read-partition-version
                                      storage sessions-partition)
                   target (read-session-record-doc storage session-id)
                   id (command-index-id identity)
                   entry (read-command-doc storage id)
                   existing (when entry
                              (read-session-record-doc
                               storage (:session-id entry)))]
               (cond
                 (nil? target)
                 {:status :missing}

                 (not= :active (:status target))
                 {:status :missing}

                 (and entry (= session-id (:session-id entry)))
                 {:status :unchanged}

                 (and entry existing)
                 {:status :duplicate :session existing}

                 :else
                 (let [result
                       (storage/commit-migration!
                        storage
                        {:migration-id (str (UUID/randomUUID))
                         :partition sessions-partition
                         :expected-version partition-version
                         :reason :session-command-claim
                         :lightweight-return? true
                         :put [{:kind session-command-kind
                                :id id
                                :doc {:id id
                                      :identity identity
                                      :session-id session-id
                                      :source :reuse}}]})]
                   {:status :claimed :revision (:version result)})))
             (catch clojure.lang.ExceptionInfo t
               (if (= :STORAGE_VERSION_CONFLICT
                      (try (storage/error-code t) (catch Throwable _ nil)))
                 {:status :conflict :revision nil}
                 (error-status t))))))))))
