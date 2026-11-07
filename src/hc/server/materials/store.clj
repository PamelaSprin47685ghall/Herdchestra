(ns hc.server.materials.store
  "Explicit persistence interface for the materials domain.

  The domain owns pure state transitions. It does not choose a database, open
  files or hide a process-local cache behind a global singleton. A caller
  supplies a Store implementation.

  Contract:

    (read-snapshot store)
      => {:found? false}
      => {:found? true :revision n :state state}

    (commit-snapshot! store expected-revision next-state)
      => {:ok? true :revision n}
      => {:ok? false :reason :revision-conflict :current-revision n}

  `expected-revision` is nil only for creation. A successful commit MUST be
  durable and atomic: readers see either the old revision or the new one.
  A conflict MUST leave the stored state unchanged. The adapter MUST NOT
  silently create an empty library when an existing store cannot be opened.

  `memory-store` is for tests and dry runs only. It is an atom, not a durable
  adapter, and it is never selected implicitly by `load-state`."
  (:require [hc.server.materials.state :as state]
            [hc.server.materials.validate :as validate]))

(defprotocol Store
  (read-snapshot [store]
    "Read the current snapshot. Opening failures should throw, not return an
    empty snapshot.")
  (commit-snapshot! [store expected-revision next-state]
    "Atomically replace the snapshot when its revision equals
    EXPECTED-REVISION. Returns a commit result map."))

(defn memory-store
  "An in-memory Store for tests and dry runs. Not a durable adapter."
  []
  (let [cell (atom {:found? false})]
    (reify Store
      (read-snapshot [_]
        @cell)
      (commit-snapshot! [_ expected-revision next-state]
        (loop []
          (let [current @cell
                current-revision (when (:found? current) (:revision current))]
            (if (= expected-revision current-revision)
              (let [next {:found? true
                          :revision (:revision next-state)
                          :state next-state}]
                (if (compare-and-set! cell current next)
                  {:ok? true :revision (:revision next-state)}
                  (recur)))
              {:ok? false
               :reason :revision-conflict
               :current-revision current-revision})))))))

(defn- snapshot-error
  [message details]
  {:ok? false
   :error {:code :MATERIALS_SNAPSHOT_INVALID
           :message message
           :details details}})

(defn load-state
  "Read and validate the stored state.

  Returns:

    {:ok? true :found? false}
    {:ok? true :found? true :state state :revision n}
    {:ok? false :error {...}}

  Missing state is reported, not silently created. A corrupt state is refused
  with every invariant violation, so the caller can open a recovery path."
  [store]
  (let [snapshot (read-snapshot store)]
    (cond
      (not (map? snapshot))
      (snapshot-error "store returned a non-map snapshot" {:snapshot snapshot})

      (not (contains? snapshot :found?))
      (snapshot-error "store snapshot has no :found? flag" {:snapshot snapshot})

      (not (:found? snapshot))
      {:ok? true :found? false}

      :else
      (let [state (state/normalize-loaded (:state snapshot))
            violations (validate/check-state state)
            snapshot-revision (:revision snapshot)
            state-revision (:revision state)]
        (cond
          (seq violations)
          {:ok? false
           :error {:code :MATERIALS_STATE_CORRUPT
                   :message "stored materials state violates invariants"
                   :details {:violations violations
                             :revision snapshot-revision}}}

          (not= snapshot-revision state-revision)
          (snapshot-error "snapshot revision does not match state revision"
                          {:snapshot-revision snapshot-revision
                           :state-revision state-revision})

          :else
          {:ok? true
           :found? true
           :state state
           :revision state-revision})))))

(defn initialize!
  "Create an empty state when the store is empty; otherwise load it.

  Creation is a compare-and-set against the absent revision, so two callers
  cannot both create different first states. An existing damaged state is
  refused, never replaced."
  [store]
  (let [loaded (load-state store)]
    (if-not (:ok? loaded)
      loaded
      (if (:found? loaded)
        loaded
        (let [state (state/new-state)
              committed (commit-snapshot! store nil state)]
          (if (:ok? committed)
            {:ok? true
             :found? true
             :state state
             :revision (:revision state)
             :created? true}
            (if (= :revision-conflict (:reason committed))
              (load-state store)
              {:ok? false
               :error {:code :MATERIALS_COMMIT_FAILED
                       :message "could not create the materials state"
                       :details committed}})))))))

(defn commit!
  "Commit a candidate state at EXPECTED-REVISION.

  The candidate is validated before it reaches the store. A successful
  transition has already advanced :revision by one; a candidate that does not
  satisfy that rule is refused here."
  [store expected-revision next-state]
  (let [violations (validate/check-state next-state)
        next-revision (:revision next-state)
        expected-ok? (or (nil? expected-revision)
                         (state/non-negative-integer? expected-revision))
        revision-ok? (and (state/non-negative-integer? next-revision)
                          (if (nil? expected-revision)
                            (zero? next-revision)
                            (= next-revision (inc (long expected-revision)))))]
    (cond
      (not expected-ok?)
      {:ok? false
       :error {:code :MATERIALS_REVISION_INVALID
               :message "expected revision must be a non-negative integer or nil"
               :details {:expected-revision expected-revision}}}

      (seq violations)
      {:ok? false
       :error {:code :MATERIALS_STATE_CORRUPT
               :message "candidate state violates invariants"
               :details {:violations violations
                         :revision next-revision}}}

      (not revision-ok?)
      {:ok? false
       :error {:code :MATERIALS_REVISION_INVALID
               :message "candidate revision is not exactly one past the expected revision"
               :details {:expected-revision expected-revision
                         :next-revision next-revision}}}

      :else
      (commit-snapshot! store expected-revision next-state))))

(defn transact!
  "Load, apply F and commit one pure transition.

  F receives the current state and returns a domain result. On success its
  :state is committed at the revision that was loaded. A conflict is returned
  as :MATERIALS_REVISION_CONFLICT; it is not retried here, because F may have
  chosen ids or source excerpts that must be re-decided against fresh state.

  The caller owns serialization. This function is a fence, not a lock."
  [store f]
  (let [loaded (load-state store)]
    (if-not (:ok? loaded)
      loaded
      (if-not (:found? loaded)
        (let [initialized (initialize! store)]
          (if (:found? initialized)
            (transact! store f)
            initialized))
        (let [base (:state loaded)
              result (f base)]
          (if-not (:ok? result)
            result
            (if (:replayed? result)
              (assoc result
                     :commit {:ok? true
                              :replayed? true
                              :revision (:revision base)})
              (let [next-state (:state result)
                    committed (commit! store (:revision base) next-state)]
              (if (:ok? committed)
                (assoc result :commit committed)
                (if (= :revision-conflict (:reason committed))
                  {:ok? false
                   :state base
                   :error {:code :MATERIALS_REVISION_CONFLICT
                           :message "materials state changed before the transition committed"
                           :details committed}
                   :effects []}
                  {:ok? false
                   :state base
                   :error (:error committed)
                   :effects []}))))))))))
