(ns hc.server.backup.maintenance
  "Failure-safe quiescence coordinator for offline backup restore.

  The live router refuses whole-store restore.  An application that can stop
  and restart its components in-process may call `run!` with `:stop!`,
  `:restore!` and `:start!` thunks.  Quiescence is never assumed: `:stop!` is
  required and must return a positive confirmation (`true`, `{:stopped? true}`
  or `{:quiescent? true}`).  A generic `{:ok? true}` is not proof that writers
  stopped.  A restore result is successful only for an explicit success status
  (`:complete`, `:no-changes`, `:already-imported`, `:applied`) or a plain
  `{:ok? true}`; `{:status :failed}`, `:cancelled`, `:ok? false` and unknown
  statuses are failures.

  Guarantees:

    - a missing or unconfirmed stop does not run restore;
    - a result-valued restore failure still attempts restart;
    - a successful restore with a failed restart is never reported complete;
    - every phase result is returned, so the operator can see what happened."
  (:refer-clojure :exclude [run!])
  (:require [hc.server.backup.error :as err]
            [hc.server.backup.offline :as offline]))

(def ^:private success-statuses
  #{:complete :no-changes :already-imported :applied})

(def ^:private failure-statuses
  #{:failed :cancelled :unknown :stop-failed :restart-failed
    :failed-needs-attention :outcome-unknown :needs-attention})

(defn- failure
  [code message data t]
  (cond-> {:code code :message message}
    data (assoc :details data)
    t (assoc :cause (or (ex-message t) (str t))
             :cause-code (err/code t))))

(defn- stop-confirmed?
  [result]
  (or (true? result)
      (and (map? result)
           (not (false? (:ok? result)))
           (nil? (:error result))
           (not (true? (:needs-attention? result)))
           (not (contains? failure-statuses (:status result)))
           (or (true? (:stopped? result))
               (true? (:quiescent? result))))))

(defn- restore-confirmed?
  [result]
  (or (true? result)
      (and (map? result)
           (not (false? (:ok? result)))
           (nil? (:error result))
           (not (true? (:needs-attention? result)))
           (not (true? (:needs-recovery? result)))
           (if (contains? result :status)
             (contains? success-statuses (:status result))
             (true? (:ok? result))))))

(defn- start-confirmed?
  [result]
  (cond
    (nil? result) true
    (true? result) true
    (map? result)
    (and (not (false? (:ok? result)))
         (nil? (:error result))
         (not (true? (:blocked? result)))
         (not (true? (:needs-attention? result)))
         (not (contains? failure-statuses (:status result))))
    :else false))

(defn run!
  "Run one quiescent maintenance operation.

  Options:
    :stop!      required; () -> confirmation; stop accepting work and stop
                Owners/writers
    :restore!   required; () -> result; the offline restore operation
    :start!     optional; () -> result; restart the application after restore
    :on-progress optional (fn [phase data])

  Returns a map with :status:
    :complete
    :stop-failed             stop was absent, threw, or did not confirm
    :failed                  restore failed; restart succeeded
    :restart-failed          restore succeeded; restart failed
    :failed-needs-attention  both restore and restart failed"
  [{:keys [stop! restore! start! on-progress]}]
  (when-not (ifn? restore!)
    (throw (ex-info "maintenance/run! needs a :restore! thunk"
                    {:error :BACKUP_MAINTENANCE_INVALID})))
  (let [emit (fn [phase data]
               (when on-progress
                 (try (on-progress phase data) (catch Throwable _ nil))))
        stop-result
        (if-not (ifn? stop!)
          {:ok? false
           :error (failure :BACKUP_QUIESCE_REQUIRED
                           "A :stop! thunk is required before restore"
                           nil nil)}
          (do
            (emit :stopping {})
            (try
              (let [value (stop!)]
                (if (stop-confirmed? value)
                  {:ok? true :value value :confirmed? true}
                  {:ok? false
                   :value value
                   :error (failure :BACKUP_QUIESCE_NOT_CONFIRMED
                                   "Stop returned no positive quiescence confirmation"
                                   {:result value}
                                   nil)}))
              (catch Throwable t
                {:ok? false
                 :error (failure :BACKUP_QUIESCE_STOP_FAILED
                                 "Could not stop the application for restore"
                                 nil t)}))))]
    (if-not (:ok? stop-result)
      (do
        (emit :done {:status :stop-failed})
        {:status :stop-failed
         :stop stop-result
         :restore-attempted? false
         :restart-attempted? false
         :needs-attention? true})
      (let [_ (emit :restoring {})
            restore-result
            (try
              (let [value (restore!)]
                (if (restore-confirmed? value)
                  {:ok? true :value value}
                  {:ok? false
                   :value value
                   :error (failure :BACKUP_MAINTENANCE_RESTORE_FAILED
                                   "Restore returned a non-success result"
                                   {:result value}
                                   nil)}))
              (catch Throwable t
                {:ok? false
                 :error (failure :BACKUP_MAINTENANCE_RESTORE_FAILED
                                 "Offline restore failed"
                                 nil t)}))
            _ (emit :restore-result restore-result)
            start-result
            (when (ifn? start!)
              (emit :starting {})
              (try
                (let [value (start!)]
                  (if (start-confirmed? value)
                    {:ok? true :value value}
                    {:ok? false
                     :value value
                     :error (failure :BACKUP_MAINTENANCE_RESTART_FAILED
                                     "Application restart returned a failure result"
                                     {:result value}
                                     nil)}))
                (catch Throwable t
                  {:ok? false
                   :error (failure :BACKUP_MAINTENANCE_RESTART_FAILED
                                   "Application restart failed after restore"
                                   nil t)})))
            restore-ok? (:ok? restore-result)
            start-ok? (or (nil? start-result) (:ok? start-result))
            status (cond
                     (and restore-ok? start-ok?) :complete
                     (and restore-ok? (not start-ok?)) :restart-failed
                     (and (not restore-ok?) start-ok?) :failed
                     :else :failed-needs-attention)]
        (emit :done {:status status})
        (cond-> {:status status
                 :stop stop-result
                 :restore restore-result
                 :restart start-result
                 :restore-attempted? true
                 :restart-attempted? (some? start-result)}
          (not restore-ok?) (assoc :needs-attention? true)
          (and restore-ok? (not start-ok?)) (assoc :needs-attention? true))))))

(defn restore!
  "Convenience wrapper around `hc.server.backup.offline`.

  Options are the same as `run!`, plus:
    :kind          :backup (default) or :recovery-point
    :offline-opts  map passed to offline/restore! or
                   offline/restore-recovery-point!"
  [{:keys [kind offline-opts] :as opts}]
  (let [restore-fn (case (or kind :backup)
                     :backup #(offline/restore! offline-opts)
                     :recovery-point #(offline/restore-recovery-point! offline-opts)
                     (throw (ex-info "maintenance/restore! kind must be :backup or :recovery-point"
                                     {:error :BACKUP_MAINTENANCE_INVALID
                                      :kind kind})))]
    (run! (assoc opts :restore! restore-fn))))
