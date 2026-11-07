(ns hc.server.app.fleet-gc-test
  (:require [clojure.test :refer [deftest is]]
            [hc.server.app.fleet-gc :as fleet-gc])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(defn- await-until
  [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))]
    (loop []
      (cond
        (pred) true
        (> (System/nanoTime) deadline) false
        :else (do
                (Thread/sleep 5)
                (recur))))))

(deftest periodic-sweep-runs-and-stops
  (let [calls (atom 0)
        service {:gc! (fn [_]
                        (swap! calls inc)
                        {:ok? true :removed []})}
        started (fleet-gc/start! {:service service
                                  :interval-ms 10
                                  :run-immediately? true})]
    (is (:ok? started))
    (let [gc (:gc started)]
      (try
        (is (await-until #(>= (:tick-count (fleet-gc/status gc)) 2) 2000))
        (is (>= @calls 2))
        (let [status (fleet-gc/status gc)]
          (is (true? (:running? status)))
          (is (>= (:tick-count status) 2))
          (is (not (contains? (:last-result status) :state))
              "scheduler evidence stays bounded"))
        (finally
          (let [stopped (fleet-gc/stop! gc)]
            (is (true? (:stopped? stopped)))
            (is (true? (:terminated? stopped))))))
      (let [after-stop @calls]
        (Thread/sleep 50)
        (is (= after-stop @calls) "no tick starts after stop")
        (let [status (fleet-gc/status gc)]
          (is (true? (:stopped? status)))
          (is (true? (:terminated? status)))
          (is (false? (:running? status))))))))

(deftest stop-waits-for-an-in-flight-sweep
  (let [started (CountDownLatch. 1)
        release (CountDownLatch. 1)
        service {:gc! (fn [_]
                        (.countDown started)
                        (.await release 2 TimeUnit/SECONDS)
                        {:ok? true})}
        result (fleet-gc/start! {:service service
                                 :interval-ms 1000
                                 :run-immediately? true})]
    (is (:ok? result))
    (let [gc (:gc result)
          stopped (promise)]
      (try
        (is (.await started 2 TimeUnit/SECONDS))
        (future (deliver stopped (fleet-gc/stop! gc)))
        (Thread/sleep 50)
        (is (not (realized? stopped))
            "stop waits for the running sweep")
        (finally
          (.countDown release)))
      (let [evidence (deref stopped 2000 ::timeout)]
        (is (not= ::timeout evidence))
        (is (true? (:stopped? evidence)))
        (is (true? (:terminated? evidence)))))))

(deftest manual-ticks-are-serialized
  (let [in-flight (atom 0)
        max-in-flight (atom 0)
        service {:gc! (fn [_]
                        (let [n (swap! in-flight inc)]
                          (swap! max-in-flight max n)
                          (Thread/sleep 30)
                          (swap! in-flight dec)
                          {:ok? true}))}
        started (fleet-gc/start! {:service service
                                  :interval-ms 100000
                                  :run-immediately? false})]
    (is (:ok? started))
    (let [gc (:gc started)
          a (future (fleet-gc/tick-now! gc))
          b (future (fleet-gc/tick-now! gc))]
      (try
        (is (:ok? (deref a 2000 ::timeout)))
        (is (:ok? (deref b 2000 ::timeout)))
        (is (= 1 @max-in-flight))
        (finally
          (fleet-gc/stop! gc))))))

(deftest a-failed-sweep-is-recorded-and-the-timer-continues
  (let [calls (atom 0)
        service {:gc! (fn [_]
                        (if (= 1 (swap! calls inc))
                          (throw (ex-info "sweep failed" {}))
                          {:ok? true}))}
        started (fleet-gc/start! {:service service
                                  :interval-ms 10
                                  :run-immediately? true})]
    (is (:ok? started))
    (let [gc (:gc started)]
      (try
        (is (await-until #(>= (:tick-count (fleet-gc/status gc)) 2) 2000))
        (is (>= @calls 2))
        (let [status (fleet-gc/status gc)]
          (is (>= (:tick-count status) 2))
          (is (nil? (:last-error status)))
          (is (:ok? (:last-result status))))
        (finally
          (fleet-gc/stop! gc))))))

(deftest an-interrupted-sweep-is-visible
  (let [started (fleet-gc/start!
                 {:service {:gc! (fn [_] {:ok? false :interrupted? true})}
                  :interval-ms 100000
                  :run-immediately? false})]
    (is (:ok? started))
    (let [gc (:gc started)]
      (try
        (let [result (fleet-gc/tick-now! gc)
              status (fleet-gc/status gc)]
          (is (false? (:ok? result)))
          (is (true? (:interrupted? result)))
          (is (= :FLEET_GC_INTERRUPTED (get-in status [:last-error :code])))
          (is (true? (get-in status [:last-result :interrupted?]))))
        (finally
          (fleet-gc/stop! gc))))))

(deftest start-requires-a-real-service-and-a-positive-interval
  (is (= :FLEET_GC_SERVICE_REQUIRED
         (get-in (fleet-gc/start! {}) [:error :code])))
  (is (= :FLEET_GC_INTERVAL_INVALID
         (get-in (fleet-gc/start! {:service {:gc! (fn [_] {:ok? true})}
                                   :interval-ms 0})
                 [:error :code]))))

(deftest a-stopped-scheduler-refuses-late-ticks
  (let [calls (atom 0)
        started (fleet-gc/start! {:service {:gc! (fn [_]
                                                   (swap! calls inc)
                                                   {:ok? true})}
                                  :interval-ms 100000
                                  :run-immediately? false})
        gc (:gc started)]
    (is (:ok? started))
    (fleet-gc/stop! gc)
    (let [result (fleet-gc/tick-now! gc)]
      (is (= :FLEET_GC_STOPPED (get-in result [:error :code])))
      (is (= 0 @calls)))))
