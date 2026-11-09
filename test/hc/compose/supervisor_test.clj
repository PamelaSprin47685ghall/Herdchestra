(ns hc.compose.supervisor-test
  "Deterministic and property tests for the process-level supervisor.

  Every test stops its supervisor in a `finally`, so a failed assertion
  cannot leak a worker thread into the next test.  Await bounds are generous:
  the assertions check ordering and honesty, not wall-clock speed."
  (:require [clojure.test :refer [are deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.supervisor :as supervisor])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(defn- await-until
  [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))]
    (loop []
      (cond
        (pred) true
        (< deadline (System/nanoTime)) false
        :else (do (Thread/sleep 5) (recur))))))

(defn- lifecycle-child
  [id & {:as options}]
  (merge {:id id
          :start! (fn [_] {:id id})
          :stop! (fn [_] nil)}
         options))

(defn- periodic-child
  [id tick! & {:as options}]
  (merge {:id id
          :start! (fn [_] {:id id})
          :stop! (fn [_] nil)
          :tick! tick!
          :interval-ms 60000
          :backoff-ms [10 20]}
         options))

(defn- child-state
  [sup id]
  (get-in (supervisor/status sup) [:children id]))

;; ---------------------------------------------------------------------------
;; Lifecycle ordering

(deftest start-and-stop-follow-declaration-order
  (let [events (atom [])
        make (fn [id]
               (lifecycle-child
                id
                :start! (fn [_] (swap! events conj [:start id]) {:id id})
                :stop! (fn [_] (swap! events conj [:stop id]))))
        started (supervisor/start! {:children [(make :a) (make :b) (make :c)]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (is (= [:a :b :c] (:started started)))
      (is (empty? (:degraded started)))
      (is (= [[:start :a] [:start :b] [:start :c]] @events))
      (let [stopped (supervisor/stop! sup)]
        (is (true? (:ok? stopped)))
        (is (= [:c :b :a] (:stopped stopped)))
        (is (empty? (:timed-out stopped)))
        (is (= [[:start :a] [:start :b] [:start :c]
                [:stop :c] [:stop :b] [:stop :a]]
               @events)))
      (finally
        (supervisor/stop! sup)))))

(deftest stop-is-idempotent
  (let [stops (atom 0)
        started (supervisor/start!
                 {:children [(lifecycle-child
                              :only
                              :stop! (fn [_] (swap! stops inc)))]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (let [first (supervisor/stop! sup)
            second (supervisor/stop! sup)]
        (is (true? (:ok? first)))
        (is (= first second))
        (is (= 1 @stops) "the stop callback ran once"))
      (is (= :stopped (:state (child-state sup :only))))
      (is (false? (:running? (supervisor/status sup))))
      (finally
        (supervisor/stop! sup)))))

(deftest a-critical-start-failure-rolls-back-started-siblings
  (let [events (atom [])
        started (supervisor/start!
                 {:children
                  [(lifecycle-child
                    :a
                    :start! (fn [_] (swap! events conj :start-a) :a)
                    :stop! (fn [_] (swap! events conj :stop-a)))
                   (lifecycle-child
                    :b
                    :critical? true
                    :start! (fn [_] (throw (ex-info "critical boot" {})))
                    :stop! (fn [_] (swap! events conj :stop-b)))
                   (lifecycle-child
                    :c
                    :start! (fn [_] (swap! events conj :start-c) :c)
                    :stop! (fn [_] (swap! events conj :stop-c)))]})]
    (is (false? (:ok? started)))
    (is (= :CRITICAL_CHILD_START_FAILED (get-in started [:error :code])))
    (is (= :b (get-in started [:error :details :child-id])))
    (is (= [:start-a :stop-a] @events)
        "the started sibling stops and the remaining child never starts")
    (is (nil? (:supervisor started)))))

;; ---------------------------------------------------------------------------
;; Failure isolation and recovery

(deftest a-failing-tick-does-not-stop-a-sibling
  (let [good-ticks (atom 0)
        bad-ticks (atom 0)
        started (supervisor/start!
                 {:children
                  [(periodic-child
                    :bad
                    (fn [_]
                      (swap! bad-ticks inc)
                      (throw (ex-info "tick boom" {})))
                    :interval-ms 5
                    :backoff-ms [5 10])
                   (periodic-child
                    :good
                    (fn [_] (swap! good-ticks inc))
                    :interval-ms 5
                    :backoff-ms [5 10])]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (is (await-until #(and (>= @good-ticks 3) (>= @bad-ticks 2)) 5000))
      (is (= :running (:state (child-state sup :good))))
      (is (= :backing-off (:state (child-state sup :bad))))
      (is (pos? (long (:failures (child-state sup :bad)))))
      (is (some? (:last-error (child-state sup :bad))))
      (finally
        (supervisor/stop! sup)))))

(deftest a-failed-periodic-start-is-retried-then-runs
  (let [attempts (atom 0)
        ticks (atom 0)
        started (supervisor/start!
                 {:children
                  [(periodic-child
                    :flaky
                    (fn [_] (swap! ticks inc))
                    :start! (fn [_]
                              (when (< (swap! attempts inc) 3)
                                (throw (ex-info "start boom" {})))
                              :ok)
                    :interval-ms 10
                    :backoff-ms [200 400])]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (is (= [:flaky] (:degraded started)))
      (is (= :backing-off (:state (child-state sup :flaky))))
      (is (await-until #(= :running (:state (child-state sup :flaky))) 5000))
      (is (= 3 @attempts))
      (is (await-until #(pos? @ticks) 5000))
      (is (= 0 (long (:consecutive-failures (child-state sup :flaky)))))
      (finally
        (supervisor/stop! sup)))))

(deftest a-start-result-with-ok-false-is-a-failure
  (let [started (supervisor/start!
                 {:children
                  [(lifecycle-child
                    :rejected
                    :start! (fn [_]
                              {:ok? false
                               :error {:code :CHILD_REJECTED
                                       :message "not today"}})
                    :restart :temporary)]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (let [child (child-state sup :rejected)]
        (is (= :failed (:state child)))
        (is (= :CHILD_REJECTED (get-in child [:last-error :code])))
        (is (= 1 (long (:failures child)))))
      (finally
        (supervisor/stop! sup)))))

(deftest a-lifecycle-child-restarts-after-an-abnormal-exit
  (let [alive (atom true)
        starts (atom 0)
        started (supervisor/start!
                 {:children
                  [(lifecycle-child
                    :svc
                    :start! (fn [_]
                              (swap! starts inc)
                              (reset! alive true)
                              alive)
                    :stop! (fn [_] nil)
                    :alive? (fn [handle] @handle)
                    :poll-ms 5
                    :restart :permanent
                    :backoff-ms [5 20])]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (is (await-until #(= 1 @starts) 5000))
      (reset! alive false)
      (is (await-until #(>= @starts 2) 5000))
      (is (await-until #(= :running (:state (child-state sup :svc))) 5000))
      (is (pos? (long (:failures (child-state sup :svc)))))
      (finally
        (supervisor/stop! sup)))))

(deftest a-normal-lifecycle-exit-with-transient-restart-does-not-restart
  (let [starts (atom 0)
        started (supervisor/start!
                 {:children
                  [(lifecycle-child
                    :job
                    :start! (fn [_]
                              (swap! starts inc)
                              (future :done))
                    :restart :transient
                    :backoff-ms [5 20])]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (is (await-until #(= :stopped (:state (child-state sup :job))) 5000))
      (is (= 1 @starts))
      (finally
        (supervisor/stop! sup)))))

(deftest an-abnormal-lifecycle-exit-with-transient-restart-restarts
  (let [starts (atom 0)
        started (supervisor/start!
                 {:children
                  [(lifecycle-child
                    :job
                    :start! (fn [_]
                              (swap! starts inc)
                              (future
                                (throw (ex-info "worker died" {}))))
                    :restart :transient
                    :backoff-ms [5 20])]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (is (await-until #(>= @starts 2) 5000))
      (is (pos? (long (:failures (child-state sup :job)))))
      (finally
        (supervisor/stop! sup)))))

;; ---------------------------------------------------------------------------
;; Status honesty

(deftest a-child-that-cannot-start-is-failed-and-not-running
  (let [started (supervisor/start!
                 {:children
                  [(lifecycle-child
                    :broken
                    :start! (fn [_] (throw (ex-info "boot boom" {})))
                    :restart :temporary)]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (let [child (child-state sup :broken)]
        (is (= :failed (:state child)))
        (is (= 1 (long (:failures child))))
        (is (= 1 (long (:consecutive-failures child))))
        (is (= 0 (long (:backoff-ms child))))
        (is (= :CHILD_START_FAILED (get-in child [:last-error :code])))
        (is (= :start (get-in child [:last-error :phase])))
        (is (nil? (:last-run-at child))))
      (finally
        (supervisor/stop! sup)))))

(deftest a-failing-tick-is-backed-off-and-not-running
  (let [started (supervisor/start!
                 {:children
                  [(periodic-child
                    :flaky
                    (fn [_] (throw (ex-info "tick boom" {})))
                    :interval-ms 60000
                    :backoff-ms [3000 6000])]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (let [receipt (supervisor/trigger! sup :flaky)]
        (is (false? (:ok? receipt)))
        (is (= :tick (:action receipt)))
        (is (= :CHILD_TICK_FAILED (get-in receipt [:error :code]))))
      (let [child (child-state sup :flaky)]
        (is (= :backing-off (:state child)))
        (is (= 1 (long (:failures child))))
        (is (= 1 (long (:consecutive-failures child))))
        (is (<= 3000 (long (:backoff-ms child)) 6000))
        (is (= :CHILD_TICK_FAILED (get-in child [:last-error :code])))
        (is (= :tick (get-in child [:last-error :phase]))))
      (finally
        (supervisor/stop! sup)))))

(deftest status-reports-the-documented-child-shape
  (let [started (supervisor/start! {:children [(lifecycle-child :only)]})
        sup (:supervisor started)]
    (try
      (let [status (supervisor/status sup)
            child (get-in status [:children :only])]
        (is (true? (:running? status)))
        (is (false? (:stopped? status)))
        (is (= [:only] (keys (:children status))))
        (is (every? #(contains? child %) [:id :state :runs :failures
                                          :consecutive-failures :backoff-ms
                                          :last-run-at :last-error]))
        (is (= :only (:id child)))
        (is (= :running (:state child)))
        (is (= 1 (long (:runs child))))
        (is (zero? (long (:failures child)))))
      (finally
        (supervisor/stop! sup)))))

;; ---------------------------------------------------------------------------
;; Trigger

(deftest trigger-runs-one-tick-synchronously
  (let [ticks (atom 0)
        started (supervisor/start!
                 {:children
                  [(periodic-child
                    :loop
                    (fn [{:keys [handle]}]
                      (swap! ticks inc)
                      {:handle handle}))]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (let [receipt (supervisor/trigger! sup :loop)]
        (is (true? (:ok? receipt)))
        (is (= :tick (:action receipt)))
        (is (= 1 @ticks) "the tick finished before trigger! returned")
        (is (= {:handle {:id :loop}} (:result receipt))))
      (is (= :CHILD_NOT_FOUND
             (get-in (supervisor/trigger! sup :missing) [:error :code])))
      (supervisor/stop! sup)
      (is (= :SUPERVISOR_STOPPED
             (get-in (supervisor/trigger! sup :loop) [:error :code])))
      (finally
        (supervisor/stop! sup)))))

(deftest trigger-restarts-a-lifecycle-child
  (let [starts (atom 0)
        stops (atom 0)
        started (supervisor/start!
                 {:children
                  [(lifecycle-child
                    :svc
                    :start! (fn [_] (swap! starts inc) {:n @starts})
                    :stop! (fn [_] (swap! stops inc))
                    :restart :temporary)]})
        sup (:supervisor started)]
    (try
      (is (= 1 @starts))
      (let [receipt (supervisor/trigger! sup :svc)]
        (is (true? (:ok? receipt)))
        (is (= :restart (:action receipt)))
        (is (= 2 @starts))
        (is (= 1 @stops)))
      (is (= :running (:state (child-state sup :svc))))
      (finally
        (supervisor/stop! sup)))))

(deftest trigger-revives-a-failed-periodic-child
  (let [attempts (atom 0)
        ticks (atom 0)
        started (supervisor/start!
                 {:children
                  [(periodic-child
                    :once
                    (fn [_] (swap! ticks inc))
                    :start! (fn [_]
                              (if (= 1 (swap! attempts inc))
                                (throw (ex-info "boot boom" {}))
                                :ok))
                    :restart :temporary
                    :interval-ms 20)]})
        sup (:supervisor started)]
    (try
      (is (= :failed (:state (child-state sup :once))))
      (let [receipt (supervisor/trigger! sup :once)]
        (is (true? (:ok? receipt)))
        (is (= :start-and-tick (:action receipt)))
        (is (= 1 @ticks)))
      (is (= :running (:state (child-state sup :once))))
      (is (await-until #(>= @ticks 2) 5000)
          "the revived worker resumes the periodic schedule")
      (finally
        (supervisor/stop! sup)))))

;; ---------------------------------------------------------------------------
;; Tick return contract

(deftest tick-returning-ok-false-is-a-failed-tick
  (let [bad-ticks (atom 0)
        started (supervisor/start!
                 {:children
                  [(periodic-child
                    :bad
                    (fn [_]
                      (swap! bad-ticks inc)
                      {:ok? false
                       :error {:code :X :message "sweep failed"}})
                    :interval-ms 60000
                    :backoff-ms [3000 6000])
                   (periodic-child :good (fn [_] {:ok? true}))]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (let [receipt (supervisor/trigger! sup :bad)]
        (is (false? (:ok? receipt)))
        (is (= :tick (:action receipt))))
      (let [bad (child-state sup :bad)]
        (is (= :backing-off (:state bad)))
        (is (zero? (long (:runs bad))))
        (is (= 1 (long (:failures bad))))
        (is (= 1 (long (:consecutive-failures bad))))
        (is (= :X (get-in bad [:last-error :code])))
        (is (= 1 @bad-ticks)))
      (is (= :running (:state (child-state sup :good))))
      (finally
        (supervisor/stop! sup)))))

(deftest tick-returning-ok-false-without-error-synthesizes-one
  (let [started (supervisor/start!
                 {:children
                  [(periodic-child
                    :bad
                    (fn [_] {:ok? false})
                    :interval-ms 60000
                    :backoff-ms [3000 6000])]})
        sup (:supervisor started)]
    (try
      (is (false? (:ok? (supervisor/trigger! sup :bad))))
      (let [bad (child-state sup :bad)]
        (is (= :backing-off (:state bad)))
        (is (zero? (long (:runs bad))))
        (is (= 1 (long (:failures bad))))
        (is (= :CHILD_TICK_FAILED (get-in bad [:last-error :code])))
        (is (= "child tick returned :ok? false"
               (get-in bad [:last-error :message]))))
      (finally
        (supervisor/stop! sup)))))

(deftest tick-returning-truthy-or-nil-is-a-success
  (let [values [{:ok? true} nil 42]
        index (atom 0)
        started (supervisor/start!
                 {:children
                  [(periodic-child
                    :loop
                    (fn [_]
                      (let [i @index]
                        (swap! index inc)
                        (nth values i))))]})
        sup (:supervisor started)]
    (try
      (doseq [value values]
        (let [receipt (supervisor/trigger! sup :loop)]
          (is (true? (:ok? receipt)))
          (is (= value (:result receipt)))))
      (let [child (child-state sup :loop)]
        (is (= :running (:state child)))
        (is (= 3 (long (:runs child))))
        (is (zero? (long (:failures child))))
        (is (zero? (long (:consecutive-failures child)))))
      (finally
        (supervisor/stop! sup)))))

(deftest failed-tick-then-success-resets-consecutive-failures
  (let [calls (atom 0)
        started (supervisor/start!
                 {:children
                  [(periodic-child
                    :loop
                    (fn [_]
                      (if (= 1 (swap! calls inc))
                        {:ok? false :error {:code :X :message "first"}}
                        {:ok? true}))
                    :interval-ms 60000
                    :backoff-ms [3000 6000])]})
        sup (:supervisor started)]
    (try
      (is (false? (:ok? (supervisor/trigger! sup :loop))))
      (is (= 1 (long (:consecutive-failures (child-state sup :loop)))))
      (is (= :backing-off (:state (child-state sup :loop))))
      (is (true? (:ok? (supervisor/trigger! sup :loop))))
      (let [child (child-state sup :loop)]
        (is (= :running (:state child)))
        (is (= 1 (long (:runs child))))
        (is (= 1 (long (:failures child))))
        (is (zero? (long (:consecutive-failures child))))
        (is (= :X (get-in child [:last-error :code]))
            "the last error stays historical"))
      (finally
        (supervisor/stop! sup)))))

;; ---------------------------------------------------------------------------
;; Stop waits, stop times out, and errors reach on-error

(deftest stop-waits-for-an-in-flight-tick
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        finished (atom 0)
        started (supervisor/start!
                 {:children
                  [(periodic-child
                    :slow
                    (fn [_]
                      (.countDown entered)
                      (.await release 5 TimeUnit/SECONDS)
                      (swap! finished inc))
                    :interval-ms 5)]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (is (.await entered 2 TimeUnit/SECONDS))
      (let [stop-future (future (supervisor/stop! sup {:timeout-ms 2000}))]
        (Thread/sleep 50)
        (is (not (realized? stop-future))
            "stop waits for the running tick")
        (.countDown release)
        (let [result (deref stop-future 3000 ::timeout)]
          (is (not= ::timeout result))
          (is (true? (:ok? result)))
          (is (= [:slow] (:stopped result)))
          (is (empty? (:timed-out result)))))
      (is (= 1 @finished))
      (is (= 1 (long (:runs (child-state sup :slow)))))
      (finally
        (.countDown release)
        (supervisor/stop! sup {:timeout-ms 1000})))))

(deftest stop-reports-a-tick-that-outlives-the-timeout
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        started (supervisor/start!
                 {:children
                  [(periodic-child
                    :blocked
                    (fn [_]
                      (.countDown entered)
                      (.await release 5 TimeUnit/SECONDS))
                    :interval-ms 5)]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (is (.await entered 2 TimeUnit/SECONDS))
      (let [result (supervisor/stop! sup {:timeout-ms 20})]
        (is (false? (:ok? result)))
        (is (= [:blocked] (:timed-out result)))
        (is (empty? (:stopped result)))
        (is (= [:blocked] (mapv :child (:failures result))))
        (is (= :CHILD_STOP_LOCK_TIMEOUT
               (get-in result [:failures 0 :error :code]))))
      (.countDown release)
      (is (await-until #(= :stopped (:state (child-state sup :blocked))) 3000)
          "the worker stops itself after the tick returns")
      (finally
        (.countDown release)
        (supervisor/stop! sup {:timeout-ms 1000})))))

(deftest a-child-stop-refusal-fails-the-whole-stop
  (let [bad-stops (atom 0)
        good-stops (atom 0)
        started (supervisor/start!
                 {:children
                  [(lifecycle-child
                    :bad
                    :stop! (fn [_]
                             (swap! bad-stops inc)
                             {:ok? false
                              :error {:code :STOP_UNCONFIRMED
                                      :message "writer still alive"}}))
                   (lifecycle-child
                    :good
                    :stop! (fn [_]
                             (swap! good-stops inc)
                             {:ok? true}))]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (let [result (supervisor/stop! sup)]
        (is (false? (:ok? result)))
        (is (= [:good :bad] (:stopped result)))
        (is (= 1 (count (:failures result))))
        (is (= :bad (:child (first (:failures result)))))
        (is (= :STOP_UNCONFIRMED
               (get-in (first (:failures result)) [:error :code])))
        (is (= 1 @bad-stops))
        (is (= 1 @good-stops)))
      (finally
        (supervisor/stop! sup)))))

(deftest stop-ok-requires-every-child-stop-to-confirm
  (are [failing expected-ok?]
      (let [children
            (mapv (fn [index]
                    (lifecycle-child
                     (keyword (str "child-" index))
                     :stop! (fn [_]
                              (if (< index failing)
                                {:ok? false
                                 :error {:code :STOP_UNCONFIRMED
                                         :message "not confirmed"}}
                                {:ok? true}))))
                  (range 3))
            started (supervisor/start! {:children children})
            sup (:supervisor started)]
        (try
          (let [result (supervisor/stop! sup)
                failed-ids (set (map :child (:failures result)))]
            (and (= expected-ok? (:ok? result))
                 (= failing (count (:failures result)))
                 (= (set (map #(keyword (str "child-" %))
                              (range failing)))
                    failed-ids)
                 (= 3 (count (:stopped result)))))
          (finally
            (supervisor/stop! sup))))
    0 true
    1 false
    2 false))

(deftest on-error-reports-failures-and-survives-a-throwing-handler
  (let [seen (atom [])
        started (supervisor/start!
                 {:on-error (fn [event]
                              (swap! seen conj event)
                              (throw (ex-info "handler boom" {})))
                  :children
                  [(periodic-child
                    :loop
                    (fn [_] (throw (ex-info "tick boom" {:code :TICK_BUG})))
                    :interval-ms 60000
                    :backoff-ms [3000 6000])]})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (is (false? (:ok? (supervisor/trigger! sup :loop))))
      (is (await-until #(seq @seen) 2000))
      (is (= :loop (:child-id (first @seen))))
      (is (= :tick (:phase (first @seen))))
      (is (= :TICK_BUG (get-in (first @seen) [:error :code])))
      (is (= :backing-off (:state (child-state sup :loop))))
      (finally
        (supervisor/stop! sup)))))

;; ---------------------------------------------------------------------------
;; Validation

(deftest invalid-options-and-child-specs-are-rejected
  (testing "children"
    (are [children code]
        (= code (get-in (supervisor/start! {:children children})
                        [:error :code]))
      :not-a-vector :CHILDREN_INVALID
      [] :CHILDREN_EMPTY
      [:not-a-map] :CHILD_SPEC_INVALID
      [{:id "a" :start! (fn [_]) :stop! (fn [_])}] :CHILD_ID_INVALID
      [{:id :a :stop! (fn [_])}] :CHILD_START_REQUIRED
      [{:id :a :start! (fn [_])}] :CHILD_STOP_REQUIRED
      [{:id :a :start! (fn [_]) :stop! (fn [_]) :tick! :nope
        :interval-ms 5}] :CHILD_TICK_INVALID
      [{:id :a :start! (fn [_]) :stop! (fn [_]) :tick! (fn [_])}]
      :CHILD_INTERVAL_REQUIRED
      [{:id :a :start! (fn [_]) :stop! (fn [_]) :interval-ms 5}]
      :CHILD_INTERVAL_WITHOUT_TICK
      [{:id :a :start! (fn [_]) :stop! (fn [_]) :alive? :nope}]
      :CHILD_ALIVE_INVALID
      [{:id :a :start! (fn [_]) :stop! (fn [_]) :tick! (fn [_])
        :interval-ms 5 :alive? (fn [_] true)}] :CHILD_ALIVE_NOT_PERIODIC
      [{:id :a :start! (fn [_]) :stop! (fn [_]) :poll-ms 0}]
      :CHILD_POLL_INVALID
      [{:id :a :start! (fn [_]) :stop! (fn [_]) :restart :sometimes}]
      :CHILD_RESTART_INVALID
      [{:id :a :start! (fn [_]) :stop! (fn [_]) :backoff-ms [10]}]
      :CHILD_BACKOFF_INVALID
      [{:id :a :start! (fn [_]) :stop! (fn [_]) :backoff-ms [20 10]}]
      :CHILD_BACKOFF_INVALID
      [{:id :a :start! (fn [_]) :stop! (fn [_]) :critical? :yes}]
      :CHILD_CRITICAL_INVALID))
  (testing "duplicate ids"
    (is (= :CHILD_ID_DUPLICATE
           (get-in (supervisor/start!
                    {:children [(lifecycle-child :a)
                                (lifecycle-child :a)]})
                   [:error :code]))))
  (testing "other options"
    (is (= :CLOCK_INVALID
           (get-in (supervisor/start!
                    {:children [(lifecycle-child :a)] :clock 42})
                   [:error :code])))
    (is (= :ON_ERROR_INVALID
           (get-in (supervisor/start!
                    {:children [(lifecycle-child :a)] :on-error :nope})
                   [:error :code]))))
  (testing "stop timeout"
    (is (= :STOP_TIMEOUT_INVALID
           (get-in (supervisor/stop! nil {:timeout-ms -1})
                   [:error :code])))))

;; ---------------------------------------------------------------------------
;; Pure backoff

(deftest backoff-delay-is-capped-and-never-spins
  (is (= 1 (supervisor/backoff-delay [1 1000] 1)))
  (is (= 8 (supervisor/backoff-delay [1 1000] 4)))
  (is (= 1000 (supervisor/backoff-delay [1 1000] 1000000)))
  (is (= 500 (supervisor/backoff-delay [500 30000] 0)))
  (is (thrown? clojure.lang.ExceptionInfo
               (supervisor/backoff-delay [10 1] 1))))

(defspec backoff-delay-is-monotone-and-capped 200
  (prop/for-all [min-ms (gen/choose 1 1000)
                 extra (gen/choose 0 200000)]
    (let [max-ms (+ min-ms extra)
          bounds [min-ms max-ms]
          delays (mapv #(supervisor/backoff-delay bounds %) (range 1 40))]
      (and (= min-ms (first delays))
           (every? #(and (<= min-ms %) (<= % max-ms)) delays)
           (every? true? (map <= delays (rest delays)))
           (= max-ms (peek delays))))))

;; ---------------------------------------------------------------------------
;; Properties over real supervisors

(defspec stop-order-is-the-reverse-of-start-order 25
  (prop/for-all [raw-ids (gen/vector (gen/choose 0 100) 1 6)]
    (let [ids (vec (distinct (map #(keyword (str "child-" %)) raw-ids)))
          events (atom [])
          children (mapv (fn [id]
                           (lifecycle-child
                            id
                            :start! (fn [_] (swap! events conj [:start id]) id)
                            :stop! (fn [_] (swap! events conj [:stop id]))))
                         ids)
          started (supervisor/start! {:children children})]
      (if-not (:ok? started)
        false
        (let [stop-result (supervisor/stop! (:supervisor started))]
          (and (true? (:ok? stop-result))
               (= (mapv (fn [id] [:start id]) ids)
                  (filterv #(= :start (first %)) @events))
               (= (mapv (fn [id] [:stop id]) (reverse ids))
                  (filterv #(= :stop (first %)) @events))
               (= (vec (reverse ids)) (:stopped stop-result))))))))

(defspec repeated-stop-runs-each-stop-callback-once 20
  (prop/for-all [child-count (gen/choose 1 5)]
    (let [stops (atom 0)
          children (mapv (fn [index]
                           (lifecycle-child
                            (keyword (str "c" index))
                            :stop! (fn [_] (swap! stops inc))))
                         (range child-count))
          started (supervisor/start! {:children children})]
      (if-not (:ok? started)
        false
        (let [sup (:supervisor started)
              first-stop (supervisor/stop! sup)
              second-stop (supervisor/stop! sup)]
          (and (true? (:ok? first-stop))
               (= first-stop second-stop)
               (= child-count @stops)
               (false? (:running? (supervisor/status sup)))))))))

(defspec a-failing-child-never-kills-a-sibling 15
  (prop/for-all [bad-index (gen/choose 0 2)
                 fail-kind (gen/elements [:start :tick])]
    (let [good-ticks (atom 0)
          children
          (mapv (fn [index]
                  (let [bad? (= index bad-index)
                        id (keyword (str "child-" index))]
                    (if bad?
                      (cond-> {:id id
                               :start! (if (= fail-kind :start)
                                         (fn [_]
                                           (throw (ex-info "bad start" {})))
                                         (fn [_] :ok))
                               :stop! (fn [_] nil)
                               :backoff-ms [5 10]}
                        (= fail-kind :tick)
                        (assoc :tick! (fn [_]
                                        (throw (ex-info "bad tick" {})))
                               :interval-ms 5))
                      {:id id
                       :start! (fn [_] :ok)
                       :stop! (fn [_] nil)
                       :tick! (fn [_] (swap! good-ticks inc))
                       :interval-ms 5
                       :backoff-ms [5 10]})))
                (range 3))
          started (supervisor/start! {:children children})]
      (if-not (:ok? started)
        false
        (let [sup (:supervisor started)]
          (try
            (let [bad-id (keyword (str "child-" bad-index))]
              (and (await-until #(>= @good-ticks 2) 5000)
                   (await-until
                    #(pos? (long (:failures (child-state sup bad-id))))
                    5000)
                   (not= :running (:state (child-state sup bad-id)))))
            (finally
              (supervisor/stop! sup))))))))

(defspec tick-result-sequence-keeps-counters-honest 40
  (prop/for-all [events (gen/vector (gen/elements [:ok :fail :throw]) 0 12)]
    (let [queue (atom events)
          started
          (supervisor/start!
           {:children
            [(periodic-child
              :loop
              (fn [_]
                (let [event (first @queue)]
                  (swap! queue rest)
                  (case event
                    :ok {:ok? true}
                    :fail {:ok? false
                           :error {:code :X :message "fail"}}
                    :throw (throw (ex-info "boom" {})))))
              :interval-ms 60000
              :backoff-ms [60000 120000])]})]
      (if-not (:ok? started)
        false
        (let [sup (:supervisor started)]
          (try
            (let [receipts (mapv (fn [_]
                                   (supervisor/trigger! sup :loop))
                                 events)
                  successes (count (filter #(= :ok %) events))
                  failures (- (count events) successes)
                  trailing
                  (loop [remaining (reverse events)
                         streak 0]
                    (if (and (seq remaining) (not= :ok (first remaining)))
                      (recur (rest remaining) (inc streak))
                      streak))
                  child (child-state sup :loop)]
              (and (every? true?
                           (mapv (fn [event receipt]
                                   (if (= :ok event)
                                     (true? (:ok? receipt))
                                     (false? (:ok? receipt))))
                                 events receipts))
                   (= successes (long (:runs child)))
                   (= failures (long (:failures child)))
                   (= trailing (long (:consecutive-failures child)))
                   (<= (long (:consecutive-failures child))
                       (long (:failures child)))
                   (= (if (and (seq events) (not= :ok (peek events)))
                        :backing-off
                        :running)
                      (:state child))))
            (finally
              (supervisor/stop! sup))))))))

(defspec a-failed-child-is-never-reported-running 25
  (prop/for-all [fail-kind (gen/elements [:start :tick])
                 restart (gen/elements [:permanent :transient :temporary])]
    (let [started
          (supervisor/start!
           {:children
            [(cond-> {:id :child
                      :start! (if (= fail-kind :start)
                                (fn [_] (throw (ex-info "boot boom" {})))
                                (fn [_] :ok))
                      :stop! (fn [_] nil)
                      :restart restart
                      :backoff-ms [3000 6000]}
               (= fail-kind :tick)
               (assoc :tick! (fn [_]
                               (throw (ex-info "tick boom" {})))
                      :interval-ms 60000))]})]
      (if-not (:ok? started)
        false
        (let [sup (:supervisor started)]
          (try
            (let [receipt (when (= fail-kind :tick)
                            (supervisor/trigger! sup :child))
                  child (child-state sup :child)]
              (and (or (= fail-kind :start)
                       (false? (:ok? receipt)))
                   (not= :running (:state child))
                   (pos? (long (:failures child)))
                   (some? (:last-error child))))
            (finally
              (supervisor/stop! sup))))))))
