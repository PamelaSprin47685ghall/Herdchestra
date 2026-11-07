(ns hc.server.scheduler-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.scheduler.core :as core]
            [hc.compose.scheduler.service :as service]
            [hc.compose.scheduler.store :as scheduler-store]
            [hc.compose.scheduler.support :as support]
            [hc.server.backup :as backup]
            [hc.server.backup.file-recovery :as backup-recovery]
            [hc.server.backup.file-target :as backup-target]
            [hc.server.backup.storage-adapter :as backup-domain]
            [hc.server.backup.workspace :as backup-workspace]
            [hc.server.recovery :as recovery]
            [hc.server.scheduler :as scheduler]
            [hc.server.scheduler.durable-store :as durable]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ports :as session-ports]
            [hc.server.sessions.registry :as session-registry]
            [hc.server.storage :as storage]))

(def shanghai "Asia/Shanghai")

(defn- now
  [local]
  (support/epoch shanghai local))

(defn- open-storage
  []
  (let [dir (support/temp-dir)
        entry (recovery/recovery-entry {:path dir :create? true})]
    (is (= :opened (:status entry)))
    {:dir dir :store (:store entry)}))

(defn- close-storage!
  [{:keys [dir store]}]
  (try
    (storage/close-store! store)
    (finally
      (support/delete-tree dir))))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))]
    (loop []
      (cond
        (pred) true
        (> (System/nanoTime) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(defn- create-enabled!
  [runtime]
  (let [created (scheduler/create-schedule! runtime
                                            (assoc support/base-spec
                                                   :enable? true))]
    (is (:ok? created) (pr-str created))
    (is (:enabled? created))
    (get-in created [:schedule :id])))

(deftest durable-state-round-trip-and-restart
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        started (atom [])
        adapter (durable/durable-store store {:generation "g1"
                                              :clock (:clock clock)})
        opened (service/open-service
                {:store adapter
                 :clock (:clock clock)
                 :start-run! (fn [{:keys [run]}]
                               (swap! started conj (:id run))
                               :accepted)})
        svc (:service opened)
        created (service/create-schedule! svc
                                          (assoc support/base-spec
                                                 :enable? true))
        schedule-id (get-in created [:schedule :id])]
    (try
      (is (:ok? opened))
      (is (:enabled? created))
      ((:at! clock) (now "2026-11-04T08:30:10"))
      (let [tick (service/tick! svc)
            run-id (get-in tick [:started 0 :id])]
        (is (= 1 (count @started)))
        (is (= :running (:status (service/run svc run-id))))
        (let [persisted (durable/state store)
              obligations (:obligations (durable/open-obligations store))]
          (is (= :running (get-in persisted [:runs run-id :status])))
          (is (= schedule-id (get-in persisted [:runs run-id :schedule-id])))
          (is (= 1 (count obligations)))
          (is (= :running (:phase (first obligations))))
          (is (false? (:safe-to-repeat? (first obligations)))))
        (let [recovered (durable/recover-obligations!
                         store
                         {:now-ms (now "2026-11-04T09:00")
                          :current-generation "g2"})
              obligations (:obligations (durable/open-obligations store))]
          (is (= 1 (:scanned recovered)))
          (is (= :outcome-unknown (:phase (first obligations))))
          (is (= "g1" (:executor-generation (first obligations))))))
      (let [later (now "2026-11-11T09:00")
            _ ((:at! clock) later)
            adapter2 (durable/durable-store store {:generation "g2"
                                                   :clock (:clock clock)})
            reopened (service/open-service
                      {:store adapter2
                       :clock (:clock clock)
                       :start-run! (fn [_]
                                     (throw (ex-info
                                             "restart must not start"
                                             {})))})]
        (is (:ok? reopened))
        (let [svc2 (:service reopened)
              recovery (:recovery reopened)
              run-id (first (:interrupted recovery))]
          (is (= 1 (count (:interrupted recovery))))
          (is (empty? (:missed recovery))
              "the due occurrences overlapped the live run")
          (is (= 2 (count (:skipped recovery))))
          (is (every? #(= :previous-still-running (:reason %))
                      (:skipped recovery)))
          (is (= :interrupted (:status (service/run svc2 run-id))))
          (is (= :process-restart (:reason (service/run svc2 run-id))))
          (is (= 1 (count @started)))
          (is (= :interrupted
                 (get-in (durable/state store) [:runs run-id :status])))
          (is (empty? (:obligations (durable/open-obligations store))))
          (is (> (get-in (durable/state store)
                         [:schedules schedule-id :next-run])
                 later))
          (let [deleted (service/delete-schedule! svc2 schedule-id
                                                  {:delete-runs? true})]
            (is (:ok? deleted))
            (is (empty? (:schedules (durable/state store))))
            (is (empty? (:runs (durable/state store))))
            (is (empty? (:obligations
                         (durable/open-obligations store)))))))
      (finally
        (close-storage! storage-env)))))

(deftest durable-store-refuses-documents-without-a-root
  (let [{:keys [store] :as storage-env} (open-storage)]
    (try
      (storage/commit-migration!
       store
       {:migration-id "orphan-scheduler-doc"
        :partition durable/scheduler-partition
        :reason :test
        :put [{:kind durable/schedule-kind
               :id "schedule-orphan"
               :doc {:id "schedule-orphan"}}]})
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"without a root"
           (durable/state store)))
      (finally
        (close-storage! storage-env)))))

(deftest awaiting-user-run-keeps-awaiting-obligation-phase
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        adapter (durable/durable-store store {:generation "g-await"
                                              :clock (:clock clock)})
        opened (service/open-service {:store adapter
                                      :clock (:clock clock)
                                      :start-run! (fn [_] :accepted)})
        svc (:service opened)]
    (try
      (is (:ok? opened))
      (let [created (service/create-schedule!
                     svc (assoc support/base-spec :enable? true))
            _ ((:at! clock) (now "2026-11-04T08:30:10"))
            tick (service/tick! svc)
            run-id (get-in tick [:started 0 :id])
            finished (service/finish-run! svc run-id
                                          {:status :awaiting-user
                                           :chat-id "chat-1"
                                           :reason :awaiting-user})
            obligations (:obligations (durable/open-obligations store))
            obligation (first obligations)]
        (is (:ok? created))
        (is (:ok? finished))
        (is (= :awaiting-user (:status (service/run svc run-id))))
        (is (= 1 (count obligations)))
        (is (= :awaiting-user (:phase obligation)))
        (is (= run-id (:scheduler/run-id obligation))))
      (finally
        (close-storage! storage-env)))))

(deftest real-timer-triggers-a-run-and-restart-recovers
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        started (atom [])
        result-file (str (:dir storage-env) "/triggered-run.txt")
        started-runtime
        (scheduler/start!
         {:storage store
          :clock (:clock clock)
          :tick-ms 20
          :preflight (constantly {:ok? true})
          :start-run! (fn [{:keys [run]}]
                        (spit result-file (str "started " (:id run)))
                        (swap! started conj (:id run))
                        :accepted)
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime) (pr-str started-runtime))
      (let [runtime (:scheduler started-runtime)
            schedule-id (create-enabled! runtime)]
        ((:at! clock) (now "2026-11-04T08:30:10"))
        (is (wait-for #(= 1 (count @started)) 3000))
        (is (.exists (io/file result-file)))
        (let [run-id (first @started)]
          (is (wait-for #(= :running
                            (get-in (scheduler/run runtime run-id) [:status]))
                        3000))
          (is (= :running
                 (get-in (durable/state store) [:runs run-id :status])))
          (is (= 1 (count (:obligations
                           (durable/open-obligations store)))))
          (is (= schedule-id
                 (get-in (durable/state store)
                         [:runs run-id :schedule-id]))))
        (scheduler/stop! runtime))
      (let [later (now "2026-11-11T09:00")
            _ ((:at! clock) later)
            reopened
            (scheduler/start!
             {:storage store
              :clock (:clock clock)
              :tick-ms 1000
              :preflight (constantly {:ok? true})
              :start-run! (fn [_]
                            (throw (ex-info "restart must not start" {})))
              :device-zone shanghai})]
        (try
          (is (:ok? reopened) (pr-str reopened))
          (let [recovery (:recovery reopened)]
            (is (= 1 (:scanned (:storage-recovery reopened))))
            (is (= 1 (count (:interrupted recovery))))
            (is (empty? (:missed recovery))
                "the due occurrences overlapped the live run")
            (is (= 2 (count (:skipped recovery))))
            (is (every? #(= :previous-still-running (:reason %))
                        (:skipped recovery))))
          (is (= 1 (count @started))
              "restart never dispatches a new start")
          (is (empty? (:obligations (durable/open-obligations store))))
          (is (.exists (io/file result-file)))
          (finally
            (scheduler/stop! (:scheduler reopened)))))
      (finally
        (when (:ok? started-runtime)
          (scheduler/stop! (:scheduler started-runtime)))
        (close-storage! storage-env)))))

(deftest default-callbacks-create-a-result-session-and-finish-on-terminal
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        commands (atom [])
        subscribers (atom [])
        create-opts (atom nil)
        session-state (atom {:commands {:command-epoch 1}
                             :runs {}
                             :run-order []})
        owner {:snapshot (fn [] {:state @session-state})
               :subscribe! (fn [listener]
                             (swap! subscribers conj listener)
                             (fn []
                               (swap! subscribers
                                      (fn [listeners]
                                        (vec (remove #{listener} listeners))))))
               :submit-command! (fn [request]
                                  (swap! commands conj request)
                                  {:status :accepted})}
        create-session! (fn [opts]
                          (reset! create-opts opts)
                          {:status :created
                           :session {:session-id "result-1"
                                     :owner-id "owner-1"}
                           :owner owner})
        started-runtime
        (scheduler/start!
         {:storage store
          :clock (:clock clock)
          :tick-ms 1000
          :preflight (constantly {:ok? true})
          :create-session! create-session!
          :owner-for (constantly owner)
          :platform :desktop
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime))
      (is (= :desktop (:platform (scheduler/health (:scheduler started-runtime)))))
      (let [runtime (:scheduler started-runtime)
            created (scheduler/create-schedule!
                     runtime
                     (assoc support/base-spec
                            :snapshot (assoc support/base-snapshot
                                             :assistant-id "assistant-1")
                            :enable? true))
            schedule-id (get-in created [:schedule :id])]
        (is (:ok? created))
        ((:at! clock) (now "2026-11-04T08:30:10"))
        (let [tick (scheduler/tick-now! runtime)
              run-id (get-in tick [:started 0 :id])]
          (is (:ok? tick))
          (is (= 1 (count @commands)))
          (is (= "assistant-1" (:assistant-id @create-opts)))
          (is (= (:capability-scope support/base-snapshot)
                 (:capability-scope @create-opts)))
          (let [request (first @commands)]
            (is (= :user/message (get-in request [:payload :kind])))
            (is (= (:prompt support/base-spec)
                   (get-in request [:payload :text])))
            (is (= 1 (:command-epoch request))))
          (is (= "result-1" (get-in (scheduler/run runtime run-id) [:chat-id])))
          (is (= 1 (count @subscribers)))
          (reset! session-state
                  {:commands {:command-epoch 1}
                   :runs {"session-run-1" {:state :completed}}
                   :run-order ["session-run-1"]})
          (doseq [listener @subscribers]
            (listener {:state @session-state}))
          (is (= :completed
                 (get-in (scheduler/run runtime run-id) [:status])))
          (is (= "result-1"
                 (get-in (scheduler/run runtime run-id) [:chat-id])))
          (is (= schedule-id
                 (get-in (scheduler/run runtime run-id) [:schedule-id])))
          (is (= :completed
                 (get-in (durable/state store) [:runs run-id :status])))
          (is (empty? (:obligations
                       (durable/open-obligations store))))))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))

(deftest awaiting-user-finishes-the-run-without-waiting-for-timeout
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        subscribers (atom [])
        session-state (atom {:commands {:command-epoch 1}
                             :runs {}
                             :run-order []})
        owner {:snapshot (fn [] {:state @session-state})
               :subscribe! (fn [listener]
                             (swap! subscribers conj listener)
                             (fn []
                               (swap! subscribers
                                      (fn [listeners]
                                        (vec (remove #{listener} listeners))))))
               :submit-command! (fn [_] {:status :accepted})}
        create-session! (fn [_]
                          {:status :created
                           :session {:session-id "awaiting-1"
                                     :owner-id "owner-1"}
                           :owner owner})
        started-runtime
        (scheduler/start!
         {:storage store
          :clock (:clock clock)
          :tick-ms 1000
          :preflight (constantly {:ok? true})
          :create-session! create-session!
          :owner-for (constantly owner)
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime))
      (let [runtime (:scheduler started-runtime)
            schedule-id (create-enabled! runtime)]
        ((:at! clock) (now "2026-11-04T08:30:10"))
        (let [tick (scheduler/tick-now! runtime)
              run-id (get-in tick [:started 0 :id])]
          (is (:ok? tick))
          (reset! session-state
                  {:commands {:command-epoch 1}
                   :runs {"session-run-1" {:state :running}}
                   :run-order ["session-run-1"]
                   :relay {:lanes {:plan {:awaiting-user? true}}}})
          (doseq [listener @subscribers]
            (listener {:state @session-state}))
          (is (scheduler/awaiting-user-state? @session-state))
          (is (= :awaiting-user
                 (get-in (scheduler/run runtime run-id) [:status])))
          (reset! session-state
                  {:commands {:command-epoch 1}
                   :runs {"session-run-1" {:state :completed}}
                   :run-order ["session-run-1"]
                   :relay {}})
          (doseq [listener @subscribers]
            (listener {:state @session-state}))
          (is (= :completed
                 (get-in (scheduler/run runtime run-id) [:status])))
          (is (= schedule-id
                 (get-in (scheduler/run runtime run-id) [:schedule-id])))))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))

(deftest live-config-resolver-reaches-preflight-and-session-creation
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        commands (atom [])
        captured-config (atom nil)
        models (atom {})
        subscribers (atom [])
        owner {:snapshot (fn [] {:state {:commands {:command-epoch 1}
                                          :runs {}
                                          :run-order []}})
               :subscribe! (fn [listener]
                             (swap! subscribers conj listener)
                             (fn [] nil))
               :submit-command! (fn [request]
                                  (swap! commands conj request)
                                  {:status :accepted})}
        configured
        {:connections
         [{:id "conn-1"
           :connection-id "conn-1"
           :name "Local"
           :kind :custom
           :enabled? true
           :models
           [{:model-entry-id "model-a"
             :provider-model-id "llama"
             :display-name "Llama"
             :enabled? true}]}]
         :roles {:main {:connection "conn-1" :model "model-a"}}}
        created-session
        (fn [opts]
          (reset! captured-config (:config opts))
          {:status :created
           :session {:session-id "result-1" :owner-id "owner-1"}
           :owner owner})
        started-runtime
        (scheduler/start!
         {:storage store
          :clock (:clock clock)
          :tick-ms 1000
          :config (fn []
                    (let [value @models]
                      (if (= ::broken value)
                        (throw (ex-info "settings are unavailable" {}))
                        {:models value})))
          :create-session! created-session
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime))
      (let [runtime (:scheduler started-runtime)]
        (testing "the resolver starts empty and preflight reports the gap"
          (let [created (scheduler/create-schedule!
                         runtime (assoc support/base-spec :enable? true))
                schedule-id (get-in created [:schedule :id])
                manual (scheduler/manual-run! runtime schedule-id)]
            (is (:ok? created))
            (is (:ok? manual))
            (is (= :needs-attention (get-in manual [:run :status])))
            (is (= :MODEL_UNAVAILABLE
                   (get-in manual [:run :issues 0 :code])))
            (is (empty? @commands))))
        (testing "a live settings save reaches the next preflight without restart"
          (reset! models configured)
          (let [created (scheduler/create-schedule!
                         runtime (assoc support/base-spec :enable? true))
                schedule-id (get-in created [:schedule :id])
                manual (scheduler/manual-run! runtime schedule-id)]
            (is (:ok? created))
            (is (:ok? manual))
            (is (:started? manual)
                "the same runtime must accept a model configured after start")
            (is (= 1 (count @commands)))
            (is (= "model-a"
                   (get-in @captured-config
                           [:connections "conn-1" :models "model-a"
                            :model-entry-id])))))
        (testing "a throwing resolver becomes a preflight issue, never a pass"
          (reset! models ::broken)
          (let [created (scheduler/create-schedule!
                         runtime (assoc support/base-spec :enable? true))
                schedule-id (get-in created [:schedule :id])
                manual (scheduler/manual-run! runtime schedule-id)]
            (is (:ok? created))
            (is (= :needs-attention (get-in manual [:run :status])))
            (is (= :PREFLIGHT_FAILED
                   (get-in manual [:run :issues 0 :code])))
            (is (= 1 (count @commands))))))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))

(deftest open-durable-store-uses-recovery-entry
  (let [dir (support/temp-dir)]
    (try
      (let [opened (durable/open-durable-store {:path dir})]
        (is (= :opened (:status opened)))
        (is (storage/store-open? (:storage opened)))
        (is (nil? (durable/state (:storage opened))))
        (storage/close-store! (:storage opened)))
      (finally
        (support/delete-tree dir)))))

(deftest no-model-manual-run-records-the-intended-preflight-reason
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        started-runtime
        (scheduler/start!
         {:storage store
          :clock (:clock clock)
          :tick-ms 1000
          :config {}
          :start-run! (fn [_]
                        (throw (ex-info "preflight must stop the run" {})))
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime))
      (let [runtime (:scheduler started-runtime)
            created (scheduler/create-schedule!
                     runtime
                     (assoc support/base-spec
                            :snapshot nil
                            :state :paused))
            schedule-id (get-in created [:schedule :id])
            manual (scheduler/manual-run! runtime schedule-id)]
        (is (:ok? created))
        (is (= :paused (get-in created [:schedule :state])))
        (is (:ok? manual))
        (is (= :needs-attention (get-in manual [:run :status])))
        (is (= :preflight-failed (get-in manual [:run :reason])))
        (is (= :SCHEDULE_INCOMPLETE
               (get-in manual [:run :issues 0 :code])))
        (is (not= :PREFLIGHT_FAILED
                  (get-in manual [:run :issues 0 :code]))))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))


(deftest ui-shaped-snapshot-gets-the-capability-scope
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        started-runtime
        (scheduler/start!
         {:storage store
          :clock (:clock clock)
          :tick-ms 1000
          :config {:models {}}
          :start-run! (fn [_] :accepted)
          :device-zone shanghai})]
    (try
      (let [runtime (:scheduler started-runtime)
            spec (assoc support/base-spec
                        :snapshot {:model {:connection-id "ui-conn"
                                           :model-entry-id "ui-model"}
                                   :prompt "Prompt"
                                   :execution {:mode :new-chat}}
                        :capability-scope {:tools #{:read-file}})
            created (scheduler/create-schedule! runtime spec)
            snapshot (get-in created [:schedule :snapshot])]
        (is (:ok? created) (pr-str created))
        (is (= "ui-conn" (get-in snapshot [:model :connection-id])))
        (is (contains? snapshot :capability-scope)
            "a UI-shaped snapshot must carry the capability ceiling key")
        (is (= {:tools #{:read-file}} (:capability-scope snapshot))))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))

(deftest ui-created-schedule-freezes-the-main-model-and-runs
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        started-runtime
        (scheduler/start!
         {:storage store
          :clock (:clock clock)
          :tick-ms 1000
          :config (fn []
                    {:models
                     {:connections [{:id "local-ollama"
                                     :kind :ollama
                                     :models [{:id "qwen"}]}]
                      :roles {:main {:connection "local-ollama"
                                     :model "qwen"}}}})
          :start-run! (fn [_] :accepted)
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime))
      (let [runtime (:scheduler started-runtime)
            created (scheduler/create-schedule!
                     runtime
                     (dissoc support/base-spec :snapshot))
            schedule (:schedule created)]
        (is (:ok? created) (pr-str created))
        (is (= "local-ollama"
               (get-in schedule [:snapshot :model :connection-id]))
            "creation freezes the live main model")
        (is (= "qwen"
               (get-in schedule [:snapshot :model :model-entry-id])))
        (is (some? (get-in schedule [:snapshot :execution])))
        (is (contains? (:snapshot schedule) :capability-scope))
        (let [manual (scheduler/manual-run! runtime (:id schedule))]
          (is (:ok? manual) (pr-str manual))
          (is (:started? manual))
          (is (not= :needs-attention (get-in manual [:run :status])))))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))

(deftest restored-schedules-are-forced-paused-without-catch-up
  (let [t0 (now "2026-11-03T09:00")
        created (core/create-schedule (core/init-state shanghai)
                                      (assoc support/base-spec :enable? true)
                                      t0)
        id (get-in created [:schedule :id])
        restored-state
        (-> (:state created)
            (assoc-in [:schedules id :restored-from-backup?] true)
            (assoc-in [:schedules id :restored-pending-review?] true)
            (assoc-in [:schedules id :pending-gaps]
                      [{:kind :gap :local-date-time "2026-11-04T02:30"
                        :zone shanghai :nominal-epoch-ms (now "2026-11-04T03:30")}])
            (assoc-in [:schedules id :attention]
                      [{:code :RESTORED_NEEDS_REVIEW}])
            (assoc-in [:schedules id :next-run] (now "2026-11-04T08:30")))
        memory (scheduler-store/memory-store restored-state)
        opened (service/open-service
                {:store memory
                 :clock (:clock (support/fixed-clock
                                 (now "2026-11-11T09:00")))
                 :start-run! (fn [_]
                               (throw (ex-info "restored schedule must not start" {})))})]
    (is (:ok? opened))
    (let [svc (:service opened)
          schedule (service/schedule svc id)
          state (service/snapshot svc)]
      (is (= :paused (:state schedule)))
      (is (nil? (:next-run schedule)))
      (is (empty? (:pending-gaps schedule)))
      (is (nil? (:attention schedule)))
      (is (nil? (:active-run-id schedule)))
      (is (false? (:exhausted? schedule)))
      (is (empty? (core/runs-for state id))
          "a restored paused schedule does not record catch-up misses"))))

(deftest stale-obligations-are-removed-even-without-domain-change
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        started (atom [])
        adapter (durable/durable-store store {:generation "g1"
                                              :clock (:clock clock)})
        opened (service/open-service
                {:store adapter
                 :clock (:clock clock)
                 :start-run! (fn [{:keys [run]}]
                               (swap! started conj (:id run))
                               :accepted)})
        svc (:service opened)
        created (service/create-schedule! svc
                                          (assoc support/base-spec
                                                 :enable? true))
        schedule-id (get-in created [:schedule :id])]
    (try
      ((:at! clock) (now "2026-11-04T08:30:10"))
      (let [tick (service/tick! svc)
            run-id (get-in tick [:started 0 :id])]
        (is (= 1 (count @started)))
        ((:at! clock) (now "2026-11-04T08:35"))
        (is (:ok? (service/finish-run! svc run-id {:status :completed})))
        ;; Simulate a stale obligation left by an older process or a restore.
        (storage/commit-migration!
         store
         {:migration-id "stale-scheduler-obligation"
          :partition durable/scheduler-partition
          :reason :test
          :obligations
          {:put [{:obligation/id run-id
                  :open? true
                  :phase :running
                  :executor-generation "old"
                  :safe-to-repeat? false
                  :scheduler/run-id run-id
                  :scheduler/schedule-id schedule-id}]}})
        (is (= 1 (count (:obligations
                         (durable/open-obligations store)))))
        (let [reopened (service/open-service
                        {:store (durable/durable-store
                                 store {:generation "g2" :clock (:clock clock)})
                         :clock (:clock clock)
                         :start-run! (fn [_]
                                       (throw (ex-info
                                               "stale obligation must not start"
                                               {})))})]
          (is (:ok? reopened))
          (is (empty? (:obligations
                       (durable/open-obligations store))))
          (is (= :completed
                 (get-in (durable/state store) [:runs run-id :status])))
          (is (= 1 (count @started)))))
      (finally
        (close-storage! storage-env)))))

(deftest redo-question-branches-the-source-and-sends-the-original-question
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        commands (atom [])
        subscribers (atom [])
        branch-calls (atom [])
        session-state (atom {:commands {:command-epoch 1}
                             :runs {}
                             :run-order []})
        owner {:snapshot (fn [] {:state @session-state})
               :subscribe! (fn [listener]
                             (swap! subscribers conj listener)
                             (fn []
                               (swap! subscribers
                                      (fn [listeners]
                                        (vec (remove #{listener} listeners))))))
               :submit-command! (fn [request]
                                  (swap! commands conj request)
                                  {:status :accepted})}
        source-session {:session-id "source-1"
                        :title "Source chat"
                        :status :active
                        :messages
                        {"message-1"
                         {:role :user
                          :current-version 0
                          :versions [{:content "What changed in the report?"}]}}}
        started-runtime
        (scheduler/start!
         {:storage store
          :clock (:clock clock)
          :tick-ms 1000
          :preflight (constantly {:ok? true})
          :get-session (fn [session-id]
                         (if (= session-id "source-1")
                           {:status :found :session source-session}
                           {:status :missing}))
          :branch-session! (fn [source-id opts]
                             (swap! branch-calls conj [source-id opts])
                             {:status :created
                              :session {:session-id "branch-1"
                                        :owner-id "owner-1"}
                              :owner owner})
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime))
      (let [runtime (:scheduler started-runtime)
            created (scheduler/create-schedule!
                     runtime
                     (assoc support/base-spec
                            :snapshot (dissoc support/base-snapshot :model)
                            :execution {:mode :redo-question
                                        :chat-id "source-1"
                                        :message-id "message-1"}
                            :enable? true))
            schedule-id (get-in created [:schedule :id])]
        (is (:ok? created))
        (let [manual (scheduler/manual-run! runtime schedule-id)]
          (is (:ok? manual))
          (is (:started? manual))
          (let [run-id (get-in manual [:run :id])]
            (is (= [["source-1" {:before-message-id "message-1"
                                 :title "Source chat — redo"}]]
                   @branch-calls))
            (is (= 1 (count @commands)))
            (is (= "What changed in the report?"
                   (get-in (first @commands) [:payload :text])))
            (is (= "branch-1"
                   (get-in (scheduler/run runtime run-id) [:chat-id])))
            (is (= :redo-question
                   (get-in (scheduler/run runtime run-id)
                           [:execution-target])))
            (is (= "source-1"
                   (get-in (scheduler/run runtime run-id)
                           [:source-session-id])))
            (is (= "message-1"
                   (get-in (scheduler/run runtime run-id)
                           [:source-message-id])))
            (is (= "branch-1"
                   (get-in (scheduler/run runtime run-id)
                           [:branch-session-id])))
            (reset! session-state
                    {:commands {:command-epoch 1}
                     :runs {"session-run-1" {:state :completed}}
                     :run-order ["session-run-1"]})
            (doseq [listener @subscribers]
              (listener {:state @session-state}))
            (is (= :completed
                   (get-in (scheduler/run runtime run-id) [:status]))))))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))


(deftest backup-restore-pauses-schedules-and-interrupts-active-runs
  (let [dir (support/temp-dir)
        db-a (str dir "/db-a")
        db-b (str dir "/db-b")
        target-root (str dir "/backups")
        recovery-root (str dir "/recovery")
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        entry-a (recovery/recovery-entry {:path db-a :create? true})
        store-a (:store entry-a)
        adapter-a (durable/durable-store store-a {:generation "g1"
                                                  :clock (:clock clock)})
        opened-a (service/open-service
                  {:store adapter-a
                   :clock (:clock clock)
                   :start-run! (constantly :accepted)})
        svc-a (:service opened-a)]
    (try
      (is (= :opened (:status entry-a)))
      (is (:ok? opened-a))
      (let [created (service/create-schedule! svc-a
                                              (assoc support/base-spec
                                                     :enable? true))
            schedule-id (get-in created [:schedule :id])]
        ((:at! clock) (now "2026-11-04T08:30:10"))
        (let [tick (service/tick! svc-a)
              run-id (get-in tick [:started 0 :id])]
          (is (= 1 (count (:started tick))))
          (is (= :running (:status (service/run svc-a run-id))))
          (let [backup-result
                (backup/backup!
                 (backup-domain/domain
                  store-a
                  {:quiescent? true
                   :no-prior-app-commands? true
                   :extensions-root (str dir "/extensions")
                   :workspace-store (backup-workspace/store
                                     (str dir "/workspaces-a"))})
                 (backup-target/store target-root)
                 {:encrypt? false
                  :categories [:schedules]})
                backup-id (:id backup-result)]
            (is (= :complete (:status backup-result)))
            (is (string? backup-id))
            (storage/close-store! store-a)
            (let [entry-b (recovery/recovery-entry {:path db-b :create? true})
                  store-b (:store entry-b)
                  restore-result
                  (backup/restore!
                   (backup-domain/domain
                    store-b
                    {:quiescent? true
                     :no-prior-app-commands? true
                     :extensions-root (str dir "/extensions")
                     :workspace-store (backup-workspace/store
                                       (str dir "/workspaces-b"))})
                   (backup-target/store target-root)
                   (backup-recovery/store recovery-root)
                   backup-id
                   {:mode :replace
                    :categories [:schedules]
                    :quiescent? true})]
              (try
                (is (= :complete (:status restore-result))
                    (pr-str (select-keys restore-result
                                         [:status :error :needs-recovery?
                                          :rolled-back? :summary :warnings])))
                (let [opened-b
                      (service/open-service
                       {:store (durable/durable-store
                                store-b {:generation "g2"
                                         :clock (:clock clock)})
                        :clock (:clock clock)
                        :start-run! (fn [_]
                                      (throw (ex-info
                                              "restored schedule must not start"
                                              {})))})]
                  (is (:ok? opened-b))
                  (let [svc-b (:service opened-b)
                        schedule (service/schedule svc-b schedule-id)
                        run (service/run svc-b run-id)]
                    (is (= :paused (:state schedule)))
                    (is (nil? (:next-run schedule)))
                    (is (nil? (:active-run-id schedule)))
                    (is (empty? (:pending-gaps schedule)))
                    (is (nil? (:attention schedule)))
                    (is (= :interrupted (:status run)))
                    (is (= :restored (:reason run)))
                    (let [runs (core/runs-for (service/snapshot svc-b)
                                              schedule-id)]
                      (is (= 1 (count runs))
                          "restored run history is preserved")
                      (is (= :interrupted (get-in runs [0 :status]))))
                    (is (empty? (:obligations
                                 (durable/open-obligations store-b))))))
                (finally
                  (storage/close-store! store-b)))))))
      (finally
        (when (storage/store-open? store-a)
          (storage/close-store! store-a))
        (support/delete-tree dir)))))


(deftest redo-question-uses-the-real-session-branch-path
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        commands (atom [])
        subscribers (atom [])
        owner {:snapshot (fn [] {:state {:commands {:command-epoch 1}
                                          :runs {}
                                          :run-order []}})
               :subscribe! (fn [listener]
                             (swap! subscribers conj listener)
                             (fn []
                               (swap! subscribers
                                      (fn [listeners]
                                        (vec (remove #{listener} listeners))))))
               :submit-command! (fn [request]
                                  (swap! commands conj request)
                                  {:status :accepted})}
        models {:revision 1
                :connections
                {"conn-1" {:connection-id "conn-1"
                           :name "Local"
                           :kind :custom
                           :default-base-url "http://localhost:11434"
                           :enabled? true
                           :models
                           {"model-a" {:model-entry-id "model-a"
                                       :provider-model-id "llama"
                                       :display-name "Llama"
                                       :enabled? true}}}}
                :connection-order ["conn-1"]}
        persistence (session-ports/memory-persistence)
        created-session
        (sessions/new-session
         {:session-id "source-1"
          :owner-id "source-owner"
          :title "Source chat"
          :config models
          :connection-id "conn-1"
          :model-entry-id "model-a"
          :now (now "2026-11-03T08:00")})
        source (:session created-session)
        message {:message-id "message-1"
                 :role :user
                 :versions [{:version-id "message-1-v1"
                             :content "What changed in the report?"
                             :created-at (now "2026-11-03T08:00")
                             :edited? false}]
                 :current-version 0
                 :attachments []
                 :citations []
                 :created-at (now "2026-11-03T08:00")
                 :updated-at (now "2026-11-03T08:00")
                 :deleted? false
                 :folded? false}
        source (-> source
                   (assoc-in [:messages "message-1"] message)
                   (assoc :message-order ["message-1"]))
        _ (is (= :committed
                 (:status (sessions/commit-session! persistence source))))
        registry (session-registry/registry
                  {:persistence persistence
                   :owner-factory (fn [_] owner)
                   :now-fn (:clock clock)})
        started-runtime
        (scheduler/start!
         {:storage store
          :registry registry
          :config {:models models}
          :clock (:clock clock)
          :tick-ms 1000
          :preflight (constantly {:ok? true})
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime))
      (let [runtime (:scheduler started-runtime)
            created (scheduler/create-schedule!
                     runtime
                     (assoc support/base-spec
                            :snapshot (dissoc support/base-snapshot :model)
                            :execution {:mode :redo-question
                                        :chat-id "source-1"
                                        :message-id "message-1"}
                            :enable? true))
            schedule-id (get-in created [:schedule :id])]
        (is (:ok? created))
        (let [manual (scheduler/manual-run! runtime schedule-id)
              run-id (get-in manual [:run :id])
              run (scheduler/run runtime run-id)
              branch-id (:chat-id run)]
          (is (:ok? manual))
          (is (:started? manual))
          (is (= 1 (count @commands)))
          (is (= "What changed in the report?"
                 (get-in (first @commands) [:payload :text])))
          (is (string? branch-id))
          (is (not= "source-1" branch-id))
          (let [branch (session-registry/get-session registry branch-id)
                branch-session (:session branch)]
            (is (= :found (:status branch)))
            (is (= "source-1" (:origin-session-id branch-session)))
            (is (= "message-1" (:origin-message-id branch-session)))
            (is (= [] (:message-order branch-session)))
            (is (= branch-id
                   (get-in (scheduler/run runtime run-id) [:chat-id])))
            (is (= "source-1"
                   (get-in (scheduler/run runtime run-id)
                           [:source-session-id])))
            (is (= "message-1"
                   (get-in (scheduler/run runtime run-id)
                           [:source-message-id]))))
          ))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))

(deftest time-controlled-due-missed-and-non-overlap
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        starts (atom [])
        started-runtime
        (scheduler/start!
         {:storage store
          :clock (:clock clock)
          :tick-ms 20
          :preflight (constantly {:ok? true})
          :start-run! (fn [{:keys [run]}]
                        (swap! starts conj (:id run))
                        :accepted)
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime) (pr-str started-runtime))
      (let [runtime (:scheduler started-runtime)
            created (scheduler/create-schedule!
                     runtime
                     (assoc support/base-spec
                            :recurrence {:kind :daily
                                         :time {:hour 8 :minute 30}}
                            :enable? true))
            schedule-id (get-in created [:schedule :id])]
        (is (:ok? created))
        ;; Due: one real run starts.
        ((:at! clock) (now "2026-11-04T08:30:10"))
        (is (wait-for #(= 1 (count @starts)) 3000))
        (let [run-id (first @starts)]
          (is (wait-for #(= :running
                            (get-in (scheduler/run runtime run-id) [:status]))
                        3000))
          ;; A second attempt while the first run owns the slot is skipped.
          ;; (Scheduled overlap across a day cannot occur under the 30-minute
          ;; run timeout; the core deterministic test covers that branch.)
          ((:at! clock) (now "2026-11-04T09:00"))
          (let [manual (scheduler/manual-run! runtime schedule-id)]
            (is (:ok? manual))
            (is (= :skipped (:outcome manual)))
            (is (= :previous-run-active (:reason manual))))
          (is (= 1 (count @starts))
              "non-overlap never starts a second run")
          ((:at! clock) (now "2026-11-04T09:05"))
          (is (:ok? (service/finish-run! (:service runtime) run-id
                                         {:status :completed})))
          ;; Two occurrences pass with no active run: missed facts, no catch-up.
          ((:at! clock) (now "2026-11-06T09:00"))
          (is (wait-for
               #(<= 2 (count (filter (fn [run] (= :missed (:status run)))
                                     (scheduler/runs runtime))))
               3000))
          (is (= 1 (count @starts))
              "missed occurrences never dispatch a run")
          (let [runs (scheduler/runs runtime)]
            (is (= 1 (count (filter #(= :completed (:status %)) runs))))
            (is (= 1 (count (filter #(= :skipped (:status %)) runs))))
            (is (= 2 (count (filter #(= :missed (:status %)) runs)))))
          (is (> (get-in (durable/state store)
                         [:schedules schedule-id :next-run])
                 (now "2026-11-06T09:00")))))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))

(deftest redo-question-append-version-uses-history-regenerate
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        commands (atom [])
        subscribers (atom [])
        session-state (atom {:commands {:command-epoch 1}
                             :runs {}
                             :run-order []})
        owner {:snapshot (fn [] {:state @session-state})
               :subscribe! (fn [listener]
                             (swap! subscribers conj listener)
                             (fn []
                               (swap! subscribers
                                      (fn [listeners]
                                        (vec (remove #{listener} listeners))))))
               :submit-command! (fn [request]
                                  (swap! commands conj request)
                                  {:status :accepted})}
        source-session {:session-id "source-1"
                        :owner-id "owner-1"
                        :title "Source chat"
                        :status :active
                        :model-binding {:connection-id "conn-1"
                                        :model-entry-id "model-a"}
                        :messages
                        {"assistant-1"
                         {:role :assistant
                          :current-version 0
                          :versions [{:content "Original answer"}]}}}
        started-runtime
        (scheduler/start!
         {:storage store
          :clock (:clock clock)
          :tick-ms 1000
          :preflight (constantly {:ok? true})
          :get-session (fn [session-id]
                         (if (= session-id "source-1")
                           {:status :found :session source-session}
                           {:status :missing}))
          :owner-for (constantly owner)
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime))
      (let [runtime (:scheduler started-runtime)
            created (scheduler/create-schedule!
                     runtime
                     (assoc support/base-spec
                            :snapshot (dissoc support/base-snapshot :model)
                            :execution {:mode :redo-question
                                        :redo-mode :append-version
                                        :chat-id "source-1"
                                        :message-id "assistant-1"}
                            :enable? true))
            schedule-id (get-in created [:schedule :id])]
        (is (:ok? created))
        (let [manual (scheduler/manual-run! runtime schedule-id)
              run-id (get-in manual [:run :id])
              run (scheduler/run runtime run-id)]
          (is (:ok? manual))
          (is (:started? manual))
          (is (= 1 (count @commands)))
          (let [request (first @commands)]
            (is (= :history/regenerate (get-in request [:payload :kind])))
            (is (= "source-1" (get-in request [:payload :session-id])))
            (is (= "assistant-1" (get-in request [:payload :message-id]))))
          (is (= "source-1" (:chat-id run)))
          (is (= :append-version (:redo-mode run)))
          (is (= "source-1" (:source-session-id run)))
          (is (= "assistant-1" (:source-message-id run)))
          (is (nil? (:branch-session-id run)))
          (is (= "Original answer"
                 (get-in source-session
                         [:messages "assistant-1" :versions 0 :content])))
          (reset! session-state
                  {:commands {:command-epoch 1}
                   :runs {"session-run-1" {:state :completed}}
                   :run-order ["session-run-1"]})
          (doseq [listener @subscribers]
            (listener {:state @session-state}))
          (is (= :completed (get-in (scheduler/run runtime run-id) [:status])))))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))

(deftest continue-chat-busy-is-skipped-not-needs-attention
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        commands (atom [])
        owner {:snapshot (fn []
                           {:state {:commands {:command-epoch 1}
                                    :runs {"session-run-1" {:state :running}}
                                    :run-order ["session-run-1"]}})
               :subscribe! (fn [_] (fn [] nil))
               :submit-command! (fn [request]
                                  (swap! commands conj request)
                                  {:status :accepted})}
        registry (session-registry/registry
                  {:persistence (session-ports/memory-persistence)
                   :owner-factory (fn [_] owner)
                   :now-fn (:clock clock)})
        _ (swap! (:state registry) assoc-in [:owners "chat-1"] owner)
        started-runtime
        (scheduler/start!
         {:storage store
          :registry registry
          :config {}
          :clock (:clock clock)
          :tick-ms 1000
          :start-run! (fn [_]
                        (throw (ex-info "busy chat must not start" {})))
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime))
      (let [runtime (:scheduler started-runtime)
            created (scheduler/create-schedule!
                     runtime
                     (assoc support/base-spec
                            :snapshot (dissoc support/base-snapshot :model)
                            :execution {:mode :continue-chat
                                        :chat-id "chat-1"}
                            :enable? true))
            schedule-id (get-in created [:schedule :id])
            manual (scheduler/manual-run! runtime schedule-id)]
        (is (:ok? created))
        (is (:ok? manual))
        (is (= :skipped (:outcome manual)))
        (is (= :result-chat-busy (:reason manual)))
        (is (empty? @commands))
        (is (= :enabled
               (get-in (scheduler/schedule runtime schedule-id) [:state])))
        (is (= :skipped (get-in manual [:run :status]))))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))

(deftest sequence-shaped-model-config-is-accepted
  (let [{:keys [store] :as storage-env} (open-storage)
        clock (support/fixed-clock (now "2026-11-03T09:00"))
        commands (atom [])
        captured-config (atom nil)
        subscribers (atom [])
        owner {:snapshot (fn [] {:state {:commands {:command-epoch 1}
                                          :runs {}
                                          :run-order []}})
               :subscribe! (fn [listener]
                             (swap! subscribers conj listener)
                             (fn [] nil))
               :submit-command! (fn [request]
                                  (swap! commands conj request)
                                  {:status :accepted})}
        models {:connections
                [{:id "conn-1"
                  :connection-id "conn-1"
                  :name "Local"
                  :kind :custom
                  :default-base-url "http://localhost:11434"
                  :enabled? true
                  :models
                  [{:model-entry-id "model-a"
                    :provider-model-id "llama"
                    :display-name "Llama"
                    :enabled? true}]}]
                :roles {:main {:connection "conn-1" :model "model-a"}}}
        created-session
        (fn [opts]
          (reset! captured-config (:config opts))
          {:status :created
           :session {:session-id "result-1" :owner-id "owner-1"}
           :owner owner})
        started-runtime
        (scheduler/start!
         {:storage store
          :clock (:clock clock)
          :tick-ms 1000
          :config {:models models}
          :create-session! created-session
          :device-zone shanghai})]
    (try
      (is (:ok? started-runtime))
      (let [runtime (:scheduler started-runtime)
            snapshot (assoc-in support/base-snapshot
                               [:model :model-entry-id] "model-a")
            created (scheduler/create-schedule!
                     runtime
                     (assoc support/base-spec
                            :snapshot snapshot
                            :enable? true))
            schedule-id (get-in created [:schedule :id])
            manual (scheduler/manual-run! runtime schedule-id)]
        (is (:ok? created))
        (is (:ok? manual))
        (is (:started? manual)
            "a correctly configured sequence-shaped model must pass preflight")
        (is (= 1 (count @commands)))
        (is (= "conn-1"
               (get-in @captured-config [:connections "conn-1" :id])))
        (is (= "model-a"
               (get-in @captured-config
                       [:connections "conn-1" :models "model-a"
                        :model-entry-id]))))
      (finally
        (scheduler/stop! (:scheduler started-runtime))
        (close-storage! storage-env)))))
