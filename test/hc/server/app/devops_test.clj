(ns hc.server.app.devops-test
  (:require [clojure.test :refer [deftest is]]
            [hc.server.app.devops :as app-devops]
            [hc.server.files :as files])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(deftest read-only-tools-use-the-real-files-service
  (let [root (temp-dir "hc-devops-tools-")
        _ (spit (str root "/a.txt") "hello from the workspace")
        service (files/service {:root root})
        executor (:execute! (app-devops/tool-executor {:files-service service}))]
    (is (= {:status :ok :text "hello from the workspace"}
           (executor {:name "cat" :arguments {:path "a.txt"}})))
    (is (= :ok (:status (executor {:name "stat"
                                   :arguments {:path "a.txt"}}))))
    (is (= :ok (:status (executor {:name "ls" :arguments {:path ""}}))))
    (is (= :ok (:status (executor {:name "where"
                                   :arguments {:content "hello world"
                                               :needle "world"}}))))
    (is (= :failed (:status (executor {:name "write"
                                       :arguments {:path "x"}}))))))

(deftest runner-uses-the-real-cat-tool-and-returns-the-final-report
  (let [root (temp-dir "hc-devops-runner-")
        _ (spit (str root "/a.txt") "real file contents")
        service (files/service {:root root})
        starts (atom 0)
        gateway {:start! (fn [request deliver!]
                           (let [index (swap! starts inc)]
                             (future
                               (if (= index 1)
                                 (deliver! {:event :completed
                                            :text ""
                                            :tool-calls [{:id "t1"
                                                          :name "cat"
                                                          :arguments {:path "a.txt"}}]})
                                 (deliver! {:event :completed
                                            :text "report: real file contents"}))))
                           {:handle true})
                 :cancel! (fn [_] nil)}
        runner (app-devops/runner {:model-gateway gateway
                                   :files-service service})
        result ((:run! runner)
                {:type :worker/spawn
                 :worker-id "worker-1"
                 :tenure-id "tenure-1"
                 :call-id "call-1"
                 :verbose-intent "read the file"
                 :model-role :task
                 :model-id "task-model"})]
    (is (= :ok (:status result)))
    (is (= "report: real file contents" (:report result)))))
