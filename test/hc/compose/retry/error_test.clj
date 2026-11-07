(ns hc.compose.retry.error-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.retry.error :as error]))

(deftest known-kinds
  (doseq [[kind category]
          [[:timeout :transient]
           [:network :transient]
           [:rate-limited :transient]
           [:server-error :transient]
           [:auth :permanent]
           [:model-not-found :permanent]
           [:input-too-long :context]
           [:invalid-tool-arguments :model-correction]
           [:outcome-unknown :reconcile]
           [:user-cancel :cancelled]]]
    (testing (pr-str kind)
      (is (= category (:category (error/classify {:kind kind})))))))

(deftest http-statuses
  (is (= :transient (:category (error/classify {:status 429}))))
  (is (= :transient (:category (error/classify {:status 503}))))
  (is (= :permanent (:category (error/classify {:status 501}))))
  (is (= :permanent (:category (error/classify {:status 505}))))
  (is (= :permanent (:category (error/classify {:status 401}))))
  (is (= :context (:category (error/classify {:status 413}))))
  (is (= :model-correction (:category (error/classify {:status 422}))))
  (is (= :unknown (:category (error/classify {:status 418})))))

(deftest unknown-and-invalid-are-conservative
  (is (= :unknown (:category (error/classify {:kind :mystery}))))
  (is (= :unknown (:category (error/classify {:category :bogus}))))
  (is (= :unknown (:category (error/classify nil))))
  (is (false? (:retryable? (error/classify {:kind :mystery})))))

(deftest explicit-category-wins
  (is (= :transient (:category (error/classify {:category :transient
                                                :kind :auth}))))
  (is (= :permanent (:category (error/classify {:category :permanent
                                                :status 503})))))

(deftest hard-status-vetoes-an-optimistic-category
  (doseq [status [401 403 501 505]]
    (is (= :permanent
           (:category (error/classify {:category :transient
                                       :status status}))))
    (is (= :status-veto
           (:source (error/classify {:category :transient
                                     :status status})))))
  (is (= :transient
         (:category (error/classify {:category :transient
                                     :status 503})))))


(deftest adapter-category-vocabulary
  (doseq [[error category]
          [[{:kind :rate-limited :category :rate-limited
             :disposition :retry :retryable? true}
            :transient]
           [{:kind :server-error :category :server-error
             :disposition :retry :retryable? true}
            :transient]
           [{:kind :network :category :network
             :disposition :retry :retryable? true}
            :transient]
           [{:kind :timeout :category :timeout
             :disposition :retry :retryable? true}
            :transient]
           [{:kind :auth :category :auth
             :disposition :fail :retryable? false}
            :permanent]
           [{:kind :permission :category :permission
             :disposition :fail :retryable? false}
            :permanent]
           [{:kind :insufficient-quota :category :insufficient-quota
             :disposition :fail :retryable? false}
            :permanent]
           [{:kind :invalid-model :category :invalid-model
             :disposition :fail :retryable? false}
            :permanent]
           [{:kind :input-too-long :category :input-too-long
             :disposition :fail :retryable? false}
            :context]
           [{:kind :content-refused :category :content-refused
             :disposition :fail :retryable? false}
            :permanent]
           [{:kind :tool-arguments-invalid :category :tool-arguments-invalid
             :disposition :new-attempt :retryable? false}
            :model-correction]
           [{:kind :outcome-unknown :category :outcome-unknown
             :disposition :reconcile :retryable? false}
            :reconcile]
           [{:kind :user-cancel :category :user-cancel
             :disposition :cancel :retryable? false}
            :cancelled]]]
    (testing (pr-str error)
      (is (= category (:category (error/classify error)))))))

(deftest partial-effect-failures-are-reconcilable
  (doseq [error [{:kind :timeout :category :timeout
                  :disposition :retry :retryable? true
                  :partial? true}
                 {:kind :server-error :category :server-error
                  :disposition :retry :retryable? true
                  :published-call-ids ["call-1"]}
                 {:kind :rate-limited :category :rate-limited
                  :disposition :retry :retryable? true
                  :published-calls [{:call-id "call-1"}]}]]
    (is (= :reconcile (:category (error/classify error))))
    (is (= :partial-effects (:source (error/classify error))))))

(deftest disposition-and-keyword-status-fallbacks
  (is (= :transient (:category (error/classify {:disposition :retry}))))
  (is (= :permanent (:category (error/classify {:disposition :fail}))))
  (is (= :model-correction
         (:category (error/classify {:disposition :new-attempt}))))
  (is (= :reconcile
         (:category (error/classify {:status :outcome-unknown}))))
  (is (= :reconcile
         (:category (error/classify {:outcome :unknown}))))
  (is (= :cancelled
         (:category (error/classify {:status :cancelled}))))
  (is (= :transient
         (:category (error/classify {:status :failed :kind :timeout})))))

(deftest remedies
  (is (= :adjust-context (:remedy (error/classify {:kind :input-too-long}))))
  (is (= :model-correction
         (:remedy (error/classify {:kind :invalid-tool-arguments}))))
  (is (= :reconcile (:remedy (error/classify {:kind :outcome-unknown}))))
  (is (= :none (:remedy (error/classify {:kind :auth})))))

(defspec only-transient-is-retryable
  100
  (prop/for-all [error-map (gen/one-of
                            [(gen/elements [{:kind :timeout}
                                            {:kind :auth}
                                            {:kind :input-too-long}
                                            {:kind :outcome-unknown}
                                            {:kind :user-cancel}
                                            {:kind :mystery}
                                            {:status 429}
                                            {:status 401}])
                             gen/any])]
    (let [c (error/classify error-map)]
      (= (:retryable? c) (= :transient (:category c))))))
