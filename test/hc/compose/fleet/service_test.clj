(ns hc.compose.fleet.service-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.client.swarm.model :as swarm-model]
            [hc.compose.fleet.restore :as restore]
            [hc.compose.fleet.service :as service]
            [hc.compose.fleet.supervisor :as supervisor]
            [hc.compose.fleet.support :as support]
            [hc.compose.mission.actors :as mission-actors]
            [hc.core.swarm-stm :as swarm]
            [hc.server.app.snapshot :as app-snapshot]))

(defn- slice
  ([id] (slice id []))
  ([id deps]
   {:slice-id id
    :goal (str "goal " id)
    :inputs []
    :depends-on deps
    :done-when "done"}))

(defn- start-service!
  [root launch & [extra]]
  (let [repo (str root "/repo")]
    (.mkdirs (io/file repo))
    (support/init-repo! repo)
    (service/start! (merge {:mission-id "mission-test"
                            :repo repo
                            :worktree-root (str root "/worktrees")
                            :master-ref "master"
                            :launch-swarm! launch
                            :clock #(System/currentTimeMillis)
                            :retention-ms 0}
                           extra))))

(deftest service-forks-real-worktrees-and-projects-real-fleet-tables
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [started (start-service!
                   root
                   (fn [{:keys [swarm-id runtime]}]
                     (support/commit-file! (:workspace runtime)
                                           (str swarm-id ".txt")
                                           (str swarm-id "\n")
                                           (str "work " swarm-id))
                     true))]
      (is (:ok? started))
      (let [s (:service started)
            forked (service/fork! s [(slice "a") (slice "b")])]
        (is (:ok? forked))
        (let [rows (service/fleet-rows s)
              tables (service/snapshot-tables s)]
          (is (= 2 (count rows)))
          (is (= #{"swarm-1" "swarm-2"} (set (keys (:swarm/by-id tables)))))
          (is (= 2 (count (:token-slot/by-id tables))))
          (is (= 2 (count (:write-gate/by-id tables))))
          (is (every? #(= :running (:status %)) rows))
          (is (every? #(some? (:board %)) rows))
          (is (every? #(= :free (get-in % [:token-slot :state])) rows))
          (is (every? #(false? (get-in % [:write-gate :held?])) rows))
          (is (not= (get-in (first rows) [:write-gate :swarm-id])
                    (get-in (second rows) [:write-gate :swarm-id]))))))))

(deftest service-fork-wrapper-returns-a-compact-vector
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [started (start-service! root (fn [_] true))]
      (is (:ok? started))
      (let [s (:service started)
            forked (service/fork s [(slice "a") (slice "b")])]
        (is (= #{"swarm-1" "swarm-2"} (set (map :swarm-id forked))))
        (is (every? #(= #{:swarm-id :slice-id :workspace}
                        (set (keys %)))
                    forked))))))

(deftest service-join-is-fifo-and-snapshot-keeps-the-full-report
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [started (start-service!
                   root
                   (fn [{:keys [swarm-id runtime]}]
                     (support/commit-file! (:workspace runtime)
                                           (str swarm-id ".txt")
                                           (str swarm-id "\n")
                                           (str "work " swarm-id))
                     true))]
      (is (:ok? started))
      (let [s (:service started)
            report (apply str (repeat 4000 "r"))]
        (is (:ok? (service/fork! s [(slice "a") (slice "b")])))
        (service/complete! s "swarm-2" report {:board-version 2})
        (service/complete! s "swarm-1" "report-a" {:board-version 1})
        (let [first-result (service/join! s)
              second-result (service/join! s)]
          (is (= "swarm-2" (:swarm-id first-result)))
          (is (= report (:report first-result)))
          (is (= "swarm-1" (:swarm-id second-result)))
          (is (= :none (service/join! s))))
        (let [row (first (filter #(= "swarm-2" (:swarm-id %))
                                 (service/fleet-rows s)))
              tables (service/snapshot-tables s)]
          (is (= :joined (:status row)))
          (is (= report (:report row)))
          (is (= report (get-in tables [:swarm/by-id "swarm-2" :report])))
          (let [merged (merge (app-snapshot/baseline {}) tables)
                dashboard (swarm-model/dashboard
                           (assoc merged :ui/selected-swarm-id "swarm-2"))]
            (is (= 2 (count (:swarms dashboard))))
            (is (= "swarm-2" (:selected-swarm-id dashboard)))
            (is (= report (get-in dashboard [:selected :report])))
            (is (some? (get-in dashboard [:selected :token-slot])))
            (is (some? (get-in dashboard [:selected :write-gate])))))))))

(deftest service-binds-real-task-actors-to-the-worktree-board
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [effects (atom [])
          started (start-service!
                   root
                   (fn [{:keys [swarm-id runtime actor]}]
                     (support/commit-file! (:workspace runtime)
                                           (str swarm-id ".txt")
                                           (str swarm-id "\n")
                                           (str "actor work " swarm-id))
                     (reset! effects (:effects actor))
                     true)
                   {:models {:main "main" :task "task"}
                    :slot-ids [:slot-a :slot-b]})]
      (is (:ok? started))
      (let [s (:service started)
            forked (service/fork! s [(slice "a")])
            row (first (service/fleet-rows s))
            model-effects (filter #(= :model/start (:type %)) @effects)
            request (:request (first model-effects))
            attempt-id (:attempt-id (first model-effects))]
        (is (:ok? forked))
        (is (= 2 (count model-effects)))
        (is (= "task" (:model-id request)))
        (is (= :task (:model-role request)))
        (is (= (:worktree row) (:workspace request)))
        (is (= 3 (count (get-in row [:board :actors])))
            "control actor plus two real @task slots")
        (is (= 1 (:actor-count (service/status s))))
        (is (= ["fleet-swarm-1"]
               (keys (service/worktree-runtimes s))))
        (is (= (:worktree row)
               (get-in (service/worktree-runtimes s)
                       ["fleet-swarm-1" :workspace])))
        (let [accepted (service/accept-actor-event!
                        s "swarm-1" attempt-id
                        {:event :completed :text "actor done"})]
          (is (:ok? accepted))
          (is (= 3 (count (get-in (first (service/fleet-rows s))
                                  [:board :actors]))))
          (service/complete! s "swarm-1" "actor report" {:board-version 1})
          (let [joined (service/join! s)
                row (first (service/fleet-rows s))]
            (is (= "swarm-1" (:swarm-id joined)))
            (is (= "actor report" (:report joined)))
            (is (string? (:ff-commit joined)))
            (is (= :joined (:status row)))
            (is (= 3 (count (get-in row [:board :actors]))))))))))

(deftest service-without-an-executor-fails-honestly
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [started (start-service! root nil)]
      (is (:ok? started))
      (let [s (:service started)
            forked (service/fork! s [(slice "a")])
            row (first (service/fleet-rows s))]
        (is (false? (:ok? forked)))
        (is (= :SWARM_EXECUTOR_UNAVAILABLE
               (get-in forked [:error :failed 0 :error :code])))
        (is (= :failed (:status row)))
        (is (not= :running (:status row)))
        (is (some? (:board row))
            "the failed launch still exposes the real per-swarm board it created")
        (is (= :free (get-in row [:token-slot :state])))))))

(deftest service-preflight-rejects-a-non-git-repository
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [result (service/start! {:mission-id "mission-test"
                                  :repo root
                                  :worktree-root (str root "/worktrees")
                                  :master-ref "master"
                                  :launch-swarm! (fn [_] true)})]
      (is (false? (:ok? result)))
      (is (= :MASTER_REF_UNAVAILABLE (get-in result [:error :code]))))))

(deftest service-gc-removes-worktrees-and-runtime-authority
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [started (start-service!
                   root
                   (fn [{:keys [swarm-id runtime]}]
                     (support/commit-file! (:workspace runtime)
                                           (str swarm-id ".txt")
                                           (str swarm-id "\n")
                                           (str "work " swarm-id))
                     true))]
      (is (:ok? started))
      (let [s (:service started)
            worktree-root (:worktree-root (service/state s))]
        (is (:ok? (service/fork! s [(slice "a")])))
        (service/complete! s "swarm-1" "report-a" {:board-version 1})
        (is (= "swarm-1" (:swarm-id (service/join! s))))
        (let [gc (service/gc! s)
              row (first (service/fleet-rows s))
              tables (service/snapshot-tables s)]
          (is (= ["swarm-1"] (:removed gc)))
          (is (not (.exists (io/file worktree-root "mission-test" "swarm-1"))))
          (is (= :joined (:status row)))
          (is (empty? (:token-slot/by-id tables)))
          (is (empty? (:write-gate/by-id tables))))))))

(deftest abandon-stops-only-the-named-swarm-and-gc-respects-its-lifetime
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [stopped (atom [])
          started (start-service!
                   root
                   (fn [{:keys [swarm-id runtime]}]
                     (support/commit-file!
                      (:workspace runtime)
                      (str swarm-id ".txt")
                      (str swarm-id "\n")
                      (str "work " swarm-id))
                     true)
                   {:stop-swarm! (fn [request]
                                   (swap! stopped conj request)
                                   {:ok? true})})]
      (is (:ok? started))
      (let [s (:service started)
            worktree-root (:worktree-root (service/state s))]
        (is (:ok? (service/fork! s [(slice "a") (slice "b")])))
        (let [abandoned (service/abandon! s "swarm-1" {:reason :stale})
              rows (service/fleet-rows s)
              row-1 (first (filter #(= "swarm-1" (:swarm-id %)) rows))
              row-2 (first (filter #(= "swarm-2" (:swarm-id %)) rows))]
          (is (:ok? abandoned))
          (is (= ["swarm-1"] (mapv :swarm-id @stopped)))
          (is (= :abandoned (:status row-1)))
          (is (= :running (:status row-2)))
          (is (.exists (io/file worktree-root "mission-test" "swarm-1")))
          (is (.exists (io/file worktree-root "mission-test" "swarm-2"))))
        (service/complete! s "swarm-2" "report-b" {:board-version 1})
        (is (= "swarm-2" (:swarm-id (service/join! s))))
        (let [gc (service/gc! s)
              tables (service/snapshot-tables s)]
          (is (= #{"swarm-1" "swarm-2"} (set (:removed gc))))
          (is (not (.exists (io/file worktree-root "mission-test" "swarm-1"))))
          (is (not (.exists (io/file worktree-root "mission-test" "swarm-2"))))
          (is (empty? (:token-slot/by-id tables)))
          (is (empty? (:write-gate/by-id tables))))))))

(deftest service-refuses-gc-after-stop
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [started (start-service! root (fn [_] true))]
      (is (:ok? started))
      (let [s (:service started)]
        (service/stop! s)
        (let [result (service/gc! s)]
          (is (false? (:ok? result)))
          (is (= :FLEET_CLOSED (get-in result [:error :code]))))))))

;; ---------------------------------------------------------------------------
;; Restart restore: durable current state, FIFO queue, in-flight uncertainty

(defn- memory-store
  [initial]
  (let [value (atom initial)]
    {:store (reify restore/SnapshotStore
              (load-snapshot [_]
                (if (nil? @value)
                  {:status :missing}
                  {:status :ok :snapshot @value}))
              (save-snapshot! [_ snapshot]
                (reset! value snapshot)
                {:ok? true}))
     :value value}))

(defn- make-restored-worktree!
  [worktree-root swarm-id]
  (let [dir (io/file worktree-root "mission-test" swarm-id)]
    (.mkdirs dir)
    (spit (io/file dir ".git") (str "gitdir: " (str dir)))
    (str dir)))

(defn- restored-swarm
  [id status created-seq workspace slice-id]
  {:swarm-id id
   :slice-id slice-id
   :workspace workspace
   :branch (str "hc/mission-test/" id)
   :status status
   :worktree-ready? true
   :completed? false
   :integrating? false
   :report nil
   :ff-commit nil
   :rebase-rounds 0
   :board-version 0
   :completion-seq nil
   :root-ready 0
   :root-waiting 0
   :root-done 0
   :master-version 0
   :created-seq created-seq
   :last-event nil
   :finished-at nil
   :gc-after nil
   :worktree-removed? false})

(defn- completed-restored-swarm
  [id created-seq workspace slice-id]
  (assoc (restored-swarm id :running created-seq workspace slice-id)
         :completed? true
         ;; A sentinel distinct from any fixture-generated text: the restore
         ;; and join paths must return this exact persisted report.
         :report (str "persisted-report-" id)
         :board-version 1
         :completion-seq created-seq))

(defn- restored-state
  [{:keys [worktree-root swarms queue master-version completion-seq slices
           version]}]
  (let [slices (or slices
                   (into {}
                         (map (fn [[id swarm]]
                                [(:slice-id swarm)
                                 {:slice-id (:slice-id swarm)
                                  :goal (str "goal " id)
                                  :inputs []
                                  :depends-on []
                                  :done-when "done"}]))
                         swarms))]
    {:mission-id "mission-test"
     :worktree-root worktree-root
     :master-ref "master"
     :retention-ms 0
     :version (or version 7)
     :event-seq 0
     :events []
     :master-version master-version
     :completion-seq completion-seq
     :next-swarm-seq (count swarms)
     :slices slices
     :slice->swarm (into {} (map (fn [[id swarm]]
                                   [(:slice-id swarm) id]))
                         swarms)
     :swarms swarms
     :completion-queue queue}))

(defn- restored-snapshot
  [state]
  {:fleet/snapshot-version 1
   :fleet/version (:version state)
   :mission-id (:mission-id state)
   :master-ref (:master-ref state)
   :state state
   :worktrees (->> (:swarms state)
                   vals
                   (sort-by :created-seq)
                   (mapv (fn [swarm]
                           {:swarm-id (:swarm-id swarm)
                            :slice-id (:slice-id swarm)
                            :workspace (:workspace swarm)
                            :branch (:branch swarm)
                            :status (:status swarm)
                            :worktree-removed? false})))})

(deftest service-restores-the-fifo-queue-and-master-version
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [worktree-root (str root "/worktrees")
          ws1 (make-restored-worktree! worktree-root "swarm-1")
          ws2 (make-restored-worktree! worktree-root "swarm-2")
          swarms {"swarm-1" (completed-restored-swarm "swarm-1" 1 ws1 "slice-1")
                  "swarm-2" (completed-restored-swarm "swarm-2" 2 ws2 "slice-2")}
          state (restored-state
                 {:worktree-root worktree-root
                  :swarms swarms
                  :queue ["swarm-1" "swarm-2"]
                  :master-version 0
                  :completion-seq 2
                  :version 5})
          memory (memory-store (restored-snapshot state))
          fake (support/fake-git
                {:repo "/fake/repo"
                 :repo-head "master-0"
                 :heads {ws1 "head-1" ws2 "head-2"}
                 :current-branches {ws1 "hc/mission-test/swarm-1"
                                    ws2 "hc/mission-test/swarm-2"}
                 :branch-heads {"hc/mission-test/swarm-1" "head-1"
                                "hc/mission-test/swarm-2" "head-2"}})
          started (service/start!
                   {:mission-id "mission-test"
                    :git (:git fake)
                    :worktree-root worktree-root
                    :master-ref "master"
                    :launch-swarm! (fn [_] true)
                    :preflight? false
                    :clock (constantly 1000)
                    :retention-ms 0
                    :store (:store memory)})]
      (is (:ok? started))
      (let [s (:service started)]
        (is (= :restored (get-in started [:restored :status])))
        (is (= ["swarm-1" "swarm-2"]
               (get-in started [:restored :plan :joinable])))
        (let [first-result (service/join! s)
              second-result (service/join! s)]
          (is (= "swarm-1" (:swarm-id first-result)))
          (is (= "persisted-report-swarm-1" (:report first-result))
              "join returns the persisted report verbatim")
          (is (= "swarm-2" (:swarm-id second-result)))
          (is (= "persisted-report-swarm-2" (:report second-result))))
        (is (= :none (service/join! s)))
        (let [persisted @(:value memory)]
          (is (= [] (get-in persisted [:state :completion-queue])))
          (is (= 2 (get-in persisted [:state :master-version])))
          (is (= :joined
                 (get-in persisted [:state :swarms "swarm-1" :status])))
          (is (= "persisted-report-swarm-1"
                 (get-in persisted [:state :swarms "swarm-1" :report]))))))))

(deftest service-restart-marks-in-flight-unknown-and-resumes-queued
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [worktree-root (str root "/worktrees")
          ws1 (make-restored-worktree! worktree-root "swarm-1")
          ws2 (make-restored-worktree! worktree-root "swarm-2")
          ws3 (make-restored-worktree! worktree-root "swarm-3")
          swarms {"swarm-1" (restored-swarm "swarm-1" :running 1 ws1 "slice-1")
                  "swarm-2" (restored-swarm "swarm-2" :queued 2 ws2 "slice-2")
                  "swarm-3" (restored-swarm "swarm-3" :queued 3 ws3 "slice-3")}
          slices {"slice-1" {:slice-id "slice-1" :goal "goal 1" :inputs []
                             :depends-on [] :done-when "done"}
                  "slice-2" {:slice-id "slice-2" :goal "goal 2" :inputs []
                             :depends-on [] :done-when "done"}
                  "slice-3" {:slice-id "slice-3" :goal "goal 3" :inputs []
                             :depends-on ["slice-1"] :done-when "done"}}
          state (restored-state
                 {:worktree-root worktree-root
                  :swarms swarms
                  :slices slices
                  :queue []
                  :master-version 0
                  :completion-seq 0
                  :version 4})
          memory (memory-store (restored-snapshot state))
          launches (atom [])
          fake (support/fake-git {:repo "/fake/repo"})
          started (service/start!
                   {:mission-id "mission-test"
                    :git (:git fake)
                    :worktree-root worktree-root
                    :master-ref "master"
                    :launch-swarm! (fn [request]
                                     (swap! launches conj request)
                                     true)
                    :preflight? false
                    :clock (constantly 1000)
                    :retention-ms 0
                    :store (:store memory)})]
      (is (:ok? started))
      (let [s (:service started)
            control-state (service/state s)]
        (is (= ["swarm-2"] (get-in started [:restored :plan :reattach])))
        (is (= ["swarm-1"] (get-in started [:restored :plan :uncertain])))
        (is (= ["swarm-2"] (mapv :swarm-id @launches)))
        (is (= :running (get-in control-state [:swarms "swarm-2" :status])))
        (is (= :failed (get-in control-state [:swarms "swarm-1" :status])))
        (is (= :SWARM_IN_FLIGHT_OUTCOME_UNKNOWN
               (get-in control-state [:swarms "swarm-1" :failure :code])))
        (is (= :failed (get-in control-state [:swarms "swarm-3" :status])))
        (is (= :DEPENDENCY_TERMINAL
               (get-in control-state [:swarms "swarm-3" :failure :code])))
        (is (= 0 (:master-version control-state)))
        (is (= [] (get-in @(:value memory) [:state :completion-queue])))))))

(deftest service-restores-real-board-token-and-actor-state
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [repo (str root "/repo")
          worktree-root (str root "/worktrees")
          _ (.mkdirs (io/file repo))
          _ (support/init-repo! repo)
          memory (memory-store nil)
          launches (atom [])
          start-opts {:mission-id "mission-test"
                      :repo repo
                      :worktree-root worktree-root
                      :master-ref "master"
                      :launch-swarm! (fn [request]
                                       (swap! launches conj request)
                                       true)
                      :clock (constantly 1000)
                      :retention-ms 0
                      :store (:store memory)
                      :models {:main "main" :task "task"}
                      :slot-ids [:slot-a :slot-b]}
          started (service/start! start-opts)]
      (is (:ok? started))
      (let [s1 (:service started)]
        (is (:ok? (service/fork! s1 [(slice "a")])))
        (let [runtime (service/runtime s1 "swarm-1")
              board (service/board-snapshot s1 "swarm-1")
              agent-id (->> (:actors board)
                            vals
                            (filter #(= :slot-a (:slot-id %)))
                            first
                            :agent-id)
              attempt-a (get-in runtime
                                [:actor :state :actors :tenures
                                 "fleet-swarm-1" :slots :slot-a :attempt-id])
              attempt-b (get-in runtime
                                [:actor :state :actors :tenures
                                 "fleet-swarm-1" :slots :slot-b :attempt-id])]
          (is (some? agent-id))
          (is (some? attempt-a))
          (is (some? attempt-b))
          (is (:ok? (swarm/execute!
                     (:kernel runtime)
                     {:op :create-task
                      :actor-id (:control-agent-id runtime)
                      :goal "holder verification"
                      :remaining-work "run the token check"
                      :done-when "the check reports its result"
                      :needs-token true})))
          (is (:ok? (swarm/execute!
                     (:kernel runtime)
                     {:op :take-or-die
                      :actor-id agent-id
                      :confirm :i-hereby-confirm-i-die-without-regret})))
          (is (= :held (get-in (service/board-snapshot s1 "swarm-1")
                               [:token-slot :state])))
          (is (:ok? (service/accept-actor-event!
                     s1 "swarm-1" attempt-b
                     {:event :completed :text "persisted assistant"})))
          (service/stop! s1)
          (reset! launches [])
          (let [snapshot @(:value memory)]
            (is (= 2 (get-in snapshot [:fleet/snapshot-version])))
            (is (some? (get-in snapshot [:runtimes "swarm-1" :board])))
            (is (some? (get-in snapshot [:runtimes "swarm-1" :actor])))
            (is (= agent-id
                   (get-in snapshot [:runtimes "swarm-1" :board
                                     :token-slot :holder-id]))))
          (let [restarted (service/start! (assoc start-opts :clock (constantly 2000)))
                s2 (:service restarted)
                row (first (service/fleet-rows s2))
                runtime (service/runtime s2 "swarm-1")
                actor-state (get-in runtime [:actor :state])
                old-attempt (get-in actor-state [:actors :calls attempt-a])
                effects (mapcat #(get-in % [:actor :effects]) @launches)
                starts (filter #(= :model/start (:type %)) effects)]
            (is (:ok? restarted))
            (is (= :restored (get-in restarted [:restored :status])))
            (is (= ["swarm-1"] (get-in restarted [:restored :reattached])))
            (is (= ["swarm-1"] (get-in restarted [:restored :resumed])))
            (is (= :persisted (:runtime-source runtime)))
            (is (= agent-id (get-in row [:token-slot :holder-id])))
            (is (= :held (get-in row [:token-slot :state])))
            (is (= agent-id (get-in row [:board :token-slot :holder-id])))
            (is (= "persisted assistant"
                   (->> (get-in actor-state
                                [:actors :tenures "fleet-swarm-1"
                                 :slots :slot-b :context])
                        (filter #(= :assistant (:role %)))
                        first
                        :text)))
            (is (some? old-attempt))
            (is (= :outcome-unknown (:status old-attempt)))
            (is (some #(and (= :system (:role %))
                            (str/includes? (str (:text %))
                                           "outcome is unknown"))
                      (get-in actor-state
                              [:actors :tenures "fleet-swarm-1"
                               :slots :slot-a :context])))
            (is (some #(not= attempt-a (:attempt-id %)) starts)
                "resume starts a fresh turn")
            (is (empty? (filter #(= attempt-a (:attempt-id %)) starts))
                "the old in-flight attempt is never replayed")))))))

(deftest service-persists-board-events-under-the-control-monitor
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [repo (str root "/repo")
          _ (.mkdirs (io/file repo))
          _ (support/init-repo! repo)
          monitor-ref (atom nil)
          held (atom [])
          store (reify restore/SnapshotStore
                  (load-snapshot [_] {:status :missing})
                  (save-snapshot! [_ _]
                    (when-let [monitor @monitor-ref]
                      (swap! held conj (Thread/holdsLock monitor)))
                    {:ok? true}))
          started (service/start!
                   {:mission-id "mission-test"
                    :repo repo
                    :worktree-root (str root "/worktrees")
                    :master-ref "master"
                    :launch-swarm! (fn [_] true)
                    :clock (constantly 1000)
                    :retention-ms 0
                    :store store})
          s (:service started)]
      (is (:ok? started))
      (reset! monitor-ref (get-in s [:control :monitor]))
      (is (:ok? (service/fork! s [(slice "a")])))
      (reset! held [])
      (let [runtime (service/runtime s "swarm-1")]
        (is (:ok? (swarm/execute!
                   (:kernel runtime)
                   {:op :create-task
                    :actor-id (:control-agent-id runtime)
                    :goal "board event"
                    :remaining-work "persist the board"
                    :done-when "the board is persisted"}))))
      (is (seq @held))
      (is (every? true? @held)
          "snapshot build and atomic write happen while the control monitor is held"))))

(deftest service-initializes-a-missing-state-file
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [worktree-root (str root "/worktrees")
          memory (memory-store nil)
          fake (support/fake-git {:repo "/fake/repo"})
          started (service/start!
                   {:mission-id "mission-test"
                    :git (:git fake)
                    :worktree-root worktree-root
                    :master-ref "master"
                    :launch-swarm! (fn [_] true)
                    :preflight? false
                    :clock (constantly 1000)
                    :retention-ms 0
                    :store (:store memory)})]
      (is (:ok? started))
      (is (= :fresh (get-in started [:restored :status])))
      (is (some? @(:value memory)))
      (is (= 0 (get-in @(:value memory) [:state :master-version]))))))

(deftest service-initializes-a-real-state-file
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [worktree-root (str root "/worktrees")
          state-file (str root "/fleet/state.edn")
          fake (support/fake-git {:repo "/fake/repo"})
          started (service/start!
                   {:mission-id "mission-test"
                    :git (:git fake)
                    :worktree-root worktree-root
                    :master-ref "master"
                    :launch-swarm! (fn [_] true)
                    :preflight? false
                    :clock (constantly 1000)
                    :retention-ms 0
                    :state-file state-file})]
      (is (:ok? started))
      (is (= :fresh (get-in started [:restored :status])))
      (is (.isFile (io/file state-file)))
      (let [loaded (restore/load! (restore/file-store state-file))]
        (is (= :ok (:status loaded)))
        (is (:ok? (restore/validate (:snapshot loaded))))
        (is (= "mission-test"
               (get-in loaded [:snapshot :mission-id])))))))

(deftest service-refuses-a-corrupt-state-file
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [worktree-root (str root "/worktrees")
          corrupt (reify restore/SnapshotStore
                    (load-snapshot [_]
                      {:status :corrupt
                       :error {:code :FLEET_STATE_CORRUPT}})
                    (save-snapshot! [_ _]
                      (throw (ex-info "a corrupt state must not be overwritten"
                                      {}))))
          fake (support/fake-git {:repo "/fake/repo"})
          started (service/start!
                   {:mission-id "mission-test"
                    :git (:git fake)
                    :worktree-root worktree-root
                    :master-ref "master"
                    :launch-swarm! (fn [_] true)
                    :preflight? false
                    :clock (constantly 1000)
                    :retention-ms 0
                    :store corrupt})]
      (is (false? (:ok? started)))
      (is (= :FLEET_STATE_CORRUPT (get-in started [:error :code]))))))

(deftest supervisor-tool-call-uses-the-real-actor-state-shape
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [started (start-service!
                   root
                   (fn [_] true)
                   {:models {:main "main" :task "task"}
                    :slot-ids [:slot-a :slot-b]})]
      (is (:ok? started))
      (let [s (:service started)
            _ (is (:ok? (service/fork! s [(slice "a")])))
            actor (get-in (service/runtime s "swarm-1") [:actor :state])
            attempt-id (get-in actor
                               [:actors :tenures "fleet-swarm-1"
                                :slots :slot-a :attempt-id])
            executed (atom nil)
            started-effects (atom [])
            sup (supervisor/make
                 {:service-ref (atom s)
                  :start-effect! (fn [effect]
                                   (swap! started-effects conj effect)
                                   {:ok? true})
                  :execute-tool! (fn [request]
                                   (reset! executed request)
                                   {:status :succeeded :value 7})})
            result ((:handle-event! sup)
                    "swarm-1"
                    {:event :tool-call
                     :attempt-id attempt-id
                     :call-id "tool-1"
                     :name "cat"
                     :output-index 0})]
        (is (:ok? result)
            (str "tool call was rejected: " (pr-str (:error result))))
        (is (some? @executed)
            "the executor received the live actor attempt")
        (is (= :slot-a (:slot-id (:slot @executed))))
        (is (= "cat" (get-in @executed [:event :name])))
        (is (= 1 (count (filter #(= :model/start (:type %))
                                @started-effects)))
            "the continuation starts exactly one next turn")
        (let [state (get-in (service/runtime s "swarm-1") [:actor :state])
              context (get-in state
                              [:actors :tenures "fleet-swarm-1"
                               :slots :slot-a :context])]
          (is (some #(= "tool-1" (:tool-call-id %)) context))
          (is (= :tool-call
                 (get-in state [:actors :calls attempt-id :status])))
          (is (= :completed
                 (get-in state [:actors :calls attempt-id
                                :tool-call-status]))))))))

(deftest launch-does-not-overwrite-an-actor-event-from-the-launcher
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [accepted (atom nil)
          launcher
          (fn [{:keys [swarm-id actor service]}]
            (let [effect (first (filter #(= :model/start (:type %))
                                        (:effects actor)))
                  attempt-id (:attempt-id effect)]
              (reset! accepted
                      (service/accept-actor-event!
                       service swarm-id attempt-id
                       {:event :completed :text "during-launch"}))
              true))
          started (start-service! root launcher
                                  {:models {:main "main" :task "task"}
                                   :slot-ids [:slot-a :slot-b]})]
      (is (:ok? started))
      (let [s (:service started)
            _ (is (:ok? (service/fork! s [(slice "a")])))
            state (get-in (service/runtime s "swarm-1") [:actor :state])
            completed (keep (fn [[id call]]
                              (when (= :completed (:status call))
                                id))
                            (get-in state [:actors :calls]))
            context (get-in state [:actors :tenures "fleet-swarm-1"
                                   :slots :slot-a :context])]
        (is (:ok? @accepted))
        (is (= 1 (count completed)))
        (is (some #(= "during-launch" (:text %))
                  (filter #(= :assistant (:role %)) context))
            "the launcher's event survived the launch publication")))))

(deftest concurrent-terminal-events-are-serialized-without-lost-updates
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [started (start-service!
                   root
                   (fn [_] true)
                   {:models {:main "main" :task "task"}
                    :slot-ids [:slot-a :slot-b]})]
      (is (:ok? started))
      (let [s (:service started)
            _ (is (:ok? (service/fork! s [(slice "a")])))
            actor (get-in (service/runtime s "swarm-1") [:actor :state])
            attempt-a (get-in actor
                              [:actors :tenures "fleet-swarm-1"
                               :slots :slot-a :attempt-id])
            attempt-b (get-in actor
                              [:actors :tenures "fleet-swarm-1"
                               :slots :slot-b :attempt-id])
            a-inside (promise)
            release-a (promise)
            original mission-actors/accept-model-event
            result-a (atom nil)
            result-b (atom nil)
            a-thread (Thread.
                      (reify Runnable
                        (run [_]
                          (reset! result-a
                                  (service/accept-actor-event!
                                   s "swarm-1" attempt-a
                                   {:event :completed :text "a"})))))
            b-thread (Thread.
                      (reify Runnable
                        (run [_]
                          (reset! result-b
                                  (service/accept-actor-event!
                                   s "swarm-1" attempt-b
                                   {:event :completed :text "b"})))))]
        (with-redefs [mission-actors/accept-model-event
                      (fn [mission actors attempt-id event]
                        (when (= attempt-id attempt-a)
                          (deliver a-inside true)
                          (deref release-a 5000 ::timeout))
                        (original mission actors attempt-id event))]
          (try
            (.start a-thread)
            (is (= true (deref a-inside 2000 ::timeout)))
            (let [^java.util.concurrent.locks.ReentrantLock lock
                  (get @(:actor-locks s) "swarm-1")]
              (is (instance? java.util.concurrent.locks.ReentrantLock lock))
              (when lock
                (.start b-thread)
                (is (loop [n 0]
                      (cond
                        (.hasQueuedThread lock b-thread) true
                        (>= n 400) false
                        :else (do (Thread/sleep 5) (recur (inc n)))))
                    "the second event waits for the swarm's actor monitor")))
            (finally
              (deliver release-a true)
              (.join a-thread 5000)
              (.join b-thread 5000))))
        (is (:ok? @result-a))
        (is (:ok? @result-b))
        (let [state (get-in (service/runtime s "swarm-1") [:actor :state])
              slots (get-in state [:actors :tenures "fleet-swarm-1" :slots])]
          (is (= ["a"]
                 (->> (get-in slots [:slot-a :context])
                      (filter #(= :assistant (:role %)))
                      (mapv :text)))
              "the first completion survived")
          (is (= ["b"]
                 (->> (get-in slots [:slot-b :context])
                      (filter #(= :assistant (:role %)))
                      (mapv :text)))
              "the second completion survived"))))))

(deftest concurrent-wakes-do-not-double-dispatch-model-turns
  (support/with-temp-dir [root "hc-fleet-service-"]
    (let [started (start-service!
                   root
                   (fn [_] true)
                   {:models {:main "main" :task "task"}
                    :slot-ids [:slot-a :slot-b]})]
      (is (:ok? started))
      (let [s (:service started)
            _ (is (:ok? (service/fork! s [(slice "a")])))
            actor (get-in (service/runtime s "swarm-1") [:actor :state])
            attempt-a (get-in actor
                              [:actors :tenures "fleet-swarm-1"
                               :slots :slot-a :attempt-id])
            attempt-b (get-in actor
                              [:actors :tenures "fleet-swarm-1"
                               :slots :slot-b :attempt-id])
            _ (is (:ok? (service/accept-actor-event!
                         s "swarm-1" attempt-a
                         {:event :completed :text "a"})))
            _ (is (:ok? (service/accept-actor-event!
                         s "swarm-1" attempt-b
                         {:event :completed :text "b"})))
            wake-inside (promise)
            release-wake (promise)
            first? (atom true)
            original mission-actors/wake-all
            result-a (atom nil)
            result-b (atom nil)
            a-thread (Thread.
                      (reify Runnable
                        (run [_]
                          (reset! result-a
                                  (service/wake-actors! s "swarm-1")))))
            b-thread (Thread.
                      (reify Runnable
                        (run [_]
                          (reset! result-b
                                  (service/wake-actors! s "swarm-1")))))]
        (with-redefs [mission-actors/wake-all
                      (fn [mission actors tenure-id]
                        (when (compare-and-set! first? true false)
                          (deliver wake-inside true)
                          (deref release-wake 5000 ::timeout))
                        (original mission actors tenure-id))]
          (try
            (.start a-thread)
            (is (= true (deref wake-inside 2000 ::timeout)))
            (let [^java.util.concurrent.locks.ReentrantLock lock
                  (get @(:actor-locks s) "swarm-1")]
              (is (instance? java.util.concurrent.locks.ReentrantLock lock))
              (when lock
                (.start b-thread)
                (is (loop [n 0]
                      (cond
                        (.hasQueuedThread lock b-thread) true
                        (>= n 400) false
                        :else (do (Thread/sleep 5) (recur (inc n)))))
                    "the second wake waits for the swarm's actor monitor")))
            (finally
              (deliver release-wake true)
              (.join a-thread 5000)
              (.join b-thread 5000))))
        (is (:ok? @result-a))
        (is (:ok? @result-b))
        (let [effects (concat (:effects @result-a) (:effects @result-b))
              starts (filter #(= :model/start (:type %)) effects)]
          (is (= 2 (count starts))
              "each idle slot starts once, not once per concurrent wake")
          (is (= #{"slot-a" "slot-b"}
                 (set (map (comp name :slot-id) starts)))))))))

(deftest observation-summary-is-bounded-and-detail-free
  (let [events (mapv (fn [i] {:type :result-queued :i i})
                     (range 0 (inc service/max-observation-events)))
        observation
        {:mission-id "m"
         :fleet-version 2
         :master-version 1
         :swarms
         [{:swarm-id "swarm-1"
           :slice-id "a"
           :worktree "/worktrees/swarm-1"
           :status :joined
           :report "full report"
           :board {:tasks {:t1 {:goal "private"}}}}
          {:swarm-id "swarm-2"
           :slice-id "b"
           :worktree "/worktrees/swarm-2"
           :status :running
           :private-detail "drop"
           :board {:tasks {:t2 {:goal "private"}}}}]
         :events events}]
    (with-redefs [service/observation (fn [_] observation)]
      (let [summary (service/observation-summary :svc)]
        (is (= service/max-observation-events (count (:events summary))))
        (is (= "full report" (get-in summary [:swarms 0 :report])))
        (is (nil? (get-in summary [:swarms 0 :board])))
        (is (nil? (get-in summary [:swarms 1 :board])))
        (is (nil? (get-in summary [:swarms 1 :private-detail])))))))
