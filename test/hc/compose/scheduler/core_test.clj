(ns hc.compose.scheduler.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.scheduler.core :as core]
            [hc.compose.scheduler.model :as model]
            [hc.compose.scheduler.support :as support]))

(def shanghai "Asia/Shanghai")

(defn- now
  [local]
  (support/epoch shanghai local))

(defn- created-enabled
  [spec now-ms]
  (let [result (core/create-schedule (core/init-state shanghai)
                                     (assoc spec :enable? true)
                                     now-ms)]
    (is (:ok? result) (pr-str result))
    [(:state result) (get-in result [:schedule :id])]))

(defn- run-statuses
  [state schedule-id]
  (mapv (juxt :status :reason :trigger)
        (core/runs-for state schedule-id)))

(defn- active-runs
  [state schedule-id]
  (filterv #(contains? model/active-run-statuses (:status %))
           (core/runs-for state schedule-id)))

(deftest tick-starts-one-run-and-advances-next
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        due (now "2026-11-04T08:30:10")
        tick (core/tick state due {})]
    (is (:ok? tick))
    (is (= 1 (count (:started tick))))
    (is (= :preparing (get-in tick [:started 0 :status])))
    (is (= :start-run (get-in tick [:effects 0 :effect])))
    (is (= (get-in tick [:started 0])
           (get-in tick [:effects 0 :run])))
    (is (= (get-in tick [:started 0 :id])
           (get-in tick [:effects 0 :schedule :active-run-id])))
    (is (= (get-in tick [:started 0 :id])
           (get-in tick [:state :schedules id :active-run-id])))
    (is (= (now "2026-11-09T08:30")
           (get-in tick [:state :schedules id :next-run])))
    (let [again (core/tick (:state tick) due {})]
      (is (empty? (:started again)))
      (is (= 1 (count (core/runs-for (:state again) id)))))))

(deftest finish-run-clears-the-slot-and-queues-notification
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        due (now "2026-11-04T08:30:10")
        tick (core/tick state due {})
        run-id (get-in tick [:started 0 :id])
        running (core/mark-running (:state tick) run-id due)
        finished (core/finish-run (:state running) run-id
                                  {:status :completed
                                   :chat-id "chat-1"
                                   :result-files ["report.md"]}
                                  (now "2026-11-04T08:35"))]
    (is (= :running (get-in running [:run :status])))
    (is (= "2026-11-04T08:30:10" (get-in running [:run :started-local])))
    (is (:ok? finished))
    (is (= :completed (get-in finished [:run :status])))
    (is (= (now "2026-11-04T08:35") (get-in finished [:run :ended-at])))
    (is (= "2026-11-04T08:35" (get-in finished [:run :ended-local])))
    (is (= shanghai (get-in finished [:run :ended-zone])))
    (is (= "chat-1" (get-in finished [:run :chat-id])))
    (is (= ["report.md"] (get-in finished [:run :result-files])))
    (is (= :pending (get-in finished [:run :notification :state])))
    (is (= :notify (get-in finished [:effects 0 :effect])))
    (is (nil? (get-in finished [:state :schedules id :active-run-id])))
    (is (= [run-id] (mapv :id (core/pending-notifications
                               (:state finished)))))))

(deftest missed-occurrences-are-facts-not-runs
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        first (core/tick state (now "2026-11-04T09:31") {})
        second (core/tick (:state first) (now "2026-11-09T09:31") {})]
    (is (empty? (:started first)))
    (is (empty? (:started second)))
    (is (empty? (:effects first)))
    (is (empty? (:effects second)))
    (is (= [[:missed :missed :scheduled]]
           (run-statuses (:state first) id)))
    (is (= [[:missed :missed :scheduled]
            [:missed :missed :scheduled]]
           (run-statuses (:state second) id)))
    (is (= (now "2026-11-11T08:30")
           (get-in second [:state :schedules id :next-run])))))

(deftest manual-run-is-independent-and-does-not-change-next-run
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        tick (core/tick state (now "2026-11-04T09:31") {})
        before (:next-run (get-in tick [:state :schedules id]))
        manual (core/manual-run (:state tick) id
                                (now "2026-11-04T10:00")
                                {:ok? true}
                                {:at (now "2026-11-04T10:00")
                                 :original-scheduled-at (now "2026-11-04T08:30")})]
    (is (:ok? manual))
    (is (:started? manual))
    (is (= :manual (get-in manual [:run :trigger])))
    (is (= :current (get-in manual [:run :data-mode])))
    (is (= (now "2026-11-04T08:30")
           (get-in manual [:run :original-scheduled-at])))
    (is (= before (get-in manual [:state :schedules id :next-run])))
    (is (= :enabled (get-in manual [:state :schedules id :state])))
    (is (= 2 (count (core/runs-for (:state manual) id))))))

(deftest overlap-skips-and-never-queues
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        first-tick (core/tick state (now "2026-11-04T08:30:10") {})
        run-id (get-in first-tick [:started 0 :id])
        running (core/mark-running (:state first-tick) run-id
                                   (now "2026-11-04T08:30:10"))
        overlap (core/tick (:state running) (now "2026-11-09T08:30:10") {}
                           {:timeout-ms 1800000000})
        skipped (first (:skipped overlap))]
    (is (empty? (:started overlap)))
    (is (empty? (:effects overlap)))
    (is (= :skipped (:status skipped)))
    (is (= :previous-run-active (:reason skipped)))
    (is (= (now "2026-11-11T08:30")
           (get-in overlap [:state :schedules id :next-run])))
    (is (= 1 (count (active-runs (:state overlap) id))))
    (let [after-finish (core/finish-run (:state overlap) run-id
                                        {:status :completed}
                                        (now "2026-11-09T09:00"))
          next-tick (core/tick (:state after-finish)
                               (now "2026-11-11T08:30:10") {}
                               {:timeout-ms 1800000000})]
      (is (= 1 (count (:started next-tick)))))))

(deftest pause-stops-future-starts-and-resume-records-the-gap
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        paused (core/pause-schedule state id (now "2026-11-03T10:00"))
        tick (core/tick (:state paused) (now "2026-11-04T09:31") {})
        resumed (core/resume-schedule (:state paused) id
                                      (now "2026-11-04T10:00"))]
    (is (:ok? paused))
    (is (= :paused (get-in paused [:schedule :state])))
    (is (empty? (:started tick)))
    (is (empty? (:effects tick)))
    (is (empty? (core/runs-for (:state tick) id)))
    (is (:ok? resumed))
    (is (= :enabled (get-in resumed [:schedule :state])))
    (is (= [[:missed :paused :scheduled]]
           (mapv (juxt :status :reason :trigger) (:missed resumed))))
    (is (= (now "2026-11-09T08:30")
           (get-in resumed [:schedule :next-run])))))

(deftest preflight-failure-needs-attention-and-repair-records-gap
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        issue {:code :MISSING_CREDENTIAL
               :message "the model credential is unavailable"
               :target :model}
        failed (core/tick state (now "2026-11-04T08:30:10")
                          {id {:ok? false :issues [issue]}})
        blocked (core/tick (:state failed) (now "2026-11-09T08:30:10") {})
        repaired (core/repair-schedule (:state failed) id
                                       {:snapshot support/base-snapshot}
                                       (now "2026-11-09T09:00"))]
    (is (empty? (:started failed)))
    (is (empty? (:effects failed)))
    (is (= :needs-attention (get-in failed [:state :schedules id :state])))
    (is (= [issue] (get-in failed [:state :schedules id :attention])))
    (is (= :needs-attention (get-in failed [:needs-attention 0 :status])))
    (is (= :preflight-failed (get-in failed [:needs-attention 0 :reason])))
    (is (= [[:needs-attention :preflight-failed]
            [:missed :needs-attention]]
           (mapv (juxt :status :reason)
                 (core/runs-for (:state blocked) id)))
        "a needs-attention schedule records the later blocked occurrence
        as a missed fact and never retries automatically")
    (is (:ok? repaired))
    (is (= :enabled (get-in repaired [:schedule :state])))
    (is (= [[:missed :needs-attention]]
           (mapv (juxt :status :reason) (:missed repaired))))
    (is (= (now "2026-11-11T08:30")
           (get-in repaired [:schedule :next-run])))))

(deftest timeout-stops-and-can-be-corrected-to-partial
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        due (now "2026-11-04T08:30:10")
        first (core/tick state due {})
        run-id (get-in first [:started 0 :id])
        running (core/mark-running (:state first) run-id due)
        timed (core/tick (:state running)
                         (+ due 600001)
                         {}
                         {:timeout-ms 600000})
        corrected (core/finish-run (:state timed) run-id
                                   {:status :partial
                                    :result-summary "two files written"}
                                   (+ due 700000))]
    (is (= [:timed-out] (mapv :status (:timed-out timed))))
    (is (= :timeout (get-in timed [:state :runs run-id :reason])))
    (is (= [{:effect :stop-run :run-id run-id :reason :timeout}]
           (:effects timed)))
    (is (nil? (get-in timed [:state :schedules id :active-run-id])))
    (is (:ok? corrected))
    (is (= :partial (get-in corrected [:run :status])))
    (is (true? (get-in corrected [:run :timeout?])))))

(deftest restart-without-a-running-run-records-missed
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        restart-at (now "2026-11-11T09:00")
        recovered (core/recover state restart-at {:reason :process-restart})]
    (is (:ok? recovered))
    (is (pos? (count (:missed recovered))))
    (is (every? #(= :process-restart (:reason %)) (:missed recovered)))
    (is (every? #(= :missed (:status %)) (:missed recovered)))
    (is (empty? (:skipped recovered)))
    (is (= (now "2026-11-16T08:30")
           (get-in recovered [:state :schedules id :next-run])))))

(deftest restart-interrupts-without-replaying-and-skips-overlaps
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        due (now "2026-11-04T08:30:10")
        first (core/tick state due {})
        run-id (get-in first [:started 0 :id])
        running (core/mark-running (:state first) run-id due)
        restart-at (now "2026-11-11T09:00")
        recovered (core/recover (:state running) restart-at
                                {:reason :process-restart})
        recovered-again (core/recover (:state recovered) restart-at
                                      {:reason :process-restart})]
    (is (:ok? recovered))
    (is (= [run-id] (:interrupted recovered)))
    (is (= :interrupted (get-in recovered [:state :runs run-id :status])))
    (is (= :process-restart (get-in recovered [:state :runs run-id :reason])))
    (is (= restart-at (get-in recovered [:state :runs run-id :ended-at]))
        "an interrupted run records when recovery observed its end")
    (is (nil? (get-in recovered [:state :schedules id :active-run-id])))
    (is (empty? (:missed recovered))
        "a due occurrence during a live run is not a process-restart miss")
    (is (= 2 (count (:skipped recovered))))
    (is (every? #(= :previous-still-running (:reason %)) (:skipped recovered)))
    (is (every? #(= :skipped (:status %)) (:skipped recovered)))
    (is (= (now "2026-11-16T08:30")
           (get-in recovered [:state :schedules id :next-run])))
    (is (empty? (:interrupted recovered-again)))
    (is (empty? (:missed recovered-again)))
    (is (= (:state recovered) (:state recovered-again)))
    (is (not (contains? recovered :effects))
        "recovery never dispatches a start")))

(deftest restart-keeps-awaiting-user-and-pending-notifications
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        due (now "2026-11-04T08:30:10")
        first (core/tick state due {})
        run-id (get-in first [:started 0 :id])
        running (core/mark-running (:state first) run-id due)
        awaiting (core/finish-run (:state running) run-id
                                  {:status :awaiting-user
                                   :issues [{:code :S1_QUESTION}]}
                                  (now "2026-11-04T08:31"))
        recovered (core/recover (:state awaiting) (now "2026-11-04T09:00") {})]
    (is (= :awaiting-user (get-in recovered [:state :runs run-id :status])))
    (is (= run-id (get-in recovered [:state :schedules id :active-run-id])))
    (is (empty? (:missed recovered)))))

(deftest explicit-update-adopts-new-snapshot-only-when-supplied
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        first-tick (core/tick state (now "2026-11-04T08:30:10") {})
        run-id (get-in first-tick [:started 0 :id])
        running (core/mark-running (:state first-tick) run-id
                                   (now "2026-11-04T08:30:10"))
        finished (core/finish-run (:state running) run-id
                                  {:status :completed}
                                  (now "2026-11-04T08:31"))
        new-snapshot (assoc-in support/base-snapshot
                               [:model :model-id] "model-b")
        updated (core/update-schedule (:state finished) id
                                      {:snapshot new-snapshot}
                                      (now "2026-11-05T09:00"))
        second (core/tick (:state updated) (now "2026-11-09T08:30:10") {})
        second-run (get-in second [:started 0])]
    (is (= "model-a" (get-in first-tick [:started 0 :snapshot :model :model-id])))
    (is (:ok? updated))
    (is (= 2 (get-in updated [:schedule :revision])))
    (is (= "model-b" (get-in second-run [:snapshot :model :model-id])))
    (is (= 2 (:revision second-run)))))

(deftest fixed-zone-survives-device-change
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        next-run (get-in state [:schedules id :next-run])
        changed (core/set-device-zone state "Europe/Paris"
                                      (now "2026-11-03T10:00"))]
    (is (:ok? changed))
    (is (= "Europe/Paris" (:device-zone changed)))
    (is (empty? (:changes changed)))
    (is (= next-run (get-in changed [:state :schedules id :next-run])))
    (is (= {:mode :fixed :zone shanghai}
           (get-in changed [:state :schedules id :timezone])))))

(deftest follow-device-zone-recomputes-next-run
  (let [t0 (now "2026-11-03T09:00")
        spec (assoc support/base-spec
                    :timezone {:mode :follow-device :zone shanghai})
        [state id] (created-enabled spec t0)
        old-next (get-in state [:schedules id :next-run])
        changed (core/set-device-zone state "Europe/Paris"
                                      (now "2026-11-03T10:00"))
        change (first (:changes changed))]
    (is (:ok? changed))
    (is (= "Europe/Paris" (get-in changed [:state :schedules id
                                           :timezone :zone])))
    (is (= "Asia/Shanghai" (:old-zone change)))
    (is (= "Europe/Paris" (:new-zone change)))
    (is (= old-next (:old-next-run change)))
    (is (not= old-next (get-in changed [:state :schedules id :next-run])))
    (is (= (support/epoch "Europe/Paris" "2026-11-04T08:30")
           (get-in changed [:state :schedules id :next-run])))))

(deftest dst-gap-is-recorded-as-missed
  (let [spec {:name "DST"
              :prompt "Run at 02:30"
              :snapshot support/base-snapshot
              :recurrence {:kind :daily :time {:hour 2 :minute 30}}
              :timezone {:mode :fixed :zone "America/New_York"}}
        t0 (support/epoch "America/New_York" "2026-03-07T03:00")
        created (core/create-schedule (core/init-state "America/New_York")
                                      (assoc spec :enable? true) t0)
        id (get-in created [:schedule :id])
        tick (core/tick (:state created)
                        (support/epoch "America/New_York" "2026-03-08T09:00")
                        {})]
    (is (= 1 (count (:missed tick))))
    (is (= :missed (get-in tick [:missed 0 :status])))
    (is (= :nonexistent-local-time (get-in tick [:missed 0 :reason])))
    (is (= "2026-03-08T02:30" (get-in tick [:missed 0 :scheduled-local])))
    (is (empty? (:started tick)))
    (is (= (support/epoch "America/New_York" "2026-03-09T02:30")
           (get-in tick [:state :schedules id :next-run])))))

(deftest import-copy-and-delete
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        tick (core/tick state (now "2026-11-04T08:30:10") {})
        run-id (get-in tick [:started 0 :id])
        finished (core/finish-run (:state tick) run-id
                                  {:status :completed}
                                  (now "2026-11-04T08:31"))
        imported (core/import-schedule (:state finished)
                                       (assoc support/base-spec
                                              :name "Morning report (copy)")
                                       (now "2026-11-05T09:00"))
        imported-id (get-in imported [:schedule :id])
        deleted (core/delete-schedule (:state imported) id
                                      (now "2026-11-05T09:00") {})
        purged (core/delete-schedule (:state imported) id
                                     (now "2026-11-05T09:00")
                                     {:delete-runs? true})]
    (is (:ok? imported))
    (is (= :paused (get-in imported [:schedule :state])))
    (is (not= id imported-id))
    (is (= "Morning report (copy)" (get-in imported [:schedule :name])))
    (is (= (assoc support/base-snapshot
                  :prompt (:prompt support/base-spec)
                  :execution (:execution support/base-spec))
           (get-in imported [:schedule :snapshot])))
    (is (nil? (get-in imported [:schedule :active-run-id])))
    (is (:ok? deleted))
    (is (nil? (get-in deleted [:state :schedules id])))
    (is (= 1 (count (core/runs-for (:state deleted) id)))
        "deleting a schedule keeps its run history by default")
    (is (empty? (core/runs-for (:state purged) id)))
    (is (nil? (get-in purged [:state :runs run-id])))))

(deftest delete-active-schedule-requires-force
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        tick (core/tick state (now "2026-11-04T08:30:10") {})
        run-id (get-in tick [:started 0 :id])
        running (core/mark-running (:state tick) run-id
                                   (now "2026-11-04T08:30:10"))
        refused (core/delete-schedule (:state running) id
                                      (now "2026-11-04T08:35") {})
        forced (core/delete-schedule (:state running) id
                                     (now "2026-11-04T08:35")
                                     {:force? true})]
    (is (false? (:ok? refused)))
    (is (= :SCHEDULE_HAS_ACTIVE_RUN (get-in refused [:error :code])))
    (is (:ok? forced))
    (is (= :stopped (get-in forced [:stopped-run :status])))
    (is (= :schedule-deleted (get-in forced [:stopped-run :reason])))))

(defspec missed-days-never-catch-up
  50
  (prop/for-all [days (gen/choose 1 20)]
    (let [t0 (now "2026-11-03T09:00")
          [state id] (created-enabled
                      (assoc support/base-spec
                             :recurrence {:kind :daily
                                          :time {:hour 8 :minute 30}})
                      t0)
          later (now (format "2026-11-%02dT07:00" (+ 4 days)))
          tick (core/tick state later {} {:grace-ms 0})]
      (and (empty? (:started tick))
           (empty? (:effects tick))
           (= days (count (:missed tick)))
           (> (get-in tick [:state :schedules id :next-run]) later)))))

(defspec at-most-one-active-run-per-schedule
  50
  (prop/for-all [offsets (gen/not-empty
                          (gen/vector (gen/choose 0 1500000000) 1 12))]
    (let [t0 (now "2026-11-03T09:00")
          [state id] (created-enabled support/base-spec t0)
          final (reduce (fn [state offset]
                          (:state (core/tick state (+ t0 offset) {})))
                        state
                        offsets)]
      (<= (count (active-runs final id)) 1))))

(deftest outcome-unknown-keeps-the-slot-and-pause-does-not-cancel-it
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        first-tick (core/tick state (now "2026-11-04T08:30:10") {})
        run-id (get-in first-tick [:started 0 :id])
        running (core/mark-running (:state first-tick) run-id
                                   (now "2026-11-04T08:30:10"))
        unknown (core/finish-run (:state running) run-id
                                 {:status :outcome-unknown
                                  :reason :receipt-lost}
                                 (now "2026-11-04T08:31"))
        overlap (core/tick (:state unknown) (now "2026-11-09T08:30:10") {}
                           {:timeout-ms 1800000000})
        paused (core/pause-schedule (:state unknown) id
                                    (now "2026-11-09T09:00"))
        recovered (core/recover (:state paused) (now "2026-11-09T10:00") {})]
    (is (= :outcome-unknown (get-in unknown [:run :status])))
    (is (= run-id (get-in unknown [:state :schedules id :active-run-id])))
    (is (= :skipped (get-in overlap [:skipped 0 :status])))
    (is (= :previous-run-active (get-in overlap [:skipped 0 :reason])))
    (is (= :paused (get-in paused [:schedule :state])))
    (is (= run-id (get-in paused [:schedule :active-run-id]))
        "pause does not stop a run that already owns the slot")
    (is (= :outcome-unknown (get-in recovered [:state :runs run-id :status])))
    (is (= run-id (get-in recovered [:state :schedules id :active-run-id])))))


(deftest preflight-skip-records-a-skip-without-disabling-the-schedule
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        due (now "2026-11-04T08:30:10")
        skipped (core/tick state due
                           {id {:ok? false
                                :skip? true
                                :reason :result-chat-busy}})
        run (first (:skipped skipped))
        manual (core/manual-run (:state skipped) id
                                (now "2026-11-04T09:00")
                                {:ok? false
                                 :skip? true
                                 :reason :result-chat-busy})]
    (is (empty? (:started skipped)))
    (is (empty? (:needs-attention skipped)))
    (is (= :skipped (:status run)))
    (is (= :result-chat-busy (:reason run)))
    (is (= :enabled (get-in skipped [:state :schedules id :state])))
    (is (= (now "2026-11-09T08:30")
           (get-in skipped [:state :schedules id :next-run])))
    (is (:ok? manual))
    (is (= :skipped (:outcome manual)))
    (is (= :result-chat-busy (:reason manual)))
    (is (= (now "2026-11-09T08:30")
           (get-in manual [:state :schedules id :next-run])))))

(deftest needs-attention-records-later-blocked-occurrences
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled support/base-spec t0)
        first-tick (core/tick state (now "2026-11-04T08:30:10")
                              {id {:ok? false
                                   :issues [{:code :MISSING_CREDENTIAL
                                             :target :model}]}})
        blocked (core/tick (:state first-tick)
                           (now "2026-11-09T08:30:10") {})
        runs (core/runs-for (:state blocked) id)]
    (is (= :needs-attention (get-in blocked [:state :schedules id :state])))
    (is (empty? (:started blocked)))
    (is (some #(and (= :missed (:status %))
                    (= :needs-attention (:reason %))
                    (= (now "2026-11-09T08:30") (:scheduled-at %)))
              runs)
        "the later blocked occurrence is a fact, not silence")
    (is (> (get-in blocked [:state :schedules id :next-run])
           (now "2026-11-09T08:30"))
        "the next run advances strictly past now")))

(deftest same-device-zone-reports-no-change
  (let [t0 (now "2026-11-03T09:00")
        [state id] (created-enabled
                    (assoc support/base-spec
                           :timezone {:mode :follow-device :zone shanghai})
                    t0)
        same (core/set-device-zone state shanghai (now "2026-11-03T10:00"))]
    (is (:ok? same))
    (is (empty? (:changes same))
        "an unchanged device zone is not a change")
    (is (nil? (get-in same [:state :schedules id :last-zone-change])))
    (is (= (now "2026-11-04T08:30")
           (get-in same [:state :schedules id :next-run])))))
