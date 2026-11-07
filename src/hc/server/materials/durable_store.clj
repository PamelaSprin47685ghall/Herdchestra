(ns hc.server.materials.durable-store
  "Durable `hc.server.materials.store/Store` backed by `hc.server.storage`.

  This namespace is deliberately outside the `hc.server.materials` facade so
  the pure domain and its tests stay free of the Datalevin dependency.  The
  application assembly constructs one adapter from the already-open storage
  handle:

      (durable-store/durable-store storage)

  Layout in the `materials` partition:

      :materials-root                 id \"root\"   {:v :revision :seq :settings}
      :materials-memory               id <memory-id>
      :materials-worldbook-book       id <book-id>
      :materials-worldbook-entry      id <entry-id>
      :materials-worldbook-session    id <session-id>

  All documents are partition-scoped, so one read view reconstructs the whole
  library.  A commit writes the root and every changed/deleted entity in one
  `commit-migration!` call.  The root `:revision` is the domain CAS token; the
  storage partition version is the second fence that prevents a lost update
  between our read and the atomic write.

  This is a real adapter: it persists, reopens and reconstructs state.  It
  does not claim to be a second search index or a vector store."
  (:require [hc.server.materials.store :as store]
            [hc.server.storage :as storage])
  (:import (java.util UUID)))

(def materials-partition
  "Storage partition owned by the materials domain."
  "materials")

(def root-kind :materials-root)
(def memory-kind :materials-memory)
(def book-kind :materials-worldbook-book)
(def entry-kind :materials-worldbook-entry)
(def session-kind :materials-worldbook-session)
(def card-kind :materials-instruction-card)
(def card-session-kind :materials-instruction-session)
(def card-turn-kind :materials-instruction-turn)

(def entity-kinds
  [memory-kind book-kind entry-kind session-kind card-kind card-session-kind
   card-turn-kind])

(def all-kinds
  (into [root-kind] entity-kinds))

(defn- corrupt!
  [message details]
  (throw (ex-info message
                  (assoc details
                         :error :MATERIALS_STORE_CORRUPT
                         :materials/expected? true))))

(defn- storage-docs
  [view kind]
  (get-in view [:records kind] []))

(defn- storage-key?
  [k]
  (= "storage" (namespace k)))

(defn- strip-storage
  "Remove the storage envelope.  Keeping `:storage/*` keys inside the domain
  document would nest stale metadata on the next commit."
  [doc]
  (into {} (remove (fn [[k _]] (storage-key? k))) doc))

(defn- doc-identity
  [doc]
  (or (:storage/id doc) (:id doc)))

(defn- doc-map
  "Return `{[kind id] document}` for every materials document in VIEW.
  Duplicate identities are corruption, not a last-writer-wins merge."
  [view]
  (reduce (fn [acc kind]
            (reduce (fn [acc doc]
                      (let [id (doc-identity doc)
                            k [kind id]]
                        (when-not (string? id)
                          (corrupt! "materials document has no stable id"
                                    {:kind kind :document doc}))
                        (when (contains? acc k)
                          (corrupt! "materials document identity is duplicated"
                                    {:kind kind :id id}))
                        (assoc acc k (strip-storage doc))))
                    acc
                    (storage-docs view kind)))
          {}
          all-kinds))

(defn- docs-of
  [documents kind]
  (into {}
        (keep (fn [[[document-kind id] document]]
                (when (= kind document-kind)
                  [id document])))
        documents))

(defn- read-state
  "Read one consistent view and reconstruct the domain state.

  Returns `{:found? true :state state :revision n :partition-version v
  :documents documents}` or `{:found? false :partition-version v
  :documents {}}`.

  A missing root with surviving entity documents is corruption: silently
  treating it as an empty library would lose user memories."
  [storage-handle partition-name]
  (let [view (storage/read-session-view storage-handle
                                        {:partition partition-name})
        documents (doc-map view)
        root (get documents [root-kind "root"])]
    (if-not root
      (if (seq documents)
        (corrupt! "materials entity documents exist without a root"
                  {:partition partition-name
                   :document-count (count documents)})
        {:found? false
         :partition-version (long (or (:version view) 0))
         :documents {}})
      (let [state {:v (:v root)
                   :revision (:revision root)
                   :seq (:seq root)
                   :settings (:settings root)
                   :instruction-turn-receipts
                   (or (:instruction-turn-receipts root) {})
                   :memories (docs-of documents memory-kind)
                   :worldbook {:books (docs-of documents book-kind)
                               :entries (docs-of documents entry-kind)
                               :sessions (docs-of documents session-kind)}
                   :instruction-cards (docs-of documents card-kind)
                   :instruction-sessions (docs-of documents card-session-kind)
                   :instruction-turns (docs-of documents card-turn-kind)}]
        {:found? true
         :state state
         :revision (:revision state)
         :partition-version (long (or (:version view) 0))
         :documents documents}))))

(defn- state-documents
  "Return `{[kind id] document}` for NEXT-STATE."
  [next-state]
  (into {}
        (concat
         [[[root-kind "root"]
           (select-keys next-state
                        [:v :revision :seq :settings
                         :instruction-turn-receipts])]]
         (map (fn [[id memory]]
                [[memory-kind id] memory])
              (:memories next-state))
         (map (fn [[id book]]
                [[book-kind id] book])
              (get-in next-state [:worldbook :books]))
         (map (fn [[id entry]]
                [[entry-kind id] entry])
              (get-in next-state [:worldbook :entries]))
         (map (fn [[id session]]
                [[session-kind id] session])
              (get-in next-state [:worldbook :sessions]))
         (map (fn [[id card]]
                [[card-kind id] card])
              (:instruction-cards next-state))
         (map (fn [[id selection]]
                [[card-session-kind id] selection])
              (:instruction-sessions next-state))
         (map (fn [[id selection]]
                [[card-turn-kind id] selection])
              (:instruction-turns next-state)))))

(defn- put-entry
  [[[kind id] document]]
  {:kind kind :id id :doc document})

(defn- delete-entry
  [[kind id]]
  {:kind kind :id id})

(defn- migration
  [partition-name expected-revision current next-state]
  (let [next-documents (state-documents next-state)
        current-documents (:documents current)
        changed (remove (fn [[k document]]
                          (= document (get current-documents k)))
                        next-documents)
        removed (remove (set (keys next-documents))
                        (keys current-documents))]
    {:migration-id (str (UUID/randomUUID))
     :partition partition-name
     :expected-version (:partition-version current)
     :reason :materials-commit
     :put (mapv put-entry changed)
     :delete (mapv delete-entry removed)}))

(defn- current-revision
  [storage-handle partition-name]
  (let [current (read-state storage-handle partition-name)]
    (when (:found? current) (:revision current))))

(defn- commit!
  [storage-handle partition-name expected-revision next-state]
  (let [current (read-state storage-handle partition-name)
        actual (when (:found? current) (:revision current))]
    (if (not= expected-revision actual)
      {:ok? false
       :reason :revision-conflict
       :current-revision actual}
      (try
        (let [result (storage/commit-migration!
                      storage-handle
                      (migration partition-name expected-revision current next-state))]
          {:ok? true
           :revision (:revision next-state)
           :partition-version (:version result)})
        (catch clojure.lang.ExceptionInfo exception
          (case (storage/error-code exception)
            :STORAGE_VERSION_CONFLICT
            {:ok? false
             :reason :revision-conflict
             :current-revision (current-revision storage-handle partition-name)}

            ;; A storage failure is not a conflict and not a success.  Let the
            ;; caller's recovery path see the real code.
            (throw exception)))))))

(defn durable-store
  "Create a durable Store over an open `hc.server.storage` handle.

  Options:

    :partition  storage partition name (default `\"materials\"`)

  The storage handle's lifecycle belongs to the application; this adapter
  never closes it.  Construction fails when the handle is not open."
  ([storage-handle]
   (durable-store storage-handle {}))
  ([storage-handle {:keys [partition]
                    :or {partition materials-partition}}]
   (when-not (storage/store-open? storage-handle)
     (throw (ex-info "durable materials store needs an open storage handle"
                     {:error :MATERIALS_STORE_CLOSED})))
   (reify store/Store
     (read-snapshot [_]
       (let [current (read-state storage-handle partition)]
         (if (:found? current)
           {:found? true
            :revision (:revision current)
            :state (:state current)}
           {:found? false})))

     (commit-snapshot! [_ expected-revision next-state]
       (commit! storage-handle partition expected-revision next-state)))))
