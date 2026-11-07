(ns hc.compose.mission.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.mission.model :as model]))

(deftest model-ownership-follows-root-invariants
  (testing "Plan, Reviewer, and Steward use the main model"
    (is (= :main (model/model-role-for :plan)))
    (is (= :main (model/model-role-for :reviewer)))
    (is (= :main (model/model-role-for :steward))))
  (testing "the dedicated worker, workers, and holder use the task model"
    (is (= :task (model/model-role-for :plan-worker)))
    (is (= :task (model/model-role-for :worker)))
    (is (= :task (model/model-role-for :holder))))
  (testing "unknown roles have no ownership rule"
    (is (nil? (model/model-role-for :fission)))))

(deftest new-models-requires-both-concrete-ids
  (is (= {:main "m" :task "t"}
         (model/new-models {:main "m" :task "t"})))
  (doseq [bad [nil {} {:main "m"} {:main "m" :task ""}
               {:main " " :task "t"} {:main 1 :task "t"}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (model/new-models bad))
        (pr-str bad))))

(deftest bind-role-records-both-class-and-concrete-id
  (let [models {:main "main-model" :task "task-model"}]
    (is (= {:role :plan
            :model-role :main
            :model-id "main-model"
            :tenure-id "tenure-1"}
           (model/bind-role models :plan {:tenure-id "tenure-1"})))
    (is (= {:role :worker
            :model-role :task
            :model-id "task-model"
            :agent-id :agent-1}
           (model/bind-role models :worker {:agent-id :agent-1})))
    (is (= "main-model" (model/model-for models :reviewer)))
    (is (= "task-model" (model/model-for models :holder)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (model/model-for models :unknown)))))

(deftest binding-errors-detect-model-drift
  (let [models {:main "main-model" :task "task-model"}
        good (model/bind-role models :reviewer {:tenure-id "t"})
        wrong-class (assoc good :model-role :task)
        wrong-id (assoc good :model-id "other")]
    (is (empty? (model/binding-errors models good)))
    (is (= [:MODEL_ROLE_MISMATCH]
           (mapv :code (model/binding-errors models wrong-class))))
    (is (= [:MODEL_ID_MISMATCH]
           (mapv :code (model/binding-errors models wrong-id))))
    (is (= [:BINDING_MISSING]
           (mapv :code (model/binding-errors models nil))))))

(deftest check-models-reports-both-missing-ids
  (is (empty? (model/check-models {:main "m" :task "t"})))
  (is (= #{:MAIN_MODEL_INVALID :TASK_MODEL_INVALID}
         (set (map :code (model/check-models {}))))))
