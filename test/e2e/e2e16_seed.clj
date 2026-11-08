(ns e2e16-seed
  "Seed driver for E2E-16 schedules/timezone.

  Writes the `scheduler` storage partition (and an empty `config` partition)
  through `hc.server.storage/restore-state!`, using the same document kinds
  `hc.server.scheduler.durable-store` expects:

      scheduler-root      id \"root\"        {:v :revision :seq :device-zone :run-order}
      scheduler-schedule  id <schedule-id>  schedule map
      scheduler-run       id <run-id>       run map

  Scenarios:

    main      follow-device + fixed schedules (paused/manual), saved paused
    recover   enabled missed schedule, enabled overlap schedule with a live
              run, paused resume-candidate schedule
    backup    one enabled schedule with a complete snapshot (backup source)
    restore   minimal empty scheduler state for the merge destination

  Run with:

      clojure -M:server -i test/e2e/e2e16_seed.clj \\
        --eval '(e2e16-seed/run \"<data-dir>\" \"<scenario>\" <now-ms>)'
  "
  (:require [clojure.data.json :as json]
            [hc.server.storage :as storage]))

(set! *warn-on-reflection* true)

(def ^:private schedule-id-follow "sched-follow")
(def ^:private schedule-id-fixed "sched-fixed")
(def ^:private schedule-id-manual "sched-manual")
(def ^:private schedule-id-missed "sched-missed")
(def ^:private schedule-id-overlap "sched-overlap")
(def ^:private schedule-id-resume "sched-resume")
(def ^:private schedule-id-backup "sched-backup")

(defn- snapshot
  []
  {:prompt "总结今天的会话与待办，并列出明天最重要的一件事。"
   :execution {:mode :new-chat}
   :model {:connection-id "mock"
           :model-entry-id "mock-fast"
           :display-name "Mock Fast"}
   ;; snapshot-complete? requires the :capability-scope key to be present;
   ;; an empty set means "no extra tools", not "unbounded".
   :capability-scope #{}})

(defn- schedule-doc
  "One `:scheduler-schedule` document.  NEXT-RUN-AT is an epoch-ms value or
  nil; PAUSED? decides :state/:paused-at."
  [{:keys [id name state next-run-at now zone timezone]}]
  {:id id
   :name name
   :prompt "总结今天的会话与待办，并列出明天最重要的一件事。"
   :state state
   :revision 1
   :created-at now
   :updated-at now
   :active-run-id nil
   :last-run-id nil
   :attention nil
   :paused-at (when (= :paused state) now)
   :snapshot (snapshot)
   :execution {:mode :new-chat}
   :recurrence {:kind :daily :time {:hour 8 :minute 30}}
   :timezone (or timezone
                 {:mode :fixed :zone zone})
   :next-run next-run-at
   :pending-gaps []
   :exhausted? false})

(defn- run-doc
  "One `:scheduler-run` document matching hc.compose.scheduler.core/make-run."
  [{:keys [id schedule-id status trigger scheduled-at started-at reason now]
    :or {trigger :scheduled status :running}}]
  {:id id
   :schedule-id schedule-id
   :trigger trigger
   :status status
   :revision 1
   :snapshot (snapshot)
   :execution {:mode :new-chat}
   :scheduled-at scheduled-at
   :scheduled-local nil
   :scheduled-zone "Asia/Shanghai"
   :scheduled-exists? true
   :ambiguous? false
   :original-scheduled-at nil
   :next-run-at-creation scheduled-at
   :data-mode :current
   :created-at now
   :started-at started-at
   :ended-at nil
   :recorded-at now
   :chat-id nil
   :result-files []
   :notification nil
   :reason reason
   :issues nil
   :manual? (= :manual trigger)
   :attempt 1})

(defn- partition
  [docs]
  {:version 1
   :meta nil
   :last-commit nil
   :docs (vec docs)
   :obligations []
   :blob-refs nil})

(defn- doc-entry
  [kind id doc]
  {:kind kind :id id :doc doc})

(defn- config-doc
  []
  {:revision 1
   :created-at "2026-01-01T00:00:00Z"
   :updated-at "2026-01-01T00:00:00Z"
   :assistants {}
   :connections []
   :connection-order []
   :role-bindings {}})

(defn- scheduler-export
  "Build the export map for one scenario.  SCHEDULES is a vector of schedule
  docs; RUNS a vector of run docs; DEVICE-ZONE the root zone."
  [{:keys [schedules runs device-zone]}]
  (let [root-doc {:kind :scheduler-root
                  :id "root"
                  :doc {:v 1
                        :revision 1
                        :seq (count runs)
                        :device-zone device-zone
                        :run-order (mapv :id runs)}}
        schedule-docs (mapv (fn [s]
                              {:kind :scheduler-schedule
                               :id (:id s)
                               :doc s})
                            schedules)
        run-docs (mapv (fn [r]
                         {:kind :scheduler-run
                          :id (:id r)
                          :doc r})
                       runs)
        scheduler-docs (into [root-doc]
                             (concat schedule-docs run-docs))
        config-partition {:version 1
                          :meta nil
                          :last-commit nil
                          :docs [{:kind :app-config
                                  :id "config"
                                  :doc (config-doc)}]
                          :obligations []
                          :blob-refs nil}
        sessions-partition {:version 1
                            :meta nil
                            :last-commit nil
                            :docs [{:kind :session-index
                                    :id "sessions"
                                    :doc {:id "sessions" :ids []}}]
                            :obligations []
                            :blob-refs nil}
        scheduler-partition {:version 1
                             :meta nil
                             :last-commit nil
                             :docs scheduler-docs
                             :obligations []
                             :blob-refs nil}]
    {:format-version 1
     :complete? true
     :store-id "e2e16-seed"
     :exported-at "2026-01-01T00:00:00Z"
     :partitions {"config" config-partition
                  "sessions" sessions-partition
                  "scheduler" scheduler-partition}}))

(defn- write-state!
  [data-dir export]
  (let [db-path (str data-dir "/db")
        store (storage/open-store {:path db-path
                                   :create? true
                                   :durability :sync})]
    (try
      (storage/restore-state! store export
                              {:quiescent? true
                               :migration-id (str "e2e16-seed-" (System/currentTimeMillis))})
      (finally
        (storage/close-store! store)))))

(defn scenario-schedules
  "Return {:schedules [...] :runs [...] :device-zone \"...\"} for SCENARIO."
  [scenario now]
  (case scenario
    :main
    {:device-zone "Asia/Shanghai"
     :schedules [(schedule-doc {:id schedule-id-follow
                                :name "E2E16 跟随设备 08:30"
                                :state :paused :next-run-at nil
                                :now now :zone "Asia/Shanghai"
                                :timezone {:mode :follow-device
                                           :zone "Asia/Shanghai"}})
                 (schedule-doc {:id schedule-id-fixed
                                :name "E2E16 固定上海 08:30"
                                :state :paused :next-run-at nil
                                :now now :zone "Asia/Shanghai"
                                :timezone {:mode :fixed :zone "Asia/Shanghai"}})
                 (schedule-doc {:id schedule-id-manual
                                :name "E2E16 手动运行"
                                :state :paused :next-run-at nil
                                :now now :zone "Asia/Shanghai"
                                :timezone {:mode :fixed :zone "Asia/Shanghai"}})]
     :runs []}

    :recover
    {:device-zone "Asia/Shanghai"
     :schedules [(schedule-doc {:id schedule-id-missed
                                :name "E2E16 错过测试"
                                :state :enabled :next-run-at (- now 90000000)
                                :now now :zone "Asia/Shanghai"})
                 (schedule-doc {:id schedule-id-overlap
                                :name "E2E16 重叠跳过"
                                :state :enabled
                                :next-run-at (- now 93600000)
                                :now now :zone "Asia/Shanghai"
                                :timezone {:mode :follow-device
                                           :zone "Asia/Shanghai"}})
                 ;; Paused with next-run already in the past (36h ago so at
                 ;; least two daily 08:30 occurrences always elapsed):
                 ;; recovery must not touch it; resume must record those
                 ;; occurrences as :missed facts with reason :paused.
                 (schedule-doc {:id schedule-id-resume
                                :name "E2E16 恢复暂停"
                                :state :paused :next-run-at (- now 129600000)
                                :now now :zone "Asia/Shanghai"
                                :timezone {:mode :follow-device
                                           :zone "Asia/Shanghai"}})]
     ;; next-run must sit before a *real* daily 08:30 occurrence inside the
     ;; [next-run, now] window.  Recovery scans occurrences strictly after
     ;; next-run up to now; a next-run of now-1h/now-2h only works before
     ;; ~09:30 local.  25h/26h back guarantees at least one (and for overlap
     ;; two: the first is a :skipped previous-still-running fact because the
     ;; live run's started-at 0 precedes every occurrence, the rest are
     ;; :missed :process-restart facts).
     :runs [(run-doc {:id "run-overlap"
                      :schedule-id schedule-id-overlap
                      :status :running :trigger :scheduled
                      :scheduled-at (- now 93600000)
                      :started-at 0 :reason nil :now now})]}

    :backup
    {:device-zone "Asia/Shanghai"
     :schedules [(schedule-doc {:id schedule-id-backup
                                :name "E2E16 备份源"
                                :state :enabled :next-run-at (+ now 86400000)
                                :now now :zone "Asia/Shanghai"})]
     :runs []}

    :restore
    {:device-zone "Asia/Shanghai"
     :schedules []
     :runs []}

    (throw (ex-info "unknown e2e16 scenario" {:scenario scenario}))))

(defn run
  "Seed one store.  Args: data-dir, scenario keyword, now-ms."
  [data-dir scenario now-ms]
  (let [scenario (keyword scenario)
        now (long now-ms)
        {:keys [schedules runs device-zone]}
        (scenario-schedules scenario now)
        export (scheduler-export {:schedules schedules
                                  :runs runs
                                  :device-zone device-zone
                                  :now now})]
    (write-state! data-dir export)
    (println
     (json/write-str
      {:ok true
       :scenario (name scenario)
       :schedules (mapv :id schedules)
       :runs (mapv :id runs)
       :deviceZone device-zone}))))
