(ns hc.server.model-port.retry-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.model-port.retry :as retry]))

(deftest classification-covers-the-contract-table
  (is (= :retry (:disposition (retry/classify {:kind :network}))))
  (is (= :retry (:disposition (retry/classify {:kind :rate-limited}))))
  (is (= :retry (:disposition (retry/classify {:http-status 503}))))
  (is (= :retry (:disposition (retry/classify {:http-status 429}))))
  (is (= :retry (:disposition (retry/classify {:http-status 408}))))
  (is (= :retry (:disposition (retry/classify {:http-status 425}))))
  (is (= :fail (:disposition (retry/classify {:kind :auth}))))
  (is (= :fail (:disposition (retry/classify {:kind :insufficient-quota}))))
  (is (= :fail (:disposition (retry/classify {:kind :invalid-model}))))
  (is (= :fail (:disposition (retry/classify {:kind :input-too-long}))))
  (is (= :fail (:disposition (retry/classify {:kind :content-refused}))))
  (is (= :new-attempt
         (:disposition (retry/classify {:kind :tool-arguments-invalid}))))
  (is (= :reconcile
         (:disposition (retry/classify {:kind :outcome-unknown}))))
  (is (= :cancel (:disposition (retry/classify {:kind :user-cancel}))))
  (is (= :fail (:disposition (retry/classify {:kind :mystery}))))
  (is (false? (:retryable? (retry/classify {:kind :mystery})))))

(deftest http-status-classification-is-specific
  (testing "413 is a context problem, not a generic bad request"
    (is (= :input-too-long
           (:category (retry/classify {:http-status 413}))))
    (is (false? (:retryable? (retry/classify {:http-status 413})))))
  (testing "425 is a transient upstream condition"
    (is (= :timeout (:category (retry/classify {:http-status 425}))))
    (is (true? (:retryable? (retry/classify {:http-status 425}))))))

(deftest implicit-transport-retries-must-be-declared-off
  (is (true? (:ok (retry/validate-transport {:implicit-retries false}))))
  (is (true? (:ok (retry/validate-transport {:implicit-retries 0}))))
  (is (false? (:ok (retry/validate-transport {}))))
  (is (= :implicit-retries-unknown
         (get-in (retry/validate-transport {}) [:error :kind])))
  (is (false? (:ok (retry/validate-transport {:implicit-retries 2}))))
  (is (= :implicit-retries-enabled
         (get-in (retry/validate-transport {:implicit-retries 2})
                 [:error :kind]))))
