(ns hc.compose.scheduler.core
  "Pure schedule state transitions.

  Every function takes the committed scheduler state and returns a new state
  plus explicit facts and effects.  No function reads a clock, writes a store,
  starts a thread, or calls a model.  The service owns persistence and
  dispatch.

  State:

    {:schedules {schedule-id schedule}
     :runs      {run-id run}
     :run-order [run-id ...]
     :seq       n
     :revision  n
     :device-zone \"Asia/Shanghai\"}

  A schedule owns at most one active run.  Active means `:preparing`,
  `:running`, `:awaiting-user`, or `:outcome-unknown`.  A due occurrence while
  an active run exists becomes a `:skipped` fact with reason
  `:previous-run-active`; it is never queued.

  Missed occurrences become `:missed` facts.  They are facts, not runs: no
  model request, no tool call, and no catch-up.  A DST gap is also `:missed`
  with reason `:nonexistent-local-time`."
  (:require [hc.compose.scheduler.model :as model]
            [hc.compose.scheduler.time :as time]))

(def empty-state
  {:schedules {}
   :runs {}
   :run-order []
   :seq 0
   :revision 0
   :device-zone nil})

(defn init-state
  [device-zone]
  (assoc empty-state :device-zone device-zone))

(defn valid-state?
  "Minimal structural check before a loaded state may be used."
  [state]
  (and (map? state)
       (map? (:schedules state))
       (map? (:runs state))
       (vector? (:run-order state))
       (integer? (:seq state))
       (every? map? (vals (:schedules state)))
       (every? map? (vals (:runs state)))
       (every? #(contains? (:runs state) %) (:run-order state))))

(defn- bump
  [state]
  (update state :revision (fnil inc 0)))

(defn- allocate-id
  [state prefix]
  (let [n (inc (long (or (:seq state) 0)))]
    [(assoc state :seq n) (str prefix "-" n)]))

(defn schedule-of
  [state schedule-id]
  (get-in state [:schedules schedule-id]))

(defn run-of
  [state run-id]
  (get-in state [:runs run-id]))

(defn all-runs
  "Runs in creation order."
  [state]
  (into [] (keep #(get-in state [:runs %])) (:run-order state)))

(defn runs-for
  "Runs for one schedule in creation order."
  [state schedule-id]
  (into [] (filter #(= schedule-id (:schedule-id %))) (all-runs state)))

(defn active-run-of
  "The schedule's active run, or nil.  A stale pointer is ignored."
  [state schedule]
  (let [pointed (when-let [run-id (:active-run-id schedule)]
                  (get-in state [:runs run-id]))
        active? #(contains? model/active-run-statuses (:status %))]
    (or (when (and pointed (active? pointed)) pointed)
        (some (fn [run]
                (when (and (= (:id schedule) (:schedule-id run))
                           (active? run))
                  run))
              (all-runs state)))))

(defn- add-run
  [state run]
  (-> state
      (assoc-in [:runs (:id run)] run)
      (update :run-order conj (:id run))))

(defn- make-run
  [state schedule attrs now]
  (let [[state run-id] (allocate-id state "run")
        run {:id run-id
             :schedule-id (:id schedule)
             :trigger (:trigger attrs :scheduled)
             :status (:status attrs)
             :revision (:revision schedule)
             :snapshot (:snapshot schedule)
             :execution (:execution schedule)
             :scheduled-at (:scheduled-at attrs)
             :scheduled-local (:scheduled-local attrs)
             :scheduled-zone (:scheduled-zone attrs)
             :scheduled-exists? (if (contains? attrs :scheduled-exists?)
                                  (:scheduled-exists? attrs)
                                  true)
             :ambiguous? (boolean (:ambiguous? attrs))
             :original-scheduled-at (:original-scheduled-at attrs)
             :next-run-at-creation (:next-run-at-creation attrs)
             :data-mode (:data-mode attrs)
             :created-at now
             :started-at nil
             :ended-at nil
             :recorded-at now
             :chat-id nil
             :result-files []
             :notification nil
             :reason (:reason attrs)
             :issues (:issues attrs)
             :manual? (= :manual (:trigger attrs))
             :attempt (or (:attempt attrs) 1)}]
    [state run]))

(defn- occurrence-epoch
  [occurrence]
  (or (:epoch-ms occurrence) (:nominal-epoch-ms occurrence)))

(defn- add-missed-run
  [state schedule occurrence reason now]
  (let [gap? (= :gap (:kind occurrence))
        [state run] (make-run state schedule
                              {:status :missed
                               :trigger :scheduled
                               :scheduled-at (occurrence-epoch occurrence)
                               :scheduled-local (:local-date-time occurrence)
                               :scheduled-zone (:zone occurrence)
                               :scheduled-exists? (not gap?)
                               :ambiguous? (:ambiguous? occurrence)
                               :reason (if gap?
                                         :nonexistent-local-time
                                         (or reason :missed))}
                              now)
        state (add-run state run)]
    [state run]))

(defn- add-skipped-run
  ([state schedule occurrence reason now]
   (add-skipped-run state schedule occurrence reason now :scheduled))
  ([state schedule occurrence reason now trigger]
   (let [[state run] (make-run state schedule
                              {:status :skipped
                               :trigger trigger
                               :scheduled-at (occurrence-epoch occurrence)
                               :scheduled-local (:local-date-time occurrence)
                               :scheduled-zone (:zone occurrence)
                               :scheduled-exists? (not= :gap (:kind occurrence))
                               :ambiguous? (:ambiguous? occurrence)
                               :reason reason}
                              now)]
     [(add-run state run) run])))

(defn- preflight-ok?
  [result]
  (cond
    (true? result) true
    (and (map? result) (true? (:ok? result))) true
    (and (map? result)
         (nil? (:ok? result))
         (empty? (:issues result))
         (nil? (:error result))) true
    :else false))

(defn- preflight-skip?
  "True when the host asks to skip this occurrence rather than disable the
  schedule.  A busy result chat is the canonical case."
  [result]
  (and (map? result)
       (true? (:skip? result))))

(defn- preflight-issues
  [result]
  (cond
    (and (map? result) (seq (:issues result))) (vec (:issues result))
    (and (map? result) (:error result)) [(:error result)]
    (map? result) [{:code :PREFLIGHT_FAILED
                    :message "run preflight did not pass"}]
    :else [{:code :PREFLIGHT_FAILED
            :message "run preflight did not pass"
            :details {:result result}}]))

(defn- prepare-run
  [state schedule occurrence trigger extra now]
  (let [[state run] (make-run state schedule
                              (merge {:status :preparing
                                      :trigger trigger
                                      :scheduled-at (occurrence-epoch occurrence)
                                      :scheduled-local (:local-date-time occurrence)
                                      :scheduled-zone (:zone occurrence)
                                      :scheduled-exists? (not= :gap (:kind occurrence))
                                      :ambiguous? (:ambiguous? occurrence)}
                                     extra)
                              now)
        schedule' (assoc schedule
                          :active-run-id (:id run)
                          :last-run-id (:id run)
                          :updated-at now)
        state (-> state
                  (add-run run)
                  (assoc-in [:schedules (:id schedule)] schedule'))]
    [state run schedule']))

(defn- needs-attention-run
  [state schedule occurrence trigger issues now]
  (let [[state run] (make-run state schedule
                              {:status :needs-attention
                               :trigger trigger
                               :scheduled-at (occurrence-epoch occurrence)
                               :scheduled-local (:local-date-time occurrence)
                               :scheduled-zone (:zone occurrence)
                               :scheduled-exists? (not= :gap (:kind occurrence))
                               :ambiguous? (:ambiguous? occurrence)
                               :reason :preflight-failed
                               :issues issues}
                              now)
        state (add-run state run)
        schedule' (cond-> (assoc schedule
                                  :last-run-id (:id run)
                                  :attention issues
                                  :updated-at now)
                    (= :enabled (:state schedule))
                    (assoc :state :needs-attention))]
    [(assoc-in state [:schedules (:id schedule)] schedule') run]))

(defn- timeout-runs
  [state now timeout-ms]
  (reduce
   (fn [[state effects timed-out] run]
     (if (and (= :running (:status run))
              (number? (:started-at run))
              (<= (+ (long (:started-at run)) (long timeout-ms)) now))
       (let [run' (assoc run
                         :status :timed-out
                         :reason :timeout
                         :ended-at now
                         :timeout-ms timeout-ms)
             schedule (get-in state [:schedules (:schedule-id run)])
             state' (assoc-in state [:runs (:id run)] run')
             state' (if (and schedule
                             (= (:active-run-id schedule) (:id run)))
                      (assoc-in state' [:schedules (:id schedule)]
                                (assoc schedule
                                       :active-run-id nil
                                       :updated-at now))
                      state')]
         [state'
          (conj effects {:effect :stop-run
                         :run-id (:id run)
                         :reason :timeout})
          (conj timed-out run')])
       [state effects timed-out]))
   [state [] []]
   (all-runs state)))

(declare record-missed-window record-gaps due-pending-gaps)

(defn- advance-blocked-schedule
  "Record the occurrences a `:needs-attention` schedule has passed.

  A schedule that needs attention never starts a run, but the occurrence that
  failed and every later blocked occurrence are still facts.  They are
  recorded one at a time as `:missed` with reason `:needs-attention`; the
  next run advances strictly past NOW so a later tick cannot duplicate them.
  "
  [state schedule now]
  (let [zone (get-in schedule [:timezone :zone])
        recurrence (:recurrence schedule)
        next-run (:next-run schedule)
        next-due? (and next-run (<= next-run now))
        gap-due? (seq (due-pending-gaps schedule now))]
    (if-not (and recurrence
                 (time/valid-zone? zone)
                 (or next-due? gap-due?))
      state
      (let [[state gap-runs]
            (record-gaps state schedule
                         (due-pending-gaps schedule now)
                         :needs-attention now)
            [state runs]
            (if next-due?
              (record-missed-window state schedule next-run now
                                    :needs-attention)
              [state []])
            window (time/next-window recurrence zone now)
            next-run' (:next-run window)
            schedule' (assoc schedule
                             :next-run next-run'
                             :pending-gaps (or (:pending-gaps window) [])
                             :exhausted? (nil? next-run')
                             :updated-at now)]
        (assoc-in state [:schedules (:id schedule)] schedule')))))

(defn- tick-schedule
  [state schedule now {:keys [grace-ms preflight] :or {grace-ms 60000}}]
  (let [next-run (:next-run schedule)
        zone (get-in schedule [:timezone :zone])
        recurrence (:recurrence schedule)
        pending-gaps (:pending-gaps schedule)
        due-gaps (filter #(<= (:nominal-epoch-ms %) now) pending-gaps)
        next-due? (and next-run (<= next-run now))]
    (if (or (nil? recurrence)
            (not (time/valid-zone? zone))
            (and (not next-due?) (empty? due-gaps)))
      {:state state :runs [] :effects [] :missed [] :skipped []
       :needs-attention [] :started []}
      (let [{:keys [runs gaps]}
            (if next-due?
              (time/occurrences-between recurrence zone next-run now)
              {:runs [] :gaps []})
            due (sort-by :epoch-ms runs)
            current (last (filter #(<= (- now (:epoch-ms %)) grace-ms)
                                  due))
            earlier (if current
                      (remove #(= (:epoch-ms %) (:epoch-ms current)) due)
                      due)
            missed (sort-by occurrence-epoch
                            (concat earlier gaps due-gaps))
            next-window (time/next-window recurrence zone now)
            next-run' (:next-run next-window)
            [state missed-runs]
            (reduce (fn [[state recorded] occurrence]
                      (let [[state run] (add-missed-run
                                         state schedule occurrence :missed now)]
                        [state (conj recorded run)]))
                    [state []]
                    missed)
            [state runs' effects started skipped needs-attention]
            (if-not current
              [state [] [] [] [] []]
              (if-let [active (active-run-of state schedule)]
                (let [[state run] (add-skipped-run
                                   state schedule current
                                   :previous-run-active now)]
                  [state [run] [] [] [run] []])
                (let [preflight (get preflight (:id schedule) {:ok? true})]
                  (cond
                    (preflight-skip? preflight)
                    (let [[state run] (add-skipped-run
                                       state schedule current
                                       (or (:reason preflight) :preflight-skip)
                                       now)]
                      [state [run] [] [] [run] []])

                    (preflight-ok? preflight)
                    (let [[state run _]
                          (prepare-run state schedule current :scheduled
                                       {:next-run-at-creation next-run'} now)]
                      [state [run]
                       [{:effect :start-run
                         :run run
                         :schedule (get-in state [:schedules (:id schedule)])}]
                       [run] [] []])

                    :else
                    (let [[state run] (needs-attention-run
                                       state schedule current :scheduled
                                       (preflight-issues preflight) now)]
                      [state [run] [] [] [] [run]])))))
            schedule-after (get-in state [:schedules (:id schedule)])
            last-run (last (concat missed-runs runs'))
            schedule' (cond-> (assoc schedule-after
                                     :next-run next-run'
                                     :pending-gaps (:pending-gaps next-window)
                                     :exhausted? (nil? next-run')
                                     :updated-at now)
                        last-run
                        (assoc :last-run-id (:id last-run)))
            state (assoc-in state [:schedules (:id schedule)] schedule')]
        {:state state
         :effects effects
         :missed missed-runs
         :skipped skipped
         :needs-attention needs-attention
         :started started}))))

(defn tick
  "Advance every enabled schedule to NOW.

  `preflight` maps schedule-id to the host's preflight result
  `{:ok? true}` or `{:ok? false :issues [...]}`.  A missing entry is treated
  as passing; the service supplies every due schedule.

  Returns `{:ok? true :state s' :started [...] :missed [...] :skipped [...]
  :needs-attention [...] :timed-out [...] :effects [...]}`.  Effects are
  `:start-run` and `:stop-run`; the service dispatches them only after the
  state commit."
  ([state now preflight]
   (tick state now preflight {}))
  ([state now preflight {:keys [grace-ms timeout-ms]
                         :or {grace-ms 60000
                              timeout-ms 600000}}]
   (let [now (time/to-epoch-ms now)
         initial-state state
         [state timeout-effects timed-out]
         (timeout-runs state now timeout-ms)
         schedules (sort-by :id (vals (:schedules state)))
         result
         (reduce
          (fn [acc schedule]
            (case (:state schedule)
              :enabled
              (let [one (tick-schedule (:state acc) schedule now
                                       {:grace-ms grace-ms
                                        :preflight preflight})]
                (-> acc
                    (assoc :state (:state one))
                    (update :started into (:started one))
                    (update :missed into (:missed one))
                    (update :skipped into (:skipped one))
                    (update :needs-attention into (:needs-attention one))
                    (update :effects into (:effects one))))

              :needs-attention
              (update acc :state advance-blocked-schedule schedule now)

              acc))
          {:state state
           :started []
           :missed []
           :skipped []
           :needs-attention []
           :effects timeout-effects}
          schedules)
         state (:state result)
         changed? (not= (dissoc initial-state :revision)
                        (dissoc state :revision))
         state (if changed? (bump state) state)]
     {:ok? true
      :state state
      :started (vec (:started result))
      :missed (vec (:missed result))
      :skipped (vec (:skipped result))
      :needs-attention (vec (:needs-attention result))
      :timed-out (vec timed-out)
      :effects (vec (:effects result))})))

(defn- due-pending-gaps
  [schedule now]
  (filter #(<= (:nominal-epoch-ms %) now) (:pending-gaps schedule)))

(defn due-schedule-ids
  "Enabled schedules that have reached their next run and have no active run.

  The service uses this to run preflight only where a start could happen."
  [state now]
  (let [now (time/to-epoch-ms now)]
    (->> (vals (:schedules state))
         (filter (fn [schedule]
                   (and (= :enabled (:state schedule))
                        (nil? (active-run-of state schedule))
                        (or (and (some? (:next-run schedule))
                                 (<= (:next-run schedule) now))
                            (seq (due-pending-gaps schedule now))))))
         (map :id)
         sort
         vec)))

(defn create-schedule
  "Create a schedule.  When `:enable?` is true, save-and-enable semantics
  apply: an enable failure keeps the schedule saved as `:paused` and returns
  `:enabled? false` with the reason."
  [state spec now]
  (let [now (time/to-epoch-ms now)
        spec (or spec {})
        supplied-id (:id spec)
        [state id] (if (model/non-blank-string? supplied-id)
                     [state supplied-id]
                     (allocate-id state "schedule"))]
    (cond
      (and supplied-id (get-in state [:schedules supplied-id]))
      (model/error :DUPLICATE_SCHEDULE_ID "a schedule with this id already exists"
                   {:id supplied-id})

      :else
      (let [created (model/new-schedule
                     (assoc spec
                            :id id
                            :now now
                            :state (if (:enable? spec)
                                     :draft
                                     (or (:state spec) :draft))
                            :device-zone (:device-zone state)))
            schedule (:schedule created)
            state' (assoc-in state [:schedules id] schedule)]
        (cond
          (not (:ok? created))
          created

          (:enable? spec)
          (let [enabled (model/enable schedule now)]
            (if (:ok? enabled)
              {:ok? true
               :outcome :created
               :enabled? true
               :state (bump (assoc-in state' [:schedules id]
                                      (:schedule enabled)))
               :schedule (:schedule enabled)
               :preview (:preview enabled)}
              (let [paused (assoc schedule :state :paused :paused-at now)]
                {:ok? true
                 :outcome :created
                 :enabled? false
                 :state (bump (assoc-in state' [:schedules id] paused))
                 :schedule paused
                 :preview (:preview created)
                 :enable-error (:error enabled)})))

          :else
          {:ok? true
           :outcome :created
           :enabled? false
           :state (bump state')
           :schedule schedule
           :preview (:preview created)})))))

(defn update-schedule
  [state schedule-id changes now]
  (let [now (time/to-epoch-ms now)]
    (if-let [schedule (schedule-of state schedule-id)]
      (let [updated (model/update-schedule schedule changes now)]
        (if (:ok? updated)
          {:ok? true
           :outcome :updated
           :state (bump (assoc-in state [:schedules schedule-id]
                                  (:schedule updated)))
           :schedule (:schedule updated)
           :previous-preview (:previous-preview updated)
           :preview (:preview updated)}
          updated))
      (model/error :UNKNOWN_SCHEDULE "schedule does not exist"
                   {:schedule-id schedule-id}))))

(defn enable-schedule
  [state schedule-id now]
  (let [now (time/to-epoch-ms now)]
    (if-let [schedule (schedule-of state schedule-id)]
      (let [enabled (model/enable schedule now)]
        (if (:ok? enabled)
          {:ok? true
           :outcome (:outcome enabled)
           :state (bump (assoc-in state [:schedules schedule-id]
                                  (:schedule enabled)))
           :schedule (:schedule enabled)
           :preview (:preview enabled)}
          enabled))
      (model/error :UNKNOWN_SCHEDULE "schedule does not exist"
                   {:schedule-id schedule-id}))))

(defn pause-schedule
  [state schedule-id now]
  (let [now (time/to-epoch-ms now)]
    (if-let [schedule (schedule-of state schedule-id)]
      (let [paused (model/pause schedule now)]
        (if (:ok? paused)
          {:ok? true
           :outcome (:outcome paused)
           :state (bump (assoc-in state [:schedules schedule-id]
                                  (:schedule paused)))
           :schedule (:schedule paused)}
          paused))
      (model/error :UNKNOWN_SCHEDULE "schedule does not exist"
                   {:schedule-id schedule-id}))))

(defn- record-missed-window
  [state schedule from-ms now reason]
  (let [zone (get-in schedule [:timezone :zone])
        {:keys [runs gaps]} (time/occurrences-between
                             (:recurrence schedule) zone from-ms now)
        missed (sort-by occurrence-epoch (concat runs gaps))]
    (reduce (fn [[state recorded] occurrence]
              (let [[state run] (add-missed-run state schedule occurrence
                                               reason now)]
                [state (conj recorded run)]))
            [state []]
            missed)))

(defn- record-gaps
  [state schedule gaps reason now]
  (reduce (fn [[state recorded] occurrence]
            (let [[state run] (add-missed-run state schedule occurrence
                                             reason now)]
              [state (conj recorded run)]))
          [state []]
          gaps))

(defn resume-schedule
  "Resume a paused schedule.  Occurrences missed while paused become
  `:missed` facts with reason `:paused`; the next run is strictly future."
  [state schedule-id now]
  (let [now (time/to-epoch-ms now)]
    (if-let [schedule (schedule-of state schedule-id)]
      (cond
        (not= :paused (:state schedule))
        (model/error :INVALID_SCHEDULE_STATE "only a paused schedule can resume"
                     {:state (:state schedule)})

        (seq (:attention schedule))
        (model/error :NEEDS_ATTENTION
                     "schedule has unresolved attention issues; repair them before resuming"
                     {:issues (:attention schedule)})

        (not (model/snapshot-complete? schedule))
        (model/error :SCHEDULE_INCOMPLETE
                     "schedule is missing its frozen model or execution configuration"
                     {:missing [:snapshot]})

        :else
        (let [window (time/next-window (:recurrence schedule)
                                       (get-in schedule [:timezone :zone])
                                       now)
              next-run (:next-run window)]
          (if (nil? next-run)
            (model/error :NO_FUTURE_OCCURRENCE
                         "schedule recurrence has no future occurrence"
                         {:recurrence (:recurrence schedule)})
            (let [[state gap-runs] (record-gaps state schedule
                                                (due-pending-gaps schedule now)
                                                :paused now)
                  [state missed]
                  (if (and (:next-run schedule)
                           (<= (:next-run schedule) now))
                    (record-missed-window state schedule
                                          (:next-run schedule) now :paused)
                    [state []])
                  schedule' (assoc schedule
                                   :state :enabled
                                   :next-run next-run
                                   :pending-gaps (:pending-gaps window)
                                   :exhausted? false
                                   :paused-at nil
                                   :updated-at now)]
              {:ok? true
               :outcome :resumed
               :state (bump (assoc-in state [:schedules schedule-id]
                                      schedule'))
               :schedule schedule'
               :missed (into (vec gap-runs) missed)}))))
      (model/error :UNKNOWN_SCHEDULE "schedule does not exist"
                   {:schedule-id schedule-id}))))

(defn repair-schedule
  "Clear attention issues, optionally adopt explicit fixes, record the gap
  that accumulated while blocked, and enable the schedule again."
  [state schedule-id changes now]
  (let [now (time/to-epoch-ms now)
        schedule (schedule-of state schedule-id)]
    (if (nil? schedule)
      (model/error :UNKNOWN_SCHEDULE "schedule does not exist"
                   {:schedule-id schedule-id})
      (let [updated (if (seq changes)
                      (model/update-schedule schedule
                                             (assoc changes :clear-attention? true)
                                             now)
                      {:ok? true :schedule (assoc schedule :attention nil)})
            schedule (:schedule updated)]
        (if-not (:ok? updated)
          updated
          (if-not (contains? #{:needs-attention :paused} (:state schedule))
            (model/error :INVALID_SCHEDULE_STATE
                         "only a schedule that needs attention can be repaired"
                         {:state (:state schedule)})
            (if-not (model/snapshot-complete? schedule)
              (model/error :SCHEDULE_INCOMPLETE
                           "schedule is still missing its frozen model or execution configuration"
                           {:missing [:snapshot]})
              (let [window (time/next-window (:recurrence schedule)
                                             (get-in schedule [:timezone :zone])
                                             now)
                    next-run (:next-run window)]
                (if (nil? next-run)
                  (model/error :NO_FUTURE_OCCURRENCE
                               "schedule recurrence has no future occurrence"
                               {:recurrence (:recurrence schedule)})
                  (let [[state gap-runs] (record-gaps state schedule
                                                      (due-pending-gaps schedule now)
                                                      :needs-attention now)
                        [state missed] (if (and (:next-run schedule)
                                                (<= (:next-run schedule) now))
                                         (record-missed-window state schedule
                                                               (:next-run schedule) now
                                                               :needs-attention)
                                         [state []])
                        schedule' (assoc schedule
                                         :state :enabled
                                         :next-run next-run
                                         :pending-gaps (:pending-gaps window)
                                         :exhausted? false
                                         :attention nil
                                         :paused-at nil
                                         :updated-at now)]
                    {:ok? true
                     :outcome :repaired
                     :state (bump (assoc-in state [:schedules schedule-id]
                                            schedule'))
                     :schedule schedule'
                     :missed (into (vec gap-runs) missed)
                     :preview (:preview (model/preview schedule' now))})))))))))
)

(defn manual-run
  "Create an independent manual run.

  A manual run never changes `:next-run` and never enables a paused schedule.
  It still respects the single active run slot and current preflight."
  ([state schedule-id now preflight]
   (manual-run state schedule-id now preflight {}))
  ([state schedule-id now preflight {:keys [at original-scheduled-at
                                            data-mode attempt]
                                     :or {data-mode :current
                                          attempt 1}}]
   (let [now (time/to-epoch-ms now)
         at (or at now)]
     (if-let [schedule (schedule-of state schedule-id)]
       (let [zone (get-in schedule [:timezone :zone])]
         (if-not (time/valid-zone? zone)
           (model/error :INVALID_TIMEZONE
                        "schedule has no valid timezone"
                        {:schedule-id schedule-id :zone zone})
           (let [view (time/local-view at zone zone)
                 occurrence {:epoch-ms at
                             :local-date-time (:local-date-time view)
                             :zone (:zone view)
                             :ambiguous? false}]
             (if-let [active (active-run-of state schedule)]
               (let [[state run] (add-skipped-run state schedule occurrence
                                                  :previous-run-active now
                                                  :manual)
                     state (assoc-in state [:schedules schedule-id]
                                     (assoc schedule
                                            :last-run-id (:id run)
                                            :updated-at now))]
                 {:ok? true
                  :outcome :skipped
                  :started? false
                  :reason :previous-run-active
                  :active-run-id (:id active)
                  :state (bump state)
                  :run run})
           (cond
             (preflight-skip? preflight)
             (let [[state run] (add-skipped-run
                                state schedule occurrence
                                (or (:reason preflight) :preflight-skip)
                                now :manual)
                   state (assoc-in state [:schedules schedule-id]
                                   (assoc schedule
                                          :last-run-id (:id run)
                                          :updated-at now))]
               {:ok? true
                :outcome :skipped
                :started? false
                :reason (or (:reason preflight) :preflight-skip)
                :state (bump state)
                :run run})

             (preflight-ok? preflight)
             (let [[state run _]
                   (prepare-run state schedule occurrence :manual
                                {:original-scheduled-at original-scheduled-at
                                 :next-run-at-creation (:next-run schedule)
                                 :data-mode data-mode
                                 :attempt attempt}
                                now)]
               {:ok? true
                :outcome :started
                :started? true
                :state (bump state)
                :run run
                :schedule (get-in state [:schedules schedule-id])
                :effects [{:effect :start-run
                           :run run
                           :schedule (get-in state [:schedules schedule-id])}]})

             :else
             (let [[state run] (needs-attention-run
                                state schedule occurrence :manual
                                (preflight-issues preflight) now)]
               {:ok? true
                :outcome :needs-attention
                :started? false
                :reason :preflight-failed
                :issues (preflight-issues preflight)
                :state (bump state)
                :run run}))))))
       (model/error :UNKNOWN_SCHEDULE "schedule does not exist"
                    {:schedule-id schedule-id})))))

(defn mark-running
  [state run-id now]
  (let [now (time/to-epoch-ms now)]
    (if-let [run (run-of state run-id)]
      (if (= :preparing (:status run))
        (let [zone (:scheduled-zone run)
              run' (cond-> (assoc run :status :running :started-at now)
                     (and zone (time/valid-zone? zone))
                     (assoc :started-local
                            (:local-date-time
                             (time/local-view now zone zone))))]
          {:ok? true
           :outcome :running
           :state (bump (assoc-in state [:runs run-id] run'))
           :run run'})
        (model/error :RUN_NOT_PREPARING
                     "only a preparing run can start"
                     {:run-id run-id :status (:status run)}))
      (model/error :UNKNOWN_RUN "run does not exist" {:run-id run-id}))))

(defn attach-run
  "Attach host execution identity to a run without changing its status.

  Used by the app adapter after it creates the result session and submits the
  first command.  The run remains the single fact for that scheduled attempt;
  attaching the chat/owner/command ids does not create a second run."
  [state run-id attrs now]
  (let [now (time/to-epoch-ms now)]
    (if-let [run (run-of state run-id)]
      (let [run' (merge run
                        (select-keys attrs
                                     [:chat-id :owner-id :command-id
                                      :execution-target :result-chat-id
                                      :source-session-id :source-message-id
                                      :branch-session-id :redo-mode]))]
        {:ok? true
         :outcome :attached
         :state (bump (assoc-in state [:runs run-id] run'))
         :run run'})
      (model/error :UNKNOWN_RUN "run does not exist" {:run-id run-id}))))

(defn mark-start-failed
  [state run-id now error]
  (let [now (time/to-epoch-ms now)]
    (if-let [run (run-of state run-id)]
      (if (= :preparing (:status run))
        (let [run' (assoc run
                          :status :failed
                          :reason :start-dispatch-failed
                          :error error
                          :ended-at now)
              schedule (get-in state [:schedules (:schedule-id run)])
              state' (assoc-in state [:runs run-id] run')
              state' (if (and schedule
                              (= (:active-run-id schedule) run-id))
                       (assoc-in state' [:schedules (:schedule-id run)]
                                 (assoc schedule :active-run-id nil
                                        :updated-at now))
                       state')]
          {:ok? true
           :outcome :failed
           :state (bump state')
           :run run'})
        {:ok? true :outcome :ignored :state state :run run})
      (model/error :UNKNOWN_RUN "run does not exist" {:run-id run-id}))))

(defn finish-run
  "Record a host result for a run.

  Active terminal-adjacent statuses `:awaiting-user` and `:outcome-unknown`
  keep the run slot.  A terminal result clears it.  A timed-out run may be
  corrected to `:partial` or `:completed` when the host later learns how far
  it got.  Notification is a separate fact: a pending notification produces a
  `:notify` effect and never re-runs the task."
  ([state run-id result now]
   (finish-run state run-id result now {}))
  ([state run-id result now {:keys [notify?] :or {notify? true}}]
   (let [now (time/to-epoch-ms now)
         result (or result {})
         status (:status result)]
     (if-let [run (run-of state run-id)]
       (let [current (:status run)
             terminal? (contains? model/terminal-run-statuses current)
             same? (= current status)
             correction? (and (= :timed-out current)
                              (contains? #{:partial :completed} status))]
         (cond
           (not (contains? model/finish-statuses status))
           (model/error :INVALID_RUN_STATUS
                        "host cannot report this run status"
                        {:run-id run-id :status status})

           (and terminal? same?)
           {:ok? true :outcome :unchanged :state state :run run}

           (and terminal? (not correction?))
           (model/error :RUN_ALREADY_TERMINAL
                        "run already has a different terminal status"
                        {:run-id run-id
                         :current current
                         :incoming status})

           :else
           (let [active-result? (contains? #{:awaiting-user :outcome-unknown}
                                           status)
                 terminal-result? (contains? model/terminal-run-statuses
                                             status)
                 notify-worthy? (or terminal-result?
                                    (= :awaiting-user status))
                 notification (or (:notification result)
                                  (when (and notify-worthy?
                                             notify?
                                             (nil? (:notification run)))
                                    {:state :pending
                                     :attempts 1
                                     :queued-at now}))
                 run-base (cond-> (merge run
                                         (select-keys result
                                                      [:status :chat-id
                                                       :result-files :reason
                                                       :issues :result-summary
                                                       :usage :partial?]))
                            true (assoc :status status)
                            (contains? result :ended-at)
                            (assoc :ended-at (:ended-at result))
                            (and terminal-result?
                                 (not (contains? result :ended-at)))
                            (assoc :ended-at now)
                            active-result?
                            (assoc :ended-at nil)
                            correction?
                            (assoc :corrected? true :timeout? true)
                            (some? notification)
                            (assoc :notification notification))
                 run' (if (and terminal-result?
                               (number? (:ended-at run-base))
                               (:scheduled-zone run-base)
                               (time/valid-zone? (:scheduled-zone run-base)))
                        (assoc run-base
                               :ended-local
                               (:local-date-time
                                (time/local-view (:ended-at run-base)
                                                 (:scheduled-zone run-base)
                                                 (:scheduled-zone run-base)))
                               :ended-zone (:scheduled-zone run-base))
                        run-base)
                 schedule (get-in state [:schedules (:schedule-id run)])
                 state' (assoc-in state [:runs run-id] run')
                 state' (if (and terminal-result? schedule
                                 (= (:active-run-id schedule) run-id))
                          (assoc-in state' [:schedules (:schedule-id run)]
                                    (assoc schedule :active-run-id nil
                                           :updated-at now))
                          state')
                 state' (if (and terminal-result? schedule
                                 (= :needs-attention status))
                          (let [current-schedule
                                (get-in state' [:schedules (:schedule-id run)])]
                            (assoc-in state' [:schedules (:schedule-id run)]
                                      (assoc current-schedule
                                             :state (if (contains?
                                                         #{:enabled
                                                           :needs-attention}
                                                         (:state current-schedule))
                                                      :needs-attention
                                                      (:state current-schedule))
                                             :attention (or (:issues result)
                                                            (:attention current-schedule))
                                             :updated-at now)))
                          state')
                 notify-effect (when (and notify-worthy?
                                          (= :pending (:state notification)))
                                 {:effect :notify
                                  :run-id run-id
                                  :run (get-in state' [:runs run-id])
                                  :schedule (get-in state' [:schedules
                                                            (:schedule-id run)])})]
             (cond-> {:ok? true
                      :outcome (if correction? :corrected :finished)
                      :state (bump state')
                      :run (get-in state' [:runs run-id])}
               notify-effect (assoc :effects [notify-effect])))))
       (model/error :UNKNOWN_RUN "run does not exist" {:run-id run-id})))))

(defn retry-notification
  "Queue one more notification attempt for a terminal run.

  This never starts the task again.  An already-sent notification is not
  resent by this function."
  [state run-id now]
  (let [now (time/to-epoch-ms now)]
    (if-let [run (run-of state run-id)]
      (let [notification (or (:notification run)
                             {:state :pending :attempts 0})]
        (cond
          (not (or (contains? model/terminal-run-statuses (:status run))
                   (= :awaiting-user (:status run))))
          (model/error :RUN_NOT_TERMINAL
                       "notifications are retried only for a finished or waiting run"
                       {:run-id run-id :status (:status run)})

          (= :sent (:state notification))
          (model/error :NOTIFICATION_ALREADY_SENT
                       "the notification was already delivered"
                       {:run-id run-id})

          :else
          (let [notification' (assoc notification
                                     :state :pending
                                     :attempts (inc (long (or (:attempts notification)
                                                              0)))
                                     :last-attempt-at now)
                run' (assoc run :notification notification')
                state' (bump (assoc-in state [:runs run-id] run'))]
            {:ok? true
             :outcome :queued
             :state state'
             :run run'
             :effects [{:effect :notify
                        :run-id run-id
                        :run run'
                        :schedule (get-in state' [:schedules
                                                  (:schedule-id run)])}]})))
      (model/error :UNKNOWN_RUN "run does not exist" {:run-id run-id}))))

(defn mark-notification
  "Record the host's notification outcome.  This never re-runs the task."
  [state run-id outcome now]
  (let [now (time/to-epoch-ms now)]
    (if-let [run (run-of state run-id)]
      (let [notification (or (:notification run)
                             {:state :pending :attempts 0})
            state' (cond
                     (= :sent (:state outcome))
                     (assoc notification
                            :state :sent
                            :sent-at (or (:at outcome) now)
                            :last-error nil)

                     (= :failed (:state outcome))
                     (assoc notification
                            :state :failed
                            :last-error (or (:error outcome)
                                            {:code :NOTIFICATION_FAILED})
                            :failed-at (or (:at outcome) now))

                     :else notification)
            run' (assoc run :notification state')
            state' (bump (assoc-in state [:runs run-id] run'))]
        {:ok? true
         :outcome (:state outcome)
         :state state'
         :run run'})
      (model/error :UNKNOWN_RUN "run does not exist" {:run-id run-id}))))

(defn pending-notifications
  "Runs whose notification is pending or failed.

  A run waiting for the user still owns a notification; the task itself is
  not finished, but the reminder is retried independently."
  [state]
  (->> (all-runs state)
       (filter #(contains? #{:pending :failed}
                           (get-in % [:notification :state])))
       vec))

(defn delete-schedule
  "Remove a schedule.  Runs are kept by default; `:delete-runs?` removes
  them too.  An active run requires `:force?`, which records it stopped."
  [state schedule-id now opts]
  (let [now (time/to-epoch-ms now)
        {:keys [force? delete-runs?]} (or opts {})]
    (if-let [schedule (schedule-of state schedule-id)]
      (if-let [active (active-run-of state schedule)]
        (if-not force?
          (model/error :SCHEDULE_HAS_ACTIVE_RUN
                       "stop the active run or wait before deleting the schedule"
                       {:schedule-id schedule-id :run-id (:id active)})
          (let [run' (assoc active
                            :status :stopped
                            :reason :schedule-deleted
                            :ended-at now)
                state (assoc-in state [:runs (:id active)] run')
                state (assoc-in state [:schedules schedule-id]
                                (assoc schedule :active-run-id nil))
                state (update state :schedules dissoc schedule-id)
                state (if delete-runs?
                        (let [ids (set (map :id (runs-for state schedule-id)))]
                          (-> state
                              (update :runs #(apply dissoc % ids))
                              (update :run-order
                                      (fn [order]
                                        (into [] (remove ids) order)))))
                        state)]
            {:ok? true
             :outcome :deleted
             :state (bump state)
             :schedule schedule
             :stopped-run run'
             :effects [{:effect :stop-run
                        :run-id (:id active)
                        :reason :schedule-deleted}]}))
        (let [run-ids (set (map :id (runs-for state schedule-id)))
              state (update state :schedules dissoc schedule-id)
              state (if delete-runs?
                      (-> state
                          (update :runs #(apply dissoc % run-ids))
                          (update :run-order
                                  (fn [order]
                                    (into [] (remove run-ids) order))))
                      state)]
          {:ok? true
           :outcome :deleted
           :state (bump state)
           :schedule schedule}))
      (model/error :UNKNOWN_SCHEDULE "schedule does not exist"
                   {:schedule-id schedule-id}))))

(defn import-schedule
  "Copy or import a schedule as a paused, independent schedule.

  The source snapshot is preserved.  The imported schedule never inherits an
  enabled state or an active run."
  [state spec now]
  (let [now (time/to-epoch-ms now)
        [state id] (allocate-id state "schedule")
        spec (-> spec
                 (dissoc :id :active-run-id :last-run-id :created-at
                         :updated-at :paused-at :next-run :attention
                         :restored-from-backup? :restored-pending-review?)
                 (assoc :id id
                        :state :paused
                        :name (or (:name spec)
                                  (str "Imported schedule " id))
                        :now now
                        :device-zone (:device-zone state)))
        created (model/new-schedule spec)]
    (if (:ok? created)
      (let [schedule (:schedule created)
            state' (assoc-in state [:schedules id] schedule)]
        {:ok? true
         :outcome :imported
         :enabled? false
         :state (bump state')
         :schedule schedule
         :preview (:preview created)})
      created)))

(defn recover
  "Reconcile persisted state after a process restart.

  - A `:preparing` or `:running` run becomes `:interrupted`; it is not
    replayed, and no external write is repeated.
  - `:awaiting-user` and `:outcome-unknown` runs keep their slot.
  - Every enabled schedule whose next run passed records those occurrences as
    `:missed` facts and advances to a strictly future occurrence.  Restart
    never catches up.
  - Pending or failed notifications stay pending for an explicit retry.

  Calling `recover` twice is safe: the first call advances every schedule
  past NOW and terminalizes every interrupted run."
  ([state now]
   (recover state now {}))
  ([state now {:keys [reason] :or {reason :process-restart}}]
   (let [now (time/to-epoch-ms now)
         initial-state state
         ;; Runs that were still live before recovery terminalizes them.
         ;; A due occurrence that happened while one of them was running is
         ;; an overlap skip, not a process-restart miss.
         active-before (group-by :schedule-id
                                 (filter #(contains? #{:preparing :running}
                                                     (:status %))
                                         (all-runs state)))
         [state interrupted]
         (reduce
          (fn [[state ids] run]
            (if (contains? #{:preparing :running} (:status run))
              (let [run' (assoc run
                                :status :interrupted
                                :reason reason
                                :ended-at now
                                :interrupted-at now
                                :recovered-at now)
                    schedule (get-in state [:schedules (:schedule-id run)])
                    state' (assoc-in state [:runs (:id run)] run')
                    state' (if (and schedule
                                    (= (:active-run-id schedule) (:id run)))
                             (assoc-in state' [:schedules (:schedule-id run)]
                                       (assoc schedule :active-run-id nil
                                              :updated-at now))
                             state')]
                [state' (conj ids (:id run))])
              [state ids]))
          [state []]
          (all-runs state))
         state
         (reduce
          (fn [state schedule]
            (let [run-id (:active-run-id schedule)
                  run (when run-id (get-in state [:runs run-id]))]
              (if (and run-id
                       (or (nil? run)
                           (not (contains? model/active-run-statuses
                                           (:status run)))))
                (assoc-in state [:schedules (:id schedule)]
                          (assoc schedule :active-run-id nil))
                state)))
          state
          (vals (:schedules state)))
         [state missed]
         (reduce
          (fn [[state recorded] schedule]
            (let [zone (get-in schedule [:timezone :zone])
                  recurrence (:recurrence schedule)
                  next-due? (and (:next-run schedule)
                                 (<= (:next-run schedule) now))
                  gap-due? (seq (due-pending-gaps schedule now))]
              (if (and (= :enabled (:state schedule))
                       recurrence
                       (time/valid-zone? zone)
                       (or next-due? gap-due?))
                (let [[state gap-runs]
                      (record-gaps state schedule
                                   (due-pending-gaps schedule now)
                                   reason now)
                      window (if next-due?
                               (time/occurrences-between
                                recurrence zone (:next-run schedule) now)
                               {:runs [] :gaps []})
                      occurrences (sort-by occurrence-epoch
                                           (concat (:runs window)
                                                   (:gaps window)))
                      [state runs]
                      (reduce (fn [[state runs] occurrence]
                                (let [overlap
                                      (some (fn [run]
                                              ;; A seeded `:running` run may
                                              ;; carry no start timestamp; a
                                              ;; live run at recovery time
                                              ;; overlapped every due
                                              ;; occurrence before now.
                                              (let [started (or (:started-at run)
                                                                (:created-at run)
                                                                0)]
                                                (<= (long started)
                                                    (long (occurrence-epoch
                                                           occurrence)))))
                                            (get active-before (:id schedule)))
                                      [state run]
                                      (if overlap
                                        (add-skipped-run
                                         state schedule occurrence
                                         :previous-still-running now)
                                        (add-missed-run
                                         state schedule occurrence reason now))]
                                  [state (conj runs run)]))
                              [state []]
                              occurrences)
                      next-window (time/next-window recurrence zone now)
                      next-run (:next-run next-window)
                      schedule' (assoc schedule
                                       :next-run next-run
                                       :pending-gaps (:pending-gaps next-window)
                                       :exhausted? (nil? next-run)
                                       :updated-at now)]
                  [(assoc-in state [:schedules (:id schedule)] schedule')
                   (into recorded (concat gap-runs runs))])
                [state recorded])))
          [state []]
          (sort-by :id (vals (:schedules state))))
         changed? (not= (dissoc initial-state :revision)
                        (dissoc state :revision))]
     {:ok? true
      :state (if changed? (bump state) state)
      :interrupted interrupted
      :missed (filterv #(= :missed (:status %)) missed)
      :skipped (filterv #(= :skipped (:status %)) missed)
      :pending-notifications (pending-notifications state)})))

(defn set-device-zone
  "Follow a device or server timezone change.

  Fixed-timezone schedules keep their zone and next run.  Follow-device
  schedules adopt ZONE and recompute the next real occurrence.  Historical
  runs are untouched: each run keeps its recorded zone and local time."
  [state zone now]
  (let [now (time/to-epoch-ms now)]
    (if-not (time/valid-zone? zone)
      (model/error :INVALID_TIMEZONE "device zone is not a valid IANA zone id"
                   {:zone zone})
      (let [previous (:device-zone state)
            [state changes missed]
            (reduce
             (fn [[state changes missed] schedule]
               (if (= :follow-device (get-in schedule [:timezone :mode]))
                 (let [old-zone (get-in schedule [:timezone :zone])
                       recurrence (:recurrence schedule)
                       missed-reason (case (:state schedule)
                                       :enabled :timezone-change
                                       :paused :paused
                                       :needs-attention :needs-attention
                                       nil)
                       [state gap-runs]
                       (if (and missed-reason
                                recurrence
                                (time/valid-zone? old-zone))
                         (record-gaps state schedule
                                      (due-pending-gaps schedule now)
                                      missed-reason now)
                         [state []])
                       [state runs]
                       (if (and missed-reason
                                (:next-run schedule)
                                recurrence
                                (time/valid-zone? old-zone)
                                (<= (:next-run schedule) now))
                         (let [window (time/occurrences-between
                                       recurrence old-zone
                                       (:next-run schedule) now)
                               occurrences (sort-by
                                            occurrence-epoch
                                            (concat (:runs window)
                                                    (:gaps window)))]
                           (reduce (fn [[state runs] occurrence]
                                     (let [[state run] (add-missed-run
                                                        state schedule occurrence
                                                        missed-reason now)]
                                       [state (conj runs run)]))
                                   [state []]
                                   occurrences))
                         [state []])
                       restored-paused? (and (= :paused (:state schedule))
                                             (model/restored-schedule? schedule))
                       next-window (when (and recurrence
                                              (not restored-paused?))
                                     (time/next-window recurrence zone now))
                       next-run (when-not restored-paused?
                                  (:next-run next-window))
                       schedule' (cond-> (assoc schedule
                                                 :timezone
                                                 (assoc (:timezone schedule)
                                                        :zone zone)
                                                 :next-run next-run
                                                 :pending-gaps
                                                 (or (:pending-gaps next-window)
                                                     [])
                                                 :exhausted?
                                                 (if restored-paused?
                                                   false
                                                   (nil? next-run))
                                                 :updated-at now)
                                   (not= old-zone zone)
                                   (assoc :last-zone-change
                                          {:from old-zone
                                           :to zone
                                           :at now}))]
                   [(assoc-in state [:schedules (:id schedule)] schedule')
                    (if (or (not= old-zone zone)
                            (not= (:next-run schedule) next-run))
                      (conj changes {:schedule-id (:id schedule)
                                     :old-zone old-zone
                                     :new-zone zone
                                     :old-next-run (:next-run schedule)
                                     :new-next-run next-run})
                      changes)
                    (into missed (concat gap-runs runs))])
                 [state changes missed]))
             [state [] []]
             (sort-by :id (vals (:schedules state))))
            state (assoc state :device-zone zone)]
        {:ok? true
         :state (if (or (seq changes) (not= previous zone))
                  (bump state)
                  state)
         :previous-zone previous
         :device-zone zone
         :changes changes
         :missed missed}))))
