(ns hc.server.app.owner-store
  "Adapt the selected storage engine to `hc.compose.owner.store/Store`.

  One Owner owns one session partition.  The whole owner state is stored as
  one document, so the compare-and-set version covers board, relay, calls,
  cancellation tree and conversation atomically.  The adapter never
  decomposes owner state into per-record writes.

  The same migration also mirrors every pending child report and every call
  waiting for a retry into the storage obligation channel.  A report
  obligation is open while the parent has not acknowledged the report; a
  `:retry-call` index is open while the call is in `:retry-wait` or is a
  recovered ready retry awaiting its claim.  The owner-state document and the
  obligation update commit together, so a crash cannot leave one without the
  other."
  (:require [clojure.string :as str]
            [hc.compose.owner.store :as owner-store]
            [hc.server.storage :as storage])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util UUID)))

(defn- partition-name
  [session-id]
  (str "session/" session-id))

(defn- storage-error-code
  [t]
  (try
    (storage/error-code t)
    (catch Throwable _ nil)))

(defn- conflict-error
  [expected actual]
  {:code :VERSION_CONFLICT
   :message "stored version does not match the expected version"
   :details {:expected expected :actual actual}})

(defn state-too-large-error
  "Actionable error for the current-state document guard."
  [details]
  {:code :OWNER_STATE_TOO_LARGE
   :size (:chars details)
   :limit (:max-doc-chars details)
   :remedy :compact-or-raise
   :hint "compact the owner state to blob refs or raise :max-doc-chars"})

(defn- sha256-hex
  [^String value]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes value StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (long %) 0xFF)) bytes))))

(defn- report-obligation-id
  "Stable obligation id.  Normal report ids keep the readable form agreed
  with the recovery owner; an over-long id is hashed to stay inside the
  storage key segment budget."
  [report-id]
  (let [id (str "report/" report-id)]
    (if (<= (alength (.getBytes ^String id StandardCharsets/UTF_8)) 160)
      id
      (str "report/sha256-" (sha256-hex report-id)))))

(defn- report-obligations
  "Return the open report-pending obligations for STATE, keyed by obligation
  id.  Only `:pending` child reports are external delivery obligations.
  Acknowledged reports wait only for process-local reclamation."
  [state]
  (let [session-id (:session-id state)
        updated-at (:updated-at state)]
    (into {}
          (for [[report-id report] (get-in state [:reports :reports])
                :when (= :pending (:phase report))]
            (let [id (report-obligation-id report-id)]
              [id
               (cond-> {:obligation/id id
                        :obligation/kind :report
                        :open? true
                        :phase :report-pending
                        :session-id session-id
                        :parent-call-id (:parent-call-id report)
                        :report report
                        :executor-generation 0
                        :updated-at updated-at}
                 (contains? report :parent-session-id)
                 (assoc :parent-session-id (:parent-session-id report))

                 (contains? report :child-session-id)
                 (assoc :child-session-id (:child-session-id report)))])))))

(defn- report-obligation-ids
  [view]
  (into #{}
        (keep (fn [obligation]
                (when (= :report-pending (:phase obligation))
                  (:obligation/id obligation))))
        (:open-obligations view)))

(defn- retry-obligation-id
  [call-id]
  (let [id (str "retry/" call-id)]
    (if (<= (alength (.getBytes ^String id StandardCharsets/UTF_8)) 160)
      id
      (str "retry/sha256-" (sha256-hex call-id)))))

(defn- retry-obligations
  "Mirror every owner call that is waiting for a retry.  The owner-state
  document remains the authority for the request, effects, and budget; this
  obligation is the durable index recovery uses to start the owning session
  after a process restart.  The obligation is removed in the same owner
  commit that moves the call out of `:retry-wait`."
  [state]
  (let [session-id (:session-id state)
        updated-at (:updated-at state)]
    (into {}
          (for [[call-id call] (:calls state)
                :let [retry (:retry call)
                      retry-wait? (and (= :retry-wait (:state call))
                                       (= :retry-wait (:phase retry)))
                      ready-retry? (and (= :ready (:state call))
                                        (true? (:retry-due? call))
                                        (= :retry-wait (:phase retry)))]
                :when (or retry-wait? ready-retry?)]
            (let [id (retry-obligation-id call-id)]
              [id
               (cond-> {:obligation/id id
                        :obligation/kind :retry-call
                        :open? true
                        :phase (if retry-wait? :retry-wait :ready)
                        :session-id session-id
                        :call-id call-id
                        :attempt-id (:attempt-id retry)
                        :attempts-used (:attempts-used retry)
                        :retries-used (:retries-used retry)
                        :max-attempts (:max-attempts retry)
                        :max-retries (:max-retries retry)
                        :deadline (:deadline retry)
                        :deadline-at (:deadline-at retry)
                        :retry-at (:retry-at retry)
                        :failure (:failure retry)
                        :effects (:effects retry)
                        :safe-to-repeat? (true? (get-in retry
                                                         [:effects
                                                          :repeat-safe?]))
                        :updated-at updated-at}
                 (contains? call :kind) (assoc :call-kind (:kind call)))])))))

(defn- retry-obligation-ids
  [view]
  (into #{}
        (keep (fn [obligation]
                (when (= :retry-call (:obligation/kind obligation))
                  (:obligation/id obligation))))
        (:open-obligations view)))

(defn- owned-obligation-ids
  [view]
  (into (report-obligation-ids view)
        (retry-obligation-ids view)))

(defn state-from-view
  "Return `{:version n :state doc}` from one owner partition view.

  A versioned partition without its owner-state document is corruption, not
  an empty conversation.  Treating it as empty would discard the board,
  relay ledger, calls and conversation."
  [{:keys [version state] :as view}]
  (let [version (long (or version 0))]
    (if (and (pos? version) (nil? state))
      (throw (ex-info "owner partition has a version but no state document"
                      {:code :OWNER_STORE_CORRUPT
                       :partition (:partition view)
                       :version version}))
      {:version version :state state})))

(defn owner-store
  "Create a Store for SESSION-ID backed by STORAGE."
  [storage session-id]
  (when-not (and (string? session-id) (not (str/blank? session-id)))
    (throw (ex-info "owner-store requires a non-blank session-id"
                    {:error/kind :invalid-session-id
                     :session-id session-id})))
  (let [partition (partition-name session-id)
        known-obligations (atom nil)]
    (reify owner-store/Store
      (load-state [_]
        (let [view (storage/read-session-view
                    storage
                    {:partition partition :session-id session-id})]
          (reset! known-obligations (owned-obligation-ids view))
          (state-from-view view)))

      (commit! [_ expected-version new-state]
        (try
          (let [current (merge (report-obligations new-state)
                               (retry-obligations new-state))
                current-ids (set (keys current))
                previous (if (nil? @known-obligations)
                           (owned-obligation-ids
                            (storage/read-session-view
                             storage
                             {:partition partition :session-id session-id}))
                           @known-obligations)
                remove-ids (vec (remove current-ids previous))
                obligations? (or (seq current) (seq remove-ids))
                result
                (storage/commit-migration!
                 storage
                 (cond-> {:migration-id (str (UUID/randomUUID))
                          :partition partition
                          :session-id session-id
                          :expected-version expected-version
                          :reason :owner-commit
                          :put [{:kind :owner-state
                                 :id session-id
                                 :doc new-state}]}
                   obligations?
                   (assoc :obligations
                          (cond-> {:put (vec (vals current))}
                            (seq remove-ids)
                            (assoc :remove remove-ids)))))]
            (reset! known-obligations current-ids)
            {:ok? true :version (:version result)})
          (catch clojure.lang.ExceptionInfo t
            (if (= :STORAGE_VERSION_CONFLICT (storage-error-code t))
              (let [details (ex-data t)]
                {:ok? false
                 :error (conflict-error expected-version
                                        (or (:actual details)
                                            (:version details)))})
              (let [details (ex-data t)]
                (if (and (:chars details) (:max-doc-chars details))
                  {:ok? false
                   :error (state-too-large-error details)}
                  (throw t)))))))

      (close! [_]
        ;; The storage environment is shared by every session; the
        ;; application closes it once during shutdown.
        {:ok? true}))))

(defn load-state
  "Public adapter for the protocol method, used by assembly to seed Pair Hint
  before the Owner starts."
  [store]
  (owner-store/load-state store))
