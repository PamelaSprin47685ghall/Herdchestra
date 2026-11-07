(ns hc.server.diagnostics.service-usage-test
  (:require [clojure.test :refer [deftest is]]
            [hc.server.diagnostics.service-usage :as service-usage]))

(def usage-meta
  {:usage-id "u1" :turn-id "t1" :session-id "s1"
   :service-id "svc1" :name "stt" :at 1234})

(deftest speech-duration-requires-provider-usage
  (let [explicit (service-usage/stt-duration
                  {:usage {:type "duration" :seconds 12.5}} usage-meta)
        missing (service-usage/stt-duration
                 {:text "hello" :duration-ms 12500} usage-meta)]
    (is (= :per-second (:unit explicit)))
    (is (== 12.5 (:quantity explicit)))
    (is (= :provider-reported (:quantity-source explicit)))
    (is (nil? missing))))

(deftest search-credits-require-provider-usage
  (let [explicit (service-usage/search-credits
                  {:usage {:credits 3} :results [{:url "https://example.com"}]}
                  (assoc usage-meta :name "search"))
        missing (service-usage/search-credits
                 {:results [{:url "https://example.com"}]}
                 (assoc usage-meta :name "search"))]
    (is (= :per-credit (:unit explicit)))
    (is (== 3 (:quantity explicit)))
    (is (= :provider-reported (:quantity-source explicit)))
    (is (nil? missing))))

(deftest explicit-quantity-does-not-infer
  (is (nil? (service-usage/explicit-quantity
             {:usage {}} [:usage :images] :per-image usage-meta)))
  (let [record (service-usage/explicit-quantity
                {:usage {:images 2}} [:usage :images] :per-image usage-meta)]
    (is (= :per-image (:unit record)))
    (is (== 2 (:quantity record)))
    (is (= :provider-reported (:quantity-source record)))))

(deftest provider-reported-record-validation
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"usage-id"
                        (service-usage/provider-reported
                         {:unit :per-call :quantity 1
                          :quantity-source :provider-reported})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unit"
                        (service-usage/provider-reported
                         {:usage-id "u1" :unit :per-furlong :quantity 1
                          :quantity-source :provider-reported})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"numeric quantity"
                        (service-usage/provider-reported
                         {:usage-id "u1" :unit :per-call :quantity :unknown
                          :quantity-source :provider-reported}))))
