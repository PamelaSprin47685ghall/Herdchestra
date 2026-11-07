(ns hc.server.diagnostics.usage-test
  (:require [clojure.test :refer [deftest is]]
            [hc.server.diagnostics.usage :as usage]))

(defn- request
  [id usage & [extra]]
  (usage/request
   (merge {:request-id id
           :turn-id (str "turn-" id)
           :model "model-a"
           :service-id "service-a"
           :usage usage}
          extra)))

(deftest missing-usage-is-unknown-not-zero
  (let [request (request "r1" nil)
        summary (usage/token-summary request)]
    (is (= :unknown (:input-tokens summary)))
    (is (= :unknown (:output-tokens summary)))
    (is (= :unknown (:cache-read-tokens summary)))
    (is (= :unknown (:total-tokens summary)))
    (is (= :unknown (:total-provenance summary)))
    (is (not= 0 (:input-tokens summary)))
    (is (not= 0 (:total-tokens summary)))))

(deftest cached-input-is-a-subset-not-an-addition
  (let [request (request "r1" {:coverage :final
                               :input-tokens 1000
                               :output-tokens 100
                               :cache-read-tokens 200
                               :cache-write-tokens 0})
        summary (usage/token-summary request)]
    (is (= 1000 (:input-tokens summary)))
    (is (= 200 (:cache-read-tokens summary)))
    (is (= 200 (:cache-total summary)))
    (is (= 1100 (:total-tokens summary)))
    (is (true? (:cache-subset? summary)))
    (is (= :input-subset (:cache-mode summary)))))

(deftest additional-cache-input-is-added-once
  (let [request (request "r1" {:coverage :final
                               :input-tokens 1000
                               :output-tokens 100
                               :cache-read-tokens 200
                               :cache-write-tokens 50}
                         {:cache-mode :additional-input})
        summary (usage/token-summary request)]
    (is (= 1350 (:total-tokens summary)))
    (is (= 250 (:cache-total summary)))
    (is (true? (:cache-additional? summary)))
    (is (= :not-applicable (:cache-subset? summary)))))

(deftest cache-larger-than-input-is-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"cache tokens must not exceed"
       (request "r1" {:coverage :final
                      :input-tokens 100
                      :output-tokens 10
                      :cache-read-tokens 200
                      :cache-write-tokens 0}))))

(deftest provenance-is-per-field-and-aggregated
  (let [reported (request "r1" {:coverage :final
                                :input-tokens 10
                                :output-tokens 5
                                :cache-read-tokens 0
                                :cache-write-tokens 0})
        estimated (request "r2" {:coverage :final
                                 :source :estimated
                                 :input-tokens 20
                                 :output-tokens 10
                                 :cache-read-tokens 0
                                 :cache-write-tokens 0})
        unknown (request "r3" {:coverage :final
                               :input-tokens 7
                               :output-tokens :unknown
                               :cache-read-tokens 0
                               :cache-write-tokens 0})
        summary (usage/summary [reported estimated unknown])]
    (is (= 37 (:input-tokens summary)))
    (is (= :estimated (get-in summary [:provenance :input-tokens])))
    (is (= :unknown (:output-tokens summary)))
    (is (= ["r3"] (get-in summary [:unknown-requests-by-field :output-tokens])))
    (is (not (contains? (:unknown-requests-by-field summary)
                        :input-tokens)))
    (is (= ["r3"] (:unknown-requests-for-total summary)))
    (is (= 1 (:estimated-requests summary)))
    (is (= 1 (:reported-requests summary)))
    (is (= 1 (:unknown-requests summary)))
    (is (false? (:complete? summary)))
    (is (false? (:all-reported? summary)))))

(deftest failed-and-cancelled-usage-stays-countable
  (let [failed (request "r1" {:coverage :final
                              :input-tokens 10
                              :output-tokens 2
                              :cache-read-tokens 0
                              :cache-write-tokens 0}
                        {:result :failed})
        cancelled (request "r2" {:coverage :final
                                 :input-tokens 5
                                 :output-tokens 1
                                 :cache-read-tokens 0
                                 :cache-write-tokens 0}
                           {:result :cancelled})
        summary (usage/summary [failed cancelled])]
    (is (= 2 (:request-count summary)))
    (is (= 15 (:input-tokens summary)))
    (is (= 3 (:output-tokens summary)))
    (is (true? (:complete? summary)))))

(deftest retry-is-a-new-request
  (let [first (request "r1" {:coverage :final
                             :input-tokens 10
                             :output-tokens 2
                             :cache-read-tokens 0
                             :cache-write-tokens 0}
                       {:retry-of nil})
        retry (request "r2" {:coverage :final
                             :input-tokens 11
                             :output-tokens 3
                             :cache-read-tokens 0
                             :cache-write-tokens 0}
                       {:retry-of "r1" :turn-id "turn-r1"})
        turns (usage/turn-summary [first retry])]
    (is (= 2 (:request-count (usage/summary [first retry]))))
    (is (= 1 (get-in turns ["turn-r1" :retry-count])))
    (is (= 2 (get-in turns ["turn-r1" :request-count])))
    (is (= 2 (count (get-in turns ["turn-r1" :requests]))))))

(deftest invalid-cache-mode-is-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"cache mode"
       (usage/request {:request-id "r1" :cache-mode :sometimes}))))

(deftest request-id-is-required
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"request-id"
       (usage/request {:usage {:input-tokens 1}}))))

(deftest normalized-report-with-missing-fields-stays-unknown
  (let [request (usage/request
                 {:request-id "r1"
                  :usage {:coverage :final
                          :usage {:input-tokens 5}
                          :sources {:input-tokens :reported}}})
        summary (usage/token-summary request)]
    (is (= 5 (:input-tokens summary)))
    (is (= :unknown (:output-tokens summary)))
    (is (= :unknown (:total-tokens summary)))
    (is (= :reported (get-in summary [:provenance :input-tokens])))
    (is (= :unknown (get-in summary [:provenance :output-tokens])))))
