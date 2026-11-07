(ns hc.server.commands.durable
  "Durable adapter for the command gate.

  `hc.server.commands` stays pure.  This namespace adds an injected
  compare-and-set store and one serialized handle.  The caller supplies the
  authoritative epoch window on every accept:

    {:command-epoch 3 :retired-through 2}

  Receipts are scoped by `[session-id command-id]`.  The gate also keeps a
  command-id index for reconnect queries that do not know the session.
  A changed window clears both.  A replay of the same scoped command with
  the same payload returns the stored receipt after a restart.

  `issue-epoch!` retires the current epoch and opens the next one in one
  committed transition.  Opening a gate also migrates its document: legacy
  plaintext secrets become digests, stale `:dispatching` placeholders become
  `:outcome-unknown` receipts, and the command-id index is rebuilt from the
  authoritative registry.  Opening a gate therefore claims exclusive
  ownership of its store partition.

  The store contract is the same compare-and-set shape used by
  `hc.compose.owner.store`:

    (load-state store)            -> {:version n :state map|nil}
    (commit! store expected new)  -> {:ok? true :version n}
                                   | {:ok? false :error {:code ..}}

  The handle serializes calls with a monitor.  The gateway may call it from
  several subscription threads; the Owner is not required to own this gate."
  (:require [hc.server.commands :as commands]
            [hc.server.commands.epoch :as epoch]
            [hc.server.commands.receipts :as receipts]))

(defprotocol Store
  (load-state
    [store]
    "Return `{:version n :state map|nil}`.")
  (commit!
    [store expected-version new-state]
    "Commit NEW-STATE only when the stored version equals EXPECTED-VERSION.")
  (close-store!
    [store]
    "Release store resources. This operation is idempotent."))

(defn- store-error
  [code message details]
  (cond-> {:code code :message message}
    (some? details) (assoc :details details)))

(defn memory-store
  "Create an in-memory Store for tests and ephemeral hosts."
  ([] (memory-store {}))
  ([{:keys [initial-state initial-version]
     :or {initial-version 0}}]
   (let [cell (atom {:version initial-version :state initial-state})
         closed? (atom false)]
     (reify Store
       (load-state [_]
         @cell)

       (commit! [_ expected-version new-state]
         (if @closed?
           {:ok? false :error (store-error :STORE_CLOSED "store is closed")}
           (let [[before after]
                 (swap-vals! cell
                             (fn [{:keys [version] :as snapshot}]
                               (if (= version expected-version)
                                 {:version (inc (long version))
                                  :state new-state}
                                 snapshot)))]
             (if (= (:version before) expected-version)
               {:ok? true :version (:version after)}
               {:ok? false
                :error (store-error :VERSION_CONFLICT
                                    "stored version does not match"
                                    {:expected expected-version
                                     :actual (:version before)})}))))

       (close-store! [_]
         (reset! closed? true)
         {:ok? true :closed? true})))))

(defn- new-state
  []
  (assoc (commands/new-state) :receipt-index {}))

(defn- ensure-index
  [state]
  (if (and (map? state) (not (contains? state :receipt-index)))
    (assoc state :receipt-index {})
    state))

(defn- clear-receipts
  [state]
  (assoc state
         :receipts receipts/empty-registry
         :receipt-index {}))

(defn- command-id-of
  [key entry]
  (or (:command-id entry)
      (when (and (vector? key) (= 2 (count key)))
        (second key))
      key))

(defn- outcome-unknown-receipt
  [key entry]
  {:status :outcome-unknown
   :command-id (command-id-of key entry)
   :command-epoch (:command-epoch entry)
   :error {:code :COMMAND_OUTCOME_UNKNOWN
           :message
           "the process stopped while this command was dispatching; re-read the current state"
           :action :resync-state}})

(defn- reconcile-dispatching
  "Turn placeholders left by a previous gate into honest unknown outcomes.

  A `:dispatching` receipt means a side effect may have started.  No process
  is dispatching it after the gate is reopened, so keeping the placeholder
  would claim that work is still in flight.  The outcome is unknown and the
  client must re-read state; a retry must not repeat the side effect."
  [state]
  (if-not (map? (:receipts state))
    state
    (assoc state
           :receipts
           (into {}
                 (map (fn [[key entry]]
                        (if (and (map? entry)
                                 (= :dispatching
                                    (get-in entry [:receipt :status])))
                          [key (assoc entry
                                      :receipt
                                      (outcome-unknown-receipt key entry))]
                          [key entry])))
                 (:receipts state)))))

(defn- receipt-index
  [state]
  (into {}
        (keep (fn [[key entry]]
                (when (map? entry)
                  (let [command-id (command-id-of key entry)]
                    [command-id (assoc entry
                                       :command-id command-id
                                       :command-key key)]))))
        (:receipts state)))

(defn- rebuild-index
  "Rebuild the command-id index from the authoritative receipt registry."
  [state]
  (if-not (map? (:receipts state))
    state
    (let [index (receipt-index state)]
      (if (= index (:receipt-index state))
        state
        (assoc state :receipt-index index)))))

(defn- migrate-state
  [state]
  (-> state
      commands/redact-state
      reconcile-dispatching
      rebuild-index))

(defn- merge-window
  "Adopt the caller's epoch window without rolling the epoch back.

  A newer epoch clears receipts.  An older window is ignored because a
  stale snapshot must never reopen retired commands.  An epoch at or below
  the high-water mark is never adopted: a retired epoch number stays retired
  even when the incoming window still names it as active."
  [state incoming]
  (let [incoming (or incoming {})
        current (when state (:command-epoch state))
        incoming-epoch (:command-epoch incoming)
        retired (max (or (:retired-through state) 0)
                     (or (:retired-through incoming) 0))]
    (cond
      (nil? state)
      (assoc (new-state)
             :command-epoch (when (and (epoch/valid-epoch? incoming-epoch)
                                       (> incoming-epoch retired))
                              incoming-epoch)
             :retired-through retired)

      (nil? incoming-epoch)
      (-> state
          (assoc :command-epoch nil
                 :retired-through (max retired (or current 0)))
          clear-receipts)

      (= current incoming-epoch)
      (assoc state :retired-through retired)

      (and (or (nil? current) (> incoming-epoch current))
           (> incoming-epoch retired))
      (-> state
          (assoc :command-epoch incoming-epoch
                 :retired-through retired)
          clear-receipts)

      ;; Repair an invalid active epoch that the high-water mark already
      ;; retired.  This branch also keeps a nil epoch when a stale window
      ;; names an epoch at or below the high-water mark.
      (and current (<= current retired))
      (-> state
          (assoc :command-epoch nil
                 :retired-through retired)
          clear-receipts)

      :else
      (assoc state :retired-through retired))))

(defn- invalid-window
  [window]
  (or (when-not (map? window)
        (store-error :COMMAND_WINDOW_INVALID
                     "command window must be a map"
                     {:window window}))
      (let [violations (epoch/check window)]
        (when (seq violations)
          (store-error :COMMAND_WINDOW_INVALID
                       "command window failed its invariants"
                       {:window window :violations violations})))))

(defn- epoch-refusal
  [state expected]
  (case (epoch/status state expected)
    :active nil
    :retired
    (store-error
     :COMMAND_EPOCH_RETIRED
     "command epoch is retired; re-read the current state before rotating"
     {:command-epoch expected
      :current-epoch (:command-epoch state)
      :action :resync-state})
    :unknown
    (store-error
     :COMMAND_EPOCH_UNKNOWN
     "command epoch was not issued by this server; re-read the current state"
     {:command-epoch expected
      :current-epoch (:command-epoch state)
      :action :resync-state})
    (store-error :COMMAND_EPOCH_INVALID
                 "command-epoch must be a positive integer"
                 {:command-epoch expected})))

(defn- command-key
  [scope command-id]
  (if (nil? scope)
    command-id
    [scope command-id]))

(defn- accept-request
  [state scope request receipt]
  (let [command-id (:command-id request)
        key (command-key scope command-id)
        request (assoc request :command-key key)]
    (if (nil? receipt)
      (commands/accept state request)
      (commands/accept state request receipt))))

(defn- complete-request
  [state scope command-id receipt]
  (let [key (if (nil? scope)
              (or (get-in state [:receipt-index command-id :command-key])
                  command-id)
              (command-key scope command-id))]
    (commands/complete state key receipt)))

(defn- load-initial-state
  "Load the gate document and migrate it before the gate becomes usable.

  The migration removes legacy plaintext secret payloads, turns stale
  `:dispatching` placeholders into `:outcome-unknown` receipts, and rebuilds
  the command-id index from the authoritative registry.  It commits before
  the gate accepts calls.  A failed migration blocks the gate rather than
  serving a document that still contains a password or claims that a dead
  process is still dispatching."
  [store]
  (let [loaded (load-state store)
        initial (if (and (map? loaded) (contains? loaded :state))
                  loaded
                  {:version 0 :state nil})
        state (:state initial)
        migrated (when (map? state) (migrate-state state))
        candidate (or migrated state)
        violations (when (map? candidate)
                     (seq (commands/check-invariants candidate)))]
    (when violations
      (throw (ex-info "command gate state failed its invariants"
                      {:code :COMMAND_STORE_CORRUPT
                       :violations (vec violations)})))
    (if (and migrated (not= migrated state))
      (let [commit (commit! store (:version initial) migrated)]
        (if (:ok? commit)
          {:version (:version commit) :state migrated}
          (throw (ex-info "command gate state could not be migrated"
                          {:error (:error commit)}))))
      initial)))

(defn gate
  "Create one serialized durable gate backed by STORE.

  The handle accepts optional SCOPE (normally a session-id).  With a scope,
  the dedup key is `[scope command-id]`.  Without a scope, the key is the
  command-id."
  [store]
  (let [lock (Object.)
        closed? (atom false)
        cell (atom (load-initial-state store))]
    (letfn [(current-state [] (:state @cell))
            (closed-result []
              {:ok? false
               :state (current-state)
               :error (store-error :COMMAND_GATE_CLOSED "command gate is closed")
               :effects []})
            (persist! [expected new-state]
              (try
                (let [result (commit! store expected new-state)]
                  (if (:ok? result)
                    (do (reset! cell {:version (:version result)
                                      :state new-state})
                        result)
                    result))
                (catch Throwable t
                  {:ok? false
                   :error (store-error
                           :COMMAND_STORE_FAILED
                           "command state could not be committed"
                           {:cause (str t)})})))
            (run-transition! [f]
              (locking lock
                (if @closed?
                  (closed-result)
                  (loop [attempt 0]
                    (let [{:keys [version state]} @cell
                          result (f state)
                          next-state (:state result)]
                      (cond
                        (not (map? next-state))
                        result

                        (= state next-state)
                        result

                        :else
                        (let [commit (persist! version next-state)]
                          (if (:ok? commit)
                            result
                            (if (and (= :VERSION_CONFLICT
                                       (get-in commit [:error :code]))
                                     (< attempt 8))
                              (let [reloaded
                                    (try
                                      {:ok? true
                                       :snapshot (load-state store)}
                                      (catch Throwable t
                                        {:ok? false
                                         :cause (str t)}))]
                                (if (:ok? reloaded)
                                  (do
                                    (reset! cell (:snapshot reloaded))
                                    (recur (inc attempt)))
                                  {:ok? false
                                   :state state
                                   :error
                                   (store-error
                                    :COMMAND_STORE_FAILED
                                    "command state could not be reloaded"
                                    {:cause (:cause reloaded)})
                                   :effects []}))
                              {:ok? false
                               :state state
                               :error (or (:error commit)
                                          (store-error
                                           :COMMAND_STORE_FAILED
                                           "command state could not be committed"))
                               :effects []})))))))))]
      (letfn [(accept-fn
                ([window request]
                 (accept-fn window nil request nil))
                ([window request receipt]
                 (accept-fn window nil request receipt))
                ([window scope request receipt]
                 (if-let [error (invalid-window window)]
                   {:ok? false
                    :state (current-state)
                    :error error
                    :effects []}
                   (run-transition!
                    (fn [state]
                      (let [state (merge-window state window)
                            result (accept-request state scope request receipt)]
                        result))))))
              (complete-fn
                ([command-id receipt]
                 (complete-fn nil command-id receipt))
                ([scope command-id receipt]
                 (complete-fn nil scope command-id receipt))
                ([window scope command-id receipt]
                 (if-let [error (when (some? window)
                                  (invalid-window window))]
                   {:ok? false
                    :state (current-state)
                    :error error
                    :effects []}
                   (run-transition!
                    (fn [state]
                      (let [state (if (some? window)
                                    (merge-window state window)
                                    state)]
                        (complete-request state scope command-id receipt)))))))
              (issue-epoch-fn
                ([] (issue-epoch-fn nil))
                ([expected-epoch]
                 (run-transition!
                  (fn [state]
                    (if-let [error (when (some? expected-epoch)
                                     (epoch-refusal state expected-epoch))]
                      {:ok? false
                       :state state
                       :error error
                       :effects []}
                      (let [result (commands/issue-epoch state)]
                        (update result :state ensure-index)))))))
              (receipt-fn
                ([command-id]
                 (or (get-in (current-state) [:receipt-index command-id :receipt])
                     (get-in (current-state) [:receipts command-id :receipt])))
                ([scope command-id]
                 (get-in (current-state)
                         [:receipts (command-key scope command-id) :receipt])))]
        {:accept! accept-fn

         :sync-window!
         (fn [window]
           (if-let [error (invalid-window window)]
             {:ok? false
              :state (current-state)
              :error error
              :effects []}
             (run-transition!
              (fn [state]
                {:ok? true
                 :state (merge-window state window)
                 :effects []}))))

         :complete! complete-fn

         :issue-epoch! issue-epoch-fn

         :receipt-for receipt-fn

         :state
         (fn []
           (current-state))

         :close!
         (fn []
           (reset! closed? true)
           (close-store! store))}))))

(defn accept!
  "Call the gate's :accept! function.

  Arity 2/3 use no scope.  Arity 4 takes SCOPE, normally a session-id."
  ([gate window request] ((:accept! gate) window request))
  ([gate window request receipt] ((:accept! gate) window request receipt))
  ([gate window scope request receipt]
   ((:accept! gate) window scope request receipt)))

(defn complete!
  "Call the gate's :complete! function.

  The three-argument form uses the command-id index.  The four-argument
  form takes SCOPE explicitly.  The five-argument form also adopts WINDOW in
  the same transition, so a completion that races an epoch rotation cannot
  store a final receipt under a retired epoch."
  ([gate command-id receipt] ((:complete! gate) command-id receipt))
  ([gate scope command-id receipt]
   ((:complete! gate) scope command-id receipt))
  ([gate window scope command-id receipt]
   ((:complete! gate) window scope command-id receipt)))

(defn sync-window!
  "Call the gate's :sync-window! function."
  [gate window]
  ((:sync-window! gate) window))

(defn issue-epoch!
  "Retire the current epoch and open the next one in one durable transition.

  With EXPECTED-EPOCH, refuse unless that epoch is current.  The transition
  clears both the receipt registry and its command-id index, so a retired
  command never replays from the old window."
  ([gate] ((:issue-epoch! gate)))
  ([gate expected-epoch] ((:issue-epoch! gate) expected-epoch)))

(defn receipt-for
  "Call the gate's :receipt-for function.

  Arity 1 queries the command-id index.  Arity 2 queries one scope."
  ([gate command-id] ((:receipt-for gate) command-id))
  ([gate scope command-id] ((:receipt-for gate) scope command-id)))

(defn state
  "Return the gate's current domain state."
  [gate]
  ((:state gate)))

(defn close!
  "Close the gate and its store."
  [gate]
  ((:close! gate)))
