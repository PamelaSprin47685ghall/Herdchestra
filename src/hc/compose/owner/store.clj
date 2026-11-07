(ns hc.compose.owner.store
  "Committed state storage for one Owner.

  The Owner is the only writer.  A store performs one atomic
  compare-and-set from an expected version to a new version.  It never runs
  inside a Missionary cancellation domain: an interrupted executor cannot
  abort a commit.

  A store returns:

    (load-state store)            -> {:version n :state map|nil}
    (commit! store expected new)  -> {:ok? true :version n}
                                   | {:ok? false :error {:code .. :message ..}}
    (close! store)                -> any

  `memory-store` is the reference implementation for tests and ephemeral
  sessions.  A real store must keep the same compare-and-set contract."
  (:require [clojure.string :as str]))

(defprotocol Store
  (load-state [store]
    "Return the last committed version and state.  An empty store returns
    `{:version 0 :state nil}`.")
  (commit! [store expected-version new-state]
    "Commit NEW-STATE only when the stored version equals EXPECTED-VERSION.")
  (close! [store]
    "Release store resources.  This operation is idempotent."))

(defn valid-state?
  "True when STATE is a map with a non-blank string session id."
  [state]
  (and (map? state)
       (string? (:session-id state))
       (not (str/blank? (:session-id state)))))

(defn invalid-state-error
  [state]
  {:code :INVALID_STATE
   :message "committed state must be a map with a non-blank :session-id"
   :details {:class (some-> state class .getName)}})

(defn- closed-error
  []
  {:code :STORE_CLOSED
   :message "store is closed"})

(defn- conflict-error
  [expected actual]
  {:code :VERSION_CONFLICT
   :message "stored version does not match the expected version"
   :details {:expected expected :actual actual}})

(defrecord MemoryStore [cell closed?]
  Store
  (load-state [_]
    (let [{:keys [version state]} @cell]
      {:version version :state state}))

  (commit! [_ expected-version new-state]
    (if @closed?
      {:ok? false :error (closed-error)}
      (if-not (valid-state? new-state)
        {:ok? false :error (invalid-state-error new-state)}
        (let [[before after]
              (swap-vals! cell
                          (fn [{:keys [version] :as snapshot}]
                            (if (= version expected-version)
                              {:version (inc (long version)) :state new-state}
                              snapshot)))
              actual (:version before)]
          (if (= actual expected-version)
            {:ok? true :version (:version after)}
            {:ok? false :error (conflict-error expected-version actual)})))))

  (close! [_]
    (reset! closed? true)
    {:ok? true :closed? true}))

(defn memory-store
  "Create an in-memory store.

  Options:

    :initial-state    map or nil
    :initial-version  non-negative integer, default 0

  An initial state without a matching version is a wiring error.  The
  caller must commit it explicitly."
  ([] (memory-store {}))
  ([{:keys [initial-state initial-version]
     :or {initial-version 0}}]
   (when-not (and (integer? initial-version) (not (neg? initial-version)))
     (throw (ex-info "initial-version must be a non-negative integer"
                     {:code :INVALID_VERSION :version initial-version})))
   (when (and (some? initial-state) (not (valid-state? initial-state)))
     (throw (ex-info "initial-state is invalid"
                     {:code :INVALID_STATE
                      :error (invalid-state-error initial-state)})))
   (when (and (some? initial-state) (zero? initial-version))
     (throw (ex-info "an initial state needs a non-zero initial version"
                     {:code :INVALID_VERSION
                      :initial-version initial-version})))
   (->MemoryStore (atom {:version initial-version :state initial-state})
                  (atom false))))
