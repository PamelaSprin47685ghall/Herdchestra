(ns hc.server.backup.command-epoch
  "Application command-epoch fencing across a whole-store restore.

  Restoring storage also restores the `commands/app` gate document, including
  its old active epoch and receipts.  Starting the application on that window
  would let a pre-restore client with the restored epoch replay old commands,
  or would roll the epoch backward relative to the live high-water mark.

  This namespace uses only the public durable-gate API.  It raises the retired
  high-water to the maximum of the pre-restore window and the restored window,
  clears receipts, then issues a fresh epoch before the restarted system opens
  its command gate.  It never edits or reimplements command-core policy."
  (:require [hc.server.commands.durable :as durable]
            [hc.server.commands.storage :as command-storage]
            [hc.server.storage :as storage]))

(defn window
  "Return the gate's `{:command-epoch :retired-through}` window, or nil."
  [gate]
  (when gate
    (try
      (let [state (durable/state gate)]
        (when (map? state)
          (select-keys state [:command-epoch :retired-through])))
      (catch Throwable _ nil))))

(defn capture-router
  "Capture the app command window from a live app router before it stops."
  [router]
  (window (:app-gate router)))

(defn- valid-active-epoch?
  [value]
  (or (nil? value)
      (and (integer? value) (pos? (long value)))))

(defn- valid-retired-through?
  [value]
  (and (integer? value) (not (neg? (long value)))))

(defn- valid-window?
  [window]
  (and (map? window)
       (valid-active-epoch? (:command-epoch window))
       (valid-retired-through? (:retired-through window))))

(defn high-water
  "Highest epoch represented by WINDOW.  Callers validate WINDOW first."
  [window]
  (max (long (or (:command-epoch window) 0))
       (long (or (:retired-through window) 0))))

(defn rotate-gate!
  "Fence and rotate one already-open command gate.

  `pre-window` is the app window captured before the store was stopped.  The
  new epoch is strictly greater than every epoch in `pre-window` and in the
  current restored gate.  Receipts are cleared by both the sync and the issue
  transition."
  [gate pre-window]
  (if-not gate
    {:ok? false
     :error {:code :COMMAND_EPOCH_GATE_MISSING
             :message "the application command gate is unavailable"}}
    (let [before (or (window gate)
                     {:command-epoch nil :retired-through 0})]
      (if-not (and (or (nil? pre-window) (valid-window? pre-window))
                   (valid-window? before))
        {:ok? false
         :error {:code :COMMAND_EPOCH_WINDOW_INVALID
                 :message "a command epoch window is corrupt"
                 :details {:pre-window pre-window :current-window before}}}
        (let [floor (max (high-water pre-window) (high-water before))
              synced (durable/sync-window! gate
                                           {:command-epoch nil
                                            :retired-through floor})]
          (if-not (:ok? synced)
            {:ok? false
             :error (or (:error synced)
                        {:code :COMMAND_EPOCH_SYNC_FAILED
                         :message "could not raise the retired command epoch high-water"})
             :floor floor
             :before before}
            (let [issued (durable/issue-epoch! gate)]
              (if (and (:ok? issued)
                       (integer? (:epoch issued))
                       (> (long (:epoch issued)) floor))
                {:ok? true
                 :epoch (:epoch issued)
                 :retired-through floor
                 :floor floor
                 :before before
                 :pre-window pre-window}
                {:ok? false
                 :error (or (:error issued)
                            {:code :COMMAND_EPOCH_ISSUE_FAILED
                             :message "fresh application command epoch is not above the fence"
                             :details {:issued (:epoch issued) :floor floor}})
                 :floor floor
                 :before before}))))))))

(defn rotate-store!
  "Rotate the app command gate inside a closed-state store.

  Called after the offline restore and before the restarted system opens its
  router.  The store is opened read/write, the gate is rotated, and the store
  is closed in every case."
  [db-path pre-window]
  (when (or (nil? db-path) (and (string? db-path) (empty? db-path)))
    (throw (ex-info "command epoch rotation needs a store path"
                    {:error :COMMAND_EPOCH_STORE_MISSING})))
  (let [store (storage/open-store {:path (str db-path)
                                   :create? false
                                   :durability :sync})]
    (try
      (let [gate (durable/gate
                  (command-storage/store
                   store {:partition "commands/app" :id "app"}))]
        (rotate-gate! gate pre-window))
      (finally
        (storage/close-store! store)))))
