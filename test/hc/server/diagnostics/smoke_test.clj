(ns hc.server.diagnostics.smoke-test
  "Runtime smoke: model-port usage -> statistics -> cost -> redacted preview."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.server.diagnostics :as diagnostics]
            [hc.server.model-port.usage :as model-usage])
  (:import [java.time Instant]))

(def openai-key "sk-1234567890abcdefghij")

(deftest end-to-end-statistics-and-diagnostic-preview
  (let [at (.toEpochMilli (Instant/parse "2026-11-03T12:00:00Z"))
        ledger (-> (model-usage/new-ledger)
                   (model-usage/open-attempt {:attempt-id :a1 :call-id :c1})
                   (as-> l (model-usage/record-usage
                            l :a1 {:coverage :final
                                   :input-tokens 1000
                                   :output-tokens 100
                                   :cache-read-tokens 200
                                   :cache-write-tokens 0}))
                   :ledger
                   (as-> l (model-usage/record-finish
                            l :a1 {:finish-reason :stop})))
        aggregate (model-usage/aggregate ledger)
        price (diagnostics/price {:currency "USD"
                                  :input 3
                                  :output 15
                                  :cache-read 0.3
                                  :cache-write 3.75
                                  :source "user"
                                  :source-date "2026-11-01"})
        request (diagnostics/request
                 {:request-id "r1"
                  :turn-id "t1"
                  :model "model-a"
                  :service-id "service-a"
                  :service-name "Example"
                  :at at
                  :price price
                  :usage (assoc (select-keys aggregate
                                             [:input-tokens :output-tokens
                                              :cache-read-tokens
                                              :cache-write-tokens])
                                :coverage :final)})
        summary (diagnostics/statistics
                 {:requests [request]
                  :user-sends [{:send-id "s1" :turn-id "t1" :at at}]
                  :tool-calls [{:tool-call-id "tc1" :turn-id "t1" :at at}]}
                 {:from "2026-11-01" :to "2026-11-03" :zone "UTC"})
        preview (diagnostics/prepare-bundle
                 {:app-version "0.0.1"
                  :platform "linux"
                  :os-version "7.3"
                  :generated-at "2026-11-03T12:00:00Z"
                  :time-range {:from "2026-11-01" :to "2026-11-03"
                               :zone "UTC"}
                  :error-category :rate-limited
                  :error-detail (str "service key " openai-key)
                  :services [{:connection-id "c1"
                              :display-name "Example"
                              :protocol "https"
                              :actual-host "api.example.com"
                              :account-label openai-key}]
                  :mask-hosts? true})
        artifact (diagnostics/local-artifact preview)
        log-policy (diagnostics/enable-log (diagnostics/new-log-policy)
                                           {:now at})
        log-result (diagnostics/record-log
                    log-policy
                    {:request-id "r1"
                     :at at
                     :headers {"Authorization" (str "Bearer " openai-key)}}
                    {:now at})]
    (is (= 1100 (get-in summary [:tokens :total-tokens])))
    (is (= 200 (get-in summary [:tokens :cache-read-tokens])))
    (is (= 0.003960M
           (get-in summary [:cost :by-currency "USD" :estimated-amount])))
    (is (= 1 (get-in summary [:counts :user-sends])))
    (is (= 1 (get-in summary [:counts :model-requests])))
    (is (= 1 (get-in summary [:counts :tool-calls])))
    (is (not (str/includes? (pr-str preview) openai-key)))
    (is (not (str/includes? (pr-str preview) "api.example.com")))
    (is (= :local-only (get-in preview [:transfer :mode])))
    (is (false? (get-in preview [:transfer :automatic-upload?])))
    (is (false? (:uploaded? artifact)))
    (is (false? (:automatic-upload? artifact)))
    (is (true? (:recorded? log-result)))
    (is (not (str/includes? (pr-str (diagnostics/log-entries
                                     (:policy log-result)))
                            openai-key)))))
