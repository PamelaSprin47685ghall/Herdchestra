(ns hc.server.app.executors-test
  "Owner executor contract for shell publication and retry routing."
  (:require [clojure.test :refer [deftest is]]
            [hc.server.app.executors :as executors]
            [hc.server.shell :as shell]))

(deftest unpublished-completion-is-outcome-unknown-test
  (with-redefs [shell/run! (fn [_ _]
                             {:status :COMPLETED
                              :exit-code 0
                              :published? false
                              :retry :publish-only
                              :request-id "r1"})]
    (let [executor (executors/shell-executor {} (fn [] {}))
          result (executor {:call-id "c1"
                            :attempt-id "a1"
                            :request {:command ["sh" "-c" "true"]}})]
      (is (= :outcome-unknown (:status result)))
      (is (= :publish-only (get-in result [:error :retry])))
      (is (= :publish-only (get-in result [:payload :retry])))
      (is (= "r1" (get-in result [:error :request-id]))))))

(deftest retry-publish-route-does-not-run-shell-test
  (let [retried (atom [])]
    (with-redefs [shell/run! (fn [& _]
                               (throw (ex-info "must not rerun shell" {})))
                  shell/retry-publish!
                  (fn [_ request-id]
                    (swap! retried conj request-id)
                    {:status :COMPLETED
                     :published? true
                     :request-id request-id})]
      (let [executor (executors/shell-executor {} (fn [] {}))
            result (executor {:call-id "c1"
                              :attempt-id "a1"
                              :request {:op :retry-publish
                                        :request-id "r1"}})]
        (is (= :succeeded (:status result)))
        (is (= ["r1"] @retried))))))

(deftest retry-publish-still-pending-is-outcome-unknown-test
  (with-redefs [shell/retry-publish!
                (fn [_ request-id]
                  {:status :COMPLETED
                   :published? false
                   :retry :publish-only
                   :request-id request-id})]
    (let [executor (executors/shell-executor {} (fn [] {}))
          result (executor {:call-id "c1"
                            :attempt-id "a1"
                            :request {:op :retry-publish
                                      :request-id "r1"}})]
      (is (= :outcome-unknown (:status result)))
      (is (= :publish-only (get-in result [:error :retry])))
      (is (= :publish-only (get-in result [:payload :retry]))))))
