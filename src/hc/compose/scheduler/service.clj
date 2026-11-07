(ns hc.compose.scheduler.service
  "Effectful schedule service.

  The service owns one immutable state atom, one store, one clock, and the
  host callbacks.  Every accepted transition persists the whole state before
  any effect is dispatched.  A store failure leaves the in-memory state
  unchanged and returns explicit error data.

  Restart behavior:

  - `open-service` loads persisted state and runs `core/recover` before the
    caller receives the service.  A run that was preparing or running becomes
    `:interrupted`; it is never replayed.
  - Enabled schedules that passed their next run record `:missed` facts and
    advance to a strictly future occurrence.  Restart never catches up.
  - A pending or failed notification stays pending.  Only an explicit
    `retry-notification!` sends it again; the task is not re-run.

  The host callbacks are deliberately small:

    :start-run! (fn [{:keys [run schedule]}] ...)
      Accept the run.  Return truthy or `{:accepted true}`.  The host calls
      `finish-run!` later from its execution path.  A synchronous
      `finish-run!` inside the callback is tolerated; the service then does
      not force the run back to `:running`.

    :stop-run!  (fn [{:keys [run-id reason]}] ...)
    :notify!    (fn [{:keys [run schedule]}] ...)

  Callbacks run after the commit and outside the service lock.  They must not
  call back into the service synchronously except for `finish-run!`; use the
  execution thread for everything else."
  (:require [hc.compose.scheduler.core :as core]
            [hc.compose.scheduler.model :as model]
            [hc.compose.scheduler.store :as store]
            [hc.compose.scheduler.time :as time])
  (:import (java.util.concurrent.locks ReentrantLock)))

(defrecord SchedulerService
    [state store clock preflight start-run! stop-run! notify! lock config])

(defn- now-ms
  [service]
  (time/to-epoch-ms ((:clock service))))

(defn- locked
  [service f]
  (.lock ^ReentrantLock (:lock service))
  (try
    (f)
    (finally
      (.unlock ^ReentrantLock (:lock service)))))

(defn- commit!
  "Persist TRANSITION's state, then publish it in memory.

  A store throw returns `:SCHEDULER_STORE_WRITE_FAILED` and leaves the atom
  on the previous value.  The transition is not dispatched.

  Every accepted transition is handed to the store, even when the domain
  state compares equal.  The durable adapter also mirrors obligation-only
  rows, so skipping the write would leave those rows stale."
  [service transition]
  (if-not (:ok? transition)
    transition
    (locked
     service
     (fn []
       (let [state (:state transition)]
         (try
           (store/save-state! (:store service) state)
           (reset! (:state service) state)
           transition
           (catch Throwable t
             {:ok? false
              :error {:code :SCHEDULER_STORE_WRITE_FAILED
                      :message "the scheduler state could not be persisted"
                      :details {:cause (or (.getMessage t) (str t))}}})))))))

(defn- accepted?
  [value]
  (or (true? value)
      (= :accepted value)
      (and (map? value)
           (or (true? (:accepted value))
               (= :accepted (:status value))
               (= :accepted (:outcome value))))))

(defn- preflight-failure
  [cause]
  {:ok? false
   :issues [{:code :PREFLIGHT_FAILED
             :message "run preflight threw an exception"
             :details {:cause (or (.getMessage ^Throwable cause)
                                  (str cause))}}]})

(defn- run-preflight
  [service schedule now]
  (try
    (let [result ((:preflight service)
                  {:schedule schedule
                   :now now
                   :device-zone (:device-zone @(:state service))})]
      (cond
        (true? result) {:ok? true}
        (map? result) result
        :else {:ok? false
               :issues [{:code :PREFLIGHT_FAILED
                         :message "run preflight returned an unrecognized value"
                         :details {:result result}}]}))
    (catch Throwable t
      (preflight-failure t))))

(defn- mark-start-failed!
  [service run-id error]
  (locked service
          #(commit! service
                    (core/mark-start-failed @(:state service) run-id
                                            (now-ms service) error))))

(defn- dispatch-start!
  [service effect]
  (let [run (:run effect)
        run-id (:id run)]
    (if-let [start-run! (:start-run! service)]
      (try
        (let [result (start-run! {:run run
                                  :schedule (:schedule effect)})]
          (if (accepted? result)
            (locked
             service
             (fn []
               (let [transition (core/mark-running
                                 @(:state service) run-id
                                 (now-ms service))]
                 (if (:ok? transition)
                   (do (commit! service transition)
                       {:run-id run-id :status :running})
                   ;; A synchronous finish-run! may already have terminalized
                   ;; the run.  That is not a dispatch failure.
                   (if (= :RUN_NOT_PREPARING
                          (get-in transition [:error :code]))
                     {:run-id run-id :status :already-finished}
                     (do (commit! service transition)
                         {:run-id run-id :status :failed
                          :error (:error transition)}))))))
            (do (mark-start-failed! service run-id
                                    (or (:error result)
                                        {:code :START_REJECTED
                                         :message "the host did not accept the run"
                                         :details {:result result}}))
                {:run-id run-id :status :failed
                 :reason :start-rejected
                 :error (:error result)})))
        (catch Throwable t
          (let [error {:code :START_DISPATCH_FAILED
                       :message "the host start callback threw"
                       :details {:cause (or (.getMessage t) (str t))}}]
            (mark-start-failed! service run-id error)
            {:run-id run-id :status :failed
             :reason :start-dispatch-failed
             :error error})))
      (do
        (mark-start-failed! service run-id
                            {:code :NO_EXECUTOR
                             :message "no :start-run! callback is configured"})
        {:run-id run-id :status :failed :reason :no-executor}))))

(defn- dispatch-stop!
  [service effect]
  (if-let [stop-run! (:stop-run! service)]
    (try
      (stop-run! {:run-id (:run-id effect)
                  :reason (:reason effect)})
      {:run-id (:run-id effect) :status :stop-requested}
      (catch Throwable t
        {:run-id (:run-id effect)
         :status :stop-failed
         :error {:code :STOP_DISPATCH_FAILED
                 :message (or (.getMessage t) (str t))}}))
    {:run-id (:run-id effect) :status :no-stop-callback}))

(defn- dispatch-notify!
  [service effect]
  (if-let [notify! (:notify! service)]
    (try
      (let [result (notify! {:run-id (:run-id effect)
                             :run (:run effect)
                             :schedule (:schedule effect)})]
        (cond
          (or (true? result)
              (= :sent result)
              (= :sent (:state result)))
          (do (locked service
                      #(commit! service
                                (core/mark-notification
                                 @(:state service)
                                 (:run-id effect)
                                 {:state :sent}
                                 (now-ms service))))
              {:run-id (:run-id effect) :status :sent})

          (or (= :failed result)
              (= :failed (:state result)))
          (do (locked service
                      #(commit! service
                                (core/mark-notification
                                 @(:state service)
                                 (:run-id effect)
                                 {:state :failed
                                  :error (:error result)}
                                 (now-ms service))))
              {:run-id (:run-id effect) :status :failed})

          :else
          {:run-id (:run-id effect) :status :pending}))
      (catch Throwable t
        (let [error {:code :NOTIFICATION_FAILED
                     :message (or (.getMessage t) (str t))}]
          (locked service
                  #(commit! service
                            (core/mark-notification
                             @(:state service)
                             (:run-id effect)
                             {:state :failed :error error}
                             (now-ms service))))
          {:run-id (:run-id effect) :status :failed :error error})))
    {:run-id (:run-id effect) :status :pending}))

(defn- dispatch-effects!
  [service effects]
  (mapv (fn [effect]
          (case (:effect effect)
            :start-run (dispatch-start! service effect)
            :stop-run (dispatch-stop! service effect)
            :notify (dispatch-notify! service effect)
            {:status :unknown-effect :effect (:effect effect)}))
        (or effects [])))

(defn open-service
  "Open a scheduler service and recover persisted state.

  Options:

    :store          a `hc.compose.scheduler.store/Store`; default in-memory
    :clock          zero-argument clock; default `System/currentTimeMillis`
    :preflight      `(fn [{:keys [schedule now device-zone]}] ...)`
    :start-run!     host acceptance callback
    :stop-run!      host stop callback
    :notify!        host notification callback
    :device-zone    current device zone; default JVM default
    :grace-ms       on-time window before an occurrence is missed; default 60000
    :timeout-ms     run limit; default 600000, maximum 1800000
    :recovery-reason reason recorded on interrupted runs; default :process-restart

  Returns `{:ok? true :service service :recovery report}` or an error value.
  A store that exists but cannot be read is never replaced with an empty
  state."
  [{:keys [store clock preflight start-run! stop-run! notify! device-zone
           grace-ms timeout-ms recovery-reason]
    :or {clock #(System/currentTimeMillis)
         grace-ms 60000
         timeout-ms 600000
         recovery-reason :process-restart}}]
  (let [device-zone (or device-zone (time/system-zone))
        store (or store (store/memory-store))]
    (cond
      (not (time/valid-zone? device-zone))
      (model/error :INVALID_TIMEZONE "device zone is not a valid IANA zone id"
                   {:zone device-zone})

      (not (integer? grace-ms))
      (model/error :INVALID_CONFIG ":grace-ms must be an integer"
                   {:grace-ms grace-ms})

      (neg? grace-ms)
      (model/error :INVALID_CONFIG ":grace-ms must not be negative"
                   {:grace-ms grace-ms})

      (not (and (integer? timeout-ms)
                (<= 1 timeout-ms 1800000)))
      (model/error :INVALID_CONFIG
                   ":timeout-ms must be 1..1800000 (30 minutes)"
                   {:timeout-ms timeout-ms})

      (and store (not (satisfies? store/Store store)))
      (model/error :INVALID_CONFIG ":store must satisfy the scheduler Store protocol"
                   {:store store})

      (not (ifn? clock))
      (model/error :INVALID_CONFIG ":clock must be callable"
                   {:clock clock})

      (and start-run! (not (ifn? start-run!)))
      (model/error :INVALID_CONFIG ":start-run! must be callable"
                   {:start-run! start-run!})

      (and stop-run! (not (ifn? stop-run!)))
      (model/error :INVALID_CONFIG ":stop-run! must be callable"
                   {:stop-run! stop-run!})

      (and notify! (not (ifn? notify!)))
      (model/error :INVALID_CONFIG ":notify! must be callable"
                   {:notify! notify!})

      (and preflight (not (ifn? preflight)))
      (model/error :INVALID_CONFIG ":preflight must be callable"
                   {:preflight preflight})

      :else
      (let [loaded (try
                     {:ok? true :state (store/load-state store)}
                     (catch Throwable t
                       {:ok? false
                        :error {:code :SCHEDULER_STORE_UNREADABLE
                                :message "the scheduler store exists but cannot be read"
                                :details {:cause (or (.getMessage t) (str t))}}}))]
        (if-not (:ok? loaded)
          loaded
          (let [initial (or (:state loaded)
                            (core/init-state device-zone))
                initial (if (:device-zone initial)
                          initial
                          (assoc initial :device-zone device-zone))]
            (if-not (core/valid-state? initial)
              (model/error :CORRUPT_SCHEDULER_STATE
                           "persisted scheduler state has an invalid shape"
                           {:state initial})
              (let [service (->SchedulerService
                             (atom initial)
                             store
                             clock
                             (or preflight (constantly {:ok? true}))
                             start-run!
                             stop-run!
                             notify!
                             (ReentrantLock.)
                             {:grace-ms grace-ms
                              :timeout-ms timeout-ms
                              :recovery-reason recovery-reason})
                    now (now-ms service)
                    normalized (model/normalize-restored-schedules initial now)
                    zone-transition
                    (if (not= (:device-zone normalized) device-zone)
                      (core/set-device-zone normalized device-zone now)
                      {:ok? true
                       :state (assoc normalized :device-zone device-zone)
                       :changes []})
                    zone-state (:state zone-transition)
                    recovery (core/recover zone-state now
                                           {:reason recovery-reason})]
                (if-not (:ok? recovery)
                  recovery
                  (let [final-state (:state recovery)
                        ;; Always hand the recovered state to the store.  The
                        ;; durable adapter reconciles stale obligation rows
                        ;; even when the domain documents are unchanged.
                        saved (try
                                (store/save-state! store final-state)
                                {:ok? true}
                                (catch Throwable t
                                  {:ok? false
                                   :error {:code :SCHEDULER_STORE_WRITE_FAILED
                                           :message "recovered scheduler state could not be saved"
                                           :details {:cause (or (.getMessage t)
                                                                (str t))}}}))]
                    (if-not (:ok? saved)
                      saved
                      (do
                        (reset! (:state service) final-state)
                        {:ok? true
                         :service service
                         :recovery (assoc (dissoc recovery :state)
                                          :zone-changes (:changes zone-transition))}))))))))))))

(defn snapshot
  [service]
  @(:state service))

(defn schedules
  [service]
  (into {} (sort-by key (:schedules @(:state service)))))

(defn schedule
  [service schedule-id]
  (get-in @(:state service) [:schedules schedule-id]))

(defn runs
  [service]
  (core/all-runs @(:state service)))

(defn runs-for
  [service schedule-id]
  (core/runs-for @(:state service) schedule-id))

(defn run
  [service run-id]
  (core/run-of @(:state service) run-id))

(defn pending-notifications
  [service]
  (core/pending-notifications @(:state service)))

(defn preview
  ([service schedule-id]
   (preview service schedule-id {}))
  ([service schedule-id opts]
   (if-let [schedule (schedule service schedule-id)]
     (model/preview schedule (now-ms service)
                    (assoc opts :device-zone
                           (:device-zone @(:state service))))
     (model/error :UNKNOWN_SCHEDULE "schedule does not exist"
                  {:schedule-id schedule-id}))))

(defn create-schedule!
  [service spec]
  (let [now (now-ms service)]
    (locked service
            #(commit! service
                      (core/create-schedule @(:state service) spec now)))))

(defn update-schedule!
  [service schedule-id changes]
  (let [now (now-ms service)]
    (locked service
            #(commit! service
                      (core/update-schedule @(:state service)
                                            schedule-id changes now)))))

(defn enable!
  [service schedule-id]
  (let [now (now-ms service)]
    (locked service
            #(commit! service
                      (core/enable-schedule @(:state service)
                                            schedule-id now)))))

(defn pause!
  [service schedule-id]
  (let [now (now-ms service)]
    (locked service
            #(commit! service
                      (core/pause-schedule @(:state service)
                                           schedule-id now)))))

(defn resume!
  [service schedule-id]
  (let [now (now-ms service)]
    (locked service
            #(commit! service
                      (core/resume-schedule @(:state service)
                                            schedule-id now)))))

(defn repair!
  ([service schedule-id]
   (repair! service schedule-id {}))
  ([service schedule-id changes]
   (let [now (now-ms service)]
     (locked service
             #(commit! service
                       (core/repair-schedule @(:state service)
                                             schedule-id changes now))))))

(defn delete-schedule!
  ([service schedule-id]
   (delete-schedule! service schedule-id {}))
  ([service schedule-id opts]
   (let [now (now-ms service)]
     (let [transition (locked service
                              #(core/delete-schedule @(:state service)
                                                     schedule-id now opts))
           committed (commit! service transition)]
       (if (:ok? committed)
         (assoc committed :dispatch
                (dispatch-effects! service (:effects committed)))
         committed)))))

(defn import-schedule!
  [service spec]
  (let [now (now-ms service)]
    (locked service
            #(commit! service
                      (core/import-schedule @(:state service) spec now)))))

(defn copy-schedule!
  "Copy a schedule as a paused, independent schedule.

  The name receives a ` (copy)` suffix.  The source snapshot is preserved and
  the copy never inherits the enabled state."
  [service schedule-id]
  (if-let [source (schedule service schedule-id)]
    (import-schedule!
     service
     (-> source
         (dissoc :id :active-run-id :last-run-id :created-at :updated-at
                 :paused-at :next-run :attention :last-zone-change
                 :exhausted? :state)
         (assoc :name (str (:name source) " (copy)"))))
    (model/error :UNKNOWN_SCHEDULE "schedule does not exist"
                 {:schedule-id schedule-id})))

(defn tick!
  "Advance all enabled schedules to the service clock.

  Preflight runs only for schedules that reached their next run and have no
  active run.  The state commit happens before any start, stop, or notify
  callback."
  [service]
  (let [now (now-ms service)
        due (locked service
                    #(core/due-schedule-ids @(:state service) now))
        preflight (into {}
                        (map (fn [schedule-id]
                               (let [schedule (schedule service schedule-id)]
                                 [schedule-id
                                  (if schedule
                                    (run-preflight service schedule now)
                                    {:ok? false
                                     :issues [{:code :UNKNOWN_SCHEDULE
                                               :message "schedule disappeared during tick"}]})]))
                             due))
        transition (try
                     (locked service
                             #(core/tick
                               @(:state service) now preflight
                               {:grace-ms (get-in service [:config :grace-ms])
                                :timeout-ms (get-in service [:config :timeout-ms])}))
                     (catch Throwable t
                       {:ok? false
                        :error {:code :SCHEDULER_TICK_FAILED
                                :message "the scheduler tick failed"
                                :details {:cause (or (.getMessage t) (str t))}}}))
        committed (commit! service transition)]
    (if (:ok? committed)
      (assoc committed :dispatch (dispatch-effects! service (:effects committed)))
      committed)))

(defn manual-run!
  ([service schedule-id]
   (manual-run! service schedule-id {}))
  ([service schedule-id opts]
   (let [now (now-ms service)
         schedule (schedule service schedule-id)
         preflight (if schedule
                     (run-preflight service schedule now)
                     {:ok? false
                      :issues [{:code :UNKNOWN_SCHEDULE
                                :message "schedule does not exist"}]})
         transition (locked service
                           #(core/manual-run @(:state service) schedule-id now
                                             preflight opts))
         committed (commit! service transition)]
     (if (:ok? committed)
       (assoc committed :dispatch (dispatch-effects! service (:effects committed)))
       committed))))

(defn attach-run!
  "Attach a result chat/owner/command identity to a run."
  [service run-id attrs]
  (let [now (now-ms service)]
    (locked service
            #(commit! service
                      (core/attach-run @(:state service) run-id attrs now)))))

(defn finish-run!
  ([service run-id result]
   (finish-run! service run-id result {}))
  ([service run-id result opts]
   (let [now (now-ms service)
         transition (locked service
                           #(core/finish-run @(:state service) run-id result now
                                             opts))
         committed (commit! service transition)]
     (if (:ok? committed)
       (assoc committed :dispatch (dispatch-effects! service (:effects committed)))
       committed))))

(defn retry-notification!
  [service run-id]
  (let [now (now-ms service)
        transition (locked service
                          #(core/retry-notification @(:state service)
                                                    run-id now))
        committed (commit! service transition)]
    (if (:ok? committed)
      (assoc committed :dispatch (dispatch-effects! service (:effects committed)))
      committed)))

(defn mark-notification!
  [service run-id outcome]
  (let [now (now-ms service)]
    (locked service
            #(commit! service
                      (core/mark-notification @(:state service)
                                              run-id outcome now)))))

(defn set-device-zone!
  "Adopt a new device or server zone.  Fixed schedules keep their zone."
  [service zone]
  (let [now (now-ms service)]
    (locked service
            #(commit! service
                      (core/set-device-zone @(:state service) zone now)))))

(defn recover!
  "Reconcile persisted state again, for example after a simulated restart."
  ([service]
   (recover! service {}))
  ([service opts]
   (let [now (now-ms service)
         transition (locked service
                           #(core/recover @(:state service) now
                                          (merge {:reason
                                                  (get-in service
                                                          [:config :recovery-reason])}
                                                 opts)))]
     (commit! service transition))))

(defn close!
  "Release the service.  The state is already durable after every commit."
  [_service]
  nil)
