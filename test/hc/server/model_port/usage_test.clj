(ns hc.server.model-port.usage-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.model-port.usage :as usage]))

(deftest missing-usage-is-unknown-not-zero
  (let [report (usage/report {:coverage :final})]
    (is (= :unknown (get-in report [:usage :input-tokens])))
    (is (= :unknown (get-in report [:usage :output-tokens])))
    (is (= :unknown (get-in report [:usage :cache-read-tokens])))
    (is (= :unknown (get-in report [:usage :cache-write-tokens])))
    (is (= :unknown (get-in report [:sources :input-tokens])))
    (is (not= 0 (get-in report [:usage :input-tokens])))))

(deftest flat-and-nested-reports-agree
  (let [flat (usage/report {:coverage :final
                            :input-tokens 10
                            :output-tokens 4
                            :cache-read-tokens 3})
        nested (usage/report {:coverage :final
                              :usage {:input-tokens 10
                                      :output-tokens 4
                                      :cache-read-tokens 3}})]
    (is (= flat nested))
    (is (= 10 (get-in flat [:usage :input-tokens])))
    (is (= :reported (get-in flat [:sources :input-tokens])))
    (is (= :unknown (get-in flat [:sources :cache-write-tokens])))))

(deftest final-report-replaces-partial-without-addition
  (let [partial (usage/report {:coverage :partial
                               :input-tokens 7
                               :output-tokens 2})
        final (usage/report {:coverage :final
                             :input-tokens 9
                             :output-tokens 3
                             :cache-read-tokens 1})
        merged (usage/merge-report partial final)]
    (is (= :final (:coverage merged)))
    (is (= 9 (get-in merged [:usage :input-tokens])))
    (is (= 3 (get-in merged [:usage :output-tokens])))
    (is (= 1 (get-in merged [:usage :cache-read-tokens])))
    (is (= :unknown (get-in merged [:usage :cache-write-tokens])))
    (is (= :final (:coverage (usage/merge-report partial final))))))

(deftest unknown-final-field-keeps-known-partial
  (let [partial (usage/report {:coverage :partial :input-tokens 7})
        final (usage/report {:coverage :final
                             :input-tokens :unknown
                             :output-tokens 3})
        merged (usage/merge-report partial final)]
    (is (= 7 (get-in merged [:usage :input-tokens])))
    (is (= 3 (get-in merged [:usage :output-tokens])))))

(deftest ledger-retains-failed-attempts-and-costs
  (let [ledger (-> (usage/new-ledger)
                   (usage/open-attempt {:attempt-id :a1 :call-id :c1})
                   (usage/open-attempt {:attempt-id :a2 :call-id :c1}))
        ledger (-> ledger
                   (as-> l (usage/record-usage l :a1 {:coverage :final
                                                      :input-tokens 10
                                                      :output-tokens 2
                                                      :cache-read-tokens 4
                                                      :cache-write-tokens 1}))
                   :ledger
                   (as-> l (usage/record-finish l :a1 {:finish-reason :tool-calls
                                                       :reasoning "I should read the file."
                                                       :continuity "opaque-signature"}))
                   (usage/record-failure :a2 {:kind :server-error :status 503})
                   (as-> l (usage/record-usage l :a2 {:coverage :final
                                                      :input-tokens 8
                                                      :output-tokens :unknown}))
                   :ledger)
        aggregate (usage/aggregate ledger)]
    (is (= 2 (count (:attempts aggregate))))
    (is (= [:a2] (:failed-attempts aggregate)))
    (is (= 18 (:input-tokens aggregate)))
    (is (= :unknown (:output-tokens aggregate)))
    (is (= [:a2] (get-in aggregate [:unknown-attempts :output-tokens])))
    (is (= :unknown (:cache-read-tokens aggregate)))
    (is (= [:a2] (get-in aggregate [:unknown-attempts :cache-read-tokens])))
    (is (= [:tool-calls] (:finish-reasons aggregate)))
    (is (= ["I should read the file."] (:reasoning aggregate)))
    (is (= [{:data "opaque-signature" :opaque? true :editable? false}]
           (:continuity aggregate)))
    (is (false? (:complete? aggregate)))))

(deftest aggregate-without-reports-is-unknown
  (let [ledger (usage/open-attempt (usage/new-ledger)
                                   {:attempt-id :a1 :call-id :c1})
        aggregate (usage/aggregate ledger)]
    (is (= :unknown (:input-tokens aggregate)))
    (is (= :unknown (:output-tokens aggregate)))
    (is (= [:a1] (get-in aggregate [:unknown-attempts :input-tokens])))
    (is (false? (:complete? aggregate)))))

(deftest duplicate-reports-do-not-double-count
  (let [ledger (-> (usage/new-ledger)
                   (usage/open-attempt {:attempt-id :a1 :call-id :c1}))
        first (usage/record-usage ledger :a1 {:coverage :partial
                                              :input-tokens 10
                                              :output-tokens 2})
        second (usage/record-usage (:ledger first) :a1 {:coverage :partial
                                                        :input-tokens 10
                                                        :output-tokens 2})
        final (usage/record-usage (:ledger second) :a1 {:coverage :final
                                                        :input-tokens 10
                                                        :output-tokens 2})
        aggregate (usage/aggregate (:ledger final))]
    (is (= 10 (:input-tokens aggregate)))
    (is (= 2 (:output-tokens aggregate)))
    (is (= :unknown (:cache-read-tokens aggregate)))
    (is (false? (:complete? aggregate)))))

(deftest invalid-values-are-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-negative"
                        (usage/report {:input-tokens -1})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"coverage"
                        (usage/report {:coverage :whole})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source"
                        (usage/report {:input-tokens 1 :source :guessed})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opened attempt"
                        (usage/record-usage (usage/new-ledger) :missing {}))))

(def gen-token
  (gen/one-of [(gen/choose 0 100000) (gen/return :unknown)]))

(defspec merge-is-idempotent 100
  (prop/for-all [input gen-token
                 output gen-token
                 cache-read gen-token]
    (let [partial (usage/report {:coverage :partial
                                 :input-tokens input
                                 :output-tokens output
                                 :cache-read-tokens cache-read})
          final (usage/report {:coverage :final
                               :input-tokens input
                               :output-tokens output
                               :cache-read-tokens cache-read})
          once (usage/merge-report partial final)
          twice (usage/merge-report once final)]
      (= once twice))))

(defspec all-known-attempts-sum-exactly-once 100
  (prop/for-all [a (gen/choose 0 1000)
                 b (gen/choose 0 1000)
                 c (gen/choose 0 1000)
                 d (gen/choose 0 1000)]
    (let [ledger (-> (usage/new-ledger)
                     (usage/open-attempt {:attempt-id :a1 :call-id :c1})
                     (usage/open-attempt {:attempt-id :a2 :call-id :c1}))
          ledger (-> ledger
                     (as-> l (usage/record-usage l :a1 {:coverage :final
                                                        :input-tokens a
                                                        :output-tokens b
                                                        :cache-read-tokens 0
                                                        :cache-write-tokens 0}))
                     :ledger
                     (as-> l (usage/record-usage l :a2 {:coverage :final
                                                        :input-tokens c
                                                        :output-tokens d
                                                        :cache-read-tokens 0
                                                        :cache-write-tokens 0}))
                     :ledger)
          aggregate (usage/aggregate ledger)]
      (and (= (+ a c) (:input-tokens aggregate))
           (= (+ b d) (:output-tokens aggregate))
           (true? (:complete? aggregate))))))
