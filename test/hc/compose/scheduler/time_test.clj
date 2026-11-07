(ns hc.compose.scheduler.time-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.scheduler.support :as support]
            [hc.compose.scheduler.time :as time])
  (:import (java.time Instant LocalDateTime LocalTime ZoneOffset)))

(def shanghai "Asia/Shanghai")
(def new-york "America/New_York")

(defn- local-time-of
  [occurrence]
  (let [local (LocalDateTime/parse (:local-date-time occurrence))]
    (.toLocalTime local)))

(deftest weekly-preview-shows-real-dates-and-zone
  (let [now (support/epoch shanghai "2026-11-03T09:00")
        recurrence {:kind :weekly
                    :days [1 3]
                    :time {:hour 8 :minute 30}}
        {:keys [times gaps]}
        (time/preview-times recurrence shanghai now 3)]
    (is (= ["2026-11-04T08:30" "2026-11-09T08:30" "2026-11-11T08:30"]
           (mapv :local-date-time times)))
    (is (empty? gaps))
    (is (every? #(= shanghai (:zone %)) times))
    (is (every? #(= (LocalTime/of 8 30) (local-time-of %)) times))
    (is (apply < (map :epoch-ms times)))))

(deftest daily-and-weekday-rules
  (let [now (support/epoch shanghai "2026-11-06T09:00") ; Friday
        daily (time/preview-times {:kind :daily
                                   :time {:hour 7 :minute 5}}
                                  shanghai now 3)
        weekdays (time/preview-times {:kind :weekdays
                                      :time {:hour 7 :minute 5}}
                                     shanghai now 3)]
    (is (= ["2026-11-07T07:05" "2026-11-08T07:05" "2026-11-09T07:05"]
           (mapv :local-date-time (:times daily))))
    (is (= ["2026-11-09T07:05" "2026-11-10T07:05" "2026-11-11T07:05"]
           (mapv :local-date-time (:times weekdays))))))

(deftest once-and-starts-on
  (let [now (support/epoch shanghai "2026-11-03T09:00")
        once {:kind :once :local-date "2026-11-05"
              :time {:hour 8 :minute 30}}
        bounded {:kind :daily :starts-on "2026-11-10"
                 :time {:hour 8 :minute 30}}]
    (is (= ["2026-11-05T08:30"]
           (mapv :local-date-time (:times (time/preview-times
                                           once shanghai now 3)))))
    (is (nil? (time/next-valid-occurrence
               once shanghai (support/epoch shanghai "2026-11-06T00:00"))))
    (is (= ["2026-11-10T08:30" "2026-11-11T08:30" "2026-11-12T08:30"]
           (mapv :local-date-time (:times (time/preview-times
                                           bounded shanghai now 3)))))))

(deftest spring-forward-gap-is-not-run
  (let [from (support/epoch new-york "2026-03-07T03:00")
        until (support/epoch new-york "2026-03-09T03:00")
        {:keys [runs gaps]}
        (time/occurrences-between {:kind :daily :time {:hour 2 :minute 30}}
                                  new-york from until)]
    (is (= ["2026-03-08T02:30"] (mapv :local-date-time gaps)))
    (is (= :nonexistent-local-time (:reason (first gaps))))
    (is (= ["2026-03-09T02:30"] (mapv :local-date-time runs)))
    (is (= "-04:00" (:offset (first runs))))))

(deftest fall-back-overlap-runs-once-with-earlier-offset
  (let [from (support/epoch new-york "2026-10-31T02:00")
        until (support/epoch new-york "2026-11-02T02:00")
        {:keys [runs gaps]}
        (time/occurrences-between {:kind :daily :time {:hour 1 :minute 30}}
                                  new-york from until)
        occurrence (first runs)]
    (is (empty? gaps))
    (is (= ["2026-11-01T01:30" "2026-11-02T01:30"]
           (mapv :local-date-time runs)))
    (is (true? (:ambiguous? occurrence)))
    (is (= "-04:00" (:offset occurrence)))
    (is (= (.toEpochMilli (Instant/parse "2026-11-01T05:30:00Z"))
           (:epoch-ms occurrence)))))

(deftest local-view-keeps-schedule-zone-and-device-zone
  (let [instant (support/epoch shanghai "2026-11-04T08:30")
        view (time/local-view instant shanghai "Europe/Paris")]
    (is (= "2026-11-04T08:30" (:local-date-time view)))
    (is (= "+08:00" (:offset view)))
    (is (= "2026-11-04T01:30" (:device-local-date-time view)))
    (is (= "+01:00" (:device-offset view)))))

(deftest gap-has-no-real-instant
  (let [occurrence (first (:gaps (time/occurrences-between
                                  {:kind :daily :time {:hour 2 :minute 30}}
                                  new-york
                                  (support/epoch new-york "2026-03-08T00:00")
                                  (support/epoch new-york "2026-03-08T23:59"))))]
    (is (= :gap (:kind occurrence)))
    (is (nil? (:epoch-ms occurrence)))
    (is (number? (:nominal-epoch-ms occurrence)))))

(deftest past-once-rule-has-no-future-window
  (let [now (support/epoch shanghai "2026-11-03T09:00")
        recurrence {:kind :once
                    :local-date "2026-01-01"
                    :time {:hour 8 :minute 30}}
        window (time/next-window recurrence shanghai now)
        preview (time/preview-times recurrence shanghai now 3)]
    (is (nil? (:next-run window)))
    (is (true? (:exhausted? window)))
    (is (empty? (:times preview)))
    (is (true? (:exhausted? preview)))))

(deftest gap-nominal-uses-the-pre-transition-offset
  (let [zone new-york
        from (support/epoch zone "2026-03-08T00:00")
        until (support/epoch zone "2026-03-08T23:59")
        {:keys [gaps]}
        (time/occurrences-between {:kind :daily
                                   :time {:hour 2 :minute 30}}
                                  zone from until)
        gap (first gaps)
        expected (.toEpochMilli
                  (.toInstant
                   (.atOffset (LocalDateTime/parse "2026-03-08T02:30")
                              (ZoneOffset/ofHours -5))))]
    (is (= :gap (:kind gap)))
    (is (= expected (:nominal-epoch-ms gap))
        "the nominal instant is the pre-gap local time, not the shifted one")))

(defspec next-daily-times-stay-on-the-rule
  100
  (prop/for-all [hour (gen/choose 0 23)
                 minute (gen/choose 0 59)
                 month (gen/choose 1 12)
                 day (gen/choose 1 28)]
    (let [zone shanghai
          now (support/epoch zone
                             (format "2026-%02d-%02dT12:00" month day))
          {:keys [times]}
          (time/preview-times {:kind :daily
                               :time {:hour hour :minute minute}}
                              zone now 3)]
      (and (= 3 (count times))
           (apply < (map :epoch-ms times))
           (every? #(= hour (.getHour (local-time-of %))) times)
           (every? #(= minute (.getMinute (local-time-of %))) times)
           (every? #(> (:epoch-ms %) now) times)))))

(defspec weekly-times-fall-on-selected-days
  100
  (prop/for-all [raw-days (gen/not-empty (gen/vector (gen/choose 1 7) 1 5))
                 hour (gen/choose 0 23)
                 minute (gen/choose 0 59)]
    (let [days (vec (sort (distinct raw-days)))
          zone shanghai
          now (support/epoch zone "2026-11-03T09:00")
          {:keys [times]}
          (time/preview-times {:kind :weekly :days days
                               :time {:hour hour :minute minute}}
                              zone now 3)]
      (and (= 3 (count times))
           (every? #(contains? (set days)
                               (.getValue (.getDayOfWeek
                                           (LocalDateTime/parse
                                            (:local-date-time %)))))
                   times)
           (apply < (map :epoch-ms times))))))
