(ns hc.server.app.scheduler
  "Application-level scheduler command adapter.

  The scheduler runtime owns durable schedule state and its own timer.  This
  namespace only maps client payloads to its public functions; it never
  invents a run result and never pretends a missed occurrence was executed."
  (:require [hc.server.scheduler :as scheduler]))

(defn handle!
  [runtime payload]
  (if-not runtime
    {:ok? false
     :error {:code :SCHEDULER_UNAVAILABLE
             :message "scheduler is not running"}}
    (let [kind (:kind payload)
          schedule-id (:schedule-id payload)
          result
          (case kind
            :scheduler/snapshot
            {:ok? true :receipt {:status :accepted
                                 :snapshot (scheduler/snapshot runtime)}}

            :scheduler/schedules
            {:ok? true :receipt {:status :accepted
                                 :schedules (scheduler/schedules runtime)}}

            :scheduler/runs
            {:ok? true :receipt {:status :accepted
                                 :runs (scheduler/runs runtime)}}

            :scheduler/health
            {:ok? true :receipt {:status :accepted
                                 :health (scheduler/health runtime)}}

            :scheduler/create
            (scheduler/create-schedule! runtime (:schedule payload))

            :scheduler/update
            (scheduler/update-schedule! runtime schedule-id (:changes payload))

            :scheduler/enable
            (scheduler/enable! runtime schedule-id)

            :scheduler/pause
            (scheduler/pause! runtime schedule-id)

            :scheduler/resume
            (scheduler/resume! runtime schedule-id)

            :scheduler/delete
            (scheduler/delete-schedule! runtime schedule-id (:opts payload))

            :scheduler/manual-run
            (scheduler/manual-run! runtime schedule-id (:opts payload))

            :scheduler/preview
            (scheduler/preview runtime schedule-id (:opts payload))

            :scheduler/tick
            (scheduler/tick-now! runtime)

            :scheduler/set-device-zone
            (scheduler/set-device-zone! runtime (:zone payload))

            {:ok? false
             :error {:code :SCHEDULER_KIND_UNKNOWN
                     :message "unknown scheduler command kind"
                     :details {:kind kind}}})]
      (cond
        (not (map? result))
        {:ok? false
         :error {:code :SCHEDULER_RESULT_INVALID
                 :message "scheduler returned a non-map"}}

        (contains? result :receipt)
        result

        (:ok? result)
        (assoc result :receipt
               {:status :accepted
                :result (dissoc result :ok? :receipt)})

        :else result))))
