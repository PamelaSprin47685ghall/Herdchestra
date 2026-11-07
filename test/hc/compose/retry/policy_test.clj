(ns hc.compose.retry.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.retry.policy :as policy]))

(def fixed
  {:max-attempts 4
   :max-retries 3
   :deadline-ms 60000
   :base-delay-ms 100
   :max-delay-ms 1000
   :jitter-ms 0
   :retry-unknown-effects? false})

(deftest defaults-are-valid
  (let [{:keys [ok? policy]} (policy/normalize-policy {})]
    (is (true? ok?))
    (is (= 4 (:max-attempts policy)))
    (is (= 3 (:max-retries policy)))
    (is (false? (:retry-unknown-effects? policy)))))

(deftest one-budget-derives-the-other
  (let [{:keys [ok? policy]} (policy/normalize-policy {:max-attempts 6})]
    (is (true? ok?))
    (is (= 5 (:max-retries policy))))
  (let [{:keys [ok? policy]} (policy/normalize-policy {:max-retries 0})]
    (is (true? ok?))
    (is (= 1 (:max-attempts policy)))))

(deftest both-budgets-stay-independent
  (let [{:keys [ok? policy]} (policy/normalize-policy
                              {:max-attempts 5 :max-retries 2})]
    (is (true? ok?))
    (is (= 5 (:max-attempts policy)))
    (is (= 2 (:max-retries policy)))))

(deftest invalid-policies-are-refused
  (doseq [bad [{:max-attempts 0}
               {:max-attempts -1}
               {:max-retries -1}
               {:deadline-ms 0}
               {:base-delay-ms -1}
               {:base-delay-ms 10 :max-delay-ms 5}
               {:jitter-ms -1}
               {:retry-unknown-effects? :yes}
               {:max-attempts "4"}]]
    (testing (pr-str bad)
      (is (false? (:ok? (policy/normalize-policy bad))))))
  (is (false? (:ok? (policy/normalize-policy :not-a-map)))))

(deftest backoff-is-exponential-and-capped
  (is (= [100 200 400 800 1000 1000]
         (mapv #(policy/backoff-delay fixed %) (range 1 7))))
  (is (= 0 (policy/backoff-delay
            (assoc fixed :base-delay-ms 0 :max-delay-ms 0) 1)))
  (is (= 0 (policy/backoff-delay
            (assoc fixed :base-delay-ms 0 :max-delay-ms 30000)
            1000000000))
      "a zero base delay never spins over a huge retry number"))

(deftest jitter-is-deterministic-and-bounded
  (let [seed ["call-1" "attempt-1" 1]]
    (is (= (policy/deterministic-jitter 250 seed)
           (policy/deterministic-jitter 250 seed)))
    (is (< -1 (policy/deterministic-jitter 250 seed) 250))
    (is (= 0 (policy/deterministic-jitter 0 seed)))))

(deftest delay-includes-jitter-and-stays-capped
  (is (= 100 (policy/delay-ms fixed 1 0)))
  (is (= 120 (policy/delay-ms fixed 1 20)))
  (is (= 1000 (policy/delay-ms fixed 5 500))))

(defspec delay-is-bounded-and-non-decreasing
  100
  (prop/for-all [retry-number (gen/choose 1 40)
                 jitter (gen/choose 0 500)]
    (let [d1 (policy/delay-ms fixed retry-number jitter)
          d2 (policy/delay-ms fixed (inc retry-number) jitter)]
      (and (<= 0 d1 1000)
           (<= 0 d2 1000)
           (<= d1 d2)))))
