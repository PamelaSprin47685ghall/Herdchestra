(ns hc.server.diagnostics.pricing-test
  (:require [clojure.test :refer [deftest is]]
            [hc.server.diagnostics.pricing :as pricing]))

(def usd
  {:currency "USD"
   :unit :per-million-tokens
   :input 3
   :output 15
   :cache-read 0.3
   :cache-write 3.75
   :source "user"
   :source-date "2026-11-01"})

(deftest per-million-preview-shows-real-magnitude
  (let [preview (pricing/unit-preview usd)
        estimate (:estimate preview)]
    (is (= 18.000000M (:amount estimate)))
    (is (= :complete (:status estimate)))
    (is (= :per-million-tokens (:unit estimate)))
    (is (= 1000000 (get-in preview [:example :input-tokens])))
    (is (= 1000000 (get-in preview [:example :output-tokens])))))

(deftest per-thousand-rate-is-not-mistaken-for-per-million
  (let [estimate (:estimate (pricing/unit-preview
                             {:currency "USD"
                              :input 0.003
                              :output 0.015}))]
    (is (= 0.018000M (:amount estimate)))
    (is (< (:amount estimate) 1M))))

(deftest cached-input-pays-the-cache-rate-only-once
  (let [estimate (pricing/estimate
                  usd
                  {:input-tokens 1000
                   :output-tokens 100
                   :cache-read-tokens 200
                   :cache-write-tokens 0
                   :cache-mode :input-subset})]
    (is (= 0.003960M (:amount estimate)))
    (is (= 0.002400M (get-in estimate [:components :uncached-input])))
    (is (= 0.000060M (get-in estimate [:components :cache-read])))
    (is (= 0.001500M (get-in estimate [:components :output])))
    (is (= :complete (:status estimate)))
    (is (empty? (:missing estimate)))))

(deftest additional-cache-input-is-priced-separately
  (let [estimate (pricing/estimate
                  usd
                  {:input-tokens 1000
                   :output-tokens 100
                   :cache-read-tokens 200
                   :cache-write-tokens 50
                   :cache-mode :additional-input})]
    (is (= 0.004748M (:amount estimate)))
    (is (= :complete (:status estimate)))))

(deftest unknown-cache-price-is-partial-not-free
  (let [estimate (pricing/estimate
                  (assoc usd :cache-read nil)
                  {:input-tokens 1000
                   :output-tokens 100
                   :cache-read-tokens 200
                   :cache-write-tokens 0
                   :cache-mode :input-subset})]
    (is (= :partial (:status estimate)))
    (is (= 0.003900M (:amount estimate)))
    (is (some #{:cache-read-price} (:missing estimate)))
    (is (not= 0 (:amount estimate)))))

(deftest unknown-output-usage-still-prices-known-input
  (let [estimate (pricing/estimate
                  usd
                  {:input-tokens 1000
                   :output-tokens :unknown
                   :cache-read-tokens 0
                   :cache-write-tokens 0
                   :cache-mode :input-subset})]
    (is (= :partial (:status estimate)))
    (is (= 0.003000M (:amount estimate)))
    (is (some #{:output-tokens} (:missing estimate)))))

(deftest no-known-usage-is-unestimated
  (let [estimate (pricing/estimate usd {:cache-mode :input-subset})]
    (is (= :unestimated (:status estimate)))
    (is (nil? (:amount estimate)))
    (is (some #{:input-tokens} (:missing estimate)))
    (is (some #{:output-tokens} (:missing estimate)))))

(deftest price-validation
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"currency"
                        (pricing/price {:currency "" :input 1})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-negative"
                        (pricing/price {:currency "USD" :input -1})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"1,000,000"
                        (pricing/price {:currency "USD"
                                        :unit :per-thousand-tokens
                                        :input 1}))))

(deftest price-is-idempotent
  (let [once (pricing/price usd)]
    (is (= once (pricing/price once)))))

(deftest catalog-keeps-services-separate
  (let [catalog (pricing/catalog
                 {["s1" "same-name"] {:currency "USD" :input 1 :output 2}
                  ["s2" "same-name"] {:currency "EUR" :input 3 :output 4}})]
    (is (= "USD" (:currency (pricing/lookup catalog "s1" "same-name"))))
    (is (= "EUR" (:currency (pricing/lookup catalog "s2" "same-name"))))
    (is (nil? (pricing/lookup catalog "s3" "same-name")))))

(deftest billed-is-not-an-estimate
  (let [billed (pricing/billed {:amount 1.23456789
                                :currency "usd"
                                :source "service-reported"})]
    (is (= 1.234568M (:amount billed)))
    (is (= "USD" (:currency billed)))
    (is (= "service-reported" (:source billed))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"currency"
                        (pricing/billed {:amount 1 :currency ""})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"amount"
                        (pricing/billed {:currency "USD"}))))

(deftest non-token-service-price-preserves-its-unit
  (let [price (pricing/service-price {:currency "usd" :unit :per-image
                                      :rate 0.04 :source "user"
                                      :source-date "2026-11-01"})
        estimate (pricing/estimate-service price 3)]
    (is (= "USD" (:currency price)))
    (is (= :per-image (:unit price)))
    (is (= :complete (:status estimate)))
    (is (= 0.120000M (:amount estimate)))
    (is (= 3M (:quantity estimate)))
    (is (= :per-image (:unit estimate)))))

(deftest non-token-service-unknown-rate-is-not-free
  (let [estimate (pricing/estimate-service
                  {:currency "USD" :unit :per-second :rate nil}
                  10)]
    (is (= :unestimated (:status estimate)))
    (is (nil? (:amount estimate)))
    (is (some #{:rate} (:missing estimate))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unit"
                        (pricing/service-price {:currency "USD"
                                                :unit :per-furlong
                                                :rate 1}))))

(deftest service-unit-preview-uses-one-unit
  (let [preview (pricing/service-unit-preview {:currency "USD"
                                               :unit :per-call
                                               :rate 0.25})]
    (is (= 1 (get-in preview [:example :quantity])))
    (is (= 0.250000M (get-in preview [:estimate :amount])))
    (is (= :per-call (get-in preview [:estimate :unit])))))


(deftest credit-unit-is-preserved-for-search-billing
  (let [price (pricing/service-price {:currency "USD" :unit :per-credit
                                      :rate 0.01})
        estimate (pricing/estimate-service price 5)]
    (is (= :per-credit (:unit price)))
    (is (= 0.050000M (:amount estimate)))
    (is (= :complete (:status estimate)))))
