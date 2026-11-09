(ns hc.server.app.steward-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.store :as owner-store]
            [hc.compose.steward :as steward]
            [hc.server.app.steward-runtime :as runtime])
  (:import (java.util.concurrent Executor)))

;; ---------------------------------------------------------------------------
;; Fixtures

(def sha-1 (apply str (repeat 40 "a")))
(def sha-2 (apply str (repeat 40 "b")))

(defn- git-commit
  [commit parents]
  {:kind :git-commit
   :commit commit
   :parents parents
   :changed-paths ["src/app.clj"]
   :subject (str "commit " commit)})

(defn- candidate
  [evidence-id]
  {:candidate-id "cand-1"
   :title "Harden startup"
   :consequence 0.9
   :evidence-strength 0.9
   :dependency-cost 0.1
   :reversibility 0.8
   :opportunity-cost 0.1
   :demand 1
   :evidence-ids [evidence-id]
   :operator-aligned? true})

(def ^:private noop-executor
  (reify Executor
    (execute [_ _] nil)))

(defn- memory-steward-store
  "A Store with the same compare-and-set shape as `memory-store` but without
  the Owner-only `:session-id` validation."
  []
  (let [cell (atom {:version 0 :state nil})
        closed? (atom false)]
    (reify owner-store/Store
      (load-state [_] @cell)
      (commit! [_ expected next-state]
        (if @closed?
          {:ok? false :error {:code :STORE_CLOSED :message "closed"}}
          (let [[before after]
                (swap-vals! cell
                            (fn [{:keys [version] :as snapshot}]
                              (if (= version expected)
                                {:version (inc (long version))
                                 :state next-state}
                                snapshot)))
                actual (:version before)]
            (if (= actual expected)
              {:ok? true :version (:version after)}
              {:ok? false
               :error {:code :VERSION_CONFLICT
                       :message "stored version does not match"
                       :details {:expected expected :actual actual}}}))))
      (close! [_]
        (reset! closed? true)
        {:ok? true :closed? true}))))

(defn- fake-git
  "First call returns COMMITS; later calls return none."
  [head commits]
  (fn [{:keys [since]}]
    {:ok? true
     :head head
     :commits (if since [] commits)
     :master-version 1}))

(defn- start-runtime
  [store overrides]
  (runtime/start!
   (merge {:store store
           :clock (constantly 1000)
           :config {:slots-total 4 :min-spare 1}
           :tick-executor noop-executor}
          overrides)))

;; ---------------------------------------------------------------------------
;; A complete tick

(deftest full-tick-persists-and-dispatches-plan-start
  (let [store (memory-steward-store)
        plan-calls (atom [])
        started (start-runtime
                 store
                 {:git (fake-git sha-1 [(git-commit sha-1 [])])
                  :plan-start-fn
                  (fn [effect]
                    (swap! plan-calls conj effect)
                    {:ok? true :session-id "session-1"})})]
    (is (:ok? started))
    (let [rt (:runtime started)]
      (try
        (testing "evidence enters the committed Steward document"
          (let [result (runtime/tick! rt)]
            (is (:ok? result))
            (is (= 1 (get-in (owner-store/load-state store)
                             [:state :cognition :evidence-seq])))))

        (testing "a decision admits a mission and plan-start runs once"
          (runtime/submit-intent! rt {:revision 1
                                      :text "Improve reliability"
                                      :received-at 1})
          (runtime/tick! rt)
          (runtime/submit-decision! rt
                                    {:request-id "req-1"
                                     :candidates
                                     [(candidate (str "git:" sha-1))]})
          (let [result (runtime/tick! rt)
                stored (:state (owner-store/load-state store))
                mission-id (first (keys (:missions (:docket stored))))]
            (is (:ok? result))
            (is (= 1 (count @plan-calls)))
            (is (= mission-id (:mission-id (first @plan-calls))))
            (is (= "session-1"
                   (get-in stored [:links mission-id :session-id])))
            (is (= :planning
                   (get-in stored [:docket :missions mission-id :status])))))

        (testing "a repeated identical intent neither changes nor writes"
          (let [before (owner-store/load-state store)]
            (runtime/submit-intent! rt {:revision 1
                                        :text "Improve reliability"
                                        :received-at 1})
            (runtime/tick! rt)
            (is (= (:version before)
                   (:version (owner-store/load-state store))))
            (is (= (:state before)
                   (:state (owner-store/load-state store))))))

        (testing "snapshot stays bounded"
          (let [snap (runtime/snapshot rt)]
            (is (map? (:steward snap)))
            (is (not (contains? (:steward snap) :cognition)))
            (is (not (contains? snap :evidence)))
            (is (map? (:pending snap)))))

        (testing "health reports the running runtime"
          (is (= :started (:status (runtime/health rt)))))
        (finally
          (runtime/stop! rt)
          (is (= :stopped (:status (runtime/health rt)))))))))

(deftest submitted-evidence-is-observed
  (let [store (memory-steward-store)
        rt (:runtime (start-runtime store {}))]
    (try
      (let [queued (runtime/submit-evidence! rt [(git-commit sha-2 [])])
            result (runtime/tick! rt)
            stored (:state (owner-store/load-state store))]
        (is (:ok? queued))
        (is (:ok? result))
        (is (contains? (get-in stored [:cognition :evidence])
                       (str "git:" sha-2)))
        (is (= 1 (get-in stored [:cognition :evidence-seq]))))
      (finally (runtime/stop! rt)))))

;; ---------------------------------------------------------------------------
;; Compare-and-set

(deftest cas-conflict-reloads-and-preserves-the-other-writer
  (let [inner (memory-steward-store)
        remaining (atom 1)
        racing (reify owner-store/Store
                 (load-state [_] (owner-store/load-state inner))
                 (commit! [_ expected next-state]
                   (if (compare-and-set! remaining 1 0)
                     (let [other (assoc (steward/initial-state)
                                        :writer-marker "other")
                           written (owner-store/commit! inner expected other)]
                       (if (:ok? written)
                         {:ok? false
                          :error {:code :VERSION_CONFLICT
                                  :message "simulated race"
                                  :details {:expected expected
                                            :actual (:version written)}}}
                         written))
                     (owner-store/commit! inner expected next-state)))
                 (close! [_] (owner-store/close! inner)))
        started (start-runtime racing {})
        rt (:runtime started)]
    (is (:ok? started))
    (try
      (runtime/submit-intent! rt {:revision 1
                                  :text "Improve reliability"
                                  :received-at 1})
      (let [result (runtime/tick! rt)
            stored (:state (owner-store/load-state inner))]
        (is (:ok? result))
        (is (= "other" (:writer-marker stored)))
        (is (= "Improve reliability" (get-in stored [:intent :text])))
        (is (zero? @(:persist-conflicts rt))))
      (finally (runtime/stop! rt)))))

(deftest repeated-cas-conflict-is-abandoned-and-counted
  (let [inner (memory-steward-store)
        always-conflicting
        (reify owner-store/Store
          (load-state [_] (owner-store/load-state inner))
          (commit! [_ _ _]
            {:ok? false
             :error {:code :VERSION_CONFLICT :message "always"}})
          (close! [_] (owner-store/close! inner)))
        started (start-runtime always-conflicting {})
        rt (:runtime started)]
    (is (:ok? started))
    (try
      (runtime/submit-intent! rt {:revision 1
                                  :text "Improve reliability"
                                  :received-at 1})
      (let [result (runtime/tick! rt)
            snap (runtime/snapshot rt)]
        (is (false? (:ok? result)))
        (is (= 1 @(:persist-conflicts rt)))
        (is (= :persist-conflict (get-in snap [:last-error :code])))
        (is (= {:version 0 :state nil}
               (owner-store/load-state inner))))
      (finally (runtime/stop! rt)))))

;; ---------------------------------------------------------------------------
;; Failures and scheduling

(deftest git-failure-is-recorded-without-deciding
  (let [store (memory-steward-store)
        started (start-runtime
                 store
                 {:git (fn [_] (throw (ex-info "git down" {:code :GIT_DOWN})))})
        rt (:runtime started)]
    (is (:ok? started))
    (try
      ;; First tick settles `:last-observation`.
      (runtime/tick! rt)
      (let [before (owner-store/load-state store)
            result (runtime/tick! rt)
            after (owner-store/load-state store)
            snap (runtime/snapshot rt)]
        (is (= (:version before) (:version after)))
        (is (= (:state before) (:state after)))
        (is (= :STEWARD_GIT_OBSERVATION_FAILED
               (get-in snap [:last-error :code])))
        (is (some #(= :STALE_OBSERVATION (:reason %))
                  (:skipped result))))
      (finally (runtime/stop! rt)))))

(deftest on-join-result-returns-before-the-tick-finishes
  (let [started (start-runtime
                 (memory-steward-store)
                 {:git (fn [_]
                         (Thread/sleep 1000)
                         {:ok? true :head sha-1 :commits []})
                  ;; Use the runtime's own daemon executor so a real slow
                  ;; tick runs behind `on-join-result!`.
                  :tick-executor nil})
        rt (:runtime started)]
    (is (:ok? started))
    (try
      (let [started-at (System/nanoTime)
            result (runtime/on-join-result!
                    rt nil {:swarm-id "sw1" :ff-commit sha-2} nil)
            elapsed-ms (/ (- (System/nanoTime) started-at) 1000000.0)]
        (is (:ok? result))
        (is (< elapsed-ms 100.0)))
      (finally (runtime/stop! rt)))))

;; ---------------------------------------------------------------------------
;; Stop boundaries

(defn- blocking-store
  "A Store whose commit! signals STARTED and then blocks for BLOCK-NS.

  The block ignores interrupts, so stop! must actually wait for it instead
  of relying on shutdownNow."
  [inner started block-ns]
  (reify owner-store/Store
    (load-state [_] (owner-store/load-state inner))
    (commit! [_ expected next-state]
      (deliver started true)
      (let [deadline (+ (System/nanoTime) block-ns)]
        (while (< (System/nanoTime) deadline)
          (try
            (Thread/sleep 10)
            (catch InterruptedException _ nil))))
      (owner-store/commit! inner expected next-state))
    (close! [_] (owner-store/close! inner))))

(deftest stop-waits-for-an-in-flight-commit-and-never-writes-after
  (let [inner (memory-steward-store)
        started (promise)
        commits (atom 0)
        store (let [base (blocking-store inner started 200000000)]
                (reify owner-store/Store
                  (load-state [_] (owner-store/load-state base))
                  (commit! [_ expected next-state]
                    (swap! commits inc)
                    (owner-store/commit! base expected next-state))
                  (close! [_] (owner-store/close! base))))
        started-runtime
        (start-runtime store {:config {:slots-total 4
                                       :min-spare 1
                                       :stop-timeout-ms 2000}})
        rt (:runtime started-runtime)
        tick-future (future (runtime/tick! rt))]
    (is (:ok? started-runtime))
    (try
      (is (= true (deref started 2000 ::timeout)))
      (let [started-at (System/nanoTime)
            stop-result (runtime/stop! rt)
            elapsed-ms (/ (- (System/nanoTime) started-at) 1000000.0)
            commits-at-stop @commits]
        (is (:ok? stop-result))
        (is (:stopped? stop-result))
        (is (:terminated? stop-result))
        (is (zero? (:pending-ticks stop-result)))
        (is (zero? (:pending-models stop-result)))
        (is (< elapsed-ms 2000.0))
        (is (map? (deref tick-future 2000 ::timeout)))
        (Thread/sleep 250)
        (is (= commits-at-stop @commits))
        (is (= :STEWARD_RUNTIME_STOPPED
               (get-in (runtime/tick! rt) [:error :code])))
        (is (= :STEWARD_RUNTIME_STOPPED
               (get-in (runtime/on-join-result!
                        rt nil {:swarm-id "sw1"} nil)
                       [:error :code])))
        (is (= :STEWARD_RUNTIME_STOPPED
               (get-in (runtime/submit-intent!
                        rt {:revision 1 :text "after stop" :received-at 1})
                       [:error :code])))
        (is (= commits-at-stop @commits)))
      (finally
        (runtime/stop! rt)
        (owner-store/close! store)))))

(deftest stop-timeout-is-explicit
  (let [inner (memory-steward-store)
        started (promise)
        store (blocking-store inner started 500000000)
        started-runtime
        (start-runtime store {:config {:slots-total 4
                                       :min-spare 1
                                       :stop-timeout-ms 50}})
        rt (:runtime started-runtime)
        tick-future (future (runtime/tick! rt))]
    (is (:ok? started-runtime))
    (try
      (is (= true (deref started 2000 ::timeout)))
      (let [started-at (System/nanoTime)
            stop-result (runtime/stop! rt)
            elapsed-ms (/ (- (System/nanoTime) started-at) 1000000.0)]
        (is (false? (:ok? stop-result)))
        (is (false? (:terminated? stop-result)))
        (is (= :STEWARD_STOP_TIMEOUT (get-in stop-result [:error :code])))
        (is (= 1 (:pending-ticks stop-result)))
        (is (zero? (:pending-models stop-result)))
        (is (< elapsed-ms 1000.0)))
      ;; Let the in-flight commit finish before closing the store.  On a
      ;; timeout the caller must not close it, exactly because this can happen.
      (is (map? (deref tick-future 2000 ::timeout)))
      (finally
        (runtime/stop! rt)
        (owner-store/close! store)))))

(deftest stop-waits-for-an-in-flight-model-task
  (let [inner (memory-steward-store)
        model-started (promise)
        model-fn
        (fn [effect]
          (when (= :model/meditate (:type effect))
            (deliver model-started true)
            (let [deadline (+ (System/nanoTime) 500000000)]
              (while (< (System/nanoTime) deadline)
                (try
                  (Thread/sleep 10)
                  (catch InterruptedException _ nil)))))
          nil)
        started-runtime
        (start-runtime inner
                       {:git (fake-git sha-1 [(git-commit sha-1 [])])
                        :model-fn model-fn
                        :config {:slots-total 4
                                 :min-spare 1
                                 :stop-timeout-ms 2000}})
        rt (:runtime started-runtime)]
    (is (:ok? started-runtime))
    (try
      (is (:ok? (runtime/tick! rt)))
      (is (= true (deref model-started 2000 ::timeout)))
      (let [started-at (System/nanoTime)
            stop-result (runtime/stop! rt)
            elapsed-ms (/ (- (System/nanoTime) started-at) 1000000.0)]
        (is (:ok? stop-result))
        (is (:terminated? stop-result))
        (is (zero? (:pending-ticks stop-result)))
        (is (zero? (:pending-models stop-result)))
        (is (< elapsed-ms 2000.0)))
      (finally
        (runtime/stop! rt)
        (owner-store/close! inner)))))

;; ---------------------------------------------------------------------------
;; Pending input recovery

(deftest failed-persist-restores-pending-and-retries
  (let [inner (memory-steward-store)
        fail? (atom true)
        store (reify owner-store/Store
                (load-state [_] (owner-store/load-state inner))
                (commit! [_ expected next-state]
                  (if @fail?
                    {:ok? false
                     :error {:code :VERSION_CONFLICT
                             :message "forced conflict"}}
                    (owner-store/commit! inner expected next-state)))
                (close! [_] (owner-store/close! inner)))
        started-runtime (start-runtime store {})
        rt (:runtime started-runtime)]
    (is (:ok? started-runtime))
    (try
      (is (:ok? (runtime/submit-intent!
                 rt {:revision 1
                     :text "Improve reliability"
                     :received-at 1})))
      (is (= 1 (:pending-count (runtime/snapshot rt))))
      (let [failed (runtime/tick! rt)
            snap (runtime/snapshot rt)]
        (is (false? (:ok? failed)))
        (is (:restored? failed))
        (is (= :VERSION_CONFLICT (get-in failed [:error :code])))
        (is (= 1 (:pending-count snap)))
        (is (zero? (:dropped-count snap)))
        (is (= {:version 0 :state nil}
               (owner-store/load-state inner))))
      (reset! fail? false)
      (let [succeeded (runtime/tick! rt)
            stored (:state (owner-store/load-state inner))]
        (is (:ok? succeeded))
        (is (= "Improve reliability" (get-in stored [:intent :text])))
        (is (= 0 (:pending-count (runtime/snapshot rt)))))
    (finally
      (runtime/stop! rt)
      (owner-store/close! store)))))

;; ---------------------------------------------------------------------------
;; Plan-stall visibility

(deftest snapshot-exposes-bounded-stalled-missions
  (let [store (memory-steward-store)
        mission-ids (mapv #(str "mission-" %) (range 40))
        missions (into {}
                       (map (fn [mission-id]
                              [mission-id {:mission-id mission-id
                                           :status :planning}])
                            mission-ids))
        links (into {}
                    (map (fn [mission-id]
                           [mission-id {:mission-id mission-id
                                        :status :planning
                                        :session-id (str "plan-" mission-id)
                                        :plan-started-at 0}])
                         mission-ids))
        state (-> (steward/initial-state)
                  (assoc-in [:docket :missions] missions)
                  (assoc :links links))]
    (is (:ok? (owner-store/commit! store 0 state)))
    (let [started-runtime
          (start-runtime store
                         {:clock (constantly 600001)
                          :config {:slots-total 4
                                   :min-spare 1
                                   :plan-stall-ms 600000}})
          rt (:runtime started-runtime)]
      (is (:ok? started-runtime))
      (try
        (let [stalled (:stalled-missions (runtime/snapshot rt))]
          (is (= 32 (count stalled)))
          (is (every? #(and (string? (:mission-id %))
                            (number? (:stalled-ms %)))
                      stalled))
          (is (every? #(= 600001 (:stalled-ms %)) stalled)))
        (finally
          (runtime/stop! rt)
          (owner-store/close! store))))))

(deftest invalid-plan-stall-threshold-is-refused
  (let [result (runtime/start! {:store (memory-steward-store)
                                :config {:plan-stall-ms 0}})]
    (is (false? (:ok? result)))
    (is (= :STEWARD_RUNTIME_INVALID_OPTIONS
           (get-in result [:error :code])))
    (is (= 0 (get-in result [:error :details :plan-stall-ms])))))
