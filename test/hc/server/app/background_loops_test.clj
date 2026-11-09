(ns hc.server.app.background-loops-test
  "Adapter and real-supervisor tests for background loop child specs.

  Every supervisor is stopped in a `finally`.  The tests check ordering,
  failure isolation, rejected starts, idempotent bounded stop, omission
  order, and the adapter counters."
  (:require [clojure.test :refer [are deftest is testing]]
            [clojure.set :as set]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.supervisor :as supervisor]
            [hc.server.app.background-loops :as background]
            [hc.server.app.fleet-gc :as fleet-gc]
            [hc.server.app.retention-gc :as retention-gc]
            [hc.server.sessions.registry :as registry])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent CountDownLatch)))

(defn- ok-options
  ([] (ok-options (atom [])))
  ([events]
   {:steward-loop
    {:tick-fn (fn [] {:ok? true})
     :interval-ms 60000
     :start-fn (fn []
                 (swap! events conj [:start :steward-loop])
                 {:ok? true})
     :stop-fn (fn [_] (swap! events conj [:stop :steward-loop]))}

    :fleet-gc
    {:gc-fn (fn [_retention-ms] {:ok? true})
     :retention-ms 1000
     :interval-ms 60000
     :start-fn (fn []
                 (swap! events conj [:start :fleet-gc])
                 {:ok? true})
     :stop-fn (fn [_] (swap! events conj [:stop :fleet-gc]))}

    :retention-gc
    {:service-ref (atom {:service :retention})
     :purge-fn (fn [_service] {:ok? true})
     :blob-gc-fn (fn [_service] {:ok? true})
     :interval-ms 60000
     :start-fn (fn []
                 (swap! events conj [:start :retention-gc])
                 {:ok? true :service {:service :retention}})
     :stop-fn (fn [_] (swap! events conj [:stop :retention-gc]))}}))

(defn- child-state
  [sup id]
  (get-in (supervisor/status sup) [:children id]))

;; ---------------------------------------------------------------------------
;; Supervisor integration

(deftest all-three-children-start-in-order-and-stop-in-reverse
  (let [events (atom [])
        children (background/child-specs (ok-options events))
        started (supervisor/start! {:children children})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (is (= [:steward-loop :fleet-gc :retention-gc]
             (mapv :id children)))
      (is (= [:steward-loop :fleet-gc :retention-gc]
             (:started started)))
      (is (= [[:start :steward-loop]
              [:start :fleet-gc]
              [:start :retention-gc]]
             @events))
      (let [summaries (background/status children)]
        (is (= [:steward-loop :fleet-gc :retention-gc]
               (mapv :id summaries)))
        (is (every? true? (mapv :running? summaries))))
      (let [stopped (supervisor/stop! sup)]
        (is (true? (:ok? stopped)))
        (is (= [:retention-gc :fleet-gc :steward-loop]
               (:stopped stopped)))
        (is (= [[:start :steward-loop]
                [:start :fleet-gc]
                [:start :retention-gc]
                [:stop :retention-gc]
                [:stop :fleet-gc]
                [:stop :steward-loop]]
               @events))
        (is (every? false? (mapv :running? (background/status children)))))
      (finally
        (supervisor/stop! sup)))))

(deftest a-throwing-tick-back-offs-and-siblings-keep-running
  (let [children
        (background/child-specs
         (-> (ok-options)
             (assoc-in [:steward-loop :tick-fn]
                       (fn [] (throw (ex-info "steward boom" {}))))
             (assoc-in [:steward-loop :backoff-ms] [60000 120000])))
        steward-spec (first children)
        started (supervisor/start! {:children children})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (let [receipt (supervisor/trigger! sup :steward-loop)]
        (is (false? (:ok? receipt)))
        ;; The adapter catches the throw and returns :BACKGROUND_TICK_FAILED;
        ;; the supervisor preserves that bounded error.
        (is (= :BACKGROUND_TICK_FAILED (get-in receipt [:error :code]))))
      (let [child (child-state sup :steward-loop)]
        (is (= :backing-off (:state child)))
        (is (zero? (long (:runs child))))
        (is (= 1 (long (:failures child))))
        (is (= 1 (long (:consecutive-failures child))))
        (is (= :BACKGROUND_TICK_FAILED (get-in child [:last-error :code]))))
      (is (= :running (:state (child-state sup :fleet-gc))))
      (is (= :running (:state (child-state sup :retention-gc))))
      (let [adapter (background/status steward-spec)]
        (is (true? (:running? adapter)))
        (is (zero? (long (:runs adapter))))
        (is (= 1 (long (:failures adapter))))
        (is (= :BACKGROUND_TICK_FAILED (get-in adapter [:last-error :code]))))
      (finally
        (supervisor/stop! sup)))))

(deftest a-rejected-underlying-start-is-never-running
  (let [children
        (background/child-specs
         (-> (ok-options)
             (assoc-in [:steward-loop :restart] :temporary)
             (assoc-in [:steward-loop :start-fn]
                       (fn []
                         {:ok? false
                          :error {:code :BOOT_NO
                                  :message "no steward"}}))))
        steward-spec (first children)
        started (supervisor/start! {:children children})
        sup (:supervisor started)]
    (try
      (is (true? (:ok? started)))
      (is (= [:steward-loop] (:degraded started)))
      (let [child (child-state sup :steward-loop)]
        (is (= :failed (:state child)))
        (is (not= :running (:state child)))
        (is (zero? (long (:runs child))))
        (is (= 1 (long (:failures child))))
        (is (= :BOOT_NO (get-in child [:last-error :code]))))
      (let [adapter (background/status steward-spec)]
        (is (false? (:running? adapter)))
        (is (zero? (long (:runs adapter))))
        (is (= 1 (long (:failures adapter))))
        (is (= :BOOT_NO (get-in adapter [:last-error :code]))))
      (is (= :running (:state (child-state sup :fleet-gc))))
      (finally
        (supervisor/stop! sup)))))

(deftest stop-is-idempotent-and-bounded
  (let [stops (atom 0)
        release (CountDownLatch. 1)
        spec (background/steward-loop-child
              {:tick-fn (fn [] {:ok? true})
               :interval-ms 60000
               :stop-timeout-ms 20
               :stop-fn (fn [_]
                          (swap! stops inc)
                          (.await release))})
        handle ((:start! spec)
                {:clock #(System/currentTimeMillis)})]
    (try
      (is (map? handle))
      (let [started (System/nanoTime)
            first-stop ((:stop! spec) handle)
            elapsed-ms (quot (- (System/nanoTime) started) 1000000)
            second-stop ((:stop! spec) handle)]
        (is (true? (:stopped? first-stop)))
        (is (true? (:timed-out? first-stop)))
        (is (false? (:ok? first-stop))
            "a timed-out stop must not report success")
        (is (= :BACKGROUND_STOP_TIMEOUT (get-in first-stop [:error :code])))
        (is (< elapsed-ms 2000)
            "stop is bounded by :stop-timeout-ms")
        (is (= 1 @stops))
        (is (true? (:ok? second-stop)))
        (is (false? (:stopped? second-stop)))
        (is (= 1 @stops) "the second stop is a no-op"))
      (is (false? (:running? (background/status handle))))
      (finally
        (.countDown release)
        ((:stop! spec) handle)))))

(deftest a-stop-timeout-is-a-failed-stop
  (let [spec (background/steward-loop-child
              {:tick-fn (fn [] {:ok? true})
               :interval-ms 60000
               :stop-timeout-ms 20
               :stop-fn (fn [_] (Thread/sleep 200))})
        handle ((:start! spec)
                {:clock #(System/currentTimeMillis)})
        result ((:stop! spec) handle)]
    (is (false? (:ok? result)))
    (is (true? (:timed-out? result)))
    (is (true? (:stopped? result)))
    (is (= :BACKGROUND_STOP_TIMEOUT (get-in result [:error :code])))))

(deftest a-throwing-stop-callback-is-a-failed-stop
  (let [spec (background/steward-loop-child
              {:tick-fn (fn [] {:ok? true})
               :interval-ms 60000
               :stop-fn (fn [_] (throw (ex-info "stop boom" {})))})
        handle ((:start! spec)
                {:clock #(System/currentTimeMillis)})
        result ((:stop! spec) handle)]
    (is (false? (:ok? result)))
    (is (false? (:timed-out? result)))
    (is (true? (:stopped? result)))
    (is (= :BACKGROUND_STOP_FAILED (get-in result [:error :code])))))

(deftest an-in-bounds-confirmed-stop-is-ok
  (let [spec (background/steward-loop-child
              {:tick-fn (fn [] {:ok? true})
               :interval-ms 60000
               :stop-fn (fn [_] {:terminated? true})})
        handle ((:start! spec)
                {:clock #(System/currentTimeMillis)})
        result ((:stop! spec) handle)]
    (is (true? (:ok? result)))
    (is (false? (:timed-out? result)))
    (is (= {:terminated? true} (:result result)))))

(deftest an-unconfirmed-underlying-stop-is-not-ok
  (let [spec (background/steward-loop-child
              {:tick-fn (fn [] {:ok? true})
               :interval-ms 60000
               :stop-fn (fn [_] {:stopped? false :terminated? false})})
        handle ((:start! spec)
                {:clock #(System/currentTimeMillis)})
        result ((:stop! spec) handle)]
    (is (false? (:ok? result)))
    (is (false? (:timed-out? result)))
    (is (= :BACKGROUND_STOP_UNCONFIRMED
           (get-in result [:error :code])))))

(deftest an-underlying-stop-refusal-is-not-ok
  (let [spec (background/steward-loop-child
              {:tick-fn (fn [] {:ok? true})
               :interval-ms 60000
               :stop-fn (fn [_]
                          {:ok? false
                           :error {:code :INNER_STOP
                                   :message "writer still alive"}})})
        handle ((:start! spec)
                {:clock #(System/currentTimeMillis)})
        result ((:stop! spec) handle)]
    (is (false? (:ok? result)))
    (is (false? (:timed-out? result)))
    (is (= :INNER_STOP (get-in result [:error :code])))))

(deftest supervisor-stop-through-the-adapter-is-idempotent
  (let [stops (atom 0)
        children (background/child-specs
                  (assoc-in (ok-options) [:fleet-gc :stop-fn]
                            (fn [_] (swap! stops inc))))
        started (supervisor/start! {:children children})
        sup (:supervisor started)]
    (try
      (let [first-stop (supervisor/stop! sup)
            second-stop (supervisor/stop! sup)]
        (is (true? (:ok? first-stop)))
        (is (= first-stop second-stop))
        (is (= 1 @stops)))
      (finally
        (supervisor/stop! sup)))))

;; ---------------------------------------------------------------------------
;; child-specs

(deftest child-specs-omit-missing-loops-and-keep-a-fixed-order
  (let [options (ok-options)]
    (testing "order and omission"
      (are [loops expected]
          (= expected (mapv :id (background/child-specs loops)))
        {} []
        nil []
        (select-keys options [:steward-loop]) [:steward-loop]
        (select-keys options [:retention-gc]) [:retention-gc]
        (select-keys options [:fleet-gc :steward-loop])
        [:steward-loop :fleet-gc]
        (assoc options :fleet-gc nil) [:steward-loop :retention-gc]
        options [:steward-loop :fleet-gc :retention-gc]))
    (testing "required supervisor fields"
      (doseq [spec (background/child-specs options)]
        (is (every? #(contains? spec %)
                    [:id :start! :stop! :tick! :interval-ms
                     :restart :backoff-ms :critical?]))))))

;; ---------------------------------------------------------------------------
;; Real component contracts

(def ^:private real-retention-opt-keys
  "The option keys the real retention-gc/start! reads.

  Source: src/hc/server/app/retention_gc.clj, start!."
  #{:registry :store :blob-root :clock :session-interval-ms :blob-interval-ms
    :session-days :blob-days :run-immediately? :purge-session! :blob-cleanup!
    :stop-timeout-ms})

(deftest retention-child-passes-the-real-start-opts
  (let [captured (atom nil)
        stopped (atom nil)
        runtime {:fake :retention-runtime}
        opts {:registry {:fake :registry}
              :store {:fake :store}
              :blob-root "/tmp/hc-bg-fake-blob-root"
              :clock #(System/currentTimeMillis)
              :session-interval-ms 600000
              :blob-interval-ms 600000
              :session-days 30
              :blob-days 7
              :run-immediately? false
              :stop-timeout-ms 100
              :purge-session! (fn [_] {:status :purged})
              :blob-cleanup! (fn [_] {:outcome :completed
                                      :deleted []
                                      :errors []})}
        service-ref (atom nil)
        child (background/retention-gc-child
               {:service-ref service-ref
                :purge-fn (fn [_] {:ok? true})
                :blob-gc-fn (fn [_] {:ok? true})
                :interval-ms 60000
                :start-opts opts})]
    (is (= real-retention-opt-keys (set (keys opts))))
    (is (= real-retention-opt-keys background/retention-start-opt-keys))
    (is (= real-retention-opt-keys
           (->> (first (first (:arglists (meta #'retention-gc/start!))))
                :keys
                (map keyword)
                set))
        "the expected set matches the real start! arglists")
    (with-redefs [retention-gc/start!
                  (fn [passed]
                    (reset! captured passed)
                    {:ok? true :service runtime})

                  retention-gc/stop!
                  (fn [service]
                    (reset! stopped service)
                    {:stopped? true :terminated? true})]
      (let [started (supervisor/start! {:children [child]})
            sup (:supervisor started)]
        (try
          (is (true? (:ok? started)))
          (is (= opts @captured))
          (is (empty? (set/difference (set (keys @captured))
                                      real-retention-opt-keys))
              "the adapter passes no key the real start! does not read")
          (is (= runtime @service-ref)
              "a successful start publishes the unwrapped service")
          (let [stop-result (supervisor/stop! sup)]
            (is (true? (:ok? stop-result)))
            (is (= runtime @stopped)
                "the derived stop-fn receives the unwrapped service"))
          (is (false? (:running? (background/status child))))
          (finally
            (supervisor/stop! sup)))))))

(deftest retention-child-drives-the-real-retention-runtime
  (let [root (Files/createTempDirectory "hc-bg-retention-"
                                        (make-array FileAttribute 0))
        now 1700000000000
        clock (constantly now)
        session {:session-id "s1"
                 :status :deleted
                 :temporary? false
                 :deleted-at 1000}
        purged (atom [])
        opts {:registry {}
              :blob-root (str root)
              :clock clock
              :session-interval-ms 600000
              :blob-interval-ms 600000
              :session-days 30
              :blob-days 7
              :run-immediately? false
              :stop-timeout-ms 100
              :purge-session! (fn [session-id]
                                (swap! purged conj session-id)
                                {:status :purged
                                 :session-id session-id})
              :blob-cleanup! (fn [_]
                               {:outcome :completed
                                :deleted []
                                :errors []})}
        service-ref (atom nil)
        child (background/retention-gc-child
               {:service-ref service-ref
                :purge-fn retention-gc/purge-expired-deleted!
                :blob-gc-fn retention-gc/gc-blobs!
                :interval-ms 60000
                :start-opts opts})]
    (try
      (with-redefs [registry/deleted-session-summaries
                    (fn [_] {:status :ok :sessions [session]})

                    registry/get-committed-session-summary
                    (fn [_ _] {:status :found :session session})]
        (let [started (supervisor/start! {:children [child]
                                          :clock clock})
              sup (:supervisor started)]
          (try
            (is (true? (:ok? started)))
            (is (map? @service-ref)
                "the real start! published its runtime")
            (let [receipt (supervisor/trigger! sup :retention-gc)]
              (is (true? (:ok? receipt)))
              (is (nil? (:error receipt))))
            (is (= ["s1"] @purged)
                "the real session sweep reached the injected purge")
            (let [real (retention-gc/status @service-ref)]
              (is (= 1 (long (:session-runs real))))
              (is (= 1 (long (:blob-runs real))))
              (is (true? (get-in real [:session-last-result :ok?])))
              (is (true? (get-in real [:blob-last-result :ok?])))
              (is (= now (:last-run-at real))))
            (let [summary (background/status child)]
              (is (true? (:running? summary)))
              (is (= 1 (long (:runs summary))))
              (is (zero? (long (:failures summary)))))
            (let [stop-result (supervisor/stop! sup)]
              (is (true? (:ok? stop-result))))
            (let [real (retention-gc/status @service-ref)]
              (is (false? (:running? real)))
              (is (true? (:stopped? real)))
              (is (true? (:terminated? real))))
            (finally
              (supervisor/stop! sup)))))
      (finally
        (when @service-ref
          (retention-gc/stop! @service-ref))
        (Files/deleteIfExists root)))))

(deftest fleet-child-unwraps-the-real-gc-start-shape
  (let [runtime {:fleet-gc-runtime true}
        captured (atom nil)
        stopped (atom nil)
        child (background/fleet-gc-child
               {:gc-fn (fn [_retention-ms] {:ok? true})
                :retention-ms 1000
                :interval-ms 60000
                :start-fn (fn []
                            (fleet-gc/start! {:service nil
                                              :interval-ms 60000
                                              :run-immediately? false}))
                ;; Resolve the var at call time so with-redefs applies.
                :stop-fn (fn [gc] (fleet-gc/stop! gc))})]
    (with-redefs [fleet-gc/start!
                  (fn [opts]
                    (reset! captured opts)
                    {:ok? true :gc runtime})

                  fleet-gc/stop!
                  (fn [gc]
                    (reset! stopped gc)
                    {:stopped? true :terminated? true})]
      (let [started (supervisor/start! {:children [child]})
            sup (:supervisor started)]
        (try
          (is (true? (:ok? started)))
          (is (= {:service nil
                  :interval-ms 60000
                  :run-immediately? false}
                 @captured))
          (is (every? (->> (first (first (:arglists (meta #'fleet-gc/start!))))
                           :keys
                           (map keyword)
                           set)
                      (keys @captured))
              "the test passes only real fleet-gc/start! opts")
          (is (true? (:running? (background/status child))))
          (supervisor/stop! sup)
          (is (= runtime @stopped)
              "stop-fn receives the unwrapped :gc runtime")
          (finally
            (supervisor/stop! sup)))))))

;; ---------------------------------------------------------------------------
;; Property

(defspec tick-results-keep-counters-and-running-honest 60
  (prop/for-all [results (gen/vector (gen/elements [:ok :fail :throw]) 0 20)]
    (let [queue (atom results)
          spec (background/steward-loop-child
                {:tick-fn
                 (fn []
                   (let [result (first @queue)]
                     (swap! queue rest)
                     (case result
                       :ok {:ok? true}
                       :fail {:ok? false
                              :error {:code :X :message "fail"}}
                       :throw (throw (ex-info "boom" {})))))
                 :interval-ms 60000})
          handle ((:start! spec)
                  {:clock #(System/currentTimeMillis)})]
      (try
        (let [walk-ok?
              (loop [remaining results
                     expected-runs 0
                     expected-failures 0
                     ok? true]
                (if (seq remaining)
                  (let [result (first remaining)
                        receipt ((:tick! spec)
                                 {:handle handle
                                  :clock #(System/currentTimeMillis)})
                        expected-ok? (= :ok result)
                        summary (background/status handle)
                        runs (long (:runs summary))
                        failures (long (:failures summary))
                        next-runs (if expected-ok?
                                    (inc expected-runs)
                                    expected-runs)
                        next-failures (if expected-ok?
                                        expected-failures
                                        (inc expected-failures))]
                    (recur (rest remaining)
                           next-runs
                           next-failures
                           (and ok?
                                (= expected-ok?
                                   (true? (:ok? receipt)))
                                (= next-runs runs)
                                (= next-failures failures)
                                (true? (:running? summary)))))
                  ok?))
              before-stop (background/status handle)
              stopped ((:stop! spec) handle)
              after-stop (background/status handle)
              trailing
              (loop [remaining (reverse results)
                     streak 0]
                (if (and (seq remaining)
                         (not= :ok (first remaining)))
                  (recur (rest remaining) (inc streak))
                  streak))]
          (and walk-ok?
               (true? (:running? before-stop))
               (true? (:stopped? stopped))
               (false? (:running? after-stop))
               (= (:runs before-stop) (:runs after-stop))
               (= (:failures before-stop) (:failures after-stop))
               (= trailing
                  (long (:consecutive-failures before-stop)))
               (<= (long (:consecutive-failures before-stop))
                   (long (:failures before-stop)))))
        (finally
          ((:stop! spec) handle))))))
