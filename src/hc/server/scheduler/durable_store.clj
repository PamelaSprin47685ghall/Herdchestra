(ns hc.server.scheduler.durable-store
  "Durable `hc.compose.scheduler.store/Store` over `hc.server.storage`.

  Layout in the `scheduler` partition (all documents are partition-scoped,
  so one read view reconstructs the whole scheduler state):

      :scheduler-root      id \"root\"       {:v :revision :seq :device-zone :run-order}
      :scheduler-schedule  id schedule-id    schedule map
      :scheduler-run       id run-id         run map

  Active runs are mirrored through the storage `:obligations` channel in the
  same atomic `commit-migration!` call.  On restart, `hc.server.recovery`
  can therefore classify a stale run instead of leaving a permanent
  `:running` document with no recovery decision.  The scheduler service still
  makes the final domain decision (interrupt, keep waiting, or keep
  outcome-unknown); the obligation is closed when the run leaves the active
  set.

  The adapter never opens or closes storage.  The application owns the
  handle; a standalone opener is provided by `open-durable-store`, which uses
  `hc.server.recovery/recovery-entry` and returns its blocked result
  unchanged."
  (:require [clojure.string :as str]
            [hc.compose.scheduler.core :as core]
            [hc.compose.scheduler.model :as model]
            [hc.compose.scheduler.store :as sched-store]
            [hc.server.storage :as storage])
  (:import (java.time Instant)
           (java.util UUID)))

(def scheduler-partition
  "Storage partition owned by the scheduler domain."
  "scheduler")

(def root-kind :scheduler-root)
(def schedule-kind :scheduler-schedule)
(def run-kind :scheduler-run)
(def root-id "root")

(def all-kinds
  [root-kind schedule-kind run-kind])

(def storage-option-keys
  #{:path :create? :create-parents? :durability :format-version
    :mapsize-mib :max-readers :lock? :read-only? :receipt-window
    :default-partition :max-doc-chars :now-fn})

(defn- recovery-entry*
  [opts]
  ((requiring-resolve 'hc.server.recovery/recovery-entry) opts))

(defn- recover!*
  [storage-handle opts]
  ((requiring-resolve 'hc.server.recovery/recover!) storage-handle opts))

(defn- corrupt!
  [message details]
  (throw (ex-info message
                  (merge {:error :SCHEDULER_STORE_CORRUPT
                          :scheduler/expected? true}
                         details))))

(defn- storage-key?
  [k]
  (= "storage" (namespace k)))

(defn- strip-storage
  [doc]
  (into {} (remove (fn [[k _]] (storage-key? k))) doc))

(defn- doc-id
  [doc]
  (or (:storage/id doc) (:id doc)))

(defn- docs-of
  [documents kind]
  (into {}
        (keep (fn [[[document-kind id] document]]
                (when (= kind document-kind)
                  [id document])))
        documents))

(defn- doc-map
  "Return `{[kind id] document}` for every scheduler document in VIEW."
  [view]
  (reduce (fn [acc kind]
            (reduce (fn [acc doc]
                      (let [id (doc-id doc)
                            k [kind id]]
                        (when-not (string? id)
                          (corrupt! "scheduler document has no stable id"
                                    {:kind kind :document doc}))
                        (when (contains? acc k)
                          (corrupt! "scheduler document identity is duplicated"
                                    {:kind kind :id id}))
                        (assoc acc k (strip-storage doc))))
                    acc
                    (get-in view [:records kind] [])))
          {}
          all-kinds))

(defn- valid-root?
  [root]
  (and (map? root)
       (integer? (:revision root))
       (not (neg? (:revision root)))
       (integer? (:seq root))
       (not (neg? (:seq root)))
       (vector? (:run-order root))
       (every? string? (:run-order root))
       (= (count (:run-order root))
          (count (distinct (:run-order root))))))

(defn- read-state
  "Read one consistent view and reconstruct the scheduler state.

  Returns `{:found? true :state state :partition-version v
  :documents {[kind id] doc} :obligations {id doc}}` or
  `{:found? false :partition-version v :documents {} :obligations {}}`.

  A missing root with surviving documents is corruption; it is never treated
  as an empty scheduler."
  [storage-handle]
  (let [view (storage/read-session-view storage-handle
                                        {:partition scheduler-partition})
        documents (doc-map view)
        root (get documents [root-kind root-id])]
    (if-not root
      (if (seq documents)
        (corrupt! "scheduler documents exist without a root"
                  {:partition scheduler-partition
                   :document-count (count documents)})
        {:found? false
         :partition-version (long (or (:version view) 0))
         :documents {}
         :obligations {}})
      (if-not (valid-root? root)
        (corrupt! "scheduler root is invalid"
                  {:root root})
        (let [schedules (docs-of documents schedule-kind)
              runs (docs-of documents run-kind)
              run-order (vec (:run-order root))
              state {:schedules schedules
                     :runs runs
                     :run-order run-order
                     :seq (:seq root)
                     :revision (:revision root)
                     :device-zone (:device-zone root)}]
          (when-not (= (set run-order) (set (keys runs)))
            (corrupt! "scheduler run order does not match run documents"
                      {:run-order run-order
                       :run-ids (vec (keys runs))}))
          (when-not (core/valid-state? state)
            (corrupt! "scheduler state is structurally invalid"
                      {:revision (:revision state)
                       :seq (:seq state)}))
          {:found? true
           :state state
           :partition-version (long (or (:version view) 0))
           :documents documents
           :obligations (into {}
                              (map (juxt :obligation/id identity))
                              (:open-obligations view))})))))

(defn- root-document
  [state]
  {:v 1
   :revision (:revision state)
   :seq (:seq state)
   :device-zone (:device-zone state)
   :run-order (vec (:run-order state))})

(defn- state-documents
  "Return `{[kind id] document}` for STATE."
  [state]
  (into {}
        (concat
         [[[root-kind root-id] (root-document state)]]
         (map (fn [[id schedule]]
                [[schedule-kind id] schedule])
              (:schedules state))
         (map (fn [[id run]]
                [[run-kind id] run])
              (:runs state)))))

(defn- active-run?
  [run]
  (contains? model/active-run-statuses (:status run)))

(defn- obligation-phase
  [run]
  (case (:status run)
    :preparing :dispatching
    :running :running
    :awaiting-user :awaiting-user
    :outcome-unknown :outcome-unknown
    :outcome-unknown))

(defn- obligation-document
  [run existing generation now]
  (let [existing (strip-storage (or existing {}))
        phase (obligation-phase run)
        status (:status run)
        stable? (and (= phase (:phase existing))
                     (= status (:scheduler/status existing))
                     (some? (:updated-at existing)))
        updated-at (if stable?
                     (:updated-at existing)
                     (str (Instant/ofEpochMilli (long now))))]
    (merge existing
           {:obligation/id (:id run)
            :open? true
            :phase phase
            ;; The generation belongs to the process that first opened the
            ;; run.  A restart keeps the old value so recovery can prove the
            ;; executor is gone; it must not overwrite it with the new one.
            :executor-generation (or (:executor-generation existing) generation)
            :safe-to-repeat? false
            :scheduler/run-id (:id run)
            :scheduler/schedule-id (:schedule-id run)
            :scheduler/status status
            :updated-at updated-at})))

(defn- next-obligations
  [state current-obligations generation now]
  (into {}
        (keep (fn [run]
                (when (active-run? run)
                  (let [id (:id run)]
                    [id (obligation-document run
                                             (get current-obligations id)
                                             generation now)]))))
        (vals (:runs state))))

(defn- migration
  [current next-state generation now & [migration-id]]
  (let [next-docs (state-documents next-state)
        current-docs (:documents current)
        next-obs (next-obligations next-state (:obligations current)
                                   generation now)
        current-obs (:obligations current)
        changed-docs (remove (fn [[k document]]
                               (= document (get current-docs k)))
                             next-docs)
        removed-docs (remove (set (keys next-docs)) (keys current-docs))
        changed-obs (remove (fn [[id document]]
                              (= document (get current-obs id)))
                            next-obs)
        removed-obs (remove (set (keys next-obs)) (keys current-obs))]
    (when (or (seq changed-docs)
              (seq removed-docs)
              (seq changed-obs)
              (seq removed-obs))
      {:migration-id (or migration-id
                         (str "scheduler-" (:revision next-state)))
       :partition scheduler-partition
       :expected-version (:partition-version current)
       :reason :scheduler-commit
       :put (mapv (fn [[[kind id] document]]
                    {:kind kind :id id :doc document})
                  changed-docs)
       :delete (mapv (fn [[kind id]]
                       {:kind kind :id id})
                     removed-docs)
       :obligations {:put (mapv val changed-obs)
                     :remove (vec removed-obs)}})))

(defn- revision-relation
  [current next-state]
  (let [current-revision (when (:found? current)
                           (:revision (:state current)))
        next-revision (:revision next-state)]
    (cond
      ;; Revision 0 is the empty initial state.  Persisting it lets the
      ;; adapter reconcile obligations on a fresh store without inventing a
      ;; fake domain transition.
      (nil? current-revision) (if (<= next-revision 1) :new :gap)
      (= current-revision next-revision) :same
      (= (inc (long current-revision)) next-revision) :next
      :else :gap)))

(defn- load-state*
  [storage-handle last-state]
  (locking last-state
    (let [current (read-state storage-handle)]
      (reset! last-state current)
      (when (:found? current)
        (:state current)))))

(defn- save-state*
  [storage-handle last-state state generation clock]
  (locking last-state
    (let [current (read-state storage-handle)
          relation (revision-relation current state)
          now (long (clock))
          next-obs (next-obligations state (:obligations current)
                                     generation now)
          same-docs? (and (:found? current)
                          (= (:state current) state)
                          (= (:documents current) (state-documents state)))
          same-obs? (= next-obs (:obligations current))]
      (cond
        (and same-docs? same-obs?)
        nil

        ;; Restore/recovery can leave an open obligation for a run that is
        ;; already terminal.  The domain documents do not change, but the
        ;; obligation row must still be closed.  Use a separate deterministic
        ;; migration id so this is not mistaken for a second domain commit.
        (and same-docs? (not same-obs?))
        (let [m (migration current state generation now
                           (str "scheduler-" (:revision state)
                                "-obligations-"
                                (hash (pr-str [next-obs
                                               (:obligations current)]))))
              committed (when m
                          (storage/commit-migration! storage-handle m))]
          (reset! last-state
                  {:found? true
                   :state state
                   :partition-version (long (or (:version committed)
                                                (:partition-version current)))
                   :documents (state-documents state)
                   :obligations next-obs})
          nil)

        (= :gap relation)
        (throw (ex-info "scheduler state revision is not the next storage revision"
                        {:error :SCHEDULER_STORE_CONFLICT
                         :scheduler/expected? true
                         :current-revision (get-in current [:state :revision])
                         :next-revision (:revision state)}))

        (= :same relation)
        (throw (ex-info "scheduler state changed without advancing its revision"
                        {:error :SCHEDULER_STORE_CONFLICT
                         :scheduler/expected? true
                         :revision (:revision state)}))

        :else
        (let [m (migration current state generation now)
              committed (when m
                          (storage/commit-migration! storage-handle m))]
          (reset! last-state
                  {:found? true
                   :state state
                   :partition-version (long (or (:version committed)
                                                (:partition-version current)))
                   :documents (state-documents state)
                   :obligations next-obs})
          nil)))))

(defn durable-store
  "Create a durable scheduler Store over an open `hc.server.storage` handle.

  Options:

    :generation  stable id for this scheduler process; default a fresh UUID
    :clock       zero-argument epoch-ms clock; default `System/currentTimeMillis`

  The storage handle's lifecycle belongs to the application; this adapter
  never closes it."
  ([storage-handle]
   (durable-store storage-handle {}))
  ([storage-handle {:keys [generation clock]
                    :or {generation (str (UUID/randomUUID))
                         clock #(System/currentTimeMillis)}}]
   (when-not (storage/store-open? storage-handle)
     (throw (ex-info "durable scheduler store needs an open storage handle"
                     {:error :SCHEDULER_STORE_CLOSED
                      :scheduler/expected? true})))
   (let [last-state (atom {:found? false
                           :partition-version 0
                           :documents {}
                           :obligations {}})]
     (reify sched-store/Store
       (load-state [_]
         (load-state* storage-handle last-state))
       (save-state! [_ state]
         (save-state* storage-handle last-state state generation clock))))))

(defn open-durable-store
  "Open a scheduler storage handle through `recovery/recovery-entry`.

  A blocked recovery entry is returned unchanged, per the storage-recovery
  contract.  On success returns
  `{:status :opened :storage handle :store durable-store}`.

  Options are the storage open options plus `:generation` and `:clock`.
  `:create?` defaults to true for a scheduler-owned path."
  [opts]
  (let [storage-opts (merge {:create? true}
                            (select-keys opts storage-option-keys))
        entry (recovery-entry* storage-opts)]
    (if (= :blocked (:status entry))
      entry
      {:status :opened
       :storage (:store entry)
       :store (durable-store (:store entry)
                             (select-keys opts [:generation :clock]))})))

(defn recover-obligations!
  "Run `hc.server.recovery/recover!` for the scheduler partition.

  Call this before opening the scheduler service so a stale obligation is
  classified before new work is dispatched.  It never re-runs a run."
  ([storage-handle]
   (recover-obligations! storage-handle {}))
  ([storage-handle opts]
   (recover!* storage-handle
              (merge {:partition scheduler-partition} opts))))

(defn open-obligations
  "Return the scheduler partition's open obligations."
  [storage-handle]
  (storage/list-open-obligations storage-handle {:partition scheduler-partition}))

(defn state
  "Read the durable scheduler state, or nil when no scheduler exists."
  [storage-handle]
  (let [current (read-state storage-handle)]
    (when (:found? current) (:state current))))
