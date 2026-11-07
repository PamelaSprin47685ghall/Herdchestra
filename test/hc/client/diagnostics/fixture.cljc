(ns hc.client.diagnostics.fixture
  "Deterministic data for diagnostics model and render tests."
  (:require [hc.client.diagnostics.model :as model]))

(def stats
  {:status :ok
   :empty? false
   :range {:all? false :from "2026-10-05" :to "2026-11-03"}
   :timezone "Asia/Shanghai"
   :zone-mode :query
   :filters {}
   :counts {:user-sends 3 :model-requests 5 :tool-calls 2 :active-days 2}
   :heatmap {"2026-11-03" {:user-sends 2 :model-requests 3 :tool-calls 1}
             "2026-11-02" {:user-sends 1 :model-requests 2 :tool-calls 1}}
   :tokens {:request-count 5
            :input-tokens 1000
            :output-tokens 100
            :cache-read-tokens 200
            :cache-write-tokens 0
            :total-tokens 1100
            :provenance {:input-tokens :reported
                         :output-tokens :reported
                         :cache-read-tokens :reported
                         :cache-write-tokens :reported}
            :total-provenance :reported
            :reported-requests 4
            :estimated-requests 1
            :unknown-requests 0
            :cache-mode :input-subset
            :cache-modes {:input-subset 5}
            :cache-subset? true
            :unknown-requests-by-field {}}
   :cost {:request-count 5
          :estimated-requests 3
          :partial-requests 1
          :unestimated-requests 2
          :actual-requests 1
          :unknown-currency-requests 0
          :invalid-price-requests 0
          :invalid-billing-requests 0
          :currency-mismatch-requests 0
          :by-currency {"USD" {:estimated-amount 0.0123
                               :actual-amount nil
                               :estimated-requests 2
                               :partial-requests 1
                               :unestimated-requests 1
                               :actual-requests 0
                               :pricing-sources #{"user"}
                               :price-dates #{"2026-10-01"}}
                        "EUR" {:estimated-amount 0.004
                               :actual-amount 0.005
                               :estimated-requests 1
                               :partial-requests 0
                               :unestimated-requests 1
                               :actual-requests 1
                               :pricing-sources #{"service"}
                               :price-dates #{"2026-10-02"}}}}
   :service-cost {:usage-count 2
                  :estimated-usages 1
                  :unestimated-usages 1
                  :actual-usages 0
                  :reported-quantity-usages 1
                  :estimated-quantity-usages 0
                  :unknown-quantity-usages 1
                  :unknown-currency-usages 1
                  :invalid-price-usages 0
                  :by-currency {"USD" {:estimated-amount 0.02
                                       :actual-amount nil
                                       :estimated-usages 1
                                       :unestimated-usages 1
                                       :actual-usages 0
                                       :reported-quantity-usages 1
                                       :estimated-quantity-usages 0
                                       :unknown-quantity-usages 1
                                       :units {:per-image {:quantity 2
                                                           :usages 2
                                                           :estimated-amount 0.02}}}}
                  :by-unit {:per-image {:quantity 2 :usages 2
                                        :estimated-amount 0.02}}}
   :models [{:service-id "openai" :service-name "OpenAI" :model "gpt-x"
             :requests 3 :usage {} :cost {}}
            {:service-id "other" :service-name "Other" :model "m-1"
             :requests 1 :usage {} :cost {}}]
   :top-models {:top [{:service-id "openai" :service-name "OpenAI"
                       :model "gpt-x" :requests 3 :usage {} :cost {}}
                      {:service-id "other" :service-name "Other"
                       :model "m-1" :requests 1 :usage {} :cost {}}]
                :other nil}
   :turns [{:turn-id "t1"
            :at 1762185600000
            :counts {:user-sends 1 :model-requests 2 :tool-calls 1}
            :user-send {:assistant-id "assistant-a"
                        :model "gpt-x"
                        :temporary? false}
            :requests [{:request-id "r1"
                        :model "gpt-x"
                        :purpose :chat
                        :result :succeeded
                        :retry? false
                        :started-at 1762185600000
                        :finished-at 1762185601000
                        :usage {:input-tokens 1000
                                :output-tokens 100
                                :cache-read-tokens 200
                                :cache-write-tokens 0
                                :total-tokens 1100
                                :provenance {:input-tokens :reported
                                             :output-tokens :estimated
                                             :cache-read-tokens :reported
                                             :cache-write-tokens :reported}
                                :total-provenance :estimated}
                        :cost {:estimate {:status :complete
                                          :currency "USD"
                                          :amount 0.0123
                                          :price-source "user"}
                               :billed nil}}
                       {:request-id "r2"
                        :model "gpt-x"
                        :purpose :chat
                        :result :succeeded
                        :retry? true
                        :retry-of "r1"
                        :started-at 1762185602000
                        :finished-at 1762185603000
                        :usage {:input-tokens 500
                                :output-tokens 50
                                :cache-read-tokens 100
                                :cache-write-tokens 0
                                :total-tokens 550
                                :provenance {:input-tokens :reported
                                             :output-tokens :reported
                                             :cache-read-tokens :reported
                                             :cache-write-tokens :reported}
                                :total-provenance :reported}
                        :cost {:estimate {:status :complete
                                          :currency "USD"
                                          :amount 0.006M
                                          :price-source "user"}
                               :billed nil}}]
            :tool-calls [{:tool-call-id "tool-1" :name "read" :result :ok
                          :at 1762185600000}]
            :service-usages [{:usage-id "u1" :name "tts" :unit :per-second
                              :quantity 2.5
                              :quantity-source :provider-reported
                              :at 1762185600000
                              :cost {:estimate {:status :complete
                                                :currency "USD"
                                                :amount 0.02}
                                     :billed nil}}]
            :service-usage-count 1
            :retry-count 1
            :failed-count 0}]
   :unknown-time {:user-sends 1 :model-requests 1 :tool-calls 0
                  :service-usages 0 :records {}}
   :excluded-unknown-time {:user-sends 1 :model-requests 1 :tool-calls 0
                           :service-usages 0}
   :last-updated 1762185600000
   :cached? true})

(def bundle
  {:bundle-id "diag-1"
   :generated-at 1762185600000
   :content {:sections []}
   :sections [{:id :app
               :title "Application"
               :lines [{:id "app-version" :label "Version" :value "0.0.1"}
                       {:id "app-platform" :label "Platform" :value "Linux"}
                       {:id "app-os" :label "OS" :value "unknown"}]}
              {:id :services
               :title "Services"
               :lines [{:id "service-0" :label "OpenAI"
                        :value {:connection-id "openai"
                                :display-name "OpenAI"
                                :actual-host "<redacted:host:1>"}}]}
              {:id :redaction
               :title "Redaction"
               :lines [{:id "redaction-review" :label "Review"
                        :value "Confirm this preview contains nothing you do not want to share."}]}]
   :extra-materials [{:id :detailed-log
                      :title "Detailed request log"
                      :purpose "Show request metadata and redacted parameters"
                      :kind :log
                      :include? false}]
   :rejected-materials []
   :redaction {:findings [{:path ["services" 0 "actual-host"]
                           :kind :host
                           :category :host
                           :placeholder "<redacted:host:1>"}]
               :finding-count 1
               :placeholder-count 1
               :redacted? true}
   :excluded ["chat body" "attachments" "secrets" "private paths" "full request"]
   :ignored-fields []
   :review-required? true
   :warning "Review this preview before sharing."
   :transfer {:mode :local-only
              :automatic-upload? false
              :network? false
              :requires-user-action? true
              :destination :user-selected}})

(def log
  {:enabled? false
   :paused? false
   :capturing? false
   :mode :next-request
   :retention-days 7
   :entry-count 0
   :oldest nil
   :newest nil
   :expires-at nil
   :last-cleared-at nil})

(defn state
  []
  (-> (model/initial-state)
      (assoc :can-submit? true :log log)
      (model/apply-receipt
       {:ok? true :receipt {:status :complete :statistics stats}})))
