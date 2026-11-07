(ns hc.server.diagnostics.stats-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.server.diagnostics.pricing :as pricing]
            [hc.server.diagnostics.stats :as stats]
            [hc.server.diagnostics.usage :as usage])
  (:import [java.time Instant]))

(defn- ms
  [text]
  (.toEpochMilli (Instant/parse text)))

(defn- request
  [id turn at & [extra]]
  (usage/request
   (merge {:request-id id
           :turn-id turn
           :at at
           :model "model-a"
           :service-id "service-a"
           :usage {:coverage :final
                   :input-tokens 10
                   :output-tokens 2
                   :cache-read-tokens 0
                   :cache-write-tokens 0}}
          extra)))

(defn- send-record
  [id turn at & [extra]]
  (merge {:send-id id :turn-id turn :at at :assistant-id "assistant-a"}
         extra))

(defn- tool
  [id turn at]
  {:tool-call-id id :turn-id turn :at at :name "read"})

(defn- snapshot
  [& {:keys [requests user-sends tool-calls service-usages]}]
  {:requests (or requests [])
   :user-sends (or user-sends [])
   :tool-calls (or tool-calls [])
   :service-usages (or service-usages [])})

(defn- query
  [& {:as overrides}]
  (merge {:from "2026-11-01"
          :to "2026-11-03"
          :zone "UTC"
          :now (ms "2026-11-03T12:00:00Z")}
         overrides))

(deftest counts-are-distinct-facts
  (let [at (ms "2026-11-02T10:00:00Z")
        snapshot (snapshot
                  :requests [(request "r1" "t1" at)
                             (request "r2" "t1" at {:model "model-b"})]
                  :user-sends [(send-record "s1" "t1" at)]
                  :tool-calls [(tool "tc1" "t1" at)
                               (tool "tc2" "t1" at)
                               (tool "tc3" "t1" at)])
        summary (stats/summary snapshot (query))]
    (is (= :ok (:status summary)))
    (is (= 1 (get-in summary [:counts :user-sends])))
    (is (= 2 (get-in summary [:counts :model-requests])))
    (is (= 3 (get-in summary [:counts :tool-calls])))
    (is (= 1 (get-in summary [:counts :active-days])))
    (is (= {:user-sends 1 :model-requests 2 :tool-calls 3}
           (get-in summary [:heatmap "2026-11-02"])))
    (is (= {:user-sends 1 :model-requests 2 :tool-calls 3}
           (get-in summary [:turns 0 :counts])))))

(deftest date-range-is-inclusive
  (let [inside-start (ms "2026-11-01T00:00:00Z")
        inside-end (ms "2026-11-03T23:59:59Z")
        before (ms "2026-10-31T23:59:59Z")
        after (ms "2026-11-04T00:00:00Z")
        summary (stats/summary
                 (snapshot :requests [(request "r1" "t1" inside-start)
                                      (request "r2" "t2" inside-end)
                                      (request "r3" "t3" before)
                                      (request "r4" "t4" after)])
                 (query))]
    (is (= 2 (get-in summary [:counts :model-requests])))
    (is (= ["2026-11-01" "2026-11-03"]
           (keys (:heatmap summary))))))

(deftest timezone-controls-day-buckets
  (let [at (ms "2026-11-03T23:30:00Z")
        snapshot (snapshot :requests [(request "r1" "t1" at)])
        shanghai (stats/summary snapshot (query :zone "Asia/Shanghai"
                                               :to "2026-11-04"))
        utc (stats/summary snapshot (query :zone "UTC" :to "2026-11-04"))]
    (is (= ["2026-11-04"] (keys (:heatmap shanghai))))
    (is (= ["2026-11-03"] (keys (:heatmap utc))))
    (is (= "Asia/Shanghai" (:timezone shanghai)))))

(deftest record-zone-mode-uses-the-record-zone
  (let [at (ms "2026-11-03T23:30:00Z")
        record (request "r1" "t1" at {:occurred-zone "Asia/Shanghai"})
        summary (stats/summary (snapshot :requests [record])
                               (query :zone "UTC"
                                      :zone-mode :record
                                      :to "2026-11-04"))]
    (is (= ["2026-11-04"] (keys (:heatmap summary))))))

(deftest unknown-time-is-never-assigned-to-a-day
  (let [record (request "r1" "t1" nil)
        ranged (stats/summary (snapshot :requests [record]) (query))
        all (stats/summary (snapshot :requests [record])
                           (query :all true :from nil :to nil))]
    (is (= :no-data (:status ranged)))
    (is (zero? (get-in ranged [:counts :model-requests])))
    (is (= 1 (get-in ranged [:unknown-time :model-requests])))
    (is (= 1 (get-in ranged [:excluded-unknown-time :model-requests])))
    (is (= :ok (:status all)))
    (is (= 1 (get-in all [:counts :model-requests])))
    (is (= 1 (get-in all [:unknown-time :model-requests])))
    (is (nil? (:excluded-unknown-time all)))))

(deftest invalid-queries-do-not-fabricate-a-view
  (let [reversed (stats/summary {} (query :from "2026-11-05"))
        bad-zone-id (stats/summary {} (query :zone "Not/AZone"))
        bad-zone (stats/summary {} (query :zone-mode :sometimes))
        bad-top (stats/summary {} (query :top-n -1))]
    (is (= :invalid-query (:status reversed)))
    (is (= :range-reversed (get-in reversed [:error :code])))
    (is (= :invalid-query (:status bad-zone-id)))
    (is (= :zone-invalid (get-in bad-zone-id [:error :code])))
    (is (= :invalid-query (:status bad-zone)))
    (is (= :invalid-query (:status bad-top)))
    (is (nil? (:counts reversed)))))

(deftest filters-apply-to-every-section
  (let [at (ms "2026-11-02T10:00:00Z")
        snapshot (snapshot
                  :requests [(request "r1" "t1" at {:assistant-id "a1"
                                                    :model "m1"
                                                    :service-id "s1"})
                             (request "r2" "t2" at {:assistant-id "a2"
                                                    :model "m2"
                                                    :service-id "s2"})]
                  :user-sends [(send-record "s1" "t1" at {:assistant-id "a1"})
                               (send-record "s2" "t2" at {:assistant-id "a2"})]
                  :tool-calls [(tool "tc1" "t1" at)])
        summary (stats/summary snapshot (query :assistant-id "a1"))]
    (is (= 1 (get-in summary [:counts :user-sends])))
    (is (= 1 (get-in summary [:counts :model-requests])))
    (is (= ["m1"] (mapv :model (:models summary))))
    (is (= 1 (count (:turns summary))))
    (is (= "a1" (get-in summary [:filters :assistant-id])))))

(deftest same-model-name-on-different-services-is-not-merged
  (let [at (ms "2026-11-02T10:00:00Z")
        summary (stats/summary
                 (snapshot :requests
                           [(request "r1" "t1" at {:model "same"
                                                   :service-id "s1"})
                            (request "r2" "t2" at {:model "same"
                                                   :service-id "s2"})])
                 (query))]
    (is (= 2 (count (:models summary))))
    (is (= #{"s1" "s2"} (set (map :service-id (:models summary)))))
    (is (= [1 1] (mapv :requests (:models summary))))))

(deftest no-data-does-not-draw-zero-curves
  (let [summary (stats/summary (snapshot) (query))]
    (is (= :no-data (:status summary)))
    (is (true? (:empty? summary)))
    (is (= {:user-sends 0 :model-requests 0 :tool-calls 0 :active-days 0}
           (:counts summary)))
    (is (nil? (:tokens summary)))
    (is (nil? (:cost summary)))
    (is (= [] (:models summary)))))

(deftest read-failure-is-explicit
  (let [summary (stats/summary {:read-error "disk unreadable"} (query))
        unavailable (stats/unavailable "disk unreadable")]
    (is (= :read-failed (:status summary)))
    (is (true? (:retry? summary)))
    (is (nil? (:counts summary)))
    (is (= :read-failed (:status unavailable)))
    (is (true? (:retry? unavailable)))))

(deftest per-turn-details-keep-retries-visible
  (let [at (ms "2026-11-02T10:00:00Z")
        price (pricing/price {:currency "USD"
                              :input 3
                              :output 15
                              :cache-read 0.3
                              :cache-write 3.75
                              :source "user"
                              :source-date "2026-11-01"})
        initial (request "r1" "t1" at {:price price})
        retry (request "r2" "t1" at {:price price
                                     :retry-of "r1"
                                     :result :failed})
        summary (stats/summary
                 (snapshot :requests [initial retry]
                           :user-sends [(send-record "s1" "t1" at)]
                           :tool-calls [(tool "tc1" "t1" at)])
                 (query))
        turn (first (:turns summary))]
    (is (= 1 (count (:turns summary))))
    (is (= {:user-sends 1 :model-requests 2 :tool-calls 1} (:counts turn)))
    (is (= [false true] (mapv :retry? (get-in turn [:requests]))))
    (is (= "r1" (get-in turn [:requests 1 :retry-of])))
    (is (= 2 (get-in turn [:usage :request-count])))
    (is (= 1 (:failed-count (get-in summary [:turns 0]))))
    (is (contains? (get-in turn [:cost :by-currency]) "USD"))))

(deftest cost-is-grouped-by-currency
  (let [at (ms "2026-11-02T10:00:00Z")
        usd (pricing/price {:currency "USD" :input 1 :output 1
                            :cache-read 0 :cache-write 0})
        eur (pricing/price {:currency "EUR" :input 2 :output 2
                            :cache-read 0 :cache-write 0})
        usage-1m {:coverage :final
                  :input-tokens 1000000
                  :output-tokens 1000000
                  :cache-read-tokens 0
                  :cache-write-tokens 0}
        summary (stats/summary
                 (snapshot :requests
                           [(request "r1" "t1" at {:price usd
                                                   :usage usage-1m})
                            (request "r2" "t2" at {:price eur
                                                   :usage usage-1m})
                            (request "r3" "t3" at {:usage usage-1m})])
                 (query))
        cost (:cost summary)]
    (is (= 2.000000M (get-in cost [:by-currency "USD" :estimated-amount])))
    (is (= 4.000000M (get-in cost [:by-currency "EUR" :estimated-amount])))
    (is (= 2 (count (:by-currency cost))))
    (is (not (contains? cost :total)))
    (is (= 3 (:request-count cost)))
    (is (= 1 (:unestimated-requests cost)))
    (is (= 1 (:unknown-currency-requests cost)))))

(deftest actual-charge-is-separate-from-estimate
  (let [at (ms "2026-11-02T10:00:00Z")
        price (pricing/price {:currency "USD" :input 1 :output 1
                              :cache-read 0 :cache-write 0})
        summary (stats/summary
                 (snapshot :requests
                           [(request "r1" "t1" at
                                     {:price price
                                      :billed {:amount 0.25M
                                               :currency "USD"
                                               :source "service-reported"}
                                      :usage {:coverage :final
                                              :input-tokens 1000000
                                              :output-tokens 0
                                              :cache-read-tokens 0
                                              :cache-write-tokens 0}})])
                 (query))
        usd (get-in summary [:cost :by-currency "USD"])]
    (is (= 1.000000M (:estimated-amount usd)))
    (is (= 0.250000M (:actual-amount usd)))
    (is (= 1 (:estimated-requests usd)))
    (is (= 1 (:actual-requests usd)))))

(deftest currencies-are-never-added-across-a-mismatch
  (let [at (ms "2026-11-02T10:00:00Z")
        price (pricing/price {:currency "USD" :input 1 :output 0
                              :cache-read 0 :cache-write 0})
        summary (stats/summary
                 (snapshot :requests
                           [(request "r1" "t1" at
                                     {:price price
                                      :billed {:amount 0.5M
                                               :currency "EUR"
                                               :source "service-reported"}
                                      :usage {:coverage :final
                                              :input-tokens 1000000
                                              :output-tokens 0
                                              :cache-read-tokens 0
                                              :cache-write-tokens 0}})])
                 (query))
        cost (:cost summary)]
    (is (= 1.000000M
           (get-in cost [:by-currency "USD" :estimated-amount])))
    (is (= 0.500000M
           (get-in cost [:by-currency "EUR" :actual-amount])))
    (is (= 1 (:currency-mismatch-requests cost)))
    (is (not (contains? cost :total)))))

(deftest catalog-price-fills-only-requests-without-a-snapshot
  (let [at (ms "2026-11-02T10:00:00Z")
        request {:request-id "r1" :turn-id "t1" :model "m1"
                 :service-id "s1" :at at :result :succeeded
                 :usage {:coverage :final :input-tokens 1000000
                         :output-tokens 0 :cache-read-tokens 0
                         :cache-write-tokens 0}}
        price {:currency "USD" :unit :per-million-tokens
               :input 2M :output 1M :cache-read 0M :cache-write 0M}
        summary (stats/summary {:requests [request]
                                :prices {["s1" "m1"] price}}
                               (query))]
    (is (= 1 (get-in summary [:cost :by-currency "USD"
                              :estimated-requests])))
    (is (= 2M (get-in summary [:cost :by-currency "USD"
                               :estimated-amount])))))

(deftest service-cost-keeps-mismatched-currencies-separate
  (let [at (ms "2026-11-02T10:00:00Z")
        summary (stats/summary
                 (snapshot :service-usages
                           [{:usage-id "u1" :turn-id "t1" :name "image"
                             :unit :per-image :quantity 2
                             :quantity-source :provider-reported :at at
                             :price {:currency "USD" :unit :per-image
                                     :rate 0.04}
                             :billed {:currency "EUR" :amount 0.05
                                      :source "provider-reported"}}])
                 (query))
        cost (:service-cost summary)]
    (is (= 1 (:currency-mismatch-usages cost)))
    (is (= 0.080000M (get-in cost [:by-currency "USD"
                                   :estimated-amount])))
    (is (= 0.05M (get-in cost [:by-currency "EUR" :actual-amount])))
    (is (nil? (get-in cost [:by-currency "USD" :actual-amount])))))

(deftest request-price-snapshot-is-not-replaced
  (let [at (ms "2026-11-02T10:00:00Z")
        old (pricing/price {:currency "USD" :input 1 :output 0
                            :cache-read 0 :cache-write 0})
        new (pricing/price {:currency "USD" :input 2 :output 0
                            :cache-read 0 :cache-write 0})
        usage-1m {:coverage :final
                  :input-tokens 1000000
                  :output-tokens 0
                  :cache-read-tokens 0
                  :cache-write-tokens 0}
        summary (stats/summary
                 (snapshot :requests
                           [(request "r1" "t1" at {:price old :usage usage-1m})
                            (request "r2" "t2" at {:price new :usage usage-1m})])
                 (query))]
    (is (= 3.000000M
           (get-in summary [:cost :by-currency "USD" :estimated-amount])))))

(deftest unpriced-usage-does-not-become-zero
  (let [at (ms "2026-11-02T10:00:00Z")
        price (pricing/price {:currency "USD" :input 1 :output 1
                              :cache-read 0 :cache-write 0})
        summary (stats/summary
                 (snapshot :requests
                           [(request "r1" "t1" at {:price price :usage {}})])
                 (query))
        usd (get-in summary [:cost :by-currency "USD"])]
    (is (= 1 (:unestimated-requests usd)))
    (is (zero? (:estimated-requests usd)))
    (is (nil? (:estimated-amount usd)))))

(deftest reprice-builds-a-new-view-without-changing-history
  (let [at (ms "2026-11-02T10:00:00Z")
        old-price (pricing/price {:currency "USD" :input 1 :output 0
                                  :cache-read 0 :cache-write 0})
        new-price (pricing/price {:currency "USD" :input 2 :output 0
                                  :cache-read 0 :cache-write 0})
        request (request "r1" "t1" at
                         {:price old-price
                          :usage {:coverage :final
                                  :input-tokens 1000000
                                  :output-tokens 0
                                  :cache-read-tokens 0
                                  :cache-write-tokens 0}})
        catalog (pricing/catalog {["service-a" "model-a"] new-price})
        repriced (stats/reprice [request] catalog)
        old-view (stats/summary (snapshot :requests [request]) (query))
        new-view (stats/summary (snapshot :requests repriced) (query))]
    (is (= old-price (:price request)))
    (is (= new-price (:price (first repriced))))
    (is (= 1.000000M
           (get-in old-view [:cost :by-currency "USD" :estimated-amount])))
    (is (= 2.000000M
           (get-in new-view [:cost :by-currency "USD" :estimated-amount])))))

(deftest duplicate-delivery-is-counted-once
  (let [at (ms "2026-11-02T10:00:00Z")
        record (request "r1" "t1" at)
        summary (stats/summary
                 (snapshot :requests [record record]
                           :user-sends [(send-record "s1" "t1" at)
                                        (send-record "s1" "t1" at)]
                           :tool-calls [(tool "tc1" "t1" at)
                                        (tool "tc1" "t1" at)])
                 (query))]
    (is (= 1 (get-in summary [:counts :user-sends])))
    (is (= 1 (get-in summary [:counts :model-requests])))
    (is (= 1 (get-in summary [:counts :tool-calls])))))

(deftest top-models-can-merge-the-tail
  (let [at (ms "2026-11-02T10:00:00Z")
        summary (stats/summary
                 (snapshot :requests
                           [(request "r1" "t1" at {:model "m1"})
                            (request "r2" "t2" at {:model "m2"})
                            (request "r3" "t3" at {:model "m3"})])
                 (query :top-n 1))]
    (is (= 1 (count (get-in summary [:top-models :top]))))
    (is (= 2 (get-in summary [:top-models :other :requests])))
    (is (= 3 (count (:models summary))))))

(deftest invalid-price-is-reported-not-fatal
  (let [at (ms "2026-11-02T10:00:00Z")
        summary (stats/summary
                 (snapshot :requests
                           [(request "r1" "t1" at
                                     {:price {:currency "" :input 1}})])
                 (query))]
    (is (= :ok (:status summary)))
    (is (= 1 (get-in summary [:cost :invalid-price-requests])))
    (is (= 1 (get-in summary [:cost :unestimated-requests])))
    (is (= 1 (get-in summary [:cost :unknown-currency-requests])))))

(deftest statistics-do-not-copy-chat-or-tool-content
  (let [at (ms "2026-11-02T10:00:00Z")
        summary (stats/summary
                 (snapshot :requests [(request "r1" "t1" at)]
                           :user-sends [(send-record "s1" "t1" at
                                                     {:text "private chat"})]
                           :tool-calls [(assoc (tool "tc1" "t1" at)
                                               :arguments {:secret "tool-secret"})])
                 (query))
        printed (pr-str summary)]
    (is (not (str/includes? printed "private chat")))
    (is (not (str/includes? printed "tool-secret")))
    (is (str/includes? printed "t1"))))


(deftest non-token-service-usage-keeps-unit-and-quantity
  (let [at (ms "2026-11-02T10:00:00Z")
        image {:currency "USD" :unit :per-image :rate 0.04
               :source "user" :source-date "2026-11-01"}
        seconds {:currency "EUR" :unit :per-second :rate 0.01
                 :source "user" :source-date "2026-11-01"}
        summary (stats/summary
                 (snapshot :service-usages
                           [{:usage-id "u1" :turn-id "t1" :name "image"
                             :unit :per-image :quantity 3
                             :quantity-source :provider-reported
                             :at at :price image}
                            {:usage-id "u2" :turn-id "t1" :name "tts"
                             :unit :per-second :quantity 10
                             :quantity-source :provider-reported
                             :at at :price seconds}])
                 (query))
        cost (:service-cost summary)]
    (is (= 2 (:service-usage-count summary)))
    (is (= 0.120000M
           (get-in cost [:by-currency "USD" :estimated-amount])))
    (is (= 0.100000M
           (get-in cost [:by-currency "EUR" :estimated-amount])))
    (is (= 3M (get-in cost [:by-unit :per-image :quantity])))
    (is (= 10M (get-in cost [:by-unit :per-second :quantity])))
    (is (= 2 (get-in summary [:turns 0 :service-usage-count])))
    (is (= 2 (:reported-quantity-usages cost)))
    (is (zero? (:unknown-quantity-usages cost)))
    (is (zero? (get-in summary [:turns 0 :counts :model-requests])))))

(deftest non-token-service-unknown-rate-is-unestimated
  (let [at (ms "2026-11-02T10:00:00Z")
        summary (stats/summary
                 (snapshot :service-usages
                           [{:usage-id "u1" :turn-id "t1" :name "image"
                             :unit :per-image :quantity 2
                             :quantity-source :provider-reported
                             :at at
                             :price {:currency "USD" :unit :per-image
                                     :rate nil}}])
                 (query))
        cost (:service-cost summary)]
    (is (= 1 (:unestimated-usages cost)))
    (is (= 1 (:reported-quantity-usages cost)))
    (is (nil? (get-in cost [:by-currency "USD" :estimated-amount])))
    (is (= 2M (get-in cost [:by-unit :per-image :quantity])))))

(deftest service-quantity-provenance-is-not-invented
  (let [at (ms "2026-11-02T10:00:00Z")
        summary (stats/summary
                 (snapshot :service-usages
                           [{:usage-id "u1" :turn-id "t1" :unit :per-call
                             :quantity 1 :quantity-source :estimated :at at
                             :price {:currency "USD" :unit :per-call :rate 1}}
                            {:usage-id "u2" :turn-id "t1" :unit :per-call
                             :quantity :unknown :quantity-source :unknown
                             :at at
                             :price {:currency "USD" :unit :per-call :rate 1}}])
                 (query))
        cost (:service-cost summary)]
    (is (= 1 (:estimated-quantity-usages cost)))
    (is (= 1 (:unknown-quantity-usages cost)))
    (is (= 0 (:reported-quantity-usages cost)))
    (is (= 1M (get-in cost [:by-unit :per-call :quantity])))))

(deftest unit-filter-applies-to-service-usage
  (let [at (ms "2026-11-02T10:00:00Z")
        summary (stats/summary
                 (snapshot :service-usages
                           [{:usage-id "u1" :turn-id "t1" :unit :per-image
                             :quantity 1 :at at
                             :price {:currency "USD" :unit :per-image
                                     :rate 1}}
                            {:usage-id "u2" :turn-id "t2" :unit :per-call
                             :quantity 1 :at at
                             :price {:currency "USD" :unit :per-call
                                     :rate 2}}])
                 (query :unit :per-call))]
    (is (= 1 (:service-usage-count summary)))
    (is (= 2.000000M
           (get-in summary [:service-cost :by-currency "USD"
                            :estimated-amount])))))


(deftest retry-usage-accumulates-once-with-cache-subset
  (let [at (ms "2026-11-02T10:00:00Z")
        price (pricing/price {:currency "USD" :input 3 :output 15
                              :cache-read 0.3 :cache-write 3.75
                              :source "user" :source-date "2026-11-01"})
        original (request "r1" "t1" at
                          {:price price
                           :usage {:coverage :final
                                   :input-tokens 1000
                                   :output-tokens 100
                                   :cache-read-tokens 200
                                   :cache-write-tokens 0}})
        retry (request "r2" "t1" at
                       {:price price
                        :retry-of "r1"
                        :usage {:coverage :final
                                :input-tokens 500
                                :output-tokens 50
                                :cache-read-tokens 100
                                :cache-write-tokens 0}})
        summary (stats/summary
                 (snapshot :requests [original retry]
                           :user-sends [(send-record "s1" "t1" at)])
                 (query))]
    (is (= 1500 (get-in summary [:tokens :input-tokens])))
    (is (= 150 (get-in summary [:tokens :output-tokens])))
    (is (= 300 (get-in summary [:tokens :cache-read-tokens])))
    (is (= 1650 (get-in summary [:tokens :total-tokens])))
    (is (= 0.005940M
           (get-in summary [:cost :by-currency "USD" :estimated-amount])))
    (is (= 2 (get-in summary [:counts :model-requests])))
    (is (= 1 (get-in summary [:turns 0 :retry-count])))
    (is (= 2 (get-in summary [:turns 0 :counts :model-requests])))))
