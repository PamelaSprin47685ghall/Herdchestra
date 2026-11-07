(ns hc.compose.scheduler.service-test
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.scheduler.core :as core]
            [hc.compose.scheduler.service :as service]
            [hc.compose.scheduler.store :as store]
            [hc.compose.scheduler.support :as support]))

(def shanghai "Asia/Shanghai")

(defn- now
  [local]
  (support/epoch shanghai local))

(defn- failing-store
  "Open can persist the initial state; later writes fail."
  []
  (let [writes (atom 0)]
    (reify store/Store
      (load-state [_] nil)
      (save-state! [_ _]
        (when (> (swap! writes inc) 1)
          (throw (ex-info "disk is full" {:code :DISK_FULL})))))))

(defn- unreadable-store
  []
  (reify store/Store
    (load-state [_] (throw (ex-info "checksum mismatch" {:code :CORRUPT})))
    (save-state! [_ _] nil)))

(deftest service-lifecycle-persists-each-fact
  (let [t0 (now "2026-11-03T09:00")
        clock (support/fixed-clock t0)
        started (atom [])
        notified (atom [])
        memory (store/memory-store)
        opened (service/open-service
                {:store memory
                 :clock (:clock clock)
                 :start-run! (fn [{:keys [run]}]
                               (swap! started conj (:id run))
                               :accepted)
                 :notify! (fn [{:keys [run-id]}]
                            (swap! notified conj run-id)
                            :sent)})]
    (is (:ok? opened))
    (let [svc (:service opened)
          created (service/create-schedule! svc (assoc support/base-spec
                                                       :enable? true))
          id (get-in created [:schedule :id])]
      (is (:enabled? created))
      (is (= :enabled (:state (service/schedule svc id))))
      ;; The store has the enabled state, not the creation draft.
      (is (= :enabled (get-in (store/stored-state memory)
                              [:schedules id :state])))
      ((:at! clock) (now "2026-11-04T08:30:10"))
      (let [tick (service/tick! svc)
            run-id (get-in tick [:started 0 :id])]
        (is (:ok? tick))
        (is (= [run-id] @started))
        (is (= :running (:status (service/run svc run-id))))
        (is (= run-id (get-in (store/stored-state memory)
                              [:schedules id :active-run-id])))
        ((:at! clock) (now "2026-11-04T08:35"))
        (let [finished (service/finish-run! svc run-id
                                            {:status :completed
                                             :chat-id "chat-1"
                                             :result-files ["report.md"]})]
          (is (:ok? finished))
          (is (= :completed (:status (service/run svc run-id))))
          (is (= [run-id] @notified))
          (is (= :sent (get-in (service/run svc run-id)
                               [:notification :state])))
          (is (= "chat-1" (get-in (store/stored-state memory)
                                  [:runs run-id :chat-id])))
          (is (nil? (get-in (store/stored-state memory)
                            [:schedules id :active-run-id])))
          (is (empty? (service/pending-notifications svc))))))))

(deftest store-write-failure-does-not-publish-state
  (let [opened (service/open-service {:store (failing-store)
                                      :clock (:clock (support/fixed-clock
                                                      (now "2026-11-03T09:00")))
                                      :start-run! (constantly :accepted)})]
    (is (:ok? opened))
    (let [svc (:service opened)
          result (service/create-schedule! svc
                                           (assoc support/base-spec
                                                  :enable? true))]
      (is (false? (:ok? result)))
      (is (= :SCHEDULER_STORE_WRITE_FAILED (get-in result [:error :code])))
      (is (empty? (:schedules (service/snapshot svc)))))))

(deftest unreadable-store-is-not-replaced-with-an-empty-state
  (let [result (service/open-service
                {:store (unreadable-store)
                 :clock (:clock (support/fixed-clock
                                 (now "2026-11-03T09:00")))})]
    (is (false? (:ok? result)))
    (is (= :SCHEDULER_STORE_UNREADABLE (get-in result [:error :code])))))

(deftest invalid-persisted-shape-is-refused
  (let [bad (reify store/Store
              (load-state [_] {:schedules [] :runs {} :run-order []
                               :seq 0 :revision 0})
              (save-state! [_ _] nil))
        result (service/open-service
                {:store bad
                 :clock (:clock (support/fixed-clock
                                 (now "2026-11-03T09:00")))})]
    (is (false? (:ok? result)))
    (is (= :CORRUPT_SCHEDULER_STATE (get-in result [:error :code])))))

(deftest restart-interrupts-and-records-misses-without-replay
  (let [t0 (now "2026-11-03T09:00")
        clock (support/fixed-clock t0)
        started (atom [])
        opened (service/open-service
                {:clock (:clock clock)
                 :start-run! (fn [{:keys [run]}]
                               (swap! started conj (:id run))
                               :accepted)})]
    (is (:ok? opened))
    (let [svc (:service opened)
          created (service/create-schedule! svc (assoc support/base-spec
                                                       :enable? true))
          id (get-in created [:schedule :id])]
      ((:at! clock) (now "2026-11-04T08:30:10"))
      (let [tick (service/tick! svc)
            run-id (get-in tick [:started 0 :id])]
        (is (= [run-id] @started))
        (is (= :running (:status (service/run svc run-id))))
        ((:at! clock) (now "2026-11-11T09:00"))
        (let [recovered (service/recover! svc)]
          (is (:ok? recovered))
          (is (= [run-id] (:interrupted recovered)))
          (is (empty? (:missed recovered))
              "the due occurrences overlapped the live run")
          (is (= 2 (count (:skipped recovered))))
          (is (every? #(= :previous-still-running (:reason %))
                      (:skipped recovered)))
          (is (= :interrupted (:status (service/run svc run-id))))
          (is (= (now "2026-11-11T09:00")
                 (:ended-at (service/run svc run-id)))
              "recovery records when it observed the interrupted end")
          (is (= :process-restart (:reason (service/run svc run-id))))
          (is (nil? (get-in (service/snapshot svc)
                            [:schedules id :active-run-id])))
          (is (= (now "2026-11-16T08:30")
                 (get-in (service/snapshot svc)
                         [:schedules id :next-run])))
          (is (= 1 (count @started))
              "recovery never dispatches a new start"))))))

(deftest notification-retry-does-not-rerun-the-task
  (let [t0 (now "2026-11-03T09:00")
        clock (support/fixed-clock t0)
        fail? (atom true)
        attempts (atom 0)
        opened (service/open-service
                {:clock (:clock clock)
                 :start-run! (constantly :accepted)
                 :notify! (fn [_]
                            (swap! attempts inc)
                            (if @fail?
                              (throw (ex-info "notification offline" {}))
                              :sent))})
        svc (:service opened)
        created (service/create-schedule! svc (assoc support/base-spec
                                                     :enable? true))
        id (get-in created [:schedule :id])]
    ((:at! clock) (now "2026-11-04T08:30:10"))
    (let [tick (service/tick! svc)
          run-id (get-in tick [:started 0 :id])]
      ((:at! clock) (now "2026-11-04T08:35"))
      (service/finish-run! svc run-id {:status :completed
                                       :result-files ["report.md"]})
      (is (= 1 @attempts))
      (is (= :failed (get-in (service/run svc run-id)
                             [:notification :state])))
      (is (= [run-id] (mapv :id (service/pending-notifications svc))))
      (reset! fail? false)
      (let [retried (service/retry-notification! svc run-id)]
        (is (:ok? retried))
        (is (= 2 @attempts))
        (is (= :sent (get-in (service/run svc run-id)
                             [:notification :state])))
        (is (= 2 (get-in (service/run svc run-id)
                         [:notification :attempts])))
        (is (= 1 (count (service/runs-for svc id)))
            "notification retry creates no new run")))))

(deftest manual-run-does-not-enable-a-paused-schedule
  (let [t0 (now "2026-11-03T09:00")
        clock (support/fixed-clock t0)
        opened (service/open-service {:clock (:clock clock)
                                      :start-run! (constantly :accepted)})
        svc (:service opened)
        created (service/create-schedule! svc
                                          (assoc support/base-spec
                                                 :state :paused
                                                 :enable? false))
        id (get-in created [:schedule :id])
        before (:next-run (service/schedule svc id))
        manual (service/manual-run! svc id {:at (now "2026-11-03T10:00")})]
    (is (:ok? manual))
    (is (:started? manual))
    (is (= :manual (get-in manual [:run :trigger])))
    (is (= :paused (:state (service/schedule svc id))))
    (is (= before (:next-run (service/schedule svc id))))))

(deftest overlap-and-copy
  (let [t0 (now "2026-11-03T09:00")
        clock (support/fixed-clock t0)
        opened (service/open-service {:clock (:clock clock)
                                      :start-run! (constantly :accepted)})
        svc (:service opened)
        created (service/create-schedule! svc (assoc support/base-spec
                                                     :enable? true))
        id (get-in created [:schedule :id])]
    ((:at! clock) (now "2026-11-04T08:30:10"))
    (service/tick! svc)
    (let [manual (service/manual-run! svc id {})]
      (is (= :skipped (:outcome manual)))
      (is (= :previous-run-active (:reason manual)))
      (is (= :manual (get-in manual [:run :trigger])))
      (is (= 1 (count (filter #(contains? #{:preparing :running}
                                          (:status %))
                              (service/runs-for svc id))))))
    (let [copy (service/copy-schedule! svc id)]
      (is (:ok? copy))
      (is (= :paused (get-in copy [:schedule :state])))
      (is (re-find #"\(copy\)$" (get-in copy [:schedule :name])))
      (is (not= id (get-in copy [:schedule :id]))))))

(deftest device-zone-change-affects-only-follow-device
  (let [t0 (now "2026-11-03T09:00")
        clock (support/fixed-clock t0)
        opened (service/open-service {:clock (:clock clock)
                                      :start-run! (constantly :accepted)})
        svc (:service opened)
        fixed (service/create-schedule! svc (assoc support/base-spec
                                                   :enable? true))
        follow-spec (assoc support/base-spec
                           :name "Travel"
                           :timezone {:mode :follow-device
                                      :zone shanghai})
        follow (service/create-schedule! svc (assoc follow-spec
                                                    :enable? true))
        fixed-id (get-in fixed [:schedule :id])
        follow-id (get-in follow [:schedule :id])
        fixed-next (:next-run (service/schedule svc fixed-id))
        follow-next (:next-run (service/schedule svc follow-id))
        changed (service/set-device-zone! svc "Europe/Paris")]
    (is (:ok? changed))
    (is (= fixed-next (:next-run (service/schedule svc fixed-id))))
    (is (= {:mode :fixed :zone shanghai}
           (:timezone (service/schedule svc fixed-id))))
    (is (= "Europe/Paris"
           (get-in (service/schedule svc follow-id) [:timezone :zone])))
    (is (= follow-next (:old-next-run (first (:changes changed)))))
    (is (not= follow-next (:next-run (service/schedule svc follow-id))))))

(deftest restart-adopts-new-device-zone-for-follow-device
  (let [t0 (now "2026-11-03T09:00")
        clock (support/fixed-clock t0)
        memory (store/memory-store)
        spec (assoc support/base-spec
                    :timezone {:mode :follow-device :zone shanghai})
        opened (service/open-service {:store memory
                                      :clock (:clock clock)
                                      :device-zone shanghai
                                      :start-run! (constantly :accepted)})
        svc (:service opened)
        created (service/create-schedule! svc (assoc spec :enable? true))
        id (get-in created [:schedule :id])]
    (service/close! svc)
    (let [reopened (service/open-service {:store memory
                                          :clock (:clock clock)
                                          :device-zone "Europe/Paris"
                                          :start-run! (constantly :accepted)})
          svc2 (:service reopened)
          recovery (:recovery reopened)]
      (is (:ok? reopened))
      (is (= "Europe/Paris"
             (get-in (service/schedule svc2 id) [:timezone :zone])))
      (is (= (support/epoch "Europe/Paris" "2026-11-04T08:30")
             (get-in (service/schedule svc2 id) [:next-run])))
      (is (= "Asia/Shanghai"
             (get-in recovery [:zone-changes 0 :old-zone])))
      (is (= "Europe/Paris"
             (get-in recovery [:zone-changes 0 :new-zone]))))))

(deftest delete-active-schedule-dispatches-stop-run
  (let [t0 (now "2026-11-03T09:00")
        clock (support/fixed-clock t0)
        stopped (atom [])
        opened (service/open-service
                {:store (store/memory-store)
                 :clock (:clock clock)
                 :start-run! (constantly :accepted)
                 :stop-run! (fn [{:keys [run-id reason]}]
                              (swap! stopped conj [run-id reason]))})
        svc (:service opened)
        created (service/create-schedule! svc
                                          (assoc support/base-spec
                                                 :enable? true))
        id (get-in created [:schedule :id])]
    ((:at! clock) (now "2026-11-04T08:30:10"))
    (let [tick (service/tick! svc)
          run-id (get-in tick [:started 0 :id])
          deleted (service/delete-schedule! svc id {:force? true})]
      (is (:ok? deleted))
      (is (= :stopped (get-in deleted [:state :runs run-id :status])))
      (is (= [[run-id :schedule-deleted]] @stopped)
          "the host is told to stop the still-running work"))))

(deftest restored-schedules-are-paused-on-open
  (let [t0 (now "2026-11-03T09:00")
        clock (support/fixed-clock t0)
        created (core/create-schedule (core/init-state shanghai)
                                      (assoc support/base-spec :enable? true)
                                      t0)
        id (get-in created [:schedule :id])
        restored-state (-> (:state created)
                           (assoc-in [:schedules id :restored-from-backup?] true)
                           (assoc-in [:schedules id :next-run] t0)
                           (assoc-in [:schedules id :pending-gaps]
                                     [{:kind :gap :nominal-epoch-ms t0}])
                           (assoc-in [:schedules id :attention]
                                     [{:code :OLD_CREDENTIAL}]))
        store* (reify store/Store
                 (load-state [_] restored-state)
                 (save-state! [_ _] nil))
        opened (service/open-service {:store store*
                                      :clock (:clock clock)
                                      :start-run! (constantly :accepted)})
        svc (:service opened)
        schedule (service/schedule svc id)]
    (is (:ok? opened))
    (is (= :paused (:state schedule)))
    (is (nil? (:next-run schedule)))
    (is (empty? (:pending-gaps schedule)))
    (is (nil? (:attention schedule)))
    (is (nil? (:active-run-id schedule)))
    (is (empty? (service/runs svc))
        "opening a restored schedule never starts a run")))

(deftest every-accepted-transition-is-handed-to-the-store
  (let [t0 (now "2026-11-03T09:00")
        writes (atom 0)
        store* (reify store/Store
                 (load-state [_] nil)
                 (save-state! [_ _] (swap! writes inc)))
        opened (service/open-service {:store store*
                                      :clock (fn [] t0)})
        svc (:service opened)
        before @writes
        tick (service/tick! svc)]
    (is (:ok? tick))
    (is (> @writes before)
        "a no-op tick still hands the state to the store so the durable
        adapter can reconcile obligation-only rows")))
