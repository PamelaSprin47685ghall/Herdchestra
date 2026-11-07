(ns hc.compose.mission.model-property-test
  "Property tests for role->model ownership: every known role binds to the
  configured class/id, and a mismatched binding is reported."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.mission.model :as model]))

(defspec every-known-role-binds-to-its-configured-model 50
  (prop/for-all [main-id (gen/such-that seq gen/string-alphanumeric 100)
                 task-id (gen/such-that seq gen/string-alphanumeric 100)]
    (let [models (model/new-models {:main main-id :task task-id})]
      (every? (fn [role]
                (let [binding (model/bind-role models role {:tenure-id "t"})]
                  (and (model/binding-valid? models binding)
                       (= (model/model-role-for role) (:model-role binding))
                       (= (model/model-for models role) (:model-id binding)))))
              (keys model/role->model-role)))))

(defspec wrong-model-class-or-id-is-reported 50
  (prop/for-all [role (gen/elements (keys model/role->model-role))]
    (let [models (model/new-models {:main "main" :task "task"})
          binding (model/bind-role models role)
          wrong-class (assoc binding :model-role
                             (if (= :main (:model-role binding)) :task :main))
          wrong-id (assoc binding :model-id "other")]
      (and (seq (model/binding-errors models wrong-class))
           (seq (model/binding-errors models wrong-id))
           (not (model/binding-valid? models wrong-class))
           (not (model/binding-valid? models wrong-id))))))
