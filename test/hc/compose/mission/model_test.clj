(ns hc.compose.mission.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.mission.model :as model]))

(deftest model-ownership-follows-root-invariants
  (testing "Steward, Meditator, Plan, and Reviewer use the main model"
    (is (= :main (model/model-role-for :steward)))
    (is (= :main (model/model-role-for :meditator)))
    (is (= :main (model/model-role-for :plan)))
    (is (= :main (model/model-role-for :reviewer))))
  (testing "main-roles lists every main-model role"
    (is (= #{:steward :meditator :plan :reviewer} model/main-roles))
    (is (not (contains? model/main-roles :worker)))
    (is (not (contains? model/main-roles :holder))))
  (testing "the dedicated worker, workers, and holder use the task model"
    (is (= :task (model/model-role-for :plan-worker)))
    (is (= :task (model/model-role-for :worker)))
    (is (= :task (model/model-role-for :holder))))
  (testing "unknown roles have no ownership rule"
    (is (nil? (model/model-role-for :fission)))))

(deftest stewardship-tool-requirements
  (testing "the Steward needs its program tool; the Meditator does not"
    (is (true? (model/role-requires-tools? :steward)))
    (is (false? (model/role-requires-tools? :meditator)))
    (is (true? (model/program-role-requires-tools? :clj-steward)))
    (is (false? (model/program-role-requires-tools? :clj-meditator))))
  (testing "swarm workers need tools; the dedicated worker owns its own gate"
    ;; A text-only model would leave every swarm primitive unavailable, so
    ;; both the ordinary worker and the holder must be refused before start.
    (is (true? (model/program-role-requires-tools? :clj-worker)))
    (is (true? (model/role-requires-tools? :worker)))
    (is (true? (model/role-requires-tools? :holder)))
    ;; The Plan dedicated worker is not decorated by prompts; its devops
    ;; runner enforces `:required-capabilities [:tools]` itself.
    (is (false? (model/program-role-requires-tools? :dedicated-worker)))
    (is (false? (model/role-requires-tools? :plan-worker))))
  (testing "both roles work when only the main model is configured"
    (let [models {:main "only-main"}
          steward (model/bind-role models :steward)
          meditator (model/bind-role models :meditator)]
      (doseq [binding [steward meditator]]
        (is (= :main (model/model-role-for (:role binding))))
        (is (= :main (:model-role binding)))
        (is (= "only-main" (:model-id binding)))
        (is (empty? (model/binding-errors models binding)))))))

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
