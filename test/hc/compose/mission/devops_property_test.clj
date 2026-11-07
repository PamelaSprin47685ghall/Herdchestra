(ns hc.compose.mission.devops-property-test
  "Property tests for the Plan dedicated worker: the same worker session is
  reused for the same tenure and every report is returned verbatim."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.mission :as mission]
            [hc.compose.mission.devops :as devops]
            [hc.compose.mission.support :as support]))

(defn- report-model [reports]
  (let [counter (atom 0)]
    {:start! (fn [request deliver!]
               (let [report (nth reports @counter (last reports))]
                 (swap! counter inc)
                 (future
                   (deliver! {:event :chunk :text report
                              :attempt-id (:attempt-id request)
                              :call-id (:call-id request)})
                   (deliver! {:event :completed :text report
                              :attempt-id (:attempt-id request)
                              :call-id (:call-id request)})))
               {:attempt-id (:attempt-id request)
                :call-id (:call-id request)})
     :cancel! (fn [_] nil)}))

(defspec worker-is-reused-and-report-is-verbatim 50
  (prop/for-all [report (gen/such-that seq gen/string-alphanumeric 100)]
    (let [host (support/host)
          plan-id (support/plan-tenure-id host)
          model (report-model [report report])
          runner (devops/runner {:model model})
          first-result (mission/devops! host {:tenure-id plan-id
                                              :call-id "devops-1"
                                              :verbose-intent "inspect"
                                              :run (:run! runner)})
          first-worker (get-in first-result [:receipt :worker-id])
          second-result (mission/devops! host {:tenure-id plan-id
                                               :call-id "devops-2"
                                               :verbose-intent "inspect again"
                                               :run (:run! runner)})
          second-worker (get-in second-result [:receipt :worker-id])]
      (and (:ok? first-result)
           (:ok? second-result)
           (= first-worker second-worker)
           (= report (get-in first-result [:receipt :report]))
           (= report (get-in second-result [:receipt :report]))
           (= 1 (count ((:sessions runner))))))))
