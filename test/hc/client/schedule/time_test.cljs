(ns hc.client.schedule.time-test
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.schedule.time :as time]))

(defn- epoch
  [iso]
  (.parse js/Date iso))

(deftest zone-and-format-basics
  (is (time/valid-zone? "Asia/Shanghai"))
  (is (not (time/valid-zone? "Not/AZone")))
  (testing "one instant in its own zone and the device zone"
    (let [view (time/local-view (epoch "2026-11-04T00:30:00Z")
                                "Asia/Shanghai"
                                "Europe/Paris")]
      (is (= "2026-11-04T08:30" (:local-date-time view)))
      (is (= "+08:00" (:offset view)))
      (is (= "2026-11-04T01:30" (:device-local-date-time view)))
      (is (= "+01:00" (:device-offset view)))))
  (testing "a bare time is never formatted without a zone"
    (is (str/includes? (time/format-epoch (epoch "2026-11-04T00:30:00Z")
                                          "Asia/Shanghai")
                       "Asia/Shanghai"))
    (is (str/includes? (time/format-epoch (epoch "2026-11-04T00:30:00Z")
                                          "Asia/Shanghai")
                       "+08:00"))))

(deftest dst-gap-is-not-shifted
  ;; 2026-03-08 02:30 does not exist in America/New_York.
  (let [resolved (time/resolve-local "America/New_York" 2026 3 8 2 30)]
    (is (= :gap (:status resolved)))
    (is (= "2026-03-08T02:30" (:local-date-time resolved)))
    (is (nil? (:epoch-ms resolved)))))

(deftest dst-overlap-uses-the-earlier-instant
  ;; 2026-11-01 01:30 happens twice in America/New_York; the earlier is EDT.
  (let [resolved (time/resolve-local "America/New_York" 2026 11 1 1 30)]
    (is (= :overlap (:status resolved)))
    (is (true? (:ambiguous? resolved)))
    (is (= (epoch "2026-11-01T05:30:00Z") (:epoch-ms resolved)))
    (is (= "-04:00" (:offset resolved)))))

(deftest fixed-zone-resolution
  (let [resolved (time/resolve-local "Asia/Shanghai" 2026 11 4 8 30)]
    (is (= :ok (:status resolved)))
    (is (= (epoch "2026-11-04T00:30:00Z") (:epoch-ms resolved)))
    (is (= "2026-11-04T08:30" (:local-date-time resolved)))))

(deftest weekly-preview-shows-real-dates
  (let [result (time/preview {:kind :weekly
                              :days [1 3]
                              :time {:hour 8 :minute 30}}
                             {:mode :fixed :zone "Asia/Shanghai"}
                             (epoch "2026-11-03T00:00:00Z")
                             {:count 3})
        times (get-in result [:preview :times])]
    (is (true? (:ok? result)))
    (is (= ["2026-11-04T08:30" "2026-11-09T08:30" "2026-11-11T08:30"]
           (mapv :local-date-time times)))
    (is (= "Asia/Shanghai" (get-in result [:preview :zone])))
    (is (str/includes? (get-in result [:preview :readable]) "Asia/Shanghai"))))

(deftest daily-preview-reports-gaps-without-running-them
  (let [result (time/preview {:kind :daily
                              :time {:hour 2 :minute 30}}
                             {:mode :fixed :zone "America/New_York"}
                             (epoch "2026-03-07T12:00:00Z")
                             {:count 3})
        times (get-in result [:preview :times])
        gaps (get-in result [:preview :gaps])]
    (is (= ["2026-03-09T02:30" "2026-03-10T02:30" "2026-03-11T02:30"]
           (mapv :local-date-time times)))
    (is (= ["2026-03-08T02:30"] (mapv :local-date-time gaps)))))

(deftest fixed-zone-preview-includes-device-conversion
  (let [result (time/preview {:kind :daily
                              :time {:hour 8 :minute 30}}
                             {:mode :fixed :zone "Asia/Shanghai"}
                             (epoch "2026-11-03T00:00:00Z")
                             {:count 1 :device-zone "Europe/Paris"})
        occurrence (first (get-in result [:preview :times]))]
    (is (= "2026-11-03T08:30" (:local-date-time occurrence)))
    (is (= "Europe/Paris" (:device-zone occurrence)))
    (is (= "2026-11-03T01:30" (:device-local-date-time occurrence)))
    (is (= "2026-11-03 01:30" (:device-display occurrence)))))

(deftest follow-device-zone-uses-supplied-zone
  (let [result (time/preview {:kind :once
                              :local-date "2026-11-04"
                              :time {:hour 8 :minute 30}}
                             {:mode :follow-device :zone "Asia/Shanghai"}
                             (epoch "2026-11-03T00:00:00Z")
                             {:count 1})]
    (is (= "2026-11-04T08:30"
           (get-in result [:preview :times 0 :local-date-time])))
    (is (= :follow-device (get-in result [:preview :timezone-mode])))))

(deftest once-in-the-past-has-no-future-run
  (let [result (time/preview {:kind :once
                              :local-date "2026-11-01"
                              :time {:hour 8 :minute 30}}
                             {:mode :fixed :zone "Asia/Shanghai"}
                             (epoch "2026-11-03T00:00:00Z")
                             {:count 3})]
    (is (true? (:ok? result)))
    (is (empty? (get-in result [:preview :times])))
    (is (false? (get-in result [:preview :exhausted?])))))

(deftest invalid-input-is-refused
  (is (false? (:ok? (time/preview {:kind :weekly :days [] :time {:hour 8 :minute 0}}
                                  {:mode :fixed :zone "Asia/Shanghai"}
                                  0))))
  (is (false? (:ok? (time/preview {:kind :daily :time {:hour 8 :minute 0}}
                                  {:mode :fixed :zone "Not/AZone"}
                                  0))))
  (is (false? (:ok? (time/preview {:kind :once
                                   :local-date "2026-02-31"
                                   :time {:hour 8 :minute 0}}
                                  {:mode :fixed :zone "Asia/Shanghai"}
                                  0)))))

(deftest recurrence-description-is-readable
  (is (= "每周一、三 08:30"
         (time/describe-recurrence {:kind :weekly
                                    :days [3 1 3]
                                    :time {:hour 8 :minute 30}})))
  (is (= "工作日 08:05"
         (time/describe-recurrence {:kind :weekdays
                                    :time {:hour 8 :minute 5}}))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.schedule.time-test))

(set! *main-cli-fn* -main)
