(ns hc.server.app.steward-store
  "Adapt the shared storage engine to `hc.compose.owner.store/Store` for the
  repository-wide Steward.

  The Steward state is one document in one partition:

    partition \"steward/repository\"
    kind      :steward-state
    id        \"current\"

  The store performs one compare-and-set from an expected partition version
  to the next version.  The partition version covers the whole document, so
  a commit cannot merge a partial Steward state.

  The storage engine is shared by every session.  `close!` closes only this
  handle; the application closes the storage engine once during shutdown."
  (:require [hc.compose.owner.store :as owner-store]
            [hc.server.storage :as storage])
  (:import (java.util UUID)))

(def steward-partition
  "The single partition owned by the Steward."
  "steward/repository")

(def document-kind :steward-state)
(def document-id "current")

(defn- closed-error
  []
  {:code :STORE_CLOSED
   :message "store is closed"})

(defn- invalid-state-error
  [state]
  {:code :INVALID_STATE
   :message "steward state must be a map"
   :details {:class (some-> state class .getName)}})

(defn- invalid-version-error
  [version]
  {:code :INVALID_VERSION
   :message "expected version must be a non-negative integer"
   :details {:version version}})

(defn- conflict-error
  [expected actual]
  {:code :VERSION_CONFLICT
   :message "stored version does not match the expected version"
   :details {:expected expected :actual actual}})

(defn- state-too-large-error
  [details]
  {:code :STEWARD_STATE_TOO_LARGE
   :size (:chars details)
   :limit (:max-doc-chars details)
   :remedy :compact-cognition
   :hint "compact the cognition evidence or the mission links"})

(defn- strip-storage-keys
  "Remove the `:storage/*` identity keys that `read-session-view` attaches."
  [doc]
  (when doc
    (into {}
          (remove (fn [[k _]]
                    (and (keyword? k) (= "storage" (namespace k)))))
          doc)))

(defn- read-current-state
  [{:keys [version state]}]
  (let [version (long (or version 0))]
    (if (and (pos? version) (nil? state))
      (throw (ex-info "steward partition has a version but no state document"
                      {:code :STEWARD_STORE_CORRUPT
                       :partition steward-partition
                       :version version}))
      {:version version
       :state (strip-storage-keys state)})))

(defn- load-current
  [storage-engine]
  (let [view (storage/read-session-view storage-engine {:partition steward-partition})
        state-doc (first (filter #(= document-id (:storage/id %))
                                 (get-in view [:records document-kind])))]
    (read-current-state {:version (:version view) :state state-doc})))

(defn- commit-current!
  [storage-engine expected-version next-state]
  (if-not (and (integer? expected-version) (not (neg? expected-version)))
    {:ok? false :error (invalid-version-error expected-version)}
    (if-not (map? next-state)
      {:ok? false :error (invalid-state-error next-state)}
      (try
        (let [result
              (storage/commit-migration!
               storage-engine
               {:migration-id (str "steward-" (UUID/randomUUID))
                :partition steward-partition
                :reason :steward-commit
                :expected-version expected-version
                :put [{:kind document-kind
                       :id document-id
                       :doc next-state}]})]
          {:ok? true :version (:version result)})
        (catch clojure.lang.ExceptionInfo t
          (if (= :STORAGE_VERSION_CONFLICT (storage/error-code t))
            (let [details (ex-data t)]
              {:ok? false
               :error (conflict-error expected-version
                                      (or (:actual details)
                                          (:version details)))})
            (let [details (ex-data t)]
              (if (and (:chars details) (:max-doc-chars details))
                {:ok? false :error (state-too-large-error details)}
                (throw t)))))))))

(defn steward-store
  "Create a `Store` for the Steward backed by STORAGE."
  [storage-engine]
  (let [closed? (atom false)]
    (reify owner-store/Store
      (load-state [_]
        (load-current storage-engine))

      (commit! [_ expected-version next-state]
        (if @closed?
          {:ok? false :error (closed-error)}
          (commit-current! storage-engine expected-version next-state)))

      (close! [_]
        (reset! closed? true)
        {:ok? true :closed? true}))))

(defn load-state
  "Public adapter for the protocol method."
  [store]
  (owner-store/load-state store))

(defn commit!
  "Public adapter for the protocol method."
  [store expected-version next-state]
  (owner-store/commit! store expected-version next-state))

(defn close!
  "Public adapter for the protocol method."
  [store]
  (owner-store/close! store))
