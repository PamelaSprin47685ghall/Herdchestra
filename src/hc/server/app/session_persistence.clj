(ns hc.server.app.session-persistence
  "Adapt the selected storage engine to
  `hc.server.sessions.ports/SessionPersistence`.

  Session records, the session index, and the creation-command index live in
  one partition so a session commit, its list entry, and its command-id
  lookup are atomic.  The partition version is the compare-and-set token;
  the adapter additionally checks the session's own `:revision`, which is
  what the session domain observes."
  (:require [hc.server.sessions.ports :as ports]
            [hc.server.blob-store.refs :as blob-refs]
            [hc.server.storage :as storage])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util UUID)))

(def ^:private sessions-partition "sessions")
(def ^:private config-partition "config")
(def ^:private session-kind :session-record)
(def ^:private index-kind :session-index)
(def ^:private session-command-kind :session-command)
(def ^:private config-kind :app-config)
(def ^:private index-id "sessions")
(def ^:private config-id "config")

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

(defn- records-of
  [view kind]
  (get-in view [:records kind] []))

(defn- find-record
  [view kind id]
  (first (filter #(or (= id (:session-id %))
                      (= id (:id %))
                      (= id (:storage/id %)))
                 (records-of view kind))))

(defn- read-view
  [storage partition session-id]
  (storage/read-session-view storage
                             {:partition partition :session-id session-id}))

(defn- index-ids
  [view]
  (or (some-> (find-record view index-kind index-id) :ids vec)
      (mapv :session-id (records-of view session-kind))))

(defn- put-index
  [ids]
  {:kind index-kind :id index-id :doc {:id index-id :ids (vec (distinct ids))}})

(defn- error-status
  [t]
  (let [code (try (storage/error-code t) (catch Throwable _ nil))
        data (ex-data t)]
    {:status :unknown
     :error (cond-> {:code (or code :PERSISTENCE_ERROR)
                     :message (or (ex-message t) "storage operation failed")}
              data (assoc :details (dissoc data :error)))} ))

(defn persistence
  "Create a SessionPersistence backed by STORAGE."
  [storage]
  (let [lock (Object.)]
    (reify ports/SessionPersistence
      (read-session [_ session-id]
        (try
          (let [view (read-view storage sessions-partition session-id)
                session (find-record view session-kind session-id)]
            (if session
              {:status :found :session session}
              {:status :missing}))
          (catch clojure.lang.ExceptionInfo t
            (error-status t))))

      (commit-session! [_ expected-revision session]
        (locking lock
          (try
            (let [session-id (:session-id session)
                  view (read-view storage sessions-partition session-id)
                  stored (find-record view session-kind session-id)
                  ;; Extension scope is session state.  Preserve the fields a
                  ;; writer did not include in this commit; a legacy record or
                  ;; a create path that does not know them must not erase them.
                  session (merge (select-keys stored
                                              [:mcp-role :mcp-servers
                                               :mcp-session-scope])
                                 session)
                  actual (long (or (:revision stored) 0))
                  expected (long (or expected-revision 0))
                  ids (index-ids view)
                  sessions (records-of view session-kind)
                  before-refs (blob-refs/sessions-refs sessions)
                  after-sessions (if (some #(= session-id (:session-id %))
                                           sessions)
                                   (mapv #(if (= session-id (:session-id %))
                                            session
                                            %)
                                         sessions)
                                   (conj (vec sessions) session))
                  after-refs (blob-refs/sessions-refs after-sessions)
                  ref-change (blob-refs/diff before-refs after-refs)
                  identity (:creation-command session)
                  command-doc-id (when identity
                                   (command-index-id identity))
                  command-entry (when command-doc-id
                                  (find-record view session-command-kind
                                               command-doc-id))
                  command-session (when command-entry
                                    (find-record view session-kind
                                                 (:session-id command-entry)))
                  duplicate? (and command-entry
                                  command-session
                                  (not= session-id
                                        (:session-id command-entry)))
                  puts (cond-> [{:kind session-kind
                                 :id session-id
                                 :doc session}
                                (put-index (conj ids session-id))]
                         identity
                         (conj (command-index-entry session)))]
              (cond
                duplicate?
                {:status :duplicate
                 :session (find-record view session-kind
                                       (:session-id command-entry))}

                (not= actual expected)
                {:status :conflict :revision actual}

                :else
                (let [result
                      (storage/commit-migration!
                       storage
                       (cond-> {:migration-id (str (UUID/randomUUID))
                                :partition sessions-partition
                                :expected-version (:version view)
                                :reason :session-commit
                                :put puts}
                         (or (seq (:add ref-change))
                             (seq (:release ref-change)))
                         (assoc :blob-refs ref-change)))]
                  {:status :committed :revision (:version result)})))
            (catch clojure.lang.ExceptionInfo t
              (if (= :STORAGE_VERSION_CONFLICT
                     (try (storage/error-code t) (catch Throwable _ nil)))
                {:status :conflict :revision nil}
                (error-status t))))))

      (list-session-ids [_]
        (try
          (let [view (read-view storage sessions-partition index-id)]
            {:status :ok :ids (vec (distinct (index-ids view)))})
          (catch clojure.lang.ExceptionInfo t
            (error-status t))))

      (delete-session! [_ expected-revision session-id]
        (locking lock
          (try
            (let [view (read-view storage sessions-partition session-id)
                  stored (find-record view session-kind session-id)]
              (cond
                (nil? stored)
                (let [erased (storage/erase-session-derived! storage session-id)]
                  {:status :missing
                   :external-effects (:external-effects erased)})

                (not= (long (or expected-revision 0))
                      (long (or (:revision stored) 0)))
                {:status :conflict :revision (:revision stored)}

                :else
                (let [erased (storage/erase-session-derived! storage session-id)
                      ;; The erasure commits the session-index update; the
                      ;; view read before it is stale.
                      view (read-view storage sessions-partition session-id)
                      ids (remove #{session-id} (index-ids view))
                      sessions (records-of view session-kind)
                      before-refs (blob-refs/sessions-refs sessions)
                      after-refs (blob-refs/sessions-refs
                                  (remove #(= session-id (:session-id %))
                                          sessions))
                      ref-change (blob-refs/diff before-refs after-refs)
                      command-deletes
                      (->> (records-of view session-command-kind)
                           (filter #(= session-id (:session-id %)))
                           (mapv (fn [entry]
                                   {:kind session-command-kind
                                    :id (:id entry)})))
                      _ (storage/commit-migration!
                         storage
                         (cond-> {:migration-id (str (UUID/randomUUID))
                                  :partition sessions-partition
                                  :expected-version (:version view)
                                  :reason :session-delete
                                  :put [(put-index ids)]
                                  :delete
                                  (into [{:kind session-kind :id session-id}]
                                        command-deletes)}
                           (or (seq (:add ref-change))
                               (seq (:release ref-change)))
                           (assoc :blob-refs ref-change)))]
                  {:status :deleted
                   :external-effects (:external-effects erased)})))
            (catch clojure.lang.ExceptionInfo t
              (if (= :STORAGE_VERSION_CONFLICT
                     (try (storage/error-code t) (catch Throwable _ nil)))
                {:status :conflict :revision nil}
                (error-status t))))))

      (read-config [_]
        (try
          (let [view (read-view storage config-partition config-id)
                config (some-> (find-record view config-kind config-id)
                               (dissoc :session-id))]
            (if config
              {:status :found :config config}
              {:status :missing}))
          (catch clojure.lang.ExceptionInfo t
            (error-status t))))

      (commit-config! [_ expected-revision config]
        (locking lock
          (try
            (let [view (read-view storage config-partition config-id)
                  stored (find-record view config-kind config-id)
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
                        :expected-version (:version view)
                        :reason :config-commit
                        :put [{:kind config-kind :id config-id :doc doc}]})]
                  {:status :committed :revision (:version result)})))
            (catch clojure.lang.ExceptionInfo t
              (if (= :STORAGE_VERSION_CONFLICT
                     (try (storage/error-code t) (catch Throwable _ nil)))
                {:status :conflict :revision nil}
                (error-status t))))))

      ports/SessionCommandLookup
      (find-session-by-command [_ identity]
        (try
          (let [view (read-view storage sessions-partition nil)
                entry (find-record view session-command-kind
                                   (command-index-id identity))]
            (if-let [session-id (:session-id entry)]
              (let [session (find-record view session-kind session-id)]
                (if session
                  {:status :found :session session}
                  {:status :missing}))
              {:status :missing}))
          (catch clojure.lang.ExceptionInfo t
            (error-status t))))

      ports/SessionCommandClaim
      (claim-session-command! [_ identity session-id]
        (locking lock
          (try
            (let [view (read-view storage sessions-partition session-id)
                  target (find-record view session-kind session-id)
                  id (command-index-id identity)
                  entry (find-record view session-command-kind id)
                  existing (when entry
                             (find-record view session-kind
                                          (:session-id entry)))]
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
                        :expected-version (:version view)
                        :reason :session-command-claim
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
                (error-status t)))))))))
