(ns hc.compose.owner.tasks-test
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.owner.tasks :as tasks]
            [missionary.core :as m]))

(deftest required-steps-succeed-together
  (is (= {:result/status :succeeded :value [1 2]}
         (m/? (tasks/run-required-steps [(m/sp 1) (m/sp 2)])))))

(deftest required-steps-fail-as-data-and-cancel-siblings
  (let [sibling-cancelled (promise)
        slow (m/sp
              (try
                (m/? (m/sleep 5000 :late))
                (finally
                  (deliver sibling-cancelled true))))
        result (m/? (tasks/run-required-steps
                     [(m/sp (throw (ex-info "boom" {:kind :timeout})))
                      slow]))]
    (is (= :failed (:result/status result)))
    (is (= :timeout (get-in result [:result/error :kind])))
    (is (true? (deref sibling-cancelled 2000 false)))))

(deftest independent-tools-keep-sibling-results
  (let [result (m/? (tasks/run-independent-tools
                     [(m/sp (throw (ex-info "boom" {:kind :timeout})))
                      (m/sp 2)]))]
    (is (= :succeeded (:result/status result)))
    (is (= [:failed :succeeded]
           (mapv :result/status (:results result))))
    (is (= 2 (-> result :results second :value)))))

(deftest first-accepted-cancels-the-other-branch
  (let [cancelled (promise)
        slow (m/sp
              (try
                (m/? (m/sleep 5000 :slow))
                (finally
                  (deliver cancelled true))))
        result (m/? (tasks/run-first-accepted
                     [(m/sleep 20 :fast) slow]))]
    (is (= :accepted (:result/status result)))
    (is (= :fast (:value result)))
    (is (= :sibling-accepted (:reason result)))
    (is (true? (deref cancelled 2000 false)))))
