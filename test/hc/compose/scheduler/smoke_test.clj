(ns hc.compose.scheduler.smoke-test
  "Real-runtime smoke for schedule persistence and restart behavior.

  This test writes an actual EDN state file, starts a run through the host
  callback (which writes a real result file), and reopens the service from
  disk at a later clock.  It asserts the restart facts that matter to a user:
  the run is interrupted rather than replayed, missed occurrences are
  recorded, saved work survives, and the next run is strictly future."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hc.compose.scheduler.service :as service]
            [hc.compose.scheduler.store :as store]
            [hc.compose.scheduler.support :as support]))

(def shanghai "Asia/Shanghai")

(defn- now
  [local]
  (support/epoch shanghai local))

(deftest runtime-smoke-restart-persists-facts
  (let [dir (support/temp-dir)
        db (str dir "/scheduler.edn")
        result-file (str dir "/result.md")
        t0 (now "2026-11-03T09:00")
        clock (support/fixed-clock t0)
        started (atom [])
        run-id (atom nil)]
    (try
      (let [opened (service/open-service
                    {:store (support/edn-file-store db)
                     :clock (:clock clock)
                     :start-run! (fn [{:keys [run]}]
                                   (swap! started conj (:id run))
                                   (reset! run-id (:id run))
                                   (spit result-file "partial work")
                                   :accepted)})
            svc (:service opened)
            created (service/create-schedule! svc
                                              (assoc support/base-spec
                                                     :enable? true))
            id (get-in created [:schedule :id])]
        (is (:ok? opened))
        (is (:enabled? created))
        (is (= ["2026-11-04T08:30" "2026-11-09T08:30" "2026-11-11T08:30"]
               (mapv :local-date-time (get-in created [:preview :times]))))
        ((:at! clock) (now "2026-11-04T08:30:10"))
        (let [tick (service/tick! svc)]
          (is (:ok? tick))
          (is (= 1 (count @started)))
          (is (= :running (:status (service/run svc @run-id))))
          (is (.exists (io/file result-file)))
          (is (= :running
                 (get-in (store/load-state (support/edn-file-store db))
                         [:runs @run-id :status]))))
        (service/close! svc)
        ((:at! clock) (now "2026-11-11T09:00"))
        (let [reopened (service/open-service
                        {:store (support/edn-file-store db)
                         :clock (:clock clock)
                         :start-run! (fn [_]
                                       (throw (ex-info
                                               "restart must not start a run"
                                               {})))})
              svc2 (:service reopened)
              recovery (:recovery reopened)]
          (is (:ok? reopened))
          (is (= [@run-id] (:interrupted recovery)))
          (is (empty? (:missed recovery))
              "the due occurrences overlapped the live run")
          (is (= 2 (count (:skipped recovery))))
          (is (every? #(= :previous-still-running (:reason %))
                      (:skipped recovery)))
          (is (= :interrupted (:status (service/run svc2 @run-id))))
          (is (= :process-restart (:reason (service/run svc2 @run-id))))
          (is (= (now "2026-11-11T09:00")
                 (:ended-at (service/run svc2 @run-id))))
          (is (= 1 (count @started))
              "recovery never dispatches a start")
          (is (.exists (io/file result-file))
              "saved work survives the restart")
          (is (= (now "2026-11-16T08:30")
                 (get-in (service/snapshot svc2)
                         [:schedules id :next-run])))
          (is (re-find #":interrupted" (slurp db)))
          (is (re-find #":skipped" (slurp db)))
          (is (re-find #":previous-still-running" (slurp db)))
          (is (= :process-restart
                 (get-in (service/snapshot svc2)
                         [:runs @run-id :reason])))))
      (finally
        (support/delete-tree dir)))))
