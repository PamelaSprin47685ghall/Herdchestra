(ns hc.server.app.devops-integration-test
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.mission :as mission]
            [hc.compose.mission.support :as support]
            [hc.server.app.devops :as app-devops]
            [hc.server.files :as files])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- scripted-gateway
  [scripts]
  (let [requests (atom [])
        starts (atom 0)]
    {:requests requests
     :start! (fn [request deliver!]
               (swap! requests conj request)
               (let [index (swap! starts inc)
                     events (nth scripts (dec index) [])]
                 (future
                   (doseq [event events]
                     (deliver! (assoc event
                                      :attempt-id (:attempt-id request)
                                      :call-id (:call-id request)))))
                 {:attempt-id (:attempt-id request)
                  :call-id (:call-id request)}))
     :cancel! (fn [_] nil)}))

(defn- command-port
  [host]
  (fn [command]
    (case (:op command)
      :devops-request (mission/devops-request! host command)
      :devops-complete (mission/devops-complete! host command))))

(deftest app-orchestration-runs-the-real-worker-through-mission-host
  (let [root (temp-dir "hc-devops-app-")
        _ (spit (str root "/a.txt") "app-visible contents")
        files-service (files/service {:root root})
        host (support/host)
        plan-id (support/plan-tenure-id host)
        gateway (scripted-gateway
                 [[{:event :completed
                    :text ""
                    :tool-calls [{:id "t1"
                                  :name "cat"
                                  :arguments {:path "a.txt"}}]}]
                  [{:event :completed
                    :text "first report: app-visible contents"}]
                  [{:event :completed
                    :text "second report"}]
                  []])
        runner (app-devops/runner {:model-gateway gateway
                                   :files-service files-service})
        command! (command-port host)
        first-result (app-devops/run-devops!
                      {:runner runner :command! command!}
                      {:tenure-id plan-id
                       :call-id "app-devops-1"
                       :verbose-intent "read a.txt"})
        first-worker (get-in first-result [:receipt :worker-id])
        second-result (app-devops/run-devops!
                       {:runner runner :command! command!}
                       {:tenure-id plan-id
                        :call-id "app-devops-2"
                        :verbose-intent "summarize"})
        worker (get-in (mission/snapshot host)
                       [:bindings :plan-worker :worker])]
    (is (:ok? first-result))
    (is (= :devops-completed (get-in first-result [:receipt :outcome])))
    (is (= :ok (get-in first-result [:receipt :worker-result-status])))
    (is (= "first report: app-visible contents"
           (get-in first-result [:receipt :report])))
    (is (= "second report" (get-in second-result [:receipt :report])))
    (is (= first-worker (get-in second-result [:receipt :worker-id])))
    (is (= :task (:model-role worker)))
    (is (= "task-model" (:model-id worker)))
    (is (= :ok (:last-status worker)))))
