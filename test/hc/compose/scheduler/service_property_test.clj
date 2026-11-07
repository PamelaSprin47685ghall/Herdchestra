(ns hc.compose.scheduler.service-property-test
  "Property tests for scheduler service: one due daily schedule starts one
  run, a second tick does not overlap, and pause prevents the next day's
  occurrence from starting."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.scheduler.service :as service]
            [hc.compose.scheduler.store :as store]
            [hc.compose.scheduler.support :as support]))

(defspec due-daily-schedule-starts-once-and-pause-stops-future 50
  (prop/for-all [hour (gen/choose 0 23)
                 minute (gen/choose 0 59)]
    (let [zone "Asia/Shanghai"
          ;; Create one second before the target day so a daily 00:00 rule is
          ;; strictly after the creation instant (next-valid-occurrence is
          ;; "strictly after"); otherwise the [0 0] case would skip day one.
          t0 (support/epoch zone "2026-11-02T23:59:59")
          clock (support/fixed-clock t0)
          started (atom [])
          opened (service/open-service
                  {:store (store/memory-store)
                   :clock (:clock clock)
                   :preflight (fn [_] {:ok? true})
                   :start-run! (fn [{:keys [run]}]
                                 (swap! started conj (:id run))
                                 :accepted)})
          svc (:service opened)
          created (service/create-schedule!
                   svc (assoc support/base-spec
                              :enable? true
                              :recurrence {:kind :daily
                                           :time {:hour hour :minute minute}}))
          schedule-id (get-in created [:schedule :id])]
      (if-not (and (:ok? opened) (:ok? created))
        false
        (let [_ ((:at! clock)
                 (support/epoch zone
                                (format "2026-11-03T%02d:%02d:10" hour minute)))
              tick (service/tick! svc)
              run-id (get-in tick [:started 0 :id])
              tick2 (service/tick! svc)
              _ (when run-id
                  (service/finish-run! svc run-id {:status :completed}))
              _ (service/pause! svc schedule-id)
              _ ((:at! clock)
                 (support/epoch zone
                                (format "2026-11-04T%02d:%02d:10" hour minute)))
              tick3 (service/tick! svc)]
          (and (:ok? tick)
               (= 1 (count @started))
               (:ok? tick2)
               (= 1 (count @started))
               (:ok? tick3)
               (= 1 (count @started))))))))
