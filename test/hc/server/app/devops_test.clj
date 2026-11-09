(ns hc.server.app.devops-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.core.ufss :as ufss]
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
    (let [stat (executor {:name "stat" :arguments {:path "a.txt"}})
          value (ufss/read-str (:text stat))]
      (is (= :ok (:status stat)))
      (is (= "a.txt" (:path value)))
      (is (true? (:exists? value)))
      (is (= :file (:kind value))))
    (let [listing (executor {:name "ls" :arguments {:path ""}})
          value (ufss/read-str (:text listing))]
      (is (= :ok (:status listing)))
      (is (= "a.txt" (get-in value [:entries 0 :name]))))
    (let [where (executor {:name "where"
                           :arguments {:content "hello world"
                                       :needle "world"}})]
      (is (= :ok (:status where)))
      (is (= [6 11] (ufss/read-str (:text where)))))
    (let [where-all (executor {:name "whereAll"
                               :arguments {:content "one and one"
                                           :needle "one"}})]
      (is (= :ok (:status where-all)))
      (is (= [[0 3] [8 11]] (ufss/read-str (:text where-all)))))
    (let [unknown (executor {:name "write" :arguments {:path "x"}})
          error (ufss/read-str (:text unknown))]
      (is (= :failed (:status unknown)))
      (is (= :failed (:status error)))
      (is (str/includes? (:message error) "unknown devops tool write")))
    (let [missing (executor {:name "cat" :arguments {:path "missing.txt"}})
          error (ufss/read-str (:text missing))]
      (is (= :failed (:status missing)))
      (is (= :NOT_FOUND (:code error)))
      (is (= :failed (:status error))))))

(deftest call-helper-results-and-errors-are-edn-ufss
  (let [root (temp-dir "hc-devops-call-")
        service (files/service {:root root})
        executor (:execute! (app-devops/tool-executor
                             {:files-service service
                              :helpers
                              {:call (fn [_] {:stdout "a\nb"
                                              :quoted "say \"hi\""})}}))
        ok (executor {:name "call" :arguments {:name "call"}})
        value (ufss/read-str (:text ok))
        missing (executor {:name "call" :arguments {:name "missing"}})
        error (ufss/read-str (:text missing))]
    (is (= :ok (:status ok)))
    (is (= {:stdout "a\nb" :quoted "say \"hi\""} value))
    (is (= :failed (:status missing)))
    (is (= :failed (:status error)))
    (is (str/includes? (:message error) "no host helper named missing"))
    (is (not (str/includes? (:text ok) "\\\"")))))

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
