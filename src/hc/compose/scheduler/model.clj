(ns hc.compose.scheduler.model
  "Schedule and run vocabulary, validation, snapshots, and previews.

  A schedule is a plain-data value.  It never contains a secret value or a
  live object, so the whole scheduler state can be persisted as EDN and
  reopened after a restart.

  Snapshot discipline (AGENTS.md §9.1–§9.2):

  - The schedule stores an explicit `:snapshot`.  Ambient project, profile,
    and model defaults never rewrite it.
  - Only `update-schedule` with an explicit `:snapshot` adopts a new
    configuration.  Prompt, execution, and output edits update the snapshot
    because those fields are the task body itself.
  - Secrets are rejected, not silently stripped.  Record credential names and
    scopes in `:credentials` instead."
  (:require [clojure.string :as str]
            [hc.compose.scheduler.time :as time]))

(def schedule-states
  #{:draft :enabled :paused :needs-attention})

(def creation-states
  #{:draft :paused})

(def run-statuses
  #{:preparing :running :awaiting-user :outcome-unknown
    :completed :partial :failed :stopped :timed-out
    :missed :skipped :needs-attention :interrupted})

(def active-run-statuses
  "Statuses that still own the schedule's single run slot."
  #{:preparing :running :awaiting-user :outcome-unknown})

(def terminal-run-statuses
  (disj run-statuses :preparing :running :awaiting-user :outcome-unknown))

(def finish-statuses
  "Statuses a host may report through `finish-run`."
  #{:completed :partial :failed :stopped :timed-out
    :awaiting-user :outcome-unknown :needs-attention :interrupted})

(def trigger-kinds #{:scheduled :manual})

(def secret-keys
  #{:api-key :apikey :api_key :secret :client-secret :token :access-token
    :refresh-token :password :passphrase :authorization :bearer
    :credential-value :private-key})

(defn error
  ([code message]
   (error code message nil))
  ([code message details]
   {:ok? false
    :error {:code code
            :message message
            :details details}}))

(defn text-length
  [value]
  (if (string? value)
    (.codePointCount ^String value 0 (.length ^String value))
    0))

(defn non-blank-string?
  [value]
  (and (string? value)
       (not (str/blank? value))))

(defn- plain-key?
  [key]
  (or (keyword? key) (string? key) (symbol? key)))

(defn plain-data?
  "True when VALUE can be written as EDN and read back without a custom
  reader.  This is the persistence boundary for schedules and runs."
  [value]
  (cond
    (nil? value) true
    (or (boolean? value) (number? value) (string? value) (keyword? value)
        (symbol? value))
    true
    (map? value) (and (every? plain-key? (keys value))
                      (every? plain-data? (vals value)))
    (or (vector? value) (set? value) (list? value))
    (every? plain-data? value)
    :else false))

(defn- find-secret-key
  [value]
  (cond
    (map? value)
    (some (fn [[k v]]
            (or (when (contains? secret-keys
                                 (if (keyword? k)
                                   (keyword (str/lower-case (name k)))
                                   k))
                  k)
                (find-secret-key v)))
          value)

    (or (vector? value) (set? value) (list? value))
    (some find-secret-key value)

    :else nil))

(defn validate-snapshot
  "Validate an explicit task snapshot.

  The snapshot is opaque configuration except for the model identity needed
  to prove that a schedule can run.  Secret keys anywhere in the tree are
  rejected.  A snapshot may be absent while the schedule is saved paused."
  [snapshot]
  (cond
    (nil? snapshot)
    {:ok? true :snapshot nil}

    (not (map? snapshot))
    (error :INVALID_SNAPSHOT "a task snapshot must be a map"
           {:snapshot snapshot})

    (not (plain-data? snapshot))
    (error :INVALID_SNAPSHOT
           "a task snapshot must contain only plain EDN data"
           {:snapshot snapshot})

    (some? (find-secret-key snapshot))
    (error :SECRET_IN_SNAPSHOT
           "a task snapshot must not contain secret values; record credential names instead"
           {:key (find-secret-key snapshot)})

    :else
    {:ok? true :snapshot snapshot}))

(defn normalize-timezone
  "Normalize a timezone declaration.

  `{:mode :fixed :zone \"Asia/Shanghai\"}` keeps its zone for every run.
  `{:mode :follow-device :zone \"...\"}` uses the supplied device zone and
  follows later device changes.  A bare string is treated as fixed."
  [timezone default-zone]
  (let [timezone
        (cond
          (nil? timezone) {:mode :follow-device :zone default-zone}
          (string? timezone) {:mode :fixed :zone timezone}
          (map? timezone) timezone
          :else ::invalid)
        mode (if (map? timezone)
               (or (:mode timezone)
                   (if (contains? timezone :zone) :fixed :follow-device))
               nil)
        zone (or (:zone timezone)
                 (when (= :follow-device mode) default-zone))]
    (cond
      (= ::invalid timezone)
      (error :INVALID_TIMEZONE "a timezone must be a zone id or a map"
             {:timezone timezone})

      (not (contains? #{:fixed :follow-device} mode))
      (error :INVALID_TIMEZONE "timezone :mode must be :fixed or :follow-device"
             {:timezone timezone})

      (not (time/valid-zone? zone))
      (error :INVALID_TIMEZONE "timezone needs a valid IANA zone id"
             {:timezone timezone :zone zone})

      :else
      {:ok? true :timezone {:mode mode :zone zone}})))

(defn normalize-recurrence
  "Validate and normalize a recurrence rule."
  [recurrence]
  (if-not (map? recurrence)
    (error :INVALID_RECURRENCE "a recurrence must be a map"
           {:recurrence recurrence})
    (let [kind (:kind recurrence)
          time-result (time/parse-local-time (:time recurrence))
          starts-on (:starts-on recurrence)]
      (cond
        (not (contains? #{:once :daily :weekdays :weekly} kind))
        (error :INVALID_RECURRENCE
               "recurrence :kind must be :once, :daily, :weekdays, or :weekly"
               {:recurrence recurrence})

        (not (:ok? time-result))
        time-result

        (and starts-on (not (time/valid-local-date? starts-on)))
        (error :INVALID_RECURRENCE "recurrence :starts-on must be a local date"
               {:starts-on starts-on})

        (and (= :once kind)
             (not (time/valid-local-date? (:local-date recurrence))))
        (error :INVALID_RECURRENCE
               "a :once recurrence needs a valid :local-date"
               {:local-date (:local-date recurrence)})

        (= :weekly kind)
        (let [days (:days recurrence)]
          (if (and (sequential? days)
                   (seq days)
                   (every? #(and (integer? %) (<= 1 % 7)) days))
            {:ok? true
             :recurrence (cond-> {:kind kind
                                  :time (:time time-result)
                                  :days (vec (sort (distinct days)))}
                           starts-on (assoc :starts-on starts-on))}
            (error :INVALID_RECURRENCE
                   "a :weekly recurrence needs :days with ISO day numbers 1-7"
                   {:days days})))

        :else
        {:ok? true
         :recurrence (cond-> {:kind kind
                              :time (:time time-result)}
                       (= :once kind)
                       (assoc :local-date (:local-date recurrence))
                       starts-on
                       (assoc :starts-on starts-on))}))))

(defn normalize-execution
  "Validate an execution target.

  `:new-chat` is the default.  `:continue-chat` needs `:chat-id`.
  `:redo-question` needs `:message-id` and may carry `:chat-id`."
  [execution]
  (let [execution (or execution {:mode :new-chat})]
    (if-not (map? execution)
      (error :INVALID_EXECUTION "execution must be a map"
             {:execution execution})
      (case (:mode execution)
        :new-chat
        (if (plain-data? execution)
          {:ok? true :execution {:mode :new-chat}}
          (error :INVALID_EXECUTION "execution must contain plain data"
                 {:execution execution}))

        :continue-chat
        (if (and (non-blank-string? (:chat-id execution))
                 (plain-data? execution))
          {:ok? true
           :execution {:mode :continue-chat :chat-id (:chat-id execution)}}
          (error :INVALID_EXECUTION
                 ":continue-chat needs a non-blank :chat-id"
                 {:execution execution}))

        :redo-question
        (let [redo-mode (or (:redo-mode execution) :branch)]
          (if (and (non-blank-string? (:message-id execution))
                   (contains? #{:branch :append-version} redo-mode)
                   (or (nil? (:chat-id execution))
                       (non-blank-string? (:chat-id execution)))
                   (plain-data? execution))
            {:ok? true
             :execution (cond-> {:mode :redo-question
                                 :message-id (:message-id execution)
                                 :redo-mode redo-mode}
                          (:chat-id execution) (assoc :chat-id (:chat-id execution)))}
            (error :INVALID_EXECUTION
                   ":redo-question needs a non-blank :message-id and :redo-mode :branch or :append-version"
                   {:execution execution})))

        (error :INVALID_EXECUTION
               "execution :mode must be :new-chat, :continue-chat, or :redo-question"
               {:execution execution})))))

(defn target-chat-execution?
  "True when the execution mode inherits its model from the target chat.

  `:continue-chat` and `:redo-question` use the target session's saved model
  binding (AGENTS.md §6.9-4), so a schedule snapshot does not have to freeze a
  second model binding.  `:new-chat` still needs a complete frozen model."
  [schedule]
  (let [execution (or (:execution schedule)
                      (get-in schedule [:snapshot :execution]))]
    (contains? #{:continue-chat :redo-question} (:mode execution))))

(defn snapshot-complete?
  "True when a schedule has the frozen configuration needed to start work."
  [schedule]
  (let [snapshot (:snapshot schedule)
        model (:model snapshot)]
    (and (map? snapshot)
         (non-blank-string? (:prompt snapshot))
         (map? (:execution snapshot))
         (plain-data? snapshot)
         (or (target-chat-execution? schedule)
             (and (map? model)
                  (non-blank-string? (:connection-id model))
                  (or (non-blank-string? (:model-entry-id model))
                      (non-blank-string? (:model-id model)))
                  ;; The enable-time tool ceiling must be frozen in the
                  ;; snapshot; a later assistant change cannot widen it.
                  (contains? snapshot :capability-scope))))))

(defn- merge-snapshot
  "Fold the editable task body into the frozen snapshot."
  [snapshot prompt execution output output?]
  (cond-> (merge (or snapshot {})
                 {:prompt prompt
                  :execution execution})
    output? (assoc :output output)))

(defn- compute-next-window
  [schedule now]
  (when (and (:recurrence schedule)
             (get-in schedule [:timezone :zone]))
    (time/next-window (:recurrence schedule)
                      (get-in schedule [:timezone :zone])
                      now)))

(defn preview
  "Return the next COUNT real occurrences and the zone that owns them.

  The preview is computed from the schedule's recurrence and timezone.  It
  does not enable anything and does not mutate the schedule."
  ([schedule now]
   (preview schedule now {}))
  ([schedule now {:keys [count device-zone]
                  :or {count 3}}]
   (let [recurrence (:recurrence schedule)
         timezone (:timezone schedule)
         zone (:zone timezone)]
     (cond
       (nil? recurrence)
       (error :INVALID_SCHEDULE "schedule has no recurrence")

       (not (time/valid-zone? zone))
       (error :INVALID_TIMEZONE "schedule has no valid timezone")

       :else
       (let [{:keys [times gaps exhausted?]}
             (time/preview-times recurrence zone now count)
             view (fn [occurrence]
                    (cond-> occurrence
                      (and device-zone
                           (not= device-zone zone)
                           (:epoch-ms occurrence))
                      (merge (select-keys
                              (time/local-view (:epoch-ms occurrence)
                                               zone device-zone)
                              [:device-zone :device-local-date-time
                               :device-offset :device-zdt]))))]
         {:ok? true
          :preview {:zone zone
                    :timezone-mode (:mode timezone)
                    :rule recurrence
                    :readable (str (time/describe-recurrence recurrence)
                                   " (" zone ")")
                    :times (mapv view times)
                    :gaps (mapv view gaps)
                    :exhausted? (boolean exhausted?)
                    :next-run (:epoch-ms (first times))}})))))

(defn new-schedule
  "Build a new draft or paused schedule.

  Required: non-blank `:id`, `:name` (at most 80 code points), `:prompt`.
  `:recurrence` and `:timezone` are required to compute a preview; without
  them the schedule is saved but cannot be enabled.  `:snapshot` may be
  absent while saved paused.  The returned state is never `:enabled`;
  enabling is a separate explicit action."
  [{:keys [id name prompt snapshot execution output recurrence timezone
           state created-at now device-zone]
    :or {state :draft}
    :as spec}]
  (let [now (time/to-epoch-ms now)
        device-zone (or device-zone (time/system-zone))
        output (if (contains? spec :output)
                 output
                 (:output snapshot))
        recurrence-result (when recurrence (normalize-recurrence recurrence))
        timezone-result (normalize-timezone timezone device-zone)
        execution-result (normalize-execution execution)
        snapshot-result (validate-snapshot snapshot)]
    (cond
      (not (non-blank-string? id))
      (error :INVALID_SCHEDULE "a schedule needs a non-blank string :id"
             {:id id})

      (not (non-blank-string? name))
      (error :INVALID_SCHEDULE "a schedule needs a non-blank :name"
             {:name name})

      (> (text-length name) 80)
      (error :INVALID_SCHEDULE "a schedule :name must not exceed 80 characters"
             {:name name :length (text-length name)})

      (not (non-blank-string? prompt))
      (error :INVALID_SCHEDULE "a schedule needs a non-blank :prompt"
             {:prompt prompt})

      (nil? recurrence)
      (error :INVALID_RECURRENCE "a schedule needs a recurrence rule"
             {:recurrence recurrence})

      (not (contains? creation-states state))
      (error :INVALID_SCHEDULE
             "a new schedule is saved as :draft or :paused; enabling is explicit"
             {:state state})

      (and recurrence-result (not (:ok? recurrence-result)))
      recurrence-result

      (not (:ok? timezone-result))
      timezone-result

      (not (:ok? execution-result))
      execution-result

      (not (:ok? snapshot-result))
      snapshot-result

      (and output (not (plain-data? output)))
      (error :INVALID_SCHEDULE "schedule :output must contain plain data"
             {:output output})

      :else
      (let [schedule
            (cond-> {:id id
                     :name name
                     :prompt prompt
                     :snapshot (merge-snapshot
                                (:snapshot snapshot-result)
                                prompt
                                (:execution execution-result)
                                output
                                (or (contains? (or snapshot {}) :output)
                                    (some? output)))
                     :execution (:execution execution-result)
                     :recurrence (when recurrence
                                   (:recurrence recurrence-result))
                     :timezone (:timezone timezone-result)
                     :state state
                     :revision 1
                     :created-at now
                     :updated-at now
                     :active-run-id nil
                     :last-run-id nil
                     :attention nil
                     :paused-at (when (= :paused state) now)}
              output (assoc :output output))
            window (compute-next-window schedule now)
            schedule (assoc schedule
                            :next-run (:next-run window)
                            :pending-gaps (:pending-gaps window)
                            :exhausted? (:exhausted? window))
            preview-result (when (:recurrence schedule)
                             (preview schedule now {:device-zone device-zone}))]
        {:ok? true
         :schedule schedule
         :preview (:preview preview-result)}))))

(defn enable
  "Explicitly enable a saved schedule.

  Rejects an incomplete snapshot, blank prompt, exhausted one-shot rule, or a
  schedule still carrying unresolved attention issues.  Recomputes the next
  real occurrence strictly after NOW; enabling never fires immediately."
  [schedule now]
  (let [now (time/to-epoch-ms now)]
    (cond
      (nil? schedule)
      (error :UNKNOWN_SCHEDULE "schedule does not exist")

      (= :enabled (:state schedule))
      {:ok? true :outcome :already-enabled :schedule schedule}

      (not (contains? #{:draft :paused :needs-attention} (:state schedule)))
      (error :INVALID_SCHEDULE_STATE "schedule cannot be enabled from this state"
             {:state (:state schedule)})

      (not (non-blank-string? (:prompt schedule)))
      (error :SCHEDULE_INCOMPLETE "a blank prompt cannot be enabled"
             {:missing [:prompt]})

      (not (snapshot-complete? schedule))
      (error :SCHEDULE_INCOMPLETE
             "schedule is missing its frozen model or execution configuration"
             {:missing [:snapshot]})

      (seq (:attention schedule))
      (error :NEEDS_ATTENTION
             "schedule has unresolved attention issues; repair them before enabling"
             {:issues (:attention schedule)})

      :else
      (let [window (compute-next-window schedule now)
            next-run (:next-run window)]
        (if (nil? next-run)
          (error :NO_FUTURE_OCCURRENCE
                 "schedule recurrence has no future occurrence"
                 {:recurrence (:recurrence schedule)
                  :timezone (:timezone schedule)})
          {:ok? true
           :outcome :enabled
           :schedule (assoc schedule
                            :state :enabled
                            :next-run next-run
                            :pending-gaps (:pending-gaps window)
                            :exhausted? false
                            :attention nil
                            :paused-at nil
                            :updated-at now)
           :preview (:preview (preview schedule now))})))))

(defn pause
  "Stop future automatic starts.  A running item is untouched."
  [schedule now]
  (let [now (time/to-epoch-ms now)]
    (cond
      (nil? schedule)
      (error :UNKNOWN_SCHEDULE "schedule does not exist")

      (= :paused (:state schedule))
      {:ok? true :outcome :already-paused :schedule schedule}

      (not (contains? #{:enabled :needs-attention} (:state schedule)))
      (error :INVALID_SCHEDULE_STATE "only a saved active schedule can be paused"
             {:state (:state schedule)})

      :else
      {:ok? true
       :outcome :paused
       :schedule (assoc schedule
                        :state :paused
                        :paused-at now
                        :updated-at now)})))

(defn restored-schedule?
  "True when a schedule carries the backup restore markers.

  The backup adapter marks imported schedules; the scheduler must enforce the
  no-implicit-authority rule even if a storage adapter forgot one of the
  pause fields."
  [schedule]
  (or (true? (:restored-from-backup? schedule))
      (true? (:restored-pending-review? schedule))))

(defn normalize-restored-schedules
  "Force backup-restored schedules into the paused state.

  A restore must never revive automatic execution (AGENTS.md §10.6).  The
  backup adapter already marks imported schedules, but this is the scheduler's
  own fence: clear the next run, pending DST gaps, attention issues, and any
  active run slot.  Existing local schedules without restore markers are
  untouched.  The operation is idempotent."
  [state now]
  (let [now (time/to-epoch-ms now)
        changed? (atom false)]
    (let [state'
          (reduce-kv
           (fn [state schedule-id schedule]
             (if (restored-schedule? schedule)
               (let [already-paused? (and (= :paused (:state schedule))
                                          (nil? (:next-run schedule))
                                          (empty? (:pending-gaps schedule))
                                          (nil? (:attention schedule))
                                          (nil? (:active-run-id schedule)))
                     schedule' (cond-> (-> schedule
                                           (assoc :state :paused
                                                  :next-run nil
                                                  :pending-gaps []
                                                  :active-run-id nil
                                                  :attention nil
                                                  :paused-at (or (:paused-at schedule) now)
                                                  :exhausted? false)
                                           (dissoc :enabled? :paused?))
                                 (not already-paused?)
                                 (update :revision (fnil inc 0)))]
                 (when (not= schedule schedule')
                   (reset! changed? true))
                 (assoc-in state [:schedules schedule-id] schedule'))
               state))
           state
           (:schedules state))]
      (cond-> state'
        @changed? (update :revision (fnil inc 0))))))

(defn update-schedule
  "Apply an explicit schedule edit.

  Only the listed fields change.  `:snapshot` is adopted only when supplied;
  ambient defaults never leak in.  Changing recurrence or timezone recomputes
  the next occurrence strictly after NOW.  An enabled schedule cannot be left
  without a future occurrence."
  [schedule changes now]
  (let [now (time/to-epoch-ms now)
        changes (or changes {})
        name (if (contains? changes :name) (:name changes) (:name schedule))
        prompt (if (contains? changes :prompt)
                 (:prompt changes)
                 (:prompt schedule))
        recurrence-input (if (contains? changes :recurrence)
                           (:recurrence changes)
                           (:recurrence schedule))
        timezone-input (if (contains? changes :timezone)
                         (:timezone changes)
                         (:timezone schedule))
        execution-input (if (contains? changes :execution)
                          (:execution changes)
                          (:execution schedule))
        output (if (contains? changes :output)
                 (:output changes)
                 (:output schedule))
        recurrence-result (when recurrence-input
                            (normalize-recurrence recurrence-input))
        timezone-result (normalize-timezone timezone-input
                                            (get-in schedule [:timezone :zone]))
        execution-result (normalize-execution execution-input)
        snapshot-result (if (contains? changes :snapshot)
                          (validate-snapshot (:snapshot changes))
                          {:ok? true :snapshot (:snapshot schedule)})]
    (cond
      (not (non-blank-string? name))
      (error :INVALID_SCHEDULE "a schedule needs a non-blank :name"
             {:name name})

      (> (text-length name) 80)
      (error :INVALID_SCHEDULE "a schedule :name must not exceed 80 characters"
             {:name name :length (text-length name)})

      (not (non-blank-string? prompt))
      (error :INVALID_SCHEDULE "a schedule needs a non-blank :prompt"
             {:prompt prompt})

      (and recurrence-result (not (:ok? recurrence-result)))
      recurrence-result

      (not (:ok? timezone-result))
      timezone-result

      (not (:ok? execution-result))
      execution-result

      (not (:ok? snapshot-result))
      snapshot-result

      (and output (not (plain-data? output)))
      (error :INVALID_SCHEDULE "schedule :output must contain plain data"
             {:output output})

      :else
      (let [updated (cond-> (assoc schedule
                                    :name name
                                    :prompt prompt
                                    :execution (:execution execution-result)
                                    :recurrence (when recurrence-input
                                                  (:recurrence recurrence-result))
                                    :timezone (:timezone timezone-result)
                                    :revision (inc (long (or (:revision schedule) 0)))
                                    :updated-at now)
                      (contains? changes :output)
                      (assoc :output output)
                      (:clear-attention? changes)
                      (assoc :attention nil))
            updated (assoc updated
                           :snapshot (merge-snapshot
                                      (:snapshot snapshot-result)
                                      (:prompt updated)
                                      (:execution updated)
                                      (:output updated)
                                      (or (contains? changes :output)
                                          (contains? (:snapshot snapshot-result)
                                                     :output)
                                          (contains? (:snapshot schedule)
                                                     :output))))
            recompute? (or (contains? changes :recurrence)
                           (contains? changes :timezone))
            window (when recompute? (compute-next-window updated now))
            next-run (if recompute?
                       (:next-run window)
                       (:next-run updated))]
        (cond
          (and (= :enabled (:state updated))
               recompute?
               (nil? next-run))
          (error :NO_FUTURE_OCCURRENCE
                 "schedule recurrence has no future occurrence"
                 {:recurrence (:recurrence updated)
                  :timezone (:timezone updated)})

          (and (= :enabled (:state updated))
               (not (snapshot-complete? updated)))
          (error :SCHEDULE_INCOMPLETE
                 "an enabled schedule cannot adopt an incomplete snapshot"
                 {:missing [:snapshot]})

          :else
          (let [previous-preview (when (and recompute?
                                            (:recurrence schedule))
                                   (:preview (preview schedule now)))
                updated (cond-> (assoc updated :next-run next-run)
                          window (assoc :pending-gaps (:pending-gaps window)
                                        :exhausted? false))]
            {:ok? true
             :outcome :updated
             :schedule updated
             :previous-preview previous-preview
             :preview (when (:recurrence updated)
                        (:preview (preview updated now)))}))))))
