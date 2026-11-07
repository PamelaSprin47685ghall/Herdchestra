(ns hc.compose.owner.dispatch-property-test
  "Property tests for attempt-scoped dispatch: a live attempt is started at
  most once, and cancel reaches the executor without duplicating a start."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.owner.dispatch :as dispatch]))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(defspec same-live-attempt-starts-once-and-cancels-once 50
  (prop/for-all [attempt-id gen/string-alphanumeric
                 call-id gen/string-alphanumeric]
    (let [calls (atom 0)
          started (promise)
          release (promise)
          cancelled (promise)
          posts (atom [])
          d (dispatch/create
             {:post! (fn [m] (swap! posts conj m) {:ok? true})
              :executors
              {:tool {:run (fn [_]
                             (swap! calls inc)
                             (deliver started true)
                             @release
                             {:status :succeeded :payload :ok})
                      :cancel (fn [_] (deliver cancelled true))}}})]
      (try
        (let [effect {:kind :tool :attempt-id attempt-id :call-id call-id}
              first-result (dispatch/start! d effect)
              _ (wait-until #(realized? started) 1000)
              second-result (dispatch/start! d effect)
              cancel-result (dispatch/cancel! d attempt-id :user-cancel)]
          (deliver release true)
          (wait-until #(some (fn [m] (and (= :call/terminal (:type m))
                                          (= attempt-id (:attempt-id m))))
                             @posts)
                      1000)
          (and (:ok? first-result)
               (true? (:already-running? second-result))
               (= 1 @calls)
               (:ok? cancel-result)
               (true? (deref cancelled 1000 false))
               (= 1 (count (filter #(and (= :call/terminal (:type %))
                                         (= attempt-id (:attempt-id %)))
                                   @posts)))))
        (finally
          (deliver release true)
          (dispatch/stop! d))))))
