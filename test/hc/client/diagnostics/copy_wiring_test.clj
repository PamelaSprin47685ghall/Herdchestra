(ns hc.client.diagnostics.copy-wiring-test
  "Tests that unknown usage/price wording stays unknown and never becomes 0."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.client.copy.failures :as failures]
            [hc.client.diagnostics.model :as model]))

(deftest unknown-provenance-is-not-zero
  (is (= "未提供" (model/provenance-label :unknown)))
  (is (= "未提供" (model/provenance-label nil))))

(deftest unknown-cost-summary-is-not-zero
  (let [label (model/cost-summary-label {:by-currency {"USD" {}}})]
    (is (= "USD 未估算" label))
    (is (not (str/includes? label "0")))))

(deftest quota-copy-with-unknown-usage-does-not-render-zero
  (let [copy (failures/failure-copy
              {:code :quota-exhausted
               :context {:unknown-usage? true :saved? false}})]
    (is (str/includes? (:body copy) "未知"))
    (is (not (str/includes? (:body copy) "0")))))
